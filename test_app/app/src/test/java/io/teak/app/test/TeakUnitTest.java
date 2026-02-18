package io.teak.app.test;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;

import io.teak.sdk.IObjectFactory;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.core.InstrumentableReentrantLock;
import io.teak.sdk.core.TeakCore;
import io.teak.sdk.io.IAndroidDeviceInfo;
import io.teak.sdk.io.IAndroidNotification;
import io.teak.sdk.io.IAndroidResources;
import io.teak.sdk.push.IPushProvider;
import io.teak.sdk.store.IStore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class TeakUnitTest {
    IStore store;
    IAndroidResources androidResources;
    TestTeakEventListener eventListener;
    IAndroidNotification androidNotification;
    TeakCore teakCore;
    Context context;
    IObjectFactory objectFactory;

    protected static final String TestAppId = "1136371193060244";

    @Before
    public void setupMocksAndTeakConfiguration() throws NoSuchFieldException, IllegalAccessException {
        // Enable lock contention timeout/checks
        InstrumentableReentrantLock.interruptLongLocksAndReport = true;

        // Reset TeakEvent
        TestHelpers.resetTeakEventListeners();

        // Reset TeakConfiguration
        TestHelpers.resetTeakConfiguration();

        // Package Manager mock
        PackageManager packageManager = mock(PackageManager.class);

        // TODO: getInstallerPackageName ?

        // Context mock
        context = mock(Context.class);
        when(context.getPackageName()).thenReturn("io.teak.app.test");
        when(context.getPackageManager()).thenReturn(packageManager);
        when(context.getApplicationContext()).thenReturn(context);

        // TODO: getApplicationInfo - applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE ?

        // Create IStore mock
        store = mock(io.teak.sdk.store.IStore.class);

        // Android Resources mock
        androidResources = mock(io.teak.sdk.io.IAndroidResources.class);
        when(androidResources.getStringResource(AppConfiguration.TEAK_APP_ID_RESOURCE)).thenReturn(TestAppId);
        when(androidResources.getStringResource(AppConfiguration.TEAK_API_KEY_RESOURCE)).thenReturn("1f3850f794b9093864a0778009744d03");

        // Android Device Info mock
        final IAndroidDeviceInfo androidDeviceInfo = mock(IAndroidDeviceInfo.class);
        when(androidDeviceInfo.getDeviceId()).thenReturn("unit_test_mock_device");

        // Android Notification mock
        androidNotification = mock(IAndroidNotification.class);

        // Teak Core
        teakCore = mock(TeakCore.class);

        // Create and add an easily mockable TeakEvent.EventListener
        if (eventListener != null) {
            TeakEvent.removeEventListener(eventListener);
        }
        eventListener = spy(TestTeakEventListener.class);
        TeakEvent.addEventListener(eventListener);

        objectFactory = new IObjectFactory() {
            @Nullable
            @Override
            public IStore getIStore() {
                return store;
            }

            @NonNull
            @Override
            public IAndroidResources getAndroidResources() {
                return androidResources;
            }

            @NonNull
            @Override
            public IAndroidDeviceInfo getAndroidDeviceInfo() {
                return androidDeviceInfo;
            }

            @Nullable
            @Override
            public IPushProvider getPushProvider() {
                return null;
            }
        };

        // Re-initialize TeakConfiguration
        if (!TeakConfiguration.initialize(context, objectFactory)) {
            throw new IllegalArgumentException("TeakConfiguration initialization failed.");
        }
    }
}
