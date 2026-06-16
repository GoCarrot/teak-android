package io.teak.sdk.store;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Appstore SDK's getAppstoreSDKMode() returns a String, not an enum, so the mode->boolean
 * mapping is the part most likely to silently misclassify a sandbox purchase as production. Only
 * "SANDBOX" (any case) is sandbox; everything else — including UNKNOWN and null — is not.
 *
 * Lives in io.teak.sdk.store to reach the package-private mapping. The reflection plumbing in
 * AmazonSandbox can't be unit-tested without the Amazon jars on the classpath.
 */
public class AmazonSandboxTest {
    @Test
    public void sandbox_isSandbox() {
        assertTrue(AmazonSandbox.isSandboxMode("SANDBOX"));
    }

    @Test
    public void sandbox_isCaseInsensitive() {
        assertTrue(AmazonSandbox.isSandboxMode("sandbox"));
        assertTrue(AmazonSandbox.isSandboxMode("Sandbox"));
    }

    @Test
    public void production_isNotSandbox() {
        assertFalse(AmazonSandbox.isSandboxMode("PRODUCTION"));
    }

    @Test
    public void unknown_isNotSandbox() {
        assertFalse(AmazonSandbox.isSandboxMode("UNKNOWN"));
    }

    @Test
    public void nullMode_isNotSandbox() {
        assertFalse(AmazonSandbox.isSandboxMode(null));
    }

    @Test
    public void emptyMode_isNotSandbox() {
        assertFalse(AmazonSandbox.isSandboxMode(""));
    }

    @Test
    public void garbageMode_isNotSandbox() {
        assertFalse(AmazonSandbox.isSandboxMode("not-a-real-mode"));
    }

    @Test
    public void paddedSandbox_isNotSandbox() {
        // No trimming — documents that we match the value verbatim (modulo case).
        assertFalse(AmazonSandbox.isSandboxMode(" SANDBOX "));
    }
}
