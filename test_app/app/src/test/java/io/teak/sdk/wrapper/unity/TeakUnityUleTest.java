package io.teak.sdk.wrapper.unity;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TeakUnityUleTest {

    @Test
    public void ule_direct_isSuppressed() {
        assertTrue(TeakUnity.shouldSuppressException(new UnsatisfiedLinkError()));
    }

    @Test
    public void invocationTargetException_wrappingUle_isSuppressed() {
        assertTrue(TeakUnity.shouldSuppressException(
            new InvocationTargetException(new UnsatisfiedLinkError())));
    }

    @Test
    public void invocationTargetException_wrappingOtherCause_isNotSuppressed() {
        assertFalse(TeakUnity.shouldSuppressException(
            new InvocationTargetException(new RuntimeException("other error"))));
    }

    @Test
    public void genericException_isNotSuppressed() {
        assertFalse(TeakUnity.shouldSuppressException(new RuntimeException("something broke")));
    }
}
