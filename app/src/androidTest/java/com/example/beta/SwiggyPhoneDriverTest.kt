package com.example.beta

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class SwiggyPhoneDriverTest {
    @Test fun drive() {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString(ACTION).orEmpty()
        assumeTrue(action.isNotBlank())
        val d = Driver(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()), InstrumentationRegistry.getInstrumentation().targetContext, args)
        d.run(action)
    }

    private class Driver(private val device: UiDevice, private val context: android.content.Context, private val args: android.os.Bundle) {
        fun run(action: String) = when (action) {
            "launch" -> launch()
            "snapshot" -> snapshot()
            "setText" -> setText()
            "tapId" -> tapId()
            "scrollDown" -> scroll(Direction.DOWN)
            "scrollUp" -> scroll(Direction.UP)
            else -> error("Unsupported betaPhoneAction=$action")
        }

        private fun launch(): Any { context.packageManager.getLaunchIntentForPackage(PKG)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: error("No launch intent"); device.wait(Until.hasObject(By.pkg(PKG)), WAIT_MS); return snapshot() }
        private fun snapshot(): Any { ensureForeground(); val raw = File(dir(), "raw-hierarchy.xml"); device.dumpWindowHierarchy(raw); val json = File(dir(), "snapshot.json"); json.writeText(toJson(parse(raw)).toString(2)); Log.i(TAG, "SWIGGY_PHONE_SNAPSHOT raw=${raw.name} json=${json.name}"); return json.absolutePath }
        private fun setText(): Any {
            ensureForeground()
            val id = args.getString(TEXT_ID).orEmpty().ifBlank { "orderCommandInput" }
            require(id == "orderCommandInput")
            val value = fixtureText(args.getString(FIXTURE_ID), args.getString(VALUE))
            require(value.isNotBlank())
            val node = unique(By.res(PKG, id))
            require(node.className == "android.widget.EditText")
            val b = node.visibleBounds
            node.setText(value)
            dismissIme()
            Log.i(TAG, "SWIGGY_PHONE_SET_TEXT id=$id bounds=$b")
            return snapshot()
        }
        private fun tapId(): Any {
            ensureForeground()
            val id = args.getString(ID).orEmpty()
            require(id in ALLOWED_IDS)
            val node = unique(By.res(PKG, id))
            val label = node.text.orEmpty().ifBlank { node.contentDescription.orEmpty() }
            require(!blocked(label))
            val b = node.visibleBounds
            require(node.isClickable)
            if (needsMutationApproval(label)) require(allowMutation() && allowedMutationLabel(label))
            Log.i(TAG, "SWIGGY_PHONE_TAP_ID id=$id bounds=$b")
            node.click()
            return snapshot()
        }
        private fun scroll(direction: Direction): Any { ensureForeground(); val node = unique(By.scrollable(true)); val b = node.visibleBounds; node.scroll(direction, .85f); Log.i(TAG, "SWIGGY_PHONE_SCROLL direction=$direction bounds=$b"); return snapshot() }

        private fun ensureForeground() { require(device.currentPackageName == PKG) { "Foreground package must be $PKG" } }
        private fun unique(sel: androidx.test.uiautomator.BySelector): UiObject2 { device.wait(Until.hasObject(sel), WAIT_MS); return device.findObjects(sel).filter { it.applicationPackage == PKG && it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }.single() }
        private fun dismissIme() { if (IME_PACKAGES.any { device.hasObject(By.pkg(it)) }) device.pressBack() }
        private fun fixtureText(id: String?, value: String?): String = value?.takeIf { it.isNotBlank() } ?: FIXTURES[id]?.joinToString(", ") ?: ""
        private fun blocked(label: String) = BLOCKED.any { label.contains(it, true) }
        private fun needsMutationApproval(label: String) = label.startsWith("Add ", true) || label.startsWith("Apply ", true)
        private fun allowMutation() = args.getString(ALLOW_MUTATION).equals("true", true)
        private fun allowedMutationLabel(label: String) = label.matches(Regex("^Add \\d+ lines to cart$")) || label.matches(Regex("^Apply \\d+ cart changes$"))
        private fun dir() = File(context.filesDir, "beta-phone-driver").apply { mkdirs() }

        private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        private fun toJson(root: Element): JSONObject {
            fun walk(e: Element, sensitive: Boolean = false): JSONObject? {
                val res = e.getAttribute("resource-id").substringAfter('/')
                val txt = e.getAttribute("text")
                val cd = e.getAttribute("content-desc")
                val cls = e.getAttribute("class")
                val sens = sensitive || listOf(res, cls, txt, cd).joinToString(" ").lowercase(Locale.US).anyIn(SENSITIVE)
                val kids = JSONArray()
                val c = e.childNodes
                for (i in 0 until c.length) (c.item(i) as? Element)?.let { walk(it, sens)?.let(kids::put) }
                if (e.getAttribute("package") != PKG && kids.length() == 0) return null
                return JSONObject()
                    .put("resourceId", res)
                    .put("bounds", e.getAttribute("bounds"))
                    .put("visible", e.getAttribute("visible-to-user").isBlank() || e.getAttribute("visible-to-user").equals("true", true))
                    .put("clickable", e.getAttribute("clickable").equals("true", true))
                    .put("scrollable", e.getAttribute("scrollable").equals("true", true))
                    .put("text", if (sens) "[REDACTED]" else "")
                    .put("contentDesc", if (sens) "[REDACTED]" else "")
                    .put("children", kids)
            }
            return walk(root) ?: JSONObject().put("children", JSONArray())
        }
    }

    private companion object {
        const val TAG = "BetaAgent"
        const val PKG = "live.betaapp.android"
        const val ACTION = "betaPhoneAction"
        const val VALUE = "betaPhoneValue"
        const val FIXTURE_ID = "betaFixtureId"
        const val TEXT_ID = "betaPhoneTextId"
        const val ID = "betaPhoneId"
        const val ALLOW_MUTATION = "betaAllowCartMutation"
        const val WAIT_MS = 5_000L
        val FIXTURES = mapOf("5regular" to listOf("milk", "eggs", "bread", "bananas", "apples"),
            "7unusual" to listOf("tahini", "edamame", "agar agar", "chia seeds", "rock salt", "parchment paper", "coconut water"),
            "10noisy" to listOf("amul buttr", "butter", "mozrella", "keen waa", "mozz a rela", "CR2032 battery", "zip ties", "frozen peas", "paper towels", "coconut water"))
        val ALLOWED_IDS = setOf("orderCommandInput", "orderVoiceInputButton", "orderSubmitButton", "swiggyConnectionAction", "swiggyChangeAddressAction", "swiggyStepClose", "swiggyStepPrimary", "swiggyStepSecondary", "swiggyStepTertiary")
        val SENSITIVE = listOf("address", "message", "caption", "token", "otp", "auth", "code")
        val BLOCKED = listOf("checkout", "pay", "place order", "order placement", "delete cart", "deletecart", "disconnect", "package change", "packagechanges")
        val IME_PACKAGES = listOf("com.google.android.inputmethod.latin", "com.android.inputmethod.latin", "com.samsung.android.honeyboard", "com.touchtype.swiftkey", "com.sohu.inputmethod.sogou")
        fun String.anyIn(words: List<String>) = words.any { contains(it, true) }
    }
}
