package com.example.beta

import com.example.beta.automation.Preference
import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyVoiceOrderCoordinatorTest {
    @Test
    fun questionCounterCountsOnlyItemsThatNeedAChoice() {
        val needsQuestion = listOf(false, true, false, true)

        assertEquals(1 to 2, swiggyQuestionPosition(needsQuestion, 1))
        assertEquals(2 to 2, swiggyQuestionPosition(needsQuestion, 3))
        assertEquals(null, swiggyQuestionPosition(needsQuestion, 0))
        assertEquals(null, swiggyQuestionPosition(needsQuestion, 4))
    }

    @Test
    fun providerAddedCardAppearsOnlyForAnExplicitFreeGiftOrSample() {
        assertEquals(
            "Swiggy also included a free gift at no charge.",
            swiggyProviderAddedMessage("Swiggy also included a free gift at no charge."),
        )
        assertEquals(
            "Swiggy added a free sample.",
            swiggyProviderAddedMessage("Swiggy added a free sample."),
        )
        assertEquals(null, swiggyProviderAddedMessage("Applied"))
        assertEquals(null, swiggyProviderAddedMessage(null))
    }

    @Test
    fun unavailableItemCountIncludesChosenAndUnprocessedItemsButNotEarlierSkips() {
        assertEquals(7, swiggyContinuableItemCount(selectedCount = 0, itemCount = 8, unavailableIndex = 0))
        assertEquals(7, swiggyContinuableItemCount(selectedCount = 3, itemCount = 8, unavailableIndex = 3))
        assertEquals(6, swiggyContinuableItemCount(selectedCount = 3, itemCount = 8, unavailableIndex = 4))
        assertEquals(0, swiggyContinuableItemCount(selectedCount = 0, itemCount = 1, unavailableIndex = 0))
    }

    @Test
    fun rememberedAddressIsReusedOnlyWhenStillAvailable() {
        val home = SwiggyMcpClient.SwiggyAddress("home", "Home", "Home")
        val work = SwiggyMcpClient.SwiggyAddress("work", "Work", "Work")

        assertEquals(work, rememberedSwiggyAddress(listOf(home, work), "work"))
        assertEquals(null, rememberedSwiggyAddress(listOf(home), "work"))
        assertEquals(null, rememberedSwiggyAddress(listOf(home, work), null))
    }

    @Test
    fun rememberedAddressExpiresAndAddressChoiceLeadsWithConciseLabel() {
        assertTrue(isRememberedSwiggyAddressFresh(1_000L, 5_000L, 10_000L))
        assertFalse(isRememberedSwiggyAddressFresh(1_000L, 20_000L, 10_000L))
        assertFalse(isRememberedSwiggyAddressFresh(0L, 1_000L, 10_000L))

        val address = SwiggyMcpClient.SwiggyAddress(
            id = "home",
            label = "10 Test Road, Bengaluru",
            normalizedLabel = "10 Test Road, Bengaluru",
            shortLabel = "Home — Bengaluru",
        )
        assertEquals(
            "Home — Bengaluru",
            swiggyAddressChoiceLabel(address),
        )
        assertEquals(
            listOf("Home — Bengaluru — saved address 1", "Home — Bengaluru — saved address 2"),
            swiggyAddressChoiceLabels(listOf(address, address.copy(id = "home-2"))),
        )
    }

    @Test
    fun preparesTwelveItemPromptWithMixedCategoriesAndUnits() {
        val items = prepareSwiggyMcpItems(promptSlice(12), lookup = { null })

        assertEquals(12, items.size)
        assertEquals(
            listOf(
                "diapers",
                "shampoo",
                "cleaning spray",
                "frozen peas",
                "medicine",
                "grapes",
                "24 pack paper towels",
                "detergent",
                "floor cleaner",
                "rice",
                "toothpaste",
                "apples",
            ),
            items.map { it.query }
        )
        assertEquals(listOf(
            "2 diapers",
            "shampoo",
            "cleaning spray",
            "frozen peas",
            "medicine",
            "grapes",
            "24 pack paper towels",
            "500 g detergent",
            "750 ml floor cleaner",
            "1000 g rice",
            "3 toothpaste",
            "6 apples",
        ), items.map { it.backendInputText() })
    }

    @Test
    fun preparesTwentyItemPromptWithDiverseHouseholdCategories() {
        val items = prepareSwiggyMcpItems(promptSlice(20), lookup = { null })

        assertEquals(20, items.size)
        assertEquals("milk", items[14].query)
        assertEquals("butter", items[15].query)
        assertEquals("pain relief balm", items[18].query)
        assertEquals(Quantity.Weight(500), items[7].quantity)
        assertEquals(Quantity.Volume(750), items[8].quantity)
        assertEquals(Quantity.Weight(1000), items[9].quantity)
        assertEquals(Quantity.Count(3), items[10].quantity)
        assertEquals(Quantity.Count(2), items[12].quantity)
        assertEquals(Quantity.Count(4), items[13].quantity)
        assertEquals(Quantity.Count(12), items[16].quantity)
    }

    @Test
    fun preparesTwentyFiveItemPromptAtTheCurrentSwiggyItemLimit() {
        val items = prepareSwiggyMcpItems(promptSlice(25), lookup = { null })

        assertEquals(25, items.size)
        assertEquals("maggi", items.last().query)
        assertEquals(Quantity.Count(2), items[0].quantity)
        assertEquals(Quantity.Weight(500), items[7].quantity)
        assertEquals(Quantity.Volume(750), items[8].quantity)
        assertEquals(Quantity.Weight(1000), items[9].quantity)
        assertEquals(null, swiggyMcpItemValidationMessage(promptSlice(25), items))
    }

    @Test
    fun deduplicatesRepeatedCategoryMentionsInFirstSeenOrder() {
        val items = prepareSwiggyMcpItems(
            instruction = "doodh, milk, cleaning spray, cleaning spray, frozen peas",
            lookup = { null },
        )

        assertEquals(listOf("milk", "cleaning spray", "frozen peas"), items.map { it.query })
        assertEquals(3, items.size)
    }

    @Test
    fun appliesMinimalPreferenceShorthandAndKeepsExplicitOverrides() {
        val items = prepareSwiggyMcpItems(
            instruction = "doodh without lactose",
            lookup = { query ->
                if (query == "milk") {
                    Preference(
                        token = "milk",
                        preferredPhrase = "organic whole milk",
                        avoidPhrases = listOf("regular"),
                        confidence = 1.0f,
                    )
                } else {
                    null
                }
            },
        )

        assertEquals(1, items.size)
        assertEquals("organic whole milk", items.single().query)
        assertEquals(listOf("lactose", "regular"), items.single().avoidPhrases)
        assertEquals("organic whole milk", items.single().strictMatchPhrase)
    }

    @Test
    fun noCandidateMessageCallsOutStrictMatchAtSelectedAddress() {
        val item = com.example.beta.automation.ParsedItem(
            rawText = "organic whole milk",
            query = "milk",
            strictMatchPhrase = "organic whole milk",
        )
        val address = SwiggyMcpClient.SwiggyAddress(
            id = "home",
            label = "10 Test Road, Bengaluru",
            normalizedLabel = "10 Test Road, Bengaluru",
            shortLabel = "Home",
        )

        assertEquals(
            "I could not find your preferred exact product, organic whole milk, at your selected address (Home). Beta did not substitute it. Nothing was added.",
            swiggyNoCandidateMessage(item, address),
        )
    }

    @Test
    fun noCandidateMessageKeepsGenericBehaviorWithoutStrictMatchPhrase() {
        val item = com.example.beta.automation.ParsedItem(
            rawText = "milk",
            query = "milk",
        )
        val address = SwiggyMcpClient.SwiggyAddress(
            id = "home",
            label = "10 Test Road, Bengaluru",
            normalizedLabel = "10 Test Road, Bengaluru",
            shortLabel = "Home",
        )

        assertEquals(
            "I could not find milk on Swiggy Instamart. Nothing was added.",
            swiggyNoCandidateMessage(item, address),
        )
    }

    @Test
    fun rejectsMissingDuplicateAndMismatchedPlanChanges() {
        val selected = listOf(
            SwiggyMcpClient.RequestedItem("milk", 2, "Whole milk"),
            SwiggyMcpClient.RequestedItem("grapes", 1, "Fragile grapes"),
        )
        val safePlan = SwiggyMcpClient.CartPlan(
            changes = listOf(
                SwiggyMcpClient.CartPlanChange(
                    spinId = "milk",
                    kind = "change",
                    displayName = "Whole milk",
                    fromQuantity = 0,
                    toQuantity = 2,
                ),
                SwiggyMcpClient.CartPlanChange(
                    spinId = "grapes",
                    kind = "increase",
                    displayName = "Fragile grapes",
                    fromQuantity = 0,
                    toQuantity = 1,
                ),
            ),
            confirmationToken = "signed-plan",
            cartMutationEnabled = true,
        )
        val missingSpinIdPlan = safePlan.copy(
            changes = listOf(
                safePlan.changes.first().copy(spinId = null),
                safePlan.changes.last(),
            ),
        )
        val duplicateSpinIdPlan = safePlan.copy(
            changes = listOf(
                safePlan.changes.first(),
                safePlan.changes.first().copy(fromQuantity = 2, toQuantity = 2),
            ),
        )
        val mismatchedQuantityPlan = safePlan.copy(
            changes = listOf(
                safePlan.changes.first().copy(toQuantity = 3),
                safePlan.changes.last(),
            ),
        )

        assertTrue(isSafeSwiggyCartPlan(safePlan, selected))
        assertFalse(isSafeSwiggyCartPlan(missingSpinIdPlan, selected))
        assertFalse(isSafeSwiggyCartPlan(duplicateSpinIdPlan, selected))
        assertFalse(isSafeSwiggyCartPlan(mismatchedQuantityPlan, selected))
    }

    @Test
    fun recommendationBatchMustMatchEveryQueryInInputOrder() {
        val milk = SwiggyMcpClient.Recommendations(emptyList(), query = "milk")
        val bread = SwiggyMcpClient.Recommendations(emptyList(), query = "bread")

        assertEquals(true, areSwiggyRecommendationsOrdered(listOf(milk, bread), listOf("milk", "bread")))
        assertEquals(false, areSwiggyRecommendationsOrdered(listOf(bread, milk), listOf("milk", "bread")))
        assertEquals(false, areSwiggyRecommendationsOrdered(listOf(milk), listOf("milk", "bread")))
    }

    @Test
    fun countQueriesSearchForTheRequestedCountAndDoNotMultiplyMatchingPacks() {
        val sixEggs = com.example.beta.automation.ParsedItem(
            rawText = "6 eggs",
            query = "eggs",
            quantity = Quantity.Count(6),
        )
        val sixPack = SwiggyMcpClient.RecommendationCandidate(
            spinId = "eggs-6",
            label = "Farm Fresh Eggs · 6 Pieces",
        )
        val thirtyPack = SwiggyMcpClient.RecommendationCandidate(
            spinId = "eggs-30",
            label = "Farm Fresh Eggs · 30 Pieces",
        )
        val twelveEggPack = SwiggyMcpClient.RecommendationCandidate(
            spinId = "eggs-12",
            label = "Farm Fresh White Eggs · 12 Eggs · 1 Pack",
        )

        assertEquals("6 eggs", swiggyRecommendationQuery(sixEggs))
        assertTrue(isSwiggyCandidateCountCompatible(sixEggs, sixPack))
        assertFalse(isSwiggyCandidateCountCompatible(sixEggs, thirtyPack))
        assertFalse(isSwiggyCandidateCountCompatible(sixEggs, twelveEggPack))
        assertEquals(1, swiggyRequestedCartQuantity(sixEggs, sixPack))
    }

    @Test
    fun unpackedCountRequestsStillUseTheRequestedCartQuantity() {
        val toothpaste = com.example.beta.automation.ParsedItem(
            rawText = "3 toothpaste",
            query = "toothpaste",
            quantity = Quantity.Count(3),
        )
        val tube = SwiggyMcpClient.RecommendationCandidate(
            spinId = "paste",
            label = "Colgate Strong Teeth Toothpaste · 150 g",
        )

        assertTrue(isSwiggyCandidateCountCompatible(toothpaste, tube))
        assertEquals(3, swiggyRequestedCartQuantity(toothpaste, tube))
    }

    @Test
    fun rejectsMoreThanTwentyFiveItemsInsteadOfSilentlyDroppingThem() {
        val prompts = promptItems.map { it.raw } + "1 extra item"
        val items = prepareSwiggyMcpItems(
            instruction = prompts.joinToString(", "),
            lookup = { null },
        )

        assertEquals(26, items.size)
        assertEquals(
            "Swiggy supports up to 25 items in one Beta cart run. Please split this list so nothing is skipped.",
            swiggyMcpItemValidationMessage(prompts.joinToString(", "), items),
        )
    }

    @Test
    fun rejectsCountsAboveTwentyInsteadOfSilentlyClamping() {
        val items = prepareSwiggyMcpItems("21 juice", lookup = { null })

        assertEquals(
            "Swiggy supports up to quantity 20 per item. Please reduce the quantity for juice; nothing was changed.",
            swiggyMcpItemValidationMessage("21 juice", items),
        )
    }

    private fun promptSlice(size: Int): String {
        return promptItems.take(size).joinToString(", ") { it.raw }
    }

    private data class PromptItem(val raw: String)

    private val promptItems = listOf(
        PromptItem("2 diapers"),
        PromptItem("shampoo"),
        PromptItem("cleaning spray"),
        PromptItem("frozen peas"),
        PromptItem("medicine"),
        PromptItem("grapes"),
        PromptItem("24 pack paper towels"),
        PromptItem("500 g detergent"),
        PromptItem("750 ml floor cleaner"),
        PromptItem("1 kg rice"),
        PromptItem("3 toothpaste"),
        PromptItem("6 apples"),
        PromptItem("2 baby wipes"),
        PromptItem("4 soap bars"),
        PromptItem("2 milk"),
        PromptItem("1 butter"),
        PromptItem("12 eggs"),
        PromptItem("2 frozen parathas"),
        PromptItem("1 pain relief balm"),
        PromptItem("8 tissues"),
        PromptItem("notebook"),
        PromptItem("pencil"),
        PromptItem("bhindi"),
        PromptItem("lays chips"),
        PromptItem("maggi"),
    )
}
