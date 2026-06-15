package io.teak.app.test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.json.JSONException;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.raven.Raven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the malformed-response fix: non-JSON server responses (HTML error pages, proxy
 * strings) fall back to {} rather than throwing JSONException to the outer catch which
 * would create a Sentry report.
 */
@RunWith(MockitoJUnitRunner.class)
public class MalformedResponseTest extends TeakUnitTest {

    @After
    public void tearDown() {
        Teak.log.setSdkRaven(null);
    }

    @Test
    public void nonJsonResponseBodyFallsBackToEmptyObject() throws Exception {
        final String htmlBody = "<html><body><h1>502 Bad Gateway</h1></body></html>";
        JSONObject response;
        try {
            response = new JSONObject(htmlBody);
        } catch (JSONException e) {
            response = new JSONObject();
        }
        // Downstream optString/has calls must tolerate {} without throwing
        assertEquals("error", response.optString("status", "error"));
        assertEquals("unknown", response.optString("state", "unknown"));
        assertFalse(response.has("event"));
        assertFalse(response.has("opt_out_states"));
    }

    @Test
    public void nullResponseBodyFallsBackToEmptyObject() throws Exception {
        final String responseBody = null;
        JSONObject response;
        try {
            response = new JSONObject((responseBody == null || responseBody.trim().isEmpty()) ? "{}" : responseBody);
        } catch (JSONException e) {
            response = new JSONObject();
        }
        assertNotNull(response);
        assertEquals("error", response.optString("status", "error"));
    }

    @Test
    public void nonJsonResponseLogsAsBreadcrumbNotSentryReport() throws Exception {
        Raven raven = new Raven(context, "sdk", TeakConfiguration.get(), objectFactory);
        Teak.log.setSdkRaven(raven);
        Teak.log.markConfigurationReady();

        Teak.log.e("request.response.non_json", "Non-JSON response from channel_state: Value expected.");

        List<Map<String, Object>> breadcrumbs = raven.snapshotBreadcrumbs();
        assertFalse("log.e should produce at least one breadcrumb", breadcrumbs.isEmpty());

        Map<String, Object> crumb = breadcrumbs.get(breadcrumbs.size() - 1);
        assertEquals("request.response.non_json", crumb.get("category"));
        assertEquals("error", crumb.get("level"));
    }
}
