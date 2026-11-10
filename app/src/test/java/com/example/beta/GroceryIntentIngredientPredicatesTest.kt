package com.example.beta

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentIngredientPredicatesTest {
    @Test fun pinnedSourceRelationFixtureMatchesExactSelectorsAndTypedOperands() {
        val relativePath = "scripts/fixtures/intent_predicate_relations_v1.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile) ?: error("Cannot find source predicate fixture at $relativePath")
        val source = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("6929edf18824ff250b57278e31b42a7fc54de78252eab5eb8d06a6b5f7786ef7", sha256)
        val document = (JsonParser(source).parseStrict() as JsonValue.Obj).members
        assertEquals("ingredient-source-relations-v1", (document.getValue("version") as JsonValue.Str).value)
        val cases = (document.getValue("cases") as JsonValue.Arr).items
        assertEquals(18, cases.size)
        val ids = mutableSetOf<String>()
        cases.forEach { value ->
            val case = (value as JsonValue.Obj).members
            fun string(key: String) = (case.getValue(key) as JsonValue.Str).value
            val id = string("id")
            assertTrue("Duplicate source fixture ID: $id", ids.add(id))
            val actual = ingredientPredicateClause(string("text"))
            if (case.getValue("selector") == JsonValue.Null) {
                assertNull(id, actual)
            } else {
                val clause = requireNotNull(actual) { "Missing source relation: $id" }
                assertEquals(id, string("selector"), clause.selector)
                assertNull(id, clause.issueCode)
                val expected = (case.getValue("predicates") as JsonValue.Arr).items.map { predicateValue ->
                    val predicate = (predicateValue as JsonValue.Obj).members
                    IngredientPredicate(
                        IngredientPredicateOperator.valueOf((predicate.getValue("operator") as JsonValue.Str).value),
                        (predicate.getValue("operand") as JsonValue.Str).value,
                    )
                }
                assertEquals(id, expected, clause.predicates)
            }
        }
    }

    @Test fun completeSourceRelationsRetainLiteralOperatorAndSelector() {
        for (text in listOf("no salt added to butter", "no added salt in butter", "butter without added salt")) {
            val clause = requireNotNull(ingredientPredicateClause(text))
            assertEquals(text, "butter", clause.selector)
            assertNull(clause.issueCode)
            assertEquals(listOf(IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")), clause.predicates)
        }
        for (text in listOf("no salt in butter", "butter without salt", "butter mein salt nahi", "butter mein namak nahin")) {
            val clause = requireNotNull(ingredientPredicateClause(text))
            assertEquals(text, "butter", clause.selector)
            assertNull(clause.issueCode)
            assertEquals(listOf(IngredientPredicate(IngredientPredicateOperator.ABSENT, "salt")), clause.predicates)
        }
        val article = requireNotNull(ingredientPredicateClause("NO ADDED SALT IN the Butter"))
        assertEquals("Butter", article.selector)
        assertEquals("salt", article.predicates.single().operand)
    }

    @Test fun coordinatedOperandsUseOnlyTheSingleOriginalNegativeRelation() {
        for (text in listOf("no maida or palm oil in biscuits", "biscuits mein maida ya palm oil nahi")) {
            val clause = requireNotNull(ingredientPredicateClause(text))
            assertNull(text, clause.issueCode)
            assertEquals(listOf("maida", "palm oil"), clause.predicates.map { it.operand })
            assertTrue(clause.predicates.all { it.operator == IngredientPredicateOperator.ABSENT })
        }
        val four = requireNotNull(ingredientPredicateClause("no salt or maida or chemicals or preservatives in biscuits"))
        assertNull(four.issueCode)
        assertEquals(4, four.predicates.size)
        for (text in listOf("no namak or salt in butter", "no salt and maida in biscuits",
            "no salt or maida or chemicals or preservatives or fragrance in biscuits",
            "no low salt in butter", "no salt with maida in biscuits", "no salt except maida in biscuits")) {
            val invalid = requireNotNull(ingredientPredicateClause(text))
            assertEquals(text, "ingredient_predicate_unproven", invalid.issueCode)
            assertTrue(invalid.predicates.isEmpty())
        }
    }

    @Test fun sourceOperandCanonicalizationUsesNounsNotSearchOrPropertyAliases() {
        val salt = requireNotNull(ingredientPredicateClause("butter without namak"))
        assertEquals("salt", salt.predicates.single().operand)
        for (operand in listOf("palak", "nariyal pani", "elaichi banana", "lean milk", "ready made tea", "aam")) {
            val clause = requireNotNull(ingredientPredicateClause("biscuits without $operand"))
            val expected = when (operand) {
                "palak" -> "spinach"
                "nariyal pani" -> "coconut water"
                else -> operand
            }
            assertNull(operand, clause.issueCode)
            assertEquals(expected, clause.predicates.single().operand)
        }
        for (text in listOf("no salt", "without salt", "no salt in butter.", "\"no salt in butter\"",
            "no salt\nin butter", "no salt\u00a0in butter", "no नमक in butter", "no salt in mİlk",
            "no 2g salt in butter", "no palm/oil in biscuits")) {
            assertNull(text, ingredientPredicateClause(text))
        }
        val redacted = requireNotNull(ingredientPredicateClause("private butter without fragrance")).toString()
        assertFalse(redacted.contains("private"))
        assertFalse(redacted.contains("butter"))
        assertFalse(redacted.contains("fragrance"))
    }

    @Test fun legacyProjectionRequiresExactOperatorRetentionAndSupportedAdapter() {
        val absent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "salt")
        val noAddition = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")
        assertEquals(setOf(absent, noAddition), ingredientExclusionPredicates(listOf(" Salt ", "ADDED_SALT", "added-salt")))
        assertEquals(emptySet<IngredientPredicate>(), ingredientExclusionPredicates(listOf("sugar", "fragrance", "no salt")))
        assertEquals(listOf("added salt"), requireIngredientPredicateProjection(listOf(noAddition), listOf("added_salt")))
        assertEquals(listOf("salt"), requireIngredientPredicateProjection(listOf(absent), listOf("salt")))
        for ((predicate, retained) in listOf(absent to listOf("added_salt"), noAddition to listOf("salt"), absent to emptyList())) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                requireIngredientPredicateProjection(listOf(predicate), retained)
            }
            assertEquals("ingredient_predicate_not_retained", error.message)
        }
        for (operand in listOf("fragrance", "sugar")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                requireIngredientPredicateProjection(listOf(IngredientPredicate(IngredientPredicateOperator.ABSENT, operand)), listOf(operand))
            }
            assertEquals("ingredient_predicate_unenforced", error.message)
        }
    }

    @Test fun ingredientClauseCapabilityIsOptInAndDoesNotCreatePropertyKeys() {
        val instruction = "1 butter; No salt added to butter."
        val row = row(instruction, "1 butter", "butter", excluded = listOf("added_salt"))
        assertFalse(hasGroceryIntentPropertyObligationClause(instruction))
        assertTrue(hasGroceryIntentPropertyObligationClause(instruction, allowIngredientPredicates = true))
        assertTrue(compileGroceryIntentPropertyObligations(instruction, listOf(row)).obligations.isEmpty())
        val compiled = compileGroceryIntentPropertyObligations(instruction, listOf(row), allowIngredientPredicates = true)
        assertEquals(emptyList<String>(), compiled.issueCodes)
        val proof = compiled.obligations.single()
        assertEquals(listOf(0), proof.memberIndices)
        assertEquals("No salt added to butter", proof.source.text)
        assertEquals(proof.source.text, instruction.substring(proof.source.startUtf16, proof.source.endUtf16))
        assertTrue(proof.required.isEmpty() && proof.excluded.isEmpty())
        assertEquals(listOf(IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")), proof.ingredientPredicates)
        assertNull(groceryIntentWholeProperty("added salt"))
        assertEquals("low fat", groceryIntentPropertyPhrase("lowfat"))
        assertFalse(proof.toString().contains("butter"))
    }

    @Test fun compilerRejectsDroppedWrongOperatorOrUnsupportedPredicates() {
        val instruction = "1 butter; No salt added to butter"
        for (excluded in listOf(emptyList(), listOf("salt"))) {
            val result = compileGroceryIntentPropertyObligations(instruction,
                listOf(row(instruction, "1 butter", "butter", excluded = excluded)), allowIngredientPredicates = true)
            assertEquals(listOf("ingredient_predicate_not_retained"), result.issueCodes)
            assertTrue(result.obligations.isEmpty())
        }
        for ((clause, excluded) in listOf("no fragrance in butter" to listOf("fragrance"),
            "no sugar in butter" to listOf("sugar"), "no salt aur maida in butter" to listOf("salt", "maida"))) {
            val source = "1 butter; $clause"
            val result = compileGroceryIntentPropertyObligations(source,
                listOf(row(source, "1 butter", "butter", excluded = excluded)), allowIngredientPredicates = true)
            assertEquals(clause, listOf("ingredient_predicate_unenforced"), result.issueCodes)
            assertTrue(result.obligations.isEmpty())
        }
    }

    @Test fun compilerBindsEveryCoordinatedOperandToOneOriginalTarget() {
        val instruction = "1 biscuits, 1 milk; no maida or palm oil in biscuits"
        val rows = listOf(row(instruction, "1 biscuits", "biscuits", excluded = listOf("maida", "palm_oil")),
            row(instruction, "1 milk", "milk", index = 1))
        val result = compileGroceryIntentPropertyObligations(instruction, rows, allowIngredientPredicates = true)
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(listOf(0), result.obligations.single().memberIndices)
        assertEquals(listOf("maida", "palm oil"), result.obligations.single().ingredientPredicates.map { it.operand })
        val borrowed = compileGroceryIntentPropertyObligations(instruction,
            listOf(rows.first(), rows.last().copy(excluded = listOf("maida"))), allowIngredientPredicates = true)
        assertEquals(listOf("ingredient_predicate_scope_mismatch"), borrowed.issueCodes)
        assertTrue(borrowed.obligations.isEmpty())
        val dropped = compileGroceryIntentPropertyObligations(instruction,
            listOf(rows.first().copy(excluded = listOf("maida")), rows.last()), allowIngredientPredicates = true)
        assertEquals(listOf("ingredient_predicate_not_retained"), dropped.issueCodes)
    }

    @Test fun scopedIngredientPredicateCannotShelterExtraExclusionOnAnyRow() {
        val instruction = "1 biscuits, 1 milk; no maida in biscuits"
        val originalRows = listOf(
            row(instruction, "1 biscuits", "biscuits", excluded = listOf("maida")),
            row(instruction, "1 milk", "milk", index = 1),
        )
        val baseline = compileGroceryIntentPropertyObligations(instruction, originalRows, allowIngredientPredicates = true)
        assertEquals(emptyList<String>(), baseline.issueCodes)
        assertEquals(listOf(0), baseline.obligations.single().memberIndices)
        for (changedIndex in originalRows.indices) {
            val rows = originalRows.mapIndexed { index, original ->
                if (index == changedIndex) original.copy(excluded = original.excluded + "palm_oil") else original
            }
            val result = compileGroceryIntentPropertyObligations(instruction, rows, allowIngredientPredicates = true)
            assertEquals("extra exclusion on row $changedIndex", listOf("ingredient_predicate_extra_exclusion"), result.issueCodes)
            assertTrue(result.obligations.isEmpty())
        }
    }

    @Test fun ingredientScopeStillAllowsSeparatelyOwnedLegacyNegativeProperty() {
        val instruction = "1 biscuits, 1 milk; no maida in biscuits; milk should not be organic"
        val result = compileGroceryIntentPropertyObligations(instruction, listOf(
            row(instruction, "1 biscuits", "biscuits", excluded = listOf("maida")),
            row(instruction, "1 milk", "milk", excluded = listOf("organic"), index = 1),
        ), allowIngredientPredicates = true)
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(listOf(listOf(0), listOf(1)), result.obligations.map { it.memberIndices })
        assertEquals(listOf("organic"), result.obligations.last().excluded)
        assertTrue(result.obligations.last().ingredientPredicates.isEmpty())
    }

    @Test fun ambiguousOrIncompleteOriginalScopeRevokesPredicateProofs() {
        val duplicate = "1 butter, 2 butter; no salt in butter"
        val duplicateResult = compileGroceryIntentPropertyObligations(duplicate, listOf(
            row(duplicate, "1 butter", "butter", excluded = listOf("salt")),
            row(duplicate, "2 butter", "butter", excluded = listOf("salt"), index = 1),
        ), allowIngredientPredicates = true)
        assertEquals(listOf("property_target_unproven"), duplicateResult.issueCodes)
        val compound = "1 butter, 1 peanut butter; no salt in butter"
        val compoundResult = compileGroceryIntentPropertyObligations(compound, listOf(
            row(compound, "1 butter", "butter", excluded = listOf("salt")),
            row(compound, "1 peanut butter", "peanut butter", index = 1),
        ), allowIngredientPredicates = true)
        assertEquals(listOf("property_scope_unknown"), compoundResult.issueCodes)
        for (instruction in listOf("1 butter organic; no salt in butter", "1 butter, 1 milk; no salt in butter")) {
            val result = compileGroceryIntentPropertyObligations(instruction,
                listOf(row(instruction, "1 butter", "butter", excluded = listOf("salt"))), allowIngredientPredicates = true)
            assertTrue(instruction, result.issueCodes.isNotEmpty())
            assertTrue(result.obligations.isEmpty())
        }
    }

    @Test fun independentPredicateScopesAndLegacyPropertiesRemainSeparate() {
        val instruction = "1 butter, 1 milk; no salt in butter; no salt in milk; milk should be organic"
        val result = compileGroceryIntentPropertyObligations(instruction, listOf(
            row(instruction, "1 butter", "butter", excluded = listOf("salt")),
            row(instruction, "1 milk", "milk", excluded = listOf("salt"), index = 1).copy(required = listOf("organic")),
        ), allowIngredientPredicates = true)
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(3, result.obligations.size)
        assertEquals(listOf(listOf(0), listOf(1), listOf(1)), result.obligations.map { it.memberIndices })
        assertTrue(result.obligations.take(2).all { it.required.isEmpty() && it.excluded.isEmpty() && it.ingredientPredicates.size == 1 })
        assertEquals(listOf("organic"), result.obligations.last().required)
        assertTrue(result.obligations.last().ingredientPredicates.isEmpty())
    }

    private fun row(instruction: String, primary: String, identity: String,
        excluded: List<String> = emptyList(), index: Int = 0): GroceryIntentPropertyRow {
        val start = instruction.indexOf(primary).also { require(it >= 0) }
        return GroceryIntentPropertyRow(index, GroceryIntentReviewV2Quote(primary, start, start + primary.length),
            identity, identity, excluded = excluded)
    }
}
