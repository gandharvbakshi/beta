package com.example.beta

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

internal data class SwiggyRememberedAddressSelection(
    val address: SwiggyMcpClient.SwiggyAddress,
    val source: String = "remembered_saved_address",
)

internal fun resolveRememberedSwiggyAddress(
    context: Context,
    addresses: List<SwiggyMcpClient.SwiggyAddress>,
): SwiggyRememberedAddressSelection {
    val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val latestUsageKey = latestUsageKey(prefs)
        ?: throw IllegalStateException("No remembered Swiggy address selection exists yet.")
    val rememberedAddress = addresses.firstOrNull { usageKey(it.id) == latestUsageKey }
        ?: throw IllegalStateException("The latest remembered Swiggy address is no longer present in the fresh saved-address list.")

    return SwiggyRememberedAddressSelection(address = rememberedAddress)
}

internal fun assertRememberedAddressMatchesCurrentCart(
    currentCartAddressId: String?,
    rememberedAddressId: String,
) {
    if (currentCartAddressId.isNullOrBlank()) return
    check(currentCartAddressId == rememberedAddressId) {
        "The current cart address does not match the remembered address selection."
    }
}

private fun usageKey(addressId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(addressId.toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
}

private fun latestUsageKey(prefs: SharedPreferences): String? {
    return prefs.all.asSequence()
        .mapNotNull { (key, value) ->
            if (!key.endsWith("_time")) return@mapNotNull null
            val timestamp = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            } ?: return@mapNotNull null
            if (timestamp > 0L) key.removeSuffix("_time") to timestamp else null
        }
        .maxByOrNull { it.second }
        ?.first
}

private const val PREFERENCES_NAME = "swiggy_address_intelligence"
