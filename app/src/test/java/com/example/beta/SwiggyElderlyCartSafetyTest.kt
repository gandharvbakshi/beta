package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Preference
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyElderlyCartSafetyTest {
    @Test fun nutrition_claim_never_supplies_cart_weight_or_volume() {
        val weight = ParsedItem(rawText = "100g protein bar", query = "protein bar", quantity = Quantity.Weight(100))
        assertEquals(2, swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 50g · 20g protein per serving")))
        assertEquals(2, swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 50g · 20g protein per 100g")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 20g protein per serving")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 20g protein per 100g")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 20g protein per serving of 50g")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 20g protein per serving (about 50g)")))
        assertNull(swiggyMeasuredPackQuantity(weight,
            candidate("Protein Bar 20g").copy(variant = "protein", subtitle = "per serving")))
        assertNull(swiggyMeasuredPackQuantity(weight,
            candidate("Protein Bar 50g · 20g protein per serving").copy(subtitle = "15g protein per serving")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("20g sugar Chocolate")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar low fat 20g per bar")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 20g protein")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 30g · 20g protein per serving")))
        assertNull(swiggyMeasuredPackQuantity(weight, candidate("Protein Bar 2x50g · 20g protein per serving")))
        assertEquals(2, swiggyMeasuredPackQuantity(weight,
            candidate("Protein Bar 20g protein per serving").copy(variant = "50g")))
        val volume = ParsedItem(rawText = "1l protein drink", query = "protein drink", quantity = Quantity.Volume(1000))
        assertEquals(2, swiggyMeasuredPackQuantity(volume,
            candidate("Protein Drink 500ml · 10g protein per 100ml")))
        assertNull(swiggyMeasuredPackQuantity(volume, candidate("Protein Drink 10g protein per 100ml")))
        assertNull(swiggyMeasuredPackQuantity(volume, candidate("Milk 500").copy(variant = "ml")))
    }

    @Test
    fun invalid_counts_are_rejected_before_any_provider_request() {
        listOf("twenty one milk and bread", "Twenty-one milk, bread", "hundred eggs", "zero milk", "0 milk", "-2 milk", "12 milk, 12 milk").forEach { instruction ->
            val items = prepareSwiggyMcpItems(instruction, lookup = { null })
            assertTrue("Must reject $instruction", swiggyMcpItemValidationMessage(instruction, items) != null)
        }
    }

    @Test
    fun spoken_counts_are_case_insensitive_and_preserved_after_conjunctions() {
        val items = prepareSwiggyMcpItems("Two milk and Three bread", lookup = { null })
        assertEquals(listOf("milk", "bread"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), items.map { it.quantity })
    }

    @Test
    fun phonetic_food_aliases_need_review_without_corrupting_brands() {
        val items = prepareSwiggyMcpItems("keen waa, mozz a rela, One Plus charger, vitamin E", lookup = { null })
        assertEquals(listOf("quinoa", "mozzarella", "oneplus charger", "vitamin e"), items.map { it.query })
        assertTrue(items.take(2).all { it.parserConfidence < 1f })
    }

    @Test
    fun unknown_or_different_area_never_claims_nearby() {
        val unknown = swiggyAddressLocationNotice(SwiggyLocationAssessment.UNKNOWN)
        val different = swiggyAddressLocationNotice(SwiggyLocationAssessment.NOT_MATCHED)
        assertTrue(unknown.contains("still use a saved address"))
        assertTrue(different.contains("confirm"))
        assertTrue(different.contains("different address"))
        assertFalse(unknown.contains("nearby"))
        assertFalse(different.contains("nearby"))
    }

    @Test
    fun measuredPackQuantity_supports_divisible_rice_and_milk_pack_sizes() {
        val rice = ParsedItem(rawText = "1kg rice", query = "rice", quantity = Quantity.Weight(1000))
        val milk = ParsedItem(rawText = "1l milk", query = "milk", quantity = Quantity.Volume(1000))

        assertEquals(2, swiggyMeasuredPackQuantity(rice, candidate("Rice · 500 g")))
        assertEquals(2, swiggyMeasuredPackQuantity(milk, candidate("Milk · 500 ml")))
        assertEquals(2, swiggyRequestedCartQuantity(rice, candidate("Rice · 500 g")))
        assertEquals(2, swiggyRequestedCartQuantity(milk, candidate("Milk · 500 ml")))
    }

    @Test
    fun measuredPackQuantity_rejects_undividable_or_unknown_pack_measures() {
        val halfKiloRice = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))
        val unknownMeasure = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))
        val mixedMeasures = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))
        val multipack = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))

        assertNull(swiggyMeasuredPackQuantity(halfKiloRice, candidate("Rice · 1 kg")))
        assertNull(swiggyMeasuredPackQuantity(unknownMeasure, candidate("Rice · 500 oz")))
        assertNull(swiggyMeasuredPackQuantity(mixedMeasures, candidate("Rice · 500 g · 1 kg")))
        assertNull(swiggyMeasuredPackQuantity(multipack, candidate("2x500 g Rice")))
    }

    @Test
    fun explicitOuterPackageCountsCannotBecomeWholeUnitPhysicalSizes() {
        val soapWeight = ParsedItem(rawText = "250 g soap bar", query = "soap bar", quantity = Quantity.Weight(250))
        assertNull(swiggyMeasuredPackQuantity(soapWeight, candidate("Soap Bar 4 bars 125 g")))

        val cleanerVolume = ParsedItem(rawText = "2 L cleaner", query = "cleaner", quantity = Quantity.Volume(2000))
        assertNull(swiggyMeasuredPackQuantity(cleanerVolume, candidate("Cleaner 4 bottles 500 ml")))

        val singleBarMeasure = ParsedItem(rawText = "250 g soap bar", query = "soap bar", quantity = Quantity.Weight(250))
        assertEquals(2, swiggyMeasuredPackQuantity(singleBarMeasure, candidate("Soap Bar 1 bar 125 g")))
    }

    @Test
    fun measuredPackQuantity_accepts_branding_prefixes_but_rejects_true_multipacks() {
        val litreCleaner = ParsedItem(rawText = "1l cleaner", query = "cleaner", quantity = Quantity.Volume(1000))

        assertEquals(1, swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 10X Advanced 1 L")))
        assertEquals(2, swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 10X Advanced 500 ml")))
        assertEquals(1, swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic Original Toilet Cleaner, 5 Min Action, 10X Advanced, Disinfectant · 1 ltr")))
        // Without a separating descriptor, 10X 1L could mean ten bottles.
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 10X 1L")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 2x500 ml")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 1 Lx2")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic pack of 2 1 L")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic pack of 10 1 L")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 500 ml combo 1 L")))
    }

    @Test
    fun retailMultipackCountsBlockUndividedBarsAndAllowExactOuterCounts() {
        val oneBar = retailCountItem("soap bar", 1, "soap bar")
        listOf("Soap Bar pack of 4", "Soap Bar 4 bars", "Soap Bar 4x125 g").forEach { label ->
            val candidate = candidate(label)
            assertFalse(label, isSwiggyCandidateCountCompatible(oneBar, candidate))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(oneBar, candidate) }
        }

        // Model conversion keeps quantity separate from its strict identity.
        val fourBars = retailCountItem("soap bar", 4, "soap bar")
        listOf("Soap Bar pack of 4", "Soap Bar 4 bars", "Soap Bar 4x125 g").forEach { label ->
            val candidate = candidate(label)
            assertTrue(label, isSwiggyCandidateAllowed(fourBars, candidate))
            assertUnqualifiedCountNeedsReview(fourBars, candidate, 4)
        }

        val explicitlyRequestedBundle = retailCountItem("soap bar", 1, "soap bar pack of 4")
        val requestedFour = candidate("Soap Bar pack of 4")
        assertLegacyRetailCountHeld(explicitlyRequestedBundle, requestedFour)
        val explicitlyRequestedLargeBundle = retailCountItem("soap bar", 1, "soap bar pack of 100")
        val requestedHundred = candidate("Soap Bar pack of 100")
        assertLegacyRetailCountHeld(explicitlyRequestedLargeBundle, requestedHundred)
        val conflictingBundle = candidate("Soap Bar Pack of 4, 3 bars")
        assertFalse(isSwiggyCandidateCountCompatible(explicitlyRequestedBundle, conflictingBundle))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(explicitlyRequestedBundle, conflictingBundle)
        }

        assertUnqualifiedCountNeedsReview(fourBars, candidate("Soap Bar pack of 4, 125 g"), 4)

        for (inner in listOf(4, 100)) {
            val reviewed = reviewedSoapContentsItem(inner)
            assertTrue("one pack of $inner pieces soap bar must retain source proof",
                reviewed.validContainerContentsProof() != null)
            val spinId = "soap-source-pack-$inner"
            val skuId = "soap-source-sku-$inner"
            val typed = RecommendationCandidate(
                spinId = spinId,
                label = "Soap Bar $inner Pieces",
                skuId = skuId,
                catalogueQuantity = CatalogueQuantityEvidence(
                    version = 1,
                    source = "search_variation",
                    spinId = spinId,
                    skuId = skuId,
                    fields = CatalogueQuantityFields(packSize = "$inner pieces"),
                ),
            )
            val checked = assessSwiggyIntentQuantity(reviewed, Recommendations(candidates = listOf(typed)))
            assertReviewedRetailProposal(checked, typed, 1)
        }
    }

    @Test
    fun retailMultipackCountRecognizesOnlyCompleteOuterContainerWords() {
        val one = retailCountItem("product", 1, "product")
        val four = retailCountItem("products", 4, "4 products")
        listOf("4 bars", "4 boxes", "4 bottles", "4 cartons", "4 rolls", "4 tubes", "4 loaves", "4 matchboxes")
            .forEach { outerCount ->
                assertFalse(outerCount, isSwiggyCandidateCountCompatible(one, candidate("Product $outerCount")))
                assertUnqualifiedCountNeedsReview(four, candidate("Product $outerCount"), 4)
            }
    }

    @Test
    fun retailMultipackCountsRecognizeOuterContainersButNotContainedMatches() {
        val oneMatchbox = retailCountItem("matchbox", 1, "matchbox")
        listOf("Matchbox 10 matchboxes", "Matchbox 10 boxes").forEach { label ->
            val candidate = candidate(label)
            assertFalse(label, isSwiggyCandidateCountCompatible(oneMatchbox, candidate))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(oneMatchbox, candidate) }
        }

        val tenMatchboxes = retailCountItem("matchboxes", 10, "10 matchboxes")
        listOf("Matchbox 10 matchboxes", "Matchbox 10 boxes").forEach { label ->
            assertLegacyRetailCountHeld(tenMatchboxes, candidate(label))
        }
        assertFalse(isSwiggyCandidateCountCompatible(tenMatchboxes, candidate("Matchbox 1matchbox 10 boxes")))

        val fourLoaves = retailCountItem("loaves", 4, "4 loaves")
        assertFalse(isSwiggyCandidateCountCompatible(fourLoaves, candidate("Bread 1loaf 4 loaves")))

        val containedMatches = candidate("Matches 50 matches")
        assertLegacyRetailCountHeld(oneMatchbox, containedMatches)
    }

    @Test
    fun malformedConflictingAndOutOfRangeMultipackCountsFailClosed() {
        val twoBarItem = retailCountItem("soap bars", 2, "2 soap bars")
        val conflict = candidate("Soap Bar 2 boxes · 10 bars")
        assertFalse(isSwiggyCandidateCountCompatible(twoBarItem, conflict))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(twoBarItem, conflict) }

        val oneBar = retailCountItem("soap bar", 1, "soap bar")
        listOf(
            "Soap Bar 100 bars",
            "Soap Bar pack of 100",
            "Soap Bar 1000 bars",
            "Soap Bar pack of 999999999999999999999999",
            "Soap Bar 0 bars",
            "Soap Bar -2 bars",
            "Soap Bar +2 boxes",
            "Soap Bar 1.5 bars",
            "Soap Bar 1e3 bars",
            "Soap Bar 1/2 boxes",
            "Soap Bar 1.5.2 bars",
            "Soap Bar 1-2 bars",
            "Soap Bar 1/2/3 bars",
            "Soap Bar 2+3 bars",
            "Soap Bar pack of four",
            "Soap Bar 3 x 1e3 g",
            "Soap Bar pack of 1, pack of four",
        ).forEach { label ->
            assertFalse(label, isSwiggyCandidateCountCompatible(oneBar, candidate(label)))
        }
    }

    @Test
    fun hyphenatedAndPostfixedOuterCountsCannotBecomeSingleUnits() {
        val oneBar = retailCountItem("soap bar", 1, "soap bar")
        listOf("Soap Bar 4-bar pack", "Soap Bar bars x4", "Soap Bar 4-bars", "Soap Bar 4×125g").forEach { label ->
            assertFalse(label, isSwiggyCandidateCountCompatible(oneBar, candidate(label)))
        }
        val soapWeight = ParsedItem(rawText = "250 g soap bar", query = "soap bar", quantity = Quantity.Weight(250))
        assertNull(swiggyMeasuredPackQuantity(soapWeight, candidate("Soap Bars x4 125 g")))
    }

    @Test
    fun bonusCountsCannotHideBehindAnOuterCountOfOne() {
        val oneBar = retailCountItem("soap bar", 1, "soap bar")
        listOf("Soap Bar 1 bar + 3 free", "Buy 1 Get 1 Free Soap Bar 125 g", "Soap Bar 1 bar + 1 bar").forEach { label ->
            assertFalse(label, isSwiggyCandidateCountCompatible(oneBar, candidate(label)))
            assertFalse(label, isSwiggyCandidateCountCompatible(oneBar.copy(quantity = Quantity.Default), candidate(label)))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(oneBar.copy(quantity = Quantity.Default), candidate(label))
            }
        }
        val soapWeight = ParsedItem(rawText = "250 g soap bar", query = "soap bar", quantity = Quantity.Weight(250))
        assertNull(swiggyMeasuredPackQuantity(soapWeight, candidate("Soap Bar 1 bar + 3 free 125 g")))
    }

    @Test
    fun doubledNumericSeparatorsCannotFallBackToSingleUnits() {
        val oneBar = retailCountItem("soap bar", 1, "soap bar")
        listOf("Soap Bar 1..2 bars", "Soap Bar 1,,2 boxes").forEach { label ->
            assertFalse(label, isSwiggyCandidateCountCompatible(oneBar, candidate(label)))
        }
    }

    @Test
    fun defaultQuantityCannotSilentlyChooseAnOuterMultipack() {
        val unspecified = ParsedItem(rawText = "soap bar", query = "soap bar", quantity = Quantity.Default,
            strictMatchPhrase = "soap bar")
        listOf("Soap Bar pack of 4", "Soap Bar 4 bars", "Soap Bar bars x4").forEach { label ->
            assertFalse(label, isSwiggyCandidateCountCompatible(unspecified, candidate(label)))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(unspecified, candidate(label))
            }
        }
        assertTrue(isSwiggyCandidateCountCompatible(unspecified, candidate("Soap Bar 1 bar 125 g")))
        assertEquals(1, swiggyRequestedCartQuantity(unspecified, candidate("Soap Bar 1 bar 125 g")))

        // A retrieval-only edit cannot manufacture a source-requested bundle.
        val inventedBundle = unspecified.copy(strictMatchPhrase = "soap bar pack of 4")
        assertFalse(isSwiggyCandidateCountCompatible(inventedBundle, candidate("Soap Bar pack of 4")))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(inventedBundle, candidate("Soap Bar pack of 4"))
        }
    }

    @Test
    fun bare_count_with_weighted_offer_requires_pack_confirmation() {
        val item = ParsedItem(rawText = "2 milk", query = "milk", quantity = Quantity.Count(2))
        val candidate = candidate("Milk · 500 ml")

        val resolution = resolveSwiggyCartQuantity(item, candidate)
        assertTrue(resolution is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(2, (resolution as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
        assertEquals("500 ml", resolution.packSize)
        assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, candidate) }
    }

    @Test
    fun low_parser_confidence_is_marked_for_basket_review() {
        val item = ParsedItem(rawText = "milk", query = "milk", parserConfidence = 0.75f)
        val recommendation = Recommendations(candidates = listOf(candidate("Milk")), requiresConfirmation = false)

        assertTrue(swiggySuggestionNeedsReview(item, recommendation, hasPreferred = true))
    }

    @Test
    fun generic_milk_is_marked_for_review_even_when_history_is_confident() {
        val item = ParsedItem("milk", "milk")
        val flavored = candidate("Chocolate Milk 200 ml")
        val recommendation = Recommendations(listOf(flavored), suggested = flavored, requiresConfirmation = false)
        assertTrue(swiggySuggestionNeedsReview(item, recommendation, hasPreferred = true))
    }

    @Test
    fun negative_preferences_force_review_and_filter_vanilla_only() {
        val items = prepareSwiggyMcpItems("ice cream without vanilla", lookup = { null })
        val item = items.single()
        val recommendation = Recommendations(candidates = listOf(candidate("Vanilla Ice Cream"), candidate("Chocolate Ice Cream")))

        assertTrue(item.avoidPhrases.contains("vanilla"))
        assertTrue(swiggySuggestionNeedsReview(item, recommendation, hasPreferred = true))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Vanilla Ice Cream")))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Chocolate Ice Cream")))
    }

    @Test
    fun default_suggestion_respects_verified_history_without_category_override() {
        val item = ParsedItem(rawText = "milk", query = "milk")
        val ordinary = candidate("Ordinary Cow Milk")
        val chocolate = candidate("Chocolate Milk 200 ml")

        assertEquals(chocolate, swiggyDefaultSuggestion(item, listOf(chocolate, ordinary), preferred = chocolate))
    }

    @Test
    fun history_first_default_is_consistent_across_product_categories() {
        listOf("dark chocolate", "butter", "rice", "soap", "battery", "tea").forEach { query ->
            val item = ParsedItem(rawText = query, query = query)
            val usual = candidate("Usual $query")
            val alternative = candidate("Alternative $query")
            assertEquals(usual, swiggyDefaultSuggestion(item, listOf(alternative, usual), preferred = usual))
            assertEquals(alternative, swiggyDefaultSuggestion(item, listOf(alternative), preferred = usual))
        }
    }

    @Test
    fun default_suggestion_preserves_provider_preferred_plain_milk_variant_between_plain_choices() {
        val item = ParsedItem(rawText = "milk", query = "milk")
        val preferred = candidate("Daily Fresh Cow Milk")
        val backup = candidate("Toned Cow Milk")

        assertEquals(preferred, swiggyDefaultSuggestion(item, listOf(preferred, backup), preferred = preferred))
    }

    @Test
    fun wirePreferredPackReachesDryReviewRegardlessOfSiblingOrder() {
        for ((product, unit) in listOf("rice" to "g", "milk" to "ml")) {
            val item = prepareSwiggyMcpItems("1000 $unit $product") { null }.single()
            val query = swiggyRecommendationQuery(item)
            val spin = "$product-family"
            fun offer(size: Int) =
                """{"spinId":"$spin","skuId":"$product-$size","label":"$product","variant":"$size $unit"}"""
            for (sizes in listOf(listOf(250, 500), listOf(500, 250))) {
                for (preferred in sizes) {
                    val body = """{"ingredientPredicateVersion":1,"sellerPropertyVersion":2,
                        "sellerAssessmentPolicy":"${SwiggySellerAssessment.POLICY}","results":[{
                        "query":"$query","ingredientPredicates":[],"sellerProperties":null,
                        "candidates":[${sizes.joinToString(",") { offer(it) }}],"suggested":${offer(preferred)}}]}"""
                    val recommendation = SwiggyMcpClient.parseRecommendationBatch(
                        body, expectedIngredientPredicates = listOf(emptyList()), expectedSellerProperties = listOf(null),
                    ).single()
                    val checked = assessSwiggyIntentQuantity(item, recommendation)
                    val usable = swiggyUsableCandidates(checked, recommendation)
                    assertEquals("Both packs must be eligible", sizes.map { "$product-$it" }, usable.map { it.skuId })
                    assertEquals("$product-$preferred", recommendation.suggested?.skuId)
                    val selected = swiggyDefaultSuggestion(checked, usable, recommendation.suggested)
                    val preview = requireNotNull(SwiggyCartReviewRequest.build(
                        listOf(checked), mapOf(0 to selected), emptySet(),
                    )).requestedItems.single()
                    assertEquals("$product-$preferred", preview.skuId)
                    assertEquals(spin, preview.spinId)
                    assertEquals(1000 / preferred, preview.quantity)
                    assertTrue(preview.displayName.contains("$preferred $unit"))
                }
            }
        }
    }

    @Test
    fun currentChoiceReturnsCurrentDetailsOnlyForOneMatchingSellableKey() {
        val hint = RecommendationCandidate("rice-family", "Rice", "500 g", "₹40", skuId = "sku-500")
        val refreshed = hint.copy(subtitle = "₹45")
        val sibling = hint.copy(skuId = "sku-250", variant = "250 g")
        val otherFamily = hint.copy(spinId = "other-family")
        val current = listOf(sibling, otherFamily, refreshed)
        assertSame(refreshed, swiggyCurrentChoice(current, hint))
        assertNull(swiggyCurrentChoice(current, null))
        assertNull(swiggyCurrentChoice(listOf(sibling, otherFamily), hint))
        assertNull(swiggyCurrentChoice(listOf(hint, refreshed), hint))
        assertNull(swiggyCurrentChoice(listOf(refreshed, refreshed.copy()), hint))
    }

    @Test
    fun legacyChoiceRequiresOneUnchangedRowAndCannotIdentifyASkuBearingSibling() {
        val legacy = RecommendationCandidate("milk-family", "Milk", "500 ml")
        val current = legacy.copy()
        val withSku = legacy.copy(skuId = "sku-500")
        assertNull(swiggyCurrentChoice(listOf(withSku), legacy))
        assertNull(swiggyCurrentChoice(listOf(current), withSku))
        assertNull(swiggyCurrentChoice(listOf(legacy.copy(variant = "250 ml")), legacy))
        assertNull(swiggyCurrentChoice(listOf(current, current.copy()), legacy))
        assertSame(current, swiggyCurrentChoice(listOf(candidate("Rice"), current), legacy))
    }

    @Test
    fun default_suggestion_honors_strict_match_phrase_for_explicit_chocolate_milk() {
        val item = ParsedItem(
            rawText = "chocolate milk",
            query = "milk",
            strictMatchPhrase = "chocolate milk",
        )
        val ordinary = candidate("Ordinary Cow Milk")
        val chocolate = candidate("Chocolate Milk 200 ml")

        assertEquals(chocolate, swiggyDefaultSuggestion(item, listOf(ordinary, chocolate), preferred = chocolate))
    }

    @Test
    fun default_suggestion_falls_back_to_flavored_only_option_for_full_basket_review() {
        val item = ParsedItem(rawText = "milk", query = "milk")
        val flavoredOnly = candidate("Chocolate Milk 200 ml")

        assertEquals(flavoredOnly, swiggyDefaultSuggestion(item, listOf(flavoredOnly), preferred = null))
    }

    @Test
    fun prepareSwiggyMcpItems_preserves_newline_separated_lists_in_order() {
        val items = prepareSwiggyMcpItems("milk\nbread\nbanana", lookup = { null })

        assertEquals(listOf("milk", "bread", "banana"), items.map { it.query })
    }

    @Test
    fun validationMessage_blocks_duplicate_conflicting_preferences() {
        val items = listOf(
            ParsedItem(
                rawText = "milk without vanilla",
                query = "milk",
                avoidPhrases = listOf("vanilla"),
            ),
            ParsedItem(
                rawText = "milk without chocolate",
                query = "milk",
                avoidPhrases = listOf("chocolate"),
            ),
        )

        assertEquals(
            "One product appears with different quantities or preferences. Please combine it into one clear line; nothing was changed.",
            swiggyMcpItemValidationMessage("milk without vanilla, milk without chocolate", items),
        )
    }

    @Test
    fun needs_exact_health_product_for_vague_symptoms_but_not_for_explicit_medicine_or_regular_grocery() {
        assertTrue(swiggyNeedsExactHealthProduct(ParsedItem(rawText = "woh cough wali goli", query = "woh cough wali goli")))
        assertFalse(swiggyNeedsExactHealthProduct(ParsedItem(rawText = "Vicks cough drops", query = "vicks cough drops")))
        assertFalse(swiggyNeedsExactHealthProduct(ParsedItem(rawText = "milk", query = "milk")))
    }

    @Test
    fun consolidate_requested_items_sums_same_sku_and_rejects_over_twenty() {
        val consolidated = consolidateSwiggyRequestedItems(
            listOf(
                RequestedItem(spinId = "milk-spin", quantity = 2, displayName = "Milk"),
                RequestedItem(spinId = "milk-spin", quantity = 3, displayName = "Milk"),
                RequestedItem(spinId = "bread-spin", quantity = 1, displayName = "Bread"),
                RequestedItem(spinId = "bread-spin", quantity = 19, displayName = "Bread"),
            )
        )

        assertEquals(2, consolidated.size)
        assertEquals(5, consolidated.first { it.spinId == "milk-spin" }.quantity)
        assertEquals(20, consolidated.first { it.spinId == "bread-spin" }.quantity)
        assertTrue(consolidateSwiggyRequestedItems(listOf(
            RequestedItem(spinId = "juice-spin", quantity = 21, displayName = "Juice"),
        )).isEmpty())
    }

    @Test
    fun consolidationPreservesHomogeneousIncrementModeAndRejectsAmbiguousOrInvalidTotals() {
        val increments = consolidateSwiggyRequestedItems(listOf(
            RequestedItem("milk-spin", 1, "Milk", skuId = "milk-sku", quantityMode = IntentCartQuantityMode.INCREMENT),
            RequestedItem("milk-spin", 2, "Milk", quantityMode = IntentCartQuantityMode.INCREMENT),
        ))
        assertEquals(1, increments.size)
        assertEquals(3, increments.single().quantity)
        assertEquals("milk-sku", increments.single().skuId)
        assertEquals(IntentCartQuantityMode.INCREMENT, increments.single().quantityMode)

        assertTrue(consolidateSwiggyRequestedItems(listOf(
            RequestedItem("milk-spin", 1, "Milk"),
            RequestedItem("milk-spin", 1, "Milk", quantityMode = IntentCartQuantityMode.INCREMENT),
        )).isEmpty())
        assertTrue(consolidateSwiggyRequestedItems(listOf(
            RequestedItem("milk-spin", 1, "Milk", skuId = "sku-a"),
            RequestedItem("milk-spin", 1, "Milk", skuId = "sku-b"),
        )).isEmpty())
        assertTrue(consolidateSwiggyRequestedItems(listOf(
            RequestedItem("milk-spin", Int.MAX_VALUE, "Milk"),
            RequestedItem("milk-spin", 1, "Milk"),
        )).isEmpty())
        for (invalid in listOf(0, -1)) {
            assertTrue(consolidateSwiggyRequestedItems(listOf(
                RequestedItem("milk-spin", invalid, "Milk"),
            )).isEmpty())
        }
    }

    @Test
    fun raw_quantity_validation_rejects_over_twenty_before_provider_discovery() {
        val items = prepareSwiggyMcpItems("21 juice", lookup = { null })

        assertEquals(1, items.size)
        assertEquals(
            "Swiggy supports up to quantity 20 per item. Please reduce the quantity for juice; nothing was changed.",
            swiggyMcpItemValidationMessage("21 juice", items),
        )
    }

    private fun candidate(label: String): RecommendationCandidate {
        return RecommendationCandidate(
            spinId = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            label = label,
        )
    }

    private fun retailCountItem(name: String, count: Int, strict: String) = ParsedItem(
        rawText = "$count $name",
        query = name,
        quantity = Quantity.Count(count),
        strictMatchPhrase = strict,
        retailPackCount = true,
    )

    private fun assertLegacyRetailCountHeld(item: ParsedItem, candidate: RecommendationCandidate) {
        assertFalse(swiggyCountResolutionDiagnostic(item, candidate),
            isSwiggyCandidateCountCompatible(item, candidate))
        assertTrue(swiggyCountResolutionDiagnostic(item, candidate),
            resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(item, candidate)
        }
    }

    private fun reviewedSoapContentsItem(inner: Int): ParsedItem {
        val source = "one pack of $inner pieces soap bar"
        return GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = source,
            items = listOf(GroceryIntentItem(
                name = "soap bar",
                quantity = BigDecimal.ONE,
                quantityUnit = "pack",
                packValue = BigDecimal(inner),
                packUnit = "piece",
                required = emptyList(),
                excluded = emptyList(),
                sourceText = source,
                needsClarification = false,
            )),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()
    }
}
