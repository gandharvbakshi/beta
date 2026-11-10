package com.example.beta

import com.example.beta.automation.Quantity
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewV2PropertiesTest {
    @Test fun aliasFreshnessCannotIgnoreAnotherOriginalCompoundReference() {
        for ((first, second, selector) in listOf(
            Triple("spinach", "palak paneer", "palak"),
            Triple("cardamom", "elaichi banana", "elaichi"),
        )) {
            val clause = "$selector should be fresh"
            val instruction = "1 $first, 2 $second; $clause"
            val rows = listOf(
                propertyReadyRow(instruction, 0,
                    propertyItem(first, "1 $first", 1, required = listOf("fresh")), clause),
                propertyReadyRow(instruction, 1, propertyItem(second, "2 $second", 2)),
            )
            val prepared = propertyReview(instruction, rows).prepareForReview(allowPropertyObligations = true)
            assertTrue(instruction, prepared.items.isEmpty())
            assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
            assertEquals(listOf("1 $first", "2 $second"), rows.map { it.evidence.primary.text })
        }
    }

    @Test fun namedAliasFreshnessContrastKeepsOriginalOwnershipInBothDirections() {
        for ((identity, selector) in listOf("spinach" to "Palak", "palak" to "Spinach")) {
            val clause = "$selector fresh chahiye, frozen nahi"
            val instruction = "2 $identity; $clause."
            val draft = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
                propertyItem(identity, "2 $identity", 2, required = listOf("fresh"), excluded = listOf("frozen")), "$clause.")))
            val prepared = draft.prepareForReview(allowPropertyObligations = true)
            assertEquals(instruction, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(Quantity.Count(2), item.quantity)
            assertEquals("2 $identity", item.intentQuantityProvenance?.primaryText)
            assertEquals(listOf("frozen"), item.avoidPhrases)
            assertTrue(item.query.contains("fresh"))
            assertEquals(listOf(clause), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            assertTrue(isSwiggyCandidateAllowed(item,
                SwiggyMcpClient.RecommendationCandidate(spinId = "alias-plain", label = "Spinach")))
            assertFalse(isSwiggyCandidateAllowed(item,
                SwiggyMcpClient.RecommendationCandidate(spinId = "alias-frozen", label = "Frozen Spinach")))
        }
    }

    @Test fun ageGreetingAndFifteenItemsPreserveNamedAliasContrastAndEveryCount() {
        val names = listOf("rice", "bread", "tea", "coffee", "flour", "oil", "potato", "tomato",
            "onion", "apple", "banana", "egg", "biscuit", "pasta", "spinach")
        val primaries = names.mapIndexed { index, name -> "${index + 1} $name" }
        val clause = "Palak fresh chahiye, frozen nahi"
        val instruction = "Hello, I'm 72. Please get " + primaries.joinToString(", ") + "; $clause."
        val rows = names.mapIndexed { index, name -> propertyReadyRow(instruction, index,
            propertyItem(name, primaries[index], index + 1,
                required = if (index == names.lastIndex) listOf("fresh") else emptyList(),
                excluded = if (index == names.lastIndex) listOf("frozen") else emptyList()),
            if (index == names.lastIndex) "$clause." else null) }
        val draft = propertyReview(instruction, rows)
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertEquals(15, prepared.items.size)
        assertEquals((1..15).map { Quantity.Count(it) }, prepared.items.map { it.quantity })
        assertEquals(primaries, prepared.items.map { it.intentQuantityProvenance?.primaryText })
        assertEquals(List(14) { emptyList<String>() } + listOf(listOf("frozen")), prepared.items.map { it.avoidPhrases })
        assertEquals(List(14) { emptyList<String>() } + listOf(listOf("fresh")), draft.readyRows.map { it.item.required })
        assertEquals(listOf(clause), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        rows.forEach { row ->
            val primary = row.evidence.primary
            assertEquals(primary.text, instruction.substring(primary.startUtf16, primary.endUtf16))
        }
    }

    @Test fun fifteenOriginalItemsKeepFinalCommaAndQuantityAndAliasProperty() {
        val names = listOf("rice", "bread", "tea", "coffee", "flour", "oil", "potato", "tomato",
            "onion", "apple", "banana", "egg", "biscuit", "pasta", "spinach")
        val counts = List(14) { 1 } + 2
        val primaries = names.mapIndexed { index, name -> "${counts[index]} $name" }
        val clause = "Palak fresh chahiye, frozen nahi"
        val instruction = "Hello, I'm 72. Please get " + primaries.dropLast(1).joinToString(", ") +
            ", and " + primaries.last() + "; $clause."
        val rows = names.mapIndexed { index, name -> propertyReadyRow(instruction, index,
            propertyItem(name, primaries[index], counts[index],
                required = if (index == names.lastIndex) listOf("fresh") else emptyList(),
                excluded = if (index == names.lastIndex) listOf("frozen") else emptyList()),
            if (index == names.lastIndex) "$clause." else null) }
        val prepared = propertyReview(instruction, rows).prepareForReview(allowPropertyObligations = true)
        assertEquals(15, prepared.items.size)
        assertEquals(counts.map { Quantity.Count(it) }, prepared.items.map { it.quantity })
        assertEquals(primaries, prepared.items.map { it.intentQuantityProvenance?.primaryText })
        assertEquals(List(14) { emptyList<String>() } + listOf(listOf("frozen")), prepared.items.map { it.avoidPhrases })
        assertTrue(prepared.items.last().query.contains("fresh"))
        assertEquals(listOf(clause), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        rows.forEach { row ->
            val source = row.evidence.primary
            assertEquals(source.text, instruction.substring(source.startUtf16, source.endUtf16))
        }
    }

    @Test fun aliasPropertyCannotSurviveCroppedOrMissingOriginalOwnership() {
        val clause = "Palak fresh chahiye, frozen nahi"
        val scenarios = listOf(
            Triple("1 spinach organic; $clause.", "1 spinach", "$clause."),
            Triple("1 spinach; $clause.", "1 spinach", "fresh chahiye, frozen nahi."),
            Triple("1 spinach; $clause.", "1 spinach", "$clause."),
        )
        scenarios.forEachIndexed { index, (instruction, primary, support) ->
            val candidate = propertyItem("spinach", primary, 1,
                required = listOf("fresh"), excluded = if (index == 2) emptyList() else listOf("frozen"))
            val prepared = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0, candidate, support)))
                .prepareForReview(allowPropertyObligations = true)
            assertTrue(instruction, prepared.items.isEmpty())
            assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
            assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
        }
    }

    @Test fun reverseHindiFreshnessContrastKeepsFrozenRequestAndRejectsFreshCandidate() {
        val clause = "spinach frozen ho, fresh nahi"
        val instruction = "1 spinach; $clause."
        val draft = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
            propertyItem("spinach", "1 spinach", 1, required = listOf("frozen"), excluded = listOf("fresh")), "$clause.")))
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals(Quantity.Count(1), item.quantity)
        assertEquals("1 spinach", item.intentQuantityProvenance?.primaryText)
        assertEquals(listOf("fresh"), item.avoidPhrases)
        assertTrue(item.query.contains("frozen"))
        assertEquals("$clause.", draft.readyRows.single().evidence.supporting.single().quote.text)
        assertEquals(listOf(clause), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        assertTrue(isSwiggyCandidateAllowed(item,
            SwiggyMcpClient.RecommendationCandidate(spinId = "frozen-reverse", label = "Frozen Spinach")))
        assertFalse(isSwiggyCandidateAllowed(item,
            SwiggyMcpClient.RecommendationCandidate(spinId = "fresh-reverse", label = "Fresh Spinach")))
    }

    @Test fun propertySupportCannotConsumeTwoOriginalTrailingDelimiters() {
        val support = "spinach fresh chahiye, frozen nahi.;"
        val instruction = "1 spinach; $support"
        val draft = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
            propertyItem("spinach", "1 spinach", 1, required = listOf("fresh"), excluded = listOf("frozen")), support)))
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
        assertEquals(support, draft.readyRows.single().evidence.supporting.single().quote.text)
    }

    @Test fun freshObligationCannotAuthorizeUnspokenDriedRequirementOrExclusion() {
        val clause = "spinach should be fresh, not frozen"
        val instruction = "1 spinach; $clause"
        listOf(
            propertyItem("spinach", "1 spinach", 1, required = listOf("fresh"), excluded = listOf("frozen", "dried")),
            propertyItem("spinach", "1 spinach", 1, required = listOf("fresh", "dried"), excluded = listOf("frozen")),
        ).forEach { candidate ->
            val prepared = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0, candidate, clause)))
                .prepareForReview(allowPropertyObligations = true)
            assertTrue(prepared.items.isEmpty())
            assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
            assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
        }
    }

    @Test fun freshObligationOnOneRowDoesNotBanAnotherRowsLiteralFrozenForm() {
        val clause = "spinach should be fresh, not frozen"
        val instruction = "1 frozen peas, 1 spinach; $clause"
        val draft = propertyReview(instruction, listOf(
            propertyReadyRow(instruction, 0, propertyItem("frozen peas", "1 frozen peas", 1, required = listOf("frozen"))),
            propertyReadyRow(instruction, 1, propertyItem("spinach", "1 spinach", 1,
                required = listOf("fresh"), excluded = listOf("frozen")), clause),
        ))
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertEquals(2, prepared.items.size)
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.first().query.contains("frozen"))
        assertEquals(emptyList<String>(), prepared.items.first().avoidPhrases)
        assertTrue(prepared.items.last().query.contains("fresh"))
        assertEquals(listOf("frozen"), prepared.items.last().avoidPhrases)
        assertEquals(listOf("1 frozen peas", "1 spinach"), prepared.items.map { it.intentQuantityProvenance?.primaryText })
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun namedHindiFreshContrastAcceptsOnlyExactSupportWithOptionalOriginalPunctuation() {
        val clause = "spinach fresh chahiye, frozen nahi"
        for (punctuation in listOf("", ".", "!", "?", ";")) {
            val instruction = "1 spinach; $clause$punctuation"
            for (support in listOf(clause, clause + punctuation).distinct()) {
                val draft = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
                    propertyItem("spinach", "1 spinach", 1, required = listOf("fresh"), excluded = listOf("frozen")), support)))
                val prepared = draft.prepareForReview(allowPropertyObligations = true)
                assertEquals("support=$support", 1, prepared.items.size)
                val item = prepared.items.single()
                assertEquals(Quantity.Count(1), item.quantity)
                assertEquals("1 spinach", item.intentQuantityProvenance?.primaryText)
                assertEquals(listOf("frozen"), item.avoidPhrases)
                assertTrue(item.query.contains("fresh"))
                assertEquals(listOf(clause), prepared.sharedGroupNotes)
                assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
                assertEquals(support, draft.readyRows.single().evidence.supporting.single().quote.text)
                assertTrue(isSwiggyCandidateAllowed(item,
                    SwiggyMcpClient.RecommendationCandidate(spinId = "plain", label = "Spinach")))
                assertFalse(isSwiggyCandidateAllowed(item,
                    SwiggyMcpClient.RecommendationCandidate(spinId = "frozen", label = "Frozen Spinach")))
            }
        }
    }

    @Test fun hindiContrastMissingFlippedOrDuplicatedPolarityRemainsHeld() {
        val clause = "spinach fresh chahiye, frozen nahi"
        val instruction = "1 spinach; $clause."
        val candidates = listOf(
            propertyItem("spinach", "1 spinach", 1, required = listOf("fresh")),
            propertyItem("spinach", "1 spinach", 1, excluded = listOf("frozen")),
            propertyItem("spinach", "1 spinach", 1, required = listOf("frozen"), excluded = listOf("fresh")),
            propertyItem("spinach", "1 spinach", 1, required = listOf("fresh", "frozen"), excluded = listOf("frozen")),
        )
        candidates.forEach { candidate ->
            val prepared = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0, candidate, "$clause.")))
                .prepareForReview(allowPropertyObligations = true)
            assertTrue(prepared.items.isEmpty())
            assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
        }
    }

    @Test fun hindiContrastCannotUseWrongTargetsPartialSupportsOrExtraWords() {
        val clause = "spinach fresh chahiye, frozen nahi"
        val scenarios = listOf(
            ("1 spinach; $clause.") to "fresh chahiye, frozen nahi.",
            ("1 spinach; $clause.") to "spinach fresh chahiye",
            "1 spinach; rice fresh chahiye, frozen nahi." to "rice fresh chahiye, frozen nahi.",
            "1 spinach; $clause if cheap." to "$clause if cheap.",
            "1 spinach; $clause. Buy 1 rice" to "$clause. Buy 1 rice",
            "1 spinach; please $clause." to "please $clause.",
        )
        scenarios.forEach { (instruction, support) ->
            val prepared = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
                propertyItem("spinach", "1 spinach", 1, required = listOf("fresh"), excluded = listOf("frozen")), support)))
                .prepareForReview(allowPropertyObligations = true)
            assertTrue("support=$support", prepared.items.isEmpty())
            assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun duplicateNamedFreshnessTargetsRemainUnresolved() {
        val clause = "spinach fresh chahiye, frozen nahi"
        val instruction = "1 spinach, 2 spinach; $clause."
        val rows = listOf(1, 2).mapIndexed { index, count -> propertyReadyRow(instruction, index,
            propertyItem("spinach", "$count spinach", count, required = listOf("fresh"), excluded = listOf("frozen")), "$clause.") }
        val prepared = propertyReview(instruction, rows).prepareForReview(allowPropertyObligations = true)
        assertTrue(prepared.items.isEmpty())
        assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    @Test fun lateNamedFreshnessMemberIdentityFailureCannotLeaveAnAcceptedDependentRow() {
        val clause = "spinach fresh chahiye, frozen nahi"
        val instruction = "1 spinach; $clause."
        val draft = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
            propertyItem("mango", "1 spinach", 1, required = listOf("fresh"), excluded = listOf("frozen")), "$clause.")))
        assertTrue(draft.provenPropertyPreparation() != null)
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertTrue(prepared.items.isEmpty())
        assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    @Test fun fifteenOriginalItemsKeepAmountsWhenOnlyLastItemOwnsFreshContrast() {
        val names = listOf("rice", "bread", "tea", "coffee", "flour", "oil", "potato", "tomato",
            "onion", "apple", "banana", "egg", "biscuit", "pasta", "spinach")
        val clause = "spinach fresh chahiye, frozen nahi"
        val instruction = names.joinToString(", ") { "1 $it" } + "; $clause."
        val rows = names.mapIndexed { index, name ->
            propertyReadyRow(instruction, index,
                propertyItem(name, "1 $name", 1,
                    required = if (name == "spinach") listOf("fresh") else emptyList(),
                    excluded = if (name == "spinach") listOf("frozen") else emptyList()),
                if (name == "spinach") "$clause." else null)
        }
        val draft = propertyReview(instruction, rows)
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertEquals(15, prepared.items.size)
        assertEquals(List(15) { Quantity.Count(1) }, prepared.items.map { it.quantity })
        assertEquals(names.map { "1 $it" }, prepared.items.map { it.intentQuantityProvenance?.primaryText })
        assertEquals(List(14) { emptyList<String>() } + listOf(listOf("frozen")), prepared.items.map { it.avoidPhrases })
        assertEquals(List(14) { emptyList<String>() } + listOf(listOf("fresh")), draft.readyRows.map { it.item.required })
        assertEquals(listOf(clause), prepared.sharedGroupNotes)
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun validatedFulfillmentContextPreservesSeparateReviewWithoutScheduling() {
        val instruction = "Bring these groceries tomorrow: 2 milk, 1 bread; milk should be low fat"
        val rows = listOf(
            propertyReadyRow(instruction, 0, propertyItem("milk", "2 milk", 2, required = listOf("low_fat")), "milk should be low fat"),
            propertyReadyRow(instruction, 1, propertyItem("bread", "1 bread", 1)),
        )
        val draft = propertyReview(instruction, rows).copy(constraints = deriveFulfillmentConstraints(instruction, rows.map { it.evidence.primary }))
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertEquals(2, prepared.items.size)
        assertEquals(listOf("milk should be low fat"), prepared.sharedGroupNotes)
        assertEquals(listOf("Bring these groceries tomorrow:"), prepared.fulfillmentConstraints)
        assertTrue(prepared.unresolvedText.isEmpty())
        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes, prepared.fulfillmentConstraints)
        assertFalse(gate.canApply())
    }

    @Test fun duplicatedModifierIsNotSilentlyRemoved() {
        val clause = "milk should be low fat"
        val instruction = "1 milk; $clause; $clause"
        val draft = propertyReview(instruction, listOf(propertyReadyRow(instruction, 0,
            propertyItem("milk", "1 milk", 1, required = listOf("low_fat")), clause)))
        val prepared = draft.prepareForReview(allowPropertyObligations = true)
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
    }

    @Test fun backendPropertyWireFixtureParsesStrictlyAndRebuildsClientNotes() {
        val relativePath = "scripts/fixtures/intent_property_wire_oct13.json"
        val fixtureFile = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile) ?: error("Cannot find backend property wire fixture at $relativePath")
        val source = fixtureFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("eb13b60f74793710ebb87bf00fec8c882b14b65e21fd9cbeb707c85787400cd9", sha256)
        val fixture = fixtureObject(JsonParser(source).parseStrict())
        assertEquals("grocery-intent-review-v2-property-wire-2026-10-13", fixture["version"].let(::fixtureString))
        val cases = fixtureArray(fixture["cases"])
        assertEquals(42, cases.size)

        cases.forEach { caseValue ->
            val case = fixtureObject(caseValue)
            val id = fixtureString(case["id"])
            val instruction = fixtureString(case["instruction"])
            val expected = fixtureObject(case["expected"])
            val draft = GroceryIntentReviewV2Response.parse(propertyFixtureJson(case["response"]), instruction).draft

            assertEquals(id, instruction, draft.instruction)
            assertTrue("$id has blocked wire rows", draft.blockedRows.isEmpty())
            assertEquals(id, fixtureInt(expected["row_count"]), draft.readyRows.size)
            assertEquals(id, fixtureStringLists(expected["required"]), draft.readyRows.map { it.item.required })
            assertEquals(id, fixtureStringLists(expected["excluded"]), draft.readyRows.map { it.item.excluded })
            assertTrue("$id retained wire support", draft.readyRows.all {
                it.item.supportingText.isEmpty() && it.evidence.supporting.isEmpty()
            })

            val prepared = draft.prepareForReview(allowPropertyObligations = true)
            assertEquals(id, fixtureInt(expected["row_count"]), prepared.items.size)
            assertEquals(id, fixtureInts(expected["quantities"]), prepared.items.map {
                (it.quantity as Quantity.Count).n
            })
            assertEquals(id, fixtureStringLists(expected["required"]), draft.readyRows.map { it.item.required })
            assertEquals(id, fixtureArray(expected["shared_notes"]).map(::fixtureString), prepared.sharedGroupNotes)
            assertTrue("$id left property text unresolved: ${prepared.unresolvedText}", prepared.unresolvedText.isEmpty())
            val sourceTexts = fixtureArray(fixtureObject(case["payload"])["items"]).map {
                fixtureString(fixtureObject(it)["source_text"])
            }
            assertEquals(id, sourceTexts, prepared.items.map { it.rawText })
            prepared.items.zip(fixtureStringLists(expected["required"])).forEach { (item, tags) ->
                tags.forEach { tag -> assertTrue("$id missing required $tag", item.query.contains(tag.replace('_', ' '))) }
            }
            prepared.items.zip(fixtureStringLists(expected["excluded"])).forEach { (item, tags) ->
                assertEquals(id, tags.map { it.replace('_', ' ') }, item.avoidPhrases)
            }
        }
    }

    @Test fun sharedNamedPropertyScopeIsOptInAndDoesNotChangeAmounts() {
        val instruction = "2 milk, 1 curd, 3 rice; all dairy items should be low fat"
        val clause = "all dairy items should be low fat"
        val draft = propertyReview(instruction, listOf(
            propertyReadyRow(instruction, 0, propertyItem("low fat milk", "2 milk", 2,
                required = listOf("low_fat")), clause),
            propertyReadyRow(instruction, 1, propertyItem("low fat curd", "1 curd", 1,
                required = listOf("low_fat")), clause),
            propertyReadyRow(instruction, 2, propertyItem("rice", "3 rice", 3)),
        ))

        val default = draft.prepareForReview()
        assertTrue(default.items.none { it.rawText == "2 milk" || it.rawText == "1 curd" })
        assertTrue((default.unresolvedText + default.excludedText).any { clause in it })

        val enabled = draft.prepareForReview(allowPropertyObligations = true)
        assertEquals(listOf(2, 1, 3), enabled.items.map { (it.quantity as Quantity.Count).n })
        assertEquals(listOf("2 milk", "1 curd", "3 rice"), enabled.items.map { it.rawText })
        assertTrue(enabled.items.take(2).all { it.query.contains("low fat") })
        assertTrue(enabled.items.none { it.query.contains("low fat") && it.rawText == "3 rice" })
        assertEquals(listOf(clause), enabled.sharedGroupNotes)
        assertEquals(listOf("2 milk", "1 curd", "3 rice"), enabled.items.map { it.rawText })
    }

    @Test fun noAddedSugarPositiveAndSugarFreeContrastKeepTheirPolarities() {
        val instruction = "1 peanut butter; peanut butter should have no added sugar, not sugar-free"
        val clause = "peanut butter should have no added sugar, not sugar-free"
        val draft = propertyReview(instruction, listOf(propertyReadyRow(
            instruction, 0, propertyItem("peanut butter", "1 peanut butter", 1,
                required = listOf("no_added_sugar"), excluded = listOf("sugar_free")), clause,
        )))

        val enabled = draft.prepareForReview(allowPropertyObligations = true)

        assertEquals(1, enabled.items.size)
        val item = enabled.items.single()
        assertEquals(Quantity.Count(1), item.quantity)
        assertTrue(item.query.contains("no added sugar"))
        assertEquals(listOf("sugar free"), item.avoidPhrases)
        assertEquals(clause, draft.readyRows.single().evidence.supporting.single().quote.text)
    }

    @Test fun propertyMissingOnOneMemberBlocksTheWholeSharedScope() {
        val instruction = "2 milk, 1 curd; all dairy items should be low fat"
        val clause = "all dairy items should be low fat"
        val draft = propertyReview(instruction, listOf(
            propertyReadyRow(instruction, 0, propertyItem("low fat milk", "2 milk", 2,
                required = listOf("low_fat")), clause),
            propertyReadyRow(instruction, 1, propertyItem("curd", "1 curd", 1), clause),
        ))

        val enabled = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(enabled.items.isEmpty())
        assertTrue(enabled.excludedText.isNotEmpty() || enabled.unresolvedText.isNotEmpty())
    }

    @Test fun unknownDhaniyaFormCannotBeIncludedInDairyPropertyScope() {
        val instruction = "1 milk, 1 dhaniya leaves; all dairy items should be low fat"
        val clause = "all dairy items should be low fat"
        val draft = propertyReview(instruction, listOf(
            propertyReadyRow(instruction, 0, propertyItem("low fat milk", "1 milk", 1,
                required = listOf("low_fat")), clause),
            propertyReadyRow(instruction, 1, propertyItem("dhaniya leaves", "1 dhaniya leaves", 1)),
        ))

        val enabled = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(enabled.items.isEmpty())
        assertTrue(enabled.excludedText.isNotEmpty() || enabled.unresolvedText.isNotEmpty())
    }

    @Test fun omittedOrCroppedPrimaryCannotCompletePropertyScope() {
        val omittedInstruction = "2 milk, 1 curd, 3 rice; all dairy items should be low fat"
        val omittedClause = "all dairy items should be low fat"
        val omitted = propertyReview(omittedInstruction, listOf(
            propertyReadyRow(omittedInstruction, 0, propertyItem("low fat milk", "2 milk", 2,
                required = listOf("low_fat")), omittedClause),
            propertyReadyRow(omittedInstruction, 1, propertyItem("rice", "3 rice", 3)),
        )).prepareForReview(allowPropertyObligations = true)
        assertTrue(omitted.items.isEmpty())

        val croppedInstruction = "1 almond milk; milk should be low fat"
        val croppedClause = "milk should be low fat"
        val cropped = propertyReview(croppedInstruction, listOf(propertyReadyRow(
            croppedInstruction, 0, propertyItem("low fat milk", "milk", 1,
                required = listOf("low_fat")), croppedClause,
        ))).prepareForReview(allowPropertyObligations = true)
        assertTrue(cropped.items.isEmpty())
    }

    @Test fun propertyScopeCannotProjectAwayAProductIdentityWord() {
        val instruction = "1 almond milk; milk should be low fat"
        val clause = "milk should be low fat"
        val draft = propertyReview(instruction, listOf(propertyReadyRow(
            instruction, 0, propertyItem("low fat milk", "1 almond milk", 1,
                required = listOf("low_fat")), clause,
        )))

        val enabled = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(enabled.items.isEmpty())
        assertTrue(enabled.excludedText.isNotEmpty() || enabled.unresolvedText.isNotEmpty())
    }

    @Test fun allClosedPropertyPhrasesPreservePositiveAndExcludedTagsWithoutWireSupport() {
        val cases = listOf(
            "low fat" to "low_fat",
            "no added sugar" to "no_added_sugar",
            "sugar free" to "sugar_free",
            "unsweetened" to "unsweetened",
            "sweetened" to "sweetened",
            "plain" to "plain",
            "unsalted" to "unsalted",
            "organic" to "organic",
            "gluten free" to "gluten_free",
            "lactose free" to "lactose_free",
            "vegan" to "vegan",
            "vegetarian" to "vegetarian",
            "ready to eat" to "ready_to_eat",
            "ready to drink" to "ready_to_drink",
            "ready to cook" to "ready_to_cook",
            "whole" to "whole",
            "ground" to "ground",
            "powder" to "powder",
            "full cream" to "full_cream",
            "full fat" to "full_fat",
        )

        cases.forEach { (phrase, tag) ->
            val positiveInstruction = "1 milk; milk should be $phrase"
            val positive = propertyReview(positiveInstruction, listOf(propertyReadyRow(
                positiveInstruction, 0,
                propertyItem("milk", "1 milk", 1, required = listOf(tag)),
            ))).prepareForReview(allowPropertyObligations = true)
            assertEquals(phrase, 1, positive.items.size)
            assertEquals(phrase, Quantity.Count(1), positive.items.single().quantity)
            assertEquals(phrase, "1 milk", positive.items.single().rawText)
            assertTrue("$phrase was not projected to the query", positive.items.single().query.contains(phrase))
            assertTrue("$phrase unexpectedly added an exclusion", positive.items.single().avoidPhrases.isEmpty())
            assertTrue("$phrase remained unresolved: ${positive.unresolvedText}", positive.unresolvedText.isEmpty())

            val negativeInstruction = "1 milk; milk should not be $phrase"
            val negative = propertyReview(negativeInstruction, listOf(propertyReadyRow(
                negativeInstruction, 0,
                propertyItem("milk", "1 milk", 1, excluded = listOf(tag)),
            ))).prepareForReview(allowPropertyObligations = true)
            assertEquals("excluded $phrase", 1, negative.items.size)
            assertEquals("excluded $phrase", Quantity.Count(1), negative.items.single().quantity)
            assertEquals("excluded $phrase", "1 milk", negative.items.single().rawText)
            assertTrue("excluded $phrase was not retained", negative.items.single().avoidPhrases.contains(phrase))
            assertEquals("excluded $phrase changed required query", "milk", negative.items.single().query)
        }
    }

    @Test fun selectorMembershipCannotLeakFromProposedDairyMilkName() {
        val instruction = "1 milk, 1 rice; all dairy items should be low fat"
        val clause = "all dairy items should be low fat"
        val draft = propertyReview(instruction, listOf(
            propertyReadyRow(instruction, 0, propertyItem("dairy milk", "1 rice", 1,
                required = listOf("low_fat")), clause),
            propertyReadyRow(instruction, 1, propertyItem("milk", "1 milk", 1,
                required = listOf("low_fat")), clause),
        ))

        val enabled = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(enabled.items.isEmpty())
        assertTrue(enabled.excludedText.isNotEmpty() || enabled.unresolvedText.isNotEmpty())
    }

    @Test fun partialModifierSupportIsNotTreatedAsTheExactPropertyClause() {
        val instruction = "1 milk, 1 dhaniya leaves; all dairy items should be low fat"
        val partial = "dairy items should be low fat"
        val draft = propertyReview(instruction, listOf(
            propertyReadyRow(instruction, 0, propertyItem("low fat milk", "1 milk", 1,
                required = listOf("low_fat")), partial),
            propertyReadyRow(instruction, 1, propertyItem("dhaniya leaves", "1 dhaniya leaves", 1)),
        ))

        val enabled = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(enabled.items.isEmpty())
        assertTrue(enabled.excludedText.isNotEmpty() || enabled.unresolvedText.isNotEmpty())
    }

    @Test fun numericCorrectionAndSharedPropertyScopeComposeInEitherClauseOrder() {
        listOf(
            "2 milk, 1 curd; make milk three; all dairy items should be low fat",
            "2 milk, 1 curd; all dairy items should be low fat; make milk three",
        ).forEach { instruction ->
            val clause = "all dairy items should be low fat"
            val correction = propertyCorrection(instruction, "make milk three", "three", 2, 3)
            val draft = propertyWireReview(instruction, listOf(
                WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(correction)),
                WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
            ))

            val prepared = draft.prepareForReview(allowPropertyObligations = true)

            assertEquals(instruction, listOf(Quantity.Count(3), Quantity.Count(1)),
                prepared.items.map { it.quantity })
            assertTrue(instruction, prepared.items[0].rawText.startsWith("2 milk"))
            assertTrue(instruction, prepared.items[0].rawText.contains("Quantity changed: 2 count → 3 count"))
            assertEquals(instruction, 1, Regex("make milk three").findAll(prepared.items[0].rawText).count())
            assertEquals(instruction, "1 curd", prepared.items[1].rawText)
            assertEquals(instruction, listOf(clause), prepared.sharedGroupNotes)
            assertTrue(instruction, prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun independentCorrectionsOnDifferentRowsKeepSharedPropertyObligations() {
        val instruction = "2 milk, 1 curd; make milk three; make curd two; all dairy items should be low fat"
        val clause = "all dairy items should be low fat"
        val draft = propertyWireReview(instruction, listOf(
            WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                propertyCorrection(instruction, "make milk three", "three", 2, 3))),
            WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat"), corrections = listOf(
                propertyCorrection(instruction, "make curd two", "two", 1, 2))),
        ))

        val prepared = draft.prepareForReview(allowPropertyObligations = true)

        assertEquals(listOf(Quantity.Count(3), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.items[0].rawText.startsWith("2 milk"))
        assertTrue(prepared.items[0].rawText.contains("Quantity changed: 2 count → 3 count"))
        assertTrue(prepared.items[1].rawText.startsWith("1 curd"))
        assertTrue(prepared.items[1].rawText.contains("Quantity changed: 1 count → 2 count"))
        assertEquals(listOf(clause), prepared.sharedGroupNotes)
    }

    @Test fun unownedOrConflictingCorrectionContextCannotAuthorizeSharedProperties() {
        val clause = "all dairy items should be low fat"
        val unowned = "2 milk, 1 curd; make milk three; $clause; maybe another item"
        val unownedDraft = propertyWireReview(unowned, listOf(
            WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                propertyCorrection(unowned, "make milk three", "three", 2, 3))),
            WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
        ))
        assertTrue(unownedDraft.prepareForReview(allowPropertyObligations = true).items.isEmpty())

        val missingProperty = "2 milk, 1 curd; make milk three; $clause"
        val missingDraft = propertyWireReview(missingProperty, listOf(
            WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                propertyCorrection(missingProperty, "make milk three", "three", 2, 3))),
            WirePropertyRow(1, "curd", "1 curd", 1),
        ))
        assertTrue(missingDraft.prepareForReview(allowPropertyObligations = true).items.isEmpty())

        val changedTarget = "2 milk, 1 curd; make curd three; $clause"
        assertThrows(IllegalArgumentException::class.java) {
            propertyWireReview(changedTarget, listOf(
                WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                    propertyCorrection(changedTarget, "make curd three", "three", 2, 3))),
                WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
            ))
        }

        val wrongAmount = "2 milk, 1 curd; make milk three; $clause"
        assertThrows(IllegalArgumentException::class.java) {
            propertyWireReview(wrongAmount, listOf(
                WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                    propertyCorrection(wrongAmount, "make milk three", "three", 2, 4))),
                WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
            ))
        }

        val duplicate = "2 milk, 1 curd; make milk three; make milk four; $clause"
        assertThrows(IllegalArgumentException::class.java) {
            propertyWireReview(duplicate, listOf(
                WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                    propertyCorrection(duplicate, "make milk three", "three", 2, 3),
                    propertyCorrection(duplicate, "make milk four", "four", 2, 4))),
                WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
            ))
        }

        val overlap = "2 milk, 1 curd; make milk three; $clause"
        assertThrows(IllegalArgumentException::class.java) {
            propertyWireReview(overlap, listOf(
                WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                    propertyCorrection(overlap, "make milk three; $clause", "three", 2, 3))),
                WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
            ))
        }
    }

    @Test fun correctionLabelWrapperComposesWithPropertiesInEitherOrder() {
        listOf(
            "2 milk, 1 curd; Correction: make milk three; all dairy items should be low fat",
            "2 milk, 1 curd; all dairy items should be low fat; Correction: make milk three",
        ).forEach { instruction ->
            val correction = propertyCorrection(instruction, "make milk three", "three", 2, 3)
            val draft = propertyWireReview(instruction, listOf(
                WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(correction)),
                WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
            ))

            val prepared = draft.prepareForReview(allowPropertyObligations = true)

            assertEquals(listOf(Quantity.Count(3), Quantity.Count(1)), prepared.items.map { it.quantity })
            assertTrue(prepared.items[0].rawText.startsWith("2 milk"))
            assertTrue(prepared.items[0].rawText.contains("Quantity changed: 2 count → 3 count"))
            assertEquals("1 curd", prepared.items[1].rawText)
            assertEquals(listOf("all dairy items should be low fat"), prepared.sharedGroupNotes)
            assertTrue(prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun omittedCorrectionOperationCannotAuthorizePropertyScope() {
        val instruction = "2 milk, 1 curd; make milk three; all dairy items should be low fat"
        val draft = propertyWireReview(instruction, listOf(
            WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat")),
            WirePropertyRow(1, "low fat curd", "1 curd", 1, listOf("low_fat")),
        ))

        assertTrue(draft.prepareForReview(allowPropertyObligations = true).items.isEmpty())
    }

    @Test fun lateIdentityFailureRevokesEveryComposedPropertyRow() {
        val instruction = "2 milk, 1 curd; make milk three; all dairy items should be low fat"
        val draft = propertyWireReview(instruction, listOf(
            WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
                propertyCorrection(instruction, "make milk three", "three", 2, 3))),
            WirePropertyRow(1, "low fat mango", "1 curd", 1, listOf("low_fat")),
        ))

        assertTrue(draft.provenPropertyPreparation() != null)
        val prepared = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty())
    }

    @Test fun lateIdentityFailureRevokesAllRowsWithIndependentCorrections() {
        val instruction = "2 milk, 1 curd; make milk three; make curd two"
        val draft = propertyWireReview(instruction, listOf(
            WirePropertyRow(0, "mango", "2 milk", 2, corrections = listOf(
                propertyCorrection(instruction, "make milk three", "three", 2, 3))),
            WirePropertyRow(1, "curd", "1 curd", 1, corrections = listOf(
                propertyCorrection(instruction, "make curd two", "two", 1, 2))),
        ))

        val prepared = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty())
    }

    @Test fun correctedRequestWithoutPropertyStillHoldsUnknownOrdinaryResidual() {
        val instruction = "2 milk; make milk three; birthday supplies"
        val draft = propertyWireReview(instruction, listOf(WirePropertyRow(
            0, "milk", "2 milk", 2, corrections = listOf(
                propertyCorrection(instruction, "make milk three", "three", 2, 3)),
        )))

        val prepared = draft.prepareForReview(allowPropertyObligations = true)

        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty())
    }

    @Test fun longExactSourceBasketComposesNamedMilkPropertyAndCorrection() {
        val names = listOf(
            "milk", "curd", "rice", "bread", "tea", "coffee", "flour", "oil", "potato",
            "tomato", "onion", "apple", "banana", "egg", "biscuit", "pasta", "paneer", "cheese",
        )
        val clause = "milk should be low fat"
        val instruction = (listOf("2 milk") + names.drop(1).map { "1 $it" }).joinToString(", ") +
            "; make milk three; $clause"
        val rows = listOf(WirePropertyRow(0, "low fat milk", "2 milk", 2, listOf("low_fat"), corrections = listOf(
            propertyCorrection(instruction, "make milk three", "three", 2, 3)))) +
            names.drop(1).mapIndexed { index, name -> WirePropertyRow(index + 1, name, "1 $name", 1) }
        val draft = propertyWireReview(instruction, rows)

        val prepared = draft.prepareForReview(allowPropertyObligations = true)

        assertEquals(18, prepared.items.size)
        assertEquals(Quantity.Count(3), prepared.items.first().quantity)
        assertEquals(listOf(listOf("low_fat")) + List(17) { emptyList<String>() }, draft.readyRows.map { it.item.required })
        assertEquals(listOf(clause), prepared.sharedGroupNotes)
        assertTrue(prepared.items.first().rawText.contains("Quantity changed: 2 count → 3 count"))
    }
}

private fun propertyReview(instruction: String, readyRows: List<GroceryIntentReviewV2ReadyRow>) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2",
    requiresReview = true,
    instruction = instruction,
    sourceItemCount = readyRows.size,
    readyRows = readyRows,
    blockedRows = emptyList(),
)

private fun propertyItem(
    name: String,
    source: String,
    amount: Int,
    required: List<String> = emptyList(),
    excluded: List<String> = emptyList(),
) = GroceryIntentReviewV2Item(
    name = name,
    quantity = BigDecimal.valueOf(amount.toLong()),
    quantityUnit = "count",
    packValue = null,
    packUnit = "unspecified",
    packaging = null,
    required = required,
    excluded = excluded,
    sourceText = source,
    supportingText = emptyList(),
    needsClarification = false,
    quantityText = amount.toString(),
    packText = null,
)

private fun propertyReadyRow(
    instruction: String,
    index: Int,
    item: GroceryIntentReviewV2Item,
    propertySupport: String? = null,
    corrections: List<GroceryIntentReviewV2Correction> = emptyList(),
) = GroceryIntentReviewV2ReadyRow(
    rowIndex = index,
    item = item.copy(supportingText = listOfNotNull(propertySupport?.let {
        GroceryIntentReviewV2SupportText("modifier", it)
    })),
    evidence = GroceryIntentReviewV2Evidence(
        primary = propertyQuote(instruction, item.sourceText),
        supporting = listOfNotNull(propertySupport?.let {
            GroceryIntentReviewV2SupportQuote("modifier", propertyQuote(instruction, it))
        }),
    ),
    quantityOrigin = "stated",
    amountRoleStatus = "matched",
    corrections = corrections,
)

private fun propertyQuote(instruction: String, text: String) = GroceryIntentReviewV2Quote(
    text,
    instruction.indexOf(text).also { require(it >= 0) },
    instruction.indexOf(text) + text.length,
)

private fun propertyCorrection(
    instruction: String,
    sourceText: String,
    amountText: String,
    oldValue: Int,
    newValue: Int,
) : GroceryIntentReviewV2Correction {
    val sourceStart = instruction.indexOf(sourceText).also { require(it >= 0) }
    val amountStart = instruction.indexOf(amountText, sourceStart).also { require(it >= sourceStart) }
    val source = GroceryIntentReviewV2Quote(sourceText, sourceStart, sourceStart + sourceText.length)
    val amount = GroceryIntentReviewV2Quote(amountText, amountStart, amountStart + amountText.length)
    return GroceryIntentReviewV2Correction("quantity", source, amount,
        BigDecimal.valueOf(oldValue.toLong()), "count", BigDecimal.valueOf(newValue.toLong()), "count")
}

private data class WirePropertyRow(
    val index: Int,
    val name: String,
    val source: String,
    val quantity: Int,
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val corrections: List<GroceryIntentReviewV2Correction> = emptyList(),
)

private fun propertyWireReview(instruction: String, rows: List<WirePropertyRow>): GroceryIntentReviewV2 =
    GroceryIntentReviewV2Response.parse(propertyWireJson(instruction, rows), instruction).draft

private fun propertyWireJson(instruction: String, rows: List<WirePropertyRow>): String {
    fun strings(values: List<String>) = values.joinToString(prefix = "[", postfix = "]") { propertyFixtureString(it) }
    fun quote(value: GroceryIntentReviewV2Quote) = """{"text":${propertyFixtureString(value.text)},"start_utf16":${value.startUtf16},"end_utf16":${value.endUtf16}}"""
    fun correction(value: GroceryIntentReviewV2Correction) = """{"kind":${propertyFixtureString(value.kind)},"source":${quote(value.source)},"new_amount":${quote(value.newAmount)},"old_value":${value.oldValue.toPlainString()},"old_unit":${propertyFixtureString(value.oldUnit)},"new_value":${value.newValue.toPlainString()},"new_unit":${propertyFixtureString(value.newUnit)}}"""
    val readyRows = rows.joinToString(",") { row ->
        val quantity = row.quantity
        val quantityText = Regex("^\\d+").find(row.source)?.value ?: error("Missing primary amount")
        val corrections = if (row.corrections.isEmpty()) "" else row.corrections.joinToString(prefix = ",\"corrections\":[", postfix = "]") { correction(it) }
        """{"row_index":${row.index},"item":{"name":${propertyFixtureString(row.name)},"quantity":$quantity,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":${strings(row.required)},"excluded":${strings(row.excluded)},"source_text":${propertyFixtureString(row.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${propertyFixtureString(quantityText)},"pack_text":null},"evidence":{"primary":${quote(propertyQuote(instruction, row.source))},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"$corrections}"""
    }
    return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${propertyFixtureString(instruction)},"source_item_count":${rows.size},"ready_rows":[$readyRows],"blocked_rows":[]},"model":"synthetic","promptVersion":"synthetic","latencyMs":0,"usage":{}}"""
}

private fun fixtureObject(value: JsonValue?): Map<String, JsonValue> =
    (value as? JsonValue.Obj)?.members ?: error("Expected fixture object")

private fun fixtureArray(value: JsonValue?): List<JsonValue> =
    (value as? JsonValue.Arr)?.items ?: error("Expected fixture array")

private fun fixtureString(value: JsonValue?): String =
    (value as? JsonValue.Str)?.value ?: error("Expected fixture string")

private fun fixtureInt(value: JsonValue?): Int =
    (value as? JsonValue.Num)?.raw?.toIntOrNull() ?: error("Expected fixture integer")

private fun fixtureInts(value: JsonValue?): List<Int> = fixtureArray(value).map(::fixtureInt)

private fun fixtureStringLists(value: JsonValue?): List<List<String>> =
    fixtureArray(value).map { list -> fixtureArray(list).map(::fixtureString) }

internal fun propertyFixtureJson(value: JsonValue?): String = when (value) {
    null, JsonValue.Null -> "null"
    is JsonValue.Bool -> value.value.toString()
    is JsonValue.Num -> value.raw
    is JsonValue.Str -> propertyFixtureString(value.value)
    is JsonValue.Arr -> value.items.joinToString(prefix = "[", postfix = "]", separator = ",") { propertyFixtureJson(it) }
    is JsonValue.Obj -> value.members.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        "${propertyFixtureString(it.key)}:${propertyFixtureJson(it.value)}"
    }
}

private fun propertyFixtureString(text: String): String = buildString {
    append('"')
    text.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
