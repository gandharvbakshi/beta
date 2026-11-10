package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentContainerCatalogueSafetyTest {
    @Test fun unqualifiedCountDoesNotProveTheContentsOfAWeightOnlyRetailUnit() {
        for (name in listOf("carrot", "pear", "guava")) {
            val source = "one $name"
            val item = prepareReadyContainerItem(
                source = source, name = name, packaging = null,
                quantity = 1, quantityUnit = "count", quantityText = "one",
            )
            val candidate = RecommendationCandidate("weight-only-$name", "$name 500 g")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
            assertFalse(name, isSwiggyCandidateCountCompatible(checked, candidate))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checked, candidate)
            }
        }
    }

    @Test fun unqualifiedCountAndExplicitPieceBothNeedCatalogueEvidence() {
        val count = prepareReadyContainerItem(
            source = "one carrot", name = "carrot", packaging = null,
            quantity = 1, quantityUnit = "count", quantityText = "one",
        )
        val piece = prepareReadyContainerItem(
            source = "one carrot", name = "carrot", packaging = null,
            quantity = 1, quantityUnit = "piece", quantityText = "one",
        )

        assertEquals(Quantity.Count(1), count.quantity)
        assertEquals(Quantity.Count(1), piece.quantity)
        assertFalse(count.retailPackCount)
        assertFalse(piece.retailPackCount)
        assertFalse(count.logicalPieceCount)
        assertTrue(piece.logicalPieceCount)

        val plainSingleton = RecommendationCandidate("carrot-single", "Carrot")
        val checkedCount = assessSwiggyIntentQuantity(count, Recommendations(candidates = listOf(plainSingleton)))
        val checkedPiece = assessSwiggyIntentQuantity(piece, Recommendations(candidates = listOf(plainSingleton)))
        assertTrue(isSwiggyCandidateAllowed(checkedCount, plainSingleton))
        assertTrue(isSwiggyCandidateAllowed(checkedPiece, plainSingleton))
        assertFalse(isSwiggyCandidateCountCompatible(checkedCount, plainSingleton))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(checkedCount, plainSingleton)
        }
        assertFalse(isSwiggyCandidateCountCompatible(checkedPiece, plainSingleton))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(checkedPiece, plainSingleton)
        }

        val explicitSinglePiece = RecommendationCandidate("carrot-one-piece", "Carrot 1 piece")
        for (item in listOf(count, piece)) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(explicitSinglePiece)))
            assertTrue(isSwiggyCandidateAllowed(checked, explicitSinglePiece))
            if (item.logicalPieceCount) {
                assertTrue(isSwiggyCandidateCountCompatible(checked, explicitSinglePiece))
                assertEquals(1, swiggyRequestedCartQuantity(checked, explicitSinglePiece))
            } else assertUnqualifiedCountNeedsReview(checked, explicitSinglePiece, 1)
        }

        val explicitTwoPieceBundle = RecommendationCandidate("carrot-two-pieces", "Carrot 2 pieces")
        for (item in listOf(count, piece)) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(explicitTwoPieceBundle)))
            assertTrue(isSwiggyCandidateAllowed(checked, explicitTwoPieceBundle))
            assertFalse(isSwiggyCandidateCountCompatible(checked, explicitTwoPieceBundle))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checked, explicitTwoPieceBundle)
            }
        }
    }

    @Test fun legacyUnquantifiedEggsStillOfferOneRetailPack() {
        val item = prepareSwiggyMcpItems("eggs", lookup = { null }).single()
        assertEquals(Quantity.Default, item.quantity)
        val candidate = RecommendationCandidate("eggs", "Farm Fresh 6 Eggs")
        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(1, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test fun legacyEggCountsWithWeightRangesCannotInferCountRole() {
        val item = ParsedItem("6 eggs", "eggs", Quantity.Count(6))
        val candidate = RecommendationCandidate("eggs", "Farm Fresh Eggs 6 pcs, 330-360 g")
        assertUnqualifiedCountNeedsReview(item, candidate, 6)
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(item.copy(logicalPieceCount = true), candidate)
        }
    }

    @Test fun ingredientPiecesCannotBecomeRetailPackSize() {
        val source = "one pack of chocolate with two pieces of walnut"
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = GroceryIntentReviewV2Item("chocolate with walnut", BigDecimal.ONE, "pack",
                BigDecimal(2), "piece", "pack", emptyList(), emptyList(), source, emptyList(), false, "one", "two"),
            evidence = GroceryIntentReviewV2Evidence(sourceQuote(source, source), emptyList()),
            quantityOrigin = "stated", amountRoleStatus = "matched",
        )
        assertEquals("discrete_pack_role_unproven", row.groundingIssue())
    }

    @Test fun bottleBrushRetainsProductAndContainerAndRejectsUnrequestedMultipack() {
        val item = prepareReadyContainerItem(
            source = "one bottle brush",
            name = "brush",
            packaging = "bottle",
        )
        assertEquals("brush bottle", item.query)
        assertEquals("brush bottle", item.strictMatchPhrase)
        assertTrue(item.query.contains("brush"))
        assertTrue(item.query.contains("bottle"))

        val single = RecommendationCandidate("brush-single", "Bottle Brush")
        val checkedTitleOnly = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(single)))
        assertTrue(isSwiggyCandidateAllowed(checkedTitleOnly, single))
        assertTitleOnlyCandidateHeld(checkedTitleOnly, single)
        assertUnqualifiedCountNeedsReview(checkedTitleOnly, single, 1)
        val typedSingle = withSyntheticCatalogueQuantity(single, "1 piece")
        val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(typedSingle)))
        assertReviewedRetailProposal(checkedSingle, typedSingle, 1)

        listOf(
            RecommendationCandidate("brush-three-pack", "Bottle Brush Pack of 3"),
            RecommendationCandidate("brush-three-pcs", "Bottle Brush 3 pcs"),
            RecommendationCandidate("brush-three-pieces", "Bottle Brush 3 pieces"),
            RecommendationCandidate("brush-three-pc", "Bottle Brush 3 pc"),
            RecommendationCandidate("brush-zero-pcs", "Bottle Brush 0 pcs"),
            RecommendationCandidate("brush-negative-pcs", "Bottle Brush -1 pcs"),
            RecommendationCandidate("brush-fractional-pcs", "Bottle Brush 1.5 pcs"),
            RecommendationCandidate("brush-overflow-pcs", "Bottle Brush 1000 pcs"),
            RecommendationCandidate("brush-conflicting-pcs", "Bottle Brush 3 pcs 4 pcs"),
        ).forEach { triple ->
            val checkedTriple = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(triple)))
            assertTrue(triple.label, isSwiggyCandidateAllowed(checkedTriple, triple))
            assertFalse(triple.label, isSwiggyCandidateCountCompatible(checkedTriple, triple))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checkedTriple, triple)
            }
        }

        val onePiece = RecommendationCandidate("brush-one-pc", "Bottle Brush 1 pc")
        val checkedOnePiece = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(onePiece)))
        assertTrue(isSwiggyCandidateAllowed(checkedOnePiece, onePiece))
        assertUnqualifiedCountNeedsReview(checkedOnePiece, onePiece, 1)
    }

    @Test fun logicalRequestForThreeBottleBrushesConsumesOneVerifiedThreePieceBundle() {
        val item = prepareReadyContainerItem(
            source = "three bottle brush",
            name = "bottle brush",
            packaging = null,
            quantity = 3,
            quantityUnit = "piece",
            quantityText = "three",
        )
        assertTrue(item.logicalPieceCount)
        val bundle = RecommendationCandidate("brush-three-pcs", "Bottle Brush Pack 3 pcs")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(bundle)))
        assertTrue(isSwiggyCandidateAllowed(checked, bundle))
        assertTrue(isSwiggyCandidateCountCompatible(checked, bundle))
        assertEquals(1, swiggyRequestedCartQuantity(checked, bundle))
    }

    @Test fun explicitlyRequestedTwoThreePiecePacksRemainTwoCartUnits() {
        val item = prepareReadyContainerItem(
            source = "two packs of three pieces bottle brush",
            name = "bottle brush",
            packaging = "pack",
            quantity = 2,
            quantityUnit = "pack",
            quantityText = "two",
            packValue = 3,
            packUnit = "piece",
            packText = "three",
        )
        val bundle = RecommendationCandidate("brush-three-pcs", "Bottle Brush Pack 3 pcs")
        val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(bundle)))
        assertTitleOnlyCandidateHeld(item, bundle)
        assertTrue(isSwiggyCandidateAllowed(titleOnlyChecked, bundle))
        val typedBundle = withSyntheticCataloguePackSize(bundle, "3 pieces")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(typedBundle)))
        assertReviewedRetailProposal(checked, typedBundle, 2)
        assertFalse(swiggyCountResolutionDiagnostic(checked, typedBundle),
            resolveSwiggyCartQuantity(checked, typedBundle) is SwiggyCartQuantityResolution.Exact)

        val explicitRelation = withSyntheticCatalogueRelation(
            RecommendationCandidate("brush-explicit-three", "Bottle Brush Pack of 3 Pieces"),
            "Pack of 3 Pieces",
        )
        val explicitChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(explicitRelation)))
        assertTrue(swiggyCountResolutionDiagnostic(explicitChecked, explicitRelation),
            resolveSwiggyCartQuantity(explicitChecked, explicitRelation) is SwiggyCartQuantityResolution.Exact)
        assertEquals(2, swiggyRequestedCartQuantity(explicitChecked, explicitRelation))
        listOf("Bottle Brush Pack 3 pcs x 3", "Bottle Brush Pack 3 pcs x 2",
            "Bottle Brush Pack of 3 3 pcs", "Bottle Brush Pack 3 pcs each, 3 pieces",
            "Bottle Brush Pack 3 boxes 3 pcs", "Bottle Brush Pack 3 sets of 3 pcs",
            "Bottle Brush Pack 3 pcs per bundle, 3 bundles", "Bottle Brush Pack three sets of 3 pcs",
            "Bottle Brush Pack 3 pcs plus 3 pcs", "Bottle Brush Pack 3 pcs and 3 pcs").forEach { label ->
            val nested = RecommendationCandidate("nested", label)
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(nested)))
            assertFalse(label, isSwiggyCandidateCountCompatible(assessed, nested))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessed, nested) }
            val typedNested = withSyntheticCataloguePackSize(nested, "3 pieces")
            val typedAssessed = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(typedNested)))
            assertTrue(label, resolveSwiggyCartQuantity(typedAssessed, typedNested) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test fun genericOuterPackCountsReviewTypedPieceOffersWithoutInferringExactDivisors() {
        data class Case(
            val source: String,
            val product: String,
            val container: String,
            val outer: Int,
            val inner: Int,
            val innerText: String,
        )
        val cases = listOf(
            Case("two packs of three pieces bottle brush", "bottle brush", "pack", 2, 3, "three"),
            Case("two packets of four pieces almonds", "almonds", "packet", 2, 4, "four"),
            Case("three packs of three pieces cookies", "cookies", "pack", 3, 3, "three"),
            Case("two packets of twenty five pieces tea bags", "tea bags", "packet", 2, 25, "twenty five"),
            Case("two packs of three pieces cream of wheat", "cream of wheat", "pack", 2, 3, "three"),
        )
        val failures = mutableListOf<String>()

        fun assertProposalCollected(item: ParsedItem, candidate: RecommendationCandidate, count: Int, label: String) {
            try {
                assertReviewedRetailProposal(item, candidate, count)
            } catch (failure: AssertionError) {
                failures += "$label ${failure.message}"
            }
        }

        fun assertHeldDirectAndReassessed(item: ParsedItem, candidate: RecommendationCandidate, label: String) {
            val direct = resolveSwiggyCartQuantity(item, candidate)
            if (direct !is SwiggyCartQuantityResolution.Held) failures += "$label direct=${direct::class.simpleName}"
            val reassessed = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
            val checked = resolveSwiggyCartQuantity(reassessed, candidate)
            if (checked !is SwiggyCartQuantityResolution.Held) failures += "$label reassessed=${checked::class.simpleName}"
        }

        cases.forEachIndexed { index, case ->
            val item = try { prepareReadyContainerItem(
                source = case.source,
                name = case.product,
                packaging = case.container,
                quantity = case.outer,
                quantityUnit = "pack",
                quantityText = case.source.substringBefore(' '),
                packValue = case.inner,
                packUnit = "piece",
                packText = case.innerText,
            ) } catch (failure: AssertionError) {
                failures += "case=$index preparation=${failure.message}"
                return@forEachIndexed
            }
            assertEquals("case=$index", Quantity.Count(case.outer), item.quantity)
            assertTrue("case=$index", item.requiresIntentQuantityProof)
            assertTrue("case=$index", item.validContainerContentsProof() != null)
            val title = "BrandA ${case.product} ${case.container} ${case.inner} pieces"
            val titleOnly = RecommendationCandidate("generic-pieces-$index", title)
            assertHeldDirectAndReassessed(item, titleOnly, "case=$index title-only")

            val typed = withSyntheticCataloguePackSize(titleOnly, "${case.inner} pieces")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(typed)))
            assertProposalCollected(checked, typed, case.outer, "case=$index")
            // Retain newly collected punctuation failures in the diagnostic
            // matrix; source/query projection alone must not hide usability gaps.
            val punctuated = withSyntheticCataloguePackSize(
                RecommendationCandidate("generic-punctuated-$index", "BrandA ${case.product}, ${case.inner} pieces"),
                "${case.inner} pieces",
            )
            assertProposalCollected(assessSwiggyIntentQuantity(item, Recommendations(listOf(punctuated))),
                punctuated, case.outer, "case=$index punctuated-title")
            assertFalse("case=$index typed piece contents must not become Exact",
                resolveSwiggyCartQuantity(checked, typed) is SwiggyCartQuantityResolution.Exact)

            val negativeCandidates = listOf(
                "different-inner" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-different-inner-$index", label = "BrandA ${case.product} ${case.container} ${case.inner + 1} pieces"),
                    "${case.inner + 1} pieces",
                ),
                "wrong-sku-binding" to typed.copy(skuId = "wrong-sku-$index"),
                "title-metadata-conflict" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-title-conflict-$index", label = "BrandA ${case.product} ${case.container} ${case.inner + 1} pieces"),
                    "${case.inner} pieces",
                ),
                "physical-size" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-physical-$index"), "500 g",
                ),
                "wrong-full-relation" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-wrong-relation-$index", label = "BrandA ${case.product} 1 box of ${case.inner} pieces"),
                    "${case.inner} pieces",
                ),
                "qualified" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-qualified-$index", label = "BrandA ${case.product} ${case.container} ${case.inner} pieces each"),
                    "${case.inner} pieces",
                ),
                "additive" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-additive-$index", label = "BrandA ${case.product} ${case.container} ${case.inner} pieces + ${case.inner} pieces"),
                    "${case.inner} pieces",
                ),
                "nested" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-nested-$index", label = "BrandA ${case.product} ${case.container} ${case.inner} pieces x 2"),
                    "${case.inner} pieces",
                ),
                "twin-pack" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-twin-$index", label = "BrandA ${case.product} Twin Pack"),
                    "${case.inner} pieces",
                ),
                "combo" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-combo-$index", label = "BrandA ${case.product} Combo Pack"),
                    "${case.inner} pieces",
                ),
                "range" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-range-$index", label = "BrandA ${case.product} ${case.container} ${case.inner}-${case.inner + 1} pieces"),
                    "${case.inner} pieces",
                ),
                "approximate" to withSyntheticCataloguePackSize(
                    titleOnly.copy(spinId = "generic-approx-$index", label = "BrandA ${case.product} ${case.container} about ${case.inner} pieces"),
                    "${case.inner} pieces",
                ),
            )
            negativeCandidates.forEach { (kind, candidate) ->
                assertHeldDirectAndReassessed(item, candidate, "case=$index $kind")
            }

            val mutations = listOf(
                "raw-source" to item.copy(rawText = "${item.rawText}, 1 milk"),
                "outer-count" to item.copy(quantity = Quantity.Count(case.outer + 1)),
                "query" to item.copy(query = "${item.query} organic"),
                "strict-phrase" to item.copy(strictMatchPhrase = "other ${case.container}"),
                "logical-piece-flag" to item.copy(logicalPieceCount = true),
                "retail-pack-flag" to item.copy(retailPackCount = false),
                "increment-mode" to item.copy(cartQuantityMode = com.example.beta.automation.IntentCartQuantityMode.INCREMENT),
                "requested-piece-role" to item.copy(intentQuantityProvenance =
                    requireNotNull(item.intentQuantityProvenance).copy(
                        sourceCountRole = com.example.beta.automation.IntentSourceCountRole.REQUESTED_PIECES)),
                "unproven-scope" to item.copy(unprovenIdentityScope = true),
                "contradictory-avoid-phrase" to item.copy(avoidPhrases = listOf(case.product)),
            )
            mutations.forEach { (kind, mutated) ->
                assertHeldDirectAndReassessed(mutated, typed, "case=$index mutation=$kind")
            }
        }

        val brushSource = "two packs bottle brush"
        val brush = prepareReadyContainerItem(brushSource, "bottle brush", "pack", quantity = 2, quantityText = "two")
        listOf(
            RecommendationCandidate("brush-title-with-pack", "BrandA Bottle Brush Pack"),
            RecommendationCandidate("brush-title-without-pack", "BrandA Bottle Brush"),
        ).forEach { candidate ->
            val typed = withSyntheticCataloguePackSize(candidate, "3 pieces")
            val checked = assessSwiggyIntentQuantity(brush, Recommendations(candidates = listOf(typed)))
            assertProposalCollected(checked, typed, 2, "simple source title=${candidate.spinId}")
        }

        val explicitSourceText = "two packs of three pieces bottle brush"
        val v1ExplicitSource = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = explicitSourceText,
            items = listOf(GroceryIntentItem(
                name = "bottle brush",
                quantity = BigDecimal(2),
                quantityUnit = "pack",
                packValue = BigDecimal(3),
                packUnit = "piece",
                required = emptyList(),
                excluded = emptyList(),
                sourceText = explicitSourceText,
                needsClarification = false,
            )),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()
        val v2ExplicitSource = prepareReadyContainerItem(
            source = explicitSourceText, name = "bottle brush", packaging = "pack",
            quantity = 2, quantityUnit = "pack", quantityText = "two", packValue = 3,
            packUnit = "piece", packText = "three",
        )
        listOf("v1" to v1ExplicitSource, "v2" to v2ExplicitSource).forEach { (route, item) ->
            listOf(
                RecommendationCandidate("same-source-$route-with-pack", "Bottle Brush Pack"),
                RecommendationCandidate("same-source-$route-without-pack", "Bottle Brush"),
            ).forEach { candidate ->
                val typed = withSyntheticCataloguePackSize(candidate, "3 pieces")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(typed)))
                assertProposalCollected(checked, typed, 2, "explicit source route=$route title=${candidate.spinId}")
            }
        }

        listOf(
            Triple("two boxes of three pieces bottle brush", "bottle brush", "box"),
            Triple("two jars of three pieces almonds", "almonds", "jar"),
        ).forEachIndexed { index, (source, product, container) ->
            val namedContainer = try { prepareReadyContainerItem(
                source = source,
                name = product,
                packaging = container,
                quantity = 2,
                quantityUnit = "pack",
                quantityText = "two",
                packValue = 3,
                packUnit = "piece",
                packText = "three",
            ) } catch (failure: AssertionError) {
                failures += "named container case=$index preparation=${failure.message}"
                return@forEachIndexed
            }
            assertTrue("named container case=$index", namedContainer.validContainerContentsProof() != null)
            val typedPieces = withSyntheticCataloguePackSize(
                RecommendationCandidate("named-container-pieces-$index", "BrandA $product ${container.replaceFirstChar { it.uppercase() }}"),
                "3 pieces",
            )
            assertHeldDirectAndReassessed(namedContainer, typedPieces, "named container case=$index pure pieces")
        }

        val splitRelation = RecommendationCandidate("brush-split-quantity", "BrandA Bottle Brush Pack", skuId = "brush-split-sku",
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1, source = "search_variation", spinId = "brush-split-quantity", skuId = "brush-split-sku",
                fields = CatalogueQuantityFields(variantName = "Pack of 2", subTitle = "x2", quantityDescription = "8 pieces"),
            ))
        assertHeldDirectAndReassessed(v2ExplicitSource, splitRelation, "split-subtitle-and-competing-quantity")

        val exactExplicitRelation = withSyntheticCatalogueRelation(
            RecommendationCandidate("brush-explicit-source-relation", "Bottle Brush Pack of 3 Pieces"),
            "Pack of 3 Pieces",
        )
        val exactChecked = assessSwiggyIntentQuantity(v2ExplicitSource, Recommendations(listOf(exactExplicitRelation)))
        assertTrue(swiggyCountResolutionDiagnostic(exactChecked, exactExplicitRelation),
            resolveSwiggyCartQuantity(exactChecked, exactExplicitRelation) is SwiggyCartQuantityResolution.Exact)
        assertEquals(2, swiggyRequestedCartQuantity(exactChecked, exactExplicitRelation))

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun fullNominalComparisonRetainsUnitWordsInsideTheProduct() {
        val source = "two packs piece cake, three pieces"
        val inner = source.indexOf("three")
        assertFalse(sourceContainerContentsRelation(source, 0, 3, inner, inner + 5, literalIdentity = "cake"))
        assertTrue(sourceContainerContentsRelation(source, 0, 3, inner, inner + 5, literalIdentity = "piece cake"))
    }

    @Test fun typedPieceFormattingMatrixRetainsStrictPositiveAndNegativeBoundaries() {
        data class Case(val source: String, val product: String, val container: String)
        val cases = listOf(
            Case("two packs of three pieces bottle brush", "bottle brush", "pack"),
            Case("two packs of three pieces cookies", "cookies", "pack"),
            Case("two packets of three pieces can opener", "can opener", "packet"),
        )
        val positiveSeparators = listOf(" " to "plain", ", " to "comma", " , " to "space-before-comma", ": " to "colon",
            " (" to "parentheses")
        val negativeTails = listOf(
            " -3 pieces" to "negative-sign",
            " +3 pieces" to "positive-sign",
            " - 3 pieces" to "spaced-negative-sign",
            " + 3 pieces" to "spaced-positive-sign",
            " 1, 3 pieces" to "numeric-prefix-before-comma",
            " 1,000 pieces" to "comma-grouped-large",
            " 1.5 pieces" to "fractional-size",
            " 3 pieces / 4 pieces" to "conflicting-sizes",
            " about 3 pieces" to "approximate-size",
            " 3 pieces and 2 packs" to "conjunction-nested-offer",
            ", Sponge, 3 pieces" to "competing-comma-product",
            ",3 pieces" to "no-space-comma",
            ", three pieces" to "word-number-comma",
        )
        val failures = mutableListOf<String>()
        var offerIndex = 2240

        fun held(item: ParsedItem, candidate: RecommendationCandidate, label: String) {
            val direct = resolveSwiggyCartQuantity(item, candidate)
            if (direct !is SwiggyCartQuantityResolution.Held) failures += "$label direct=$direct"
            val reassessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val checked = resolveSwiggyCartQuantity(reassessed, candidate)
            if (checked !is SwiggyCartQuantityResolution.Held) failures += "$label reassessed=$checked"
        }

        cases.forEachIndexed { caseIndex, case ->
            val attempted = runCatching {
                prepareReadyContainerItem(
                    source = case.source,
                    name = case.product,
                    packaging = case.container,
                    quantity = 2,
                    quantityUnit = "pack",
                    quantityText = "two",
                    packValue = 3,
                    packUnit = "piece",
                    packText = "three",
                )
            }
            if (attempted.isFailure) {
                failures += "case=$caseIndex preparation=${attempted.exceptionOrNull()?.javaClass?.simpleName}"
                return@forEachIndexed
            }
            val item = attempted.getOrThrow()
            val proof = item.validContainerContentsProof()
            if (item.rawText != case.source || item.quantity != Quantity.Count(2) || proof == null ||
                proof.outer.source != case.source || proof.inner.source != case.source ||
                proof.outer.value.compareTo(BigDecimal(2)) != 0 || proof.inner.value.compareTo(BigDecimal(3)) != 0) {
                failures += "case=$caseIndex original outer/inner proof not retained"
                return@forEachIndexed
            }

            positiveSeparators.forEach { (separator, kind) ->
                val title = if (kind == "parentheses") "BrandFmt ${case.product} (3 pieces)"
                    else "BrandFmt ${case.product}${separator}3 pieces"
                val candidate = withSyntheticCataloguePackSize(
                    RecommendationCandidate("fmt-positive-${caseIndex}-${offerIndex++}", title), "3 pieces",
                )
                val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(assessed, candidate)
                if (result !is SwiggyCartQuantityResolution.RetailProposal || result.cartUnits != 2) {
                    failures += "case=$caseIndex positive=$kind result=$result"
                } else {
                    runCatching { assertReviewedRetailProposal(assessed, candidate, 2) }
                        .exceptionOrNull()?.let { failures += "case=$caseIndex positive=$kind review=${it.javaClass.simpleName}" }
                }
                if (result is SwiggyCartQuantityResolution.Exact) failures += "case=$caseIndex positive=$kind unexpectedly Exact"
            }

            negativeTails.forEach { (tail, kind) ->
                val title = "BrandFmt ${case.product}$tail"
                val candidate = withSyntheticCataloguePackSize(
                    RecommendationCandidate("fmt-negative-${caseIndex}-${offerIndex++}", title), "3 pieces",
                )
                held(item, candidate, "case=$caseIndex negative=$kind")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun cataloguePhysicalMeasureAcceptsOneLiteralAndRejectsNumericBoundaryVariants() {
        val valid = withSyntheticCataloguePackSize(
            RecommendationCandidate("physical-valid", "500 g"), "500 g",
        )
        val measure = swiggyCatalogueSourcePhysicalMeasure(valid)
        assertNotNull(measure)
        assertEquals(GroceryPackDimension.WEIGHT, measure?.dimension)
        assertEquals(0, BigDecimal(500).compareTo(measure?.baseValue))

        val negatives = listOf(
            "-500 g" to "signed-negative",
            "+500 g" to "signed-positive",
            "- 500 g" to "spaced-negative",
            "+ 500 g" to "spaced-positive",
            "1,000 g" to "comma-grouped",
            "1, 500 g" to "numeric-prefix-before-comma",
            "500 g, 250 g" to "competing-comma-measures",
            "1.5 g" to "fractional",
        )
        val failures = mutableListOf<String>()
        negatives.forEachIndexed { index, (label, kind) ->
            val candidate = withSyntheticCataloguePackSize(
                RecommendationCandidate("physical-invalid-$index", label), "500 g",
            )
            if (swiggyCatalogueSourcePhysicalMeasure(candidate) != null) {
                failures += "case=$index $kind accepted a source physical measure"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun requestedSourcePiecesDivideOnlyOnCommaSeparatedTypedPieceOffer() {
        val source = "6 pieces cookies"
        val item = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = source,
            items = listOf(GroceryIntentItem(
                name = "cookies",
                quantity = BigDecimal(6),
                quantityUnit = "piece",
                packValue = null,
                packUnit = "unspecified",
                required = emptyList(),
                excluded = emptyList(),
                sourceText = source,
                needsClarification = false,
            )),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()
        assertEquals(Quantity.Count(6), item.quantity)
        assertNotNull(item.validSourcePieceAmount())

        val candidate = withSyntheticCataloguePackSize(
            RecommendationCandidate("cookies-3-pieces-comma", "Cookies, 3 pieces"), "3 pieces",
        )
        assertEquals(3, swiggyCatalogueSourcePieceCount(candidate, "cookies"))
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(assessed, candidate))

        listOf(
            "Cookies, -3 pieces" to "signed-negative",
            "Cookies, +3 pieces" to "signed-positive",
            "Cookies, 1,000 pieces" to "comma-grouped",
            "Cookies,1, 3 pieces" to "numeric-prefix-before-comma",
            "Cookies, 3 pieces, 4 pieces" to "multiple-comma-sizes",
        ).forEachIndexed { index, (label, kind) ->
            val malformed = withSyntheticCataloguePackSize(
                RecommendationCandidate("cookies-malformed-$index", label), "3 pieces",
            )
            assertEquals("$kind catalogue piece count", null, swiggyCatalogueSourcePieceCount(malformed, "cookies"))
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(malformed)))
            assertTrue("$kind final resolution must be held",
                resolveSwiggyCartQuantity(checked, malformed) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test fun v2ContainerProducerPackagingMustMatchTheFirstSourceContainer() {
        data class Case(val source: String, val name: String, val container: String, val wrong: String)
        val cases = listOf(
            Case("two packs of Party Pack cookies", "Party Pack cookies", "pack", "box"),
            Case("two boxes of gift box chocolates", "gift box chocolates", "box", "jar"),
            Case("two jars of jar candles", "jar candles", "jar", "box"),
            Case("two bottles of bottle brush", "bottle brush", "bottle", "jar"),
            Case("two packs of Bottle Party Pack cookies", "Bottle Party Pack cookies", "pack", "bottle"),
            Case("two packets of milk", "milk", "packet", "pack"),
        )
        val failures = mutableListOf<String>()

        fun prepare(case: Case, quantityUnit: String, packaging: String): GroceryIntentPreparation {
            val row = GroceryIntentReviewV2ReadyRow(
                rowIndex = 0,
                item = GroceryIntentReviewV2Item(
                    name = case.name,
                    quantity = BigDecimal(2),
                    quantityUnit = quantityUnit,
                    packValue = null,
                    packUnit = "unspecified",
                    packaging = packaging,
                    required = emptyList(),
                    excluded = emptyList(),
                    sourceText = case.source,
                    supportingText = emptyList(),
                    needsClarification = false,
                    quantityText = "two",
                    packText = null,
                ),
                evidence = GroceryIntentReviewV2Evidence(
                    primary = sourceQuote(case.source, case.source),
                    supporting = emptyList(),
                ),
                quantityOrigin = "stated",
                amountRoleStatus = "matched",
            )
            return GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2",
                requiresReview = true,
                instruction = case.source,
                sourceItemCount = 1,
                readyRows = listOf(row),
                blockedRows = emptyList(),
            ).prepareForReview()
        }

        cases.forEachIndexed { index, case ->
            val normalizedAttempt = runCatching { prepare(case, "pack", case.container) }
            if (normalizedAttempt.isFailure) {
                failures += "case=$index normalized preparation=${normalizedAttempt.exceptionOrNull()?.javaClass?.simpleName}"
            } else {
                val normalized = normalizedAttempt.getOrThrow()
                val item = normalized.items.singleOrNull()
                val proof = item?.intentQuantityProvenance
                val genericPacket = case.container in setOf("pack", "packet")
                val roleValid = if (genericPacket) {
                    val syntax = sourcePacketOfferSyntax(case.source)
                    item != null && proof != null && proof.retailContainer == null && proof.sourceCountRole == null &&
                        syntax != null && item.packetOfferProofMatches(syntax) &&
                        intentEvidenceTokens(item.query).containsAll(intentEvidenceTokens(case.name))
                } else proof != null && proof.retailContainer == case.container && groceryIntentSourceRetailReceiptValid(proof)
                if (item == null || normalized.unresolvedText.isNotEmpty() || item.quantity != Quantity.Count(2) ||
                    !roleValid) {
                    failures += "case=$index correct pack row not prepared with source-bound retail proof"
                }
            }

            for (unit in listOf("count", "pack")) {
                val wrongAttempt = runCatching { prepare(case, unit, case.wrong) }
                if (wrongAttempt.isFailure) {
                    failures += "case=$index unit=$unit contradictory packaging exception=${wrongAttempt.exceptionOrNull()?.javaClass?.simpleName}"
                    continue
                }
                val wrong = wrongAttempt.getOrThrow()
                val recovery = wrong.recoverySnapshot?.toDisplay()
                if (wrong.items.isNotEmpty() || wrong.excludedText != listOf(case.source) ||
                    wrong.unresolvedText.isNotEmpty() || recovery?.originalInstruction != case.source) {
                    failures += "case=$index unit=$unit contradictory packaging items=${wrong.items.size} " +
                        "excluded=${wrong.excludedText.size} unresolved=${wrong.unresolvedText.size} recovery=${recovery != null}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun catalogueBoxRelationsDoNotBypassTypedPieceBoundaryForV1OrV2Almonds() {
        val source = "two packets of four pieces almonds"
        val v1 = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = source,
            items = listOf(GroceryIntentItem(
                name = "almonds",
                quantity = BigDecimal(2),
                quantityUnit = "pack",
                packValue = BigDecimal(4),
                packUnit = "piece",
                required = emptyList(),
                excluded = emptyList(),
                sourceText = source,
                needsClarification = false,
            )),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()
        assertEquals(Quantity.Count(2), v1.quantity)
        assertEquals(BigDecimal(4), requireNotNull(v1.validContainerContentsProof()).inner.value)
        val v2 = prepareReadyContainerItem(
            source = source,
            name = "almonds",
            packaging = "packet",
            quantity = 2,
            quantityUnit = "pack",
            quantityText = "two",
            packValue = 4,
            packUnit = "piece",
            packText = "four",
        )
        assertTrue(v2.validContainerContentsProof() != null)
        assertEquals(Quantity.Count(2), v2.quantity)
        assertEquals(BigDecimal(4), requireNotNull(v2.validContainerContentsProof()).inner.value)

        val titleForms = listOf(
            RecommendationCandidate("almonds-box-of-four", "Almonds Box of 4 Pieces"),
            RecommendationCandidate("almonds-four-in-box", "Almonds 4 Pieces in a Box"),
            RecommendationCandidate("almonds-cup-of-four", "Almonds Cup of 4 Pieces"),
            RecommendationCandidate("almonds-spray-of-four", "Almonds Spray of 4 Pieces"),
            RecommendationCandidate("almonds-basket-of-four", "Almonds Basket of 4 Pieces"),
        )
        listOf("v1" to v1, "v2" to v2).forEach { (route, item) ->
            titleForms.forEach { title ->
                val typed = withSyntheticCataloguePackSize(title, "4 pieces")
                val direct = resolveSwiggyCartQuantity(item, typed)
                assertTrue("$route ${title.spinId} direct=$direct", direct is SwiggyCartQuantityResolution.Held)
                val reassessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(typed)))
                val checked = resolveSwiggyCartQuantity(reassessed, typed)
                assertTrue("$route ${title.spinId} reassessed=$checked", checked is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test fun containerContentsProofCannotBeRewrittenWithBothQueryAndProvenance() {
        val source = "two packs of three pieces almond cookies"
        val original = prepareReadyContainerItem(
            source = source,
            name = "almond cookies",
            packaging = "pack",
            quantity = 2,
            quantityUnit = "pack",
            quantityText = "two",
            packValue = 3,
            packUnit = "piece",
            packText = "three",
        )
        val sourceProof = requireNotNull(original.containerContentsProof)
        val provenance = requireNotNull(original.intentQuantityProvenance)
        val forgedQuery = "cookies 3 pieces"
        val forged = original.copy(
            query = forgedQuery,
            strictMatchPhrase = forgedQuery,
            containerContentsProof = sourceProof.copy(query = forgedQuery),
            intentQuantityProvenance = provenance.copy(query = forgedQuery, strictMatchPhrase = forgedQuery),
        )
        val plainCookies = withSyntheticCataloguePackSize(
            RecommendationCandidate("plain-cookies", "Plain Cookies"), "3 pieces",
        )
        val explicitRelation = withSyntheticCatalogueRelation(
            RecommendationCandidate("plain-cookies-relation", "Plain Cookies Pack of 3 Pieces"), "Pack of 3 Pieces",
        )
        for (candidate in listOf(plainCookies, explicitRelation)) {
            val direct = resolveSwiggyCartQuantity(forged, candidate)
            assertTrue("forged source identity direct=$direct", direct is SwiggyCartQuantityResolution.Held)
            val reassessed = assessSwiggyIntentQuantity(forged, Recommendations(listOf(candidate)))
            val checked = resolveSwiggyCartQuantity(reassessed, candidate)
            assertTrue("forged source identity reassessed=$checked", checked is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test fun honeyJarPackagingSurvivesAndMultipacksDoNotSatisfyOneRequestedJar() {
        val item = prepareReadyContainerItem(
            source = "one jar of honey",
            name = "honey",
            packaging = "jar",
        )
        assertTrue(item.query.contains("honey"))
        assertTrue(item.query.contains("jar"))
        assertTrue(item.strictMatchPhrase.orEmpty().contains("honey"))
        assertTrue(item.strictMatchPhrase.orEmpty().contains("jar"))

        val titleOnly = RecommendationCandidate("honey-jar-title-only", "Honey Jar")
        val checkedTitleOnly = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
        assertTrue(isSwiggyCandidateAllowed(checkedTitleOnly, titleOnly))
        assertTitleOnlyCandidateHeld(checkedTitleOnly, titleOnly)
        assertUnqualifiedCountNeedsReview(checkedTitleOnly, titleOnly, 1)

        val single = withSyntheticCatalogueQuantity(titleOnly, "1 jar")
        val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(single)))
        assertTrue(isSwiggyCandidateAllowed(checkedSingle, single))
        assertTrue(isSwiggyCandidateCountCompatible(checkedSingle, single))
        assertEquals(1, swiggyRequestedCartQuantity(checkedSingle, single))

        listOf(
            RecommendationCandidate("honey-three-jars", "Honey Jar 3x500 g"),
            RecommendationCandidate("honey-pack-of-three", "Honey Jar Pack of 3"),
        ).forEach { multi ->
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(multi)))
            assertTrue(multi.label, isSwiggyCandidateAllowed(checked, multi))
            assertFalse(multi.label, isSwiggyCandidateCountCompatible(checked, multi))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checked, multi)
            }
        }
    }

    @Test fun v2CupAndJarMultipackCountsNeverRoundDownToOneRequestedContainer() {
        data class ContainerCase(
            val name: String,
            val source: String,
            val packaging: String,
            val single: RecommendationCandidate,
            val multipacks: List<RecommendationCandidate>,
            val wireUnits: List<String>,
            val expectedExact: Boolean = false,
        )
        val cases = listOf(
            ContainerCase(
                "yogurt", "one yogurt cup", "cup",
                RecommendationCandidate("yogurt-single-cup", "BrandA Yogurt 150 g Cup"),
                listOf(
                    RecommendationCandidate("yogurt-three-cups", "BrandA Yogurt 3 cups"),
                    RecommendationCandidate("yogurt-cup-x3", "BrandA Yogurt Cups x 3"),
                    RecommendationCandidate("yogurt-nested-cups", "BrandA Yogurt Cups 3 cups x 3"),
                    RecommendationCandidate("yogurt-additive-cups", "BrandA Yogurt 3 cups + 3 cups"),
                    RecommendationCandidate("yogurt-additive-suffix-plus", "BrandA Yogurt Cups x 3 + Cups x 3"),
                    RecommendationCandidate("yogurt-additive-suffix-and", "BrandA Yogurt Cups x 3 and Cups x 3"),
                    RecommendationCandidate("yogurt-pack-three", "BrandA Yogurt Pack of 3"),
                ),
                listOf("count", "pack"),
            ),
            ContainerCase(
                "honey", "one jar of honey", "jar",
                withSyntheticCatalogueQuantity(
                    RecommendationCandidate("honey-single-jar", "BrandA Honey 500 g Jar"), "1 jar",
                ),
                listOf(
                    RecommendationCandidate("honey-three-jars", "BrandA Honey 3 jars"),
                    RecommendationCandidate("honey-jar-x3", "BrandA Honey Jars x 3"),
                    RecommendationCandidate("honey-pack-three", "BrandA Honey Pack of 3"),
                ),
                // Canonical producer normalizes this explicit contents form.
                // Unnormalized count DTOs are held by RetailCountRoleTest.
                listOf("pack"),
                expectedExact = true,
            ),
        )
        val unsafeMultipackAcceptances = mutableListOf<String>()

        cases.forEach { case ->
            case.wireUnits.forEach { sourceUnit ->
                val item = prepareReadyContainerItem(
                    source = case.source,
                    name = case.name,
                    packaging = case.packaging,
                    quantityUnit = sourceUnit,
                )
                assertTrue("${case.name}/$sourceUnit requires V2 proof", item.requiresIntentQuantityProof)
                assertEquals("${case.name}/$sourceUnit retail-pack flag", sourceUnit == "pack", item.retailPackCount)

                val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(case.single)))
                assertTrue(case.single.label, isSwiggyCandidateAllowed(checkedSingle, case.single))
                if (case.expectedExact) {
                    assertTrue(swiggyCountResolutionDiagnostic(checkedSingle, case.single),
                        isSwiggyCandidateCountCompatible(checkedSingle, case.single))
                    assertEquals(case.single.label, 1, swiggyRequestedCartQuantity(checkedSingle, case.single))
                } else {
                    assertReviewedRetailProposal(checkedSingle, case.single, 1)
                }

                (case.multipacks + if (case.packaging == "cup") listOf(
                    RecommendationCandidate("yogurt-zero-cups", "BrandA Yogurt 0 cups"),
                    RecommendationCandidate("yogurt-negative-cups", "BrandA Yogurt -1 cups"),
                    RecommendationCandidate("yogurt-unicode-minus-cups", "BrandA Yogurt −1 cups"),
                    RecommendationCandidate("yogurt-fullwidth-minus-cups", "BrandA Yogurt －1 cups"),
                    RecommendationCandidate("yogurt-plus-cups", "BrandA Yogurt +1 cups"),
                    RecommendationCandidate("yogurt-fraction-cups", "BrandA Yogurt 1.5 cups"),
                ) else emptyList()).forEach { candidate ->
                    val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
                    val caseLabel = "${case.name}/$sourceUnit: ${candidate.label}"
                    if (isSwiggyCandidateCountCompatible(checked, candidate)) {
                        unsafeMultipackAcceptances += "$caseLabel is count-compatible"
                    }
                    runCatching { swiggyRequestedCartQuantity(checked, candidate) }.getOrNull()?.let { requested ->
                        unsafeMultipackAcceptances += "$caseLabel returns cart quantity $requested"
                    }
                }
            }
        }
        assertTrue(unsafeMultipackAcceptances.joinToString("; "), unsafeMultipackAcceptances.isEmpty())
    }

    @Test fun legacyParsedItemsKeepDefaultQuantityProofAndRetailPackFlags() {
        val legacy = ParsedItem("one yogurt", "yogurt", Quantity.Count(1))
        assertFalse(legacy.requiresIntentQuantityProof)
        assertFalse(legacy.retailPackCount)
    }

    @Test fun sourceUnprovenLegacyFlagsCannotAuthorizeExactOrDirectContainerAction() {
        data class LegacyContainerCase(
            val name: String,
            val query: String,
            val single: RecommendationCandidate,
            val pluralCount: RecommendationCandidate,
            val pluralX3: RecommendationCandidate,
            val nestedEqual: RecommendationCandidate,
            val malformed: List<RecommendationCandidate>,
            val additive: List<RecommendationCandidate> = emptyList(),
        )
        val cases = listOf(
            LegacyContainerCase(
                "yogurt", "yogurt cup",
                RecommendationCandidate("legacy-yogurt-cup", "BrandA Yogurt 150 g Cup"),
                RecommendationCandidate("legacy-yogurt-3cups", "BrandA Yogurt 3 cups"),
                RecommendationCandidate("legacy-yogurt-cups-x3", "BrandA Yogurt Cups x 3"),
                RecommendationCandidate("legacy-yogurt-nested", "BrandA Yogurt Cups 3 cups x 3"),
                listOf("0 cups", "-1 cups", "−1 cups", "－1 cups", "+1 cups", "1.5 cups").mapIndexed { index, count ->
                    RecommendationCandidate("legacy-yogurt-malformed-$index", "BrandA Yogurt $count")
                },
                listOf(
                    RecommendationCandidate("legacy-yogurt-additive-plus", "BrandA Yogurt Cups x 3 + Cups x 3"),
                    RecommendationCandidate("legacy-yogurt-additive-and", "BrandA Yogurt Cups x 3 and Cups x 3"),
                ),
            ),
            LegacyContainerCase(
                "honey", "honey jar",
                RecommendationCandidate("legacy-honey-jar", "BrandA Honey 500 g Jar"),
                RecommendationCandidate("legacy-honey-3jars", "BrandA Honey 3 jars"),
                RecommendationCandidate("legacy-honey-jars-x3", "BrandA Honey Jars x 3"),
                RecommendationCandidate("legacy-honey-nested", "BrandA Honey Jars 3 jars x 3"),
                listOf("0 jars", "-1 jars", "−1 jars", "－1 jars", "+1 jars", "1.5 jars").mapIndexed { index, count ->
                    RecommendationCandidate("legacy-honey-malformed-$index", "BrandA Honey $count")
                },
            ),
            LegacyContainerCase(
                "flour", "flour bag",
                RecommendationCandidate("legacy-flour-bag", "BrandA Flour 1 kg Bag"),
                RecommendationCandidate("legacy-flour-3bags", "BrandA Flour 3 bags"),
                RecommendationCandidate("legacy-flour-bags-x3", "BrandA Flour Bags x 3"),
                RecommendationCandidate("legacy-flour-nested", "BrandA Flour Bags 3 bags x 3"),
                listOf("0 bags", "-1 bags", "1.5 bags").mapIndexed { index, count ->
                    RecommendationCandidate("legacy-flour-malformed-$index", "BrandA Flour $count")
                },
            ),
        )
        val extraContainers = listOf(
            "tin" to "tins", "tub" to "tubs", "pouch" to "pouches", "packet" to "packets",
            "sachet" to "sachets", "can" to "cans", "refill" to "refills",
        )

        cases.forEach { case ->
            listOf(false, true).forEach { retailPackCount ->
                val item = legacyContainerItem(case.name, case.query, 1, retailPackCount)
                assertFalse(item.requiresIntentQuantityProof)
                assertEquals(retailPackCount, item.retailPackCount)

                val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(case.single)))
                assertLegacyContainerCountCannotActWithoutSourceProof(checkedSingle, case.single)

                (listOf(case.pluralCount, case.pluralX3, case.nestedEqual) + case.malformed + case.additive).forEach { candidate ->
                    assertLegacyContainerCandidateRejected(item, candidate)
                }
            }
        }

        extraContainers.forEach { (singular, plural) ->
            listOf(false, true).forEach { retailPackCount ->
                val query = "yogurt $singular"
                val item = legacyContainerItem("yogurt", query, 1, retailPackCount)
                assertEquals(retailPackCount, item.retailPackCount)
                val single = RecommendationCandidate("legacy-$singular-single", "BrandA Yogurt 150 g $singular")
                val pluralBundle = RecommendationCandidate("legacy-$singular-3", "BrandA Yogurt 3 $plural")
                val xBundle = RecommendationCandidate("legacy-$singular-x3", "BrandA Yogurt ${plural.replaceFirstChar { it.uppercase() }} x 3")
                val nested = RecommendationCandidate("legacy-$singular-nested", "BrandA Yogurt ${plural.replaceFirstChar { it.uppercase() }} 3 $plural x 3")
                val additivePlus = RecommendationCandidate("legacy-$singular-additive-plus", "BrandA Yogurt ${plural.replaceFirstChar { it.uppercase() }} x 3 + ${plural.replaceFirstChar { it.uppercase() }} x 3")
                val additiveAnd = RecommendationCandidate("legacy-$singular-additive-and", "BrandA Yogurt ${plural.replaceFirstChar { it.uppercase() }} x 3 and ${plural.replaceFirstChar { it.uppercase() }} x 3")

                val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(single)))
                assertLegacyContainerCountCannotActWithoutSourceProof(checkedSingle, single)
                listOf(pluralBundle, xBundle, nested, additivePlus, additiveAnd).forEach { assertLegacyContainerCandidateRejected(item, it) }
            }
        }
    }

    @Test fun sourceProvenCupCartonAndJarCountsHaveTypedCataloguePositiveControls() {
        data class Case(
            val source: String,
            val product: String,
            val container: String,
            val label: String,
            val physicalSize: String,
            val namedUnit: String?,
        )
        val cases = listOf(
            Case("one yogurt cup", "yogurt", "cup", "BrandA Yogurt 150 g Cup", "150 g", null),
            Case("one carton of milk", "milk", "carton", "BrandA Milk 1 L Carton", "1 L", "carton"),
            Case("one jar of honey", "honey", "jar", "BrandA Honey 500 g Jar", "500 g", "jar"),
        )

        cases.forEachIndexed { index, case ->
            val item = prepareReadyContainerItem(
                source = case.source,
                name = case.product,
                packaging = case.container,
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "one",
            )
            assertEquals("case=$index", case.source, item.rawText)
            assertEquals("case=$index", Quantity.Count(1), item.quantity)
            assertTrue("case=$index", item.retailPackCount)

            val titleCandidate = RecommendationCandidate("typed-container-$index", case.label)
            val weightOnly = withSyntheticCatalogueQuantity(titleCandidate, case.physicalSize)
            val weightOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(weightOnly)))
            if (case.namedUnit == null) {
                // Cup is deliberately not a NAMED_RETAIL source role; preserve
                // its established explicit physical-size proposal path.
                assertReviewedRetailProposal(weightOnlyChecked, weightOnly, 1)
            } else {
                assertTrue("case=$index physical size alone cannot prove one ${case.container}",
                    resolveSwiggyCartQuantity(weightOnlyChecked, weightOnly) is SwiggyCartQuantityResolution.Held)
                assertFalse("case=$index physical size alone cannot act",
                    isSwiggyCandidateCountCompatible(weightOnlyChecked, weightOnly))
                assertThrows(IllegalArgumentException::class.java) {
                    swiggyRequestedCartQuantity(weightOnlyChecked, weightOnly)
                }
            }

            case.namedUnit?.let { unit ->
                val typedCandidate = withSyntheticCatalogueQuantity(titleCandidate, "1 $unit")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(typedCandidate)))
                assertEquals("case=$index", SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(checked, typedCandidate))
                assertEquals("case=$index", 1, swiggyRequestedCartQuantity(checked, typedCandidate))
            }
        }
    }

    @Test fun standardCountOfThreeItemsCannotSilentlyConsumeOneThreeContainerBundle() {
        val item = legacyContainerItem("3 yogurt cups", "yogurt cup", 3, retailPackCount = false)
        val exactBundle = RecommendationCandidate("legacy-yogurt-three-cups", "BrandA Yogurt Pack of 3 cups")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(exactBundle)))
        assertTrue(isSwiggyCandidateAllowed(checked, exactBundle))
        assertUnqualifiedCountNeedsReview(checked, exactBundle, 3)
        val suffixBundle = RecommendationCandidate("legacy-yogurt-three-cups-suffix", "BrandA Yogurt Cup 150 g x 3")
        val checkedSuffix = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(suffixBundle)))
        assertTrue(isSwiggyCandidateAllowed(checkedSuffix, suffixBundle))
        assertUnqualifiedCountNeedsReview(checkedSuffix, suffixBundle, 3)
    }

    @Test fun v2UnqualifiedCountOfThreeYogurtCupsCannotSilentlyConvertBundle() {
        val item = prepareReadyContainerItem(
            source = "three yogurt cups",
            name = "yogurt",
            packaging = "cup",
            quantity = 3,
            quantityUnit = "count",
            quantityText = "three",
        )
        val exactBundle = RecommendationCandidate("v2-yogurt-three-cups", "BrandA Yogurt Pack of 3 cups")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(exactBundle)))
        assertTrue(isSwiggyCandidateAllowed(checked, exactBundle))
        assertUnqualifiedCountNeedsReview(checked, exactBundle, 3)
        val suffixBundle = RecommendationCandidate("v2-yogurt-three-cups-suffix", "BrandA Yogurt Cup 150 g x 3")
        val checkedSuffix = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(suffixBundle)))
        assertTrue(isSwiggyCandidateAllowed(checkedSuffix, suffixBundle))
        assertUnqualifiedCountNeedsReview(checkedSuffix, suffixBundle, 3)
    }

    @Test fun legacyOneBoxEggsDoesNotSilentlyStripInnerPieceCount() {
        val item = legacyContainerItem("eggs", "eggs", 1, retailPackCount = true)
        val mixed = RecommendationCandidate("legacy-eggs-one-box-six-pcs", "Farm Fresh 6 pcs 1 Box, 330-360 g")
        assertLegacyContainerCandidateRejected(item, mixed)
    }

    @Test fun cupAndJarRemainDistinctRequiredPackageForms() {
        val yogurtCup = prepareReadyContainerItem(
            source = "one yogurt cup", name = "yogurt", packaging = "cup", quantityUnit = "count",
        )
        val honeyJar = prepareReadyContainerItem(
            source = "one jar of honey", name = "honey", packaging = "jar", quantityUnit = "pack",
        )
        val yogurtInJar = RecommendationCandidate("yogurt-wrong-jar", "BrandA Yogurt 150 g Jar")
        val honeyInCup = RecommendationCandidate("honey-wrong-cup", "BrandA Honey 500 g Cup")
        assertFalse(isSwiggyCandidateAllowed(yogurtCup, yogurtInJar))
        assertFalse(isSwiggyCandidateAllowed(honeyJar, honeyInCup))
    }

    @Test fun countedSamosaRejectsFrozenBagAndDoesNotInferPieceRole() {
        val item = prepareReadyContainerItem(
            source = "one samosa",
            name = "samosa",
            packaging = null,
            quantityUnit = "count",
        )
        assertFalse(item.retailPackCount)

        val frozenBag = RecommendationCandidate("frozen-samosa-200g", "BrandA Frozen Samosa 200 g Bag")
        val checkedFrozen = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(frozenBag)))
        assertTrue(isSwiggyCandidateAllowed(checkedFrozen, frozenBag))
        assertFalse(isSwiggyCandidateCountCompatible(checkedFrozen, frozenBag))

        val preparedPiece = RecommendationCandidate("ready-samosa-1pc", "BrandA Ready to Eat Samosa 1 pc")
        val checkedPrepared = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(preparedPiece)))
        assertTrue(isSwiggyCandidateAllowed(checkedPrepared, preparedPiece))
        assertUnqualifiedCountNeedsReview(checkedPrepared, preparedPiece, 1)
    }

    @Test fun breadRollsSixDoesNotBecomeSixPacksFromCandidateMultipackCount() {
        val item = prepareReadyContainerItem(
            source = "six bread rolls",
            name = "bread rolls",
            packaging = null,
            quantity = 6,
            quantityUnit = "pack",
            quantityText = "six",
        )
        val bundle = RecommendationCandidate("bread-rolls-six-pack", "Bread Rolls (Pack of 6)")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(bundle)))
        assertTrue(isSwiggyCandidateAllowed(checked, bundle))
        assertUnqualifiedCountNeedsReview(checked, bundle, 6)
    }

    @Test fun neighboringMilkPacketCannotBeDroppedFromEggPackRow() {
        val base = readyPackRow("two eggs and milk packets", "eggs", quantityText = "two")
        val row = base.copy(item = base.item.copy(quantity = BigDecimal.valueOf(2), packaging = "packet"))
        assertEquals("missing_source_identity", row.groundingIssue())
    }

    @Test fun unsupportedBarePackLabelsAreBlockedDuringPreparation() {
        listOf("one samosa" to "samosa", "one yogurt" to "yogurt", "one egg" to "egg").forEach { (source, name) ->
            val row = readyPackRow(source, name)
            assertEquals(source, "retail_pack_unproven", row.groundingIssue())
            val prepared = GroceryIntentReviewV2(
                "grocery-intent-v2", true, source, 1, listOf(row), emptyList(),
            ).prepareForReview()
            assertTrue(source, prepared.items.isEmpty())
            assertTrue(source, prepared.excludedText.any { it.contains(source) })
        }
    }

    @Test fun explicitRetailContainerProvesPackWithoutMetadata() {
        listOf("one pack of samosa" to "pack samosa", "one jar of yogurt" to "jar yogurt").forEach { (source, name) ->
            val row = readyPackRow(source, name)
            assertEquals(source, null, row.groundingIssue())
            val parsed = GroceryIntentReviewV2(
                "grocery-intent-v2", true, source, 1, listOf(row), emptyList(),
            ).prepareForReview().items.single()
            assertEquals(Quantity.Count(1), parsed.quantity)
        }
    }

    @Test fun refillContainerRequiresRetainedIdentityAndExactCatalogueForm() {
        data class RefillCase(
            val source: String,
            val count: Int,
            val countText: String,
            val container: String,
            val label: String,
            val packValue: Int? = null,
            val packUnit: String = "unspecified",
            val packText: String? = null,
            val name: String = "soap refill",
        )
        val cases = listOf(
            RefillCase("one refill pouch of soap", 1, "one", "pouch", "Soap Refill Pouch"),
            RefillCase("two refill pouches of soap", 2, "two", "pouch", "Soap Refill Pouch"),
            RefillCase("one refill bottle of soap", 1, "one", "bottle", "Soap Refill Bottle"),
            RefillCase("one REFILL pouch of soap", 1, "one", "pouch", "Soap Refill Pouch"),
            RefillCase("one soap refill pouch", 1, "one", "pouch", "Soap Refill Pouch"),
            RefillCase("one pouch of refill soap", 1, "one", "pouch", "Refill Soap Pouch", name = "refill soap"),
            RefillCase("one refill-pouch of soap", 1, "one", "pouch", "Soap Refill Pouch"),
            RefillCase("one refill pouch of soap 500 ml each", 1, "one", "pouch",
                "Soap Refill Pouch 500 ml", 500, "ml", "500"),
            RefillCase("one 500 ml refill pouch of soap", 1, "one", "pouch",
                "Soap Refill Pouch 500 ml", 500, "ml", "500"),
            RefillCase("one gentle nourishing family hand soap refill pouch", 1, "one", "pouch",
                "Gentle Nourishing Family Hand Soap Refill Pouch", name = "gentle nourishing family hand soap refill"),
        )
        cases.forEachIndexed { index, case ->
            val item = prepareReadyContainerItem(
                source = case.source, name = case.name, packaging = case.container,
                quantity = case.count, quantityText = case.countText,
                packValue = case.packValue, packUnit = case.packUnit, packText = case.packText,
            )
            assertEquals("case=$index", case.source, item.rawText)
            assertEquals("case=$index", Quantity.Count(case.count), item.quantity)
            assertTrue("case=$index", item.retailPackCount)
            assertTrue("case=$index", item.query.contains(case.name, ignoreCase = true))
            assertTrue("case=$index", item.query.contains(case.container, ignoreCase = true))
            assertTrue("case=$index", item.strictMatchPhrase.orEmpty().contains(case.container, ignoreCase = true))
            case.packValue?.let { assertTrue("case=$index", item.query.contains("500 ml", ignoreCase = true)) }

            val titleOnly = RecommendationCandidate("soap-refill-exact-$index", case.label)
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue("case=$index ${case.label}", isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            val sourceRetail = item.intentQuantityProvenance?.let(::groceryIntentSourceRetailReceiptValid) == true
            if (sourceRetail) {
                assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
            } else if (case.packValue != null) {
                assertReviewedRetailProposal(titleOnlyChecked, titleOnly, case.count)
            } else {
                assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
                assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, case.count)
            }
            val evidenceSize = case.packValue?.let { "$it ${case.packUnit}" } ?: "200 ml"
            val exact = withSyntheticCatalogueQuantity(
                titleOnly, if (sourceRetail) "1 ${case.container}" else evidenceSize,
            )
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(exact)))
            if (sourceRetail) {
                assertEquals(SwiggyCartQuantityResolution.Exact(case.count), resolveSwiggyCartQuantity(checked, exact))
            } else {
                assertReviewedRetailProposal(checked, exact, case.count)
            }

            val wrongForm = RecommendationCandidate("soap-refill-wrong-form-$index", "Soap Refill ${if (case.container == "pouch") "Bottle" else "Pouch"}")
            val missingRefill = RecommendationCandidate("soap-missing-refill-$index", "Soap ${case.container}")
            val multiPack = RecommendationCandidate("soap-refill-multipack-$index", "${case.label} Pack of ${case.count + 1}")
            for (candidate in listOf(wrongForm, missingRefill, multiPack)) {
                val rejected = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
                assertFalse("case=$index ${candidate.label} must not be purchasable",
                    isSwiggyCandidateAllowed(rejected, candidate) && isSwiggyCandidateCountCompatible(rejected, candidate))
                if (candidate == multiPack) assertThrows(IllegalArgumentException::class.java) {
                    swiggyRequestedCartQuantity(rejected, candidate)
                }
            }
        }
    }

    @Test fun refillContainerCannotBeInferredAcrossGapsOrWithoutExactOwnership() {
        val ambiguousSources = listOf(
            "one refill pouch of soap" to "soap",
            "one refill and pouch of soap" to "soap refill",
            "one refill or pouch of soap" to "soap refill",
            "one refill not pouch of soap" to "soap refill",
            "one refill 2 pouch of soap" to "soap refill",
            "one refill, pouch of soap" to "soap refill",
            "one refill\npouch of soap" to "soap refill",
            "one refill pouch of soap and two bottles of soap" to "soap refill",
            "one refill pouch of soap and one refill pouch of shampoo" to "soap refill",
            "one refill bottle of soap" to "soap refill",
            "two refill pouches of soap" to "soap refill",
            "one refill pouch of soap 500 ml each" to "soap refill",
            "one ref\u0131ll pouch of soap" to "soap refill",
            "one person needs refill pouch of soap" to "person needs soap refill",
            "one person wants refill pouch of soap" to "person wants soap refill",
            "one child uses refill pouch of soap" to "child uses soap refill",
            "one set refill pouch of soap" to "set soap refill",
        )
        ambiguousSources.forEach { (source, name) ->
            assertContainerRowBlocked(source, source, name, "pouch")
        }
        assertContainerRowBlocked("one refill pouch of soap", "one refill pouch of soap", "soap refill", "bottle")

        val wrongPackSizeSource = "one refill pouch of soap 500 ml each"
        val base = readyPackRow(wrongPackSizeSource, "soap refill", packValue = 250, packUnit = "ml", packText = "250")
        val row = base.copy(item = base.item.copy(packaging = "pouch"))
        assertTrue(row.groundingIssue() != null)
        val prepared = GroceryIntentReviewV2(
            "grocery-intent-v2", true, wrongPackSizeSource, 1, listOf(row), emptyList(),
        ).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty())
    }

    @Test fun refillCompoundRequiresPositiveLiteralIdentityAndNoDistributionClause() {
        val primary = "one refill pouch of soap"
        listOf("one refill pac\u212a of soap" to "pack", "one refill \u017fachet of soap" to "sachet").forEach { (source, form) ->
            assertContainerRowBlocked(source, source, "soap refill", form)
        }
        listOf("soap no refill", "soap not refill", "soap without refill", "soap non refill",
            "soap non-refill", "soap no-refill", "soap anti-refill", "soap refill-free", "soap refill's",
            "soap\u00a0refill", "soap\u2002refill", "soap\u202frefill", "soap\u3000refill",
        ).forEach { name -> assertContainerRowBlocked(primary, primary, name, "pouch") }
        listOf("per child", "per person", "for each child", "every day", "tomorrow", "daily", "as before", "as usual").forEach { distribution ->
            listOf("$distribution $primary", "$primary $distribution").forEach { instruction ->
                assertContainerRowBlocked(instruction, instruction, "soap refill $distribution", "pouch")
                assertContainerRowBlocked(instruction, primary, "soap refill", "pouch")
            }
        }
    }

    @Test fun unicodeLookalikesCannotProveOrdinaryOrRefillContainerFamilies() {
        data class ContainerCase(
            val unicodeSource: String,
            val literalSource: String,
            val identity: String,
            val packaging: String,
        )
        val cases = listOf(
            ContainerCase("one pac\u212a of soap", "one pack of soap", "soap", "pack"),
            ContainerCase("one refill pac\u212a of soap", "one refill pack of soap", "soap refill", "pack"),
            ContainerCase("one \u017fachet of shampoo", "one sachet of shampoo", "shampoo", "sachet"),
            ContainerCase("one refill \u017fachet of shampoo", "one refill sachet of shampoo", "shampoo refill", "sachet"),
            ContainerCase("one t\u0131n of detergent", "one tin of detergent", "detergent", "tin"),
            ContainerCase("one refill t\u0131n of detergent", "one refill tin of detergent", "detergent refill", "tin"),
        )

        val failures = mutableListOf<String>()
        for (case in cases) {
            try {
                assertContainerRowBlocked(case.unicodeSource, case.unicodeSource, case.identity, case.packaging)
            } catch (error: AssertionError) {
                failures += "lookalike accepted for ${case.packaging}"
            }

            val literalBase = readyPackRow(case.literalSource, case.identity)
            val literalRow = literalBase.copy(item = literalBase.item.copy(packaging = case.packaging))
            val literalIssue = literalRow.groundingIssue(instruction = case.literalSource)
            if (literalIssue != null) failures += "literal control rejected for ${case.packaging}: $literalIssue"
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test fun closedGaddiAliasPreparesExactBunchAndChecksCataloguePackCount() {
        data class GaddiCase(
            val source: String,
            val name: String,
            val count: Int,
            val countText: String,
            val label: String,
            val packValue: Int? = null,
            val expectedExact: Boolean = false,
        )
        val cases = listOf(
            GaddiCase("coriander one bunch", "coriander", 1, "one", "Fresh Coriander Bunch"),
            GaddiCase("one bunch of coriander", "coriander", 1, "one", "Fresh Coriander Bunch", expectedExact = true),
            GaddiCase("dhaniya ek gaddi", "coriander", 1, "ek", "Fresh Coriander Bunch"),
            GaddiCase("ek gaddi dhaniya", "coriander", 1, "ek", "Fresh Coriander Bunch"),
            GaddiCase("do gaddi dhaniya", "coriander", 2, "do", "Fresh Coriander Bunch"),
            GaddiCase("ek GADDI dhaniya", "coriander", 1, "ek", "Fresh Coriander Bunch"),
            GaddiCase("mint ek gaddi", "mint", 1, "ek", "Fresh Mint Bunch"),
            GaddiCase("dhaniya ek gaddi 100 g each", "coriander", 1, "ek", "Fresh Coriander Bunch 100 g", 100),
        )
        cases.forEachIndexed { index, case ->
            val item = prepareReadyContainerItem(
                source = case.source, name = case.name, packaging = "bunch",
                quantity = case.count, quantityText = case.countText,
                packValue = case.packValue, packUnit = if (case.packValue == null) "unspecified" else "g",
                packText = case.packValue?.toString(),
            )
            assertEquals("case=$index", case.source, item.rawText)
            assertEquals("case=$index", Quantity.Count(case.count), item.quantity)
            assertTrue("case=$index", item.retailPackCount)
            assertTrue("case=$index", item.query.contains(case.name, ignoreCase = true))
            assertTrue("case=$index", item.query.contains("bunch", ignoreCase = true))
            case.packValue?.let { assertTrue("case=$index", item.query.contains("100 g", ignoreCase = true)) }

            val titleOnly = RecommendationCandidate("gaddi-exact-$index", case.label)
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue("case=$index ${case.label}", isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            if (case.expectedExact) {
                assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
                assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, case.count)
            } else if (case.packValue != null) {
                assertReviewedRetailProposal(titleOnlyChecked, titleOnly, case.count)
            } else {
                assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
                assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, case.count)
            }
            val exact = withSyntheticCatalogueQuantity(
                titleOnly,
                when {
                    case.expectedExact -> "1 bunch"
                    case.packValue != null -> "${case.packValue} g"
                    else -> "100 g"
                },
            )
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(exact)))
            if (case.expectedExact) {
                assertTrue(groceryIntentSourceRetailReceiptValid(requireNotNull(checked.intentQuantityProvenance)))
                assertTrue(swiggyCountResolutionDiagnostic(checked, exact),
                    resolveSwiggyCartQuantity(checked, exact) is SwiggyCartQuantityResolution.Exact)
                assertTrue(isSwiggyCandidateCountCompatible(checked, exact))
                assertEquals(case.count, swiggyRequestedCartQuantity(checked, exact))
            } else {
                assertReviewedRetailProposal(checked, exact, case.count)
            }

            val wrongPackCount = withSyntheticCatalogueQuantity(
                exact.copy(spinId = "gaddi-wrong-count-$index", label = "${case.label} Pack of ${case.count + 1}"),
                case.packValue?.let { "$it g" } ?: "100 g",
            )
            val checkedWrongCount = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(wrongPackCount)))
            assertFalse("case=$index wrong pack count", isSwiggyCandidateCountCompatible(checkedWrongCount, wrongPackCount))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checkedWrongCount, wrongPackCount)
            }
        }
    }

    @Test fun gaddiProofRejectsMismatchedCountMetadataAndUnownedContext() {
        fun assertBlocked(
            instruction: String,
            source: String,
            name: String = "coriander",
            quantity: Int = 1,
            quantityText: String = "ek",
            packaging: String? = "bunch",
            packValue: Int? = null,
            packText: String? = null,
        ) {
            val base = readyPackRow(
                source, name, quantity = BigDecimal.valueOf(quantity.toLong()), quantityText = quantityText,
                quantityUnit = "pack", packValue = packValue,
                packUnit = if (packValue == null) "unspecified" else "g", packText = packText,
            )
            val row = base.copy(
                item = base.item.copy(packaging = packaging),
                evidence = base.evidence.copy(primary = sourceQuote(instruction, source)),
            )
            val prepared = GroceryIntentReviewV2(
                "grocery-intent-v2", true, instruction, 1, listOf(row), emptyList(),
            ).prepareForReview()
            assertTrue("unexpected ready row: $instruction", prepared.items.isEmpty())
            assertTrue("source disappeared from review: $instruction", prepared.excludedText.isNotEmpty())
        }

        assertBlocked("do gaddi dhaniya", "do gaddi dhaniya", quantity = 1)
        assertBlocked("dhaniya ek gaddi", "dhaniya ek gaddi", packaging = null)
        assertBlocked("dhaniya ek gaddi", "dhaniya ek gaddi", packaging = "bottle")
        assertBlocked("dhaniya ek gaddi and do mint", "dhaniya ek gaddi", name = "coriander")
        assertBlocked("for each person dhaniya ek gaddi", "for each person dhaniya ek gaddi")
        assertBlocked("not dhaniya ek gaddi", "dhaniya ek gaddi")
        assertBlocked("if available dhaniya ek gaddi", "dhaniya ek gaddi")
        for (token in listOf("gaddis", "gaddix", "gadd\u0131", "gadd\u0130", "gaddi\u0301")) {
            val source = "dhaniya ek $token"
            assertBlocked(source, source)
        }
        assertBlocked("dhaniya ek gaddi 100 g each", "dhaniya ek gaddi 100 g each",
            packValue = 250, packText = "100")
        listOf("gaddi-free coriander", "gaddi-shaped coriander").forEach { name ->
            assertBlocked("one $name", "one $name", name = name, quantityText = "one")
        }
        listOf(
            "one bunch coriander for each 100 g serving" to "coriander for each serving",
            "one gaddi coriander for each 100 g serving" to "coriander for each serving",
            "one bunch coriander each 100 g portion" to "coriander each portion",
            "one gaddi coriander each 100 g portion" to "coriander each portion",
            "one bunch coriander for each 100 g" to "coriander for each",
            "one gaddi coriander for each 100 g" to "coriander for each",
        ).forEach { (source, name) ->
            assertBlocked(source, source, name = name, quantityText = "one", packValue = 100, packText = "100")
        }
    }

    @Test fun bunchAliasesCannotHideDistributionInsideCopiedProductName() {
        val cases = listOf(
            Triple("one person needs bunch coriander", "person needs coriander", "bunch"),
            Triple("one bunch coriander per child", "coriander per child", "bunch"),
            Triple("one bunch coriander for each child", "coriander for each child", "bunch"),
            Triple("one person needs gaddi coriander", "person needs coriander", "gaddi"),
            Triple("one gaddi coriander per child", "coriander per child", "gaddi"),
            Triple("one gaddi coriander for each child", "coriander for each child", "gaddi"),
            Triple("one bottle needs coriander bunch", "bottle needs coriander", "bunch"),
            Triple("one bottle needs coriander gaddi", "bottle needs coriander", "gaddi"),
            Triple("one can hold coriander bunch", "can hold coriander", "bunch"),
            Triple("one can hold coriander gaddi", "can hold coriander", "gaddi"),
            Triple("one tin contains coriander bunch", "tin contains coriander", "bunch"),
            Triple("one tin contains coriander gaddi", "tin contains coriander", "gaddi"),
            Triple("person needs coriander one bunch", "person needs coriander", "bunch"),
            Triple("person needs coriander one gaddi", "person needs coriander", "gaddi"),
            Triple("person needs coriander bunch one", "person needs coriander", "bunch"),
            Triple("person needs coriander gaddi one", "person needs coriander", "gaddi"),
            Triple("person needs one bunch coriander", "person needs coriander", "bunch"),
            Triple("person needs one gaddi coriander", "person needs coriander", "gaddi"),
            Triple("coriander per child one bunch", "coriander per child", "bunch"),
            Triple("coriander per child one gaddi", "coriander per child", "gaddi"),
            Triple("per child one bunch coriander", "coriander per child", "bunch"),
            Triple("per child one gaddi coriander", "coriander per child", "gaddi"),
            Triple("one bunch coriander for every child", "coriander for every child", "bunch"),
            Triple("one gaddi coriander for every child", "coriander for every child", "gaddi"),
            Triple("each child gets one bunch coriander", "each child gets coriander", "bunch"),
            Triple("each child gets one gaddi coriander", "each child gets coriander", "gaddi"),
            Triple("one bunch coriander each child", "coriander each child", "bunch"),
            Triple("one gaddi coriander each child", "coriander each child", "gaddi"),
            Triple("one bunch coriander apiece", "coriander apiece", "bunch"),
            Triple("one gaddi coriander apiece", "coriander apiece", "gaddi"),
            Triple("one child regularly uses bunch coriander", "child regularly uses coriander", "bunch"),
            Triple("one child regularly uses gaddi coriander", "child regularly uses coriander", "gaddi"),
            Triple("one child who eats coriander bunch", "child who eats coriander", "bunch"),
            Triple("one child who eats coriander gaddi", "child who eats coriander", "gaddi"),
        )
        cases.forEach { (source, name, form) ->
            val base = readyPackRow(source, name, quantity = BigDecimal.ONE, quantityText = "one")
            val row = base.copy(
                item = base.item.copy(packaging = "bunch"),
                evidence = base.evidence.copy(primary = sourceQuote(source, source)),
            )
            val prepared = GroceryIntentReviewV2(
                "grocery-intent-v2", true, source, 1, listOf(row), emptyList(),
            ).prepareForReview()
            assertTrue("$form should not hide distribution: $source", prepared.items.isEmpty())
            assertTrue("source disappeared from review: $source", prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun preservedNameSuffixCannotDropBunchOrGaddiScope() {
        listOf("daily", "weekly", "nightly", "as before", "as usual").forEach { suffix ->
            listOf("bunch", "gaddi").forEach { form ->
                val source = "one $form coriander $suffix"
                assertContainerRowBlocked(source, source, "coriander $suffix", "bunch")
            }
        }
    }

    @Test fun distributionOutsidePrimaryIsHeldButPlainAddRemainsReady() {
        listOf(
            "for each child add one bunch coriander" to "one bunch coriander",
            "for each child add one gaddi coriander" to "one gaddi coriander",
            "as before add one bunch coriander" to "one bunch coriander",
            "as before add one gaddi coriander" to "one gaddi coriander",
            "one bunch coriander, add this for each child" to "one bunch coriander",
            "one gaddi coriander, add this for each child" to "one gaddi coriander",
            "one gaddi coriander, add for each child" to "one gaddi coriander",
            "one gaddi coriander, please add it for each child" to "one gaddi coriander",
            "one gaddi coriander, order this daily" to "one gaddi coriander",
        ).forEach { (instruction, source) ->
            val base = readyPackRow(source, "coriander", quantity = BigDecimal.ONE, quantityText = "one")
            val row = base.copy(
                item = base.item.copy(packaging = "bunch"),
                evidence = base.evidence.copy(primary = sourceQuote(instruction, source)),
            )
            val prepared = GroceryIntentReviewV2(
                "grocery-intent-v2", true, instruction, 1, listOf(row), emptyList(),
            ).prepareForReview()
            assertTrue("unexpected ready row: $instruction", prepared.items.isEmpty())
            assertTrue("source disappeared from review: $instruction", prepared.excludedText.isNotEmpty())
        }
        listOf("bunch", "gaddi").forEach { form ->
            val instruction = "please add one $form coriander"
            val source = "one $form coriander"
            val base = readyPackRow(source, "coriander", quantity = BigDecimal.ONE, quantityText = "one")
            val row = base.copy(
                item = base.item.copy(packaging = "bunch"),
                evidence = base.evidence.copy(primary = sourceQuote(instruction, source)),
            )
            val prepared = GroceryIntentReviewV2(
                "grocery-intent-v2", true, instruction, 1, listOf(row), emptyList(),
            ).prepareForReview()
            assertEquals("$instruction should remain an ordinary add", 1, prepared.items.size)
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test fun gaddiAliasAcceptsBoundedUnicodeWhitespaceAndExplicitSize() {
        listOf(
            "one gaddi\u00a0dhaniya", "one gaddi\u2002dhaniya",
            "one gaddi\u202fdhaniya", "one gaddi\u3000dhaniya",
            "one 100 g gaddi coriander",
            "one bunch coriander 100 g each", "one gaddi coriander 100 g each",
        ).forEach { source ->
            val hasSize = source.contains("100 g")
            val item = prepareReadyContainerItem(
                source = source, name = "coriander", packaging = "bunch",
                quantity = 1, quantityText = "one",
                packValue = if (hasSize) 100 else null,
                packUnit = if (hasSize) "g" else "unspecified",
                packText = if (hasSize) "100" else null,
            )
            assertEquals(source, item.rawText)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertTrue(item.query.contains("bunch", ignoreCase = true))
            if (hasSize) assertTrue(item.query.contains("100 g", ignoreCase = true))
            val titleOnly = RecommendationCandidate("gaddi-spacing-${source.length}", "Fresh Coriander Bunch${if (hasSize) " 100 g" else ""}")
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue(titleOnly.label, isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            if (hasSize) {
                assertReviewedRetailProposal(titleOnlyChecked, titleOnly, 1)
            } else {
                assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
                assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
            }
            val candidate = withSyntheticCatalogueQuantity(titleOnly, "100 g")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
            assertReviewedRetailProposal(checked, candidate, 1)
        }
    }

    @Test fun leadingNominalBrandAndProductPhrasesKeepTerminalContainer() {
        listOf(
            Triple("one Love Beauty soap bar", "Love Beauty soap", "bar"),
            Triple("one Prefer Farms honey jar", "Prefer Farms honey", "jar"),
            Triple("one Drink More juice bottle", "Drink More juice", "bottle"),
            Triple("one energy drink can", "energy drink", "can"),
            Triple("one mango drink bottle", "mango drink", "bottle"),
            Triple("one mineral drinking water bottle", "mineral drinking water", "bottle"),
            Triple("one baby drinking water bottle", "baby drinking water", "bottle"),
            Triple("one dark eating chocolate bar", "dark eating chocolate", "bar"),
            Triple("one green tea drink bottle", "green tea drink", "bottle"),
        ).forEach { (source, name, packaging) ->
            val item = prepareReadyContainerItem(
                source = source, name = name, packaging = packaging,
                quantity = 1, quantityText = "one",
            )
            assertEquals(source, item.rawText)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertTrue(item.query.contains(name))
            assertTrue(item.query.contains(packaging))
            val titleOnly = RecommendationCandidate("leading-nominal-$packaging", "$name $packaging")
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue(titleOnly.label, isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
            assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
            val size = when (name) {
                "Love Beauty soap" -> "100 g"
                "Prefer Farms honey" -> "500 g"
                "dark eating chocolate" -> "100 g"
                else -> "200 ml"
            }
            val candidate = withSyntheticCatalogueQuantity(titleOnly, size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
            assertReviewedRetailProposal(checked, candidate, 1)
        }
    }

    @Test fun nominalReadyToEatAndReadyToDrinkContainerControlsRemainValid() {
        listOf(
            Triple("one ready-to-eat snack pouch", "ready-to-eat snack", "pouch"),
            Triple("one ready-to-drink juice bottle", "ready-to-drink juice", "bottle"),
        ).forEach { (source, name, packaging) ->
            val item = prepareReadyContainerItem(
                source = source, name = name, packaging = packaging,
                quantity = 1, quantityText = "one",
            )
            assertEquals(source, item.rawText)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertTrue(item.query.contains(name))
            assertTrue(item.query.contains(packaging))

            val titleOnly = RecommendationCandidate("nominal-$packaging", "$name ${packaging}")
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue(titleOnly.label, isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
            assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
            val size = if (packaging == "pouch") "100 g" else "200 ml"
            val candidate = withSyntheticCatalogueQuantity(titleOnly, size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
            assertReviewedRetailProposal(checked, candidate, 1)
        }
    }

    @Test fun internalOfInExactProductNameCanProveTerminalJarWithoutChangingCount() {
        val source = "one Valley of Farms ghee jar"
        listOf("Valley of Farms ghee", "Valley of Farms ghee jar").forEachIndexed { index, name ->
            val item = prepareReadyContainerItem(
                source = source,
                name = name,
                packaging = "jar",
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "one",
            )
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertTrue(item.query, item.query.contains(name))
            assertTrue(item.query.contains("ghee"))
            assertTrue(item.query.contains("jar"))

            val titleOnly = RecommendationCandidate("valley-ghee-single-$index", "Valley of Farms Ghee Jar")
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue(titleOnly.label, isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
            assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
            val singleton = withSyntheticCatalogueQuantity(titleOnly, "500 g")
            val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(singleton)))
            assertReviewedRetailProposal(checkedSingle, singleton, 1)

            val multipack = RecommendationCandidate("valley-ghee-three-$index", "Valley of Farms Ghee Jar Pack of 3")
            val checkedMultipack = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(multipack)))
            assertTrue(multipack.label, isSwiggyCandidateAllowed(checkedMultipack, multipack))
            assertFalse(multipack.label, isSwiggyCandidateCountCompatible(checkedMultipack, multipack))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checkedMultipack, multipack)
            }
        }
    }

    @Test fun lowercaseInternalOfIdentityCanProveTerminalJarWithoutChangingCount() {
        val source = "one valley of farms ghee jar"
        val item = prepareReadyContainerItem(
            source = source,
            name = "valley of farms ghee",
            packaging = "jar",
        )
        assertEquals(Quantity.Count(1), item.quantity)
        assertTrue(item.retailPackCount)
        val titleOnly = RecommendationCandidate("valley-ghee-lowercase", "Valley of Farms Ghee Jar")
        val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
        assertTrue(isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
        assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
        assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
        val singleton = withSyntheticCatalogueQuantity(titleOnly, "500 g")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(singleton)))
        assertReviewedRetailProposal(checked, singleton, 1)
    }

    @Test fun internalOfBrandContainerEvidenceIsCaseInsensitiveButIdentityRemainsExact() {
        data class BrandCase(val sourceName: String, val productName: String, val container: String, val label: String)
        val cases = listOf(
            BrandCase("Valley of Farms ghee", "Valley of Farms ghee", "jar", "Valley of Farms Ghee Jar"),
            BrandCase("valley of farms ghee", "valley of farms ghee", "jar", "Valley of Farms Ghee Jar"),
            BrandCase("VALLEY OF FARMS GHEE", "VALLEY OF FARMS GHEE", "jar", "Valley of Farms Ghee Jar"),
            BrandCase("VaLlEy Of FaRmS GhEe", "VaLlEy Of FaRmS GhEe", "jar", "Valley of Farms Ghee Jar"),
            BrandCase("Harbor of Grove almonds", "Harbor of Grove almonds", "tin", "Harbor of Grove Almonds Tin"),
            BrandCase("harbor of grove almonds", "harbor of grove almonds tin", "tin", "Harbor of Grove Almonds Tin"),
            BrandCase("HARBOR OF GROVE ALMONDS", "HARBOR OF GROVE ALMONDS tin", "tin", "Harbor of Grove Almonds Tin"),
            BrandCase("HaRbOr Of GrOvE AlMoNdS", "HaRbOr Of GrOvE AlMoNdS", "tin", "Harbor of Grove Almonds Tin"),
        )
        cases.forEachIndexed { index, case ->
            val source = "one ${case.sourceName} ${case.container}"
            val item = prepareReadyContainerItem(source, case.productName, case.container)
            assertEquals("case=$index", Quantity.Count(1), item.quantity)
            assertTrue("case=$index", item.retailPackCount)
            val titleOnly = RecommendationCandidate("brand-single-$index", case.label)
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
            assertTrue("case=$index", isSwiggyCandidateAllowed(titleOnlyChecked, titleOnly))
            assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
            assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
            val single = withSyntheticCatalogueQuantity(titleOnly, if (case.container == "jar") "500 g" else "100 g")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(single)))
            assertReviewedRetailProposal(checked, single, 1)

            val mismatched = withSyntheticCatalogueQuantity(
                single.copy(spinId = "brand-mismatch-$index", label = "Other Brand ${case.container}"),
                if (case.container == "jar") "500 g" else "100 g",
            )
            val checkedMismatch = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(mismatched)))
            assertFalse("case=$index", isSwiggyCandidateAllowed(checkedMismatch, mismatched))

            val outerMultiPack = withSyntheticCatalogueQuantity(
                single.copy(spinId = "brand-multipack-$index", label = "${case.label} Pack of 3"),
                if (case.container == "jar") "500 g" else "100 g",
            )
            val checkedMultiPack = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(outerMultiPack)))
            assertTrue("case=$index", isSwiggyCandidateAllowed(checkedMultiPack, outerMultiPack))
            assertFalse("case=$index", isSwiggyCandidateCountCompatible(checkedMultiPack, outerMultiPack))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checkedMultiPack, outerMultiPack)
            }
        }

        val source = "one valley of farms ghee jar"
        assertContainerRowBlocked(source, source, "Valley of farms ghee", "jar")
    }

    @Test fun internalOfServingMeasureAndSetPhrasesCannotProveTerminalContainers() {
        data class AmbiguousOfCase(val source: String, val name: String, val packaging: String)
        val cases = listOf(
            AmbiguousOfCase("one Valley of Farms ghee jar", "Valley Farms ghee", "jar"),
            AmbiguousOfCase("one Valley of Farms ghee jar", "Valley of farms ghee", "jar"),
            AmbiguousOfCase("one Valley of Farms ghee, jar", "Valley of Farms ghee", "jar"),
            AmbiguousOfCase("one spoon of honey jar", "spoon of honey", "jar"),
            AmbiguousOfCase("one glass of water bottle", "glass of water", "bottle"),
            AmbiguousOfCase("one set of soap bars", "set of soap", "bar"),
            AmbiguousOfCase("one half of cheese block", "half of cheese", "block"),
            AmbiguousOfCase("one SPOON OF HONEY jar", "SPOON OF HONEY", "jar"),
            AmbiguousOfCase("one Set of Soap Bars box", "Set of Soap Bars", "box"),
            AmbiguousOfCase("one Friend of Farms wants jar", "Friend of Farms wants", "jar"),
            AmbiguousOfCase("one She of Farms jar", "She of Farms", "jar"),
            AmbiguousOfCase("one Friend of Farms if jar", "Friend of Farms if", "jar"),
        )

        cases.forEach { case ->
            val base = readyPackRow(case.source, case.name)
            val row = base.copy(item = base.item.copy(packaging = case.packaging))
            assertTrue(case.source, row.groundingIssue() != null)
            val prepared = GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2",
                requiresReview = true,
                instruction = case.source,
                sourceItemCount = 1,
                readyRows = listOf(row),
                blockedRows = emptyList(),
            ).prepareForReview()
            assertTrue(case.source, prepared.items.isEmpty())
            assertTrue(case.source, prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun titleCasedCollectiveAndMeasureGapsCannotProveTerminalContainers() {
        data class DeniedIdentity(val name: String, val container: String, val packaging: String)
        listOf(
            DeniedIdentity("Case of Cola", "can", "can"),
            DeniedIdentity("Crate of Acme water", "bottle", "bottle"),
            DeniedIdentity("Couple of Acme", "jar", "jar"),
            DeniedIdentity("Few of Acme", "box", "box"),
            DeniedIdentity("Dozen of Acme tablets", "can", "can"),
            DeniedIdentity("Strip of Acme tablets", "box", "box"),
        ).forEach { denied ->
            val source = "one ${denied.name} ${denied.container}"
            assertContainerRowBlocked(
                instruction = source,
                source = source,
                name = denied.name,
                packaging = denied.packaging,
            )
        }
    }

    @Test fun ownedIdentityContainerProofCannotCropInstructionSuffixOrPrefix() {
        val primary = "one Valley of Farms ghee jar"
        listOf(
            primary + " of 3",
            primary + " refill",
            "not " + primary,
        ).forEach { instruction ->
            assertContainerRowBlocked(
                instruction = instruction,
                source = primary,
                name = "Valley of Farms ghee",
                packaging = "jar",
            )
        }
    }

    @Test fun internalOfContainerProofAllowsOnlyClosedHorizontalConjunctionPrefix() {
        val primary = "one Valley of Farms ghee jar"
        listOf("one soap, and $primary", "one soap, aur $primary").forEach { instruction ->
            val base = readyPackRow(primary, "Valley of Farms ghee")
            val row = base.copy(
                item = base.item.copy(packaging = "jar"),
                evidence = base.evidence.copy(primary = sourceQuote(instruction, primary)),
            )
            assertEquals(primary, null, row.groundingIssue(instruction = instruction))
            assertTrue(primary, row.groundingIssue() != null)
        }

        listOf("not and $primary", "one soap,\n and $primary").forEach { instruction ->
            val base = readyPackRow(primary, "Valley of Farms ghee")
            val row = base.copy(
                item = base.item.copy(packaging = "jar"),
                evidence = base.evidence.copy(primary = sourceQuote(instruction, primary)),
            )
            assertTrue(primary, row.groundingIssue(instruction = instruction) != null)
        }
    }

    @Test fun independentlyBoundPackSizeMaySitBetweenCountAndContainer() {
        val source = "one 500 g jar yogurt"
        val row = readyPackRow(source, "yogurt", packValue = 500, packUnit = "g", packText = "500")
        assertEquals(null, row.copy(item = row.item.copy(name = "jar yogurt")).groundingIssue())
    }

    @Test fun verifiedGroupEachContainerCanProveMemberPackUnit() {
        val source = "yogurt"
        val row = readyPackRow(source, "yogurt", quantity = null, quantityText = null, quantityUnit = "unspecified")
        val groupSource = "one jar each"
        val eachSource = sourceQuote(groupSource, groupSource)
        val amountSource = sourceQuote(groupSource, groupSource)
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified", amountStatus = "each_verified", source = eachSource,
            header = null, memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal.ONE, "pack", "each"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal.ONE, "pack", "each"),
            ), packaging = null, total = null,
            each = GroceryIntentReviewV2GroupAmount(amountSource, "one", BigDecimal.ONE, "pack"),
        )
        assertEquals(null, row.groundingIssue(group))
        val headerGroup = group.copy(header = sourceQuote(groupSource, "jar"), packaging = "jar")
        assertEquals(null, row.groundingIssue(headerGroup))
    }

    @Test fun ambiguousDistantPackAndMeasureDoNotProveRetailContainer() {
        listOf("one samosa please add a pack" to "samosa", "one cup of yogurt" to "yogurt").forEach { (source, name) ->
            assertFalse(source, readyPackRow(source, name).groundingIssue() == null)
        }
    }

    @Test fun copiedLongClauseCannotTurnSubjectOrBundleCountIntoRetailCount() {
        listOf(
            Triple("two sets premium organic herbal bath soap bars", "sets premium organic herbal bath soap", "bar"),
            Triple("two children prefer fresh organic milk bottles", "children prefer fresh organic milk", "bottle"),
            Triple("two customers bought fresh organic milk bottles", "customers bought fresh organic milk", "bottle"),
            Triple("two patients require fresh organic milk bottles", "patients require fresh organic milk", "bottle"),
            Triple("two families share premium organic herbal bath soap bars", "families share premium organic herbal bath soap", "bar"),
            Triple("two boxes contain fresh organic milk bottles", "boxes contain fresh organic milk", "bottle"),
        ).forEach { (source, name, form) ->
            val base = readyPackRow(source, name, quantity = BigDecimal.valueOf(2), quantityText = "two")
            val row = base.copy(item = base.item.copy(packaging = form))
            val prepared = GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2", requiresReview = true, instruction = source,
                sourceItemCount = 1, readyRows = listOf(row), blockedRows = emptyList(),
            ).prepareForReview()
            assertTrue(source, prepared.items.isEmpty())
            assertTrue(source, prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun copiedInflectedClauseDoesNotCountPeopleAsContainers() {
        listOf("preferred", "shared", "sharing", "used", "loved", "choosing", "requested", "needed").forEach { verb ->
            val name = "customers $verb fresh organic milk"
            val source = "two $name bottles"
            val base = readyPackRow(source, name, quantity = BigDecimal.valueOf(2), quantityText = "two")
            val row = base.copy(item = base.item.copy(packaging = "bottle"))
            assertEquals(source, "retail_pack_unproven", row.retailPackEvidenceIssue(null, source))
        }
    }

    @Test fun exactLongProductIdentityBeforeTerminalContainerPreservesCountAndForm() {
        fun prepareLongBar(source: String, name: String, quantity: Int, quantityText: String): Pair<GroceryIntentReviewV2ReadyRow, ParsedItem> {
            val base = readyPackRow(
                source = source,
                name = name,
                quantity = BigDecimal.valueOf(quantity.toLong()),
                quantityText = quantityText,
                quantityUnit = "pack",
            )
            val row = base.copy(item = base.item.copy(packaging = "bar"))
            assertEquals(source, null, row.groundingIssue(instruction = source))
            val prepared = GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2",
                requiresReview = true,
                instruction = source,
                sourceItemCount = 1,
                readyRows = listOf(row),
                blockedRows = emptyList(),
            ).prepareForReview()
            assertTrue(source, prepared.excludedText.isEmpty())
            return row to prepared.items.single()
        }

        val (singleRow, single) = prepareLongBar(
            source = "one Acme Valley roasted cocoa almond protein bar",
            name = "Acme Valley roasted cocoa almond protein",
            quantity = 1,
            quantityText = "one",
        )
        assertEquals(BigDecimal.ONE, singleRow.item.quantity)
        assertEquals("pack", singleRow.item.quantityUnit)
        assertEquals("bar", singleRow.item.packaging)
        assertEquals(null, singleRow.item.packValue)
        assertEquals("unspecified", singleRow.item.packUnit)
        assertEquals(null, singleRow.item.packText)
        assertEquals(Quantity.Count(1), single.quantity)
        assertTrue(single.retailPackCount)
        assertTrue(single.query.contains("Acme Valley roasted cocoa almond protein"))
        assertTrue(single.query.contains("bar"))
        assertTrue(single.strictMatchPhrase.orEmpty().contains("bar"))

        val (pluralRow, plural) = prepareLongBar(
            source = "two Green Meadow pure unscented bath soap bars",
            name = "Green Meadow pure unscented bath soap",
            quantity = 2,
            quantityText = "two",
        )
        assertEquals(BigDecimal.valueOf(2), pluralRow.item.quantity)
        assertEquals("pack", pluralRow.item.quantityUnit)
        assertEquals("bar", pluralRow.item.packaging)
        assertEquals(null, pluralRow.item.packValue)
        assertEquals("unspecified", pluralRow.item.packUnit)
        assertEquals(null, pluralRow.item.packText)
        assertEquals(Quantity.Count(2), plural.quantity)
        assertTrue(plural.retailPackCount)
        assertTrue(plural.query.contains("Green Meadow pure unscented bath soap"))
        assertTrue(plural.query.contains("bar"))
        assertTrue(plural.strictMatchPhrase.orEmpty().contains("bar"))
    }

    @Test fun longTerminalContainerEvidenceRejectsAlteredNamesMeasurementsAndOpenTails() {
        val name = "Acme Valley roasted cocoa almond protein"
        val cases = listOf(
            "one Acme Valley roasted cocoa almond protein bar" to "Acme Valley roasted cocoa almond",
            "one Acme Valley roasted cocoa almond protein bar" to "Acme Valley roasted cocoa almond protein snack",
            "one 500 g Acme Valley roasted cocoa almond protein bar" to name,
            "one serving of Acme Valley roasted cocoa almond protein bar" to name,
            "one Acme Valley roasted cocoa almond protein with vitamins bar" to
                "Acme Valley roasted cocoa almond protein with vitamins",
            "one Acme Valley roasted cocoa almond protein of vanilla bar" to
                "Acme Valley roasted cocoa almond protein of vanilla",
            "one Acme Valley roasted cocoa almond protein for adults bar" to
                "Acme Valley roasted cocoa almond protein for adults",
            "one Acme Valley roasted cocoa almond protein and vanilla bar" to
                "Acme Valley roasted cocoa almond protein and vanilla",
            "one Acme Valley roasted cocoa almond protein bar, please order another" to name,
            "one Acme Valley roasted cocoa almond protein, bar" to name,
            "one Acme Valley roasted cocoa almond protein\nbar" to name,
            "one Acme Valley roasted cocoa almond protein bar and soap bar" to name,
        )
        cases.forEach { (source, candidateName) ->
            assertContainerRowBlocked(
                instruction = source,
                source = source,
                name = candidateName,
                packaging = "bar",
            )
        }

        val closedPrimary = "one Acme Valley roasted cocoa almond protein bar"
        listOf(
            "not $closedPrimary" to closedPrimary,
            "$closedPrimary of three" to closedPrimary,
            "Acme Valley roasted cocoa almond protein bar" to "Acme Valley roasted cocoa almond protein bar",
        ).forEach { (instruction, primary) ->
            assertContainerRowBlocked(
                instruction = instruction,
                source = primary,
                name = name,
                packaging = "bar",
            )
        }
    }

    @Test fun longLiteralRetailNamesSupportWordBoundariesAndTerminalForm() {
        val cases = listOf(
            // Five identity words is the lower bound; apostrophe and hyphen are internal name characters.
            Triple(
                "one Acme Valley Farmer's cocoa-almond protein bar",
                "Acme Valley Farmer's cocoa-almond protein",
                "bar",
            ),
            // Twelve identity words is the upper bound, and the model may retain the terminal form in its name.
            Triple(
                "one Alpine Meadow Pure Harvest roasted cocoa almond protein snack premium sea salt bar",
                "Alpine Meadow Pure Harvest roasted cocoa almond protein snack premium sea salt bar",
                "bar",
            ),
            Triple(
                "one Acme Valley roasted cocoa almond protein bar",
                "Acme Valley roasted cocoa almond protein bar",
                "bar",
            ),
        )

        cases.forEachIndexed { index, (source, name, packaging) ->
            val base = readyPackRow(source, name)
            val row = base.copy(item = base.item.copy(packaging = packaging))
            assertEquals("case $index", null, row.groundingIssue(instruction = source))
            val prepared = GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2",
                requiresReview = true,
                instruction = source,
                sourceItemCount = 1,
                readyRows = listOf(row),
                blockedRows = emptyList(),
            ).prepareForReview()
            assertTrue("case $index", prepared.excludedText.isEmpty())
            val item = prepared.items.single()
            assertTrue("case $index", item.retailPackCount)
            assertEquals(Quantity.Count(1), item.quantity)
            assertEquals("bar", row.item.packaging)
            assertTrue("case $index", item.query.contains(name, ignoreCase = true))
            assertTrue("case $index", item.strictMatchPhrase.orEmpty().contains("bar", ignoreCase = true))
        }
    }

    @Test fun longNominalIdentityDoesNotDoubleCountInternalContainerWords() {
        fun prepare(row: GroceryIntentReviewV2ReadyRow, instruction: String): ParsedItem {
            assertEquals("valid synthetic row", null, row.groundingIssue(instruction = instruction))
            val prepared = GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2",
                requiresReview = true,
                instruction = instruction,
                sourceItemCount = 1,
                readyRows = listOf(row),
                blockedRows = emptyList(),
            ).prepareForReview()
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
            return prepared.items.single()
        }

        val spraySource = "one Acme Valley gentle lavender room spray bottle, no added sugar"
        val sprayName = "Acme Valley gentle lavender room spray"
        val sprayBase = readyPackRow(spraySource, sprayName)
        val spray = prepare(sprayBase.copy(item = sprayBase.item.copy(
            packaging = "bottle", required = listOf("no_added_sugar"),
        )), spraySource)
        assertEquals(Quantity.Count(1), spray.quantity)
        assertTrue(spray.retailPackCount)
        assertTrue(spray.query.contains(sprayName))
        assertTrue(spray.query.contains("bottle"))
        assertTrue(spray.query.contains("no added sugar"))
        assertTrue(spray.strictMatchPhrase.orEmpty().contains("bottle"))

        val brushSource = "one Acme Valley bottle cleaning brush packet"
        val brushName = "Acme Valley bottle cleaning brush"
        val brushBase = readyPackRow(brushSource, brushName)
        val brush = prepare(brushBase.copy(item = brushBase.item.copy(packaging = "packet")), brushSource)
        assertEquals(Quantity.Count(1), brush.quantity)
        assertTrue(brush.retailPackCount)
        assertTrue(brush.query.contains(brushName))
        assertTrue(brush.query.contains("packet"))
        assertTrue(brush.strictMatchPhrase.orEmpty().contains("packet"))
    }

    @Test fun longNominalIdentityStillRejectsDroppedScopeAndCompetingForms() {
        val name = "Acme Valley gentle lavender room spray"
        listOf(
            "one Acme Valley gentle lavender room spray bottle" to "Acme Valley lavender room spray",
            "one Acme Valley gentle lavender room spray bottle jar" to name,
            "one 500 g Acme Valley gentle lavender room spray bottle" to name,
            "one Acme Valley gentle lavender room spray bottle for adults" to name,
            "one Acme Valley gentle lavender room spray and bottle" to name,
            // The catalogue name cannot claim both the internal product form and external container.
            "one Acme Valley gentle lavender room spray bottle" to "Acme Valley gentle lavender room spray bottle",
        ).forEach { (source, candidateName) ->
            assertContainerRowBlocked(source, source, candidateName, "bottle")
        }

        val croppedInstruction = "one Acme Valley gentle lavender room spray bottle"
        val croppedPrimary = croppedInstruction.removePrefix("one ")
        val croppedBase = readyPackRow(croppedPrimary, name, quantityText = "one")
        val croppedRow = croppedBase.copy(
            item = croppedBase.item.copy(packaging = "bottle"),
            evidence = croppedBase.evidence.copy(primary = sourceQuote(croppedInstruction, croppedPrimary)),
        )
        assertTrue(croppedRow.groundingIssue(instruction = croppedInstruction) != null)
        val croppedPrepared = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true,
            instruction = croppedInstruction, sourceItemCount = 1,
            readyRows = listOf(croppedRow), blockedRows = emptyList(),
        ).prepareForReview()
        assertTrue(croppedPrepared.items.isEmpty())

        val missingPropertySource = "$croppedInstruction, no added sugar"
        assertContainerRowBlocked(missingPropertySource, missingPropertySource, name, "bottle")
    }

    @Test fun longLiteralRetailNameRequiresOriginalMultiItemContextAndRejectsSwallowedTerms() {
        listOf(
            "one Acme Valley roasted cocoa almond protein serving bar" to
                "Acme Valley roasted cocoa almond protein serving",
            "one Acme Valley roasted cocoa almond protein spoon bar" to
                "Acme Valley roasted cocoa almond protein spoon",
            "one Acme Valley roasted cocoa almond protein set bar" to
                "Acme Valley roasted cocoa almond protein set",
            "one Acme Valley roasted cocoa almond protein two bar" to
                "Acme Valley roasted cocoa almond protein two",
            "one Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Juliet Kilo Lima Mike bar" to
                "Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Juliet Kilo Lima Mike",
        ).forEach { (source, name) ->
            assertContainerRowBlocked(source, source, name, "bar")
        }

        val primary = "one Acme Valley roasted cocoa almond protein bar"
        listOf(
            "one soap bar, Then $primary",
            "one soap bar; also $primary",
        ).forEach { instruction ->
            val base = readyPackRow(primary, "Acme Valley roasted cocoa almond protein")
            val row = base.copy(item = base.item.copy(packaging = "bar"),
                evidence = base.evidence.copy(primary = sourceQuote(instruction, primary)))
            assertEquals(instruction, null, row.groundingIssue(instruction = instruction))
            assertTrue("whole instruction is required", row.groundingIssue() != null)
        }
    }

    @Test fun longContainerIdentityMayRetainOneCommaDelimitedProtectedPropertyTailEndToEnd() {
        val source = "one Acme Valley roasted cocoa almond protein bar, no added sugar"
        val prepared = GroceryIntentReviewV2Response.parse(
            longContainerResponse(source), source,
        ).draft.prepareForReview()

        assertTrue(prepared.excludedText.isEmpty())
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals(Quantity.Count(1), item.quantity)
        assertTrue(item.retailPackCount)
        assertTrue(item.query.contains("Acme Valley roasted cocoa almond protein"))
        assertTrue(item.query.contains("no added sugar"))
        assertTrue(item.query.contains("bar"))
        assertTrue(item.strictMatchPhrase.orEmpty().contains("no added sugar"))

        val single = RecommendationCandidate(
            "acme-single-bar", "Acme Valley Roasted Cocoa Almond Protein Bar No Added Sugar",
        )
        val checkedTitleOnly = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(single)))
        assertTrue(isSwiggyCandidateAllowed(checkedTitleOnly, single))
        assertTitleOnlyCandidateHeld(checkedTitleOnly, single)
        assertUnqualifiedCountNeedsReview(checkedTitleOnly, single, 1)
        val typedSingle = withSyntheticCatalogueQuantity(single, "40 g")
        val checkedSingle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(typedSingle)))
        assertReviewedRetailProposal(checkedSingle, typedSingle, 1)

        val unrequestedBundle = RecommendationCandidate(
            "acme-two-bars", "Acme Valley Roasted Cocoa Almond Protein Bar Pack of 2 No Added Sugar",
        )
        val checkedBundle = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(unrequestedBundle)))
        assertTrue(isSwiggyCandidateAllowed(checkedBundle, unrequestedBundle))
        assertFalse(isSwiggyCandidateCountCompatible(checkedBundle, unrequestedBundle))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(checkedBundle, unrequestedBundle)
        }

        val contradictory = RecommendationCandidate(
            "acme-added-sugar", "Acme Valley Roasted Cocoa Almond Protein Bar With Added Sugar",
        )
        val checkedContradictory = assessSwiggyIntentQuantity(item,
            Recommendations(candidates = listOf(contradictory)))
        assertFalse(isSwiggyCandidateAllowed(checkedContradictory, contradictory))
        // Quantity compatibility is a separate gate, not dietary permission.
        assertTrue(listOf(contradictory).filter {
            isSwiggyCandidateCountCompatible(checkedContradictory, it) &&
                isSwiggyCandidateAllowed(checkedContradictory, it)
        }.isEmpty())
    }

    @Test fun requestedTwoLongNominalBarsCannotSilentlySelectBundleCountRole() {
        val source = "two Acme Valley roasted cocoa almond protein bars, no added sugar"
        val prepared = GroceryIntentReviewV2Response.parse(
            longContainerResponse(source, quantity = 2, quantityText = "two"), source,
        ).draft.prepareForReview()
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals(Quantity.Count(2), item.quantity)
        assertTrue(item.retailPackCount)

        val twoBarBundle = RecommendationCandidate(
            "acme-two-bar-bundle", "Acme Valley Roasted Cocoa Almond Protein Bar Pack of 2 No Added Sugar",
        )
        val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(twoBarBundle)))
        assertTrue(isSwiggyCandidateAllowed(checked, twoBarBundle))
        assertUnqualifiedCountNeedsReview(checked, twoBarBundle, 2)
    }

    @Test fun longContainerPropertyTailNearMissesDoNotBecomeReadyRows() {
        val nominal = "Acme Valley roasted cocoa almond protein bar"
        listOf(
            "one $nominal, no added sugar if available",
            "one $nominal, no added sugar or gluten free",
            "one $nominal, almost no added sugar",
            "one $nominal, no added sugar, two more bars",
            "one $nominal and bottle, no added sugar",
            *listOf("chemicals", "preservatives", "artificial colours", "artificial flavours")
                .map { "one $nominal, no $it if available" }.toTypedArray(),
        ).forEach { source ->
            val prepared = GroceryIntentReviewV2Response.parse(longContainerResponse(source), source)
                .draft.prepareForReview()
            assertTrue("near-miss must remain out of the basket: $source", prepared.items.isEmpty())
            assertTrue("near-miss should remain visible for review: $source", prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun longContainerPropertyGrammarDoesNotReplaceItsPreservationChecks() {
        val nominal = "Acme Valley roasted cocoa almond protein"
        val cases = listOf(
            Triple("organic", listOf("organic"), emptyList<String>()),
            Triple("low fat", listOf("low_fat"), emptyList<String>()),
            Triple("unsweetened", listOf("unsweetened"), emptyList<String>()),
            Triple("sugar free", listOf("sugar_free"), emptyList<String>()),
            Triple("zero sugar", listOf("sugar_free"), emptyList<String>()),
            Triple("0% sugar", listOf("sugar_free"), emptyList<String>()),
            Triple("no palm oil", emptyList<String>(), listOf("palm oil")),
            Triple("no chemicals", emptyList<String>(), listOf("chemicals")),
            Triple("no preservatives", emptyList<String>(), listOf("preservatives")),
            Triple("no artificial colours", emptyList<String>(), listOf("artificial colours")),
            Triple("no artificial flavours", emptyList<String>(), listOf("artificial flavours")),
        )
        for ((property, required, excluded) in cases) {
            val source = "one $nominal bar, $property"
            val base = readyPackRow(source = source, name = nominal, quantity = BigDecimal.ONE,
                quantityText = "one", quantityUnit = "pack")
            val row = base.copy(item = base.item.copy(packaging = "bar", required = required, excluded = excluded))
            assertEquals(property, null, row.groundingIssue(instruction = source))
            assertTrue(property, row.copy(item = row.item.copy(required = emptyList(), excluded = emptyList()))
                .groundingIssue(instruction = source) != null)
        }
    }

    @Test fun longContainerTailUnitRepresentationsAgreeButTamperedMetadataCannotAuthorize() {
        val source = "one Acme Valley roasted cocoa almond protein bar, no added sugar"
        val countForm = GroceryIntentReviewV2Response.parse(
            longContainerResponse(source, quantityUnit = "count"), source,
        ).draft.prepareForReview()
        val packForm = GroceryIntentReviewV2Response.parse(longContainerResponse(source), source)
            .draft.prepareForReview()
        assertTrue(countForm.excludedText.isEmpty())
        assertEquals(Quantity.Count(1), countForm.items.single().quantity)
        assertFalse(countForm.items.single().retailPackCount)
        assertTrue(packForm.items.single().retailPackCount)
        val titleOnly = RecommendationCandidate("single", "Acme Valley Roasted Cocoa Almond Protein Bar No Added Sugar")
        val bundle = titleOnly.copy(spinId = "bundle", subtitle = "Pack of 2")
        val single = withSyntheticCatalogueQuantity(titleOnly, "40 g")
        for (item in listOf(countForm.items.single(), packForm.items.single())) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(single, bundle)))
            assertTrue(isSwiggyCandidateAllowed(checked, single))
            if (item.retailPackCount) {
                val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(titleOnly)))
                assertTitleOnlyCandidateHeld(titleOnlyChecked, titleOnly)
                assertUnqualifiedCountNeedsReview(titleOnlyChecked, titleOnly, 1)
                assertReviewedRetailProposal(checked, single, 1)
            } else {
                assertFalse(isSwiggyCandidateCountCompatible(checked, single))
                assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(checked, single) }
            }
            assertFalse(isSwiggyCandidateCountCompatible(checked, bundle))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(checked, bundle) }
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(checked.copy(retailPackCount = !checked.retailPackCount), single)
            }
        }

        listOf(
            longContainerResponse(source, quantityOrigin = "not_stated"),
            longContainerResponse(source, amountRoleStatus = "not_stated"),
        ).forEach { tampered ->
            assertThrows(RuntimeException::class.java) {
                GroceryIntentReviewV2Response.parse(tampered, source)
            }
        }
        val wrongAmount = GroceryIntentReviewV2Response.parse(
            longContainerResponse(source, quantity = 2), source,
        ).draft.prepareForReview()
        assertTrue(wrongAmount.items.isEmpty())
        assertTrue(wrongAmount.excludedText.isNotEmpty())
    }
}

private fun longContainerResponse(
    instruction: String,
    quantity: Int = 1,
    quantityText: String = "one",
    quantityUnit: String = "pack",
    quantityOrigin: String = "stated",
    amountRoleStatus: String = "matched",
): String {
    fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
    val source = instruction
    val name = "Acme Valley roasted cocoa almond protein"
    val start = instruction.indexOf(source)
    require(start >= 0)
    val row = """{"row_index":0,"item":{"name":${jsonString(name)},"quantity":$quantity,"quantity_unit":${jsonString(quantityUnit)},"pack_value":null,"pack_unit":"unspecified","packaging":"bar","required":["no_added_sugar"],"excluded":[],"source_text":${jsonString(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${jsonString(quantityText)},"pack_text":null},"evidence":{"primary":{"text":${jsonString(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"supporting":[]},"quantity_origin":${jsonString(quantityOrigin)},"amount_role_status":${jsonString(amountRoleStatus)}}"""
    return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":1,"ready_rows":[$row],"blocked_rows":[]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
}

private fun prepareReadyContainerItem(
    source: String,
    name: String,
    packaging: String?,
    quantity: Int = 1,
    quantityUnit: String = "pack",
    quantityText: String = "one",
    packValue: Int? = null,
    packUnit: String = "unspecified",
    packText: String? = null,
): ParsedItem {
    val item = GroceryIntentReviewV2Item(
        name = name,
        quantity = BigDecimal.valueOf(quantity.toLong()),
        quantityUnit = quantityUnit,
        packValue = packValue?.let { BigDecimal.valueOf(it.toLong()) },
        packUnit = packUnit,
        packaging = packaging,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = quantityText,
        packText = packText,
    )
    val evidence = GroceryIntentReviewV2Evidence(
        primary = sourceQuote(source, source),
        supporting = emptyList(),
    )
    val row = GroceryIntentReviewV2ReadyRow(
        rowIndex = 0,
        item = item,
        evidence = evidence,
        quantityOrigin = "stated",
        amountRoleStatus = "matched",
    )
    assertEquals("Synthetic row grounding", null, row.groundingIssue(instruction = source))
    return GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2",
        requiresReview = true,
        instruction = source,
        sourceItemCount = 1,
        readyRows = listOf(row),
        blockedRows = emptyList(),
    ).prepareForReview().items.single()
}

private fun withSyntheticCatalogueQuantity(
    candidate: RecommendationCandidate,
    size: String,
): RecommendationCandidate {
    val skuId = "synthetic-sku-${candidate.spinId}"
    return candidate.copy(
        skuId = skuId,
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = candidate.spinId,
            skuId = skuId,
            fields = CatalogueQuantityFields(variantName = size),
        ),
    )
}

private fun withSyntheticCataloguePackSize(
    candidate: RecommendationCandidate,
    packSize: String,
): RecommendationCandidate {
    val skuId = "synthetic-sku-${candidate.spinId}"
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

private fun withSyntheticCatalogueRelation(
    candidate: RecommendationCandidate,
    relation: String,
): RecommendationCandidate {
    val skuId = "synthetic-sku-${candidate.spinId}"
    return candidate.copy(
        skuId = skuId,
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = candidate.spinId,
            skuId = skuId,
            fields = CatalogueQuantityFields(variantName = relation, quantityDescription = relation),
        ),
    )
}

private fun assertTitleOnlyCandidateHeld(item: ParsedItem, candidate: RecommendationCandidate) {
    assertTrue(swiggyCountResolutionDiagnostic(item, candidate),
        resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
}

private fun legacyContainerItem(
    rawText: String,
    query: String,
    quantity: Int,
    retailPackCount: Boolean,
): ParsedItem = ParsedItem(
    rawText = rawText,
    query = query,
    quantity = Quantity.Count(quantity),
    strictMatchPhrase = null,
    retailPackCount = retailPackCount,
    requiresIntentQuantityProof = false,
)

private fun assertLegacyContainerCandidateRejected(item: ParsedItem, candidate: RecommendationCandidate) {
    val checked = assessSwiggyIntentQuantity(item, Recommendations(candidates = listOf(candidate)))
    assertFalse("${candidate.label} must not count as requested container quantity", isSwiggyCandidateCountCompatible(checked, candidate))
    assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(checked, candidate) }
}

private fun assertLegacyContainerCountCannotActWithoutSourceProof(item: ParsedItem, candidate: RecommendationCandidate) {
    val count = (item.quantity as? Quantity.Count)?.n ?: throw AssertionError("fixture must carry a numeric count")
    val result = resolveSwiggyCartQuantity(item, candidate)
    assertFalse(swiggyCountResolutionDiagnostic(item, candidate), result is SwiggyCartQuantityResolution.Exact)
    when (result) {
        is SwiggyCartQuantityResolution.Held -> {
            assertFalse(swiggyCountResolutionDiagnostic(item, candidate), isSwiggyCandidateCountCompatible(item, candidate))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, candidate) }
        }
        is SwiggyCartQuantityResolution.RetailProposal -> {
            assertTrue(swiggyCountResolutionDiagnostic(item, candidate), result.contents is SwiggyRetailOfferContents.DisplayOnly)
            assertReviewedRetailProposal(item, candidate, count)
        }
        is SwiggyCartQuantityResolution.Exact -> throw AssertionError("source-less flag must not prove an exact container count")
    }
}

private fun sourceQuote(instruction: String, text: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(text)
    require(start >= 0)
    return GroceryIntentReviewV2Quote(text, start, start + text.length)
}

private fun readyPackRow(
    source: String,
    name: String,
    quantity: BigDecimal? = BigDecimal.ONE,
    quantityText: String? = source.substringBefore(' '),
    quantityUnit: String = "pack",
    packValue: Int? = null,
    packUnit: String = "unspecified",
    packText: String? = null,
): GroceryIntentReviewV2ReadyRow =
    GroceryIntentReviewV2ReadyRow(
        rowIndex = 0,
        item = GroceryIntentReviewV2Item(
            name = name, quantity = quantity, quantityUnit = quantityUnit,
            packValue = packValue?.let { BigDecimal.valueOf(it.toLong()) }, packUnit = packUnit, packaging = null,
            required = emptyList(), excluded = emptyList(), sourceText = source,
            supportingText = emptyList(), needsClarification = false,
            quantityText = quantityText, packText = packText,
        ),
        evidence = GroceryIntentReviewV2Evidence(sourceQuote(source, source), emptyList()),
        quantityOrigin = if (quantity == null) "not_stated" else "stated",
        amountRoleStatus = if (quantity == null && packValue == null) "not_stated" else "matched",
    )

private fun assertContainerRowBlocked(
    instruction: String,
    source: String,
    name: String,
    packaging: String,
) {
    val base = readyPackRow(source, name)
    val row = base.copy(
        item = base.item.copy(packaging = packaging),
        evidence = base.evidence.copy(primary = sourceQuote(instruction, source)),
    )
    assertTrue("$source; retail=${row.retailPackEvidenceIssue(null, instruction)}; " +
        "local=${row.localQuantityEvidence() != null}; generic=${row.genericContainerRange() != null}",
        row.groundingIssue(instruction = instruction) != null)
    val prepared = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2",
        requiresReview = true,
        instruction = instruction,
        sourceItemCount = 1,
        readyRows = listOf(row),
        blockedRows = emptyList(),
    ).prepareForReview()
    assertTrue(source, prepared.items.isEmpty())
    assertTrue(source, prepared.excludedText.isNotEmpty())
}
