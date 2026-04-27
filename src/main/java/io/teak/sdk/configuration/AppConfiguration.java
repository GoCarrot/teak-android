package io.teak.sdk.configuration;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import io.teak.sdk.Helpers;
import io.teak.sdk.IntegrationChecker;
import io.teak.sdk.Teak;
import io.teak.sdk.io.AndroidResources;
import io.teak.sdk.io.IAndroidResources;
import io.teak.sdk.json.JSONObject;

public class AppConfiguration {
    @SuppressWarnings("WeakerAccess")
    public final String appId;
    @SuppressWarnings("WeakerAccess")
    public final String apiKey;
    @SuppressWarnings("WeakerAccess")
    public final long appVersion;
    @SuppressWarnings("WeakerAccess")
    public final String appVersionName;
    @SuppressWarnings("WeakerAccess")
    public final String bundleId;
    @SuppressWarnings("WeakerAccess")
    public final String installerPackage;
    @SuppressWarnings("WeakerAccess")
    public final String storeId;
    @SuppressWarnings("WeakerAccess")
    public final PackageManager packageManager;
    @SuppressWarnings("WeakerAccess")
    public final Context applicationContext;
    @SuppressWarnings("WeakerAccess")
    public final int targetSdkVersion;
    @SuppressWarnings("WeakerAccess")
    public final boolean traceLog;
    @SuppressWarnings("WeakerAccess")
    public final Set<String> urlSchemes;
    @SuppressWarnings("WeakerAccess")
    public final boolean sdk5Behaviors;
    @SuppressWarnings("WeakerAccess")
    public final String claimMode;

    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_API_KEY_RESOURCE = "io_teak_api_key";
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_APP_ID_RESOURCE = "io_teak_app_id";
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_STORE_ID = "io_teak_store_id";
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_TRACE_LOG_RESOURCE = "io_teak_log_trace";
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_SDK_5_BEHAVIORS = "io_teak_sdk5_behaviors";
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_CLAIM_MODE_RESOURCE = "io_teak_reward_claim_mode";

    @SuppressWarnings("WeakerAccess")
    public static final String DefaultClaimMode = "legacy";

    @SuppressWarnings("WeakerAccess")
    public static final String GooglePlayStoreId = "google_play";
    @SuppressWarnings("WeakerAccess")
    public static final String AmazonStoreId = "amazon";
    @SuppressWarnings("WeakerAccess")
    public static final String SamsungStoreId = "samsung";
    @SuppressWarnings("WeakerAccess")
    public static final Set<String> KnownStoreIds = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        GooglePlayStoreId,
        AmazonStoreId,
        SamsungStoreId)));

    public AppConfiguration(@NonNull Context context, @NonNull IAndroidResources iAndroidResources) throws IntegrationChecker.InvalidConfigurationException {
        this.applicationContext = context.getApplicationContext();

        // Target SDK Version
        {
            int tempTargetSdkVersion = 0;
            try {
                ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                tempTargetSdkVersion = appInfo.targetSdkVersion;
            } catch (Exception ignored) {
            }
            this.targetSdkVersion = tempTargetSdkVersion;
        }

        final AndroidResources androidResources = new AndroidResources(context, iAndroidResources);

        // Teak App Id
        {
            this.appId = androidResources.getTeakStringResource(TEAK_APP_ID_RESOURCE);

            if (this.appId == null) {
                throw new IntegrationChecker.InvalidConfigurationException("Failed to find R.string." + TEAK_APP_ID_RESOURCE);
            } else if (this.appId.trim().length() < 1) {
                throw new IntegrationChecker.InvalidConfigurationException("R.string." + TEAK_APP_ID_RESOURCE + " is empty.");
            }
        }

        // Teak API Key
        {
            this.apiKey = androidResources.getTeakStringResource(TEAK_API_KEY_RESOURCE);

            if (this.apiKey == null) {
                throw new IntegrationChecker.InvalidConfigurationException("Failed to find R.string." + TEAK_API_KEY_RESOURCE);
            } else if (this.apiKey.trim().length() < 1) {
                throw new IntegrationChecker.InvalidConfigurationException("R.string." + TEAK_API_KEY_RESOURCE + " is empty.");
            }
        }

        // Store
        {
            String tempStoreId = androidResources.getTeakStringResource(TEAK_STORE_ID);

            // If Amazon store stuff is available, we must be on Amazon
            if (Helpers.isNullOrEmpty(tempStoreId)) {
                tempStoreId = Helpers.isAmazonDevice(context) ? AppConfiguration.AmazonStoreId : AppConfiguration.GooglePlayStoreId;
            }
            this.storeId = tempStoreId;

            // Warn if we haven't seen this
            if (!AppConfiguration.KnownStoreIds.contains(this.storeId)) {
                android.util.Log.w(IntegrationChecker.LOG_TAG,
                    "Store id '" + this.storeId + "' is not a known value for this version of the Teak SDK. "
                        +
                        "Ignore this warning if you are certain the value is correct.");
            }
        }

        // Package Id
        {
            this.bundleId = context.getPackageName();
            if (this.bundleId == null) {
                throw new RuntimeException("Failed to get Bundle Id.");
            }
        }

        this.packageManager = context.getPackageManager();
        if (this.packageManager == null) {
            throw new RuntimeException("Unable to get Package Manager.");
        }

        // App Version
        {
            long tempAppVersion = 0;
            String tempAppVersionName = null;
            try {
                final PackageInfo info = this.packageManager.getPackageInfo(this.bundleId, 0);
                tempAppVersion = getVersionCodeFromPackageInfo(info);
                tempAppVersionName = info.versionName;
            } catch (Exception e) {
                Teak.log.exception(e);
            } finally {
                this.appVersion = tempAppVersion;
                this.appVersionName = tempAppVersionName;
            }
        }

        // Get the installer package
        {
            this.installerPackage = Helpers.getInstallerPackage(context);
        }

        // Trace log mode
        {
            final Boolean traceLog = androidResources.getTeakBoolResource(TEAK_TRACE_LOG_RESOURCE, false);
            this.traceLog = traceLog != null ? traceLog : false;
        }

        // URL Schemes we care about for deep links
        {
            HashSet<String> urlSchemeSet = new HashSet<>();
            urlSchemeSet.add("teak" + this.appId);
            this.urlSchemes = Collections.unmodifiableSet(urlSchemeSet);
        }

        // SDK 5 Behaviors
        {
            final Boolean sdk5Behaviors = androidResources.getTeakBoolResource(TEAK_SDK_5_BEHAVIORS, true);
            this.sdk5Behaviors = sdk5Behaviors != null ? sdk5Behaviors : true;
        }

        // Claim mode
        {
            final String configuredClaimMode = androidResources.getTeakStringResource(TEAK_CLAIM_MODE_RESOURCE);
            this.claimMode = (configuredClaimMode == null || configuredClaimMode.trim().isEmpty()) ? DefaultClaimMode : configuredClaimMode;
        }
    }

    @SuppressWarnings("deprecation")
    private static long getVersionCodeFromPackageInfo(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        } else {
            return info.versionCode;
        }
    }

    public Map<String, Object> toMap() {
        HashMap<String, Object> ret = new HashMap<>();
        ret.put("appId", this.appId);
        ret.put("apiKey", this.apiKey);
        ret.put("appVersion", this.appVersion);
        ret.put("bundleId", this.bundleId);
        ret.put("installerPackage", this.installerPackage);
        ret.put("storeId", this.storeId);
        ret.put("targetSdkVersion", this.targetSdkVersion);
        ret.put("traceLog", this.traceLog);
        ret.put("sdk5Behaviors", this.sdk5Behaviors);
        ret.put("claimMode", this.claimMode);
        return ret;
    }

    @Override
    @NonNull
    public String toString() {
        try {
            return String.format(Locale.US, "%s: %s", super.toString(), Teak.formatJSONForLogging(new JSONObject(this.toMap())));
        } catch (Exception ignored) {
            return super.toString();
        }
    }
}
