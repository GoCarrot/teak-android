package io.teak.sdk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.net.URLEncoder;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.core.TeakCore;
import io.teak.sdk.core.ThreadFactory;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.event.LogoutEvent;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.event.TrackEventEvent;
import io.teak.sdk.event.UserIdEvent;
import io.teak.sdk.facebook.AccessTokenTracker;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.push.PushState;
import io.teak.sdk.raven.Raven;
import io.teak.sdk.shortcutbadger.ShortcutBadger;
import io.teak.sdk.store.IStore;
import io.teak.sdk.activity.PermissionRequest;

public class TeakInstance implements Unobfuscable {
    public final IObjectFactory objectFactory;
    public final Context context;
    private Activity mainActivity;
    private final TeakCore teakCore;
    private AccessTokenTracker facebookAccessTokenTracker;

    private static final String PREFERENCE_FIRST_RUN = "io.teak.sdk.Preferences.FirstRun";

    @SuppressLint("ObsoleteSdkInt")
    TeakInstance(@NonNull Activity activity, @NonNull final IObjectFactory objectFactory) {
        //noinspection all -- Disable warning on the null check
        if (activity == null) {
            throw new InvalidParameterException("null Activity passed to Teak.onCreate");
        }

        this.context = activity.getApplicationContext();
        setMainActivity(activity);
        this.objectFactory = objectFactory;
        this.teakCore = TeakCore.get();
        PushState.init(this.context);

        // Ravens
        TeakConfiguration.addEventListener(configuration -> {
            TeakInstance.this.sdkRaven = new Raven(context, "sdk", configuration, objectFactory);
            TeakInstance.this.appRaven = new Raven(context, configuration.appConfiguration.bundleId, configuration, objectFactory);
        });

        TeakEvent.addEventListener(event -> {
            if (event.eventType.equals(RemoteConfigurationEvent.Type)) {
                final RemoteConfiguration remoteConfiguration = ((RemoteConfigurationEvent) event).remoteConfiguration;

                if (remoteConfiguration.sdkSentryDsn != null && TeakInstance.this.sdkRaven != null) {
                    TeakInstance.this.sdkRaven.setDsn(remoteConfiguration.sdkSentryDsn);
                }

                if (remoteConfiguration.appSentryDsn != null && TeakInstance.this.appRaven != null) {
                    TeakInstance.this.appRaven.setDsn(remoteConfiguration.appSentryDsn);
                    if (!android.os.Debug.isDebuggerConnected()) {
                        TeakInstance.this.appRaven.setAsUncaughtExceptionHandler();
                    }
                }

                for (Teak.Channel.Category category : remoteConfiguration.categories) {
                    NotificationBuilder.configureNotificationChannelId(TeakInstance.this.context, category);
                }
            }
        });

        // Get Teak Configuration ready
        if (!TeakConfiguration.initialize(context, this.objectFactory)) {
            this.setState(State.Disabled);
            return;
        }

        // Lifecycle callbacks
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            Teak.log.e("api_level", "Teak requires API level 14 to operate. Teak is disabled.");
            this.setState(State.Disabled);
        } else {
            try {
                Application application = activity.getApplication();
                application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
            } catch (Exception e) {
                Teak.log.exception(e);
                this.setState(State.Disabled);
            }
        }
    }

    void setMainActivity(Activity activity) {
        int newHashCode = activity.hashCode();
        Teak.log.i("setMainActivity", Helpers.mm.h(
            "oldHashCode", activityHashCode, "newHashCode", newHashCode, "name", activity.getComponentName().flattenToString()
        ));
        this.mainActivity = activity;
        this.activityHashCode = newHashCode;
    }

    private void cleanup(Activity activity) {
        if (this.appStore != null) {
            this.appStore = null;
        }

        if (this.facebookAccessTokenTracker != null) {
            this.facebookAccessTokenTracker.stopTracking();
            this.facebookAccessTokenTracker = null;
        }

        activity.getApplication().unregisterActivityLifecycleCallbacks(this.lifecycleCallbacks);
    }

    ///// identifyUser

    void identifyUser(final String userId, final Teak.UserConfiguration userConfiguration) {
        if (userId == null || userId.isEmpty()) {
            Teak.log.e("identify_user.error", "User identifier can not be null or empty.");
            return;
        }

        Teak.log.i("identify_user", userId, userConfiguration.toHash());

        if (this.isEnabled()) {
            TeakEvent.postEvent(new UserIdEvent(userId, userConfiguration));
        }
    }

    ///// logout

    void logout() {
        Teak.log.i("logout", new HashMap<>());

        if (this.isEnabled()) {
            TeakEvent.postEvent(new LogoutEvent());
        }
    }

    ///// deleteEmail

    void deleteEmail() {
        Session.whenUserIdIsReadyRun(session -> {
            session.email = null;
            Request.submit(null, "DELETE", "/me/email.json", new HashMap<>(), session, null);
        });
    }

    ///// channels

    public Future<Teak.Channel.Reply> setChannelState(final Teak.Channel.Type channel, final Teak.Channel.State state) {
        final ArrayBlockingQueue<Teak.Channel.Reply> q = new ArrayBlockingQueue<>(1);
        final FutureTask<Teak.Channel.Reply> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);
            }
            return null;
        });
        ThreadFactory.autoStart(ret);

        Session.whenUserIdIsReadyRun((session) -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("channel", channel.name);
            payload.put("state", state.name);
            Request.submit(null, "POST", "/me/channel_state.json", payload,
                session, (int responseCode, String responseBody) -> {
                    try {
                        final JSONObject response = new JSONObject((responseBody == null || responseBody.trim().isEmpty()) ? "{}" : responseBody);

                        final boolean error = !"ok".equalsIgnoreCase(response.optString("status", "error"));
                        final Teak.Channel.State replyState = Teak.Channel.State.fromString(response.optString("state", "unknown"));
                        final Teak.Channel.Type replyType = Teak.Channel.Type.fromString(response.optString("channel", "unknown"));

                        // May get re-assigned
                        Map<String, String[]> replyErrors = null;

                        // If there are errors, marshal them into the correct format
                        final JSONObject errorsJson = response.optJSONObject("errors");
                        if (errorsJson != null) {
                            replyErrors = new HashMap<String, String[]>();
                            for (Iterator<String> it = errorsJson.keys(); it.hasNext();) {
                                final String key = it.next();
                                final JSONArray errorStrings = errorsJson.optJSONArray(key);
                                if (errorStrings != null) {
                                    final String[] array = new String[errorStrings.length()];
                                    int index = 0;
                                    for (Object value : errorStrings) {
                                        array[index] = (String) value;
                                        index++;
                                    }
                                    replyErrors.put(key, array);
                                }
                            }
                        }

                        // Offer to queue
                        q.offer(new Teak.Channel.Reply(error, replyState, replyType, replyErrors));
                    } catch (Exception e) {
                        Teak.log.exception(e);
                        q.offer(new Teak.Channel.Reply(true, Teak.Channel.State.Unknown, channel, Collections.singletonMap("sdk", e.toString().split("\n"))));
                    }
                });
        });
        return ret;
    }

    public Future<Teak.Channel.Reply> setCategoryState(final Teak.Channel.Type channel, final String category, final Teak.Channel.State state) {
        final ArrayBlockingQueue<Teak.Channel.Reply> q = new ArrayBlockingQueue<>(1);
        final FutureTask<Teak.Channel.Reply> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);
            }
            return null;
        });
        ThreadFactory.autoStart(ret);

        Session.whenUserIdIsReadyRun((session) -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("channel", channel.name);
            payload.put("category", category);
            payload.put("state", state.name);
            Request.submit(null, "POST", "/me/category_state.json", payload,
                session, (int responseCode, String responseBody) -> {
                    try {
                        final JSONObject response = new JSONObject((responseBody == null || responseBody.trim().isEmpty()) ? "{}" : responseBody);

                        final boolean error = !"ok".equalsIgnoreCase(response.optString("status", "error"));
                        final Teak.Channel.State replyState = Teak.Channel.State.fromString(response.optString("state", "unknown"));
                        final Teak.Channel.Type replyType = Teak.Channel.Type.fromString(response.optString("channel", "unknown"));
                        final String replyCategory = response.optString("category", null);

                        // May get re-assigned
                        Map<String, String[]> replyErrors = null;

                        // If there are errors, marshal them into the correct format
                        final JSONObject errorsJson = response.optJSONObject("errors");
                        if (errorsJson != null) {
                            replyErrors = new HashMap<String, String[]>();
                            for (Iterator<String> it = errorsJson.keys(); it.hasNext();) {
                                final String key = it.next();
                                final JSONArray errorStrings = errorsJson.optJSONArray(key);
                                if (errorStrings != null) {
                                    final String[] array = new String[errorStrings.length()];
                                    int index = 0;
                                    for (Object value : errorStrings) {
                                        array[index] = (String) value;
                                        index++;
                                    }
                                    replyErrors.put(key, array);
                                }
                            }
                        }

                        // Offer to queue
                        q.offer(new Teak.Channel.Reply(error, replyState, replyType, replyCategory, replyErrors));
                    } catch (Exception e) {
                        Teak.log.exception(e);
                        q.offer(new Teak.Channel.Reply(true, Teak.Channel.State.Unknown, channel, Collections.singletonMap("sdk", e.toString().split("\n"))));
                    }
                });
        });
        return ret;
    }

    public Future<Teak.Notification.Reply> scheduleNotification(final String creativeId, final long delayInSeconds, final Map<String, Object> personalizationData) {
        final ArrayBlockingQueue<Teak.Notification.Reply> q = new ArrayBlockingQueue<>(1);
        final FutureTask<Teak.Notification.Reply> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);
            }
            return null;
        });
        ThreadFactory.autoStart(ret);

        Session.whenUserIdIsOrWasReadyRun((session) -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("identifier", creativeId);
            payload.put("offset", delayInSeconds);
            payload.put("personalization_data", personalizationData);
            Request.submit(null, "POST", "/me/local_notify.json", payload,
                session, (int responseCode, String responseBody) -> {
                    try {
                        final JSONObject response = new JSONObject((responseBody == null || responseBody.trim().isEmpty()) ? "{}" : responseBody);

                        final boolean error = !"ok".equalsIgnoreCase(response.optString("status", "error"));
                        final Teak.Notification.Reply.Status status = Teak.Notification.Reply.Status.fromString(response.optString("status", "unknown"));

                        List<String> scheduleIds = null;
                        if (response.has("event")) {
                            final JSONObject event = response.optJSONObject("event");
                            if (event.has("id")) {
                                scheduleIds = Collections.singletonList(event.get("id").toString());
                            }
                        }

                        // May get re-assigned
                        Map<String, String[]> replyErrors = null;

                        // If there are errors, marshal them into the correct format
                        final JSONObject errorsJson = response.optJSONObject("errors");
                        if (errorsJson != null) {
                            replyErrors = new HashMap<String, String[]>();
                            for (Iterator<String> it = errorsJson.keys(); it.hasNext();) {
                                final String key = it.next();
                                final JSONArray errorStrings = errorsJson.optJSONArray(key);
                                if (errorStrings != null) {
                                    final String[] array = new String[errorStrings.length()];
                                    int index = 0;
                                    for (Object value : errorStrings) {
                                        array[index] = (String) value;
                                        index++;
                                    }
                                    replyErrors.put(key, array);
                                }
                            }
                        }

                        // Offer to queue
                        q.offer(new Teak.Notification.Reply(error, status, replyErrors, scheduleIds));
                    } catch (Exception e) {
                        Teak.log.exception(e);
                        q.offer(new Teak.Notification.Reply(true, Teak.Notification.Reply.Status.Error, Collections.singletonMap("sdk", e.toString().split("\n")), null));
                    }
                });
        });
        return ret;
    }

    ///// trackEvent

    void trackEvent(final String actionId, final String objectTypeId, final String objectInstanceId) {
        trackEvent(actionId, objectTypeId, objectInstanceId, 1);
    }

    void trackEvent(final String actionId, final String objectTypeId, final String objectInstanceId, final long count) {
        if (actionId == null || actionId.isEmpty()) {
            Teak.log.e("track_event.error", "actionId can not be null or empty, ignoring.");
            return;
        }

        if ((objectInstanceId != null && !objectInstanceId.isEmpty()) &&
            (objectTypeId == null || objectTypeId.isEmpty())) {
            Teak.log.e("track_event.error", "objectTypeId can not be null or empty if objectInstanceId is present, ignoring.");
            return;
        }

        if (count < 0) {
            Teak.log.e("track_event.error", "count can not be less than zero, ignoring.");
            return;
        }

        Teak.log.i("track_event", Helpers.mm.h("actionId", actionId, "objectTypeId", objectTypeId, "objectInstanceId", objectInstanceId, "count", count));

        if (this.isEnabled()) {
            final Map<String, Object> payload = TrackEventEvent.payloadForEvent(actionId, objectTypeId, objectInstanceId, count);
            TeakEvent.postEvent(new TrackEventEvent(payload));
        }
    }

    ///// Player profile

    void setNumericAttribute(final String attributeName, final double attributeValue) {
        Session.whenUserIdIsReadyRun(session -> {
            if (session.userProfile != null) {
                session.userProfile.setNumericAttribute(attributeName, attributeValue);
            }
        });
    }

    void setStringAttribute(final String attributeName, final String attributeValue) {
        Session.whenUserIdIsReadyRun(session -> {
            if (session.userProfile != null) {
                session.userProfile.setStringAttribute(attributeName, attributeValue);
            }
        });
    }

    ///// Notifications and Settings

    int getNotificationStatus() {
        return PushState.get().getNotificationStatus();
    }

    @SuppressLint("AnnotateVersionCheck")
    boolean canOpenSettingsAppToThisAppsSettings() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }

    boolean openSettingsAppToThisAppsSettings() {
        boolean ret = false;
        try {
            final Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationBuilder.DEFAULT_NOTIFICATION_CHANNEL_ID);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", this.context.getPackageName());
                intent.putExtra("app_uid", this.context.getApplicationInfo().uid);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.setData(Uri.parse("package:" + this.context.getPackageName()));
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.context.startActivity(intent);
            ret = true;
        } catch (Exception e) {
            Teak.log.exception(e);
        }
        return ret;
    }

    @SuppressLint("AnnotateVersionCheck")
    boolean canOpenNotificationSettings() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    boolean openNotificationSettings(String channelId) {
        if (!this.canOpenNotificationSettings()) return false;

        Intent settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, this.context.getPackageName());
        if (channelId != null) {
            settingsIntent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
        }
        this.context.startActivity(settingsIntent);
        return true;
    }

    ///// Application icon badge

    boolean setApplicationBadgeNumber(int count) {
        try {
            ShortcutBadger.applyCountOrThrow(this.context, count);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    ///// Exception Handling

    Raven sdkRaven;
    private Raven appRaven;

    ///// State Machine

    private enum State {
        Disabled("Disabled"),
        Allocated("Allocated"),
        Created("Created"),
        Active("Active"),
        Paused("Paused"),
        Destroyed("Destroyed");

        //public static final Integer length = 1 + Destroyed.ordinal();

        private static final State[][] allowedTransitions = {
            {},
            {State.Created},
            {State.Active},
            {State.Paused},
            {State.Destroyed, State.Active},
            {State.Created}};

        public final String name;

        State(String name) {
            this.name = name;
        }

        public boolean canTransitionTo(State nextState) {
            if (nextState == State.Disabled) return true;

            for (State allowedTransition : allowedTransitions[this.ordinal()]) {
                if (nextState.equals(allowedTransition)) return true;
            }
            return false;
        }
    }

    private State state = State.Allocated;
    private final Object stateMutex = new Object();

    boolean isEnabled() {
        synchronized (this.stateMutex) {
            return (this.state != State.Disabled);
        }
    }

    private boolean setState(@NonNull State newState) {
        synchronized (this.stateMutex) {
            if (this.state == newState) {
                Teak.log.i("teak.state_duplicate", String.format("Teak State transition to same state (%s). Ignoring.", this.state));
                return false;
            }

            if (!this.state.canTransitionTo(newState)) {
                Teak.log.e("teak.state_invalid", String.format("Invalid Teak State transition (%s -> %s). Ignoring.", this.state, newState));
                return false;
            }

            Teak.log.i("teak.state", Helpers.mm.h("old_state", this.state.name, "state", newState.name));

            this.state = newState;

            return true;
        }
    }

    ///// Purchase Tracking

    private IStore appStore;

    ///// Activity Lifecycle

    private int activityHashCode;

    private void lifecycleTrace(String method, Activity activity) {
        Teak.log.i("lifecycle_trace", Helpers.mm.h(
            "callback", method, "name", activity.getComponentName().flattenToString(),
            "ourHashCode", activityHashCode, "theirHashCode", activity.hashCode()
        ));
    }

    // Needs to be public for TeakInitProvider
    @SuppressWarnings("WeakerAccess")
    public final Application.ActivityLifecycleCallbacks lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(Activity activity, Bundle bundle) {
            lifecycleTrace("onActivityCreated", activity);
            if (activity.hashCode() == activityHashCode && setState(State.Created)) {
                Teak.log.i("lifecycle", Helpers.mm.h("callback", "onActivityCreated"));

                final Context context = activity.getApplicationContext();

                // Get IStore
                appStore = objectFactory.getIStore();

                // Facebook Access Token Tracker
                // This will be removed in SDK 5
                final TeakConfiguration teakConfiguration = TeakConfiguration.get();
                if (!teakConfiguration.appConfiguration.sdk5Behaviors) {
                    try {
                        Class.forName("com.facebook.AccessTokenTracker");
                        TeakInstance.this.facebookAccessTokenTracker = new AccessTokenTracker();
                    } catch (Exception ignored) {
                    }
                }

                Intent intent = activity.getIntent();
                if (intent == null) {
                    intent = new Intent();
                }

                if (!TeakEvent.postEvent(new LifecycleEvent(LifecycleEvent.Created, intent, context))) {
                    cleanup(activity);
                    setState(State.Disabled);
                } else {
                    // Set up the /teak_internal/* routes
                    registerTeakInternalDeepLinks();
                }
            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            lifecycleTrace("onActivityResumed", activity);
            if (activity.hashCode() == activityHashCode && setState(State.Active)) {
                Teak.log.i("lifecycle", Helpers.mm.h("callback", "onActivityResumed"));
                Intent intent = activity.getIntent();
                if (intent == null) {
                    intent = new Intent();
                }

                // Check and see if this is (probably) the first time this app has been ever launched
                boolean isFirstLaunch = false;
                SharedPreferences preferences;
                try {
                    preferences = activity.getSharedPreferences(Teak.PREFERENCES_FILE, Context.MODE_PRIVATE);
                    if (preferences != null) {
                        long firstLaunch = preferences.getLong(PREFERENCE_FIRST_RUN, 0);
                        if (firstLaunch == 0) {
                            firstLaunch = new Date().getTime() / 1000;
                            synchronized (Teak.PREFERENCES_FILE) {
                                SharedPreferences.Editor editor = preferences.edit();
                                editor.putLong(PREFERENCE_FIRST_RUN, firstLaunch);
                                editor.apply();
                            }
                            isFirstLaunch = true;
                        }
                    }
                } catch (Exception ignored) {
                }
                intent.putExtra("teakIsFirstLaunch", isFirstLaunch);

                TeakEvent.postEvent(new LifecycleEvent(LifecycleEvent.Resumed, intent, activity.getApplicationContext()));
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            lifecycleTrace("onActivityPaused", activity);
            if (activity.hashCode() == activityHashCode && setState(State.Paused)) {
                Teak.log.i("lifecycle", Helpers.mm.h("callback", "onActivityPaused"));
                Intent intent = activity.getIntent();
                if (intent == null) {
                    intent = new Intent();
                }
                TeakEvent.postEvent(new LifecycleEvent(LifecycleEvent.Paused, intent, activity.getApplicationContext()));
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            lifecycleTrace("onActivityDestroyed", activity);
            if (activity.hashCode() == activityHashCode && setState(State.Destroyed)) {
                Teak.log.i("lifecycle", Helpers.mm.h("callback", "onActivityDestroyed"));

                if (TeakInstance.this.facebookAccessTokenTracker != null) {
                    TeakInstance.this.facebookAccessTokenTracker.stopTracking();
                }
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
            lifecycleTrace("onActivityStarted", activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            lifecycleTrace("onActivityStopped", activity);
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
            // None
        }
    };

    public void requestNotificationPermissions() {
        Teak.log.i("instance.requestNotificationPermissions", "trace");
        // Android pre 13 has no notification permissions.
        if(Build.VERSION.SDK_INT < 33) {
            Teak.log.i("instance.requestNotificationPermissions", "Android < 13");
            return;
        }

        int permissionInfo = ContextCompat.checkSelfPermission(
            this.context, Teak.NOTIFICATION_PERMISSION
        );

        if (permissionInfo == PackageManager.PERMISSION_GRANTED) {
            Teak.log.i("instance.requestNotificationPermissions", "Permission  granted");
            return;
        }

        Teak.log.i("instance.requestNotificationPermissions", "requesting intent");
        Intent intent = new Intent(this.context, PermissionRequest.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        this.mainActivity.startActivity(intent);
    }

    ///// Permissions handling
    public void onNotificationPermissionResult(boolean result) {

    }

    ///// Built-in deep links

    private void registerTeakInternalDeepLinks() {
        Teak.registerDeepLink("/teak_internal/companion", "", "", new Teak.DeepLink() {
            @Override
            public void call(Map<String, Object> params) {
                Session.whenUserIdIsReadyRun(session -> {
                    final TeakConfiguration teakConfiguration = TeakConfiguration.get();
                    String key;
                    String value;
                    try {
                        JSONObject params1 = new JSONObject();
                        params1.put("user_id", session.userId());
                        params1.put("device_id", teakConfiguration.deviceConfiguration.deviceId);
                        key = "response";
                        value = params1.toString();
                    } catch (Exception e) {
                        key = "error";
                        value = e.toString();
                        Teak.log.exception(e);
                    }

                    try {
                        final String uriString = "teak:///callback?" + key + "=" + URLEncoder.encode(value, "UTF-8");
                        final Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriString));
                        uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        final List<ResolveInfo> resolvedActivities = teakConfiguration.appConfiguration.packageManager.queryIntentActivities(uriIntent, 0);
                        for (ResolveInfo info : resolvedActivities) {
                            if ("io.teak.app.Teak".equalsIgnoreCase(info.activityInfo.packageName)) {
                                teakConfiguration.appConfiguration.applicationContext.startActivity(uriIntent);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                });
            }
        });

        Teak.registerDeepLink("/teak_internal/app_settings", "", "", new Teak.DeepLink() {
            @Override
            public void call(Map<String, Object> params) {
                if (TeakInstance.this.getNotificationStatus() == Teak.TEAK_NOTIFICATIONS_DISABLED) {
                    TeakInstance.this.openSettingsAppToThisAppsSettings();
                }
            }
        });
    }
}
