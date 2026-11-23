# Avakin Radar — progress log

## 2026-11-23

### Completed
- Merged PR #1 to `main` (commit `d01c6cadb5d1da1c33caf403e9c8b0bb3d8d0de9`): actual Kotlin/Jetpack Compose Android app source, public storefront monitor, snapshot/history pipeline, notifications, settings, and build workflows.
- The monitoring client uses a dedicated public WebView profile with cookies disabled. Main-frame monitoring navigation is restricted to the official HTTPS host `store.avakin.com`; no sign-in, private APIs, CAPTCHA bypass, or purchase actions.
- Parser recognizes the rendered Xsolla article-card layout with stable SKU identifiers; the earlier generic parser remains as a fallback.
- First successful scan is treated as a baseline: baseline cards are not counted as alert-worthy new offers.
- Added manual Android API 35 emulator workflow for live catalog integration test (not yet executed).
- CI on PR head `3bacd39548c0e1ac5ca9bf04f4199d0230d4a8af` passed unit tests and debug APK assembly (run 91). The merged main-branch build is run 92 and was still in progress when this log was written.

### APK
- Verified CI artifact ZIP contains `app-debug.apk` (18 MB), and APK ZIP integrity test passed.
- SHA-256: `8a45cbc7f87e718d859280b426d8d32f13c0e62c8883d7351c0c4f58bf19d70d`.
- Debug APK is for testing only; it has not been installed on a physical Xiaomi 14T Pro yet.

### Next
1. Run **Live public catalog emulator test** manually in GitHub Actions; review live response and second-scan comparison.
2. Install APK on Xiaomi 14T Pro and verify first scan, status display, notifications, battery/background behavior.
3. Add a value-per-price metric (Crowns/Avacoins per displayed price) and a best-value comparison, with currency-aware grouping and tests.
4. Continue refining the parser only from observed public page structures; preserve the last valid snapshot on any failure.

## Constraints
Only the anonymous public store is observed. Do not authenticate, collect personal data, probe private APIs, bypass anti-bot/access controls, or perform purchase actions.
