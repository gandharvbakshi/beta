# Beta Privacy Policy Draft

Beta helps users add requested grocery items to a supported grocery app cart.
It stops before payment and requires the user to review the cart.

## Data Used

- User order instructions.
- Screen capture and AccessibilityService information from supported grocery
  apps while an order is running.
- Visible grocery app screen details that may include product names, prices,
  cart contents, buttons, and delivery details such as the user's name, precise delivery location, delivery address, locality, or delivery-area header text if those details are shown by the grocery app.
- App version, build type, device model, Android version, and order result.
- Optional feedback text.
- Optional diagnostic logs when the user enables log sharing.

## Why Data Is Used

- To understand the requested grocery items.
- To find product results, add items to cart, verify the cart, and confirm the
  delivery context visible in the grocery app during the cart-building flow.
- To improve reliability from tester feedback.
- To diagnose failures when the user chooses to send logs.

## User Control

- The user starts each automation flow.
- Beta stops before checkout/payment.
- Feedback logs are optional.
- Screenshots/logs should not be sent with feedback unless the user explicitly
  chooses to include them.
- Beta does not sell personal or sensitive user data or use grocery screen data
  for advertising.
- The user can clear learned preferences from the app.

## Backend

Beta sends automation and feedback requests to the Beta backend hosted on
Google Cloud. Feedback is stored separately from ordering telemetry.

This draft must be reviewed before publishing and should be hosted at a stable
URL before Play Console submission.
