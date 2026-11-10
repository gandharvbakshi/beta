package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentOwnedPropertyScopeTest {
    @Test
    fun terminalOwnedPropertiesDoNotPoisonEarlierCommaChainRows() {
        data class OwnedCase(val prefix: List<String>, val source: String, val name: String, val required: List<String>)
        val cases = listOf(
            OwnedCase(listOf("rice", "dal"), "cashews plain", "cashews", listOf("plain")),
            OwnedCase(listOf("rice", "oats"), "yogurt organic", "yogurt", listOf("organic")),
            OwnedCase(listOf("dal", "apples"), "milk low fat", "milk", listOf("low fat")),
        )
        cases.forEach { case ->
            val result = prepare(case.prefix, case.source, case.name, case.required)
            assertEquals("${case.source} should retain the locally owned property",
                case.prefix.size + 1, result.items.size)
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test
    fun precedingRowPermutationsRemainReadyWhenFinalPropertyHasAnOwner() {
        val permutations = listOf(
            listOf("rice", "dal", "oats"), listOf("rice", "oats", "dal"),
            listOf("dal", "rice", "oats"), listOf("dal", "oats", "rice"),
            listOf("oats", "rice", "dal"), listOf("oats", "dal", "rice"),
        )
        permutations.forEach { prefix ->
            val result = prepare(prefix, "cashews plain", "cashews", listOf("plain"))
            assertEquals(prefix.joinToString(), prefix.size + 1, result.items.size)
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test
    fun longOrdinaryCommaChainCanEndInOneOwnedProperty() {
        val products = listOf(
            "rice", "dal", "cashews", "dates", "oats", "apples", "carrots", "mangoes", "noodles",
            "coffee", "tea", "lentils", "flour", "grapes", "honey", "oranges", "pasta",
        )
        val result = prepare(products, "yogurt plain", "yogurt", listOf("plain"))
        assertEquals(18, result.items.size)
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test
    fun terminalExcludedPropertyCanBeRetainedByItsProductOwner() {
        val sources = listOf("rice", "dal", "soap no chemicals")
        val instruction = sources.joinToString(", ")
        val rows = listOf(
            readyRow(instruction, 0, "rice", "rice"),
            readyRow(instruction, 1, "dal", "dal"),
            readyRow(instruction, 2, "soap no chemicals", "soap", excluded = listOf("chemicals")),
        )
        val result = GroceryIntentReviewV2Response.parse(response(instruction, rows), instruction)
            .draft.prepareForReview()
        assertEquals(3, result.items.size)
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test
    fun bareOrUnretainedPropertyFragmentsStayHeld() {
        assertHeld(listOf("rice", "dal", "plain"))
        assertHeld(listOf("rice", "dal plain"), ownerName = "dal", ownerRequired = emptyList())
        assertHeld(listOf("rice", "dal", "cashews plain"), ownerName = "cashews", ownerRequired = emptyList())
    }

    @Test
    fun propertyPrefixAndInternalDelimiterDoNotBecomeTerminalOwnership() {
        assertHeld(listOf("rice", "plain dal"), ownerName = "dal", ownerRequired = listOf("plain"))
        assertHeld(listOf("rice", "dal", "cashews, plain"), ownerName = "cashews", ownerRequired = listOf("plain"))
        assertHeld(listOf("rice", "dal", "cashews: plain"), ownerName = "cashews", ownerRequired = listOf("plain"))
    }

    @Test
    fun terminalPropertyOwnerRequiresAnExplicitCommaItemBoundary() {
        listOf(" and ", " aur ", " ", " & ", ", and ").forEach { gap ->
            assertGapHeld(gap)
        }
        val commaBoundary = prepareGap(", ")
        assertEquals(2, commaBoundary.items.size)
        assertTrue(commaBoundary.unresolvedText.isEmpty())
    }

    @Test
    fun reportedScopedOrQuantifiedPropertyClaimsCannotOwnTheSuffix() {
        listOf(
            "eggs reported as organic", "eggs for everyone organic", "eggs both organic", "eggs very organic",
            "both organic", "every organic", "either organic", "neither organic", "some organic",
            "none organic", "reported organic", "say organic",
        ).forEach { phrase ->
            assertHeld(listOf("rice", phrase), ownerName = phrase, ownerRequired = listOf("organic"))
        }
    }

    @Test
    fun blockedOwnerAndTrailingUnownedNumberRevokeTheScope() {
        val blockedInstruction = "rice, dal, cashews plain"
        val blockedRows = listOf("rice", "dal")
        val blockedReady = blockedRows.mapIndexed { index, source -> readyRow(blockedInstruction, index, source, source) }
        val blocked = GroceryIntentReviewV2Response.parse(
            response(blockedInstruction, blockedReady, blockedSource = "cashews plain"), blockedInstruction,
        ).draft.prepareForReview()
        assertTrue(blocked.items.isEmpty())
        assertTrue(blocked.unresolvedText.isNotEmpty() || blocked.excludedText.isNotEmpty() ||
            blocked.recoverySnapshot != null)

        assertHeld(listOf("rice", "dal", "cashews plain"), trailing = ", 2")
    }

    private fun assertHeld(
        rows: List<String>,
        ownerName: String? = null,
        ownerRequired: List<String> = emptyList(),
        trailing: String = "",
    ) {
        val instruction = rows.joinToString(", ") + trailing
        val ready = rows.mapIndexed { index, source ->
            val name = if (index == rows.lastIndex) ownerName ?: source else source
            val required = if (index == rows.lastIndex) ownerRequired else emptyList()
            readyRow(instruction, index, source, name, required)
        }
        val prepared = GroceryIntentReviewV2Response.parse(response(instruction, ready), instruction)
            .draft.prepareForReview()
        assertTrue("Expected fail-closed hold", prepared.items.isEmpty())
        assertTrue("Original request should remain visible", prepared.unresolvedText.isNotEmpty() ||
            prepared.excludedText.isNotEmpty() || prepared.recoverySnapshot != null)
    }

    private fun prepare(
        prefix: List<String>, ownerSource: String, ownerName: String, required: List<String>,
    ): GroceryIntentPreparation {
        val sources = prefix + ownerSource
        val instruction = sources.joinToString(", ")
        val rows = sources.mapIndexed { index, source ->
            if (index == sources.lastIndex) readyRow(instruction, index, source, ownerName, required)
            else readyRow(instruction, index, source, source)
        }
        return GroceryIntentReviewV2Response.parse(response(instruction, rows), instruction).draft.prepareForReview()
    }

    private fun assertGapHeld(gap: String) {
        val result = prepareGap(gap)
        assertTrue("Gap '$gap' must not grant a second product", result.items.isEmpty())
        assertTrue(result.unresolvedText.isNotEmpty() || result.excludedText.isNotEmpty() ||
            result.recoverySnapshot != null)
    }

    private fun prepareGap(gap: String): GroceryIntentPreparation {
        val instruction = "rice${gap}dal organic"
        val rows = listOf(
            readyRow(instruction, 0, "rice", "rice"),
            readyRow(instruction, 1, "dal organic", "dal", listOf("organic")),
        )
        return GroceryIntentReviewV2Response.parse(response(instruction, rows), instruction).draft.prepareForReview()
    }

    private fun readyRow(
        instruction: String,
        index: Int,
        source: String,
        name: String,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
    ): String {
        val start = instruction.indexOf(source)
        require(start >= 0)
        return """{"row_index":$index,"item":{"name":${json(name)},"quantity":null,"quantity_unit":"unspecified","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[${required.joinToString(",") { json(it) }}],"excluded":[${excluded.joinToString(",") { json(it) }}],"source_text":${json(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":null,"pack_text":null},"evidence":{"primary":{"text":${json(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"supporting":[]},"quantity_origin":"not_stated","amount_role_status":"not_stated"}"""
    }

    private fun response(instruction: String, rows: List<String>, blockedSource: String? = null): String {
        val blocked = if (blockedSource == null) "" else {
            val start = instruction.indexOf(blockedSource)
            """{"row_index":${rows.size},"source":{"text":${json(blockedSource)},"start_utf16":$start,"end_utf16":${start + blockedSource.length}},"issue_codes":["needs_clarification"]}"""
        }
        val ready = rows.joinToString(",")
        val blockedRows = if (blocked.isEmpty()) "" else blocked
        val allRows = rows.size + if (blocked.isEmpty()) 0 else 1
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":$allRows,"ready_rows":[$ready],"blocked_rows":[${blockedRows}]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
