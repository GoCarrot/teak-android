package io.teak.app.test;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.core.UserProfile;
import io.teak.sdk.event.TrackEventEvent;
import io.teak.sdk.json.JSONObject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.Mockito.mock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reads request_id off the wire. Endpoint settings are keyed to the mocked hostname.
 */
public class RequestIdTest extends TeakHttpUnitTest {
    private RemoteConfiguration originalRemoteConfiguration;

    @Before
    public void configureEndpoints() throws Exception {
        Request.setTeakApiKey("test_teak_api_key");
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        originalRemoteConfiguration = (RemoteConfiguration) field.get(null);

        final JSONObject retry = new JSONObject("{\"times\":[0],\"jitter\":0}");
        final JSONObject endpoints = new JSONObject("{\"127.0.0.1\":{"
            + "\"/me/events\":{\"batch\":{\"time\":0,\"count\":50},\"retry\":" + retry.toString() + "},"
            + "\"/me/profile\":{\"batch\":{\"time\":0}}"
            + "}}");

        final RemoteConfiguration remoteConfiguration =
            new RemoteConfiguration(mock(AppConfiguration.class), "127.0.0.1", null, null,
                "mock_gcm_sender_id", "mock_firebase_app_id", false, false, endpoints, null,
                600, new ArrayList<Teak.Channel.Category>(), true);
        setRemoteConfiguration(remoteConfiguration);
    }

    @After
    public void restoreRemoteConfiguration() throws Exception {
        setRemoteConfiguration(originalRemoteConfiguration);
    }

    private static void setRemoteConfiguration(RemoteConfiguration remoteConfiguration) throws Exception {
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        field.set(null, remoteConfiguration);
    }

    private static List<String> awaitRequestIds(String path, int count) throws Exception {
        final long deadline = System.currentTimeMillis() + 5000;
        List<LoggedRequest> requests;
        do {
            requests = findAll(postRequestedFor(urlEqualTo(path)));
            if (requests.size() >= count) break;
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);

        assertEquals("requests to " + path, count, requests.size());
        final List<String> ids = new ArrayList<>();
        for (LoggedRequest request : requests) {
            final JSONObject body = new JSONObject(request.getBodyAsString());
            assertTrue("no request_id in " + request.getBodyAsString(), body.has("request_id"));
            ids.add(body.getString("request_id"));
        }
        return ids;
    }

    @Test
    public void plainRequestSendsRequestId() throws Exception {
        stubFor(post(urlEqualTo("/test/plain")).willReturn(aResponse().withStatus(200).withBody("{}")));

        Request.submit(null, "POST", "/test/plain", new HashMap<>(), Session.NullSession, null);

        final List<String> ids = awaitRequestIds("/test/plain", 1);
        assertTrue(ids.get(0).matches("[0-9a-f]{32}"));
    }

    @Test
    public void eventBatchSendsRequestId() throws Exception {
        stubFor(post(urlEqualTo("/me/events")).willReturn(aResponse().withStatus(200).withBody("{}")));

        final Map<String, Object> event = new HashMap<>();
        event.put("action_type", "test");
        event.put("object_type", "test");
        Request.submit(null, "POST", "/me/events", event, Session.NullSession, null);

        final List<String> ids = awaitRequestIds("/me/events", 1);
        assertTrue(ids.get(0).matches("[0-9a-f]{32}"));
    }

    @Test
    public void eventBatchRetryAfterServerErrorKeepsRequestId() throws Exception {
        stubFor(post(urlEqualTo("/me/events")).inScenario("retry")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("recovered"));
        stubFor(post(urlEqualTo("/me/events")).inScenario("retry")
                    .whenScenarioStateIs("recovered")
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

        Request.submit(null, "POST", "/me/events", TrackEventEvent.payloadForEvent("test", "test", null, 5), Session.NullSession, null);

        final List<String> ids = awaitRequestIds("/me/events", 2);
        assertEquals(ids.get(0), ids.get(1));
    }

    @Test
    public void sentEventBatchTakesNoMoreEvents() throws Exception {
        stubFor(post(urlEqualTo("/me/events")).willReturn(aResponse().withStatus(200).withBody("{}")));

        final Class<?> batchedTrackEvent = Class.forName("io.teak.sdk.Request$BatchedTrackEventRequest");
        final Method getCurrentBatch = batchedTrackEvent.getDeclaredMethod("getCurrentBatch", String.class, Session.class);
        getCurrentBatch.setAccessible(true);
        final Object batch = getCurrentBatch.invoke(null, "127.0.0.1", Session.NullSession);

        final Method add = batchedTrackEvent.getDeclaredMethod("add", String.class, Map.class, Request.Callback.class);
        add.setAccessible(true);
        assertTrue((Boolean) add.invoke(batch, "/me/events", TrackEventEvent.payloadForEvent("test", "test", null, 5), null));
        awaitRequestIds("/me/events", 1);

        // Same event again, through the now-sent batch
        assertFalse((Boolean) add.invoke(batch, "/me/events", TrackEventEvent.payloadForEvent("test", "test", null, 5), null));

        final Field contentsField = Class.forName("io.teak.sdk.Request$BatchedRequest").getDeclaredField("batchContents");
        contentsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> contents = (List<Map<String, Object>>) contentsField.get(batch);
        assertEquals(1, contents.size());
        assertEquals(1, ((Number) contents.get(0).get(TrackEventEvent.CountKey)).intValue());
    }

    @Test
    public void eachProfileSendHasItsOwnRequestId() throws Exception {
        stubFor(post(urlEqualTo("/me/profile")).willReturn(aResponse().withStatus(200).withBody("{}")));

        final Map<String, Object> stringAttributes = new HashMap<>();
        stringAttributes.put("tier", "");
        final Map<String, Object> profile = new HashMap<>();
        profile.put("context", "test");
        profile.put("string_attributes", stringAttributes);
        profile.put("number_attributes", new HashMap<String, Object>());

        final Constructor<UserProfile> constructor = UserProfile.class.getDeclaredConstructor(Session.class, Map.class);
        constructor.setAccessible(true);
        final UserProfile userProfile = constructor.newInstance(Session.NullSession, profile);

        userProfile.setStringAttribute("tier", "gold");
        final List<String> first = awaitRequestIds("/me/profile", 1);

        userProfile.setStringAttribute("tier", "platinum");
        final List<String> both = awaitRequestIds("/me/profile", 2);

        assertEquals(first.get(0), both.get(0));
        assertNotEquals(both.get(0), both.get(1));
    }
}
