# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Teak Android SDK — a native Android library (AAR) providing push notifications, deep linking, rewards, and marketing analytics for free-to-play mobile games. Part of the Teak SDK suite alongside `teak-ios` and `teak-unity`.

## Build & Test Commands

```bash
./gradlew assemble                          # Build AAR (output: build/outputs/aar/teak-release.aar)
./gradlew clean assemble generateApiDoc     # Full build with Javadoc (same as ./compile)
./format-code                               # Format all Java files with clang-format

# Tests live in the test_app/ subproject
(cd test_app && ./gradlew test --continue)                                    # Run all unit tests
(cd test_app && ./gradlew test --tests "io.teak.app.test.DeepLinkRoutes")    # Run single test class
```

## CI Checks to Know About

These run in CI and will fail the build if violated:

- **`./org_json_check`** — No imports of `org.json.*` allowed. Must use vendored `io.teak.sdk.json.*` (exists to avoid Android API < 19 differences).
- **`./check_thread_use`** — No direct `import java.util.concurrent.Executors` except in `Executors.java`. All executor creation goes through `io.teak.sdk.core.Executors`.
- **`./validate-code-format`** — clang-format validation (also runs as pre-commit hook).

## Code Style

- **clang-format** enforced (config in `.clang-format`)
- 4-space indent, no column limit, K&R braces
- `BreakAfterJavaFieldAnnotations: true`
- Vendored code (`shortcutbadger/`, `json/`) is excluded from formatting and linting

## Project Structure

Single Gradle module (`com.android.library`). All SDK source under `src/main/java/io/teak/sdk/`.

| Package | Purpose |
|---------|---------|
| `io.teak.sdk` | Public API: `Teak`, `TeakInstance`, `TeakNotification`, `TeakEvent`, `Request` |
| `io.teak.sdk.configuration` | Layered config: `AppConfiguration`, `DeviceConfiguration`, `RemoteConfiguration`, `DebugConfiguration` |
| `io.teak.sdk.core` | Internal engine: `Session`, `TeakCore`, `DeepLink`, `Executors`, `ChannelStatus`, `UserProfile` |
| `io.teak.sdk.event` | Internal event types (`LifecycleEvent`, `PushNotificationEvent`, `UserIdEvent`, etc.) |
| `io.teak.sdk.io` | Platform abstraction interfaces (`IAndroidResources`, `IHttpRequest`) and `Default*` implementations |
| `io.teak.sdk.push` | Push providers: `FCMPushProvider`, `ADMPushProvider` |
| `io.teak.sdk.store` | IAP tracking: `GooglePlayBillingV4`, `GooglePlayBillingV5`, `Amazon` |
| `io.teak.sdk.wrapper` | Cross-engine bridge: `TeakInterface`, `ISDKWrapper` |
| `io.teak.sdk.wrapper.unity` | Unity integration layer |
| `io.teak.sdk.json` | **Vendored** — do not use `org.json` |
| `io.teak.sdk.shortcutbadger` | **Vendored** — launcher badge counts |
| `io.teak.sdk.raven` | Sentry/Raven error reporting |

## Architecture

### Initialization (Dual Path)

1. **Manual**: Game calls `Teak.onCreate(activity)` before `super.onCreate()` → creates `TeakInstance` singleton at `Teak.Instance`
2. **Automatic**: `TeakInitProvider` (ContentProvider) registers `ActivityLifecycleCallbacks`, auto-initializes when an Activity with `io.teak.sdk.initialize=true` metadata is created

### Two Event Systems

- **Internal** (`TeakEvent`): Custom blocking-queue event bus with dedicated processing thread. String-typed events for internal SDK plumbing (lifecycle, push, session state). Listeners dispatched on single-thread executor.
- **External** (GreenRobot `EventBus`): Delivers events to game/wrapper layer on main thread. Events queued until user is identified, then drained. `TeakInterface` bridges internal → external via `@Subscribe` annotations forwarded through `ISDKWrapper.sdkSendMessage()`.

### Session State Machine

`Session` is the most complex class (~1135 lines). State flow:

```
Allocated → Created → Configured → IdentifyingUser → UserIdentified → Expiring → Expired
                                                            ↑               |
                                                            └───────────────┘ (resume on activity return)
```

Key pattern: `Session.whenUserIdIsReadyRun(runnable)` queues work until user is identified, then drains.

Sessions resume (Expiring → UserIdentified) if the activity returns within 120 seconds. Otherwise a new session is created.

### Threading

All executor creation funneled through `io.teak.sdk.core.Executors` (enforced by CI). `ThreadFactory` auto-names threads from call stack for debugging. Key executors:

- `Session.executionQueue` — single-thread per session, serializes session operations
- `Request.requestExecutor` — single-thread, serializes all HTTP requests
- `TeakEvent.eventQueue` — blocking queue with dedicated processing thread
- `Teak.asyncExecutor` — cached pool for public API calls
- `DeepLink.executor` — single-thread for deep link processing

`InstrumentableReentrantLock` wraps `ReentrantLock` with optional long-lock detection.

### HTTP / Request Layer

`Request` handles HMAC-SHA256 signing (`TeakV2-HMAC-SHA256`), configurable retry with jitter, and endpoint blackholing (server-controlled via `RemoteConfiguration`). Batching subclasses (`BatchedTrackEventRequest`, `BatchedParsnipRequest`) aggregate payloads with configurable count/time thresholds.

### Configuration Layer

Layered: `AppConfiguration` (Android resources) → `DeviceConfiguration` (device info) → `RemoteConfiguration` (fetched from `gocarrot.com/games/{appId}/settings.json`) → `DebugConfiguration`. Remote config controls Sentry DSNs, FCM sender ID, endpoint batching/retry/blackhole settings, heartbeat interval, notification categories.

### Dependency Injection

`IObjectFactory` provides `IStore`, `IAndroidResources`, `IAndroidDeviceInfo`, `IPushProvider`. `DefaultObjectFactory` auto-detects platform (Amazon vs Google). Tests inject mocks via `Teak.onCreate(activity, objectFactory)`.

### ProGuard / Obfuscation

`Unobfuscable` marker interface — all implementing classes kept unobfuscated. Consumer ProGuard rules in `proguard.txt` are bundled in the AAR.

## Testing

Tests use JUnit 4 + Mockito 2.22 + WireMock 2.18 + ConcurrentUnit. Located in `test_app/app/src/test/java/io/teak/app/test/`. Base class `TeakUnitTest` provides mock setup. Tests are disabled in the root project and run only from the `test_app` subproject.

## Documentation & Changelog

Public documentation is built with **Antora** and lives under `docs/`. API docs are generated via doxygen (`doxygen2adoc`).

### Changelog System

The changelog is published to the documentation site. Source of truth is YAML files in `docs/modules/changelog/versions/`. AsciiDoc partials and the main changelog page are **generated** by `doxygen2adoc` and gitignored — never edit `.adoc` files under `docs/modules/changelog/`.

- **Adding entries:** Edit `docs/modules/changelog/unreleased.yaml`. When a user-facing change is made, add a line under the appropriate category.
- **Important:** `unreleased.yaml` lives in `docs/modules/changelog/`, NOT in `versions/`. The `versions/` directory is read by `doxygen2adoc` which requires valid semver filenames — `unreleased.yaml` in that directory will break doc generation.
- **At release time:** Move `unreleased.yaml` into `versions/<version>.yaml` and create a fresh `unreleased.yaml`.
- **YAML categories:** `new`, `bug`, `enhancement`, `breaking`, `deprecation`, `upgrade_note`, `known_issue`
- **Format:** Each category is a list of strings. Use backticks for code references.

## Version Management

- `VERSION` file at repo root (currently `4.3.9`)
- Build version from `git describe --tags --always` → `BuildConfig.VERSION_NAME`
- `./release` script: validates clean tree, creates annotated git tag, pushes
- CI auto-tags on successful builds; manual approval gate before promoting to "latest" on S3

## Commit Message Format

Every commit must include a full human-Claude interaction log. Placeholders
below are in angle brackets — replace them with actual content, do not include
the brackets. The interaction log only covers prompts since the previous commit,
not the entire session.

```
Brief description of what was done

Technical description of changes made

## Human-Claude Interaction Log

### Human prompts (VERBATIM - include typos, informal language, COMPLETE text):
**Include EVERY prompt since last commit - even short ones, corrections, clarifications**
1. "<copy-paste ENTIRE first prompt since last commit>"
   → Claude: <what Claude did in response>

2. "<copy-paste ENTIRE second prompt>"
   → Claude: <how Claude adjusted>

<continue numbering ALL prompts - don't skip any or judge importance>

### Key decisions made:
- Human guided: <specific guidance provided>
- Claude discovered: <patterns found>

🤖 Generated with Claude Code
Co-Authored-By: Claude <noreply@anthropic.com>
```
