package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import com.example.beta.automation.ParsedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCartPlanRefreshTest {
    @Test
    fun selectionStartsOneReadAndIsNotEligibleUntilThatExactReadCompletes() {
        val refresh = SwiggyCartPlanRefresh()
        val input = input(1, "milk")

        val read = requireNotNull(refresh.select(input))
        assertEquals(1L, read.revision)
        assertEquals(input, read.input)
        assertFalse(refresh.isCurrentCompletedPlan(input))

        val completion = refresh.complete(read)
        assertSame(read, completion.current)
        assertNull(completion.next)
        assertTrue(refresh.isCurrentCompletedPlan(input))
    }

    @Test
    fun manyChangesDuringReadCoalesceAndOnlyLatestInputStartsNext() {
        val refresh = SwiggyCartPlanRefresh()
        val first = input(10, "milk")
        val latest = input(15, "oats", address = address("work", "Synthetic Work"))
        val flight = requireNotNull(refresh.select(first))

        listOf("oats", "rice", "tea", "soap").forEachIndexed { index, name ->
            assertNull(refresh.select(input(11L + index, name)))
        }
        assertNull(refresh.select(latest))

        val completion = refresh.complete(flight)
        assertNull(completion.current)
        val next = requireNotNull(completion.next)
        assertEquals(latest, next.input)
        assertTrue(next.revision > flight.revision)
        assertFalse(refresh.isCurrentCompletedPlan(latest))

        assertSame(next, refresh.complete(next).current)
        assertTrue(refresh.isCurrentCompletedPlan(latest))
    }

    @Test
    fun returningToEqualInputStillUsesANewerRevision() {
        val refresh = SwiggyCartPlanRefresh()
        val a = input(20, "milk")
        val firstA = requireNotNull(refresh.select(a))
        assertNull(refresh.select(input(20, "oats")))
        assertNull(refresh.select(a))

        val nextA = requireNotNull(refresh.complete(firstA).next)
        assertEquals(a, nextA.input)
        assertNotEquals(firstA.revision, nextA.revision)
        assertFalse(refresh.isCurrentCompletedPlan(a))
        assertSame(nextA, refresh.complete(nextA).current)
        assertTrue(refresh.isCurrentCompletedPlan(a))
    }

    @Test
    fun forgedAndDuplicateCallbacksCannotRetireTheActualCurrentFlight() {
        val refresh = SwiggyCartPlanRefresh()
        val first = requireNotNull(refresh.select(input(30, "milk")))
        assertNull(refresh.select(input(31, "oats")))

        val forged = SwiggyCartPlanRefresh.Read(first.revision, first.input)
        assertNull(refresh.complete(forged).current)
        assertNull(refresh.complete(forged).next)
        val latestInput = input(32, "rice")
        assertNull(refresh.select(latestInput))

        val second = requireNotNull(refresh.complete(first).next)
        assertEquals(latestInput, second.input)
        assertNull(refresh.complete(first).current)
        assertNull(refresh.complete(first).next)
        val thirdInput = input(33, "tea")
        assertNull(refresh.select(thirdInput))
        val thirdCompletion = refresh.complete(second)
        assertNull(thirdCompletion.current)
        val third = requireNotNull(thirdCompletion.next)
        assertEquals(thirdInput, third.input)
        assertSame(third, refresh.complete(third).current)
    }

    @Test
    fun invalidationClearsEligibilityButKeepsTheRealFlightSlotUntilCallback() {
        val refresh = SwiggyCartPlanRefresh()
        val old = input(40, "milk")
        val flight = requireNotNull(refresh.select(old))
        refresh.invalidate()
        assertFalse(refresh.isCurrentCompletedPlan(old))

        val nextInput = input(41, "oats", address("new-home", "Synthetic New Home"))
        assertNull(refresh.select(nextInput))
        val completion = refresh.complete(flight)
        assertNull(completion.current)
        val next = requireNotNull(completion.next)
        assertEquals(nextInput, next.input)
        val nextPlan = next.input as SwiggyCartPlanRefresh.Input.Plan
        assertEquals("new-home", nextPlan.address.id)
        assertEquals(listOf("oats"), nextPlan.request.requestedItems.map { it.displayName })
        assertSame(next, refresh.complete(next).current)
        assertTrue(refresh.isCurrentCompletedPlan(nextInput))
    }

    @Test
    fun emptySelectionDoesNotStartOrBecomeEligibleAndCannotBeRevivedByOldResult() {
        val refresh = SwiggyCartPlanRefresh()
        val old = input(50, "milk")
        val flight = requireNotNull(refresh.select(old))
        val empty = input(51, "eggs", skipped = setOf(0))

        assertTrue(empty.request.requestedItems.isEmpty())
        assertNull(refresh.select(empty))
        val completion = refresh.complete(flight)
        assertNull(completion.current)
        assertNull(completion.next)
        assertFalse(refresh.isCurrentCompletedPlan(empty))
    }

    @Test
    fun sourceCandidateSkippedSetOrAddressChangeInvalidatesCompletedEligibility() {
        val baselineItem = ParsedItem(rawText = "milk", query = "milk")
        val base = requestInput(60, listOf(baselineItem), mapOf(0 to candidate("milk")), emptySet())
        for (changed in listOf(
            requestInput(60, listOf(baselineItem.copy(rawText = "please get milk")), mapOf(0 to candidate("milk")), emptySet()),
            requestInput(60, listOf(baselineItem), mapOf(0 to candidate("milk").copy(suggested = true)), emptySet()),
            requestInput(60, listOf(baselineItem, ParsedItem("oats", "oats")),
                mapOf(0 to candidate("milk"), 1 to candidate("oats")), setOf(1)),
            base.copy(address = address("other-home", "Synthetic Other Home")),
        )) {
            val refresh = SwiggyCartPlanRefresh()
            val read = requireNotNull(refresh.select(base))
            assertSame(read, refresh.complete(read).current)
            assertTrue(refresh.isCurrentCompletedPlan(base))
            assertNotEquals(base, changed)
            refresh.select(changed)
            assertFalse(refresh.isCurrentCompletedPlan(base))
            assertFalse(refresh.isCurrentCompletedPlan(changed))
        }
    }

    @Test
    fun sameInputRetryHasANewRevisionAndOldDuplicateCannotFinishIt() {
        val refresh = SwiggyCartPlanRefresh()
        val input = input(70, "milk")
        val first = requireNotNull(refresh.select(input))
        assertSame(first, refresh.complete(first).current)
        assertTrue(refresh.isCurrentCompletedPlan(input))

        val retry = requireNotNull(refresh.select(input))
        assertTrue(retry.revision > first.revision)
        assertFalse(refresh.isCurrentCompletedPlan(input))
        assertNull(refresh.complete(first).current)
        assertNull(refresh.complete(first).next)
        assertFalse(refresh.isCurrentCompletedPlan(input))
        assertSame(retry, refresh.complete(retry).current)
        assertTrue(refresh.isCurrentCompletedPlan(input))
    }

    @Test
    fun inputAndReadDiagnosticsRedactAddressAndRequestDetails() {
        val input = input(80, "synthetic private item", address = address("home-id", "Synthetic Private Address"))
        val read = requireNotNull(SwiggyCartPlanRefresh().select(input))
        val snapshot = SwiggyCartPlanRefresh.Input.Snapshot(81, address("snapshot-id", "Synthetic Snapshot Address"))
        for (diagnostic in listOf(input.toString(), read.toString(), snapshot.toString())) {
            assertFalse(diagnostic.contains("synthetic private item"))
            assertFalse(diagnostic.contains("Synthetic Private Address"))
            assertFalse(diagnostic.contains("home-id"))
            assertFalse(diagnostic.contains("sku-synthetic private item"))
            assertFalse(diagnostic.contains("Synthetic Snapshot Address"))
            assertFalse(diagnostic.contains("snapshot-id"))
        }
    }

    @Test
    fun snapshotReadsCoalesceWithPlansInEitherDirectionAndOnlyLatestStarts() {
        val refresh = SwiggyCartPlanRefresh()
        val snapshot = SwiggyCartPlanRefresh.Input.Snapshot(90, address("home", "Synthetic Home"))
        val plan = input(91, "milk", address("work", "Synthetic Work"))

        val snapshotRead = requireNotNull(refresh.select(snapshot))
        assertEquals(snapshot, snapshotRead.input)
        assertNull(refresh.select(plan))
        val planAfterSnapshot = requireNotNull(refresh.complete(snapshotRead).next)
        assertEquals(plan, planAfterSnapshot.input)
        assertNull(refresh.complete(snapshotRead).current)
        assertNull(refresh.complete(snapshotRead).next)
        assertFalse(refresh.isCurrentCompletedPlan(plan))
        assertSame(planAfterSnapshot, refresh.complete(planAfterSnapshot).current)
        assertTrue(refresh.isCurrentCompletedPlan(plan))

        val planRead = requireNotNull(refresh.select(plan))
        assertNull(refresh.select(snapshot))
        val snapshotAfterPlan = requireNotNull(refresh.complete(planRead).next)
        assertEquals(snapshot, snapshotAfterPlan.input)
        assertSame(snapshotAfterPlan, refresh.complete(snapshotAfterPlan).current)
        assertFalse(refresh.isCurrentCompletedPlan(plan))
    }

    @Test
    fun completedSnapshotCannotMakeAnyPlanEligibleEvenWithSameOperationAndAddress() {
        val refresh = SwiggyCartPlanRefresh()
        val address = address("home", "Synthetic Home")
        val snapshot = SwiggyCartPlanRefresh.Input.Snapshot(92, address)
        val read = requireNotNull(refresh.select(snapshot))
        assertSame(read, refresh.complete(read).current)

        val plan = input(92, "milk", address)
        val otherPlan = input(92, "oats", address)
        assertFalse(refresh.isCurrentCompletedPlan(plan))
        assertFalse(refresh.isCurrentCompletedPlan(otherPlan))
        val planRead = requireNotNull(refresh.select(plan))
        assertSame(planRead, refresh.complete(planRead).current)
        assertTrue(refresh.isCurrentCompletedPlan(plan))
    }

    @Test
    fun invalidationDuringSnapshotKeepsFlightAndQueuesOnlyLatestPlan() {
        val refresh = SwiggyCartPlanRefresh()
        val snapshot = SwiggyCartPlanRefresh.Input.Snapshot(93, address("home", "Synthetic Home"))
        val flight = requireNotNull(refresh.select(snapshot))
        refresh.invalidate()
        val latestPlan = input(94, "oats", address("new-home", "Synthetic New Home"))

        assertNull(refresh.select(latestPlan))
        assertFalse(refresh.isCurrentCompletedPlan(latestPlan))
        val latestRead = requireNotNull(refresh.complete(flight).next)
        assertEquals(latestPlan, latestRead.input)
        assertSame(latestRead, refresh.complete(latestRead).current)
        assertTrue(refresh.isCurrentCompletedPlan(latestPlan))
    }

    @Test
    fun forgedSnapshotCallbackCannotRetireCurrentPlanFlight() {
        val refresh = SwiggyCartPlanRefresh()
        val firstPlan = input(95, "milk")
        val planRead = requireNotNull(refresh.select(firstPlan))
        val forgedSnapshot = SwiggyCartPlanRefresh.Read(
            planRead.revision,
            SwiggyCartPlanRefresh.Input.Snapshot(95, firstPlan.address),
        )

        assertNull(refresh.complete(forgedSnapshot).current)
        assertNull(refresh.complete(forgedSnapshot).next)
        val latest = SwiggyCartPlanRefresh.Input.Snapshot(96, address("work", "Synthetic Work"))
        assertNull(refresh.select(latest))
        val completion = refresh.complete(planRead)
        assertNull(completion.current)
        val next = requireNotNull(completion.next)
        assertEquals(latest, next.input)
        assertFalse(refresh.isCurrentCompletedPlan(firstPlan))
        assertSame(next, refresh.complete(next).current)
        assertFalse(refresh.isCurrentCompletedPlan(firstPlan))
    }

    private fun input(
        operationId: Long,
        name: String,
        address: SwiggyAddress = address("home", "Synthetic Home"),
        skipped: Set<Int> = emptySet(),
    ): SwiggyCartPlanRefresh.Input.Plan {
        val item = ParsedItem(rawText = name, query = name)
        return requestInput(operationId, listOf(item), mapOf(0 to candidate(name)), skipped, address)
    }

    private fun requestInput(
        operationId: Long,
        items: List<ParsedItem>,
        candidates: Map<Int, RecommendationCandidate>,
        skipped: Set<Int>,
        address: SwiggyAddress = address("home", "Synthetic Home"),
    ) = SwiggyCartPlanRefresh.Input.Plan(
        operationId,
        address,
        requireNotNull(SwiggyCartReviewRequest.build(items, candidates, skipped)),
    )

    private fun candidate(name: String) = RecommendationCandidate(
        spinId = "spin-$name", label = name, skuId = "sku-$name",
    )

    private fun address(id: String, label: String) = SwiggyAddress(id = id, label = label)
}
