# Beta Play Console Submission Guide

## Current release submission status — 4 October 2026

Version `0.3.4` (`versionCode 21`) was uploaded, validated and committed to
Open Testing in Publisher edit `07870429999914262666`. Independent API readback
confirmed the bundle hash, both descriptions and all 24 screenshot hashes.
Console shows `Changes in review` with quick checks running before automatic
review submission. Managed publishing is off. Google approval and tester
availability are not yet confirmed; do not treat API track status `completed`
as approval or resubmit while checks run. Production was not changed.

Android runtime/source `87585b1` and backend `d6ea016` were pushed to GitHub
`main` and read back. Final signed AAB SHA-256:
`fa35799d6b4031c1939057bfbd9788e9f5b62c35fb5497f8871f49a2bbcd59dc`.
Release lint/bundle, upload-certificate and retired manifest/DEX gates passed;
lint reported zero errors and 101 warnings. See
`docs/INTENT_RELEASE_VERIFICATION_2026-10-04.md` for full proof and limits.

The public privacy policy was published at
`sites/beta-496723/versions/a7873bd0e33e1319`; its live URL returned HTTP 200
and the Gemini consent wording was verified. The owner published the reviewed
Photos-only Data Safety CSV; the API returned `publish=ok`, and fresh Console
readback showed Photos/videos 0 of 2 selected. Precise location remains declared because Android's
Geocoder implementation may use the network.

Final source checks: 373 Android unit tests passed with one opt-in private replay
skipped; 739 backend tests plus 282 subtests passed. The exact Python 3.9 v11
image passed its Cloud Build test. Normal service traffic is now 100% on
`intent-oct04-v11`; AI remains owner-allowlisted and OFF by default. Physical
synthetic UI checks passed 13/13; six hosted intent examples passed after fixing
the literal tetra-pack spelling guard. Read-only catalogue matches were 3/3,
11/15 and 14/20; this is not full-basket/cart-write success. No cart, address,
checkout, payment or order was mutated during these tests.

AI quality remains a limited-preview gate. The old 20-case confirmation set
was source-audited at 19/20 semantically correct, with 18/20 review-ready;
C05's safe shared-context request was rejected and C17's dropped certification
qualifier was blocked. A separate v8 20-case source audit was also 19/20
semantically correct, and its corrected saved replay reached 19/20
review-ready after the pack guard. These curated results do not establish the
90% target, general accuracy
or live-cart accuracy. Keep the feature off by default and restricted to
owner-approved installation allowlisting; no broad connected rollout is
enabled or authorized here.

The September 6 promotion record below is historical. Reusable full Swiggy
reviewer access remains a separate requirement; static offline demos do not
satisfy signed-in app access. Pre-approval phone checks stop before live
checkout or payment, and this document is not proof of Play review approval.

Submitted release: `0.3.4` / version code `21`, package
`live.betaapp.android`, open-testing track `beta`, pending Google checks/review
and verified tester availability.

Enabled checkout release candidate: when checkout is approved, Beta should
present the full cart, saved address, fees, total and payment method before
any handoff to payment. UPI stays user-approved in the UPI app and Beta never
receives a PIN, card number or VPA. Cash on delivery may be surfaced when
Swiggy returns it. Keep the legacy-track transition language until Play
readback proves no older bundle remains active.

## 1. Release proof before upload

Do not upload until all of these pass against the final commit:

- Backend full test suite and hosted `/health` check.
- Android `testDebugUnitTest`, `assembleDebug`,
  `assembleDebugAndroidTest`, `lintRelease` and signed `bundleRelease`.
- Physical-phone Swiggy connection, voice/text, saved-address, recent-order,
  long-list, review/cancel and one controlled verified-cart test. If a cart
  update cannot be confirmed, Beta should ask the reviewer to inspect Swiggy
  and stop there; it must not retry automatically or proceed to checkout.
- Release merged-manifest/AAB inspection proving the absence of overlay,
  media-projection, AccessibilityService, Blinkit and Zepto declarations.
- Firebase consent-off and consent-on event checks with no grocery, product,
  cart, address, GPS or free-text values in analytics.
- Final Claude Opus adversarial review and resolution of release blockers.
- Re-review the privacy policy and current Data Safety mapping for optional
  Enhanced list understanding. Do not infer Play data-type or sharing
  classifications from this guide: assess the shipped payload and current
  Play definitions, including typed/transcribed instructions sent through
  Beta's backend to a selected AI provider. Do not edit or submit a Data Safety
  form as part of this documentation change.
- Verify Enhanced list understanding is off by default and requires
  affirmative in-app consent; with consent off, provider calls must fail
  closed. Verify only the user-entered instruction is forwarded, with no
  automatic address, GPS, history or OAuth-token inclusion; verify backend
  logs contain only coarse provider/token-count/latency metadata, never the
  instruction, and provider API keys are absent from the APK.
- Measure the feature's structured-draft quality against a documented test
  set. A 90% quality target is a measured release criterion, not a promise or
  guarantee to users. Current curated evidence (old and separate v8 20-case
  source audits both 19/20 semantic; old review-ready 18/20; corrected replay
  19/20 review-ready after the pack guard) is not general or live-cart
  accuracy. Keep default-off and owner-approved
  allowlisting; do not enable connected audience rollout. Keep all
  purchase/order testing mocked; no purchases were tested for this change.

For any future checkout-enabled build, add one more gate before upload: the
current Console owner must re-review the Data Safety mapping for **Financial
info → Purchase history** and the surrounding order-recovery disclosures, and
the review outcome must be verified in the live Console before anyone says the
mapping was submitted. Do not rely on this document as proof of submission.

The app must never enter checkout, place an order or make a payment during
testing or review.

## 2. Store listing and assets

Use the checked-in `en-US` and `en-GB` listings under
`play_store_assets/listing/`. Both now describe Swiggy voice/text cart building,
full cart/address/fee/total/payment-method review, UPI approval in the UPI
app, cash on delivery when returned by Swiggy, opaque recovery markers and the
checkout boundary, plus default-off analytics and, when the Google account
setup is enabled, coarse Google Ads conversion measurement from the same
privacy-safe events. Keep the public copy plain: Beta may take about a minute
to verify a cart update, and if it cannot safely distinguish the result from
the existing cart it asks the user to inspect Swiggy instead of retrying.

Do not edit screenshots for this docs-only copy change.

- Keep `app_icon_512.png`.
- Upload `feature_graphic_1024x500.png` only after confirming it contains no
  Blinkit or legacy-permission UI.
- Capture every phone and tablet screenshot from the final verified build.
- Delete the old image set for each locale through the Publisher API before
  uploading replacements.
- Do not upload retired AccessibilityService or media-projection review assets.

Recommended release notes:

```text
Swiggy Instamart is now Beta's single, direct cart-building and checkout
experience. This release simplifies setup, supports seamless voice and text,
improves recent address and product ranking, adds clear spoken confirmations,
and removes the old screen-access permissions. Beta now shows the full cart,
delivery address, fees, total and payment method before you approve payment in
the UPI app or continue with cash on delivery.
```

## 3. Privacy policy and app access

The privacy policy was published as Sites version
`sites/beta-496723/versions/a7873bd0e33e1319`; the live
`https://betaapp.live/privacy-policy.html` URL returned HTTP 200 and its Gemini
consent wording was verified. Recheck the URL before a future Play edit.

App access instructions should tell the reviewer:

1. Open Beta and tap **Connect Swiggy**.
2. Complete Swiggy authentication on Swiggy's page. Beta does not see the OTP.
3. Return to Beta, enter a grocery list, choose and confirm a saved address.
4. Review the proposed cart, fees, total and payment method, then stop before
   completing payment.

Provide a reusable working Swiggy reviewer path in Play Console before
promotion. Do not rely on an owner OTP or attest that the static demo provides
all signed-in functions. Do not put credentials in the public listing or repo.

Set **Contains ads** to `No`. Advertising the app through Google Ads does not
mean the app displays ads.

## 4. Data Safety current export and legacy transition

### Current export and release readback — 4 October 2026

Source baseline: Play Console UI Data Safety CSV export, saved 4 October 2026
at 17:28:39 IST (`data_safety_export (2).csv`), before the Photos-only update.
It had 77 selected responses. For **App activity → Other user-generated content**, it
declares collected only, required, non-ephemeral, and App functionality; no
sharing purpose is selected. Shopping instructions typed or transcribed by a
user are plausibly covered by this existing type. The same instruction is
needed by standard matching when Enhanced list understanding is off, so the
optional Gemini path does not by itself make the overall data type optional.

For the Gemini path, Settings first identifies Gemini as the selected public
release provider; the follow-up dialog explains that the typed or transcribed
request is sent through Beta to that provider, that account/location data is
not automatically included, and that the user can cancel or turn the feature
off. The user must affirmatively press **Enable** before the setting changes.
Google Play's current Data Safety guidance exempts transfers based on a
specific user action or a prominent in-app disclosure and consent from the
"sharing" declaration in qualifying cases. The current `ONLY_COLLECTED` answer
is retained on that disclosure-and-consent basis only; this is not a claim
that Gemini is a service provider or a legal guarantee. Reassess if the
consent flow, data payload or provider changes. See [Google Play Data Safety
guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

Keep the instruction non-ephemeral in the form unless provider handling is
verified to meet Play's exact in-memory/no-longer-than-request standard.
Provider retention and training use are not represented as zero or otherwise
guaranteed here.

The Publisher track readback showed version 20 completed on `beta`,
version 3 as an internal **draft**, and no releases on `alpha` or `production`;
version 16 was not an active release. The internal v3 draft is not a
distributed release. The exported form included legacy-related answers.

The export also selects **Photos and videos → Photos** as collected-only,
required, non-ephemeral, App functionality. The active v20 Android manifest
does not request camera/photo/media-read permissions, the app has no image
picker/capture/upload path, and `FeedbackPayload` sets
`include_screenshot=false`. Screenshot capture found in instrumentation tests
is test-only. With v16 inactive and v3 still draft, Photos was identified as a
legacy-only declaration. The owner published the reviewed Photos-only CSV
through the Data Safety API; it returned `publish=ok`. The update deselected
`PSL_PHOTOS` and cleared its dependent usage answers only. Fresh Console
readback showed Photos/videos 0 of 2 selected on October 4.
The local helper branch `scripts/update_play_data_safety.py
--remove-retired-photos-only` validates the current photo-row template, makes
that narrow transformation, and refuses `--publish`. Its 4 October dry run
normalized 20 photo rows; 5 response values changed, with every non-Photos row
and field preserved. Output is `logs/data_safety_photos_only_dryrun.csv`.
Precise location remains declared: although Beta does not send raw coordinates
to its backend or analytics, Android's geocoder implementation may use the
network, and the recipient/handling is device-provider dependent.

### Historical conservative v16/v17 union mapping (audit reference)

The following transition mapping was written while legacy v16 could be active;
it is retained as historical context and is not a fresh claim that v16 remains
distributed. Google's Data Safety form covers the union of versions currently
distributed on Play.

Conservative transition answers:

- Collects user data: `Yes`
- Data encrypted in transit: `Yes`
- Users can request deletion: `Yes`
- All data optional: `No`; direct Swiggy cart functionality requires some data
- Data sharing: disclose Google/Firebase/Swiggy service-provider processing and
  opted-in Google Ads conversion measurement according to the current form

Declare the applicable types and purposes:

| Play category | Transition disclosure |
| --- | --- |
| Personal info → Name | Saved Swiggy address payload if present; also legacy visible delivery UI. App functionality. |
| Personal info → Physical address | Saved/selected Swiggy addresses; also legacy visible delivery UI. App functionality. |
| Financial info → Purchase history | Recent completed Instamart orders used to rank current products. App functionality/personalisation. This is not payment-card data. |
| App activity → App interactions | Requests, flow state, product discovery, confirmations, cart result and opted-in activation/retention events. App functionality and analytics. |
| App activity → Other user-generated content | Grocery instruction text and optional feedback. App functionality/support. |
| User IDs | Pseudonymous Beta installation/connection identity. App functionality, security and opted-in analytics. |
| Device or other IDs | Firebase app-instance/device identifiers when analytics is enabled. Analytics and campaign measurement. Advertising ID is disabled. |
| Approximate location | Firebase may derive approximate location from the network when analytics is enabled; legacy v16 may expose a locality on screen. Analytics/app functionality. |
| Precise location | Legacy v16 visible delivery/map information and current address ranking through Android location/geocoding APIs. Raw coordinates are not sent to Beta's backend or analytics; the Android provider may process them. |
| Photos and videos → Other visual content | Legacy v16 screen capture during a user-started cart flow. |
| App info and performance → Diagnostics | Opted-in Crashlytics and optional feedback diagnostics. Reliability/analytics. |

Normally mark these `No` unless the implementation changes:

- Payment information: Beta does not process payment credentials.
- Messages: Beta does not read SMS or email.
- Audio files: Beta receives recognised text but does not upload/store raw audio.

For advertising purpose, if the form asks, disclose only coarse consented
measurement and keep it separate from shopping data. Grocery requests,
item/product names, carts, addresses, Swiggy purchase details, feedback and
raw GPS are not sent to Firebase Analytics or Google Ads.

Do not hand-author a Data Safety API CSV from memory. Export the current Play
template/form first, map these answers to the current schema, review it, then
write it through `applications.dataSafety` or the Console.

## 5. Narrowing after v17 is the only distributed build

After Play readback proves that no legacy bundle is active on any track:

- Remove the v16 screen-capture visual-content declaration.
- Remove the v16-only precise-location/accessibility-screen categories.
- Remove AccessibilityService and media-projection declarations from Console.
- Remove the temporary **Older test versions** section from the privacy policy.
- Retain direct Swiggy addresses, purchase history, user-generated grocery
  requests, user/app identifiers, app interactions, optional diagnostics and
  analytics-derived approximate geography as applicable.

## 6. Upload and readback

1. Create a temporary edit and re-read every track. Confirm the intended version
   code is unused; never reuse a version already uploaded. Version 21 is the
   intended candidate but remains unuploaded; re-read before relying on that
   code being available.
2. Upload the final signed AAB from
   `app/build/outputs/bundle/release/app-release.aab`.
3. Update the open-testing `beta` track with the verified version and release
   notes. Keep status `draft` until the reviewer-access and release gates pass.
4. Replace both locale listings and every final image set.
5. Commit the edit without `changesNotSentForReview` unless fresh API behavior
   proves that flag is required.
6. Read back the track, version, listing and image counts through the Publisher
   API. A committed edit proves receipt, not approval.
7. After reusable reviewer access is verified, review Publishing overview and
   send the policy/release changes for review. A draft upload is not a rollout.

Play review may take days. Submission can be completed today, but approval and
tester availability cannot be guaranteed by end of day.

## 7. After approval

Install from the Play testing link and repeat the enabled-flow smoke test that
still stops before any live payment approval unless the later live gate is
explicitly authorized. Confirm the Play-delivered manifest has no legacy
permissions, verify analytics consent, submit one worked and one issue feedback
response, and monitor Firebase, Cloud Run and Play vitals. Promote beyond open
testing only after this readback.
