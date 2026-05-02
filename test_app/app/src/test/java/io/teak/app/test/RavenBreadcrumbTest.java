package io.teak.app.test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
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
}
