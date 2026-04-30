package io.teak.sdk.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.greenrobot.eventbus.EventBus;

import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.Helpers;
import io.teak.sdk.Helpers.mm;
import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.event.AdvertisingInfoEvent;
import io.teak.sdk.event.FacebookAccessTokenEvent;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.event.LogoutEvent;
import io.teak.sdk.event.PushRegistrationEvent;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.event.SessionStateEvent;
import io.teak.sdk.event.UserIdEvent;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.push.PushState;

public class Session {

    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"}) // This can be changed by tests
    private static long SAME_SESSION_TIME_DELTA = 120000;

    public static final Session NullSession = new Session("Null Session");

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

        // public static final Integer length = 1 + Expired.ordinal();

        private static final State[][] allowedTransitions = {
            {},
            {State.Created, State.Expiring},
            {State.Configured, State.Expiring},
            {State.IdentifyingUser, State.Expiring},
            {State.UserIdentified, State.Expiring},
            {State.Expiring},
            {State.Created, State.Configured, State.IdentifyingUser, State.UserIdentified, State.Expired},
            {}};

        public final String name;

        State(String name) {
            this.name = name;
        }

        public boolean canTransitionTo(State nextState) {
            if (nextState == State.Invalid) return true;

            for (State allowedTransition : allowedTransitions[this.ordinal()]) {
                if (nextState.equals(allowedTransition)) return true;
            }
            return false;
        }
    }

    private State state = State.Allocated;
    private State previousState = null;
    private final InstrumentableReentrantLock stateLock = new InstrumentableReentrantLock();
    private final ExecutorService executionQueue = Executors.newSingleThreadExecutor();
    // endregion

    // State: Created
    private final long startTimeMillis;
    @SuppressWarnings("UnusedDeclaration")
    private final String sessionId;

    // State: Configured
    // (none)

    // State: UserIdentified
    private String userId;
    public String email;

    private ScheduledExecutorService heartbeatService;
    private String countryCode;
    private String facebookAccessToken;
    private String facebookId;

    private ChannelStatus channelStatusEmail = ChannelStatus.Unknown;
    private ChannelStatus channelStatusPush = ChannelStatus.Unknown;
    private ChannelStatus channelStatusSms = ChannelStatus.Unknown;
    private JSONObject additionalData = null;

    public UserProfile userProfile;

    private String serverSessionId;
    private int sessionVectorClock;

    // State: Expiring
    private long endTimeMillis;

    // State Independent
    private LaunchDataSource launchDataSource = null;
    private boolean launchAttributionProcessed = false;
    private boolean userIdentificationSent = false;

    // For cases where setUserId() is called before a Session has been created
    private static String pendingUserId;
    private static String pendingEmail;
    private static String pendingFacebookId;

    // Used specifically for creating the "null session" which is just used for code-intent clarity
    private Session(@NonNull String nullSessionId) {
        this.startTimeMillis = SystemClock.elapsedRealtime();
        this.sessionId = nullSessionId;
    }

    private Session() {
        this(null, null);
    }

    private Session(@Nullable Session session, @Nullable LaunchDataSource launchDataSource) {
        // State: Created
        // Valid data:
        // - startTimeMillis
        // - appConfiguration
        // - deviceConfiguration
        this.startTimeMillis = SystemClock.elapsedRealtime();
        this.sessionId = UUID.randomUUID().toString().replace("-", "");
        this.serverSessionId = null;
        this.sessionVectorClock = 0;
        this.launchDataSource = launchDataSource;
        if (session != null) {
            this.userId = session.userId;
            this.facebookAccessToken = session.facebookAccessToken;
            this.facebookId = session.facebookId;
        }
        TeakEvent.addEventListener(this.teakEventListener);

        setState(State.Created);
    }

    private boolean hasExpired() {
        this.stateLock.lock();
        try {
            if (this.state == State.Expiring &&
                (SystemClock.elapsedRealtime() - this.endTimeMillis > SAME_SESSION_TIME_DELTA)) {
                setState(State.Expired);
            }
            return (this.state == State.Expired);
        } finally {
            this.stateLock.unlock();
        }
    }

    private boolean isCurrentSession() {
        Session.currentSessionLock.lock();
        this.stateLock.lock();
        try {
            return (Session.currentSession == this);
        } finally {
            this.stateLock.unlock();
            Session.currentSessionLock.unlock();
        }
    }

    private boolean setState(@NonNull State newState) {
        this.stateLock.lock();
        try {
            if (this.state == newState) {
                Teak.log.i("session.same_state", Helpers.mm.h("state", this.state, "session_id", this.sessionId));
                return true;
            }

            if (!this.state.canTransitionTo(newState)) {
                Teak.log.e("session.invalid_state", mm.h("state", this.state, "new_state", newState, "session_id", this.sessionId));
                return false;
            }

            ArrayList<Object[]> invalidValuesForTransition = new ArrayList<>();

            // Check the data that should be valid before transitioning to the next state. Perform any
            // logic that should occur on transition.
            switch (newState) {
                case Created: {
                    TeakEvent.addEventListener(this.remoteConfigurationEventListener);
                } break;

                case Configured: {
                    TeakEvent.removeEventListener(this.remoteConfigurationEventListener);

                    this.executionQueue.execute(() -> {
                        if (Session.this.userId != null) {
                            Session.this.identifyUser();
                        }
                    });
                } break;

                case IdentifyingUser: {
                    if (this.userId == null) {
                        invalidValuesForTransition.add(new Object[] {"userId", "null"});
                    }
                } break;

                case UserIdentified: {
                    // Start heartbeat, heartbeat service should be null right now
                    if (this.heartbeatService != null) {
                        invalidValuesForTransition.add(new Object[] {"heartbeatService", this.heartbeatService});
                    } else {
                        startHeartbeat();
                    }

                    userIdReadyRunnableQueueLock.lock();
                    try {
                        for (WhenUserIdIsReadyRun runnable : userIdReadyRunnableQueue) {
                            this.executionQueue.execute(runnable);
                        }
                        userIdReadyRunnableQueue.clear();
                    } finally {
                        userIdReadyRunnableQueueLock.unlock();
                    }

                    // Run EventBus on the main thread
                    new Handler(Looper.getMainLooper()).post(() -> {
                        userIdReadyEventBusQueueLock.lock();
                        try {
                            for (Object event : userIdReadyEventBusQueue) {
                                EventBus.getDefault().post(event);
                            }
                            userIdReadyEventBusQueue.clear();
                        } finally {
                            userIdReadyEventBusQueueLock.unlock();
                        }
                    });

                    // Process deep link and/or rewards and send out events
                    processAttributionAndDispatchEvents();

                    // Resurface unacked server-jwt claims for at-least-once delivery on
                    // RewardClaimResolvedEvent. Skipped on the Expiring → UserIdentified
                    // flicker (rapid background-foreground within SAME_SESSION_TIME_DELTA)
                    // — the same Session instance keeps any in-flight click-time poll
                    // alive across that transition, and a re-sweep would risk a double-
                    // fire for any claim whose ack hasn't yet committed server-side. The
                    // post-120s resume path goes through hasExpired → new Session →
                    // IdentifyingUser → UserIdentified, so the gate doesn't filter the
                    // design's primary use case (player kicks off a server-jwt click,
                    // backgrounds long enough to cycle Session, foregrounds → sweep
                    // retrieves the customer-server resolution).
                    if (this.state != State.Expiring) {
                        RewardClaimManager.get().startSweep(this);
                    }

                    // If we are currently expiring, reset the future that will report duration
                    // and send the server a "hey nevermind, I'm back" message
                    if (this.state == State.Expiring) {
                        this.sessionVectorClock++;
                        // Reset the future, and if session_stop got sent then send session_resume
                        // Send server a "nevermind that" message
                        HashMap<String, Object> payload = new HashMap<>();
                        payload.put("session_id", this.serverSessionId);
                        payload.put("session_vector_clock", this.sessionVectorClock);
                        Request.submit("gocarrot.com", "/session_resume", payload, this, null);
                    }
                } break;

                case Expiring: {
                    this.endTimeMillis = SystemClock.elapsedRealtime();

                    // Stop heartbeat, Expiring->Expiring is possible, so no invalid data here
                    if (this.heartbeatService != null) {
                        this.heartbeatService.shutdown();
                        this.heartbeatService = null;
                    }

                    // Send UserProfile to server
                    if (this.userProfile != null) {
                        TeakCore.operationQueue.execute(this.userProfile);
                    }

                    if (this.serverSessionId != null) {
                        this.sessionVectorClock++;
                        // This is a message to the server that, in effect, says "If you don't hear
                        // from me again, consider this session over"
                        HashMap<String, Object> payload = new HashMap<>();
                        payload.put("session_id", this.serverSessionId);
                        payload.put("session_duration_ms", this.endTimeMillis - this.startTimeMillis);
                        payload.put("session_vector_clock", this.sessionVectorClock);
                        Request.submit("gocarrot.com", "/session_stop", payload, this, null);
                    }
                } break;

                case Expired: {
                    TeakEvent.removeEventListener(this.teakEventListener);
                } break;
            }

            // Print out any invalid values
            if (invalidValuesForTransition.size() > 0) {
                Map<String, Object> h = new HashMap<>();
                for (Object[] invalidValue : invalidValuesForTransition) {
                    h.put(invalidValue[0].toString(), invalidValue[1]);
                }
                h.put("state", this.state);
                h.put("new_state", newState);
                h.put("session_id", this.sessionId);
                Teak.log.e("session.invalid_values", h);

                // Invalidate this session
                this.setState(State.Invalid);
                return false;
            }

            this.previousState = this.state;
            this.state = newState;

            Teak.log.i("session.state", Helpers.mm.h("state", this.state.name, "old_state", this.previousState.name, "session_id", this.sessionId));
            TeakEvent.postEvent(new SessionStateEvent(this, this.state, this.previousState));

            TeakConfiguration teakConfiguration = TeakConfiguration.get();
            // noinspection all - Seriously, that is not a simplification
            if (this.state == State.Created && teakConfiguration != null && teakConfiguration.remoteConfiguration != null) {
                return setState(State.Configured);
            } else {
                return true;
            }
        } finally {
            this.stateLock.unlock();
        }
    }

    private void startHeartbeat() {
        final TeakConfiguration teakConfiguration = TeakConfiguration.get();

        // If heartbeatInterval is 0, do not send heartbeats
        final int heartbeatInterval = teakConfiguration.remoteConfiguration != null ? teakConfiguration.remoteConfiguration.heartbeatInterval : 60;
        if (heartbeatInterval == 0) {
            return;
        }

        // TODO: Revist this when we have time, if it is important
        // noinspection deprecation - Alex said "ehhhhhhh" to changing the heartbeat param to a map
        @SuppressWarnings("deprecation")
        final String teakSdkVersion = Teak.SDKVersion;

        this.heartbeatService = Executors.newSingleThreadScheduledExecutor();
        this.heartbeatService.scheduleAtFixedRate(() -> {
            HttpsURLConnection connection = null;
            try {
                String buster;
                {
                    final SecureRandom random = new SecureRandom();
                    byte[] bytes = new byte[4];
                    random.nextBytes(bytes);

                    // When packing signed bytes into an int, each byte needs to be masked off
                    // because it is sign-extended to 32 bits (rather than zero-extended) due to
                    // the arithmetic promotion rule (described in JLS, Conversions and Promotions).
                    // https://stackoverflow.com/questions/7619058/convert-a-byte-array-to-integer-in-java-and-vice-versa
                    final int asInt = bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
                    buster = String.format("%08x", asInt);
                }

                String queryString = "game_id=" + URLEncoder.encode(teakConfiguration.appConfiguration.appId, "UTF-8") +
                                     "&api_key=" + URLEncoder.encode(Session.this.userId, "UTF-8") +
                                     "&sdk_version=" + URLEncoder.encode(teakSdkVersion, "UTF-8") +
                                     "&sdk_platform=" + URLEncoder.encode(teakConfiguration.deviceConfiguration.platformString, "UTF-8") +
                                     "&app_version=" + URLEncoder.encode(String.valueOf(teakConfiguration.appConfiguration.appVersion), "UTF-8") +
                                     "&app_version_name=" + URLEncoder.encode(String.valueOf(teakConfiguration.appConfiguration.appVersionName), "UTF-8") +
                                     (Session.this.countryCode == null ? "" : "&country_code=" + URLEncoder.encode(Session.this.countryCode, "UTF-8")) +
                                     "&buster=" + URLEncoder.encode(buster, "UTF-8");
                URL url = new URL("https://gocarrot.com/ping?" + queryString);
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
        }, 0, heartbeatInterval, TimeUnit.SECONDS);
    }

    private void identifyUser() {
        final TeakConfiguration teakConfiguration = TeakConfiguration.get();

        this.executionQueue.execute(() -> {
            Session.this.stateLock.lock();
            try {
                HashMap<String, Object> payload = new HashMap<>();

                if (Session.this.state != State.UserIdentified && !Session.this.setState(State.IdentifyingUser)) {
                    return;
                }

                if (Session.this.userIdentificationSent) {
                    payload.put("do_not_track_event", Boolean.TRUE);
                }
                Session.this.userIdentificationSent = true;

                if (Session.this.email != null) {
                    payload.put("email", Session.this.email);
                }

                // "true", "false" or "unknown" (if API < 19)
                payload.put("notifications_enabled",
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? String.valueOf(Teak.getNotificationStatus() == Teak.TEAK_NOTIFICATIONS_ENABLED) : "unknown");

                TimeZone tz = TimeZone.getDefault();
                long rawTz = tz.getRawOffset();
                if (tz.inDaylightTime(new Date())) {
                    rawTz += tz.getDSTSavings();
                }
                long minutes = TimeUnit.MINUTES.convert(rawTz, TimeUnit.MILLISECONDS);
                String tzOffset = new DecimalFormat("#0.00").format(minutes / 60.0f);
                payload.put("timezone", tzOffset);
                payload.put("timezone_id", tz.getID());

                final String locale = Locale.getDefault().toString();
                payload.put("locale", locale);

                payload.put("android_limit_ad_tracking", !teakConfiguration.dataCollectionConfiguration.enableIDFA());
                if (teakConfiguration.deviceConfiguration.advertisingId != null &&
                    teakConfiguration.dataCollectionConfiguration.enableIDFA()) {
                    payload.put("android_ad_id", teakConfiguration.deviceConfiguration.advertisingId);
                } else {
                    payload.put("android_ad_id", "");
                }

                // Reporting Facebook Access Token will be removed in SDK 5
                if (!teakConfiguration.appConfiguration.sdk5Behaviors) {
                    if (teakConfiguration.dataCollectionConfiguration.enableFacebookAccessToken()) {
                        if (Session.this.facebookAccessToken == null) {
                            Session.this.facebookAccessToken = Helpers.getCurrentFacebookAccessToken();
                        }

                        if (Session.this.facebookAccessToken != null) {
                            payload.put("access_token", Session.this.facebookAccessToken);
                        }
                    }
                }

                // Report Facebook Id if it was specified
                {
                    if (Session.this.facebookId != null) {
                        payload.put("facebook_id", Session.this.facebookId);
                    }
                }

                // Report additional device information
                {
                    payload.put("device_num_cores", teakConfiguration.deviceConfiguration.numCores);
                    payload.put("device_device_memory_in_bytes", teakConfiguration.deviceConfiguration.memoryInBytes);
                    payload.put("device_display_metrics", teakConfiguration.deviceConfiguration.displayMetrics);
                }

                // Put the AttributionData into the payload, if it exists
                Teak.LaunchData tempLaunchData = null;
                if (Session.this.launchDataSource != null) {
                    try {
                        tempLaunchData = Session.this.launchDataSource.get(5, TimeUnit.SECONDS);
                        if (tempLaunchData != null) {
                            payload.putAll(tempLaunchData.toSessionAttributionMap());
                        }
                    } catch (Exception e) {
                        Teak.log.exception(e);
                    }
                }
                final Teak.LaunchData launchData = tempLaunchData;

                if (teakConfiguration.deviceConfiguration.pushRegistration != null &&
                    teakConfiguration.dataCollectionConfiguration.enablePushKey()) {
                    payload.putAll(teakConfiguration.deviceConfiguration.pushRegistration);
                    payload.putAll(PushState.get().toMap());
                }

                Teak.log.i("session.identify_user", mm.h("userId", Session.this.userId, "timezone", tzOffset, "locale", locale, "session_id", Session.this.sessionId));

                Request.submit("/games/" + teakConfiguration.appConfiguration.appId + "/users.json", payload, Session.this,
                    (responseCode, responseBody) -> {
                        Session.this.stateLock.lock();
                        try {
                            JSONObject response = new JSONObject(responseBody);

                            // TODO: Grab 'id' and 'game_id' from response and store for Parsnip

                            // Enable verbose logging if flagged
                            boolean logLocal = response.optBoolean("verbose_logging");
                            boolean logRemote = response.optBoolean("log_remote");
                            teakConfiguration.debugConfiguration.setLogPreferences(logLocal, logRemote);

                            // Server requesting new push key.
                            if (response.optBoolean("reset_push_key", false)) {
                                teakConfiguration.deviceConfiguration.requestNewPushToken();
                            }

                            // Assign country code from server if it sends it
                            if (!response.isNull("country_code")) {
                                Session.this.countryCode = response.getString("country_code");
                            }

                            // Assign deep link to launch, if it is provided
                            if (!response.isNull("deep_link")) {
                                try {
                                    final String updatedDeepLink = response.getString("deep_link");
                                    final Uri launchLink = payload.containsKey("launch_link") ? Uri.parse((String) payload.get("launch_link")) : null;
                                    Session.this.launchDataSource = LaunchDataSource.sourceWithUpdatedDeepLink(launchData, Uri.parse(updatedDeepLink), launchLink);
                                    Teak.log.i("deep_link.processed", updatedDeepLink);
                                } catch (Exception e) {
                                    Teak.log.exception(e);
                                }
                            }

                            // Grab user profile
                            JSONObject profile = response.optJSONObject("user_profile");
                            if (profile != null) {
                                try {
                                    Session.this.userProfile = new UserProfile(Session.this, profile.toMap());
                                } catch (Exception ignored) {
                                }
                            }

                            // Grab additional data
                            Session.this.additionalData = response.optJSONObject("additional_data");
                            if (Session.this.additionalData != null) {
                                Teak.log.i("additional_data.received", Session.this.additionalData.toString());

                                @SuppressWarnings("deprecation")
                                final Teak.AdditionalDataEvent event = new Teak.AdditionalDataEvent(Session.this.additionalData);
                                whenUserIdIsReadyPost(event);
                            }

                            // Opt out state
                            if (response.has("opt_out_states")) {
                                final JSONObject optOutStates = response.getJSONObject("opt_out_states");
                                Session.this.channelStatusEmail = ChannelStatus.fromJSON(optOutStates.optJSONObject("email"));
                                Session.this.channelStatusPush = ChannelStatus.fromJSON(optOutStates.optJSONObject("push"));
                                Session.this.channelStatusSms = ChannelStatus.fromJSON(optOutStates.optJSONObject("sms"));
                            }

                            // Server-session id is the id of the underlying Redshift launch event
                            if (response.has("session_id")) {
                                Session.this.serverSessionId = response.get("session_id").toString();
                            }

                            // Send user data event
                            Session.this.dispatchUserEvent();

                            // Assign new state
                            // Prevent warning for 'do_not_track_event'
                            if (Session.this.state == State.Expiring) {
                                Session.this.previousState = State.UserIdentified;
                            } else if (Session.this.state != State.UserIdentified) {
                                Session.this.setState(State.UserIdentified);
                            }

                        } catch (Exception e) {
                            Teak.log.exception(e);
                        } finally {
                            Session.this.stateLock.unlock();
                        }
                    });
            } finally {
                Session.this.stateLock.unlock();
            }
        });
    }

    private final TeakEvent.EventListener teakEventListener = event -> {
        switch (event.eventType) {
            case FacebookAccessTokenEvent.Type: {
                String newAccessToken = ((FacebookAccessTokenEvent) event).accessToken;
                if (newAccessToken != null && !newAccessToken.equals(Session.this.facebookAccessToken)) {
                    Session.this.facebookAccessToken = newAccessToken;
                    Teak.log.i("session.fb_access_token", mm.h("access_token", Session.this.facebookAccessToken, "session_id", Session.this.sessionId));
                    userInfoWasUpdated();
                }
            } break;
            case AdvertisingInfoEvent.Type: {
                userInfoWasUpdated();
            } break;
            case PushRegistrationEvent.Registered: {
                userInfoWasUpdated();
            } break;
        }
    };

    private void userInfoWasUpdated() {
        // TODO: Revisit/double-check this logic
        this.executionQueue.execute(() -> {
            Session.currentSessionLock.lock();
            Session.this.stateLock.lock();
            try {
                if (Session.this.state == State.UserIdentified) {
                    identifyUser();
                } else if (Session.this.state == State.IdentifyingUser) {
                    Session.whenUserIdIsReadyRun(session -> identifyUser());
                }
            } finally {
                Session.this.stateLock.unlock();
                Session.currentSessionLock.unlock();
            }
        });
    }

    private void dispatchUserEvent() {
        this.stateLock.lock();
        final TeakConfiguration teakConfiguration = TeakConfiguration.get();
        final Teak.UserDataEvent event = new Teak.UserDataEvent(this.additionalData, this.channelStatusEmail, this.channelStatusPush, this.channelStatusSms, teakConfiguration.deviceConfiguration.pushRegistration, teakConfiguration.deviceConfiguration.deviceId);
        this.stateLock.unlock();

        whenUserIdIsReadyPost(event);
    }

    private synchronized void processAttributionAndDispatchEvents() {
        // If there is no launch attribution, bail.
        if (this.launchDataSource == null || this.launchAttributionProcessed) return;
        this.launchAttributionProcessed = true;

        this.executionQueue.execute(() -> {
            try {
                Teak.waitUntilDeepLinksAreReady();
            } catch (Exception ignored) {
            }

            // If this is no longer the current session, do not continue processing
            if (!Session.this.isCurrentSession()) {
                return;
            }

            try {
                // Resolve attribution Future
                final Teak.LaunchData launchData = Session.this.launchDataSource.get(15, TimeUnit.SECONDS);

                // LaunchData should never be null, but in case it is...
                if (launchData == null) {
                    Session.whenUserIdIsReadyPost(new Teak.PostLaunchSummaryEvent(Teak.LaunchData.Unattributed));
                    return;
                }

                if (launchData instanceof Teak.AttributedLaunchData) {
                    final Teak.AttributedLaunchData attributedLaunchData = (Teak.AttributedLaunchData) launchData;

                    // Process any rewards
                    Session.this.checkLaunchDataForRewardAndPostEvents(attributedLaunchData);

                    // Process any notifications
                    Session.this.checkLaunchDataForNotificationAndPostEvents(attributedLaunchData);
                }

                // Process deep links even if this isn't attributed
                Session.this.checkLaunchDataForDeepLinkAndPostEvents(launchData);

                // Send PostLaunchSummary
                Session.whenUserIdIsReadyPost(new Teak.PostLaunchSummaryEvent(launchData));
            } catch (Exception e) {
                Teak.log.exception(e);
            }
        });
    }

    // This is separate so it can be removed/added independently
    private final TeakEvent.EventListener remoteConfigurationEventListener = event -> {
        if (event.eventType.equals(RemoteConfigurationEvent.Type)) {
            executionQueue.execute(() -> {
                stateLock.lock();
                try {
                    if (state == State.Expiring) {
                        previousState = State.Configured;
                    } else {
                        setState(State.Configured);
                    }
                } finally {
                    stateLock.unlock();
                }
            });
        }
    };

    // Static event listener
    private static final TeakEvent.EventListener staticTeakEventListener = new TeakEvent.EventListener() {
        @Override
        public void onNewEvent(@NonNull TeakEvent event) {
            switch (event.eventType) {
                case LogoutEvent.Type:
                    logout();
                    break;
                case UserIdEvent.Type:
                    final UserIdEvent userIdEvent = (UserIdEvent) event;
                    final String userId = userIdEvent.userId;
                    final String email = userIdEvent.userConfiguration.email;
                    final String facebookId = userIdEvent.userConfiguration.facebookId;
                    setUserId(userId, email, facebookId);
                    break;
                case LifecycleEvent.Paused:
                    // Set state to 'Expiring'
                    currentSessionLock.lock();
                    try {
                        if (currentSession != null) {
                            currentSession.setState(State.Expiring);
                        }
                    } finally {
                        currentSessionLock.unlock();
                    }
                    break;
                case LifecycleEvent.Resumed:
                    LifecycleEvent lEvent = (LifecycleEvent) event;
                    onActivityResumed(lEvent.intent, lEvent.context);
                    break;
            }
        }
    };

    // TODO: I'd love to make this Annotation based
    static void registerStaticEventListeners() {
        TeakEvent.addEventListener(Session.staticTeakEventListener);
    }

    static boolean isExpiringOrExpired() {
        currentSessionLock.lock();
        try {
            return currentSession == null || currentSession.hasExpired() || currentSession.state == State.Expiring;
        } finally {
            currentSessionLock.unlock();
        }
    }

    private static void setUserId(@NonNull String userId, @Nullable String email, @Nullable String facebookId) {
        // If the user id has changed, create a new session
        currentSessionLock.lock();
        try {
            if (currentSession == null) {
                Session.pendingUserId = userId;
                Session.pendingEmail = email;
                Session.pendingFacebookId = facebookId;
            } else {
                if (currentSession.userId != null && !currentSession.userId.equals(userId)) {
                    Session.logout();
                }

                currentSession.stateLock.lock();

                boolean needsIdentifyUser = (currentSession.state == State.Configured);
                if (!Helpers.stringsAreEqual(currentSession.email, email) &&
                    (currentSession.state == State.UserIdentified || currentSession.state == State.IdentifyingUser)) {
                    needsIdentifyUser = true;
                }
                if (!Helpers.stringsAreEqual(currentSession.facebookId, facebookId) &&
                    (currentSession.state == State.UserIdentified || currentSession.state == State.IdentifyingUser)) {
                    needsIdentifyUser = true;
                }

                currentSession.stateLock.unlock();

                currentSession.userId = userId;
                currentSession.email = email;
                currentSession.facebookId = facebookId;

                if (needsIdentifyUser) {
                    currentSession.identifyUser();
                }
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    private static void logout() {
        currentSessionLock.lock();
        try {
            final Session _lockedSession = currentSession;
            _lockedSession.stateLock.lock();
            try {
                // Do *not* copy the launch attribution. Prevent the server from
                // double-counting attributions, and prevent the client from
                // double-processing deep links and rewards.
                Session newSession = new Session();

                currentSession.forceExpire();

                currentSession = newSession;
            } finally {
                _lockedSession.stateLock.unlock();
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    public interface SessionRunnable {
        void run(Session session);
    }

    private static class WhenUserIdIsReadyRun implements Runnable {
        private final SessionRunnable runnable;

        WhenUserIdIsReadyRun(SessionRunnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            currentSessionLock.lock();
            try {
                this.runnable.run(currentSession);
            } finally {
                currentSessionLock.unlock();
            }
        }
    }

    private static final InstrumentableReentrantLock userIdReadyEventBusQueueLock = new InstrumentableReentrantLock();
    private static final ArrayList<Object> userIdReadyEventBusQueue = new ArrayList<>();

    public static void whenUserIdIsReadyPost(@NonNull Object event) {
        currentSessionLock.lock();
        try {
            if (currentSession == null) {
                userIdReadyEventBusQueueLock.lock();
                try {
                    userIdReadyEventBusQueue.add(event);
                } finally {
                    userIdReadyEventBusQueueLock.unlock();
                }
            } else {
                final Session _lockedSession = currentSession;
                _lockedSession.stateLock.lock();
                try {
                    if (currentSession.state == State.UserIdentified) {
                        // Run EventBus on the main thread
                        new Handler(Looper.getMainLooper()).post(() -> {
                            EventBus.getDefault().post(event);
                        });
                    } else {
                        userIdReadyEventBusQueueLock.lock();
                        try {
                            userIdReadyEventBusQueue.add(event);
                        } finally {
                            userIdReadyEventBusQueueLock.unlock();
                        }
                    }
                } finally {
                    _lockedSession.stateLock.unlock();
                }
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    private static final InstrumentableReentrantLock userIdReadyRunnableQueueLock = new InstrumentableReentrantLock();
    private static final ArrayList<WhenUserIdIsReadyRun> userIdReadyRunnableQueue = new ArrayList<>();

    public static void whenUserIdIsReadyRun(@NonNull SessionRunnable runnable) {
        currentSessionLock.lock();
        try {
            if (currentSession == null) {
                userIdReadyRunnableQueueLock.lock();
                try {
                    userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                } finally {
                    userIdReadyRunnableQueueLock.unlock();
                }
            } else {
                final Session _lockedSession = currentSession;
                _lockedSession.stateLock.lock();
                try {
                    if (currentSession.state == State.UserIdentified) {
                        currentSession.executionQueue.execute(new WhenUserIdIsReadyRun(runnable));
                    } else {
                        userIdReadyRunnableQueueLock.lock();
                        try {
                            userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                        } finally {
                            userIdReadyRunnableQueueLock.unlock();
                        }
                    }
                } finally {
                    _lockedSession.stateLock.unlock();
                }
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    public static void whenUserIdIsOrWasReadyRun(@NonNull SessionRunnable runnable) {
        currentSessionLock.lock();
        try {
            if (currentSession == null) {
                userIdReadyRunnableQueueLock.lock();
                try {
                    userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                } finally {
                    userIdReadyRunnableQueueLock.unlock();
                }
            } else {
                final Session _lockedSession = currentSession;
                _lockedSession.stateLock.lock();
                try {
                    if (currentSession.state == State.UserIdentified ||
                        (currentSession.state == State.Expiring && currentSession.previousState == State.UserIdentified)) {
                        currentSession.executionQueue.execute(new WhenUserIdIsReadyRun(runnable));
                    } else {
                        userIdReadyRunnableQueueLock.lock();
                        try {
                            userIdReadyRunnableQueue.add(new WhenUserIdIsReadyRun(runnable));
                        } finally {
                            userIdReadyRunnableQueueLock.unlock();
                        }
                    }
                } finally {
                    _lockedSession.stateLock.unlock();
                }
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    private void forceExpire() {
        stateLock.lock();
        try {
            if (state != State.Expired) {
                setState(State.Expiring);
                setState(State.Expired);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private static void resetCurrentSessionToPreviousState() {
        // It's safe to potentially create a new session here, since otherwise
        // all we would do is reset the state on the session.
        getCurrentSession();
        // Reset state on current session, if it is expiring
        final Session _lockedSession = currentSession;
        _lockedSession.stateLock.lock();
        try {
            if (currentSession.state == State.Expiring) {
                currentSession.setState(currentSession.previousState);
            }
        } finally {
            _lockedSession.stateLock.unlock();
        }
    }

    private static void onActivityResumed(final Intent intent, final Context context) {
        // Call getCurrentSession() so the null || Expired logic stays in one place
        currentSessionLock.lock();
        try {
            // If this intent has already been processed by Teak, just reset the state and we are done.
            // Otherwise, out-of-app deep links can cause a back-stack loop
            if (intent.getBooleanExtra("teakSessionProcessed", false)) {
                resetCurrentSessionToPreviousState();
                return;
            }
            intent.putExtra("teakSessionProcessed", true);

            final LaunchDataSource launchDataSource = LaunchDataSource.sourceFromIntent(intent, context);

            // We explicitly avoid calling getCurrentSession() in this path.
            // getCurrentSession() will create a new session if the previous session has expired. That
            // new session will get through to identifying the user while at the same time we may be
            // creating our own new session due to the presence of an attributed launch data source.
            // If the current session has a launch different attribution, it's a new session.
            if (currentSession != null && (currentSession.state == State.Allocated || currentSession.state == State.Created)) {
                currentSession.launchDataSource = launchDataSource;
            } else if (launchDataSource != LaunchDataSource.Unattributed) {
                Session oldSession = currentSession;
                currentSession = new Session(oldSession, launchDataSource);
                if (oldSession != null) {
                    oldSession.forceExpire();
                }
            } else {
                // Reset state on current session, if it is expiring
                resetCurrentSessionToPreviousState();
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    private void checkLaunchDataForNotificationAndPostEvents(final Teak.AttributedLaunchData attributedLaunchData) {
        if (attributedLaunchData instanceof Teak.NotificationLaunchData) {
            final Teak.NotificationLaunchData notificationLaunchData = (Teak.NotificationLaunchData) attributedLaunchData;
            Session.whenUserIdIsReadyPost(new Teak.NotificationEvent(notificationLaunchData, false));
        }
    }

    private void checkLaunchDataForDeepLinkAndPostEvents(final Teak.LaunchData launchData) {
        try {
            final TeakConfiguration teakConfiguration = TeakConfiguration.get();
            if (launchData instanceof Teak.AttributedLaunchData) {
                // This is an attributed launch, it came from a Teak source
                final Teak.AttributedLaunchData attributedLaunchData = (Teak.AttributedLaunchData) launchData;

                // If this was a RewardLink send the appropriate event
                if (attributedLaunchData instanceof Teak.RewardlinkLaunchData) {
                    Session.whenUserIdIsReadyPost(new Teak.LaunchFromLinkEvent((Teak.RewardlinkLaunchData) attributedLaunchData));
                }

                // If TeakLinks will not handle the deep link, then it's an external deep link and
                // so we should launch an intent with it
                if (attributedLaunchData.deepLink != null && !DeepLink.willProcessUri(attributedLaunchData.deepLink)) {
                    // In API 30+ using 'queryIntentActivities' requires a list of queried schemes
                    // in AndroidManifest.xml. Instead, add 'teakSessionProcessed' and the existing
                    // code in Session.onActivityResumed will check for that flag, and not cause
                    // an infinite loop.
                    final Intent uriIntent = new Intent(Intent.ACTION_VIEW, attributedLaunchData.deepLink);
                    uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    uriIntent.putExtra("teakSessionProcessed", true);
                    try {
                        teakConfiguration.appConfiguration.applicationContext.startActivity(uriIntent);
                    } catch (android.content.ActivityNotFoundException e) {
                        // Ignored.
                    }
                } else {
                    // Otherwise, handle the deep link
                    DeepLink.processUri(attributedLaunchData.deepLink);
                }
            } else {
                // If this is not an attributed launch, then we should check to see if TeakLinks can
                // do anything with the **launchLink**, because a customer may still want to use the
                // TeakLink system outside of the context of a Teak Reward Link, and that should still
                // function properly.
                DeepLink.processUri(launchData.launchLink);
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    private void checkLaunchDataForRewardAndPostEvents(final Teak.AttributedLaunchData attributedLaunchData) {
        try {
            if (attributedLaunchData.rewardId != null) {
                // The click POST and per-status event dispatch live inside Reward; this is
                // the SDK-driven (not game-driven) entry point that supplies the launch
                // attribution. The Future return value is ignored on this path — events
                // are the contract with host games.
                TeakNotification.Reward.fireClickFromLaunchData(attributedLaunchData.rewardId,
                    attributedLaunchData);
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }
    }

    // region Accessors
    public String userId() {
        return userId;
    }
    // endregion

    // region Current Session
    private static Session currentSession;
    private static final InstrumentableReentrantLock currentSessionLock = new InstrumentableReentrantLock();

    private static void getCurrentSession() {
        currentSessionLock.lock();
        try {
            if (currentSession == null || currentSession.hasExpired()) {
                Session oldSession = currentSession;
                currentSession = new Session();

                if (oldSession != null) {
                    // If the old session had a user id assigned, it needs to be passed to the newly created
                    // session. When setState(State.Configured) happens, it will call identifyUser()
                    if (oldSession.userId != null) {
                        setUserId(oldSession.userId, oldSession.email, oldSession.facebookId);
                    }
                } else if (Session.pendingUserId != null) {
                    setUserId(Session.pendingUserId, Session.pendingEmail, Session.pendingFacebookId);
                    Session.pendingUserId = null;
                    Session.pendingEmail = null;
                    Session.pendingFacebookId = null;
                }
            }
        } finally {
            currentSessionLock.unlock();
        }
    }

    static Session getCurrentSessionOrNull() {
        currentSessionLock.lock();
        try {
            return currentSession;
        } finally {
            currentSessionLock.unlock();
        }
    }
    // endregion

    private Map<String, Object> toMap() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("startDate", this.startTimeMillis / 1000);
        map.put("endDate", this.endTimeMillis / 1000);
        return map;
    }

    @Override
    @NonNull
    public String toString() {
        try {
            return String.format(Locale.US, "%s: %s", super.toString(), Teak.formatJSONForLogging(new JSONObject(this.toMap())));
        } catch (Exception ignored) {
            return super.toString();
        }
    }
}
