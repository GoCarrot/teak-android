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
 * A request that gives up before a reply still completes its caller's callback, with the same
 * 599 reply a dropped connection gets.
 */
public class RequestCallbackTest extends TeakHttpUnitTest {
    private RemoteConfiguration originalRemoteConfiguration;

    @Before
    public void configureEndpoints() throws Exception {
        Request.setTeakApiKey("test_teak_api_key");
        final Field field = Request.class.getDeclaredField("remoteConfiguration");
        field.setAccessible(true);
        originalRemoteConfiguration = (RemoteConfiguration) field.get(null);

        final JSONObject endpoints = new JSONObject("{\"127.0.0.1\":{"
                                                    + "\"/test/blackhole\":{\"blackhole\":true},"
                                                    + "\"/me/events\":{\"blackhole\":true,\"batch\":{\"time\":0,\"count\":50}}"
                                                    + "}}");
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

    private static class Recorder implements Request.Callback {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile int code;
        volatile String body;

        @Override
        public void onRequestCompleted(int responseCode, String responseBody) {
            this.code = responseCode;
            this.body = responseBody;
            this.calls.incrementAndGet();
            this.latch.countDown();
        }

        void assertErrorReply() throws Exception {
            assertTrue("callback never ran", latch.await(5, TimeUnit.SECONDS));
            assertEquals(599, code);
            assertEquals("{}", body);
        }
    }

    @Test
    public void blackholedRequestCompletesWithErrorReply() throws Exception {
        final Recorder recorder = new Recorder();
        Request.submit(null, "POST", "/test/blackhole", new HashMap<>(), Session.NullSession, recorder);

        recorder.assertErrorReply();
        assertEquals(0, findAll(postRequestedFor(urlEqualTo("/test/blackhole"))).size());
    }

    @Test
    public void blackholedBatchCompletesEveryCallback() throws Exception {
        final Recorder recorder = new Recorder();
        final HashMap<String, Object> event = new HashMap<>();
        event.put("action_type", "test");
        event.put("object_type", "test");
        Request.submit(null, "POST", "/me/events", event, Session.NullSession, recorder);

        recorder.assertErrorReply();
    }

    @Test
    public void sendThatThrowsCompletesWithErrorReplyOnce() throws Exception {
        // A report_client_error that isn't a map throws a ClassCastException inside the send
        stubFor(post(urlEqualTo("/test/throws")).willReturn(aResponse().withStatus(200).withBody("{\"report_client_error\":\"oops\"}")));

        final Recorder recorder = new Recorder();
        Request.submit(null, "POST", "/test/throws", new HashMap<>(), Session.NullSession, recorder);

        recorder.assertErrorReply();
        Thread.sleep(200);
        assertEquals(1, recorder.calls.get());
    }

    @Test
    public void sendThatThrowsNullPointerCompletesWithErrorReply() throws Exception {
        // A null report_client_error throws a NullPointerException inside the send
        stubFor(post(urlEqualTo("/test/npe")).willReturn(aResponse().withStatus(200).withBody("{\"report_client_error\":null}")));

        final Recorder recorder = new Recorder();
        Request.submit(null, "POST", "/test/npe", new HashMap<>(), Session.NullSession, recorder);

        recorder.assertErrorReply();
    }

    @Test
    public void failureWhileSigningCompletesWithErrorReply() throws Exception {
        final String apiKey = "test_teak_api_key";
        Request.setTeakApiKey(null);
        try {
            stubFor(post(urlEqualTo("/test/signing")).willReturn(aResponse().withStatus(200).withBody("{}")));

            final Recorder recorder = new Recorder();
            Request.submit(null, "POST", "/test/signing", new HashMap<>(), Session.NullSession, recorder);

            recorder.assertErrorReply();
            assertEquals(0, findAll(postRequestedFor(urlEqualTo("/test/signing"))).size());
        } finally {
            Request.setTeakApiKey(apiKey);
        }
    }

    @Test
    public void callbackThatThrowsIsNotCalledAgain() throws Exception {
        stubFor(post(urlEqualTo("/test/ok")).willReturn(aResponse().withStatus(200).withBody("{}")));

        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        Request.submit(null, "POST", "/test/ok", new HashMap<>(), Session.NullSession, (code, body) -> {
            calls.incrementAndGet();
            latch.countDown();
            throw new RuntimeException("caller bug");
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertEquals(1, calls.get());
    }
}
