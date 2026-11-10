package com.example.beta

import com.example.beta.automation.Quantity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentLocalPropertyConnectorTest {
    @Test
    fun sharedPythonWireContractMatchesDefaultAndroidPreparation() {
        val relative = "scripts/fixtures/intent_local_connector_contract_v1.json"
        val file = listOf(File("../$relative"), File(relative)).first(File::isFile)
        val cases = ((JsonParser(file.readText()).parseStrict() as JsonValue.Obj).members
            .getValue("cases") as JsonValue.Arr).items

        assertEquals(15, cases.size)
        cases.forEach { value ->
            val case = (value as JsonValue.Obj).members
            val instruction = (case.getValue("instruction") as JsonValue.Str).value
            val review = GroceryIntentReviewV2.parse(
                (case.getValue("review") as JsonValue.Obj).members, instruction,
            )
            val prepared = review.prepareForReview()
            val expectedCount = (case.getValue("expected_count") as JsonValue.Num).raw.toBigDecimal().intValueExact()
            val quantity = (case.getValue("quantity") as JsonValue.Num).raw.toBigDecimal().intValueExact()
            val ownerIndex = (case.getValue("owner_index") as JsonValue.Num).raw.toBigDecimal().intValueExact()
            val ownerClaim = (case.getValue("owner_claim") as JsonValue.Str).value
            val caseId = (case.getValue("id") as JsonValue.Str).value

            assertEquals(caseId, expectedCount, prepared.items.size)
            if (expectedCount == 0) {
                assertTrue("$caseId must remain visible for review", prepared.unresolvedText.isNotEmpty() ||
                    prepared.excludedText.isNotEmpty() || prepared.recoverySnapshot != null)
            } else {
                assertEquals(caseId, List(expectedCount) { Quantity.Count(quantity) }, prepared.items.map { it.quantity })
                assertTrue("$caseId", prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
                val owner = prepared.items[ownerIndex]
                val query = owner.query.lowercase()
                val exclusions = owner.avoidPhrases.map(String::lowercase)
                when (ownerClaim) {
                    "no added sugar" -> assertTrue(caseId,
                        query.contains("no added sugar") || "added sugar" in exclusions)
                    "no sugar" -> assertTrue(caseId,
                        query.contains("sugar free") || "sugar free" in exclusions || "sugar" in exclusions)
                    "no added salt" -> assertTrue(caseId,
                        query.contains("no added salt") || "added salt" in exclusions)
                    else -> error("Unexpected fixture claim")
                }
                val ownerSource = review.readyRows[ownerIndex].evidence.primary.text
                assertNotNull(caseId, owner.intentQuantityProvenance)
                assertEquals(caseId, ownerSource, owner.intentQuantityProvenance!!.primaryText)
            }
        }
    }

    @Test
    fun localConnectorKeepsExactIngredientRestrictionOnItsKnownCommodity() {
        val instruction = "one apple, one tea with no added sugar, one cabbage"
        val prepared = prepare(instruction, listOf(
            Row("one apple", "apple"),
            Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar")),
            Row("one cabbage", "cabbage"),
        ))
        assertEquals(3, prepared.items.size)
        assertEquals(List(3) { Quantity.Count(1) }, prepared.items.map { it.quantity })
        assertTrue(prepared.items[0].query.contains("apple", ignoreCase = true))
        assertTrue(prepared.items[1].query.contains("tea", ignoreCase = true))
        assertTrue(prepared.items[1].avoidPhrases.contains("added sugar"))
        assertTrue(prepared.items[2].query.contains("cabbage", ignoreCase = true))
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test
    fun retainedPropertyAndIngredientComplementsStayAttachedToTheNoun() {
        val cases = listOf(
            Row("one tea with no sugar", "tea", required = listOf("sugar_free")),
            Row("one coffee with no added salt", "coffee", excluded = listOf("added_salt")),
            Row("one biscuits with no added sugar", "biscuits", excluded = listOf("added_sugar")),
        )
        cases.forEach { marked ->
            val instruction = "one apple, ${marked.source}, one cabbage"
            val result = prepare(instruction, listOf(Row("one apple", "apple"), marked, Row("one cabbage", "cabbage")))
            assertEquals(marked.source, 3, result.items.size)
            val product = result.items[1]
            assertTrue(product.query.contains(marked.name, ignoreCase = true))
            marked.excluded.forEach { exclusion -> assertTrue(product.avoidPhrases.contains(exclusion.replace('_', ' '))) }
            assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
        }
    }

    @Test
    fun knownCommodityHeadWordsCanOwnTheExactRestriction() {
        listOf("tea", "coffee", "milk", "biscuits", "chocolate", "rice", "oats", "flour").forEach { noun ->
            val marked = Row("one $noun with no added sugar", noun, excluded = listOf("added_sugar"))
            val instruction = "one apple, ${marked.source}, one cabbage"
            val result = prepare(instruction, listOf(Row("one apple", "apple"), marked, Row("one cabbage", "cabbage")))
            assertEquals(noun, 3, result.items.size)
            assertTrue(result.items[1].query.contains(noun, ignoreCase = true))
            assertTrue(result.items[1].avoidPhrases.contains("added sugar"))
            assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
        }
    }

    @Test
    fun connectorOwnerMayMoveAcrossCommaSemicolonAndNewlineSeparators() {
        val products = listOf(Row("one apple", "apple"),
            Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar")),
            Row("one cabbage", "cabbage"))
        listOf(", ", "; ", "\n").forEach { separator ->
            for (offset in products.indices) {
                val arranged = products.drop(offset) + products.take(offset)
                val instruction = arranged.joinToString(separator) { it.source }
                val prepared = prepare(instruction, arranged)
                assertEquals("separator=$separator offset=$offset", 3, prepared.items.size)
                val tea = prepared.items.single { it.query.contains("tea", ignoreCase = true) }
                assertTrue(tea.avoidPhrases.contains("added sugar"))
                assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            }
        }
        val terminalPunctuation = "one apple, one tea with no added sugar, one cabbage."
        val punctuated = prepare(terminalPunctuation, listOf(
            Row("one apple", "apple"),
            Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar")),
            Row("one cabbage", "cabbage"),
        ))
        assertEquals(3, punctuated.items.size)
        assertTrue(punctuated.items[1].avoidPhrases.contains("added sugar"))
    }

    @Test
    fun fifteenAndTwentyLocallyCountedProductsRetainConnectorEvidence() {
        val names = listOf("apple", "tea", "cabbage", "milk", "oats", "flour", "rice", "coffee", "biscuits",
            "chocolate", "carrots", "eggs", "lentils", "bread", "pasta", "honey", "oranges", "tomatoes",
            "yogurt", "bananas")
        for (size in listOf(15, 20)) {
            val products = names.take(size).map { name ->
                if (name == "tea") Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar"))
                else Row("one $name", name)
            }
            val arranged = products.reversed()
            val instruction = arranged.joinToString(", ") { it.source }
            val prepared = prepare(instruction, arranged)
            assertEquals("size=$size", size, prepared.items.size)
            assertEquals(List(size) { Quantity.Count(1) }, prepared.items.map { it.quantity })
            assertTrue(prepared.items.single { it.query.contains("tea", ignoreCase = true) }
                .avoidPhrases.contains("added sugar"))
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test
    fun unknownOrCompoundNounsCannotOwnTheRestriction() {
        val rejected = listOf(
            Row("one tea hamper with no added sugar", "tea hamper", excluded = listOf("added_sugar")),
            Row("one tea kit with no added sugar", "tea kit", excluded = listOf("added_sugar")),
            Row("one widget with no added sugar", "widget", excluded = listOf("added_sugar")),
            Row("one box of tea with no added sugar", "tea", packaging = "box", excluded = listOf("added_sugar")),
            Row("one organic tea with no added sugar", "organic tea", required = listOf("organic"), excluded = listOf("added_sugar")),
        )
        rejected.forEach { row -> assertHeld(listOf(Row("one apple", "apple"), row, Row("one cabbage", "cabbage"))) }
    }

    @Test
    fun reportedLabelQuotedAndActualContentsClaimsDoNotGrantConnectorOwnership() {
        listOf(
            Row("one tea contains no added sugar", "tea", excluded = listOf("added_sugar")),
            Row("one tea includes no added sugar", "tea", excluded = listOf("added_sugar")),
            Row("one tea with milk", "tea", excluded = listOf("added_sugar")),
            Row("one tea with no added sugar content", "tea", excluded = listOf("added_sugar")),
            Row("one tea with label says no added sugar", "tea", excluded = listOf("added_sugar")),
            Row("one tea with label \"no added sugar\"", "tea", excluded = listOf("added_sugar")),
            Row("one tea with label printed \"no added sugar\"", "tea", excluded = listOf("added_sugar")),
            Row("one tea with \"no added sugar\"", "tea", excluded = listOf("added_sugar")),
        ).forEach { row -> assertHeld(listOf(Row("one apple", "apple"), row, Row("one cabbage", "cabbage"))) }
    }

    @Test
    fun croppedBareAndAmbiguousMarkerFormsStayHeld() {
        assertHeld(listOf(Row("one apple", "apple"),
            Row("one tea", "tea", excluded = listOf("added_sugar")), Row("one cabbage", "cabbage")),
            instructionOverride = "one apple, one tea with no added sugar, one cabbage")
        listOf(
            listOf(Row("one apple", "apple"), Row("one tea with no added sugar with no added salt", "tea", excluded = listOf("added_sugar", "added_salt")), Row("one cabbage", "cabbage")),
            listOf(Row("one apple", "apple"), Row("one good quality tea with no added sugar", "tea", excluded = listOf("added_sugar")), Row("one cabbage", "cabbage")),
        ).forEach(::assertHeld)
        val marked = Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar"))
        val ordinary = listOf(Row("one apple", "apple"), marked, Row("one cabbage", "cabbage"))
        assertHeld(ordinary, instructionOverride = ordinary.joinToString(" and ") { it.source })
        assertHeld(ordinary, instructionOverride = ordinary.joinToString(" & ") { it.source })
    }

    @Test
    fun unownedWeightFollowerAndBlockedOrUnretainedOwnerStayHeld() {
        assertHeld(listOf(Row("one apple", "apple"),
            Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar")), Row("one cabbage", "cabbage")),
            instructionOverride = "one apple, one tea with no added sugar, 250 g, one cabbage")
        assertHeld(listOf(Row("one apple", "apple"),
            Row("one tea with no added sugar", "tea", excluded = listOf("added_sugar")), Row("one cabbage", "cabbage")),
            instructionOverride = "one apple, one tea with no added sugar, tea leaves, one cabbage")
        assertHeld(listOf(Row("one apple", "apple"), Row("one tea with no added sugar", "tea"), Row("one cabbage", "cabbage")))
        val instruction = "one apple, one tea with no added sugar, one cabbage"
        val blocked = listOf(Row("one apple", "apple"), Row("one cabbage", "cabbage"))
        assertHeld(blocked, instructionOverride = instruction, blockedSource = "one tea with no added sugar")
    }

    private data class Row(
        val source: String,
        val name: String,
        val packaging: String? = null,
        val required: List<String> = emptyList(),
        val excluded: List<String> = emptyList(),
    )

    private fun assertHeld(
        rows: List<Row>,
        instructionOverride: String? = null,
        blockedSource: String? = null,
    ) {
        val instruction = instructionOverride ?: rows.joinToString(", ") { it.source }
        val prepared = prepare(instruction, rows, blockedSource)
        assertTrue("Unproven connector released rows: $instruction", prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty() ||
            prepared.recoverySnapshot != null)
    }

    private fun prepare(
        instruction: String,
        rows: List<Row>,
        blockedSource: String? = null,
    ): GroceryIntentPreparation {
        val sources = rows.map { it.source } + listOfNotNull(blockedSource)
        val orderedSources = sources.sortedBy { instruction.indexOf(it).also { start -> require(start >= 0) } }
        val readyJson = rows.map { row ->
            readyRow(instruction, orderedSources.indexOf(row.source), row)
        }
        val blocked = blockedSource?.let { listOf(blockedRow(instruction, orderedSources.indexOf(it), it)) }.orEmpty()
        val wire = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":${readyJson.size + blocked.size},"ready_rows":[${readyJson.joinToString(",")}],"blocked_rows":[${blocked.joinToString(",")}]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(wire, instruction).draft.prepareForReview()
    }

    private fun readyRow(instruction: String, index: Int, row: Row): String {
        val start = instruction.indexOf(row.source)
        require(start >= 0)
        val required = row.required.joinToString(",") { json(it) }
        val excluded = row.excluded.joinToString(",") { json(it) }
        return """{"row_index":$index,"item":{"name":${json(row.name)},"quantity":1,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":${row.packaging?.let(::json) ?: "null"},"required":[$required],"excluded":[$excluded],"source_text":${json(row.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":"one","pack_text":null},"evidence":{"primary":{"text":${json(row.source)},"start_utf16":$start,"end_utf16":${start + row.source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
    }

    private fun blockedRow(instruction: String, index: Int, source: String): String {
        val start = instruction.indexOf(source)
        require(start >= 0)
        return """{"row_index":$index,"source":{"text":${json(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"issue_codes":["model_needs_review"]}"""
    }

    private fun json(text: String): String = "\"" + text.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
