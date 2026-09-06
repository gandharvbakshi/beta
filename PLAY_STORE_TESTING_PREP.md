# Beta Play and Live Testing Preparation

Current target: Swiggy-only version `0.3.0` (`versionCode 17`) on the open
testing track after all release gates pass.

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

All live tests stop after a verified cart. Never open checkout, place an order
or make a payment.

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

Use Espresso for Beta UI, UI Automator only for Android/Swiggy UI that cannot be
controlled in-app, and ADB for installation and logs. Never include user-private
order contents, addresses, tokens or authentication codes in committed output.

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
- Fresh readback on August 17, 2026 showed maximum active version code `16`, so
  `17` is the next candidate. Read tracks again immediately before upload.
- Upload the signed AAB, assign it to `beta`, commit the edit, then read the
  track and localized listing back through the API.
- A Play edit commit proves submission, not review approval or availability.

While version 16 is still distributed anywhere, the Data Safety form and
privacy policy must cover the union of its legacy screen-assisted behavior and
version 17's direct Swiggy/analytics behavior. Narrow the declarations only
after fresh Play readback proves no legacy bundle is active on any track.
