# Beta Privacy Policy Draft

Beta helps users add requested grocery items to a supported grocery app cart, currently Swiggy Instamart or Blinkit.
For Swiggy Instamart, Beta uses MCP first and keeps a reversible screen-assisted fallback if needed.
It stops before payment and requires the user to review the cart.

## Data Used

- User order instructions.
- For direct Swiggy Instamart use: a pseudonymous Beta installation identity,
  encrypted Swiggy connection token, saved address details, address-specific
  product results and availability, go-to and recent-order product history,
  current cart contents, and the user's selected address.
- For Blinkit and the optional Swiggy screen-assisted fallback: screen
  capture and AccessibilityService information while a user-started
  cart-building flow is running.
- Visible supported grocery app screen details that may include product names, prices,
  cart contents, buttons, and delivery details such as the user's name, precise delivery location, delivery address, locality, or delivery-area header text if those details are shown by the grocery app.
- App version, build type, device model, Android version, and order result.
- Optional feedback text.
- Optional diagnostic logs when the user enables log sharing.
- If the user grants location permission, the current device location is
  reverse-geocoded on the phone only to rank nearby saved Swiggy addresses.
  Raw GPS coordinates are not sent to Beta's backend or persisted by Beta.

## Why Data Is Used

- To understand the requested grocery items.
- For supported grocery apps, to find currently available product results for
  the confirmed delivery address, rank those results using preferences and
  recent choices, add only user-confirmed items to the cart, and verify the cart.
- To improve reliability from tester feedback.
- To diagnose failures when the user chooses to send logs.

## User Control

- The user starts each automation flow.
- Beta can suggest a saved Swiggy address using recent choices and, with
  optional permission, an on-device current-location match. The user still
  explicitly chooses and confirms the address before direct MCP product discovery.
- Swiggy Instamart uses MCP first, while the screen-assisted fallback remains
  available if needed.
- Beta stops before checkout/payment.
- Beta does not use GPS as the authoritative delivery selector or silently
  change a delivery address.
- Beta may use learned grocery preferences, including shorthand category words
  that resolve to a specific item, to reduce repeated typing without adding
  per-order latency.
- Feedback logs are optional.
- Screenshots/logs should not be sent with feedback unless the user explicitly
  chooses to include them.
- Beta does not sell personal or sensitive user data or use grocery screen data
  for advertising.
- The user can clear learned preferences from the app.

## Backend

Beta sends automation and feedback requests to the Beta backend hosted on
Google Cloud. Swiggy connection tokens are encrypted at rest. Feedback is
stored separately from ordering telemetry.

This draft must be reviewed before publishing and should be hosted at a stable
URL before Play Console submission.
