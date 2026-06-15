package io.teak.sdk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONException;
import io.teak.sdk.json.JSONObject;

public class Helpers implements Unobfuscable {
    @SuppressWarnings("deprecation")
    @SuppressLint("PackageManagerGetSignatures")
    public static Signature[] getAppSignatures(@NonNull Context appContext) {
        try {
            return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void runAndLogGC(final @NonNull String logEvent) {
        long preGc = Runtime.getRuntime().freeMemory();
        Runtime.getRuntime().gc();
        long postGc = Runtime.getRuntime().freeMemory();
        Teak.log.i(logEvent, Helpers.mm.h(
                                 "pre_gc", String.format(Locale.US, "%dk", preGc / 1024L),
                                 "post_gc", String.format(Locale.US, "%dk", postGc / 1024L),
                                 "delta_gc", String.format(Locale.US, "%dk", (postGc - preGc) / 1024L)));
    }

    public static boolean stringsAreEqual(final @Nullable String a, final @Nullable String b) {
        if (a == b) return true;

        final String notNullString = (a == null ? b : a);
        final String possiblyNullString = (a == null ? a : b);
        return notNullString.equals(possiblyNullString);
    }

    public static String getStringOrNullFromIntentExtra(final @NonNull Intent intent, final @NonNull String key) {
        String ret = null;
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            String value = bundle.getString(key);
            if (value != null && !value.isEmpty()) {
                ret = value;
            }
        }
        return ret;
    }

    public static boolean getBooleanFromBundle(Bundle b, String key) {
        try {
            final Object value = b.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return Boolean.parseBoolean(value.toString());
        } catch (Exception ignored) {
        }

        return false;
    }

    public static boolean getBooleanFromMap(final Map<String, Object> map, final String key) {
        if (map.containsKey(key)) {
            Boolean bool = (Boolean) map.get(key);
            if (bool != null) {
                return bool;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    public static String getInstallerPackage(final @NonNull Context context) {
        final String bundleId = context.getPackageName();
        final PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) {
            return null;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return packageManager.getInstallSourceInfo(bundleId).getInstallingPackageName();
            }

            return packageManager.getInstallerPackageName(bundleId);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isAmazonDevice(final @NonNull Context context) {
        final String installerPackage = Helpers.getInstallerPackage(context);
        return "amazon".equalsIgnoreCase(Build.MANUFACTURER) ||
            "com.amazon.venezia".equals(installerPackage);
    }

    public static class mm {
        public static Map<String, Object> h(@NonNull Object... args) {
            Map<String, Object> ret = new HashMap<>();
            if (args.length % 2 != 0)
                throw new InvalidParameterException("Args must be in key value pairs.");
            for (int i = 0; i < args.length; i += 2) {
                ret.put(args[i].toString(), args[i + 1]);
            }
            return ret;
        }
    }

    public static String join(CharSequence delimiter, Iterable tokens) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = tokens.iterator();
        if (it.hasNext()) {
            sb.append(it.next());
            while (it.hasNext()) {
                sb.append(delimiter);
                sb.append(it.next());
            }
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder sb = new StringBuilder();
        boolean firstTime = true;
        for (Object token : tokens) {
            if (firstTime) {
                firstTime = false;
            } else {
                sb.append(delimiter);
            }
            sb.append(token);
        }
        return sb.toString();
    }

    public static boolean isNullOrEmpty(final @Nullable String string) {
        return string == null || string.trim().isEmpty();
    }

    @NonNull
    public static JSONObject fromResponseBody(@Nullable String body, @NonNull String source) {
        try {
            return new JSONObject((body == null || body.trim().isEmpty()) ? "{}" : body);
        } catch (JSONException e) {
            Teak.log.e("request.response.non_json", "Non-JSON response from " + source + ": " + e.getMessage());
            return new JSONObject();
        }
    }

    public static boolean is_equal(final @Nullable Object a, final @Nullable Object b) {
        return (a == b) ||
            (a != null && a.equals(b)) ||
            (b != null && b.equals(a));
    }

    public static JSONObject bundleToJson(Bundle bundle) {
        JSONObject json = new JSONObject();
        Set<String> keys = bundle.keySet();
        for (String key : keys) {
            try {
                json.put(key, JSONObject.wrap(bundle.get(key)));
            } catch (JSONException ignored) {
            }
        }
        return json;
    }

    public static Bundle jsonToGCMBundle(JSONObject jsonObject) {
        Bundle bundle = new Bundle();

        for (Iterator<String> it = jsonObject.keys(); it.hasNext();) {
            String key = it.next();
            Object obj = jsonObject.get(key);

            if (obj instanceof Integer || obj instanceof Long) {
                if (key.startsWith("google.")) {
                    long value = ((Number) obj).longValue();
                    bundle.putLong(key, value);
                } else {
                    bundle.putString(key, String.valueOf(obj));
                }
            } else if (obj instanceof Boolean) {
                boolean value = (Boolean) obj;
                bundle.putBoolean(key, value);
            } else if (obj instanceof Float || obj instanceof Double) {
                if (key.startsWith("google.")) {
                    double value = ((Number) obj).doubleValue();
                    bundle.putDouble(key, value);
                } else {
                    bundle.putString(key, String.valueOf(obj));
                }
            } else if (obj instanceof JSONObject || obj instanceof JSONArray) {
                String value = obj.toString();
                bundle.putString(key, value);
            } else if (obj instanceof String) {
                String value = jsonObject.getString(key);
                bundle.putString(key, value);
            }
        }

        return bundle;
    }

    private static int targetSDKVersion = 0;
    public static int getTargetSDKVersion(@NonNull Context context) {
        if (targetSDKVersion == 0) {
            try {
                ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                targetSDKVersion = appInfo.targetSdkVersion;
            } catch (Exception ignored) {
            }
        }
        return targetSDKVersion;
    }

    public static String formatSig(Signature sig, String hashType) throws java.security.NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(hashType);
        byte[] sha256Bytes = md.digest(sig.toByteArray());
        StringBuilder hexString = new StringBuilder();
        for (byte aSha256Byte : sha256Bytes) {
            if (hexString.length() > 0) {
                hexString.append(":");
            }

            if ((0xff & aSha256Byte) < 0x10) {
                hexString.append("0").append(Integer.toHexString((0xFF & aSha256Byte)));
            } else {
                hexString.append(Integer.toHexString(0xFF & aSha256Byte));
            }
        }
        return hexString.toString().toUpperCase();
    }

    public static String getCurrentFacebookAccessToken() {
        try {
            Class<?> com_facebook_AccessToken = Class.forName("com.facebook.AccessToken");
            Method com_facebook_AccessToken_getCurrentAccessToken = com_facebook_AccessToken.getMethod("getCurrentAccessToken");
            Method com_facebook_AccessToken_getToken = com_facebook_AccessToken.getMethod("getToken");

            Object currentAccessToken = com_facebook_AccessToken_getCurrentAccessToken.invoke(null);
            if (currentAccessToken != null) {
                Object accessTokenString = com_facebook_AccessToken_getToken.invoke(currentAccessToken);
                if (accessTokenString != null) {
                    return accessTokenString.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // https://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static <T> Future<T> futureForValue(final T value) {
        return new Future<T>() {
            @Override
            public boolean cancel(boolean b) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public T get() {
                return value;
            }

            @Override
            public T get(long l, TimeUnit timeUnit) {
                return get();
            }
        };
    }

    public static <T> T newIfNotOld(final T oldValue, final T newValue) {
        return oldValue == null ? newValue : oldValue;
    }

    public static String stringForBool(boolean value) {
        return value ? "true" : "false";
    }
}
