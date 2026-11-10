package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.Quantity
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentSourcePiecesTest {
    @Test
    fun sourcePieceReceiptValidatesAnExistingCountButCannotPromoteDefaultDescriptors() {
        data class Case(val source: String, val title: String, val count: Int)
        val cases = listOf(
            Case("2 pieces frozen snacks", "Frozen Snacks", 2),
            Case("2 pcs candles", "Candles", 2),
            Case("12 pieces eggs", "Eggs", 12),
            Case("two pieces of lemon", "Lemon", 2),
        )
        for ((source, title, count) in cases) {
            val descriptor = InstructionParser.parse(source).single()
            assertEquals(source, descriptor.rawText)
            assertEquals("$source: descriptor is not purchase authority", Quantity.Default, descriptor.quantity)
            assertNull(descriptor.validSourcePieceAmount())
            val item = com.example.beta.automation.ParsedItem(
                rawText = source, query = descriptor.query, quantity = Quantity.Count(count),
            ).withDeterministicSourceEvidence(source, source, ownsClause = true)
            assertTrue("$source: explicit loose-piece role", item.logicalPieceCount)
            assertTrue("$source: source amount requires proof", item.requiresIntentQuantityProof)
            assertNotNull("$source: source receipt", item.validSourcePieceAmount())

            for (perSku in listOf(1, 2, 6)) {
                val candidate = pieceCandidate(label = "$title $perSku Pieces",
                    fields = CatalogueQuantityFields(variantName = "$perSku pieces"))
                val descriptorChecked = assessSwiggyIntentQuantity(descriptor, Recommendations(listOf(candidate)))
                assertTrue("$source: a descriptor cannot authorize conversion",
                    resolveSwiggyCartQuantity(descriptorChecked, candidate) is SwiggyCartQuantityResolution.Held)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val actual = resolveSwiggyCartQuantity(checked, candidate)
                if (count % perSku == 0 && count / perSku in 1..20)
                    assertEquals("$source / $perSku", SwiggyCartQuantityResolution.Exact(count / perSku), actual)
                else assertTrue("$source must hold for non-divisible SKU size: $actual", actual is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun typedSourcePieceRequestsRejectAmbiguousOrMutatedSourceEvidence() {
        listOf(
            "2 pieces eggs 3",
            "about 2 pieces eggs",
            "not 2 pieces eggs",
            "5 star 2 pieces",
            "2 pieces eggs x 2",
        ).forEach { source ->
            val parsed = InstructionParser.parse(source).single()
            val deterministic = com.example.beta.automation.ParsedItem(
                rawText = source,
                query = parsed.query,
                quantity = parsed.quantity,
                retailPackCount = parsed.retailPackCount,
            ).withDeterministicSourceEvidence(source, source, ownsClause = true)
            assertNull("$source: deterministic binder must not create a piece receipt",
                deterministic.validSourcePieceAmount())
            val candidate = pieceCandidate(label = "Eggs 1 Piece",
                fields = CatalogueQuantityFields(variantName = "1 piece"))
            val checked = assessSwiggyIntentQuantity(parsed, Recommendations(listOf(candidate)))
            assertFalse("$source must not be ready as a source-piece conversion",
                checked.validSourcePieceAmount() != null)
            assertTrue("$source must remain held",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }

        val source = "2 pieces eggs"
        val original = InstructionParser.parse(source).single()
        fun deterministic(retailPackCount: Boolean = false) =
            com.example.beta.automation.ParsedItem(
                rawText = source,
                query = original.query,
                quantity = original.quantity,
                retailPackCount = retailPackCount,
            ).withDeterministicSourceEvidence(source, source, ownsClause = true)
        val mismatched = deterministic().copy(quantity = Quantity.Count(3))
        assertNull("mismatched count cannot validate original source receipt", mismatched.validSourcePieceAmount())
        val retailFlag = deterministic(retailPackCount = true)
        assertNull("retail-pack role cannot coexist with source loose pieces", retailFlag.validSourcePieceAmount())
        val candidate = pieceCandidate(label = "Eggs 1 Piece",
            fields = CatalogueQuantityFields(variantName = "1 piece"))
        for (item in listOf(mismatched, retailFlag)) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }

        val lemonSource = "two pieces of lemon"
        val lemonCandidate = pieceCandidate(label = "Lemon 1 Piece",
            fields = CatalogueQuantityFields(variantName = "1 piece"))
        val lemonCases = listOf(
            com.example.beta.automation.ParsedItem(
                rawText = lemonSource, query = "lemon 3", quantity = Quantity.Count(2),
            ),
            com.example.beta.automation.ParsedItem(
                rawText = lemonSource, query = "2 2 pieces of lemon", quantity = Quantity.Count(2),
            ),
            com.example.beta.automation.ParsedItem(
                rawText = lemonSource, query = "lemon", quantity = Quantity.Count(3),
            ),
        )
        lemonCases.forEachIndexed { index, base ->
            val item = base.withDeterministicSourceEvidence(lemonSource, lemonSource, ownsClause = true)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(lemonCandidate)))
            val result = resolveSwiggyCartQuantity(checked, lemonCandidate)
            if (index == 1 && result !is SwiggyCartQuantityResolution.Held) {
                // Retrieval duplicates cannot survive the source-owned rebuild.
                assertEquals("lemon", item.query)
                assertEquals("lemon", item.strictMatchPhrase)
                assertEquals(Quantity.Count(2), item.quantity)
                assertEquals(lemonSource, item.rawText)
            } else assertTrue("lemon numeric identity case $index must be held; query=${item.query}; result=$result",
                result is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun parserCharacterizesPieceSourceFormsWithoutManufacturingPurchaseAuthority() {
        val sources = listOf(
            "2 pieces eggs", "eggs 2 pieces", "two pieces of lemon", "12 pcs candles",
            "2 dozen eggs", "eggs 2 dozen", "eggs 1.5 dozen",
        )
        val parsedItems = sources.flatMap { source ->
            val parsed = InstructionParser.parse(source)
            assertTrue("Characterization source must produce rows: $source", parsed.isNotEmpty())
            parsed.map { source to it }
        }
        val diagnostics = parsedItems.map { (source, parsed) ->
            "$source => quantity=${parsed.quantity};logicalPieces=${parsed.logicalPieceCount};validReceipt=${parsed.validSourcePieceAmount() != null}"
        }
        val countAndReceipt = parsedItems.filter { (_, parsed) ->
            parsed.quantity is Quantity.Count && parsed.validSourcePieceAmount() != null
        }.map { it.first }
        println("Parser Count+receipt cases=$countAndReceipt; observations=$diagnostics")
        parsedItems.forEach { (_, parsed) ->
            if (parsed.quantity == Quantity.Default) assertNull(parsed.validSourcePieceAmount())
            parsed.validSourcePieceAmount()?.let { amount ->
                assertEquals(amount.value.intValueExact(), (parsed.quantity as Quantity.Count).n)
                assertEquals(parsed.rawText, amount.source)
            }
        }
    }

    @Test
    fun receiptOwnsUntouchedCountMarkerIdentityAndOrder() {
        val cases = listOf(
            Triple(" 4 pieces of lemon! ", "4", SourcePieceForm.PREFIX),
            Triple(" lemon, 4 pieces! ", "4", SourcePieceForm.POSTFIX),
            Triple(" eggs 1.5 dozen. ", "1.5 dozen", SourcePieceForm.POSTFIX),
        )
        for ((source, atom, form) in cases) {
            val receipt = requireNotNull(sourcePiecePurchaseReceipt(source))
            assertEquals(source, receipt.source)
            assertEquals(atom, source.substring(receipt.amount.startUtf16, receipt.amount.endUtf16))
            assertEquals(form, receipt.form)
            assertEquals(if ("lemon" in source) "lemon" else "eggs", source.substring(receipt.identity))
            assertEquals(if ("dozen" in source) null else "pieces", receipt.marker?.let { source.substring(it) })
            assertEquals("SourcePiecePurchaseReceipt(<redacted>)", receipt.toString())
        }
    }

    @Test
    fun pieceIdentityCannotBeRewrittenByChangingBothQueryAndProof() {
        for (source in listOf("24 pieces eggs", "eggs 24 pieces")) {
            val original = sourcePiecesItem(source, 24)
            val proof = requireNotNull(original.intentQuantityProvenance)
            val forged = original.copy(
                query = "candles", strictMatchPhrase = "candles",
                intentQuantityProvenance = proof.copy(query = "candles", strictMatchPhrase = "candles"),
            )
            val candidate = pieceCandidate(label = "Candles 6 Pieces")
            val checked = assessSwiggyIntentQuantity(forged, Recommendations(listOf(candidate)))
            assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }
        val retained = responseItem("organic eggs 24 pieces", "eggs", 24, "count")
        assertEquals("organic eggs", retained.query)
        assertEquals(retained.query, retained.strictMatchPhrase)
    }

    @Test
    fun sharedSourcePieceCasesMatchAndroidReviewReadiness() {
        val cases = loadFixture()
        assertEquals(setOf(false, true), cases.map { it.accepted }.toSet())
        var rejectedCasesParsed = 0

        cases.forEach { case ->
            val parsed = runCatching {
                GroceryIntentResponse.parse(responseBody(case), case.source)
            }.getOrNull()
            if (case.accepted) {
                val response = requireNotNull(parsed) { "${case.id}: expected strict response parse" }
                val preparation = response.draft.prepareForReview()
                assertEquals("${case.id}: one prepared row", 1, preparation.items.size)
                assertTrue("${case.id}: no additional text review", preparation.unresolvedText.isEmpty())
                assertTrue("${case.id}: no excluded text", preparation.excludedText.isEmpty())
                val item = preparation.items.single()
                assertTrue("${case.id}: source proves requested loose pieces", item.logicalPieceCount)
                assertTrue("${case.id}: exact quantity requires source proof", item.requiresIntentQuantityProof)
                assertEquals(IntentSourceCountRole.REQUESTED_PIECES,
                    item.intentQuantityProvenance?.sourceCountRole)
                assertEquals(Quantity.Count(case.quantity), item.quantity)
                assertEquals(null, swiggyMcpItemValidationMessage(case.source, preparation.items))
            } else if (parsed != null) {
                rejectedCasesParsed++
                val preparation = runCatching { parsed.draft.prepareForReview() }.getOrNull()
                val reviewReady = preparation != null && preparation.items.isNotEmpty() &&
                    preparation.unresolvedText.isEmpty() &&
                    swiggyMcpItemValidationMessage(case.source, preparation.items) == null
                assertFalse("${case.id}: rejected source must not become review-ready", reviewReady)
            }
        }
        assertTrue("Fixture should exercise held-after-parse cases", rejectedCasesParsed > 0)
    }

    @Test
    fun sourcePieceContextCasesMatchReceiptAndActualAndroidGrounding() {
        loadContextFixture().forEach { case ->
            val receipt = requireNotNull(sourcePiecePurchaseReceipt(case.source)) { "${case.id}: receipt" }
            val expectedIssue = case.expectedIssue.takeUnless { it == "safe" }
            assertEquals("${case.id}: context helper", expectedIssue,
                sourcePieceInstructionIssue(case.instruction, receipt))

            val items = case.basket.map { row ->
                GroceryIntentItem(
                    name = row.name,
                    quantity = row.quantity?.let { BigDecimal.valueOf(it.toLong()) },
                    quantityUnit = row.quantityUnit,
                    packValue = null,
                    packUnit = "unspecified",
                    required = emptyList(),
                    excluded = emptyList(),
                    sourceText = row.source,
                    needsClarification = false,
                )
            }
            val grounding = groceryIntentGrounding(case.instruction, items)
            if (expectedIssue == null) {
                assertTrue("${case.id}: safe context remains grounded", grounding.rejected.isEmpty())
            } else {
                assertTrue("${case.id}: unsafe postfix rejected by Android grounding",
                    case.source in grounding.rejected)
            }
        }
    }

    @Test
    fun sourcePieceContextAcceptsCrLfClauseBoundary() {
        val receipt = requireNotNull(sourcePiecePurchaseReceipt("eggs 12 pieces"))
        assertNull(sourcePieceInstructionIssue("eggs 12 pieces\r\nmilk", receipt))
    }

    @Test
    fun sourcePieceHelpersAcceptClosedNeutralSuffixesWithoutChangingQuote() {
        val source = "2 dozen eggs"
        val receipt = requireNotNull(sourcePiecePurchaseReceipt(source))
        val instructions = listOf(
            "$source bhi.",
            "$source too.",
            "$source as well.",
            "$source le aana.",
            "rice, $source bhi.",
            "🛒, $source bhi.",
        )
        instructions.forEach { instruction ->
            assertTrue(instruction, groceryIntentSourceClauseEndProven(instruction, source, receipt.terminalEnd))
            assertTrue(instruction, groceryIntentSourceClauseContextProven(instruction, source, receipt.terminalEnd))
            assertNull(instruction, sourcePieceInstructionIssue(instruction, receipt))
            assertEquals(source, receipt.source)
        }

        data class PairedShell(val source: String, val instruction: String, val amount: String, val identity: String)
        val paired = listOf(
            PairedShell("2 dozen eggs", "bazaar se 2 dozen eggs le aana.", "2 dozen", "eggs"),
            PairedShell("2 pieces candles", "please bazar se 2 pieces candles la do.", "2", "candles"),
            PairedShell("one dozen lemons", "ghar ke liye mandi se one dozen lemons le aaiyega.", "one dozen", "lemons"),
            PairedShell("2 dozen eggs", "milk aur bazaar se 2 dozen eggs le aana.", "2 dozen", "eggs"),
            PairedShell("2 dozen eggs", "milk, bazaar se 2 dozen eggs le aana.", "2 dozen", "eggs"),
        )
        val contextFailures = mutableListOf<String>()
        paired.forEach { case ->
            val pairedReceipt = requireNotNull(sourcePiecePurchaseReceipt(case.source))
            val quoteStart = case.instruction.indexOf(case.source)
            assertTrue("${case.instruction}: quote exists", quoteStart >= 0)
            if (!groceryIntentSourceClauseEndProven(case.instruction, case.source, pairedReceipt.terminalEnd))
                contextFailures += "${case.instruction}: terminal boundary rejected"
            if (!groceryIntentSourceClauseContextProven(case.instruction, case.source, pairedReceipt.terminalEnd))
                contextFailures += "${case.instruction}: neutral context rejected"
            if (sourcePieceInstructionIssue(case.instruction, pairedReceipt) != null)
                contextFailures += "${case.instruction}: source-piece context rejected"
            assertEquals(case.source, pairedReceipt.source)
            assertEquals(case.amount, case.source.substring(pairedReceipt.amount.startUtf16, pairedReceipt.amount.endUtf16))
            assertEquals(case.identity, case.source.substring(pairedReceipt.identity))
            assertEquals(case.amount,
                case.instruction.substring(quoteStart + pairedReceipt.amount.startUtf16,
                    quoteStart + pairedReceipt.amount.endUtf16))
            assertEquals(case.identity,
                case.instruction.substring(quoteStart + pairedReceipt.identity.first,
                    quoteStart + pairedReceipt.identity.last + 1))
        }
        assertTrue("Paired supported shells should retain full context: $contextFailures", contextFailures.isEmpty())
    }

    @Test
    fun pairedSourcePieceShellsRejectMissingSuffixAndUnownedContext() {
        val source = "2 dozen eggs"
        val receipt = requireNotNull(sourcePiecePurchaseReceipt(source))
        val rejected = listOf(
            "bazaar se 2 dozen eggs.",
            "do not get 2 dozen eggs le aana.",
            "bazaar se 2 dozen eggs le aana if cheap.",
            "bazaar se 2 dozen eggs without sugar le aana.",
            "bazaar se 2 dozen eggs le aana 2 milk.",
            "bazaar se 2 dozen eggs le aana, bazaar se 2 dozen eggs le aana.",
            "2 dozen eggs bread le aana.",
            "bazaar se 2 dozen eggs. le aana.",
            "dukaan se 2 dozen eggs le aana.",
            "milk aur not bazaar se 2 dozen eggs le aana.",
            "milk aur bazaar se 2 dozen eggs. le aana.",
        )
        rejected.forEach { instruction ->
            assertFalse("${instruction}: no context borrowing",
                groceryIntentSourceClauseContextProven(instruction, source, receipt.terminalEnd))
            assertEquals("${instruction}: source piece issue", "source_piece_context_unproven",
                sourcePieceInstructionIssue(instruction, receipt))
            assertEquals(source, receipt.source)
        }
    }

    @Test
    fun pairedHindiRequestSurvivesV1PreparationAndExactCatalogueCheck() {
        val cases = listOf(
            Triple("bazaar se 2 dozen eggs le aana.", "2 dozen eggs", 24),
            Triple("ghar ke liye mandi se one dozen lemons le aaiyega.", "one dozen lemons", 12),
        )
        for ((instruction, source, count) in cases) {
            val identity = requireNotNull(sourcePiecePurchaseReceipt(source)).literalIdentity()
            val fixture = FixtureCase("paired-preparation", source, identity, count, "piece", null, "unspecified", true)
            val preparation = GroceryIntentResponse.parse(responseBody(fixture, instruction), instruction)
                .draft.prepareForReview()
            // V1's separate lexical coverage still presents the courtesy as
            // wording to acknowledge. This test proves preparation and exact
            // catalogue arithmetic, not removal of that existing review step.
            assertEquals(uncoveredGroceryIntentSegments(instruction, listOf(source)), preparation.unresolvedText)
            val item = preparation.items.single()
            assertEquals(source, item.rawText)
            assertEquals(Quantity.Count(count), item.quantity)
            val candidate = pieceCandidate(label = "$identity 6 Pieces")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertEquals(SwiggyCartQuantityResolution.Exact(count / 6), resolveSwiggyCartQuantity(checked, candidate))
        }
    }

    @Test
    fun sourcePieceHelpersRejectUnclosedOrDuplicatedInstructionContext() {
        val source = "2 dozen eggs"
        val receipt = requireNotNull(sourcePiecePurchaseReceipt(source))
        val rejected = listOf(
            "$source bhi if cheap.",
            "$source with no added sugar.",
            "$source le aana 2 milk.",
            "$source tonight.",
            "$source 500 g each.",
            "$source without shell.",
            "$source or 1 dozen eggs.",
            "$source bhi 🛒.",
            "$source white",
            "$source, $source.",
        )
        rejected.forEach { instruction ->
            assertFalse(instruction, groceryIntentSourceClauseEndProven(instruction, source, receipt.terminalEnd))
            assertEquals(instruction, "source_piece_context_unproven",
                sourcePieceInstructionIssue(instruction, receipt))
            assertEquals(source, receipt.source)
        }
        assertTrue("Leading negation still has a terminal boundary",
            groceryIntentSourceClauseEndProven("do not get $source bhi.", source, receipt.terminalEnd))
        assertFalse("Leading negation is not neutral context",
            groceryIntentSourceClauseContextProven("do not get $source bhi.", source, receipt.terminalEnd))

        val consumedEnd = source.length
        listOf(" red", " and 4 apples", ", extra").forEach { remainder ->
            val croppedQuote = source + remainder
            val croppedInstruction = "$croppedQuote bhi."
            assertFalse("Terminal proof cannot skip text remaining inside the quote",
                groceryIntentSourceClauseEndProven(croppedInstruction, croppedQuote, consumedEnd))
            assertFalse("Full context proof cannot skip text remaining inside the quote",
                groceryIntentSourceClauseContextProven(croppedInstruction, croppedQuote, consumedEnd))
        }
        listOf("", " ", "!", ". ", ",").forEach { trailer ->
            val quote = source + trailer
            assertTrue(groceryIntentSourceClauseEndProven(quote, quote, source.length))
        }
        assertTrue("External list boundary still belongs to the full-basket coverage gate",
            groceryIntentSourceClauseEndProven("$source, 4 apples", source, source.length))
    }

    @Test
    fun sourcePieceContextRejectsInstructionBeyondEvidenceLengthLimit() {
        val receipt = requireNotNull(sourcePiecePurchaseReceipt("eggs 12 pieces"))
        val instruction = "eggs 12 pieces\r\n" + "milk ".repeat(1200)
        assertTrue(instruction.length > 6000)
        assertEquals("source_piece_context_unproven",
            sourcePieceInstructionIssue(instruction, receipt))
    }

    @Test
    fun sourceProvedPiecesConvertOnlyByExactCataloguePieceDivisorWithinCartLimit() {
        val sixPieces = pieceCandidate()
        val cases = listOf(
            Triple("2 dozen eggs", 24, 4),
            Triple("eggs 24 pieces", 24, 4),
            Triple("eggs, 24 pieces", 24, 4),
            Triple("120 pieces eggs", 120, 20),
        )
        cases.forEach { (source, count, expectedCartUnits) ->
            val item = sourcePiecesItem(source, count)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(sixPieces)))
            assertEquals(SwiggyCartQuantityResolution.Exact(expectedCartUnits),
                resolveSwiggyCartQuantity(checked, sixPieces))
            assertEquals(expectedCartUnits, swiggyRequestedCartQuantity(checked, sixPieces))
        }

        listOf("126 pieces eggs" to 126, "25 pieces eggs" to 25).forEach { (source, count) ->
            val item = sourcePiecesItem(source, count)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(sixPieces)))
            assertTrue("$source must not become cart units",
                resolveSwiggyCartQuantity(checked, sixPieces) is SwiggyCartQuantityResolution.Held)
        }

        val suffixSource = "eggs 24 pieces"
        val suffixItem = sourcePiecesItem(suffixSource, 24)
        val titleOnly = pieceCandidate(label = "Fresh Eggs 6 Pieces", evidence = null)
        val titleOnlyChecked = assessSwiggyIntentQuantity(suffixItem, Recommendations(listOf(titleOnly)))
        assertTrue("suffix source cannot authorize title-only divisors",
            resolveSwiggyCartQuantity(titleOnlyChecked, titleOnly) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun bareModelPieceEnumDoesNotCreateTheNewRequestedPieceRole() {
        val source = "2 milk"
        val item = responseItem(source, "milk", 2, "piece")
        assertFalse(item.logicalPieceCount)
        // The source owns the number, not the model's claimed piece role.
        assertTrue(item.requiresIntentQuantityProof)
        assertEquals(com.example.beta.automation.IntentQuantityOrigin.SOURCE_NUMERIC,
            item.intentQuantityProvenance?.origin)
        assertNull(item.intentQuantityProvenance?.sourceCountRole)
        val candidate = pieceCandidate(label = "Fresh Milk 6 Pieces")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertFalse(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Exact)
    }

    @Test
    fun reassessmentCannotDowngradeSourcePiecesToTitleOnlyLegacyConversion() {
        val item = sourcePiecesItem("2 dozen eggs", 24)
        val proof = requireNotNull(item.intentQuantityProvenance)
        val titleOnly = pieceCandidate(evidence = null)
        val withoutMarker = item.copy(intentQuantityProvenance = proof.copy(sourceCountRole = null))
        val mutations = listOf(withoutMarker,
            withoutMarker.copy(logicalPieceCount = false,
                intentQuantityProvenance = proof.copy(sourceCountRole = null, logicalPieceCount = false)),
            withoutMarker.copy(requiresIntentQuantityProof = false),
            withoutMarker.copy(intentQuantityProvenance = null))
        mutations.forEach { mutated ->
            val reassessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(titleOnly)))
            assertTrue(resolveSwiggyCartQuantity(reassessed, titleOnly) is SwiggyCartQuantityResolution.Held)
        }
        val complete = pieceCandidate()
        val reassessed = assessSwiggyIntentQuantity(withoutMarker, Recommendations(listOf(complete)))
        assertEquals(SwiggyCartQuantityResolution.Exact(4), resolveSwiggyCartQuantity(reassessed, complete))
    }

    @Test
    fun rawCountGuardExemptsOnlyOwnedCompoundAmountNotSiblingCounts() {
        val item = sourcePiecesItem("24 dozen eggs", 288)
        assertNull(swiggyMcpItemValidationMessage(item.rawText, listOf(item)))
        assertNotNull(swiggyMcpItemValidationMessage("${item.rawText}, 25 milk", listOf(item)))
    }

    @Test
    fun correctedPieceRequestsCannotReuseTheObsoleteLocalDivisorReceipt() {
        val item = sourcePiecesItem("6 pieces eggs", 6)
        val original = requireNotNull(item.intentQuantityProvenance)
        val corrected = requireNotNull(sourcePiecePurchaseAmount("12 pieces eggs"))
        val changed = item.copy(quantity = Quantity.Count(12), intentQuantityProvenance = original.copy(
            sourceCountRole = null,
            origin = com.example.beta.automation.IntentQuantityOrigin.CORRECTION,
            resolvedQuantity = Quantity.Count(12), corrected = corrected,
        ))
        val candidate = pieceCandidate()
        val checked = assessSwiggyIntentQuantity(changed, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun missingOrMutatedCatalogueAndSourceProofStayHeld() {
        val source = "2 dozen eggs"
        val item = sourcePiecesItem(source, 24)
        val complete = pieceCandidate()
        val titleOnly = pieceCandidate(label = "Fresh Eggs 6 Pieces", evidence = null)
        val conflictingCatalogue = pieceCandidate(
            fields = CatalogueQuantityFields(variantName = "6 pcs"),
            label = "Fresh Eggs 8 Pieces",
        )

        listOf(
            "missing metadata" to pieceCandidate(evidence = null, label = "Fresh Eggs"),
            "title-only piece text" to titleOnly,
            "mutated catalogue count" to conflictingCatalogue,
        ).forEach { (label, candidate) ->
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("$label must be held",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }

        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(complete)))
        val provenance = requireNotNull(checked.intentQuantityProvenance)
        val local = requireNotNull(provenance.local)
        val mutations = listOf(
            checked.copy(rawText = "24 eggs"),
            checked.copy(intentQuantityProvenance = provenance.copy(
                local = local.copy(value = BigDecimal("25")),
            )),
            checked.copy(intentQuantityProvenance = provenance.copy(
                local = local.copy(startUtf16 = local.startUtf16 + 1),
            )),
            checked.copy(requiresIntentQuantityProof = false),
            checked.copy(intentQuantityProvenance = null),
        )
        mutations.forEachIndexed { index, mutated ->
            assertTrue("mutated source/provenance $index must be held",
                resolveSwiggyCartQuantity(mutated, complete) is SwiggyCartQuantityResolution.Held)
        }
    }

    private data class FixtureCase(
        val id: String,
        val source: String,
        val name: String,
        val quantity: Int,
        val quantityUnit: String,
        val packValue: Int?,
        val packUnit: String,
        val accepted: Boolean,
    )

    private data class ContextFixtureCase(
        val id: String,
        val instruction: String,
        val source: String,
        val name: String,
        val quantity: Int,
        val quantityUnit: String,
        val expectedIssue: String,
        val basket: List<ContextFixtureItem>,
    )

    private data class ContextFixtureItem(
        val name: String,
        val quantity: Int?,
        val quantityUnit: String,
        val source: String,
    )

    private fun loadFixture(): List<FixtureCase> {
        val relative = "scripts/fixtures/intent_source_piece_oct28.json"
        val file = listOf(File("../$relative"), File(relative)).firstOrNull(File::isFile)
        assertNotNull("Shared source-piece fixture missing: $relative", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        assertEquals("beta-grocery-intent-source-piece-test-v1", stringAt(root["schema"]))
        return arrayAt(root["cases"]).map { value ->
            val row = objectAt(value)
            FixtureCase(
                id = stringAt(row["id"]),
                source = stringAt(row["source"]),
                name = stringAt(row["name"]),
                quantity = numberAt(row["quantity"]),
                quantityUnit = stringAt(row["quantity_unit"]),
                packValue = (row["pack_value"] as? JsonValue.Num)?.raw?.toIntOrNull(),
                packUnit = stringAt(row["pack_unit"]),
                accepted = booleanAt(row["accepted"]),
            )
        }
    }

    private fun loadContextFixture(): List<ContextFixtureCase> {
        val relative = "scripts/fixtures/intent_source_piece_oct28.json"
        val file = listOf(File("../$relative"), File(relative)).firstOrNull(File::isFile)
        assertNotNull("Shared source-piece fixture missing: $relative", file)
        val root = objectAt(JsonParser(requireNotNull(file).readText(Charsets.UTF_8)).parseStrict())
        return arrayAt(root["context_cases"]).map { value ->
            val row = objectAt(value)
            val basket = (row["basket"] as? JsonValue.Arr)?.items?.map { basketValue ->
                val basketRow = objectAt(basketValue)
                ContextFixtureItem(
                    name = stringAt(basketRow["name"]),
                    quantity = (basketRow["quantity"] as? JsonValue.Num)?.raw?.toIntOrNull(),
                    quantityUnit = stringAt(basketRow["quantity_unit"]),
                    source = stringAt(basketRow["source_text"]),
                )
            } ?: listOf(ContextFixtureItem(
                name = stringAt(row["name"]),
                quantity = numberAt(row["quantity"]),
                quantityUnit = stringAt(row["quantity_unit"]),
                source = stringAt(row["source"]),
            ))
            ContextFixtureCase(
                id = stringAt(row["id"]),
                instruction = stringAt(row["instruction"]),
                source = stringAt(row["source"]),
                name = stringAt(row["name"]),
                quantity = numberAt(row["quantity"]),
                quantityUnit = stringAt(row["quantity_unit"]),
                expectedIssue = stringAt(row["expected_issue"]),
                basket = basket,
            )
        }
    }

    private fun sourcePiecesItem(source: String, count: Int): com.example.beta.automation.ParsedItem =
        responseItem(source, "eggs", count, "piece")

    private fun responseItem(
        source: String,
        name: String,
        quantity: Int,
        quantityUnit: String,
    ): com.example.beta.automation.ParsedItem {
        val case = FixtureCase("cart", source, name, quantity, quantityUnit, null, "unspecified", true)
        return GroceryIntentResponse.parse(responseBody(case), source).draft.prepareForReview().items.single()
    }

    private fun pieceCandidate(
        label: String = "Fresh Eggs 6 Pieces",
        fields: CatalogueQuantityFields? = CatalogueQuantityFields(variantName = "6 pcs"),
        evidence: CatalogueQuantityEvidence? = fields?.let {
            CatalogueQuantityEvidence(1, "search_variation", "spin-eggs", "sku-eggs", it)
        },
    ) = RecommendationCandidate(
        spinId = "spin-eggs", label = label, skuId = "sku-eggs", catalogueQuantity = evidence,
    )

    private fun responseBody(case: FixtureCase, instruction: String = case.source): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(instruction)},"items":[{"name":${jsonString(case.name)},"quantity":${case.quantity},"quantity_unit":${jsonString(case.quantityUnit)},"pack_value":${case.packValue ?: "null"},"pack_unit":${jsonString(case.packUnit)},"required":[],"excluded":[],"source_text":${jsonString(case.source)},"needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""

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
