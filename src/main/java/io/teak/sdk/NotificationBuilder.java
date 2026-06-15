package io.teak.sdk;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Result;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONObject;

import android.service.notification.StatusBarNotification;

public class NotificationBuilder {
    public static final String DEFAULT_NOTIFICATION_CHANNEL_ID = "teak";
    public static class AssetLoadException extends Exception {
        AssetLoadException(String assetName, Exception cause) {
            super("Failed to load asset: " + assetName, cause);
        }
    }

    static class ResourceHelper {
        private final Context context;
        public final int appIconResourceId;

        public ResourceHelper(@NonNull final Context context) {
            this.context = context;
            // Get app icon
            int tempAppIconResourceId;
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), 0);
                tempAppIconResourceId = ai.icon;
            } catch (Exception e) {
                Teak.log.e("notification_builder", "Unable to load app icon resource for Notification.");
                tempAppIconResourceId = -1;
            }
            this.appIconResourceId = tempAppIconResourceId;
        }

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

    static class PendingIntentHelper {
        private final Context context;
        private final SecureRandom rng;
        private final ComponentName cn;
        private final Bundle bundle;

        PendingIntentHelper(Context context, Bundle bundle) {
            this.context = context;
            this.bundle = bundle;
            this.rng = new SecureRandom();
            this.cn = new ComponentName(context.getPackageName(), "io.teak.sdk.Teak");
        }

        PendingIntent getDeleteIntent() {
            final String action = context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX;
            final Bundle bundleCopy = new Bundle(bundle);
            final Intent deleteIntent = new Intent(action);
            deleteIntent.putExtras(bundleCopy);
            deleteIntent.setComponent(cn);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getBroadcast(context, rng.nextInt(), deleteIntent, flags);
        }

        PendingIntent getLaunchIntent(String deepLink) {
            // If this is Android 11 or 12, direct-launch the app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return getLaunchIntentInternal(deepLink);
            } else {
                return getTrampolineIntent(deepLink);
            }
        }

        private PendingIntent getLaunchIntentInternal(String deepLink) {
            final Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent == null) {
                return null;
            }

            final Bundle bundleCopy = new Bundle(bundle);
            if (deepLink != null) {
                bundleCopy.putString("teakDeepLink", deepLink);
            }
            bundleCopy.putBoolean("closeSystemDialogs", true);
            launchIntent.putExtras(bundleCopy);

            // https://stackoverflow.com/questions/5502427/resume-application-and-stack-from-notification/5502950#5502950
            launchIntent.setPackage(null);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            return PendingIntent.getActivity(context, rng.nextInt(), launchIntent, flags);
        }

        private PendingIntent getTrampolineIntent(String deepLink) {
            final String action = context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX;
            final Bundle bundleCopy = new Bundle(bundle);
            if (deepLink != null) {
                bundleCopy.putString("teakDeepLink", deepLink);
                bundleCopy.putBoolean("closeSystemDialogs", true);
            }
            final Intent pushOpenedIntent = new Intent(action);
            pushOpenedIntent.putExtras(bundleCopy);
            pushOpenedIntent.setComponent(cn);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getBroadcast(context, rng.nextInt(), pushOpenedIntent, flags);
        }
    }

    private static void addDefaultIcons(Context context, ResourceHelper R, NotificationCompat.Builder builder) {
        int smallNotificationIcon = R.appIconResourceId;
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

        // Icon accent color (added in API 21 version of Notification builder, so use reflection)
        try {
            Method setColor = builder.getClass().getMethod("setColor", int.class);
            setColor.invoke(builder, R.integer("io_teak_notification_accent_color"));
        } catch (Exception ignored) {
        }
    }

    public static Notification createSummaryNotification(Context context, String groupKey, ArrayList<Notification> notifications) {
        final Notification mostRecentNotif = notifications.get(0);
        final String notificationChannelId = NotificationCompat.getChannelId(mostRecentNotif);
        final ResourceHelper R = new ResourceHelper(context);
        final int notificationCount = notifications.size();
        final PackageManager pm = context.getPackageManager();
        String applicationName = "";
        try {
            final ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), 0);
            applicationName = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            Teak.log.exception(e);
        }

        final String contentTitleTemplate = null;
        final String contentTextTemplate = "{{ notification_count }} new messages";

        String contentTitle = null;
        if(contentTitleTemplate != null) {
            contentTitle = contentTitleTemplate.replaceAll("\\{\\{\\h*notification_count\\h*\\}\\}", Integer.toString(notificationCount));
        } else {
            contentTitle = applicationName;
        }

        final String contentText = contentTextTemplate.replaceAll("\\{\\{\\h*notification_count\\h*\\}\\}", Integer.toString(notificationCount));
        final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle().setBigContentTitle(contentText);

        for(Notification notification : notifications) {
            final Bundle extras = notification.extras;
            final String notifTitle = extras.getString(NotificationCompat.EXTRA_TITLE);
            final String notifText = extras.getString(NotificationCompat.EXTRA_TEXT);
            final String title = notifTitle == null ? "" : notifTitle;
            final String text = notifText == null ? "" : notifText;
            if(title.length() > 0 || text.length() > 0) {
                final SpannableString line = new SpannableString(title + text);
                if(title.length() > 0) {
                    line.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), 0);
                }
                style.addLine(line);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, notificationChannelId);
        addDefaultIcons(context, R, builder);
        builder.setDeleteIntent(mostRecentNotif.deleteIntent);
        builder.setContentIntent(mostRecentNotif.contentIntent);

        return builder.setGroup(groupKey)
                .setGroupSummary(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentText(contentText)
                .setContentTitle(contentTitle)
                .setStyle(style)
                .build();

    }

    private static boolean isHostAppDebug() {
        try {
            return TeakConfiguration.get().debugConfiguration.isDebug();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static Notification createNativeNotification(Context context, TeakNotification teakNotificaton) throws AssetLoadException {
        if (teakNotificaton.notificationVersion == TeakNotification.TEAK_NOTIFICATION_V0) {
            return null;
        }

        try {
            return createNativeNotificationV1Plus(context, teakNotificaton);
        } catch (AssetLoadException e) {
            throw e;
        } catch (Exception e) {
            HashMap<String, Object> extras = new HashMap<>();
            if (teakNotificaton.teakCreativeName != null) {
                extras.put("teakCreativeName", teakNotificaton.teakCreativeName);
            }
            // Layout/RemoteViews quirks (e.g. Android 15) are outside our control — suppress in
            // production. Report in debug builds so our own bugs remain visible during QA.
            Teak.log.exception(e, extras, isHostAppDebug());

            // TODO: Report to the 'callback' URL on the push when/if we implement that
            return null;
        }
    }

    private static String channelIdForOptOutId(Context context, String optOutId) {
        if (Helpers.isNullOrEmpty(optOutId)) {
            return NotificationBuilder.DEFAULT_NOTIFICATION_CHANNEL_ID;
        }

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            if (notificationManager.getNotificationChannel(optOutId) != null) {
                return optOutId;
            }
        }
        return NotificationBuilder.DEFAULT_NOTIFICATION_CHANNEL_ID;
    }

    public static void configureNotificationChannelId(Context context, Teak.Channel.Category category) {
        // Notification channel, required for running on API 26+
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            try {
                final int importance = NotificationManager.IMPORTANCE_HIGH;
                final NotificationChannel channel = new NotificationChannel(category.id, category.name, importance);

                // The following settings can't be changed after a channel is created
                if (notificationManager.getNotificationChannel(category.id) == null) {
                    channel.enableLights(true);
                    channel.setLightColor(Color.RED);
                    channel.enableVibration(true);
                    channel.setVibrationPattern(new long[] {100L, 300L, 0L, 0L, 100L, 300L});
                    channel.setShowBadge(category.showBadge);
                }
                channel.setName(category.name);
                channel.setDescription(category.description);

                if (category.sound != null) {
                    Uri soundUri = null;
                    try {
                        final Resources resources = context.getResources();
                        final String packageName = context.getPackageName();
                        final int soundId = resources.getIdentifier(category.sound, "raw", packageName);
                        if (soundId != 0) {
                            soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/" + soundId);
                        }
                    } catch (Exception ignored) {
                    }

                    if (soundUri != null) {
                        final AudioAttributes audioAttributes = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build();
                        channel.setSound(soundUri, audioAttributes);
                    }
                }

                notificationManager.createNotificationChannel(channel);
            } catch (Exception ignored) {
            }
        }
    }

    private static String quietNotificationChannelId;
    public static String getQuietNotificationChannelId(Context context) {
        // Notification channel, required for running on API 26+
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (quietNotificationChannelId == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            try {
                final String channelId = "teak-no-sound-or-vibrate";
                final int importance = NotificationManager.IMPORTANCE_DEFAULT;
                final NotificationChannel channel = new NotificationChannel(channelId, "Silent Notifications", importance);
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

    private static Notification createNativeNotificationV1Plus(final Context context, final TeakNotification teakNotificaton) throws Exception {
        final Bundle bundle = teakNotificaton.bundle;
        // Get the memory class here, don't rely on TeakConfiguration
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final int deviceMemoryClass = am == null ? 0 : am.getMemoryClass();

        // For Android 11+ it will remove RemoteViews over a certain size, get that size
        final int config_notificationStripRemoteViewSizeBytes_Id = context.getResources().getIdentifier("config_notificationStripRemoteViewSizeBytes", "integer", "android");
        final int config_notificationStripRemoteViewSizeBytes = config_notificationStripRemoteViewSizeBytes_Id > 0 ? context.getResources().getInteger(config_notificationStripRemoteViewSizeBytes_Id) : 0;

        // Should this notification display both the small view and the big view when expanded?
        final boolean displayContentViewAboveBigContentView = teakNotificaton.display.optBoolean("displayContentViewAboveBigContentView", false);

        // Because we can't be certain that the R class will line up with what is at SDK build time
        // like in the case of Unity et. al.
        final ResourceHelper R = new ResourceHelper(context); // Declaring local as 'R' ensures we don't accidentally use the other R
        // Logic for the Android 12 notification style
        int targetSdkVersion = 0; // Do not use TeakConfiguration.get()
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            targetSdkVersion = appInfo.targetSdkVersion;
        } catch (Exception ignored) {
        }
        final boolean isRunningOn12Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        final boolean isTargeting12Plus = targetSdkVersion >= 31;
        final boolean willAutomaticallyUse12PlusStyle = isRunningOn12Plus && isTargeting12Plus;
        final boolean serverRequests12PlusStyle = teakNotificaton.useDecoratedCustomView;
        final boolean isAndroid12NotificationStyle = serverRequests12PlusStyle || willAutomaticallyUse12PlusStyle;
        final String notificationChannelId = NotificationBuilder.channelIdForOptOutId(context, teakNotificaton.teakOptOutCategory);
        final String groupKey = teakNotificaton.groupKey;

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, notificationChannelId);
        builder.setGroup(groupKey);

        // Assign DecoratedCustomViewStyle if the server requests the Android 12 style, and it would
        // not automatically assign that style
        if (serverRequests12PlusStyle && !willAutomaticallyUse12PlusStyle) {
            builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle());
        }

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

        builder.setContentTitle(teakNotificaton.title);
        // Assign content text otherwise a badge will not show up
        builder.setContentText(teakNotificaton.message);

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

        addDefaultIcons(context, R, builder);

        // Intent creation helper
        final PendingIntentHelper pendingIntent = new PendingIntentHelper(context, bundle);

        try {
            // Create intent to fire if/when notification is cleared
            builder.setDeleteIntent(pendingIntent.getDeleteIntent());
            builder.setContentIntent(pendingIntent.getLaunchIntent(null));
        } catch (Exception e) {
            if (!bundle.getBoolean("teakUnitTest")) {
                throw e;
            }
        }

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
                FutureTask<View> viewInflaterRunnable = new FutureTask<>(() -> factory.inflate(viewLayout, null));
                new Handler(Looper.getMainLooper()).post(viewInflaterRunnable);
                View inflatedView = viewInflaterRunnable.get();

                // Configure view
                JSONObject viewConfig = teakNotificaton.display.getJSONObject(name);
                Iterator<String> keys = viewConfig.keys();

                // View configuration does not include frames
                if (isAndroid12NotificationStyle) {
                    try {
                        final int viewElementId = R.id("left_image_frame");
                        remoteViews.setViewVisibility(viewElementId, View.GONE);
                    } catch (Exception ignored) {
                    }
                }

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
                        remoteViews.setOnClickPendingIntent(viewElementId, pendingIntent.getLaunchIntent(deepLink));
                    } else if (isUIType(viewElement, TextView.class)) {
                        final String value = viewConfig.getString(key);
                        remoteViews.setViewVisibility(viewElementId, View.VISIBLE);
                        remoteViews.setTextViewText(viewElementId, fromHtml(value));
                    } else //noinspection StatementWithEmptyBody
                        if (isUIType(viewElement, ImageButton.class)) {
                        // ImageButton must go before ImageView, because ImageButton is a ImageView
                    } else if (isUIType(viewElement, ImageView.class)) {
                        final String value = viewConfig.getString(key);
                        if (value.equalsIgnoreCase("BUILTIN_APP_ICON")) {
                            remoteViews.setImageViewResource(viewElementId, R.appIconResourceId);
                        } else if (value.equalsIgnoreCase("NONE")) {
                            remoteViews.setViewVisibility(viewElementId, View.GONE);
                        } else {
                            final Result<Bitmap> bitmapResult = loadBitmapWithOOMFallbacks(key, viewConfig);
                            if (bitmapResult.value == null) {
                                // If an asset failed to load, throw an AssetLoadException, unless it's
                                // the "left_image" in which case just ignore it.
                                if (!"left_image".equals(key)) {
                                    throw new AssetLoadException(value, bitmapResult.error);
                                }
                            } else {
                                remoteViews.setImageViewBitmap(viewElementId, bitmapResult.value);
                            }
                        }
                    } else if (isUIType(viewElement, ViewFlipper.class)) {
                        final Result<AnimationConfiguration> animationConfigResult = loadAnimationConfigWithOOMFallbacks(viewConfig);
                        if (animationConfigResult.value == null) {
                            final JSONObject jsonConfig = viewConfig.getJSONObject("view_animator");
                            throw new AssetLoadException(jsonConfig.getString("sprite_sheet"), animationConfigResult.error);
                        } else if (animationConfigResult.value.spriteSheet == null) {
                            throw new AssetLoadException(animationConfigResult.value.spriteSheetUrl, animationConfigResult.error);
                        }
                        final Bitmap bitmap = animationConfigResult.value.spriteSheet;
                        final int frameWidth = animationConfigResult.value.width;
                        final int frameHeight = animationConfigResult.value.height;
                        final int msPerFrame = animationConfigResult.value.displayMs;

                        final int numCols = bitmap.getWidth() / frameWidth;
                        final int numRows = bitmap.getHeight() / frameHeight;

                        for (int x = 0; x < numCols; x++) {
                            for (int y = 0; y < numRows; y++) {
                                final int startX = x * frameWidth;
                                final int startY = y * frameHeight;
                                Bitmap frame = Bitmap.createBitmap(bitmap, startX, startY, frameWidth, frameHeight);

                                if (frame == null) {
                                    throw new IllegalArgumentException("Frame [" + x + ", " + y + "] is null (" + animationConfigResult.value.spriteSheetUrl + ")");
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

            private Result<AnimationConfiguration> loadAnimationConfigWithOOMFallbacks(JSONObject viewConfig) throws OutOfMemoryError {
                final AnimationConfiguration ret = new AnimationConfiguration();
                final JSONObject initialAnimationConfig = viewConfig.getJSONObject("view_animator");
                try {
                    ret.spriteSheetUrl = initialAnimationConfig.getString("sprite_sheet");
                    final Result<Bitmap> loadBitmapResult = loadBitmapFromUriString(ret.spriteSheetUrl);
                    ret.spriteSheet = loadBitmapResult.value;
                    ret.width = initialAnimationConfig.getInt("width");
                    ret.height = initialAnimationConfig.getInt("height");
                    ret.displayMs = initialAnimationConfig.optInt("display_ms", 500);
                    return new Result<>(ret, loadBitmapResult.error);
                } catch (OutOfMemoryError e) {
                    Teak.log.e("oom.animation.initial", initialAnimationConfig.getString("sprite_sheet"));

                    final JSONArray oomFallbacks = viewConfig.optJSONArray("oom_view_animator");
                    if (oomFallbacks != null) {
                        for (int i = 0; i < oomFallbacks.length(); i++) {
                            final JSONObject fallbackAnimationConfig = oomFallbacks.getJSONObject(i);
                            try {
                                ret.spriteSheetUrl = fallbackAnimationConfig.getString("sprite_sheet");
                                final Result<Bitmap> loadBitmapResult = loadBitmapFromUriString(ret.spriteSheetUrl);
                                ret.spriteSheet = loadBitmapResult.value;
                                ret.width = fallbackAnimationConfig.getInt("width");
                                ret.height = fallbackAnimationConfig.getInt("height");
                                ret.displayMs = fallbackAnimationConfig.optInt("display_ms", 500);
                                Teak.log.i("oom.animation.fallback", ret.spriteSheetUrl);
                                return new Result<>(ret, loadBitmapResult.error);
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
                return new Result<>(null);
            }

            private Result<Bitmap> loadBitmapWithOOMFallbacks(String key, JSONObject viewConfig) throws OutOfMemoryError {
                try {
                    return loadBitmapFromUriString(viewConfig.getString(key));
                } catch (OutOfMemoryError e) {
                    Teak.log.e("oom.image.initial", viewConfig.getString(key));

                    final String oom_key = "oom_" + key;
                    final JSONArray oomFallbacks = viewConfig.optJSONArray(oom_key);
                    if (oomFallbacks != null) {
                        for (int i = 0; i < oomFallbacks.length(); i++) {
                            try {
                                final Result<Bitmap> loadBitmapResult = loadBitmapFromUriString(oomFallbacks.getString(i));
                                Teak.log.i("oom.image.fallback", oomFallbacks.getString(i));
                                return loadBitmapResult;
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
                return new Result<>(null);
            }

            private Result<Bitmap> loadBitmapFromUriString(String bitmapUriString) throws OutOfMemoryError {
                final Uri bitmapUri = Uri.parse(bitmapUriString);
                Bitmap ret = null;
                InputStream inputStream = null;
                Exception exception = null;
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
                } catch (Exception e) {
                    exception = e;
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                return new Result<>(ret, exception);
            }

            private boolean isUIType(View viewElement, Class<?> clazz) {
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
        final ViewBuilder viewBuilder = new ViewBuilder();

        // Build small content view
        final String contentView = teakNotificaton.display.getString("contentView");
        final RemoteViews smallContentView = viewBuilder.buildViews(contentView);

        // Assign content view
        builder.setCustomContentView(smallContentView);

        // Build big content view
        RemoteViews bigContentView = null;
        if (teakNotificaton.display.has("bigContentView")) {
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
                // Big content view failures (CDN, RemoteViews quirks) are outside our control — suppress in
                // production. Report in debug builds so our own bugs remain visible during QA.
                Teak.log.exception(e, extras, isHostAppDebug());
            }
        }

        // Assign expanded view if it's there
        if (bigContentView != null) {
            builder.setCustomBigContentView(bigContentView);
        } else if (isAndroid12NotificationStyle) {
            // All notifications are expandable for apps targeting Android 12.
            // This will ensure the view is the same if it gets expanded.
            builder.setCustomBigContentView(smallContentView);
        }

        // Notification builder
        Notification nativeNotification;
        try {
            nativeNotification = builder.build();
        } catch (Exception e) {
            if (!bundle.getBoolean("teakUnitTest")) {
                throw e;
            } else {
                final Notification notification = new Notification();
                notification.flags = Integer.MAX_VALUE;
                return notification;
            }
        }

        return nativeNotification;
    }
}
