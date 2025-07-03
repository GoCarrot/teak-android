package io.teak.sdk;

import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import io.teak.sdk.Helpers.mm;
import io.teak.sdk.core.Session;
import io.teak.sdk.core.ThreadFactory;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONException;
import io.teak.sdk.json.JSONObject;

/**
 * An app-to-user notification received from Teak via GCM.
 *
 * The following parameters from the GCM payload are used to create a <code>TeakNotification</code>.
 *
 * {@code
 * {
 *   [teakRewardId] : string  - associated Teak Reward Id,
 *   [deepLink]     : string  - a deep link to navigate to on launch,
 *   teakNotifId    : string  - associated Teak Notification Id,
 *   message        : string  - the body text of the notification,
 *   longText       : string  - text displayed when the notification is expanded,
 *   imageAssetA    : string  - URI of an image asset to use for a banner image,
 *   [extras]       : string  - JSON encoded extra data
 *   [useDecoratedCustomView] : boolean - Use the Android 12 notification style on non-Android 12 devices
 * }
 * }
 */
public class TeakNotification implements Unobfuscable {
    /**
     * The {@link Intent} action sent by Teak when a notification has been opened by the user.
     *
     * This allows you to take special actions, it is not required that you listen for it.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX = ".intent.TEAK_NOTIFICATION_OPENED";

    /**
     * The {@link Intent} action sent by Teak when a notification has been cleared by the user.
     *
     * This allows you to take special actions, it is not required that you listen for it.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX = ".intent.TEAK_NOTIFICATION_CLEARED";

    /**
     * Get optional extra data associated with this notification.
     *
     * @return {@link JSONObject} containing extra data sent by the server.
     */
    @SuppressWarnings("unused")
    public JSONObject getExtras() {
        return this.extras;
    }

    @SuppressWarnings("WeakerAccess")
    public static class Reward implements Unobfuscable {

        /**
         * An unknown error occured while processing the reward.
         */
        public static final int UNKNOWN = 1;

        /**
         * Valid reward claim, grant the user the reward.
         */
        public static final int GRANT_REWARD = 0;

        /**
         * The user has attempted to claim a reward from their own social post.
         */
        public static final int SELF_CLICK = -1;

        /**
         * The user has already been issued this reward.
         */
        public static final int ALREADY_CLICKED = -2;

        /**
         * The reward has already been claimed its maximum number of times globally.
         */
        public static final int TOO_MANY_CLICKS = -3;

        /**
         * The user has already claimed their maximum number of rewards of this type for the day.
         */
        public static final int EXCEED_MAX_CLICKS_FOR_DAY = -4;

        /**
         * This reward has expired and is no longer valid.
         */
        public static final int EXPIRED = -5;

        /**
         * Teak does not recognize this reward id.
         */
        public static final int INVALID_POST = -6;

        /**
         * Status of this reward.
         *
         * One of the following status codes:
         * {@link Reward#UNKNOWN}
         * {@link Reward#GRANT_REWARD}
         * {@link Reward#SELF_CLICK}
         * {@link Reward#ALREADY_CLICKED}
         * {@link Reward#TOO_MANY_CLICKS}
         * {@link Reward#EXCEED_MAX_CLICKS_FOR_DAY}
         * {@link Reward#EXPIRED}
         * {@link Reward#INVALID_POST}
         *
         * If status is {@link Reward#GRANT_REWARD}, the 'reward' field will contain the reward that should be granted.
         */
        public int status;

        public JSONObject json;

        Reward(JSONObject json) {
            String statusString = "";
            // Try/catch is unneeded practically, but needed to compile
            try {
                statusString = json.isNull("status") ? "" : json.getString("status");
            } catch (Exception ignored) {
            }
            this.json = json;

            if (GRANT_REWARD_STRING.equals(statusString)) {
                status = GRANT_REWARD;
            } else if (SELF_CLICK_STRING.equals(statusString)) {
                status = SELF_CLICK;
            } else if (ALREADY_CLICKED_STRING.equals(statusString)) {
                status = ALREADY_CLICKED;
            } else if (TOO_MANY_CLICKS_STRING.equals(statusString)) {
                status = TOO_MANY_CLICKS;
            } else if (EXCEED_MAX_CLICKS_FOR_DAY_STRING.equals(statusString)) {
                status = EXCEED_MAX_CLICKS_FOR_DAY;
            } else if (EXPIRED_STRING.equals(statusString)) {
                status = EXPIRED;
            } else if (INVALID_POST_STRING.equals(statusString)) {
                status = INVALID_POST;
            } else {
                status = UNKNOWN;
            }
        }

        @Override
        @NonNull
        public String toString() {
            return String.format(Locale.US, "%s{status: %d, json: %s}", super.toString(), status, json == null ? "null" : json.toString());
        }

        private static final String GRANT_REWARD_STRING = "grant_reward";
        private static final String SELF_CLICK_STRING = "self_click";
        private static final String ALREADY_CLICKED_STRING = "already_clicked";
        private static final String TOO_MANY_CLICKS_STRING = "too_many_clicks";
        private static final String EXCEED_MAX_CLICKS_FOR_DAY_STRING = "exceed_max_clicks_for_day";
        private static final String EXPIRED_STRING = "expired";
        private static final String INVALID_POST_STRING = "invalid_post";

        /**
         * @return A {@link Future} which will contain the reward that should be granted, or <code>null</code> if there is no associated reward.
         */
        @SuppressWarnings("unused")
        public static Future<Reward> rewardFromRewardId(final String teakRewardId) {
            Teak.log.trace("TeakNotification.Reward.rewardFromRewardId", "teakRewardId", teakRewardId);

            if (Teak.Instance == null || !Teak.Instance.isEnabled()) {
                Teak.log.e("reward", "Teak is disabled, ignoring rewardFromRewardId().");
                return null;
            }

            if (teakRewardId == null || teakRewardId.isEmpty()) {
                Teak.log.e("reward", "teakRewardId cannot be null or empty");
                return null;
            }

            final ArrayBlockingQueue<Reward> q = new ArrayBlockingQueue<>(1);
            final FutureTask<Reward> ret = new FutureTask<>(() -> {
                try {
                    return q.take();
                } catch (InterruptedException e) {
                    Teak.log.exception(e);
                }
                return null;
            });
            ThreadFactory.autoStart(ret);

            Session.whenUserIdIsReadyRun(session -> {
                Teak.log.i("reward.claim.request", mm.h("teakRewardId", teakRewardId));

                try {
                    // https://rewards.gocarrot.com/<<teak_reward_id>>/clicks?clicking_user_id=<<your_user_id>>
                    //String requestBody = "clicking_user_id=" + URLEncoder.encode(session.userId(), "UTF-8");
                    HashMap<String, Object> payload = new HashMap<>();
                    payload.put("clicking_user_id", session.userId());

                    Request.submit("rewards.gocarrot.com", "/" + teakRewardId + "/clicks", payload, session,
                        (responseCode, responseBody) -> {
                            try {
                                final JSONObject responseJson = new JSONObject(responseBody);

                                // https://sentry.io/organizations/teak/issues/1354507192/?project=141792&referrer=alert_email
                                if (responseBody == null) {
                                    q.offer(null);
                                    return;
                                }

                                final JSONObject rewardResponse = responseJson.optJSONObject("response");
                                if (rewardResponse == null) {
                                    q.offer(null);
                                    return;
                                }

                                if (rewardResponse.get("status") == null) {
                                    q.offer(null);
                                    return;
                                }

                                final JSONObject fullParsedResponse = new JSONObject();
                                fullParsedResponse.put("teakRewardId", teakRewardId);
                                fullParsedResponse.put("status", rewardResponse.get("status"));
                                if (rewardResponse.optJSONObject("reward") != null) {
                                    fullParsedResponse.put("reward", rewardResponse.get("reward"));
                                } else if (rewardResponse.opt("reward") != null) {
                                    fullParsedResponse.put("reward", new JSONObject(rewardResponse.getString("reward")));
                                }
                                final Reward reward = new Reward(fullParsedResponse);

                                Teak.log.i("reward.claim.response", responseJson.toMap());

                                q.offer(reward);
                            } catch (JSONException e) {
                                Teak.log.exception(e, false);
                                q.offer(null);
                            } catch (Exception e) {
                                Teak.log.exception(e);
                                q.offer(null); // TODO: Fix this?
                            }
                        });
                } catch (Exception e) {
                    Teak.log.exception(e);
                    q.offer(null); // TODO: Fix this?
                }
            });

            return ret;
        }
    }

    /**
     * Schedules a push notification for some time in the future.
     *
     * @deprecated Use {@link Teak.Notification#schedule(String, long, Map)} instead.
     *
     * @param creativeId     The identifier of the notification in the Teak dashboard (will create if not found).
     * @param defaultMessage The default message to send, may be over-ridden in the dashboard.
     * @param delayInSeconds The delay in seconds from now to send the notification.
     * @return The identifier of the scheduled notification (see {@link TeakNotification#cancelNotification(String)} or null.
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    @Deprecated
    public static FutureTask<String> scheduleNotification(final String creativeId, final String defaultMessage, final long delayInSeconds) {
        Teak.log.trace("TeakNotification.scheduleNotification", "creativeId", creativeId, "defaultMessage", defaultMessage, "delayInSeconds", delayInSeconds);

        if (Teak.Instance == null || !Teak.Instance.isEnabled()) {
            Teak.log.e("notification.schedule.disabled", "Teak is disabled, ignoring scheduleNotification().");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.teak.disabled");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (creativeId == null || creativeId.isEmpty()) {
            Teak.log.e("notification.schedule.error", "creativeId cannot be null or empty");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.creativeId");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (defaultMessage == null || defaultMessage.isEmpty()) {
            Teak.log.e("notification.schedule.error", "defaultMessage cannot be null or empty");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.defaultMessage");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (delayInSeconds > 2630000 /* one month in seconds */ || delayInSeconds < 0) {
            Teak.log.e("notification.schedule.error", "delayInSeconds can not be negative, or greater than one month");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.delayInSeconds");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        final ArrayBlockingQueue<String> q = new ArrayBlockingQueue<>(1);
        final FutureTask<String> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);

                final Map<String, Object> err = new HashMap<>();
                err.put("status", "error.exception.exception");
                return new JSONObject(err).toString();
            }
        });

        Session.whenUserIdIsOrWasReadyRun(session -> {
            HashMap<String, Object> payload = new HashMap<>();
            payload.put("identifier", creativeId);
            payload.put("message", defaultMessage);
            payload.put("offset", delayInSeconds);

            Request.submit("/me/local_notify.json", payload, session,
                (responseCode, responseBody) -> {
                    try {
                        JSONObject response = new JSONObject(responseBody);

                        final Map<String, Object> contents = new HashMap<>();
                        if (response.has("status")) {
                            contents.put("status", response.getString("status"));

                            if (response.getString("status").equals("ok")) {
                                Teak.log.i("notification.schedule", "Scheduled notification.", mm.h("notification", response.getJSONObject("event").get("id")));
                                contents.put("data", response.getJSONObject("event").get("id").toString());
                            } else {
                                Teak.log.e("notification.schedule.error", "Error scheduling notification.", mm.h("response", response.toString()));
                            }
                        } else {
                            Teak.log.e("notification.schedule.error", "JSON does not contain 'status' element.");
                            contents.put("status", "error.internal");
                        }

                        q.offer(new JSONObject(contents).toString());
                    } catch (JSONException e) {
                        Teak.log.e("notification.schedule.error", "Error parsing JSON: " + e.toString());
                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                    } catch (Exception e) {
                        Teak.log.exception(e, mm.h("teakCreativeId", creativeId));

                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                    }

                    ret.run();
                });
        });
        return ret;
    }

    /**
     * Schedules a push notification, to be delivered to other users, for some time in the future.
     *
     * @param creativeId     The identifier of the notification in the Teak dashboard, this must already exist.
     * @param delayInSeconds The delay in seconds from now to send the notification.
     * @param userIds        A list of game-assigned user ids to deliver the notification to.
     * @return The identifiers of the scheduled notifications (see {@link TeakNotification#cancelNotification(String)} or null.
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public static FutureTask<String> scheduleNotification(final String creativeId, final long delayInSeconds, final String[] userIds) {
        Teak.log.trace("TeakNotification.scheduleNotification", "creativeId", creativeId, "delayInSeconds", delayInSeconds, "userIds", Arrays.toString(userIds));

        if (Teak.Instance == null || !Teak.Instance.isEnabled()) {
            Teak.log.e("notification.schedule.disabled", "Teak is disabled, ignoring scheduleNotification().");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.teak.disabled");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (creativeId == null || creativeId.isEmpty()) {
            Teak.log.e("notification.schedule.error", "creativeId cannot be null or empty");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.creativeId");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (userIds == null || userIds.length < 1) {
            Teak.log.e("notification.schedule.error", "userIds cannot be null or empty");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.userIds");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (delayInSeconds > 2630000 /* one month in seconds */ || delayInSeconds < 0) {
            Teak.log.e("notification.schedule.error", "delayInSeconds can not be negative, or greater than one month");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.delayInSeconds");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        final ArrayBlockingQueue<String> q = new ArrayBlockingQueue<>(1);
        final FutureTask<String> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);

                final Map<String, Object> err = new HashMap<>();
                err.put("status", "error.exception.exception");
                return new JSONObject(err).toString();
            }
        });

        Session.whenUserIdIsOrWasReadyRun(session -> {
            HashMap<String, Object> payload = new HashMap<>();
            payload.put("identifier", creativeId);
            payload.put("offset", delayInSeconds);
            payload.put("user_ids", userIds);

            Request.submit("/me/long_distance_notify.json", payload, session,
                (responseCode, responseBody) -> {
                    try {
                        JSONObject response = new JSONObject(responseBody);

                        final Map<String, Object> contents = new HashMap<>();
                        if (response.has("status")) {
                            contents.put("status", response.getString("status"));

                            if (response.getString("status").equals("ok")) {
                                Teak.log.i("notification.schedule", "Scheduled notification.", mm.h("notification", response.getJSONArray("ids").toString()));
                                contents.put("data", response.getJSONArray("ids").toString());
                            } else {
                                Teak.log.e("notification.schedule.error", "Error scheduling notification.", mm.h("response", response.toString()));
                            }
                        } else {
                            Teak.log.e("notification.schedule.error", "JSON does not contain 'status' element.");
                            contents.put("status", "error.internal");
                        }

                        q.offer(new JSONObject(contents).toString());
                    } catch (JSONException e) {
                        Teak.log.e("notification.schedule.error", "Error parsing JSON: " + e.toString());
                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                    } catch (Exception e) {
                        Teak.log.exception(e, mm.h("teakCreativeId", creativeId));

                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                    }

                    ret.run();
                });
        });
        return ret;
    }

    /**
     * Cancel a push notification that was scheduled with {@link TeakNotification#scheduleNotification(String, String, long)}
     *
     * @param scheduleId Id returned by {@link TeakNotification#scheduleNotification(String, String, long)}
     * @return The status of the operation
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public static FutureTask<String> cancelNotification(final String scheduleId) {
        Teak.log.trace("TeakNotification.cancelNotification", "scheduleId", scheduleId);

        if (Teak.Instance == null || !Teak.Instance.isEnabled()) {
            Teak.log.e("notification.cancel.disabled", "Teak is disabled, ignoring cancelNotification().");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.teak.disabled");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        if (scheduleId == null || scheduleId.isEmpty()) {
            Teak.log.e("notification.cancel.error", "scheduleId cannot be null or empty");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.parameter.creativeId");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        final ArrayBlockingQueue<String> q = new ArrayBlockingQueue<>(1);
        final FutureTask<String> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);
            }
            return null;
        });

        Session.whenUserIdIsOrWasReadyRun(session -> {
            HashMap<String, Object> payload = new HashMap<>();
            payload.put("id", scheduleId);

            Request.submit("/me/cancel_local_notify.json", payload, session,
                (responseCode, responseBody) -> {
                    try {
                        JSONObject response = new JSONObject(responseBody);

                        final Map<String, Object> contents = new HashMap<>();
                        if (response.has("status")) {
                            contents.put("status", response.getString("status"));

                            if (response.getString("status").equals("ok")) {
                                Teak.log.i("notification.cancel", "Canceled notification.", mm.h("notification", scheduleId));
                                contents.put("data", response.getJSONObject("event").get("id").toString());
                            } else {
                                Teak.log.e("notification.cancel.error", "Error canceling notification.", mm.h("response", response.toString()));
                            }
                        } else {
                            Teak.log.e("notification.cancel.error", "Timed out while canceling notification.");
                            contents.put("status", "error.internal");
                        }
                        q.offer(new JSONObject(contents).toString());
                    } catch (JSONException e) {
                        Teak.log.e("notification.cancel.error", "Timed out while canceling notification.");
                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                    } catch (Exception e) {
                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                        Teak.log.exception(e, mm.h("scheduleId", scheduleId));
                    }
                    ret.run();
                });
        });

        return ret;
    }

    /**
     * Cancel all scheduled push notifications for the logged in user.
     *
     * @return A list containing the ids of all scheduled notifications.
     */
    @SuppressWarnings("unused")
    public static FutureTask<String> cancelAll() {
        Teak.log.trace("TeakNotification.cancelAll");

        if (!Teak.isEnabled()) {
            Teak.log.e("notification.cancel_all.disabled", "Teak is disabled, ignoring cancelAll().");

            final Map<String, Object> ret = new HashMap<>();
            ret.put("status", "error.teak.disabled");

            return new FutureTask<>(() -> new JSONObject(ret).toString());
        }

        final ArrayBlockingQueue<String> q = new ArrayBlockingQueue<>(1);
        final FutureTask<String> ret = new FutureTask<>(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Teak.log.exception(e);
            }
            return null;
        });

        Session.whenUserIdIsOrWasReadyRun(session -> {
            HashMap<String, Object> payload = new HashMap<>();

            Request.submit("/me/cancel_all_local_notifications.json", payload, session,
                (responseCode, responseBody) -> {
                    try {
                        JSONObject response = new JSONObject(responseBody);

                        final Map<String, Object> contents = new HashMap<>();
                        if (response.has("status")) {
                            contents.put("status", response.getString("status"));

                            if (response.getString("status").equals("ok")) {
                                ArrayList<Map<String, Object>> canceled = new ArrayList<>();
                                JSONArray jArray = response.getJSONArray("canceled");
                                if (jArray != null) {
                                    for (int i = 0; i < jArray.length(); i++) {
                                        canceled.add(jArray.getJSONObject(i).toMap());
                                    }
                                }
                                contents.put("data", canceled);
                                Teak.log.i("notification.cancel_all", "Canceled all notifications.");
                            } else {
                                Teak.log.e("notification.cancel_all.error", "Error canceling all notifications.", mm.h("response", response.toString()));
                            }
                        } else {
                            Teak.log.e("notification.cancel.error", "Timed out while canceling all notifications.");
                            contents.put("status", "error.internal");
                        }
                        q.offer(new JSONObject(contents).toString());
                    } catch (JSONException e) {
                        Teak.log.e("notification.cancel.error", "Timed out while canceling all notifications.");
                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                    } catch (Exception e) {
                        final Map<String, Object> contents = new HashMap<>();
                        contents.put("status", "error.internal");
                        q.offer(new JSONObject(contents).toString());
                        Teak.log.exception(e, mm.h("responseBody", responseBody));
                    }
                    ret.run();
                });
        });

        return ret;
    }

    /**************************************************************************/
    // @cond hide_from_doxygen
    // Version of the push from Teak
    static final int TEAK_NOTIFICATION_V0 = 0;
    int notificationVersion = TEAK_NOTIFICATION_V0;

    final String teakCreativeName;

    // v1
    public int platformId;
    public final long teakNotifId;

    final String title;
    final String message;
    final String longText;
    final String imageAssetA;

    @SuppressWarnings("WeakerAccess")
    final JSONObject extras;
    @SuppressWarnings("WeakerAccess")
    final String teakDeepLink;
    @SuppressWarnings("WeakerAccess")
    final String teakRewardId;

    // v2+
    final JSONObject display;
    final boolean useDecoratedCustomView;

    // v2023
    @SuppressWarnings("WeakerAccess")
    final String teakOptOutCategory;

    public final String groupKey;
    public final int minGroupSize;
    public final int groupSummaryId;
    public final String groupTitle;
    public final String groupMessage;

    // Animation
    public boolean isAnimated;

    public final Bundle bundle;

    public enum NotificationPlacement {
        Background("background"),
        Foreground("foreground");

        public final String name;

        NotificationPlacement(String name) {
            this.name = name;
        }
    }
    public final NotificationPlacement notificationPlacement;

    public TeakNotification(Bundle bundle, boolean appInForeground) {
        this.message = bundle.getString("message");
        this.title = bundle.getString("title");
        this.longText = bundle.getString("longText");
        this.teakRewardId = bundle.getString("teakRewardId");
        this.imageAssetA = bundle.getString("imageAssetA");
        this.teakDeepLink = bundle.getString("teakDeepLink");
        this.teakCreativeName = bundle.getString("teakCreativeName");
        this.teakOptOutCategory = bundle.getString("teakOptOutCategory", "teak");
        this.groupKey = bundle.getString("teakGroupKey", "teak");
        this.minGroupSize = bundle.getInt("teakGroupMinSize", 2);
        this.groupSummaryId = bundle.getInt("groupSummaryId", 0);
        this.groupTitle = bundle.getString("teakGroupTitle");
        this.groupMessage = bundle.getString("teakGroupMessage", "{{notification_count}} new messages");
        this.isAnimated = false;
        this.bundle = bundle;
        this.notificationPlacement = appInForeground ? NotificationPlacement.Foreground : NotificationPlacement.Background;

        JSONObject tempExtras = null;
        try {
            tempExtras = bundle.getString("extras") == null ? null : new JSONObject(bundle.getString("extras"));
        } catch (Exception ignored) {
        }
        this.extras = tempExtras;

        try {
            this.notificationVersion = Integer.parseInt(bundle.getString("version"));
        } catch (Exception ignored) {
            this.notificationVersion = TEAK_NOTIFICATION_V0;
        }

        JSONObject tempDisplay = null;

        // Check for content specified by SDK version
        if (bundle.getString("versioned_content") != null) {
            try {
                final JSONArray versionedContent = new JSONArray(bundle.getString("versioned_content"));
                for (Object content : versionedContent) {
                    if (!(content instanceof JSONObject)) continue;
                    final JSONObject jsonContent = (JSONObject) content;

                    final String contentVersionString = jsonContent.getString("version");
                    if (contentVersionString == null) continue;

                    Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+).*").matcher(contentVersionString);
                    if (m.matches()) {
                        int[] contentMajorMinorRevision = new int[] {
                            Integer.parseInt(m.group(1)), // major
                            Integer.parseInt(m.group(2)), // minor
                            Integer.parseInt(m.group(3))  // revision
                        };

                        if (compareMajorMinorRevision(Teak.MajorMinorRevision, contentMajorMinorRevision) >= 0) {
                            tempDisplay = jsonContent;
                        } else {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Teak.log.exception(e);
            }
        }

        // Fall back to display
        if (tempDisplay == null && bundle.getString("display") != null) {
            try {
                tempDisplay = new JSONObject(bundle.getString("display"));
            } catch (Exception e) {
                Teak.log.exception(e);
                this.notificationVersion = TEAK_NOTIFICATION_V0;
            }
        }
        this.display = tempDisplay;

        long tempTeakNotifId = 0;
        try {
            tempTeakNotifId = Long.parseLong(bundle.getString("teakNotifId"));
        } catch (Exception ignored) {
        }
        this.teakNotifId = tempTeakNotifId;

        this.useDecoratedCustomView = Helpers.getBooleanFromBundle(bundle, "useDecoratedCustomView");

        this.platformId = new Random().nextInt();

        // Update our bundle with calculated information.
        bundle.putInt("platformId", platformId);
        bundle.putString("teakNotificationPlacement", notificationPlacement.name);
    }

    private int compareMajorMinorRevision(int[] version, int[] otherVersion) {
        if (version[0] != otherVersion[0]) {
            return version[0] - otherVersion[0];
        }

        if (version[1] != otherVersion[1]) {
            return version[1] - otherVersion[1];
        }

        if (version[2] != otherVersion[2]) {
            return version[2] - otherVersion[2];
        }

        return 0;
    }
    // @endcond
}
