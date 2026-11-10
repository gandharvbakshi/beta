package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentRecoverySessionTest {
    private class Harness {
        var current = true
        var saveSucceeds = true
        val writes = mutableListOf<Pair<String, String>>()

        fun session(original: String = "one rice", blankMessage: String = "Enter a list.") =
            GroceryIntentRecoverySession(
                originalInstruction = original,
                isCurrent = { current },
                persist = { expected, next ->
                    writes += expected to next
                    saveSucceeds
                },
                blankMessage = blankMessage,
            )
    }

    @Test
    fun editKeepsExactTextWithoutPersistingUntilAnAction() {
        val h = Harness()
        val session = h.session()

        assertTrue(session.edit("  two rice\n"))

        assertEquals("  two rice\n", session.text)
        assertEquals("one rice", session.lastSavedText)
        assertTrue(h.writes.isEmpty())
        assertNull(session.message)
    }

    @Test
    fun recheckSavesBeforeReturningChangedText() {
        val h = Harness()
        val session = h.session()
        session.edit("two rice")

        assertEquals(GroceryIntentRecoverySession.Outcome.Recheck("two rice"),
            session.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals(listOf("one rice" to "two rice"), h.writes)
        assertEquals("two rice", session.lastSavedText)
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.RECHECK))
    }

    @Test
    fun unchangedTrimmedTextAndBlankTextDoNotRecheck() {
        val unchanged = Harness().session(original = " one rice ")
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            unchanged.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals(GroceryIntentRecoverySession.UNCHANGED, unchanged.message)

        val blank = Harness().session(blankMessage = "Please enter your list.")
        assertTrue(blank.edit(" \t "))
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            blank.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals("Please enter your list.", blank.message)
    }

    @Test
    fun failedSaveKeepsTextEditableAndBlocksRecheckOrVoiceUntilRetry() {
        val h = Harness().apply { saveSucceeds = false }
        val session = h.session()

        assertTrue(session.edit("two rice"))
        assertEquals("two rice", session.text)
        assertEquals("one rice", session.lastSavedText)
        assertTrue(h.writes.isEmpty())
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals(GroceryIntentRecoverySession.FAILED_SAVE, session.message)
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.VOICE))

        h.saveSucceeds = true
        assertEquals(GroceryIntentRecoverySession.Outcome.Recheck("two rice"),
            session.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals("two rice", session.lastSavedText)
    }

    @Test
    fun voiceCanUseBlankTextOnlyAfterSuccessfulSave() {
        val h = Harness()
        val session = h.session()
        session.edit("")
        assertEquals(GroceryIntentRecoverySession.Outcome.Voice(""),
            session.attempt(GroceryIntentRecoverySession.Action.VOICE))
    }

    @Test
    fun exitNeedsTwoFailedSavesBeforeExplicitDiscardChoice() {
        val h = Harness().apply { saveSucceeds = false }
        val session = h.session()

        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.EXIT))
        assertFalse(session.canLeaveWithoutSaving)
        assertEquals(GroceryIntentRecoverySession.FAILED_SAVE, session.message)
        assertEquals(GroceryIntentRecoverySession.Outcome.None, session.leaveWithoutSaving())

        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.EXIT))
        assertTrue(session.canLeaveWithoutSaving)
        assertEquals(GroceryIntentRecoverySession.UNSAVED_EXIT_WARNING, session.message)
        assertEquals(GroceryIntentRecoverySession.Outcome.Exit(withoutSaving = true),
            session.leaveWithoutSaving())
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.leaveWithoutSaving())
        assertEquals(2, h.writes.size)
    }

    @Test
    fun editingOrNonExitActionClearsExplicitDiscardOffer() {
        val h = Harness().apply { saveSucceeds = false }
        val session = h.session()
        session.attempt(GroceryIntentRecoverySession.Action.EXIT)
        session.attempt(GroceryIntentRecoverySession.Action.EXIT)
        assertTrue(session.canLeaveWithoutSaving)

        session.edit("three rice")
        assertFalse(session.canLeaveWithoutSaving)
        assertEquals(GroceryIntentRecoverySession.Outcome.None, session.leaveWithoutSaving())
        session.attempt(GroceryIntentRecoverySession.Action.EXIT)
        assertFalse(session.canLeaveWithoutSaving)
        session.attempt(GroceryIntentRecoverySession.Action.EXIT)
        assertTrue(session.canLeaveWithoutSaving)
        session.attempt(GroceryIntentRecoverySession.Action.VOICE)
        assertFalse(session.canLeaveWithoutSaving)
    }

    @Test
    fun successfulActionsAreOneShotAndPersistenceExceptionsFailClosed() {
        val h = Harness()
        val session = h.session()
        assertEquals(GroceryIntentRecoverySession.Outcome.Exit(withoutSaving = false),
            session.attempt(GroceryIntentRecoverySession.Action.EXIT))
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.VOICE))

        val throwing = GroceryIntentRecoverySession(
            originalInstruction = "one rice",
            isCurrent = { true },
            persist = { _, _ -> error("disk failure") },
            blankMessage = "Enter a list.",
        )
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            throwing.attempt(GroceryIntentRecoverySession.Action.VOICE))
        assertEquals(GroceryIntentRecoverySession.FAILED_SAVE, throwing.message)
    }

    @Test
    fun staleOrInvalidatedSessionCannotWriteOrReturnOutcome() {
        val h = Harness()
        val stale = h.session()
        h.current = false
        assertFalse(stale.edit("two rice"))
        assertFalse(stale.saveDraft())
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            stale.attempt(GroceryIntentRecoverySession.Action.EXIT))
        assertEquals(GroceryIntentRecoverySession.Outcome.None, stale.leaveWithoutSaving())
        assertTrue(h.writes.isEmpty())

        h.current = true
        val invalidated = h.session()
        invalidated.invalidate()
        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            invalidated.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertTrue(h.writes.isEmpty())
    }

    @Test
    fun exitSaveRetriesUseMostRecentlySavedTextAsCasExpectedValue() {
        val h = Harness()
        val session = h.session()
        session.edit("two rice")
        h.saveSucceeds = false

        session.attempt(GroceryIntentRecoverySession.Action.EXIT)
        session.attempt(GroceryIntentRecoverySession.Action.EXIT)

        assertEquals(listOf("one rice" to "two rice", "one rice" to "two rice"), h.writes)
    }

    @Test
    fun lifecycleSavePersistsLatestExactTextWithoutProducingAnAction() {
        val h = Harness()
        val session = h.session()
        session.edit("  two rice\n")

        assertTrue(session.saveDraft())

        assertEquals(listOf("one rice" to "  two rice\n"), h.writes)
        assertEquals("  two rice\n", session.lastSavedText)
        assertEquals("  two rice\n", session.text)
    }

    @Test
    fun persistCallbackInvalidationPreventsCasAdvanceAndActionOutcome() {
        var current = true
        lateinit var session: GroceryIntentRecoverySession
        var writes = 0
        session = GroceryIntentRecoverySession(
            originalInstruction = "one rice",
            isCurrent = { current },
            persist = { _, _ ->
                writes += 1
                current = false
                session.invalidate()
                true
            },
            blankMessage = "Enter a list.",
        )
        session.edit("two rice")

        assertEquals(GroceryIntentRecoverySession.Outcome.None,
            session.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals(1, writes)
        assertEquals("one rice", session.lastSavedText)
    }

    @Test
    fun nestedAttemptDuringPersistenceCannotWriteOrReturnItsOwnOutcome() {
        var current = true
        lateinit var session: GroceryIntentRecoverySession
        var writes = 0
        var nestedOutcome: GroceryIntentRecoverySession.Outcome? = null
        session = GroceryIntentRecoverySession(
            originalInstruction = "one rice",
            isCurrent = { current },
            persist = { _, _ ->
                writes += 1
                nestedOutcome = session.attempt(GroceryIntentRecoverySession.Action.VOICE)
                true
            },
            blankMessage = "Enter a list.",
        )
        session.edit("two rice")

        assertEquals(GroceryIntentRecoverySession.Outcome.Recheck("two rice"),
            session.attempt(GroceryIntentRecoverySession.Action.RECHECK))
        assertEquals(GroceryIntentRecoverySession.Outcome.None, nestedOutcome)
        assertEquals(1, writes)
        assertEquals("two rice", session.lastSavedText)
    }
}
