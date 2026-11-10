package com.example.beta

/** One explicit choice edits the saved text, then rechecks the ENTIRE list. No cart action exists here. */
internal class GroceryIntentInputRepairReview(
    private val repair: GroceryIntentInputRepair,
    private val isCurrent: () -> Boolean,
    persist: (String, String) -> Boolean,
    private val recheck: (String) -> Unit,
    private val failed: () -> Unit,
    private val edit: () -> Unit,
    private val cancel: () -> Unit,
    private val onStatusChanged: (String?, Boolean) -> Unit = { _, _ -> },
    private val onUnsavedExit: () -> Unit = cancel,
) {
    private val persistDraft = persist
    private var session = newSession()
    private var statusOverride: String? = null
    private var consumed = false
    private var busy = false

    val message: String?
        get() = statusOverride ?: session.message

    val canLeaveWithoutSaving: Boolean
        get() = session.canLeaveWithoutSaving

    val question = "You said \u201c${repair.source.text.trimEnd(' ', '\t', '.', ',', '!', '?')}\u201d. Which item should be ${repair.newAmount.text}?"

    fun screen() = SwiggyStepScreen(
        eyebrow = "One quick check",
        title = "Which item did you mean?",
        message = question,
        caption = "Choose an item, or edit your list below.",
        choices = repair.options.map { option ->
            SwiggyStepChoice(
                title = option.target.text,
                detail = "Requested: ${option.oldAmount.text}. Use: ${repair.newAmount.text}.",
                onClick = { choose(option.rowIndex) },
            )
        },
        safetyNote = "Other items stay as written. Beta will check the whole list again before you review it. Nothing has been added.",
        secondary = SwiggyStepAction("None of these — edit my list") { saveOriginalThenEdit() },
        cancel = { attemptExit() },
    )

    /** Persist the exact original before handing control to the editable recovery screen. */
    fun saveDraft(): Boolean {
        if (!beginAction()) return false
        return try {
            statusOverride = null
            // This review has no editor: only the original instruction is safe to save here.
            val saved = session.edit(repair.originalInstruction) && session.saveDraft()
            if (!isCurrent()) {
                session.invalidate()
                return false
            }
            if (!saved) failed()
            notifyStatus()
            saved
        } finally {
            busy = false
        }
    }

    /** Explicitly discard only after the session has offered that action following two failed exits. */
    fun leaveWithoutSaving() {
        if (!beginAction()) return
        try {
            statusOverride = null
            val outcome = session.leaveWithoutSaving()
            if (outcome is GroceryIntentRecoverySession.Outcome.Exit && outcome.withoutSaving && isCurrent()) {
                consumed = true
                onUnsavedExit()
            }
            notifyStatus()
        } finally {
            busy = false
        }
    }

    fun invalidate() {
        consumed = true
        session.invalidate()
    }

    private fun choose(rowIndex: Int) {
        if (!beginAction()) return
        try {
            statusOverride = null
            val edited = repair.editedInstruction(rowIndex)
            val saved = session.edit(edited) && session.saveDraft()
            if (!isCurrent()) {
                session.invalidate()
                return
            }
            if (!saved) {
                statusOverride = session.message
                session.invalidate()
                session = newSession()
                failed()
                notifyStatus()
                return
            }
            consumed = true
            session.invalidate()
            recheck(edited)
        } finally {
            busy = false
        }
    }

    private fun saveOriginalThenEdit() {
        if (!saveDraft() || !isCurrent() || consumed) return
        consumed = true
        session.invalidate()
        edit()
    }

    private fun attemptExit() {
        if (!beginAction()) return
        try {
            statusOverride = null
            val outcome = session.attempt(GroceryIntentRecoverySession.Action.EXIT)
            if (!isCurrent()) {
                session.invalidate()
                return
            }
            if (outcome is GroceryIntentRecoverySession.Outcome.Exit && !outcome.withoutSaving) {
                consumed = true
                session.invalidate()
                cancel()
                return
            }
            if (session.message != null) failed()
            notifyStatus()
        } finally {
            busy = false
        }
    }

    private fun beginAction(): Boolean {
        if (consumed || busy || !isCurrent()) return false
        busy = true
        return true
    }

    private fun notifyStatus() = onStatusChanged(message, session.canLeaveWithoutSaving)

    private fun newSession() = GroceryIntentRecoverySession(
        originalInstruction = repair.originalInstruction,
        isCurrent = isCurrent,
        persist = persistDraft,
        blankMessage = "",
    )
}
