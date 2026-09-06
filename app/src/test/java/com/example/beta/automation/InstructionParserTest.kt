package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionParserTest {
    @Test
    fun parserVersion_tracksLearningContract() {
        assertEquals("2026.09.05.2", InstructionParser.PARSER_VERSION)
    }

    @Test
    fun parse_keepsMaggiMinuteDescriptorOutOfQuantities() {
        for (descriptor in listOf("2 Minute", "2-Minute", "two minute")) {
            val items = InstructionParser.parse("Tata Crystal Salt 1 kg and MAGGI $descriptor Noodles 280 g")
            assertEquals(2, items.size)
            assertEquals(Quantity.Weight(1000), items[0].quantity)
            assertEquals(Quantity.Weight(280), items[1].quantity)
            assertEquals("maggi 2 minute noodles", items[1].query)
        }
    }

    @Test
    fun parse_preservesExplicitCountBeforeMaggiMinuteDescriptor() {
        val items = InstructionParser.parse("2 Maggi 2 Minute noodles and 3 apples")
        assertEquals(2, items.size)
        assertEquals("maggi 2 minute noodles", items[0].query)
        assertEquals(Quantity.Count(2), items[0].quantity)
        assertEquals(Quantity.Count(3), items[1].quantity)
    }

    @Test
    fun parse_stripsLeadingCommandPrefixForSingleItem() {
        val items = InstructionParser.parse("get me apples")

        assertEquals(1, items.size)
        assertEquals(ParsedItem(rawText = "apples", query = "apples"), items[0])
    }

    @Test
    fun parse_splitsCommaSeparatedMultiItemOrders() {
        val items = InstructionParser.parse("order apples, bananas,  milk ")

        assertEquals(3, items.size)
        assertEquals(ParsedItem(rawText = "apples", query = "apples"), items[0])
        assertEquals(ParsedItem(rawText = "bananas", query = "bananas"), items[1])
        assertEquals(ParsedItem(rawText = "milk", query = "milk"), items[2])
    }

    @Test
    fun parse_lowercasesQueriesForSearchButKeepsTrimmedRawText() {
        val items = InstructionParser.parse("Order Butter , Apple , Notebook")

        assertEquals(3, items.size)
        assertEquals("Butter", items[0].rawText)
        assertEquals("butter", items[0].query)
        assertEquals("Apple", items[1].rawText)
        assertEquals("apple", items[1].query)
        assertEquals("Notebook", items[2].rawText)
        assertEquals("notebook", items[2].query)
    }

    @Test
    fun parse_dropsEmptyItemsAndPreservesPlainInput() {
        val items = InstructionParser.parse("  add   bread, ,  eggs ,, ")

        assertEquals(2, items.size)
        assertEquals(ParsedItem(rawText = "bread", query = "bread"), items[0])
        assertEquals(ParsedItem(rawText = "eggs", query = "eggs"), items[1])
    }

    @Test
    fun parse_extractsLeadingCountForSingleItem() {
        val items = InstructionParser.parse("order 2 butter")

        assertEquals(1, items.size)
        assertEquals("butter", items[0].query)
        assertEquals(Quantity.Count(2), items[0].quantity)
        assertEquals("2 butter", items[0].backendInputText())
    }

    @Test
    fun parse_keepsTrailingPieceAndPackCountsInsideProductVariant() {
        val items = InstructionParser.parse(
            "Pampers Premium Care Pant Style Baby Diapers small medium 70 pieces, tissues 2 packs"
        )

        assertEquals(2, items.size)
        assertEquals(
            listOf(
                "pampers premium care pant style baby diapers small medium 70 pieces",
                "tissues 2 packs",
            ),
            items.map { it.query },
        )
        assertEquals(listOf(Quantity.Default, Quantity.Default), items.map { it.quantity })
    }

    @Test
    fun parse_translatesHindiAndKannadaProductTermsToEnglishQueries() {
        val hindiItems = InstructionParser.parse("order दूध, मक्खन और पेंसिल")
        val kannadaItems = InstructionParser.parse("order ಹಾಲು, ಬೆಣ್ಣೆ ಮತ್ತು ಪೆನ್ಸಿಲ್")

        assertEquals(listOf("milk", "butter", "pencil"), hindiItems.map { it.query })
        assertEquals(listOf("milk", "butter", "pencil"), kannadaItems.map { it.query })
    }

    @Test
    fun parse_stripsTrailingCartDirectionPhrases() {
        val singleItem = InstructionParser.parse("add vicks to cart")
        val mixedItems = InstructionParser.parse("please add milk to my cart, butter into the cart")

        assertEquals(listOf("vicks"), singleItem.map { it.query })
        assertEquals(listOf("milk", "butter"), mixedItems.map { it.query })
    }
}
