package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test

class SwiggyArticleMeasureTest {
    private fun offer(title: String, pack: String) = SwiggyMcpClient.RecommendationCandidate(
        "spin", "$title · $pack", variant = pack, skuId = "sku", catalogueQuantity = CatalogueQuantityEvidence(
            1, "search_variation", "spin", "sku", CatalogueQuantityFields(packSize = pack)))

    @Test fun originalArticleDesignatesOnePhysicalMeasureNotOneSellableSku() {
        for ((source,title,pack) in listOf(
            Triple("Please get me a kilo of oranges.", "Oranges", "250 g"),
            Triple("a litre of milk", "Milk", "250 ml"),
            Triple("a kilogram of onions", "Onions", "250 g"))) {
            val parsed = prepareSwiggyMcpItems(source) { null }
            assertEquals(source, 1, parsed.size)
            assertNull(source, swiggyMcpItemValidationMessage(source, parsed))
            val row = parsed.single()
            assertEquals(source, title.lowercase(), row.query.lowercase())
            assertEquals(source, if (pack.endsWith("ml")) Quantity.Volume(1000) else Quantity.Weight(1000), row.quantity)
            val good = offer(title,pack)
            val checked = assessSwiggyIntentQuantity(row,SwiggyMcpClient.Recommendations(listOf(good)))
            val final = SwiggyCartReviewRequest.build(listOf(checked),mapOf(0 to good),emptySet())
            assertNotNull(source,final)
            assertEquals(source,4,final!!.requestedItems.single().quantity)
            assertTrue(source,final.quantityProposals.isEmpty())
            for (bad in listOf(offer("Unrelated Paper",pack),offer(title,if(pack.endsWith("ml")) "300 ml" else "300 g"))) {
                val c = assessSwiggyIntentQuantity(row,SwiggyMcpClient.Recommendations(listOf(bad)))
                assertNull(source,SwiggyCartReviewRequest.build(listOf(c),mapOf(0 to bad),emptySet()))
            }
        }
    }

    @Test fun completePurchaseOwnsTheArticleNotAnAmountConditionOrAnotherRow() {
        for (source in listOf("not a litre of milk", "half a litre of milk", "a litre of milk if available",
            "a litre of 500 ml milk cartons", "a litre of milk or juice", "a kilo of rice with 100 g dal",
            "a kilo and a half of rice", "a litre-of milk", "a kilo of")) {
            assertNull(source,sourceArticleMeasure(source))
        }
        for (source in listOf("a litre of milk, only if available", "a kilo of rice, no sugar",
                "a kilo of rice, 400 g")) {
            val items = prepareSwiggyMcpItems(source) { null }
            assertTrue(source,items.isEmpty() || swiggyMcpItemValidationMessage(source,items) != null)
        }
    }
}
