package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionParserTest {
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
}
