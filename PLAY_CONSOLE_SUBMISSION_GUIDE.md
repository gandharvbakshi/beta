# Beta Play Console Submission Guide

Target release: `0.3.3` / version code `20`, package
`live.betaapp.android`, open-testing track `beta`.

Current status: the history-first, enabled Swiggy checkout candidate is the
release target. Version 20 is being prepared as a draft, not a public rollout.
Reusable full Swiggy reviewer access is required before promotion; the four
static offline demo screens do not satisfy signed-in app access. See
`SWIGGY_HISTORY_MATCHING_RELEASE_20260906.md` for the latest verified readback.
Pre-approval phone checks still stop before live checkout or payment, and
older installed builds may remain cart-only until Play readback confirms the
new release is active.

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

Publish and verify the public URL that serves
`play_store_assets/privacy-policy.html` before committing the Play edit.

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

## 4. Data Safety during the v16 to v17 transition

Google's Data Safety form covers every app version currently distributed. While
version 16 remains active on any internal, closed, open or production track,
the form must cover the union of legacy v16 and Swiggy-only v17 behavior.

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
| Precise location | Legacy v16 only, when visible delivery/map information was processed. Raw v17 GPS stays on-device. |
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
   code is unused; never reuse a version already uploaded. Version 20 is the
   current candidate, not a guarantee that it remains unused after this release.
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
