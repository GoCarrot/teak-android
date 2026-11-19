package io.teak.app.test;

import org.junit.Test;

import java.net.URL;

import io.teak.sdk.Request;
import io.teak.sdk.io.DefaultHttpRequest;
import io.teak.sdk.io.IHttpRequest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A server that stops replying can't hold a request, and the single request thread behind it,
 * forever.
 */
public class HttpTimeoutTest extends TeakHttpUnitTest {
    private static URL url(String path) throws Exception {
        return new URL("http", "127.0.0.1", Request.MOCKED_PORT, path);
    }

    @Test
    public void silentServerTimesOutAndTheNextRequestRuns() throws Exception {
        stubFor(post(urlEqualTo("/test/silent")).willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(4000)));
        stubFor(post(urlEqualTo("/test/ok")).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        final long start = System.nanoTime();
        final DefaultHttpRequest http = new DefaultHttpRequest(1000, 300);
        final IHttpRequest.Response timedOut = http.synchronousRequest(url("/test/silent"), "POST", "{}", "sig");
        final IHttpRequest.Response next = http.synchronousRequest(url("/test/ok"), "POST", "{}", "sig");
        final long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertEquals(IHttpRequest.Response.ERROR_RESPONSE, timedOut);
        assertEquals(200, next.statusCode);
        assertTrue("both requests took " + elapsedMs + "ms", elapsedMs < 2000);
    }
}
