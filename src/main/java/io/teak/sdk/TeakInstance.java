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

package io.teak.sdk;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.json.JSONObject;

import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.Map;

import io.teak.sdk.event.OSListener;

class TeakInstance {
    TeakInstance(@NonNull Activity activity, @Nullable ObjectFactory objectFactory) {
        if (activity == null) {
            throw new InvalidParameterException("null Activity passed to Teak.onCreate");
        }
        this.activityHashCode = activity.hashCode();
        this.osListener = objectFactory.getOSListener();

        // Add version info for Unity/Air
        String wrapperSDKName = Helpers.getStringResourceByName("io_teak_wrapper_sdk_name", activity.getApplicationContext());
        String wrapperSDKVersion = Helpers.getStringResourceByName("io_teak_wrapper_sdk_version", activity.getApplicationContext());
        if (wrapperSDKName != null && wrapperSDKVersion != null) {
            this.wrapperSDKMap.put(wrapperSDKName, wrapperSDKVersion);
        }

        // Output version information
        Teak.log.useSdk(this.to_h());

        // Ready for debug logging.
        Teak.debugConfiguration = new DebugConfiguration(activity.getApplicationContext());

        // Check the launch mode of the activity
        try {
            ComponentName cn = new ComponentName(activity, activity.getClass());
            ActivityInfo ai = activity.getPackageManager().getActivityInfo(cn, PackageManager.GET_META_DATA);
            // (LAUNCH_SINGLE_INSTANCE == LAUNCH_SINGLE_TASK | LAUNCH_SINGLE_TOP) but let's not
            // assume that those values will stay the same
            if ((ai.launchMode & ActivityInfo.LAUNCH_SINGLE_INSTANCE) == 0 &&
                    (ai.launchMode & ActivityInfo.LAUNCH_SINGLE_TASK) == 0 &&
                    (ai.launchMode & ActivityInfo.LAUNCH_SINGLE_TOP) == 0) {
                Teak.log.w("launch_mode", "The android:launchMode of this activity is not set to 'singleTask', 'singleTop' or 'singleInstance'. This could cause undesired behavior.");
            }
        } catch (Exception e) {
            Teak.log.exception(e);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            Teak.log.e("api_level", "Teak requires API level 14 to operate. Teak is disabled.");
            this.setState(State.Disabled);
        } else {
            try {
                Application application = activity.getApplication();
                application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
            } catch (Exception e) {
                Teak.log.exception(e);
                this.setState(State.Disabled);
            }
        }
    }

    void close(Activity activity) {
        if (this.appStore != null) {
            this.appStore.dispose();
            this.appStore = null;
        }

//        RemoteConfiguration.removeEventListener(Teak.remoteConfigurationEventListener);
//        Session.removeEventListener(Teak.sessionEventListener);

//        if (Teak.facebookAccessTokenBroadcast != null) {
//            Teak.facebookAccessTokenBroadcast.unregister(activity.getApplicationContext());
//        }

        activity.getApplication().unregisterActivityLifecycleCallbacks(this.lifecycleCallbacks);
    }

    ///// to_h

    Map<String, Object> to_h() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("android", Teak.SDKVersion);
        map.putAll(wrapperSDKMap);
        return map;
    }
    private Map<String, Object> wrapperSDKMap = new HashMap<>();

    ///// identifyUser

    void identifyUser(String userIdentifier) {
        if (userIdentifier == null || userIdentifier.isEmpty()) {
            Teak.log.e("identify_user.error", "User identifier can not be null or empty.");
            return;
        }

        Teak.log.i("identify_user", Helpers._.h("userId", userIdentifier));

        if (this.isEnabled()) {
            // TODO: Send to Core

            // Add userId to the Ravens
            //Teak.sdkRaven.addUserData("id", userIdentifier);
            //Teak.appRaven.addUserData("id", userIdentifier);

            // Send to Session
            //Session.setUserId(userIdentifier);
        }
    }

    ///// trackEvent

    void trackEvent(final String actionId, final String objectTypeId, final String objectInstanceId) {
        if (actionId == null || actionId.isEmpty()) {
            Teak.log.e("track_event.error", "actionId can not be null or empty for trackEvent(), ignoring.");
            return;
        }

        if ((objectInstanceId != null && !objectInstanceId.isEmpty()) &&
                (objectTypeId == null || objectTypeId.isEmpty())) {
            Teak.log.e("track_event.error", "objectTypeId can not be null or empty if objectInstanceId is present for trackEvent(), ignoring.");
            return;
        }

        Teak.log.i("track_event", Helpers._.h("actionId", actionId, "objectTypeId", objectTypeId, "objectInstanceId", objectInstanceId));

        // TODO: Send to Core
        if (this.isEnabled()) {
            Session.whenUserIdIsReadyRun(new Session.SessionRunnable() {
                @Override
                public void run(Session session) {
                    HashMap<String, Object> payload = new HashMap<>();
                    payload.put("action_type", actionId);
                    payload.put("object_type", objectTypeId);
                    payload.put("object_instance_id", objectInstanceId);
                    new Request("/me/events", payload, session).run();
                }
            });
        }
    }

    ///// Broadcast Receiver

    private static final String GCM_RECEIVE_INTENT_ACTION = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GCM_REGISTRATION_INTENT_ACTION  = "com.google.android.c2dm.intent.REGISTRATION";

    void onReceive(Context inContext, Intent intent) {
        final Context context = inContext.getApplicationContext();

        if (!this.isEnabled()) {
            return;
        }

        String action = intent.getAction();
        if (GCM_RECEIVE_INTENT_ACTION.equals(action)) {
            this.osListener.notification_onNotificationReceived(context, intent);
        } else if (GCM_REGISTRATION_INTENT_ACTION.equals(action)) {
            final String registrationId = intent.getStringExtra("registration_id");
            // TODO: TeakIOListener
        } else if (action.endsWith(TeakNotification.TEAK_NOTIFICATION_OPENED_INTENT_ACTION_SUFFIX)) {
            this.osListener.notification_onNotificationAction(context, intent);
        } else if (action.endsWith(TeakNotification.TEAK_NOTIFICATION_CLEARED_INTENT_ACTION_SUFFIX)) {
            this.osListener.notification_onNotificationCleared(context, intent);
        }
    }

    ///// State Machine

    enum State {
        Disabled("Disabled"),
        Allocated("Allocated"),
        Created("Created"),
        Active("Active"),
        Paused("Paused"),
        Destroyed("Destroyed");

        //public static final Integer length = 1 + Destroyed.ordinal();

        private static final State[][] allowedTransitions = {
                {},
                {State.Created},
                {State.Active},
                {State.Paused},
                {State.Destroyed, State.Active},
                {}
        };

        public final String name;

        State(String name) {
            this.name = name;
        }

        public boolean canTransitionTo(State nextState) {
            if (nextState == State.Disabled) return true;

            for (State allowedTransition : allowedTransitions[this.ordinal()]) {
                if (nextState == allowedTransition) return true;
            }
            return false;
        }
    }

    private State state = State.Allocated;
    private final Object stateMutex = new Object();

    boolean isEnabled() {
        synchronized (this.stateMutex) {
            return (this.state != State.Disabled);
        }
    }

    private boolean setState(@NonNull State newState) {
        synchronized (this.stateMutex) {
            if (this.state == newState) {
                Teak.log.i("teak.state_duplicate", String.format("Teak State transition to same state (%s). Ignoring.", this.state));
                return false;
            }

            if (!this.state.canTransitionTo(newState)) {
                Teak.log.e("teak.state_invalid", String.format("Invalid Teak State transition (%s -> %s). Ignoring.", this.state, newState));
                return false;
            }

            Teak.log.i("teak.state", Helpers._.h("old_state", this.state.name, "state", newState.name));

            this.state = newState;

            return true;
        }
    }

    ///// Purchase

    private IStore appStore;

    void pluginPurchaseSucceeded(JSONObject originalJson) {
        this.osListener.purchase_onPurchaseSucceeded(originalJson);
    }

    void pluginPurchaseFailed(int errorCode) {
        JSONObject json = new JSONObject();
        try {
            json.put("errorCode", errorCode);
        } catch (Exception ignored){
        }
        this.osListener.purchase_onPurchaseFailed(json);
    }

    void checkActivityResultForPurchase(int resultCode, Intent data) {
        if (this.isEnabled()) {
            if (this.appStore != null) {
                this.appStore.checkActivityResultForPurchase(resultCode, data);
            } else {
                Teak.log.e("puchase.failed", "Unable to checkActivityResultForPurchase, no active app store.");
            }
        }
    }

    ///// Activity Lifecycle

    private final OSListener osListener;
    private final int activityHashCode;

    private final Application.ActivityLifecycleCallbacks lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(Activity activity, Bundle bundle) {
            if (activity.hashCode() == activityHashCode && setState(State.Created)) {
                Teak.log.i("lifecycle", Helpers._.h("callback", "onActivityCreated"));
                if (!osListener.lifecycle_onActivityCreated(activity)) {
                    close(activity);
                    setState(State.Disabled);
                }

            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity.hashCode() == activityHashCode && setState(State.Active)) {
                Teak.log.i("lifecycle", Helpers._.h("callback", "onActivityResumed"));
                osListener.lifecycle_onActivityResumed(activity);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (activity.hashCode() == activityHashCode && setState(State.Paused)) {
                Teak.log.i("lifecycle", Helpers._.h("callback", "onActivityPaused"));
                osListener.lifecycle_onActivityPaused(activity);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (activity.hashCode() == activityHashCode && setState(State.Destroyed)) {
                // Nothing right now
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
        }
    };
}
