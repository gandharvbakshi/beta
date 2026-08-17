# Beta Privacy Policy

**Last updated: August 17, 2026**

Beta is a cart-only assistant for Swiggy Instamart. It helps you find products,
review proposed cart changes and add only the items you confirm. Beta always
stops before checkout and payment.

## Information Beta processes

- Grocery requests you type or dictate.
- A pseudonymous Beta installation identifier used to maintain your connection
  and protect one-time cart confirmations.
- An encrypted Swiggy connection token, saved address details, recent Instamart
  purchase history, address-specific product results and availability, proposed
  selections, current cart contents and the saved address you confirm.
- Basic app and device information needed for security, reliability and support.
- Feedback text and diagnostic logs only when you choose to submit them.

Beta does not receive your Swiggy OTP or payment details. It does not place an
order, complete checkout or make a payment.

## Voice and location

When you tap the microphone, Android's speech-recognition provider may process
your voice and return text to Beta. Beta does not upload or store raw voice
audio.

Location permission is optional. If granted, your phone reverse-geocodes the
current device location and uses the result only to rank nearby saved Swiggy
addresses. Raw GPS coordinates stay on the phone and are not sent to Beta's
backend or analytics. You always choose and confirm the delivery address.

## Optional analytics and crash reporting

Google Analytics for Firebase and Firebase Crashlytics are off by default. If
you opt in, Beta records pseudonymous product events such as first open, app
open, Swiggy connection, activation, address confirmation, product discovery,
verified cart updates, feedback prompts and D1/D5/D7/D28 retention milestones.
Firebase may process an app-instance identifier, device/app information and an
approximate location derived from the network connection.

These events are used to improve Beta, diagnose reliability and measure which
Google Ads campaigns lead to activation or a verified cart. Grocery requests,
product names, cart contents, saved addresses, Swiggy order details, feedback
text and raw GPS are never sent to Firebase Analytics or Google Ads. Android
Advertising ID collection and personalised ads are disabled. You can change
analytics consent at any time in Beta Settings.

## Why information is used

- To keep your Swiggy connection working securely.
- To show and rank saved addresses while keeping the final choice with you.
- To search the live catalogue for the confirmed address and rank relevant
  available products using recent choices.
- To show an exact cart plan, make only the changes you confirm and verify the
  resulting cart.
- To provide support, investigate failures and improve reliability.
- If you opt in, to understand activation, retention and campaign performance.

## Sharing and storage

Beta sends the information needed for the cart flow to its backend on Google
Cloud and to Swiggy's authorised MCP service. Swiggy connection tokens are
encrypted at rest. Google processes opted-in analytics and crash data as a
service provider; selected pseudonymous conversion events may be linked to
Google Ads for campaign measurement. Beta does not sell personal data.

## Older test versions

Versions distributed before 0.3.0 may have offered Blinkit and a
screen-assisted mode. During a user-started flow, those older versions could
use Android screen capture and AccessibilityService to process visible grocery
app text, product information, cart contents, buttons and visible delivery
details. The 0.3.0 Swiggy-only app does not request screen capture, overlay or
AccessibilityService access. This section will remain while an older test
bundle is still distributed through any Google Play track.

## Your choices and deletion

- You decide when a cart flow starts and can cancel before any cart update.
- You choose and confirm the address and the proposed cart changes.
- Voice, location, analytics, feedback and diagnostic-log sharing are optional.
- You can disconnect Swiggy or turn analytics off in Settings.
- To request deletion, email `gandharv@musicaigeneration.com` with the subject
  `Delete my Beta data`. Include any tester email address you used and say the
  request is for Beta. We review and action deletion requests within 14 days.

Questions and deletion requests: `gandharv@musicaigeneration.com`.
