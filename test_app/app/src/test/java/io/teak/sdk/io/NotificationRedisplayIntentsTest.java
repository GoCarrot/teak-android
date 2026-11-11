package io.teak.sdk.io;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

public class NotificationRedisplayIntentsTest {
    private int originalSdkInt;

    private Context context;
    private final PendingIntent activityIntent = mock(PendingIntent.class);
    private final PendingIntent broadcastIntent = mock(PendingIntent.class);

    @Before
    public void setUp() throws Exception {
        originalSdkInt = Build.VERSION.SDK_INT;

        final PackageManager packageManager = mock(PackageManager.class);
        when(packageManager.getLaunchIntentForPackage("test_package_name")).thenReturn(mock(Intent.class));
        context = mock(Context.class);
        when(context.getPackageName()).thenReturn("test_package_name");
        when(context.getPackageManager()).thenReturn(packageManager);
    }

    // SDK_INT is a static final, which Field.setInt can't change on a modern JDK. sun.misc.Unsafe
    // isn't on the android.jar compile classpath, so reach it by reflection.
    private static void setSdkInt(int value) throws Exception {
        final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        final Object unsafe = theUnsafe.get(null);
        final Field sdkInt = Build.VERSION.class.getField("SDK_INT");
        final Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, sdkInt);
        final long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, sdkInt);
        final Method putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
        putInt.invoke(unsafe, base, offset, value);
    }

    @After
    public void restoreSdkInt() throws Exception {
        setSdkInt(originalSdkInt);
    }

    private Notification refresh(int sdkInt, MockedStatic<PendingIntent> pendingIntent) throws Exception {
        setSdkInt(sdkInt);
        pendingIntent.when(() -> PendingIntent.getActivity(any(Context.class), anyInt(), any(Intent.class), anyInt())).thenReturn(activityIntent);
        pendingIntent.when(() -> PendingIntent.getBroadcast(any(Context.class), anyInt(), any(Intent.class), anyInt())).thenReturn(broadcastIntent);

        final Notification notification = new Notification();
        DefaultAndroidNotification.refreshIntents(context, notification, new Bundle());
        return notification;
    }

    @Test
    public void contentIntentLaunchesActivityOnApi30() throws Exception {
        try (MockedStatic<PendingIntent> pendingIntent = Mockito.mockStatic(PendingIntent.class)) {
            final Notification notification = refresh(Build.VERSION_CODES.R, pendingIntent);
            assertSame(activityIntent, notification.contentIntent);
            pendingIntent.verify(() -> PendingIntent.getBroadcast(any(Context.class), anyInt(), any(Intent.class), anyInt()), times(1));
        }
    }

    @Test
    public void contentIntentLaunchesActivityOnApi31() throws Exception {
        try (MockedStatic<PendingIntent> pendingIntent = Mockito.mockStatic(PendingIntent.class)) {
            final Notification notification = refresh(Build.VERSION_CODES.S, pendingIntent);
            assertSame(activityIntent, notification.contentIntent);
            pendingIntent.verify(() -> PendingIntent.getBroadcast(any(Context.class), anyInt(), any(Intent.class), anyInt()), times(1));
        }
    }

    @Test
    public void contentIntentIsBroadcastBelowApi30() throws Exception {
        try (MockedStatic<PendingIntent> pendingIntent = Mockito.mockStatic(PendingIntent.class)) {
            final Notification notification = refresh(Build.VERSION_CODES.Q, pendingIntent);
            assertSame(broadcastIntent, notification.contentIntent);
            pendingIntent.verify(() -> PendingIntent.getActivity(any(Context.class), anyInt(), any(Intent.class), anyInt()), never());
        }
    }

    @Test
    public void deleteIntentStaysBroadcast() throws Exception {
        try (MockedStatic<PendingIntent> pendingIntent = Mockito.mockStatic(PendingIntent.class)) {
            final Notification notification = refresh(Build.VERSION_CODES.S, pendingIntent);
            assertSame(broadcastIntent, notification.deleteIntent);
        }
    }
}
