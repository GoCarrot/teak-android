package io.teak.app.test;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

import net.jodah.concurrentunit.Waiter;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Helpers;
import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.raven.Raven;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * WireMock round-trip test: HTML body from a real (mocked) endpoint survives the
 * Helpers.fromResponseBody path without hitting Sentry.
 */
@RunWith(MockitoJUnitRunner.class)
public class MalformedResponseHttpTest extends TeakHttpUnitTest {

    @After
    public void tearDownRaven() {
        Teak.log.setSdkRaven(null);
    }

    @Test
    public void htmlBodyFromEndpointFallsBackToEmptyJsonAndLogsBreadcrumb() throws Throwable {
        stubFor(post(urlEqualTo("/me/channel_state.json"))
                    .willReturn(aResponse()
                                    .withStatus(200)
                                    .withBody("<html><body><h1>502 Bad Gateway</h1></body></html>")));

        Raven raven = new Raven(context, "sdk", TeakConfiguration.get(), objectFactory);
        Teak.log.setSdkRaven(raven);
        Teak.log.markConfigurationReady();

        Waiter waiter = new Waiter();

        Request.submit(null, "POST", "/me/channel_state.json", new HashMap<>(), Session.NullSession,
            (responseCode, responseBody) -> {
                // This is the exact call used in TeakInstance.setChannelState
                JSONObject response = Helpers.fromResponseBody(responseBody, "channel_state");

                // Callback must complete gracefully — no exception, error defaults applied
                assertEquals("error", response.optString("status", "error"));
                assertFalse(response.has("state"));

                // log.e fired a Raven breadcrumb, not a Sentry report
                List<Map<String, Object>> breadcrumbs = raven.snapshotBreadcrumbs();
                boolean hasBreadcrumb = false;
                for (Map<String, Object> crumb : breadcrumbs) {
                    if ("request.response.non_json".equals(crumb.get("category"))) {
                        hasBreadcrumb = true;
                        assertEquals("error", crumb.get("level"));
                        break;
                    }
                }
                assertTrue("Non-JSON response should produce a breadcrumb", hasBreadcrumb);

                waiter.resume();
            });

        waiter.await(5, TimeUnit.SECONDS);
    }
}
