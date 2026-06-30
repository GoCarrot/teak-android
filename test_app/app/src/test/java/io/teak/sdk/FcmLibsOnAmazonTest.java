package io.teak.sdk;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// Guards the Amazon push-libs diagnostic by exercising the real report path: bundled FCM/Play-Services
// libraries on an ADM (Amazon) build raise the integration warning, and a build without them stays
// silent. Reverting the gate to warn unconditionally fails the no-libs case.
public class FcmLibsOnAmazonTest {
    @Test
    public void fcmLibsPresent_reportsWarning() {
        try (MockedStatic<IntegrationChecker> ic = Mockito.mockStatic(IntegrationChecker.class)) {
            DefaultObjectFactory.warnIfFcmLibsBundledOnAmazon(true);
            ic.verify(() -> IntegrationChecker.addErrorToReport(eq("push.amazon.fcm_libs_bundled"), anyString()), times(1));
        }
    }

    @Test
    public void noFcmLibs_doesNotReportWarning() {
        try (MockedStatic<IntegrationChecker> ic = Mockito.mockStatic(IntegrationChecker.class)) {
            DefaultObjectFactory.warnIfFcmLibsBundledOnAmazon(false);
            ic.verify(() -> IntegrationChecker.addErrorToReport(any(), any()), never());
        }
    }
}
