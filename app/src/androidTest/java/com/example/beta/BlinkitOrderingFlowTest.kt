package com.example.beta

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlinkitOrderingFlowTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context

    private val betaPackage = "com.example.beta"
    private val blinkitPackage = "com.grofers.customerapp"
    private val accessibilityService = "com.example.beta/.MyAccessibilityService"
    private val targetInstruction = "order butter"

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        device.pressHome()
        runShell("logcat -c")
        enableAccessibilityService()
        allowOverlay()
    }

    @After
    fun tearDown() {
        device.pressHome()
    }

    @Test
    fun orderButterStopsAtCartVerification() {
        assertTrue("Blinkit package is not installed: $blinkitPackage", isPackageInstalled(blinkitPackage))
        assertTrue("Beta AccessibilityService is not enabled", isAccessibilityServiceEnabled())

        launchApp(betaPackage)
        assertTrue("Beta AccessibilityService did not bind", waitForAccessibilityServiceBound())
        startScreenCaptureIfNeeded()
        launchApp(blinkitPackage)
        submitInstructionFromOverlay(targetInstruction)

        assertTrue(
            "Expected butter add-to-cart success signal from beta logs/status or Blinkit cart UI",
            waitForSafeCartSignal(120_000)
        )
    }

    private fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            runShell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        }
        assertTrue(
            "Package did not come to foreground: $packageName",
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 15_000)
        )
    }

    private fun startScreenCaptureIfNeeded() {
        val button = waitAny(7_000, "Start Capture", "Start screen capture", "button_start_screen_capture")
            ?: return
        button.click()

        acceptScreenCapturePrompt()

        waitAny(12_000, "Tap to tell Beta")
    }

    private fun acceptScreenCapturePrompt() {
        val prompt = waitAny(10_000, "Start recording or casting", "Start recording", "Start now")
            ?: error("Screen capture permission prompt did not appear")
        val startNow = device.findObject(By.text("Start now"))
            ?: device.findObject(By.textContains("Start now"))
            ?: prompt
        startNow.click()
    }

    private fun submitInstructionFromOverlay(instruction: String) {
        val overlay = waitAny(15_000, "Tap to tell Beta")
            ?: error("Instruction overlay was not visible after screen capture setup")
        overlay.click()

        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
            ?: error("Instruction input was not visible")
        input.text = instruction

        val submit = waitAny(8_000, "Send to Beta")
            ?: error("Send to Beta button was not visible")
        submit.click()
    }

    private fun waitForSafeCartSignal(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (hasAnyVisibleText("STATE: SUCCESS", "FLOW_SUCCESS", "CART_INCREMENT_CONFIRMED")) {
                return true
            }
            if (hasAnyVisibleText("View cart", "View Cart", "Cart", "added to cart")) {
                return true
            }
            if (logcatContainsSuccess()) {
                return true
            }
            if (hasAnyVisibleText("STATE: FAILED", "FLOW_FAILED")) {
                return false
            }
            Thread.sleep(1_000)
        }
        return false
    }

    private fun enableAccessibilityService() {
        val current = runShell("settings get secure enabled_accessibility_services").trim()
        if (current.contains(accessibilityService)) {
            return
        }
        val next = when {
            current == "null" || current.isBlank() -> accessibilityService
            else -> "$current:$accessibilityService"
        }
        runShell("settings put secure enabled_accessibility_services $next")
        runShell("settings put secure accessibility_enabled 1")
    }

    private fun waitForAccessibilityServiceBound(timeoutMs: Long = 10_000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val dump = runShell("dumpsys accessibility")
            if (!dump.contains("Bound services:{}") &&
                !dump.contains("Bound services: {}") &&
                dump.contains("com.example.beta/com.example.beta.MyAccessibilityService") &&
                !dump.contains("Crashed services:{{com.example.beta/com.example.beta.MyAccessibilityService}}")
            ) {
                return true
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun allowOverlay() {
        runShell("appops set $betaPackage SYSTEM_ALERT_WINDOW allow")
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return runShell("settings get secure enabled_accessibility_services")
            .contains(accessibilityService)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runShell("pm list packages $packageName").contains(packageName)
    }

    private fun logcatContainsSuccess(): Boolean {
        val logs = runShell("logcat -d -s BetaAgent:I BetaAgent:E")
        return logs.contains("FLOW_SUCCESS") ||
            logs.contains("BLINKIT_CART_INCREMENT_CONFIRMED")
    }

    private fun waitAny(timeoutMs: Long, vararg textParts: String): UiObject2? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            for (text in textParts) {
                device.findObject(By.textContains(text))?.let { return it }
                device.findObject(By.descContains(text))?.let { return it }
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun hasAnyVisibleText(vararg textParts: String): Boolean {
        return textParts.any { text ->
            device.findObject(By.textContains(text)) != null ||
                device.findObject(By.descContains(text)) != null
        }
    }

    private fun runShell(command: String): String {
        return device.executeShellCommand(command) ?: ""
    }
}
