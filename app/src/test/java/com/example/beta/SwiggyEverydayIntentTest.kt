package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.Quantity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline catalogue identity/exclusion fixtures; no provider or cart calls. */
class SwiggyEverydayIntentTest {
    @Test
    fun everyday_spoken_counts_pass_app_validation_not_only_parser() {
        listOf("Order one samosa", "I want one lean milk", "One milk with less cream",
            "Order one tetra pack milk", "Can you please order two samosa", "ek tetra pack doodh").forEach { command ->
            val parsed = prepareSwiggyMcpItems(command, lookup = { null })
            assertEquals(command, 1, parsed.size)
            assertNull(command, swiggyMcpItemValidationMessage(command, parsed))
        }
    }

    @Test
    fun bare_counts_are_held_but_explicit_logical_pieces_keep_exact_arithmetic() {
        val two = ParsedItem("two samosa", "samosa", Quantity.Count(2))
        val single = candidate("Fresh Samosa Ready to Eat 1 pc")
        assertTrue(resolveSwiggyCartQuantity(two, single) is SwiggyCartQuantityResolution.Held)
        assertFalse(isSwiggyCandidateCountCompatible(two, single))
        val twoPieces = two.copy(logicalPieceCount = true)
        assertTrue(isSwiggyCandidateCountCompatible(twoPieces, single))
        assertEquals(2, swiggyRequestedCartQuantity(twoPieces, single))
        assertFalse(isSwiggyCandidateCountCompatible(twoPieces, candidate("Samosa 3 pcs")))
        assertFalse(isSwiggyCandidateCountCompatible(twoPieces, candidate("Samosa 2 pcs — 6 pcs")))
        val dozen = ParsedItem("12 eggs", "eggs", Quantity.Count(12))
        val six = candidate("Eggs 6 pcs")
        assertTrue(resolveSwiggyCartQuantity(dozen, six) is SwiggyCartQuantityResolution.Held)
        val dozenPieces = dozen.copy(logicalPieceCount = true)
        assertTrue(isSwiggyCandidateCountCompatible(dozenPieces, six))
        assertEquals(2, swiggyRequestedCartQuantity(dozenPieces, six))
    }

    @Test
    fun explicit_retail_pack_counts_do_not_multiply_or_divide_inner_pieces() {
        val oneCarton = InstructionParser.parse("one tetra pack milk").single()
        assertFalse(isSwiggyCandidateCountCompatible(oneCarton, candidate("Milk Tetra Pack 6 x 200 ml")))
        val frozen = candidate("Frozen Samosa 6 pcs")
        val frozenItem = InstructionParser.parse("one packet frozen samosa").single()
        val frozenPack = assessSwiggyIntentQuantity(frozenItem, SwiggyMcpClient.Recommendations(listOf(frozen)))
        assertTrue(frozenPack.retailPackCount)
        assertTrue(isSwiggyCandidateAllowed(frozenPack, frozen))
        assertTrue(swiggyCountResolutionDiagnostic(frozenPack, frozen),
            resolveSwiggyCartQuantity(frozenPack, frozen) is SwiggyCartQuantityResolution.Held)
        val typedFrozen = withTypedPackSize(frozen, "6 pieces")
        val typedFrozenPack = assessSwiggyIntentQuantity(frozenItem, SwiggyMcpClient.Recommendations(listOf(typedFrozen)))
        assertReviewedRetailProposal(typedFrozenPack, typedFrozen, 1)

        val twinPack = candidate("Milk 2 x 500 ml")
        val fiveItem = InstructionParser.parse("2 x 500 ml milk packs paanch").single()
        val five = assessSwiggyIntentQuantity(fiveItem, SwiggyMcpClient.Recommendations(listOf(twinPack)))
        assertTrue(five.retailPackCount)
        assertTrue(swiggyCountResolutionDiagnostic(five, twinPack),
            resolveSwiggyCartQuantity(five, twinPack) is SwiggyCartQuantityResolution.Held)
        val typedTwinPack = withTypedPackSize(twinPack, "2 x 500 ml")
        val typedFive = assessSwiggyIntentQuantity(fiveItem, SwiggyMcpClient.Recommendations(listOf(typedTwinPack)))
        assertReviewedRetailProposal(typedFive, typedTwinPack, 5)
    }

    @Test
    fun composite_pack_numeric_role_diagnostic_matrix() {
        data class Case(val id: String, val source: String)
        val cases = listOf(
            Case("milk_x_postfix_hinglish", "2 x 500 ml milk packs paanch"),
            Case("milk_x_postfix_digits", "2 x 500 ml milk packs 5"),
            Case("milk_identity_before_x", "milk 2 x 500 ml packs paanch"),
            Case("milk_x_no_outer_count", "2 x 500 ml milk packs"),
            Case("milk_measure_before_suffix_count", "500 ml milk packs 2"),
            Case("milk_prefix_count_measure_suffix", "2 packs milk 500 ml"),
            Case("milk_postfix_packets", "milk 500 ml 2 packets"),
            Case("milk_each_prefix_count", "500 ml each milk 2 bottles"),
            Case("milk_each_suffix_count", "2 bottles milk 500 ml each"),
            Case("milk_multiple_outer_counters", "2 x 500 ml milk packs 5 and 3"),
            Case("juice_x_postfix_count", "2 x 1 l juice cartons 3"),
            Case("juice_measure_then_cartons", "juice 1 l cartons 2"),
            Case("juice_prefix_cartons", "2 cartons juice 1 l"),
            Case("rice_x_postfix_count", "2 x 500 g rice packs 3"),
            Case("rice_prefix_count_x_suffix", "3 packs rice 2 x 500 g"),
            Case("soap_x_postfix_bar_count", "2 x 100 g soap bars 4"),
        )
        assertEquals("diagnostic fixture count", 16, cases.size)

        fun amountAndUnit(item: ParsedItem): Pair<java.math.BigDecimal?, String> = when (val quantity = item.quantity) {
            Quantity.Default -> null to "unspecified"
            is Quantity.Count -> quantity.n.toBigDecimal() to if (item.retailPackCount) "pack" else "count"
            is Quantity.Weight -> quantity.grams.toBigDecimal() to "g"
            is Quantity.Volume -> quantity.ml.toBigDecimal() to "ml"
        }
        fun receiptSummary(item: ParsedItem): String {
            val receipt = sourceRetailCountReceipt(item.rawText) ?: return "none"
            val size = receipt.size?.let { "${it.atom}:${it.unit}" } ?: "none"
            return "${receipt.packaging}:${receipt.count.atom}:${receipt.count.unit}:$size"
        }
        fun parserSummary(item: ParsedItem): String =
            "qty=${item.quantity};retail=${item.retailPackCount};signal=${item.quantitySignal ?: "none"};" +
                "receipt=${receiptSummary(item)};sourceIssue=${item.untypedSourceNumericIssue() ?: "none"}"

        cases.forEach { case ->
            val parsed = prepareSwiggyMcpItems(case.source, lookup = { null })
            val validation = if (swiggyMcpItemValidationMessage(case.source, parsed) == null) "clear" else "blocked"
            val rows = parsed.map { item ->
                GroceryIntentItem(
                    name = item.query,
                    quantity = amountAndUnit(item).first,
                    quantityUnit = amountAndUnit(item).second,
                    packValue = null,
                    packUnit = "unspecified",
                    required = emptyList(),
                    excluded = item.avoidPhrases,
                    sourceText = item.rawText,
                    needsClarification = false,
                )
            }
            val v1Outcome = try {
                val prepared = GroceryIntentDraft(
                    contractVersion = "grocery-intent-v1",
                    requiresReview = true,
                    instruction = case.source,
                    items = rows,
                    modelNeedsClarification = false,
                ).prepareForReview()
                "prepared:${prepared.items.joinToString("|") { parserSummary(it) }}"
            } catch (review: IntentNeedsReviewException) {
                "review-held:${review.affectedSourceText.size}"
            }
            println(
                "COMPOSITE_NUMERIC_DIAGNOSTIC id=${case.id} outputCount=${parsed.size} " +
                    "validation=$validation parser=${parsed.joinToString("|") { parserSummary(it) }} v1=$v1Outcome",
            )
        }
    }

    @Test
    fun sugar_free_spellings_are_equivalent_for_tea() {
        listOf("Sugar-Free Tea", "Sugar Free Tea", "Sugarfree Tea").forEach { label ->
            assertAllowed("sugar-free tea", label)
        }
    }

    @Test
    fun sugar_free_tea_rejects_generic_no_added_sugar_and_contradictory_titles() {
        assertAllowed("sugar-free tea", "Sugar-Free Tea")
        assertBlocked("sugar-free tea", "Green Tea")
        assertBlocked("sugar-free tea", "Tea No Added Sugar")
        assertBlocked("sugar-free tea", "Sugar Free Tea With Sugar")
    }

    @Test
    fun standalone_sugar_free_requires_explicit_sweetener_evidence() {
        assertAllowed("sugar free", "Sugar Free Sweetener Tablets")
        assertAllowed("sugar free", "Sugar Free Sweetener Sachets")
        assertBlocked("sugar free", "Sugar Free Cookies")
        assertBlocked("sugar free", "Sugar Free Tea")
    }

    @Test
    fun standalone_sugar_free_accepts_only_complete_known_sweetener_titles() {
        val accepted = listOf(
            "Sugar Free Gold · 100 Pieces",
            "Sugar Free Gold · 50 g",
            "Sugar Free Green Stevia · 300 Pieces",
            "Sugar Free Green 100% Natural Made From Stevia · 200 g",
            "Sugar Free Natura Low Calorie Sweetner · 75 g",
        )
        accepted.forEach { label -> assertAllowed("sugar free", label) }

        listOf(
            "Sugar Free Gold Cookies",
            "Sugar Free D-Lite Chocolate",
            "Sugar Free Tea",
            "Gold Tea",
        ).forEach { label -> assertBlocked("sugar free", label) }
    }

    @Test
    fun without_sugar_requires_literal_sugar_absence_not_only_unsweetened_or_no_added_sugar() {
        val item = ParsedItem(
            rawText = "tea without sugar",
            query = "tea",
            avoidPhrases = listOf("sugar"),
        )

        assertFalse(isSwiggyCandidateAllowed(item, candidate("Unsweetened Tea")))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Sugar-Free Tea")))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Tea Zero Sugar")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Green Tea")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Tea No Added Sugar")))

        val juiceWithoutSugar = ParsedItem(
            rawText = "juice without sugar",
            query = "juice",
            avoidPhrases = listOf("sugar"),
        )
        assertFalse(isSwiggyCandidateAllowed(juiceWithoutSugar, candidate("Unsweetened Apple Juice")))
        assertTrue(isSwiggyCandidateAllowed(juiceWithoutSugar, candidate("Sugar-Free Apple Juice")))
        assertTrue(isSwiggyCandidateAllowed(juiceWithoutSugar, candidate("Apple Juice Zero Sugar")))
        assertAllowed("unsweetened apple juice", "Unsweetened Apple Juice")
    }

    @Test
    fun no_added_sugar_and_sugar_free_intents_remain_distinct() {
        assertAllowed("tea with no added sugar", "Tea No Added Sugar")
        assertAllowed("tea without added sugar", "Tea No Added Sugar")
        assertAllowed("tea no added sugar", "Tea No Added Sugar")
        assertBlocked("tea no added sugar", "Tea No Added Sugar With Sugar")
        assertBlocked("tea no added sugar", "Sugar-Free Tea")
        assertBlocked("sugar-free tea", "Tea No Added Sugar")
    }

    @Test
    fun low_fat_milk_accepts_only_explicit_bounded_low_fat_labels() {
        assertAllowed("low-fat milk", "Amul Low Fat Milk")
        assertAllowed("low-fat milk", "Amul Skimmed Milk")
        assertAllowed("low-fat milk", "Amul Double Toned Milk")
        assertBlocked("low-fat milk", "Amul Full Cream Milk")
        assertBlocked("low-fat milk", "Amul Milk")
    }

    @Test
    fun explicit_skimmed_milk_stays_skimmed() {
        assertAllowed("skimmed milk", "Amul Skimmed Milk")
        assertBlocked("skimmed milk", "Amul Low Fat Milk")
        assertBlocked("skimmed milk", "Amul Double Toned Milk")
    }

    @Test
    fun lean_milk_and_less_cream_are_bounded_low_fat_interpretations() {
        listOf("lean milk", "milk with less cream").forEach { query ->
            assertAllowed(query, "Amul Low Fat Milk")
            assertAllowed(query, "Amul Skimmed Milk")
            assertAllowed(query, "Amul Double Toned Milk")
            assertBlocked(query, "Lean Meat")
            assertBlocked(query, "Lean Chicken")
        }
    }

    @Test
    fun ready_made_tea_means_ready_to_drink_not_leaves_or_premix() {
        assertAllowed("ready-made tea", "Brewed Tea Ready to Drink")
        assertBlocked("ready-made tea", "Tea Leaves Ready Made")
        assertBlocked("ready-made tea", "Instant Tea Premix Ready Made")
    }

    @Test
    fun plain_samosa_rejects_masala_and_pastry_products() {
        assertAllowed("samosa", "Fresh Vegetable Samosa")
        assertBlocked("samosa", "Samosa Masala")
        assertBlocked("samosa", "Samosa Pastry Sheets")
    }

    @Test
    fun ready_to_eat_samosa_rejects_frozen_and_ready_to_cook() {
        assertAllowed("ready-to-eat samosa", "Vegetable Samosa Ready to Eat")
        assertBlocked("ready-to-eat samosa", "Frozen Samosa Ready to Eat")
        assertBlocked("ready-to-eat samosa", "Samosa Ready to Cook")
    }

    @Test
    fun bare_samosa_count_is_held_and_explicit_piece_intent_requires_one_fresh_ready_to_eat_piece() {
        val item = ParsedItem(
            rawText = "Order one samosa",
            query = "samosa",
            quantity = Quantity.Count(1),
        )
        val logicalPieceItem = item.copy(logicalPieceCount = true)
        val cases = listOf(
            "Fresh Vegetable Samosa Ready to Eat · 1 pc" to true,
            "Fresh Vegetable Samosa Ready to Eat · 6 pcs" to false,
            "Fresh Vegetable Samosa Ready to Eat · 400 g" to false,
            "Frozen Vegetable Samosa · 1 pc" to false,
            "Vegetable Samosa · 1 pc" to false,
        )

        cases.forEach { (label, expected) ->
            val candidate = candidate(label)
            val resolution = resolveSwiggyCartQuantity(item, candidate)
            assertTrue(resolution is SwiggyCartQuantityResolution.Held)
            assertFalse(isSwiggyCandidateCountCompatible(item, candidate))

            val allowed = isSwiggyCandidateAllowed(logicalPieceItem, candidate) &&
                isSwiggyCandidateCountCompatible(logicalPieceItem, candidate)
            assertTrueOrFalse(
                expected,
                allowed,
                "${item.rawText}: '$label'",
            )
        }
    }

    private fun assertAllowed(query: String, label: String) {
        assertTrue(
            "Expected '$query' to allow '$label'",
            isSwiggyCandidateAllowed(item(query), candidate(label)),
        )
    }

    private fun assertBlocked(query: String, label: String) {
        assertFalse(
            "Expected '$query' to reject '$label'",
            isSwiggyCandidateAllowed(item(query), candidate(label)),
        )
    }

    private fun item(query: String): ParsedItem = ParsedItem(rawText = query, query = query)

    private fun candidate(label: String): RecommendationCandidate = RecommendationCandidate(
        spinId = "synthetic-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
        label = label,
    )

    private fun withTypedPackSize(candidate: RecommendationCandidate, packSize: String): RecommendationCandidate {
        val skuId = "typed-${candidate.spinId}"
        return candidate.copy(
            skuId = skuId,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = candidate.spinId,
                skuId = skuId,
                fields = CatalogueQuantityFields(packSize = packSize),
            ),
        )
    }

    private fun assertTrueOrFalse(expected: Boolean, actual: Boolean, message: String) {
        if (expected) assertTrue(message, actual) else assertFalse(message, actual)
    }
}
