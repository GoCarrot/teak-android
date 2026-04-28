package io.teak.app.test;

import java.util.ArrayList;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

import net.jodah.concurrentunit.Waiter;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.event.RemoteConfigurationEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.mockito.Mockito.mock;

public class TeakHttpUnitTest extends TeakUnitTest {
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(Request.MOCKED_PORT);

    @BeforeClass
    public static void setupWireMock() {
        // RemoteConfiguration for mocking
        Request.registerStaticEventListeners();
        final AppConfiguration appConfiguration = mock(AppConfiguration.class);
        final RemoteConfiguration remoteConfiguration =
                new RemoteConfiguration(appConfiguration,
                        "127.0.0.1",
                        null,
                        null,
                        "mock_gcm_sender_id",
                        "mock_firebase_app_id",
                        false,
                        false,
                        null,
                        null,
                        600,
                        io.teak.sdk.core.RewardClaimManager.DEFAULT_INITIAL_DELAY_MS,
                        io.teak.sdk.core.RewardClaimManager.DEFAULT_CEILING_MS,
                        new ArrayList<Teak.Channel.Category>(),
                        true);
        TeakEvent.postEvent(new RemoteConfigurationEvent(remoteConfiguration));
    }

    @Before
    public void resetWireMock() {
        WireMock.reset();
    }
}
