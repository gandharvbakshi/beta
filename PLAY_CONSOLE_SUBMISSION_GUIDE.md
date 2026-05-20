# Play Console Submission Guide

## Copy-Paste Finish Sheet

Use this section to finish the remaining Play Console screens quickly. The
store listing, graphics, screenshots, and draft internal release were already
uploaded through the Play Developer API on May 20, 2026.

### Current Play State

- Package name: `live.betaapp.android`
- App name: `Beta`
- Internal testing release: `0.2.0 internal`
- Uploaded version code: `2`
- Release status: `draft`
- Store listing title already uploaded: `Beta`
- Short description already uploaded: `Voice-first grocery cart assistant for Blinkit and Instamart`
- Uploaded graphics: app icon `1`, feature graphic `1`, phone screenshots `4`, seven-inch screenshots `4`

### Public Privacy Policy URL

The local policy file is:

```text
play_store_assets/privacy-policy.html
```

Use this exact URL in Play Console after committing and pushing this file to the
public `gandharvbakshi/beta` repo on branch `master`:

```text
https://raw.githubusercontent.com/gandharvbakshi/beta/master/play_store_assets/privacy-policy.html
```

If GitHub Pages is enabled for the `gandharvbakshi/beta` repo from the `master`
branch root, this rendered URL should also work:

```text
https://gandharvbakshi.github.io/beta/play_store_assets/privacy-policy.html
```

Prefer the rendered GitHub Pages URL if it returns `200 OK`. Use the raw GitHub
URL as the fallback. Do not use either SMS Classifier URL for Beta.

Official Google privacy-policy requirement reference:

```text
https://support.google.com/googleplay/android-developer/answer/10144311
```

### App Content -> Privacy Policy

Copy-paste:

```text
https://raw.githubusercontent.com/gandharvbakshi/beta/master/play_store_assets/privacy-policy.html
```

If you enabled GitHub Pages and verified it opens:

```text
https://gandharvbakshi.github.io/beta/play_store_assets/privacy-policy.html
```

### App Access

Question: Does your app use any access restrictions, login credentials, memberships, location-based restrictions, or other special instructions that reviewers need?

Answer:

```text
No
```

Explanation if Play asks:

```text
All reviewable functionality is available without signing in. The app requires Android permissions that reviewers can grant during review, but it does not require a user account, membership, invite code, or demo credentials.
```

### Ads

Question: Does your app contain ads?

Answer:

```text
No
```

### App Category And Contact Details

Category:

```text
Shopping
```

Contact email:

```text
gandharv@musicaigeneration.com
```

Website:

```text

```

Leave website blank unless you publish a Beta-specific website. Do not put the
SMS Classifier website or privacy-policy URL here.

### Store Listing Text

These values were already uploaded through the API, but keep them here if Play
Console asks you to review or re-enter them.

App name:

```text
Beta
```

Short description:

```text
Voice-first grocery cart assistant for Blinkit and Instamart
```

Full description:

```text
Beta helps testers build grocery carts in supported grocery apps from a typed or spoken instruction. After the user starts a flow, Beta reads the visible supported grocery app screen, searches for the requested items, adds matching products to the cart, and stops before checkout or payment so the user can review everything manually.

Beta is an early testing app. It requires explicit user consent for screen capture and Accessibility access. It does not place orders, make payments, or complete checkout.
```

### Content Rating

Use the closest matching option names in Play Console.

App category/type:

```text
Utility, Productivity, or Shopping
```

Violence:

```text
No
```

Sexual content:

```text
No
```

Controlled substances:

```text
No
```

Gambling:

```text
No
```

User-generated content or social sharing:

```text
No
```

Digital goods purchases:

```text
No
```

If asked about shopping or physical goods:

```text
The app helps users build a cart in supported grocery apps, but it stops before checkout and payment. Users manually review and complete any purchase in the grocery app.
```

### Target Audience

Target age:

```text
18 and over
```

Designed for children:

```text
No
```

Reason if asked:

```text
The app is intended for adult testers and is commerce-adjacent because it helps build grocery carts in supported grocery apps.
```

### News Apps

Answer:

```text
No
```

### Financial Features

Answer:

```text
No
```

Explanation if needed:

```text
Beta does not process payments, provide financial advice, or handle payment credentials. It stops before checkout/payment.
```

### Health Apps

Answer:

```text
No, this app does not provide health features and does not access health data.
```

### Data Safety

Official Google reference:

```text
https://support.google.com/googleplay/android-developer/answer/10787469
```

Top-level answers:

Does the app collect or share user data?

```text
Yes
```

Is all user data collected by the app encrypted in transit?

```text
Yes
```

Do you provide a way for users to request that their data is deleted?

```text
Yes
```

Deletion method:

```text
Users can request deletion by emailing gandharv@musicaigeneration.com. Users can also clear saved preferences in the app.
```

Does the app share user data with third parties?

```text
No
```

Rationale:

```text
Data is sent to Beta's backend on Google Cloud as a service provider for app functionality. It is not sold or shared for third-party advertising.
```

Declare these collected data types if the form shows them:

```text
App activity -> App interactions
App activity -> Other user-generated content
Photos and videos -> Photos or other visual content
Location -> Approximate location
Personal info -> Physical address
App info and performance -> Diagnostics
```

For `App activity -> App interactions`:

```text
Collected: Yes
Shared: No
Processed ephemerally: No
Required or optional: Required
Purposes: App functionality, Analytics, Developer communications
```

For `App activity -> Other user-generated content`:

```text
Collected: Yes
Shared: No
Processed ephemerally: No
Required or optional: Required
Purposes: App functionality
```

Reason:

```text
This covers typed or spoken grocery instructions such as "order apple" or "bread, eggs and milk."
```

For `Photos and videos -> Photos or other visual content`:

```text
Collected: Yes
Shared: No
Processed ephemerally: No
Required or optional: Required
Purposes: App functionality
```

Reason:

```text
This covers screen capture from supported grocery apps while the user-started cart-building flow is running.
```

For `Location -> Approximate location`:

```text
Collected: Yes
Shared: No
Processed ephemerally: No
Required or optional: Required
Purposes: App functionality
```

Reason:

```text
Supported grocery app screens may show delivery area or locality text that Beta reads during the cart-building flow.
```

For `Personal info -> Physical address`:

```text
Collected: Yes
Shared: No
Processed ephemerally: No
Required or optional: Required
Purposes: App functionality
```

Reason:

```text
Supported grocery app screens may show saved delivery address or home header text that Beta reads during the cart-building flow.
```

For `App info and performance -> Diagnostics`:

```text
Collected: Yes
Shared: No
Processed ephemerally: No
Required or optional: Optional
Purposes: App functionality, Analytics, Developer communications
```

Reason:

```text
This covers optional diagnostic logs, app version, device model, Android version, and order result when the tester chooses to send feedback.
```

Data types to answer `No` for unless the release behavior changes:

```text
Financial info: No
Messages: No
Contacts: No
Calendar: No
Files and docs: No
Web browsing: No
Device or other IDs: No
Audio files: No
```

Audio note:

```text
Answer No for raw audio collection if the release only uses Android speech recognition and does not store or upload raw audio. The recognized grocery instruction text is covered under App activity / user-generated content.
```

### AccessibilityService Declaration

Official Google reference:

```text
https://support.google.com/googleplay/android-developer/answer/10964491
```

Does your app use the AccessibilityService API?

```text
Yes
```

Is your app an accessibility tool?

```text
No
```

Why does your app need to use AccessibilityService API?

```text
App functionality
```

Core feature that requires AccessibilityService:

```text
Beta uses AccessibilityService only after the user starts an order. It reads visible text, content descriptions, buttons, and window structure from supported grocery apps so it can tap the controls needed to add the user-requested items to the cart. It stops before checkout/payment and never places an order or pays.
```

Data accessed by AccessibilityService:

```text
Visible text, content descriptions, view IDs, bounds, clickable state, product names, prices, cart contents, and visible delivery-area or address header text from supported grocery app screens during an active user-started flow.
```

Why the feature cannot work without AccessibilityService:

```text
Supported grocery apps do not expose a public cart/search API for this prototype. AccessibilityService lets Beta understand and interact with the same visible controls the user sees, after explicit consent, so it can build a cart and stop for manual review before checkout.
```

Prominent disclosure summary:

```text
Before Beta helps in another app, it tells the user that it uses screen capture and AccessibilityService only after the user chooses to start, reads supported grocery app screens, helps build the requested cart, and stops before payment. The user can continue manually whenever they want, and diagnostic feedback is optional.
```

Video URL:

```text
Add a short unlisted YouTube video URL after recording it.
```

Video script to record:

```text
Open Beta. Show the disclosure. Enable Beta in Accessibility settings. Start screen capture. Type or speak "order apple." Show Beta opening a supported grocery app, adding the item to cart, and stopping before checkout/payment. Show that the user manually reviews the cart.
```

### Sensitive Permissions / Declarations

Microphone:

```text
Optional voice command input. Users can type instead. Beta does not store or upload raw audio in the release behavior; it uses the recognized instruction text to build the grocery cart.
```

Foreground service / media projection:

```text
Used only after the user starts a flow and grants screen capture. Beta uses screen capture to understand supported grocery app screens during cart-building and stops before checkout/payment.
```

Overlay / display over other apps:

```text
Used to show Beta's helper/status and stop controls while the supported grocery app is open during a user-started flow.
```

Internet:

```text
Used to send cart-building and optional feedback requests to the Beta backend on Google Cloud over HTTPS.
```

### Internal Testing Release

The draft release already exists through API upload.

Release name:

```text
0.2.0 internal
```

Release notes:

```text
Initial internal testing build for Beta cart-only grocery assistance. This build helps add requested grocery items to a supported app cart and stops before checkout/payment for manual review.
```

Final action for you:

```text
Review the draft internal testing release, confirm all declarations are accurate, then click the final start testing / roll out / submit for review button shown by Play Console.
```

Current app target:

- App name: `Beta`
- Package name / application ID: `live.betaapp.android`
- Android namespace / Kotlin package: `com.example.beta`
- First track: internal testing
- Recommended category: Shopping
- Release backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`

The `com.example.beta` namespace can stay as-is. Play Store package uniqueness is controlled by `applicationId`, and this project now uses `live.betaapp.android`.

## What I Can And Cannot Do

I can build the signed Android App Bundle, verify package IDs, draft Play Console answers, and upload to an internal/testing track through the Play Developer API after you create and grant the service account.

You need to personally review and submit the Play Console policy declarations. Those are developer-owner legal/policy certifications, so I should not click final submission/attestation buttons for you.

## Service Account And JSON

Use this flow to create the JSON key I need for API uploads.

1. Open Google Cloud Console: `https://console.cloud.google.com/`.
2. Select the Google Cloud project connected to your Play developer account.
3. Go to `APIs & Services` -> `Library`.
4. Search for `Google Play Android Developer API`.
5. Click it and choose `Enable`.
6. Go to `IAM & Admin` -> `Service Accounts`.
7. Click `Create service account`.
8. Service account name: `beta-play-publisher`.
9. Service account ID: keep the generated value, usually `beta-play-publisher`.
10. Description: `Uploads Beta Android testing releases to Google Play`.
11. Click `Create and continue`.
12. For Google Cloud project role, you can skip broad roles unless your org requires one.
13. Finish service-account creation.
14. Open the new service account.
15. Go to the `Keys` tab.
16. Click `Add key` -> `Create new key`.
17. Choose `JSON`.
18. Click `Create`.
19. Save the downloaded file outside Git, preferably:

```text
D:\Projects\Android Keys\beta-play-publisher.json
```

Do not paste the JSON into chat. Do not commit it. The repo ignores common service-account JSON names, but the safest place is still outside the repo.

## Grant App Access In Play Console

This is the screen you asked about.

1. Open Play Console: `https://play.google.com/console`.
2. Select the developer account.
3. In the left navigation, open `Users and permissions`.
4. Click `Invite new users`.
5. Email address: paste the `client_email` from the JSON file. It will look like:

```text
beta-play-publisher@<project-id>.iam.gserviceaccount.com
```

6. Under account permissions, avoid broad account-wide release permissions unless Play requires it.
7. Under app permissions, choose the `Beta` app with package `live.betaapp.android`.
8. Grant these app-level permissions:
   - `View app information (read-only)`
   - `Release apps to testing tracks`
   - `Manage testing tracks and edit tester lists`
9. If the API upload later fails because it cannot create or edit releases, add the narrowest available permission related to creating/editing draft releases for this app.
10. Do not grant production release permission unless you want API access to production.
11. Send the invitation.

Some older Google docs and Play Console layouts mention `Setup` -> `API access`. If you see it, use it only for linking the Cloud project. The actual app access is still granted from `Users and permissions`.

## Create The Play App

1. Play Console -> `All apps`.
2. Click `Create app`.
3. App name: `Beta`.
4. Default language: `English (United States)`.
5. App or game: `App`.
6. Free or paid: `Free`.
7. Declarations: accept only after reviewing them yourself.
8. Create the app.

## Store Listing

Suggested values:

- App name: `Beta`
- Short description: `Voice-first grocery cart assistant for Blinkit and Instamart`
- Full description:

```text
Beta helps testers build grocery carts in supported grocery apps from a typed or spoken instruction. After the user starts a flow, Beta reads the visible supported grocery app screen, searches for the requested items, adds matching products to the cart, and stops before checkout or payment so the user can review everything manually.

Beta is an early testing app. It requires explicit user consent for screen capture and Accessibility access. It does not place orders, make payments, or complete checkout.
```

- App category: `Shopping`
- Tags: use grocery, shopping, productivity-style tags only if Play offers them.
- Contact email: `gandharv@musicaigeneration.com` if you want to reuse the SMS Classifier contact address.
- Website: leave blank unless you have a Beta-specific public page.
- Privacy policy: use a Beta-specific public URL. Do not use the SMS Classifier policy URL for Beta.

## Privacy Policy Link

SMS Classifier currently has these public links:

- Rendered GitHub Pages URL: `https://gandharvbakshi.github.io/smsClassifier/privacy-policy.html`
- Raw GitHub fallback URL: `https://raw.githubusercontent.com/gandharvbakshi/smsClassifier/main/privacy-policy.html`

Both returned `200 OK` when checked on May 20, 2026.

You should not reuse the same SMS Classifier privacy-policy URL for Beta because that policy describes SMS permissions, SMS bodies, billing, Firebase services, and SMS classification behavior. That would be inaccurate for this app.

You can reuse the same hosting pattern:

- Preferred: `https://betaapp.live/privacy-policy`
- Acceptable public GitHub Pages option: `https://gandharvbakshi.github.io/beta/privacy-policy.html`
- Fallback if Play accepts it: a raw public GitHub URL for a Beta-specific `privacy-policy.html`

Use `PRIVACY_POLICY_DRAFT.md` as the starting content, then have it reviewed before publishing.

## App Access Form

Recommended answer:

- Does the app require login, membership, location, or other restricted access to review? `No`

If you later add gated backend access or tester-only login, change this and provide demo credentials.

## Ads Form

Recommended answer:

- Does your app contain ads? `No`

## Content Rating

Use conservative answers:

- App type/category: Shopping, productivity, utility, or closest available option.
- Violence: No
- Sexual content: No
- Controlled substances: No
- Gambling: No
- User-generated content/social sharing: No
- Digital goods purchases: No
- Physical goods ordering: explain that Beta stops before checkout/payment and does not place orders.

## Target Audience

Recommended:

- Target age: `18 and over`
- Designed for children: `No`

Reason: the app is commerce/payment-adjacent even though it stops before checkout.

## Data Safety

Use the actual release behavior. A conservative first pass:

- Does the app collect user data? `Yes`
- Is data encrypted in transit? `Yes`
- Can users request deletion? `Yes`, via the support email and in-app preference clearing where applicable.
- Is all data optional? `No`. Screen/accessibility/order data is required for the core cart-building flow. Feedback logs are optional.

Likely data types:

- App activity -> App interactions: collected for app functionality.
- App activity -> Other user-generated content: user order instruction text, if Play offers this option.
- Photos and videos -> Photos or other visual content: screen capture from supported grocery apps while a flow is running.
- Location or personal info -> Physical address / approximate location: answer `Yes` if visible grocery app screens can expose delivery address, area, or home header text.
- App info and performance -> Diagnostics: optional feedback logs, app version, device model, Android version, order result.
- Audio: answer `No` if the release only uses Android speech recognition and does not store or upload raw audio. The transcript/order text is still collected as app activity.
- Financial info: `No`; Beta stops before payment and does not process payment details.
- Messages: `No`; this app does not read SMS or email.
- Device or other IDs: `No` unless you add Firebase Installations, advertising ID, or another persistent ID.

For sharing, do not mark service-provider processing as third-party sharing unless Play's definition for the specific data type requires it. Beta sends data to the Beta backend to provide app functionality.

## AccessibilityService Declaration

Recommended answers:

- Does the app use AccessibilityService API? `Yes`
- Is the app an accessibility tool? Use `No` unless you intentionally position and support the app as an assistive tool for users with disabilities.
- Core purpose:

```text
Beta uses AccessibilityService only after the user starts an order. It reads visible text, content descriptions, buttons, and window structure from supported grocery apps so it can tap the controls needed to add the user-requested items to the cart. It stops before checkout/payment and never places an order or pays.
```

- Data accessed:

```text
Visible text, content descriptions, view IDs, bounds, clickable state, product names, prices, cart contents, and visible delivery-area/header text from supported grocery app screens during an active user-started flow.
```

- Why AccessibilityService is needed:

```text
Supported grocery apps do not expose a public cart/search API for this prototype. AccessibilityService lets Beta understand and interact with the same visible controls the user sees, after explicit consent, so it can build a cart and stop for manual review before checkout.
```

- What the demo video should show:
  - Open Beta.
  - Show the disclosure/consent copy.
  - Enable Accessibility for Beta.
  - Start screen capture.
  - Type or speak `order apple`.
  - Beta opens a supported grocery app, searches, adds an item, and verifies the cart.
  - Beta stops before checkout/payment.
  - User manually reviews the cart.

Accessibility automation is a sensitive Play review area. Keep every description aligned with the actual behavior: user-started, transparent, supported grocery apps only, cart-only, no checkout, no payment.

## Permissions And Declarations

Manifest permissions currently include:

- `INTERNET`: backend requests and feedback.
- `RECORD_AUDIO`: voice order input, if enabled in the release.
- `SYSTEM_ALERT_WINDOW`: automation status/control overlay.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`: active screen-capture flow.
- `BIND_ACCESSIBILITY_SERVICE`: AccessibilityService registration.

If Play asks for a permission-use explanation:

- Microphone: optional voice command input; users can type instead.
- Screen capture/media projection: only starts after user consent; used to understand supported grocery app screens during a cart-building flow.
- Overlay: shows current automation status and stop controls while the supported grocery app is open.
- Accessibility: reads and interacts with visible supported grocery app UI only during a user-started flow.

## Internal Testing Release

1. Complete required store setup forms enough to create an internal testing release.
2. Build a signed release AAB.
3. Play Console -> `Testing` -> `Internal testing`.
4. Create or select a tester list.
5. Add tester emails.
6. Create a new release.
7. Upload the `.aab`.
8. Release name: `0.2.0 internal`.
9. Release notes:

```text
Initial internal testing build for Beta cart-only grocery assistance. This build helps add requested grocery items to a supported app cart and stops before checkout/payment for manual review.
```

10. Save.
11. Review.
12. You click final rollout/start testing after confirming all declarations are accurate.

## API Upload After JSON Exists

After the service-account JSON exists and has app access, I can upload via the Play Developer API or Gradle Play Publisher. The minimal API flow is:

1. Create an edit.
2. Upload the signed AAB to that edit.
3. Assign the uploaded version code to `internal`, `closed`, or another testing track.
4. Commit the edit.

Before that, I need:

- The service-account JSON file path on this machine.
- The release keystore path and Gradle signing properties.
- Confirmation of the first track, usually `internal`.
- Confirmation that the Play app already exists with package `live.betaapp.android`.
