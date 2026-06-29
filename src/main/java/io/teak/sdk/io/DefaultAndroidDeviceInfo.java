package io.teak.sdk.io;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.Helpers;
import io.teak.sdk.IntegrationChecker;
import io.teak.sdk.RetriableTask;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.core.ThreadFactory;
import io.teak.sdk.event.AdvertisingInfoEvent;

public class DefaultAndroidDeviceInfo implements IAndroidDeviceInfo {
    private final Context context;

    public DefaultAndroidDeviceInfo(@NonNull Context context) throws IntegrationChecker.MissingDependencyException {
        this.context = context;

        IntegrationChecker.requireDependency("com.google.android.gms.common.GooglePlayServicesUtil");
        if (isGooglePlayServicesAvailable()) {
            IntegrationChecker.suggestDependency("com.google.android.gms.ads.identifier.AdvertisingIdClient");
        }
    }

    /**
     * Raw Google Play Services availability status, or {@link ConnectionResult#SERVICE_INVALID}
     * when the check itself throws. Centralizes the {@code GooglePlayServicesUtil} call so
     * callers can derive their own predicate (available vs. definitively missing) from a single
     * source rather than copy-pasting it.
     */
    @SuppressWarnings("deprecation")
    public static int googlePlayServicesAvailability(@NonNull Context context) {
        try {
            return GooglePlayServicesUtil.isGooglePlayServicesAvailable(context);
        } catch (Exception ignored) {
        }
        return ConnectionResult.SERVICE_INVALID;
    }

    /**
     * True only when Google Play Services is <em>definitively absent</em> — the GPS APK is not
     * installed ({@link ConnectionResult#SERVICE_MISSING}). Transient states such as
     * {@code SERVICE_UPDATING} / {@code SERVICE_VERSION_UPDATE_REQUIRED} are intentionally NOT
     * treated as missing, so a device that merely needs an update keeps FCM. Used to avoid
     * selecting FCM on Fire/Huawei devices, where {@code getToken()} would throw
     * {@code MISSING_INSTANCEID_SERVICE}.
     */
    public static boolean isGooglePlayServicesMissing(@NonNull Context context) {
        return googlePlayServicesAvailability(context) == ConnectionResult.SERVICE_MISSING;
    }

    private boolean isGooglePlayServicesAvailable() {
        return googlePlayServicesAvailability(this.context) == ConnectionResult.SUCCESS;
    }

    @NonNull
    @Override
    public Map<String, String> getDeviceDescription() {
        // https://raw.githubusercontent.com/jaredrummler/AndroidDeviceNames/master/library/src/main/java/com/jaredrummler/android/device/DeviceName.java
        String deviceManufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String deviceModel = Build.MODEL == null ? "" : Build.MODEL;
        String deviceFallback;
        if (deviceModel.startsWith(Build.MANUFACTURER)) {
            deviceFallback = capitalize(Build.MODEL);
        } else {
            deviceFallback = capitalize(Build.MANUFACTURER) + " " + Build.MODEL;
        }

        HashMap<String, String> info = new HashMap<>();
        info.put("deviceManufacturer", deviceManufacturer);
        info.put("deviceModel", deviceModel);
        info.put("deviceFallback", deviceFallback);
        info.put("deviceBoard", Build.BOARD == null ? "" : Build.BOARD);
        info.put("deviceProduct", Build.PRODUCT == null ? "" : Build.PRODUCT);
        return info;
    }

    @Nullable
    @Override
    @SuppressLint("HardwareIds") // The fallback device id uses these
    public String getDeviceId() {
        String tempDeviceId = null;

        // Build.SERIAL will always return "UNKNOWN" on Android P (API 28+) and greater
        // TODO: Replace hard coded '28' with Build.VERSION_CODES.P once we have that SDK
        if (Build.VERSION.SDK_INT < 28) {
            try {
                @SuppressWarnings("deprecation")
                final byte[] buildSerial = android.os.Build.SERIAL.getBytes("utf8");
                tempDeviceId = UUID.nameUUIDFromBytes(buildSerial).toString();
            } catch (Exception e) {
                Teak.log.e("getDeviceId", "android.os.Build.SERIAL not available, falling back to Settings.Secure.ANDROID_ID.");
                Teak.log.exception(e);
            }
        }

        if (tempDeviceId == null) {
            try {
                String androidId = Settings.Secure.getString(this.context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if ("9774d56d682e549c".equals(androidId)) {
                    Teak.log.e("getDeviceId", "Settings.Secure.ANDROID_ID == '9774d56d682e549c', falling back to random UUID stored in preferences.");
                } else {
                    tempDeviceId = UUID.nameUUIDFromBytes(androidId.getBytes("utf8")).toString();
                }
            } catch (Exception e) {
                Teak.log.e("getDeviceId", "Error generating device id from Settings.Secure.ANDROID_ID, falling back to random UUID stored in preferences.");
                Teak.log.exception(e);
            }
        }

        if (tempDeviceId == null) {
            SharedPreferences preferences = null;
            try {
                preferences = this.context.getSharedPreferences(Teak.PREFERENCES_FILE, Context.MODE_PRIVATE);
            } catch (Exception ignored) {
            }

            if (preferences != null) {
                tempDeviceId = preferences.getString(PREFERENCE_DEVICE_ID, null);
                if (tempDeviceId == null) {
                    try {
                        String prefDeviceId = UUID.randomUUID().toString();
                        synchronized (Teak.PREFERENCES_FILE) {
                            SharedPreferences.Editor editor = preferences.edit();
                            editor.putString(PREFERENCE_DEVICE_ID, prefDeviceId);
                            editor.apply();
                        }
                        tempDeviceId = prefDeviceId;
                    } catch (Exception e) {
                        Teak.log.e("getDeviceId", "Error storing random UUID, no more fallbacks.");
                        Teak.log.exception(e);
                    }
                }
            } else {
                Teak.log.e("getDeviceId", "getSharedPreferences() returned null, unable to store random UUID, no more fallbacks.");
            }
        }

        return tempDeviceId;
    }

    @Override
    public void requestAdvertisingId() {
        // First try to use Google Play
        boolean usingGooglePlayForAdId = false;
        try {
            final FutureTask<AdvertisingIdClient.Info> adInfoFuture = new FutureTask<>(new RetriableTask<>(10, 7000L, () -> {
                if (isGooglePlayServicesAvailable()) {
                    return AdvertisingIdClient.getAdvertisingIdInfo(context);
                }
                throw new Exception("Retrying GooglePlayServicesUtil.isGooglePlayServicesAvailable()");
            }));

            if (isGooglePlayServicesAvailable()) {
                ThreadFactory.autoStart(() -> {
                    try {
                        final AdvertisingIdClient.Info adInfo = adInfoFuture.get();
                        if (adInfo != null) {
                            final String advertisingId = adInfo.getId();
                            final boolean limitAdTracking = adInfo.isLimitAdTrackingEnabled();

                            TeakEvent.postEvent(new AdvertisingInfoEvent(advertisingId, limitAdTracking));
                        }
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                });

                // Only start running the future if we get this far
                ThreadFactory.autoStart(adInfoFuture);

                // And we're good
                usingGooglePlayForAdId = true;
            }
        } catch (Exception ignored) {
        }

        // If not using Google Play, try Settings.Secure
        if (!usingGooglePlayForAdId) {
            try {
                ContentResolver cr = this.context.getContentResolver();
                String advertisingId = Settings.Secure.getString(cr, "advertising_id");
                boolean limitAdTracking = Settings.Secure.getInt(cr, "limit_ad_tracking") != 0;

                TeakEvent.postEvent(new AdvertisingInfoEvent(advertisingId, limitAdTracking));
            } catch (Exception ignored) {
            }
        }
    }

    private static final String PREFERENCE_DEVICE_ID = "io.teak.sdk.Preferences.DeviceId";

    // https://raw.githubusercontent.com/jaredrummler/AndroidDeviceNames/master/library/src/main/java/com/jaredrummler/android/device/DeviceName.java
    private static String capitalize(String str) {
        if (Helpers.isNullOrEmpty(str)) {
            return str;
        }
        char[] arr = str.toCharArray();
        boolean capitalizeNext = true;
        StringBuilder phrase = new StringBuilder();
        for (char c : arr) {
            if (capitalizeNext && Character.isLetter(c)) {
                phrase.append(Character.toUpperCase(c));
                capitalizeNext = false;
                continue;
            } else if (Character.isWhitespace(c)) {
                capitalizeNext = true;
            }
            phrase.append(c);
        }
        return phrase.toString();
    }

    @Override
    public String getSystemProperty(String propName) {
        String line;
        BufferedReader input = null;
        try {
            java.lang.Process p = Runtime.getRuntime().exec("getprop " + propName);
            input = new BufferedReader(new InputStreamReader(p.getInputStream()), 1024);
            line = input.readLine();
            input.close();
        } catch (IOException ex) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return line;
    }

    /**
     * Gets the number of cores available in this device, across all processors.
     * Requires: Ability to peruse the filesystem at "/sys/devices/system/cpu"
     * <p>
     * http://forums.makingmoneywithandroid.com/android-development/280-%5Bhow-%5D-get-number-cpu-cores-android-device.html
     *
     * @return The number of cores, or 1 if failed to get result
     */
    @Override
    public int getNumCores() {
        //Private Class to display only CPU devices in the directory listing
        class CpuFilter implements FileFilter {
            @Override
            public boolean accept(File pathname) {
                //Check if filename is "cpu", followed by a single digit number
                return Pattern.matches("cpu[0-9]+", pathname.getName());
            }
        }

        try {
            //Get directory containing CPU info
            File dir = new File("/sys/devices/system/cpu/");

            //Filter to only list the devices we care about
            File[] files = dir.listFiles(new CpuFilter());

            //Return the number of cores (virtual CPU devices)
            return files.length;
        } catch (Exception e) {
            Teak.log.exception(e);
            return 1;
        }
    }

    public long totalMemoryInBytes() {
        final ActivityManager actManager = (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        final ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return memInfo.totalMem;
    }

    public Map<String, Object> displayMetrics() {
        final DisplayMetrics displayMetrics = this.context.getResources().getDisplayMetrics();
        final HashMap<String, Object> map = new HashMap<>();
        map.put("width", displayMetrics.widthPixels);
        map.put("height", displayMetrics.heightPixels);
        map.put("dpi", displayMetrics.densityDpi);
        return map;
    }
}
