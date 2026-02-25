package io.teak.sdk;

import android.content.Context;
import androidx.annotation.NonNull;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.DataCollectionConfiguration;
import io.teak.sdk.configuration.DebugConfiguration;
import io.teak.sdk.configuration.DeviceConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.event.RemoteConfigurationEvent;
import java.util.ArrayList;

public class TeakConfiguration {
    public static boolean initialize(@NonNull Context context, @NonNull IObjectFactory objectFactory) {
        try {
            TeakConfiguration teakConfiguration = new TeakConfiguration(context.getApplicationContext(), objectFactory);

            if (teakConfiguration.deviceConfiguration.deviceId != null) {
                Instance = teakConfiguration;
                synchronized (eventListenersMutex) {
                    for (EventListener e : eventListeners) {
                        e.onConfigurationReady(teakConfiguration);
                    }
                }
            }
        } catch (IntegrationChecker.InvalidConfigurationException e) {
            android.util.Log.e(IntegrationChecker.LOG_TAG, e.getMessage());
        }

        return Instance != null;
    }

    public final DebugConfiguration debugConfiguration;
    public final AppConfiguration appConfiguration;
    public final DeviceConfiguration deviceConfiguration;
    public RemoteConfiguration remoteConfiguration;
    public DataCollectionConfiguration dataCollectionConfiguration;

    private TeakConfiguration(@NonNull Context context, @NonNull IObjectFactory objectFactory) throws IntegrationChecker.InvalidConfigurationException {
        this.debugConfiguration = new DebugConfiguration(context, objectFactory.getAndroidResources());
        this.appConfiguration = new AppConfiguration(context, objectFactory.getAndroidResources());
        this.deviceConfiguration = new DeviceConfiguration(context, objectFactory);
        this.dataCollectionConfiguration = new DataCollectionConfiguration(context, objectFactory.getAndroidResources());
    }

    private static TeakConfiguration Instance;

    public static @NonNull TeakConfiguration get() {
        if (Instance == null) {
            throw new IllegalStateException("Call to TeakConfiguration.get() before initialization.");
        }

        return Instance;
    }

    static {
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (event.eventType.equals(RemoteConfigurationEvent.Type) && Instance != null) {
                    Instance.remoteConfiguration = ((RemoteConfigurationEvent) event).remoteConfiguration;
                }
            }
        });
    }

    ///// Events

    public interface EventListener {
        void onConfigurationReady(@NonNull TeakConfiguration configuration);
    }

    private static final Object eventListenersMutex = new Object();
    private static final ArrayList<EventListener> eventListeners = new ArrayList<>();

    public static void addEventListener(EventListener e) {
        synchronized (eventListenersMutex) {
            if (!eventListeners.contains(e)) {
                eventListeners.add(e);
            }

            // Configuration is already ready, so call it now
            if (Instance != null) {
                e.onConfigurationReady(Instance);
            }
        }
    }
}
