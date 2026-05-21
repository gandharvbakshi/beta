package com.example.beta

import android.content.Context
import android.content.Intent
import android.util.Log

object CommerceAppLauncher {
    const val EXTRA_LAUNCH_PREFERRED_COMMERCE_APP = "launch_preferred_commerce_app"
    const val LAUNCH_SETTLE_DELAY_MS = 5000L

    private const val TAG = "CommerceAppLauncher"
    private const val BLINKIT_PACKAGE = "com.grofers.customerapp"
    private const val SWIGGY_INSTAMART_PACKAGE = "in.swiggy.android.instamart"

    private val candidates = listOf(
        CommerceApp("Blinkit", BLINKIT_PACKAGE),
        CommerceApp("Swiggy Instamart", SWIGGY_INSTAMART_PACKAGE),
    )

    data class LaunchResult(
        val launched: Boolean,
        val appName: String? = null,
        val packageName: String? = null,
        val message: String,
    )

    private data class CommerceApp(
        val name: String,
        val packageName: String,
    )

    fun launchPreferred(context: Context): LaunchResult {
        val packageManager = context.packageManager
        for (candidate in candidates) {
            val launchIntent = packageManager.getLaunchIntentForPackage(candidate.packageName)
                ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return try {
                context.startActivity(launchIntent)
                Log.i(TAG, "Launched ${candidate.name} (${candidate.packageName})")
                LaunchResult(
                    launched = true,
                    appName = candidate.name,
                    packageName = candidate.packageName,
                    message = context.getString(R.string.commerce_app_opening, candidate.name),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch ${candidate.name}: ${e.message}", e)
                LaunchResult(
                    launched = false,
                    appName = candidate.name,
                    packageName = candidate.packageName,
                    message = context.getString(R.string.commerce_app_launch_failed, candidate.name),
                )
            }
        }

        Log.w(TAG, "No supported grocery app is installed")
        return LaunchResult(
            launched = false,
            message = context.getString(R.string.commerce_app_not_installed),
        )
    }
}
