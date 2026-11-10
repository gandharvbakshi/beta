package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryIntentPackagingEvidenceTest {
    @Test
    fun lexicalizedMatchboxProvesOnlyItsBoxPackaging(): Unit {
        assertEquals(setOf("box"), groceryIntentPackagingEvidence("1 matchbox", "matchbox", "box"))
        assertEquals(setOf("box"), groceryIntentPackagingEvidence("One matchbox.", "matchbox", "box"))
        assertEquals(setOf("box"), groceryIntentPackagingEvidence("a matchbox", "matchboxes", "box"))
        assertEquals(setOf("box"), groceryIntentPackagingEvidence("matchbox", "matchbox", "box"))
        assertEquals(setOf("box"), groceryIntentPackagingEvidence("3 matchboxes", "matchboxes", "box"))
        assertEquals(setOf("box"), groceryIntentPackagingEvidence("do matchbox", "matchbox", "box"))
    }

    @Test
    fun soapTikkiPhrasesProveABarOnlyForAnIndependentlyNamedSoap(): Unit {
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("1 sabun ki tikki", "soap", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("One sabun ki tikiya.", "sabun", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("sabun ki tikki", "soap", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("2 sabun ki tikki", "soap", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("3 sabun ki tikki", "soap", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("teen sabun ki tikiya", "sabun", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("chaar sabun ki tikki", "soap", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("barah sabun ki tikki", "soap", "bar"))
        assertEquals(setOf("bar"), groceryIntentPackagingEvidence("2 Soap Ki Tikki", "soap", "bar"))
    }

    @Test
    fun unrelatedOrExpandedSourcePhrasesCannotLendPackagingEvidence(): Unit {
        val noEvidence = listOf(
            Triple("1 aloo tikki", "potato", "bar"),
            Triple("1 potato tikki", "potato", "bar"),
            Triple("1 tikki", "soap", "bar"),
            Triple("1 soap", "soap", "bar"),
            Triple("1 sabun", "soap", "bar"),
            Triple("1 matchbox toy cars", "matchbox", "box"),
            Triple("1 matchbox and 1 candle", "matchbox", "box"),
            Triple("1 matchbox, unless available", "matchbox", "box"),
            Triple("please get 1 matchbox", "matchbox", "box"),
            Triple("not 1 matchbox", "matchbox", "box"),
            Triple("1 sabun ki tikki and 1 shampoo", "soap", "bar"),
            Triple("1 sabun ki tikki if cheap", "soap", "bar"),
            Triple("1 sabun ki tikki 250 g", "soap", "bar"),
            Triple("1 sabun ki tikkiyan", "soap", "bar"),
            Triple("0 matchbox", "matchbox", "box"),
            Triple("1000 matchboxes", "matchboxes", "box"),
            Triple("1.5 matchbox", "matchbox", "box"),
            Triple("1/2 matchbox", "matchbox", "box"),
            Triple("+2 matchbox", "matchbox", "box"),
            Triple("-2 matchbox", "matchbox", "box"),
            Triple("2-in-1 matchbox", "matchbox", "box"),
            Triple("twenty matchbox", "matchbox", "box"),
            Triple("dozen matchboxes", "matchboxes", "box"),
            Triple("do matchbox 250 g", "matchbox", "box"),
            Triple("1/matchbox", "matchbox", "box"),
            Triple("1. matchbox", "matchbox", "box"),
        )
        noEvidence.forEach { (source, name, packaging) ->
            assertEquals("$source => $name / $packaging", emptySet<String>(),
                groceryIntentPackagingEvidence(source, name, packaging))
        }
    }

    @Test
    fun wrongProposedIdentityOrPackagingCannotBorrowTheAlias(): Unit {
        assertEquals(emptySet<String>(), groceryIntentPackagingEvidence("1 matchbox", "matchbox", null))
        val cases = listOf(
            Triple("1 matchbox", "Matchbox toy cars", "box"),
            Triple("1 matchbox", "brand matchbox", "box"),
            Triple("1 matchbox", "match", "box"),
            Triple("1 matchbox", "matchbox", "tub"),
            Triple("1 matchbox", "matchbox", "pack"),
            Triple("1 sabun ki tikki", "brand soap", "bar"),
            Triple("1 sabun ki tikki", "soap dispenser", "bar"),
            Triple("1 sabun ki tikki", "potato", "bar"),
            Triple("1 sabun ki tikki", "soap", "tub"),
            Triple("1 sabun ki tikki", "soap", "pack"),
        )
        cases.forEach { (source, name, packaging) ->
            assertEquals("$source => $name / $packaging", emptySet<String>(),
                groceryIntentPackagingEvidence(source, name, packaging))
        }
    }
}
