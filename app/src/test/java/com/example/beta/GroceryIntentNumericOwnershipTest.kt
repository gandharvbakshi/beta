package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.ParsedItem
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentNumericOwnershipTest {
    @Test fun equalDiscreteOuterAndInnerAmountsBindToDistinctOriginalRoles() {
        data class Case(
            val source: String,
            val name: String,
            val outerText: String,
            val innerText: String,
            val value: String,
            val container: String,
        )
        val cases = listOf(
            Case("3 packs of 3 pieces cookies", "cookies", "3", "3", "3", "pack"),
            Case("two packets of two pieces almonds", "almonds", "two", "two", "2", "packet"),
            Case("4 boxes of four pieces bottle brush", "bottle brush", "4", "four", "4", "box"),
        )

        cases.flatMap { case -> listOf("count", "pack", "piece").map { case to it } }.forEachIndexed { index, (case, unit) ->
            val item = numericItem(
                name = case.name, source = case.source,
                quantity = case.value, quantityUnit = unit, quantityText = case.outerText,
                packValue = case.value, packUnit = "piece", packText = case.innerText,
                packaging = case.container,
            )
            val row = numericRow(case.source, index, item)
            assertEquals(case.source, null, row.numericOwnershipIssue())
            val outer = requireNotNull(row.localQuantityEvidence())
            val inner = requireNotNull(row.localPackSizeEvidence())
            val outerStart = case.source.indexOf(case.outerText)
            val innerStart = case.source.indexOf(case.innerText, outerStart + case.outerText.length)
            assertTrue(case.source, outerStart >= 0 && innerStart > outerStart)
            assertEquals(case.source, case.source, outer.source)
            assertEquals(case.source, outerStart, outer.startUtf16)
            assertEquals(case.source, outerStart + case.outerText.length, outer.endUtf16)
            assertEquals(case.source, case.outerText, outer.atom)
            assertEquals(case.source, BigDecimal(case.value), outer.value)
            assertEquals(case.source, unit, outer.unit)
            assertEquals(case.source, case.source, inner.source)
            assertEquals(case.source, innerStart, inner.startUtf16)
            assertEquals(case.source, innerStart + case.innerText.length, inner.endUtf16)
            assertEquals(case.source, case.innerText, inner.atom)
            assertEquals(case.source, BigDecimal(case.value), inner.value)
            assertEquals(case.source, "piece", inner.unit)
        }
    }

    @Test fun v1ContainerContentsReceiptKeepsEqualAmountsAndDistinctOffsets() {
        val cases = listOf(
            Triple("3 packs of 3 pieces cookies", "3", "3"),
            Triple("two packets of two pieces almonds", "two", "two"),
        )
        cases.forEach { (source, outerText, innerText) ->
            val outerStart = source.indexOf(outerText)
            val innerStart = source.indexOf(innerText, outerStart + outerText.length)
            val item = GroceryIntentItem(
                name = source.substringAfter("pieces "),
                quantity = BigDecimal(if (outerText == "two") "2" else "3"), quantityUnit = "pack",
                packValue = BigDecimal(if (innerText == "two") "2" else "3"), packUnit = "piece",
                required = emptyList(), excluded = emptyList(), sourceText = source, needsClarification = false,
            )
            val receipt = requireNotNull(item.sourceContainerContentsReceipt())
            assertEquals(source, source, receipt.outer.source)
            assertEquals(source, outerStart, receipt.outer.startUtf16)
            assertEquals(source, outerStart + outerText.length, receipt.outer.endUtf16)
            assertEquals(source, outerText, receipt.outer.atom)
            assertEquals(source, source, receipt.inner.source)
            assertEquals(source, innerStart, receipt.inner.startUtf16)
            assertEquals(source, innerStart + innerText.length, receipt.inner.endUtf16)
            assertEquals(source, innerText, receipt.inner.atom)
            assertEquals(source, BigDecimal(if (outerText == "two") "2" else "3"), receipt.outer.value)
            assertEquals(source, receipt.outer.value, receipt.inner.value)
            assertEquals(source, "pack", receipt.outer.unit)
            assertEquals(source, "piece", receipt.inner.unit)
            assertEquals(source, if (source.contains("packets")) "packet" else "pack", receipt.container)
        }
    }

    @Test fun ambiguousOrMismatchedEqualAmountMappingsStayHeld() {
        val negatives = listOf(
            Triple("3 packs of 3 pieces cookies plus 4", "cookies", "3"),
            Triple("3 packs of 3 pieces cookies and 2 milk", "cookies milk", "3"),
            Triple("3 packs of 3 pieces cookies total", "cookies", "3"),
            Triple("3 packs of 3 pieces cookies per serving", "cookies", "3"),
            Triple("3 packs of 3 packs cookies", "cookies", "3"),
            Triple("2 packs of 2 pieces 5 Star chocolate", "chocolate", "2"),
            Triple("3 packs of 3 pieces BrandA cookies", "cookies", "3"),
        )
        negatives.forEach { (source, name, value) ->
            val row = numericRow(source, 0, numericItem(
                name = name, source = source,
                quantity = value, quantityUnit = "pack", quantityText = value,
                packValue = value, packUnit = "piece", packText = value,
                packaging = "pack",
            ))
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertTrue(source, row.numericOwnershipIssue() != null || prepared.items.isEmpty())
            assertTrue(source, prepared.items.isEmpty())
        }

        val wrongUnitSource = "3 kg cookies in 3 pieces"
        val wrongUnit = numericRow(wrongUnitSource, 0, numericItem(
            name = "cookies", source = wrongUnitSource,
            quantity = "3", quantityUnit = "piece", quantityText = "3",
            packValue = "3", packUnit = "piece", packText = "3", packaging = "pack",
        ))
        val wrongUnitPrepared = numericReview(wrongUnitSource, listOf(wrongUnit)).prepareForReview()
        assertTrue(wrongUnitSource, wrongUnit.numericOwnershipIssue() != null || wrongUnitPrepared.items.isEmpty())
        assertTrue(wrongUnitSource, wrongUnitPrepared.items.isEmpty())

        val nestedSource = "2 packs of 3 packs cookies"
        val nested = numericRow(nestedSource, 0, numericItem(
            name = "cookies", source = nestedSource, quantity = "2", quantityUnit = "pack", quantityText = "2",
            packValue = "3", packUnit = "piece", packText = "3", packaging = "pack",
        ))
        assertTrue(nested.numericOwnershipIssue() != null)
        assertTrue(numericReview(nestedSource, listOf(nested)).prepareForReview().items.isEmpty())

        for ((quantity, inner) in listOf("2" to "3", "3" to "4")) {
            val mismatchSource = "3 packs of 3 pieces cookies"
            val mismatch = numericRow(mismatchSource, 0, numericItem(
                name = "cookies", source = mismatchSource,
                quantity = quantity, quantityUnit = "pack", quantityText = "3",
                packValue = inner, packUnit = "piece", packText = "3", packaging = "pack",
            ))
            val prepared = numericReview(mismatchSource, listOf(mismatch)).prepareForReview()
            assertTrue(mismatchSource, mismatch.numericOwnershipIssue() != null || prepared.items.isEmpty())
            assertTrue(mismatchSource, prepared.items.isEmpty())
        }
    }

    @Test fun explicitContentsAmountsStayOuterCountOrHeldAcrossV2GroupsCorrectionsAndAdditives() {
        data class AmountCase(val source: String, val outer: Int, val inner: Int)
        val cases = listOf(
            AmountCase("3 packs of 3 pieces cookies", 3, 3),
            AmountCase("2 packs of 3 pieces cookies", 2, 3),
        )

        for ((caseIndex, case) in cases.withIndex()) for (unit in listOf("count", "pack", "piece")) {
            val ungroupedRow = numericRow(case.source, 0, numericItem(
                name = "cookies", source = case.source,
                quantity = case.outer.toString(), quantityUnit = unit, quantityText = case.outer.toString(),
                packValue = case.inner.toString(), packUnit = "piece", packText = case.inner.toString(),
                packaging = "pack",
            ))
            assertEquals("${case.source} must have locally bound amounts", null, ungroupedRow.numericOwnershipIssue())
            assertEquals(case.source, null, ungroupedRow.groundingIssue(instruction = case.source))
            val ungrouped = numericReview(case.source, listOf(ungroupedRow)).prepareForReview()
            assertEquals(case.source, 1, ungrouped.items.size)
            val sourceProved = ungrouped.items.single()
            assertEquals(case.source, Quantity.Count(case.outer), sourceProved.quantity)
            assertFalse(case.source, sourceProved.logicalPieceCount)
            assertTrue(case.source, sourceProved.validContainerContentsProof() != null)
            val typedOffer = syntheticContentsOffer(sourceProved, case.inner, "ungrouped-$caseIndex")
            val typedChecked = assessSwiggyIntentQuantity(sourceProved, Recommendations(candidates = listOf(typedOffer)))
            val typedResolution = resolveSwiggyCartQuantity(typedChecked, typedOffer)
            assertTrue("${case.source}: $typedResolution", typedResolution is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(case.source, case.outer, (typedResolution as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
            assertReviewedRetailProposal(typedChecked, typedOffer, case.outer)

            val header = "For breakfast, please add"
            val eggSource = "one egg"
            val instruction = "$header ${case.source}, $eggSource"
            val rows = listOf(
                numericRow(instruction, 0, numericItem(
                    name = "cookies", source = case.source,
                    quantity = case.outer.toString(), quantityUnit = unit, quantityText = case.outer.toString(),
                    packValue = case.inner.toString(), packUnit = "piece", packText = case.inner.toString(),
                    packaging = "pack",
                )),
                numericRow(instruction, 1, numericItem(
                    name = "egg", source = eggSource, quantity = "1", quantityUnit = "count", quantityText = "one",
                )),
            )
            val group = GroceryIntentReviewV2Group(
                status = "structurally_verified", amountStatus = "local_amounts_verified",
                source = numericQuote(instruction, instruction), header = numericQuote(instruction, header),
                memberIndices = listOf(0, 1),
                members = listOf(
                    GroceryIntentReviewV2GroupMember(0, BigDecimal(case.outer), unit, "local"),
                    GroceryIntentReviewV2GroupMember(1, BigDecimal.ONE, "count", "local"),
                ),
                packaging = null, total = null, each = null,
            )
            val grouped = numericReview(instruction, rows).copy(groups = listOf(group)).prepareForReview()
            assertOuterCountOrHeld(grouped, instruction, case.source, case.outer, case.inner, "group-$caseIndex-$unit")

            val operation = "make cookies four pieces"
            val correctedInstruction = "${case.source}; $operation"
            val correction = GroceryIntentReviewV2Correction(
                kind = "pack_size", source = numericQuote(correctedInstruction, operation),
                newAmount = numericQuote(correctedInstruction, "four"),
                oldValue = BigDecimal(case.inner), oldUnit = "piece",
                newValue = BigDecimal("4"), newUnit = "piece",
            )
            val correctedRow = numericRow(correctedInstruction, 0, numericItem(
                name = "cookies", source = case.source,
                quantity = case.outer.toString(), quantityUnit = unit, quantityText = case.outer.toString(),
                packValue = case.inner.toString(), packUnit = "piece", packText = case.inner.toString(),
                packaging = "pack",
            )).copy(corrections = listOf(correction))
            val corrected = numericReview(correctedInstruction, listOf(correctedRow)).prepareForReview()
            assertOuterCountOrHeld(corrected, correctedInstruction, case.source, case.outer, case.inner,
                "correction-$caseIndex-$unit")
        }

        for (inner in listOf(1, 3)) {
            val innerPhrase = if (inner == 1) "one piece" else "$inner pieces"
            val packText = if (inner == 1) "one" else inner.toString()
            val source = "another one pack of $innerPhrase cookies, one only"
            val row = numericRow(source, 0, numericItem(
                name = "cookies", source = source,
                quantity = "1", quantityUnit = "piece", quantityText = "one",
                packValue = inner.toString(), packUnit = "piece", packText = packText,
                packaging = "pack",
            ))
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertOuterCountOrHeld(prepared, source, source, 1, inner, "additive-inner-$inner")
        }
    }

    @Test fun postfixContainerCountsNeverBecomeLogicalPieces() {
        val failures = mutableListOf<String>()
        for (outer in listOf(2, 3, 6)) for (unit in listOf("count", "pack", "piece"))
            for (modifier in listOf("", "small ", "sealed ")) {
            val source = "cookies $outer ${modifier}packs of 3 pieces"
            val row = numericRow(source, 0, numericItem(
                name = "${modifier}cookies", source = source,
                quantity = outer.toString(), quantityUnit = unit, quantityText = outer.toString(),
                packValue = "3", packUnit = "piece", packText = "3", packaging = "pack",
            ))
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            val context = "postfix-$outer-$unit-${modifier.trim()}"
            val failure = runCatching { assertOuterCountOrHeld(prepared,
                source, source, outer, 3, context) }.exceptionOrNull()
            if (failure != null) {
                val resolution = prepared.items.singleOrNull()?.let { item ->
                    val offer = syntheticContentsOffer(item, 3, context)
                    resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(item, Recommendations(listOf(offer))), offer)
                }
                failures += "$context: ${failure.message}; resolution=$resolution"
            }
        }

        // An actual piece request may still divide by independently typed SKU
        // contents; a container denial must not blanket-ban all piece rows.
        val source = "12 pieces eggs"
        val row = numericRow(source, 0, numericItem(
            name = "eggs", source = source, quantity = "12", quantityUnit = "piece", quantityText = "12",
        ))
        val item = numericReview(source, listOf(row)).prepareForReview().items.single()
        val candidate = syntheticContentsOffer(item, 6, "explicit-pieces")
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(assessed, candidate))
        val measuredSource = "12 pieces eggs pack of 6 pieces"
        val measuredRow = numericRow(measuredSource, 0, numericItem(
            name = "eggs", source = measuredSource, quantity = "12", quantityUnit = "piece", quantityText = "12",
            packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
        ))
        val measuredItem = numericReview(measuredSource, listOf(measuredRow)).prepareForReview().items.single()
        val measuredOffer = syntheticContentsOffer(measuredItem, 6, "explicit-pieces-with-size")
        val measuredChecked = assessSwiggyIntentQuantity(measuredItem, Recommendations(listOf(measuredOffer)))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(measuredChecked, measuredOffer))
        assertTrue("Postfix role failures: $failures", failures.isEmpty())
    }

    @Test fun threeBoxesOfThreeCountCrackersIsNotAnExplicitPieceContentsReceipt() {
        val source = "3 boxes of 3 count crackers"
        val row = numericRow(source, 0, numericItem(
            name = "crackers", source = source,
            quantity = "3", quantityUnit = "pack", quantityText = "3",
            packValue = "3", packUnit = "piece", packText = "3", packaging = "box",
        ))
        val prepared = numericReview(source, listOf(row)).prepareForReview()
        assertTrue(source, row.numericOwnershipIssue() != null || prepared.items.isEmpty())
        assertTrue(source, prepared.items.isEmpty())
    }

    private fun assertOuterCountOrHeld(
        prepared: GroceryIntentPreparation,
        instruction: String,
        source: String,
        outer: Int,
        inner: Int,
        context: String,
    ) {
        val retained = prepared.items.singleOrNull { it.rawText == source }
        if (retained == null) {
            assertTrue(context, source in prepared.excludedText || source in prepared.unresolvedText ||
                prepared.recoverySnapshot?.toDisplay()?.originalInstruction == instruction)
            return
        }
        assertFalse("$context must not treat explicit outer contents as loose logical pieces", retained.logicalPieceCount)
        assertEquals("$context", Quantity.Count(outer), retained.quantity)

        val candidate = syntheticContentsOffer(retained, inner, context)
        val assessed = assessSwiggyIntentQuantity(retained, Recommendations(candidates = listOf(candidate)))
        when (val resolution = resolveSwiggyCartQuantity(assessed, candidate)) {
            is SwiggyCartQuantityResolution.Held -> Unit
            is SwiggyCartQuantityResolution.Exact -> assertEquals(context, outer, resolution.cartUnits)
            is SwiggyCartQuantityResolution.RetailProposal -> assertEquals(context, outer, resolution.cartUnits)
        }
    }

    private fun syntheticContentsOffer(item: ParsedItem, inner: Int, suffix: String): RecommendationCandidate {
        val spinId = "synthetic-container-$suffix"
        val skuId = "synthetic-container-sku-$suffix"
        return RecommendationCandidate(
            spinId = spinId, label = item.query, skuId = skuId,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1, source = "search_variation", spinId = spinId, skuId = skuId,
                fields = CatalogueQuantityFields(packSize = "$inner pieces"),
            ),
        )
    }

    @Test fun v1NumericConservationKeepsDescriptiveOneDistinctFromPurchaseAmount() {
        fun item(source: String, name: String, quantity: String?, unit: String) = GroceryIntentItem(
            name = name,
            quantity = quantity?.let(::BigDecimal),
            quantityUnit = unit,
            packValue = null,
            packUnit = "unspecified",
            required = emptyList(),
            excluded = emptyList(),
            sourceText = source,
            needsClarification = false,
        )

        val accepted = listOf(
            "one hand wash, the red one" to item("one hand wash, the red one", "red hand wash", "1", "count"),
            "one loaf of brown bread? The sliced one" to item("one loaf of brown bread? The sliced one", "sliced brown bread", "1", "piece"),
            "two hand wash, the red one" to item("two hand wash, the red one", "red hand wash", "2", "count"),
            "three apples, the green one" to item("three apples, the green one", "green apples", "3", "count"),
            "🛒 one hand wash, the red one" to item("🛒 one hand wash, the red one", "red hand wash", "1", "count"),
            "one 5 Star chocolate, the small one" to item("one 5 Star chocolate, the small one", "small 5 Star chocolate", "1", "count"),
        )
        accepted.forEach { (source, row) ->
            assertEquals(source, null, row.legacyNumericOwnershipIssue())
        }

        val denied = listOf(
            item("one hand wash, the red one", "hand wash", "1", "count"),
            item("one loaf of brown bread? The sliced one", "brown bread", "1", "piece"),
            item("one hand wash and one hand wash", "hand wash", "1", "count"),
            item("one hand wash 2", "hand wash", "1", "count"),
            item("one 5 Star chocolate, the small one", "small chocolate", "1", "count"),
            item("one hand wash, the red one, one hand wash", "red hand wash", "1", "count"),
            item("one g detergent, the blue one", "blue detergent", "1", "count"),
        )
        denied.forEach { row -> assertTrue(row.sourceText, row.legacyNumericOwnershipIssue() != null) }

        // Numeric conservation does not turn a descriptive pronoun into a stated purchase amount.
        val pronounOnly = item("hand wash, the red one", "red hand wash", null, "unspecified")
        assertEquals(null, pronounOnly.quantity)
        assertEquals(null, pronounOnly.legacyNumericOwnershipIssue())
    }

    @Test fun digitCountBesidePhysicalSizeNeedsExplicitContainerNotAdjacencyAlone() {
        for ((source, name, count, unit) in listOf(
            listOf("2 500ml milk", "milk", "2", "ml"),
            listOf("1 500g dal", "dal", "1", "g"),
        )) {
            val row = numericRow(source, 0, numericItem(name, source,
                quantity = count, quantityText = count, packValue = "500", packUnit = unit, packText = "500"))
            // Number ownership alone is valid; the independent final role gate
            // must reject the ambiguous multiplication before recommendations.
            assertEquals(source, null, row.numericOwnershipIssue())
            assertTrue(source, numericReview(source, listOf(row)).prepareForReview().items.isEmpty())
        }
        for ((source, atom) in listOf("two 500ml milk" to "two", "2 500ml bottles milk" to "2")) {
            val row = numericRow(source, 0, numericItem("milk", source,
                quantity = "2", quantityText = atom, packValue = "500", packUnit = "ml", packText = "500",
                packaging = if (source.contains("bottles")) "bottle" else null))
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, Quantity.Count(2), prepared.items.single().quantity)
            assertEquals(source, prepared.items.single().intentQuantityProvenance?.primaryText)
        }
    }

    @Test fun finalPackRelabelCannotTurnNoGroupPackagingIssueIntoAContainerReceipt() {
        val instruction = "two milk 500ml, two juice 500ml"
        val primary = "two milk 500ml"
        val row = numericRow(instruction, 0, numericItem("milk", primary,
            quantity = "2", quantityText = "two", packValue = "500", packUnit = "ml", packText = "500"))
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified", amountStatus = "local_amounts_verified",
            source = numericQuote(instruction, instruction), header = null, memberIndices = listOf(0, 1),
            members = listOf(0, 1).map { GroceryIntentReviewV2GroupMember(it, BigDecimal("2"), "count", "local") },
            packaging = null, total = null, each = null,
        )
        val final = GroceryIntentItem(name = "milk", quantity = BigDecimal("2"), quantityUnit = "pack",
            packValue = BigDecimal("500"), packUnit = "ml", required = emptyList(), excluded = emptyList(),
            sourceText = primary, needsClarification = false)
        assertEquals("physical_unit_size_ambiguous", row.physicalUnitSizeIssue(final, group))
        assertEquals(primary, row.evidence.primary.text)
        assertEquals("count", row.item.quantityUnit)
    }

    @Test fun groupEachNeedsLiteralPacketsToSupplyPhysicalPackRoleWithNullPackaging() {
        for ((eachText, unit) in listOf("two each" to "count", "two packets each" to "pack")) {
            val instruction = "milk 500ml, juice 500ml, $eachText"
            val rows = listOf("milk", "juice").mapIndexed { index, name ->
                numericRow(instruction, index, numericItem(name, "$name 500ml",
                    packValue = "500", packUnit = "ml", packText = "500"))
            }
            val group = GroceryIntentReviewV2Group(
                status = "structurally_verified", amountStatus = "each_verified",
                source = numericQuote(instruction, instruction), header = null, memberIndices = listOf(0, 1),
                members = listOf(0, 1).map { GroceryIntentReviewV2GroupMember(it, BigDecimal("2"), unit, "each") },
                packaging = null, total = null,
                each = GroceryIntentReviewV2GroupAmount(numericQuote(instruction, eachText), "two", BigDecimal("2"), unit),
            )
            rows.forEach { row ->
                val final = GroceryIntentItem(name = row.item.name, quantity = BigDecimal("2"), quantityUnit = "pack",
                    packValue = BigDecimal("500"), packUnit = "ml", required = emptyList(), excluded = emptyList(),
                    sourceText = row.evidence.primary.text, needsClarification = false)
                assertEquals(eachText, if (unit == "pack") null else "physical_unit_size_ambiguous",
                    row.physicalUnitSizeIssue(final, group))
            }
            val prepared = numericReview(instruction, rows).copy(groups = listOf(group)).prepareForReview()
            if (unit == "pack") {
                assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), prepared.items.map { it.quantity })
                assertEquals(listOf("milk 500ml", "juice 500ml"), prepared.items.map { it.intentQuantityProvenance?.primaryText })
                assertTrue(prepared.items.all { it.retailPackCount && it.query.contains("500 ml") })
                assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            } else {
                assertTrue(prepared.items.isEmpty())
                assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            }
        }
    }

    @Test fun discretePurchaseCountNeedsOriginalPhysicalUnitSizeRole() {
        val source = "two milk 500ml"
        for (unit in listOf("count", "pack")) for (status in listOf("matched", "unresolved")) {
            for (packaging in listOf(null, "bottle")) {
                val row = numericRow(source, 0, numericItem("milk", source,
                    quantity = "2", quantityUnit = unit, quantityText = "two",
                    packValue = "500", packUnit = "ml", packText = "500", packaging = packaging))
                    .copy(amountRoleStatus = status)
                val prepared = numericReview(source, listOf(row)).prepareForReview()
                assertTrue(prepared.items.isEmpty())
                assertEquals(source, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            }
        }
        val pieces = "two milk pieces 500ml"
        val pieceRow = numericRow(pieces, 0, numericItem("milk pieces", pieces,
            quantity = "2", quantityUnit = "piece", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500"))
        assertEquals("physical_piece_size_ambiguous", pieceRow.numericOwnershipIssue())
    }

    @Test fun adjacentSizeOriginalContainerAndTerminalEachKeepCountAndSizeSeparate() {
        val sources = listOf("two 500ml milk", "two bottles milk 500ml", "two 500ml bottles milk",
            "two milk 500ml each")
        for (source in sources) {
            val row = numericRow(source, 0, numericItem("milk", source,
                quantity = "2", quantityText = "two", packValue = "500", packUnit = "ml", packText = "500",
                packaging = if (source.contains("bottles")) "bottle" else null))
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            val parsed = prepared.items.single()
            assertEquals(source, Quantity.Count(2), parsed.quantity)
            assertEquals(source, parsed.intentQuantityProvenance?.primaryText)
            assertTrue(source, parsed.query.contains("500 ml"))
            assertTrue(source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun barePhysicalSizeKeepsExactSinglePackWhileExplicitTotalKeepsVolume() {
        val source = "500ml milk"
        val row = numericRow(source, 0, numericItem("milk", source,
            packValue = "500", packUnit = "ml", packText = "500"))
        assertEquals(null, row.numericOwnershipIssue())
        val prepared = numericReview(source, listOf(row)).prepareForReview()
        val parsed = prepared.items.single()
        assertEquals(Quantity.Default, parsed.quantity)
        assertEquals(IntentQuantityOrigin.UNSTATED, parsed.intentQuantityProvenance?.origin)
        assertEquals(source, parsed.intentQuantityProvenance?.primaryText)
        val displayOnly = RecommendationCandidate("size-display-only", "Milk 500 ml")
        val exact = boundPhysicalSizeCandidate("size-single", "Milk 500 ml", "500 ml")
        val rejected = listOf(RecommendationCandidate("size-double", "Milk 500 ml x 2"),
            RecommendationCandidate("size-pack", "Milk Pack of 2 500 ml"),
            RecommendationCandidate("size-other", "Milk 1 l"))
        val displayOnlyChecked = assessSwiggyIntentQuantity(parsed, Recommendations(listOf(displayOnly)))
        assertFalse(isSwiggyCandidateAllowed(displayOnlyChecked, displayOnly) &&
            isSwiggyCandidateCountCompatible(displayOnlyChecked, displayOnly))
        val checked = assessSwiggyIntentQuantity(parsed, Recommendations(listOf(exact) + rejected))
        assertTrue(isSwiggyCandidateAllowed(checked, exact))
        assertTrue(isSwiggyCandidateCountCompatible(checked, exact))
        assertEquals(1, swiggyRequestedCartQuantity(checked, exact))
        rejected.forEach { candidate ->
            assertFalse(candidate.label, isSwiggyCandidateAllowed(checked, candidate) &&
                isSwiggyCandidateCountCompatible(checked, candidate))
        }
        val total = numericReview(source, listOf(numericRow(source, 0, numericItem("milk", source,
            quantity = "500", quantityUnit = "ml", quantityText = "500")))).prepareForReview()
        assertEquals(Quantity.Volume(500), total.items.single().quantity)
        assertEquals(source, total.items.single().intentQuantityProvenance?.primaryText)
    }

    @Test fun physicalUnitSizeCannotBorrowAnotherProductOrNutritionMeasure() {
        for (source in listOf("two milk, juice 500ml each", "two milk and juice 500ml each",
            "two milk with juice 500ml each", "two milk; juice 500ml each",
            "two milk another 500ml bottle juice", "two milk 500ml total")) {
            val row = numericRow(source, 0, numericItem("milk juice", source, quantity = "2", quantityText = "two",
                packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle"))
            assertTrue(source, numericReview(source, listOf(row)).prepareForReview().items.isEmpty())
        }
        val source = "two milk 10g protein per serving"
        val row = numericRow(source, 0, numericItem("milk protein", source, quantity = "2", quantityText = "two",
            packValue = "10", packUnit = "g", packText = "10", packaging = "bottle"))
        assertTrue(row.numericOwnershipIssue() != null)
        assertTrue(numericReview(source, listOf(row)).prepareForReview().items.isEmpty())
    }

    @Test fun genericGroupEachCountCannotManufacturePhysicalEachSize() {
        val instruction = "milk 500ml, juice 500ml, two each"
        val rows = listOf("milk", "juice").mapIndexed { index, name ->
            numericRow(instruction, index, numericItem(name, "$name 500ml",
                packValue = "500", packUnit = "ml", packText = "500"))
        }
        rows.forEach { assertEquals(null, it.numericOwnershipIssue()) }
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified", amountStatus = "each_verified",
            source = numericQuote(instruction, instruction), header = null, memberIndices = listOf(0, 1),
            members = listOf(0, 1).map { GroceryIntentReviewV2GroupMember(it, BigDecimal("2"), "count", "each") },
            packaging = null, total = null,
            each = GroceryIntentReviewV2GroupAmount(numericQuote(instruction, "two each"), "two", BigDecimal("2"), "count"),
        )
        val prepared = numericReview(instruction, rows).copy(groups = listOf(group)).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.sharedGroupNotes.isEmpty())
        assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    @Test fun quantityCorrectionCannotCreateMissingPhysicalUnitSizeRole() {
        for ((primary, allowed) in listOf("one 500ml milk" to true, "one milk 500ml" to false)) {
            val instruction = "$primary; make milk two"
            val row = numericRow(instruction, 0, numericItem("milk", primary,
                quantity = "1", quantityText = "one", packValue = "500", packUnit = "ml", packText = "500"))
                .copy(corrections = listOf(GroceryIntentReviewV2Correction(
                    kind = "quantity", source = numericQuote(instruction, "make milk two"),
                    newAmount = numericQuote(instruction, "two"), oldValue = BigDecimal.ONE, oldUnit = "count",
                    newValue = BigDecimal("2"), newUnit = "count",
                )))
            val prepared = numericReview(instruction, listOf(row)).prepareForReview()
            if (allowed) {
                assertEquals(Quantity.Count(2), prepared.items.single().quantity)
                assertEquals(IntentQuantityOrigin.CORRECTION, prepared.items.single().intentQuantityProvenance?.origin)
                assertEquals(primary, prepared.items.single().intentQuantityProvenance?.primaryText)
                assertTrue(prepared.items.single().query.contains("500 ml"))
            } else {
                assertTrue(prepared.items.isEmpty())
                assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            }
        }
    }

    @Test fun compositionMeasurementsNeedOriginalPackRoleEvenWithForgedMatchedStatus() {
        val sources = listOf(
            "Brand 10g Coffee Protein Bar one",
            "Brand 10g Coffee Protein Bar no added sugar one",
            "one roasted coffee protein bar, the 10 gram, no added sugar",
            "one 10g protein bar", "one bar of 10g protein",
            "one bar of 10g protein each", "one bar of 10g protein with cocoa",
            "one bar of 10g protein, cocoa flavour", "one bar of 10g protein & cocoa",
            "one protein bar per 10g serving", "one protein bar PER 10g bar",
            "one protein bar per serving of 10g bar",
            "one protein bar total 10g each",
            "calories 10g one", "kcal 10g one",
        ) + listOf("\u00a0", "\u202f", "\u0085", "\u001c").map { "one protein bar per${it}10g bar" } +
            listOf("protein", "fibre", "fiber", "carbs", "carbohydrates", "fat", "sugar", "sodium", "calcium").map {
            "one bar with 10g $it"
        }
        for (source in sources) for (status in listOf("matched", "unresolved")) {
            val item = numericItem(source.replace(Regex("\\bone\\b"), "").trim(), source,
                quantity = "1", quantityText = "one", packValue = "10", packUnit = "g", packText = "10", packaging = "bar")
            val row = numericRow(source, 0, item).copy(amountRoleStatus = status)
            assertTrue(source, row.numericOwnershipIssue() != null)
            assertTrue(source, numericReview(source, listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun compositionLabelHoldDoesNotModifyAnIndependentPeer() {
        val source = "Brand 10g Coffee Protein Bar no added sugar one"
        val instruction = "$source; two eggs"
        val ambiguous = numericItem("Brand 10g Coffee Protein Bar no added sugar", source,
            quantity = "1", quantityText = "one", packValue = "10", packUnit = "g", packText = "10", packaging = "bar")
        val peer = numericItem("eggs", "two eggs", quantity = "2", quantityText = "two")
        val response = numericReview(instruction, listOf(numericRow(instruction, 0, ambiguous), numericRow(instruction, 1, peer)))
        val prepared = response.prepareForReview()
        assertEquals(listOf("eggs"), prepared.items.map { it.query })
        assertEquals(Quantity.Count(2), prepared.items.single().quantity)
        assertEquals(listOf(source), prepared.excludedText)
        assertEquals(instruction, response.instruction)
        assertEquals(ambiguous, response.readyRows[0].item)
    }

    @Test fun sharedCompositionHeaderCannotProveMemberPackMass() {
        for (explicit in listOf(false, true)) {
            val first = if (explicit) "Coffee 10g bar one" else "Coffee 10g one"
            val second = if (explicit) "Cocoa 20g bar one" else "Cocoa 20g one"
            val instruction = "protein bars: $first, $second"
            val group = GroceryIntentReviewV2Group(
                status = "structurally_verified", amountStatus = "local_amounts_verified",
                source = numericQuote(instruction, instruction), header = numericQuote(instruction, "protein bars:"),
                memberIndices = listOf(0, 1), members = listOf(
                    GroceryIntentReviewV2GroupMember(0, BigDecimal.ONE, "count", "local"),
                    GroceryIntentReviewV2GroupMember(1, BigDecimal.ONE, "count", "local"),
                ), packaging = "bar", total = null, each = null,
            )
            val rows = listOf(Triple("Coffee", first, "10"), Triple("Cocoa", second, "20")).mapIndexed { index, (name, source, size) ->
                numericRow(instruction, index, numericItem("$name protein bar", source, quantity = "1", quantityText = "one",
                    packValue = size, packUnit = "g", packText = size, packaging = "bar"))
            }
            val prepared = numericReview(instruction, rows).copy(groups = listOf(group)).prepareForReview()
            assertEquals("explicit=$explicit", if (explicit) 2 else 0, prepared.items.size)
        }
    }

    @Test fun compositionProductExplicitSizesAndPhysicalPurchaseTotalsSurvive() {
        val sources = listOf(
            "two packs of 50g protein powder", "two 50g bars",
            "two 50g bars with no added sugar", "two protein bars 50g each",
            "two protein bars, each bar weighs 50g",
            "two Brand 50g low-sugar coffee bars", "two Brand 50g no-added-sugar coffee bars",
        )
        for (source in sources) {
            val item = numericItem(source.removePrefix("two "), source, quantity = "2", quantityText = "two",
                packValue = "50", packUnit = "g", packText = "50")
            assertEquals(source, null, numericRow(source, 0, item).numericOwnershipIssue())
        }
        for (source in listOf("500g sugar", "500g protein powder", "500g sugar-free powder")) {
            val item = numericItem(source.removePrefix("500g "), source, quantity = "500", quantityUnit = "g", quantityText = "500")
            assertEquals(source, null, numericRow(source, 0, item).numericOwnershipIssue())
        }
    }

    @Test fun physicalPieceSizeCannotBorrowASecondPackage() {
        for (atom in listOf("100", "hundred")) for (unit in listOf("piece", "count", "pack")) {
            for (determiner in listOf("another", "additional", "separate")) {
                for (size in listOf("pack of $atom grams", "$atom gram pack")) {
                    val source = "two ginger pieces $determiner $size"
                    val item = numericItem("ginger pieces $determiner", source, quantity = "2", quantityUnit = unit,
                        quantityText = "two", packValue = "100", packUnit = "g", packText = atom, packaging = "pack")
                    assertBlocked(source, item)
                    assertEquals(source, "physical_piece_size_ambiguous", numericRow(source, 0, item).numericOwnershipIssue())
                }
            }
        }
    }

    @Test fun retailMarkerCannotOverrideAnExplicitPhysicalTotalRole() {
        for (marker in listOf("total", "altogether", "kul")) for (suffix in listOf("pack", "each")) {
            val source = "two ginger pieces $marker hundred gram $suffix"
            val item = numericItem("ginger pieces $marker", source, quantity = "2", quantityUnit = "piece",
                quantityText = "two", packValue = "100", packUnit = "g", packText = "hundred",
                packaging = "pack".takeIf { suffix == "pack" })
            assertBlocked(source, item)
            assertEquals(source, "physical_piece_size_ambiguous", numericRow(source, 0, item).numericOwnershipIssue())
        }
    }

    @Test fun pieceSizeEachOrRetailContainerCannotBorrowADifferentProduct() {
        for (separator in listOf(".", "!", "?", ";", ",", " and", " with")) {
            val source = "two ginger pieces$separator turmeric hundred grams each"
            assertBlocked(source, numericItem("ginger pieces turmeric", source, quantity = "2", quantityUnit = "piece",
                quantityText = "two", packValue = "100", packUnit = "g", packText = "hundred"))
        }
        val source = "two ginger pieces pack of hundred grams turmeric"
        assertBlocked(source, numericItem("ginger pieces turmeric", source, quantity = "2", quantityUnit = "piece",
            quantityText = "two", packValue = "100", packUnit = "g", packText = "hundred", packaging = "pack"))
    }

    @Test fun physicalPieceCountNeedsSourceProofOfEachNotAnUnresolvedRoleLabel() {
        for (atom in listOf("100", "hundred")) for (unit in listOf("piece", "count", "pack")) {
            val source = "two ginger $atom gram pieces"
            val item = numericItem("ginger pieces", source, quantity = "2", quantityUnit = unit,
                quantityText = "two", packValue = "100", packUnit = "g", packText = atom)
            val row = numericRow(source, 0, item)
            assertEquals("physical_piece_size_ambiguous", row.numericOwnershipIssue())
            assertBlocked(source, item)
        }
        for ((atom, value) in mapOf("100" to "100", "hundred" to "100", "one hundred and twenty five" to "125")) {
            val source = "two ginger pieces $atom grams each"
            val item = numericItem("ginger pieces", source, quantity = "2", quantityUnit = "piece",
                quantityText = "two", packValue = value, packUnit = "g", packText = atom)
            val row = numericRow(source, 0, item)
            assertEquals(null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test fun finalHeadlessGroupEachCannotPromoteMemberWeightsToPerPieceSizes() {
        val instruction = "ginger pieces hundred grams, turmeric pieces hundred grams, two pieces each"
        val ginger = "ginger pieces hundred grams"
        val turmeric = "turmeric pieces hundred grams"
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified",
            amountStatus = "each_verified",
            source = numericQuote(instruction, instruction),
            header = null,
            memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal("2"), "piece", "each"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal("2"), "piece", "each"),
            ),
            packaging = null,
            total = null,
            each = GroceryIntentReviewV2GroupAmount(
                numericQuote(instruction, "two pieces each"), "two", BigDecimal("2"), "piece",
            ),
        )
        val response = numericReview(instruction, listOf(
            numericRow(instruction, 0, numericItem(
                "ginger pieces", ginger, packValue = "100", packUnit = "g", packText = "hundred",
            )),
            numericRow(instruction, 1, numericItem(
                "turmeric pieces", turmeric, packValue = "100", packUnit = "g", packText = "hundred",
            )),
        )).copy(groups = listOf(group)).prepareForReview()

        assertTrue(response.items.isEmpty())
        assertTrue(response.sharedGroupNotes.isEmpty())
        assertTrue(response.excludedText.any { it.contains(ginger) })
        assertTrue(response.excludedText.any { it.contains(turmeric) })
    }

    @Test fun singularPcCountRelabelsDoNotProvePieceSizeButKeepPcInIdentity() {
        val cases = listOf(
            Triple("two ginger hundred gram pc", "ginger pc", "hundred"),
            Triple("two pc ginger hundred grams", "pc ginger", "hundred"),
            Triple("two ginger 100 gram pc", "ginger pc", "100"),
            Triple("two pc ginger 100 grams", "pc ginger", "100"),
        )
        cases.forEach { (source, name, sizeText) ->
            val item = numericItem(
                name = name, source = source, quantity = "2", quantityUnit = "count", quantityText = "two",
                packValue = "100", packUnit = "g", packText = sizeText,
            )
            assertTrue("pc stays in product identity: $source", item.name.contains("pc", ignoreCase = true))
            assertBlocked(source, item)
        }
    }

    @Test fun explicitPieceWeightEachSurvivesWithoutOrWithTerminalPunctuation() {
        val cases = listOf(
            Triple("two ginger pieces hundred grams each.", "hundred", "100"),
            Triple("two ginger pieces 100 grams each.", "100", "100"),
            Triple("two ginger pieces one hundred and twenty five grams each.", "one hundred and twenty five", "125"),
        )
        cases.forEach { (source, packText, expectedPack) ->
            val item = numericItem(
                name = "ginger pieces", source = source, quantity = "2", quantityUnit = "piece",
                quantityText = "two", packValue = expectedPack, packUnit = "g",
                packText = packText,
            )
            val row = numericRow(source, 0, item)
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, Quantity.Count(2), prepared.items.single().quantity)
            assertTrue(source, prepared.items.single().query.contains("$expectedPack g"))
            assertTrue(source, prepared.items.single().strictMatchPhrase!!.contains("$expectedPack g"))
            assertTrue(source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun explicitGroupPhysicalEachSourceRemainsAvailableForReview() {
        val instruction = "ginger pieces, turmeric pieces, 100 g each"
        val eachSource = numericQuote(instruction, "100 g each")
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified",
            amountStatus = "each_verified",
            source = numericQuote(instruction, instruction),
            header = null,
            memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal("100"), "g", "each"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal("100"), "g", "each"),
            ),
            packaging = null,
            total = null,
            each = GroceryIntentReviewV2GroupAmount(eachSource, "100", BigDecimal("100"), "g"),
        )
        val prepared = numericReview(instruction, listOf(
            numericRow(instruction, 0, numericItem("ginger pieces", "ginger pieces")),
            numericRow(instruction, 1, numericItem("turmeric pieces", "turmeric pieces")),
        )).copy(groups = listOf(group)).prepareForReview()

        assertEquals(listOf("ginger pieces", "turmeric pieces"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Weight(100), Quantity.Weight(100)), prepared.items.map { it.quantity })
        assertEquals(listOf("100 g each"), prepared.sharedGroupNotes)
        assertTrue(prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
    }

    @Test fun wholeHundredsRetainExactPhysicalAmountAndSourceOwnership() {
        mapOf("a hundred" to "100", "five hundred" to "500", "one hundred and twenty five" to "125",
            "sau" to "100", "do sau" to "200", "dedh sau" to "150", "dhai sau" to "250").forEach { (atom, amount) ->
            val source = "$atom grams rice"
            val item = numericItem("rice", source, quantity = amount, quantityUnit = "g", quantityText = atom)
            val row = numericRow(source, 0, item)
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, prepared.items.single().rawText)
            assertEquals(source, Quantity.Weight(amount.toInt()), prepared.items.single().quantity)
            assertEquals(atom, prepared.items.single().intentQuantityProvenance?.local?.atom)
            assertTrue(prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun closedKejiAliasPreservesPhysicalPurchaseTotalAndKgControls() {
        val cases = listOf(
            Triple("one keji rice", "one", 1000),
            Triple("aadha keji rice", "aadha", 500),
            Triple("one KEJI rice", "one", 1000),
            Triple("one kg rice", "one", 1000),
            Triple("one kilo rice", "one", 1000),
        )
        cases.forEach { (source, amountText, expectedGrams) ->
            val item = numericItem(
                name = "rice", source = source, quantity = if (expectedGrams == 500) "0.5" else "1",
                quantityUnit = "kg", quantityText = amountText,
            )
            val row = numericRow(source, 0, item)
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            val parsed = prepared.items.single()
            assertEquals(source, parsed.rawText)
            assertEquals(source, Quantity.Weight(expectedGrams), parsed.quantity)
            assertFalse(source, parsed.retailPackCount)
            assertTrue(source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun kejiPackSizeKeepsPurchasePackCountSeparateFromPhysicalSize() {
        val cases = listOf(
            "two half-keji packets of rice" to ("two" to 2),
            "two half-kg packets of rice" to ("two" to 2),
            "two half-kilo packets of rice" to ("two" to 2),
            "one rice packet of half keji" to ("one" to 1),
            "one rice packet of half kg" to ("one" to 1),
            "one rice packet of half kilo" to ("one" to 1),
        )
        cases.forEach { (source, count) ->
            val item = numericItem(
                name = "rice", source = source, quantity = count.second.toString(),
                quantityUnit = "pack", quantityText = count.first,
                packValue = "0.5", packUnit = "kg", packText = "half", packaging = "packet",
            )
            val row = numericRow(source, 0, item)
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            val parsed = prepared.items.single()
            assertEquals(source, Quantity.Count(count.second), parsed.quantity)
            assertTrue(source, parsed.retailPackCount)
            assertTrue(source, parsed.query.contains("500 g"))
            assertTrue(source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun kejiCannotChangeAmountRoleValueOrUnitOrBorrowPartialNumber() {
        listOf(
            "keji" to numericItem("rice", "one keji rice", quantity = "1", quantityUnit = "count", quantityText = "one"),
            "pack" to numericItem("rice", "one keji rice", quantity = "1", quantityUnit = "pack", quantityText = "one"),
            "g" to numericItem("rice", "one keji rice", quantity = "1", quantityUnit = "g", quantityText = "one"),
            "wrong half" to numericItem("rice", "aadha keji rice", quantity = "1", quantityUnit = "kg", quantityText = "aadha"),
            "cropped fraction" to numericItem("rice", "half a keji rice", quantity = "0.5", quantityUnit = "kg", quantityText = "half"),
            "partial mixed number" to numericItem("rice", "one and a half keji rice", quantity = "0.5", quantityUnit = "kg", quantityText = "half"),
            "piece size without each" to numericItem(
                "ginger pieces", "two ginger pieces half keji", quantity = "2", quantityUnit = "piece",
                quantityText = "two", packValue = "0.5", packUnit = "kg", packText = "half",
            ),
        ).forEach { (_, item) -> assertBlocked(item.sourceText, item) }

        listOf("kejir", "kejis", "keji's", "keji’s", "kej\u0131", "\u212Aeji", "keji\u0301").forEach { spelling ->
            val source = "one $spelling rice"
            assertBlocked(source, numericItem(
                name = "rice", source = source, quantity = "1", quantityUnit = "kg", quantityText = "one",
            ))
            val packSource = "one rice packet of half $spelling"
            assertBlocked(packSource, numericItem(
                name = "rice", source = packSource, quantity = "1", quantityUnit = "pack", quantityText = "one",
                packValue = "0.5", packUnit = "kg", packText = "half", packaging = "packet",
            ))
        }
    }

    @Test fun kejiPhysicalAmountAndPropertyObligationSurviveTogether() {
        val clause = "rice should be organic"
        listOf("kg", "keji").forEach { unit ->
            val source = "one $unit rice; $clause"
            val item = numericItem("rice", "one $unit rice", quantity = "1", quantityUnit = "kg",
                quantityText = "one", required = listOf("organic"))
            val support = GroceryIntentReviewV2SupportText("modifier", clause)
            val row = numericRow(source, 0, item, supporting = listOf(support))
            val prepared = numericReview(source, listOf(row)).prepareForReview(allowPropertyObligations = true)

            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, Quantity.Weight(1000), prepared.items.single().quantity)
            assertTrue(source, prepared.items.single().query.contains("organic"))
            assertEquals(source, listOf(clause), prepared.sharedGroupNotes)
            assertTrue(source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun explicitEachAllowsASourceBoundKejiPieceSize() {
        val cases = listOf("two ginger pieces half keji each", "two ginger pieces half kg each")
        cases.forEach { source ->
            val row = numericRow(source, 0, numericItem(
                name = "ginger pieces", source = source, quantity = "2", quantityUnit = "piece",
                quantityText = "two", packValue = "0.5", packUnit = "kg", packText = "half",
            ))
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, Quantity.Count(2), prepared.items.single().quantity)
            assertTrue(source, prepared.items.single().query.contains("500 g"))
            assertTrue(source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun partialHundredsAndCountRelabelsCannotReachReview() {
        listOf(
            Triple("five hundred grams rice", "five", "5"),
            Triple("five hundred grams rice", "hundred", "100"),
            Triple("one hundred twenty grams rice", "one hundred", "100"),
            Triple("one hundred and twenty grams rice", "twenty", "20"),
            Triple("do sau grams rice", "do", "2"),
            Triple("do sau grams rice", "sau", "100"),
        ).forEach { (source, atom, amount) ->
            assertBlocked(source, numericItem("rice", source, quantity = amount, quantityUnit = "g", quantityText = atom))
        }
        val source = "five hundred grams rice"
        assertBlocked(source, numericItem("rice", source, quantity = "500", quantityUnit = "count", quantityText = "five hundred"))
    }

    @Test fun completeLiteralProductCompoundsDoNotBecomePurchaseNumbers() {
        listOf("pav bhaji masala", "paav bhaji masala", "paneer do pyaza", "half and half cream").forEach { name ->
            val source = "1 packet $name 100 g"
            val item = numericItem(name, source, quantity = "1", quantityUnit = "pack", quantityText = "1",
                packValue = "100", packUnit = "g", packText = "100", packaging = "packet")
            val prepared = numericReview(source, listOf(numericRow(source, 0, item))).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(Quantity.Count(1), prepared.items.single().quantity)
            assertEquals("1", prepared.items.single().intentQuantityProvenance?.local?.atom)
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
        }
        val source = "pav bhaji masala"
        val prepared = numericReview(source, listOf(numericRow(source, 0, numericItem(source, source)))).prepareForReview()
        assertEquals(Quantity.Default, prepared.items.single().quantity)
        assertEquals(IntentQuantityOrigin.UNSTATED, prepared.items.single().intentQuantityProvenance?.origin)
    }

    @Test fun compoundExemptionDoesNotHideCountsOrPermitPartialNames() {
        val source = "1 packet pav bhaji masala 100 g"
        assertBlocked(source, numericItem("bhaji masala", source, quantity = "1", quantityUnit = "pack",
            quantityText = "1", packValue = "100", packUnit = "g", packaging = "packet"))
        listOf("pav milk", "two pav bhaji masala", "pav bhaji masala 3", "pav bhajix masala").forEach { text ->
            assertBlocked(text, numericItem(text, text))
        }
        val dish = "paneer do pyaza"
        assertBlocked(dish, numericItem(dish, dish, quantity = "2", quantityText = "do"))
        val fraction = "pav bhaji masala"
        assertBlocked(fraction, numericItem(fraction, fraction, quantity = "0.25", quantityUnit = "kg", quantityText = "pav"))
    }

    @Test fun descriptiveOneWithoutPurchaseCountRemainsExplicitlyUnstated() {
        listOf(
            "the ExampleBrand lemon hand wash one" to "ExampleBrand lemon hand wash",
            "hand wash, the red one" to "red hand wash",
        ).forEach { (source, name) ->
            val result = numericReview(source, listOf(numericRow(source, 0, numericItem(name, source)))).prepareForReview()
            val parsed = result.items.single()
            assertEquals(Quantity.Default, parsed.quantity)
            assertEquals(IntentQuantityOrigin.UNSTATED, parsed.intentQuantityProvenance?.origin)
            assertEquals(null, parsed.intentQuantityProvenance?.local)
            assertTrue(result.excludedText.isEmpty())
        }
    }

    @Test fun descriptiveOneKeepsGenuineCountAndPhysicalPackSize() {
        val counted = "one hand wash, the red one"
        val countItem = numericItem("red hand wash", counted, quantity = "1", quantityText = "one")
        val count = numericReview(counted, listOf(numericRow(counted, 0, countItem))).prepareForReview().items.single()
        assertEquals(Quantity.Count(1), count.quantity)
        assertEquals(IntentQuantityOrigin.LOCAL, count.intentQuantityProvenance?.origin)
        assertEquals("one", count.intentQuantityProvenance?.local?.atom)
        val packed = "500 ml hand wash, the red one"
        val packItem = numericItem("red hand wash", packed, packValue = "500", packText = "500")
        val pack = numericReview(packed, listOf(numericRow(packed, 0, packItem))).prepareForReview().items.single()
        assertEquals(Quantity.Default, pack.quantity)
        assertEquals(IntentQuantityOrigin.UNSTATED, pack.intentQuantityProvenance?.origin)
        assertEquals(null, pack.intentQuantityProvenance?.local)

        val prefixedPackInstruction = "Beta, thoda cart mein daal do na. $packed"
        val prefixedPackReview = numericReview(prefixedPackInstruction, listOf(
            numericRow(prefixedPackInstruction, 0, packItem),
        )).prepareForReview()
        assertEquals(listOf(Quantity.Default), prefixedPackReview.items.map { it.quantity })
        assertEquals(IntentQuantityOrigin.UNSTATED, prefixedPackReview.items.single().intentQuantityProvenance?.origin)
        assertEquals(null, prefixedPackReview.items.single().intentQuantityProvenance?.local)
        assertTrue(prefixedPackReview.excludedText.isEmpty())
        assertTrue(prefixedPackReview.unresolvedText.isEmpty())
    }

    @Test fun descriptiveOneCannotHideOmittedAmountsOrInventAPrefixQuantity() {
        listOf(
            "the ExampleBrand lemon hand wash one" to "lemon hand wash",
            "two hand wash, the red one" to "red hand wash",
            "hand wash, the two pack one" to "two pack hand wash",
            "hand wash, the red one and blue one" to "red blue hand wash",
            "hand wash, the red one with more soap" to "red hand wash",
            "zero sugar cola, the red one" to "zero sugar red cola",
        ).forEach { (source, name) -> assertBlocked(source, numericItem(name, source)) }
        val source = "the ExampleBrand lemon hand wash one"
        assertBlocked(source, numericItem("ExampleBrand lemon hand wash", source, quantity = "1", quantityText = "one"))
        val instruction = "Beta, thoda cart mein daal do na. $source"
        val prepared = numericReview(instruction, listOf(numericRow(instruction, 0,
            numericItem("ExampleBrand lemon hand wash", source)))).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Default, prepared.items.single().quantity)
        assertEquals(IntentQuantityOrigin.UNSTATED, prepared.items.single().intentQuantityProvenance?.origin)
        assertEquals(null, prepared.items.single().intentQuantityProvenance?.local)
        assertTrue(prepared.excludedText.isEmpty())
        assertTrue(prepared.unresolvedText.isEmpty())

        val correctionSupportInstruction = "Beta, thoda cart mein daal do na. milk; make that two"
        val correctionSupport = numericReview(correctionSupportInstruction, listOf(numericRow(
            correctionSupportInstruction, 0, numericItem("milk", "milk"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "make that two")),
        ))).prepareForReview()
        assertTrue(correctionSupport.items.isEmpty())
        assertTrue(correctionSupport.unresolvedText.isNotEmpty())
    }

    @Test fun unownedTotalsAndExtraCountsBlockRowsWithNonNullPurchaseAmounts() {
        listOf(
            "two 500 ml milk bottles, 2 litres total",
            "two 500 ml milk bottles, 1 litre total",
        ).forEach { source ->
            assertBlocked(source, numericItem(
                name = "milk", source = source, quantity = "2", quantityUnit = "pack", quantityText = "two",
                packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
            ))
        }

        val extraCount = "two milk bottles, milk 5 packets please"
        assertBlocked(extraCount, numericItem(
            name = "milk", source = extraCount, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packaging = "bottle",
        ))

        val extraSize = "two 500 ml bottles milk, 750 ml each"
        assertBlocked(extraSize, numericItem(
            name = "milk", source = extraSize, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        ))
    }

    @Test fun numericAmountInAnchoredSupportCannotBeHiddenByAValidPrimary() {
        val instruction = "one BrandA milk; milk 5 packets please"
        val milk = numericItem(
            name = "BrandA milk", source = "one BrandA milk", quantity = "1", quantityUnit = "count",
            quantityText = "one",
        )
        val result = numericReview(instruction, listOf(numericRow(
            instruction, 0, milk,
            listOf(GroceryIntentReviewV2SupportText("modifier", "milk 5 packets please")),
        ))).prepareForReview()

        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.isNotEmpty())
        assertTrue(result.excludedText.joinToString(" ").contains("5 packets"))
    }

    @Test fun completeAmountBindingsAndUnstatedQuantityRemainUsable() {
        val source = "two 500 ml milk bottles"
        val simple = numericReview(source, listOf(numericRow(source, 0, numericItem(
            name = "milk", source = source, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        )))).prepareForReview()
        assertEquals(Quantity.Count(2), simple.items.single().quantity)
        assertTrue(simple.items.single().retailPackCount)

        val repeatedSize = "two 500 ml bottles milk, 500 ml each"
        val repeated = numericReview(repeatedSize, listOf(numericRow(repeatedSize, 0, numericItem(
            name = "milk", source = repeatedSize, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        )))).prepareForReview()
        assertEquals(Quantity.Count(2), repeated.items.single().quantity)
        assertTrue(repeated.items.single().query.contains("500 ml"))

        val distinctRoles = "one bottle of one litre milk"
        val bottle = numericReview(distinctRoles, listOf(numericRow(distinctRoles, 0, numericItem(
            name = "milk", source = distinctRoles, quantity = "1", quantityUnit = "pack", quantityText = "one",
            packValue = "1", packUnit = "l", packText = "one", packaging = "bottle",
        )))).prepareForReview()
        assertEquals(Quantity.Count(1), bottle.items.single().quantity)
        assertTrue(bottle.items.single().query.contains("1000 ml"))

        val unstated = "milk"
        val unstatedRow = numericRow(unstated, 0, numericItem(name = "milk", source = unstated))
        val oneMilk = numericReview(unstated, listOf(unstatedRow)).prepareForReview()
        assertEquals(Quantity.Default, oneMilk.items.single().quantity)
        assertTrue(oneMilk.excludedText.isEmpty())
        assertEquals("not_stated", unstatedRow.quantityOrigin)
    }

    @Test fun newlyRecognizedNumericWordsCannotDefaultToOneOrHideInSupport() {
        listOf(
            "gyarah eggs", "bees eggs", "couple of eggs", "pair of eggs",
            "तीन eggs", "ग्यारह eggs", "आधा milk",
        ).forEach { source ->
            val result = numericReview(source, listOf(numericRow(source, 0, numericItem(
                name = if (source.endsWith("milk")) "milk" else "eggs", source = source,
            )))).prepareForReview()
            assertTrue(source, result.items.isEmpty())
            assertTrue(source, result.excludedText.contains(source))
            assertTrue(source, result.items.none { it.quantity == Quantity.Count(1) })
        }

        val extraCount = "one milk, gyarah packets"
        assertBlocked(extraCount, numericItem(
            name = "milk", source = extraCount, quantity = "1", quantityUnit = "count", quantityText = "one",
        ))

        val supportInstruction = "one milk; milk bees packets"
        val supportedItem = numericItem(
            name = "milk", source = "one milk", quantity = "1", quantityUnit = "count", quantityText = "one",
        )
        val supportResult = numericReview(supportInstruction, listOf(numericRow(
            supportInstruction, 0, supportedItem,
            listOf(GroceryIntentReviewV2SupportText("modifier", "milk bees packets")),
        ))).prepareForReview()
        assertTrue(supportResult.items.isEmpty())
        assertTrue(supportResult.excludedText.isNotEmpty())
    }

    @Test fun sharedNumericAtomDetectorHandlesWordsAndSaathPostpositionPrecisely() {
        listOf("few eggs", "couple eggs", "bees eggs", "तीन eggs", "二 eggs", "हज़ार eggs").forEach {
            assertTrue(it, groceryIntentContainsNumericAtom(it))
        }
        assertFalse(groceryIntentContainsNumericAtom("masala ke saath"))
        assertTrue(groceryIntentContainsNumericAtom("saath eggs"))
        assertTrue(groceryIntentContainsNumericAtom("masala ke saath, saath eggs"))
    }

    @Test fun saathPostpositionStaysNeutralButASecondNumericSaathBlocks() {
        val spokenAmount = "two noodles masala ke saath"
        val amountResult = numericReview(spokenAmount, listOf(numericRow(spokenAmount, 0, numericItem(
            name = "noodles masala", source = spokenAmount,
            quantity = "2", quantityUnit = "count", quantityText = "two",
        )))).prepareForReview()
        assertEquals(1, amountResult.items.size)
        assertEquals(Quantity.Count(2), amountResult.items.single().quantity)

        val unstated = "noodles masala ke saath"
        val unstatedResult = numericReview(unstated, listOf(numericRow(unstated, 0, numericItem(
            name = "noodles masala", source = unstated,
        )))).prepareForReview()
        assertEquals(1, unstatedResult.items.size)
        assertEquals(Quantity.Default, unstatedResult.items.single().quantity)

        val extraNumeric = "two noodles masala ke saath, saath eggs"
        assertBlocked(extraNumeric, numericItem(
            name = "noodles masala", source = extraNumeric,
            quantity = "2", quantityUnit = "count", quantityText = "two",
        ))
    }

    @Test fun literalNumericDescriptorsRemainGroundedWithoutBecomingCounts() {
        val cases = listOf(
            Triple("one BrandA alkaline battery 1.5 volt", "BrandA alkaline battery 1.5 volt", "one"),
            Triple("2 packets A2 milk", "A2 milk", "2"),
            Triple("one 9 watt bulb", "9 watt bulb", "one"),
        )
        cases.forEachIndexed { index, (source, name, countText) ->
            val qUnit = if (countText == "2") "pack" else "count"
            val form = if (countText == "2") "packet" else null
            val result = numericReview(source, listOf(numericRow(source, index, numericItem(
                name = name, source = source, quantity = if (countText == "2") "2" else "1",
                quantityUnit = qUnit, quantityText = countText, packaging = form,
            )))).prepareForReview()
            assertEquals(source, 1, result.items.size)
            assertTrue(source, result.items.single().query.contains(name))
        }

        val unknownBrand = "5 Star chocolate, 2 packs"
        val droppedDescriptor = numericReview(unknownBrand, listOf(numericRow(unknownBrand, 0, numericItem(
            name = "Star chocolate", source = unknownBrand, quantity = "2", quantityUnit = "pack",
            quantityText = "2", packaging = "pack",
        )))).prepareForReview()
        assertTrue(droppedDescriptor.items.isEmpty())
        assertEquals(listOf(unknownBrand), droppedDescriptor.excludedText)
    }

    @Test fun descriptorAndNutritionNumbersNeedLiteralNameOwnership() {
        val omittedDescriptors = listOf(
            Triple("one BrandA alkaline battery 1.5 volt", "BrandA alkaline battery", "one"),
            Triple("one 9 watt bulb", "bulb", "one"),
        )
        omittedDescriptors.forEach { (source, name, countText) ->
            assertBlocked(source, numericItem(
                name = name, source = source, quantity = "1", quantityUnit = "count", quantityText = countText,
            ))
        }

        val boundDescriptorDigit = "9 watt bulb"
        assertBlocked(boundDescriptorDigit, numericItem(
            name = "bulb", source = boundDescriptorDigit, quantity = "9", quantityUnit = "count", quantityText = "9",
        ))
        val extraDigit = "one 9 watt bulb 9"
        assertBlocked(extraDigit, numericItem(
            name = "9 watt bulb", source = extraDigit, quantity = "1", quantityUnit = "count", quantityText = "one",
        ))

        val nutrition = "one 20 g protein per serving bar"
        val nutritionResult = numericReview(nutrition, listOf(numericRow(nutrition, 0, numericItem(
            name = "20 g protein per serving bar", source = nutrition,
            quantity = "1", quantityUnit = "count", quantityText = "one",
            packaging = "bar",
        )))).prepareForReview()
        assertEquals(1, nutritionResult.items.size)
        assertTrue(nutritionResult.items.single().query.contains("20 g protein per serving bar"))
        val item = nutritionResult.items.single()
        val exact = RecommendationCandidate(spinId = "nutrition-exact", label = "20 g protein per serving bar 50g")
        assertTrue(isSwiggyCandidateAllowed(item, exact))
        val checked = assessSwiggyIntentQuantity(item, Recommendations(
            query = item.query, candidates = listOf(exact), suggested = exact,
        ))
        assertUnqualifiedCountNeedsReview(checked, exact, 1)
        assertFalse(isSwiggyCandidateAllowed(item, exact.copy(label = "15 g protein per serving bar 50g")))
        assertFalse(isSwiggyCandidateAllowed(item, exact.copy(label = "protein bar 20g")))
    }

    @Test fun nonPhysicalNumericDescriptorsStayInNameAndOutsidePackFields() {
        val positives = listOf(
            Triple("one battery, 1.5 volts", "battery 1.5 volts", "1.5 volts"),
            Triple("one bulb, 9 watts", "bulb 9 watts", "9 watts"),
        )
        positives.forEachIndexed { index, (source, name, descriptorNumber) ->
            val row = numericRow(source, 0, numericItem(
                name = name, source = source, quantity = "1", quantityUnit = "count", quantityText = "one",
            ))
            assertEquals(source, null, row.numericOwnershipIssue())
            val prepared = numericReview(source, listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(source, Quantity.Count(1), item.quantity)
            assertEquals(source, "count", item.intentQuantityProvenance?.local?.unit)
            assertEquals(source, null, row.item.packValue)
            assertEquals(source, "unspecified", row.item.packUnit)
            assertEquals(source, null, row.item.packText)
            assertTrue(source, item.query.contains(descriptorNumber))
            assertTrue(source, item.query.contains(name))

            val exact = RecommendationCandidate("exact-descriptor-$index", name)
            val catalog = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact)))
            assertTrue(source, swiggyIntentQuantityCandidateChecked(catalog, exact))
            assertTrue(source, isSwiggyCandidateAllowed(catalog, exact))
            assertUnqualifiedCountNeedsReview(catalog, exact, 1)

            if (index == 0) {
                val conflictingCandidates = listOf(
                    RecommendationCandidate("missing-voltage", "battery"),
                    RecommendationCandidate("wrong-voltage", "battery 1.2 volts"),
                    RecommendationCandidate("reordered-voltage", "battery 5.1 volts"),
                    RecommendationCandidate("variant-voltage", name, variant = "9 volts"),
                    RecommendationCandidate("subtitle-voltage", name, subtitle = "9 volts"),
                )
                val conflictingCatalog = assessSwiggyIntentQuantity(
                    item, Recommendations(listOf(exact) + conflictingCandidates),
                )
                assertTrue(source, isSwiggyCandidateAllowed(conflictingCatalog, exact))
                conflictingCandidates.forEach { candidate ->
                    assertFalse("${candidate.spinId} must not contradict numeric descriptor",
                        isSwiggyCandidateAllowed(conflictingCatalog, candidate))
                    assertFalse(isSwiggyCandidateAllowed(conflictingCatalog, candidate) &&
                        isSwiggyCandidateCountCompatible(conflictingCatalog, candidate))
                }
            }
        }

        val source = "one battery, 1.5 volts"
        val invalidMappings = listOf(
            numericItem(name = "battery", source = source, quantity = "1", quantityUnit = "count", quantityText = "one"),
            numericItem(name = "battery 1.2 volts", source = source, quantity = "1", quantityUnit = "count", quantityText = "one"),
            numericItem(name = "battery 1.5 volts", source = source, quantity = "1", quantityUnit = "count", quantityText = "one",
                packValue = "9", packUnit = "g", packText = "9"),
            numericItem(name = "battery 1.5 volts", source = source, quantity = "1", quantityUnit = "count", quantityText = "one",
                packValue = "9", packUnit = "piece", packText = "9"),
        )
        invalidMappings.forEach {
            assertTrue(source, numericRow(source, 0, it).numericOwnershipIssue() != null)
            assertBlocked(source, it)
        }

        val duplicateCount = "one battery, 1.5 volts, one more"
        assertBlocked(duplicateCount, numericItem(
            name = "battery 1.5 volts", source = duplicateCount,
            quantity = "1", quantityUnit = "count", quantityText = "one",
        ))
        val extraNumber = "one battery, 1.5 volts, 9"
        assertBlocked(extraNumber, numericItem(
            name = "battery 1.5 volts", source = extraNumber,
            quantity = "1", quantityUnit = "count", quantityText = "one",
        ))
    }

    @Test fun numericDescriptorClaimMatcherAndFinalGateRequireExactUnconflictedSpecs() {
        val helperCases = listOf(
            Triple("battery 1.5 volts", "battery 1.5 volts", true),
            Triple("bulb 9 watts", "bulb 9 watts", true),
            Triple("battery 1.50 volts", "battery 1.5 volts", true),
            Triple("battery alkaline", "battery", true),
            Triple("battery 1.5 volts", "battery 5.1 volts", false),
            Triple("battery 1.5 volts", "battery 1.2 volts", false),
            Triple("battery 1.5 volts", "battery 1.5", false),
            Triple("battery 1.5 volts", "battery 1.5 watts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts 9 volts", false),
            Triple("battery 1.5 volts and 5 volts", "battery 1.5 volts", false),
            Triple("battery 1.5 volts", "battery +1.5 volts", false),
            Triple("battery 1.5 volts", "battery 1.5-2 volts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts or 2 volts", false),
            Triple("battery 1.5 volts", "battery not 1.5 volts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts approximately", false),
            Triple("battery 1.5 volts", "battery 1.5 volts/3 volts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts; 0.0 volts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts; 0.0volts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts; 3/9volts", false),
            Triple("battery 1.5 volts", "battery 1.5 volts-2", false),
            Triple("non-rechargeable battery 1.5 volts", "non-rechargeable battery 1.5 volts", true),
            Triple("sunscreen 50 SPF", "sunscreen 50 SPF - 50 ml", true),
        )
        helperCases.forEach { (request, offered, expected) ->
            assertEquals("$request <> $offered", expected,
                groceryIntentNumericDescriptorClaimsMatch(request, offered))
        }

        val cases = listOf(
            Triple("one battery, 1.5 volts", "battery 1.5 volts", "battery 1.5 volts"),
            Triple("one bulb, 9 watts", "bulb 9 watts", "bulb 9 watts"),
        )
        cases.forEach { (source, name, exactLabel) ->
            val prepared = numericReview(source, listOf(numericRow(source, 0, numericItem(
                name = name, source = source, quantity = "1", quantityUnit = "count", quantityText = "one",
            )))).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            val item = prepared.items.single()
            val exact = RecommendationCandidate("exact-descriptor-gate", exactLabel)
            val invalids = if (source.contains("battery")) listOf(
                RecommendationCandidate("wrong-value-gate", "battery 5.1 volts"),
                RecommendationCandidate("missing-unit-gate", "battery 1.5"),
                RecommendationCandidate("conflicting-variant-gate", exactLabel, variant = "9 volts"),
                RecommendationCandidate("conflicting-subtitle-gate", exactLabel, subtitle = "not 1.5 volts"),
                RecommendationCandidate("slash-conflict-gate", "battery 1.5 volts/3 volts"),
                RecommendationCandidate("decimal-zero-gate", exactLabel, subtitle = "0.0 volts"),
            ) else listOf(
                RecommendationCandidate("wrong-value-gate", "bulb 8 watts"),
                RecommendationCandidate("missing-descriptor-gate", "bulb"),
                RecommendationCandidate("conflicting-subtitle-gate", exactLabel, subtitle = "10 watts"),
            )
            val catalog = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact) + invalids))
            assertTrue(source, isSwiggyCandidateAllowed(catalog, exact))
            assertUnqualifiedCountNeedsReview(catalog, exact, 1)
            invalids.forEach { candidate ->
                assertFalse("${candidate.spinId} must fail product identity", isSwiggyCandidateAllowed(catalog, candidate))
                assertFalse(isSwiggyCandidateAllowed(catalog, candidate) &&
                    isSwiggyCandidateCountCompatible(catalog, candidate))
            }
        }
    }

    @Test fun closedNumericDescriptorsCanRemainUnstatedOnlyWhenLiteralNameIsPreserved() {
        val positives = listOf(
            "9 watt bulb" to "9 watt bulb",
            "2 ply tissue" to "2 ply tissue",
            "10 g protein per serving bar" to "10 g protein per serving bar",
            "grade A rice" to "grade A rice",
            "vitamin A oil" to "vitamin A oil",
            "9 watt bulb, the red one" to "9 watt red bulb",
        )
        positives.forEach { (source, name) ->
            val prepared = numericReview(source, listOf(numericRow(source, 0, numericItem(name, source))))
                .prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, Quantity.Default, prepared.items.single().quantity)
            assertEquals(source, IntentQuantityOrigin.UNSTATED, prepared.items.single().intentQuantityProvenance?.origin)
            assertEquals(source, null, prepared.items.single().intentQuantityProvenance?.local)
            assertTrue(source, prepared.items.single().query.contains(name))
            assertTrue(source, prepared.excludedText.isEmpty())
            assertTrue(source, prepared.unresolvedText.isEmpty())
        }

        val supportInstruction = "9 watt bulb; actually two"
        val supportResult = numericReview(supportInstruction, listOf(numericRow(
            supportInstruction, 0, numericItem("9 watt bulb", "9 watt bulb"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "actually two")),
        ))).prepareForReview()
        assertTrue(supportResult.items.isEmpty())
        assertTrue(supportResult.excludedText.isNotEmpty())

        val supportOnlyInstruction = "bulb; the 9 watt one"
        val supportOnlyResult = numericReview(supportOnlyInstruction, listOf(numericRow(
            supportOnlyInstruction, 0, numericItem("9 watt bulb", "bulb"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "the 9 watt one")),
        ))).prepareForReview()
        assertTrue(supportOnlyResult.items.isEmpty())
        assertTrue(supportOnlyResult.excludedText.isNotEmpty())

        listOf(
            "9 bulbs" to "bulbs",
            "9 bulbs, the red one" to "red bulbs",
            "two 9 watt bulbs" to "9 watt bulbs",
            "bulb, the 9 watt one" to "9 watt bulb",
            "do 9 watt bulb" to "9 watt bulb",
            "9 watt bulb" to "9 volt bulb",
            "9 watt bulb, the red one" to "red bulb",
            "9 watt bulb 2" to "9 watt bulb",
            "x2 milk" to "x2 milk",
            "pack2 milk" to "pack2 milk",
            "rice 2" to "rice",
        ).forEach { (source, name) -> assertBlocked(source, numericItem(name, source)) }

        assertBlocked("A2 milk", numericItem("milk", "A2 milk"))
        for (source in listOf("A2दूध", "dz2 eggs", "qty2 milk", "2in1 milk", "milk٢")) {
            assertBlocked(source, numericItem(source, source))
        }
    }

    @Test fun opaqueLetterDigitIdentityRequiresExactRawCatalogueTitleProof() {
        val cases = listOf(
            Triple("A2 milk", "A2 milk", "a2"),
            Triple("QX15 device", "QX15 device", "qx15"),
            Triple("milk2", "milk2", "milk2"),
        )
        cases.forEach { (source, name, code) ->
            val prepared = numericReview(source, listOf(numericRow(source, 0, numericItem(name, source))))
                .prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(source, Quantity.Default, item.quantity)
            assertEquals(source, IntentQuantityOrigin.UNSTATED, item.intentQuantityProvenance?.origin)
            assertEquals(source, setOf(code), item.intentQuantityProvenance?.opaqueNumericLabels)
            assertTrue(source, item.query.contains(name))

            val exact = RecommendationCandidate("exact-$code", name)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact)))
            assertTrue(source, swiggyIntentQuantityCandidateChecked(checked, exact))
            assertTrue(source, isSwiggyCandidateAllowed(checked, exact))
            assertEquals(source, 1, swiggyRequestedCartQuantity(checked, exact))
        }

        val source = "A2 milk"
        val prepared = numericReview(source, listOf(numericRow(source, 0, numericItem("A2 milk", source))))
            .prepareForReview()
        val item = prepared.items.single()
        val exact = RecommendationCandidate("exact-a2", "A2 milk")
        val invalids = listOf(
            RecommendationCandidate("missing", "Milk"),
            RecommendationCandidate("near-code", "A20 milk"),
            RecommendationCandidate("subtitle-code", "Milk", subtitle = "A2"),
            RecommendationCandidate("variant-code", "Milk", variant = "A2"),
            RecommendationCandidate("negated-code", "Milk without A2"),
            RecommendationCandidate("conflicting-title", "A1 A2 milk"),
            RecommendationCandidate("conflicting-variant", "A2 milk", variant = "A1"),
            RecommendationCandidate("negating-subtitle", "A2 milk", subtitle = "not A2"),
        )
        val catalog = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact) + invalids))
        assertTrue(swiggyIntentQuantityCandidateChecked(catalog, exact))
        assertTrue(isSwiggyCandidateAllowed(catalog, exact))
        invalids.forEach { candidate ->
            assertFalse("${candidate.spinId} must not prove opaque identity", isSwiggyCandidateAllowed(catalog, candidate))
            assertTrue(runCatching { swiggyRequestedCartQuantity(catalog, candidate) }.isFailure)
        }

        val suggestedOnly = RecommendationCandidate("suggested-only", "Milk")
        val withSuggestion = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact), suggested = suggestedOnly))
        assertFalse(swiggyIntentQuantityCandidateChecked(withSuggestion, suggestedOnly))
        assertFalse(isSwiggyCandidateAllowed(withSuggestion, suggestedOnly))

        val mutatedCode = catalog.copy(intentQuantityProvenance = requireNotNull(catalog.intentQuantityProvenance)
            .copy(opaqueNumericLabels = setOf("a20")))
        assertFalse(swiggyIntentQuantityCandidateChecked(mutatedCode, exact))
        assertFalse(isSwiggyCandidateAllowed(mutatedCode, exact))
        assertFalse(swiggyIntentQuantityCandidateChecked(catalog.copy(intentQuantityProvenance = null), exact))
        assertFalse(swiggyIntentQuantityCandidateChecked(catalog.copy(intentQuantityCatalogueCheck = null), exact))
        assertFalse(swiggyIntentQuantityCandidateChecked(catalog, exact.copy(label = "A2 milk changed")))

        val milk2 = numericReview("milk2", listOf(numericRow("milk2", 0, numericItem("milk2", "milk2"))))
            .prepareForReview().items.single()
        val ordinaryMilk = RecommendationCandidate("ordinary-milk", "Milk")
        val checkedMilk2 = assessSwiggyIntentQuantity(milk2, Recommendations(listOf(ordinaryMilk)))
        assertFalse(swiggyIntentQuantityCandidateChecked(checkedMilk2, ordinaryMilk))
        assertFalse(isSwiggyCandidateAllowed(checkedMilk2, ordinaryMilk))
        assertTrue(runCatching { swiggyRequestedCartQuantity(checkedMilk2, ordinaryMilk) }.isFailure)
    }

    @Test fun opaqueCodeInAnchoredModifierSurvivesPreparationAndFinalCartGate() {
        val instruction = "milk; milk A2"
        val support = GroceryIntentReviewV2SupportText("modifier", "milk A2")
        val prepared = numericReview(instruction, listOf(numericRow(instruction, 0,
            numericItem("A2 milk", "milk;"), listOf(support)))).prepareForReview()
        val item = prepared.items.single()
        assertEquals(setOf("a2"), item.intentQuantityProvenance?.opaqueNumericLabels)
        val exact = RecommendationCandidate("exact", "A2 milk")
        val subtitleOnly = RecommendationCandidate("subtitle", "milk", subtitle = "A2")
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact, subtitleOnly)))
        assertEquals(1, swiggyRequestedCartQuantity(assessed, exact))
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed, subtitleOnly) }.isFailure)
        val missing = assessed.copy(intentQuantityProvenance = requireNotNull(assessed.intentQuantityProvenance)
            .copy(opaqueNumericLabels = emptySet()))
        assertTrue(runCatching { swiggyRequestedCartQuantity(missing, exact) }.isFailure)
        val dropped = numericRow(instruction, 0, numericItem("milk", "milk;"), listOf(support))
        assertEquals("numeric_descriptor_not_preserved", dropped.numericOwnershipIssue())
        assertTrue(numericReview(instruction, listOf(dropped)).prepareForReview().items.isEmpty())
    }

    @Test fun opaqueCodeSurvivesAProvedQuantityCorrection() {
        val instruction = "one A2 milk; make A2 milk two"
        val row = numericRow(instruction, 0,
            numericItem("A2 milk", "one A2 milk", quantity = "1", quantityText = "one")).copy(
            corrections = listOf(GroceryIntentReviewV2Correction(
                kind = "quantity", source = numericQuote(instruction, "make A2 milk two"),
                newAmount = numericQuote(instruction, "two"), oldValue = BigDecimal.ONE, oldUnit = "count",
                newValue = BigDecimal("2"), newUnit = "count",
            )),
        )
        val item = numericReview(instruction, listOf(row)).prepareForReview().items.single()
        assertEquals(Quantity.Count(2), item.quantity)
        assertEquals(IntentQuantityOrigin.CORRECTION, item.intentQuantityProvenance?.origin)
        assertEquals(setOf("a2"), item.intentQuantityProvenance?.opaqueNumericLabels)
        val exact = RecommendationCandidate("exact", "A2 milk")
        val plain = RecommendationCandidate("plain", "milk", subtitle = "A2")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact, plain)))
        assertUnqualifiedCountNeedsReview(checked, exact, 2)
        assertTrue(runCatching { swiggyRequestedCartQuantity(checked, plain) }.isFailure)
    }

    @Test fun spelledOutDescriptorNumbersKeepTheirOwnRole() {
        val valid = listOf(
            "two nine watt bulbs" to "nine watt bulbs",
            "two one and a half watt bulbs" to "one and a half watt bulbs",
            "two twenty five watt bulbs" to "twenty five watt bulbs",
        )
        valid.forEach { (source, name) ->
            val result = numericReview(source, listOf(numericRow(source, 0, numericItem(
                name = name, source = source, quantity = "2", quantityUnit = "count", quantityText = "two",
            )))).prepareForReview()
            assertEquals(source, 1, result.items.size)
            assertEquals(Quantity.Count(2), result.items.single().quantity)
            assertTrue(source, result.items.single().query.contains(name))
        }

        val ambiguous = "one and a half watt bulb"
        assertBlocked(ambiguous, numericItem(
            name = "a half watt bulb", source = ambiguous,
            quantity = "1", quantityUnit = "count", quantityText = "one",
        ))
    }

    @Test fun inverseUnitPrefixesCannotMasqueradeAsProductIdentifiers() {
        listOf("pk4", "pkt2", "pc2", "qty2").forEach { code ->
            val source = "one $code bulb"
            assertBlocked(source, numericItem(
                name = "$code bulb", source = source, quantity = "1", quantityUnit = "count", quantityText = "one",
            ))
        }
    }

    @Test fun additionalUnicodeNumericFormsRemainUnowned() {
        listOf("二", "２", "Ⅱ", "\uD840\uDC01").forEach { numeral ->
            val source = "two 500 ml milk bottles, $numeral"
            assertBlocked(source, numericItem(
                name = "milk", source = source, quantity = "2", quantityUnit = "pack", quantityText = "two",
                packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
            ))
        }
    }

    @Test fun repeatedPackSizeRequiresAnExplicitEachRestatement() {
        val withoutEach = "two 500 ml milk bottles, 500 ml"
        assertBlocked(withoutEach, numericItem(
            name = "milk", source = withoutEach, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        ))

        val withEach = "two 500 ml milk bottles, 500 ml each"
        val result = numericReview(withEach, listOf(numericRow(withEach, 0, numericItem(
            name = "milk", source = withEach, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        )))).prepareForReview()
        assertEquals(1, result.items.size)
        assertEquals(Quantity.Count(2), result.items.single().quantity)
        assertTrue(result.items.single().query.contains("500 ml"))
    }

    @Test fun physicalUnitsAndUnicodeNumbersCannotBeBorrowedAcrossRoles() {
        listOf(
            "two milk bottles 500 ml, 2 kg total" to Triple("milk", "500", "ml"),
            "two milk bottles 500 ml, 2 packs" to Triple("milk", "500", "ml"),
            "two milk bottles 500 ml, ٢" to Triple("milk", "500", "ml"),
            "two milk bottles 500 ml, ½" to Triple("milk", "500", "ml"),
            "two milk bottles 500 ml, ²" to Triple("milk", "500", "ml"),
        ).forEach { (source, fields) ->
            val (name, size, sizeUnit) = fields
            assertBlocked(source, numericItem(
                name = name, source = source, quantity = "2", quantityUnit = "pack", quantityText = "two",
                packValue = size, packUnit = sizeUnit, packText = size, packaging = "bottle",
            ))
        }

        val reversedOrder = "500 ml milk bottles, two"
        val reversedOrderResult = numericReview(reversedOrder, listOf(numericRow(reversedOrder, 0, numericItem(
            name = "milk", source = reversedOrder, quantity = "2", quantityUnit = "pack", quantityText = "two",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        )))).prepareForReview()
        assertEquals(1, reversedOrderResult.items.size)
        assertEquals(Quantity.Count(2), reversedOrderResult.items.single().quantity)
        assertTrue(reversedOrderResult.items.single().query.contains("500 ml"))
    }

    @Test fun numericIdentityAndDescriptiveOneStayLiteralButMustBeQuotedInTheName() {
        val literalRows = listOf(
            Triple("two rolls 3 ply tissue", "3 ply tissue", "two"),
            Triple("two kg grade A rice", "grade A rice", "two"),
            Triple("one milk packet, the blue one", "milk packet, blue one", "one"),
        )
        literalRows.forEach { (source, name, countText) ->
            val isWeight = source.startsWith("two kg")
            val quantity = if (source.startsWith("one ")) "1" else "2"
            val result = numericReview(source, listOf(numericRow(source, 0, numericItem(
                name = name, source = source, quantity = quantity,
                quantityUnit = if (isWeight) "kg" else "pack",
                quantityText = countText,
                packaging = when {
                    source.startsWith("one ") -> "packet"
                    source.startsWith("two rolls") -> "roll"
                    else -> null
                },
            )))).prepareForReview()
            assertEquals(source, 1, result.items.size)
            assertTrue(source, result.items.single().query.contains(name))
        }

        val omittedBlue = "one milk packet, the blue one"
        assertBlocked(omittedBlue, numericItem(
            name = "milk packet", source = omittedBlue, quantity = "1", quantityUnit = "pack",
            quantityText = "one", packaging = "packet",
        ))

        val ambiguousMeasure = "one roasted coffee protein bar, the 10 gram, no added sugar"
        val ambiguousItem = numericItem(
            name = "roasted coffee protein bar, the 10 gram", source = ambiguousMeasure,
            quantity = "1", quantityUnit = "pack", quantityText = "one", packaging = "bar",
            required = listOf("no_added_sugar"),
        )
        val ambiguousRow = numericRow(ambiguousMeasure, 0, ambiguousItem)
        assertEquals("numeric_atom_unowned", ambiguousRow.numericOwnershipIssue())
        assertBlocked(ambiguousMeasure, ambiguousItem)
    }

    @Test fun blockedNumericOwnershipDoesNotDiscardAnIndependentPeer() {
        val instruction = "two 500 ml milk bottles, 2 litres total; one bread"
        val result = numericReview(instruction, listOf(
            numericRow(instruction, 0, numericItem(
                name = "milk", source = "two 500 ml milk bottles, 2 litres total",
                quantity = "2", quantityUnit = "pack", quantityText = "two",
                packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
            )),
            numericRow(instruction, 1, numericItem(
                name = "bread", source = "one bread", quantity = "1", quantityUnit = "count", quantityText = "one",
            )),
        )).prepareForReview()

        assertEquals(listOf("bread"), result.items.map { it.query })
        assertEquals(Quantity.Count(1), result.items.single().quantity)
        assertTrue(result.excludedText.any { it.contains("2 litres total") })
        assertFalse(result.unresolvedText.contains("one bread"))
    }

    @Test fun descriptiveOneKeepsAllLiteralDescriptorsAndDietaryRestrictions() {
        val examples = listOf(
            Triple("one sauce, the hot and sweet one", "hot sweet sauce", emptyList<String>()),
            Triple("one milk packet, the blue and white one", "blue white milk", emptyList<String>()),
            Triple("one cola, the sugar free one", "cola", listOf("sugar_free")),
            Triple("one cola, the zero sugar one", "cola", listOf("sugar_free")),
        )
        examples.forEach { (source, name, required) ->
            val hasPacket = "packet" in source
            val item = numericItem(name = name, source = source, quantity = "1",
                quantityUnit = if (hasPacket) "pack" else "count", quantityText = "one",
                packaging = if (hasPacket) "packet" else null).copy(required = required)
            val prepared = numericReview(source, listOf(numericRow(source, 0, item))).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, Quantity.Count(1), prepared.items.single().quantity)
        }
        val zeroMaida = "one biscuits, the zero maida one"
        val item = numericItem(name = "biscuits", source = zeroMaida, quantity = "1", quantityUnit = "count",
            quantityText = "one").copy(excluded = listOf("maida"))
        val prepared = numericReview(zeroMaida, listOf(numericRow(zeroMaida, 0, item))).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertTrue(prepared.items.single().avoidPhrases.contains("maida"))
    }

    @Test fun sourceOwnedDescriptiveOneAllowsCompleteTrailingNegativeIngredientClauses() {
        val exclusions = listOf("palm oil", "chemicals", "preservatives", "artificial colours", "artificial flavours")
        val clauses = listOf("no palm oil", "no chemicals", "no preservatives", "no artificial colours", "no artificial flavours")
        val source = "one BrandA tangy tomato puffs, the tangy tomato one, ${clauses.joinToString(", ")}"
        val item = numericItem(
            name = "BrandA tangy tomato puffs, tangy tomato", source = source,
            quantity = "1", quantityUnit = "count", quantityText = "one",
        ).copy(excluded = exclusions)
        val row = numericRow(source, 0, item)

        assertEquals(null, row.numericOwnershipIssue())
        val response = numericReview(source, listOf(row))
        val prepared = response.prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().query.contains("tangy tomato"))
        assertTrue(prepared.items.single().avoidPhrases.containsAll(exclusions))
        assertTrue(prepared.excludedText.isEmpty())
        assertEquals(source, response.instruction)

        for ((clause, exclusion) in listOf("no added sugar" to "added sugar", "no sugar" to "sugar")) {
            val sugarSource = "one BrandA tangy tomato puffs, the tangy tomato one, $clause"
            val sugarItem = numericItem(
                "BrandA tangy tomato puffs, tangy tomato", sugarSource,
                quantity = "1", quantityText = "one",
            ).copy(excluded = listOf(exclusion))
            val sugarPrepared = numericReview(sugarSource, listOf(numericRow(sugarSource, 0, sugarItem))).prepareForReview()
            assertEquals(sugarSource, 1, sugarPrepared.items.size)
            assertTrue(sugarSource, sugarPrepared.items.single().avoidPhrases.contains(exclusion))
        }
    }

    @Test fun zeroSugarDescriptorTailProvesSugarButNotAddedSugar() {
        val source = "one BrandA tangy tomato puffs, the zero sugar one"
        val name = "BrandA tangy tomato puffs, zero sugar"
        val sugarExcluded = numericItem(name, source, quantity = "1", quantityText = "one")
            .copy(excluded = listOf("sugar"))
        val prepared = numericReview(source, listOf(numericRow(source, 0, sugarExcluded))).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().avoidPhrases.contains("sugar"))
        assertTrue(prepared.items.single().query.contains("zero sugar"))

        val addedSugarOnly = sugarExcluded.copy(excluded = listOf("added sugar"))
        assertBlocked(source, addedSugarOnly)

        val exactRequiredAndExcluded = sugarExcluded.copy(required = listOf("sugar_free"))
        val exactPrepared = numericReview(source, listOf(numericRow(source, 0, exactRequiredAndExcluded))).prepareForReview()
        assertEquals(1, exactPrepared.items.size)
        assertEquals(Quantity.Count(1), exactPrepared.items.single().quantity)
        assertTrue(exactPrepared.items.single().avoidPhrases.contains("sugar"))
    }

    @Test fun chemicalAndArtificialIngredientZeroClausesKeepThePurchaseCountAndSource() {
        val forms = listOf("no", "zero", "0", "0%")
        val terms = listOf(
            "chemicals" to "chemicals",
            "preservatives" to "preservatives",
            "artificial colours" to "artificial colours",
            "artificial flavours" to "artificial flavours",
        )
        for ((term, exclusion) in terms) for (claim in forms) {
            val source = "one BrandA tangy tomato puffs, the tangy tomato one, $claim $term"
            val item = numericItem(
                "BrandA tangy tomato puffs, tangy tomato", source,
                quantity = "1", quantityText = "one",
            ).copy(excluded = listOf(exclusion))
            val response = numericReview(source, listOf(numericRow(source, 0, item)))
            val prepared = response.prepareForReview()

            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, Quantity.Count(1), prepared.items.single().quantity)
            assertTrue(source, prepared.items.single().avoidPhrases.contains(exclusion))
            assertEquals(source, source, item.sourceText)
            assertEquals(source, source, response.instruction)
        }
    }

    @Test fun sugarExclusionMustMatchTheSourceScopeAndCannotConflictWithPositiveClaims() {
        val base = "one BrandA tangy tomato puffs"
        val cases = listOf(
            Triple("$base, no added sugar", listOf("sugar"), emptyList()),
            Triple("$base, no sugar", listOf("added sugar"), emptyList()),
            Triple("$base, no added sugar", emptyList(), emptyList()),
            Triple("$base, no sugar", emptyList(), emptyList()),
            Triple("$base, no added sugar", listOf("added sugar"), listOf("sugar")),
            Triple("$base, no sugar", listOf("sugar"), listOf("added sugar")),
        )
        for ((source, excluded, required) in cases) {
            val item = numericItem(
                "BrandA tangy tomato puffs", source,
                quantity = "1", quantityText = "one",
            ).copy(excluded = excluded, required = required)
            assertEquals(source, BigDecimal.ONE, item.quantity)
            assertBlocked(source, item)
        }
    }

    @Test fun croppedDescriptiveOnePrimaryCannotHideTrailingCancellationOrCount() {
        val primary = "one BrandA tangy tomato puffs, the tangy tomato one, no chemicals"
        val exclusions = listOf("chemicals")
        for (tail in listOf(", no wait, the plain one", ", no, two", " biscuits")) {
            val instruction = primary + tail
            val item = numericItem(
                "BrandA tangy tomato puffs, tangy tomato", primary,
                quantity = "1", quantityText = "one",
            ).copy(excluded = exclusions)
            val croppedRow = numericRow(instruction, 0, item)
            val response = numericReview(instruction, listOf(croppedRow))
            val prepared = response.prepareForReview()

            assertTrue(instruction, prepared.items.isEmpty())
            assertEquals(instruction, primary, croppedRow.evidence.primary.text)
            assertEquals(instruction, instruction, response.instruction)
        }
    }

    @Test fun sourceOwnedDescriptiveOneRejectsUnexplainedOrQualifiedNegativeTailText() {
        val validPrefix = "one BrandA tangy tomato puffs, the tangy tomato one"
        val cases = listOf(
            "BrandA tangy tomato puffs, the tangy tomato one, no chemicals" to numericItem(
                "BrandA tangy tomato puffs, tangy tomato", "BrandA tangy tomato puffs, the tangy tomato one, no chemicals",
                quantity = "1", quantityText = "one").copy(excluded = listOf("chemicals")),
            "$validPrefix, no palm oil, no chemicals" to numericItem(
                "BrandA tangy tomato puffs, tangy tomato", "$validPrefix, no palm oil, no chemicals",
                quantity = "1", quantityText = "one"),
            "$validPrefix, no palm oil, no chemicals" to numericItem("BrandA puffs",
                "$validPrefix, no palm oil, no chemicals", quantity = "1", quantityText = "one")
                .copy(excluded = listOf("palm oil", "chemicals")),
            "$validPrefix, almost no chemicals" to numericItem("BrandA tangy tomato puffs, tangy tomato",
                "$validPrefix, almost no chemicals", quantity = "1", quantityText = "one")
                .copy(excluded = listOf("chemicals")),
            "$validPrefix, no chemicals if available" to numericItem("BrandA tangy tomato puffs, tangy tomato",
                "$validPrefix, no chemicals if available", quantity = "1", quantityText = "one")
                .copy(excluded = listOf("chemicals")),
            "$validPrefix, no palm oil, no chemicals, one cold drink" to numericItem(
                "BrandA tangy tomato puffs, tangy tomato", "$validPrefix, no palm oil, no chemicals, one cold drink",
                quantity = "1", quantityText = "one").copy(excluded = listOf("palm oil", "chemicals")),
            "$validPrefix, no palm oil, no chemicals, two packs" to numericItem(
                "BrandA tangy tomato puffs, tangy tomato", "$validPrefix, no palm oil, no chemicals, two packs",
                quantity = "1", quantityText = "one").copy(excluded = listOf("palm oil", "chemicals")),
            "$validPrefix, no palm oil, no chemicals, skip the cheap one" to numericItem(
                "BrandA tangy tomato puffs, tangy tomato", "$validPrefix, no palm oil, no chemicals, skip the cheap one",
                quantity = "1", quantityText = "one").copy(excluded = listOf("palm oil", "chemicals")),
        )
        for ((source, item) in cases) assertBlocked(source, item)
        for ((clause, exclusions) in listOf(
            "no preservative" to listOf("preservative"),
            "no artificial colors" to listOf("artificial colors"),
            "no chemicals and preservatives" to listOf("chemicals", "preservatives"),
            "no added preservatives" to listOf("preservatives"),
            "no preservatives added" to listOf("preservatives"),
        )) {
            val source = "$validPrefix, $clause"
            assertBlocked(source, numericItem("BrandA tangy tomato puffs", source,
                quantity = "1", quantityText = "one").copy(excluded = exclusions))
        }
    }

    @Test fun negativeIndefiniteArticleDoesNotConsumePackSizeOrRetainedExclusion() {
        val examples = listOf(
            Triple("250 g toothpaste without a strong mint flavour", "toothpaste", "strong mint flavour"),
            Triple("500 g soap without an artificial fragrance", "soap", "artificial fragrance"),
            Triple("100 g tea without a bitter aftertaste", "tea", "bitter aftertaste"),
            Triple("250 g toothpaste without a strong mint flavour. ", "toothpaste", "strong mint flavour"),
            Triple("250 g toothpaste without a strong mint flavour!", "toothpaste", "strong mint flavour"),
            Triple("250 g toothpaste without a strong mint flavour?", "toothpaste", "strong mint flavour"),
        )
        examples.forEach { (source, name, exclusion) ->
            val item = numericItem(name, source, packValue = "${source.substringBefore(' ')}",
                packUnit = "g", packText = source.substringBefore(' ')).copy(excluded = listOf(exclusion))
            val row = numericRow(source, 0, item)
            assertEquals(source, null, row.numericOwnershipIssue())
            val pack = requireNotNull(row.localPackSizeEvidence())
            assertEquals(source, BigDecimal(source.substringBefore(' ')), pack.value)
            assertEquals(source, "g", pack.unit)
            assertEquals(source, BigDecimal(source.substringBefore(' ')), row.item.packValue)
            assertEquals(source, null, row.item.quantity)
            assertEquals(source, listOf(exclusion), row.item.excluded)
            assertEquals(source, source, row.evidence.primary.text)
        }
        // Numeric ownership is not whole-instruction semantic approval; retain the
        // independent V2 source/property hold on the unmodified sentence.
        val fullSource = examples.first().first
        assertBlocked(fullSource, numericItem("toothpaste", fullSource, packValue = "250", packUnit = "g",
            packText = "250").copy(excluded = listOf("strong mint flavour")))
    }

    @Test fun negativeIndefiniteArticleExceptionRequiresExactTerminalRetainedExclusion() {
        data class Case(val source: String, val excluded: List<String>, val name: String = "toothpaste",
                        val required: List<String> = emptyList())
        val cases = listOf(
            Case("250 g toothpaste without a strong mint flavour", emptyList()),
            Case("250 g toothpaste without a strong mint flavour", listOf("mint flavour")),
            Case("250 g toothpaste without a strong mint flavour", listOf("strong mint")),
            Case("250 g toothpaste without a strong mint flavour and two soaps", listOf("strong mint flavour")),
            Case("250 g toothpaste without a strong mint flavour for sensitive gums", listOf("strong mint flavour")),
            Case("250 g toothpaste without a 2 in 1 formula", listOf("2 in 1 formula")),
            Case("250 g toothpaste with or without a strong mint flavour", listOf("strong mint flavour")),
            Case("250 g toothpaste not without a strong mint flavour", listOf("strong mint flavour")),
            Case("250 g toothpaste never without a strong mint flavour", listOf("strong mint flavour")),
            Case("250 g toothpaste almost without a strong mint flavour", listOf("strong mint flavour")),
            Case("250 g toothpaste without a strong mint flavour if available", listOf("strong mint flavour")),
            Case("250 g toothpaste without a strong mint flavour and no sugar", listOf("strong mint flavour", "sugar")),
            Case("250 g toothpaste without a packet of sugar", listOf("packet")),
            Case("250 g toothpaste without 100 g sugar", listOf("100 g sugar")),
            Case("250 g toothpaste without one tube of sugar", listOf("one tube of sugar")),
            Case("250 g toothpaste without two packs of sugar", listOf("two packs of sugar")),
            Case("250 g toothpaste without a strong mint flavour, and without a strong mint flavour",
                listOf("strong mint flavour")),
            Case("250 g toothpaste labelled \"without a strong mint flavour\"", listOf("strong mint flavour")),
            Case("250 g toothpaste without a strong mint flavour", listOf("strong mint flavour"),
                name = "strong mint toothpaste"),
            Case("250 g toothpaste without a strong mint flavour", listOf("strong mint flavour"),
                required = listOf("strong mint flavour")),
        )
        cases.forEach { case ->
            val item = numericItem(case.name, case.source, packValue = "250", packUnit = "g", packText = "250",
                required = case.required).copy(excluded = case.excluded)
            if (case.required.isEmpty()) {
                if (case.name == "toothpaste") {
                    assertTrue(case.source, numericRow(case.source, 0, item).numericOwnershipIssue() != null)
                }
                assertBlocked(case.source, item)
            } else {
                val outcome = runCatching { numericReview(case.source, listOf(numericRow(case.source, 0, item))).prepareForReview() }
                assertTrue(case.source, outcome.isFailure || outcome.getOrNull()?.items?.isEmpty() == true)
            }
        }
        val negativeArticleSource = "250 g toothpaste without a strong mint flavour"
        val negativeArticleCount = numericItem("toothpaste", negativeArticleSource,
            quantity = "1", quantityUnit = "count", quantityText = "a", packValue = "250",
            packUnit = "g", packText = "250").copy(excluded = listOf("strong mint flavour"))
        // Android excludes non-purchase candidates before binding; unlike the
        // backend's explicit-context low-level API, this fails before partition.
        assertEquals("numeric_atom_unbound", numericRow(negativeArticleSource, 0,
            negativeArticleCount).numericOwnershipIssue())
        assertBlocked(negativeArticleSource, negativeArticleCount)
    }

    @Test fun genuineLeadingIndefiniteArticleCountRemainsOwned() {
        val cases = listOf(
            "a toothpaste" to "toothpaste",
            "an apple" to "apple",
            "a toothpaste, 250 g without a strong mint flavour" to "toothpaste",
            "an apple, 100 g without a bitter aftertaste" to "apple",
        )
        for ((source, name) in cases) {
            val exclusion = when {
                source.contains("strong mint") -> "strong mint flavour"
                source.contains("bitter aftertaste") -> "bitter aftertaste"
                else -> null
            }
            val packValue = when {
                source.contains("250 g") -> "250"
                source.contains("100 g") -> "100"
                else -> null
            }
            val item = numericItem(name, source, quantity = "1", quantityUnit = "count",
                quantityText = source.substringBefore(' '), packValue = packValue, packUnit = if (packValue == null) "unspecified" else "g",
                packText = packValue).copy(excluded = listOfNotNull(exclusion))
            val row = numericRow(source, 0, item)
            if (exclusion == null) {
                // Keep whole-review acceptance controls for ordinary leading articles.
                val prepared = numericReview(source, listOf(row)).prepareForReview()
                assertEquals(source, 1, prepared.items.size)
                assertEquals(source, Quantity.Count(1), prepared.items.single().quantity)
                assertTrue(source, prepared.excludedText.isEmpty())
            } else {
                assertEquals(source, null, row.numericOwnershipIssue())
                val count = requireNotNull(row.localQuantityEvidence())
                assertEquals(source, 0, count.startUtf16)
                assertEquals(source, source.substringBefore(' '), count.atom)
                assertEquals(source, BigDecimal("1"), count.value)
                assertEquals(source, BigDecimal(requireNotNull(packValue)), requireNotNull(row.localPackSizeEvidence()).value)
                assertEquals(source, listOf(exclusion), row.item.excluded)
                // The later article belongs to the exact retained exclusion, not quantity.
                assertBlocked(source, item)
            }
        }
    }

    @Test fun legacyPhysicalTotal250GramsSurvivesNegativeIndefiniteArticle() {
        val source = "250 g toothpaste without a strong mint flavour"
        val item = GroceryIntentItem(
            name = "toothpaste", quantity = BigDecimal("250"), quantityUnit = "g",
            packValue = null, packUnit = "unspecified", required = emptyList(),
            excluded = listOf("strong mint flavour"), sourceText = source, needsClarification = false,
        )
        assertEquals(null, item.legacyNumericOwnershipIssue())
        assertEquals(BigDecimal("250"), item.quantity)
        assertEquals(listOf("strong mint flavour"), item.excluded)
    }

    @Test fun preferablyWithoutKeepsNumericOwnershipButRemainsUnderWholeV2Review() {
        val source = "250 g toothpaste preferably without a strong mint flavour"
        val item = numericItem("toothpaste", source, packValue = "250", packUnit = "g", packText = "250")
            .copy(excluded = listOf("strong mint flavour"))
        val row = numericRow(source, 0, item)
        assertEquals(null, row.numericOwnershipIssue())
        assertEquals(BigDecimal("250"), requireNotNull(row.localPackSizeEvidence()).value)
        assertBlocked(source, item)
    }

    @Test fun negativeIndefiniteArticleRetainedExclusionUsesExactUnicodeSourceText() {
        val ordinarySpaceSource = "250 g toothpaste without a strong mint flavour"
        val nonBreakingSpace = '\u00A0'
        val mismatched = numericRow(ordinarySpaceSource, 0,
            numericItem("toothpaste", ordinarySpaceSource, packValue = "250", packUnit = "g", packText = "250")
                .copy(excluded = listOf("strong mint${nonBreakingSpace}flavour")))
        assertTrue(mismatched.numericOwnershipIssue() != null)

        val exactNbspSource = "250 g toothpaste without a strong mint${nonBreakingSpace}flavour"
        val exactNbsp = numericRow(exactNbspSource, 0,
            numericItem("toothpaste", exactNbspSource, packValue = "250", packUnit = "g", packText = "250")
                .copy(excluded = listOf("strong mint${nonBreakingSpace}flavour")))
        assertEquals(null, exactNbsp.numericOwnershipIssue())

        val unicodeCaseSource = "250 g toothpaste without a Café flavour"
        val unicodeCase = numericRow(unicodeCaseSource, 0,
            numericItem("toothpaste", unicodeCaseSource, packValue = "250", packUnit = "g", packText = "250")
                .copy(excluded = listOf("café flavour")))
        assertEquals(null, unicodeCase.numericOwnershipIssue())
    }

    @Test fun descriptiveOneCannotHideCountsAlternativesOrMissingWords() {
        val examples = listOf(
            "one sauce, the hot and sweet one" to "hot sauce",
            "one sauce, the two pack one" to "two pack sauce",
            "one sauce, the hot one and one more" to "hot sauce",
            "one sauce, the hot or sweet one" to "hot sweet sauce",
            "one sauce, the not hot one" to "hot sauce",
            "one sauce, the and one" to "sauce",
            "one cola, the zero one" to "cola",
            "one biscuits, the zero maida one" to "biscuits",
            "one sauce and one sauce" to "sauce",
            "one sauce, the hot and sweet one with extra cheese" to "hot sweet sauce",
            "one sauce, the hot and sweet one and cold sauce" to "hot sweet sauce",
            "one sauce, the hot and sweet one, cold sauce" to "hot sweet sauce",
            "one sauce, the blue one with extra cheese" to "blue sauce",
            "one sauce, the blue one and cold sauce" to "blue sauce",
        )
        examples.forEach { (source, name) ->
            assertBlocked(source, numericItem(name = name, source = source, quantity = "1",
                quantityUnit = "count", quantityText = "one"))
        }
        val addedDescriptor = "one milk packet, the blue one"
        assertBlocked(addedDescriptor, numericItem(name = "blue red milk", source = addedDescriptor,
            quantity = "1", quantityUnit = "pack", quantityText = "one", packaging = "packet"))
    }

    @Test fun distributiveHarEkCannotOwnPurchaseCountButExplicitOuterCountAndSuffixEachRemainValid() {
        val source = "Vim ki tikki, har ek 200 gram ki"
        val wrappedInstruction = "Please add: $source"
        val v2 = numericRow(wrappedInstruction, 0, numericItem(
            name = "Vim tikki", source = source, quantity = "1", quantityUnit = "count", quantityText = "ek",
            packValue = "200", packUnit = "g", packText = "200",
        ))
        assertEquals("distributive_each_as_purchase_count", v2.numericOwnershipIssue())
        assertTrue(numericReview(wrappedInstruction, listOf(v2)).prepareForReview().items.isEmpty())

        listOf("Vim ki tikki, each one 200 gram" to "one").forEach { (markedSource, markerAmount) ->
            val markedRow = numericRow(markedSource, 0, numericItem(
                name = "Vim tikki", source = markedSource, quantity = "1", quantityUnit = "count",
                quantityText = markerAmount, packValue = "200", packUnit = "g", packText = "200",
            ))
            assertEquals(markedSource, "distributive_each_as_purchase_count", markedRow.numericOwnershipIssue())
            assertTrue(markedSource, numericReview(markedSource, listOf(markedRow)).prepareForReview().items.isEmpty())
        }
        val hindi = "Vim ki tikki, हर एक 200 gram ki"
        val hindiRow = numericRow(hindi, 0, numericItem(
            name = "Vim tikki", source = hindi, quantity = "1", quantityUnit = "count", quantityText = "एक",
            packValue = "200", packUnit = "g", packText = "200",
        ))
        assertEquals(hindi, "numeric_atom_unbound", hindiRow.numericOwnershipIssue())
        assertTrue(hindi, numericReview(hindi, listOf(hindiRow)).prepareForReview().items.isEmpty())

        val v1 = GroceryIntentItem(
            name = "Vim tikki", quantity = BigDecimal.ONE, quantityUnit = "count",
            packValue = BigDecimal("200"), packUnit = "g", required = emptyList(), excluded = emptyList(),
            sourceText = source, needsClarification = false,
        )
        assertTrue(runCatching {
            GroceryIntentDraft("grocery-intent-v1", true, source, listOf(v1), false).prepareForReview()
        }.isFailure)

        val suffix = "2 bottles milk 500 ml each"
        val suffixV2 = numericRow(suffix, 0, numericItem(
            name = "milk", source = suffix, quantity = "2", quantityUnit = "count", quantityText = "2",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "bottle",
        ))
        assertEquals(null, suffixV2.numericOwnershipIssue())

        // The outer count has an independent source span; the distributive
        // marker is not a reason to reject that explicitly stated quantity.
        val explicit = "2 packs milk 500 ml each"
        val explicitV2 = numericRow(explicit, 0, numericItem(
            name = "milk", source = explicit, quantity = "2", quantityUnit = "count", quantityText = "2",
            packValue = "500", packUnit = "ml", packText = "500", packaging = "pack",
        ))
        assertEquals(null, explicitV2.numericOwnershipIssue())
    }

    private fun assertBlocked(source: String, item: GroceryIntentReviewV2Item) {
        val result = numericReview(source, listOf(numericRow(source, 0, item))).prepareForReview()
        assertTrue(source, result.items.isEmpty())
        assertEquals(source, listOf(source), result.excludedText)
    }
}

private fun numericReview(instruction: String, rows: List<GroceryIntentReviewV2ReadyRow>) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2",
    requiresReview = true,
    instruction = instruction,
    sourceItemCount = rows.size,
    readyRows = rows,
    blockedRows = emptyList(),
)

private fun numericItem(
    name: String,
    source: String,
    quantity: String? = null,
    quantityUnit: String = if (quantity == null) "unspecified" else "count",
    quantityText: String? = quantity,
    packValue: String? = null,
    packUnit: String = if (packValue == null) "unspecified" else "ml",
    packText: String? = packValue,
    packaging: String? = null,
    required: List<String> = emptyList(),
) = GroceryIntentReviewV2Item(
    name = name,
    quantity = quantity?.let { BigDecimal(it) },
    quantityUnit = quantityUnit,
    packValue = packValue?.let { BigDecimal(it) },
    packUnit = packUnit,
    packaging = packaging,
    required = required,
    excluded = emptyList(),
    sourceText = source,
    supportingText = emptyList(),
    needsClarification = false,
    quantityText = quantityText,
    packText = packText,
)

private fun numericRow(
    instruction: String,
    index: Int,
    item: GroceryIntentReviewV2Item,
    supporting: List<GroceryIntentReviewV2SupportText> = emptyList(),
) = GroceryIntentReviewV2ReadyRow(
    rowIndex = index,
    item = item.copy(supportingText = supporting),
    evidence = GroceryIntentReviewV2Evidence(
        primary = numericQuote(instruction, item.sourceText),
        supporting = supporting.map { GroceryIntentReviewV2SupportQuote(it.kind, numericQuote(instruction, it.text)) },
    ),
    quantityOrigin = if (item.quantity == null) "not_stated" else "stated",
    amountRoleStatus = if (item.quantity == null && item.packValue == null) "not_stated" else "matched",
)

private fun numericQuote(instruction: String, text: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(text)
    require(start >= 0)
    return GroceryIntentReviewV2Quote(text, start, start + text.length)
}
