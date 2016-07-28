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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Random;

class NotificationBuilder {
    private static final String LOG_TAG = "Teak:NotifBuilder:<21";

    public static Notification createNativeNotification(final Context context, Bundle bundle, TeakNotification teakNotificaton) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context);

        // Rich text message
        Spanned richMessageText = Html.fromHtml(teakNotificaton.message);

        // Configure notification behavior
        builder.setPriority(NotificationCompat.PRIORITY_MAX);
        builder.setDefaults(NotificationCompat.DEFAULT_ALL);
        builder.setOnlyAlertOnce(true);
        builder.setAutoCancel(true);
        builder.setTicker(richMessageText);

        // Set small view image
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), 0);
            builder.setSmallIcon(ai.icon);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Unable to load icon resource for Notification.");
            return null;
        }

        Random rng = new Random();

        // Create intent to fire if/when notification is cleared
        Intent pushClearedIntent = new Intent(context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX);
        pushClearedIntent.putExtras(bundle);
        PendingIntent pushClearedPendingIntent = PendingIntent.getBroadcast(context, rng.nextInt(), pushClearedIntent, PendingIntent.FLAG_ONE_SHOT);
        builder.setDeleteIntent(pushClearedPendingIntent);

        // Create intent to fire if/when notification is opened, attach bundle info
        Intent pushOpenedIntent = new Intent(context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX);
        pushOpenedIntent.putExtras(bundle);
        PendingIntent pushOpenedPendingIntent = PendingIntent.getBroadcast(context, rng.nextInt(), pushOpenedIntent, PendingIntent.FLAG_ONE_SHOT);
        builder.setContentIntent(pushOpenedPendingIntent);

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
        }
        IdHelper R = new IdHelper(); // Declaring local as 'R' ensures we don't accidentally use the other R

        // Configure notification small view
        RemoteViews smallView = new RemoteViews(
                context.getPackageName(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        R.layout("teak_notif_no_title_v21") :
                        R.layout("teak_notif_no_title")
        );

        // Set small view image
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), 0);
            smallView.setImageViewResource(R.id("left_image"), ai.icon);
            builder.setSmallIcon(ai.icon);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Unable to load icon resource for Notification.");
            return null;
        }

        // Set small view text
        smallView.setTextViewText(R.id("text"), richMessageText);

        // Check for Jellybean (API 16, 4.1)+ for expanded view
        RemoteViews bigView = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN &&
                teakNotificaton.longText != null &&
                !teakNotificaton.longText.isEmpty()) {
            bigView = new RemoteViews(context.getPackageName(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                            R.layout("teak_big_notif_image_text_v21") :
                            R.layout("teak_big_notif_image_text")
            );

            // Set big view text
            bigView.setTextViewText(R.id("text"), Html.fromHtml(teakNotificaton.longText));

            URI imageAssetA = null;
            try {
                imageAssetA = new URI(teakNotificaton.imageAssetA);
            } catch (Exception ignored) {
            }

            Bitmap topImageBitmap = null;
            if (imageAssetA != null) {
                try {
                    URL aURL = new URL(imageAssetA.toString());
                    URLConnection conn = aURL.openConnection();
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    BufferedInputStream bis = new BufferedInputStream(is);
                    topImageBitmap = BitmapFactory.decodeStream(bis);
                    bis.close();
                    is.close();
                } catch (Exception ignored) {
                }
            }

            if (topImageBitmap == null) {
                try {
                    InputStream istr = context.getAssets().open("teak_notif_large_image_default.png");
                    topImageBitmap = BitmapFactory.decodeStream(istr);
                } catch (Exception ignored) {
                }
            }

            if (topImageBitmap != null) {
                // Set large bitmap
                bigView.setImageViewBitmap(R.id("top_image"), topImageBitmap);
            } else {
                Log.e(LOG_TAG, "Unable to load image asset for Notification.");
                // Hide pulldown
                smallView.setViewVisibility(R.id("pulldown_layout"), View.INVISIBLE);
            }
        } else {
            // Hide pulldown
            smallView.setViewVisibility(R.id("pulldown_layout"), View.INVISIBLE);
        }

        // Voodoo from http://stackoverflow.com/questions/28169474/notification-background-in-android-lollipop-is-white-can-we-change-it
        int topId = Resources.getSystem().getIdentifier("status_bar_latest_event_content", "id", "android");
        int topBigLayout = Resources.getSystem().getIdentifier("notification_template_material_big_media_narrow", "layout", "android");
        int topSmallLayout = Resources.getSystem().getIdentifier("notification_template_material_media", "layout", "android");

        RemoteViews topBigView = null;
        if (bigView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // This is invisible inner view - to have media_actions in hierarchy
            RemoteViews innerTopView = new RemoteViews("android", topBigLayout);
            bigView.addView(android.R.id.empty, innerTopView);

            // This should be on top - we need status_bar_latest_event_content as top layout
            topBigView = new RemoteViews("android", topBigLayout);
            topBigView.removeAllViews(topId);
            topBigView.addView(topId, bigView);
        } else if (bigView != null) {
            topBigView = bigView;
        }

        // This should be on top - we need status_bar_latest_event_content as top layout
        RemoteViews topSmallView;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            topSmallView = new RemoteViews("android", topSmallLayout);
            topSmallView.removeAllViews(topId);
            topSmallView.addView(topId, smallView);
        } else {
            topSmallView = smallView;
        }

        builder.setContent(topSmallView);

        Notification n = builder.build();
        if (topBigView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // Use reflection to avoid compile-time issues, we check minimum API version above
            try {
                Field bigContentViewField = n.getClass().getField("bigContentView");
                bigContentViewField.set(n, topBigView);
            } catch (Exception ignored) {
            }
        }

        return n;
    }
}