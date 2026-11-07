# License testing the example app (on-device purchase validation)

Goal: drive a **real** Google Play purchase through the example app — without being
charged — so we can confirm Teak's automatic purchase tracking posts a `purchase`
event. The example app now runs its own `BillingClient` (mirroring a game's IAP
library); Teak registers a separate parallel `BillingClient`, and Play delivers the
purchase to Teak's `onPurchasesUpdated`. That's the path under test.

This is Play-Console side setup — mostly a human task, done once.

## Prerequisites (Play Console)

1. **App on a track.** `io.teak.app.unity.dev` must be published to at least the
   Internal testing track. (Already true — the device has a Play-installed build.)
2. **Test product exists + Active.** Monetize → Products → In-app products:
   `io.teak.app.sku.dollar` must exist as a **one-time (INAPP)** product, status
   **Active**. The example queries it as `ProductType.INAPP`.
3. **License testers.** Setup → License testing → add the device's Google account.
   Testers buy through a **test card** ("you will not be charged").
4. **Device account.** The device must be signed into that tester Google account.

## Signing / install nuance (the thing that just bit us)

For Play to resolve the product and allow a test purchase, the installed build has to
be one Play recognizes. Two workable paths for our retooled harness:

- **A — Upload the harness to the Internal track**, install via Play. Highest
  fidelity, slower iteration. Needs a Play Console upload.
- **B — Sign the local build with the app's upload key**, then `installGoogleDebug`
  installs over the Play build and stays recognized. Fast iteration; needs the
  keystore wired into `example/app` signing config.

A plain debug-signed sideload (default) will install if the Play build is removed, but
product queries may return `ITEM_UNAVAILABLE` because Play doesn't recognize the
signature. Don't expect Phase-2 purchases to work from a bare debug sideload.

## Running the test

1. Install a recognized build (path A or B), signed into the tester account.
2. Launch, tap **Test Purchase** (or open deep link `…/store/io.teak.app.sku.dollar`).
3. Complete the test-purchase dialog.
4. Watch: `adb logcat -s Teak Teak.Example`

### Pass criteria

- `Teak.Example`: `Example BillingClient setup: 0`, then `Example client purchase: […]`.
- `Teak`: `billing.google …` SKU/product details retrieved, and a `PurchaseEvent`.
- The `purchase` event lands for Teak app `613659812345256`.

## References

- Test your Play Billing integration: https://developer.android.com/google/play/billing/test
- Billing release notes: https://developer.android.com/google/play/billing/release-notes
