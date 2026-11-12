package io.teak.sdk.core;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkerParameters;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.teak.sdk.core.DeviceScreenState.DeviceScreenStateWorker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class DeviceScreenStateTest {
    @Test
    public void request_runsTheScreenStateWorker() {
        final OneTimeWorkRequest request = DeviceScreenState.buildRequest(null);
        assertEquals(DeviceScreenStateWorker.class.getName(), request.getWorkSpec().workerClassName);
    }

    // WorkManager's default factory instantiates workers reflectively, so an inner class
    // (implicit outer-instance parameter) or a non-public constructor can never be created
    @Test
    public void worker_isConstructibleByWorkManager() throws NoSuchMethodException {
        assertTrue(Modifier.isStatic(DeviceScreenStateWorker.class.getModifiers()));
        assertTrue(Modifier.isPublic(DeviceScreenStateWorker.class.getModifiers()));
        assertNotNull(DeviceScreenStateWorker.class.getConstructor(Context.class, WorkerParameters.class));
    }

    @Test
    public void request_isDelayedWithinTheWindowAndWalksTheChain() {
        Data data = null;
        for (int expectedIndex = 0; expectedIndex < DeviceScreenState.ExecutionWindow.length + 2; expectedIndex++) {
            final int index = Math.min(expectedIndex, DeviceScreenState.ExecutionWindow.length - 1);
            final int[] window = DeviceScreenState.ExecutionWindow[index];

            final OneTimeWorkRequest request = DeviceScreenState.buildRequest(data);
            assertEquals(index, request.getWorkSpec().input.getInt(DeviceScreenState.DELAY_INDEX_KEY, -1));

            final long delayMs = request.getWorkSpec().initialDelay;
            assertTrue("delay " + delayMs + "ms outside window " + window[0] + "-" + window[1] + "s",
                delayMs >= TimeUnit.SECONDS.toMillis(window[0]) && delayMs <= TimeUnit.SECONDS.toMillis(window[1] + 1) - 1);
            data = request.getWorkSpec().input;
        }
    }

    public static class ScreenStateCounter {
        final AtomicInteger count = new AtomicInteger();

        @Subscribe
        public void onScreenState(DeviceScreenState.ScreenStateEvent event) {
            count.incrementAndGet();
        }
    }

    // A chain left over from a dismissed notification, or from before a process restart, must end
    @Test
    public void worker_doesNothingWhenNothingIsAnimated() {
        final DeviceScreenState deviceScreenState = new DeviceScreenState(mock(Context.class));
        deviceScreenState.scheduleScreenStateWork(0);

        final ScreenStateCounter counter = new ScreenStateCounter();
        EventBus.getDefault().register(counter);
        try {
            final DeviceScreenStateWorker worker = new DeviceScreenStateWorker(mock(Context.class), mock(WorkerParameters.class));
            assertEquals(ListenableWorker.Result.success(), worker.doWork());
            assertEquals(0, counter.count.get());
        } finally {
            EventBus.getDefault().unregister(counter);
            EventBus.getDefault().unregister(deviceScreenState);
        }
    }
}
