package io.teak.sdk.push;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import io.teak.sdk.NotificationBuilder;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.Unobfuscable;
import io.teak.sdk.core.Executors;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONObject;

public class PushState implements Unobfuscable {
    private static final String PUSH_STATE_CHAIN_KEY = "io.teak.sdk.Preferences.PushStateChain";

    public enum State {
        Unknown("unknown"),
        Authorized("authorized"),
        Denied("denied");

        // public static final Integer length = 1 + Denied.ordinal();

        private static final State[][] allowedTransitions = {
            {State.Authorized, State.Denied},
            {State.Denied},
            {State.Authorized}};

        public final String name;

        State(String name) {
            this.name = name;
        }

        public boolean canTransitionTo(State nextState) {
            for (State allowedTransition : allowedTransitions[this.ordinal()]) {
                if (nextState.equals(allowedTransition)) return true;
            }
            return false;
        }
    }

    private static class StateChainEntry {
        final State state;
        final Date date;
        final boolean canBypassDnd;
        final boolean canShowOnLockscreen;
        final boolean canShowBadge;

        StateChainEntry(State state, boolean canBypassDnd, boolean canShowOnLockscreen, boolean canShowBadge) {
            this.state = state;
            this.date = new Date();
            this.canBypassDnd = canBypassDnd;
            this.canShowOnLockscreen = canShowOnLockscreen;
            this.canShowBadge = canShowBadge;
        }

        StateChainEntry(JSONObject json) {
            String stateName = json.getString("state");
            if (stateName.equals(State.Authorized.name)) {
                this.state = State.Authorized;
            } else if (stateName.equals(State.Denied.name)) {
                this.state = State.Denied;
            } else {
                this.state = State.Unknown;
            }

            long timestamp = json.getLong("date");
            this.date = new Date(timestamp * 1000L); // Sec -> MS

            this.canBypassDnd = json.getBoolean("canBypassDnd");
            this.canShowOnLockscreen = json.getBoolean("canShowOnLockscreen");
            this.canShowBadge = json.getBoolean("canShowBadge");
        }

        Map<String, Object> toMap() {
            HashMap<String, Object> ret = new HashMap<>();
            ret.put("state", this.state.name);
            ret.put("date", this.date.getTime() / 1000L); // MS -> Sec
            if (this.state == State.Authorized) {
                ret.put("canBypassDnd", this.canBypassDnd);
                ret.put("canShowOnLockscreen", this.canShowOnLockscreen);
                ret.put("canShowBadge", this.canShowBadge);
            }
            return ret;
        }

        JSONObject toJson() {
            return new JSONObject(this.toMap());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof StateChainEntry)) {
                return super.equals(obj);
            }

            StateChainEntry other = (StateChainEntry) obj;
            return this.equalsIgnoreDate(other) && this.date.equals(other.date);
        }

        public boolean equalsIgnoreDate(StateChainEntry other) {
            return this.state.equals(other.state) &&
                this.canBypassDnd == other.canBypassDnd &&
                this.canShowOnLockscreen == other.canShowOnLockscreen &&
                this.canShowBadge == other.canShowBadge;
        }
    }

    private List<StateChainEntry> stateChain = new ArrayList<>();
    private final NotificationManagerCompat notificationManager;
    private final ExecutorService executionQueue = Executors.newSingleThreadExecutor();

    private static PushState Instance;
    public static void init(@NonNull Context context) {
        if (Instance == null) {
            Instance = new PushState(context);
        }
    }

    public static PushState get() {
        return Instance;
    }

    private PushState(@NonNull Context context) {
        this.notificationManager = NotificationManagerCompat.from(context);

        // Try and load serialized state chain
        final SharedPreferences preferences = context.getSharedPreferences(Teak.PREFERENCES_FILE, Context.MODE_PRIVATE);
        final String pushStateChainJson = preferences.getString(PUSH_STATE_CHAIN_KEY, null);
        try {
            if (pushStateChainJson != null) {
                List<StateChainEntry> tempStateChain = new ArrayList<>();
                JSONArray stateChainJsonArray = new JSONArray(pushStateChainJson);
                for (Object jsonEntry : stateChainJsonArray) {
                    tempStateChain.add(new StateChainEntry((JSONObject) jsonEntry));
                }
                this.stateChain = Collections.unmodifiableList(tempStateChain);
            }
        } catch (Exception ignored) {
        }

        // Event listener - When onResume is called, update the state chain
        TeakEvent.addEventListener(event -> {
            switch (event.eventType) {
                case LifecycleEvent.Resumed: {
                    LifecycleEvent lifecycleEvent = (LifecycleEvent) event;
                    PushState.this.updateStateChain(lifecycleEvent.context);
                } break;
            }
        });
    }

    private Future<State> updateStateChain(@NonNull final Context context) {
        return this.executionQueue.submit(() -> {
            final State currentState = PushState.this.getCurrentStateFromChain();
            final StateChainEntry currentEntry = PushState.this.stateChain.size() == 0 ? null : PushState.this.stateChain.get(PushState.this.stateChain.size() - 1);
            final StateChainEntry newStateEntry = PushState.this.determineStateFromSystem(context);
            if (currentState.canTransitionTo(newStateEntry.state) ||
                (currentState.equals(newStateEntry.state) && !newStateEntry.equalsIgnoreDate(currentEntry))) {
                List<StateChainEntry> newChain = new ArrayList<>(PushState.this.stateChain);
                newChain.add(newStateEntry);

                // Trim state chain to 50 max
                while (newChain.size() > 50) {
                    newChain.remove(0);
                }

                PushState.this.stateChain = Collections.unmodifiableList(newChain);
                PushState.this.writeSerialzedStateChain(context, PushState.this.stateChain);
                return newStateEntry.state;
            }
            return PushState.this.getCurrentStateFromChain();
        });
    }

    private State getCurrentStateFromChain() {
        if (this.stateChain.size() == 0) return State.Unknown;

        final StateChainEntry currentEntry = this.stateChain.get(PushState.this.stateChain.size() - 1);
        return currentEntry.state;
    }

    private void writeSerialzedStateChain(@NonNull final Context context, List<StateChainEntry> stateChain) {
        final JSONArray jsonStateChain = new JSONArray();
        for (StateChainEntry entry : stateChain) {
            jsonStateChain.put(entry.toJson());
        }

        this.executionQueue.submit(() -> {
            synchronized (Teak.PREFERENCES_FILE) {
                SharedPreferences preferences = context.getSharedPreferences(Teak.PREFERENCES_FILE, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString(PUSH_STATE_CHAIN_KEY, jsonStateChain.toString());
                editor.apply();
    }
});
}

public int getNotificationStatus() {
    return this.notificationManager.areNotificationsEnabled() ? Teak.TEAK_NOTIFICATIONS_ENABLED : Teak.TEAK_NOTIFICATIONS_DISABLED;
}

private StateChainEntry determineStateFromSystem(@NonNull Context context) {
    State tmpState;
    switch (this.getNotificationStatus()) {
        case Teak.TEAK_NOTIFICATIONS_ENABLED:
            tmpState = State.Authorized;
            break;
        case Teak.TEAK_NOTIFICATIONS_DISABLED:
            tmpState = State.Denied;
            break;
        default:
            tmpState = State.Unknown;
            break;
    }
    final State newState = tmpState;
    boolean canBypassDnd = false;
    boolean canShowOnLockscreen = true;
    boolean canShowBadge = true;

    final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
        final NotificationChannel channel = notificationManager.getNotificationChannel(NotificationBuilder.DEFAULT_NOTIFICATION_CHANNEL_ID);
        if (channel != null) {
            final int channelImportance = channel.getImportance();
            canBypassDnd = channel.canBypassDnd();
            // Future-Pat: The name of the settings does not line up with the constant names.
            //             'Low' in settings == IMPORTANCE_MIN
            canShowOnLockscreen = (channelImportance > NotificationManager.IMPORTANCE_MIN);
            canShowBadge = channel.canShowBadge();
        }
    }
    return new StateChainEntry(newState, canBypassDnd, canShowOnLockscreen, canShowBadge);
}

public Map<String, Object> toMap() {
    List<StateChainEntry> currentStateChain = this.stateChain;
    List<Map<String, Object>> genericStateChain = new ArrayList<>();
    for (StateChainEntry entry : currentStateChain) {
        genericStateChain.add(entry.toMap());
    }

    HashMap<String, Object> ret = new HashMap<>();
    ret.put("push_state_chain", genericStateChain);
    return ret;
}
}
