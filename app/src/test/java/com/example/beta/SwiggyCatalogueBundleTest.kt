package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for catalogue descriptions that smuggle multiple retail packs into one SKU. */
class SwiggyCatalogueBundleTest {
    @Test
    fun allRecognizedContentsUnitsRetainDistinctMultipliers() {
        listOf("tablets", "lozenges", "capsules", "sachets", "pellets", "eggs", "batteries").forEach { unit ->
            val query = "acme 24 $unit"
            assertTrue(swiggyStrictPackMatches(query, "Acme Pack of 24 $unit"))
            listOf("Acme 24 $unit x 2", "Acme 2 x 24 $unit", "Acme Pack of 24 $unit x 2").forEach { label ->
                assertFalse("$unit multiplier must remain distinct", swiggyStrictPackMatches(query, label))
                val item = parsed(query, Quantity.Count(1))
                val offered = candidate(label, "synthetic-contents")
                assertFalse(isSwiggyCandidateAllowed(item, offered))
                assertTrue(resolveSwiggyCartQuantity(item, offered) is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun completeMeasuresPreservePackFormButNotOrphanUnits() {
        listOf("milk" to "500 ml", "butter" to "250 g", "biscuits" to "6 pieces").forEach { (noun, amount) ->
            assertEquals(setOf(noun, "pack"), swiggyIdentityTokens("$noun pack of $amount"))
            assertEquals(swiggyIdentityTokens("$noun pack $amount"), swiggyIdentityTokens("$noun pack of $amount"))
            assertTrue(swiggyStrictPackMatches("$noun pack of $amount", "$noun $amount Pack"))
            assertFalse(swiggyStrictPackMatches("$noun pack of $amount", "$noun $amount x 2 Pack"))
        }
    }

    @Test
    fun aMeasuredInnerCountIsNotASecondOuterPackMultiplier() {
        listOf(
            "bottle brush 3 pieces" to "Bottle Brush Pack of 3 Pieces",
            "biscuits 6 pieces" to "Biscuits Pack of 6 Pieces",
            "tea bags 25 pcs" to "Tea Bags Pack of 25 Pieces",
        ).forEachIndexed { index, (request, label) ->
            assertTrue("same inner measure case $index", swiggyStrictPackMatches(request, label))
        }
        listOf(
            "Bottle Brush Pack of 3",
            "Bottle Brush Pack of 4 Pieces",
            "Bottle Brush 2 x 3 Pieces",
            "Bottle Brush Pack 3 Pieces x 2",
            "Bottle Brush Pack of 2, 3 Pieces",
        ).forEachIndexed { index, label ->
            assertFalse("unproved outer or different measure case $index", swiggyStrictPackMatches("bottle brush 3 pieces", label))
        }
    }

    @Test
    fun sharedCatalogueBundleFixtureMatchesTheRuntimeDenialGate() {
        val relativePath = "scripts/fixtures/catalogue_bundle_v1.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot find shared catalogue-bundle fixture at $relativePath")
        val document = objectAt(JsonParser(fixture.readText(Charsets.UTF_8)).parseStrict())
        assertEquals("catalogue-bundle-denial-v1", stringAt(document["schema"]))
        val cases = arrayAt(document["cases"])
        assertEquals(54, cases.size)
        cases.forEach { value ->
            val case = objectAt(value)
            val id = stringAt(case["id"])
            val expectedDenied = (case["denied"] as? JsonValue.Bool)?.value
                ?: error("Expected boolean denied for $id")
            assertEquals(id, expectedDenied, swiggyCatalogueHasUnresolvedBundle(
                arrayAt(case["fields"]).map(::stringAt),
            ))
        }
    }

    @Test
    fun wordDescribedBundlesDoNotMasqueradeAsOneMeasuredPackAcrossCategories() {
        val cases = listOf(
            Triple("milk", Quantity.Volume(500), "Milk 500 ml"),
            Triple("orange juice", Quantity.Volume(500), "Orange Juice 500 ml"),
            Triple("butter", Quantity.Weight(250), "Butter 250 g"),
        )
        val bundleWords = listOf("Twin Pack", "Duo Pack", "Trio Pack", "Combo Pack", "Multipack", "Bundle",
            "BOGO", "B1G1", "1+1 Offer", "2 for ₹99", "Combi Pack", "Twin\u200bPack")

        cases.forEachIndexed { categoryIndex, (query, quantity, product) ->
            val item = parsed(query, quantity)
            bundleWords.forEachIndexed { wordIndex, descriptor ->
                val candidate = candidate("$product $descriptor", "bundle-$categoryIndex-$wordIndex")
                assertFalse("$query / $descriptor", isSwiggyCandidateAllowed(item, candidate))
                assertFalse("$query / $descriptor count", isSwiggyCandidateCountCompatible(item, candidate))
                assertEquals(null, swiggyMeasuredPackQuantity(item, candidate))
                assertThrows(IllegalArgumentException::class.java) {
                    swiggyRequestedCartQuantity(item, candidate)
                }
            }
        }
    }

    @Test
    fun bundleEvidenceIsJoinedAcrossCandidateFieldsAndRecognizedInEachField() {
        val item = parsed("milk", Quantity.Volume(500))
        val splitAcrossFields = candidate("Milk 500 ml Twin", "split", variant = "Pack")
        assertFalse(isSwiggyCandidateAllowed(item, splitAcrossFields))
        assertFalse(isSwiggyCandidateCountCompatible(item, splitAcrossFields))

        val evidenceFields = listOf<(String) -> RecommendationCandidate>(
            { marker -> candidate("Milk 500 ml", "label-$marker", suffix = marker) },
            { marker -> candidate("Milk", "variant-$marker", variant = "500 ml $marker") },
            { marker -> candidate("Milk", "subtitle-$marker", subtitle = "500 ml $marker") },
        )
        listOf("Twin Pack", "Duo Pack", "Trio Pack", "Combo Pack", "Multipack", "Bundle").forEach { marker ->
            evidenceFields.forEachIndexed { index, make ->
                val candidate = make(marker)
                assertFalse("field $index / $marker", isSwiggyCandidateAllowed(item, candidate))
            }
        }
    }

    @Test
    fun existingExplicitBundlePhrasesRemainRejected() {
        val item = parsed("milk", Quantity.Volume(500))
        listOf("Milk 500 ml Pack of Two", "Milk 500 ml Buy 1 Get 1").forEachIndexed { index, label ->
            val candidate = candidate(label, "explicit-$index")
            assertEquals(null, swiggyMeasuredPackQuantity(item, candidate))
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(item, candidate)
            }
        }
    }

    @Test
    fun ordinarySinglePacksRemainValidAndNameWordsAloneAreNotBundleEvidence() {
        val measured = parsed("milk", Quantity.Volume(1000))
        val single = candidate("Milk 500 ml", "milk-single")
        assertTrue(isSwiggyCandidateAllowed(measured, single))
        assertTrue(isSwiggyCandidateCountCompatible(measured, single))
        assertEquals(2, swiggyRequestedCartQuantity(measured, single))
        assertEquals(2, swiggyMeasuredPackQuantity(measured, single))

        val count = parsed("milk", Quantity.Count(1))
        val countedSingle = candidate("Milk 500 ml", "milk-count-single")
        assertReviewedRetailProposal(count, countedSingle, 1)
        // A mutable flag is not evidence that the user requested outer SKUs.
        val flagOnly = count.copy(retailPackCount = true)
        assertTrue(resolveSwiggyCartQuantity(flagOnly, countedSingle) is SwiggyCartQuantityResolution.Held)
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(flagOnly, countedSingle)
        }

        val explicitCount = parsed("milk 500 ml", Quantity.Count(1), retailPackCount = true)
        val countedBundle = candidate("Milk 500 ml Twin Pack", "milk-count-twin")
        for (item in listOf(explicitCount, explicitCount.copy(retailPackCount = false), count)) {
            assertTrue(resolveSwiggyCartQuantity(item, countedBundle) is SwiggyCartQuantityResolution.Held)
            assertThrows(IllegalArgumentException::class.java) {
                swiggyRequestedCartQuantity(item, countedBundle)
            }
        }

        assertTrue(isSwiggyCandidateAllowed(
            parsed("twinings tea", Quantity.Volume(500)),
            candidate("Twinings Tea 500 ml", "twinings-name-control"),
        ))
        assertTrue(isSwiggyCandidateAllowed(
            parsed("duo chocolate", Quantity.Volume(500)),
            candidate("Duo Chocolate 500 ml", "duo-name-control"),
        ))
        listOf("Milk Family Pack 500 ml", "Milk Economical Pack 500 ml").forEachIndexed { index, label ->
            assertTrue(label, isSwiggyCandidateAllowed(parsed("milk", Quantity.Volume(500)), candidate(label, "ordinary-pack-$index")))
        }
    }

    private fun parsed(
        query: String,
        quantity: Quantity,
        retailPackCount: Boolean = false,
    ) = ParsedItem(
        rawText = when (quantity) {
            is Quantity.Count -> "${quantity.n} $query"
            is Quantity.Weight -> "${quantity.grams} g $query"
            is Quantity.Volume -> "${quantity.ml} ml $query"
            Quantity.Default -> query
        },
        query = query,
        quantity = quantity,
        strictMatchPhrase = query,
        retailPackCount = retailPackCount,
    )

    private fun candidate(
        label: String,
        id: String,
        variant: String? = null,
        subtitle: String? = null,
        suffix: String? = null,
    ) = RecommendationCandidate(
        spinId = id,
        skuId = id,
        label = label + (suffix?.let { " $it" } ?: ""),
        variant = variant,
        subtitle = subtitle,
    )

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")
}
