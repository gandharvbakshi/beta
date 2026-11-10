package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentEditorialContextTest {
    @Test fun initialShoppingFramesBelongOnlyToTheWholePrefixBesideTheFirstItem() {
        fun consumed(source: String, prefix: String, primaries: List<GroceryIntentCoverageSpan>): Boolean {
            val spans = neutralGroceryIntentContextSpans(source, primaries)
            val start = source.indexOf(prefix)
            return prefix.indices.filter { prefix[it] !in " \t" }.all { offset ->
                spans.any { start + offset in it.startUtf16 until it.endUtf16 }
            }
        }
        for (prefix in listOf("Could I have ", "May we please buy ", "Can I\tget ",
            "Weekly list please: ", "Monthly grocery list: ", "Shopping list please: ",
            "For dinner, ", "For afternoon coffee: ", "For morning tea, ")) {
            val source = prefix + ITEMS
            val primaries = primarySpans(source)
            assertTrue(prefix, consumed(source, prefix, primaries.reversed()))
            val prepared = response(source).prepareForReview()
            assertEquals(prefix, listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(prefix, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
        for (prefix in listOf("Could I have only organic ", "Could I have under 2 ", "Could I have if available ",
            "Could I\nhave ", "Weekly list please ", "By dinner, ", "For diabetics, ", "For dinner before seven, ",
            "For dinner only if: ", "Monday forecast: ", "For evening tea ", "Could I have ".padStart(181))) {
            assertFalse(prefix, consumed(prefix + ITEMS, prefix, primarySpans(prefix + ITEMS)))
        }
        val frame = "Could I have "
        val source = frame + ITEMS
        val valid = primarySpans(source)
        for (missing in listOf(emptyList(), listOf(valid.last()), listOf(valid.first(), valid.first())))
            assertFalse(consumed(source, frame, missing))
        val interior = "one milk, Could I have two rice"
        assertFalse(consumed(interior, frame, primarySpans(interior)))
    }

    @Test fun compactOwnedListPrefixesAreConsumedOnlyAsCompleteSafeEnvelopes() {
        val safePrefixes = listOf(
            "Could you put down ", "Please add to the list ", "Please pick ",
            "Dukaan se le aao: ", "Thoda dhyan se likh lo: ", "Bhai, list mein ",
            "Shop list: ",
        )
        for (prefix in safePrefixes) {
            val instruction = prefix + ITEMS
            val primaries = primarySpans(instruction)
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("$prefix -> $spans", spans.any {
                it.startUtf16 == 0 && instruction.substring(0, it.endUtf16).trimEnd() == prefix.trimEnd()
            })
            assertTrue(primaries.all { item -> spans.none {
                it.startUtf16 < item.endUtf16 && item.startUtf16 < it.endUtf16
            } })
            val prepared = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun compactPrefixEnvelopeRejectsObligationsAndMalformedPrimaryEvidence() {
        val unsafePrefixes = listOf(
            "Could you put down only organic ", "Could you put down no sugar ",
            "Could you put down if available ", "Could you put down tomorrow ",
            "Could you put down 2 ", "Could you put down BrandA: ",
            "Could you put down same as last time ", "Aaj ke liye ",
            "Let's keep it simple ", "Could you put down café ",
        )
        for (prefix in unsafePrefixes) {
            val instruction = prefix + ITEMS
            val primaries = primarySpans(instruction)
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("Unsafe complete prefix was consumed: $prefix -> $spans", spans.none {
                it.startUtf16 == 0 && it.endUtf16 >= primaries.first().startUtf16
            })
            val prepared = response(instruction).prepareForReview()
            assertTrue("Unsafe obligation disappeared: $prefix", prepared.items.size < 2 ||
                prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        }
        for (prefix in listOf("Could you put down\n", "Could you put down\u000b")) {
            val instruction = prefix + ITEMS
            val primaries = primarySpans(instruction)
            assertTrue(prefix, neutralGroceryIntentContextSpans(instruction, primaries).none {
                it.startUtf16 == 0 && it.endUtf16 >= primaries.first().startUtf16
            })
        }

        val instruction = "Please add to the list: $ITEMS"
        val valid = primarySpans(instruction)
        val malformed = listOf(
            emptyList(),
            listOf(GroceryIntentCoverageSpan(-1, valid.first().endUtf16), valid.last()),
            listOf(valid.first(), GroceryIntentCoverageSpan(valid.first().startUtf16, instruction.length + 1)),
            listOf(valid.first(), valid.first(), valid.last()),
            listOf(GroceryIntentCoverageSpan(0, valid.first().startUtf16), valid.last()),
        )
        for (primaries in malformed) {
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("Malformed primaries accepted: $primaries -> $spans", spans.none {
                it.startUtf16 == 0 && it.endUtf16 >= valid.first().startUtf16
            })
        }
        assertTrue(neutralGroceryIntentContextSpans(instruction, valid.reversed()).any {
            it.startUtf16 == 0 && it.endUtf16 == valid.first().startUtf16
        })

        val cropped = "Please add to the list: one milk, two rice"
        val croppedFirst = spanOf(cropped, "milk")
        val croppedPrimaries = listOf(croppedFirst, spanOf(cropped, "two rice"))
        assertTrue(neutralGroceryIntentContextSpans(cropped, croppedPrimaries).none {
            it.startUtf16 == 0 && it.endUtf16 >= croppedFirst.startUtf16
        })
    }

    @Test fun strictResponsePreparationKeepsCountsAndDoesNotAcknowledgeAwayDietOrConditions() {
        listOf("Could you put these in my list, please", "Please make the list slowly",
            "Add these to my shopping note, in whichever order is easier",
            "Before I forget, please note this mixed list").forEach { heading ->
            val instruction = "$heading: $ITEMS"
            val prepared = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            listOf("; no sugar", "; only organic", "; if available", "; make rice three").forEach { tail ->
                assertTrue(tail, response(instruction + tail).prepareForReview().items.isEmpty())
            }
        }
    }

    @Test fun closedEditorialSlotsComposeAcrossCourtesyActionsDocumentsAndTails() {
        val courtesies = listOf("", "Please ", "Could you ", "Before I forget, kindly ")
        val actions = listOf("make ", "write down ", "put these in ", "add the following items to ")
        val documents = listOf("my list", "the grocery note", "this shopping list")
        val tails = listOf("", " slowly", ", please", ", in whichever order is easier")
        var combinations = 0
        for (courtesy in courtesies) for (action in actions) for (document in documents) for (tail in tails) {
            val prefix = "$courtesy$action$document$tail: "
            val instruction = "${prefix}one milk, two rice"
            val primary = primarySpans(instruction)
            val expected = GroceryIntentCoverageSpan(0, prefix.length - 1)
            val spans = neutralGroceryIntentContextSpans(instruction, primary)
            assertTrue("$prefix -> $spans", spans.contains(expected))
            assertTrue(primary.all { item -> spans.none { it.startUtf16 < item.endUtf16 && item.startUtf16 < it.endUtf16 } })
            assertEquals("one milk", instruction.substring(primary[0].startUtf16, primary[0].endUtf16))
            assertEquals("two rice", instruction.substring(primary[1].startUtf16, primary[1].endUtf16))
            combinations++
        }
        assertEquals(192, combinations)
    }

    @Test fun editorialHeadingDoesNotInterfereWithOwnedItemsOrVerifiedCorrection() {
        val instruction = "Could you put these in my list, please: one milk, two rice; make milk two"
        val primary = primarySpans(instruction)
        val intro = GroceryIntentCoverageSpan(0, instruction.indexOf(":") + 1)
        assertTrue(neutralGroceryIntentContextSpans(instruction, primary).contains(intro))
        val correction = spanOf(instruction, "make milk two")
        assertTrue(primary.none { correction.startUtf16 < it.endUtf16 && it.startUtf16 < correction.endUtf16 })
        assertTrue(correctionGroceryIntentDiscourseSpans(instruction, listOf(correction), primary).isEmpty())
        assertEquals(listOf("one milk", "two rice"), primary.map { instruction.substring(it.startUtf16, it.endUtf16) })
    }

    @Test fun dictationHeadingsComposeAsExactEditorialSpansWithoutChangingRowsOrCorrections() {
        val headings = listOf(
            "I am dictating this slowly so I don't miss anything",
            "I'm going to say the household list in groups",
            "We are reading out my grocery list clearly one item at a time",
            "We're going to read out our shopping list",
        )
        for (heading in headings) {
            val instruction = "$heading: $ITEMS"
            val primaries = primarySpans(instruction)
            val intro = spanOf(instruction, "$heading:")
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("$heading -> $spans", spans.contains(intro))
            assertEquals("$heading:", instruction.substring(intro.startUtf16, intro.endUtf16))
            assertTrue(primaries.all { item -> spans.none { it.startUtf16 < item.endUtf16 && item.startUtf16 < it.endUtf16 } })
            val prepared = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val heading = "I am dictating this slowly so I don't miss anything"
        val correction = "milk 1 nahi 2 kar do"
        val correctedInstruction = "$heading: 1 milk, 2 rice; $correction"
        val corrected = correctedCountResponse(correctedInstruction, correction).prepareForReview()
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), corrected.items.map { it.quantity })
        assertTrue(corrected.items.first().rawText.contains("You said: $correction"))
        assertTrue(corrected.unresolvedText.isEmpty() && corrected.excludedText.isEmpty())
        val correctedIntro = spanOf(correctedInstruction, "$heading:")
        assertTrue(neutralGroceryIntentContextSpans(correctedInstruction, primarySpansForCountCorrection(correctedInstruction))
            .contains(correctedIntro))
    }

    @Test fun dictationHeadingRejectsMeaningfulSuffixesCroppingAndUnprovedGroupHeaders() {
        val safeCore = "I am dictating this slowly so I don't miss anything"
        for (between in listOf("organic ", "only ", "BrandA: ", "if available ", "2 ")) {
            val instruction = "$safeCore: $between$ITEMS"
            assertFalse(between, neutralGroceryIntentContextSpans(instruction, primarySpans(instruction)).any {
                it.startUtf16 == 0
            })
        }
        val rejected = listOf(
            "$safeCore by tomorrow", "$safeCore for my diabetic father", "$safeCore without sugar",
            "$safeCore twice", "$safeCore as I bought last week", "$safeCore if you have time",
            "I am not dictating this list", "I am dictating this list plus two extra",
            "I am dictating this slowly\nso I don't miss anything", "I am dictating this slowly\u000b",
            "I am dictating th\u0131s list",
        )
        for (heading in rejected) {
            val instruction = "$heading: $ITEMS"
            assertFalse(heading, neutralGroceryIntentContextSpans(instruction, primarySpans(instruction)).any {
                it.startUtf16 == 0 && it.endUtf16 >= heading.length
            })
        }

        val cropped = "one milk, $safeCore: two rice"
        val croppedPrimaries = primarySpans(cropped)
        assertFalse(neutralGroceryIntentContextSpans(cropped, croppedPrimaries).any {
            it.startUtf16 == cropped.indexOf(safeCore)
        })

        val quoted = "$safeCore: $ITEMS"
        val quotedPrimaries = primarySpans(quoted) + GroceryIntentCoverageSpan(0, safeCore.length)
        assertFalse(neutralGroceryIntentContextSpans(quoted, quotedPrimaries).any { it.startUtf16 == 0 })

        val laterGroup = "I'm going to say the household list in groups: one milk, two rice; breakfast section: three eggs"
        val laterReview = response(laterGroup).prepareForReview()
        assertTrue(laterReview.unresolvedText.isNotEmpty())
        assertTrue(laterReview.unresolvedText.any { it.contains("breakfast section") || it.contains("three eggs") })
    }

    @Test fun closedHindiDictationHeadingsAreEditorialOnlyAndRetainExactSource() {
        val headings = listOf(
            "Bhaiya, thoda dhyan se sunna, ye sab chahiye",
            "Meri beti ne bola list saaf likh dena, suno",
            "Suno ji, chhoti si lambi list hai",
            "Dhyan se suniye, yeh sab chahiye",
            "Hamari beta ne bola list likh lena, suniye",
            "Chhoti si list hai",
        )
        for (heading in headings) {
            val instruction = "$heading: $ITEMS"
            val primaries = primarySpans(instruction)
            val intro = spanOf(instruction, "$heading:")
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("$heading -> $spans", spans.contains(intro))
            assertEquals("$heading:", instruction.substring(intro.startUtf16, intro.endUtf16))
            val prepared = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val heading = "Bhaiya, thoda dhyan se sunna, ye sab chahiye"
        val correction = "milk 1 nahi 2 kar do"
        val instruction = "$heading: 1 milk, 2 rice; $correction"
        val prepared = correctedCountResponse(instruction, correction).prepareForReview()
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.first().rawText.contains("You said: $correction"))
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        assertTrue(neutralGroceryIntentContextSpans(instruction, primarySpansForCountCorrection(instruction))
            .contains(spanOf(instruction, "$heading:")))

        val afterProduct = "one milk, Bhaiya, thoda dhyan se sunna, ye sab chahiye: two rice"
        assertFalse(neutralGroceryIntentContextSpans(afterProduct, primarySpans(afterProduct)).any {
            it.startUtf16 == afterProduct.indexOf("Bhaiya")
        })
    }

    @Test fun hindiDictationHeadingRejectsMeaningfulNeighborsAndUnknownSuffixes() {
        val rejected = listOf(
            "Kal dhyan se sunna, ye sab chahiye", "Aaj dhyan se sunna, ye sab chahiye",
            "Bhaiya, sirf dhyan se sunna, ye sab chahiye", "Bhaiya, dhyan se sunna, ye sab nahi chahiye",
            "Bhaiya, dhyan se sunna, ye sab milk chahiye", "Bhaiya, dhyan se sunna, ye sab chahiye 2 kilo",
            "Bhaiya, dhyan se sunna, ye sab chahiye no sugar", "Bhaiya, dhyan se sunna, ye sab chahiye if available",
            "Meri beti ne bola list saaf likh dena, suno, kal tak", "List saaf likh dena, suno pichli baar ki tarah",
            "List hai for my family", "Chhoti si list hai; only organic", "Dhyan se sunna, ye sab chahiye BrandA",
            "Bhaiya, dhyan se sunna\nye sab chahiye", "Bhaiya, dhyan se sunna\u000b, ye sab chahiye",
            "Bhaiya, dhyaan se sunna, ye sab chahiye",
        )
        for (heading in rejected) {
            val instruction = "$heading: $ITEMS"
            assertFalse(heading, neutralGroceryIntentContextSpans(instruction, primarySpans(instruction)).any {
                it.startUtf16 == 0 && it.endUtf16 >= heading.length
            })
        }
    }

    @Test fun editorialHeadingRejectsMeaningfulSlotsFragmentsAndNonAsciiOrControls() {
        val headings = listOf(
            "make the sugar-free list", "make two lists", "make the list of milk",
            "make my usual list", "make the list by tomorrow", "make the list if available",
            "make the list without sugar", "make the list and no bread", "make milk two",
            "add organic items to my list", "put these two in my list", "put these in my BrandA list",
            "put these in my list or bread", "put these in my list, except milk",
            "do not make the list", "make the list instead", "only make the list",
            "prepare a list for diabetics", "prepare my usual kitchen things",
            "write down the following milk", "milk, make the list", "BrandA: make the list",
            "make the list, please, sugar-free", "make the list slowly tomorrow",
            "make the list\nslowly", "make the list\u000bslowly", "make the l\u0131st",
        )
        headings.forEach { heading ->
            val instruction = "$heading: one milk, two rice"
            assertFalse(heading, neutralGroceryIntentContextSpans(instruction, primarySpans(instruction)).any {
                it.startUtf16 == 0 && it.endUtf16 >= heading.length
            })
        }
    }

    @Test fun editorialHeadingRequiresAdjacentUnoverlappedOriginalItems() {
        val heading = "Please make the list: "
        for (between in listOf("organic ", "only ", "BrandA: ", "if available ", "2 ")) {
            val instruction = "$heading$between${ITEMS}"
            assertFalse(between, neutralGroceryIntentContextSpans(instruction, primarySpans(instruction)).any { it.startUtf16 == 0 })
        }

        val instruction = "$heading$ITEMS"
        val primaries = primarySpans(instruction)
        val invalid = listOf(
            listOf(GroceryIntentCoverageSpan(0, 4)) + primaries,
            listOf(GroceryIntentCoverageSpan(-1, 2)) + primaries,
            List(26) { primaries }.flatten(),
            listOf(GroceryIntentCoverageSpan(0, instruction.length)),
        )
        invalid.forEach { spans ->
            assertFalse(neutralGroceryIntentContextSpans(instruction, spans).any { it.startUtf16 == 0 })
        }
    }

    @Test fun completeOriginalResumptionsBetweenItemsPreserveCountsAndHardBoundaries() {
        val gaps = listOf(
            "; Could you also pick up ", ". And round it off with ",
            ". Thanks very much And round it off with ", ", please include ",
            ". Thank you. To complete the list with ", " Could you also get ",
        )
        for (gap in gaps) {
            val instruction = "one milk$gap" + "two rice"
            val primaries = primarySpans(instruction)
            val nextStart = instruction.indexOf("two rice")
            assertTrue(gap, listTransitionSpans(instruction, primaries).any {
                it.startUtf16 >= primaries[0].endUtf16 && it.endUtf16 == nextStart
            })
            val review = response(instruction).prepareForReview()
            if ("round it off with" in gap || "please include" in gap) {
                assertTrue("ambiguous editorial relation cannot create separate items: $gap", review.items.isEmpty())
                assertEquals(listOf(instruction), review.unresolvedText)
            } else {
                assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), review.items.map { it.quantity })
                assertTrue(review.unresolvedText.isEmpty() && review.excludedText.isEmpty())
            }
        }

        // A leading greeting is meaningful content in the whole gap; do not trim it
        // into a valid continuation or overlap the existing greeting context.
        val greeting = "one milk. Good morning, could you also get two rice"
        val greetingPrimaries = primarySpans(greeting)
        val nextStart = greeting.indexOf("two rice")
        assertFalse(listTransitionSpans(greeting, greetingPrimaries).any {
            it.startUtf16 >= greetingPrimaries[0].endUtf16 && it.endUtf16 == nextStart
        })
    }

    @Test fun onlyACompleteFinalListClosingAfterAllItemsIsNeutral() {
        val closings = listOf(
            "That's all.", "That is everything!", "That's all for today.",
            "That's everything, thank you very much.", "Thanks very much.",
        )
        for (closing in closings) {
            val instruction = "$ITEMS. $closing"
            val spans = listTransitionSpans(instruction, primarySpans(instruction))
            assertTrue(closing, spans.any { instruction.substring(it.startUtf16, it.endUtf16).trim() == closing })
            val review = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), review.items.map { it.quantity })
            assertTrue(review.unresolvedText.isEmpty() && review.excludedText.isEmpty())
        }

        val meaningful = listOf(
            "That's all except milk.", "That's everything if available.",
            "That's all for tomorrow.", "That's all, no sugar.", "That's all, make milk two.",
            "That's all. Three eggs.", "That's all: organic.", "That's all\u000b.",
        )
        for (closing in meaningful) {
            val instruction = "$ITEMS. $closing"
            val spans = listTransitionSpans(instruction, primarySpans(instruction))
            assertFalse(closing, spans.any { instruction.substring(it.startUtf16, it.endUtf16).trim() == closing })
            if ('\u000b' !in closing) {
                val review = response(instruction).prepareForReview()
                assertTrue(closing, review.items.size < 2 ||
                    review.unresolvedText.isNotEmpty() || review.excludedText.isNotEmpty())
            }
        }
    }

    @Test fun onlyCompleteFinalAnaphoricClosingsReferToAlreadyOwnedItems() {
        val closings = listOf(
            "Please get those.",
            "Could you bring those to my cart?",
            "Zara ghar ke liye yeh sab le aaiyega.",
        )
        for (closing in closings) {
            val instruction = "$ITEMS. $closing"
            val primaries = primarySpans(instruction)
            val transitions = listTransitionSpans(instruction, primaries)
            assertTrue(closing, transitions.any {
                instruction.substring(it.startUtf16, it.endUtf16).trim() == closing
            })
            val review = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), review.items.map { it.quantity })
            assertTrue(review.unresolvedText.isEmpty() && review.excludedText.isEmpty())
        }

        val invalidClosings = listOf(
            "Please get those from last time.",
            "Please get those except milk.",
            "Please do not get those.",
            "Please get those by tomorrow.",
            "Please get those and 3 eggs.",
            "Please get those, make milk two.",
            "Those.",
        )
        for (closing in invalidClosings) {
            val instruction = "$ITEMS. $closing"
            val transitions = listTransitionSpans(instruction, primarySpans(instruction))
            assertFalse(closing, transitions.any {
                instruction.substring(it.startUtf16, it.endUtf16).trim() == closing
            })
        }

        val opening = "Please get those: $ITEMS"
        assertTrue(listTransitionSpans(opening, primarySpans(opening)).isEmpty())

        val swallowed = "$ITEMS. Please get those."
        val normalPrimaries = primarySpans(swallowed)
        val swallowingPrimaries = normalPrimaries.dropLast(1) + GroceryIntentCoverageSpan(
            normalPrimaries.last().startUtf16, swallowed.length,
        )
        assertTrue(listTransitionSpans(swallowed, swallowingPrimaries).isEmpty())

        val meaningfulPrefix = "deliver by tomorrow. $ITEMS. Please get those."
        val prefixSpan = spanOf(meaningfulPrefix, "deliver by tomorrow")
        val meaningfulPrimaries = primarySpans(meaningfulPrefix)
        val transitions = listTransitionSpans(meaningfulPrefix, meaningfulPrimaries)
        assertTrue(transitions.any { meaningfulPrefix.substring(it.startUtf16, it.endUtf16).trim() == "Please get those." })
        assertFalse(transitions.any {
            it.startUtf16 < prefixSpan.endUtf16 && prefixSpan.startUtf16 < it.endUtf16
        })
        val contextual = neutralGroceryIntentContextSpans(meaningfulPrefix, meaningfulPrimaries)
        assertFalse(contextual.any {
            it.startUtf16 <= prefixSpan.startUtf16 && prefixSpan.endUtf16 <= it.endUtf16
        })
        val review = response(meaningfulPrefix).prepareForReview()
        assertTrue(review.items.size < 2 || review.unresolvedText.isNotEmpty() || review.excludedText.isNotEmpty())
    }

    @Test fun finalTodayClosingDoesNotHideEarlierDeadlineOrUnverifiedCorrection() {
        val prefixes = listOf("deliver by today", "make milk two")
        for (prefix in prefixes) {
            val instruction = "$prefix. $ITEMS. That's all for today."
            val primaries = primarySpans(instruction)
            val transitions = listTransitionSpans(instruction, primaries)
            assertTrue(transitions.any {
                instruction.substring(it.startUtf16, it.endUtf16).trim() == "That's all for today."
            })

            val prefixSpan = spanOf(instruction, prefix)
            assertTrue("$prefix must remain uncovered: $transitions", transitions.none {
                it.startUtf16 < prefixSpan.endUtf16 && prefixSpan.startUtf16 < it.endUtf16
            })
            val contextual = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("$prefix must remain uncovered: $contextual", contextual.none {
                it.startUtf16 <= prefixSpan.startUtf16 && prefixSpan.endUtf16 <= it.endUtf16
            })

            val review = response(instruction).prepareForReview()
            assertTrue(prefix, review.items.size < 2 ||
                review.unresolvedText.isNotEmpty() || review.excludedText.isNotEmpty())
        }

        val byToday = "$ITEMS. That's all by today."
        val byTodaySpans = listTransitionSpans(byToday, primarySpans(byToday))
        assertFalse(byTodaySpans.any {
            byToday.substring(it.startUtf16, it.endUtf16).trim() == "That's all by today."
        })
        val byTodayReview = response(byToday).prepareForReview()
        assertTrue(byTodayReview.items.size < 2 || byTodayReview.unresolvedText.isNotEmpty() ||
            byTodayReview.excludedText.isNotEmpty())
    }

    @Test fun transitionMatcherRejectsMeaningfulGapsAndInvalidPrimarySets() {
        val badGaps = listOf(
            " except milk, also add ", " if available, add ", "; don't also add ",
            "; without sugar, add ", "; add organic ", "; add two ",
            "; remove milk and add ", "; instead of milk add ", "; add by tomorrow ",
            "; only add ", "; no more than ", "; also get BrandA ",
            "; Round milk off with ", "; round it off to ", "; round it off with less ",
            "; Thanks very much except bread And round it off with ",
            "; Could you\n also get ", "; could you also get\u000b ",
        )
        for (gap in badGaps) {
            val instruction = "one milk$gap" + "two rice"
            val primaries = primarySpans(instruction)
            assertTrue(listTransitionSpans(instruction, primaries).isEmpty())
            if ('\n' !in gap && '\u000b' !in gap) {
                val review = response(instruction).prepareForReview()
                assertTrue(gap, review.items.size < 2 ||
                    review.unresolvedText.isNotEmpty() || review.excludedText.isNotEmpty())
            }
        }

        val instruction = "one milk. Please add two rice. That's all."
        val valid = primarySpans(instruction)
        val invalidSets = listOf(
            emptyList(),
            listOf(GroceryIntentCoverageSpan(-1, 3)) + valid,
            valid + valid[0],
            List(51) { valid[0] },
            listOf(GroceryIntentCoverageSpan(0, instruction.indexOf("two rice") + 4), valid[1]),
        )
        for (invalid in invalidSets) {
            assertTrue(listTransitionSpans(instruction, invalid).isEmpty())
        }

        val omittedQualifier = "one milk. two rice without sugar"
        val review = response(omittedQualifier).prepareForReview()
        assertTrue(review.items.size < 2 || review.unresolvedText.isNotEmpty() || review.excludedText.isNotEmpty())
    }

    @Test fun pastSectionOrderMasksOnlyTheIntroAndRequiresUniqueOrderedOriginalHeadings() {
        val cases = listOf(
            "We wrote pantry goods first and then fridge products." to ("pantry goods:" to "fridge products:"),
            "I have listed breakfast items first and then dinner things." to ("breakfast items:" to "dinner things:"),
            "We noted down household first and then garden." to ("household:" to "garden:"),
        )
        for ((intro, labels) in cases) {
            val (firstHeader, secondHeader) = labels
            val instruction = "$intro $firstHeader one milk; $secondHeader two rice"
            val primaries = primarySpans(instruction)
            val expectedIntro = GroceryIntentCoverageSpan(0, intro.length)
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue("$instruction -> $spans", spans.contains(expectedIntro))
            assertEquals(intro, instruction.substring(expectedIntro.startUtf16, expectedIntro.endUtf16))
            assertEquals(listOf("one milk", "two rice"), primaries.map {
                instruction.substring(it.startUtf16, it.endUtf16)
            })
            for (retained in listOf(firstHeader, "one milk", secondHeader, "two rice")) {
                val span = spanOf(instruction, retained)
                assertFalse("Editorial span must not erase '$retained'", spans.any {
                    it.startUtf16 < span.endUtf16 && span.startUtf16 < it.endUtf16
                })
            }
        }

        val intro = "We wrote pantry goods first and then fridge products."
        val instruction = "$intro pantry goods: one milk; fridge products: two rice"
        val prepared = response(instruction).prepareForReview()
        assertEquals(listOf("milk", "rice"), prepared.items.map { it.query })
        val retained = (prepared.unresolvedText + prepared.excludedText).joinToString(" ")
        assertTrue(retained.contains("pantry goods"))
        assertTrue(retained.contains("fridge products"))

        val invalid = listOf(
            "We wrote 2 pantry items first and then fridge items." to "pantry items: one milk; fridge items: two rice",
            "We wrote pantry items first and then fridge items." to "pantry items: one milk; pantry items: two rice; fridge items: two rice",
            "We wrote pantry items first and then fridge items." to "pantry items: one milk; two rice",
            "We wrote pantry items first and then fridge items." to "fridge items: one milk; pantry items: two rice",
            "We wrote pantry items first and then fridge items." to "tomorrow pantry items: one milk; fridge items: two rice",
            "We wrote only pantry items first and then fridge items." to "pantry items: one milk; fridge items: two rice",
            "We wrote pantry items first and then no sugar." to "pantry items: one milk; no sugar: two rice",
            "We wrote pantry items first and then 2 fridge items." to "pantry items: one milk; 2 fridge items: two rice",
            "We wrote pantry items first and then fridge items." to "pantry items: one milk; fr\u0131dge items: two rice",
            "We wrote pantry items first and then fridge items.\n" to "pantry items: one milk; fridge items: two rice",
        )
        for ((badIntro, body) in invalid) {
            val malformed = "$badIntro $body"
            assertFalse(malformed, neutralGroceryIntentContextSpans(malformed, primarySpans(malformed)).any {
                it.startUtf16 == 0 && it.endUtf16 == badIntro.length
            })
        }

        val overlapping = "We wrote pantry items first and then fridge items. pantry items: one milk; fridge items: two rice"
        val good = primarySpans(overlapping)
        for (bad in listOf(
            good + good.first(),
            listOf(good.first(), GroceryIntentCoverageSpan(good.first().startUtf16, good.last().startUtf16 + 2)),
            listOf(GroceryIntentCoverageSpan(-1, good.first().endUtf16), good.last()),
        )) {
            assertFalse(neutralGroceryIntentContextSpans(overlapping, bad).any { it.startUtf16 == 0 })
        }
    }

    @Test fun terminalHelpThanksClosesOnlyTheCompleteFinalListAfterAllPrimaries() {
        val closings = listOf(
            "Thank you for helping me.", "Thanks for helping us!", "Thank you very much for sorting it.",
            "Thanks for arranging the list.", "Thank you for noting this.",
        )
        for (closing in closings) {
            val instruction = "$ITEMS. $closing"
            val primaries = primarySpans(instruction)
            val spans = neutralGroceryIntentContextSpans(instruction, primaries)
            assertTrue(closing, spans.any { instruction.substring(it.startUtf16, it.endUtf16).trim() == closing })
            val prepared = response(instruction).prepareForReview()
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val invalid = listOf(
            "Thank you for helping me if milk is available.",
            "Thanks for helping us, and add bread.",
            "Thank you for helping my father.",
            "Thanks for arranging the list tomorrow.",
            "Thank you for noting the sugar-free list.",
            "Thank you for helping me. 3 eggs.",
            "Thank you for helping me\n.",
        )
        for (closing in invalid) {
            val instruction = "$ITEMS. $closing"
            val primaries = primarySpans(instruction)
            assertFalse(closing, neutralGroceryIntentContextSpans(instruction, primaries).any {
                it.startUtf16 >= primaries.last().endUtf16
            })
        }

        val beforeAllItems = "one milk. Thank you for helping me. two rice"
        assertFalse(neutralGroceryIntentContextSpans(beforeAllItems, primarySpans(beforeAllItems)).any {
            it.startUtf16 >= beforeAllItems.indexOf("Thank you")
        })
        val duplicatedPrimary = "$ITEMS. Thank you for helping me."
        val duplicateSpans = primarySpans(duplicatedPrimary) + primarySpans(duplicatedPrimary).first()
        assertFalse(neutralGroceryIntentContextSpans(duplicatedPrimary, duplicateSpans).any {
            it.startUtf16 >= duplicatedPrimary.indexOf("Thank you")
        })
    }

    @Test fun orderedEditorialSectionContextPreparesTwoLiteralBrandGroupsWithoutErasingTheirHeadings() {
        val intro = "I listed the BrandA items first and then the BrandB products."
        val sectionA = "BrandA:"
        val sectionB = "BrandB:"
        val sourcesA = listOf("1 BrandA milk", "2 BrandA curd")
        val sourcesB = listOf("1 BrandB rice", "2 BrandB flour")
        val instruction = "$intro $sectionA ${sourcesA.joinToString(", ")}; $sectionB ${sourcesB.joinToString(", ")}. Thanks for arranging the list."

        fun quote(value: String): String {
            val start = instruction.indexOf(value).also { require(it >= 0) }
            return """{"text":${q(value)},"start_utf16":$start,"end_utf16":${start + value.length}}"""
        }
        fun row(index: Int, source: String, name: String): String {
            val amount = source.substringBefore(' ')
            return """{"row_index":$index,"item":{"name":${q(name)},"quantity":${amount.toInt()},"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${q(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${q(amount)},"pack_text":null},"evidence":{"primary":${quote(source)},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
        }
        fun group(header: String, sources: List<String>, indices: List<Int>): String {
            val source = "$header ${sources.joinToString(", ")}"
            val members = indices.zip(sources).joinToString(",") { (index, itemSource) ->
                """{"row_index":$index,"value":${itemSource.substringBefore(' ').toInt()},"unit":"count","origin":"local"}"""
            }
            return """{"status":"structurally_verified","amount_status":"local_amounts_verified","source":${quote(source)},"header":${quote(header)},"member_indices":[${indices.joinToString(",")}],"members":[$members],"packaging":null,"total":null,"each":null}"""
        }
        fun responseWithRows(secondBrandAName: String = "BrandA curd"): GroceryIntentReviewV2 {
            val sources = sourcesA + sourcesB
            val names = listOf("BrandA milk", secondBrandAName, "BrandB rice", "BrandB flour")
            val rows = sources.mapIndexed { index, source -> row(index, source, names[index]) }
            val groups = listOf(group(sectionA, sourcesA, listOf(0, 1)), group(sectionB, sourcesB, listOf(2, 3)))
            val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${q(instruction)},"source_item_count":4,"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[],"groups":[${groups.joinToString(",")}]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
            return GroceryIntentReviewV2Response.parse(body, instruction).draft
        }

        val primaries = listOf("1 BrandA milk", "2 BrandA curd", "1 BrandB rice", "2 BrandB flour")
            .map { spanOf(instruction, it) }
        val introSpan = GroceryIntentCoverageSpan(0, intro.length)
        val contextSpans = neutralGroceryIntentContextSpans(instruction, primaries)
        assertTrue(contextSpans.contains(introSpan))
        assertFalse(contextSpans.any { it.startUtf16 < instruction.indexOf(sectionA) + sectionA.length &&
            instruction.indexOf(sectionA) < it.endUtf16 })
        assertFalse(contextSpans.any { it.startUtf16 < instruction.indexOf(sectionB) + sectionB.length &&
            instruction.indexOf(sectionB) < it.endUtf16 })

        val prepared = responseWithRows().prepareForReview()
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2), Quantity.Count(1), Quantity.Count(2)),
            prepared.items.map { it.quantity })
        assertEquals(4, prepared.items.size)
        assertTrue(prepared.items[0].query.contains("BrandA"))
        assertTrue(prepared.items[1].query.contains("BrandA"))
        assertTrue(prepared.items[2].query.contains("BrandB"))
        assertTrue(prepared.items[3].query.contains("BrandB"))
        assertEquals(listOf("BrandA", "BrandB"), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())

        val missingSharedBrand = responseWithRows(secondBrandAName = "curd").prepareForReview()
        assertTrue(missingSharedBrand.items.none { it.query.contains("BrandA") })
        assertTrue(missingSharedBrand.items.any { it.query.contains("BrandB") })
        assertFalse(missingSharedBrand.sharedGroupNotes.contains("BrandA"))
        assertTrue((missingSharedBrand.excludedText + missingSharedBrand.unresolvedText)
            .joinToString(" ").contains("BrandA: 1 BrandA milk, 2 BrandA curd"))
    }

    private fun primarySpans(instruction: String) = listOf(spanOf(instruction, "one milk"), spanOf(instruction, "two rice"))

    private fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n") + "\""

    private fun response(instruction: String): GroceryIntentReviewV2 {
        val rows = listOf("one milk", "two rice").mapIndexed { index, source ->
            val start = instruction.indexOf(source)
            val name = source.substringAfter(' ')
            val amount = source.substringBefore(' ')
            """{"row_index":$index,"item":{"name":${q(name)},"quantity":${index + 1},"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${q(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${q(amount)},"pack_text":null},"evidence":{"primary":{"text":${q(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
        }
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${q(instruction)},"source_item_count":2,"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction).draft
    }

    private fun correctedCountResponse(instruction: String, correction: String): GroceryIntentReviewV2 {
        val sources = listOf("1 milk", "2 rice")
        val rows = sources.mapIndexed { index, source ->
            val start = instruction.indexOf(source)
            val name = source.substringAfter(' ')
            val amount = source.substringBefore(' ')
            val correctionStart = instruction.indexOf(correction)
            val correctionJson = if (index != 0) "" else {
                val amountStart = correctionStart + correction.lastIndexOf("2")
                val correctionEnd = correctionStart + correction.length
                ",\"corrections\":[{\"kind\":\"quantity\",\"source\":{\"text\":${q(correction)}," +
                    "\"start_utf16\":$correctionStart,\"end_utf16\":$correctionEnd},\"new_amount\":{\"text\":\"2\"," +
                    "\"start_utf16\":$amountStart,\"end_utf16\":${amountStart + 1}},\"old_value\":1," +
                    "\"old_unit\":\"count\",\"new_value\":2,\"new_unit\":\"count\"}]"
            }
            """{"row_index":$index,"item":{"name":${q(name)},"quantity":${amount.toInt()},"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${q(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${q(amount)},"pack_text":null},"evidence":{"primary":{"text":${q(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"$correctionJson}"""
        }
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${q(instruction)},"source_item_count":2,"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction).draft
    }

    private fun primarySpansForCountCorrection(instruction: String) =
        listOf(spanOf(instruction, "1 milk"), spanOf(instruction, "2 rice"))

    private fun spanOf(instruction: String, quote: String): GroceryIntentCoverageSpan {
        val start = instruction.indexOf(quote)
        require(start >= 0)
        return GroceryIntentCoverageSpan(start, start + quote.length)
    }

    private companion object {
        const val ITEMS = "one milk, two rice"
    }
}
