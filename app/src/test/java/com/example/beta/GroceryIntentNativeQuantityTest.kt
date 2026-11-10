package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.ProductLexicon
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GroceryIntentNativeQuantityTest {
    @Test fun decimalNumericProductIdentityIsNotAQuantityAndKeepsUtf16Offsets() {
        for ((source, atom, key) in listOf(
            Triple("٥ Star chocolate", "٥", "5 star"), Triple("७ Up drink", "७", "7 up"),
            Triple("২৪ Mantra rice", "২৪", "24 mantra"), Triple("Maggi ೨ minutes noodles", "೨", "maggi 2 minute"),
            Triple("𝟝 Star chocolate", "𝟝", "5 star"), Triple("🙂 𝟝 Star chocolate", "𝟝", "5 star"),
        )) {
            val identity = ProductLexicon.numericProductIdentities(source).single()
            assertEquals(key, identity.key)
            assertEquals(atom, source.substring(identity.amountRange))
            assertTrue(ProductLexicon.normalizeNumericProductIdentities(source).contains(key))
            assertNull(source, sourceQuotedLocalAmount(source, atom, "count"))
        }
    }

    @Test fun decimalDigitScriptsAreArithmeticOnlyNotCompatibilityNormalization() {
        listOf("२००" to "200", "১.২৫" to "1.25", "٢/٤" to "0.5", "１２,５００" to "12500",
            "੨੦" to "20", "૨૫૦" to "250", "೧೦" to "10", "௨" to "2", "൨" to "2", "𝟚" to "2")
            .forEach { (raw, expected) -> assertEquals(raw, 0, BigDecimal(expected).compareTo(parseIntentCorrectionAmount(raw))) }
        listOf("²", "①", "Ⅳ", "१e३", "१+२", "१-२", "١٫٢", "१,२", "०", "१०००००१")
            .forEach { raw -> assertNull(raw, parseIntentCorrectionAmount(raw)) }
    }

    @Test fun countProjectionExcludesOnlyCompleteOriginalMeasuredMetadata() {
        listOf("1 litre each", "400 ml", "500 millilitres har ek.", "दो सौ मिलीलीटर each").forEach { fragment ->
            assertTrue(fragment, groceryIntentMeasuredMetadata("2 bottles milk, $fragment", fragment))
        }
        listOf("1 litre bottle", "1 litre with 2 bottles", "1 litre each 2 packs", "1 litre per 2 bottles",
            "1 litre gram flour", "1 litre each milk", "1 litre and", "1 litre or 2 litres")
            .forEach { fragment -> assertFalse(fragment, groceryIntentMeasuredMetadata("2 bottles milk, $fragment", fragment)) }
        assertFalse(groceryIntentMeasuredMetadata("2 bottles milk, 1 litre each", "1 l each"))
        assertFalse(groceryIntentMeasuredMetadata("1 litre each; 1 litre each", "1 litre each"))
        assertFalse(groceryIntentMeasuredMetadata("2 milk, 1 litre bottle", "1 litre"))
        assertFalse(groceryIntentMeasuredMetadata("2 milk, 1 litre each 2 packs", "1 litre"))
        assertFalse(groceryIntentMeasuredMetadata("2 milk, not 1 litre each", "1 litre each"))
    }

    @Test fun physicalUnitComparisonUsesOnlyBoundOriginalTokens() {
        listOf(
            "250millilitres milk" to "250ml milk",
            "250 millilitres milk; 500 grams lentils" to "250 ml milk; 500 g lentils",
            "दो सौ मिलीलीटर milk" to "दो सौ ml milk",
            "500 g gram flour" to "500 g gram flour",
        ).forEach { (source, expected) ->
            assertEquals(source, expected, groceryIntentPhysicalUnitComparisonView(source))
        }
        listOf(
            "250 millilitres milk; 250 millilitres juice",
            "सवा एक किलो rice", "एक लीटर\u093e milk", "1 kilometre rice",
            "1 L'Oreal shampoo", "1 L’Oreal shampoo", "1 mlong shampoo", "Millilitres shampoo",
        ).forEach { source -> assertEquals(source, source, groceryIntentPhysicalUnitComparisonView(source)) }
        val source = "250 millilitres milk"
        val masked = source.replaceRange(4, 15, " ".repeat(11))
        assertEquals(masked, groceryIntentPhysicalUnitComparisonView(source, masked))
        assertEquals("milk", groceryIntentPhysicalUnitComparisonView(source, "milk"))
        assertEquals(setOf("milk"), groceryIntentFragmentIdentityTokens(source))
        assertEquals(intentEvidenceTokens("gram flour"), groceryIntentFragmentIdentityTokens("500 g gram flour"))
    }

    @Test fun physicalUnitComparisonCannotEraseAnUnownedProductWord() {
        listOf(
            Triple("500 g gram flour", "flour", "g"),
            Triple("500 g black gram flour", "black flour", "g"),
            Triple("250 ml Millilitres shampoo", "shampoo", "ml"),
            Triple("250 ml L'Oreal shampoo", "shampoo", "ml"),
            Triple("250 ml mlong shampoo", "shampoo", "ml"),
        ).forEach { (source, name, unit) ->
            val quantity = if (unit == "g") "500" else "250"
            val prepared = runCatching {
                GroceryIntentContextResponse.parse(contextBody(source, source, quantity, unit, name = name), source)
                    .prepareForReview()
            }.getOrNull()
            assertTrue(source, prepared == null || prepared.blockingIssues.isNotEmpty())
        }
    }

    @Test fun physicalUnitComparisonRetainsProductIdentityAndRejectsChangedAmount() {
        listOf("gram flour", "black gram flour", "green gram", "L'Oreal shampoo").forEach { name ->
            val unit = if (name.endsWith("shampoo")) "ml" else "g"
            val spelling = if (unit == "ml") "millilitres" else "grams"
            val source = "250 $spelling $name"
            val result = GroceryIntentContextResponse.parse(
                contextBody(source, source, "250", unit, name = name), source,
            ).prepareForReview()
            assertTrue(source, result.blockingIssues.isEmpty())
            assertFalse(source, result.legacyPreparation.items.single().unprovenIdentityScope)
            assertEquals(source, result.legacyPreparation.items.single().rawText)
            assertThrows(source, IntentNeedsReviewException::class.java) {
                GroceryIntentContextResponse.parse(contextBody(source, source, "500", unit, name = name), source)
                    .prepareForReview()
            }
        }
    }

    @Test fun physicalUnitAliasesDoNotBecomeProductIdentity() {
        val aliases = listOf(
            Triple("250", "g", listOf("grams", "gms", "ग्राम")),
            Triple("1", "kg", listOf("kilogram", "kilos", "kgs", "किलोग्राम", "किलो")),
            Triple("250", "ml", listOf("millilitres", "milliliters", "मिलीलीटर", "मिलिलीटर", "मिलिलिटर")),
            Triple("1", "l", listOf("litres", "liters", "ltr", "लीटर", "लिटर")),
        )
        val failures = mutableListOf<String>()
        aliases.forEach { (amount, unit, words) ->
            val name = if (unit in setOf("g", "kg")) "lentils" else "milk"
            words.forEach { word ->
                val source = "$amount $word $name"
                val result = runCatching {
                    GroceryIntentContextResponse.parse(contextBody(source, source, amount, unit, name = name), source)
                        .prepareForReview()
                }
                val prepared = result.getOrNull()
                if (prepared == null || prepared.blockingIssues.isNotEmpty() || prepared.pendingCounts.isNotEmpty()) {
                    failures += "$unit/$word: ${prepared?.blockingIssues?.map { it.kind } ?: result.exceptionOrNull()?.javaClass?.simpleName}"
                } else {
                    val parsed = prepared.legacyPreparation.items.single()
                    assertFalse(source, parsed.unprovenIdentityScope)
                    assertEquals(source, source, parsed.rawText)
                    assertEquals(source, if (unit in setOf("g", "kg"))
                        Quantity.Weight(if (unit == "kg") 1000 else 250) else
                        Quantity.Volume(if (unit == "l") 1000 else 250), parsed.quantity)
                }
            }
        }
        assertEquals("A physical-unit spelling is not a product qualifier", emptyList<String>(), failures)
    }

    @Test fun closedHindiNumeralsAndFractionsParseExactly() {
        val whole = listOf(
            "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4", "पांच" to "5", "पाँच" to "5",
            "छह" to "6", "छः" to "6", "सात" to "7", "आठ" to "8", "नौ" to "9", "दस" to "10",
            "ग्यारह" to "11", "बारह" to "12", "तेरह" to "13", "चौदह" to "14", "पंद्रह" to "15",
            "पन्द्रह" to "15", "सोलह" to "16", "सत्रह" to "17", "अठारह" to "18", "उन्नीस" to "19",
            "बीस" to "20", "तीस" to "30", "चालीस" to "40", "पचास" to "50", "साठ" to "60",
            "सत्तर" to "70", "अस्सी" to "80", "नब्बे" to "90", "सौ" to "100",
        )
        whole.forEach { (atom, expected) ->
            assertEquals(atom, 0, BigDecimal(expected).compareTo(parseIntentCorrectionAmount(atom)))
        }
        listOf("आधा" to "0.5", "आधी" to "0.5", "पाव" to "0.25", "डेढ़" to "1.5", "ढाई" to "2.5")
            .forEach { (atom, expected) ->
                assertEquals(atom, 0, BigDecimal(expected).compareTo(parseIntentCorrectionAmount(atom)))
            }
        listOf("एक सौ" to "100", "नौ सौ" to "900", "आधा सौ" to "50",
            "डेढ़ सौ" to "150", "ढाई सौ" to "250")
            .forEach { (atom, expected) ->
                assertEquals(atom, 0, BigDecimal(expected).compareTo(parseIntentCorrectionAmount(atom)))
            }
    }

    @Test fun nativePhysicalAliasesBindAtUnicodeSafeBoundaries() {
        val positive = listOf(
            listOf("एक ग्राम चावल", "एक", "1", "g"),
            listOf("एक किलोग्राम चावल", "एक", "1", "kg"),
            listOf("एक किलो चावल", "एक", "1", "kg"),
            listOf("एक मिलीलीटर दूध", "एक", "1", "ml"),
            listOf("एक मिलिलीटर दूध", "एक", "1", "ml"),
            listOf("एक मिलिलिटर दूध", "एक", "1", "ml"),
            listOf("दो सौ मिलीलीटर दूध", "दो सौ", "200", "ml"),
            listOf("एक लीटर दूध", "एक", "1", "l"),
            listOf("एक लिटर दूध", "एक", "1", "l"),
            listOf("500मिलीलीटर दूध", "500", "500", "ml"),
        )
        positive.forEach { (source, atom, expected, unit) ->
            val start = source.indexOf(atom)
            assertTrue(source, physicalUnitFollowsAt(source, start, atom, unit))
            val bound = requireNotNull(sourceQuotedLocalAmount(source, atom, unit))
            assertEquals(source, start, bound.startUtf16)
            assertEquals(source, start + atom.length, bound.endUtf16)
            assertEquals(source, atom, bound.atom)
            assertEquals(source, 0, BigDecimal(expected).compareTo(bound.value))
            assertEquals(source, unit, bound.unit)
            val item = GroceryIntentItem(
                name = source.substringAfterLast(' '), quantity = BigDecimal(expected), quantityUnit = unit,
                packValue = null, packUnit = "unspecified", required = emptyList(), excluded = emptyList(),
                sourceText = source, needsClarification = false,
            )
            assertNull(source, item.legacyNumericOwnershipIssue())
        }
        listOf(
            "एक किलोमीटर" to "kg", "एक किलो\u0301 चावल" to "kg", "एक लीटर\u093e दूध" to "l",
            "एक l'Oreal शैम्पू" to "l",
        ).forEach { (source, unit) ->
            assertFalse(source, physicalUnitFollowsAt(source, 0, "एक", unit))
        }
    }

    @Test fun unsupportedNativeCompoundsAndOmittedDecimalDigitsRemainUnresolved() {
        listOf("सवा", "साढ़े", "साढे", "पौने").forEach { assertNull(parseIntentCorrectionAmount(it)) }
        listOf("दो सौ दस", "सवा एक", "साढ़े तीन", "पौने एक", "एक और आधा")
            .forEach { assertNull(it, parseIntentCorrectionAmount(it)) }
        assertTrue(groceryIntentContainsNumericAtom("सवा एक किलो"))
        assertTrue(groceryIntentContainsNumericAtom("२ किलो"))
        assertNull(sourceQuotedLocalAmount("सौ नौ ग्राम मसाला", "सौ", "count"))
        assertNull(sourceQuotedLocalAmount("सौ दस किलो चावल", "दस", "kg"))
        assertNull(sourceQuotedLocalAmount("सौ\u0301 नौ ग्राम मसाला", "सौ", "count"))
        val digitOnly = GroceryIntentItem(
            name = "चावल", quantity = null, quantityUnit = "unspecified", packValue = null,
            packUnit = "unspecified", required = emptyList(), excluded = emptyList(),
            sourceText = "२ किलो चावल", needsClarification = false,
        )
        assertTrue(digitOnly.legacyNumericOwnershipIssue() != null)
    }

    @Test fun localLegacyBinderRejectsCroppedNativeCompoundAtoms() {
        data class Case(val source: String, val atom: String, val amount: String, val unit: String, val name: String)
        val cases = listOf(
            Case("सवा एक किलो चावल", "एक", "1", "kg", "चावल"),
            Case("साढ़े तीन किलो चावल", "तीन", "3", "kg", "चावल"),
            Case("पौने एक किलो चावल", "एक", "1", "kg", "चावल"),
            Case("एक और आधा किलो चावल", "आधा", "0.5", "kg", "चावल"),
            Case("दो और आधा किलो चावल", "आधा", "0.5", "kg", "चावल"),
            Case("एक सौ ग्राम मसाला", "एक", "1", "count", "मसाला"),
            Case("दो दर्जन अंडे", "दो", "2", "count", "अंडे"),
            Case("दो सौ दस ग्राम मसाला", "दो सौ", "200", "count", "मसाला"),
        )
        cases.forEach { (source, atom, amount, unit, name) ->
            assertNull("$source / $atom", sourceQuotedLocalAmount(source, atom, unit))
            val item = GroceryIntentItem(
                name = name, quantity = BigDecimal(amount), quantityUnit = unit,
                packValue = null, packUnit = "unspecified", required = emptyList(), excluded = emptyList(),
                sourceText = source, needsClarification = false,
            )
            assertTrue("$source / $atom", item.legacyNumericOwnershipIssue() != null)
        }
    }

    @Test fun strictContextPreparationAcceptsOnlySourceBoundNativeAmount() {
        val source = "एक लीटर दूध"
        val accepted = GroceryIntentContextResponse.parse(contextBody(source, source, "1", "l"), source)
            .prepareForReview()
        assertTrue(accepted.pendingCounts.isEmpty())
        assertFalse(accepted.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_QUANTITY })
        assertEquals(Quantity.Volume(1000), accepted.legacyPreparation.items.single().quantity)

        val ambiguous = "सवा एक लीटर दूध"
        val rejected = GroceryIntentContextResponse.parse(contextBody(ambiguous, ambiguous, "1", "l"), ambiguous)
        assertThrows(IntentNeedsReviewException::class.java) { rejected.prepareForReview() }
    }

    @Test fun strictContextPreparationBindsMixedNativeSizeButDoesNotInventRetailProof() {
        val source = "2 bottles each 500 मिलीलीटर milk"
        val count = requireNotNull(sourceQuotedLocalAmount(source, "2", "pack"))
        val size = requireNotNull(sourceQuotedLocalAmount(source, "500", "ml"))
        assertEquals(0, BigDecimal("2").compareTo(count.value))
        assertEquals("pack", count.unit)
        assertEquals(source.indexOf("2"), count.startUtf16)
        assertEquals(0, BigDecimal("500").compareTo(size.value))
        assertEquals("ml", size.unit)
        assertEquals(source.indexOf("500"), size.startUtf16)

        val preparation = GroceryIntentContextResponse.parse(
            contextBody(source, source, "2", "pack", packValue = "500", packUnit = "ml"), source,
        ).prepareForReview()
        assertTrue(preparation.pendingCounts.isEmpty())
        // Removing a false lexical hold does not create the still-missing
        // purchase-count receipt for this package syntax.
        assertFalse(preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_IDENTITY })
        assertTrue(preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_QUANTITY })
        assertTrue(preparation.legacyPreparation.items.single().requiresIntentQuantityProof)
        assertNull(preparation.legacyPreparation.items.single().intentQuantityProvenance)
        assertEquals(Quantity.Count(2), preparation.legacyPreparation.items.single().quantity)
    }

    @Test fun nativeHundredAndFollowingAmountCannotBeSplitAcrossQuantityAndPackFields() {
        val source = "सौ दस किलो चावल"
        val splitInterpretation = GroceryIntentItem(
            name = "चावल", quantity = BigDecimal("100"), quantityUnit = "count",
            packValue = BigDecimal("10"), packUnit = "kg", required = emptyList(), excluded = emptyList(),
            sourceText = source, needsClarification = false,
        )
        assertTrue(source, splitInterpretation.legacyNumericOwnershipIssue() != null)

        // The strict context DTO rejects a 100-pack count before preparation;
        // regardless, the source-local legacy numeric gate above must not accept the split.
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentContextResponse.parse(
                contextBody(source, source, "100", "count", packValue = "10", packUnit = "kg", name = "चावल"),
                source,
            ).prepareForReview()
        }
    }

    @Test fun contextReviewHoldsNativeRecipeHomographWhenLegacyNumericGateHasNoIssue() {
        val source = "दो प्याजा"
        val item = GroceryIntentItem(
            name = "प्याजा", quantity = BigDecimal("2"), quantityUnit = "count",
            packValue = null, packUnit = "unspecified", required = emptyList(), excluded = emptyList(),
            sourceText = source, needsClarification = false,
        )
        val issue = item.legacyNumericOwnershipIssue()
        val preparation = runCatching {
            GroceryIntentContextResponse.parse(contextBody(source, source, "2", "count", name = "प्याजा"), source)
                .prepareForReview()
        }.getOrNull()
        val blockingKinds = preparation?.blockingIssues?.map { it.kind }
        val quantityIssues = preparation?.blockingIssues?.filter {
            it.kind == GroceryIntentContextIssueKind.ITEM_QUANTITY
        }.orEmpty()
        val retained = preparation?.legacyPreparation?.items?.singleOrNull()
        val v1Body = contextBody(source, source, "2", "count", name = "प्याजा")
            .replace("\"contract_version\":\"$GROCERY_INTENT_CONTEXT_CONTRACT\",", "\"contract_version\":\"grocery-intent-v1\",")
            .replace(",\"context\":[]", "")
        val v1Preparation = GroceryIntentResponse.parse(v1Body, source).draft.prepareForReview()
        val v1Item = v1Preparation.items.single()
        assertNull("Context blocks this independently; legacy gate result is diagnostic", issue)
        assertTrue("context blockingKinds=$blockingKinds",
            preparation != null && GroceryIntentContextIssueKind.ITEM_QUANTITY in blockingKinds.orEmpty())
        assertEquals("Homograph remains a permanent context hold absent a pending offer witness", 0,
            preparation?.pendingCounts?.size)
        assertEquals(1, quantityIssues.size)
        assertEquals(source, quantityIssues.single().source?.text)
        assertNull("The held context item must not receive quantity action provenance", retained?.intentQuantityProvenance)
        assertTrue("Ordinary V1 has no newly added quantity review for this homograph",
            v1Preparation.unresolvedText.isEmpty())
        assertNull("V1 does not mint source quantity provenance for the homograph", v1Item.intentQuantityProvenance)
        val candidate = SwiggyMcpClient.RecommendationCandidate("synthetic-only", "प्याजा")
        assertFalse(swiggyIntentQuantityCandidateChecked(v1Item, candidate))
        assertTrue(resolveSwiggyCartQuantity(v1Item, candidate) is SwiggyCartQuantityResolution.Held)
        assertTrue("The numeric noun is retained rather than silently stripped from retrieval identity",
            v1Item.query.contains("प्याजा"))
    }

    private fun contextBody(
        instruction: String,
        source: String,
        quantity: String,
        unit: String,
        packValue: String? = null,
        packUnit: String = "unspecified",
        name: String = "milk",
    ): String =
        """{"accepted":true,"draft":{"contract_version":"$GROCERY_INTENT_CONTEXT_CONTRACT","requires_review":true,"instruction":${json(instruction)},"items":[{"name":${json(name)},"quantity":$quantity,"quantity_unit":${json(unit)},"pack_value":${packValue ?: "null"},"pack_unit":${json(packUnit)},"required":[],"excluded":[],"source_text":${json(source)},"needs_clarification":false}],"model_needs_clarification":false,"context":[]},"model":"synthetic","promptVersion":"native-quantity-test","latencyMs":0,"usage":{}}"""

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""
}
