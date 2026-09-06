package com.example.beta

import android.content.Context

/**
 * Encrypted, no-backup storage for the reviewed cart confirmation token.
 *
 * This is intentionally separate from the durable pending-cart marker so the
 * review token can be stored, cleared, and corrupted independently.
 */
internal class SwiggyCartReviewStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) {
    private val draftStore = SwiggyDraftStore(context, fileName)

    fun save(token: String): Boolean {
        if (!isValidToken(token)) return false
        return draftStore.save(token)
    }

    fun load(): String? {
        return draftStore.load()?.takeIf { isValidToken(it) }
    }

    fun clear(): Boolean {
        return draftStore.clear()
    }

    private fun isValidToken(token: String): Boolean {
        return token.isNotBlank() && token.length <= MAX_TOKEN_CHARS
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "swiggy-cart-review-v1.bin"
        const val MAX_TOKEN_CHARS = 4096
    }
}
