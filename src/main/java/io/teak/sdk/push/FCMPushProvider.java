package io.teak.sdk.push;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import androidx.annotation.NonNull;
import io.teak.sdk.Helpers;
import io.teak.sdk.IntegrationChecker;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.core.TeakCore;
import io.teak.sdk.event.PushNotificationEvent;
import io.teak.sdk.event.PushRegistrationEvent;

public class FCMPushProvider extends FirebaseMessagingService implements IPushProvider, Unobfuscable {
    private static FCMPushProvider Instance = null;

    private Context context;
    private FirebaseApp firebaseApp;

    private static FCMPushProvider getInstance(@NonNull final Context context) {
        if (Instance == null) {
            Instance = new FCMPushProvider();
            Instance.context = context;
        } else {
            Instance.context = context;

            // Future-Pat: This exception is ignored because getApplicationContext() is not available unless
            // this is the active receiver, and when receivers are chained, then this will throw an exception.
            try {
                if (Instance.getApplicationContext() != Instance.context) {
                    Teak.log.e("google.fcm.initialize.context_mismatch",
                        Helpers.mm.h("getApplicationContext", Instance.getApplicationContext(),
                            "Instance.context", Instance.context));
                }
            } catch (Exception ignored) {
            }
        }

        return FCMPushProvider.Instance;
    }

    public FCMPushProvider() {
        super();
        Instance = this;
    }

    public static FCMPushProvider initialize(@NonNull final Context context) throws IntegrationChecker.MissingDependencyException {
        IntegrationChecker.requireDependency("com.google.firebase.messaging.FirebaseMessagingService");

        if (Instance == null) {
            Teak.log.i("google.fcm.initialize", "Creating new FCMPushProvider instance.");
        } else {
            Teak.log.i("google.fcm.initialize", "FCMPushProvider already created.");
        }

        return FCMPushProvider.getInstance(context);
    }

    public void postEvent(final Context context, final Intent intent) {
        final TeakCore teakCore = TeakCore.getWithoutThrow(context);
        if (teakCore == null) {
            Teak.log.e("google.fcm.null_teak_core", "TeakCore.getWithoutThrow returned null.");
        }
        TeakEvent.postEvent(new PushNotificationEvent(PushNotificationEvent.Received, context, intent));
    }

    /**
     * Determine if the provided notification was sent by Teak.
     *
     * @param remoteMessage The notification received by the active {#FirebaseMessagingService}
     * @return true if Teak sent this notification.
     */
    @SuppressWarnings("unused")
    public static boolean isTeakNotification(RemoteMessage remoteMessage) {
        final Map<String, String> data = remoteMessage.getData();
        return data.containsKey("teakNotifId");
    }

    /**
     * Used to have Teak process a notification received by another {#FirebaseMessagingService}.
     *
     * Note: Teak takes no action if the notification was not sent by Teak.
     *
     * @param remoteMessage The notification received by the active {#FirebaseMessagingService}
     * @param context Application context
     */
    @SuppressWarnings("unused")
    public static void onMessageReceivedExternal(RemoteMessage remoteMessage, Context context) {
        FCMPushProvider.onMessageReceivedExternal(remoteMessage, context, true);
    }

    private static void onMessageReceivedExternal(RemoteMessage remoteMessage, Context context, boolean wasActuallyExternal) {
        Teak.log.i("google.fcm.received", Helpers.mm.h("external", wasActuallyExternal));

        // Future-Pat, the RemoteMessage.toIntent method doesn't seem to exist all the time
        // so don't rely on it.
        final Intent intent = new Intent().putExtras(welcomeToTheBundle(remoteMessage.getData()));
        FCMPushProvider.getInstance(context).postEvent(context, intent);
    }

    //// FirebaseMessagingService

    @Override
    public void onNewToken(@NonNull String token) {
        Teak.log.i("google.fcm.registered", Helpers.mm.h("fcmId", token));
        if (Teak.isEnabled()) {
            final String senderId = this.firebaseApp == null ? null : this.firebaseApp.getOptions().getGcmSenderId();
            TeakEvent.postEvent(new PushRegistrationEvent("gcm_push_key", token, senderId));
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        // Future-Pat, this method will only be invoked via an incoming message,
        // in which case getApplicationContext() will work
        FCMPushProvider.onMessageReceivedExternal(remoteMessage, getApplicationContext(), false);
    }

    //// IPushProvider

    @Override
    public void requestPushKey() {
        if (this.firebaseApp == null) {
            try {
                this.firebaseApp = FirebaseApp.getInstance();
            } catch (Exception ignored) {
            }
        }

        if (this.firebaseApp == null) {
            Teak.log.e("google.fcm.null_app", "Could not get Firebase App. Push notifications are unlikely to work.");
            IntegrationChecker.addErrorToReport("google.fcm.null_app", "Could not get Firebase App. Push notifications are unlikely to work.");
        } else {
            try {
                final Task<String> instanceIdTask = FirebaseMessaging.getInstance().getToken();
                instanceIdTask.addOnSuccessListener(this::onNewToken);

                instanceIdTask.addOnFailureListener(e -> {
                    if (isTransientFcmError(e)) {
                        Teak.log.i("google.fcm.token_failure_transient", Helpers.mm.h("error", e.getMessage()));
                    } else {
                        Teak.log.exception(e);
                    }
                });

                // Log out the Firebase config
                final FirebaseOptions firebaseOptions = this.firebaseApp.getOptions();
                final HashMap<String, Object> fcmConfig = new HashMap<>();
                fcmConfig.put("projectId", firebaseOptions.getProjectId());
                fcmConfig.put("applicationId", firebaseOptions.getApplicationId());
                fcmConfig.put("gcmSenderId", firebaseOptions.getGcmSenderId());
                Teak.log.i("google.fcm.configuration", fcmConfig);
            } catch (Exception e) {
                Teak.log.exception(e);
            }
        }
    }

    // Firebase client contract: SERVICE_NOT_AVAILABLE / MISSING_INSTANCEID_SERVICE / FIS_AUTH_ERROR
    // are delivered as IOException with the code as the message string — no typed getter on the
    // client side, getMessage()-matching is the documented fingerprint.
    // TimeoutException and ExecutionException(transient-cause) are infra-level, not bugs.
    static boolean isTransientFcmError(Exception e) {
        if (e instanceof TimeoutException) return true;
        if (e instanceof ExecutionException) {
            final Throwable cause = e.getCause();
            return cause instanceof Exception && isTransientFcmError((Exception) cause);
        }
        final String msg = e.getMessage();
        return msg != null && (msg.equals("SERVICE_NOT_AVAILABLE") ||
                               msg.equals("MISSING_INSTANCEID_SERVICE") ||
                               msg.equals("FIS_AUTH_ERROR"));
    }

    ///// Beware of the Leopard

    /*
        //
        // com.google.firebase.messaging.RemoteMessage
        //
        public final Map<String, String> getData() {
            if (this.zzdt == null) {
                Bundle var1 = this.zzds;
                ArrayMap var2 = new ArrayMap();
                Iterator var3 = var1.keySet().iterator();

                while(var3.hasNext()) {
                    String var4 = (String)var3.next();
                    Object var5;
                    if ((var5 = var1.get(var4)) instanceof String) {
                        String var6 = (String)var5;
                        if (!var4.startsWith("google.") && !var4.startsWith("gcm.") && !var4.equals("from") && !var4.equals("message_type") && !var4.equals("collapse_key")) {
                            var2.put(var4, var6);
                        }
                    }
                }

                this.zzdt = var2;
            }

            return this.zzdt;
        }
     */

    private static Bundle welcomeToTheBundle(Map<String, String> remoteMessageGetDataResult) {
        final Bundle bundle = new Bundle();
        for (Map.Entry<String, String> entry : remoteMessageGetDataResult.entrySet()) {
            bundle.putString(entry.getKey(), entry.getValue());
        }
        return bundle;
    }
}
