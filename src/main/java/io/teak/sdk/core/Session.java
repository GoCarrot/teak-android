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
package io.teak.sdk.core;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLProtocolException;

import io.teak.sdk.Helpers;
import io.teak.sdk.Helpers.mm;
import io.teak.sdk.InstallReferrerReceiver;
import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.DeviceConfiguration;
import io.teak.sdk.event.FacebookAccessTokenEvent;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.event.SessionStateEvent;
import io.teak.sdk.event.UserIdEvent;

public class Session {

    private static long SAME_SESSION_TIME_DELTA = 120000;

    // region State machine
    public enum State {
        Invalid("Invalid"),
        Allocated("Allocated"),
        Created("Created"),
        Configured("Configured"),
        IdentifyingUser("IdentifyingUser"),
        UserIdentified("UserIdentified"),
        Expiring("Expiring"),
        Expired("Expired");

        //public static final Integer length = 1 + Expired.ordinal();

        private static final State[][] allowedTransitions = {
                {},
                {State.Created, State.Expiring},
                {State.Configured, State.Expiring},
                {State.IdentifyingUser, State.Expiring},
                {State.UserIdentified, State.Expiring},
                {State.Expiring},
                {State.Created, State.Configured, State.IdentifyingUser, State.UserIdentified, State.Expired},
                {}
        };

        public final String name;

        State(String name) {
            this.name = name;
        }

        public boolean canTransitionTo(State nextState) {
            if (nextState == State.Invalid) return true;

            for (State allowedTransition : allowedTransitions[this.ordinal()]) {
                if (nextState == allowedTransition) return true;
            }
            return false;
        }
    }

    private State state = State.Allocated;
    private State previousState = null;
    private final Object stateMutex = new Object();
    // endregion
/*
    // State: Created
    private final Date startDate;
    private final String sessionId;

    // State: Configured
    // (none)

    // State: UserIdentified
    private String userId;

    private ScheduledExecutorService heartbeatService;
    private String countryCode;
    private String facebookAccessToken;

    // State: Expiring
    private Date endDate;

    // State Independent
    private Future<Map<String, Object>> launchAttribution = null;

    // For cases where setUserId() is called before a Session has been created
    private static String pendingUserId;

    private static final String PREFERENCE_FIRST_RUN = "io.teak.sdk.Preferences.FirstRun";

    private Session() {
        // State: Created
        // Valid data:
        // - startDate
        // - appConfiguration
        // - deviceConfiguration
        this.startDate = new Date();
        this.sessionId = UUID.randomUUID().toString().replace("-", "");

        TeakEvent.addEventListener(this.teakEventListener);

        DeviceConfiguration.addEventListener(this.deviceConfigurationListener);

        setState(State.Created);
    }

    private Session(@NonNull Session session, Future<Map<String, Object>> launchAttribution) {
        this();
        this.launchAttribution = launchAttribution;
        this.userId = session.userId;
    }

    private boolean hasExpired() {
        synchronized (stateMutex) {
            if (this.state == State.Expiring &&
                    (new Date().getTime() - this.endDate.getTime() > SAME_SESSION_TIME_DELTA)) {
                setState(State.Expired);
            }
            return (this.state == State.Expired);
        }
    }

    private boolean setState(@NonNull State newState) {
        synchronized (stateMutex) {
            if (this.state == newState) {
                Teak.log.i("session.same_state", Helpers.mm.h("state", this.state));
                return false;
            }

            if (!this.state.canTransitionTo(newState)) {
                Teak.log.e("session.invalid_state", mm.h("state", this.state, "new_state", newState));
                return false;
            }

            ArrayList<Object[]> invalidValuesForTransition = new ArrayList<>();

            // Check the data that should be valid before transitioning to the next state. Perform any
            // logic that should occur on transition.
            switch (newState) {
                case Created: {
                    if (this.startDate == null) {
                        invalidValuesForTransition.add(new Object[]{"startDate", "null"});
                        break;
                    }

                    TeakEvent.addEventListener(this.remoteConfigurationEventListener);
                }
                break;

                case Configured: {
                    TeakEvent.removeEventListener(this.remoteConfigurationEventListener);

                    final Session _this = this;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (_this.userId != null) {
                                _this.identifyUser();
                            }
                        }
                    }).start();
                }
                break;

                case IdentifyingUser: {
                    if (this.userId == null) {
                        invalidValuesForTransition.add(new Object[]{"userId", "null"});
                        break;
                    }
                }
                break;

                case UserIdentified: {

                    // Start heartbeat, heartbeat service should be null right now
                    if (this.heartbeatService != null) {
                        invalidValuesForTransition.add(new Object[]{"heartbeatService", this.heartbeatService});
                        break;
                    }

                    startHeartbeat();

                    synchronized (userIdReadyRunnableQueueMutex) {
                        for (WhenUserIdIsReadyRun runnable : userIdReadyRunnableQueue) {
                            new Thread(runnable).start();
                        }
                        userIdReadyRunnableQueue.clear();
                    }
                }
                break;

                case Expiring: {
                    this.endDate = new Date();

                    // TODO: When expiring, punt to background service and say "Hey check the state of this session in N seconds"

                    // Stop heartbeat, Expiring->Expiring is possible, so no invalid data here
                    if (this.heartbeatService != null) {
                        this.heartbeatService.shutdown();
                        this.heartbeatService = null;
                    }
                }
                break;

                case Expired: {
                    TeakEvent.removeEventListener(this.teakEventListener);
                    DeviceConfiguration.removeEventListener(this.deviceConfigurationListener);

                    // TODO: Report Session to server, once we collect that info.
                }
                break;
            }

            // Print out any invalid values
            if (invalidValuesForTransition.size() > 0) {
                Map<String, Object> h = new HashMap<>();
                for (Object[] invalidValue : invalidValuesForTransition) {
                    h.put(invalidValue[0].toString(), invalidValue[1]);
                }
                h.put("state", this.state);
                h.put("new_state", newState);
                Teak.log.e("session.invalid_values", h);

                // Invalidate this session
                this.setState(State.Invalid);
                return false;
            }

            this.previousState = this.state;
            this.state = newState;

            Teak.log.i("session.state", Helpers.mm.h("state", this.state.name, "old_state", this.previousState.name));
            TeakEvent.postEvent(new SessionStateEvent(this, this.state, this.previousState));

            TeakConfiguration teakConfiguration = TeakConfiguration.get();
            //noinspection all - Seriously, that is not a simplification
            if (this.state == State.Created && teakConfiguration != null && teakConfiguration.remoteConfiguration != null) {
                return setState(State.Configured);
            } else {
                return true;
            }
        }
    }

    private void startHeartbeat() {
        final Session _this = this;
        final TeakConfiguration teakConfiguration = TeakConfiguration.get();

        this.heartbeatService = Executors.newSingleThreadScheduledExecutor();
        this.heartbeatService.scheduleAtFixedRate(new Runnable() {
            public void run() {
                HttpsURLConnection connection = null;
                try {
                    // TODO: Ask Alex if we should use Teak.Version instead, and what needs to be changed
                    //noinspection deprecation
                    String queryString = "game_id=" + URLEncoder.encode(teakConfiguration.appConfiguration.appId, "UTF-8") +
                            "&api_key=" + URLEncoder.encode(_this.userId, "UTF-8") +
                            "&sdk_version=" + URLEncoder.encode(Teak.SDKVersion, "UTF-8") +
                            "&sdk_platform=" + URLEncoder.encode(teakConfiguration.deviceConfiguration.platformString, "UTF-8") +
                            "&app_version=" + URLEncoder.encode(String.valueOf(teakConfiguration.appConfiguration.appVersion), "UTF-8") +
                            (_this.countryCode == null ? "" : "&country_code=" + URLEncoder.encode(String.valueOf(_this.countryCode), "UTF-8")) +
                            "&buster=" + URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
                    URL url = new URL("https://iroko.gocarrot.com/ping?" + queryString);
                    connection = (HttpsURLConnection) url.openConnection();
                    connection.setRequestProperty("Accept-Charset", "UTF-8");
                    connection.setUseCaches(false);
                    connection.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }, 0, 1, TimeUnit.MINUTES); // TODO: If RemoteConfiguration specifies a different rate, use that
    }

    private void identifyUser() {
        final Session _this = this;
        final TeakConfiguration teakConfiguration = TeakConfiguration.get();

        new Thread(new Runnable() {
            public void run() {
                synchronized (_this.stateMutex) {
                    if (_this.state != State.UserIdentified && !_this.setState(State.IdentifyingUser)) {
                        return;
                    }

                    HashMap<String, Object> payload = new HashMap<>();

                    if (_this.state == State.UserIdentified) {
                        payload.put("do_not_track_event", Boolean.TRUE);
                    }

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

                    if (teakConfiguration.deviceConfiguration.advertisingId != null) {
                        payload.put("android_ad_id", teakConfiguration.deviceConfiguration.advertisingId);
                        payload.put("android_limit_ad_tracking", teakConfiguration.deviceConfiguration.limitAdTracking);
                    }

                    if (_this.facebookAccessToken != null) {
                        payload.put("access_token", _this.facebookAccessToken);
                    }

                    Map<String, Object> attribution = new HashMap<>();
                    if (_this.launchAttribution != null) {
                        try {
                            attribution = _this.launchAttribution.get(5, TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                        }
                    }
                    for (Map.Entry<String, Object> entry : attribution.entrySet()) {
                        payload.put(entry.getKey(), entry.getValue());
                    }

                    if (teakConfiguration.deviceConfiguration.gcmId != null) {
                        payload.put("gcm_push_key", teakConfiguration.deviceConfiguration.gcmId);
                    }

                    if (teakConfiguration.deviceConfiguration.admId != null) {
                        payload.put("adm_push_key", teakConfiguration.deviceConfiguration.admId);
                    }

                    Teak.log.i("session.identify_user", Helpers.mm.h("userId", _this.userId, "timezone", tzOffset, "locale", locale));

                    new Request("/games/" + teakConfiguration.appConfiguration.appId + "/users.json", payload, _this) {
                        @Override
                        protected void done(int responseCode, String responseBody) {
                            try {
                                JSONObject response = new JSONObject(responseBody);

                                // TODO: Grab 'id' and 'game_id' from response and store for Parsnip

                                // Enable verbose logging if flagged
                                boolean enableVerboseLogging = response.optBoolean("verbose_logging");
                                teakConfiguration.debugConfiguration.setPreferenceForceDebug(enableVerboseLogging);

                                // Server requesting new push key.
                                if (response.optBoolean("reset_push_key", false)) {
                                    teakConfiguration.deviceConfiguration.reRegisterPushToken(teakConfiguration.appConfiguration, "reset_push_key");
                                }

                                if (response.has("country_code")) {
                                    _this.countryCode = response.getString("country_code");
                                }

                                // Prevent warning for 'do_not_track_event'
                                if (_this.state == State.Expiring) {
                                    _this.previousState = State.UserIdentified;
                                } else if (_this.state != State.UserIdentified) {
                                    _this.setState(State.UserIdentified);
                                }
                            } catch (Exception ignored) {
                            }

                            super.done(responseCode, responseBody);
                        }
                    }.run();
                }
            }
        }).start();
    }

    private DeviceConfiguration.EventListener deviceConfigurationListener = new DeviceConfiguration.EventListener() {
        @Override
        public void onPushIdChanged(DeviceConfiguration deviceConfiguration) {
            synchronized (stateMutex) {
                if (state == State.UserIdentified) {
                    identifyUser();
                }
            }
        }

        @Override
        public void onAdvertisingInfoChanged(DeviceConfiguration deviceConfiguration) {
            synchronized (stateMutex) {
                if (state == State.UserIdentified) {
                    identifyUser();
                }
            }
        }
    };

    private final TeakEvent.EventListener teakEventListener = new TeakEvent.EventListener() {
        @Override
        public void onNewEvent(@NonNull TeakEvent event) {
            // Facebook Access Token
            if (event.eventType.equals(FacebookAccessTokenEvent.Type)) {
                String newAccessToken = ((FacebookAccessTokenEvent)event).accessToken;
                if (newAccessToken != null && !newAccessToken.equals(facebookAccessToken)) {
                    facebookAccessToken = newAccessToken;
                    Teak.log.i("session.fb_access_token", Helpers.mm.h("access_token", facebookAccessToken));

                    // TODO: Revisit this logic
                    synchronized (stateMutex) {
                        if (state == State.UserIdentified) {
                            identifyUser();
                        }
                    }
                }
            }
        }
    };

    // This is separate so it can be removed/added independently
    private final TeakEvent.EventListener remoteConfigurationEventListener = new TeakEvent.EventListener() {
        @Override
        public void onNewEvent(@NonNull TeakEvent event) {
            if (event.eventType.equals(RemoteConfigurationEvent.Type)) {
                synchronized (currentSession.stateMutex) {
                    if (currentSession.state == State.Expiring) {
                        currentSession.previousState = State.Configured;
                    } else {
                        setState(State.Configured);
                    }
                }
            }
        }
    };

    // Static event listener
    static {
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (event.eventType.equals(UserIdEvent.Type)) {
                    String userId = ((UserIdEvent)event).userId;
                    setUserId(userId);
                } else if (event.eventType.equals(LifecycleEvent.Paused)) {
                    // Set state to 'Expiring'
                    synchronized (currentSessionMutex) {
                        if (currentSession != null) {
                            currentSession.setState(State.Expiring);
                        }
                    }
                } else if (event.eventType.equals(LifecycleEvent.Resumed)) {
                    Intent resumeIntent = ((LifecycleEvent)event).intent;
                    onActivityResumed(resumeIntent);
                }
            }
        });
    }

    static boolean isExpiringOrExpired() {
        synchronized (currentSessionMutex) {
            return currentSession == null || currentSession.hasExpired() || currentSession.state == State.Expiring;
        }
    }

    private static void setUserId(@NonNull String userId) {
        // If the user id has changed, create a new session
        synchronized (currentSessionMutex) {
            if (currentSession == null) {
                Session.pendingUserId = userId;
            } else {
                synchronized (currentSession.stateMutex) {
                    if (currentSession.userId != null && !currentSession.userId.equals(userId)) {
                        Session newSession = new Session(currentSession, currentSession.launchAttribution);

                        currentSession.setState(State.Expiring);
                        currentSession.setState(State.Expired);

                        currentSession = newSession;
                    }

                    currentSession.userId = userId;

                    if (currentSession.state == State.Configured) {
                        currentSession.identifyUser();
                    }
                }
            }
        }
    }

    static abstract class SessionRunnable {
        public abstract void run(Session session);
    }

    private static class WhenUserIdIsReadyRun implements Runnable {
        private SessionRunnable runnable;

        WhenUserIdIsReadyRun(SessionRunnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            synchronized (currentSessionMutex) {
                this.runnable.run(currentSession);
            }
        }
    }

    private static final Object userIdReadyRunnableQueueMutex = new Object();
    private static final ArrayList<WhenUserIdIsReadyRun> userIdReadyRunnableQueue = new ArrayList<>();

    static void whenUserIdIsReadyRun(@NonNull SessionRunnable runnable) {
        synchronized (currentSessionMutex) {
            if (currentSession == null) {
                synchronized (userIdReadyRunnableQueueMutex) {
                    userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                }
            } else {
                synchronized (currentSession.stateMutex) {
                    if (currentSession.state == State.UserIdentified) {
                        new Thread(new WhenUserIdIsReadyRun(runnable)).start();
                    } else {
                        synchronized (userIdReadyRunnableQueueMutex) {
                            userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                        }
                    }
                }
            }
        }
    }

    static void whenUserIdIsOrWasReadyRun(@NonNull SessionRunnable runnable) {
        synchronized (currentSessionMutex) {
            if (currentSession == null) {
                synchronized (userIdReadyRunnableQueueMutex) {
                    userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                }
            } else {
                synchronized (currentSession.stateMutex) {
                    if (currentSession.state == State.UserIdentified ||
                            (currentSession.state == State.Expiring && currentSession.previousState == State.UserIdentified)) {
                        new Thread(new WhenUserIdIsReadyRun(runnable)).start();
                    } else {
                        synchronized (userIdReadyRunnableQueueMutex) {
                            userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                        }
                    }
                }
            }
        }
    }

    private static void onActivityResumed(final Intent intent) {
        // Call getCurrentSession() so the null || Expired logic stays in one place
        synchronized (currentSessionMutex) {
            getCurrentSession();

            // If this intent has already been processed by Teak, just reset the state and we are done.
            // Otherwise, out-of-app deep links can cause a back-stack loop
            if (intent.getBooleanExtra("processedByTeak", false)) {
                // Reset state on current session, if it is expiring
                synchronized (currentSession.stateMutex) {
                    if (currentSession.state == State.Expiring) {
                        currentSession.setState(currentSession.previousState);
                    }
                }
                return;
            }

            final TeakConfiguration teakConfiguration = TeakConfiguration.get();

            // Check and see if this is (probably) the first time this app has been ever launched
            boolean isFirstLaunch = false;
            // TODO: Just make a TeakPreferences class or something
            if (teakConfiguration.deviceConfiguration.preferences != null) {
                long firstLaunch = teakConfiguration.deviceConfiguration.preferences.getLong(PREFERENCE_FIRST_RUN, 0);
                if (firstLaunch == 0) {
                    firstLaunch = new Date().getTime() / 1000;
                    SharedPreferences.Editor editor = teakConfiguration.deviceConfiguration.preferences.edit();
                    editor.putLong(PREFERENCE_FIRST_RUN, firstLaunch);
                    editor.apply();
                    isFirstLaunch = true;
                }
            }

            // If this is the first launch, see if the InstallReferrerReceiver has anything for us.
            Future<String> deepLinkURL = null;
            if (isFirstLaunch) {
                FutureTask<String> referrerPollTask = new FutureTask<>(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        long startTime = System.nanoTime();
                        String referralString = InstallReferrerReceiver.installReferrerQueue.poll();
                        while(referralString == null) {
                            Thread.sleep(100);
                            referralString = InstallReferrerReceiver.installReferrerQueue.poll();
                            long elapsedTime = System.nanoTime() - startTime;
                            if (elapsedTime / 1000000000 > 10) break;
                        }
                        return referralString;
                    }
                });
                new Thread(referrerPollTask).start();
                deepLinkURL = referrerPollTask;
            } else {
                // Otherwise see if there's a deep link in the intent
                final String intentDataString = intent.getDataString();
                if (intentDataString != null && !intentDataString.isEmpty()) {
                    Teak.log.i("session.attribution", Helpers.mm.h("deep_link", intentDataString));
                    deepLinkURL = new Future<String>() {
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
                        public String get() throws InterruptedException, ExecutionException {
                            return intentDataString;
                        }

                        @Override
                        public String get(long l, @NonNull TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
                            return get();
                        }
                    };
                }
            }

            //
            final Future<Map<String, Object>> deepLinkAttribution;
            if (deepLinkURL != null) {
                deepLinkAttribution = attributionForDeepLink(deepLinkURL);
            } else {
                deepLinkAttribution = null;
            }

            // Check for launch via notification
            String nonFinalTeakNotifId = null;
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                String teakNotifId = bundle.getString("teakNotifId");
                if (teakNotifId != null && !teakNotifId.isEmpty()) {
                    nonFinalTeakNotifId = teakNotifId;
                }
            }
            final String teakNotifId = nonFinalTeakNotifId;

            // Get the session attribution
            final Future<Map<String, Object>> sessionAttribution;
            if (teakNotifId != null) {
                Teak.log.i("session.attribution", Helpers.mm.h("teak_notif_id", teakNotifId));
                sessionAttribution = new Future<Map<String, Object>>() {
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
                    public Map<String, Object> get() throws InterruptedException, ExecutionException {
                        Map<String, Object> returnValue = new HashMap<>();
                        returnValue.put("teak_notif_id", teakNotifId);
                        return returnValue;
                    }

                    @Override
                    public Map<String, Object> get(long l, @NonNull TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
                        return get();
                    }
                };
            } else if (deepLinkAttribution != null) {
                sessionAttribution = deepLinkAttribution;
            } else {
                sessionAttribution = null;
            }

            // If there is any deep link, see if we handle the link
            if (deepLinkAttribution != null) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // If we need to wait for Unity/Adobe Air, do it here
                            if (Teak.waitForDeepLink != null) {
                                Teak.waitForDeepLink.get();
                            }
                        } catch (Exception ignored){
                        }

                        try {
                            // Resolve the deepLinkAttribution future
                            Map<String, Object> attribution = deepLinkAttribution.get(5, TimeUnit.SECONDS);
                            Uri uri = Uri.parse((String) attribution.get("deep_link"));

                            // See if TeakLinks can do anything with the deep link
                            if (!DeepLink.processUri(uri) && teakNotifId != null) {
                                // If this was a deep link from a Teak Notification, then go ahead and
                                // try to find a different app to launch.
                                Intent uriIntent = new Intent(Intent.ACTION_VIEW, uri);
                                uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                List<ResolveInfo> resolvedActivities = teakConfiguration.appConfiguration.packageManager.queryIntentActivities(uriIntent, 0);
                                boolean safeToRedirect = true;
                                for (ResolveInfo info : resolvedActivities) {
                                    safeToRedirect &= !teakConfiguration.appConfiguration.bundleId.equalsIgnoreCase(info.activityInfo.packageName);
                                }
                                if (resolvedActivities.size() > 0 && safeToRedirect) {
                                    teakConfiguration.appConfiguration.applicationContext.startActivity(uriIntent);
                                }
                            }

                            // Send reward broadcast
                            String teakRewardId = attribution.get("teak_reward_id").toString();
                            if (teakRewardId != null) {
                                final Future<TeakNotification.Reward> rewardFuture = TeakNotification.Reward.rewardFromRewardId(teakRewardId);
                                if (rewardFuture != null) {
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (Teak.Instance != null && Teak.Instance.localBroadcastManager != null) {
                                                try {
                                                    TeakNotification.Reward reward = rewardFuture.get();
                                                    HashMap<String, Object> rewardMap = Helpers.jsonToMap(reward.json);
                                                    final Intent rewardIntent = new Intent(Teak.REWARD_CLAIM_ATTEMPT);
                                                    rewardIntent.putExtra("reward", rewardMap);
                                                    Teak.Instance.localBroadcastManager.sendBroadcast(rewardIntent);
                                                } catch (Exception e) {
                                                    Teak.log.exception(e);
                                                }
                                            }
                                        }
                                    }).start();
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }).start();
            }

            // If the current session has a launch different attribution, it's a new session
            if (currentSession.state == State.Allocated || currentSession.state == State.Created) {
                currentSession.launchAttribution = sessionAttribution;
            } else if (sessionAttribution != null) {
                Session oldSession = currentSession;
                currentSession = new Session(oldSession, sessionAttribution);
                synchronized (oldSession.stateMutex) {
                    oldSession.setState(State.Expiring);
                    oldSession.setState(State.Expired);
                }
            } else {
                // Reset state on current session, if it is expiring
                synchronized (currentSession.stateMutex) {
                    if (currentSession.state == State.Expiring) {
                        currentSession.setState(currentSession.previousState);
                    }
                }
            }
        }
    }

    private static Future<Map<String, Object>> attributionForDeepLink(final Future<String> urlFuture, final AppConfiguration appConfiguration) {
        FutureTask<Map<String, Object>> returnTask = new FutureTask<>(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                Map<String, Object> returnValue = new HashMap<>();

                // Wait on the incoming Future
                Uri uri = null;
                try {
                    uri = Uri.parse(urlFuture.get(5, TimeUnit.SECONDS));
                } catch (Exception ignored) {
                }

                // If we have a URL, process it if needed
                if (uri != null) {
                    // Try and resolve any Teak links
                    if (uri.getScheme().equals("http") || uri.getScheme().equals("https")) {
                        HttpsURLConnection connection = null;
                        try {
                            Uri.Builder httpsUri = uri.buildUpon();
                            httpsUri.scheme("https");
                            URL url = new URL(httpsUri.build().toString());
                            connection = (HttpsURLConnection) url.openConnection();
                            connection.setRequestProperty("Accept-Charset", "UTF-8");
                            connection.setRequestProperty("X-Teak-DeviceType", "API");

                            // Get Response
                            InputStream is;
                            if (connection.getResponseCode() < 400) {
                                is = connection.getInputStream();
                            } else {
                                is = connection.getErrorStream();
                            }
                            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                            String line;
                            StringBuilder response = new StringBuilder();
                            while ((line = rd.readLine()) != null) {
                                response.append(line);
                                response.append('\r');
                            }
                            rd.close();

                            try {
                                JSONObject teakData = new JSONObject(response.toString());
                                if (teakData.getString("AndroidPath") != null) {
                                    uri = Uri.parse(String.format(Locale.US, "teak%s://%s", appConfiguration.appId, teakData.getString("AndroidPath")));
                                }
                            } catch (Exception ignored) {
                            }
                        } catch (SSLProtocolException ssl_e) {
                            // Ignored, Sentry issue 'TEAK-SDK-Z'
                        } catch (Exception e) {
                            Teak.log.exception(e);
                        } finally {
                            if (connection != null) {
                                connection.disconnect();
                            }
                        }
                    }

                    // Put the URI and any query parameters that start with 'teak_'
                    returnValue.put("deep_link", uri.toString());
                    for (String name : uri.getQueryParameterNames()) {
                        if (name.startsWith("teak_")) {
                            List<String> values = uri.getQueryParameters(name);
                            if (values.size() > 1) {
                                returnValue.put(name, values);
                            } else {
                                returnValue.put(name, values.get(0));
                            }
                        }
                    }
                }

                return returnValue;
            }
        });

        // Start it running, and return the Future
        new Thread(returnTask).start();
        return returnTask;
    }

    // region Accessors
    public String userId() {
        return userId;
    }
    // endregion

    // region Current Session
    private static Session currentSession;
    private static final Object currentSessionMutex = new Object();

    private static Session getCurrentSession() {
        synchronized (currentSessionMutex) {
            if (currentSession == null || currentSession.hasExpired()) {
                Session oldSession = currentSession;
                currentSession = new Session();

                if (oldSession != null) {
                    // If the old session had a user id assigned, it needs to be passed to the newly created
                    // session. When setState(State.Configured) happens, it will call identifyUser()
                    if (oldSession.userId != null) {
                        setUserId(oldSession.userId);
                    }
                } else if (Session.pendingUserId != null) {
                    setUserId(Session.pendingUserId);
                    Session.pendingUserId = null;
                }
            }
            return currentSession;
        }
    }

    static Session getCurrentSessionOrNull() {
        synchronized (currentSessionMutex) {
            return currentSession;
        }
    }
    // endregion

    private Map<String, Object> to_h() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("startDate", this.startDate.getTime() / 1000);
        return map;
    }

    @Override
    public String toString() {
        try {
            return String.format(Locale.US, "%s: %s", super.toString(), Teak.formatJSONForLogging(new JSONObject(this.to_h())));
        } catch (Exception ignored) {
            return super.toString();
        }
    }*/
}