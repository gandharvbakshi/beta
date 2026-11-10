package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class GroceryIntentCategoryAliasTest {
    @Test fun everyMultiwordAliasAcceptsSpacesAndAsciiLetterHyphensWithoutChangingItsIdentityScope() {
        val instance = ProductLexicon::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        val entries = ProductLexicon::class.java.getDeclaredField("aliasTokenEntries").apply { isAccessible = true }
            .get(instance) as List<Pair<String, List<String>>>
        val multiword = entries.filter { it.second.size > 1 }
        var asciiHyphenVariants = 0

        multiword.forEach { (canonical, tokens) ->
            val spaced = tokens.joinToString(" ")
            assertEquals("spaced alias=$spaced of ${multiword.size} multiword aliases", canonical,
                ProductLexicon.canonicalizeAliasesPreservingText(spaced))
            assertEquals("product alias=$spaced", canonical, ProductLexicon.canonicalizeProductText(spaced))
            val surrounded = "Brand $spaced (0.5 kg)"
            val expectedSurrounded = "Brand $canonical (0.5 kg)"
            assertEquals("surrounded alias=$spaced", expectedSurrounded,
                ProductLexicon.canonicalizeAliasesPreservingText(surrounded))
            assertEquals("preserving pass is idempotent for $spaced", expectedSurrounded,
                ProductLexicon.canonicalizeAliasesPreservingText(expectedSurrounded))

            if (tokens.all { it.matches(Regex("[A-Za-z]+")) }) {
                asciiHyphenVariants++
                val hyphenated = tokens.joinToString("-")
                assertEquals("single internal ASCII hyphens for $spaced of ${multiword.size} multiword aliases",
                    canonical, ProductLexicon.canonicalizeAliasesPreservingText(hyphenated))
                val hyphenatedContext = "Brand $hyphenated (0.5 kg)"
                assertEquals("hyphenated quantity context for $spaced",
                    expectedSurrounded, ProductLexicon.canonicalizeAliasesPreservingText(hyphenatedContext))
                assertEquals("hyphenated source is not rewritten on a second pass", expectedSurrounded,
                    ProductLexicon.canonicalizeAliasesPreservingText(expectedSurrounded))
            }
        }
        assertTrue("expected aliases to be enumerated", multiword.isNotEmpty())
        assertTrue("expected ASCII multiword aliases to exercise hyphen variants", asciiHyphenVariants > 0)

        listOf(
            "ready - made tea", "ready--made tea", "ready/made tea", "ready, made tea", "ready; made tea",
            "ready-\nmade tea", "ready–made tea", "ready‑made tea", "ready-2made tea", "ready2-made tea",
            "unknown-product tea",
        ).forEach { source ->
            assertEquals("only a single ASCII letter-internal separator is accepted: $source", source,
                ProductLexicon.canonicalizeAliasesPreservingText(source))
        }
        assertEquals("ready made tea", ProductLexicon.canonicalizeIdentityNounsPreservingText("ready made tea"))
        assertEquals("ready-made tea", ProductLexicon.canonicalizeIdentityNounsPreservingText("ready-made tea"))
    }

    @Test fun aliasCanonicalizersMatchOriginalTextCursorAcrossTheCurrentAliasCatalogue() {
        val instance = ProductLexicon::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }.get(null)
        @Suppress("UNCHECKED_CAST")
        val tokenEntries = ProductLexicon::class.java.getDeclaredField("aliasTokenEntries").apply {
            isAccessible = true
        }.get(instance) as List<Pair<String, List<String>>>
        tokenEntries.forEach { (canonical, tokens) ->
            for (length in 1 until tokens.size) {
                assertNotEquals(
                    "Token alias for $canonical must not self-overlap: optimized findAll " +
                        "matching could skip a later cursor match when an earlier occurrence is consumed",
                    tokens.take(length), tokens.takeLast(length),
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        val preservingEntries = ProductLexicon::class.java.getDeclaredField("preservingAliasEntries").apply {
            isAccessible = true
        }.get(instance) as List<Pair<Regex, String>>

        fun referencePreserving(text: String): String = buildString {
            var cursor = 0
            while (cursor < text.length) {
                val match = preservingEntries.firstNotNullOfOrNull { (pattern, canonical) ->
                    pattern.matchAt(text, cursor)?.let { it to canonical }
                }
                if (match == null) {
                    append(text[cursor])
                    cursor++
                } else {
                    append(match.second)
                    cursor = match.first.range.last + 1
                }
            }
        }

        fun referenceProduct(text: String): String {
            val tokens = ProductLexicon.tokenize(text)
            if (tokens.isEmpty()) return text.trim().lowercase()
            val output = mutableListOf<String>()
            var cursor = 0
            while (cursor < tokens.size) {
                val match = tokenEntries.firstOrNull { (_, aliasTokens) ->
                    cursor + aliasTokens.size <= tokens.size &&
                        tokens.subList(cursor, cursor + aliasTokens.size) == aliasTokens
                }
                if (match == null) {
                    output += tokens[cursor++]
                } else {
                    output += ProductLexicon.tokenize(match.first)
                    cursor += match.second.size
                }
            }
            return output.joinToString(" ")
        }

        val samples = linkedSetOf(
            "elaichi banana elaichi", "kittale hannu juice",
            "elaichi; banana", "knownbrand elaichi unknownitem",
        )
        tokenEntries.forEachIndexed { index, (_, tokens) ->
            val phrase = tokens.joinToString(" ")
            samples += phrase
            samples += phrase.uppercase()
            samples += tokens.joinToString("-")
            // Rotate through boundary stressors to keep the full-catalogue
            // matrix bounded while still covering each boundary class.
            samples += when (index % 4) {
                0 -> tokens.joinToString("_")
                1 -> "${phrase}'s"
                2 -> "$phrase\u0301"
                else -> "brand $phrase, unknownitem"
            }
        }

        samples.forEach { sample ->
            assertEquals("preserving alias parity: $sample", referencePreserving(sample),
                ProductLexicon.canonicalizeAliasesPreservingText(sample))
            assertEquals("token alias parity: $sample", referenceProduct(sample),
                ProductLexicon.canonicalizeProductText(sample))
        }
    }

    @Test fun knownLocalCategoryNamesProduceUsableStrictCatalogueQueries() {
        for ((source, canonical) in listOf("aloo" to "potato", "shimla mirch" to "capsicum",
            "gud" to "jaggery", "nariyal paani" to "coconut water", "dahi" to "curd",
            "kapde dhone ka powder" to "laundry detergent powder", "haldi" to "turmeric",
            "palak" to "spinach", "suji" to "semolina", "amrood" to "guava")) {
            val item = GroceryIntentItem(source, BigDecimal.ONE, "count", null, "unspecified",
                emptyList(), emptyList(), source, false)
            assertTrue(groceryIntentGroundingConcerns(source, listOf(item)).isEmpty())
            val parsed = item.toParsedItem()
            assertEquals(canonical, parsed.query)
            assertEquals(source, parsed.rawText)
            assertEquals(canonical, parsed.strictMatchPhrase)
            assertTrue(swiggyMatchesProductIdentity(parsed, canonical))
            assertTrue(swiggyMatchesProductIdentity(parsed, source))
        }
    }

    @Test fun aliasNormalizationPreservesMeasuresAndWordBoundaries() {
        assertEquals("Brand potato (0.5 kg)", ProductLexicon.canonicalizeAliasesPreservingText("Brand aloo (0.5 kg)"))
        assertEquals("protein bar 12.5 g protein", ProductLexicon.canonicalizeAliasesPreservingText("protein bar 12.5 g protein"))
        assertEquals("aloof", ProductLexicon.canonicalizeAliasesPreservingText("aloof"))
        val item = ParsedItem(rawText = "red capsicum", query = "red capsicum", strictMatchPhrase = "red capsicum")
        assertTrue(swiggyMatchesProductIdentity(item, "red shimla mirch"))
        assertFalse(swiggyMatchesProductIdentity(item, "green shimla mirch"))
        val sized = item.copy(query = "red capsicum 500 g", strictMatchPhrase = "red capsicum 500 g")
        assertFalse(swiggyMatchesProductIdentity(sized, "red shimla mirch 1 kg"))
    }

    @Test fun verifiedRegionalAliasesPreserveTheWholeProductPhrase() {
        listOf("sebu" to "apple", "hasiru menasinakaayi" to "green chilli",
            "hasiru menasinakayi" to "green chilli", "dalimbe" to "pomegranate",
            "daalimbe" to "pomegranate").forEach { (source, canonical) ->
            assertEquals(canonical, ProductLexicon.canonicalizeProductText(source))
            assertEquals("$canonical (0.5 kg)",
                ProductLexicon.canonicalizeAliasesPreservingText("$source (0.5 kg)"))
            assertEquals(intentEvidenceTokens(canonical), intentEvidenceTokens(source))
        }
        assertEquals("menasinakayi", ProductLexicon.canonicalizeProductText("menasinakayi"))
        assertEquals("dalimbex", ProductLexicon.canonicalizeProductText("dalimbex"))
        assertEquals("nariyalx paani", ProductLexicon.canonicalizeProductText("nariyalx paani"))
        assertEquals("coconut water coconut", ProductLexicon.canonicalizeProductText("nariyal paani nariyal"))
        listOf("dalimbe_suffix", "dalimbe's", "dalimbe\u0301", "hasiru;menasinakayi").forEach { source ->
            assertEquals(source, ProductLexicon.canonicalizeAliasesPreservingText(source))
        }
        assertEquals("coconut; paani", ProductLexicon.canonicalizeAliasesPreservingText("nariyal; paani"))
        assertEquals(intentEvidenceTokens("pomegranates"), intentEvidenceTokens("dalimbe"))
        assertEquals(intentEvidenceTokens("green chillies"), intentEvidenceTokens("hasiru menasinakayi"))
    }

    @Test fun wholeKannadaBananaPhrasesAreCategoryOnlyAliases() {
        listOf("baalehannu", "baale hannu", "balehannu", "bale hannu", "ಬಾಳೆಹಣ್ಣು").forEach { source ->
            assertEquals("banana", ProductLexicon.canonicalizeProductText(source))
            assertEquals("banana", ProductLexicon.canonicalizeAliasesPreservingText(source))
            assertEquals("banana", ProductLexicon.canonicalizeAliasesPreservingText(source.uppercase()))
            assertEquals(intentEvidenceTokens("banana"), intentEvidenceTokens(source))
        }

        // Keep other literal qualifiers; the fruit category does not create a
        // named variety, count, package, or prepared form of its own.
        assertEquals("yelakki banana", ProductLexicon.canonicalizeAliasesPreservingText("yelakki baalehannu"))
        assertEquals("yelakki banana", ProductLexicon.canonicalizeAliasesPreservingText("yelakki banana"))
        assertEquals("banana powder", ProductLexicon.canonicalizeAliasesPreservingText("baalehannu powder"))
        assertEquals("dried banana", ProductLexicon.canonicalizeAliasesPreservingText("dried baalehannu"))
        assertEquals("two banana", ProductLexicon.canonicalizeAliasesPreservingText("two baalehannu"))
        assertEquals("banana", ProductLexicon.canonicalizeAliasesPreservingText("baalehannu"))

        listOf("baale", "bale").forEach { ambiguous ->
            assertEquals(ambiguous, ProductLexicon.canonicalizeProductText(ambiguous))
            assertEquals(ambiguous, ProductLexicon.canonicalizeAliasesPreservingText(ambiguous))
        }
        listOf("baalehannu_suffix", "baalehannu's", "baalehannu\u0301", "prefixbaalehannu", "baalehannu1",
            "baale; hannu").forEach { source ->
            assertEquals(source, ProductLexicon.canonicalizeAliasesPreservingText(source))
        }

        val aliased = GroceryIntentItem("yelakki baalehannu", BigDecimal.ONE, "count", null, "unspecified",
            emptyList(), emptyList(), "yelakki baalehannu", false).toParsedItem()
        val canonical = GroceryIntentItem("yelakki banana", BigDecimal.ONE, "count", null, "unspecified",
            emptyList(), emptyList(), "yelakki banana", false).toParsedItem()
        assertEquals("yelakki banana", aliased.query)
        assertEquals(canonical.query, aliased.query)
        assertEquals("yelakki baalehannu", aliased.rawText)
        assertTrue(swiggyMatchesProductIdentity(aliased, requireNotNull(canonical.strictMatchPhrase)))

        val generic = GroceryIntentItem("baalehannu", BigDecimal.ONE, "count", null, "unspecified",
            emptyList(), emptyList(), "baalehannu", false).toParsedItem()
        assertEquals("banana", generic.query)
        assertFalse(generic.query.contains("yelakki"))
    }

    @Test fun sharedHindiAliasesProduceCanonicalStrictCatalogueQueriesWithoutVariantInference() {
        val aliases = listOf(
            "chai patti" to "tea leaves", "santra" to "orange", "santre" to "orange",
            "laal mirch" to "red chilli", "kali mirch" to "black pepper",
            "hari mirch" to "green chilli", "methi" to "fenugreek",
        )
        aliases.forEach { (source, canonical) ->
            val item = GroceryIntentItem(source, BigDecimal.ONE, "count", null, "unspecified",
                emptyList(), emptyList(), source, false)
            assertTrue(source, groceryIntentGroundingConcerns(source, listOf(item)).isEmpty())
            val parsed = item.toParsedItem()
            assertEquals(source, source, parsed.rawText)
            assertEquals(source, canonical, parsed.query)
            assertEquals(source, canonical, parsed.strictMatchPhrase)
            assertTrue(source, swiggyMatchesProductIdentity(parsed, canonical))
            assertFalse(source, swiggyMatchesProductIdentity(parsed, wrongCandidateForAlias(source)))
        }
        listOf(
            "methi leaves" to "fenugreek leaves", "methi seeds" to "fenugreek seeds",
            "dried methi" to "dried fenugreek", "methi juice" to "fenugreek juice",
            "laal mirch powder" to "red chilli powder",
        ).forEach { (source, canonical) ->
            assertEquals(source, canonical, ProductLexicon.canonicalizeAliasesPreservingText(source))
        }
        listOf(
            "methi_suffix" to "methi_suffix", "methi's" to "methi's", "methi\u0301" to "methi\u0301",
            "chai; patti" to "tea; patti", "laal; mirch" to "red; mirch",
        ).forEach { (source, canonical) ->
            assertEquals(source, canonical, ProductLexicon.canonicalizeAliasesPreservingText(source))
        }
    }

    @Test fun verifiedGroceryAliasesPreserveExactPhrasesAndDoNotInventForms() {
        val aliases = listOf(
            "shunti" to "ginger",
            "elaichi" to "cardamom",
            "dalchini" to "cinnamon",
            "kittale" to "orange",
            "kittale hannu" to "orange",
        )
        aliases.forEach { (source, canonical) ->
            assertEquals(source, canonical, ProductLexicon.canonicalizeProductText(source))
            assertEquals(source, "$canonical (0.5 kg)",
                ProductLexicon.canonicalizeAliasesPreservingText("$source (0.5 kg)"))
            assertEquals(source, intentEvidenceTokens(canonical), intentEvidenceTokens(source))
            val item = GroceryIntentItem(source, BigDecimal.ONE, "count", null, "unspecified",
                emptyList(), emptyList(), source, false).toParsedItem()
            assertEquals(source, canonical, item.query)
            assertEquals(source, source, item.rawText)
            assertEquals(source, canonical, item.strictMatchPhrase)
            assertTrue(source, swiggyMatchesProductIdentity(item, canonical))
        }

        listOf("shuntix", "elaichi's", "dalchini\u0301", "kittalx").forEach { source ->
            assertEquals(source, source, ProductLexicon.canonicalizeAliasesPreservingText(source))
        }
        assertEquals("orange; hannu", ProductLexicon.canonicalizeAliasesPreservingText("kittale; hannu"))

        val bareAliases = listOf(
            "shunti" to "green ginger",
            "elaichi" to "black cardamom",
            "dalchini" to "whole cinnamon",
            "dalchini" to "cinnamon powder",
            "kittale hannu" to "fresh orange",
            "elaichi" to "dried cardamom",
        )
        bareAliases.forEach { (source, ungroundedForm) ->
            // The interpreter cannot claim the user requested an unstated form.
            // A generic category can still produce a more specific suggestion
            // for the separate basket review; that is not source evidence.
            assertTrue("$source -> $ungroundedForm", groceryIntentGroundingConcerns(source, listOf(
                GroceryIntentItem(ungroundedForm, BigDecimal.ONE, "count", null, "unspecified",
                    emptyList(), emptyList(), source, false),
            )).isNotEmpty())
        }

        val explicitQualifiers = listOf(
            Triple("green shunti", "green ginger", "ginger"),
            Triple("green elaichi", "green cardamom", "cardamom"),
            Triple("whole dalchini", "whole cinnamon", "cinnamon"),
            Triple("red kittale", "red orange", "orange"),
        )
        explicitQualifiers.forEach { (source, exact, dropped) ->
            val exactItem = GroceryIntentItem(source, BigDecimal.ONE, "count", null, "unspecified",
                emptyList(), emptyList(), source, false).toParsedItem()
            assertTrue("$source -> $exact", swiggyMatchesProductIdentity(exactItem, exact))
            assertTrue("$source -> $exact", groceryIntentGroundingConcerns(source, listOf(
                GroceryIntentItem(exact, BigDecimal.ONE, "count", null, "unspecified",
                    emptyList(), emptyList(), source, false),
            )).isEmpty())

            assertFalse("$source -> candidate missing $dropped", swiggyMatchesProductIdentity(exactItem, dropped))
            assertTrue("$source -> dropped $dropped", groceryIntentGroundingConcerns(source, listOf(
                GroceryIntentItem(dropped, BigDecimal.ONE, "count", null, "unspecified",
                    emptyList(), emptyList(), source, false),
            )).isNotEmpty())
        }
    }

    @Test fun produceVarietyPhrasesOverrideSpiceAliasIdempotently() {
        listOf("banana", "bananas", "kela", "kele", "litchi", "lychee").forEach { head ->
            val expected = "elaichi " + if (head in setOf("bananas", "kela", "kele")) "banana" else head
            listOf("elaichi $head", "$head elaichi").forEach { source ->
                assertEquals(expected, ProductLexicon.canonicalizeAliasesPreservingText(source.uppercase()))
                assertEquals(expected, ProductLexicon.canonicalizeAliasesPreservingText(expected))
                assertEquals(expected, ProductLexicon.canonicalizeProductText(source))
            }
        }
        assertEquals("cardamom tea", ProductLexicon.canonicalizeAliasesPreservingText("elaichi tea"))
        assertEquals("cardamom; banana", ProductLexicon.canonicalizeAliasesPreservingText("elaichi; banana"))
        assertEquals("orange juice", ProductLexicon.canonicalizeAliasesPreservingText("kittale hannu juice"))
        val item = GroceryIntentItem("elaichi kela", BigDecimal.ONE, "count", null, "unspecified",
            emptyList(), emptyList(), "one elaichi kela", false).toParsedItem()
        assertTrue(swiggyMatchesProductIdentity(item, "Elaichi Banana"))
        assertFalse(swiggyMatchesProductIdentity(item, "Cardamom Banana"))
    }

    @Test fun ingredientTranslationsCannotMatchUnrequestedDerivedProducts() {
        listOf(
            "laal mirch" to "Red Chilli", "hari mirch" to "Green Chilli",
            "kali mirch" to "Black Pepper", "methi" to "Fenugreek",
            "santre" to "Orange", "chai patti" to "Tea Leaves",
        ).forEach { (source, label) ->
            val item = GroceryIntentItem(source, BigDecimal.ONE, "count", null, "unspecified",
                emptyList(), emptyList(), source, false).toParsedItem()
            listOf("Sauce", "Pickles", "Juice", "Capsules", "Shampoo").forEach { derivative ->
                assertFalse("$source -> $label $derivative", swiggyMatchesProductIdentity(item, "$label $derivative"))
                assertFalse("$source -> $derivative $label", swiggyMatchesProductIdentity(item, "$derivative $label"))
                val explicit = item.copy(query = "${item.query} $derivative", strictMatchPhrase = "${item.query} $derivative")
                assertTrue(swiggyMatchesProductIdentity(explicit, "$label $derivative"))
                assertFalse(swiggyMatchesProductIdentity(explicit, "Tablets $label $derivative"))
            }
        }
        val chilli = ParsedItem(rawText = "hari mirch", query = "green chilli", quantity = Quantity.Count(1),
            strictMatchPhrase = "green chilli")
        assertTrue(swiggyMatchesProductIdentity(chilli, "Fresh Green Chillies"))
    }

    private fun wrongCandidateForAlias(source: String): String = when (source) {
        "chai patti" -> "black tea"
        "santra", "santre" -> "green chilli"
        "laal mirch" -> "green chilli"
        "kali mirch" -> "white pepper"
        "hari mirch" -> "red chilli"
        else -> "red chilli"
    }

    @Test fun unsupportedKangniStaysOutsideGlobalAliasesAndRequiresEnglishIdentityEvidence() {
        listOf("kangni").forEach { source ->
            assertEquals(source, ProductLexicon.canonicalizeProductText(source))
            assertEquals("Brand $source (0.5 kg)",
                ProductLexicon.canonicalizeAliasesPreservingText("Brand $source (0.5 kg)"))
            assertEquals(setOf(source), intentEvidenceTokens(source))
        }
        listOf("kangnix", "methi_suffix", "elaichi's", "dalchini\u0301", "kangni; millet")
            .forEach { source ->
                assertEquals(source, ProductLexicon.canonicalizeAliasesPreservingText(source))
            }

        fun grounded(source: String, name: String) = groceryIntentGroundingConcerns(source, listOf(
            GroceryIntentItem(name, null, "unspecified", null, "unspecified", emptyList(), emptyList(),
                source, false),
        ))
        assertEquals(listOf("one kangni"), grounded("one kangni", "foxtail millet"))
        assertTrue(grounded("one elaichi", "cardamom").isEmpty())
        assertTrue(grounded("one dalchini", "cinnamon").isEmpty())
        assertEquals(listOf("one green elaichi"), grounded("one green elaichi", "cardamom"))
        assertEquals(listOf("one whole dalchini"), grounded("one whole dalchini", "cinnamon"))
        assertTrue(grounded("one methi", "fenugreek").isEmpty())
        assertEquals(listOf("one methi"), grounded("one methi", "fenugreek leaves"))
    }
}
