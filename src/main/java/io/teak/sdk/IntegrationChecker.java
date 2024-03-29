package io.teak.sdk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.ThreadFactory;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.io.ManifestParser;

public class IntegrationChecker {
    public static final String LOG_TAG = "Teak.Integration";

    private final Activity activity;

    private static boolean enhancedIntegrationChecks = false;
    private static final Map<String, String> errorsToReport = new HashMap<>();

    public static final String[][] dependencies = new String[][] {
        new String[] {"androidx.core.app.NotificationCompat", "androidx.core:core:1.0.+"},
        new String[] {"androidx.core.app.NotificationManagerCompat", "androidx.core:core:1.0.+"},
        new String[] {"com.google.android.gms.common.GooglePlayServicesUtil", "com.google.android.gms:play-services-base:16+", "com.google.android.gms:play-services-basement:16+"},
        new String[] {"com.google.firebase.messaging.FirebaseMessagingService", "com.google.firebase:firebase-messaging:17+"},
        new String[] {"com.google.android.gms.ads.identifier.AdvertisingIdClient", "com.google.android.gms:play-services-ads:16+"},
        new String[] {"androidx.work.Worker", "androidx.work:work-runtime:2.5.0"}};

    public static final String[] permissionFeatures = new String[] {
        "shortcutbadger"};
    public static final String[][] permissions = new String[][] {
        new String[] {
            "com.sec.android.provider.badge.permission.READ",
            "com.sec.android.provider.badge.permission.WRITE",
            "com.htc.launcher.permission.READ_SETTINGS",
            "com.htc.launcher.permission.UPDATE_SHORTCUT",
            "com.sonyericsson.home.permission.BROADCAST_BADGE",
            "com.sonymobile.home.permission.PROVIDER_INSERT_BADGE",
            "com.anddoes.launcher.permission.UPDATE_COUNT",
            "com.majeur.launcher.permission.UPDATE_BADGE",
            "com.huawei.android.launcher.permission.CHANGE_BADGE",
            "com.huawei.android.launcher.permission.READ_SETTINGS",
            "com.huawei.android.launcher.permission.WRITE_SETTINGS",
            "android.permission.READ_APP_BADGE",
            "com.oppo.launcher.permission.READ_SETTINGS",
            "com.oppo.launcher.permission.WRITE_SETTINGS",
            "me.everything.badger.permission.BADGE_COUNT_READ",
            "me.everything.badger.permission.BADGE_COUNT_WRITE"}};

    public static final String[] configurationStrings = new String[] {
        AppConfiguration.TEAK_API_KEY_RESOURCE,
        AppConfiguration.TEAK_APP_ID_RESOURCE};

    public static void requireDependency(@NonNull String fullyQualifiedClassName) throws MissingDependencyException {
        addDependency(fullyQualifiedClassName, true);
    }

    public static void suggestDependency(@NonNull String fullyQualifiedClassName) throws MissingDependencyException {
        addDependency(fullyQualifiedClassName, false);
    }

    private static void addDependency(@NonNull String fullyQualifiedClassName, boolean required) throws MissingDependencyException {
        // Protect against future-Pat adding/removing a dependency and forgetting to update the array
        if (BuildConfig.DEBUG) {
            boolean foundInDependencies = false;
            for (String[] dependency : dependencies) {
                if (fullyQualifiedClassName.equals(dependency[0])) {
                    foundInDependencies = true;
                    break;
                }
            }
            if (!foundInDependencies) {
                final String errorText = "Missing '" + fullyQualifiedClassName + "' in dependencies list.";
                Teak.log.e("dependency.missing_source", errorText);
                throw new NoClassDefFoundError(errorText);
            }
        }

        try {
            Class.forName(fullyQualifiedClassName);
        } catch (ClassNotFoundException e) {
            final String dependency = "Missing dependency: " + fullyQualifiedClassName;
            if (required) {
                addErrorToReport("dependency.required", dependency);
                throw new MissingDependencyException(e);
            } else {
                addErrorToReport("dependency.optional", dependency);
            }
        }
    }

    public static class InvalidConfigurationException extends Exception implements Unobfuscable {
        public InvalidConfigurationException(@NonNull String message) {
            super(message);
        }
    }

    public static class UnsupportedVersionException extends Exception implements Unobfuscable {
        public UnsupportedVersionException(@NonNull String message) {
            super(message);
        }
    }

    public static class MissingDependencyException extends ClassNotFoundException implements Unobfuscable {
        public final String[] missingDependency;

        public MissingDependencyException(@NonNull ClassNotFoundException e) {
            super();

            // See what the missing dependency is
            String[] foundMissingDependencies = null;
            for (String[] dependency : dependencies) {
                if (e.getMessage().contains(dependency[0])) {
                    foundMissingDependencies = dependency;
                    break;
                }
            }
            this.missingDependency = foundMissingDependencies;

            // Add to list that will get reported during debug
            if (this.missingDependency != null) {
                addErrorToReport(this.missingDependency[0],
                    "Missing dependencies: " + Helpers.join(", ",
                                                   Arrays.copyOfRange(this.missingDependency, 1, this.missingDependency.length)));
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private static IntegrationChecker integrationChecker;

    static boolean init(@NonNull Activity activity) {
        if (integrationChecker == null || integrationChecker.activity != activity) {
            try {
                integrationChecker = new IntegrationChecker(activity);
                return true;
            } catch (Exception e) {
                android.util.Log.e(LOG_TAG, android.util.Log.getStackTraceString(e));
                Teak.log.exception(e);
            }
        }
        return false;
    }

    private IntegrationChecker(@NonNull Activity activity) throws UnsupportedVersionException, InvalidConfigurationException {
        this.activity = activity;

        // Teak 2.0+ requires targeting Android 26+
        if (Helpers.getTargetSDKVersion(activity) < Build.VERSION_CODES.P) {
            throw new UnsupportedVersionException("Teak only supports targetSdkVersion 28 or higher.");
        }

        // Check for configuration strings
        try {
            final String packageName = this.activity.getPackageName();
            final ApplicationInfo appInfo = this.activity.getPackageManager().getApplicationInfo(this.activity.getPackageName(), PackageManager.GET_META_DATA);
            final Bundle metaData = appInfo.metaData;

            for (String configString : configurationStrings) {
                try {
                    // First try AndroidManifest meta data
                    boolean foundConfigString = false;
                    if (metaData != null) {
                        final String appIdFromMetaData = metaData.getString(configString);
                        if (appIdFromMetaData != null && appIdFromMetaData.startsWith("teak")) {
                            foundConfigString = true;
                        }
                    }

                    // Default to resource id
                    if (!foundConfigString) {
                        int resId = this.activity.getResources().getIdentifier(configString, "string", packageName);
                        this.activity.getString(resId);
                    }
                } catch (Exception ignored) {
                    throw new InvalidConfigurationException("Failed to find R.string." + configString);
                }
            }
        } catch (InvalidConfigurationException e) {
            throw e;
        } catch (Exception ignored) {
        }

        // Run checks on a background thread
        // Activity launch mode should be 'singleTask', 'singleTop' or 'singleInstance'
        ThreadFactory.autoStart(this::checkActivityLaunchMode);

        TeakEvent.addEventListener(event -> {
            if (event.eventType.equals(RemoteConfigurationEvent.Type)) {
                final RemoteConfiguration remoteConfiguration = ((RemoteConfigurationEvent) event).remoteConfiguration;
                onRemoteConfigurationReady(remoteConfiguration);
            }
        });

        // If < API 26, the manifest checker will work properly, otherwise it will not
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            TeakConfiguration.addEventListener(configuration -> ThreadFactory.autoStart(this::checkAndroidManifest));
        }
    }

    private void checkActivityLaunchMode() {
        // Check the launch mode of the activity for debugging purposes
        try {
            Teak.log.i("integration.launchMode", "Checking android:launchMode for main <activity>.");
            ComponentName cn = new ComponentName(this.activity, this.activity.getClass());
            ActivityInfo ai = this.activity.getPackageManager().getActivityInfo(cn, PackageManager.GET_META_DATA);
            // (LAUNCH_SINGLE_INSTANCE == LAUNCH_SINGLE_TASK | LAUNCH_SINGLE_TOP) but let's not
            // assume that those values will stay the same
            if ((ai.launchMode & ActivityInfo.LAUNCH_SINGLE_INSTANCE) == 0 &&
                (ai.launchMode & ActivityInfo.LAUNCH_SINGLE_TASK) == 0 &&
                (ai.launchMode & ActivityInfo.LAUNCH_SINGLE_TOP) == 0) {
                addErrorToReport("activity.launchMode", "The android:launchMode of this <activity> is not set to 'singleTask', 'singleTop' or 'singleInstance'. This could cause undesired behavior.");
            }
        } catch (Exception ignored) {
        }
    }

    private void checkAndroidManifest() {
        Teak.log.i("integration.manifest", "Checking AndroidManifest.xml for integration issues.");
        try {
            final TeakConfiguration teakConfiguration = TeakConfiguration.get();
            final ManifestParser manifestParser = new ManifestParser(this.activity);

            // Check to make sure there is only one <application>
            final List<ManifestParser.XmlTag> applications = manifestParser.tags.find("$.application");
            if (applications.size() > 1) {
                addErrorToReport("application.count", "There is more than one <application> defined in your AndroidManifest.xml, only one is allowed by Android.");
            } else if (applications.size() == 0) {
                Teak.log.i("integration.manifest", "Unable to read AndroidManifest.xml properly, skipping.");
                return;
            }

            // Make sure the Teak FCM service is present
            {
                Teak.log.i("integration.manifest.fcm", "Checking AndroidManifest.xml for push notification integration issues.");
                final List<ManifestParser.XmlTag> fcmServices = applications.get(0).find("service.intent-filter.action",
                    new HashMap.SimpleEntry<>("name", "com.google.firebase.MESSAGING_EVENT"));
                ManifestParser.XmlTag teakFcmService = null;
                for (ManifestParser.XmlTag tag : fcmServices) {
                    final String checkServiceClass = tag.attributes.get("name");
                    try {
                        Class.forName(checkServiceClass);
                    } catch (Exception ignored) {
                        addErrorToReport(checkServiceClass, "Push notifications will crash because \"" + checkServiceClass + "\" is in your AndroidManifest.xml, but the corresponding SDK has been removed.\n\nTo fix this, remove the <service> for \"" + checkServiceClass + "\"");
                    }

                    // Check to make sure Teak GCM receiver is present
                    if ("io.teak.sdk.push.FCMPushProvider".equals(checkServiceClass)) {
                        teakFcmService = tag;
                    }
                }

                // Error if no Teak GCM receiver
                if (teakFcmService == null) {
                    addErrorToReport("io.teak.sdk.push.FCMPushProvider", "Push notifications will not work because there is no \"io.teak.sdk.push.FCMPushProvider\" <service> in your AndroidManifest.xml.\n\nTo fix this, add the io.teak.sdk.push.FCMPushProvider <service>");
                }
            }

            // Find the teakXXXX:// scheme
            {
                Teak.log.i("integration.manifest.teak_scheme", "Checking AndroidManifest.xml for teakXXX:// scheme");
                final List<ManifestParser.XmlTag> teakScheme = applications.get(0).find("(activity|activity\\-alias).intent\\-filter.data",
                    new HashMap.SimpleEntry<>("scheme", "teak\\d+"));
                if (teakScheme.size() < 1) {
                    addErrorToReport("activity.intent-filter.data.scheme", "Deep linking will not work because there is no <intent-filter> in any <activity> or <activity-alias> has the \"teak\" data scheme.\n\nAdd <data android:scheme=\"teak" + teakConfiguration.appConfiguration.appId + "\" android:host=\"*\" /> to the <intent-filter> for your main activity.");
                } else {
                    // Make sure the <intent-filter> for the teakXXXX:// scheme has <action android:name="android.intent.action.VIEW" />
                    final List<ManifestParser.XmlTag> teakSchemeAction = teakScheme.get(0).find("intent\\-filter.action",
                        new HashMap.SimpleEntry<>("name", "android.intent.action.VIEW"));
                    if (teakSchemeAction.size() < 1) {
                        addErrorToReport("activity.intent-filter.data.scheme", "the <intent-filter> with the \"teak\" data scheme should have <action android:name=\"android.intent.action.VIEW\" />");
                    }

                    // Make sure the <intent-filter> for the teakXXXX:// scheme has <category android:name="android.intent.category.DEFAULT" /> and <category android:name="android.intent.category.BROWSABLE" />
                    final List<ManifestParser.XmlTag> teakSchemeCategories = teakScheme.get(0).find("intent\\-filter.category",
                        new HashMap.SimpleEntry<>("name", "android.intent.category.(DEFAULT|BROWSABLE)"));
                    if (teakSchemeCategories.size() < 2) {
                        addErrorToReport("activity.intent-filter.data.scheme", "the <intent-filter> with the \"teak\" data scheme should have <category android:name=\"android.intent.category.DEFAULT\" /> and <category android:name=\"android.intent.category.BROWSABLE\" />");
                    }

                    // Make sure the <intent-filter> for the teakXXXX:// scheme does *not* also contain any http(s) schemes
                    final List<ManifestParser.XmlTag> teakSchemeOtherSchemes = teakScheme.get(0).find("data",
                        new HashMap.SimpleEntry<>("scheme", "(http|https)"));
                    if (teakSchemeOtherSchemes.size() > 0) {
                        addErrorToReport("activity.intent-filter.data.scheme", "the <intent-filter> with the \"teak\" data scheme *should not* contain any http or https schemes.\n\nPut the \"teak\" data scheme in its own <intent-filter>");
                    }
                }
            }

            // Make sure per-feature permissions are included
            {
                final List<ManifestParser.XmlTag> usesPermissions = manifestParser.tags.find("$.uses-permission");
                final Map<String, Boolean> permissionsAsMap = new HashMap<>();
                for (ManifestParser.XmlTag permission : usesPermissions) {
                    permissionsAsMap.put(permission.attributes.get("name"), true);
                }

                for (int i = 0; i < IntegrationChecker.permissionFeatures.length; i++) {
                    final String feature = IntegrationChecker.permissionFeatures[i];
                    final List<Integer> missingPermissions = new ArrayList<>();
                    for (int j = 0; j < IntegrationChecker.permissions[i].length; j++) {
                        final String permission = IntegrationChecker.permissions[i][j];
                        if (!permissionsAsMap.containsKey(permission)) {
                            missingPermissions.add(j);
                        }
                    }

                    for (int j = 0; j < missingPermissions.size(); j++) {
                        addErrorToReport("permission." + feature, "missing permission '" + IntegrationChecker.permissions[i][missingPermissions.get(j)] + "'");
                    }
                }
                for (ManifestParser.XmlTag permission : usesPermissions) {
                    Teak.log.i("permission", permission.toString());
                }
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    private void onRemoteConfigurationReady(@NonNull RemoteConfiguration remoteConfiguration) {
        // If Enhanced Integration Checks are enabled, report the errors as alert dialogs
        IntegrationChecker.enhancedIntegrationChecks = remoteConfiguration.enhancedIntegrationChecks;
        if (IntegrationChecker.enhancedIntegrationChecks) {
            for (Map.Entry<String, String> error : errorsToReport.entrySet()) {
                displayError(error.getValue(), error.getKey());
            }
        }
    }

    public static void addErrorToReport(@NonNull String key, @NonNull String description) {
        if (IntegrationChecker.enhancedIntegrationChecks && IntegrationChecker.integrationChecker != null) {
            IntegrationChecker.integrationChecker.displayError(description, key);
        } else {
            errorsToReport.put(key, description);
        }
        android.util.Log.e(LOG_TAG, description);
    }

    private void displayError(@NonNull final String description, @Nullable final String title) {
        new Handler(Looper.getMainLooper()).post(() -> {
            AlertDialog.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder = new AlertDialog.Builder(IntegrationChecker.this.activity, android.R.style.Theme_Material_Dialog_Alert);
            } else {
                builder = new AlertDialog.Builder(IntegrationChecker.this.activity);
            }

            builder.setTitle(title == null ? "Human, your assistance is needed" : title)
                .setMessage(description)
                .setPositiveButton(android.R.string.ok, null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
        });
    }
}
