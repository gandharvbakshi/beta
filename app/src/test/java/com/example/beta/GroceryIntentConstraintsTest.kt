package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentConstraintsTest {
    @Test
    fun mixedDepartmentRequestCannotMakePartialOrGenericCategoryBasketReady(): Unit {
        val intro = "Please get groceries and vegetables:"
        val requests = listOf("1 rice" to "rice", "1 notebook" to "notebook", "1 vegetables" to "vegetables")
        for ((source, product) in requests) {
            val instruction = "$intro $source"
            val primaries = primaryQuotes(instruction, source)
            val notes = deriveFulfillmentConstraints(instruction, primaries,
                allowListPurpose = true, allowPurposeGroups = true)
            val constraints = notes.map { constraintJson(instruction, it.source.text, it.kind) }
            val prepared = GroceryIntentReviewV2Response.parse(response(instruction,
                listOf(countedRow(0, instruction, source, name = product)), constraints), instruction)
                .draft.prepareForReview()
            assertFalse("$product cannot satisfy the complete mixed-department request",
                prepared.items.size == 1 && prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test
    fun recognizesOnlyTheClosedHeadingAndPairedTimedOriginForms(): Unit {
        val positive = listOf(
            "Kal subah ke liye list likh lo: 1 milk" to "Kal subah ke liye list likh lo:",
            "Please bring these groceries tomorrow morning: 1 milk" to "Please bring these groceries tomorrow morning:",
            "For tomorrow please prepare my grocery list: 1 milk" to "For tomorrow please prepare my grocery list:",
            "Please buy the following items if available: 1 milk" to "Please buy the following items if available:",
            "Kal mandi se 1 milk, 2 eggs le aaiyega." to "Kal mandi se",
        )
        positive.forEach { (instruction, expected) ->
            val primaryTexts = if (instruction.contains("2 eggs")) arrayOf("1 milk", "2 eggs") else arrayOf("1 milk")
            val primaries = primaryQuotes(instruction, *primaryTexts)
            assertEquals(expected, deriveFulfillmentConstraints(instruction, primaries).single().source.text)
        }

        val newFulfillment = listOf(
            "Namaste, aaj bazaar ki list bana do: 1 milk" to "Namaste, aaj bazaar ki list bana do:",
            "Hello, please bring these groceries tomorrow: 1 milk" to "Hello, please bring these groceries tomorrow:",
            "Beta, aaj bazaar ki list bana do: 1 milk" to "Beta, aaj bazaar ki list bana do:",
            "Zara aaj mandi ka list likh lijiye: 1 milk" to "Zara aaj mandi ka list likh lijiye:",
            "Please aaj list bana do: 1 milk" to "Please aaj list bana do:",
            "Aaj list bana do: 1 milk" to "Aaj list bana do:",
        )
        newFulfillment.forEach { (instruction, expected) ->
            val constraint = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk")).single()
            assertEquals("fulfillment", constraint.kind)
            assertEquals(expected, constraint.source.text)
        }
    }

    @Test
    fun sourceQuotesUseExactUtf16Offsets(): Unit {
        val instruction = "😀. Please bring these groceries tomorrow morning: 1 milk"
        val expected = "Please bring these groceries tomorrow morning:"
        val constraint = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk")).single().source
        assertEquals(expected, constraint.text)
        assertEquals(instruction.indexOf(expected), constraint.startUtf16)
        assertEquals(instruction.indexOf(expected) + expected.length, constraint.endUtf16)
    }

    @Test
    fun leadingTemporalReceiptsAcceptClosedGreetingsObjectsTimesAndBoundaries(): Unit {
        val greetings = listOf("Namaste, ", "Hello, ", "Hi, ", "Arre, ", "Beta, ")
        greetings.forEach { greeting ->
            val instruction = "${greeting}Could you get a few things before the weekend? 1 milk, 2 rice"
            val expected = "${greeting}Could you get a few things before the weekend?"
            val receipt = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 rice")).single()
            assertEquals(expected, receipt.source.text)
            assertEquals(instruction.indexOf(expected), receipt.source.startUtf16)
            assertEquals(instruction.indexOf(expected) + expected.length, receipt.source.endUtf16)
        }

        val cases = listOf(
            "Could you get this grocery list today" to "?",
            "Can you please bring the shopping list tomorrow" to ":",
            "Would you kindly buy my groceries tomorrow morning" to ".",
            "Could you order our shopping list tomorrow evening" to ";",
            "Please deliver these items this morning" to "!",
            "Send a few things this evening" to ":",
            "Get a few things tonight" to "：",
            "Could you get a few things by the weekend" to ".",
            "Could you get a few things before the weekend" to ";",
            "Could you get a few things tomorrow, if you can" to ":",
        )
        cases.forEach { (heading, boundary) ->
            val instruction = "$heading$boundary 1 milk, 2 rice"
            val expected = "$heading$boundary"
            val receipt = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 rice")).single()
            assertEquals("$instruction should preserve its exact fulfillment clause", expected, receipt.source.text)
            assertEquals(instruction.indexOf(expected), receipt.source.startUtf16)
            assertEquals(instruction.indexOf(expected) + expected.length, receipt.source.endUtf16)
        }

        val whitespaceHeading = "Could you get a few things before the weekend?"
        listOf("\n", "\r\n", "\t").forEach { separator ->
            val instruction = "$whitespaceHeading$separator" + "1 milk, 2 rice"
            val receipt = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 rice")).single()
            assertEquals(whitespaceHeading, receipt.source.text)
        }

        val temporalForms = listOf(
            "Could you get a few things today?",
            "Could you get a few things tomorrow?",
            "Could you get a few things tomorrow morning?",
            "Could you get a few things tomorrow evening?",
            "Could you get a few things this morning?",
            "Could you get a few things this evening?",
            "Could you get a few things tonight?",
            "Could you get a few things by the weekend?",
            "Could you get a few things before the weekend?",
        )
        temporalForms.forEach { heading ->
            val instruction = "$heading 1 milk"
            assertEquals(heading, heading, deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk")).single().source.text)
        }
    }

    @Test
    fun temporalEditorialShoppingRequestsRemainExactFulfillmentReceiptsNotNeutralContext(): Unit {
        val cases = listOf(
            "For today's restock, please note these items:" to "For today's restock, please note these items:",
            "For today's shopping, jot these down for me:" to "For today's shopping, jot these down for me:",
            "For tomorrow's restock, please put down these items:" to "For tomorrow's restock, please put down these items:",
            "Aaj ki grocery list mein likh lo:" to "Aaj ki grocery list mein likh lo:",
            "Kal ki shopping list mein likh lijiye:" to "Kal ki shopping list mein likh lijiye:",
        )
        cases.forEach { (heading, expected) ->
            val instruction = "$heading 1 milk, 2 eggs"
            val primaries = primaryQuotes(instruction, "1 milk", "2 eggs")
            val receipt = deriveFulfillmentConstraints(instruction, primaries).single()
            assertEquals("fulfillment", receipt.kind)
            assertEquals(expected, receipt.source.text)
            assertEquals(instruction.indexOf(expected), receipt.source.startUtf16)
            assertEquals(instruction.indexOf(expected) + expected.length, receipt.source.endUtf16)
            assertTrue("temporal words stay in the retained receipt", neutralGroceryIntentContextSpans(instruction,
                primaries.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }).none {
                it.startUtf16 == 0 && it.endUtf16 >= primaries.first().startUtf16
            })
        }
    }

    @Test
    fun temporalEditorialRequestsRejectOpenSlotsQualifiersAndMalformedBoundaries(): Unit {
        val rejected = listOf(
            "For today's restock with no sugar, please note:",
            "For today's restock, please note BrandA:",
            "For today's restock, please note 2 items:",
            "For today's restock, please note if available:",
            "For today's restock, maybe add bread, then please note:",
            "For today's restock, please note\u00a0these items:",
            "Aaj ki grocery list with no sugar mein likh lo:",
        )
        rejected.forEach { heading ->
            val instruction = "$heading 1 milk, 2 eggs"
            val primaries = primaryQuotes(instruction, "1 milk", "2 eggs")
            assertTrue("invalid temporal editorial frame must not create a receipt: $heading",
                deriveFulfillmentConstraints(instruction, primaries).isEmpty())
            assertTrue("invalid temporal frame cannot be neutralized: $heading",
                neutralGroceryIntentContextSpans(instruction,
                    primaries.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }).none { it.startUtf16 == 0 })
        }
    }

    @Test
    fun leadingTemporalReceiptMustBeCompleteImmediateAndProductFree(): Unit {
        val rejected = listOf(
            "Could you get a few organic things today?",
            "Could you get BrandA groceries today?",
            "Could you get a few low-fat items tomorrow?",
            "Could you get two things today?",
            "Could you get a few things today under 500 rupees?",
            "Could you get a few things today or rice?",
            "Could you get a few things today maybe?",
            "Could you get a few things today, add sugar too?",
            "Could you get a few things tomorrow, guarantee delivery?",
        )
        rejected.forEach { heading ->
            val instruction = "$heading 1 milk, 2 rice"
            assertTrue(heading, deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 rice")).isEmpty())
        }

        val nonImmediate = listOf(
            "Could you get a few things today? maybe 1 milk, 2 rice",
            "Could you get a few things today? tomorrow 1 milk, 2 rice",
            "Could you get a few things today?",
        )
        nonImmediate.forEach { instruction ->
            val primaries = if (instruction.contains("1 milk")) {
                primaryQuotes(instruction, "1 milk").let { first ->
                    if (instruction.contains("2 rice")) first + primaryQuotes(instruction, "2 rice") else first
                }
            } else emptyList()
            assertTrue("body must be immediately bound after the clause boundary: $instruction",
                deriveFulfillmentConstraints(instruction, primaries).isEmpty())
        }

        val trailingUnknown = "Could you get a few things today? 1 milk later, 2 rice"
        val trailingPrimaries = primaryQuotes(trailingUnknown, "1 milk", "2 rice")
        val trailingReceipt = deriveFulfillmentConstraints(trailingUnknown, trailingPrimaries).single()
        assertEquals("Could you get a few things today?", trailingReceipt.source.text)
        val trailingRows = listOf("1 milk", "2 rice").mapIndexed { index, source -> countedRow(index, trailingUnknown, source) }
        val trailingDraft = GroceryIntentReviewV2Response.parse(
            response(trailingUnknown, trailingRows,
                listOf(constraintJson(trailingUnknown, trailingReceipt.source.text, "fulfillment"))), trailingUnknown,
        ).draft.prepareForReview()
        assertTrue("unknown text after an immediate item must remain unresolved",
            trailingDraft.unresolvedText.any { "later" in it })

        val afterItem = "1 milk. Could you get a few things today? 2 rice"
        assertTrue(deriveFulfillmentConstraints(afterItem, primaryQuotes(afterItem, "1 milk", "2 rice")).isEmpty())
        listOf("\t", "\u0000").forEach { control ->
            val instruction = "Could you${control} get a few things today? 1 milk, 2 rice"
            assertTrue(instruction, deriveFulfillmentConstraints(instruction,
                primaryQuotes(instruction, "1 milk", "2 rice")).isEmpty())
        }
        listOf("\u00a0", "\u0085", "\u2028").forEach { separator ->
            val instruction = "Could you get a few things today?$separator" + "1 milk, 2 rice"
            assertTrue(instruction, deriveFulfillmentConstraints(instruction,
                primaryQuotes(instruction, "1 milk", "2 rice")).isEmpty())
        }
    }

    @Test
    fun fulfillmentSourceMustBeAnExactCodecValidQuoteAcrossAllDerivationBranches(): Unit {
        val invalidSources = listOf(
            "Could you get a few things before the weekend\t? 1 milk",
            "Please bring these groceries tomorrow\t: 1 milk",
            "Kal mandi\t se 1 milk, 2 eggs le aaiyega.",
            "Could you get a few things before the weekend\u00a0? 1 milk",
            "Could you get a few things before the weekend\u2028? 1 milk",
            "Please bring these groceries tomorrow\u00a0: 1 milk",
            "Please bring these groceries tomorrow\u2028: 1 milk",
        )
        invalidSources.forEach { instruction ->
            val primaries = when {
                instruction.startsWith("Kal") -> primaryQuotes(instruction, "1 milk", "2 eggs")
                else -> primaryQuotes(instruction, "1 milk")
            }
            assertTrue("unsafe terminal/source whitespace must not be persisted: $instruction",
                deriveFulfillmentConstraints(instruction, primaries).isEmpty())
        }

        val leadingHeading = "Could you get a few things today?"
        val leadingInstruction = "  $leadingHeading   1 milk"
        val leadingReceipt = deriveFulfillmentConstraints(
            leadingInstruction, primaryQuotes(leadingInstruction, "1 milk"),
        ).single().source
        assertEquals(leadingHeading, leadingReceipt.text)
        assertEquals(leadingInstruction.indexOf(leadingHeading), leadingReceipt.startUtf16)

        val paddedClauses = listOf(
            "Could you get a few things before the weekend?" to "temporal",
            "Please bring these groceries tomorrow:" to "legacy-colon",
            "Kal mandi se" to "timed-origin",
        )
        fun padFirstGapToLength(text: String, targetLength: Int): String {
            val gap = text.indexOf(' ')
            require(gap >= 0 && targetLength >= text.length)
            return text.substring(0, gap) + " ".repeat(targetLength - text.length + 1) + text.substring(gap + 1)
        }

        paddedClauses.forEach { (base, branch) ->
            for (targetLength in listOf(180, 181)) {
                val padded = padFirstGapToLength(base, targetLength)
                val instruction = when (branch) {
                    "temporal", "legacy-colon" -> "$padded 1 milk"
                    "timed-origin" -> "$padded 1 milk, 2 eggs le aaiyega."
                    else -> error("Unknown fulfillment test branch: $branch")
                }
                val primaries = if (branch == "timed-origin") {
                    primaryQuotes(instruction, "1 milk", "2 eggs")
                } else primaryQuotes(instruction, "1 milk")
                val derived = deriveFulfillmentConstraints(instruction, primaries)
                if (targetLength == 180) {
                    val receipt = derived.single().source
                    assertEquals("$branch source quote should retain its full valid length", 180, receipt.text.length)
                    assertTrue("$branch quote should pass the persistence codec", GroceryIntentConstraintCodec.isValidQuote(receipt.text))
                    val encoded = requireNotNull(GroceryIntentConstraintCodec.encode(listOf(receipt.text)))
                    assertEquals(listOf(receipt.text), GroceryIntentConstraintCodec.decode(encoded))
                } else {
                    assertTrue("$branch source longer than codec limit must not be emitted", derived.isEmpty())
                }
            }
        }
    }

    @Test
    fun temporalFulfillmentReceiptSurvivesPreparationAndRequiresAcknowledgement(): Unit {
        val heading = "Could you get a few things before the weekend?"
        val instruction = "$heading 1 milk, 2 rice"
        val sources = listOf("1 milk", "2 rice")
        val rows = sources.mapIndexed { index, source -> countedRow(index, instruction, source) }
        val derived = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, *sources.toTypedArray())).single()
        assertEquals("fulfillment", derived.kind)
        assertEquals(heading, derived.source.text)

        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, rows, listOf(constraintJson(instruction, heading, "fulfillment"))), instruction,
        ).draft
        val originalRows = draft.readyRows.toList()
        val prepared = draft.prepareForReview()
        assertEquals(originalRows, draft.readyRows)
        assertEquals(listOf("milk", "rice"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertEquals(listOf(heading), prepared.fulfillmentConstraints)
        assertTrue(prepared.sharedGroupNotes.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, rows), instruction)
        }
        val incompleteQuote = heading.removeSuffix("?")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, rows, listOf(constraintJson(instruction, incompleteQuote, "fulfillment"))), instruction,
            )
        }

        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes, prepared.fulfillmentConstraints)
        assertEquals(listOf(heading), gate.fulfillmentConstraints)
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        assertTrue(gate.canApply())
        gate.beginReview()
        assertFalse("a fresh review must require acknowledgement again", gate.canApply())

        val persisted = GroceryIntentConstraintCodec.encode(prepared.fulfillmentConstraints)
        assertTrue(persisted != null)
        assertEquals(prepared.fulfillmentConstraints, GroceryIntentConstraintCodec.decode(requireNotNull(persisted)))
    }

    @Test
    fun trailingFulfillmentReceiptsAreOptInExactAndFollowAllPrimaryItems(): Unit {
        val cases = listOf(
            "Please bring these on my list when you come over." to ".",
            "Could you please get everything on the list when you can?" to "?",
            "Kindly deliver those on this list tomorrow." to ".",
            "Would you send these on our list before the weekend!" to "!",
        )
        cases.forEach { (raw, _) ->
            val note = raw
            val instruction = "1 milk, 2 rice. $note"
            val primaries = primaryQuotes(instruction, "1 milk", "2 rice")
            assertTrue("trailing support is disabled by default: $instruction",
                deriveFulfillmentConstraints(instruction, primaries).none { it.source.text == note })
            val receipt = deriveFulfillmentConstraints(
                instruction, primaries, allowTrailingFulfillment = true,
            ).single()
            assertEquals("fulfillment", receipt.kind)
            assertEquals(note, receipt.source.text)
            assertEquals(instruction.indexOf(note), receipt.source.startUtf16)
            assertEquals(instruction.indexOf(note) + note.length, receipt.source.endUtf16)
        }

        listOf("\t;\n", "!\r\n", "?\n").forEach { boundary ->
            val note = "Please bring these on my list tomorrow."
            val instruction = "1 milk, 2 rice${boundary} $note"
            val receipt = deriveFulfillmentConstraints(
                instruction, primaryQuotes(instruction, "1 milk", "2 rice"), allowTrailingFulfillment = true,
            ).single()
            assertEquals(note, receipt.source.text)
            assertEquals(instruction.indexOf(note), receipt.source.startUtf16)
        }

        val before = "Please get these on my list tomorrow."
        val instruction = "1 milk, 2 rice. $before 3 eggs"
        val primaries = primaryQuotes(instruction, "1 milk", "2 rice", "3 eggs")
        assertTrue("a request before the last primary is not a terminal receipt",
            deriveFulfillmentConstraints(instruction, primaries, allowTrailingFulfillment = true).none {
                it.source.text == before
            })
    }

    @Test
    fun leadingAndTrailingFulfillmentReceiptsPersistInOrderAndRequireReviewAcknowledgement(): Unit {
        val leading = "Could you get a few things before the weekend?"
        val trailing = "Please bring these on my list when you come over."
        val instruction = "$leading 1 milk, 2 rice. $trailing"
        val sources = listOf("1 milk", "2 rice")
        val primaries = primaryQuotes(instruction, *sources.toTypedArray())
        val derived = deriveFulfillmentConstraints(instruction, primaries, allowTrailingFulfillment = true)
        assertEquals(listOf(leading, trailing), derived.map { it.source.text })
        assertTrue("legacy default behavior remains leading-only",
            deriveFulfillmentConstraints(instruction, primaries).map { it.source.text } == listOf(leading))

        val rows = sources.mapIndexed { index, source -> countedRow(index, instruction, source) }
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, rows, derived.map { constraintJson(instruction, it.source.text, it.kind) }),
            instruction,
        ).draft
        val prepared = draft.prepareForReview()
        assertEquals(listOf("milk", "rice"), prepared.items.map { it.query })
        assertEquals(listOf(leading, trailing), prepared.fulfillmentConstraints)
        assertTrue(prepared.unresolvedText.isEmpty())

        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes, prepared.fulfillmentConstraints)
        assertFalse("both notes require explicit acknowledgement", gate.canApply())
        gate.acknowledge(true)
        assertTrue(gate.canApply())
        gate.beginReview()
        assertFalse("reviewing again resets acknowledgement", gate.canApply())
        val encoded = requireNotNull(GroceryIntentConstraintCodec.encode(prepared.fulfillmentConstraints))
        assertEquals(prepared.fulfillmentConstraints, GroceryIntentConstraintCodec.decode(encoded))
    }

    @Test
    fun trailingFulfillmentRejectsMeaningfulExtraClausesAndInvalidBoundaryEvidence(): Unit {
        val invalid = listOf(
            "1 milk. Please bring these on my list tomorrow, but use organic milk.",
            "1 milk. Please bring milk and eggs on my list tomorrow.",
            "1 milk. Please bring two things on my list tomorrow.",
            "1 milk. Please bring these on my list tomorrow if milk is available.",
            "1 milk. Please bring these on my list tomorrow at 5 pm.",
            "1 milk. Please bring these on my list tomorrow under 500 rupees.",
            "1 milk. Please bring BrandA on my list tomorrow.",
            "1 milk. Please bring these on my list tomorrow; add sugar too.",
            "1 milk. Please\tbring these on my list tomorrow.",
            "1 milk. Please\u0000bring these on my list tomorrow.",
            "1 milk. Please bring these on my list tomorrow unless I call.",
            "1 milk. Please bring these on my list tomorrow. 2 rice",
        )
        invalid.forEach { instruction ->
            val allSourceItems = listOf("1 milk", "2 rice", "3 eggs").filter(instruction::contains)
            val primaries = primaryQuotes(instruction, *allSourceItems.toTypedArray())
            assertTrue("unsupported trailing text must not authorize a receipt: $instruction",
                deriveFulfillmentConstraints(instruction, primaries, allowTrailingFulfillment = true)
                    .none { it.source.startUtf16 >= instruction.indexOf('.') + 1 })
        }

        val baseNote = "Please bring these on my list tomorrow."
        fun paddedNote(length: Int): String {
            val gap = baseNote.indexOf(' ')
            return baseNote.substring(0, gap) + " ".repeat(length - baseNote.length + 1) + baseNote.substring(gap + 1)
        }
        val note180 = paddedNote(180)
        val valid180 = "1 milk. $note180"
        val receipt180 = deriveFulfillmentConstraints(
            valid180, primaryQuotes(valid180, "1 milk"), allowTrailingFulfillment = true,
        ).single().source
        assertEquals(180, receipt180.text.length)
        assertTrue(GroceryIntentConstraintCodec.isValidQuote(receipt180.text))
        assertEquals(listOf(receipt180.text), GroceryIntentConstraintCodec.decode(
            requireNotNull(GroceryIntentConstraintCodec.encode(listOf(receipt180.text))),
        ))

        val note181 = paddedNote(181)
        val oversized = "1 milk. $note181"
        assertTrue("181-character terminal note cannot be persisted",
            deriveFulfillmentConstraints(oversized, primaryQuotes(oversized, "1 milk"), allowTrailingFulfillment = true)
                .none { it.source.text == note181 })

        val overlapped = "1 milk. Please bring these on my list tomorrow."
        val fakePrimary = GroceryIntentReviewV2Quote(
            "1 milk. Please bring these on my list tomorrow.", 0, overlapped.length,
        )
        assertTrue("a terminal receipt overlapping primary evidence must be rejected",
            deriveFulfillmentConstraints(overlapped, listOf(fakePrimary), allowTrailingFulfillment = true).isEmpty())
    }

    @Test
    fun negationConditionsItemChangesAndUnknownAmountClausesAreNotFulfillmentProofs(): Unit {
        val headings = listOf(
            "Bring milk tomorrow:",
            "Bring these groceries tomorrow, not sweet milk:",
            "Bring these groceries tomorrow instead of rice:",
            "Bring these groceries tomorrow at 5 pm:",
            "Bring these groceries tomorrow under 500 rupees:",
            "Bring these groceries tomorrow for my diabetic father:",
            "Do not bring these groceries tomorrow:",
            "Please buy the following items if available otherwise take rice:",
        )
        headings.forEach { heading ->
            val instruction = "$heading 1 milk"
            assertFalse(heading, deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk")).isNotEmpty())
        }

        val inline = listOf(
            "Aaj mandi se 1 milk, 2 eggs",
            "Aaj mandi se 1 milk, 2 eggs le aaiyega unless expensive.",
            "Aaj mandi se 1 milk; 2 eggs le aaiyega.",
            "Aaj mandi se organic 1 milk, 2 eggs le aaiyega.",
            "Aaj mandi se 1 milk, 2 eggs mat le aaiyega.",
        )
        inline.forEach { instruction ->
            assertFalse(instruction, deriveFulfillmentConstraints(
                instruction, primaryQuotes(instruction, "1 milk", "2 eggs"),
            ).isNotEmpty())
        }
    }

    @Test
    fun fulfillmentSourceMustMatchAndMustNotOverlapAnyOwnedEvidence(): Unit {
        val instruction = "Please get these items tomorrow: milk"
        val primaries = primaryQuotes(instruction, "milk")
        val expected = deriveFulfillmentConstraints(instruction, primaries)
        assertEquals("Please get these items tomorrow:", expected.single().source.text)

        assertThrows(IllegalArgumentException::class.java) {
            validateFulfillmentConstraints(instruction, primaries, emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateFulfillmentConstraints(instruction, primaries, expected, listOf(expected.single().source))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateFulfillmentConstraints(instruction, primaries, emptyList(), listOf(primaries.single()))
        }
    }

    @Test
    fun internalControlOrNonAsciiWhitespaceCannotBecomeAPersistedReceipt() {
        listOf("Bring\tthese groceries tomorrow:", "Bring\u00a0these groceries tomorrow:",
            "Kal\tmandi se", "Kal\u00a0mandi se").forEach { heading ->
            val instruction = "$heading 1 milk, 2 eggs le aaiyega."
            assertEquals(emptyList<GroceryIntentReviewV2Constraint>(),
                deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 eggs")))
        }
    }

    @Test
    fun nonAsciiWhitespaceAfterAPeriodIsNotAnIndependentHeadingBoundary() {
        listOf("\u00a0", "\u2028").forEach { separator ->
            val instruction = "Hello.${separator}Get my list today: 1 milk, 2 eggs"
            assertEquals(emptyList<GroceryIntentReviewV2Constraint>(),
                deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 eggs")))
        }
    }

    @Test
    fun listPurposeIsOptInAndAcceptsOnlyClosedPositiveProductFreeScaffolds() {
        val positive = listOf(
            "Sun, bazaar se chai-nashta aur safai ka list:" to "Sun, bazaar se chai-nashta aur safai ka list:",
            "Ghar ke liye nahane aur roz ke khane ki samaan:" to "Ghar ke liye nahane aur roz ke khane ki samaan:",
            "Please prepare my shopping list for tea and cleaning:" to "Please prepare my shopping list for tea and cleaning:",
            "Please write down my grocery list for tea, snacks, and cleaning, kindly:" to
                "Please write down my grocery list for tea, snacks, and cleaning, kindly:",
            "Please prepare my list for tea ,snacks, and cleaning:" to
                "Please prepare my list for tea ,snacks, and cleaning:",
            "Please prepare my list for tea, snacks ,and cleaning:" to
                "Please prepare my list for tea, snacks ,and cleaning:",
            "Please prepare my list for tea,kindly:" to "Please prepare my list for tea,kindly:",
            "Kindly make a list of tea and breakfast and lunch and laundry items:" to
                "Kindly make a list of tea and breakfast and lunch and laundry items:",
            "Ghar ke liye safai, nahane aur roz ke khane, chai-nashta ka list:" to
                "Ghar ke liye safai, nahane aur roz ke khane, chai-nashta ka list:",
            "Ghar ke liye safai ,nahane, aur roz ke khane,chai-nashta ka list:" to
                "Ghar ke liye safai ,nahane, aur roz ke khane,chai-nashta ka list:",
        )
        positive.forEach { (instruction, expected) ->
            val fullInstruction = "$instruction 1 milk"
            val primaries = primaryQuotes(fullInstruction, "1 milk")
            assertEquals(emptyList<GroceryIntentReviewV2Constraint>(), deriveFulfillmentConstraints(fullInstruction, primaries))
            val derived = deriveFulfillmentConstraints(fullInstruction, primaries, allowListPurpose = true)
            assertEquals("list_purpose", derived.single().kind)
            assertEquals(expected, derived.single().source.text)
        }

        val invalidHeadings = listOf(
            "Sun, bazaar se chai-nashta aur diabetic logon ka list:",
            "2 chai-nashta ka list:",
            "Sun, bazaar se chai-nashta aur milk ka list:",
            "milk ka list:",
            "Sun, bazaar se chai-nashta ka list please:",
            "Sun, bazaar se chai-nashta ka list for 2 people:",
            "Sun, bazaar se chai-nashta ka list at 5 pm:",
            "Sun, bazaar se chai-nashta ka list under 500 rupees:",
            "Sunday bazaar se chai-nashta ka list:",
            "Please write my grocery list for tea, snacks, breakfast, and lunch, and dinner:",
            "Please write my grocery list for tea for diabetics:",
            "Please kindly make my list for tea:",
            "Please write my list for tea tomorrow:",
            "Please write my list for branded tea:",
            "Please write down my list for tea and snacks and cleaning and bathing and laundry:",
            "Ghar ke liye safai aur nahane aur roz ke khane aur chai-nashta aur nashta ka list:",
        )
        invalidHeadings.forEach { heading ->
            val instruction = "$heading 1 milk"
            assertEquals(heading, emptyList<GroceryIntentReviewV2Constraint>(),
                deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk"), allowListPurpose = true))
        }
    }

    @Test
    fun initialPurposeRequestsRequireBothPurposeCapabilitiesAndExactClosedSlots(): Unit {
        val request = "Beta, could you add the things for breakfast and the sink area?"
        val instruction = "$request 1 milk"
        val primaries = primaryQuotes(instruction, "1 milk")
        assertTrue(deriveFulfillmentConstraints(instruction, primaries).isEmpty())
        assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).isEmpty())
        assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowPurposeGroups = true).isEmpty())

        val receipt = deriveFulfillmentConstraints(
            instruction, primaries, allowListPurpose = true, allowPurposeGroups = true,
        ).single()
        assertEquals("list_purpose", receipt.kind)
        assertEquals(request, receipt.source.text)
        assertEquals(0, receipt.source.startUtf16)
        assertEquals(request.length, receipt.source.endUtf16)

        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, listOf(countedRow(0, instruction, "1 milk")),
                listOf(constraintJson(instruction, request, "list_purpose"))),
            instruction,
        ).draft
        val prepared = draft.prepareForReview()
        assertEquals(listOf("milk"), prepared.items.map { it.query })
        assertEquals(listOf(request), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty())

        val fourSlots = "Could you add groceries for breakfast, lunch, kitchen, and the sink area?"
        val fourInstruction = "$fourSlots 1 milk"
        val four = deriveFulfillmentConstraints(
            fourInstruction, primaryQuotes(fourInstruction, "1 milk"),
            allowListPurpose = true, allowPurposeGroups = true,
        ).single()
        assertEquals(fourSlots, four.source.text)

        val colonRequest = "Could you add the things for breakfast and the sink area:"
        val colonInstruction = "$colonRequest 1 milk"
        val colon = deriveFulfillmentConstraints(
            colonInstruction, primaryQuotes(colonInstruction, "1 milk"),
            allowListPurpose = true, allowPurposeGroups = true,
        ).single()
        assertEquals(colonRequest, colon.source.text)

        listOf(
            "Could you add the things for breakfast and diabetics?",
            "Could you add BrandA things for breakfast?",
            "Could you add organic groceries for breakfast?",
            "Could you add 2 things for breakfast?",
            "Could you add the things for breakfast under 500 rupees?",
            "Could you add the things for breakfast, no sugar, and the sink area?",
            "Could you add the things for breakfast if milk is available?",
            "Could you add the things for breakfast, then perhaps the sink area?",
        ).forEach { unsafeRequest ->
            val unsafeInstruction = "$unsafeRequest 1 milk"
            val unsafePrimary = primaryQuotes(unsafeInstruction, "1 milk")
            assertTrue("unknown or qualified purpose slots stay unowned: $unsafeRequest",
                deriveFulfillmentConstraints(unsafeInstruction, unsafePrimary,
                    allowListPurpose = true, allowPurposeGroups = true).isEmpty())
        }
    }

    @Test
    fun initialStoragePurposeIsASingleRetainedListPurposeReceiptAndNeverProductEvidence(): Unit {
        val request = "I need to stock the freezer."
        val instruction = "$request Could you get 1 milk, 2 eggs"
        val primaries = primaryQuotes(instruction, "1 milk", "2 eggs")
        assertTrue(deriveFulfillmentConstraints(instruction, primaries).isEmpty())
        assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).isEmpty())
        assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowPurposeGroups = true).isEmpty())
        val receipt = deriveFulfillmentConstraints(
            instruction, primaries, allowListPurpose = true, allowPurposeGroups = true,
        ).single()
        assertEquals("list_purpose", receipt.kind)
        assertEquals(request, receipt.source.text)
        assertEquals(0, receipt.source.startUtf16)
        assertEquals(request.length, receipt.source.endUtf16)

        val validDto = GroceryIntentReviewV2Response.parse(
            response(instruction,
                listOf(countedRow(0, instruction, "1 milk"), countedRow(1, instruction, "2 eggs")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction,
        ).draft
        val prepared = validDto.prepareForReview()
        assertEquals(listOf(request), prepared.sharedGroupNotes)
        assertEquals(listOf("1 milk", "2 eggs"), prepared.items.map { it.rawText })
        assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.unresolvedText.isEmpty())

        val omittedItem = GroceryIntentReviewV2Response.parse(
            response(instruction, listOf(countedRow(0, instruction, "1 milk")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction,
        ).draft.prepareForReview()
        assertTrue("unowned numeric suffix keeps its ambiguous predecessor held", omittedItem.items.isEmpty())
        assertTrue("a retained purpose receipt cannot conceal the omitted second source item",
            omittedItem.unresolvedText.any { it.contains("2 eggs") })
        val omittedGate = GroceryIntentReviewGate()
        omittedGate.prepare(omittedItem.unresolvedText, omittedItem.excludedText, omittedItem.sharedGroupNotes)
        assertFalse(omittedGate.canApply())

        val inventedFrozen = GroceryIntentReviewV2Response.parse(
            response(instruction,
                listOf(countedRow(0, instruction, "1 milk", name = "frozen milk"), countedRow(1, instruction, "2 eggs")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction,
        ).draft.prepareForReview()
        assertEquals("storage purpose must not lend frozen identity", listOf("eggs"), inventedFrozen.items.map { it.query })
        assertTrue(inventedFrozen.excludedText.any { it.contains("1 milk") })

        val generic = "I want to stock our pantry. Please bring 1 liquid"
        val genericRequest = "I want to stock our pantry."
        val genericDto = GroceryIntentReviewV2Response.parse(
            response(generic, listOf(countedRow(0, generic, "1 liquid", name = "liquid")),
                listOf(constraintJson(generic, genericRequest, "list_purpose"))), generic,
        ).draft.prepareForReview()
        assertTrue("storage purpose cannot turn a generic liquid into a product", genericDto.items.isEmpty())
        assertTrue(genericDto.excludedText.any { it.contains("1 liquid") })
    }

    @Test
    fun initialStoragePurposeRequiresExactClosedOpeningSentenceAndValidBoundPrimaries(): Unit {
        val validRequest = "We want to restock our pantry and a few kitchen items."
        val invalidRequests = listOf(
            "I need to stock the freezer if cheap.",
            "I need to stock the freezer, low sugar.",
            "I need to stock the BrandA freezer.",
            "I need to stock 2 freezers.",
            "I need to stock the freezer tomorrow.",
            "I need to stock the freezer only.",
            "I need to stock the freezer and laundry items.",
            "I need to stock the freezer and a few dairy products.",
            "I need to stock the freezer\n.",
            "I need to stock the freezer\t.",
            "I need to stock the freezer\u00a0.",
            "I need to stock the freezеr.", // Cyrillic е
            "I need to stock the freezer; then get these.",
        )
        invalidRequests.forEach { request ->
            val instruction = "$request Could you get 1 milk"
            val primary = primaryQuotes(instruction, "1 milk")
            assertTrue("invalid storage sentence must not grant a list-purpose receipt: $request",
                deriveFulfillmentConstraints(instruction, primary, allowListPurpose = true,
                    allowPurposeGroups = true).none { it.kind == "list_purpose" })
        }

        val withAdditionalStorage = "We need to restock the fridge and freezer. Could you bring 1 milk"
        val twoStorage = deriveFulfillmentConstraints(withAdditionalStorage,
            primaryQuotes(withAdditionalStorage, "1 milk"), allowListPurpose = true, allowPurposeGroups = true)
        assertTrue("closed same-class storage pair should be supported", twoStorage.any { it.kind == "list_purpose" })

        val noninitial = "1 bread. $validRequest Could you get 1 milk"
        assertTrue("storage purpose is initial-only", deriveFulfillmentConstraints(noninitial,
            primaryQuotes(noninitial, "1 bread", "1 milk"), allowListPurpose = true, allowPurposeGroups = true)
            .none { it.kind == "list_purpose" })

        val valid = "$validRequest Could you get 1 milk, 2 eggs"
        val validPrimaries = primaryQuotes(valid, "1 milk", "2 eggs")
        val invalidPrimarySets = listOf(
            emptyList(),
            listOf(validPrimaries.first(), validPrimaries.last().copy(startUtf16 = -1)),
            listOf(validPrimaries.first(), validPrimaries.last().copy(endUtf16 = valid.length + 1)),
            validPrimaries + validPrimaries.first().copy(startUtf16 = validPrimaries.first().startUtf16 + 1),
        )
        invalidPrimarySets.forEach { spans ->
            assertTrue("invalid, incomplete, or overlapping primary evidence cannot authorize scope: $spans",
                deriveFulfillmentConstraints(valid, spans, allowListPurpose = true, allowPurposeGroups = true)
                    .none { it.kind == "list_purpose" })
        }
    }

    @Test
    fun storagePurposeRejectsUnownedInterveningClausesAndNeverCreatesNeutralContext(): Unit {
        val examples = listOf(
            "I need to stock the freezer, and get 1 milk",
            "I need to stock the freezer. Then please get 1 milk",
            "I need to stock the freezer. Only if available, please get 1 milk",
            "1 rice. I need to stock the freezer. Could you get 1 milk",
        )
        examples.forEach { instruction ->
            val sources = listOf("1 milk").filter { instruction.contains(it) }
            val primaries = primaryQuotes(instruction, *sources.toTypedArray())
            assertTrue("unowned clause or noninitial storage cannot create purpose: $instruction",
                deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true,
                    allowPurposeGroups = true).none { it.kind == "list_purpose" })
        }

        val instruction = "I need to stock the freezer. Could you get 1 milk, no sugar"
        val request = "I need to stock the freezer."
        val receipt = deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk"),
            allowListPurpose = true, allowPurposeGroups = true).single { it.kind == "list_purpose" }
        assertEquals("only the complete opening sentence is retained", request, receipt.source.text)
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, listOf(countedRow(0, instruction, "1 milk")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction,
        ).draft.prepareForReview()
        assertEquals(listOf(request), prepared.sharedGroupNotes)
        assertTrue("later dietary instruction remains unowned", prepared.unresolvedText.any { it.contains("no sugar") })
        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes)
        assertFalse("unowned tail must continue blocking apply", gate.canApply())
    }

    @Test
    fun invertedPurposeRequestsRetainTheirWholeScopeAndNeverSupplyItemIdentity(): Unit {
        listOf("For the evening tea and the cupboard, please put down:",
            "For cleaning and the bathroom, could you write down these items?",
            "Please keep the kitchen and washroom lists together.").forEach { request ->
            val instruction = "$request 1 milk"
            val primaries = primaryQuotes(instruction, "1 milk")
            assertTrue(deriveFulfillmentConstraints(instruction, primaries).isEmpty())
            assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).isEmpty())
            assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowPurposeGroups = true).isEmpty())
            val note = deriveFulfillmentConstraints(instruction, primaries,
                allowListPurpose = true, allowPurposeGroups = true).single()
            assertEquals("list_purpose", note.kind)
            assertEquals(request, note.source.text)
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, listOf(countedRow(0, instruction, "1 milk")),
                    listOf(constraintJson(instruction, request, "list_purpose"))), instruction,
            ).draft.prepareForReview()
            assertEquals(listOf("milk"), prepared.items.map { it.query })
            assertEquals(listOf(request), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty())
        }
        listOf("For sugar-free breakfast, please put down:", "For milk, please put down:",
            "For breakfast tomorrow, please write down:", "For 2 breakfasts, please get:",
            "For breakfast, lunch, dinner, laundry, cleaning, please add:",
            "For breakfast, please put down if available:", "Please keep the low-fat kitchen lists together."
        ).forEach { request ->
            val instruction = "$request 1 milk"
            assertTrue(request, deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk"),
                allowListPurpose = true, allowPurposeGroups = true).isEmpty())
        }
        val request = "For breakfast, please put down:"
        listOf("1 sugar; ", "no sugar; ", "BrandA: ").forEach { gap ->
            val instruction = "$request $gap" + "1 milk"
            assertTrue(gap, deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk"),
                allowListPurpose = true, allowPurposeGroups = true).isEmpty())
        }
    }

    @Test
    fun listPurposeIdentityDenialIsLeadingScopeOnlyAndPreparationHoldsGenericForms() {
        val instruction = "Please prepare my grocery list for tea: 1 powder, 1 pack powder, 1 powder box, 1 tea, 1 milk, 1 bread"
        val sources = listOf("1 powder", "1 pack powder", "1 powder box", "1 tea", "1 milk", "1 bread")
        val primaries = primaryQuotes(instruction, *sources.toTypedArray())
        val constraint = deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).single()
        val constraints = listOf(constraint)
        listOf("patti", "pattis", "masala", "masalas", "powder", "powders", "liquid", "liquids",
            "brush", "brushes", "cloth", "cloths", "towel", "towels", "roll", "rolls", "soap", "soaps",
            "cleaner", "cleaners", "leaf", "leaves", "mix", "mixes", "pack powder", "powder box",
            "bottle of liquid", "a packet of brushes", "carton of leaves", "tube of mix",
            "small powder", "big powder", "large powder", "bada powder", "badi powder", "chhota powder",
            "chhoti powder", "packet ka powder", "powder wala", "powder wali", "powder wale").forEach { name ->
            assertEquals(name, "list_purpose_requires_item_identity", listPurposeIdentityIssue(
                primaries.first(), name, constraints,
            ))
        }
        listOf("tea", "milk", "bread", "tea powder", "dish soap", "laundry powder").forEach { source ->
            assertEquals(source, null, listPurposeIdentityIssue(primaries[1], source, constraints))
        }
        assertEquals("list_purpose_requires_item_identity",
            listPurposeIdentityIssue(primaries.last(), "powder", constraints))
        // Non-ASCII text/spacing cannot exempt an otherwise generic source.
        assertEquals("list_purpose_requires_item_identity",
            listPurposeIdentityIssue(primaries.first(), "粉 powder", constraints))
        assertEquals(null, listPurposeIdentityIssue(primaries.first(), "powder", emptyList()))

        val repeated = "Please prepare my grocery list for tea: Please prepare my grocery list for tea: 1 powder"
        val repeatedPrimary = primaryQuotes(repeated, "1 powder").single()
        val repeatedPurpose = deriveFulfillmentConstraints(repeated, listOf(repeatedPrimary), allowListPurpose = true)
        assertEquals("list_purpose_requires_item_identity", listPurposeIdentityIssue(
            repeatedPrimary, "powder", repeatedPurpose,
        ))

        val rows = sources.mapIndexed { index, source ->
            countedRow(index, instruction, source, name = if (index < 3) "laundry powder" else source.substringAfter(' '))
        }
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, rows, listOf(constraintJson(instruction, constraint.source.text, constraint.kind))),
            instruction,
        ).draft
        val prepared = draft.prepareForReview()
        assertEquals(listOf("tea", "milk", "bread"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.excludedText.any { it.contains("1 powder") })
        assertTrue(prepared.excludedText.any { it.contains("1 pack powder") })
        assertTrue(prepared.excludedText.any { it.contains("1 powder box") })
        assertEquals(listOf(constraint.source.text), prepared.sharedGroupNotes)
        assertTrue(prepared.fulfillmentConstraints.isEmpty())
        assertTrue(prepared.items.none { it.query.equals("powder", ignoreCase = true) })
    }

    @Test
    fun listPurposeDoesNotAllowGenericFormsThroughDescriptiveOrHindiFillers() {
        val genericForms = listOf(
            "1 powder" to "powder",
            "1 small powder" to "small powder",
            "1 bada powder" to "bada powder",
            "1 packet ka powder" to "packet ka powder",
            "1 powder wala" to "powder wala",
        )
        for ((source, literalName) in genericForms) {
            val instruction = "Please prepare my grocery list for cleaning: $source"
            val primary = primaryQuotes(instruction, source)
            val constraint = deriveFulfillmentConstraints(instruction, primary, allowListPurpose = true).single()
            assertEquals("list_purpose_requires_item_identity", listPurposeIdentityIssue(
                primary.single(), source.substringAfter(' '), listOf(constraint),
            ))

            val row = countedRow(0, instruction, source, name = literalName)
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, listOf(row), listOf(constraintJson(instruction, constraint.source.text, constraint.kind))),
                instruction,
            ).draft
            val prepared = draft.prepareForReview()
            assertTrue("$source must not be selected", prepared.items.isEmpty())
            assertTrue("$source must remain visible", prepared.excludedText.any { it.contains(source) })
            assertTrue("$source must not be silently normalized", prepared.items.none { it.query == "powder" })
        }
    }

    @Test
    fun newHindiFulfillmentScaffoldRejectsExtraMeaningControlsAndBareGreetings() {
        val invalid = listOf(
            "Aaj kal bazaar ki list bana do:",
            "Beta, please aaj bazaar ki list bana do:",
            "Namaste:",
            "Hello, please bring milk tomorrow:",
            "Aaj bazaar ki list bana do for diabetes:",
            "Aaj bazaar ki list bana dena:",
            "Aaj bazaar ki list bana do at 5 pm:",
            "Aaj\tbazaar ki list bana do:",
            "Aaj\u00a0bazaar ki list bana do:",
        )
        invalid.forEach { heading ->
            val instruction = "$heading 1 milk"
            assertEquals(heading, emptyList<GroceryIntentReviewV2Constraint>(),
                deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk")))
        }
    }

    @Test
    fun listPurposeMustUseExactSourceAndMustNotOverlapEvidenceOrAppearAfterItems() {
        val instruction = "Sun, bazaar se chai-nashta aur safai ka list: 1 milk, 2 eggs"
        val primaries = primaryQuotes(instruction, "1 milk", "2 eggs")
        val expected = deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).single()

        assertThrows(IllegalArgumentException::class.java) {
            validateFulfillmentConstraints(instruction, primaries,
                listOf(expected.copy(source = expected.source.copy(text = "Sun, bazaar se chai-nashta ka list"))), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateFulfillmentConstraints(instruction, primaries, listOf(expected),
                listOf(GroceryIntentReviewV2Quote("Sun,", expected.source.startUtf16, expected.source.startUtf16 + 4)))
        }

        val later = "1 milk. Sun, bazaar se chai-nashta aur safai ka list: 2 eggs"
        assertEquals(emptyList<GroceryIntentReviewV2Constraint>(), deriveFulfillmentConstraints(
            later, primaryQuotes(later, "1 milk", "2 eggs"), allowListPurpose = true,
        ))
    }

    @Test
    fun wireDeclaredListPurposeBecomesSharedNoteNotFulfillmentOrAnExtraItem() {
        val instruction = "Sun, bazaar se chai-nashta aur safai ka list: 1 milk, 2 eggs"
        val sources = listOf("1 milk", "2 eggs")
        val rows = sources.mapIndexed { index, source -> countedRow(index, instruction, source) }
        val constraint = constraintJson(instruction, "Sun, bazaar se chai-nashta aur safai ka list:", "list_purpose")
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, rows, listOf(constraint)), instruction,
        ).draft
        val originalRows = draft.readyRows.toList()

        val prepared = draft.prepareForReview()

        assertEquals(originalRows, draft.readyRows)
        assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertEquals(listOf("Sun, bazaar se chai-nashta aur safai ka list:"), prepared.sharedGroupNotes)
        assertTrue(prepared.fulfillmentConstraints.isEmpty())
        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes, prepared.fulfillmentConstraints)
        assertTrue(gate.fulfillmentConstraints.isEmpty())
        assertTrue(gate.canApply())

        val omitted = GroceryIntentReviewV2Response.parse(response(instruction, rows), instruction).draft.prepareForReview()
        assertTrue(omitted.sharedGroupNotes.isEmpty())
        assertTrue(omitted.fulfillmentConstraints.isEmpty())
    }

    @Test
    fun listPurposeUsesUtf16OffsetsAndCoexistsWithAnActualNumericCorrectionDto() {
        val instruction = "😀. Sun, bazaar se chai-nashta aur safai ka list: 1 kg toor dal; toor dal ko 2 kilo kar do"
        val primary = "1 kg toor dal"
        val correctionText = "toor dal ko 2 kilo kar do"
        val row = countedRow(0, instruction, primary, name = "toor dal", quantity = "1", quantityUnit = "kg")
            .dropLast(1) + ",\"corrections\":[${quantityCorrectionJson(instruction, correctionText, "2", "1", "kg", "2", "kg")}] }"
        val constraint = constraintJson(instruction, "Sun, bazaar se chai-nashta aur safai ka list:", "list_purpose")
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, listOf(row), listOf(constraint)), instruction,
        ).draft
        val note = deriveFulfillmentConstraints(instruction, listOf(draft.readyRows.single().evidence.primary), allowListPurpose = true).single().source
        assertEquals("Sun, bazaar se chai-nashta aur safai ka list:", note.text)
        assertEquals(instruction.indexOf(note.text), note.startUtf16)
        assertEquals(instruction.indexOf(note.text) + note.text.length, note.endUtf16)

        val prepared = draft.prepareForReview()
        assertEquals(listOf(note.text), prepared.sharedGroupNotes)
        assertTrue(prepared.fulfillmentConstraints.isEmpty())
        assertEquals(Quantity.Weight(2000), prepared.items.single().quantity)
        assertTrue(prepared.items.single().rawText.contains("You said: $correctionText"))
        assertEquals("1", draft.readyRows.single().item.quantity?.stripTrailingZeros()?.toPlainString())
        assertEquals("2", draft.readyRows.single().corrections.single().newValue.stripTrailingZeros().toPlainString())
    }

    @Test
    fun wireParserRejectsOmittedKindAsFulfillmentAndForgedListPurpose() {
        val instruction = "Sun, bazaar se chai-nashta aur safai ka list: 1 milk"
        val row = countedRow(0, instruction, "1 milk")
        val source = "Sun, bazaar se chai-nashta aur safai ka list:"
        val forgedKinds = listOf("fulfillment", "unexpected")
        forgedKinds.forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(instruction, listOf(row), listOf(constraintJson(instruction, source, kind))), instruction)
            }
        }

        val invalid = "Sun, bazaar se chai-nashta aur diabetic ka list: 1 milk"
        val invalidRow = countedRow(0, invalid, "1 milk")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(invalid, listOf(invalidRow), listOf(constraintJson(invalid, "Sun, bazaar se chai-nashta aur diabetic ka list:", "list_purpose"))),
                invalid,
            )
        }
    }

    @Test
    fun initialPurposeCapabilityComposesWithSourceProvenQuantityCorrections() {
        listOf("Get things for breakfast?", "For breakfast, please put down:").forEach { request ->
            val instruction = "$request 1 milk, 2 eggs; make milk two"
            val row = countedRow(0, instruction, "1 milk").dropLast(1) +
                ",\"corrections\":[${quantityCorrectionJson(instruction, "make milk two", "two", "1", "count", "2", "count")}] }"
            val draft = GroceryIntentReviewV2Response.parse(response(instruction,
                listOf(row, countedRow(1, instruction, "2 eggs")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction).draft
            val prepared = draft.prepareForReview()
            assertEquals(listOf(request), prepared.sharedGroupNotes)
            assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
            assertEquals(Quantity.Count(2), prepared.items.first().quantity)
            assertTrue(prepared.unresolvedText.isEmpty())
            assertEquals("1", draft.readyRows.first().item.quantity?.stripTrailingZeros()?.toPlainString())
            assertEquals("2", draft.readyRows.first().corrections.single().newValue.toPlainString())
        }
    }

    @Test
    fun purposeWithWeightPieceReplacementIsHeldAndItsBlockedDtoRemainsParseable() {
        val instruction = "Nashta ka samaan: 1 kg apples; apples 1 kilo nahi 4 pieces rakhna"
        val source = "1 kg apples"
        val constraint = constraintJson(instruction, "Nashta ka samaan:", "list_purpose")
        val row = countedRow(0, instruction, source, name = "apples", quantityUnit = "kg")
            .dropLast(1) + ",\"corrections\":[${quantityCorrectionJson(instruction,
                "apples 1 kilo nahi 4 pieces rakhna", "4", "1", "kg", "4", "piece")}] }"
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, listOf(row), listOf(constraint)), instruction)
        }
        val blocked = """{"row_index":0,"source":${quoteJson(instruction, source)},"issue_codes":["correction_unit_role_changed"]}"""
        val body = response(instruction, emptyList(), listOf(constraint))
            .replace("\"source_item_count\":0", "\"source_item_count\":1")
            .replace("\"blocked_rows\":[]", "\"blocked_rows\":[$blocked]")
        val prepared = GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty())
    }

    @Test
    fun canonicalGlobalPurposeNoteNeedsNoDuplicateGroupAndSuppliesNoItemFacts() {
        val request = "For cleaning and the bathroom, could you write down these items:"
        val instruction = "$request 1 milk, 2 eggs"
        val constraint = constraintJson(instruction, request, "list_purpose")
        val draft = GroceryIntentReviewV2Response.parse(response(instruction,
            listOf(countedRow(0, instruction, "1 milk"), countedRow(1, instruction, "2 eggs")),
            listOf(constraint)), instruction).draft
        val prepared = draft.prepareForReview()
        assertTrue(draft.groups.isEmpty())
        assertEquals(listOf(request), prepared.sharedGroupNotes)
        assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())

        // Removing redundant group metadata must never lend its purpose words
        // to a product. The Android client still grounds each row independently.
        val invented = GroceryIntentReviewV2Response.parse(response(instruction,
            listOf(countedRow(0, instruction, "1 milk", name = "bathroom milk"),
                countedRow(1, instruction, "2 eggs")), listOf(constraint)), instruction).draft.prepareForReview()
        assertEquals(listOf("eggs"), invented.items.map { it.query })
        assertTrue(invented.excludedText.isNotEmpty())
    }

    @Test
    fun canonicalPurposeNoteCannotHideUnclaimedCorrectionTail() {
        val request = "For cleaning and the bathroom, could you write down these items:"
        listOf("only if cheap", "make milk three", "without sugar").forEach { tail ->
            val instruction = "$request 1 milk, 2 eggs; $tail"
            val prepared = GroceryIntentReviewV2Response.parse(response(instruction,
                listOf(countedRow(0, instruction, "1 milk"), countedRow(1, instruction, "2 eggs")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction).draft.prepareForReview()
            assertTrue(tail, prepared.items.isEmpty())
            assertTrue(tail, prepared.unresolvedText.joinToString(" ").contains(tail))
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes)
            assertFalse(tail, gate.canApply())
        }
    }

    @Test
    fun nominalPurposeRequestsRetainOnlyTheWholeProductFreeOpeningNote() {
        val positives = listOf(
            "A few replacements for the kitchen and bathroom, please:",
            "Some groceries for breakfast and laundry, kindly:",
            "These supplies for the pantry and living room, please:",
            "My items for tea and the sink area:",
        )
        positives.forEach { request ->
            val instruction = "$request 1 milk, 2 eggs"
            val primaries = primaryQuotes(instruction, "1 milk", "2 eggs")
            assertTrue("both purpose capabilities are required: $request",
                deriveFulfillmentConstraints(instruction, primaries).isEmpty())
            assertTrue("list-purpose capability alone is insufficient: $request",
                deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).isEmpty())
            assertTrue("purpose-group capability alone is insufficient: $request",
                deriveFulfillmentConstraints(instruction, primaries, allowPurposeGroups = true).isEmpty())
            val receipt = deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true,
                allowPurposeGroups = true).single { it.kind == "list_purpose" }
            assertEquals(request, receipt.source.text)
            assertEquals(instruction.indexOf(request), receipt.source.startUtf16)
            assertEquals(instruction.indexOf(request) + request.length, receipt.source.endUtf16)

            val prepared = GroceryIntentReviewV2Response.parse(response(instruction,
                listOf(countedRow(0, instruction, "1 milk"), countedRow(1, instruction, "2 eggs")),
                listOf(constraintJson(instruction, request, "list_purpose"))), instruction)
                .draft.prepareForReview()
            assertEquals(listOf("milk", "eggs"), prepared.items.map { it.query })
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertEquals(listOf(request), prepared.sharedGroupNotes)
            assertTrue("a list note is not a schedule/fulfillment promise", prepared.fulfillmentConstraints.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
        }
    }

    @Test
    fun nominalPurposeRequestsRejectFactsActionsAndMalformedPrimaryProof() {
        val invalidRequests = listOf(
            "2 groceries for the kitchen, please",
            "BrandA groceries for the kitchen",
            "Organic replacements for the bathroom",
            "A few replacements for tea",
            "Some replacements for breakfast",
            "These replacements for cleaning",
            "Our replacements for the kitchen and tea",
            "Sugar-free items for breakfast",
            "Pet food supplies for the pantry",
            "Replace milk with oat milk for the kitchen",
            "Items for the kitchen instead of the pantry",
            "A few groceries for breakfast if available",
            "Some items for the kitchen by tomorrow",
            "Some supplies for the kitchen with free delivery",
            "Some replacements for the kitchen and unknown things",
            "Some items for the kitchen\nplease",
            "Some items for the kitchen\tplease",
            "Some items for the kitchen\u00a0please",
            "Some items for the kіtchen", // Cyrillic і
        )
        invalidRequests.forEach { request ->
            val instruction = "$request: 1 milk, 2 eggs"
            assertTrue("unrecognized or fact-bearing nominal request: $request",
                deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 eggs"),
                    allowListPurpose = true, allowPurposeGroups = true).none { it.kind == "list_purpose" })
        }

        val request = "A few replacements for the kitchen and bathroom, please:"
        val instruction = "$request 1 milk, 2 eggs"
        val valid = primaryQuotes(instruction, "1 milk", "2 eggs")
        val malformed = listOf(
            emptyList(),
            listOf(valid.first(), valid.last().copy(startUtf16 = -1)),
            listOf(valid.first(), valid.last().copy(endUtf16 = instruction.length + 1)),
            valid + valid.first().copy(startUtf16 = valid.first().startUtf16 + 1),
            listOf(valid.first(), valid.last().copy(startUtf16 = valid.first().startUtf16 + 1,
                endUtf16 = valid.first().endUtf16 + 1)),
        )
        malformed.forEach { spans ->
            assertTrue("invalid/missing/overlapping primary proof cannot authorize: $spans",
                deriveFulfillmentConstraints(instruction, spans, allowListPurpose = true,
                    allowPurposeGroups = true).none { it.kind == "list_purpose" })
        }

        val omittedInstruction = "$request 1 milk, 2 eggs"
        val omitted = GroceryIntentReviewV2Response.parse(response(omittedInstruction,
            listOf(countedRow(0, omittedInstruction, "1 milk")),
            listOf(constraintJson(omittedInstruction, request, "list_purpose"))), omittedInstruction)
            .draft.prepareForReview()
        assertTrue("purpose receipt cannot prove ownership of the omitted count", omitted.items.isEmpty())
        assertTrue(omitted.unresolvedText.joinToString(" ").contains("2 eggs"))
        val gate = GroceryIntentReviewGate()
        gate.prepare(omitted.unresolvedText, omitted.excludedText, omitted.sharedGroupNotes,
            omitted.fulfillmentConstraints)
        assertFalse("the unclaimed item must prevent automatic application", gate.canApply())
    }

    @Test
    fun interitemHouseholdPurposeRetainsOnlyExactClosedNoteAndPreservesRows() {
        val cases = listOf(
            "for the house;" to "then",
            "for my home." to "and then",
            "ghar ke liye!" to "phir",
            "for our household?" to "aur phir",
            "for the house;" to "fir",
        )
        for ((note, transition) in cases) {
            val first = "1 milk"
            val second = "2 eggs"
            val instruction = "$first $note $transition $second"
            val primaries = primaryQuotes(instruction, first, second)
            assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true).isEmpty())
            assertTrue(deriveFulfillmentConstraints(instruction, primaries, allowPurposeGroups = true).isEmpty())
            val derived = deriveFulfillmentConstraints(instruction, primaries,
                allowListPurpose = true, allowPurposeGroups = true)
            assertEquals(listOf("list_purpose" to note), derived.map { it.kind to it.source.text })

            val prepared = GroceryIntentReviewV2Response.parse(response(instruction,
                listOf(countedRow(0, instruction, first), countedRow(1, instruction, second)),
                listOf(constraintJson(instruction, note, "list_purpose"))), instruction).draft.prepareForReview()
            assertEquals(listOf(first, second), prepared.items.map { it.rawText })
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertEquals(listOf(note), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty())
        }
    }

    @Test
    fun interitemHouseholdPurposeRejectsUnclosedOrFactBearingGapsAndBadPrimaryProof() {
        val invalidGaps = listOf(
            " for the house then ", " for the house; maybe ", " for the house; 3 ",
            " for the house; without sugar; then ", " for the house; tomorrow; then ",
            " for my address; then ", " for the house; last time; then ",
            " for the house; unknown; then ", " for the house\n; then ",
            " for the hоuse; then ", // Cyrillic о
        )
        for (gap in invalidGaps) {
            val instruction = "1 milk" + gap + "2 eggs"
            assertTrue(gap, deriveFulfillmentConstraints(instruction, primaryQuotes(instruction, "1 milk", "2 eggs"),
                allowListPurpose = true, allowPurposeGroups = true).isEmpty())
        }

        val text = "1 milk for the house; then 2 eggs"
        val valid = primaryQuotes(text, "1 milk", "2 eggs")
        assertEquals(1, deriveFulfillmentConstraints(text, valid, allowListPurpose = true,
            allowPurposeGroups = true).size)
        val malformed = listOf(
            emptyList(),
            listOf(valid.first()),
            listOf(valid.first(), valid.last().copy(startUtf16 = valid.last().startUtf16 + 1)),
            listOf(valid.first(), valid.first()),
            listOf(valid.first(), valid.last().copy(endUtf16 = text.length + 1)),
        )
        for (spans in malformed) {
            assertTrue("invalid/missing/overlapping primary proof cannot authorize",
                deriveFulfillmentConstraints(text, spans, allowListPurpose = true,
                    allowPurposeGroups = true).isEmpty())
        }
    }

    @Test
    fun interitemHouseholdNoteDoesNotLaunderGenericItemAndComposesWithOtherNotes() {
        val generic = "1 powder"
        val second = "2 milk"
        val instruction = "$generic for the house; then $second"
        val rowPayload = response(instruction,
            listOf(countedRow(0, instruction, generic), countedRow(1, instruction, second)),
            listOf(constraintJson(instruction, "for the house;", "list_purpose")))
        val held = GroceryIntentReviewV2Response.parse(rowPayload, instruction).draft.prepareForReview()
        assertTrue(held.items.none { it.rawText == generic })
        assertTrue(held.excludedText.any { it.contains(generic) })

        val heading = "A few groceries for the kitchen, please:"
        val sources = listOf("1 milk", "2 eggs", "3 rice")
        val trailing = "Please bring everything on this list tomorrow."
        val full = "$heading ${sources[0]} for the house; then ${sources[1]} ghar ke liye; phir ${sources[2]}. $trailing"
        val noteTexts = listOf(heading, "for the house;", "ghar ke liye;", trailing)
        val constraints = noteTexts.mapIndexed { index, note ->
            constraintJson(full, note, if (index == noteTexts.lastIndex) "fulfillment" else "list_purpose")
        }
        val composed = GroceryIntentReviewV2Response.parse(response(full,
            sources.mapIndexed { index, source -> countedRow(index, full, source) }, constraints), full)
            .draft.prepareForReview()
        assertEquals(sources, composed.items.map { it.rawText })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2), Quantity.Count(3)),
            composed.items.map { it.quantity })
        assertEquals(noteTexts.take(3), composed.sharedGroupNotes)
        assertEquals(trailing, composed.fulfillmentConstraints.single())
    }

    @Test
    fun conditionalHouseholdGenericHeadsStayExcludedUnlessIdentityIsConcrete() {
        val genericHeads = listOf("powder", "cleaner", "brush", "liquid")
        for (generic in genericHeads) {
            val first = "1 milk"
            val conditional = "2 $generic if available"
            val instruction = "$first for the house; then $conditional"
            val wire = response(instruction,
                listOf(
                    countedRow(0, instruction, first, name = "milk"),
                    countedRow(1, instruction, conditional, name = generic),
                ), listOf(constraintJson(instruction, "for the house;", "list_purpose")))
            val prepared = GroceryIntentReviewV2Response.parse(wire, instruction).draft.prepareForReview()
            assertTrue(generic, prepared.items.none { it.rawText == conditional })
            assertTrue(generic, prepared.excludedText.any { it.contains(conditional) })
        }

        val first = "1 milk"
        val concrete = "2 detergent powder if available"
        val instruction = "$first for the house; then $concrete"
        val wire = response(instruction,
            listOf(countedRow(0, instruction, first, name = "milk"),
                countedRow(1, instruction, concrete, name = "detergent powder")),
            listOf(constraintJson(instruction, "for the house;", "list_purpose")))
        val prepared = GroceryIntentReviewV2Response.parse(wire, instruction).draft.prepareForReview()
        assertEquals(listOf("milk", "detergent powder"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertEquals(concrete, prepared.items.last().rawText)
    }

    @Test
    fun interitemHouseholdAndUnscentedAvailabilityKeepExactProductPropertyAndQuantity() {
        val first = "1 milk"
        val conditional = "2 hand cream if the unscented kind is available"
        for (transition in listOf("aur phir", "aur fir")) {
            val instruction = "$first for the house; $transition $conditional"
            val row = countedRow(1, instruction, conditional, name = "unscented hand cream")
                .replace("\"required\":[]", "\"required\":[\"unscented\"]")
            val wire = response(instruction,
                listOf(countedRow(0, instruction, first, name = "milk"), row),
                listOf(constraintJson(instruction, "for the house;", "list_purpose")))
            val prepared = GroceryIntentReviewV2Response.parse(wire, instruction).draft.prepareForReview()
            assertEquals("milk", prepared.items.first().query)
            assertEquals(setOf("unscented", "hand", "cream"), prepared.items.last().query.split(' ').toSet())
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertEquals(listOf(first, conditional), prepared.items.map { it.rawText })
            assertEquals(listOf("for the house;"), prepared.sharedGroupNotes)
        }

        val unknownTransition = "$first for the house; maybe $conditional"
        val unknownRow = countedRow(1, unknownTransition, conditional, name = "unscented hand cream")
            .replace("\"required\":[]", "\"required\":[\"unscented\"]")
        val held = GroceryIntentReviewV2Response.parse(response(unknownTransition,
            listOf(countedRow(0, unknownTransition, first, name = "milk"), unknownRow)), unknownTransition)
            .draft.prepareForReview()
        assertTrue(held.items.none { it.rawText == conditional })
    }

    @Test
    fun interitemHouseholdContextNoteLimitIsBounded() {
        val sources = (1..22).map { "$it item$it" }
        val instruction = sources.joinToString(" for the house; then ")
        var cursor = 0
        val primaries = sources.map { source ->
            val start = instruction.indexOf(source, cursor)
            cursor = start + source.length
            GroceryIntentReviewV2Quote(source, start, cursor)
        }
        assertTrue(runCatching {
            deriveFulfillmentConstraints(instruction, primaries, allowListPurpose = true,
                allowPurposeGroups = true)
        }.isFailure)
    }

    private fun response(instruction: String, rows: List<String>, constraints: List<String> = emptyList()): String {
        val constraintsField = if (constraints.isEmpty()) "" else ",\"constraints\":[${constraints.joinToString(",")}]"
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":${rows.size},"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[]$constraintsField},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
    }

    private fun countedRow(
        index: Int,
        instruction: String,
        source: String,
        name: String = source.substringAfterLast(' ').trim(),
        quantity: String = source.substringBefore(' ').toIntOrNull()?.toString() ?: "1",
        quantityUnit: String = "count",
    ): String {
        val start = instruction.indexOf(source)
        val end = start + source.length
        val amountText = source.substringBefore(' ').takeIf { it.toIntOrNull() != null }
            ?: quantity
        return """{"row_index":$index,"item":{"name":${jsonString(name)},"quantity":$quantity,"quantity_unit":${jsonString(quantityUnit)},"pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${jsonString(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${jsonString(amountText)},"pack_text":null},"evidence":{"primary":{"text":${jsonString(source)},"start_utf16":$start,"end_utf16":$end},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
    }

    private fun quantityCorrectionJson(
        instruction: String, source: String, newAmount: String, oldValue: String, oldUnit: String,
        newValue: String, newUnit: String,
    ): String {
        val start = instruction.indexOf(source)
        val amountStart = start + source.indexOf(newAmount)
        return """{"kind":"quantity","source":{"text":${jsonString(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"new_amount":{"text":${jsonString(newAmount)},"start_utf16":$amountStart,"end_utf16":${amountStart + newAmount.length}},"old_value":$oldValue,"old_unit":${jsonString(oldUnit)},"new_value":$newValue,"new_unit":${jsonString(newUnit)}}"""
    }

    private fun constraintJson(instruction: String, text: String, kind: String): String {
        val start = instruction.indexOf(text)
        return """{"kind":${jsonString(kind)},"source":{"text":${jsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}}"""
    }

    private fun quoteJson(instruction: String, text: String): String {
        val start = instruction.indexOf(text)
        require(start >= 0)
        return """{"text":${jsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""
    }

    private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun primaryQuotes(instruction: String, vararg texts: String): List<GroceryIntentReviewV2Quote> =
        texts.map { text ->
            val start = instruction.indexOf(text)
            require(start >= 0)
            GroceryIntentReviewV2Quote(text, start, start + text.length)
        }
}
