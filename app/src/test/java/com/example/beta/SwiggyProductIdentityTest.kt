package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyProductIdentityTest {
    @Test
    fun allows_expected_exact_generic_and_phonetic_matches() {
        assertAllowed("sesame paste", "Tahini")
        assertAllowed("sesame paste", "Sesame Paste")
        assertAllowed("ginger garlic paste", "Ginger Garlic Paste")
        assertAllowed("fresh coriander leaves", "Fresh Coriander Leaves")
        assertAllowed("keenwaa", "Quinoa")
        assertAllowed("mozrella", "Mozzarella Cheese")
        assertAllowed("amul buttr", "Amul Butter")
        assertAllowed("amul buttr 500g pack", "Amul Butter 500 g Pack")
        assertAllowed("oneplus 12r", "OnePlus 12R")
        assertAllowed("CR2032 battery", "CR2032 Battery")
        assertAllowed("milk", "Milk")
        assertAllowed("Milk", "Plain Milk")
        assertAllowed("1.5 litres milk", "Milk 3 x 500 ml")
        assertAllowed("unsalted butter", "Butter 500 g (unsalted)")
    }

    @Test
    fun rejects_wrong_brand_model_digit_and_unrelated_matches() {
        assertBlocked("sesame paste", "Aruna Fish Fry Paste 250g")
        assertBlocked("sesame paste", "Curry Paste")
        assertBlocked("sesame paste", "Ginger Paste")
        assertBlocked("Amul butter", "Amul Milk")
        assertBlocked("Amul", "Wrongbrand")
        assertBlocked("CR2032", "CR2025")
        assertBlocked("milk", "silk")
        assertBlocked("butter", "batter")
        assertBlocked("soup", "Dettol Soap 100 g")
        assertBlocked("bitter", "Amul Butter 500 g")
        assertBlocked("floor", "Aashirvaad Atta flour")
        assertBlocked("ginger", "Fish Finger")
        assertBlocked("Pampers Premium Care taped diapers", "diapers")
        assertBlocked("salted butter", "Butter 500 g (unsalted)")
        assertBlocked("butter 500 g (unsalted)", "Butter 500 g (salted)")
    }

    @Test
    fun honors_strict_match_phrase_and_avoid_phrases() {
        assertBlocked(
            query = "milk",
            label = "Milk",
            strictMatchPhrase = "strawberry milk",
        )
        assertBlocked(
            query = "amul butter",
            label = "Amul Butter 100 g",
            strictMatchPhrase = "Amul Butter 500 g",
        )
        assertAllowed(
            query = "amul butter",
            label = "Amul Butter 500gm",
            strictMatchPhrase = "Amul Butter 500 g",
        )
        assertBlocked(
            query = "amul butter",
            label = "Amul Butter 2 x 500 g",
            strictMatchPhrase = "Amul Butter 500 g",
        )
        assertBlocked("butter", "Butter 3 packs", strictMatchPhrase = "Butter 2 packs")
        assertBlocked("eggs", "Eggs 6 packs", strictMatchPhrase = "Eggs 6 pieces")
        assertBlocked(
            query = "milk",
            label = "Salted Milk",
            avoidPhrases = listOf("salted"),
        )
        assertAllowed(
            query = "milk",
            label = "Unsalted Milk",
            avoidPhrases = listOf("salted"),
        )
        assertAllowed(
            query = "salted butter",
            label = "Butter 500 g (salted)",
            strictMatchPhrase = "salted butter",
        )
    }

    private fun assertAllowed(
        query: String,
        label: String,
        strictMatchPhrase: String? = null,
        avoidPhrases: List<String> = emptyList(),
    ) {
        assertTrue(
            "Expected '$query' to allow '$label'",
            isSwiggyCandidateAllowed(
                item = item(query, strictMatchPhrase, avoidPhrases),
                candidate = candidate(label),
            ),
        )
    }

    private fun assertBlocked(
        query: String,
        label: String,
        strictMatchPhrase: String? = null,
        avoidPhrases: List<String> = emptyList(),
    ) {
        assertFalse(
            "Expected '$query' to reject '$label'",
            isSwiggyCandidateAllowed(
                item = item(query, strictMatchPhrase, avoidPhrases),
                candidate = candidate(label),
            ),
        )
    }

    private fun item(
        query: String,
        strictMatchPhrase: String? = null,
        avoidPhrases: List<String> = emptyList(),
    ): ParsedItem {
        return ParsedItem(
            rawText = query,
            query = query,
            strictMatchPhrase = strictMatchPhrase,
            avoidPhrases = avoidPhrases,
        )
    }

    private fun candidate(label: String): RecommendationCandidate {
        return RecommendationCandidate(
            spinId = "synthetic-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            label = label,
        )
    }
}
