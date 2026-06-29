package io.teak.app.test;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.teak.sdk.io.DefaultAndroidDeviceInfo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

// Guards the push-provider gate: only SERVICE_MISSING (no Play Services APK) counts as missing, so
// transient states such as SERVICE_UPDATING keep FCM on real Google devices that merely need an update.
public class GooglePlayServicesMissing {
    @Test
    @SuppressWarnings("deprecation")
    public void serviceMissing_isMissing() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenReturn(ConnectionResult.SERVICE_MISSING);
            assertTrue(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void serviceUpdating_isNotMissing() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenReturn(ConnectionResult.SERVICE_UPDATING);
            assertFalse(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void serviceAvailable_isNotMissing() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenReturn(ConnectionResult.SUCCESS);
            assertFalse(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void availabilityCheckThrows_isNotMissing() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenThrow(new RuntimeException("boom"));
            assertFalse(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
        }
    }
}
