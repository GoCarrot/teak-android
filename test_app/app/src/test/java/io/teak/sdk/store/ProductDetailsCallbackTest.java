package io.teak.sdk.store;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Guards GooglePlayBilling.isProductDetailsCallback. A minified host app renames
// ProductDetailsResponseListener's method (e.g. to "a"), so the proxy must recognize the callback
// by shape, not by name.
public class ProductDetailsCallbackTest {

    // Stands in for the listener after R8 has renamed its single method.
    interface RenamedListener {
        void a(Object billingResult, Object productDetails);
    }

    interface OriginalListener {
        void onProductDetailsResponse(Object billingResult, Object productDetails);
    }

    @Test
    public void renamedMethod_isDispatched() throws Exception {
        final Method method = RenamedListener.class.getMethod("a", Object.class, Object.class);
        assertTrue(GooglePlayBilling.isProductDetailsCallback(method, new Object[] {null, null}));
    }

    @Test
    public void originalName_isDispatched() throws Exception {
        final Method method = OriginalListener.class.getMethod("onProductDetailsResponse", Object.class, Object.class);
        assertTrue(GooglePlayBilling.isProductDetailsCallback(method, new Object[] {null, null}));
    }

    @Test
    public void objectMethods_areNotDispatched() throws Exception {
        assertFalse(GooglePlayBilling.isProductDetailsCallback(Object.class.getMethod("equals", Object.class), new Object[] {null}));
        assertFalse(GooglePlayBilling.isProductDetailsCallback(Object.class.getMethod("hashCode"), null));
    }

    @Test
    public void wrongArity_isNotDispatched() throws Exception {
        final Method method = RenamedListener.class.getMethod("a", Object.class, Object.class);
        assertFalse(GooglePlayBilling.isProductDetailsCallback(method, null));
        assertFalse(GooglePlayBilling.isProductDetailsCallback(method, new Object[] {null}));
    }
}
