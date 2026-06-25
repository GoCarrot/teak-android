package io.teak.sdk.push;

import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransientFcmErrorTest {

    @Test
    public void timeoutException_isTransient() {
        assertTrue(FCMPushProvider.isTransientFcmError(new TimeoutException()));
    }

    @Test
    public void executionException_wrappingTimeout_isTransient() {
        assertTrue(FCMPushProvider.isTransientFcmError(new ExecutionException(new TimeoutException())));
    }

    @Test
    public void executionException_wrappingServiceNotAvailable_isTransient() {
        assertTrue(FCMPushProvider.isTransientFcmError(
            new ExecutionException(new Exception("SERVICE_NOT_AVAILABLE"))));
    }

    @Test
    public void serviceNotAvailable_isTransient() {
        assertTrue(FCMPushProvider.isTransientFcmError(new Exception("SERVICE_NOT_AVAILABLE")));
    }

    @Test
    public void missingInstanceIdService_isTransient() {
        assertTrue(FCMPushProvider.isTransientFcmError(new Exception("MISSING_INSTANCEID_SERVICE")));
    }

    @Test
    public void fisAuthError_isTransient() {
        assertTrue(FCMPushProvider.isTransientFcmError(new Exception("FIS_AUTH_ERROR")));
    }

    @Test
    public void genericRuntimeException_isNotTransient() {
        assertFalse(FCMPushProvider.isTransientFcmError(new RuntimeException("something broke")));
    }

    @Test
    public void executionException_wrappingRealError_isNotTransient() {
        assertFalse(FCMPushProvider.isTransientFcmError(
            new ExecutionException(new RuntimeException("bad sender id"))));
    }

    @Test
    public void illegalArgumentException_isNotTransient() {
        // IAE was excluded — could indicate misconfiguration (bad google-services.json etc.)
        assertFalse(FCMPushProvider.isTransientFcmError(new IllegalArgumentException("bad arg")));
    }

    @Test
    public void partialCodeMatch_isNotTransient() {
        // Substring of a known code should NOT match (we use equals, not contains)
        assertFalse(FCMPushProvider.isTransientFcmError(new Exception("NOT_SERVICE_NOT_AVAILABLE")));
    }
}
