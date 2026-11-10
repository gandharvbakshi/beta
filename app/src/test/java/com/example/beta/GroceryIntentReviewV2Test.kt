package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentQuantityOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewV2Test {
    @Test
    fun decodesExactV2AndUsesUtf16OffsetsAfterEmoji() {
        val instruction = "😀 one milk"
        val start = instruction.indexOf("one milk")
        val body = response(instruction, ready = listOf(readyRow(0, instruction, "one milk", start, quantity = "1", quantityUnit = "count", quantityText = "one")))

        val decoded = GroceryIntentReviewV2Response.parse(body, instruction).draft

        assertEquals("grocery-intent-v2", decoded.contractVersion)
        assertEquals(1, decoded.sourceItemCount)
        assertEquals("one milk", decoded.readyRows.single().evidence.primary.text)
        assertEquals(start, decoded.readyRows.single().evidence.primary.startUtf16)
        assertEquals("stated", decoded.readyRows.single().quantityOrigin)
        assertEquals("1", decoded.readyRows.single().item.quantity.toString())
    }

    @Test
    fun adjacentQuantityExclusionDoesNotChangeTheOriginalPurchaseQuantity() {
        val primary = "two boxes of cereal"
        listOf(", not one", " not 1 box").forEach { suffix ->
            val instruction = primary + suffix
            val row = readyRow(
                0, instruction, primary, instruction.indexOf(primary),
                quantity = "2", quantityUnit = "pack", quantityText = "two",
            ).replace("\"name\":\"milk\"", "\"name\":\"cereal\"")
                .replace("\"packaging\":null", "\"packaging\":\"box\"")
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(row)), instruction,
            ).draft

            val prepared = draft.prepareForReview()

            assertEquals(1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(Quantity.Count(2), item.quantity)
            assertTrue(item.rawText.startsWith(primary))
            assertTrue(item.rawText.endsWith("You also said: ${suffix.trimStart(',', ' ')}"))
            assertEquals(1, item.rawText.windowed("not ".length).count { it == "not " })
            assertTrue(prepared.unresolvedText.isEmpty())
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test
    fun croppedLeadingPurchaseAmountsCannotBecomeAnUnqualifiedWholeBasket() {
        listOf("a", "one", "do", "two").forEach { amount ->
            val instruction = "$amount milk"
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(readyRow(
                    0, instruction, "milk", instruction.indexOf("milk"),
                ))), instruction,
            ).draft
            val prepared = draft.prepareForReview()
            assertTrue("Cropped amount cannot create a default-count item: $amount", prepared.items.isEmpty())
            assertTrue("Cropped amount must remain visible for review: $amount",
                prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes,
                prepared.fulfillmentConstraints)
            assertFalse("Cropped quantity cannot bypass review", gate.canApply())
        }
    }

    @Test
    fun blockedPackContentsAmountCannotBeBorrowedByReadyProductRow(): Unit {
        val countTails = listOf("twenty five lozenges", "25 lozenges")
        countTails.forEach { countTail ->
            val instruction = "one BrandA lozenges pack, $countTail. one soap"
            val productSource = "one BrandA lozenges pack"
            val soapSource = "one soap"
            val countStart = instruction.indexOf(countTail)
            val blockedCount = """{"row_index":1,"source":{"text":${jsonString(countTail)},"start_utf16":$countStart,"end_utf16":${countStart + countTail.length}},"issue_codes":["quantity_omission_suspected"]}"""
            val productRow = readyRow(
                0, instruction, productSource, instruction.indexOf(productSource),
                quantity = "1", quantityUnit = "pack", quantityText = "one",
            ).replace("\"name\":\"milk\"", "\"name\":\"BrandA lozenges\"")
                .replace("\"packaging\":null", "\"packaging\":\"pack\"")
            val soapRow = readyRow(
                2, instruction, soapSource, instruction.indexOf(soapSource),
                quantity = "1", quantityUnit = "count", quantityText = "one",
            ).replace("\"name\":\"milk\"", "\"name\":\"soap\"")
            val readyCountTail = readyRow(
                1, instruction, countTail, countStart,
                quantity = "25", quantityUnit = "piece", quantityText = countTail.substringBeforeLast(" lozenges"),
            ).replace("\"name\":\"milk\"", "\"name\":\"lozenges\"")

            for (tailIsReady in listOf(false, true)) {
                val readyWithoutTail = listOf(productRow, soapRow)
                val readyWithTail = listOf(productRow, readyCountTail, soapRow)
                val sourceRows = if (tailIsReady) readyWithTail else readyWithoutTail
                for (reverseReadyRows in listOf(false, true)) {
                    val readyRows = if (reverseReadyRows) sourceRows.reversed() else sourceRows
                    val prepared = GroceryIntentReviewV2Response.parse(
                        response(
                            instruction,
                            ready = readyRows,
                            blocked = if (tailIsReady) emptyList() else listOf(blockedCount),
                        ), instruction,
                    ).draft.prepareForReview()

                    assertTrue("soap remains for $countTail ready=$tailIsReady", prepared.items.any { "soap" in it.query })
                    assertFalse("no lozenges product is accepted for $countTail ready=$tailIsReady", prepared.items.any {
                        "BrandA" in it.query || "lozenges" in it.query
                    })
                    assertTrue("count remains visible for $countTail ready=$tailIsReady",
                        (prepared.unresolvedText + prepared.excludedText).any { "lozenges" in it })
                }
            }
        }
    }

    @Test
    fun sharedBrandHeadingCannotTurnNumericOrPropertyFragmentsIntoBasketItems(): Unit {
        val numericInstruction = "BrandA: one lozenges pack, 25 lozenges"
        val numericRows = listOf(
            readyRow(0, numericInstruction, "one lozenges pack", numericInstruction.indexOf("one lozenges pack"),
                quantity = "1", quantityUnit = "pack", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"BrandA lozenges pack\"")
                .replace("\"packaging\":null", "\"packaging\":\"pack\""),
            readyRow(1, numericInstruction, "25 lozenges", numericInstruction.indexOf("25 lozenges"),
                quantity = "25", quantityUnit = "piece", quantityText = "25")
                .replace("\"name\":\"milk\"", "\"name\":\"lozenges\""),
        )
        val numericDraft = GroceryIntentReviewV2Response.parse(
            response(numericInstruction, ready = numericRows, groups = listOf(sharedGroup(numericInstruction, "BrandA:", listOf("1" to "pack", "25" to "piece")))),
            numericInstruction,
        ).draft
        val numericPrepared = numericDraft.prepareForReview()
        assertTrue("pack row cannot borrow its piece count from another row", numericPrepared.items.isEmpty())
        assertTrue((numericPrepared.unresolvedText + numericPrepared.excludedText).any { "lozenges" in it })

        val propertyInstruction = "BrandA: one cereal, no added sugar"
        val propertyRows = listOf(
            readyRow(0, propertyInstruction, "one cereal", propertyInstruction.indexOf("one cereal"),
                quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"BrandA cereal\"") ,
            readyRow(1, propertyInstruction, "no added sugar", propertyInstruction.indexOf("no added sugar"),
                required = listOf("no_added_sugar"))
                .replace("\"name\":\"milk\"", "\"name\":\"no added sugar\""),
        )
        val propertyDraft = GroceryIntentReviewV2Response.parse(
            response(propertyInstruction, ready = propertyRows, groups = listOf(sharedGroup(propertyInstruction, "BrandA:", listOf("1" to "count", null to "unspecified")))),
            propertyInstruction,
        ).draft
        val propertyPrepared = propertyDraft.prepareForReview()
        assertFalse("shared heading cannot convert a property pseudo-item or lend property to cereal",
            propertyPrepared.items.any { "cereal" in it.query || "no added sugar" in it.query })
        assertTrue((propertyPrepared.unresolvedText + propertyPrepared.excludedText).any { "cereal" in it })
    }

    @Test
    fun splitPurchaseCountsCannotBeBorrowedByUnstatedReadyProducts(): Unit {
        val cases = listOf("two milk", "one soap, two milk", "two packs of milk")
        cases.forEach { instruction ->
            val milkStart = instruction.lastIndexOf("milk")
            val countText = when (instruction) {
                "two milk" -> "two"
                "one soap, two milk" -> "two"
                else -> "two packs of"
            }
            val countStart = instruction.indexOf(countText)
            val blockedCount = """{"row_index":${if (instruction == "one soap, two milk") 1 else 0},"source":{"text":${jsonString(countText)},"start_utf16":$countStart,"end_utf16":${countStart + countText.length}},"issue_codes":["quantity_omission_suspected"]}"""
            val rows = buildList {
                if (instruction.startsWith("one soap")) {
                    add(readyRow(0, instruction, "one soap", instruction.indexOf("one soap"), quantity = "1", quantityUnit = "count", quantityText = "one")
                        .replace("\"name\":\"milk\"", "\"name\":\"soap\""))
                }
                add(readyRow(if (instruction.startsWith("one soap")) 2 else 1, instruction, "milk", milkStart)
                    .replace("\"name\":\"milk\"", "\"name\":\"milk\""))
            }
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = rows, blocked = listOf(blockedCount)), instruction,
            ).draft.prepareForReview()
            assertTrue("only independently grounded soap survives: $instruction",
                prepared.items.map { it.query }.any { "soap" in it } == instruction.startsWith("one soap"))
            assertFalse("unmentioned count cannot be borrowed by milk: $instruction",
                prepared.items.any { "milk" in it.query })
            assertTrue("milk remains visible for review: $instruction",
                (prepared.unresolvedText + prepared.excludedText).any { "milk" in it })
        }
    }

    @Test
    fun trailingBlockedPurchaseCountsCannotAttachToEarlierUnstatedProducts(): Unit {
        val cases = listOf(
            Triple("milk two", "milk", false),
            Triple("milk, two", "milk", false),
            Triple("milk two, soap", "milk", true),
        )
        cases.forEach { (instruction, milkSource, hasSoap) ->
            val milkStart = instruction.indexOf(milkSource)
            val twoStart = instruction.lastIndexOf("two")
            val blockedCount = """{"row_index":1,"source":{"text":"two","start_utf16":$twoStart,"end_utf16":${twoStart + 3}},"issue_codes":["quantity_omission_suspected"]}"""
            val rows = buildList {
                add(readyRow(0, instruction, milkSource, milkStart)
                    .replace("\"name\":\"milk\"", "\"name\":\"milk\""))
                if (hasSoap) add(readyRow(2, instruction, "soap", instruction.indexOf("soap"))
                    .replace("\"name\":\"milk\"", "\"name\":\"soap\""))
            }
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = rows, blocked = listOf(blockedCount)), instruction,
            ).draft.prepareForReview()
            assertFalse("trailing blocked quantity cannot attach to milk: $instruction", prepared.items.any { "milk" in it.query })
            assertEquals("soap remains independently grounded", hasSoap, prepared.items.any { "soap" in it.query })
        }

        val noBlockedInstruction = "milk two"
        val noBlockedRow = readyRow(0, noBlockedInstruction, "milk", 0)
            .replace("\"name\":\"milk\"", "\"name\":\"milk\"")
        val noBlockedPrepared = GroceryIntentReviewV2Response.parse(
            response(noBlockedInstruction, ready = listOf(noBlockedRow)), noBlockedInstruction,
        ).draft.prepareForReview()
        assertFalse("raw trailing count still prevents defaulting when backend omitted a blocked row",
            noBlockedPrepared.items.any { "milk" in it.query })
    }

    @Test
    fun blockedContainerCountFragmentsCannotAttachToUnstatedMilk(): Unit {
        listOf("cartons", "bags", "tubs", "tins", "cans", "sachets").forEach { container ->
            val instruction = "two $container milk"
            val countSource = "two $container"
            val countStart = instruction.indexOf(countSource)
            val milkStart = instruction.indexOf("milk")
            val blockedCount = """{"row_index":0,"source":{"text":${jsonString(countSource)},"start_utf16":$countStart,"end_utf16":${countStart + countSource.length}},"issue_codes":["quantity_omission_suspected"]}"""
            val milkRow = readyRow(1, instruction, "milk", milkStart)
                .replace("\"name\":\"milk\"", "\"name\":\"milk\"")
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(milkRow), blocked = listOf(blockedCount)), instruction,
            ).draft.prepareForReview()
            assertFalse("blocked '$countSource' cannot lend a count to milk", prepared.items.any { "milk" in it.query })
            assertTrue((prepared.unresolvedText + prepared.excludedText).any { "milk" in it })
        }
    }

    @Test
    fun reversedPackContentsAmountWordingCannotCreatePackAndContents(): Unit {
        listOf("lozenges 25", "lozenges twenty five").forEach { tail ->
            val instruction = "one BrandA lozenges pack, $tail. one soap"
            val packSource = "one BrandA lozenges pack"
            val tailStart = instruction.indexOf(tail)
            val packRow = readyRow(0, instruction, packSource, instruction.indexOf(packSource), quantity = "1", quantityUnit = "pack", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"BrandA lozenges pack\"")
                .replace("\"packaging\":null", "\"packaging\":\"pack\"")
            val tailText = tail.removePrefix("lozenges ")
            val tailRow = readyRow(1, instruction, tail, tailStart, quantity = "25", quantityUnit = "piece", quantityText = tailText)
                .replace("\"name\":\"milk\"", "\"name\":\"lozenges\"")
            val soapRow = readyRow(2, instruction, "one soap", instruction.indexOf("one soap"), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"soap\"")

            for (rows in listOf(listOf(packRow, tailRow, soapRow), listOf(soapRow, tailRow, packRow))) {
                val prepared = GroceryIntentReviewV2Response.parse(
                    response(instruction, ready = rows), instruction,
                ).draft.prepareForReview()
                assertTrue("soap remains for '$tail'", prepared.items.any { "soap" in it.query })
                assertFalse("reversed contents phrase cannot create pack or contents rows for '$tail'",
                    prepared.items.any { "lozenges" in it.query || "BrandA" in it.query })
                assertTrue((prepared.unresolvedText + prepared.excludedText).any { "lozenges" in it })
            }
        }
    }

    @Test
    fun countedPropertyOnlyFragmentsDoNotAttachToNeighboringProducts(): Unit {
        val counted = "one cereal, one no added sugar. one soap"
        val cereal = "one cereal"
        val property = "one no added sugar"
        val soap = "one soap"
        val countedRows = listOf(
            readyRow(0, counted, cereal, counted.indexOf(cereal), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"cereal\""),
            readyRow(1, counted, property, counted.indexOf(property), quantity = "1", quantityUnit = "count", quantityText = "one", required = listOf("no_added_sugar"))
                .replace("\"name\":\"milk\"", "\"name\":\"no added sugar\""),
            readyRow(2, counted, soap, counted.indexOf(soap), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"soap\""),
        )
        val countedPrepared = GroceryIntentReviewV2Response.parse(response(counted, ready = countedRows), counted)
            .draft.prepareForReview()
        assertTrue(countedPrepared.items.any { "soap" in it.query })
        assertFalse("numeric property pseudo-item cannot isolate a diet claim for cereal",
            countedPrepared.items.any { "cereal" in it.query || "no added sugar" in it.query })

        val prefix = "no added sugar, one cereal, one apple. one soap"
        val prefixProperty = "no added sugar"
        val prefixCereal = "one cereal"
        val apple = "one apple"
        val prefixRows = listOf(
            readyRow(0, prefix, prefixProperty, prefix.indexOf(prefixProperty), required = listOf("no_added_sugar"))
                .replace("\"name\":\"milk\"", "\"name\":\"no added sugar\""),
            readyRow(1, prefix, prefixCereal, prefix.indexOf(prefixCereal), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"cereal\""),
            readyRow(2, prefix, apple, prefix.indexOf(apple), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"apple\""),
            readyRow(3, prefix, soap, prefix.indexOf(soap), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"soap\""),
        )
        val prefixPrepared = GroceryIntentReviewV2Response.parse(response(prefix, ready = prefixRows), prefix)
            .draft.prepareForReview()
        assertTrue(prefixPrepared.items.any { "soap" in it.query })
        assertFalse("property-only prefix cannot taint later products across a full stop",
            prefixPrepared.items.any { "cereal" in it.query || "apple" in it.query || "no added sugar" in it.query })
    }

    @Test
    fun blockedPackRowCannotLendItsContentsCountToAnotherReadyRow(): Unit {
        val instruction = "one BrandA lozenges pack, 25 lozenges. one soap"
        val blockedSource = "one BrandA lozenges pack"
        val countSource = "25 lozenges"
        val soapSource = "one soap"
        val blockedStart = instruction.indexOf(blockedSource)
        val countStart = instruction.indexOf(countSource)
        val soapStart = instruction.indexOf(soapSource)
        val blockedProduct = """{"row_index":0,"source":{"text":${jsonString(blockedSource)},"start_utf16":$blockedStart,"end_utf16":${blockedStart + blockedSource.length}},"issue_codes":["model_needs_review"]}"""
        val countRow = readyRow(1, instruction, countSource, countStart, quantity = "25", quantityUnit = "piece", quantityText = "25")
            .replace("\"name\":\"milk\"", "\"name\":\"lozenges\"")
        val soapRow = readyRow(2, instruction, soapSource, soapStart, quantity = "1", quantityUnit = "count", quantityText = "one")
            .replace("\"name\":\"milk\"", "\"name\":\"soap\"")

        for (rows in listOf(listOf(countRow, soapRow), listOf(soapRow, countRow))) {
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = rows, blocked = listOf(blockedProduct)), instruction,
            ).draft.prepareForReview()
            assertTrue(prepared.items.any { "soap" in it.query })
            assertFalse(prepared.items.any { "lozenges" in it.query })
            assertTrue((prepared.unresolvedText + prepared.excludedText).any { "lozenges" in it })
        }
    }

    @Test
    fun blockedNumericTailCannotBeIsolatedByDifferentProductNouns(): Unit {
        listOf(
            "one herbal drops, 25 lozenges. one soap" to "25 lozenges",
            "one cough drops, twenty five lozenges. one soap" to "twenty five lozenges",
        ).forEach { (instruction, tail) ->
            val product = instruction.substringBefore(",")
            val soap = "one soap"
            val productRow = readyRow(0, instruction, product, instruction.indexOf(product),
                quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":${jsonString(product.removePrefix("one "))}")
            val soapRow = readyRow(2, instruction, soap, instruction.indexOf(soap),
                quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"soap\"")
            val tailStart = instruction.indexOf(tail)
            val blockedTail = """{"row_index":1,"source":{"text":${jsonString(tail)},"start_utf16":$tailStart,"end_utf16":${tailStart + tail.length}},"issue_codes":["quantity_omission_suspected"]}"""

            for (rows in listOf(listOf(productRow, soapRow), listOf(soapRow, productRow))) {
                val prepared = GroceryIntentReviewV2Response.parse(
                    response(instruction, ready = rows, blocked = listOf(blockedTail)), instruction,
                ).draft.prepareForReview()
                assertTrue("soap stays accepted for '$instruction'", prepared.items.any { "soap" in it.query })
                assertFalse("blocked numeric tail cannot authorize an item, pack, or merged product for '$instruction'",
                    prepared.items.any { "herbal" in it.query || "cough" in it.query || "lozenges" in it.query })
                assertTrue((prepared.unresolvedText + prepared.excludedText).any { "lozenges" in it })
            }
        }
    }

    @Test
    fun invalidReadyLabeledNumericSiblingCannotConferBoundaryToFirstProduct(): Unit {
        val instruction = "one herbal drops, 25 lozenges. one soap"
        val product = "one herbal drops"
        val tail = "25 lozenges"
        val soap = "one soap"
        val productRow = readyRow(0, instruction, product, instruction.indexOf(product),
            quantity = "1", quantityUnit = "count", quantityText = "one")
            .replace("\"name\":\"milk\"", "\"name\":\"herbal drops\"")
        val tailStart = instruction.indexOf(tail)
        val invalidTailRows = listOf(
            readyRow(1, instruction, tail, tailStart, quantity = "25", quantityUnit = "piece", quantityText = "25")
                .replace("\"name\":\"milk\"", "\"name\":\"vitamin tablets\""),
            readyRow(1, instruction, tail, tailStart, quantity = "25", quantityUnit = "piece", quantityText = "25", required = listOf("sugar_free"))
                .replace("\"name\":\"milk\"", "\"name\":\"lozenges\""),
        )
        val soapRow = readyRow(2, instruction, soap, instruction.indexOf(soap), quantity = "1", quantityUnit = "count", quantityText = "one")
            .replace("\"name\":\"milk\"", "\"name\":\"soap\"")

        invalidTailRows.forEach { invalidTail ->
            for (rows in listOf(listOf(productRow, invalidTail, soapRow), listOf(soapRow, invalidTail, productRow))) {
                val prepared = GroceryIntentReviewV2Response.parse(response(instruction, ready = rows), instruction)
                    .draft.prepareForReview()
                assertTrue("independent soap remains", prepared.items.any { "soap" in it.query })
                assertFalse("invalid ready-labeled sibling cannot confer a boundary to the first product",
                    prepared.items.any { "herbal drops" in it.query || "lozenges" in it.query || "vitamin tablets" in it.query })
                assertTrue((prepared.unresolvedText + prepared.excludedText).any { "herbal drops" in it })
            }
        }

        val independent = "one herbal drops; two soaps"
        val independentRows = listOf(
            readyRow(0, independent, "one herbal drops", independent.indexOf("one herbal drops"), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"herbal drops\""),
            readyRow(1, independent, "two soaps", independent.indexOf("two soaps"), quantity = "2", quantityUnit = "count", quantityText = "two")
                .replace("\"name\":\"milk\"", "\"name\":\"soaps\""),
        )
        val independentPrepared = GroceryIntentReviewV2Response.parse(response(independent, ready = independentRows), independent)
            .draft.prepareForReview()
        assertEquals(2, independentPrepared.items.size)
        assertTrue(independentPrepared.items.any { "herbal drops" in it.query })
        assertTrue(independentPrepared.items.any { "soap" in it.query })
    }

    @Test
    fun splitDietPropertyReachesStableHoldAndPreservesHardSeparatedProducts(): Unit {
        fun rowsFor(instruction: String): List<String> {
            val sources = listOf("one rice", "one cereal", "one milk", "no added sugar", "one soap")
            return sources.mapIndexed { index, source ->
                val required = if (source == "no added sugar") listOf("no_added_sugar") else emptyList()
                val quantity = if (source.startsWith("one ")) "1" else "null"
                val row = readyRow(
                    index, instruction, source, instruction.indexOf(source),
                    quantity = quantity,
                    quantityUnit = if (quantity == "1") "count" else "unspecified",
                    quantityText = "one".takeIf { quantity == "1" },
                    required = required,
                )
                val name = when (source) {
                    "one rice" -> "rice"
                    "one cereal" -> "cereal"
                    "one milk" -> "milk"
                    "no added sugar" -> "no added sugar"
                    else -> "soap"
                }
                row.replace("\"name\":\"milk\"", "\"name\":${jsonString(name)}")
            }
        }

        val commaInstruction = "one rice, one cereal, one milk, no added sugar. one soap"
        for (reverseRows in listOf(false, true)) {
            val rows = rowsFor(commaInstruction).let { if (reverseRows) it.reversed() else it }
            val prepared = GroceryIntentReviewV2Response.parse(
                response(commaInstruction, ready = rows), commaInstruction,
            ).draft.prepareForReview()
            assertEquals("only the independent soap is stable after fixed-point rejection",
                listOf("soap"), prepared.items.map { it.query })
            assertTrue((prepared.unresolvedText + prepared.excludedText).any { "rice" in it })
            assertTrue((prepared.unresolvedText + prepared.excludedText).any { "cereal" in it })
            assertTrue((prepared.unresolvedText + prepared.excludedText).any { "milk" in it })
        }

        val semicolonInstruction = "one rice, one cereal; one milk, no added sugar. one soap"
        val semicolonPrepared = GroceryIntentReviewV2Response.parse(
            response(semicolonInstruction, ready = rowsFor(semicolonInstruction)), semicolonInstruction,
        ).draft.prepareForReview()
        assertTrue("hard boundary preserves rice", semicolonPrepared.items.any { "rice" in it.query })
        assertTrue("hard boundary preserves cereal", semicolonPrepared.items.any { "cereal" in it.query })
        assertTrue("independent soap remains", semicolonPrepared.items.any { "soap" in it.query })
    }

    @Test
    fun hardBoundaryAndExplicitIndependentCountsRemainSeparateProducts(): Unit {
        listOf("one herbal drops; one soap", "one herbal drops\none soap").forEach { instruction ->
            val product = "one herbal drops"
            val soap = "one soap"
            val rows = listOf(
                readyRow(0, instruction, product, instruction.indexOf(product), quantity = "1", quantityUnit = "count", quantityText = "one")
                    .replace("\"name\":\"milk\"", "\"name\":\"herbal drops\""),
                readyRow(1, instruction, soap, instruction.indexOf(soap), quantity = "1", quantityUnit = "count", quantityText = "one")
                    .replace("\"name\":\"milk\"", "\"name\":\"soap\""),
            )
            val prepared = GroceryIntentReviewV2Response.parse(response(instruction, ready = rows), instruction)
                .draft.prepareForReview()
            assertEquals(2, prepared.items.size)
            assertTrue(prepared.items.any { "herbal drops" in it.query })
            assertTrue(prepared.items.any { "soap" in it.query })
        }

        val quantified = "one herbal drops, two soaps"
        val quantifiedRows = listOf(
            readyRow(0, quantified, "one herbal drops", quantified.indexOf("one herbal drops"), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"herbal drops\""),
            readyRow(1, quantified, "two soaps", quantified.indexOf("two soaps"), quantity = "2", quantityUnit = "count", quantityText = "two")
                .replace("\"name\":\"milk\"", "\"name\":\"soaps\""),
        )
        val quantifiedPrepared = GroceryIntentReviewV2Response.parse(response(quantified, ready = quantifiedRows), quantified)
            .draft.prepareForReview()
        assertEquals(2, quantifiedPrepared.items.size)
        assertTrue(quantifiedPrepared.items.any { "herbal drops" in it.query })
        assertTrue(quantifiedPrepared.items.any { "soap" in it.query })
    }

    @Test
    fun quantityExclusionCannotCoverSuffixWhenItsOwnerFailsPreflightGrounding() {
        val instruction = "two boxes of cereal, not one"
        val primary = "two boxes of cereal"
        val rowJson = readyRow(
            0, instruction, primary, instruction.indexOf(primary),
            quantity = "2", quantityUnit = "pack", quantityText = "two",
            required = listOf("sugar_free"),
        ).replace("\"name\":\"milk\"", "\"name\":\"cereal\"")
            .replace("\"packaging\":null", "\"packaging\":\"box\"")
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, ready = listOf(rowJson)), instruction,
        ).draft
        val owner = draft.readyRows.single()
        val provisionalReceipts = groceryIntentRestatementReceipts(
            instruction,
            listOf(GroceryIntentRestatementRow(
                rowIndex = owner.rowIndex,
                primary = owner.evidence.primary,
                quantity = owner.item.quantity,
                quantityUnit = owner.item.quantityUnit,
                packValue = owner.item.packValue,
                packUnit = owner.item.packUnit,
                packaging = owner.item.packaging,
                required = owner.item.required,
            )),
            listOf(owner.evidence.primary),
            allowQuantityExclusions = true,
        )
        assertEquals("quantity_exclusion", provisionalReceipts.single().kind)
        assertTrue(owner.groundingIssue(instruction = instruction) != null)

        val prepared = draft.prepareForReview()

        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf("not one"), prepared.unresolvedText)
        assertEquals(listOf(primary), prepared.excludedText)
    }

    @Test
    fun v2PropertyGroundingRequiresExactHighProteinAndSugarPropertyEvidence(): Unit {
        fun parseRows(instruction: String, specs: List<Triple<String, String, List<String>>>) =
            GroceryIntentReviewV2Response.parse(
                response(instruction, ready = specs.mapIndexed { index, (source, name, required) ->
                    val retailBar = Regex("\\bbar\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(source)
                    readyRow(
                        index, instruction, source, instruction.indexOf(source),
                        quantity = "1", quantityUnit = if (retailBar) "pack" else "count",
                        quantityText = "one", required = required,
                    ).replace("\"name\":\"milk\"", "\"name\":${jsonString(name)}")
                        .replace("\"packaging\":null", "\"packaging\":${if (retailBar) jsonString("bar") else "null"}")
                }),
                instruction,
            ).draft.readyRows

        val proteinCategory = "one Acme protein bar"
        val categoryRow = parseRows(proteinCategory, listOf(
            Triple(proteinCategory, "Acme protein bar", listOf("high_protein")),
        )).single()
        assertTrue(categoryRow.groundingIssue(instruction = proteinCategory) != null)

        val explicitProtein = "one Acme high protein bar"
        val explicitProteinRow = parseRows(explicitProtein, listOf(
            Triple(explicitProtein, "Acme high protein bar", listOf("high_protein")),
        )).single()
        assertNull(explicitProteinRow.groundingIssue(instruction = explicitProtein))

        val noAddedSugar = "one Meadow no added sugar yogurt"
        val noAddedSugarRow = parseRows(noAddedSugar, listOf(
            Triple(noAddedSugar, "Meadow no added sugar yogurt", listOf("no_added_sugar")),
        )).single()
        assertNull(noAddedSugarRow.groundingIssue(instruction = noAddedSugar))
        val inventedSugarFree = parseRows(noAddedSugar, listOf(
            Triple(noAddedSugar, "Meadow yogurt", listOf("sugar_free")),
        )).single()
        assertTrue(inventedSugarFree.groundingIssue(instruction = noAddedSugar) != null)

        val splitInstruction = "one high fibre biscuit, one Acme protein bar"
        val fibre = "one high fibre biscuit"
        val protein = "one Acme protein bar"
        val splitRows = parseRows(splitInstruction, listOf(
            Triple(fibre, "high fibre biscuit", listOf("high_fibre")),
            Triple(protein, "Acme protein bar", listOf("high_protein")),
        ))
        val allSources = listOf(0 to fibre, 1 to protein)
        assertNull(splitRows[0].groundingIssue(instruction = splitInstruction, allPrimarySources = allSources))
        assertTrue(splitRows[1].groundingIssue(
            instruction = splitInstruction,
            allPrimarySources = allSources,
        ) != null)
    }

    @Test
    fun adjacentPropertyFragmentsCannotBeReassembledIntoCerealAcrossRows(): Unit {
        val instruction = "one BrandA cereal, fruit and nut wala, no added sugar. one soap"
        data class Fragment(val source: String, val name: String, val required: List<String> = emptyList(), val role: String)

        for (threeFragments in listOf(false, true)) {
            for (propertyBlocked in listOf(false, true)) {
                for (reverseRows in listOf(false, true)) {
                    val fragments = buildList {
                        add(Fragment(
                            if (threeFragments) "one BrandA cereal" else "one BrandA cereal, fruit and nut wala",
                            if (threeFragments) "BrandA cereal" else "BrandA fruit and nut cereal",
                            role = "cereal",
                        ))
                        if (threeFragments) add(Fragment("fruit and nut wala", "fruit and nut cereal", role = "descriptor"))
                        add(Fragment("no added sugar", "no added sugar", listOf("no_added_sugar"), "property"))
                        add(Fragment("one soap", "soap", role = "soap"))
                    }
                    val ordered = if (reverseRows) fragments.reversed() else fragments
                    val readyRows = mutableListOf<String>()
                    val blockedRows = mutableListOf<String>()
                    ordered.forEachIndexed { rowIndex, fragment ->
                        val start = instruction.indexOf(fragment.source)
                        if (fragment.role == "property" && propertyBlocked) {
                            blockedRows += """{"row_index":$rowIndex,"source":{"text":${jsonString(fragment.source)},"start_utf16":$start,"end_utf16":${start + fragment.source.length}},"issue_codes":["model_needs_review"]}"""
                        } else {
                            val qty = if (fragment.source.startsWith("one ")) "1" else "null"
                            val row = readyRow(
                                rowIndex, instruction, fragment.source, start,
                                quantity = qty,
                                quantityUnit = if (qty == "1") "count" else "unspecified",
                                quantityText = "one".takeIf { qty == "1" },
                                required = fragment.required,
                            ).replace("\"name\":\"milk\"", "\"name\":${jsonString(fragment.name)}")
                            readyRows += row
                        }
                    }

                    val prepared = GroceryIntentReviewV2Response.parse(
                        response(instruction, ready = readyRows, blocked = blockedRows), instruction,
                    ).draft.prepareForReview()

                    val queries = prepared.items.map { it.query }
                    assertTrue("soap remains: three=$threeFragments blocked=$propertyBlocked reverse=$reverseRows", queries.any { "soap" in it })
                    assertFalse("cereal cannot borrow the split property", queries.any { "cereal" in it })
                    assertTrue((prepared.unresolvedText + prepared.excludedText).any { "cereal" in it })
                }
            }
        }
    }

    @Test
    fun propertyOnlyPrefixAndIndependentProductControlsKeepCerealSeparate(): Unit {
        val prefix = "no added sugar, one BrandA cereal, fruit and nut wala. one soap"
        val propertySource = "no added sugar"
        val cerealSource = "one BrandA cereal, fruit and nut wala"
        val soapSource = "one soap"
        val prefixRows = listOf(
            readyRow(0, prefix, propertySource, prefix.indexOf(propertySource), required = listOf("no_added_sugar"))
                .replace("\"name\":\"milk\"", "\"name\":\"no added sugar\""),
            readyRow(1, prefix, cerealSource, prefix.indexOf(cerealSource), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"BrandA fruit and nut cereal\""),
            readyRow(2, prefix, soapSource, prefix.indexOf(soapSource), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"soap\""),
        )
        val prefixPrepared = GroceryIntentReviewV2Response.parse(response(prefix, ready = prefixRows), prefix)
            .draft.prepareForReview()
        assertTrue(prefixPrepared.items.any { "soap" in it.query })
        assertFalse(prefixPrepared.items.any { "cereal" in it.query })

        val quantified = "one cereal, one no added sugar juice"
        val quantifiedRows = listOf(
            readyRow(0, quantified, "one cereal", quantified.indexOf("one cereal"), quantity = "1", quantityUnit = "count", quantityText = "one")
                .replace("\"name\":\"milk\"", "\"name\":\"cereal\""),
            readyRow(1, quantified, "one no added sugar juice", quantified.indexOf("one no added sugar juice"), quantity = "1", quantityUnit = "count", quantityText = "one", required = listOf("no_added_sugar"))
                .replace("\"name\":\"milk\"", "\"name\":\"no added sugar juice\""),
        )
        val quantifiedPrepared = GroceryIntentReviewV2Response.parse(response(quantified, ready = quantifiedRows), quantified)
            .draft.prepareForReview()
        assertEquals(2, quantifiedPrepared.items.size)
        assertTrue(quantifiedPrepared.items.any { "cereal" in it.query })
        assertTrue(quantifiedPrepared.items.any { "juice" in it.query && "no added sugar" in it.query })

        listOf("one cereal; no added sugar juice", "one cereal\nno added sugar juice").forEach { separated ->
            val cereal = "one cereal"
            val juice = "no added sugar juice"
            val rows = listOf(
                readyRow(0, separated, cereal, separated.indexOf(cereal), quantity = "1", quantityUnit = "count", quantityText = "one")
                    .replace("\"name\":\"milk\"", "\"name\":\"cereal\""),
                readyRow(1, separated, juice, separated.indexOf(juice), required = listOf("no_added_sugar"))
                    .replace("\"name\":\"milk\"", "\"name\":\"no added sugar juice\""),
            )
            val prepared = GroceryIntentReviewV2Response.parse(response(separated, ready = rows), separated)
                .draft.prepareForReview()
            assertTrue("cereal retained for hard boundary", prepared.items.any { "cereal" in it.query })
        }

        val milkEggs = "low fat milk, eggs"
        val milkEggRows = listOf(
            readyRow(0, milkEggs, "low fat milk", milkEggs.indexOf("low fat milk"), required = listOf("low_fat"))
                .replace("\"name\":\"milk\"", "\"name\":\"low fat milk\""),
            readyRow(1, milkEggs, "eggs", milkEggs.indexOf("eggs"))
                .replace("\"name\":\"milk\"", "\"name\":\"eggs\""),
        )
        val milkEggDraft = GroceryIntentReviewV2Response.parse(response(milkEggs, ready = milkEggRows), milkEggs).draft
        assertTrue(milkEggDraft.readyRows.all { it.item.quantity == null })
        val milkEggPrepared = milkEggDraft.prepareForReview()
        assertEquals(2, milkEggPrepared.items.size)
        assertTrue(milkEggPrepared.items.any { "milk" in it.query && "low fat" in it.query })
        assertTrue(milkEggPrepared.items.all { it.intentQuantityProvenance?.origin == IntentQuantityOrigin.UNSTATED })
        assertTrue(milkEggPrepared.items.any { "eggs" in it.query })
    }

    @Test
    fun preservesExplicitOneSeparatelyFromUnstatedQuantity(): Unit {
        val instruction = "one milk; milk"
        val first = instruction.indexOf("one milk")
        val second = instruction.lastIndexOf("milk")
        val decoded = GroceryIntentReviewV2Response.parse(
            response(instruction, ready = listOf(
                readyRow(0, instruction, "one milk", first, quantity = "1", quantityUnit = "count", quantityText = "one"),
                readyRow(1, instruction, "milk", second),
            )),
            instruction,
        ).draft

        assertEquals("stated", decoded.readyRows[0].quantityOrigin)
        assertEquals("1", decoded.readyRows[0].item.quantity.toString())
        assertEquals("not_stated", decoded.readyRows[1].quantityOrigin)
        assertNull(decoded.readyRows[1].item.quantity)
        assertEquals("not_stated", decoded.readyRows[1].amountRoleStatus)
    }

    @Test
    fun acceptsBlockedRowWithoutProductFieldsAndRetainsIssueCodes(): Unit {
        val instruction = "milk and maybe something"
        val readyStart = instruction.indexOf("milk")
        val decoded = GroceryIntentReviewV2Response.parse(
            response(
                instruction,
                ready = listOf(readyRow(0, instruction, "milk", readyStart)),
                blocked = listOf("""{"row_index":1,"source":null,"issue_codes":["quote_unresolved"]}"""),
            ),
            instruction,
        ).draft

        assertEquals(1, decoded.readyRows.size)
        assertEquals(1, decoded.blockedRows.size)
        assertEquals(listOf("quote_unresolved"), decoded.blockedRows.single().issueCodes)
        assertNull(decoded.blockedRows.single().source)
    }

    @Test
    fun rejectsBlockedRowWithProductOrActionFields(): Unit {
        val instruction = "milk and bread"
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(readyRow(0, instruction, "milk", instruction.indexOf("milk"))),
                    blocked = listOf("""{"row_index":1,"source":null,"issue_codes":["needs_review"],"item":{"name":"bread"}}""")),
                instruction,
            )
        }
    }

    @Test
    fun rowIndicesMustExactlyCoverSourceCountWithoutGapsOrDuplicates(): Unit {
        val instruction = "milk and bread"
        val milk = readyRow(0, instruction, "milk", instruction.indexOf("milk"))
        val bread = readyRow(1, instruction, "bread", instruction.indexOf("bread"))
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(milk), sourceCount = 2), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(milk, bread.replace("\"row_index\":1", "\"row_index\":0"))), instruction)
        }
    }

    @Test
    fun rejectsUnsupportedV1UnknownFieldsAndWrongOffsets(): Unit {
        val instruction = "milk"
        val valid = readyRow(0, instruction, "milk", 0)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid), version = "grocery-intent-v1"), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"amount_role_status\":\"not_stated\"", "\"amount_role_status\":\"not_stated\",\"action\":\"add\""))), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"start_utf16\":0", "\"start_utf16\":1"))), instruction)
        }
    }

    @Test
    fun supportEvidenceMustPreserveItemQuoteOrderAndExactOffsets(): Unit {
        val instruction = "skim milk; make skim milk plain; keep skim milk lactose-free"
        val source = "skim milk"
        val firstSupport = "make skim milk plain"
        val secondSupport = "keep skim milk lactose-free"
        val valid = readyRow(
            0, instruction, source, instruction.indexOf(source),
            supports = listOf("modifier" to firstSupport, "modifier" to secondSupport),
        )
        val draft = GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid)), instruction).draft
        assertEquals(listOf(firstSupport, secondSupport), draft.readyRows.single().evidence.supporting.map { it.quote.text })

        val reorderedEvidence = valid.replace(
            "\"text\":\"$firstSupport\",\"start_utf16\":${instruction.indexOf(firstSupport)},\"end_utf16\":${instruction.indexOf(firstSupport) + firstSupport.length}",
            "\"text\":\"$secondSupport\",\"start_utf16\":${instruction.indexOf(secondSupport)},\"end_utf16\":${instruction.indexOf(secondSupport) + secondSupport.length}",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(reorderedEvidence)), instruction)
        }
    }

    @Test
    fun validatesAmountOriginsAndStatusCoherenceWithoutDefaulting(): Unit {
        val instruction = "milk"
        val valid = readyRow(0, instruction, "milk", 0)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"quantity_origin\":\"not_stated\"", "\"quantity_origin\":\"stated\""))), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"quantity\":null", "\"quantity\":1"))), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"quantity\":null", "\"quantity\":21"))), instruction)
        }
        val unstated = GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid)), instruction).draft.readyRows.single()
        assertNull(unstated.item.quantity)
        assertEquals("not_stated", unstated.quantityOrigin)
    }

    @Test
    fun validatesPackagingAndExactAmountLexemes(): Unit {
        val instruction = "one 500 g bag rice"
        val start = instruction.indexOf(instruction)
        val valid = readyRow(
            0, instruction, instruction, start,
            quantity = "1", quantityUnit = "count", pack = "500", packUnit = "g",
            quantityText = "one", packText = "500",
        ).replace("\"packaging\":null", "\"packaging\":\"bag\"")
        val decoded = GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid)), instruction).draft
        assertEquals("bag", decoded.readyRows.single().item.packaging)
        assertEquals("500", decoded.readyRows.single().item.packValue.toString())
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"packaging\":\"bag\"", "\"packaging\":\"sack\""))), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(valid.replace("\"quantity_text\":\"one\"", "\"quantity_text\":null"))), instruction)
        }
    }

    @Test
    fun fullHalfAndQuarterArticleQuotesAreBoundToTheirPhysicalUnits(): Unit {
        val cases = listOf(
            Triple("half a kg tomatoes", "tomatoes", "kg") to ("0.5" to Quantity.Weight(500)),
            Triple("quarter a litre milk", "milk", "l") to ("0.25" to Quantity.Volume(250)),
        )
        cases.forEach { (case, expected) ->
            val (source, name, unit) = case
            val instruction = source
            val amountText = if (source.startsWith("half")) "half a" else "quarter a"
            val amount = expected.first
            val row = readyRow(
                0, instruction, source, instruction.indexOf(source), quantity = amount,
                quantityUnit = unit, quantityText = amountText,
            ).replace("\"name\":\"milk\"", "\"name\":${jsonString(name)}")

            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(row)), instruction,
            ).draft
            assertEquals(amountText, draft.readyRows.single().item.quantityText)
            val prepared = draft.prepareForReview()
            assertEquals(instruction, 1, prepared.items.size)
            assertEquals(expected.second, prepared.items.single().quantity)
            assertTrue(prepared.unresolvedText.isEmpty())
        }
    }

    @Test
    fun croppedHalfOrChangedAmountOrUnitCannotGroundThePhysicalQuantity(): Unit {
        val instruction = "half a kg tomatoes"
        fun row(quantity: String = "0.5", unit: String = "kg", quantityText: String = "half a") =
            readyRow(
                0, instruction, instruction, 0, quantity = quantity, quantityUnit = unit,
                quantityText = quantityText,
            ).replace("\"name\":\"milk\"", "\"name\":\"tomatoes\"")

        listOf(
            "cropped half" to row(quantityText = "half"),
            "changed numeric value" to row(quantity = "0.25"),
            "changed physical unit" to row(unit = "l"),
        ).forEach { (label, invalidRow) ->
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(invalidRow)), instruction,
            ).draft
            val prepared = draft.prepareForReview()
            assertTrue("$label must not produce a grounded item", prepared.items.isEmpty())
            assertTrue("$label must remain visible for review",
                instruction in prepared.unresolvedText || prepared.excludedText.any { instruction in it })
        }
    }

    @Test
    fun fullHalfAUnitQuoteProvesPerBottlePackSizeWithoutChangingBottleCount(): Unit {
        val instruction = "two half a litre bottles of water"
        val row = readyRow(
            0, instruction, instruction, 0,
            quantity = "2", quantityUnit = "pack", quantityText = "two",
            pack = "0.5", packUnit = "l", packText = "half a",
        ).replace("\"name\":\"milk\"", "\"name\":\"water\"")
            .replace("\"packaging\":null", "\"packaging\":\"bottle\"")

        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, ready = listOf(row)), instruction,
        ).draft
        val parsed = draft.readyRows.single().item
        assertEquals("2", parsed.quantity?.stripTrailingZeros()?.toPlainString())
        assertEquals("pack", parsed.quantityUnit)
        assertEquals("two", parsed.quantityText)
        assertEquals("0.5", parsed.packValue?.stripTrailingZeros()?.toPlainString())
        assertEquals("l", parsed.packUnit)
        assertEquals("half a", parsed.packText)

        val prepared = draft.prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(2), prepared.items.single().quantity)
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test
    fun croppedHalfOrMismatchedPackUnitCannotGroundThePerBottleSize(): Unit {
        val instruction = "two half a litre bottles of water"
        fun row(packUnit: String = "l", packText: String = "half a", packValue: String = "0.5") =
            readyRow(
                0, instruction, instruction, 0,
                quantity = "2", quantityUnit = "pack", quantityText = "two",
                pack = packValue, packUnit = packUnit, packText = packText,
            ).replace("\"name\":\"milk\"", "\"name\":\"water\"")
                .replace("\"packaging\":null", "\"packaging\":\"bottle\"")

        listOf(
            "cropped pack-size quote" to row(packText = "half"),
            "mismatched pack unit" to row(packUnit = "ml"),
            "changed pack-size value" to row(packValue = "0.25"),
        ).forEach { (label, invalidRow) ->
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(invalidRow)), instruction,
            ).draft
            val prepared = draft.prepareForReview()
            assertTrue("$label must not produce a grounded basket row", prepared.items.isEmpty())
            assertTrue("$label must remain visible for review",
                instruction in prepared.unresolvedText || prepared.excludedText.any { instruction in it })
        }
    }

    @Test
    fun wordInternalBrandInitialIsNotAUnitButLiteralLRemainsPhysical(): Unit {
        val brandSource = "one L\u2019Or\u00e9al shampoo bottle 200 ml"
        assertFalse(physicalUnitFollowsAt(brandSource, 0, "one", "l"))
        assertTrue(physicalUnitFollowsAt("one l shampoo", 0, "one", "l"))
        assertTrue(physicalUnitFollowsAt("2 l\u2019 milk", 0, "2", "l"))
        assertTrue(physicalUnitFollowsAt("200ml L\u2019Or\u00e9al shampoo", 0, "200", "ml"))
        assertEquals("ml", physicalUnitTokenFollowsAt("ml L\u2019Or\u00e9al shampoo", 0, "ml"))

        val valid = readyRow(
            0, brandSource, brandSource, 0,
            quantity = "1", quantityUnit = "pack", quantityText = "one",
            pack = "200", packUnit = "ml", packText = "200",
        ).replace("\"name\":\"milk\"", "\"name\":\"L\u2019Or\u00e9al shampoo\"")
            .replace("\"packaging\":null", "\"packaging\":\"bottle\"")
        val parsed = GroceryIntentReviewV2Response.parse(response(brandSource, ready = listOf(valid)), brandSource).draft
        val prepared = parsed.prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertEquals(brandSource, prepared.items.single().rawText)
        assertTrue(prepared.items.single().strictMatchPhrase?.contains("200 ml") == true)

        val falseLitre = valid.replace("\"quantity_unit\":\"pack\"", "\"quantity_unit\":\"l\"")
        val falseLitreDraft = GroceryIntentReviewV2Response.parse(
            response(brandSource, ready = listOf(falseLitre)), brandSource,
        ).draft
        assertTrue(falseLitreDraft.readyRows.single().groundingIssue(instruction = brandSource) != null)
        val falseLitrePreparation = falseLitreDraft.prepareForReview()
        assertTrue(falseLitrePreparation.items.isEmpty())
        assertTrue(brandSource in falseLitrePreparation.unresolvedText || brandSource in falseLitrePreparation.excludedText)

        val literalUnitSource = "one l shampoo"
        val countForgery = readyRow(
            0, literalUnitSource, literalUnitSource, 0,
            quantity = "1", quantityUnit = "count", quantityText = "one",
        ).replace("\"name\":\"milk\"", "\"name\":\"shampoo\"")
        val countForgeryDraft = GroceryIntentReviewV2Response.parse(
            response(literalUnitSource, ready = listOf(countForgery)), literalUnitSource,
        ).draft
        assertTrue(countForgeryDraft.readyRows.single().groundingIssue(instruction = literalUnitSource) != null)
        val countForgeryPreparation = countForgeryDraft.prepareForReview()
        assertTrue(countForgeryPreparation.items.isEmpty())
        assertTrue(literalUnitSource in countForgeryPreparation.unresolvedText ||
            literalUnitSource in countForgeryPreparation.excludedText)
    }

    @Test
    fun fulfillmentConstraintMustBeDerivedDeclaredAndDisjointFromPrimaryEvidence(): Unit {
        val instruction = "Please get these items tomorrow: milk"
        val source = "milk"
        val row = readyRow(0, instruction, source, instruction.indexOf(source))
        val constraint = constraintJson(instruction, "Please get these items tomorrow:")

        val decoded = GroceryIntentReviewV2Response.parse(
            response(instruction, ready = listOf(row), constraints = listOf(constraint)), instruction,
        ).draft
        val preparation = decoded.prepareForReview()
        assertEquals(listOf("Please get these items tomorrow:"), preparation.fulfillmentConstraints)
        assertFalse(preparation.unresolvedText.any { it.contains("get these items tomorrow") })

        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(row)), instruction)
        }

        val overlappingPrimary = readyRow(0, instruction, instruction, 0)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(overlappingPrimary), constraints = listOf(constraint)), instruction,
            )
        }
    }

    @Test
    fun objectConstructedReviewCannotDropAnIndependentlyDerivedConstraint(): Unit {
        val instruction = "Please get these items tomorrow: milk"
        val valid = GroceryIntentReviewV2Response.parse(
            response(instruction,
                ready = listOf(readyRow(0, instruction, "milk", instruction.indexOf("milk"))),
                constraints = listOf(constraintJson(instruction, "Please get these items tomorrow:"))),
            instruction,
        ).draft
        val changed = valid.copy(constraints = emptyList())
        assertThrows(IllegalArgumentException::class.java) { changed.prepareForReview() }
    }

    @Test
    fun sameSizeQuantityCorrectionKeepsOriginalPackAndExplicitReview(): Unit {
        for (note in listOf("same size", "same pack size")) {
            for (prefix in listOf("make water", "actually make the water", "sorry, make water", "no, make water")) {
                val primary = "2 250 ml bottles of water"
                val correction = "$prefix 3 bottles, $note"
                val instruction = "Please get $primary; $correction"
                val case = CorrectionCase(instruction, primary, "water", "pack", "2", "bottle", "250", "ml",
                    "quantity", correction, "3", "2", "pack", "3", "pack")
                val draft = GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(correctionRow(case))), instruction).draft
                assertEquals("250", draft.readyRows.single().item.packValue?.toPlainString())
                assertEquals("2", draft.readyRows.single().item.quantity?.toPlainString())
                assertEquals("3", draft.readyRows.single().corrections.single().newValue.toPlainString())
                val prepared = draft.prepareForReview()
                assertEquals(instruction, 1, prepared.items.size)
                assertEquals(Quantity.Count(3), prepared.items.single().quantity)
                assertTrue(instruction, prepared.unresolvedText.isEmpty())
                assertTrue(draft.requiresReview)
            }
        }
    }

    @Test
    fun repeatedNumericAtomSameSizeCorrectionKeepsCountAndPhysicalPackSeparate(): Unit {
        val primary = "2 2 l bottles of water"
        val correction = "make water 3 bottles, same size"
        val instruction = "$primary; $correction"
        val case = CorrectionCase(
            instruction, primary, "water", "pack", "2", "bottle", "2", "l",
            "quantity", correction, "3", "2", "pack", "3", "pack", packText = "2",
        )

        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, ready = listOf(correctionRow(case))), instruction,
        ).draft

        val row = draft.readyRows.single()
        assertEquals("2", row.item.quantity?.toPlainString())
        assertEquals("2", row.item.packValue?.toPlainString())
        assertEquals("l", row.item.packUnit)
        assertEquals("2", row.item.packText)
        assertEquals("quantity", row.corrections.single().kind)
        assertEquals("pack", row.corrections.single().oldUnit)
        assertEquals("pack", row.corrections.single().newUnit)

        val prepared = draft.prepareForReview()
        assertEquals(instruction, 1, prepared.items.size)
        assertEquals(Quantity.Count(3), prepared.items.single().quantity)
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test
    fun repeatedExplicitPackSizePreservesOriginalPackAcrossUnitsFractionsAndContainers(): Unit {
        val cases = listOf(
            CorrectionCase("1 750 ml bottle of water; make water 2 750 ml bottles", "1 750 ml bottle of water",
                "water", "count", "1", "bottle", "750", "ml", "quantity",
                "make water 2 750 ml bottles", "2", "1", "count", "2", "count", packText = "750"),
            CorrectionCase("2 1 kg bags of rice; make rice 3 1 kg bags", "2 1 kg bags of rice",
                "rice", "pack", "2", "bag", "1", "kg", "quantity",
                "make rice 3 1 kg bags", "3", "2", "pack", "3", "pack", packText = "1"),
            CorrectionCase("2 0.5 l bottles of juice; make juice 3 0.5 l bottles", "2 0.5 l bottles of juice",
                "juice", "pack", "2", "bottle", "0.5", "l", "quantity",
                "make juice 3 0.5 l bottles", "3", "2", "pack", "3", "pack", packText = "0.5"),
            CorrectionCase("2 500 g packets of flour; make flour 3 500 g packets", "2 500 g packets of flour",
                "flour", "pack", "2", "packet", "500", "g", "quantity",
                "make flour 3 500 g packets", "3", "2", "pack", "3", "pack", packText = "500"),
        )

        cases.forEach { case ->
            val draft = GroceryIntentReviewV2Response.parse(
                response(case.instruction, ready = listOf(correctionRow(case))), case.instruction,
            ).draft
            val row = draft.readyRows.single()
            val proof = row.corrections.single()
            assertTrue(case.instruction, proof.retainsPackSize)
            assertEquals(case.name, row.item.name)
            assertEquals(case.quantity, row.item.quantity?.stripTrailingZeros()?.toPlainString())
            assertEquals(case.packValue, row.item.packValue?.stripTrailingZeros()?.toPlainString())
            assertEquals(case.packUnit, row.item.packUnit)
            assertEquals(case.packText, row.item.packText)
            assertEquals(case.newValue, proof.newValue.stripTrailingZeros().toPlainString())

            val prepared = draft.prepareForReview()
            assertTrue(case.instruction, prepared.items.single().query.contains(case.name))
            assertEquals(case.instruction, expectedPreparedQuantity(case), prepared.items.single().quantity)
            assertTrue(case.instruction, prepared.unresolvedText.isEmpty())
        }
    }

    @Test
    fun physicalCorrectionPreservesExplicitSinglePackSelectionAndOriginalEvidence(): Unit {
        for (values in listOf(
            listOf("1 kg rice", "sorry, no, make rice 500 g, one 500 g packet", "500", "500", "g", "packet"),
            listOf("1 kg rice", "make rice 0.5 kg, one 500 g bag", "0.5", "0.5", "kg", "bag"),
            listOf("1 l oil", "make oil 500 ml, ek 0.5 l bottle", "500", "500", "ml", "bottle"),
            listOf("1 l oil", "change oil to 1 l, 1.0 1000 ml bottle", "1", "1", "l", "bottle"),
        )) {
            val (primary, text, amount, value, unit) = values
            val form = values[5]
            val oldUnit = if (primary.contains("kg")) "kg" else "l"
            val name = primary.substringAfterLast(' ')
            val case = CorrectionCase("$primary; $text", primary, name, oldUnit, "1", null, null,
                "unspecified", "quantity", text, amount, "1", oldUnit, value, unit)
            val draft = GroceryIntentReviewV2Response.parse(
                response(case.instruction, ready = listOf(correctionRow(case))), case.instruction).draft
            val row = draft.readyRows.single()
            assertEquals(form, row.corrections.single().singlePackSelection)
            assertEquals("1", row.item.quantity?.toPlainString())
            assertEquals(oldUnit, row.item.quantityUnit)
            assertNull(row.item.packValue)
            assertNull(row.item.packaging)
            val prepared = draft.prepareForReview()
            assertEquals(case.instruction, 1, prepared.items.size)
            val result = prepared.items.single()
            assertEquals(if (unit in setOf("g", "kg")) Quantity.Weight(500)
                else Quantity.Volume(if (unit == "l") 1000 else 500), result.quantity)
            assertTrue(result.query, result.query.contains(canonicalPackQueryValue(case)))
            assertTrue(result.query, result.query.contains(form))
            assertEquals(result.query, result.strictMatchPhrase)
            assertTrue(prepared.unresolvedText.isEmpty())
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(result.requiresIntentQuantityProof)
            assertNull(swiggyIntentQuantityIssue(result,
                SwiggyMcpClient.Recommendations(candidates = emptyList(), suggested = null)))
        }
    }

    @Test
    fun singlePackCorrectionNeverIgnoresCountSizeIdentityOrUnknownTail(): Unit {
        val primary = "1 kg rice"
        for (text in listOf(
            "make rice 500 g, two 500 g packets", "make rice 500 g, one 250 g packet",
            "make rice 500 g, one 500 ml bottle", "make rice 500 g, one 500 g packet if available",
            "make rice 500 g, one 500 g packet and one more", "make rice 500 g, one 500 g packet without salt",
            "make organic rice 500 g, one 500 g packet", "do not make rice 500 g, one 500 g packet",
            "make rice 500 g, one half 500 g packet", "make rice 500 g, one 500 g",
            "make rice 500 g, one 500 g packet or bag", "make rice 500 g, one 500 g packet tomorrow",
            "make 2 rice 500 g, one 500 g packet", "make 1 rice 500 g, one 500 g packet",
            "make two rice 500 g, one 500 g packet", "make one rice 500 g, one 500 g packet",
        )) {
            val case = CorrectionCase("$primary; $text", primary, "rice", "kg", "1", null, null,
                "unspecified", "quantity", text, "500", "1", "kg", "500", "g")
            val prepared = runCatching { GroceryIntentReviewV2Response.parse(
                response(case.instruction, ready = listOf(correctionRow(case))), case.instruction).draft.prepareForReview() }
            assertTrue(text, prepared.isFailure || prepared.getOrThrow().items.isEmpty())
        }
    }

    @Test
    fun countedListIntroductionRequiresRealCorrectionsAndKeepsWholeBasket(): Unit {
        val primary = "1 kg rice"
        val correction = "make rice 500 g, one 500 g packet"
        fun draft(prefix: String, includeCorrection: Boolean = true): GroceryIntentReviewV2 {
            val instruction = "$prefix $primary, 2 eggs; $correction"
            val case = CorrectionCase(instruction, primary, "rice", "kg", "1", null, null,
                "unspecified", "quantity", correction, "500", "1", "kg", "500", "g")
            val rice = if (includeCorrection) correctionRow(case) else readyRow(0, instruction, primary,
                instruction.indexOf(primary), quantity = "1", quantityUnit = "kg", quantityText = "1")
                .replace("\"name\":\"milk\"", "\"name\":\"rice\"")
            val eggs = readyRow(1, instruction, "2 eggs", instruction.indexOf("2 eggs"),
                quantity = "2", quantityUnit = "piece", quantityText = "2")
                .replace("\"name\":\"milk\"", "\"name\":\"eggs\"")
            return GroceryIntentReviewV2Response.parse(response(instruction,
                ready = listOf(rice, eggs), sourceCount = 2), instruction).draft
        }
        val prefix = "Mummy's list from the phone, one correction at the end:"
        val valid = draft(prefix)
        assertEquals("1", valid.readyRows.first().item.quantity?.toPlainString())
        val prepared = valid.prepareForReview()
        assertEquals(2, prepared.items.size)
        assertEquals(Quantity.Weight(500), prepared.items.first().quantity)
        assertEquals(Quantity.Count(2), prepared.items.last().quantity)
        assertTrue(prepared.items.first().query.contains("packet 500 g"))
        assertTrue(prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())
        val unsupported = listOf(prefix.replace("one correction", "two corrections"),
            prefix.replace("at the end", "at the end without sugar"),
            prefix.replace("Mummy's", "BrandA's"))
        unsupported.forEach { intro ->
            val check = runCatching { draft(intro).prepareForReview() }
            assertTrue(intro, check.isFailure || check.getOrThrow().unresolvedText.isNotEmpty())
        }
        val missing = runCatching { draft(prefix, includeCorrection = false).prepareForReview() }
        assertTrue(missing.isFailure || missing.getOrThrow().unresolvedText.isNotEmpty())
    }

    @Test
    fun numericCorrectionTargetKeepsCompleteDecimalAndCodeIdentity(): Unit {
        for ((name, unit, form) in listOf(Triple("1.5% fat milk", "l", "bottle"),
            Triple("Brand2 rice", "kg", "packet"))) {
            val primary = "1 $unit $name"
            val newUnit = if (unit == "l") "ml" else "g"
            val valid = "make $name 500 $newUnit, one 500 $newUnit $form"
            fun case(text: String) = CorrectionCase("$primary; $text", primary, name, unit, "1", null, null,
                "unspecified", "quantity", text, "500", "1", unit, "500", newUnit)
            fun parse(text: String): GroceryIntentReviewV2Response {
                val input = case(text)
                return GroceryIntentReviewV2Response.parse(
                    response(input.instruction, ready = listOf(correctionRow(input))), input.instruction)
            }
            val validDraft = parse(valid).draft
            val row = validDraft.readyRows.single()
            assertEquals(form, row.corrections.single().singlePackSelection)
            assertEquals(name, row.item.name)
            val prepared = validDraft.prepareForReview()
            assertEquals(valid, 1, prepared.items.size)
            assertTrue(prepared.items.single().query, prepared.items.single().query.contains(name, ignoreCase = true))
            val badNames = if (unit == "l") listOf("5.1% fat milk", "5% fat milk", "1% fat milk")
                else listOf("Brand20 rice", "rice Brand2", "2 rice")
            badNames.forEach { changed ->
                val text = "make $changed 500 $newUnit, one 500 $newUnit $form"
                val result = runCatching { parse(text).draft.prepareForReview() }
                assertTrue(text, result.isFailure || result.getOrThrow().items.isEmpty())
            }
        }
    }

    @Test
    fun singlePackProofCannotBeForgedOrBindRepeatedSizeAsCorrectedTotal(): Unit {
        val primary = "1 kg rice"
        val text = "make rice 500 g, one 500 g packet"
        val instruction = "$primary; $text"
        val case = CorrectionCase(instruction, primary, "rice", "kg", "1", null, null,
            "unspecified", "quantity", text, "500", "1", "kg", "500", "g")
        val row = correctionRow(case)
        val first = instruction.indexOf(text) + text.indexOf("500")
        val second = instruction.indexOf(text) + text.lastIndexOf("500")
        val forged = listOf(
            row.replace("\"new_unit\":\"g\"", "\"new_unit\":\"g\",\"single_pack_selection\":\"pack\""),
            row.replace("\"start_utf16\":$first,\"end_utf16\":${first + 3}",
                "\"start_utf16\":$second,\"end_utf16\":${second + 3}"),
        )
        forged.forEach { invalid -> assertTrue(runCatching {
            GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(invalid)), instruction)
        }.isFailure) }
    }

    @Test
    fun singlePackRequirementSurvivesCatalogueAndCartQuantityChecks(): Unit {
        val primary = "1 kg rice"
        val text = "make rice 500 g, one 500 g packet"
        val instruction = "$primary; $text"
        val case = CorrectionCase(instruction, primary, "rice", "kg", "1", null, null,
            "unspecified", "quantity", text, "500", "1", "kg", "500", "g")
        val item = GroceryIntentReviewV2Response.parse(response(instruction,
            ready = listOf(correctionRow(case))), instruction).draft.prepareForReview().items.single()
        fun candidate(title: String, id: String) = SwiggyMcpClient.RecommendationCandidate(
            spinId = id, skuId = id, label = title)
        val exact = candidate("Rice packet 500 g", "exact")
        val wrong = listOf(candidate("Rice packet 250 g", "small"), candidate("Rice packet 1 kg", "large"),
            candidate("Rice packet 2 x 250 g", "multipack"), candidate("Rice bag 500 g", "form"),
            candidate("Rice pack 500 g", "generic"))
        val assessed = assessSwiggyIntentQuantity(item,
            SwiggyMcpClient.Recommendations(candidates = listOf(exact) + wrong, suggested = exact))
        assertTrue(isSwiggyCandidateAllowed(assessed, exact))
        assertTrue(isSwiggyCandidateCountCompatible(assessed, exact))
        assertEquals(1, swiggyRequestedCartQuantity(assessed, exact))
        wrong.forEach { candidate -> assertTrue(candidate.label,
            !isSwiggyCandidateAllowed(assessed, candidate) || !isSwiggyCandidateCountCompatible(assessed, candidate)) }
        assertFalse(swiggyIntentQuantityCandidateChecked(assessed.copy(query = "rice"), exact))
    }

    @Test
    fun repeatedSizeRequiresExactOriginalAndRejectsTotalsQualifiersAndContainerChanges(): Unit {
        val primary = "1 750 ml bottle of water"
        fun case(suffix: String = "2 750 ml bottles", source: String = primary) = CorrectionCase(
            "$source; make water $suffix", source, "water", "count", "1", "bottle", "750", "ml",
            "quantity", "make water $suffix", "2", "1", "count", "2", "count", packText = "750",
        )
        val cases = listOf(
            case(suffix = "2 500 ml bottles"),
            case(source = "1 bottle of water"),
            case(suffix = "1500 ml total"),
            case(suffix = "2 bottles totaling 1500 ml"),
            case(suffix = "2 750 ml cartons"),
            case(suffix = "2 750 ml bottles if available"),
            case(suffix = "2 750 ml bottles, sugar free"),
            case(suffix = "2 750 ml bottles and 1 juice"),
            case(suffix = "2 750 ml bottles\nplease"),
        )
        cases.forEach { input ->
            assertThrows(input.instruction, IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(input.instruction, ready = listOf(correctionRow(input))), input.instruction,
                )
            }
        }
    }

    @Test
    fun repeatedSizeRejectsAmbiguousTargetsAndMissingOrAmbiguousOriginalPackFacts(): Unit {
        val primary = "1 750 ml bottle of water"
        val correction = "make water 2 750 ml bottles"
        val single = CorrectionCase("$primary; $correction", primary, "water", "count", "1", "bottle",
            "750", "ml", "quantity", correction, "2", "1", "count", "2", "count", packText = "750")
        val ambiguousInstruction = "$primary, 1 330 ml bottle of water; $correction"
        val ambiguous = single.copy(instruction = ambiguousInstruction)
        val ambiguousSecond = readyRow(
            1, ambiguousInstruction, "1 330 ml bottle of water", ambiguousInstruction.indexOf("1 330 ml bottle of water"),
            quantity = "1", quantityUnit = "count", quantityText = "1", pack = "330", packUnit = "ml", packText = "330",
        ).replace("\"name\":\"milk\"", "\"name\":\"water\"")
            .replace("\"packaging\":null", "\"packaging\":\"bottle\"")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(ambiguousInstruction, ready = listOf(correctionRow(ambiguous), ambiguousSecond)), ambiguousInstruction,
            )
        }

        val missingPack = single.copy(packValue = null, packText = null)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(missingPack.instruction, ready = listOf(correctionRow(missingPack))), missingPack.instruction,
            )
        }
        val wrongOldSize = single.copy(packValue = "500", packText = "500")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(wrongOldSize.instruction, ready = listOf(correctionRow(wrongOldSize))), wrongOldSize.instruction,
            )
        }
    }

    @Test
    fun repeatedSizeProofIsDerivedNotAcceptedFromWireAndConflictsWithPackSizeEdit(): Unit {
        val primary = "1 750 ml bottle of water"
        val quantityCorrection = "make water 2 750 ml bottles"
        val instruction = "$primary; $quantityCorrection"
        val quantityCase = CorrectionCase(instruction, primary, "water", "count", "1", "bottle", "750", "ml",
            "quantity", quantityCorrection, "2", "1", "count", "2", "count", packText = "750")
        val encoded = correctionRow(quantityCase)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(encoded.replace("\"new_unit\":\"count\"}",
                    "\"new_unit\":\"count\",\"retains_pack_size\":true}"))), instruction,
            )
        }

        val packCorrection = "change water pack size to 500 ml"
        val combinedInstruction = "$primary; $quantityCorrection; $packCorrection"
        val quantity = quantityCase.copy(instruction = combinedInstruction)
        val pack = quantity.copy(kind = "pack_size", correctionText = packCorrection, newAmount = "500",
            oldValue = "750", oldUnit = "ml", newValue = "500", newUnit = "ml")
        val quantityRow = correctionRow(quantity)
        val packRow = correctionRow(pack)
        val quantityProof = quantityRow.substringAfter(",\"corrections\":[").substringBeforeLast("]}")
        val packProof = packRow.substringAfter(",\"corrections\":[").substringBeforeLast("]}")
        val combinedRow = quantityRow.substringBefore(",\"corrections\":[") +
            ",\"corrections\":[" + quantityProof + "," + packProof + "]}"
        val error = assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(combinedInstruction, ready = listOf(combinedRow)), combinedInstruction,
            )
        }
        assertEquals("Unchanged-size reference conflicts with a size correction", error.message)
    }

    @Test
    fun provenCorrectionAfterBareDashGapKeepsWholeBasketReviewReady(): Unit {
        for (dash in listOf("—", "–", "‒", "-")) {
            val primary = "rice, one 500 g bag"
            val correction = "actually make that two 500 g bags"
            val instruction = "Arre, ek grocery note bana lo. $primary$dash$correction; 1 water. Bas itna hi, thank you."
            val case = CorrectionCase(instruction, primary, "rice", "pack", "1", "bag", "500", "g",
                "quantity", correction, "two", "1", "pack", "2", "pack", packText = "500", quantityText = "one")
            val water = readyRow(
                1, instruction, "1 water", instruction.indexOf("1 water"), quantity = "1",
                quantityUnit = "count", quantityText = "1",
            ).replace("\"name\":\"milk\"", "\"name\":\"water\"")
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(correctionRow(case), water)), instruction,
            ).draft

            assertEquals(instruction, 2, draft.readyRows.size)
            val riceRow = draft.readyRows.single { it.item.name == "rice" }
            assertEquals(instruction, "1", riceRow.item.quantity?.stripTrailingZeros()?.toPlainString())
            assertEquals(instruction, "500", riceRow.item.packValue?.stripTrailingZeros()?.toPlainString())
            assertEquals(instruction, 1.toBigDecimal(), riceRow.corrections.single().oldValue)
            assertEquals(instruction, 2.toBigDecimal(), riceRow.corrections.single().newValue)
            assertTrue(instruction, riceRow.corrections.single().retainsPackSize)

            val prepared = draft.prepareForReview()
            assertEquals(instruction, 2, prepared.items.size)
            assertEquals(instruction, Quantity.Count(2), prepared.items.first().quantity)
            assertTrue(instruction, prepared.unresolvedText.isEmpty())
        }
    }

    @Test
    fun unownedDashGapOrMissingOrInvalidCorrectionCannotMakeWholeBasketReady(): Unit {
        val primary = "rice, one 500 g bag"
        val goodCorrection = "actually make that two 500 g bags"
        val cases = listOf(
            Triple("$primary—tomorrow $goodCorrection", goodCorrection, true),
            Triple("$primary—actually make that two 400 g bags", "actually make that two 400 g bags", true),
            Triple("$primary—$goodCorrection", "", false),
        )
        cases.forEach { (body, correction, hasCorrection) ->
            val instruction = "Arre, ek grocery note bana lo. $body"
            val case = CorrectionCase(instruction, primary, "rice", "pack", "1", "bag", "500", "g",
                "quantity", correction, "two", "1", "pack", "2", "pack", packText = "500", quantityText = "one")
            val row = if (hasCorrection) correctionRow(case) else readyRow(
                0, instruction, primary, instruction.indexOf(primary), quantity = "1", quantityUnit = "pack",
                quantityText = "one", pack = "500", packUnit = "g", packText = "500",
            ).replace("\"name\":\"milk\"", "\"name\":\"rice\"")
                .replace("\"packaging\":null", "\"packaging\":\"bag\"")
            val parsed = runCatching {
                GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(row)), instruction)
                    .draft.prepareForReview()
            }
            parsed.fold(onSuccess = { prepared ->
                assertTrue(instruction, prepared.items.isEmpty())
                assertTrue(instruction, prepared.unresolvedText.isNotEmpty())
            }, onFailure = { error -> assertTrue(instruction, error is IllegalArgumentException) })
        }
    }

    @Test
    fun sameSizeQuantityCorrectionCannotConflictWithPackSizeCorrectionForSameTarget(): Unit {
        val primary = "2 2 l bottles of water"
        val quantityCorrection = "make water 3 bottles, same size"
        val packCorrection = "water 2 l ki jagah 1 l ke 2 bottles chahiye"
        val instruction = "$primary; $quantityCorrection; $packCorrection"
        val quantityCase = CorrectionCase(
            instruction, primary, "water", "pack", "2", "bottle", "2", "l",
            "quantity", quantityCorrection, "3", "2", "pack", "3", "pack", packText = "2",
        )
        val packCase = quantityCase.copy(
            kind = "pack_size", correctionText = packCorrection, newAmount = "1",
            oldValue = "2", oldUnit = "l", newValue = "1", newUnit = "l",
        )
        val quantityRow = correctionRow(quantityCase)
        val packRow = correctionRow(packCase)
        val quantityProof = quantityRow.substringAfter(",\"corrections\":[").substringBeforeLast("]}")
        val packProof = packRow.substringAfter(",\"corrections\":[").substringBeforeLast("]}")
        val combinedRow = quantityRow.substringBefore(",\"corrections\":[") +
            ",\"corrections\":[" + quantityProof + "," + packProof + "]}"

        val error = assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(combinedRow)), instruction,
            )
        }
        assertEquals("Unchanged-size reference conflicts with a size correction", error.message)
    }

    @Test
    fun sameSizeCorrectionIsNotAppliedAcrossTwoAmbiguousWaterTargets(): Unit {
        val first = "2 2 l bottles of water"
        val second = "1 1 l bottle of water"
        val correction = "make water 3 bottles, same size"
        val instruction = "$first, $second; $correction"
        val case = CorrectionCase(
            instruction, first, "water", "pack", "2", "bottle", "2", "l",
            "quantity", correction, "3", "2", "pack", "3", "pack", packText = "2",
        )
        val secondRow = readyRow(
            1, instruction, second, instruction.indexOf(second), quantity = "1", quantityUnit = "pack",
            quantityText = "1", pack = "1", packUnit = "l", packText = "1",
        ).replace("\"name\":\"milk\"", "\"name\":\"water\"")
            .replace("\"packaging\":null", "\"packaging\":\"bottle\"")

        val singleTargetInstruction = "$first; $correction"
        val singleTargetCase = case.copy(instruction = singleTargetInstruction)
        val singleTarget = GroceryIntentReviewV2Response.parse(
            response(singleTargetInstruction, ready = listOf(correctionRow(singleTargetCase))), singleTargetInstruction,
        ).draft.prepareForReview()
        assertEquals(1, singleTarget.items.size)
        assertEquals(Quantity.Count(3), singleTarget.items.single().quantity)

        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, ready = listOf(correctionRow(case), secondRow)), instruction,
            )
        }
    }

    @Test
    fun sameSizeDoesNotCertifyInventedSizesDifferentContainersOrAppendedInstructions(): Unit {
        val primary = "2 250 ml bottles of water"
        fun case(source: String = primary, suffix: String = "3 bottles, same size") = CorrectionCase(
            "$source; make water $suffix", source, "water", "pack", "2", "bottle", "250", "ml", "quantity",
            "make water $suffix", "3", "2", "pack", "3", "pack")
        val cases = listOf(
            case(source = "2 bottles of water"),
            case().copy(packValue = "500"),
            case().copy(packaging = "can"),
            case().copy(packUnit = "g"),
            case(suffix = "3 cans, same size"),
            case(suffix = "3 bottles, not same size"),
            case(suffix = "3 bottles, same size as the milk"),
            case(suffix = "3 bottles, same size, sugar free"),
            case(suffix = "3 bottles, same size or 4"),
        )
        cases.forEach { input ->
            assertThrows(input.instruction, IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(input.instruction, ready = listOf(correctionRow(input))), input.instruction)
            }
        }
    }

    @Test
    fun strictWireParserAcceptsSourceBoundHindiQuantityAndPackSizeCorrections(): Unit {
        val cases = listOf(
            CorrectionCase("1 low fat dahi tub no added sugar no palm oil 500 g; dahi 500 g ki jagah 400 g tub chahiye", "1 low fat dahi tub no added sugar no palm oil 500 g",
                "dahi", "pack", "1", "tub", "500", "g", "pack_size",
                "dahi 500 g ki jagah 400 g tub chahiye", "400", "500", "g", "400", "g",
                required = listOf("low_fat", "no_added_sugar"), excluded = listOf("palm_oil")),
            CorrectionCase("2 packet urad dal 1 kg; urad 1 kg nahi 500 g ke 2 packet karna", "2 packet urad dal 1 kg",
                "urad dal", "pack", "2", "packet", "1", "kg", "pack_size",
                "urad 1 kg nahi 500 g ke 2 packet karna", "500", "1", "kg", "500", "g", packText = "1"),
            CorrectionCase("2 packet urad dal 500 g; urad 500 g nahi 1 kg ke 2 packet karna", "2 packet urad dal 500 g",
                "urad dal", "pack", "2", "packet", "500", "g", "pack_size",
                "urad 500 g nahi 1 kg ke 2 packet karna", "1", "500", "g", "1", "kg"),
            CorrectionCase("1 paneer pack 200 g; paneer 200 nahi 400 g ka ek pack", "1 paneer pack 200 g",
                "paneer", "pack", "1", "pack", "200", "g", "pack_size",
                "paneer 200 nahi 400 g ka ek pack", "400", "200", "g", "400", "g"),
            CorrectionCase("1 kg toor dal; toor dal ko 2 kilo kar do", "1 kg toor dal",
                "toor dal", "kg", "1", null, null, "unspecified", "quantity",
                "toor dal ko 2 kilo kar do", "2", "1", "kg", "2", "kg"),
        )

        cases.forEach { case ->
            val draft = GroceryIntentReviewV2Response.parse(
                response(case.instruction, ready = listOf(correctionRow(case))), case.instruction,
            ).draft

            val row = draft.readyRows.single()
            val proof = row.corrections.single()
            assertEquals(case.kind, proof.kind)
            assertEquals(case.oldValue, proof.oldValue.stripTrailingZeros().toPlainString())
            assertEquals(case.oldUnit, proof.oldUnit)
            assertEquals(case.newValue, proof.newValue.stripTrailingZeros().toPlainString())
            assertEquals(case.newUnit, proof.newUnit)
            assertEquals(case.quantity, row.item.quantity?.stripTrailingZeros()?.toPlainString())
            assertEquals(case.packValue, row.item.packValue?.stripTrailingZeros()?.toPlainString())
            assertEquals(case.packaging, row.item.packaging)
            assertEquals(case.required, row.item.required)
            assertEquals(case.excluded, row.item.excluded)

            val prepared = draft.prepareForReview()
            assertEquals(case.instruction, 1, prepared.items.size)
            val preparedItem = prepared.items.single()
            assertEquals(case.instruction, expectedPreparedQuantity(case), preparedItem.quantity)
            assertTrue(case.instruction, prepared.unresolvedText.isEmpty())
            assertTrue(case.instruction, prepared.excludedText.isEmpty())
            val oldReceiptAmount = "${proof.oldValue.stripTrailingZeros().toPlainString()} ${proof.oldUnit}"
            val newReceiptAmount = "${proof.newValue.stripTrailingZeros().toPlainString()} ${proof.newUnit}"
            assertTrue(case.instruction, preparedItem.rawText.contains("changed: $oldReceiptAmount → $newReceiptAmount"))
            if (case.kind == "pack_size") {
                assertTrue(case.instruction, preparedItem.query.contains(canonicalPackQueryValue(case)))
            }
            assertTrue(case.instruction, case.required.map { it.replace('_', ' ') }.all { preparedItem.query.contains(it) })
            assertEquals(case.instruction, case.excluded.map { it.replace('_', ' ') }, preparedItem.avoidPhrases)
        }
    }

    @Test
    fun strictWireParserRejectsUnsafeOrAmbiguousHindiCorrectionReceipts(): Unit {
        val wrongOld = CorrectionCase("1 dahi tub 500 g; dahi 1 kg nahi 400 g tub chahiye", "1 dahi tub 500 g",
            "dahi", "pack", "1", "tub", "500", "g", "pack_size",
            "dahi 1 kg nahi 400 g tub chahiye", "400", "1000", "g", "400", "g")
        val wrongNew = CorrectionCase("1 dahi tub 500 g; dahi 500 g ki jagah 400 g tub chahiye", "1 dahi tub 500 g",
            "dahi", "pack", "1", "tub", "500", "g", "pack_size",
            "dahi 500 g ki jagah 400 g tub chahiye", "400", "500", "g", "500", "g")
        listOf(wrongOld, wrongNew).forEach { case ->
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(case.instruction, ready = listOf(correctionRow(case))), case.instruction)
            }
        }

        val changedCount = CorrectionCase("2 packet urad dal 1 kg; urad 1 kg nahi 500 g ke 1 packet karna", "2 packet urad dal 1 kg",
            "urad dal", "pack", "2", "packet", "1", "kg", "pack_size",
            "urad 1 kg nahi 500 g ke 1 packet karna", "500", "1", "kg", "500", "g", packText = "1")
        val missingRepeatedCount = CorrectionCase("2 packet urad dal 500 g; urad 500 g nahi 1 kg ka pack karna", "2 packet urad dal 500 g",
            "urad dal", "pack", "2", "packet", "500", "g", "pack_size",
            "urad 500 g nahi 1 kg ka pack karna", "1", "500", "g", "1", "kg")
        val physicalTotalBase = CorrectionCase("1 kg dal 500 g pack; dal 500 g ki jagah 1 kg pack chahiye", "1 kg dal 500 g pack",
            "dal", "kg", "1", "pack", "500", "g", "pack_size",
            "dal 500 g ki jagah 1 kg pack chahiye", "1", "500", "g", "1", "kg")
        val unknownBaseCount = CorrectionCase("urad dal 500 g packet; urad 500 g nahi 1 kg ka pack karna", "urad dal 500 g packet",
            "urad dal", "unspecified", "null", "packet", "500", "g", "pack_size",
            "urad 500 g nahi 1 kg ka pack karna", "1", "500", "g", "1", "kg")
        val wrongContainer = CorrectionCase("1 dahi tub 500 g; dahi 500 g ki jagah 400 g bottle chahiye", "1 dahi tub 500 g",
            "dahi", "pack", "1", "tub", "500", "g", "pack_size",
            "dahi 500 g ki jagah 400 g bottle chahiye", "400", "500", "g", "400", "g")
        val unknownSuffix = CorrectionCase("1 dahi tub 500 g; dahi 500 g ki jagah 400 g family pack chahiye", "1 dahi tub 500 g",
            "dahi", "pack", "1", "tub", "500", "g", "pack_size",
            "dahi 500 g ki jagah 400 g family pack chahiye", "400", "500", "g", "400", "g")
        val dietAddition = CorrectionCase("1 paneer pack 200 g; paneer 200 nahi 400 g ka ek pack unsweetened chahiye", "1 paneer pack 200 g",
            "paneer", "pack", "1", "pack", "200", "g", "pack_size",
            "paneer 200 nahi 400 g ka ek pack unsweetened chahiye", "400", "200", "g", "400", "g")
        val noOldContrast = CorrectionCase("aata 10 kilo bag; aur aata 5 kilo bag kar dena", "aata 10 kilo bag",
            "aata", "unspecified", "null", "bag", "10", "kg", "pack_size",
            "aur aata 5 kilo bag kar dena", "5", "10", "kg", "5", "kg", packText = "10")
        listOf(changedCount, missingRepeatedCount, physicalTotalBase, unknownBaseCount,
            wrongContainer, unknownSuffix, dietAddition, noOldContrast).forEach { case ->
            val parsed = try {
                GroceryIntentReviewV2Response.parse(
                    response(case.instruction, ready = listOf(correctionRow(case))), case.instruction,
                ).draft
            } catch (_: IllegalArgumentException) {
                null
            }
            if (parsed != null) assertTrue(parsed.prepareForReview().items.isEmpty())
        }

        val ambiguous = "1 dahi tub 500 g, 1 dahi cup 200 g; dahi 500 g ki jagah 400 g tub chahiye"
        val ambiguousRow = correctionRow(CorrectionCase(ambiguous, "1 dahi tub 500 g", "dahi", "pack", "1", "tub",
            "500", "g", "pack_size", "dahi 500 g ki jagah 400 g tub chahiye", "400", "500", "g", "400", "g"))
        val secondRow = readyRow(1, ambiguous, "1 dahi cup 200 g", ambiguous.indexOf("1 dahi cup 200 g"),
            quantity = "1", quantityUnit = "pack", quantityText = "1", pack = "200", packUnit = "g", packText = "200")
            .replace("\"name\":\"milk\"", "\"name\":\"dahi\"")
            .replace("\"packaging\":null", "\"packaging\":\"cup\"")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(ambiguous, ready = listOf(ambiguousRow, secondRow)), ambiguous)
        }

        val equalAmountAmbiguity = CorrectionCase("1 pack dal 1 kg; dal 1 nahi 500 g ka pack karna", "1 pack dal 1 kg",
            "dal", "pack", "1", "pack", "1", "kg", "pack_size",
            "dal 1 nahi 500 g ka pack karna", "500", "1", "kg", "500", "g")
        val equalAmount = try {
            GroceryIntentReviewV2Response.parse(
                response(equalAmountAmbiguity.instruction, ready = listOf(correctionRow(equalAmountAmbiguity))), equalAmountAmbiguity.instruction,
            ).draft
        } catch (_: IllegalArgumentException) {
            null
        }
        if (equalAmount != null) assertTrue(equalAmount.prepareForReview().items.isEmpty())

        val sameQuantityAndPack = CorrectionCase("500 g dal pack 500 g; dal 500 g ki jagah 400 g pack chahiye",
            "500 g dal pack 500 g", "dal", "g", "500", "pack", "500", "g", "pack_size",
            "dal 500 g ki jagah 400 g pack chahiye", "400", "500", "g", "400", "g")
        val sameAmount = try {
            GroceryIntentReviewV2Response.parse(
                response(sameQuantityAndPack.instruction, ready = listOf(correctionRow(sameQuantityAndPack))), sameQuantityAndPack.instruction,
            ).draft
        } catch (_: IllegalArgumentException) {
            null
        }
        if (sameAmount != null) assertTrue(sameAmount.prepareForReview().items.isEmpty())
    }

    @Test
    fun omittedHindiCorrectionReceiptAlsoRevokesUnrelatedRows(): Unit {
        val cases = listOf(
            Triple(
                "1 dahi tub 500 g, 2 milk; dahi 500 g ki jagah 400 g tub chahiye",
                readyRow(0, "1 dahi tub 500 g, 2 milk; dahi 500 g ki jagah 400 g tub chahiye", "1 dahi tub 500 g", 0,
                    quantity = "1", quantityUnit = "pack", quantityText = "1", pack = "500", packUnit = "g", packText = "500")
                    .replace("\"name\":\"milk\"", "\"name\":\"dahi\"")
                    .replace("\"packaging\":null", "\"packaging\":\"tub\""),
                readyRow(1, "1 dahi tub 500 g, 2 milk; dahi 500 g ki jagah 400 g tub chahiye", "2 milk", 18,
                    quantity = "2", quantityUnit = "count", quantityText = "2"),
            ),
            Triple(
                "1 dahi tub 500 g, 2 milk; dahi 500 g ke bajay 400 g tub chahiye",
                readyRow(0, "1 dahi tub 500 g, 2 milk; dahi 500 g ke bajay 400 g tub chahiye", "1 dahi tub 500 g", 0,
                    quantity = "1", quantityUnit = "pack", quantityText = "1", pack = "500", packUnit = "g", packText = "500")
                    .replace("\"name\":\"milk\"", "\"name\":\"dahi\"")
                    .replace("\"packaging\":null", "\"packaging\":\"tub\""),
                readyRow(1, "1 dahi tub 500 g, 2 milk; dahi 500 g ke bajay 400 g tub chahiye", "2 milk", 18,
                    quantity = "2", quantityUnit = "count", quantityText = "2"),
            ),
            Triple(
                "1 kg toor dal, 2 milk; toor dal ko 2 kilo kar do",
                readyRow(0, "1 kg toor dal, 2 milk; toor dal ko 2 kilo kar do", "1 kg toor dal", 0,
                    quantity = "1", quantityUnit = "kg", quantityText = "1")
                    .replace("\"name\":\"milk\"", "\"name\":\"toor dal\""),
                readyRow(1, "1 kg toor dal, 2 milk; toor dal ko 2 kilo kar do", "2 milk", 15,
                    quantity = "2", quantityUnit = "count", quantityText = "2"),
            ),
        )
        cases.forEach { (instruction, first, second) ->
            val draft = GroceryIntentReviewV2Response.parse(response(instruction, ready = listOf(first, second)), instruction).draft
            assertTrue(draft.prepareForReview().items.isEmpty())
        }
    }

    @Test
    fun strictWirePackCorrectionNeedsOriginalPhysicalTotalRoleEvidence(): Unit {
        fun correctedCase(source: String): CorrectionCase {
            val correction = "change milk pack size to 500 ml"
            val instruction = "$source; $correction"
            return CorrectionCase(
                instruction, source, "milk", "l", "1", "pack", "1000", "ml",
                "pack_size", correction, "500", "1000", "ml", "500", "ml",
            )
        }

        val ambiguous = correctedCase("1 litre milk 1000 ml pack")
        val ambiguousDraft = GroceryIntentReviewV2Response.parse(
            response(ambiguous.instruction, ready = listOf(correctionRow(ambiguous))), ambiguous.instruction,
        ).draft.prepareForReview()
        assertTrue(ambiguousDraft.items.isEmpty())
        val ambiguousVisible = ambiguousDraft.unresolvedText + ambiguousDraft.excludedText
        assertTrue(ambiguousVisible.any { it.contains(ambiguous.primary) })
        assertTrue(ambiguousVisible.any { it.contains("change milk pack size to 500 ml") })

        val explicit = correctedCase("total 1 litre milk 1000 ml pack")
        val accepted = GroceryIntentReviewV2Response.parse(
            response(explicit.instruction, ready = listOf(correctionRow(explicit))), explicit.instruction,
        ).draft.prepareForReview()
        assertEquals(1, accepted.items.size)
        assertEquals(Quantity.Volume(1000), accepted.items.single().quantity)
        assertTrue(accepted.items.single().query.contains("500 ml"))
        assertTrue(accepted.items.single().rawText.contains("1000 ml"))
        assertTrue(accepted.items.single().rawText.contains("500 ml"))
        assertTrue(accepted.unresolvedText.isEmpty())
        assertTrue(accepted.excludedText.isEmpty())
    }

    @Test
    fun numericHindiCueGuardRequiresAnAmount(): Unit {
        assertFalse(intentV2HasCorrectionCue("sab ko cart mein add kar do"))
        assertTrue(intentV2HasCorrectionCue("dal ko dedh kilo kar do"))
    }

    @Test
    fun namedCorrectionsComposeAcrossOwnedConnectiveThroughStrictDtoAndPreparation(): Unit {
        for (heading in listOf("", "Sun, bazaar se ghar ka list: ")) for (gap in listOf(" aur ", "; aur ", ", and ")) {
            val first = "dahi 500 g nahi 400 g tub chahiye"
            val second = "atta ko 3 kilo kar do"
            val text = heading + "1 dahi tub 500 g, 2 kilo atta; " + first + gap + second
            val a = CorrectionCase(text, "1 dahi tub 500 g", "dahi", "pack", "1", "tub", "500", "g",
                "pack_size", first, "400", "500", "g", "400", "g")
            val b = CorrectionCase(text, "2 kilo atta", "atta", "kg", "2", null, null, "unspecified",
                "quantity", second, "3", "2", "kg", "3", "kg")
            val prepared = GroceryIntentReviewV2Response.parse(response(text,
                ready = listOf(correctionRow(a), correctionRow(b, 1))), text).draft.prepareForReview()
            assertEquals(text, 2, prepared.items.size)
            assertTrue(text, prepared.unresolvedText.isEmpty())
            assertTrue(text, prepared.excludedText.isEmpty())
            assertEquals(Quantity.Count(1), prepared.items[0].quantity)
            assertTrue(prepared.items[0].query.contains("400 g"))
            assertEquals(Quantity.Weight(3000), prepared.items[1].quantity)
        }
    }

    @Test
    fun connectedCorrectionsLoseCoverageWhenNeighborFailsLateGrounding(): Unit {
        val first = "dahi 500 g nahi 400 g tub chahiye"
        val second = "atta ko 3 kilo kar do"
        val text = "1 dahi tub 500 g, 2 kilo atta; $first aur $second"
        val a = CorrectionCase(text, "1 dahi tub 500 g", "dahi", "pack", "1", "tub", "500", "g",
            "pack_size", first, "400", "500", "g", "400", "g")
        val b = CorrectionCase(text, "2 kilo atta", "atta chocolate", "kg", "2", null, null, "unspecified",
            "quantity", second, "3", "2", "kg", "3", "kg")
        val prepared = GroceryIntentReviewV2Response.parse(response(text,
            ready = listOf(correctionRow(a), correctionRow(b, 1))), text).draft.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(text), prepared.unresolvedText)
    }

    @Test
    fun bareHindiDoBeforeAurCannotBeMistakenForActionVerb(): Unit {
        val left = "make milk do"
        val right = "atta ko 3 kilo kar do"
        val text = "1 milk, 2 kilo atta; $left aur $right"
        val a = CorrectionCase(text, "1 milk", "milk", "count", "1", null, null, "unspecified",
            "quantity", left, "do", "1", "count", "2", "count")
        val b = CorrectionCase(text, "2 kilo atta", "atta", "kg", "2", null, null, "unspecified",
            "quantity", right, "3", "2", "kg", "3", "kg")
        val prepared = GroceryIntentReviewV2Response.parse(response(text,
            ready = listOf(correctionRow(a), correctionRow(b, 1))), text).draft.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(text), prepared.unresolvedText)
    }

    private fun response(
        instruction: String,
        ready: List<String>,
        blocked: List<String> = emptyList(),
        sourceCount: Int = ready.size + blocked.size,
        version: String = "grocery-intent-v2",
        constraints: List<String> = emptyList(),
        groups: List<String> = emptyList(),
    ): String {
        val constraintsField = if (constraints.isEmpty()) "" else ",\"constraints\":[${constraints.joinToString(",")}]"
        val groupsField = if (groups.isEmpty()) "" else ",\"groups\":[${groups.joinToString(",")}]"
        return """{"accepted":true,"draft":{"contract_version":"$version","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":$sourceCount,"ready_rows":[${ready.joinToString(",")}],"blocked_rows":[${blocked.joinToString(",")}]$constraintsField$groupsField},"model":"test","promptVersion":"v2-test","latencyMs":1,"usage":{}}"""
    }

    private fun sharedGroup(instruction: String, header: String, members: List<Pair<String?, String>>): String {
        val sourceStart = instruction.indexOf(header)
        val sourceEnd = instruction.length
        val headerEnd = sourceStart + header.length
        val memberJson = members.mapIndexed { index, (value, unit) ->
            """{"row_index":$index,"value":${value ?: "null"},"unit":${jsonString(unit)},"origin":"${if (value == null) "not_stated" else "local"}"}"""
        }
        val amountStatus = if (members.all { it.first != null }) "local_amounts_verified" else "amounts_unspecified"
        return """{"status":"structurally_verified","amount_status":"$amountStatus","source":{"text":${jsonString(instruction.substring(sourceStart, sourceEnd))},"start_utf16":$sourceStart,"end_utf16":$sourceEnd},"header":{"text":${jsonString(header)},"start_utf16":$sourceStart,"end_utf16":$headerEnd},"member_indices":[${members.indices.joinToString(",")}],"members":[${memberJson.joinToString(",")}],"packaging":null,"total":null,"each":null}"""
    }

    private fun constraintJson(instruction: String, text: String): String {
        val start = instruction.indexOf(text)
        return """{"kind":"fulfillment","source":{"text":${jsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}}"""
    }

    private fun readyRow(
        index: Int,
        instruction: String,
        source: String,
        start: Int,
        quantity: String = "null",
        quantityUnit: String = "unspecified",
        quantityText: String? = null,
        pack: String = "null",
        packUnit: String = "unspecified",
        packText: String? = null,
        supports: List<Pair<String, String>> = emptyList(),
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
    ): String {
        val end = start + source.length
        val quantityTextJson = quantityText?.let(::jsonString) ?: "null"
        val packTextJson = packText?.let(::jsonString) ?: "null"
        val supportTextJson = supports.joinToString(",") { (kind, text) -> """{"kind":${jsonString(kind)},"text":${jsonString(text)}}""" }
        val supportEvidenceJson = supports.joinToString(",") { (kind, text) ->
            val supportStart = instruction.indexOf(text)
            val supportEnd = supportStart + text.length
            """{"kind":${jsonString(kind)},"quote":{"text":${jsonString(text)},"start_utf16":$supportStart,"end_utf16":$supportEnd}}"""
        }
        val requiredJson = required.joinToString(",") { jsonString(it) }
        val excludedJson = excluded.joinToString(",") { jsonString(it) }
        return """{"row_index":$index,"item":{"name":"milk","quantity":$quantity,"quantity_unit":"$quantityUnit","pack_value":$pack,"pack_unit":"$packUnit","packaging":null,"required":[$requiredJson],"excluded":[$excludedJson],"source_text":"$source","supporting_text":[$supportTextJson],"needs_clarification":false,"quantity_text":$quantityTextJson,"pack_text":$packTextJson},"evidence":{"primary":{"text":"$source","start_utf16":$start,"end_utf16":$end},"supporting":[$supportEvidenceJson]},"quantity_origin":"${if (quantity == "null") "not_stated" else "stated"}","amount_role_status":"${if (quantity == "null" && pack == "null") "not_stated" else "matched"}"}"""
    }

    private data class CorrectionCase(
        val instruction: String, val primary: String, val name: String, val quantityUnit: String, val quantity: String,
        val packaging: String?, val packValue: String?, val packUnit: String, val kind: String, val correctionText: String,
        val newAmount: String, val oldValue: String, val oldUnit: String, val newValue: String, val newUnit: String,
        val packText: String? = packValue,
        val quantityText: String? = quantity.takeUnless { it == "null" },
        val required: List<String> = emptyList(),
        val excluded: List<String> = emptyList(),
    )

    private fun correctionRow(case: CorrectionCase, rowIndex: Int = 0): String {
        val start = case.instruction.indexOf(case.primary)
        val base = readyRow(
            rowIndex, case.instruction, case.primary, start, quantity = case.quantity, quantityUnit = case.quantityUnit,
            quantityText = case.quantityText, pack = case.packValue ?: "null", packUnit = case.packUnit,
            packText = case.packText, required = case.required, excluded = case.excluded,
        ).replace("\"name\":\"milk\"", "\"name\":${jsonString(case.name)}")
            .replace("\"packaging\":null", "\"packaging\":${case.packaging?.let(::jsonString) ?: "null"}")
        val correctionStart = case.instruction.indexOf(case.correctionText)
        val amountStart = correctionStart + case.correctionText.indexOf(case.newAmount)
        val correction = """{"kind":${jsonString(case.kind)},"source":{"text":${jsonString(case.correctionText)},"start_utf16":$correctionStart,"end_utf16":${correctionStart + case.correctionText.length}},"new_amount":{"text":${jsonString(case.newAmount)},"start_utf16":$amountStart,"end_utf16":${amountStart + case.newAmount.length}},"old_value":${case.oldValue},"old_unit":${jsonString(case.oldUnit)},"new_value":${case.newValue},"new_unit":${jsonString(case.newUnit)}}"""
        return base.dropLast(1) + ",\"corrections\":[$correction]}"
    }

    private fun expectedPreparedQuantity(case: CorrectionCase): Quantity {
        val (value, unit) = if (case.kind == "quantity") case.newValue to case.newUnit else case.quantity to case.quantityUnit
        val amount = value.toBigDecimal().toInt()
        return when (unit) {
            "count", "pack", "piece" -> Quantity.Count(amount)
            "g" -> Quantity.Weight(amount)
            "kg" -> Quantity.Weight(amount * 1000)
            "ml" -> Quantity.Volume(amount)
            "l" -> Quantity.Volume(amount * 1000)
            else -> error("Unsupported expected quantity unit: $unit")
        }
    }

    private fun canonicalPackQueryValue(case: CorrectionCase): String {
        val amount = case.newValue.toBigDecimal()
        val (canonicalAmount, canonicalUnit) = when (case.newUnit) {
            "kg" -> amount.multiply("1000".toBigDecimal()) to "g"
            "l" -> amount.multiply("1000".toBigDecimal()) to "ml"
            "g", "ml", "piece" -> amount to case.newUnit
            else -> error("Unsupported pack-size unit: ${case.newUnit}")
        }
        return "${canonicalAmount.stripTrailingZeros().toPlainString()} $canonicalUnit"
    }

    private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
}
