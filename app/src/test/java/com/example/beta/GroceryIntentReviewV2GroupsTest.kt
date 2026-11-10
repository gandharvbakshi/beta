package com.example.beta

import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewV2GroupsTest {
    @Test fun amountScaffoldWordsAreIdentityWithoutAProvenSharedTotal() {
        for (identity in listOf("BrandA", "Total", "Ka", "Altogether")) {
            val header = "$identity:"
            val instruction = "$header 1 rice, 2 beans"
            val proof = group(instruction, instruction, header, listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
                "local_amounts_verified")
            for (retained in listOf(false, true)) {
                val required = if (retained) listOf(identity) else emptyList()
                val items = listOf(
                    Item(0, "1 rice", "1", "count", "1", name = "rice", required = required),
                    Item(1, "2 beans", "2", "count", "2", name = "beans", required = required),
                )
                val prepared = GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft.prepareForReview()
                assertEquals(identity, if (retained) 2 else 0, prepared.items.size)
            }
        }
    }

    @Test fun terminalDescriptorParticleDoesNotInventItsScopeAcrossAGroup() {
        val instruction = "BrandA: one biscuit, two cookie, high fibre wala"
        val items = listOf(
            Item(0, "one biscuit", "1", "count", "one", name = "BrandA biscuit"),
            Item(1, "two cookie, high fibre wala", "2", "count", "two",
                name = "BrandA cookie high fibre", required = listOf("high_fibre")),
        )
        val proof = group(instruction, instruction, "BrandA", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified")
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft
        assertEquals("missing_source_identity", draft.readyRows[1].groundingIssue(
            group = draft.groups.single(), instruction = instruction))
        assertTrue(draft.prepareForReview().items.isEmpty())
    }

    @Test fun headedGroupCanUseExactSequenceContextAfterACompletedPrimary() {
        val groupSource = "BrandA: one biscuit, two cookie"
        val prefixes = listOf(
            "one rice. Then the ",
            "one rice; and then the ",
            "one rice. Next the ",
            "one rice. Last the ",
            "one rice. Lastly the ",
            "one rice? Finally the ",
            "one rice. THEN the ",
            "one rice. Then  the ",
        )
        for (prefix in prefixes) {
            val instruction = prefix + groupSource
            val items = listOf(
                Item(0, "one rice", "1", "count", "one", name = "rice"),
                Item(1, "one biscuit", "1", "count", "one", name = "BrandA biscuit"),
                Item(2, "two cookie", "2", "count", "two", name = "BrandA cookie"),
            )
            val proof = group(
                instruction, groupSource, "BrandA:", listOf(1, 2),
                listOf(Member(1, "1", "count", "local"), Member(2, "2", "count", "local")),
                "local_amounts_verified",
            )
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft
            val prepared = draft.prepareForReview()

            assertEquals(prefix, "BrandA:", draft.groups.single().header?.text)
            assertEquals(prefix, listOf("rice", "BrandA biscuit", "BrandA cookie"), prepared.items.map { it.query })
            assertEquals(
                prefix,
                listOf(
                    com.example.beta.automation.Quantity.Count(1),
                    com.example.beta.automation.Quantity.Count(1),
                    com.example.beta.automation.Quantity.Count(2),
                ),
                prepared.items.map { it.quantity },
            )
            assertEquals(prefix, listOf("BrandA"), prepared.sharedGroupNotes)
            assertTrue(prefix, prepared.excludedText.isEmpty())
            assertTrue(prefix, prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun sequenceContextPrefixCannotHideMeaningfulTextOrExpandGroupScope() {
        val groupSource = "BrandA: one biscuit, two cookie"
        val invalidPrefixes = listOf(
            "Then The ",
            "Then a ",
            "Then an ",
            "Then ek ",
            "Then two ",
            "Then the organic ",
            "Then the no sugar ",
            "Then the not ",
            "Then the\n",
            " ".repeat(65) + "Then the ",
        )
        for (prefix in invalidPrefixes) {
            val instruction = "one rice. $prefix$groupSource"
            val items = listOf(
                Item(0, "one rice", "1", "count", "one", name = "rice"),
                Item(1, "one biscuit", "1", "count", "one", name = "BrandA biscuit"),
                Item(2, "two cookie", "2", "count", "two", name = "BrandA cookie"),
            )
            val proof = group(
                instruction, groupSource, "BrandA:", listOf(1, 2),
                listOf(Member(1, "1", "count", "local"), Member(2, "2", "count", "local")),
                "local_amounts_verified",
            )
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertTrue("unowned prefix must remain visible", prepared.excludedText.isNotEmpty() || prepared.unresolvedText.isNotEmpty())
        }

        val omittedInstruction = "one rice. Then the BrandA: one biscuit, two cookie, three oats"
        val omittedItems = listOf(
            Item(0, "one rice", "1", "count", "one", name = "rice"),
            Item(1, "one biscuit", "1", "count", "one", name = "BrandA biscuit"),
            Item(2, "two cookie", "2", "count", "two", name = "BrandA cookie"),
        )
        val truncatedProof = group(
            omittedInstruction, groupSource, "BrandA:", listOf(1, 2),
            listOf(Member(1, "1", "count", "local"), Member(2, "2", "count", "local")),
            "local_amounts_verified",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(omittedInstruction, omittedItems.map { row(it, omittedInstruction) }, listOf(truncatedProof)), omittedInstruction,
            )
        }
    }

    @Test fun sequenceContextHelperRequiresPriorNonOverlappingPrimaryEvidence() {
        val groupSource = "BrandA: one biscuit, two cookie"
        val noPriorInstruction = "Then the $groupSource"
        val groupItems = listOf(
            Item(1, "one biscuit", "1", "count", "one", name = "BrandA biscuit"),
            Item(2, "two cookie", "2", "count", "two", name = "BrandA cookie"),
        )
        val proof = group(
            noPriorInstruction, groupSource, "BrandA:", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        val noPriorDraft = GroceryIntentReviewV2Response.parse(
            response(noPriorInstruction, groupItems.mapIndexed { index, item -> row(item.copy(index = index), noPriorInstruction) }, listOf(proof)),
            noPriorInstruction,
        ).draft
        assertNull(noPriorDraft.groups.single().sequenceContextSpan(
            noPriorInstruction, noPriorDraft.readyRows.map { it.evidence.primary },
        ))

        val instruction = "one rice. Then the $groupSource"
        val items = listOf(Item(0, "one rice", "1", "count", "one", name = "rice")) + groupItems
        val validProof = group(
            instruction, groupSource, "BrandA:", listOf(1, 2),
            listOf(Member(1, "1", "count", "local"), Member(2, "2", "count", "local")),
            "local_amounts_verified",
        )
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(validProof)), instruction,
        ).draft
        val overlapStart = instruction.indexOf("Then the")
        val overlappingPrimary = GroceryIntentReviewV2Quote("Then the", overlapStart, overlapStart + "Then the".length)
        assertNull(draft.groups.single().sequenceContextSpan(
            instruction, draft.readyRows.map { it.evidence.primary } + overlappingPrimary,
        ))
    }

    @Test fun opaqueLetterDigitProductCodesRemainGroupIdentityNotEachAmounts() {
        val instruction = "A2 milk and QX15 device, one each"
        val items = listOf(
            Item(0, "A2 milk", name = "A2 milk"),
            Item(1, "QX15 device", name = "QX15 device"),
        )
        val proof = group(
            instruction, instruction, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )

        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()

        assertEquals(2, prepared.items.size)
        assertEquals(listOf("A2 milk", "QX15 device"), prepared.items.map { it.query })
        assertEquals(listOf(com.example.beta.automation.Quantity.Count(1), com.example.beta.automation.Quantity.Count(1)),
            prepared.items.map { it.quantity })
        assertEquals(listOf(com.example.beta.automation.IntentQuantityOrigin.GROUP,
            com.example.beta.automation.IntentQuantityOrigin.GROUP),
            prepared.items.map { it.intentQuantityProvenance?.origin })
        assertEquals(listOf(setOf("a2"), setOf("qx15")),
            prepared.items.map { it.intentQuantityProvenance?.opaqueNumericLabels })
        assertTrue(prepared.items.all { it.intentQuantityProvenance?.local == null })
        assertTrue(prepared.excludedText.isEmpty())
        assertTrue(prepared.unresolvedText.isEmpty())
        assertFalse(prepared.items.any { it.query == "milk" || it.query == "device" })
    }

    @Test fun literalNumericDescriptorCanJoinAnEachGroupWithoutBecomingALocalCount() {
        val instruction = "9 watt bulb and rice, one each"
        val items = listOf(
            Item(0, "9 watt bulb", name = "9 watt bulb"),
            Item(1, "rice", name = "rice"),
        )
        val proof = group(
            instruction, instruction, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, prepared.items.size)
        assertEquals(listOf(com.example.beta.automation.Quantity.Count(1), com.example.beta.automation.Quantity.Count(1)),
            prepared.items.map { it.quantity })
        assertEquals(com.example.beta.automation.IntentQuantityOrigin.GROUP,
            prepared.items.first().intentQuantityProvenance?.origin)
        assertNull(prepared.items.first().intentQuantityProvenance?.local)
        assertTrue(prepared.items.first().query.contains("9 watt bulb"))
        assertTrue(prepared.excludedText.isEmpty())
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test fun descriptiveOneMembersKeepSeparateEachProofWithoutInventingLocalCounts() {
        listOf(
            Triple("the red soap one", "red soap", true),
            Triple("two soap, the red one", "red soap", false),
            Triple("the red soap one", "soap", false),
            Triple("9 gram soap, the red one", "9 gram red soap", false),
        ).forEach { (first, name, valid) ->
            val second = "the blue soap one"
            val instruction = "$first, $second, one each"
            val items = listOf(Item(0, first, name = name), Item(1, second, name = "blue soap"))
            val proof = group(instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"))
            val body = response(instruction, items.map { row(it, instruction) }, listOf(proof))
            if (valid) {
                val decoded = GroceryIntentReviewV2Response.parse(body, instruction).draft
                val prepared = decoded.prepareForReview()
                assertEquals(2, prepared.items.size)
                prepared.items.forEach {
                    assertEquals(com.example.beta.automation.Quantity.Count(1), it.quantity)
                    assertEquals(com.example.beta.automation.IntentQuantityOrigin.GROUP, it.intentQuantityProvenance?.origin)
                    assertNull(it.intentQuantityProvenance?.local)
                }
            } else assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(body, instruction)
            }
        }
    }

    @Test
    fun absentGroupsRemainBackwardCompatible(): Unit {
        val instruction = "milk"
        val draft = GroceryIntentReviewV2Response.parse(response(instruction, listOf(row(Item(0, "milk"), instruction))), instruction).draft
        assertEquals(emptyList<GroceryIntentReviewV2Group>(), draft.groups)
    }

    @Test
    fun acceptsVerifiedLocalAmountsAndExactTotal(): Unit {
        val instruction = "Fruit cups total three: one strawberry and two mangoes"
        val items = listOf(
            Item(0, "one strawberry", "1", "count", "one"),
            Item(1, "two mangoes", "2", "count", "two"),
        )
        val group = group(
            instruction, "Fruit cups total three: one strawberry and two mangoes", "Fruit cups total three",
            listOf(0, 1), listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "total_verified", packaging = "cup", total = Amount("three", "3", "count"),
        )

        val decoded = GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction).draft

        assertEquals(1, decoded.groups.size)
        assertEquals("total_verified", decoded.groups.single().amountStatus)
        assertEquals("3", decoded.groups.single().total?.value.toString())
        assertEquals(listOf(0, 1), decoded.groups.single().memberIndices)
    }

    @Test
    fun acceptsEachProofOnlyForUnstatedMembers(): Unit {
        val instruction = "Fruit cups: one each strawberry and mango"
        val items = listOf(Item(0, "strawberry"), Item(1, "mango"))
        val group = group(
            instruction, instruction, "Fruit cups", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", packaging = "cup", each = Amount("one", "1", "count", "one each"),
        )

        val decoded = GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction).draft

        assertEquals("each_verified", decoded.groups.single().amountStatus)
        assertEquals(listOf("each", "each"), decoded.groups.single().members.map { it.origin })
        assertNull(decoded.readyRows.first().item.quantity)
    }

    @Test
    fun acceptsHeaderlessBrandSiblingsWithExactSeparateEachDistribution(): Unit {
        val instruction = "BrandA biscuits, BrandB milk, one each"
        val items = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"))
        val group = group(
            instruction, instruction, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )

        val decoded = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction,
        ).draft

        assertNull(decoded.groups.single().header)
        assertEquals("each_verified", decoded.groups.single().amountStatus)
        assertEquals(listOf(0, 1), decoded.groups.single().memberIndices)
        assertNull(decoded.readyRows[0].item.quantity)
        assertNull(decoded.readyRows[1].item.quantity)
    }

    @Test
    fun trailingUniversalIdentityIsPreparedOnlyForTheCompleteExactGroup(): Unit {
        val cases = listOf(
            "one rice and two dal, all from BrandX." to "all from BrandX",
            "one rice and two dal, sab BrandX ke." to "sab BrandX ke",
            "one rice and two dal, sabhi BrandX ke." to "sabhi BrandX ke",
        )
        cases.forEach { (instruction, header) ->
            val items = listOf(
                Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
                Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
            )
            val proof = group(
                instruction, instruction, header, listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
                "local_amounts_verified",
            )
            val decoded = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft
            val prepared = decoded.prepareForReview()

            assertEquals(header, decoded.groups.single().header?.text)
            assertEquals(listOf("BrandX rice", "BrandX dal"), prepared.items.map { it.query })
            assertEquals(
                listOf(com.example.beta.automation.Quantity.Count(1), com.example.beta.automation.Quantity.Count(2)),
                prepared.items.map { it.quantity },
            )
            assertEquals(listOf("BrandX rice", "BrandX dal"), prepared.items.map { it.query })
            assertEquals(listOf(header), prepared.sharedGroupNotes)
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
        }

        // Existing prefix-headed identity remains accepted.
        val instruction = "BrandX: one rice and two dal"
        val prefixItems = listOf(
            Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
            Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
        )
        val prefix = group(instruction, instruction, "BrandX:", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified")
        val prefixPrepared = GroceryIntentReviewV2Response.parse(
            response(instruction, prefixItems.map { row(it, instruction) }, listOf(prefix)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, prefixPrepared.items.size)
        assertTrue(prefixPrepared.excludedText.isEmpty())

        val unstatedInstruction = "rice and dal, all from BrandX."
        val unstatedItems = listOf(Item(0, "rice", name = "BrandX rice"), Item(1, "dal", name = "BrandX dal"))
        val unstatedProof = group(
            unstatedInstruction, unstatedInstruction, "all from BrandX", listOf(0, 1),
            membersUnstated(0, 1), "amounts_unspecified",
        )
        val unstated = GroceryIntentReviewV2Response.parse(
            response(unstatedInstruction, unstatedItems.map { row(it, unstatedInstruction) }, listOf(unstatedProof)),
            unstatedInstruction,
        ).draft.prepareForReview()
        assertEquals(
            listOf(com.example.beta.automation.Quantity.Default, com.example.beta.automation.Quantity.Default),
            unstated.items.map { it.quantity },
        )
        assertEquals(listOf("BrandX rice", "BrandX dal"), unstated.items.map { it.query })
    }

    @Test
    fun completeForRequestHeaderPreservesThreeLocallyCountedVariantsAndEveryIdentityToken(): Unit {
        val instruction = "For the Meadow no-added-sugar fruit yoghurt cups, I want: one Mango cup, two Strawberry cups, and one Blueberry cup"
        val sources = listOf("one Mango cup", "two Strawberry cups", "one Blueberry cup")
        val names = listOf(
            "Meadow no-added-sugar fruit yoghurt Mango",
            "Meadow no-added-sugar fruit yoghurt Strawberry",
            "Meadow no-added-sugar fruit yoghurt Blueberry",
        )
        val items = sources.mapIndexed { index, source ->
            val amount = if (index == 1) "2" else "1"
            Item(index, source, amount, "pack", if (index == 1) "two" else "one", name = names[index])
        }
        val proof = group(
            instruction, instruction, "For the Meadow no-added-sugar fruit yoghurt cups, I want",
            listOf(0, 1, 2),
            listOf(
                Member(0, "1", "pack", "local"),
                Member(1, "2", "pack", "local"),
                Member(2, "1", "pack", "local"),
            ),
            "local_amounts_verified", packaging = "cup",
        )

        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()

        assertEquals(names.map { "$it cup" }, prepared.items.map { it.query })
        assertEquals(
            listOf(
                com.example.beta.automation.Quantity.Count(1),
                com.example.beta.automation.Quantity.Count(2),
                com.example.beta.automation.Quantity.Count(1),
            ),
            prepared.items.map { it.quantity },
        )
        assertTrue(prepared.excludedText.isEmpty())
        assertTrue(prepared.unresolvedText.isEmpty())

        val incompleteNames = listOf(
            names.toMutableList().also { it[1] = "Strawberry" },
            names.toMutableList().also { it[1] = "the Meadow no-added-sugar fruit cups Strawberry" },
            names.toMutableList().also { it[1] = "the Meadow fruit yoghurt cups Strawberry" },
        )
        incompleteNames.forEach { proposedNames ->
            val badItems = items.mapIndexed { index, item -> item.copy(name = proposedNames[index]) }
            val badPrepared = GroceryIntentReviewV2Response.parse(
                response(instruction, badItems.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertTrue("no row should be prepared after a shared identity token is dropped", badPrepared.items.isEmpty())
            assertTrue("the complete source group must remain visible", badPrepared.excludedText.contains(instruction))
        }

        val nestedInstruction = "For Then Meadow fruit yoghurt cups, I want: one Mango cup, two Strawberry cups, and one Blueberry cup"
        val nestedIdentity = "Then Meadow fruit yoghurt cups"
        val nestedNames = listOf("$nestedIdentity Mango", "$nestedIdentity Strawberry", "$nestedIdentity Blueberry")
        val nestedItems = sources.mapIndexed { index, source ->
            Item(index, source, if (index == 1) "2" else "1", "pack", if (index == 1) "two" else "one", name = nestedNames[index])
        }
        val nestedProof = group(
            nestedInstruction, nestedInstruction, "For Then Meadow fruit yoghurt cups, I want", listOf(0, 1, 2),
            listOf(Member(0, "1", "pack", "local"), Member(1, "2", "pack", "local"), Member(2, "1", "pack", "local")),
            "local_amounts_verified", packaging = "cup",
        )
        val nestedPrepared = GroceryIntentReviewV2Response.parse(
            response(nestedInstruction, nestedItems.map { row(it, nestedInstruction) }, listOf(nestedProof)), nestedInstruction,
        ).draft.prepareForReview()
        assertEquals(nestedNames.map { "$it cup" }, nestedPrepared.items.map { it.query })
        val droppedNestedIdentity = nestedItems.mapIndexed { index, item ->
            if (index == 1) item.copy(name = "Meadow fruit yoghurt Strawberry") else item
        }
        val nestedBlocked = GroceryIntentReviewV2Response.parse(
            response(nestedInstruction, droppedNestedIdentity.map { row(it, nestedInstruction) }, listOf(nestedProof)), nestedInstruction,
        ).draft.prepareForReview()
        assertTrue(nestedBlocked.items.isEmpty())
        assertTrue(nestedBlocked.excludedText.contains(nestedInstruction))

    }

    @Test
    fun explicitPostfixIdentityUsesTheFullClauseAcrossSafeWhitespace(): Unit {
        val cases = listOf(
            Triple("one rice and two dal,\nall from BrandX.", "one rice and two dal,\nall from BrandX.", "all from BrandX"),
            Triple("one rice and two dal\nall from BrandX.", "one rice and two dal\nall from BrandX.", "all from BrandX"),
            Triple("one rice and two dal, all from BrandX .", "one rice and two dal, all from BrandX .", "all from BrandX"),
            Triple("one rice and two dal, all from BrandX .", "one rice and two dal, all from BrandX .", "all from BrandX ."),
            Triple("Please get one rice and two dal, all from BrandX.", "one rice and two dal, all from BrandX.", "all from BrandX"),
        )
        cases.forEach { (instruction, scope, header) ->
            val items = listOf(
                Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
                Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
            )
            val proof = group(
                instruction, scope, header, listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
                "local_amounts_verified",
            )
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertEquals(2, prepared.items.size)
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
        }

        val splitClause = "one rice and two dal.\nall from BrandX."
        val items = listOf(
            Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
            Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
        )
        val splitProof = group(
            splitClause, splitClause, "all from BrandX", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        val splitPrepared = runCatching {
            GroceryIntentReviewV2Response.parse(
                response(splitClause, items.map { row(it, splitClause) }, listOf(splitProof)), splitClause,
            ).draft.prepareForReview()
        }.getOrNull()
        assertFalse(splitPrepared?.let { it.items.size == 2 && it.excludedText.isEmpty() } == true)
    }

    @Test
    fun ambiguousAllPurposeFlourCannotBeConsumedAsSharedIdentity(): Unit {
        val instruction = "rice, dal and all purpose flour"
        val items = listOf(
            Item(0, "rice", name = "rice purpose flour"),
            Item(1, "dal", name = "dal purpose flour"),
        )
        val proof = group(
            instruction, instruction, "all purpose flour", listOf(0, 1),
            membersUnstated(0, 1), "amounts_unspecified",
        )
        val prepared = runCatching {
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
        }.getOrNull()
        assertFalse(
            "the flour product must not be silently consumed as shared identity",
            prepared?.let { it.items.size == 2 && it.excludedText.isEmpty() } == true,
        )
    }

    @Test
    fun ungroupedAmulSuffixCannotBeDistributedToPrimaryRows(): Unit {
        val instruction = "milk, curd all Amul"
        val items = listOf(
            Item(0, "milk", name = "Amul milk"),
            Item(1, "curd", name = "Amul curd"),
        )
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }), instruction,
        ).draft.prepareForReview()
        assertTrue("unowned shared suffix must hold both rows for review", prepared.items.isEmpty())

        val ordinaryProduct = "milk, all purpose flour"
        val ordinaryItems = listOf(Item(0, "milk", name = "milk"), Item(1, "all purpose flour", name = "all purpose flour"))
        val ordinary = GroceryIntentReviewV2Response.parse(
            response(ordinaryProduct, ordinaryItems.map { row(it, ordinaryProduct) }), ordinaryProduct,
        ).draft.prepareForReview()
        assertEquals(listOf("all purpose flour"), ordinary.items.map { it.query })
        assertTrue(ordinary.excludedText.any { it.contains("milk") })
        assertTrue(ordinary.unresolvedText.isEmpty())

        val quantifiedOrdinary = "milk, two all purpose flour"
        val quantifiedItems = listOf(
            Item(0, "milk", name = "milk"),
            Item(1, "two all purpose flour", "2", "count", "two", name = "all purpose flour"),
        )
        val quantified = GroceryIntentReviewV2Response.parse(
            response(quantifiedOrdinary, quantifiedItems.map { row(it, quantifiedOrdinary) }), quantifiedOrdinary,
        ).draft.prepareForReview()
        assertEquals(listOf("all purpose flour"), quantified.items.map { it.query })
        assertEquals(com.example.beta.automation.Quantity.Count(2), quantified.items.single().quantity)
        assertTrue(quantified.excludedText.any { it.contains("milk") })
    }

    @Test
    fun explicitUniversalSuffixCannotEscapeAsAnUnverifiedStandaloneProduct(): Unit {
        listOf("all from BrandX", "sab BrandX ke", "sabhi BrandX ke").forEach { suffix ->
            val instruction = "milk, curd, $suffix"
            val items = listOf(
                Item(0, "milk", name = "milk"),
                Item(1, "curd", name = "curd"),
                Item(2, suffix, name = suffix),
            )
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }), instruction,
            ).draft.prepareForReview()
            assertTrue("shared-identity phrase cannot become a standalone SKU: $suffix", prepared.items.isEmpty())
            assertTrue(prepared.excludedText.isNotEmpty() || prepared.unresolvedText.isNotEmpty())
        }
    }

    @Test
    fun suffixDenialScopeCoversTrailingConnectorsEmojiAndHindiForms(): Unit {
        val cases = listOf(
            Triple("milk, curd all Amul, and bread", listOf("milk", "curd", "bread"), listOf("milk", "curd", "bread")),
            Triple("milk, curd all from Amul\nbread", listOf("milk", "curd", "bread"), listOf("milk", "curd", "bread")),
            Triple("milk, curd all Amul🙏", listOf("milk", "curd"), listOf("milk", "curd")),
            Triple("doodh, dahi sab Amul ka", listOf("doodh", "dahi"), listOf("doodh", "dahi")),
        )
        cases.forEach { (instruction, sources, names) ->
            val items = sources.mapIndexed { index, source -> Item(index, source, name = names[index]) }
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }), instruction,
            ).draft.prepareForReview()
            assertTrue("all suffix-affected rows must stay held: $instruction", prepared.items.isEmpty())
        }
    }

    @Test
    fun neutralIntroCannotMaskAnActualUniversalSuffix(): Unit {
        val instructions = listOf(
            "bazaar se yeh sab le aana: milk, curd all from Amul",
            "bazaar se yeh sab Amul le aana: milk, curd",
        )
        instructions.forEach { instruction ->
            val items = listOf(Item(0, "milk", name = "milk"), Item(1, "curd", name = "curd"))
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }), instruction,
            ).draft.prepareForReview()
            assertTrue("introductory wording must not conceal suffix identity: $instruction", prepared.items.isEmpty())
        }
    }

    @Test
    fun onlyExactLiteralSuffixProductSurvivesWhileMixedListSiblingsStayHeld(): Unit {
        val literalInstruction = "milk, curd all Amul"
        val literalItems = listOf(
            Item(0, "milk", name = "milk"),
            Item(1, "curd", name = "curd"),
            Item(2, "all Amul", name = "all Amul"),
        )
        val literal = GroceryIntentReviewV2Response.parse(
            response(literalInstruction, literalItems.map { row(it, literalInstruction) }), literalInstruction,
        ).draft.prepareForReview()
        assertEquals(listOf("all Amul"), literal.items.map { it.query })
        assertTrue(literal.excludedText.any { it.contains("milk") })
        assertTrue(literal.excludedText.any { it.contains("curd") })

        val mixedFlourInstruction = "rice, dal and all purpose flour"
        val mixedFlourItems = listOf(
            Item(0, "rice", name = "rice"),
            Item(1, "dal", name = "dal"),
            Item(2, "all purpose flour", name = "all purpose flour"),
        )
        val mixedFlour = GroceryIntentReviewV2Response.parse(
            response(mixedFlourInstruction, mixedFlourItems.map { row(it, mixedFlourInstruction) }), mixedFlourInstruction,
        ).draft.prepareForReview()
        assertEquals(listOf("all purpose flour"), mixedFlour.items.map { it.query })
        assertTrue(mixedFlour.excludedText.any { it.contains("rice") })
        assertTrue(mixedFlour.excludedText.any { it.contains("dal") })

        val quantifiedInstruction = "rice, dal and two all purpose flour"
        val quantifiedItems = listOf(
            Item(0, "rice", name = "rice"),
            Item(1, "dal", name = "dal"),
            Item(2, "two all purpose flour", "2", "count", "two", name = "all purpose flour"),
        )
        val quantified = GroceryIntentReviewV2Response.parse(
            response(quantifiedInstruction, quantifiedItems.map { row(it, quantifiedInstruction) }), quantifiedInstruction,
        ).draft.prepareForReview()
        assertEquals(listOf("all purpose flour"), quantified.items.map { it.query })
        assertEquals(com.example.beta.automation.Quantity.Count(2), quantified.items.single().quantity)

        val singletonInstruction = "all purpose flour"
        val singleton = GroceryIntentReviewV2Response.parse(
            response(singletonInstruction, listOf(row(Item(0, singletonInstruction, name = singletonInstruction), singletonInstruction))),
            singletonInstruction,
        ).draft.prepareForReview()
        assertEquals(listOf("all purpose flour"), singleton.items.map { it.query })
        assertTrue(singleton.excludedText.isEmpty())
        assertTrue(singleton.unresolvedText.isEmpty())
    }

    @Test
    fun postfixCandidateBoundariesDoNotPromoteUnicodeSpacingOrAccentedWordFragments(): Unit {
        val nbspAfterPeriod = "milk.\u00a0all from Amul"
        val asciiSpaceAfterPeriod = "milk. all from Amul"
        val nbspCandidate = groceryIntentPostfixIdentityCandidates(nbspAfterPeriod).single()
        val asciiCandidate = groceryIntentPostfixIdentityCandidates(asciiSpaceAfterPeriod).single()
        assertEquals(0, nbspCandidate.left)
        assertEquals("milk.".length, asciiCandidate.left)

        val accentedBoundary = "dahi\u00e9all from Amul"
        val accentedCandidate = groceryIntentPostfixIdentityCandidates(accentedBoundary).single()
        assertEquals(accentedBoundary.indexOf("all"), accentedCandidate.start)
    }

    @Test
    fun ambiguousLiteralProductInsideEachGroupCannotReleaseOnlyOneMember(): Unit {
        val instruction = "rice, all purpose flour, one each"
        val items = listOf(
            Item(0, "rice", name = "rice"),
            Item(1, "all purpose flour", name = "all purpose flour"),
        )
        val proof = group(
            instruction, instruction, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty())
        assertTrue(prepared.items.none { it.query == "all purpose flour" })
    }

    @Test
    fun trailingUniversalIdentityRequiresCompleteHardClauseAndAllMembers(): Unit {
        val examples = listOf(
            // The group omits a sibling before or after its claimed member range.
            Triple("one rice and two dal and three flour, all BrandX.", "all BrandX", listOf(1, 2)),
            Triple("one rice and two dal and three flour, all BrandX.", "all BrandX", listOf(0, 1)),
            // The identity is interleaved between members or belongs to another sentence.
            Triple("one rice, all BrandX, two dal.", "all BrandX", listOf(0, 1)),
            Triple("one rice and two dal. all BrandX.", "all BrandX", listOf(0, 1)),
        )
        examples.forEach { (instruction, header, indices) ->
            val items = if (instruction.contains("three flour")) listOf(
                Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
                Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
                Item(2, "three flour", "3", "count", "three", name = "BrandX flour"),
            ) else listOf(
                Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
                Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
            )
            val proof = group(
                instruction, instruction, header, indices,
                indices.map { index -> Member(index, items[index].quantity, "count", "local") },
                "local_amounts_verified",
            )
            val prepared = runCatching {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft.prepareForReview()
            }.getOrNull()
            assertFalse("incomplete or non-contiguous group must not prepare all rows: $instruction",
                prepared?.let { it.items.size == items.size && it.excludedText.isEmpty() } == true)
        }
    }

    @Test
    fun trailingUniversalIdentityMustBePreservedAndCannotHideConditionsOrAmounts(): Unit {
        val instruction = "one rice and two dal, all from BrandX."
        val items = listOf(
            Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
            Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
        )
        val proof = group(
            instruction, instruction, "all from BrandX", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        val droppedIdentity = items.mapIndexed { index, item ->
            row(if (index == 1) item.copy(name = "dal") else item, instruction)
        }
        val droppedPreparation = GroceryIntentReviewV2Response.parse(
            response(instruction, droppedIdentity, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertTrue(droppedPreparation.items.isEmpty())
        assertTrue(droppedPreparation.excludedText.isNotEmpty())

        listOf("all BrandX if available", "not all BrandX").forEach { unsafeHeader ->
            val unsafeInstruction = "one rice and two dal, $unsafeHeader."
            val unsafeItems = items.map { item ->
                val start = unsafeInstruction.indexOf(item.source)
                item.copy(index = item.index, name = "BrandX ${item.source.substringAfter(' ')}")
                    .also { require(start >= 0) }
            }
            val unsafeGroup = group(
                unsafeInstruction, unsafeInstruction, unsafeHeader, listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
                "local_amounts_verified",
            )
            val unsafePrepared = runCatching {
                GroceryIntentReviewV2Response.parse(
                    response(unsafeInstruction, unsafeItems.map { row(it, unsafeInstruction) }, listOf(unsafeGroup)),
                    unsafeInstruction,
                ).draft.prepareForReview()
            }.getOrNull()
            assertFalse(unsafePrepared?.let { it.items.size == 2 && it.excludedText.isEmpty() } == true)
        }

        // Amount proofs cannot be attached to the universal identity quote when
        // the corresponding amount text is absent from the original source.
        val suffixStart = instruction.indexOf("all from BrandX")
        val inventedEach = proof.replace(
            "\"each\":null",
            "\"each\":{\"source\":{\"text\":\"all from BrandX\",\"start_utf16\":$suffixStart," +
                "\"end_utf16\":${suffixStart + "all from BrandX".length}},\"amount_text\":\"one each\",\"value\":1,\"unit\":\"count\"}",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(inventedEach)), instruction,
            )
        }
        val inventedTotal = proof.replace(
            "\"total\":null",
            "\"total\":{\"source\":{\"text\":\"all from BrandX\",\"start_utf16\":$suffixStart," +
                "\"end_utf16\":${suffixStart + "all from BrandX".length}},\"amount_text\":\"three\",\"value\":3,\"unit\":\"count\"}",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(inventedTotal)), instruction,
            )
        }
    }

    @Test
    fun trailingUniversalIdentityRequiresEveryLiteralIdentityTokenInEachName(): Unit {
        val instruction = "one rice and two dal, all from BrandX snacks."
        val proof = group(
            instruction, instruction, "all from BrandX snacks", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )

        val literalNames = listOf(
            Item(0, "one rice", "1", "count", "one", name = "BrandX snacks rice"),
            Item(1, "two dal", "2", "count", "two", name = "BrandX snacks dal"),
        )
        val accepted = GroceryIntentReviewV2Response.parse(
            response(instruction, literalNames.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, accepted.items.size)
        assertTrue(accepted.items.all { it.query.contains("snacks") })
        assertTrue(accepted.excludedText.isEmpty())

        val adversarialNames = listOf(
            listOf("BrandX snacks rice", "BrandX dal"), // Missing on a sibling.
            listOf("BrandX snacks rice", "BrandX treats dal"), // Category-like alias is not literal proof.
            listOf("BrandX snacks rice", "BrandY snacks dal"), // Brand identity changed.
            listOf("BrandX snacks rice", "BrandX snack dal"), // Singular alias is not the quoted token.
        )
        adversarialNames.forEachIndexed { index, names ->
            val adversarial = listOf(
                Item(0, "one rice", "1", "count", "one", name = names[0]),
                Item(1, "two dal", "2", "count", "two", name = names[1]),
            )
            val prepared = runCatching {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, adversarial.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft.prepareForReview()
            }.getOrNull()
            assertFalse("postfix identity must be literal in each item name case $index",
                prepared?.let { it.items.size == 2 && it.excludedText.isEmpty() } == true)
        }

        // A token carried as a required tag may be eligible only if it also
        // survives into every final matching query.
        val requiredOnlyItems = listOf(
            Item(0, "one rice", "1", "count", "one", name = "BrandX snacks rice"),
            Item(1, "two dal", "2", "count", "two", name = "BrandX dal", required = listOf("snacks")),
        )
        val requiredOnly = GroceryIntentReviewV2Response.parse(
            response(instruction, requiredOnlyItems.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, requiredOnly.items.size)
        assertTrue(requiredOnly.excludedText.isEmpty())
        assertTrue(requiredOnly.unresolvedText.isEmpty())
        assertTrue(requiredOnly.items.all { it.query.contains("snacks") })
        assertTrue(requiredOnly.items.all { it.strictMatchPhrase?.contains("snacks") == true })

        val bareAllInstruction = "one rice and two dal, all BrandX."
        val bareAllItems = listOf(
            Item(0, "one rice", "1", "count", "one", name = "BrandX rice"),
            Item(1, "two dal", "2", "count", "two", name = "BrandX dal"),
        )
        val bareAllProof = group(
            bareAllInstruction, bareAllInstruction, "all BrandX", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        val bareAll = runCatching {
            GroceryIntentReviewV2Response.parse(
                response(bareAllInstruction, bareAllItems.map { row(it, bareAllInstruction) }, listOf(bareAllProof)),
                bareAllInstruction,
            ).draft.prepareForReview()
        }.getOrNull()
        assertFalse(bareAll?.let { it.items.size == 2 && it.excludedText.isEmpty() } == true)

        val ambiguous = "one rice and two dal, All Out."
        val ambiguousProof = group(
            ambiguous, ambiguous, "All Out", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        val ambiguousItems = listOf(
            Item(0, "one rice", "1", "count", "one", name = "Out rice"),
            Item(1, "two dal", "2", "count", "two", name = "Out dal"),
        )
        val ambiguousPrepared = runCatching {
            GroceryIntentReviewV2Response.parse(
                response(ambiguous, ambiguousItems.map { row(it, ambiguous) }, listOf(ambiguousProof)), ambiguous,
            ).draft.prepareForReview()
        }.getOrNull()
        assertFalse(ambiguousPrepared?.let { it.items.size == 2 && it.sharedGroupNotes.isNotEmpty() } == true)
    }

    @Test
    fun neutralPleaseGetPrefixAndTerminalPeriodAreScopedAsOneHeadlessGroup(): Unit {
        val instruction = "Please get BrandA biscuits, BrandB milk, one each."
        val items = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"))
        val bodyScope = "BrandA biscuits, BrandB milk, one each"
        listOf(bodyScope, instruction, "$bodyScope.").forEach { candidateSource ->
            val valid = group(
                instruction, candidateSource, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            // The third scope includes only the terminal period beyond the two member primaries and distribution.
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(valid)), instruction,
            )
        }

        val nextSentence = "Please get BrandA biscuits, BrandB milk, one each. Also get BrandC chips."
        val nextSentenceRows = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"), Item(2, "BrandC chips"))
        val firstSentenceGroup = group(
            nextSentence, "BrandA biscuits, BrandB milk, one each.", null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        GroceryIntentReviewV2Response.parse(
            response(nextSentence, nextSentenceRows.map { row(it, nextSentence) }, listOf(firstSentenceGroup)), nextSentence,
        )
    }

    @Test
    fun headlessScopeRejectsMeaningfulPrefixesTailsAndPunctuationSplits(): Unit {
        val baselineInstruction = "Please get BrandA biscuits, BrandB milk, one each."
        val baselineItems = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"))
        val baseline = group(
            baselineInstruction, "BrandA biscuits, BrandB milk, one each", null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        GroceryIntentReviewV2Response.parse(
            response(baselineInstruction, baselineItems.map { row(it, baselineInstruction) }, listOf(baseline)), baselineInstruction,
        )

        val malformed = listOf(
            "organic BrandA biscuits, BrandB milk, one each",
            "BrandA biscuits for BrandC, BrandB milk, one each",
            "two BrandA biscuits, BrandB milk, one each",
            "dairy-free BrandA biscuits, BrandB milk, one each",
            "if available BrandA biscuits, BrandB milk, one each",
            "BrandA biscuits, BrandB milk, one each, no substitutions",
            "BrandA biscuits, BrandB milk, one each, BrandC chips",
            "BrandA biscuits. BrandB milk, one each",
            "BrandA biscuits... BrandB milk, one each",
            "BrandA biscuits .5, BrandB milk, one each",
            "BrandA biscuits, BrandB milk, one each..",
            "BrandA biscuits, BrandB milk, one each...",
            "BrandA biscuits, BrandB milk, one each.5",
            "Please get one each BrandA biscuits, BrandB milk",
            "Please get home BrandA biscuits, BrandB milk, one each",
            "Please get rice, BrandA biscuits, BrandB milk, one each",
        )
        malformed.forEach { instruction ->
            val items = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"))
            val candidate = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(candidate)), instruction)
            }
        }
        val body = "BrandA biscuits, BrandB milk, one each."
        listOf("Please get rice, ", "Please get home ", "Please get sugar-free ",
            "For my diabetic father, ").forEach { prefix ->
            val instruction = prefix + body
            val candidate = group(
                instruction, body, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, baselineItems.map { row(it, instruction) }, listOf(candidate)), instruction,
                )
            }
        }
    }

    @Test fun headlessPhysicalEachAcceptsClosedKejiAliasAndRejectsUnicodeNearMatches() {
        listOf("kg", "keji", "KEJI").forEach { unit ->
            val instruction = "one $unit each of rice and lentils"
            val items = listOf(Item(0, "rice", name = "rice"), Item(1, "lentils", name = "lentils"))
            val proof = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "kg", "each"), Member(1, "1", "kg", "each")),
                "each_verified", each = Amount("one", "1", "kg", "one $unit each"),
            )
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertEquals(unit, listOf(Quantity.Weight(1000), Quantity.Weight(1000)), prepared.items.map { it.quantity })
            assertTrue(unit, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        listOf("kej\u0131", "\u212Aeji", "keji\u0301").forEach { alias ->
            val instruction = "one $alias each of rice and lentils"
            val items = listOf(Item(0, "rice", name = "rice"), Item(1, "lentils", name = "lentils"))
            val proof = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "kg", "each"), Member(1, "1", "kg", "each")),
                "each_verified", each = Amount("one", "1", "kg", "one $alias each"),
            )
            val prepared = try {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft.prepareForReview()
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            assertTrue("Unicode near-match became actionable", prepared.items.isEmpty())
        }
    }

    @Test
    fun typedHeadlessEachGapAllowsOnlyClosedInitialScope(): Unit {
        listOf(
            "one each of BrandA rice, BrandA nuts.",
            "one each of the BrandA rice, BrandA nuts.",
        ).forEachIndexed { index, instruction ->
            val items = listOf(Item(0, "BrandA rice", name = "BrandA rice"), Item(1, "BrandA nuts", name = "BrandA nuts"))
            val proof = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            val parsed = runCatching {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft
            }
            assertTrue("headless each positive case $index should parse", parsed.isSuccess)
            val decoded = requireNotNull(parsed.getOrNull())
            assertEquals(listOf(0, 1), decoded.groups.single().memberIndices)
            val prepared = decoded.prepareForReview()
            assertEquals(2, prepared.items.size)
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
            prepared.items.forEach {
                assertEquals(com.example.beta.automation.Quantity.Count(1), it.quantity)
                assertTrue(it.query.contains("BrandA"))
            }
        }

        val headed = "BrandA: one each of rice and nuts."
        val headedItems = listOf(Item(0, "rice", name = "rice"), Item(1, "nuts", name = "nuts"))
        val headedProof = group(
            headed, headed, "BrandA:", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        GroceryIntentReviewV2Response.parse(
            response(headed, headedItems.map { row(it, headed) }, listOf(headedProof)), headed,
        )

        val malformed = listOf(
            "of one each BrandA rice, BrandA nuts.",
            "one each BrandA rice, BrandA nuts, of.",
            "one each organic BrandA rice, BrandA nuts.",
            "one each two BrandA rice, BrandA nuts.",
            "one each sugar-free BrandA rice, BrandA nuts.",
            "one each BrandB BrandA rice, BrandA nuts.",
            "one each a BrandA rice, BrandA nuts.",
            "one each an BrandA rice, BrandA nuts.",
            "one each ek BrandA rice, BrandA nuts.",
        )
        malformed.forEachIndexed { index, instruction ->
            val items = listOf(Item(0, "BrandA rice", name = "rice"), Item(1, "BrandA nuts", name = "nuts"))
            val proof = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            assertThrows("malformed headless gap case $index", IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                )
            }
        }

        val hidden = "one each of BrandA rice, BrandA nuts, and BrandC flour."
        val hiddenItems = listOf(
            Item(0, "BrandA rice", name = "rice"),
            Item(1, "BrandA nuts", name = "nuts"),
            Item(2, "BrandC flour", name = "flour"),
        )
        val hiddenProof = group(
            hidden, hidden, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(hidden, hiddenItems.map { row(it, hidden) }, listOf(hiddenProof)), hidden,
            )
        }

        val tailed = "one each of BrandA rice, BrandA nuts, no substitutions."
        val shortened = "one each of BrandA rice, BrandA nuts"
        val tailItems = listOf(Item(0, "BrandA rice", name = "rice"), Item(1, "BrandA nuts", name = "nuts"))
        val shortenedProof = group(
            tailed, shortened, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(tailed, tailItems.map { row(it, tailed) }, listOf(shortenedProof)), tailed,
            )
        }
    }

    @Test
    fun typedMemberArticleRequiresLiteralLowercaseArticleAfterASeparator(): Unit {
        listOf(
            "BrandA paneer, and the BrandA curd, one each.",
            "BrandA paneer, the BrandA curd, one each.",
            "BrandA paneer, aur the BrandA curd, one each.",
            "BrandA paneer, bhi the BrandA curd, one each.",
            "BrandA paneer & the BrandA curd, one each.",
            "BrandA paneer,\nthe BrandA curd, one each.",
        ).forEachIndexed { index, instruction ->
            val items = listOf(
                Item(0, "BrandA paneer", name = "BrandA paneer"),
                Item(1, "BrandA curd", name = "BrandA curd"),
            )
            val proof = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            val parsed = runCatching {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft
            }
            assertTrue("article gap positive case $index should parse", parsed.isSuccess)
            val decoded = requireNotNull(parsed.getOrNull())
            val prepared = decoded.prepareForReview()
            assertEquals(2, prepared.items.size)
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
            prepared.items.forEach {
                assertEquals(com.example.beta.automation.Quantity.Count(1), it.quantity)
                assertTrue(it.query.contains("BrandA"))
            }
        }

        listOf(
            "BrandA paneer, and The BrandA curd, one each.",
            "BrandA paneer, and the the BrandA curd, one each.",
            "BrandA paneer, and a BrandA curd, one each.",
            "BrandA paneer, and an BrandA curd, one each.",
            "BrandA paneer, and ek BrandA curd, one each.",
            "BrandA paneer the, BrandA curd, one each.",
            "BrandA paneer the BrandA curd, one each.",
            "BrandA paneer, and the low-fat BrandA curd, one each.",
        ).forEachIndexed { index, instruction ->
            val items = listOf(Item(0, "BrandA paneer", name = "paneer"), Item(1, "BrandA curd", name = "curd"))
            val proof = group(
                instruction, instruction, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            assertThrows("malformed article case $index", IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                )
            }
        }
    }

    @Test
    fun closedRequestGapIsAllowedOnlyBeforeLocallyProvenMembers(): Unit {
        listOf(
            "BrandA fruit cups: I want one blueberry and one mango.",
            "BrandA fruit cups: I need one blueberry and one mango.",
            "BrandA fruit cups: I would like one blueberry and one mango.",
            "BrandA fruit cups: we want one blueberry and one mango.",
            "BrandA fruit cups: we need one blueberry and one mango.",
            "BrandA fruit cups: we would like one blueberry and one mango.",
            "BrandA fruit cups: mujhe chahiye one blueberry and one mango.",
        ).forEach { instruction ->
            val items = listOf(
                Item(0, "one blueberry", "1", "count", "one", name = "blueberry"),
                Item(1, "one mango", "1", "count", "one", name = "mango"),
            )
            val proof = group(
                instruction, instruction, "BrandA fruit cups:", listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "1", "count", "local")),
                "local_amounts_verified", packaging = "cup",
            )
            val decoded = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft
            assertEquals(listOf(0, 1), decoded.groups.single().memberIndices)
        }

        val eachInstruction = "BrandA fruit cups: I want blueberry and mango, one each."
        val eachItems = listOf(Item(0, "blueberry", name = "blueberry"), Item(1, "mango", name = "mango"))
        val eachProof = group(
            eachInstruction, eachInstruction, "BrandA fruit cups:", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", packaging = "cup", each = Amount("one", "1", "count", "one each"),
        )
        GroceryIntentReviewV2Response.parse(
            response(eachInstruction, eachItems.map { row(it, eachInstruction) }, listOf(eachProof)), eachInstruction,
        )

        val malformed = listOf(
            "BrandA fruit cups: one blueberry and I want one mango.",
            "BrandA fruit cups: one blueberry and one mango, I want.",
            "BrandA fruit cups: I want blueberry and mango.",
            "BrandA fruit cups: I want sugar-free one blueberry and one mango.",
            "BrandA fruit cups: I want two one blueberry and one mango.",
            "BrandA fruit cups: I want rice one blueberry and one mango.",
        )
        malformed.forEachIndexed { index, instruction ->
            val unstated = " I want blueberry and mango" in instruction
            val items = if (unstated) {
                listOf(Item(0, "blueberry", name = "blueberry"), Item(1, "mango", name = "mango"))
            } else {
                listOf(
                    Item(0, "one blueberry", "1", "count", "one", name = "blueberry"),
                    Item(1, "one mango", "1", "count", "one", name = "mango"),
                )
            }
            val proof = group(
                instruction, instruction, "BrandA fruit cups:", listOf(0, 1),
                if (unstated) membersUnstated(0, 1) else listOf(
                    Member(0, "1", "count", "local"), Member(1, "1", "count", "local"),
                ),
                if (unstated) "amounts_unspecified" else "local_amounts_verified", packaging = "cup",
            )
            assertThrows("malformed request-gap case $index", IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
                )
            }
        }
    }

    @Test
    fun headedGroupTerminalPeriodDoesNotClaimNextSentenceProduct(): Unit {
        val source = "cups: blueberry, mango."
        listOf("", " rice").forEach { suffix ->
            val instruction = source + suffix
            val items = listOf(Item(0, "blueberry"), Item(1, "mango")) +
                if (suffix.isNotEmpty()) listOf(Item(2, "rice")) else emptyList()
            val candidate = group(
                instruction, source, "cups:", listOf(0, 1),
                listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated")),
                "amounts_unspecified", packaging = "cup",
            )
            val parsed = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(candidate)), instruction,
            ).draft
            assertEquals(listOf(0, 1), parsed.groups.single().memberIndices)
            assertEquals(items.size, parsed.readyRows.size)
        }
    }

    @Test
    fun headedGroupSourceMustNotStopBeforeAnUnownedListTail(): Unit {
        val tails = listOf(
            ", and 1 flour",
            " aur 1 flour",
            "\n1 flour",
            ", no substitutions",
            " la do",
            ", thank you",
            ", deliver by 6 pm",
            ".5",
        )
        tails.forEach { tail ->
            val instruction = "BrandA: 1 rice, 1 beans$tail"
            val items = listOf(
                Item(0, "1 rice", "1", "count", "1", name = "rice"),
                Item(1, "1 beans", "1", "count", "1", name = "beans"),
            ) + if (tail.contains("flour")) listOf(Item(2, "1 flour", "1", "count", "1", name = "flour")) else emptyList()
            val shortenedSource = "BrandA: 1 rice, 1 beans"
            val candidate = group(
                instruction, shortenedSource, "BrandA:", listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "1", "count", "local")),
                "local_amounts_verified",
            )

            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(candidate)), instruction,
                )
            }
        }
    }

    @Test
    fun multipleHeadedSectionsWithoutExplicitTerminatorRemainUnsupported(): Unit {
        // Known V2 limitation: a following proved heading needs a future
        // cross-group boundary proof; never treat every newline as one.
        listOf("\n", ", ").forEach { separator ->
            val first = "BrandA: 1 rice, 1 beans"
            val second = "BrandB: 1 milk, 1 curd"
            val instruction = first + separator + second
            val items = listOf("rice", "beans", "milk", "curd").mapIndexed { index, name ->
                Item(index, "1 $name", "1", "count", "1", name = name)
            }
            val groups = listOf(first, second).mapIndexed { index, source ->
                val members = listOf(index * 2, index * 2 + 1)
                group(instruction, source, if (index == 0) "BrandA:" else "BrandB:", members,
                    members.map { Member(it, "1", "count", "local") }, "local_amounts_verified")
            }
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, groups), instruction,
                )
            }
        }

        val first = "BrandA: 1 rice, 1 beans"
        val second = "BrandB: 1 milk, 1 curd"
        val instruction = "$first;\n$second"
        val items = listOf("rice", "beans", "milk", "curd").mapIndexed { index, name ->
            val brand = if (index < 2) "BrandA" else "BrandB"
            Item(index, "1 $name", "1", "count", "1", name = "$brand $name")
        }
        val groups = listOf(first, second).mapIndexed { groupIndex, source ->
            val members = listOf(groupIndex * 2, groupIndex * 2 + 1)
            group(
                instruction, source, if (groupIndex == 0) "BrandA:" else "BrandB:", members,
                members.map { Member(it, "1", "count", "local") }, "local_amounts_verified",
            )
        }
        val separated = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, groups), instruction,
        ).draft.prepareForReview()
        assertEquals(4, separated.items.size)
        assertTrue(separated.excludedText.isEmpty())
        assertTrue(separated.unresolvedText.isEmpty())
    }

    @Test
    fun headedGroupRequiresCompleteThreeMemberSourceAndAllowsSeparateFollowingProduct(): Unit {
        val fullInstruction = "BrandA: 1 rice, 1 beans, 1 flour"
        val threeItems = listOf(
            Item(0, "1 rice", "1", "count", "1", name = "rice"),
            Item(1, "1 beans", "1", "count", "1", name = "beans"),
            Item(2, "1 flour", "1", "count", "1", name = "flour"),
        )
        val fullGroup = group(
            fullInstruction, fullInstruction, "BrandA:", listOf(0, 1, 2),
            listOf(
                Member(0, "1", "count", "local"),
                Member(1, "1", "count", "local"),
                Member(2, "1", "count", "local"),
            ), "local_amounts_verified",
        )
        val full = GroceryIntentReviewV2Response.parse(
            response(fullInstruction, threeItems.map { row(it, fullInstruction) }, listOf(fullGroup)), fullInstruction,
        ).draft
        assertEquals(listOf(0, 1, 2), full.groups.single().memberIndices)
        assertEquals(3, full.readyRows.size)

        val base = "BrandA: 1 rice, 1 beans"
        listOf(";", ".", "!", "?").forEach { separator ->
            val instruction = "$base$separator 1 flour"
            val sourceVariants = if (separator == ".") listOf(base, "$base.") else listOf(base)
            sourceVariants.forEach { source ->
                val rows = listOf(
                    Item(0, "1 rice", "1", "count", "1", name = "rice"),
                    Item(1, "1 beans", "1", "count", "1", name = "beans"),
                    Item(2, "1 flour", "1", "count", "1", name = "flour"),
                )
                val firstGroup = group(
                    instruction, source, "BrandA:", listOf(0, 1),
                    listOf(Member(0, "1", "count", "local"), Member(1, "1", "count", "local")),
                    "local_amounts_verified",
                )
                val decoded = GroceryIntentReviewV2Response.parse(
                    response(instruction, rows.map { row(it, instruction) }, listOf(firstGroup)), instruction,
                ).draft
                assertEquals(listOf(0, 1), decoded.groups.single().memberIndices)
                assertEquals(3, decoded.readyRows.size)
            }
        }
    }

    @Test
    fun exactEachDistributionCanBracketAllThreeSiblingMembers(): Unit {
        val examples = listOf(
            "BrandA biscuits, BrandB milk, BrandC chips, one each" to "one each",
            "one each, BrandA biscuits, BrandB milk, BrandC chips" to "one each",
        )
        examples.forEach { (instruction, eachSource) ->
            val items = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"), Item(2, "BrandC chips"))
            val group = group(
                instruction, instruction, null, listOf(0, 1, 2),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each"), Member(2, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", eachSource),
            )
            val decoded = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction,
            ).draft
            assertEquals(listOf(0, 1, 2), decoded.groups.single().memberIndices)
        }
    }

    @Test
    fun eachDistributionBetweenSiblingMembersAndUnicodeSentenceBoundaryFailClosed(): Unit {
        val cases = listOf(
            Triple("BrandA biscuits, two each, BrandB milk", null, listOf("BrandA biscuits", "BrandB milk")),
            Triple("BrandA fruit: strawberry, two each, mango", "BrandA fruit:", listOf("strawberry", "mango")),
            Triple("Earlier sentence.\u00a0BrandA biscuits, BrandB milk, one each", null, listOf("BrandA biscuits", "BrandB milk")),
        )
        cases.forEach { (instruction, header, names) ->
            val items = names.mapIndexed { index, name -> Item(index, name) }
            val amountToken = if (instruction.contains("one each")) "one" else "two"
            val amountValue = if (amountToken == "one") "1" else "2"
            val memberSpecs = listOf(Member(0, amountValue, "count", "each"), Member(1, amountValue, "count", "each"))
            val scopedSource = if (instruction.contains("\u00a0")) instruction.substringAfter(".\u00a0") else instruction
            val group = group(
                instruction, scopedSource, header,
                listOf(0, 1), memberSpecs,
                "each_verified", each = Amount(amountToken, amountValue, "count", "$amountToken each"),
            )
            // Ensure a valid exact-distribution baseline is accepted before exercising the misplaced span.
            val baselineInstruction = "${header?.let { "$it " }.orEmpty()}${items.joinToString(", ") { it.source }}, $amountToken each"
            val baseline = group(
                baselineInstruction, baselineInstruction, header, listOf(0, 1),
                memberSpecs,
                "each_verified", each = Amount(amountToken, amountValue, "count", "$amountToken each"),
            )
            GroceryIntentReviewV2Response.parse(
                response(baselineInstruction, items.map { row(it, baselineInstruction) }, listOf(baseline)), baselineInstruction,
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction)
            }
        }
    }

    @Test
    fun rejectsUnsafeHeaderlessEachProofs(): Unit {
        val instruction = "BrandA biscuits, BrandB milk, one each"
        val items = listOf(Item(0, "BrandA biscuits"), Item(1, "BrandB milk"))
        val valid = group(
            instruction, instruction, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(valid)), instruction)
        val noEach = valid.substringBefore(",\"each\":") + ",\"each\":null}"
        val totalStart = instruction.indexOf("one each")
        val totalSource = "\"text\":\"one each\",\"start_utf16\":$totalStart,\"end_utf16\":${instruction.length}"
        val invalid = listOf(
            noEach,
            valid.replace("\"amount_status\":\"each_verified\"", "\"amount_status\":\"amounts_unspecified\""),
            valid.replace("\"packaging\":null", "\"packaging\":\"box\""),
            valid.replace("\"total\":null", "\"total\":{\"source\":{$totalSource},\"amount_text\":\"one\",\"value\":1,\"unit\":\"count\"}"),
            valid.replace("\"members\":[", "\"members\":[{\"row_index\":0,\"value\":1,\"unit\":\"count\",\"origin\":\"each\"},"),
            valid.replace("\"value\":1,\"unit\":\"count\",\"origin\":\"each\"}", "\"value\":2,\"unit\":\"count\",\"origin\":\"each\"}"),
            valid.replace("\"header\":null", "\"header\":{\"text\":\"BrandA\",\"start_utf16\":0,\"end_utf16\":6}"),
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(candidate)), instruction)
            }
        }

        val scopedOutCases = listOf(
            Triple("rice, BrandA biscuits, BrandB milk, one each", "BrandA biscuits, BrandB milk, one each", listOf("BrandA biscuits", "BrandB milk")),
            Triple("one each BrandA biscuits, BrandB milk, rice", "one each BrandA biscuits, BrandB milk", listOf("BrandA biscuits", "BrandB milk")),
            Triple("BrandA items: BrandA biscuits, BrandB milk, one each", "BrandA biscuits, BrandB milk, one each", listOf("BrandA biscuits", "BrandB milk")),
            Triple("ek ek BrandA biscuits, BrandB milk, one each", "BrandA biscuits, BrandB milk, one each", listOf("BrandA biscuits", "BrandB milk")),
        )
        scopedOutCases.forEach { (scopedInstruction, scopedSource, names) ->
            val scopedItems = names.mapIndexed { index, name -> Item(index, name) }
            val scopedGroup = group(
                scopedInstruction, scopedSource, null, listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(scopedInstruction, scopedItems.map { row(it, scopedInstruction) }, listOf(scopedGroup)), scopedInstruction,
                )
            }
        }

        val omittedLocalNumber = "two BrandA biscuits, BrandB milk, one each"
        val omittedItems = listOf(Item(0, "two BrandA biscuits"), Item(1, "BrandB milk"))
        val omitted = group(
            omittedLocalNumber, omittedLocalNumber, null, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(omittedLocalNumber, omittedItems.map { row(it, omittedLocalNumber) }, listOf(omitted)), omittedLocalNumber)
        }
    }

    @Test
    fun acceptsUnspecifiedGroupWithoutInventingQuantities(): Unit {
        val instruction = "Fruit cups: strawberry and mango"
        val items = listOf(Item(0, "strawberry"), Item(1, "mango"))
        val group = group(
            instruction, instruction, "Fruit cups", listOf(0, 1),
            listOf(Member(0, null, "unspecified", "not_stated"), Member(1, null, "unspecified", "not_stated")),
            "amounts_unspecified", packaging = "cup",
        )

        val decoded = GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction).draft

        assertEquals("amounts_unspecified", decoded.groups.single().amountStatus)
        assertNull(decoded.groups.single().members.first().value)
    }

    @Test
    fun repeatedHeadingIsScopedByDistinctUtf16SourceQuotes(): Unit {
        val instruction = "😀 Fruit cups: strawberry and mango. Fruit cups: pear and banana"
        val items = listOf(Item(0, "strawberry"), Item(1, "mango"), Item(2, "pear"), Item(3, "banana"))
        val firstSource = "Fruit cups: strawberry and mango"
        val secondSource = "Fruit cups: pear and banana"
        val groups = listOf(
            group(instruction, firstSource, "Fruit cups", listOf(0, 1), membersUnstated(0, 1), "amounts_unspecified", "cup"),
            group(instruction, secondSource, "Fruit cups", listOf(2, 3), membersUnstated(2, 3), "amounts_unspecified", "cup"),
        )

        val decoded = GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, groups), instruction).draft

        assertEquals(2, decoded.groups.size)
        assertEquals(instruction.indexOf(firstSource), decoded.groups[0].source.startUtf16)
        assertEquals(instruction.indexOf(secondSource), decoded.groups[1].source.startUtf16)
        val decodedHeader = requireNotNull(decoded.groups[1].header)
        assertEquals("Fruit cups".length, decodedHeader.endUtf16 - decodedHeader.startUtf16)
    }

    @Test
    fun rejectsBadTotalBlockedMemberOverlapAndUnknownGroupFields(): Unit {
        val instruction = "Fruit cups total three: one strawberry and two mangoes"
        val items = listOf(
            Item(0, "one strawberry", "1", "count", "one"),
            Item(1, "two mangoes", "2", "count", "two"),
        )
        val valid = group(
            instruction, instruction, "Fruit cups total three", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "total_verified", "cup", Amount("three", "3", "count"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(valid.replace("\"value\":3", "\"value\":4"))), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, listOf(row(items[0], instruction)), listOf(valid), sourceCount = 2), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(valid.replace("\"amount_status\":\"total_verified\"", "\"amount_status\":\"total_verified\",\"action\":\"add\""))), instruction)
        }
    }

    @Test
    fun rejectsEachThatWouldOverrideExplicitLocalQuantityAndNumericOmission(): Unit {
        val instruction = "Fruit cups: two strawberry and one each mango"
        val items = listOf(Item(0, "two strawberry", "2", "count", "two"), Item(1, "mango"))
        val conflict = group(
            instruction, instruction, "Fruit cups", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", "cup", each = Amount("one", "1", "count", "one each"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(conflict)), instruction)
        }

        val omittedNumeric = "Fruit cups: one each strawberry and two mango"
        val omittedItems = listOf(Item(0, "strawberry"), Item(1, "two mango"))
        val omittedGroup = group(
            omittedNumeric, omittedNumeric, "Fruit cups", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", "cup", each = Amount("one", "1", "count"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(omittedNumeric, omittedItems.map { row(it, omittedNumeric) }, listOf(omittedGroup)), omittedNumeric)
        }
    }

    @Test
    fun rejectsFakeEachGrammarAndHeaderNumberWithoutTotal(): Unit {
        val instruction = "Fruit cups: two in total strawberry and mango"
        val items = listOf(Item(0, "strawberry"), Item(1, "mango"))
        val fakeEach = group(
            instruction, instruction, "Fruit cups", listOf(0, 1),
            listOf(Member(0, "2", "count", "each"), Member(1, "2", "count", "each")),
            "each_verified", "cup", each = Amount("two", "2", "count", "two in total"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(fakeEach)), instruction)
        }

        val omittedTotal = "Fruit cups four: strawberry and mango"
        val omittedRows = listOf(Item(0, "strawberry"), Item(1, "mango"))
        val noTotal = group(
            omittedTotal, omittedTotal, "Fruit cups four", listOf(0, 1), membersUnstated(0, 1),
            "amounts_unspecified", "cup",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(response(omittedTotal, omittedRows.map { row(it, omittedTotal) }, listOf(noTotal)), omittedTotal)
        }
    }

    @Test
    fun retainsRepeatedGluedPerItemPackAtomsWithinTheirOwnPrimaryQuotes(): Unit {
        val instruction = "Juice: 500ml orange and 500ml mango, one each"
        val items = listOf(
            Item(0, "500ml orange", pack = "500", packUnit = "ml", packText = "500"),
            Item(1, "500ml mango", pack = "500", packUnit = "ml", packText = "500"),
        )
        val group = group(
            instruction, instruction, "Juice", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )

        val decoded = GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction).draft

        assertEquals("500", decoded.readyRows.first().item.packValue.toString())
        assertEquals("ml", decoded.readyRows.first().item.packUnit)

        listOf("SKU500ml orange", "1500ml orange", "0.500ml orange").forEach { cropped ->
            val unsafeInstruction = "Juice: $cropped and 500ml mango, one each"
            val unsafeItems = listOf(
                Item(0, cropped, pack = "500", packUnit = "ml", packText = "500"),
                Item(1, "500ml mango", pack = "500", packUnit = "ml", packText = "500"),
            )
            val unsafeGroup = group(
                unsafeInstruction, unsafeInstruction, "Juice", listOf(0, 1),
                listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(unsafeInstruction, unsafeItems.map { row(it, unsafeInstruction) }, listOf(unsafeGroup)),
                    unsafeInstruction,
                )
            }
        }
    }

    @Test
    fun rejectsEveryUnownedGroupGapAndTail(): Unit {
        val badSources = listOf(
            "Fruit cups: 1 milk product and 1 curd",
            "Fruit cups: 1 milk 2 and 1 curd",
            "Fruit cups: 1 milk do not substitute and 1 curd",
            "Fruit cups: 1 milk actually cancel and 1 curd",
            "Fruit cups: 1 milk and 1 curd plus rice",
            "Fruit cups: 1 milk, 1 curd, do not substitute organic milk, actually cancel curd",
            "Fruit cups: 1 milk, 1 curd, no organic versions",
        )
        badSources.forEach { instruction ->
            val items = listOf(
                Item(0, "1 milk", "1", "count", "1"),
                Item(1, "1 curd", "1", "count", "1"),
            )
            val group = group(
                instruction, instruction, "Fruit cups", listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "1", "count", "local")),
                "local_amounts_verified", packaging = "cup",
            )
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction)
            }
        }
    }

    @Test
    fun acceptsOnlyDocumentedGroupSeparatorsAndEachSpan(): Unit {
        val instruction = "Fruit cups: one each milk aur curd bhi"
        val items = listOf(Item(0, "milk"), Item(1, "curd"))
        val group = group(
            instruction, instruction, "Fruit cups", listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", packaging = "cup", each = Amount("one", "1", "count", "one each"),
        )
        val decoded = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(group)), instruction,
        ).draft
        assertEquals("each_verified", decoded.groups.single().amountStatus)

        val hyphenInstruction = "Fruit cups - 1 milk and 1 curd"
        val hyphenGroup = group(
            hyphenInstruction, hyphenInstruction, "Fruit cups", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified", packaging = "cup",
        )
        val localItems = listOf(Item(0, "1 milk", "1", "count", "1"), Item(1, "1 curd", "1", "count", "1"))
        assertEquals("local_amounts_verified", GroceryIntentReviewV2Response.parse(
            response(hyphenInstruction, localItems.map { row(it, hyphenInstruction) }, listOf(hyphenGroup)), hyphenInstruction,
        ).draft.groups.single().amountStatus)
    }

    @Test
    fun local_quantity_heading_owns_semicolon_scaffold_but_rejects_injection_and_shared_amounts(): Unit {
        val header = "BrandA ke liye alag section bana do; har BrandA ki apni ginti hai:"
        val instruction = "$header 1 milk and 2 curd"
        val items = listOf(Item(0, "1 milk", "1", "count", "1"), Item(1, "2 curd", "2", "count", "2"))
        val valid = group(
            instruction, instruction, header, listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        val decoded = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(valid)), instruction,
        ).draft
        assertEquals(1, decoded.groups.size)

        val injectedHeader = "BrandA ke liye alag section bana do; har BrandB ki apni ginti hai:"
        val injectedInstruction = "$injectedHeader 1 milk and 2 curd"
        val injected = group(
            injectedInstruction, injectedInstruction, injectedHeader, listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(injectedInstruction, items.map { row(it, injectedInstruction) }, listOf(injected)), injectedInstruction,
            )
        }

        val noAmountsInstruction = "BrandA items, har naam ke saamne jitne likhe hain utne hi lena: milk and curd"
        val noAmountRows = listOf(Item(0, "milk"), Item(1, "curd"))
        val noAmounts = group(
            noAmountsInstruction, noAmountsInstruction,
            "BrandA items, har naam ke saamne jitne likhe hain utne hi lena:", listOf(0, 1),
            membersUnstated(0, 1), "amounts_unspecified",
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(noAmountsInstruction, noAmountRows.map { row(it, noAmountsInstruction) }, listOf(noAmounts)), noAmountsInstruction,
            )
        }

        val eachTail = "one each: 1 milk and 2 curd"
        val eachInstruction = "$header $eachTail"
        val eachRows = listOf(Item(0, "milk"), Item(1, "curd"))
        val withEach = group(
            eachInstruction, eachInstruction, header, listOf(0, 1),
            listOf(Member(0, "1", "count", "each"), Member(1, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", eachTail.substringBefore(":")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(eachInstruction, eachRows.map { row(it, eachInstruction) }, listOf(withEach)), eachInstruction,
            )
        }

        val numericHeader = "1 BrandA items, har naam ke saamne jitne likhe hain utne hi lena:"
        val totalInstruction = "$numericHeader 1 milk and 2 curd"
        val totalRows = listOf(Item(0, "1 milk", "1", "count", "1"), Item(1, "2 curd", "2", "count", "2"))
        val withTotal = group(
            totalInstruction, totalInstruction, numericHeader, listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "total_verified", total = Amount("1", "1", "count", quoteSource = numericHeader),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(totalInstruction, totalRows.map { row(it, totalInstruction) }, listOf(withTotal)), totalInstruction,
            )
        }
    }

    @Test fun validatedFruitGroupCarriesCategoryIntoCandidateAndCartProof() {
        val header = "fruits items, take the quantity written beside each item:"
        val instruction = "$header 1 orange and 2 apples"
        val items = listOf(Item(0, "1 orange", "1", "count", "1"), Item(1, "2 apples", "2", "count", "2"))
        val proof = group(instruction, instruction, header, listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")), "local_amounts_verified")
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, prepared.items.size)
        assertTrue(prepared.items.all { it.intentQuantityProvenance?.produceCategory == IntentProduceCategory.FRUITS })
        val orange = prepared.items.first()
        val whole = SwiggyMcpClient.RecommendationCandidate(spinId = "whole", label = "Fresh Orange", subtitle = "1 pc")
        val juice = SwiggyMcpClient.RecommendationCandidate(spinId = "juice", label = "Orange Juice", subtitle = "1 L")
        val assessed = assessSwiggyIntentQuantity(orange, SwiggyMcpClient.Recommendations(candidates = listOf(whole, juice)))
        assertTrue(isSwiggyCandidateAllowed(assessed, whole))
        assertTrue(!isSwiggyCandidateAllowed(assessed, juice))
        assertUnqualifiedCountNeedsReview(assessed, whole, 1)
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessed, juice) }
    }

    @Test fun organizationalProduceRetainsExactLocalAmountsAndCategoryThroughPreparation() {
        val cases = listOf(
            Triple("Sabzi side:", Item(0, "tomato 1 kg", "1", "kg", "1", name = "tomato"),
                Item(1, "2 kg onion", "2", "kg", "2", name = "onion")),
            Triple("phir vegetables side:", Item(0, "1 kg tomato", "1", "kg", "1", name = "tomato"),
                Item(1, "onion 2 kg", "2", "kg", "2", name = "onion")),
            Triple("Fruits section:", Item(0, "apple 1.5 kg", "1.5", "kg", "1.5", name = "apple"),
                Item(1, "banana six", "6", "count", "six", name = "banana")),
            Triple("And vegetables:", Item(0, "tomato 1 kg", "1", "kg", "1", name = "tomato"),
                Item(1, "2 kg onion", "2", "kg", "2", name = "onion")),
            Triple("Phir sabzi:", Item(0, "aloo ek kilo", "1", "kg", "ek", name = "potato"),
                Item(1, "pyaaz do kilo", "2", "kg", "do", name = "onion")),
            Triple("Fruits —", Item(0, "apple 1.5 kg", "1.5", "kg", "1.5", name = "apple"),
                Item(1, "banana six", "6", "count", "six", name = "banana")),
            Triple("Vegetables:", Item(0, "tomato"), Item(1, "onion")),
        )
        for ((header, first, second) in cases) {
            val instruction = "$header ${first.source}, ${second.source}."
            val members = listOf(first, second).map { Member(it.index, it.quantity.takeUnless { it == "null" },
                it.quantityUnit, if (it.quantity == "null") "not_stated" else "local") }
            val proof = group(instruction, instruction, header, listOf(0, 1), members,
                if (members.all { it.value != null }) "local_amounts_verified" else "amounts_unspecified")
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, listOf(first, second).map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertEquals(header, 2, prepared.items.size)
            val category = if (header.startsWith("Fruits")) IntentProduceCategory.FRUITS else IntentProduceCategory.VEGETABLES
            assertTrue(prepared.items.all { it.intentQuantityProvenance?.produceCategory == category })
            assertEquals(listOf(first.source, second.source), prepared.items.map { it.intentQuantityProvenance?.primaryText })
        }
    }

    @Test fun scopedMeinIdentityRequiresEveryMemberAndPreservesOriginalNoteAndAmounts() {
        val header = "BrandA organic makhana mein,"
        val source = "$header 1 salted makhana, 2 cheese makhana"
        val instruction = "$source; 1 milk"
        val quantities = listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local"))

        val validRepresentations = listOf(
            listOf(
                Item(0, "1 salted makhana", "1", "count", "1", name = "BrandA organic salted makhana"),
                Item(1, "2 cheese makhana", "2", "count", "2", name = "BrandA organic cheese makhana"),
            ),
            listOf(
                Item(0, "1 salted makhana", "1", "count", "1", name = "salted makhana", required = listOf("BrandA", "organic")),
                Item(1, "2 cheese makhana", "2", "count", "2", name = "cheese makhana", required = listOf("BrandA", "organic")),
            ),
        )
        for (groupItems in validRepresentations) {
            val items = groupItems + Item(2, "1 milk", "1", "count", "1", name = "milk")
            val proof = group(instruction, source, header, listOf(0, 1), quantities, "local_amounts_verified")
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertEquals(3, prepared.items.size)
            assertTrue(prepared.items[0].query.contains("salted makhana"))
            assertTrue(prepared.items[1].query.contains("cheese makhana"))
            prepared.items.take(2).forEach { query ->
                assertTrue(query.query.contains("BrandA"))
                assertTrue(query.query.contains("organic"))
            }
            assertEquals("milk", prepared.items[2].query)
            assertEquals(listOf(Quantity.Count(1), Quantity.Count(2), Quantity.Count(1)),
                prepared.items.map { it.quantity })
            assertEquals(listOf(header), prepared.sharedGroupNotes)
            assertTrue(prepared.excludedText.isEmpty())
        }

        for (missingProperty in listOf("BrandA", "organic")) {
            val firstRequired = listOf("BrandA", "organic") - missingProperty
            val items = listOf(
                Item(0, "1 salted makhana", "1", "count", "1", name = "salted makhana", required = firstRequired),
                Item(1, "2 cheese makhana", "2", "count", "2", name = "cheese makhana", required = listOf("BrandA", "organic")),
                Item(2, "1 milk", "1", "count", "1", name = "milk"),
            )
            val proof = group(instruction, source, header, listOf(0, 1), quantities, "local_amounts_verified")
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertEquals(listOf("milk"), prepared.items.map { it.query })
            assertEquals(listOf(Quantity.Count(1)), prepared.items.map { it.quantity })
            assertTrue(prepared.excludedText.isNotEmpty() || prepared.unresolvedText.isNotEmpty())
        }

    }

    @Test fun sharedIngredientExclusionRemainsOneWholeCatalogueConstraint() {
        val header = "BrandA no palm oil:"
        val instruction = "$header 1 plain chips, 2 salt chips"
        for (excluded in listOf(listOf("palm oil"), listOf("palm", "oil"), emptyList())) {
            val items = listOf(
                Item(0, "1 plain chips", "1", "count", "1", name = "BrandA plain chips", excluded = excluded),
                Item(1, "2 salt chips", "2", "count", "2", name = "BrandA salt chips", excluded = excluded),
            )
            val proof = group(instruction, instruction, header, listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
                "local_amounts_verified")
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertEquals(excluded.toString(), if (excluded == listOf("palm oil")) 2 else 0, prepared.items.size)
            if (prepared.items.isNotEmpty()) {
                assertTrue(prepared.items.all { "palm oil" in it.avoidPhrases })
            }
        }
    }

    @Test fun sharedNewIngredientExclusionsMustBePreservedByEveryGroupMember() {
        val properties = listOf("chemicals", "preservatives", "artificial colours", "artificial flavours")
        val exclusions = properties
        val header = "BrandA ${properties.joinToString(", ") { "no $it" }}:"
        val instruction = "$header 1 plain chips, 2 salt chips"
        val proof = group(instruction, instruction, header, listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
            "local_amounts_verified")

        val complete = listOf(
            Item(0, "1 plain chips", "1", "count", "1", name = "BrandA plain chips", excluded = exclusions),
            Item(1, "2 salt chips", "2", "count", "2", name = "BrandA salt chips", excluded = exclusions),
        )
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, complete.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, prepared.items.size)
        assertTrue(prepared.items.all { it.avoidPhrases.containsAll(properties) })

        for (missing in properties) {
            val incomplete = complete.mapIndexed { index, item ->
                item.copy(excluded = if (index == 1) exclusions - missing else exclusions)
            }
            val held = GroceryIntentReviewV2Response.parse(
                response(instruction, incomplete.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertTrue("missing=$missing", held.items.isEmpty())
            assertTrue("missing=$missing", held.excludedText.isNotEmpty() || held.unresolvedText.isNotEmpty())
            assertTrue("missing=$missing", held.sharedGroupNotes.isEmpty())
        }
    }

    @Test fun organizationalSpiceFormAndIdentitySurviveBeforeAndAfterTheProductNoun() {
        for (spice in listOf("cumin", "pepper", "cardamom", "cinnamon", "nutmeg", "coriander")) {
            for (form in listOf("whole", "ground")) {
                val header = "Spices ke liye,"
                val instruction = "$header 1 $form $spice, 2 turmeric powder"
                val items = listOf(
                    Item(0, "1 $form $spice", "1", "count", "1", name = "$spice $form"),
                    Item(1, "2 turmeric powder", "2", "count", "2", name = "turmeric powder"),
                )
                val proof = group(instruction, instruction, header, listOf(0, 1),
                    listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")),
                    "local_amounts_verified")
                fun prepare(rows: List<Item>) = GroceryIntentReviewV2Response.parse(
                    response(instruction, rows.map { row(it, instruction) }, listOf(proof)), instruction,
                ).draft.prepareForReview()
                val prepared = prepare(items)
                assertEquals("$spice $form", listOf("$spice $form", "turmeric powder"), prepared.items.map { it.query })
                assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
                assertEquals(listOf(header), prepared.sharedGroupNotes)
                assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
                assertTrue("Form must not disappear", prepare(listOf(items[0].copy(name = spice), items[1])).items.isEmpty())
            }
        }
    }

    @Test fun organizationalShapesCannotShrinkTheirScopeOrHideLeftContextOrSharedAmounts() {
        val source = "Vegetables: 1 tomato, 2 onion"
        val items = listOf(Item(0, "1 tomato", "1", "count", "1"), Item(1, "2 onion", "2", "count", "2"))
        val members = listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local"))
        for (instruction in listOf("buy $source", "$source, 3 carrot", "$source, kul 3 kilo", "$source, each")) {
            val proof = group(instruction, source, "Vegetables:", listOf(0, 1), members, "local_amounts_verified")
            assertThrows(IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction)
            }
        }

        val sideSource = "Sabzi side: 1 tomato, 2 onion"
        val sideItems = listOf(Item(0, "1 tomato", "1", "count", "1"), Item(1, "2 onion", "2", "count", "2"))
        val sideMembers = listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local"))
        val malformed = listOf(
            Triple("buy $sideSource", sideSource, "hidden left context"),
            Triple(sideSource, "Sabzi side: 1 tomato", "shortened scope"),
            Triple("$sideSource, 3 kg total", sideSource, "unowned shared amount"),
        )
        malformed.forEach { (instruction, quotedSource, reason) ->
            val proof = group(instruction, quotedSource, "Sabzi side:", listOf(0, 1), sideMembers, "local_amounts_verified")
            assertThrows(reason, IllegalArgumentException::class.java) {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, sideItems.map { row(it, instruction) }, listOf(proof)), instruction,
                )
            }
        }
    }

    @Test fun organizationalMembershipCannotBeBorrowedFromHeaderOrRemoveLocalModifiers() {
        for ((header, source) in listOf(
            "Vegetables:" to "2 tomato puree",
            "Vegetables:" to "2 milk",
            "Vegetables:" to "2 organic tomato",
            "Sabzi side:" to "2 tomato puree",
            "Sabzi side:" to "2 milk",
            "Sabzi side:" to "2 organic tomato",
        )) {
            val instruction = "$header 1 onion, $source"
            val items = listOf(Item(0, "1 onion", "1", "count", "1"),
                Item(1, source, "2", "count", "2", name = source.removePrefix("2 ").removePrefix("organic ")))
            val proof = group(instruction, instruction, header, listOf(0, 1),
                listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")), "local_amounts_verified")
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            assertTrue(source, prepared.items.isEmpty())
        }
    }

    @Test fun organizationalVegetableGroupCannotUseIngredientOrDryPulseCandidates() {
        val instruction = "Vegetables: 1 beans, 2 onion"
        val items = listOf(Item(0, "1 beans", "1", "count", "1"), Item(1, "2 onion", "2", "count", "2"))
        val proof = group(instruction, instruction, "Vegetables:", listOf(0, 1),
            listOf(Member(0, "1", "count", "local"), Member(1, "2", "count", "local")), "local_amounts_verified")
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(2, prepared.items.size)
        val beans = prepared.items.first()
        val fresh = SwiggyMcpClient.RecommendationCandidate(spinId = "fresh", label = "Fresh Green Beans", subtitle = "1 pc")
        val unsafe = listOf("Coffee Beans", "Black Beans", "Red Beans", "White Beans").mapIndexed { index, label ->
            SwiggyMcpClient.RecommendationCandidate(spinId = "unsafe$index", label = label, subtitle = "1 pc")
        }
        val assessed = assessSwiggyIntentQuantity(beans, SwiggyMcpClient.Recommendations(candidates = listOf(fresh) + unsafe))
        assertTrue(isSwiggyCandidateAllowed(assessed, fresh))
        assertUnqualifiedCountNeedsReview(assessed, fresh, 1)
        unsafe.forEach { candidate ->
            assertTrue(candidate.label, !isSwiggyCandidateAllowed(assessed, candidate))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(assessed, candidate) }
        }
    }

    @Test fun forwardEachAfterOwnedSiblingPreservesDifferentQuantitiesThroughCartBoundary() {
        for (connector in listOf(", and ", " and ", ", aur ")) {
            val scope = "one each of rice, lentils."
            val instruction = "two almonds$connector$scope"
            val items = listOf(Item(0, "two almonds", "2", "count", "two"),
                Item(1, "rice"), Item(2, "lentils"))
            val proof = group(instruction, scope, null, listOf(1, 2),
                listOf(Member(1, "1", "count", "each"), Member(2, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"))
            val draft = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft
            assertEquals("2", draft.readyRows.first().item.quantity.toString())
            assertEquals(listOf(1, 2), draft.groups.single().memberIndices)
            val prepared = draft.prepareForReview()
            assertEquals(3, prepared.items.size)
            assertTrue(prepared.excludedText.isEmpty())
            assertTrue(prepared.unresolvedText.isEmpty())
            assertEquals(listOf(2, 1, 1), prepared.items.map {
                (it.quantity as com.example.beta.automation.Quantity.Count).n
            })
            prepared.items.forEachIndexed { index, item ->
                val candidate = SwiggyMcpClient.RecommendationCandidate(
                    spinId = "synthetic-$index", label = item.query, subtitle = "1 pc")
                val assessed = assessSwiggyIntentQuantity(item,
                    SwiggyMcpClient.Recommendations(candidates = listOf(candidate)))
                assertTrue(isSwiggyCandidateAllowed(assessed, candidate))
                assertUnqualifiedCountNeedsReview(assessed, candidate, if (index == 0) 2 else 1)
            }
        }
    }

    @Test fun forwardEachCannotBorrowARejectedNumericPropertyPredecessor() {
        val scope = "one each of rice, lentils."
        val instruction = "two milk, one no added sugar, and $scope"
        val items = listOf(
            Item(0, "two milk", "2", "count", "two", name = "milk"),
            Item(1, "one no added sugar", "1", "count", "one", name = "no added sugar", required = listOf("no_added_sugar")),
            Item(2, "rice", name = "rice"),
            Item(3, "lentils", name = "lentils"),
        )
        val proof = group(
            instruction, scope, null, listOf(2, 3),
            listOf(Member(2, "1", "count", "each"), Member(3, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft
        assertEquals(listOf(2, 3), draft.groups.single().memberIndices)
        val prepared = draft.prepareForReview()
        assertTrue("rejected predecessor rows cannot lend scope to the each group", prepared.items.isEmpty())

        val independentInstruction = "two milk, one no added sugar; $scope"
        val independentItems = items.map { item ->
            if (item.index >= 2) item.copy(source = item.source) else item
        }
        val independentProof = group(
            independentInstruction, scope, null, listOf(2, 3),
            listOf(Member(2, "1", "count", "each"), Member(3, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one each"),
        )
        val independentPrepared = GroceryIntentReviewV2Response.parse(
            response(independentInstruction, independentItems.map { row(it, independentInstruction) }, listOf(independentProof)),
            independentInstruction,
        ).draft.prepareForReview()
        assertTrue("semicolon-separated group retains rice", independentPrepared.items.any { it.query == "rice" })
        assertTrue("semicolon-separated group retains lentils", independentPrepared.items.any { it.query == "lentils" })
    }

    @Test fun forwardEachCannotHideContextOrUseAnUnprovedPredecessor() {
        val scope = "one each of rice, lentils."
        val previous = Item(0, "two almonds", "2", "count", "two")
        val variants = listOf(
            Triple("two almonds, $scope", previous, true),
            Triple("two almonds, and $scope", previous.copy(quantity = "3"), true),
            Triple("two almonds, and $scope", previous.copy(quantity = "null", quantityUnit = "unspecified", quantityText = null), true),
            Triple("BrandA: two almonds, and $scope", previous, true),
            Triple("From BrandA two almonds, and $scope", previous.copy(source = "From BrandA two almonds", name = "BrandA almonds"), true),
            Triple("BrandA two almonds, and $scope", previous.copy(source = "BrandA two almonds", name = "BrandA almonds"), true),
            Triple("Then BrandA two almonds, and $scope", previous.copy(source = "Then BrandA two almonds", name = "BrandA almonds"), true),
            Triple("two almonds, unclaimed oats, and $scope", previous, true),
            Triple("two almonds, and $scope", previous, false),
        )
        variants.forEachIndexed { index, (instruction, prior, includePrior) ->
            val items = (if (includePrior) listOf(prior) else emptyList()) +
                listOf(Item(1, "rice"), Item(2, "lentils"))
            val proof = group(instruction, scope, null, listOf(1, 2),
                listOf(Member(1, "1", "count", "each"), Member(2, "1", "count", "each")),
                "each_verified", each = Amount("one", "1", "count", "one each"))
            assertTrue("unsafe forward-scope case $index must reject", runCatching {
                GroceryIntentReviewV2Response.parse(
                    response(instruction, items.map { row(it, instruction) }, listOf(proof), sourceCount = 3), instruction,
                )
            }.isFailure)
        }
    }

    @Test fun forwardEachRejectsNonAsciiWhitespaceLikeBackend() {
        val scope = "one\u00a0each of rice, lentils."
        val instruction = "two almonds, and $scope"
        val items = listOf(Item(0, "two almonds", "2", "count", "two"), Item(1, "rice"), Item(2, "lentils"))
        val proof = group(instruction, scope, null, listOf(1, 2),
            listOf(Member(1, "1", "count", "each"), Member(2, "1", "count", "each")),
            "each_verified", each = Amount("one", "1", "count", "one\u00a0each"))
        assertTrue(runCatching {
            GroceryIntentReviewV2Response.parse(response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction)
        }.isFailure)
    }

    @Test fun strictWireGroupAndNumericCorrectionComposeWithoutMutatingOriginalProof(): Unit {
        val groupSource = "BrandA: 2 milk, 1 curd"
        val correctionText = "make milk 3"
        val instruction = "$groupSource; $correctionText"
        val items = listOf(
            Item(0, "2 milk", "2", "count", "2", name = "BrandA milk",
                corrections = listOf(Correction("quantity", correctionText, "3", "2", "count", "3", "count"))),
            Item(1, "1 curd", "1", "count", "1", name = "BrandA curd"),
        )
        val proof = group(instruction, groupSource, "BrandA:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        val draft = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft

        assertEquals("2", draft.readyRows[0].item.quantity.toString())
        assertEquals(listOf("2", "1"), draft.groups.single().members.map { it.value.toString() })
        val prepared = draft.prepareForReview()
        assertEquals(listOf(Quantity.Count(3), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())
        prepared.items.forEachIndexed { index, item ->
            val candidate = SwiggyMcpClient.RecommendationCandidate(
                spinId = "synthetic-corrected-$index", label = item.query, subtitle = "1 pc")
            val assessed = assessSwiggyIntentQuantity(item,
                SwiggyMcpClient.Recommendations(candidates = listOf(candidate)))
            assertTrue(isSwiggyCandidateAllowed(assessed, candidate))
            assertUnqualifiedCountNeedsReview(assessed, candidate, if (index == 0) 3 else 1)
        }
    }

    @Test fun strictWireBalancedGroupCorrectionsPreserveTotalButConflictingOrCrossFamilyEditsHold(): Unit {
        val groupSource = "3 cups: 2 milk, 1 curd"
        val header = "3 cups"
        val balancedInstruction = "$groupSource; make milk 1 and make curd 2"
        val balancedItems = listOf(
            Item(0, "2 milk", "2", "count", "2", name = "milk",
                corrections = listOf(Correction("quantity", "make milk 1", "1", "2", "count", "1", "count"))),
            Item(1, "1 curd", "1", "count", "1", name = "curd",
                corrections = listOf(Correction("quantity", "make curd 2", "2", "1", "count", "2", "count"))),
        )
        val totalProof = group(balancedInstruction, groupSource, header, listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "total_verified", packaging = "cup", total = Amount("3", "3", "count", quoteSource = header))
        val balancedDraft = GroceryIntentReviewV2Response.parse(
            response(balancedInstruction, balancedItems.map { row(it, balancedInstruction) }, listOf(totalProof)), balancedInstruction,
        ).draft
        assertEquals(listOf(null, null), balancedDraft.readyRows.map {
            it.groundingIssue(group = balancedDraft.groups.single(), instruction = balancedInstruction)
        })
        val balanced = balancedDraft.prepareForReview()
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), balanced.items.map { it.quantity })
        assertTrue(balanced.unresolvedText.isEmpty())

        val conflictingInstruction = "$groupSource; make milk 3"
        val conflictingItems = listOf(
            Item(0, "2 milk", "2", "count", "2", name = "milk",
                corrections = listOf(Correction("quantity", "make milk 3", "3", "2", "count", "3", "count"))),
            Item(1, "1 curd", "1", "count", "1", name = "curd"),
        )
        val conflictProof = group(conflictingInstruction, groupSource, header, listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "total_verified", packaging = "cup", total = Amount("3", "3", "count", quoteSource = header))
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(conflictingInstruction, conflictingItems.map { row(it, conflictingInstruction) }, listOf(conflictProof)),
                conflictingInstruction,
            )
        }

        val crossSource = "2 kg total: 1 kg rice and 1 kg dal"
        val crossCorrection = "rice 1 kilo nahi 3 pieces rakhna"
        val crossInstruction = "$crossSource; $crossCorrection"
        val crossItems = listOf(
            Item(0, "1 kg rice", "1", "kg", "1", name = "rice",
                corrections = listOf(Correction("quantity", crossCorrection, "3", "1", "kg", "3", "piece"))),
            Item(1, "1 kg dal", "1", "kg", "1", name = "dal"),
        )
        val crossProof = group(crossInstruction, crossSource, "2 kg total", listOf(0, 1),
            listOf(Member(0, "1", "kg", "local"), Member(1, "1", "kg", "local")),
            "total_verified", total = Amount("2", "2", "kg", quoteSource = "2 kg total"))
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(crossInstruction, crossItems.map { row(it, crossInstruction) }, listOf(crossProof)), crossInstruction,
            )
        }
    }

    @Test fun strictWireEachGroupRejectsCorrectionEvenWithValidOriginalLocalCounts(): Unit {
        val source = "BrandA: 2 milk, 2 curd, 2 each"
        val baseItems = listOf(
            Item(0, "2 milk", "2", "count", "2", name = "BrandA milk"),
            Item(1, "2 curd", "2", "count", "2", name = "BrandA curd"),
        )
        fun proof(instruction: String) = group(instruction, source, "BrandA:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "2", "count", "local")),
            "each_verified", each = Amount("2", "2", "count", "2 each"))
        val base = GroceryIntentReviewV2Response.parse(
            response(source, baseItems.map { row(it, source) }, listOf(proof(source))), source,
        ).draft.prepareForReview()
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2)), base.items.map { it.quantity })
        val instruction = "$source; make milk 3"
        val corrected = baseItems.map { item -> if (item.index == 0) item.copy(corrections = listOf(
            Correction("quantity", "make milk 3", "3", "2", "count", "3", "count"))) else item }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, corrected.map { row(it, instruction) }, listOf(proof(instruction))), instruction,
            )
        }
    }

    @Test fun strictWirePackSizeCorrectionCoexistsWithGroupCountsAndLateFailureRevokesUnrelatedRows(): Unit {
        val groupSource = "BrandA: 2 milk 1 kg packet, 1 curd"
        val correctionText = "milk 1 kg nahi 500 g ke 2 packet karna"
        val instruction = "$groupSource; $correctionText"
        val items = listOf(
            Item(0, "2 milk 1 kg packet", "2", "count", "2", pack = "1", packUnit = "kg", packText = "1",
                name = "BrandA milk", packaging = "packet", corrections = listOf(Correction(
                    "pack_size", correctionText, "500", "1", "kg", "500", "g"))),
            Item(1, "1 curd", "1", "count", "1", name = "BrandA curd"),
        )
        val proof = group(instruction, groupSource, "BrandA:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.first().query.contains("500 g"))

        val badGroupSource = "BrandA: 2 milk, 1 curd"
        val correction = "make milk 3"
        val badInstruction = "$badGroupSource; 2 bread; $correction"
        val badItems = listOf(
            Item(0, "2 milk", "2", "count", "2", name = "BrandA milk", required = listOf("organic"),
                corrections = listOf(Correction("quantity", correction, "3", "2", "count", "3", "count"))),
            Item(1, "1 curd", "1", "count", "1", name = "BrandA curd"),
            Item(2, "2 bread", "2", "count", "2", name = "bread"),
        )
        val badProof = group(badInstruction, badGroupSource, "BrandA:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        val revoked = GroceryIntentReviewV2Response.parse(
            response(badInstruction, badItems.map { row(it, badInstruction) }, listOf(badProof)), badInstruction,
        ).draft.prepareForReview()
        assertTrue(revoked.items.isEmpty())
        assertEquals(listOf(badInstruction), revoked.unresolvedText)
    }

    @Test fun strictWireRejectsCorrectionQuoteOverlappingTheGroupSource(): Unit {
        val instruction = "BrandA: 2 milk, 1 curd; make milk 3"
        val items = listOf(
            Item(0, "2 milk", "2", "count", "2", name = "BrandA milk",
                corrections = listOf(Correction("quantity", "make milk 3", "3", "2", "count", "3", "count"))),
            Item(1, "1 curd", "1", "count", "1", name = "BrandA curd"),
        )
        val overlapping = group(instruction, instruction, "BrandA:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(overlapping)), instruction,
            )
        }
    }

    @Test fun strictWireRejectsNonMemberCorrectionQuoteOverlappingTheGroupSource(): Unit {
        val instruction = "2 bread; BrandA: 2 milk, 1 curd; make bread 3"
        val groupSource = "BrandA: 2 milk, 1 curd; make bread 3"
        val items = listOf(
            Item(0, "2 bread", "2", "count", "2", name = "bread",
                corrections = listOf(Correction("quantity", "make bread 3", "3", "2", "count", "3", "count"))),
            Item(1, "2 milk", "2", "count", "2", name = "BrandA milk"),
            Item(2, "1 curd", "1", "count", "1", name = "BrandA curd"),
        )
        val proof = group(instruction, groupSource, "BrandA:", listOf(1, 2),
            listOf(Member(1, "2", "count", "local"), Member(2, "1", "count", "local")),
            "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            )
        }
    }

    @Test fun purposeGroupProofKeepsLiteralSectionNoteAndDoesNotLendHeaderIdentity(): Unit {
        val header = "For breakfast:"
        val instruction = "$header 2 apples, 1 banana"
        val items = listOf(
            Item(0, "2 apples", "2", "count", "2", name = "apples"),
            Item(1, "1 banana", "1", "count", "1", name = "banana"),
        )
        val proof = group(instruction, instruction, header, listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        val prepared = GroceryIntentReviewV2Response.parse(
            response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
        ).draft.prepareForReview()
        assertEquals(listOf("apples", "banana"), prepared.items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertEquals(listOf(header), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())

        val genericInstruction = "Kitchen: 2 powder, 1 brush"
        val genericItems = listOf(
            Item(0, "2 powder", "2", "count", "2", name = "kitchen powder"),
            Item(1, "1 brush", "1", "count", "1", name = "kitchen brush"),
        )
        val genericProof = group(genericInstruction, genericInstruction, "Kitchen:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        val genericPrepared = GroceryIntentReviewV2Response.parse(
            response(genericInstruction, genericItems.map { row(it, genericInstruction) }, listOf(genericProof)),
            genericInstruction,
        ).draft.prepareForReview()
        assertTrue("purpose label cannot invent generic product identity", genericPrepared.items.isEmpty())
        assertTrue(genericPrepared.excludedText.isNotEmpty())
    }

    @Test fun purposeGroupCannotExpandLocalPieceSourceAcrossAnUnseparatedSibling(): Unit {
        val cases = listOf(
            Triple("For breakfast: 2 pieces milk bread", "2 pieces milk", false),
            Triple("For breakfast: 2 pieces milk, bread", "2 pieces milk", true),
            Triple("For breakfast: milk, 2 pieces bread", "milk, 2 pieces", false),
            Triple("For breakfast: milk, 2 pieces, bread", "milk, 2 pieces", true),
        )
        for ((instruction, milkSource, safe) in cases) {
            val items = listOf(
                Item(0, milkSource, "2", "piece", "2", name = "milk"),
                Item(1, "bread", name = "bread"),
            )
            val proof = group(
                instruction, instruction, "For breakfast:", listOf(0, 1),
                listOf(Member(0, "2", "piece", "local"), Member(1, null, "unspecified", "not_stated")),
                "amounts_unspecified",
            )
            val prepared = GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(proof)), instruction,
            ).draft.prepareForReview()
            val diagnostic = "length=${instruction.length};safe=$safe;items=${prepared.items.size};excluded=${prepared.excludedText.size};unresolved=${prepared.unresolvedText.size}"
            if (safe) {
                assertEquals(diagnostic, 2, prepared.items.size)
                assertTrue(diagnostic, prepared.excludedText.isEmpty())
                assertTrue(diagnostic, prepared.unresolvedText.isEmpty())
            } else {
                assertTrue(diagnostic, prepared.items.isEmpty())
                assertTrue(diagnostic, prepared.excludedText.contains(instruction))
                assertTrue(diagnostic, prepared.recoverySnapshot != null)
            }
        }
    }

    @Test fun purposeGroupCannotTruncateScopeOrAssertACommonAmount(): Unit {
        val instruction = "For breakfast: 2 apples, 1 banana, 1 bread"
        val items = listOf(
            Item(0, "2 apples", "2", "count", "2", name = "apples"),
            Item(1, "1 banana", "1", "count", "1", name = "banana"),
            Item(2, "1 bread", "1", "count", "1", name = "bread"),
        )
        val truncatedSource = "For breakfast: 2 apples, 1 banana"
        val truncated = group(instruction, truncatedSource, "For breakfast:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "local_amounts_verified")
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(instruction, items.map { row(it, instruction) }, listOf(truncated)), instruction,
            )
        }

        val totalInstruction = "For breakfast: 2 apples, 1 banana, 2 each"
        val amount = Amount("2 each", "2", "count", quoteSource = "2 each")
        val totalProof = group(totalInstruction, totalInstruction, "For breakfast:", listOf(0, 1),
            listOf(Member(0, "2", "count", "local"), Member(1, "1", "count", "local")),
            "each_verified", each = amount)
        val totalItems = items.take(2)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(
                response(totalInstruction, totalItems.map { row(it, totalInstruction) }, listOf(totalProof)),
                totalInstruction,
            )
        }
    }

    private data class Item(
        val index: Int,
        val source: String,
        val quantity: String = "null",
        val quantityUnit: String = "unspecified",
        val quantityText: String? = null,
        val pack: String = "null",
        val packUnit: String = "unspecified",
        val packText: String? = null,
        val name: String = source.substringAfterLast(' '),
        val required: List<String> = emptyList(),
        val excluded: List<String> = emptyList(),
        val packaging: String? = null,
        val corrections: List<Correction> = emptyList(),
    )
    private data class Member(val index: Int, val value: String?, val unit: String, val origin: String)
    private data class Amount(val text: String, val value: String, val unit: String, val quoteSource: String = text)
    private data class Correction(
        val kind: String,
        val text: String,
        val newText: String,
        val oldValue: String,
        val oldUnit: String,
        val newValue: String,
        val newUnit: String,
    )

    private fun membersUnstated(vararg indices: Int) = indices.map { Member(it, null, "unspecified", "not_stated") }

    private fun response(instruction: String, rows: List<String>, groups: List<String> = emptyList(), sourceCount: Int = rows.size): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":$sourceCount,"ready_rows":[${rows.joinToString(",")}],"blocked_rows":[],"groups":[${groups.joinToString(",")}]},"model":"test","promptVersion":"v2","latencyMs":1,"usage":{}}"""

    private fun row(item: Item, instruction: String): String {
        val start = instruction.indexOf(item.source)
        val end = start + item.source.length
        val quantityText = item.quantityText?.let(::json) ?: "null"
        val packText = item.packText?.let(::json) ?: "null"
        val correctionsJson = item.corrections.joinToString(",") { correction ->
            val correctionStart = instruction.indexOf(correction.text)
            require(correctionStart >= 0)
            val newStart = correctionStart + correction.text.indexOf(correction.newText)
            require(newStart >= correctionStart)
            """{"kind":"${correction.kind}","source":{"text":${json(correction.text)},"start_utf16":$correctionStart,"end_utf16":${correctionStart + correction.text.length}},"new_amount":{"text":${json(correction.newText)},"start_utf16":$newStart,"end_utf16":${newStart + correction.newText.length}},"old_value":${correction.oldValue},"old_unit":"${correction.oldUnit}","new_value":${correction.newValue},"new_unit":"${correction.newUnit}"}"""
        }
        val correctionField = if (item.corrections.isEmpty()) "" else ",\"corrections\":[$correctionsJson]"
        val packagingJson = item.packaging?.let(::json) ?: "null"
        return """{"row_index":${item.index},"item":{"name":${json(item.name)},"quantity":${item.quantity},"quantity_unit":"${item.quantityUnit}","pack_value":${item.pack},"pack_unit":"${item.packUnit}","packaging":$packagingJson,"required":[${item.required.joinToString(",") { json(it) }}],"excluded":[${item.excluded.joinToString(",") { json(it) }}],"source_text":${json(item.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":$packText},"evidence":{"primary":{"text":${json(item.source)},"start_utf16":$start,"end_utf16":$end},"supporting":[]},"quantity_origin":"${if (item.quantity == "null") "not_stated" else "stated"}","amount_role_status":"${if (item.quantity == "null" && item.pack == "null") "not_stated" else "matched"}"$correctionField}"""
    }

    private fun group(
        instruction: String,
        source: String,
        header: String?,
        indices: List<Int>,
        members: List<Member>,
        amountStatus: String,
        packaging: String? = null,
        total: Amount? = null,
        each: Amount? = null,
    ): String {
        fun quote(text: String): String {
            val sourceStart = instruction.indexOf(source)
            val start = instruction.indexOf(text, sourceStart)
            require(start >= 0)
            return """{"text":${json(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""
        }
        fun amount(value: Amount?): String = value?.let {
            """{"source":${quote(if (it === total) requireNotNull(header) else it.quoteSource)},"amount_text":${json(it.text)},"value":${it.value},"unit":"${it.unit}"}"""
        } ?: "null"
        val headerJson = header?.let(::quote) ?: "null"
        return """{"status":"structurally_verified","amount_status":"$amountStatus","source":${quote(source)},"header":$headerJson,"member_indices":[${indices.joinToString(",")}],"members":[${members.joinToString(",") { """{"row_index":${it.index},"value":${it.value ?: "null"},"unit":"${it.unit}","origin":"${it.origin}"}""" }}],"packaging":${packaging?.let(::json) ?: "null"},"total":${amount(total)},"each":${amount(each)}}"""
    }

    private fun json(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\b", "\\b").replace("\u000C", "\\f").replace("\n", "\\n")
        .replace("\r", "\\r").replace("\t", "\\t")}\""
}
