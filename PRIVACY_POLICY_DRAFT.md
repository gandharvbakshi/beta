# Beta Privacy Policy Draft

Beta helps users add requested grocery items to a supported grocery app cart, currently Blinkit, Swiggy Instamart, or Zepto.
It stops before payment and requires the user to review the cart.

## Data Used

- User order instructions.
- For Swiggy Instamart: saved-address choices, product search results, go-to
  items, order history and order details from the last 15 days, and current cart
  contents, accessed through the user's secure connection only when needed for
  a user-started flow.
- For Blinkit and Zepto: screen capture and AccessibilityService information
  while a user-started cart-building flow is running.
- Visible Blinkit or Zepto screen details that may include product names, prices,
  cart contents, buttons, and delivery details such as the user's name, precise delivery location, delivery address, locality, or delivery-area header text if those details are shown by the grocery app.
- A random installation identifier used to protect the Swiggy connection. It is
  encrypted on the Android device; the backend stores only an opaque derived ID.
- App version, build type, device model, Android version, and order result.
- Optional feedback text.
- Optional diagnostic logs when the user enables log sharing.

## Why Data Is Used

- To understand the requested grocery items.
- To use recent Swiggy choices to recommend products, show exact cart changes,
  add only after confirmation, and verify the resulting cart.
- For Blinkit and Zepto, to find product results, add items to the cart, verify
  the cart, and confirm the delivery context visible during the flow.
- To improve reliability from tester feedback.
- To diagnose failures when the user chooses to send logs.

## User Control

- The user starts each automation flow.
- Swiggy does not use screen capture or AccessibilityService. The Android app
  never stores the user's Swiggy access token or OTP; the backend stores the
  access token encrypted.
- Disconnecting Swiggy removes Beta's stored Swiggy connection.
- Beta stops before checkout/payment.
- Feedback logs are optional.
- Screenshots/logs should not be sent with feedback unless the user explicitly
  chooses to include them.
- Beta does not sell personal or sensitive user data or use grocery screen data
  for advertising.
- The user can clear learned preferences from the app.

## Backend

Beta sends automation and feedback requests to the Beta backend hosted on
Google Cloud. The backend stores per-user Swiggy access tokens encrypted and
removes them when the user disconnects. Feedback is stored separately from
ordering telemetry.

This draft must be reviewed before publishing and should be hosted at a stable
URL before Play Console submission.
