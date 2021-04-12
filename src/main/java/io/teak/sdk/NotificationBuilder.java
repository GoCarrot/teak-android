package io.teak.sdk;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannedString;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewFlipper;
import androidx.core.app.NotificationCompat;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONObject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

public class NotificationBuilder {
    public static class AssetLoadException extends Exception {
        AssetLoadException(String assetName) {
            super("Failed to load asset: " + assetName);
        }
    }

    public static Notification createNativeNotification(Context context, Bundle bundle, TeakNotification teakNotificaton) throws AssetLoadException {
        if (teakNotificaton.notificationVersion == TeakNotification.TEAK_NOTIFICATION_V0) {
            return null;
        }

        try {
            return createNativeNotificationV1Plus(context, bundle, teakNotificaton);
        } catch (AssetLoadException e) {
            throw e;
        } catch (Exception e) {
            HashMap<String, Object> extras = new HashMap<>();
            if (teakNotificaton.teakCreativeName != null) {
                extras.put("teakCreativeName", teakNotificaton.teakCreativeName);
            }
            Teak.log.exception(e, extras);

            // TODO: Report to the 'callback' URL on the push when/if we implement that
            return null;
        }
    }

    private static String notificationChannelId;
    public static String getNotificationChannelId(Context context) {
        // Notification channel, required for running on API 26+
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationChannelId == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            try {
                final String channelId = "teak";
                if (notificationManager.getNotificationChannel(channelId) == null) {
                    final int importance = NotificationManager.IMPORTANCE_HIGH;
                    final NotificationChannel channel = new NotificationChannel(channelId, "Notifications", importance);
                    channel.enableLights(true);
                    channel.setLightColor(Color.RED);
                    channel.enableVibration(true);
                    channel.setVibrationPattern(new long[] {100L, 300L, 0L, 0L, 100L, 300L});
                    notificationManager.createNotificationChannel(channel);
                }
                notificationChannelId = channelId;
            } catch (Exception ignored) {
            }
        }
        return notificationChannelId;
    }

    private static String quietNotificationChannelId;
    public static String getQuietNotificationChannelId(Context context) {
        // Notification channel, required for running on API 26+
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (quietNotificationChannelId == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            try {
                final String channelId = "teak-no-sound-or-vibrate";
                final int importance = NotificationManager.IMPORTANCE_HIGH;
                final NotificationChannel channel = new NotificationChannel(channelId, "Notifications", importance);
                channel.enableLights(true);
                channel.setSound(null, null);
                channel.setLightColor(Color.RED);
                channel.enableVibration(false);
                channel.setVibrationPattern(new long[] {0L});
                notificationManager.createNotificationChannel(channel);
                quietNotificationChannelId = channelId;
            } catch (Exception ignored) {
            }
        }
        return quietNotificationChannelId;
    }

    @SuppressWarnings("deprecation")
    private static Spanned fromHtml(String string) {
        return Html.fromHtml(string);
    }

    private static Notification createNativeNotificationV1Plus(final Context context, final Bundle bundle, final TeakNotification teakNotificaton) throws Exception {
        // Get the memory class here, don't rely on TeakConfiguration
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final int deviceMemoryClass = am == null ? 0 : am.getMemoryClass();

        final DisplayMetrics displayMetrics = new DisplayMetrics();
        final WindowManager wm = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE));
        if (wm != null) {
            wm.getDefaultDisplay().getMetrics(displayMetrics);
        }

        // For Android 11+ it will remove RemoteViews over a certain size, get that size
        final int config_notificationStripRemoteViewSizeBytes_Id = context.getResources().getIdentifier("config_notificationStripRemoteViewSizeBytes", "integer", "android");
        final int config_notificationStripRemoteViewSizeBytes = config_notificationStripRemoteViewSizeBytes_Id > 0 ? context.getResources().getInteger(config_notificationStripRemoteViewSizeBytes_Id) : 0;

        // Should this notification display both the small view and the big view when expanded?
        final boolean displayContentViewAboveBigContentView = teakNotificaton.display.optBoolean("displayContentViewAboveBigContentView", false);

        // Because we can't be certain that the R class will line up with what is at SDK build time
        // like in the case of Unity et. al.
        class IdHelper {
            public int id(String identifier) {
                int ret = context.getResources().getIdentifier(identifier, "id", context.getPackageName());
                if (ret == 0) {
                    throw new Resources.NotFoundException("Could not find R.id." + identifier);
                }
                return ret;
            }

            public int layout(String identifier) {
                int ret = context.getResources().getIdentifier(identifier, "layout", context.getPackageName());
                if (ret == 0) {
                    throw new Resources.NotFoundException("Could not find R.layout." + identifier);
                }
                return ret;
            }

            public int integer(String identifier) {
                int ret = context.getResources().getIdentifier(identifier, "integer", context.getPackageName());
                if (ret == 0) {
                    throw new Resources.NotFoundException("Could not find R.integer." + identifier);
                }
                return context.getResources().getInteger(ret);
            }

            public int drawable(String identifier) {
                int ret = context.getResources().getIdentifier(identifier, "drawable", context.getPackageName());
                if (ret == 0) {
                    throw new Resources.NotFoundException("Could not find R.drawable." + identifier);
                }
                return ret;
            }
        }
        final IdHelper R = new IdHelper(); // Declaring local as 'R' ensures we don't accidentally use the other R

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, getNotificationChannelId(context));
        builder.setGroup(UUID.randomUUID().toString());

        // Set visibility of our notifications to public
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            } catch (Exception ignored) {
            }
        }

        // Configure notification behavior
        builder.setPriority(NotificationCompat.PRIORITY_MAX);
        builder.setDefaults(NotificationCompat.DEFAULT_ALL);
        builder.setOnlyAlertOnce(true);
        builder.setAutoCancel(true);

        // Rich text message
        Spanned richMessageText = new SpannedString(teakNotificaton.message);
        try {
            richMessageText = fromHtml(teakNotificaton.message);
        } catch (Exception e) {
            if (!bundle.getBoolean("teakUnitTest")) {
                throw e;
            }
        }

        try {
            builder.setTicker(richMessageText);
        } catch (Exception e) {
            if (!bundle.getBoolean("teakUnitTest")) {
                throw e;
            }
        }

        // Icon accent color (added in API 21 version of Notification builder, so use reflection)
        try {
            Method setColor = builder.getClass().getMethod("setColor", int.class);
            if (setColor != null) {
                setColor.invoke(builder, R.integer("io_teak_notification_accent_color"));
            }
        } catch (Exception ignored) {
        }

        // Get app icon
        int tempAppIconResourceId;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), 0);
            tempAppIconResourceId = ai.icon;
        } catch (Exception e) {
            Teak.log.e("notification_builder", "Unable to load app icon resource for Notification.");
            return null;
        }
        final int appIconResourceId = tempAppIconResourceId;

        // Assign notification icons
        int smallNotificationIcon = appIconResourceId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                smallNotificationIcon = R.drawable("io_teak_small_notification_icon");
            } catch (Exception ignored) {
            }
        }

        Bitmap largeNotificationIcon = null;
        try {
            largeNotificationIcon = BitmapFactory.decodeResource(context.getResources(),
                R.drawable("io_teak_large_notification_icon"));
        } catch (Exception ignored) {
        }

        builder.setSmallIcon(smallNotificationIcon);
        if (largeNotificationIcon != null) {
            builder.setLargeIcon(largeNotificationIcon);
        }

        // Intent creation helper
        final Random rng = new Random();
        final ComponentName cn = new ComponentName(context.getPackageName(), "io.teak.sdk.Teak");
        class PendingIntentHelper {
            PendingIntent forActionButton(String action, String deepLink) {
                Bundle bundleCopy = new Bundle(bundle);
                if (deepLink != null) {
                    bundleCopy.putString("teakDeepLink", deepLink);
                    bundleCopy.putBoolean("closeSystemDialogs", true);
                }
                Intent pushOpenedIntent = new Intent(action);
                pushOpenedIntent.putExtras(bundleCopy);
                pushOpenedIntent.setComponent(cn);
                return PendingIntent.getBroadcast(context, rng.nextInt(), pushOpenedIntent, PendingIntent.FLAG_ONE_SHOT);
            }

            PendingIntent get(String action) {
                return forActionButton(action, null);
            }
        }
        final PendingIntentHelper pendingIntent = new PendingIntentHelper();

        try {
            // Create intent to fire if/when notification is cleared
            builder.setDeleteIntent(pendingIntent.get(context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX));

            // Create intent to fire if/when notification is opened, attach bundle info
            builder.setContentIntent(pendingIntent.get(context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX));
        } catch (Exception e) {
            if (!bundle.getBoolean("teakUnitTest")) {
                throw e;
            }
        }

        // Notification builder
        Notification tempNotification;
        try {
            tempNotification = builder.build();
        } catch (Exception e) {
            if (!bundle.getBoolean("teakUnitTest")) {
                throw e;
            } else {
                final Notification notification = new Notification();
                notification.flags = Integer.MAX_VALUE;
                return notification;
            }
        }
        final Notification nativeNotification = tempNotification;

        class ViewBuilder {
            private RemoteViews buildViews(String name, boolean isLargeView) throws Exception {
                // Run the GC
                Helpers.runAndLogGC("notification_builder.gc");

                final int viewLayout = R.layout(name);
                final RemoteViews remoteViews = new RemoteViews(context.getPackageName(), viewLayout);

                // To let us query for information about the view
                final LayoutInflater factory = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                if (factory == null) {
                    throw new Exception("Unable to get LayoutInflater service.");
                }

                // ViewFlipper must inflate on main thread
                FutureTask<View> viewInflaterRunnable = new FutureTask<>(new Callable<View>() {
                    @Override
                    public View call() throws Exception {
                        return factory.inflate(viewLayout, null);
                    }
                });
                new Handler(Looper.getMainLooper()).post(viewInflaterRunnable);
                View inflatedView = viewInflaterRunnable.get();

                // Configure view
                JSONObject viewConfig = teakNotificaton.display.getJSONObject(name);
                Iterator<String> keys = viewConfig.keys();

                // Action buttons
                final boolean[] actionButtonsConfigured = new boolean[3];

                while (keys.hasNext()) {
                    String key = keys.next();

                    // 3.5.0+ the new 'oom_notification_background' key will be skipped
                    int tempViewElementId;
                    try {
                        tempViewElementId = R.id(key);
                    } catch (Exception e) {
                        continue;
                    }

                    final int viewElementId = tempViewElementId;
                    final View viewElement = inflatedView.findViewById(viewElementId);

                    //noinspection StatementWithEmptyBody
                    if (isUIType(viewElement, Button.class)) {
                        // Button must go before TextView, because Button is a TextView

                        // Action button bar
                        try {
                            final int actionButtonIndex = Integer.parseInt(key.substring(6));
                            actionButtonsConfigured[actionButtonIndex] = true;
                        } catch (Exception ignored) {
                        }

                        final JSONObject buttonConfig = viewConfig.getJSONObject(key);
                        remoteViews.setTextViewText(viewElementId, buttonConfig.getString("text"));
                        String deepLink = buttonConfig.has("deepLink") ? buttonConfig.getString("deepLink") : null;
                        remoteViews.setOnClickPendingIntent(viewElementId, pendingIntent.forActionButton(
                                                                               context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX, deepLink));
                    } else if (isUIType(viewElement, TextView.class)) {
                        final String value = viewConfig.getString(key);
                        remoteViews.setTextViewText(viewElementId, fromHtml(value));
                    } else //noinspection StatementWithEmptyBody
                        if (isUIType(viewElement, ImageButton.class)) {
                        // ImageButton must go before ImageView, because ImageButton is a ImageView
                    } else if (isUIType(viewElement, ImageView.class)) {
                        final String value = viewConfig.getString(key);
                        if (value.equalsIgnoreCase("BUILTIN_APP_ICON")) {
                            remoteViews.setImageViewResource(viewElementId, appIconResourceId);
                        } else if (value.equalsIgnoreCase("NONE")) {
                            remoteViews.setViewVisibility(viewElementId, View.GONE);
                        } else {
                            final Bitmap bitmap = loadNotificationBackgroundWithOOMFallbacks(viewConfig);
                            if (bitmap == null) {
                                if ("left_image".equals(key)) {
                                    remoteViews.setViewVisibility(viewElementId, View.GONE);
                                } else {
                                    throw new AssetLoadException(value);
                                }
                            } else {
                                remoteViews.setImageViewBitmap(viewElementId, bitmap);
                            }
                        }
                    } else if (isUIType(viewElement, ViewFlipper.class)) {
                        final AnimationConfiguration animationConfig = loadAnimationConfigWithOOMFallbacks(viewConfig);
                        if (animationConfig == null) {
                            final JSONObject jsonConfig = viewConfig.getJSONObject("view_animator");
                            throw new AssetLoadException(jsonConfig.getString("sprite_sheet"));
                        } else if (animationConfig.spriteSheet == null) {
                            throw new AssetLoadException(animationConfig.spriteSheetUrl);
                        }
                        final Bitmap bitmap = animationConfig.spriteSheet;
                        final int frameWidth = animationConfig.width;
                        final int frameHeight = animationConfig.height;
                        final int msPerFrame = animationConfig.displayMs;

                        final int numCols = bitmap.getWidth() / frameWidth;
                        final int numRows = bitmap.getHeight() / frameHeight;

                        for (int x = 0; x < numCols; x++) {
                            for (int y = 0; y < numRows; y++) {
                                final int startX = x * frameWidth;
                                final int startY = y * frameHeight;
                                Bitmap frame = Bitmap.createBitmap(bitmap, startX, startY, frameWidth, frameHeight);

                                if (frame == null) {
                                    throw new IllegalArgumentException("Frame [" + x + ", " + y + "] is null (" + animationConfig.spriteSheetUrl + ")");
                                }

                                final RemoteViews frameView = new RemoteViews(context.getPackageName(),
                                    isLargeView ? R.layout("teak_big_frame") : R.layout("teak_frame"));
                                final int frameViewId = R.id("notification_background");
                                frameView.setImageViewBitmap(frameViewId, frame);
                                remoteViews.addView(viewElementId, frameView);
                            }
                        }

                        // Set frame rate
                        remoteViews.setInt(viewElementId, "setFlipInterval", msPerFrame);

                        // Mark notification as containing animated element(s)
                        teakNotificaton.isAnimated = true;
                    }
                    // TODO: Else, report error to dashboard.
                }

                // Button bar show/hide
                if (actionButtonsConfigured[0] || actionButtonsConfigured[1] || actionButtonsConfigured[2]) {
                    try {
                        // Unhide action button bar
                        final int actionButtonLayoutId = R.id("actionButtonLayout");
                        remoteViews.setViewVisibility(actionButtonLayoutId, View.VISIBLE);

                        // Hide unused buttons
                        for (int i = 0; i < actionButtonsConfigured.length; i++) {
                            final int actionButtonId = R.id("button" + i);
                            remoteViews.setViewVisibility(actionButtonId, actionButtonsConfigured[i] ? View.VISIBLE : View.GONE);
                        }

                        // Hide unused dividers
                        if (!actionButtonsConfigured[1]) {
                            final int dividerButton1_2 = R.id("divider_button1_button2");
                            remoteViews.setViewVisibility(dividerButton1_2, View.GONE);
                        }

                        if (!actionButtonsConfigured[0]) {
                            final int dividerButton0_1 = R.id("divider_button0_button1");
                            remoteViews.setViewVisibility(dividerButton0_1, View.GONE);
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    // Hide action button bar
                    try {
                        final int actionButtonLayoutId = R.id("actionButtonLayout");
                        remoteViews.setViewVisibility(actionButtonLayoutId, View.GONE);
                    } catch (Exception ignored) {
                    }
                }

                return remoteViews;
            }

            class AnimationConfiguration {
                public String spriteSheetUrl;
                public Bitmap spriteSheet;
                public int width;
                public int height;
                public int displayMs;
            }

            private AnimationConfiguration loadAnimationConfigWithOOMFallbacks(JSONObject viewConfig) throws OutOfMemoryError {
                final AnimationConfiguration ret = new AnimationConfiguration();
                final JSONObject initialAnimationConfig = viewConfig.getJSONObject("view_animator");
                try {
                    ret.spriteSheetUrl = initialAnimationConfig.getString("sprite_sheet");
                    ret.spriteSheet = loadBitmapFromUriString(ret.spriteSheetUrl);
                    ret.width = initialAnimationConfig.getInt("width");
                    ret.height = initialAnimationConfig.getInt("height");
                    ret.displayMs = initialAnimationConfig.optInt("display_ms", 500);
                    return ret;
                } catch (OutOfMemoryError e) {
                    Teak.log.e("oom.animation.initial", initialAnimationConfig.getString("sprite_sheet"));

                    final JSONArray oomFallbacks = viewConfig.optJSONArray("oom_view_animator");
                    if (oomFallbacks != null) {
                        for (int i = 0; i < oomFallbacks.length(); i++) {
                            final JSONObject fallbackAnimationConfig = oomFallbacks.getJSONObject(i);
                            try {
                                ret.spriteSheetUrl = fallbackAnimationConfig.getString("sprite_sheet");
                                ret.spriteSheet = loadBitmapFromUriString(ret.spriteSheetUrl);
                                ret.width = fallbackAnimationConfig.getInt("width");
                                ret.height = fallbackAnimationConfig.getInt("height");
                                ret.displayMs = fallbackAnimationConfig.optInt("display_ms", 500);
                                Teak.log.i("oom.animation.fallback", ret.spriteSheetUrl);
                                return ret;
                            } catch (OutOfMemoryError ignored) {
                                Teak.log.e("oom.animation.fallback", fallbackAnimationConfig.getString("sprite_sheet"));
                            }
                        }

                        // No other fallbacks
                        final OutOfMemoryError err = new OutOfMemoryError("All OOM fallbacks exceeded.");
                        err.initCause(e);
                        throw err;
                    }
                }
                return null;
            }

            private Bitmap loadNotificationBackgroundWithOOMFallbacks(JSONObject viewConfig) throws OutOfMemoryError {
                try {
                    return loadBitmapFromUriString(viewConfig.getString("notification_background"));
                } catch (OutOfMemoryError e) {
                    Teak.log.e("oom.image.initial", viewConfig.getString("notification_background"));

                    final JSONArray oomFallbacks = viewConfig.optJSONArray("oom_notification_background");
                    if (oomFallbacks != null) {
                        for (int i = 0; i < oomFallbacks.length(); i++) {
                            try {
                                final Bitmap ret = loadBitmapFromUriString(oomFallbacks.getString(i));
                                Teak.log.i("oom.image.fallback", oomFallbacks.getString(i));
                                return ret;
                            } catch (OutOfMemoryError ignored) {
                                Teak.log.e("oom.animation.fallback", oomFallbacks.getString(i));
                            }
                        }

                        // No other fallbacks
                        final OutOfMemoryError err = new OutOfMemoryError("All OOM fallbacks exceeded.");
                        err.initCause(e);
                        throw err;
                    }
                }
                return null;
            }

            private Bitmap loadBitmapFromUriString(String bitmapUriString) throws OutOfMemoryError {
                final Uri bitmapUri = Uri.parse(bitmapUriString);
                Bitmap ret = null;
                InputStream inputStream = null;
                try {
                    if (bitmapUri.getScheme() != null &&
                        bitmapUri.getScheme().equals("assets")) {
                        String assetFilePath = bitmapUri.getPath();
                        if (assetFilePath != null) {
                            assetFilePath = assetFilePath.startsWith("/") ? assetFilePath.substring(1) : assetFilePath;
                            inputStream = context.getAssets().open(assetFilePath);
                            ret = BitmapFactory.decodeStream(inputStream);
                        }
                    } else {
                        // Add the "well behaved heap size" as a query param
                        final Uri.Builder uriBuilder = Uri.parse(bitmapUriString).buildUpon();
                        uriBuilder.appendQueryParameter("device_memory_class", String.valueOf(deviceMemoryClass));
                        uriBuilder.appendQueryParameter("scaled_density", String.valueOf(displayMetrics.scaledDensity));
                        if (config_notificationStripRemoteViewSizeBytes > 0) {
                            uriBuilder.appendQueryParameter("remote_view_byte_limit", String.valueOf(config_notificationStripRemoteViewSizeBytes));
                        }

                        URL aURL = new URL(uriBuilder.toString());
                        HttpsURLConnection conn = (HttpsURLConnection) aURL.openConnection();
                        conn.setUseCaches(true);
                        conn.connect();
                        inputStream = conn.getInputStream();
                        ret = BitmapFactory.decodeStream(inputStream);
                    }
                } catch (SocketException ignored) {
                } catch (SSLException ignored) {
                } catch (UnknownHostException ignored) {
                } catch (FileNotFoundException ignored) {
                } catch (SocketTimeoutException ignored) {
                } catch (IOException ignored) {
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                return ret;
            }

            private boolean isUIType(View viewElement, Class clazz) {
                // TODO: Do more error checking to see if this is an AppCompat* class, and don't use InstanceOf
                return clazz.isInstance(viewElement);
            }

            private RemoteViews buildViews(String name) throws Exception {
                return this.buildViews(name, false);
            }

            private RemoteViews buildLargeViews(String name) throws Exception {
                try {
                    return this.buildViews(name, true);
                } catch (OutOfMemoryError e) {
                    return null;
                }
            }
        }
        ViewBuilder viewBuilder = new ViewBuilder();

        // Configure 'contentView'
        class Deprecated {
            @SuppressWarnings("deprecation")
            private void assignDeprecated(RemoteViews remoteViews) {
                nativeNotification.contentView = remoteViews;
            }
        }
        final Deprecated deprecated = new Deprecated();
        deprecated.assignDeprecated(viewBuilder.buildViews(teakNotificaton.display.getString("contentView")));

        // Check for Jellybean (API 16, 4.1)+ for expanded view
        RemoteViews bigContentView = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && teakNotificaton.display.has("bigContentView")) {
            try {
                bigContentView = viewBuilder.buildLargeViews(teakNotificaton.display.getString("bigContentView"));

                // Assign small content view to display above big content view, if that's what the notification wants
                if (bigContentView != null && displayContentViewAboveBigContentView) {
                    final RemoteViews frameView = viewBuilder.buildViews(teakNotificaton.display.getString("contentView"));
                    bigContentView.addView(R.id("small_view_container"), frameView);
                } else if (bigContentView != null) {
                    bigContentView.setViewVisibility(R.id("small_view_container"), View.GONE);
                }
            } catch (Exception e) {
                HashMap<String, Object> extras = new HashMap<>();
                if (teakNotificaton.teakCreativeName != null) {
                    extras.put("teakCreativeName", teakNotificaton.teakCreativeName);
                }
                Teak.log.exception(e, extras);
            }
        }

        // Assign expanded view if it's there
        if (bigContentView != null) {
            try {
                // Use reflection to avoid compile-time issues
                final Field bigContentViewField = nativeNotification.getClass().getField("bigContentView");
                bigContentViewField.set(nativeNotification, bigContentView);
            } catch (Exception ignored) {
            }
        }

        return nativeNotification;
    }
}
