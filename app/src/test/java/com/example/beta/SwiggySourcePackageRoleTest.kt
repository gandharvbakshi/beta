package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceSource
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggySourcePackageRoleTest {
    private data class CompoundPackCase(
        val source: String,
        val nominal: String,
        val packaging: String,
        val count: Int,
        val countText: String,
    )

    @Test
    fun requestPrefixesKeepPacketReviewRoleInFirstAndLaterClauses() {
        val failures = mutableListOf<String>()
        for ((index, prefix) in listOf("please get ", "i want ", "i need ", "i would like ", "add ").withIndex()) {
            for (earlier in listOf("", "milk, ")) {
                val instruction = earlier + prefix + "one packet frozen vegetables"
                val item = InstructionParser.parse(instruction).single { "frozen" in it.query }
                val candidate = offer("Frozen Vegetables", "6 pieces", 400 + index)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                if (sourcePacketOfferSyntax(item.rawText) == null) failures += "prefix=$index later=${earlier.isNotEmpty()} source role missing; " +
                    "context=${groceryIntentSourceClauseContextProven(instruction, "one packet frozen vegetables", "one packet frozen vegetables".length)} raw=${item.rawText}"
                runCatching { assertReviewedRetailProposal(checked, candidate, 1) }.exceptionOrNull()?.let {
                    failures += "prefix=$index later=${earlier.isNotEmpty()} ${swiggyCountResolutionDiagnostic(checked, candidate)}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun compoundNominalsRemainWholeAcrossGenericPackPreparationRoutes() {
        val cases = listOf(
            CompoundPackCase("two packs bottle brush", "bottle brush", "pack", 2, "two"),
            CompoundPackCase("two packets can opener", "can opener", "packet", 2, "two"),
            CompoundPackCase("two packets tea bags", "tea bags", "packet", 2, "two"),
            CompoundPackCase("three packs soap refill", "soap refill", "pack", 3, "three"),
        )
        data class Route(val name: String, val prepare: (CompoundPackCase) -> ParsedItem)
        val routes = listOf(
            Route("parser") { case -> InstructionParser.parse(case.source).single() },
            Route("v1-count") { case -> prepareV1CompoundPackCase(case, "count") },
            Route("v1-pack") { case -> prepareV1CompoundPackCase(case, "pack") },
            Route("v2-count") { case -> prepareV2CompoundPackCase(case, "count") },
            Route("v2-pack") { case -> prepareV2CompoundPackCase(case, "pack") },
        )
        val failures = mutableListOf<String>()
        val observations = mutableListOf<String>()
        var offerIndex = 960

        cases.forEachIndexed { caseIndex, case ->
            val receipt = sourcePacketOfferSyntax(case.source)
            if (receipt == null) {
                failures += "case=$caseIndex source receipt missing"
            } else {
                val nominalTokens = intentEvidenceTokens(case.source.substring(receipt.identity))
                if (nominalTokens.isEmpty() || !intentEvidenceTokens(receipt.literalIdentity()).containsAll(nominalTokens)) {
                    failures += "case=$caseIndex source receipt shortened literal nominal"
                }
            }

            routes.forEach { route ->
                val prepared = runCatching { route.prepare(case) }
                if (prepared.isFailure) {
                    failures += "case=$caseIndex route=${route.name} preparation=${prepared.exceptionOrNull()?.javaClass?.simpleName}"
                    return@forEach
                }
                val item = prepared.getOrThrow()
                val proof = item.intentQuantityProvenance
                val local = proof?.local
                val nominalTokens = intentEvidenceTokens(case.nominal)
                val diagnostic = "case=$caseIndex route=${route.name} quantity=${item.quantity} " +
                    "proof=${proof?.origin ?: "none"}"
                if (item.quantity != Quantity.Count(case.count)) failures += "$diagnostic outer count not retained"
                if (item.rawText != case.source || proof?.primaryText != case.source ||
                    local?.source != case.source || local?.value?.compareTo(case.count.toBigDecimal()) != 0) {
                    failures += "$diagnostic original quote/count proof not retained"
                }
                if (nominalTokens.isEmpty() || !intentEvidenceTokens(item.query).containsAll(nominalTokens) ||
                    (!item.strictMatchPhrase.isNullOrBlank() &&
                        !intentEvidenceTokens(item.strictMatchPhrase.orEmpty()).containsAll(nominalTokens))) {
                    failures += "$diagnostic full compound nominal not retained in query/optional strict phrase"
                }
                if (route.name == "parser" && receipt != null && !item.packetOfferProofMatches(receipt)) {
                    failures += "$diagnostic original packet receipt did not validate its proof snapshot"
                }

                val candidate = offer("${case.nominal} Pack of 3 Pieces", "3 pieces", offerIndex++)
                val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(assessed, candidate)
                observations += "$diagnostic offer=$result"
                if (result !is SwiggyCartQuantityResolution.RetailProposal || result.cartUnits != case.count) {
                    failures += "$diagnostic matching typed offer expected reviewed outer proposal(${case.count})"
                } else {
                    runCatching { assertReviewedRetailProposal(assessed, candidate, case.count) }
                        .exceptionOrNull()?.let { failures += "$diagnostic review assertion=${it.javaClass.simpleName}" }
                }
                if (result is SwiggyCartQuantityResolution.Exact) failures += "$diagnostic must never resolve Exact"

                val shortenedNominal = case.nominal.substringAfter(' ')
                val shortenedCandidate = offer("$shortenedNominal Pack of 3 Pieces", "3 pieces", offerIndex++)
                val shortenedChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(shortenedCandidate)))
                val shortenedResult = resolveSwiggyCartQuantity(shortenedChecked, shortenedCandidate)
                if (shortenedResult !is SwiggyCartQuantityResolution.Held) {
                    failures += "$diagnostic shortened identity unexpectedly resolved $shortenedResult"
                }
            }
        }

        val heldControls = listOf(
            "two packs of three bottle brushes" to "Bottle Brush Pack of 3 Pieces",
            "two packs bottle brush 3" to "Bottle Brush Pack of 3 Pieces",
        )
        heldControls.forEachIndexed { index, (source, title) ->
            val candidate = offer(title, "3 pieces", offerIndex++)
            val items = InstructionParser.parse(source)
            if (sourcePacketOfferSyntax(source) != null) {
                failures += "negative=$index unexpectedly produced a whole-source generic packet receipt"
            }
            // These malformed/nested forms must not become an actionable offer.
            if (items.isEmpty()) {
                failures += "negative=$index produced no diagnostic parse row"
            } else {
                items.forEachIndexed { rowIndex, item ->
                    val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                    val result = resolveSwiggyCartQuantity(assessed, candidate)
                    if (result !is SwiggyCartQuantityResolution.Held) {
                        failures += "negative=$index row=$rowIndex unexpectedly resolved $result"
                    }
                }
            }
        }

        listOf("packs two bottle brush", "two bottle brush packs", "two packs three bottle brush")
            .forEachIndexed { index, source ->
                if (sourceRetailCountReceipt(source) != null || sourcePacketOfferSyntax(source) != null) {
                    failures += "invalid-source=$index unexpectedly produced a retail count receipt"
                }
            }

        val merged = "two packs bottle brush and milk"
        if (sourcePacketOfferSyntax(merged) != null) failures += "merged source unexpectedly produced a whole-source packet receipt"
        val mergedRows = InstructionParser.parse(merged)
        if (mergedRows.size != 2 || mergedRows.any { it.rawText == merged }) {
            failures += "merged source did not retain two separately scoped parser rows"
        } else {
            if (mergedRows[0].rawText != "two packs bottle brush" || mergedRows[0].quantity != Quantity.Count(2)) {
                failures += "merged source first row lost its original packet scope/count"
            }
            if (mergedRows[1].rawText != "milk" || mergedRows[1].quantity !in setOf(Quantity.Default, Quantity.Count(1))) {
                failures += "merged source second row inherited the first row's count"
            }
        }

        listOf(
            "two jars of tea bags" to "tea bags",
            "two boxes of bottle brush" to "bottle brush",
        ).forEachIndexed { index, (source, nominal) ->
            val receipt = sourceRetailCountReceipt(source)
            if (receipt == null || !intentEvidenceTokens(source.substring(receipt.identity)).containsAll(intentEvidenceTokens(nominal)) ||
                receipt.count.value.compareTo(BigDecimal(2)) != 0) {
                failures += "named-source=$index failed to retain its explicit-of count and full nominal"
            }
        }

        val partyPackSource = "one packet Party Pack chocolates"
        val partyPack = InstructionParser.parse(partyPackSource).singleOrNull()
        val partyPackTokens = intentEvidenceTokens("Party Pack chocolates")
        if (partyPack == null || partyPack.rawText != partyPackSource ||
            !intentEvidenceTokens(partyPack.query).containsAll(partyPackTokens)) {
            failures += "Party Pack nominal was not retained intact"
        }
        assertTrue((failures + observations).joinToString("\n"), failures.isEmpty())
    }

    private fun prepareV1CompoundPackCase(case: CompoundPackCase, quantityUnit: String): ParsedItem =
        GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = case.source,
            items = listOf(GroceryIntentItem(
                name = case.nominal,
                quantity = BigDecimal.valueOf(case.count.toLong()),
                quantityUnit = quantityUnit,
                packValue = null,
                packUnit = "unspecified",
                required = emptyList(),
                excluded = emptyList(),
                sourceText = case.source,
                needsClarification = false,
            )),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()

    private fun prepareV2CompoundPackCase(case: CompoundPackCase, quantityUnit: String): ParsedItem {
        return prepareV2CompoundPackPreparation(case, quantityUnit).items.single()
    }

    private fun prepareV2CompoundPackPreparation(
        case: CompoundPackCase,
        quantityUnit: String,
    ): GroceryIntentPreparation {
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = GroceryIntentReviewV2Item(
                name = case.nominal,
                quantity = BigDecimal.valueOf(case.count.toLong()),
                quantityUnit = quantityUnit,
                packValue = null,
                packUnit = "unspecified",
                packaging = case.packaging,
                required = emptyList(),
                excluded = emptyList(),
                sourceText = case.source,
                supportingText = emptyList(),
                needsClarification = false,
                quantityText = case.countText,
                packText = null,
            ),
            evidence = GroceryIntentReviewV2Evidence(
                primary = GroceryIntentReviewV2Quote(case.source, 0, case.source.length),
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

    @Test
    fun v1PurchaseTagOmissionPreservesRequiredEvidenceAndSpecificForms() {
        val source = "2 packets milk"
        val row = GroceryIntentItem("milk", BigDecimal(2), "pack", null, "unspecified",
            listOf("packet"), emptyList(), source, false)
        val item = GroceryIntentDraft("grocery-intent-v1", true, source, listOf(row), false)
            .prepareForReview().items.single()
        assertEquals(listOf("packet"), row.required)
        assertEquals("milk", item.query)
        val candidate = offer("Milk", "500 ml", 405)
        assertReviewedRetailProposal(assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate))), candidate, 2)
        // Projection is a tag contribution only, never deletion of a different form.
        val specific = row.copy(name = "honey", sourceText = "one packet honey", quantity = BigDecimal.ONE,
            required = listOf("jar"))
        assertTrue("jar" in specific.toParsedItem().query)
        assertEquals(listOf("jar"), specific.required)
    }

    @Test
    fun sourceBoundContainerContentsReceiptControlsLegacyQueryProjection() {
        val source = "two packs of three pieces organic unsalted bottle brush"
        val row = GroceryIntentItem(
            name = "organic unsalted bottle brush",
            quantity = BigDecimal(2),
            quantityUnit = "pack",
            packValue = BigDecimal(3),
            packUnit = "piece",
            required = listOf("pack", "organic", "unsalted"),
            excluded = emptyList(),
            sourceText = source,
            needsClarification = false,
        )
        val sourceReceipt = requireNotNull(row.sourceContainerContentsReceipt())

        val noProof = row.toParsedItem(
            emptySet(), legacySourceProofs = false, contentsSource = null,
        )
        assertTrue("without the source receipt, the generic required tag remains", "pack" in noProof.query)
        assertEquals(null, noProof.containerContentsProof)
        assertEquals(null, noProof.intentQuantityProvenance)
        assertFalse(noProof.requiresIntentQuantityProof)
        val unprovenCandidate = offer("Organic Unsalted Bottle Brush Pack 3 pieces", "3 pieces", 1099)
        val unprovenResult = resolveSwiggyCartQuantity(noProof, unprovenCandidate)
        assertTrue("projection without source quantity provenance stays held",
            unprovenResult is SwiggyCartQuantityResolution.Held)

        val item = row.toParsedItem(emptySet())
        val contentsProof = requireNotNull(item.validContainerContentsProof())
        assertEquals(source, item.rawText)
        assertEquals(Quantity.Count(2), item.quantity)
        assertEquals(source, contentsProof.outer.source)
        assertEquals(source, contentsProof.inner.source)
        assertEquals(BigDecimal(2), contentsProof.outer.value)
        assertEquals("two", contentsProof.outer.atom)
        assertEquals(BigDecimal(3), contentsProof.inner.value)
        assertEquals("three", contentsProof.inner.atom)
        assertEquals("pack", contentsProof.container)
        assertEquals(item.query, contentsProof.query)
        assertTrue("the source-owned generic tag is omitted from query", "pack" !in item.query)
        assertTrue("other required source/diet words remain", "organic" in item.query && "unsalted" in item.query)
        assertEquals(listOf("pack", "organic", "unsalted"), row.required)

        val acceptedSource = "two packs of three pieces bottle brush"
        val acceptedRow = GroceryIntentItem(
            name = "bottle brush",
            quantity = BigDecimal(2),
            quantityUnit = "pack",
            packValue = BigDecimal(3),
            packUnit = "piece",
            required = listOf("pack"),
            excluded = emptyList(),
            sourceText = acceptedSource,
            needsClarification = false,
        )
        val reviewedItem = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = acceptedSource,
            items = listOf(acceptedRow),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()
        assertEquals(Quantity.Count(2), reviewedItem.quantity)
        assertEquals(acceptedSource, reviewedItem.rawText)
        assertNotNull(reviewedItem.validContainerContentsProof())
        assertTrue("only the source-owned pack tag is omitted", "pack" !in reviewedItem.query)
        assertTrue("inner contents are retained in the full preparation", "3 pieces" in reviewedItem.query)

        val offers = listOf(
            offer("Bottle Brush 3 pieces", "3 pieces", 1100),
            offer("BrandA Bottle Brush 3 pieces", "3 pieces", 1101),
        )
        val assessed = assessSwiggyIntentQuantity(reviewedItem, Recommendations(offers))
        offers.forEach { candidate -> assertReviewedRetailProposal(assessed, candidate, 2) }

        assertThrows(IllegalArgumentException::class.java) {
            row.copy(sourceText = "two packs of three pieces can opener")
                .toParsedItem(emptySet(), contentsSource = sourceReceipt)
        }
        assertThrows(IllegalArgumentException::class.java) {
            row.copy(quantity = BigDecimal(3)).toParsedItem(emptySet(), contentsSource = sourceReceipt)
        }
        assertThrows(IllegalArgumentException::class.java) {
            row.copy(packValue = BigDecimal(4)).toParsedItem(emptySet(), contentsSource = sourceReceipt)
        }

        listOf(
            Triple("two jars of honey", "honey", "jar"),
            Triple("two cartons of oat milk", "oat milk", "carton"),
        ).forEachIndexed { index, (formSource, name, form) ->
            val specific = GroceryIntentItem(
                name = name,
                quantity = BigDecimal(2),
                quantityUnit = "pack",
                packValue = null,
                packUnit = "unspecified",
                required = listOf(form),
                excluded = emptyList(),
                sourceText = formSource,
                needsClarification = false,
            )
            val specificItem = specific.toParsedItem(emptySet())
            assertTrue("case=$index specific source form remains in query", form in specificItem.query)
            assertEquals(listOf(form), specific.required)
        }

        val partySource = "two packs of three pieces Party Pack bottle brush"
        val partyPack = GroceryIntentItem(
            name = "Party Pack bottle brush",
            quantity = BigDecimal(2),
            quantityUnit = "pack",
            packValue = BigDecimal(3),
            packUnit = "piece",
            required = listOf("pack"),
            excluded = emptyList(),
            sourceText = partySource,
            needsClarification = false,
        ).toParsedItem(emptySet())
        assertTrue("literal Party Pack name is preserved", partyPack.query.contains("Party Pack"))
        assertEquals(1, Regex("(?i)(?<![a-z])pack(?![a-z])").findAll(partyPack.query).count())
        assertNotNull(partyPack.validContainerContentsProof())
    }

    @Test
    fun splitPartyPackIdentityCannotUseTypedContentsOrExplicitRelationAsExactEvidence() {
        val source = "two packs of three pieces Party Pack cookies"
        fun prepareV1(name: String): ParsedItem = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1",
            requiresReview = true,
            instruction = source,
            items = listOf(GroceryIntentItem(
                name = name, quantity = BigDecimal(2), quantityUnit = "pack",
                packValue = BigDecimal(3), packUnit = "piece", required = listOf("pack"),
                excluded = emptyList(), sourceText = source, needsClarification = false,
            )),
            modelNeedsClarification = false,
        ).prepareForReview().items.single()

        fun prepareV2(name: String): ParsedItem {
            val row = GroceryIntentReviewV2ReadyRow(
                rowIndex = 0,
                item = GroceryIntentReviewV2Item(
                    name = name, quantity = BigDecimal(2), quantityUnit = "pack",
                    packValue = BigDecimal(3), packUnit = "piece", packaging = "pack",
                    required = listOf("pack"), excluded = emptyList(), sourceText = source,
                    supportingText = emptyList(), needsClarification = false,
                    quantityText = "two", packText = "three",
                ),
                evidence = GroceryIntentReviewV2Evidence(
                    primary = GroceryIntentReviewV2Quote(source, 0, source.length), supporting = emptyList(),
                ),
                quantityOrigin = "stated", amountRoleStatus = "matched",
            )
            return GroceryIntentReviewV2(
                contractVersion = "grocery-intent-v2", requiresReview = true, instruction = source,
                sourceItemCount = 1, readyRows = listOf(row), blockedRows = emptyList(),
            ).prepareForReview().items.single()
        }

        val splitRows = listOf(
            "v1" to prepareV1("Party cookies"),
            "v2" to prepareV2("Party cookies"),
        )
        var offerIndex = 1120
        for ((route, item) in splitRows) {
            val plainTyped = offer("Party Cookies 3 Pieces", "3 pieces", offerIndex++)
            val relationTyped = offer("Party Cookies Pack of 3 Pieces", "Pack of 3 Pieces", offerIndex++)
            for ((formIndex, candidate) in listOf(plainTyped, relationTyped).withIndex()) {
                val direct = resolveSwiggyCartQuantity(item, candidate)
                assertTrue("$route form=$formIndex direct=$direct", direct is SwiggyCartQuantityResolution.Held)
                val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val reassessed = resolveSwiggyCartQuantity(assessed, candidate)
                assertTrue("$route form=$formIndex reassessed=$reassessed",
                    reassessed is SwiggyCartQuantityResolution.Held)
            }
        }

        val retainedRows = listOf(
            "v1" to prepareV1("Party Pack cookies"),
            "v2" to prepareV2("Party Pack cookies"),
        )
        retainedRows.forEach { (route, item) ->
            assertTrue("$route complete source identity", item.query.contains("Party Pack cookies"))
            assertEquals("Party Pack cookies", item.containerContentsLiteralIdentity())
            val candidate = offer("Party Pack Cookies 3 Pieces", "3 pieces", offerIndex++)
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertReviewedRetailProposal(assessed, candidate, 2)
        }
    }

    @Test
    fun fullyOwnedContainerFormAfterInnerSizeStillAllowsExplicitExactOfferRelation() {
        data class Case(val source: String, val nominal: String, val container: String)
        val cases = listOf(
            Case("two boxes of three pieces cookies", "cookies", "box"),
            Case("two jars of three pieces olives", "olives", "jar"),
        )
        var candidateIndex = 1170
        cases.forEachIndexed { caseIndex, case ->
            val sourceRow = GroceryIntentItem(
                name = case.nominal,
                quantity = BigDecimal(2),
                quantityUnit = "pack",
                packValue = BigDecimal(3),
                packUnit = "piece",
                required = emptyList(),
                excluded = emptyList(),
                sourceText = case.source,
                needsClarification = false,
            )
            val item = GroceryIntentDraft(
                contractVersion = "grocery-intent-v1",
                requiresReview = true,
                instruction = case.source,
                items = listOf(sourceRow),
                modelNeedsClarification = false,
            ).prepareForReview().items.single()
            val proof = requireNotNull(item.validContainerContentsProof())
            assertEquals(case.source, item.rawText)
            assertEquals(Quantity.Count(2), item.quantity)
            assertEquals(case.source, proof.outer.source)
            assertEquals("two", proof.outer.atom)
            assertEquals(BigDecimal(2), proof.outer.value)
            assertEquals(case.source, proof.inner.source)
            assertEquals("three", proof.inner.atom)
            assertEquals(BigDecimal(3), proof.inner.value)
            assertEquals(case.container, proof.container)
            assertTrue("case=$caseIndex source identity retained", item.query.contains(case.nominal))

            val innerText = case.container.replaceFirstChar { it.uppercase() } + " of 3 Pieces"
            val matching = offer("${case.nominal.replaceFirstChar { it.uppercase() }} $innerText", innerText, candidateIndex++)
            assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(item, matching))

            val wrongContainer = if (case.container == "box") "jar" else "box"
            val mismatched = offer(
                "${case.nominal.replaceFirstChar { it.uppercase() }} ${wrongContainer.replaceFirstChar { it.uppercase() }} of 3 Pieces",
                "${wrongContainer.replaceFirstChar { it.uppercase() }} of 3 Pieces",
                candidateIndex++,
            )
            val wrongChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(mismatched)))
            assertTrue("case=$caseIndex wrong container direct",
                resolveSwiggyCartQuantity(item, mismatched) is SwiggyCartQuantityResolution.Held)
            assertTrue("case=$caseIndex wrong container reassessed",
                resolveSwiggyCartQuantity(wrongChecked, mismatched) is SwiggyCartQuantityResolution.Held)

            val dropped = offer(
                "${case.container.replaceFirstChar { it.uppercase() }} of 3 Pieces",
                innerText,
                candidateIndex++,
            )
            val droppedChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(dropped)))
            assertTrue("case=$caseIndex dropped nominal direct",
                resolveSwiggyCartQuantity(item, dropped) is SwiggyCartQuantityResolution.Held)
            assertTrue("case=$caseIndex dropped nominal reassessed",
                resolveSwiggyCartQuantity(droppedChecked, dropped) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun v2NamedRawCountHoldsWholeBasketUntilRetailCountIsNormalized() {
        val instruction = "two boxes of tea bags and milk"
        val teaSource = "two boxes of tea bags"
        fun quote(source: String) = GroceryIntentReviewV2Quote(
            source, instruction.indexOf(source), instruction.indexOf(source) + source.length,
        )

        fun row(index: Int, source: String, normalized: Boolean): GroceryIntentReviewV2ReadyRow {
            val countRow = source == teaSource
            return GroceryIntentReviewV2ReadyRow(
                rowIndex = index,
                item = GroceryIntentReviewV2Item(
                    name = if (countRow) "tea bags" else "milk",
                    quantity = if (countRow) BigDecimal(2) else null,
                    quantityUnit = if (!countRow) "unspecified" else if (normalized) "pack" else "count",
                    packValue = null,
                    packUnit = "unspecified",
                    packaging = if (countRow) "box" else null,
                    required = emptyList(), excluded = emptyList(), sourceText = source,
                    supportingText = emptyList(), needsClarification = false,
                    quantityText = if (countRow) "two" else null, packText = null,
                ),
                evidence = GroceryIntentReviewV2Evidence(primary = quote(source), supporting = emptyList()),
                quantityOrigin = if (countRow) "stated" else "not_stated",
                amountRoleStatus = if (countRow) "matched" else "not_stated",
            )
        }

        fun prepare(normalized: Boolean): GroceryIntentPreparation = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
            sourceItemCount = 2,
            readyRows = listOf(row(0, teaSource, normalized), row(1, "milk", normalized)),
            blockedRows = emptyList(),
        ).prepareForReview()

        val rawCount = prepare(normalized = false)
        assertTrue(rawCount.items.isEmpty())
        assertEquals(listOf(instruction), rawCount.unresolvedText)

        val normalizedPack = prepare(normalized = true)
        assertEquals(2, normalizedPack.items.size)
        assertEquals(emptyList<String>(), normalizedPack.unresolvedText)
        assertEquals(listOf(Quantity.Count(2), Quantity.Default), normalizedPack.items.map { it.quantity })
    }

    @Test
    fun unresolvedLaterQuantityCorrectionsCannotEnterModelOffCartPreparation() {
        val failures = mutableListOf<String>()
        listOf("one pack rice, sorry two", "one packet rice, sorry two", "two packets milk, sorry three")
            .forEachIndexed { index, source ->
                val items = prepareSwiggyMcpItems(source) { null }
                if (items.isNotEmpty() && swiggyMcpItemValidationMessage(source, items) == null)
                    failures += "case=$index correction not held by model-off preparation"
            }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun modelOffPreparationKeepsProtectedAntecedentsEquivalentAcrossDesireAndGetFrames() {
        data class PrefixCase(val prefix: String, val residual: String)
        val prefixes = listOf(
            PrefixCase("no, ", "no"),
            PrefixCase("if available, ", "if available"),
            PrefixCase("someone said, ", "someone said"),
            PrefixCase("3 packets rice, no, ", "no"),
        )
        val framings = listOf("i want one packet dal", "get one packet dal")
        val failures = mutableListOf<String>()

        for ((prefixIndex, prefixCase) in prefixes.withIndex()) {
            val signatures = mutableListOf<String>()
            for ((framingIndex, framing) in framings.withIndex()) {
                val items = prepareSwiggyMcpItems(prefixCase.prefix + framing) { null }
                val validation = swiggyMcpItemValidationMessage(prefixCase.prefix + framing, items)
                val blocked = items.isEmpty() || validation != null
                val explicitUnresolvedResidual = items.any { item ->
                    item.rawText.trim().equals(prefixCase.residual, ignoreCase = true) &&
                        (item.quantitySignal != null || item.quantity == Quantity.Default || item.unprovenIdentityScope)
                }
                if (!blocked && !explicitUnresolvedResidual) {
                    failures += "prefix=$prefixIndex framing=$framingIndex neither blocked nor retained a separate unresolved residual"
                }
                val coarseItems = items.map { "${it.quantity}:${it.query.lowercase()}" }.sorted().joinToString("|")
                signatures += if (blocked) "blocked" else "items:$coarseItems"
            }
            if (signatures.distinct().size != 1) failures += "prefix=$prefixIndex desire/get coarse outcomes differ"
        }

        for ((index, framing) in framings.withIndex()) {
            val items = prepareSwiggyMcpItems(framing) { null }
            val validation = swiggyMcpItemValidationMessage(framing, items)
            if (items.size != 1 || items.singleOrNull()?.quantity != Quantity.Count(1) || validation != null) {
                failures += "clean framing=$index did not retain exactly one count unit"
                continue
            }
            val item = items.single()
            if (item.rawText != "one packet dal" || item.query.contains("want", ignoreCase = true) ||
                item.query.contains("get", ignoreCase = true)) {
                failures += "clean framing=$index introduced residual query material"
                continue
            }
            val candidate = offer("Dal 6 pieces", "6 pieces", 920 + index)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            runCatching { assertReviewedRetailProposal(checked, candidate, 1) }
                .exceptionOrNull()?.let { failures += "clean framing=$index typed offer was not a reviewed one-unit proposal" }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun preferenceCannotErasePackFromGenericPacketSourceIdentity() {
        val parsed = InstructionParser.parse("one packet chocolates").single()
        val preferred = InstructionParser.applyPreferences(
            listOf(parsed),
            lookup = { query -> Preference(query, "Party Pack chocolates", source = PreferenceSource.USER_OVERRIDE, confidence = 1f) },
        ).single()
        assertTrue(preferred.query.contains("pack", ignoreCase = true))
        assertTrue(preferred.strictMatchPhrase.orEmpty().contains("pack", ignoreCase = true))

        val matching = offer("Party Pack Chocolates 6 pieces", "6 pieces", 292)
        val matchingChecked = assessSwiggyIntentQuantity(preferred, Recommendations(listOf(matching)))
        assertReviewedRetailProposal(matchingChecked, matching, 1)

        val withoutPack = offer("Party Chocolates 6 pieces", "6 pieces", 293)
        val withoutPackChecked = assessSwiggyIntentQuantity(preferred, Recommendations(listOf(withoutPack)))
        assertFalse(isSwiggyCandidateAllowed(withoutPackChecked, withoutPack))
        assertTrue(swiggyCountResolutionDiagnostic(withoutPackChecked, withoutPack),
            resolveSwiggyCartQuantity(withoutPackChecked, withoutPack) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun outOfRangeGenericPackCountsNeverDefaultOrClampToAnActionableQuantity() {
        data class Case(val source: String, val expectedCount: Int)
        val cases = listOf(
            Case("21 packets rice", 21),
            Case("twenty one packets rice", 21),
            Case("100 packs rice", 100),
            Case("one hundred packs rice", 100),
        )
        cases.forEachIndexed { index, case ->
            val items = InstructionParser.parse(case.source)
            assertTrue("case=$index source retained a diagnostic row", items.isNotEmpty())
            items.forEachIndexed { rowIndex, item ->
                val candidate = offer("Rice 6 pieces", "6 pieces", 294 + index)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(checked, candidate)
                val diagnostic = "case=$index row=$rowIndex quantity=${item.quantity} " +
                    "proof=${item.intentQuantityProvenance?.origin ?: "none"} result=$result"
                assertTrue(diagnostic, result is SwiggyCartQuantityResolution.Held)
                assertFalse(diagnostic, result is SwiggyCartQuantityResolution.Exact)
                assertFalse(diagnostic, result is SwiggyCartQuantityResolution.RetailProposal)
                val retainedCount = item.quantity as? Quantity.Count
                if (retainedCount != null) {
                    assertEquals(diagnostic, case.expectedCount, retainedCount.n)
                }
                assertFalse(diagnostic, item.quantity == Quantity.Count(1) || item.quantity == Quantity.Count(20))
            }
        }
    }

    @Test
    fun explicitPackageSourceCountProposalsStayReviewableAcrossAllPreparationRoutes() {
        data class Case(val source: String, val identity: String, val count: Int, val pieces: Int)
        val cases = listOf(
            Case("one packet frozen vegetables", "frozen vegetables", 1, 6),
            Case("two packets frozen snacks", "frozen snacks", 2, 12),
            Case("one pack cotton swabs", "cotton swabs", 1, 50),
            Case("two packs toothbrush", "toothbrush", 2, 3),
            Case("1 pack basmati rice", "basmati rice", 1, 2),
            Case("2 packets frozen corn", "frozen corn", 2, 8),
            Case("3 packets dog treats", "dog treats", 3, 12),
            Case("one packet Acme almonds", "Acme almonds", 1, 6),
        )
        data class Route(val name: String, val prepare: (Case, String) -> ParsedItem)

        val routes = buildList {
            add(Route("legacy-parser") { case, _ -> InstructionParser.parse(case.source).single() })
            for (unit in listOf("count", "pack")) {
                add(Route("v1-$unit") { case, quantityUnit ->
                    GroceryIntentDraft(
                        contractVersion = "grocery-intent-v1",
                        requiresReview = true,
                        instruction = case.source,
                        items = listOf(GroceryIntentItem(
                            name = case.identity,
                            quantity = BigDecimal.valueOf(case.count.toLong()),
                            quantityUnit = quantityUnit,
                            packValue = null,
                            packUnit = "unspecified",
                            required = emptyList(),
                            excluded = emptyList(),
                            sourceText = case.source,
                            needsClarification = false,
                        )),
                        modelNeedsClarification = false,
                    ).prepareForReview().items.single()
                })
                add(Route("v2-$unit") { case, quantityUnit ->
                    val packaging = if (case.source.contains("packet")) "packet" else "pack"
                    val row = GroceryIntentReviewV2ReadyRow(
                        rowIndex = 0,
                        item = GroceryIntentReviewV2Item(
                            name = case.identity,
                            quantity = BigDecimal.valueOf(case.count.toLong()),
                            quantityUnit = quantityUnit,
                            packValue = null,
                            packUnit = "unspecified",
                            packaging = packaging,
                            required = emptyList(),
                            excluded = emptyList(),
                            sourceText = case.source,
                            supportingText = emptyList(),
                            needsClarification = false,
                            quantityText = case.source.substringBefore(' '),
                            packText = null,
                        ),
                        evidence = GroceryIntentReviewV2Evidence(
                            primary = GroceryIntentReviewV2Quote(case.source, 0, case.source.length),
                            supporting = emptyList(),
                        ),
                        quantityOrigin = "stated",
                        amountRoleStatus = "matched",
                    )
                    GroceryIntentReviewV2(
                        contractVersion = "grocery-intent-v2",
                        requiresReview = true,
                        instruction = case.source,
                        sourceItemCount = 1,
                        readyRows = listOf(row),
                        blockedRows = emptyList(),
                    ).prepareForReview().items.single()
                })
            }
        }

        val failures = mutableListOf<String>()
        val observations = mutableListOf<String>()
        var candidateIndex = 300
        for ((caseIndex, case) in cases.withIndex()) {
            for (route in routes) {
                val units = if (route.name.startsWith("v1-") || route.name.startsWith("v2-")) {
                    route.name.substringAfter('-')
                } else "source"
                val quantityUnit = when (units) {
                    "count" -> "count"
                    "pack" -> "pack"
                    else -> "unspecified"
                }
                val prepared = runCatching { route.prepare(case, quantityUnit) }
                if (prepared.isFailure) {
                    failures += "case=$caseIndex route=${route.name} preparation=${prepared.exceptionOrNull()?.javaClass?.simpleName}"
                    continue
                }
                val item = prepared.getOrThrow()
                val proof = item.intentQuantityProvenance
                val local = proof?.local
                val sourceObservation = "case=$caseIndex route=${route.name} quantity=${item.quantity} " +
                    "proof=${proof?.sourceCountRole ?: proof?.origin ?: "none"} " +
                    "quote=${proof?.primaryText} atom=${local?.atom}"
                if (item.quantity != Quantity.Count(case.count)) failures += "$sourceObservation expected outer count ${case.count}"
                if (proof == null || proof.primaryText != case.source || local == null || local.source != case.source ||
                    local.atom != case.source.substringBefore(' ') ||
                    local.value.compareTo(BigDecimal.valueOf(case.count.toLong())) != 0) {
                    failures += "$sourceObservation source proof did not retain the full quote/count atom"
                }
                val offers = listOf(
                    "typed-pieces" to offer("${case.identity} ${case.pieces} pieces", "${case.pieces} pieces", candidateIndex++),
                    "typed-500g" to offer("${case.identity} 500 g", "500 g", candidateIndex++),
                )
                for ((offerName, candidate) in offers) {
                    val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                    val result = resolveSwiggyCartQuantity(checked, candidate)
                    val observation = "$sourceObservation offer=$offerName result=$result"
                    observations += observation
                    if (result !is SwiggyCartQuantityResolution.RetailProposal || result.cartUnits != case.count) {
                        failures += "$observation expected reviewed RetailProposal(${case.count})"
                    } else {
                        runCatching { assertReviewedRetailProposal(checked, candidate, case.count) }
                            .exceptionOrNull()?.let { failures += "$observation review/ack assertion=${it.javaClass.simpleName}" }
                    }
                    if (result is SwiggyCartQuantityResolution.Exact) failures += "$observation must never resolve Exact"
                }
            }
        }
        assertTrue((failures + observations).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun explicitPacketRequestsRemainOuterSkuCountsDespiteInnerPieceCounts() {
        data class Case(val source: String, val identity: String, val count: Int, val pieces: Int)
        val cases = listOf(
            Case("one packet frozen vegetables", "frozen vegetables", 1, 6),
            Case("two packets frozen snacks", "frozen snacks", 2, 12),
            Case("one pack cotton swabs", "cotton swabs", 1, 50),
            Case("two packs toothbrush", "toothbrush", 2, 3),
        )

        cases.forEachIndexed { index, case ->
            val item = InstructionParser.parse(case.source).single()
            assertEquals(case.source, Quantity.Count(case.count), item.quantity)
            assertTrue("${case.source} should preserve explicit source review", item.requiresIntentQuantityProof)
            val proof = requireNotNull(item.intentQuantityProvenance)
            assertEquals(case.source, proof.primaryText)
            assertEquals(case.source, proof.local?.source)
            assertTrue("${case.source} source count atom mismatch",
                proof.local?.value?.compareTo(case.count.toBigDecimal()) == 0)

            val candidate = offer("${case.identity} ${case.pieces} pieces", "${case.pieces} pieces", index)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val result = resolveSwiggyCartQuantity(checked, candidate)
            assertTrue("${case.source} requires a typed outer-package proposal: ${swiggyCountResolutionDiagnostic(checked, candidate)}",
                result is SwiggyCartQuantityResolution.RetailProposal)
            val proposal = result as SwiggyCartQuantityResolution.RetailProposal
            assertEquals("outer SKUs, not inner pieces", case.count, proposal.cartUnits)
            assertTrue(proposal.contents is SwiggyRetailOfferContents.Proven)
            val contents = proposal.contents as SwiggyRetailOfferContents.Proven
            assertEquals(GroceryPackDimension.PIECES, contents.measure.dimension)
            assertEquals(0, case.pieces.toBigDecimal().compareTo(contents.measure.baseValue))
            assertTrue("proposal is not exact-action authorization", runCatching {
                swiggyRequestedCartQuantity(checked, candidate)
            }.isFailure)
            assertReviewedRetailProposal(checked, candidate, case.count)
        }
    }

    @Test
    fun narrowedStrictPhraseCannotDetachPacketCountFromItsFullSourceIdentity() {
        val source = "one packet Acme almonds"
        val item = InstructionParser.parse(source).single()
        val proof = requireNotNull(item.intentQuantityProvenance)
        val narrowed = item.copy(
            strictMatchPhrase = "almonds",
            intentQuantityProvenance = proof.copy(strictMatchPhrase = "almonds"),
        )
        assertEquals(item.query, narrowed.query)
        assertEquals(proof.numericSourceQuery, narrowed.intentQuantityProvenance?.numericSourceQuery)
        val candidate = offer("OtherBrand Almonds 6 pieces", "6 pieces", 299)
        val checked = assessSwiggyIntentQuantity(narrowed, Recommendations(listOf(candidate)))
        assertTrue(swiggyCountResolutionDiagnostic(checked, candidate),
            resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun packetOffersDoNotInferSourceRoleFromPieceMetadataOrDamagedProof() {
        val sources = listOf("2 frozen vegetables", "six pack yogurt", "2 pack toothbrush",
            "one bottle brush", "one small packet rice", "one 200 g packet rice",
            "one packet frozen vegetables 2")
        // A compound may split into valid separate rows; it is not one packet receipt.
        assertEquals(null, sourcePacketOfferSyntax("one packet rice and dal"))
        sources.forEachIndexed { index, source ->
            val items = InstructionParser.parse(source)
            assertTrue("case=$index must retain at least one diagnostic row", items.isNotEmpty())
            items.forEach { item ->
                val candidate = offer(item.query, "6 pieces", 200 + index)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                assertTrue("case=$index ${swiggyCountResolutionDiagnostic(checked, candidate)}",
                    resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            }
        }
        val source = "two packets frozen vegetables"
        val item = InstructionParser.parse(source).single()
        val proof = requireNotNull(item.intentQuantityProvenance)
        val variants = listOf("1 piece", "6 pieces", "500 g", "1 packet of 6 pieces")
        variants.forEachIndexed { index, size ->
            val candidate = offer("Frozen Vegetables", size, 220 + index)
            val mutations = listOf(
                item.copy(requiresIntentQuantityProof = false, intentQuantityProvenance = null, retailPackCount = false),
                item.copy(quantity = Quantity.Count(3)),
                item.copy(intentQuantityProvenance = proof.copy(primaryText = "two packets vegetables")),
                item.copy(query = "vegetables", intentQuantityProvenance = proof.copy(query = "vegetables", numericSourceQuery = "vegetables")),
                item.copy(cartQuantityMode = com.example.beta.automation.IntentCartQuantityMode.INCREMENT),
            )
            mutations.forEachIndexed { mutationIndex, mutated ->
                val checked = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(candidate)))
                assertTrue("offer=$index mutation=$mutationIndex ${swiggyCountResolutionDiagnostic(checked, candidate)}",
                    resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun genericPacketSyntaxAndMatchingRemainBoundToOriginalSource() {
        val bareDescriptor = ParsedItem(
            rawText = "rice pack",
            query = "rice pack",
            quantity = Quantity.Default,
            strictMatchPhrase = "rice pack",
        )
        val riceWithoutPack = offer("Rice 6 pieces", "6 pieces", 280)
        val checkedBare = assessSwiggyIntentQuantity(bareDescriptor, Recommendations(listOf(riceWithoutPack)))
        assertFalse(isSwiggyCandidateAllowed(checkedBare, riceWithoutPack))
        assertTrue(swiggyCountResolutionDiagnostic(checkedBare, riceWithoutPack),
            resolveSwiggyCartQuantity(checkedBare, riceWithoutPack) is SwiggyCartQuantityResolution.Held)

        val compoundPacketSources = listOf(
            "one packet ice pack" to "Ice Pack 6 pieces",
            "one packet bottle brush" to "Bottle Brush 1 piece",
        )
        compoundPacketSources.forEachIndexed { index, (source, title) ->
            assertNotNull("compound=$index retains a source-owned packet role", sourcePacketOfferSyntax(source))
            val item = InstructionParser.parse(source).single()
            val candidate = offer(title, if (index == 0) "6 pieces" else "1 piece", 281 + index)
            assertReviewedRetailProposal(assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate))), candidate, 1)
        }
        val nonPacketSources = listOf(
            "one small packet rice" to "Rice 6 pieces",
            "one 200 g packet rice" to "Rice 6 pieces",
        )
        nonPacketSources.forEachIndexed { index, (source, title) ->
            assertEquals("case=$index source syntax", null, sourcePacketOfferSyntax(source))
            val items = InstructionParser.parse(source)
            assertTrue("case=$index parser rows", items.isNotEmpty())
            items.forEach { item ->
                val candidate = offer(title, "6 pieces", 281 + index)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                assertTrue("case=$index ${swiggyCountResolutionDiagnostic(checked, candidate)}",
                    resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            }
        }

        val namedPacketSource = "two packets of milk"
        assertNotNull(sourcePacketOfferSyntax(namedPacketSource))
        val milk = InstructionParser.parse(namedPacketSource).single()
        assertEquals(null, milk.validNamedRetailReceipt())
        val packetless = offer("Milk 500 ml", "500 ml", 290)
        val checkedPacketless = assessSwiggyIntentQuantity(milk, Recommendations(listOf(packetless)))
        assertTrue(checkedPacketless.packetOfferProofMatches(requireNotNull(sourcePacketOfferSyntax(namedPacketSource))))
        assertReviewedRetailProposal(checkedPacketless, packetless, 2)

        val packetSku = offer("Milk Packet 500 ml", "500 ml", 291)
        val checkedPacketSku = assessSwiggyIntentQuantity(milk, Recommendations(listOf(packetSku)))
        assertReviewedRetailProposal(checkedPacketSku, packetSku, 2)
    }

    @Test
    fun additionalExclusionsConstrainPacketOffersWithoutInventingPurchaseRoles() {
        val item = InstructionParser.parse("one packet almonds").single().copy(avoidPhrases = listOf("salted"))
        val plain = offer("Almonds", "6 pieces", 270)
        val excluded = offer("Salted Almonds", "6 pieces", 271)
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(plain, excluded)))
        assertReviewedRetailProposal(checked, plain, 1)
        assertTrue(resolveSwiggyCartQuantity(checked, excluded) is SwiggyCartQuantityResolution.Held)
        // An exclusion cannot erase or move a positive source identity.
        val contradiction = checked.copy(avoidPhrases = listOf("almonds"))
        assertTrue(resolveSwiggyCartQuantity(contradiction, plain) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun packetProposalUsesCompleteSkuBoundContentsAndSingleUseReview() {
        val item = InstructionParser.parse("two packets frozen vegetables").single()
        val candidate = offer("Frozen Vegetables", "1 packet of 6 pieces", 250)
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertReviewedRetailProposal(checked, candidate, 2)
        val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
        assertEquals(listOf(2), request.requestedItems.map { it.quantity })
        val gate = SwiggyQuantityProposalGate()
        gate.prepare(5, "address", "plan", request.quantityProposals)
        assertFalse(gate.canApply(5, "address", "plan", request.quantityProposals))
        assertTrue(gate.acknowledge(5, "address", "plan", request.quantityProposals, true))
        assertTrue(gate.consumeForApply(5, "address", "plan", request.quantityProposals))
        assertFalse(gate.consumeForApply(5, "address", "plan", request.quantityProposals))
        val unsafe = listOf(candidate.copy(catalogueQuantity = null), candidate.copy(skuId = "changed"),
            candidate.copy(label = "Frozen Vegetables Pack of 2"), candidate.copy(variant = "500 g"),
            candidate.copy(subtitle = "12 pieces"), candidate.copy(catalogueQuantityInvalid = true))
        unsafe.forEachIndexed { index, other ->
            assertTrue("case=$index changed candidate must invalidate the existing assessment",
                resolveSwiggyCartQuantity(checked, other) is SwiggyCartQuantityResolution.Held)
            val reassessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(other)))
            assertTrue("case=$index must not authorize conflicting contents", resolveSwiggyCartQuantity(reassessed, other)
                is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun packetAndTetraPackCountOrderingsNeverTurnAnInnerBundleIntoTheOuterCount() {
        data class Case(val source: String, val count: Int)
        val cases = listOf(
            Case("milk two packets", 2),
            Case("2 packets milk", 2),
            Case("5 milk tetra packs", 5),
        )
        for ((index, case) in cases.withIndex()) {
            val item = InstructionParser.parse(case.source).single()
            assertEquals(case.source, Quantity.Count(case.count), item.quantity)
            val bundle = offer("Milk", "6 x 200 ml", 10 + index)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(bundle)))
            assertFalse("${case.source} cannot infer exact outer units from a six-pack bundle: ${swiggyCountResolutionDiagnostic(checked, bundle)}",
                resolveSwiggyCartQuantity(checked, bundle) is SwiggyCartQuantityResolution.Exact)
        }

        val oneTetraPack = InstructionParser.parse("one tetra pack milk").single()
        assertEquals(Quantity.Count(1), oneTetraPack.quantity)
        val sixBy200 = offer("Milk", "6 x 200 ml", 20)
        val checked = assessSwiggyIntentQuantity(oneTetraPack, Recommendations(listOf(sixBy200)))
        assertTrue("a six-by-200 ml bundle cannot stand in for one tetra pack",
            resolveSwiggyCartQuantity(checked, sixBy200) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun ordinaryMilkPacketCountsResolveTwoPhysicalSkusAcrossWholeSourceWordOrders() {
        data class Case(val source: String, val title: String)
        val cases = listOf(
            Case("two packets milk", "Milk"),
            Case("milk two packets", "Milk"),
            Case("milk packets two", "Milk"),
            Case("2 milk tetra packs", "Milk Tetra Pack"),
        )
        for ((index, case) in cases.withIndex()) {
            val item = InstructionParser.parse(case.source).single()
            assertEquals(case.source, Quantity.Count(2), item.quantity)
            val candidate = offer(case.title, "500 ml", 80 + index)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertReviewedRetailProposal(checked, candidate, 2)
        }
    }

    @Test
    fun bareCountAndRetailPackFlagCannotCreateAnExactPackageRole() {
        val candidate = offer("Frozen Vegetables 6 Pieces", "6 pieces", 30)
        val item = com.example.beta.automation.ParsedItem(
            rawText = "2 frozen vegetables",
            query = "frozen vegetables",
            quantity = Quantity.Count(2),
            retailPackCount = true,
        )
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun optionalOfNamedIdentityAndGenericWrapperFlagsDoNotAuthorizeExactCounts() {
        val candidate = offer("Honey 6 Pieces", "6 pieces", 40)
        val namedOptionalOf = com.example.beta.automation.ParsedItem(
            rawText = "one jar honey",
            query = "honey jar",
            quantity = Quantity.Count(1),
            retailPackCount = true,
        )
        assertNotNull(sourceRetailIdentityConstraint(namedOptionalOf.rawText))
        val namedChecked = assessSwiggyIntentQuantity(namedOptionalOf, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(namedChecked, candidate) is SwiggyCartQuantityResolution.Held)

        val genericWithoutReceipt = com.example.beta.automation.ParsedItem(
            rawText = "one dabba frozen vegetables",
            query = "frozen vegetables",
            quantity = Quantity.Count(1),
            retailPackCount = true,
        )
        val genericChecked = assessSwiggyIntentQuantity(genericWithoutReceipt, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(genericChecked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun proofFlagMutationAndSourceProofRemovalCannotChangeValidatedPacketCount(): Unit {
        val source = "one packet frozen vegetables"
        val item = InstructionParser.parse(source).single()
        val candidate = offer("Frozen Vegetables 6 Pieces", "6 pieces", 50)
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertTrue(swiggyCountResolutionDiagnostic(checked, candidate),
            resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.RetailProposal)

        val mutations = listOf(
            checked.copy(requiresIntentQuantityProof = false),
            checked.copy(requiresIntentQuantityProof = true, intentQuantityProvenance = null),
            checked.copy(retailPackCount = false),
        )
        mutations.forEachIndexed { index, mutated ->
            assertTrue("mutated package proof $index must also fail without reassessment",
                resolveSwiggyCartQuantity(mutated, candidate) is SwiggyCartQuantityResolution.Held)
            val reassessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(candidate)))
            assertTrue("mutated package proof $index must be held: ${swiggyCountResolutionDiagnostic(reassessed, candidate)}",
                resolveSwiggyCartQuantity(reassessed, candidate) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun repeatedEqualCountsRemainHeldInsteadOfChoosingAnOccurrence(): Unit {
        val instruction = "2 packets frozen snacks 2"
        val parsed = InstructionParser.parse(instruction).single()
        val candidate = offer("Frozen Snacks 12 Pieces", "12 pieces", 60)
        assertTrue("a repeated equal source amount is ambiguous or review-held",
            parsed.quantitySignal != null || parsed.untypedSourceNumericIssue() != null)
        val checked = assessSwiggyIntentQuantity(parsed, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun ordinaryMilkTetraPackSizeIsRecognizedInAllThreeWordOrders() {
        val instructions = listOf(
            "500 ml milk tetra pack",
            "milk 500 ml tetra pack",
            "milk tetra pack 500 ml",
        )
        for ((index, source) in instructions.withIndex()) {
            val item = InstructionParser.parse(source).single()
            assertEquals(source, Quantity.Volume(500), item.quantity)
            val candidate = offer("Milk Tetra Pack", "500 ml", 70 + index)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertEquals("$source should match the ordinary 500 ml SKU",
                SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(checked, candidate))
        }
    }

    @Test
    fun bareCountAndRetailPackFlagStayHeldForPhysicalAndPieceOffersAcrossIdentities() {
        data class Case(val identity: String, val physical: String, val pieces: String)
        val cases = listOf(
            Case("milk", "500 ml", "6 pieces"),
            Case("frozen snacks", "500 g", "12 pieces"),
            Case("biscuits", "200 g", "6 pieces"),
        )

        var candidateIndex = 100
        for (case in cases) {
            val item = com.example.beta.automation.ParsedItem(
                rawText = "2 ${case.identity}",
                query = case.identity,
                quantity = Quantity.Count(2),
                retailPackCount = true,
            )
            for (candidate in listOf(
                offer(case.identity, case.physical, candidateIndex++),
                offer("${case.identity} ${case.pieces}", case.pieces, candidateIndex++),
            )) {
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                assertTrue("bare source count cannot authorize ${case.identity} / ${candidate.label}",
                    resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun parserProvenPacketCountAgainstOrdinarySingleSkuIsProposalAndCannotBeMutatedIntoExact() {
        data class Case(val source: String, val label: String, val size: String)
        val cases = listOf(
            Case("two packets milk", "Milk Packet", "500 ml"),
            Case("milk two packets", "Milk Packet", "500 ml"),
            Case("2 milk tetra packs", "Milk Tetra Pack", "500 ml"),
        )

        for ((index, case) in cases.withIndex()) {
            val item = InstructionParser.parse(case.source).single()
            assertEquals(case.source, Quantity.Count(2), item.quantity)
            assertTrue("${case.source} must retain parser source proof", item.requiresIntentQuantityProof)
            val proof = requireNotNull(item.intentQuantityProvenance) {
                "${case.source} must retain source provenance"
            }
            assertEquals(com.example.beta.automation.IntentQuantityOrigin.SOURCE_NUMERIC, proof.origin)
            assertEquals(case.source, proof.primaryText)
            val ordinary = offer(case.label, case.size, 120 + index)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(ordinary)))
            val result = resolveSwiggyCartQuantity(checked, ordinary)
            assertTrue("${case.source} should offer two ordinary SKUs, not silently authorize Exact",
                result is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(2, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)

            val mutations = listOf(
                checked.copy(requiresIntentQuantityProof = false),
                checked.copy(intentQuantityProvenance = null),
                checked.copy(quantity = Quantity.Count(3)),
            )
            mutations.forEachIndexed { mutationIndex, mutated ->
                val reassessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(ordinary)))
                assertTrue("${case.source} mutation $mutationIndex must be held",
                    resolveSwiggyCartQuantity(reassessed, ordinary) is SwiggyCartQuantityResolution.Held)
            }

            val badOffers = listOf(
                offer(case.label, "6 x 500 ml", 130 + index),
                offer(case.label, "500 ml / 1 kg", 140 + index),
            )
            for (badOffer in badOffers) {
                val badChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(badOffer)))
                assertTrue("${case.source} cannot resolve a bundle or malformed offer",
                    resolveSwiggyCartQuantity(badChecked, badOffer) is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun namedRetailCountRemainsExactButBundleIsHeldAndPhysicalTotalStillResolves() {
        val jars = InstructionParser.parse("two jars of honey").single()
        assertEquals(Quantity.Count(2), jars.quantity)
        assertNotNull(jars.intentQuantityProvenance)
        val unknownJar = offer("Honey Jar", "500 g", 150)
        assertTrue(resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(jars, Recommendations(listOf(unknownJar))), unknownJar,
        ) is SwiggyCartQuantityResolution.Held)
        val honeyJar = offer("Honey Jar 500 g", "1 jar", 153)
        val checkedJars = assessSwiggyIntentQuantity(jars, Recommendations(listOf(honeyJar)))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(checkedJars, honeyJar))

        val honeyBundle = offer("Honey Jar", "2 x 500 g", 151)
        val checkedBundle = assessSwiggyIntentQuantity(jars, Recommendations(listOf(honeyBundle)))
        assertTrue("a two-unit bundle cannot stand in for two ordinary jars",
            resolveSwiggyCartQuantity(checkedBundle, honeyBundle) is SwiggyCartQuantityResolution.Held)

        val physical = InstructionParser.parse("500 ml milk tetra pack").single()
        val milkTetra = offer("Milk Tetra Pack", "500 ml", 152)
        val checkedPhysical = assessSwiggyIntentQuantity(physical, Recommendations(listOf(milkTetra)))
        assertEquals("source-stated physical total remains an exact one-SKU match",
            SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(checkedPhysical, milkTetra))
    }

    @Test
    fun namedCompoundContainerCountsStayExactOnlyWithOriginalFullReceiptAcrossRoutes() {
        val cases = listOf(
            CompoundPackCase("two boxes of tea bags", "tea bags", "box", 2, "two"),
            CompoundPackCase("two boxes of bottle brush", "bottle brush", "box", 2, "two"),
        )
        data class Route(val name: String, val prepare: (CompoundPackCase) -> ParsedItem)
        val routes = listOf(
            Route("parser") { case -> InstructionParser.parse(case.source).single() },
            Route("v1-count") { case -> prepareV1CompoundPackCase(case, "count") },
            Route("v1-pack") { case -> prepareV1CompoundPackCase(case, "pack") },
            Route("v2-pack") { case -> prepareV2CompoundPackCase(case, "pack") },
        )
        val failures = mutableListOf<String>()
        var offerIndex = 1000

        cases.forEachIndexed { caseIndex, case ->
            val v2Count = runCatching { prepareV2CompoundPackPreparation(case, "count") }
            if (v2Count.isFailure) {
                failures += "case=$caseIndex route=v2-count preparation=${v2Count.exceptionOrNull()?.javaClass?.simpleName}"
            } else {
                val preparation = v2Count.getOrThrow()
                if (preparation.items.isNotEmpty() || preparation.unresolvedText != listOf(case.source)) {
                    failures += "case=$caseIndex route=v2-count expected empty items with exact original source unresolved"
                }
            }
            routes.forEach { route ->
                val attempted = runCatching { route.prepare(case) }
                if (attempted.isFailure) {
                    failures += "case=$caseIndex route=${route.name} preparation=${attempted.exceptionOrNull()?.javaClass?.simpleName}"
                    return@forEach
                }
                val item = attempted.getOrThrow()
                val receipt = item.validNamedRetailReceipt()
                val proof = item.intentQuantityProvenance
                val nominalTokens = intentEvidenceTokens(case.nominal)
                val diagnostic = "case=$caseIndex route=${route.name} quantity=${item.quantity} " +
                    "receipt=${receipt?.packaging ?: "none"}"
                val sourceReceipt = sourceRetailCountReceipt(case.source)
                val sourceNominalMatches = sourceReceipt != null &&
                    case.source.substring(sourceReceipt.identity) == case.nominal &&
                    intentEvidenceTokens(case.source.substring(sourceReceipt.identity)).containsAll(nominalTokens)
                val namedReceiptValid = receipt != null && receipt.source == case.source &&
                    receipt.packaging == "box" && case.source.substring(receipt.identity) == case.nominal &&
                    sourceNominalMatches
                val v2RetailProofValid = proof != null && groceryIntentSourceRetailReceiptValid(proof) &&
                    proof.primaryText == case.source && proof.retailContainer == "box" &&
                    proof.local?.value?.compareTo(BigDecimal(2)) == 0 && sourceNominalMatches
                if (!namedReceiptValid && !v2RetailProofValid) {
                    failures += "$diagnostic valid source-bound full-nominal box receipt/proof missing"
                    return@forEach
                }
                if (item.rawText != case.source || proof?.primaryText != case.source ||
                    item.quantity != Quantity.Count(2) || proof?.local?.value?.compareTo(BigDecimal(2)) != 0 ||
                    !intentEvidenceTokens(item.query).containsAll(nominalTokens)) {
                    failures += "$diagnostic source/count/full query identity not retained"
                }

                val size = if (case.nominal == "tea bags") "500 g" else "1 piece"
                val unknown = offer("${case.nominal} Box $size", size, offerIndex++)
                val unknownChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(unknown)))
                if (resolveSwiggyCartQuantity(unknownChecked, unknown) !is SwiggyCartQuantityResolution.Held) {
                    failures += "$diagnostic contents alone proved an unsupported outer count"
                }
                // An explicit same-unit field proves the requested boxes;
                // one incidental inner piece alone cannot prove one box.
                val matching = offer("${case.nominal} Box", "1 box", offerIndex++)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(matching)))
                val exact = resolveSwiggyCartQuantity(checked, matching)
                if (exact != SwiggyCartQuantityResolution.Exact(2)) {
                    failures += "$diagnostic matching source-box SKU expected Exact(2), got $exact"
                }

                val wrongOuter = offer("${case.nominal} Jar $size", size, offerIndex++)
                val outerChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(wrongOuter)))
                val outerResult = resolveSwiggyCartQuantity(outerChecked, wrongOuter)
                if (outerResult !is SwiggyCartQuantityResolution.Held) {
                    failures += "$diagnostic conflicting outer form resolved $outerResult"
                }

                val bundle = offer("${case.nominal} 3 Boxes $size", size, offerIndex++)
                val bundleChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(bundle)))
                val bundleResult = resolveSwiggyCartQuantity(bundleChecked, bundle)
                if (bundleResult !is SwiggyCartQuantityResolution.Held) {
                    failures += "$diagnostic outer bundle resolved $bundleResult"
                }

                val shortName = case.nominal.substringAfter(' ')
                val narrowed = offer("$shortName Box $size", size, offerIndex++)
                val narrowedChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(narrowed)))
                val narrowedResult = resolveSwiggyCartQuantity(narrowedChecked, narrowed)
                if (narrowedResult !is SwiggyCartQuantityResolution.Held) {
                    failures += "$diagnostic narrowed nominal resolved $narrowedResult"
                }

                val originalProof = requireNotNull(item.intentQuantityProvenance)
                val mutations = listOf(
                    item.copy(rawText = "${item.rawText} changed"),
                    item.copy(quantity = Quantity.Count(3)),
                    item.copy(intentQuantityProvenance = originalProof.copy(
                        sourceCountRole = com.example.beta.automation.IntentSourceCountRole.REQUESTED_PIECES,
                    )),
                )
                mutations.forEachIndexed { mutationIndex, mutated ->
                    val reassessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(matching)))
                    val mutationResult = resolveSwiggyCartQuantity(reassessed, matching)
                    if (mutationResult !is SwiggyCartQuantityResolution.Held) {
                        failures += "$diagnostic mutation=$mutationIndex resolved $mutationResult"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun physicalTotalsUseTheirDimensionRatherThanTheProofFlagForIncidentalPieceCounts() {
        val rows = listOf(
            com.example.beta.automation.ParsedItem("500 g chicken curry cut", "chicken curry cut", Quantity.Weight(500)),
            InstructionParser.parse("500 g chicken curry cut").single(),
        )
        val offers = listOf(
            RecommendationCandidate("plain-pieces", "Chicken Curry Cut 500 g 10 pcs"),
            RecommendationCandidate("range-pieces", "Chicken Curry Cut 500 g 8-10 pcs"),
        )
        for (item in rows) {
            for (candidate in offers) {
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(checked, candidate)
                if (candidate.spinId == "plain-pieces") {
                    assertEquals("physical total must not use incidental pieces as a divisor",
                        SwiggyCartQuantityResolution.Exact(1), result)
                } else {
                    // The physical evidence validator already rejects ranges.
                    // Changing the requested dimension does not bypass it.
                    assertTrue("a range remains held on both routes", result is SwiggyCartQuantityResolution.Held)
                }
            }
            val bundle = RecommendationCandidate("physical-bundle", "Chicken Curry Cut 2 x 250 g")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(bundle)))
            assertTrue(resolveSwiggyCartQuantity(checked, bundle) is SwiggyCartQuantityResolution.Held)
        }
    }

    private fun offer(label: String, size: String, index: Int): RecommendationCandidate {
        val spin = "synthetic-package-spin-$index"
        val sku = "synthetic-package-sku-$index"
        return RecommendationCandidate(
            spinId = spin,
            label = label,
            skuId = sku,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = spin,
                skuId = sku,
                fields = CatalogueQuantityFields(variantName = size),
            ),
        )
    }
}
