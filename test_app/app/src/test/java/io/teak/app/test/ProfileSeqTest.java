package io.teak.app.test;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Reads profile_seq and context off the /me/profile wire body.
 */
public class ProfileSeqTest extends TeakHttpUnitTest {
    // Shaped like the context Carrot signs: ASCII-escaped, fixed key order. The server verifies
    // the signature, so the SDK must send these characters back exactly as received.
    private static final String CONTEXT = "{\"string\":{\"tier\":\"\"},\"number\":{},\"write_epoch\":\"0123456789abcdef0123456789abcdef\","
        + "\"session_id\":\"caf\\u00e9\\/\\\"s\\\"\","
        + "\"sig\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}";

    private RemoteConfiguration originalRemoteConfiguration;

    @Before
    public void configureEndpoints() throws Exception {
        Request.setTeakApiKey("test_teak_api_key");
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        originalRemoteConfiguration = (RemoteConfiguration) field.get(null);

        final JSONObject endpoints = new JSONObject("{\"127.0.0.1\":{\"/me/profile\":{\"batch\":{\"time\":0}}}}");
        final RemoteConfiguration remoteConfiguration =
            new RemoteConfiguration(mock(AppConfiguration.class), "127.0.0.1", null, null,
                "mock_gcm_sender_id", "mock_firebase_app_id", false, false, endpoints, null,
                600, new ArrayList<Teak.Channel.Category>(), true);
        field.set(null, remoteConfiguration);

        stubFor(post(urlEqualTo("/me/profile")).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    @After
    public void restoreRemoteConfiguration() throws Exception {
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        field.set(null, originalRemoteConfiguration);
    }

    // Built from a users.json reply the way Session does
    private static UserProfile profileFromUsersJson() throws Exception {
        final JSONObject reply = new JSONObject("{\"user_profile\":{\"context\":" + JSONObject.quote(CONTEXT)
            + ",\"string_attributes\":{\"tier\":null},\"number_attributes\":{}}}");
        final Constructor<UserProfile> constructor = UserProfile.class.getDeclaredConstructor(Session.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Session.NullSession, reply.optJSONObject("user_profile").toMap());
    }

    private static List<LoggedRequest> awaitSends(int count) throws Exception {
        final long deadline = System.currentTimeMillis() + 5000;
        List<LoggedRequest> requests;
        do {
            requests = findAll(postRequestedFor(urlEqualTo("/me/profile")));
            if (requests.size() >= count) break;
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(count, requests.size());
        return requests;
    }

    @Test
    public void overlappingSendsCarryConsecutiveSeq() throws Exception {
        final UserProfile profile = profileFromUsersJson();

        profile.setStringAttribute("tier", "gold");
        awaitSends(1);
        profile.setStringAttribute("tier", "platinum");
        final List<LoggedRequest> sends = awaitSends(2);

        // Raw body check: an integer, not "1" or 1.0
        assertTrue(sends.get(0).getBodyAsString(), sends.get(0).getBodyAsString().matches(".*\"profile_seq\":1[,}].*"));
        assertTrue(sends.get(1).getBodyAsString(), sends.get(1).getBodyAsString().matches(".*\"profile_seq\":2[,}].*"));
    }

    // 4.3-stable never resends a profile after a 5xx, so this re-runs Request.run on the sent
    // object, which is what a retry does, to show the resend doesn't take a new number.
    @Test
    public void resendKeepsSeq() throws Throwable {
        final UserProfile profile = profileFromUsersJson();

        profile.setStringAttribute("tier", "gold");
        awaitSends(1);

        final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Request.class, MethodHandles.lookup());
        final MethodHandle superRun = lookup.findSpecial(Request.class, "run", MethodType.methodType(void.class), Request.class);
        superRun.invoke((Request) profile);

        final List<LoggedRequest> sends = awaitSends(2);
        assertEquals(new JSONObject(sends.get(0).getBodyAsString()).getInt("profile_seq"),
            new JSONObject(sends.get(1).getBodyAsString()).getInt("profile_seq"));
        assertEquals(1, new JSONObject(sends.get(1).getBodyAsString()).getInt("profile_seq"));
    }

    @Test
    public void contextGoesOutByteIdentical() throws Exception {
        final UserProfile profile = profileFromUsersJson();

        profile.setStringAttribute("tier", "gold");
        final String body = awaitSends(1).get(0).getBodyAsString();

        // Compare the wire text of the string, not a re-parsed value
        assertTrue(body, body.contains("\"context\":" + JSONObject.quote(CONTEXT)));
        assertEquals(CONTEXT, new JSONObject(body).getString("context"));
    }
}
