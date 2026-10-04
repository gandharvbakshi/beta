package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentGroundingTest {
    private fun item(name: String, source: String, tags: List<String> = emptyList()) = GroceryIntentItem(
        name, null, "unspecified", null, "unspecified", tags, emptyList(), source, false,
    )

    @Test fun modelCannotInventAnUnspokenBrandOrFlavour() {
        assertEquals(listOf("milk"), groceryIntentGroundingConcerns("milk", listOf(item("chocolate milk", "milk"))))
        assertEquals(listOf("milk"), groceryIntentGroundingConcerns("milk", listOf(item("Amul milk", "milk"))))
    }

    @Test fun knownTranslationsAndCategoryPluralsRemainSupported() {
        assertTrue(groceryIntentGroundingConcerns("do doodh aur aloo", listOf(item("milk", "do doodh"), item("potatoes", "aloo"))).isEmpty())
        assertTrue(groceryIntentGroundingConcerns("one yoghurt and chai patti", listOf(item("yogurt", "one yoghurt"), item("tea leaves", "chai patti"))).isEmpty())
    }

    @Test fun explicitDietAndProductFormMustSurviveInProposedEvidence() {
        for (source in listOf("sugar free milk", "no added sugar milk", "low fat milk", "milk tetra pack")) {
            assertEquals(listOf(source), groceryIntentGroundingConcerns(source, listOf(item("milk", source))))
        }
        assertTrue(groceryIntentGroundingConcerns("lean milk", listOf(item("low fat milk", "lean milk", listOf("low_fat")))).isEmpty())
    }

    @Test fun reviewedTetraPackMisspellingsSupportCorrectionButNotPackLoss() {
        for (spelling in listOf("tetta packs", "tetera pack", "tetrapak")) {
            val source = "5 milk $spelling"
            assertTrue(spelling, groceryIntentGroundingConcerns(source,
                listOf(item("milk", source, listOf("tetra_pack")))).isEmpty())
            assertEquals(listOf(source), groceryIntentGroundingConcerns(source, listOf(item("milk", source))))
        }
    }

    @Test fun sharedBrandIsPermittedButGlobalEvidenceIsNotAScopeProof() {
        assertTrue(groceryIntentGroundingConcerns("Amul butter and milk", listOf(item("Amul milk", "milk"))).isEmpty())
        // The combined product review remains mandatory; neighbouring-modifier scope needs
        // semantic evaluation and cannot be established by this lexical evidence check.
    }

    @Test fun certificationAndNutritionPropertiesCannotDisappearInsideValidQuotes() {
        for (source in listOf("organic certified ginger", "high fibre biscuit", "low calorie cola", "gluten free bread")) {
            assertEquals(listOf(source), groceryIntentGroundingConcerns(source, listOf(item(source.substringAfterLast(' '), source))))
        }
    }

    @Test fun unspokenDietAndPreparedFormCannotBeInferredFromTheCategory() {
        assertEquals(listOf("toned milk"), groceryIntentGroundingConcerns("toned milk", listOf(item("toned milk", "toned milk", listOf("low_fat")))))
        assertEquals(listOf("cocoa beverage"), groceryIntentGroundingConcerns("cocoa beverage", listOf(item("cocoa beverage", "cocoa beverage", listOf("ready_to_drink")))))
    }

    @Test fun requiredTagsCannotSmuggleAnUnspokenFlavourOrPackageIntoTheQuery() {
        for (tag in listOf("chocolate", "family_pack", "Amul")) {
            assertEquals(listOf("milk"), groceryIntentGroundingConcerns("milk", listOf(item("milk", "milk", listOf(tag)))))
        }
        assertTrue(groceryIntentGroundingConcerns("sugar-free biscuits", listOf(item("biscuits", "sugar-free biscuits", listOf("sugar_free")))).isEmpty())
    }

    @Test fun unrelatedItemCannotLendDietOrProductIdentity() {
        val instruction = "one milk, one sugar-free chocolate biscuit"
        assertEquals(listOf("one milk"), groceryIntentGroundingConcerns(instruction,
            listOf(item("chocolate milk", "one milk"))))
        assertEquals(listOf("one milk"), groceryIntentGroundingConcerns(instruction,
            listOf(item("milk", "one milk", listOf("sugar_free")))))
    }

    @Test fun preparedIngredientDoesNotProveReadyToEatOrHighProtein() {
        val batter = "one ready-made dosa batter"
        assertTrue(groceryIntentGroundingConcerns(batter, listOf(item("ready-made dosa batter", batter))).isEmpty())
        assertEquals(listOf(batter), groceryIntentGroundingConcerns(batter,
            listOf(item("dosa batter", batter, listOf("ready_to_eat")))))
        val protein = "two protein bars with 11 grams protein"
        assertEquals(listOf(protein), groceryIntentGroundingConcerns(protein,
            listOf(item("protein bars 11 grams protein", protein, listOf("high_protein")))))
    }

    @Test fun preparedIngredientMatchingKeepsItsFormAndRejectsDrySubstitution() {
        val item = com.example.beta.automation.ParsedItem(rawText = "ready made dosa batter",
            query = "ready made dosa batter", quantity = com.example.beta.automation.Quantity.Count(1),
            strictMatchPhrase = "ready made dosa batter")
        assertTrue(swiggyMatchesProductIdentity(item, "Fresh Dosa Batter"))
        assertTrue(swiggyMatchesProductIdentity(item, "Ready to Cook Dosa Batter"))
        assertEquals(false, swiggyMatchesProductIdentity(item, "Instant Dosa Batter Mix Powder"))
    }

    @Test fun knownFoodPreparationAndCommonHindiTranslationsRemainSupported() {
        val food = "one ready-made butter popcorn"
        assertTrue(groceryIntentGroundingConcerns(food,
            listOf(item("butter popcorn", food, listOf("ready_to_eat")))).isEmpty())
        for ((source, target) in listOf("nimbu" to "lemon", "kele" to "bananas",
            "tamatar" to "tomatoes", "nariyal paani" to "coconut water", "sabun" to "soap")) {
            assertTrue(groceryIntentGroundingConcerns(source, listOf(item(target, source))).isEmpty())
        }
    }

    @Test fun correctionAndOverlappingQuoteNeedAcknowledgementNotSilentAttributeLoss() {
        val corrected = "salted nuts, no, keep the unsalted nuts"
        val result = groceryIntentGrounding(corrected, listOf(item("unsalted nuts", corrected)))
        assertTrue(result.rejected.isEmpty())
        val cancelled = "unsalted butter, no, keep plain butter"
        val needsReview = groceryIntentGrounding(cancelled, listOf(item("plain butter", cancelled)))
        assertTrue(needsReview.rejected.isEmpty())
        assertTrue(needsReview.review.single().startsWith(cancelled))
        assertTrue(needsReview.review.single().contains("mention unsalted"))
        val group = "organic paneer and milk"
        val grouped = groceryIntentGrounding(group,
            listOf(item("organic paneer", "organic paneer"), item("milk", group)))
        assertTrue(grouped.rejected.isEmpty())
        assertTrue(grouped.review.single().startsWith(group))
        assertTrue(grouped.review.single().contains("mention organic"))
        assertEquals(listOf("organic milk"), groceryIntentGroundingConcerns("organic milk",
            listOf(item("milk", "organic milk"))))
    }

    @Test fun preparationCannotDisappearAndOrdinaryNegationIsNotCorrection() {
        for ((source, name) in listOf("ready-made popcorn" to "popcorn",
            "ready to cook paneer tikka" to "paneer tikka", "no-added-sugar yogurt" to "yogurt",
            "sugar-free nahi wala yogurt" to "yogurt")) {
            assertEquals(listOf(source), groceryIntentGroundingConcerns(source, listOf(item(name, source))))
        }
        assertEquals(listOf("milk"), groceryIntentGroundingConcerns("Buy chocolate. Also get milk",
            listOf(item("chocolate milk", "milk"))))
        assertTrue(groceryIntentGroundingConcerns("Amul butter and milk", listOf(item("Amul milk", "milk"))).isEmpty())
        val batter = "ready-made dosa batter"
        assertTrue(groceryIntentGroundingConcerns(batter, listOf(item("dosa batter", batter))).isEmpty())
    }

    @Test fun explicitSharedHeaderSurvivesCommasButRequiresCombinedAcknowledgement() {
        val instruction = "Brand fruit yogurt cups: mango one, blueberry one, strawberry one."
        val result = groceryIntentGrounding(instruction, listOf(
            item("Brand mango fruit yogurt cups", "mango one"),
            item("Brand blueberry fruit yogurt cups", "blueberry one"),
            item("Brand strawberry fruit yogurt cups", "strawberry one")))
        assertTrue(result.rejected.isEmpty())
        assertEquals(2, result.review.size)
        assertTrue(result.review.all { it.contains("Shared wording: Brand fruit yogurt cups") })
        for (instructionWithoutHeader in listOf("Brand mango yogurt one, blueberry one",
            "Brand yogurt: mango one. blueberry one", "Brand yogurt: mango one; blueberry one")) {
            assertEquals(listOf("blueberry one"), groceryIntentGroundingConcerns(instructionWithoutHeader,
                listOf(item("Brand blueberry yogurt", "blueberry one"))))
        }
        val diet = "sugar-free yogurt: mango one, blueberry one"
        assertEquals(listOf("blueberry one"), groceryIntentGroundingConcerns(diet,
            listOf(item("blueberry yogurt", "blueberry one", listOf("sugar_free")))))
    }

    @Test fun commonIndianCategoryTranslationsDoNotInventProductForms() {
        for ((source, target) in listOf("kothmir" to "coriander leaves", "dhaniya powder" to "coriander powder",
            "jeera" to "cumin seeds", "plain murmura" to "plain puffed rice",
            "laal chawal" to "red rice", "kachche aam" to "raw mangoes")) {
            assertTrue(groceryIntentGroundingConcerns(source, listOf(item(target, source))).isEmpty())
        }
        assertEquals(listOf("jeera"), groceryIntentGroundingConcerns("jeera", listOf(item("cumin powder", "jeera"))))
        assertEquals(listOf("dhaniya"), groceryIntentGroundingConcerns("dhaniya", listOf(item("coriander powder", "dhaniya"))))
        assertEquals(listOf("aam"), groceryIntentGroundingConcerns("aam", listOf(item("raw mango", "aam"))))
    }

    @Test fun aCoveredSharedHeaderCannotHideADroppedDietRequirement() {
        val instruction = "Sugar-free biscuits: Marie, digestive"
        val result = groceryIntentGrounding(instruction, listOf(
            item("sugar-free Marie biscuits", "Sugar-free biscuits: Marie", listOf("sugar_free")),
            item("digestive", "digestive")))
        assertTrue(result.rejected.isEmpty())
        assertTrue(result.review.single().contains("mention sugar-free"))
        assertTrue(groceryIntentGrounding("Amul: butter, cheese", listOf(
            item("butter", "butter"), item("cheese", "cheese"))).review.isEmpty())
        assertEquals(listOf("digestive"), groceryIntentGroundingConcerns(instruction,
            listOf(item("digestive", "digestive", listOf("sugar_free")))))
    }

    @Test fun clockAndRepeatedQuotesCannotProveSharedIdentity() {
        assertEquals(listOf("milk"), groceryIntentGroundingConcerns("deliver by 5:30 bread, milk",
            listOf(item("5 milk", "milk"))))
        assertEquals(listOf("milk"), groceryIntentGroundingConcerns("Amul: milk; Nandini: milk",
            listOf(item("Amul milk", "milk"))))
    }
}
