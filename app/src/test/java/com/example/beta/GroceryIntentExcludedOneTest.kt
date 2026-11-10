package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentQuantityOrigin
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compiled-payload fixtures: server-ready labels cannot prove exclusion scope. */
class GroceryIntentExcludedOneTest {
    @Test fun terminalExcludedOneRetainsPhysicalPurchaseAndBothPropertyPolarities() {
        listOf(
            Triple("250 g fresh spinach, not the dried one", "fresh spinach", "fresh" to "dried"),
            Triple("250 g organic rice, not the plain one", "organic rice", "organic" to "plain"),
        ).forEach { (source, name, properties) ->
            val candidate = item(name, source, required = listOf(properties.first), excluded = listOf(properties.second))
            val originalRow = row(source, candidate)
            val prepared = review(source, listOf(originalRow)).prepareForReview()

            assertEquals(source, 1, prepared.items.size)
            val parsed = prepared.items.single()
            assertEquals(Quantity.Weight(250), parsed.quantity)
            assertTrue(parsed.query.contains(properties.first, ignoreCase = true))
            assertTrue(parsed.query.contains(name.substringAfterLast(' '), ignoreCase = true))
            assertEquals(listOf(properties.second), parsed.avoidPhrases)
            assertEquals(source, parsed.intentQuantityProvenance?.primaryText)
            assertEquals(source, originalRow.item.sourceText)
            assertEquals(source, originalRow.evidence.primary.text)
            assertEquals(BigDecimal("250"), originalRow.item.quantity)
            assertEquals("250", originalRow.item.quantityText)
            assertEquals(listOf(properties.first), originalRow.item.required)
            assertEquals(listOf(properties.second), originalRow.item.excluded)
            assertTrue(prepared.unresolvedText.isEmpty())
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test fun existingTerminalNotOrganicStillRetainsCountAndExclusion() {
        val source = "one milk, not organic"
        val candidate = item("milk", source, excluded = listOf("organic")).copy(
            quantity = BigDecimal.ONE, quantityUnit = "count", quantityText = "one",
        )
        val prepared = review(source, listOf(row(source, candidate))).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertEquals(listOf("organic"), prepared.items.single().avoidPhrases)
        assertEquals(source, prepared.items.single().intentQuantityProvenance?.primaryText)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun missingOrPromotedNegativePropertyCannotBorrowTheReceipt() {
        val source = "250 g fresh spinach, not the dried one"
        listOf(
            item("fresh spinach", source, required = listOf("fresh")),
            item("fresh dried spinach", source, required = listOf("fresh", "dried")),
            item("fresh dried spinach", source, required = listOf("fresh", "dried"), excluded = listOf("dried")),
            item("fresh spinach", source, required = listOf("fresh"), excluded = listOf("plain")),
        ).forEach { candidate -> assertHeld(source, listOf(row(source, candidate))) }
    }

    @Test fun anaphoricOneCannotReplaceTheActualPurchaseAmount() {
        val source = "250 g fresh spinach, not the dried one"
        val forged = item("fresh spinach", source, required = listOf("fresh"), excluded = listOf("dried")).copy(
            quantity = BigDecimal.ONE, quantityUnit = "count", quantityText = "one",
        )
        assertHeld(source, listOf(row(source, forged)))
    }

    @Test fun anaphoricOneCannotCreateAnUnstatedPurchaseCount() {
        val source = "fresh spinach, not the dried one"
        val forged = item("fresh spinach", source, required = listOf("fresh"), excluded = listOf("dried")).copy(
            quantity = BigDecimal.ONE, quantityUnit = "count", quantityText = "one",
        )
        assertHeld(source, listOf(row(source, forged)))
    }

    @Test fun nullPurchaseAmountNeverAcquiresTheExcludedPronounAsLocalEvidence() {
        val source = "fresh spinach, not the dried one"
        val unstated = item("fresh spinach", source, required = listOf("fresh"), excluded = listOf("dried")).copy(
            quantity = null, quantityUnit = "unspecified", quantityText = null,
        )
        val originalRow = row(source, unstated).copy(quantityOrigin = "not_stated", amountRoleStatus = "not_stated")
        val prepared = review(source, listOf(originalRow)).prepareForReview()
        if (prepared.items.isEmpty()) {
            assertEquals(source, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        } else {
            val parsed = prepared.items.single()
            assertEquals(Quantity.Default, parsed.quantity)
            val provenance = requireNotNull(parsed.intentQuantityProvenance)
            assertEquals(IntentQuantityOrigin.UNSTATED, provenance.origin)
            assertEquals(Quantity.Default, provenance.resolvedQuantity)
            assertEquals(null, provenance.local)
            assertEquals(source, provenance.primaryText)
            assertEquals(listOf("dried"), parsed.avoidPhrases)
        }
        assertEquals(null, originalRow.item.quantity)
        assertEquals(null, originalRow.item.quantityText)
    }

    @Test fun exclusionReceiptCannotAuthorizeInventedAdditionalConstraints() {
        val source = "250 g fresh spinach, not the dried one"
        listOf(listOf("dried", "plain"), listOf("dried", "branded"), listOf("dried", "plain", "branded"))
            .forEach { exclusions ->
                assertHeld(source, listOf(row(source,
                    item("fresh spinach", source, required = listOf("fresh"), excluded = exclusions))))
            }
    }

    @Test fun neutralPleaseGetPrefixPreservesTheExactPhysicalPurchase() {
        val primary = "250 g fresh spinach, not the dried one"
        val instruction = "Please get $primary"
        val prepared = review(instruction, listOf(row(instruction,
            item("fresh spinach", primary, required = listOf("fresh"), excluded = listOf("dried")))))
            .prepareForReview()
        assertEquals(1, prepared.items.size)
        val parsed = prepared.items.single()
        assertEquals(Quantity.Weight(250), parsed.quantity)
        assertEquals(listOf("dried"), parsed.avoidPhrases)
        assertTrue(parsed.query.contains("fresh", ignoreCase = true))
        val provenance = requireNotNull(parsed.intentQuantityProvenance)
        assertEquals(primary, provenance.primaryText)
        assertEquals("250", provenance.local?.atom)
        assertEquals(BigDecimal("250"), provenance.local?.value)
        assertEquals("g", provenance.local?.unit)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun extraAmountsUnknownWordsAndConditionsRemainHeld() {
        listOf(
            "250 g fresh spinach, not the dried two one",
            "250 g fresh spinach, not the 2 dried one",
            "250 g fresh spinach, not the dried unusual one",
            "250 g fresh spinach, not the dried one if cheap",
            "250 g fresh spinach, not the dried one tomorrow",
            "250 g fresh spinach, not the dried one or rice",
        ).forEach { source ->
            assertHeld(source, listOf(row(source,
                item("fresh spinach", source, required = listOf("fresh"), excluded = listOf("dried")))))
        }
        val unknown = "250 g fresh spinach, not the mysterious one"
        assertHeld(unknown, listOf(row(unknown,
            item("fresh spinach", unknown, required = listOf("fresh"), excluded = listOf("mysterious")))))
    }

    @Test fun aCroppedPrimaryCannotHideAnotherPurchaseOrCondition() {
        val primary = "250 g fresh spinach, not the dried one"
        listOf(" and one rice", ", two apples", " if cheap", " tomorrow", " from last time").forEach { suffix ->
            val instruction = primary + suffix
            assertHeld(instruction, listOf(row(instruction,
                item("fresh spinach", primary, required = listOf("fresh"), excluded = listOf("dried")))))
        }
    }

    @Test fun croppedPrimaryBeforeExclusionCannotRelyOnLexicalCoverageOrAnExcludedTag() {
        val primary = "one pack of fresh spinach"
        val instruction = "$primary, not the dried one"
        for (exclusions in listOf(emptyList<String>(), listOf("dried"))) {
            val candidate = item("fresh spinach", primary, required = listOf("fresh"), excluded = exclusions).copy(
                quantity = BigDecimal.ONE, quantityUnit = "pack", quantityText = "one", packaging = "pack",
            )
            for (allowProperties in listOf(false, true)) {
                assertHeld(instruction, listOf(row(instruction, candidate)), allowProperties)
            }
        }
    }

    @Test fun anUnownedExcludedOneClauseCannotAuthorizeAnUnrelatedReadyRow() {
        val instruction = "one rice; one spinach, not the dried one"
        val rice = item("rice", "one rice").copy(
            quantity = BigDecimal.ONE, quantityUnit = "count", quantityText = "one",
        )
        for (allowProperties in listOf(false, true)) {
            assertHeld(instruction, listOf(row(instruction, rice)), allowProperties)
        }
    }

    @Test fun quotedAndReportedExclusionsAreNotDirectPurchaseConstraints() {
        val primary = "250 g fresh spinach, not the dried one"
        listOf(
            "\"$primary\"",
            "The label says $primary",
            "The note reads: $primary",
        ).forEach { instruction ->
            assertHeld(instruction, listOf(row(instruction,
                item("fresh spinach", primary, required = listOf("fresh"), excluded = listOf("dried")))))
        }
        val reported = "250 g fresh spinach labelled, not the dried one"
        assertHeld(reported, listOf(row(reported,
            item("fresh spinach labelled", reported, required = listOf("fresh"), excluded = listOf("dried")))))
    }

    @Test fun aSecondIndependentlyValidRowCannotExtendSingleItemExclusionScope() {
        val primary = "250 g fresh spinach, not the dried one"
        listOf(
            "$primary; one rice",
            "one rice; $primary",
        ).forEach { instruction ->
            val spinach = item("fresh spinach", primary, required = listOf("fresh"), excluded = listOf("dried"))
            val rice = item("rice", "one rice").copy(
                quantity = BigDecimal.ONE, quantityUnit = "count", quantityText = "one",
            )
            val candidates = if (instruction.startsWith(primary)) listOf(spinach, rice) else listOf(rice, spinach)
            assertHeld(instruction, candidates.mapIndexed { index, candidate -> row(instruction, candidate, index) })
        }
    }

    @Test fun detachedSupportCannotAuthorizeThisSinglePrimaryReceipt() {
        val primary = "250 g fresh spinach, not the dried one"
        val instruction = "$primary. The spinach must be fresh"
        val support = GroceryIntentReviewV2SupportText("modifier", "The spinach must be fresh")
        val original = row(instruction,
            item("fresh spinach", primary, required = listOf("fresh"), excluded = listOf("dried")))
        val supported = original.copy(
            item = original.item.copy(supportingText = listOf(support)),
            evidence = original.evidence.copy(supporting = listOf(
                GroceryIntentReviewV2SupportQuote("modifier", quote(instruction, support.text)),
            )),
        )
        assertHeld(instruction, listOf(supported))
    }

    private fun assertHeld(
        instruction: String, rows: List<GroceryIntentReviewV2ReadyRow>, allowPropertyObligations: Boolean = false,
    ) {
        val prepared = review(instruction, rows).prepareForReview(allowPropertyObligations = allowPropertyObligations)
        assertTrue("Unexpected accepted projection: $instruction", prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    private fun review(instruction: String, rows: List<GroceryIntentReviewV2ReadyRow>) = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
        sourceItemCount = rows.size, readyRows = rows, blockedRows = emptyList(), groups = emptyList(),
    )

    private fun item(
        name: String, source: String, required: List<String> = emptyList(), excluded: List<String> = emptyList(),
    ) = GroceryIntentReviewV2Item(
        name = name, quantity = BigDecimal("250"), quantityUnit = "g", quantityText = "250",
        packValue = null, packUnit = "unspecified", packText = null, packaging = null,
        required = required, excluded = excluded, sourceText = source, supportingText = emptyList(),
        needsClarification = false,
    )

    private fun row(instruction: String, item: GroceryIntentReviewV2Item, index: Int = 0) = GroceryIntentReviewV2ReadyRow(
        rowIndex = index, item = item,
        evidence = GroceryIntentReviewV2Evidence(primary = quote(instruction, item.sourceText), supporting = emptyList()),
        quantityOrigin = "stated", amountRoleStatus = "matched",
    )

    private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
        val start = instruction.indexOf(text)
        require(start >= 0) { "Synthetic quote must occur in the instruction" }
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }
}
