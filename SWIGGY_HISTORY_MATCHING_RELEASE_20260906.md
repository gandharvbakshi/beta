# Swiggy history matching release evidence — 2026-09-06

## Current verdict

Update after explicit owner direction to publish to open testing: promotion edit
`12204443968900016051` validated and committed successfully. Fresh independent
API readback shows **beta version20 completed**, no beta draft17/20 release,
production/alpha empty and internal3draft unchanged. Only the open-testing track
was changed; the existing signed20 bundle/hash, listings and assets were reused.
This supersedes the draft hold below, not the unresolved reviewer-access risk
or live-transaction limits. App-access declarations were not changed and no
claim of full demo access was made. API completed status does not independently
prove Google review approval or installation availability.

History-first matching is implemented, the hosted backend is deployed, both
GitHub main branches are updated, and signed Play version 20 is saved as a draft.
This is not a claim of public rollout or completed-order acceptance. No provider cart,
checkout, payment or order mutation was attempted during this matching pass.

## Verified build and test evidence

- Android JVM suite: 245 tests, 0 failures.
- Android verification gates: `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `lintRelease`, and `bundleRelease` all passed.
- Signed AAB 20 / 0.3.3 SHA256: `b272daf78785fc2635d346d8bdf083d0e44ece20012f8a55607ff503f2b4032f`.
- Backend revision `3613430`: 283 tests and 161 subtests passed.
- 29 isolated Android UI tests passed.
- No live provider transaction was made during matching verification.

## Read-only matching probes

- Mixed Hinglish probe of 10 items: 6 compatible, 4 safely unresolved. Three
  apples could not use the available 2/4/6-piece packs; an unavailable usual
  variant and two unverified products were also withheld. This is not a 10/10
  product availability result or proof of cart additions.
- Exact `2 packets 0.5 litre milk` resolved to `2 x 500 ml`.
- Compact and spaced product names such as `7up`, `5 star`, and `24 mantra` were not treated as quantities.
- Salt and makhana remained distinct.
- A compatible current result carried actual-order history and was ranked first.
- When usual history was unavailable, the system produced no substitute.
- Final 8-query probe passed in 11.955 seconds, with 7 compatible and 1 unavailable.

## Release scope and guardrails

- Recent history was bounded to 7 orders; this report does not imply full lifetime history.
- Final backend revision `beta-backend-staging-history-final-3613430` was promoted to 100% and all 7 tags.
- Public readiness readback passed: health, backend authentication, installation
  authentication, synthetic empty checkout, durable closed fence twice and
  sanitized OAuth callback. These checks made no Swiggy provider calls.
- Runtime image: `sha256:23e61c0e1ebb404d6c6ad0e1a5f5033d5165976823e75894c16f1fd906a5ad7d`.
  Environment and runtime service-account parity were verified before promotion.
- Claude review outcome: APPROVE WITH CAUTIONS. Codex reproduced and fixed
  dash-delimited flavour-tail and compact/spaced numeric-brand false negatives.
  Codex rejected splitting every ampersand because that weakens the ingredient
  word safeguard. Claude did not execute the tests; the results above are Codex's.
- 12 final-build real UI screenshots were refreshed across 3 form factors and the listing was accurate.
- Play draft upload readback passed; reusable Swiggy reviewer access is still
  required for promotion. Four static offline demo screens are not full access.
- No cart writes, payment steps, or order confirmations occurred.

## Matching contract summary

1. Respect explicit brand, variant, exclusions, product kind and quantity first.
2. Rank compatible actual purchases by frequency, then recent returned order;
   go-to and catalogue scores cannot outrank a verified order-history match.
3. Use only available current-catalogue rows as cart candidates. Historical IDs
   never become cart IDs. One bounded title search may recover the usual item
   missing from the generic search page; known pack identity must agree.
4. Withhold a different variant when the usual item cannot be verified. Numeric
   brands and model numbers are identity, not shopping counts. Exact requested
   pack counts remain separate from measured totals; neither is rounded up.
5. Select compatible defaults and show one basket review with changes available,
   rather than repeatedly asking a product-choice question for each item.

## Reproduction and evidence boundaries

- Android: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintRelease bundleRelease`.
- Backend: the `test_swiggy*.py` suite plus auth, incoming-request guard, request
  budgets, usage telemetry/middleware, language input and feedback tests.
- Isolated Android classes: `SwiggyCheckoutFlowTest`, `SwiggyCheckoutStoreTest`,
  `SwiggyCheckoutCoordinatorHandoffTest`, `SwiggyCartReviewStoreTest` and
  `SwiggyOfflineDemoTest`; synthetic fixtures, network disabled.
- Physical-phone probe: `SwiggyHistoryPreferenceReadOnlyTest`, explicitly opted
  in with `liveSwiggyHistoryPreference=true`. It only reads current cart/orders
  and requests recommendation batches. It uses the real parser, response
  parser, candidate filters and quantity arithmetic, but never sends a cart plan.
- Signed release manifest excludes accessibility, overlay, screen capture,
  legacy-provider and advertising-ID declarations.
- Actual final-build screenshots: four per phone/7-inch/10-inch set. Dimensions
  are 1080x1920, 1200x1920 and 1600x2560 respectively, stored as 24-bit RGB PNG.
  Synthetic fixture address and cart values are not real purchase evidence.

Coverage is bounded, not universal speech or catalogue understanding. These
results do not establish native-Swiggy cart persistence or live UPI/order success.
Unavailable products remain unavailable; no different chocolate was approved.
Future purchase validation requires fresh exact basket/address/total/method
confirmation. Never automatically retry an uncertain mutation.

## Final release readback — September 6, 2026

- Android implementation commit `b3e5c13` pushed non-force to public `main` and
  legacy `master`; the remote default branch was independently verified as main.
- Backend `3613430a083b88dc095fa63db15179a0795414df` pushed non-force to private main.
- Publisher edit `06366122968290908142` validated and committed successfully.
  A separate fresh readback edit `05173779115538911483` was deleted after checks.
- Fresh Play truth: beta has version 20 / 0.3.3 **draft** and version 17
  **completed**. Production/alpha have no releases; internal retains draft 3.
  Uploading version 20 did not make it available to testers or replace version 17.
- Play's version-20 bundle SHA256 exactly matches the signed local AAB above.
  Both en-US/en-GB descriptions exactly match repository listing files. Each
  locale has four screenshots in each of the three device groups, with ordered
  SHA256 values matching all local final-build files. Feature-graphic hashes match.
- Remaining gates: reusable full Swiggy reviewer access before Play promotion;
  a fresh approved purchase before any live order/payment acceptance claim.
  Neither unavailable stock nor these external gates is marked resolved.
