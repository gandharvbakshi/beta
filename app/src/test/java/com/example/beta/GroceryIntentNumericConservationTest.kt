package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceSource
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentNumericConservationTest {
    private fun legacy(
        source: String,
        name: String,
        quantity: String? = null,
        quantityUnit: String = "unspecified",
        packValue: String? = null,
        packUnit: String = "unspecified",
    ) = GroceryIntentItem(
        name = name,
        quantity = quantity?.let { BigDecimal(it) },
        quantityUnit = quantityUnit,
        packValue = packValue?.let { BigDecimal(it) },
        packUnit = packUnit,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        needsClarification = false,
    )

    @Test
    fun recognizedIdentityAmountsCannotBecomePurchaseCountsOrDisappear() {
        val cases = listOf(
            Triple(legacy("5 Star chocolate", "Star chocolate", "5", "count"), false, "lost identity"),
            Triple(legacy("5 Star chocolate", "5 Star chocolate", "5", "count"), false, "borrowed identity"),
            Triple(legacy("2 5 Star chocolate", "5 Star chocolate", "2", "count"), true, "separate count"),
            Triple(legacy("7up", "7 up"), true, "literal default"),
            Triple(legacy("7up", "7 up", "7", "count"), false, "compact borrowed count"),
            Triple(legacy("24 mantra atta", "24 mantra atta", "24", "count"), false, "numeric identity borrowed as count"),
            Triple(legacy("one plus charger", "oneplus charger"), true, "retained word identity"),
            Triple(legacy("one plus charger", "oneplus charger", "1", "count"), true, "legacy implicit one is a default, not a source count"),
            Triple(legacy("one plus charger", "charger", "1", "count"), false, "lost word identity"),
            Triple(legacy("Maggi two-minute noodles", "maggi 2 minute noodles"), true, "retained descriptor"),
            Triple(legacy("Maggi two-minute noodles", "maggi 2 minute noodles", "2", "count"), false, "descriptor borrowed as count"),
            Triple(legacy("7 milk", "milk", "7", "count"), true, "ordinary count"),
        )
        for ((item, safe, label) in cases) {
            val issue = item.legacyNumericOwnershipIssue()
            if (safe) assertNull("$label: $issue", issue) else assertNotNull(label, issue)
        }
        val implicitOne = legacy("one plus charger", "oneplus charger", "1", "count")
        assertTrue(implicitOne.legacyImplicitUnitDefault())
        assertEquals(Quantity.Default, implicitOne.toParsedItem().quantity)
    }

    @Test
    fun numericIdentitiesRemainRequiredAtTheFinalCatalogueAndPreferenceBoundaries() {
        for (instruction in listOf("7up", "2 7 up")) {
            val item = InstructionParser.parse(instruction).single()
            val exact = RecommendationCandidate("numeric-brand", "7 UP Drink 500 ml")
            val invalid = listOf(
                RecommendationCandidate("missing-brand", "Drink 500 ml"),
                RecommendationCandidate("subtitle-only", "Drink 500 ml", subtitle = "7up"),
                RecommendationCandidate("negated", "Not 7up Drink 500 ml"),
                RecommendationCandidate("conflicting", "7up Drink 500 ml", subtitle = "5star"),
            )
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact) + invalid))
            assertTrue(instruction, swiggyIntentQuantityCandidateChecked(checked, exact))
            invalid.forEach { assertFalse("$instruction / ${it.spinId}", swiggyIntentQuantityCandidateChecked(checked, it)) }
            val changed = InstructionParser.applyPreferences(listOf(item), {
                Preference(token = it, preferredPhrase = "drink", source = PreferenceSource.USER_OVERRIDE, confidence = 1f)
            }).single()
            assertEquals("Search the original identity instead of the conflicting preference", item.query, changed.query)
            val reassessed = assessSwiggyIntentQuantity(changed, Recommendations(listOf(exact) + invalid))
            assertTrue("An exact requested identity stays usable", swiggyIntentQuantityCandidateChecked(reassessed, exact))
            invalid.forEach { assertFalse("Preference cannot admit ${it.spinId}", swiggyIntentQuantityCandidateChecked(reassessed, it)) }
            if (checked.intentQuantityProvenance != null) {
                assertFalse("Cannot strip the proof flag", swiggyIntentQuantityCandidateChecked(
                    checked.copy(requiresIntentQuantityProof = false), exact))
            }
        }
    }

    @Test
    fun allFiveNumericIdentitiesSurviveSourceQueryAndFinalCatalogueBoundaries() {
        data class IdentityCase(
            val source: String,
            val key: String,
            val title: String,
            val droppedTitle: String,
            val droppedQuery: String,
        )
        val cases = listOf(
            IdentityCase("7up drink", "7 up", "7 UP Drink 500 ml", "Drink 500 ml", "drink"),
            IdentityCase("7-Up drink", "7 up", "7 UP Drink 500 ml", "Drink 500 ml", "drink"),
            IdentityCase("5star chocolate", "5 star", "Cadbury 5 Star Chocolate", "Cadbury Chocolate", "chocolate"),
            IdentityCase("5-Star chocolate", "5 star", "Cadbury 5 Star Chocolate", "Cadbury Chocolate", "chocolate"),
            IdentityCase("24mantra atta", "24 mantra", "24 Mantra Atta 1 kg", "Atta 1 kg", "atta"),
            IdentityCase("24-Mantra atta", "24 mantra", "24 Mantra Atta 1 kg", "Atta 1 kg", "atta"),
            IdentityCase("oneplus charger", "oneplus", "One Plus Charger", "Charger", "charger"),
            IdentityCase("one plus charger", "oneplus", "OnePlus Charger", "Charger", "charger"),
            IdentityCase("Maggi two-minute noodles", "maggi 2 minute", "Maggi 2-Minute Noodles", "Maggi Noodles", "maggi noodles"),
            IdentityCase("Maggi 2-minute noodles", "maggi 2 minute", "Maggi Two Minute Noodles", "Maggi Noodles", "maggi noodles"),
            IdentityCase("Maggi two minute noodles", "maggi 2 minute", "Maggi 2-Minute Noodles", "Maggi Noodles", "maggi noodles"),
        )

        for ((index, case) in cases.withIndex()) {
            val item = InstructionParser.parse(case.source).single()
            assertEquals("${case.source} remains unstated", Quantity.Default, item.quantity)
            assertTrue("source identity must survive in query: ${item.query}",
                ProductLexicon.normalizeNumericProductIdentities(item.query).contains(case.key))
            assertEquals(item.query, com.example.beta.swiggyRecommendationQuery(item))
            assertTrue("raw catalogue title must retain ${case.key}",
                swiggyMatchesProductIdentity(item, case.title))
            assertFalse("dropped catalogue title cannot prove ${case.key}",
                swiggyMatchesProductIdentity(item, case.droppedTitle))

            val exact = RecommendationCandidate("identity-title-$index", case.title)
            assertTrue("matching raw title should remain eligible", isSwiggyCandidateAllowed(item, exact))
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact)))
            assertTrue("raw catalogue title should prove ${case.key}",
                swiggyIntentQuantityCandidateChecked(checked, exact))
            assertTrue("${case.key} title should survive final resolution",
                resolveSwiggyCartQuantity(checked, exact) !is SwiggyCartQuantityResolution.Held)

            val dropped = RecommendationCandidate("identity-dropped-$index", case.droppedTitle)
            val droppedChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(dropped)))
            assertFalse("dropped title cannot satisfy ${case.key}",
                swiggyIntentQuantityCandidateChecked(droppedChecked, dropped))
            assertTrue(resolveSwiggyCartQuantity(droppedChecked, dropped) is SwiggyCartQuantityResolution.Held)

            val queryDropped = item.copy(query = case.droppedQuery, strictMatchPhrase = case.droppedQuery)
            val queryDroppedCandidate = RecommendationCandidate("query-dropped-$index", case.droppedTitle)
            assertTrue("editable query cannot discard source identity ${case.key}",
                resolveSwiggyCartQuantity(queryDropped, queryDroppedCandidate) is SwiggyCartQuantityResolution.Held)

            val subtitleOnly = RecommendationCandidate("identity-subtitle-$index", case.droppedTitle,
                subtitle = case.title)
            assertFalse("subtitle-only identity cannot prove ${case.key}",
                isSwiggyCandidateAllowed(item, subtitleOnly))
            val subtitleChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(subtitleOnly)))
            assertFalse("subtitle cannot prove ${case.key}",
                swiggyIntentQuantityCandidateChecked(subtitleChecked, subtitleOnly))
            assertTrue(resolveSwiggyCartQuantity(subtitleChecked, subtitleOnly) is SwiggyCartQuantityResolution.Held)

            val countedInstruction = "2 ${case.source}"
            val counted = InstructionParser.parse(countedInstruction).single()
            assertEquals("only leading two is the purchase count: ${case.source}",
                Quantity.Count(2), counted.quantity)
            assertTrue("counted source still retains identity ${case.key}",
                ProductLexicon.normalizeNumericProductIdentities(counted.query).contains(case.key))
        }

        val sevenUp = InstructionParser.parse("7up drink").single()
        val negated = RecommendationCandidate("negated-title", "Not 7up Drink 500 ml")
        val conflicting = RecommendationCandidate("conflicting-title", "7up Drink 500 ml", subtitle = "5 Star Chocolate")
        val negatedChecked = assessSwiggyIntentQuantity(sevenUp, Recommendations(listOf(negated)))
        assertFalse("negated identity is not raw positive evidence",
            swiggyIntentQuantityCandidateChecked(negatedChecked, negated))
        assertTrue(resolveSwiggyCartQuantity(negatedChecked, negated) is SwiggyCartQuantityResolution.Held)
        assertFalse("conflicting subtitle cannot strengthen a raw title",
            isSwiggyCandidateAllowed(sevenUp, conflicting))
    }

    @Test
    fun repeatedEqualLeadingCountBindsBeforeTheSameNumericIdentityAtom() {
        data class RepeatedCase(val instruction: String, val amount: String, val count: Int, val title: String)
        val cases = listOf(
            RepeatedCase("2 maggi 2-minute noodles", "2", 2, "Maggi 2-Minute Noodles"),
            RepeatedCase("two maggi two-minute noodles", "two", 2, "Maggi 2-Minute Noodles"),
            RepeatedCase("5 5 star chocolate", "5", 5, "Cadbury 5 Star Chocolate"),
            RepeatedCase("7 7 up drink", "7", 7, "7 Up Drink"),
        )
        for ((index, case) in cases.withIndex()) {
            val item = InstructionParser.parse(case.instruction).single()
            assertEquals(case.instruction, Quantity.Count(case.count), item.quantity)
            val local = requireNotNull(item.intentQuantityProvenance?.local)
            assertEquals("outer amount must own first occurrence: ${case.instruction}",
                case.instruction.indexOf(case.amount), local.startUtf16)
            assertEquals(case.instruction.indexOf(case.amount) + case.amount.length, local.endUtf16)
            assertNull(item.copy(rawText = requireNotNull(item.intentQuantityProvenance).primaryText)
                .untypedSourceNumericIssue())

            val candidate = RecommendationCandidate("repeated-count-$index", case.title)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("retained identity and outer count should reach catalogue: ${case.instruction}",
                swiggyIntentQuantityCandidateChecked(checked, candidate))
        }
    }

    @Test
    fun legacyNumericAtomsMustHaveOneSourceSupportedOwner() {
        val cases = listOf(
            Triple(legacy("1 lemon", "lemon", "2", "count"), false, "wrong count"),
            Triple(legacy("3 apples", "apples"), false, "missing explicit quantity"),
            Triple(legacy("2 apples 3 bananas", "apples", "2", "count"), false, "unowned extra atom"),
            Triple(legacy("2 ply tissue", "tissue", "2", "count"), false, "descriptor cannot become count"),
            Triple(legacy("3 apples", "apples", "3", "count"), true, "explicit count"),
            Triple(legacy("half kg atta", "atta", "500", "g"), true, "half kilogram normalization"),
            Triple(legacy("1 litre milk", "milk", "1000", "ml"), true, "litre normalization"),
            Triple(legacy("atta 500g", "atta", "500", "g"), true, "glued physical unit"),
            Triple(legacy("1 bottle juice 500 ml", "juice", "1", "pack", "500", "ml"), true,
                "one retail pack with physical size"),
            Triple(legacy("milk", "milk"), true, "unspecified quantity stays absent"),
            Triple(legacy("2 ply tissue", "2 ply tissue"), true,
                "numeric product descriptor is retained"),
            Triple(legacy("a lemon", "lemon", "1", "count"), true, "a/an determiner"),
        )

        for ((item, safe, label) in cases) {
            val issue = item.legacyNumericOwnershipIssue()
            if (safe) assertNull("$label: $issue", issue) else assertNotNull(label, issue)
        }
    }

    @Test
    fun existingParserKeepsFractionalPhysicalUnitsIntegralAfterNormalization() {
        val items = InstructionParser.parse("aadha kilo doodh, dedh kg atta")
        assertEquals(listOf(Quantity.Weight(500), Quantity.Weight(1500)), items.map { it.quantity })
        for (item in items) {
            val proof = requireNotNull(item.intentQuantityProvenance)
            assertTrue(item.requiresIntentQuantityProof)
            assertEquals(item.rawText, proof.rawText)
            assertNull(item.copy(rawText = proof.primaryText).untypedSourceNumericIssue())
            assertTrue(resolveSwiggyCartQuantity(item.copy(quantity = Quantity.Weight(2000)),
                RecommendationCandidate("tamper-check", "Wheat Flour 1 kg")) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun finalResolutionHoldsWhenModelCountDisagreesWithSourceCount() {
        val item = ParsedItem(rawText = "1 lemon", query = "lemon", quantity = Quantity.Count(2),
            strictMatchPhrase = "lemon")
        val candidate = RecommendationCandidate("synthetic-lemon", "Lemon 500 g")

        assertNotNull(item.untypedSourceNumericIssue())
        assertTrue(resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun savedPreferencesPreserveDeterministicCountSourceAndRejectAmountOrRoleTampering() {
        val parsed = InstructionParser.parse("order 2 lemons").single()
        val originalProof = requireNotNull(parsed.intentQuantityProvenance)
        assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, originalProof.origin)
        assertEquals("lemons", originalProof.numericSourceQuery)
        assertEquals(Quantity.Count(2), parsed.quantity)

        val enriched = InstructionParser.applyPreferences(listOf(parsed), lookup = { query ->
            if (query == "lemons") Preference(token = "lemons", preferredPhrase = "fresh lemons", source = PreferenceSource.USER_OVERRIDE, confidence = 1.0f) else null
        }).single()
        val proof = requireNotNull(enriched.intentQuantityProvenance)
        assertEquals("fresh lemons", enriched.query)
        assertEquals("fresh lemons", proof.query)
        assertEquals("lemons", proof.numericSourceQuery)
        assertEquals("2 lemons", proof.primaryText)
        assertEquals(Quantity.Count(2), enriched.quantity)

        val candidate = RecommendationCandidate("synthetic-fresh-lemons", "Fresh Lemons 500 g")
        val checked = assessSwiggyIntentQuantity(enriched, Recommendations(listOf(candidate)))
        assertNull(checked.intentQuantityCatalogueCheck?.issue)
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) !is SwiggyCartQuantityResolution.Held)

        val changedAmount = checked.copy(quantity = Quantity.Count(3))
        assertTrue(resolveSwiggyCartQuantity(changedAmount, candidate) is SwiggyCartQuantityResolution.Held)

        val inventedRole = checked.copy(
            intentQuantityProvenance = proof.copy(sourceCountRole = IntentSourceCountRole.NAMED_RETAIL),
            intentQuantityCatalogueCheck = null,
        )
        assertTrue(resolveSwiggyCartQuantity(inventedRole, candidate) is SwiggyCartQuantityResolution.Held)

        val extraNumericAtom = checked.copy(
            intentQuantityProvenance = proof.copy(primaryText = "2 lemons and 3 bananas"),
            intentQuantityCatalogueCheck = null,
        )
        assertTrue(resolveSwiggyCartQuantity(extraNumericAtom, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun savedPreferencesPreserveDeterministicPhysicalAmountAndRejectAmountTampering() {
        val parsed = InstructionParser.parse("500 g atta").single()
        val originalProof = requireNotNull(parsed.intentQuantityProvenance)
        assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, originalProof.origin)
        assertEquals("atta", originalProof.numericSourceQuery)
        assertEquals(Quantity.Weight(500), parsed.quantity)

        val enriched = InstructionParser.applyPreferences(listOf(parsed), lookup = { query ->
            if (query == "atta") Preference(token = "atta", preferredPhrase = "whole wheat atta", source = PreferenceSource.USER_OVERRIDE, confidence = 1.0f) else null
        }).single()
        val proof = requireNotNull(enriched.intentQuantityProvenance)
        assertEquals("whole wheat atta", enriched.query)
        assertEquals("whole wheat atta", proof.query)
        assertEquals("atta", proof.numericSourceQuery)
        assertEquals("500 g atta", proof.primaryText)
        assertEquals(Quantity.Weight(500), enriched.quantity)

        val candidate = RecommendationCandidate("synthetic-whole-wheat", "Whole Wheat Atta 500 g")
        val checked = assessSwiggyIntentQuantity(enriched, Recommendations(listOf(candidate)))
        assertNull(checked.intentQuantityCatalogueCheck?.issue)
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) !is SwiggyCartQuantityResolution.Held)

        val changedAmount = checked.copy(quantity = Quantity.Weight(750))
        assertTrue(resolveSwiggyCartQuantity(changedAmount, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun v1PreparedPacketCountsRemainProposalsAndMutationsAreHeld() {
        data class Case(val source: String, val name: String, val title: String, val size: String)
        val cases = listOf(
            Case("two packets milk", "milk", "Milk Packet", "500 ml"),
            Case("milk two packets", "milk", "Milk Packet", "500 ml"),
            Case("2 milk tetra packs", "milk tetra pack", "Milk Tetra Pack", "500 ml"),
        )

        for ((index, case) in cases.withIndex()) {
            val item = prepareV1(case.source, case.name, quantity = "2", quantityUnit = "pack")
                .items.single()
            val proof = requireNotNull(item.intentQuantityProvenance)
            assertTrue(item.requiresIntentQuantityProof)
            assertNull(item.quantitySignal)
            assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, proof.origin)
            assertEquals(case.source, proof.primaryText)
            assertEquals(case.source, proof.local?.source)
            assertEquals(BigDecimal("2"), proof.local?.value)
            assertEquals(item.query, proof.numericSourceQuery)
            assertEquals(Quantity.Count(2), item.quantity)

            val single = RecommendationCandidate("v1-single-$index", "${case.title} ${case.size}")
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(single)))
            val result = resolveSwiggyCartQuantity(assessed, single)
            assertTrue("${case.source} should yield a two-SKU proposal, not exact authorization",
                result is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(2, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
            assertUnqualifiedCountNeedsReview(assessed, single, 2)

            val mutations = listOf(
                assessed.copy(quantity = Quantity.Count(3)),
                assessed.copy(rawText = "${case.source} 3"),
                assessed.copy(intentQuantityProvenance = proof.copy(origin = IntentQuantityOrigin.LOCAL)),
                assessed.copy(requiresIntentQuantityProof = false),
                assessed.copy(intentQuantityProvenance = null),
                assessed.copy(intentQuantityProvenance = proof.copy(numericSourceQuery = null)),
            )
            mutations.forEachIndexed { mutationIndex, changed ->
                val reassessed = assessSwiggyIntentQuantity(changed, Recommendations(listOf(single)))
                assertTrue("${case.source} mutation $mutationIndex must be held",
                    resolveSwiggyCartQuantity(reassessed, single) is SwiggyCartQuantityResolution.Held)
            }

            val badOffers = listOf(
                RecommendationCandidate("v1-bundle-$index", "${case.title} 6 x 500 ml"),
                RecommendationCandidate("v1-malformed-$index", "${case.title} ${case.size} / 1 kg"),
            )
            for (bad in badOffers) {
                val badAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(bad)))
                assertTrue("${case.source} cannot authorize a bundle or malformed offer",
                    resolveSwiggyCartQuantity(badAssessed, bad) is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun v1NumericSplitTotalStopsBothClaimsBeforeDiscovery() {
        val packs = legacy("Two 500 ml milk pouches", "milk pouch", "2", "count", "500", "ml")
        val rice = legacy("200 g rice", "rice", "200", "g")
        for ((first, total) in listOf(
            packs to legacy("1 litre milk in total", "milk", "1", "l"), // equal arithmetic, still not another SKU
            rice to legacy("1 kg rice in total", "rice", "1", "kg"), // cannot silently reconcile conflicting amounts
            packs to legacy("kul ek litre doodh", "milk", "1", "l"),
            packs to legacy("1 litre of milk in total", "milk", null, "unspecified", "1", "l"),
            legacy("milk", "milk") to legacy("1 litre of milk in total", "milk", "1", "l"),
            legacy("2 bags of sugar", "sugar", "2", "count") to legacy("1 kg sugar in total", "sugar", "1000", "g"),
        )) {
            val source = first.sourceText + ", " + total.sourceText
            val draft = GroceryIntentDraft("grocery-intent-v1", true, source, listOf(first, total), false)
            assertEquals(setOf(first.sourceText, total.sourceText), splitPhysicalTotalConflicts(source, draft.items).toSet())
            val error = org.junit.Assert.assertThrows(IntentNeedsReviewException::class.java) { draft.prepareForReview() }
            assertTrue(error.affectedSourceText.containsAll(listOf(first.sourceText, total.sourceText)))
        }
    }

    @Test
    fun v1NumericConservationKeepsIndependentRepeatsStandaloneTotalsAndProductNames() {
        val a = legacy("Two 500 ml milk pouches", "milk", "2", "pack", "500", "ml")
        val b = legacy("two 500 ml milk pouches", "milk", "2", "pack", "500", "ml")
        val single = legacy("1 litre milk in total", "milk", "1", "l")
        val juice = legacy("1 litre juice in total", "juice", "1", "l")
        for (rows in listOf(listOf(a, b), listOf(single), listOf(a, juice))) {
            val source = rows.joinToString(" and ") { it.sourceText }
            assertTrue(splitPhysicalTotalConflicts(source, rows).isEmpty())
            assertEquals(rows.size, GroceryIntentDraft("grocery-intent-v1", true, source, rows, false)
                .prepareForReview().items.size)
        }
        val brand = legacy("100 g Total toothpaste", "Total toothpaste", "100", "g")
        val plain = legacy("50 g toothpaste", "toothpaste", "50", "g")
        assertTrue(splitPhysicalTotalConflicts(plain.sourceText + ", " + brand.sourceText, listOf(plain, brand)).isEmpty())
        val trailingBrand = legacy("100 g toothpaste Total", "toothpaste Total", "100", "g")
        assertTrue(splitPhysicalTotalConflicts(plain.sourceText + ", " + trailingBrand.sourceText,
            listOf(plain, trailingBrand)).isEmpty())
    }

    @Test
    fun v1NumericSourceProofDoesNotReplaceExistingReceiptFamiliesOrAmbiguousInputs() {
        val named = prepareV1("two jars of honey", "honey", quantity = "2", quantityUnit = "pack")
            .items.single()
        val namedProof = requireNotNull(named.intentQuantityProvenance)
        assertEquals(IntentQuantityOrigin.LOCAL, namedProof.origin)
        assertEquals(IntentSourceCountRole.NAMED_RETAIL, namedProof.sourceCountRole)
        assertNull(namedProof.numericSourceQuery)

        val pieces = prepareV1("2 pieces frozen snacks", "frozen snacks", quantity = "2", quantityUnit = "piece")
            .items.single()
        val pieceProof = requireNotNull(pieces.intentQuantityProvenance)
        assertEquals(IntentQuantityOrigin.LOCAL, pieceProof.origin)
        assertEquals(IntentSourceCountRole.REQUESTED_PIECES, pieceProof.sourceCountRole)
        assertNull(pieceProof.numericSourceQuery)

        val generic = prepareV1("2 dabba rice 500 g", "rice", quantity = "2", quantityUnit = "pack",
            packValue = "500", packUnit = "g").items.single()
        val genericProof = requireNotNull(generic.intentQuantityProvenance)
        assertEquals(IntentQuantityOrigin.LOCAL, genericProof.origin)
        assertEquals(IntentSourceCountRole.GENERIC_WRAPPERS, genericProof.sourceCountRole)
        assertNotNull(genericProof.genericContainerReceipt)
        assertNull(genericProof.numericSourceQuery)

        val contents = prepareV1("two packets tea bags, 25 bags", "tea bags", quantity = "2",
            quantityUnit = "count", packValue = "25", packUnit = "piece").items.single()
        assertNotNull(contents.containerContentsProof)
        assertFalse(contents.intentQuantityProvenance?.origin == IntentQuantityOrigin.SOURCE_NUMERIC)

        for ((source, name, count) in listOf(
            Triple("6 pack juice", "juice", "6"),
            Triple("2 pack milk", "milk", "2"),
        )) {
            val preparation = try {
                prepareV1(source, name, quantity = count, quantityUnit = "pack")
            } catch (_: IntentNeedsReviewException) {
                null
            }
            assertTrue("descriptor input must not acquire numeric source provenance: $source",
                preparation == null || preparation.items.none {
                    it.intentQuantityProvenance?.origin == IntentQuantityOrigin.SOURCE_NUMERIC
                })
            if (source == "6 pack juice" && preparation != null) {
                val descriptorOffer = RecommendationCandidate("six-pack-descriptor", "Juice 6 Pack")
                val checked = assessSwiggyIntentQuantity(preparation.items.single(),
                    Recommendations(listOf(descriptorOffer)))
                assertTrue("A product descriptor cannot authorize six Juice SKUs",
                    resolveSwiggyCartQuantity(checked, descriptorOffer) is SwiggyCartQuantityResolution.Held)
            }
        }

        for (instruction in listOf("rice 2 sugar", "rice 2 out of rice 2 sugar")) {
            val cropped = try {
                prepareV1("rice 2", "rice", quantity = "2", quantityUnit = "pack",
                    instruction = instruction)
            } catch (_: IntentNeedsReviewException) {
                null
            }
            assertTrue("a cropped quote must not acquire numeric source provenance",
                cropped == null || cropped.items.none {
                    it.intentQuantityProvenance?.origin == IntentQuantityOrigin.SOURCE_NUMERIC
                })
        }
    }

    @Test
    fun v1NumericWitnessDoesNotReplaceModelIdentityOrSourceFields() {
        val source = "2 dhaniya"
        val modelName = "coriander"
        val item = prepareV1(source, modelName, quantity = "2", quantityUnit = "count").items.single()
        val witness = InstructionParser.parse(source).single()
        assertFalse("Fixture must distinguish parser identity from V1 identity", witness.query == modelName)

        assertEquals(modelName, item.query)
        assertEquals(modelName, item.strictMatchPhrase)
        assertEquals(source, item.rawText)
        assertEquals(Quantity.Count(2), item.quantity)
        val proof = requireNotNull(item.intentQuantityProvenance)
        assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, proof.origin)
        assertEquals(modelName, proof.numericSourceQuery)
        assertEquals(source, proof.primaryText)
        assertEquals(source, proof.local?.source)
        assertEquals(BigDecimal("2"), proof.local?.value)
        assertFalse("Numeric witness must not replace the grounded translated identity",
            proof.numericSourceQuery == witness.query)
    }

    @Test
    fun attachingV1NumericProofPreservesProposalsButCatalogueIdentityConflictHoldsTheRow() {
        val source = "2 milk"
        val proposed = legacy(source, "milk", quantity = "2", quantityUnit = "count").toParsedItem()
        val prepared = prepareV1(source, "milk", quantity = "2", quantityUnit = "count").items.single()
        val ordinaryOffers = listOf(
            RecommendationCandidate("milk-500ml", "Milk 500 ml"),
            RecommendationCandidate("milk-2l", "Milk 2 L"),
        )

        for (offer in ordinaryOffers) {
            val before = assessSwiggyIntentQuantity(proposed, Recommendations(ordinaryOffers))
            assertTrue("Unflagged count already permits a review-bound proposal for ${offer.label}",
                resolveSwiggyCartQuantity(before, offer) is SwiggyCartQuantityResolution.RetailProposal)

            val after = assessSwiggyIntentQuantity(prepared, Recommendations(ordinaryOffers))
            val result = resolveSwiggyCartQuantity(after, offer)
            assertTrue("${offer.label} should remain a two-SKU proposal after numeric proof attachment",
                result is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(2, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
        }

        val conflictingSet = ordinaryOffers + RecommendationCandidate("identity-conflict", "2 Milk 500 ml")
        val conflicted = assessSwiggyIntentQuantity(prepared, Recommendations(conflictingSet))
        for (offer in conflictingSet) {
            assertTrue("A conflicting identity in the full catalogue set must hold ${offer.label}",
                resolveSwiggyCartQuantity(conflicted, offer) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun descriptorPackCountsNeedTheProductionWitnessBeforeTheyCanProposeCartUnits() {
        assertNotNull(
            "Shared numeric-brand protection already rejects this proposed count",
            legacy("5 star chocolate", "chocolate", "5", "count").legacyNumericOwnershipIssue(),
        )
        data class Case(val source: String, val name: String, val count: Int, val title: String)
        val cases = listOf(
            Case("2 pack milk", "milk", 2, "Milk 500 ml"),
            Case("6 pack juice", "juice", 6, "Juice 500 ml"),
        )
        val helper = Class.forName("com.example.beta.GroceryIntentNumericOwnershipKt")
            .getDeclaredMethod("numericSourceProvenance", ParsedItem::class.java, String::class.java)
            .apply { isAccessible = true }

        for (case in cases) {
            val sourceItem = legacy(case.source, case.name, case.count.toString(), "pack")
            assertNull("Legacy numeric gate should accept this descriptor", sourceItem.legacyNumericOwnershipIssue())
            val prepared = GroceryIntentDraft(
                "grocery-intent-v1", true, case.source, listOf(sourceItem), false,
            ).prepareForReview().items.single()
            assertFalse("V1 must not attach SOURCE_NUMERIC here",
                prepared.intentQuantityProvenance?.origin == IntentQuantityOrigin.SOURCE_NUMERIC)

            val sku = RecommendationCandidate("ordinary-${case.name}", case.title)
            val actual = resolveSwiggyCartQuantity(
                assessSwiggyIntentQuantity(prepared, Recommendations(listOf(sku))), sku,
            )
            assertTrue("Production path must hold this descriptor request", actual is SwiggyCartQuantityResolution.Held)

            // Characterization only: reflect the private helper to simulate bypassing
            // the production witness gate; this is not an approved V1 behavior.
            val original = sourceItem.toParsedItem()
            val counterfactualProof = helper.invoke(null, original, case.source) as? IntentQuantityProvenance
            assertNotNull("Counterfactual helper should expose the descriptor promotion", counterfactualProof)
            val counterfactual = original.copy(
                requiresIntentQuantityProof = true,
                intentQuantityProvenance = requireNotNull(counterfactualProof),
            )
            val promoted = resolveSwiggyCartQuantity(
                assessSwiggyIntentQuantity(counterfactual, Recommendations(listOf(sku))), sku,
            )
            assertTrue("Counterfactual should expose the unapproved proposal promotion",
                promoted is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(case.count, (promoted as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
        }

        val overLimitSource = legacy("24 milk", "milk", "24", "count")
        val overLimitBase = overLimitSource.toParsedItem()
        val overLimitProof = helper.invoke(null, overLimitBase, overLimitSource.sourceText) as? IntentQuantityProvenance
        val overLimit = overLimitBase.copy(
            requiresIntentQuantityProof = true,
            intentQuantityProvenance = overLimitProof,
        )
        val overLimitSku = RecommendationCandidate("milk-500ml", "Milk 500 ml")
        val overLimitResult = resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(overLimit, Recommendations(listOf(overLimitSku))), overLimitSku,
        )
        assertFalse("Over-limit request must never become a 24-unit proposal",
            overLimitResult is SwiggyCartQuantityResolution.RetailProposal && overLimitResult.cartUnits == 24)
    }

    private fun prepareV1(
        source: String,
        name: String,
        quantity: String?,
        quantityUnit: String,
        packValue: String? = null,
        packUnit: String = "unspecified",
        instruction: String = source,
    ): GroceryIntentPreparation = GroceryIntentDraft(
        contractVersion = "grocery-intent-v1",
        requiresReview = true,
        instruction = instruction,
        items = listOf(legacy(source, name, quantity, quantityUnit, packValue, packUnit)),
        modelNeedsClarification = false,
    ).prepareForReview()
}
