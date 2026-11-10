package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SwiggyTypedIngredientContractTest {
    @Test
    fun payloadIsStrictAndUsesStableLowercaseOperators() {
        val absent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")
        val noAddition = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")
        assertEquals("absent", absent.toPayload()["operator"])
        assertEquals("no_addition", noAddition.toPayload()["operator"])
        assertEquals(absent, IngredientPredicate.fromPayload(absent.toPayload()))
        assertEquals(noAddition, IngredientPredicate.fromPayload(noAddition.toPayload()))
        assertEquals("fragrance", absent.literalExclusion())
        assertEquals("added salt", noAddition.literalExclusion())
        assertFalse(absent.toString().contains("fragrance"))

        listOf(
            mapOf("operator" to "ABSENT", "operand" to "salt"),
            mapOf("operator" to "absent", "operand" to "salt", "extra" to true),
            mapOf("operator" to "no", "operand" to "salt"),
            mapOf("operator" to "absent", "operand" to 7),
            mapOf("operator" to "absent", "operand" to "not salt"),
        ).forEach { assertThrows(IllegalArgumentException::class.java) { IngredientPredicate.fromPayload(it) } }
    }

    @Test
    fun typedProjectionIsOptInExactAndLegacyProjectionRemainsClosed() {
        val fragrance = IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")
        val noAddition = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "sugar")
        assertTrue(ingredientExclusionPredicates(listOf("fragrance")).isEmpty())
        assertEquals(setOf(fragrance, noAddition), ingredientExclusionPredicates(
            listOf("Fragrance", "Added_Sugar"), allowTyped = true,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            requireIngredientPredicateProjection(listOf(fragrance), listOf("fragrance"))
        }
        assertEquals(listOf("fragrance"), requireIngredientPredicateProjection(
            listOf(fragrance), listOf("Fragrance"), allowTyped = true,
        ))
        assertEquals(listOf("added sugar"), requireIngredientPredicateProjection(
            listOf(noAddition), listOf("added-sugar"), allowTyped = true,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            requireIngredientPredicateProjection(listOf(noAddition), listOf("sugar"), allowTyped = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireIngredientPredicateProjection(listOf(fragrance), listOf("scent-free"), allowTyped = true)
        }
    }

    @Test
    fun sameSkuMatcherRequiresCompletePerFieldClaimsAndUsesJoinOnlyToVeto() {
        val absent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")
        val noAddedSugar = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "sugar")
        assertTrue(swiggyIngredientPredicatesMatchFields(listOf("Soap no fragrance"), listOf(absent)))
        assertTrue(swiggyIngredientPredicatesMatchFields(listOf("No added sugar"), listOf(noAddedSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(emptyList<String>(), listOf(absent)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("Soap"), listOf(absent)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No added", "sugar"), listOf(noAddedSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No", "added sugar"), listOf(noAddedSugar)))
    }

    @Test
    fun contradictoryQualifiedAndMixedStrengthFieldsVeto() {
        val absentSugar = IngredientPredicate(IngredientPredicateOperator.ABSENT, "sugar")
        val noAddedSugar = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "sugar")
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No sugar", "Sugar"), listOf(absentSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No sugar", "possibly"), listOf(absentSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No added sugar", "Sugar free"), listOf(noAddedSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No sugar", "No added sugar"), listOf(absentSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No sugar"), listOf(noAddedSugar)))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("No added sugar"), listOf(absentSugar)))
    }

    @Test
    fun matcherIsBoundedRejectsMalformedInputsAndDoesNotMutate() {
        val fields = mutableListOf("No fragrance", "No added sugar")
        val predicates = mutableListOf(
            IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance"),
            IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "sugar"),
        )
        val beforeFields = fields.toList()
        val beforePredicates = predicates.toList()
        assertTrue(swiggyIngredientPredicatesMatchFields(fields, predicates))
        assertTrue(swiggyIngredientPredicatesMatchFields(fields.reversed(), predicates.reversed()))
        assertFalse(swiggyIngredientPredicatesMatchFields(fields + "\u0001", predicates))
        assertFalse(swiggyIngredientPredicatesMatchFields(listOf("x".repeat(501)), listOf(predicates.first())))
        assertFalse(swiggyIngredientPredicatesMatchFields(List(17) { "No fragrance" }, listOf(predicates.first())))
        assertFalse(swiggyIngredientPredicatesMatchFields(fields, List(17) { predicates.first() }))
        @Suppress("UNCHECKED_CAST")
        val malformed = listOf("No fragrance", 7) as List<String>
        assertFalse(swiggyIngredientPredicatesMatchFields(malformed, listOf(predicates.first())))
        assertEquals(beforeFields, fields)
        assertEquals(beforePredicates, predicates)
    }

    @Test
    fun annotatedClaimsCannotBorrowCertaintyFromCleanDuplicateFields() {
        listOf(
            IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance") to "Fragrance-free",
            IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "sugar") to "No added sugar",
        ).forEach { (predicate, claim) ->
            assertTrue(swiggyIngredientPredicatesMatchFields(listOf(claim), listOf(predicate)))
            listOf("*", "\u2020", "\u2021").forEach { marker ->
                assertFalse(swiggyHasIngredientPredicate(claim + marker, predicate))
                assertFalse(swiggyIngredientPredicatesMatchFields(listOf(claim + marker), listOf(predicate)))
                assertFalse(swiggyIngredientPredicatesMatchFields(listOf(claim, claim + marker), listOf(predicate)))
                assertFalse(swiggyIngredientPredicatesMatchFields(listOf(claim, marker), listOf(predicate)))
            }
        }
    }
}
