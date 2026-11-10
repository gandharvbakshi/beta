package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Freshness is owned by original source, not supplied by a catalogue brand token. */
class GroceryIntentFreshnessCatalogueTest {
    @Test fun sharedFixtureMatchesRejectOnlyFormHelperNotFullCatalogueAcceptance() {
        val relativePath = "scripts/fixtures/swiggy_form_gate_cases.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot find shared form-gate fixture at $relativePath")
        val text = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("8969c7811e22f9216e9aa7e1ef78bced339f1809854799c02786ce230b31ccd6", sha256)
        val document = objectAt(JsonParser(text).parseStrict())
        assertEquals("swiggy-form-contradiction-v1", stringAt(document["schema"]))
        val cases = arrayAt(document["cases"])
        assertEquals(31, cases.size)
        cases.forEach { value ->
            val case = objectAt(value)
            val expected = (case["allowed"] as? JsonValue.Bool)?.value ?: error("Expected boolean allowed")
            assertEquals(stringAt(case["id"]), expected, swiggyProductFormsAllowed(
                arrayAt(case["requests"]).map(::stringAt),
                arrayAt(case["exclusions"]).map(::stringAt),
                arrayAt(case["fields"]).map(::stringAt),
            ))
        }
    }

    @Test fun matchingFreshPhysicalPackRetainsSourceAndResolvesToOneCartUnit() {
        val item = preparedFreshSpinach()
        listOf("Fresh Spinach 250 g", "Spinach 250 g").forEachIndexed { index, label ->
            val candidate = candidate(label, "fresh-$index")
            assertTrue(label, isSwiggyCandidateAllowed(item, candidate))
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
            assertEquals(1, swiggyRequestedCartQuantity(assessed, candidate))
            assertEquals(Quantity.Weight(250), assessed.quantity)
            assertEquals(source, assessed.intentQuantityProvenance?.primaryText)
            assertEquals(listOf("dried"), assessed.avoidPhrases)
        }
    }

    @Test fun contradictoryPreparationFormsCannotSatisfySourceOwnedFreshness() {
        val item = preparedFreshSpinach()
        val incorrectlyAllowed = listOf(
            "Dried Spinach 250 g",
            "Dry Spinach 250 g",
            "Dehydrated Spinach 250 g",
            "Frozen Spinach 250 g",
        ).filterIndexed { index, label ->
            isSwiggyCandidateAllowed(item, candidate(label, "contradiction-$index"))
        }
        assertEquals(emptyList<String>(), incorrectlyAllowed)
    }

    @Test fun freshBrandLikeTokenCannotOverrideAnExplicitFrozenTitle() {
        val item = preparedFreshSpinach()
        listOf(
            "Fresh Frozen Spinach 250 g", "Fresh Dried Spinach 250 g",
            "Fresho Frozen Spinach 250 g", "Fresho Dried Spinach 250 g",
        ).forEachIndexed { index, label ->
            assertFalse(label, isSwiggyCandidateAllowed(item, candidate(label, "brand-form-$index")))
        }
    }

    @Test fun explicitDriedExclusionAlsoRejectsDryAndDehydratedWithoutFreshRequest() {
        val item = legacyItem("spinach", avoid = listOf("dried"))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Spinach 250 g", "plain")))
        // A dried-only exclusion does not manufacture a separate frozen exclusion.
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Frozen Spinach 250 g", "frozen-only")))
        listOf("Dried", "Dry", "Dehydrated").forEach { form ->
            assertFalse(form, isSwiggyCandidateAllowed(item, candidate("$form Spinach 250 g", form)))
        }
    }

    @Test fun compoundExclusionDoesNotBecomeAnAllDriedProductBan() {
        val item = legacyItem("spinach", avoid = listOf("dried fruit"))
        listOf("Dried Spinach 250 g", "Dry Spinach 250 g", "Dehydrated Spinach 250 g").forEachIndexed { index, label ->
            assertTrue(label, isSwiggyCandidateAllowed(item, candidate(label, "compound-$index")))
        }
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Spinach with Dried Fruit 250 g", "literal-compound")))
    }

    @Test fun queryAndStrictPhraseEachIndependentlyEnforceFreshness() {
        listOf(
            legacyItem("fresh spinach", strict = "spinach"),
            legacyItem("spinach", strict = "fresh spinach"),
        ).forEach { item ->
            assertTrue(isSwiggyCandidateAllowed(item, candidate("Spinach 250 g", "independent-plain")))
            listOf("Frozen", "Dried", "Dry", "Dehydrated").forEach { form ->
                assertFalse("query=${item.query}, strict=${item.strictMatchPhrase}, form=$form",
                    isSwiggyCandidateAllowed(item, candidate("$form Spinach 250 g", "independent-$form")))
            }
        }
    }

    @Test fun contradictoryVariantOrSubtitleCannotHideBehindFreshTitle() {
        val item = preparedFreshSpinach()
        for (form in listOf("Frozen", "Dried", "Dry", "Dehydrated")) {
            val label = candidate("Fresh Spinach 250 g", "metadata-$form")
            assertFalse("variant=$form", isSwiggyCandidateAllowed(item, label.copy(variant = form)))
            assertFalse("subtitle=$form", isSwiggyCandidateAllowed(item, label.copy(subtitle = form)))
        }
    }

    @Test fun rawVariantCannotDisappearInsideAnUnrelatedBrandSubstring() {
        val item = preparedFreshSpinach()
        val product = candidate("Dryad Fresh Spinach 250 g", "substring-variant").copy(variant = "Dry")
        assertFalse(isSwiggyCandidateAllowed(item, product))
    }

    @Test fun explicitlyRequestedFrozenFormIsNotRejectedAsAnImplicitFreshRequest() {
        for (request in listOf("frozen spinach", "fresh frozen spinach")) {
            val item = legacyItem(request, strict = request)
            assertTrue(request, isSwiggyCandidateAllowed(item, candidate("Fresh Frozen Spinach 250 g", "requested-frozen")))
            assertTrue(request, isSwiggyCandidateAllowed(item, candidate("Frozen Spinach 250 g", "literal-frozen")))
        }
    }

    @Test fun formCompatibilityDoesNotRelaxExactPhysicalPackQuantity() {
        val item = preparedFreshSpinach()
        val oversized = candidate("Fresh Spinach 500 g", "oversized")
        assertEquals(null, swiggyMeasuredPackQuantity(item, oversized))
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(oversized)))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessed, oversized) }
    }

    @Test fun sourceBoundFreshMangoRejectsPickleAndPickledCatalogueProducts() {
        val instruction = "250 g fresh mango"
        val compiledItem = GroceryIntentReviewV2Item(
            name = "fresh mango", quantity = BigDecimal("250"), quantityUnit = "g", quantityText = "250",
            packValue = null, packUnit = "unspecified", packText = null, packaging = null,
            required = listOf("fresh"), excluded = emptyList(), sourceText = instruction,
            supportingText = emptyList(), needsClarification = false,
        )
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0, item = compiledItem,
            evidence = GroceryIntentReviewV2Evidence(
                primary = GroceryIntentReviewV2Quote(instruction, 0, instruction.length), supporting = emptyList(),
            ),
            quantityOrigin = "stated", amountRoleStatus = "matched",
        )
        val prepared = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
            sourceItemCount = 1, readyRows = listOf(row), blockedRows = emptyList(), groups = emptyList(),
        ).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        val item = prepared.items.single()
        assertEquals(Quantity.Weight(250), item.quantity)
        assertEquals(instruction, item.intentQuantityProvenance?.primaryText)
        assertTrue(item.query.contains("fresh", ignoreCase = true))
        val plain = candidate("Mango 250 g", "fresh-mango")
        assertTrue(isSwiggyCandidateAllowed(item, plain))
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(plain)))
        assertEquals(1, swiggyRequestedCartQuantity(assessed, plain))
        for (label in listOf("Mango Pickle 250 g", "Pickled Mango 250 g")) {
            assertFalse(label, isSwiggyCandidateAllowed(item, candidate(label, "prepared-mango")))
        }
    }

    @Test fun helperFieldsOnlyPlainLabelAndSeparateFrozenFieldRemainContradictory() {
        // Helper-level fields only: this does not invent an Android candidate "name" API field.
        val requests = listOf("fresh mango")
        assertTrue(swiggyProductFormsAllowed(requests, emptyList(), listOf("Mango 250 g")))
        assertFalse(swiggyProductFormsAllowed(requests, emptyList(), listOf("Mango 250 g", "Frozen")))
    }

    private fun legacyItem(query: String, strict: String? = null, avoid: List<String> = emptyList()) = ParsedItem(
        rawText = "250 g $query", query = query, strictMatchPhrase = strict, avoidPhrases = avoid, quantity = Quantity.Weight(250),
    )

    private fun preparedFreshSpinach(): ParsedItem {
        val compiledItem = GroceryIntentReviewV2Item(
            name = "fresh spinach", quantity = BigDecimal("250"), quantityUnit = "g", quantityText = "250",
            packValue = null, packUnit = "unspecified", packText = null, packaging = null,
            required = listOf("fresh"), excluded = listOf("dried"), sourceText = source,
            supportingText = emptyList(), needsClarification = false,
        )
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0, item = compiledItem,
            evidence = GroceryIntentReviewV2Evidence(
                primary = GroceryIntentReviewV2Quote(source, 0, source.length), supporting = emptyList(),
            ),
            quantityOrigin = "stated", amountRoleStatus = "matched",
        )
        val prepared = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = source,
            sourceItemCount = 1, readyRows = listOf(row), blockedRows = emptyList(), groups = emptyList(),
        ).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        return prepared.items.single().also {
            assertEquals(Quantity.Weight(250), it.quantity)
            assertTrue(it.query.contains("fresh", ignoreCase = true))
            assertEquals(listOf("dried"), it.avoidPhrases)
            assertEquals(source, it.intentQuantityProvenance?.primaryText)
        }
    }

    private fun candidate(label: String, id: String) = RecommendationCandidate(
        spinId = "synthetic-$id", skuId = "synthetic-sku-$id", label = label,
    )

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private val source = "250 g fresh spinach, not the dried one"
}
