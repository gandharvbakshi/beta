package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCatalogueQuantityTest {
    @Test fun provedProduceCategoryFiltersEachCandidateAndSurvivesTheFinalCartGuard() {
        val ordinary = parsed("one orange", "orange", 1)
        val scoped = ordinary.copy(intentQuantityProvenance = ordinary.intentQuantityProvenance!!.copy(
            produceCategory = IntentProduceCategory.FRUITS,
        ))
        val whole = candidate("Fresh Orange", "whole", subtitle = "1 pc")
        val juice = candidate("Orange Juice", "juice", subtitle = "1 L")
        val raw = Recommendations(candidates = listOf(juice, whole), suggested = juice)
        val assessed = assessSwiggyIntentQuantity(scoped, raw)
        assertNull(swiggyIntentQuantityIssue(scoped, raw)) // Mixed results do not quarantine the safe alternative.
        assertTrue(isSwiggyCandidateAllowed(assessed, whole))
        assertTrue(!isSwiggyCandidateAllowed(assessed, juice))
        assertUnqualifiedCountNeedsReview(assessed, whole, 1)
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessed, juice) }
        assertTrue(!swiggyIntentQuantityCandidateChecked(assessed.copy(
            intentQuantityProvenance = assessed.intentQuantityProvenance!!.copy(produceCategory = null),
        ), whole))
        val flagRemoved = assessed.copy(requiresIntentQuantityProof = false)
        assertTrue(!isSwiggyCandidateAllowed(flagRemoved, whole))
        assertTrue(!swiggyIntentQuantityCandidateChecked(flagRemoved, whole))
        assertTrue(!isSwiggyCandidateAllowed(assessed, whole.copy(subtitle = "Orange juice")))
        assertTrue(!swiggyIntentQuantityCandidateChecked(assessed, whole.copy(variant = "Juice")))
        // Shared identity guards now reject a derived juice even without a produce heading.
        assertTrue(!isSwiggyCandidateAllowed(ordinary, juice))
        assertTrue(isSwiggyCandidateAllowed(parsed("one orange juice", "orange juice", 1), juice))
    }

    @Test fun sameSourceNumberCannotOwnBothQuantityAndModelProductName() {
        listOf("Five Star chocolate" to 5, "Three Roses tea" to 3, "Seven Up" to 7).forEach { (source, count) ->
            val row = readyRow(source, source, count, source.substringBefore(' '))
            assertEquals("quantity_name_atom_reused", row.numericOwnershipIssue())
            assertTrue(intentReview(source, listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun numericProductNamesAreQuarantinedFromRawCatalogueCandidates() {
        val cases = listOf(
            Triple("Five Star chocolate", "Star chocolate", 5 to "Cadbury Five Star Chocolate"),
            Triple("Three Roses tea", "Roses tea", 3 to "Cadbury Three Roses Tea"),
            Triple("seven Up", "Up", 7 to "7 Up"),
            Triple("Five Star chocolate", "Star chocolate", 5 to "cadbury five star chocolate"),
            Triple("Five Star chocolate", "Star chocolate", 5 to "Cadbury 5Star Chocolate"),
        )

        cases.forEachIndexed { index, (source, query, quantityAndLabel) ->
            val (quantity, label) = quantityAndLabel
            val quantityText = source.substringBefore(' ')
            val sourceItem = parsed(source, query, quantity, quantityText)
            val candidate = candidate(label, "identity-$index")
            assertNotNull(label, swiggyIntentQuantityIssue(sourceItem, recommendations(candidate)))
        }
    }

    @Test fun unsafeCandidateBeyondShortlistQuarantinesTheWholeCatalogue() {
        val item = parsed("Five Star chocolate", "Star chocolate", 5, "Five")
        val safe = candidate("Star Chocolate", "safe")
        val raw = Recommendations(candidates = listOf(
            safe,
            candidate("Classic Star Chocolate", "safe-2"),
            candidate("Dark Star Chocolate", "safe-3"),
            candidate("Milk Star Chocolate", "safe-4"),
            candidate("Rich Star Chocolate", "safe-5"),
            candidate("Cadbury 5Star Chocolate", "unsafe-sixth"),
        ))

        assertNotNull(swiggyIntentQuantityIssue(item, raw))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessSwiggyIntentQuantity(item, raw), safe)
        }
    }

    @Test fun assessedV2CountIsBoundToExactCandidateAndOriginalItem() {
        val item = parsed("two milk", "milk", 2)
        val candidate = candidate("Amul Milk", "amul-1", variant = "Toned", subtitle = "1 L")
        val raw = recommendations(candidate)

        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(item, candidate)
        }
        val assessed = assessSwiggyIntentQuantity(item, raw)
        assertNotEquals(item, assessed)
        assertUnqualifiedCountNeedsReview(assessed, candidate, 2)
        assertEquals("two milk", swiggyIntentQuantitySource(assessed))

        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed.copy(quantity = Quantity.Count(1)), candidate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed, candidate.copy(skuId = "another-sku"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed, candidate.copy(label = "Different Milk"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed, candidate.copy(variant = "Double Toned"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed, candidate.copy(subtitle = "500 ml"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed.copy(intentQuantityCatalogueCheck = null), candidate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessed.copy(intentQuantityProvenance = null), candidate)
        }
    }

    @Test fun copiedQuantityAndMatchingFlagsCannotReuseAnAssessment() {
        val item = parsed("two milk", "milk", 2)
        val candidate = candidate("Amul Milk", "amul-tamper")
        val assessed = assessSwiggyIntentQuantity(item, recommendations(candidate))

        listOf(
            assessed.copy(quantity = Quantity.Count(1)),
            assessed.copy(retailPackCount = true),
            assessed.copy(logicalPieceCount = true),
            assessed.copy(strictMatchPhrase = null),
            assessed.copy(query = "another milk"),
            assessed.copy(rawText = "one milk"),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(tampered, candidate)
            }
        }
    }

    @Test fun suggestionOnlyIdentityCollisionQuarantinesOtherwiseSafeCandidates() {
        val item = parsed("Five Star chocolate", "Star chocolate", 5, "Five")
        val safe = candidate("Star Chocolate", "safe-suggestion-test")
        val suggestion = candidate("Cadbury 5Star Chocolate", "suggested-collision")
        val raw = Recommendations(candidates = listOf(safe), suggested = suggestion)

        assertNotNull(swiggyIntentQuantityIssue(item, raw))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(assessSwiggyIntentQuantity(item, raw), safe)
        }
    }

    @Test fun numericIdentityInVariantSubtitleOrAcrossFieldsQuarantinesTheItem() {
        val item = parsed("Five Star chocolate", "Star chocolate", 5, "Five")
        val base = candidate("Cadbury Chocolate Bar", "candidate-details")
        listOf(base.copy(variant = "5 Star"), base.copy(subtitle = "5 Star"),
            base.copy(label = "Cadbury 5", variant = "Star Chocolate")).forEach { candidate ->
            val assessed = assessSwiggyIntentQuantity(item, recommendations(candidate))
            assertNotNull(swiggyIntentQuantityIssue(item, recommendations(candidate)))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessed, candidate) }
        }
    }

    @Test fun verifiedPieceTotalIsNotNumericBrandIdentityAndCannotHideOtherNumericNames() {
        val eggs = parsed("6 eggs", "eggs", 6, "6")
        listOf("Farm Fresh 6 Eggs", "Farm Fresh Eggs Pack of 6 Eggs", "Farm Fresh Eggs 6 pcs").forEach { title ->
            val candidate = candidate(title, "eggs")
            val assessed = assessSwiggyIntentQuantity(eggs, recommendations(candidate))
            assertNull(swiggyIntentQuantityIssue(eggs, recommendations(candidate)))
            assertUnqualifiedCountNeedsReview(assessed, candidate, 6)
        }
        val fiveStar = parsed("Five Star chocolate", "Star chocolate", 5, "Five")
        val candidate = candidate("5 Star Chocolate 5 pcs", "five-star-pieces")
        assertNotNull(swiggyIntentQuantityIssue(fiveStar, recommendations(candidate)))
        val conflicting = candidate("Farm Fresh 6 Eggs 12 Pieces", "ambiguous-eggs")
        assertNotNull(swiggyIntentQuantityIssue(eggs, recommendations(conflicting)))
    }

    @Test fun numericIdentityComparisonPreservesCombiningMarksAndDoesNotInventFractions() {
        assertTrue(intentQuantityIdentityConflict("5 गुलाब", 0, 1, BigDecimal("5"), "count", "five गुलाब"))
        assertTrue(!intentQuantityIdentityConflict("5 गुलाब", 0, 1, BigDecimal("5"), "count", "five गुलाबी"))
        assertTrue(!intentQuantityIdentityConflict("5 Star", 0, 1, BigDecimal("5"), "count", "½ Star"))
    }

    @Test fun matchingLiteralOneCollidesButDifferentAmountAndSafeTitlesDoNot() {
        assertTrue(!intentQuantityIdentityConflict("1 bhujia", 0, 1, BigDecimal.ONE, "count", "BrandA bhujia"))
        val oneStar = parsed("one Star chocolate", "Star chocolate", 1, "one")
        assertNotNull(swiggyIntentQuantityIssue(oneStar, recommendations(candidate("Cadbury 1 Star Chocolate", "one-star"))))
        assertNull(swiggyIntentQuantityIssue(oneStar, recommendations(candidate("Cadbury 5 Star Chocolate", "five-star"))))

        val brandA = parsed("one BrandA milk", "BrandA milk", 1, "one")
        assertNull(swiggyIntentQuantityIssue(brandA, recommendations(candidate("BrandA Milk", "brand-a"))))

        val maggi = parsed("two Maggi noodles", "Maggi noodles", 2, "two")
        assertNull(swiggyIntentQuantityIssue(
            maggi,
            recommendations(candidate("Maggi 2-Minute Noodles", "maggi-title")),
        ))
    }

    @Test fun legacyBareCountsNeedTheSameQuantityReview() {
        val legacy = ParsedItem(rawText = "two milk", query = "milk", quantity = Quantity.Count(2))
        val candidate = candidate("Amul Milk", "amul-legacy")

        assertFalseLegacyProof(legacy)
        assertUnqualifiedCountNeedsReview(legacy, candidate, 2)
        assertNull(swiggyIntentQuantitySource(legacy))
    }

    @Test fun displayUsesQuantityNotationOnlyForProvenV2Counts() {
        val v2 = parsed("5 chocolates", "chocolates", 5, "5")
        val assessed = assessSwiggyIntentQuantity(v2, recommendations(candidate("Chocolates", "chocolates")))
        assertEquals("5 × chocolates", swiggyDisplayItem(assessed))

        val legacy = ParsedItem(rawText = "5 chocolates", query = "chocolates", quantity = Quantity.Count(5))
        assertTrue(swiggyDisplayItem(legacy).contains("chocolates"))
        assertNotEquals("5 × chocolates", swiggyDisplayItem(legacy))
    }

    @Test fun correctionRetainsBaseQuantityProvenanceForCatalogueQuarantine() {
        val corrected = correctedItem()

        assertEquals(Quantity.Count(2), corrected.quantity)
        assertEquals(IntentQuantityOrigin.CORRECTION, corrected.intentQuantityProvenance?.origin)
        assertEquals(BigDecimal("5"), corrected.intentQuantityProvenance?.local?.value)
        assertEquals("Five", corrected.intentQuantityProvenance?.local?.atom)
        assertEquals(IntentLocalAmount("make Star chocolate 2", 20, 21, "2", BigDecimal("2"), "count"),
            corrected.intentQuantityProvenance?.corrected)
        assertNull(swiggyIntentQuantityIssue(corrected, recommendations(candidate("Star Chocolate", "corrected-safe"))))
        assertNotNull(swiggyIntentQuantityIssue(
            corrected,
            recommendations(candidate("Cadbury 5Star Chocolate", "colliding-five-star")),
        ))
        val correctionWithNumericName = correctedItem(correctionTail = " Star chocolate")
        assertNotNull(swiggyIntentQuantityIssue(
            correctionWithNumericName,
            recommendations(candidate("Cadbury 2 Star Chocolate", "colliding-corrected-two-star")),
        ))
    }

    @Test fun correctionRequiresItsExactValidatedAmountSpan() {
        val corrected = correctedItem()
        val proof = corrected.intentQuantityProvenance!!
        val amount = proof.corrected!!
        val malformed = listOf(
            proof.copy(corrected = null),
            proof.copy(corrected = amount.copy(startUtf16 = amount.startUtf16 - 1)),
            proof.copy(corrected = amount.copy(endUtf16 = amount.endUtf16 + 1)),
            proof.copy(corrected = amount.copy(source = amount.source.replace("2", "3"))),
            proof.copy(corrected = amount.copy(atom = "3")),
            proof.copy(corrected = amount.copy(value = BigDecimal("3"))),
            proof.copy(corrected = amount.copy(unit = "pack")),
        )

        malformed.forEach { tampered ->
            assertNotNull(swiggyIntentQuantityIssue(
                corrected.copy(intentQuantityProvenance = tampered),
                recommendations(candidate("Star Chocolate", "corrected-proof")),
            ))
        }
    }

    @Test fun correctedAmountRemainsTheQuantityAuthorityAfterItemAndResolvedQuantityAreChanged() {
        val original = correctedItem()
        val proof = original.intentQuantityProvenance!!
        val candidate = candidate("Star Chocolate", "corrected-quantity-authority")

        assertNotNull(swiggyIntentQuantityIssue(
            original.copy(quantity = Quantity.Count(3), intentQuantityProvenance = proof.copy(
                resolvedQuantity = Quantity.Count(3),
            )),
            recommendations(candidate),
        ))

        val independentlyCorrected = correctedItem(newAmount = "3")
        assertEquals(Quantity.Count(3), independentlyCorrected.quantity)
        assertNull(swiggyIntentQuantityIssue(independentlyCorrected, recommendations(candidate)))
    }

    @Test fun correctedAmountIsRejectedOnNonCorrectionProvenance() {
        val ordinary = parsed("two milk", "milk", 2)
        val amount = IntentLocalAmount("two", 0, 3, "two", BigDecimal("2"), "count")
        val tampered = ordinary.copy(intentQuantityProvenance = ordinary.intentQuantityProvenance!!.copy(
            corrected = amount,
        ))

        assertNotNull(swiggyIntentQuantityIssue(tampered, recommendations(candidate("Amul Milk", "non-correction"))))
    }

    @Test fun groupEachAndUnstatedQuantitiesDoNotInventLocalAtoms() {
        val instruction = "two each milk, bread"
        val eachText = "two each"
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified",
            amountStatus = "each_verified",
            source = quote(instruction, instruction),
            header = null,
            memberIndices = listOf(0, 1),
            members = listOf(0, 1).map { index -> GroceryIntentReviewV2GroupMember(
                rowIndex = index,
                value = BigDecimal("2"),
                unit = "count",
                origin = "each",
            ) },
            packaging = null,
            total = null,
            each = GroceryIntentReviewV2GroupAmount(
                source = quote(instruction, eachText),
                amountText = "two",
                value = BigDecimal("2"),
                unit = "count",
            ),
        )
        val rows = listOf("milk", "bread").mapIndexed { index, product ->
            readyRow(instruction, product, null, null, product).copy(rowIndex = index)
        }
        val grouped = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = 2,
            readyRows = rows,
            blockedRows = emptyList(),
            groups = listOf(group),
        ).prepareForReview().items

        assertEquals(2, grouped.size)
        assertTrue(grouped.all { it.quantity == Quantity.Count(2) })
        assertTrue(grouped.all { it.intentQuantityProvenance?.origin == IntentQuantityOrigin.GROUP })
        assertTrue(grouped.all { it.intentQuantityProvenance?.local == null })

        val unstated = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = "milk",
            sourceItemCount = 1,
            readyRows = listOf(readyRow("milk", "milk", null, null, "milk")),
            blockedRows = emptyList(),
        ).prepareForReview().items.single()
        assertEquals(Quantity.Default, unstated.quantity)
        assertEquals(IntentQuantityOrigin.UNSTATED, unstated.intentQuantityProvenance?.origin)
        assertNull(unstated.intentQuantityProvenance?.local)
    }

    private fun assertFalseLegacyProof(item: ParsedItem) {
        assertTrue(!item.requiresIntentQuantityProof)
    }
}

private fun parsed(source: String, name: String, quantity: Int, quantityText: String? = null): ParsedItem =
    intentReview(source, listOf(readyRow(source, name, quantity, quantityText))).prepareForReview().items.single()

private fun correctedItem(newAmount: String = "2", correctionTail: String = ""): ParsedItem {
    val instruction = "Five Star chocolate; make Star chocolate $newAmount$correctionTail"
    val source = "Five Star chocolate"
    val correctionText = "make Star chocolate $newAmount$correctionTail"
    val correctedValue = BigDecimal(newAmount)
    val row = readyRow(instruction, "Star chocolate", 5, "Five", source).copy(
        corrections = listOf(GroceryIntentReviewV2Correction(
            kind = "quantity",
            source = quote(instruction, correctionText),
            newAmount = quote(instruction, newAmount),
            oldValue = BigDecimal("5"),
            oldUnit = "count",
            newValue = correctedValue,
            newUnit = "count",
        )),
    )
    return intentReview(instruction, listOf(row)).prepareForReview().items.single()
}

private fun intentReview(source: String, rows: List<GroceryIntentReviewV2ReadyRow>) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2",
    requiresReview = true,
    instruction = source,
    sourceItemCount = rows.size,
    readyRows = rows,
    blockedRows = emptyList(),
)

private fun readyRow(
    instruction: String,
    name: String,
    quantity: Int?,
    quantityText: String? = null,
    source: String = instruction,
) = GroceryIntentReviewV2ReadyRow(
    rowIndex = 0,
    item = GroceryIntentReviewV2Item(
        name = name,
        quantity = quantity?.let { BigDecimal.valueOf(it.toLong()) },
        quantityUnit = if (quantity == null) "unspecified" else "count",
        packValue = null,
        packUnit = "unspecified",
        packaging = null,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = quantity?.let { quantityText ?: when (it) {
            1 -> "one"
            2 -> "two"
            else -> it.toString()
        } },
        packText = null,
    ),
    evidence = GroceryIntentReviewV2Evidence(
        primary = quote(instruction, source),
        supporting = emptyList(),
    ),
    quantityOrigin = if (quantity == null) "not_stated" else "stated",
    amountRoleStatus = if (quantity == null) "not_stated" else "matched",
)

private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(text)
    require(start >= 0)
    return GroceryIntentReviewV2Quote(text, start, start + text.length)
}

private fun candidate(label: String, id: String, variant: String? = null, subtitle: String? = null) = RecommendationCandidate(
    spinId = id,
    label = label,
    variant = variant,
    subtitle = subtitle,
    skuId = id,
)

private fun recommendations(candidate: RecommendationCandidate) = Recommendations(
    candidates = listOf(candidate),
    suggested = candidate,
)
