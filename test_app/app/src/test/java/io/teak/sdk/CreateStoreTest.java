package io.teak.sdk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import io.teak.sdk.store.IStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CreateStoreTest {

    private Context createMockContext(Boolean noAutoTrack) throws PackageManager.NameNotFoundException {
        Context context = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(context.getPackageName()).thenReturn("io.teak.app.test");

        ApplicationInfo appInfo = new ApplicationInfo();
        Bundle metaData = mock(Bundle.class);
        if (noAutoTrack != null) {
            when(metaData.getBoolean("io_teak_no_auto_track_purchase", false)).thenReturn(noAutoTrack);
        }
        appInfo.metaData = metaData;
        when(packageManager.getApplicationInfo("io.teak.app.test", PackageManager.GET_META_DATA))
            .thenReturn(appInfo);

        return context;
    }

    @Test
    public void autoTrackDisabled_returnsNull() throws Exception {
        Context context = createMockContext(true);
        IStore store = DefaultObjectFactory.createStore(context);
        assertNull("Store should be null when auto-track purchase is disabled", store);
    }

    @Test
    public void nullPackageManager_returnsNull() throws Exception {
        Context context = mock(Context.class);
        when(context.getPackageManager()).thenReturn(null);
        when(context.getPackageName()).thenReturn("io.teak.app.test");

        IStore store = DefaultObjectFactory.createStore(context);
        assertNull("Store should be null when PackageManager is null", store);
    }

    @Test
    public void nullBundleId_returnsNull() throws Exception {
        Context context = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(context.getPackageName()).thenReturn(null);

        IStore store = DefaultObjectFactory.createStore(context);
        assertNull("Store should be null when bundle ID is null", store);
    }

    // Selection is asserted rather than instantiation: building a real BillingClient requires the
    // Android runtime, absent under plain-JVM unit tests. selectStoreClass covers the teak logic
    // (gating + which store class); on-device runs cover instantiation.
    @Test
    public void noMetadataKey_selectsGooglePlayBilling() throws Exception {
        Context context = createMockContext(null);
        Class<?> storeClass = DefaultObjectFactory.selectStoreClass(context);
        assertEquals("GooglePlayBilling should be selected when billing library is present and auto-track is not disabled",
            io.teak.sdk.store.GooglePlayBilling.class, storeClass);
    }

    @Test
    public void autoTrackExplicitlyEnabled_selectsGooglePlayBilling() throws Exception {
        Context context = createMockContext(false);
        Class<?> storeClass = DefaultObjectFactory.selectStoreClass(context);
        assertEquals("GooglePlayBilling should be selected when auto-track is explicitly enabled",
            io.teak.sdk.store.GooglePlayBilling.class, storeClass);
    }
}
