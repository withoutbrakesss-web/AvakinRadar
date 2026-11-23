# Avakin Radar — Xiaomi 14T Pro scan failure diagnosis (2026-11-24)

## Evidence from the device screenshots
- The official store WebView renders the page, but the narrow card columns are unusable: the category/navigation overlay is open and the page's card layout is squeezed. This is the Store viewer layout issue, not evidence that the catalog parser failed.
- The monitoring log reaches: HTTP 200 / text-html, WebView requested, main page loaded, and then reports that the JavaScript catalog is still awaited. No parsed cards or saved scan result appear.
- WorkManager subsequently records cancellation before a result was saved. The app reports Android stopped the worker and that the previous valid snapshot remains saved.
- The monitoring toggle is shown off after the failure. The screenshot's VPN indicator is visible, so network conditions may also differ; this is not proven to be the cause.

## Source inspection
- `MonitorWorker` runs `OfferCheckPipeline` and calls `RenderedStoreClient.fetchOffers`.
- `RenderedStoreClient` performs an anonymous HTTP probe, then creates a WebView on the main thread, waits for `onPageFinished`, polls `document.documentElement.outerHTML` via `evaluateJavascript`, and waits up to 40 seconds for parsed cards. Overall timeout is 70 seconds.
- The worker has a foreground notification (`ForegroundInfo`, data-sync FGS type on Android Q+) but the WebView renderer is still a background/hidden web content instance; foreground service status does not guarantee a stable, visible WebView renderer on manufacturer-modified Android.
- `OfferCheckPipeline` correctly rejects empty or small catalogs and retains the prior snapshot. This safety behavior should stay.
- The in-app Store tab uses a separate visible WebView and has viewport injection. Screenshot indicates that viewer itself also needs responsive-layout cleanup, but it is not the monitor's WebView.

## Most likely failure (not proven)
The worker is being stopped by Android/HyperOS while the hidden WebView is waiting for the store's JavaScript-rendered catalog. The screenshots do not show a parser exception or anti-bot page; they show a cancellation. We must not label this as a store-block or parser defect without a captured DOM/error.

## Recommended next implementation, in order
1. **Foreground manual scan (reliable first):** Move the manual “Check offers” operation out of WorkManager's hidden WebView flow into a dedicated visible scan screen/activity. Reuse the visible WebView, isolated public profile/cookie-free setup, allowed-host checks, and DOM parser. Extract DOM only after cards appear, then pass parsed offers into the existing snapshot/diff/save pipeline. Show scan stage and allow cancel; don't update snapshot on incomplete/empty results.
2. **Background checks (best-effort):** Keep WorkManager periodic scheduling, but do not depend on a hidden WebView. First try an anonymous public HTTP response and parse structured/rendered HTML if cards are present. If the response is only a JS shell, return an explicit “catalog requires foreground rendering” result and preserve the last snapshot; do not retry indefinitely or fake a success.
3. **Device stability controls:** Explain HyperOS battery restrictions and provide an in-app link/instructions to set battery mode to unrestricted / allow background autostart if available. Treat this as optional user setting, not a guarantee.
4. **Store tab layout:** Keep the navigation menu closed by default, reset viewport to mobile width, and use WebView zoom/overview appropriately. The current screenshot has the menu overlay and tiny horizontal card columns; avoid treating this as the monitor result.
5. Add emulator integration tests for foreground DOM extraction and explicit “JS shell/no cards” background fallback. Add test asserting prior snapshot remains untouched on cancellation/incomplete result.

## Acceptance criteria
- A manual foreground scan on Xiaomi 14T Pro can produce a visible stage log and parsed card count, or a clear reason (network/access/unsupported WebView/JS shell), without leaving an endless spinner.
- A successful scan stores offers and displays count; a failed/incomplete scan preserves the previous snapshot.
- A second successful scan creates diff/history events and configured notifications.
- Background work never claims a catalog success unless actual offers were parsed and saved.

## Privacy/safety
Only the public official store is used. No account sign-in, cookies, CAPTCHA/access-control bypass, private API probing, personal data collection, or purchase flow.
