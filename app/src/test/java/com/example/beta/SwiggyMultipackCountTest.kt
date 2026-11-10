package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyMultipackCountTest {
    @Test
    fun bare_count_against_two_piece_multipack_is_held_for_review() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g x 2")

        assertHeld(item, candidate)
    }

    @Test
    fun bare_count_against_prefix_multipack_is_held_for_review() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 2 x 150 g")

        assertHeld(item, candidate)
    }

    @Test
    fun bare_count_against_multipack_is_held_even_when_divisible() {
        val item = countItem(4, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g x 2")

        assertHeld(item, candidate)
    }

    @Test
    fun bare_count_with_three_requested_items_is_held_against_multipack() {
        val item = countItem(3, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g x 2")

        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
        assertTrue(resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
        assertThrowsIllegalArgumentException {
            swiggyRequestedCartQuantity(item, candidate)
        }
    }

    @Test
    fun single_weighted_offer_proposes_bare_requested_count_for_review() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · 150 g")

        val resolution = resolveSwiggyCartQuantity(item, candidate)
        assertTrue(resolution is SwiggyCartQuantityResolution.RetailProposal)
        resolution as SwiggyCartQuantityResolution.RetailProposal
        assertEquals(2, resolution.cartUnits)
        assertEquals("150 g", resolution.packSize)
        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
        assertThrowsIllegalArgumentException { swiggyRequestedCartQuantity(item, candidate) }
    }

    @Test
    fun bare_count_against_pack_of_two_is_held_for_review() {
        val item = countItem(2, "chocolate")
        val candidate = candidate("Dark Chocolate · Pack of 2")

        assertHeld(item, candidate)
    }

    @Test
    fun explicit_logical_piece_count_allows_exact_piece_pack_arithmetic() {
        val item = countItem(4, "chocolate").copy(logicalPieceCount = true)
        val candidate = candidate("Dark Chocolate · 2 pcs")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(2, swiggyRequestedCartQuantity(item, candidate))
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

    private fun assertHeld(item: ParsedItem, candidate: SwiggyMcpClient.RecommendationCandidate) {
        assertTrue(resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
        assertThrowsIllegalArgumentException { swiggyRequestedCartQuantity(item, candidate) }
    }

    private inline fun assertThrowsIllegalArgumentException(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
