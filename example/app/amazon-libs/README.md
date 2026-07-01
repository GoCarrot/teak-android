# Amazon IAP SDK slots

The `amazon` product flavor links against one of these slots, chosen with `-PamazonIap`:

| `-PamazonIap` | Slot dir(s)                    | What                                              |
|---------------|--------------------------------|---------------------------------------------------|
| `v2` (default)| `iap-v2/`                      | Legacy In-App Purchasing 2.0 (committed)          |
| `appstore3x`  | `appstore3x/`                  | Appstore SDK 3.x (IAP + DRM/LicensingService)     |
| `v2-drm`      | `iap-v2/` + `drm-standalone/`  | Legacy IAP 2.0 + Amazon's standalone DRM lib      |

Only `iap-v2/` is committed — the other jars are proprietary and gitignored. To use them, drop
the jar into the matching slot and pass the flag, e.g.:

```
cp ~/Downloads/AmazonAppstoreSDK-3.x.x.jar appstore3x/
./gradlew :app:installAmazonDebug -PamazonIap=appstore3x
```

The jar filename doesn't matter — every `*.jar` in the selected slot is linked. The runtime
Amazon classes are provided by whichever slot you select; `LicensingService` is reached via
reflection, so the harness compiles even against the DRM-less `v2` slot.

See `example/AMAZON_SANDBOX_VERIFICATION.md` for the full per-case runbook.
