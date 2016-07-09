/* Teak -- Copyright (C) 2016 GoCarrot Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.teak.sdk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.util.Log;

import io.teak.sdk.service.RavenService;

public class AppConfiguration {
    private static final String LOG_TAG = "Teak:AppConfig";

    public final String appId;
    public final String apiKey;
    public final String pushSenderId;
    public final int appVersion;
    public final String bundleId;
    public final String installerPackage;

    private static final String TEAK_API_KEY = "io_teak_api_key";
    private static final String TEAK_APP_ID = "io_teak_app_id";
    private static final String TEAK_GCM_SENDER_ID = "io_teak_gcm_sender_id";

    public AppConfiguration(@NonNull Activity activity) {
        // Teak App Id
        {
            this.appId = Helpers.getStringResourceByName(TEAK_APP_ID, activity);
            if (this.appId == null) {
                throw new RuntimeException("Failed to find R.string." + TEAK_APP_ID);
            }
        }

        // Teak API Key
        {
            this.apiKey = Helpers.getStringResourceByName(TEAK_API_KEY, activity);
            if (this.apiKey == null) {
                throw new RuntimeException("Failed to find R.string." + TEAK_API_KEY);
            }
        }

        // Push Sender Id
        {
            // TODO: Check ADM vs GCM
            this.pushSenderId = Helpers.getStringResourceByName(TEAK_GCM_SENDER_ID, activity);
            if (this.pushSenderId == null && Teak.isDebug) {
                Log.d(LOG_TAG, "R.string." + TEAK_GCM_SENDER_ID + " not present, push notifications disabled.");
            }
        }

        // Package Id
        {
            this.bundleId = activity.getPackageName();
            if (this.bundleId == null) {
                throw new RuntimeException("Failed to get Bundle Id.");
            }
        }

        PackageManager packageManager = activity.getPackageManager();
        if (packageManager == null) {
            throw new RuntimeException("Unable to get Package Manager.");
        }

        // App Version
        {
            int tempAppVersion = 0;
            try {
                tempAppVersion = packageManager.getPackageInfo(this.bundleId, 0).versionCode;
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error getting App Version: " + Log.getStackTraceString(e));
            } finally {
                this.appVersion = tempAppVersion;
            }
        }

        // Get the installer package
        {
            this.installerPackage = packageManager.getInstallerPackageName(this.bundleId);
            if (this.installerPackage == null) {
                Log.e(LOG_TAG, "Installer package (Store) is null, purchase tracking disabled.");
            }
        }

        // Tell the Raven service about the app id
        try {
            Intent intent = new Intent(activity, RavenService.class);
            intent.putExtra("appId", this.appId);
            ComponentName componentName = activity.startService(intent);
            if (componentName == null) {
                Log.e(LOG_TAG, "Unable to communicate with exception reporting service. Please add:\n\t<service android:name=\"io.teak.sdk.service.RavenService\" android:process=\":teak.raven\" android:exported=\"false\"/>\nTo the <application> section of your AndroidManifest.xml");
            } else if(Teak.isDebug) {
                Log.d(LOG_TAG, "Communication with exception reporting service established: " + componentName.toString());
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calling startService for exception reporting service: " + Log.getStackTraceString(e));
        }
    }
}