# Play Store Policy Knowledge Base

Last updated: 2026-05-22

## Current App Context

- Play app name: `Beta`
- Package / application ID: `live.betaapp.android`
- Android namespace and Kotlin package: `com.example.beta`
- Core feature: user-started, cart-only grocery assistance for Blinkit and Swiggy Instamart.
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

This workspace currently did not contain the expected service-account key:

- `D:\Projects\Android Keys\beta-play-publisher.json`

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

## Useful Official References

- AccessibilityService API policy: `https://support.google.com/googleplay/android-developer/answer/10964491`
- User Data policy / prominent disclosure: `https://support.google.com/googleplay/android-developer/answer/10144311`
- Android Publisher listings API: `https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings`
- Android Publisher Data Safety API: `https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety`
