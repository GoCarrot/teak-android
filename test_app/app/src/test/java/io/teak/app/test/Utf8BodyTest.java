package io.teak.app.test;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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
import io.teak.sdk.json.JSONObject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * A player property with non-ASCII text goes out as UTF-8, with a Content-Length that matches.
 */
public class Utf8BodyTest extends TeakHttpUnitTest {
    private static final String VALUE = "é 漢字 😀";

    private RemoteConfiguration originalRemoteConfiguration;

    @Before
    public void configureEndpoints() throws Exception {
        Request.setTeakApiKey("test_teak_api_key");
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        originalRemoteConfiguration = (RemoteConfiguration) field.get(null);

        final JSONObject endpoints = new JSONObject("{\"127.0.0.1\":{\"/me/profile\":{\"batch\":{\"time\":0}}}}");
        setRemoteConfiguration(new RemoteConfiguration(mock(AppConfiguration.class), "127.0.0.1", null, null,
            "mock_gcm_sender_id", "mock_firebase_app_id", false, false, endpoints, null,
            600, new ArrayList<Teak.Channel.Category>(), true));
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

    @Test
    public void nonAsciiStringAttributeReachesTheServerAsUtf8() throws Exception {
        stubFor(post(urlEqualTo("/me/profile")).willReturn(aResponse().withStatus(200).withBody("{}")));

        final Map<String, Object> stringAttributes = new HashMap<>();
        stringAttributes.put("name", "");
        final Map<String, Object> profile = new HashMap<>();
        profile.put("context", "test");
        profile.put("string_attributes", stringAttributes);
        profile.put("number_attributes", new HashMap<String, Object>());

        final Constructor<UserProfile> constructor = UserProfile.class.getDeclaredConstructor(Session.class, Map.class);
        constructor.setAccessible(true);
        constructor.newInstance(Session.NullSession, profile).setStringAttribute("name", VALUE);

        final long deadline = System.currentTimeMillis() + 5000;
        List<LoggedRequest> requests;
        do {
            requests = findAll(postRequestedFor(urlEqualTo("/me/profile")));
            if (!requests.isEmpty()) break;
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(1, requests.size());

        final byte[] body = requests.get(0).getBody();
        final JSONObject sent = new JSONObject(new String(body, StandardCharsets.UTF_8));
        assertEquals(VALUE, sent.getJSONObject("string_attributes").getString("name"));
        assertEquals(String.valueOf(body.length), requests.get(0).getHeader("Content-Length"));
    }
}
