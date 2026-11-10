package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCompositeV1AdapterTest {
    @Test
    fun v1CountAndPackModelsNeedSourceAndTypedCompositeOfferForAProposal() {
        val cases = listOf(
            Positive("one pack of 2 x 500 ml milk", "milk", 1, "pack", "500", "ml", "2 x 500 ml"),
            Positive("2 x 500 ml milk packs paanch", "milk", 5, "count", "500", "ml", "2 x 500 ml"),
            Positive("2 x 500 ml milk packs paanch", "milk", 5, "pack", "500", "ml", "2 x 500 ml"),
            Positive("2 packs of 2 x 500 ml milk", "milk", 2, "count", "500", "ml", "2 x 500 ml"),
            Positive("2 packs of 2 x 500 ml milk", "milk", 2, "pack", "500", "ml", "2 x 500 ml"),
            Positive("5 packs of 2 x 5 kg rice", "rice", 5, "pack", "5", "kg", "2 x 5 kg"),
            Positive("3 packs of 2 x 0.5 l milk", "milk", 3, "count", "500", "ml", "2 x 500 ml"),
            Positive("2 x 500 ml milk packs paanch", "milk 2 x 500 ml", 5, "pack", null, "unspecified", "2 x 500 ml"),
        )

        cases.forEachIndexed { index, case ->
            val item = prepare(case.source, case.name, case.count, case.quantityUnit,
                case.packValue, case.packUnit)
            assertEquals("case $index original source", case.source, item.rawText)
            assertEquals("case $index model count remains explicit", Quantity.Count(case.count), item.quantity)
            val receipt = requireNotNull(item.intentQuantityProvenance?.compositeRetailReceipt)
            assertEquals("case $index receipt source", case.source, receipt.source)
            assertEquals("case $index requested count", case.count,
                receipt.requestedCount.value.intValueExact())
            assertEquals("case $index role", IntentSourceCountRole.COMPOSITE_RETAIL_OFFER,
                item.intentQuantityProvenance?.sourceCountRole)

            val candidate = candidate(case.name.substringBefore(" 2 x"), case.shape)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val result = resolveSwiggyCartQuantity(checked, candidate)
            assertTrue("case $index must produce a reviewed proposal only", result is SwiggyCartQuantityResolution.RetailProposal)
            result as SwiggyCartQuantityResolution.RetailProposal
            assertEquals("case $index requested units", case.count, result.cartUnits)
            assertTrue("case $index should retain typed composite contents",
                result.contents is SwiggyRetailOfferContents.ProvenComposite)
            assertTrue("case $index must not be exposed as exact cart quantity", runCatching {
                swiggyRequestedCartQuantity(checked, candidate)
            }.isFailure)
        }
    }

    @Test
    fun modelMismatchAndUnprovenV1RowsNeverBecomeExactOrRetailProposals() {
        val source = "2 x 500 ml milk packs paanch"
        val cases = listOf(
            Negative("aggregate count", source, "milk", 10, "pack", "500", "ml"),
            Negative("factor used as requested count", source, "milk", 2, "pack", "500", "ml"),
            Negative("total contents substituted for inner size", source, "milk", 5, "pack", "1000", "ml"),
            Negative("wrong dimension", source, "milk", 5, "pack", "500", "g"),
            Negative("conflicting factor in name", source, "milk 3 x 500 ml", 5, "pack", "500", "ml"),
            Negative("equal-total conflicting shape in name", source, "milk 4 x 250 ml", 5, "pack", "500", "ml"),
            Negative("factor collides with requested count in name", source, "milk 5 x 500 ml", 5, "pack", "500", "ml"),
            Negative("unsourced organic tag", source, "milk", 5, "pack", "500", "ml", required = listOf("organic")),
            Negative("numeric 1 l tag", source, "milk", 5, "pack", "500", "ml", required = listOf("1 l")),
            Negative("exclusion", source, "milk", 5, "pack", "500", "ml", excluded = listOf("sugar")),
            Negative("clarification", source, "milk", 5, "pack", "500", "ml", needsClarification = true),
            Negative("condition", "if available, $source", "milk", 5, "pack", "500", "ml"),
            Negative("shared sugar-free header", "sugar-free: $source", "milk", 5, "pack", "500", "ml",
                required = listOf("sugar_free")),
            Negative("correction", "$source, make it 2 packs", "milk", 5, "pack", "500", "ml"),
            Negative("duplicate quote", "$source; $source", "milk", 5, "pack", "500", "ml"),
        )

        val failures = cases.mapNotNull { case ->
            val outcome = runCatching {
                prepare(case.instruction, case.name, case.count, case.quantityUnit,
                    case.packValue, case.packUnit, case.required, case.excluded, case.needsClarification)
            }
            val exception = outcome.exceptionOrNull()
            if (exception != null) {
                if (exception is IntentNeedsReviewException) null else "${case.label}: unexpected ${exception::class.simpleName}"
            } else {
                val item = requireNotNull(outcome.getOrNull())
                val candidate = candidate("milk", "2 x 500 ml")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(checked, candidate)
                if (result is SwiggyCartQuantityResolution.Held) null
                else "${case.label}: unexpectedly actionable $result"
            }
        }
        assertTrue("V1 mismatches must be held or require review:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun sourceOnlyPackShapeCannotFillAnAbsentModelOfferAndNamedCartonsAreNotPackSkus() {
        val source = "2 x 500 ml milk packs paanch"
        val missingPack = runCatching { prepare(source, "milk", 5, "pack", null, "unspecified") }
        val missingOutcome = missingPack.getOrNull()
        if (missingOutcome == null) {
            assertTrue(missingPack.exceptionOrNull() is IntentNeedsReviewException)
        } else {
            val offer = candidate("milk", "2 x 500 ml")
            val checked = assessSwiggyIntentQuantity(missingOutcome, Recommendations(listOf(offer)))
            assertTrue("source shape cannot supply a missing model pack field",
                resolveSwiggyCartQuantity(checked, offer) is SwiggyCartQuantityResolution.Held)
        }

        val cartonSource = "3 cartons of 2 x 500 ml milk"
        val carton = prepare(cartonSource, "milk", 3, "pack", "500", "ml")
        val cartonOffer = candidate("milk", "2 x 500 ml", titlePrefix = "Carton")
        val cartonChecked = assessSwiggyIntentQuantity(carton, Recommendations(listOf(cartonOffer)))
        assertTrue("named outer cartons are not generic pack SKUs",
            resolveSwiggyCartQuantity(cartonChecked, cartonOffer) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun croppedAndSplitSourceQuotesCannotAssembleTheThreeAmountReceipt() {
        val full = "2 x 500 ml milk packs paanch"
        val cropped = runCatching {
            prepare(full, "milk", 5, "pack", "500", "ml", sourceText = "500 ml milk packs paanch")
        }
        assertTrue("cropping the factor must require review", cropped.exceptionOrNull() is IntentNeedsReviewException)

        val splitRows = listOf(
            row("milk", 2, "pack", "500", "ml", "2 x 500 ml milk"),
            row("milk", 5, "pack", "500", "ml", "packs paanch"),
        )
        val split = runCatching { GroceryIntentDraft("grocery-intent-v1", true, full, splitRows, false).prepareForReview() }
        if (split.exceptionOrNull() != null) {
            assertTrue(split.exceptionOrNull() is IntentNeedsReviewException)
        } else {
            val actionable = requireNotNull(split.getOrNull()).items.any { item ->
                val offer = candidate("milk", "2 x 500 ml")
                val result = resolveSwiggyCartQuantity(
                    assessSwiggyIntentQuantity(item, Recommendations(listOf(offer))), offer,
                )
                result !is SwiggyCartQuantityResolution.Held
            }
            assertFalse("split model rows must not acquire a composite quantity", actionable)
        }
    }

    @Test
    fun separatelyCitedConditionOrPropertyCannotLaunderAPlainCompositeQuote() {
        val plain = "3 packs of 2 x 500 ml milk"
        val conditionInstruction = "if available, $plain"
        assertGuardRejectsLaunderedScope(conditionInstruction, plain, "if available")

        val propertyInstruction = "$plain, sugar-free"
        assertGuardRejectsLaunderedScope(propertyInstruction, plain, "sugar-free")
    }

    @Test
    fun v1CompositeReceiptGuardRejectsCroppedAndUnsupportedMultiplierShapes() {
        val instruction = "2 x 500 ml milk packs three, bread"
        val croppedRows = listOf(
            row("milk", 3, "pack", "500", "ml", "500 ml milk packs three,"),
            row("bread", null, "unspecified", null, "unspecified", "bread"),
        )
        val cropped = runCatching { compositeV1Receipts(instruction, croppedRows) }
        assertTrue("comma-ended crop cannot omit the factor",
            cropped.exceptionOrNull() is IntentNeedsReviewException)

        val crossingInstruction = "2 x 500 ml milk packs three"
        val crossingRows = listOf(
            row("milk", 3, "pack", "500", "ml", "2 x"),
            row("milk", 3, "pack", "500", "ml", "500 ml milk packs three"),
        )
        val crossing = runCatching { compositeV1Receipts(crossingInstruction, crossingRows) }
        assertTrue("separate quotes cannot combine across a multiplier",
            crossing.exceptionOrNull() is IntentNeedsReviewException)

        val unsupported = listOf(
            "3 packs of 1 x 500 ml milk",
            "3 packs of 100 x 5 ml milk",
            "3 packs of do x 500 ml milk",
        )
        unsupported.forEach { source ->
            val rows = listOf(row("milk", 3, "pack", "500", "ml", source))
            val guarded = runCatching { compositeV1Receipts(source, rows) }
            val error = guarded.exceptionOrNull()
            assertTrue("unsupported factor must not yield a receipt: $source",
                error == null && guarded.getOrNull().isNullOrEmpty() || error is IntentNeedsReviewException)
        }

        val ordinary = "2 x 500 ml milk"
        val ordinaryRow = row("milk", null, "unspecified", null, "unspecified", ordinary)
        assertEquals("contained two-amount variant stays on its existing path", emptyMap<Int, CompositeRetailReceipt>(),
            compositeV1Receipts(ordinary, listOf(ordinaryRow)))
    }

    @Test
    fun ordinaryNoReceiptBasketRowsLeaveTheSpecializedCompositeGateNeutral() {
        // This checks only composite-receipt gate neutrality. It does not
        // claim the full basket is grounded, ready, or suitable for cart use.
        val cases = listOf(
            "2 x 500 ml milk, bread only" to listOf(
                row("milk", null, "unspecified", null, "unspecified", "2 x 500 ml milk"),
                row("bread", null, "unspecified", null, "unspecified", "bread only"),
            ),
            "2 x 500 ml milk, no onion chips" to listOf(
                row("milk", null, "unspecified", null, "unspecified", "2 x 500 ml milk"),
                row("chips", null, "unspecified", null, "unspecified", "no onion chips", excluded = listOf("onion")),
            ),
            "2 x 500 ml milk, bread and eggs" to listOf(
                row("milk", null, "unspecified", null, "unspecified", "2 x 500 ml milk"),
                row("bread", null, "unspecified", null, "unspecified", "bread and eggs"),
                row("eggs", null, "unspecified", null, "unspecified", "bread and eggs"),
            ),
            "Amul: 2 x 500 ml milk" to listOf(
                row("milk", null, "unspecified", null, "unspecified", "2 x 500 ml milk"),
            ),
        )
        val failures = cases.mapNotNull { (instruction, rows) ->
            val result = runCatching { compositeV1Receipts(instruction, rows) }
            when {
                result.exceptionOrNull() != null -> "$instruction threw ${result.exceptionOrNull()?.javaClass?.simpleName}"
                !result.getOrNull().isNullOrEmpty() -> "$instruction unexpectedly received composite receipts"
                else -> null
            }
        }
        assertTrue("specialized gate must stay neutral for ordinary baskets: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test
    fun suffixQuoteCannotSplitTheCompositeClauseAtItsFinalCount() {
        val instruction = "2 x 500 ml milk packs paanch"
        val rows = listOf(
            row("milk", null, "unspecified", null, "unspecified", "2 x 500 ml milk"),
            row("milk", 5, "pack", "500", "ml", "packs paanch"),
        )
        val result = runCatching { compositeV1Receipts(instruction, rows) }
        assertTrue("the multiplier owner must also retain the clause-ending count",
            result.exceptionOrNull() is IntentNeedsReviewException)
    }

    @Test
    fun unsupportedMultiplierAndRepeatedPostfixCasesStayHeldThroughV1Preparation() {
        val cases = listOf(
            Triple("3 packs of 1 x 500 ml milk", 3, "milk" to "500"),
            Triple("3 packs of 100 x 5 ml milk", 3, "milk" to "5"),
            Triple("3 packs of do x 500 ml milk", 3, "milk" to "500"),
            Triple("2 x 500 ml milk packs 2", 2, "milk" to "500"),
        )
        cases.forEach { (source, count, model) ->
            val outcome = runCatching { prepare(source, model.first, count, "pack", model.second, "ml") }
            val failure = outcome.exceptionOrNull()
            // Reject before catalogue lookup, not just because a convenient
            // test SKU happens to have a different shape from the request.
            assertTrue("$source must require review before catalogue lookup",
                failure is IntentNeedsReviewException)
        }
    }

    @Test
    fun untypedParserAndProteinCompositionSourcesCannotBecomeCompositeOffers() {
        val source = "2 x 500 ml milk packs paanch"
        val untyped = com.example.beta.automation.ParsedItem(
            rawText = source,
            query = "milk",
            quantity = Quantity.Count(5),
        )
        assertNotNull(untyped.untypedSourceNumericIssue())
        val milkOffer = candidate("milk", "2 x 500 ml")
        assertTrue(resolveSwiggyCartQuantity(untyped, milkOffer) is SwiggyCartQuantityResolution.Held)

        val proteinSource = "2 x 30 g protein packs 4"
        val modelOffItems = prepareSwiggyMcpItems(proteinSource) { null }
        val proteinOffer = candidate("protein", "2 x 30 g")
        assertFalse("model-off composition source must not become actionable", modelOffItems.any { item ->
            val result = resolveSwiggyCartQuantity(
                assessSwiggyIntentQuantity(item, Recommendations(listOf(proteinOffer))), proteinOffer,
            )
            result !is SwiggyCartQuantityResolution.Held
        })

        val v1 = runCatching { prepare(proteinSource, "protein", 4, "pack", "30", "g") }
        if (v1.exceptionOrNull() != null) {
            assertTrue(v1.exceptionOrNull() is IntentNeedsReviewException)
        } else {
            val item = requireNotNull(v1.getOrNull())
            assertTrue("V1 composition source must remain held",
                resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(item, Recommendations(listOf(proteinOffer))), proteinOffer)
                    is SwiggyCartQuantityResolution.Held)
        }

        val compound = runCatching { prepare("3 packs of 2 x 500 ml milk sugar", "milk sugar", 3,
            "pack", "500", "ml") }
        assertTrue("multiple-product nominal must not receive an actionable receipt",
            compound.exceptionOrNull() is IntentNeedsReviewException)
    }

    private fun assertGuardRejectsLaunderedScope(instruction: String, plainSource: String, detachedQuote: String) {
        val rows = listOf(
            row("milk", 3, "pack", "500", "ml", plainSource),
            row("milk", null, "unspecified", null, "unspecified", detachedQuote),
        )
        val result = runCatching { compositeV1Receipts(instruction, rows) }
        assertTrue("detached context must not be laundered through a separately cited row: $instruction",
            result.exceptionOrNull() is IntentNeedsReviewException)
        val draft = runCatching {
            GroceryIntentDraft("grocery-intent-v1", true, instruction, rows, false).prepareForReview()
        }
        assertTrue("draft adapter must reject detached context despite exact plain quote: $instruction",
            draft.exceptionOrNull() is IntentNeedsReviewException)
    }

    private data class Positive(
        val source: String,
        val name: String,
        val count: Int,
        val quantityUnit: String,
        val packValue: String?,
        val packUnit: String,
        val shape: String,
    )

    private data class Negative(
        val label: String,
        val instruction: String,
        val name: String,
        val count: Int,
        val quantityUnit: String,
        val packValue: String?,
        val packUnit: String,
        val required: List<String> = emptyList(),
        val excluded: List<String> = emptyList(),
        val needsClarification: Boolean = false,
    )

    private fun prepare(
        instruction: String,
        name: String,
        count: Int,
        quantityUnit: String,
        packValue: String?,
        packUnit: String,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        needsClarification: Boolean = false,
        sourceText: String = instruction,
    ): com.example.beta.automation.ParsedItem = GroceryIntentDraft(
        contractVersion = "grocery-intent-v1",
        requiresReview = true,
        instruction = instruction,
        items = listOf(row(name, count, quantityUnit, packValue, packUnit, sourceText, required, excluded, needsClarification)),
        modelNeedsClarification = false,
    ).prepareForReview().items.single()

    private fun row(
        name: String,
        count: Int?,
        quantityUnit: String,
        packValue: String?,
        packUnit: String,
        sourceText: String,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        needsClarification: Boolean = false,
    ) = GroceryIntentItem(
        name = name,
        quantity = count?.let(::BigDecimal),
        quantityUnit = quantityUnit,
        packValue = packValue?.let(::BigDecimal),
        packUnit = packUnit,
        required = required,
        excluded = excluded,
        sourceText = sourceText,
        needsClarification = needsClarification,
    )

    private fun candidate(
        identity: String,
        shape: String,
        titlePrefix: String? = null,
    ): RecommendationCandidate {
        val spinId = "spin-$identity-${shape.hashCode()}"
        val skuId = "sku-$identity-${shape.hashCode()}"
        return RecommendationCandidate(
            spinId = spinId,
            skuId = skuId,
            label = listOfNotNull(titlePrefix, identity.replaceFirstChar(Char::uppercase), shape).joinToString(" "),
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = spinId,
                skuId = skuId,
                fields = CatalogueQuantityFields(packSize = shape),
            ),
        )
    }
}
