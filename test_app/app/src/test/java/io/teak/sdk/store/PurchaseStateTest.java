package io.teak.sdk.store;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;

import org.junit.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A PurchaseEvent only posts from the product-details callback, so a purchase that never reaches
// queryProductDetailsAsync is never reported.
public class PurchaseStateTest {

    private static void deliver(BillingClient client, int purchaseState) {
        final Purchase purchase = mock(Purchase.class);
        when(purchase.getPurchaseState()).thenReturn(purchaseState);
        when(purchase.getProducts()).thenReturn(Collections.singletonList("sku"));

        final BillingResult ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build();
        new GooglePlayBilling(client).onPurchasesUpdated(ok, Collections.singletonList(purchase));
    }

    @Test
    public void purchased_isReported() {
        final BillingClient client = mock(BillingClient.class);
        deliver(client, Purchase.PurchaseState.PURCHASED);
        verify(client, times(1)).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void pending_isNotReported() {
        final BillingClient client = mock(BillingClient.class);
        deliver(client, Purchase.PurchaseState.PENDING);
        verify(client, never()).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void unspecified_isNotReported() {
        final BillingClient client = mock(BillingClient.class);
        deliver(client, Purchase.PurchaseState.UNSPECIFIED_STATE);
        verify(client, never()).queryProductDetailsAsync(any(), any());
    }
}
