package io.teak.sdk.store;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

// Guards GooglePlayBilling's enablePendingPurchases version gate (PendingPurchasesParams exists
// on billing 7+, not on 6.x). The gate is classpath-driven rather than argument-driven, so a JVM
// unit test can only exercise the branch matching whatever billing jar is on ITS classpath --
// test_app pins billing to 7.1.1 to match Teak's compileOnly floor, so PendingPurchasesParams
// resolves here. The no-arg fallback (billing 6.x, where the class doesn't exist) is proven
// on-device instead, the same split CreateStoreTest documents for store instantiation.
public class EnablePendingPurchasesTest {
    @Test
    public void pendingPurchasesParams_resolvesOnTheCompileOnlyFloor() {
        assertTrue(GooglePlayBilling.pendingPurchasesParamsAvailable());
    }
}
