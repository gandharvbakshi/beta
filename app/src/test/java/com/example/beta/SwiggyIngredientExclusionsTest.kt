package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyIngredientExclusionsTest {
    @Test
    fun explicit_chemical_and_artificial_additive_exclusions_are_closed() {
        val cases = listOf(
            Triple("chemicals", "Biscuits", false),
            Triple("chemicals", "Biscuits no chemicals", true),
            Triple("chemicals", "Biscuits without chemicals", true),
            Triple("chemicals", "Biscuits chemicals-free", true),
            Triple("preservatives", "Biscuits", false),
            Triple("preservatives", "Biscuits no preservatives", true),
            Triple("preservatives", "Biscuits without preservatives", true),
            Triple("preservatives", "Biscuits preservatives-free", true),
            Triple("artificial colours", "Biscuits", false),
            Triple("artificial colours", "Biscuits no artificial colours", true),
            Triple("artificial colours", "Biscuits without artificial colours", true),
            Triple("artificial colours", "Biscuits artificial-colours-free", true),
            Triple("artificial flavours", "Biscuits", false),
            Triple("artificial flavours", "Biscuits no artificial flavours", true),
            Triple("artificial flavours", "Biscuits without artificial flavours", true),
            Triple("artificial flavours", "Biscuits artificial-flavours-free", true),
        )
        cases.forEach { (exclusion, label, expected) ->
            assertTrue("Expected '$label' for '$exclusion' to be $expected", allowed(exclusion, label) == expected)
        }
    }

    @Test
    fun ingredient_exclusion_phrasing_covers_singular_us_spelling_and_scoped_preservatives() {
        assertFalse(allowed("preservatives", "Biscuits no preservative"))
        assertFalse(allowed("artificial colours", "Biscuits no artificial colors"))
        assertFalse(allowed(listOf("chemicals", "preservatives"), "Biscuits no chemicals and preservatives"))

        for (label in listOf("Biscuits no added preservatives", "Biscuits no preservatives added")) {
            assertFalse("all-preservatives label=$label", allowed("preservatives", label))
        }

        assertFalse(allowed(listOf("chemicals", "preservatives"), "Biscuits no chemicals"))
        assertFalse(allowed(listOf("artificial colours", "artificial flavours"), "Biscuits no artificial colors"))
    }

    @Test
    fun absence_of_added_ingredient_does_not_prove_absence_of_the_ingredient() {
        val cases = listOf(
            "chemicals" to "Biscuits no added chemicals",
            "preservatives" to "Biscuits no added preservatives",
            "artificial colours" to "Biscuits no added artificial colors",
            "artificial flavours" to "Biscuits no added artificial flavors",
        )
        cases.forEach { (exclusion, label) -> assertFalse("label=$label", allowed(exclusion, label)) }
        listOf("chemicals", "preservatives", "artificial colours", "artificial flavours").forEach {
            assertFalse(allowed(it, "Biscuits no $it added"))
        }
    }

    @Test
    fun chemical_and_artificial_additive_exclusions_reject_qualified_or_conflicting_claims() {
        val cases = listOf(
            "chemicals" to "Biscuits maybe no chemicals",
            "chemicals" to "Biscuits no chemicals, contains chemicals",
            "preservatives" to "Biscuits may be without preservatives",
            "preservatives" to "Biscuits preservatives-free, contains preservatives",
            "artificial colours" to "Biscuits possibly without artificial colours",
            "artificial colours" to "Biscuits no artificial colours, contains artificial colours",
            "artificial flavours" to "Biscuits not artificial-flavours-free",
            "artificial flavours" to "Biscuits without artificial flavours, contains artificial flavours",
        )
        cases.forEach { (exclusion, label) -> assertFalse("label=$label", allowed(exclusion, label)) }
    }

    @Test
    fun protected_ingredients_need_matching_explicit_absence_claims() {
        val cases = listOf(
            Triple("maida", "Biscuits zero maida", true),
            Triple("maida", "Biscuits without maida", true),
            Triple("maida", "Biscuits no-maida", true),
            Triple("maida", "Biscuits maida-free", true),
            Triple("maida", "Biscuits", false),
            Triple("maida", "Biscuits zero maida, contains maida", false),
            Triple("maida", "Biscuits not zero maida", false),
            Triple("maida", "Biscuits not maida-free", false),
            Triple("maida", "Biscuits no maida-free", false),
            Triple("maida", "Biscuits low maida", false),
            Triple("maida", "Biscuits may contain maida", false),
            Triple("maida", "Biscuits zero maida alternatives", false),
            Triple("maida", "Biscuits 0.5 maida", false),
            Triple("maida", "Biscuits 1/0 maida", false),
            Triple("maida", "Biscuits -0 maida", false),
            Triple("maida", "Biscuits −0 maida", false),
            Triple("palm oil", "Biscuits palm-oil-free", true),
            Triple("palm oil", "Biscuits without palm oil", true),
            Triple("palm oil", "Biscuits no palm oil, palm oil", false),
            Triple("salt", "Biscuits unsalted", false),
            Triple("salt", "Biscuits no salt", true),
            Triple("salt", "Biscuits low salt", false),
            Triple("added salt", "Biscuits unsalted", false),
            Triple("added salt", "Biscuits no-added-salt", true),
            Triple("added sugar", "Biscuits zero added sugar", true),
            Triple("added sugar", "Biscuits sugar-free", false),
            Triple("added sugar", "Biscuits no added sugar, added sugar", false),
            Triple("maida", "Biscuits—zero maida!", true),
            Triple("maida", "Biscuits zero maidas", false),
            Triple("maida", "Biscuits not: maida free", false),
            Triple("maida", "Biscuits not actually maida-free", false),
            Triple("maida", "Biscuits almost zero maida", false),
            Triple("maida", "Biscuits zero maida or regular", false),
            Triple("maida", "Biscuits maida-free-ish", false),
            Triple("maida", "Biscuits zero-maida-based", false),
            Triple("maida", "Biscuits no-maida-substitutes", false),
            Triple("salt", "Biscuits not unsalted", false),
            Triple("salt", "Biscuits not: unsalted", false),
            Triple("salt", "Biscuits may be unsalted", false),
            Triple("salt", "Biscuits low unsalted", false),
            Triple("salt", "Biscuits unsalted alternative", false),
            Triple("salt", "Biscuits unsalted, salted", false),
            Triple("salt", "Biscuits unsalted, contains salt", false),
            Triple("salt", "Biscuits unsalted, added salt", false),
            Triple("palm oil", "Biscuits no palm-oil: not guaranteed", false),
            // Conservative catalogue evidence, not an ingredient-safety inference.
            Triple("palm oil", "Biscuits No Palm Oil - For Kids", false),
            Triple("maida", "Biscuits No Maida, In Jar", false),
            Triple("added sugar", "Muesli No Added Sugar - For Kids", false),
            Triple("added sugar", "Biscuits 0% Added Sugar, for kids", false),
        )
        for ((exclusion, label, allowed) in cases) {
            val actual = allowed(exclusion, label)
            if (allowed) assertTrue("Expected '$label' to be allowed for '$exclusion'", actual)
            else assertFalse("Expected '$label' to be rejected for '$exclusion'", actual)
        }
    }

    @Test
    fun every_protected_exclusion_needs_its_own_claim_and_generic_dislikes_stay_generic() {
        val all = listOf("maida", "palm oil", "salt", "added salt", "added sugar")
        assertTrue(allowed(all, "Biscuits zero maida, palm-oil-free, no salt, no added salt, no added sugar"))
        assertFalse(allowed(all, "Biscuits zero maida, palm-oil-free, no salt, no added sugar"))
        assertTrue(allowed("vanilla", "Chocolate biscuits"))
        assertFalse(allowed("vanilla", "Vanilla chocolate biscuits"))
    }

    @Test
    fun exact_zero_percent_catalogue_claims_cover_all_closed_ingredients() {
        val cases = listOf(
            "sugar" to listOf("zero percent sugar", "0% sugar", "zero% sugar"),
            "salt" to listOf("zero percent salt", "0% salt", "zero% salt"),
            "added sugar" to listOf("zero percent added sugar", "0% added sugar", "zero% added sugar"),
            "added salt" to listOf("zero percent added salt", "0% added salt", "zero% added salt"),
            "maida" to listOf("zero percent maida", "0% maida", "zero% maida"),
            "palm oil" to listOf("zero percent palm oil", "0% palm oil", "zero% palm oil"),
        )
        cases.forEach { (ingredient, claims) -> claims.forEach { claim ->
            assertTrue("ingredient=$ingredient claim=$claim", allowed(ingredient, "Biscuits $claim"))
        } }
    }

    @Test
    fun percentageIngredientClaimsMustBeExactUnqualifiedAndUncontradicted() {
        val labels = listOf(
            "Biscuits sugar", "Biscuits 0.5% sugar", "Biscuits -0% sugar", "Biscuits +0% sugar",
            "Biscuits 1/0% sugar", "Biscuits 1e0% sugar", "Biscuits maybe 0% sugar",
            "Biscuits approximately zero percent sugar", "Biscuits not 0% sugar",
            "Biscuits 0% sugar, contains sugar",
        )
        labels.forEach { label -> assertFalse("label=$label", allowed("sugar", label)) }
    }

    @Test
    fun percentageSugarAndAddedSugarRemainDistinct() {
        assertTrue(allowed("sugar", "Biscuits zero percent sugar"))
        assertFalse(allowed("sugar", "Biscuits zero percent added sugar"))
        assertTrue(allowed("added sugar", "Biscuits zero percent added sugar"))
        assertFalse(allowed("added sugar", "Biscuits zero percent sugar"))
    }

    @Test
    fun saltedConflictsWithEveryExplicitSaltAbsenceForm() {
        listOf("0% salt", "zero percent salt", "no salt", "salt-free", "unsalted").forEach { claim ->
            assertFalse("claim=$claim", allowed("salt", "Biscuits $claim, salted"))
        }
    }

    @Test
    fun strictMatchPhraseAcceptsOnlyEquivalentPercentageProtectedProperties() {
        val cases = listOf(
            listOf("sugar", "biscuits without sugar", "biscuits sugar-free", "Biscuits zero percent sugar"),
            listOf("added sugar", "biscuits without added sugar", "biscuits no added sugar", "Biscuits 0% added sugar"),
            listOf("salt", "biscuits without salt", "biscuits unsalted", "Biscuits zero% salt"),
        )
        cases.forEach { (exclusion, query, strict, label) ->
            assertTrue("label=$label", allowed(exclusion, label, query, strict))
        }
        assertFalse(allowed("added sugar", "Biscuits zero percent added sugar",
            "biscuits without sugar", "biscuits sugar-free"))
    }

    @Test
    fun literalZeroPercentMaidaAndCombinedIngredientsRequireTheirExactClaims() {
        assertTrue(allowed("maida", "Biscuits zero percent maida"))
        assertTrue(allowed("maida", "Biscuits 0% maida"))
        assertTrue(allowed("maida", "Biscuits 0% maida",
            "biscuits zero percent maida", "biscuits zero percent maida"))
        assertTrue(allowed(listOf("maida", "palm oil"), "Biscuits 0% maida, zero% palm oil"))
        assertFalse(allowed(listOf("maida", "palm oil"), "Biscuits 0% maida"))
        assertFalse(allowed(listOf("maida", "salt"), "Biscuits 0% maida, zero% palm oil"))
    }

    private fun allowed(exclusion: String, label: String): Boolean = allowed(listOf(exclusion), label)

    private fun allowed(exclusion: String, label: String, query: String, strictMatchPhrase: String?): Boolean =
        allowed(listOf(exclusion), label, query, strictMatchPhrase)

    private fun allowed(
        exclusions: List<String>,
        label: String,
        query: String = "biscuits",
        strictMatchPhrase: String? = null,
    ): Boolean = isSwiggyCandidateAllowed(
        item = ParsedItem(rawText = query, query = query, strictMatchPhrase = strictMatchPhrase, avoidPhrases = exclusions),
        candidate = RecommendationCandidate(spinId = "biscuits", label = label),
    )
}
