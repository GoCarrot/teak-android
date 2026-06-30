package io.teak.sdk;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Guards the Amazon push-libs diagnostic: warn only when ADM is the selected provider (an Amazon
// build) AND FCM/Play-Services push libraries are bundled. Non-Amazon (FCM) builds never warn.
public class FcmLibsOnAmazonTest {
    @Test
    public void admSelected_fcmLibsPresent_warns() {
        assertTrue(DefaultObjectFactory.shouldWarnFcmLibsOnAmazon(true, true));
    }

    @Test
    public void admSelected_noFcmLibs_doesNotWarn() {
        assertFalse(DefaultObjectFactory.shouldWarnFcmLibsOnAmazon(true, false));
    }

    @Test
    public void admNotSelected_fcmLibsPresent_doesNotWarn() {
        assertFalse(DefaultObjectFactory.shouldWarnFcmLibsOnAmazon(false, true));
    }

    @Test
    public void admNotSelected_noFcmLibs_doesNotWarn() {
        assertFalse(DefaultObjectFactory.shouldWarnFcmLibsOnAmazon(false, false));
    }
}
