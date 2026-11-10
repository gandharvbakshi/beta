package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentContextBasketTest {
    // The revision regressions below cover fresh source assessment only. Their
    // synthetic Recommendations data has no address/time freshness metadata and
    // says nothing about production cache adoption or consent persistence.
    @Test
    fun pendingRowsRemainLegacyHeldButExplicitBasketRequestCanUseRetailProposal() {
        val instruction = "For the meeting get 2 milk"
        val preparation = GroceryIntentContextResponse.parse(response(instruction, listOf("2 milk" to 2),
            listOf("background" to "For the meeting get")), instruction).prepareForReview()
        assertTrue(preparation.blockingIssues.toString(), preparation.blockingIssues.isEmpty())
        val item = preparation.legacyPreparation.items.single()
        val milk = candidate("Amul Milk 500 ml", "milk", "milk-sku")
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation, listOf(recommendations("milk", milk))))

        assertTrue(item.requiresIntentQuantityProof)
        assertNull(item.intentQuantityProvenance)
        assertFalse(isSwiggyCandidateAllowed(item, milk))
        assertTrue(basket.usable(0).contains(milk))
        assertTrue(basket.resolution(0, milk) is SwiggyCartQuantityResolution.RetailProposal)

        val request = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to milk), emptySet()))
        assertEquals(2, request.requestedItems.single().quantity)
        assertEquals(1, request.quantityProposals.size)
        assertEquals("context-offer-v1", request.quantityProposals.single().evidence.resolverVersion)
    }

    @Test
    fun queryOrderingIsRequiredAndSuggestedOnlyCandidateCannotBeSelected() {
        val instruction = "2 milk, 3 eggs"
        val preparation = preparation(instruction, listOf("2 milk" to 2, "3 eggs" to 3))
        val milk = candidate("Milk 500 ml", "milk")
        val eggs = candidate("Eggs 6 pieces", "eggs")
        val ordered = listOf(recommendations("milk", milk), recommendations("eggs", eggs))
        assertNotNullBasket(preparation, ordered)
        assertNull(GroceryIntentContextBasket.create(preparation, ordered.reversed()))

        val suggestedOnly = candidate("Other Milk 500 ml", "suggested-only")
        val catalogue = Recommendations(candidates = listOf(milk), suggested = suggestedOnly, query = "milk")
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation(instruction, listOf("2 milk" to 2, "3 eggs" to 3)),
            listOf(catalogue, recommendations("eggs", eggs))))
        assertFalse(basket.usable(0).contains(suggestedOnly))
        assertTrue(basket.resolution(0, suggestedOnly) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to suggestedOnly, 1 to eggs), emptySet()))
    }

    @Test
    fun conflictingSuggestionMetadataHoldsTheWholeResponse() {
        val instruction = "For the meeting get 2 milk"
        val preparation = GroceryIntentContextResponse.parse(response(instruction, listOf("2 milk" to 2),
            listOf("background" to "For the meeting get")), instruction).prepareForReview()
        assertTrue(preparation.blockingIssues.toString(), preparation.blockingIssues.isEmpty())
        val listed = candidate("Milk 500 ml", "same-sku", "same-sku")
        val conflictingSuggested = listed.copy(label = "Different Milk 500 ml")
        val response = Recommendations(candidates = listOf(listed), suggested = conflictingSuggested, query = "milk")
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation, listOf(response)))

        assertTrue(basket.usable(0).isEmpty())
        assertTrue(basket.resolution(0, listed) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to listed), emptySet()))
    }

    @Test
    fun basketAndRequestDetachCandidateListsAndBindTheExactBasketRevision() {
        val instruction = "2 milk"
        val source = preparation(instruction, listOf("2 milk" to 2))
        val milk = candidate("Milk 500 ml", "milk")
        val mutableCandidates = mutableListOf(milk)
        val mutableResponse = Recommendations(candidates = mutableCandidates, suggested = milk, query = "milk")
        val basket = requireNotNull(GroceryIntentContextBasket.create(source, listOf(mutableResponse)))
        mutableCandidates += candidate("Other Milk 500 ml", "later")
        assertEquals(listOf(milk), basket.usable(0))

        val request = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to milk), emptySet()))
        val rebuilt = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to milk), emptySet()))
        assertEquals(request, rebuilt)
        assertThrows(UnsupportedOperationException::class.java) {
            (request.requestedItems as MutableList).clear()
        }

        val equivalentNewBasket = requireNotNull(GroceryIntentContextBasket.create(
            preparation(instruction, listOf("2 milk" to 2)), listOf(recommendations("milk", milk)),
        ))
        val changedBasketRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            equivalentNewBasket, mapOf(0 to milk), emptySet(),
        ))
        assertEquals(request.requestedItems, changedBasketRequest.requestedItems)
        assertNotEquals("equivalent totals do not reuse consent from another basket snapshot", request, changedBasketRequest)
    }

    @Test
    fun blockingPreparationCannotCreateBasketOrRequest() {
        val instruction = "2 milk"
        val response = response(instruction, listOf("2 milk" to 2), listOf("background" to "2 milk"))
        val preparation = GroceryIntentContextResponse.parse(response, instruction).prepareForReview()
        assertTrue(preparation.blockingIssues.isNotEmpty())
        assertNull(GroceryIntentContextBasket.create(preparation, listOf(recommendations(
            "milk", candidate("Milk 500 ml", "milk"),
        ))))
    }

    @Test
    fun pendingCountAndDefaultRowsStayOnSeparateResolutionPaths() {
        val instruction = "For the meeting get 2 milk, bread"
        val preparation = GroceryIntentContextResponse.parse(response(instruction,
            listOf("2 milk" to 2, "bread" to null), listOf("background" to "For the meeting get")),
            instruction).prepareForReview()
        val milk = candidate("Milk 500 ml", "milk")
        val wrongBread = candidate("Milk 500 ml", "wrong-bread")
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation, listOf(
            recommendations("milk", milk), recommendations("bread", wrongBread),
        )))

        assertEquals(setOf(0), preparation.pendingCounts.keys)
        assertFalse(basket.items[1].requiresIntentQuantityProof)
        assertTrue(basket.usable(0).contains(milk))
        assertTrue(basket.usable(1).isEmpty())
        assertTrue(basket.resolution(1, wrongBread) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to milk, 1 to wrongBread), emptySet()))
    }

    @Test
    fun duplicateSourceRowsAggregateButSkipSnapshotsRetainDifferentProposalOwnership() {
        val instruction = "2 milk, 2 Amul milk"
        val preparation = preparation(instruction, listOf("2 milk" to 2, "2 Amul milk" to 2))
        val candidate = candidate("Amul Milk 500 ml", "same-milk", "same-milk-sku")
        val queries = preparation.legacyPreparation.items.map(::swiggyRecommendationQuery)
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation, queries.map {
            recommendations(it, candidate)
        }))

        val combined = requireNotNull(SwiggyCartReviewRequest.buildContext(
            basket, mapOf(0 to candidate, 1 to candidate), emptySet(),
        ))
        assertEquals(4, combined.requestedItems.single().quantity)
        assertEquals(listOf(0, 1), combined.quantityProposals.map { it.rowIndex })

        val skipFirst = requireNotNull(SwiggyCartReviewRequest.buildContext(
            basket, mapOf(0 to candidate, 1 to candidate), setOf(0),
        ))
        val skipSecond = requireNotNull(SwiggyCartReviewRequest.buildContext(
            basket, mapOf(0 to candidate, 1 to candidate), setOf(1),
        ))
        assertEquals(2, skipFirst.requestedItems.single().quantity)
        assertEquals(skipFirst.requestedItems, skipSecond.requestedItems)
        assertNotEquals(skipFirst, skipSecond)
        assertEquals(listOf(1), skipFirst.quantityProposals.map { it.rowIndex })
        assertEquals(listOf(0), skipSecond.quantityProposals.map { it.rowIndex })
    }

    @Test
    fun changedSkuOrCandidateFieldsCannotUseTheSavedBasketAssessment() {
        val instruction = "2 milk"
        val preparation = preparation(instruction, listOf("2 milk" to 2))
        val original = candidate("Milk 500 ml", "milk", "milk-sku")
        val basket = requireNotNull(GroceryIntentContextBasket.create(
            preparation, listOf(recommendations("milk", original)),
        ))
        val changedSku = original.copy(skuId = "different-sku")
        val changedDescription = original.copy(label = "Different Milk 500 ml")

        assertEquals(listOf(original), basket.usable(0))
        listOf(changedSku, changedDescription).forEach { changed ->
            assertFalse(basket.usable(0).contains(changed))
            assertTrue(basket.resolution(0, changed) is SwiggyCartQuantityResolution.Held)
            assertNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to changed), emptySet()))
        }
    }

    @Test
    fun explicitNameEditPreservesOtherSourceAndCreatesFreshBasketRequestIdentity() {
        val original = "For the meeting get 2 milk, 3 curd"
        val context = listOf("background" to "For the meeting get")
        val originalPreparation = prepared(original, listOf("2 milk" to 2, "3 curd" to 3), context)
        val milk = candidate("Amul Milk 500 ml", "milk-spin", "milk-sku")
        val curd = candidate("Curd 400 g", "curd-spin", "curd-sku")
        val originalCatalogues = catalogues(originalPreparation, mapOf("2 milk" to milk, "3 curd" to curd))
        val originalBasket = requireNotNull(GroceryIntentContextBasket.create(originalPreparation, originalCatalogues))
        val originalRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            originalBasket, mapOf(0 to milk, 1 to curd), emptySet(),
        ))
        assertEquals(setOf(0), originalPreparation.pendingCounts.keys)

        val base = GroceryIntentRequestRevision.begin(original)
        val start = original.indexOf("milk")
        val revised = requireNotNull(base.replace(base,
            GroceryIntentReviewV2Quote("milk", start, start + "milk".length), "Amul milk"))
        assertEquals(original, revised.originalInstruction)
        assertEquals("For the meeting get 2 Amul milk, 3 curd", revised.instruction)

        val freshPreparation = prepared(revised.instruction, listOf("2 Amul milk" to 2, "3 curd" to 3), context)
        assertEquals(listOf("2 Amul milk", "3 curd"), freshPreparation.itemSources.map { it.text })
        assertEquals(revised.instruction.indexOf("3 curd"), freshPreparation.itemSources[1].startUtf16)
        assertNotEquals(originalCatalogues[0].query,
            swiggyRecommendationQuery(freshPreparation.legacyPreparation.items[0]))
        val freshCatalogues = listOf(
            recommendations(swiggyRecommendationQuery(freshPreparation.legacyPreparation.items[0]), milk),
            originalCatalogues[1],
        )
        // This reuses only synthetic candidate data; Recommendations carries no address/time freshness.
        assertSame("unchanged-row catalogue object can be reused as data only", originalCatalogues[1], freshCatalogues[1])
        assertNull("edited-row query cannot reuse the old ordered query snapshot",
            GroceryIntentContextBasket.create(freshPreparation, originalCatalogues))
        val freshBasket = requireNotNull(GroceryIntentContextBasket.create(freshPreparation, freshCatalogues))
        val freshRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            freshBasket, mapOf(0 to milk, 1 to curd), emptySet(),
        ))

        assertEquals(originalRequest.requestedItems, freshRequest.requestedItems)
        assertNotEquals("same candidate data cannot carry request identity across an edit", originalRequest, freshRequest)
        assertEquals(listOf("2 Amul milk", "3 curd"), freshRequest.sourceItems.map { it.rawText })
    }

    @Test
    fun explicitCountEditRecompilesPendingWitnessAndProposalBinding() {
        val original = "For the meeting get 2 milk"
        val context = listOf("background" to "For the meeting get")
        val originalPreparation = prepared(original, listOf("2 milk" to 2), context)
        val milk = candidate("Milk 500 ml", "milk-spin", "milk-sku")
        val catalogue = recommendations("milk", milk)
        val originalBasket = requireNotNull(GroceryIntentContextBasket.create(originalPreparation, listOf(catalogue)))
        val originalRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(originalBasket, mapOf(0 to milk), emptySet()))
        assertEquals(setOf(0), originalPreparation.pendingCounts.keys)
        val oldWitness = requireNotNull(originalPreparation.pendingCounts[0])
        val oldBinding = originalBasket.binding(0, milk)!!
        assertEquals(2, oldWitness.requestedCount)
        assertEquals(2, oldBinding.quantity)
        assertEquals("2 milk", oldBinding.source)

        val base = GroceryIntentRequestRevision.begin(original)
        val start = original.indexOf("2 milk")
        val revised = requireNotNull(base.replace(base,
            GroceryIntentReviewV2Quote("2", start, start + 1), "3"))
        assertEquals("For the meeting get 3 milk", revised.instruction)
        val freshPreparation = prepared(revised.instruction, listOf("3 milk" to 3), context)
        assertEquals(setOf(0), freshPreparation.pendingCounts.keys)
        val freshWitness = requireNotNull(freshPreparation.pendingCounts[0])
        assertFalse("old source witness must not match revised instruction/row",
            oldWitness.matches(revised.instruction, 0, freshPreparation.legacyPreparation.items.single()))
        assertEquals(3, freshWitness.requestedCount)

        val freshBasket = requireNotNull(GroceryIntentContextBasket.create(freshPreparation, listOf(catalogue)))
        val freshRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(freshBasket, mapOf(0 to milk), emptySet()))
        val freshBinding = requireNotNull(freshBasket.binding(0, milk))
        assertEquals(3, freshBasket.preview(0, milk)?.quantity)
        assertEquals(3, freshRequest.requestedItems.single().quantity)
        assertEquals(3, freshBinding.quantity)
        assertEquals("3 milk", freshBinding.source)
        assertNotEquals("old proposal binding is not reusable", oldBinding, freshBinding)
        assertNotEquals("old review request is not reusable", originalRequest, freshRequest)
    }

    @Test
    fun dietAndConditionEditsAreReassessedAgainstFreshSourceAndCatalogue() {
        val original = "2 milk"
        val oldPreparation = prepared(original, listOf(original to 2))
        val plainMilk = candidate("Milk 500 ml", "milk-spin", "milk-sku")
        val oldBasket = requireNotNull(GroceryIntentContextBasket.create(oldPreparation,
            listOf(recommendations("milk", plainMilk))))
        val oldRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(oldBasket, mapOf(0 to plainMilk), emptySet()))

        val dietBase = GroceryIntentRequestRevision.begin(original)
        val dietEdited = requireNotNull(dietBase.replace(dietBase,
            GroceryIntentReviewV2Quote(original, 0, original.length), "2 low fat milk"))
        val dietSource = "2 low fat milk"
        val dietResponse = GroceryIntentContextResponse.parse(response(dietEdited.instruction, listOf(dietSource to 2),
            names = mapOf(dietSource to "milk"), required = mapOf(dietSource to listOf("low_fat"))), dietEdited.instruction)
        val dietAssessment = dietResponse.assessForReview()
        assertTrue("diet edit must pass the complete current validator: $dietAssessment",
            dietAssessment is GroceryIntentContextAssessment.Prepared)
        val dietPreparation = (dietAssessment as GroceryIntentContextAssessment.Prepared).preparation
        assertTrue("diet edit must pass the complete current validator: ${dietPreparation.blockingIssues}",
            dietPreparation.blockingIssues.isEmpty())
        assertEquals(listOf("low_fat"), dietResponse.validatedDraftSnapshot().items.single().required)
        assertTrue(dietPreparation.legacyPreparation.items.single().query.contains("low fat"))
        assertEquals(dietSource, dietPreparation.itemSources.single().text)

        val lowFatMilk = candidate("Low Fat Milk 500 ml", "milk-low-fat-spin", "milk-low-fat-sku")
        val freshCatalogue = recommendations(swiggyRecommendationQuery(dietPreparation.legacyPreparation.items.single()), lowFatMilk)
        val compatibleFreshBasket = requireNotNull(GroceryIntentContextBasket.create(dietPreparation,
            listOf(freshCatalogue)))
        val compatibleFreshRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            compatibleFreshBasket, mapOf(0 to lowFatMilk), emptySet(),
        ))
        assertTrue(compatibleFreshBasket.usable(0).contains(lowFatMilk))
        assertEquals(oldRequest.requestedItems.single().quantity, compatibleFreshRequest.requestedItems.single().quantity)
        assertNotEquals("the old plan identity cannot survive a new dietary constraint", oldRequest, compatibleFreshRequest)

        val contradictingMilk = plainMilk
        val contradictingBasket = requireNotNull(GroceryIntentContextBasket.create(dietPreparation,
            listOf(recommendations(swiggyRecommendationQuery(dietPreparation.legacyPreparation.items.single()), contradictingMilk))))
        assertTrue(contradictingBasket.usable(0).isEmpty())
        assertTrue(contradictingBasket.resolution(0, contradictingMilk) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.buildContext(contradictingBasket, mapOf(0 to contradictingMilk), emptySet()))

        val sharedInstruction = "2 milk and 3 yogurt, both low fat"
        val sharedRows = listOf("2 milk" to 2, "3 yogurt" to 3)
        val sharedRequired = mapOf("2 milk" to listOf("low_fat")) // Deliberately omitted from the second row.
        val sharedResponse = GroceryIntentContextResponse.parse(response(sharedInstruction, sharedRows,
            names = mapOf("2 milk" to "milk", "3 yogurt" to "yogurt"), required = sharedRequired), sharedInstruction)
        val sharedAssessment = sharedResponse.assessForReview()
        val sharedIssues = when (sharedAssessment) {
            is GroceryIntentContextAssessment.Held -> sharedAssessment.blockingIssues
            is GroceryIntentContextAssessment.Prepared -> sharedAssessment.preparation.blockingIssues
        }
        assertTrue("a shared requirement omitted from one row must remain held: $sharedIssues", sharedIssues.isNotEmpty())
        assertEquals(listOf(listOf("low_fat"), emptyList()),
            sharedResponse.validatedDraftSnapshot().items.map { it.required })
        if (sharedAssessment is GroceryIntentContextAssessment.Prepared) {
            assertNull(GroceryIntentContextBasket.create(sharedAssessment.preparation, catalogues(
                sharedAssessment.preparation,
                mapOf("2 milk" to lowFatMilk, "3 yogurt" to candidate("Low Fat Yogurt 500 g", "yogurt-low-fat")),
            )))
        }

        val conditionalBase = GroceryIntentRequestRevision.begin(original)
        val conditionalEdit = requireNotNull(conditionalBase.replace(conditionalBase,
            GroceryIntentReviewV2Quote(original, 0, original.length), "2 milk if available"))
        val conditional = GroceryIntentContextResponse.parse(
            response(conditionalEdit.instruction, listOf(original to 2),
                context = listOf("unresolved" to "if available")), conditionalEdit.instruction,
        ).assessForReview()
        assertTrue(conditional is GroceryIntentContextAssessment.Prepared)
        val conditionalPreparation = (conditional as GroceryIntentContextAssessment.Prepared).preparation
        assertTrue(conditionalPreparation.blockingIssues.any {
            it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT
        })
        assertNull("a freshly assessed unsupported condition cannot reuse the old basket",
            GroceryIntentContextBasket.create(conditionalPreparation, listOf(recommendations("milk", plainMilk))))
    }

    @Test
    fun rowReorderRebindsSourceOwnershipAndChangesRequestIdentity() {
        val original = "For the meeting get 2 milk, 3 curd"
        val context = listOf("background" to "For the meeting get")
        val originalPreparation = prepared(original, listOf("2 milk" to 2, "3 curd" to 3), context)
        val milk = candidate("Milk 500 ml", "milk-spin", "milk-sku")
        val curd = candidate("Curd 400 g", "curd-spin", "curd-sku")
        assertEquals(setOf(0), originalPreparation.pendingCounts.keys)
        val originalCatalogues = catalogues(originalPreparation, mapOf("2 milk" to milk, "3 curd" to curd))
        val originalBasket = requireNotNull(GroceryIntentContextBasket.create(originalPreparation,
            originalCatalogues))
        val originalRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            originalBasket, mapOf(0 to milk, 1 to curd), emptySet(),
        ))
        val oldMilkWitness = requireNotNull(originalPreparation.pendingCounts[0])

        val base = GroceryIntentRequestRevision.begin(original)
        val reorderedText = "For the meeting get 3 curd, 2 milk"
        val reordered = requireNotNull(base.replace(base,
            GroceryIntentReviewV2Quote(original, 0, original.length), reorderedText))
        val freshPreparation = prepared(reordered.instruction, listOf("3 curd" to 3, "2 milk" to 2), context)
        assertNull("old row-ordered catalogue snapshot cannot be adopted after rows move",
            GroceryIntentContextBasket.create(freshPreparation, originalCatalogues))
        val reorderedCatalogues = listOf(originalCatalogues[1], originalCatalogues[0])
        val freshBasket = requireNotNull(GroceryIntentContextBasket.create(freshPreparation,
            reorderedCatalogues))

        assertEquals(listOf("3 curd", "2 milk"), freshPreparation.itemSources.map { it.text })
        assertFalse("the milk witness moved rows and cannot be reused",
            oldMilkWitness.matches(reordered.instruction, 1, freshBasket.items[1]))
        assertEquals(setOf(0), freshPreparation.pendingCounts.keys)
        assertEquals("3 curd", freshPreparation.pendingCounts.getValue(0).source.text)
        val freshRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            freshBasket, mapOf(0 to curd, 1 to milk), emptySet(),
        ))
        assertEquals(mapOf("curd-spin" to 3, "milk-spin" to 2),
            freshRequest.requestedItems.associate { it.spinId to it.quantity })
        assertEquals("3 curd", freshRequest.quantityProposals.single { it.rowIndex == 0 }.source)
        freshRequest.quantityProposals.forEach { proposal ->
            assertEquals(freshPreparation.itemSources[proposal.rowIndex].text, proposal.source)
        }
        assertNotEquals("row order and current source invalidate the old request identity", originalRequest, freshRequest)
    }

    @Test
    fun unquantifiedGarlicAndRoastedChanaKeepDefaultSourceAmountInBasketRequest() {
        // Item vectors mirror exposed DEV rows nov02-basic-012 and nov02-basic-020;
        // narration/context wording is intentionally not treated as item evidence.
        listOf(
            Triple(", get garlic.", "garlic", "garlic"),
            Triple(", get garlic.", "get garlic", "garlic"),
            Triple(" add roasted chana; .", "roasted chana", "roasted chana"),
            Triple(" add roasted chana; .", "add roasted chana", "roasted chana"),
        ).forEachIndexed { index, (instruction, source, name) ->
            val preparation = prepared(instruction, listOf(source to null), names = mapOf(source to name))
            val item = preparation.legacyPreparation.items.single()
            assertTrue("an omitted source amount must remain Default", item.quantity is com.example.beta.automation.Quantity.Default)
            assertFalse("no source quantity proof may be fabricated", item.requiresIntentQuantityProof)
            assertNull(item.intentQuantityProvenance)

            // The one selected SKU is a review-plan unit, not a source count claim.
            val offer = candidate("$name 1 pack", "default-$index")
            val basket = requireNotNull(GroceryIntentContextBasket.create(preparation,
                listOf(recommendations(swiggyRecommendationQuery(item), offer))))
            val request = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to offer), emptySet()))
            assertTrue(request.sourceItems.single().quantity is com.example.beta.automation.Quantity.Default)
            assertEquals(1, request.requestedItems.single().quantity)
            assertTrue("unspecified source rows do not gain quantity proposals", request.quantityProposals.isEmpty())
        }
    }

    @Test
    fun one250GramCheesePacketSelectsOneMatchingSkuAndRejectsSizeOrMultipackMismatch() {
        // Preserve nov02-basic-027's MODEL count unit, not the gold's pack unit.
        // The source quote must prove the outer-packet meaning without repairing the model row.
        val source = "one 250 g cheese slices packet"
        val ginger = "ginger"
        val instruction = ", get one 250 g cheese slices packet and ginger."
        val packetDraft = GroceryIntentContextResponse.parse(response(instruction, listOf(source to 1, ginger to null),
            names = mapOf(source to "cheese slices"),
            packSizes = mapOf(source to (250 to "g"))), instruction).validatedDraftSnapshot()
        assertEquals(250.0, packetDraft.items.first().packValue?.toDouble() ?: -1.0, 0.0)
        val preparation = prepared(instruction, listOf(source to 1, ginger to null),
            names = mapOf(source to "cheese slices"), packSizes = mapOf(source to (250 to "g")))
        val item = preparation.legacyPreparation.items.first()
        assertEquals(2, preparation.legacyPreparation.items.size)
        assertEquals(1, (preparation.legacyPreparation.items[0].quantity as com.example.beta.automation.Quantity.Count).n)
        assertTrue(preparation.legacyPreparation.items[1].quantity is com.example.beta.automation.Quantity.Default)
        val correct = candidate("Cheese Slices 250 g", "cheese-250")
        val gingerCandidate = candidate("Ginger 1 pack", "ginger-default")
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation,
            listOf(recommendations(swiggyRecommendationQuery(item), correct),
                recommendations(swiggyRecommendationQuery(preparation.legacyPreparation.items[1]), gingerCandidate))))
        val resolution = basket.resolution(0, correct)
        assertTrue("the source count is one outer packet, not 250 units: $resolution",
            resolution !is SwiggyCartQuantityResolution.Held)
        val request = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to correct, 1 to gingerCandidate), emptySet()))
        assertEquals(mapOf("cheese-250" to 1, "ginger-default" to 1), request.requestedItems.associate { it.spinId to it.quantity })
        assertEquals(source, request.sourceItems.first().rawText)

        val wrongOffers = listOf(
            candidate("Cheese Slices 500 g", "cheese-wrong-size"),
            candidate("Cheese Slices 2 x 250 g", "cheese-multipack"),
            candidate("Cheese Slices 250 g", "cheese-wrong-pack", variant = "2 pack"),
        )
        wrongOffers.forEach { wrong ->
            val wrongBasket = requireNotNull(GroceryIntentContextBasket.create(preparation,
                listOf(recommendations(swiggyRecommendationQuery(item), wrong),
                    recommendations(swiggyRecommendationQuery(preparation.legacyPreparation.items[1]), gingerCandidate))))
            assertTrue("mismatched pack offer must stay held", wrongBasket.resolution(0, wrong) is SwiggyCartQuantityResolution.Held)
            assertNull(SwiggyCartReviewRequest.buildContext(wrongBasket, mapOf(0 to wrong, 1 to gingerCandidate), emptySet()))
        }
    }

    @Test
    fun lactoseFreeSourceNameConstrainsOffersWhileUnspecifiedNeighborRowsStayDefault() {
        // Item vectors mirror nov02-basic-066. This is hypothetical catalogue data,
        // not a claim about actual Swiggy stock or successful discovery.
        val milk = "500 g lactose-free milk"
        val oranges = "oranges"
        val chilli = "one 250 g red chilli powder packet"
        val cucumber = "cucumber"
        val instruction = " $milk, $oranges, $chilli and $cucumber chahiye, note kar lo."
        val context = listOf("background" to "chahiye, note kar lo")
        val preparation = prepared(instruction,
            listOf(milk to 500, oranges to null, chilli to 1, cucumber to null),
            context = context,
            names = mapOf(milk to "lactose-free milk", oranges to "oranges",
                chilli to "red chilli powder", cucumber to "cucumber"),
            quantityUnits = mapOf(milk to "g"),
            packSizes = mapOf(chilli to (250 to "g")))
        val draft = GroceryIntentContextResponse.parse(response(instruction,
            listOf(milk to 500, oranges to null, chilli to 1, cucumber to null),
            context = context,
            names = mapOf(milk to "lactose-free milk", oranges to "oranges",
                chilli to "red chilli powder", cucumber to "cucumber"),
            quantityUnits = mapOf(milk to "g"),
            packSizes = mapOf(chilli to (250 to "g"))), instruction).validatedDraftSnapshot()
        val sourceDraft = preparation.legacyPreparation.items
        assertEquals(emptyList<String>(), draft.items.first().required)
        assertTrue(draft.items.first().name.contains("lactose-free"))
        assertTrue("source keeps the lactose-free trait despite required=[]", sourceDraft.first().rawText.contains("lactose-free"))
        assertTrue(sourceDraft[1].quantity is com.example.beta.automation.Quantity.Default)
        assertTrue(sourceDraft[3].quantity is com.example.beta.automation.Quantity.Default)
        assertEquals(4, sourceDraft.size)

        val lactoseFree = candidate("Lactose-Free Milk 500 g", "milk-lactose-free")
        val orangeOffer = candidate("Oranges 1 pack", "oranges")
        val chilliOffer = candidate("Red Chilli Powder 250 g", "chilli-250")
        val cucumberOffer = candidate("Cucumber 1 pack", "cucumber")
        val selected = mapOf(0 to lactoseFree, 1 to orangeOffer, 2 to chilliOffer, 3 to cucumberOffer)
        val positiveBasket = requireNotNull(GroceryIntentContextBasket.create(preparation,
            catalogues(preparation, mapOf(milk to lactoseFree, oranges to orangeOffer,
                chilli to chilliOffer, cucumber to cucumberOffer))))
        assertTrue(positiveBasket.usable(0).contains(lactoseFree))
        val positiveRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(
            positiveBasket, selected, emptySet(),
        ))
        assertEquals(mapOf("milk-lactose-free" to 1, "oranges" to 1, "chilli-250" to 1, "cucumber" to 1),
            positiveRequest.requestedItems.associate { it.spinId to it.quantity })

        listOf(
            candidate("Milk 500 g", "milk-ordinary"),
            candidate("Low Lactose Milk 500 g", "milk-low-lactose"),
            candidate("Lactose-Free Milk 500 ml", "milk-wrong-dimension"),
        ).forEach { incompatible ->
            val rejectedBasket = requireNotNull(GroceryIntentContextBasket.create(preparation,
                catalogues(preparation, mapOf(milk to incompatible, oranges to orangeOffer,
                    chilli to chilliOffer, cucumber to cucumberOffer))))
            assertTrue("ordinary, low-lactose, or wrong-dimension milk must not match", rejectedBasket.usable(0).isEmpty())
            assertTrue(rejectedBasket.resolution(0, incompatible) is SwiggyCartQuantityResolution.Held)
            assertNull(SwiggyCartReviewRequest.buildContext(
                rejectedBasket, selected + (0 to incompatible), emptySet(),
            ))
        }
    }

    private fun assertNotNullBasket(preparation: GroceryIntentContextPreparation, catalogues: List<Recommendations>) {
        assertTrue(GroceryIntentContextBasket.create(preparation, catalogues) != null)
    }

    private fun prepared(
        instruction: String,
        rows: List<Pair<String, Int?>>,
        context: List<Pair<String, String>> = emptyList(),
        names: Map<String, String> = emptyMap(),
        excluded: Map<String, List<String>> = emptyMap(),
        quantityUnits: Map<String, String> = emptyMap(),
        packSizes: Map<String, Pair<Int, String>> = emptyMap(),
    ): GroceryIntentContextPreparation {
        val assessment = GroceryIntentContextResponse.parse(
            response(instruction, rows, context = context, names = names, excluded = excluded,
                quantityUnits = quantityUnits, packSizes = packSizes), instruction,
        ).assessForReview()
        assertTrue("expected a fully prepared current interpretation: $assessment",
            assessment is GroceryIntentContextAssessment.Prepared)
        return (assessment as GroceryIntentContextAssessment.Prepared).preparation
    }

    private fun preparation(instruction: String, rows: List<Pair<String, Int?>>) =
        GroceryIntentContextResponse.parse(response(instruction, rows), instruction).prepareForReview()

    private fun catalogues(
        preparation: GroceryIntentContextPreparation,
        candidatesBySource: Map<String, RecommendationCandidate>,
    ): List<Recommendations> = preparation.legacyPreparation.items.map { item ->
        recommendations(swiggyRecommendationQuery(item), requireNotNull(candidatesBySource[item.rawText]))
    }

    private fun response(
        instruction: String,
        rows: List<Pair<String, Int?>>,
        context: List<Pair<String, String>> = emptyList(),
        names: Map<String, String> = emptyMap(),
        required: Map<String, List<String>> = emptyMap(),
        excluded: Map<String, List<String>> = emptyMap(),
        quantityUnits: Map<String, String> = emptyMap(),
        packSizes: Map<String, Pair<Int, String>> = emptyMap(),
    ): String {
        val itemJson = rows.joinToString(",") { (source, quantity) ->
            val unit = quantityUnits[source] ?: if (quantity == null) "unspecified" else "count"
            val quantityJson = quantity?.toString() ?: "null"
            val pack = packSizes[source]
            val packJson = pack?.first?.toString() ?: "null"
            val packUnit = pack?.second ?: "unspecified"
            val name = names[source] ?: source.substringAfter(' ').trim()
            val requiredValues = required[source].orEmpty().joinToString(",", transform = ::json)
            val excludedValues = excluded[source].orEmpty().joinToString(",", transform = ::json)
            """{"name":${json(name)},"quantity":$quantityJson,"quantity_unit":${json(unit)},"pack_value":$packJson,"pack_unit":${json(packUnit)},"required":[$requiredValues],"excluded":[$excludedValues],"source_text":${json(source)},"needs_clarification":false}"""
        }
        val entries = context.joinToString(",") { (kind, quote) ->
            val start = instruction.indexOf(quote)
            """{"kind":${json(kind)},"source":{"text":${json(quote)},"start_utf16":$start,"end_utf16":${start + quote.length}}}"""
        }
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1-context","requires_review":true,"instruction":${json(instruction)},"items":[$itemJson],"model_needs_clarification":false,"context":[$entries]},"model":"test","promptVersion":"context-v1","latencyMs":1,"usage":{}}"""
    }

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun candidate(label: String, spin: String, sku: String = spin, variant: String? = null) =
        RecommendationCandidate(spinId = spin, skuId = sku, label = label, variant = variant)

    private fun recommendations(query: String, candidate: RecommendationCandidate) =
        Recommendations(candidates = listOf(candidate), suggested = candidate, query = query)
}
