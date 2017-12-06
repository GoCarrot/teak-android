package io.teak.app.notification_visuals;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;

import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.test.rule.GrantPermissionRule;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;


import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.event.LifecycleEvent;

import io.teak.sdk.event.UserIdEvent;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;


@RunWith(AndroidJUnit4.class)
public class NotificationImageSize {
    @Rule
    public ActivityTestRule<MainActivity> testRule = new ActivityTestRule<MainActivity>(MainActivity.class, false, false) {
        @Override
        protected void beforeActivityLaunched() {
            super.beforeActivityLaunched();

            // Reset things other tests could be mucking up
            Teak.Instance = null;
        }
    };

    @Rule
    public GrantPermissionRule writeExternalStoragePermission = GrantPermissionRule.grant(Manifest.permission.WRITE_EXTERNAL_STORAGE);

    @Rule
    public GrantPermissionRule readExternalStoragePermission = GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE);

    @Test
    public void sendNotificationAndGetScreenshot() {
        // Event Listener
        TestTeakEventListener eventListener = spy(TestTeakEventListener.class);
        TeakEvent.addEventListener(eventListener);

        // Init
        Activity activity = testRule.launchActivity(null);

        final String creativeId = "test_deeplink";
        final String searchText = "Teak";
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        // Identify user
        String userId = String.format(Locale.US, "notification-%s-%s", creativeId, "test");
        TestTeakEventListener listener = spy(TestTeakEventListener.class);
        TeakEvent.addEventListener(listener);
        Teak.identifyUser(userId);
        verify(listener, timeout(5000).times(1)).eventRecieved(UserIdEvent.class, UserIdEvent.Type);

        // Send notification
        FutureTask<String> notificationTask = TeakNotification.scheduleNotification(creativeId, searchText, 10);
        String response = null;
        try {
            response = notificationTask.get(20000, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        assertNotNull(response);

        // Background app
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        activity.startActivity(i);
        verify(eventListener, timeout(5000).times(1)).eventRecieved(LifecycleEvent.class, LifecycleEvent.Paused);

        // Wait for notification
        UiDevice device = UiDevice.getInstance(instrumentation);
        device.openNotification();
        device.wait(Until.hasObject(By.text(searchText)), 60000);
        UiObject2 title = device.findObject(By.text(searchText));
        assertNotNull(title);

        // Screenshot
        String path = "/sdcard/test-screenshots";
        File file = new File(path);
        if (!file.exists()) {
            assertTrue(file.mkdirs());
        }
        file = new File(file, "teak-" + creativeId + ".png");
        boolean success = UiDevice.getInstance(instrumentation).takeScreenshot(file);
        assertTrue(success);
        android.util.Log.i("Teak.NotificationVisuals", "Wrote screenshot to file: " + file.toString());
    }

    @SuppressWarnings("WeakerAccess") // Must be public in order to mock
    public abstract class TestTeakEventListener implements TeakEvent.EventListener {
        @Override
        public void onNewEvent(@NonNull TeakEvent event) {
            eventRecieved(event.getClass(), event.eventType);
        }

        public abstract void eventRecieved(Class eventClass, String eventType);
    }
}