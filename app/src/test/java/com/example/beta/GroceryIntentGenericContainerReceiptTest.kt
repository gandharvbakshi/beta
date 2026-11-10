package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentGenericContainerReceiptTest {
    /** These exercise source evidence only; receipt presence never authorizes an API/cart action. */
    @Test
    fun genericContainerFixtureCompilesOnlyItsAcceptedSpanReceipts() {
        val cases = loadFixture()
        assertEquals(40, cases.size)
        assertEquals(setOf(false, true), cases.map { it.accepted }.toSet())

        cases.forEach { case ->
            val count = localAmount(case.source, case.quantityText, BigDecimal(case.quantity), case.quantityUnit)
            val size = localAmount(case.source, case.packText, case.packValue, case.packUnit)
            val receipt = if (count != null && size != null) {
                compileGenericContainerReceipt(case.source, case.name, count, size, case.extended)
            } else null
            assertEquals("${case.id}: fixture disposition must match receipt compilation", case.accepted, receipt != null)

            if (case.accepted) {
                val compiled = requireNotNull(receipt)
                assertEquals(case.source, compiled.source)
                assertEquals(count, compiled.count)
                assertEquals(size, compiled.size)
                assertTrue(case.source.substring(compiled.wrapper.first, compiled.wrapper.last + 1).lowercase() in
                    groceryIntentGenericContainerWords)
                assertEquals(case.name, case.source.substring(compiled.identity.first, compiled.identity.last + 1))
                val physicalSize = requireNotNull(compiled.physicalSize)
                val physical = case.source.substring(physicalSize.first, physicalSize.last + 1)
                assertTrue("${case.id}: physical size span should start at its exact amount", physical.startsWith(case.packText))
                assertTrue("${case.id}: physical size span should include its source unit", physical.length > case.packText.length)
                compiled.request?.let { assertTrue(case.source.substring(it.first, it.last + 1).isNotBlank()) }
                compiled.connector?.let { assertTrue(case.source.substring(it.first, it.last + 1).isNotBlank()) }

                val redacted = compiled.toString()
                assertEquals("GenericContainerReceipt(<redacted>)", redacted)
                listOf(case.source, case.name, case.quantityText, case.packText).forEach {
                    assertFalse("receipt string must not expose source evidence", redacted.contains(it))
                }
            }
        }
    }

    @Test
    fun unsizedFixtureUsesAnExplicitUnspecifiedSizeReceiptOnlyWhenSourceAndModelAgree() {
        val cases = loadUnsizedFixture()
        assertEquals(27, cases.size)
        assertEquals(setOf(false, true), cases.map { it.accepted }.toSet())
        cases.forEach { case ->
            val count = localAmount(case.source, case.quantityText, BigDecimal(case.quantity), case.quantityUnit)
            val size = case.packText?.let { text -> case.packValue?.let { value ->
                localAmount(case.source, text, value, case.packUnit)
            } }
            val modelSizeMatchesSource = (case.packText == null && case.packValue == null &&
                case.packUnit == "unspecified") || size != null
            val receipt = if (count != null && modelSizeMatchesSource) {
                compileGenericContainerReceipt(case.source, case.name, count, size, case.extended)
            } else null
            assertEquals("${case.id}: fixture disposition must match receipt compilation",
                case.accepted, receipt != null)
            if (case.accepted) {
                val compiled = requireNotNull(receipt)
                assertTrue(compiled.requestedSize is GenericContainerRequestedSize.Unspecified)
                assertNull(compiled.size)
                assertNull(compiled.physicalSize)
                assertEquals(case.quantity, compiled.count.value.toInt())
                assertEquals(case.name, case.source.substring(compiled.identity))
                assertEquals("GenericContainerReceipt(<redacted>)", compiled.toString())
                assertFalse(compiled.toString().contains(case.source))
            }
        }
    }

    @Test
    fun sharedUnsizedContextFixtureRequiresTheTargetRowBoundaryAndCompleteOwnership() {
        val cases = loadContextFixture()
        assertEquals(12, cases.size)
        assertEquals(setOf(false, true), cases.map { it.accepted }.toSet())
        cases.forEach { case ->
            val targetSource = case.sources[case.target]
            val count = requireNotNull(localAmount(targetSource, "2", BigDecimal("2"), "count"))
            val receipt = requireNotNull(compileGenericContainerReceipt(
                targetSource, "Nescafe", count, null, extended = true,
            ))
            assertEquals(case.accepted,
                receipt.instructionContextProven(case.instruction, case.sources))
        }
    }

    @Test
    fun alteredSourceBindingsAndUnicodeConfusablesDoNotCompile() {
        val source = "2 dabba rice 500 g"
        val count = requireNotNull(localAmount(source, "2", BigDecimal("2"), "count"))
        val size = requireNotNull(localAmount(source, "500", BigDecimal("500"), "g"))
        assertNotNull(compileGenericContainerReceipt(source, "rice", count, size))

        assertNull(compileGenericContainerReceipt(source, "rice", count.copy(startUtf16 = count.startUtf16 + 1), size))
        assertNull(compileGenericContainerReceipt(source, "rice", count.copy(source = "2 dabba wheat 500 g"), size))
        assertNull(compileGenericContainerReceipt(source, "rice", count.copy(value = BigDecimal("3")), size))
        assertNull(compileGenericContainerReceipt(source, "rice", count.copy(unit = "piece"), size))
        assertNull(compileGenericContainerReceipt(source, "rice", count, size.copy(startUtf16 = size.startUtf16 + 1)))
        assertNull(compileGenericContainerReceipt(source, "rice", count, size.copy(source = "2 dabba rice 500 ml")))
        assertNull(compileGenericContainerReceipt(source, "rice", count, size.copy(value = BigDecimal("400"))))
        assertNull(compileGenericContainerReceipt(source, "rice", count, size.copy(unit = "ml")))

        val repeatedSizeSource = "2 dabba rice 500 g and 500 g"
        val repeatedCount = requireNotNull(localAmount(repeatedSizeSource, "2", BigDecimal("2"), "count"))
        val firstSizeStart = repeatedSizeSource.indexOf("500")
        val firstSize = IntentLocalAmount(
            repeatedSizeSource, firstSizeStart, firstSizeStart + 3, "500", BigDecimal("500"), "g",
        )
        assertNull(compileGenericContainerReceipt(repeatedSizeSource, "rice", repeatedCount, firstSize))

        val wrapperConfusable = "2 dıbba rice 500 g"
        assertNull(compileGenericContainerReceipt(
            wrapperConfusable, "rice",
            requireNotNull(localAmount(wrapperConfusable, "2", BigDecimal("2"), "count")),
            requireNotNull(localAmount(wrapperConfusable, "500", BigDecimal("500"), "g")),
        ))
        val connectorConfusable = "2 500 g rice kı dabba"
        assertNull(compileGenericContainerReceipt(
            connectorConfusable, "rice",
            requireNotNull(localAmount(connectorConfusable, "2", BigDecimal("2"), "count")),
            requireNotNull(localAmount(connectorConfusable, "500", BigDecimal("500"), "g")),
            extended = true,
        ))
        val requestConfusable = "2 dabba rice lı aao, 500 g"
        assertNull(compileGenericContainerReceipt(
            requestConfusable, "rice",
            requireNotNull(localAmount(requestConfusable, "2", BigDecimal("2"), "count")),
            requireNotNull(localAmount(requestConfusable, "500", BigDecimal("500"), "g")),
            extended = true,
        ))
    }

    private data class FixtureCase(
        val id: String,
        val source: String,
        val name: String,
        val quantityText: String,
        val quantity: Int,
        val quantityUnit: String,
        val packText: String,
        val packValue: BigDecimal,
        val packUnit: String,
        val extended: Boolean,
        val accepted: Boolean,
    )

    private data class UnsizedFixtureCase(
        val id: String,
        val source: String,
        val name: String,
        val quantityText: String,
        val quantity: Int,
        val quantityUnit: String,
        val packText: String?,
        val packValue: BigDecimal?,
        val packUnit: String,
        val extended: Boolean,
        val accepted: Boolean,
    )

    private data class ContextFixtureCase(
        val id: String,
        val instruction: String,
        val sources: List<String>,
        val target: Int,
        val accepted: Boolean,
    )

    private fun loadFixture(): List<FixtureCase> {
        val path = "scripts/fixtures/intent_generic_container_receipt_oct28.json"
        val file = listOf(File(path), File("../$path")).firstOrNull(File::isFile)
        assertNotNull("Generic-container fixture not found: $path", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        assertEquals("beta-generic-container-receipt-v1", stringAt(root["schema"]))
        return arrayAt(root["cases"]).map { value ->
            val row = objectAt(value)
            FixtureCase(
                id = stringAt(row["id"]),
                source = stringAt(row["source"]),
                name = stringAt(row["name"]),
                quantityText = stringAt(row["quantity_text"]),
                quantity = numberAt(row["quantity"]),
                quantityUnit = stringAt(row["quantity_unit"]),
                packText = stringAt(row["pack_text"]),
                packValue = decimalAt(row["pack_value"]),
                packUnit = stringAt(row["pack_unit"]),
                extended = booleanAt(row["extended"]),
                accepted = booleanAt(row["accepted"]),
            )
        }
    }

    private fun loadUnsizedFixture(): List<UnsizedFixtureCase> {
        val path = "scripts/fixtures/intent_generic_container_receipt_oct28.json"
        val file = listOf(File(path), File("../$path")).firstOrNull(File::isFile)
        assertNotNull("Generic-container fixture not found: $path", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        return arrayAt(root["unsized_cases"]).map { value ->
            val row = objectAt(value)
            UnsizedFixtureCase(
                id = stringAt(row["id"]),
                source = stringAt(row["source"]),
                name = stringAt(row["name"]),
                quantityText = stringAt(row["quantity_text"]),
                quantity = numberAt(row["quantity"]),
                quantityUnit = stringAt(row["quantity_unit"]),
                packText = (row["pack_text"] as? JsonValue.Str)?.value,
                packValue = (row["pack_value"] as? JsonValue.Num)?.raw?.toBigDecimalOrNull(),
                packUnit = stringAt(row["pack_unit"]),
                extended = booleanAt(row["extended"]),
                accepted = booleanAt(row["accepted"]),
            )
        }
    }

    private fun loadContextFixture(): List<ContextFixtureCase> {
        val path = "scripts/fixtures/intent_generic_container_receipt_oct28.json"
        val file = listOf(File(path), File("../$path")).firstOrNull(File::isFile)
        assertNotNull("Generic-container fixture not found: $path", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        return arrayAt(root["context_cases"]).map { value ->
            val row = objectAt(value)
            ContextFixtureCase(
                id = stringAt(row["id"]),
                instruction = stringAt(row["instruction"]),
                sources = arrayAt(row["sources"]).map(::stringAt),
                target = numberAt(row["target"]),
                accepted = booleanAt(row["accepted"]),
            )
        }
    }

    private fun localAmount(source: String, text: String, value: BigDecimal, unit: String): IntentLocalAmount? {
        val start = source.indexOf(text)
        if (start < 0 || source.indexOf(text, start + text.length) >= 0) return null
        return IntentLocalAmount(source, start, start + text.length, text, value, unit)
    }

    private fun objectAt(value: JsonValue): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private fun numberAt(value: JsonValue?): Int =
        (value as? JsonValue.Num)?.raw?.toIntOrNull() ?: error("Expected integer JSON number")

    private fun decimalAt(value: JsonValue?): BigDecimal =
        (value as? JsonValue.Num)?.raw?.toBigDecimalOrNull() ?: error("Expected decimal JSON number")

    private fun booleanAt(value: JsonValue?): Boolean =
        (value as? JsonValue.Bool)?.value ?: error("Expected JSON boolean")
}
