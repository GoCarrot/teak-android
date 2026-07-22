package io.teak.sdk.store;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.PendingPurchasesParams;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Guards GooglePlayBilling's enablePendingPurchases version gate (PendingPurchasesParams exists
// on billing 7+, not on 6.x). The boolean is injected rather than read live so both selections get
// a real regression guard against a mocked Builder -- CI always runs against the 7.1.1 compileOnly
// floor, so the live probe (pendingPurchasesParamsAvailable(), covered separately below) can only
// ever exercise "present" there.
public class EnablePendingPurchasesTest {
    @Test
    public void available_choosesTypedOverload() {
        final BillingClient.Builder builder = mock(BillingClient.Builder.class);
        when(builder.enablePendingPurchases(any(PendingPurchasesParams.class))).thenReturn(builder);

        GooglePlayBilling.enablePendingPurchases(builder, true);

        verify(builder).enablePendingPurchases(any(PendingPurchasesParams.class));
        verify(builder, never()).enablePendingPurchases();
    }

    @Test
    public void unavailable_choosesNoArgOverload() {
        final BillingClient.Builder builder = mock(BillingClient.Builder.class);
        when(builder.enablePendingPurchases()).thenReturn(builder);

        GooglePlayBilling.enablePendingPurchases(builder, false);

        verify(builder).enablePendingPurchases();
        verify(builder, never()).enablePendingPurchases(any(PendingPurchasesParams.class));
    }

    @Test
    public void pendingPurchasesParams_resolvesOnTheCompileOnlyFloor() {
        assertTrue(GooglePlayBilling.pendingPurchasesParamsAvailable());
    }
}
