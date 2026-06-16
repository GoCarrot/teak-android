package io.teak.sdk.store;

import io.teak.sdk.Teak;

/**
 * Resolves the Amazon billing "sandbox" flag across both Amazon IAP SDKs.
 *
 * IAP v2.0 exposes {@code PurchasingService.IS_SANDBOX_MODE} (a static boolean
 * field). The newer Appstore SDK removed that field and replaced it with
 * {@code LicensingService.getAppstoreSDKMode()}, which returns the String
 * "SANDBOX", "PRODUCTION", or "UNKNOWN".
 *
 * Both are accessed via reflection so this class carries no compile-time
 * dependency on either Amazon SDK -- the Teak AAR links against whichever one
 * the game actually ships, and a direct reference to the removed field would
 * otherwise throw NoSuchFieldError at runtime under the Appstore SDK.
 */
final class AmazonSandbox {
    private AmazonSandbox() {}

    /**
     * Maps an Appstore SDK {@code getAppstoreSDKMode()} value to our boolean
     * sandbox flag. Only "SANDBOX" (any case) counts; "PRODUCTION", "UNKNOWN",
     * null, and anything unexpected map to false.
     */
    static boolean isSandboxMode(String appstoreSDKMode) {
        return "SANDBOX".equalsIgnoreCase(appstoreSDKMode);
    }

    /**
     * Best-effort sandbox detection for whichever Amazon billing SDK is present.
     * Returns false if it cannot be determined.
     */
    static boolean isSandboxMode() {
        // Appstore SDK: IS_SANDBOX_MODE was removed in favor of LicensingService.
        try {
            final Class<?> licensingService = Class.forName("com.amazon.device.drm.LicensingService");
            final Object mode = licensingService.getMethod("getAppstoreSDKMode").invoke(null);
            return isSandboxMode((String) mode);
        } catch (ClassNotFoundException | NoSuchMethodException notAppstoreSDK) {
            // Benign and expected: this isn't the Appstore SDK. LicensingService can
            // still be present from Amazon's standalone DRM lib (which games use for
            // verifyLicense) but without getAppstoreSDKMode -- so fall through to the
            // legacy IS_SANDBOX_MODE field rather than reporting noise per purchase.
        } catch (Exception e) {
            Teak.log.exception(e);
            return false;
        }

        // Legacy IAP v2.0: PurchasingService.IS_SANDBOX_MODE static boolean field.
        try {
            final Object isSandbox = Class.forName("com.amazon.device.iap.PurchasingService")
                                         .getField("IS_SANDBOX_MODE")
                                         .get(null);
            return Boolean.TRUE.equals(isSandbox);
        } catch (Exception e) {
            Teak.log.exception(e);
            return false;
        }
    }
}
