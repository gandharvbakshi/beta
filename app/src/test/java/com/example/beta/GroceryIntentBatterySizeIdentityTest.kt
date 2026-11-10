package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GroceryIntentBatterySizeIdentityTest {
    @Test fun fullLiteralDoubleAndTripleAFormsAreRetainedAsBatteryDescriptors() {
        listOf(
            Triple("one double A battery", "double A battery", 1),
            Triple("two triple-A batteries", "triple-A batteries", 2),
        ).forEach { (instruction, name, count) ->
            val result = review(instruction, listOf(
                readyRow(instruction, row(instruction, name, instruction, count, countWord(count))),
            )).prepareForReview()
            assertEquals(1, result.items.size)
            val parsed = result.items.single()
            assertTrue(parsed.query.lowercase().contains(name.lowercase()))
            assertEquals(Quantity.Count(count), parsed.quantity)
            assertEquals(instruction, parsed.intentQuantityProvenance?.primaryText)
            assertTrue(parsed.intentQuantityProvenance?.local != null)
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun unstatedCountDoesNotAcquireOneAndVoltageRemainsLiteralEvidence() {
        val unstated = "double A battery"
        val unstatedResult = review(unstated, listOf(readyRow(unstated, row(unstated, unstated, unstated, null, null))))
            .prepareForReview()
        assertEquals(1, unstatedResult.items.size)
        assertEquals(Quantity.Default, unstatedResult.items.single().quantity)
        assertEquals(unstated, unstatedResult.items.single().intentQuantityProvenance?.primaryText)

        val voltage = "one double A battery 1.5 volts"
        val voltageResult = review(voltage, listOf(readyRow(voltage, row(
            voltage, "double A battery 1.5 volts", voltage, 1, "one",
        )))).prepareForReview()
        assertEquals(1, voltageResult.items.size)
        val parsed = voltageResult.items.single()
        assertEquals(Quantity.Count(1), parsed.quantity)
        assertEquals(voltage, parsed.intentQuantityProvenance?.primaryText)
        assertTrue(parsed.strictMatchPhrase.orEmpty().contains("1.5 volts", ignoreCase = true))
        assertFalse(parsed.retailPackCount)
    }

    @Test fun overlappingAAsCountAndCroppedOrContaminatedDescriptorSourcesStayHeld() {
        val wrongName = "one double A battery"
        assertHeld(wrongName, row(wrongName, "battery", wrongName, 1, "one"))
        val aAsCount = "triple A batteries"
        assertHeld(aAsCount, row(aAsCount, "triple A batteries", aAsCount, 1, "A"))

        val appended = "one double A battery, 2 AA cells"
        assertHeld(appended, row(appended, "double A battery", "one double A battery", 1, "one"))

        val cropped = "one double A battery; tomorrow"
        assertHeld(cropped, row(cropped, "double A battery", "one double A battery", 1, "one"))

        val notBattery = "one double A connector"
        assertHeld(notBattery, row(notBattery, "double A battery", notBattery, 1, "one"))

        val wrongForm = "one double-A battery"
        assertHeld(wrongForm, row(wrongForm, "double A battery", wrongForm, 1, "one"))
        val croppedSpelling = "one double A batter"
        assertHeld(croppedSpelling, row(croppedSpelling, "AA battery", croppedSpelling, 1, "one"))

        val supportText = "one double A battery; from last time"
        val supportItem = row(supportText, "double A battery", "one double A battery", 1, "one",
            supporting = listOf("from last time"))
        val supportRow = readyRow(supportText, supportItem, supporting = listOf("from last time"))
        assertHeld(supportText, listOf(supportRow))
    }

    @Test fun wireCorrectionCannotBindTheLetterAAsACountOrMisquoteTheReplacementValue() {
        val corrected = "one double A battery; actually make it two"
        val base = row(corrected, "double A battery", "one double A battery", 1, "one")
        val primary = quote(corrected, "one double A battery")
        val source = quote(corrected, "actually make it two")
        val newAmount = quote(corrected, "two")
        val wire = mapOf(
            "kind" to JsonValue.Str("quantity"),
            "source" to quoteJson(source),
            "new_amount" to quoteJson(newAmount),
            "old_value" to JsonValue.Num("1"),
            "old_unit" to JsonValue.Str("count"),
            "new_value" to JsonValue.Num("3"),
            "new_unit" to JsonValue.Str("count"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Correction.parse(wire, corrected, base, primary)
        }
    }

    @Test fun catalogueIdentityNormalizesOnlyExactBatterySizeCodesInBothDirections() {
        listOf(
            "double A battery" to "AA Battery",
            "Double-A batteries" to "aa batteries",
            "triple A battery" to "AAA battery",
            "Triple-A batteries" to "aaa Batteries",
            "AA battery" to "Double A Battery",
            "AAA batteries" to "Triple-A Battery",
            "AAAA battery" to "AAAA Battery",
        ).forEach { (query, label) -> assertMatch(query, label, expected = true) }

        listOf(
            "double A battery" to "AAA battery",
            "double A battery" to "AAAA battery",
            "triple A battery" to "AA battery",
            "triple A battery" to "AA/AAA mixed battery",
            "double A battery" to "battery",
            "AAAA battery" to "AAA battery",
            "AAAA battery" to "AA/AAAA mixed battery",
        ).forEach { (query, label) -> assertMatch(query, label, expected = false) }

        listOf(
            "Not AA Battery",
            "AA Battery Not Included",
            "AA and C Batteries",
            "AA/D Batteries",
            "AA Battery Holder",
            "AA Battery Charger",
            "AA plus 18650 Batteries",
        ).forEach { label ->
            assertMatch(query = "double A battery", label = label, expected = false)
            assertMatch(query = "battery", label = label, strictMatchPhrase = "double A battery", expected = false)
        }

        assertMatch(query = "battery", label = "AA battery", strictMatchPhrase = "double A battery", expected = true)
        assertMatch(query = "triple A battery 1.5 volts", label = "AAA battery 3 volts", expected = false)

        assertMatch(
            query = "double A battery",
            label = "AA battery",
            strictMatchPhrase = "triple A battery",
            expected = false,
        )
        assertMatch(
            query = "double A battery",
            label = "AA battery",
            strictMatchPhrase = "double A battery 1.5 volts",
            expected = false,
        )
        assertMatch(
            query = "double A battery 1.5 volts",
            label = "AA battery 1.5 V",
            strictMatchPhrase = "double A battery 1.5 volts",
            expected = true,
        )
    }

    private fun assertMatch(query: String, label: String, strictMatchPhrase: String? = null, expected: Boolean) {
        val item = ParsedItem(rawText = query, query = query, strictMatchPhrase = strictMatchPhrase)
        assertEquals("query=$query label=$label strict=$strictMatchPhrase", expected,
            swiggyMatchesProductIdentity(item, label))
    }

    @Test fun voltageIsAnAtomicClaimAndMixedCodesCannotHideOnEitherSide() {
        listOf("AA battery 5.1 V", "AA battery 1.5/3 V", "AA battery -1.5 V",
            "AA battery 1.5 V/3 V", "AA battery 1.5 V - 3 V",
            "18650 and AA batteries 1.5 V", "18650 batteries plus AA batteries 1.5 V",
            "AA batteries and 18650 batteries 1.5 V").forEach { label ->
            assertMatch("double A battery 1.5 volts", label, expected = false)
            assertMatch("battery", label, strictMatchPhrase = "double A battery 1.5 volts", expected = false)
        }
    }

    @Test fun qualifiedSizeAndVoltageCannotBecomeAffirmativeEvidence() {
        listOf("Non-AA Battery", "Non AA Battery", "AA Battery up to 1.5 V",
            "AA Battery approximately 1.5 V", "AA Battery around 1.5 V", "AA Battery ≤1.5 V").forEach { label ->
            assertMatch("double A battery 1.5 V", label, expected = false)
            assertMatch("battery", label, strictMatchPhrase = "double A battery 1.5 V", expected = false)
        }
        assertMatch("double A battery", "Non-Rechargeable AA Battery", expected = true)
        listOf("double A battery" to "Non-AA Battery", "triple A battery" to "Non AAA Battery",
            "double A battery" to "Non-double A Battery", "triple A battery" to "Non triple A Battery",
            "double A battery" to "Non–AA Battery", "double A battery" to "AA Battery-Free Device").forEach { (query, label) ->
            assertMatch(query, label, expected = false)
            assertMatch("battery", label, strictMatchPhrase = query, expected = false)
        }
        assertMatch("double A battery", "Non-Rechargeable double A Battery", expected = true)
    }

    private fun assertHeld(instruction: String, candidate: GroceryIntentReviewV2Item) =
        assertHeld(instruction, listOf(readyRow(instruction, candidate)))

    private fun assertHeld(
        instruction: String,
        rows: List<GroceryIntentReviewV2ReadyRow>,
        groups: List<GroceryIntentReviewV2Group> = emptyList(),
    ) {
        assertTrue(review(instruction, rows, groups).prepareForReview().items.isEmpty())
    }

    private fun review(
        instruction: String,
        rows: List<GroceryIntentReviewV2ReadyRow>,
        groups: List<GroceryIntentReviewV2Group> = emptyList(),
    ) = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
        sourceItemCount = rows.size, readyRows = rows, blockedRows = emptyList(), groups = groups,
    )

    private fun row(
        instruction: String,
        name: String,
        source: String,
        count: Int?,
        countText: String?,
        supporting: List<String> = emptyList(),
    ): GroceryIntentReviewV2Item {
        require(instruction.contains(source))
        return GroceryIntentReviewV2Item(
        name = name, quantity = count?.let { BigDecimal.valueOf(it.toLong()) },
        quantityUnit = if (count == null) "unspecified" else "count",
        packValue = null, packUnit = "unspecified", packaging = null,
        required = emptyList(), excluded = emptyList(), sourceText = source,
        supportingText = supporting.map { GroceryIntentReviewV2SupportText("modifier", it) },
        needsClarification = false, quantityText = countText, packText = null,
        )
    }

    private fun readyRow(
        instruction: String,
        item: GroceryIntentReviewV2Item,
        index: Int = 0,
        supporting: List<String> = emptyList(),
        corrections: List<GroceryIntentReviewV2Correction> = emptyList(),
    ) = GroceryIntentReviewV2ReadyRow(
        rowIndex = index, item = item,
        evidence = GroceryIntentReviewV2Evidence(quote(instruction, item.sourceText), supporting.map {
            GroceryIntentReviewV2SupportQuote("modifier", quote(instruction, it))
        }),
        quantityOrigin = if (item.quantity == null) "not_stated" else "stated",
        amountRoleStatus = if (item.quantity == null && item.packValue == null) "not_stated" else "matched",
        corrections = corrections,
    )

    private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
        val start = instruction.indexOf(text)
        require(start >= 0)
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }

    private fun quoteJson(quote: GroceryIntentReviewV2Quote) = JsonValue.Obj(mapOf(
        "text" to JsonValue.Str(quote.text),
        "start_utf16" to JsonValue.Num(quote.startUtf16.toString()),
        "end_utf16" to JsonValue.Num(quote.endUtf16.toString()),
    ))

    private fun countWord(value: Int) = when (value) {
        1 -> "one"
        2 -> "two"
        else -> error("Synthetic count helper supports only the local test values")
    }
}
