package com.example.beta

import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class GroceryPhysicalUnitOccurrencesTest {
    private val fixture: Map<String, JsonValue> by lazy {
        val path = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "scripts/fixtures/grocery_physical_unit_occurrences_v1.json") }
            .firstOrNull(File::isFile)
            ?: error("Shared physical-unit fixture was not found from ${System.getProperty("user.dir")}")
        objectAt(JsonParser(String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8)).parseStrict())
    }

    @Test fun physicalUnitAliasesMatchOnlyTheirExactFixtureOccurrenceAndUtf16End() {
        val cases = arrayAt(fixture["positive"])
        for (raw in cases) {
            val case = objectAt(raw)
            val id = stringAt(case["id"])
            val source = stringAt(case["source"])
            val amountText = stringAt(case["amount_text"])
            val amountEnd = source.indexOf(amountText) + amountText.length
            val unit = stringAt(case["unit"])
            val match = GroceryIntentPhysicalUnitMatcher.after(source, amountEnd, unit)

            assertNotNull(id, match)
            requireNotNull(match)
            assertEquals(id, numberAt(case["expected_end_utf16"]), match.end)
            assertEquals(id, stringAt(case["matched_suffix"]), source.substring(amountEnd, match.end))
            assertEquals(id, match.token, source.substring(match.start, match.end))
        }
    }

    @Test fun physicalUnitMatcherRejectsLetterCombiningBrandAndUnknownUnitContinuations() {
        val cases = arrayAt(fixture["negative"])
        for (raw in cases) {
            val case = objectAt(raw)
            val id = stringAt(case["id"])
            val source = stringAt(case["source"])
            val amountText = stringAt(case["amount_text"])
            val amountEnd = source.indexOf(amountText) + amountText.length

            assertNull(id, GroceryIntentPhysicalUnitMatcher.after(
                source, amountEnd, stringAt(case["unit"]),
            ))
        }
    }

    @Test fun legacyCountAmountsCannotConsumeKnownPhysicalUnitOccurrences() {
        val cases = arrayAt(fixture["legacyPhysicalAsCount"])
        for (raw in cases) {
            val case = objectAt(raw)
            val id = stringAt(case["id"])
            val source = stringAt(case["source"])

            assertNull(id, sourceQuotedLocalAmount(
                source, stringAt(case["amount_text"]), stringAt(case["legacy_unit"]),
            ))
        }
    }

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private fun numberAt(value: JsonValue?): Int =
        (value as? JsonValue.Num)?.raw?.toInt() ?: error("Expected JSON integer")
}
