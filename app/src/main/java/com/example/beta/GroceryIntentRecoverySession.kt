package com.example.beta

/** Pure, non-actionable editing state for recovering an original grocery instruction. */
internal class GroceryIntentRecoverySession(
    originalInstruction: String,
    private val isCurrent: () -> Boolean,
    private val persist: (expectedSaved: String, newText: String) -> Boolean,
    private val blankMessage: String,
    initialText: String = originalInstruction,
    initiallySavedText: String = originalInstruction,
) {
    enum class Action { RECHECK, VOICE, EXIT }

    sealed interface Outcome {
        data object None : Outcome
        data class Recheck(val text: String) : Outcome
        data class Voice(val text: String) : Outcome
        data class Exit(val withoutSaving: Boolean) : Outcome
    }

    private val original = originalInstruction
    private var active = true
    private var isPersisting = false
    private var consecutiveExitSaveFailures = 0
    private var leaveWithoutSavingOffered = false

    var text: String = initialText
        private set

    var lastSavedText: String = initiallySavedText
        private set

    var message: String? = null
        private set

    val canLeaveWithoutSaving: Boolean
        get() = active && isCurrent() && leaveWithoutSavingOffered

    /** Keep exact user text in memory. Persistence happens on actions/lifecycle, not keystrokes. */
    fun edit(newText: String): Boolean {
        if (!isActive() || isPersisting) return false
        text = newText
        clearExitOffer()
        message = null
        return true
    }

    /** Lifecycle persistence is best-effort and never produces a user action. */
    fun saveDraft(): Boolean {
        if (!isActive() || isPersisting) return false
        val saved = persistCurrentText()
        if (!isActive()) return false
        if (!saved) message = FAILED_SAVE
        return saved
    }

    fun attempt(action: Action): Outcome {
        if (!isActive() || isPersisting) return Outcome.None
        if (action != Action.EXIT) clearExitOffer()
        message = null
        if (!persistCurrentText()) {
            if (!isActive()) return Outcome.None
            if (action == Action.EXIT) {
                consecutiveExitSaveFailures += 1
                if (consecutiveExitSaveFailures >= EXIT_FAILURES_BEFORE_DISCARD) {
                    leaveWithoutSavingOffered = true
                    message = UNSAVED_EXIT_WARNING
                } else {
                    message = FAILED_SAVE
                }
            } else {
                message = FAILED_SAVE
            }
            return Outcome.None
        }

        clearExitOffer()
        return when (action) {
            Action.RECHECK -> when {
                text.isBlank() -> {
                    message = blankMessage
                    Outcome.None
                }
                original.trim() == text.trim() -> {
                    message = UNCHANGED
                    Outcome.None
                }
                else -> {
                    active = false
                    Outcome.Recheck(text)
                }
            }
            Action.VOICE -> {
                active = false
                Outcome.Voice(text)
            }
            Action.EXIT -> {
                active = false
                Outcome.Exit(withoutSaving = false)
            }
        }
    }

    fun leaveWithoutSaving(): Outcome {
        if (!isActive() || isPersisting || !leaveWithoutSavingOffered) return Outcome.None
        active = false
        message = null
        clearExitOffer()
        return Outcome.Exit(withoutSaving = true)
    }

    /** Invalidates a screen when its owning operation is cancelled or replaced. */
    fun invalidate() {
        active = false
        clearExitOffer()
    }

    private fun isActive(): Boolean = active && isCurrent()

    private fun persistCurrentText(): Boolean {
        if (!isActive() || isPersisting) return false
        val expectedSaved = lastSavedText
        val textBeingSaved = text
        isPersisting = true
        val saved = try {
            runCatching { persist(expectedSaved, textBeingSaved) }.getOrDefault(false)
        } finally {
            isPersisting = false
        }
        // The callback may synchronously invalidate this session or trigger a
        // nested operation. Never advance CAS state or return success afterward.
        if (!saved || !isActive() || text != textBeingSaved || lastSavedText != expectedSaved) return false
        lastSavedText = textBeingSaved
        consecutiveExitSaveFailures = 0
        leaveWithoutSavingOffered = false
        message = null
        return true
    }

    private fun clearExitOffer() {
        consecutiveExitSaveFailures = 0
        leaveWithoutSavingOffered = false
    }

    companion object {
        const val FAILED_SAVE = "Beta couldn't save your list on this phone. Your words are still in the box. Tap the button again to try once more."
        const val UNCHANGED = "Your list hasn't changed yet. Change the words in the box, then check again."
        const val UNSAVED_EXIT_WARNING = "Your list still couldn't be saved. If you leave now, these words may be lost. Tap Leave without saving to go anyway, or stay and try again."
        private const val EXIT_FAILURES_BEFORE_DISCARD = 2
    }
}
