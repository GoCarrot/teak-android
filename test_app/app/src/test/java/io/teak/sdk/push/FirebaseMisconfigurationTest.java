package io.teak.sdk.push;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.teak.sdk.IntegrationChecker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// Guards the Firebase invalid-state gate by exercising the real report path: a missing FirebaseApp
// raises the google.fcm.null_app integration error only when push is expected. Apps that opt out of
// push (io_teak_enable_push_key=false) ship no Firebase config by design and must stay silent.
// Reverting the gate to report unconditionally fails the pushDisabled case.
public class FirebaseMisconfigurationTest {
    @Test
    public void missingApp_pushEnabled_reportsIntegrationError() {
        try (MockedStatic<IntegrationChecker> ic = Mockito.mockStatic(IntegrationChecker.class)) {
            FCMPushProvider.reportMissingFirebaseApp(true);
            ic.verify(() -> IntegrationChecker.addErrorToReport(eq("google.fcm.null_app"), anyString()), times(1));
        }
    }

    @Test
    public void missingApp_pushDisabled_doesNotReport() {
        try (MockedStatic<IntegrationChecker> ic = Mockito.mockStatic(IntegrationChecker.class)) {
            FCMPushProvider.reportMissingFirebaseApp(false);
            ic.verify(() -> IntegrationChecker.addErrorToReport(any(), any()), never());
        }
    }
}
