package io.teak.sdk.io;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;
import java.util.Objects;
import java.util.Arrays;

import androidx.annotation.NonNull;
import androidx.work.Data;
import io.teak.sdk.Helpers;
import io.teak.sdk.NotificationBuilder;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.core.DeviceScreenState;
import io.teak.sdk.event.NotificationDisplayEvent;
import io.teak.sdk.event.NotificationReDisplayEvent;
import io.teak.sdk.event.PushNotificationEvent;

import androidx.core.app.NotificationCompat;
import android.service.notification.StatusBarNotification;

public class DefaultAndroidNotification implements IAndroidNotification {
    private final NotificationManager notificationManager;
    private final ArrayList<AnimationEntry> animatedNotifications = new ArrayList<>();
    private final Handler handler;
    private final DeviceScreenState deviceScreenState;

    private static class AnimationEntry {
        final Notification notification;
        final Bundle bundle;

        AnimationEntry(Notification notification, TeakNotification teakNotification) {
            this.notification = notification;
            this.bundle = teakNotification.bundle;
            this.bundle.putInt("platformId", teakNotification.platformId);
        }
    }

    private static final String NOTIFICATION_TAG = "io.teak.sdk.TeakNotification";

    private static final Object InstanceMutex = new Object();
    private static DefaultAndroidNotification Instance = null;
    public static DefaultAndroidNotification get(@NonNull Context context) {
        synchronized (InstanceMutex) {
            if (Instance == null) {
                Instance = new DefaultAndroidNotification(context);
            }
            return Instance;
        }
    }

    public DefaultAndroidNotification(@NonNull final Context context) {
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.deviceScreenState = new DeviceScreenState(context);

        if ("test_package_name".equalsIgnoreCase(context.getPackageName())) {
            this.handler = null;
        } else {
            EventBus.getDefault().register(this);
            this.handler = new Handler(Looper.getMainLooper());
        }

        TeakEvent.addEventListener(event -> {
            switch (event.eventType) {
                case PushNotificationEvent.Cleared:
                case PushNotificationEvent.Interaction: {
                    final Intent intent = ((PushNotificationEvent) event).intent;
                    if (intent != null && intent.getExtras() != null) {
                        final Bundle bundle = intent.getExtras();
                        cancelNotification(bundle.getInt("platformId"), context, bundle.getString("teakGroupKey", "teak"));
                    }
                } break;
                case NotificationDisplayEvent.Type: {
                    NotificationDisplayEvent notificationDisplayEvent = (NotificationDisplayEvent) event;
                    displayNotification(context, notificationDisplayEvent.teakNotification, notificationDisplayEvent.nativeNotification);
                } break;
            }
        });
    }

    private void scheduleScreenStateWork() {
        try {
            if (this.animatedNotifications.size() > 0) {
                final Data data = new Data.Builder()
                                      .putInt(IAndroidNotification.ANIMATED_NOTIFICATION_COUNT_KEY, this.animatedNotifications.size())
                                      .build();
                this.deviceScreenState.scheduleScreenStateWork(data);
            }
        } catch (Exception e) {
            Teak.log.exception(e, false);
        }
    }

    private class NotificationGroup {
        final public StatusBarNotification[] children;
        final public StatusBarNotification summary;

        NotificationGroup(StatusBarNotification[] children, StatusBarNotification summary) {
            this.children = children;
            this.summary = summary;
        }
    }

    // Only supports Android 7+, which means notification grouping only works on Android 7+
    // Based on our latest data, less than 0.5% of sessions are on Android < 7
    //
    // The underlying method call (getActiveNotifications()) is available on Android 6, but
    // in the presence of a summary notification it only returns the summary. This breaks a
    // bunch of downstream logic.
    private NotificationGroup getActiveNotificationsForGroup(String groupKey) {
        ArrayList<StatusBarNotification> children = new ArrayList<StatusBarNotification>();
        StatusBarNotification summary = null;

        // This behavior is largely aped from NotificationManagerCompat.
        // NotificationManagerCompat didn't add this method until sometime after 1.11.x, and
        // I'd rather not force an update. This is simple enough.
        //
        // I'm restricting this to Android 7+ for the reason given above -- Android 6 will only
        // return the summary notification, which breaks our downstream logic.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
            if(notifications != null) {
                for(StatusBarNotification sbn : notifications) {
                    final Notification notification = sbn.getNotification();
                    if(Objects.equals(groupKey, NotificationCompat.getGroup(notification))) {
                        if(NotificationCompat.isGroupSummary(notification)) {
                            summary = sbn;
                        } else {
                            children.add(sbn);
                        }
                    }
                }
            }
        }

        StatusBarNotification[] childrenArr = new StatusBarNotification[children.size()];
        childrenArr = children.toArray(childrenArr);
        Arrays.sort(childrenArr, (a, b) -> {
            long diff = b.getNotification().when - a.getNotification().when;
            // Bit of absurdity to deal with converting long to int.
            if(diff < 0) {
                return -1;
            } else if (diff == 0) {
                return 0;
            } else {
                return 1;
            }
        });

        return new NotificationGroup(childrenArr, summary);
    }

    @Override
    public void cancelNotification(int platformId, final @NonNull Context context, final String groupKey) {
        Teak.log.i("notification.cancel", Helpers.mm.h("platformId", platformId));

        this.notificationManager.cancel(NOTIFICATION_TAG, platformId);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && groupKey != null) {
            final NotificationGroup groupInfo = DefaultAndroidNotification.this.getActiveNotificationsForGroup(groupKey);

            final StatusBarNotification groupSummary = groupInfo.summary;
            final ArrayList<Notification> ourNotifications = new ArrayList<Notification>();
            // I thought that we were having an issue where cancelling notifications wasn't synchronous. As it turns
            // out, that was not the issue, but at this point I don't trust Android so I'm going to leave this
            // check in, in case there are devices where getting active notifications immediately after cancelling
            // one still returns the cancelled one.
            for(StatusBarNotification sbn : groupInfo.children) {
                if(sbn.getId() != platformId) {
                    ourNotifications.add(sbn.getNotification());
                }
            }

            Teak.log.i(
                "default_android_notification.cancel_notification.summary_info",
                Helpers.mm.h("liveCount", ourNotifications.size(), "hasSummary", groupSummary != null)
            );

            if(groupSummary != null && ourNotifications.size() > 0) {
                this.handler.post(() -> {
                    try {
                        DefaultAndroidNotification.this.notificationManager.notify(
                            NOTIFICATION_TAG,
                            groupSummary.getId(),
                            NotificationBuilder.createSummaryNotification(context, groupKey, ourNotifications)
                        );
                    } catch (SecurityException ignored) {
                        // This likely means that they need the VIBRATE permission on old versions of Android
                        Teak.log.e("notification.permission_needed.vibrate", "Please add this to your AndroidManifest.xml: <uses-permission android:name=\"android.permission.VIBRATE\" />");
                    } catch (OutOfMemoryError e) {
                        Teak.log.exception(e);
                    } catch (Exception e) {
                        Teak.log.exception(e);
                        throw e;
                    }

                });
            } else if(groupSummary != null && ourNotifications.size() == 0) {
                // This came up in the Android 7.1 test -- if the group summary was not explicitly cancelled then
                // it would show up as a notification using the inbox style and already issued intents so it
                // couldn't launch the game.
                this.notificationManager.cancel(NOTIFICATION_TAG, groupSummary.getId());
            }
        }

        synchronized (this.animatedNotifications) {
            ArrayList<AnimationEntry> removeList = new ArrayList<>();
            for (int i = 0; i < this.animatedNotifications.size(); i++) {
                if (this.animatedNotifications.get(i).bundle.getInt("platformId") == platformId) {
                    removeList.add(this.animatedNotifications.get(i));
                }
            }
            this.animatedNotifications.removeAll(removeList);
            this.scheduleScreenStateWork();
        }
    }

    @Override
    public void displayNotification(@NonNull final Context context, @NonNull final TeakNotification teakNotification, @NonNull final Notification nativeNotification) {
        // Send it out
        final int platformId = teakNotification.platformId;
        Teak.log.i("notification.display", Helpers.mm.h("teakNotifId", teakNotification.teakNotifId, "platformId", platformId));

        // This should only be the case during unit tests, but catch it here anyway
        if (this.handler == null) {
            Teak.log.e("notification.display.error", "this.handler is null, skipping display");
            return;
        }

        this.handler.post(() -> {
            // Run the GC
            Helpers.runAndLogGC("display_notification.gc");

            try {
                DefaultAndroidNotification.this.notificationManager.notify(NOTIFICATION_TAG, platformId, nativeNotification);

                if (teakNotification.isAnimated) {
                    synchronized (DefaultAndroidNotification.this.animatedNotifications) {
                        DefaultAndroidNotification.this.animatedNotifications.add(new AnimationEntry(nativeNotification, teakNotification));
                        DefaultAndroidNotification.this.scheduleScreenStateWork();
                    }
                }

                final String groupKey = NotificationCompat.getGroup(nativeNotification);

                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && groupKey != null) {
                    final NotificationGroup groupInfo = DefaultAndroidNotification.this.getActiveNotificationsForGroup(groupKey);

                    final StatusBarNotification groupSummary = groupInfo.summary;
                    final StatusBarNotification[] extantNotifications = groupInfo.children;
                    final ArrayList<Notification> ourNotifications = new ArrayList<Notification>();
                    boolean listNeedsUs = true;
                    for(StatusBarNotification n : extantNotifications) {
                        if(n.getId() == platformId) {
                            listNeedsUs = false;
                        }
                        ourNotifications.add(n.getNotification());
                    }
                    if(listNeedsUs) {
                        ourNotifications.add(0, nativeNotification);
                    }

                    final int notificationCount = ourNotifications.size();

                    Teak.log.i(
                        "default_android_notification.display_notification.summary_info",
                        Helpers.mm.h("liveCount", notificationCount, "hasSummary", groupSummary != null)
                    );

                    if(notificationCount >= teakNotification.minGroupSize) {
                        int summaryId = teakNotification.groupSummaryId;
                        if(groupSummary != null) {
                            summaryId = groupSummary.getId();
                        }

                        DefaultAndroidNotification.this.notificationManager.notify(
                            NOTIFICATION_TAG,
                            summaryId,
                            NotificationBuilder.createSummaryNotification(context, groupKey, ourNotifications)
                        );
                    }
                }

            } catch (SecurityException ignored) {
                // This likely means that they need the VIBRATE permission on old versions of Android
                Teak.log.e("notification.permission_needed.vibrate", "Please add this to your AndroidManifest.xml: <uses-permission android:name=\"android.permission.VIBRATE\" />");
            } catch (OutOfMemoryError e) {
                Teak.log.exception(e);
            } catch (Exception e) {
                // Unit testing case
                if (nativeNotification.flags != Integer.MAX_VALUE) {
                    throw e;
                }
            }
        });
    }

    @Subscribe
    public void onScreenState(final DeviceScreenState.ScreenStateChangeEvent event) {
        if (event.newState == DeviceScreenState.State.ScreenOn) return;

        // This should only be the case during unit tests, but catch it here anyway
        if (this.handler == null) {
            Teak.log.e("notification.animation.error", "this.handler is null, skipping animation refresh");
            return;
        }

        // Double, double, toil and trouble...
        String tempNotificationChannelId = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tempNotificationChannelId = NotificationBuilder.getQuietNotificationChannelId(event.context);
        }
        final String notificationChannelId = tempNotificationChannelId;

        this.handler.postDelayed(() -> {
            synchronized (DefaultAndroidNotification.this.animatedNotifications) {
                Random rng = new Random();
                for (final AnimationEntry entry : DefaultAndroidNotification.this.animatedNotifications) {
                    try {
                        DefaultAndroidNotification.this.notificationManager.cancel(NOTIFICATION_TAG, entry.bundle.getInt("platformId"));

                        class Deprecated {
                            @SuppressWarnings("deprecation")
                            private void assignDeprecated() {
                                entry.notification.defaults = 0; // Disable sound/vibrate etc
                                entry.notification.vibrate = new long[] {0L};
                                entry.notification.sound = null;
                            }
                        }
                        final Deprecated deprecated = new Deprecated();
                        deprecated.assignDeprecated();

                        entry.bundle.putInt("platformId", rng.nextInt());

                        // Fire burn, and cauldron bubble...
                        if (notificationChannelId != null) {
                            try {
                                final Field mChannelIdField = Notification.class.getDeclaredField("mChannelId");
                                mChannelIdField.setAccessible(true);
                                mChannelIdField.set(entry.notification, notificationChannelId);
                            } catch (Exception ignored) {
                            }
                        }

                        // Now it needs new intents
                        ComponentName cn = new ComponentName(event.context.getPackageName(), "io.teak.sdk.Teak");

                        // Create intent to fire if/when notification is cleared
                        Intent pushClearedIntent = new Intent(event.context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX);
                        pushClearedIntent.putExtras(entry.bundle);
                        pushClearedIntent.setComponent(cn);
                        entry.notification.deleteIntent = PendingIntent.getBroadcast(event.context, rng.nextInt(), pushClearedIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

                        // Create intent to fire if/when notification is opened, attach bundle info
                        Intent pushOpenedIntent = new Intent(event.context.getPackageName() + TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX);
                        pushOpenedIntent.putExtras(entry.bundle);
                        pushOpenedIntent.setComponent(cn);
                        entry.notification.contentIntent = PendingIntent.getBroadcast(event.context, rng.nextInt(), pushOpenedIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

                        DefaultAndroidNotification.this.notificationManager.notify(NOTIFICATION_TAG, entry.bundle.getInt("platformId"), entry.notification);
                        TeakEvent.postEvent(new NotificationReDisplayEvent(entry.bundle, entry.notification));
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                }
            }

            // Re-issue the work, this will overwrite the current worker, and reset the retry-backoff
            DefaultAndroidNotification.this.scheduleScreenStateWork();
        }, 1000);
    }
}
