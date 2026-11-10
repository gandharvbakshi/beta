package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewV2CorrectionsTest {
    @Test fun dietaryClauseKeepsOnePropertyOwnerInsteadOfCompetingWithReminderCoverage() {
        val primary = "2 lactose-free milk"
        listOf("milk lactose-free hi chahiye", "milk lactose-free hi hona").forEach { tail ->
            val instruction = "$primary; $tail"
            val review = parse(instruction, row(instruction, primary, "lactose-free milk",
                "2", "count", "2", required = listOf("lactose_free")))
            val prepared = review.prepareForReview(allowPropertyObligations = true)
            assertEquals(tail, 1, prepared.items.size)
            assertTrue(tail, prepared.unresolvedText.isEmpty())
            assertTrue(tail, prepared.excludedText.isEmpty())
            assertEquals(listOf(tail), prepared.sharedGroupNotes)
            assertFalse(prepared.items.single().rawText.contains("You also said"))
        }
    }

    @Test fun repeatedQuantityAndDietPreserveIndependentCorrectionWithoutAnotherAcknowledgement() {
        val milk = "2 Brand lactose-free milk cartons 1 litre each"
        val apples = "1 kg apples"
        val operation = "apples 1 kilo nahi 4 apples rakhna"
        val reminder = "milk 2 cartons hi, lactose-free hi rehna"
        val instruction = "$milk, $apples; $operation; $reminder"
        val rows = listOf(
            row(instruction, milk, "Brand lactose-free milk", "2", "pack", "2",
                "1", "l", "1", "carton", listOf("lactose_free"), rowIndex = 0),
            row(instruction, apples, "apples", "1", "kg", "1", rowIndex = 1,
                corrections = listOf(correction(instruction, "quantity", operation, "4", 1, "kg", 4, "piece"))),
        )
        val prepared = GroceryIntentReviewV2Response.parse(responseRows(instruction, rows), instruction).draft.prepareForReview()
        assertEquals(2, prepared.items.size)
        assertTrue(prepared.unresolvedText.toString(), prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())
        val selectedMilk = prepared.items.first()
        assertEquals(Quantity.Count(2), selectedMilk.quantity)
        assertTrue(selectedMilk.retailPackCount)
        assertEquals(1, Regex(Regex.escape(reminder)).findAll(selectedMilk.rawText).count())
        assertEquals(Quantity.Count(4), prepared.items.last().quantity)
        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText)
        assertTrue(gate.canApply())
    }

    @Test fun repeatedFactsCannotHideChangesAmbiguityOrAnInvalidOwner() {
        val milk = "2 Brand lactose-free milk cartons 1 litre each"
        val apples = "1 kg apples"
        val operation = "apples 1 kilo nahi 4 apples rakhna"
        listOf(
            "milk 3 cartons hi, lactose-free hi rehna" to "Brand lactose-free milk",
            "milk 2 bottles hi, lactose-free hi rehna" to "Brand lactose-free milk",
            "milk 2 cartons hi, sugar-free hi rehna" to "Brand lactose-free milk",
            "milk 2 cartons hi, lactose-free hi rehna tomorrow" to "Brand lactose-free milk",
            "milk 1 litre hi rehna" to "Brand lactose-free milk",
            "milk 2 cartons hi, lactose-free hi rehna" to "Brand strawberry lactose-free milk",
        ).forEach { (reminder, name) ->
            val instruction = "$milk, $apples; $operation; $reminder"
            val rows = listOf(
                row(instruction, milk, name, "2", "pack", "2", "1", "l", "1", "carton",
                    listOf("lactose_free"), rowIndex = 0),
                row(instruction, apples, "apples", "1", "kg", "1", rowIndex = 1,
                    corrections = listOf(correction(instruction, "quantity", operation, "4", 1, "kg", 4, "piece"))),
            )
            val prepared = GroceryIntentReviewV2Response.parse(responseRows(instruction, rows), instruction).draft.prepareForReview()
            assertTrue(reminder, prepared.items.isEmpty())
            assertTrue(reminder, prepared.unresolvedText.isNotEmpty())
        }
    }

    @Test fun finalListReferencesKeepWholeBasketUsableButDoNotHideNewCategoryRequests() {
        fun prepare(instruction: String): GroceryIntentPreparation {
            val rows = listOf(
                row(instruction, "2 eggs", "eggs", "2", "count", "2", rowIndex = 0),
                row(instruction, "1 milk", "milk", "1", "count", "1", rowIndex = 1),
            )
            return GroceryIntentReviewV2Response.parse(responseRows(instruction, rows), instruction).draft.prepareForReview()
        }
        listOf("yeh sab le aana", "Please add these to my cart.", "Ye sab la do.").forEach { closer ->
            val prepared = prepare("2 eggs, 1 milk. $closer")
            assertEquals(2, prepared.items.size)
            assertTrue(closer, prepared.unresolvedText.isEmpty())
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText)
            assertTrue(closer, gate.canApply())
        }
        listOf("2 eggs. Add essentials. 1 milk.", "2 eggs, add essentials, 1 milk",
            "2 eggs, 1 milk. Please get the following.",
            "2 eggs, 1 milk. Ye sab la do. Plus cleaning supplies").forEach { instruction ->
            val prepared = prepare(instruction)
            assertTrue(instruction, prepared.unresolvedText.isNotEmpty())
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText)
            assertFalse(instruction, gate.canApply())
        }
    }

    @Test fun explicitWeightPieceCorrectionsKeepLogicalUnitsThroughCatalogueQuantity() {
        data class Case(val primary: String, val old: Int, val oldUnit: String,
                        val operation: String, val new: Int, val newUnit: String, val label: String, val cartCount: Int)
        listOf(
            Case("1 kg apples", 1, "kg", "apples 1 kilo nahi 4 pieces rakhna", 4, "piece", "Fresh Apples 4 pcs", 1),
            Case("4 pieces apples", 4, "piece", "apples 4 pieces nahi 1 kg rakhna", 1, "kg", "Fresh Apples 500 g", 2),
        ).forEach { case ->
            val instruction = "${case.primary}; ${case.operation}"
            val prepared = parse(instruction, row(instruction, case.primary, "apples",
                case.old.toString(), case.oldUnit, case.old.toString(), corrections = listOf(
                    correction(instruction, "quantity", case.operation, case.new.toString(),
                        case.old, case.oldUnit, case.new, case.newUnit)))).prepareForReview()
            assertTrue(prepared.unresolvedText.toString(), prepared.unresolvedText.isEmpty())
            val item = prepared.items.single()
            assertEquals(case.newUnit == "piece", item.logicalPieceCount)
            assertFalse(item.retailPackCount)
            assertEquals(case.newUnit, item.intentQuantityProvenance?.corrected?.unit)
            if (case.operation.contains("pieces")) {
                assertEquals(null, item.intentQuantityProvenance?.produceCategory)
            }
            val candidate = SwiggyMcpClient.RecommendationCandidate(spinId = "synthetic", label = case.label)
            val assessed = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(candidates = listOf(candidate)))
            assertEquals(case.cartCount, swiggyRequestedCartQuantity(assessed, candidate))
            if (case.newUnit == "piece") {
                val wrong = candidate.copy(label = "Fresh Apples 1 kg")
                val wrongAssessed = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(candidates = listOf(wrong)))
                assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(wrongAssessed, wrong) }
            }
        }
    }

    @Test fun namedWholeProducePieceCorrectionRequiresExactCataloguePieceEvidence() {
        val primary = "1 kg apples"
        val operation = "apples 1 kilo nahi 4 apples rakhna"
        val instruction = "$primary; $operation"
        val prepared = parse(instruction, row(
            instruction, primary, "apples", "1", "kg", "1",
            corrections = listOf(correction(instruction, "quantity", operation, "4", 1, "kg", 4, "piece")),
        )).prepareForReview()
        assertTrue(prepared.unresolvedText.toString(), prepared.unresolvedText.isEmpty())
        val item = prepared.items.single()
        assertEquals(Quantity.Count(4), item.quantity)
        assertTrue(item.logicalPieceCount)
        assertEquals("piece", item.intentQuantityProvenance?.corrected?.unit)
        assertEquals(IntentProduceCategory.FRUITS, item.intentQuantityProvenance?.produceCategory)

        fun candidate(label: String) = SwiggyMcpClient.RecommendationCandidate(spinId = label, label = label)
        val exact = candidate("Fresh Apples 4 pcs")
        val exactAssessment = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(candidates = listOf(exact)))
        assertTrue(isSwiggyCandidateCountCompatible(exactAssessment, exact))
        assertEquals(1, swiggyRequestedCartQuantity(exactAssessment, exact))

        val twoPiecePack = candidate("Fresh Apples 2 pcs")
        val twoPieceAssessment = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(candidates = listOf(twoPiecePack)))
        assertTrue(isSwiggyCandidateCountCompatible(twoPieceAssessment, twoPiecePack))
        assertEquals(2, swiggyRequestedCartQuantity(twoPieceAssessment, twoPiecePack))

        listOf("Fresh Apples", "Fresh Apples 6 pcs", "Fresh Apples 4", "4 Apples",
            "Fresh Cut Apples 4 pcs", "Apple Juice 4 pcs").forEach { label ->
            val unsupported = candidate(label)
            val assessment = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(candidates = listOf(unsupported)))
            assertFalse("unproved catalogue piece count: $label", isSwiggyCandidateCountCompatible(assessment, unsupported))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessment, unsupported) }
        }
    }

    @Test fun namedPieceCorrectionsKeepThe24BoundaryAndAllowOnlySameNounPluralGroup() {
        data class Case(val primary: String, val name: String, val operation: String, val count: Int)
        listOf(
            Case("1 kg apples", "apples", "apples 1 kilo nahi 24 apples rakhna", 24),
            Case("1 KG Apples", "Apples", "Apples 1 kilo nahi 4 Apples rakhna", 4),
            Case("apples 1 kg", "apples", "apples 1 kilo nahi 4 apples rakhna", 4),
            Case("1 kg apple", "apple", "apple 1 kilo nahi 4 apples rakhna", 4),
            Case("1 kg apples", "apples", "apples 1 kilo nahi 4 apple rakhna", 4),
        ).forEach { case ->
            val instruction = "${case.primary}; ${case.operation}"
            val prepared = parse(instruction, row(
                instruction, case.primary, case.name, "1", "kg", "1",
                corrections = listOf(correction(instruction, "quantity", case.operation, case.count.toString(),
                    1, "kg", case.count, "piece")),
            )).prepareForReview()
            assertTrue(instruction, prepared.unresolvedText.isEmpty())
            val item = prepared.items.single()
            assertEquals(Quantity.Count(case.count), item.quantity)
            assertTrue(item.logicalPieceCount)
            assertEquals(IntentProduceCategory.FRUITS, item.intentQuantityProvenance?.produceCategory)
        }

        val tooMany = "apples 1 kilo nahi 25 apples rakhna"
        val instruction = "1 kg apples; $tooMany"
        assertRejectedCorrection(instruction, row(instruction, "1 kg apples", "apples", "1", "kg", "1",
            corrections = listOf(correction(instruction, "quantity", tooMany, "25", 1, "kg", 25, "piece"))))
    }

    @Test fun namedPieceCorrectionsRejectUnicodeConfusablesAndDuplicateTargets() {
        val kelvinSuffix = "kela 1 kilo nahi 4 kel\u212A rakhna"
        var instruction = "1 kg kela; $kelvinSuffix"
        assertRejectedCorrection(instruction, row(instruction, "1 kg kela", "kela", "1", "kg", "1",
            corrections = listOf(correction(instruction, "quantity", kelvinSuffix, "4", 1, "kg", 4, "piece"))))

        val dotlessSuffix = "kiwi 1 kilo nahi 4 kiw\u0131 rakhna"
        instruction = "1 kg kiwi; $dotlessSuffix"
        assertRejectedCorrection(instruction, row(instruction, "1 kg kiwi", "kiwi", "1", "kg", "1",
            corrections = listOf(correction(instruction, "quantity", dotlessSuffix, "4", 1, "kg", 4, "piece"))))

        val confusablePrimary = "1 kg kiw\u0131"
        val operation = "kiw\u0131 1 kilo nahi 4 kiw\u0131 rakhna"
        instruction = "$confusablePrimary; $operation"
        assertRejectedCorrection(instruction, row(instruction, confusablePrimary, "kiwi", "1", "kg", "1",
            corrections = listOf(correction(instruction, "quantity", operation, "4", 1, "kg", 4, "piece"))))

        val first = "1 kg apples"
        val second = "2 kg apples"
        val duplicateOperation = "apples 1 kilo nahi 4 apples rakhna"
        instruction = "$first; $second; $duplicateOperation"
        val rows = listOf(
            row(instruction, first, "apples", "1", "kg", "1", corrections = listOf(
                correction(instruction, "quantity", duplicateOperation, "4", 1, "kg", 4, "piece"))),
            row(instruction, second, "apples", "2", "kg", "2", rowIndex = 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(responseRows(instruction, rows), instruction)
        }
    }

    @Test fun weightPieceCorrectionRejectsImplicitRetailCountsAndUnprovedChanges() {
        listOf(
            "apples 1 kilo nahi 4 rakhna" to "piece",
            "apples 1 kilo nahi 4 bananas rakhna" to "piece",
            "apples 1 kilo nahi 4 apples packet rakhna" to "piece",
            "apples 1 kilo nahi 4 pieces rakhna" to "count",
            "apples 1 kilo nahi 4 packs rakhna" to "pack",
            "apples 1 kilo nahi 4 pieces each rakhna" to "piece",
            "organic apples 1 kilo nahi 4 pieces rakhna" to "piece",
            "apples 2 kilo nahi 4 pieces rakhna" to "piece",
            "make apples 4 pieces" to "piece",
            "no make it 4 pieces" to "piece",
            "apples 1 kilo nahi 4 bananas rakhna" to "piece",
            "apple juice 1 kilo nahi 4 apple juice rakhna" to "piece",
            "chopped apples 1 kilo nahi 4 chopped apples rakhna" to "piece",
        ).forEach { (operation, unit) ->
            val primary = when {
                operation.startsWith("apple juice") -> "1 kg apple juice"
                operation.startsWith("apples") -> "1 kg apples"
                operation.startsWith("chopped apples") -> "1 kg chopped apples"
                else -> "1 kg apples"
            }
            val name = if (primary.contains("apple juice")) "apple juice" else if (primary.contains("chopped")) "chopped apples" else "apples"
            val instruction = "$primary; $operation"
            assertRejectedCorrection(instruction, row(instruction, primary, name, "1", "kg", "1",
                corrections = listOf(correction(instruction, "quantity", operation, "4", 1, "kg", 4, unit))))
        }
    }

    @Test fun weightPieceReplacementCannotReusePackOrFulfillmentEvidence() {
        val operation = "apples 1 kilo nahi 4 pieces rakhna"
        listOf("1 kg apples", "1 kg apples bag").forEach { primary ->
            val instruction = "$primary; $operation"
            val proof = correction(instruction, "quantity", operation, "4", 1, "kg", 4, "piece")
            assertRejectedCorrection(instruction, row(instruction, primary, "apples", "1", "kg", "1",
                packaging = "bag", corrections = listOf(proof)))
            assertRejectedCorrection(instruction, row(instruction, primary, "apples", "1", "kg", "1",
                pack = "500", packUnit = "g", packText = "500", corrections = listOf(proof)))
        }
        val heading = "Get groceries tomorrow:"
        val instruction = "$heading 1 kg apples; $operation"
        val ready = row(instruction, "1 kg apples", "apples", "1", "kg", "1", corrections = listOf(
            correction(instruction, "quantity", operation, "4", 1, "kg", 4, "piece")))
        val constraint = """{"kind":"fulfillment","source":{"text":${json(heading)},"start_utf16":0,"end_utf16":${heading.length}}}"""
        val wire = response(instruction, ready).replace("\"blocked_rows\":[]", "\"blocked_rows\":[],\"constraints\":[$constraint]")
        assertThrows(IllegalArgumentException::class.java) { GroceryIntentReviewV2Response.parse(wire, instruction) }
        // Omitting the declaration cannot evade independently derived constraints.
        assertRejectedCorrection(instruction, ready)
    }

    @Test fun hindiListWritingAndUnchangedTailPreserveEveryItemAndApplyOnlyTheVerifiedCorrection() {
        listOf("aaj ghar ka samaan likh rahi hoon", "hum grocery list likh rahe hain", "list mein likh lo").forEach { intro ->
            val operation = "milk 1 nahi 2 kar do"
            val instruction = "$intro: 1 milk, 2 eggs; $operation; baaki same"
            val rows = listOf(
                row(instruction, "1 milk", "milk", "1", "count", "1", rowIndex = 0,
                    corrections = listOf(correction(instruction, "quantity", operation, "2", 1, "count", 2, "count"))),
                row(instruction, "2 eggs", "eggs", "2", "count", "2", rowIndex = 1),
            )
            val decoded = GroceryIntentReviewV2Response.parse(responseRows(instruction, rows), instruction).draft
            assertEquals("1", decoded.readyRows.first().item.quantity.toString())
            val prepared = decoded.prepareForReview()
            assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
            assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(instruction, prepared.unresolvedText.isEmpty())
            // An unowned restriction or product cannot be hidden behind a valid
            // introductory clause or a cropped unchanged-list reference.
            listOf("; no sugar", "; 1 bread", "; actually make eggs 3").forEach { extra ->
                val unsafe = instruction + extra
                val result = GroceryIntentReviewV2Response.parse(responseRows(unsafe, rows), unsafe).draft.prepareForReview()
                assertTrue(extra, result.unresolvedText.isNotEmpty())
                if (extra != "; 1 bread") assertTrue(extra, result.items.isEmpty())
                assertFalse(extra, result.items.any { it.query == "bread" })
                val gate = GroceryIntentReviewGate()
                gate.prepare(result.unresolvedText, result.excludedText)
                assertFalse("unresolved content must prevent unacknowledged cart use: $extra", gate.canApply())
            }
        }
    }

    @Test fun compositionalShoppingIntroductionsPreserveBothRowsAndApplyOnlyNamedCorrection() {
        listOf(
            "Monthly restock, please:",
            "Can you add these pantry things:",
            "I need a household shopping list:",
        ).forEach { intro ->
            val operation = "make milk two"
            val instruction = "$intro 1 milk, 2 eggs; $operation"

            fun rowsFor(source: String): List<String> = listOf(
                row(source, "1 milk", "milk", "1", "count", "1", rowIndex = 0,
                    corrections = listOf(correction(source, "quantity", operation, "two", 1, "count", 2, "count"))),
                row(source, "2 eggs", "eggs", "2", "count", "2", rowIndex = 1),
            )

            val decoded = GroceryIntentReviewV2Response.parse(responseRows(instruction, rowsFor(instruction)), instruction).draft
            assertEquals("1", decoded.readyRows.first().item.quantity.toString())
            val prepared = decoded.prepareForReview()
            assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
            assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(instruction, prepared.unresolvedText.isEmpty())

            listOf("; no sugar", "; make eggs 3").forEach { tail ->
                val unsafe = instruction + tail
                val result = GroceryIntentReviewV2Response.parse(responseRows(unsafe, rowsFor(unsafe)), unsafe)
                    .draft.prepareForReview()
                assertTrue(tail, result.unresolvedText.isNotEmpty())
                assertTrue(tail, result.items.isEmpty())
                val gate = GroceryIntentReviewGate()
                gate.prepare(result.unresolvedText, result.excludedText)
                assertFalse("unsafe tail must prevent cart use: $tail", gate.canApply())
            }
        }
    }

    @Test fun trailingOrDetachedShoppingRequestsRemainUnresolvedBesideAnExistingEggRow() {
        val unsafeInstructions = listOf(
            "2 eggs. Add essentials.",
            "2 eggs, please get weekly basics",
            "2 eggs. Shopping list.",
            "Add essentials. No rush; 2 eggs",
        )
        unsafeInstructions.forEach { instruction ->
            val draft = parse(instruction, row(
                instruction, "2 eggs", "eggs", "2", "count", "2",
            ))
            assertEquals(1, draft.readyRows.size)
            assertEquals("2", draft.readyRows.single().item.quantity?.toPlainString())

            val prepared = draft.prepareForReview()
            assertTrue("unfulfilled request must remain visible: $instruction", prepared.unresolvedText.isNotEmpty())
            assertTrue("the independently sourced egg row may remain: $instruction",
                prepared.items.any { it.query.contains("eggs", ignoreCase = true) })
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText)
            assertFalse("unresolved request must block cart use: $instruction", gate.canApply())
        }

        val positive = "Can you add these pantry things: 2 eggs"
        val prepared = parse(positive, row(
            positive, "2 eggs", "eggs", "2", "count", "2",
        )).prepareForReview()
        assertTrue(prepared.unresolvedText.isEmpty())
        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText)
        assertTrue(gate.canApply())
    }

    @Test
    fun hindiNamedRecallChangesOnlyAnExactlyRecalledOriginalQuantity() {
        listOf("kar do", "kar dena", "karna", "rakh do", "rakhna", "rakho", "lena", "le lo", "chahiye", "").forEach { action ->
            val phrase = "soap 2 nahi, 3 $action".trimEnd()
            val instruction = "2 green soap; $phrase"
            val decoded = parse(instruction, row(
                instruction, "2 green soap", "green soap", "2", "count", "2",
                corrections = listOf(correction(instruction, "quantity", phrase, "3", 2, "count", 3, "count")),
            ))
            assertEquals("2", decoded.readyRows.single().item.quantity.toString())
            assertEquals("green soap", decoded.readyRows.single().item.name)
            val prepared = decoded.prepareForReview()
            assertEquals(Quantity.Count(3), prepared.items.single().quantity)
            assertTrue(prepared.items.single().rawText.contains(phrase))
            assertTrue(prepared.unresolvedText.isEmpty())
        }
        val phrase = "tea 2 nahi, 1 packet hi lena"
        val instruction = "2 packets tea; $phrase"
        val prepared = parse(instruction, row(
            instruction, "2 packets tea", "tea", "2", "pack", "2", packaging = "packet",
            corrections = listOf(correction(instruction, "quantity", phrase, "1", 2, "pack", 1, "pack")),
        )).prepareForReview()
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().retailPackCount)

        val weightPhrase = "salt 1 kg nahin, aadha kg karna"
        val weight = "1 kg salt; $weightPhrase"
        assertEquals(Quantity.Weight(500), parse(weight, row(
            weight, "1 kg salt", "salt", "1", "kg", "1",
            corrections = listOf(correction(weight, "quantity", weightPhrase, "aadha", 1, "kg", 0.5, "kg")),
        )).prepareForReview().items.single().quantity)
    }

    @Test
    fun hindiRecallCannotChangeIdentityContainerRoleOrHideAdditionalInstructions() {
        listOf(
            "soap 4 nahi, 3", "shampoo 2 nahi, 3", "organic soap 2 nahi, 3",
            "soap 2 nahi, 3 each", "soap 2 nahi, 3 or 4", "soap 2 nahi, 3 shampoo",
            "soap 2 nahi, 3 kar do not", "soap 2 nahi, 3 kar do and 1 rice",
            "soap 2 packet nahi, 3", "soap 2 nahi, 3 bars kar do",
        ).forEach { phrase ->
            val instruction = "2 soap; $phrase"
            assertRejectedCorrection(instruction, row(
                instruction, "2 soap", "soap", "2", "count", "2",
                corrections = listOf(correction(instruction, "quantity", phrase, "3", 2, "count", 3, "count")),
            ))
        }
        val phrase = "milk 2 pouch nahi, 3 bottle karna"
        val instruction = "2 milk pouches; $phrase"
        assertRejectedCorrection(instruction, row(
            instruction, "2 milk pouches", "milk", "2", "pack", "2", packaging = "pouch",
            corrections = listOf(correction(instruction, "quantity", phrase, "3", 2, "pack", 3, "pack")),
        ))
        val rolePhrase = "salt 1 kg nahi, 2 packet karna"
        val role = "1 kg salt; $rolePhrase"
        assertRejectedCorrection(role, row(
            role, "1 kg salt", "salt", "1", "kg", "1",
            corrections = listOf(correction(role, "quantity", rolePhrase, "2", 1, "kg", 2, "pack")),
        ))
        val sizePhrase = "tea 500 g nahi, 250 g karna"
        val size = "1 packet 500 g tea; $sizePhrase"
        assertRejectedCorrection(size, row(
            size, "1 packet 500 g tea", "tea", "1", "pack", "1",
            pack = "500", packUnit = "g", packText = "500", packaging = "packet",
            corrections = listOf(correction(size, "pack_size", sizePhrase, "250", 500, "g", 250, "g")),
        ))
    }

    @Test
    fun hindiCorrectionTargetMustBeUniqueAcrossAllOriginalRows() {
        val phrase = "soap 2 nahi, 4"
        val instruction = "2 green soap, 3 blue soap; $phrase"
        val rows = listOf(
            row(instruction, "2 green soap", "green soap", "2", "count", "2", rowIndex = 0,
                corrections = listOf(correction(instruction, "quantity", phrase, "4", 2, "count", 4, "count"))),
            row(instruction, "3 blue soap", "blue soap", "3", "count", "3", rowIndex = 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(responseRows(instruction, rows), instruction)
        }
        val cropped = "soap 2 nahi, 3"
        val complete = "2 soap; $cropped or 4"
        val prepared = parse(complete, row(
            complete, "2 soap", "soap", "2", "count", "2",
            corrections = listOf(correction(complete, "quantity", cropped, "3", 2, "count", 3, "count")),
        )).prepareForReview()
        assertTrue(prepared.items.isEmpty())
    }

    @Test
    fun shortenedHindiReferencePreservesEveryOriginalProductDescriptor() {
        // A target may omit an already-stated descriptor; it cannot edit the
        // identity. Only the count changes and the liquid variant survives.
        val phrase = "green soap 2 nahi, 3"
        val instruction = "2 green liquid soap; $phrase"
        val decoded = parse(instruction, row(
            instruction, "2 green liquid soap", "green liquid soap", "2", "count", "2",
            corrections = listOf(correction(instruction, "quantity", phrase, "3", 2, "count", 3, "count")),
        ))
        assertEquals("green liquid soap", decoded.readyRows.single().item.name)
        val prepared = decoded.prepareForReview()
        assertEquals("green liquid soap", prepared.items.single().query)
        assertEquals(Quantity.Count(3), prepared.items.single().quantity)
        assertTrue(prepared.items.single().rawText.contains("2 green liquid soap"))
    }

    @Test
    fun quotedNewAmountAndPhysicalUnitMustAgreeWithTheTypedOverride() {
        val countInstruction = "one milk, make milk 2"
        assertRejectedCorrection(countInstruction, row(
            countInstruction, "one milk", "milk", "1", "count", "one",
            corrections = listOf(correction(countInstruction, "quantity", "make milk 2", "2", 1, "count", 20, "count")),
        ))
        val weightInstruction = "5 kg rice, make rice 2 g"
        assertRejectedCorrection(weightInstruction, row(
            weightInstruction, "5 kg rice", "rice", "5", "kg", "5",
            corrections = listOf(correction(weightInstruction, "quantity", "make rice 2 g", "2", 5, "kg", 2, "kg")),
        ))
    }

    @Test
    fun omittedOrEmbeddedCorrectionCannotBecomeActionableByGenericAcknowledgement() {
        listOf(
            "two milk, no make that one" to "two milk, no make that one",
            "two milk, actually make that one" to "two milk",
            "two milk; make milk one" to "two milk",
        ).forEach { (instruction, source) ->
            val draft = parse(instruction, row(
                instruction, source, "milk", "2", "count", "two",
            ))
            val prepared = draft.prepareForReview()
            assertTrue(prepared.items.isEmpty())
            assertTrue(prepared.excludedText.isNotEmpty())
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText)
            gate.acknowledge(true)
            assertFalse(gate.canApply())
        }
        val alternative = "2 kg or 3 kg rice"
        assertTrue(parse(alternative, row(alternative, alternative, "rice", "2", "kg", "2"))
            .prepareForReview().items.isEmpty())
        assertFalse(intentV2HasCorrectionCue("one juice with no added sugar"))
        assertFalse(intentV2HasCorrectionCue("one snack with no palm oil"))
    }

    @Test
    fun quantityCorrectionKeepsOriginalRowAndPreparesCorrectedCountForReview() {
        val instruction = "two milk packets, no make that one"
        val decoded = parse(
            instruction,
            row(
                instruction, source = "two milk packets", name = "milk",
                quantity = "2", quantityUnit = "count", quantityText = "two",
                packaging = "packet",
                corrections = listOf(correction(instruction, "quantity", "no make that one", "one", 2, "count", 1, "count")),
            ),
        )
        val ready = decoded.readyRows.single()
        assertEquals("milk", ready.item.name)
        assertEquals("two milk packets", ready.item.sourceText)
        assertEquals("2", ready.item.quantity.toString())
        assertEquals("no make that one", ready.corrections.single().source.text)
        assertEquals("one", ready.corrections.single().newAmount.text)

        val prepared = decoded.prepareForReview()
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().query.contains("packet"))
        assertTrue(prepared.items.single().rawText.contains("two milk packets"))
        assertTrue(prepared.items.single().rawText.contains("no make that one"))
        assertTrue(prepared.items.single().rawText.contains("Quantity changed: 2 count → 1 count"))
    }

    @Test
    fun betaAssistantPrefixDoesNotBlockAnOtherwiseExactNumericCorrectionReview() {
        val instruction = "Beta, please add these to the cart. one milk, two rice; make milk two"
        val milk = row(
            instruction, source = "one milk", name = "milk",
            quantity = "1", quantityUnit = "count", quantityText = "one",
            corrections = listOf(correction(
                instruction, "quantity", "make milk two", "two", 1, "count", 2, "count",
            )),
            rowIndex = 0,
        )
        val rice = row(
            instruction, source = "two rice", name = "rice",
            quantity = "2", quantityUnit = "count", quantityText = "two", rowIndex = 1,
        )

        val prepared = GroceryIntentReviewV2Response.parse(
            responseRows(instruction, listOf(milk, rice)), instruction,
        ).draft.prepareForReview()

        assertEquals(listOf("milk", "rice"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.first().rawText.contains("one milk"))
        assertTrue(prepared.items.first().rawText.contains("make milk two"))
        assertTrue(prepared.excludedText.isEmpty())
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test
    fun sentenceInitialPrimaryMustMatchCanonicalCaseAndCorrectionStillTargetsOriginalRow() {
        val instruction = "Two milk packets, no make that one."
        val canonicalRow = row(
            instruction, source = "Two milk packets", name = "milk",
            quantity = "2", quantityUnit = "count", quantityText = "Two",
            packaging = "packet",
            corrections = listOf(correction(
                instruction, "quantity", "no make that one.", "one", 2, "count", 1, "count",
            )),
        )
        val decoded = parse(instruction, canonicalRow)
        val original = decoded.readyRows.single().item
        assertEquals("Two milk packets", original.sourceText)
        assertEquals("2", original.quantity.toString())
        assertEquals("Two", original.quantityText)
        assertEquals("no make that one.", decoded.readyRows.single().corrections.single().source.text)
        val prepared = decoded.prepareForReview()
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().rawText.contains("Two milk packets"))

        // Change only the primary/source quote's initial case. The instruction and UTF-16 offsets stay fixed.
        val caseForgedRow = canonicalRow.replace(json("Two milk packets"), json("two milk packets"))
        assertRejectedCorrection(instruction, caseForgedRow)
    }

    @Test
    fun packSizeCorrectionKeepsOnePacketAndUsesNewMeasureInFinalQuery() {
        val instruction = "one packet of 500 g rice, change rice pack size to 250 g"
        val decoded = parse(
            instruction,
            row(
                instruction, source = "one packet of 500 g rice", name = "rice",
                quantity = "1", quantityUnit = "pack", quantityText = "one",
                pack = "500", packUnit = "g", packText = "500", packaging = "packet",
                corrections = listOf(correction(instruction, "pack_size", "change rice pack size to 250 g", "250", 500, "g", 250, "g")),
            ),
        )
        val parsed = decoded.prepareForReview().items.single()
        assertEquals(Quantity.Count(1), parsed.quantity)
        assertTrue(parsed.retailPackCount)
        assertTrue(parsed.query.contains("250 g"))
        assertFalse(parsed.query.contains("500 g"))
        assertTrue(parsed.strictMatchPhrase!!.contains("250 g"))
        assertTrue(parsed.rawText.contains("one packet of 500 g rice"))
    }

    @Test
    fun singleBottleSizeCorrectionKeepsOnePackAndUsesOnlyTheCorrectedVolume() {
        val primary = "a 200 ml bottle of juice"
        val operation = "no, juice is 1 litre"
        listOf(" ", ", ", "? ", " — ").forEach { separator ->
            val instruction = "$primary$separator$operation"
            val decoded = parse(instruction, row(
                instruction, primary, "juice", "1", "pack", "a",
                "200", "ml", "200", "bottle",
                corrections = listOf(correction(
                    instruction, "pack_size", operation, "1", 200, "ml", 1, "l",
                )),
            ))
            assertEquals("stated", decoded.readyRows.single().quantityOrigin)
            val prepared = decoded.prepareForReview()
            assertTrue(separator, prepared.unresolvedText.isEmpty())
            assertTrue(separator, prepared.excludedText.isEmpty())
            assertEquals(1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertTrue(item.query.contains("1 l") || item.query.contains("1000 ml"))
            assertFalse(item.query.contains("200 ml"))
            assertTrue(item.strictMatchPhrase?.contains("1 l") == true || item.strictMatchPhrase?.contains("1000 ml") == true)
            assertFalse(item.strictMatchPhrase?.contains("200 ml") == true)
            assertTrue(item.rawText.contains(primary))
            // Exercise the actual composed V2 item, not just its primary quote.
            // Its raw text includes the correction, so the uncorrected closed
            // source production must not be reapplied at the action boundary.
            val sku = SwiggyMcpClient.RecommendationCandidate("juice-bottle", "Juice Bottle", "1000 ml")
            val assessed = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(sku)))
            assertEquals(null, sourceRetailCountReceipt(item.rawText))
            assertReviewedRetailProposal(assessed, sku, 1)
            val wrongForm = sku.copy(label = "Juice Can")
            val wrongChecked = assessSwiggyIntentQuantity(item, SwiggyMcpClient.Recommendations(listOf(wrongForm)))
            assertFalse(isSwiggyCandidateAllowed(wrongChecked, wrongForm))
            assertTrue(resolveSwiggyCartQuantity(wrongChecked, wrongForm) is SwiggyCartQuantityResolution.Held)
            val unbound = assessed.copy(intentQuantityProvenance = null, requiresIntentQuantityProof = false)
            assertFalse(isSwiggyCandidateAllowed(unbound, sku))
            assertTrue(resolveSwiggyCartQuantity(unbound, sku) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun singleBottleSizeCorrectionRejectsUnprovenCountSizeContainerTargetAndSuffix() {
        val operation = "no, juice is 1 litre"
        fun attempt(
            primary: String,
            quantity: String = "1",
            quantityUnit: String = "pack",
            quantityText: String? = "a",
            pack: String = "200",
            packUnit: String = "ml",
            packText: String? = "200",
            packaging: String? = "bottle",
            correctionSource: String = operation,
            newAmount: String = "1",
            newValue: Number = 1,
            newUnit: String = "l",
            middle: String = "",
        ) {
            val instruction = "$primary$middle, $correctionSource"
            val proof = correction(instruction, "pack_size", correctionSource, newAmount,
                200, "ml", newValue, newUnit)
            val ready = row(instruction, primary, "juice", quantity, quantityUnit, quantityText,
                pack, packUnit, packText, packaging, corrections = listOf(proof))
            assertRejectedOrHeld(instruction, ready)
        }

        attempt("two 200 ml bottles of juice", quantity = "2", quantityText = "two")
        attempt("a 200 ml bottle of juice", quantity = "null", quantityUnit = "unspecified", quantityText = null)
        attempt("a 200 ml bottle of juice", quantity = "2", quantityText = "two")
        attempt("a bottle of juice") // Model cannot invent the old physical size.
        attempt("a 200 ml juice", packaging = "bottle") // Model cannot invent a source container.
        attempt("a 200 ml bottle juice") // The source must bind the closed `of <contents>` form.
        attempt("a 200 ml bottle of juice", newAmount = "1", newValue = 1, newUnit = "kg")
        attempt("a 200 ml bottle of juice", correctionSource = "$operation if available")
        // A validated size correction is not authority to discard a negative
        // occurrence of the SAME form (token-set equality alone cannot prove polarity).
        listOf("not a bottle", "bottle nahi", "without a bottle", "not bottled").forEach { denial ->
            attempt("a 200 ml bottle of juice", correctionSource = "$operation, $denial")
            attempt("a 200 ml bottle of juice", middle = ", $denial")
        }
        attempt("a 200 ml bottle of juice", middle = "; add eggs") // The correction must immediately follow its unique target.

        val primary = "a 200 ml bottle of juice"
        val duplicateInstruction = "$primary; another 200 ml bottle of juice; $operation"
        val duplicateProof = correction(duplicateInstruction, "pack_size", operation, "1", 200, "ml", 1, "l")
        assertRejectedOrHeld(duplicateInstruction, row(
            duplicateInstruction, primary, "juice", "1", "pack", "a", "200", "ml", "200", "bottle",
            corrections = listOf(duplicateProof),
        ))
    }

    @Test
    fun singleBottleSizeCorrectionCanBeEmbeddedInAnOrdinaryHeadedGroupOnly() {
        val header = "For breakfast, please add"
        val bottle = "a 200 ml bottle of juice"
        val operation = "no, juice is 1 litre"
        val eggs = "2 eggs"
        val instruction = "$header $bottle, $operation, $eggs"
        fun rowsFor(source: String): List<String> = listOf(
            row(source, bottle, "juice", "1", "pack", "a", "200", "ml", "200", "bottle",
                corrections = listOf(correction(source, "pack_size", operation, "1", 200, "ml", 1, "l"))),
            row(source, eggs, "eggs", "2", "count", "2", rowIndex = 1),
        )
        val members = listOf(0 to "pack", 1 to "count")
        val groupJson = group(instruction, instruction, header, members, null, "local_amounts_verified")
        val draft = GroceryIntentReviewV2Response.parse(responseRows(
            instruction, rowsFor(instruction), listOf(groupJson),
        ), instruction).draft
        val verifiedGroup = draft.groups.single()
        val verifiedRow = draft.readyRows.first()
        val embeddedCorrection = verifiedRow.corrections.single()
        assertTrue(verifiedGroup.isEmbeddedPackCorrection(verifiedRow, embeddedCorrection))
        val amountSourceStart = instruction.indexOf("2 eggs")
        val disallowedAmount = GroceryIntentReviewV2GroupAmount(
            GroceryIntentReviewV2Quote("2", amountSourceStart, amountSourceStart + 1),
            "2", BigDecimal("2"), "count",
        )
        assertFalse(verifiedGroup.copy(packaging = "bottle").isEmbeddedPackCorrection(verifiedRow, embeddedCorrection))
        assertFalse(verifiedGroup.copy(total = disallowedAmount).isEmbeddedPackCorrection(verifiedRow, embeddedCorrection))
        assertFalse(verifiedGroup.copy(each = disallowedAmount).isEmbeddedPackCorrection(verifiedRow, embeddedCorrection))

        val prepared = draft.prepareForReview()
        assertEquals(2, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items[0].quantity)
        assertTrue(prepared.items[0].retailPackCount)
        assertTrue(prepared.items[0].query.contains("1 l") || prepared.items[0].query.contains("1000 ml"))
        assertFalse(prepared.items[0].query.contains("200 ml"))
        assertEquals(Quantity.Count(2), prepared.items[1].quantity)
        assertEquals(listOf(header), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())

        val packaged = group(instruction, instruction, header, members, "bottle", "local_amounts_verified")
        assertResponseRejectedOrHeld(instruction, responseRows(instruction, rowsFor(instruction), listOf(packaged)))

        val totalInstruction = "$header 3 total: $bottle, $operation, $eggs"
        val total = group(totalInstruction, totalInstruction, header, members, null, "total_verified", total = "3")
        assertResponseRejectedOrHeld(totalInstruction, responseRows(totalInstruction, rowsFor(totalInstruction), listOf(total)))

        val eachInstruction = "$header 2 each of $bottle, $operation, $eggs"
        val each = group(eachInstruction, eachInstruction, header, members, null, "each_verified", each = "2 each")
        assertResponseRejectedOrHeld(eachInstruction, responseRows(eachInstruction, rowsFor(eachInstruction), listOf(each)))

        val cardinalityHeader = "juice varieties:"
        val cardinalityInstruction = "$cardinalityHeader $bottle, $operation, $eggs"
        val cardinality = group(cardinalityInstruction, cardinalityInstruction, cardinalityHeader, members, null, "local_amounts_verified")
        assertResponseRejectedOrHeld(cardinalityInstruction,
            responseRows(cardinalityInstruction, rowsFor(cardinalityInstruction), listOf(cardinality)))

        val siblingWithInventedProperty = rowsFor(instruction).toMutableList().also {
            it[1] = row(instruction, eggs, "organic eggs", "2", "count", "2", rowIndex = 1)
        }
        assertResponseRejectedOrHeld(instruction,
            responseRows(instruction, siblingWithInventedProperty, listOf(groupJson)))

        val omittedProofRows = listOf(
            row(instruction, bottle, "juice", "1", "pack", "a", "200", "ml", "200", "bottle"),
            rowsFor(instruction)[1],
        )
        assertResponseRejectedOrHeld(instruction, responseRows(instruction, omittedProofRows, listOf(groupJson)))

        val wrongTargetOperation = "no, eggs is 1 litre"
        val wrongTargetInstruction = "$header $bottle, $wrongTargetOperation, $eggs"
        val wrongTargetRows = listOf(
            row(wrongTargetInstruction, bottle, "juice", "1", "pack", "a", "200", "ml", "200", "bottle",
                corrections = listOf(correction(wrongTargetInstruction, "pack_size", wrongTargetOperation, "1", 200, "ml", 1, "l"))),
            row(wrongTargetInstruction, eggs, "eggs", "2", "count", "2", rowIndex = 1),
        )
        assertResponseRejectedOrHeld(wrongTargetInstruction, responseRows(wrongTargetInstruction, wrongTargetRows,
            listOf(group(wrongTargetInstruction, wrongTargetInstruction, header, members, null, "local_amounts_verified"))))

        listOf("maybe bring", "maybe").forEach { gap ->
            val gapInstruction = "$header $bottle, $operation, $gap, $eggs"
            assertResponseRejectedOrHeld(gapInstruction,
                responseRows(gapInstruction, rowsFor(gapInstruction), listOf(group(
                    gapInstruction, gapInstruction, header, members, null, "local_amounts_verified",
                ))))
        }

        val tailInstruction = "$instruction maybe bring bread"
        assertResponseRejectedOrHeld(tailInstruction,
            responseRows(tailInstruction, rowsFor(instruction), listOf(group(
                tailInstruction, instruction, header, members, null, "local_amounts_verified",
            ))))
    }

    @Test
    fun insteadCorrectionMustRecallExactOriginalAmountAndUnit() {
        val weight = "1 kg rice, make rice 750 g instead of 1 kg"
        val weightDraft = parse(weight, row(
            weight, "1 kg rice", "rice", "1", "kg", "1",
            corrections = listOf(correction(weight, "quantity", "make rice 750 g instead of 1 kg", "750", 1, "kg", 750, "g")),
        ))
        assertEquals("1", weightDraft.readyRows.single().item.quantity.toString())
        assertEquals(Quantity.Weight(750), weightDraft.prepareForReview().items.single().quantity)

        val count = "2 milk packets, make milk 3 packets instead of 2"
        val countDraft = parse(count, row(
            count, "2 milk packets", "milk", "2", "count", "2", packaging = "packet",
            corrections = listOf(correction(count, "quantity", "make milk 3 packets instead of 2", "3", 2, "count", 3, "count")),
        ))
        assertEquals("2", countDraft.readyRows.single().item.quantity.toString())
        assertEquals(Quantity.Count(3), countDraft.prepareForReview().items.single().quantity)

        val bareCount = "2 eggs, make it 3 instead of 2."
        val bareDraft = parse(bareCount, row(
            bareCount, "2 eggs", "eggs", "2", "count", "2",
            corrections = listOf(correction(bareCount, "quantity", "make it 3 instead of 2.", "3", 2, "count", 3, "count")),
        ))
        assertEquals(Quantity.Count(3), bareDraft.prepareForReview().items.single().quantity)

        val packSize = "one packet of 500 g rice, change rice pack size to 250 g instead of 500 g"
        val packDraft = parse(packSize, row(
            packSize, "one packet of 500 g rice", "rice", "1", "pack", "one",
            pack = "500", packUnit = "g", packText = "500", packaging = "packet",
            corrections = listOf(correction(packSize, "pack_size", "change rice pack size to 250 g instead of 500 g", "250", 500, "g", 250, "g")),
        ))
        assertEquals("500", packDraft.readyRows.single().item.packValue.toString())
        assertTrue(packDraft.prepareForReview().items.single().query.contains("250 g"))
    }

    @Test fun kejiOriginalPhysicalAmountSupportsExactKilogramCorrection() {
        listOf("kg", "keji", "KEJI").forEach { unit ->
            val primary = "one $unit rice"
            val operation = "make rice 2 kg instead of one $unit"
            val draft = parse("$primary; $operation", row(
                "$primary; $operation", primary, "rice", "1", "kg", "one",
                corrections = listOf(correction(
                    "$primary; $operation", "quantity", operation, "2", 1, "kg", 2, "kg",
                )),
            ))
            val prepared = draft.prepareForReview()
            assertEquals(unit, 1, prepared.items.size)
            assertEquals(unit, Quantity.Weight(2000), prepared.items.single().quantity)
            assertEquals(unit, "kg", prepared.items.single().intentQuantityProvenance?.corrected?.unit)
            assertEquals(unit, "$primary\nQuantity changed: 1 kg → 2 kg. You said: $operation",
                prepared.items.single().rawText)
            assertTrue(unit, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun unicodeNearMatchesCannotProveKilogramsInCorrectionScopes() {
        listOf("kej\u0131", "\u212Aeji", "keji\u0301").forEach { alias ->
            val primary = "one $alias rice"
            val operation = "make rice 2 kg instead of one $alias"
            val instruction = "$primary; $operation"
            assertRejectedOrHeld(instruction, row(
                instruction, primary, "rice", "1", "kg", "one",
                corrections = listOf(correction(
                    instruction, "quantity", operation, "2", 1, "kg", 2, "kg",
                )),
            ))
        }

        listOf("kej\u0131", "\u212Aeji").forEach { alias ->
            val primary = "one half-kg bag of rice"
            val operation = "make rice two half $alias bags"
            val instruction = "$primary; $operation"
            assertRejectedOrHeld(instruction, row(
                instruction, primary, "rice", "1", "pack", "one", "0.5", "kg", "half", "bag",
                corrections = listOf(correction(
                    instruction, "quantity", operation, "two", 1, "pack", 2, "pack",
                )),
            ))

            val kgPrimary = "one kg rice"
            val badInstead = "change rice to two kg instead of one $alias"
            val insteadInstruction = "$kgPrimary; $badInstead"
            assertRejectedOrHeld(insteadInstruction, row(
                insteadInstruction, kgPrimary, "rice", "1", "kg", "one",
                corrections = listOf(correction(
                    insteadInstruction, "quantity", badInstead, "two", 1, "kg", 2, "kg",
                )),
            ))

            val badBag = "make rice two kg, one two $alias bag"
            val bagInstruction = "$kgPrimary; $badBag"
            assertRejectedOrHeld(bagInstruction, row(
                bagInstruction, kgPrimary, "rice", "1", "kg", "one",
                corrections = listOf(correction(
                    bagInstruction, "quantity", badBag, "two", 1, "kg", 2, "kg",
                )),
            ))
        }
    }

    @Test
    fun quantityCorrectionRejectsChangedOrUnprovenContainersInLocalNamedAndRecalledText() {
        val changedLocal = "3 milk packs, make it 2 bottles"
        assertRejectedCorrection(changedLocal, row(
            changedLocal, "3 milk packs", "milk", "3", "count", "3", packaging = "pack",
            corrections = listOf(correction(
                changedLocal, "quantity", "make it 2 bottles", "2", 3, "count", 2, "count",
            )),
        ))

        val changedNamed = "3 milk packets, make milk 2 bottles"
        assertRejectedCorrection(changedNamed, row(
            changedNamed, "3 milk packets", "milk", "3", "count", "3", packaging = "packet",
            corrections = listOf(correction(
                changedNamed, "quantity", "make milk 2 bottles", "2", 3, "count", 2, "count",
            )),
        ))

        val changedRecalledForm = "3 milk packs, make milk 2 packs instead of 3 bottles"
        assertRejectedCorrection(changedRecalledForm, row(
            changedRecalledForm, "3 milk packs", "milk", "3", "count", "3", packaging = "pack",
            corrections = listOf(correction(
                changedRecalledForm, "quantity", "make milk 2 packs instead of 3 bottles",
                "2", 3, "count", 2, "count",
            )),
        ))

        val introducedWithoutSourceForm = "3 milk, make milk 2 bottles"
        assertRejectedCorrection(introducedWithoutSourceForm, row(
            introducedWithoutSourceForm, "3 milk", "milk", "3", "count", "3",
            corrections = listOf(correction(
                introducedWithoutSourceForm, "quantity", "make milk 2 bottles",
                "2", 3, "count", 2, "count",
            )),
        ))

        val changedAmountRole = "3 milk packets, make milk 2 packets"
        assertRejectedCorrection(changedAmountRole, row(
            changedAmountRole, "3 milk packets", "milk", "3", "count", "3", packaging = "packet",
            corrections = listOf(correction(
                changedAmountRole, "quantity", "make milk 2 packets",
                "2", 3, "count", 2, "pack",
            )),
        ))

        listOf("dabbe", "botal").forEach { unsupportedForm ->
            val phrase = "make milk 2 $unsupportedForm"
            val instruction = "3 milk packets; $phrase"
            assertRejectedCorrection(instruction, row(
                instruction, "3 milk packets", "milk", "3", "pack", "3", packaging = "packet",
                corrections = listOf(correction(instruction, "quantity", phrase, "2", 3, "pack", 2, "pack")),
            ))
        }

        // A modal can is conservatively ambiguous in this form guard, not inferred away.
        val modalSource = "Can you get 3 milk packs"
        val modal = "$modalSource; make milk 2 packs"
        assertRejectedCorrection(modal, row(
            modal, modalSource, "milk", "3", "pack", "3", packaging = "pack",
            corrections = listOf(correction(modal, "quantity", "make milk 2 packs", "2", 3, "pack", 2, "pack")),
        ))

        val sizeSource = "3 500 ml milk packets"
        val sizePhrase = "change milk pack size to 1 litre bottles"
        val sizeInstruction = "$sizeSource; $sizePhrase"
        assertRejectedCorrection(sizeInstruction, row(
            sizeInstruction, sizeSource, "milk", "3", "pack", "3",
            pack = "500", packUnit = "ml", packText = "500", packaging = "packet",
            corrections = listOf(correction(sizeInstruction, "pack_size", sizePhrase, "1", 500, "ml", 1, "l")),
        ))
    }

    @Test
    fun quantityCorrectionAcceptsOnlySourceProvenContainerFamilyAndStillAllowsNoContainerText() {
        listOf("pack", "packs", "packet", "packets").forEach { correctedContainer ->
            val instruction = "3 milk packets, make milk 2 $correctedContainer"
            val prepared = parse(instruction, row(
                instruction, "3 milk packets", "milk", "3", "count", "3", packaging = "packet",
                corrections = listOf(correction(
                    instruction, "quantity", "make milk 2 $correctedContainer",
                    "2", 3, "count", 2, "count",
                )),
            )).prepareForReview()
            assertEquals(Quantity.Count(2), prepared.items.single().quantity)
            assertTrue(prepared.items.single().query.contains("packet"))
        }

        val noContainer = "3 milk, make milk 2 instead of 3"
        val noContainerPrepared = parse(noContainer, row(
            noContainer, "3 milk", "milk", "3", "count", "3",
            corrections = listOf(correction(
                noContainer, "quantity", "make milk 2 instead of 3", "2", 3, "count", 2, "count",
            )),
        )).prepareForReview()
        assertEquals(Quantity.Count(2), noContainerPrepared.items.single().quantity)
        assertTrue(noContainerPrepared.items.single().query.contains("milk"))
    }

    @Test
    fun insteadCorrectionRejectsWrongOldAmountUnitConversionAlternativesAndTrailingText() {
        val invalid = listOf(
            Triple("1 kg rice, make rice 750 g instead of 2 kg", "make rice 750 g instead of 2 kg", "750"),
            Triple("1 kg rice, make rice 750 g instead of 1000 g", "make rice 750 g instead of 1000 g", "750"),
            Triple("1 kg rice, make rice 750 g instead of 1 kg or 2 kg", "make rice 750 g instead of 1 kg or 2 kg", "750"),
            Triple("1 kg rice, make rice 750 g instead of 1 kg and then 2 kg", "make rice 750 g instead of 1 kg and then 2 kg", "750"),
        )
        invalid.forEach { (instruction, source, amount) ->
            assertRejectedCorrection(instruction, row(
                instruction, "1 kg rice", "rice", "1", "kg", "1",
                corrections = listOf(correction(instruction, "quantity", source, amount, 1, "kg", 750, "g")),
            ))
        }

        // A number embedded in a larger numeric token is not a recalled amount.
        val embedded = "1 kg rice, make rice 750 g instead of 11 kg"
        assertRejectedCorrection(embedded, row(
            embedded, "1 kg rice", "rice", "1", "kg", "1",
            corrections = listOf(correction(embedded, "quantity", "make rice 750 g instead of 11 kg", "750", 1, "kg", 750, "g")),
        ))
    }

    @Test
    fun correctionPrefixMustBindLiteralUniqueTargetAndConsumeWholeSuffix() {
        val wrongTarget = "1 kg onions, make tomatoes 750 g"
        assertRejectedCorrection(wrongTarget, row(
            wrongTarget, "1 kg onions", "onions", "1", "kg", "1",
            corrections = listOf(correction(wrongTarget, "quantity", "make tomatoes 750 g", "750", 1, "kg", 750, "g")),
        ))

        val overlap = "1 kg tomatoes, 1 kg cherry tomatoes, make tomatoes 750 g"
        val first = row(overlap, "1 kg tomatoes", "tomatoes", "1", "kg", "1",
            corrections = listOf(correction(overlap, "quantity", "make tomatoes 750 g", "750", 1, "kg", 750, "g")))
        val second = row(overlap, "1 kg cherry tomatoes", "cherry tomatoes", "1", "kg", "1", rowIndex = 1)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(responseRows(overlap, listOf(first, second)), overlap)
        }

        val unsupportedPrefix = "1 kg rice, don't make rice 750 g"
        assertRejectedCorrection(unsupportedPrefix, row(
            unsupportedPrefix, "1 kg rice", "rice", "1", "kg", "1",
            corrections = listOf(correction(unsupportedPrefix, "quantity", "don't make rice 750 g", "750", 1, "kg", 750, "g")),
        ))

        listOf("each", "or 2 kg").forEach { suffix ->
            val instruction = "1 kg rice, make rice 750 g $suffix"
            assertRejectedCorrection(instruction, row(
                instruction, "1 kg rice", "rice", "1", "kg", "1",
                corrections = listOf(correction(instruction, "quantity", "make rice 750 g $suffix", "750", 1, "kg", 750, "g")),
            ))
        }

        val croppedNegative = "1 kg rice, do not make rice 750 g"
        val cropped = row(croppedNegative, "1 kg rice", "rice", "1", "kg", "1",
            corrections = listOf(correction(croppedNegative, "quantity", "make rice 750 g", "750", 1, "kg", 750, "g")))
        val croppedDraft = parse(croppedNegative, cropped).prepareForReview()
        assertTrue(croppedDraft.items.isEmpty())
        // Revoking the last numeric receipt leaves the negated operation
        // unresolved as a whole; it must not disappear into a cropped exclusion.
        assertEquals(listOf(croppedNegative), croppedDraft.unresolvedText)
    }

    @Test
    fun namedCorrectionTargetsPluralProductAmongSeveralItemsAndPreservesTheOthers() {
        val instruction = "1 kg tomatoes, 2 eggs. Correction: make the tomatoes 750 g instead of 1 kg; everything else stays as first listed."
        val tomatoes = row(instruction, "1 kg tomatoes", "tomatoes", "1", "kg", "1",
            corrections = listOf(correction(instruction, "quantity", "make the tomatoes 750 g instead of 1 kg", "750", 1, "kg", 750, "g")))
        val eggs = row(instruction, "2 eggs", "eggs", "2", "count", "2", rowIndex = 1)
        val draft = GroceryIntentReviewV2Response.parse(responseRows(instruction, listOf(tomatoes, eggs)), instruction)
            .draft.prepareForReview()
        assertEquals(2, draft.items.size)
        assertEquals(Quantity.Weight(750), draft.items[0].quantity)
        assertEquals(Quantity.Count(2), draft.items[1].quantity)
        assertTrue(draft.unresolvedText.isEmpty())
        assertTrue(draft.items[0].rawText.contains("1 kg tomatoes"))
    }

    @Test
    fun localQuantityPrefixWinsAndCannotBeUsedForPackSize() {
        val quantity = "two milk packets, make it one"
        val correctedQuantity = parse(quantity, row(
            quantity, "two milk packets", "milk", "2", "count", "two",
            packaging = "packet",
            corrections = listOf(correction(quantity, "quantity", "make it one", "one", 2, "count", 1, "count")),
        )).prepareForReview()
        assertEquals(Quantity.Count(1), correctedQuantity.items.single().quantity)
        assertTrue(correctedQuantity.items.single().query.contains("packet"))

        val packSize = "one packet of 500 g rice, make it 250 g"
        assertRejectedCorrection(packSize, row(
            packSize, "one packet of 500 g rice", "rice", "1", "pack", "one",
            pack = "500", packUnit = "g", packText = "500", packaging = "packet",
            corrections = listOf(correction(packSize, "pack_size", "make it 250 g", "250", 500, "g", 250, "g")),
        ))
    }

    @Test
    fun quantityAndPackSizeCorrectionsUseSeparateFieldsWithoutSwapping() {
        val instruction = "one packet of 500 g rice, change rice to two packets, change rice pack size to 250 g"
        val decoded = parse(
            instruction,
            row(
                instruction, source = "one packet of 500 g rice", name = "rice",
                quantity = "1", quantityUnit = "pack", quantityText = "one",
                pack = "500", packUnit = "g", packText = "500", packaging = "packet",
                corrections = listOf(
                    correction(instruction, "quantity", "change rice to two packets", "two", 1, "pack", 2, "pack"),
                    correction(instruction, "pack_size", "change rice pack size to 250 g", "250", 500, "g", 250, "g"),
                ),
            ),
        )
        val row = decoded.readyRows.single()
        assertEquals("1", row.item.quantity.toString())
        assertEquals("pack", row.item.quantityUnit)
        assertEquals("500", row.item.packValue.toString())
        assertEquals("g", row.item.packUnit)
        val prepared = decoded.prepareForReview().items.single()
        assertEquals(Quantity.Count(2), prepared.quantity)
        assertTrue(prepared.retailPackCount)
        assertTrue(prepared.query.contains("250 g"))
        assertFalse(prepared.query.contains("500 g"))
    }

    @Test
    fun correctionTextCannotBecomePositiveNameOrDietaryEvidence() {
        val nameInstruction = "one milk, make Amul milk two"
        assertRejectedCorrection(
            nameInstruction,
            row(
                nameInstruction, source = "one milk", name = "Amul milk",
                quantity = "1", quantityUnit = "count", quantityText = "one",
                corrections = listOf(correction(nameInstruction, "quantity", "make Amul milk two", "two", 1, "count", 2, "count")),
            ),
        )

        val dietInstruction = "one milk, make organic milk two"
        assertRejectedCorrection(
            dietInstruction,
            row(
                dietInstruction, source = "one milk", name = "milk", required = listOf("organic"),
                quantity = "1", quantityUnit = "count", quantityText = "one",
                corrections = listOf(correction(dietInstruction, "quantity", "make organic milk two", "two", 1, "count", 2, "count")),
            ),
        )
    }

    @Test
    fun oldValueAndUnitMustMatchTheOriginalRow() {
        val instruction = "two milk packets, no make that one"
        for ((oldValue, oldUnit) in listOf(3 to "count", 2 to "pack")) {
            val body = response(
                instruction,
                row(
                    instruction, source = "two milk packets", name = "milk",
                    quantity = "2", quantityUnit = "count", quantityText = "two",
                    corrections = listOf(correction(instruction, "quantity", "no make that one", "one", oldValue, oldUnit, 1, "count")),
                ),
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(body, instruction)
            }
        }
    }

    @Test
    fun newAmountMustBeInsideCorrectionQuoteAndCorrectionCannotOverlapPrimaryOrPeer() {
        val instruction = "two milk packets, no make that one"
        val primaryAmount = correction(instruction, "quantity", "no make that one", "two", 2, "count", 1, "count", amountInPrimary = true)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, row(instruction, "two milk packets", "milk", "2", "count", "two", corrections = listOf(primaryAmount))),
                instruction,
            )
        }

        val overlappingSource = "packets, no make that one"
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, row(
                    instruction, "two milk packets", "milk", "2", "count", "two",
                    corrections = listOf(correction(instruction, "quantity", overlappingSource, "one", 2, "count", 1, "count")),
                )),
                instruction,
            )
        }

        val peerInstruction = "two 500 g milk packets, change milk to three packets and pack size 250 g"
        val peers = listOf(
            correction(peerInstruction, "quantity", "change milk to three packets and pack size 250 g", "three", 2, "pack", 3, "pack"),
            correction(peerInstruction, "pack_size", "pack size 250 g", "250", 500, "g", 250, "g"),
        )
        // Strict prefix/tail checks now reject these individually as well as
        // rejecting the overlapping combined proof; neither can hide its peer.
        peers.forEach { peer ->
            assertRejectedCorrection(peerInstruction, row(
                peerInstruction, "two 500 g milk packets", "milk", "2", "pack", "two",
                pack = "500", packUnit = "g", packText = "500", corrections = listOf(peer),
            ))
        }
        val peerRows = row(
            peerInstruction, "two 500 g milk packets", "milk", "2", "pack", "two",
            pack = "500", packUnit = "g", packText = "500",
            corrections = peers,
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(peerInstruction, peerRows), peerInstruction)
        }
    }

    @Test
    fun wrongUnitFamilyOversizedCountUnsupportedKindDuplicateFieldAndForeignKeysReject() {
        // Build each malformed wire row independently so every rejection is attributable.
        val wrongFamilyInstruction = "two milk packets, no make that 2 kg"
        assertRejectedCorrection(
            wrongFamilyInstruction,
            row(wrongFamilyInstruction, "two milk packets", "milk", "2", "count", "two",
                corrections = listOf(correction(wrongFamilyInstruction, "quantity", "no make that 2 kg", "2", 2, "count", 2, "kg"))),
        )

        val oversizedInstruction = "2 milk packets, make milk 21 packets"
        assertRejectedCorrection(
            oversizedInstruction,
            row(oversizedInstruction, "2 milk packets", "milk", "2", "count", "2",
                corrections = listOf(correction(oversizedInstruction, "quantity", "make milk 21 packets", "21", 2, "count", 21, "count"))),
        )

        val kindInstruction = "two milk packets, no make that one"
        assertRejectedCorrection(
            kindInstruction,
            row(kindInstruction, "two milk packets", "milk", "2", "count", "two",
                corrections = listOf(correction(kindInstruction, "product", "no make that one", "one", 2, "count", 1, "count"))),
        )

        val duplicateInstruction = "two milk packets, change milk to one packet, change milk to three packets"
        assertRejectedCorrection(
            duplicateInstruction,
            row(duplicateInstruction, "two milk packets", "milk", "2", "count", "two",
                corrections = listOf(
                    correction(duplicateInstruction, "quantity", "change milk to one packet", "one", 2, "count", 1, "count"),
                    correction(duplicateInstruction, "quantity", "change milk to three packets", "three", 2, "count", 3, "count"),
                )),
        )

        val foreignInstruction = "two milk packets, no make that one"
        val foreign = correction(foreignInstruction, "quantity", "no make that one", "one", 2, "count", 1, "count", extra = ",\"action\":\"add\"")
        assertRejectedCorrection(
            foreignInstruction,
            row(foreignInstruction, "two milk packets", "milk", "2", "count", "two", corrections = listOf(foreign)),
        )
    }

    private fun assertRejectedCorrection(instruction: String, ready: String) {
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready), instruction)
        }
    }

    private fun assertRejectedOrHeld(instruction: String, ready: String) {
        val prepared = try {
            GroceryIntentReviewV2Response.parse(response(instruction, ready), instruction).draft.prepareForReview()
        } catch (_: IllegalArgumentException) {
            return
        }
        assertTrue("Unsafe correction became ready: $instruction", prepared.items.isEmpty())
        assertTrue("Unsafe correction vanished from review: $instruction",
            prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
    }

    private fun assertResponseRejectedOrHeld(instruction: String, body: String) {
        val prepared = try {
            GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
        } catch (_: IllegalArgumentException) {
            return
        }
        assertTrue("Unsafe grouped correction became ready: $instruction", prepared.items.isEmpty())
        assertTrue("Unsafe grouped correction vanished from review: $instruction",
            prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
    }

    private fun parse(instruction: String, ready: String): GroceryIntentReviewV2 =
        GroceryIntentReviewV2Response.parse(response(instruction, ready), instruction).draft

    private fun response(instruction: String, ready: String): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":1,"ready_rows":[$ready],"blocked_rows":[]},"model":"test","promptVersion":"v2-test","latencyMs":1,"usage":{}}"""

    private fun responseRows(instruction: String, readyRows: List<String>, groups: List<String> = emptyList()): String {
        val groupProperty = if (groups.isEmpty()) "" else ",\"groups\":[${groups.joinToString(",")}]"
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":${readyRows.size},"ready_rows":[${readyRows.joinToString(",")}],"blocked_rows":[]$groupProperty},"model":"test","promptVersion":"v2-test","latencyMs":1,"usage":{}}"""
    }

    private fun group(
        instruction: String,
        source: String,
        header: String,
        members: List<Pair<Int, String>>,
        packaging: String?,
        amountStatus: String,
        total: String? = null,
        each: String? = null,
    ): String {
        val memberJson = members.joinToString(",") { (index, unit) ->
            """{"row_index":$index,"value":${if (index == 0) 1 else 2},"unit":"$unit","origin":"local"}"""
        }
        fun amount(sourceText: String?, value: Int) = sourceText?.let { text ->
            """{"source":{"text":${json(text)},"start_utf16":${instruction.indexOf(text)},"end_utf16":${instruction.indexOf(text) + text.length}},"amount_text":"$value","value":$value,"unit":"count"}"""
        } ?: "null"
        val start = instruction.indexOf(source).also { require(it >= 0) }
        val headerStart = instruction.indexOf(header).also { require(it >= start) }
        return """{"status":"structurally_verified","amount_status":${json(amountStatus)},"source":{"text":${json(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"header":{"text":${json(header)},"start_utf16":$headerStart,"end_utf16":${headerStart + header.length}},"member_indices":[${members.joinToString(",") { it.first.toString() }}],"members":[$memberJson],"packaging":${packaging?.let(::json) ?: "null"},"total":${amount(total, 3)},"each":${amount(each, 2)}}"""
    }

    private fun row(
        instruction: String,
        source: String,
        name: String,
        quantity: String = "null",
        quantityUnit: String = "unspecified",
        quantityText: String? = null,
        pack: String = "null",
        packUnit: String = "unspecified",
        packText: String? = null,
        packaging: String? = null,
        required: List<String> = emptyList(),
        corrections: List<String> = emptyList(),
        rowIndex: Int = 0,
    ): String {
        val start = instruction.indexOf(source).also { require(it >= 0) }
        val end = start + source.length
        val origin = if (quantity == "null") "not_stated" else "stated"
        val role = if (quantity == "null" && pack == "null") "not_stated" else "matched"
        val correctionJson = if (corrections.isEmpty()) "" else ",\"corrections\":[${corrections.joinToString(",")}]"
        val requiredJson = required.joinToString(",") { json(it) }
        return """{"row_index":$rowIndex,"item":{"name":${json(name)},"quantity":$quantity,"quantity_unit":${json(quantityUnit)},"pack_value":$pack,"pack_unit":${json(packUnit)},"packaging":${packaging?.let(::json) ?: "null"},"required":[$requiredJson],"excluded":[],"source_text":${json(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${quantityText?.let(::json) ?: "null"},"pack_text":${packText?.let(::json) ?: "null"}},"evidence":{"primary":{"text":${json(source)},"start_utf16":$start,"end_utf16":$end},"supporting":[]},"quantity_origin":${json(origin)},"amount_role_status":${json(role)}$correctionJson}"""
    }

    private fun correction(
        instruction: String,
        kind: String,
        source: String,
        amount: String,
        oldValue: Int,
        oldUnit: String,
        newValue: Number,
        newUnit: String,
        amountInPrimary: Boolean = false,
        extra: String = "",
    ): String {
        val sourceStart = instruction.indexOf(source).also { require(it >= 0) }
        val sourceEnd = sourceStart + source.length
        val amountStart = if (amountInPrimary) instruction.indexOf(amount) else sourceStart + source.indexOf(amount)
        require(amountStart >= 0)
        val amountEnd = amountStart + amount.length
        return """{"kind":${json(kind)},"source":{"text":${json(source)},"start_utf16":$sourceStart,"end_utf16":$sourceEnd},"new_amount":{"text":${json(amount)},"start_utf16":$amountStart,"end_utf16":$amountEnd},"old_value":$oldValue,"old_unit":${json(oldUnit)},"new_value":$newValue,"new_unit":${json(newUnit)}$extra}"""
    }

    private fun json(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
