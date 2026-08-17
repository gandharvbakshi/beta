# Beta: Swiggy Instamart cart assistant

Beta is an Android app that helps a user build a Swiggy Instamart cart by
voice or text. The app uses Swiggy's authorised MCP connection, shows saved
addresses and live products, asks for confirmation before changing the cart,
verifies the result and stops before checkout and payment.

## Current product

- Swiggy Instamart only; Blinkit, Zepto and screen-assisted automation are not
  part of the active app.
- Voice and text are interchangeable within the same flow.
- Android speech recognition is requested only when the microphone is tapped.
- Text-to-speech prefers an Indian English male voice when the installed Google
  TTS engine provides one.
- Location is optional and requested only for nearby-address ranking. Raw GPS
  remains on the phone; the user still chooses and confirms the address.
- Recent Swiggy addresses and product choices improve ranking.
- Every cart plan has a final confirmation and cart-only safety boundary.
- Google Analytics for Firebase and Crashlytics are off by default and enabled
  only after explicit consent.

The retired Blinkit/accessibility/screen-capture implementation is preserved in
private, buildable archive repositories with restoration instructions. It is
not compiled into this app.

## Architecture

1. `MainActivity` owns the lightweight connection, consent and voice/text UI.
2. `SwiggyVoiceOrderCoordinator` manages address selection, discovery, review,
   confirmed cart mutation and spoken readback.
3. The Android client calls the hosted Beta backend on Google Cloud Run.
4. The backend maintains the encrypted Swiggy MCP session, performs read-only
   discovery and recent-order ranking, and applies only a user-confirmed cart
   plan.
5. The flow ends after cart verification. Checkout, payment and order placement
   are deliberately outside the product.

## Privacy boundaries

- Beta never receives the Swiggy OTP or payment credentials.
- Raw voice audio is not stored by Beta.
- Raw GPS coordinates are not sent to the backend or analytics.
- Grocery requests, products, addresses and cart contents are not logged to
  Firebase Analytics or Google Ads.
- Screen capture, overlay and Android AccessibilityService are absent from the
  active manifest.

See `PRIVACY_POLICY_DRAFT.md` for the complete disclosure.

## Development

The package published on Google Play is `live.betaapp.android`; the Android
namespace remains `com.example.beta`.

Hosted test backend:

```text
https://beta-backend-staging-kvuem5t7mq-el.a.run.app
```

Minimum verification:

```powershell
.\gradlew testDebugUnitTest
.\gradlew assembleDebug
.\gradlew assembleDebugAndroidTest
.\gradlew lintRelease
.\gradlew bundleRelease
```

Release builds require the backend keys and signing properties described in
`PLAY_STORE_TESTING_PREP.md`. Never commit keys, OAuth tokens, recent-order
payloads, addresses, screenshots containing personal information or cart data.

## Testing safety

Live tests may search, discover, propose, add to cart and verify the cart.
They must never proceed to checkout, place an order or make a payment. Use one
controlled cart mutation at a time and clean up only the items added by the
test.
