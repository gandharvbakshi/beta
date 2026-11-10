package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentConstraintSafetyTest {
    @Test
    fun unsupportedConstraintCannotHideInsideOrOutsideAnExactProductQuote() {
        listOf(
            "mat", "bina cheeni", "bagair namak", "jo mil jaaye", "kal subah", "shaam ko",
            "unless cheap", "except Amul", "sirf Amul", "halka", "thoda", "lagbhag",
            "by 5 pm", "without milk", "don't order", "Correction",
        ).forEach { constraint ->
            listOf(true, false).forEach { embedded ->
                val instruction = "1 biscuits $constraint"
                val source = if (embedded) instruction else "1 biscuits"
                val preparation = decode(instruction, source).prepareForReview()
                assertTrue("$constraint (embedded=$embedded)", preparation.items.isEmpty())
                assertTrue(preparation.excludedText.isNotEmpty())
                val gate = GroceryIntentReviewGate()
                gate.prepare(preparation.unresolvedText, preparation.excludedText)
                gate.acknowledge(true)
                assertFalse(gate.canApply())
            }
        }
    }

    @Test
    fun completeCountedLocalAvailabilitySourceRemainsReviewable() {
        val source = "one biscuit if available"
        val preparation = decode(source, source, name = "biscuit", quantityText = "one").prepareForReview()

        assertEquals(listOf(source), preparation.items.map { it.rawText })
        assertEquals(Quantity.Count(1), preparation.items.single().quantity)
        assertTrue(preparation.unresolvedText.isEmpty())
        assertTrue(preparation.excludedText.isEmpty())
        val gate = GroceryIntentReviewGate()
        gate.prepare(preparation.unresolvedText, preparation.excludedText)
        gate.acknowledge(true)
        assertTrue(gate.canApply())
    }

    @Test
    fun croppedCountedProductCannotBorrowLocalAvailabilityFromFullInstructionAfterAcknowledgement() {
        val instruction = "1 biscuits if available"
        val preparation = decode(instruction, "1 biscuits").prepareForReview()

        assertTrue(preparation.items.isEmpty())
        val gate = GroceryIntentReviewGate()
        gate.prepare(preparation.unresolvedText, preparation.excludedText)
        gate.acknowledge(true)
        assertFalse(gate.canApply())
    }

    @Test
    fun existingProtectedDietGrammarRemainsSeparateFromUnsupportedConstraints() {
        listOf("no added sugar", "no palm oil", "no salt", "without organic substitutions").forEach {
            assertFalse(it, intentV2HasCorrectionCue(it))
        }
        assertFalse(intentV2HasCorrectionCue("milk chocolate"))
        assertFalse(intentV2HasCorrectionCue("tomatoes"))
    }

    @Test
    fun unownedDietPhraseIsNotDiscardedAsCourtesy() {
        listOf(
            "no sugar", "no added sugar", "no salt", "no palm oil", "no maida",
            "no chemicals", "no preservatives", "no artificial colours", "no artificial flavours",
        ).forEach { restriction ->
            listOf("1 biscuits; $restriction", "bazaar se 1 biscuits le aana; $restriction").forEach { instruction ->
                val preparation = decode(instruction, "1 biscuits").prepareForReview()
                assertTrue(instruction, preparation.items.isEmpty())
                val gate = GroceryIntentReviewGate()
                gate.prepare(preparation.unresolvedText, preparation.excludedText)
                gate.acknowledge(true)
                assertFalse(gate.canApply())
            }
        }
    }

    @Test
    fun quotedNegativeIngredientMustSurviveInExclusions() {
        listOf("no sugar" to "sugar", "no salt" to "salt", "no added salt" to "added_salt",
            "no palm oil" to "palm_oil", "no maida" to "maida",
            "no chemicals" to "chemicals", "no preservatives" to "preservatives",
            "no artificial colours" to "artificial_colours", "no artificial flavours" to "artificial_flavours",
        ).forEach { (restriction, excluded) ->
            val source = "1 biscuits with $restriction"
            assertTrue(decode(source, source).prepareForReview().items.isEmpty())
            val preserved = decode(source, source, excluded).prepareForReview()
            assertTrue(restriction, preserved.items.size == 1)
            assertTrue(preserved.items.single().avoidPhrases.contains(excluded.replace('_', ' ')))
        }
    }

    private fun decode(instruction: String, source: String, excluded: String? = null,
        name: String = "biscuits", quantityText: String = "1"): GroceryIntentReviewV2 {
        // Exercise the actual wire decoder and preparation, not just a regex.
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2",
            "requires_review":true,"instruction":"$instruction","source_item_count":1,
            "ready_rows":[{"row_index":0,"item":{"name":"$name","quantity":1,
            "quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,
            "required":[],"excluded":[${excluded?.let { "\"$it\"" }.orEmpty()}],"source_text":"$source","supporting_text":[],
            "needs_clarification":false,"quantity_text":"$quantityText","pack_text":null},
            "evidence":{"primary":{"text":"$source","start_utf16":${instruction.indexOf(source)},"end_utf16":${instruction.indexOf(source) + source.length}},
            "supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}],"blocked_rows":[]},
            "model":"test","promptVersion":"v2-test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction).draft
    }
}
