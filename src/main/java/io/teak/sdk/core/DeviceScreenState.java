package io.teak.sdk.core;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.Date;
import java.util.concurrent.TimeUnit;

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

    private static final String UNIQUE_WORK_NAME = "io.teak.sdk.screenState";

    // The worker runs outside any object graph, so it finds the live instance here
    private static volatile DeviceScreenState Instance = null;

    private State state = State.Unknown;
    private final Object stateMutex = new Object();
    private final Context context;
    private volatile int animatedCount = 0;

    public DeviceScreenState(final Context context) {
        this.context = context;
        EventBus.getDefault().register(this);
        DeviceScreenState.Instance = this;
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

    // Seconds, {earliest, latest}. Indexed by the delay index carried in the work's input data.
    static final int[][] ExecutionWindow = new int[][] {
        {15, 16},
        {5, 10},  // Not an ordering error, this is to prevent the device re-checking if the screen
        {10, 20}, // gets turned on from re-issuing the notification in the case of a state change to OFF
        {20, 30},
        {30, 50},
        {50, 75},
        {75, 150},
        {150, 300},
        {600, 900}};

    // Next link in the polling chain: one step further along ExecutionWindow than the previous
    // link's delay index (null starts the chain), run after a delay drawn from that window.
    static OneTimeWorkRequest buildRequest(@Nullable Data previous) {
        final int delayIndex = Math.max(0, Math.min(previous == null ? 0 : previous.getInt(DELAY_INDEX_KEY, -1) + 1, ExecutionWindow.length - 1));
        final int[] window = ExecutionWindow[delayIndex];
        final long delaySeconds = window[0] + (long) (Math.random() * (window[1] - window[0] + 1));

        return new OneTimeWorkRequest.Builder(DeviceScreenStateWorker.class)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(new Data.Builder().putInt(DELAY_INDEX_KEY, delayIndex).build())
            .build();
    }

    // Starts the polling chain over. At zero the pending work is cancelled, which is what ends
    // the polling once the last animated notification is gone.
    public void scheduleScreenStateWork(int animatedCount) {
        final int previousCount = this.animatedCount;
        this.animatedCount = animatedCount;
        if (animatedCount < 1) {
            if (previousCount > 0) {
                WorkManager.getInstance(this.context).cancelUniqueWork(UNIQUE_WORK_NAME);
            }
            return;
        }
        this.enqueueScreenStateWork(null);
    }

    private void enqueueScreenStateWork(@Nullable Data previous) {
        final OneTimeWorkRequest request = buildRequest(previous);
        Teak.log.i("teak.animation", Helpers.mm.h("delay_index", request.getWorkSpec().input.getInt(DELAY_INDEX_KEY, -1), "timestamp", new Date().getTime() / 1000));
        WorkManager.getInstance(this.context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    public static class DeviceScreenStateWorker extends Worker {
        public DeviceScreenStateWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            // No instance, or nothing animated: a restarted process or a stale chain, so stop here
            final DeviceScreenState instance = DeviceScreenState.Instance;
            if (instance == null || instance.animatedCount < 1) {
                return Result.success();
            }

            try {
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

                // Re-schedule ourselves, unless a dismissal cancelled the work while this ran
                if (instance.animatedCount > 0) {
                    instance.enqueueScreenStateWork(this.getInputData());
                }
                return Result.success();
            } catch (Exception e) {
                Teak.log.exception(e, false);
                return Result.failure();
            }
        }
    }
}
