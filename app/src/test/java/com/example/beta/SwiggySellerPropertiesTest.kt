package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggySellerPropertiesTest {
    @Test
    fun quoteUniquenessUsesWordBoundariesAndPreservesMultilineWhitespace() {
        for ((text, literal) in listOf("1 red shredded cabbage" to "red",
            "1 organic\nmilk" to "organic", "1 low\tfat milk" to "low\tfat")) {
            val source = quoteAt(text, text)
            val attribute = quoteAt(text, literal)
            val requirement = requireNotNull(SellerPropertyRequirement.fromSource("product", source, listOf(attribute)))
            assertEquals(source, requirement.source)
            assertEquals(listOf(attribute), requirement.requirements)
            assertEquals(requirement, SellerPropertyRequirement.fromPayload(JsonParser(payloadJson(requirement)).parseStrict()))
        }
    }

    @Test
    fun sourceRequirementPayloadRetainsExactWholeRowAndQuotedAttributeSpans() {
        val instruction = "Please get 1 organic low-fat milk"
        val primary = quoteAt(instruction, "1 organic low-fat milk")
        val organic = quoteAt(instruction, "organic")
        val lowFat = quoteAt(instruction, "low-fat")
        val requirement = requireNotNull(SellerPropertyRequirement.fromSource(
            "Organic---low fat milk", primary, listOf(organic, lowFat),
        ))

        assertEquals("organic low fat milk", requirement.subject)
        assertEquals(primary, requirement.source)
        assertEquals(listOf(organic, lowFat), requirement.requirements)
        assertEquals(listOf("r${organic.startUtf16}:${organic.endUtf16}",
            "r${lowFat.startUtf16}:${lowFat.endUtf16}"), requirement.properties)

        val payload = requirement.toPayload()
        assertEquals(setOf("version", "subject", "source", "requirements"), payload.keys)
        assertEquals(2, payload["version"])
        assertEquals(mapOf("text" to primary.text, "start_utf16" to primary.startUtf16,
            "end_utf16" to primary.endUtf16), payload["source"])
        val entries = payload["requirements"] as List<*>
        assertEquals(2, entries.size)
        assertEquals(mapOf("id" to "r${organic.startUtf16}:${organic.endUtf16}",
            "source" to mapOf("text" to "organic", "start_utf16" to organic.startUtf16,
                "end_utf16" to organic.endUtf16)), entries[0])
        assertEquals(requirement, SellerPropertyRequirement.fromPayload(JsonParser(payloadJson(requirement)).parseStrict()))
    }

    @Test
    fun sourceScopeRequiresExactUniqueNonoverlappingBoundarySafeQuotes() {
        val instruction = "organic rice then regular rice"
        val primary = quoteAt(instruction, "organic rice")
        val outside = quoteAt(instruction, "regular")
        assertNull(SellerPropertyRequirement.fromSource("rice", primary, listOf(outside)))

        val overlappingSource = "low fat free milk"
        val overlappingPrimary = quoteAt(overlappingSource, overlappingSource)
        assertNull(SellerPropertyRequirement.fromSource("milk", overlappingPrimary, listOf(
            quoteAt(overlappingSource, "low fat"), quoteAt(overlappingSource, "fat free"),
        )))

        val repeatedSource = "organic organic milk"
        val repeatedPrimary = quoteAt(repeatedSource, repeatedSource)
        assertNull(SellerPropertyRequirement.fromSource("milk", repeatedPrimary, listOf(
            quoteAt(repeatedSource, "organic", occurrence = 0), quoteAt(repeatedSource, "organic", occurrence = 1),
        )))

        val croppedSource = "organically grown rice"
        val croppedPrimary = quoteAt(croppedSource, croppedSource)
        val cropped = GroceryIntentReviewV2Quote("organic", 0, "organic".length)
        assertNull(SellerPropertyRequirement.fromSource("rice", croppedPrimary, listOf(cropped)))
    }

    @Test
    fun sourceRequirementBoundsAndUnicodeSpansAreStrict() {
        val instruction = "😀 organic milk"
        val primary = quoteAt(instruction, "organic milk")
        val organic = quoteAt(instruction, "organic")
        assertTrue(requireNotNull(SellerPropertyRequirement.fromSource("milk", primary, listOf(organic)))
            .source.startUtf16 > instruction.indexOf("😀"))

        assertNull(SellerPropertyRequirement.fromSource("x".repeat(201), primary, listOf(organic)))
        assertNull(SellerPropertyRequirement.fromSource("😀".repeat(201), primary, listOf(organic)))

        val tooLong = "x".repeat(321)
        val tooLongPrimary = quoteAt(tooLong, tooLong)
        assertNull(SellerPropertyRequirement.fromSource("rice", tooLongPrimary, listOf(
            GroceryIntentReviewV2Quote("x", 0, 1),
        )))

        val offsetSource = "x".repeat(12_000) + " organic rice"
        val offsetPrimary = quoteAt(offsetSource, "organic rice")
        assertNull(SellerPropertyRequirement.fromSource("rice", offsetPrimary,
            listOf(quoteAt(offsetSource, "organic"))))

        val primarySource = "1 " + "organic ".repeat(17) + "rice"
        val largePrimary = quoteAt(primarySource, primarySource)
        val seventeenRequirements = (0 until 17).map { quoteAt(primarySource, "organic", occurrence = it) }
        assertNull(SellerPropertyRequirement.fromSource("rice", largePrimary, seventeenRequirements))

        val maxPrimaryText = "x".repeat(320)
        val maxPrimary = quoteAt(maxPrimaryText, maxPrimaryText)
        assertFalse(SellerPropertyRequirement.fromSource("rice", maxPrimary, listOf(maxPrimary)) == null)
        val maxSubject = "😀".repeat(100)
        assertFalse(SellerPropertyRequirement.fromSource(maxSubject, quoteAt("organic rice", "organic rice"),
            listOf(quoteAt("organic rice", "organic"))) == null)

        val maxOffsetSource = "x".repeat(11_988) + "organic rice"
        assertFalse(SellerPropertyRequirement.fromSource("rice", quoteAt(maxOffsetSource, "organic rice"),
            listOf(quoteAt(maxOffsetSource, "organic"))) == null)
    }

    @Test
    fun sourceAndQuoteListsAreFrozenAndRedacted() {
        val instruction = "organic milk"
        val primary = quoteAt(instruction, instruction)
        val mutableRequirements = mutableListOf(quoteAt(instruction, "organic"))
        val requirement = requireNotNull(SellerPropertyRequirement.fromSource("Organic milk", primary, mutableRequirements))
        mutableRequirements.clear()

        assertEquals(1, requirement.requirements.size)
        assertEquals(listOf("r0:7"), requirement.properties)
        assertTrue(requirement.toString().contains("redacted"))
        assertThrows(UnsupportedOperationException::class.java) {
            (requirement.requirements as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (requirement.properties as MutableList).clear()
        }
    }

    @Test
    fun payloadParserRequiresVersionTwoAndStrictRecursiveSpanShape() {
        val valid = payloadJson(
            subject = "organic milk",
            primary = GroceryIntentReviewV2Quote("organic milk", 0, 12),
            requirements = listOf(GroceryIntentReviewV2Quote("organic", 0, 7)),
        )
        assertEquals(listOf("r0:7"), SellerPropertyRequirement.fromPayload(JsonParser(valid).parseStrict()).properties)

        val invalid = listOf(
            "null",
            "{}",
            """{"subject":"milk","properties":["organic"]}""", // v1 has no fallback.
            valid.replace("\"version\":2", "\"version\":1"),
            valid.replace("\"version\":2", "\"version\":2.0"),
            valid.replace("\"id\":\"r0:7\"", "\"id\":\"r1:8\""),
            valid.replace("\"end_utf16\":7", "\"end_utf16\":6"),
            valid.replace("\"source\":{\"text\":\"organic\"", "\"source\":{\"text\":\"organically\""),
            valid.replace("\"version\":2,", "\"version\":2,\"version\":2,"),
            valid.replace("\"id\":\"r0:7\"", "\"id\":\"r0:7\",\"extra\":true"),
        )
        invalid.forEach { body ->
            assertTrue(body, runCatching {
                SellerPropertyRequirement.fromPayload(JsonParser(body).parseStrict())
            }.isFailure)
        }

        val splitSurrogate = payloadJson(
            subject = "milk",
            primary = GroceryIntentReviewV2Quote("😀milk", 0, 6),
            requirements = listOf(GroceryIntentReviewV2Quote("milk", 2, 6)),
        ).replace("\"start_utf16\":2,\"end_utf16\":6", "\"start_utf16\":1,\"end_utf16\":5")
        assertFalse(runCatching {
            SellerPropertyRequirement.fromPayload(JsonParser(splitSurrogate).parseStrict())
        }.isSuccess)
    }
}

private fun quoteAt(original: String, text: String, occurrence: Int = 0): GroceryIntentReviewV2Quote {
    var start = 0
    repeat(occurrence) {
        start = original.indexOf(text, start).also { require(it >= 0) } + text.length
    }
    start = original.indexOf(text, start).also { require(it >= 0) }
    return GroceryIntentReviewV2Quote.parse(mapOf(
        "text" to JsonValue.Str(text),
        "start_utf16" to JsonValue.Num(start.toString()),
        "end_utf16" to JsonValue.Num((start + text.length).toString()),
    ), original, 640)
}

private fun payloadJson(requirement: SellerPropertyRequirement): String = payloadJson(
    requirement.subject, requirement.source, requirement.requirements,
)

private fun payloadJson(
    subject: String,
    primary: GroceryIntentReviewV2Quote,
    requirements: List<GroceryIntentReviewV2Quote>,
): String = buildString {
    append("{\"version\":2,\"subject\":${quoteJson(subject)},\"source\":${quoteObject(primary)}")
    append(",\"requirements\":[")
    append(requirements.joinToString(",") { quote ->
        "{\"id\":${quoteJson("r${quote.startUtf16}:${quote.endUtf16}")},\"source\":${quoteObject(quote)}}"
    })
    append("]}")
}

private fun quoteObject(quote: GroceryIntentReviewV2Quote): String =
    "{\"text\":${quoteJson(quote.text)},\"start_utf16\":${quote.startUtf16},\"end_utf16\":${quote.endUtf16}}"

private fun quoteJson(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
