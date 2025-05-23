package io.teak.app.java.dev;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.StrictMode;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.vending.billing.IInAppBillingService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;

import android.content.pm.PackageManager;
import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.core.content.ContextCompat;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.event.PushNotificationEvent;

public class MainActivity extends AppCompatActivity {
    public static final String LOG_TAG = "Teak.Example";

    @Subscribe
    public void onNotification(Teak.NotificationEvent event) {
        Log.d(LOG_TAG, event.toJSON().toString());
    }

    @Subscribe
    public void onPostLaunchSummary(Teak.PostLaunchSummaryEvent event) {
        Log.d(LOG_TAG, event.toJSON().toString());
    }

    @Subscribe
    public void onRewardlink(Teak.LaunchFromLinkEvent event) {
        Log.d(LOG_TAG, event.toJSON().toString());
    }

    @Subscribe
    public void doTheThing(Teak.RewardClaimEvent event) {
        TeakNotification.Reward rewardInfo = event.reward;
        String rewardId = rewardInfo.json.getString("teakRewardId");
        switch(rewardInfo.status) {
        case TeakNotification.Reward.GRANT_REWARD: {
          Map<String, Object> reward = rewardInfo.json.getJSONObject("reward").toMap();
          Log.d("TeakExample.Reward", "Reward with id " + rewardId + "Granted! " + reward.toString());
        } break;
        case TeakNotification.Reward.ALREADY_CLICKED: {
          Log.d("TeakExample.Reward", "You already claimed this reward!");
        } break;
        case TeakNotification.Reward.EXPIRED: {
          Log.d("TeakExample.Reward", "The reward has expired");
        } break;
        case TeakNotification.Reward.TOO_MANY_CLICKS: {
          Log.d("TeakExample.Reward", "Too many other players already claimed this reward");
        } break;
        case TeakNotification.Reward.EXCEED_MAX_CLICKS_FOR_DAY: {
          Log.d("TeakExample.Reward", "You've already claimed too many rewards today");
        } break;
        default: {
          Log.d("TeakExample.Reward", "The reward was rejected for a different reason: " + rewardInfo.json.getString("status"));
        }
        }
        if (event.reward != null) {
            final StringBuilder rewardString = new StringBuilder("You got ");
            boolean isFirstEntry = true;
            for (Map.Entry<String, Object> entry : event.reward.json.toMap().entrySet()) {
                if (isFirstEntry) {
                    isFirstEntry = false;
                } else {
                    rewardString.append(", ");
                }
                rewardString.append(String.valueOf(entry.getValue()));
                rewardString.append(" ");
                rewardString.append(entry.getKey());
            }
            rewardString.append(".");

            AlertDialog.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder = new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert);
            } else {
                builder = new AlertDialog.Builder(MainActivity.this);
            }
            builder.setTitle("Reward!")
                    .setMessage(rewardString.toString())
                    .setPositiveButton(android.R.string.yes, null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        }
    }

    // Register the permissions callback, which handles the user's response to the
    // system permissions dialog. Save the return value, an instance of
    // ActivityResultLauncher, as an instance variable.
    private ActivityResultLauncher<String> requestPermissionLauncher =
        registerForActivityResult(new RequestPermission(), isGranted -> {
            if (isGranted) {
                Log.d("MainActivity", "permission granted");
                // Permission is granted. Continue the action or workflow in your
                // app.
            } else {
                Log.d("MainActivity", "permission denied");
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        });

    // If this is not overridden, it will destroy the activity when the Back button is pressed.
    //
    // Unity: When back is pressed, nothing happens
    // Air: When back pressed, it backgrounds the app but does not destroy activity
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // For debugging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectNonSdkApiUsage()
                    .penaltyLog()
                    .build());
        }

        // Set up view
        setupViewThings();

        // Register with EventBus
        EventBus.getDefault().register(this);

        Log.d("MainActivity", "requestNotificationPermissions");
        Teak.requestNotificationPermissions();

        // Create a deep link route that opens the Google Play store to a specific SKU in your game
        Teak.registerDeepLink("/store/:sku", "Store", "Link directly to purchase an item", new Teak.DeepLink() {
            @Override
            public void call(Map<String, Object> parameters) {
                String sku = (String)parameters.get("sku");
                showPurchaseDialogForSku(sku);
            }
        });

        Teak.registerDeepLink("/slots/:slot_id", "Slots", "Link directly to a slot machine", new Teak.DeepLink() {
            @Override
            public void call(final Map<String, Object> parameters) {
                Log.d("MainActivity", "/slots/" + parameters.get("slot_id").toString() + ": " + parameters.toString());
                runOnUiThread(new Runnable() {
                    public void run() {
                        AlertDialog.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            builder = new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert);
                        } else {
                            builder = new AlertDialog.Builder(MainActivity.this);
                        }
                        builder.setTitle("Slot!")
                                .setMessage(parameters.get("slot_id").toString())
                                .setPositiveButton(android.R.string.yes, null)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    }
                });
            }
        });

        // Identify user.
        // In your game, you will want to use the same user id that you use in your database.
        //
        // These user ids should be unique, no two players should have the same user id.
        final String userId = "native-" + Build.MODEL.toLowerCase();

        final Teak.UserConfiguration userConfiguration = new Teak.UserConfiguration("pat@teak.io");
        Teak.identifyUser(userId, userConfiguration);

        // Binding the in app billing service
        Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        serviceIntent.setPackage("com.android.vending");
        bindService(serviceIntent, mServiceConn, Context.BIND_AUTO_CREATE);

        // HAX
        Teak.setStringAttribute("automated_test_string", "asdfasdfasdf");
        Teak.trackEvent("Player_Level_Up", null, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Call setIntent() for Teak
        setIntent(intent);
        super.onNewIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // If your In App Purchase activity needs to be modified to call Teak, you can do this
        // easily without needing to import Teak (useful for other libraries, etc).
        /*
        try {
            Class<?> cls = Class.forName("io.teak.sdk.Teak");
            Method m = cls.getMethod("checkActivityResultForPurchase", int.class, Intent.class);
            m.invoke(null, resultCode, data);
        } catch (Exception ignored) {} */

        // TODO: Update this to new google billing

        if (requestCode == 1001) {
            int responseCode = data.getIntExtra("RESPONSE_CODE", 0);
            String purchaseData = data.getStringExtra("INAPP_PURCHASE_DATA");
            String dataSignature = data.getStringExtra("INAPP_DATA_SIGNATURE");

            if (resultCode == RESULT_OK) {
                try {
                    JSONObject jo = new JSONObject(purchaseData);
                    String sku = jo.getString("productId");
                    int response = mService.consumePurchase(3, getPackageName(), jo.getString("purchaseToken"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    ///////
    // Not required for Teak integration

    private void simulateNotification(Context context) {
        Intent intent = new Intent();
        intent.putExtra("teakNotifId", "fake-notif-id");
        intent.putExtra("version", "1");
        intent.putExtra("message", "Teak");

        // UI template
        JSONObject teak_notif_animated = new JSONObject();
        try {
            JSONObject animated = new JSONObject();
            /*animated.put("sprite_sheet", "assets:///777-animated-phone-it-in-snow.png");
            animated.put("width", 512);
            animated.put("height", 256);*/

            animated.put("sprite_sheet", "https://assets.teakcdn.com/creative_translations-media/53532/original-base64Default.txt?1518130762");//"assets:///teak-slots-banner-sprite.jpg");
            animated.put("display_ms", 200);
            animated.put("width", 1920);
            animated.put("height", 225);

            //teak_notif_animated.put("text", "This is the text that doesn't end. Yes it goes on and on my friend. Some people started translating not knowing what it was, and they'll blow their translation budget just because...");
            //teak_notif_animated.put("view_animator", animated);
            teak_notif_animated.put("left_image", "BUILTIN_APP_ICON");
            //teak_notif_animated.put("left_image", "NONE");
            teak_notif_animated.put("notification_background", "assets:///AndroidPushGrid.png");
        } catch (Exception ignored) {
        }

        JSONObject teak_big_notif_image_text = new JSONObject();
        try {
            JSONObject button0 = new JSONObject();
            button0.put("text", "The Google");
            button0.put("deepLink", "https://google.com");
            teak_big_notif_image_text.put("button0", button0);

            JSONObject button2 = new JSONObject();
            button2.put("text", "Store");
            button2.put("deepLink", "teak1136371193060244:///store/com.teakio.pushtest.dollar");
            teak_big_notif_image_text.put("button2", button2);

            teak_big_notif_image_text.put("text", "teak_big_notif_image_text");
            teak_big_notif_image_text.put("notification_background", "assets:///1700x550.png");

            JSONObject animated = new JSONObject();
            animated.put("sprite_sheet", "assets:///pixelgrid_2000x2000.png");
            animated.put("display_ms", 200);
            animated.put("width", 2000);
            animated.put("height", 2000);

            //teak_big_notif_image_text.put("text", "This is the text that doesn't end. Yes it goes on and on my friend. Some people started translating not knowing what it was, and they'll blow their translation budget just because...");
            teak_big_notif_image_text.put("view_animator", animated);
        } catch (Exception ignored) {
        }

        // Display
        JSONObject display = new JSONObject();
        try {
            //display.put("contentView", "teak_notif_animated");
            //display.put("teak_notif_animated", teak_notif_animated);
            display.put("contentView", "teak_notif_no_title");
            display.put("teak_notif_no_title", teak_notif_animated);

            //display.put("bigContentView", "teak_big_notif_image_text");
            //display.put("teak_big_notif_image_text", teak_big_notif_image_text);
            display.put("bigContentView", "teak_big_notif_animated");
            display.put("teak_big_notif_animated", teak_big_notif_image_text);
        } catch (Exception ignored) {
        }

        // Add display to intent
        intent.putExtra("display", display.toString());

        TeakEvent.postEvent(new PushNotificationEvent(PushNotificationEvent.Received, context, intent));
    }

    private void showPurchaseDialogForSku(final String sku) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    Bundle buyIntentBundle = mService.getBuyIntent(3, MainActivity.this.getPackageName(), sku, "inapp", "");
                    PendingIntent pendingIntent = buyIntentBundle.getParcelable("BUY_INTENT");
                    MainActivity.this.startIntentSenderForResult(pendingIntent.getIntentSender(), 1001, new Intent(), 0, 0, 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        EventBus.getDefault().unregister(this);

        if (mService != null) {
            unbindService(mServiceConn);
        }
    }

    public void testNotification(View view) {
        // Schedule Notification
        scheduleTestNotification("Animated", "Default text", "5");

        // Background the app
        moveTaskToBack(true);

        // Simulate Notification
//        final Handler handler = new Handler();
//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                simulateNotification(MainActivity.this);
//            }
//        }, 1000);
    }

    public void makePurchase(View view) {
        showPurchaseDialogForSku("io.teak.app.sku.dollar");
    }

    public void crashApp(View view) {
//        throw new RuntimeException("I crashed the app!");
//        Teak.log.exception(new Raven.ReportTestException(Teak.SDKVersion));
//        Teak.incrementEvent("debug_increment", null, null, 5);
//        android.os.Process.sendSignal(android.os.Process.myPid(), android.os.Process.SIGNAL_QUIT);
//        Teak.setChannelState(Teak.Channel.Type.PlatformPush, Teak.Channel.State.Available);
//        Teak.openNotificationSettings();
//        Teak.Notification.schedule("test_none", 5);
        Log.d(LOG_TAG, Teak.Channel.getCategoriesJson());
    }

    public void integrationTestTimeout(String timeout) {
        try {
            Class c = Class.forName("io.teak.sdk.Session");
            Field field = c.getDeclaredField("SAME_SESSION_TIME_DELTA");
            field.setAccessible(true);
            field.set(null, Integer.parseInt(timeout));
        } catch (Exception e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    public void scheduleTestNotification(String id, String defaultText, String delay) {
        TeakNotification.scheduleNotification(id, defaultText, Integer.parseInt(delay));
    }

    private Bitmap loadBitmapFromUriString(String bitmapUriString) throws Exception {
        final ActivityManager am = (ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE);
        final int deviceMemoryClass = am == null ? 0 : am.getMemoryClass();

        final DisplayMetrics displayMetrics = new DisplayMetrics();
        final WindowManager wm = ((WindowManager) this.getSystemService(Context.WINDOW_SERVICE));
        if (wm != null) {
            wm.getDefaultDisplay().getMetrics(displayMetrics);
        }

        final Uri bitmapUri = Uri.parse(bitmapUriString);
        Bitmap ret = null;
        try {
            if (bitmapUri.getScheme().equals("assets")) {
                String assetFilePath = bitmapUri.getPath();
                assetFilePath = assetFilePath.startsWith("/") ? assetFilePath.substring(1) : assetFilePath;
                InputStream is = this.getAssets().open(assetFilePath);
                ret = BitmapFactory.decodeStream(is);
                is.close();
            } else {
                // Add the "well behaved heap size" as a query param
                final Uri.Builder uriBuilder = Uri.parse(bitmapUriString).buildUpon();
                uriBuilder.appendQueryParameter("device_memory_class", String.valueOf(deviceMemoryClass));
                uriBuilder.appendQueryParameter("xdpi", String.valueOf(displayMetrics.xdpi));
                uriBuilder.appendQueryParameter("ydpi", String.valueOf(displayMetrics.ydpi));
                uriBuilder.appendQueryParameter("width", String.valueOf(displayMetrics.widthPixels));
                uriBuilder.appendQueryParameter("height", String.valueOf(displayMetrics.heightPixels));
                uriBuilder.appendQueryParameter("density", String.valueOf(displayMetrics.density));
                uriBuilder.appendQueryParameter("density_dpi", String.valueOf(displayMetrics.densityDpi));
                uriBuilder.appendQueryParameter("scaled_density", String.valueOf(displayMetrics.scaledDensity));

                URL aURL = new URL(uriBuilder.toString());
                URLConnection conn = aURL.openConnection();
                conn.connect();
                InputStream is = conn.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(is);
                ret = BitmapFactory.decodeStream(bis);
                bis.close();
                is.close();
            }
        } catch (OutOfMemoryError ignored) {
            // Request lower-res version?
        } catch (SocketException ignored) {
        } catch (SSLException ignored) {
        } catch (FileNotFoundException ignored) {
        }
        return ret;
    }

    private void setupViewThings() {
        setContentView(R.layout.activity_main);
        final TextView deviceMetricsView = findViewById(R.id.device_metrics);
        final TextView noncompatDeviceMetricsView = findViewById(R.id.noncompat_device_metrics);

        // Relevant Metrics
        final ActivityManager am = (ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE);
        final int deviceMemoryClass = am == null ? 0 : am.getMemoryClass();

        final DisplayMetrics displayMetrics = new DisplayMetrics();
        final WindowManager wm = ((WindowManager) this.getSystemService(Context.WINDOW_SERVICE));
        if (wm != null) {
            wm.getDefaultDisplay().getMetrics(displayMetrics);
        }
        deviceMetricsView.setText(String.format(Locale.US, "device_memory_class: %d\nxdpi: %f\nydpi: %f\nwidth: %d\nheight: %d\ndensity: %f\ndensity_dpi: %d\nscaled_density: %f",
                deviceMemoryClass,
                displayMetrics.xdpi, displayMetrics.ydpi,
                displayMetrics.widthPixels, displayMetrics.heightPixels,
                displayMetrics.density, displayMetrics.densityDpi, displayMetrics.scaledDensity));

        // Really special
        final Configuration configuration = getResources().getConfiguration();
        try {
            noncompatDeviceMetricsView.setText(String.format(Locale.US, "screen_width_dp: %d\nscreen_height_dp: %d", configuration.screenWidthDp, configuration.screenHeightDp));
        } catch (Exception ignored) {
        }
    }

    static IInAppBillingService mService;
    ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name,
                                       IBinder service) {
            mService = IInAppBillingService.Stub.asInterface(service);

            // Clear inventory
            if (true) {
                try {
                    Bundle ownedItems = mService.getPurchases(3, getPackageName(), "inapp", null);
                    int response = ownedItems.getInt("RESPONSE_CODE");
                    if (response == 0) {
                        ArrayList<String>  purchaseDataList = ownedItems.getStringArrayList("INAPP_PURCHASE_DATA_LIST");

                        for (int i = 0; i < purchaseDataList.size(); ++i) {
                            String purchaseData = purchaseDataList.get(i);
                            Log.d(LOG_TAG, purchaseData);
                            try {
                                JSONObject purchase = new JSONObject(purchaseData);
                                mService.consumePurchase(3, getPackageName(), purchase.getString("purchaseToken"));
                            } catch (Exception e) {
                                Log.e(LOG_TAG, Log.getStackTraceString(e));
                            }

                            // do something with this purchase information
                            // e.g. display the updated list of products owned by user
                            //response =
                        }

                        // if continuationToken != null, call getPurchases again
                        // and pass in the token to retrieve more items
                    }

                } catch (Exception e) {
                    Log.e(LOG_TAG, Log.getStackTraceString(e));
                }
            }
        }
    };
}
