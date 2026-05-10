# Codex Implementation Brief: Android Beta App Test Automation for Blinkit Ordering Flow

## Goal

Implement a repeatable local test automation loop for the Android beta app that attempts to automate product ordering actions inside Blinkit.

The automation should help validate the basic flow:

1. Launch the beta app on an Android emulator.
2. Ensure the beta app Accessibility Service is enabled for the emulator/dev environment.
3. Start screen capture from the beta app.
4. Open Blinkit and bring it to the home/search state.
5. Use the beta app overlay/instruction entry UI to submit a command such as `order butter`.
6. Observe whether the beta app searches Blinkit and attempts to add the target SKU to cart.
7. Verify success using app status/log signals and, where practical, Blinkit UI signals.
8. Save logs for debugging when the flow fails.

Important: this automation must **not** complete payment, place an order, or attempt checkout. It should stop at search/product/cart verification only.

---

## Tooling Strategy

Use the right tool for each layer:

| Layer | Tool |
|---|---|
| Beta app internal UI | Espresso |
| Blinkit UI and Android system UI | UI Automator |
| Accessibility enablement, install, logs, emulator prep | ADB / Gradle / PowerShell |
| Debug signal extraction | App logs + visible debug/status overlay |

Do **not** try to implement this entire flow with Espresso only. Espresso is for the app under test. UI Automator is needed for cross-app/system interactions such as Blinkit, Android permission dialogs, Settings, and overlays.

---

## Non-Negotiable Guardrails

1. Do not automate final checkout or payment.
2. Do not store real payment credentials or personal addresses in test code.
3. Keep all changes minimal and scoped to test automation/debug observability.
4. Do not refactor unrelated app logic.
5. Prefer deterministic Gradle/ADB/test automation over Android Studio UI clicks.
6. Add debug-only logs and UI status indicators where needed.
7. If Blinkit UI selectors are unstable, fail gracefully and save diagnostics.
8. Keep package names configurable at the top of the test/script.
9. Do not hardcode user-specific secrets.
10. Do not make irreversible actions in Blinkit.

---

## Phase 0: Inspect Project

Before editing, inspect the repo and identify:

- Android package name of the beta app.
- Main activity name.
- AccessibilityService component name.
- Whether the app uses XML views, Jetpack Compose, or a hybrid UI.
- Existing Gradle structure: Groovy or Kotlin DSL.
- Existing test dependencies.
- Button/text labels for:
  - Start screen capture
  - Start instructions
  - Instruction text input
  - Submit button
  - Status/update text
- Whether the beta app overlay is implemented as an Android overlay/window or normal app UI.
- Blinkit package name installed on emulator.

Likely Blinkit package name to verify:

```text
com.grofers.customerapp
```

Verify with:

```bash
adb shell pm list packages | grep -i grofers
adb shell pm list packages | grep -i blinkit
```

On Windows PowerShell:

```powershell
adb shell pm list packages | Select-String "grofers|blinkit"
```

---

## Phase 1: Add Test Dependencies

Add Android instrumented test dependencies.

### Kotlin DSL example

In `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
```

### Groovy DSL example

In `app/build.gradle`:

```groovy
android {
    defaultConfig {
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation "androidx.test.espresso:espresso-core:3.6.1"
    androidTestImplementation "androidx.test:runner:1.6.1"
    androidTestImplementation "androidx.test:rules:1.6.1"
    androidTestImplementation "androidx.test.ext:junit:1.2.1"
    androidTestImplementation "androidx.test.uiautomator:uiautomator:2.4.0"
}
```

If the project already uses newer compatible versions, do not downgrade. Prefer minimal changes.

---

## Phase 2: Add Stable IDs / Test Tags to Beta App UI

Where practical, add stable identifiers to beta app UI elements.

### XML View example

```xml
<Button
    android:id="@+id/button_start_screen_capture"
    android:text="Start screen capture" />

<Button
    android:id="@+id/button_start_instructions"
    android:text="Start instructions" />

<EditText
    android:id="@+id/input_instruction" />

<Button
    android:id="@+id/button_submit_instruction"
    android:text="Submit" />

<TextView
    android:id="@+id/text_agent_status" />
```

### Compose example

If the app uses Jetpack Compose, add `testTag` modifiers:

```kotlin
Modifier.testTag("button_start_screen_capture")
Modifier.testTag("button_start_instructions")
Modifier.testTag("input_instruction")
Modifier.testTag("button_submit_instruction")
Modifier.testTag("text_agent_status")
```

Do not change UI copy unless required.

---

## Phase 3: Add Debug Observability

Add clear debug logs for the ordering flow. Use a consistent tag such as:

```kotlin
private const val TAG = "BetaAgent"
```

Emit logs like:

```kotlin
Log.i(TAG, "INSTRUCTION_RECEIVED: order butter")
Log.i(TAG, "BLINKIT_SEARCH_STARTED: butter")
Log.i(TAG, "BLINKIT_SEARCH_BOX_FOUND")
Log.i(TAG, "BLINKIT_SEARCH_TEXT_ENTERED: butter")
Log.i(TAG, "BLINKIT_PRODUCT_SELECTED: <product name>")
Log.i(TAG, "BLINKIT_ADD_TO_CART_CLICKED")
Log.i(TAG, "BLINKIT_CART_INCREMENT_CONFIRMED")
Log.i(TAG, "FLOW_SUCCESS: target=butter")
Log.e(TAG, "FLOW_FAILED: reason=<reason>")
```

If the app has a visible overlay/status text, show debug-only state such as:

```text
STATE: SEARCHING
TARGET: butter
LAST_ACTION: clicked search box
ERROR: none
```

Success state should be easy for UI Automator to detect:

```text
STATE: SUCCESS
TARGET: butter
```

Failure state should also be visible:

```text
STATE: FAILED
ERROR: search_box_not_found
```

Important: keep this debug observability behind debug build flags if needed. Do not expose overly noisy logs in production builds.

---

## Phase 4: Add Basic Espresso Smoke Test for Beta App

Create:

```text
app/src/androidTest/java/<package>/BetaAppSmokeTest.kt
```

Example for XML Views:

```kotlin
package com.yourpackage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaAppSmokeTest {

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java)

    @Test
    fun betaAppShowsScreenCaptureButton() {
        onView(withId(R.id.button_start_screen_capture))
            .check(matches(isDisplayed()))
    }
}
```

If stable IDs are not available yet, temporarily use text selectors:

```kotlin
onView(withText("Start screen capture"))
    .check(matches(isDisplayed()))
```

But prefer stable IDs.

Acceptance criteria:

```bash
./gradlew connectedDebugAndroidTest
```

or Windows:

```powershell
.\gradlew connectedDebugAndroidTest
```

should run this smoke test successfully on the emulator.

---

## Phase 5: Add UI Automator End-to-End Flow Test

Create:

```text
app/src/androidTest/java/<package>/BlinkitOrderingFlowTest.kt
```

Use UI Automator because this flow crosses app boundaries.

Skeleton:

```kotlin
package com.yourpackage

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlinkitOrderingFlowTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context

    private val betaPackage = "com.yourpackage" // TODO: replace
    private val blinkitPackage = "com.grofers.customerapp" // TODO: verify
    private val targetInstruction = "order butter"
    private val targetSku = "butter"

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        device.pressHome()
    }

    @Test
    fun orderButterAddsItemToCartOrReportsSuccess() {
        launchApp(betaPackage)
        startScreenCaptureIfNeeded()
        launchApp(blinkitPackage)
        bringBlinkitToHomeOrSearchState()
        submitInstruction(targetInstruction)

        val success = waitForSuccessSignal(timeoutMs = 120_000)

        assertTrue("Expected beta app to add butter to cart or report success", success)
    }

    private fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("Could not find package: $packageName")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 15_000)
    }

    private fun startScreenCaptureIfNeeded() {
        val button = device.wait(
            Until.findObject(By.textContains("Start screen capture")),
            5_000
        )
        button?.click()

        // Screen capture permission dialog text can vary by Android version.
        val startNow = device.wait(
            Until.findObject(By.textContains("Start now")),
            5_000
        )
        startNow?.click()
    }

    private fun bringBlinkitToHomeOrSearchState() {
        // Defensive reset. Tune this based on actual Blinkit behavior.
        device.pressBack()
        Thread.sleep(500)
        launchApp(blinkitPackage)

        // Optional if visible.
        device.wait(Until.findObject(By.textContains("Home")), 3_000)?.click()
    }

    private fun submitInstruction(instruction: String) {
        val startInstructions = device.wait(
            Until.findObject(By.textContains("Start instructions")),
            10_000
        ) ?: device.wait(
            Until.findObject(By.textContains("Instructions")),
            5_000
        ) ?: error("Could not find beta app instruction overlay")

        startInstructions.click()

        val input = device.wait(
            Until.findObject(By.clazz("android.widget.EditText")),
            5_000
        ) ?: error("Could not find instruction input")

        input.text = instruction

        val submit = device.wait(
            Until.findObject(By.textContains("Submit")),
            5_000
        ) ?: error("Could not find Submit button")

        submit.click()
    }

    private fun waitForSuccessSignal(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val successState = device.findObject(By.textContains("STATE: SUCCESS"))
                ?: device.findObject(By.textContains("FLOW_SUCCESS"))
                ?: device.findObject(By.textContains("added to cart"))
                ?: device.findObject(By.textContains("View cart"))
                ?: device.findObject(By.textContains("Cart"))

            if (successState != null) return true

            val failureState = device.findObject(By.textContains("STATE: FAILED"))
                ?: device.findObject(By.textContains("FLOW_FAILED"))

            if (failureState != null) return false

            Thread.sleep(1_000)
        }

        return false
    }
}
```

Tune selectors after running once against the actual UI.

---

## Phase 6: Add PowerShell Runner Script

Create:

```text
scripts/run_blinkit_flow_test.ps1
```

Script:

```powershell
$ErrorActionPreference = "Stop"

$Package = "com.yourpackage" # TODO: replace
$Service = "com.yourpackage/.YourAccessibilityService" # TODO: replace
$TestClass = "com.yourpackage.BlinkitOrderingFlowTest" # TODO: replace

New-Item -ItemType Directory -Force -Path logs | Out-Null

Write-Host "Building debug and androidTest APKs..."
.\gradlew assembleDebug assembleDebugAndroidTest

if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed"
}

Write-Host "Installing debug app..."
.\gradlew installDebug

if ($LASTEXITCODE -ne 0) {
    throw "App install failed"
}

Write-Host "Disabling emulator animations..."
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

Write-Host "Attempting to enable Accessibility Service for test emulator..."
$current = adb shell settings get secure enabled_accessibility_services
$current = $current.Trim()

if ($current -eq "null") {
    $current = ""
}

if ($current -notlike "*$Service*") {
    if ($current.Length -gt 0) {
        adb shell settings put secure enabled_accessibility_services "$current`:$Service"
    } else {
        adb shell settings put secure enabled_accessibility_services "$Service"
    }
}

adb shell settings put secure accessibility_enabled 1

Write-Host "Clearing old logs..."
adb logcat -c

Write-Host "Running Blinkit flow test..."
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass"
$testExitCode = $LASTEXITCODE

Write-Host "Saving logs..."
adb logcat -d AndroidRuntime:E "*:S" > logs\blinkit_flow_crash_log.txt
adb logcat -d | Select-String "$Package|BetaAgent|AndroidRuntime|FATAL EXCEPTION|Accessibility|MediaProjection|Blinkit|grofers" > logs\blinkit_flow_full_log.txt

if ($testExitCode -ne 0) {
    Write-Host "Test failed. Check logs/blinkit_flow_full_log.txt and logs/blinkit_flow_crash_log.txt"
    exit $testExitCode
}

Write-Host "Blinkit flow test passed."
```

Important: replace package/service/test class names after inspecting the project.

---

## Phase 7: Optional Helper Scripts

### `scripts/dev_check.ps1`

```powershell
$ErrorActionPreference = "Stop"

.\gradlew clean assembleDebug testDebugUnitTest

if ($LASTEXITCODE -ne 0) {
    throw "Build/unit test failed"
}

Write-Host "dev_check passed."
```

### `scripts/install_debug.ps1`

```powershell
$ErrorActionPreference = "Stop"

.\gradlew assembleDebug installDebug

if ($LASTEXITCODE -ne 0) {
    throw "Install failed"
}

Write-Host "Debug app installed."
```

### `scripts/run_logcat.ps1`

```powershell
$Package = "com.yourpackage" # TODO: replace
adb logcat | Select-String "$Package|BetaAgent|AndroidRuntime|FATAL EXCEPTION|Accessibility|MediaProjection"
```

---

## Phase 8: Acceptance Criteria

Implementation is complete when:

1. Project builds successfully.
2. `connectedDebugAndroidTest` runs on emulator.
3. Basic beta app smoke test passes.
4. PowerShell runner can:
   - build app
   - install app
   - disable animations
   - attempt to enable accessibility service
   - run the Blinkit flow test
   - save logs
5. The Blinkit flow test can at least:
   - launch beta app
   - start screen capture or handle already-started state
   - launch Blinkit
   - find beta app instruction overlay
   - submit `order butter`
   - wait up to 120 seconds for success/failure state
6. Failures produce useful log output in `logs/blinkit_flow_full_log.txt`.
7. The test does not place an actual order or initiate payment.

---

## Expected Iteration Plan

Do not attempt to perfect the full flow in one pass.

Implement in this order:

1. Add dependencies.
2. Add stable beta app IDs/test tags.
3. Add BetaAgent logs and visible debug status.
4. Add simple Espresso smoke test.
5. Add UI Automator test that launches beta app and Blinkit.
6. Add instruction submission.
7. Add success/failure state waiting.
8. Add PowerShell runner.
9. Run and tune selectors.
10. Improve failure diagnostics.

---

## Codex Working Rules

When implementing this brief:

1. Inspect before editing.
2. Report package names and service component name found.
3. Make minimal changes.
4. Do not rewrite unrelated architecture.
5. Add tests under `app/src/androidTest` only unless app debug observability requires small app changes.
6. Do not use arbitrary long sleeps except in polling loops with timeout.
7. Prefer `Until.findObject` / `wait` over blind sleeps.
8. Keep Blinkit selectors defensive and easy to tune.
9. Save logs on every test run.
10. At the end, report:
    - files changed
    - commands run
    - test results
    - remaining manual setup required
    - any selectors that need tuning

---

## Manual Setup Notes

Before running the E2E test, ensure:

1. Android emulator is running.
2. Blinkit is installed on the emulator.
3. Blinkit is logged in if needed.
4. Delivery location is set if needed.
5. Test should not proceed to payment or order placement.
6. If ADB accessibility enablement fails, enable the Accessibility Service manually once, then re-run the script.
7. Screen-capture permission may still require UI confirmation depending on Android version.

---

## Useful Commands

List devices:

```powershell
adb devices
```

List installed packages:

```powershell
adb shell pm list packages | Select-String "grofers|blinkit"
```

Run all instrumented tests:

```powershell
.\gradlew connectedDebugAndroidTest
```

Run only Blinkit flow test:

```powershell
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.yourpackage.BlinkitOrderingFlowTest"
```

Clear logs:

```powershell
adb logcat -c
```

Capture logs:

```powershell
adb logcat -d > logs\full_logcat.txt
```

Filter relevant logs:

```powershell
adb logcat | Select-String "BetaAgent|AndroidRuntime|FATAL EXCEPTION|Accessibility|MediaProjection"
```

Disable animations:

```powershell
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

Enable accessibility service, after replacing service name:

```powershell
adb shell settings put secure enabled_accessibility_services "com.yourpackage/.YourAccessibilityService"
adb shell settings put secure accessibility_enabled 1
```

---

## Final Note

The most important part of this project is not the test framework itself. It is making the beta app observable enough that automation can tell what happened.

Prioritize clear state transitions:

```text
IDLE -> INSTRUCTION_RECEIVED -> SEARCHING -> PRODUCT_FOUND -> ADDING_TO_CART -> SUCCESS
```

and clear failure states:

```text
FAILED_SEARCH_BOX_NOT_FOUND
FAILED_PRODUCT_NOT_FOUND
FAILED_ADD_BUTTON_NOT_FOUND
FAILED_CART_NOT_CONFIRMED
```

Once the app reports these states reliably, Codex can run tests, inspect logs, and iterate much faster.
