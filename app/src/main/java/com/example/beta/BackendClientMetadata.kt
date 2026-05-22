package com.example.beta

import android.content.Context
import android.os.Build
import org.json.JSONArray

object BackendClientMetadata {
    const val FRONTEND_CONTRACT_VERSION = "android-v2"

    private val capabilities = listOf(
        "multipart_screenshot_v1",
        "accessibility_tree_v1",
        "session_id_v1",
        "action_history_json_v1",
        "last_action_result_json_v1",
        "multi_item_order_v1",
        "commerce_app_profiles_v1"
    )

    data class Snapshot(
        val platform: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val buildType: String,
        val frontendContractVersion: String,
        val capabilitiesJson: String
    )

    fun snapshot(context: Context): Snapshot {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return Snapshot(
            platform = "android",
            packageName = context.packageName,
            versionName = packageInfo.versionName ?: BuildConfig.VERSION_NAME,
            versionCode = versionCode,
            buildType = BuildConfig.BUILD_TYPE,
            frontendContractVersion = FRONTEND_CONTRACT_VERSION,
            capabilitiesJson = JSONArray(capabilities).toString()
        )
    }

    fun formFields(context: Context): Map<String, String> = formFields(snapshot(context))

    fun formFields(snapshot: Snapshot): Map<String, String> {
        return linkedMapOf(
            "client_platform" to snapshot.platform,
            "client_package_name" to snapshot.packageName,
            "client_version_name" to snapshot.versionName,
            "client_version_code" to snapshot.versionCode.toString(),
            "client_build_type" to snapshot.buildType,
            "frontend_contract_version" to snapshot.frontendContractVersion,
            "client_capabilities_json" to snapshot.capabilitiesJson,
            "versioned_client_version" to snapshot.versionName
        )
    }
}
