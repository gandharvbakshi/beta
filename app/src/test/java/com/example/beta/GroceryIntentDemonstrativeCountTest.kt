package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic source-ownership coverage for local "N of those" counts. */
class GroceryIntentDemonstrativeCountTest {
    @Test fun requiredOrganicPropertySurvivesThroughCatalogueFiltering() {
        val source = "ginger, shunti, organic, three of those"
        val parsed = review(source, listOf(
            readyRow(source, item("ginger", source, 3, "three", required = listOf("organic"))),
        )).prepareForReview().items.single()
        assertEquals(Quantity.Count(3), parsed.quantity)
        assertTrue(isSwiggyCandidateAllowed(parsed,
            RecommendationCandidate(spinId = "synthetic-organic", label = "Organic Ginger")))
        for (label in listOf("Ginger", "Non-organic Ginger", "Ginger powder")) {
            assertFalse(label, isSwiggyCandidateAllowed(parsed,
                RecommendationCandidate(spinId = "synthetic-wrong", label = label)))
        }
    }

    @Test fun completeLocalNominalCanSupplyTheExplicitCountWithoutChangingItsSource() {
        listOf(
            Triple("organic ginger, three of those", "organic ginger", listOf("organic")),
            Triple("ginger, shunti, organic, three of those", "organic ginger", listOf("organic")),
        ).forEach { (instruction, name, required) ->
            val original = instruction
            val prepared = review(instruction, listOf(
                readyRow(instruction, item(name, instruction, 3, "three", required = required)),
            )).prepareForReview()

            assertEquals(instruction, original)
            assertEquals(1, prepared.items.size)
            val result = prepared.items.single()
            val identityTokens = result.query.lowercase().split(Regex("[^a-z0-9]+" )).filter { it.isNotBlank() }.toSet()
            val requestedTokens = name.lowercase().split(Regex("[^a-z0-9]+" )).filter { it.isNotBlank() }.toSet()
            assertTrue(identityTokens.containsAll(requestedTokens))
            assertTrue(result.query.contains("organic", ignoreCase = true))
            assertEquals(Quantity.Count(3), result.quantity)
            assertEquals(instruction, result.intentQuantityProvenance?.primaryText)
            assertEquals(listOf(emptyList<String>()), prepared.items.map { it.avoidPhrases })
            assertTrue(prepared.unresolvedText.isEmpty())
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test fun explicitCountBoundaryIsPreservedAndNotDefaulted() {
        val upper = "organic ginger, 20 of those"
        val accepted = review(upper, listOf(
            readyRow(upper, item("organic ginger", upper, 20, "20", required = listOf("organic"))),
        )).prepareForReview()
        assertEquals(listOf(Quantity.Count(20)), accepted.items.map { it.quantity })

        val over = "organic ginger, 21 of those"
        assertHeld(over, item("organic ginger", over, 21, "21", required = listOf("organic")))

        val unstated = "organic ginger, some of those"
        assertHeld(unstated, item("organic ginger", unstated, null, null, required = listOf("organic")))
    }

    @Test fun ambiguousOrNonlocalDemonstrativesRemainHeld() {
        listOf(
            "milk, sugar, three of those" to item("milk", "milk, sugar, three of those", 3, "three"),
            "organic ginger, three of those per child" to item("organic ginger", "organic ginger, three of those per child", 3, "three", required = listOf("organic")),
            "organic ginger, three of those if available" to item("organic ginger", "organic ginger, three of those if available", 3, "three", required = listOf("organic")),
            "organic ginger, three of those from last time" to item("organic ginger", "organic ginger, three of those from last time", 3, "three", required = listOf("organic")),
            "organic ginger, three of those" to item("organic ginger", "organic ginger, three of those", 2, "two", required = listOf("organic")),
            "organic ginger, three of those" to item("organic ginger", "organic ginger, three of those", 3, "three", quantityUnit = "pack", required = listOf("organic")),
            "organic ginger, 2g, three of those" to item("organic ginger", "organic ginger, 2g, three of those", 3, "three", packValue = BigDecimal("2"), packUnit = "g", required = listOf("organic")),
            "organic ginger, three of those; not organic" to item("organic ginger", "organic ginger, three of those; not organic", 3, "three", required = listOf("organic"), excluded = listOf("organic")),
            "organic ginger, three of those" to item("ginger", "organic ginger, three of those", 3, "three"),
            // A detached property tail must survive in the structured required tags too.
            "ginger, organic, three of those" to item("organic ginger", "ginger, organic, three of those", 3, "three"),
            // A generic search alias is not enough to prove an apposition segment.
            "ginger, adrak, organic, three of those" to item("organic ginger", "ginger, adrak, organic, three of those", 3, "three", required = listOf("organic")),
            "organic ginger, three of the usual" to item("organic ginger", "organic ginger, three of the usual", 3, "three", required = listOf("organic")),
            "organic ginger, three of the same" to item("organic ginger", "organic ginger, three of the same", 3, "three", required = listOf("organic")),
            "organic ginger, three from history" to item("organic ginger", "organic ginger, three from history", 3, "three", required = listOf("organic")),
            "organic ginger, a couple of those" to item("organic ginger", "organic ginger, a couple of those", 2, "a couple", required = listOf("organic")),
            "organic ginger, a dozen of those" to item("organic ginger", "organic ginger, a dozen of those", 12, "a dozen", required = listOf("organic")),
            "organic ginger, three each of those" to item("organic ginger", "organic ginger, three each of those", 3, "three", required = listOf("organic")),
            "another organic ginger, one more of those" to item("organic ginger", "another organic ginger, one more of those", 1, "one", required = listOf("organic")),
            "organic ginger, add three more of those" to item("organic ginger", "organic ginger, add three more of those", 3, "three", required = listOf("organic")),
            "organic ginger, three of those" to item("organic ginger", "organic ginger, three of those", 3, "three", required = listOf("organic")),
            "organic ginger, three\u200B of those" to item("organic ginger", "organic ginger, three\u200B of those", 3, "three", required = listOf("organic")),
        ).forEach { (instruction, candidate) -> assertHeld(instruction, candidate) }

        val split = "rice and dal, three of those"
        assertHeldBasket(split, listOf(
            item("rice", "rice", 1, "one"),
            item("dal", split, 3, "three"),
        ))
        val laterNominal = "rice, then ginger, three of those"
        assertHeldBasket(laterNominal, listOf(
            item("rice", "rice", 1, "one"),
            item("ginger", "then ginger, three of those", 3, "three"),
        ))

        listOf("; ", ". ", "\n").forEach { hardSeparator ->
            val instruction = "one rice${hardSeparator}organic ginger, three of those"
            val result = review(instruction, listOf(
                readyRow(instruction, item("rice", "one rice", 1, "one"), 0),
                readyRow(instruction, item("organic ginger", "organic ginger, three of those", 3, "three", required = listOf("organic")), 1),
            )).prepareForReview()
            assertEquals(2, result.items.size)
            assertEquals("rice", result.items.first().query)
            assertTrue(result.items.last().query.lowercase().split(' ').toSet().containsAll(setOf("organic", "ginger")))
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(3)), result.items.map { it.quantity })
        }

        val grouped = "for breakfast: organic ginger, three of those; one rice"
        val ginger = item("organic ginger", "organic ginger, three of those", 3, "three", required = listOf("organic"))
        val rice = item("rice", "one rice", 1, "one")
        val members = listOf(
            GroceryIntentReviewV2GroupMember(0, BigDecimal("3"), "count", "local"),
            GroceryIntentReviewV2GroupMember(1, BigDecimal.ONE, "count", "local"),
        )
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified",
            amountStatus = "local_amounts_verified",
            source = quote(grouped, grouped),
            header = quote(grouped, "for breakfast:"),
            memberIndices = listOf(0, 1),
            members = members,
            packaging = null,
            total = null,
            each = null,
        )
        val groupedReview = review(grouped, listOf(readyRow(grouped, ginger, 0), readyRow(grouped, rice, 1)), listOf(group))
            .prepareForReview()
        assertWholeInstructionRecovery(grouped, groupedReview)

        val corrected = "organic ginger, three of those; actually make it two"
        val correctedRow = readyRow(corrected,
            item("organic ginger", "organic ginger, three of those", 3, "three", required = listOf("organic")),
            corrections = listOf(GroceryIntentReviewV2Correction(
                kind = "quantity",
                source = quote(corrected, "actually make it two"),
                newAmount = quote(corrected, "two"),
                oldValue = BigDecimal("3"),
                oldUnit = "count",
                newValue = BigDecimal("2"),
                newUnit = "count",
            )),
        )
        val correctedReview = review(corrected, listOf(correctedRow)).prepareForReview()
        assertWholeInstructionRecovery(corrected, correctedReview)
    }

    @Test fun independentUncoveredRequestStillRequiresWholeListRecovery() {
        val primary = "organic ginger, three of those"
        val instruction = "$primary; also buy two soaps"
        val result = review(instruction, listOf(
            readyRow(instruction, item("organic ginger", primary, 3, "three", required = listOf("organic"))),
        )).prepareForReview()
        assertEquals(listOf(Quantity.Count(3)), result.items.map { it.quantity })
        assertTrue(result.unresolvedText.isNotEmpty())
        assertEquals(instruction, requireNotNull(result.recoverySnapshot).toDisplay().originalInstruction)
    }

    @Test fun croppedPrimaryCannotBorrowCountAcrossTheOriginalBoundary() {
        val instruction = "organic ginger, three of those if available"
        val cropped = "organic ginger, three of those"
        val result = review(instruction, listOf(
            readyRow(instruction, item("organic ginger", cropped, 3, "three", required = listOf("organic"))),
        )).prepareForReview()
        assertWholeInstructionRecovery(instruction, result)
    }

    private fun assertHeld(instruction: String, candidate: GroceryIntentReviewV2Item) {
        assertHeldBasket(instruction, listOf(candidate))
    }

    private fun assertHeldBasket(instruction: String, candidates: List<GroceryIntentReviewV2Item>) {
        val result = review(instruction, candidates.mapIndexed { index, candidate ->
            readyRow(instruction, candidate, index)
        }).prepareForReview()
        assertWholeInstructionRecovery(instruction, result)
    }

    private fun assertWholeInstructionRecovery(instruction: String, result: GroceryIntentPreparation) {
        assertTrue("Unexpected accepted projection for synthetic input: $instruction", result.items.isEmpty())
        assertEquals(instruction, result.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    private fun review(
        instruction: String,
        ready: List<GroceryIntentReviewV2ReadyRow>,
        groups: List<GroceryIntentReviewV2Group> = emptyList(),
    ) = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2",
        requiresReview = true,
        instruction = instruction,
        sourceItemCount = ready.size,
        readyRows = ready,
        blockedRows = emptyList(),
        groups = groups,
    )

    private fun item(
        name: String,
        source: String,
        quantity: Int?,
        quantityText: String?,
        quantityUnit: String = "count",
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        packValue: BigDecimal? = null,
        packUnit: String = if (packValue == null) "unspecified" else "g",
    ) = GroceryIntentReviewV2Item(
        name = name,
        quantity = quantity?.let { BigDecimal.valueOf(it.toLong()) },
        quantityUnit = quantityUnit,
        packValue = packValue,
        packUnit = packUnit,
        packaging = null,
        required = required,
        excluded = excluded,
        sourceText = source,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = quantityText,
        packText = packValue?.toPlainString(),
    )

    private fun readyRow(
        instruction: String,
        item: GroceryIntentReviewV2Item,
        index: Int = 0,
        corrections: List<GroceryIntentReviewV2Correction> = emptyList(),
    ) = GroceryIntentReviewV2ReadyRow(
        rowIndex = index,
        item = item,
        evidence = GroceryIntentReviewV2Evidence(primary = quote(instruction, item.sourceText), supporting = emptyList()),
        quantityOrigin = if (item.quantity == null) "not_stated" else "stated",
        amountRoleStatus = if (item.quantity == null && item.packValue == null) "not_stated" else "matched",
        corrections = corrections,
    )

    private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
        val start = instruction.indexOf(text)
        require(start >= 0)
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }
}
