package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyMultipackCountTest {
    @Test
    fun count_multipack_150g_times_2_requires_one_requested_pack_for_two_items() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g x 2")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(1, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test
    fun count_multipack_prefix_two_x_150g_also_requires_one_requested_pack_for_two_items() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 2 x 150 g")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(1, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test
    fun count_multipack_150g_times_2_scales_to_two_requested_packs_for_four_items() {
        val item = countItem(4, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g x 2")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(2, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test
    fun count_multipack_150g_times_2_rejects_three_requested_items() {
        val item = countItem(3, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g x 2")

        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
        assertThrowsIllegalArgumentException {
            swiggyRequestedCartQuantity(item, candidate)
        }
    }

    @Test
    fun single_150g_candidate_keeps_two_requested_count_items() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(2, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test
    fun pack_of_two_candidate_returns_one_requested_pack_for_two_items() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · Pack of 2")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(1, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test
    fun malformed_conflicting_multipack_counts_are_rejected() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 2 x 150 g and 3 x 150 g")

        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
        assertThrowsIllegalArgumentException {
            swiggyRequestedCartQuantity(item, candidate)
        }
    }

    @Test
    fun measured_weight_multipacks_still_reject_unknown_true_multipacks() {
        val item = ParsedItem(
            rawText = "500 g rice",
            query = "rice",
            quantity = Quantity.Weight(500),
        )
        val candidate = candidate("Brand Rice · 2 x 500 g")

        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
    }

    private fun candidate(label: String) = SwiggyMcpClient.RecommendationCandidate(
        spinId = "spin-$label",
        label = label,
    )

    private fun countItem(quantity: Int, query: String) = ParsedItem(
        rawText = "$quantity $query",
        query = query,
        quantity = Quantity.Count(quantity),
    )

    private inline fun assertThrowsIllegalArgumentException(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
