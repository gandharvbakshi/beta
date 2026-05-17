package com.example.beta

import android.content.Context
import android.os.Build
import org.json.JSONObject

object FeedbackPayload {
    fun build(
        context: Context,
        rating: String,
        category: String,
        message: String,
        includeLogs: Boolean,
        logs: String = "",
        instruction: String = "",
        result: String = ""
    ): JSONObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val consent = JSONObject()
            .put("include_logs", includeLogs)
            .put("include_screenshot", false)
            .put("contact_allowed", false)
        val app = JSONObject()
            .put("package_name", context.packageName)
            .put("version_name", packageInfo.versionName ?: "")
            .put("version_code", packageInfo.longVersionCode.toString())
            .put("build_type", BuildConfig.BUILD_TYPE)
        val device = JSONObject()
            .put("manufacturer", Build.MANUFACTURER ?: "")
            .put("model", Build.MODEL ?: "")
            .put("android_version", Build.VERSION.RELEASE ?: "")
            .put("sdk_int", Build.VERSION.SDK_INT.toString())
        val order = JSONObject()
            .put("instruction", instruction)
            .put("result", result)
        val diagnostics = JSONObject()
        if (includeLogs) {
            diagnostics.put("logs", logs.take(20_000))
        }
        return JSONObject()
            .put("source", "android")
            .put("rating", rating.take(40))
            .put("category", category.take(80))
            .put("message", message.take(4000))
            .put("consent", consent)
            .put("app", app)
            .put("device", device)
            .put("order", order)
            .put("diagnostics", diagnostics)
    }
}
