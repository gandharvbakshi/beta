package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic diagnostics for quantity ownership across parser, V1, V2 and catalogue routes. */
class GroceryIntentQuantityRouteParityTest {
    private enum class DeclaredCapabilityDifference {
        NONE,
        PARSER_KEEPS_AMBIGUOUS_PIECE_DESCRIPTOR,
        PARSER_LACKS_RETAIL_CONTENTS_PROOF,
    }

    private data class Request(
        val text: String,
        val name: String,
        val amount: String? = null,
        val unit: String = "unspecified",
        val pack: String? = null,
        val packUnit: String = "unspecified",
        val packaging: String? = null,
        val declaredDifference: DeclaredCapabilityDifference = DeclaredCapabilityDifference.NONE,
    )

    private data class Route(
        val rows: List<String>,
        val itemCount: Int,
        val unresolvedCount: Int,
        val resolutions: List<List<String>>,
        val blocked: String? = null,
    )

    private val requests = listOf(
        Request("eggs", "eggs"),
        Request("2 milk", "milk", "2", "count"),
        Request("one packet frozen snacks", "frozen snacks", "1", "pack", packaging = "packet"),
        Request("milk two packets", "milk", "2", "pack", packaging = "packet"),
        Request("two packets milk", "milk", "2", "pack", packaging = "packet"),
        Request("2 milk tetra packs", "milk", "2", "pack", packaging = "tetra_pack"),
        Request("one bottle brush", "bottle brush", "1", "count"),
        Request("two jars of honey", "honey", "2", "pack", packaging = "jar"),
        Request("2 pieces frozen snacks", "frozen snacks", "2", "piece",
            declaredDifference = DeclaredCapabilityDifference.PARSER_KEEPS_AMBIGUOUS_PIECE_DESCRIPTOR),
        Request("2 packets of 6 biscuits", "biscuits", "2", "pack", "6", "piece", "packet",
            DeclaredCapabilityDifference.PARSER_LACKS_RETAIL_CONTENTS_PROOF),
    )

    @Test fun equalCapabilityRequestsRemainEquivalentAcrossParserV1AndV2() {
        val common = requests.filter { it.declaredDifference == DeclaredCapabilityDifference.NONE }
        assertEquals(8, common.size)
        common.forEachIndexed { index, request ->
            val parserItems = InstructionParser.parse(request.text)
            val v1 = v1Route(request)
            val v2 = v2Route(request)
            val parser = route(parserItems, emptyList(), request)
            assertEquivalentRoute("case=$index parser/V1", parser, v1)
            assertEquivalentRoute("case=$index parser/V2", parser, v2)
        }
    }

    @Test fun v1AndV2CatalogueDecisionsRemainEquivalentAcrossAllTenRequests() {
        requests.forEachIndexed { index, request ->
            assertEquivalentRoute("case=$index V1/V2", v1Route(request), v2Route(request))
        }
    }

    // These two rows record known model-off usability gaps, not qualification or whole-basket success.
    @Test fun ambiguousPieceDescriptorIsDeniedByEveryCatalogueAcrossAllThreeRoutes() {
        val request = requests[8]
        assertEquals(DeclaredCapabilityDifference.PARSER_KEEPS_AMBIGUOUS_PIECE_DESCRIPTOR,
            request.declaredDifference)
        val parserItem = InstructionParser.parse(request.text).single()
        assertEquals(Quantity.Default, parserItem.quantity)
        assertNotNull(parserItem.quantitySignal)
        assertNull(parserItem.intentQuantityProvenance)

        val parser = route(listOf(parserItem), emptyList(), request)
        val v1 = v1Route(request)
        val v2 = v2Route(request)
        listOf("parser" to parser, "V1" to v1, "V2" to v2).forEach { (name, result) ->
            assertEquals("$name must retain one item source", 1, result.itemCount)
            assertEquals("$name must evaluate all synthetic catalogues", 5, result.resolutions.single().size)
            assertTrue("$name must deny every candidate: ${result.resolutions}",
                result.resolutions.single().all(::isDenied))
        }
        assertEquals(normalizedResolutions(v1), normalizedResolutions(v2))
        listOf(v1Preparation(request), v2Preparation(request)).forEach { prepared ->
            val item = prepared.items.single()
            assertEquals(Quantity.Count(2), item.quantity)
            assertTrue(item.logicalPieceCount)
            assertNotNull(item.validSourcePieceAmount())
        }
    }

    @Test fun nestedRetailContentsRequireTypedProofBeforeOfferingOuterCount() {
        val request = requests[9]
        assertEquals(DeclaredCapabilityDifference.PARSER_LACKS_RETAIL_CONTENTS_PROOF,
            request.declaredDifference)
        val parserItem = InstructionParser.parse(request.text).single()
        assertEquals(Quantity.Count(2), parserItem.quantity)
        assertNull(parserItem.containerContentsProof)
        assertNull(parserItem.intentQuantityProvenance)
        assertTrue(parserItem.retailPackCount)

        val parser = route(listOf(parserItem), emptyList(), request)
        assertEquals(5, parser.resolutions.single().size)
        assertTrue("parser has no retail-contents proof: ${parser.resolutions}",
            parser.resolutions.single().all(::isDenied))

        val v1Preparation = v1Preparation(request)
        val v2Preparation = v2Preparation(request)
        val v1Item = v1Preparation.items.single()
        val v2Item = v2Preparation.items.single()
        listOf("V1" to v1Item, "V2" to v2Item).forEach { (name, item) ->
            assertEquals("$name preserves the spoken outer purchase count", Quantity.Count(2), item.quantity)
            assertTrue("$name keeps the typed contents size in the query", "6 pieces" in item.query)
            assertNotNull("$name binds outer count and inner size independently", item.validContainerContentsProof())
        }
        val v1 = route(v1Preparation.items, v1Preparation.unresolvedText, request)
        val v2 = route(v2Preparation.items, v2Preparation.unresolvedText, request)
        val expected = listOf("held", "held", "proposal2", "held", "held")
        assertEquals(expected, normalizedResolutions(v1).single())
        assertEquals(expected, normalizedResolutions(v2).single())
        assertFalse((normalizedResolutions(v1) + normalizedResolutions(v2)).flatten().any { it.startsWith("exact") })
    }

    private fun assertEquivalentRoute(label: String, expected: Route, actual: Route) {
        assertEquals("$label item count", expected.itemCount, actual.itemCount)
        assertEquals("$label unresolved count", expected.unresolvedCount, actual.unresolvedCount)
        assertEquals("$label catalogue outcomes", normalizedResolutions(expected), normalizedResolutions(actual))
    }

    private fun normalizedResolutions(route: Route): List<List<String>> = route.resolutions.map { results ->
        results.map { if (isDenied(it)) "held" else it }
    }

    private fun isDenied(outcome: String): Boolean = outcome == "identity-held" || outcome == "held"

    @Test fun croppedV1AndV2QuotesAreRecordedAgainstWholeInstruction() {
        val instruction = "milk 2 packets bread"
        val quote = "milk 2 packets"
        val crop = Request(instruction, "milk", "2", "pack", packaging = "packet")
        val v1 = try {
            v1Preparation(crop, quote)
        } catch (review: IntentNeedsReviewException) {
            null
        }
        val v2 = v2Preparation(crop, quote)
        val diagnostic = "v1Blocked=${v1 == null},v1Items=${v1?.items?.size},v1Unresolved=${v1?.unresolvedText?.size},v2Items=${v2.items.size},v2Unresolved=${v2.unresolvedText.size}"
        assertTrue(diagnostic, v1 == null || v1.items.isEmpty() || v1.unresolvedText.isNotEmpty())
        assertTrue(diagnostic, v2.items.isEmpty() || v2.unresolvedText.isNotEmpty())
    }

    @Test fun v2TwoRowPreparationsRetainWholeInstructionContext() {
        val cases = listOf(
            TwoRowCase("2 pieces milk bread", "2 pieces milk", "bread", safe = false),
            TwoRowCase("milk 2 pieces bread", "milk 2 pieces", "bread", safe = false),
            TwoRowCase("2 pieces milk, bread", "2 pieces milk", "bread", safe = true),
            TwoRowCase("bread, milk 2 pieces", "milk 2 pieces", "bread", safe = true),
            TwoRowCase("not 2 pieces milk, bread", "2 pieces milk", "bread", safe = false),
            TwoRowCase("please order 2 pieces milk, bread", "2 pieces milk", "bread", safe = true),
            TwoRowCase("about 2 pieces milk, bread", "2 pieces milk", "bread", safe = false),
            TwoRowCase("milk, 2 pieces; bread", "milk, 2 pieces", "bread", safe = true),
            TwoRowCase("milk, 2 pieces bread", "milk, 2 pieces", "bread", safe = false),
            TwoRowCase("For breakfast: 2 pieces milk bread", "2 pieces milk", "bread", safe = false, purposeGroup = true),
            TwoRowCase("For breakfast: milk, 2 pieces bread", "milk, 2 pieces", "bread", safe = false, purposeGroup = true),
            TwoRowCase("For breakfast: 2 pieces milk, bread", "2 pieces milk", "bread", safe = true, purposeGroup = true),
            TwoRowCase("For breakfast: milk, 2 pieces, bread", "milk, 2 pieces", "bread", safe = true, purposeGroup = true),
        )
        val failures = mutableListOf<String>()
        cases.forEachIndexed { index, case ->
            val prepared = prepareTwoRowV2(case.instruction, case.milkSource, case.breadSource, case.purposeGroup)
            val diagnostic = "case=$index;sourceLen=${case.instruction.length};rows=${prepared.items.size};unresolved=${prepared.unresolvedText.size};excluded=${prepared.excludedText.size}"
            val passed = if (case.safe) prepared.items.size == 2 && prepared.unresolvedText.isEmpty()
                else if (case.purposeGroup) prepared.items.isEmpty() &&
                    case.instruction in prepared.excludedText && prepared.recoverySnapshot != null
                else prepared.items.none { it.query == "milk" } &&
                    (case.milkSource in prepared.excludedText || prepared.unresolvedText.isNotEmpty()) &&
                    prepared.recoverySnapshot != null
            if (!passed) failures += diagnostic
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test fun unstatedQuantitySelectsOneSkuWithoutConvertingItsInnerPiecesAcrossAllRoutes() {
        val request = Request("eggs", "eggs")
        val routes = listOf(
            "parser" to InstructionParser.parse(request.text).single(),
            "v1" to v1Preparation(request).items.single(),
            "v2" to v2Preparation(request).items.single(),
        )
        val offers = listOf(
            "typed six pieces" to candidate("typed-six", "Eggs 6 Pieces", CatalogueQuantityFields(variantName = "6 pieces")),
            "typed one packet of six" to candidate("typed-one-packet-six", "Eggs 1 Packet of 6 Pieces", CatalogueQuantityFields(variantName = "1 packet of 6 pieces")),
            "two packets of six" to candidate("mixed-two-packets", "Eggs 2 Packets of 6 Pieces", CatalogueQuantityFields(variantName = "2 packets of 6 pieces")),
            "six pieces times two" to candidate("pieces-times-two", "Eggs 6 Pieces x 2", CatalogueQuantityFields(variantName = "6 pieces x 2")),
            "two cartons" to candidate("two-cartons", "Eggs 2 Cartons", CatalogueQuantityFields(variantName = "2 cartons")),
            "conflicting typed fields" to candidate("conflicting-fields", "Eggs 6 Pieces", CatalogueQuantityFields(variantName = "6 pieces", quantityDescription = "8 pieces")),
            "bogo" to candidate("bogo", "Eggs Buy One Get One Free", CatalogueQuantityFields(variantName = "buy one get one free")),
            "title only six eggs" to RecommendationCandidate("title-only-six", "Farm Fresh Eggs 6 Eggs"),
            "word pieces numeric multiplier" to RecommendationCandidate("word-numeric", "Eggs six pieces x 2"),
            "word pieces word multiplier" to RecommendationCandidate("word-word", "Eggs six pieces times two"),
            "split word pieces numeric multiplier" to RecommendationCandidate("split-word-numeric", "Eggs six pieces", subtitle = "x 2"),
            "split word pieces word multiplier" to RecommendationCandidate("split-word-word", "Eggs six pieces", subtitle = "times two"),
            "digit pieces numeric multiplier" to RecommendationCandidate("digit-numeric", "Eggs 6 pieces x 2"),
            "digit pieces word multiplier" to RecommendationCandidate("digit-word", "Eggs 6 pieces times two"),
            // Two separately stated relationships remain a conservative hold;
            // do not infer a nested relation by joining their fields.
            "pieces plus separate pack of one" to RecommendationCandidate("separate-pack-one", "Eggs 6 pieces", subtitle = "Pack of 1"),
        )
        // With no stated amount, one listed SKU is the existing default. Its
        // title does not authorize inner-piece division for explicit counts.
        val expectedExact = setOf("typed six pieces", "typed one packet of six", "title only six eggs")
        val failures = mutableListOf<String>()
        for ((routeName, item) in routes) {
            for ((offerName, offer) in offers) {
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(offer)))
                val resolution = resolveSwiggyCartQuantity(checked, offer)
                val diagnostic = "$routeName/$offerName;q=${item.quantity};proof=${item.requiresIntentQuantityProof};origin=${item.intentQuantityProvenance?.origin ?: "none"};resolution=$resolution"
                val passes = if (offerName in expectedExact) resolution == SwiggyCartQuantityResolution.Exact(1)
                    else resolution is SwiggyCartQuantityResolution.Held
                if (!passes) failures += diagnostic
            }
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }

    private data class TwoRowCase(
        val instruction: String,
        val milkSource: String,
        val breadSource: String,
        val safe: Boolean,
        val purposeGroup: Boolean = false,
    )

    private fun prepareTwoRowV2(instruction: String, milkSource: String, breadSource: String,
                               purposeGroup: Boolean = false): GroceryIntentPreparation {
        fun row(index: Int, source: String, name: String, quantity: BigDecimal?, unit: String, quantityText: String?): GroceryIntentReviewV2ReadyRow {
            val start = instruction.indexOf(source)
            require(start >= 0 && instruction.indexOf(source, start + 1) < 0)
            val item = GroceryIntentReviewV2Item(
                name = name,
                quantity = quantity,
                quantityUnit = unit,
                packValue = null,
                packUnit = "unspecified",
                packaging = null,
                required = emptyList(),
                excluded = emptyList(),
                sourceText = source,
                supportingText = emptyList(),
                needsClarification = false,
                quantityText = quantityText,
                packText = null,
            )
            val primary = GroceryIntentReviewV2Quote(source, start, start + source.length)
            return GroceryIntentReviewV2ReadyRow(
                rowIndex = index,
                item = item,
                evidence = GroceryIntentReviewV2Evidence(primary, emptyList()),
                quantityOrigin = if (quantity == null) "not_stated" else "stated",
                amountRoleStatus = if (quantity == null) "not_stated" else "matched",
            )
        }
        val readyRows = listOf(
            milkSource to "milk",
            breadSource to "bread",
        ).sortedBy { instruction.indexOf(it.first) }.mapIndexed { index, (source, name) ->
            val amount = if (name == "milk") BigDecimal.valueOf(2) else null
            row(index, source, name, amount, if (amount == null) "unspecified" else "piece", amount?.let { "2" })
        }
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = 2,
            readyRows = readyRows,
            blockedRows = emptyList(),
            groups = if (!purposeGroup) emptyList() else listOf(GroceryIntentReviewV2Group(
                status = "structurally_verified",
                amountStatus = "amounts_unspecified",
                source = GroceryIntentReviewV2Quote(instruction, 0, instruction.length),
                header = GroceryIntentReviewV2Quote("For breakfast:", 0, "For breakfast:".length),
                memberIndices = readyRows.map { it.rowIndex },
                members = readyRows.map { GroceryIntentReviewV2GroupMember(it.rowIndex, it.item.quantity,
                    it.item.quantityUnit, if (it.item.quantity == null) "not_stated" else "local") },
                packaging = null, total = null, each = null,
            )),
        ).prepareForReview()
    }

    private fun v1Preparation(request: Request, source: String = request.text) = GroceryIntentResponse.parse(
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":"${esc(request.text)}","items":[{"name":"${esc(request.name)}","quantity":${request.amount ?: "null"},"quantity_unit":"${request.unit}","pack_value":${request.pack ?: "null"},"pack_unit":"${request.packUnit}","required":[${request.packaging?.let { "\"$it\"" } ?: ""}],"excluded":[],"source_text":"${esc(source)}","needs_clarification":false}],"model_needs_clarification":false},"model":"synthetic","promptVersion":"route-parity","latencyMs":0,"usage":{}}""",
        request.text,
    ).draft.prepareForReview()

    private fun v1Route(request: Request): Route = try {
        v1Preparation(request).let { route(it.items, it.unresolvedText, request) }
    } catch (review: IntentNeedsReviewException) {
        Route(emptyList(), 0, review.affectedSourceText.size, emptyList(), "intent_needs_review")
    }

    private fun v2Preparation(request: Request, source: String = request.text): GroceryIntentPreparation {
        val start = request.text.indexOf(source)
        require(start >= 0)
        val end = start + source.length
        val quantityText = request.amount?.let { amount ->
            val token = when {
                request.text.contains("$amount") -> amount
                amount == "1" && request.text.contains("one", ignoreCase = true) -> "one"
                amount == "2" && request.text.contains("two", ignoreCase = true) -> "two"
                else -> amount
            }
            "\"$token\""
        } ?: "null"
        val amountStatus = if (request.amount == null && request.pack == null) "not_stated" else "matched"
        val wire = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"${esc(request.text)}","source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":"${esc(request.name)}","quantity":${request.amount ?: "null"},"quantity_unit":"${request.unit}","pack_value":${request.pack ?: "null"},"pack_unit":"${request.packUnit}","packaging":${request.packaging?.let { "\"$it\"" } ?: "null"},"required":[],"excluded":[],"source_text":"${esc(source)}","supporting_text":[],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":${request.pack?.let { "\"$it\"" } ?: "null"}},"evidence":{"primary":{"text":"${esc(source)}","start_utf16":$start,"end_utf16":$end},"supporting":[]},"quantity_origin":"${if (request.amount == null) "not_stated" else "stated"}","amount_role_status":"$amountStatus"}],"blocked_rows":[]},"model":"synthetic","promptVersion":"route-parity","latencyMs":0,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(wire, request.text).draft.prepareForReview()
    }

    private fun v2Route(request: Request) = v2Preparation(request).let { route(it.items, it.unresolvedText, request) }

    private fun route(items: List<com.example.beta.automation.ParsedItem>, unresolved: List<String>, request: Request): Route {
        val outcomes = items.map { item ->
            val resolutions = catalogues(request).map { candidate ->
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                if (!isSwiggyCandidateAllowed(checked, candidate)) "identity-held"
                else resolutionName(resolveSwiggyCartQuantity(checked, candidate))
            }
            val summary = "sourceLen=${item.rawText.length};q=${item.quantity};pack=${item.retailPackCount};pieces=${item.logicalPieceCount};proof=${item.requiresIntentQuantityProof};origin=${item.intentQuantityProvenance?.origin ?: "none"};final=$resolutions"
            summary to resolutions
        }
        return Route(outcomes.map { it.first }, items.size, unresolved.size, outcomes.map { it.second })
    }

    private fun resolutionName(value: SwiggyCartQuantityResolution): String = when (value) {
        is SwiggyCartQuantityResolution.Exact -> "exact${value.cartUnits}"
        is SwiggyCartQuantityResolution.RetailProposal -> "proposal${value.cartUnits}"
        is SwiggyCartQuantityResolution.Held -> "held"
    }

    private fun esc(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun catalogues(request: Request) = listOf(
        candidate("ordinary-500ml", "${catalogueIdentity(request)} 500 ml", CatalogueQuantityFields(variantName = "500 ml")),
        candidate("ordinary-500g", "${catalogueIdentity(request)} 500 g", CatalogueQuantityFields(variantName = "500 g")),
        candidate("six-pieces", "${catalogueIdentity(request)} 6 pieces", CatalogueQuantityFields(variantName = "6 pieces")),
        candidate("one-packet-six", "${catalogueIdentity(request)} 1 packet of 6 pieces", CatalogueQuantityFields(variantName = "1 packet of 6 pieces")),
        candidate("two-outers", "${catalogueIdentity(request)} 2 ${request.packagingPhrase() ?: "packs"}", CatalogueQuantityFields(variantName = "2 ${request.packagingPhrase() ?: "packs"}")),
    )

    private fun catalogueIdentity(request: Request) = listOfNotNull(request.name, request.packagingPhrase()).joinToString(" ")

    private fun Request.packagingPhrase(): String? = when (packaging) {
        "packet" -> "packets"
        "tetra_pack" -> "tetra packs"
        "jar" -> "jars"
        else -> packaging?.replace('_', ' ')
    }

    private fun candidate(id: String, title: String, fields: CatalogueQuantityFields) = RecommendationCandidate(
        spinId = id,
        skuId = id,
        label = title,
        catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", id, id, fields),
    )
}
