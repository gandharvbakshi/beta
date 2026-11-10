package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class SwiggyProductFormTest {
    @Test fun typedPackContentsKeepTheNounAndCountInTheStrictCataloguePhrase() {
        val item = typedContentsItem(
            name = "BrandA cough drops",
            form = "lozenges",
            source = "one pack of BrandA cough drops with 24 lozenges",
        )
        assertTrue(item.strictMatchPhrase.orEmpty().contains("lozenges"))
        assertTrue(item.strictMatchPhrase.orEmpty().contains("24 pieces"))
        assertEquals(Quantity.Count(1), item.quantity)
        assertTrue(swiggyMatchesProductIdentity(item, "BrandA Cough Drops 24 lozenges"))
        assertTrue(swiggyMatchesProductIdentity(item, "BrandA Cough Drops lozenge 24 pieces"))
        listOf(
            "BrandA Cough Drops 24 tablets",
            "BrandA Cough Drops 24 capsules",
            "BrandA Cough Drops 24 pieces",
            "BrandA Cough Drops lozenges",
            "BrandA Cough Drops 25 lozenges",
            "BrandA Cough Drops 24 lozenges pack of 2",
        ).forEach { label -> assertFalse(label, swiggyMatchesProductIdentity(item, label)) }
    }

    @Test fun typedPackContentsPreserveBoundedSingularPluralFormsWithoutCrossTypeSubstitution() {
        val forms = listOf(
            "lozenge" to "lozenges",
            "tablet" to "tablets",
            "capsule" to "capsules",
            "sachet" to "sachets",
            "pellet" to "pellets",
            "egg" to "eggs",
            "battery" to "batteries",
        )
        forms.forEach { (singular, plural) ->
            val item = typedContentsItem("BrandA item", plural, "one BrandA item pack of 24 $plural")
            assertEquals("$plural -> purchase quantity", Quantity.Count(1), item.quantity)
            assertTrue("$plural -> strict type", item.strictMatchPhrase.orEmpty().contains(plural))
            assertTrue("$plural -> strict pack count", item.strictMatchPhrase.orEmpty().contains("24 pieces"))
            assertTrue("$plural catalogue label", swiggyMatchesProductIdentity(item, "BrandA item 24 $plural"))
            assertTrue("$singular measured label", swiggyMatchesProductIdentity(item, "BrandA item 24 $singular"))
            assertTrue("$singular catalogue label",
                swiggyMatchesProductIdentity(item, "BrandA item $singular 24 pieces"))
            val otherType = forms.first { it.second != plural }.second
            assertFalse("$plural cannot become $otherType",
                swiggyMatchesProductIdentity(item, "BrandA item 24 $otherType"))
            assertFalse("$plural cannot become generic pieces",
                swiggyMatchesProductIdentity(item, "BrandA item 24 pieces"))
            assertFalse("$plural cannot lose its contents count",
                swiggyMatchesProductIdentity(item, "BrandA item $plural"))
            assertFalse("$plural cannot change the contents count",
                swiggyMatchesProductIdentity(item, "BrandA item 25 $plural"))
            assertFalse("$plural cannot infer a multipack",
                swiggyMatchesProductIdentity(item, "BrandA item 24 $plural pack of 2"))
        }
    }

    @Test fun packContentsAreAnExactCountNotAnInferredPackSize() {
        val item = ParsedItem(rawText = "one pack of 25 lozenges", query = "Vicks cough drops 25 pieces", strictMatchPhrase = "Vicks cough drops 25 pieces")
        assertTrue(swiggyMatchesProductIdentity(item, "Vicks Cough Drops 25 lozenges"))
        assertFalse(swiggyMatchesProductIdentity(item, "Vicks Cough Drops 20 lozenges"))
        assertFalse(swiggyMatchesProductIdentity(item, "Vicks Cough Drops"))
        assertFalse(swiggyMatchesProductIdentity(item, "Vicks Cough Drops 25 lozenges pack of 2"))
    }

    @Test fun commonProducePluralsMatchInEitherDirectionWithoutRelaxingStrictVariants() {
        val pairs = listOf(
            "apple" to "apples", "orange" to "oranges", "carrot" to "carrots", "cucumber" to "cucumbers",
            "lemon" to "lemons", "pear" to "pears", "mango" to "mangoes", "guava" to "guavas",
            "kiwi" to "kiwis", "plum" to "plums", "peach" to "peaches", "grape" to "grapes",
            "cauliflower" to "cauliflowers", "cabbage" to "cabbages", "berry" to "berries",
            "strawberry" to "strawberries", "blueberry" to "blueberries",
        )
        for ((singular, plural) in pairs) {
            val singularQuery = ParsedItem(rawText = singular, query = singular, strictMatchPhrase = singular)
            val pluralQuery = ParsedItem(rawText = plural, query = plural, strictMatchPhrase = plural)
            assertTrue("$singular -> $plural", swiggyMatchesProductIdentity(singularQuery, plural))
            assertTrue("$plural -> $singular", swiggyMatchesProductIdentity(pluralQuery, singular))
        }

        val greenApple = ParsedItem(rawText = "green apple", query = "green apple", strictMatchPhrase = "green apple")
        assertFalse(swiggyMatchesProductIdentity(greenApple, "red apples"))
    }

    @Test fun stapleQueriesRejectPreparedDishesButExplicitDishQueriesAndReorderedLabelsMatch() {
        val pairs = listOf(
            Triple("doodh", "Haldiram Doodh Peda", "doodh peda"),
            Triple("aloo", "Aloo Bhujia", "aloo bhujia"),
            Triple("aam", "Aam Papad", "aam papad"),
            Triple("aam", "Aam Achaar", "aam achaar"),
            Triple("dahi", "Dahi Vada", "dahi vada"),
            Triple("gajar", "Gajar Halwa", "gajar halwa"),
            Triple("pyaaz", "Pyaz Kachori", "pyaaz kachori"),
        )
        for ((staple, dishLabel, explicitDish) in pairs) {
            val stapleQuery = ParsedItem(rawText = staple, query = staple, strictMatchPhrase = staple)
            val dishQuery = ParsedItem(rawText = explicitDish, query = explicitDish, strictMatchPhrase = explicitDish)
            assertFalse("$staple must not match $dishLabel", swiggyMatchesProductIdentity(stapleQuery, dishLabel))
            assertTrue("$explicitDish should match $dishLabel", swiggyMatchesProductIdentity(dishQuery, dishLabel))
        }

        val yogurt = ParsedItem(rawText = "Mixed Berries Greek Yogurt", query = "Mixed Berries Greek Yogurt",
            strictMatchPhrase = "Mixed Berries Greek Yogurt")
        assertTrue(swiggyMatchesProductIdentity(yogurt, "Greek Yogurt Mixed Berries"))
        val pearsSoap = ParsedItem(rawText = "Pears Soap", query = "Pears Soap", strictMatchPhrase = "Pears Soap")
        assertTrue(swiggyMatchesProductIdentity(pearsSoap, "Soap Pears"))
    }

    @Test fun tetraSpellingIsEquivalentButPouchAndUnknownFormatAreNot() {
        for (form in listOf("tetra pack", "tetra packs", "tetrapak", "tetrapack", "tetta packs")) {
            val item = ParsedItem(rawText = "milk $form", query = "milk $form")
            assertTrue(form, swiggyMatchesProductIdentity(item, "Amul Milk Tetra Pack 500 ml"))
            assertFalse(form, swiggyMatchesProductIdentity(item, "Amul Milk Pouch 500 ml"))
            assertFalse(form, swiggyMatchesProductIdentity(item, "Amul UHT Milk 500 ml"))
            assertFalse(form, swiggyMatchesProductIdentity(item, "Milk Tetra Pack Pouch 500 ml"))
            assertFalse(form, swiggyMatchesProductIdentity(item, "Milk Tetra Pack Bottle 500 ml"))
        }
    }

    @Test fun readyMadePopcornRequiresReadyToEatEvidence() {
        val item = ParsedItem(rawText = "popcorn butter ready made", query = "popcorn butter ready made")
        assertTrue(swiggyMatchesProductIdentity(item, "Butter Popcorn Ready to Eat 50 g"))
        for (label in listOf("Butter Popcorn", "Butter Popcorn Ready to Cook", "Butter Popcorn Microwave Ready to Eat", "Raw Butter Popcorn Kernels Ready to Eat")) {
            assertFalse(label, swiggyMatchesProductIdentity(item, label))
        }
    }

    @Test fun exactMilkPackAndFormatSurviveStrictMatch() {
        val item = ParsedItem(rawText = "milk tetra packs", query = "milk tetra pack", strictMatchPhrase = "500 ml milk tetra pack")
        assertTrue(swiggyMatchesProductIdentity(item, "Amul Milk Tetra Pack 500 ml"))
        assertFalse(swiggyMatchesProductIdentity(item, "Amul Milk Tetra Pack 1 L"))
        assertFalse(swiggyMatchesProductIdentity(item, "Amul Milk Tetra Pack 2 x 500 ml"))
        assertFalse(swiggyMatchesProductIdentity(item, "Amul Milk Pouch 500 ml"))
    }

    @Test fun liveTetraShorthandIsMilkButNeverAnInventedSizeOrProduct() {
        val item = ParsedItem(rawText = "milk tetra pack", query = "milk tetra pack")
        assertTrue(swiggyMatchesProductIdentity(item, "Amul Taaza Tetra · 200 ml"))
        assertTrue(swiggyMatchesProductIdentity(item, "Amul Calci+ Milk Tetra · 1 ltr"))
        assertFalse(swiggyMatchesProductIdentity(item.copy(strictMatchPhrase = "500 ml milk tetra pack"), "Amul Taaza Tetra · 200 ml"))
        assertTrue(swiggyMatchesProductIdentity(item.copy(strictMatchPhrase = "200 ml milk tetra pack"), "Amul Taaza Tetra · 200 ml"))
        for (label in listOf("Amul Taaza", "Tetra Juice 200 ml", "Taaza Tea Tetra 200 ml", "Amul Taaza Tetra Ice Cream 200 ml", "Amul Taaza Tetra Pouch 200 ml")) {
            assertFalse(label, swiggyMatchesProductIdentity(item, label))
        }
    }
}

private fun typedContentsItem(name: String, form: String, source: String): ParsedItem =
    GroceryIntentItem(
        name = name,
        quantity = BigDecimal.ONE,
        quantityUnit = "pack",
        packValue = BigDecimal("24"),
        packUnit = "piece",
        required = listOf(form),
        excluded = emptyList(),
        sourceText = source,
        needsClarification = false,
    ).toParsedItem()
