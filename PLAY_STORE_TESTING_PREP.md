# Play Store Testing Prep

Current target: open testing first, using the existing Play developer
account and existing Google Cloud account.

## Build Configuration

- Debug backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`
- Release backend default: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`
- Optional backend overrides:
  - `BETA_BACKEND_DEBUG_URL`
  - `BETA_BACKEND_RELEASE_URL`
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
$env:BETA_BACKEND_RELEASE_URL = "https://beta-backend-staging-kvuem5t7mq-el.a.run.app"
$env:BETA_FEEDBACK_API_KEY = "<same value as Secret Manager BETA_FEEDBACK_API_KEY>"
.\gradlew.bat bundleRelease
```

## Policy Checklist

- AccessibilityService disclosure appears before release automation starts.
- AccessibilityService description explains cart-building assistance and
  stop-before-payment behavior.
- App does not request broad all-files storage access.
- Feedback logs are opt-in.
- Screenshot/log feedback attachments must remain opt-in.
- Release backend must be Cloud Run HTTPS, not emulator-local.
- Debug/emulator builds also default to Cloud Run HTTPS, not local Docker.
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
  - screen/accessibility-derived data used for automation
- Complete AccessibilityService declaration.
- Add a short demo video showing consent, command, cart-only stop, and feedback.

## Open Test Smoke

1. Install from open testing.
2. Start Beta and accept disclosure.
3. Enable accessibility and screen capture.
4. Run one Blinkit cart-only order.
5. Submit "Worked" feedback.
6. Submit "Report issue" feedback with logs enabled.
7. Verify both feedback rows reach the backend.
