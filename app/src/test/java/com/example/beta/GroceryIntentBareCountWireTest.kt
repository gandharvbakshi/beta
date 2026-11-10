package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire captured from the actual quoted compiler and backend review. */
class GroceryIntentBareCountWireTest {
    @Test fun recoveredCountKeepsSourceQuantityButNeedsRetailUnitReview() {
        val item = GroceryIntentReviewV2Response.parse(wires[0], "two milk").draft.prepareForReview().items.single()
        assertEquals(Quantity.Count(2), item.quantity)
        assertFalse(item.retailPackCount)
        assertTrue(item.requiresIntentQuantityProof)
        val product = candidate("Milk", "milk")
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, product) }
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(product)))
        assertUnqualifiedCountNeedsReview(checked, product, 2)
    }

    @Test fun recoveredNumericNameStillQuarantinesWholeRawCatalogueAndFinalCartQuantity() {
        listOf(
            Triple(1, "Five Star chocolate", "Cadbury 5Star Chocolate"),
            Triple(2, "Three Roses tea", "Three Roses Tea"),
        ).forEach { (index, source, title) ->
            val item = GroceryIntentReviewV2Response.parse(wires[index], source).draft.prepareForReview().items.single()
            assertFalse(item.retailPackCount)
            val ordinary = candidate("Plain Chocolate", "other")
            val collision = candidate(title, "numeric")
            // Keep the collision beyond a small shortlist; all raw candidates matter.
            val raw = Recommendations(candidates = List(5) { ordinary.copy(skuId = "other-$it", spinId = "other-$it") } + collision)
            assertNotNull(swiggyIntentQuantityIssue(item, raw))
            val checked = assessSwiggyIntentQuantity(item, raw)
            raw.candidates.forEach { product ->
                assertFalse(swiggyIntentQuantityCandidateChecked(checked, product))
                assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(checked, product) }
            }
        }
    }

    private fun candidate(title: String, id: String) =
        RecommendationCandidate(spinId = id, skuId = id, label = title)

    private val wires = listOf(
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"two milk","source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":"milk","quantity":2,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":"two milk","supporting_text":[],"needs_clarification":false,"quantity_text":"two","pack_text":null},"evidence":{"primary":{"text":"two milk","start_utf16":0,"end_utf16":8},"supporting":[]},"quantity_origin":"stated","amount_role_status":"unresolved"}],"blocked_rows":[]},"model":"fixture","promptVersion":"bare-count-test","latencyMs":1,"usage":{}}""",
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"Five Star chocolate","source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":"Star chocolate","quantity":5,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":"Five Star chocolate","supporting_text":[],"needs_clarification":false,"quantity_text":"Five","pack_text":null},"evidence":{"primary":{"text":"Five Star chocolate","start_utf16":0,"end_utf16":19},"supporting":[]},"quantity_origin":"stated","amount_role_status":"unresolved"}],"blocked_rows":[]},"model":"fixture","promptVersion":"bare-count-test","latencyMs":1,"usage":{}}""",
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"Three Roses tea","source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":"Roses tea","quantity":3,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":"Three Roses tea","supporting_text":[],"needs_clarification":false,"quantity_text":"Three","pack_text":null},"evidence":{"primary":{"text":"Three Roses tea","start_utf16":0,"end_utf16":15},"supporting":[]},"quantity_origin":"stated","amount_role_status":"unresolved"}],"blocked_rows":[]},"model":"fixture","promptVersion":"bare-count-test","latencyMs":1,"usage":{}}"""
    )
}
