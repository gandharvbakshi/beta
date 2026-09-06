# Beta Privacy Policy

**Last updated: September 6, 2026**

Beta helps you build and review Swiggy Instamart carts by voice or text. It
helps you find products, review proposed cart changes and add only the items
you confirm. In the enabled checkout build, Beta also shows the full cart,
delivery address, fees, total and payment method before you hand off to
payment.

## Information Beta processes

- Grocery requests you type or dictate.
- A pseudonymous Beta installation identifier used to maintain your connection,
  verify cart updates and protect one-time confirmations.
- An encrypted Swiggy connection token, saved address details, recent Instamart
  purchase history, address-specific product results and availability, proposed
  selections, current cart contents and the saved address you confirm.
- Basic app and device information needed for security, reliability and support.
- Feedback text and diagnostic logs only when you choose to submit them.

Beta does not receive your Swiggy OTP, PIN, card number or VPA. It does not
store raw voice audio. The enabled checkout build requires separate
confirmation before the order is placed.

## Checkout and payment

When checkout is enabled, Beta presents the full cart, saved address, fees,
total and payment method before any payment handoff. UPI approval happens in
your UPI app. Beta never asks for your PIN, card number or VPA. If Swiggy
returns cash on delivery, Beta can surface that option too.

For recovery only, Beta keeps an encrypted backend record containing the
confirmed cart, delivery address, selected payment method, provider receipt
and transaction identifiers while an order attempt is unresolved. Resolved
records are reduced to the attempt ID, order IDs, status and recovery
timestamps; the basket, address and payment link are removed. Unresolved
records do not expire automatically, because forgetting them could allow a
duplicate order. The app stores only an opaque attempt ID and payment-handoff
marker in no-backup storage. Grocery, order and payment IDs are not used for
analytics. No new Android permissions are required for this flow.

## Voice and location

When you tap the microphone, Android's speech-recognition provider may process
your voice and return text to Beta. Beta does not upload or store raw voice
audio.

Location permission is optional. If granted, your phone reverse-geocodes the
current device location and uses the result only to rank nearby saved Swiggy
addresses. Beta does not send raw GPS coordinates to its backend or analytics.
Android's location and geocoding providers may process the location to return
the nearby area. You always choose and confirm the delivery address.

## Optional analytics and crash reporting

Google Analytics for Firebase and Firebase Crashlytics are off by default. If
you opt in, Beta records pseudonymous usage timing and reliability signals so
we can understand whether people can connect, verify a cart, complete feedback
and return to the app. Those same coarse events may also be linked to Google
Ads conversion measurement when the Play/Google account setup is enabled.
Firebase may process an app-instance identifier, device/app information and an
approximate location derived from the network connection.

These signals are used to improve Beta, diagnose reliability and measure
campaign performance at a coarse level only. They are limited to counts,
timing, completion states and verified-cart / return events; grocery requests,
product names, cart contents, saved addresses, Swiggy order details, feedback
text, raw GPS and other shopping payloads are never sent to Firebase Analytics
or Google Ads. Android Advertising ID collection and personalised ads are
disabled. You can change analytics consent at any time in Beta Settings.

## Why information is used

- To keep your Swiggy connection working securely.
- To show and rank saved addresses while keeping the final choice with you.
- To search the live catalogue for the confirmed address and rank relevant
  available products using recent choices.
- To show an exact cart plan, make only the changes you confirm and verify the
  resulting cart after a short delay.
- To provide support, investigate failures and improve reliability.
- If you opt in, to understand app reliability and return visits.

## Sharing and storage

Beta sends the information needed for the cart and checkout flow to its backend
on Google Cloud and to Swiggy's authorised MCP service. Swiggy connection
tokens are encrypted at rest. Beta also keeps a small no-backup attempt marker
locally when checkout is waiting for external completion; it contains no cart
contents, credentials or other shopping data. Google processes opted-in
analytics and crash data as a service provider, and the data is limited to
coarse timing, counts, reliability states and, when enabled, coarse Google Ads
conversion measurement. Beta does not sell personal data.

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

Deletion covers Beta connection tokens, saved shopping/recovery data and
support records that can be associated with your request. Disconnecting Beta
does not delete your Swiggy account or Swiggy orders. If an order or payment is
unresolved, its recovery record must first be reconciled to avoid a duplicate
purchase. Minimal hashed attempt fences are retained for duplicate-order
prevention; they contain no basket, address or payment credentials. We explain
any necessary retention when responding to a deletion request.

Questions and deletion requests: `gandharv@musicaigeneration.com`.
