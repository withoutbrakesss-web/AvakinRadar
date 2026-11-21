# Avakin Radar

Android app for tracking publicly visible offers in the official Avakin Life web store.

## What it does

- Opens `https://store.avakin.com/` in an Android WebView with JavaScript, DOM storage, standard WebView cookies, loading progress, retry, and safe external-link handling.
- Checks only the public storefront over HTTPS without signing in, sending account cookies, solving access checks, or using undocumented APIs.
- Reads offer details exposed in the returned HTML/JSON-LD: title, public link, available image, price/currency, Crowns, Avacoins, bonus, and in-app amount when present.
- Compares successful snapshots locally and records new, changed, and removed offers. The first successful snapshot is only a baseline.
- Runs an immediate check when monitoring is enabled and schedules follow-up checks with WorkManager at the selected 6, 12, or 24 hour interval.
- Keeps offer snapshots, settings, and up to 250 history events in app-private SharedPreferences. No app-owned backend is used.
- Allows filters for all offers, currency offers, and web exclusives, plus notification controls for new offers, changes, and removals.

## Source and parsing limits

The store renders its catalog with JavaScript. The monitor uses a separate unauthenticated Android WebView profile to read the public page after rendering; it does not use account cookies, call private catalog endpoints, or bypass CAPTCHA/anti-bot controls. If the page is blocked or no recognizable offer cards are found, the app reports the problem, preserves the last successful snapshot, and does not generate removal events from an empty response. The Store tab uses a full-screen mobile WebView, and the Overview shows live check progress and the last result.

The store can change its page markup and regional catalog. A markup change may require updating `OfferParser`; currency and prices are whatever the public page returns for that request. Background execution timing is also subject to Android and device battery policy.

## Build

The source project is in `AvakinRadar_cloudkit/AvakinRadar`. The earlier project ZIP is retained at the repository root as a backup. GitHub Actions runs the unit tests and builds a debug APK on pushes to `main` and `codex/**`, and on pull requests to `main`.

For a local build, use JDK 17, Android SDK platform 35, and Gradle 8.9:

```text
cd AvakinRadar_cloudkit/AvakinRadar
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug APKs are for installation/testing and are not a Play Store release build.

