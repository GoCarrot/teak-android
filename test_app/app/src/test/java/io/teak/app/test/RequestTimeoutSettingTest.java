package io.teak.app.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.io.DefaultHttpRequest;
import io.teak.sdk.json.JSONObject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The per-game request_timeout_ms from settings.json becomes the read timeout of the next request.
 */
public class RequestTimeoutSettingTest extends TeakHttpUnitTest {
    private static final int DEFAULT_MS = DefaultHttpRequest.DEFAULT_READ_TIMEOUT_MS;

    private RemoteConfiguration originalRemoteConfiguration;

    @Before
    public void saveRemoteConfiguration() throws Exception {
        Request.setTeakApiKey("test_teak_api_key");
        originalRemoteConfiguration = (RemoteConfiguration) remoteConfigurationField().get(null);
    }

    @After
    public void restoreRemoteConfiguration() throws Exception {
        remoteConfigurationField().set(null, originalRemoteConfiguration);
    }

    private static Field remoteConfigurationField() throws Exception {
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        return field;
    }

    private static RemoteConfiguration configFromSettings(String settingsJson) {
        return RemoteConfiguration.fromSettings(mock(AppConfiguration.class), new JSONObject(settingsJson));
    }

    @Test
    public void settingsReplyWithTimeoutIsParsed() {
        assertEquals(5000, configFromSettings("{\"request_timeout_ms\":5000}").requestTimeoutMs);
    }

    @Test
    public void absentOrInvalidTimeoutKeepsTheDefault() {
        assertEquals(DEFAULT_MS, configFromSettings("{}").requestTimeoutMs);
        assertEquals(DEFAULT_MS, configFromSettings("{\"request_timeout_ms\":null}").requestTimeoutMs);
        assertEquals(DEFAULT_MS, configFromSettings("{\"request_timeout_ms\":\"abc\"}").requestTimeoutMs);
        assertEquals(DEFAULT_MS, configFromSettings("{\"request_timeout_ms\":\"5000\"}").requestTimeoutMs);
        assertEquals(DEFAULT_MS, configFromSettings("{\"request_timeout_ms\":0}").requestTimeoutMs);
        assertEquals(DEFAULT_MS, configFromSettings("{\"request_timeout_ms\":-1}").requestTimeoutMs);
        assertEquals(DEFAULT_MS, configFromSettings("{\"request_timeout_ms\":true}").requestTimeoutMs);
    }

    private static class Recorder implements Request.Callback {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger code = new AtomicInteger();

        @Override
        public void onRequestCompleted(int responseCode, String responseBody) {
            code.set(responseCode);
            latch.countDown();
        }
    }

    // The server replies after 1s: a request whose read timeout is shorter fails with the 599
    // error reply, one on the default succeeds.
    private int sendToSlowServer(RemoteConfiguration configuration) throws Exception {
        remoteConfigurationField().set(null, configuration);
        stubFor(post(urlEqualTo("/test/slow")).willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(1000)));
        final Recorder recorder = new Recorder();
        Request.submit(null, "POST", "/test/slow", new HashMap<>(), Session.NullSession, recorder);
        assertTrue("callback never ran", recorder.latch.await(10, TimeUnit.SECONDS));
        return recorder.code.get();
    }

    private static RemoteConfiguration mockedConfig(JSONObject settings) {
        return new RemoteConfiguration(mock(AppConfiguration.class), "127.0.0.1", null, null,
            "mock_gcm_sender_id", "mock_firebase_app_id", false, false, null, null,
            600, new ArrayList<Teak.Channel.Category>(), true, RemoteConfiguration.parseRequestTimeoutMs(settings));
    }

    @Test
    public void configuredTimeoutIsTheNextRequestsReadTimeout() throws Exception {
        assertEquals(599, sendToSlowServer(mockedConfig(new JSONObject("{\"request_timeout_ms\":300}"))));
    }

    @Test
    public void defaultTimeoutAppliesWhenSettingIsAbsent() throws Exception {
        assertEquals(200, sendToSlowServer(mockedConfig(new JSONObject("{}"))));
    }

    @Test
    public void defaultTimeoutAppliesBeforeSettingsArrive() throws Exception {
        // Without a configuration there's no mocked host to send to, so check the timeout itself.
        remoteConfigurationField().set(null, null);
        assertEquals(DEFAULT_MS, Request.readTimeoutMs());
    }
}
