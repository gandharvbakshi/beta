package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceSource
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic safety regressions for parser normalization and source ownership boundaries. */
class GroceryIntentParserBoundaryRegressionTest {
    @Test
    fun exactImplicitListsAreDistinctFromNestedContainerCounts() {
        val ordinary = InstructionParser.parse("2 milk 3 bread")
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), ordinary.map { it.quantity })
        assertTrue(ordinary.all { it.quantitySignal == null })
        for (source in listOf("2 boxes of 5 Star bars", "2 boxes of 24 Mantra atta")) {
            val branded = InstructionParser.parse(source)
            assertEquals(1, branded.size)
            assertEquals(Quantity.Count(2), branded.single().quantity)
            assertTrue("Existing numeric identities cannot become nested contents",
                branded.single().quantitySignal != "nested container quantity needs review")
        }
        for (source in listOf("2 boxes of 6 biscuits", "2 boxes 6 biscuits", "2 jars of 6 sweets", "2 packets of 6 biscuits",
            "2 milk 2 boxes of 6 biscuits", "milk 2 boxes of 6 biscuits", "2 boxes of 6 biscuits 3 bread",
            "2 milk two boxes of 6 biscuits", "do boxes of chhe biscuits")) {
            val items = InstructionParser.parse(source)
            val preservedOuter = items.singleOrNull()?.let {
                it.quantity == Quantity.Count(2) && it.rawText == source && it.containerContentsProof != null
            } == true
            assertTrue("Nested relation cannot become independent products: count=${items.size}; " +
                "quantities=${items.map { it.quantity }}; signals=${items.count { it.quantitySignal != null }}",
                preservedOuter || swiggyMcpItemValidationMessage(source, items) != null)
        }
    }

    @Test fun contentsBoundaryDoesNotCaptureCompleteItemsOrPhysicalSizes() {
        val failures = mutableListOf<String>()
        for (source in listOf("milk 2 packet 3 eggs", "2 milk packets 3 bread",
            "2 maggi packets 1 coke bottle", "1 packet 500 g sugar", "2 bottles 1 litre oil")) {
            val items = InstructionParser.parse(source)
            if (items.any { it.quantitySignal == "nested container quantity needs review" }) {
                failures += "$source: quantities=${items.map { it.quantity }}; signals=${items.map { it.quantitySignal }}"
            }
        }
        assertEquals(failures.joinToString("; "), emptyList<String>(), failures)
    }

    @Test
    fun ambiguousImplicitSplitsKeepWholeSourceWithoutAcquiringProof() {
        for (source in listOf("2 packets of 6 biscuits", "2 packets of 12 tea bags",
            "2 packets milk 3 bread", "two packets of six biscuits")) {
            val items = InstructionParser.parse(source)
            assertTrue("Unresolved source must remain visible", items.isNotEmpty())
            for (item in items) {
                assertEquals("Unresolved amounts must retain their original clause", source, item.rawText)
                assertNotNull("Ambiguity must not become an actionable partial count", item.quantitySignal)
                assertTrue("No partial-source proof may be manufactured", item.intentQuantityProvenance == null)
                val candidate = RecommendationCandidate("held-source", "${item.query} 500 g")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            }
        }
        val separate = InstructionParser.parse("2 packets milk, 3 bread")
        assertEquals(2, separate.size)
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), separate.map { it.quantity })
        assertEquals(listOf("2 packets milk", "3 bread"), separate.map { it.rawText })
        assertTrue(separate.all { it.quantitySignal == null && it.intentQuantityProvenance != null })
    }

    private data class SourceCase(
        val instruction: String,
        val quantity: Quantity,
        val sourceMarkers: List<String>,
        val catalogueTitle: String,
    )

    @Test
    fun explicitPackAndSpokenAmountsKeepTheirOriginalSourceSurfaceThroughPreparation() {
        val cases = listOf(
            SourceCase("milk two packets", Quantity.Count(2), listOf("two", "packets"), "Milk 500 ml"),
            SourceCase("2 packets milk", Quantity.Count(2), listOf("2", "packets"), "Milk 500 ml"),
            SourceCase("aadha kilo atta", Quantity.Weight(500), listOf("aadha", "kilo"), "Atta 500 g"),
            SourceCase("one packet frozen vegetables", Quantity.Count(1), listOf("one", "packet"),
                "Frozen Vegetables 500 g"),
        )
        val failures = mutableListOf<String>()

        for ((index, case) in cases.withIndex()) {
            val direct = InstructionParser.parse(case.instruction)
            val prepared = prepareSwiggyMcpItems(case.instruction) { null }
            if (prepared.size != 1 || direct.size != 1) {
                failures += "${case.instruction}: expected one parsed row (direct=${direct.size}, prepared=${prepared.size})"
                continue
            }
            val item = prepared.single()
            if (item.quantity != case.quantity) failures += "${case.instruction}: quantity=${item.quantity}"
            val proof = item.intentQuantityProvenance
            if (proof == null) {
                failures += "${case.instruction}: no source-bound numeric proof"
                continue
            }
            val sourceSurfaces = listOf(item.rawText, proof.rawText, proof.primaryText, proof.local?.source.orEmpty())
            for (marker in case.sourceMarkers) {
                if (sourceSurfaces.none { it.contains(marker, ignoreCase = true) }) {
                    failures += "${case.instruction}: source marker '$marker' disappeared"
                }
            }

            val candidate = RecommendationCandidate("synthetic-boundary-$index", case.catalogueTitle)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            if (proof.primaryText != case.instruction || proof.local?.source != case.instruction)
                failures += "${case.instruction}: proof is not bound to the original clause"
            if (resolution is SwiggyCartQuantityResolution.Exact &&
                resolveSwiggyCartQuantity(checked.copy(retailPackCount = !checked.retailPackCount), candidate)
                    !is SwiggyCartQuantityResolution.Held)
                failures += "${case.instruction}: changing a role flag preserved authorization"
        }

        assertEquals("Every normalized request must retain source evidence: ${failures.joinToString("; ")}",
            emptyList<String>(), failures)
    }

    @Test
    fun sourceOwnedPackOfCountsSurviveDescriptorParsingAcrossProductsAndContexts() {
        data class PackCase(val source: String, val identity: String, val count: Int, val packaging: String)
        val cases = listOf(
            PackCase("1 pack of oats", "oats", 1, "pack"),
            PackCase("2 packets of salted butter", "salted butter", 2, "packet"),
            PackCase("one packet of tea bags", "tea bags", 1, "packet"),
            PackCase("two packs of frozen vegetables", "frozen vegetables", 2, "pack"),
        )

        for (case in cases) {
            val sourceReceipt = requireNotNull(sourceRetailCountReceipt(case.source))
            assertEquals(case.packaging, sourceReceipt.packaging)
            for ((context, instruction) in listOf(
                "bare" to case.source,
                "later-row" to "rice, ${case.source}",
            )) {
                val items = prepareSwiggyMcpItems(instruction) { null }
                assertEquals("$context/$instruction must not create extra rows", if (context == "bare") 1 else 2, items.size)
                val item = items.last()
                assertEquals("$context/$instruction count", Quantity.Count(case.count), item.quantity)
                assertEquals("$context/$instruction source", case.source, item.rawText)
                assertEquals("$context/$instruction quantity signal", null, item.quantitySignal)
                assertTrue("$context/$instruction identity: ${item.query}",
                    item.query.contains(case.identity, ignoreCase = true))

                val proof = requireNotNull(item.intentQuantityProvenance)
                assertEquals(case.source, proof.rawText)
                assertEquals(case.source, proof.primaryText)
                assertEquals(case.source, proof.local?.source)
                assertEquals(item.quantity, proof.resolvedQuantity)
                assertEquals(null, proof.sourceCountRole)
                assertEquals(null, proof.retailContainer)
                assertEquals(case.identity, item.query)
                assertEquals("$context/$instruction is a generic offer, not named packaging", null, item.validNamedRetailReceipt())
                assertEquals("$context/$instruction should pass preparation validation", null,
                    swiggyMcpItemValidationMessage(instruction, items))
            }
        }

        val oats = prepareSwiggyMcpItems("1 pack of oats") { null }.single()
        val formlessOats = RecommendationCandidate("oats-without-form", "Oats")
        val formlessChecked = assessSwiggyIntentQuantity(oats, Recommendations(listOf(formlessOats)))
        assertTrue("A source pack receipt cannot infer the catalogue's missing pack form",
            resolveSwiggyCartQuantity(formlessChecked, formlessOats) is SwiggyCartQuantityResolution.Held)

        val ambiguousControls = listOf(
            "2 packets of 6 biscuits",                 // ambiguous inner-count multipack
            "two packets of six biscuits",             // spoken inner-count multipack
            "milk, and not two packets of oats",       // negated request
            "milk, get two packets of oats if cheap",  // unsupported condition
            "milk, maybe two packets of oats",         // unknown shared transition
        )
        for (instruction in ambiguousControls) {
            val items = prepareSwiggyMcpItems(instruction) { null }
            assertTrue("Control must not mint a named retail success: $instruction",
                items.none { it.intentQuantityProvenance?.sourceCountRole == IntentSourceCountRole.NAMED_RETAIL })
            // Preparation can retain unresolved words in a query. The cart
            // boundary, not necessarily the early quantity check, must deny it.
            val candidate = RecommendationCandidate("control-offer",
                if (instruction.contains("biscuits")) "Biscuits Packet 6 Pieces" else "Oats Packet 500 g")
            val checked = assessSwiggyIntentQuantity(items.last(), Recommendations(listOf(candidate)))
            assertTrue("Control must remain held at the cart boundary: $instruction",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }
        for (source in listOf("2 pack of oats", "6 pack of cola", "1 packs of oats",
            "1 pack of 2 batteries", "2 500 g packs of oats")) {
            assertEquals("Ambiguous or sized syntax cannot enter the new parser projection", null,
                sourcePacketCountSyntax(source))
        }
        for (source in listOf("2 pack of oats", "6 pack of cola")) {
            val item = InstructionParser.parse(source).single()
            assertEquals(Quantity.Default, item.quantity)
            assertNotNull(item.quantitySignal)
        }
    }

    @Test
    fun expandedLeadingQuantityCannotSilentlyDefaultEveryProduct() {
        val failures = mutableListOf<String>()
        for (instruction in listOf("2 rice sugar", "2 bread butter")) {
            val items = prepareSwiggyMcpItems(instruction) { null }
            if (items.isEmpty()) {
                failures += "$instruction: no held or represented rows"
                continue
            }
            if (items.none { it.quantity != Quantity.Default || it.quantitySignal != null }) {
                failures += "$instruction: leading quantity was silently discarded"
            }

            val recommendations = items.map { item ->
                val label = when (item.query) {
                    "rice" -> "Rice 500 g"
                    "sugar" -> "Sugar 1 kg"
                    else -> "${item.query} 500 g"
                }
                Recommendations(listOf(RecommendationCandidate("synthetic-${item.query}", label)))
            }
            val checked = items.zip(recommendations).map { (item, offers) ->
                assessSwiggyIntentQuantity(item, offers)
            }
            val resolutions = checked.zip(recommendations).map { (item, offers) ->
                resolveSwiggyCartQuantity(item, offers.candidates.single())
            }
            val expanded = items.size > 1
            if (expanded) {
                if (!resolutions.all { it is SwiggyCartQuantityResolution.Held }) {
                    failures += "$instruction: ambiguous expanded rows resolved: $resolutions"
                }
            } else {
                if (resolutions.any { it is SwiggyCartQuantityResolution.Exact }) {
                    failures += "$instruction: combined identity converted to exact quantity: $resolutions"
                }
            }
        }
        assertEquals(failures.joinToString("; "), emptyList<String>(), failures)
    }

    @Test
    fun duplicateCountsAndDefaultPlusHeldRowRetainEveryRequest() {
        val repeated = "2 milk and 2 milk"
        val repeatedRows = prepareSwiggyMcpItems(repeated) { null }
        val mergedWithReviewSignal = repeatedRows.size == 1 && repeatedRows.single().quantitySignal != null
        assertTrue("duplicate counts must remain separate or explicitly review-held: $repeatedRows",
            repeatedRows.size > 1 || mergedWithReviewSignal)
        assertNotNull(swiggyMcpItemValidationMessage(repeated, repeatedRows))

        val mixed = "milk and 21 milk"
        val mixedRows = prepareSwiggyMcpItems(mixed) { null }
        assertEquals(listOf("milk", "milk"), mixedRows.map { it.query })
        assertEquals(listOf(Quantity.Default, Quantity.Count(21)), mixedRows.map { it.quantity })
        assertNotNull(swiggyMcpItemValidationMessage(mixed, mixedRows))

        val heldRow = mixedRows.single { it.quantity == Quantity.Count(21) }
        val candidate = RecommendationCandidate("synthetic-milk", "Milk 500 ml")
        val checked = assessSwiggyIntentQuantity(heldRow, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun defaultMergeMustNotEraseEvidenceFromASeparatelyHeldNumericRow() {
        val candidate = RecommendationCandidate("synthetic-rice", "Rice 1 kg")
        val isolated = prepareSwiggyMcpItems("rice 5") { null }
        val failures = mutableListOf<String>()
        if (isolated.size != 1) failures += "isolated 'rice 5' produced ${isolated.size} rows"
        if (isolated.none { it.requiresIntentQuantityProof || it.quantitySignal != null })
            failures += "isolated 'rice 5' lost its unexplained amount evidence"
        if (swiggyMcpItemValidationMessage("rice 5", isolated) == null)
            failures += "isolated 'rice 5' passed input validation"
        isolated.forEach { item ->
            val isolatedChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            if (resolveSwiggyCartQuantity(isolatedChecked, candidate) !is SwiggyCartQuantityResolution.Held)
                failures += "isolated 'rice 5' resolved for cart use"
        }

        for (instruction in listOf("rice, rice 5", "rice 5, rice")) {
            val items = prepareSwiggyMcpItems(instruction) { null }
            val hasUnexplainedNumberEvidence = items.any {
                it.requiresIntentQuantityProof || it.quantitySignal != null || it.quantity != Quantity.Default
            }
            val validation = swiggyMcpItemValidationMessage(instruction, items)
            val finalRowsHeld = items.map { item ->
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                resolveSwiggyCartQuantity(checked, candidate)
            }.all { it is SwiggyCartQuantityResolution.Held }
            if (!hasUnexplainedNumberEvidence || (validation == null && !finalRowsHeld)) {
                failures += "duplicate default merge erased a held row: $instruction / $items"
            }
        }
        assertEquals(failures.joinToString("; "), emptyList<String>(), failures)
    }

    @Test
    fun retailAndProofBooleansCannotSubstituteForAProvenSourceRole() {
        val candidate = RecommendationCandidate("synthetic-bare-milk", "Milk 500 ml")
        val booleanOnlyRows = listOf(
            ParsedItem("2 milk", "milk", Quantity.Count(2), retailPackCount = true),
            ParsedItem("2 milk", "milk", Quantity.Count(2), requiresIntentQuantityProof = true),
            ParsedItem("2 milk", "milk", Quantity.Count(2), retailPackCount = true,
                requiresIntentQuantityProof = true),
        )

        for (bareCount in booleanOnlyRows) {
            val checked = assessSwiggyIntentQuantity(bareCount, Recommendations(listOf(candidate)))
            assertTrue(bareCount.retailPackCount || bareCount.requiresIntentQuantityProof)
            assertEquals(null, bareCount.intentQuantityProvenance?.sourceCountRole)
            assertTrue("role-less flags authorized quantity: $bareCount",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun splittingPreservesFractionAndBrandConnectorsAndDoesNotMoveAmountsAcrossRows() {
        val instruction = "one and a half kilo rice and milk two packets, One Plus charger"
        val items = prepareSwiggyMcpItems(instruction) { null }
        assertEquals(listOf("rice", "milk", "oneplus charger"), items.map { it.query })
        assertEquals(listOf(Quantity.Weight(1500), Quantity.Count(2), Quantity.Default), items.map { it.quantity })
        assertEquals(listOf("one and a half kilo rice", "milk two packets"),
            items.take(2).map { it.intentQuantityProvenance?.primaryText })
        assertEquals(null, swiggyMcpItemValidationMessage(instruction, items))
    }

    @Test
    fun implicitSplitCannotInventSourceMappingsAfterNormalization() {
        // The same original phrase remains ambiguous after normalizing a spoken
        // count; it must not be mapped to guessed per-item source substrings.
        val implicit = prepareSwiggyMcpItems("two rice 3 sugar") { null }
        assertNotNull(swiggyMcpItemValidationMessage("two rice 3 sugar", implicit))
    }

    @Test
    fun listExpansionCannotDropAnExclusion() {
        val instruction = "rice sugar without added salt"
        val items = prepareSwiggyMcpItems(instruction) { null }
        assertEquals(1, items.size)
        assertTrue(items.single().avoidPhrases.any { it.contains("salt") })
        assertNotNull(swiggyMcpItemValidationMessage(instruction, items))
    }

    @Test fun deterministicRowsRetainTheExactOriginalClauseAndProofSource() {
        val clauses = listOf(
            "ready made popcorn",
            "One Plus charger",
            "tea bags",
            "2 milk",
            "milk two packets",
            "two jars of honey",
            "2 pieces frozen snacks",
            "aadha kilo atta",
            "rice 5",
        )
        clauses.forEach { source ->
            val parsed = InstructionParser.parse(source)
            assertEquals("source=$source", listOf(source), parsed.map { it.rawText })
            parsed.forEach { item ->
                item.intentQuantityProvenance?.let { proof ->
                    assertEquals("proof rawText source=$source", source, proof.rawText)
                    assertEquals("proof primary source=$source", source, proof.primaryText)
                }
            }
        }

        val repeated = InstructionParser.parse("milk, milk")
        assertEquals(listOf("milk", "milk"), repeated.map { it.rawText })
    }

    @Test fun exactImplicitSplitRetainsPerPartSourcesButNormalizedSplitStaysHeld() {
        val exact = InstructionParser.parse("2 rice 3 sugar")
        // Exact numeric split receipts keep their own original parts. They do
        // not acquire a closed piece/retail role from an implicit delimiter.
        assertEquals(listOf("2 rice", "3 sugar"), exact.map { it.intentQuantityProvenance?.primaryText })
        exact.forEach { item ->
            item.intentQuantityProvenance?.let { proof ->
                assertEquals(item.rawText, proof.rawText)
                assertEquals(null, proof.sourceCountRole)
            }
        }

        val normalized = InstructionParser.parse("two rice 3 sugar")
        assertTrue(normalized.isNotEmpty())
        assertTrue(normalized.all { it.quantitySignal != null })
        assertTrue(normalized.all { it.intentQuantityProvenance?.sourceCountRole == null })
    }

    @Test fun originalClausePrefixesCannotBypassClosedSourceRoleChecks() {
        val candidate = RecommendationCandidate(
            "synthetic-lemon", "Lemon 3 Pieces", skuId = "synthetic-lemon",
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1, source = "search_variation", spinId = "synthetic-lemon", skuId = "synthetic-lemon",
                fields = CatalogueQuantityFields(variantName = "3 pieces"),
            ),
        )
        val failures = mutableListOf<String>()
        for (instruction in listOf("6 pieces lemon", "milk, and 6 pieces lemon", "milk, get 6 pieces lemon")) {
            val item = InstructionParser.parse(instruction).last()
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            if (resolveSwiggyCartQuantity(assessed, candidate) !is SwiggyCartQuantityResolution.Held) {
                failures += "$instruction: prefix hid the ambiguous piece source role"
            }
        }
        for (instruction in listOf("two jars of honey", "milk, and two jars of honey", "milk, get two jars of honey")) {
            val item = InstructionParser.parse(instruction).last()
            if (item.rawText != "two jars of honey" || item.validNamedRetailReceipt() == null) {
                failures += "$instruction: complete named-container source receipt was lost"
            }
        }
        assertEquals(failures.joinToString("; "), emptyList<String>(), failures)
    }

    @Test fun namedReceiptPreservesCountButAmbiguousPieceDescriptorDoesNotInventAPurchaseRole() {
        val jarSource = "two jars of honey"
        val jar = InstructionParser.parse(jarSource).single()
        assertEquals(jarSource, jar.rawText)
        val jarReceipt = requireNotNull(sourceRetailCountReceipt(jar.rawText))
        assertEquals((jar.quantity as Quantity.Count).n.toBigDecimal(), jarReceipt.count.value)
        assertNotNull(jar.validNamedRetailReceipt())

        val piecesSource = "6 pieces frozen snacks"
        val pieces = InstructionParser.parse(piecesSource).single()
        assertEquals(piecesSource, pieces.rawText)
        assertNotNull(sourcePiecePurchaseReceipt(pieces.rawText))
        // The syntax can mean a 6-piece variant OR six requested pieces.
        // Until that role is resolved, neither a matching SKU nor a compiler
        // recognizing the number is authority to rewrite Default as Count.
        assertEquals(Quantity.Default, pieces.quantity)
        assertNotNull(pieces.quantitySignal)
        assertEquals(null, pieces.intentQuantityProvenance?.sourceCountRole)

        val candidate = RecommendationCandidate(
            spinId = "frozen-snacks-six-pieces",
            skuId = "frozen-snacks-six-pieces",
            label = "Frozen Snacks 6 Pieces",
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = "frozen-snacks-six-pieces",
                skuId = "frozen-snacks-six-pieces",
                fields = CatalogueQuantityFields(variantName = "6 pieces"),
            ),
        )
        val assessed = assessSwiggyIntentQuantity(pieces, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun receiptPrefixHandlingCannotEraseConstraintsOrBindDuplicateSources() {
        val instructions = listOf(
            "milk, and not two jars of honey",
            "milk, get not two jars of honey",
            "milk, get around two jars of honey",
            "milk, get two jars of honey if cheap",
            "two jars of honey, get two jars of honey",
        )
        instructions.forEach { instruction ->
            val items = InstructionParser.parse(instruction)
            assertTrue("No source role may be inferred: $instruction",
                items.all { it.intentQuantityProvenance?.sourceCountRole == null })
        }
        // Retrieval has an older command-shell grammar; source evidence must
        // not lose command-like identity words without a closed receipt.
        assertEquals("get well tea", InstructionParser.parse("milk, get well tea").last().rawText)
        assertEquals("get 2 rice", InstructionParser.parse("milk, get 2 rice").last().rawText)

        val protected = InstructionParser.parse("milk, and one and a half kilo rice, and One Plus charger")
        assertEquals(listOf("milk", "rice", "oneplus charger"), protected.map { it.query })
        assertEquals(listOf("milk", "one and a half kilo rice", "One Plus charger"), protected.map { it.rawText })
        assertEquals(Quantity.Weight(1500), protected[1].quantity)
    }

    @Test fun namedReceiptCannotSurviveMutationOfItsSourceOrAmount() {
        val item = InstructionParser.parse("milk, get two jars of honey").last()
        assertNotNull(item.validNamedRetailReceipt())
        val candidate = RecommendationCandidate("synthetic-honey", "Honey Jar 500 g")
        val proof = requireNotNull(item.intentQuantityProvenance)
        val mutations = listOf(
            item.copy(rawText = "three jars of honey"),
            item.copy(quantity = Quantity.Count(3)),
            item.copy(query = "jam jar"),
            item.copy(intentQuantityProvenance = proof.copy(primaryText = "three jars of honey")),
            item.copy(intentQuantityProvenance = null),
            item.copy(avoidPhrases = listOf("added sugar")),
        )
        mutations.forEach { mutated ->
            assertEquals(null, mutated.validNamedRetailReceipt())
            val assessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(candidate)))
            assertTrue(resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test fun preservedVariantDescriptorCannotBecomeSeveralSmallerPacks() {
        val source = "Pampers Premium Care Pant Style Baby Diapers small medium 70 pieces"
        val item = InstructionParser.parse(source).single()
        assertEquals(Quantity.Default, item.quantity)
        assertTrue(item.query.contains("70 pieces"))
        val candidate = RecommendationCandidate(
            "synthetic-diapers", "Pampers Premium Care Pant Style Baby Diapers small medium 10 pieces",
            skuId = "synthetic-diapers",
            catalogueQuantity = CatalogueQuantityEvidence(
                1, "search_variation", "synthetic-diapers", "synthetic-diapers",
                CatalogueQuantityFields(variantName = "10 pieces"),
            ),
        )
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun sourceOwnedIdentitySurvivesPreferencesWithoutDroppingTheirExclusions() {
        val item = InstructionParser.parse("two jars of honey").single()
        val preferred = Preference("honey jar", "usual brand honey", source = PreferenceSource.USER_OVERRIDE, confidence = 1.0f)
        val unchanged = InstructionParser.applyPreferences(listOf(item), { preferred }).single()
        assertEquals(item.query, unchanged.query)
        assertNotNull(unchanged.validNamedRetailReceipt())

        val constrained = InstructionParser.applyPreferences(listOf(item), {
            preferred.copy(avoidPhrases = listOf("added sugar"))
        }).single()
        assertEquals(item.query, constrained.query)
        assertEquals(listOf("added sugar"), constrained.avoidPhrases)
        val candidate = RecommendationCandidate("synthetic-preferred-honey", "Honey Jar 500 g")
        val checked = assessSwiggyIntentQuantity(constrained, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun constrainedPieceSourcesCannotEscapeByAddingARequestPrefix() {
        val candidate = RecommendationCandidate(
            "synthetic-constrained-lemon", "Lemon 3 Pieces", skuId = "synthetic-constrained-lemon",
            catalogueQuantity = CatalogueQuantityEvidence(
                1, "search_variation", "synthetic-constrained-lemon", "synthetic-constrained-lemon",
                CatalogueQuantityFields(variantName = "3 pieces"),
            ),
        )
        val failures = mutableListOf<String>()
        for (source in listOf("6 pieces lemon without seeds", "get 6 pieces lemon without seeds")) {
            val item = InstructionParser.parse("milk, $source").last()
            assertEquals(listOf("seeds"), item.avoidPhrases)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            if (resolveSwiggyCartQuantity(checked, candidate) !is SwiggyCartQuantityResolution.Held) {
                failures += "$source: receipt=${sourcePiecePurchaseReceipt(source) != null};quantity=${item.quantity};allowed=${isSwiggyCandidateAllowed(checked, candidate)};validation=${swiggyMcpItemValidationMessage("milk, $source", listOf(item))}"
            }
        }
        assertEquals(failures.joinToString("; "), emptyList<String>(), failures)
    }

    @Test fun defaultOneSkuConservesVariantSizeButTypedPhysicalTotalsStillConvert() {
        data class Case(val source: String, val product: String, val requested: String, val wrong: String)
        val cases = listOf(
            Case("6 pieces lemon without seeds", "Lemon", "6 pieces", "3 pieces"),
            Case("rice 500 g without added salt", "Rice", "500 g", "250 g"),
            Case("milk 500 ml without lactose", "Lactose Free Milk", "500 ml", "250 ml"),
            Case("batteries 4 pieces without charger", "Batteries", "4 pieces", "2 pieces"),
        )
        fun offer(id: String, label: String, size: String) = RecommendationCandidate(
            id, "$label $size", skuId = id,
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", id, id,
                CatalogueQuantityFields(variantName = size)),
        )
        val failures = mutableListOf<String>()
        cases.forEachIndexed { index, case ->
            // Piece rows exercise the actual parser. Physical-dimension
            // rows explicitly exercise the legacy untyped Default boundary;
            // typed physical totals are a separate positive control below.
            val item = if (index == 0 || index == 3) InstructionParser.parse(case.source).single()
                else ParsedItem("${case.product} ${case.requested}", "${case.product} ${case.requested}")
            assertEquals(case.source, Quantity.Default, item.quantity)
            for ((size, matches) in listOf(case.requested to true, case.wrong to false)) {
                val candidate = offer("variant-$index-$matches", case.product, size)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(checked, candidate)
                if (if (matches) result != SwiggyCartQuantityResolution.Exact(1)
                    else result !is SwiggyCartQuantityResolution.Held) {
                    failures += "case=$index;matches=$matches;result=$result"
                }
                if (!matches) {
                    val preference = Preference(item.query, case.product, source = PreferenceSource.USER_OVERRIDE, confidence = 1.0f)
                    val preferred = InstructionParser.applyPreferences(listOf(item), { preference }).single()
                    val preferredChecked = assessSwiggyIntentQuantity(preferred, Recommendations(listOf(candidate)))
                    if (resolveSwiggyCartQuantity(preferredChecked, candidate) !is SwiggyCartQuantityResolution.Held)
                        failures += "case=$index;preference erased original size constraint"
                }
            }
        }
        // Loose discovery is not authority to interpret an untyped physical
        // total. Preserve retrieval, but keep that ambiguous final row held.
        val ambiguous = ParsedItem("1.5 litres milk", "1.5 litres milk")
        val bundle = offer("ambiguous-total", "Milk", "3 x 500 ml")
        assertTrue(isSwiggyCandidateAllowed(ambiguous, bundle))
        val checkedBundle = assessSwiggyIntentQuantity(ambiguous, Recommendations(listOf(bundle)))
        if (resolveSwiggyCartQuantity(checkedBundle, bundle) !is SwiggyCartQuantityResolution.Held)
            failures += "untyped total incorrectly became one SKU"

        val measured = InstructionParser.parse("1.5 litres milk").single()
        assertEquals(Quantity.Volume(1500), measured.quantity)
        val halfLitre = offer("measured-total", "Milk", "500 ml")
        val checkedMeasured = assessSwiggyIntentQuantity(measured, Recommendations(listOf(halfLitre)))
        assertEquals(SwiggyCartQuantityResolution.Exact(3), resolveSwiggyCartQuantity(checkedMeasured, halfLitre))
        // Existing strict pack comparison is exact across dimensions. Record
        // this conservative coverage limit rather than relaxing it without
        // catalogue evidence: six eggs plus an incidental weight still holds.
        val dualDimension = offer("dual-dimension", "Eggs", "6 pieces 360 g")
        val eggs = InstructionParser.parse("eggs 6 pieces without cracks").single()
        val checkedEggs = assessSwiggyIntentQuantity(eggs, Recommendations(listOf(dualDimension)))
        assertEquals(SwiggyCartQuantityResolution.Held("default_identity_not_proven"),
            resolveSwiggyCartQuantity(checkedEggs, dualDimension))
        assertEquals(failures.joinToString("; "), emptyList<String>(), failures)
    }
}
