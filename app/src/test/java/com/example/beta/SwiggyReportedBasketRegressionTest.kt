package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import org.junit.Assert.*
import org.junit.Test

/** Parsed text -> eligible live catalogue fixture -> exact requested cart count. No MCP calls. */
class SwiggyReportedBasketRegressionTest {
    @Test fun threeItemMixedRequestPreservesProductFormsAndFiveExactMilkPacks() {
        val text = "500 ml 5 milk tetta packs, dark chocolate, popcorn butter ready made"
        val items = prepareSwiggyMcpItems(text) { null }
        assertNull(swiggyMcpItemValidationMessage(text, items))
        assertEquals(3, items.size)
        val candidates = listOf(
            RecommendationCandidate("milk", "Amul Milk Tetra Pack · 500 ml"),
            RecommendationCandidate("chocolate", "Amul Dark Chocolate · 150 g"),
            RecommendationCandidate("popcorn", "Butter Popcorn Ready to Eat · 50 g"),
        )
        items.zip(candidates).forEach { (item, candidate) ->
            assertTrue(item.query, isSwiggyCandidateAllowed(item, candidate))
            assertTrue(item.query, isSwiggyCandidateCountCompatible(item, candidate))
        }
        assertEquals(5, swiggyRequestedCartQuantity(items[0], candidates[0]))
        assertFalse(isSwiggyCandidateAllowed(items[0], RecommendationCandidate("pouch", "Amul Milk Pouch · 500 ml")))
        assertFalse(isSwiggyCandidateAllowed(items[0], RecommendationCandidate("wrong-size", "Amul Milk Tetra Pack · 1 L")))
    }

    @Test fun conflictingPackRequestStopsBeforeProviderSearch() {
        val text = "500 ml 1 l 5 milk tetra packs"
        assertNotNull(swiggyMcpItemValidationMessage(text, prepareSwiggyMcpItems(text) { null }))
    }
}
