# Phase 5 System — Task 10 device and journey coverage

Date: 2026-09-04

## Covered on the available TV emulator

| Journey/variant | Evidence | Result |
|---|---|---|
| Welcome no-provider actions | `WelcomePresentationTest`, `welcome_no_provider` golden | Passed |
| Welcome loading/progress labels | `WelcomePresentationTest`, `welcome_syncing` golden | Passed |
| Downloads empty/action state | `DownloadsPresentationTest`, `downloads_empty` golden | Passed |
| Downloads completed/status/delete state | `DownloadsPresentationTest`, `downloads_completed` golden | Passed |
| Plugins empty/discovered/configuration state | `PluginsPresentationTest`, two plugin goldens | Passed |
| Welcome → Downloads → Plugins route registration | `SystemRouteGraphBehaviorTest` | Passed |
| TV semantics/remote-style activation | Connected presentation tests use semantics actions and click actions | Passed |
| RTL and 1.3 font scale | `SystemPresentationVariantTest` | Passed, 3/3 |
| Paused Compose animations | `SystemPresentationVariantTest` with `mainClock.autoAdvance = false` | Passed, 3/3 |

## Not available or not claimed

- Only `Television_1080p(AVD) - 16` was available; no phone/tablet profile was
  present for touch/mouse activation.
- No TalkBack or accessibility service was enabled on the emulator
  (`enabled_accessibility_services = null`), so service-mediated traversal was
  not claimed. Compose semantics assertions remain covered.
- The deterministic feature connected tests do not constitute a full
  authorized production-provider journey. No provider credentials or external
  plugin APK were available for a safe isolated run, so the following remain
  open rather than being reported as passed: production startup redirect with
  an existing provider, configured Downloads/Plugins landing preference,
  real folder picker select/cancel, external completed-download playback,
  plugin APK discovery/IPC, and activity-launched plugin configuration.

The app-side unit tests and route codec tests cover startup-navigation intent,
typed `welcome`/`downloads`/`plugins` route mapping, and adapter delegation;
`SystemRouteGraphBehaviorTest` covers the feature-owned graph registration.
These are compatibility evidence, not a substitute for the unavailable
authorized production-provider/device journeys above.
