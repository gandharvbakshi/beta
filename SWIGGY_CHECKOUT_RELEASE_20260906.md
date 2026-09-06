# Swiggy checkout release evidence - September 6, 2026

## Latest checkpoint - 14:15 IST (supersedes version 17 checkpoint below)

- Android candidate 0.3.2 / version 19: trailing packet/unit quantities no longer
  create phantom groceries; oversized counts remain explicit for validation;
  counted retail multipacks divide exactly, with non-divisible/conflicting counts
  rejected. A public, clearly labelled offline onboarding demo never calls Swiggy.
- Build/debug/test APKs, unit tests, release lint and signed bundle pass.
  **205 JVM tests, zero failures/errors; 29 disconnected emulator checkout,
  handoff, persistence and demo tests passed. Lint: zero errors, 96 warnings.**
- Signed version 19 bundle SHA256:
  `23e28289e4ae9c32c94b044ff3fd84dc16bf79521885813e7b8089aad6688644`.
- Play version 17 was published before an attempted withdrawal took effect
  (Console notification and September 6 publishing date). Version 18 was uploaded
  as a draft; version 19 replaces that draft via committed edit
  `12034684431507713168`. Fresh readback confirms beta version 19 draft alongside
  completed version 17; uploaded bundle hash matches the signed local artifact.
  Draft is not rollout/approval. Both English listings, feature graphic and three
  screenshot sets were updated. Play rejects `changesNotSentForReview` on this app;
  a draft bundle can coexist with automatically reviewed listing changes.
- Reviewer access is unresolved: the old no-sign-in declaration is inaccurate.
  A four-screen offline preview is not full signed-in access. The full-access
  attestation was not checked; no false credentials/declaration was submitted.
  A reusable provider-approved reviewer account/access path is needed.
- Backend source `4162200` accepts actual flat MCP responses while preserving
  explicit error checks and field-level checkout validation. **259 tests plus
  126 subtests passed.** `elderly-canary` runs
  `beta-backend-staging-checkout-flat-4162200`. After successful physical-phone
  whole-cart/payment-option review, ordinary 100% traffic and all seven tags were
  promoted to this revision. Environment and runtime identity stayed unchanged.
  Synthetic unconnected readiness probes passed before and after promotion.
  Backend main `a3e9085` adds only the probe URL to runtime source `4162200`.
- Live cart test: one owner-approved addition request, no order/payment. Readback
  shows the intended counted multipack and second product, with the original cart
  item preserved. The strict verifier stopped because two SKU references changed
  although spin IDs and quantities matched. No write retry, no relaxed verifier.
  Independent current-cart inspection and explicit acknowledgement were followed
  by a successful fresh whole-cart checkout review on the phone. Only pay-on-
  delivery was returned for this cart; UPI is implemented/offline-tested but not
  live-proven. This is not automatic cart-verification success.
- Final exact items/address/total/payment confirmation is still required before
  the one authorised real order. No real transaction success is claimed here.
- Android source commit `328554b` was pushed and read back on main and master;
  GitHub default branch is main. Drive handoffs record these gates and limits.

## Earlier version 17 checkpoint

Checkout is enabled following the owner's explicit release approval. The earlier
default-off implementation decision is superseded. This does not imply that a
live payment has succeeded: at this checkpoint all transaction tests are mocked.
The owner later authorised one specific live basket; exact products, address,
total and payment method must be confirmed before that purchase. No retries of
an uncertain order are permitted.

## Verified implementation and tests

- Android release 0.3.0, version code 17; checkout BuildConfig true; ordinary
  hosted backend URL; no accessibility, overlay, media-projection or AD_ID.
- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintRelease
  bundleRelease`: successful. 193 JVM tests, zero failures/errors.
- 28 instrumented checkout, persistence and cart-review tests passed on an
  isolated Android 36 emulator at font scale 1.3. Network disabled, fake gateway,
  no connected Swiggy account, no real provider/order calls.
- Lint: zero errors, 96 warnings (not a claim of warning-free code).
- Backend: 252 tests plus 119 subtests passed across Swiggy checkout/cart/address,
  transport, auth/rate budgets and related request safeguards.
- Root review plus independent adversarial review found no concrete blocking
  checkout defect. Grok independently reviewed dispatch-fence races. Claude
  consultation failed; this is not represented as Claude approval.
- Missing-attempt recovery atomically closes the attempt UUID before permitting
  a new confirmation. Delayed requests cannot dispatch an already-closed UUID.
- A pre-dispatch storage failure after claiming a fence can conservatively need
  support recovery. Duplicate prevention takes precedence over automatic retries.

## Hosted readback

- Cloud Build `6dae7c27-cff7-4c84-803f-1bcbe485a640` succeeded.
- Runtime source commit `607e0bd`; Cloud Run revision
  `beta-backend-staging-checkout-607e0bd`, project `beta-496723`, `asia-south1`.
- Image SHA256 `a61511ca6812d623100993e766f82e1be1001612c3aea7652db4027c07ef9367`.
- Checkout enabled, 100% traffic. Existing environment/runtime identity preserved;
  previous public canary tags now resolve to the same hardened revision.
- Canary and ordinary-URL readiness checks passed: health, missing-key 401,
  missing-installation 401, new-installation no-attempt response, and durable
  `not_submitted` fencing. These use synthetic unconnected installations only.
- Privacy page HTTP 200 at `https://betaapp.live/privacy-policy.html`.
  Firebase release `sites/beta-496723/releases/1788679494438000` preserved the
  existing exact OAuth callback rewrite and Firebase-generated files.

## Play preparation

- Fresh track readback before upload: open testing `beta` completed version 16;
  production empty, internal draft 3. Version 17 unused.
- Data Safety accepted by the official Publisher API. OAuth and account/data
  deletion URLs verified in Console. Purchase history is disclosed separately
  from payment credentials. Required legacy declarations retained conservatively.
- Both English listings updated; phone/7-inch/10-inch screenshots show the actual
  renderer with synthetic demo data, not a completed real purchase.
- Final signed AAB SHA256:
  `b2c0272743233f155003f6310455d1307d60e629e8ee49bb9147c26813996db6`.
- The first upload was rejected as unsigned (no release committed). Rebuilt with
  the existing upload key and verified via `jarsigner`; the publisher now fails
  locally on unsigned/unverified bundles before creating an edit. The earlier
  C36158... artifact was unsigned and must not be distributed.
- Release upload/review status must be recorded from fresh readback below.

## Feature graphic provenance

Asset: `play_store_assets/feature_graphic_1024x500.png`.
Built-in image generation edited the existing graphic; the final export was
mechanically sized to Play's exact 1024x500 requirement. Screenshot assets were
captured directly, not generated.

Final prompt: Preserve the existing Beta cream palette, logo, rounded layout and
right-hand phone screenshot. Replace the left heading with "Your Swiggy assistant",
the body with "Speak or type. Review your items, address and total before you
order.", and the "Cart only" chip with "You confirm". Keep "Voice + text" unchanged.
No added promises, brands or badges; all text must fit legibly.
