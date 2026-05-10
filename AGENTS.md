# Android Beta App Automation Instructions

## Project Context

This is an Android beta app that helps automate ordering-like flows in Blinkit. The app may use Android app UI, `AccessibilityService`, screen capture permission, overlay/status UI, cross-app interaction with Blinkit, and emulator-based testing.

The main development goal is to make the app more reliable and automate repeatable testing of flows like `order butter` or `order pencil`.

## Safety Boundary

Do not automate checkout or payment. Never implement code or tests that place a real order, proceed to payment, confirm checkout, store payment credentials, bypass Blinkit safeguards, abuse Blinkit systems, run high-volume automation, or scrape large amounts of data.

Testing should stop at search, product selection, add-to-cart, and confirming cart increment or app success state.

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
