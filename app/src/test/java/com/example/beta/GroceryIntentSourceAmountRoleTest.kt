package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import java.io.File
import java.security.MessageDigest
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentSourceAmountRoleTest {
    @Test fun pinnedFixtureMatchesBackendSourceTotalGrammar() {
        val relativePath = "scripts/fixtures/intent_source_totals_v1.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile) ?: error("Cannot find amount-role fixture: $relativePath")
        val source = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("c27a68ce8b3c1ba911e7449ca3f4f7f174ac056f299e4b207395a5f331e9f186", sha256)
        val document = (JsonParser(source).parseStrict() as JsonValue.Obj).members
        assertEquals("source-physical-total-v1", (document.getValue("version") as JsonValue.Str).value)
        val cases = (document.getValue("cases") as JsonValue.Arr).items
        assertEquals(56, cases.size)
        val ids = mutableSetOf<String>()
        cases.forEach { value ->
            val case = (value as JsonValue.Obj).members
            fun string(key: String) = (case.getValue(key) as JsonValue.Str).value
            val id = string("id")
            assertTrue("Duplicate fixture ID: $id", ids.add(id))
            val text = string("source")
            val atom = string("atom")
            val start = text.indexOf(atom)
            assertTrue("$id: atom absent", start >= 0)
            val parsed = parseIntentCorrectionAmount(atom)
            assertTrue("$id: fixture atom must parse", parsed != null)
            val amount = IntentLocalAmount(text, start, start + atom.length, atom,
                requireNotNull(parsed), string("unit"))
            assertEquals(id, (case.getValue("total") as JsonValue.Bool).value, isSourcePhysicalTotal(amount))
        }
    }

    @Test fun sourceBindingAndTypedUnitAreMandatory() {
        val source = "750 ml of juice"
        val value = BigDecimal("750")
        assertTrue(isSourcePhysicalTotal(IntentLocalAmount(source, 0, 3, "750", value, "ml")))
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(source, 1, 4, "750", value, "ml")))
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(source, 0, 3, "750", BigDecimal("500"), "ml")))
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(source, 0, 3, "750", value, "kg")))
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(source, 0, 3, "750", value, "count")))
    }

    @Test fun ambiguousAndPackConstrainedMeasuresAreNotTotals() {
        for (source in listOf(
            "750 ml juice", "750 ml pack juice", "750 ml bottle", "750 ml each",
            "750 ml of juice bottle", "total 750 ml pack juice", "750 ml of juice and 250 ml of milk",
        )) {
            val atom = "750"
            val unit = "ml"
            val start = source.indexOf(atom)
            assertFalse(source, isSourcePhysicalTotal(IntentLocalAmount(
                source, start, start + atom.length, atom, BigDecimal("750"), unit)))
        }
    }

    @Test fun exactFractionsAndUnicodeNominalsAreSupported() {
        val source = "half a litre of दूध"
        val atom = "half a"
        val parsed = requireNotNull(parseIntentCorrectionAmount(atom))
        assertTrue(isSourcePhysicalTotal(IntentLocalAmount(source, 0, atom.length, atom, parsed, "l")))
        val hindi = "500 g of दाल"
        assertTrue(isSourcePhysicalTotal(IntentLocalAmount(hindi, 0, 3, "500", BigDecimal("500"), "g")))
    }

    @Test fun compatibilityCharactersCannotSupplyUnitOrNominalEvidence() {
        val kelvin = "500 Kg of rice"
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(kelvin, 0, 3, "500", BigDecimal("500"), "kg")))
        val fullWidthOf = "750 ml ｏｆ juice"
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(fullWidthOf, 0, 3, "750", BigDecimal("750"), "ml")))
        val fullWidthNominal = "750 ml of ｊｕｉｃｅ"
        assertFalse(isSourcePhysicalTotal(IntentLocalAmount(fullWidthNominal, 0, 3, "750", BigDecimal("750"), "ml")))
    }

    @Test fun totalAmountPreparesAsVolumeAndKeepsDietConstraint() {
        val source = "750 ml of unsweetened juice"
        val prepared = prepare(source, row(source, name = "unsweetened juice", quantity = "750",
            quantityUnit = "ml", required = listOf("unsweetened")))
        val item = prepared.items.single()
        assertEquals(Quantity.Volume(750), item.quantity)
        assertEquals(setOf("unsweetened", "juice"), item.query.split(" ").toSet())
        assertTrue(item.query.contains("unsweetened"))
    }

    @Test fun totalAmountSupportsCataloguePackArithmeticAndRejectsNondivisorsOrUnknownSizes() {
        val source = "750 ml of juice"
        val item = prepare(source, row(source, name = "juice", quantity = "750",
            quantityUnit = "ml")).items.single()
        assertEquals(Quantity.Volume(750), item.quantity)
        for ((index, pair) in listOf("250 ml" to 3, "750 ml" to 1).withIndex()) {
            val (measure, count) = pair
            val candidate = RecommendationCandidate("juice", "total-$index", subtitle = measure, skuId = "total-$index")
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate), candidate))
            assertEquals(count, swiggyRequestedCartQuantity(assessed, candidate))
        }
        for ((index, subtitle) in listOf("300 ml", "1 L", "juice", "250 ml x 2").withIndex()) {
            val candidate = RecommendationCandidate("juice", "bad-total-$index", subtitle = subtitle, skuId = "bad-total-$index")
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate), candidate))
            assertTrue(subtitle, runCatching { swiggyRequestedCartQuantity(assessed, candidate) }.isFailure)
        }
    }

    @Test fun packOnlyAmountCannotBeReinterpretedAsTotalButCapacityMeasuresStayUnpromoted() {
        val source = "750 ml of juice"
        val packOnly = prepare(source, row(source, name = "juice", packValue = "750",
            packUnit = "ml", packText = "750"))
        assertTrue(packOnly.items.isEmpty())
        assertTrue(packOnly.unresolvedText.isNotEmpty() || packOnly.excludedText.isNotEmpty())

        for (capacity in listOf("500 ml measuring jug", "2 l pressure cooker")) {
            val atom = capacity.substringBefore(' ')
            val unit = capacity.substringAfter(' ').substringBefore(' ')
            val start = capacity.indexOf(atom)
            assertFalse(capacity, isSourcePhysicalTotal(IntentLocalAmount(capacity, start, start + atom.length,
                atom, requireNotNull(parseIntentCorrectionAmount(atom)), unit)))
        }
    }

    private fun prepare(instruction: String, row: String): GroceryIntentPreparation =
        GroceryIntentReviewV2Response.parse(response(instruction, row), instruction).draft.prepareForReview()

    @Test fun unrelatedGlobalCueDoesNotIntroduceNewRoleHoldOrHideUncoveredText() {
        val source = "750 ml of juice"
        for (other in listOf("biscuits without palm oil", "only butter", "tea today")) {
            val instruction = "$source; $other"
            val prepared = prepare(instruction, row(source, "juice", packValue = "750",
                packUnit = "ml", packText = "750"))
            assertTrue(other, intentV2HasCorrectionCue(instruction))
            val ready = GroceryIntentReviewV2Response.parse(response(source, row(source, "juice",
                packValue = "750", packUnit = "ml", packText = "750")), source).draft.readyRows.single()
            // Isolate the new guard from unrelated full-basket coverage gates.
            assertEquals("physical_total_as_pack", ready.groundingIssue(instruction = source,
                enforceSourceTotalRole = true))
            assertEquals(other, null, ready.groundingIssue(instruction = source,
                enforceSourceTotalRole = !intentV2HasCorrectionCue(instruction)))
            assertTrue(prepared.items.all { it.quantity == Quantity.Default })
            assertTrue(other, prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        }
    }

    private fun response(instruction: String, row: String): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":1,"ready_rows":[$row],"blocked_rows":[]},"model":"test","promptVersion":"source-amount-role-test","latencyMs":1,"usage":{}}"""

    private fun row(
        source: String,
        name: String,
        quantity: String? = null,
        quantityUnit: String = "unspecified",
        packValue: String? = null,
        packUnit: String = "unspecified",
        quantityText: String? = quantity,
        packText: String? = null,
        required: List<String> = emptyList(),
    ): String {
        val start = 0
        val q = quantity ?: "null"
        val qt = quantityText?.let(::json) ?: "null"
        val pv = packValue ?: "null"
        val pt = packText?.let(::json) ?: "null"
        val req = required.joinToString(",") { json(it) }
        val status = if (quantity == null && packValue == null) "not_stated" else "matched"
        val origin = if (quantity == null) "not_stated" else "stated"
        return """{"row_index":0,"item":{"name":${json(name)},"quantity":$q,"quantity_unit":${json(quantityUnit)},"pack_value":$pv,"pack_unit":${json(packUnit)},"packaging":null,"required":[$req],"excluded":[],"source_text":${json(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$qt,"pack_text":$pt},"evidence":{"primary":{"text":${json(source)},"start_utf16":$start,"end_utf16":${source.length}},"supporting":[]},"quantity_origin":${json(origin)},"amount_role_status":${json(status)}}"""
    }

    private fun json(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
