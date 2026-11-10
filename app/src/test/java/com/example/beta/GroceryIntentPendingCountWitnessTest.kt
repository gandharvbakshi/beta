package com.example.beta

import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test

class GroceryIntentPendingCountWitnessTest {
    private fun item(source: String = "2 milk", name: String = "milk", count: Int = 2) =
        ParsedItem(source, name, Quantity.Count(count), strictMatchPhrase = name)

    @Test fun localCountStaysNonExecutableForEveryUnresolvedSurroundingScope() {
        val raw = item()
        listOf(
            "For the meeting get 2 milk",
            "not 2 milk",
            "instead of 2 milk",
            "only sugar free 2 milk",
            "2 milk, only if available",
            "2 milk, no added sugar",
            "2 milk, total should be under 100",
        ).forEach { instruction ->
            val witness = requireNotNull(GroceryIntentPendingCountWitness.compile(instruction, 0, raw))
            assertEquals(2, witness.requestedCount)
            assertTrue(witness.matches(instruction, 0, raw))
            // A witness never modifies either the input or its existing result.
            assertNull(raw.intentQuantityProvenance)
            if (!groceryIntentSourceClauseContextProven(instruction, raw.rawText, raw.rawText.length)) {
                val held = raw.withLegacySourceEvidence(instruction)
                assertTrue(held.requiresIntentQuantityProof)
                assertNull(held.intentQuantityProvenance)
                val heldWitness = requireNotNull(GroceryIntentPendingCountWitness.compile(instruction, 0, held))
                assertTrue(heldWitness.matches(instruction, 0, held))
                assertNotNull(swiggyIntentQuantityIssue(held, SwiggyMcpClient.Recommendations(emptyList())))
            }
        }
    }

    @Test fun legacySourceProofStillRequiresItsOriginalContextAndRetainsControls() {
        val raw = item()
        val proven = raw.withLegacySourceEvidence("please get 2 milk")
        assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, proven.intentQuantityProvenance?.origin)
        assertEquals(Quantity.Count(2), proven.intentQuantityProvenance?.resolvedQuantity)
        assertNull(GroceryIntentPendingCountWitness.compile("please get 2 milk", 0, proven))
        val held = raw.withLegacySourceEvidence("For the meeting get 2 milk")
        assertNull(held.intentQuantityProvenance)
        assertTrue(held.requiresIntentQuantityProof)
        assertEquals(held, held.withLegacySourceEvidence("please get 2 milk"))
    }

    @Test fun originalBoundaryAndNumericIdentityChecksCannotBeBypassed() {
        listOf(
            Triple("2 milk 3", "2 milk", "milk"),
            Triple("2 milk chocolate", "2 milk", "milk"),
            Triple("2 milk and 2 milk", "2 milk", "milk"),
            Triple("12 milk", "2 milk", "milk"),
            Triple("2 milk and bread", "2 milk and bread", "milk"),
            Triple("2 milk 500 ml", "2 milk 500 ml", "milk"),
        ).forEach { (instruction, source, name) ->
            assertNull(instruction, GroceryIntentPendingCountWitness.compile(instruction, 0, item(source, name)))
        }
        assertNull(GroceryIntentPendingCountWitness.compile("2 milk", 0, item(count = 3)))
        assertNull(GroceryIntentPendingCountWitness.compile("2 milk", -1, item()))
        assertNull(GroceryIntentPendingCountWitness.compile("2 milk", 50, item()))
    }

    @Test fun witnessInvalidatesForEntireInstructionRowAndEveryRelevantItemChange() {
        val instruction = "For the meeting get 2 milk"
        val avoids = mutableListOf<String>()
        val raw = item().copy(avoidPhrases = avoids)
        val witness = requireNotNull(GroceryIntentPendingCountWitness.compile(instruction, 0, raw))
        assertTrue(witness.matches(instruction, 0, raw))
        assertFalse(witness.matches("$instruction, only if cheap", 0, raw))
        assertFalse(witness.matches(instruction, 1, raw))
        listOf(raw.copy(quantity = Quantity.Count(3)), raw.copy(query = "flavoured milk"),
            raw.copy(strictMatchPhrase = "flavoured milk"), raw.copy(rawText = "milk"),
            raw.copy(retailPackCount = true), raw.copy(avoidPhrases = listOf("sugar")),
            raw.copy(requiresIntentQuantityProof = true), raw.copy(unprovenIdentityScope = true),
        ).forEach { assertFalse(witness.matches(instruction, 0, it)) }
        avoids += "sugar"
        assertFalse(witness.matches(instruction, 0, raw))
        assertFalse(witness.toString().contains("milk"))
        assertFalse(witness.toString().contains("meeting"))
    }

    @Test fun specializedAndAmbiguousPathsCannotBecomeOrdinaryCountWitnesses() {
        val raw = item()
        listOf(raw.copy(quantitySignal = "ambiguous"), raw.copy(logicalPieceCount = true),
            raw.copy(unprovenIdentityScope = true), raw.copy(quantity = Quantity.Default),
            raw.copy(quantity = Quantity.Volume(500)),
        ).forEach { assertNull(GroceryIntentPendingCountWitness.compile("2 milk", 0, it)) }
    }
}
