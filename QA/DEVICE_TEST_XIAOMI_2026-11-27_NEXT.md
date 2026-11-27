# Xiaomi 14T Pro — follow-up QA (27 Nov 2026)

## Confirmed on-device behavior
- Successful storefront scan returns 35 offers.
- Overview counts: 1 new, 1 changed, 0 disappeared, 33 unchanged.
- History displays a new offer named **Собранная грация** at 29.99 EUR, containing 1,045 Crowns.
- History also displays a generic Crowns price-change event from 7.99 EUR to 29.99 EUR, without identifying the exact offer card.

## Investigation hypothesis: offer identity collision
A newly appearing offer may have been matched to a previous distinct offer by weak matching (generic category/label, similar contents or price), incorrectly classified as a price change. This remains a hypothesis. See Issue #5 for regression requirements. Matching must prefer stable store SKU/offer IDs, and uncertain matches must not be treated as price changes.

## Store category menu observed
Official store menu screenshot includes: Favorites, Fashion, Black Friday discounts, Branded, Bright Collection, Basic, Look, Fantasy and Magic, Character Favorites, Bundle Storage, Currency Bundles, Crowns, Avacoins.

## Filter UX recommendation
- Keep All as quick filter.
- Currency filter should contain currency-only packs, not clothing bundles.
- Keep Promotions/Exclusives only if classification remains reliable.
- Provide a compact expandable category picker (not a large permanent row of buttons) for official categories supported by parsed data.
- Preserve an Uncategorised bucket when classification is uncertain; do not infer categories arbitrarily.
- Check portrait and landscape layouts.

## Acceptance / regression tests
1. Same stable offer ID, price changes: emit one price-change event with exact offer card identity and old/new price.
2. Old offer removed, distinct new offer added: emit removal + addition; no false price change.
3. Initial baseline is stored and represented without mass false-new notifications.
4. Overview statistics reconcile with persisted history events.
5. Currency filter excludes non-currency apparel bundles; category selector filters reliably and layout remains usable in both orientations.

This report records device observations and intended tests; it does not claim these bugs are fixed or that a new APK has been built.