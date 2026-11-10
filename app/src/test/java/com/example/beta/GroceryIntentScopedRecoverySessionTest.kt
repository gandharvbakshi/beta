package com.example.beta

import org.junit.Assert.*
import org.junit.Test

class GroceryIntentScopedRecoverySessionTest {
    @Test fun wholeDictationRejectsUnsafeCursorAndEmptySpeech() {
        val initial = GroceryIntentRecoveryRecord.begin("दूध tea")
        val session = GroceryIntentScopedRecoverySession(initial,
            GroceryIntentReviewV2Quote(initial.instruction, 0, initial.instruction.length),
            { true }, { _, _ -> true }, { _, _ -> true }, GroceryIntentEditKind.WHOLE_MESSAGE)
        for (cursor in listOf(-1, 1, initial.instruction.length + 1)) {
            session.voice("fresh ", cursor)
            assertEquals(initial.instruction, session.text)
        }
        session.voice("", 0)
        session.voice("  ", 0)
        assertEquals(initial.instruction, session.text)
        assertEquals(0L, session.generation)
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.READ, session.voice("read line 999999999999999"))
        assertEquals("That line is not in your message.", session.message)
        assertEquals(initial.instruction, session.text)
    }

    @Test fun removalWordsPreserveNativeVowelMarks() {
        val initial = GroceryIntentRecoveryRecord.begin("दूध दाल चावल")
        val session = GroceryIntentScopedRecoverySession(initial,
            GroceryIntentReviewV2Quote(initial.instruction, 0, initial.instruction.length),
            { true }, { _, _ -> true }, { _, _ -> true }, GroceryIntentEditKind.WHOLE_MESSAGE)
        session.edit("दध दल चावल")
        assertTrue(session.requiresRemovalConfirmation)
    }
    @Test fun wholeMessageInsertsAtCursorAndRequiresTwoExplicitVoiceSaves() {
        val initial = GroceryIntentRecoveryRecord.begin("milk\napples")
        var stored = initial
        val session = GroceryIntentScopedRecoverySession(initial,
            GroceryIntentReviewV2Quote(initial.instruction, 0, initial.instruction.length),
            { true }, { _, next -> stored = next; true }, { _, next -> stored = next; true },
            GroceryIntentEditKind.WHOLE_MESSAGE)
        val generation = session.generation
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.NONE, session.voice("fresh ", 0))
        assertEquals("fresh milk\napples", session.text)
        assertTrue(session.generation > generation)
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.READ, session.voice("read line 2"))
        assertEquals("apples", session.message)
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.CONFIRM, session.voice("save"))
        assertEquals("Save your changed message, 2 lines? Say save or cancel.", session.message)
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.SAVE, session.voice("save"))
        assertNotNull(session.save())
        assertEquals(1, stored.revision)
    }

    @Test fun wholeMessageGuardCountsRemovedWordsNotNetLength() {
        val initial = GroceryIntentRecoveryRecord.begin("milk apples bread tea")
        val session = GroceryIntentScopedRecoverySession(initial,
            GroceryIntentReviewV2Quote(initial.instruction, 0, initial.instruction.length),
            { true }, { _, _ -> true }, { _, _ -> true }, GroceryIntentEditKind.WHOLE_MESSAGE)
        session.edit("rice beans sugar coffee chocolate")
        assertTrue(session.requiresRemovalConfirmation)
        assertNull(session.save())
        assertNotNull(session.save(confirmLargeRemoval = true))
    }

    @Test fun removalGuardAllowsHalfAndCountsDuplicateOccurrences() {
        val initial = GroceryIntentRecoveryRecord.begin("milk milk apples bread")
        val session = GroceryIntentScopedRecoverySession(initial,
            GroceryIntentReviewV2Quote(initial.instruction, 0, initial.instruction.length),
            { true }, { _, _ -> true }, { _, _ -> true }, GroceryIntentEditKind.WHOLE_MESSAGE)
        session.edit("milk apples rice beans")
        assertFalse(session.requiresRemovalConfirmation)
        session.edit("milk rice beans flour")
        assertTrue(session.requiresRemovalConfirmation)
    }

    @Test fun differentKindCannotRestoreSameBoundedDirtyBuffer() {
        val initial = GroceryIntentRecoveryRecord.begin("milk")
        val source = GroceryIntentReviewV2Quote("milk", 0, 4)
        val dirty = requireNotNull(initial.withDirty(source, "tea"))
        assertTrue(runCatching {
            GroceryIntentScopedRecoverySession(dirty, source, { true }, { _, _ -> true },
                { _, _ -> true }, GroceryIntentEditKind.WHOLE_MESSAGE)
        }.isFailure)
    }

    @Test fun kindSwitchIsDistinctAndWholeRequiresFullSource() {
        val initial = GroceryIntentRecoveryRecord.begin("milk apples")
        val full = GroceryIntentReviewV2Quote(initial.instruction, 0, initial.instruction.length)
        val phrase = requireNotNull(initial.withDirty(full, "milk pears"))
        val whole = requireNotNull(phrase.withDirty(full, "milk pears", GroceryIntentEditKind.WHOLE_MESSAGE))
        assertNotEquals(phrase.dirty, whole.dirty)
        assertEquals(phrase.mutationGeneration + 1, whole.mutationGeneration)
        assertTrue(phrase.acceptsSuccessor(whole))
        assertNull(initial.withDirty(GroceryIntentReviewV2Quote("milk", 0, 4), "tea", GroceryIntentEditKind.WHOLE_MESSAGE))
    }
    private val original = "two milk packs, sugar free tea, four apples"
    private val quote = GroceryIntentReviewV2Quote("two milk packs", 0, 14)
    private class Harness(initial: GroceryIntentRecoveryRecord) {
        var stored = initial
        var active = true
        var fail = false
        var ambiguous = false
        var commits = 0
        var nested: (() -> Unit)? = null
        fun persist(expected: GroceryIntentRecoveryRecord, updated: GroceryIntentRecoveryRecord): Boolean {
            nested?.invoke()
            if (fail) return false
            if (stored != expected && stored != updated) return false
            stored = updated
            return !ambiguous
        }
        fun session(quote: GroceryIntentReviewV2Quote) = GroceryIntentScopedRecoverySession(stored, quote,
            isCurrent = { active }, persist = ::persist, commit = { old, next ->
                commits++
                persist(old, next)
            })
    }

    @Test fun pausePreservesBufferButDoesNotCommitWording() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        assertTrue(session.edit("three milk packs"))
        assertTrue(session.checkpoint())
        assertEquals(original, h.stored.instruction)
        assertEquals(0, h.stored.revision)
        assertEquals("three milk packs", h.stored.dirty!!.text)
        assertEquals(0, h.commits)
        val restored = h.session(quote)
        assertEquals("three milk packs", restored.text)
        assertTrue(restored.cancel())
        assertNull(h.stored.dirty)
        assertEquals(original, h.stored.instruction)
    }

    @Test fun explicitSaveChangesOnlyQuoteAndPreservesOriginal() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        session.edit("three milk packs")
        val saved = requireNotNull(session.save())
        assertEquals(original, saved.originalInstruction)
        assertEquals("three milk packs, sugar free tea, four apples", saved.instruction)
        assertEquals(1, saved.revision)
        assertNull(saved.dirty)
        assertNull(session.save())
        assertEquals(1, h.commits)
    }

    @Test fun failedAndAmbiguousSavesRetainInputAndRetryExactWrite() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        session.edit("three milk packs")
        h.fail = true
        assertNull(session.save())
        assertEquals(0, h.stored.revision)
        assertEquals("three milk packs", session.text)
        h.fail = false
        h.ambiguous = true
        assertNull(session.save()) // Buffer write committed but read-back ambiguous.
        assertEquals(original, h.stored.instruction)
        h.ambiguous = false
        assertNotNull(session.save())
        assertEquals(1, h.stored.revision)
    }

    @Test fun ambiguousCommittedSaveCanRetryWithoutAnotherRevision() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        session.edit("three milk packs")
        assertTrue(session.checkpoint())
        h.ambiguous = true
        assertNull(session.save())
        assertEquals(1, h.stored.revision)
        h.ambiguous = false
        assertNotNull(session.save())
        assertEquals(1, h.stored.revision)
    }

    @Test fun blankUnchangedStaleAndReentrantCallbacksCannotCommit() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        assertNull(session.save())
        session.edit(" ")
        assertNull(session.save())
        session.edit("three milk packs")
        h.nested = { assertNull(session.save()); assertFalse(session.edit("late text")) }
        assertNotNull(session.save())
        assertEquals(1, h.commits)
        val stale = h.session(GroceryIntentReviewV2Quote("three milk packs", 0, 16))
        stale.edit("one milk pack")
        h.active = false
        assertFalse(stale.checkpoint())
        assertNull(stale.save())
        assertEquals(1, h.commits)
    }

    @Test fun voiceDictationRequiresSeparateConfirmationAndTextEditResetsIt() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.CONFIRM, session.voice("three milk packs"))
        assertEquals(original, h.stored.instruction)
        session.edit("four milk packs")
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.CONFIRM, session.voice("save"))
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.SAVE, session.voice("save"))
        assertNotNull(session.save())
        assertEquals("four milk packs, sugar free tea, four apples", h.stored.instruction)
    }

    @Test fun recognitionPunctuationDoesNotOverwriteBufferWithACommand() {
        val h = Harness(GroceryIntentRecoveryRecord.begin(original))
        val session = h.session(quote)
        session.edit("three milk packs")
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.CONFIRM, session.voice(" Save. "))
        assertEquals("three milk packs", session.text)
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.SAVE, session.voice("Save!"))
        assertEquals(GroceryIntentScopedRecoverySession.VoiceAction.CANCEL, session.voice("Cancel।"))
        assertEquals("three milk packs", session.text)
        assertEquals(original, h.stored.instruction)
    }

    @Test fun anotherQuotesSessionCannotClearAnUnfinishedBuffer() {
        val initial = GroceryIntentRecoveryRecord.begin(original)
        val dirty = requireNotNull(initial.withDirty(quote, "three milk packs"))
        val other = GroceryIntentReviewV2Quote("sugar free tea", 16, 30)
        assertTrue(runCatching { Harness(dirty).session(other) }.isFailure)
        assertEquals("three milk packs", dirty.dirty!!.text)
    }
}
