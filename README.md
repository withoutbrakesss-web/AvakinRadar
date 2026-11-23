# Avakin Radar

Android app for monitoring publicly visible offers on the official Avakin Life web store: `https://store.avakin.com/`.

## Features
- Anonymous public-store monitoring using a separate Android WebView profile with cookies disabled.
- Reads offer data rendered publicly on the page: SKU, title, price/currency, Crowns or Avacoins, bonus, web-exclusive/limited markers, and image where available.
- Saves snapshots locally, compares later successful scans, and keeps up to 250 history events.
- Android notifications for new offers, changes, and removals, controlled in Settings.
- WorkManager scheduled checks; Android may delay background execution.
- Fail-closed behavior: empty, duplicate, or suspiciously incomplete results do not replace the last good snapshot or produce false removal events.
- Main-frame monitoring navigation is restricted to the official HTTPS storefront. The in-app Store tab is a public-page viewer; monitoring itself never uses that tab's cookies.

No account sign-in, CAPTCHA/access-control bypass, personal-data collection, private API probing, or purchase/payment actions are used by the monitor.

## Source
The Android project is in `AvakinRadar_cloudkit/AvakinRadar`.

## GitHub Actions
- **Avakin Radar Android**: unit tests and debug APK build on pushes and pull requests. Download `AvakinRadar-debug-APK` from a successful run.
- **Live public catalog emulator test**: manual workflow. Open Actions → **Live public catalog emulator test** → **Run workflow**. It runs the instrumented flow on Android API 35 emulator against the live public store, checks that offers are saved and the UI reports them, then performs a second comparison scan. This test has not yet been run.

## Limitations
The store is JavaScript-rendered and can change its markup or regional catalog. The monitor may report a parse or access error and preserve the previous snapshot instead of inventing data. A physical Xiaomi 14T Pro test is still required for WebView behavior and notification delivery. Background timing depends on Android/device battery policy.

The artifact is a debug APK for testing, not a Play Store release.
