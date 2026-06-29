package io.teak.app.test;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.teak.sdk.DefaultObjectFactory;
import io.teak.sdk.DefaultObjectFactory.PushProvider;
import io.teak.sdk.io.DefaultAndroidDeviceInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class PushProviderSelection {
    ///// Pure selection predicate — the three acceptance cases from C-899 plus edges.

    // Case 1: Amazon device, FCM libs bundled, ADM present/supported -> ADM.
    // (gpsMissing is true on a Fire device, but ADM wins regardless.)
    @Test
    public void amazonWithUsableAdm_selectsAdm() {
        assertEquals(PushProvider.ADM, DefaultObjectFactory.selectPushProvider(true, true, true));
    }

    // Case 2: Amazon device, FCM libs bundled, no usable ADM, GPS definitively missing -> NONE.
    @Test
    public void amazonNoAdmGpsMissing_selectsNone() {
        assertEquals(PushProvider.NONE, DefaultObjectFactory.selectPushProvider(false, true, true));
    }

    // Case 3: Normal Google device with GPS available -> FCM.
    @Test
    public void googleWithGps_selectsFcm() {
        assertEquals(PushProvider.FCM, DefaultObjectFactory.selectPushProvider(false, true, false));
    }

    // ADM is preferred even when GPS is fully available.
    @Test
    public void admWinsOverAvailableGps() {
        assertEquals(PushProvider.ADM, DefaultObjectFactory.selectPushProvider(true, true, false));
    }

    // No push libraries present at all -> NONE.
    @Test
    public void noLibraries_selectsNone() {
        assertEquals(PushProvider.NONE, DefaultObjectFactory.selectPushProvider(false, false, false));
    }

    ///// GPS-missing derivation — only SERVICE_MISSING counts as missing. Locks in that
    ///// transient states (e.g. SERVICE_UPDATING) keep FCM on real Google devices.

    @Test
    @SuppressWarnings("deprecation")
    public void serviceMissing_isMissing_andSelectsNone() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenReturn(ConnectionResult.SERVICE_MISSING);

            assertTrue(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
            assertEquals(PushProvider.NONE,
                DefaultObjectFactory.selectPushProvider(false, true,
                    DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context)));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void serviceUpdating_isNotMissing_andKeepsFcm() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenReturn(ConnectionResult.SERVICE_UPDATING);

            assertFalse(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
            assertEquals(PushProvider.FCM,
                DefaultObjectFactory.selectPushProvider(false, true,
                    DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context)));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void success_isNotMissing() {
        final Context context = mock(Context.class);
        try (MockedStatic<GooglePlayServicesUtil> gps = Mockito.mockStatic(GooglePlayServicesUtil.class)) {
            gps.when(() -> GooglePlayServicesUtil.isGooglePlayServicesAvailable(context))
                .thenReturn(ConnectionResult.SUCCESS);

            assertFalse(DefaultAndroidDeviceInfo.isGooglePlayServicesMissing(context));
        }
    }

    // A throwing availability check is not treated as definitively missing — FCM is not blocked.
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
