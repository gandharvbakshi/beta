package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentZeroIngredientsTest {
    @Test
    fun reviewedPercentAbsenceReachesOnlyMatchingCatalogueCandidate() {
        val source = "1 biscuits with zero percent maida and 0% palm oil"
        val prepared = zeroReview(source, source, excluded = listOf("maida", "palm oil")).prepareForReview()
        val item = prepared.items.single()
        fun offered(label: String) = SwiggyMcpClient.RecommendationCandidate(spinId = "test", label = label)
        assertTrue(isSwiggyCandidateAllowed(item, offered("Biscuits 0% maida, zero percent palm oil")))
        assertFalse(isSwiggyCandidateAllowed(item, offered("Biscuits 0% maida")))
        assertFalse(isSwiggyCandidateAllowed(item, offered("Biscuits 0% maida, zero percent palm oil, contains palm oil")))
        assertEquals(Quantity.Count(1), item.quantity)
        assertEquals(source, item.rawText)
    }

    @Test
    fun exactZeroPercentClaimsKeepCountAndExactExclusions() {
        val cases = listOf(
            Triple("zero percent sugar", "sugar", "sugar"),
            Triple("0 percent salt", "salt", "salt"),
            Triple("0% added sugar", "added sugar", "added sugar"),
            Triple("zero% added salt", "added salt", "added salt"),
            Triple("zero % maida", "maida", "maida"),
            Triple("0 % palm oil", "palm oil", "palm oil"),
        )
        cases.forEach { (claim, _, exclusion) ->
            val source = "1 biscuits with $claim"
            val prepared = zeroReview(source, source, excluded = listOf(exclusion)).prepareForReview()
            assertEquals("source=$source", 1, prepared.items.size)
            assertEquals("source=$source", Quantity.Count(1), prepared.items.single().quantity)
            assertEquals("source=$source", listOf(exclusion), prepared.items.single().avoidPhrases)
            assertTrue("source=$source", prepared.unresolvedText.isEmpty())
            assertTrue("source=$source", prepared.excludedText.isEmpty())
        }
    }

    @Test
    fun zeroPercentSupportsOnlyCompatibleProtectedProperties() {
        val cases = listOf(
            Triple("1 cola with zero percent sugar", "sugar_free", "sugar"),
            Triple("1 cola with zero percent added sugar", "no_added_sugar", "added sugar"),
            Triple("1 chips with zero percent salt", "", "salt"),
        )
        cases.forEach { (source, required, exclusion) ->
            val productName = if (source.contains("chips")) "chips" else "cola"
            val prepared = zeroReview(source, source, required = listOf(required).filter(String::isNotEmpty), excluded = listOf(exclusion),
                nameForFirst = productName)
                .prepareForReview()
            assertEquals("source=$source", 1, prepared.items.size)
            assertEquals("source=$source", Quantity.Count(1), prepared.items.single().quantity)
            if (required.isNotEmpty()) assertTrue("source=$source", prepared.items.single().query.contains(required.replace('_', ' ')))
            assertEquals(prepared.items.single().query, prepared.items.single().strictMatchPhrase)
            assertEquals(source, prepared.items.single().rawText)
            assertEquals("source=$source", listOf(exclusion), prepared.items.single().avoidPhrases)
        }
        val addedSugarIsNotSugarFree = "1 biscuits with zero percent added sugar"
        val mismatch = zeroReview(addedSugarIsNotSugarFree, addedSugarIsNotSugarFree,
            required = listOf("sugar_free"), excluded = listOf("added sugar")).prepareForReview()
        assertTrue(mismatch.items.isEmpty())
        assertTrue(mismatch.excludedText.isNotEmpty())
        val salt = "1 chips with zero percent salt"
        assertTrue(zeroReview(salt, salt, required = listOf("unsalted"), excluded = listOf("salt"),
            nameForFirst = "chips").prepareForReview().items.isEmpty())
    }

    @Test
    fun malformedOrQualifiedZeroPercentClaimsRemainBlocked() {
        listOf(
            "10% sugar", "0.5% sugar", "-0% sugar", "+0% sugar", "1/0% sugar", "1e0% sugar",
            "almost zero percent sugar", "not 0% sugar",
        ).forEach { claim ->
            val source = "1 biscuits with $claim"
            val prepared = zeroReview(source, source, excluded = listOf("sugar")).prepareForReview()
            assertTrue("claim=$claim", prepared.items.isEmpty())
        }
    }

    @Test
    fun unknownZeroPercentIngredientAndOmittedCountRemainBlocked() {
        val unknown = "1 biscuits with zero percent turmeric"
        val unknownResult = zeroReview(unknown, unknown, excluded = listOf("turmeric")).prepareForReview()
        assertTrue(unknownResult.items.isEmpty())
        assertTrue(unknownResult.excludedText.isNotEmpty() || unknownResult.unresolvedText.isNotEmpty())

        val omitted = "zero percent maida biscuits"
        val omittedResult = zeroReview(omitted, omitted, quantity = null, excluded = listOf("maida"),
            nameForFirst = "zero percent maida biscuits").prepareForReview()
        assertTrue(omittedResult.items.isEmpty())
        assertTrue(omittedResult.excludedText.isNotEmpty() || omittedResult.unresolvedText.isNotEmpty())

        val retainedPhrase = "1 zero percent maida biscuits"
        val noExclusion = zeroReview(retainedPhrase, retainedPhrase,
            nameForFirst = "zero percent maida biscuits").prepareForReview()
        assertTrue(noExclusion.items.isEmpty())
        assertTrue(noExclusion.excludedText.isNotEmpty())
    }

    @Test
    fun zeroPercentRequiresExactExclusionAndCannotDoubleClaimOrLendEvidence() {
        listOf(emptyList(), listOf("palm"), listOf("oil"), listOf("palm", "oil"), listOf("maida"))
            .forEach { exclusion ->
                val source = "1 biscuits with 0% palm oil"
                val prepared = zeroReview(source, source, excluded = exclusion).prepareForReview()
                assertTrue("exclusion=$exclusion", prepared.items.isEmpty())
            }

        val retained = "1 zero percent maida biscuits with 0% palm oil"
        val retainedResult = zeroReview(retained, retained,
            nameForFirst = "zero percent maida biscuits", excluded = listOf("maida", "palm oil"))
            .prepareForReview()
        assertEquals(1, retainedResult.items.size)
        assertEquals(Quantity.Count(1), retainedResult.items.single().quantity)
        assertEquals(listOf("maida", "palm oil"), retainedResult.items.single().avoidPhrases)

        val extraCount = "1 biscuits with 0% sugar and 2 packs"
        val extraResult = zeroReview(extraCount, extraCount, excluded = listOf("sugar")).prepareForReview()
        assertTrue(extraResult.items.isEmpty())

        val first = "1 biscuits with zero percent maida"
        val second = "1 crackers with zero percent palm oil"
        val instruction = "$first; $second"
        val draft = zeroReview(instruction, first, excluded = listOf("maida"),
            secondSource = second, secondExcluded = listOf("maida"))
        assertTrue("the first row's local zero/maida polarity is independently grounded",
            draft.readyRows[0].groundingIssue(instruction = instruction) == null)
        assertTrue("the second row cannot borrow palm-oil absence from its local text",
            draft.readyRows[1].groundingIssue(instruction = instruction) != null)
        val neighbor = draft.prepareForReview()
        assertTrue(neighbor.items.isEmpty())
        assertEquals(listOf(instruction), neighbor.unresolvedText)
    }

    @Test
    fun exactZeroMaidaAndPalmOilExclusionsKeepTheRequestedCount() {
        listOf("1 biscuits with zero maida" to "maida", "1 biscuits with zero palm oil" to "palm oil")
            .forEach { (source, ingredient) ->
                val prepared = zeroReview(source, source, excluded = listOf(ingredient)).prepareForReview()

                assertEquals(1, prepared.items.size)
                assertEquals(Quantity.Count(1), prepared.items.single().quantity)
                assertTrue(prepared.items.single().avoidPhrases.contains(ingredient))
                assertTrue(prepared.unresolvedText.isEmpty())
                assertTrue(prepared.excludedText.isEmpty())
            }
    }

    @Test
    fun zeroPalmOilRequiresTheExactCompleteExclusion() {
        listOf(emptyList(), listOf("palm"), listOf("oil"), listOf("palm", "oil")).forEach { exclusion ->
            val source = "1 biscuits with zero palm oil"
            val prepared = zeroReview(source, source, excluded = exclusion).prepareForReview()
            assertTrue("exclusions=$exclusion", prepared.items.isEmpty())
            assertTrue("exclusions=$exclusion", prepared.excludedText.isNotEmpty())
        }
    }

    @Test
    fun positiveIngredientConflictCannotBeOverriddenByZeroClaim() {
        val source = "1 maida biscuits with zero maida"
        val prepared = zeroReview(source, source, nameForFirst = "maida biscuits", excluded = listOf("maida"))
            .prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty())
    }

    @Test
    fun onlyLiteralZeroIsAnAbsenceClaimAndItCannotHideAnotherCount() {
        listOf("0.5", "10", "-0", "1").forEach { amount ->
            val source = "1 biscuits with $amount maida"
            val prepared = zeroReview(source, source, excluded = listOf("maida")).prepareForReview()
            assertTrue("amount=$amount", prepared.items.isEmpty())
        }

        val trueZero = "1 biscuits with 0 maida"
        val acceptedZero = zeroReview(trueZero, trueZero, excluded = listOf("maida")).prepareForReview()
        assertEquals(1, acceptedZero.items.size)
        assertEquals(Quantity.Count(1), acceptedZero.items.single().quantity)
        assertTrue(acceptedZero.items.single().avoidPhrases.contains("maida"))

        val extraCount = "1 biscuits with zero maida and 2 crackers"
        val extraCountPrepared = zeroReview(extraCount, extraCount, excluded = listOf("maida")).prepareForReview()
        assertTrue(extraCountPrepared.items.isEmpty())
        assertTrue(extraCountPrepared.excludedText.isNotEmpty() || extraCountPrepared.unresolvedText.isNotEmpty())
    }

    @Test
    fun unknownZeroIngredientCannotBeSilentlyAccepted() {
        val source = "1 biscuits with zero turmeric"
        val prepared = zeroReview(source, source, excluded = listOf("turmeric")).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty() || prepared.unresolvedText.isNotEmpty())
    }

    @Test
    fun zeroSugarSupportsSugarFreeButNotNoAddedSugar() {
        val source = "1 biscuits with zero sugar"
        val sugarFree = zeroReview(source, source, required = listOf("sugar_free")).prepareForReview()
        assertEquals(1, sugarFree.items.size)
        assertEquals(Quantity.Count(1), sugarFree.items.single().quantity)

        val noAddedSugar = zeroReview(source, source, required = listOf("no_added_sugar")).prepareForReview()
        assertTrue(noAddedSugar.items.isEmpty())
        assertTrue(noAddedSugar.excludedText.isNotEmpty())
    }

    @Test
    fun equivalentSugarAbsencePhrasesProveTheirExactExclusions() {
        val cases = listOf(
            Triple("1 biscuits with zero sugar", "sugar_free", "sugar"),
            Triple("1 biscuits with no added sugar", "no_added_sugar", "added sugar"),
        )

        cases.forEach { (source, required, exclusion) ->
            val prepared = zeroReview(
                source,
                source,
                required = listOf(required),
                excluded = listOf(exclusion),
            ).prepareForReview()

            assertEquals("source=$source", 1, prepared.items.size)
            assertEquals("source=$source", Quantity.Count(1), prepared.items.single().quantity)
            assertEquals("source=$source", listOf(exclusion), prepared.items.single().avoidPhrases)
            assertTrue("source=$source", prepared.unresolvedText.isEmpty())
            assertTrue("source=$source", prepared.excludedText.isEmpty())
        }
    }

    @Test
    fun sugarExclusionCannotBeRewrittenToADifferentRestriction() {
        val cases = listOf(
            Triple("1 biscuits with no added sugar", "no_added_sugar", "sugar"),
            Triple("1 biscuits with zero sugar", "sugar_free", "added sugar"),
        )

        cases.forEach { (source, required, exclusion) ->
            val prepared = zeroReview(
                source,
                source,
                required = listOf(required),
                excluded = listOf(exclusion),
            ).prepareForReview()

            assertTrue("source=$source exclusion=$exclusion", prepared.items.isEmpty())
            assertTrue("source=$source exclusion=$exclusion", prepared.excludedText.isNotEmpty())
        }
    }

    @Test
    fun qualifiedSugarAbsenceDoesNotProveRequiredPropertyOrExclusion() {
        val cases = listOf(
            Triple("almost zero sugar", "sugar_free", "sugar"),
            Triple("nearly no added sugar", "no_added_sugar", "added sugar"),
            Triple("possibly zero sugar", "sugar_free", "sugar"),
            Triple("may contain zero sugar", "sugar_free", "sugar"),
            Triple("cant be zero sugar", "sugar_free", "sugar"),
            Triple("can’t contain zero sugar", "sugar_free", "sugar"),
            Triple("zero sugar maybe", "sugar_free", "sugar"),
        )

        cases.forEach { (phrase, required, exclusion) ->
            val source = "1 biscuits with $phrase"
            listOf(emptyList(), listOf(exclusion)).forEach { excluded ->
                val prepared = zeroReview(
                    source,
                    source,
                    required = listOf(required),
                    excluded = excluded,
                ).prepareForReview()

                assertTrue("source=$source excluded=$excluded", prepared.items.isEmpty())
            }
        }
    }

    @Test
    fun zeroSugarWithLowFatRequirementAndMatchingExclusionRemainsValid() {
        val source = "1 low fat biscuits with zero sugar"
        val prepared = zeroReview(
            source,
            source,
            required = listOf("low_fat", "sugar_free"),
            excluded = listOf("sugar"),
        ).prepareForReview()

        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertEquals(listOf("sugar"), prepared.items.single().avoidPhrases)
        assertTrue(prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())
    }

    @Test
    fun unclaimedZeroIngredientTailStillBlocksAfterAcknowledgement() {
        val instruction = "1 biscuits; zero palm oil"
        val prepared = zeroReview(instruction, "1 biscuits").prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty())

        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText, prepared.excludedText)
        gate.acknowledge(true)
        assertFalse(gate.canApply())
    }

    @Test
    fun oneItemsExclusionCannotBeBorrowedByANeighbor() {
        val first = "1 biscuits with zero maida"
        val second = "1 crackers with zero palm oil"
        val instruction = "$first; $second"
        val review = zeroReview(
            instruction,
            first,
            excluded = listOf("maida"),
            secondSource = second,
            secondExcluded = listOf("maida"),
        )
        assertTrue("local polarity for the first product remains independently grounded",
            review.readyRows[0].groundingIssue(instruction = instruction) == null)
        assertTrue("the second product's palm-oil absence remains locally ungrounded",
            review.readyRows[1].groundingIssue(instruction = instruction) != null)
        val prepared = review.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(instruction), prepared.unresolvedText)
    }

    @Test
    fun nullCountWithZeroIngredientRemainsBlocked() {
        val source = "biscuits with zero maida"
        val prepared = zeroReview(source, source, quantity = null, excluded = listOf("maida")).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.excludedText.isNotEmpty() || prepared.unresolvedText.isNotEmpty())
    }

    private fun zeroReview(
        instruction: String,
        source: String,
        quantity: BigDecimal? = BigDecimal.ONE,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        nameForFirst: String = "biscuits",
        secondSource: String? = null,
        secondExcluded: List<String> = emptyList(),
    ): GroceryIntentReviewV2 {
        val rows = listOfNotNull(
            zeroRow(instruction, 0, source, quantity, required, excluded, nameForFirst),
            secondSource?.let { zeroRow(instruction, 1, it, BigDecimal.ONE, emptyList(), secondExcluded) },
        )
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = rows.size,
            readyRows = rows,
            blockedRows = emptyList(),
        )
    }

    private fun zeroRow(
        instruction: String,
        rowIndex: Int,
        source: String,
        quantity: BigDecimal?,
        required: List<String>,
        excluded: List<String>,
        name: String = if (rowIndex == 0) "biscuits" else "crackers",
    ): GroceryIntentReviewV2ReadyRow {
        val amountStated = quantity != null
        val item = GroceryIntentReviewV2Item(
            name = name,
            quantity = quantity,
            quantityUnit = if (amountStated) "count" else "unspecified",
            packValue = null,
            packUnit = "unspecified",
            packaging = null,
            required = required,
            excluded = excluded,
            sourceText = source,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = quantity?.toPlainString(),
            packText = null,
        )
        val start = instruction.indexOf(source)
        require(start >= 0)
        val primary = GroceryIntentReviewV2Quote(source, start, start + source.length)
        return GroceryIntentReviewV2ReadyRow(
            rowIndex = rowIndex,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(primary, emptyList()),
            quantityOrigin = if (amountStated) "stated" else "not_stated",
            amountRoleStatus = if (amountStated) "matched" else "not_stated",
        )
    }
}
