package com.example.beta

import android.content.Context

/** Durable, encrypted record of user-visible fulfillment constraints. */
internal open class GroceryIntentConstraintStore(
    context: Context,
    fileName: String = FILE_NAME,
) {
    private val drafts = SwiggyDraftStore(
        context = context,
        fileName = fileName,
        expireAfterMillis = null,
    )

    sealed interface State {
        data object Empty : State
        data class Ready(val quotes: List<String>) : State
        data object Unreadable : State
    }

    fun read(): State = drafts.withStoreLock { readLocked() }

    private fun readLocked(): State {
        val text = drafts.loadIncludingEmpty() ?: return if (drafts.hasStoredRecord()) State.Unreadable else State.Empty
        if (text.isEmpty()) return State.Empty
        return GroceryIntentConstraintCodec.decode(text)?.let { quotes ->
            if (quotes.isEmpty()) State.Empty else State.Ready(quotes)
        } ?: State.Unreadable
    }

    /** Merges new quotes and verifies the encrypted write before returning success. */
    fun appendBeforeApply(quotes: List<String>): Boolean = drafts.withStoreLock {
        val merged = mergedQuotesForAppendLocked(quotes) ?: return@withStoreLock false
        if (quotes.isEmpty()) true else save(merged)
    }

    /** Read-only preflight; appendBeforeApply still rechecks under the lock before writing. */
    fun canAppend(quotes: List<String>): Boolean = drafts.withStoreLock {
        val merged = mergedQuotesForAppendLocked(quotes) ?: return@withStoreLock false
        GroceryIntentConstraintCodec.encode(merged) != null
    }

    /** Returns the exact normalized payload append would encode, or null when it cannot append. */
    private fun mergedQuotesForAppendLocked(quotes: List<String>): List<String>? {
        if (quotes.any { !GroceryIntentConstraintCodec.isValidQuote(it) }) return null
        return when (val current = readLocked()) {
            State.Unreadable -> null
            State.Empty -> quotes.distinct()
            is State.Ready -> (current.quotes + quotes).distinct()
        }
    }

    /** True means safe settlement: unseen/changed notes are retained, never erased. */
    open fun settlePlacedAfterReview(expectedQuotes: List<String>): Boolean = drafts.withStoreLock {
        val expected = if (expectedQuotes.isEmpty()) State.Empty else State.Ready(expectedQuotes)
        if (readLocked() != expected) return@withStoreLock true
        save(emptyList()) && readLocked() == State.Empty
    }

    /** Explicitly discards a currently unreadable record after caller-confirmed review. */
    fun discardUnreadableAfterConfirmation(): Boolean = drafts.withStoreLock {
        if (readLocked() != State.Unreadable) return@withStoreLock false
        save(emptyList()) && readLocked() == State.Empty
    }

    private fun save(quotes: List<String>): Boolean {
        val encoded = GroceryIntentConstraintCodec.encode(quotes) ?: return false
        val expected = if (quotes.isEmpty()) State.Empty else State.Ready(quotes.toList())
        return drafts.save(encoded) && readLocked() == expected
    }

    private companion object {
        const val FILE_NAME = "grocery-intent-constraints-v1.bin"
    }
}

/** Strict, Android-independent version-1 payload codec and limits. */
internal object GroceryIntentConstraintCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_QUOTES = 20
    private const val MAX_QUOTE_CHARS = 180
    private const val MAX_PAYLOAD_CHARS = 6000

    fun encode(quotes: List<String>): String? {
        if (quotes.size > MAX_QUOTES || quotes.distinct().size != quotes.size || quotes.any { !isValidQuote(it) }) {
            return null
        }
        return buildString {
            append("{\"version\":1,\"quotes\":[")
            quotes.forEachIndexed { index, quote ->
                if (index > 0) append(',')
                append('"')
                quote.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        else -> append(char)
                    }
                }
                append('"')
            }
            append("]}")
        }.takeIf { it.length <= MAX_PAYLOAD_CHARS }
    }

    fun decode(text: String): List<String>? {
        if (text.length > MAX_PAYLOAD_CHARS) return null
        val root = runCatching { JsonParser(text).parseStrict() as? JsonValue.Obj }.getOrNull() ?: return null
        if (root.members.keys != setOf("version", "quotes")) return null
        if (root.members["version"] != JsonValue.Num(SCHEMA_VERSION.toString())) return null
        val array = root.members["quotes"] as? JsonValue.Arr ?: return null
        if (array.items.size > MAX_QUOTES) return null
        val quotes = mutableListOf<String>()
        for (entry in array.items) {
            val quote = (entry as? JsonValue.Str)?.value ?: return null
            if (!isValidQuote(quote)) return null
            quotes += quote
        }
        if (quotes.distinct().size != quotes.size) return null
        return quotes.toList()
    }

    fun isValidQuote(quote: String): Boolean =
        quote.isNotBlank() && quote == quote.trim() && quote.length <= MAX_QUOTE_CHARS &&
            quote.none { it.isISOControl() }
}
