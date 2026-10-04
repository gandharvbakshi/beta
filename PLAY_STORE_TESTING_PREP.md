# Beta Play and Live Testing Preparation

## Current release preparation status — 4 October 2026

Version `0.3.3` (`versionCode 20`) is the latest completed Open Testing
release. Version `0.3.4` (`versionCode 21`) is a candidate only: it has not
been uploaded to Play. The previously signed bundle predates the latest
grounding fix and must be rebuilt and re-signed before upload. The latest
grounding/dish fix is still pending the final signed rebuild. No Git push or
Play v21 upload has occurred.

The privacy policy was published at
`sites/beta-496723/versions/a7873bd0e33e1319`; the live URL returned HTTP 200
and the Gemini consent wording was verified. The reviewed Photos-only Data
Safety CSV was published under owner authority and the API returned `publish=ok`;
fresh Play Console readback showed Photos/videos 0 of 2 selected. Precise location remains declared
because the Android Geocoder implementation may use the network.

Final source checks: 373 Android unit tests passed with one opt-in private replay
skipped; 739 backend tests plus 282 subtests passed. The exact Python 3.9 v11
image passed its Cloud Build test. Normal service traffic is now 100% on
`intent-oct04-v11`; AI remains owner-allowlisted and OFF by default. Physical
synthetic UI checks passed 13/13; six hosted intent examples passed after fixing
the literal tetra-pack spelling guard. Read-only catalogue matches were 3/3,
11/15 and 14/20; this is not full-basket/cart-write success. No cart, address,
checkout, payment or order was mutated during these tests.

Intent quality remains a limited-preview gate, not a general accuracy claim.
The old 20-case confirmation set was source-audited at 19/20 semantically
correct, with only 18/20 review-ready (C05's safe shared-context request was
rejected and C17's dropped certification qualifier was blocked). A separate
v8 20-case source audit was also 19/20 semantically correct; its corrected
saved replay was 19/20 review-ready after the pack guard. These are small,
curated sets and do not establish the 90% target or live-cart accuracy. Keep
the feature off by default and restricted
to owner-approved installation allowlisting. Do not present it as broadly
available or generally reliable.

These facts supersede the September 6 release snapshot below. Build success is
not proof of Play distribution or a completed transaction.

Current target: Swiggy-only version `0.3.4` (`versionCode 21`), pending final
grounding-fix build, verification, and owner release gates. See
`SWIGGY_HISTORY_MATCHING_RELEASE_20260906.md` for historical matching context.

Current status: the candidate enables checkout review and payment handoff,
with an independent backend emergency switch. Reusable Swiggy reviewer access
and live-transaction evidence remain separate unresolved risks.

Enabled checkout candidate summary: once release approval exists, Beta should
show the full cart, saved address, fees, total and payment method before any
payment handoff. UPI must stay user-approved in the UPI app; Beta should never
collect or store PIN, card number or VPA. Cash on delivery may be returned by
Swiggy. Do not run live payment/order tests in this change.

## Hosted backend

- Google Cloud project: `beta-496723`
- Cloud Run service: `beta-backend-staging`
- Region: `asia-south1`
- URL: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`

Verify the hosted target before phone testing:

```powershell
gcloud run services describe beta-backend-staging --project beta-496723 --region asia-south1 --format="value(status.url)"
curl.exe -s https://beta-backend-staging-kvuem5t7mq-el.a.run.app/health
```

Use Secret Manager for `BETA_BACKEND_API_KEY` and
`BETA_FEEDBACK_API_KEY`. Do not print either value.

## Build and signing

Supported overrides:

- `BETA_BACKEND_DEBUG_URL`, `BETA_BACKEND_RELEASE_URL`
- `BETA_BACKEND_API_KEY`, `BETA_FEEDBACK_API_KEY`
- `BETA_VERSION_CODE`, `BETA_VERSION_NAME`
- `BETA_RELEASE_STORE_FILE`, `BETA_RELEASE_STORE_PASSWORD`
- `BETA_RELEASE_KEY_ALIAS`, `BETA_RELEASE_KEY_PASSWORD`

Run the smallest test first, then the complete release gate:

```powershell
.\gradlew testDebugUnitTest
.\gradlew assembleDebug
.\gradlew assembleDebugAndroidTest
.\gradlew lintRelease
.\gradlew bundleRelease
```

Before upload, inspect the release merged manifest and fail the release if it
contains any of these:

```text
SYSTEM_ALERT_WINDOW
FOREGROUND_SERVICE_MEDIA_PROJECTION
BIND_ACCESSIBILITY_SERVICE
MyAccessibilityService
ScreenCaptureService
com.grofers.customerapp
com.zeptoconsumerapp
com.zepto.customer
```

The active app may request microphone and coarse/fine location just in time.
It must not request screen capture, overlay or AccessibilityService.

## Physical-phone acceptance suite

All live tests stop after a verified cart unless a separate approval-reviewed
release explicitly authorises checkout testing. Never open checkout, place an
order or make a payment in this change.

1. Fresh install: confirm the first screen asks only for Swiggy connection and
   optional analytics consent; no Android permission is requested on launch.
2. Returning user: confirm the voice/text composer is above the fold and the
   connected status is announced correctly at large font scale.
3. Voice/text continuity: type a request, use voice for the next request, then
   edit with text; confirm state and draft content remain coherent.
4. Microphone denial: deny once and permanently; confirm typed input remains
   fully usable and Settings recovery is clear.
5. Address ranking: verify the most recently used address is preferred; grant
   optional location and confirm only a nearby saved address is suggested.
6. Address confirmation: verify the selected saved label and shortened street
   or apartment address are shown and spoken before discovery.
7. Recent orders: replay every recent Instamart order as a read-only discovery
   plan, including the longest order, and compare found/unavailable/ambiguous
   counts without printing item names or address data to logs.
8. Complicated products: cover pack size, quantity, duplicate wording,
   ambiguous brands, unavailable items, substitutes and plural quantities.
9. Review/cancel: generate a long cart plan, inspect all rows, cancel and prove
   the server cart did not change.
10. Controlled mutation: after the plan is verified, apply one small confirmed
    cart plan, read the cart back and remove only the test-added items. If the
    cart update cannot be verified after the follow-up wait, stop and inspect
    Swiggy instead of retrying. Stop if the pre-existing cart cannot be
    distinguished safely. Do not continue to checkout.
11. Session expiry: force or observe a 401 and confirm the app requests Swiggy
    reconnection instead of silently falling back to screen automation.
12. Feedback: exercise success, D1 and D5 prompts; submit one worked and one
    issue response, with diagnostic logs included only by explicit choice.
13. Analytics: with consent off, confirm no Analytics/Crashlytics upload. With
    consent on, confirm only coarse install-level timing, completion and
    reliability signals are recorded, with no grocery, product, address, cart,
    GPS or identity text. If Google Ads conversion measurement is enabled in
    the account, only these same coarse events should be linkable; no shopping
    payload should be present.

## Checkout acceptance checklist

This section is approval-required only. Do not run the live cases until a
separate release explicitly enables checkout.

### Offline checks

1. With the checkout flag off, verify the app still launches into the existing
   cart-only experience and exposes no new checkout entry point.
2. Confirm the checkout flag stays off across app restart, process death and
   background/foreground transitions.
3. Exercise the checkout state machine with mocked data only and confirm it
   cannot write or surface any payment credential, PIN, card or VPA data.
4. Confirm no new Android permissions are requested for checkout.
5. Confirm totals, address labels and payment-method labels render correctly at
   large font scale and do not clip or overlap.
6. Confirm timeout handling does not trigger an automatic retry loop.
7. Confirm a double tap does not duplicate a pending checkout or mutate the
   cart twice.
8. Confirm partial local storage loss falls back to a safe disabled state and
   does not resurrect stale checkout details.
9. Confirm unmatched address, GPS-off and empty-location cases keep checkout
   disabled or force an explicit user re-review.
10. Confirm no grocery, order or payment-ID analytics are emitted from checkout
    plumbing.

### Live tests, approval required

1. Open the approved checkout build and review the full cart, saved address,
   fees, total and payment-method summary before any external payment handoff.
2. Confirm UPI handoff uses the trusted payment-provider bridge into the UPI
   app and never collects or stores PIN, card or VPA data in Beta.
3. Confirm cash on delivery is surfaced only when Swiggy returns it.
4. Confirm the encrypted backend recovery record retains the reviewed cart,
   address and provider receipt only while unresolved, then removes them when
   resolved. Verify the reduced terminal record and no shopping analytics.
5. Confirm backgrounding the app, killing the process, or returning later
   resumes from the persisted recovery state without automatic retry.
6. Confirm a timeout or readback failure stops safely and asks for review
   instead of retrying checkout automatically.
7. Confirm accessibility, large font and delayed-input flows still present the
   same totals and payment-method review before any external handoff.
8. Approval must cover the specific basket and spending limit before any live
   checkout: even opening a UPI bridge may create a pending order, and COD can
   place an order immediately. Do not approve a payment or complete a purchase
   unless that specific action has also been approved by the user.

Use Espresso for Beta UI, UI Automator only for Android/Swiggy UI that cannot be
controlled in-app, and ADB for installation and logs. Never include user-private
order contents, addresses, tokens or authentication codes in committed output.

## Enhanced list understanding privacy and release checks

This optional feature is off by default and requires affirmative in-app
consent. Verify the consent choice persists correctly and that disabled or
failed consent blocks provider requests. With the feature enabled, confirm
only the user-entered typed/transcribed instruction is sent through Beta's
backend to the selected provider and returned as a review-only structured
draft. Confirm saved addresses, GPS, recent-order history and OAuth/connection
tokens are not automatically added. Test adversarially with personal data in
the instruction: explain that users may include such data and it may reach the
provider. Confirm provider keys are backend-only (not in the APK) and
operational logs contain provider, token counts and latency only, never
instruction text. Review all provider disclosures and the Data Safety mapping
before a release; do not assume a legal classification from this checklist.

Measure structured-draft quality against a documented evaluation set and
record the result. The 90% quality target is a measured release criterion,
not a user-facing guarantee. Current v8 evidence is limited to curated cases:
19/20 semantic on the old and separate v8 confirmation sets, with 18/20
review-ready on the old set and 19/20 on the corrected saved replay after the
pack guard. It does not establish general accuracy
or live-cart accuracy. Keep the feature off by default and allowlisted to
owner-approved connections; do not enable a broad connected rollout. Keep
purchase/order tests mocked; no purchases were tested for this documentation
change.

## Store assets

- Keep `play_store_assets/app_icon_512.png`.
- Use the Swiggy-only `play_store_assets/feature_graphic_1024x500.png`.
- Capture new phone and tablet screenshots from the final verified build.
- Delete old Play image sets through the Publisher API before uploading the new
  sets so legacy provider/permission screens cannot remain visible.
- Do not upload the retired AccessibilityService or media-projection review
  videos; those belong only in the private archive.

## Play readback and release

- Package: `live.betaapp.android`
- Open-testing API track: `beta`
- Version 21 is the intended candidate, not yet uploaded. Re-read tracks and
  uploaded bundles immediately before upload; never treat an older snapshot as
  proof a version remains unused.
- Upload the signed AAB, assign it to `beta`, commit the edit, then read the
  track and localized listing back through the API.
- A Play edit commit proves submission, not review approval or availability.

While version 16 is still distributed anywhere, the Data Safety form and
privacy policy must cover the union of its legacy screen-assisted behavior and
version 17's direct Swiggy/analytics behavior. Narrow the declarations only
after fresh Play readback proves no legacy bundle is active on any track.
