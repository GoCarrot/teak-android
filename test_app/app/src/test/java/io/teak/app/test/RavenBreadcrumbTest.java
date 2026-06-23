package io.teak.app.test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.json.JSONObject;
import io.teak.sdk.raven.Raven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class RavenBreadcrumbTest extends TeakUnitTest {

    @After
    public void tearDown() {
        Teak.log.setSdkRaven(null);
    }

    private Raven makeRaven() {
        return new Raven(context, "sdk", TeakConfiguration.get(), objectFactory);
    }

    @Test
    public void logIFiresBreadcrumb() throws Exception {
        Raven raven = new Raven(context, "sdk", TeakConfiguration.get(), objectFactory);
        Teak.log.setSdkRaven(raven);
        Teak.log.markConfigurationReady();

        Teak.log.i("test.breadcrumb", "hello breadcrumbs");

        List<Map<String, Object>> breadcrumbs = raven.snapshotBreadcrumbs();
        assertFalse(breadcrumbs.isEmpty());

        Map<String, Object> crumb = breadcrumbs.get(breadcrumbs.size() - 1);
        assertEquals("test.breadcrumb", crumb.get("message"));
        assertEquals("test.breadcrumb", crumb.get("category"));
        assertEquals("info", crumb.get("level"));
        assertTrue(crumb.containsKey("timestamp"));
    }

    @Test
    public void breadcrumbCapAt100() throws Exception {
        Raven raven = makeRaven();

        for (int i = 0; i < 110; i++) {
            raven.addBreadcrumb("info", "event." + i, null);
        }

        List<Map<String, Object>> breadcrumbs = raven.snapshotBreadcrumbs();
        assertEquals(100, breadcrumbs.size());
        assertEquals("event.10", breadcrumbs.get(0).get("message"));
        assertEquals("event.109", breadcrumbs.get(99).get("message"));
    }

    @Test
    public void exceptionEventNotAddedAsBreadcrumb() throws Exception {
        Raven raven = makeRaven();
        Teak.log.setSdkRaven(raven);

        Teak.log.exception(new RuntimeException("test"), false);

        List<Map<String, Object>> breadcrumbs = raven.snapshotBreadcrumbs();
        for (Map<String, Object> crumb : breadcrumbs) {
            assertFalse("exception".equals(crumb.get("message")));
        }
    }

    private static Map<String, Object> crumb(int i) {
        final HashMap<String, Object> c = new HashMap<>();
        c.put("timestamp", "2026-06-22T00:00:00");
        c.put("level", "info");
        c.put("category", "event." + i);
        c.put("message", "event." + i);
        return c;
    }

    private static int serializedSize(Map<String, Object> base, List<Map<String, Object>> crumbs) {
        final HashMap<String, Object> trial = new HashMap<>(base);
        final HashMap<String, Object> breadcrumbs = new HashMap<>();
        breadcrumbs.put("values", crumbs);
        trial.put("breadcrumbs", breadcrumbs);
        return new JSONObject(trial).toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static int crumbIndex(Map<String, Object> crumb) {
        return Integer.parseInt(((String) crumb.get("message")).substring("event.".length()));
    }

    // Over budget: keep a non-empty set of the NEWEST crumbs, in chronological order, under budget.
    // This is the C-863 acceptance case — a bounded, non-empty breadcrumb set still ships.
    @Test
    public void budgetTrimsToNewestWithinBudget() {
        final HashMap<String, Object> base = new HashMap<>();
        base.put("level", "error");

        final List<Map<String, Object>> snapshot = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            snapshot.add(crumb(i)); // oldest = event.0 ... newest = event.99
        }

        final int budget = 1024;
        final List<Map<String, Object>> fitted = Raven.fitBreadcrumbsToBudget(base, snapshot, budget);

        assertFalse("must keep a non-empty breadcrumb set", fitted.isEmpty());
        assertTrue("must have trimmed", fitted.size() < snapshot.size());
        assertTrue("must fit budget", serializedSize(base, fitted) <= budget);

        // The kept crumbs are the newest contiguous tail, ending at the newest (event.99), ascending.
        assertEquals(99, crumbIndex(fitted.get(fitted.size() - 1)));
        final int first = crumbIndex(fitted.get(0));
        for (int j = 0; j < fitted.size(); j++) {
            assertEquals(first + j, crumbIndex(fitted.get(j)));
        }
    }

    // Under budget: keep everything, in chronological order.
    @Test
    public void budgetKeepsAllWhenUnderBudget() {
        final HashMap<String, Object> base = new HashMap<>();

        final List<Map<String, Object>> snapshot = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            snapshot.add(crumb(i));
        }

        final List<Map<String, Object>> fitted = Raven.fitBreadcrumbsToBudget(base, snapshot, 64 * 1024);

        assertEquals(5, fitted.size());
        assertEquals(0, crumbIndex(fitted.get(0)));
        assertEquals(4, crumbIndex(fitted.get(4)));
    }

    // Base payload alone already over budget: drop all breadcrumbs (the core report still sends).
    @Test
    public void budgetReturnsEmptyWhenBaseExceedsBudget() {
        final HashMap<String, Object> base = new HashMap<>();
        final StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            huge.append('x');
        }
        base.put("huge", huge.toString());

        final List<Map<String, Object>> snapshot = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            snapshot.add(crumb(i));
        }

        final List<Map<String, Object>> fitted = Raven.fitBreadcrumbsToBudget(base, snapshot, 1024);

        assertTrue(fitted.isEmpty());
    }
}
