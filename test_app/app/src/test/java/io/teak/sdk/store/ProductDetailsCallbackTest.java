package io.teak.sdk.store;

import com.android.billingclient.api.BillingResult;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Guards GooglePlayBilling.isProductDetailsCallback: the proxy only prices a purchase for the
// listener's own method, which proguard.txt keeps by name.
public class ProductDetailsCallbackTest {

    interface Listener {
        void onProductDetailsResponse(BillingResult billingResult, List<Object> productDetails);

        void a(BillingResult billingResult, List<Object> productDetails);

        void onProductDetailsResponse(BillingResult billingResult);
    }

    @Test
    public void listenerMethod_isDispatched() throws Exception {
        assertTrue(GooglePlayBilling.isProductDetailsCallback(Listener.class.getMethod("onProductDetailsResponse", BillingResult.class, List.class)));
    }

    @Test
    public void otherMethods_areNotDispatched() throws Exception {
        assertFalse(GooglePlayBilling.isProductDetailsCallback(Listener.class.getMethod("a", BillingResult.class, List.class)));
        assertFalse(GooglePlayBilling.isProductDetailsCallback(Listener.class.getMethod("onProductDetailsResponse", BillingResult.class)));
    }
}
