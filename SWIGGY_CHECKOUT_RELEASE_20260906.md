# Swiggy checkout release evidence - September 6, 2026

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
  `c36158c196958229bacb9769165e6912cafc572de229f6bda96434e0434acee2`.
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
