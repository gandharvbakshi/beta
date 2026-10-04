package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class GroceryIntentPackEvidenceTest {
    private fun item(
        source: String,
        quantity: String? = "1",
        quantityUnit: String = "pack",
        packValue: String? = null,
        packUnit: String = "unspecified",
    ) = GroceryIntentItem(
        name = "rice",
        quantity = quantity?.let(::BigDecimal),
        quantityUnit = quantityUnit,
        packValue = packValue?.let(::BigDecimal),
        packUnit = packUnit,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        needsClarification = false,
    )

    @Test fun matchingContainerSizeIsAcceptedAndMissingOrWrongPackSizeConflicts() {
        val source = "a 5 kg bag of rice"
        assertEquals(false, groceryIntentPackEvidenceConflict(item(source, packValue = "5", packUnit = "kg")))
        assertEquals(true, groceryIntentPackEvidenceConflict(item(source, quantity = "5", quantityUnit = "kg")))
        assertEquals(true, groceryIntentPackEvidenceConflict(item(source, packValue = "2", packUnit = "kg")))
    }

    @Test fun equivalentPhysicalUnitsAndBothContainerWordOrdersAreAccepted() {
        assertEquals(false, groceryIntentPackEvidenceConflict(
            item("a 1 kilogram bag of rice", packValue = "1000", packUnit = "g"),
        ))
        assertEquals(false, groceryIntentPackEvidenceConflict(
            item("two 750 ml bottles of juice", quantity = "2", packValue = "0.75", packUnit = "l"),
        ))
        assertEquals(false, groceryIntentPackEvidenceConflict(
            item("two packs of 250 g cashews", quantity = "2", packValue = "0.25", packUnit = "kg"),
        ))
        assertEquals(false, groceryIntentPackEvidenceConflict(
            item("one 1-litre bottle of oil", packValue = "1000", packUnit = "ml"),
        ))
    }

    @Test fun bareWeightsAndNutritionAmountsAreNotMistakenForContainerSizes() {
        assertEquals(false, groceryIntentPackEvidenceConflict(
            item("5 kg of rice", quantity = "5", quantityUnit = "kg"),
        ))
        assertEquals(false, groceryIntentPackEvidenceConflict(
            item("a tub of yogurt with 10 g protein", packValue = null),
        ))
    }

    @Test fun conflictingExplicitContainerSizesFailClosed() {
        assertEquals(true, groceryIntentPackEvidenceConflict(
            item("a 1 kg bag and a 500 g packet of rice", packValue = "1", packUnit = "kg"),
        ))
    }

    @Test fun singularContainersAndUnspecifiedCountKeepExplicitPackSize() {
        for (container in listOf("box", "pouch")) {
            assertEquals(true, groceryIntentPackEvidenceConflict(item("a 250 g $container of rice")))
            assertEquals(false, groceryIntentPackEvidenceConflict(item("250 g $container of rice",
                quantity = null, quantityUnit = "unspecified", packValue = "250", packUnit = "g")))
        }
    }

    @Test fun groundingIntegrationRejectsLostContainerButAcceptsExactPack() {
        val source = "a 5 kg bag of rice"
        assertEquals(listOf(source), groceryIntentGroundingConcerns(source,
            listOf(item(source, quantity = "5", quantityUnit = "kg"))))
        assertEquals(emptyList<String>(), groceryIntentGroundingConcerns(source,
            listOf(item(source, packValue = "5", packUnit = "kg"))))
    }

    @Test fun measuredTotalsRequireExactMultiplesOfTheExplicitPack() {
        assertEquals(false, groceryIntentPackEvidenceConflict(item("10 kg rice in 5 kg bags",
            quantity = "10", quantityUnit = "kg", packValue = "5", packUnit = "kg")))
        assertEquals(true, groceryIntentPackEvidenceConflict(item("7 kg rice in 5 kg bags",
            quantity = "7", quantityUnit = "kg", packValue = "5", packUnit = "kg")))
        assertEquals(true, groceryIntentPackEvidenceConflict(item("10 kg rice in 5 kg bags",
            quantity = "10", quantityUnit = "l", packValue = "5", packUnit = "kg")))
    }

    @Test fun commonIndianNumberFormatsNeverBecomePartialSizes() {
        for ((source, value, unit) in listOf(
            Triple("1,000 ml bottle", "1", "l"), Triple("1/2 kg pack", "0.5", "kg"),
            Triple("½ kg pack", "500", "g"), Triple("1 1/2 kg bag", "1.5", "kg"),
            Triple("1 ltr bottle", "1000", "ml"), Triple("5 kgs bag", "5000", "g"),
        )) {
            assertEquals(source, false, groceryIntentPackEvidenceConflict(item(source,
                packValue = value, packUnit = unit)))
            assertEquals(source, true, groceryIntentPackEvidenceConflict(item(source,
                packValue = "2", packUnit = "kg")))
        }
        assertEquals(true, groceryIntentPackEvidenceConflict(item("1/0 kg pack", packValue = "1", packUnit = "kg")))
    }

    @Test fun adjacentProductsInContextualQuotesMakeOtherwiseExactPackSizesAmbiguous() {
        val sauceContext = "two 500 g jars of pasta sauce, one 250 ml carton of cream. Make the sauce plain, not spicy"
        assertEquals(true, groceryIntentPackEvidenceConflict(item(
            sauceContext, quantity = "2", quantityUnit = "count", packValue = "500", packUnit = "g",
        )))
        assertEquals(false, groceryIntentPackEvidenceConflict(item(
            "two 500 g jars of pasta sauce", quantity = "2", quantityUnit = "count", packValue = "500", packUnit = "g",
        )))

        val juiceContext = "one 1 litre carton of orange juice, a 250 g jar of strawberry jam. The juice should be pulp-free"
        assertEquals(true, groceryIntentPackEvidenceConflict(item(
            juiceContext, quantity = "1", quantityUnit = "count", packValue = "1", packUnit = "l",
        )))
        assertEquals(false, groceryIntentPackEvidenceConflict(item(
            "one 1 litre carton of orange juice", quantity = "1", quantityUnit = "count", packValue = "1", packUnit = "l",
        )))
        assertEquals(false, groceryIntentPackEvidenceConflict(item(
            "one 1 litre carton of orange juice", quantity = "1", quantityUnit = "count", packValue = "1000", packUnit = "ml",
        )))
    }
}
