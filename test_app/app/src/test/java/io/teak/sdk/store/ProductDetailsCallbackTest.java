package io.teak.sdk.store;

import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.QueryProductDetailsResult;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Guards GooglePlayBilling.isProductDetailsCallback. A minified host app can rename
// ProductDetailsResponseListener's method (e.g. to "a"), so the proxy recognizes the callback by
// signature, not by name.
public class ProductDetailsCallbackTest {

    // Stand-ins for the listener after R8 has renamed its method, on each billing shape.
    interface RenamedBilling7Listener {
        void a(BillingResult billingResult, List<Object> productDetails);
    }

    interface RenamedBilling8Listener {
        void a(BillingResult billingResult, QueryProductDetailsResult result);
    }

    interface OtherListener {
        void a(BillingResult billingResult, String other);

        void b(Object billingResult, List<Object> productDetails);

        void c(BillingResult billingResult);
    }

    @Test
    public void renamedBilling7Signature_isDispatched() throws Exception {
        assertTrue(GooglePlayBilling.isProductDetailsCallback(RenamedBilling7Listener.class.getMethod("a", BillingResult.class, List.class)));
    }

    @Test
    public void renamedBilling8Signature_isDispatched() throws Exception {
        assertTrue(GooglePlayBilling.isProductDetailsCallback(RenamedBilling8Listener.class.getMethod("a", BillingResult.class, QueryProductDetailsResult.class)));
    }

    @Test
    public void otherSignatures_areNotDispatched() throws Exception {
        assertFalse(GooglePlayBilling.isProductDetailsCallback(OtherListener.class.getMethod("a", BillingResult.class, String.class)));
        assertFalse(GooglePlayBilling.isProductDetailsCallback(OtherListener.class.getMethod("b", Object.class, List.class)));
        assertFalse(GooglePlayBilling.isProductDetailsCallback(OtherListener.class.getMethod("c", BillingResult.class)));
    }
}
