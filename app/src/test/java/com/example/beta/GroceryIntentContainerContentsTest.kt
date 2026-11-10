package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentContainerContentsTest {
    @Test
    fun v1ContainerContentsCasesMatchSharedBackendAcceptanceFixture() {
        val cases = loadFixture()
        assertEquals("Fixture must exercise both dispositions", setOf(false, true), cases.map { it.accepted }.toSet())

        cases.forEach { case ->
            val response = GroceryIntentResponse.parse(responseBody(case), case.source)
            val preparation = runCatching { response.draft.prepareForReview() }
            if (case.accepted) {
                val prepared = preparation.getOrThrow()
                assertEquals("${case.id}: expected one prepared item", 1, prepared.items.size)
                assertTrue("${case.id}: accepted item should not require extra text review", prepared.unresolvedText.isEmpty())
                assertTrue("${case.id}: accepted item should not have excluded source text", prepared.excludedText.isEmpty())
                assertEquals(null, swiggyMcpItemValidationMessage(case.source, prepared.items))

                if (case.packUnit == "piece") {
                    val item = prepared.items.single()
                    assertEquals("${case.id}: inner pieces must not become purchase quantity", Quantity.Count(case.quantity), item.quantity)
                    assertTrue("${case.id}: explicit outer container should remain a retail-package count", item.retailPackCount)
                    assertFalse("${case.id}: V1 package contents are not logical-piece purchase intent", item.logicalPieceCount)
                }
            } else {
                val prepared = preparation.getOrNull()
                val reviewReady = prepared != null && prepared.items.isNotEmpty() && prepared.unresolvedText.isEmpty() &&
                    swiggyMcpItemValidationMessage(case.source, prepared.items) == null
                assertFalse("${case.id}: backend-held row must not become Android review-ready", reviewReady)
            }
        }
    }

    @Test
    fun sourceProvedOuterCountProjectsAsRetailPacksAndKeepsCartUnitsAtTwo() {
        val source = "two packets tea bags, 25 bags"
        listOf("count", "piece").forEach { sourceQuantityUnit ->
            val case = FixtureCase(
                id = "projection-$sourceQuantityUnit",
                source = source,
                name = "tea bags",
                quantity = 2,
                quantityUnit = sourceQuantityUnit,
                packValue = 25,
                packUnit = "piece",
                accepted = true,
            )
            val parsed = GroceryIntentResponse.parse(responseBody(case), source)
                .draft.prepareForReview().items.single()

            assertEquals(Quantity.Count(2), parsed.quantity)
            assertTrue("$sourceQuantityUnit source count should retain outer-package role", parsed.retailPackCount)
            assertFalse("$sourceQuantityUnit outer-package count is not a loose logical-piece count", parsed.logicalPieceCount)

            val spinId = "tea-bag-packets-$sourceQuantityUnit"
            val skuId = "sku-tea-bag-packets-$sourceQuantityUnit"
            val candidate = RecommendationCandidate(
                spinId = spinId,
                label = "Tea Bags Packet of 25 Pieces",
                skuId = skuId,
                catalogueQuantity = CatalogueQuantityEvidence(
                    version = 1,
                    source = "search_variation",
                    spinId = spinId,
                    skuId = skuId,
                    fields = CatalogueQuantityFields(variantName = "Packet of 25 Pieces"),
                ),
            )
            assertEquals("$sourceQuantityUnit requests two outer packets, not 50 loose pieces", 2,
                swiggyRequestedCartQuantity(parsed, candidate))
        }
    }

    @Test
    fun copiedOrForgedSourceReceiptChangesCannotAuthorizeCartUnits() {
        val item = sourceContentsItem()
        val proof = requireNotNull(item.containerContentsProof)
        val candidate = contentsCandidate()
        val changedRows = listOf(
            item.copy(quantity = Quantity.Count(3)),
            item.copy(rawText = "${item.rawText}, 25 milk"),
            item.copy(query = item.query.replace("25", "24")),
            item.copy(strictMatchPhrase = "tea bags 24 pieces"),
            item.copy(retailPackCount = false),
            item.copy(logicalPieceCount = true),
            item.copy(containerContentsProof = proof.copy(query = "tea bags 24 pieces")),
            item.copy(containerContentsProof = proof.copy(container = "box")),
            item.copy(containerContentsProof = proof.copy(outer = proof.outer.copy(value = BigDecimal("3")))),
            item.copy(containerContentsProof = proof.copy(outer = proof.outer.copy(atom = "three"))),
            item.copy(containerContentsProof = proof.copy(outer = proof.outer.copy(startUtf16 = proof.outer.startUtf16 + 1))),
            item.copy(containerContentsProof = proof.copy(inner = proof.inner.copy(value = BigDecimal("24")))),
            item.copy(containerContentsProof = proof.copy(inner = proof.inner.copy(atom = "24"))),
            item.copy(containerContentsProof = proof.copy(inner = proof.inner.copy(endUtf16 = proof.inner.endUtf16 - 1))),
            item.copy(query = "tea bags", strictMatchPhrase = "tea bags", containerContentsProof = proof.copy(query = "tea bags")),
        )

        changedRows.forEachIndexed { index, changed ->
            assertContentsHeld("tampered source receipt $index", changed, candidate)
        }
    }

    @Test
    fun unrelatedOversizedSiblingCountIsNotCoveredByOwnedInnerSpan() {
        val row = FixtureCase(
            id = "sibling-count",
            source = "two packets tea bags, 25 bags",
            name = "tea bags",
            quantity = 2,
            quantityUnit = "count",
            packValue = 25,
            packUnit = "piece",
            accepted = true,
        )
        val fullInstruction = "${row.source}, 25 milk"
        val preparation = GroceryIntentResponse.parse(responseBody(row, fullInstruction), fullInstruction)
            .draft.prepareForReview()
        val teaRow = preparation.items.single()
        assertNotNull("the tea contents proof itself should remain valid", teaRow.validContainerContentsProof())
        val message = swiggyMcpItemValidationMessage(fullInstruction, preparation.items)
        assertNotNull("a proved tea-bag count must not exempt the sibling milk count", message)
        assertTrue(requireNotNull(message).contains("quantity 20"))
    }

    @Test
    fun onlyCompleteSkuBoundMatchingCatalogueContentsAuthorizeOuterCount() {
        val item = sourceContentsItem()
        assertEquals(2, swiggyRequestedCartQuantity(item, contentsCandidate()))

        val heldCandidates = listOf(
            "missing metadata" to RecommendationCandidate("spin-tea", "Tea Bags Packet of 25 Pieces", skuId = "sku-tea"),
            "sku metadata mismatch" to contentsCandidate(evidenceSku = "sku-other"),
            "wrong inner count" to contentsCandidate(CatalogueQuantityFields(variantName = "Packet of 24 Pieces")),
            "wrong outer container" to contentsCandidate(CatalogueQuantityFields(variantName = "Box of 25 Pieces")),
            "approximate relationship" to contentsCandidate(CatalogueQuantityFields(variantName = "Packet of approximately 25 Pieces")),
            "approximate whole claim" to contentsCandidate(CatalogueQuantityFields(variantName = "approximately Packet of 25 Pieces")),
            "negated relationship" to contentsCandidate(CatalogueQuantityFields(variantName = "not Packet of 25 Pieces")),
            "absent contents" to contentsCandidate(CatalogueQuantityFields(variantName = "No packet of 25 Pieces")),
            "title has extra container outside literal identity" to contentsCandidate(
                title = "Tea Bags Box Packet of 25 Pieces",
            ),
            "plural identity participates in original relation" to contentsCandidate(title = "Tea Bags of 25 Pieces"),
            "identity participates in original parenthesized size" to contentsCandidate(title = "Tea Bags (25 Pieces)"),
            "identity participates in original numeric size" to contentsCandidate(title = "Tea Bags 25 Pieces"),
            "punctuation cannot hide relation after identity" to contentsCandidate(title = "Tea Bags - of 25 Pieces"),
            "relation before identity cannot disappear" to contentsCandidate(title = "25 Pieces in Tea Bags"),
            "corroborating approximate field" to contentsCandidate(CatalogueQuantityFields(
                variantName = "Packet of 25 Pieces", subTitle = "approximately 25 pieces")),
            "corroborating qualified title" to contentsCandidate(title = "Tea Bags Packet of 25 Pieces maximum"),
            "split-only metadata" to contentsCandidate(CatalogueQuantityFields(
                variantName = "Packet", quantityDescription = "25 Pieces",
            )),
        )
        heldCandidates.forEach { (label, candidate) -> assertContentsHeld(label, item, candidate) }
    }

    @Test
    fun bareMilkPieceCountDoesNotGainPackageAuthorityFromBareCataloguePieces() {
        val parsed = com.example.beta.automation.InstructionParser.parse("2 milk").single()
        assertEquals(Quantity.Count(2), parsed.quantity)
        assertFalse(parsed.retailPackCount)
        assertFalse(parsed.logicalPieceCount)
        assertContentsHeld(
            "bare milk quantity against bare two-piece catalogue claim",
            parsed,
            RecommendationCandidate(
                spinId = "spin-milk",
                label = "Milk 2 Pieces",
                skuId = "sku-milk",
                catalogueQuantity = CatalogueQuantityEvidence(
                    version = 1,
                    source = "search_variation",
                    spinId = "spin-milk",
                    skuId = "sku-milk",
                    fields = CatalogueQuantityFields(variantName = "2 Pieces"),
                ),
            ),
        )
        val modelPiece = GroceryIntentItem("milk", BigDecimal("2"), "piece", null, "unspecified",
            emptyList(), emptyList(), "2 milk", false).toParsedItem()
        assertFalse(modelPiece.logicalPieceCount)
        assertFalse(modelPiece.retailPackCount)
        assertContentsHeld("model piece enum is not source proof", modelPiece,
            RecommendationCandidate("milk-two", "Milk 2 Pieces"))
    }

    @Test fun nominalOwnershipNeverComesFromTheOuterContainerMarker() {
        val item = sourceContentsItem()
        val proof = requireNotNull(item.containerContentsProof)
        assertTrue(sourceContainerContentsRelation(item.rawText, proof.outer.startUtf16, proof.outer.endUtf16,
            proof.inner.startUtf16, proof.inner.endUtf16, literalIdentity = "tea bags"))
        assertFalse(sourceContainerContentsRelation(item.rawText, proof.outer.startUtf16, proof.outer.endUtf16,
            proof.inner.startUtf16, proof.inner.endUtf16, literalIdentity = "packets"))
    }

    @Test
    fun v2SourceContentsKeepOuterCartUnitsAcrossCountPackAndPieceEnums() {
        val cases = listOf(
            V2ContentsCase("two packets tea bags, 25 bags", "tea bags", 2, "pack", "two", 25, "25", "packet", "Packet of 25 Pieces"),
            V2ContentsCase("two packs of three pieces bottle brush", "bottle brush", 2, "count", "two", 3, "three", "pack", "Pack of 3 Pieces"),
            V2ContentsCase("3 boxes of 6 biscuits", "biscuits", 3, "piece", "3", 6, "6", "box", "Box of 6 Pieces"),
        )

        var index = 0
        val failures = mutableListOf<String>()
        cases.forEach { sourceCase ->
            listOf("count", "pack", "piece").forEach { quantityUnit ->
                val case = sourceCase.copy(quantityUnit = quantityUnit)
                var phase = "prepare"
                val failure = runCatching {
                    val prepared = prepareV2Contents(case)
                    check(prepared.items.size == 1 && prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
                    phase = "source proof"
                    val item = prepared.items.single()
                    check(item.rawText == case.source && item.query.contains(case.name))
                    val proof = requireNotNull(item.validContainerContentsProof())
                    check(proof.container == case.container && proof.outer.value.compareTo(case.outer.toBigDecimal()) == 0 &&
                        proof.inner.value.compareTo(case.inner.toBigDecimal()) == 0)
                    check(item.retailPackCount && !item.logicalPieceCount)
                    phase = "catalogue resolution"
                    val candidate = v2ContentsCandidate(case, "sku-$index")
                    val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                    val analysis = swiggyCatalogueContents(candidate, item.containerContentsLiteralIdentity())
                    val resolution = resolveSwiggyCartQuantity(assessed, candidate)
                    check(resolution == SwiggyCartQuantityResolution.Exact(case.outer)) {
                        "resolution=${resolution::class.simpleName}, issue=${analysis.issue}, contentsPresent=${analysis.contents != null}, " +
                            "candidateAllowed=${isSwiggyCandidateAllowed(assessed, candidate)}, " +
                            "literalPresent=${item.containerContentsLiteralIdentity() != null}, proofValid=${item.validContainerContentsProof() != null}"
                    }
                    check(swiggyRequestedCartQuantity(assessed, candidate) == case.outer)
                }.exceptionOrNull()
                if (failure != null) {
                    val detail = failure.message?.takeIf { it.startsWith("resolution=") }
                    failures += "case=$index unit=$quantityUnit phase=$phase${detail?.let { " $it" } ?: " failure=${failure::class.simpleName}"}"
                }
                index += 1
            }
        }
        assertTrue("V2 contents matrix failures: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun v2ContentsProofCopiesAndCatalogueControlsFailClosed() {
        val case = V2ContentsCase("two packs of three pieces bottle brush", "bottle brush", 2, "pack", "two", 3,
            "three", "pack", "Pack of 3 Pieces")
        val item = prepareV2ContentsDirect(case).items.single()
        val proof = requireNotNull(item.validContainerContentsProof())
        val good = v2ContentsCandidate(case, "sku-brush")
        val goodAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(good)))
        val goodResolution = resolveSwiggyCartQuantity(goodAssessed, good)
        val goodAnalysis = swiggyCatalogueContents(good, item.containerContentsLiteralIdentity())
        assertEquals("resolution=${goodResolution::class.simpleName}, issue=${goodAnalysis.issue}, " +
            "contentsPresent=${goodAnalysis.contents != null}, candidateAllowed=${isSwiggyCandidateAllowed(goodAssessed, good)}, " +
            "literalPresent=${item.containerContentsLiteralIdentity() != null}, proofValid=${item.validContainerContentsProof() != null}",
            SwiggyCartQuantityResolution.Exact(2), goodResolution)

        listOf(
            item.copy(containerContentsProof = proof.copy(query = "bottle brush 6 pieces")),
            item.copy(containerContentsProof = proof.copy(container = "box")),
            item.copy(containerContentsProof = proof.copy(outer = proof.outer.copy(value = BigDecimal("3")))),
            item.copy(containerContentsProof = proof.copy(inner = proof.inner.copy(value = BigDecimal("6")))),
            item.copy(containerContentsProof = null),
        ).forEachIndexed { index, mutated ->
            val assessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(good)))
            assertTrue("tampered/missing V2 proof $index must be held",
                resolveSwiggyCartQuantity(assessed, good) is SwiggyCartQuantityResolution.Held)
        }

        val held = listOf(
            "missing typed evidence" to RecommendationCandidate("spin-brush", "Bottle Brush Pack of 3 Pieces", skuId = "sku-brush"),
            "wrong source SKU" to v2ContentsCandidate(case, "sku-brush", evidenceSku = "sku-other"),
            "wrong inner amount" to v2ContentsCandidate(case.copy(relation = "Pack of 4 Pieces"), "sku-brush"),
            "wrong outer container" to v2ContentsCandidate(case.copy(relation = "Box of 3 Pieces"), "sku-brush"),
        )
        held.forEach { (label, candidate) ->
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("$label must not authorize a conversion",
                resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Held)
        }

        val titleOnly = RecommendationCandidate("spin-brush-title", "Bottle Brush Pack of 3 Pieces", skuId = "sku-title")
        val titleAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly)))
        assertTrue("legacy title-only bottle-brush contents must remain held",
            resolveSwiggyCartQuantity(titleAssessed, titleOnly) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun v2GenericPurchaseUnitProjectionPreservesSourceAndDietObligations() {
        val case = V2ContentsCase(
            source = "two packets organic tea bags, 25 bags",
            name = "organic tea bags",
            outer = 2,
            quantityUnit = "pack",
            quantityText = "two",
            inner = 25,
            innerText = "25",
            container = "packet",
            relation = "Packet of 25 Pieces",
            required = emptyList(),
        )
        val item = prepareV2ContentsDirect(case).items.single()
        val proof = requireNotNull(item.validContainerContentsProof())
        assertEquals(item.query, item.strictMatchPhrase)
        assertTrue("source diet qualifier remains query evidence", item.query.contains("organic"))
        assertFalse("the generic purchase tag is not a mandatory title word", item.query.contains("packet"))
        assertEquals("the original container remains source evidence", "packet", proof.container)
        assertEquals("the full literal nominal remains available for contents analysis",
            "organic tea bags", item.containerContentsLiteralIdentity())
        assertEquals("the projection must not rewrite the query", item.query, proof.query)

        val candidate = v2ContentsCandidate(case, "sku-organic-tea")
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(assessed, candidate))

        val missingOuterForm = RecommendationCandidate(
            spinId = "spin-inner-only",
            label = "Organic Tea Bags 25 Pieces",
            skuId = "sku-inner-only",
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = "spin-inner-only",
                skuId = "sku-inner-only",
                fields = CatalogueQuantityFields(variantName = "25 Pieces"),
            ),
        )
        val missingAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(missingOuterForm)))
        assertReviewedRetailProposal(missingAssessed, missingOuterForm, 2)
        assertFalse("inner pieces without a full provider relation cannot authorize Exact",
            resolveSwiggyCartQuantity(missingAssessed, missingOuterForm) is SwiggyCartQuantityResolution.Exact)

        val missingDiet = RecommendationCandidate(
            spinId = "spin-no-organic",
            label = "Tea Bags Packet of 25 Pieces",
            skuId = "sku-no-organic",
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = "spin-no-organic",
                skuId = "sku-no-organic",
                fields = CatalogueQuantityFields(
                    variantName = case.relation,
                    quantityDescription = case.relation,
                ),
            ),
        )
        val missingDietAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(missingDiet)))
        assertTrue("source-required organic identity must not be dropped from catalogue matching",
            resolveSwiggyCartQuantity(missingDietAssessed, missingDiet) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun v2ProofBoundNominalRejectsRepeatedSuffixExtraTermsAndChangedSource() {
        val case = V2ContentsCase("two packets tea bags, 25 bags", "tea bags packet", 2, "pack", "two", 25,
            "25", "packet", "Packet of 25 Pieces")
        val item = prepareV2Contents(case).items.single()
        val proof = requireNotNull(item.validContainerContentsProof())
        val candidate = v2ContentsCandidate(case, "sku-tea-packets")

        val amountSuffix = " ${proof.inner.value.stripTrailingZeros().toPlainString()} pieces"
        assertTrue("fixture query should end in the proof-bound amount", item.query.endsWith(amountSuffix))
        val nominal = item.query.removeSuffix(amountSuffix)

        fun assertChangedQueryHeld(label: String, query: String) {
            val altered = item.copy(
                query = query,
                strictMatchPhrase = query,
                containerContentsProof = proof.copy(query = query),
            )
            assertNull("$label should have no complete source literal identity",
                altered.containerContentsLiteralIdentity())
            val assessed = assessSwiggyIntentQuantity(altered, Recommendations(listOf(candidate)))
            assertTrue("$label should not preserve source nominal ownership",
                resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Held)
        }
        listOf(
            "repeated container suffix" to "$nominal packet packet$amountSuffix",
            "extra nominal word" to "$nominal fresh$amountSuffix",
            "numeric tag" to "$nominal 7$amountSuffix",
            "relationship tag" to "$nominal with$amountSuffix",
        ).forEach { (label, changedNominal) -> assertChangedQueryHeld(label, changedNominal) }

        val changedRaw = item.copy(rawText = "${item.rawText} fresh")
        val changedAssessed = assessSwiggyIntentQuantity(changedRaw, Recommendations(listOf(candidate)))
        assertTrue("changed source text should invalidate the bound proof",
            resolveSwiggyCartQuantity(changedAssessed, candidate) is SwiggyCartQuantityResolution.Held)

        assertEquals("the original compound nominal remains intact before suffix projection",
            "tea bags", item.containerContentsLiteralIdentity())
    }

    @Test
    fun croppedV2PrimaryCannotClaimContentsFromTheWholeInstruction() {
        val case = V2ContentsCase("two packets tea bags, 25 bags", "tea bags", 2, "pack", "two", 25,
            "25", "packet", "Packet of 25 Pieces")
        val fullInstruction = "${case.source}, 500 ml milk"
        val preparation = prepareV2ContentsDirect(case, instruction = fullInstruction)
        val ready = preparation.items.singleOrNull()
        val wholeBasketIssue = if (preparation.items.isEmpty()) "no_prepared_rows"
            else swiggyMcpItemValidationMessage(fullInstruction, preparation.items)
        assertTrue("cropped primary plus sibling numeric text must not be fully ready",
            ready == null || preparation.unresolvedText.isNotEmpty() || preparation.excludedText.isNotEmpty() ||
                wholeBasketIssue != null)
    }

    private data class V2ContentsCase(
        val source: String,
        val name: String,
        val outer: Int,
        val quantityUnit: String,
        val quantityText: String,
        val inner: Int,
        val innerText: String,
        val container: String,
        val relation: String,
        val required: List<String> = emptyList(),
    )

    private fun prepareV2Contents(case: V2ContentsCase): GroceryIntentPreparation {
        val outerStart = case.source.indexOf(case.quantityText)
        val innerToken = case.innerText
        val innerStart = case.source.indexOf(innerToken)
        require(outerStart >= 0 && innerStart >= 0)
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(case.source)},"source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":${jsonString(case.name)},"quantity":${case.outer},"quantity_unit":${jsonString(case.quantityUnit)},"pack_value":${case.inner},"pack_unit":"piece","packaging":${jsonString(case.container)},"required":[],"excluded":[],"source_text":${jsonString(case.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${jsonString(case.quantityText)},"pack_text":${jsonString(innerToken)}},"evidence":{"primary":{"text":${jsonString(case.source)},"start_utf16":0,"end_utf16":${case.source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}],"blocked_rows":[],"groups":[]},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""
        require(case.source.substring(outerStart, outerStart + case.quantityText.length) == case.quantityText)
        require(case.source.substring(innerStart, innerStart + innerToken.length) == innerToken)
        return GroceryIntentReviewV2Response.parse(body, case.source).draft.prepareForReview()
    }

    private fun prepareV2ContentsDirect(
        case: V2ContentsCase,
        instruction: String = case.source,
    ): GroceryIntentPreparation {
        val start = instruction.indexOf(case.source)
        require(start >= 0 && instruction.indexOf(case.source, start + 1) < 0)
        val sourceQuote = GroceryIntentReviewV2Quote(case.source, start, start + case.source.length)
        val item = GroceryIntentReviewV2Item(
            name = case.name,
            quantity = BigDecimal(case.outer),
            quantityUnit = case.quantityUnit,
            packValue = BigDecimal(case.inner),
            packUnit = "piece",
            packaging = case.container,
            required = case.required,
            excluded = emptyList(),
            sourceText = case.source,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = case.quantityText,
            packText = case.innerText,
        )
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(sourceQuote, emptyList()),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = 1,
            readyRows = listOf(row),
            blockedRows = emptyList(),
            groups = emptyList(),
        ).prepareForReview()
    }

    private fun v2ContentsCandidate(
        case: V2ContentsCase,
        sku: String,
        evidenceSku: String = sku,
    ) = RecommendationCandidate(
        spinId = "spin-$sku",
        label = "${case.name} ${case.relation}",
        skuId = sku,
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = "spin-$sku",
            skuId = evidenceSku,
            fields = CatalogueQuantityFields(
                variantName = case.relation,
                quantityDescription = case.relation,
            ),
        ),
    )

    private data class FixtureCase(
        val id: String,
        val source: String,
        val name: String,
        val quantity: Int,
        val quantityUnit: String,
        val packValue: Int,
        val packUnit: String,
        val accepted: Boolean,
    )

    private fun loadFixture(): List<FixtureCase> {
        val relativePath = "scripts/fixtures/intent_container_contents_oct28.json"
        val file = listOf(File("../$relativePath"), File(relativePath)).firstOrNull(File::isFile)
        assertNotNull("Shared backend-parity fixture not found: $relativePath", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        assertEquals("beta-grocery-intent-container-contents-test-v1", stringAt(root["schema"]))
        return arrayAt(root["cases"]).map { value ->
            val row = objectAt(value)
            FixtureCase(
                id = stringAt(row["id"]),
                source = stringAt(row["source"]),
                name = stringAt(row["name"]),
                quantity = numberAt(row["quantity"]),
                quantityUnit = stringAt(row["quantity_unit"]),
                packValue = numberAt(row["pack_value"]),
                packUnit = stringAt(row["pack_unit"]),
                accepted = booleanAt(row["accepted"]),
            )
        }
    }

    private fun sourceContentsItem(): com.example.beta.automation.ParsedItem {
        val row = FixtureCase(
            id = "source-contents",
            source = "two packets tea bags, 25 bags",
            name = "tea bags",
            quantity = 2,
            quantityUnit = "count",
            packValue = 25,
            packUnit = "piece",
            accepted = true,
        )
        return GroceryIntentResponse.parse(responseBody(row), row.source).draft.prepareForReview().items.single()
    }

    private fun contentsCandidate(
        fields: CatalogueQuantityFields = CatalogueQuantityFields(variantName = "Packet of 25 Pieces"),
        title: String = "Tea Bags Packet of 25 Pieces",
        sku: String = "sku-tea",
        evidenceSku: String = sku,
    ) = RecommendationCandidate(
        spinId = "spin-tea",
        label = title,
        skuId = sku,
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = "spin-tea",
            skuId = evidenceSku,
            fields = fields,
        ),
    )

    private fun assertContentsHeld(
        label: String,
        item: com.example.beta.automation.ParsedItem,
        candidate: RecommendationCandidate,
    ) {
        val assessed = assessSwiggyIntentQuantity(item, com.example.beta.SwiggyMcpClient.Recommendations(listOf(candidate)))
        assertFalse("$label should not be count-compatible", isSwiggyCandidateCountCompatible(assessed, candidate))
        assertTrue("$label should not produce a cart quantity", runCatching {
            swiggyRequestedCartQuantity(assessed, candidate)
        }.isFailure)
    }

    private fun responseBody(case: FixtureCase, instruction: String = case.source): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(instruction)},"items":[{"name":${jsonString(case.name)},"quantity":${case.quantity},"quantity_unit":${jsonString(case.quantityUnit)},"pack_value":${case.packValue},"pack_unit":${jsonString(case.packUnit)},"required":[],"excluded":[],"source_text":${jsonString(case.source)},"needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""

    private fun objectAt(value: JsonValue): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private fun numberAt(value: JsonValue?): Int =
        (value as? JsonValue.Num)?.raw?.toIntOrNull() ?: error("Expected integer JSON number")

    private fun booleanAt(value: JsonValue?): Boolean =
        (value as? JsonValue.Bool)?.value ?: error("Expected JSON boolean")

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
