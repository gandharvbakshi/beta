# Beta Play Store Go-Live Tasks

Last updated: 2026-06-06

This is the current owner-only checklist for getting `Beta` live on Google Play after the AccessibilityService rejection. API-doable build/listing/release tasks are separated from actions that require Play Console owner review or policy attestation.

## Current State

- App name: `Beta`
- Package / application ID: `live.betaapp.android`
- Android namespace: `com.example.beta`
- Current release config: `versionCode 7`, `versionName 0.2.5`
- Release backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`
- Local Play Publisher credentials and Beta upload signing files exist in ignored local files.
- The Play listing drafts for `en-US` and default locale `en-GB` were updated through the Android Publisher API to name Blinkit, Swiggy Instamart, and Zepto.
- The in-app prominent disclosure was updated.
- The public privacy policy asset was updated.
- The AccessibilityService review video was regenerated.
- A signed local `0.2.5` release AAB was built at `app/build/outputs/bundle/release/app-release.aab`.
- Open testing / API track `beta` now has version code `7` assigned with status `completed`, release name `0.2.5 open testing`, and overlay recovery / Blinkit recovery release notes.
- The 2026-06-06 Android pause / overlay hardening patch is pushed to GitHub in commit `dc5900e` but is not uploaded to Play yet.
- The raw GitHub privacy-policy and review-video URLs were verified as publicly reachable.
- Data Safety was not updated by API because there is no current Play Console CSV export/template in the repo.
- The privacy-policy URL, Data Safety form, AccessibilityService declaration, foreground service / media projection declaration, and final send-for-review are still owner-only Play Console actions.

## Only You Must Do

Do these in Play Console with the owner account:

1. Set the privacy-policy URL and deletion instructions.
2. Complete the Data Safety form for the data categories below.
3. Complete the AccessibilityService declaration.
4. Complete the foreground service / media projection declaration.
5. Open Publishing overview, review the pending changes, and click send changes for review.

For the Zepto release, confirm the committed Play changes in Play Console Publishing overview before final owner submission/review steps.

## URLs To Use

Privacy policy:

```text
https://raw.githubusercontent.com/gandharvbakshi/beta/master/play_store_assets/privacy-policy.html
```

AccessibilityService review video:

```text
https://raw.githubusercontent.com/gandharvbakshi/beta/master/play_store_assets/accessibility_review/beta_accessibility_prominent_disclosure_review.mp4
```

Foreground service / media projection review video:

```text
https://raw.githubusercontent.com/gandharvbakshi/beta/master/play_store_assets/foreground_service_media_projection/beta_foreground_service_media_projection_review.mp4
```

Do not use the old SMS Classifier URLs for Beta. Do not use the Beta GitHub Pages URL unless GitHub Pages is enabled and the URL is verified.

## What Codex Can Still Do

- Build a future signed release AAB if the version changes.
- Upload a future signed AAB to the open testing track through the Play Developer API if the service-account permissions still work.
- Update localized store listing text through the Play Developer API if owner review timing is acceptable.
- Write Data Safety through the Play Developer API only after you export/download a current Data Safety CSV/template from Play Console or Google and provide it in the repo.
- Verify raw GitHub asset URLs.

## API Upload Notes

- Current Play package: `live.betaapp.android`.
- Current open-testing track name in the Android Publisher API: `beta`.
- Query current Play versions by creating a temporary edit, reading `edits.tracks.list`, and deleting the edit without committing it.
- Upload AABs through `edits.bundles.upload`, then update `edits.tracks.update` for `beta`, then commit the edit.
- The 2026-06-04 `0.2.4` upload returned `uploaded_bundle_version_code=6` and committed edit `15314746252293635489`.
- The 2026-06-04 `0.2.5` upload returned `uploaded_bundle_version_code=7` and committed edit `07289748393267164803`.
- The next Play upload must use a new version, likely `versionCode 8` / `versionName 0.2.6`, unless the Play beta track has advanced since this note.
- Do not upload a release AAB unless `BETA_BACKEND_API_KEY` and `BETA_FEEDBACK_API_KEY` are set for `:app:bundleRelease`; a release built without them may install but fail backend calls.
- On 2026-06-06, `D:\Projects\beta\beta-496723-040570e7b0fa.json` could query Android Publisher, but could not read Secret Manager (`403`). The active `gcloud` user account also needed browser reauthentication before it could fetch `BETA_BACKEND_API_KEY` / `BETA_FEEDBACK_API_KEY`.
- Normal Gradle builds no longer start logcat capture by default. Set `BETA_AUTO_LOGCAT=true` only when a build should launch `scripts/start-logcat-capture.ps1`.
- For signed release builds, pass `BETA_RELEASE_STORE_FILE` as an absolute path or a path relative to `app/`; Gradle resolves the signing file from the app module.

## Play Console Owner Steps

### 1. Confirm The Draft Store Listing

Check that the long description explicitly says `AccessibilityService`, names Blinkit, Swiggy Instamart, and Zepto, and matches the current app behavior:

```text
Beta helps testers build grocery carts in supported grocery apps, currently Blinkit, Swiggy Instamart, and Zepto, from a typed or spoken instruction. After the user starts a flow, Beta uses screen capture and AccessibilityService to read the visible supported grocery app screen, search for the requested items, add matching products to the cart, and stop before checkout or payment so the user can review everything manually.

Beta is an early testing app. It requires explicit user consent for screen capture and AccessibilityService access. While the user-started cart-building flow is running, Beta may read visible grocery app screen data such as product names, prices, cart contents, buttons, and delivery details including name, precise delivery location, delivery address, locality, or delivery-area header text if shown by the grocery app. Beta sends this screen context to the Beta backend only to build the requested cart. It does not place orders, make payments, complete checkout, sell personal data, or use this data for advertising.
```

### 2. Set Privacy Policy And Deletion Details

Use the Beta privacy-policy URL above.

If Play asks for deletion instructions:

```text
Users can request deletion by emailing gandharv@musicaigeneration.com with the subject line "Delete my Beta data". They should include any tester email address they used and note that the request is for the Beta app. We review and action deletion requests within 14 days. Users can also clear saved preferences in the app.
```

### 3. Complete Data Safety

Conservative answers for current behavior:

- Does the app collect user data? `Yes`
- Is data encrypted in transit? `Yes`
- Can users request deletion? `Yes`
- Is all data optional? `No`
- Is data shared? Treat the Beta backend as service-provider processing for app functionality; do not mark advertising or sale of data.

Data types to declare:

- App activity -> App interactions: typed/spoken grocery instruction, cart-building interaction state, and supported grocery app screen context used for app functionality.
- App activity -> Other user-generated content: user order instruction text, if Play offers this option.
- Photos and videos -> Photos or other visual content: screen capture from supported grocery apps during an active user-started cart-building flow.
- Location -> Approximate location and Precise location: visible grocery app delivery area, locality, map pin, or precise delivery location text if shown.
- Personal info -> Name: visible name in supported grocery app account, delivery, or address UI if shown.
- Personal info -> Physical address: visible delivery address, apartment/building details, or home header text if shown.
- App info and performance -> Diagnostics: optional feedback logs, app version, device model, Android version, and order result when the tester submits feedback.

Data types to mark `No` unless code changes:

- Financial info: Beta stops before payment and does not process payment details.
- Messages: Beta does not read SMS or email.
- Audio files: Android speech recognition may produce order text, but Beta does not store or upload raw audio in the current release.
- Device or other IDs: `No` unless Firebase Installations, advertising ID, or another persistent identifier is added.

API note: `applications.dataSafety` expects an exact `safetyLabels` CSV payload. It does not export or generate the form. Export/download the current Data Safety CSV/template first if you want me to upload it by API.

### 4. Complete AccessibilityService Declaration

Answers:

- Does the app use AccessibilityService API? `Yes`
- Is the app an accessibility tool? `No`
- Does the app collect and/or share personal or sensitive user data using AccessibilityService API? `Yes`

Core purpose:

```text
Beta uses AccessibilityService only after the user starts an order. It reads visible text, content descriptions, buttons, window structure, and delivery details such as name, precise delivery location, and address if shown by supported grocery apps so it can tap the controls needed to add the user-requested items to the cart. It stops before checkout/payment and never places an order or pays.
```

Data accessed:

```text
Visible text, content descriptions, view IDs, bounds, clickable state, product names, prices, cart contents, buttons, and delivery details such as name, precise delivery location, delivery address, locality, and delivery-area/header text from supported grocery app screens during an active user-started flow.
```

Collection/sharing explanation:

```text
During an active user-started cart-building flow, Beta accesses visible text, content descriptions, view IDs, bounds, clickable state, product names, prices, cart contents, buttons, and delivery details such as name, precise delivery location, delivery address, locality, and delivery-area/header text if shown by supported grocery app screens. Beta sends this screen context to the Beta backend only to build the grocery cart requested by the user. Beta does not start this flow until the user accepts the prominent disclosure and grants Android permissions. Beta stops before checkout/payment and does not place orders or make payments.
```

Why AccessibilityService is needed:

```text
Supported grocery apps do not expose a public cart/search API for this prototype. AccessibilityService lets Beta understand and interact with the same visible controls the user sees, after explicit consent, so it can build a cart and stop for manual review before checkout.
```

Video URL: use the AccessibilityService review video URL above.

### 5. Complete Foreground Service / Media Projection Declaration

Select the closest task option for media/content projection or screen capture.

Use this explanation:

```text
Beta uses Android screen capture only after user consent during an active cart-building flow. Screen capture lets Beta understand the supported grocery app screen so it can help add requested items to the cart and stop before checkout/payment. The user can stop the flow, and Beta does not capture screens in the background for unrelated purposes.
```

Video URL: use the foreground service / media projection review video URL above.

### 6. Confirm The New Build Is Pending

Because the in-app disclosure changed after the earlier Play rejection, confirm the new release build is present before resubmitting.

Current Play API state:

- Track: open testing first
- Release name: `0.2.5 open testing`
- Version code: `7`
- Status: completed through Android Publisher API; confirm in Play Console Publishing overview

Release notes:

```text
Fixes Beta overlay recovery after automated Blinkit flows, so the helper control returns once automation is idle. Includes the Blinkit recovery, checkout safety, and overlay stop fixes from the prior open-testing build.
```

### 7. Send Changes For Review

Before sending:

- Confirm Store listing contains the exact term `AccessibilityService`.
- Confirm Data Safety includes precise location, name, and physical address because these can be visible in supported grocery app screens.
- Confirm AccessibilityService declaration is complete and uses the current video URL.
- Confirm Foreground Service / Media Projection declaration is complete and uses the current video URL.
- Confirm the new AAB version code is assigned to open testing.
- Confirm no old SMS Classifier URL is present anywhere.

Then use Play Console Publishing overview to send the changes for review. This final submission is an owner attestation and should be clicked by you after reviewing the declarations.

## After Approval

For open testing:

1. Install from the Play testing link.
2. Run one Blinkit cart-only smoke test.
3. Run one Swiggy Instamart cart-only smoke test.
4. Run one Zepto cart-only smoke test.
5. Submit one worked feedback item.
6. Submit one issue feedback item with logs enabled.
7. Verify feedback reaches the backend.

For production:

1. Promote only after open testing is accepted and smoke-tested.
2. Use a staged rollout.
3. Monitor Play vitals, backend logs, and tester feedback.
