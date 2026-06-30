package io.teak.sdk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.amazon.device.messaging.ADM;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.io.DefaultAndroidDeviceInfo;
import io.teak.sdk.io.DefaultAndroidNotification;
import io.teak.sdk.io.DefaultAndroidResources;
import io.teak.sdk.io.IAndroidDeviceInfo;
import io.teak.sdk.io.IAndroidNotification;
import io.teak.sdk.io.IAndroidResources;
import io.teak.sdk.push.ADMPushProvider;
import io.teak.sdk.push.FCMPushProvider;
import io.teak.sdk.push.IPushProvider;
import io.teak.sdk.store.IStore;

public class DefaultObjectFactory implements IObjectFactory {
    private final IAndroidResources androidResources;
    private final IStore store;
    private final IAndroidDeviceInfo androidDeviceInfo;
    private final IPushProvider pushProvider;
    private final IAndroidNotification androidNotification;

    DefaultObjectFactory(@NonNull Context context) throws IntegrationChecker.MissingDependencyException {
        IntegrationChecker.requireDependency("androidx.core.app.NotificationCompat");
        IntegrationChecker.requireDependency("androidx.core.app.NotificationManagerCompat");

        this.androidResources = new DefaultAndroidResources(context);
        this.store = createStore(context);
        this.androidDeviceInfo = new DefaultAndroidDeviceInfo(context);
        this.androidNotification = DefaultAndroidNotification.get(context);

        // Future-Pat, this is handled differently because it can be the case where someone just uploads
        // their Google Play build to Amazon, in which case things can go wrong, and Teak should just
        // ignore this instead of disabling itself.
        IPushProvider tempPushProvider = null;
        try {
            tempPushProvider = createPushProvider(context);
        } catch (Exception ignored) {
        }
        this.pushProvider = tempPushProvider;
    }

    ///// IObjectFactory

    @Nullable
    @Override
    public IStore getIStore() {
        return this.store;
    }

    @NonNull
    @Override
    public IAndroidResources getAndroidResources() {
        return this.androidResources;
    }

    @NonNull
    @Override
    public IAndroidDeviceInfo getAndroidDeviceInfo() {
        return this.androidDeviceInfo;
    }

    @Nullable
    @Override
    public IPushProvider getPushProvider() {
        return this.pushProvider;
    }

    ///// Helpers

    static IStore createStore(@NonNull Context context) {
        // If automatic purchase collection is disabled, just return null
        //
        // Note that we cannot use TeakConfiguration here because this happens before it is initialized.
        try {
            final ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (appInfo.metaData.getBoolean("io_teak_no_auto_track_purchase", false)) {
                Teak.log.i("factory.istore", "Automatic purchase tracking disabled (io_teak_no_auto_track_purchase).");
                return null;
            }
        } catch (Exception ignored) {
        }

        final PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) {
            Teak.log.e("factory.istore", "Unable to get Package Manager.");
            return null;
        }

        final String bundleId = context.getPackageName();
        if (bundleId == null) {
            Teak.log.e("factory.istore", "Unable to get Bundle Id.");
            return null;
        }

        // Applicable store, default to GooglePlay
        Class<?> clazz = null;
        if (Helpers.isAmazonDevice(context)) {
            try {
                Class.forName("com.amazon.device.iap.PurchasingListener");
                clazz = Class.forName("io.teak.sdk.store.Amazon");
            } catch (Throwable e) {
                Teak.log.exception(e);
            }
        } else {
            try {
                // Check if the billing library is present at all
                Class<?> billingClientClass = Class.forName("com.android.billingclient.api.BillingClient");

                try {
                    // If the 'BillingClient.queryProductDetailsAsync' method is present
                    // this is Google Play Billing v5 so use that instead.
                    billingClientClass.getMethod("queryProductDetailsAsync");
                    clazz = Class.forName("io.teak.sdk.store.GooglePlayBillingV5");
                } catch (NoSuchMethodException ignored) {
                }

                if (clazz == null) {
                    // Default to Billing v4
                    clazz = Class.forName("io.teak.sdk.store.GooglePlayBillingV4");
                }
            } catch (Throwable e) {
                Teak.log.exception(e);
            }
        }

        if (clazz != null) {
            try {
                return (IStore) clazz.getDeclaredConstructor(Context.class).newInstance(context);
            } catch (Exception e) {
                Teak.log.exception(e);
            }
        }

        return null;
    }

    @SuppressWarnings("WeakerAccess") // Integration tests call this
    public static IPushProvider createPushProvider(@NonNull Context context) throws IntegrationChecker.MissingDependencyException {
        IPushProvider ret = null;
        IntegrationChecker.MissingDependencyException pushCreationException = null;

        try {
            Class.forName("com.amazon.device.messaging.ADM");
            if (new ADM(context).isSupported()) {
                ADMPushProvider admPushProvider = new ADMPushProvider();
                admPushProvider.initialize(context);
                ret = admPushProvider;
                Teak.log.i("factory.pushProvider", Helpers.mm.h("type", "adm"));
            } else {
                Teak.log.i("factory.pushProvider", "ADM is not supported in this context.");
            }
        } catch (Exception ignored) {
            Teak.log.i("factory.pushProvider", "ADM is not present.");
        }

        // When ADM is the selected provider this is an Amazon build, where FCM/Play-Services push
        // libraries cannot work (no Google Play Services). Warn if they were bundled anyway. Probe the
        // FCM messaging class specifically — play-services-base alone can legitimately appear via other
        // Google libs (e.g. advertising id) without a push misconfiguration.
        final boolean admSelected = ret instanceof ADMPushProvider;
        if (admSelected) {
            boolean fcmLibsPresent;
            try {
                Class.forName("com.google.firebase.messaging.FirebaseMessagingService");
                fcmLibsPresent = true;
            } catch (Throwable ignored) {
                fcmLibsPresent = false;
            }
            if (shouldWarnFcmLibsOnAmazon(admSelected, fcmLibsPresent)) {
                IntegrationChecker.addErrorToReport("push.amazon.fcm_libs_bundled",
                    "FCM/Play-Services push libraries are bundled but this build is using ADM (Amazon), where they cannot work. If this is an Amazon-targeted build, remove the FCM/Play-Services libraries. If this is a universal Google+Amazon build, you can ignore this.");
            }
        }

        if (ret == null) {
            try {
                Class.forName("com.google.android.gms.common.GooglePlayServicesUtil");
                if (DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context)) {
                    // No FCM when the Play Services APK is absent — getToken() would throw
                    // MISSING_INSTANCEID_SERVICE. This is the expected outcome, not an error.
                    Teak.log.i("factory.pushProvider", Helpers.mm.h("type", "none"));
                    return null;
                } else {
                    ret = FCMPushProvider.initialize(context);
                    Teak.log.i("factory.pushProvider", Helpers.mm.h("type", "fcm"));
                }
            } catch (IntegrationChecker.MissingDependencyException e) {
                pushCreationException = e;
            } catch (Exception ignored) {
            }
        }

        if (ret == null) {
            Teak.log.e("factory.pushProvider", Helpers.mm.h("type", "none"));
            if (pushCreationException != null) {
                throw pushCreationException;
            }
        }
        return ret;
    }

    // Pure predicate: FCM/Play-Services libraries bundled into an ADM (Amazon) build cannot work —
    // Amazon devices have no Google Play Services. Warn the developer. A universal Google+Amazon APK
    // legitimately bundles both, so this surfaces as a diagnostic rather than failing hard.
    static boolean shouldWarnFcmLibsOnAmazon(boolean admSelected, boolean fcmLibsPresent) {
        return admSelected && fcmLibsPresent;
    }
}
