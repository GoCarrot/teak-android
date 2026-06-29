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

    public enum PushProvider {
        ADM,
        FCM,
        NONE
    }

    /**
     * Push-provider selection as a pure function of the three runtime signals, so the decision
     * is unit-testable independently of class loading and device state.
     *
     * <ul>
     *   <li>ADM whenever it is usable (Amazon).</li>
     *   <li>FCM only when the Play Services libraries are present AND Google Play Services is not
     *       definitively missing. Never FCM on a device with no GPS APK ({@code gpsMissing}),
     *       where {@code getToken()} would throw {@code MISSING_INSTANCEID_SERVICE} — this is the
     *       behavioral root fix for FCM-on-Amazon (C-899). Transient GPS states are not "missing",
     *       so real Google devices that merely need an update keep FCM.</li>
     *   <li>Otherwise no provider.</li>
     * </ul>
     */
    public static PushProvider selectPushProvider(boolean admUsable, boolean gpsLibraryPresent, boolean gpsMissing) {
        if (admUsable) {
            return PushProvider.ADM;
        }
        if (gpsLibraryPresent && !gpsMissing) {
            return PushProvider.FCM;
        }
        return PushProvider.NONE;
    }

    @SuppressWarnings("WeakerAccess") // Integration tests call this
    public static IPushProvider createPushProvider(@NonNull Context context) throws IntegrationChecker.MissingDependencyException {
        // Is ADM present and usable on this device?
        boolean admUsable = false;
        try {
            Class.forName("com.amazon.device.messaging.ADM");
            admUsable = new ADM(context).isSupported();
            if (!admUsable) {
                Teak.log.i("factory.pushProvider", "ADM is not supported in this context.");
            }
        } catch (Throwable ignored) {
            Teak.log.i("factory.pushProvider", "ADM is not present.");
        }

        // Are the FCM / Play Services libraries present, and is Google Play Services definitively
        // missing at runtime? The gpsMissing check guards a direct GooglePlayServicesUtil reference,
        // so it only runs once the class is confirmed present.
        boolean gpsLibraryPresent = false;
        boolean gpsMissing = false;
        try {
            Class.forName("com.google.android.gms.common.GooglePlayServicesUtil");
            gpsLibraryPresent = true;
            gpsMissing = DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context);
        } catch (Throwable ignored) {
        }

        IPushProvider ret = null;
        IntegrationChecker.MissingDependencyException pushCreationException = null;
        switch (selectPushProvider(admUsable, gpsLibraryPresent, gpsMissing)) {
            case ADM: {
                ADMPushProvider admPushProvider = new ADMPushProvider();
                admPushProvider.initialize(context);
                ret = admPushProvider;
                Teak.log.i("factory.pushProvider", Helpers.mm.h("type", "adm"));
            } break;
            case FCM: {
                try {
                    ret = FCMPushProvider.initialize(context);
                    Teak.log.i("factory.pushProvider", Helpers.mm.h("type", "fcm"));
                } catch (IntegrationChecker.MissingDependencyException e) {
                    pushCreationException = e;
                }
            } break;
            case NONE:
                break;
        }

        if (ret == null) {
            Teak.log.e("factory.pushProvider", Helpers.mm.h("type", "none"));
            if (pushCreationException != null) {
                throw pushCreationException;
            }
        }
        return ret;
    }
}
