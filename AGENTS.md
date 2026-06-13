# Android Beta App Automation Instructions

## Project Context

This is an Android beta app that helps automate ordering-like flows in Blinkit. The app may use Android app UI, `AccessibilityService`, screen capture permission, overlay/status UI, cross-app interaction with Blinkit, and emulator-based testing.

The main development goal is to make the app more reliable and automate repeatable testing of flows like `order butter` or `order pencil`.

## Safety Boundary

Do not automate checkout or payment. Never implement code or tests that place a real order, proceed to payment, confirm checkout, store payment credentials, bypass Blinkit safeguards, abuse Blinkit systems, run high-volume automation, or scrape large amounts of data.

Testing should stop at search, product selection, add-to-cart, and confirming cart increment or app success state.

## Play Store Policy Context

For Play Store rejection, listing, Data Safety, privacy-policy, or AccessibilityService declaration work, read `PLAY_STORE_POLICY_KB.md` before changing files. Keep Play-facing copy aligned across `app/src/main/res/values/strings.xml`, `play_store_assets/privacy-policy.html`, `PRIVACY_POLICY_DRAFT.md`, `PLAY_CONSOLE_SUBMISSION_GUIDE.md`, and `PLAY_STORE_TESTING_PREP.md`.

The current Play-facing package is `live.betaapp.android`, while the Android namespace remains `com.example.beta`. Do not claim Play Console forms were submitted unless a valid Google Play Android Publisher credential was verified and the API call succeeded.

## Preferred Testing Strategy

Use Espresso for this app's own UI, UI Automator for Android system UI and Blinkit UI, ADB scripts for emulator preparation and log collection, and Gradle for build/test execution. Do not use Android Studio clicks as the main automation path.

## Build And Test Commands

Use the smallest relevant command first:

```powershell
.\gradlew assembleDebug
.\gradlew testDebugUnitTest
.\gradlew connectedDebugAndroidTest
```

For one instrumented test class:

```powershell
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=<package>.BlinkitOrderingFlowTest"
```

Do not assume `<package>`. Inspect the repo first.

## Backend Target For Testing

Default emulator and release verification must target the hosted Google Cloud Run backend, not a local Docker backend, unless the task is explicitly backend-local debugging.

- Google Cloud project: `beta-496723`
- Cloud Run service: `beta-backend-staging`
- Region: `asia-south1`
- Hosted URL: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`

Before hosted-backend Android testing, verify:

```powershell
gcloud run services describe beta-backend-staging --project beta-496723 --region asia-south1 --format="value(status.url)"
curl.exe -s https://beta-backend-staging-kvuem5t7mq-el.a.run.app/health
```

For debug builds against the hosted backend, provide `BETA_BACKEND_API_KEY` from Google Cloud Secret Manager without printing it in logs. Local Docker is acceptable only for backend code debugging or isolated offline tests, and its missing local Vision credentials must not be treated as representative of production Cloud Run behavior.

## Android Automation Rules

For app UI, prefer stable resource IDs, useful content descriptions, and Compose test tags if Compose is used. For Blinkit UI, prefer UI Automator, defensive selectors, and `device.wait(Until.findObject(...), timeout)` over arbitrary sleeps.

For screen capture, start from the app and handle Android permission dialogs via UI Automator. For AccessibilityService, prefer ADB enablement on emulator when possible, falling back to UI Automator Settings navigation only if ADB setup fails.

## App-Side Observability

When changing the automation agent, add clear debug logs with the `BetaAgent` tag. Useful lifecycle logs include `INSTRUCTION_RECEIVED`, `BLINKIT_SEARCH_STARTED`, `BLINKIT_SEARCH_BOX_FOUND`, `BLINKIT_SEARCH_QUERY_TYPED`, `BLINKIT_PRODUCT_SELECTED`, `BLINKIT_ADD_TO_CART_CLICKED`, `BLINKIT_CART_INCREMENT_CONFIRMED`, and `FLOW_FAILED`.

In debug builds, prefer visible status text/overlay states such as `STATE: SEARCHING`, `STATE: PRODUCT_SELECTED`, `STATE: ADDING_TO_CART`, `STATE: SUCCESS`, and `STATE: FAILED`.

## Model Delegation Policy

Use the lead model, preferably GPT-5.5, for diagnosing failed Blinkit automation, emulator/logcat failures, AccessibilityService issues, Gradle/build failures, flaky UI Automator behavior, Android lifecycle issues, and final review.

Use cheaper/faster subagents for adding IDs, adding logs, creating boilerplate Espresso/UI Automator tests, creating PowerShell scripts, and scanning files for package names, Activity names, and service names.

## Required Workflow

Before changes, inspect the repo and identify the application package name, main Activity, AccessibilityService component name, whether UI is XML, Compose, or mixed, and existing test dependencies. Share a short plan and make the smallest useful change.

After changes, run the smallest relevant build/test command. If it fails, inspect the failure before patching. Do not mark the task complete unless build/test status is clear.

## Definition Of Done

A task is done only when the code builds or the exact build failure is reported, relevant tests are added or updated where applicable, scripts are documented where applicable, no payment/checkout automation is introduced, and the diff is minimal.

## ChatGPT Handoff

Canonical handoff folder: G:\My Drive\ChatGPT handover.

After meaningful work in this repo, update the flat handoff files for Beta Android grocery automation:

- ${Prefix}PROJECT_STATUS.md: concise current state, branch/commit if relevant, files changed, verification run, blockers, and next 3 actions.
- ${Prefix}ROADMAP.md: update only when priorities, milestones, or sequencing changed.
- ${Prefix}DECISIONS.md: append durable decisions; explicitly supersede stale decisions instead of deleting history.

Use repo evidence, logs, tests, and live system checks for current claims. Do not use memory as proof of live GitHub, Vercel, Render, Play Console, emulator, Docker, or production state. Do not copy secrets, tokens, credentials, private keys, sensitive logs, or user-private raw data into the handoff folder. If writing to G:\ is blocked by sandboxing, ask for approval and mention that the ChatGPT handoff was not updated yet.

### Compatibility And Non-Interference

These handoff instructions apply to Codex CLI, Codex app, IDE extension, and other local Codex surfaces that can read these `AGENTS.md` files. Treat the ChatGPT handoff update as a final reporting step after the normal repo workflow. It must not replace project-specific build, test, safety, Play, deployment, review, or approval instructions.

If handoff instructions conflict with repo-specific work rules, follow the repo-specific rules first, then update the handoff with the verified outcome. On native Windows, use `G:\My Drive\ChatGPT handover\`. In WSL, use `/mnt/g/My Drive/ChatGPT handover/` only if that mount exists and is writable. If the Drive path is unavailable, Google Drive is paused, or sandboxing blocks the write, ask for approval or report clearly that the ChatGPT handoff was not updated yet. If a Codex surface cannot write to Drive but can edit the repo, update the repo-local handoff files as a fallback and mention that the Drive canonical copy still needs syncing.
