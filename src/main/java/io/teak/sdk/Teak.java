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
import android.app.Application.ActivityLifecycleCallbacks;

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;

import android.support.v4.content.LocalBroadcastManager;

import android.os.Bundle;
import android.os.Handler;
import android.os.AsyncTask;

import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import android.telephony.TelephonyManager;

import android.util.Log;

import org.json.JSONObject;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import java.lang.InterruptedException;

import javax.net.ssl.HttpsURLConnection;

import java.net.URL;
import java.net.URLEncoder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import java.util.Stack;
import java.util.UUID;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.TimeZone;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

/**
 * Working with Teak on Android.
 * <p/>
 * Firstly, add a <code>teak.xml</code> file into your res/values folder.
 * <pre>{@code
 * <?xml version="1.0" encoding="utf-8"?>
 * <resources>
 *   <string name="io_teak_app_id">YOUR TEAK APP ID</string>
 *   <string name="io_teak_api_key">YOUR TEAK API KEY</string>
 * </resources>
 * }</pre>
 * Your Teak App Id and API Key can be found in the Settings for your app on the Teak dashboard.
 * <p/>
 * You may also provide a GCM sender id to Teak, in which case Teak will take care of
 * registering for a GCM key.
 * <pre>{@code
 * <string name="io_teak_gcm_sender_id">YOUR GCM SENDER ID</string>
 * }</pre>
 * <p/>
 * Next, add Teak to your gradle build.
 * <p/>
 * For Unity, open settings.gradle and add the line:
 * <pre>{@code project(':teak').projectDir=new File('teak-android/sdk') }</pre>
 * <p/>
 * And then open app/build.gradle and add the following line to dependencies:
 * <pre>{@code compile project(':teak') }</pre>
 * <p/>
 * Add the following as the <i>first line</i> of onCreate function of UnityPlayerNativeActivity:
 * <pre>{@code Teak.onCreate(this); }</pre>
 * <p/>
 * Add the following as the <i>first line</i> of onActivityResult function of UnityPlayerNativeActivity:
 * <pre>{@code Teak.onActivityResult(requestCode, resultCode, data); }</pre>
 */
public class Teak extends BroadcastReceiver {

    /**
     * Version of the Teak SDK.
     */
    public static final String SDKVersion = "2.0";

    /**
     * Initialize Teak and tell it to listen to the lifecycle events of {@link Activity}.
     * <p/>
     * <p>Call this function from the {@link Activity#onCreate} function of your <code>Activity</code>
     * <b>before</b> the call to <code>super.onCreate()</code></p>
     *
     * @param activity The main <code>Activity</code> of your app.
     */
    public static void onCreate(Activity activity) {
        Teak.mainActivity = activity;
        activity.getApplication().registerActivityLifecycleCallbacks(Teak.lifecycleCallbacks);
    }

    /**
     * Tell Teak about the result of an {@link Activity} started by your app.
     * <p/>
     * <p>This allows Teak to automatically get the results of In-App Purchase events.</p>
     *
     * @param requestCode The <code>requestCode</code> parameter received from {@link Activity#onActivityResult}
     * @param resultCode  The <code>resultCode</code> parameter received from {@link Activity#onActivityResult}
     * @param data        The <code>data</code> parameter received from {@link Activity#onActivityResult}
     */
    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (Teak.isDebug) {
            Log.d(LOG_TAG, "Lifecycle - onActivityResult");
        }

        if (data != null) {
            checkActivityResultForPurchase(resultCode, data);
        }
    }

    public static void onNewIntent(Intent intent) {
        Bundle bundle = intent.getExtras();
        if (Teak.isDebug) {
            Log.d(LOG_TAG, "Lifecycle - onNewIntent");
        }

        if (bundle != null) {
            // Set the notification id
            Teak.launchedFromTeakNotifId = bundle.getString("teakNotifId");
        }
    }

    /**
     * Tell Teak how it should identify the current user.
     * <p/>
     * <p>This should be the same way you identify the user in your backend.</p>
     *
     * @param userIdentifier An identifier which is unique for the current user.
     */
    public static void identifyUser(String userIdentifier) {
        // Always show this debug output.
        Log.d(LOG_TAG, "identifyUser(): " + userIdentifier);

        if (Teak.userId == null) {
            Log.e(LOG_TAG, "Teak.onCreate() has not been called in your Activity's onCreate() function.");
        } else if (!Teak.userId.isDone()) {
            Teak.userIdQueue.offer(userIdentifier);
        }
    }

    /**************************************************************************/

    static int appVersion;
    static boolean isDebug;
    static FutureTask<String> gcmId;
    static String apiKey;
    static String appId;
    static FutureTask<AdvertisingInfo> adInfo;
    static FutureTask<ServiceConfig> serviceConfig;
    static FutureTask<String> userId;
    static FutureTask<String> facebookAccessToken;
    static Activity mainActivity;
    static ArrayBlockingQueue<String> userIdQueue;
    static ArrayBlockingQueue<String> gcmIdQueue;
    static ArrayBlockingQueue<String> facebookAccessTokenQueue;
    static ExecutorService asyncExecutor = Executors.newCachedThreadPool();
    static FacebookAccessTokenBroadcast facebookAccessTokenBroadcast;
    static CacheOpenHelper cacheOpenHelper;
    static String launchedFromTeakNotifId;
    static ScheduledExecutorService heartbeatService;
    static boolean userIdentifiedThisSession;
    static Date lastSessionEndedAt;
    static SQLiteDatabase database;
    static String deviceId;
    static String installerPackage;
    static IStore appStore;
    static Stack<String> skuStack = new Stack<String>();

    static final String LOG_TAG = "Teak";

    private static final String TEAK_API_KEY = "io_teak_api_key";
    private static final String TEAK_APP_ID = "io_teak_app_id";
    private static final String TEAK_GCM_SENDER_ID = "io_teak_gcm_sender_id";

    private static final String TEAK_PREFERENCES_FILE = "io.teak.sdk.Preferences";
    private static final String TEAK_PREFERENCE_GCM_ID = "io.teak.sdk.Preferences.GcmId";
    private static final String TEAK_PREFERENCE_APP_VERSION = "io.teak.sdk.Preferences.AppVersion";

    private static final String TEAK_SERVICES_HOSTNAME = "services.gocarrot.com";

    private static final long SAME_SESSION_TIME_DELTA = 120000;

    /**************************************************************************/
    private static final TeakActivityLifecycleCallbacks lifecycleCallbacks = new TeakActivityLifecycleCallbacks();

    static class TeakActivityLifecycleCallbacks implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(final Activity activity, Bundle savedInstanceState) {
            if (activity != Teak.mainActivity) return;

            // Unique device id
            final TelephonyManager tm = (TelephonyManager) activity.getBaseContext().getSystemService(Context.TELEPHONY_SERVICE);
            final String tmDevice, tmSerial, androidId;
            tmDevice = "" + tm.getDeviceId();
            tmSerial = "" + tm.getSimSerialNumber();
            androidId = "" + android.provider.Settings.Secure.getString(activity.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            UUID deviceUuid = new UUID(androidId.hashCode(), ((long) tmDevice.hashCode() << 32) | tmSerial.hashCode());
            Teak.deviceId = deviceUuid.toString();

            // Check for debug build
            Teak.isDebug = ((Boolean) Helpers.getBuildConfigValue(activity, "DEBUG")) == Boolean.TRUE;

            // Get current app version
            Teak.appVersion = 0;
            try {
                Teak.appVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
            } catch (Exception e) {
                Log.e(LOG_TAG, Log.getStackTraceString(e));
            }

            // Get the API Key
            if (Teak.apiKey == null) {
                Teak.apiKey = Helpers.getStringResourceByName(TEAK_API_KEY, activity);
                if (Teak.apiKey == null) {
                    throw new RuntimeException("Failed to find R.string." + TEAK_API_KEY);
                }
            }

            // Get the App Id
            if (Teak.appId == null) {
                Teak.appId = Helpers.getStringResourceByName(TEAK_APP_ID, activity);
                if (Teak.appId == null) {
                    throw new RuntimeException("Failed to find R.string." + TEAK_APP_ID);
                }
            }

            // Get the installer package
            Teak.installerPackage = activity.getPackageManager().getInstallerPackageName(activity.getPackageName());

            // Applicable store
            if (Teak.installerPackage != null) {
                Class<?> clazz = null;
                if (Teak.installerPackage.equals("com.amazon.venezia")) {
                    try {
                        clazz = Class.forName("io.teak.sdk.Amazon");
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Couldn't find Teak's Amazon app store handler. " + e.toString());
                    }
                } else {
                    // Default to Google Play
                    try {
                        clazz = Class.forName("io.teak.sdk.GooglePlay");
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Couldn't find Teak's Google Play app store handler. " + e.toString());
                    }
                }
                try {
                    Teak.appStore = (IStore) clazz.newInstance();
                    Teak.appStore.init(activity);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Unable to create app store interface. " + e.toString());
                }
            }

            // Facebook Access Token Broadcaster
            Teak.facebookAccessTokenBroadcast = new FacebookAccessTokenBroadcast(activity);

            // Register for local broadcasts
            IntentFilter filter = new IntentFilter();
            filter.addAction(FacebookAccessTokenBroadcast.UPDATED_ACCESS_TOKEN_INTENT_ACTION);
            LocalBroadcastManager.getInstance(activity).registerReceiver(Teak.localBroadcastReceiver, filter);

            // Producer/Consumer Queues
            Teak.gcmIdQueue = new ArrayBlockingQueue<String>(1);
            Teak.userIdQueue = new ArrayBlockingQueue<String>(1);
            Teak.facebookAccessTokenQueue = new ArrayBlockingQueue<String>(1);

            // User Id
            Teak.userId = new FutureTask<String>(new Callable<String>() {
                public String call() {
                    try {
                        String ret = Teak.userIdQueue.take();
                        if (Teak.isDebug) {
                            Log.d(LOG_TAG, "User Id ready: " + ret);
                        }
                        return ret;
                    } catch (InterruptedException e) {
                        Log.e(LOG_TAG, Log.getStackTraceString(e));
                    }
                    return null;
                }
            });
            Teak.asyncExecutor.submit(Teak.userId);

            // Facebook Access Token
            createFacebookAccessTokenFuture();

            // Check for valid GCM Id
            SharedPreferences preferences = activity.getSharedPreferences(TEAK_PREFERENCES_FILE, Context.MODE_PRIVATE);
            int storedAppVersion = preferences.getInt(TEAK_PREFERENCE_APP_VERSION, 0);
            String storedGcmId = preferences.getString(TEAK_PREFERENCE_GCM_ID, null);
            if (storedAppVersion == Teak.appVersion && storedGcmId != null) {
                // No need to get a new one, so put it on the blocking queue
                if (Teak.isDebug) {
                    Log.d(LOG_TAG, "GCM Id found in cache: " + storedGcmId);
                }
                Teak.gcmIdQueue.offer(storedGcmId);
            } else {
                // If io_teak_gcm_sender_id is available, do the registration ourselves.
                try {
                    final String senderId = Helpers.getStringResourceByName(TEAK_GCM_SENDER_ID, activity);
                    if (senderId != null) {
                        if (Teak.isDebug) {
                            Log.d(LOG_TAG, "Registering for GCM with sender id: " + senderId);
                        }

                        // Register for GCM in the background
                        Teak.asyncExecutor.submit(new Runnable() {
                            public void run() {
                                try {
                                    GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(activity);
                                    gcm.register(senderId);
                                } catch (Exception ex) {
                                    Log.e(LOG_TAG, ex.toString());
                                    // TODO: exponential back-off, re-register
                                }
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }

            Teak.gcmId = new FutureTask<String>(new Callable<String>() {
                public String call() {
                    try {
                        String ret = Teak.gcmIdQueue.take();
                        return ret;
                    } catch (InterruptedException e) {
                        Log.e(LOG_TAG, Log.getStackTraceString(e));
                    }
                    return null;
                }
            });
            Teak.asyncExecutor.submit(Teak.gcmId);

            // Google Play Advertising Id
            int googlePlayStatus = GooglePlayServicesUtil.isGooglePlayServicesAvailable(activity);
            if (googlePlayStatus == ConnectionResult.SUCCESS) {
                Teak.adInfo = new FutureTask<AdvertisingInfo>(new Callable<AdvertisingInfo>() {
                    public AdvertisingInfo call() {
                        AdvertisingInfo ret = null;
                        try {
                            ret = new AdvertisingInfo(AdvertisingIdClient.getAdvertisingIdInfo(activity));

                            if (Teak.isDebug) {
                                Log.d(LOG_TAG, "Google Play Advertising Info loaded: " + ret.toString());
                            }
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Couldn't get Google Play Advertising Id.");
                            if (Teak.isDebug) {
                                e.printStackTrace();
                            }
                        }
                        return ret;
                    }
                });
            } else {
                Teak.adInfo = new FutureTask<AdvertisingInfo>(new Callable<AdvertisingInfo>() {
                    public AdvertisingInfo call() {
                        Log.e(LOG_TAG, "Google Play Services not available, can't get advertising id.");
                        return null;
                    }
                });
            }
            Teak.asyncExecutor.submit(Teak.adInfo);

            // Cache
            Teak.cacheOpenHelper = new CacheOpenHelper(activity);
            try {
                Teak.database = Teak.cacheOpenHelper.getWritableDatabase();
            } catch (SQLException e) {
                Log.e(Teak.LOG_TAG, Log.getStackTraceString(e));
            }

            // Validate the app id/key via "/games/#{@appId}/validate_sig.json"
            if (Teak.isDebug) {
                HashMap<String, Object> payload = new HashMap<String, Object>();
                payload.put("id", Teak.appId);
                Teak.asyncExecutor.execute(new Request("POST", "gocarrot.com", "/games/" + Teak.appId + "/validate_sig.json", payload) {
                    @Override
                    protected void done(int responseCode, String responseBody) {
                        try {
                            JSONObject response = new JSONObject(responseBody);
                            if (response.has("error")) {
                                JSONObject error = response.getJSONObject("error");
                                Log.e(LOG_TAG, "Error in Teak configuration: " + error.getString("message"));
                            } else {
                                Log.d(LOG_TAG, "Teak configuration valid for: " + response.getString("name"));
                            }
                        } catch (Exception ignored) {
                        }
                        super.done(responseCode, responseBody);
                    }
                });
            }

            if (Teak.isDebug) {
                Log.d(LOG_TAG, "Lifecycle - onActivityCreated");
                Log.d(LOG_TAG, "        App Id: " + Teak.appId);
                Log.d(LOG_TAG, "       Api Key: " + Teak.apiKey);
                Log.d(LOG_TAG, "   App Version: " + Teak.appVersion);
                Log.d(LOG_TAG, "     App Store: " + Teak.installerPackage);
                if (Teak.launchedFromTeakNotifId != null) {
                    Log.d(LOG_TAG, " Teak Notif Id: " + Teak.launchedFromTeakNotifId);
                }
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (activity != Teak.mainActivity) return;

            if (Teak.isDebug) {
                Log.d(LOG_TAG, "Lifecycle - onActivityDestroyed");
            }

            if (Teak.appStore != null) {
                Teak.appStore.dispose();
            }
            Teak.database.close();
            Teak.cacheOpenHelper.close();
            Teak.facebookAccessTokenBroadcast.unregister(activity);
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(Teak.localBroadcastReceiver);
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (activity != Teak.mainActivity) return;

            if (Teak.isDebug) {
                Log.d(LOG_TAG, "Lifecycle - onActivityPaused");
            }

            if (Teak.heartbeatService != null) {
                Teak.heartbeatService.shutdown();
                Teak.heartbeatService = null;
            }

            Teak.launchedFromTeakNotifId = null;
            Teak.userIdentifiedThisSession = false;
            Teak.lastSessionEndedAt = new Date();
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity != Teak.mainActivity) return;

            if (Teak.isDebug) {
                Log.d(LOG_TAG, "Lifecycle - onActivityResumed");
            }

            // Service config
            final ServiceConfig config = new ServiceConfig();
            HashMap<String, Object> payload = new HashMap<String, Object>();
            payload.put("id", Teak.appId);
            Teak.serviceConfig = new FutureTask<ServiceConfig>(new Request("POST", "gocarrot.com", "/games/" + Teak.appId + "/settings.json", payload) {
                @Override
                protected void done(int responseCode, String responseBody) {
                    try {
                        JSONObject response = new JSONObject(responseBody);
                        config.setConfig(response);

                        if (Teak.isDebug) {
                            Log.d(LOG_TAG, "Services response (" + responseCode + "): " + response.toString(2));
                            Log.d(LOG_TAG, "Service Config " + config.toString());
                        }

                        // Heartbeat will block on userId Future, which is fine
                        startHeartbeat();

                        // Submit cached requests
                        CachedRequest.submitCachedRequests();
                    } catch (Exception ignored) {
                    }
                }
            }, config);
            Teak.asyncExecutor.execute(Teak.serviceConfig);

            // Adds executor task that waits on userId and other Futures
            if (Teak.launchedFromTeakNotifId != null ||
                    Teak.lastSessionEndedAt == null ||
                    new Date().getTime() - Teak.lastSessionEndedAt.getTime() > SAME_SESSION_TIME_DELTA) {
                identifyUser();
            }

            // Check for pending inbox messages, and notify app if they exist
            if (TeakNotification.inboxCount() > 0) {
                LocalBroadcastManager.getInstance(Teak.mainActivity).sendBroadcast(new Intent(TeakNotification.TEAK_INBOX_HAS_NOTIFICATIONS_INTENT));
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
            if (Teak.isDebug) {
                Log.d(LOG_TAG, "Lifecycle - onActivityStarted: " + activity.toString());
            }

            // OpenIAB, need to store off the SKU for the purchase failed case
            if (activity.getClass().getName().equals("org.onepf.openiab.UnityProxyActivity")) {
                Bundle bundle = activity.getIntent().getExtras();
                if (Teak.isDebug) {
                    Log.d(LOG_TAG, "Unity OpenIAB purchase launched: " + bundle.toString());
                }
                skuStack.push(bundle.getString("sku"));
            }
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }
    }

    private static void startHeartbeat() {
        if (Teak.heartbeatService == null) {
            Teak.heartbeatService = Executors.newSingleThreadScheduledExecutor();
        }

        Teak.heartbeatService.scheduleAtFixedRate(new Runnable() {
            public void run() {
                String userId = null;
                try {
                    userId = Teak.userId.get();
                } catch (Exception e) {
                    Log.e(Teak.LOG_TAG, Log.getStackTraceString(e));
                    return;
                }

                if (Teak.isDebug) {
                    Log.d(Teak.LOG_TAG, "Sending heartbeat for user: " + userId);
                }

                HttpsURLConnection connection = null;
                try {
                    String queryString = "game_id=" + URLEncoder.encode(Teak.appId, "UTF-8") +
                            "&api_key=" + URLEncoder.encode(userId, "UTF-8") +
                            "&sdk_version=" + URLEncoder.encode(Teak.SDKVersion, "UTF-8") +
                            "&sdk_platform=" + URLEncoder.encode("android_" + android.os.Build.VERSION.RELEASE, "UTF-8") +
                            "&app_version=" + URLEncoder.encode(String.valueOf(Teak.appVersion), "UTF-8") +
                            "&buster=" + URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
                    URL url = new URL("https://iroko.gocarrot.com/ping?" + queryString);
                    connection = (HttpsURLConnection) url.openConnection();
                    connection.setRequestProperty("Accept-Charset", "UTF-8");
                    connection.setUseCaches(false);
                } catch (Exception e) {
                } finally {
                    connection.disconnect();
                    connection = null;
                }
            }
        }, 0, 1, TimeUnit.MINUTES); // TODO: If services config specifies a different rate, use that
    }

    private static void identifyUser() {
        final Date dateIssued = new Date();
        final String launchedFromTeakNotifId = Teak.launchedFromTeakNotifId;

        Teak.asyncExecutor.submit(new Runnable() {
            public void run() {
                String userId = null;
                try {
                    userId = Teak.userId.get();
                } catch (Exception e) {
                    Log.e(Teak.LOG_TAG, Log.getStackTraceString(e));
                    return;
                }

                HashMap<String, Object> payload = new HashMap<String, Object>();

                payload.put("happened_at", dateIssued.getTime() / 1000); // Milliseconds -> Seconds

                if (Teak.userIdentifiedThisSession) {
                    payload.put("do_not_track_event", Boolean.TRUE);
                }
                Teak.userIdentifiedThisSession = true;

                TimeZone tz = TimeZone.getDefault();
                long rawTz = tz.getRawOffset();
                if (tz.inDaylightTime(new Date())) {
                    rawTz += tz.getDSTSavings();
                }
                long minutes = TimeUnit.MINUTES.convert(rawTz, TimeUnit.MILLISECONDS);
                String tzOffset = new DecimalFormat("#0.00").format(minutes / 60.0f);
                payload.put("timezone", tzOffset);

                String locale = Locale.getDefault().toString();
                payload.put("locale", locale);

                try {
                    AdvertisingInfo adInfo = Teak.adInfo.get(5L, TimeUnit.SECONDS);
                    if (adInfo != null) {
                        payload.put("android_ad_id", adInfo.adId);
                        payload.put("android_limit_ad_tracking", adInfo.limitAdTracking);
                    }
                } catch (Exception e) {
                }

                try {
                    String accessToken = Teak.facebookAccessToken.get(5L, TimeUnit.SECONDS);
                    if (accessToken != null) {
                        payload.put("access_token", accessToken);
                    }
                } catch (Exception e) {
                }

                if (launchedFromTeakNotifId != null) {
                    payload.put("teak_notif_id", new Long(launchedFromTeakNotifId));
                }

                try {
                    String gcmId = Teak.gcmId.get(5L, TimeUnit.SECONDS);
                    if (gcmId != null) {
                        payload.put("gcm_push_key", gcmId);
                    }
                } catch (Exception e) {
                }

                Log.d(LOG_TAG, "Identifying user: " + userId);
                Log.d(LOG_TAG, "        Timezone: " + tzOffset);
                Log.d(LOG_TAG, "          Locale: " + locale);

                Teak.asyncExecutor.submit(new CachedRequest("/games/" + Teak.appId + "/users.json", payload, dateIssued) {
                    @Override
                    protected void done(int responseCode, String responseBody) {
                        try {
                            JSONObject response = new JSONObject(responseBody);

                            // TODO: Grab 'id' and 'game_id' from response and store for Parsnip

                            if (Teak.isDebug) {
                                Log.d(LOG_TAG, "identifyUser response: " + response.toString(2));
                            }
                        } catch (Exception ignored) {
                        }

                        super.done(responseCode, responseBody);
                    }
                });
            }
        });
    }

    static BroadcastReceiver localBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (FacebookAccessTokenBroadcast.UPDATED_ACCESS_TOKEN_INTENT_ACTION.equals(action)) {
                if (Teak.isDebug) {
                    Log.d(LOG_TAG, "Facebook Access Token updated.");
                }
                createFacebookAccessTokenFuture();
            }
        }
    };

    /**************************************************************************/

    private static final String GCM_RECEIVE_INTENT_ACTION = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GCM_REGISTRATION_INTENT_ACTION = "com.google.android.c2dm.intent.REGISTRATION";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (GCM_REGISTRATION_INTENT_ACTION.equals(action)) {
            // Store off the GCM Id and app version
            try {
                Bundle bundle = intent.getExtras();
                String registration = bundle.get("registration_id").toString();
                SharedPreferences.Editor editor = context.getSharedPreferences(TEAK_PREFERENCES_FILE, Context.MODE_PRIVATE).edit();
                editor.putInt(TEAK_PREFERENCE_APP_VERSION, Teak.appVersion);
                editor.putString(TEAK_PREFERENCE_GCM_ID, registration);
                editor.apply();

                if (Teak.isDebug) {
                    Log.d(LOG_TAG, "GCM Id received from registration intent: " + registration);
                }

                if (Teak.gcmIdQueue != null) {
                    Teak.gcmIdQueue.offer(registration);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error storing GCM Id from " + GCM_REGISTRATION_INTENT_ACTION + ":\n" + Log.getStackTraceString(e));
            }
        } else if (GCM_RECEIVE_INTENT_ACTION.equals(action)) {
            final TeakNotification notif = TeakNotification.notificationFromIntent(context, intent);

            // Send Notification Received Metric
            Teak.asyncExecutor.submit(new Runnable() {
                public void run() {
                    if (Teak.userId == null) return;

                    String userId = null;
                    try {
                        userId = Teak.userId.get();
                    } catch (Exception e) {
                        Log.e(Teak.LOG_TAG, Log.getStackTraceString(e));
                        return;
                    }

                    HashMap<String, Object> payload = new HashMap<String, Object>();
                    payload.put("app_id", Teak.appId);
                    payload.put("user_id", Teak.userId);
                    payload.put("platform_id", new Long(notif.teakNotifId));

                    Teak.asyncExecutor.submit(new CachedRequest("/notification_received", payload, new Date()));
                }
            });

            // Send out inbox broadcast
            if (Teak.mainActivity != null) {
                LocalBroadcastManager.getInstance(Teak.mainActivity).sendBroadcast(new Intent(TeakNotification.TEAK_INBOX_HAS_NOTIFICATIONS_INTENT));
            }
        } else if (action.endsWith(TeakNotification.TEAK_PUSH_OPENED_INTENT_ACTION_SUFFIX)) {
            Bundle bundle = intent.getExtras();

            // Cancel the update
            TeakNotification.cancel(context, bundle.getInt("platformId"));
            Log.d(LOG_TAG, "Bundle: " + bundle.toString());

            // Launch the app
            if (!bundle.getBoolean("noAutolaunch")) {
                if (Teak.isDebug) {
                    Log.d(LOG_TAG, "Notification (" + bundle.getString("teakNotifId") + ") opened, auto-launching app.");
                }
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                launchIntent.addCategory("android.intent.category.LAUNCHER");
                launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                launchIntent.putExtras(bundle);
                context.startActivity(launchIntent);
            } else {
                if (Teak.isDebug) {
                    Log.d(LOG_TAG, "Notification (" + bundle.getString("teakNotifId") + ") opened, NOT auto-launching app (noAutoLaunch flag present, and set to true).");
                }
            }
        } else if (action.endsWith(TeakNotification.TEAK_PUSH_CLEARED_INTENT_ACTION_SUFFIX)) {
            Bundle bundle = intent.getExtras();
            TeakNotification.cancel(context, bundle.getInt("platformId"));
        }
    }

    static void createFacebookAccessTokenFuture() {
        Teak.facebookAccessToken = new FutureTask<String>(new Callable<String>() {
            public String call() {
                try {
                    String ret = Teak.facebookAccessTokenQueue.take();
                    return ret;
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, Log.getStackTraceString(e));
                }
                return null;
            }
        });
        Teak.asyncExecutor.submit(Teak.facebookAccessToken);
    }

    /**************************************************************************/

    private static void openIABPurchaseSucceeded(String json) {
        try {
            JSONObject purchase = new JSONObject(json);
            if (Teak.isDebug) {
                Log.d(LOG_TAG, "OpenIAB purchase succeeded: " + purchase.toString(2));
            }
            JSONObject originalJson = new JSONObject(purchase.getString("originalJson"));
            purchaseSucceeded(originalJson);
        } catch (Exception e) {
            Log.e(LOG_TAG, e.toString());
        }
    }

    private static void openIABPurchaseFailed(int errorCode) {
        String sku = skuStack.pop();
        if (Teak.isDebug) {
            Log.d(LOG_TAG, "OpenIAB purchase failed (" + errorCode + ") for sku: " + sku);
        }
        purchaseFailed(errorCode, sku);
    }

    static void purchaseSucceeded(JSONObject purchaseData) {
        // TODO: Payload
        try {
            Log.d(LOG_TAG, "Purchase succeeded: " + purchaseData.toString(2));

            HashMap<String, Object> payload = new HashMap<String, Object>();
            payload.put("purchaseToken", purchaseData.get("purchaseToken"));
            payload.put("purchaseTime", purchaseData.get("purchaseTime"));
            payload.put("productId", purchaseData.get("productId"));
            payload.put("packageName", purchaseData.get("packageName"));
            payload.put("orderId", purchaseData.get("orderId"));
            payload.put("appstoreName", Teak.installerPackage);

            JSONObject skuDetails = Teak.appStore.querySkuDetails(purchaseData.getString("productId"));
            if (skuDetails != null) {
                payload.put("currencyCode", skuDetails.getString("price_currency_code"));
                payload.put("amountMicros", skuDetails.getString("price_amount_micros"));
            }

            Log.d(LOG_TAG, "Payload: " + new JSONObject(payload).toString(2));
        } catch (Exception e) {

        }
        Teak.asyncExecutor.submit(new Runnable() {
            public void run() {
                //Teak.appStore.querySkuDetails();
            }
        });
    }

    static void purchaseFailed(int errorCode, String sku) {
        // TODO: Payload
        if (Teak.isDebug) {
            Log.d(LOG_TAG, "Purchase failed (" + errorCode + ") for sku: " + sku);
        }
    }

    private static void checkActivityResultForPurchase(int resultCode, Intent data) {
        Teak.appStore.checkActivityResultForPurchase(resultCode, data);
    }
}
