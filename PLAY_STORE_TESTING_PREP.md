# Play Store Testing Prep

Current target: open testing first, using the existing Play developer
account and existing Google Cloud account.

## Build Configuration

- Debug backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`
- Release backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`
- Optional backend overrides:
  - `BETA_BACKEND_DEBUG_URL`
  - `BETA_BACKEND_RELEASE_URL`
- Backend request auth:
  - `BETA_BACKEND_API_KEY`
  - `BETA_FEEDBACK_API_KEY`
  - `BETA_BACKEND_API_KEY` should match Secret Manager `BETA_BACKEND_API_KEY`.
  - `BETA_FEEDBACK_API_KEY` should match Secret Manager `BETA_FEEDBACK_API_KEY`.
- Version values:
  - `BETA_VERSION_CODE`
  - `BETA_VERSION_NAME`
- Optional release signing Gradle properties:
  - `BETA_RELEASE_STORE_FILE`
  - `BETA_RELEASE_STORE_PASSWORD`
  - `BETA_RELEASE_KEY_ALIAS`
  - `BETA_RELEASE_KEY_PASSWORD`

Example release bundle:

```powershell
$props = ConvertFrom-StringData (Get-Content -Raw -LiteralPath key.properties)
$env:BETA_BACKEND_RELEASE_URL = "https://beta-backend-staging-kvuem5t7mq-el.a.run.app"
$env:BETA_BACKEND_API_KEY = "<same value as Secret Manager BETA_BACKEND_API_KEY>"
$env:BETA_FEEDBACK_API_KEY = "<same value as Secret Manager BETA_FEEDBACK_API_KEY>"
$env:BETA_RELEASE_STORE_PASSWORD = $props.BETA_RELEASE_STORE_PASSWORD
$env:BETA_RELEASE_KEY_ALIAS = $props.BETA_RELEASE_KEY_ALIAS
$env:BETA_RELEASE_KEY_PASSWORD = $props.BETA_RELEASE_KEY_PASSWORD
$storePath = (Resolve-Path -LiteralPath $props.BETA_RELEASE_STORE_FILE).Path
.\gradlew.bat --no-daemon "-PBETA_RELEASE_STORE_FILE=$storePath" :app:bundleRelease
```

`BETA_RELEASE_STORE_FILE` must be absolute or relative to `app/`, because the
Android Gradle plugin resolves `storeFile = file(...)` from the app module.
Normal Gradle builds do not auto-start logcat capture. Set
`BETA_AUTO_LOGCAT=true` only when a build should start
`scripts/start-logcat-capture.ps1`.

## Policy Checklist

- AccessibilityService disclosure appears before a Blinkit or Zepto screen-automation flow starts.
- AccessibilityService description explains cart-building assistance,
  stop-before-payment behavior, backend processing, and visible grocery-screen
  data that may include name, precise delivery location, and address if shown.
- Swiggy onboarding clearly says it uses a secure connection rather than screen
  capture or AccessibilityService, and that Beta never sees the OTP.
- Play Store long description explicitly documents `AccessibilityService` use.
- App does not request broad all-files storage access.
- Feedback logs are opt-in.
- Screenshot/log feedback attachments must remain opt-in.
- Release backend must be Cloud Run HTTPS, not emulator-local.
- Debug/emulator builds also default to Cloud Run HTTPS, not local Docker.
- Screenshot automation requires `x-beta-backend-key`; release builds should
  set `BETA_BACKEND_API_KEY`.
- Feedback endpoint requires `x-beta-feedback-key`; release builds should set
  `BETA_FEEDBACK_API_KEY`.

## Play Console Checklist

- Create new app under the existing developer account.
- Upload testing AABs to open testing. In the Android Publisher API this track
  is named `beta`.
- Add privacy policy URL.
- Complete Data Safety using actual data sent:
  - feedback text
  - optional diagnostics logs
  - app/device version metadata
  - screen/accessibility-derived data used for Blinkit/Zepto automation
  - Swiggy saved-address choices, go-to items, product results, order history and
    order details from the last 15 days, and current cart contents
  - the encrypted-on-device random installation identifier used to protect the
    Swiggy connection; the backend stores only its opaque derived identifier
  - precise location, name, and physical address from Swiggy saved addresses or
    if visible in Blinkit/Zepto screens during the user-started flow
- Complete AccessibilityService declaration.
- Before the next Play release, replace the existing review video with one that
  shows both paths: Swiggy connect/confirm/verified-cart without screen access,
  and Blinkit/Zepto disclosure, consent, Accessibility grant, cart-only stop,
  and feedback.

## Open Test Smoke

1. Install from open testing.
2. Start Beta and accept disclosure.
3. Connect Swiggy in the secure browser and confirm the app reaches Ready
   without requesting Accessibility or screen capture.
4. Run one Swiggy Instamart cart-only order; confirm Beta shows the exact diff,
   updates once only after confirmation, reads the cart back, and stops.
5. Enable Accessibility and screen capture for the Blinkit/Zepto path.
6. Run one Blinkit cart-only order.
7. Run one Zepto cart-only order.
8. Submit "Worked" feedback.
9. Submit "Report issue" feedback with logs enabled.
10. Verify both feedback rows reach the backend.
