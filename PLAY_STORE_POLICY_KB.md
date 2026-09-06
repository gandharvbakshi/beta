# Beta Play Store Policy Knowledge Base

Last updated: August 17, 2026

## Checkout implementation decision - September 6, 2026

The user has now authorised implementation of Swiggy MCP checkout, UPI handoff,
and order confirmation. This supersedes the implementation-only cart boundary
below, not the live-testing restriction. Both app and backend checkout switches
remain off by default. No live checkout/payment/order test or checkout-enabled
release is authorised until the user explicitly approves it later. Existing
distributed releases and store assets have not been changed by this decision.
See `SWIGGY_CHECKOUT_ACCEPTANCE.md` for offline evidence and later live gates.
Before enabling/releasing, review financial/purchase-history Data Safety coverage,
the recovery-record retention policy, full-address/amount consent, and current
provider payment-link hosts. Never claim that Beta receives PIN/card/VPA data.

## Current app context

- Play app: `Beta`
- Package: `live.betaapp.android`
- Android namespace: `com.example.beta`
- Current product: Swiggy Instamart cart building by voice or text through the
  authorised Swiggy MCP connection.
- Safety boundary: stop after cart verification; never checkout, pay or place
  an order.
- Current runtime permissions: optional microphone and coarse/fine location,
  requested only when the related feature is used.
- Removed from the active app: Blinkit, Zepto, overlay, media projection,
  screen capture and Android AccessibilityService.

The retired Android and backend implementations are preserved in private
archive repositories with build, test and restoration documentation. They must
not be copied back into a Swiggy-only Play release accidentally.

## Historical 2026-05-22 rejection

Google rejected an older Beta build because the Play listing and prominent
disclosure did not accurately describe AccessibilityService data access,
including possible visible name, precise location and address data. The old app
was remediated with expanded disclosure, Data Safety guidance and review-video
assets.

That remediation path is historical and superseded for version 0.3.0 because
the active app no longer declares or runs AccessibilityService or screen
capture. Preserve the record for auditability, but do not upload the old review
videos or paste old AccessibilityService copy into the new listing.

## Current policy requirements

Play-facing text and imagery must match the shipped binary:

- Describe Swiggy Instamart only.
- Explain voice/text input, saved-address confirmation, live product discovery,
  exact cart review and the no-checkout/payment boundary.
- Explain that raw voice audio is not stored and raw GPS remains on-device.
- Explain default-off Firebase Analytics/Crashlytics and any consented Google
  Ads conversion measurement accurately.
- Do not state that Beta collects no data: direct MCP still processes saved
  addresses, recent purchase history, requests, catalogue/cart data and a
  pseudonymous connection identity.
- Do not state that financial information is entirely absent. Play classifies
  recent completed orders as **Financial info → Purchase history**, separately
  from payment credentials.
- Keep **Contains ads** as `No` unless the app starts displaying ads.

## Transition rule for old tracks

Data Safety applies to all distributed versions, not only the newly uploaded
bundle. While version 16 or another legacy build is active on any track, the
form and privacy policy must cover both:

1. legacy screen-assisted visual/accessibility behavior, and
2. version 17 direct Swiggy plus optional Analytics/Crashlytics/Ads measurement.

Only after fresh Play API/Console readback proves no legacy bundle is active may
the developer remove legacy screen-capture, precise-location and accessibility
declarations. The temporary legacy section in the privacy policy follows the
same rule.

## Release gates

Before each upload:

1. Read every Play track and choose a genuinely unused version code.
2. Build with the hosted backend URL, current Secret Manager keys and verified
   release signing; never commit secrets.
3. Run unit, instrumentation-build, lint and signed-bundle tasks.
4. Inspect the merged release manifest/AAB for forbidden legacy permissions,
   components and provider package queries.
5. Run the cart-only physical-phone suite, including long recent orders,
   address confirmation, voice/text continuity, cancel/no-mutation and one
   controlled verified-cart mutation.
6. Verify consent-off and consent-on telemetry contains no grocery, item, cart,
   address, GPS, OTP, token or free-text payload.
7. Capture listing screenshots only from the final build and remove stale image
   sets before uploading replacements.
8. Read the committed Play edit back. Never report review approval based only
   on an API commit.

## Console and API boundaries

The Android Publisher API can update releases, listings and images. Data Safety
can be written only with a current exact CSV/template payload; export it from
Play first and review the mapping. App access and some policy declarations may
still require owner attestation in Play Console.

Credential files, if present, must never be printed or committed:

- `D:\Projects\Android Keys\beta-play-publisher.json`
- `D:\Projects\beta\beta-496723-040570e7b0fa.json`

The package is `live.betaapp.android`; the open-testing API track is `beta`.
Commit future edits without `changesNotSentForReview` unless current API
readback demonstrates a changed requirement.

## Canonical files

- App copy and consent: `app/src/main/res/values/strings.xml`
- Active manifest: `app/src/main/AndroidManifest.xml`
- Public privacy asset: `play_store_assets/privacy-policy.html`
- Working privacy text: `PRIVACY_POLICY_DRAFT.md`
- Listing drafts: `play_store_assets/listing/`
- Submission checklist: `PLAY_CONSOLE_SUBMISSION_GUIDE.md`
- Test/release checklist: `PLAY_STORE_TESTING_PREP.md`

Keep these aligned after every behavior, permission, analytics or release change.

## Official references

- Data Safety: `https://support.google.com/googleplay/android-developer/answer/10787469`
- Accurate store behavior: `https://support.google.com/googleplay/android-developer/answer/17006354`
- AccessibilityService policy: `https://support.google.com/googleplay/android-developer/answer/10964491`
- User Data policy: `https://support.google.com/googleplay/android-developer/answer/10144311`
- Android Publisher listings: `https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings`
- Android Publisher Data Safety: `https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety`
