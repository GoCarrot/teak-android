package io.teak.sdk.core;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.Date;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import io.teak.sdk.Helpers;
import io.teak.sdk.Teak;
import io.teak.sdk.io.IAndroidNotification;
import io.teak.sdk.raven.Sender;

public class DeviceScreenState {
    public static final String DELAY_INDEX_KEY = "DeviceScreenState.DelayIndex";

    public enum State {
        Unknown("Unknown"),
        ScreenOn("ScreenOn"),
        ScreenOff("ScreenOff");

        // public static final Integer length = 1 + Expired.ordinal();

        private static final State[][] allowedTransitions = {
            {State.ScreenOn, State.ScreenOff},
            {State.ScreenOff},
            {State.ScreenOn}};

        public final String name;
        public final int ordinal;

        State(String name) {
            this.name = name;
            this.ordinal = this.ordinal();
        }

        public boolean canTransitionTo(State nextState) {
            for (State allowedTransition : allowedTransitions[this.ordinal()]) {
                if (nextState.equals(allowedTransition)) return true;
            }
            return false;
        }
    }

    public static class ScreenStateEvent {
        public final State state;

        public ScreenStateEvent(final State state) {
            this.state = state;
        }
    }

    public static class ScreenStateChangeEvent {
        public final State newState;
        public final State oldState;
        public final Context context;

        public ScreenStateChangeEvent(final State newState, final State oldState, final Context context) {
            this.newState = newState;
            this.oldState = oldState;
            this.context = context;
        }
    }

    private State state = State.Unknown;
    private final Object stateMutex = new Object();
    private final Context context;

    public DeviceScreenState(final Context context) {
        this.context = context;
        EventBus.getDefault().register(this);
    }

    @Subscribe
    public void onScreenState(final ScreenStateEvent event) {
        synchronized (this.stateMutex) {
            if (this.state.canTransitionTo(event.state)) {
                State oldState = this.state;
                this.state = event.state;
                EventBus.getDefault().post(new ScreenStateChangeEvent(event.state, oldState, this.context));
                Teak.log.i("teak.animation", Helpers.mm.h("old_state", oldState, "new_state", event.state));
            }
        }
    }

    private static final int[][] ExecutionWindow = new int[][] {
        {15, 16},
        {5, 10},  // Not an ordering error, this is to prevent the device re-checking if the screen
        {10, 20}, // gets turned on from re-issuing the notification in the case of a state change to OFF
        {20, 30},
        {30, 50},
        {50, 75},
        {75, 150},
        {150, 300},
        {600, 900}};

    public void scheduleScreenStateWork(@Nullable Data data) {
        int delayIndex = 0;
        if (data != null) {
            // Do not schedule if there is no remaining animated notifications
            if (data.getInt(IAndroidNotification.ANIMATED_NOTIFICATION_COUNT_KEY, 0) < 1) {
                return;
            }
            delayIndex = data.getInt(DeviceScreenState.DELAY_INDEX_KEY, -1) + 1;
        }
        delayIndex = Math.max(0, Math.min(delayIndex, ExecutionWindow.length - 1));

        final Data.Builder dataBuilder = new Data.Builder();
        if (data != null) {
            dataBuilder.putAll(data);
        }
        dataBuilder.putInt(DeviceScreenState.DELAY_INDEX_KEY, delayIndex);

        Teak.log.i("teak.animation", Helpers.mm.h("delay_index", delayIndex, "timestamp", new Date().getTime() / 1000));

        int[] executionWindow = DeviceScreenState.ExecutionWindow[delayIndex];
        final OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Sender.class)
                                               .setInputData(dataBuilder.build())
                                               .build();
        WorkManager.getInstance(this.context)
            .enqueueUniqueWork("io.teak.sdk.screenState", ExistingWorkPolicy.REPLACE, request);
    }

    public class DeviceScreenStateWorker extends Worker {
        public DeviceScreenStateWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            boolean isScreenOn = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                final DisplayManager dm = (DisplayManager) this.getApplicationContext().getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null && dm.getDisplays() != null) {
                    for (Display display : dm.getDisplays()) {
                        final int displayState = display.getState();
                        isScreenOn |= (displayState == Display.STATE_ON);
                    }
                }
            } else {
                final PowerManager powerManager = (PowerManager) this.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                @SuppressWarnings("deprecation")
                final boolean suppressDeprecation = powerManager != null && powerManager.isScreenOn();
                isScreenOn = suppressDeprecation;
            }

            EventBus.getDefault().post(new ScreenStateEvent(isScreenOn ? State.ScreenOn : State.ScreenOff));

            // Re-schedule ourselves
            DeviceScreenState.this.scheduleScreenStateWork(this.getInputData());

            return Result.success();
        }
    }
}
