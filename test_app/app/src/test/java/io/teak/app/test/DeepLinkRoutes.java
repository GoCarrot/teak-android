package io.teak.app.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.teak.sdk.Teak;
import io.teak.sdk.core.DeepLink;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class DeepLinkRoutes extends TeakUnitTest {
    @Test
    public void simple() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/foo/:bar/:baz", "Test", "Also test", callback);
        Thread.sleep(10); // sleep to make sure the async happens
        assertTrue(io.teak.sdk.core.DeepLink.routes.containsKey("/foo/([^/]+)/([^/]+)"));

        final URI uri = new URI("teak" + TestAppId + ":///foo/1234/abcd");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("bar", "1234");
        arg.put("baz", "abcd");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void withQuery() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/foo/:bar/:baz", "", "", callback);
        Thread.sleep(10);
        assertTrue(io.teak.sdk.core.DeepLink.routes.containsKey("/foo/([^/]+)/([^/]+)"));

        final URI uri = new URI("teak" + TestAppId + ":///foo/1234/abcd?foo=bar");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("bar", "1234");
        arg.put("baz", "abcd");
        arg.put("foo", "bar");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void queryWithEncodedSpace() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/store/:sku", "", "", callback);
        Thread.sleep(10);

        // %20 encodes a space. This should decode correctly.
        final URI uri = new URI("teak" + TestAppId + ":///store/item123?offer=Spring%20Sale");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("sku", "item123");
        arg.put("offer", "Spring Sale");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void queryWithEncodedAmpersand() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/store/:sku", "", "", callback);
        Thread.sleep(10);

        // %26 encodes a literal '&' inside a value. This should decode correctly.
        final URI uri = new URI("teak" + TestAppId + ":///store/item123?offer=Buy%26Save");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("sku", "item123");
        arg.put("offer", "Buy&Save");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void queryWithPercentInValue() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/store/:sku", "", "", callback);
        Thread.sleep(10);

        // "50% Off" is percent-encoded by the server as 50%25%20Off.
        // %25 encodes literal '%', %20 encodes space.
        final URI uri = new URI("teak" + TestAppId + ":///store/item123?offer=50%25%20Off");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("sku", "item123");
        arg.put("offer", "50% Off");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void queryWithPercentInTeakCreativeName() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/store/:sku", "", "", callback);
        Thread.sleep(10);

        // An email/link launch where teak_creative_name is "50% Off Sale",
        // properly encoded by the server. The non-percent params should still
        // arrive even if the percent-containing one is broken.
        final URI uri = new URI("teak" + TestAppId + ":///store/item123"
            + "?teak_notif_id=99999"
            + "&teak_creative_name=50%25%20Off%20Sale"
            + "&teak_schedule_name=Summer%20Promo");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("sku", "item123");
        arg.put("teak_notif_id", "99999");
        arg.put("teak_creative_name", "50% Off Sale");
        arg.put("teak_schedule_name", "Summer Promo");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void queryWithPercentInTeakScheduleName() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/store/:sku", "", "", callback);
        Thread.sleep(10);

        // Schedule name "100% Boost Weekend" with % in the name.
        final URI uri = new URI("teak" + TestAppId + ":///store/item123"
            + "?teak_notif_id=88888"
            + "&teak_creative_name=Weekend%20Creative"
            + "&teak_schedule_name=100%25%20Boost%20Weekend");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("sku", "item123");
        arg.put("teak_notif_id", "88888");
        arg.put("teak_creative_name", "Weekend Creative");
        arg.put("teak_schedule_name", "100% Boost Weekend");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }

    @Test
    public void queryOverwritesPath() throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/foo/:bar/:baz", "", "", callback);
        Thread.sleep(10);
        assertTrue(io.teak.sdk.core.DeepLink.routes.containsKey("/foo/([^/]+)/([^/]+)"));

        final URI uri = new URI("teak" + TestAppId + ":///foo/1234/abcd?bar=barbar");
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> arg = new HashMap<>();
        arg.put("bar", "barbar");
        arg.put("baz", "abcd");
        arg.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        arg.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(arg);
    }
}
