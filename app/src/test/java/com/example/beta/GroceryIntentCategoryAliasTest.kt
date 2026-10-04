package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class GroceryIntentCategoryAliasTest {
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
}
