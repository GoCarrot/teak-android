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

import io.teak.sdk.Log;
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

    // C-740 regression: an event logged before configuration is ready (before the Raven exists) is
    // queued and must replay into the Raven as a breadcrumb when setSdkRaven() is called. A fresh
    // Log reproduces the pre-configuration window deterministically -- the singleton Teak.log is
    // already past it. Reverting the fix (draining in the config listener with a null Raven) drops
    // the event, leaving it absent from the snapshot, and fails this test.
    @Test
    public void preConfigurationEventReplaysAsBreadcrumbWhenRavenIsSet() {
        final Log log = new Log("Teak.Test", 0);

        // Logged before the Raven exists -- queued, not yet a breadcrumb.
        log.i("pre.config.event", "fired before the raven existed");

        final Raven raven = makeRaven();
        log.setSdkRaven(raven);

        boolean found = false;
        for (Map<String, Object> crumb : raven.snapshotBreadcrumbs()) {
            if ("pre.config.event".equals(crumb.get("category"))) {
                assertEquals("info", crumb.get("level"));
                found = true;
                break;
            }
        }
        assertTrue("pre-configuration log event must replay into the raven as a breadcrumb", found);
    }

    // C-740 (C-863 positive-proof): the null-Raven fallback. TeakInstance hands setSdkRaven a
    // possibly-null Raven from a try/finally, so the queue still drains if Raven construction throws
    // -- otherwise the queued pre-config events would be silently lost when Teak.onCreate swallows
    // the exception and the SDK runs on disabled. With a null Raven the events must still flush
    // through logEvent (observed here via a LogListener); breadcrumbs are skipped because there is
    // no Raven to receive them.
    @Test
    public void nullRavenStillFlushesQueuedEventsAsLogs() {
        final Log log = new Log("Teak.Test", 0);

        final List<String> flushed = new ArrayList<>();
        log.setLogListener(new Teak.LogListener() {
            @Override
            public void logEvent(String logEvent, String logLevel, Map<String, Object> logData) {
                if ("pre.config.event".equals(logEvent)) {
                    flushed.add(logEvent);
                }
            }
        });

        log.i("pre.config.event", "fired before the raven existed");
        assertTrue("queued event must not flush before setSdkRaven drains the queue", flushed.isEmpty());

        // Raven-construction-failed path: a null Raven must still drain the queue.
        log.setSdkRaven(null);

        assertEquals("queued event must flush as a log even with a null raven", 1, flushed.size());
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

        // Maximality: the next-older crumb that was dropped would have pushed the real serialized
        // size over budget. The <=budget assert above catches under-keeping; this catches
        // over-keeping (a comma/wrapper off-by-one in the size algebra leaving room it shouldn't).
        final List<Map<String, Object>> oneMore = new ArrayList<>();
        oneMore.add(crumb(first - 1));
        oneMore.addAll(fitted);
        assertTrue("dropping a crumb that would have fit", serializedSize(base, oneMore) > budget);
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
