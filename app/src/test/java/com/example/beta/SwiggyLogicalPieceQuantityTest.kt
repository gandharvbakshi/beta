package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyLogicalPieceQuantityTest {
    @Test
    fun v2PieceQuantityRemainsLogicalUntilExactPackConversion(): Unit {
        val item = v2EggPieces(24)

        assertTrue(item.logicalPieceCount)
        assertEquals(Quantity.Count(24), item.quantity)
        assertNull(swiggyMcpItemValidationMessage("", listOf(item)))

        val sixPack = candidate("Farm Fresh Eggs · 6 Pieces")
        assertTrue(isSwiggyCandidateCountCompatible(assessed(item, sixPack), sixPack))
        assertEquals(4, swiggyRequestedCartQuantity(assessed(item, sixPack), sixPack))
        listOf("Farm Fresh Eggs · Pack of 6 Eggs", "Farm Fresh Eggs · Tray of 6 Pieces").forEach { label ->
            val candidate = candidate(label)
            val verified = assessed(item, candidate)
            assertTrue(isSwiggyCandidateCountCompatible(verified, candidate))
            assertEquals(4, swiggyRequestedCartQuantity(verified, candidate))
        }
    }

    @Test
    fun logicalPieceCountRejectsUnknownPackAndNonDivisibleConversion(): Unit {
        val item = v2EggPieces(24)
        val unknown = candidate("Farm Fresh Eggs")
        val sixPack = candidate("Farm Fresh Eggs · 6 Pieces")

        val assessedUnknown = assessed(item, unknown)
        assertFalse(isSwiggyCandidateCountCompatible(assessedUnknown, unknown))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessedUnknown, unknown) }
        val nonDivisible = assessed(v2EggPieces(25), sixPack)
        assertFalse(isSwiggyCandidateCountCompatible(nonDivisible, sixPack))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(nonDivisible, sixPack) }
    }

    @Test
    fun logicalPiecesConvertOnlyToAtMostTwentyRetailPacks(): Unit {
        val sixPack = candidate("Farm Fresh Eggs · 6 Pieces")

        val divisible = assessed(v2EggPieces(120), sixPack)
        assertTrue(isSwiggyCandidateCountCompatible(divisible, sixPack))
        assertEquals(20, swiggyRequestedCartQuantity(divisible, sixPack))
        val tooMany = assessed(v2EggPieces(126), sixPack)
        assertFalse(isSwiggyCandidateCountCompatible(tooMany, sixPack))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(tooMany, sixPack) }
        assertNull(swiggyMcpItemValidationMessage("", listOf(v2EggPieces(1000))))
        assertTrue(swiggyMcpItemValidationMessage("", listOf(v2EggPieces(1001))) != null)
    }

    @Test
    fun logicalPieceRequestsRejectNestedOrConflictingBundleLabels(): Unit {
        val item = v2EggPieces(24)
        val ambiguousLabels = listOf(
            "6 pcs x 2",
            "2 x 6 pcs",
            "Pack of 2 · 6 Eggs each",
            "two boxes of 6 eggs",
            "6 pcs 500 g",
            "6 Eggs · 12 Pieces",
            "Eggs · 1 Pack",
        )

        ambiguousLabels.forEach { label ->
            val candidate = candidate(label)
            val verified = assessed(item, candidate)
            assertFalse("Ambiguous candidate label must be rejected: $label", isSwiggyCandidateCountCompatible(verified, candidate))
            assertThrows("Must not derive cart quantity from $label", IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(verified, candidate)
            }
        }
    }

    @Test
    fun legacyCountTwentyFourAndV1PieceTwentyFourStayRejected(): Unit {
        val legacy = ParsedItem(rawText = "24 eggs", query = "eggs", quantity = Quantity.Count(24))
        assertTrue(swiggyMcpItemValidationMessage("", listOf(legacy)) != null)

        val v1Body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":"24 eggs","items":[{"name":"eggs","quantity":24,"quantity_unit":"piece","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"24 eggs","needs_clarification":false}],"model_needs_clarification":false},"model":"test","promptVersion":"v1-test","latencyMs":1,"usage":{}}"""
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(v1Body, "24 eggs")
        }
    }

    @Test
    fun bonusPiecesCannotBeMistakenForTheSinglePackTotal(): Unit {
        val item = v2EggPieces(24)
        listOf(
            "Farm Fresh Eggs · 6 Pieces + 6 Pieces Free",
            "Farm Fresh Eggs · Pack of 6 Eggs + 6 Pieces Free",
            "Farm Fresh Eggs · 6 Pieces with extra eggs",
        ).forEach { label ->
            val candidate = candidate(label)
            val verified = assessed(item, candidate)
            assertFalse(isSwiggyCandidateCountCompatible(verified, candidate))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(verified, candidate) }
        }
    }

    private fun v2EggPieces(count: Int): ParsedItem {
        val instruction = "$count eggs"
        val v2Item = GroceryIntentReviewV2Item(
            name = "eggs",
            quantity = BigDecimal.valueOf(count.toLong()),
            quantityUnit = "piece",
            packValue = null,
            packUnit = "unspecified",
            packaging = null,
            required = emptyList(),
            excluded = emptyList(),
            sourceText = instruction,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = count.toString(),
            packText = null,
        )
        val quote = GroceryIntentReviewV2Quote(instruction, 0, instruction.length)
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = v2Item,
            evidence = GroceryIntentReviewV2Evidence(quote, emptyList()),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = 1,
            readyRows = listOf(row),
            blockedRows = emptyList(),
        ).prepareForReview().items.single()
    }

    private fun candidate(label: String) = RecommendationCandidate(spinId = "synthetic-eggs", label = label)

    private fun assessed(item: ParsedItem, candidate: RecommendationCandidate) =
        assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate), suggested = candidate))
}
