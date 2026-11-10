package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentDraftTest {
    @Test
    fun nameOnlyTetraEvidencePreservesFormWithoutRequiringADuplicateTag() {
        val instruction = "5 milk tetta packs and 2 dark chocolate"
        val parsed = GroceryIntentResponse.parse(response(instruction, listOf(
            item("milk tetta packs", "5", quantityUnit = "pack", sourceText = "5 milk tetta packs"),
            item("dark chocolate", "2", quantityUnit = "count", sourceText = "2 dark chocolate"),
        ).joinToString(",")), instruction).draft.toParsedItems()
        assertEquals(2, parsed.size)
        assertEquals(Quantity.Count(5), parsed[0].quantity)
        assertEquals(Quantity.Count(2), parsed[1].quantity)
        assertTrue(parsed[0].retailPackCount)
        assertTrue("tetrapack" in swiggyIdentityTokens(parsed[0].strictMatchPhrase.orEmpty()))
        assertTrue(swiggyMatchesProductIdentity(parsed[0], "Amul Milk Tetra Pack 500 ml"))
        assertTrue(swiggyMatchesProductIdentity(parsed[0], "Amul Taaza Milk 1 L Tetra Pak"))
        listOf("Amul Milk Pouch 500 ml", "Amul Milk Bottle 500 ml", "Amul UHT Milk 500 ml").forEach {
            assertFalse(it, swiggyMatchesProductIdentity(parsed[0], it))
        }
    }

    @Test
    fun parsesExactReviewOnlyContractAndPreservesCountPackAndConstraints() {
        val response = GroceryIntentResponse.parse(
            response(
                instruction = "Two 750 ml sugar free milk tetra packs",
                item = item(
                    name = "milk", quantity = "2", quantityUnit = "pack", packValue = "750", packUnit = "ml",
                    required = "\"sugar_free\",\"tetra_pack\"", excluded = "\"added_sugar\"",
                    sourceText = "Two 750 ml sugar free milk tetra packs",
                ),
            ),
            "Two 750 ml sugar free milk tetra packs",
        )

        val parsed = response.draft.toParsedItems().single()
        assertEquals("milk sugar free tetra pack 750 ml", parsed.query)
        assertEquals("milk sugar free tetra pack 750 ml", parsed.strictMatchPhrase)
        assertEquals(Quantity.Count(2), parsed.quantity)
        assertTrue(parsed.retailPackCount)
        assertEquals(null, parsed.quantitySignal)
        assertEquals(null, swiggyMcpItemValidationMessage("", listOf(parsed)))
        assertEquals(listOf("added sugar"), parsed.avoidPhrases)
        assertEquals("model-x", response.model)
        assertEquals(mapOf("input_tokens" to 3.0, "output_tokens" to 5.0), response.usage)
    }

    @Test
    fun bareCountCannotBeSubmittedAsPackWhileCountControlsPass() {
        listOf("one samosa" to "samosa", "one yogurt" to "yogurt", "six eggs" to "eggs",
            "Can you get one samosa" to "samosa").forEach { (source, name) ->
            val count = if (source.startsWith("six")) "6" else "1"
            val pack = draft(source, item(name, count, quantityUnit = "pack", sourceText = source))
            val error = assertThrows(IntentNeedsReviewException::class.java) { pack.prepareForReview() }
            assertTrue(error.affectedSourceText.contains(source))

            val ordinaryCount = draft(source, item(name, count, quantityUnit = "count", sourceText = source))
            assertEquals(1, ordinaryCount.prepareForReview().items.size)
        }
    }

    @Test
    fun sharedHeadingOrNeighborPacketCannotProveBareItemPack() {
        val heading = "two packs: one milk and one yogurt"
        val shared = GroceryIntentResponse.parse(
            response(heading, listOf(
                item("milk", "1", quantityUnit = "pack", sourceText = "one milk"),
                item("yogurt", "1", quantityUnit = "pack", sourceText = "one yogurt"),
            ).joinToString(",")), heading,
        ).draft
        val sharedError = assertThrows(IntentNeedsReviewException::class.java) { shared.prepareForReview() }
        assertTrue(sharedError.affectedSourceText.contains("one milk"))
        assertTrue(sharedError.affectedSourceText.contains("one yogurt"))

        val neighbor = "one milk, one packet sugar"
        val adjacent = GroceryIntentResponse.parse(
            response(neighbor, listOf(
                item("milk", "1", quantityUnit = "pack", sourceText = "one milk"),
                item("packet sugar", "1", quantityUnit = "pack", sourceText = "one packet sugar"),
            ).joinToString(",")), neighbor,
        ).draft
        val neighborError = assertThrows(IntentNeedsReviewException::class.java) { adjacent.prepareForReview() }
        assertTrue(neighborError.affectedSourceText.contains("one milk"))
    }

    @Test
    fun convertsOnlyExactlyRepresentableFractionalMeasures() {
        val kg = parsed("1.5 kg rice", quantity = "1.5", quantityUnit = "kg", itemName = "rice").toParsedItems().single()
        assertEquals(Quantity.Weight(1500), kg.quantity)
        val literPack = parsed(
            "juice 0.5 l one bottle", quantity = "1", quantityUnit = "count", packValue = "0.5", packUnit = "l",
        ).toParsedItems().single()
        assertEquals("juice 500 ml bottle", literPack.strictMatchPhrase)
        assertThrows(IllegalArgumentException::class.java) {
            parsed("0.5 g spice", quantity = "0.5", quantityUnit = "g")
        }
    }

    @Test
    fun representsPieceCountAsPackConstraintNotRequestedCartQuantity() {
        val parsed = parsed(
            "one pack of 25 apples",
            quantity = "1",
            quantityUnit = "pack",
            packValue = "25",
            packUnit = "piece",
            itemName = "apple",
        ).toParsedItems().single()

        assertEquals("apple 25 pieces", parsed.query)
        assertEquals(parsed.query, parsed.strictMatchPhrase)
        assertEquals(Quantity.Count(1), parsed.quantity)
        assertTrue(parsed.retailPackCount)
        assertEquals(null, swiggyMcpItemValidationMessage("", listOf(parsed)))
    }

    @Test
    fun packContentsDoNotInventAnUnspokenPurchaseCount() {
        val source = "pack of 25 apples"
        val unspecified = parsed(
            source,
            quantity = "null",
            quantityUnit = "unspecified",
            packValue = "25",
            packUnit = "piece",
            itemName = "apples",
        ).toParsedItems().single()
        assertEquals("apples 25 pieces", unspecified.query)
        assertEquals(Quantity.Default, unspecified.quantity)
        assertFalse(unspecified.retailPackCount)

        val inventedCount = parsed(
            source,
            quantity = "1",
            quantityUnit = "count",
            packValue = "25",
            packUnit = "piece",
            itemName = "apples",
        )
        assertThrows(IntentNeedsReviewException::class.java) { inventedCount.prepareForReview() }
    }

    @Test
    fun unspokenCountCannotMaskAProductNameBeforePiecePackEvidence() {
        listOf(
            Triple("BrandA remote pack of 2 batteries", "BrandA remote batteries", "batteries"),
            Triple("BrandA cough drops pack of 24 lozenges", "BrandA cough drops lozenges", "lozenges"),
            Triple("one BrandA remote pack of 2 batteries", "BrandA remote batteries", "batteries"),
            Triple("BrandA remote one pack of 2 batteries", "BrandA remote batteries", "batteries"),
        ).forEach { (source, name, _) ->
            val draft = parsed(
                source,
                quantity = if (source.startsWith("one ") || " one pack " in source) "1" else "null",
                quantityUnit = if (source.startsWith("one ") || " one pack " in source) "count" else "unspecified",
                packValue = if ("24" in source) "24" else "2",
                packUnit = "piece",
                itemName = name,
            )
            assertThrows("a product prefix must not scope a separate contents noun: $source",
                IntentNeedsReviewException::class.java) { draft.prepareForReview() }
        }
    }

    @Test
    fun preservesRequestedQuantityOfMultiplePacksOfPieces() {
        val parsed = parsed(
            "two packs of 25 apples",
            quantity = "2",
            quantityUnit = "pack",
            packValue = "25",
            packUnit = "piece",
            itemName = "apple",
        ).toParsedItems().single()

        assertEquals("apple 25 pieces", parsed.query)
        assertEquals(Quantity.Count(2), parsed.quantity)
        assertTrue(parsed.retailPackCount)

        val eggs = parsed(
            "two packs of 6 eggs",
            quantity = "2",
            quantityUnit = "pack",
            packValue = "6",
            packUnit = "piece",
            itemName = "eggs",
        ).toParsedItems().single()
        assertEquals(Quantity.Count(2), eggs.quantity)
        assertTrue(eggs.retailPackCount)
    }

    @Test
    fun discretePiecePackMustBelongToOneExplicitContainer() {
        val sources = listOf(
            "one BrandA remote, 2 batteries",
            "one BrandA remote; 2 batteries",
            "one BrandA remote and 2 batteries",
            "one BrandA remote with 2 batteries",
            "one BrandA remote plus 2 batteries",
            "one BrandA remote, a pack of 2 batteries",
            "one BrandA remote, one pack of 2 batteries",
            "one BrandA remote a pack of 2 batteries",
        )
        sources.forEach { source ->
            val draft = parsed(
                source,
                quantity = "1",
                quantityUnit = "count",
                packValue = "2",
                packUnit = "piece",
                itemName = "BrandA remote batteries",
            )
            val error = assertThrows(IntentNeedsReviewException::class.java) { draft.prepareForReview() }
            assertTrue("held source should be identified: $source", error.affectedSourceText.contains(source))
        }

        val looseEggs = parsed("six eggs", quantity = "6", quantityUnit = "count", itemName = "eggs")
            .prepareForReview().items.single()
        assertEquals(Quantity.Count(6), looseEggs.quantity)
        assertFalse(looseEggs.retailPackCount)

        listOf(
            "one pack of 2 cups of tea" to "tea",
            "one cup of tea pack of 2 tea bags" to "tea bags",
            "one 24-pack BrandA drops" to "BrandA drops",
        ).forEach { (source, name) ->
            val ambiguous = parsed(
                source,
                quantity = "1",
                quantityUnit = "count",
                packValue = if ("24-pack" in source) "24" else "2",
                packUnit = "piece",
                itemName = name,
            )
            assertThrows("must not infer piece-pack role: $source", IntentNeedsReviewException::class.java) {
                ambiguous.prepareForReview()
            }
        }
    }

    @Test
    fun piecePackContentsMustBeScopedToTheProductOrGenericCount() {
        listOf(
            Triple("one BrandA remote pack of 2 batteries", "BrandA remote batteries", "batteries"),
            Triple("one BrandA remote pack of 24 tablets", "BrandA remote tablets", "tablets"),
            Triple("one BrandA cough drops pack of 24 lozenges", "BrandA cough drops lozenges", "lozenges"),
            Triple("one BrandA apple juice pack of 6 orange juice", "BrandA apple juice orange juice", "orange juice"),
        ).forEach { (source, name, _) ->
            val draft = parsed(
                source,
                quantity = "1",
                quantityUnit = "count",
                packValue = when {
                    "of 24 " in source -> "24"
                    "of 6 " in source -> "6"
                    else -> "2"
                },
                packUnit = "piece",
                itemName = name,
            )
            val error = assertThrows(IntentNeedsReviewException::class.java) { draft.prepareForReview() }
            assertTrue("held source should be identified: $source", error.affectedSourceText.contains(source))
        }

        listOf(
            "one BrandA eggs pack of 6 eggs" to "BrandA eggs",
            "one BrandA batteries pack of 2 batteries" to "BrandA batteries",
            "one BrandA cough lozenges pack of 24 lozenges" to "BrandA cough lozenges",
            "one BrandA eggs 6 pieces pack" to "BrandA eggs",
            "one BrandA eggs 6 pieces pack of eggs" to "BrandA eggs",
            "one BrandA eggs pack of 6 pieces" to "BrandA eggs",
            "one BrandA eggs pack of 6" to "BrandA eggs",
        ).forEach { (source, name) ->
            val result = parsed(
                source,
                quantity = "1",
                quantityUnit = "count",
                packValue = when {
                    "24" in source -> "24"
                    "2 batteries" in source -> "2"
                    else -> "6"
                },
                packUnit = "piece",
                itemName = name,
            ).toParsedItems().single()
            assertEquals(Quantity.Count(1), result.quantity)
            assertFalse("count purchase quantity is distinct from retail-pack quantity", result.retailPackCount)
        }

        val suffixBypass = parsed(
            "one BrandA remote 2 pcs pack batteries",
            quantity = "1",
            quantityUnit = "count",
            packValue = "2",
            packUnit = "piece",
            itemName = "BrandA remote batteries",
        )
        assertThrows(IntentNeedsReviewException::class.java) { suffixBypass.prepareForReview() }
    }

    @Test
    fun wordedPiecePackCountsRemainDistinctFromPurchaseCountAndMustMatchSource() {
        val source = "two packs of twenty five apples"
        val converted = parsed(
            source,
            quantity = "2",
            quantityUnit = "pack",
            packValue = "25",
            packUnit = "piece",
            itemName = "apples",
        ).toParsedItems().single()
        assertEquals("apples 25 pieces", converted.query)
        assertEquals(Quantity.Count(2), converted.quantity)
        assertTrue(converted.retailPackCount)

        val mismatch = parsed(
            source,
            quantity = "2",
            quantityUnit = "pack",
            packValue = "24",
            packUnit = "piece",
            itemName = "apples",
        )
        assertThrows(IntentNeedsReviewException::class.java) { mismatch.prepareForReview() }
    }

    @Test
    fun rejectsFractionalPerPackPieceCountInsteadOfRounding() {
        assertThrows(IllegalArgumentException::class.java) {
            parsed(
                "one pack of 2.5 apples",
                quantity = "1",
                quantityUnit = "pack",
                packValue = "2.5",
                packUnit = "piece",
                itemName = "apple",
            )
        }
    }

    @Test
    fun preservesUnspecifiedQuantityAndDoesNotInventDefaultCount() {
        val item = parsed("some rice", quantity = "null", quantityUnit = "unspecified").toParsedItems().single()
        assertEquals(Quantity.Default, item.quantity)
        assertFalse(item.retailPackCount)
    }

    @Test
    fun dietaryConstraintsCannotUseFuzzyProductMatching() {
        val item = parsed("organic rice", required = "\"organic\"").toParsedItems().single()
        assertEquals(item.query, item.strictMatchPhrase)
        assertFalse(swiggyMatchesProductIdentity(item, "ordinary rice"))
    }

    @Test
    fun descriptorsAndMeasuresInNameAreEnforcedEvenWithoutStructuredTags() {
        val size = parsed("one 10g coffee bar", itemName = "coffee bar 10g").toParsedItems().single()
        assertEquals(size.query, size.strictMatchPhrase)
        assertTrue(swiggyMatchesProductIdentity(size, "coffee bar 10 g"))
        assertFalse(swiggyMatchesProductIdentity(size, "coffee bar 50 g"))
        val descriptor = parsed("one certified ginger", itemName = "certified ginger").toParsedItems().single()
        assertTrue(swiggyMatchesProductIdentity(descriptor, "certified ginger"))
        assertFalse(swiggyMatchesProductIdentity(descriptor, "ginger"))
    }

    @Test
    fun ambiguousMedicineStopsBeforeMatching() {
        val draft = draft(
            "woh cough wali goli", item(
                name = "cough tablet", quantity = "null", quantityUnit = "unspecified",
                sourceText = "woh cough wali goli", needsClarification = true,
            ), modelNeedsClarification = true,
        )
        val error = assertThrows(IntentNeedsReviewException::class.java) { draft.toParsedItems() }
        assertTrue(error.affectedSourceText.contains("woh cough wali goli"))
    }

    @Test
    fun omittedProductRequiresCombinedReviewBeforeMatching() {
        val value = draft("please get 2 milk and one samosa", item("milk", "2", sourceText = "2 milk"))
        val error = assertThrows(IntentNeedsReviewException::class.java) { value.toParsedItems() }
        assertEquals(listOf("one samosa"), error.affectedSourceText)
        val preparation = value.prepareForReview()
        assertEquals(1, preparation.items.size)
        assertEquals(listOf("one samosa"), preparation.unresolvedText)
    }

    @Test
    fun v1PreparationPreservesItemsBesideRecognizedListFraming() {
        val cases = listOf(
            "I have written it down: 2 milk, 1 bread" to GroceryIntentCoverageSpan(0, "I have written it down: ".length),
            "Please write the following items down for us: 2 milk, 1 bread" to GroceryIntentCoverageSpan(0, "Please write the following items down for us: ".length),
            "I need a household shopping list: 2 milk, 1 bread" to GroceryIntentCoverageSpan(0, "I need a household shopping list: ".length),
            "2 milk, 1 bread cart mein daal do please" to GroceryIntentCoverageSpan("2 milk, 1 bread ".length, "2 milk, 1 bread cart mein daal do please".length),
        )
        val baseline = twoItemDraft("2 milk, 1 bread").prepareForReview()
        cases.forEach { (instruction, expectedContext) ->
            val primaries = listOf(uniqueSpan(instruction, "2 milk"), uniqueSpan(instruction, "1 bread"))
            val neutral = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("complete non-product context must be proved: $instruction",
                coversContent(instruction, expectedContext, neutral))

            val prepared = twoItemDraft(instruction).prepareForReview()
            assertTrue("recognized framing leaves no unresolved text: $instruction", prepared.unresolvedText.isEmpty())
            assertEquals(baseline.items.size, prepared.items.size)
            baseline.items.zip(prepared.items).forEach { (expected, actual) ->
                assertEquals(expected.quantity, actual.quantity)
                assertEquals(expected.query, actual.query)
                assertEquals(expected.rawText, actual.rawText)
                assertEquals(expected.intentQuantityProvenance, actual.intentQuantityProvenance)
            }
        }
    }

    @Test
    fun v1PreparationKeepsUnquotedObligationsAndUncitedItemsUnresolved() {
        listOf(
            "if available, 2 milk, 1 bread",
            "no, 2 milk, 1 bread",
            "sugar-free please: 2 milk, 1 bread",
            "instead, 2 milk, 1 bread",
        ).forEach { instruction ->
            val primaries = listOf(uniqueSpan(instruction, "2 milk"), uniqueSpan(instruction, "1 bread"))
            val prefix = GroceryIntentCoverageSpan(0, primaries.first().startUtf16)
            assertFalse("meaningful prefix cannot be neutralized: $instruction",
                coversContent(instruction, prefix, neutralGroceryIntentContextSpans(instruction, primaries)))
            assertHeldOrUnresolved(twoItemDraft(instruction))
        }

        listOf(
            "2 milk, 1 bread, and 3 eggs",
            "2 milk, eggs, 1 bread",
            "2 milk, no sugar, 1 bread",
            "2 milk, three extra, 1 bread",
        ).forEach { body ->
            val omitted = "I have written it down: $body"
            val omittedSpans = listOf(uniqueSpan(omitted, "2 milk"), uniqueSpan(omitted, "1 bread"))
            val header = GroceryIntentCoverageSpan(0, "I have written it down: ".length)
            assertTrue(header in neutralGroceryIntentContextSpans(omitted, omittedSpans))
            assertHeldOrUnresolved(twoItemDraft(omitted))
        }
    }

    @Test
    fun duplicatePrimaryQuotesCannotAuthorizeListHeaderCoverage() {
        val instruction = "I have written it down: 2 milk, 2 milk and 1 bread"
        val milkStart = instruction.indexOf("2 milk")
        val milk = GroceryIntentCoverageSpan(milkStart, milkStart + "2 milk".length)
        val bread = uniqueSpan(instruction, "1 bread")
        val expectedHeader = GroceryIntentCoverageSpan(0, "I have written it down: ".length)
        assertFalse(expectedHeader in neutralGroceryIntentContextSpans(instruction, listOf(milk, milk, bread)))
        assertHeldOrUnresolved(twoItemDraft(instruction))
    }

    @Test
    fun recognizedHeaderDoesNotReplaceExactSourceCoverage() {
        val ordinary = "I need a household shopping list: 2 milk, 1 bread"
        listOf(
            emptyList(),
            listOf("2 milk", "2 milk", "1 bread"),
            listOf("2 milk", "milk, 1 bread"),
        ).forEach { sources ->
            assertTrue("unquoted header remains a source gap for $sources",
                uncoveredGroceryIntentSegments(ordinary, sources).any { "household shopping list" in it })
        }

        val cropped = "I need a household shopping list: 1.2 milk"
        val croppedGaps = uncoveredGroceryIntentSegments(cropped, listOf("2 milk"))
        assertTrue(croppedGaps.any { "household shopping list" in it })
        assertTrue("cropped quote must not consume the leading numeric atom", croppedGaps.any { Regex("\\b1\\b").containsMatchIn(it) })
    }

    @Test
    fun editorialSectionOrderDoesNotClearUnquotedSectionHeadingsOrBody() {
        val instruction = "I wrote dairy first and then bakery. Dairy items: 2 milk. Bakery items: 1 bread"
        val primaries = listOf(uniqueSpan(instruction, "2 milk"), uniqueSpan(instruction, "1 bread"))
        val prefaceEnd = instruction.indexOf('.') + 1
        val neutral = neutralGroceryIntentContextSpans(instruction, primaries)
        assertTrue(GroceryIntentCoverageSpan(0, prefaceEnd) in neutral)
        assertFalse(neutral.any { it.startUtf16 <= instruction.indexOf("Dairy items") && it.endUtf16 > instruction.indexOf("Dairy items") })
        assertFalse(neutral.any { it.startUtf16 <= instruction.indexOf("Bakery items") && it.endUtf16 > instruction.indexOf("Bakery items") })
        val gaps = uncoveredGroceryIntentSegments(instruction, primaries.map { instruction.substring(it.startUtf16, it.endUtf16) })
        assertTrue(gaps.any { "Dairy items" in it })
        assertTrue(gaps.any { "Bakery items" in it })
        assertHeldOrUnresolved(twoItemDraft(instruction))
    }

    private fun twoItemDraft(instruction: String): GroceryIntentDraft = draft(
        instruction,
        listOf(item("milk", "2", sourceText = "2 milk"), item("bread", "1", sourceText = "1 bread")).joinToString(","),
    )

    private fun uniqueSpan(instruction: String, quote: String): GroceryIntentCoverageSpan {
        val start = instruction.indexOf(quote)
        require(start >= 0 && start == instruction.lastIndexOf(quote)) { "Expected one exact source quote" }
        return GroceryIntentCoverageSpan(start, start + quote.length)
    }

    private fun coversContent(
        instruction: String,
        range: GroceryIntentCoverageSpan,
        spans: List<GroceryIntentCoverageSpan>,
    ): Boolean = (range.startUtf16 until range.endUtf16).filter { instruction[it].isLetterOrDigit() }
        .all { offset -> spans.any { it.startUtf16 <= offset && offset < it.endUtf16 } }

    private fun assertHeldOrUnresolved(value: GroceryIntentDraft) {
        val preparation = try {
            value.prepareForReview()
        } catch (_: IntentNeedsReviewException) {
            return
        }
        assertTrue("unsafe or incomplete context must remain unresolved", preparation.unresolvedText.isNotEmpty())
    }

    @Test
    fun rejectsArbitraryFieldsChangedInstructionAndNonQuoteSource() {
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("milk", item("milk", "1", sourceText = "milk", extra = ",\"action\":\"add\"")), "milk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("milk", item("milk", "1", sourceText = "milk")), "milk please")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("milk please", item("milk", "1", sourceText = "not present")), "milk please")
        }
    }

    @Test
    fun rejectsInvalidQuantitiesUnitsAndConflictingTags() {
        assertThrows(IllegalArgumentException::class.java) { parsed("rice", quantity = "null", quantityUnit = "count") }
        assertThrows(IllegalArgumentException::class.java) { parsed("rice", quantity = "21", quantityUnit = "count") }
        assertThrows(IllegalArgumentException::class.java) { parsed("rice", quantity = "1.5", quantityUnit = "pack") }
        assertThrows(IllegalArgumentException::class.java) {
            parsed("sugar free sweet", required = "\"sugar_free\"", excluded = "\"sugar_free\"")
        }
    }

    @Test
    fun itemLimitAndContractAreStrict() {
        val many = (0..50).joinToString(",") { item("rice", "null", quantityUnit = "unspecified", sourceText = "rice") }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("rice", many), "rice")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentResponse.parse(response("rice", item("rice", "null", quantityUnit = "unspecified", sourceText = "rice"), contract = "v2"), "rice")
        }
    }

    @Test fun readyDrinkEvidenceInNameIsEnforcedWithoutRequiringDuplicateTag() {
        for (product in listOf("tea", "coffee")) {
            for (form in listOf("ready made", "ready-made")) {
                for (tags in listOf("", "\"sugar_free\"", "\"sugar_free\",\"ready_to_drink\"")) {
                    for (count in listOf(null, 2)) {
                        val name = "sugar-free $form $product"
                        val instruction = (count?.let { "$it " } ?: "") + name
                        val result = draft(instruction, item(name, count?.toString() ?: "null",
                            quantityUnit = if (count == null) "unspecified" else "count",
                            required = tags, sourceText = instruction)).toParsedItems().single()
                        assertFalse("literal spelling must not acquire an identity hold: $instruction", result.unprovenIdentityScope)
                        assertEquals(instruction, result.rawText)
                        assertEquals(if (count == null) Quantity.Default else Quantity.Count(count), result.quantity)
                        if (count != null) assertEquals(instruction, result.intentQuantityProvenance?.primaryText)
                        assertTrue(swiggyIdentityTokens(result.strictMatchPhrase.orEmpty())
                            .containsAll(setOf("sugarfree", "readytodrink", product)))
                        assertTrue(swiggyMatchesProductIdentity(result, "Sugar Free Ready to Drink $product"))
                        assertFalse(swiggyMatchesProductIdentity(result, "Sugar Free $product Premix Powder"))
                        assertFalse(swiggyMatchesProductIdentity(result, "Sweetened Ready to Drink $product"))
                        assertFalse(swiggyMatchesProductIdentity(result, "No Added Sugar Ready to Drink $product"))
                    }
                }
            }
        }
    }

    @Test fun explicitPremixOrPowderFormCannotSilentlyMatchReadyToDrinkCandidates() {
        data class Outcome(
            val caseId: String,
            val held: Boolean,
            val matchesOrdinaryReadyToDrink: Boolean?,
            val quantityProvenancePresent: Boolean?,
            val originalSourcePreserved: Boolean,
            val holdChecks: Set<GroceryIntentDraftCheck>,
        )

        val cases = buildList {
            for (product in listOf("tea", "coffee")) {
                for (form in listOf("premix", "powder")) {
                    for (readyMade in listOf("ready made", "ready-made")) {
                        val source = "$readyMade $product $form"
                        add("${product}_${form}_${readyMade.replace(' ', '_')}" to source)
                    }
                }
            }
        }
        val outcomes = cases.map { (caseId, source) ->
            val value = draft(source, item(
                name = source,
                quantity = "null",
                quantityUnit = "unspecified",
                sourceText = source,
            )).assessForContextReview()
            val preparation = value.preparation
            if (preparation == null) {
                Outcome(caseId, true, null, null, true, value.holds.map { it.check }.toSet())
            } else {
                val parsed = preparation.items.single()
                val product = if (caseId.startsWith("tea_")) "tea" else "coffee"
                Outcome(
                    caseId,
                    held = false,
                    matchesOrdinaryReadyToDrink = swiggyMatchesProductIdentity(parsed, "Ready to Drink $product"),
                    quantityProvenancePresent = parsed.intentQuantityProvenance != null,
                    originalSourcePreserved = parsed.rawText == source,
                    holdChecks = emptySet(),
                )
            }
        }
        val diagnostic = outcomes.joinToString(prefix = "\n", separator = "\n") { outcome ->
            "${outcome.caseId}: held=${outcome.held}, readyToDrinkMatch=${outcome.matchesOrdinaryReadyToDrink}, " +
                "quantityProofPresent=${outcome.quantityProvenancePresent}, sourcePreserved=${outcome.originalSourcePreserved}, " +
                "checks=${outcome.holdChecks}"
        }

        assertEquals("two products x two explicit forms x two original spellings", 8, outcomes.size)
        assertTrue("explicit premix/powder cannot silently match an ordinary Ready to Drink SKU; outcomes=$diagnostic",
            outcomes.all { it.held || it.matchesOrdinaryReadyToDrink == false })
        assertTrue("prepared items retain exact source and no invented quantity proof; outcomes=$diagnostic",
            outcomes.all { it.held || it.originalSourcePreserved && it.quantityProvenancePresent == false })
    }

    private fun parsed(
        instruction: String,
        quantity: String = "1",
        quantityUnit: String = "count",
        packValue: String = "null",
        packUnit: String = "unspecified",
        required: String = "",
        excluded: String = "",
        itemName: String = instruction.substringBefore(' '),
    ): GroceryIntentDraft = draft(
        instruction,
        item(
            itemName, quantity, quantityUnit, packValue, packUnit,
            required, excluded, instruction,
        ),
    )

    private fun draft(instruction: String, item: String, modelNeedsClarification: Boolean = false): GroceryIntentDraft =
        GroceryIntentResponse.parse(response(instruction, item, modelNeedsClarification = modelNeedsClarification), instruction).draft

    private fun response(
        instruction: String,
        item: String,
        contract: String = "grocery-intent-v1",
        modelNeedsClarification: Boolean = false,
    ): String = """
        {"accepted":true,"draft":{"contract_version":"$contract","requires_review":true,
         "instruction":"$instruction","items":[$item],"model_needs_clarification":$modelNeedsClarification},
         "model":"model-x","promptVersion":"intent-v1","latencyMs":125,"usage":{"input_tokens":3,"output_tokens":5}}
    """.trimIndent()

    private fun item(
        name: String,
        quantity: String,
        quantityUnit: String = "count",
        packValue: String = "null",
        packUnit: String = "unspecified",
        required: String = "",
        excluded: String = "",
        sourceText: String = name,
        needsClarification: Boolean = false,
        extra: String = "",
    ): String = """
        {"name":"$name","quantity":$quantity,"quantity_unit":"$quantityUnit",
         "pack_value":$packValue,"pack_unit":"$packUnit","required":[$required],"excluded":[$excluded],
         "source_text":"$sourceText","needs_clarification":$needsClarification$extra}
    """.trimIndent().replace("\n", "")
}
