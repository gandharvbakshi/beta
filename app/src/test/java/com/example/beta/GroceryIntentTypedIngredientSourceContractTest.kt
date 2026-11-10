package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GroceryIntentTypedIngredientSourceContractTest {
    @Test
    fun unknownLiteralsRequireTypedCompositionAndExactModelRetention() {
        val instruction = "one soap; no fragrance in soap"
        val row = propertyRow(instruction, "one soap", "soap", excluded = listOf("fragrance"))
        assertEquals("ingredient_predicate_unenforced", compile(instruction, row).issueCodes.single())
        val compiled = compile(instruction, row, allowTyped = true)
        assertTrue(compiled.issueCodes.isEmpty())
        assertEquals(listOf(IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")),
            compiled.obligations.single().ingredientPredicates)

        for (clause in listOf(
            "soap without fragrance", "no organic in soap", "no fresh in soap", "no extra fragrance in soap",
            "no packet in soap", "no soap in soap",
        )) {
            val unsupportedInstruction = "one soap; $clause"
            val unsupported = compile(unsupportedInstruction,
                propertyRow(unsupportedInstruction, "one soap", "soap", excluded = listOf("fragrance")), allowTyped = true)
            assertTrue("$clause: ${unsupported.issueCodes}", unsupported.obligations.isEmpty())
        }
    }

    @Test
    fun supportedCompositionsCoverNoInNoAddedToAndHindiButNotWithout() {
        val examples = listOf(
            Triple("no fragrance in soap", "fragrance", IngredientPredicateOperator.ABSENT),
            Triple("no fragrance added to soap", "added_fragrance", IngredientPredicateOperator.NO_ADDITION),
            Triple("soap mein fragrance nahi", "fragrance", IngredientPredicateOperator.ABSENT),
        )
        examples.forEach { (clauseText, retained, operator) ->
            val instruction = "one soap; $clauseText"
            val result = compile(instruction,
                propertyRow(instruction, "one soap", "soap", excluded = listOf(retained)), allowTyped = true)
            assertTrue(clauseText, result.issueCodes.isEmpty())
            assertEquals("fragrance", result.obligations.single().ingredientPredicates.single().operand)
            assertEquals(operator, result.obligations.single().ingredientPredicates.single().operator)
        }
        val without = "one soap; soap without fragrance"
        assertEquals("ingredient_predicate_unenforced", compile(without,
            propertyRow(without, "one soap", "soap", excluded = listOf("fragrance")), allowTyped = true)
            .issueCodes.single())
    }

    @Test
    fun typedCapabilityRequiresACompleteExactReceiptAndDoesNotChangeDefaultMode() {
        val base = review("one soap; no fragrance in soap", listOf("fragrance"))
        val compiled = base.provenPropertyPreparation(
            allowIngredientPredicates = true, allowTypedIngredientPredicates = true,
        )!!.properties.single()
        val correct = GroceryIntentReviewV2IngredientObligation(
            compiled.source, compiled.memberIndices, compiled.ingredientPredicates,
        )
        val valid = base.copy(ingredientObligations = listOf(correct))
        val prepared = valid.prepareForReview(
            allowPropertyObligations = true, allowIngredientPredicates = true,
            allowTypedIngredientPredicates = true,
        )
        assertEquals(1, prepared.items.size)
        assertEquals(correct.predicates,
            prepared.items.single().ingredientProvenance?.let { proof ->
                // The runtime receipt is intentionally opaque outside this package API.
                proof.predicates()
            })
        assertNotNull(prepared.items.single().intentQuantityProvenance?.ingredientProvenance)

        val missing = base.prepareForReview(
            allowPropertyObligations = true, allowIngredientPredicates = true,
            allowTypedIngredientPredicates = true,
        )
        assertTrue(missing.items.isEmpty())
        assertEquals(listOf(base.instruction), missing.unresolvedText)

        val forged = valid.copy(ingredientObligations = listOf(correct.copy(memberIndices = listOf(1))))
            .prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true,
                allowTypedIngredientPredicates = true)
        assertTrue(forged.items.isEmpty())
        val default = valid.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true)
        assertTrue(default.items.isEmpty())
        for ((allowProperties, allowPredicates) in listOf(false to true, true to false)) {
            val invalidFlags = valid.prepareForReview(allowPropertyObligations = allowProperties,
                allowIngredientPredicates = allowPredicates, allowTypedIngredientPredicates = true)
            assertTrue("$allowProperties/$allowPredicates", invalidFlags.items.isEmpty())
            assertEquals(listOf(base.instruction), invalidFlags.unresolvedText)
        }
    }

    @Test
    fun receiptDecoderIsExactBoundedSortedAndRejectsDuplicates() {
        val instruction = "one soap; no fragrance in soap"
        val source = "no fragrance in soap"
        val start = instruction.indexOf(source)
        fun parse(memberIndices: String, predicates: String = "[{\"operator\":\"absent\",\"operand\":\"fragrance\"}]") =
            JsonParser("""{"source":{"text":"$source","start_utf16":$start,"end_utf16":${start + source.length}},"member_indices":$memberIndices,"predicates":$predicates}""")
                .parseStrict()
        val receipt = GroceryIntentReviewV2IngredientObligation.parse(
            parse("[0]"), instruction,
        )
        assertEquals(listOf(0), receipt.memberIndices)
        assertEquals("fragrance", receipt.predicates.single().operand)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2IngredientObligation.parse(parse("[1,0]"), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2IngredientObligation.parse(parse("[0,0]"), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2IngredientObligation.parse(parse("[0]", "[{\"operator\":\"ABSENT\",\"operand\":\"fragrance\"}]"), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2IngredientObligation.parse(parse("[0]", "[{\"operator\":\"absent\",\"operand\":\"fragrance\",\"extra\":1}]"), instruction)
        }
    }

    @Test
    fun optionalReceiptDistinguishesAbsentFromNegotiatedEmpty() {
        val absent = parseDraft("one soap", null)
        val negotiatedEmpty = parseDraft("one soap", "[]")
        assertNull(absent.ingredientObligations)
        assertNotNull(negotiatedEmpty.ingredientObligations)
        val prepared = negotiatedEmpty.prepareForReview(allowPropertyObligations = true,
            allowIngredientPredicates = true, allowTypedIngredientPredicates = true)
        assertEquals(1, prepared.items.size)
        assertNull(prepared.items.single().ingredientProvenance)

        val source = "no fragrance in soap"
        val instruction = "one soap; $source"
        val start = instruction.indexOf(source)
        val receipt = """[{"source":{"text":"$source","start_utf16":$start,"end_utf16":${start + source.length}},"member_indices":[0],"predicates":[{"operator":"absent","operand":"fragrance"}]}]"""
        assertEquals(1, requireNotNull(parseDraft(instruction, receipt).ingredientObligations).size)
        assertThrows(IllegalArgumentException::class.java) {
            parseDraft(instruction, receipt.dropLast(1) + "," + receipt.drop(1))
        }
    }

    private fun compile(
        instruction: String,
        row: GroceryIntentPropertyRow,
        allowTyped: Boolean = false,
    ) = compileGroceryIntentPropertyObligations(
        instruction, listOf(row), allowIngredientPredicates = true,
        allowTypedIngredientPredicates = allowTyped,
    )

    private fun propertyRow(
        instruction: String,
        primary: String,
        identity: String,
        excluded: List<String>,
    ): GroceryIntentPropertyRow {
        val start = instruction.indexOf(primary)
        return GroceryIntentPropertyRow(
            rowIndex = 0,
            primary = GroceryIntentReviewV2Quote(primary, start, start + primary.length),
            sourceIdentity = identity,
            name = identity,
            excluded = excluded,
        )
    }

    private fun review(instruction: String, excluded: List<String>): GroceryIntentReviewV2 {
        val primary = "one soap"
        val end = primary.length
        val item = GroceryIntentReviewV2Item(
            name = "soap", quantity = BigDecimal.ONE, quantityUnit = "count", packValue = null,
            packUnit = "unspecified", packaging = null, required = emptyList(), excluded = excluded,
            sourceText = primary, supportingText = emptyList(), needsClarification = false,
            quantityText = "one", packText = null,
        )
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
            sourceItemCount = 1,
            readyRows = listOf(GroceryIntentReviewV2ReadyRow(
                0, item, GroceryIntentReviewV2Evidence(
                    GroceryIntentReviewV2Quote(primary, 0, end), emptyList(),
                ), "stated", "matched",
            )),
            blockedRows = emptyList(),
        )
    }

    private fun parseDraft(instruction: String, ingredientReceipts: String?): GroceryIntentReviewV2 {
        val primary = "one soap"
        val end = primary.length
        val optional = ingredientReceipts?.let { ",\"ingredient_obligations\":$it" }.orEmpty()
        val body = """{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"$instruction","source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":"soap","quantity":1,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":"$primary","supporting_text":[],"needs_clarification":false,"quantity_text":"one","pack_text":null},"evidence":{"primary":{"text":"$primary","start_utf16":0,"end_utf16":$end},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}],"blocked_rows":[]$optional}"""
        val root = JsonParser(body).parseStrict() as JsonValue.Obj
        return GroceryIntentReviewV2.parse(root.members, instruction)
    }
}
