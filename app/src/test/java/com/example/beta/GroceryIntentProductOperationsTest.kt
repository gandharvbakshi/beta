package com.example.beta

import com.example.beta.automation.Preference
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentProductOperationsTest {
    @Test
    fun unreceiptedEditLanguageCannotEnableOriginalAndReplacementInEitherMode() {
        listOf("one oat milk instead", "swap milk for one oat milk", "one oat milk rather than milk",
            "change milk to one oat milk", "milk badal do, one oat milk", "uski jagah one oat milk",
            "milk ke badle one oat milk").forEach { clause ->
            val instruction = "one milk; $clause"
            assertTrue(clause, prepareSwiggyMcpItems(instruction) { null }.isEmpty())
            val draft = review(instruction, listOf(ready(instruction, 0, "milk", "one milk"),
                ready(instruction, 1, "oat milk", "one oat milk")))
            assertTrue(clause, draft.prepareForReview().items.isEmpty())
        }
    }

    @Test
    fun unicodeOperationScaffoldWaitsForSharedGrammar() {
        listOf("replace\u00a0milk with one oat milk", "replace mil\u212a with one oat milk").forEach { clause ->
            val instruction = "one milk; $clause"
            val rows = listOf(ready(instruction, 0, "milk", "one milk"), ready(instruction, 1, "oat milk", "one oat milk"))
            assertTrue(compileGroceryIntentProductReplacements(instruction, rows.map { it.item }).isEmpty())
        }
    }

    @Test
    fun matchesSharedProductOperationCompilerFixture() {
        val relativePath = "scripts/fixtures/grocery_product_operations_v1.json"
        val fixtureFile = listOf(File("../$relativePath"), File(relativePath)).firstOrNull { it.isFile }
            ?: error("Cannot find shared product-operation fixture at $relativePath")
        val fixture = (JsonParser(fixtureFile.readText(Charsets.UTF_8)).parseStrict() as JsonValue.Obj).members
        assertEquals("grocery-product-operations-v1", (fixture.getValue("schema") as JsonValue.Str).value)
        val cases = (fixture.getValue("cases") as JsonValue.Arr).items
        assertEquals(16, cases.size)
        cases.forEach { rawCase ->
            val case = (rawCase as JsonValue.Obj).members
            val instruction = (case.getValue("instruction") as JsonValue.Str).value
            val items = (case.getValue("items") as JsonValue.Arr).items.map { rawItem ->
                val item = (rawItem as JsonValue.Obj).members
                GroceryIntentReviewV2Item(
                    name = (item.getValue("name") as JsonValue.Str).value,
                    quantity = (item["quantity"] as? JsonValue.Num)?.raw?.let(::BigDecimal),
                    quantityUnit = (item["quantity_unit"] as? JsonValue.Str)?.value ?: "count",
                    packValue = (item["pack_value"] as? JsonValue.Num)?.raw?.let(::BigDecimal),
                    packUnit = (item["pack_unit"] as? JsonValue.Str)?.value ?: "unspecified",
                    packaging = (item["packaging"] as? JsonValue.Str)?.value,
                    required = stringList(item["required"]),
                    excluded = stringList(item["excluded"]),
                    sourceText = (item.getValue("source_text") as JsonValue.Str).value,
                    supportingText = emptyList(),
                    needsClarification = false,
                    quantityText = (item["quantity_text"] as? JsonValue.Str)?.value,
                    packText = (item["pack_text"] as? JsonValue.Str)?.value,
                )
            }
            val actual = compileGroceryIntentProductReplacements(instruction, items)
            val expected = (case.getValue("expected_operations") as JsonValue.Arr).items.map { rawOperation ->
                val operation = (rawOperation as JsonValue.Obj).members
                fun quote(key: String): GroceryIntentReviewV2Quote {
                    val value = (operation.getValue(key) as JsonValue.Obj).members
                    return GroceryIntentReviewV2Quote(
                        (value.getValue("text") as JsonValue.Str).value,
                        (value.getValue("start_utf16") as JsonValue.Num).raw.toInt(),
                        (value.getValue("end_utf16") as JsonValue.Num).raw.toInt(),
                    )
                }
                GroceryIntentProductOperation(
                    kind = (operation.getValue("kind") as JsonValue.Str).value,
                    targetRowIndex = (operation.getValue("target_row_index") as JsonValue.Num).raw.toInt(),
                    replacementRowIndex = (operation.getValue("replacement_row_index") as JsonValue.Num).raw.toInt(),
                    source = quote("source"),
                    targetMention = quote("target_mention"),
                    coverageSources = emptyList(),
                )
            }
            assertEquals((case.getValue("id") as JsonValue.Str).value, expected.size, actual.size)
            assertTrue("${(case.getValue("id") as JsonValue.Str).value}: $actual != $expected",
                expected.indices.all { expected[it].payloadEquals(actual[it]) })
        }
    }

    @Test
    fun compiledReplacementUsesUtf16QuotesAndSuppressesOnlyAfterBothRowsConvert() {
        val instruction = "😀 one milk; replace the milk with one oat milk"
        val draft = review(instruction, listOf(
            ready(instruction, 0, "milk", "one milk"),
            ready(instruction, 1, "oat milk", "one oat milk"),
        ))
        val compiled = compileGroceryIntentProductReplacements(instruction, draft.readyRows.map { it.item })
        assertEquals(1, compiled.size)
        assertEquals(0, compiled.single().targetRowIndex)
        assertEquals(1, compiled.single().replacementRowIndex)
        assertEquals("replace the milk with one oat milk", compiled.single().source.text)
        assertEquals("milk", compiled.single().targetMention.text)
        assertEquals(instruction.indexOf("replace"), compiled.single().source.startUtf16)
        assertEquals(13, compiled.single().source.startUtf16)

        val prepared = draft.copy(operations = compiled).prepareForReview()
        assertEquals(listOf("oat milk"), prepared.items.map { it.query })
        assertEquals(1, prepared.items.size)
        assertTrue(prepared.sharedGroupNotes.single().contains("replace milk with oat milk", ignoreCase = true))
        assertFalse(prepared.sharedGroupNotes.single().contains("quantity", ignoreCase = true))
    }

    @Test
    fun replacementDoesNotSwallowAnIndependentTerminalItemEndingInToo() {
        val instruction = "one milk; replace milk with one oat milk; two rice, too."
        val draft = review(instruction, listOf(
            ready(instruction, 0, "milk", "one milk"),
            ready(instruction, 1, "oat milk", "one oat milk"),
            ready(instruction, 2, "rice", "two rice", quantity = 2),
        ))
        val operation = compileGroceryIntentProductReplacements(instruction, draft.readyRows.map { it.item }).single()
        val prepared = draft.copy(operations = listOf(operation)).prepareForReview()
        assertEquals(listOf("oat milk", "rice"), prepared.items.map { it.query })
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test
    fun replacementCoverageDoesNotHideUnrelatedContentsScopeMarkers() {
        val instruction = "one milk; replace milk with one oat milk; two tea bags with three biscuits"
        val rows = listOf(
            ready(instruction, 0, "milk", "one milk"),
            ready(instruction, 1, "oat milk", "one oat milk"),
            ready(instruction, 2, "tea bags", "two tea bags", quantity = 2),
            ready(instruction, 3, "biscuits", "three biscuits", quantity = 3),
        )
        val operation = compileGroceryIntentProductReplacements(instruction, rows.map { it.item }).single()
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction,
            rows.map { it.evidence.primary }, sourceItemCount = 4,
            productOperationSources = operation.coverageSources))
    }

    @Test
    fun wireLedgerMustExactlyMatchIndependentCompilation() {
        val instruction = "one milk; replace the milk with one oat milk"
        val rows = listOf(ready(instruction, 0, "milk", "one milk"), ready(instruction, 1, "oat milk", "one oat milk"))
        val operation = compileGroceryIntentProductReplacements(instruction, rows.map { it.item }).single()
        val body = response(instruction, rows, operation)
        val parsed = GroceryIntentReviewV2Response.parse(body, instruction).draft
        assertTrue(operation.payloadEquals(parsed.operations.single()))

        val forged = body.replace("\"target_row_index\":0", "\"target_row_index\":1")
        assertTrue(runCatching { GroceryIntentReviewV2Response.parse(forged, instruction) }.isFailure)
    }

    @Test
    fun replacementCueWithoutWireLedgerCannotResurrectOriginalRow() {
        val instruction = "one milk; replace the milk with one oat milk"
        val rows = listOf(ready(instruction, 0, "milk", "one milk"), ready(instruction, 1, "oat milk", "one oat milk"))
        val ordinaryDraft = review(instruction, rows).prepareForReview()
        assertTrue(ordinaryDraft.items.isEmpty())
        assertEquals(listOf(instruction), ordinaryDraft.unresolvedText)
    }

    @Test
    fun closedEnglishAndHindiOperationGrammarMatchesOnlySupportedForms() {
        val supported = listOf(
            "one oat milk instead of milk",
            "instead of milk, one oat milk",
            "milk ki jagah one oat milk",
            "uski jagah one oat milk, milk nahi",
        )
        supported.forEach { clause ->
            val instruction = "one milk; $clause"
            val rows = listOf(ready(instruction, 0, "milk", "one milk"), ready(instruction, 1, "oat milk", "one oat milk"))
            val operation = compileGroceryIntentProductReplacements(instruction, rows.map { it.item }).singleOrNull()
            assertTrue("$clause", operation != null)
            assertEquals(0, operation!!.targetRowIndex)
            assertEquals(1, operation.replacementRowIndex)
        }

        val unsupportedInstruction = "one milk; if available replace milk with one oat milk"
        val unsupportedRows = listOf(
            ready(unsupportedInstruction, 0, "milk", "one milk"),
            ready(unsupportedInstruction, 1, "oat milk", "one oat milk"),
        )
        assertTrue(compileGroceryIntentProductReplacements(unsupportedInstruction,
            unsupportedRows.map { it.item }).isEmpty())
    }

    @Test
    fun replacementCannotDropNumericIdentityQuantityPackOrExistingProperties() {
        val numeric = "one Brand2 milk; replace milk with one oat milk"
        val numericRows = listOf(ready(numeric, 0, "Brand2 milk", "one Brand2 milk"),
            ready(numeric, 1, "oat milk", "one oat milk"))
        assertTrue(compileGroceryIntentProductReplacements(numeric, numericRows.map { it.item }).isEmpty())

        val count = "three milk; replace milk with oat milk"
        val countRows = listOf(ready(count, 0, "milk", "three milk", quantity = 3),
            ready(count, 1, "oat milk", "oat milk").let { row ->
                row.copy(item = row.item.copy(quantity = null, quantityUnit = "unspecified", quantityText = null),
                    quantityOrigin = "not_stated", amountRoleStatus = "not_stated")
            })
        assertTrue(compileGroceryIntentProductReplacements(count, countRows.map { it.item }).isEmpty())

        val pack = "one milk; replace milk with one oat milk"
        val packRows = listOf(ready(pack, 0, "milk", "one milk"), ready(pack, 1, "oat milk", "one oat milk"))
        val packedTarget = packRows.first().copy(item = packRows.first().item.copy(
            packValue = BigDecimal("250"), packUnit = "g", packText = "250",
        ))
        assertTrue(compileGroceryIntentProductReplacements(pack, listOf(packedTarget.item, packRows[1].item)).isEmpty())

        val properties = "one milk; replace milk with one oat milk"
        val propertyRows = listOf(
            ready(properties, 0, "milk", "one milk").let { row ->
                row.copy(item = row.item.copy(required = listOf("organic")))
            },
            ready(properties, 1, "oat milk", "one oat milk"),
        )
        assertTrue(compileGroceryIntentProductReplacements(properties, propertyRows.map { it.item }).isEmpty())
        assertTrue(compileGroceryIntentProductReplacements(properties, listOf(
            propertyRows.first().item.copy(required = emptyList()), propertyRows.last().item.copy(required = listOf("organic")),
        )).isNotEmpty())
    }

    @Test
    fun failedOrdinaryConversionOfEitherParticipantRevokesTheWholeReceipt() {
        val oldFails = "one BrandB milk; replace the BrandA milk with one BrandA oat milk"
        val oldDraft = review(oldFails, listOf(
            ready(oldFails, 0, "BrandA milk", "one BrandB milk"),
            ready(oldFails, 1, "BrandA oat milk", "one BrandA oat milk"),
        ))
        val oldOperation = compileGroceryIntentProductReplacements(oldFails, oldDraft.readyRows.map { it.item }).single()
        val oldPrepared = oldDraft.copy(operations = listOf(oldOperation)).prepareForReview()
        assertTrue(oldPrepared.items.isEmpty())
        assertEquals(listOf(oldFails), oldPrepared.unresolvedText)

        val newFails = "one milk; replace milk with two banana"
        val newDraft = review(newFails, listOf(
            ready(newFails, 0, "milk", "one milk"),
            ready(newFails, 1, "oat milk", "two banana", quantity = 2),
        ))
        val newOperation = compileGroceryIntentProductReplacements(newFails, newDraft.readyRows.map { it.item }).single()
        val newPrepared = newDraft.copy(operations = listOf(newOperation)).prepareForReview()
        assertTrue(newPrepared.items.isEmpty())
        assertEquals(listOf(newFails), newPrepared.unresolvedText)
    }

    @Test
    fun ledgerRequiresEveryOrdinarySourceRowAndNoOtherNegotiatedOperations() {
        val instruction = "one milk; replace milk with one oat milk"
        val rows = listOf(ready(instruction, 0, "milk", "one milk"), ready(instruction, 1, "oat milk", "one oat milk"))
        val operation = compileGroceryIntentProductReplacements(instruction, rows.map { it.item }).single()
        val direct = review(instruction, rows).copy(operations = listOf(operation), blockedRows = listOf(
            GroceryIntentReviewV2BlockedRow(2, null, listOf("unclear")),
        ))
        val prepared = direct.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(instruction), prepared.unresolvedText)

        assertTrue(SwiggyMcpClient.intentPreparationCapabilities(GroceryIntentContract.V2)
            .getValue("supports_product_operations"))
        assertFalse(SwiggyMcpClient.intentPreparationCapabilities(GroceryIntentContract.V1)
            .containsKey("supports_product_operations"))
    }

    @Test
    fun standardPreparationFailsClosedForEnglishAndHindiReplacementCues() {
        listOf(
            "replace the milk with oat milk",
            "oat milk instead of milk",
            "milk ki jagah oat milk",
            "milk ke badle oat milk",
            "milk ke bajay oat milk",
            "uski jagah oat milk, milk nahi",
        ).forEach { instruction ->
            assertTrue(instruction, hasExplicitGroceryIntentProductOperationCue(instruction))
            assertTrue(instruction, prepareSwiggyMcpItems(instruction) { _: String -> null }.isEmpty())
        }
    }

    private fun review(instruction: String, rows: List<GroceryIntentReviewV2ReadyRow>) = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2",
        requiresReview = true,
        instruction = instruction,
        sourceItemCount = rows.size,
        readyRows = rows,
        blockedRows = emptyList(),
    )

    private fun ready(
        instruction: String,
        index: Int,
        name: String,
        source: String,
        quantity: Int = 1,
    ): GroceryIntentReviewV2ReadyRow {
        val start = instruction.indexOf(source)
        require(start >= 0)
        val primary = GroceryIntentReviewV2Quote(source, start, start + source.length)
        val amountText = source.substringBefore(' ')
        val item = GroceryIntentReviewV2Item(
            name = name,
            quantity = BigDecimal.valueOf(quantity.toLong()),
            quantityUnit = "count",
            packValue = null,
            packUnit = "unspecified",
            packaging = null,
            required = emptyList(),
            excluded = emptyList(),
            sourceText = source,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = amountText,
            packText = null,
        )
        return GroceryIntentReviewV2ReadyRow(
            rowIndex = index,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(primary, emptyList()),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
    }

    private fun response(
        instruction: String,
        rows: List<GroceryIntentReviewV2ReadyRow>,
        operation: GroceryIntentProductOperation,
    ): String {
        fun quote(value: GroceryIntentReviewV2Quote) =
            """{"text":${json(value.text)},"start_utf16":${value.startUtf16},"end_utf16":${value.endUtf16}}"""
        val rowJson = rows.joinToString(",") { row ->
            val item = row.item
            """{"row_index":${row.rowIndex},"item":{"name":${json(item.name)},"quantity":${item.quantity},"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${json(item.sourceText)},"supporting_text":[],"needs_clarification":false,"quantity_text":${json(item.quantityText!!)},"pack_text":null},"evidence":{"primary":${quote(row.evidence.primary)},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
        }
        val opJson = """{"kind":"replace","target_row_index":${operation.targetRowIndex},"replacement_row_index":${operation.replacementRowIndex},"source":${quote(operation.source)},"target_mention":${quote(operation.targetMention)}}"""
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":${rows.size},"ready_rows":[$rowJson],"blocked_rows":[],"operations":[$opJson]},"model":"test","promptVersion":"test","latencyMs":0,"usage":{}}"""
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun stringList(value: JsonValue?): List<String> =
        (value as? JsonValue.Arr)?.items?.map { (it as JsonValue.Str).value } ?: emptyList()
}
