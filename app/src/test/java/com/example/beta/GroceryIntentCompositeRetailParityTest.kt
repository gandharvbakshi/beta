package com.example.beta

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCompositeRetailParityTest {
    private data class Expected(
        val countAtom: String,
        val countValue: String,
        val factorAtom: String,
        val factorValue: String,
        val measureAtom: String,
        val measureValue: String,
        val measureUnit: String,
        val containerText: String,
        val containerKey: String,
        val identity: String,
        val shapeText: String,
    )

    @Test
    fun sourceOnlyReceiptCompilerMatchesSharedFixture() {
        val cases = loadFixture()
        assertTrue("fixture should contain at least 40 vectors", cases.size >= 40)
        cases.forEach { (id, source, expected) ->
            val receipt = sourceCompositeRetailReceipt(source)
            if (expected == null) {
                assertNull("$id should remain outside the anchored source grammar", receipt)
            } else {
                assertNotNull("$id should compile as a lexical source receipt", receipt)
                val actual = requireNotNull(receipt)
                assertEquals("$id source", source, actual.source)
                assertAmount(id, source, actual.requestedCount, expected.countAtom, expected.countValue, "count")
                assertAmount(id, source, actual.skuFactor, expected.factorAtom, expected.factorValue, "factor")
                assertAmount(id, source, actual.innerMeasure, expected.measureAtom, expected.measureValue, "measure")
                assertEquals("$id unit", expected.measureUnit, actual.innerMeasure.unit)
                assertEquals("$id container", expected.containerText,
                    source.substring(actual.container.first, actual.container.last + 1))
                assertEquals("$id container key", expected.containerKey, actual.containerKey)
                assertEquals("$id identity", expected.identity,
                    source.substring(actual.identity.first, actual.identity.last + 1))
                assertEquals("$id shape", expected.shapeText,
                    source.substring(actual.shapeSpan.first, actual.shapeSpan.last + 1))
                val structuralShape = actual.shape()
                val expectedDimension = if (expected.measureUnit in setOf("g", "kg")) {
                    GroceryPackDimension.WEIGHT
                } else {
                    GroceryPackDimension.VOLUME
                }
                val expectedBase = BigDecimal(expected.measureValue).multiply(
                    if (expected.measureUnit in setOf("kg", "l")) BigDecimal("1000") else BigDecimal.ONE,
                )
                assertEquals("$id structural factor", expected.factorValue.toInt(), structuralShape.factor)
                assertEquals("$id structural dimension", expectedDimension, structuralShape.inner.dimension)
                assertEquals("$id structural base measure", 0,
                    expectedBase.compareTo(structuralShape.inner.baseValue))
                val spans = listOf(
                    actual.requestedCount.startUtf16 until actual.requestedCount.endUtf16,
                    actual.skuFactor.startUtf16 until actual.skuFactor.endUtf16,
                    actual.innerMeasure.startUtf16 until actual.innerMeasure.endUtf16,
                )
                assertFalse("$id amount spans must not overlap", spans.indices.any { left ->
                    (left + 1 until spans.size).any { right -> spans[left].any { it in spans[right] } }
                })
                assertTrue("$id factor and measure belong inside the SKU shape",
                    spans.drop(1).all { span -> span.all { it in actual.shapeSpan } })
                assertTrue("$id requested count is outside the SKU shape",
                    spans.first().none { it in actual.shapeSpan })
                assertEquals("$id redacted diagnostics", "CompositeRetailReceipt(<redacted>)", actual.toString())
                assertFalse("$id diagnostics must not reveal source", actual.toString().contains(source))
            }
        }
    }

    private fun loadFixture(): List<Triple<String, String, Expected?>> {
        val path = "scripts/fixtures/grocery_composite_retail_v1.json"
        val file = listOf(File(path), File("../$path")).firstOrNull(File::isFile)
        assertNotNull("Composite-retail fixture not found: $path", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        assertEquals("grocery-composite-retail-v1", stringAt(root["schema"]))
        return arrayAt(root["cases"]).map { raw ->
            val row = objectAt(raw)
            val expectedValue = row["expected"]
            val expected = if (expectedValue == JsonValue.Null) null else {
                val fields = objectAt(expectedValue)
                Expected(
                    stringAt(fields["count_atom"]), stringAt(fields["count_value"]),
                    stringAt(fields["factor_atom"]), stringAt(fields["factor_value"]),
                    stringAt(fields["measure_atom"]), stringAt(fields["measure_value"]),
                    stringAt(fields["measure_unit"]), stringAt(fields["container_text"]),
                    stringAt(fields["container_key"]), stringAt(fields["identity"]),
                    stringAt(fields["shape_text"]),
                )
            }
            Triple(stringAt(row["id"]), stringAt(row["source"]), expected)
        }
    }

    private fun assertAmount(
        id: String,
        source: String,
        actual: com.example.beta.automation.IntentLocalAmount,
        atom: String,
        value: String,
        role: String,
    ) {
        assertEquals("$id $role atom", atom, actual.atom)
        assertEquals("$id $role original span", atom, source.substring(actual.startUtf16, actual.endUtf16))
        assertEquals("$id $role value", 0, actual.value.compareTo(BigDecimal(value)))
        assertEquals("$id $role source binding", source, actual.source)
    }

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")
}
