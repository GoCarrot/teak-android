package io.teak.sdk.store;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

// Guards GooglePlayBilling.normalizeProductDetails — the one billing-version-divergent step the
// whole SDK fix hinges on. Billing 7 hands the product-details callback a List<ProductDetails>;
// billing 8+ hands it a QueryProductDetailsResult whose getProductDetailsList() yields the List.
// These fakes stand in for both shapes so the unwrap is verified without a real BillingClient.
public class NormalizeProductDetailsTest {

    // Stands in for billing 8+'s QueryProductDetailsResult: any object exposing a public
    // getProductDetailsList() the reflective unwrap can find.
    public static final class FakeQueryProductDetailsResult {
        private final List<Object> list;

        FakeQueryProductDetailsResult(List<Object> list) {
            this.list = list;
        }

        public List<Object> getProductDetailsList() {
            return this.list;
        }
    }

    @Test
    public void billing7Shape_listPassesThroughUnchanged() {
        final List<Object> list = Arrays.asList(new Object(), new Object());
        // Billing 7: the argument already IS the List, returned as-is.
        assertSame(list, GooglePlayBilling.normalizeProductDetails(list));
    }

    @Test
    public void billing8Shape_resultIsUnwrapped() {
        final List<Object> inner = Arrays.asList(new Object(), new Object(), new Object());
        final List<?> unwrapped = GooglePlayBilling.normalizeProductDetails(new FakeQueryProductDetailsResult(inner));
        // Billing 8+: getProductDetailsList() is invoked reflectively and its List returned.
        assertEquals(inner, unwrapped);
    }

    @Test
    public void nullResponse_returnsNull() {
        assertNull(GooglePlayBilling.normalizeProductDetails(null));
    }

    @Test
    public void unrecognizedShape_returnsNull() {
        // No getProductDetailsList(): the reflective lookup fails and the method degrades to null
        // rather than throwing into the caller.
        assertNull(GooglePlayBilling.normalizeProductDetails("not a billing response"));
    }

    @Test
    public void billing8Shape_emptyList_unwrapsToEmpty() {
        final List<?> unwrapped = GooglePlayBilling.normalizeProductDetails(
            new FakeQueryProductDetailsResult(Collections.emptyList()));
        assertEquals(Collections.emptyList(), unwrapped);
    }
}
