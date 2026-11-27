# Avakin Radar — Device QA checklist (Xiaomi 14T Pro)

Baseline source: `codex/fix-rendered-webview-stopreason` at `5a9c16acd329fb0d7bee2052a0b82467fc8c1f8d` (PR #2; CI build/unit tests passed).

## Confirmed device behavior (26 Nov 2026)
- [x] Manual public-store scan succeeds.
- [x] 34 offer cards are shown on Overview with image, title/details and price.
- [x] Comparison counts display 0 new / 0 changed / 0 removed / 34 unchanged for this run.
- [ ] History is populated after successful scan (currently empty; investigate persistence/read path).
- [ ] Store viewer is legible in portrait.
- [x] Store viewer is legible in landscape (new screenshot evidence).

## Repro / compare orientations
1. Open Overview; capture current successful-check summary and offer count.
2. Open Store in portrait; capture viewport and card width/clipping.
3. Rotate to landscape; capture same store state. Landscape currently renders normal multi-column cards.
4. Rotate back to portrait and note whether layout reflows immediately or requires reload.
5. Compare the WebView's measured container width, visible viewport, and page viewport (if diagnostics can be added) in both orientations. Do not inject forced page widths or arbitrary card CSS.

## History acceptance
- Initial valid snapshot creates a visible baseline/catalog-success history entry, but does not emit 34 false "new offer" notifications.
- A later scan with no catalog changes creates an appropriate check/baseline history entry or clearly documented history policy.
- Subsequent new/changed/removed offer events persist and match Overview counts.
- Failed/incomplete scans preserve prior snapshot and don't create false removal events.
- Add unit tests for initial baseline and second comparison; test history repository/UI data source.

## Store responsive acceptance
- Portrait: card titles, bonus, currency/price, and action text are readable; no columns squeezed to narrow strips.
- Landscape: current good rendering remains intact.
- Both orientations: no clipped overlap or unusable horizontal overflow.
- Test on phone-sized emulator and physical Xiaomi 14T Pro where possible.

## Privacy and safety
Only the public official storefront; no sign-in, cookies, personal data, CAPTCHA/access-control bypass, private APIs, or purchase actions.
