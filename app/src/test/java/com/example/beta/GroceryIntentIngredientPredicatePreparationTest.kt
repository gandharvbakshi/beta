package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentIngredientPredicatePreparationTest {
    private val products = listOf("juice", "soap", "coffee", "tea", "rice", "milk", "eggs", "noodles", "pasta",
        "shampoo", "towel", "pencil", "notebook", "chocolate", "dal", "butter", "paneer", "banana", "apple", "bread")

    @Test fun completeFifteenAndTwentyItemBasketsRetainTypedRestrictionsAndOriginalCounts() {
        val clauses = listOf(
            "No salt added to the juice" to listOf("added_salt"),
            "No salt in the juice" to listOf("salt"),
            "juice without salt or preservatives" to listOf("salt", "preservatives"),
            "juice without salt or added sugar" to listOf("salt", "added_sugar"),
            "juice without added salt or added sugar" to listOf("added_salt", "added_sugar"),
            "juice mein namak nahi" to listOf("salt"),
        )
        for (size in listOf(15, 20)) for ((clause, exclusions) in clauses) {
            val names = products.take(size)
            val draft = basket(names, clause, exclusions)
            val prepared = draft.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true)
            assertEquals("$size / $clause", size, prepared.items.size)
            assertEquals(List(size) { Quantity.Count(1) }, prepared.items.map { it.quantity })
            assertEquals(names.map { "one $it" }, prepared.items.map { it.intentQuantityProvenance?.primaryText })
            assertEquals(exclusions.map { it.replace('_', ' ') }, prepared.items.first().avoidPhrases)
            assertTrue(prepared.items.drop(1).all { it.avoidPhrases.isEmpty() })
            prepared.items.zip(names).forEach { (item, name) ->
                assertTrue("Missing original noun $name", item.query.contains(name, ignoreCase = true))
            }
            assertFalse(prepared.items.first().query.contains("salt", ignoreCase = true))
            assertFalse(prepared.items.first().query.contains("preservatives", ignoreCase = true))
            assertEquals(listOf(clause), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            draft.readyRows.forEach { original ->
                val source = original.evidence.primary
                assertEquals(source.text, draft.instruction.substring(source.startUtf16, source.endUtf16))
                assertEquals(BigDecimal.ONE, original.item.quantity)
                assertEquals("count", original.item.quantityUnit)
                assertEquals("one", original.item.quantityText)
            }
        }
    }

    @Test fun sourcePredicateCannotGrantReadinessWithoutBothCapabilities() {
        val draft = basket(products.take(15), "No salt added to the juice", listOf("added_salt"))
        for ((allowProperties, allowPredicates) in listOf(false to false, true to false, false to true)) {
            val prepared = draft.prepareForReview(
                allowPropertyObligations = allowProperties, allowIngredientPredicates = allowPredicates)
            assertTrue("$allowProperties/$allowPredicates", prepared.items.size < draft.sourceItemCount)
            assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
            assertEquals(draft.instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            assertTrue(prepared.items.none { it.query.contains("juice", ignoreCase = true) })
        }
    }

    @Test fun missingWrongOperatorWrongRowOrPositiveReplacementCannotRetainNegativePredicate() {
        val draft = basket(products.take(15), "No salt added to the juice", listOf("added_salt"))
        val mutations = listOf(
            mutateItem(draft, 0) { it.copy(excluded = emptyList()) },
            mutateItem(draft, 0) { it.copy(excluded = listOf("salt")) },
            mutateItem(mutateItem(draft, 0) { it.copy(excluded = emptyList()) }, 1) {
                it.copy(excluded = listOf("added_salt"))
            },
            mutateItem(draft, 0) { it.copy(excluded = emptyList(), required = listOf("unsalted")) },
            mutateItem(draft, 0) { it.copy(excluded = listOf("added_salt", "palm_oil")) },
            mutateItem(draft, 1) { it.copy(excluded = listOf("palm_oil")) },
        )
        mutations.forEach(::assertHeld)
        val absent = basket(products.take(15), "No salt in the juice", listOf("salt"))
        assertHeld(mutateItem(absent, 0) { it.copy(excluded = listOf("added_salt")) })
        val coordinated = basket(products.take(15), "juice without salt or preservatives", listOf("salt", "preservatives"))
        assertHeld(mutateItem(coordinated, 0) { it.copy(excluded = listOf("salt")) })
    }

    @Test fun croppedOmittedOrDuplicateOriginalTargetsCannotSupplyScopedIngredientProof() {
        val names = products.take(15)
        val fullSources = names.map { "one $it" }.toMutableList().also { it[0] = "one juice citrus" }
        val complete = basket(names, "No salt in the juice", listOf("salt"), sources = fullSources)
        val first = complete.readyRows.first()
        val cropped = first.copy(
            item = first.item.copy(sourceText = "one juice"),
            evidence = first.evidence.copy(primary = first.evidence.primary.copy(
                text = "one juice", endUtf16 = first.evidence.primary.startUtf16 + "one juice".length)),
        )
        assertHeld(complete.copy(readyRows = listOf(cropped) + complete.readyRows.drop(1)))
        val ordinary = basket(names, "No salt in the juice", listOf("salt"))
        assertHeld(ordinary.copy(readyRows = ordinary.readyRows.filterNot { it.rowIndex == 3 }))
        assertHeld(basket(listOf("juice", "juice"), "No salt in the juice", listOf("salt")))
    }

    @Test fun unknownIngredientAndUnprovedCoordinationStayHeldDespiteReadyRows() {
        for ((clause, exclusions) in listOf(
            "juice without fragrance" to listOf("fragrance"),
            "No sugar in the juice" to listOf("sugar"),
            "juice without salt and preservatives" to listOf("salt", "preservatives"),
            "juice mein namak aur preservatives nahi" to listOf("salt", "preservatives"),
        )) {
            assertHeld(basket(products.take(15), clause, exclusions))
        }
    }

    @Test fun onlyCompleteOriginalModifierSupportAndItsSingleFinalPeriodAreRemoved() {
        val clause = "No salt added to the juice"
        for (support in listOf(clause, "$clause.")) {
            val draft = basket(products.take(15), clause, listOf("added_salt"), support = support)
            val proof = requireNotNull(draft.provenPropertyPreparation(allowIngredientPredicates = true))
            assertEquals(support, draft.readyRows.first().evidence.supporting.single().quote.text)
            assertTrue(proof.review.readyRows.first().item.supportingText.isEmpty())
            assertTrue(proof.review.readyRows.first().evidence.supporting.isEmpty())
            assertEquals(draft.instruction, proof.review.instruction)
            assertEquals(draft.readyRows.map { it.evidence.primary }, proof.review.readyRows.map { it.evidence.primary })
            val prepared = draft.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true)
            assertEquals(15, prepared.items.size)
            assertEquals(List(15) { Quantity.Count(1) }, prepared.items.map { it.quantity })
            assertEquals(listOf("added salt"), prepared.items.first().avoidPhrases)
            assertEquals(listOf(clause), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
        for (partial in listOf("No salt added", "salt added to the juice", "added to the juice")) {
            val draft = basket(products.take(15), clause, listOf("added_salt"), support = partial)
            val proof = requireNotNull(draft.provenPropertyPreparation(allowIngredientPredicates = true))
            assertEquals(partial, proof.review.readyRows.first().evidence.supporting.single().quote.text)
            assertEquals(partial, proof.review.readyRows.first().item.supportingText.single().text)
            assertHeld(draft)
        }
    }

    @Test fun noAdditionClauseCannotOverrideOriginalSaltCrackersIdentityConflict() {
        assertHeld(basket(listOf("salt crackers"), "No salt added to the salt crackers", listOf("added_salt")))
    }

    @Test fun independentlyStatedAbsenceAndNoAdditionBothRemainInFinalExclusions() {
        val clauses = listOf("No salt in the biscuits", "No added salt in the biscuits")
        val draft = basket(listOf("biscuits"), clauses.joinToString("; "), listOf("salt", "added_salt"))
        val prepared = draft.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true)
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals(Quantity.Count(1), item.quantity)
        assertEquals("one biscuits", item.intentQuantityProvenance?.primaryText)
        assertEquals(listOf("salt", "added salt"), item.avoidPhrases)
        assertTrue(item.query.contains("biscuits", ignoreCase = true))
        assertFalse(item.query.contains("salt", ignoreCase = true))
        assertEquals(clauses, prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun localNegativePrimaryIsNotPromotedToSeparateScopedPredicateByCapability() {
        for ((source, name) in listOf("one biscuits without salt" to "biscuits", "one biscuit mein namak nahi" to "biscuit")) {
            val draft = basket(listOf(name), "", listOf("salt"), sources = listOf(source)).copy(instruction = source)
            val primary = draft.readyRows.single().evidence.primary
            assertEquals(source, primary.text)
            assertEquals(0, primary.startUtf16)
            assertEquals(source.length, primary.endUtf16)
            assertTrue(draft.readyRows.single().evidence.supporting.isEmpty())
            for (enabled in listOf(false, true)) {
                val prepared = draft.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = enabled)
                assertTrue("local negative source, capability=$enabled", prepared.items.isEmpty())
                assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
                assertEquals(source, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            }
        }
    }

    private fun basket(
        names: List<String>,
        clause: String,
        juiceExclusions: List<String>,
        support: String? = null,
        sources: List<String> = names.map { "one $it" },
    ): GroceryIntentReviewV2 {
        require(names.size == sources.size)
        val instruction = sources.joinToString(", ") + "; $clause."
        var nextStart = 0
        val rows = names.mapIndexed { index, name ->
            val source = sources[index]
            val start = instruction.indexOf(source, nextStart).also { require(it >= 0) }
            nextStart = start + source.length
            val rowSupport = support?.takeIf { index == 0 }
            val supportingText = listOfNotNull(rowSupport?.let { GroceryIntentReviewV2SupportText("modifier", it) })
            val supportingQuotes = listOfNotNull(rowSupport?.let {
                val supportStart = instruction.indexOf(it, instruction.indexOf(';')).also { offset -> require(offset >= 0) }
                GroceryIntentReviewV2SupportQuote("modifier", GroceryIntentReviewV2Quote(it, supportStart, supportStart + it.length))
            })
            GroceryIntentReviewV2ReadyRow(
                rowIndex = index,
                item = GroceryIntentReviewV2Item(name = name, quantity = BigDecimal.ONE, quantityUnit = "count",
                    packValue = null, packUnit = "unspecified", packaging = null, required = emptyList(),
                    excluded = if (index == 0) juiceExclusions else emptyList(), sourceText = source,
                    supportingText = supportingText, needsClarification = false, quantityText = "one", packText = null),
                evidence = GroceryIntentReviewV2Evidence(GroceryIntentReviewV2Quote(source, start, start + source.length), supportingQuotes),
                quantityOrigin = "stated", amountRoleStatus = "matched",
            )
        }
        return GroceryIntentReviewV2(contractVersion = "grocery-intent-v2", requiresReview = true,
            instruction = instruction, sourceItemCount = rows.size, readyRows = rows, blockedRows = emptyList())
    }

    private fun mutateItem(draft: GroceryIntentReviewV2, index: Int,
        change: (GroceryIntentReviewV2Item) -> GroceryIntentReviewV2Item): GroceryIntentReviewV2 =
        draft.copy(readyRows = draft.readyRows.map { if (it.rowIndex == index) it.copy(item = change(it.item)) else it })

    private fun assertHeld(draft: GroceryIntentReviewV2) {
        val prepared = draft.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true)
        assertTrue("Unproven predicate must not release a basket", prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        assertEquals(draft.instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
    }
}
