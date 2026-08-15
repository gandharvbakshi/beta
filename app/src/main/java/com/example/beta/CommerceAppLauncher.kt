package com.example.beta

import android.content.Context
import android.content.Intent
import android.util.Log

object CommerceAppLauncher {
    const val EXTRA_LAUNCH_PREFERRED_COMMERCE_APP = "launch_preferred_commerce_app"
    const val LAUNCH_SETTLE_DELAY_MS = 5000L

    private const val TAG = "CommerceAppLauncher"

    data class LaunchResult(
        val launched: Boolean,
        val selectedProvider: CommerceProviderRouter.CommerceProvider? = null,
        val appName: String? = null,
        val packageName: String? = null,
        val message: String,
        val fallbackUsed: Boolean = false,
        val preferenceSource: CommerceProviderRouter.PreferenceSource? = null,
    )

    @JvmStatic
    @JvmOverloads
    fun launchPreferred(context: Context, instruction: String? = null): LaunchResult {
        CommerceProviderRouter.unsupportedProviderName(instruction)?.let { providerName ->
            return LaunchResult(
                launched = false,
                message = context.getString(R.string.provider_not_supported, providerName),
            )
        }
        val installedApps = discoverInstalledApps(context)
        val decision = CommerceProviderRouter.routeLaunch(instruction, installedApps)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(decision.packageName)

        if (launchIntent == null) {
            Log.w(TAG, "No launch intent for ${decision.appName} (${decision.packageName})")
            return LaunchResult(
                launched = false,
                selectedProvider = decision.selectedProvider,
                appName = decision.appName,
                packageName = decision.packageName,
                message = decision.message,
                fallbackUsed = decision.fallbackUsed,
                preferenceSource = decision.preferenceSource,
            )
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(launchIntent)
            Log.i(TAG, "Launched ${decision.appName} (${decision.packageName})")
            LaunchResult(
                launched = true,
                selectedProvider = decision.selectedProvider,
                appName = decision.appName,
                packageName = decision.packageName,
                message = decision.message,
                fallbackUsed = decision.fallbackUsed,
                preferenceSource = decision.preferenceSource,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch ${decision.appName}: ${e.message}", e)
            LaunchResult(
                launched = false,
                selectedProvider = decision.selectedProvider,
                appName = decision.appName,
                packageName = decision.packageName,
                message = context.getString(R.string.commerce_app_launch_failed, decision.appName),
                fallbackUsed = decision.fallbackUsed,
                preferenceSource = decision.preferenceSource,
            )
        }
    }

    private fun discoverInstalledApps(context: Context): Set<CommerceProviderRouter.InstalledCommerceApp> {
        val packageManager = context.packageManager
        return CommerceProviderRouter.supportedProviders().mapNotNullTo(mutableSetOf()) { provider ->
            provider.packageAliases.firstOrNull { packageName ->
                packageManager.getLaunchIntentForPackage(packageName) != null
            }?.let { packageName ->
                CommerceProviderRouter.InstalledCommerceApp(provider = provider, packageName = packageName)
            }
        }
    }
}
