package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentDraftTest {
    @Test
    fun parsesExactReviewOnlyContractAndPreservesCountPackAndConstraints() {
        val response = GroceryIntentResponse.parse(
            response(
                instruction = "Two 750 ml sugar free milk tetra packs",
                item = item(
                    name = "milk", quantity = "2", quantityUnit = "pack", packValue = "750", packUnit = "ml",
                    required = "\"sugar_free\",\"tetra_pack\"", excluded = "\"added_sugar\"",
                    sourceText = "Two 750 ml sugar free milk tetra packs",
                ),
            ),
            "Two 750 ml sugar free milk tetra packs",
        )

        val parsed = response.draft.toParsedItems().single()
        assertEquals("milk sugar free tetra pack 750 ml", parsed.query)
        assertEquals("milk sugar free tetra pack 750 ml", parsed.strictMatchPhrase)
        assertEquals(Quantity.Count(2), parsed.quantity)
        assertTrue(parsed.retailPackCount)
        assertEquals(null, parsed.quantitySignal)
        assertEquals(null, swiggyMcpItemValidationMessage("", listOf(parsed)))
        assertEquals(listOf("added sugar"), parsed.avoidPhrases)
        assertEquals("model-x", response.model)
        assertEquals(mapOf("input_tokens" to 3.0, "output_tokens" to 5.0), response.usage)
    }

    @Test
    fun convertsOnlyExactlyRepresentableFractionalMeasures() {
        val kg = parsed("1.5 kg rice", quantity = "1.5", quantityUnit = "kg", itemName = "rice").toParsedItems().single()
        assertEquals(Quantity.Weight(1500), kg.quantity)
        val literPack = parsed(
            "juice 0.5 l one bottle", quantity = "1", quantityUnit = "count", packValue = "0.5", packUnit = "l",
        ).toParsedItems().single()
        assertEquals("juice 500 ml", literPack.strictMatchPhrase)
        assertThrows(IllegalArgumentException::class.java) {
            parsed("0.5 g spice", quantity = "0.5", quantityUnit = "g")
        }
    }

    @Test
    fun representsPieceCountAsPackConstraintNotRequestedCartQuantity() {
        val parsed = parsed(
            "one pack of 25 apples",
            quantity = "1",
            quantityUnit = "pack",
            packValue = "25",
            packUnit = "piece",
            itemName = "apple",
        ).toParsedItems().single()

        assertEquals("apple 25 pieces", parsed.query)
        assertEquals(parsed.query, parsed.strictMatchPhrase)
        assertEquals(Quantity.Count(1), parsed.quantity)
        assertTrue(parsed.retailPackCount)
        assertEquals(null, swiggyMcpItemValidationMessage("", listOf(parsed)))
    }

    @Test
    fun preservesRequestedQuantityOfMultiplePacksOfPieces() {
        val parsed = parsed(
            "two packs of 25 apples",
            quantity = "2",
            quantityUnit = "pack",
            packValue = "25",
            packUnit = "piece",
            itemName = "apple",
        ).toParsedItems().single()

        assertEquals("apple 25 pieces", parsed.query)
        assertEquals(Quantity.Count(2), parsed.quantity)
        assertTrue(parsed.retailPackCount)
    }

    @Test
    fun rejectsFractionalPerPackPieceCountInsteadOfRounding() {
        assertThrows(IllegalArgumentException::class.java) {
            parsed(
                "one pack of 2.5 apples",
                quantity = "1",
                quantityUnit = "pack",
                packValue = "2.5",
                packUnit = "piece",
                itemName = "apple",
            )
        }
    }

    @Test
    fun preservesUnspecifiedQuantityAndDoesNotInventDefaultCount() {
        val item = parsed("some rice", quantity = "null", quantityUnit = "unspecified").toParsedItems().single()
        assertEquals(Quantity.Default, item.quantity)
        assertFalse(item.retailPackCount)
    }

    @Test
    fun dietaryConstraintsCannotUseFuzzyProductMatching() {
        val item = parsed("organic rice", required = "\"organic\"").toParsedItems().single()
        assertEquals(item.query, item.strictMatchPhrase)
        assertFalse(swiggyMatchesProductIdentity(item, "ordinary rice"))
    }

    @Test
    fun descriptorsAndMeasuresInNameAreEnforcedEvenWithoutStructuredTags() {
        val size = parsed("one 10g coffee bar", itemName = "coffee bar 10g").toParsedItems().single()
        assertEquals(size.query, size.strictMatchPhrase)
        assertTrue(swiggyMatchesProductIdentity(size, "coffee bar 10 g"))
        assertFalse(swiggyMatchesProductIdentity(size, "coffee bar 50 g"))
        val descriptor = parsed("one certified ginger", itemName = "certified ginger").toParsedItems().single()
        assertTrue(swiggyMatchesProductIdentity(descriptor, "certified ginger"))
        assertFalse(swiggyMatchesProductIdentity(descriptor, "ginger"))
    }

    @Test
    fun ambiguousMedicineStopsBeforeMatching() {
        val draft = draft(
            "woh cough wali goli", item(
                name = "cough tablet", quantity = "null", quantityUnit = "unspecified",
                sourceText = "woh cough wali goli", needsClarification = true,
            ), modelNeedsClarification = true,
        )
        val error = assertThrows(IntentNeedsReviewException::class.java) { draft.toParsedItems() }
        assertTrue(error.affectedSourceText.contains("woh cough wali goli"))
    }

    @Test
    fun omittedProductRequiresCombinedReviewBeforeMatching() {
        val value = draft("please get 2 milk and one samosa", item("milk", "2", sourceText = "2 milk"))
        val error = assertThrows(IntentNeedsReviewException::class.java) { value.toParsedItems() }
        assertEquals(listOf("one samosa"), error.affectedSourceText)
        val preparation = value.prepareForReview()
        assertEquals(1, preparation.items.size)
        assertEquals(listOf("one samosa"), preparation.unresolvedText)
    }

    @Test
    fun rejectsArbitraryFieldsChangedInstructionAndNonQuoteSource() {
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("milk", item("milk", "1", sourceText = "milk", extra = ",\"action\":\"add\"")), "milk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("milk", item("milk", "1", sourceText = "milk")), "milk please")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("milk please", item("milk", "1", sourceText = "not present")), "milk please")
        }
    }

    @Test
    fun rejectsInvalidQuantitiesUnitsAndConflictingTags() {
        assertThrows(IllegalArgumentException::class.java) { parsed("rice", quantity = "null", quantityUnit = "count") }
        assertThrows(IllegalArgumentException::class.java) { parsed("rice", quantity = "21", quantityUnit = "count") }
        assertThrows(IllegalArgumentException::class.java) { parsed("rice", quantity = "1.5", quantityUnit = "pack") }
        assertThrows(IllegalArgumentException::class.java) {
            parsed("sugar free sweet", required = "\"sugar_free\"", excluded = "\"sugar_free\"")
        }
    }

    @Test
    fun itemLimitAndContractAreStrict() {
        val many = (0..50).joinToString(",") { item("rice", "null", quantityUnit = "unspecified", sourceText = "rice") }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("rice", many), "rice")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("rice", item("rice", "null", quantityUnit = "unspecified", sourceText = "rice"), contract = "v2"), "rice")
        }
    }

    @Test fun readyDrinkEvidenceInNameIsEnforcedWithoutRequiringDuplicateTag() {
        val instruction = "sugar-free ready-made tea"
        val result = draft(instruction, item(instruction, "null", quantityUnit = "unspecified",
            required = "\"sugar_free\"", sourceText = instruction)).toParsedItems().single()
        assertTrue(swiggyIdentityTokens(result.strictMatchPhrase.orEmpty()).containsAll(setOf("sugarfree", "readytodrink", "tea")))
        assertTrue(swiggyMatchesProductIdentity(result, "Sugar Free Ready to Drink Tea"))
        assertFalse(swiggyMatchesProductIdentity(result, "Sugar Free Tea Premix Powder"))
        assertFalse(swiggyMatchesProductIdentity(result, "Sweetened Ready to Drink Tea"))
    }

    private fun parsed(
        instruction: String,
        quantity: String = "1",
        quantityUnit: String = "count",
        packValue: String = "null",
        packUnit: String = "unspecified",
        required: String = "",
        excluded: String = "",
        itemName: String = instruction.substringBefore(' '),
    ): GroceryIntentDraft = draft(
        instruction,
        item(
            itemName, quantity, quantityUnit, packValue, packUnit,
            required, excluded, instruction,
        ),
    )

    private fun draft(instruction: String, item: String, modelNeedsClarification: Boolean = false): GroceryIntentDraft =
        GroceryIntentResponse.parse(response(instruction, item, modelNeedsClarification = modelNeedsClarification), instruction).draft

    private fun response(
        instruction: String,
        item: String,
        contract: String = "grocery-intent-v1",
        modelNeedsClarification: Boolean = false,
    ): String = """
        {"accepted":true,"draft":{"contract_version":"$contract","requires_review":true,
         "instruction":"$instruction","items":[$item],"model_needs_clarification":$modelNeedsClarification},
         "model":"model-x","promptVersion":"intent-v1","latencyMs":125,"usage":{"input_tokens":3,"output_tokens":5}}
    """.trimIndent()

    private fun item(
        name: String,
        quantity: String,
        quantityUnit: String = "count",
        packValue: String = "null",
        packUnit: String = "unspecified",
        required: String = "",
        excluded: String = "",
        sourceText: String = name,
        needsClarification: Boolean = false,
        extra: String = "",
    ): String = """
        {"name":"$name","quantity":$quantity,"quantity_unit":"$quantityUnit",
         "pack_value":$packValue,"pack_unit":"$packUnit","required":[$required],"excluded":[$excluded],
         "source_text":"$sourceText","needs_clarification":$needsClarification$extra}
    """.trimIndent().replace("\n", "")
}
