package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun nativeMeasuredListEnvelope(instruction: String, items: List<GroceryIntentItem>): List<String>? =
    compileMeasuredProduceList(instruction, items)?.map { it.category }

class GroceryMeasuredListEnvelopeTest {
    @Test fun attestedFreshCategoriesBuildMixedOriginalListWithoutConfusingSweetAndOrdinaryPotato() {
        val native = listOf("आलू", "शकरकंद", "भिंडी", "गाजर", "पालक", "चुकंदर")
        val english = listOf("potato", "sweet potato", "okra", "carrot", "spinach", "beetroot")
        val grams = listOf(500, 500, 250, 200, 100, 150)
        val sources = native.indices.map { "${grams[it]} ग्राम ${native[it]}" }
        val instruction = sources.dropLast(1).joinToString(", ") + " और " + sources.last() + " चाहिए।"
        val draft = GroceryIntentDraft("grocery-intent-v1", true, instruction,
            native.indices.map { row(sources[it], native[it], grams[it].toString(), "g") }, false)
        assertEquals("complete original envelope", english, nativeMeasuredListEnvelope(instruction, draft.items))
        val prepared = draft.prepareForReview()
        assertEquals(emptyList<String>(), prepared.unresolvedText)
        assertEquals(english, prepared.items.map { it.query })
        assertEquals(sources, prepared.items.map { it.rawText })
        assertEquals(grams.map { Quantity.Weight(it) }, prepared.items.map { it.quantity })
        val packGrams = listOf(250, 500, 250, 200, 100, 150)
        fun offer(i: Int, title: String = english[i], pack: Int = packGrams[i]) = SwiggyMcpClient.RecommendationCandidate(
            "attested-$i", "$title · $pack g", variant = "$pack g", skuId = "attested-sku-$i",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "attested-$i", "attested-sku-$i",
                CatalogueQuantityFields(packSize = "$pack g")))
        val offers = native.indices.map { offer(it) }
        offers.forEachIndexed { i, candidate ->
            val result = SwiggyMcpClient.Recommendations(listOf(candidate))
            assertEquals(listOf(candidate), swiggyUsableCandidates(assessSwiggyIntentQuantity(prepared.items[i], result), result))
        }
        fun build(chosen: List<SwiggyMcpClient.RecommendationCandidate>): SwiggyCartReviewRequest? {
            val checked = prepared.items.mapIndexed { i, item -> assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(chosen[i]))) }
            return SwiggyCartReviewRequest.build(checked, chosen.mapIndexed { i, c -> i to c }.toMap(), emptySet())
        }
        val request = requireNotNull(build(offers))
        assertEquals(6, request.requestedItems.size)
        offers.indices.forEach { i ->
            val requested = request.requestedItems.single { it.spinId == offers[i].spinId && it.skuId == offers[i].skuId }
            assertEquals(grams[i], requested.quantity * packGrams[i]) // Independent delivered mass, no rounding.
        }
        assertTrue(request.quantityProposals.isEmpty())
        for (i in offers.indices) {
            val bad = listOf(offer(i, if (i == 0) "sweet potato" else if (i == 1) "potato" else "potato"),
                offer(i, "${english[i]} puree"), offer(i, "${english[i]} powder"), offer(i, "${english[i]} juice"),
                offer(i, "${english[i]} chips"), offer(i, pack = grams[i] + 1), offers[i].copy(catalogueQuantity = null),
                offers[i].copy(skuId = "unbound-attested-sku"))
            bad.forEach { candidate -> assertNull("Wrong or unproven chosen offer must hold the whole list",
                build(offers.mapIndexed { j, c -> if (i == j) candidate else c })) }
        }
    }

    @Test fun newlyAttestedNounsDoNotWaiveHiddenSourceMeaningOrUnownedModifiers() {
        for (noun in listOf("शकरकंद", "भिंडी", "गाजर", "पालक", "चुकंदर")) {
            for (quote in listOf("250 ग्राम $noun और नमक", "250 ग्राम $noun बिना कीटनाशक", "250 ग्राम $noun कल",
                    "जैविक 250 ग्राम $noun", "250 ग्राम $noun तीन पैकेट")) {
                val instruction = "$quote चाहिए।"
                val items = listOf(row(quote, noun, "250", "g"))
                assertNull(nativeMeasuredListEnvelope(instruction, items))
                val draft = GroceryIntentDraft("grocery-intent-v1", true, instruction, items, false)
                assertTrue(runCatching { draft.prepareForReview().items.none { it.measuredProduceSubject != null } }.getOrDefault(true))
            }
        }
    }

    private fun row(
        source: String,
        name: String,
        quantity: String,
        unit: String,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        packValue: String? = null,
    ) = GroceryIntentItem(
        name = name,
        quantity = BigDecimal(quantity),
        quantityUnit = unit,
        packValue = packValue?.let(::BigDecimal),
        packUnit = if (packValue == null) "unspecified" else "kg",
        required = required,
        excluded = excluded,
        sourceText = source,
        needsClarification = false,
    )

    @Test fun recognizesOnlyClosedMeasuredListsAcrossTheNativeCategorySet() {
        val cases = listOf(
            Triple("एक किलो आलू, 250 ग्राम टमाटर और 500 ग्राम हरी मिर्च चाहिए।",
                listOf(row("एक किलो आलू", "आलू", "1", "kg"),
                    row("250 ग्राम टमाटर", "टमाटर", "250", "g"),
                    row("500 ग्राम हरी मिर्च", "हरी मिर्च", "500", "g")),
                listOf("potato", "tomato", "green chilli")),
            Triple("दो किलो प्याज और 100 ग्राम लहसुन ले आना।",
                listOf(row("दो किलो प्याज", "प्याज", "2", "kg"),
                    row("100 ग्राम लहसुन", "लहसुन", "100", "g")),
                listOf("onion", "garlic")),
            Triple("1 kg फूलगोभी, 2 kg पत्तागोभी, 500 g बैंगन और 3 kg खीरा भेज देना",
                listOf(row("1 kg फूलगोभी", "फूलगोभी", "1", "kg"),
                    row("2 kg पत्तागोभी", "पत्तागोभी", "2", "kg"),
                    row("500 g बैंगन", "बैंगन", "500", "g"),
                    row("3 kg खीरा", "खीरा", "3", "kg")),
                listOf("cauliflower", "cabbage", "brinjal", "cucumber")),
            Triple("500 ग्राम टमाटर, 250 ग्राम आलू और 1 kg प्याज।",
                listOf(row("500 ग्राम टमाटर", "टमाटर", "500", "g"),
                    row("250 ग्राम आलू", "आलू", "250", "g"),
                    row("1 kg प्याज", "प्याज", "1", "kg")),
                listOf("tomato", "potato", "onion")),
        )
        cases.forEach { (instruction, items, expected) ->
            assertEquals(instruction, expected, nativeMeasuredListEnvelope(instruction, items))
        }
    }

    @Test fun rejectsMissingDuplicateReorderedOrStaleRows() {
        val instruction = "एक किलो आलू और 500 ग्राम टमाटर चाहिए।"
        val potato = row("एक किलो आलू", "आलू", "1", "kg")
        val tomato = row("500 ग्राम टमाटर", "टमाटर", "500", "g")
        listOf(
            listOf(potato),
            listOf(potato, potato),
            listOf(tomato, potato),
            listOf(potato, tomato.copy(sourceText = "250 ग्राम टमाटर")),
            listOf(potato, tomato.copy(quantity = BigDecimal("250"))),
        ).forEach { assertNull(nativeMeasuredListEnvelope(instruction, it)) }
    }

    @Test fun rejectsModifiersDietaryScopeCorrectionBrandAndUnexpectedTags() {
        val cases = listOf(
            "एक किलो लाल आलू चाहिए।" to listOf(row("एक किलो लाल आलू", "आलू", "1", "kg")),
            "एक किलो जैविक आलू चाहिए।" to listOf(row("एक किलो जैविक आलू", "आलू", "1", "kg")),
            "बिना कीटनाशक के 500 ग्राम टमाटर चाहिए।" to listOf(row("बिना कीटनाशक के 500 ग्राम टमाटर", "टमाटर", "500", "g")),
            "अगर ताज़े हों तो 500 ग्राम टमाटर चाहिए।" to listOf(row("500 ग्राम टमाटर", "टमाटर", "500", "g")),
            "पहले 2 किलो आलू, नहीं 1 किलो आलू चाहिए।" to listOf(row("1 किलो आलू", "आलू", "1", "kg")),
            "1 किलो BrandX आलू चाहिए।" to listOf(row("1 किलो BrandX आलू", "आलू", "1", "kg")),
        )
        cases.forEach { (instruction, items) -> assertNull(instruction, nativeMeasuredListEnvelope(instruction, items)) }
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू चाहिए।",
            listOf(row("एक किलो आलू", "आलू", "1", "kg", required = listOf("organic")))))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू चाहिए।",
            listOf(row("एक किलो आलू", "आलू", "1", "kg", excluded = listOf("pesticide")))))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू चाहिए।",
            listOf(row("एक किलो आलू", "आलू", "1", "kg", packValue = "1"))))
    }

    @Test fun rejectsWrongDimensionsSeparatorsAndNonAffirmingClosures() {
        val source = "एक किलो आलू और 500 ग्राम टमाटर"
        val rows = listOf(row("एक किलो आलू", "आलू", "1", "kg"),
            row("500 ग्राम टमाटर", "टमाटर", "500", "g"))
        assertNull(nativeMeasuredListEnvelope(source, rows))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू और 500 ml टमाटर चाहिए।", rows))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू; 500 ग्राम टमाटर चाहिए।", rows))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू या 500 ग्राम टमाटर चाहिए।", rows))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू और चाहिए।", rows))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू चाहिए?", listOf(rows.first())))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू चाहिए, मेरे लिए", listOf(rows.first())))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू चाहिए तो", listOf(rows.first())))
        assertNull(nativeMeasuredListEnvelope("एक किलो आलू​ और 500 ग्राम टमाटर चाहिए।", rows))
        assertNull(nativeMeasuredListEnvelope("एक किलो फूलगोभी और 500 ग्राम आलू चाहिए।",
            listOf(row("एक किलो फूलगोभी और", "फूलगोभी", "1", "kg"), rows.first())))
    }

    @Test fun v1PreparationPreservesTheExactMeasuredRowsBeforeEnvelopeClassification() {
        val instruction = "एक किलो आलू और 500 ग्राम टमाटर चाहिए।"
        val draft = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = instruction,
            items = listOf(row("एक किलो आलू", "आलू", "1", "kg"),
                row("500 ग्राम टमाटर", "टमाटर", "500", "g")),
            modelNeedsClarification = false,
        )
        val review = draft.prepareForReview()
        assertEquals(emptyList<String>(), review.unresolvedText)
        val prepared = review.items
        assertEquals(listOf("potato", "tomato"), prepared.map { it.query })
        assertEquals(listOf("potato", "tomato"), prepared.map { it.strictMatchPhrase })
        val translated = draft.copy(items = draft.items.mapIndexed { index, item ->
            item.copy(name = listOf("potato", "tomato")[index])
        })
        assertEquals(prepared, translated.prepareForReview().items)
        val substituted = translated.copy(items = listOf(translated.items.first().copy(name = "sweet potato"), translated.items.last()))
        assertTrue(runCatching { substituted.prepareForReview().items.all { it.measuredProduceSubject != null } }.getOrDefault(false).not())
        for (collectHolds in listOf(true, false)) {
            val context = requireNotNull(draft.assessForContextReview(collectHolds).preparation)
            assertTrue(context.unresolvedText.isNotEmpty())
            assertEquals(listOf("आलू", "टमाटर"), context.items.map { it.query })
        }
        assertEquals(listOf(Quantity.Weight(1000), Quantity.Weight(500)), prepared.map { it.quantity })
        assertEquals(listOf("एक किलो आलू", "500 ग्राम टमाटर"), prepared.map { it.rawText })
        assertEquals(listOf("potato", "tomato"), nativeMeasuredListEnvelope(instruction, draft.items))
        val stale = draft.copy(items = listOf(draft.items[0], draft.items[1].copy(quantity = BigDecimal("250"))))
        assertTrue(runCatching { stale.prepareForReview() }.isFailure ||
            nativeMeasuredListEnvelope(stale.instruction, stale.items) == null)
    }

    @Test fun localizedSellerAppositionNeedsExactV1SubjectAndBoundSinglePackThroughFinalReview() {
        val draft = GroceryIntentDraft("grocery-intent-v1", true,
            "एक किलो आलू, 100 ग्राम हरी मिर्च और 250 ग्राम टमाटर चाहिए।",
            listOf(row("एक किलो आलू", "आलू", "1", "kg"), row("100 ग्राम हरी मिर्च", "हरी मिर्च", "100", "g"),
                row("250 ग्राम टमाटर", "टमाटर", "250", "g")), false)
        val prepared = draft.prepareForReview().items
        assertEquals(listOf("potato", "green chilli", "tomato"), prepared.map { it.query })
        fun offer(index: Int, head: String, pack: String) = SwiggyMcpClient.RecommendationCandidate(
            "apposition-$index", "$head · $pack", variant = pack, skuId = "apposition-sku-$index",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "apposition-$index", "apposition-sku-$index",
                CatalogueQuantityFields(packSize = pack)))
        val offers = listOf(offer(0, "Potato (Aloo Gadde)", "1 kg"),
            offer(1, "Green Chilli (Hasiru Menasinakaayi)", "100 g"), offer(2, "Tomatoes (Tamatar)", "250 g"))
        val built = requireNotNull(SwiggyCartReviewRequest.build(prepared, offers.mapIndexed { i, c -> i to c }.toMap(), emptySet()))
        assertEquals(listOf(1, 1, 1), built.requestedItems.map { it.quantity })
        assertEquals(offers.map { it.skuId }, built.requestedItems.map { it.skuId })
        val valid = offers.first()
        val badHeads = listOf("Potato (Tamatar)", "Potato (Aloo Gadde Tomato)", "Potato (Gadde)", "Potato (Aloo Gadd)",
            "Potato ((Aloo Gadde))", "Potato (Aloo Gadde) (Aloo Gadde)", "Organic Potato (Aloo Gadde)",
            "Baby Potato (Aloo Gadde)", "BrandX Potato (Aloo Gadde)", "Buy Potato (Aloo Gadde)",
            "Potato (Aloo Gadde) organic", "Potato (Aloo Gadde) red", "Potato (Aloo Gadde) BrandX",
            "Potato (Aloo Gadde) please", "Aloo Gadde (आलू)", "Potato (1 kg)", "Potato (Aloo Gadde 1)", "BrandX")
        val badOffers = badHeads.map { offer(0, it, "1 kg") } + listOf(
            valid.copy(catalogueQuantity = null), valid.copy(skuId = "unbound-sku"),
            valid.copy(variant = "500 g"), valid.copy(subtitle = "Tomato Puree"),
            valid.copy(catalogueQuantity = valid.catalogueQuantity!!.copy(fields = CatalogueQuantityFields(packSize = "500 g"))),
            valid.copy(catalogueQuantity = valid.catalogueQuantity!!.copy(fields = CatalogueQuantityFields(packSize = "2 x 500 g"))),
            offer(0, "Potato (Aloo Gadde)", "600 g"), offer(0, "Potato (Aloo Gadde)", "1 L"),
            offer(0, "BrandX", "1 kg").copy(catalogueBrand = SwiggyMcpClient.CatalogueBrandEvidence(
                "Potato", "apposition-0", "apposition-sku-0", "search_variation")))
        badOffers.forEach { bad ->
            assertNull("Unproved apposition must not reach final request", SwiggyCartReviewRequest.build(prepared,
                mapOf(0 to bad, 1 to offers[1], 2 to offers[2]), emptySet()))
        }
        val wrongDimension = draft.copy(instruction = draft.instruction.replace("100 ग्राम", "100 ml"),
            items = draft.items.mapIndexed { i, item -> if (i == 1) item.copy(quantityUnit = "ml",
                sourceText = "100 ml हरी मिर्च") else item })
        assertTrue(runCatching { wrongDimension.prepareForReview().items.all { it.measuredProduceSubject != null } }
            .getOrDefault(false).not())
    }

    @Test fun measuredLookupCannotBuyAnIngredientMatchOrAnUnboundPack() {
        val draft = GroceryIntentDraft("grocery-intent-v1", true, "एक किलो आलू और 100 ग्राम हरी मिर्च भेज देना।",
            listOf(row("एक किलो आलू", "आलू", "1", "kg"), row("100 ग्राम हरी मिर्च", "हरी मिर्च", "100", "g")),
            modelNeedsClarification = false)
        val (potato, chilli) = draft.prepareForReview().items
        fun offer(label: String, pack: String) = SwiggyMcpClient.RecommendationCandidate(
            "demo-spin", "$label · $pack", variant = pack, skuId = "demo-sku",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "demo-spin", "demo-sku",
                CatalogueQuantityFields(packSize = pack)))
        val plain = offer("Fresh Potatoes", "500 g")
        assertEquals(2, swiggyPreviewItemFromParsedItem(plain, potato).quantity)
        assertEquals(2, SwiggyCartReviewRequest.build(listOf(potato), mapOf(0 to plain), emptySet())
            ?.requestedItems?.singleOrNull()?.quantity)
        assertEquals(1, swiggyPreviewItemFromParsedItem(offer("Green Chillies", "100 g"), chilli).quantity)
        for (bad in listOf(offer("Sweet Potato", "500 g"), offer("Potato Starch", "500 g"),
            offer("Potato", "600 g"), plain.copy(catalogueQuantity = null), plain.copy(skuId = "changed-sku"),
            plain.copy(subtitle = "Tomato Puree"), plain.copy(variant = "600 g"),
            offer("Potato 500 g 1 kg", "500 g"))) {
            assertTrue("Unproved seller offer entered a preview", runCatching { swiggyPreviewItemFromParsedItem(bad, potato) }.isFailure)
            assertNull(SwiggyCartReviewRequest.build(listOf(potato), mapOf(0 to bad), emptySet()))
        }
        assertNull(SwiggyCartReviewRequest.build(listOf(potato.copy(strictMatchPhrase = "other")),
            mapOf(0 to plain), emptySet()))
        assertTrue(runCatching { swiggyPreviewItemFromParsedItem(offer("Green Chilli Sauce", "100 g"), chilli) }.isFailure)
    }
}
