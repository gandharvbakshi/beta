package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCompositeRetailTest {
    private data class PositiveCase(
        val id: String,
        val source: String,
        val shapeText: String,
        val countAtom: String,
        val count: Int,
        val countAtStart: Boolean,
        val factor: Int,
        val measureAtom: String,
        val measureValue: String,
        val measureUnit: String,
        val containerText: String,
        val containerKey: String,
        val identity: String,
    )

    @Test
    fun anchoredOrderingsBindOriginalCountFactorInnerSizeAndIdentity() {
        val cases = listOf(
            PositiveCase("glued-reversed-shape", "milk 500mlx2 packs paanch", "500mlx2", "paanch", 5, false, 2, "500", "500", "ml", "packs", "pack", "milk"),
            PositiveCase("shape-nominal-container-count", "2x500ml milk cartons three", "2x500ml", "three", 3, false, 2, "500", "500", "ml", "cartons", "carton", "milk"),
            PositiveCase("shape-nominal-count-container", "2 x 500 ml orange juice 3 cartons", "2 x 500 ml", "3", 3, false, 2, "500", "500", "ml", "cartons", "carton", "orange juice"),
            PositiveCase("nominal-shape-container-count", "rice 2 × 500 ml cartons three", "2 × 500 ml", "three", 3, false, 2, "500", "500", "ml", "cartons", "carton", "rice"),
            PositiveCase("nominal-reversed-shape-count-container", "soap 500 ml x 2 three cartons", "500 ml x 2", "three", 3, false, 2, "500", "500", "ml", "cartons", "carton", "soap"),
            PositiveCase("count-container-shape-nominal", "two cartons of 2x500ml milk", "2x500ml", "two", 2, true, 2, "500", "500", "ml", "cartons", "carton", "milk"),
            PositiveCase("count-container-shape-nominal-hinglish-five", "2 x 500 ml milk packs paanch", "2 x 500 ml", "paanch", 5, false, 2, "500", "500", "ml", "packs", "pack", "milk"),
            PositiveCase("postfix-count-differs-from-factor", "2 x500ml milk packs three", "2 x500ml", "three", 3, false, 2, "500", "500", "ml", "packs", "pack", "milk"),
            PositiveCase("hinglish-count-container-nominal-reversed-shape", "do cartons of juice 500ml×2", "500ml×2", "do", 2, true, 2, "500", "500", "ml", "cartons", "carton", "juice"),
            // This named bar is only a source-requested unit; the test invokes
            // neither a catalogue matcher nor a cart quantity resolver.
            PositiveCase("named-bars-are-requested-source-units", "2 bars of 2 x 100 g soap", "2 x 100 g", "2", 2, true, 2, "100", "100", "g", "bars", "bar", "soap"),
            PositiveCase("singular-requested-carton", "one carton of 2 x 500 ml milk", "2 x 500 ml", "one", 1, true, 2, "500", "500", "ml", "carton", "carton", "milk"),
        )

        for (case in cases) {
            val receipt = sourceCompositeRetailReceipt(case.source)
            assertNotNull("${case.id}: expected a source-only receipt", receipt)
            assertReceipt(case, requireNotNull(receipt))
        }
    }

    @Test
    fun equalTotalsWithDifferentFactorsRemainDifferentShapesAndUnitsCanonicalize() {
        val twoByFiveHundred = requireNotNull(sourceCompositeRetailReceipt("two cartons of 2 x 500 ml milk"))
        val fourByTwoFifty = requireNotNull(sourceCompositeRetailReceipt("two cartons of 4 x 250 ml milk"))
        assertNotEquals(twoByFiveHundred.shape(), fourByTwoFifty.shape())
        // Total contents are equal, but the factor/inner-size decomposition is not.
        assertEquals(0, BigDecimal("1000").compareTo(
            twoByFiveHundred.shape().inner.baseValue.multiply(BigDecimal(twoByFiveHundred.shape().factor)),
        ))
        assertEquals(0, BigDecimal("1000").compareTo(
            fourByTwoFifty.shape().inner.baseValue.multiply(BigDecimal(fourByTwoFifty.shape().factor)),
        ))

        assertNull("Factor one is not in this source grammar", sourceCompositeRetailReceipt("two cartons of 1 x 1000 ml milk"))
        val kilogram = requireNotNull(sourceCompositeRetailReceipt("two cartons of 2 x 1 kg milk"))
        val grams = requireNotNull(sourceCompositeRetailReceipt("two cartons of 2 x 1000 g milk"))
        assertEquals(kilogram.shape(), grams.shape())
        val litre = requireNotNull(sourceCompositeRetailReceipt("two cartons of 2 x 1 l juice"))
        val millilitres = requireNotNull(sourceCompositeRetailReceipt("two cartons of 2 x 1000 ml juice"))
        assertEquals(litre.shape(), millilitres.shape())
    }

    @Test
    fun incompleteAmbiguousMalformedOrUnsafeSourcesDoNotCompile() {
        val cases = listOf(
            "missing count" to "2x500ml milk cartons",
            "nested multipliers" to "two cartons of 2 x 3 x 500 ml milk",
            "extra count" to "2 cartons of 2 x 500 ml milk, 3 cartons",
            "numeric product identity" to "two cartons of 2 x 500 ml 2% milk",
            "conditional" to "maybe two cartons of 2 x 500 ml milk",
            "negated" to "not two cartons of 2 x 500 ml milk",
            "correction" to "instead two cartons of 2 x 500 ml milk",
            "other item" to "two cartons of 2 x 500 ml milk and one bag rice",
            "unowned descriptor" to "two cartons of 2 x 500 ml milk with 2% fat",
            "malformed count" to "two-plus cartons of 2 x 500 ml milk",
            "signed count" to "+2 cartons of 2 x 500 ml milk",
            "signed factor" to "two cartons of +2 x 500 ml milk",
            "count range" to "2-3 cartons of 2 x 500 ml milk",
            "fractional count" to "1/2 carton of 2 x 500 ml milk",
            "zero count" to "0 cartons of 2 x 500 ml milk",
            "over-limit count" to "21 cartons of 2 x 500 ml milk",
            "factor one" to "two cartons of 1 x 1000 ml milk",
            "factor out of range" to "two cartons of 100 x 10 ml milk",
            "zero inner size" to "two cartons of 2 x 0 ml milk",
            "negative inner size" to "two cartons of 2 x -500 ml milk",
            "count/container mismatch singular" to "two carton of 2 x 500 ml milk",
            "count/container mismatch plural" to "one cartons of 2 x 500 ml milk",
            "second container" to "two cartons of 2 x 500 ml milk and two bars soap",
            "wrong container agreement" to "three bar of 2 x 100 g soap",
        )
        val failures = cases.mapNotNull { (id, source) ->
            val compiled = runCatching { sourceCompositeRetailReceipt(source) }
                .getOrElse { return@mapNotNull "$id unexpectedly threw ${it::class.simpleName}" }
            if (compiled == null) null else "$id unexpectedly compiled"
        }
        assertTrue("Unsafe or incomplete composite sources compiled: $failures", failures.isEmpty())

        val control = "two cart\u043ens of 2 × 500 ml milk"
        assertNull("Unicode-confusable container spelling is not accepted", sourceCompositeRetailReceipt(control))
        assertNull("Control characters are rejected", sourceCompositeRetailReceipt("two cartons of 2 x 500 ml\u0001 milk"))
    }

    @Test
    fun repeatedFactorCountsWithoutExplicitContainmentAndPostfixDoRemainAmbiguous() {
        val cases = listOf(
            "2 x 500 ml orange juice 2 cartons",
            "2x500ml milk cartons two",
            "2 x500ml milk packs 2",
            "2 cartons 2 x 500 ml milk",
            "2 x 500 ml milk cartons do",
            "milk 2 x 500 ml cartons do",
        )
        cases.forEach { source ->
            assertNull("Ambiguous factor/count wording must not acquire a receipt: $source", sourceCompositeRetailReceipt(source))
        }
    }

    @Test
    fun over512CharacterSourcesAreRejectedWithoutThrowing() {
        val longNoise = "x".repeat(513)
        val longNominal = "two cartons of 2 x 500 ml " + List(100) { "milk" }.joinToString(" ")
        assertTrue(longNoise.length > 512)
        assertTrue(longNominal.length > 512)
        listOf(longNoise, longNominal).forEach { source ->
            val result = runCatching { sourceCompositeRetailReceipt(source) }
            assertTrue("Long source must not throw (length=${source.length})", result.isSuccess)
            assertNull("Long source must be outside the bounded compiler (length=${source.length})", result.getOrNull())
        }
    }

    @Test
    fun requestedCountAndSkuFactorRemainDistinctEvenWhenBothEqualTwo() {
        val source = "2 cartons of 2 x 500 ml milk"
        val receipt = requireNotNull(sourceCompositeRetailReceipt(source))
        assertEquals(0, receipt.requestedCount.startUtf16)
        assertEquals("2", source.substring(receipt.requestedCount.startUtf16, receipt.requestedCount.endUtf16))
        assertEquals(2, receipt.requestedCount.value.intValueExact())
        assertEquals(2, receipt.skuFactor.value.intValueExact())
        assertTrue(receipt.requestedCount.endUtf16 <= receipt.skuFactor.startUtf16)
        assertEquals("carton", receipt.containerKey)
    }

    private fun assertReceipt(case: PositiveCase, receipt: CompositeRetailReceipt) {
        assertEquals(case.source, receipt.source)
        assertAmount(case.source, receipt.requestedCount, case.countAtom, case.count.toBigDecimal())
        assertAmount(case.source, receipt.skuFactor, case.factor.toString(), case.factor.toBigDecimal())
        assertAmount(case.source, receipt.innerMeasure, case.measureAtom, case.measureValue.toBigDecimal())

        val shapeStart = case.source.indexOf(case.shapeText)
        val containerStart = case.source.indexOf(case.containerText)
        val identityStart = case.source.indexOf(case.identity)
        val countStart = if (case.countAtStart) case.source.indexOf(case.countAtom) else case.source.lastIndexOf(case.countAtom)
        val factorStart = shapeStart + case.shapeText.lastIndexOf(case.factor.toString())
        val measureStart = shapeStart + case.shapeText.indexOf(case.measureAtom)
        assertEquals("${case.id}: expected source positions", countStart, receipt.requestedCount.startUtf16)
        assertEquals("${case.id}: expected factor source position", factorStart, receipt.skuFactor.startUtf16)
        assertEquals("${case.id}: expected measure source position", measureStart, receipt.innerMeasure.startUtf16)

        assertEquals(case.shapeText, case.source.substring(receipt.shapeSpan.first, receipt.shapeSpan.last + 1))
        assertEquals(case.containerText, case.source.substring(receipt.container.first, receipt.container.last + 1))
        assertEquals(case.identity, case.source.substring(receipt.identity.first, receipt.identity.last + 1))
        assertEquals(shapeStart, receipt.shapeSpan.first)
        assertEquals(containerStart, receipt.container.first)
        assertEquals(identityStart, receipt.identity.first)
        assertEquals(case.containerKey, receipt.containerKey)
        assertEquals(case.source, receipt.requestedCount.source)
        assertEquals(case.source, receipt.skuFactor.source)
        assertEquals(case.source, receipt.innerMeasure.source)

        val shape = receipt.shape()
        assertEquals(case.factor, shape.factor)
        assertEquals(GroceryPackMeasure.of(case.measureValue.toBigDecimal(), case.measureUnit), shape.inner)
        assertTrue(receipt.literalQuery().startsWith(case.identity, ignoreCase = true))
        assertEquals("CompositeRetailReceipt(<redacted>)", receipt.toString())
        assertFalse(receipt.toString().contains(case.source, ignoreCase = true))
    }

    private fun assertAmount(source: String, actual: com.example.beta.automation.IntentLocalAmount,
                             expectedAtom: String, expectedValue: BigDecimal) {
        assertEquals(source, actual.source)
        assertEquals(expectedAtom, actual.atom)
        assertEquals(expectedAtom, source.substring(actual.startUtf16, actual.endUtf16))
        assertEquals(0, actual.value.compareTo(expectedValue))
        assertTrue(actual.startUtf16 in 0..source.length)
        assertTrue(actual.endUtf16 in actual.startUtf16..source.length)
    }
}
