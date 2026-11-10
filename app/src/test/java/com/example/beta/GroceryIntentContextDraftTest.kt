package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon

class GroceryIntentContextDraftTest {
    @Test fun typedRequestContextAndExclusionsCannotWaiveNativeLocalScope() {
        for (source in listOf("दूध नहीं", "500 g दूध नहीं", "2 मिल जाए तो दूध")) {
            val instruction = "Please add $source"
            val response = GroceryIntentContextResponse.parse(body(instruction,
                itemJson(source, null, name = source, quantityUnit = "unspecified", excluded = listOf("milk")),
                listOf(entry("fulfillment", instruction, "Please add"))), instruction)
            val assessment = response.assessForReview()
            assertTrue(assessment is GroceryIntentContextAssessment.Held)
            val held = assessment as GroceryIntentContextAssessment.Held
            assertTrue(held.groundingIssues.any { it.kind == GroceryIntentGroundingIssueKind.SOURCE_SCOPE })
            assertTrue(held.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_GROUNDING })
            assertThrows(IntentNeedsReviewException::class.java) { response.prepareForReview() }
        }
    }

    @Test
    fun hyphenatedNegationCannotBecomeAnUnqualifiedPositiveProduct() {
        val positives = listOf("ready-made tea", "ready-to-eat popcorn", "low-fat milk", "sugar-free biscuits")
        positives.forEach { positive ->
            listOf("non-$positive", "non ${positive.replace('-', ' ')}").forEach { source ->
                assertTrue("Negation must survive alias spelling normalization",
                    propertyHasUnprovedNegation(ProductLexicon.canonicalizeAliasesPreservingText(source)))
                val assessment = GroceryIntentContextResponse.parse(
                    body(source, itemJson(source, null, name = positive, quantityUnit = "unspecified"), emptyList()),
                    source,
                ).assessForReview()
                val issues = when (assessment) {
                    is GroceryIntentContextAssessment.Held -> assessment.blockingIssues
                    is GroceryIntentContextAssessment.Prepared -> assessment.preparation.blockingIssues
                }
                assertTrue("A negated form must retain an identity/property hold, not only an unrelated quantity hold",
                    issues.any { it.kind in setOf(GroceryIntentContextIssueKind.ITEM_GROUNDING,
                        GroceryIntentContextIssueKind.ITEM_IDENTITY, GroceryIntentContextIssueKind.ITEM_REVIEW) })
            }
        }
    }

    @Test
    fun backgroundSignalGroupsPreserveLegacyBooleanAcrossRepresentativeInputs() {
        val matrix = listOf(
            "I went shopping yesterday", "milk", "2 litres", "without sugar", "milk and eggs",
            "", "😀", "please, thanks", "please", "greetings", "could you bring", "le aana",
            "For breakfast and everyday cooking", "to the list for me.", "if available",
            "not two, make it three", "all dairy items low fat", "keep all other items",
            "कृपया दूध लाना", "२ लीटर", "milk\tand\neggs", "\u0000", "\uD800",
            "x".repeat(320), "with ".repeat(64), "Milk!", "ＭＩＬＫ", "1½",
        )
        matrix.forEach { text ->
            val words = ProductLexicon.tokenize(text)
            val legacyLiteral = sourceNominalHasAmountOrRelation(text) || groceryIntentPurchaseScopeContextRanges(text).isNotEmpty() ||
                intentV2HasCorrectionCue(text) || groceryIntentUncertainScope.containsMatchIn(text) ||
                groceryIntentPropertiesIn(text).isNotEmpty() || propertyHasUnprovedNegation(text) ||
                groceryIntentHasNominalClausePredicate(text) || groceryIntentRetailNominalValid(text) ||
                groceryIntentClosedHindiRequestRanges(text).isNotEmpty() ||
                ProductLexicon.knownProductTokens.any { (_, tokens) -> tokens.isNotEmpty() && words.containsAll(tokens) }
            assertEquals("legacy boolean changed for <redacted input>", legacyLiteral,
                groceryIntentContextBackgroundSignals(text).isNotEmpty())
        }
        assertTrue(groceryIntentContextBackgroundSignals("milk").contains(
            GroceryIntentContextBackgroundSignal.KNOWN_PRODUCT_TOKENS))
        assertTrue(groceryIntentContextBackgroundSignals("2 litres").contains(
            GroceryIntentContextBackgroundSignal.NUMBER_OR_RELATION))
        assertEquals(setOf(GroceryIntentContextBackgroundSignal.RETAIL_NOMINAL_SHAPE),
            groceryIntentContextBackgroundSignals("greetings"))
        assertTrue(groceryIntentContextBackgroundSignals("please").isEmpty())
        assertTrue("ordinary coordination is a lexical signal, not quantity proof",
            GroceryIntentContextBackgroundSignal.NUMBER_OR_RELATION in groceryIntentContextBackgroundSignals("and"))
    }

    @Test
    fun neutralRequestShellRoleLabelsDoNotChangeMeaningOfOriginalInstruction() {
        val prefixes = listOf(
            "Please add ",
            "Could you bring ",
            "I would like ",
        )
        val itemSource = "1 litre milk"

        prefixes.forEach { prefix ->
            val instruction = prefix + itemSource
            val itemStart = instruction.indexOf(itemSource)
            val trimmedPrefix = prefix.trimEnd()
            val neutral = neutralGroceryIntentCoverageSpans(instruction, listOf(itemSource))
            assertTrue("whole shell should be independently recognized: $prefix", neutral.any {
                it.startUtf16 <= 0 && it.endUtf16 >= trimmedPrefix.length
            })
            assertTrue(groceryIntentClosedRequestShell(prefix))

            val outcomes = listOf("background", "fulfillment").map { rawKind ->
                val response = GroceryIntentContextResponse.parse(
                    body(instruction,
                        itemJson(itemSource, 1, name = "milk", quantityUnit = "l"),
                        listOf(entry(rawKind, instruction, trimmedPrefix))),
                    instruction,
                )
                // Role proposals remain an immutable record of what the model said;
                // neutral interpretation must be grounded in the original source.
                assertEquals(GroceryIntentContextKind.valueOf(rawKind.uppercase()), response.proposals.single().kind)
                assertEquals(trimmedPrefix, response.proposals.single().source.text)
                assertEquals(0, response.proposals.single().source.startUtf16)
                assertEquals(trimmedPrefix.length, response.proposals.single().source.endUtf16)

                val assessment = response.assessForReview()
                assertTrue("$prefix / $rawKind should prepare: $assessment",
                    assessment is GroceryIntentContextAssessment.Prepared)
                val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
                assertTrue("$prefix / $rawKind: ${preparation.blockingIssues}", preparation.blockingIssues.isEmpty())
                assertEquals(emptyList<String>(), preparation.fulfillmentConstraints)
                assertEquals(GroceryIntentContextKind.BACKGROUND, preparation.proposals.single().kind)
                assertEquals(trimmedPrefix, preparation.proposals.single().source.text)
                assertEquals(0, preparation.proposals.single().source.startUtf16)
                assertEquals(trimmedPrefix.length, preparation.proposals.single().source.endUtf16)
                assertEquals(listOf(itemSource), preparation.itemSources.map { it.text })
                assertEquals(itemStart, preparation.itemSources.single().startUtf16)
                assertEquals(itemStart + itemSource.length, preparation.itemSources.single().endUtf16)
                assertTrue(preparation.disputeBackground(0).blockingIssues.any {
                    it.kind == GroceryIntentContextIssueKind.DISPUTED_BACKGROUND
                })
                val visible = groceryIntentContextReviewRows(preparation, {}, {})
                assertEquals(trimmedPrefix, visible[1].title)
                assertEquals("Used as background, not as an item", visible[1].detail)
                assertEquals("This matters", visible[1].action?.label)
                assertEquals(GroceryIntentContextKind.valueOf(rawKind.uppercase()), response.proposals.single().kind)
                preparation
            }

            assertEquals("same original basket regardless of role label: $prefix",
                outcomes[0].legacyPreparation.items, outcomes[1].legacyPreparation.items)
        }
    }

    @Test
    fun romanizedHindiRequestSuffixesAreNeutralOnlyWhenExactAndUnconditional() {
        data class Example(val source: String, val item: String)
        val lowFatSource = "1 litre low fat milk"
        val examples = listOf(
            Example("1 litre milk", itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l")),
            Example("2 eggs", itemJson("2 eggs", 2, name = "eggs")),
            Example(lowFatSource, itemJson(lowFatSource, 1, name = "low fat milk", quantityUnit = "l",
                required = listOf("low_fat"))),
        )
        val tails = listOf("le aana", "le aao", "la do", "mangwa dijiye", "cart mein daal do")

        examples.forEach { example ->
            tails.forEach { tail ->
                val instruction = "${example.source} $tail"
                val sourceStart = instruction.indexOf(example.source)
                val tailStart = instruction.indexOf(tail, sourceStart + example.source.length)
                assertTrue("the complete request-only suffix must be independently covered: $tail",
                    neutralGroceryIntentCoverageSpans(instruction, listOf(example.source)).any {
                        it.startUtf16 <= tailStart && it.endUtf16 >= tailStart + tail.length
                    })

                val preparations = listOf("background", "fulfillment").map { rawKind ->
                    val response = GroceryIntentContextResponse.parse(
                        body(instruction, example.item, listOf(entry(rawKind, instruction, tail))), instruction,
                    )
                    assertEquals(GroceryIntentContextKind.valueOf(rawKind.uppercase()), response.proposals.single().kind)
                    assertEquals(tail, response.proposals.single().source.text)
                    assertEquals(tailStart, response.proposals.single().source.startUtf16)
                    assertEquals(tailStart + tail.length, response.proposals.single().source.endUtf16)

                    val assessment = response.assessForReview()
                    assertTrue("${example.source} / $tail / $rawKind: $assessment",
                        assessment is GroceryIntentContextAssessment.Prepared)
                    val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
                    assertTrue("${example.source} / $tail: ${preparation.blockingIssues}",
                        preparation.blockingIssues.isEmpty())
                    assertEquals(listOf(example.source), preparation.itemSources.map { it.text })
                    assertEquals(sourceStart, preparation.itemSources.single().startUtf16)
                    assertEquals(sourceStart + example.source.length, preparation.itemSources.single().endUtf16)
                    assertEquals(GroceryIntentContextKind.BACKGROUND, preparation.proposals.single().kind)
                    assertEquals(response.proposals.single().source, preparation.proposals.single().source)
                    assertEquals(GroceryIntentContextKind.valueOf(rawKind.uppercase()), response.proposals.single().kind)
                    assertEquals(emptyList<String>(), preparation.fulfillmentConstraints)
                    assertTrue(preparation.disputeBackground(0).blockingIssues.any {
                        it.kind == GroceryIntentContextIssueKind.DISPUTED_BACKGROUND
                    })
                    if (example.source == lowFatSource) {
                        assertEquals(listOf("low_fat"), response.validatedDraftSnapshot().items.single().required)
                        assertEquals(lowFatSource, preparation.legacyPreparation.items.single().rawText)
                    }
                    preparation.legacyPreparation
                }
                assertEquals("role label must not change quantities or source evidence", preparations[0], preparations[1])
            }
        }
    }

    @Test
    fun closedPurchaseSuffixRequiresWholeUnquotedUnconditionalSyntax() {
        listOf(" le aana ", "\tla do. ", "mangwa dijiye!", "cart mein daal do;").forEach {
            assertTrue(it, groceryIntentClosedPurchaseSuffix(it))
        }
        listOf("do", "please le aana", "jab aasani ho le aana", "likh lo", "likh lijiye",
            "le aana if cheap", "from the shop le aana", "le aana 2", "le aana without sugar",
            "\"le aana\"", "(le aana)", "le aana:", "le aana..", "le aana milk").forEach {
            assertFalse(it, groceryIntentClosedPurchaseSuffix(it))
        }
    }

    @Test
    fun independentlyBoundSuffixDoesNotClearAnotherClauseRequirement() {
        val instruction = "1 litre milk le aana, if cheap"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("1 litre milk", 1, quantityUnit = "l"), listOf(
                entry("fulfillment", instruction, "le aana"),
                entry("unresolved", instruction, "if cheap"),
            )), instruction,
        )
        val assessment = response.assessForReview()
        assertTrue(contextBlockingIssues(assessment).any { it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT })
        assertEquals(GroceryIntentContextKind.FULFILLMENT, response.proposals.first().kind)
        assertTrue(assessment is GroceryIntentContextAssessment.Prepared)
        val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
        assertEquals(GroceryIntentContextKind.BACKGROUND, preparation.proposals.first().kind)
        assertEquals("if cheap", preparation.blockingIssues.single {
            it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT
        }.source?.text)
    }

    @Test
    fun conditionAfterBoundSuffixSurvivesOmittedOrMislabelledRole() {
        val instruction = "1 litre milk le aana, if cheap"
        listOf(null, "background", "fulfillment").forEach { conditionRole ->
            val roles = listOf(entry("fulfillment", instruction, "le aana")) +
                listOfNotNull(conditionRole?.let { entry(it, instruction, "if cheap") })
            val response = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("1 litre milk", 1, quantityUnit = "l"), roles), instruction,
            )
            val issues = contextBlockingIssues(response.assessForReview())
            val expected = when (conditionRole) {
                null -> GroceryIntentContextIssueKind.UNOWNED_SOURCE
                "background" -> GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND
                else -> GroceryIntentContextIssueKind.UNSUPPORTED_FULFILLMENT
            }
            assertTrue("condition must survive missing or wrong role: $conditionRole", issues.any {
                it.kind == expected && it.source?.text == "if cheap"
            })
        }
    }

    @Test
    fun conditionalAndUnresolvedRomanizedHindiSuffixesRemainHeld() {
        val instruction = "1 litre milk jab aasani ho le aana"
        val tail = "jab aasani ho le aana"
        listOf("background", "fulfillment").forEach { rawKind ->
            val response = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"),
                    listOf(entry(rawKind, instruction, tail))), instruction,
            )
            assertTrue("conditional tail cannot be promoted from $rawKind", contextBlockingIssues(response.assessForReview()).any {
                it.kind == GroceryIntentContextIssueKind.UNSUPPORTED_FULFILLMENT ||
                    it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT ||
                    it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND
            })
        }

        val unresolvedInstruction = "1 litre milk le aana"
        val unresolved = GroceryIntentContextResponse.parse(
            body(unresolvedInstruction, itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"),
                listOf(entry("unresolved", unresolvedInstruction, "le aana"))), unresolvedInstruction,
        ).assessForReview()
        assertTrue(contextBlockingIssues(unresolved).any { it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT })
    }

    @Test
    fun unsupportedAndAmbiguousRomanizedHindiTailsRemainHeld() {
        listOf("likh lo", "likh lijiye").forEach { tail ->
            val instruction = "2 eggs $tail"
            val response = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("2 eggs", 2, name = "eggs"),
                    listOf(entry("fulfillment", instruction, tail))), instruction,
            )
            assertTrue("$tail must not be treated as an approved fulfillment", contextBlockingIssues(response.assessForReview()).any {
                it.kind == GroceryIntentContextIssueKind.UNSUPPORTED_FULFILLMENT ||
                    it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND ||
                    it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT
            })
        }

        val instruction = "1 litre milk le aana"
        val conflicting = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"), listOf(
                entry("background", instruction, "le aana"),
                entry("fulfillment", instruction, "le aana"),
            )), instruction,
        ).assessForReview()
        assertTrue(contextBlockingIssues(conflicting).any { it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE })
    }

    @Test
    fun romanizedHindiSuffixQuotesCannotOverrunItemsOrClauses() {
        val dietSource = "1 litre low fat milk"
        val cases = listOf(
            Triple("1 litre milk le aana", itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"), "1 litre milk le"),
            Triple("$dietSource le aana", itemJson(dietSource, 1, name = "low fat milk", quantityUnit = "l",
                required = listOf("low_fat")), "$dietSource le"),
            Triple("1 litre milk le aana if available", itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"), "le aana if available"),
            Triple("1 litre milk; le aana", itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"), "le aana"),
        )
        cases.forEach { (instruction, item, quote) ->
            val response = GroceryIntentContextResponse.parse(
                body(instruction, item, listOf(entry("fulfillment", instruction, quote))), instruction,
            )
            assertTrue("must retain overrun, conditional, or detached tail ($quote)",
                contextBlockingIssues(response.assessForReview()).isNotEmpty())
        }
    }

    @Test
    fun unresolvedAndConditionalBackgroundShellsRemainHeld() {
        val unresolvedInstruction = "Please add 1 litre milk"
        val unresolvedQuote = "Please add"
        val unresolved = GroceryIntentContextResponse.parse(
            body(unresolvedInstruction,
                itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"),
                listOf(entry("unresolved", unresolvedInstruction, unresolvedQuote))),
            unresolvedInstruction,
        )
        val unresolvedAssessment = unresolved.assessForReview()
        assertTrue(contextBlockingIssues(unresolvedAssessment).any {
            it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT
        })

        val conditionalInstruction = "Please add 1 litre milk if available"
        val itemSource = "1 litre milk"
        val conditional = "if available"
        val neutral = neutralGroceryIntentCoverageSpans(conditionalInstruction, listOf(itemSource))
        val conditionalStart = conditionalInstruction.indexOf(conditional)
        assertFalse("conditional shell must not be independently neutral", neutral.any {
            it.startUtf16 <= conditionalStart && it.endUtf16 >= conditionalStart + conditional.length
        })
        val response = GroceryIntentContextResponse.parse(
            body(conditionalInstruction,
                itemJson(itemSource, 1, name = "milk", quantityUnit = "l"),
                listOf(entry("background", conditionalInstruction, conditional))),
            conditionalInstruction,
        )
        val assessment = response.assessForReview()
        assertTrue(contextBlockingIssues(assessment).any {
            it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND ||
                it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT
        })

        val mixedInstruction = "Please add 1 litre milk"
        val mixedQuote = "Please add"
        val mixed = GroceryIntentContextResponse.parse(
            body(mixedInstruction,
                itemJson("1 litre milk", 1, name = "milk", quantityUnit = "l"),
                listOf(entry("background", mixedInstruction, mixedQuote),
                    entry("fulfillment", mixedInstruction, mixedQuote))),
            mixedInstruction,
        )
        val mixedAssessment = mixed.assessForReview()
        assertTrue(contextBlockingIssues(mixedAssessment).any {
            it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE
        })
    }

    private fun contextBlockingIssues(assessment: GroceryIntentContextAssessment): List<GroceryIntentContextIssue> =
        when (assessment) {
            is GroceryIntentContextAssessment.Held -> assessment.blockingIssues
            is GroceryIntentContextAssessment.Prepared -> assessment.preparation.blockingIssues
        }

    @Test
    fun closedRequestEvidenceDoesNotPromoteEditorialPurposeAgeTimingOrSourceCoverage() {
        val prefixes = listOf(
            "My shopping list: ", "Please note my kitchen list: ",
            "I am 65 years old. ", "When you have a moment please add ",
            "For my diabetic mother, ", "Please don't add ", "From the local shop, ",
        )
        for (prefix in prefixes) {
            val instruction = prefix + "1 litre milk"
            assertFalse("not request-only syntax: $prefix", groceryIntentClosedRequestShell(prefix))
            val response = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("1 litre milk", 1, quantityUnit = "l"),
                    listOf(entry("fulfillment", instruction, prefix.trimEnd()))), instruction,
            )
            val assessment = response.assessForReview()
            val retained = when (assessment) {
                is GroceryIntentContextAssessment.Held -> assessment.proposals
                is GroceryIntentContextAssessment.Prepared -> assessment.preparation.proposals
            }
            assertEquals("broader coverage cannot turn this into background: $prefix",
                GroceryIntentContextKind.FULFILLMENT, retained.single().kind)
            assertEquals(response.proposals.single().source, retained.single().source)
        }
    }

    @Test
    fun requestShellCannotCoverOmittedAmountsItemsOrConditions() {
        data class Example(val instruction: String, val itemSource: String, val quote: String, val quantity: Int?)
        val examples = listOf(
            Example("Please add 1 litre milk", "milk", "Please add", null),
            Example("Please add 1 litre milk", "1 litre milk", "Please add 1 litre milk", 1),
            Example("If available, please add 1 litre milk", "1 litre milk", "please add", 1),
            Example("Please add kuttu flour and 1 litre milk", "1 litre milk", "Please add kuttu flour and", 1),
        )
        for (example in examples) {
            val response = GroceryIntentContextResponse.parse(
                body(example.instruction,
                    itemJson(example.itemSource, example.quantity,
                        quantityUnit = if (example.quantity == null) "unspecified" else "l"),
                    listOf(entry("fulfillment", example.instruction, example.quote))), example.instruction,
            )
            assertTrue("whole input must remain held: ${example.instruction}",
                contextBlockingIssues(response.assessForReview()).isNotEmpty())
        }
    }

    @Test
    fun closedRequestSubquotesKeepTheirExactBoundaryAndCannotOverrunIntoAnItem() {
        val instruction = "Could you bring 1 litre milk"
        fun assess(quote: String) = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("1 litre milk", 1, quantityUnit = "l"),
                listOf(entry("fulfillment", instruction, quote))), instruction,
        ).assessForReview()

        val contained = assess("bring") as GroceryIntentContextAssessment.Prepared
        assertTrue(contained.preparation.blockingIssues.isEmpty())
        val interpreted = contained.preparation.proposals.single()
        assertEquals(GroceryIntentContextKind.BACKGROUND, interpreted.kind)
        assertEquals("bring", interpreted.source.text)
        assertEquals(instruction.indexOf("bring"), interpreted.source.startUtf16)
        assertEquals(instruction.indexOf("bring") + 5, interpreted.source.endUtf16)

        val overrun = assess("bring 1")
        assertTrue(contextBlockingIssues(overrun).any {
            it.kind == GroceryIntentContextIssueKind.UNSUPPORTED_FULFILLMENT
        })
        val retained = when (overrun) {
            is GroceryIntentContextAssessment.Held -> overrun.proposals
            is GroceryIntentContextAssessment.Prepared -> overrun.preparation.proposals
        }
        assertEquals(GroceryIntentContextKind.FULFILLMENT, retained.single().kind)
        assertEquals("bring 1", retained.single().source.text)
    }

    @Test
    fun closedRequestClassificationCannotClearItemHoldsOrRewriteTheirRawDiagnosticEvidence() {
        val instruction = "Please add 1 litre milk"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("1 litre milk", 2, quantityUnit = "l"),
                listOf(entry("fulfillment", instruction, "Please add"))), instruction,
        )
        val held = response.assessForReview() as GroceryIntentContextAssessment.Held
        assertTrue(held.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_NUMERIC })
        assertFalse(held.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.UNSUPPORTED_FULFILLMENT })
        assertEquals(instruction, held.draft.instruction)
        assertEquals(response.proposals, held.proposals)
        assertEquals(GroceryIntentContextKind.FULFILLMENT, held.proposals.single().kind)
        assertEquals(setOf(0), held.unevaluatedItemRows)

        val conflicting = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("1 litre milk", 1, quantityUnit = "l"), listOf(
                entry("background", instruction, "Please"),
                entry("fulfillment", instruction, "Please add"),
            )), instruction,
        ).assessForReview()
        assertTrue(contextBlockingIssues(conflicting).any { it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE })
    }

    @Test
    fun structuralCompositionAcceptanceStillRequiresSemanticAndScopeReviewAcrossContracts() {
        val base = "two 250 g tomato ketchup packets without added sugar"
        data class Case(val instruction: String, val requireSemanticHold: Boolean = true)
        val cases = listOf(
            // The backend's new acceptance is a structural result only; this
            // baseline is diagnostic and is not required to be held.
            Case(base, requireSemanticHold = false),
            Case("$base, if cheap"),
            Case("$base or oat biscuits"),
            Case("for each household, $base"),
            // These are whole-item controls: retain the alternative/condition in
            // the exact item evidence and require the ordinary review pipeline to hold.
            Case("two 250 g tomato ketchup packets with or without added sugar"),
            Case("two 250 g tomato ketchup packets without added sugar if available"),
        )
        val semanticOrScopeKinds = setOf(
            GroceryIntentContextIssueKind.UNOWNED_SOURCE,
            GroceryIntentContextIssueKind.ITEM_REVIEW,
            GroceryIntentContextIssueKind.ITEM_IDENTITY,
            GroceryIntentContextIssueKind.ITEM_GROUNDING,
        )

        cases.forEach { case ->
            val instruction = case.instruction
            val row = itemJson(
                source = instruction,
                quantity = 2,
                name = "tomato ketchup",
                packValue = "250",
                packUnit = "g",
                excluded = listOf("added sugar"),
            )
            val legacyResponse = GroceryIntentResponse.parse(
                body(instruction, row, emptyList(), contract = "grocery-intent-v1", includeContext = false),
                instruction,
            )
            val legacyAssessment = legacyResponse.draft.assessForContextReview()
            assertFalse("$instruction: must not be a numeric ownership hold",
                legacyAssessment.holds.any { it.check == GroceryIntentDraftCheck.NUMERIC_OWNERSHIP })
            if (legacyAssessment.preparation == null) {
                if (case.requireSemanticHold) {
                    assertTrue("$instruction: expected semantic/scope grounding hold",
                        legacyAssessment.holds.any { it.check == GroceryIntentDraftCheck.GROUNDING })
                }
                assertThrows(IntentNeedsReviewException::class.java) {
                    legacyResponse.draft.prepareForReview()
                }
            } else {
                val strict = legacyResponse.draft.prepareForReview()
                if (case.requireSemanticHold) {
                    assertTrue("$instruction: must retain unresolved or item-review evidence",
                        strict.unresolvedText.isNotEmpty() || strict.itemReviewConcerns.isNotEmpty())
                }
            }

            val contextResponse = GroceryIntentContextResponse.parse(body(instruction, row, emptyList()), instruction)
            when (val assessment = contextResponse.assessForReview()) {
                is GroceryIntentContextAssessment.Held -> {
                    if (case.requireSemanticHold) assertTrue(
                        "$instruction: held context must retain semantic/scope reason",
                        assessment.blockingIssues.any { it.kind in semanticOrScopeKinds },
                    )
                    assertFalse("$instruction: numeric composition must not be the blocker",
                        assessment.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_NUMERIC })
                    assertThrows(IntentNeedsReviewException::class.java) { contextResponse.prepareForReview() }
                }
                is GroceryIntentContextAssessment.Prepared -> {
                    if (case.requireSemanticHold) assertTrue(
                        "$instruction: prepared draft must still have a semantic/scope block",
                        assessment.preparation.blockingIssues.any { it.kind in semanticOrScopeKinds },
                    )
                    assertFalse("$instruction: numeric composition must not be the blocker",
                        assessment.preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_NUMERIC })
                    val parsedItem = assessment.preparation.legacyPreparation.items.single()
                    val candidate = SwiggyMcpClient.RecommendationCandidate(
                        spinId = "synthetic-ketchup", skuId = "synthetic-ketchup", label = "tomato ketchup 250 g packet",
                    )
                    val catalogue = SwiggyMcpClient.Recommendations(
                        candidates = listOf(candidate), suggested = candidate,
                        query = swiggyRecommendationQuery(parsedItem),
                    )
                    val basket = GroceryIntentContextBasket.create(assessment.preparation, listOf(catalogue))
                    if (case.requireSemanticHold) assertEquals(null, basket)
                }
            }
        }
    }

    @Test
    fun backendStructuralAcceptanceCannotChangeThePhysicalCartTotal() {
        val previewedUnits = mutableSetOf<String>()
        listOf(
            Triple("2 kg rice", "rice", "kg"),
            Triple("2 kg rice in total", "rice", "kg"),
            Triple("2 l milk", "milk", "l"),
            Triple("2 l milk in total", "milk", "l"),
        ).forEach { (instruction, name, unit) ->
            val row = itemJson(instruction, null, name = name, quantityUnit = "unspecified",
                packValue = "2", packUnit = unit)
            val assessment = GroceryIntentContextResponse.parse(
                body(instruction, row, emptyList()), instruction,
            ).assessForReview()
            if (assessment is GroceryIntentContextAssessment.Held) {
                assertTrue(assessment.blockingIssues.isNotEmpty())
                return@forEach
            }
            val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
            val candidates = listOf(1, 2, 3).map { size ->
                SwiggyMcpClient.RecommendationCandidate(spinId = "synthetic-$size", skuId = "synthetic-$size",
                    label = "$name $size $unit")
            }
            val catalogue = SwiggyMcpClient.Recommendations(candidates = candidates, suggested = candidates[1],
                query = swiggyRecommendationQuery(preparation.legacyPreparation.items.single()))
            val basket = GroceryIntentContextBasket.create(preparation, listOf(catalogue))
            if (preparation.blockingIssues.isNotEmpty()) {
                assertEquals(null, basket)
                return@forEach
            }
            assertNotNull(basket)
            previewedUnits += unit
            assertEquals(null, basket!!.preview(0, candidates[0]))
            assertEquals(null, basket.preview(0, candidates[2]))
            // A strict two-unit-size offer may resolve to ONE cart unit, or be
            // held; backend shape acceptance must not silently alter the total.
            basket.preview(0, candidates[1])?.let { assertEquals(1, it.quantity) }
        }
        // Ensure both mass and volume cases reached the catalogue-preview checks.
        assertEquals(setOf("kg", "l"), previewedUnits)
    }

    @Test
    fun heldAssessmentKeepsAllRowsAndIndependentCheckClassesWithoutParsingAnyRow() {
        val instruction = "2 biskits; 5 eggs; garlic"
        val rows = listOf(
            itemJson("2 biskits", 2, name = "biscuits"),
            itemJson("5 eggs", 2, name = "eggs"),
            itemJson("garlic", null, name = "garlic", quantityUnit = "unspecified", needsClarification = true),
        )
        val response = GroceryIntentContextResponse.parse(
            body(instruction, rows.joinToString(","), emptyList(), modelNeedsClarification = true), instruction,
        )

        val assessment = response.assessForReview()
        assertTrue(assessment is GroceryIntentContextAssessment.Held)
        val held = assessment as GroceryIntentContextAssessment.Held
        assertEquals(rows.size, held.draft.items.size)
        assertEquals(listOf("2 biskits", "5 eggs", "garlic"), held.draft.items.map { it.sourceText })
        assertEquals(listOf("2 biskits", "5 eggs", "garlic"), held.itemSources.map { it.text })
        assertEquals(setOf(0, 1, 2), held.unevaluatedItemRows)
        assertTrue(held.draftHolds.any { it.check == GroceryIntentDraftCheck.GROUNDING })
        assertTrue(held.draftHolds.any { it.check == GroceryIntentDraftCheck.NUMERIC_OWNERSHIP })
        assertTrue(held.draftHolds.any { it.check == GroceryIntentDraftCheck.MODEL_CLARIFICATION })
        assertTrue(held.groundingIssues.any { it.kind == GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED })
        assertTrue(held.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_GROUNDING })
        assertTrue(held.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_NUMERIC })
        assertTrue(held.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_CLARIFICATION })
        assertTrue(GroceryIntentContextAssessment.Held::class.java.declaredFields.none {
            ParsedItem::class.java.isAssignableFrom(it.type) || GroceryIntentPendingCountWitness::class.java.isAssignableFrom(it.type) ||
                it.name in setOf("preparation", "pendingCounts")
        })
        assertThrows(IntentNeedsReviewException::class.java) { response.prepareForReview() }.also {
            assertTrue(it.affectedSourceText.contains("2 biskits"))
        }
    }

    @Test
    fun lexicalOnlyGarlicToGingerRemainsHeldWithoutCatalogueAliasWaiver() {
        val instruction = "garlic"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson(instruction, null, name = "ginger", quantityUnit = "unspecified"), emptyList()),
            instruction,
        )
        val held = response.assessForReview() as GroceryIntentContextAssessment.Held
        assertEquals(listOf(instruction), held.draft.items.map { it.sourceText })
        assertTrue(held.groundingIssues.any { it.kind == GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED })
        assertEquals(setOf(0), held.unevaluatedItemRows)
    }

    @Test
    fun preparedContextProjectionMatchesLegacyPreparationWhenNoHoldExists() {
        val instruction = "2 milk"
        val item = itemJson(instruction, 2)
        val legacy = GroceryIntentResponse.parse(
            body(instruction, item, emptyList(), contract = "grocery-intent-v1", includeContext = false), instruction,
        ).draft.prepareForReview()
        val context = GroceryIntentContextResponse.parse(body(instruction, item, emptyList()), instruction)
            .assessForReview() as GroceryIntentContextAssessment.Prepared

        assertEquals(legacy.items, context.preparation.legacyPreparation.items)
        assertEquals(legacy.unresolvedText, context.preparation.legacyPreparation.unresolvedText)
        assertEquals(legacy.excludedText, context.preparation.legacyPreparation.excludedText)
        assertEquals(legacy.itemReviewConcerns, context.preparation.legacyPreparation.itemReviewConcerns)
    }

    @Test
    fun terminalPropertyScopeFailureIsMarkedUnevaluatedAndHeldCollectionsAreImmutableAndRedacted() {
        val instruction = "2 milk, not organic; 1 bread"
        val rows = listOf(
            itemJson("2 milk, not organic", 2, name = "milk", excluded = listOf("organic")),
            itemJson("1 bread", 1, name = "bread", needsClarification = true),
        )
        val response = GroceryIntentContextResponse.parse(
            body(instruction, rows.joinToString(","), emptyList(), modelNeedsClarification = true), instruction,
        )
        val assessment = response.assessForReview() as GroceryIntentContextAssessment.Held

        assertEquals(setOf(0), assessment.unevaluatedPropertyRows)
        assertEquals(setOf(0, 1), assessment.unevaluatedItemRows)
        assertThrows(UnsupportedOperationException::class.java) { (assessment.itemSources as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (assessment.draftHolds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (assessment.groundingIssues as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (assessment.unevaluatedPropertyRows as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (assessment.unevaluatedItemRows as MutableSet).clear() }
        assertFalse(assessment.toString().contains("2 milk"))
        assertFalse(assessment.draftHolds.toString().contains("not organic"))
        assertFalse(assessment.groundingIssues.toString().contains("not organic"))
    }

    @Test
    fun malformedOrCroppedItemSourceThrowsInsteadOfBecomingHeld() {
        val instruction = "unavailable; milk"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("available", null, name = "milk", quantityUnit = "unspecified"), emptyList()),
            instruction,
        )
        assertThrows(RuntimeException::class.java) { response.assessForReview() }
        assertThrows(RuntimeException::class.java) { response.prepareForReview() }
    }

    @Test
    fun contextCoverageReusesOriginalNeutralCompilerWithoutDiscardingOtherWords() {
        val source = "2 eggs"
        val instruction = "list mein likh lo: $source"
        assertTrue(uncoveredGroceryIntentSegments(instruction, listOf(source)).isEmpty())
        val prepared = GroceryIntentContextResponse.parse(body(instruction,
            itemJson(source, 2, "eggs"), emptyList()), instruction).prepareForReview()
        assertTrue(prepared.unownedRanges.isEmpty())
        assertFalse(prepared.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.UNOWNED_SOURCE })

        for (prefix in listOf("organic samaan likh rahi hoon", "milk likh raha hoon", "at most")) {
            val original = "$prefix: $source"
            val held = GroceryIntentContextResponse.parse(body(original,
                itemJson(source, 2, "eggs"), emptyList()), original).prepareForReview()
            assertTrue(prefix, held.unownedRanges.isNotEmpty())
        }
    }

    @Test
    fun contextContractIsNegotiatedSeparatelyFromLegacyV1() {
        val instruction = "2 milk"
        val item = itemJson(instruction, quantity = 2)
        val contextBody = body(instruction, item, listOf(entry("background", instruction, instruction)))
        val legacyBody = body(instruction, item, emptyList(), contract = "grocery-intent-v1", includeContext = false)

        val parsed = GroceryIntentContextResponse.parse(contextBody, instruction)
        assertEquals(instruction, parsed.prepareForReview().instruction)
        assertThrows(RuntimeException::class.java) { GroceryIntentResponse.parse(contextBody, instruction) }
        assertThrows(RuntimeException::class.java) { GroceryIntentContextResponse.parse(legacyBody, instruction) }
    }

    @Test
    fun exactUniqueUtf16QuoteOffsetsSupportEmojiAndHindi() {
        val instruction = "🛒 घर में 2 दूध; दुकान बंद है 😀"
        val quote = "दुकान बंद है 😀"
        val start = instruction.indexOf(quote)
        val context = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 दूध", quantity = 2), listOf(entry("unresolved", instruction, quote))),
            instruction,
        )

        assertEquals(start, context.proposals.single().source.startUtf16)
        assertEquals(start + quote.length, context.proposals.single().source.endUtf16)
        assertTrue(start > instruction.codePointCount(0, start))
    }

    @Test
    fun boundItemSourcesRemainExactForSpellingAndNativeTranslationEvenWhenPreparationHolds() {
        val cases = listOf(
            Triple("milk", "milk", true),
            Triple("चावल", "rice", false),
        )
        cases.forEach { (source, name, needsClarification) ->
            val instruction = "😀 $source"
            val response = GroceryIntentContextResponse.parse(
                body(instruction, itemJson(source, null, name = name, quantityUnit = "unspecified",
                    needsClarification = needsClarification), emptyList(),
                    modelNeedsClarification = needsClarification),
                instruction,
            )
            val bound = response.boundItemSources().single()
            val start = instruction.indexOf(source)
            assertEquals(source, bound.text)
            assertEquals(start, bound.startUtf16)
            assertEquals(start + source.length, bound.endUtf16)
            assertThrows(IntentNeedsReviewException::class.java) { response.prepareForReview() }
        }
    }

    @Test
    fun boundItemSourcesRejectCroppedRepeatedCombiningAndSurrogateSplitQuotes() {
        val emoji = "\uD83D\uDE00"
        val highSurrogate = String(charArrayOf('\uD83D'))
        val cases = listOf(
            "unavailable; milk" to "available",
            "milk and milk" to "milk",
            "cafe\u0301; milk" to "e",
            "$emoji milk" to highSurrogate,
        )
        cases.forEach { (instruction, source) ->
            val response = GroceryIntentContextResponse.parse(
                body(instruction, itemJson(source, null, name = "item", quantityUnit = "unspecified"), emptyList()),
                instruction,
            )
            assertThrows("source length=${source.length}", RuntimeException::class.java) {
                response.boundItemSources()
            }
        }
    }

    @Test
    fun boundSourcesAndStructuralConflictsAreImmutableRedactedAndDoNotChangeDraftSnapshot() {
        val instruction = "PRIVATE-SYNTHETIC 2 milk"
        val items = listOf(
            itemJson("2 milk", 2),
            itemJson("milk", null, name = "milk", quantityUnit = "unspecified"),
        ).joinToString(",")
        val response = GroceryIntentContextResponse.parse(
            body(instruction, items, listOf(entry("background", instruction, instruction))), instruction,
        )
        val snapshot = response.validatedDraftSnapshot()
        val bound = response.boundItemSources()
        assertEquals(listOf("2 milk", "milk"), bound.map { it.text })
        assertEquals(2, response.sourceConflictIssues().count {
            it.kind == GroceryIntentContextIssueKind.ITEM_SOURCE_OVERLAP
        })
        assertEquals(1, response.sourceConflictIssues().count {
            it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE
        })
        assertThrows(UnsupportedOperationException::class.java) { (bound as MutableList).clear() }
        val conflicts = response.sourceConflictIssues()
        assertThrows(UnsupportedOperationException::class.java) { (conflicts as MutableList).clear() }
        assertFalse(conflicts.toString().contains("PRIVATE-SYNTHETIC"))
        assertFalse(conflicts.first().toString().contains("PRIVATE-SYNTHETIC"))
        assertEquals(snapshot, response.validatedDraftSnapshot())
    }

    @Test
    fun contextQuoteLimitCountsUnicodeCodePointsRatherThanUtf16Units() {
        val quote = "😀".repeat(161) + "x".repeat(159)
        assertEquals(320, quote.codePointCount(0, quote.length))
        assertTrue(quote.length > 320)
        val instruction = "$quote; 1 milk"
        val parsed = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("1 milk", quantity = 1), listOf(entry("background", instruction, quote))),
            instruction,
        )
        assertEquals(quote.length, parsed.proposals.single().source.endUtf16)
    }

    @Test
    fun explicitEditMustBeFollowedByFullReparseAndDoesNotClearOtherRowHolds() {
        val original = "1 garlic; 2 milk without peanuts; if available"
        val start = original.indexOf("garlic")
        val revision = GroceryIntentRequestRevision.begin(original)
        val edited = requireNotNull(revision.replace(revision,
            GroceryIntentReviewV2Quote("garlic", start, start + "garlic".length), "ginger"))

        val priorResponse = body(original, itemJson("1 garlic", 1, name = "garlic"), emptyList())
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(priorResponse, edited.instruction)
        }

        val rows = listOf(
            itemJson("1 ginger", 1, name = "ginger"),
            itemJson("2 milk without peanuts", 1, name = "milk", excluded = listOf("peanuts")),
        ).joinToString(",")
        val fresh = GroceryIntentContextResponse.parse(
            body(edited.instruction, rows, listOf(entry("unresolved", edited.instruction, "if available"))),
            edited.instruction,
        )
        val assessment = fresh.assessForReview() as GroceryIntentContextAssessment.Held

        assertEquals(edited.instruction, assessment.draft.instruction)
        assertEquals(listOf("1 ginger", "2 milk without peanuts"), assessment.draft.items.map { it.sourceText })
        assertTrue(assessment.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_NUMERIC })
        assertTrue(assessment.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT })
        assertTrue(assessment.draft.instruction.contains("without peanuts"))
        assertTrue(assessment.draft.instruction.contains("if available"))
    }

    @Test
    fun explicitEditCanPassOnlyAfterFreshResponseValidatesTheWholeRevisedRequest() {
        val original = "1 garlic; 2 milk"
        val start = original.indexOf("garlic")
        val root = GroceryIntentRequestRevision.begin(original)
        val edited = requireNotNull(root.replace(root,
            GroceryIntentReviewV2Quote("garlic", start, start + "garlic".length), "ginger"))

        val rows = listOf(
            itemJson("1 ginger", 1, name = "ginger"),
            itemJson("2 milk", 2, name = "milk"),
        ).joinToString(",")
        val freshResponse = GroceryIntentContextResponse.parse(
            body(edited.instruction, rows, emptyList()), edited.instruction,
        )
        val assessment = freshResponse.assessForReview()

        assertTrue(assessment is GroceryIntentContextAssessment.Prepared)
        val prepared = (assessment as GroceryIntentContextAssessment.Prepared).preparation
        assertEquals(edited.instruction, prepared.instruction)
        assertTrue(prepared.blockingIssues.isEmpty())
        assertEquals(listOf("1 ginger", "2 milk"), prepared.legacyPreparation.items.map { it.rawText })
    }

    @Test
    fun rejectsWrongOffsetsCroppedWordsRepeatedQuotesAndInvalidSourceTypes() {
        val cases = listOf(
            "wrong offset" to Triple("not available; 1 milk", "not available", "start_plus_one"),
            "cropped word" to Triple("unavailable; 1 milk", "available", "correct"),
            "repeated quote" to Triple("not available, not available; 1 milk", "not available", "correct"),
            "invalid offset type" to Triple("if available; 1 milk", "if available", "string_offset"),
        )
        cases.forEach { (label, case) ->
            val (instruction, quote, offsetMode) = case
            val source = entry("background", instruction, quote, offsetMode)
            assertThrows(label, RuntimeException::class.java) {
                GroceryIntentContextResponse.parse(
                    body(instruction, itemJson("1 milk", quantity = 1), listOf(source)), instruction,
                )
            }
        }
        val invalidKind = """{"kind":7,"source":{"text":"if available","start_utf16":0,"end_utf16":13}}"""
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(
                body("if available; 1 milk", itemJson("1 milk", quantity = 1), listOf(invalidKind)),
                "if available; 1 milk",
            )
        }
        val invalidText = """{"kind":"background","source":{"text":7,"start_utf16":0,"end_utf16":13}}"""
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(
                body("if available; 1 milk", itemJson("1 milk", quantity = 1), listOf(invalidText)),
                "if available; 1 milk",
            )
        }
    }

    @Test
    fun rejectsDuplicateKeysExtraAuthorityFieldsAndChangedInstruction() {
        val instruction = "if available; 1 milk"
        val duplicate = """{"kind":"background","kind":"unresolved","source":{"text":"if available","start_utf16":0,"end_utf16":13}}"""
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("1 milk", 1), listOf(duplicate)), instruction)
        }

        val authority = """{"kind":"background","quantity":1,"source":{"text":"if available","start_utf16":0,"end_utf16":13}}"""
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("1 milk", 1), listOf(authority)), instruction)
        }
        val extraDraftField = body(instruction, itemJson("1 milk", 1), emptyList()).replace(
            "\"model_needs_clarification\":false,\"context\":[]",
            "\"model_needs_clarification\":false,\"context\":[],\"cart_action\":\"add\"",
        )
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(extraDraftField, instruction)
        }
        val changed = body(instruction, itemJson("1 milk", 1), emptyList()).replace(instruction, "1 milk")
        assertThrows(RuntimeException::class.java) { GroceryIntentContextResponse.parse(changed, instruction) }
    }

    @Test
    fun acceptsFiftyContextEntriesAndRejectsFiftyOne() {
        fun fixture(size: Int): Pair<String, List<String>> {
            val quotes = (0 until size).map { "note$it" }
            val instruction = (quotes + "1 milk").joinToString(" ")
            return instruction to quotes.map { entry("background", instruction, it) }
        }

        val (fiftyInstruction, fifty) = fixture(50)
        assertEquals(50, GroceryIntentContextResponse.parse(
            body(fiftyInstruction, itemJson("1 milk", 1), fifty), fiftyInstruction,
        ).proposals.size)

        val (fiftyOneInstruction, fiftyOne) = fixture(51)
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(
                body(fiftyOneInstruction, itemJson("1 milk", 1), fiftyOne), fiftyOneInstruction,
            )
        }
    }

    @Test
    fun overlappingContextRolesArePreservedAndDoNotClearLegacyUnresolvedText() {
        val instruction = "not available: 2 milk"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), listOf(
                entry("background", instruction, "not available"),
                entry("fulfillment", instruction, "available"),
            )), instruction,
        )

        val preparation = response.prepareForReview()
        assertEquals(listOf(GroceryIntentContextKind.BACKGROUND, GroceryIntentContextKind.FULFILLMENT),
            preparation.proposals.map { it.kind })
        assertTrue("not available" in preparation.legacyPreparation.unresolvedText.joinToString(" "))
        assertTrue(preparation.unownedRanges.isEmpty())
    }

    @Test
    fun emptyContextDoesNotProveCoverageAndWrongQuantityStillThrowsAtPreparation() {
        val instruction = "not available: 2 milk"
        val empty = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), emptyList()), instruction,
        ).prepareForReview()
        assertTrue(empty.unownedRanges.any {
            instruction.substring(it.startUtf16, it.endUtf16).contains("not available")
        })
        assertTrue(empty.legacyPreparation.unresolvedText.isNotEmpty())

        val wrongQuantityInstruction = "six eggs"
        val wrongQuantity = GroceryIntentContextResponse.parse(
            body(wrongQuantityInstruction, itemJson("six eggs", 5, name = "eggs"), emptyList()),
            wrongQuantityInstruction,
        )
        assertThrows(IntentNeedsReviewException::class.java) { wrongQuantity.prepareForReview() }
    }

    @Test
    fun responsePreparationAndProposalStringFormsRedactSourceText() {
        val instruction = "PRIVATE not available; 2 milk"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), listOf(entry("background", instruction, "PRIVATE not available"))),
            instruction,
        )
        val preparation = response.prepareForReview()
        listOf(response.toString(), preparation.toString(), preparation.proposals.single().toString()).forEach {
            assertFalse(it.contains("PRIVATE"))
            assertFalse(it.contains(instruction))
        }
        assertNotNull(preparation.legacyPreparation)
    }

    @Test
    fun duplicateRoleAndNonIntegerOffsetsCannotBeAccepted() {
        val instruction = "meeting; 2 milk"
        val proposal = entry("background", instruction, "meeting")
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2), listOf(proposal, proposal)), instruction)
        }
        listOf("true", "0.0", "0.5", "2147483648").forEach { offset ->
            val changed = proposal.replace("\"start_utf16\":0", "\"start_utf16\":$offset")
            assertThrows(RuntimeException::class.java) {
                GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2), listOf(changed)), instruction)
            }
        }
    }

    @Test
    fun unicodeAndInstructionValidationRejectsInvalidSourcesEvenWithEmptyContext() {
        listOf("\uD800; 2 milk", "\uDC00; 2 milk", "\u0000; 2 milk", "x".repeat(6001) + "; 2 milk").forEach { instruction ->
            assertThrows(RuntimeException::class.java) {
                GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2), emptyList()), instruction)
            }
        }
        val quote = "x".repeat(321)
        val instruction = "$quote; 2 milk"
        assertThrows(RuntimeException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2),
                listOf(entry("background", instruction, quote))), instruction)
        }
    }

    @Test
    fun existingWholeInstructionUtf16LimitFailsClosedRatherThanCropping() {
        val instruction = "😀".repeat(3000) + "; 2 milk"
        assertTrue(instruction.codePointCount(0, instruction.length) < 6000)
        val response = GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2), emptyList()), instruction)
        assertThrows(RuntimeException::class.java) { response.prepareForReview() }
    }

    @Test
    fun proposalsAndPendingWitnessMapCannotBeMutatedOrUsedToClearHolds() {
        val instruction = "For the meeting get 2 milk"
        val response = GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2),
            listOf(entry("background", instruction, "For the meeting get"))), instruction)
        val prepared = response.prepareForReview()
        assertEquals(1, prepared.pendingCounts.size)
        assertTrue(prepared.pendingCounts.getValue(0).matches(instruction, 0, prepared.legacyPreparation.items.single()))
        assertThrows(UnsupportedOperationException::class.java) {
            (response.proposals as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (prepared.pendingCounts as MutableMap).clear()
        }
        assertTrue(prepared.legacyPreparation.unresolvedText.isNotEmpty())
        assertEquals(null, prepared.legacyPreparation.items.single().intentQuantityProvenance)
    }

    @Test
    fun backgroundEnclosingAnItemSourceIsRetainedAsConflictingAndDisputeDoesNotEraseIt() {
        val instruction = "2 milk for the meeting"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), listOf(entry("background", instruction, instruction))),
            instruction,
        )

        val prepared = response.prepareForReview()
        assertTrue(prepared.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE })
        assertTrue(prepared.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND })
        val disputed = prepared.disputeBackground(0)
        assertTrue(disputed.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.DISPUTED_BACKGROUND })
        assertTrue(disputed.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE })
        assertTrue(disputed.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND })
        assertEquals(instruction, disputed.proposals.single().source.text)
        assertEquals(prepared.legacyPreparation.unresolvedText, disputed.legacyPreparation.unresolvedText)
    }

    @Test
    fun overlappingBackgroundAndUnresolvedRolesHoldInEitherProposalOrder() {
        val instruction = "2 milk; note"
        val background = entry("background", instruction, "2 milk")
        val unresolved = entry("unresolved", instruction, instruction)

        listOf(listOf(background, unresolved), listOf(unresolved, background)).forEach { roles ->
            val preparation = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("2 milk", 2), roles), instruction,
            ).prepareForReview()
            assertTrue(preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE })
            assertTrue(preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT })
            assertEquals(setOf(GroceryIntentContextKind.BACKGROUND, GroceryIntentContextKind.UNRESOLVED),
                preparation.proposals.map { it.kind }.toSet())
        }
    }

    @Test
    fun unownedPhraseRemainsVisibleWhenContextDoesNotClaimIt() {
        val instruction = "2 milk after lunch"
        val preparation = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), emptyList()), instruction,
        ).prepareForReview()

        assertTrue(preparation.unownedRanges.any {
            instruction.substring(it.startUtf16, it.endUtf16).contains("after lunch")
        })
        assertTrue(preparation.legacyPreparation.unresolvedText.any { it.contains("after lunch") })
    }

    @Test
    fun numericNegationConditionAndProductLabelsCannotBeDisposedAsBackground() {
        listOf("3 eggs", "no salt", "if available", "bread").forEach { phrase ->
            val instruction = "2 milk; $phrase"
            val preparation = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("2 milk", 2), listOf(entry("background", instruction, phrase))),
                instruction,
            ).prepareForReview()
            assertTrue("$phrase", preparation.blockingIssues.any {
                it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND
            })
        }
    }

    @Test
    fun identityAndQuantityReviewStayInLegacyUnresolvedAndTypedGlobalConcerns() {
        val propertyInstruction = "Brand fruit yogurt cups: mango one, blueberry one, strawberry one."
        val sharedHeaderItems = listOf(
            itemJson("mango one", 1, name = "Brand mango fruit yogurt cups"),
            itemJson("blueberry one", 1, name = "Brand blueberry fruit yogurt cups"),
            itemJson("strawberry one", 1, name = "Brand strawberry fruit yogurt cups"),
        ).joinToString(",")
        val property = GroceryIntentContextResponse.parse(
            body(propertyInstruction, sharedHeaderItems, emptyList()),
            propertyInstruction,
        ).prepareForReview()
        assertTrue(property.legacyPreparation.unresolvedText.isNotEmpty())
        assertTrue(property.legacyPreparation.itemReviewConcerns.any {
            it.kind == GroceryIntentItemReviewKind.IDENTITY_OR_PROPERTY && it.text.contains("Shared wording")
        })
        assertTrue(property.blockingIssues.any {
            it.kind == GroceryIntentContextIssueKind.ITEM_REVIEW &&
                it.concern?.kind == GroceryIntentItemReviewKind.IDENTITY_OR_PROPERTY &&
                it.concern.text.contains("Shared wording")
        })

        val quantityInstruction = "do 1 litre milk"
        val quantityItem = itemJson(quantityInstruction, 2, quantityUnit = "count", packValue = "1", packUnit = "l")
        val quantity = GroceryIntentContextResponse.parse(
            body(quantityInstruction, quantityItem, emptyList()), quantityInstruction,
        ).prepareForReview()
        assertTrue(quantity.legacyPreparation.unresolvedText.any { it.contains("Quantity to check") })
        assertTrue(quantity.legacyPreparation.itemReviewConcerns.any {
            it.kind == GroceryIntentItemReviewKind.QUANTITY_INTERPRETATION
        })
        assertTrue(quantity.blockingIssues.any {
            it.kind == GroceryIntentContextIssueKind.ITEM_REVIEW &&
                it.concern?.kind == GroceryIntentItemReviewKind.QUANTITY_INTERPRETATION
        })
    }

    @Test
    fun preparationAndNestedReviewCollectionsAreImmutableSnapshots() {
        val instruction = "2 milk; note for later"
        val response = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), listOf(entry("background", instruction, "note for later"))),
            instruction,
        )
        val preparation = response.prepareForReview()

        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.proposals as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.itemSources as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.unownedRanges as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.blockingIssues as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.legacyPreparation.items as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.legacyPreparation.items.single().avoidPhrases as MutableList).add("mutated")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (preparation.legacyPreparation.unresolvedText as MutableList).clear()
        }
        assertEquals("2 milk", preparation.itemSources.single().text)
        assertEquals("note for later", preparation.proposals.single().source.text)
    }

    @Test
    fun strictAdapterRejectsRepeatedOrForgedRolesAndSnapshotIsDetached() {
        val instruction = "2 milk; note"
        val proposal = entry("background", instruction, "note")
        val valid = GroceryIntentContextResponse.parse(
            body(instruction, itemJson("2 milk", 2), listOf(proposal)), instruction,
        )
        val snapshot = valid.validatedDraftSnapshot()
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.items as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.items.single().required as MutableList).add("mutated")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2),
                listOf(proposal, proposal)), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2),
                listOf(entry("background", instruction, "note", "start_plus_one"))), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2), emptyList())
                .replace("\"requires_review\":true", "\"requires_review\":false"), instruction)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentContextResponse.parse(body(instruction, itemJson("2 milk", 2), emptyList(),
                contract = "grocery-intent-v1"), instruction)
        }
    }

    @Test
    fun wholePrefixDistributionAndUpperBoundRemainActionableBackground() {
        listOf(
            "for each household, 2 milk" to "for each household",
            "at most, 2 milk" to "at most",
        ).forEach { (instruction, scope) ->
            val preparation = GroceryIntentContextResponse.parse(
                body(instruction, itemJson("2 milk", 2), listOf(entry("background", instruction, scope))),
                instruction,
            ).prepareForReview()
            assertTrue("$scope", preparation.blockingIssues.any {
                it.kind == GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND
            })
        }
    }

    @Test
    fun sharedMetadataAndClarificationValidationCannotBeSkippedByNewAdapter() {
        val instruction = "2 milk"
        val valid = body(instruction, itemJson(instruction, 2), emptyList())
        listOf(
            valid.replace("\"accepted\":true", "\"accepted\":false"),
            valid.replace("\"requires_review\":true", "\"requires_review\":false"),
            valid.replace("\"model_needs_clarification\":false", "\"model_needs_clarification\":true"),
            valid.replace("\"latencyMs\":1", "\"latencyMs\":-1"),
            valid.replace("\"usage\":{}", "\"usage\":{\"cart\":1}"),
            valid.replace("\"quantity_unit\":\"count\"", "\"quantity_unit\":\"guess\""),
        ).forEach { changed ->
            assertThrows(RuntimeException::class.java) { GroceryIntentContextResponse.parse(changed, instruction) }
        }
    }

    private fun itemJson(
        source: String,
        quantity: Int?,
        name: String = "milk",
        quantityUnit: String = "count",
        packValue: String? = null,
        packUnit: String = "unspecified",
        needsClarification: Boolean = false,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
    ) = """{"name":${json(name)},"quantity":${quantity?.toString() ?: "null"},"quantity_unit":${json(quantityUnit)},"pack_value":${packValue ?: "null"},"pack_unit":${json(packUnit)},"required":[${required.joinToString(",", transform = ::json)}],"excluded":[${excluded.joinToString(",", transform = ::json)}],"source_text":${json(source)},"needs_clarification":$needsClarification}"""

    private fun entry(kind: String, instruction: String, quote: String, offsetMode: String = "correct"): String {
        val start = instruction.indexOf(quote)
        val end = start + quote.length
        val startWire = when (offsetMode) {
            "start_plus_one" -> (start + 1).toString()
            "string_offset" -> json(start.toString())
            else -> start.toString()
        }
        return """{"kind":${json(kind)},"source":{"text":${json(quote)},"start_utf16":$startWire,"end_utf16":$end}}"""
    }

    private fun body(
        instruction: String,
        item: String,
        context: List<String>,
        contract: String = GROCERY_INTENT_CONTEXT_CONTRACT,
        includeContext: Boolean = true,
        modelNeedsClarification: Boolean = false,
    ): String {
        val contextField = if (includeContext) ",\"context\":[${context.joinToString(",")}]" else ""
        return """{"accepted":true,"draft":{"contract_version":${json(contract)},"requires_review":true,"instruction":${json(instruction)},"items":[$item],"model_needs_clarification":$modelNeedsClarification$contextField},"model":"model-x","promptVersion":"context-v1","latencyMs":1,"usage":{}}"""
    }

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""
}
