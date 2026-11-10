package com.example.beta

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class GroceryIntentInputRepairReviewTest {
    private val original = "one rice, two milk, no sorry, yes two. Keep the rest."
    private fun quote(text: String, last: Boolean = false): GroceryIntentReviewV2Quote {
        val start = if (last) original.lastIndexOf(text) else original.indexOf(text)
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }
    private val proposal get() = GroceryIntentInputRepair(
        source = quote("no sorry, yes two."),
        newAmount = quote("two", last = true),
        options = listOf(
            GroceryIntentInputRepair.Option(0, quote("one rice"), quote("one"), quote("rice")),
            GroceryIntentInputRepair.Option(1, quote("two milk"), quote("two"), quote("milk")),
        ),
        originalInstruction = original,
    )

    private class Harness {
        var current = true
        var saveSucceeds = true
        val events = mutableListOf<String>()
        var savedOriginal: String? = null
        var savedEdit: String? = null
        var reparsed: String? = null
        var duringPersist: (() -> Unit)? = null
        val statuses = mutableListOf<Pair<String?, Boolean>>()
    }

    private fun review(h: Harness) = GroceryIntentInputRepairReview(
        proposal, isCurrent = { h.current },
        persist = { before, after ->
            h.events += "persist"
            h.savedOriginal = before
            h.savedEdit = after
            h.duringPersist?.also { h.duringPersist = null; it() }
            h.saveSucceeds
        },
        recheck = { h.events += "recheck"; h.reparsed = it },
        failed = { h.events += "failed" },
        edit = { h.events += "edit" },
        cancel = { h.events += "cancel" },
        onStatusChanged = { message, canLeave -> h.statuses.add(message to canLeave) },
        onUnsavedExit = { h.events += "discard" },
    )

    @Test fun presentationNeverChoosesOrSavesAnItem() {
        val h = Harness()
        val screen = review(h).screen()
        assertEquals(listOf("rice", "milk"), screen.choices.map { it.title })
        assertNull(screen.primary) // No default confirmation or suggested item.
        assertTrue(screen.message.contains("\u201cno sorry, yes two\u201d."))
        assertTrue(h.events.isEmpty())
    }

    @Test fun explicitChoicePersistsBeforeRecheckingTheWholeList() {
        val h = Harness()
        review(h).screen().choices.first().onClick()
        assertEquals(listOf("persist", "recheck"), h.events)
        assertEquals(original, h.savedOriginal)
        assertEquals("one rice, two milk, make rice two. Keep the rest.", h.savedEdit)
        assertEquals(h.savedEdit, h.reparsed)
    }

    @Test fun sameCountChoiceStillRequiresAnExplicitTapAndFullRecheck() {
        val h = Harness()
        review(h).screen().choices.last().onClick()
        assertEquals("one rice, two milk, make milk two. Keep the rest.", h.reparsed)
        assertEquals(listOf("persist", "recheck"), h.events)
    }

    @Test fun DoubleTapOrAnotherChoiceCannotCauseASecondRequest() {
        val h = Harness()
        val screen = review(h).screen()
        screen.choices.first().onClick()
        screen.choices.first().onClick()
        screen.choices.last().onClick()
        screen.cancel?.invoke()
        assertEquals(listOf("persist", "recheck"), h.events)
    }

    @Test fun failedChoiceStaysOpenAndAnExplicitRetryCanPersistThenRecheck() {
        val h = Harness().apply { saveSucceeds = false }
        val review = review(h)
        val screen = review.screen()
        screen.choices.first().onClick()
        screen.choices.first().onClick()
        assertEquals(listOf("persist", "failed", "persist", "failed"), h.events)
        assertNull(h.reparsed)
        assertEquals(GroceryIntentRecoverySession.FAILED_SAVE, review.message)
        assertFalse(review.canLeaveWithoutSaving)

        h.saveSucceeds = true
        screen.choices.first().onClick()
        assertEquals(listOf("persist", "failed", "persist", "failed", "persist", "recheck"), h.events)
        assertEquals(h.savedEdit, h.reparsed)
        assertNull(review.message)
    }

    @Test fun editInsteadMustSaveTheOriginalAndCanRetryAfterFailure() {
        val h = Harness().apply { saveSucceeds = false }
        val review = review(h)
        val screen = review.screen()
        val editAction = screen.secondary!!
        editAction.onClick()
        assertEquals(listOf("persist", "failed"), h.events)
        assertEquals(original, h.savedOriginal)
        assertEquals(original, h.savedEdit)
        assertEquals(GroceryIntentRecoverySession.FAILED_SAVE, review.message)

        h.saveSucceeds = true
        editAction.onClick()
        screen.choices.first().onClick()
        assertEquals(listOf("persist", "failed", "persist", "edit"), h.events)
        assertNull(h.reparsed)
    }

    @Test fun cancelRetriesAndOnlyOffersDiscardAfterTwoFailedExits() {
        val h = Harness().apply { saveSucceeds = false }
        val review = review(h)
        val screen = review.screen()
        val cancelAction = screen.cancel!!
        cancelAction.invoke()
        assertEquals(GroceryIntentRecoverySession.FAILED_SAVE, review.message)
        assertFalse(review.canLeaveWithoutSaving)
        cancelAction.invoke()
        assertEquals(GroceryIntentRecoverySession.UNSAVED_EXIT_WARNING, review.message)
        assertTrue(review.canLeaveWithoutSaving)
        assertEquals(listOf("persist", "failed", "persist", "failed"), h.events)

        review.leaveWithoutSaving()
        screen.choices.first().onClick()
        cancelAction.invoke()
        assertEquals(listOf("persist", "failed", "persist", "failed", "discard"), h.events)
        assertFalse(review.canLeaveWithoutSaving)
    }

    @Test fun successfulCancelPersistsBeforeClosingAndStalePersistCannotRecheck() {
        val h = Harness()
        review(h).screen().cancel!!.invoke()
        assertEquals(listOf("persist", "cancel"), h.events)

        val stale = Harness().apply { duringPersist = { current = false } }
        review(stale).screen().choices.first().onClick()
        assertEquals(listOf("persist"), stale.events)
        assertNull(stale.reparsed)
    }

    @Test fun reentrantChoiceDuringPersistenceIsIgnored() {
        val h = Harness()
        val review = review(h)
        val screen = review.screen()
        h.duringPersist = { screen.choices.last().onClick() }
        screen.choices.first().onClick()
        assertEquals(listOf("persist", "recheck"), h.events)
        assertEquals("one rice, two milk, make rice two. Keep the rest.", h.reparsed)
    }

    @Test fun staleOrPausedReviewCannotEditOrRecheck() {
        val h = Harness()
        val review = review(h)
        val screen = review.screen()
        h.current = false
        screen.choices.first().onClick()
        screen.secondary?.onClick?.invoke()
        screen.cancel?.invoke()
        review.leaveWithoutSaving()
        assertTrue(h.events.isEmpty())
    }
}
