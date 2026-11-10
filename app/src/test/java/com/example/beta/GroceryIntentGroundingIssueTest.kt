package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentGroundingIssueTest {
    @Test fun nestedQuotesCannotReuseAnOriginalNumericAtom() {
        val instruction = "two bars of Vim and Surf, each 200 g"
        val rows = listOf(
            item("Vim", instruction, "2", "count", "200", "g"),
            item("bars", "two bars", "2", "count"))
        assertEquals(rows.map { it.sourceText }.toSet(), legacyNumericSourceCollisions(instruction, rows).toSet())
        assertNumericCollisionHolds(instruction, rows)
    }

    @Test fun quantifiedRowsSharingOnlyQualitativeWordsDoNotAcquireANumericCollision() {
        val instruction = "two organic paneer and milk 1 litre"
        val rows = listOf(
            item("organic paneer", "two organic paneer and milk", "2", "count"),
            item("organic milk", "organic paneer and milk 1 litre", "1", "l"))
        assertTrue(legacyNumericSourceCollisions(instruction, rows).isEmpty())
    }

    @Test fun ambiguousShortQuoteCannotBorrowAUniqueLargerPurchaseSpan() {
        val instruction = "two Vim bars; two Vim bars Surf"
        val rows = listOf(item("Vim", "two Vim bars", "2", "count"),
            item("Surf", "two Vim bars Surf", "2", "count"))
        assertTrue(bindGroceryIntentProductSource(instruction, rows[0].sourceText, 6000) == null)
        assertTrue(bindGroceryIntentProductSource(instruction, rows[1].sourceText, 6000) != null)
        assertEquals(listOf(rows[0].sourceText), legacyNumericSourceCollisions(instruction, rows))
        assertNumericCollisionHolds(instruction, rows)
    }

    @Test fun wordInteriorNearMatchDoesNotMakeAnExactSourceAmbiguous() {
        val instruction = "salt and unsalted butter"
        val rows = listOf(item("salt", "salt"), item("unsalted butter", "unsalted butter"))
        assertEquals(0, requireNotNull(bindGroceryIntentProductSource(instruction, "salt", 6000)).startUtf16)
        val result = groceryIntentGrounding(instruction, rows)
        assertTrue(legacyNumericSourceCollisions(instruction, rows).isEmpty())
        assertFalse(GroceryIntentGroundingIssueKind.SOURCE_SCOPE in kinds(result, 0))
        assertFalse(GroceryIntentGroundingIssueKind.SOURCE_SCOPE in kinds(result, 1))
        val prepared = GroceryIntentDraft("grocery-intent-v1", true, instruction, rows, false).prepareForReview()
        assertEquals(2, prepared.items.size)
    }

    @Test fun legacyRowsCannotReuseOneOriginalPurchaseOccurrence() {
        val source = "two bars of Vim and Surf, each 200 g"
        val rows = listOf(item("Vim", source, "2", "count", "200", "g"),
            item("Surf", source, "2", "count", "200", "g"))
        assertEquals(listOf(source), legacyNumericSourceCollisions(source, rows))
        assertNumericCollisionHolds(source, rows)
    }

    @Test fun partialOriginalQuoteOverlapCannotShareAPurchaseAmount() {
        val source = "Vim two bars Surf"
        val rows = listOf(item("Vim", "Vim two bars", "2", "count"),
            item("Surf", "two bars Surf", "2", "count"))
        assertEquals(rows.map { it.sourceText }.toSet(), legacyNumericSourceCollisions(source, rows).toSet())
        assertNumericCollisionHolds(source, rows)
    }

    @Test fun distinctAdjacentQuotesAndRepeatedWordsDoNotCollide() {
        for ((source, rows) in listOf(
            "two bars of Vim, three bars of Surf" to listOf(
                item("Vim", "two bars of Vim", "2", "count"),
                item("Surf", "three bars of Surf", "3", "count")),
            "two bars of Vim two bars of Surf" to listOf(
                item("Vim", "two bars of Vim", "2", "count"),
                item("Surf", "two bars of Surf", "2", "count")),
        )) {
            assertTrue(source, legacyNumericSourceCollisions(source, rows).isEmpty())
            val result = groceryIntentGrounding(source, rows)
            assertFalse(source, GroceryIntentGroundingIssueKind.SOURCE_SCOPE in kinds(result, 0))
            assertFalse(source, GroceryIntentGroundingIssueKind.SOURCE_SCOPE in kinds(result, 1))
        }
    }

    private fun assertNumericCollisionHolds(instruction: String, rows: List<GroceryIntentItem>) {
        val sources = legacyNumericSourceCollisions(instruction, rows)
        val draft = GroceryIntentDraft("grocery-intent-v1", true, instruction, rows, false)
        val error = assertThrows(IntentNeedsReviewException::class.java) { draft.prepareForReview() }
        assertTrue(error.affectedSourceText.containsAll(sources))
        val assessment = draft.assessForContextReview()
        assertTrue(assessment.preparation == null)
        assertTrue(assessment.holds.any { it.check == GroceryIntentDraftCheck.NUMERIC_OWNERSHIP &&
            it.affectedSourceText.containsAll(sources) })
    }

    @Test fun invisibleFormattingCannotHideNativeScopeOperatorsAndNeverProvesIdentity() {
        for (format in listOf("\u200d", "\u200c", "\u202e", "\u2066", "\u0301", "\udb40\udc01")) {
            for (operator in listOf("नहीं", "नहीँ", "बिना", "अगर", "मत")) {
                for (decorated in listOf(format + operator, operator + format,
                    operator.take(1) + format + operator.drop(1))) {
                    val source = "दूध $decorated"
                    assertTrue(source, groceryIntentHasUncompiledNativeSourceScope(source))
                    assertTrue(GroceryIntentGroundingIssueKind.SOURCE_SCOPE in
                        kinds(groceryIntentGrounding(source, listOf(item(source, source)))))
                }
            }
        }
        // Original check must survive shadow concatenation of a format separator.
        assertTrue(groceryIntentHasUncompiledNativeSourceScope("नहीं\u202eसा"))
        for (source in listOf("नहीं\u200dसा", "नहीं\u200cसा", "नही\u0301सा", "अगरबत्ती",
            "भिंडी", "मति", "नहीं\ua8e0", "क्\u200dष", "milk\u200d", "not\u0301 milk", "तो\u200d दूध")) {
            assertFalse(source, groceryIntentHasUncompiledNativeSourceScope(source))
        }
    }

    @Test fun uncompiledNativeOperatorsDenyOnlyOriginalLocalSourceScope() {
        val scopes = listOf("दूध नहीं", "दूध नही", "दूध नहीँ", "मत दूध", "बिना दूध", "अगर दूध",
            "यदि दूध", "दूध वरना", "दूध बजाय", "दूध अलावा", "दूध छोड़कर", "मिल जाए तो दूध")
        for (scope in scopes) {
            assertTrue(scope, groceryIntentHasUncompiledNativeSourceScope(scope))
            for (name in listOf(scope, "दूध", scope.split(' ').reversed().joinToString(" "))) {
                for (prefix in listOf("", "500 g ", "500 ग्राम ", "2 ")) {
                    val source = prefix + scope
                    val proposed = item(name, source, excluded = listOf("दूध"))
                    val result = groceryIntentGrounding("please get $source", listOf(proposed))
                    assertTrue(source, source in result.rejected)
                    assertTrue(source, GroceryIntentGroundingIssueKind.SOURCE_SCOPE in kinds(result))
                }
            }
        }
        for (source in listOf("दूध", "तो दूध", "नहींसा", "नहीसा", "मतलब", "बिनायक",
            "अगरबत्ती", "यदिगंध", "वरनागंध", "बजायसा", "अलावासा", "छोड़करसा", "तोरई",
            "नहीं\u200dसा", "नही\u0301सा", "milk", "sugar-free milk")) {
            assertFalse(source, groceryIntentHasUncompiledNativeSourceScope(source))
        }
        assertTrue(groceryIntentGrounding("अगर दूध; rice", listOf(item("rice", "rice"))).rejected.isEmpty())
        assertTrue(groceryIntentGrounding("milk", listOf(item("milk", "milk"))).rejected.isEmpty())
        assertTrue(groceryIntentGrounding("अगरबत्ती", listOf(item("अगरबत्ती", "अगरबत्ती"))).rejected.isEmpty())
    }

    private fun item(
        name: String,
        source: String,
        quantity: String? = null,
        quantityUnit: String = "unspecified",
        packValue: String? = null,
        packUnit: String = "unspecified",
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
    ) = GroceryIntentItem(name, quantity?.toBigDecimal(), quantityUnit, packValue?.toBigDecimal(), packUnit,
        required, excluded, source, false)

    private fun kinds(result: GroceryIntentGroundingResult, row: Int = 0) =
        result.issues.filter { it.rowIndex == row }.map { it.kind }.toSet()

    @Test fun nativeVowelMarksRemainPartOfTheGroundedIdentity() {
        assertEquals(intentEvidenceTokens("क़लम"), intentEvidenceTokens("क़लम"))
        assertTrue(intentEvidenceTokens("\u093f").isEmpty())
        assertTrue(intentEvidenceTokens("क्\u200dष") != intentEvidenceTokens("क् ष"))
        for ((literal, other) in listOf("तिल" to "तेल", "बेल" to "बैल")) {
            assertEquals(setOf(literal), intentEvidenceTokens(literal))
            assertTrue(groceryIntentGrounding(literal, listOf(item(literal, literal))).rejected.isEmpty())
            assertTrue(GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED in
                kinds(groceryIntentGrounding(literal, listOf(item(other, literal)))))
        }
    }

    @Test fun spellingAndUnsupportedTranslationRemainRejectedAsLexicalEvidenceFailures() {
        for ((instruction, proposedName) in listOf("biskits" to "biscuits", "चावल" to "rice")) {
            val result = groceryIntentGrounding(instruction, listOf(item(proposedName, instruction)))
            assertEquals(listOf(instruction), result.rejected)
            assertTrue(kinds(result).contains(GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED))
        }
    }

    @Test fun terminalPropertyScopeEarlyReturnDoesNotInventPositiveIdentityReasons() {
        val source = "milk, not frozen"
        val result = groceryIntentGrounding("$source; rice", listOf(
            item("chocolate milk", source), item("rice", "rice"),
        ))
        assertEquals(listOf(source), result.rejected)
        assertEquals(setOf(GroceryIntentGroundingIssueKind.SOURCE_SCOPE), kinds(result))
        assertEquals(setOf(0), result.unevaluatedPropertyRows)
    }

    @Test fun lexicalDiagnosticIsNotAnExclusiveScopeOrQuantityVerdict() {
        val borrowed = groceryIntentGrounding("Acme: biskits", listOf(item("Acme biscuits", "biskits")))
        assertEquals(setOf(GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED), kinds(borrowed))
        assertEquals(setOf("biskits"), borrowed.unprovenIdentityScope)
        assertEquals(listOf("biskits"), borrowed.rejected)

        val wrongAmount = item("eggs", "six eggs", quantity = "5", quantityUnit = "count")
        assertTrue(groceryIntentGrounding("six eggs", listOf(wrongAmount)).issues.isEmpty())
        assertTrue(wrongAmount.legacyNumericOwnershipIssue() != null)
        assertThrows(IntentNeedsReviewException::class.java) {
            GroceryIntentDraft("grocery-intent-v1", true, "six eggs", listOf(wrongAmount), false).prepareForReview()
        }
    }

    @Test fun multipleFailuresDoNotThrowBeforeStructuredDiagnosticsAreReturned() {
        // Strict wire parsing rejects fractional piece sizes before this stage.
        // The internal comparison is still safe when given this invalid value.
        val source = "two organic samosas"
        val result = groceryIntentGrounding(source, listOf(item("chocolate samosas", source,
            quantity = "2", quantityUnit = "pack", packValue = "1.5", packUnit = "piece")))
        assertEquals(listOf(source), result.rejected)
        assertTrue(kinds(result).containsAll(setOf(GroceryIntentGroundingIssueKind.COUNT_ROLE,
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED, GroceryIntentGroundingIssueKind.PROPERTY_MISSING)))
    }

    @Test fun lexicalMismatchDoesNotHideMissingOrAddedProtectedForms() {
        val missing = groceryIntentGrounding("fresh organic milk",
            listOf(item("chocolate milk", "fresh organic milk")))
        assertEquals(listOf("fresh organic milk"), missing.rejected)
        assertTrue(kinds(missing).containsAll(setOf(
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED,
            GroceryIntentGroundingIssueKind.PROPERTY_MISSING,
        )))

        val added = groceryIntentGrounding("milk",
            listOf(item("chocolate milk", "milk", required = listOf("organic"))))
        assertEquals(listOf("milk"), added.rejected)
        assertTrue(kinds(added).containsAll(setOf(
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED,
            GroceryIntentGroundingIssueKind.PROPERTY_ADDED,
        )))
    }

    @Test fun borrowedIdentityReportsSourceScopeAndRetainsIndependentLexicalIssue() {
        val instruction = "organic milk. Get bread"
        val result = groceryIntentGrounding(instruction,
            listOf(item("organic premium bread", "bread")))
        assertEquals(listOf("bread"), result.rejected)
        assertTrue(kinds(result).containsAll(setOf(
            GroceryIntentGroundingIssueKind.SOURCE_SCOPE,
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED,
        )))
        assertEquals(setOf("bread"), result.unprovenIdentityScope)
    }

    @Test fun quantityRoleFailureRemainsDistinctFromLexicalMismatch() {
        val result = groceryIntentGrounding("two milk",
            listOf(item("chocolate milk", "two milk", quantity = "2", quantityUnit = "pack")))
        assertEquals(listOf("two milk"), result.rejected)
        assertTrue(kinds(result).containsAll(setOf(
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED,
            GroceryIntentGroundingIssueKind.COUNT_ROLE,
        )))
    }

    @Test fun lexicalFailuresRemainSeparateFromPackEvidenceAndAddedExclusionIssues() {
        val packSource = "500 g packet milk"
        val pack = groceryIntentGrounding(packSource, listOf(item("chocolate milk", packSource)))
        assertEquals(listOf(packSource), pack.rejected)
        assertTrue(kinds(pack).containsAll(setOf(
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED,
            GroceryIntentGroundingIssueKind.PACK_EVIDENCE,
        )))

        val exclusion = groceryIntentGrounding("milk", listOf(
            item("chocolate milk", "milk", excluded = listOf("gluten_free")),
        ))
        assertEquals(listOf("milk"), exclusion.rejected)
        assertTrue(kinds(exclusion).containsAll(setOf(
            GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED,
            GroceryIntentGroundingIssueKind.EXCLUSION_ADDED,
        )))
    }

    @Test fun sharedHeaderRemainsReviewOnlyAndLegacyProjectionsStayExact() {
        val instruction = "Brand fruit yogurt cups: mango one, blueberry one, strawberry one."
        val sources = listOf("mango one", "blueberry one", "strawberry one")
        val result = groceryIntentGrounding(instruction, listOf(
            item("Brand mango fruit yogurt cups", sources[0]),
            item("Brand blueberry fruit yogurt cups", sources[1]),
            item("Brand strawberry fruit yogurt cups", sources[2]),
        ))
        assertEquals(emptyList<String>(), result.rejected)
        assertEquals(listOf(
            "Shared wording: Brand fruit yogurt cups\nFor blueberry one, Beta understood Brand blueberry fruit yogurt cups. Check this in your basket.",
            "Shared wording: Brand fruit yogurt cups\nFor strawberry one, Beta understood Brand strawberry fruit yogurt cups. Check this in your basket.",
        ), result.review)
        assertEquals(sources.toSet(), result.unprovenIdentityScope)
        assertTrue(result.issues.isEmpty())

        val exact = groceryIntentGrounding("milk", listOf(item("milk", "milk")))
        assertEquals(emptyList<String>(), exact.rejected)
        assertEquals(emptyList<String>(), exact.review)
        assertEquals(emptySet<String>(), exact.unprovenIdentityScope)
        assertEquals(emptyList<GroceryIntentGroundingIssue>(), exact.issues)
    }

    @Test fun duplicateSourceRowsKeepSeparateIssueIndexesAndIssueRenderingIsRedactedImmutable() {
        val secretSource = "milk-private-synthetic"
        val result = groceryIntentGrounding(secretSource, listOf(
            item("chocolate milk", secretSource), item("chocolate milk", secretSource),
        ))
        val lexicalIssues = result.issues.filter { it.kind == GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED }
        assertEquals(listOf(0, 1), lexicalIssues.map { it.rowIndex })
        assertTrue(lexicalIssues.all { it.sourceText == secretSource })
        assertFalse(lexicalIssues.toString().contains(secretSource))
        assertFalse(lexicalIssues.first().toString().contains(secretSource))

        val mutableView = result.issues as MutableList<GroceryIntentGroundingIssue>
        try {
            mutableView.clear()
            throw AssertionError("Issue collection must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: structured diagnostics are a detached, unmodifiable result.
        }
        assertEquals(2, result.issues.size)
    }

    @Test fun literalReadyMadeTermsPreserveGroundingWhileProtectedFormControlsStillReject() {
        data class Case(
            val id: String,
            val instruction: String,
            val row: GroceryIntentItem,
            val shouldRejectWholeRequest: Boolean = false,
        )
        data class Outcome(
            val id: String,
            val issueKinds: Set<GroceryIntentGroundingIssueKind>,
            val holdKinds: Set<GroceryIntentDraftCheck>,
            val rejectedSources: List<String>,
            val preparationCreated: Boolean,
            val parsedItemCreated: Boolean,
            val parsedIdentityFlag: Boolean?,
            val parsedIdentityUnproven: Boolean,
            val wholeRequestConversionRejected: Boolean,
            val shouldRejectWholeRequest: Boolean,
        )

        val products = listOf("tea", "coffee", "popcorn", "soup", "batter", "dough", "cake")
        val cases = buildList {
            for (product in products) {
                for ((suffix, wording) in listOf("space" to "ready made", "hyphen" to "ready-made")) {
                    val source = "$wording $product"
                    add(Case("literal_${product}_$suffix", source, item(source, source)))
                }
            }
            val sugarFree = "sugar-free ready-made tea"
            add(Case("literal_sugar_free_no_tags", sugarFree, item(sugarFree, sugarFree)))
            add(Case("literal_sugar_free_one_tag", sugarFree,
                item(sugarFree, sugarFree, required = listOf("sugar_free"))))
            add(Case("literal_sugar_free_with_tags", sugarFree,
                item(sugarFree, sugarFree, required = listOf("sugar_free", "ready_to_drink"))))

            for (product in listOf("batter", "dough")) {
                add(Case("added_ready_to_eat_$product", product,
                    item("ready to eat $product", product, required = listOf("ready_to_eat")),
                    shouldRejectWholeRequest = true))
                val readyMadeSource = "ready-made $product"
                add(Case("incorrect_ready_to_eat_tag_$product", readyMadeSource,
                    item(readyMadeSource, readyMadeSource, required = listOf("ready_to_eat")),
                    shouldRejectWholeRequest = true))
            }
            add(Case("added_sugar_free_to_plain_tea", "tea",
                item("sugar-free tea", "tea", required = listOf("sugar_free")), shouldRejectWholeRequest = true))
            add(Case("omitted_sugar_free_tag", "sugar-free tea",
                item("tea", "sugar-free tea"), shouldRejectWholeRequest = true))
            add(Case("omitted_second_product", "sugar-free tea and coffee",
                item("sugar-free tea", "sugar-free tea", required = listOf("sugar_free")),
                shouldRejectWholeRequest = true))
        }

        val outcomes = cases.map { case ->
            val draft = GroceryIntentDraft("grocery-intent-v1", true, case.instruction, listOf(case.row), false)
            val assessment = draft.assessForContextReview()
            val parsed = runCatching { draft.toParsedItems() }.getOrNull()
            Outcome(
                id = case.id,
                issueKinds = assessment.grounding.issues.map { it.kind }.toSet(),
                holdKinds = assessment.holds.map { it.check }.toSet(),
                rejectedSources = assessment.grounding.rejected,
                preparationCreated = assessment.preparation != null,
                parsedItemCreated = parsed?.size == 1,
                parsedIdentityFlag = parsed?.singleOrNull()?.unprovenIdentityScope,
                parsedIdentityUnproven = case.row.sourceText in assessment.grounding.unprovenIdentityScope,
                wholeRequestConversionRejected = runCatching { draft.toParsedItems() }.isFailure,
                shouldRejectWholeRequest = case.shouldRejectWholeRequest,
            )
        }

        val diagnostic = outcomes.joinToString(prefix = "\n", separator = "\n") { outcome ->
            "${outcome.id}: issues=${outcome.issueKinds}, holds=${outcome.holdKinds}, " +
                "rejected=${outcome.rejectedSources.isNotEmpty()}, prepared=${outcome.preparationCreated}, " +
                "parsed=${outcome.parsedItemCreated}, parsedIdentity=${outcome.parsedIdentityFlag}, " +
                "identityUnproven=${outcome.parsedIdentityUnproven}, fullReject=${outcome.wholeRequestConversionRejected}"
        }

        val literalCases = outcomes.filterNot { it.shouldRejectWholeRequest }
        assertEquals("literal row set", products.size * 2 + 3, literalCases.size)
        assertTrue("literal source/name preservation should not acquire identity holds:$diagnostic",
            literalCases.all { outcome ->
                GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED !in outcome.issueKinds &&
                    !outcome.parsedIdentityUnproven && outcome.parsedIdentityFlag == false &&
                    outcome.preparationCreated && outcome.parsedItemCreated && !outcome.wholeRequestConversionRejected
            })

        val negativeControls = outcomes.filter { it.shouldRejectWholeRequest }
        assertEquals("control set", 7, negativeControls.size)
        assertTrue("added protected forms and omitted products must remain rejected:$diagnostic",
            negativeControls.all { it.wholeRequestConversionRejected })
    }
}
