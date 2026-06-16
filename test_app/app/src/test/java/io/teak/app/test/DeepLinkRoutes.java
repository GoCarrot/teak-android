package io.teak.app.test;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.teak.sdk.Helpers;
import io.teak.sdk.Teak;
import io.teak.sdk.core.DeepLink;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // CGI.escape on the server encodes spaces as '+'.
    @Test
    public void queryWithPlusForSpace() throws Exception {
        assertQueryDecoding("/store/item123?offer=Summer+Sale",
            "sku", "item123", "offer", "Summer Sale");
    }

    // %20 encodes a space.
    @Test
    public void queryWithEncodedSpace() throws Exception {
        assertQueryDecoding("/store/item123?offer=Spring%20Sale",
            "sku", "item123", "offer", "Spring Sale");
    }

    // %26 encodes a literal '&' inside a value.
    @Test
    public void queryWithEncodedAmpersand() throws Exception {
        assertQueryDecoding("/store/item123?offer=Buy%26Save",
            "sku", "item123", "offer", "Buy&Save");
    }

    // %25 encodes literal '%', %20 encodes space. "50% Off" → 50%25%20Off
    @Test
    public void queryWithPercentInValue() throws Exception {
        assertQueryDecoding("/store/item123?offer=50%25%20Off",
            "sku", "item123", "offer", "50% Off");
    }

    // teak_creative_name "50% Off Sale" with other teak params alongside.
    @Test
    public void queryWithPercentInTeakCreativeName() throws Exception {
        assertQueryDecoding("/store/item123"
                + "?teak_notif_id=99999"
                + "&teak_creative_name=50%25%20Off%20Sale"
                + "&teak_schedule_name=Summer%20Promo",
            "sku", "item123",
            "teak_notif_id", "99999",
            "teak_creative_name", "50% Off Sale",
            "teak_schedule_name", "Summer Promo");
    }

    // teak_schedule_name "100% Boost Weekend" with % in the name.
    @Test
    public void queryWithPercentInTeakScheduleName() throws Exception {
        assertQueryDecoding("/store/item123"
                + "?teak_notif_id=88888"
                + "&teak_creative_name=Weekend%20Creative"
                + "&teak_schedule_name=100%25%20Boost%20Weekend",
            "sku", "item123",
            "teak_notif_id", "88888",
            "teak_creative_name", "Weekend Creative",
            "teak_schedule_name", "100% Boost Weekend");
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

    // A bare '%' (e.g. an unencoded "50%" creative name) makes URI.create throw
    // "Malformed escape pair". willProcessUri must swallow that and return false,
    // matching processUri, rather than letting it propagate. C-735.
    @Test
    public void willProcessUriWithUnencodedPercentDoesNotThrow() {
        final Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn("teak" + TestAppId + ":///deep_link?teak_creative_name=50%+Off");
        assertFalse(DeepLink.willProcessUri(uri));
    }

    /**
     * Register a /store/:sku route, process a deep link, and verify the callback
     * receives the expected key/value pairs.
     */
    private void assertQueryDecoding(String pathAndQuery, Object... keysAndValues) throws Exception {
        io.teak.sdk.core.DeepLink.routes.clear();

        final Teak.DeepLink callback = mock(Teak.DeepLink.class);
        Teak.registerDeepLink("/store/:sku", "", "", callback);
        Thread.sleep(10);

        final URI uri = new URI("teak" + TestAppId + "://" + pathAndQuery);
        assertNotNull(uri);
        assertTrue(io.teak.sdk.core.DeepLink.processUri(uri));

        final Map<String, Object> expected = Helpers.mm.h(keysAndValues);
        expected.put(DeepLink.INCOMING_URL_PATH_KEY, uri.getPath());
        expected.put(DeepLink.INCOMING_URL_KEY, uri.toString());
        verify(callback, timeout(100)).call(expected);
    }
}
