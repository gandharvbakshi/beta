package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Test

/** An unsupported same-dimension measurement must not vanish when a second field looks valid. */
class SwiggyMeasuredPackExactDecimalTest {
    private fun checkOffer(pack: String, variant: String?, expected: Int?, liquid: Boolean = false) {
        val product = if (liquid) "sunflower oil" else "wheat flour"
        val item = ParsedItem(if (liquid) "1 L $product" else "1 kg $product", product,
            if (liquid) Quantity.Volume(1_000) else Quantity.Weight(1_000))
        val offer = RecommendationCandidate("test-spin", "$product $pack", variant, skuId = "test-sku")
        val divisor = swiggyMeasuredPackQuantity(item, offer)
        val resolved = resolveSwiggyCartQuantity(item, offer)
        val request = SwiggyCartReviewRequest.build(listOf(item), mapOf(0 to offer), emptySet())
        assertEquals("measure / resolution / final review for $pack and $variant",
            listOf(expected, expected, expected), listOf(divisor,
                (resolved as? SwiggyCartQuantityResolution.Exact)?.cartUnits,
                request?.requestedItems?.single()?.quantity))
        if (expected == null) assertEquals("not a retail proposal", true, resolved is SwiggyCartQuantityResolution.Held)
    }

    @Test fun extraPrecisionCannotRoundToAnExactGramOrMillilitre() {
        checkOffer("1.00000000000000000001 kg", null, null)
        checkOffer("0.99999999999999999999 L", null, null, liquid = true)
    }

    @Test fun disagreementCannotBeRoundedAwayOrDroppedAsUnrepresentable() {
        checkOffer("1 kg", "1.00000000000000000001 kg", null)
        checkOffer("500.4 g", "1 kg", null)
        checkOffer("2147483648 g", "1 kg", null)
        checkOffer("1 L", "500.4 ml", null, liquid = true)
    }

    @Test fun ordinaryDisagreementAndOutOfRangeOrNonintegralSolePackStillHold() {
        checkOffer("1 kg", "500 g", null)
        checkOffer("2147483648 g", null, null)
        checkOffer("0.3333333333333333 kg", null, null)
    }

    @Test fun equivalentFractionalKilogramsAndLitresStillGiveExactWholeUnits() {
        checkOffer("0.25 kg", "250 g", 4)
        checkOffer("0.5 L", "500 ml", 2, liquid = true)
    }
}
