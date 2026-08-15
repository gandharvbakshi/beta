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

- AccessibilityService disclosure appears before a Blinkit or optional Swiggy screen-assisted flow starts.
- AccessibilityService description explains cart-building assistance,
  stop-before-payment behavior, backend processing, and visible grocery-screen
  data that may include name, precise delivery location, and address if shown.
- Swiggy onboarding clearly says the MCP connection is primary and the
  screen-assisted path is a reversible fallback.
- Direct Swiggy disclosure covers its encrypted connection token, saved-address
  selection, address-specific catalog, recent-choice/history signals, current
  cart, and locally learned shorthand preferences.
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
  - screen/accessibility-derived data used for Blinkit and the optional Swiggy
    screen-assisted automation
  - direct Swiggy saved addresses, selected address, address-specific catalog
    results, go-to/recent-order product metadata, current cart contents, and
    pseudonymous installation identity
  - approximate and precise current device location when the user enables smart
    address suggestions; Beta reverse-geocodes it on-device only to rank saved
    Swiggy addresses and does not send or persist raw GPS coordinates
  - precise location, name, and physical address if visible in Blinkit or the
    optional Swiggy screen-assisted flow
- Complete AccessibilityService declaration.
- Before the next Play release, replace the existing review video with one that
  shows the Swiggy MCP connection and saved-address/cart confirmation flow, plus
  the disclosure, consent, Accessibility grant, cart-only stop, and feedback for
  the screen-assisted fallback.

## Open Test Smoke

1. Install from open testing.
2. Start Beta and accept disclosure.
3. Connect Swiggy directly; do not enable Accessibility or screen capture for
   this primary MCP check.
4. Run one Swiggy Instamart cart-only order; confirm Beta uses the selected
   saved address, adds only the requested item, verifies the cart, and stops
   before checkout/payment.
5. Switch to the screen-assisted fallback and verify its prominent disclosure.
6. Run one Blinkit cart-only smoke test and confirm the app stops before checkout/payment.
7. Submit "Worked" feedback.
8. Submit "Report issue" feedback with logs enabled.
9. Verify both feedback rows reach the backend.
