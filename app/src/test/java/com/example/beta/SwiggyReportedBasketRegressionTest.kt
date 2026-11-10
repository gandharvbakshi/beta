package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceSource
import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test

/** Parsed text -> eligible catalogue fixture -> reviewed requested cart count. No MCP calls. */
class SwiggyReportedBasketRegressionTest {
    @Test fun threeItemMixedRequestPreservesProductFormsAndFiveExactMilkPacks() {
        val text = "500 ml 5 milk tetta packs, dark chocolate, popcorn butter ready made"
        val items = prepareSwiggyMcpItems(text) { null }
        assertNull(swiggyMcpItemValidationMessage(text, items))
        assertEquals(3, items.size)
        val candidates = listOf(
            RecommendationCandidate("milk", "Amul Milk Tetra Pack · 500 ml"),
            RecommendationCandidate("chocolate", "Amul Dark Chocolate · 150 g"),
            RecommendationCandidate("popcorn", "Butter Popcorn Ready to Eat · 50 g"),
        )
        val assessed = items.zip(candidates).map { (item, candidate) ->
            assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(candidate)))
        }
        assessed.zip(candidates).forEachIndexed { index, (item, candidate) ->
            assertTrue(item.query, isSwiggyCandidateAllowed(item, candidate))
            if (index == 0) assertReviewedRetailProposal(item, candidate, 5)
            else assertTrue(swiggyCountResolutionDiagnostic(item, candidate), isSwiggyCandidateCountCompatible(item, candidate))
        }
        assertEquals(5, swiggyPreviewItemFromParsedItem(candidates[0], assessed[0]).quantity)
        assertFalse(isSwiggyCandidateAllowed(items[0], RecommendationCandidate("pouch", "Amul Milk Pouch · 500 ml")))
        assertFalse(isSwiggyCandidateAllowed(items[0], RecommendationCandidate("wrong-size", "Amul Milk Tetra Pack · 1 L")))
    }

    @Test fun conflictingPackRequestStopsBeforeProviderSearch() {
        val text = "500 ml 1 l 5 milk tetra packs"
        assertNotNull(swiggyMcpItemValidationMessage(text, prepareSwiggyMcpItems(text) { null }))
    }

    @Test fun dualFieldContainerIdentityAndMeasureMustBelongToTheSelectedSku() {
        fun candidate(spin: String, sku: String, title: String, size: String, containerDescription: String?,
                      evidenceSku: String = sku) = RecommendationCandidate(
            spinId = spin, skuId = sku, label = "$title · $size", variant = size,
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", spin, evidenceSku,
                CatalogueQuantityFields(variantName = size, quantityDescription = containerDescription)),
        )

        fun check(source: String, offer: RecommendationCandidate): Pair<SwiggyCartQuantityResolution, SwiggyCartReviewRequest?> {
            val item = prepareSwiggyMcpItems(source) { null }.single()
            val recommendations = SwiggyMcpClient.Recommendations(listOf(offer))
            val checked = assessSwiggyIntentQuantity(item, recommendations)
            val resolution = resolveSwiggyCartQuantity(checked, offer)
            val request = if (offer in swiggyUsableCandidates(checked, recommendations))
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer), emptySet()) else null
            return resolution to request
        }

        val milkSource = "one tetra pack of milk"
        val milkExact = candidate("milk-spin", "milk-sku", "Milk Tetra Pack", "500 ml", "1 tetra pack")
        val (milkResolution, milkReview) = check(milkSource, milkExact)
        assertEquals(SwiggyCartQuantityResolution.Exact(1), milkResolution)
        assertEquals("milk-sku" to 1, requireNotNull(milkReview).requestedItems.single().let { it.skuId to it.quantity })

        val milkSizeOnly = candidate("milk-size-only", "milk-size-only-sku", "Milk Tetra Pack", "500 ml", null)
        val milkHeld = check(milkSource, milkSizeOnly)
        assertTrue(milkHeld.first is SwiggyCartQuantityResolution.Held)
        assertNull(milkHeld.second)

        val juiceSource = "one tetra pack of apple juice"
        val juiceExact = candidate("juice-spin", "juice-sku", "Apple Juice Tetra Pack", "500 ml", "1 tetra pack")
        val (juiceResolution, juiceReview) = check(juiceSource, juiceExact)
        assertEquals(SwiggyCartQuantityResolution.Exact(1), juiceResolution)
        assertEquals("juice-sku" to 1, requireNotNull(juiceReview).requestedItems.single().let { it.skuId to it.quantity })
        val juiceSizeOnly = candidate("juice-size-only", "juice-size-only-sku", "Apple Juice Tetra Pack", "500 ml", null)
        val juiceHeld = check(juiceSource, juiceSizeOnly)
        assertTrue(juiceHeld.first is SwiggyCartQuantityResolution.Held)
        assertNull(juiceHeld.second)

        val rejectedOffers = listOf(
            candidate("milk-two-tetra", "milk-two-tetra-sku", "Milk Tetra Pack", "500 ml", "2 tetra packs"),
            candidate("milk-bottle", "milk-bottle-sku", "Milk Tetra Pack", "500 ml", "1 bottle"),
            candidate("milk-count-in-title", "milk-count-in-title-sku", "1 Tetra Pack Milk", "500 ml", "1 tetra pack"),
            candidate("milk-stale-evidence", "milk-stale-sku", "Milk Tetra Pack", "500 ml", "1 tetra pack", "other-sku"),
        )
        rejectedOffers.forEach { offer ->
            val (resolution, request) = check(milkSource, offer)
            assertTrue("${offer.spinId}: $resolution", resolution is SwiggyCartQuantityResolution.Held)
            assertNull("${offer.spinId}", request)
        }

        val oilSource = "one bottle of sunflower oil"
        val bottleControl = candidate("oil-bottle", "oil-bottle-sku", "Sunflower Oil Bottle", "500 ml", "1 bottle")
        val (bottleResolution, bottleReview) = check(oilSource, bottleControl)
        assertEquals(SwiggyCartQuantityResolution.Exact(1), bottleResolution)
        assertEquals("oil-bottle-sku" to 1,
            requireNotNull(bottleReview).requestedItems.single().let { it.skuId to it.quantity })
    }

    // Fixed, already-exposed inputs; no real catalogue, price, preference or user data.
    private data class Good(val id: String, val title: String, val size: String, val units: Int,
                            val amount: Quantity, val decoys: List<String>, val sellerContainer: String? = null)

    private fun standardReview(id: String, instruction: String, expected: List<Good>,
                               lookup: (String) -> Preference? = { null }) {
        fun candidate(key: String, title: String, size: String, container: String? = null) = RecommendationCandidate(
            key, "$title · $size", skuId = "sku-$key", variant = size,
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", key, "sku-$key",
                CatalogueQuantityFields(variantName = size, quantityDescription = container)))
        val items = prepareSwiggyMcpItems(instruction, lookup)
        val errors = mutableListOf<String>()
        if (swiggyMcpItemValidationMessage(instruction, items) != null) errors += "preparation-held"
        if (items.size != expected.size) errors += "rows=${items.size}/${expected.size}"
        // Identify this fixed fixture's source rows independently of position, so a stray
        // greeting doesn't get reported as the tea row. Never feed this mapping to Beta.
        val sourceKinds = items.map { item ->
            expected.singleOrNull { gold ->
                val term = when (gold.id) { "drink" -> "7 up"; "sweet" -> "5 star"; else -> gold.id }
                item.rawText.contains(term, ignoreCase = true)
            }?.id
        }
        println("standard_exposed $id parsed=" + sourceKinds.zip(items).joinToString(",") { (kind, item) ->
            "${kind ?: "extra"}:${item.quantity}"
        })
        if (sourceKinds != expected.map { it.id }) errors += "source-rows-not-aligned"
        val selected = mutableMapOf<Int, RecommendationCandidate>()
        val checked = items.mapIndexed { index, item ->
            val gold = expected.find { it.id == sourceKinds[index] } ?: return@mapIndexed item
            val actual = candidate(gold.id, gold.title, gold.size, gold.sellerContainer)
            val alternatives = gold.decoys.mapIndexed { n, title -> candidate("${gold.id}-wrong$n", title, gold.size) } +
                if (gold.amount is Quantity.Weight) listOf(candidate("${gold.id}-nondivisor", gold.title, "330 g")) else emptyList()
            val offers = SwiggyMcpClient.Recommendations(listOf(actual) + alternatives)
            val assessed = assessSwiggyIntentQuantity(item, offers)
            val usable = swiggyUsableCandidates(assessed, offers)
            val leaked = alternatives.filter { it in usable }.map { it.spinId }
            val qty = resolveSwiggyCartQuantity(assessed, actual)
            val units = when (qty) {
                is SwiggyCartQuantityResolution.Exact -> qty.cartUnits
                is SwiggyCartQuantityResolution.RetailProposal -> qty.cartUnits
                is SwiggyCartQuantityResolution.Held -> null
            }
            // An implicit single retail item and an explicit single both mean one.
            val amount = item.quantity == gold.amount || gold.amount == Quantity.Count(1) && item.quantity == Quantity.Default
            println("standard_exposed $id/${gold.id} amount=$amount viable=${actual in usable} units=$units " +
                "hold=${(qty as? SwiggyCartQuantityResolution.Held)?.reason} acceptedDecoys=$leaked")
            if (!amount || actual !in usable || units != gold.units || leaked.isNotEmpty()) errors += gold.id
            if (actual in usable) selected[index] = actual
            assessed
        }
        val review = if (errors.none { it == "preparation-held" } && items.size == expected.size)
            SwiggyCartReviewRequest.build(checked, selected, emptySet()) else null
        val requested = review?.requestedItems?.map { it.skuId to it.quantity }
        val complete = requested == expected.map { "sku-${it.id}" to it.units }
        println("standard_exposed $id fullReview=$complete")
        if (!complete) errors += "incomplete-final-review"
        assertTrue("$id: $errors", errors.isEmpty())
    }

    @Test fun exposedStandardMilkChocolatePopcorn() = standardThreeItemReview(null)
    @Test fun exposedStandardMilkChocolatePopcornWithMerchantContainerProof() = standardThreeItemReview("1 tetra pack")

    private fun standardThreeItemReview(container: String?) = standardReview("three",
        "One tetra pack of milk, two dark chocolates and one packet of ready-made butter popcorn.", listOf(
            Good("milk", "Milk Tetra Pack", "500 ml", 1, Quantity.Count(1), listOf("Milk Pouch"), container),
            Good("chocolate", "Dark Chocolate", "100 g", 2, Quantity.Count(2), listOf("Milk Chocolate")),
            Good("popcorn", "Ready to Eat Butter Popcorn Packet", "50 g", 1, Quantity.Count(1),
                listOf("Butter Popcorn Microwave Kernels Packet", "Ready to Eat Caramel Popcorn Packet"))))

    @Test fun exposedStandardHinglishTeaDal() = standardReview("hinglish",
        "Bhai, 500 g black tea aur 2 kg chana dal chahiye.", listOf(
            Good("tea", "Black Tea", "250 g", 2, Quantity.Weight(500), listOf("Green Tea")),
            Good("dal", "Chana Dal", "500 g", 4, Quantity.Weight(2000), listOf("Moong Dal"))))

    @Test fun spokenRequestFrameIsBoundedNotAnItemVocabulary() {
        for (greeting in listOf("Bhai", "Namaste ji", "Didi")) {
            for (suffix in listOf("chahiye.", "le aao!", "mangao।")) {
                assertEquals("500 g plain oats aur 2 kg whole wheat flour",
                    standardGroceryRequestBody("$greeting, 500 g plain oats aur 2 kg whole wheat flour $suffix"))
            }
        }
        for (text in listOf("Bhai, 500 g tea chahiye?", "Bhai, 500 g tea", "Namaste Tea, 2 packets",
            "Chahiye Masala", "if cheap, 500 g tea", "Bhai, 500 g tea nahi chahiye.",
            "Didi, 1 kg sugar mat mangao!")) assertEquals(text, standardGroceryRequestBody(text))
        assertEquals("Didi Tea, 2 packets", standardGroceryRequestBody("Didi Tea, 2 packets chahiye."))
        assertEquals("500 g tea without sugar", standardGroceryRequestBody("Bhai, 500 g tea without sugar chahiye."))
    }

    @Test fun negativeSpeechCannotCreateAPositiveProductAndAmountKeepsItsOriginalSource() {
        for ((text, unwanted) in listOf("doodh nhi chahiye" to "Milk",
            "2 kg chawal, cheeni nai chahiye." to "Sugar", "1 litre doodh na chahiye" to "Milk",
            "Bhai, 1 kg sugar nahi chahiye." to "Sugar")) {
            assertEquals(text, standardGroceryRequestBody(text))
            val items = prepareSwiggyMcpItems(text) { null }
            val size = if (unwanted == "Milk") "1 L" else "1 kg"
            val forbidden = RecommendationCandidate("forbidden", "$unwanted · $size", skuId = "forbidden",
                variant = size, catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation",
                    "forbidden", "forbidden", CatalogueQuantityFields(variantName = size)))
            val offers = SwiggyMcpClient.Recommendations(listOf(forbidden))
            val positive = prepareSwiggyMcpItems("$size $unwanted") { null }.single()
            assertTrue("Positive control must be selectable", forbidden in
                swiggyUsableCandidates(assessSwiggyIntentQuantity(positive, offers), offers))
            assertTrue(text, items.all {
                forbidden !in swiggyUsableCandidates(assessSwiggyIntentQuantity(it, offers), offers)
            })
        }
        val text = "Bhaiya, 2 kg chawal chahiye."
        val row = prepareSwiggyMcpItems(text) { null }.single()
        assertEquals("2 kg chawal", row.rawText)
        assertEquals(8, text.indexOf(row.rawText))
        assertEquals(Quantity.Weight(2000), row.quantity)
    }

    @Test fun exposedStandardRiceSugar() = standardReview("rice-sugar",
        "Please get 1 kg basmati rice and 500 g white sugar.", listOf(
            Good("rice", "Basmati Rice", "500 g", 2, Quantity.Weight(1000), listOf("Sona Masoori Rice")),
            Good("sugar", "White Sugar", "250 g", 2, Quantity.Weight(500), listOf("Brown Sugar"))))

    private fun branded(override: Boolean) = standardReview(if (override) "brand-preference" else "brand",
        "2 7 up, 5 star chocolate 2 packets, do 24 mantra atta", listOf(
            Good("drink", "7 Up", "250 ml", 2, Quantity.Count(2), listOf("Sprite")),
            Good("sweet", "5 Star Chocolate Packet", "40 g", 2, Quantity.Count(2), listOf("Silk Chocolate Packet")),
            Good("atta", "24 Mantra Atta", "1 kg", 2, Quantity.Count(2), listOf("Aashirvaad Atta")))) { query ->
        if (override && query.equals("24 mantra atta", true)) Preference(query, "aashirvaad atta",
            source = PreferenceSource.USER_OVERRIDE, confidence = 1f) else null
    }
    @Test fun exposedStandardExplicitBrand() = branded(false)
    @Test fun exposedStandardExplicitBrandWithConflictingSavedPreference() = branded(true)
}
