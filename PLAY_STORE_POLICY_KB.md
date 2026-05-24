# Play Store Policy Knowledge Base

Last updated: 2026-05-22

## Current App Context

- Play app name: `Beta`
- Package / application ID: `live.betaapp.android`
- Android namespace and Kotlin package: `com.example.beta`
- Core feature: user-started, cart-only grocery assistance for Blinkit, Swiggy Instamart, and Zepto.
- Hard boundary: Beta must stop before checkout and payment. It must not place orders or pay.

## Where To Look First

- Google rejection PDF, if present: `MusicMakers Mail - Action Required_ Your app is not compliant with Google Play Policies (Beta).pdf`
- App prominent disclosure copy: `app/src/main/res/values/strings.xml`
- Accessibility service config: `app/src/main/res/xml/accessibility_service_config.xml`
- Manifest service declaration: `app/src/main/AndroidManifest.xml`
- Public privacy policy asset: `play_store_assets/privacy-policy.html`
- Draft privacy policy: `PRIVACY_POLICY_DRAFT.md`
- Human Play Console checklist: `PLAY_CONSOLE_SUBMISSION_GUIDE.md`
- Short operational checklist: `PLAY_STORE_TESTING_PREP.md`

## 2026-05-22 Rejection Summary

Google rejected `Beta (live.betaapp.android)` because:

- The Play Store long description did not clearly document use of `AccessibilityService`.
- The in-app prominent disclosure did not sufficiently explain data accessed through `AccessibilityService`.
- Google specifically named missing data types: `Precise location`, `Name`, and `Address`.

Treat this as a disclosure/listing/Data Safety issue, not a build/package-name issue.

## 2026-05-22 Remediation Status

- App disclosure copy was updated in `app/src/main/res/values/strings.xml`.
- Public privacy-policy asset was updated in `play_store_assets/privacy-policy.html`.
- Play Console copy-paste guidance was updated in `PLAY_CONSOLE_SUBMISSION_GUIDE.md`.
- The `en-US` Play Store listing draft was updated through the Android Publisher API for `live.betaapp.android` with `changesNotSentForReview=true`.
- Data Safety was not updated through the API because that requires an up-to-date Data Safety CSV payload.
- AccessibilityService declaration still requires developer-owner review/submission in Play Console.
- Review video assets in `play_store_assets/accessibility_review/` were regenerated after the prominent disclosure text changed.

## Required Disclosure Positioning

Keep all Play-facing copy aligned with actual behavior:

- Beta uses `AccessibilityService` and screen capture only after the user starts a cart-building flow and gives consent.
- Beta may read visible supported grocery app screen data, including product names, prices, cart contents, buttons, visible text, view metadata, name, precise delivery location, delivery address, locality, and delivery-area/header text if shown by the grocery app.
- Beta sends this screen context to the Beta backend only to build the cart requested by the user.
- Beta stops before checkout/payment and does not place orders or make payments.
- Beta does not sell personal or sensitive user data and does not use grocery screen data for advertising.
- Do not claim Beta is an accessibility tool unless the product is redesigned primarily to serve users with disabilities. Current config intentionally uses `android:isAccessibilityTool="false"`.

## Play Console / API Reality

The Google Play Developer API can update localized store listings through `edits.listings` and can write Data Safety labels through `applications.dataSafety` if an up-to-date Data Safety CSV and valid service-account credentials are available.

The Data Safety API is not a form-answer generator and does not export the current Play Console form. It accepts a POST body whose `safetyLabels` value is the exact CSV text for the Data Safety answers. Use a Play Console export or Google's current sample CSV as the template before writing this API field; do not hand-roll a CSV from memory because question IDs and required rows can change.

Check for these service-account key locations without printing contents:

- `D:\Projects\Android Keys\beta-play-publisher.json`
- `D:\Projects\beta\beta-496723-040570e7b0fa.json`

The root `beta-496723-040570e7b0fa.json` file is intentionally ignored by git. Do not commit it and do not paste its contents into chat.

Do not claim Play Console changes were submitted unless credentials were verified and the API call succeeded. The AccessibilityService permission declaration is still a developer-owner policy attestation and should be reviewed in Play Console by the account owner.

## Resubmission Checklist

1. Update the app prominent disclosure in `strings.xml`.
2. Update `play_store_assets/privacy-policy.html`.
3. Update the Play listing long description to use the exact term `AccessibilityService`.
4. Update Data Safety for all visible grocery-screen data that can be collected, including precise location, name, and physical address when shown on screen.
5. Update the AccessibilityService declaration in Play Console with the same data categories.
6. Regenerate the review video after the disclosure copy changes; the video must show the current disclosure text, consent path, Accessibility settings grant, decline path if requested, and one core cart-building flow.
7. Upload a new build if the app disclosure changed.
8. Resubmit from Play Console Publishing overview.

Zepto support changes the advertised-app surface. Before resubmitting, make sure the Play listing, privacy policy, review assets, and smoke plan all name Blinkit, Swiggy Instamart, and Zepto consistently.

## Review Video Regeneration

- Reusable renderer: `scripts/render_accessibility_review_video.py`.
- Source frames: `play_store_assets/accessibility_review/01_home.png` through `04_android_accessibility_settings.png`.
- Output: `play_store_assets/accessibility_review/beta_accessibility_prominent_disclosure_review.mp4`.
- If the emulator shows Settings ANR dialogs, black boot frames, `init.svc.bootanim=running` after `sys.boot_completed=1`, or `dumpsys window` has no focused windows, stop testing and cold-restart the emulator before further capture attempts. Repeated tap retries after those signs waste time and can pollute review assets.

## Useful Official References

- AccessibilityService API policy: `https://support.google.com/googleplay/android-developer/answer/10964491`
- User Data policy / prominent disclosure: `https://support.google.com/googleplay/android-developer/answer/10144311`
- Android Publisher listings API: `https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings`
- Android Publisher Data Safety API: `https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety`
