package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentContextReviewTest {
    @Test
    fun contextRowsKeepExactBackgroundOrderActionsAndOriginalRequestAccess() {
        val instruction = "hello, 2 milk, note, thanks"
        val preparation = prepare(instruction, listOf(
            "background" to "hello", "unresolved" to "note", "background" to "thanks",
        ))
        val backgroundActions = mutableListOf<Int>()
        var originalOpened = false

        val rows = groceryIntentContextReviewRows(preparation, backgroundActions::add) { originalOpened = true }

        assertEquals(4, rows.size)
        assertEquals("How Beta understood your request", rows[0].title)
        assertTrue(rows[0].isHeading)
        assertEquals(listOf("hello", "thanks"), rows.drop(1).take(2).map { it.title })
        rows.drop(1).take(2).forEach { row ->
            assertEquals("Used as background, not as an item", row.detail)
            assertEquals("This matters", row.action?.label)
            assertFalse(row.isHeading)
        }
        assertEquals("Original request", rows.last().title)
        assertEquals("View original request", rows.last().action?.label)

        rows[1].action?.onClick?.invoke()
        rows[2].action?.onClick?.invoke()
        rows.last().action?.onClick?.invoke()
        assertEquals(listOf(0, 2), backgroundActions)
        assertTrue(originalOpened)
    }

    @Test
    fun issueRowsSurfaceEachDistinctBlockingSourceOrConcernWithoutWaiverActions() {
        val instruction = "no sugar, 2 milk"
        val preparation = prepare(instruction, listOf("background" to "no sugar"))
        assertTrue(preparation.blockingIssues.isNotEmpty())
        val expectedQuotes = preparation.blockingIssues.flatMap { issue ->
            listOfNotNull(issue.source?.text, issue.concern?.text)
        }.distinct()
        assertTrue(expectedQuotes.isNotEmpty())

        val rows = groceryIntentContextIssueRows(preparation)

        assertEquals(expectedQuotes.map { "Beta can't follow this yet: '$it'" }, rows.map { it.title })
        assertTrue(rows.all { it.action == null && it.tone == SwiggyStepTone.AMBER })
        expectedQuotes.forEach { quote -> assertNotNull(rows.singleOrNull { it.title.endsWith("'$quote'") }) }
    }

    @Test
    fun issueRowsAreEmptyOnlyWhenThereAreNoBlockingIssues() {
        val instruction = "2 milk"
        val preparation = prepare(instruction, emptyList())
        assertTrue(preparation.blockingIssues.isEmpty())
        assertTrue(groceryIntentContextIssueRows(preparation).isEmpty())
        assertNull(groceryIntentContextReviewRows(preparation, {}, {}).firstOrNull { it.title.contains("can't follow") })
    }

    private fun prepare(instruction: String, context: List<Pair<String, String>>) =
        GroceryIntentContextResponse.parse(response(instruction, context), instruction).prepareForReview()

    private fun response(instruction: String, context: List<Pair<String, String>>): String {
        val start = instruction.indexOf("2 milk")
        val item = """{"name":"milk","quantity":2,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"2 milk","needs_clarification":false}"""
        val entries = context.joinToString(",") { (kind, quote) ->
            val quoteStart = instruction.indexOf(quote)
            """{"kind":${json(kind)},"source":{"text":${json(quote)},"start_utf16":$quoteStart,"end_utf16":${quoteStart + quote.length}}}"""
        }
        assertTrue(start >= 0)
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1-context","requires_review":true,"instruction":${json(instruction)},"items":[$item],"model_needs_clarification":false,"context":[$entries]},"model":"test","promptVersion":"context-v1","latencyMs":1,"usage":{}}"""
    }

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
