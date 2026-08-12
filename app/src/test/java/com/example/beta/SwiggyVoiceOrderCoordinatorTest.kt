package com.example.beta

import com.example.beta.automation.Preference
import org.junit.Assert.assertEquals
import org.junit.Test

class SwiggyVoiceOrderCoordinatorTest {
    @Test
    fun preparesAllTwentyFiveItemsAndAppliesLocalPreferences() {
        val prompts = (1..25).map { "item$it" }

        val items = prepareSwiggyMcpItems(
            instruction = "use swiggy for ${prompts.joinToString(", ")}",
            lookup = { query ->
                if (query == "item1") {
                    Preference(
                        token = query,
                        preferredPhrase = "preferred item one",
                        confidence = 1.0f,
                    )
                } else {
                    null
                }
            },
        )

        assertEquals(25, items.size)
        assertEquals("preferred item one", items.first().query)
        assertEquals("item25", items.last().query)
    }

    @Test
    fun rejectsMoreThanTwentyFiveItemsInsteadOfSilentlyDroppingThem() {
        val prompts = (1..30).map { "item$it" }

        val items = prepareSwiggyMcpItems(
            instruction = prompts.joinToString(", "),
            lookup = { null },
        )

        assertEquals(30, items.size)
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

    @Test
    fun allowsLargeWeightsVolumesAndMultipackDescriptors() {
        val instruction = "500 g detergent, 24 pack paper towels, 2000 ml juice"
        val items = prepareSwiggyMcpItems(instruction, lookup = { null })

        assertEquals(null, swiggyMcpItemValidationMessage(instruction, items))
    }

    @Test
    fun rejectsPlansThatRemoveOrChangeUnselectedItems() {
        val selected = listOf(SwiggyMcpClient.RequestedItem("juice", 2, "Usual juice"))
        val safePlan = SwiggyMcpClient.CartPlan(
            changes = listOf(
                SwiggyMcpClient.CartPlanChange(
                    spinId = "juice",
                    kind = "change",
                    displayName = "Usual juice",
                    fromQuantity = 0,
                    toQuantity = 2,
                ),
            ),
            confirmationToken = "signed-plan",
            cartMutationEnabled = true,
        )
        val unsafePlan = safePlan.copy(
            changes = safePlan.changes + SwiggyMcpClient.CartPlanChange(
                spinId = "existing",
                kind = "remove",
                displayName = "Existing cart item",
                fromQuantity = 1,
                toQuantity = 0,
            ),
        )
        val selectedRemovalPlan = safePlan.copy(
            changes = listOf(safePlan.changes.single().copy(kind = "remove")),
        )

        assertEquals(true, isSafeSwiggyCartPlan(safePlan, selected))
        assertEquals(false, isSafeSwiggyCartPlan(unsafePlan, selected))
        assertEquals(false, isSafeSwiggyCartPlan(selectedRemovalPlan, selected))
    }

    @Test
    fun recommendationBatchMustMatchEveryQueryInInputOrder() {
        val milk = SwiggyMcpClient.Recommendations(emptyList(), query = "milk")
        val bread = SwiggyMcpClient.Recommendations(emptyList(), query = "bread")

        assertEquals(true, areSwiggyRecommendationsOrdered(listOf(milk, bread), listOf("milk", "bread")))
        assertEquals(false, areSwiggyRecommendationsOrdered(listOf(bread, milk), listOf("milk", "bread")))
        assertEquals(false, areSwiggyRecommendationsOrdered(listOf(milk), listOf("milk", "bread")))
    }
}
