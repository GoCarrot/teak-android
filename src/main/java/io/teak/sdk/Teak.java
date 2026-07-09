package io.teak.sdk;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.StrictMode;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.ChannelStatus;
import io.teak.sdk.core.Executors;
import io.teak.sdk.core.InstrumentableReentrantLock;
import io.teak.sdk.core.TeakCore;
import io.teak.sdk.event.DeepLinksReadyEvent;
import io.teak.sdk.event.PushNotificationEvent;
import io.teak.sdk.io.AndroidResources;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONException;
import io.teak.sdk.json.JSONObject;

/**
 * Teak
 */
public class Teak extends BroadcastReceiver implements Unobfuscable {
    static final String LOG_TAG = "Teak";

    /**
     * The default id Teak uses for {@link android.app.job.JobInfo}.
     */
    public static final int JOB_ID = 1946157056;

    /**
     * The push notifications permission identifier.
     */
    public static final String NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS";

    /**
     * Version of the Teak SDK.
     *
     * @deprecated Use the {@link Teak#Version} member instead.
     */
    @Deprecated
    public static final String SDKVersion = io.teak.sdk.BuildConfig.VERSION_NAME;

    /**
     * Version of the Teak SDK, and Unity/Cocos2dx SDK if applicable.
     * <p>
     * You must call {@link Teak#onCreate(Activity)} in order to get Unity/Cocos2dx SDK version info.
     */
    public static final Map<String, Object> Version;

    /**
     * Version of the Teak SDK, as an array [major, minor, revision]
     */
    public static final int[] MajorMinorRevision;

    private static final Map<String, Object> sdkMap = new HashMap<>();

    static {
        sdkMap.put("android", io.teak.sdk.BuildConfig.VERSION_NAME);
        Version = Collections.unmodifiableMap(sdkMap);

        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+).*").matcher(io.teak.sdk.BuildConfig.VERSION_NAME);
        if (m.matches()) {
            MajorMinorRevision = new int[] {
                Integer.parseInt(m.group(1)), // major
                Integer.parseInt(m.group(2)), // minor
                Integer.parseInt(m.group(3))  // revision
            };
        } else {
            MajorMinorRevision = new int[] {0, 0, 0};
        }
    }

    /**
     * Force debug print on/off.
     */
    public static boolean forceDebug;

    /**
     * Is Teak enabled?
     *
     * @return True if Teak is enabled; False if Teak has not been initialized, or is disabled.
     */
    public static boolean isEnabled() {
        return Instance != null && Instance.isEnabled();
    }

    /**
     * Initialize Teak and tell it to listen to the lifecycle events of {@link Activity}.
     *
     * Call this function from the {@link Activity#onCreate} function of your <code>Activity</code>
     * <b>before</b> the call to <code>super.onCreate()</code>
     *
     * @param activity The main <code>Activity</code> of your app.
     */
    @SuppressWarnings("unused")
    public static void onCreate(@NonNull Activity activity) {
        onCreate(activity, null);
    }

    /// @cond hide_from_doxygen
    /**
     * Used for internal testing.
     *
     * @param activity      The main <code>Activity</code> of your app.
     * @param objectFactory Teak Object Factory to use, or null for default.
     */
    public static void onCreate(@NonNull Activity activity, @Nullable IObjectFactory objectFactory) {
        // Provide a way to wait for debugger to connect via a data param
        final Intent intent = activity.getIntent();

        try {
            if (intent != null && intent.getData() != null) {
                final Uri intentData = intent.getData();
                if (intentData.isHierarchical()) {
                    if (intentData.getBooleanQueryParameter("teak_log", false)) {
                        Teak.forceDebug = true;
                    }

                    if (intentData.getBooleanQueryParameter("teak_debug", false) &&
                        (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        Debug.waitForDebugger();
                    }

                    if (intentData.getBooleanQueryParameter("teak_mutex_report", false)) {
                        InstrumentableReentrantLock.interruptLongLocksAndReport = true;
                    }

                    if (intentData.getBooleanQueryParameter("teak_strict_mode", false)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                                    .detectNonSdkApiUsage()
                                    .penaltyLog()
                                    .build());
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, android.util.Log.getStackTraceString(e));
        }

        // Init integration checks, we decide later if they report or not
        if (!IntegrationChecker.init(activity)) {
            throw new RuntimeException("Teak integration check failed. Please see the log for details.");
        }

        // Unless something gave us an object factory, use the default one
        if (objectFactory == null) {
            try {
                objectFactory = new DefaultObjectFactory(activity.getApplicationContext());
            } catch (Exception e) {
                android.util.Log.e(LOG_TAG, android.util.Log.getStackTraceString(e));
                return;
            }
        }

        // Add version info for Unity
        try {
            Class<?> clazz = Class.forName("io.teak.sdk.wrapper.Version");
            Method m = clazz.getDeclaredMethod("map");
            @SuppressWarnings("unchecked")
            final Map<String, Object> wrapperVersion = (Map<String, Object>) m.invoke(null);
            if (wrapperVersion != null) {
                Teak.sdkMap.putAll(wrapperVersion);
            }
        } catch (Exception ignored) {
        }

        final AndroidResources androidResources = new AndroidResources(activity.getApplicationContext(), objectFactory.getAndroidResources());
        if (androidResources != null) {
            // Check for 'trace' log mode
            final Boolean traceLog = androidResources.getTeakBoolResource(AppConfiguration.TEAK_TRACE_LOG_RESOURCE, false);
            if (traceLog != null) {
                Teak.log.setLogTrace(traceLog);
            }
        }

        // Create Instance last
        if (Instance == null) {
            try {
                Instance = new TeakInstance(activity, objectFactory);
            } catch (Exception e) {
                android.util.Log.e(LOG_TAG, android.util.Log.getStackTraceString(e));
            }
        } else {
            Instance.setMainActivity(activity);
        }
    }
    /// @endcond

    /**
     * Tell Teak how it should identify the current user.
     *
     * This will also begin tracking and reporting of a session, and track a daily active user.
     *
     * @note This should be the same way you identify the user in your backend.
     * @deprecated Use {@link Teak#identifyUser(String, UserConfiguration)} instead.
     *
     * @param userIdentifier An identifier which is unique for the current user.
     */
    @SuppressWarnings("unused")
    @Deprecated
    public static void identifyUser(final String userIdentifier) {
        Teak.identifyUser(userIdentifier, new String[0], null);
    }

    /**
     * Tell Teak how it should identify the current user.
     *
     * This will also begin tracking and reporting of a session, and track a daily active user.
     *
     * @note This should be the same way you identify the user in your backend.
     * @deprecated Use {@link Teak#identifyUser(String, UserConfiguration)} instead.
     *
     * @param userIdentifier An identifier which is unique for the current user.
     * @param email          The email address for the user.
     */
    @SuppressWarnings("unused")
    @Deprecated
    public static void identifyUser(final String userIdentifier, final String email) {
        Teak.identifyUser(userIdentifier, new String[0], email);
    }

    /**
     * Value provided to {@link #identifyUser(String, String[])} to opt out of
     * collecting an IDFA for this specific user.
     *
     * If you prevent Teak from collecting the Identifier For Advertisers (IDFA), Teak will no longer be able to add this user to Facebook Ad Audiences.
     */
    @SuppressWarnings("unused")
    public static final String OPT_OUT_IDFA = "opt_out_idfa";

    /**
     * Value provided to {@link #identifyUser(String, String[])} to opt out of
     * collecting a Facebook Access Token for this specific user.
     *
     * If you prevent Teak from collecting the Facebook Access Token, Teak will no longer be able to correlate this user across multiple devices.
     */
    @SuppressWarnings("unused")
    public static final String OPT_OUT_FACEBOOK = "opt_out_facebook";

    /**
     * Value provided to {@link #identifyUser(String, String[])} to opt out of
     * collecting a Push Key for this specific user.
     *
     * If you prevent Teak from collecting the Push Key, Teak will no longer be able to send Local Notifications or Push Notifications for this user.
     */
    @SuppressWarnings("unused")
    public static final String OPT_OUT_PUSH_KEY = "opt_out_push_key";

    /**
     * Tell Teak how it should identify the current user, with data collection opt-out.
     *
     * This will also begin tracking and reporting of a session, and track a daily active user.
     *
     * @note This should be the same way you identify the user in your backend.
     * @deprecated Use {@link Teak#identifyUser(String, UserConfiguration)} instead.
     *
     * @param userIdentifier An identifier which is unique for the current user.
     * @param optOut         A list containing zero or more of:
     *                          {@link #OPT_OUT_IDFA}, {@link #OPT_OUT_FACEBOOK}, {@link #OPT_OUT_PUSH_KEY}
     */
    @SuppressWarnings("unused")
    @Deprecated
    public static void identifyUser(final String userIdentifier, final String[] optOut) {
        identifyUser(userIdentifier, optOut, null);
    }

    /**
     * Tell Teak how it should identify the current user, with data collection opt-out and email.
     *
     * This will also begin tracking and reporting of a session, and track a daily active user.
     *
     * @note This should be the same way you identify the user in your backend.
     * @deprecated Use {@link Teak#identifyUser(String, UserConfiguration)} instead.
     *
     * @param userIdentifier An identifier which is unique for the current user.
     * @param optOut         A list containing zero or more of:
     *                          {@link #OPT_OUT_IDFA}, {@link #OPT_OUT_FACEBOOK}, {@link #OPT_OUT_PUSH_KEY}
     * @param email          The email address for the user.
     */
    @SuppressWarnings("unused")
    @Deprecated
    public static void identifyUser(final String userIdentifier, final String[] optOut, final String email) {
        final Set<String> optOutSet = optOut == null ? new HashSet<>() : new HashSet<>(Arrays.asList(optOut));
        final UserConfiguration userConfiguration = new UserConfiguration(email, null,
            optOutSet.contains(OPT_OUT_FACEBOOK),
            optOutSet.contains(OPT_OUT_IDFA),
            optOutSet.contains(OPT_OUT_PUSH_KEY));

        identifyUser(userIdentifier, userConfiguration);
    }

    public static class UserConfiguration implements Unobfuscable {
        /**
         * Email address
         */
        public final String email;

        /**
         * Facebook Id
         */
        public final String facebookId;

        /**
         * Opt out of collecting a Facebook Access Token for this specific user.
         *
         * @note If you prevent Teak from collecting the Facebook Access Token, Teak will no longer be able to correlate this user across multiple devices.
         *
         * @deprecated Instead do not specify a Facebook Id for the user.
         */
        @Deprecated
        public final boolean optOutFacebook;

        /**
         * Opt out of collecting an IDFA for this specific user.
         *
         * @note If you prevent Teak from collecting the Identifier For Advertisers (IDFA), Teak will no longer be able to add this user to Facebook Ad Audiences.
         */
        public final boolean optOutIDFA;

        /**
         * Opt out of collecting a Push Key for this specific user.
         *
         * @note If you prevent Teak from collecting the Push Key, Teak will no longer be able to send Local Notifications or Push Notifications for this user.
         */
        public final boolean optOutPushKey;

        /**
         * UserConfiguration with no email, no facebook id, and no opt outs
         */
        public UserConfiguration() {
            this(null, null, false, false, false);
        }

        /**
         * UserConfiguration specifying email
         *
         * @param email Email address for the user.
         */
        public UserConfiguration(final String email) {
            this(email, null, false, false, false);
        }

        /**
         * UserConfiguration specifying email and Facebook Id.
         *
         * @param email      Email address for the user.
         * @param facebookId Facebook Id for the user.
         */
        public UserConfiguration(final String email, final String facebookId) {
            this(email, facebookId, false, false, false);
        }

        /**
         * UserConfiguration
         *
         * @param email          Email address for the user.
         * @param facebookId     Facebook Id for the user.
         * @param optOutfacebook <code>true</code> if the user should be opted out of Facebook Id Collection
         * @param optOutIDFA     <code>true</code> if the user should be opted out of IDFA collection.
         * @param optOutPushKey  <code>true</code> if the user should be opted out of push key collection.
         */
        public UserConfiguration(final String email, final String facebookId,
            final boolean optOutFacebook, final boolean optOutIDFA,
            final boolean optOutPushKey) {
            this.email = email;
            this.facebookId = facebookId;
            this.optOutFacebook = optOutFacebook;
            this.optOutIDFA = optOutIDFA;
            this.optOutPushKey = optOutPushKey;
        }

        /// @cond hide_from_doxygen
        public Map<String, Object> toHash() {
            final Map<String, Object> map = new HashMap<>();
            map.put("email", this.email);
            map.put("facebook_id", this.facebookId);
            map.put("opt_out_facebook", this.optOutFacebook);
            map.put("opt_out_idfa", this.optOutIDFA);
            map.put("opt_out_push_key", this.optOutPushKey);
            return map;
        }
        /// @endcond
    }

    /**
     * Tell Teak how it should identify the current user, with additional options and configuration.
     *
     * This will also begin tracking and reporting of a session, and track a daily active user.
     *
     * @note This should be the same way you identify the user in your backend.
     *
     * @param userIdentifier An identifier which is unique for the current user.
     * @param userConfiguration A set of configuration keys and value.
     */
    @SuppressWarnings("unused")
    public static void identifyUser(final String userIdentifier, final UserConfiguration userConfiguration) {
        Teak.log.trace("Teak.identifyUser", "userIdentifier", userIdentifier, "userConfiguration", userConfiguration);

        // Always process deep links when identifyUser is called
        Teak.processDeepLinks();

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.identifyUser(userIdentifier, userConfiguration));
        }
    }

    public static void requestNotificationPermissions() {
        Teak.log.i("Teak.requestNotificationPermissions", "Hello");

        if(Instance != null) {
            asyncExecutor.submit(() -> Instance.requestNotificationPermissions());
        }
    }

    /**
     * Logout the current user.
     */
    @SuppressWarnings("unused")
    public static void logout() {
        Teak.log.trace("Teak.logout");

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.logout());
        }
    }

    /**
     * Delete any email address associated with the current user.
     */
    @SuppressWarnings("unused")
    public static void deleteEmail() {
        Teak.log.trace("Teak.deleteEmail");

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.deleteEmail());
        }
    }

    /**
     * Teak Marketing Channel.
     */
    public static class Channel implements Unobfuscable {

        /**
         * An individual category.
         */
        public static class Category implements Unobfuscable {
            public final String id;
            public final String name;
            public final String description;
            public final String sound;
            public final boolean showBadge;

            public Category(final String id, final String name, final String description, final String sound, final boolean showBadge) {
                this.id = id;
                this.name = name;
                this.description = description;
                this.sound = sound;
                this.showBadge = showBadge;
            }

            public JSONObject toJSON() {
                final JSONObject json = new JSONObject();
                json.put("id", this.id);
                json.put("name", this.name);
                json.put("description", this.description);
                json.put("sound", this.sound);
                json.put("showBadge", Helpers.stringForBool(this.showBadge));
                return json;
            }
        }

        /**
         * Marketing channel type
         */
        public enum Type implements Unobfuscable {
            MobilePush("push"),            ///< Push notification channel for mobile devices
            DesktopPush("desktop_push"),   ///< Push notification channel for desktop devices
            PlatformPush("platform_push"), ///< Push notification channel for the current platform
            Email("email"),                ///< Email channel
            SMS("sms"),                    ///< SMS channel
            Invalid("invalid");            ///< Invalid channel, will be ignored if used

            // public static final Integer length = 1 + Invalid.ordinal();

            public final String name;

            Type(String name) {
                this.name = name;
            }

            /// @cond hide_from_doxygen
            public static Type fromString(final String string) {
                if (MobilePush.name.equalsIgnoreCase(string)) return MobilePush;
                if (DesktopPush.name.equalsIgnoreCase(string)) return DesktopPush;
                if (PlatformPush.name.equalsIgnoreCase(string)) return PlatformPush;
                if (Email.name.equalsIgnoreCase(string)) return Email;
                if (SMS.name.equalsIgnoreCase(string)) return SMS;

                return Invalid;
            }
            /// @endcond
        }

        /**
         * State of marketing channel
         */
        public enum State implements Unobfuscable {
            OptOut("opt_out"),
            Available("available"),
            OptIn("opt_in"),
            Absent("absent"),
            Unknown("unknown");

            // public static final Integer length = 1 + Absent.ordinal();

            public final String name;

            State(String name) {
                this.name = name;
            }

            public static State fromString(final String string) {
                if (OptOut.name.equalsIgnoreCase(string)) return OptOut;
                if (Available.name.equalsIgnoreCase(string)) return Available;
                if (OptIn.name.equalsIgnoreCase(string)) return OptIn;
                if (Absent.name.equalsIgnoreCase(string)) return Absent;

                return Unknown;
            }
        }

        /**
         * Get a list of the notification channel categories as a JSON string.
         * @return A Json array of notification categories. This will be null if RemoteConfiguration has not yet happened.
         */
        @SuppressWarnings("unused")
        public static String getCategoriesJson() {
            final List<Channel.Category> categories = getCategories();
            if (categories == null) {
                return null;
            }

            final JSONArray json = new JSONArray();
            for (Channel.Category category : categories) {
                json.put(category.toJSON());
            }
            return json.toString();
        }

        /**
         * Get a list of the notification channel categories.
         * @return A Json array of notification categories. This will be null if RemoteConfiguration has not yet happened.
         */
        @SuppressWarnings("unused")
        public static List<Channel.Category> getCategories() {
            try {
                return TeakConfiguration.get().remoteConfiguration.categories;
            } catch (Exception ignored) {
            }
            return null;
        }

        /**
         * Class used for {@link Teak#setChannelState(String, String)} and {@link Teak#setCategoryState(String, String, String)} replies.
         */
        public static class Reply implements Unobfuscable {
            public final boolean error;
            public final State state;
            public final Type channel;
            public final String category;
            public final Map<String, String[]> errors;

            public Reply(boolean error, State state, Type channel, Map<String, String[]> errors) {
                this(error, state, channel, null, errors);
            }

            public Reply(boolean error, State state, Type channel, String category, Map<String, String[]> errors) {
                this.error = error;
                this.state = state;
                this.channel = channel;
                this.category = category;
                this.errors = errors;
            }

            public JSONObject toJSON() {
                final JSONObject json = new JSONObject();
                json.put("state", this.state.name);
                json.put("channel", this.channel.name);
                json.put("category", this.category);
                json.put("error", Helpers.stringForBool(this.error));
                json.put("errors", this.errors);
                return json;
            }

            public static final Reply NoInstance = new Reply(true, State.Unknown, Type.Invalid, Collections.singletonMap("sdk", new String[] {"Instance not ready"}));
        }
    }

    /**
     * Set the state of a Teak Marketing Channel.
     *
     * @note You may only assign the values {@link Teak.Channel.State#OptOut} and {@link Teak.Channel.State#Available} to Push Channels; {@link Channel.State#OptIn} is not allowed.
     *
     * @param channel The channel being modified.
     * @param state   The state for the channel.
     */
    @SuppressWarnings("unused")
    public static Future<Channel.Reply> setChannelState(final Channel.Type channel, final Channel.State state) {
        Teak.log.trace("Teak.setChannelState", "channel", channel.name, "state", state.name);

        if (Instance != null) {
            // If "PlatformPush" is requested, this is Android; so it's MobilePush
            return Instance.setChannelState(channel == Channel.Type.PlatformPush ? Channel.Type.MobilePush : channel, state);
        }

        return Helpers.futureForValue(Channel.Reply.NoInstance);
    }

    /// @cond hide_from_doxygen
    @SuppressWarnings("unused")
    public static Future<Channel.Reply> setChannelState(final String channelName, final String stateName) {
        return setChannelState(Channel.Type.fromString(channelName), Channel.State.fromString(stateName));
    }
    /// @endcond

    /**
     * Set the state of a Teak Marketing Channel Category
     *
     * @note You may only assign the values {@link Channel.State#OptOut} and {@link Channel.State#Available}.
     *
     * @param channel The channel being modified.
     * @param category    The category being modified.
     * @param state   The state for the channel.
     */
    public static Future<Channel.Reply> setCategoryState(final Channel.Type channel, final String category, final Channel.State state) {
        Teak.log.trace("Teak.setCategoryState", "channel", channel.name, "category", category, "state", state.name);

        if (Instance != null) {
            // If "PlatformPush" is requested, this is Android; so it's MobilePush
            return Instance.setCategoryState(channel == Channel.Type.PlatformPush ? Channel.Type.MobilePush : channel, category, state);
        }

        return Helpers.futureForValue(Channel.Reply.NoInstance);
    }

    /// @cond hide_from_doxygen
    @SuppressWarnings("unused")
    public static Future<Channel.Reply> setCategoryState(final String channelName, final String category, final String stateName) {
        return setCategoryState(Channel.Type.fromString(channelName), category, Channel.State.fromString(stateName));
    }
    /// @endcond

    /**
     * Methods for scheduling and canceling Teak notifications.
     */
    public static class Notification implements Unobfuscable {
        /**
         * Class used to communicate replies to:
         * - {@link Notification#schedule(String, long, Map)} ()}
         */
        public static class Reply implements Unobfuscable {
            public enum Status {
                Ok("ok"),
                Error("error"),
                UnconfiguredKey("unconfigured_key"),
                InvalidDevice("invalid_device"),
                Unknown("unknown");

                public final String name;

                Status(String name) {
                    this.name = name;
                }

                public static Status fromString(final String string) {
                    if (Ok.name.equalsIgnoreCase(string)) return Ok;
                    if (Error.name.equalsIgnoreCase(string)) return Error;
                    if (UnconfiguredKey.name.equalsIgnoreCase(string)) return UnconfiguredKey;
                    if (InvalidDevice.name.equalsIgnoreCase(string)) return InvalidDevice;

                    return Unknown;
                }
            }

            public final boolean error;
            public final Status status;
            public final Map<String, String[]> errors;
            public final List<String> scheduleIds;

            public Reply(boolean error, Status status, Map<String, String[]> errors, List<String> scheduleIds) {
                this.error = error;
                this.status = status;
                this.errors = errors;
                this.scheduleIds = scheduleIds;
            }

            public JSONObject toJSON() {
                final JSONObject json = new JSONObject();
                json.put("status", this.status.name);
                json.put("error", Helpers.stringForBool(this.error));
                json.put("errors", this.errors);
                json.put("schedule_ids", this.scheduleIds);
                return json;
            }

            public static final Reply NoInstance = new Reply(true, Status.Error, Collections.singletonMap("sdk", new String[] {"Instance not ready"}), null);
        }

        /**
         * Schedule a notification to be sent to the current user in the future
         * @param creativeId     The identifier of the notification creative on the Teak dashboard.
         * @param delayInSeconds The delay, in seconds, before sending the notification.
         * @return A {@link Future} for the {@link Reply} to this operation.
         */
        @SuppressWarnings("unused")
        public static Future<Reply> schedule(final String creativeId, final long delayInSeconds) {
            return schedule(creativeId, delayInSeconds, (Map<String, Object>) null);
        }

        /// @cond hide_from_doxygen
        @SuppressWarnings("unused")
        public static Future<Reply> schedule(final String creativeId, final long delayInSeconds, final String userInfoJson) {
            return schedule(creativeId, delayInSeconds, userInfoJson == null ? null : new JSONObject(userInfoJson).toMap());
        }
        /// @endcond

        /**
         * Schedule a notification to be sent to the current user in the future
         * @param creativeId            The identifier of the notification creative on the Teak dashboard.
         * @param delayInSeconds        The delay, in seconds, before sending the notification.
         * @param personalizationData   A dictionary containing parameters that the server can use for templating.
         * @return A {@link Future} for the {@link Reply} to this operation.
         */
        @SuppressWarnings("unused")
        public static Future<Reply> schedule(final String creativeId, final long delayInSeconds, final Map<String, Object> personalizationData) {
            Teak.log.trace("Teak.Notification.schedule", "creativeId", creativeId, "delayInSeconds", delayInSeconds);

            if (creativeId == null || creativeId.isEmpty()) {
                Teak.log.e("notification.schedule.error", "creativeId cannot be null or empty");
                return Helpers.futureForValue(new Reply(true, Reply.Status.Error, Collections.singletonMap("creative_id", new String[] {"creativeId cannot be null or empty"}), null));
            }

            if (delayInSeconds > 2630000 /* one month in seconds */ || delayInSeconds < 0) {
                Teak.log.e("notification.schedule.error", "delayInSeconds can not be negative, or greater than one month");
                return Helpers.futureForValue(new Reply(true, Reply.Status.Error, Collections.singletonMap("delay_in_seconds", new String[] {"delayInSeconds can not be negative, or greater than one month"}), null));
            }

            if (Instance != null) {
                return Instance.scheduleNotification(creativeId, delayInSeconds, personalizationData);
            }

            return Helpers.futureForValue(Reply.NoInstance);
        }
    }

    /**
     * Track an arbitrary event in Teak.
     *
     * @param actionId         The identifier for the action, e.g. 'complete'.
     * @param objectTypeId     The type of object that is being posted, e.g. 'quest'.
     * @param objectInstanceId The specific instance of the object, e.g. 'gather-quest-1'
     */
    @SuppressWarnings("unused")
    public static void trackEvent(final String actionId, final String objectTypeId, final String objectInstanceId) {
        Teak.log.trace("Teak.trackEvent", "actionId", actionId, "objectTypeId", objectTypeId, "objectInstanceId", objectInstanceId);

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.trackEvent(actionId, objectTypeId, objectInstanceId));
        }
    }

    /**
     * Increment the value an arbitrary event in Teak.
     *
     * @param actionId         The identifier for the action, e.g. 'complete'.
     * @param objectTypeId     The type of object that is being posted, e.g. 'quest'.
     * @param objectInstanceId The specific instance of the object, e.g. 'gather-quest-1'
     * @param count            The amount by which to increment.
     */
    @SuppressWarnings("unused")
    public static void incrementEvent(final String actionId, final String objectTypeId, final String objectInstanceId, final long count) {
        Teak.log.trace("Teak.incrementEvent", "actionId", actionId, "objectTypeId", objectTypeId, "objectInstanceId", objectInstanceId);

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.trackEvent(actionId, objectTypeId, objectInstanceId, count));
        }
    }

    /**
     * Notifications are enabled.
     */
    public static final int TEAK_NOTIFICATIONS_ENABLED = 0;

    /**
     * Notifications are disabled.
     */
    public static final int TEAK_NOTIFICATIONS_DISABLED = 1;

    /**
     * Notification status is not known. The device could be below API 19, or another issue.
     */
    public static final int TEAK_NOTIFICATIONS_UNKNOWN = -1;

    /**
     * Has the user disabled notifications for this app.
     *
     * @note This will always return <code>false</code> for any device below API 19.
     *
     * @return <code>true</code> if the device is above API 19 and the user has disabled notifications, <code>false</code> otherwise.
     */
    @SuppressWarnings("unused")
    public static int getNotificationStatus() {
        Teak.log.trace("Teak.getNotificationStatus");

        if (Instance == null) {
            Teak.log.e("error.getNotificationStatus", "getNotificationStatus() should not be called before onCreate()");
            return TEAK_NOTIFICATIONS_UNKNOWN;
        }
        return Instance.getNotificationStatus();
    }

    /**
     * Determine if Teak can open the settings app to the settings for this app.
     *
     * @return <code>true</code> if Teak will be able to open the settings, <code>false</code> otherwise.
     */
    @SuppressWarnings("unused")
    public static boolean canOpenSettingsAppToThisAppsSettings() {
        Teak.log.trace("Teak.canOpenSettingsAppToThisAppsSettings");

        if (Instance == null) {
            Teak.log.e("error.canOpenSettingsAppToThisAppsSettings", "canOpenSettingsAppToThisAppsSettings() should not be called before onCreate()");
            return false;
        } else {
            return Instance.canOpenSettingsAppToThisAppsSettings();
        }
    }

    /**
     * Open the settings app to the settings for this app.
     *
     * @note This will always return <code>false</code> for any device below API 19.
     * @note Be sure to prompt the user to re-enable notifications for your app before calling this function.
     *
     * @return <code>true</code> if Teak was (probably) able to open the settings, <code>false</code> if Teak was (probably) not able to open the settings.
     */
    @SuppressWarnings("unused")
    public static boolean openSettingsAppToThisAppsSettings() {
        Teak.log.trace("Teak.openSettingsAppToThisAppsSettings");

        if (Instance == null) {
            Teak.log.e("error.openSettingsAppToThisAppsSettings", "openSettingsAppToThisAppsSettings() should not be called before onCreate()");
            return false;
        } else {
            return Instance.openSettingsAppToThisAppsSettings();
        }
    }

    /**
     * Determine if the current device is able to open directly to the notificaton settings
     * for this app.
     *
     * @return <code>true</code> if Teak will be able to open the notification settings, <code>false</code> otherwise.
     */
    @SuppressWarnings("unused")
    public static boolean canOpenNotificationSettings() {
        Teak.log.trace("Teak.canOpenNotificationSettings");

        if (Instance == null) {
            Teak.log.e("error.canOpenNotificationSettings", "canOpenNotificationSettings() should not be called before onCreate()");
            return false;
        } else {
            return Instance.canOpenNotificationSettings();
        }
    }

    /**
     * Open the settings app to the notification settings for this app.
     *
     * @note This will always return <code>false</code> for any device below API 26.
     *
     * @return <code>true</code> if Teak was able to open the notification settings, <code>false</code> if Teak was not able to open the settings.
     */
    @SuppressWarnings("unused")
    public static boolean openNotificationSettings() {
        Teak.log.trace("Teak.openNotificationSettings");

        if (Instance == null) {
            Teak.log.e("error.openNotificationSettings", "openNotificationSettings() should not be called before onCreate()");
            return false;
        } else {
            return Instance.openNotificationSettings(null);
        }
    }

    /**
     * Open the settings app to the notification settings for this app.
     *
     * @note This will always return <code>false</code> for any device below API 26.
     *
     * @param channelId The specific channel id to open the settings to.
     * @return <code>true</code> if Teak was able to open the notification settings, <code>false</code> if Teak was not able to open the settings.
     */
    @SuppressWarnings("unused")
    public static boolean openNotificationSettings(String channelId) {
        Teak.log.trace("Teak.openNotificationSettings");

        if (Instance == null) {
            Teak.log.e("error.openNotificationSettings", "openNotificationSettings() should not be called before onCreate()");
            return false;
        } else {
            return Instance.openNotificationSettings(channelId);
        }
    }

    /**
     * Set the badge number on the icon of the application.
     *
     * @note Set the count to 0 to remove the badge.
     *
     * @param count The value to set as the badge number.
     * @return <code>true</code> if Teak was able to set the badge number, <code>false</code> otherwise.
     */
    @SuppressWarnings({"unused", "UnusedReturnValue", "SameParameterValue"})
    public static boolean setApplicationBadgeNumber(int count) {
        Teak.log.trace("Teak.setApplicationBadgeNumber", "count", count);

        if (Instance == null) {
            Teak.log.e("error.setApplicationBadgeNumber", "setApplicationBadgeNumber() should not be called before onCreate()");
            return false;
        } else {
            return Instance.setApplicationBadgeNumber(count);
        }
    }

    /**
     * Track a numeric player profile attribute.
     *
     * @param attributeName  The name of the numeric attribute.
     * @param attributeValue The numeric value to assign.
     */
    @SuppressWarnings("unused")
    public static void setNumericAttribute(final String attributeName, final double attributeValue) {
        Teak.log.trace("Teak.setNumericAttribute", "attributeName", attributeName, "attributeValue", attributeValue);

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.setNumericAttribute(attributeName, attributeValue));
        }
    }

    /**
     * Track a string player profile attribute.
     *
     * @param attributeName  The name of the string attribute.
     * @param attributeValue The string value to assign.
     */
    @SuppressWarnings("unused")
    public static void setStringAttribute(final String attributeName, final String attributeValue) {
        Teak.log.trace("Teak.setStringAttribute", "attributeName", attributeName, "attributeValue", attributeValue);

        if (Instance != null) {
            asyncExecutor.submit(() -> Instance.setStringAttribute(attributeName, attributeValue));
        }
    }

    /**
     * Get Teak's configuration data about the current device.
     *
     * @return JSON string containing device info, or null if it's not ready
     */
    @SuppressWarnings("unused")
    public static String getDeviceConfiguration() {
        Teak.log.trace("Teak.getDeviceConfiguration");

        return getConfiguration("deviceConfiguration", new String[] {
                                                           "deviceId",
                                                           "deviceManufacturer",
                                                           "deviceModel",
                                                           "deviceFallback",
                                                           "platformString",
                                                           "memoryClass",
                                                           "advertisingId",
                                                           "limitAdTracking",
                                                           "pushRegistration"});
    }

    /**
     * Get Teak's configuration data about the current app.
     *
     * @return JSON string containing device info, or null if it's not ready
     */
    @SuppressWarnings("unused")
    public static String getAppConfiguration() {
        Teak.log.trace("Teak.getAppConfiguration");

        return getConfiguration("appConfiguration", new String[] {
                                                        "appId",
                                                        "apiKey",
                                                        "appVersion",
                                                        "bundleId",
                                                        "installerPackage",
                                                        "targetSdkVersion",
                                                        "traceLog"});
    }

    private static String getConfiguration(String subConfiguration, String[] configurationElements) {
        try {
            final TeakConfiguration teakConfiguration = TeakConfiguration.get();

            Map<String, Object> configurationMap = null;
            if ("deviceConfiguration".equalsIgnoreCase(subConfiguration)) {
                configurationMap = teakConfiguration.deviceConfiguration.toMap();
            } else if ("appConfiguration".equalsIgnoreCase(subConfiguration)) {
                configurationMap = teakConfiguration.appConfiguration.toMap();
            }
            if (configurationMap == null) return null;

            JSONObject configurationJSON = new JSONObject();
            for (String element : configurationElements) {
                if (configurationMap.containsKey(element)) {
                    configurationJSON.put(element, configurationMap.get(element));
                }
            }
            return configurationJSON.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Interface for running code when a deep link is received
     */
    public static abstract class DeepLink implements Unobfuscable {
        /**
         * Method called when a deep link is invoked.
         *
         * @param parameters A dictionary of the path, and url parameters provided to the deep link.
         */
        public abstract void call(Map<String, Object> parameters);
    }

    /**
     * Register a deep link route with Teak.
     *
     * @param route       A Sinatra-style route, eg /path/:capture
     * @param name        The name of the deep link, used in the Teak dashboard
     * @param description The description of the deep link, used in the Teak dashboard
     * @param call        The code to invoke when this deep link is received
     */
    @SuppressWarnings("unused")
    public static void registerDeepLink(@NonNull String route, @NonNull String name, @NonNull String description, @NonNull Teak.DeepLink call) {
        Teak.log.i("deep_link.register", Helpers.mm.h("route", route, "name", name, "description", description));
        io.teak.sdk.core.DeepLink.internalRegisterRoute(route, name, description, call);
    }

    /**
     * Base class for providing data about the launch of the app.
     */
    public static class LaunchData implements Unobfuscable {
        /**
         * If this launch is not attributed to anything, this constant is used instead of
         * a null LaunchData.
         */
        public static final LaunchData Unattributed = new LaunchData();

        /**
         * The link associated with this launch; or null.
         */
        public final Uri launchLink;

        /// @cond hide_from_doxygen
        /**
         * Constructor with {@link String}.
         * @param launchLink Link as a String.
         */
        protected LaunchData(@Nullable String launchLink) {
            this(launchLink != null ? Uri.parse(launchLink) : null);
        }

        /**
         * Constructor with {@link Uri}.
         * @param launchLink Link as a Uri.
         */
        public LaunchData(@Nullable Uri launchLink) {
            this.launchLink = launchLink;
        }

        /**
         * For {@link LaunchData#Unattributed}
         */
        private LaunchData() {
            this.launchLink = null;
        }

        /**
         * For internal use.
         *
         * @return Map used by Teak internally.
         */
        public Map<String, Object> toSessionAttributionMap() {
            final HashMap<String, Object> map = new HashMap<>();

            if (this.launchLink != null) {
                map.put("launch_link", this.launchLink.toString());
            }
            return map;
        }

        /**
         * Convert to a Map, intended to be converted to JSON and
         * consumed by the Teak Unity SDK.
         *
         * @return A Map representation of this object.
         */
        public Map<String, Object> toMap() {
            final HashMap<String, Object> map = new HashMap<>();
            map.put("launch_link", this.launchLink != null ? this.launchLink.toString() : null);
            return map;
        }
        /// @endcond
    }

    /**
     * Base class for describing a Teak-attributed launch of the app.
     */
    public static class AttributedLaunchData extends LaunchData implements Unobfuscable {
        /**
         * The name of the schedule responsible on the Teak dashboard; or null if this was not a scheduled channel.
         */
        public final String scheduleName;

        /**
         * The id of the schedule responsible on the Teak dashboard; or null if this was not a scheduled channel.
         */
        public final String scheduleId;

        /**
         * The name of the creative on the Teak dashboard.
         */
        public final String creativeName;

        /**
         * The id of the creative on the Teak dashboard.
         */
        public final String creativeId;

        /**
         * The id of the Teak reward associated with this launch; or null.
         */
        public final String rewardId;

        /**
         * The name of the channel responsible for this attribution.
         *
         * One of:
         *  * generic_link
         *  * android_push
         *  * email
         */
        public final String channelName;

        /**
         * The deep link associated with this launch; or null.
         */
        public final Uri deepLink;

        /**
         * The opt-out category of the marketing channel.
         */
        public final String optOutCategory;

        /**
         * Returns true if this was an incentivized launch.
         *
         * @return True if this notification had a reward attached to it.
         */
        public boolean isIncentivized() {
            return this.rewardId != null;
        }

        /// @cond hide_from_doxygen
        /**
         * Used by {@link NotificationLaunchData#NotificationLaunchData(Bundle)}
         * @param bundle Push notification contents.
         */
        protected AttributedLaunchData(@NonNull final Bundle bundle) {
            super(bundle.getString("teakDeepLink"));

            if (!(this instanceof NotificationLaunchData)) {
                throw new RuntimeException("AttributedLaunchData(Bundle) constructor used to construct something other than NotificationLaunchData");
            }

            this.scheduleName = bundle.getString("teakScheduleName");
            this.scheduleId = bundle.getString("teakScheduleId");
            this.creativeName = bundle.getString("teakCreativeName");
            this.creativeId = bundle.getString("teakCreativeId");
            this.rewardId = bundle.getString("teakRewardId");
            this.channelName = bundle.getString("teakChannelName");

            // When constructing from a Bundle, the deepLink should be assigned the launchLink
            // so that Session#checkLaunchDataForDeepLinkAndPostEvents will execute the deep link
            // correctly.
            this.deepLink = this.launchLink; // Resolved by call to super()

            this.optOutCategory = bundle.getString("teakOptOutCategory", "teak");
        }

        /**
         * Used by {@link NotificationLaunchData#NotificationLaunchData(Uri)}
         * @param deepLink Uri of the deep link in the notification
         */
        protected AttributedLaunchData(@NonNull final Uri deepLink) {
            this((Uri) null, deepLink);
        }

        /**
         * Used by {@link RewardlinkLaunchData#RewardlinkLaunchData(Uri, Uri)}
         * @param shortLink Uri of the email link or generic link used to launch the app.
         * @param deepLink Uri of a resolved deep link.
         */
        protected AttributedLaunchData(@Nullable final Uri shortLink, @NonNull Uri deepLink) {
            super(shortLink);

            if (deepLink.isOpaque()) {
                this.scheduleName = this.scheduleId = this.creativeName = this.creativeId = this.rewardId = this.channelName = this.optOutCategory = null;
                this.deepLink = deepLink;
                return;
            }

            this.scheduleName = deepLink.getQueryParameter("teak_schedule_name");
            this.scheduleId = deepLink.getQueryParameter("teak_schedule_id");

            // In the non-mobile world, there is no such thing as "not a link launch" and so the
            // parameter names are different to properly differentiate session source
            final String urlCreativeName = deepLink.getQueryParameter("teak_creative_name");
            this.creativeName = urlCreativeName != null ? urlCreativeName : deepLink.getQueryParameter("teak_rewardlink_name");
            final String urlCreativeId = deepLink.getQueryParameter("teak_creative_id");
            this.creativeId = urlCreativeId != null ? urlCreativeId : deepLink.getQueryParameter("teak_rewardlink_id");
            this.rewardId = deepLink.getQueryParameter("teak_reward_id");
            this.channelName = deepLink.getQueryParameter("teak_channel_name");

            final String teakDeepLink = deepLink.getQueryParameter("teak_deep_link");
            this.deepLink = (teakDeepLink == null ? deepLink : Uri.parse(teakDeepLink));

            this.optOutCategory = deepLink.getQueryParameter("teak_opt_out_category") != null ? deepLink.getQueryParameter("teak_opt_out_category") : "teak";
        }

        /**
         * For use in {@link AttributedLaunchData#mergeDeepLink(Uri)}
         * @param oldLaunchData The old attribution
         * @param updatedDeepLink The deep link in the reply from the identify user request.
         */
        protected AttributedLaunchData(@NonNull final AttributedLaunchData oldLaunchData, @NonNull Uri updatedDeepLink) {
            super(oldLaunchData.launchLink);

            final AttributedLaunchData newLaunchData = new AttributedLaunchData(updatedDeepLink);
            this.scheduleName = Helpers.newIfNotOld(oldLaunchData.scheduleName, newLaunchData.scheduleName);
            this.scheduleId = Helpers.newIfNotOld(oldLaunchData.scheduleId, newLaunchData.scheduleId);
            this.creativeName = Helpers.newIfNotOld(oldLaunchData.creativeName, newLaunchData.creativeName);
            this.creativeId = Helpers.newIfNotOld(oldLaunchData.creativeId, newLaunchData.creativeId);
            this.rewardId = Helpers.newIfNotOld(oldLaunchData.rewardId, newLaunchData.rewardId);
            this.channelName = Helpers.newIfNotOld(oldLaunchData.channelName, newLaunchData.channelName);
            this.deepLink = updatedDeepLink;
            if (updatedDeepLink.isHierarchical()) {
                this.optOutCategory = Helpers.newIfNotOld(oldLaunchData.optOutCategory,
                    updatedDeepLink.getQueryParameter("teak_opt_out_category") != null ? updatedDeepLink.getQueryParameter("teak_opt_out_category") : "teak");
            } else {
                this.optOutCategory = oldLaunchData.optOutCategory;
            }
        }

        /**
         * Used by {@link io.teak.sdk.core.LaunchDataSource#sourceWithUpdatedDeepLink}
         * @param uri Updated deep link
         * @return A merged AttributedLaunchData
         */
        public AttributedLaunchData mergeDeepLink(@NonNull Uri uri) {
            return new AttributedLaunchData(this, uri);
        }

        @Override
        public Map<String, Object> toSessionAttributionMap() {
            final Map<String, Object> map = super.toSessionAttributionMap();

            map.put("teak_opt_out_category", this.optOutCategory);

            // Put the URI and any query parameters that start with 'teak_' into 'deep_link'
            if (this.deepLink != null) {
                map.put("deep_link", this.deepLink.toString());
                if (this.deepLink.isHierarchical()) {
                    for (final String name : this.deepLink.getQueryParameterNames()) {
                        if (name.startsWith("teak_")) {
                            final List<String> values = this.deepLink.getQueryParameters(name);
                            if (values.size() > 1) {
                                map.put(name, values);
                            } else {
                                map.put(name, values.get(0));
                            }
                        }
                    }
                }
            }

            return map;
        }

        /**
         * Convert to a Map, intended to be converted to JSON and
         * consumed by the Teak Unity SDK.
         *
         * @return A Map representation of this object.
         */
        @Override
        public Map<String, Object> toMap() {
            final Map<String, Object> map = super.toMap();
            map.put("teakScheduleName", this.scheduleName);
            map.put("teakScheduleId", this.scheduleId);
            map.put("teakCreativeName", this.creativeName);
            map.put("teakCreativeId", this.creativeId);
            map.put("teakRewardId", this.rewardId);
            map.put("teakChannelName", this.channelName);
            map.put("teakDeepLink", io.teak.sdk.core.DeepLink.willProcessUri(this.deepLink) ? this.deepLink.toString() : null);
            map.put("teakOptOutCategory", this.optOutCategory);
            return map;
        }
        /// @endcond
    }

    /**
     * Launch data for a Teak push notification or email.
     */
    public static class NotificationLaunchData extends AttributedLaunchData implements Unobfuscable {
        /**
         * The send-id of the notification.
         */
        public final String sourceSendId;

        /// @cond hide_from_doxygen
        /**
         * Construct NotificationLaunchData for a push notification.
         * @param bundle Push notification contents.
         */
        public NotificationLaunchData(@NonNull final Bundle bundle) {
            super(bundle);
            this.sourceSendId = bundle.getString("teakNotifId");
        }

        /**
         * Construct NotificationLaunchData for an email link.
         * @param uri Email link.
         */
        public NotificationLaunchData(@NonNull final Uri uri) {
            super(uri);
            this.sourceSendId = uri.getQueryParameter("teak_notif_id");
        }

        /**
         * For use in {@link NotificationLaunchData#mergeDeepLink(Uri)}
         * @param oldLaunchData The old attribution
         * @param updatedDeepLink The deep link in the reply from the identify user request.
         */
        protected NotificationLaunchData(@NonNull final NotificationLaunchData oldLaunchData, @NonNull Uri updatedDeepLink) {
            super(oldLaunchData, updatedDeepLink);
            if (updatedDeepLink.isHierarchical()) {
                this.sourceSendId = Helpers.newIfNotOld(oldLaunchData.sourceSendId, updatedDeepLink.getQueryParameter("teak_notif_id"));
            } else {
                this.sourceSendId = oldLaunchData.sourceSendId;
            }
        }

        @Override
        public AttributedLaunchData mergeDeepLink(@NonNull Uri uri) {
            return new NotificationLaunchData(this, uri);
        }

        /**
         * For internal use.
         * @param uri The Uri to test.
         * @return true if the Uri is from a Teak email, in which case it should use NotificationLaunchData.
         */
        public static boolean isTeakEmailUri(@NonNull final Uri uri) {
            try {
                return !Helpers.isNullOrEmpty(uri.getQueryParameter("teak_notif_id"));
            } catch (UnsupportedOperationException ignored) {
            }
            return false;
        }

        @Override
        public Map<String, Object> toMap() {
            final Map<String, Object> map = super.toMap();
            map.put("teakNotifId", this.sourceSendId);
            return map;
        }

        @Override
        public Map<String, Object> toSessionAttributionMap() {
            final Map<String, Object> map = super.toSessionAttributionMap();
            if (this.sourceSendId != null) {
                map.put("teak_notif_id", this.sourceSendId);
            }
            return map;
        }
        /// @endcond
    }

    /**
     * Launch data for a Teak reward link.
     */
    public static class RewardlinkLaunchData extends AttributedLaunchData implements Unobfuscable {
        /// @cond hide_from_doxygen
        public RewardlinkLaunchData(@NonNull final Uri uri, @Nullable final Uri shortLink) {
            super(shortLink, uri);
        }

        /**
         * For internal use.
         * @param uri The Uri to test.
         * @return true if the Uri is a Teak reward link.
         */
        public static boolean isTeakRewardLink(@NonNull final Uri uri) {
            try {
                return !Helpers.isNullOrEmpty(uri.getQueryParameter("teak_rewardlink_id"));
            } catch (UnsupportedOperationException ignored) {
            }
            return false;
        }

        /**
         * For use in {@link RewardlinkLaunchData#mergeDeepLink(Uri)}
         * @param oldLaunchData The old attribution
         * @param updatedDeepLink The deep link in the reply from the identify user request.
         */
        protected RewardlinkLaunchData(@NonNull final RewardlinkLaunchData oldLaunchData, @NonNull Uri updatedDeepLink) {
            super(oldLaunchData, updatedDeepLink);
        }

        @Override
        public AttributedLaunchData mergeDeepLink(@NonNull Uri uri) {
            return new RewardlinkLaunchData(this, uri);
        }
        /// @endcond
    }

    /**
     * Base class for Teak events
     */
    public static class Event implements Unobfuscable {
        /**
         * Data associated with this launch.
         */
        public final LaunchData launchData;

        /**
         * The {@link TeakNotification.Reward} attached, or null.
         */
        public final TeakNotification.Reward reward;

        /// @cond hide_from_doxygen
        /**
         * Constructor.
         * @param launchData Attribution data for launch.
         * @param reward Reward, if available.
         */
        public Event(@NonNull final LaunchData launchData, @Nullable final TeakNotification.Reward reward) {
            this.launchData = launchData;
            this.reward = reward;
        }

        public JSONObject toJSON() {
            final Map<String, Object> map = this.launchData.toMap();
            if (this.reward != null && this.reward.json != null) {
                map.putAll(this.reward.json.toMap());
            }
            return new JSONObject(map);
        }
        /// @endcond
    }

    public static class ConfigurationDataEvent extends Event implements Unobfuscable {
        private final RemoteConfiguration remoteConfiguration;
        public final String deviceId;

        public ConfigurationDataEvent(@NonNull final RemoteConfiguration configuration, @NonNull final String deviceId) {
            super(null, null);
            this.remoteConfiguration = configuration;
            this.deviceId = deviceId;
        }

        @Override
        public JSONObject toJSON() {
            final JSONObject json = new JSONObject();
            final ArrayList<JSONObject> categories = new ArrayList<JSONObject>();
            for (Teak.Channel.Category category : this.remoteConfiguration.categories) {
                categories.add(category.toJSON());
            }

            json.put("channelCategories", categories);
            json.put("deviceId", this.deviceId);
            return json;
        }
    }

    /**
     * Event posted when a foreground notification was received, or the game was launched from a
     * Teak email, or Teak push notification.
     */
    public static class NotificationEvent extends Event implements Unobfuscable {
        /**
         * True if this push notification was received when the app was in the foreground.
         */
        public final boolean isForeground;

        /// @cond hide_from_doxygen
        /**
         * Constructor.
         * @param launchData   Notification launch data.
         * @param isForeground True if the notification was delivered in the foreground.
         */
        public NotificationEvent(@NonNull final NotificationLaunchData launchData, final boolean isForeground) {
            super(launchData, null);
            this.isForeground = isForeground;
        }

        @Override
        public JSONObject toJSON() {
            final JSONObject json = super.toJSON();
            json.put("isForeground", this.isForeground);
            return json;
        }
        /// @endcond
    }

    /**
     * Event posted when the app was launched from a link created by the Teak dashboard.
     */
    public static class LaunchFromLinkEvent extends Event implements Unobfuscable {
        /// @cond hide_from_doxygen
        /**
         * Constructor.
         * @param launchData Launch attribution data.
         */
        public LaunchFromLinkEvent(@NonNull final RewardlinkLaunchData launchData) {
            super(launchData, null);
        }
        /// @endcond
    }

    /**
     * Event posted whenever the app launches.
     */
    public static class PostLaunchSummaryEvent extends Event implements Unobfuscable {
        /// @cond hide_from_doxygen
        public PostLaunchSummaryEvent(@NonNull final LaunchData launchData) {
            super(launchData, null);
        }
        /// @endcond
    }

    /**
     * Event posted when a reward claim attempt has occurred.
     */
    public static class RewardClaimEvent extends Event implements Unobfuscable {
        /// @cond hide_from_doxygen
        /**
         * Constructor
         * @param launchData Launch attribution data.
         * @param reward Reward.
         */
        public RewardClaimEvent(@NonNull final AttributedLaunchData launchData, @NonNull final TeakNotification.Reward reward) {
            super(launchData, reward);
        }
        /// @endcond
    }

    /**
     * Event sent when "additional data" is available for the user.
     *
     * @deprecated Use the {@link UserDataEvent} event instead.
     */
    @Deprecated
    public static class AdditionalDataEvent implements Unobfuscable {
        /**
         * A JSON object containing user-defined data received from the server.
         */
        public final JSONObject additionalData;

        /// @cond hide_from_doxygen
        /**
         * Constructor.
         * @param additionalData User-specific data received from the server.
         */
        public AdditionalDataEvent(final JSONObject additionalData) {
            this.additionalData = additionalData;
        }
        /// @endcond
    }

    /**
     * Event sent when data about the user becomes available, or gets updated.
     */
    public static class UserDataEvent implements Unobfuscable {
        /**
         * A JSON object containing user-defined data received from the Teak server, or null.
         */
        public final JSONObject additionalData;

        /**
         * Status of the Teak email channel for this user.
         */
        public final ChannelStatus emailStatus;

        /**
         * Status of the Teak push notification channel for this user.
         */
        public final ChannelStatus pushStatus;

        /**
         * Status of the Teak SMS channel for this user.
         */
        public final ChannelStatus smsStatus;

        /**
         * Push registration information for this user, if push is enabled.
         */
        public final Map<String, String> pushRegistration;

        /**
         * The device's persistent random ID.
         */
        public final String deviceId;

        /// @cond hide_from_doxygen
        /**
         * Constructor.
         * @param additionalData   User-specific data received from the server.
         * @param email            Email opt out state.
         * @param push             Push opt out state.
         * @param sms              SMS opt out state.
         * @param pushRegistration Push registration dictionary
         * @param deviceId         The device's persistent random ID.
         */
        public UserDataEvent(final JSONObject additionalData,
            final ChannelStatus email,
            final ChannelStatus push,
            final ChannelStatus sms,
            final Map<String, String> pushRegistration,
            @NonNull final String deviceId) {
            this.additionalData = additionalData == null ? new JSONObject() : additionalData;
            this.emailStatus = email;
            this.pushStatus = push;
            this.smsStatus = sms;
            this.pushRegistration = pushRegistration;
            this.deviceId = deviceId;
        }

        public JSONObject toJSON() {
            final JSONObject json = new JSONObject();
            json.put("additionalData", this.additionalData);
            json.put("emailStatus", this.emailStatus.toJSON());
            json.put("pushStatus", this.pushStatus.toJSON());
            json.put("smsStatus", this.smsStatus.toJSON());
            json.put("pushRegistration", this.pushRegistration);
            json.put("deviceId", this.deviceId);
            return json;
        }
        /// @endcond
    }

    ///// LogListener

    /**
     * Used to listen for Teak log events.
     */
    public static abstract class LogListener {
        /**
         * A log event sent by the Teak SDK.
         *
         * @param logEvent The log event type.
         * @param logLevel The severity of the log message.
         * @param logData  Semi-structured log message.
         **/
        public abstract void logEvent(String logEvent, String logLevel, Map<String, Object> logData);
    }

    /**
     * Listen for Teak SDK log events.
     *
     * @param logListener A {@link LogListener} that will be called each time Teak would log an internal SDK event.
     */
    @SuppressWarnings("unused")
    public static void setLogListener(LogListener logListener) {
        Teak.log.setLogListener(logListener);
    }

    ///// BroadcastReceiver

    @Override
    /// @cond hide_from_doxygen
    public void onReceive(final Context inContext, final Intent intent) {
        final Context context = inContext.getApplicationContext();

        String action = intent.getAction();
        if (action == null) return;

        // If Instance is null, make sure TeakCore is around. If it's not null, make sure it's enabled.
        if ((Instance == null && TeakCore.getWithoutThrow() == null) || (Instance != null && !Instance.isEnabled())) {
            return;
        }

        if (action.endsWith(TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX)) {
            TeakEvent.postEvent(new PushNotificationEvent(PushNotificationEvent.Interaction, context, intent));
        } else if (action.endsWith(TeakNotification.TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX)) {
            Teak.log.i("notification.cleared", "notification.cleared");
            TeakEvent.postEvent(new PushNotificationEvent(PushNotificationEvent.Cleared, context, intent));
        }
    }
    /// @endcond

    ///// Logging
    /// @cond hide_from_doxygen
    public static int jsonLogIndentation = 0;
    public static io.teak.sdk.Log log = new io.teak.sdk.Log(Teak.LOG_TAG, Teak.jsonLogIndentation);

    public static String formatJSONForLogging(JSONObject obj) throws JSONException {
        if (Teak.jsonLogIndentation > 0) {
            return obj.toString(Teak.jsonLogIndentation);
        } else {
            return obj.toString();
        }
    }
    /// @endcond

    ///// Deep Links

    /**
     * Indicate that your app is ready for deep links.
     *
     * Deep links will not be processed sooner than the earliest of:
     * - {@link #identifyUser(String, UserConfiguration)} is called
     * - This method is called
     */
    @SuppressWarnings("unused")
    public static void processDeepLinks() {
        try {
            synchronized (Teak.waitForDeepLink) {
                if (!Teak.waitForDeepLink.isDone()) {
                    Teak.asyncExecutor.execute(Teak.waitForDeepLink);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static final FutureTask<Void> waitForDeepLink = new FutureTask<>(() -> TeakEvent.postEvent(new DeepLinksReadyEvent()), null);

    /// @cond hide_from_doxygen
    /**
     * Block until deep links are ready for processing.
     *
     * For internal use.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public static void waitUntilDeepLinksAreReady() throws ExecutionException, InterruptedException {
        Teak.waitForDeepLink.get();
    }
    /// @endcond

    /**
     * Manually pass Teak a deep link path to handle.
     *
     * This path should be prefixed with a forward slash, and can contain query parameters, e.g.
     *     /foo/bar?fizz=buzz
     * It should not contain a host, or a scheme.
     *
     * This function will only execute deep links that have been defined through Teak.
     * It has no visibility into any other SDKs or custom code.
     * @param path The deep link path to process.
     * @return true if the deep link was found and handled.
     */
    @SuppressWarnings("unused")
    public static boolean handleDeepLinkPath(final String path) {
        Teak.log.trace("Teak.handleDeepLinkPath", "path", path);

        try {
            final URI uri = URI.create(TeakConfiguration.get().appConfiguration.urlSchemes.iterator().next() + "://" + path);
            return io.teak.sdk.core.DeepLink.processUri(uri);
        } catch (Exception ignored) {
            return false;
        }
    }

    /// @cond hide_from_doxygen
    ///// Configuration

    public static final String PREFERENCES_FILE = "io.teak.sdk.Preferences";

    ///// Data Members

    public static TeakInstance Instance;

    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool();
    /// @endcond
}
