# Amazon sandbox-detection verification

Turn-key harness for verifying `AmazonSandbox.isSandboxMode()` on a real Amazon device across both
Amazon IAP SDKs. The pure `isSandboxMode(String)` mapping is already unit-tested
(`test_app/.../store/AmazonSandboxTest.java`); what needs a device is the reflection dispatch —
which SDK is present, reading the mode, and the value landing in the purchase payload.

The harness is the `amazon` product flavor of this example app. Its launcher
(`AmazonSandboxActivity`) identifies a Teak user, verifies the Appstore SDK license at launch, and
gives you one button that starts an Amazon purchase. Teak's registered listener does the rest.

> **Runtime values not verified here.** This doc and the harness were built and compile-verified, but
> no real purchase was run — the `is_sandbox` values below (and the appstore3x / v2-drm runtime
> behavior) are what *you* confirm on-device once the jars are dropped in.

## What you read off each run (two observables)

Both are Teak logcat lines (Android tag `Teak`, JSON payload). A `Debug` build auto-enables Teak
logging, so no extra flag is needed. The harness's own diagnostics (purchase started, verifyLicense
result) log under tag `Teak.Example`, so watch both tags:

```
adb logcat -s Teak:I Teak.Example:I
# or narrow it:
adb logcat | grep -E "billing.amazon.v2|purchase.succeeded|request.send|is_sandbox|sandboxMode"
```

1. **Register-time** — `event_type: "billing.amazon.v2"`, message "Amazon In-App Purchasing 2.0
   registered.", with `sandboxMode` in the payload. Emitted once when Teak builds the Amazon store.
2. **Purchase-time** — `event_type: "purchase.succeeded"` and the `request.send` for
   `POST /me/purchase`, both carrying `is_sandbox` in the payload. This is the load-bearing value.

## One-time device setup

- An **Amazon device** (Fire tablet, or any device where `Build.MANUFACTURER == "amazon"` /
  installer is `com.amazon.venezia`). Teak only builds the Amazon store there — a Google-installer
  device will silently use Google Play billing instead.
- **Amazon App Tester** installed (for sandbox purchases), configured with an
  `amazon.sdktester.json` that defines your SKUs.
- An **Amazon Appstore listing** for this app's package (`io.teak.app.unity.dev`) with matching
  IAP SKUs, for the production (live-account) cases. If your listing uses a different package, set
  `applicationId` in `example/app/build.gradle` accordingly.
- **The SKU to purchase.** Default is `io.teak.app.sku.dollar`; override per build with
  `-PamazonSku=your.consumable.sku`. It must exist in your App Tester JSON (sandbox) / listing
  (production).

## Selecting the Amazon SDK (jar swap)

The proprietary jars aren't committed. Drop the jar into the matching slot under
`app/amazon-libs/` and select it with `-PamazonIap`. See `app/amazon-libs/README.md` for the slot
table. Only the legacy IAP v2 jar (`-PamazonIap=v2`, the default) is committed.

```
# Appstore SDK 3.x cases:
cp AmazonAppstoreSDK-3.x.x.jar app/amazon-libs/appstore3x/
./gradlew :app:installAmazonDebug -PamazonIap=appstore3x

# Legacy IAP v2 (default) — nothing to drop:
./gradlew :app:installAmazonDebug
```

`LicensingService` is reached via reflection, so the harness compiles even against the DRM-less v2
jar; the class only needs to be present at runtime (appstore3x / drm-standalone slots).

## Cases

Install, launch (the app boots straight into the sandbox screen), watch logcat, then tap **Amazon
Sandbox Purchase** and complete it in App Tester (sandbox) or with a real account (production).

### MUST-RUN

**1. Appstore SDK 3.x — sandbox** (the original crash / sad path)
- Build: `./gradlew :app:installAmazonDebug -PamazonIap=appstore3x`
- Trigger: launch (verifyLicense fires), tap Purchase, complete in App Tester.
- Expected: no `NoSuchFieldError`, purchase reports, `is_sandbox=true`.

**2. Appstore SDK 3.x — production** (happy path)
- Build: `./gradlew :app:installAmazonRelease -PamazonIap=appstore3x`, live Appstore build.
- Trigger: launch, tap Purchase, complete with a real account.
- Expected: no crash, `is_sandbox=false`.

### Additional coverage

**A1. IAP v2.0 — sandbox**
- Build: `./gradlew :app:installAmazonDebug` (default v2).
- Trigger: purchase via App Tester.
- Expected: no crash, `is_sandbox=true`.

**A2. IAP v2.0 — production**
- Build: v2, live Appstore build, real-account purchase.
- Expected: `is_sandbox=false`.

**A3. Appstore SDK 3.x — pre-verifyLicense window**
- Build: `./gradlew :app:installAmazonDebug -PamazonIap=appstore3x -PverifyLicenseAtLaunch=false`.
- Trigger: launch (verifyLicense is skipped, so `getAppstoreSDKMode()` reads UNKNOWN), tap Purchase.
- Expected: no crash, no exception, `is_sandbox=false`.

**A4. Legacy IAP v2.0 + standalone DRM lib** (the fall-through path)
- Build: drop the DRM jar into `app/amazon-libs/drm-standalone/`, then
  `./gradlew :app:installAmazonDebug -PamazonIap=v2-drm`. `LicensingService` is present but has no
  `getAppstoreSDKMode`, so detection falls through to the legacy `IS_SANDBOX_MODE` field.
- Trigger: sandbox purchase via App Tester.
- Expected: `is_sandbox=true`, and **zero per-purchase Raven/Sentry exceptions** from the sandbox
  lookup (the missing-method fall-through is silent).

**A5. Sentry-noise check**
- Build: `./gradlew :app:installAmazonDebug` (default v2, no DRM lib).
- Trigger: normal purchases.
- Expected: **zero per-purchase Raven exceptions** from the sandbox lookup (the ClassNotFound
  fall-through is silent).
