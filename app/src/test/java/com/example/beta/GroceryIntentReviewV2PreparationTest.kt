package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun boundPhysicalSizeCandidate(spinId: String, label: String, rawSingleSize: String) =
    RecommendationCandidate(
        spinId = spinId,
        label = label,
        skuId = "$spinId-sku",
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = spinId,
            skuId = "$spinId-sku",
            fields = CatalogueQuantityFields(packSize = rawSingleSize),
        ),
    )

class GroceryIntentReviewV2PreparationTest {
    @Test fun dietarySuffixInCroppedPrimaryCannotCloseContentsScope() {
        listOf("no sugar", "no added sugar", "no added salt").forEach { property ->
            listOf(", ", "; ", "\n").forEach { separator ->
                val parts = listOf("one hamper with $property", "two tea bags", "three biscuits")
                val instruction = parts.joinToString(separator)
                assertTrue(hasGroceryIntentCrossItemContentsScope(instruction,
                    parts.map { quote(instruction, it) }))
            }
        }
    }

    @Test fun crossItemContentsScopeCannotBeDischargedByDietaryPropertiesOrQuotedImperatives() {
        fun assertHeld(instruction: String, items: List<GroceryIntentReviewV2Item>) {
            // Isolate this scope guard from other possible identity failures in
            // a malicious proposed split of the printed text.
            assertTrue(instruction, hasGroceryIntentCrossItemContentsScope(
                instruction, items.map { quote(instruction, it.sourceText) }, items.size,
            ))
            val prepared = review(instruction, ready = items.mapIndexed { index, item ->
                readyRow(instruction, index, item)
            }).prepareForReview()
            assertTrue(instruction, prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
            assertTrue(prepared.excludedText.isEmpty())
        }

        for (separator in listOf(", ", "; ", "\n")) {
            val hamper = "one hamper with no added sugar"
            val tea = "two tea bags"
            val biscuits = "three biscuits"
            val instruction = listOf(hamper, tea, biscuits).joinToString(separator)
            assertHeld(instruction, listOf(
                item("hamper", hamper, 1, "count", "one", required = listOf("no_added_sugar")),
                item("tea bags", tea, 2, "count", "two"),
                item("biscuits", biscuits, 3, "count", "three"),
            ))
        }

        val greetingCard = "one greeting card with the text \"Hello.\nThen buy two rice\""
        val greetingRows = listOf(
            item("greeting card", "one greeting card with the text \"Hello.", 1, "count", "one"),
            item("rice", "Then buy two rice\"", 2, "count", "two"),
        )
        assertHeld(greetingCard, greetingRows)

        val poster = "one poster with these words:\nThen buy two rice"
        val posterRows = listOf(
            item("poster", "one poster with these words:", 1, "count", "one"),
            item("rice", "Then buy two rice", 2, "count", "two"),
        )
        assertHeld(poster, posterRows)

        val madeWith = "one gift hamper made with woven cotton, two tea bags, three biscuits"
        val madeWithRows = listOf(
            item("gift hamper", "one gift hamper made with woven cotton", 1, "count", "one"),
            item("tea bags", "two tea bags", 2, "count", "two"),
            item("biscuits", "three biscuits", 3, "count", "three"),
        )
        assertHeld(madeWith, madeWithRows)
    }

    @Test fun borrowedQuantityCannotExemptDefiniteDescription() {
        val instruction = "one BrandA cleaning spray, the BrandA red bottle; two rice"
        val rows = listOf(
            readyRow(instruction, 0, item("BrandA cleaning spray", "one BrandA cleaning spray", 1, "count", "one")),
            readyRow(instruction, 1, item("BrandA red bottle", "the BrandA red bottle", 1, "count", "one")),
            readyRow(instruction, 2, item("rice", "two rice", 2, "count", "two")),
        )
        val prepared = review(instruction, ready = rows).prepareForReview()
        assertEquals(listOf("rice"), prepared.items.map { it.query })
        assertTrue(prepared.excludedText.any { it.contains("one BrandA cleaning spray") })
        assertTrue(prepared.excludedText.any { it.contains("the BrandA red bottle") })
    }

    @Test fun blockedDefiniteSharedDescriptionCannotReleaseIncompletePredecessor() {
        listOf(false, true).forEach { descriptionBlocked ->
            listOf("the BrandA red bottle", "BrandA red bottle").forEach { description ->
                val instruction = "one BrandA cleaning spray, the BrandA red bottle; two rice"
                val first = readyRow(instruction, 0, item("BrandA cleaning spray", "one BrandA cleaning spray", 1, "count", "one"))
                val last = readyRow(instruction, 2, item("rice", "two rice", 2, "count", "two"))
                val rows = listOf(first, last) + if (descriptionBlocked) emptyList() else
                    listOf(readyRow(instruction, 1, item("BrandA red bottle", description)))
                val blocked = if (descriptionBlocked) listOf(blockedRow(instruction, 1, description)) else emptyList()
                val result = review(instruction, ready = rows, blocked = blocked).prepareForReview()
                assertEquals(listOf("rice"), result.items.map { it.query })
                assertEquals(listOf(Quantity.Count(2)), result.items.map { it.quantity })
                assertTrue((result.excludedText + result.unresolvedText).any { it.contains("BrandA cleaning spray") })
                assertTrue((result.excludedText + result.unresolvedText).any { it.contains("BrandA red bottle") })
            }
        }
    }

    @Test fun commaAppositiveUnstatedAliasHoldsBothRowsWithoutMerging() {
        val instruction = "two Mandarin Orange, kittale hannu, and one rice"
        val rows = listOf(
            readyRow(instruction, 0, item("mandarin orange", "two Mandarin Orange", 2, "count", "two")),
            readyRow(instruction, 1, item("orange", "kittale hannu")),
            readyRow(instruction, 2, item("rice", "one rice", 1, "count", "one")),
        )
        val prepared = review(instruction, ready = rows).prepareForReview()
        assertEquals(listOf("rice"), prepared.items.map { it.query })
        assertTrue(prepared.excludedText.any { it.contains("two Mandarin Orange") })
        assertTrue(prepared.excludedText.any { it.contains("kittale hannu") })
    }

    @Test fun definiteRefinementDenialNeedsSharedIdentityAndDoesNotReinterpretCountedReadyRows() {
        listOf("the bread" to true, "the two BrandA red bottles" to false, "BrandA red bottle" to true).forEach { (description, unstated) ->
            val instruction = "one BrandA spray, $description"
            val rows = listOf(
                GroceryIntentAppositiveSourceRow(0, quote(instruction, "one BrandA spray"), true, false),
                GroceryIntentAppositiveSourceRow(1, quote(instruction, description), true, unstated),
            )
            assertTrue(description, groceryIntentAppositiveSourceSplitRows(instruction, rows,
                if (unstated) emptySet() else setOf(1)).isEmpty())
        }
    }

    @Test fun appositiveGuardUsesExactAdjacentSourceAndOnlyUnstatedReadyRightRows() {
        fun records(instruction: String, left: String, right: String, rightReady: Boolean = true,
                    rightUnstated: Boolean = true) = listOf(
            GroceryIntentAppositiveSourceRow(0, quote(instruction, left), true, false),
            GroceryIntentAppositiveSourceRow(1, quote(instruction, right), rightReady, rightUnstated),
        )
        val comma = "two Mandarin Orange, kittale hannu"
        assertEquals(setOf(0, 1), groceryIntentAppositiveSourceSplitRows(comma,
            records(comma, "two Mandarin Orange", "kittale hannu")))
        assertTrue(groceryIntentAppositiveSourceSplitRows("😀 $comma",
            records("😀 $comma", "two Mandarin Orange", "kittale hannu")).containsAll(setOf(0, 1)))
        assertTrue(groceryIntentAppositiveSourceSplitRows(comma,
            records(comma, "two Mandarin Orange", "kittale hannu", rightReady = false)).isEmpty())
        assertTrue(groceryIntentAppositiveSourceSplitRows(comma,
            records(comma, "two Mandarin Orange", "kittale hannu", rightUnstated = false)).isEmpty())

        val conjunction = "two Mandarin Orange, and kittale hannu"
        assertTrue(groceryIntentAppositiveSourceSplitRows(conjunction,
            records(conjunction, "two Mandarin Orange", "kittale hannu")).isEmpty())
        val newline = "two Mandarin Orange,\nkittale hannu"
        assertTrue(groceryIntentAppositiveSourceSplitRows(newline,
            records(newline, "two Mandarin Orange", "kittale hannu")).isEmpty())
        val unrelated = "one milk, bread"
        assertTrue(groceryIntentAppositiveSourceSplitRows(unrelated,
            records(unrelated, "one milk", "bread")).isEmpty())
        assertTrue(groceryIntentAppositiveSourceSplitRows(comma,
            records(comma, "two Mandarin Orange", "kittale hannu").reversed()).containsAll(setOf(0, 1)))
        assertTrue(groceryIntentAppositiveSourceSplitRows(comma,
            records(comma, "two Mandarin Orange", "kittale hannu").map {
                if (it.rowIndex == 1) it.copy(primary = it.primary.copy(text = "hannu")) else it
            }).isEmpty())

        val overlapThenPair = "some ambiguous area, two mandarin oranges, kittale hannu"
        val overlapRows = listOf(
            GroceryIntentAppositiveSourceRow(0, quote(overlapThenPair, "some ambiguous area"), false, false),
            GroceryIntentAppositiveSourceRow(1, quote(overlapThenPair, "ambiguous area"), false, false),
            GroceryIntentAppositiveSourceRow(2, quote(overlapThenPair, "two mandarin oranges"), true, false),
            GroceryIntentAppositiveSourceRow(3, quote(overlapThenPair, "kittale hannu"), true, true),
        )
        assertEquals(setOf(2, 3), groceryIntentAppositiveSourceSplitRows(overlapThenPair, overlapRows))

        val articleAlias = "one apple, the sebu"
        assertEquals(setOf(0, 1), groceryIntentAppositiveSourceSplitRows(articleAlias,
            records(articleAlias, "one apple", "the sebu")))
    }

    @Test fun blockedAppositivePredecessorStillPreventsUnstatedAliasConversion() {
        val instruction = "two Mandarin Orange, kittale hannu"
        val ready = readyRow(instruction, 1, item("orange", "kittale hannu"))
        val prepared = review(instruction, ready = listOf(ready),
            blocked = listOf(blockedRow(instruction, 0, "two Mandarin Orange"))).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.any { it.contains("kittale hannu") })
    }

    @Test fun unlocatedBlockedRowDoesNotDisableAdjacentAppositiveDenial() {
        val instruction = "two Mandarin Orange, kittale hannu; rice"
        val orange = readyRow(instruction, 0, item("mandarin orange", "two Mandarin Orange", 2, "count", "two"))
        val alias = readyRow(instruction, 1, item("orange", "kittale hannu"))
        val unlocated = GroceryIntentReviewV2BlockedRow(2, null, listOf("model_needs_review"))
        val prepared = review(instruction, ready = listOf(orange, alias), blocked = listOf(unlocated)).prepareForReview()
        assertFalse(prepared.items.any { it.query.contains("orange", ignoreCase = true) })
        val reviewText = prepared.unresolvedText + prepared.excludedText
        assertTrue(reviewText.any { it.contains("two Mandarin Orange") })
        assertTrue(reviewText.any { it.contains("kittale hannu") })
        assertTrue(prepared.unresolvedText.any { it.contains("rice") })
    }

    @Test fun commaArticleAliasCannotBecomeAnAdditionalUnquantifiedPurchase() {
        val instruction = "one apple, the sebu"
        val rows = listOf(
            readyRow(instruction, 0, item("apple", "one apple", 1, "count", "one")),
            readyRow(instruction, 1, item("apple", "the sebu")),
        )
        val prepared = review(instruction, ready = rows).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.any { it.contains("one apple") })
        assertTrue(prepared.excludedText.any { it.contains("the sebu") })
    }

    @Test fun terminalCommaNotPropertyKeepsExactSingleItemPolarityAndCount(): Unit {
        val source = "one milk, not organic"
        val prepared = review(source, ready = listOf(readyRow(source,
            0, item("milk", source, 1, "count", "one", excluded = listOf("organic")),
        ))).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertEquals(listOf("organic"), prepared.items.single().avoidPhrases)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun terminalCommaNotPropertyAllowsOnlyClosedSuffixAndNeutralRequestPrefix(): Unit {
        listOf("", " ", "\t", ".", "!", "?", ". \t").forEach { suffix ->
            val source = "one milk, not organic"
            val instruction = source + suffix
            val prepared = review(instruction, ready = listOf(readyRow(instruction,
                0, item("milk", source, 1, "count", "one", excluded = listOf("organic")),
            ))).prepareForReview()
            assertEquals("suffix=$suffix", 1, prepared.items.size)
        }

        val prefix = "Please add these to the list: "
        val source = "one mother's-choice milk, not organic"
        val instruction = "$prefix$source."
        val prefixed = review(instruction, ready = listOf(readyRow(instruction,
            0, item("mother's-choice milk", source, 1, "count", "one", excluded = listOf("organic")),
        ))).prepareForReview()
        assertEquals(1, prefixed.items.size)

        val decimal = "1.5 kg rice, not organic"
        val decimalItem = item("rice", decimal, null, excluded = listOf("organic")).copy(
            quantity = BigDecimal("1.5"), quantityUnit = "kg", quantityText = "1.5",
        )
        val decimalPrepared = review(decimal, ready = listOf(readyRow(decimal, 0, decimalItem))).prepareForReview()
        assertEquals(1, decimalPrepared.items.size)
        assertEquals(Quantity.Weight(1500), decimalPrepared.items.single().quantity)
    }

    @Test fun terminalPropertyMayEndBeforeACompleteProvenCourtesySuffix(): Unit {
        val source = "one milk, not organic"
        val instruction = "$source. Thanks"
        val prepared = review(instruction, ready = listOf(readyRow(instruction,
            0, item("milk", source, 1, "count", "one", excluded = listOf("organic"))),
        )).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test fun terminalPropertyCannotSkipUnownedOrObligatingSuffix(): Unit {
        val source = "one milk, not organic"
        listOf(". Thanks; two eggs", ". Thanks if cheap", ". Thanks, not plain", ". Tomorrow").forEach { tail ->
            val instruction = source + tail
            val prepared = review(instruction, ready = listOf(readyRow(instruction,
                0, item("milk", source, 1, "count", "one", excluded = listOf("organic"))),
            )).prepareForReview()
            assertTrue("tail=$tail", prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
        }
    }

    @Test fun terminalCommaNotPropertyStillRequiresTheNegativePropertyTag(): Unit {
        val source = "one milk, not organic"
        listOf(
            item("milk", source, 1, "count", "one"),
            item("milk", source, 1, "count", "one", required = listOf("organic")),
            item("milk", source, 1, "count", "one", required = listOf("organic"), excluded = listOf("organic")),
        ).forEach { candidate ->
            val prepared = review(source, ready = listOf(readyRow(source, 0, candidate))).prepareForReview()
            assertTrue(prepared.items.isEmpty())
        }
    }

    @Test fun terminalCommaNotPropertyRejectsPunctuationConjunctionAndMultipleOriginalProducts(): Unit {
        val unsafe = listOf(
            "one milk. eggs, not organic", "one milk & eggs, not organic",
            "one milk/eggs, not organic", "one milk! eggs, not organic",
            "one milk? eggs, not organic", "one milk: eggs, not organic",
            "one milk— eggs, not organic", "one milk\neggs, not organic",
            "one milk + eggs, not organic", "two eggs and one milk, not organic",
        )
        unsafe.forEach { instruction ->
            val prepared = review(instruction, ready = listOf(readyRow(instruction, 0,
                item("milk", instruction, 1, "count", "one", excluded = listOf("organic")),
            ))).prepareForReview()
            assertTrue("must retain the complete original clause: $instruction", prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
        }
    }

    @Test fun terminalCommaNotPropertyRejectsConnectiveMultiitemPrefixes(): Unit {
        listOf(
            "one milk as well as eggs, not organic", "one milk also eggs, not organic",
            "one milk alongside eggs, not organic", "one milk besides eggs, not organic",
            "one milk in addition to eggs, not organic", "one milk followed by eggs, not organic",
        ).forEach { instruction ->
            val prepared = review(instruction, ready = listOf(readyRow(instruction, 0,
                item("milk", instruction, 1, "count", "one", excluded = listOf("organic"))),
            )).prepareForReview()
            assertTrue("must retain connective clause: $instruction", prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
        }
    }

    @Test fun croppedTerminalPropertyCannotReleaseOriginalSuffixOrPeer(): Unit {
        val instruction = "one milk, not organic eggs"
        val cropped = "one milk, not organic"
        val rows = listOf(
            readyRow(instruction, 0, item("milk", cropped, 1, "count", "one", excluded = listOf("organic"))),
            readyRow(instruction, 1, item("eggs", "eggs", 1, "count", "one")),
        )
        listOf(rows, rows.reversed()).forEach { permutation ->
            val prepared = review(instruction, ready = permutation).prepareForReview()
            assertTrue(prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
        }

        val onlyCropped = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("milk", cropped, 1, "count", "one", excluded = listOf("organic"))),
        )).prepareForReview()
        assertTrue(onlyCropped.items.isEmpty())
        assertEquals(listOf(instruction), onlyCropped.unresolvedText)
    }

    @Test fun terminalPropertyScopeRejectsSupportOperationsDuplicateQuotesAndFragments(): Unit {
        val instruction = "one milk, not organic"
        val remoteSupport = readyRow(instruction, 0,
            item("milk", "one milk", 1, "count", "one", excluded = listOf("organic")),
            listOf(GroceryIntentReviewV2SupportText("modifier", ", not organic")),
        )
        val supportReview = review(instruction, ready = listOf(remoteSupport)).prepareForReview()
        assertTrue(supportReview.items.isEmpty())
        assertEquals(listOf(instruction), supportReview.unresolvedText)

        val completeRow = readyRow(instruction, 0,
            item("milk", instruction, 1, "count", "one", excluded = listOf("organic")))
        val grouped = review(instruction, ready = listOf(completeRow)).copy(
            groups = listOf(localGroup(instruction, instruction, listOf(0 to instruction))),
        ).prepareForReview()
        assertTrue(grouped.items.isEmpty())
        assertEquals(listOf(instruction), grouped.unresolvedText)

        val edited = completeRow.copy(corrections = listOf(GroceryIntentReviewV2Correction(
            kind = "quantity", source = quote(instruction, ", not organic"), newAmount = quote(instruction, "organic"),
            oldValue = BigDecimal.ONE, oldUnit = "count", newValue = BigDecimal("2"), newUnit = "count",
        )))
        val corrected = review(instruction, ready = listOf(edited)).prepareForReview()
        assertTrue(corrected.items.isEmpty())
        assertEquals(listOf(instruction), corrected.unresolvedText)

        val repeated = "$instruction; $instruction"
        val duplicate = review(repeated, ready = listOf(readyRow(repeated, 0,
            item("milk", instruction, 1, "count", "one", excluded = listOf("organic"))))).prepareForReview()
        assertTrue(duplicate.items.isEmpty())
        assertEquals(listOf(repeated), duplicate.unresolvedText)

        val croppedToken = review(instruction, ready = listOf(readyRow(instruction, 0,
            item("milk", "milk, not organic", 1, "count", "one", excluded = listOf("organic"))))).prepareForReview()
        assertTrue(croppedToken.items.isEmpty())
        assertEquals(listOf(instruction), croppedToken.unresolvedText)
    }

    @Test fun forgedTerminalPropertySourceCountsAndNamedContrastCannotBypassTheGuard(): Unit {
        val instruction = "one milk, not organic"
        val row = readyRow(instruction, 0, item("milk", instruction, 1, "count", "one", excluded = listOf("organic")))
        listOf(0, 2).forEach { declaredCount ->
            val forged = review(instruction, ready = listOf(row)).copy(sourceItemCount = declaredCount).prepareForReview()
            assertTrue("count=$declaredCount", forged.items.isEmpty())
            assertEquals(listOf(instruction), forged.unresolvedText)
        }

        val contrast = "one milk must be organic, not sugar-free"
        val contrastReview = review(contrast, ready = listOf(readyRow(contrast, 0,
            item("milk", contrast, 1, "count", "one", required = listOf("organic"), excluded = listOf("sugar_free")),
        ))).prepareForReview()
        assertTrue(contrastReview.items.isEmpty())
        // Existing named-contrast validation retains rejected, located evidence
        // as excluded text; it is not an unlocated original-source segment.
        assertEquals(listOf(contrast), contrastReview.excludedText)
        assertTrue(contrastReview.unresolvedText.isEmpty())

        val longForm = "one milk without organic substitutions"
        val longReview = review(longForm, ready = listOf(readyRow(longForm, 0,
            item("milk", longForm, 1, "count", "one", excluded = listOf("organic")),
        ))).prepareForReview()
        assertEquals(1, longReview.items.size)
    }

    @Test fun crossItemContentsRelationHoldsForgedAllReadyRowsAndPreservesWholeSource(): Unit {
        val instruction = "one gift hamper containing jam and two bags of rice"
        val first = "one gift hamper containing jam"
        val second = "two bags of rice"
        val allReady = listOf(
            readyRow(instruction, 0, item("gift hamper containing jam", first, 1, "count", "one")),
            readyRow(instruction, 1, item("rice", second, 2, "pack", "two", packaging = "bag")),
        )
        listOf(allReady, allReady.reversed()).forEach { rows ->
            val prepared = review(instruction, ready = rows).prepareForReview()
            assertTrue(prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
            assertTrue(prepared.excludedText.isEmpty())
        }

        val blockedPredecessor = review(instruction,
            ready = listOf(allReady.last()),
            blocked = listOf(blockedRow(instruction, 0, first)),
        ).prepareForReview()
        assertTrue("a blocked predecessor still supplies original source scope", blockedPredecessor.items.isEmpty())
        assertEquals(listOf(instruction), blockedPredecessor.unresolvedText)
    }

    @Test fun crossItemContentsRelationMatrixUsesBoundOriginalSourceRanges(): Unit {
        val relations = listOf("contains", "containing", "comprising", "consists of", "filled with",
            "includes", "including", "with", "composed of", "made up of", "consisting of")
        relations.forEach { relation ->
            val first = "one hamper $relation jam"
            val second = "two bags of rice"
            val instruction = "$first and $second"
            val sources = listOf(quote(instruction, first), quote(instruction, second))
            assertTrue("contents relation should be detected: $instruction",
                hasGroceryIntentCrossItemContentsScope(instruction, sources))
        }
    }

    @Test fun crossItemContentsGuardPreservesUnicodeAndLineBreakSourceOffsets(): Unit {
        val first = "one hamper\ncontaining jam"
        val second = "two bags of rice"
        val instruction = "😀 $first and $second"
        val sources = listOf(quote(instruction, first), quote(instruction, second))
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction, sources))
        assertEquals(instruction.indexOf(first), sources.first().startUtf16)
        assertEquals(instruction.indexOf(second), sources.last().startUtf16)
    }

    @Test fun crossItemContentsGuardDoesNotFireForLeadingIncludingOrOneCompoundPrimary(): Unit {
        val leading = "including jam for breakfast, one hamper and two bags of rice"
        assertTrue(!hasGroceryIntentCrossItemContentsScope(leading, listOf(
            quote(leading, "one hamper"), quote(leading, "two bags of rice"),
        )))

        val compound = "one gift hamper containing jam"
        assertTrue(!hasGroceryIntentCrossItemContentsScope(compound, listOf(quote(compound, compound))))
    }

    @Test fun crossItemContentsScopeCannotDisappearWhenMultirowPrimaryEvidenceIsMissing(): Unit {
        val instruction = "one gift hamper containing jam and two bags of rice"
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction, emptyList(), sourceItemCount = 2))
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction,
            listOf(quote(instruction, "one gift hamper containing jam")), sourceItemCount = 2))
    }

    @Test fun crossItemContentsHelperRejectsDuplicateOrCroppedEvidenceAndAcceptsReorderedValidQuotes(): Unit {
        val instruction = "one hamper containing jam and two bags of rice"
        val first = quote(instruction, "one hamper containing jam")
        val second = quote(instruction, "two bags of rice")
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction, listOf(second, first)))
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction, listOf(first, first)))
        assertTrue(hasGroceryIntentCrossItemContentsScope(instruction,
            listOf(first.copy(text = first.text.dropLast(1)), second)))
    }

    @Test fun forgedReadyCountCannotHideCrossItemContentsAndUnlocatedBlockedOwner(): Unit {
        val instruction = "one gift hamper containing jam and two bags of rice"
        val first = "one gift hamper containing jam"
        val second = "two bags of rice"
        val rows = listOf(
            readyRow(instruction, 0, item("gift hamper containing jam", first, 1, "count", "one")),
            readyRow(instruction, 1, item("rice", second, 2, "pack", "two", packaging = "bag")),
        )
        listOf(0, 1).forEach { declaredCount ->
            val prepared = review(instruction, ready = rows).copy(sourceItemCount = declaredCount).prepareForReview()
            assertTrue("count=$declaredCount cannot free forged ready rows", prepared.items.isEmpty())
            assertEquals(listOf(instruction), prepared.unresolvedText)
        }

        val unlocatedOwner = review(instruction,
            ready = listOf(rows.last()),
            blocked = listOf(GroceryIntentReviewV2BlockedRow(0, null, listOf("model_needs_review"))),
        ).copy(sourceItemCount = 1).prepareForReview()
        assertTrue(unlocatedOwner.items.isEmpty())
        assertEquals(listOf(instruction), unlocatedOwner.unresolvedText)
    }

    @Test fun crossItemContentsGuardKeepsOrdinaryTwoItemControlAvailable(): Unit {
        val instruction = "one milk and two bags of rice"
        assertTrue(!hasGroceryIntentCrossItemContentsScope(instruction, listOf(
            quote(instruction, "one milk"), quote(instruction, "two bags of rice"),
        )))
    }

    @Test fun crossItemGuardIgnoresOnlyCompleteProvenEditorialTransitionMarkers(): Unit {
        val cases = listOf(
            ". And round it off with " to true,
            ". Thanks very much And round it off with " to true,
            ", please include " to true,
            ". Thank you. To complete the list with " to false,
        )
        cases.forEach { (gap, shouldHold) ->
            val ordinary = "one milk${gap}two rice"
            assertEquals(shouldHold, hasGroceryIntentCrossItemContentsScope(ordinary,
                listOf(quote(ordinary, "one milk"), quote(ordinary, "two rice"))))

            val bareHamper = "one gift hamper${gap}two bags of rice"
            assertEquals("bare hamper remains ambiguous: $bareHamper", shouldHold,
                hasGroceryIntentCrossItemContentsScope(bareHamper, listOf(
                    quote(bareHamper, "one gift hamper"), quote(bareHamper, "two bags of rice"),
                )))

            val compound = "one hamper containing jam${gap}two bags of rice"
            assertTrue("real containing relation remains in scope after transition: $compound",
                hasGroceryIntentCrossItemContentsScope(compound, listOf(
                    quote(compound, "one hamper containing jam"), quote(compound, "two bags of rice"),
                )))
        }
    }

    @Test fun crossItemGuardKeepsCroppedOrUnprovedEditorialMarkersAsObligations(): Unit {
        val instructions = listOf(
            "one milk. And round it off with extra two rice",
            "one milk. Thanks very much And round it off with BrandA two rice",
            "one milk. Thank you. To complete the list with no sugar two rice",
        )
        instructions.forEach { instruction ->
            assertTrue("unproved editorial transition cannot suppress marker: $instruction",
                hasGroceryIntentCrossItemContentsScope(instruction, listOf(
                    quote(instruction, "one milk"), quote(instruction, "two rice"),
                )))
        }
    }

    @Test fun sameCountRefinementCoversOnlySupersededTextAtHardBoundaries() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val clause = "$old, sorry, $final"
        val only = review(clause, ready = listOf(readyRow(
            clause, 0, item("organic certified lumaroot", final, 3, "count", "three"),
        ))).prepareForReview()
        assertEquals(listOf("organic certified lumaroot"), only.items.map { it.query })
        assertEquals(listOf(final), only.items.map { it.rawText })
        assertTrue(only.unresolvedText.isEmpty())
        assertTrue(only.excludedText.isEmpty())

        val withSibling = "$clause; two milk"
        val sibling = review(withSibling, ready = listOf(
            readyRow(withSibling, 0, item("organic certified lumaroot", final, 3, "count", "three")),
            readyRow(withSibling, 1, item("milk", "two milk", 2, "count", "two")),
        )).prepareForReview()
        assertEquals(listOf("organic certified lumaroot", "milk"), sibling.items.map { it.query })
        assertTrue(sibling.unresolvedText.isEmpty())
        assertTrue(sibling.excludedText.isEmpty())
    }

    @Test fun sameCountRefinementHoldsInvalidOrIncompleteBoundaryVariants() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val cases = listOf(
            Triple("$old, sorry, three organic lumaroot", "three organic lumaroot", "organic certified lumaroot"),
            Triple("$old, sorry, $final; two lumaroot", final, "organic certified lumaroot"),
            Triple("two organic lumaroot, sorry, $final", final, "organic certified lumaroot"),
            Triple("one organic lumaroot, sorry, $final", final, "organic certified lumaroot"),
            Triple("please add two boxes of $old, sorry, $final", final, "organic certified lumaroot"),
            Triple("$old, sorry, $final, two milk", final, "organic certified lumaroot"),
            Triple("$old, sorry, $final", final, "organic lumaroot"),
            Triple("1/3 organic lumaroot, sorry, $final", final, "organic certified lumaroot"),
        )
        cases.forEachIndexed { index, (instruction, primary, name) ->
            val ready = listOf(readyRow(instruction, 0, item(name, primary, 3, "count", "three")))
            val blocked = if (index == 1) listOf(blockedRow(instruction, 1, "two lumaroot")) else emptyList()
            assertRefinementReviewBlocked(review(instruction, ready = ready, blocked = blocked).prepareForReview())
        }
    }

    @Test fun commaRefinementRequiresACompleteIndependentlyGroundedPartition() {
        val final = "three organic certified lumaroot"
        val clause = "teen organic lumaroot, sorry, $final"
        listOf("$clause, one rice, one tea", "one rice, $clause, one tea", "one rice, one tea, $clause").forEach { instruction ->
            // Original row indices need not equal source order.
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("tea", "one tea", 1, "count", "one")),
                readyRow(instruction, 1, item("organic certified lumaroot", final, 3, "count", "three")),
                readyRow(instruction, 2, item("rice", "one rice", 1, "count", "one")),
            )).prepareForReview()
            assertEquals(listOf("tea", "organic certified lumaroot", "rice"), result.items.map { it.query })
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(3), Quantity.Count(1)), result.items.map { it.quantity })
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun commaRefinementDoesNotBorrowUnknownUngroundedOrCroppedPeers() {
        val final = "three organic certified lumaroot"
        val clause = "teen organic lumaroot, sorry, $final"
        val instructions = listOf("one rice, $clause, one tea", "two boxes of one rice, $clause, one tea",
            "one rice, a little mystery, $clause, one tea", "one rice, $clause, one tea plus")
        instructions.forEach { instruction ->
            val ready = listOf(
                readyRow(instruction, 0, item("rice", "one rice", 1, "count", "one")),
                readyRow(instruction, 1, item("organic certified lumaroot", final, 3, "count", "three")),
            )
            val peers = if (instruction == instructions.first()) emptyList() else listOf(
                readyRow(instruction, 2, item("tea", "one tea", 1, "count", "one")))
            assertRefinementReviewBlocked(review(instruction, ready = ready + peers).prepareForReview())
        }
        val instruction = instructions.first()
        val invalid = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("almond rice", "one rice", 1, "count", "one")),
            readyRow(instruction, 1, item("organic certified lumaroot", final, 3, "count", "three")),
            readyRow(instruction, 2, item("tea", "one tea", 1, "count", "one")),
        )).prepareForReview()
        assertRefinementReviewBlocked(invalid)
        val blockedPeer = review(instruction, ready = listOf(
            readyRow(instruction, 1, item("organic certified lumaroot", final, 3, "count", "three")),
            readyRow(instruction, 2, item("tea", "one tea", 1, "count", "one")),
        ), blocked = listOf(blockedRow(instruction, 0, "one rice"))).prepareForReview()
        assertRefinementReviewBlocked(blockedPeer)
    }

    @Test fun commaRefinementOwnerCannotCreateAReadinessCycleOrLoseQualifiers() {
        val final = "three organic certified lumaroot"
        val otherFinal = "two amber certified moongrain"
        val instruction = "teen organic lumaroot, sorry, $final, two amber moongrain, sorry, $otherFinal"
        assertRefinementReviewBlocked(review(instruction, ready = listOf(
            readyRow(instruction, 0, item("organic certified lumaroot", final, 3, "count", "three")),
            readyRow(instruction, 1, item("amber certified moongrain", otherFinal, 2, "count", "two")),
        )).prepareForReview())
        val one = "one rice, teen organic lumaroot, sorry, $final, one tea"
        listOf(item("organic lumaroot", final, 3, "count", "three"),
            item("organic certified lumaroot", final, 3, "count", "three", required = listOf("lactose_free"))).forEach { invalid ->
            assertRefinementReviewBlocked(review(one, ready = listOf(
                readyRow(one, 0, item("rice", "one rice", 1, "count", "one")),
                readyRow(one, 1, invalid),
                readyRow(one, 2, item("tea", "one tea", 1, "count", "one")),
            )).prepareForReview())
        }
    }

    @Test fun invalidRefinementOwnerCannotCoverTheCorrectionForItsSibling() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val instruction = "$old, sorry, $final; two milk"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("organic certified lumaroot", final, 3, "count", "three",
                required = listOf("lactose_free"))),
            readyRow(instruction, 1, item("milk", "two milk", 2, "count", "two")),
        )).prepareForReview()
        assertRefinementReviewBlocked(result)
    }

    @Test fun refinementUsesOriginalRowIndicesWithEarlierBlockedRows() {
        val final = "three organic certified lumaroot"
        val instruction = "one tea; teen organic lumaroot, sorry, $final"
        val result = review(instruction,
            ready = listOf(readyRow(instruction, 1, item("organic certified lumaroot", final, 3, "count", "three"))),
            blocked = listOf(blockedRow(instruction, 0, "one tea")),
        ).prepareForReview()
        assertEquals(listOf("organic certified lumaroot"), result.items.map { it.query })
        assertEquals(listOf("one tea"), result.excludedText)
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun unlocatedPeerDisablesRefinementAndRejectedOwnerLeavesCueUncovered() {
        val final = "three organic certified lumaroot"
        val instruction = "teen organic lumaroot, sorry, $final; one tea"
        val unlocated = review(instruction,
            ready = listOf(readyRow(instruction, 0, item("organic certified lumaroot", final, 3, "count", "three"))),
            blocked = listOf(GroceryIntentReviewV2BlockedRow(1, null, listOf("source_unproven"))),
        ).prepareForReview()
        assertRefinementReviewBlocked(unlocated)
        val rejected = review(instruction,
            ready = listOf(readyRow(instruction, 1, item("tea", "one tea", 1, "count", "one"))),
            blocked = listOf(blockedRow(instruction, 0, final)),
        ).prepareForReview()
        assertRefinementReviewBlocked(rejected)
    }

    @Test fun survivingRefinementStillRequiresCompleteClosingCoverage() {
        val final = "three organic certified lumaroot"
        val instruction = "teen organic lumaroot, sorry, $final; remember one tea"
        assertRefinementReviewBlocked(review(instruction,
            ready = listOf(readyRow(instruction, 0, item("organic certified lumaroot", final, 3, "count", "three"))),
        ).prepareForReview())
    }

    private fun assertRefinementReviewBlocked(result: GroceryIntentPreparation) {
        assertTrue(result.items.isEmpty())
        val gate = GroceryIntentReviewGate()
        gate.prepare(result.unresolvedText, result.excludedText, result.sharedGroupNotes, result.fulfillmentConstraints)
        assertFalse(gate.canApply())
    }

    @Test fun completeHaanBetaGenericCartIntroPreservesOriginalWordsAndLocalCounts() {
        val instruction = "Haan beta, ye saman cart mein daal do. 2 oat milk, 3 vegan bread."
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("oat milk", "2 oat milk", 2, "count", "2")),
            readyRow(instruction, 1, item("vegan bread", "3 vegan bread", 3, "count", "3",
                required = listOf("vegan"))),
        )).prepareForReview()

        assertEquals(listOf("oat milk", "vegan bread vegan"), result.items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), result.items.map { it.quantity })
        assertEquals(listOf("2 oat milk", "3 vegan bread"), result.items.map { it.rawText })
        assertTrue(result.unresolvedText.isEmpty())
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun cartIntroDoesNotHideOmittedItemRestrictionOrQuantity() {
        val omittedInstruction = "Haan beta, ye saman cart mein daal do. 2 oat milk, 3 vegan bread."
        val partial = review(omittedInstruction, ready = listOf(
            readyRow(omittedInstruction, 0, item("oat milk", "2 oat milk", 2, "count", "2")),
        )).prepareForReview()
        // The omitted numeric phrase has no independently validated owner.
        assertTrue(partial.items.isEmpty())
        assertTrue(partial.unresolvedText.any { it.contains("3 vegan bread") })

        val restrictionSource = "2 vegan oat milk"
        val restrictionInstruction = "Haan beta, ye saman cart mein daal do. $restrictionSource."
        val droppedRestriction = review(restrictionInstruction, ready = listOf(readyRow(
            restrictionInstruction, 0, item("oat milk", restrictionSource, 2, "count", "2"),
        ))).prepareForReview()
        assertTrue(droppedRestriction.items.isEmpty())
        assertTrue(droppedRestriction.excludedText.any { it.contains(restrictionSource) })

        val quantitySource = "2 oat milk"
        val quantityInstruction = "Haan beta, ye saman cart mein daal do. $quantitySource."
        val droppedQuantity = review(quantityInstruction, ready = listOf(readyRow(
            quantityInstruction, 0, item("oat milk", quantitySource),
        ))).prepareForReview()
        assertTrue(droppedQuantity.items.isEmpty())
        assertTrue((droppedQuantity.excludedText + droppedQuantity.unresolvedText)
            .any { it.contains(quantitySource) })
    }

    @Test fun backendReadySubsetDoesNotProveWholeRequestCoverage() {
        val instruction = "one rice and two milk"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("rice", "one rice", 1, "count", "one")),
        )).prepareForReview()

        assertTrue(result.items.isEmpty())
        assertTrue(result.unresolvedText.any { it.contains("two milk") })
    }

    @Test fun backendReadyInventedPositivePropertyIsHeldOnAndroid() {
        val instruction = "one rice"
        val row = readyRow(instruction, 0, item(
            "rice", instruction, 1, "count", "one", required = listOf("organic"),
        ))
        assertEquals("unproven_name_or_modifier", row.groundingIssue())

        val result = review(instruction, ready = listOf(row)).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun blockedRowDoesNotDiscardAnIndependentValidItem() {
        val instruction = "2 eggs, mystery sauce"
        val result = review(
            instruction,
            ready = listOf(readyRow(instruction, 0, item("eggs", "2 eggs", 2, "piece", "2"))),
            blocked = listOf(blockedRow(instruction, 1, "mystery sauce")),
        ).prepareForReview()

        assertEquals(listOf("eggs"), result.items.map { it.query })
        assertEquals(listOf("mystery sauce"), result.excludedText)
    }

    @Test fun allBlockedRowsProduceNoParsedItems() {
        val instruction = "mystery sauce, unknown snack"
        val result = review(
            instruction,
            blocked = listOf(
                blockedRow(instruction, 0, "mystery sauce"),
                blockedRow(instruction, 1, "unknown snack"),
            ),
        ).prepareForReview()

        assertTrue(result.items.isEmpty())
        assertEquals(listOf("mystery sauce", "unknown snack"), result.excludedText)
    }

    @Test fun inventedBrandAndRemovedDietaryRestrictionBlockOnlyTheirRows() {
        val instruction = "milk, vegan tofu, eggs"
        val rows = listOf(
            readyRow(instruction, 0, item("Amul milk", "milk")),
            readyRow(instruction, 1, item("tofu", "vegan tofu", required = emptyList())),
            readyRow(instruction, 2, item("eggs", "eggs")),
        )
        val result = review(instruction, ready = rows).prepareForReview()

        assertEquals(listOf("eggs"), result.items.map { it.query })
        assertTrue(result.excludedText.any { it.contains("milk") })
        assertTrue(result.excludedText.any { it.contains("vegan tofu") })
    }

    @Test fun terminalWalaAfterProvenNegativeDoesNotBecomePositiveIdentity() {
        listOf(
            "no palm oil wala" to listOf("palm_oil"),
            "no maida wali" to listOf("maida"),
            "no added sugar wale" to listOf("added_sugar"),
            "no chemicals wala" to listOf("chemicals"),
            "no preservatives wala" to listOf("preservatives"),
            "no artificial colours wala" to listOf("artificial_colours"),
            "no artificial flavours wala" to listOf("artificial_flavours"),
            "NO PALM OIL WALA!" to listOf("palm_oil"),
        ).forEach { (negative, excluded) ->
            val primary = "two cereal bars, $negative"
            val instruction = "Add $primary, and one soap."
            val required = if (negative.startsWith("no added sugar")) listOf("no_added_sugar") else emptyList()
            val result = review(
                instruction,
                ready = listOf(
                    readyRow(instruction, 0, item(
                        "cereal bars", primary, 2, "pack", "two", packaging = "bar", required = required, excluded = excluded,
                    )),
                    readyRow(instruction, 1, item("soap", "one soap", 1, "count", "one")),
                ),
            ).prepareForReview()

            assertEquals(negative, 2, result.items.size)
            assertEquals(Quantity.Count(2), result.items.first().quantity)
            assertTrue(result.items.first().retailPackCount)
            assertEquals(excluded.map { it.replace('_', ' ') }, result.items.first().avoidPhrases)
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun negativeParticleDoesNotHideMissingIdentityOrUnclaimedSuffixes() {
        (listOf(
            "two cereal bars, no palm oil wala" to emptyList(),
            "two BrandA cereal bars, no palm oil wala" to listOf("palm_oil"),
            "two cereal bars, with palm oil wala" to listOf("palm_oil"),
            "two cereal bars, no palm oil wala soap" to listOf("palm_oil"),
            "two cereal bars, no palm oil wala maybe" to listOf("palm_oil"),
            "two cereal bars, no palm oil wala 3" to listOf("palm_oil"),
            "two cereal bars, no palm oil\nwala" to listOf("palm_oil"),
            "two cereal bars, no palm oil wala\n" to listOf("palm_oil"),
            "two cereal bars, no palm oil wala\u00a0" to listOf("palm_oil"),
            "two cereal bars, no palm oil walı" to listOf("palm_oil"),
        ) + listOf("chemicals" to "chemicals", "preservatives" to "preservatives",
            "artificial colours" to "artificial_colours", "artificial flavours" to "artificial_flavours")
            .flatMap { (term, excluded) -> listOf(
                "two cereal bars, no $term wala" to emptyList(),
                "two cereal bars, no $term wala soap" to listOf(excluded),
            ) }).forEach { (source, excluded) ->
            val instruction = "Add $source."
            val result = review(
                instruction,
                ready = listOf(readyRow(instruction, 0, item(
                    "cereal bars", source, 2, "pack", "two", packaging = "bar", excluded = excluded,
                ))),
            ).prepareForReview()
            assertTrue("unsafe suffix must remain blocked: $source", result.items.isEmpty())
        }
    }

    @Test fun unsaltedRemainsASellerPropertyButCannotProveSaltExclusion() {
        val explicitAbsence = "one butter without salt"
        val downgraded = readyRow(explicitAbsence, 0, item("butter", explicitAbsence, 1, "count", "one",
            required = listOf("unsalted")))
        assertTrue("a seller adjective cannot replace the original exclusion",
            review(explicitAbsence, ready = listOf(downgraded)).prepareForReview().items.isEmpty())
        val source = "one unsalted butter"
        val saltExclusion = readyRow(source, 0, item(
            "unsalted butter", source, 1, "count", "one",
            required = listOf("unsalted"), excluded = listOf("salt"),
        ))
        assertEquals("unproven_exclusion", saltExclusion.groundingIssue())
        assertTrue(review(source, ready = listOf(saltExclusion)).prepareForReview().items.isEmpty())

        val noExclusion = readyRow(source, 0, item(
            "unsalted butter", source, 1, "count", "one", required = listOf("unsalted"),
        ))
        assertEquals(null, noExclusion.groundingIssue())
        val sellerProperty = review(source, ready = listOf(noExclusion)).prepareForReview().items.single()
        assertEquals(Quantity.Count(1), sellerProperty.quantity)
        assertTrue(sellerProperty.query.contains("unsalted butter"))
        assertTrue(sellerProperty.avoidPhrases.isEmpty())
    }

    @Test fun saltExclusionCannotBeInferredFromQualifiedOrDifferentAbsenceClaims() {
        val deniedSources = listOf(
            "one butter", "one plain butter", "one salted butter",
            "one almost unsalted butter", "one nearly unsalted butter", "one low unsalted butter",
            "one less unsalted butter", "one reduced unsalted butter", "one maybe unsalted butter",
            "one maybe\u00a0unsalted butter", "one ｍａｙｂｅ unsalted butter",
            "one not unsalted butter", "one unsalted butter alternative", "one unsalted butter contains salt",
            "one butter with no added salt",
        )
        deniedSources.forEach { source ->
            val name = source.removePrefix("one ")
            val row = readyRow(source, 0, item(
                name, source, 1, "count", "one",
                required = listOf("unsalted"), excluded = listOf("salt"),
            ))
            assertTrue("qualified/different absence must not prove unsalted: $source", row.groundingIssue() != null)
            assertTrue(review(source, ready = listOf(row)).prepareForReview().items.isEmpty())
        }

        val unsalted = "one unsalted butter"
        val tooBroadExclusion = readyRow(unsalted, 0, item(
            "unsalted butter", unsalted, 1, "count", "one",
            required = listOf("unsalted"), excluded = listOf("added_salt"),
        ))
        assertTrue("unsalted does not prove no-added-salt", tooBroadExclusion.groundingIssue() != null)
        assertTrue(review(unsalted, ready = listOf(tooBroadExclusion)).prepareForReview().items.isEmpty())

        val missingProperty = "one butter"
        val missing = readyRow(missingProperty, 0, item(
            "butter", missingProperty, 1, "count", "one", required = listOf("unsalted"),
        ))
        assertTrue(missing.groundingIssue() != null)
        assertTrue(review(missingProperty, ready = listOf(missing)).prepareForReview().items.isEmpty())
    }

    @Test fun cleanSupportCannotLaunderAQualifiedUnsaltedPrimary() {
        val primary = "one almost unsalted butter"
        val instruction = "$primary; unsalted butter"
        val row = readyRow(instruction, 0, item(
            "almost unsalted butter", primary, 1, "count", "one",
            required = listOf("unsalted"), excluded = listOf("salt"),
        ), listOf(GroceryIntentReviewV2SupportText("modifier", "unsalted butter")))
        assertEquals("unproven_exclusion", row.groundingIssue())
        assertTrue(review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
    }

    @Test fun croppedNegativeParticleCannotBorrowTheNextProductBoundary() {
        val primary = "two cereal bars, no palm oil wala"
        for (gap in listOf(" ", "\n", "\u00a0")) {
            val instruction = "Add $primary${gap}soap."
            val row = readyRow(instruction, 0, item(
                "cereal bars", primary, 2, "pack", "two", packaging = "bar", excluded = listOf("palm_oil"),
            ))
            assertEquals("missing_source_identity", row.groundingIssue(instruction = instruction))
            val prepared = review(instruction, ready = listOf(
                row, readyRow(instruction, 1, item("soap", "soap")),
            )).prepareForReview()
            assertTrue(prepared.excludedText.contains(primary))
            assertTrue(prepared.items.none { it.rawText == primary })
        }
    }

    @Test fun terminalNegativeParticleNeedsOriginalInstructionEvenForSupports() {
        val primary = "two cereal bars"
        val support = "cereal bars no palm oil wala"
        val draft = item("cereal bars", primary, 2, "pack", "two", packaging = "bar", excluded = listOf("palm_oil"))
        for (tail in listOf("", " soap", "\nsoap")) {
            val instruction = "$primary; $support$tail"
            val row = readyRow(instruction, 0, draft, listOf(GroceryIntentReviewV2SupportText("modifier", support)))
            assertEquals("missing_source_identity", row.groundingIssue())
            assertEquals(if (tail.isEmpty()) null else "missing_source_identity", row.groundingIssue(instruction = instruction))
        }
    }

    @Test fun sharedHeaderIsVisibleForEachOwnedItem() {
        val instruction = "Amul: milk, butter"
        val header = GroceryIntentReviewV2SupportText("header", "Amul:")
        val result = review(
            instruction,
            ready = listOf(
                readyRow(instruction, 0, item("Amul milk", "milk"), listOf(header)),
                readyRow(instruction, 1, item("Amul butter", "butter"), listOf(header)),
            ),
        ).prepareForReview()

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.rawText.contains("Shared wording: Amul:") })
        assertTrue(result.items.all { it.query.contains("Amul") })
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun separateSupportFragmentsCannotCreateNoAddedSugarAttribute() {
        val instruction = "no milk added sugar"
        val support = listOf(
            GroceryIntentReviewV2SupportText("modifier", "no"),
            GroceryIntentReviewV2SupportText("modifier", "added sugar"),
        )
        val result = review(
            instruction,
            ready = listOf(readyRow(
                instruction, 0,
                item("milk", "milk", required = listOf("no_added_sugar")),
                support,
            )),
        ).prepareForReview()

        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.single().contains("\nno\nadded sugar"))
        assertFalse(result.excludedText.single().contains("no added sugar"))
    }

    @Test fun explicitOneAndUnstatedDefaultRemainDifferentAfterConversion() {
        val instruction = "one bread, milk"
        val result = review(
            instruction,
            ready = listOf(
                readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
                readyRow(instruction, 1, item("milk", "milk")),
            ),
        ).prepareForReview()

        assertEquals(Quantity.Count(1), result.items[0].quantity)
        assertEquals(Quantity.Default, result.items[1].quantity)
    }

    @Test fun exactRetailPackMeasureIsRetainedInSearchQuery() {
        val instruction = "500 g bag of oats"
        val result = review(
            instruction,
            ready = listOf(readyRow(
                instruction, 0,
                item("oats", instruction, packValue = BigDecimal("500"), packUnit = "g", packaging = "bag", packText = "500"),
            )),
        ).prepareForReview()

        val parsed = result.items.single()
        assertTrue(parsed.query.contains("500 g"))
        assertEquals(Quantity.Default, parsed.quantity)
        assertTrue(parsed.strictMatchPhrase!!.contains("500 g"))
    }

    @Test fun literalBatteryVoltageIsNotInventedPackagingOrLostByDescriptorNormalization() {
        val source = "one BrandA alkaline battery 1.5 volt"
        val exact = item(
            name = "BrandA alkaline battery 1.5 volt",
            sourceText = source,
            quantity = 1,
            quantityUnit = "count",
            quantityText = "one",
        )
        val accepted = review(source, ready = listOf(readyRow(source, 0, exact))).prepareForReview()
        assertEquals(1, accepted.items.size)
        assertEquals(Quantity.Count(1), accepted.items.single().quantity)
        assertTrue(accepted.items.single().query.contains("1.5 volt"))
        assertTrue(accepted.excludedText.isEmpty())
        assertTrue(accepted.unresolvedText.isEmpty())

        val inventedContainer = review(source, ready = listOf(readyRow(source, 0, exact.copy(packaging = "pack")))).prepareForReview()
        assertTrue(inventedContainer.items.isEmpty())
        assertEquals(listOf(source), inventedContainer.excludedText)

        val wordNumberSource = "BrandA alkaline battery ten volt"
        val normalizedNumber = review(wordNumberSource, ready = listOf(readyRow(
            wordNumberSource, 0, item("BrandA alkaline battery 10 volt", wordNumberSource),
        ))).prepareForReview()
        assertTrue(normalizedNumber.items.isEmpty())
        assertEquals(listOf(wordNumberSource), normalizedNumber.excludedText)
    }

    @Test fun literalBrandFlavourCupGroundsButDoesNotInferYogurtCategory() {
        val source = "one BrandA strawberry cup"
        val literal = item(
            name = "BrandA strawberry cup", sourceText = source,
            quantity = 1, quantityUnit = "count", quantityText = "one", packaging = "cup",
        )
        val accepted = review(source, ready = listOf(readyRow(source, 0, literal))).prepareForReview()
        assertEquals(1, accepted.items.size)
        assertTrue(accepted.items.single().query.contains("BrandA"))
        assertTrue(accepted.items.single().query.contains("strawberry"))
        assertTrue(accepted.items.single().query.contains("cup"))

        val inferredCategory = review(source, ready = listOf(readyRow(
            source, 0, literal.copy(name = "BrandA strawberry yogurt cup"),
        ))).prepareForReview()
        assertTrue(inferredCategory.items.isEmpty())
        assertEquals(listOf(source), inferredCategory.excludedText)
    }

    @Test fun spokenContainerWordsRemainInTheirOwnProductQueries() {
        listOf(
            Triple("one BrandA tea packet", "BrandA tea", "packet"),
            Triple("one BrandA strawberry cup", "BrandA strawberry", "cup"),
            Triple("one BrandA milk bottle", "BrandA milk", "bottle"),
        ).forEach { (source, name, container) ->
            val result = review(source, ready = listOf(readyRow(
                source, 0, item(name, source, 1, "count", "one", packaging = container),
            ))).prepareForReview()
            assertEquals(source, 1, result.items.size)
            assertTrue(source, result.items.single().query.contains(container, ignoreCase = true))
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun packagingWordsCannotBeSilentlyDroppedOrChanged() {
        val bottleRequest = "one bottle ketchup"
        val literalBottle = review(bottleRequest, ready = listOf(readyRow(
            bottleRequest, 0, item("bottle ketchup", bottleRequest, 1, "count", "one"),
        ))).prepareForReview()
        assertEquals(1, literalBottle.items.size)
        assertTrue(literalBottle.items.single().query.contains("bottle", ignoreCase = true))
        assertTrue(literalBottle.unresolvedText.isEmpty())

        val droppedBottle = review(bottleRequest, ready = listOf(readyRow(
            bottleRequest, 0, item("ketchup", bottleRequest, 1, "count", "one"),
        ))).prepareForReview()
        assertTrue(droppedBottle.items.isEmpty())
        assertEquals(listOf(bottleRequest), droppedBottle.excludedText)

        val changedForm = review(bottleRequest, ready = listOf(readyRow(
            bottleRequest, 0, item("pouch ketchup", bottleRequest, 1, "count", "one"),
        ))).prepareForReview()
        assertTrue(changedForm.items.isEmpty())
        assertEquals(listOf(bottleRequest), changedForm.excludedText)

        val canRequest = "one can beans"
        val literalCan = review(canRequest, ready = listOf(readyRow(
            canRequest, 0, item("can beans", canRequest, 1, "count", "one"),
        ))).prepareForReview()
        assertEquals(1, literalCan.items.size)
        val droppedCan = review(canRequest, ready = listOf(readyRow(
            canRequest, 0, item("beans", canRequest, 1, "count", "one"),
        ))).prepareForReview()
        assertTrue(droppedCan.items.isEmpty())
        assertEquals(listOf(canRequest), droppedCan.excludedText)

        val courteousInstruction = "Can you get one bottle ketchup"
        val courteous = review(courteousInstruction, ready = listOf(readyRow(
            courteousInstruction, 0, item("bottle ketchup", courteousInstruction, 1, "count", "one"),
        ))).prepareForReview()
        assertEquals(1, courteous.items.size)
        assertTrue(courteous.items.single().query.contains("bottle", ignoreCase = true))
        assertFalse(courteous.items.single().query.contains("can you", ignoreCase = true))
        assertTrue(courteous.unresolvedText.isEmpty())
    }

    @Test fun everyStatedPackagingFormMustRemainInNameOrMetadata() {
        val forms = listOf(
            "pack" to "packs", "packet" to "packets", "bag" to "bags", "bottle" to "bottles",
            "carton" to "cartons", "tetra pack" to "tetra packs", "cup" to "cups", "can" to "cans",
            "tin" to "tins", "tub" to "tubs", "jar" to "jars", "box" to "boxes", "bar" to "bars",
            "block" to "blocks", "loaf" to "loaves", "bunch" to "bunches", "roll" to "rolls",
            "refill" to "refills", "pouch" to "pouches", "sachet" to "sachets", "tube" to "tubes",
            "spray" to "sprays", "tetrapack" to "tetrapacks",
        )
        forms.forEach { (singular, plural) ->
            listOf(singular, plural).forEach { statedForm ->
                val fragment = "one $statedForm BrandA milk"
                val source = listOf(fragment)
                val missing = intentEvidenceTokens("BrandA milk")
                assertTrue(statedForm, groceryIntentStatedPackagingMissing(source, missing))
                assertFalse(statedForm, groceryIntentStatedPackagingMissing(source, intentEvidenceTokens("$statedForm BrandA milk")))
                assertFalse(statedForm, groceryIntentStatedPackagingMissing(source, intentEvidenceTokens("BrandA milk $statedForm")))
                assertTrue(statedForm, groceryIntentStatedPackagingMissing(source, intentEvidenceTokens("different BrandA milk")))
            }
        }
    }

    @Test fun packagingGuardDoesNotJoinFragmentsOrConsumeModalCan() {
        val fragments = listOf("tetra", "pack milk")
        assertTrue(groceryIntentStatedPackagingMissing(fragments, intentEvidenceTokens("tetrapack milk")))

        val modalRequest = listOf("Can you get one can beans")
        assertTrue(groceryIntentStatedPackagingMissing(modalRequest, intentEvidenceTokens("beans")))
        assertFalse(groceryIntentStatedPackagingMissing(modalRequest, intentEvidenceTokens("can beans")))
        assertFalse(groceryIntentStatedPackagingMissing(
            listOf("Can you get ketchup"), intentEvidenceTokens("ketchup"),
        ))
    }

    @Test fun excludedTetraPackSubstitutionDoesNotCountAsAnUnownedPositiveForm() {
        val source = "BrandA milk bottle, no tetra pack substitutes"
        val preserved = review(source, ready = listOf(readyRow(
            source, 0, item("BrandA milk", source, packaging = "bottle", excluded = listOf("tetra_pack")),
        ))).prepareForReview()
        assertEquals(1, preserved.items.size)
        assertTrue(preserved.items.single().query.contains("bottle", ignoreCase = true))
        assertTrue(intentEvidenceTokens(preserved.items.single().avoidPhrases.joinToString(" ")).contains("tetrapack"))
        assertTrue(preserved.excludedText.isEmpty())

        val omittedExclusion = review(source, ready = listOf(readyRow(
            source, 0, item("BrandA milk", source, packaging = "bottle"),
        ))).prepareForReview()
        assertTrue(omittedExclusion.items.isEmpty())

        val omittedBottle = review(source, ready = listOf(readyRow(
            source, 0, item("BrandA milk", source, excluded = listOf("tetra_pack")),
        ))).prepareForReview()
        assertTrue(omittedBottle.items.isEmpty())
    }

    @Test fun explicitCupBottleAndPacketPackUnitsRemainRetailPackCounts() {
        listOf(
            Triple("1 BrandA yogurt cup 200 g", "BrandA yogurt", "cup") to ("200" to "g"),
            Triple("1 BrandA milk bottle 500 ml", "BrandA milk", "bottle") to ("500" to "ml"),
            Triple("1 BrandA tea packet 250 g", "BrandA tea", "packet") to ("250" to "g"),
        ).forEach { (case, measure) ->
            val (source, name, container) = case
            val (packValue, packUnit) = measure
            val result = review(source, ready = listOf(readyRow(source, 0, item(
                name = name,
                sourceText = source,
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "1",
                packValue = BigDecimal(packValue),
                packUnit = packUnit,
                packaging = container,
                packText = packValue,
            )))).prepareForReview()
            assertEquals(source, 1, result.items.size)
            assertEquals(Quantity.Count(1), result.items.single().quantity)
            assertTrue(source, result.items.single().retailPackCount)
            assertTrue(source, result.items.single().query.contains(container, ignoreCase = true))
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun missingPurchaseQuantityCannotSilentlyDefaultWhenNumericAmountWasStated() {
        val omittedQuantityCases = listOf(
            "2 milk" to item("milk", "2 milk"),
            "two packets milk 500 ml" to item("milk", "two packets milk 500 ml", packValue = BigDecimal("500"), packUnit = "ml", packaging = "packet", packText = "500"),
            "do doodh packets 500ml" to item("doodh", "do doodh packets 500ml", packValue = BigDecimal("500"), packUnit = "ml", packaging = "packet", packText = "500"),
            "dedh kg rice" to item("rice", "dedh kg rice"),
            "one milk" to item("milk", "one milk"),
            "½ kg rice" to item("rice", "½ kg rice"),
            "¾ kg rice" to item("rice", "¾ kg rice"),
            "٢ milk" to item("milk", "٢ milk"),
        )
        omittedQuantityCases.forEachIndexed { index, (source, candidate) ->
            val result = review(source, ready = listOf(readyRow(source, index, candidate))).prepareForReview()
            assertTrue(source, result.items.isEmpty())
            assertEquals(source, listOf(source), result.excludedText)
            assertTrue(source, result.items.none { it.quantity == Quantity.Default })
        }
    }

    @Test fun trulyUnstatedOrPackSizeOnlyQuantityMayStillUseDefault() {
        val unstated = review("milk", ready = listOf(readyRow("milk", 0, item("milk", "milk")))).prepareForReview()
        assertEquals(Quantity.Default, unstated.items.single().quantity)
        assertTrue(unstated.excludedText.isEmpty())

        // The supplied size is retained; validation does not prove whether it is a purchase total or capacity.
        val source = "milk 500 ml"
        val packSizeOnly = review(source, ready = listOf(readyRow(source, 0, item(
            name = "milk", sourceText = source, packValue = BigDecimal("500"), packUnit = "ml",
            packText = "500",
        )))).prepareForReview()
        assertEquals(Quantity.Default, packSizeOnly.items.single().quantity)
        assertTrue(packSizeOnly.items.single().query.contains("500 ml"))
        assertTrue(packSizeOnly.excludedText.isEmpty())

        val capacity = "500 ml measuring jug"
        val capacityOnly = review(capacity, ready = listOf(readyRow(capacity, 0, item(
            "measuring jug", capacity, packValue = BigDecimal("500"), packUnit = "ml", packText = "500",
        )))).prepareForReview()
        assertEquals(Quantity.Default, capacityOnly.items.single().quantity)
        assertTrue(capacityOnly.items.single().strictMatchPhrase?.contains("500 ml") == true)
        assertTrue(capacityOnly.items.single().query.contains("500 ml"))
        assertTrue(capacityOnly.excludedText.isEmpty())

        val stated = "two packets milk 500 ml"
        val fullCountAndSize = review(stated, ready = listOf(readyRow(stated, 0, item(
            name = "milk", sourceText = stated, quantity = 2, quantityUnit = "pack", quantityText = "two",
            packValue = BigDecimal("500"), packUnit = "ml", packaging = "packet", packText = "500",
        )))).prepareForReview()
        assertEquals(Quantity.Count(2), fullCountAndSize.items.single().quantity)
        assertTrue(fullCountAndSize.items.single().retailPackCount)
        assertTrue(fullCountAndSize.excludedText.isEmpty())

        val mixed = "2 milk, bread"
        val independentGoodRowSurvives = review(mixed, ready = listOf(
            readyRow(mixed, 0, item("milk", "2 milk")),
            readyRow(mixed, 1, item("bread", "bread")),
        )).prepareForReview()
        assertEquals(listOf("bread"), independentGoodRowSurvives.items.map { it.query })
        assertTrue(independentGoodRowSurvives.excludedText.contains("2 milk"))
    }

    @Test fun unstatedQuantityWithRetainedPhysicalPackSizeAllowsOnlyOneExactSinglePackCandidate() {
        val cases = listOf(
            listOf("500 g masoor dal", "masoor dal", "Masoor Dal", "500 g", "1 kg"),
            listOf("250 g semolina", "semolina", "Semolina", "250 g", "500 g"),
            listOf("400 g paneer", "paneer", "Paneer", "400 g", "200 g"),
            listOf("100 g coffee", "coffee", "Coffee", "100 g", "250 g"),
        )
        for ((source, name, label, size, otherSize) in cases) {
            val amount = source.substringBefore(' ').toBigDecimal()
            val unit = source.substringAfter(' ').substringBefore(' ')
            val prepared = review(source, ready = listOf(readyRow(source, 0, item(
                name, source, packValue = amount, packUnit = unit, packText = amount.toPlainString(),
            )))).prepareForReview()
            val parsed = prepared.items.single()
            assertEquals(source, Quantity.Default, parsed.quantity)
            assertTrue(source, parsed.strictMatchPhrase?.contains(size) == true)
            assertTrue(source, parsed.query.contains(size))
            assertTrue(source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())

            val displayOnly = RecommendationCandidate("$name-display-only", "$label $size")
            val exact = boundPhysicalSizeCandidate("$name-exact", "$label $size", size)
            val failClosed = listOf(
                displayOnly,
                RecommendationCandidate("$name-wrong-size", "$label $otherSize"),
                RecommendationCandidate("$name-unknown-size", label),
                RecommendationCandidate("$name-multipack", "$label Pack of 2 $size"),
                RecommendationCandidate("$name-bonus", "Buy 1 Get 1 Free $label $size"),
            )
            // The production flow binds quantity provenance to actual catalogue
            // candidates before any cart count may be selected.
            assertFalse(source, isSwiggyCandidateCountCompatible(parsed, exact))
            val assessed = assessSwiggyIntentQuantity(parsed,
                SwiggyMcpClient.Recommendations(candidates = listOf(exact) + failClosed))
            assertTrue(source, isSwiggyCandidateAllowed(assessed, exact))
            assertTrue(source, isSwiggyCandidateCountCompatible(assessed, exact))
            assertEquals(source, 1, swiggyRequestedCartQuantity(assessed, exact))
            for (candidate in failClosed) {
                assertFalse("$source -> ${candidate.label}: candidate identity/pack must not be accepted",
                    isSwiggyCandidateAllowed(assessed, candidate) &&
                        isSwiggyCandidateCountCompatible(assessed, candidate))
            }
        }
    }

    @Test fun syntheticGeminiA2RetainedRiceSizeAllowsOnlyExactSinglePackWithoutInventingPurchaseWeight() {
        val instruction = "500 g rice; two soaps if available; one cereal if the no-added-sugar kind is available"
        val riceSource = "500 g rice"
        val soapSource = "two soaps if available"
        val cerealSource = "one cereal if the no-added-sugar kind is available"

        // Mirrors the synthetic Gemini A2 row: no purchase amount was supplied;
        // 500 g is retained only as unit_size and the amount role remains unresolved.
        val rice = item(
            name = "rice", sourceText = riceSource,
            packValue = BigDecimal("500"), packUnit = "g", packText = "500",
        )
        val soap = item("soaps", soapSource, quantity = 2, quantityUnit = "count", quantityText = "two")
        val cereal = item("cereal", cerealSource, quantity = 1, quantityUnit = "count", quantityText = "one",
            required = listOf("no_added_sugar"))
        val draft = review(instruction, ready = listOf(
            readyRow(instruction, 0, rice).copy(amountRoleStatus = "unresolved"),
            readyRow(instruction, 1, soap).copy(amountRoleStatus = "unresolved"),
            readyRow(instruction, 2, cereal).copy(amountRoleStatus = "unresolved"),
        ))
        val prepared = draft.prepareForReview()
        val parsed = prepared.items.single { it.query.contains("rice", ignoreCase = true) }

        assertEquals(riceSource, rice.sourceText)
        assertEquals(riceSource, parsed.rawText)
        assertEquals(Quantity.Default, parsed.quantity)
        assertTrue(parsed.strictMatchPhrase?.contains("500 g", ignoreCase = true) == true)
        assertTrue(parsed.query.contains("500 g", ignoreCase = true))

        val displayOnly = RecommendationCandidate("rice-display-only-500g", "Rice 500 g")
        val exact = boundPhysicalSizeCandidate("rice-exact-500g", "Rice 500 g", "500 g")
        val rejected = listOf(
            RecommendationCandidate("rice-250g", "Rice 250 g"),
            RecommendationCandidate("rice-1kg", "Rice 1 kg"),
            RecommendationCandidate("rice-unknown-size", "Rice"),
            RecommendationCandidate("rice-multipack", "Rice Pack of 2 500 g"),
        )
        assertFalse(isSwiggyCandidateCountCompatible(parsed, displayOnly))
        val displayOnlyAssessment = assessSwiggyIntentQuantity(parsed,
            SwiggyMcpClient.Recommendations(candidates = listOf(displayOnly)))
        assertFalse(isSwiggyCandidateAllowed(displayOnlyAssessment, displayOnly) &&
            isSwiggyCandidateCountCompatible(displayOnlyAssessment, displayOnly))
        assertFalse(isSwiggyCandidateCountCompatible(parsed, exact))
        val assessed = assessSwiggyIntentQuantity(parsed,
            SwiggyMcpClient.Recommendations(candidates = listOf(exact) + rejected))
        assertTrue(isSwiggyCandidateAllowed(assessed, exact))
        assertTrue(isSwiggyCandidateCountCompatible(assessed, exact))
        assertEquals(1, swiggyRequestedCartQuantity(assessed, exact))
        rejected.forEach { candidate ->
            assertFalse("${candidate.label} must not be an allowed/count-compatible catalogue match",
                isSwiggyCandidateAllowed(assessed, candidate) &&
                    isSwiggyCandidateCountCompatible(assessed, candidate))
        }
    }

    @Test fun sourceBoundAadhaKejiUnitSizeAllowsOnlyOneExact500gCataloguePack() {
        for (unit in listOf("kg", "keji", "KEJI")) {
            val source = "aadha $unit rice"
            val rice = item(
                name = "rice", sourceText = source,
                packValue = BigDecimal("0.5"), packUnit = "kg", packText = "aadha",
            )
            val draft = review(source, ready = listOf(
                readyRow(source, 0, rice).copy(amountRoleStatus = "unresolved"),
            ))
            val parsed = draft.prepareForReview().items.single()

            assertEquals(source, "unresolved", draft.readyRows.single().amountRoleStatus)
            assertEquals(source, source, parsed.rawText)
            assertEquals(source, Quantity.Default, parsed.quantity)
            assertTrue(source, parsed.strictMatchPhrase?.contains("500 g", ignoreCase = true) == true)
            assertTrue(source, parsed.query.contains("500 g", ignoreCase = true))

            for ((id, label, rawSize) in listOf(
                Triple("rice-exact-500g", "Rice 500 g", "500 g"),
                Triple("rice-exact-500g-compact", "Rice 500g", "500g"),
                Triple("rice-exact-half-kg", "Rice 0.5kg", "0.5kg"),
            )) {
                val displayOnly = RecommendationCandidate("$id-display-only", label)
                val displayOnlyAssessment = assessSwiggyIntentQuantity(
                    parsed, SwiggyMcpClient.Recommendations(candidates = listOf(displayOnly)),
                )
                assertFalse("$source -> $label display-only offer",
                    isSwiggyCandidateAllowed(displayOnlyAssessment, displayOnly) &&
                        isSwiggyCandidateCountCompatible(displayOnlyAssessment, displayOnly))
                val exact = boundPhysicalSizeCandidate(id, label, rawSize)
                val assessed = assessSwiggyIntentQuantity(
                    parsed, SwiggyMcpClient.Recommendations(candidates = listOf(exact)),
                )
                assertTrue("$source -> $label", isSwiggyCandidateAllowed(assessed, exact))
                assertTrue("$source -> $label", isSwiggyCandidateCountCompatible(assessed, exact))
                assertEquals("$source -> $label", 1, swiggyRequestedCartQuantity(assessed, exact))
            }

            val rejected = listOf(
                RecommendationCandidate("rice-250g", "Rice 250 g"),
                RecommendationCandidate("rice-1kg", "Rice 1 kg"),
                RecommendationCandidate("rice-unknown-size", "Rice"),
                RecommendationCandidate("rice-double-prefix", "Rice 2 x 500 g"),
                RecommendationCandidate("rice-double-500g", "Rice 500 g x 2"),
                RecommendationCandidate("rice-double-500gx2", "Rice 500 g x2"),
                RecommendationCandidate("rice-pack-of-two", "Rice Pack of 2 500 g"),
                RecommendationCandidate("rice-mixed-sizes", "Rice 500 g and 1 kg"),
            )
            rejected.forEach { candidate ->
                val assessed = assessSwiggyIntentQuantity(
                    parsed, SwiggyMcpClient.Recommendations(candidates = listOf(candidate)),
                )
                assertFalse("$source -> ${candidate.label} must not be purchasable",
                    isSwiggyCandidateAllowed(assessed, candidate) &&
                        isSwiggyCandidateCountCompatible(assessed, candidate))
            }
        }
    }

    @Test fun closedBarePhysicalAmountGrammarKeepsExactWeightAndVolumeTotalsReviewable() {
        val cases = listOf(
            Triple("2 kg rice", "rice", Quantity.Weight(2000)),
            Triple("rice 2kg", "rice", Quantity.Weight(2000)),
            Triple("500ml milk", "milk", Quantity.Volume(500)),
            Triple("milk 500 ml", "milk", Quantity.Volume(500)),
            Triple("2 g paneer", "paneer", Quantity.Weight(2)),
            Triple("1 l दूध", "दूध", Quantity.Volume(1000)),
        )
        for ((source, name, expected) in cases) {
            val numericText = Regex("[0-9]+(?:\\.[0-9]+)?|dedh", RegexOption.IGNORE_CASE)
                .find(source)!!.value
            val numericValue = when (numericText.lowercase()) {
                "dedh" -> BigDecimal("1.5")
                else -> BigDecimal(numericText)
            }
            val unit = when {
                source.contains("kg", ignoreCase = true) -> "kg"
                source.contains("ml", ignoreCase = true) -> "ml"
                source.contains(Regex("(?i)\\bl\\b|\\blitre\\b|\\bliter\\b")) -> "l"
                else -> "g"
            }
            val row = readyRow(source, 0, item(name, source).copy(
                quantity = numericValue,
                quantityUnit = unit,
                quantityText = numericText,
            ))
            val prepared = review(source, ready = listOf(row)).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertEquals(source, expected, prepared.items.single().quantity)
            assertEquals(source, source, prepared.items.single().rawText)
            assertTrue(source, prepared.excludedText.isEmpty())
            assertTrue(source, prepared.unresolvedText.isEmpty())
        }

        val fractional = "dedh kg sugar"
        val fractionalRow = readyRow(fractional, 0, item("sugar", fractional).copy(
            quantity = BigDecimal("1.5"), quantityUnit = "kg", quantityText = "dedh",
        ))
        val fractionalPrepared = review(fractional, ready = listOf(fractionalRow)).prepareForReview()
        assertEquals(Quantity.Weight(1500), fractionalPrepared.items.single().quantity)
        assertTrue(fractionalPrepared.excludedText.isEmpty())
    }

    @Test fun explicitPacketEachGroupKeepsMemberPackSizesAndCounts() {
        val eachInstruction = "milk 500 ml, sugar 1 kg, one packet each"
        val milk = "milk 500 ml"
        val sugar = "sugar 1 kg"
        val eachGroup = GroceryIntentReviewV2Group(
            status = "structurally_verified",
            amountStatus = "each_verified",
            source = quote(eachInstruction, eachInstruction),
            header = null,
            memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal.ONE, "pack", "each"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal.ONE, "pack", "each"),
            ),
            packaging = null, // Headless each supplies a count, not a shared form heading.
            total = null,
            each = GroceryIntentReviewV2GroupAmount(quote(eachInstruction, "one packet each"), "one", BigDecimal.ONE, "pack"),
        )
        val eachPrepared = review(eachInstruction, ready = listOf(
            readyRow(eachInstruction, 0, item("milk", milk, packValue = BigDecimal("500"), packUnit = "ml", packText = "500")),
            readyRow(eachInstruction, 1, item("sugar", sugar, packValue = BigDecimal("1"), packUnit = "kg", packText = "1")),
        ), groups = listOf(eachGroup)).prepareForReview()
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1)), eachPrepared.items.map { it.quantity })
        assertTrue(eachPrepared.items.all { it.retailPackCount })
        assertTrue(eachPrepared.items[0].query.contains("500 ml"))
        assertTrue(eachPrepared.items[1].query.contains("1000 g"))
        assertTrue(eachPrepared.excludedText.isEmpty())
        assertTrue(eachPrepared.unresolvedText.isEmpty())
    }

    @Test fun physicalTotalAndPackAmountsNeedSourceBoundTotalMarker() {
        val ambiguousCases = listOf(
            "one L Oreal shampoo bottle 200 ml" to "L Oreal shampoo",
            "one L Oreal shampoo bottle 200 ml" to "Oreal shampoo",
            "one litre shampoo bottle 200 ml" to "shampoo",
        )
        ambiguousCases.forEachIndexed { index, (source, name) ->
            val prepared = review(source, ready = listOf(readyRow(source, 0, item(
                name, source, quantity = 1, quantityUnit = "l", quantityText = "one",
                packValue = BigDecimal("200"), packUnit = "ml", packaging = "bottle", packText = "200",
            )))).prepareForReview()
            assertTrue(source, prepared.items.isEmpty())
            assertTrue(source, (prepared.unresolvedText + prepared.excludedText).any { it.contains(source) })
        }
    }

    @Test fun equalAndExplicitTotalAmountsKeepTheirIndependentRoles() {
        val cases = listOf(
            listOf("1 litre milk 1000 ml pack", "milk", "pack", "1", "l", "1000", "ml"),
            listOf("total 2 litre milk, 500 ml packets", "milk", "packet", "2", "l", "500", "ml"),
            listOf("kul 2 kg rice, 500 g packets", "rice", "packet", "2", "kg", "500", "g"),
        )
        cases.forEachIndexed { index, fields ->
            val source = fields[0]
            val name = fields[1]
            val packaging = fields[2]
            val quantityText = fields[3]
            val quantityUnit = fields[4]
            val packText = fields[5]
            val packUnit = fields[6]
            val quantity = quantityText.toInt()
            val prepared = review(source, ready = listOf(readyRow(source, 0, item(
                name, source, quantity = quantity, quantityUnit = quantityUnit, quantityText = quantityText,
                packValue = BigDecimal(packText), packUnit = packUnit, packaging = packaging, packText = packText,
            )))).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertTrue(source, prepared.unresolvedText.isEmpty())
            assertEquals(source, if (quantityUnit == "kg") Quantity.Weight(quantity * 1000) else Quantity.Volume(quantity * 1000),
                prepared.items.single().quantity)
            assertTrue(source, prepared.items.single().query.contains("$packText $packUnit"))
        }
    }

    @Test fun totalMarkerCannotBeStolenAsProductNameButRealTotalCareBrandSurvives() {
        val source = "total 1 l Total Care shampoo, 200 ml bottles"
        val valid = review(source, ready = listOf(readyRow(source, 0, item(
            "Total Care shampoo", source, quantity = 1, quantityUnit = "l", quantityText = "1",
            packValue = BigDecimal("200"), packUnit = "ml", packaging = "bottle", packText = "200",
        )))).prepareForReview()
        assertTrue(valid.items.single().query.contains("Total Care shampoo", ignoreCase = true))
        assertEquals(Quantity.Volume(1000), valid.items.single().quantity)

        val markerOnly = "total 1 l shampoo, 200 ml bottles"
        val forged = review(markerOnly, ready = listOf(readyRow(markerOnly, 0, item(
            "Total shampoo", markerOnly, quantity = 1, quantityUnit = "l", quantityText = "1",
            packValue = BigDecimal("200"), packUnit = "ml", packaging = "bottle", packText = "200",
        )))).prepareForReview()
        assertTrue(forged.items.isEmpty())
        assertTrue((forged.unresolvedText + forged.excludedText).any { it.contains(markerOnly) })
    }

    @Test fun totalSuffixMustAttachToQuantityAndEqualPackTotalCareNameIsPreserved() {
        val suffixTotal = "1 litre in total milk, 500 ml packets"
        val accepted = review(suffixTotal, ready = listOf(readyRow(suffixTotal, 0, item(
            "milk", suffixTotal, quantity = 1, quantityUnit = "l", quantityText = "1",
            packValue = BigDecimal("500"), packUnit = "ml", packaging = "packet", packText = "500",
        )))).prepareForReview()
        assertEquals(1, accepted.items.size)
        assertEquals(Quantity.Volume(1000), accepted.items.single().quantity)
        assertTrue(accepted.items.single().query.contains("500 ml"))

        val packSuffixOnly = "1 litre milk, 500 ml packets in total"
        val ambiguous = review(packSuffixOnly, ready = listOf(readyRow(packSuffixOnly, 0, item(
            "milk", packSuffixOnly, quantity = 1, quantityUnit = "l", quantityText = "1",
            packValue = BigDecimal("500"), packUnit = "ml", packaging = "packet", packText = "500",
        )))).prepareForReview()
        assertTrue(ambiguous.items.isEmpty())
        assertTrue((ambiguous.unresolvedText + ambiguous.excludedText).any { it.contains(packSuffixOnly) })

        val totalCare = "total 1 l Total Care milk, 1000 ml pack"
        val exactEqualPack = review(totalCare, ready = listOf(readyRow(totalCare, 0, item(
            "Total Care milk", totalCare, quantity = 1, quantityUnit = "l", quantityText = "1",
            packValue = BigDecimal("1000"), packUnit = "ml", packaging = "pack", packText = "1000",
        )))).prepareForReview()
        assertEquals(1, exactEqualPack.items.size)
        assertTrue(exactEqualPack.items.single().query.contains("Total Care milk", ignoreCase = true))
        assertEquals(Quantity.Volume(1000), exactEqualPack.items.single().quantity)

    }

    @Test fun eachPhysicalGroupCannotOverrideAConflictingLocalPackRole() {
        val instruction = "milk 500 ml bottle, curd, 2 l each"
        val milkSource = "milk 500 ml bottle"
        val curdSource = "curd"
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified",
            amountStatus = "each_verified",
            source = quote(instruction, instruction),
            header = null,
            memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal("2"), "l", "each"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal("2"), "l", "each"),
            ),
            packaging = null,
            total = null,
            each = GroceryIntentReviewV2GroupAmount(quote(instruction, "2 l each"), "2", BigDecimal("2"), "l"),
        )
        val prepared = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("milk", milkSource, packValue = BigDecimal("500"),
                packUnit = "ml", packaging = "bottle", packText = "500")),
            readyRow(instruction, 1, item("curd", curdSource)),
        ), groups = listOf(group)).prepareForReview()

        assertTrue(prepared.items.isEmpty())
        assertTrue((prepared.unresolvedText + prepared.excludedText).any { it.contains(instruction) || it.contains(milkSource) })
    }

    @Test fun literalVoltageDescriptorDoesNotInventAPurchaseCount() {
        val source = "BrandA alkaline battery 1.5 volts"
        val result = review(source, ready = listOf(readyRow(
            source, 0, item("BrandA alkaline battery 1.5 volts", source),
        ))).prepareForReview()
        assertEquals(listOf(source), result.items.map { it.query })
        assertEquals(Quantity.Default, result.items.single().quantity)
        assertEquals(com.example.beta.automation.IntentQuantityOrigin.UNSTATED,
            result.items.single().intentQuantityProvenance?.origin)
        assertTrue(result.excludedText.isEmpty())
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun numericSupportingModifierOrHeaderCannotBeHiddenByNullQuantity() {
        val cases = listOf(
            Triple("BrandA milk; I need 2 of the milk", "BrandA milk", GroceryIntentReviewV2SupportText("modifier", "I need 2 of the milk")),
            Triple("BrandA milk; the milk should be 2 packets", "BrandA milk", GroceryIntentReviewV2SupportText("modifier", "the milk should be 2 packets")),
            Triple("BrandA milk; please buy 2 of the milk", "BrandA milk", GroceryIntentReviewV2SupportText("modifier", "please buy 2 of the milk")),
            Triple("BrandA milk; we need 2 packets of milk", "BrandA milk", GroceryIntentReviewV2SupportText("modifier", "we need 2 packets of milk")),
            Triple("BrandA milk; 2 packets for the milk", "BrandA milk", GroceryIntentReviewV2SupportText("modifier", "2 packets for the milk")),
            Triple("BrandA milk; the milk should be 2 packets", "BrandA milk", GroceryIntentReviewV2SupportText("header", "the milk should be 2 packets")),
        )
        cases.forEachIndexed { index, (instruction, primary, support) ->
            val result = review(instruction, ready = listOf(readyRow(
                instruction, index, item("BrandA milk", primary), listOf(support),
            ))).prepareForReview()
            assertTrue(instruction, result.items.isEmpty())
            assertTrue(instruction, result.excludedText.isNotEmpty())
            assertTrue(instruction, result.items.none { it.quantity == Quantity.Default || it.quantity == Quantity.Count(1) })
        }

        val packPrimary = "milk 500 ml"
        val packInstruction = "$packPrimary; 2 milk"
        val packWithCountSupport = review(packInstruction, ready = listOf(readyRow(
            packInstruction, 0,
            item("milk", packPrimary, packValue = BigDecimal("500"), packUnit = "ml", packText = "500"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "2 milk")),
        ))).prepareForReview()
        assertTrue(packWithCountSupport.items.isEmpty())
        assertTrue(packWithCountSupport.excludedText.isNotEmpty())
    }

    @Test fun numericPiecePackForgeryCannotMaskAnOmittedPurchaseCount() {
        val source = "2 milk"
        val forgedPack = item(
            name = "milk", sourceText = source, quantity = null,
            packValue = BigDecimal("2"), packUnit = "piece", packText = "2",
        )
        val result = review(source, ready = listOf(readyRow(source, 0, forgedPack))).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertEquals(listOf(source), result.excludedText)
    }

    @Test fun numberFreeSupportedModifierCanStillUseDefaultQuantity() {
        val instruction = "organic milk"
        val result = review(instruction, ready = listOf(readyRow(
            instruction, 0, item("milk", "milk", required = listOf("organic")),
            listOf(GroceryIntentReviewV2SupportText("modifier", "organic")),
        ))).prepareForReview()
        assertEquals(1, result.items.size)
        assertEquals(Quantity.Default, result.items.single().quantity)
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun terminalCountOnlyEmphasisPreservesTheExactLocalCountAndNeighbor() {
        for (count in 1..20) {
            val amountText = if (count == 1) "one" else count.toString()
            val milkName = "BrandA low-fat milk"
            val milkSource = "$milkName, $amountText only"
            val instruction = "$milkSource; two soaps"
            val milk = item(milkName, milkSource, count, "count", amountText)
            val prepared = review(instruction, ready = listOf(
                readyRow(instruction, 0, milk),
                readyRow(instruction, 1, item("soaps", "two soaps", 2, "count", "two")),
            )).prepareForReview()

            assertEquals("count=$count", 2, prepared.items.size)
            val milkResult = prepared.items.first()
            assertEquals("count=$count", Quantity.Count(count), milkResult.quantity)
            assertEquals("count=$count", milkSource, milkResult.rawText)
            assertTrue("count=$count", milkResult.query.contains(milkName))
            assertFalse("count=$count only is emphasis, not identity", milkResult.query.contains("only", ignoreCase = true))
            assertTrue("count=$count literal property", milkResult.query.contains("low-fat"))
            assertEquals("count=$count peer", Quantity.Count(2), prepared.items.last().quantity)
        }
    }

    @Test fun terminalCountOnlyEmphasisFailsClosedWithoutEveryLocalCountProof() {
        val source = "BrandA milk, one only"
        fun assertNotSilentlyStripped(instruction: String, rows: List<GroceryIntentReviewV2ReadyRow>,
            groups: List<GroceryIntentReviewV2Group> = emptyList()) {
            val prepared = review(instruction, ready = rows, groups = groups).prepareForReview()
            assertTrue(instruction, prepared.items.none {
                it.rawText.contains("BrandA milk", ignoreCase = true) &&
                    !it.query.contains("only", ignoreCase = true)
            })
        }

        val malformedAmounts = listOf(
            item("BrandA milk", source, 1, "count", null),
            item("BrandA milk", source, null, "unspecified", null),
            item("BrandA milk", source, 2, "count", "one"),
            item("BrandA milk", "BrandA milk, couple only", 1, "count", "couple"),
            item("BrandA milk", "BrandA milk, only"),
        )
        for (candidate in malformedAmounts) {
            val instruction = candidate.sourceText
            assertTrue(instruction, review(instruction, ready = listOf(readyRow(instruction, 0, candidate)))
                .prepareForReview().items.isEmpty())
        }

        val nonCountUnit = item("BrandA milk", source, 1, "pack", "one")
        assertNotSilentlyStripped(source, listOf(readyRow(source, 0, nonCountUnit)))

        val sizedSource = "BrandA 500 ml milk, one only"
        val sizedItem = item("BrandA 500 ml milk", sizedSource, 1, "count", "one",
            packValue = BigDecimal("500"), packUnit = "ml", packText = "500")
        assertNotSilentlyStripped(sizedSource, listOf(readyRow(sizedSource, 0, sizedItem)))

        val supportInstruction = "Note: $source"
        val supported = readyRow(supportInstruction, 0, item("BrandA milk", source, 1, "count", "one"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "Note")))
        assertNotSilentlyStripped(supportInstruction, listOf(supported))

        val groupInstruction = "$source; two soaps"
        val groupRows = listOf(
            readyRow(groupInstruction, 0, item("BrandA milk", source, 1, "count", "one")),
            readyRow(groupInstruction, 1, item("soaps", "two soaps", 2, "count", "two")),
        )
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified", amountStatus = "local_amounts_verified",
            source = quote(groupInstruction, groupInstruction), header = null, memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal.ONE, "count", "local"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal("2"), "count", "local"),
            ), packaging = null, total = null, each = null,
        )
        assertNotSilentlyStripped(groupInstruction, groupRows, listOf(group))

        val correctionInstruction = "$source; make it two"
        val correction = GroceryIntentReviewV2Correction(
            kind = "quantity", source = quote(correctionInstruction, "make it two"),
            newAmount = quote(correctionInstruction, "two"), oldValue = BigDecimal.ONE, oldUnit = "count",
            newValue = BigDecimal("2"), newUnit = "count",
        )
        val corrected = readyRow(correctionInstruction, 0, item("BrandA milk", source, 1, "count", "one"))
            .copy(corrections = listOf(correction))
        assertNotSilentlyStripped(correctionInstruction, listOf(corrected))
    }

    @Test fun terminalCountOnlyEmphasisCannotConsumeUnquotedContinuationOrOtherCorrections() {
        val source = "BrandA milk, one only"
        for (continuation in listOf(", biscuits", " biscuits")) {
            val instruction = "$source$continuation; two soaps"
            val rows = listOf(
                readyRow(instruction, 0, item("BrandA milk", source, 1, "count", "one")),
                readyRow(instruction, 1, item("soaps", "two soaps", 2, "count", "two")),
            )
            assertTrue(continuation, review(instruction, ready = rows).prepareForReview().items.isEmpty())
        }

        for (tail in listOf("organic", "if available", "tomorrow")) {
            val milkSource = "$source $tail"
            val instruction = "$milkSource; two soaps"
            val rows = listOf(
                readyRow(instruction, 0, item("BrandA milk", milkSource, 1, "count", "one")),
                readyRow(instruction, 1, item("soaps", "two soaps", 2, "count", "two")),
            )
            assertTrue(tail, review(instruction, ready = rows).prepareForReview().items.isEmpty())
        }

        for (tail in listOf("no", "sorry")) {
            val milkSource = "BrandA milk, one $tail"
            val instruction = "$milkSource; two soaps"
            val rows = listOf(
                readyRow(instruction, 0, item("BrandA milk", milkSource, 1, "count", "one")),
                readyRow(instruction, 1, item("soaps", "two soaps", 2, "count", "two")),
            )
            assertTrue(tail, review(instruction, ready = rows).prepareForReview().items.isEmpty())
        }

        val missingComma = "BrandA milk one only"
        assertTrue(review(missingComma, ready = listOf(readyRow(
            missingComma, 0, item("BrandA milk", missingComma, 1, "count", "one"),
        ))).prepareForReview().items.isEmpty())

        for (unsafePrefix in listOf(
            "BrandA milk and sugar", "BrandA milk with sugar", "BrandA milk containing sugar",
            "buy BrandA milk", "send BrandA milk", "BrandA 500 ml milk",
        )) {
            val unsafeSource = "$unsafePrefix, one only"
            assertTrue(unsafeSource, review(unsafeSource, ready = listOf(readyRow(
                unsafeSource, 0, item("BrandA milk", unsafeSource, 1, "count", "one"),
            ))).prepareForReview().items.isEmpty())
        }

        val another = "another BrandA milk, two only"
        assertTrue(review(another, ready = listOf(readyRow(
            another, 0, item("BrandA milk", another, 2, "count", "two"),
        ))).prepareForReview().items.isEmpty())
    }

    @Test fun explicitAnotherOnePreservesIncrementModeThroughLocalQuantityProof() {
        val primary = "another BrandA low-fat milk, one only"
        val source = "$primary; two soaps"
        val result = review(source, ready = listOf(
            readyRow(source, 0, item("BrandA low-fat milk", primary, 1, "count", "one")),
            readyRow(source, 1, item("soaps", "two soaps", 2, "count", "two")),
        )).prepareForReview()
        assertEquals(2, result.items.size)
        val more = result.items.first()
        assertEquals(primary, more.rawText)
        assertEquals(Quantity.Count(1), more.quantity)
        assertEquals(IntentCartQuantityMode.INCREMENT, more.cartQuantityMode)
        assertEquals(IntentCartQuantityMode.INCREMENT, more.intentQuantityProvenance?.cartQuantityMode)
        assertEquals(IntentCartQuantityMode.TARGET, result.items.last().cartQuantityMode)
        val recommendations = SwiggyMcpClient.Recommendations(emptyList())
        assertEquals("quantity_provenance_changed", swiggyIntentQuantityIssue(
            more.copy(cartQuantityMode = IntentCartQuantityMode.TARGET), recommendations))
        assertEquals("quantity_provenance_missing", swiggyIntentQuantityIssue(
            more.copy(requiresIntentQuantityProof = false), recommendations))
        val candidate = RecommendationCandidate("milk", "BrandA low-fat milk", subtitle = "500 ml", skuId = "milk-sku")
        val checked = assessSwiggyIntentQuantity(more, SwiggyMcpClient.Recommendations(listOf(candidate)))
        assertUnqualifiedCountNeedsReview(checked, candidate, 1)
        val requested = swiggyPreviewItemFromParsedItem(candidate, checked)
        assertEquals(IntentCartQuantityMode.INCREMENT, requested.quantityMode)
        assertEquals(1, requested.quantity)
        assertEquals("increment", SwiggyMcpClient.requestedItemPayload(requested)["quantityMode"])
        val plan = SwiggyMcpClient.CartPlan(listOf(SwiggyMcpClient.CartPlanChange(
            "milk", "increase", "BrandA low-fat milk", 2, 3,
        )))
        assertTrue(isSafeSwiggyCartPlan(plan, listOf(requested)))
        assertFalse(isSafeSwiggyCartPlan(plan.copy(changes = listOf(plan.changes.single().copy(toQuantity = 1))),
            listOf(requested)))
        val ordinarySource = "BrandA low-fat milk, one only"
        val ordinary = review(ordinarySource, ready = listOf(readyRow(ordinarySource, 0,
            item("BrandA low-fat milk", ordinarySource, 1, "count", "one")))).prepareForReview().items.single()
        val forged = ordinary.copy(cartQuantityMode = IntentCartQuantityMode.INCREMENT,
            intentQuantityProvenance = ordinary.intentQuantityProvenance!!.copy(cartQuantityMode = IntentCartQuantityMode.INCREMENT))
        assertEquals("quantity_operation_unproven", swiggyIntentQuantityIssue(forged, recommendations))
        assertFalse(swiggyIntentQuantityCandidateChecked(assessSwiggyIntentQuantity(forged,
            SwiggyMcpClient.Recommendations(listOf(candidate))), candidate))
        val downgraded = more.copy(cartQuantityMode = IntentCartQuantityMode.TARGET,
            intentQuantityProvenance = more.intentQuantityProvenance!!.copy(cartQuantityMode = IntentCartQuantityMode.TARGET))
        assertEquals("quantity_operation_unproven", swiggyIntentQuantityIssue(downgraded, recommendations))
    }

    @Test fun explicitAnotherOneStaysBoundedAcrossFifteenAndTwentyItemLists() {
        val names = listOf(
            "rice", "soap", "bread", "eggs", "tea", "coffee", "apples", "oranges", "onions", "potatoes",
            "tomatoes", "biscuits", "butter", "cheese", "yogurt", "oats", "flour", "lentils", "juice", "cereal",
        )
        for (size in listOf(15, 20)) for (incrementIndex in listOf(0, size / 2, size - 1)) {
            val queryNames = names.take(size).toMutableList()
            queryNames[incrementIndex] = "BrandA low-fat milk"
            val sources = queryNames.mapIndexed { index, name ->
                if (index == incrementIndex) "another $name, one only" else "one $name"
            }
            val instruction = sources.joinToString("; ")
            val prepared = review(instruction, ready = sources.mapIndexed { index, source ->
                val name = queryNames[index]
                val countText = "one"
                readyRow(instruction, index, item(name, source, 1, "count", countText))
            }).prepareForReview()

            assertEquals("size=$size index=$incrementIndex", size, prepared.items.size)
            assertEquals("size=$size index=$incrementIndex", sources, prepared.items.map { it.rawText })
            assertEquals("size=$size index=$incrementIndex", queryNames, prepared.items.map { it.query })
            assertTrue(prepared.items.all { it.quantity == Quantity.Count(1) })
            val expectedModes = queryNames.indices.map { index ->
                if (index == incrementIndex) IntentCartQuantityMode.INCREMENT else IntentCartQuantityMode.TARGET
            }
            assertEquals(expectedModes, prepared.items.map { it.cartQuantityMode })
            assertEquals(1, prepared.items.count { it.cartQuantityMode == IntentCartQuantityMode.INCREMENT })
            prepared.items.forEachIndexed { index, resolved ->
                val proof = resolved.intentQuantityProvenance
                assertEquals(sources[index], proof?.primaryText)
                assertEquals(sources[index], proof?.local?.source)
                assertEquals(expectedModes[index], proof?.cartQuantityMode)
            }

            val increment = prepared.items[incrementIndex]
            val candidate = RecommendationCandidate("increment-$size-$incrementIndex", "BrandA low-fat milk", subtitle = "500 ml")
            val recommendations = SwiggyMcpClient.Recommendations(listOf(candidate))
            assertEquals(null, swiggyIntentQuantityIssue(increment, recommendations))
            val checked = assessSwiggyIntentQuantity(increment, recommendations)
            assertUnqualifiedCountNeedsReview(checked, candidate, 1)
            // A read-only preview may retain the increment; mutation still needs the bound acknowledgement.
            val requested = swiggyPreviewItemFromParsedItem(candidate, checked)
            assertEquals(IntentCartQuantityMode.INCREMENT, requested.quantityMode)
            assertEquals(1, requested.quantity)
        }
    }

    @Test fun croppedOrUnsupportedAnotherItemHoldsTheWholeLongList() {
        val names = listOf(
            "rice", "soap", "bread", "eggs", "tea", "coffee", "apples", "oranges", "onions", "potatoes",
            "tomatoes", "biscuits", "butter", "cheese", "yogurt",
        )
        val position = names.size / 2
        val special = "another BrandA low-fat milk, one only"

        val croppedSources = names.mapIndexed { index, name ->
            if (index == position) special else "one $name"
        }
        val croppedInstruction = croppedSources.joinToString("; ")
        val croppedRows = croppedSources.mapIndexed { index, fullSource ->
            val source = if (index == position) fullSource.removeSuffix(" only") else fullSource
            val name = if (index == position) "BrandA low-fat milk" else names[index]
            readyRow(croppedInstruction, index, item(name, source, 1, "count", "one"))
        }
        val cropped = review(croppedInstruction, ready = croppedRows).prepareForReview()
        assertTrue("cropped additive quote cannot release siblings", cropped.items.isEmpty())

        val unsupportedSources = names.mapIndexed { index, name ->
            if (index == position) "$special and two more" else "one $name"
        }
        val unsupportedInstruction = unsupportedSources.joinToString("; ")
        val unsupportedRows = unsupportedSources.mapIndexed { index, source ->
            val name = if (index == position) "BrandA low-fat milk" else names[index]
            readyRow(unsupportedInstruction, index, item(name, source, 1, "count", "one"))
        }
        val unsupported = review(unsupportedInstruction, ready = unsupportedRows).prepareForReview()
        assertTrue("unsupported additive wording cannot become a target request", unsupported.items.isEmpty())
    }

    @Test fun anotherCannotHideUnsupportedScopeOrBeCopiedIntoTheProductName() {
        val primary = "another BrandA milk, one only"
        for ((original, name) in listOf(
            primary to "another BrandA milk",
            "$primary if available" to "BrandA milk",
            "$primary, biscuits" to "BrandA milk",
            "another BrandA milk and sugar, one only" to "BrandA milk sugar",
            "another another BrandA milk, one only" to "BrandA milk",
        )) {
            val quoted = if (original.endsWith("biscuits")) primary else original
            val result = review(original, ready = listOf(readyRow(
                original, 0, item(name, quoted, 1, "count", "one"),
            ))).prepareForReview()
            assertTrue(original, result.items.isEmpty())
        }
    }

    @Test fun terminalCountOnlyEmphasisIsRevokedWhenEitherCommaNeighborFailsIdentity() {
        val instruction = "blue milk, one only, two blue soaps"
        val first = "blue milk, one only"
        val second = "two blue soaps"

        val firstIdentityFails = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("milk", first, 1, "count", "one")),
            readyRow(instruction, 1, item("blue soaps", second, 2, "count", "two")),
        )).prepareForReview()
        assertTrue("failed first owner revokes the whole basket", firstIdentityFails.items.isEmpty())

        val lateNeighborFails = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("blue milk", first, 1, "count", "one")),
            readyRow(instruction, 1, item("soaps", second, 2, "count", "two")),
        )).prepareForReview()
        assertTrue("late quantity-valid neighbor lost blue", lateNeighborFails.items.isEmpty())

        val semicolonIsAnIndependentHardBoundary = "blue milk, one only; two blue soaps"
        val independent = review(semicolonIsAnIndependentHardBoundary, ready = listOf(
            readyRow(semicolonIsAnIndependentHardBoundary, 0,
                item("blue milk", "blue milk, one only", 1, "count", "one")),
            readyRow(semicolonIsAnIndependentHardBoundary, 1,
                item("soaps", "two blue soaps", 2, "count", "two")),
        )).prepareForReview()
        assertEquals(listOf("blue milk"), independent.items.map { it.query })
    }

    @Test fun onlyDoNotSubstituteMasksDoAsAuxiliaryNotAsAnAmount() {
        val harmless = "milk; do not substitute sweetened milk"
        val harmlessRow = readyRow(
            harmless, 0, item("milk", "milk"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "do not substitute sweetened milk")),
        )
        assertEquals(null, harmlessRow.quantityOmissionIssue())

        val amountStillVisible = "milk; do not substitute 2 sweetened milk"
        val amountRow = readyRow(
            amountStillVisible, 0, item("milk", "milk"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "do not substitute 2 sweetened milk")),
        )
        assertEquals("quantity_omission_suspected", amountRow.quantityOmissionIssue())
    }

    @Test fun ambiguousNumberLikeProductWordingRemainsConservativelyBlocked() {
        // Independently retained terminal descriptive-one pronouns are covered
        // by GroceryIntentNumericOwnershipTest, as are complete retained product
        // compounds such as pav bhaji. An arbitrary pav phrase stays blocked.
        listOf("zero sugar cola", "pav masala", "ladi pav", "doodh la do", "blue wala de do").forEachIndexed { index, source ->
            val result = review(source, ready = listOf(readyRow(source, index, item(source, source)))).prepareForReview()
            assertTrue(source, result.items.isEmpty())
            assertEquals(source, listOf(source), result.excludedText)
        }
    }

    @Test fun containerRequestedInOwnedSupportCannotBeDropped() {
        val instruction = "ketchup; one bottle ketchup"
        val row = readyRow(
            instruction, 0, item("ketchup", "ketchup"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "one bottle ketchup")),
        )
        val result = review(instruction, ready = listOf(row)).prepareForReview()

        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.single().contains("ketchup"))
    }

    @Test fun protectedNoAddedSugarAndReadyMadePropertiesCannotBeDropped() {
        val cases = listOf(
            "BrandA tea with no added sugar" to item("BrandA tea", "BrandA tea with no added sugar"),
            "BrandA ready-made curry" to item("BrandA curry", "BrandA ready-made curry"),
        )
        cases.forEachIndexed { index, (source, candidate) ->
            val result = review(source, ready = listOf(readyRow(source, index, candidate))).prepareForReview()
            assertTrue(source, result.items.isEmpty())
            assertEquals(source, listOf(source), result.excludedText)
        }
    }

    @Test fun sourceProvenPackMeasureRemainsStrictCatalogueEvidence() {
        val source = "one 10 gram bar of roasted coffee protein, no added sugar"
        val instruction = source
        val prepared = review(
            instruction,
            ready = listOf(readyRow(
                instruction,
                0,
                item(
                    name = "roasted coffee protein bar 10 gram",
                    sourceText = source,
                    quantity = 1,
                    quantityUnit = "pack",
                    quantityText = "one",
                    packaging = "bar",
                    required = listOf("no_added_sugar"),
                    packValue = BigDecimal("10"), packUnit = "g", packText = "10",
                ),
            )),
        ).prepareForReview()

        val parsed = prepared.items.single()
        assertEquals(Quantity.Count(1), parsed.quantity)
        assertTrue(parsed.retailPackCount)
        assertTrue(parsed.query.contains("10 gram"))
        assertTrue(parsed.strictMatchPhrase!!.contains("10 gram"))

        assertTrue(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("correct-10g", "Roasted Coffee Protein Bar 10 g No Added Sugar"),
        ))
        assertFalse(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("wrong-30g", "Roasted Coffee Protein Bar 30 g No Added Sugar"),
        ))
        assertFalse(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate(
                "conflicting-variant",
                "Roasted Coffee Protein Bar With 10 g Protein No Added Sugar",
                variant = "50 g",
            ),
        ))
        assertFalse(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("no-size", "Roasted Coffee Protein Bar No Added Sugar"),
        ))
    }

    @Test fun preparedCockroachSprayPackSizeRemainsStrictCatalogueEvidence() {
        val source = "one 500 ml BrandA red spray for cockroaches"
        val prepared = review(source, ready = listOf(readyRow(
            source,
            0,
            item(
                name = "BrandA red cockroach spray",
                sourceText = source,
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = BigDecimal("500"),
                packUnit = "ml",
                packaging = "spray",
                packText = "500",
            ),
        ))).prepareForReview()

        val parsed = prepared.items.single()
        assertEquals(Quantity.Count(1), parsed.quantity)
        assertTrue(prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())

        assertTrue(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("correct-500ml-spray", "BrandA red spray for cockroaches 500 ml"),
        ))
        assertFalse(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("wrong-300ml-spray", "BrandA red spray for cockroaches 300 ml"),
        ))
        assertFalse(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("wrong-mosquito-target", "BrandA red spray for mosquitoes 500 ml"),
        ))
        assertFalse(isSwiggyCandidateAllowed(
            parsed,
            RecommendationCandidate("wrong-brand-spray", "BrandB red spray for cockroaches 500 ml"),
        ))
    }

    @Test fun uncitedGlobalInstructionRemainsForCombinedAcknowledgement() {
        val instruction = "2 eggs, remember my weekly shopping note"
        val result = review(
            instruction,
            ready = listOf(readyRow(instruction, 0, item("eggs", "2 eggs", 2, "piece", "2"))),
        ).prepareForReview()

        assertTrue(result.unresolvedText.isNotEmpty())
        assertTrue(result.unresolvedText.joinToString(" ").contains("weekly shopping note"))
    }

    @Test fun repeatedIdenticalProductRemainsUnresolvedWhenOnlyFirstSpanIsOwned() {
        val instruction = "milk and milk"
        val result = review(
            instruction,
            ready = listOf(readyRow(instruction, 0, item("milk", "milk"))),
        ).prepareForReview()

        assertEquals(listOf("milk"), result.unresolvedText)
    }

    @Test fun explicitNegativeVariantBecomesAnAvoidPhraseNotAPositiveRequirement() {
        for (prefix in listOf("no", "not", "without")) {
            for (suffix in listOf("substitution", "substitutions", "substitute", "substitutes", "version", "versions")) {
                val support = "$prefix sugar-free $suffix for the jaggery"
                val instruction = "1 kg jaggery; $support"
                val result = review(instruction, ready = listOf(readyRow(
                    instruction, 0, item("jaggery", "1 kg jaggery", 1, "kg", "1", excluded = listOf("sugar_free")),
                    listOf(GroceryIntentReviewV2SupportText("modifier", support)),
                ))).prepareForReview()
                assertTrue(result.excludedText.isEmpty())
                assertTrue(result.unresolvedText.isEmpty())
                assertEquals(listOf("sugar free"), result.items.single().avoidPhrases)
                assertFalse(result.items.single().query.contains("sugar"))
            }
        }
    }

    @Test fun negativePropertyCannotBeDroppedOrFlipped() {
        val support = "no sugar-free substitutions for the jaggery"
        val instruction = "1 kg jaggery; $support"
        for (candidate in listOf(
            item("jaggery", "1 kg jaggery", 1, "kg", "1"),
            item("jaggery", "1 kg jaggery", 1, "kg", "1", required = listOf("sugar_free")),
            item("sugar-free jaggery", "1 kg jaggery", 1, "kg", "1", excluded = listOf("sugar_free")),
        )) {
            val result = review(instruction, ready = listOf(readyRow(
                instruction, 0, candidate, listOf(GroceryIntentReviewV2SupportText("modifier", support)),
            ))).prepareForReview()
            assertTrue(result.items.isEmpty())
            assertTrue(result.excludedText.single().contains(support))
        }

        for ((term, tag) in listOf(
            "chemicals" to "chemicals", "preservatives" to "preservatives",
            "artificial colours" to "artificial_colours", "artificial flavours" to "artificial_flavours",
        )) {
            val propertySupport = "no $term"
            val propertyInstruction = "1 kg jaggery; $propertySupport"
            for (candidate in listOf(
                item("jaggery", "1 kg jaggery", 1, "kg", "1"),
                item("jaggery", "1 kg jaggery", 1, "kg", "1", required = listOf(tag)),
                item("$term jaggery", "1 kg jaggery", 1, "kg", "1", excluded = listOf(tag)),
            )) {
                val result = review(propertyInstruction, ready = listOf(readyRow(
                    propertyInstruction, 0, candidate,
                    listOf(GroceryIntentReviewV2SupportText("modifier", propertySupport)),
                ))).prepareForReview()
                assertTrue("term=$term candidate=${candidate.name}", result.items.isEmpty())
                assertTrue("term=$term", result.excludedText.single().contains(propertySupport))
            }
        }
    }

    @Test fun unownedNegativeVariantCannotBeIgnoredAtCombinedReview() {
        val instruction = "jaggery; no sugar-free substitutions for the jaggery"
        val result = review(instruction, ready = listOf(readyRow(
            instruction, 0, item("jaggery", "jaggery;"),
        ))).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertTrue(result.unresolvedText.joinToString(" ").contains("no sugar-free substitutions"))
    }

    @Test fun negativeVariantCannotHidePositivePropertyElsewhereInSameItem() {
        val support = "no sugar-free substitutions for the jaggery"
        val instruction = "sugar-free jaggery; $support"
        val row = readyRow(instruction, 0,
            item("jaggery", "sugar-free jaggery", excluded = listOf("sugar_free")),
            listOf(GroceryIntentReviewV2SupportText("modifier", support)),
        )
        assertEquals("conflicting_property_polarity", row.groundingIssue())
        assertTrue(review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
    }

    @Test fun positivePropertyCannotBeMovedIntoExclusions() {
        val instruction = "sugar-free tea"
        val row = readyRow(instruction, 0, item("tea", instruction, excluded = listOf("sugar_free")))
        assertEquals("unproven_protected_exclusion", row.groundingIssue())
    }

    @Test fun negativeAndPositiveVariantsStayWithTheirRespectiveItems() {
        val support = "no sugar-free substitutions for the jaggery"
        val instruction = "jaggery; sugar-free tea; $support"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("jaggery", "jaggery;", excluded = listOf("sugar_free")),
                listOf(GroceryIntentReviewV2SupportText("modifier", support))),
            readyRow(instruction, 1, item("tea", "sugar-free tea", required = listOf("sugar_free"))),
        )).prepareForReview()
        assertEquals(2, result.items.size)
        assertEquals(listOf("sugar free"), result.items[0].avoidPhrases)
        assertTrue(result.items[1].avoidPhrases.isEmpty())
        assertTrue(result.items[1].query.contains("sugar free"))
    }

    @Test fun separateQuotesCannotFabricateNegativeVariantEvidence() {
        val instruction = "no; jaggery; sugar-free substitutions"
        val row = readyRow(instruction, 0, item("jaggery", "jaggery", excluded = listOf("sugar_free")), listOf(
            GroceryIntentReviewV2SupportText("modifier", "no"),
            GroceryIntentReviewV2SupportText("modifier", "sugar-free substitutions"),
        ))
        assertEquals("item_contains_correction_or_alternative", row.groundingIssue())
    }

    @Test fun negativeVariantExceptionDoesNotEraseOtherCorrectionsOrPartialWords() {
        listOf(
            "no sugar-free jaggery", "no sugar free jaggery", "no salt-free crackers",
            "no sugar-free substitutions-free jaggery", "no organic milk",
            "no sugar-free substitutions; actually remove jaggery",
            "no sugar-free substitutions or plain jaggery", "no, make it two",
        ).forEach { assertTrue(it, intentV2HasCorrectionCue(it)) }
        listOf("tea with no added sugar", "crackers with no salt", "no-maida biscuits").forEach {
            assertFalse(it, intentV2HasCorrectionCue(it))
        }
    }

    @Test fun namedNegativeSubstitutionRequiresAnExplicitOppositeNotGenericFoodOrNonFood() {
        listOf("milk", "soap").forEach { product ->
            val source = "1 $product"
            val phrase = "do not substitute organic $product"
            val instruction = "$source; $phrase"
            val row = readyRow(instruction, 0, item(product, source, 1, excluded = listOf("organic")), listOf(
                GroceryIntentReviewV2SupportText("modifier", phrase),
            ))
            assertEquals("negative_substitution_polarity_ambiguous", row.groundingIssue())
            assertTrue(review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun sweetenedPropertyVocabularyIsProtectedInBothPolarities() {
        listOf("unsweetened", "sweetened").forEach { property ->
            val opposite = if (property == "sweetened") "unsweetened" else "sweetened"
            val primary = "1 $opposite tea"
            val instruction = "$primary; do not substitute $property tea"
            val row = readyRow(instruction, 0, item("tea", primary, 1, required = listOf(opposite), excluded = listOf(property)), listOf(
                GroceryIntentReviewV2SupportText("modifier", "do not substitute $property tea"),
            ))
            assertEquals(property, null, row.groundingIssue())
            val flipped = row.copy(item = row.item.copy(required = listOf(property)))
            assertEquals(property, "conflicting_property_polarity", flipped.groundingIssue())
        }
    }

    @Test fun namedNegativeSubstitutionRetainsPositiveDietAndRequiresLiteralTarget() {
        val source = "unsweetened almond milk with no added sugar"
        val phrase = "do not substitute sweetened almond milk"
        val instruction = "$source; $phrase"
        val row = readyRow(instruction, 0, item(
            "almond milk", source, required = listOf("unsweetened", "no_added_sugar"), excluded = listOf("sweetened"),
        ), listOf(GroceryIntentReviewV2SupportText("modifier", phrase)))
        assertEquals(null, row.groundingIssue())
        val prepared = review(instruction, ready = listOf(row)).prepareForReview()
        assertEquals(listOf("sweetened"), prepared.items.single().avoidPhrases)
        assertTrue(prepared.items.single().query.contains("unsweetened"))

        for ((primary, target) in listOf("doodh" to "milk", "sabun" to "soap", "milk" to "plain")) {
            val exclusion = "do not substitute organic $target"
            val text = "$primary; $exclusion"
            val invalid = readyRow(text, 0, item(primary, primary, excluded = listOf("organic")), listOf(
                GroceryIntentReviewV2SupportText("modifier", exclusion),
            ))
            assertEquals(primary, "negative_substitution_target_unproven", invalid.groundingIssue())
        }
    }

    @Test fun namedPropertyContrastPreservesBothPolaritiesAndIsNotANumericCorrection() {
        for ((positive, negative) in listOf(
            "unsweetened" to "sweetened",
            "sweetened" to "unsweetened",
        )) {
            for (verb in listOf("must", "should")) {
                val support = "the oat milk $verb be $positive, not $negative"
                val instruction = "1 oat milk, 2 eggs; $support"
                val result = review(instruction, ready = listOf(
                    readyRow(instruction, 0, item(
                        "oat milk", "1 oat milk", 1, required = listOf(positive), excluded = listOf(negative),
                    ), listOf(GroceryIntentReviewV2SupportText("modifier", support))),
                    readyRow(instruction, 1, item("eggs", "2 eggs", 2, "piece", "2")),
                )).prepareForReview()

                assertEquals(2, result.items.size)
                assertTrue(result.unresolvedText.isEmpty())
                assertTrue(result.excludedText.isEmpty())
                assertTrue(result.items.first().query.contains(positive))
                assertEquals(listOf(negative), result.items.first().avoidPhrases)
            }
        }
    }

    @Test fun propertyContrastCannotLoseOrReverseEitherPolarity() {
        val support = "the oat milk must be unsweetened, not sweetened"
        val instruction = "1 oat milk; $support"
        val variants = listOf(
            item("oat milk", "1 oat milk", 1, required = emptyList(), excluded = listOf("sweetened")),
            item("oat milk", "1 oat milk", 1, required = listOf("unsweetened"), excluded = emptyList()),
            item("oat milk", "1 oat milk", 1, required = listOf("sweetened"), excluded = listOf("unsweetened")),
            item("oat milk", "1 oat milk", 1, required = listOf("unsweetened"), excluded = listOf("unsweetened")),
            item("oat milk", "1 oat milk", 1, required = listOf("unsweetened", "sweetened"), excluded = listOf("sweetened")),
        )
        variants.forEach { candidate ->
            val row = readyRow(instruction, 0, candidate, listOf(GroceryIntentReviewV2SupportText("modifier", support)))
            assertTrue(row.groundingIssue() != null)
            assertTrue(review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun propertyContrastRequiresAnEntireOwnedClauseAndBoundTarget() {
        listOf(
            "the oat milk must be unsweetened, not sweetened and 1 bread",
            "the oat milk must be unsweetened, not sweetened if cheap",
            "the oat milk must be unsweetened, not sweetened; make it two",
            "the oat milk must be unsweetened, not organic",
            "the rice must be unsweetened, not sweetened",
            "the organic oat milk must be unsweetened, not sweetened",
            "the oat milk and eggs must be unsweetened, not sweetened",
            "the oat milk must be unsweetened, not sweetened or plain",
            "the oat milk must be unsweetened, not sweetened tomorrow",
        ).forEach { support ->
            val instruction = "1 oat milk; $support"
            val row = readyRow(instruction, 0, item(
                "oat milk", "1 oat milk", 1, required = listOf("unsweetened"), excluded = listOf("sweetened"),
            ), listOf(GroceryIntentReviewV2SupportText("modifier", support)))
            assertTrue(support, row.groundingIssue() != null)
            assertTrue(support, review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun propertyContrastCannotOverrideTheOppositeLiteralInThePrimary() {
        val support = "the oat milk must be unsweetened, not sweetened"
        val instruction = "1 sweetened oat milk; $support"
        val row = readyRow(instruction, 0, item(
            "oat milk", "1 sweetened oat milk", 1,
            required = listOf("unsweetened"), excluded = listOf("sweetened"),
        ), listOf(GroceryIntentReviewV2SupportText("modifier", support)))

        assertEquals("conflicting_property_polarity", row.groundingIssue())
        assertTrue(review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
    }

    @Test fun unownedAndAmbiguousPropertyContrastsCannotAttachToAnItem() {
        val unowned = "1 oat milk, 2 eggs; the oat milk must be unsweetened, not sweetened"
        val unownedResult = review(unowned, ready = listOf(
            readyRow(unowned, 0, item("oat milk", "1 oat milk", 1)),
            readyRow(unowned, 1, item("eggs", "2 eggs", 2, "piece", "2")),
        )).prepareForReview()
        assertTrue(unownedResult.items.isEmpty())
        assertEquals(2, unownedResult.excludedText.size)

        val ambiguous = "1 oat milk, 1 soy milk; the milk must be unsweetened, not sweetened"
        val support = "the milk must be unsweetened, not sweetened"
        val ambiguousResult = review(ambiguous, ready = listOf(
            readyRow(ambiguous, 0, item(
                "oat milk", "1 oat milk", 1, required = listOf("unsweetened"), excluded = listOf("sweetened"),
            ), listOf(GroceryIntentReviewV2SupportText("modifier", support))),
            readyRow(ambiguous, 1, item("soy milk", "1 soy milk", 1)),
        )).prepareForReview()
        assertTrue(ambiguousResult.items.isEmpty())
    }

    @Test fun primaryOnlyPropertyContrastsBlockTheWholeBasket() {
        val milkContrast = "the milk must be unsweetened, not sweetened"
        val milkInstruction = "1 oat milk, 1 soy milk; $milkContrast"
        val milkOnlyResult = review(milkInstruction, ready = listOf(
            readyRow(milkInstruction, 0, item("oat milk", "1 oat milk", 1)),
            readyRow(milkInstruction, 1, item("soy milk", "1 soy milk", 1)),
            readyRow(milkInstruction, 2, item(
                "milk", milkContrast, required = listOf("unsweetened"), excluded = listOf("sweetened"),
            )),
        )).prepareForReview()
        assertTrue(milkOnlyResult.items.isEmpty())
        assertEquals(3, milkOnlyResult.excludedText.size)

        val oatContrast = "the oat milk must be unsweetened, not sweetened"
        val oatInstruction = "1 oat milk, 2 eggs; $oatContrast"
        val inventedThirdRowResult = review(oatInstruction, ready = listOf(
            readyRow(oatInstruction, 0, item("oat milk", "1 oat milk", 1)),
            readyRow(oatInstruction, 1, item("eggs", "2 eggs", 2, "piece", "2")),
            readyRow(oatInstruction, 2, item(
                "oat milk", oatContrast, required = listOf("unsweetened"), excluded = listOf("sweetened"),
            )),
        )).prepareForReview()
        assertTrue(inventedThirdRowResult.items.isEmpty())
        assertEquals(3, inventedThirdRowResult.excludedText.size)
    }

    @Test fun aBlockedPrimaryContrastCannotHideItsConstraintFromReadyPeers() {
        val contrast = "the oat milk must be unsweetened, not sweetened"
        val instruction = "1 oat milk, 2 eggs; $contrast"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("oat milk", "1 oat milk", 1)),
            readyRow(instruction, 1, item("eggs", "2 eggs", 2, "piece", "2")),
        ), blocked = listOf(blockedRow(instruction, 2, contrast))).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.contains(contrast))
    }

    @Test fun aPrimaryContrastAloneHasNoIndependentlyProvenProduct() {
        val contrast = "the oat milk must be unsweetened, not sweetened"
        val result = review(contrast, ready = listOf(readyRow(contrast, 0, item(
            "oat milk", contrast, required = listOf("unsweetened"), excluded = listOf("sweetened"),
        )))).prepareForReview()
        assertTrue(result.items.isEmpty())
    }

    @Test fun aNamedPropertyContrastCanCoexistWithAnIndependentlyOwnedNumericCorrection() {
        val support = "the oat milk must be unsweetened, not sweetened"
        val correctionText = "make eggs 3"
        val instruction = "2 eggs, 1 oat milk; $correctionText; $support"
        val eggs = readyRow(instruction, 0, item("eggs", "2 eggs", 2, "piece", "2")).copy(
            corrections = listOf(GroceryIntentReviewV2Correction(
                kind = "quantity",
                source = quote(instruction, correctionText),
                newAmount = quote(instruction, "3"),
                oldValue = BigDecimal("2"),
                oldUnit = "piece",
                newValue = BigDecimal("3"),
                newUnit = "piece",
            )),
        )
        val oatMilk = readyRow(instruction, 1, item(
            "oat milk", "1 oat milk", 1, required = listOf("unsweetened"), excluded = listOf("sweetened"),
        ), listOf(GroceryIntentReviewV2SupportText("modifier", support)))
        val result = review(instruction, ready = listOf(eggs, oatMilk)).prepareForReview()

        assertEquals(2, result.items.size)
        assertEquals(Quantity.Count(3), result.items.first().quantity)
        assertTrue(result.items.last().query.contains("unsweetened"))
        assertEquals(listOf("sweetened"), result.items.last().avoidPhrases)
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun namedNegativeSubstitutionRequiresItsExclusionAndCannotFlipPolarity() {
        val instruction = "unsweetened milk; do not substitute sweetened milk"
        val phrase = "do not substitute sweetened milk"
        for (candidate in listOf(
            item("sweetened milk", "unsweetened milk", required = listOf("unsweetened")),
            item("milk", "unsweetened milk", required = listOf("sweetened"), excluded = listOf("sweetened")),
        )) {
            val row = readyRow(instruction, 0, candidate, listOf(GroceryIntentReviewV2SupportText("modifier", phrase)))
            assertEquals("conflicting_property_polarity", row.groundingIssue())
        }
        val dropped = readyRow(instruction, 0, item("milk", "unsweetened milk", required = listOf("unsweetened")), listOf(GroceryIntentReviewV2SupportText("modifier", phrase)))
        assertEquals("missing_protected_exclusion", dropped.groundingIssue())
    }

    @Test fun splitNegationNeverTurnsUnsweetenedIntoSweetenedOrNonVegetarianIntoVegetarian() {
        listOf("un-sweetened", "un sweetened", "non-sweetened", "non sweetened").forEach { spelling ->
            val source = "$spelling almond milk"
            val correct = readyRow(source, 0, item("almond milk", source, required = listOf("unsweetened")))
            assertEquals(spelling, null, correct.groundingIssue())
            assertEquals(spelling, 1, review(source, ready = listOf(correct)).prepareForReview().items.size)
            listOf(emptyList(), listOf("sweetened")).forEach { wrong ->
                val incorrect = correct.copy(item = correct.item.copy(required = wrong))
                assertTrue(spelling, incorrect.groundingIssue() != null)
                assertTrue(review(source, ready = listOf(incorrect)).prepareForReview().items.isEmpty())
            }
        }
        listOf("non-vegetarian", "non vegetarian", "non-organic", "un-certified", "ｎｏｎ vegetarian", "non_vegetarian",
            "nonvegetarian", "nonveg", "nonorganic", "uncertified").forEach { spelling ->
            val source = "$spelling soup"
            listOf(emptyList(), listOf("vegetarian"), listOf("organic"), listOf("certified")).forEach { proposed ->
                val row = readyRow(source, 0, item("soup", source, required = proposed))
                assertEquals("unproven_property_negation", row.groundingIssue())
                assertTrue(review(source, ready = listOf(row)).prepareForReview().items.isEmpty())
            }
        }
        assertTrue(intentV2HasCorrectionCue("do not substitute sweetened almond milk, actually cancel curd"))
    }

    @Test fun namedNegativeSubstitutionRejectsWrongTargetAndAlternativeTails() {
        for (phrase in listOf(
            "do not substitute organic tea", "do not substitute organic milk with oat milk",
            "do not substitute organic milk or soy milk", "don't substitute organic milk instead use oat milk",
        )) {
            val instruction = "milk; $phrase"
            val row = readyRow(instruction, 0, item("milk", "milk"), listOf(GroceryIntentReviewV2SupportText("modifier", phrase)))
            assertEquals(phrase, "negative_substitution_target_unproven", row.groundingIssue())
        }
    }

    @Test fun unownedNamedNegativeSubstitutionBlocksAndFragmentsCannotReassemble() {
        val unowned = "milk; do not substitute organic milk"
        val unownedResult = review(unowned, ready = listOf(readyRow(unowned, 0, item("milk", "milk;")))).prepareForReview()
        assertTrue(unownedResult.items.isEmpty())
        assertTrue(unownedResult.unresolvedText.joinToString(" ").contains("do not substitute organic milk"))

        val instruction = "do not; substitute organic milk"
        val row = readyRow(instruction, 0, item("milk", "milk", excluded = listOf("organic")), listOf(
            GroceryIntentReviewV2SupportText("modifier", "do not"),
            GroceryIntentReviewV2SupportText("modifier", "substitute organic milk"),
        ))
        assertEquals("item_contains_correction_or_alternative", row.groundingIssue())
    }

    @Test fun namedNegativeSupportMustNotSwallowAnotherProductBeforeOrAfterIt() {
        listOf(
            "do not substitute sweetened almond milk, 2 eggs",
            "do not substitute sweetened almond milk: organic soap",
            "2 eggs, do not substitute sweetened almond milk",
            "do not substitute sweetened almond milk; bread",
        ).forEach { phrase ->
            val source = "1 unsweetened almond milk"
            val instruction = "$source; $phrase"
            val row = readyRow(instruction, 0, item("almond milk", source, 1,
                required = listOf("unsweetened"), excluded = listOf("sweetened")), listOf(
                GroceryIntentReviewV2SupportText("modifier", phrase),
            ))
            assertEquals(phrase, "negative_substitution_scope_unproven", row.groundingIssue())
            assertTrue(review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun compoundPositiveAndNegativeRequirementProvesBothTargetsAndPreservesBothProperties() {
        val source = "1 unsweetened almond milk"
        val prefixes = listOf("Please make sure the almond milk has no added sugar; ", "ensure almond milk is no-added-sugar; ")
        prefixes.forEach { prefix ->
            val phrase = prefix + "do not substitute sweetened almond milk."
            val instruction = "$source; $phrase"
            val row = readyRow(instruction, 0, item("almond milk", source, 1,
                required = listOf("unsweetened", "no_added_sugar"), excluded = listOf("sweetened")), listOf(
                GroceryIntentReviewV2SupportText("modifier", phrase),
            ))
            assertEquals(prefix, null, row.groundingIssue())
            assertEquals(1, review(instruction, ready = listOf(row)).prepareForReview().items.size)
            assertTrue(row.copy(item = row.item.copy(required = listOf("unsweetened"))).groundingIssue() != null)
        }
        listOf(
            "Please make sure tea has no added sugar; ",
            "Please make sure almond milk and tea has no added sugar; ",
            "Please make sure almond milk has no added sugar and 2 eggs; ",
            "Please make sure almond milk has no added sugar unless unavailable; ",
        ).forEach { prefix ->
            val phrase = prefix + "do not substitute sweetened almond milk"
            val instruction = "$source; $phrase"
            val row = readyRow(instruction, 0, item("almond milk", source, 1,
                required = listOf("unsweetened", "no_added_sugar"), excluded = listOf("sweetened")), listOf(
                GroceryIntentReviewV2SupportText("modifier", phrase),
            ))
            assertTrue(prefix, review(instruction, ready = listOf(row)).prepareForReview().items.isEmpty())
        }
    }

    @Test fun noAddedSugarRemainsAPositiveProperty() {
        val instruction = "tea with no added sugar"
        val result = review(instruction, ready = listOf(readyRow(
            instruction, 0, item("tea", instruction, required = listOf("no_added_sugar")),
        ))).prepareForReview()
        assertEquals(1, result.items.size)
        assertTrue(result.items.single().avoidPhrases.isEmpty())
    }

    @Test fun completeNeutralIntroductionAndCourtesyDoNotAddAnUnresolvedReviewStep() {
        for (intro in listOf("Please get the following", "Shopping list", "bazaar se yeh sab le aana")) {
            val instruction = "$intro: one bread, 2 eggs. No rush"
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
                readyRow(instruction, 1, item("eggs", "2 eggs", 2, "piece", "2")),
            )).prepareForReview()
            assertEquals(2, result.items.size)
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun validatedSnackSectionKeepsExactRowsCountsAndDietaryConstraints() {
        val firstSnack = "one BrandA organic almonds, no salt"
        val secondSnack = "two BrandB sugar-free biscuits"
        val instruction = "one rice. Snacks mein $firstSnack, $secondSnack"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("rice", "one rice", 1, "count", "one")),
            readyRow(instruction, 1, item(
                "BrandA organic almonds", firstSnack, 1, "count", "one",
                required = listOf("organic"), excluded = listOf("salt"),
            )),
            readyRow(instruction, 2, item(
                "BrandB sugar-free biscuits", secondSnack, 2, "count", "two",
                required = listOf("sugar_free"),
            )),
        )).prepareForReview()

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
        assertEquals(listOf("one rice", firstSnack, secondSnack), result.items.map { it.rawText })
        assertTrue(result.items[1].query.contains("BrandA organic almonds"))
        assertTrue(result.items[1].query.contains("organic"))
        assertTrue(result.items[1].avoidPhrases.contains("salt"))
        assertTrue(result.items[2].query.contains("BrandB sugar-free biscuits"))
        assertTrue(result.items[2].query.contains("sugar free"))
        assertTrue(result.unresolvedText.isEmpty())
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun snackSectionDoesNotHideARejectedOrOmittedMember() {
        val failedInstruction = "Snacks mein one BrandA almonds; two BrandB raisins"
        val failedRow = review(
            failedInstruction,
            ready = listOf(readyRow(failedInstruction, 0,
                item("BrandA almonds", "one BrandA almonds", 1, "count", "one"))),
            blocked = listOf(blockedRow(failedInstruction, 1, "two BrandB raisins")),
        ).prepareForReview()
        assertEquals(listOf("BrandA almonds"), failedRow.items.map { it.query })
        assertTrue((failedRow.unresolvedText + failedRow.excludedText).joinToString(" ")
            .contains("Snacks mein"))
        assertTrue(failedRow.excludedText.any { it.contains("two BrandB raisins") })

        val omittedInstruction = "Snacks mein one BrandA almonds; two BrandB raisins"
        val omitted = review(omittedInstruction, ready = listOf(readyRow(
            omittedInstruction, 0, item("BrandA almonds", "one BrandA almonds", 1, "count", "one"),
        ))).prepareForReview()
        assertEquals(listOf("BrandA almonds"), omitted.items.map { it.query })
        assertTrue(omitted.unresolvedText.joinToString(" ").contains("two BrandB raisins"))
        assertTrue(omitted.unresolvedText.joinToString(" ").contains("Snacks mein"))

        val commaBlockedInstruction = "Snacks mein one BrandA almonds, two BrandB raisins"
        val commaBlocked = review(
            commaBlockedInstruction,
            ready = listOf(readyRow(commaBlockedInstruction, 0,
                item("BrandA almonds", "one BrandA almonds", 1, "count", "one"))),
            blocked = listOf(blockedRow(commaBlockedInstruction, 1, "two BrandB raisins")),
        ).prepareForReview()
        assertFalse("ambiguous comma plus blocked numeric sibling cannot establish the first item's boundary",
            commaBlocked.items.any { "BrandA almonds" in it.query })

        val commaOmitted = review(commaBlockedInstruction, ready = listOf(readyRow(
            commaBlockedInstruction, 0, item("BrandA almonds", "one BrandA almonds", 1, "count", "one"),
        ))).prepareForReview()
        assertFalse("ambiguous comma plus omitted numeric sibling cannot establish the first item's boundary",
            commaOmitted.items.any { "BrandA almonds" in it.query })
    }

    @Test fun snackSectionCannotRepairMissingBrandDietOrQuantityEvidence() {
        data class Candidate(
            val source: String,
            val candidate: GroceryIntentReviewV2Item,
        )
        val failures = listOf(
            Candidate("two BrandB raisins", item("BrandC raisins", "two BrandB raisins", 2, "count", "two")),
            Candidate("two BrandB sugar-free raisins", item("BrandB raisins", "two BrandB sugar-free raisins", 2, "count", "two")),
            Candidate("two BrandB raisins", item("BrandB raisins", "two BrandB raisins", 1, "count", "one")),
        )

        failures.forEach { failure ->
            val first = "one BrandA almonds"
            val instruction = "Snacks mein $first; ${failure.source}"
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("BrandA almonds", first, 1, "count", "one")),
                readyRow(instruction, 1, failure.candidate),
            )).prepareForReview()

            assertEquals(listOf("BrandA almonds"), result.items.map { it.query })
            assertEquals(listOf(Quantity.Count(1)), result.items.map { it.quantity })
            val retained = (result.unresolvedText + result.excludedText).joinToString(" ")
            assertTrue("Snack section consumed ${failure.source}", retained.contains(failure.source))
            assertTrue("Snack section consumed its heading", retained.contains("Snacks mein"))

            val commaInstruction = "Snacks mein $first, ${failure.source}"
            val commaResult = review(commaInstruction, ready = listOf(
                readyRow(commaInstruction, 0, item("BrandA almonds", first, 1, "count", "one")),
                readyRow(commaInstruction, 1, failure.candidate.copy(sourceText = failure.source)),
            )).prepareForReview()
            assertFalse("invalid comma sibling cannot establish the first item's boundary: ${failure.source}",
                commaResult.items.any { "BrandA almonds" in it.query })
        }
    }

    @Test fun snackSectionCannotRepairAnOmittedOrInventedFlavor() {
        val cases = listOf(
            "one BrandB roasted foxnuts peri peri" to "BrandB roasted foxnuts",
            "one BrandB roasted foxnuts" to "BrandB roasted foxnuts peri peri",
            "one BrandB peanuts" to "BrandB roasted peanuts",
            "one BrandB roasted peanuts" to "BrandB peanuts",
        )
        cases.forEach { (source, proposedName) ->
            val first = "one BrandA roasted foxnuts"
            val instruction = "Snacks mein $first; $source"
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("BrandA roasted foxnuts", first, 1, "count", "one")),
                readyRow(instruction, 1, item(proposedName, source, 1, "count", "one")),
            )).prepareForReview()

            assertEquals(listOf("BrandA roasted foxnuts"), result.items.map { it.query })
            val retained = (result.unresolvedText + result.excludedText).joinToString(" ")
            assertTrue("Flavor evidence was consumed: $source -> $proposedName", retained.contains(source))
            assertTrue("Snack heading was consumed: $source -> $proposedName", retained.contains("Snacks mein"))

            val commaInstruction = "Snacks mein $first, $source"
            val dependent = review(commaInstruction, ready = listOf(
                readyRow(commaInstruction, 0, item("BrandA roasted foxnuts", first, 1, "count", "one")),
                readyRow(commaInstruction, 1, item(proposedName, source, 1, "count", "one")),
            )).prepareForReview()
            assertTrue("Failed source proof cannot confer a comma boundary", dependent.items.isEmpty())
        }
    }

    @Test fun snackSectionKeepsCoordinatedPreparationAndPostposedFlavor() {
        val sources = listOf("one BrandA roasted foxnuts peri peri", "two BrandB roasted and salted foxnuts")
        val names = listOf("BrandA roasted foxnuts peri peri", "BrandB roasted and salted foxnuts")
        val instruction = "Snacks mein ${sources.joinToString(", ")}"
        val result = review(instruction, ready = sources.mapIndexed { index, source ->
            readyRow(instruction, index, item(names[index], source, index + 1, "count", if (index == 0) "one" else "two"))
        }).prepareForReview()
        assertEquals(names, result.items.map { it.query })
        assertEquals(sources, result.items.map { it.rawText })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
        assertTrue(result.unresolvedText.isEmpty())
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun snackSectionNeedsEveryMemberToBeAnIndependentConcreteSnack() {
        val first = "one almonds"
        val second = "two raisins"
        val third = "one almond milk"
        for (sources in listOf(listOf(first), listOf(first, second, third))) {
            val instruction = "Snacks mein ${sources.joinToString(", ")}."
            val result = review(instruction, ready = sources.mapIndexed { index, source ->
                val count = if (index == 1) 2 else 1
                readyRow(instruction, index, item(source.substringAfter(' '), source, count, "count", source.substringBefore(' ')))
            }).prepareForReview()
            assertEquals(sources.size, result.items.size)
            assertTrue(result.unresolvedText.any { it.contains("Snacks mein") })
        }
    }

    @Test fun snackSectionDoesNotTurnAnAbsorbedSiblingIntoACompleteBasket() {
        val swallowed = "one almonds, two raisins"
        val last = "three biscuits"
        val instruction = "Snacks mein $swallowed, $last."
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("almonds", swallowed, 1, "count", "one")),
            readyRow(instruction, 1, item("biscuits", last, 3, "count", "three")),
        )).prepareForReview()
        assertEquals(listOf("biscuits"), result.items.map { it.query })
        assertTrue(result.unresolvedText.any { it.contains("Snacks mein") })
        assertTrue(result.excludedText.any { it.contains(swallowed) })
    }

    @Test fun snackSectionCannotConsumePriorOrFollowingSentences() {
        val sources = listOf("one rice", "one almonds", "two raisins", "three eggs")
        val instruction = "${sources[0]}. Snacks mein ${sources[1]}, ${sources[2]}; ${sources[3]}"
        val result = review(instruction, ready = sources.mapIndexed { index, source ->
            val count = if (index < 2) 1 else index
            readyRow(instruction, index, item(source.substringAfter(' '), source, count, "count", source.substringBefore(' ')))
        }).prepareForReview()
        assertEquals(sources, result.items.map { it.rawText })
        assertTrue(result.unresolvedText.isEmpty())
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun contextualAffirmedGroupKeepsItsLiteralHeaderRowsCountsAndDiet() {
        val first = "organic mango one"
        val second = "organic strawberry two"
        val instruction = "one rice. Then BrandA yogurt, haan, $first, $second"
        val header = "BrandA yogurt, haan, "
        val group = localGroup(instruction, header, listOf(1 to first, 2 to second))
        val draft = review(
            instruction,
            ready = listOf(
                readyRow(instruction, 0, item("rice", "one rice", 1, "count", "one")),
                readyRow(instruction, 1, item("BrandA yogurt organic mango", first, 1, "count", "one", required = listOf("organic"))),
                readyRow(instruction, 2, item("BrandA yogurt organic strawberry", second, 2, "count", "two", required = listOf("organic"))),
            ),
            groups = listOf(group),
        )
        val result = draft.prepareForReview()

        assertEquals(header, draft.groups.single().header?.text)
        assertEquals(listOf("one rice", first, second), result.items.map { it.rawText })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
        assertEquals("BrandA yogurt organic mango organic", result.items[1].query)
        assertEquals("BrandA yogurt organic strawberry organic", result.items[2].query)
        assertTrue(result.items[1].query.contains("organic"))
        assertTrue(result.items[2].query.contains("organic"))
        assertTrue(result.excludedText.isEmpty())
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun contextualAffirmedGroupDoesNotHideMissingBrandDietOrSourceSuffix() {
        data class Case(val second: String, val candidateName: String, val required: List<String>)
        val cases = listOf(
            Case("organic strawberry two", "yogurt organic strawberry", listOf("organic")),
            Case("organic strawberry two", "BrandA yogurt strawberry", emptyList()),
            Case("organic strawberry two BrandB", "BrandA yogurt organic strawberry", listOf("organic")),
        )
        cases.forEach { case ->
            val first = "organic mango one"
            val instruction = "one rice. Then BrandA yogurt, haan, $first, ${case.second}"
            val group = localGroup(instruction, "BrandA yogurt, haan, ", listOf(1 to first, 2 to case.second))
            val result = review(
                instruction,
                ready = listOf(
                    readyRow(instruction, 0, item("rice", "one rice", 1, "count", "one")),
                    readyRow(instruction, 1, item("BrandA yogurt organic mango", first, 1, "count", "one", required = listOf("organic"))),
                    readyRow(instruction, 2, item(case.candidateName, case.second, 2, "count", "two", required = case.required)),
                ),
                groups = listOf(group),
            ).prepareForReview()

            assertTrue("Unproved member parsed: ${case.second}", result.items.none { it.rawText == case.second })
            val held = (result.excludedText + result.unresolvedText).joinToString(" ")
            assertTrue("Source wording disappeared: ${case.second}", held.contains(case.second))
            if (case.second.endsWith("BrandB")) assertTrue("Unmatched source brand disappeared", held.contains("BrandB"))
        }
    }

    @Test fun completeNeutralNarrationHeadingsKeepOrdinaryGroupRowsReviewVisible() {
        val cases = listOf(
            "I am reading from the kitchen shelf, note this down: ",
            "We're reading from my pantry cupboard, note this down: ",
            "We are reading out from the grocery list, write this down: ",
        )
        cases.forEach { header ->
            val first = "one milk"
            val second = "two eggs"
            val instruction = "$header$first, $second"
            val primaries = listOf(0 to first, 1 to second)
            val group = localGroup(instruction, header, primaries)
            assertTrue(header, group.hasNeutralNarrationHeading(instruction, primaries))

            val result = review(
                instruction,
                ready = listOf(
                    readyRow(instruction, 0, item("milk", first, 1, "count", "one")),
                    readyRow(instruction, 1, item("eggs", second, 2, "count", "two")),
                ),
                groups = listOf(group),
            ).prepareForReview()

            assertEquals(header, listOf("milk", "eggs"), result.items.map { it.query })
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
            assertTrue(header, result.sharedGroupNotes.any { it.contains(header.trim().trimEnd(':')) })
            assertTrue(header, result.unresolvedText.isEmpty())
            assertTrue(header, result.excludedText.isEmpty())
        }
    }

    @Test fun completeNeutralNarrationHeadingDoesNotInventUnstatedAmountsOrProductIdentity() {
        val header = "I am reading from the kitchen shelf, note this down: "
        val first = "milk"
        val second = "eggs"
        val instruction = "$header$first, $second"
        val primaries = listOf(0 to first, 1 to second)
        val group = localGroup(instruction, header, primaries).copy(amountStatus = "amounts_unspecified")
        val result = review(
            instruction,
            ready = listOf(
                readyRow(instruction, 0, item("milk", first)),
                readyRow(instruction, 1, item("eggs", second)),
            ),
            groups = listOf(group),
        ).prepareForReview()

        assertTrue(group.hasNeutralNarrationHeading(instruction, primaries))
        assertEquals(listOf(Quantity.Default, Quantity.Default), result.items.map { it.quantity })
        assertTrue(result.sharedGroupNotes.any { it.contains("kitchen shelf, note this down") })

        val forged = review(
            instruction,
            ready = listOf(
                readyRow(instruction, 0, item("kitchen shelf read this down milk", first)),
                readyRow(instruction, 1, item("eggs", second)),
            ),
            groups = listOf(group),
        ).prepareForReview()
        assertTrue(forged.items.none { it.query.contains("kitchen shelf") || it.query.contains("read this down") })
        assertTrue((forged.unresolvedText + forged.excludedText).joinToString(" ").contains(first))
    }

    @Test fun neutralNarrationProofRejectsContaminatedPartialOrMisboundEvidence() {
        val base = "I am reading from the kitchen shelf, note this down: "
        val first = "one milk"
        val second = "two eggs"
        val instruction = "$base$first, $second"
        val primaries = listOf(0 to first, 1 to second)
        val group = localGroup(instruction, base, primaries)
        assertFalse(group.copy(header = quote(instruction, "note this down: "))
            .hasNeutralNarrationHeading(instruction, primaries))
        val originalHeader = requireNotNull(group.header)
        assertFalse(group.copy(header = originalHeader.copy(startUtf16 = originalHeader.startUtf16 + 1))
            .hasNeutralNarrationHeading(instruction, primaries))
        assertFalse(group.hasNeutralNarrationHeading(instruction, emptyList()))
        assertFalse(group.hasNeutralNarrationHeading(instruction, listOf(0 to first)))
        assertFalse(group.hasNeutralNarrationHeading(instruction, listOf(0 to first, 1 to "one eggs")))
        assertFalse(group.hasNeutralNarrationHeading(instruction, listOf(0 to first, 0 to second)))
        assertFalse(group.hasNeutralNarrationHeading(instruction, listOf(0 to "one milk, two", 1 to "two eggs")))

        listOf(" for BrandA", " sugar-free", " no sugar", " 2 kg").forEach { tail ->
            val contaminatedHeader = "$base$tail: "
            val contaminatedInstruction = "$contaminatedHeader$first, $second"
            val contaminatedGroup = localGroup(contaminatedInstruction, contaminatedHeader, listOf(0 to first, 1 to second))
            assertFalse(contaminatedHeader, contaminatedGroup.hasNeutralNarrationHeading(
                contaminatedInstruction, listOf(0 to first, 1 to second),
            ))
        }
    }

    @Test fun realSharedIdentityAndDietHeadingsStillRequireFaithfulMemberIdentity() {
        data class Case(val header: String, val first: String, val second: String, val firstName: String, val secondName: String, val required: List<String>)
        val cases = listOf(
            Case("BrandA yogurt: ", "one mango", "two strawberry", "BrandA yogurt mango", "yogurt strawberry", emptyList()),
            Case("no sugar: ", "one milk", "two eggs", "milk", "eggs", listOf("no_sugar")),
            Case("sugar-free: ", "one biscuits", "two crackers", "sugar-free biscuits", "crackers", listOf("sugar_free")),
        )
        cases.forEach { case ->
            val instruction = "${case.header}${case.first}, ${case.second}"
            val group = localGroup(instruction, case.header, listOf(0 to case.first, 1 to case.second))
            val rows = listOf(case.first to case.firstName, case.second to case.secondName).mapIndexed { index, (source, name) ->
                val required = if (index == 0) case.required else emptyList()
                readyRow(instruction, index, item(name, source, index + 1, "count", if (index == 0) "one" else "two", required = required))
            }
            val result = review(instruction, ready = rows, groups = listOf(group)).prepareForReview()
            assertTrue(case.header, result.items.size < 2)
            assertTrue(case.header, (result.unresolvedText + result.excludedText).joinToString(" ").contains(
                case.second,
            ))
        }
    }

    @Test fun affirmedHeadingDoesNotAbsorbAnExplicitCorrection() {
        val instruction = "one rice, one oil, two soaps, no sorry, yes two"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("rice", "one rice", 1, "count", "one")),
            readyRow(instruction, 1, item("oil", "one oil", 1, "count", "one")),
            readyRow(instruction, 2, item("soap", "two soaps", 2, "count", "two")),
        )).prepareForReview()

        assertTrue(result.items.isEmpty())
        val retained = (result.unresolvedText + result.excludedText).joinToString(" ")
        assertTrue(retained.contains("no sorry") || retained.contains("yes two"))
    }

    @Test fun neutralIntroductionCannotHideAnUnownedRequirementOrCorrection() {
        val instruction = "Please get the following: milk; no sugar-free substitutions for milk"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("milk", "milk;")),
        )).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertTrue(result.unresolvedText.joinToString(" ").contains("no sugar-free substitutions"))
    }

    @Test fun boundInlineRequestPrefixDoesNotAddAnotherReviewQuestion() {
        for (prefix in listOf("when you get a moment please bring home", "could you please get", "kindly pick up")) {
            val instruction = "Namaste beta, $prefix one bread, 2 eggs"
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
                readyRow(instruction, 1, item("eggs", "2 eggs", 2, "piece", "2")),
            )).prepareForReview()
            assertEquals(prefix, listOf("bread", "eggs"), result.items.map { it.query })
            assertTrue(prefix, result.excludedText.isEmpty())
            assertTrue(prefix, result.unresolvedText.isEmpty())
        }
    }

    @Test fun putInCartAndBoundListSequencePrepareWithoutExtraReviewText() {
        for (instruction in listOf(
            "Please put in one bread; and last two eggs",
            "Please put in one bread; Last, two eggs",
        )) {
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
                readyRow(instruction, 1, item("eggs", "two eggs", 2, "count", "two")),
            )).prepareForReview()

            assertEquals(listOf("bread", "eggs"), result.items.map { it.query })
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun sequenceCannotConsumeAnOmittedModifierBrandCorrectionOrUnknownWord() {
        val cases = listOf(
            "Please put in one bread; and last organic two eggs" to "organic",
            "Please put in one bread; and last BrandA two eggs" to "BrandA",
            "Please put in one bread; and last two eggs, no wait make it three" to "no wait",
            "Please put in one bread; and last two eggs, blorp" to "blorp",
        )
        cases.forEach { (instruction, meaningfulText) ->
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
                readyRow(instruction, 1, item("eggs", "two eggs", 2, "count", "two")),
            )).prepareForReview()

            val retainedText = (result.unresolvedText + result.excludedText).joinToString(" ")
            assertTrue("$instruction silently consumed $meaningfulText", retainedText.contains(meaningfulText))
        }
    }

    @Test fun utf16PrefixOffsetsDoNotHideMeaningfulSequenceText() {
        val instruction = "🛒 Please put in one bread; and last organic two eggs"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
            readyRow(instruction, 1, item("eggs", "two eggs", 2, "count", "two")),
        )).prepareForReview()

        assertTrue(result.items.any { it.query == "bread" })
        assertTrue((result.unresolvedText + result.excludedText).joinToString(" ").contains("organic"))
    }

    @Test fun boundInlinePrefixCannotEraseUnownedBrandDietOrQuantity() {
        for (detail in listOf("BrandA", "organic", "2", "no added sugar")) {
            val prefix = "please get $detail"
            val instruction = "$prefix milk"
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("milk", "milk")),
            )).prepareForReview()
            assertTrue(prefix, result.unresolvedText.joinToString(" ").contains(detail))
        }
    }

    @Test fun inlineCourtesyCannotHideLaterNegativeOrCorrection() {
        for (suffix in listOf("no organic versions", "actually remove eggs", "make it two")) {
            val instruction = "please get 2 eggs; $suffix"
            val result = review(instruction, ready = listOf(
                readyRow(instruction, 0, item("eggs", "2 eggs", 2, "piece", "2")),
            )).prepareForReview()
            assertTrue(suffix, result.items.isEmpty())
            assertTrue(suffix, result.unresolvedText.joinToString(" ").contains(suffix))
        }
    }

    @Test fun modelSplitHomemadeModifierCannotDisappearFromReview() {
        val instruction = "please bring home made ghee"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("ghee", "made ghee")),
        )).prepareForReview()
        assertTrue(result.unresolvedText.joinToString(" ").contains("home"))
    }

    @Test fun completePurposeIntroductionRemainsDistinctFromMeaningfulProductContext() {
        val instruction = "Could you please get the following for my evening tea and the house: one bread, 2 eggs"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("bread", "one bread", 1, "count", "one")),
            readyRow(instruction, 1, item("eggs", "2 eggs", 2, "piece", "2")),
        )).prepareForReview()
        assertEquals(2, result.items.size)
        assertTrue(result.excludedText.isEmpty())
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun cannotAssembleCourtesyAcrossAnOwnedProduct() {
        val instruction = "no milk rush"
        val result = review(instruction, ready = listOf(
            readyRow(instruction, 0, item("milk", "milk")),
        )).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertTrue(result.unresolvedText.contains("no"))
    }

    @Test fun unsupportedPunnetFormCannotFallBackToPackButCanonicalPackIsPreserved() {
        val unsupported = "1 punnet strawberries (250 g)"
        val punnetRow = readyRow(
            unsupported, 0,
            item(
                "strawberries", unsupported, 1, "pack", "1", BigDecimal("250"), "g",
                packaging = "pack", packText = "250",
            ),
        )
        assertEquals("unproven_name_or_modifier", punnetRow.groundingIssue())
        val blocked = review(unsupported, ready = listOf(punnetRow)).prepareForReview()
        assertTrue(blocked.items.isEmpty())
        assertEquals(listOf(unsupported), blocked.excludedText)

        val supported = "1 pack strawberries (250 g)"
        val packRow = readyRow(
            supported, 0,
            item(
                "strawberries", supported, 1, "pack", "1", BigDecimal("250"), "g",
                packaging = "pack", packText = "250",
            ),
        )
        assertEquals(null, packRow.groundingIssue())
        val prepared = review(supported, ready = listOf(packRow)).prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().query.contains("250 g"))
        assertTrue(prepared.items.single().retailPackCount)
        assertTrue(prepared.excludedText.isEmpty())
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test fun lexicalizedPackagingRetainsProductCountAndRequiredForm() {
        listOf(
            Triple("1 matchbox", "matchbox", "box"),
            Triple("2 matchboxes", "matchbox", "box"),
            Triple("3 sabun ki tikki", "soap", "bar"),
        ).forEach { (source, name, packaging) ->
            val count = source.substringBefore(' ').toInt()
            val row = readyRow(source, 0, item(name, source, count, "pack", count.toString(), packaging = packaging))
            assertEquals(null, row.groundingIssue())
            val result = review(source, ready = listOf(row)).prepareForReview()
            assertEquals(Quantity.Count(count), result.items.single().quantity)
            assertTrue(result.items.single().retailPackCount)
            assertTrue(result.items.single().query.contains(packaging))
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun packagingCannotBeInventedOrBorrowedFromAnotherProduct() {
        listOf(
            Triple("1 aloo tikki", "potato tikki", "bar"),
            Triple("1 soap", "soap", "bar"),
            Triple("1 sabun ki tikki", "soap dispenser", "bar"),
            Triple("1 matchbox", "Matchbox toy car", "box"),
            Triple("1 sabun ki tikki", "soap", "bottle"),
        ).forEach { (source, name, packaging) ->
            val row = readyRow(source, 0, item(name, source, 1, "pack", "1", packaging = packaging))
            assertEquals("unproven_name_or_modifier", row.groundingIssue())
            val result = review(source, ready = listOf(row)).prepareForReview()
            assertTrue(result.items.isEmpty())
            assertEquals(listOf(source), result.excludedText)
        }
        val source = "soap; 1 sabun ki tikki"
        val row = readyRow(source, 0, item("soap", "soap", packaging = "bar"),
            listOf(GroceryIntentReviewV2SupportText("modifier", "1 sabun ki tikki")))
        assertEquals("unproven_name_or_modifier", row.groundingIssue())
    }

    @Test fun knownGaddiSupportsBunchPackagingWithoutChangingCounts() {
        val rows = listOf(
            Triple("1 gaddi dhaniya", "dhaniya", 1),
            Triple("2 gaddi palak", "palak", 2),
        )
        rows.forEach { (source, name, count) ->
            val result = review(source, ready = listOf(readyRow(
                source, 0, item(name, source, count, "count", count.toString(), packaging = "bunch"),
            ))).prepareForReview()

            val parsed = result.items.single()
            assertEquals(Quantity.Count(count), parsed.quantity)
            assertTrue(parsed.query.contains("bunch", ignoreCase = true))
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }

        val unsupported = "1 gaddix dhaniya"
        val unsupportedRow = readyRow(
            unsupported, 0, item("dhaniya", unsupported, 1, "count", "1", packaging = "bunch"),
        )
        assertEquals("unproven_name_or_modifier", unsupportedRow.groundingIssue())
        val blocked = review(unsupported, ready = listOf(unsupportedRow)).prepareForReview()
        assertTrue(blocked.items.isEmpty())
        assertEquals(listOf(unsupported), blocked.excludedText)
    }

    @Test fun wordInternalBrandApostrophesAndHyphenCompoundPreservePackEvidenceInLongBasket() {
        val rows = listOf(
            Triple("1 Kellogg's corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 McVitie\u2019s biscuits packet 200 g", "McVitie\u2019s biscuits", BigDecimal("200")),
            Triple("1 Mother's Recipe pickle jar 500 g", "Mother's Recipe pickle", BigDecimal("500")),
            Triple("one bottle L\u2019Or\u00e9al shampoo 200 ml", "L\u2019Or\u00e9al shampoo", BigDecimal("200")),
            Triple("one L\u2019Or\u00e9al shampoo bottle 200 ml", "L\u2019Or\u00e9al shampoo", BigDecimal("200")),
            Triple("one L'Oreal shampoo bottle 200 ml", "L'Oreal shampoo", BigDecimal("200")),
            Triple("1 Or\u00e9a shampoo box 200 g", "Or\u00e9a shampoo", BigDecimal("200")),
            Triple("1 Not\u00e9 shampoo box 200 g", "Not\u00e9 shampoo", BigDecimal("200")),
            Triple("1 Kellogg's corn-flakes box 500 g", "Kellogg's corn-flakes", BigDecimal("500")),
        )
        val instruction = (listOf("2 milk") + rows.map { it.first }).joinToString("; ")
        val reviewRows = listOf(readyRow(instruction, 0, item("milk", "2 milk", 2, "count", "2"))) +
            rows.mapIndexed { offset, (source, name, size) ->
                val unit = if (source.endsWith("ml")) "ml" else "g"
                val container = when {
                    source.contains(" bottle ") || source.startsWith("one bottle ") -> "bottle"
                    source.contains(" packet ") -> "packet"
                    source.contains(" jar ") -> "jar"
                    else -> "box"
                }
                readyRow(instruction, offset + 1, item(
                    name, source, 1, "pack", if (source.startsWith("one ")) "one" else "1",
                    size, unit, packaging = container, packText = size.toPlainString(),
                ))
            }

        reviewRows.drop(1).forEach { assertEquals(null, it.groundingIssue()) }
        val prepared = review(instruction, ready = reviewRows).prepareForReview()
        assertTrue(prepared.unresolvedText.isEmpty())
        assertEquals(rows.size + 1, prepared.items.size)
        assertEquals(Quantity.Count(2), prepared.items.first().quantity)
        rows.forEachIndexed { index, (source, name, size) ->
            val wireItem = reviewRows[index + 1].item
            assertEquals(name, wireItem.name)
            assertEquals(BigDecimal.ONE, wireItem.quantity)
            assertEquals(size, wireItem.packValue)
            assertEquals(if (source.startsWith("one ")) "one" else "1", wireItem.quantityText)
            val parsed = prepared.items[index + 1]
            assertEquals(source, parsed.rawText)
            assertEquals(Quantity.Count(1), parsed.quantity)
            assertTrue(parsed.retailPackCount)
            val unit = if (source.endsWith("ml")) "ml" else "g"
            assertTrue(parsed.strictMatchPhrase?.contains("${size.stripTrailingZeros().toPlainString()} $unit") == true)
        }
    }

    @Test fun malformedApostropheAndUnstatedOrInventedPackEvidenceRemainHeld() {
        val cases = listOf(
            Triple("1 \"Kellogg's\" corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 'Kellogg's corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 Kellogg's' corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 -Kellogg's corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 Kellogg's corn-flakes- box 500 g", "Kellogg's corn-flakes", BigDecimal("500")),
            Triple("1 Kellogg's corn flakes 500 g", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 Kellogg's corn flakes box 500 g or another box", "Kellogg's corn flakes", BigDecimal("500")),
            Triple("1 Kellogg's corn flakes box 500 g", "corn flakes", BigDecimal("500")),
            Triple("1 Kellogg's corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("600")),
            Triple("Kellogg's corn flakes box 500 g", "Kellogg's corn flakes", BigDecimal("500")),
        )
        cases.forEach { (source, name, size) ->
            val row = readyRow(source, 0, item(
                name, source, 1, "pack", "1", size, "g", packaging = "box", packText = size.toPlainString(),
            ))
            assertTrue("$source should fail grounding", row.groundingIssue() != null)
            val prepared = review(source, ready = listOf(row)).prepareForReview()
            assertTrue("$source should not produce a usable row", prepared.items.isEmpty())
        }
    }

    @Test fun standaloneLUnitAndBrandInitialCannotBeRecastAsPackOrVolumeEvidence() {
        val literalUnit = "one l shampoo"
        val countAsPack = readyRow(literalUnit, 0, item(
            "shampoo", literalUnit, 1, "count", "one",
        ))
        assertTrue(countAsPack.groundingIssue() != null)
        assertTrue(review(literalUnit, ready = listOf(countAsPack)).prepareForReview().items.isEmpty())

        val brandSource = "one L\u2019Or\u00e9al shampoo bottle 200 ml"
        val brandAsLitre = readyRow(brandSource, 0, item(
            "L\u2019Or\u00e9al shampoo", brandSource, 1, "l", "one", BigDecimal("200"), "ml",
            packaging = "bottle", packText = "200",
        ))
        assertTrue(brandAsLitre.groundingIssue() != null)
        assertTrue(review(brandSource, ready = listOf(brandAsLitre)).prepareForReview().items.isEmpty())
    }

    @Test fun contractionsAndStandaloneCorrectionWordsCannotBeProductIdentity() {
        val cases = listOf(
            Triple("1 isn't shampoo bottle 200 ml", "isn't shampoo", "bottle"),
            Triple("1 doesn't shampoo bottle 200 ml", "doesn't shampoo", "bottle"),
            Triple("1 can't shampoo bottle 200 ml", "can't shampoo", "bottle"),
            Triple("1 or shampoo box 200 g", "or shampoo", "box"),
            Triple("1 not shampoo box 200 g", "not shampoo", "box"),
        )
        cases.forEach { (source, name, container) ->
            val isMl = source.endsWith("ml")
            val size = BigDecimal("200")
            val row = readyRow(source, 0, item(
                name, source, 1, "pack", "1", size, if (isMl) "ml" else "g",
                packaging = container, packText = "200",
            ))
            assertTrue("$source should be rejected as correction language", row.groundingIssue() != null)
            assertTrue("$source should remain held", review(source, ready = listOf(row)).prepareForReview().items.isEmpty())
        }
    }
}

private fun review(
    instruction: String,
    ready: List<GroceryIntentReviewV2ReadyRow> = emptyList(),
    blocked: List<GroceryIntentReviewV2BlockedRow> = emptyList(),
    groups: List<GroceryIntentReviewV2Group> = emptyList(),
) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2",
    requiresReview = true,
    instruction = instruction,
    sourceItemCount = ready.size + blocked.size,
    readyRows = ready,
    blockedRows = blocked,
    groups = groups,
)

private fun localGroup(
    instruction: String,
    headerText: String,
    memberSources: List<Pair<Int, String>>,
) = GroceryIntentReviewV2Group(
    status = "structurally_verified",
    amountStatus = "local_amounts_verified",
    source = quote(
        instruction,
        instruction.substring(instruction.indexOf(headerText), memberSources.last().let { (_, text) ->
            val rowStart = instruction.indexOf(text)
            require(rowStart >= 0)
            rowStart + text.length
        }),
    ),
    header = quote(instruction, headerText),
    memberIndices = memberSources.map { it.first },
    members = memberSources.map { (index, text) ->
        val amount = Regex("\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\b", RegexOption.IGNORE_CASE)
            .findAll(text).lastOrNull()?.value
        val value = when (amount?.lowercase()) {
            "one" -> BigDecimal.ONE
            "two" -> BigDecimal("2")
            "three" -> BigDecimal("3")
            "four" -> BigDecimal("4")
            "five" -> BigDecimal("5")
            "six" -> BigDecimal("6")
            "seven" -> BigDecimal("7")
            "eight" -> BigDecimal("8")
            "nine" -> BigDecimal("9")
            "ten" -> BigDecimal.TEN
            else -> amount?.toBigDecimalOrNull()
        }
        GroceryIntentReviewV2GroupMember(
            rowIndex = index,
            value = value,
            unit = if (value == null) "unspecified" else "count",
            origin = if (value == null) "not_stated" else "local",
        )
    },
    packaging = null,
    total = null,
    each = null,
)

private fun item(
    name: String,
    sourceText: String,
    quantity: Int? = null,
    quantityUnit: String = if (quantity == null) "unspecified" else "count",
    quantityText: String? = if (quantity == null) null else quantity.toString(),
    packValue: BigDecimal? = null,
    packUnit: String = if (packValue == null) "unspecified" else "g",
    packaging: String? = null,
    required: List<String> = emptyList(),
    excluded: List<String> = emptyList(),
    packText: String? = packValue?.toPlainString(),
) = GroceryIntentReviewV2Item(
    name = name,
    quantity = quantity?.let { BigDecimal.valueOf(it.toLong()) },
    quantityUnit = quantityUnit,
    packValue = packValue,
    packUnit = packUnit,
    packaging = packaging,
    required = required,
    excluded = excluded,
    sourceText = sourceText,
    supportingText = emptyList(),
    needsClarification = false,
    quantityText = quantityText,
    packText = packText,
)

private fun readyRow(
    instruction: String,
    index: Int,
    item: GroceryIntentReviewV2Item,
    supporting: List<GroceryIntentReviewV2SupportText> = emptyList(),
) = GroceryIntentReviewV2ReadyRow(
    rowIndex = index,
    item = item.copy(supportingText = supporting),
    evidence = GroceryIntentReviewV2Evidence(
        primary = quote(instruction, item.sourceText),
        supporting = supporting.map { GroceryIntentReviewV2SupportQuote(it.kind, quote(instruction, it.text)) },
    ),
    quantityOrigin = if (item.quantity == null) "not_stated" else "stated",
    amountRoleStatus = if (item.quantity == null && item.packValue == null) "not_stated" else "matched",
)

private fun blockedRow(instruction: String, index: Int, source: String) =
    GroceryIntentReviewV2BlockedRow(index, quote(instruction, source), listOf("model_needs_review"))

private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(text)
    require(start >= 0) { "Synthetic quote is not present in its instruction" }
    return GroceryIntentReviewV2Quote(text, start, start + text.length)
}
