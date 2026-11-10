package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentQuantityOrigin
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentV2GroupPreparationTest {
    @Test fun completeInitialCartDesireDoesNotBreakWholeClausePostfixIdentityProof() {
        for (header in listOf("sab BrandA ke", "sabhi BrandA ke", "all from BrandA")) {
            val source = "one rice, two lentils, $header"
            val instruction = "Haan, mujhe cart mein daalna hai: $source"
            val rows = listOf(Item("BrandA rice", "one rice", 1, "one"),
                Item("BrandA lentils", "two lentils", 2, "two"))
            val result = prepare(instruction, rows, listOf(group(instruction, source, header,
                listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local")),
                null, amountStatus = "local_amounts_verified")))
            assertEquals(header, listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
            assertTrue(header, result.items.all { it.query.contains("BrandA", ignoreCase = true) })
            assertTrue(header, result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
        }
    }

    @Test fun contextualFromHeadingRetainsSharedIdentityAndExactLocalAmounts() {
        val header = "from BrandA "
        val result = prepareContextualFromGroup(header)
        assertEquals(4, result.items.size)
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(2), Quantity.Count(3)),
            result.items.map { it.quantity })
        assertTrue(result.items.drop(1).all { it.query.contains("BrandA", ignoreCase = true) })
        assertEquals(listOf(header.trim()), result.sharedGroupNotes)
        assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
    }

    @Test fun contextualFromCategoryHeadingUsesTheSameProjectedIdentityEverywhere() {
        val instruction = "Please add one milk, and from BrandA snacks: one bhujia and two crackers"
        val header = "from BrandA snacks:"
        val source = "$header one bhujia and two crackers"
        val rows = listOf(Item("milk", "one milk", 1, "one"),
            Item("BrandA bhujia", "one bhujia", 1, "one"),
            Item("BrandA crackers", "two crackers", 2, "two"))
        val members = listOf(Member(1, 1, "count", "local"), Member(2, 2, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(instruction, source, header, members, null,
            amountStatus = "local_amounts_verified")))
        assertEquals(3, result.items.size)
        assertTrue(result.items.drop(1).all { it.query.contains("BrandA", ignoreCase = true) })
        assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
    }

    @Test fun contextualFromCannotDropBrandPropertyOrUnsupportedHeaderWords() {
        listOf("from BrandA organic ", "from BrandA if available ",
            "from From Nature ", "From BrandA ").forEach { header ->
            val result = prepareContextualFromGroup(header)
            assertTrue(header, result.items.size <= 1)
            assertTrue(header, result.excludedText.isNotEmpty())
        }
        val lostBrand = prepareContextualFromGroup("from BrandA ", firstName = "rice")
        assertTrue("failed group cannot continue granting the predecessor a boundary", lostBrand.items.isEmpty())
        assertTrue(lostBrand.excludedText.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            prepareContextualFromGroup("from BrandA two ")
        }
    }

    @Test fun contextualFromRequiresAnOriginalClosedJoinAfterAnOwnedPrimary() {
        listOf(" and ", ", maybe and ", ", two and ", ", if available and ", ". ",
            ", and\n").forEach { join ->
            val result = prepareContextualFromGroup("from BrandA ", join = join)
            assertTrue(join, result.items.size <= 1)
            assertTrue(join, result.excludedText.isNotEmpty())
        }
    }

    @Test fun contextualFromProofRequiresUniqueOriginalPrimaryAndLocalAmounts() {
        val instruction = "Please add one milk, and from BrandA one rice and two lentils"
        val header = "from BrandA "
        val source = "${header}one rice and two lentils"
        val rows = listOf(Item("milk", "one milk", 1, "one"),
            Item("BrandA rice", "one rice", 1, "one"),
            Item("BrandA lentils", "two lentils", 2, "two"))
        val groupJson = group(instruction, source, header,
            listOf(Member(1, 1, "count", "local"), Member(2, 2, "count", "local")),
            null, amountStatus = "local_amounts_verified")
        val draft = GroceryIntentReviewV2Response.parse(response(instruction,
            rows.mapIndexed { index, row -> rowJson(instruction, index, row) }, listOf(groupJson)), instruction).draft
        val verified = draft.groups.single()
        val primaries = draft.readyRows.map { it.rowIndex to it.evidence.primary.text }
        assertEquals("BrandA", verified.contextualFromRequestIdentity(instruction, primaries))
        assertEquals(null, verified.contextualFromRequestIdentity(null, primaries))
        assertEquals(null, verified.contextualFromRequestIdentity(instruction, null))
        assertEquals(null, verified.contextualFromRequestIdentity("$instruction and one milk", primaries))
        assertEquals(null, verified.contextualFromRequestIdentity(instruction, primaries.drop(1)))
        assertEquals(null, verified.copy(members = verified.members.map { it.copy(value = null) })
            .contextualFromRequestIdentity(instruction, primaries))
        assertEquals(null, verified.copy(header = requireNotNull(verified.header).copy(startUtf16 = -1))
            .contextualFromRequestIdentity(instruction, primaries))
    }

    @Test fun verifiedLocalFruitHeadingAcceptsLiteralFruitMembersAndRejectsOtherProducts() {
        val header = "fruits wale items, har naam ke saamne jo quantity hai utni hi lena:"
        val instruction = "$header 1 apple and 2 bananas"
        val rows = listOf(
            Item("apple", "1 apple", 1),
            Item("bananas", "2 bananas", 2),
        )
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )))
        assertEquals(2, result.items.size)
        assertTrue(result.excludedText.isEmpty() && result.unresolvedText.isEmpty())

        val wrongInstruction = "$header 1 apple and 2 juice"
        val wrongRows = listOf(
            Item("apple", "1 apple", 1),
            Item("juice", "2 juice", 2),
        )
        val wrong = prepare(wrongInstruction, wrongRows, listOf(group(
            wrongInstruction, wrongInstruction, header, members, null, amountStatus = "local_amounts_verified",
        )))
        assertTrue(wrong.items.isEmpty())
        assertEquals(listOf(wrongInstruction), wrong.excludedText)

        val identityHeader = "BrandA organic items, har naam ke saamne jitne likhe hain utne hi lena:"
        val identityInstruction = "$identityHeader 1 apples and 2 bananas"
        val identityMembers = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        val lostIdentity = prepare(identityInstruction, listOf(
            Item("BrandA organic apples", "1 apples", 1), Item("BrandA bananas", "2 bananas", 2),
        ), listOf(group(
            identityInstruction, identityInstruction, identityHeader, identityMembers, null,
            amountStatus = "local_amounts_verified",
        )))
        assertTrue(lostIdentity.items.isEmpty())
        assertEquals(listOf(identityInstruction), lostIdentity.excludedText)
    }

    @Test fun heterogeneousCategoriesRetainBrandAndLocalSpecificityWithoutCrossBorrowing() {
        val header = "BrandA dals and flour:"
        val instruction = "$header 1 chana dal and 1 besan"
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
        val rows = listOf(Item("BrandA chana dal", "1 chana dal", 1), Item("BrandA flour besan", "1 besan", 1))
        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )))
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.query.contains("BrandA", ignoreCase = true) })
        assertFalse(result.items.first().query.contains("flour"))
        assertEquals(listOf(header.dropLast(1)), result.sharedGroupNotes)
        assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
    }

    @Test fun categoryDistributionCannotBorrowSiblingIdentityOrDropBrandDietOrLocalProduct() {
        val cases = listOf(
            Triple("BrandA dals and flour:", "1 besan", "besan"),
            Triple("BrandA organic dals and flour:", "1 besan", "BrandA besan"),
            Triple("BrandA dals and flour:", "1 sugar", "BrandA sugar flour"),
            Triple("BrandA dals and flour:", "1 moong dal", "BrandA moong dal flour"),
            Triple("BrandA dals and flour:", "1 organic besan", "BrandA besan"),
            Triple("BrandA dals and flour:", "1 saat anaj atta", "BrandA anaj atta"),
        )
        cases.forEach { (header, primary, name) ->
            val instruction = "$header 1 chana dal and $primary"
            val firstName = if ("organic" in header) "BrandA organic chana dal" else "BrandA chana dal"
            val rows = listOf(Item(firstName, "1 chana dal", 1), Item(name, primary, 1))
            val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
            val result = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertTrue("$header / $primary", result.items.isEmpty())
            assertEquals(listOf(instruction), result.excludedText)
        }
    }

    @Test fun amountLikeWordsInMemberNamesAreNotRemovedByCategoryGrounding() {
        val header = "BrandA biscuits:"
        val instruction = "$header 1 Bar One biscuits and 1 oat biscuits"
        val rows = listOf(Item("BrandA One biscuits", "1 Bar One biscuits", 1),
            Item("BrandA oat biscuits", "1 oat biscuits", 1))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )))
        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun missingCategoryWitnessCannotBeAcknowledgedIntoAPartialGroup() {
        val header = "BrandA dals and flour:"
        val instruction = "$header 1 chana dal and 1 moong dal"
        val rows = listOf(Item("BrandA chana dal", "1 chana dal", 1), Item("BrandA moong dal", "1 moong dal", 1))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )))
        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun collectiveSnacksRequirePreparedProductIdentityAndCannotLoseHeadingExclusions() {
        for ((example, succeeds) in listOf(
            Triple("BrandA snacks:", "1 crackers", "BrandA crackers") to true,
            Triple("BrandA snacks:", "1 moong dal", "BrandA moong dal") to false,
            Triple("BrandA snacks:", "1 biscuit mix", "BrandA biscuit mix") to false,
            Triple("BrandA snacks no maida:", "1 crackers", "BrandA crackers") to false,
        )) {
            val (header, primary, name) = example
            val instruction = "$header 1 bhujia and $primary"
            val rows = listOf(Item("BrandA bhujia", "1 bhujia", 1), Item(name, primary, 1))
            val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
            val result = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertEquals(header, succeeds, result.items.size == 2)
        }
    }

    @Test fun compiledSharedTotalKeepsLocalCountsAndAddsSharedBrandCategoryAndForm() {
        val instruction = "3 BrandA yogurt cups: one cup blueberry and two cups mango"
        val header = "3 BrandA yogurt cups:"
        val source = instruction
        val rows = listOf(
            Item("BrandA yogurt blueberry", "one cup blueberry", quantity = 1, quantityText = "one", packaging = "cup"),
            Item("BrandA yogurt mango", "two cups mango", quantity = 2, quantityText = "two", packaging = "cup"),
        )
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        val total = Amount(header, "3", 3, "count")

        val result = prepare(instruction, rows, listOf(group(instruction, source, header, members, "cup", total = total, amountStatus = "total_verified")))

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.query.contains("BrandA", ignoreCase = true) })
        assertTrue(result.items.all { it.query.contains("yogurt", ignoreCase = true) })
        assertTrue(result.items.all { it.query.contains("cup", ignoreCase = true) })
        assertEquals(Quantity.Count(1), result.items[0].quantity)
        assertEquals(Quantity.Count(2), result.items[1].quantity)
        assertTrue(result.unresolvedText.isEmpty())
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun explicitEachFillsOnlyNullLocalQuantitiesAndIsShownOnceAsGroupNote() {
        val instruction = "BrandA yogurt cups: one each blueberry and mango"
        val source = instruction
        val header = "BrandA yogurt cups:"
        val each = "one each"
        val rows = listOf(
            Item("BrandA yogurt blueberry", "blueberry", packaging = "cup"),
            Item("BrandA yogurt mango", "mango", packaging = "cup"),
        )
        val members = listOf(Member(0, 1, "count", "each"), Member(1, 1, "count", "each"))
        val eachAmount = Amount(each, "one", 1, "count")
        val group = group(instruction, source, header, members, "cup", each = eachAmount, amountStatus = "each_verified")

        val result = prepare(instruction, rows, listOf(group))

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1)), result.items.map { it.quantity })
        assertEquals(listOf("BrandA yogurt cups — one each"), result.sharedGroupNotes)
        assertEquals(1, result.sharedGroupNotes.size)
        assertTrue(result.excludedText.isEmpty())
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun headerlessBrandSiblingsPrepareEachWithoutBorrowingOrMutatingSourceRows() {
        val instruction = "BrandA biscuits, BrandB milk, one each"
        val rows = listOf(
            Item("BrandA biscuits", "BrandA biscuits"),
            Item("BrandB milk", "BrandB milk"),
        )
        val members = listOf(Member(0, 1, "count", "each"), Member(1, 1, "count", "each"))
        val each = Amount("one each", "one", 1, "count")
        val body = response(instruction, rows.mapIndexed { index, item -> rowJson(instruction, index, item) }, listOf(group(
            instruction, instruction, null, members, null, each = each, amountStatus = "each_verified",
        )))
        val draft = GroceryIntentReviewV2Response.parse(body, instruction).draft

        val result = draft.prepareForReview()

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1)), result.items.map { it.quantity })
        assertEquals(listOf("one each"), result.sharedGroupNotes)
        assertTrue(result.items[0].query.contains("BrandA", ignoreCase = true))
        assertFalse(result.items[0].query.contains("BrandB", ignoreCase = true))
        assertTrue(result.items[1].query.contains("BrandB", ignoreCase = true))
        assertFalse(result.items[1].query.contains("BrandA", ignoreCase = true))
        assertTrue(draft.readyRows.all { it.item.quantity == null })
        assertEquals(listOf("BrandA biscuits", "BrandB milk"), draft.readyRows.map { it.item.name })
        assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
    }

    @Test fun headlessEachGroupAfterAListItemRequiresClosedContinuationAndKeepsAllItems() {
        val continuations = listOf("Then", "and then", "phir", "fir", "aur phir", "aur fir")
        continuations.forEach { continuation ->
            val instruction = "one bread. $continuation milk, juice, one each."
            val source = "$continuation milk, juice, one each."
            val rows = listOf(
                Item("bread", "one bread", 1, quantityText = "one"),
                Item("milk", "milk"),
                Item("juice", "juice"),
            )
            val members = listOf(Member(1, 1, "count", "each"), Member(2, 1, "count", "each"))
            val each = Amount("one each", "one", 1, "count")
            val prepared = prepare(instruction, rows, listOf(group(
                instruction, source, null, members, null, each = each, amountStatus = "each_verified",
            )))

            assertEquals(continuation, listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
            assertEquals(3, prepared.items.size)
            assertTrue("$continuation: ${prepared.excludedText}", prepared.excludedText.isEmpty())
            assertTrue("$continuation: ${prepared.unresolvedText}", prepared.unresolvedText.isEmpty())
        }

        // The validated source may omit the discourse connective while retaining the exact member list.
        val instruction = "one bread. Then milk, juice, one each."
        val rows = listOf(Item("bread", "one bread", 1, quantityText = "one"), Item("milk", "milk"), Item("juice", "juice"))
        val prepared = prepare(instruction, rows, listOf(group(
            instruction, "milk, juice, one each.", null,
            listOf(Member(1, 1, "count", "each"), Member(2, 1, "count", "each")), null,
            each = Amount("one each", "one", 1, "count"), amountStatus = "each_verified",
        )))
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
    }

    @Test fun eachGroupContinuationCannotBeInventedWithoutSafeAdjacencyOrHideMeaningfulText() {
        val unsafeSources = listOf(
            "Next milk, juice, one each.", "Last milk, juice, one each.",
            "Then milk, juice, one each:", "Then\nmilk, juice, one each.",
            "Then BrandA milk, juice, one each.", "Then organic milk, juice, one each.",
            "Then two milk, juice, one each.", "Then milk, one each, juice.",
            "Then milk, juice, one each, remove bread.",
        )
        unsafeSources.forEach { source ->
            val instruction = "one bread. $source"
            val rows = listOf(Item("bread", "one bread", 1, quantityText = "one"), Item("milk", "milk"), Item("juice", "juice"))
            assertThrows("Malformed/unsafe group source must be rejected: $source", IllegalArgumentException::class.java) {
                prepare(instruction, rows, listOf(group(
                    instruction, source, null,
                    listOf(Member(1, 1, "count", "each"), Member(2, 1, "count", "each")), null,
                    each = Amount("one each", "one", 1, "count"), amountStatus = "each_verified",
                )))
            }
        }

        val noPrior = "Then milk, juice, one each."
        assertThrows(IllegalArgumentException::class.java) {
            prepare(noPrior, listOf(Item("milk", "milk"), Item("juice", "juice")), listOf(group(
                noPrior, noPrior, null,
                listOf(Member(0, 1, "count", "each"), Member(1, 1, "count", "each")), null,
                each = Amount("one each", "one", 1, "count"), amountStatus = "each_verified",
            )))
        }

        val omittedMember = "one bread. Then apples, milk, juice, one each."
        val omittedRows = listOf(
            Item("bread", "one bread", 1, quantityText = "one"),
            Item("apples", "apples"),
            Item("milk", "milk"),
            Item("juice", "juice"),
        )
        assertThrows("A group source cannot omit its first listed member", IllegalArgumentException::class.java) {
            prepare(omittedMember, omittedRows, listOf(group(
                omittedMember, "Then apples, milk, juice, one each.", null,
                listOf(Member(2, 1, "count", "each"), Member(3, 1, "count", "each")), null,
                each = Amount("one each", "one", 1, "count"), amountStatus = "each_verified",
            )))
        }
    }

    @Test fun cartRequestPrefixDoesNotTurnDescriptiveOneIntoAPurchaseAmount() {
        val instruction = "Beta, thoda cart mein daal do na. the red juice one"
        val result = prepare(instruction, listOf(Item("red juice", "the red juice one")), emptyList())
        assertEquals(listOf("red juice"), result.items.map { it.query })
        assertEquals(Quantity.Default, result.items.single().quantity)
        assertEquals(IntentQuantityOrigin.UNSTATED, result.items.single().intentQuantityProvenance?.origin)
        assertEquals(null, result.items.single().intentQuantityProvenance?.local)
        assertTrue(result.excludedText.isEmpty())
        assertTrue(result.unresolvedText.isEmpty())

        val forged = prepare(instruction,
            listOf(Item("red juice", "the red juice one", 1, quantityText = "one")), emptyList())
        assertTrue("A descriptive terminal one cannot become a purchase count", forged.items.isEmpty())
        assertTrue((forged.unresolvedText + forged.excludedText).any { it.contains("the red juice one") })

        val missingDescriptor = prepare(instruction, listOf(Item("juice", "the red juice one")), emptyList())
        assertTrue(missingDescriptor.items.isEmpty())
        assertTrue((missingDescriptor.unresolvedText + missingDescriptor.excludedText).any { it.contains("the red juice one") })
    }

    @Test fun cartRequestPrefixRechecksNumericOwnershipForGroupedMembers() {
        val instruction = "Beta, thoda cart mein daal do na. one bread. Then the milk one, juice, one each."
        val source = "the milk one, juice, one each"
        val rows = listOf(Item("bread", "one bread", 1, quantityText = "one"),
            Item("milk one", "the milk one", 1, quantityText = "one"), Item("juice", "juice"))
        val members = listOf(Member(1, 1, "count", "local"), Member(2, 1, "count", "each"))
        val result = prepare(instruction, rows, listOf(group(instruction, source, null, members, null,
            each = Amount("one each", "one", 1, "count"), amountStatus = "each_verified")))
        assertTrue(result.items.isEmpty())
        assertTrue((result.unresolvedText + result.excludedText).any { it.contains("thoda") })
    }

    @Test fun cartRequestPrefixCannotHideUnboundDietOrCorrectionAfterAnExactItem() {
        for (tail in listOf("no sugar", "actually make milk two")) {
            val instruction = "Beta, thoda cart mein daal do na. one milk; $tail"
            val result = prepare(instruction, listOf(Item("milk", "one milk", 1, quantityText = "one")), emptyList())
            assertTrue(tail, result.items.isEmpty())
            assertTrue(tail, (result.unresolvedText + result.excludedText).any { it.contains(tail) })
        }
    }

    @Test fun cartRequestPrefixAllowsVerifiedUnstatedAmountButRejectsForgedCount() {
        val instruction = "Beta, thoda cart mein daal do na. one milk, two juices."
        val ready = prepare(instruction, listOf(
            Item("milk", "one milk", 1, quantityText = "one"),
            Item("juices", "two juices", 2, quantityText = "two"),
        ), emptyList())
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), ready.items.map { it.quantity })
        assertTrue(ready.excludedText.isEmpty())
        assertTrue(ready.unresolvedText.isEmpty())

        val missingQuantityInstruction = "Beta, thoda cart mein daal do na. milk, two juices."
        val missingQuantity = prepare(missingQuantityInstruction, listOf(
            Item("milk", "milk"),
            Item("juices", "two juices", 2, quantityText = "two"),
        ), emptyList())
        assertEquals(listOf(Quantity.Default, Quantity.Count(2)), missingQuantity.items.map { it.quantity })
        assertEquals(IntentQuantityOrigin.UNSTATED, missingQuantity.items.first().intentQuantityProvenance?.origin)
        assertEquals(null, missingQuantity.items.first().intentQuantityProvenance?.local)
        assertTrue(missingQuantity.excludedText.isEmpty())
        assertTrue(missingQuantity.unresolvedText.isEmpty())

        val forgedInstruction = "Beta, thoda cart mein daal do na. milk, two juices."
        assertThrows("Count fields cannot prove an amount absent from its original primary", IllegalArgumentException::class.java) {
            prepare(forgedInstruction, listOf(
                Item("milk", "milk", 1, quantityText = "one"),
                Item("juice", "two juices", 2, quantityText = "two"),
            ), emptyList())
        }
    }

    @Test fun unstatedPrimaryDoesNotAllowUnknownPrefixOrMeaningfulDietAndCorrectionTails() {
        val unknownPrefix = "Beta, thoda cart mein daal do na, quickly. milk"
        val unknown = prepare(unknownPrefix, listOf(Item("milk", "milk")), emptyList())
        assertTrue(unknown.items.isEmpty())
        assertTrue(unknown.unresolvedText.isNotEmpty())

        for (tail in listOf("no sugar", "actually make milk two")) {
            val instruction = "Beta, thoda cart mein daal do na. milk; $tail"
            val result = prepare(instruction, listOf(Item("milk", "milk")), emptyList())
            assertTrue(tail, result.items.isEmpty())
            assertTrue(tail, (result.unresolvedText + result.excludedText).any { it.contains(tail) })
        }

        for (source in listOf("milk thoda", "milk lagbhag", "milk, actually make that two")) {
            val instruction = "Beta, thoda cart mein daal do na. $source"
            val result = prepare(instruction, listOf(Item("milk", source)), emptyList())
            assertTrue(source, result.items.isEmpty())
            assertTrue(source, result.unresolvedText.isNotEmpty())
        }
    }

    @Test fun colonAndSemicolonMayPrefixOnlyFullyQuantifiedPrimaries() {
        for (separator in listOf(":", ";")) {
            val unquantifiedInstruction = "Beta, thoda cart mein daal do na$separator milk"
            val unquantified = prepare(unquantifiedInstruction, listOf(Item("milk", "milk")), emptyList())
            assertTrue(separator, unquantified.items.isEmpty())

            val quantifiedInstruction = "Beta, thoda cart mein daal do na$separator one milk"
            val quantified = prepare(quantifiedInstruction,
                listOf(Item("milk", "one milk", 1, quantityText = "one")), emptyList())
            assertEquals(separator, listOf(Quantity.Count(1)), quantified.items.map { it.quantity })
            assertTrue(separator, quantified.excludedText.isEmpty() && quantified.unresolvedText.isEmpty())
        }
    }

    @Test fun neutralPleaseGetContextPreparesHeaderlessBrandSiblingsWithoutCrossBorrowing() {
        val instruction = "Please get BrandA biscuits, BrandB milk, one each."
        val rows = listOf(
            Item("BrandA biscuits", "BrandA biscuits"),
            Item("BrandB milk", "BrandB milk"),
        )
        val members = listOf(Member(0, 1, "count", "each"), Member(1, 1, "count", "each"))
        val each = Amount("one each", "one", 1, "count")
        val body = "BrandA biscuits, BrandB milk, one each"
        listOf(body, instruction, "$body.").forEach { source ->
            val result = prepare(instruction, rows, listOf(group(
                instruction, source, null, members, null, each = each, amountStatus = "each_verified",
            )))
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(1)), result.items.map { it.quantity })
            assertTrue(result.items[0].query.contains("BrandA", ignoreCase = true))
            assertFalse(result.items[0].query.contains("BrandB", ignoreCase = true))
            assertTrue(result.items[1].query.contains("BrandB", ignoreCase = true))
            assertFalse(result.items[1].query.contains("BrandA", ignoreCase = true))
            assertTrue(result.unresolvedText.isEmpty())
            assertTrue(result.excludedText.isEmpty())
        }
    }

    @Test fun missingLocalQuantityWithoutExplicitEachRemainsDefault() {
        val instruction = "BrandA yogurt cups: blueberry and mango"
        val rows = listOf(
            Item("BrandA yogurt blueberry", "blueberry", packaging = "cup"),
            Item("BrandA yogurt mango", "mango", packaging = "cup"),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, "BrandA yogurt cups:", members, "cup", amountStatus = "amounts_unspecified",
        )))

        assertEquals(listOf(Quantity.Default, Quantity.Default), result.items.map { it.quantity })
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun positiveSugarFreeHeaderIsCopiedToEveryMemberRequirement() {
        val instruction = "BrandA sugar-free yogurt cups: blueberry and mango"
        val header = "BrandA sugar-free yogurt cups:"
        val rows = listOf(
            Item("BrandA yogurt blueberry", "blueberry", packaging = "cup", required = listOf("sugar_free")),
            Item("BrandA yogurt mango", "mango", packaging = "cup", required = listOf("sugar_free")),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, "cup", amountStatus = "amounts_unspecified",
        )))

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.query.contains("sugar free", ignoreCase = true) })
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun positiveNoMaidaHeaderAddsTheExclusionToEveryMember() {
        val instruction = "BrandA yogurt cups no maida: blueberry and mango"
        val header = "BrandA yogurt cups no maida:"
        val rows = listOf(
            Item("BrandA yogurt blueberry", "blueberry", packaging = "cup", excluded = listOf("maida")),
            Item("BrandA yogurt mango", "mango", packaging = "cup", excluded = listOf("maida")),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, "cup", amountStatus = "amounts_unspecified",
        )))

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { "maida" in it.avoidPhrases })
        assertTrue(result.excludedText.isEmpty())
    }

    @Test fun oneMemberMissingBrandCategoryOrProtectedFormExcludesTheWholeGroup() {
        val cases = listOf(
            Triple("BrandA yogurt cups: blueberry and mango", "BrandA yogurt cups:", "BrandA yogurt blueberry" to "yogurt mango"),
            Triple("BrandA yogurt cups: blueberry and mango", "BrandA yogurt cups:", "BrandA yogurt blueberry" to "BrandA mango"),
            Triple("BrandA ready-to-eat cups: blueberry and mango", "BrandA ready-to-eat cups:", "BrandA ready-to-eat blueberry" to "BrandA blueberry"),
        )

        cases.forEach { (instruction, header, names) ->
            val source = instruction
            val localSources = listOf("blueberry", "mango")
            val rows = listOf(
                Item(names.first, localSources[0], packaging = "cup"),
                Item(names.second, localSources[1], packaging = "cup"),
            )
            val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

            val result = prepare(instruction, rows, listOf(group(
                instruction, source, header, members, "cup", amountStatus = "amounts_unspecified",
            )))

            assertTrue("group should have no partial results for ${names.second}", result.items.isEmpty())
            assertEquals(listOf(source), result.excludedText)
        }
    }

    @Test fun negativeMaidaHeadingNeedsAnExclusionOnEveryMemberEvenIfAnotherNameSaysMaida() {
        val instruction = "BrandA yogurt cups no maida: blueberry and mango"
        val header = "BrandA yogurt cups no maida:"
        val rows = listOf(
            Item("BrandA yogurt blueberry", "blueberry", packaging = "cup", excluded = listOf("maida")),
            // The positive name token is not evidence that this member excludes maida.
            Item("BrandA yogurt maida mango", "mango", packaging = "cup"),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, "cup", amountStatus = "amounts_unspecified",
        )))

        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun failedUnpunctuatedGroupCannotDeclareFollowingMilkIndependent() {
        val instruction = "BrandA yogurt cups blueberry mango milk"
        val groupSource = "BrandA yogurt cups blueberry mango"
        val rows = listOf(
            Item("yogurt blueberry", "blueberry", packaging = "cup"),
            Item("BrandA yogurt mango", "mango", packaging = "cup"),
            Item("milk", "milk"),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))
        assertThrows(IllegalArgumentException::class.java) {
            prepare(instruction, rows, listOf(group(
                instruction, groupSource, "BrandA yogurt cups", members, "cup", amountStatus = "amounts_unspecified",
            )))
        }
    }

    @Test fun failedGroupWithExplicitBoundaryIsExcludedAndIndependentMilkSurvives() {
        val instruction = "BrandA yogurt cups blueberry mango; milk"
        val groupSource = "BrandA yogurt cups blueberry mango"
        val rows = listOf(
            Item("yogurt blueberry", "blueberry", packaging = "cup"),
            Item("BrandA yogurt mango", "mango", packaging = "cup"),
            Item("milk", "milk"),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

        val result = prepare(instruction, rows, listOf(group(
            instruction, groupSource, "BrandA yogurt cups", members, "cup", amountStatus = "amounts_unspecified",
        )))

        assertEquals(listOf("milk"), result.items.map { it.query })
        assertEquals(listOf(groupSource), result.excludedText)
        assertTrue(result.unresolvedText.isEmpty())
    }

    @Test fun proceduralQuantityHeadingKeepsLocalCountsAndProductIdentity() {
        val headers = listOf(
            "Pantry shelf ke liye BrandA items, har naam ke saamne jitne likhe hain utne hi lena:",
            "BrandA items, take the quantity written beside each item:",
            "For the kitchen shelf BrandA items, please take the stated quantity of each named item:",
        )
        headers.forEach { header ->
            val instruction = "$header 2 poha and 1 masoor"
            val rows = listOf(Item("BrandA poha", "2 poha", 2), Item("BrandA masoor", "1 masoor", 1))
            val members = listOf(Member(0, 2, "count", "local"), Member(1, 1, "count", "local"))
            val result = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))

            assertEquals(header, listOf(Quantity.Count(2), Quantity.Count(1)), result.items.map { it.quantity })
            assertTrue(result.items.all { it.query.contains("BrandA", ignoreCase = true) })
            assertEquals(listOf(header.dropLast(1)), result.sharedGroupNotes)
            assertTrue(result.excludedText.isEmpty())
            assertTrue(result.unresolvedText.isEmpty())
        }
    }

    @Test fun proceduralHeadingCannotDiscardBrandCategoryDietOrUnknownPurpose() {
        val cases = listOf(
            "BrandA" to ("BrandA poha" to "masoor"),
            "BrandA organic" to ("BrandA organic poha" to "BrandA masoor"),
            "BrandA lentils" to ("BrandA lentils poha" to "BrandA masoor"),
            "for diabetics BrandA" to ("BrandA poha" to "BrandA masoor"),
            "for my evening tea BrandA" to ("BrandA poha" to "BrandA masoor"),
        )
        cases.forEach { (identity, names) ->
            val header = "$identity items, take the quantity written beside each item:"
            val instruction = "$header 2 poha and 1 masoor"
            val rows = listOf(Item(names.first, "2 poha", 2), Item(names.second, "1 masoor", 1))
            val members = listOf(Member(0, 2, "count", "local"), Member(1, 1, "count", "local"))
            val result = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertTrue(identity, result.items.isEmpty())
            assertEquals(listOf(instruction), result.excludedText)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun proceduralHeadingRequiresEveryLocalQuantityAndNeverDefaultsMissingAmounts() {
        val header = "BrandA items, take the quantity written beside each item:"
        val instruction = "$header 2 poha and masoor"
        val rows = listOf(Item("BrandA poha", "2 poha", 2), Item("BrandA masoor", "masoor"))
        val members = listOf(Member(0, 2, "count", "local"), Member(1, null, "unspecified", "not_stated"))
        prepare(instruction, rows, listOf(group(
            instruction, instruction, header, members, null, amountStatus = "amounts_unspecified",
        )))
    }

    @Test fun proceduralHeadingCannotSwallowExtraRestrictionsOrSimilarQuantityInstructions() {
        val suffixes = listOf(
            "take the quantity written beside each item without sugar",
            "take the quantity written beside each item but double it",
            "take the quantity written beside each item if available",
            "take the quantity written beside each itemx",
            "take the quantity written beside each item, no organic versions",
        )
        suffixes.forEach { suffix ->
            val header = "BrandA items, $suffix:"
            val instruction = "$header 2 poha and 1 masoor"
            val rows = listOf(Item("BrandA poha", "2 poha", 2), Item("BrandA masoor", "1 masoor", 1))
            val members = listOf(Member(0, 2, "count", "local"), Member(1, 1, "count", "local"))
            val result = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertTrue(suffix, result.items.isEmpty())
            assertEquals(listOf(instruction), result.excludedText)
        }
    }

    @Test fun completeTerminalRequestSuffixIsProjectedForGroundingButShownUnshortened() {
        val headers = listOf(
            "BrandA fruit yogurt cups bhi chahiye,",
            "BrandA fruit yogurt cups bhi daalna,",
            "BrandA fruit yogurt cups bhi,",
        )
        headers.forEach { header ->
            val result = prepareFruitHeaderGroup(header)

            assertEquals(2, result.items.size)
            assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), result.items.map { it.quantity })
            assertTrue(result.items.all { it.query.contains("BrandA", ignoreCase = true) })
            assertTrue(result.items.all { it.query.contains("fruit yogurt", ignoreCase = true) })
            assertTrue(result.items.all { it.query.contains("cup", ignoreCase = true) })
            assertEquals(listOf(header), result.sharedGroupNotes)
            assertTrue(result.unresolvedText.isEmpty())
            assertTrue(result.excludedText.isEmpty())
        }
    }

    @Test fun completeEnglishRequestHeadingsRetainIdentityAmountsAndOriginalReviewWording() {
        listOf(
            "From BrandA fruit yogurt cups I need:",
            "from BrandA fruit yogurt cups I want,",
            "Then BrandA fruit yogurt cups:",
            "Then the BrandA fruit yogurt cups,",
        ).forEach { header ->
            val result = prepareFruitHeaderGroup(header)
            assertEquals(header, 2, result.items.size)
            assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), result.items.map { it.quantity })
            assertTrue(result.items.all { it.query.contains("BrandA", ignoreCase = true) })
            assertTrue(result.items.all { it.query.contains("fruit yogurt", ignoreCase = true) })
            assertTrue(result.items.all { it.query.contains("cup", ignoreCase = true) })
            assertEquals(listOf(header.trimEnd(':')), result.sharedGroupNotes)
            assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
        }
    }

    @Test fun EnglishRequestProjectionDoesNotDropBrandsModifiersConditionsOrNestedRequestWords() {
        listOf(
            Triple("From BrandA fruit yogurt cups I need:", "fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("From BrandA organic fruit yogurt cups I need:", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("from BrandA sugar-free fruit yogurt cups:", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("Then BrandA no maida fruit yogurt cups:", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("Then BrandA fruit yogurt cups if available:", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("Then from BrandA fruit yogurt cups:", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
        ).forEach { (header, firstName, secondName) ->
            val result = prepareFruitHeaderGroup(header, firstName, secondName)
            assertTrue(header, result.items.isEmpty())
            assertEquals(listOf("$header 2 blueberry and 3 mango"), result.excludedText)
        }
    }

    @Test fun EnglishWrapperRetainsAnExplicitFromWordInsideTheIdentity() {
        val header = "From From Nature fruit yogurt cups I need:"
        val kept = prepareFruitHeaderGroup(header, "From Nature fruit yogurt blueberry", "From Nature fruit yogurt mango")
        assertEquals(2, kept.items.size)
        val lost = prepareFruitHeaderGroup(header, "Nature fruit yogurt blueberry", "Nature fruit yogurt mango")
        assertTrue(lost.items.isEmpty())
    }

    @Test fun bareFromHeadingCannotLoseAPossibleBrandWord() {
        val header = "From Nature fruit yogurt cups:"
        val kept = prepareFruitHeaderGroup(header, "From Nature fruit yogurt blueberry", "From Nature fruit yogurt mango")
        assertEquals(2, kept.items.size)
        val lost = prepareFruitHeaderGroup(header, "Nature fruit yogurt blueberry", "Nature fruit yogurt mango")
        assertTrue(lost.items.isEmpty())
    }

    @Test fun EnglishRequestIsNotInventedAfterRemovingASharedTotal() {
        val header = "From BrandA fruit yogurt cups I 2 need:"
        val instruction = "$header 1 blueberry and 1 mango"
        val rows = listOf(Item("BrandA fruit yogurt blueberry", "1 blueberry", 1, packaging = "cup"),
            Item("BrandA fruit yogurt mango", "1 mango", 1, packaging = "cup"))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(instruction, instruction, header, members, "cup",
            total = Amount(header, "2", 2, "count"), amountStatus = "total_verified")))
        assertTrue(result.items.isEmpty())
    }

    @Test fun requestSuffixProjectionCannotEraseMissingIdentityPropertiesOrMeaningfulTails() {
        val base = "BrandA fruit yogurt cups bhi chahiye,"
        val cases = listOf(
            Triple(base, "fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA organic fruit yogurt cups bhi chahiye,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA sugar-free fruit yogurt cups bhi chahiye,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA no maida fruit yogurt cups bhi chahiye,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA fruit yogurt cups if available bhi chahiye,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA fruit yogurt cups na,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA organic fruit yogurt cups bhi,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
            Triple("BrandA fruit yogurt cups bhi nahi,", "BrandA fruit yogurt blueberry", "BrandA fruit yogurt mango"),
        )
        cases.forEach { (header, firstName, secondName) ->
            val result = prepareFruitHeaderGroup(header, firstName, secondName)
            assertTrue(header, result.items.isEmpty())
            assertEquals(header, listOf("$header 2 blueberry and 3 mango"), result.excludedText)
        }
    }

    @Test fun requestSuffixProjectionCannotDropRestrictionAppendedAfterTheSuffix() {
        val header = "BrandA fruit yogurt cups bhi chahiye, sugar-free"
        val result = prepareFruitHeaderGroup(header)

        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.contains("$header 2 blueberry and 3 mango") ||
            result.unresolvedText.contains("$header 2 blueberry and 3 mango"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestSuffixCannotHideAConflictingContainerInTheHeader() {
        prepareFruitHeaderGroup("BrandA fruit yogurt cups two packs bhi chahiye,")
    }

    @Test fun terminalSuffixIsNotInventedAfterRemovingAValidatedSharedTotal() {
        val header = "BrandA fruit yogurt cups bhi 2 chahiye,"
        val instruction = "$header 1 blueberry and 1 mango"
        val rows = listOf(
            Item("BrandA fruit yogurt blueberry", "1 blueberry", 1, packaging = "cup"),
            Item("BrandA fruit yogurt mango", "1 mango", 1, packaging = "cup"),
        )
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(
            instruction,
            instruction,
            header,
            members,
            "cup",
            total = Amount(header, "2", 2, "count"),
            amountStatus = "total_verified",
        )))

        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun sharedHeaderCannotLicenseAHallucinatedVariant() {
        val instruction = "BrandA yogurt cups: blueberry and mango"
        val rows = listOf(
            Item("BrandA yogurt blueberry", "blueberry", packaging = "cup"),
            Item("BrandA yogurt strawberry", "mango", packaging = "cup"),
        )
        val members = listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated"))

        val result = prepare(instruction, rows, listOf(group(
            instruction, instruction, "BrandA yogurt cups:", members, "cup", amountStatus = "amounts_unspecified",
        )))

        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun canonicalCommaHeaderPreservesTheExactSharedTotalAndLocalCounts() {
        val instruction = "3 cups, one cup blueberry and two cups mango"
        val rows = listOf(Item("blueberry", "one cup blueberry", 1, "one", "cup"),
            Item("mango", "two cups mango", 2, "two", "cup"))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        val result = prepare(instruction, rows, listOf(group(instruction, instruction, "3 cups,", members,
            "cup", total = Amount("3 cups,", "3", 3, "count"), amountStatus = "total_verified")))
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
        assertTrue(result.excludedText.isEmpty() && result.unresolvedText.isEmpty())
    }

    @Test fun commaGroupCannotMakeAnOmittedProductDisappearAsAHeading() {
        val instruction = "eggs, blueberry and mango"
        val rows = listOf(Item("blueberry", "blueberry"), Item("mango", "mango"))
        val members = listOf(Member(0, null, "unspecified", "not_stated"),
            Member(1, null, "unspecified", "not_stated"))
        val result = prepare(instruction, rows, listOf(group(instruction, instruction, "eggs,", members,
            null, amountStatus = "amounts_unspecified")))
        assertTrue(result.items.isEmpty())
        assertEquals(listOf(instruction), result.excludedText)
    }

    @Test fun purposeGroupsKeepExactSharedNotesWithoutLendingHeaderIdentityOrAmounts() {
        listOf(
            "For breakfast:", "By the sink:", "Kitchen:", "Washroom:",
            "Pantry side:", "nashta ke liye:", "bathroom ke liye:",
        ).forEach { header ->
            val source = "$header 2 apples, 1 banana"
            val rows = listOf(Item("apples", "2 apples", 2, "2"), Item("banana", "1 banana", 1, "1"))
            val members = listOf(Member(0, 2, "count", "local"), Member(1, 1, "count", "local"))
            val prepared = prepare(source, rows, listOf(group(
                source, source, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertEquals(header, listOf(Quantity.Count(2), Quantity.Count(1)), prepared.items.map { it.quantity })
            assertEquals(header, listOf(header), prepared.sharedGroupNotes)
            assertTrue(header, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        listOf("", "\u00a0").forEach { suffix ->
            val instruction = "Kitchen: 2 powder$suffix, 1 brush$suffix"
            val genericRows = listOf(
                Item("kitchen powder", "2 powder$suffix", 2, "2"),
                Item("kitchen brush", "1 brush$suffix", 1, "1"),
            )
            val genericMembers = listOf(Member(0, 2, "count", "local"), Member(1, 1, "count", "local"))
            val generic = prepare(instruction, genericRows, listOf(group(
                instruction, instruction, "Kitchen:", genericMembers, null,
                amountStatus = "local_amounts_verified",
            )))
            assertTrue("ASCII/NBSP source suffix cannot let purpose invent identity", generic.items.isEmpty())
            assertTrue(generic.excludedText.isNotEmpty())
        }

        val literalInstruction = "Bathroom: 1 blue brush, 1 BrandA powder"
        val literalRows = listOf(
            Item("blue brush", "1 blue brush", 1, "1"),
            Item("BrandA powder", "1 BrandA powder", 1, "1"),
        )
        val literalMembers = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
        val literal = prepare(literalInstruction, literalRows, listOf(group(
            literalInstruction, literalInstruction, "Bathroom:", literalMembers, null,
            amountStatus = "local_amounts_verified",
        )))
        assertEquals(listOf("blue brush", "BrandA powder"), literal.items.map { it.query })
        assertEquals(listOf("Bathroom:"), literal.sharedGroupNotes)

        val inventedRows = listOf(
            Item("toilet brush", "1 blue brush", 1, "1"),
            Item("BrandA turmeric powder", "1 BrandA powder", 1, "1"),
        )
        val invented = prepare(literalInstruction, inventedRows, listOf(group(
            literalInstruction, literalInstruction, "Bathroom:", literalMembers, null,
            amountStatus = "local_amounts_verified",
        )))
        assertTrue("purpose labels cannot invent `toilet` or `turmeric` identities", invented.items.isEmpty())
    }

    @Test fun genericBrushPurposeMemberStaysBlockedWithNbspAndSpecificSibling(): Unit {
        val outcomes = listOf("", "\u00a0").map { suffix ->
            val brush = "1 brush$suffix"
            val oatMilk = "1 oat milk"
            val instruction = "Kitchen: $brush, $oatMilk"
            val rows = listOf(Item("brush", brush, 1, "1"), Item("oat milk", oatMilk, 1, "1"))
            val members = listOf(Member(0, 1, "count", "local"), Member(1, 1, "count", "local"))
            prepare(instruction, rows, listOf(group(
                instruction, instruction, "Kitchen:", members, null,
                amountStatus = "local_amounts_verified",
            )))
        }
        outcomes.forEach { prepared ->
            assertTrue("generic brush must prevent the purpose group and its members from becoming ready",
                prepared.items.isEmpty())
            assertTrue(prepared.excludedText.isNotEmpty())
            assertTrue(prepared.sharedGroupNotes.isEmpty())
        }
        // Blocking is identical, but the user's exact original source must not
        // be normalized merely to make the two review notes byte-identical.
        assertEquals(listOf("Kitchen: 1 brush, 1 oat milk"), outcomes[0].excludedText)
        assertEquals(listOf("Kitchen: 1 brush\u00a0, 1 oat milk"), outcomes[1].excludedText)
        assertEquals(outcomes[0].sharedGroupNotes, outcomes[1].sharedGroupNotes)
    }

    @Test fun imperativePurposeGroupHeadingsKeepExactNotesAndRequireWhitespaceAndCompleteScope(): Unit {
        listOf("By the sink, get", "For breakfast, please add").forEach { header ->
            val instruction = "$header 1 milk, 2 rice"
            val rows = listOf(Item("milk", "1 milk", 1, "1"), Item("rice", "2 rice", 2, "2"))
            val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
            val prepared = prepare(instruction, rows, listOf(group(
                instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
            )))
            assertEquals(header, listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
            assertEquals("exact purpose-action label must remain visible", listOf(header), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val gluedHeader = "By the sink, get"
        val gluedInstruction = "$gluedHeader" + "1 milk, 2 rice"
        val rows = listOf(Item("milk", "1 milk", 1, "1"), Item("rice", "2 rice", 2, "2"))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        assertThrows(IllegalArgumentException::class.java) {
            prepare(gluedInstruction, rows, listOf(group(
                gluedInstruction, gluedInstruction, gluedHeader, members, null,
                amountStatus = "local_amounts_verified",
            )))
        }

        val truncatedInstruction = "For breakfast, please add 1 milk, 2 rice, 1 bread"
        val truncatedSource = "For breakfast, please add 1 milk, 2 rice"
        val truncatedRows = listOf(
            Item("milk", "1 milk", 1, "1"), Item("rice", "2 rice", 2, "2"), Item("bread", "1 bread", 1, "1"),
        )
        val truncatedGroup = group(truncatedInstruction, truncatedSource, "For breakfast, please add",
            members, null, amountStatus = "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(truncatedInstruction, truncatedRows, listOf(truncatedGroup))
        }

        val unlistedGroup = group(truncatedInstruction, truncatedInstruction, "For breakfast, please add",
            members, null, amountStatus = "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(truncatedInstruction, truncatedRows, listOf(unlistedGroup))
        }

        val extraInstruction = "For breakfast, please add 1 milk, 2 rice, no sugar"
        val extraGroup = group(extraInstruction, extraInstruction, "For breakfast, please add",
            members, null, amountStatus = "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(extraInstruction, rows, listOf(extraGroup))
        }
    }

    @Test fun imperativePurposeGroupCannotCarrySharedEachOrTotal(): Unit {
        val header = "For breakfast, please add"
        val sources = listOf("1 milk", "2 rice")
        val rows = listOf(Item("milk", sources[0], 1, "1"), Item("rice", sources[1], 2, "2"))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))

        val eachInstruction = "$header 2 each of 1 milk, 2 rice"
        val each = group(eachInstruction, eachInstruction, header, members, null,
            each = Amount("2 each", "2", 2, "count"), amountStatus = "each_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(eachInstruction, rows, listOf(each))
        }

        val totalInstruction = "$header 3 total: 1 milk, 2 rice"
        val total = group(totalInstruction, totalInstruction, header, members, null,
            total = Amount("3", "3", 3, "count"), amountStatus = "total_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(totalInstruction, rows, listOf(total))
        }
    }

    @Test fun neutralNarrationGroupPreparesWithExactHeaderAndLocalAmounts() {
        val prefix = "  I am reading from the kitchen shelf, please note this down: "
        val first = "1 milk"
        val second = "2 eggs"
        val instruction = "$prefix$first, $second  "
        val header = prefix.trim()
        val source = instruction.substring(instruction.indexOf(header), instruction.indexOf(second) + second.length)
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        val result = prepare(instruction, listOf(
            Item("milk", first, 1, "1"), Item("eggs", second, 2, "2"),
        ), listOf(group(instruction, source, header, members, null, amountStatus = "local_amounts_verified")))

        assertEquals(listOf("milk", "eggs"), result.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
        assertEquals(listOf(header.trimEnd(':')), result.sharedGroupNotes)
        assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
    }

    @Test fun neutralNarrationGroupRejectsRepeatedPrimaryAndTruncatedMemberCoverage() {
        val header = "I am reading from the kitchen shelf, note this down: "
        val repeated = "${header}1 milk, 2 eggs, 1 milk"
        val repeatedRows = listOf(Item("milk", "1 milk", 1, "1"), Item("eggs", "2 eggs", 2, "2"))
        val repeatedGroup = group(repeated, repeated, header,
            listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local")), null,
            amountStatus = "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(repeated, repeatedRows, listOf(repeatedGroup))
        }

        val instruction = "${header}1 milk, 2 eggs, 1 bread"
        val source = "${header}1 milk, 2 eggs"
        val rows = listOf(Item("milk", "1 milk", 1, "1"), Item("eggs", "2 eggs", 2, "2"), Item("bread", "1 bread", 1, "1"))
        val truncated = group(instruction, source, header,
            listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local")), null,
            amountStatus = "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            prepare(instruction, rows, listOf(truncated))
        }
    }

    @Test fun neutralNarrationDoesNotSwallowAPropertyBetweenHeadingAndFirstItem() {
        val header = "I am reading from the kitchen shelf, note this down: "
        val instruction = "${header}sugar-free milk, 2 eggs"
        val rows = listOf(Item("milk", "sugar-free milk"), Item("eggs", "2 eggs", 2, "2"))
        val source = instruction
        val groupJson = group(instruction, source, header,
            listOf(Member(0, null, "unspecified", "not_stated"), Member(1, 2, "count", "local")), null,
            amountStatus = "amounts_unspecified")
        val result = prepare(instruction, rows, listOf(groupJson))
        assertTrue(result.items.none { it.query == "milk" })
        assertTrue((result.unresolvedText + result.excludedText).joinToString(" ").contains("sugar-free milk"))

        val omittedInstruction = "${header}only sugar-free 1 milk, 2 eggs"
        assertThrows(IllegalArgumentException::class.java) {
            prepare(omittedInstruction, listOf(Item("milk", "1 milk", 1, "1"), Item("eggs", "2 eggs", 2, "2")),
                listOf(group(omittedInstruction, omittedInstruction, header,
                    listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local")), null,
                    amountStatus = "local_amounts_verified")))
        }
    }

    @Test fun neutralNarrationCannotVerifyPackagingEachOrTotal() {
        val header = "I am reading from the kitchen shelf, note this down: "
        val sources = listOf("1 milk", "2 eggs")
        val rows = listOf(Item("milk", sources[0], 1, "1", packaging = "bottle"),
            Item("eggs", sources[1], 2, "2", packaging = "bottle"))
        val members = listOf(Member(0, 1, "count", "local"), Member(1, 2, "count", "local"))
        val instruction = "$header${sources[0]}, ${sources[1]}"
        assertThrows(IllegalArgumentException::class.java) {
            prepare(instruction, rows, listOf(group(instruction, instruction, header, members, "bottle",
                amountStatus = "local_amounts_verified")))
        }

        val eachInstruction = "$header 2 each: ${sources[0]}, ${sources[1]}"
        assertThrows(IllegalArgumentException::class.java) {
            prepare(eachInstruction, rows, listOf(group(eachInstruction, eachInstruction, header, members, null,
                each = Amount("2 each", "2", 2, "count"), amountStatus = "each_verified")))
        }

        val totalInstruction = "$header total 3: ${sources[0]}, ${sources[1]}"
        assertThrows(IllegalArgumentException::class.java) {
            prepare(totalInstruction, rows, listOf(group(totalInstruction, totalInstruction, header, members, null,
                total = Amount("3", "3", 3, "count"), amountStatus = "total_verified")))
        }
    }

    @Test fun sourceBoundBottleCountAndSizeSurviveGroupPreparation() {
        val header = "For breakfast, please add"
        val bottle = "a 500 ml bottle of juice"
        val eggs = "two eggs"
        val instruction = "$header $bottle, $eggs"
        val rows = listOf(
            Item(
                "juice", bottle, 1, "a", packaging = "bottle", quantityUnit = "pack",
                packValue = BigDecimal("500"), packUnit = "ml", packText = "500",
            ),
            Item("eggs", eggs, 2, "two", quantityUnit = "piece"),
        )
        val members = listOf(Member(0, 1, "pack", "local"), Member(1, 2, "piece", "local"))
        val groupJson = group(
            instruction, instruction, header, members, null, amountStatus = "local_amounts_verified",
        )
        val draft = GroceryIntentReviewV2Response.parse(response(
            instruction, rows.mapIndexed { index, item -> rowJson(instruction, index, item) }, listOf(groupJson),
        ), instruction).draft
        assertEquals("stated", draft.readyRows[0].quantityOrigin)
        val prepared = draft.prepareForReview()

        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.items[0].retailPackCount)
        assertEquals(IntentQuantityOrigin.LOCAL, prepared.items[0].intentQuantityProvenance?.origin)
        assertEquals("juice bottle 500 ml", prepared.items[0].strictMatchPhrase)
        assertEquals("juice bottle 500 ml", prepared.items[0].query)
        assertEquals("eggs", prepared.items[1].query)
        assertEquals(listOf(header.trimEnd(':')), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())

        fun assertJuiceHeld(makePreparation: () -> GroceryIntentPreparation) {
            try {
                assertTrue(makePreparation().items.none { it.query.startsWith("juice") })
            } catch (_: IllegalArgumentException) {
                // Invalid source amounts may already be rejected at the DTO boundary.
            }
        }
        assertJuiceHeld {
            prepare(instruction, listOf(
                rows[0].copy(quantity = null, quantityText = null, quantityUnit = "unspecified"), rows[1],
            ), listOf(group(instruction, instruction, header, listOf(
                Member(0, null, "unspecified", "not_stated"), members[1],
            ), null, amountStatus = "amounts_unspecified")))
        }
        assertJuiceHeld {
            prepare(instruction, listOf(rows[0].copy(quantity = 2, quantityText = "two"), rows[1]),
                listOf(group(instruction, instruction, header, listOf(members[0].copy(value = 2), members[1]),
                    null, amountStatus = "local_amounts_verified")))
        }
        val noSize = "$header a bottle of juice, two eggs"
        assertJuiceHeld {
            prepare(noSize, listOf(rows[0].copy(source = "a bottle of juice"), rows[1]),
                listOf(group(noSize, noSize, header, members, null, amountStatus = "local_amounts_verified")))
        }
        val noContainer = "$header a 500 ml juice, two eggs"
        assertJuiceHeld {
            prepare(noContainer, listOf(rows[0].copy(source = "a 500 ml juice"), rows[1]),
                listOf(group(noContainer, noContainer, header, members, null, amountStatus = "local_amounts_verified")))
        }
    }
}

private data class Item(
    val name: String,
    val source: String,
    val quantity: Int? = null,
    val quantityText: String? = quantity?.toString(),
    val packaging: String? = null,
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val quantityUnit: String = if (quantity == null) "unspecified" else "count",
    val packValue: BigDecimal? = null,
    val packUnit: String = if (packValue == null) "unspecified" else "g",
    val packText: String? = packValue?.toPlainString(),
)

private data class Member(val rowIndex: Int, val value: Int?, val unit: String, val origin: String)
private data class Amount(val source: String, val amountText: String, val value: Int, val unit: String)

private fun prepareContextualFromGroup(
    header: String,
    join: String = ", and ",
    firstName: String = "BrandA rice",
): GroceryIntentPreparation {
    val source = "${header}one rice, two lentils and three flour"
    val instruction = "Please add one milk$join$source"
    val rows = listOf(Item("milk", "one milk", 1, "one"),
        Item(firstName, "one rice", 1, "one"),
        Item("BrandA lentils", "two lentils", 2, "two"),
        Item("BrandA flour", "three flour", 3, "three"))
    val members = listOf(Member(1, 1, "count", "local"), Member(2, 2, "count", "local"),
        Member(3, 3, "count", "local"))
    return prepare(instruction, rows, listOf(group(instruction, source, header, members, null,
        amountStatus = "local_amounts_verified")))
}

private fun prepare(instruction: String, items: List<Item>, groups: List<String>): GroceryIntentPreparation {
    val rows = items.mapIndexed { index, item -> rowJson(instruction, index, item) }
    val body = response(instruction, rows, groups)
    return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
}

private fun prepareFruitHeaderGroup(
    header: String,
    firstName: String = "BrandA fruit yogurt blueberry",
    secondName: String = "BrandA fruit yogurt mango",
): GroceryIntentPreparation {
    val instruction = "$header 2 blueberry and 3 mango"
    val rows = listOf(
        Item(firstName, "2 blueberry", 2, packaging = "cup"),
        Item(secondName, "3 mango", 3, packaging = "cup"),
    )
    val members = listOf(Member(0, 2, "count", "local"), Member(1, 3, "count", "local"))
    return prepare(instruction, rows, listOf(group(
        instruction, instruction, header, members, "cup", amountStatus = "local_amounts_verified",
    )))
}

private fun response(instruction: String, rows: List<String>, groups: List<String>): String =
    """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":${rows.size},"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[],"groups":[${groups.joinToString(",")}]},"model":"test","promptVersion":"v19-test","latencyMs":1,"usage":{}}"""

private fun rowJson(instruction: String, index: Int, item: Item): String {
    val start = instruction.indexOf(item.source)
    require(start >= 0)
    val quantity = item.quantity?.toString() ?: "null"
    val packValue = item.packValue?.toPlainString() ?: "null"
    val packText = item.packText?.let(::jsonString) ?: "null"
    val quantityText = item.quantityText?.let(::jsonString) ?: "null"
    return """{"row_index":$index,"item":{"name":${jsonString(item.name)},"quantity":$quantity,"quantity_unit":"${item.quantityUnit}","pack_value":$packValue,"pack_unit":"${item.packUnit}","packaging":${item.packaging?.let(::jsonString) ?: "null"},"required":${jsonArray(item.required)},"excluded":${jsonArray(item.excluded)},"source_text":${jsonString(item.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":$packText},"evidence":{"primary":${quoteJson(instruction, item.source, start)},"supporting":[]},"quantity_origin":"${if (item.quantity == null) "not_stated" else "stated"}","amount_role_status":"${if (item.quantity == null && item.packValue == null) "not_stated" else "matched"}"}"""
}

private fun group(
    instruction: String,
    source: String,
    header: String?,
    members: List<Member>,
    packaging: String?,
    total: Amount? = null,
    each: Amount? = null,
    amountStatus: String,
): String {
    val memberJson = members.joinToString(",") { member ->
        """{"row_index":${member.rowIndex},"value":${member.value ?: "null"},"unit":"${member.unit}","origin":"${member.origin}"}"""
    }
    val totalJson = total?.let { amountJson(instruction, it) } ?: "null"
    val eachJson = each?.let { amountJson(instruction, it) } ?: "null"
    val headerJson = header?.let { quoteJson(instruction, it) } ?: "null"
    return """{"status":"structurally_verified","amount_status":"$amountStatus","source":${quoteJson(instruction, source)},"header":$headerJson,"member_indices":[${members.joinToString(",") { it.rowIndex.toString() }}],"members":[$memberJson],"packaging":${packaging?.let(::jsonString) ?: "null"},"total":$totalJson,"each":$eachJson}"""
}

private fun amountJson(instruction: String, amount: Amount): String =
    """{"source":${quoteJson(instruction, amount.source)},"amount_text":${jsonString(amount.amountText)},"value":${amount.value},"unit":"${amount.unit}"}"""

private fun quoteJson(instruction: String, text: String, start: Int = instruction.indexOf(text)): String {
    require(start >= 0 && instruction.substring(start, start + text.length) == text)
    return """{"text":${jsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""
}

private fun jsonArray(values: List<String>) = values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
