package io.teak.sdk.push;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Guards the Firebase invalid-state gate: an absent FirebaseApp is an integration error only when
// push is expected. Apps that opt out of push (io_teak_enable_push_key=false) ship no Firebase
// configuration by design, so a missing app is not a misconfiguration for them.
public class FirebaseMisconfigurationTest {
    @Test
    public void noApp_pushEnabled_isMisconfiguration() {
        assertTrue(FCMPushProvider.isFirebaseMisconfiguration(false, true));
    }

    @Test
    public void noApp_pushDisabled_isByDesign() {
        assertFalse(FCMPushProvider.isFirebaseMisconfiguration(false, false));
    }

    @Test
    public void appPresent_pushEnabled_isNotMisconfiguration() {
        assertFalse(FCMPushProvider.isFirebaseMisconfiguration(true, true));
    }

    @Test
    public void appPresent_pushDisabled_isNotMisconfiguration() {
        assertFalse(FCMPushProvider.isFirebaseMisconfiguration(true, false));
    }
}
