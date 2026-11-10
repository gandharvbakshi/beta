package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryPackMeasureTest {
    @Test
    fun v1ProjectionPreservesOnlyTheDeclaredPerPackMeasure() {
        val cases = listOf(
            packItem(packValue = null, packUnit = "unspecified") to emptySet(),
            packItem(packValue = "500", packUnit = "ml") to setOf(GroceryPackMeasure.of(BigDecimal("500"), "ml")),
            packItem(packValue = "1", packUnit = "kg") to setOf(GroceryPackMeasure.of(BigDecimal("1000"), "g")),
            packItem(packValue = "6", packUnit = "piece") to setOf(GroceryPackMeasure.of(BigDecimal("6"), "pieces")),
        )

        cases.forEach { (intentItem, expected) ->
            val parsed = intentItem.toParsedItem()
            assertEquals(expected, groceryPackMeasures(parsed.query))
            assertEquals(expected, groceryPackMeasures(parsed.strictMatchPhrase.orEmpty()))
            assertTrue(intentItem.hasExactPackProjection(parsed))
        }
    }

    @Test
    fun physicalTotalWithoutPackSizeDoesNotBecomeAnInferredPackMeasure() {
        val parsed = packItem(
            name = "milk",
            quantity = BigDecimal("1000"),
            quantityUnit = "ml",
            packValue = null,
            packUnit = "unspecified",
            sourceText = "total 1000 ml milk",
        ).toParsedItem()

        assertTrue(groceryPackMeasures(parsed.query).isEmpty())
        assertTrue(groceryPackMeasures(parsed.strictMatchPhrase.orEmpty()).isEmpty())
    }

    @Test
    fun projectionRejectsExtraMissingAndDimensionMismatchedMeasuresInEitherTextBoundary() {
        val expected = packItem(packValue = "500", packUnit = "ml").toParsedItem()

        assertFalse(expected.copy(query = "milk 500 ml 1 l").let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
        assertFalse(expected.copy(strictMatchPhrase = "milk 500 ml 1 l").let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
        assertFalse(expected.copy(query = "milk").let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
        assertFalse(expected.copy(strictMatchPhrase = "milk").let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
        assertFalse(expected.copy(strictMatchPhrase = null).let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
        assertFalse(expected.copy(query = "milk 500 g").let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
        assertFalse(expected.copy(strictMatchPhrase = "milk 500 g").let { item ->
            packItem(packValue = "500", packUnit = "ml").hasExactPackProjection(item)
        })
    }

    @Test
    fun equivalentPhysicalUnitsHaveTheSameCanonicalMeasure() {
        assertEquals(
            GroceryPackMeasure.of(BigDecimal("0.5"), "L"),
            GroceryPackMeasure.of(BigDecimal("500"), "ml"),
        )
    }

    @Test
    fun nutrientAmountsAreNotPhysicalPackMeasures() {
        listOf(
            "Protein Bar 20 g protein per serving",
            "Protein Bar 20 g protein per 100 g",
        ).forEach { text -> assertTrue(text, groceryPackMeasures(text).isEmpty()) }

        assertEquals(
            setOf(GroceryPackMeasure.of(BigDecimal("50"), "g")),
            groceryPackMeasures("Protein Bar 50 g · 20 g protein per serving"),
        )
    }

    @Test
    fun unsupportedNutrientAmountCannotBeProjectedAsPackSize() {
        val unsupportedIntent = packItem(
            name = "Protein Bar 20 g protein",
            packValue = "20",
            packUnit = "g",
        )
        val unsupported = unsupportedIntent.toParsedItem()
        assertFalse(unsupported.strictMatchPhrase.orEmpty(), unsupportedIntent.hasExactPackProjection(unsupported))

        val supported = packItem(
            name = "Protein Bar 20 g protein per serving",
            packValue = "50",
            packUnit = "g",
        )
        // A rendered "per serving 50 g" can mean serving size, not sellable
        // pack size. Retain the hold rather than erasing the role ambiguity.
        assertFalse(supported.hasExactPackProjection(supported.toParsedItem()))
        val separated = supported.toParsedItem().copy(
            query = "Protein Bar 50 g · 20 g protein per serving",
            strictMatchPhrase = "Protein Bar 50 g · 20 g protein per serving",
        )
        assertTrue(supported.hasExactPackProjection(separated))
    }

    private fun packItem(
        name: String = "milk",
        quantity: BigDecimal? = BigDecimal.ONE,
        quantityUnit: String = "pack",
        packValue: String?,
        packUnit: String,
        sourceText: String = "one $name",
    ) = GroceryIntentItem(
        name = name,
        quantity = quantity,
        quantityUnit = quantityUnit,
        packValue = packValue?.let { BigDecimal(it) },
        packUnit = packUnit,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = sourceText,
        needsClarification = false,
    )
}
