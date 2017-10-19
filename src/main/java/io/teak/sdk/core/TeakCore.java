/* Teak -- Copyright (C) 2017 GoCarrot Inc.
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

package io.teak.sdk.core;

/*
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
*/
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TeakCore {
    public TeakCore() {

    }
/*
    @Override
    public void lifecycle_onActivityResumed() {

        // Get or create an empty Intent
        Intent intent = activity.getIntent();
        if (intent == null) {
            intent = new Intent();
        }

        // Prevent back-stack loops
        if (intent.getBooleanExtra("processedByTeak", false)) {
            return;
        } else {
            intent.putExtra("processedByTeak", true);
        }

        // Check to see if this was a push notification launch
        checkIntentForPushLaunchAndSendBroadcasts(intent);
    }

    @Override
    public void notification_onNotificationReceived(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        TeakNotification notif = null;
        boolean showInForeground = Helpers.getBooleanFromBundle(bundle, "teakShowInForeground");
        if (showInForeground || Session.isExpiringOrExpired()) {
            notif = TeakNotification.remoteNotificationFromIntent(context, intent);
            if (notif == null) {
                return;
            }
        }
        final long teakNotifId = notif == null ? 0 : notif.teakNotifId;
        final String teakUserId = bundle.getString("teakUserId", null);

        if (teakUserId == null) {
            return;
        }

        HashMap<String, Object> debugHash = new HashMap<>();
        Set<String> keys = bundle.keySet();
        for (String key : keys) {
            Object o = bundle.get(key);
            if (o instanceof String) {
                try {
                    JSONObject jsonObject = new JSONObject(o.toString());
                    o = Helpers.jsonToMap(jsonObject);
                } catch (Exception ignored) {
                }
            }
            debugHash.put(key, o);
        }
        Teak.log.i("notification.received", debugHash);

        // Send Notification Received Metric
        Session session = Session.getCurrentSessionOrNull();
        if (session != null) {
            HashMap<String, Object> payload = new HashMap<>();
            payload.put("app_id", this.appConfiguration.appId);
            payload.put("user_id", teakUserId);
            payload.put("platform_id", teakNotifId);

            if (teakNotifId == 0) {
                payload.put("impression", false);
            }

            new Thread(new Request("parsnip.gocarrot.com", "/notification_received", payload, session)).start();
        }
    }

    @Override
    public void notification_onNotificationAction(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();

        // Cancel any updates pending
        TeakNotification.cancel(context, bundle.getInt("platformId"));

        // Launch the app
        boolean autoLaunch = !Helpers.getBooleanFromBundle(bundle, "noAutolaunch");
        Teak.log.i("notification.opened", Helpers.mm.h("teakNotifId", bundle.getString("teakNotifId"), "autoLaunch", autoLaunch));

        if (autoLaunch) {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchIntent.putExtras(bundle);
            if (bundle.getString("teakDeepLink") != null) {
                Uri teakDeepLink = Uri.parse(bundle.getString("teakDeepLink"));
                launchIntent.setData(teakDeepLink);
            }
            context.startActivity(launchIntent);
        }
    }

    @Override
    public void notification_onNotificationCleared(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        TeakNotification.cancel(context, bundle.getInt("platformId"));
    }

    @Override
    public void purchase_onPurchaseSucceeded(final Map<String, Object> payload) {
        Session.whenUserIdIsReadyRun(new Session.SessionRunnable() {
            @Override
            public void run(Session session) {
                new Request("/me/purchase", payload, session).run();
            }
        });
    }

    @Override
    public void purchase_onPurchaseFailed(final Map<String, Object> payload) {
        Teak.log.i("puchase.failed", payload);

        Session.whenUserIdIsReadyRun(new Session.SessionRunnable() {
            @Override
            public void run(Session session) {
                new Request("/me/purchase", payload, session).run();
            }
        });
    }

    private void checkIntentForPushLaunchAndSendBroadcasts(Intent intent) {
        if (intent.hasExtra("teakNotifId")) {
            Bundle bundle = intent.getExtras();

            // Send broadcast
            if (this.localBroadcastManager != null) {
                final HashMap<String, Object> eventDataDict = new HashMap<String, Object>();
                if (bundle.getString("teakRewardId") != null) {
                    eventDataDict.put("incentivized", true);
                    eventDataDict.put("teakRewardId", bundle.getString("teakRewardId"));
                } else {
                    eventDataDict.put("incentivized", false);
                }
                if (bundle.getString("teakScheduleName") != null) eventDataDict.put("teakScheduleName", bundle.getString("teakScheduleName"));
                if (bundle.getString("teakCreativeName") != null) eventDataDict.put("teakCreativeName", bundle.getString("teakCreativeName"));

                final Intent broadcastEvent = new Intent(Teak.LAUNCHED_FROM_NOTIFICATION_INTENT);
                broadcastEvent.putExtras(bundle);
                broadcastEvent.putExtra("eventData", eventDataDict);
                this.localBroadcastManager.sendBroadcast(broadcastEvent);

                String teakRewardId = bundle.getString("teakRewardId");
                if (teakRewardId != null) {
                    final Future<TeakNotification.Reward> rewardFuture = TeakNotification.Reward.rewardFromRewardId(teakRewardId);
                    if (rewardFuture != null) {
                        this.asyncExecutor.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    TeakNotification.Reward reward = rewardFuture.get();
                                    HashMap<String, Object> rewardMap = Helpers.jsonToMap(reward.json);
                                    rewardMap.putAll(eventDataDict);

                                    // Broadcast reward only if everything goes well
                                    final Intent rewardIntent = new Intent(Teak.REWARD_CLAIM_ATTEMPT);
                                    rewardIntent.putExtra("reward", rewardMap);
                                    localBroadcastManager.sendBroadcast(rewardIntent);
                                } catch (Exception e) {
                                    Teak.log.exception(e);
                                }
                            }
                        });
                    }
                }
            }
        }
    }

    ///// Data Members
    private ExecutorService asyncExecutor = Executors.newCachedThreadPool();*/
}