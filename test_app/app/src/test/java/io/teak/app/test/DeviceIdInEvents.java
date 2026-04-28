package io.teak.app.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.HashMap;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.ChannelStatus;
import io.teak.sdk.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class DeviceIdInEvents extends TeakUnitTest {

    private RemoteConfiguration createMockRemoteConfiguration() {
        return new RemoteConfiguration(
            TeakConfiguration.get().appConfiguration,
            "gocarrot.com", null, null, null, null,
            false, false, null, null, 60,
            io.teak.sdk.core.RewardClaimManager.DEFAULT_INITIAL_DELAY_MS,
            io.teak.sdk.core.RewardClaimManager.DEFAULT_CEILING_MS,
            new ArrayList<Teak.Channel.Category>(), true);
    }

    @Test
    public void configurationDataEvent_toJSON_containsDeviceId() {
        RemoteConfiguration remoteConfig = createMockRemoteConfiguration();
        Teak.ConfigurationDataEvent event = new Teak.ConfigurationDataEvent(remoteConfig, "unit_test_mock_device");

        JSONObject json = event.toJSON();

        assertTrue("ConfigurationDataEvent JSON should contain deviceId key",
            json.has("deviceId"));
        assertEquals("ConfigurationDataEvent deviceId should match device configuration",
            "unit_test_mock_device", json.getString("deviceId"));
    }

    @Test
    public void userDataEvent_toJSON_containsDeviceId() {
        Teak.UserDataEvent event = new Teak.UserDataEvent(
            new JSONObject(),
            ChannelStatus.Unknown,
            ChannelStatus.Unknown,
            ChannelStatus.Unknown,
            new HashMap<String, String>(),
            "unit_test_mock_device");

        JSONObject json = event.toJSON();

        assertTrue("UserDataEvent JSON should contain deviceId key",
            json.has("deviceId"));
        assertEquals("UserDataEvent deviceId should match device configuration",
            "unit_test_mock_device", json.getString("deviceId"));
    }
}
