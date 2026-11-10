package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentSourceFormRetentionTest {
    @Test
    fun locallyRetainedGenitivesShareGrammarWithoutChangingPurchaseCounts() {
        data class Case(val source: String, val name: String, val count: Int, val label: String, val size: String)
        val cases = listOf(
            Case("oil ke 2 packets", "oil", 2, "Oil", "500 ml"),
            Case("Doodh ka ek packet", "milk", 1, "Milk", "500 ml"),
            Case("Dahi ke 2 cups", "curd cups", 2, "Curd Cup", "200 g"),
            Case("coriander ka ek bunch", "coriander bunch", 1, "Coriander Bunch", "100 g"),
        )
        val failures = mutableListOf<String>()
        cases.forEachIndexed { index, case ->
            val item = prepare(case.source, case.name, case.count)
            val candidate = typedSku(case.label, "genitive-$index", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            if (item.unprovenIdentityScope || resolution !is SwiggyCartQuantityResolution.RetailProposal) {
                failures += "case=$index scopeHeld=${item.unprovenIdentityScope} resolution=$resolution"
            } else {
                assertEquals(case.source, item.rawText)
                assertEquals(case.source, item.intentQuantityProvenance?.primaryText)
                assertProposalCanBeAcknowledgedOnce(checked, candidate, case.count)
            }
        }
        assertTrue("Genitive review outcomes: $failures", failures.isEmpty())
    }

    @Test
    fun genitiveGrammarCannotEraseIdentityPolaritySizeOrRepeatedParticles() {
        data class Case(val source: String, val name: String, val excluded: List<String> = emptyList())
        val cases = listOf(
            Case("mummy ki soap 2 packets", "soap"),
            Case("ghee ka 2 packets oil", "oil"),
            Case("sugar ka 2 packets", "packets", listOf("sugar")),
            Case("oil ke ke 2 packets", "oil"),
            Case("bread 2 jars honey", "honey"),
            Case("2 small honey jars", "honey jars"),
            Case("oil ke 2 packets ka 3", "oil"),
        )
        cases.forEachIndexed { index, case ->
            val item = try { prepare(case.source, case.name, 2, excluded = case.excluded) }
                catch (_: IntentNeedsReviewException) { return@forEachIndexed }
            assertTrue("case=$index must preserve the identity hold", item.unprovenIdentityScope)
            val candidate = typedSku(case.name + " " + groceryIntentSpecificSourceFormTokens(case.source).joinToString(" "),
                "genitive-negative-$index", "500 g")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("case=$index must remain held", resolveSwiggyCartQuantity(checked, candidate)
                is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun genitiveRequiresANonemptyRetainedNounRatherThanAStopWord() {
        for (noun in listOf("the", "of", "and")) {
            assertTrue(groceryIntentRetainedGenitiveRanges("$noun ke 2 packets oil", intentEvidenceTokens("oil")).isEmpty())
        }
    }

    @Test
    fun noOfNamedFormsStayInThePreparedIdentityAndRequireMatchingCatalogueForms() {
        data class Case(val source: String, val identity: String, val form: String, val size: String)
        val cases = listOf(
            Case("1 bunch spring onions", "spring onions", "bunch", "100 g"),
            Case("1 jar honey", "honey", "jar", "500 g"),
            Case("1 carton juice", "juice", "carton", "1 litre"),
            Case("1 loaf bread", "bread", "loaf", "400 g"),
        )
        for ((source, identity, form, size) in cases) {
            val item = prepare(source, identity, quantityUnit = "pack")
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue("$source must retain source-numeric proof", item.requiresIntentQuantityProof)
            assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, item.intentQuantityProvenance?.origin)
            assertNull("$source is an identity constraint, not a named-retail count receipt", item.validNamedRetailReceipt())
            assertTrue("$source should retain source form in query ${item.query}",
                item.query.contains(form, ignoreCase = true))
            assertEquals(item.query, item.strictMatchPhrase)
            val matching = typedSku("${identity.replaceFirstChar { it.uppercase() }} ${form.replace('_', ' ')}",
                "matched-${form}", size)
            val matchingChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(matching)))
            val titleOnly = sku("${identity.replaceFirstChar { it.uppercase() }} ${form.replace('_', ' ')}", "title-only-${form}")
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly)))
            assertTrue("$source title-only offer lacks a physical amount",
                resolveSwiggyCartQuantity(titleOnlyChecked, titleOnly) is SwiggyCartQuantityResolution.Held)

            val formless = typedSku("${identity.replaceFirstChar { it.uppercase()}} $size",
                "formless-${form}", size)
            val formChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(formless)))
            assertFalse("$source must not allow form-less SKU ${formless.label}",
                isSwiggyCandidateAllowed(formChecked, formless))
            assertTrue(resolveSwiggyCartQuantity(formChecked, formless) is SwiggyCartQuantityResolution.Held)
            // Removing every editable query hint cannot erase the source obligation.
            val downgraded = item.copy(query = identity, strictMatchPhrase = identity, retailPackCount = false,
                requiresIntentQuantityProof = false, intentQuantityProvenance = null, intentQuantityCatalogueCheck = null)
            assertTrue(resolveSwiggyCartQuantity(downgraded, formless) is SwiggyCartQuantityResolution.Held)
            assertTrue("$source should allow matching source form ${matching.label}",
                isSwiggyCandidateAllowed(matchingChecked, matching))
            val resolution = resolveSwiggyCartQuantity(matchingChecked, matching)
            assertTrue("$source with typed physical offer must be reviewable, not exact: $resolution",
                resolution is SwiggyCartQuantityResolution.RetailProposal)
            assertFalse("$source has no exact count under the current receipt contract",
                resolution is SwiggyCartQuantityResolution.Exact)
            assertProposalCanBeAcknowledgedOnce(matchingChecked, matching, 1)
        }
    }

    @Test
    fun explicitOfFormRemainsSupported() {
        val source = "1 bunch of spring onions"
        val item = prepare(source, "spring onions", quantityUnit = "pack")
        assertTrue(item.query.contains("bunch", ignoreCase = true))
        assertEquals(item.query, item.strictMatchPhrase)
        assertEquals(IntentSourceCountRole.NAMED_RETAIL,
            item.intentQuantityProvenance?.sourceCountRole)

        val unknown = sku("Spring Onions Bunch", "spring-onions-bunch-unknown")
        assertTrue(resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(item, Recommendations(listOf(unknown))), unknown,
        ) is SwiggyCartQuantityResolution.Held)
        val candidate = typedSku("Spring Onions Bunch", "spring-onions-bunch", "1 bunch")
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(assessed, candidate))
    }

    @Test
    fun modelQuantityCannotChangeTheLiteralSourceCount() {
        for ((source, name) in listOf("1 bunch spring onions" to "spring onions", "1 jar honey" to "honey")) {
            assertTrue("A changed source count must be rejected",
                runCatching { prepare(source, name, quantity = 2) }.isFailure)
        }
    }

    @Test
    fun ordinaryItemCannotAcquireAnInventedModelCount() {
        assertTrue(runCatching { prepare("1 apple", "apple", quantity = 2, quantityUnit = "count") }.isFailure)
    }

    @Test
    fun wrongIdentityAndInventedDietPropertyStillFailPreparation() {
        val source = "1 bunch spring onions"
        assertTrue(runCatching { prepare(source, "tomatoes", quantityUnit = "pack") }.isFailure)
        assertTrue(runCatching {
            prepare(source, "spring onions", quantityUnit = "pack", required = listOf("organic"))
        }.isFailure)
    }

    @Test
    fun compoundProductNamesCannotAcquireNamedRetailReceiptFromOptionalOfParsing() {
        for ((source, identity) in listOf(
            "one bottle brush" to "bottle brush",
            "one can opener" to "can opener",
        )) {
            val item = prepare(source, identity, quantityUnit = "count")
            assertNull("$source is a compound identity, not a named retail count",
                item.validNamedRetailReceipt())
            assertFalse(item.intentQuantityProvenance?.sourceCountRole == IntentSourceCountRole.NAMED_RETAIL)
            assertFalse(item.retailPackCount)
        }
    }

    @Test
    fun aGroundedPrefixBrandCannotBeReplacedByTheLiteralSourceConstraint() {
        val item = prepare("1 jar honey", "Acme honey", instruction = "Acme 1 jar honey")
        assertTrue(item.query.contains("Acme"))
        assertTrue(item.query.contains("jar"))
        val unbranded = sku("Honey Jar", "missing-brand")
        assertFalse(isSwiggyCandidateAllowed(item, unbranded))
        assertTrue(resolveSwiggyCartQuantity(item, unbranded) is SwiggyCartQuantityResolution.Held)
        val branded = sku("Acme Honey Jar", "with-brand")
        assertTrue("A cropped quote does not own its brand", item.unprovenIdentityScope)
        assertTrue(resolveSwiggyCartQuantity(item, branded) is SwiggyCartQuantityResolution.Held)
        val typed = typedSku("Acme Honey Jar 500 g", "with-brand-typed", "500 g")
        val cropped = assessSwiggyIntentQuantity(item, Recommendations(listOf(typed)))
        assertTrue(resolveSwiggyCartQuantity(cropped, typed) is SwiggyCartQuantityResolution.Held)
        assertNull(SwiggyCartReviewRequest.build(listOf(cropped), mapOf(0 to typed), emptySet()))

        val complete = prepare("Acme 1 jar honey", "Acme honey")
        val checked = assessSwiggyIntentQuantity(complete, Recommendations(listOf(typed)))
        assertFalse(complete.unprovenIdentityScope)
        assertProposalCanBeAcknowledgedOnce(checked, typed, 1)
    }

    @Test
    fun v1PreparationRetainsNumericProofAcrossPrefixBrandQuoteShapes() {
        data class Case(
            val instruction: String,
            val quote: String,
            val identity: String,
            val count: Int,
            val form: String,
            val size: String,
            val label: String,
            val expectProposal: Boolean = true,
        )
        val cases = listOf(
            Case("Acme 1 jar honey", "Acme 1 jar honey", "Acme honey", 1, "jar", "500 g", "Acme jar full"),
            Case("Acme 1 jar honey", "1 jar honey", "Acme honey", 1, "jar", "500 g", "Acme jar cropped", false),
            Case("Acme 2 jars honey", "Acme 2 jars honey", "Acme honey", 2, "jars", "500 g", "Acme jars full"),
            Case("Acme 2 jars honey", "2 jars honey", "Acme honey", 2, "jars", "500 g", "Acme jars cropped", false),
            Case("BrandB 2 bottles water", "BrandB 2 bottles water", "BrandB water", 2, "bottles", "1 litre", "BrandB bottles full"),
            Case("BrandB 2 bottles water", "2 bottles water", "BrandB water", 2, "bottles", "1 litre", "BrandB bottles cropped", false),
            Case("BrandC 3 cartons juice", "BrandC 3 cartons juice", "BrandC juice", 3, "cartons", "1 litre", "BrandC cartons full"),
            Case("BrandC 3 cartons juice", "3 cartons juice", "BrandC juice", 3, "cartons", "1 litre", "BrandC cartons cropped", false),
            Case("1 jar Acme honey", "1 jar Acme honey", "Acme honey", 1, "jar", "500 g", "Acme jar suffix"),
            Case("2 bottles BrandB water", "2 bottles BrandB water", "BrandB water", 2, "bottles", "1 litre", "BrandB bottles suffix"),
            Case("3 cartons BrandC juice", "3 cartons BrandC juice", "BrandC juice", 3, "cartons", "1 litre", "BrandC cartons suffix"),
            Case("Acme 2 packets milk", "Acme 2 packets milk", "Acme milk", 2, "packets", "500 ml", "Acme packets"),
            Case("Acme 2 packs juice", "Acme 2 packs juice", "Acme juice", 2, "packs", "1 litre", "Acme packs juice"),
            Case("Acme 2 tins almonds", "Acme 2 tins almonds", "Acme almonds", 2, "tins", "200 g", "Acme tins"),
            Case("Fresh Meadow 2 jars honey", "Fresh Meadow 2 jars honey", "Fresh Meadow honey", 2, "jars", "500 g", "Fresh Meadow jars"),
            Case("Haldiram's 2 packets bhujia", "Haldiram's 2 packets bhujia", "Haldiram's bhujia", 2, "packets", "200 g", "Haldiram packets"),
            Case("Acme 2 bars chocolate", "Acme 2 bars chocolate", "Acme chocolate", 2, "bars", "50 g", "Acme bars"),
            Case("Acme honey 2 jars", "Acme honey 2 jars", "Acme honey", 2, "jars", "500 g", "Acme honey suffix"),
            Case("Acme six pack yogurt", "Acme six pack yogurt", "Acme yogurt", 6, "pack", "500 g", "Acme yogurt descriptor", false),
            Case("Acme, 2 jars honey", "Acme, 2 jars honey", "Acme honey", 2, "jars", "500 g", "comma prefix", false),
            Case("not Acme 2 jars honey", "not Acme 2 jars honey", "Acme honey", 2, "jars", "500 g", "negated prefix", false),
            Case("Acme jar 2 jars honey", "Acme jar 2 jars honey", "Acme honey", 2, "jars", "500 g", "duplicate form", false),
            Case("Acme 2 jars honey and bread", "Acme 2 jars honey and bread", "Acme honey", 2, "jars", "500 g", "trailing product", false),
            // A fully retained contents phrase may be a reviewed offer, never
            // an exact receipt. The prefix must not change the unbranded route.
            Case("Acme 2 jars with honey", "Acme 2 jars with honey", "Acme honey", 2, "jars", "500 g", "interposed contents phrase"),
            Case("2 jars with honey", "2 jars with honey", "honey", 2, "jars", "500 g", "unbranded contents phrase"),
            Case("milk 2 packets bread", "milk 2 packets bread", "milk bread", 2, "packets", "500 g", "merged identity", false),
            Case("Acme 2 packets of 6 biscuits", "Acme 2 packets of 6 biscuits", "Acme biscuits", 2, "packets", "200 g", "nested biscuit amount", false),
        )
        val failures = mutableListOf<String>()

        cases.forEachIndexed { index, case ->
            for (quantityUnit in listOf("pack", "count")) {
            val fullOwnedQuote = case.quote == case.instruction
            val prepared = runCatching {
                prepare(case.quote, case.identity, quantity = case.count, quantityUnit = quantityUnit,
                    instruction = case.instruction)
            }
            if (prepared.isFailure) {
                if (case.expectProposal) {
                    failures += "case=$index unit=$quantityUnit ${case.label} preparation=${prepared.exceptionOrNull()?.javaClass?.simpleName}"
                }
                continue
            }
            val item = prepared.getOrThrow()
            val candidate = typedSku(
                "${case.identity} ${case.form} ${case.size}",
                "prefix-context-$index-$quantityUnit",
                case.size,
            )
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            val proposal = resolution as? SwiggyCartQuantityResolution.RetailProposal
            val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet())
            val correctOutcome = if (case.expectProposal) {
                item.quantity == Quantity.Count(case.count) && proposal?.cartUnits == case.count &&
                    resolution !is SwiggyCartQuantityResolution.Exact && request != null && fullOwnedQuote
            } else {
                resolution is SwiggyCartQuantityResolution.Held && request == null
            }
            if (!correctOutcome) {
                val proof = item.intentQuantityProvenance
                val parser = com.example.beta.automation.InstructionParser.parse(case.quote)
                failures += "case=$index unit=$quantityUnit ${case.label} origin=${proof?.origin} witness=${proof?.numericSourceQuery} " +
                    "quantity=${item.quantity} resolution=$resolution " +
                    "parser=${parser.map { it.quantity to it.intentQuantityProvenance?.origin }} " +
                    "numericIssue=${item.untypedSourceNumericIssue()} " +
                    "context=${groceryIntentSourceClauseContextProven(case.instruction, case.quote, case.quote.length)} " +
                    "request=${request != null} fullOwnedQuote=$fullOwnedQuote expectedProposal=${case.expectProposal}"
            }
            if (case.expectProposal) {
                val titleOnly = sku("${case.identity} ${case.form}", "prefix-title-only-$index-$quantityUnit")
                val titleChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly)))
                val titleResolution = resolveSwiggyCartQuantity(titleChecked, titleOnly)
                if (titleResolution !is SwiggyCartQuantityResolution.Held ||
                    SwiggyCartReviewRequest.build(listOf(titleChecked), mapOf(0 to titleOnly), emptySet()) != null) {
                    failures += "case=$index unit=$quantityUnit ${case.label} title-only offer escaped without physical size: $titleResolution"
                }
            }
            }
        }

        assertTrue("V1 prefix-context review proposals did not conserve their source counts: $failures", failures.isEmpty())
    }

    @Test
    fun v1PackDescriptorsAndGlobalBrandContextCannotCreatePurchaseCountEvidence() {
        data class DescriptorCase(val source: String, val identity: String, val count: Int, val sku: String)
        val descriptorCases = listOf(
            DescriptorCase("Acme 6 pack juice", "Acme juice", 6, "Acme Juice 500 ml"),
            DescriptorCase("BrandB 2 pack milk", "BrandB milk", 2, "BrandB Milk 500 ml"),
            DescriptorCase("6 pack juice", "juice", 6, "Juice 500 ml"),
            DescriptorCase("2 pack milk", "milk", 2, "Milk 500 ml"),
        )
        val failures = mutableListOf<String>()
        val observations = mutableListOf<String>()

        descriptorCases.forEachIndexed { index, case ->
            val prepared = runCatching {
                prepare(case.source, case.identity, quantity = case.count, quantityUnit = "pack")
            }
            if (prepared.isFailure) {
                observations += "descriptor=$index rejected-during-preparation"
            } else {
                val item = prepared.getOrThrow()
                val candidate = typedSku(case.sku, "descriptor-negative-$index", "500 ml")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                observations += "descriptor=$index resolution=$resolution"
                if (resolution is SwiggyCartQuantityResolution.RetailProposal ||
                    resolution is SwiggyCartQuantityResolution.Exact) {
                    failures += "descriptor=$index created actionable source-count evidence: $resolution"
                }
            }
        }

        val sharedInstruction = "BrandA butter and 2 packets milk"
        fun row(name: String, source: String, quantity: Int?, quantityUnit: String) = GroceryIntentItem(
            name = name,
            quantity = quantity?.toBigDecimal(),
            quantityUnit = quantityUnit,
            packValue = null,
            packUnit = "unspecified",
            required = emptyList(),
            excluded = emptyList(),
            sourceText = source,
            needsClarification = false,
        )
        val rowShapes = listOf(
            (listOf(
                row("BrandA butter", "BrandA butter", null, "unspecified"),
                row("BrandA milk", "2 packets milk", 2, "pack"),
            ) to "cross-row-packet-count"),
            (listOf(row("BrandA milk", "2 packets milk", 2, "pack")) to "single-row-packet-count"),
            (listOf(
                row("BrandA butter", "BrandA butter", null, "unspecified"),
                row("BrandA milk", "2 milk", 2, "count"),
            ) to "cross-row-bare-count"),
            (listOf(row("BrandA milk", "2 milk", 2, "count")) to "single-row-bare-count"),
            (listOf(
                row("BrandA butter", "BrandA butter", null, "unspecified"),
                row("BrandA milk", "milk", null, "unspecified"),
            ) to "cross-row-default"),
            (listOf(row("BrandA milk", "milk", null, "unspecified")) to "single-row-default"),
            (listOf(
                row("BrandA butter", "BrandA butter", null, "unspecified"),
                row("BrandA milk", "1 litre milk", 1000, "ml"),
            ) to "cross-row-volume"),
            (listOf(row("BrandA milk", "1 litre milk", 1000, "ml")) to "single-row-volume"),
        )
        rowShapes.forEachIndexed { index, (rows, shape) ->
            val instruction = "BrandA butter and ${rows.last().sourceText}"
            val prepared = runCatching {
                GroceryIntentDraft("grocery-intent-v1", true, instruction, rows, false)
                    .prepareForReview().items
            }
            if (prepared.isFailure) {
                observations += "brand-scope=$shape rejected-during-preparation"
            } else {
                val targetIndex = prepared.getOrThrow().lastIndex
                val item = prepared.getOrThrow()[targetIndex]
                val candidate = typedSku("BrandA Milk 500 ml", "global-brand-scope-$index", "500 ml")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                observations += "brand-scope=$shape resolution=$resolution"
                if (resolution is SwiggyCartQuantityResolution.RetailProposal ||
                    resolution is SwiggyCartQuantityResolution.Exact) {
                    failures += "brand-scope=$shape borrowed BrandA across rows: $resolution"
                }
            }
        }

        val localMilkShapes = listOf(
            listOf(
                row("BrandA butter", "BrandA butter", null, "unspecified"),
                row("milk", "2 packets milk", 2, "pack"),
            ) to "cross-row-local-name",
            listOf(row("milk", "2 packets milk", 2, "pack")) to "single-row-local-name",
        )
        localMilkShapes.forEachIndexed { index, (rows, shape) ->
            val prepared = runCatching {
                GroceryIntentDraft("grocery-intent-v1", true, sharedInstruction, rows, false)
                    .prepareForReview().items
            }
            if (prepared.isFailure) {
                failures += "brand-scope=$shape local identity failed preparation"
            } else {
                val targetIndex = prepared.getOrThrow().lastIndex
                val item = prepared.getOrThrow()[targetIndex]
                assertFalse("$shape must use only its local product identity", item.unprovenIdentityScope)
                val candidate = typedSku("Milk 500 ml", "local-brand-scope-$index", "500 ml")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                observations += "brand-scope=$shape resolution=$resolution"
                val proposal = resolution as? SwiggyCartQuantityResolution.RetailProposal
                if (proposal?.cartUnits != 2) {
                    failures += "brand-scope=$shape local milk should remain RetailProposal(2), got $resolution"
                }
            }
        }

        val preparedRows = GroceryIntentDraft(
            "grocery-intent-v1", true, sharedInstruction,
            listOf(
                row("BrandA butter", "BrandA butter", null, "unspecified"),
                row("BrandA milk", "2 packets milk", 2, "pack"),
            ), false,
        ).prepareForReview().items
        val butterCandidate = typedSku("BrandA Butter 500 g", "request-boundary-butter", "500 g")
        val milkCandidate = typedSku("BrandA Milk 500 ml", "request-boundary-milk", "500 ml")
        val assessedButter = assessSwiggyIntentQuantity(preparedRows[0], Recommendations(listOf(butterCandidate)))
        val assessedMilk = assessSwiggyIntentQuantity(preparedRows[1], Recommendations(listOf(milkCandidate)))
        val allCandidates = mapOf(0 to butterCandidate, 1 to milkCandidate)
        val allAssessed = listOf(assessedButter, assessedMilk)
        if (SwiggyCartReviewRequest.build(allAssessed, allCandidates, emptySet()) != null) {
            failures += "unskipped invalid milk row did not block the whole review request"
        }
        val butterOnly = SwiggyCartReviewRequest.build(allAssessed, allCandidates, setOf(1))
        if (butterOnly == null || butterOnly.requestedItems.map { it.spinId } != listOf(butterCandidate.spinId) ||
            butterOnly.requestedItems.map { it.quantity } != listOf(1)) {
            failures += "skipping invalid milk row did not preserve the valid butter request"
        }
        val badRow = preparedRows[1]
        assertTrue(badRow.unprovenIdentityScope)
        assertNull("An identity-scope hold must not ask the user to rewrite a valid quantity",
            swiggyMcpItemValidationMessage(sharedInstruction, preparedRows))
        listOf(
            "cleared-quantity-signal" to badRow.copy(quantitySignal = null),
            "cleared-proof-flag" to badRow.copy(requiresIntentQuantityProof = false),
            "edited-query" to badRow.copy(query = "milk", strictMatchPhrase = "milk"),
        ).forEach { (mutation, changed) ->
            val reassessed = assessSwiggyIntentQuantity(changed, Recommendations(listOf(milkCandidate)))
            val resolution = resolveSwiggyCartQuantity(reassessed, milkCandidate)
            if (resolution !is SwiggyCartQuantityResolution.Held ||
                SwiggyCartReviewRequest.build(listOf(reassessed), mapOf(0 to milkCandidate), emptySet()) != null) {
                failures += "$mutation made the wrong-brand milk row actionable: $resolution"
            }
        }

        assertTrue("V1 descriptor/global-brand evidence escaped its source scope: $failures; observations=$observations",
            failures.isEmpty())
    }

    @Test
    fun explicitBrandHeaderKeepsUnscopedRowsInReviewWithoutBlockingAnUnrelatedValidRow() {
        val instruction = "BrandA: butter, 2 packets milk; 1 jar honey"
        fun row(name: String, source: String, quantity: Int?, quantityUnit: String) = GroceryIntentItem(
            name = name,
            quantity = quantity?.toBigDecimal(),
            quantityUnit = quantityUnit,
            packValue = null,
            packUnit = "unspecified",
            required = emptyList(),
            excluded = emptyList(),
            sourceText = source,
            needsClarification = false,
        )
        val preparation = GroceryIntentDraft(
            "grocery-intent-v1", true, instruction,
            listOf(
                row("BrandA butter", "butter", null, "unspecified"),
                row("BrandA milk", "2 packets milk", 2, "pack"),
                row("honey", "1 jar honey", 1, "pack"),
            ), false,
        ).prepareForReview()
        val prepared = preparation.items
        assertEquals(3, prepared.size)
        assertTrue(prepared[0].unprovenIdentityScope)
        assertTrue(prepared[1].unprovenIdentityScope)
        assertFalse(prepared[2].unprovenIdentityScope)
        // Known copy follow-up for Claude Design: these rows are held, not
        // actionable merely by checking the shared-wording acknowledgement.
        assertTrue(preparation.unresolvedText.any {
            it.contains("Shared wording: BrandA") && it.contains("Check this in your basket.")
        })

        val candidates = mapOf(
            0 to typedSku("BrandA Butter 500 g", "header-butter", "500 g"),
            1 to typedSku("BrandA Milk 500 ml", "header-milk", "500 ml"),
            2 to typedSku("Honey Jar 500 g", "header-honey", "500 g"),
        )
        val assessed = prepared.mapIndexed { index, item ->
            assessSwiggyIntentQuantity(item, Recommendations(listOf(requireNotNull(candidates[index]))))
        }
        assertTrue(resolveSwiggyCartQuantity(assessed[0], requireNotNull(candidates[0])) is SwiggyCartQuantityResolution.Held)
        assertTrue(resolveSwiggyCartQuantity(assessed[1], requireNotNull(candidates[1])) is SwiggyCartQuantityResolution.Held)
        val honeyResolution = resolveSwiggyCartQuantity(assessed[2], requireNotNull(candidates[2]))
        assertTrue("Independently quoted honey remains a proposal: $honeyResolution",
            honeyResolution is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(1, (honeyResolution as SwiggyCartQuantityResolution.RetailProposal).cartUnits)

        assertNull(SwiggyCartReviewRequest.build(assessed, candidates, emptySet()))
        val honeyOnly = SwiggyCartReviewRequest.build(assessed, candidates, setOf(0, 1))
        assertEquals(listOf(requireNotNull(candidates[2]).spinId), honeyOnly?.requestedItems?.map { it.spinId })
        assertEquals(listOf(1), honeyOnly?.requestedItems?.map { it.quantity })
        val allSkipped = requireNotNull(SwiggyCartReviewRequest.build(assessed, candidates, setOf(0, 1, 2)))
        assertTrue(allSkipped.requestedItems.isEmpty())
        assertTrue(allSkipped.quantityProposals.isEmpty())
    }

    @Test
    fun ambiguousMergedAndDescriptorSourcesCannotReachAReviewRequest() {
        data class Case(
            val source: String,
            val name: String,
            val quantity: Int,
            val quantityUnit: String,
            val candidateTitle: String,
            val size: String,
        )
        val cases = listOf(
            Case("bread 2 jars honey", "honey", 2, "pack", "Honey Jar 500 g", "500 g"),
            Case("soap 2 bottles water", "water", 2, "pack", "Water Bottle 1 litre", "1 litre"),
            Case("2 milk and bread", "milk", 2, "count", "Milk 500 ml", "500 ml"),
            Case("2 milk, bread", "milk", 2, "count", "Milk 500 ml", "500 ml"),
            Case("2 milk bread", "milk", 2, "count", "Milk 500 ml", "500 ml"),
            Case("2 packets", "milk", 2, "pack", "Milk Packet 500 ml", "500 ml"),
            Case("Acme 6 pack juice", "Acme juice", 6, "pack", "Acme Juice 500 ml", "500 ml"),
            Case("six pack yogurt", "yogurt", 6, "pack", "Yogurt 500 g", "500 g"),
            Case("Acme 2 jars honey and 1 bottle oil", "Acme honey", 2, "pack", "Acme Honey Jar 500 g", "500 g"),
            Case("2 boxes of 3 bars chocolate", "chocolate", 6, "count", "Chocolate Bar 50 g", "50 g"),
        )
        val failures = mutableListOf<String>()

        val unitCases = cases.flatMap { case ->
            if (case.quantityUnit == "pack") listOf(case, case.copy(quantityUnit = "count")) else listOf(case)
        }
        unitCases.forEachIndexed { index, case ->
            val prepared = runCatching {
                prepare(case.source, case.name, quantity = case.quantity, quantityUnit = case.quantityUnit)
            }
            if (prepared.isFailure) return@forEachIndexed

            val item = prepared.getOrThrow()
            val candidate = typedSku(case.candidateTitle, "ambiguous-source-$index-${case.quantityUnit}", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet())
            if (resolution !is SwiggyCartQuantityResolution.Held || request != null) {
                failures += "case=$index unit=${case.quantityUnit} resolution=$resolution request=${request != null} " +
                    "proof=${item.intentQuantityProvenance?.origin} sourceRole=${item.intentQuantityProvenance?.sourceCountRole}"
            }
        }

        assertTrue("Ambiguous, merged, or descriptor source reached review: $failures", failures.isEmpty())
    }

    @Test
    fun parserIdentityFragmentsCannotAuthorizeMergedDefaultOrPhysicalRows() {
        data class Case(
            val source: String,
            val name: String,
            val quantity: Int?,
            val quantityUnit: String,
            val skuTitle: String,
            val size: String,
        )
        fun row(case: Case) = GroceryIntentItem(
            name = case.name,
            quantity = case.quantity?.toBigDecimal(),
            quantityUnit = case.quantityUnit,
            packValue = null,
            packUnit = "unspecified",
            required = emptyList(),
            excluded = emptyList(),
            sourceText = case.source,
            needsClarification = false,
        )
        fun prepared(case: Case) = GroceryIntentDraft(
            "grocery-intent-v1", true, case.source, listOf(row(case)), false,
        ).prepareForReview().items.single()

        val invalid = listOf(
            Case("milk and bread", "milk", null, "unspecified", "Milk 500 ml", "500 ml"),
            Case("1 litre milk and bread", "milk", 1000, "ml", "Milk 500 ml", "500 ml"),
            Case("500 g rice and sugar", "rice", 500, "g", "Rice 500 g", "500 g"),
        )
        invalid.forEachIndexed { index, case ->
            val item = prepared(case)
            val candidate = typedSku(case.skuTitle, "fragment-invalid-$index", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("case=$index must retain an independent identity-scope hold", item.unprovenIdentityScope)
            assertTrue("case=$index must remain Held",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            assertNull("case=$index cannot create a review request",
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
        }

        val plainMilk = Case("plain milk", "plain milk", null, "unspecified", "Plain Milk 500 ml", "500 ml")
        val positives = listOf(
            Case("milk", "milk", null, "unspecified", "Milk 500 ml", "500 ml") to 1,
            Case("1 litre milk", "milk", 1000, "ml", "Milk 500 ml", "500 ml") to 2,
            Case("500 g rice", "rice", 500, "g", "Rice 500 g", "500 g") to 1,
            plainMilk to 1,
        )
        positives.forEachIndexed { index, (case, expectedUnits) ->
            val item = prepared(case)
            val candidate = typedSku(case.skuTitle, "fragment-positive-$index", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertFalse("${case.source}: locally grounded identity must not be scope-held", item.unprovenIdentityScope)
            if (case.source == "plain milk") assertTrue(item.query.contains("plain", ignoreCase = true))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            val exact = resolution as? SwiggyCartQuantityResolution.Exact
            assertEquals("${case.source} should conserve a usable physical quantity", expectedUnits, exact?.cartUnits)
            assertTrue("${case.source} should remain requestable",
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null)
        }
    }

    @Test
    fun preparedIngredientEquivalencesRemainGroundedAtTheCatalogueBoundary() {
        data class Case(
            val source: String,
            val name: String,
            val quantity: Int?,
            val quantityUnit: String,
            val required: List<String>,
            val title: String,
            val size: String,
            val expectedProposal: Boolean,
        )
        fun row(case: Case) = GroceryIntentItem(
            name = case.name,
            quantity = case.quantity?.toBigDecimal(),
            quantityUnit = case.quantityUnit,
            packValue = null,
            packUnit = "unspecified",
            required = case.required,
            excluded = emptyList(),
            sourceText = case.source,
            needsClarification = false,
        )
        fun prepareCase(case: Case) = GroceryIntentDraft(
            "grocery-intent-v1", true, case.source, listOf(row(case)), false,
        ).prepareForReview().items.single()

        val positives = listOf(
            Case("one ready-made butter popcorn", "butter popcorn", 1, "count", listOf("ready_to_eat"),
                "Ready To Eat Butter Popcorn 100 g", "100 g", expectedProposal = true),
            Case("ready-made butter popcorn", "butter popcorn", null, "unspecified", listOf("ready_to_eat"),
                "Ready To Eat Butter Popcorn 100 g", "100 g", expectedProposal = false),
            Case("ready-made dosa batter", "ready-made dosa batter", null, "unspecified", emptyList(),
                "Ready Made Dosa Batter 500 g", "500 g", expectedProposal = false),
        )
        val failures = mutableListOf<String>()
        positives.forEachIndexed { index, case ->
            val prepared = runCatching { prepareCase(case) }
            if (prepared.isFailure) {
                failures += "positive=$index preparation=${prepared.exceptionOrNull()?.javaClass?.simpleName}"
            } else {
                val item = prepared.getOrThrow()
                val candidate = typedSku(case.title, "prepared-equivalence-positive-$index", case.size)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                val quantity = when (resolution) {
                    is SwiggyCartQuantityResolution.Exact -> resolution.cartUnits
                    is SwiggyCartQuantityResolution.RetailProposal -> resolution.cartUnits
                    is SwiggyCartQuantityResolution.Held -> null
                }
                val correctKind = if (case.expectedProposal)
                    resolution is SwiggyCartQuantityResolution.RetailProposal
                else resolution is SwiggyCartQuantityResolution.Exact
                if (item.unprovenIdentityScope || !correctKind || quantity != 1 ||
                    SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) == null) {
                    failures += "positive=$index scope=${item.unprovenIdentityScope} resolution=$resolution " +
                        "request=${SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null}"
                }
            }
        }

        val negative = Case("ready-made dosa batter", "dosa batter", null, "unspecified",
            listOf("ready_to_eat"), "Ready To Eat Dosa Batter 500 g", "500 g", expectedProposal = false)
        val rejected = runCatching { prepareCase(negative) }
        if (rejected.isSuccess) {
            val item = rejected.getOrThrow()
            val candidate = typedSku(negative.title, "prepared-equivalence-negative", negative.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            if (resolution !is SwiggyCartQuantityResolution.Held ||
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null) {
                failures += "negative scope=${item.unprovenIdentityScope} resolution=$resolution"
            }
        }
        assertTrue("Prepared-source equivalence escaped its grounded catalogue contract: $failures", failures.isEmpty())
    }

    @Test
    fun positiveSourceIdentityCannotBeMovedIntoExclusionsOrBorrowedFromSharedRows() {
        data class Case(
            val source: String,
            val name: String,
            val quantity: Int?,
            val quantityUnit: String,
            val excluded: List<String>,
            val title: String,
            val size: String,
        )
        fun prepareRow(case: Case, instruction: String = case.source) = GroceryIntentDraft(
            "grocery-intent-v1", true, instruction,
            listOf(GroceryIntentItem(
                name = case.name,
                quantity = case.quantity?.toBigDecimal(),
                quantityUnit = case.quantityUnit,
                packValue = null,
                packUnit = "unspecified",
                required = emptyList(),
                excluded = case.excluded,
                sourceText = case.source,
                needsClarification = false,
            )), false,
        ).prepareForReview().items.single()

        val invalid = listOf(
            Case("Acme 2 jars honey", "honey", 2, "pack", listOf("Acme"), "Honey Jar 500 g", "500 g"),
            Case("1 kg rice and dal", "rice", 1000, "g", listOf("dal"), "Rice 1 kg", "1 kg"),
            Case("milk and bread", "milk", null, "unspecified", listOf("bread"), "Milk 500 ml", "500 ml"),
            Case("tofu 2 milk", "milk", 2, "count", listOf("tofu"), "Milk 500 ml", "500 ml"),
            Case("2 milk without sugar", "sugar milk", 2, "count", emptyList(), "Sugar Milk 500 ml", "500 ml"),
            Case("2 milk without sugar", "milk", 2, "count", listOf("sugar"), "Unsweetened Milk 500 ml", "500 ml"),
            Case("2 biscuits no egg", "egg biscuits", 2, "count", emptyList(), "Egg Biscuits 200 g", "200 g"),
            Case("sugar ke bina chai", "sugar chai", null, "unspecified", emptyList(), "Sugar Tea 100 g", "100 g"),
        )
        val failures = mutableListOf<String>()
        invalid.forEachIndexed { index, case ->
            val prepared = runCatching { prepareRow(case) }
            if (prepared.isFailure) return@forEachIndexed
            val item = prepared.getOrThrow()
            val candidate = typedSku(case.title, "excluded-identity-$index", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            if (resolution !is SwiggyCartQuantityResolution.Held ||
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null) {
                failures += "excluded-identity=$index scope=${item.unprovenIdentityScope} resolution=$resolution"
            }
        }

        val positiveControls = listOf(
            // Absence of 'sugar' in a plain milk label is not affirmative
            // dietary evidence. The usable control must actually say so.
            Case("2 milk without sugar", "milk", 2, "count", listOf("sugar"), "Sugar-Free Milk 500 ml", "500 ml"),
            Case("2 milk without chocolate", "milk", 2, "count", listOf("chocolate"), "Milk 500 ml", "500 ml"),
        )
        positiveControls.forEachIndexed { index, case ->
            val prepared = runCatching { prepareRow(case) }
            if (prepared.isFailure) {
                failures += "valid-exclusion=$index preparation=${prepared.exceptionOrNull()?.javaClass?.simpleName}"
            } else {
                val item = prepared.getOrThrow()
                val candidate = typedSku(case.title, "valid-exclusion-$index", case.size)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                val proposal = resolution as? SwiggyCartQuantityResolution.RetailProposal
                if (item.unprovenIdentityScope || proposal?.cartUnits != case.quantity ||
                    SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) == null) {
                    failures += "valid-exclusion=$index scope=${item.unprovenIdentityScope} resolution=$resolution"
                }
            }
        }

        val sharedInstruction = "milk and bread"
        val sharedRows = listOf(
            Case(sharedInstruction, "milk", null, "unspecified", emptyList(), "Milk 500 ml", "500 ml"),
            Case(sharedInstruction, "bread", null, "unspecified", emptyList(), "Bread 400 g", "400 g"),
        )
        val sharedPrepared = GroceryIntentDraft(
            "grocery-intent-v1", true, sharedInstruction,
            sharedRows.map { case -> GroceryIntentItem(case.name, null, case.quantityUnit, null, "unspecified",
                emptyList(), case.excluded, case.source, false) }, false,
        ).prepareForReview().items
        val sharedCandidates = mapOf(
            0 to typedSku("Milk 500 ml", "shared-milk", "500 ml"),
            1 to typedSku("Bread 400 g", "shared-bread", "400 g"),
        )
        val sharedAssessed = sharedPrepared.mapIndexed { index, item ->
            val candidate = requireNotNull(sharedCandidates[index])
            assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        }
        sharedPrepared.forEachIndexed { index, item ->
            val candidate = requireNotNull(sharedCandidates[index])
            val resolution = resolveSwiggyCartQuantity(sharedAssessed[index], candidate)
            if (!item.unprovenIdentityScope || resolution !is SwiggyCartQuantityResolution.Held) {
                failures += "shared-full-quote=$index scope=${item.unprovenIdentityScope} resolution=$resolution"
            }
        }
        if (SwiggyCartReviewRequest.build(sharedAssessed, sharedCandidates, emptySet()) != null) {
            failures += "shared full-quote rows unexpectedly created a review request"
        }

        val individualInstruction = "milk and bread"
        val individualRows = listOf(
            Case("milk", "milk", null, "unspecified", emptyList(), "Milk 500 ml", "500 ml"),
            Case("bread", "bread", null, "unspecified", emptyList(), "Bread 400 g", "400 g"),
        )
        val individualPrepared = GroceryIntentDraft(
            "grocery-intent-v1", true, individualInstruction,
            individualRows.map { case -> GroceryIntentItem(case.name, null, case.quantityUnit, null, "unspecified",
                emptyList(), case.excluded, case.source, false) }, false,
        ).prepareForReview().items
        val individualAssessed = individualPrepared.mapIndexed { index, item ->
            assessSwiggyIntentQuantity(item, Recommendations(listOf(requireNotNull(sharedCandidates[index]))))
        }
        individualPrepared.forEachIndexed { index, item ->
            val candidate = requireNotNull(sharedCandidates[index])
            val resolution = resolveSwiggyCartQuantity(individualAssessed[index], candidate)
            if (item.unprovenIdentityScope || resolution !is SwiggyCartQuantityResolution.Exact ||
                resolution.cartUnits != 1) {
                failures += "individual-quote=$index scope=${item.unprovenIdentityScope} resolution=$resolution"
            }
        }
        if (SwiggyCartReviewRequest.build(individualAssessed, sharedCandidates, emptySet()) == null) {
            failures += "individually quoted milk/bread rows were not requestable"
        }

        assertTrue("Excluded/shared identity evidence escaped its source row: $failures", failures.isEmpty())
    }

    @Test
    fun v1ContainerRequestWithExplicitPreservationExclusionRemainsUsable() {
        // A terminal exclusion is a signed property, not a correction to the
        // requested container. Keep original source and exact pack review.
        val source = "one carton milk, not frozen"
        val item = prepare(source, "milk", excluded = listOf("frozen"))
        val candidate = typedSku("Milk Carton 500 ml", "not-frozen-carton", "500 ml")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertFalse(item.unprovenIdentityScope)
        assertProposalCanBeAcknowledgedOnce(checked, candidate, 1)
    }

    @Test
    fun v1ContainerAndExclusionProofsAreCollectedAcrossProductFamilies() {
        data class Case(
            val source: String,
            val name: String,
            val container: String,
            val excluded: String,
            val size: String,
            val wrongTitle: String,
            val instruction: String = source,
        )
        val cases = listOf(
            Case("one carton milk, not frozen", "milk", "carton", "frozen", "500 ml", "Milk Carton Frozen 500 ml"),
            Case("one bag peas, not dried", "peas", "bag", "dried", "500 g", "Dried Peas Bag 500 g"),
            Case("one bottle juice, not frozen", "juice", "bottle", "frozen", "1 l", "Frozen Juice Bottle 1 l"),
        )
        val failures = mutableListOf<String>()

        fun candidate(label: String, id: String, size: String) = typedSku(label, id, size)

        cases.forEachIndexed { index, case ->
            val prepared = runCatching {
                prepare(case.source, case.name, quantity = 1, quantityUnit = "count",
                    excluded = listOf(case.excluded), instruction = case.instruction)
            }
            if (prepared.isFailure) {
                failures += "positive=$index prepare=${prepared.exceptionOrNull()?.javaClass?.simpleName}"
                return@forEachIndexed
            }
            val item = prepared.getOrThrow()
            val expectedRaw = case.source
            val positive = candidate(
                "${case.name.replaceFirstChar { it.uppercase() }} ${case.container} ${case.size}",
                "source-exclusion-positive-$index", case.size,
            )
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(positive)))
            val resolution = resolveSwiggyCartQuantity(checked, positive)
            val proposal = resolution as? SwiggyCartQuantityResolution.RetailProposal
            val avoidsRetained = item.avoidPhrases.any { it.equals(case.excluded, ignoreCase = true) }
            if (item.rawText != expectedRaw || item.quantity != Quantity.Count(1) ||
                !item.query.contains(case.container, ignoreCase = true) || !avoidsRetained ||
                item.unprovenIdentityScope || proposal?.cartUnits != 1) {
                failures += "positive=$index raw=${item.rawText == expectedRaw} count=${item.quantity == Quantity.Count(1)} " +
                    "container=${item.query.contains(case.container, ignoreCase = true)} avoid=$avoidsRetained " +
                    "scope=${item.unprovenIdentityScope} resolution=$resolution"
            } else {
                runCatching { assertProposalCanBeAcknowledgedOnce(checked, positive, 1) }
                    .onFailure { failures += "positive=$index acknowledgement=${it.javaClass.simpleName}" }
            }

            val tampered = listOf(
                "exclusion removed" to item.copy(avoidPhrases = emptyList()),
                "exclusion changed" to item.copy(avoidPhrases = listOf("unrelated")),
                "strict phrase removed" to item.copy(strictMatchPhrase = null),
                "query changed" to item.copy(query = "${case.container} unrelated"),
            )
            tampered.forEach { (mutation, changed) ->
                val changedChecked = assessSwiggyIntentQuantity(changed, Recommendations(listOf(positive)))
                val changedResolution = resolveSwiggyCartQuantity(changedChecked, positive)
                if (changedResolution !is SwiggyCartQuantityResolution.Held ||
                    SwiggyCartReviewRequest.build(listOf(changedChecked), mapOf(0 to positive), emptySet()) != null) {
                    failures += "tampered-positive=$index mutation=$mutation resolution=$changedResolution"
                }
            }
            val splitProperty = when (case.excluded) {
                "frozen" -> listOf("fro zen", "fro_zen", "fro-zen")
                "dried" -> listOf("dri ed", "dri_ed", "dri-ed")
                else -> emptyList()
            }
            splitProperty.forEach { changedProperty ->
                val changed = item.copy(avoidPhrases = listOf(changedProperty))
                val changedChecked = assessSwiggyIntentQuantity(changed, Recommendations(listOf(positive)))
                val changedResolution = resolveSwiggyCartQuantity(changedChecked, positive)
                if (changedResolution !is SwiggyCartQuantityResolution.Held ||
                    SwiggyCartReviewRequest.build(listOf(changedChecked), mapOf(0 to positive), emptySet()) != null) {
                    failures += "tampered-property=$index value=$changedProperty resolution=$changedResolution"
                }
            }

            val wrong = candidate(case.wrongTitle, "source-exclusion-wrong-$index", case.size)
            val wrongChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(wrong)))
            val wrongResolution = resolveSwiggyCartQuantity(wrongChecked, wrong)
            if (wrongResolution !is SwiggyCartQuantityResolution.Held) {
                failures += "wrong-excluded-variant=$index resolution=$wrongResolution"
            }
        }

        data class Negative(val source: String, val name: String, val excluded: List<String>, val label: String,
                            val size: String, val instruction: String = source)
        val negatives = listOf(
            Negative("one carton milk, not frozen", "milk", emptyList(), "Milk Carton 500 ml", "500 ml"),
            Negative("one bag peas", "peas", listOf("dried"), "Peas Bag 500 g", "500 g"),
            Negative("one bottle milk, not frozen", "frozen milk", listOf("frozen"), "Frozen Milk Bottle 500 ml", "500 ml"),
            Negative("one carton milk", "milk", listOf("frozen"), "Milk Carton 500 ml", "500 ml",
                instruction = "one carton milk, not frozen, peas"),
        )
        negatives.forEachIndexed { index, case ->
            val prepared = runCatching {
                prepare(case.source, case.name, quantity = 1, quantityUnit = "count",
                    excluded = case.excluded, instruction = case.instruction)
            }
            if (prepared.isFailure) return@forEachIndexed
            val item = prepared.getOrThrow()
            val candidate = candidate(case.label, "source-exclusion-negative-$index", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            if (item.rawText != case.source ||
                resolution !is SwiggyCartQuantityResolution.Held ||
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null) {
                failures += "negative=$index raw=${item.rawText == case.source} scope=${item.unprovenIdentityScope} " +
                    "resolution=$resolution"
            }
        }

        cases.forEachIndexed { index, case ->
            fun requireRejectOrHold(label: String, quantity: Int = 1, instruction: String = case.source,
                                    extraAmount: Boolean = false) {
                val prepared = runCatching {
                    if (!extraAmount) {
                        prepare(case.source, case.name, quantity = quantity, quantityUnit = "count",
                            excluded = listOf(case.excluded), instruction = instruction)
                    } else {
                        val json = response(case.source, case.name, quantity, "count", emptyList(), instruction)
                            .replace("\"excluded\":[]", "\"excluded\":[${jsonString(case.excluded)}]")
                            .replace("\"pack_value\":null", "\"pack_value\":500")
                            .replace("\"pack_unit\":\"unspecified\"", "\"pack_unit\":\"ml\"")
                        GroceryIntentResponse.parse(json, instruction).draft.prepareForReview().items.single()
                    }
                }
                if (prepared.isFailure) return
                val item = prepared.getOrThrow()
                val candidate = candidate(
                    "${case.name.replaceFirstChar { it.uppercase() }} ${case.container} ${case.size}",
                    "unsafe-context-$index-${label.replace(' ', '-')}", case.size,
                )
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                if (item.rawText != case.source || resolution !is SwiggyCartQuantityResolution.Held ||
                    SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null) {
                    failures += "unsafe-context=$index case=$label raw=${item.rawText == case.source} " +
                        "scope=${item.unprovenIdentityScope} resolution=$resolution"
                }
            }

            requireRejectOrHold("prefix", instruction = "frozen, ${case.source}")
            requireRejectOrHold("suffix", instruction = "${case.source}, frozen")
            requireRejectOrHold("multi product", instruction = "${case.source}, peas")
            requireRejectOrHold("count mismatch", quantity = 2)
            requireRejectOrHold("extra amount", extraAmount = true)
        }

        data class AmbiguousRole(
            val source: String,
            val name: String,
            val quantity: Int,
            val quantityUnit: String,
            val excluded: List<String>,
            val title: String,
            val size: String,
        )
        val ambiguousRoles = listOf(
            AmbiguousRole("milk, not the frozen one", "milk", 1, "count", listOf("frozen"),
                "Milk 500 ml", "500 ml"),
            // Existing descriptor and nested-contents cases: terminal exclusion
            // must not cause the inner/descriptive number to become a purchase count.
            AmbiguousRole("6 pack juice, not frozen", "juice", 6, "pack", listOf("frozen"),
                "Juice 500 ml", "500 ml"),
            AmbiguousRole("2 packets of 6 biscuits, not frozen", "biscuits", 6, "count", listOf("frozen"),
                "Biscuits 200 g", "200 g"),
        )
        ambiguousRoles.forEachIndexed { index, case ->
            val prepared = runCatching {
                prepare(case.source, case.name, quantity = case.quantity, quantityUnit = case.quantityUnit,
                    excluded = case.excluded)
            }
            if (prepared.isFailure) return@forEachIndexed
            val item = prepared.getOrThrow()
            val explicitCount = item.intentQuantityProvenance?.let { proof ->
                proof.resolvedQuantity == item.quantity && proof.local?.unit in setOf("count", "pack") &&
                    item.quantity is Quantity.Count
            } == true
            val candidate = typedSku(case.title, "ambiguous-role-$index", case.size)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            val unsafeClaim = if (index == 0) {
                // With no source count, retain Default or reject; the terminal
                // word "one" in the negated identity is not Count(1).
                item.quantity == Quantity.Count(1) || explicitCount
            } else {
                explicitCount || resolution !is SwiggyCartQuantityResolution.Held ||
                    SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null
            }
            if (item.rawText != case.source || unsafeClaim) {
                failures += "ambiguous-role=$index raw=${item.rawText == case.source} explicitCount=$explicitCount " +
                    "quantity=${item.quantity} resolution=$resolution"
            }
        }
        assertTrue("V1 container/exclusion family outcomes: $failures", failures.isEmpty())
    }

    @Test
    fun genericRetailUnitsDoNotClaimASpecificContainerStyle() {
        listOf("2 packs milk", "2 packets milk").forEach { source ->
            assertNull(sourceRetailIdentityConstraint(source))
            val item = prepare(source, "milk", quantity = 2)
            assertTrue(item.requiresIntentQuantityProof)
            assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, item.intentQuantityProvenance?.origin)
            assertNull(item.validNamedRetailReceipt())
            val candidate = typedSku("Milk 500 ml", "generic-${source.hashCode()}", "500 ml")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val resolution = resolveSwiggyCartQuantity(checked, candidate)
            assertTrue("$source typed physical SKU remains a proposal: $resolution",
                resolution is SwiggyCartQuantityResolution.RetailProposal)
            assertFalse("$source is not exact under the current receipt contract", resolution is SwiggyCartQuantityResolution.Exact)
            val titleOnly = sku("Milk", "generic-title-only-${source.hashCode()}")
            val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly)))
            assertTrue("$source title-only SKU has no physical amount",
                resolveSwiggyCartQuantity(titleOnlyChecked, titleOnly) is SwiggyCartQuantityResolution.Held)
            assertProposalCanBeAcknowledgedOnce(checked, candidate, 2)
        }
    }

    @Test
    fun knownGroundingTranslationsUseTheSameIdentityVocabularyAtCatalogueBoundary() {
        val item = prepare("1 bunch dhaniya", "coriander")
        assertTrue(item.query.contains("coriander"))
        assertTrue(item.query.contains("bunch"))
        assertTrue(item.requiresIntentQuantityProof)
        assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, item.intentQuantityProvenance?.origin)
        assertNull(item.validNamedRetailReceipt())
        val matching = typedSku("Fresh Coriander Bunch", "translated-form", "100 g")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(matching)))
        assertTrue(isSwiggyCandidateAllowed(checked, matching))
        val resolution = resolveSwiggyCartQuantity(checked, matching)
        assertTrue("Typed translated offer remains a proposal: $resolution",
            resolution is SwiggyCartQuantityResolution.RetailProposal)
        assertFalse(resolution is SwiggyCartQuantityResolution.Exact)
        val titleOnly = sku("Fresh Coriander Bunch", "translated-title-only")
        val titleOnlyChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly)))
        assertTrue(resolveSwiggyCartQuantity(titleOnlyChecked, titleOnly) is SwiggyCartQuantityResolution.Held)
        assertProposalCanBeAcknowledgedOnce(checked, matching, 1)
        val wrongForm = typedSku("Coriander Powder", "wrong-form", "100 g")
        val wrongFormChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(wrongForm)))
        assertTrue(resolveSwiggyCartQuantity(wrongFormChecked, wrongForm) is SwiggyCartQuantityResolution.Held)
        val formless = typedSku("Fresh Coriander 100 g", "translated-formless", "100 g")
        val formlessChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(formless)))
        assertFalse(isSwiggyCandidateAllowed(formlessChecked, formless))
        assertTrue(resolveSwiggyCartQuantity(formlessChecked, formless) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun suffixPhysicalSizesDoNotAuthorizeWrongLiteralRetailForms() {
        data class Case(
            val source: String,
            val identity: String,
            val quantity: Int,
            val form: String,
            val wrongForm: String,
            val size: String,
            val quantityUnit: String,
            val packValue: Int?,
            val packUnit: String,
        )

        val cases = listOf(
            Case("one carton coconut milk, 400 ml", "coconut milk", 1, "carton", "can", "400 ml", "pack", 400, "ml"),
            Case("one 400 ml carton of coconut milk", "coconut milk", 1, "carton", "can", "400 ml", "pack", 400, "ml"),
            Case("2 bottle mineral water, 1 litre each", "mineral water", 2, "bottle", "pouch", "1 litre", "count", 1, "l"),
            Case("2 1 litre bottles of mineral water", "mineral water", 2, "bottle", "pouch", "1 litre", "count", 1, "l"),
            // "cup" is intentionally outside the named source-role grammar.
            Case("two cup plain curd", "plain curd", 2, "cup", "tub", "150 g", "count", null, "unspecified"),
        )
        val failures = mutableListOf<String>()
        val observations = mutableListOf<String>()

        for ((index, case) in cases.withIndex()) {
            val prepared = runCatching {
                GroceryIntentDraft("grocery-intent-v1", true, case.source, listOf(
                    GroceryIntentItem(case.identity, case.quantity.toBigDecimal(), case.quantityUnit,
                        case.packValue?.toBigDecimal(), case.packUnit, emptyList(), emptyList(), case.source, false),
                ), false).prepareForReview().items.single()
            }
            if (prepared.isFailure) {
                failures += "case=$index preparation rejected"
                continue
            }
            val item = prepared.getOrThrow()
            fun candidate(form: String, suffix: String, oneUnit: Boolean = false): RecommendationCandidate {
                val skuId = "form-size-$index-$suffix"
                val spinId = "spin-$skuId"
                return RecommendationCandidate(
                    spinId = spinId,
                    label = "${case.identity} $form ${case.size}",
                    skuId = skuId,
                    catalogueQuantity = CatalogueQuantityEvidence(
                        version = 1,
                        source = "search_variation",
                        spinId = spinId,
                        skuId = skuId,
                        fields = CatalogueQuantityFields(packSize = case.size,
                            quantityDescription = if (oneUnit) "1 $form" else null),
                    ),
                )
            }

            val named = index in setOf(1, 3)
            if (named) {
                val unknown = candidate(case.form, "unknown-count")
                val unknownChecked = assessSwiggyIntentQuantity(item, Recommendations(listOf(unknown)))
                if (resolveSwiggyCartQuantity(unknownChecked, unknown) !is SwiggyCartQuantityResolution.Held)
                    failures += "case=$index physical size proved an unsupported named count"
            }
            val correct = candidate(case.form, "correct", oneUnit = named)
            val wrong = candidate(case.wrongForm, "wrong", oneUnit = named)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(correct, wrong)))
            val correctResolution = resolveSwiggyCartQuantity(checked, correct)
            val wrongAllowed = isSwiggyCandidateAllowed(checked, wrong)
            val wrongResolution = resolveSwiggyCartQuantity(checked, wrong)
            val formRetained = item.query.contains(case.form, ignoreCase = true)

            if (!formRetained || !isSwiggyCandidateAllowed(checked, correct) ||
                correctResolution is SwiggyCartQuantityResolution.Held) {
                failures += "case=$index matching-form path was lost"
            }
            if (index in setOf(0, 2, 4) && correctResolution !is SwiggyCartQuantityResolution.RetailProposal) {
                failures += "case=$index identity-only evidence changed the proposal role"
            }
            if (wrongAllowed || wrongResolution !is SwiggyCartQuantityResolution.Held) {
                failures += "case=$index wrong-form candidate escaped source form constraint"
            }
            val edited = checked.copy(query = case.identity, strictMatchPhrase = case.identity)
            if (isSwiggyCandidateAllowed(edited, wrong) ||
                resolveSwiggyCartQuantity(edited, wrong) !is SwiggyCartQuantityResolution.Held) {
                failures += "case=$index edited query bypassed the source form"
            }
            observations += "case=$index queryRetainsForm=$formRetained correctAllowed=${isSwiggyCandidateAllowed(checked, correct)} " +
                "correctResolution=$correctResolution wrongAllowed=$wrongAllowed wrongResolution=$wrongResolution"
        }

        assertTrue("Wrong-form candidates must be forbidden. Failures: $failures; Diagnostics: ${observations.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun sourceFormConservationUsesIdentityVocabularyNotQuantityOrProductRules() {
        val cases = mapOf(
            "Can you get one can beans" to setOf("can"),
            "Can you get one apple" to emptySet(),
            "one bottle brush" to setOf("bottle"),
            "one can opener" to setOf("can"),
            "two cups sugar" to setOf("cup"),
            "one spray cleaner" to setOf("spray"),
            "one tetra pack milk" to setOf("tetrapack"),
            "two packs milk" to emptySet(),
            "one carton milk not a bottle" to null,
            "one carton or bottle milk" to null,
            "one bottle milk, sorry a carton" to null,
        )
        cases.forEach { (source, expected) ->
            assertEquals(source, expected, groceryIntentPositiveSourceFormTokens(source))
        }
        // Identity extraction does not broaden the old numeric witness.
        listOf("one carton coconut milk, 400 ml", "two cup plain curd", "one spray cleaner").forEach { source ->
            assertNull(sourceRetailCountReceipt(source))
            assertNull(sourceRetailIdentityConstraint(source))
        }
        // The shared V2 omission guard retains its existing behavior even for
        // scoped negative statements; the new positive accessor reports unknown.
        assertTrue(groceryIntentStatedPackagingMissing(listOf("not a bottle"), emptySet()))
        assertFalse(groceryIntentStatedPackagingMissing(listOf("Can you get one apple"), emptySet()))
    }

    @Test
    fun unrelatedCorrectionOrRestrictionCannotSilentlyEraseARequestedForm() {
        val sources = listOf(
            "one carton milk only",
            "one carton milk, not frozen",
            "one carton milk, not a bottle",
            "one carton milk, sorry two",
            "one carton milk or juice",
        )
        val failures = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            val prepared = runCatching {
                prepare(source, "milk", quantityUnit = "count",
                    excluded = if (index == 1) listOf("frozen") else emptyList())
            }.getOrNull()
            val skuId = "scope-$index"
            val candidate = RecommendationCandidate("spin-$skuId", "Milk Can 500 ml", skuId = skuId,
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "spin-$skuId", skuId,
                    CatalogueQuantityFields(packSize = "500 ml")))
            // Even a direct/editable ParsedItem cannot turn unknown into absent.
            val direct = com.example.beta.automation.ParsedItem(
                rawText = source, query = "milk", quantity = Quantity.Count(1))
            val checked = assessSwiggyIntentQuantity(prepared ?: direct, Recommendations(listOf(candidate)))
            if (isSwiggyCandidateAllowed(checked, candidate) ||
                resolveSwiggyCartQuantity(checked, candidate) !is SwiggyCartQuantityResolution.Held) {
                failures += "case=$index queryRetainedForm=${prepared?.query?.contains("carton")} " +
                    "resolution=${resolveSwiggyCartQuantity(checked, candidate)}"
            }
        }
        assertTrue("Scoped form omissions escaped: $failures", failures.isEmpty())
    }

    private fun prepare(
        source: String,
        name: String,
        quantity: Int = 1,
        quantityUnit: String = "pack",
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        instruction: String = source,
    ) = GroceryIntentResponse.parse(
        response(source, name, quantity, quantityUnit, required, instruction).replace(
            "\"excluded\":[]", "\"excluded\":" + excluded.joinToString(",", "[", "]", transform = ::jsonString)), instruction,
    ).draft.prepareForReview().items.single()

    private fun sku(label: String, skuId: String) =
        RecommendationCandidate("spin-$skuId", label, skuId = skuId)

    private fun typedSku(label: String, skuId: String, size: String) = RecommendationCandidate(
        spinId = "spin-$skuId",
        label = label,
        skuId = skuId,
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = "spin-$skuId",
            skuId = skuId,
            fields = CatalogueQuantityFields(packSize = size),
        ),
    )

    private fun assertProposalCanBeAcknowledgedOnce(
        item: com.example.beta.automation.ParsedItem,
        candidate: RecommendationCandidate,
        quantity: Int,
    ) {
        assertReviewedRetailProposal(item, candidate, quantity)
        val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(item), mapOf(0 to candidate), emptySet()))
        assertEquals(listOf(candidate.spinId), request.requestedItems.map { it.spinId })
        assertEquals(listOf(quantity), request.requestedItems.map { it.quantity })
        assertEquals(listOf(quantity), request.quantityProposals.map { it.quantity })
        val gate = SwiggyQuantityProposalGate()
        val token = "source-form-review-token"
        gate.prepare(741, "source-form-review-address", token, request.quantityProposals)
        assertFalse(gate.canApply(741, "source-form-review-address", token, request.quantityProposals))
        assertTrue(gate.acknowledge(741, "source-form-review-address", token, request.quantityProposals, checked = true))
        assertTrue(gate.consumeForApply(741, "source-form-review-address", token, request.quantityProposals))
        assertFalse(gate.consumeForApply(741, "source-form-review-address", token, request.quantityProposals))
    }

    private fun response(source: String, name: String, quantity: Int, quantityUnit: String,
                         required: List<String>, instruction: String): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(instruction)},"items":[{"name":${jsonString(name)},"quantity":$quantity,"quantity_unit":${jsonString(quantityUnit)},"pack_value":null,"pack_unit":"unspecified","required":${required.joinToString(",", "[", "]", transform = ::jsonString)},"excluded":[],"source_text":${jsonString(source)},"needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
