# Beta Play Store Go-Live Tasks

Last updated: 2026-08-15

This is the current owner-only checklist for getting `Beta` live on Google Play after the AccessibilityService rejection. API-doable build/listing/release tasks are separated from actions that require Play Console owner review or policy attestation.

## Future Codex Start Here

- Before any future Beta Play upload, search Codex memory for `beta-play-upload-smooth-runbook` and `beta-play-026-upload-result`.
- The detailed local memory notes are `C:\Users\gandh\.codex\memories\extensions\ad_hoc\notes\2026-06-06-beta-play-upload-smooth-runbook.md` and `C:\Users\gandh\.codex\memories\extensions\ad_hoc\notes\2026-06-06-beta-play-026-upload-result.md`.
- Do not rediscover package name, Play track, signing, Secret Manager key source, `gcloud auth login --no-launch-browser`, or the `changesNotSentForReview` rejection from scratch.

## Current State

- App name: `Beta`
- Package / application ID: `live.betaapp.android`
- Android namespace: `com.example.beta`
- Current release config: `versionCode 16`, `versionName 0.2.14`
- Release backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`
- Local Play Publisher credentials and Beta upload signing files exist in ignored local files.
- Fresh Play API readback confirmed that the live-edit `en-US` and
  default-locale `en-GB` listings disclose the Swiggy MCP-first flow, smart
  saved-address ranking, voice/text input, the reversible screen-assisted
  fallback, and the no-checkout boundary. Each locale has four phone, four
  7-inch tablet, and four 10-inch tablet screenshots.
- The in-app prominent disclosure was updated.
- The public privacy policy asset was updated.
- The existing AccessibilityService review video predates the current Swiggy
  MCP-first flow. Replace it if Play requests a new declaration review; direct
  Swiggy MCP use itself does not invoke AccessibilityService.
- The signed `0.2.14` release AAB was built at
  `app/build/outputs/bundle/release/app-release.aab`, passed release lint, and
  its signature, package, version, hosted backend, and non-empty release keys
  were verified without exposing secrets.
- Fresh Play API readback confirmed open-testing track `beta` at version code
  `16`, status `completed`, release name `0.2.14 - Voice and smart Swiggy
  addresses`.
- The 2026-08-12 release was uploaded only after a real eight-item Instamart MCP cart mutation was verified on the physical phone and the cart was restored to empty without entering checkout or payment.
- The raw GitHub privacy-policy and review-video URLs were verified as publicly reachable.
- Data Safety was not updated by API because there is no current Play Console CSV export/template in the repo.
- The Data Safety form, AccessibilityService declaration, and foreground
  service / media projection declaration remain owner-attested Play Console
  surfaces and cannot be read back through the Android Publisher API.

## Owner-Attested Play Console Follow-Up

Keep these declarations aligned in Play Console with the shipped behavior:

1. Confirm the privacy-policy URL and deletion instructions remain current.
2. Confirm the Data Safety form includes the data categories below.
3. Confirm the AccessibilityService declaration covers Blinkit and the optional Swiggy screen-assisted fallback, but not direct Swiggy MCP use.
4. Confirm the foreground service / media projection declaration remains current.
5. If Play surfaces a pending policy declaration or review action, complete it before expanding beyond Open Testing.

For the current Swiggy MCP release, check Publishing overview for any Google-generated policy or review action before widening tester access.

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
- The 2026-06-06 `0.2.6` upload returned `uploaded_bundle_version_code=8` and committed edit `02021199645127430828`.
- The 2026-08-15 release commit used edit `13165206850685336359`. Fresh
  readback confirmed version code `16` / version name `0.2.14`, both localized
  descriptions, and all 24 localized screenshot uploads. Always re-read the
  track immediately before a later upload.
- Do not upload a release AAB unless `BETA_BACKEND_API_KEY` and `BETA_FEEDBACK_API_KEY` are set for `:app:bundleRelease`; a release built without them may install but fail backend calls.
- On 2026-06-06, `D:\Projects\beta\beta-496723-040570e7b0fa.json` could query Android Publisher, but could not read Secret Manager (`403`). The active `gcloud` user account needed normal PowerShell `gcloud auth login --no-launch-browser --account gandharv@musicaigeneration.com` before it could fetch `BETA_BACKEND_API_KEY` / `BETA_FEEDBACK_API_KEY`.
- Play rejected `changesNotSentForReview=true` for the 2026-06-06 upload because changes are sent for review automatically; commit future release edits without that query parameter unless Play behavior changes.
- Normal Gradle builds no longer start logcat capture by default. Set `BETA_AUTO_LOGCAT=true` only when a build should launch `scripts/start-logcat-capture.ps1`.
- For signed release builds, pass `BETA_RELEASE_STORE_FILE` as an absolute path or a path relative to `app/`; Gradle resolves the signing file from the app module.

## Play Console Owner Steps

### 1. Confirm The Store Listing

Check that the long description explicitly says `AccessibilityService`, names Swiggy Instamart and Blinkit, and describes the current Swiggy MCP-first flow with the reversible screen-assisted fallback:

```text
Beta helps testers build grocery carts in Swiggy Instamart and Blinkit from a typed or spoken instruction. Swiggy Instamart uses Beta's direct MCP connection first. Beta ranks saved addresses using recent choices and, with optional permission, an on-device current-location match; the user still chooses and confirms the address. Beta then searches the address-specific Instamart catalog, uses learned preferences and recent product choices to rank live results, shows the exact cart changes, and asks before updating the cart. A reversible screen-assisted Swiggy fallback remains available. Blinkit is labelled as beta in the app.

For direct Swiggy use, Beta processes a pseudonymous installation identity, an encrypted Swiggy connection token, saved address details, address-specific catalog results and availability, go-to and recent-order product history, the current cart, and the saved address the user confirms. If the user grants location permission, current GPS is reverse-geocoded on the phone only to rank saved addresses; raw coordinates are not sent to Beta's cloud or stored by Beta. For Blinkit and the optional Swiggy fallback, Beta may read visible screen data such as product names, prices, cart contents, buttons, and delivery details only during a user-started flow. Beta uses this data only to build the requested cart. It does not see the Swiggy OTP, use GPS as the authoritative delivery selector, place orders, make payments, complete checkout, sell personal data, or use this data for advertising.
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
- Location -> Approximate location and Precise location: visible supported-app delivery area, locality, map pin, or precise delivery location text if shown.
- Personal info -> Name: visible name in supported-app account, delivery, or address UI if shown.
- Personal info -> Physical address: visible supported-app delivery address, apartment/building details, or home header text if shown.
- Personal info -> Physical address: saved Swiggy addresses returned through the direct MCP connection and shown for explicit user selection.
- App activity -> App interactions: Swiggy go-to/recent-order product metadata, address-specific catalog results, selected products, and current cart contents used for app functionality and personalization.
- User IDs: pseudonymous Beta installation identity used to bind the encrypted Swiggy connection and one-time cart confirmation.
- App info and performance -> Diagnostics: optional feedback logs, app version, device model, Android version, and order result when the tester submits feedback.

Data types to mark `No` unless code changes:

- Financial info: Beta stops before payment and does not process payment details.
- Messages: Beta does not read SMS or email.
- Audio files: Android speech recognition may produce order text, but Beta does not store or upload raw audio in the current release.
- With optional permission, Beta processes the current device location on-device
  to rank nearby saved Swiggy addresses. It does not transmit or persist raw GPS
  coordinates for the direct Swiggy address-selection flow.

API note: `applications.dataSafety` expects an exact `safetyLabels` CSV payload. It does not export or generate the form. Export/download the current Data Safety CSV/template first if you want me to upload it by API.

### 4. Complete AccessibilityService Declaration

Answers:

- Does the app use AccessibilityService API? `Yes`
- Is the app an accessibility tool? `No`
- Does the app collect and/or share personal or sensitive user data using AccessibilityService API? `Yes`

Core purpose:

```text
For Blinkit and the optional Swiggy screen-assisted fallback, Beta uses AccessibilityService after the user starts a cart-building flow. Direct Swiggy MCP use does not use AccessibilityService. In a screen-assisted flow, Beta reads visible text, content descriptions, buttons, window structure, and delivery details such as name, precise delivery location, and address if shown so it can tap the controls needed to add user-requested items to the cart. Beta stops before checkout/payment and never places an order or pays.
```

Data accessed:

```text
Visible text, content descriptions, view IDs, bounds, clickable state, product names, prices, cart contents, buttons, and delivery details such as name, precise delivery location, delivery address, locality, and delivery-area/header text from Blinkit or the optional Swiggy screen-assisted fallback during an active user-started flow.
```

Collection/sharing explanation:

```text
During an active user-started Blinkit or optional Swiggy screen-assisted cart-building flow, Beta accesses visible text, content descriptions, view IDs, bounds, clickable state, product names, prices, cart contents, buttons, and delivery details such as name, precise delivery location, delivery address, locality, and delivery-area/header text if shown. Beta sends this screen context to the Beta backend only to build the grocery cart requested by the user. Direct Swiggy MCP use does not use AccessibilityService or screen capture. Beta does not start a screen-assisted flow until the user accepts the prominent disclosure and grants Android permissions. Beta stops before checkout/payment and does not place orders or make payments.
```

Why AccessibilityService is needed:

```text
Blinkit does not expose an approved cart/search integration used by this prototype. AccessibilityService lets Beta understand and interact with the same visible controls the user sees after the user starts the flow, so it can build a cart and stop for manual review before checkout. Swiggy uses the direct MCP connection first; AccessibilityService is used for Swiggy only when the user explicitly switches to the reversible screen-assisted fallback.
```

Video URL: use the AccessibilityService review video URL above.

### 5. Complete Foreground Service / Media Projection Declaration

Select the closest task option for media/content projection or screen capture.

Use this explanation:

```text
For Blinkit and the optional Swiggy screen-assisted fallback, Beta uses Android screen capture after the user starts an active cart-building flow. Screen capture lets Beta understand the visible grocery app screen so it can help add requested items to the cart and stop before checkout/payment. The user can stop the flow, and Beta does not capture screens in the background for unrelated purposes. Direct Swiggy MCP use does not use screen capture.
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
2. Grant the disclosed screen permissions and run one Swiggy Instamart
   cart-only smoke test.
3. Run one Blinkit cart-only smoke test.
4. Submit one worked feedback item.
5. Submit one issue feedback item with logs enabled.
6. Verify feedback reaches the backend.

For production:

1. Promote only after open testing is accepted and smoke-tested.
2. Use a staged rollout.
3. Monitor Play vitals, backend logs, and tester feedback.
