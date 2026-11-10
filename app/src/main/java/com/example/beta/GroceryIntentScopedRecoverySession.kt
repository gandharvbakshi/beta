package com.example.beta

/** An uncommitted phrase buffer. Only save() can advance the user's request revision. */
internal class GroceryIntentScopedRecoverySession(
    initial: GroceryIntentRecoveryRecord,
    val quote: GroceryIntentReviewV2Quote,
    private val isCurrent: () -> Boolean,
    private val persist: (GroceryIntentRecoveryRecord, GroceryIntentRecoveryRecord) -> Boolean,
    private val commit: (GroceryIntentRecoveryRecord, GroceryIntentRecoveryRecord) -> Boolean,
    val kind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE,
) {
    var record = initial
        private set
    var text = initial.dirty?.takeIf { it.quote == quote && it.kind == kind }?.text ?: quote.text
        private set
    var message: String? = null
        private set
    private var active = true
    private var writing = false
    private var voiceConfirmation: String? = null
    var generation: Long = 0
        private set

    init {
        require(initial.dirty == null || (initial.dirty.quote == quote && initial.dirty.kind == kind)) { "Another edit is unfinished" }
        require(initial.withDirty(quote, text, kind) != null) { "Invalid recovery source" }
    }

    val hasChanges get() = text != quote.text
    val canSave get() = hasChanges && text.isNotBlank()

    fun edit(value: String): Boolean {
        if (!current() || writing) return false
        text = value
        generation++
        voiceConfirmation = null
        message = null
        return true
    }

    /** Pause/Back stores only the buffer, never the replacement instruction. */
    fun checkpoint(): Boolean {
        if (!current() || writing) return false
        val updated = if (hasChanges) record.withDirty(quote, text, kind) else record.withoutDirty()
        return write(updated, persist, finish = false)
    }

    fun save(confirmLargeRemoval: Boolean = false): GroceryIntentRecoveryRecord? {
        if (!current() || writing) return null
        if (!canSave) {
            message = if (text.isBlank()) "Type or say something." else "Change the words first."
            return null
        }
        if (requiresRemovalConfirmation && !confirmLargeRemoval) {
            message = "This removes most of your message. Save anyway?"
            return null
        }
        // First make the exact buffer durable. Repeating either phase after an
        // ambiguous read-back retries the same record, not a different revision.
        if (record.dirty?.quote != quote || record.dirty?.text != text || record.dirty?.kind != kind) {
            if (!checkpoint()) return null
        }
        val updated = record.commitDirty()
        return updated?.takeIf { write(it, commit, finish = true) }.also {
            if (it == null && current()) message = FAILED_SAVE
        }
    }

    fun cancel(): Boolean = current() && !writing && write(record.withoutDirty(), persist, finish = true)

    /** Exact standalone commands only; dictation never implicitly saves or submits. */
    fun voice(value: String, cursorUtf16: Int = text.length): VoiceAction {
        if (!current() || writing) return VoiceAction.NONE
        val command = value.trim().trimEnd { it in ".!?,।" }.trim().lowercase(java.util.Locale.ROOT)
        if (kind == GroceryIntentEditKind.WHOLE_MESSAGE) {
            if (command == "read my message") {
                voiceConfirmation = null
                message = text
                return VoiceAction.READ
            }
            val lineCommand = Regex("read line\\s+(\\d+)").matchEntire(command)
            if (lineCommand != null) {
                val line = lineCommand.groupValues[1].toIntOrNull()
                voiceConfirmation = null
                message = line?.takeIf { it > 0 }?.let { text.split('\n').getOrNull(it - 1) }
                    ?: "That line is not in your message."
                return VoiceAction.READ
            }
        }
        return when (command) {
            "cancel" -> VoiceAction.CANCEL
            "save", "save change" -> {
                if (!canSave) {
                    message = if (text.isBlank()) "Type or say something." else "Change the words first."
                    VoiceAction.NONE
                } else if (voiceConfirmation == text) {
                    voiceConfirmation = null
                    VoiceAction.SAVE
                } else {
                    voiceConfirmation = text
                    message = confirmationMessage()
                    VoiceAction.CONFIRM
                }
            }
            else -> {
                if (kind == GroceryIntentEditKind.WHOLE_MESSAGE) {
                    val cursor = cursorUtf16
                    val source = runCatching { GroceryIntentRequestRevision.begin(text) }.getOrNull()
                    if (value.isBlank() || source == null || !source.isSourceBoundary(cursor)) return VoiceAction.NONE
                    edit(text.substring(0, cursor) + value + text.substring(cursor))
                    return VoiceAction.NONE
                }
                edit(value)
                voiceConfirmation = text
                message = "Save: '$text'? Say save or cancel."
                VoiceAction.CONFIRM
            }
        }
    }

    private fun confirmationMessage() = if (kind == GroceryIntentEditKind.WHOLE_MESSAGE)
        "Save your changed message, ${text.split('\n').size} lines? Say save or cancel."
        else "Save: '$text'? Say save or cancel."

    /** Multiset overlap counts actual absent original words, even if new words replace them. */
    val requiresRemovalConfirmation: Boolean get() {
        if (kind != GroceryIntentEditKind.WHOLE_MESSAGE) return false
        fun words(value: String) = groceryIdentityWords(value.lowercase(java.util.Locale.ROOT)).toList()
        val original = words(quote.text)
        val remaining = words(text).groupingBy { it }.eachCount().toMutableMap()
        val removed = original.count { word ->
            val count = remaining[word] ?: 0
            if (count > 0) { remaining[word] = count - 1; false } else true
        }
        return removed > original.size / 2
    }

    fun invalidate() { active = false; voiceConfirmation = null; generation++ }

    private fun current() = active && isCurrent()

    private fun write(
        updated: GroceryIntentRecoveryRecord?,
        action: (GroceryIntentRecoveryRecord, GroceryIntentRecoveryRecord) -> Boolean,
        finish: Boolean,
    ): Boolean {
        if (updated == null) { message = FAILED_SAVE; return false }
        val expected = record
        val expectedText = text
        writing = true
        val saved = try { runCatching { action(expected, updated) }.getOrDefault(false) }
        finally { writing = false }
        if (!current() || record !== expected || text != expectedText) return false
        if (!saved) { message = FAILED_SAVE; return false }
        record = updated
        message = null
        if (finish) active = false
        return true
    }

    enum class VoiceAction { NONE, CONFIRM, SAVE, CANCEL, READ }
    companion object { const val FAILED_SAVE = "Couldn't save. Your change is still here." }
}
