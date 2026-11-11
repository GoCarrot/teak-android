package io.teak.sdk.store;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.QueryPurchasesParams;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A PurchaseEvent only posts from the product-details callback, so the number of
// queryProductDetailsAsync calls is the number of purchases reported.
public class QueryPurchasesTest {
    private static final BillingResult OK = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build();

    private final BillingClient client = mock(BillingClient.class);
    private final GooglePlayBilling billing = new GooglePlayBilling(client);

    private static Purchase purchase(String token, int state, long purchaseTime) {
        final Purchase purchase = mock(Purchase.class);
        when(purchase.getPurchaseToken()).thenReturn(token);
        when(purchase.getPurchaseState()).thenReturn(state);
        when(purchase.getPurchaseTime()).thenReturn(purchaseTime);
        when(purchase.getProducts()).thenReturn(Collections.singletonList("sku"));
        return purchase;
    }

    private static Purchase purchased(String token) {
        return purchase(token, Purchase.PurchaseState.PURCHASED, System.currentTimeMillis());
    }

    // Runs a resume-triggered query and answers it with the given result.
    private void query(BillingResult result, List<Purchase> purchases) {
        when(client.isReady()).thenReturn(true);
        billing.onResumed();
        final ArgumentCaptor<PurchasesResponseListener> listener = ArgumentCaptor.forClass(PurchasesResponseListener.class);
        verify(client, atLeastOnce()).queryPurchasesAsync(any(QueryPurchasesParams.class), listener.capture());
        listener.getValue().onQueryPurchasesResponse(result, purchases);
    }

    @After
    public void neverChangesPurchaseState() {
        verify(client, never()).consumeAsync(any(), any());
        verify(client, never()).acknowledgePurchase(any(), any());
    }

    @Test
    public void queriedPurchase_isReportedOnce() {
        final List<Purchase> owned = Collections.singletonList(purchased("t1"));
        query(OK, owned);
        verify(client, times(1)).queryProductDetailsAsync(any(), any());
        query(OK, owned);
        verify(client, times(1)).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void purchaseFromListener_isNotReportedAgainByQuery() {
        final Purchase p = purchased("t1");
        billing.onPurchasesUpdated(OK, Collections.singletonList(p));
        query(OK, Collections.singletonList(p));
        verify(client, times(1)).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void distinctPurchases_areEachReported() {
        query(OK, Arrays.asList(purchased("t1"), purchased("t2")));
        verify(client, times(2)).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void pendingQueriedPurchase_isNotReported() {
        query(OK, Collections.singletonList(purchase("t1", Purchase.PurchaseState.PENDING, System.currentTimeMillis())));
        verify(client, never()).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void queriedPurchaseOlderThanCutoff_isNotReported() {
        final long old = System.currentTimeMillis() - GooglePlayBilling.QUERIED_PURCHASE_MAX_AGE_MS - TimeUnit.MINUTES.toMillis(1);
        query(OK, Collections.singletonList(purchase("t1", Purchase.PurchaseState.PURCHASED, old)));
        verify(client, never()).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void queriedPurchaseInsideCutoff_isReported() {
        final long recent = System.currentTimeMillis() - GooglePlayBilling.QUERIED_PURCHASE_MAX_AGE_MS + TimeUnit.MINUTES.toMillis(1);
        query(OK, Collections.singletonList(purchase("t1", Purchase.PurchaseState.PURCHASED, recent)));
        verify(client, times(1)).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void failedQuery_reportsNothing() {
        final BillingResult error = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE).build();
        query(error, Collections.singletonList(purchased("t1")));
        verify(client, never()).queryProductDetailsAsync(any(), any());
    }

    @Test
    public void setupFinished_queriesPurchases() {
        billing.onBillingSetupFinished(OK);
        verify(client, times(1)).queryPurchasesAsync(any(QueryPurchasesParams.class), any());
    }

    @Test
    public void resumeAfterServiceDisconnect_reconnectsInsteadOfQuerying() {
        billing.onBillingServiceDisconnected();
        when(client.isReady()).thenReturn(false);
        when(client.getConnectionState()).thenReturn(BillingClient.ConnectionState.DISCONNECTED);
        billing.onResumed();
        verify(client, times(1)).startConnection(billing);
        verify(client, never()).queryPurchasesAsync(any(QueryPurchasesParams.class), any());
    }

    @Test
    public void resumeAfterFailedSetup_doesNotReconnect() {
        billing.onBillingSetupFinished(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE).build());
        when(client.isReady()).thenReturn(false);
        when(client.getConnectionState()).thenReturn(BillingClient.ConnectionState.DISCONNECTED);
        billing.onResumed();
        verify(client, never()).startConnection(any());
    }

    @Test
    public void failedReconnect_isRetriedOnNextResume() {
        billing.onBillingServiceDisconnected();
        when(client.isReady()).thenReturn(false);
        when(client.getConnectionState()).thenReturn(BillingClient.ConnectionState.DISCONNECTED);
        billing.onResumed();
        billing.onBillingSetupFinished(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE).build());
        billing.onResumed();
        verify(client, times(2)).startConnection(billing);
    }

    @Test
    public void resumeWhileConnecting_doesNothing() {
        billing.onBillingServiceDisconnected();
        when(client.isReady()).thenReturn(false);
        when(client.getConnectionState()).thenReturn(BillingClient.ConnectionState.CONNECTING);
        billing.onResumed();
        verify(client, never()).startConnection(any());
        verify(client, never()).queryPurchasesAsync(any(QueryPurchasesParams.class), any());
    }
}
