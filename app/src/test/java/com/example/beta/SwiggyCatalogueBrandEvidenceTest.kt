package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCatalogueBrandEvidenceTest {
    @Test fun acceptsOnlyExactCandidateBoundSearchVariationEvidence() {
        val candidate = parseCandidate(evidenceJson())

        assertEquals("Arbor Farm", candidate.catalogueBrand?.name)
        assertEquals("spin-1", candidate.catalogueBrand?.spinId)
        assertEquals("sku-1", candidate.catalogueBrand?.skuId)
        assertEquals("search_variation", candidate.catalogueBrand?.source)
        assertEquals("Arbor Farm hot sauce", candidate.label)
        assertEquals("500 ml", candidate.subtitle)
    }

    @Test fun absentNullWrongShapeOrExtraKeysDoNotCreateBrandEvidence() {
        listOf(
            null,
            "null",
            "\"Arbor Farm\"",
            "[]",
            "{}",
            "{\"name\":\"Arbor Farm\",\"spinId\":\"spin-1\",\"skuId\":\"sku-1\",\"source\":\"search_variation\",\"extra\":\"x\"}",
        ).forEach { raw ->
            assertNull("unexpected brand evidence for $raw", parseCandidate(raw).catalogueBrand)
        }
    }

    @Test fun nonStringFieldsAndInvalidIdsOrSourceAreRejected() {
        val invalid = listOf(
            "{\"name\":7,\"spinId\":\"spin-1\",\"skuId\":\"sku-1\",\"source\":\"search_variation\"}",
            "{\"name\":true,\"spinId\":\"spin-1\",\"skuId\":\"sku-1\",\"source\":\"search_variation\"}",
            "{\"name\":\"Arbor Farm\",\"spinId\":false,\"skuId\":\"sku-1\",\"source\":\"search_variation\"}",
            "{\"name\":\"Arbor Farm\",\"spinId\":\"spin-1\",\"skuId\":3,\"source\":\"search_variation\"}",
            "{\"name\":\"Arbor Farm\",\"spinId\":\"spin-1\",\"skuId\":\"sku-1\",\"source\":false}",
            evidenceJson(spinId = "other"),
            evidenceJson(skuId = "other"),
            evidenceJson(spinId = " spin-1"),
            evidenceJson(spinId = "spin-1 "),
            evidenceJson(skuId = " sku-1"),
            evidenceJson(skuId = "sku-1 "),
            evidenceJson(source = "search"),
        )
        invalid.forEach { raw ->
            assertNull("unexpected brand evidence for $raw", parseCandidate(raw).catalogueBrand)
        }
    }

    @Test fun brandNameMustBeTrimmedNonblankControlFreeAndBounded() {
        listOf(
            evidenceJson(name = ""),
            evidenceJson(name = "   "),
            evidenceJson(name = " Arbor Farm"),
            evidenceJson(name = "Arbor Farm "),
            evidenceJson(name = "bad\u0001name"),
            evidenceJson(name = "x".repeat(121)),
        ).forEach { raw ->
            assertNull("unexpected brand evidence for $raw", parseCandidate(raw).catalogueBrand)
        }
        assertNotNull(parseCandidate(evidenceJson(name = "x".repeat(120))).catalogueBrand)
    }

    @Test fun aliasesOrSyntheticFallbackIdsCannotBindEvidence() {
        val aliasId = parseCandidate(evidenceJson(), spinField = "\"id\":\"spin-1\"")
        val missingSku = parseCandidate(evidenceJson(), skuField = null)

        assertEquals("spin-1", aliasId.spinId)
        assertNull(aliasId.catalogueBrand)
        assertNull(missingSku.catalogueBrand)
    }

    @Test fun recursiveBrandMetadataCannotOverrideAliasIdsOrTheCandidateTitle() {
        val parsed = parseRawCandidate(
            """{"id":"spin-outer","sku_id":"sku-outer","title":"Original product title","catalogueBrand":{"name":"Nested brand","spinId":"nested-spin","skuId":"nested-sku","source":"search_variation"}}""",
        )
        val candidate = parsed.candidates.single()

        assertEquals("spin-outer", candidate.spinId)
        assertEquals("sku-outer", candidate.skuId)
        assertEquals("Original product title", candidate.label)
        assertNull(candidate.catalogueBrand)
    }

    @Test fun brandMetadataCannotSupplyMissingCandidateTitleOrIds() {
        val missingIds = parseRawCandidate(
            """{"label":"Original product title","catalogueBrand":{"name":"Arbor Farm","spinId":"spin-1","skuId":"sku-1","source":"search_variation"}}""",
        )
        val missingTitle = parseRawCandidate(
            """{"id":"outer-spin","sku_id":"outer-sku","productName":"Provider title","catalogueBrand":{"name":"Arbor Farm","spinId":"outer-spin","skuId":"outer-sku","source":"search_variation"}}""",
        )

        assertTrue(missingIds.candidates.isEmpty())
        assertNull(missingIds.suggested)
        val fallbackCandidate = missingTitle.candidates.single()
        assertEquals("outer-spin", fallbackCandidate.spinId)
        assertEquals("outer-sku", fallbackCandidate.skuId)
        assertEquals("Suggestion 1", fallbackCandidate.label)
        assertNull(fallbackCandidate.catalogueBrand)
    }

    @Test fun nestedMalformedSuggestedFlagCannotMarkCandidateSuggested() {
        val parsed = parseRawCandidate(
            """{"spinId":"spin-1","skuId":"sku-1","label":"Original product title","catalogueBrand":{"name":"Arbor Farm","spinId":"spin-1","skuId":"sku-1","source":"search_variation","suggested":true}}""",
        )

        assertFalse(parsed.candidates.single().suggested)
        assertNull(parsed.suggested)
        assertNull(parsed.candidates.single().catalogueBrand)
    }

    @Test fun negotiatedBatchRequiresExactBrandEchoAndSameSkuEvidence() {
        val expected = listOf("Maker", null)
        val response = brandBatch(
            listOf(
                brandRow("Maker", candidates = listOf(candidateJson("spin-1", "sku-1", "Maker")),
                    suggested = candidateJson("spin-1", "sku-1", "Maker")),
                brandRow(null, candidates = listOf(candidateJson("spin-2", "sku-2", null))),
            ),
        )

        val parsed = SwiggyMcpClient.parseRecommendationBatch(response, expectedBrands = expected)
        assertEquals(2, parsed.size)
        assertEquals("Maker", parsed[0].candidates.single().catalogueBrand?.name)
        assertEquals("Maker", parsed[0].suggested?.catalogueBrand?.name)
        assertNull(parsed[1].candidates.single().catalogueBrand)
    }

    @Test fun negotiatedBatchRejectsMissingOrMismatchedBrandReceipt() {
        val maker = candidateJson("spin-1", "sku-1", "Maker")
        val malformed = listOf(
            brandBatch(listOf(brandRow("Maker", listOf(maker))), version = null),
            brandBatch(listOf(brandRow("Maker", listOf(maker))), version = 2),
            brandBatch(listOf(brandRow("Maker", listOf(maker), includeEcho = false))),
            brandBatch(listOf(brandRow("Other", listOf(maker)))),
            brandBatch(listOf(brandRow(null, listOf(maker)))),
        )
        malformed.forEach { body ->
            assertTrue(runCatching {
                SwiggyMcpClient.parseRecommendationBatch(body, expectedBrands = listOf("Maker"))
            }.isFailure)
        }
    }

    @Test fun negotiatedBatchVetoesEveryCandidateAndSuggestionWithoutExactBoundMaker() {
        val exact = candidateJson("spin-1", "sku-1", "Maker")
        val unbranded = candidateJson("spin-2", "sku-2", null)
        val wrongBrand = candidateJson("spin-2", "sku-2", "Other")
        val wrongSpin = candidateJson("spin-2", "sku-2", "Maker", evidenceSpin = "elsewhere")
        val wrongSku = candidateJson("spin-2", "sku-2", "Maker", evidenceSku = "elsewhere")
        val malformed = listOf(
            brandBatch(listOf(brandRow("Maker", listOf(exact, unbranded)))),
            brandBatch(listOf(brandRow("Maker", listOf(exact, wrongBrand)))),
            brandBatch(listOf(brandRow("Maker", listOf(exact, wrongSpin)))),
            brandBatch(listOf(brandRow("Maker", listOf(exact, wrongSku)))),
            brandBatch(listOf(brandRow("Maker", listOf(exact), suggested = unbranded))),
            brandBatch(listOf(brandRow("Maker", listOf(exact), suggested = wrongBrand))),
        )
        malformed.forEach { body ->
            assertTrue(runCatching {
                SwiggyMcpClient.parseRecommendationBatch(body, expectedBrands = listOf("Maker"))
            }.isFailure)
        }
    }

    @Test fun omittedExpectedBrandsPreservesLegacyBatchParsing() {
        val body = """{"results":[{"candidates":[${candidateJson("spin-1", "sku-1", null)}]}]}"""
        val parsed = SwiggyMcpClient.parseRecommendationBatch(body)
        assertEquals(1, parsed.size)
        assertNull(parsed.single().candidates.single().catalogueBrand)
    }

    @Test fun attachedBrandEvidenceDoesNotChangeTitleOrSatisfyFromRelation() {
        val candidate = parseCandidate(evidenceJson())
        val request = "hot sauce from Arbor Farm"
        val item = ParsedItem(
            rawText = "one bottle hot sauce from Arbor Farm",
            query = request,
            quantity = Quantity.Count(1),
            strictMatchPhrase = request,
        )

        assertEquals("Arbor Farm hot sauce", candidate.label)
        assertFalse(isSwiggyCandidateAllowed(item, candidate))
        assertTrue(candidate.catalogueBrand != null)
    }

    private fun parseCandidate(
        catalogueBrand: String?,
        spinField: String? = "\"spinId\":\"spin-1\"",
        skuField: String? = "\"skuId\":\"sku-1\"",
    ): SwiggyMcpClient.RecommendationCandidate {
        val fields = listOfNotNull(
            spinField,
            skuField,
            "\"label\":\"Arbor Farm hot sauce\"",
            "\"subtitle\":\"500 ml\"",
            catalogueBrand?.let { "\"catalogueBrand\":$it" },
        ).joinToString(",")
        return SwiggyMcpClient.parseRecommendations(
            """{"success":true,"data":{"candidates":[{$fields}]}}""",
        ).candidates.single()
    }

    private fun parseRawCandidate(candidateJson: String): SwiggyMcpClient.Recommendations =
        SwiggyMcpClient.parseRecommendations(
            """{"success":true,"data":{"candidates":[$candidateJson]}}""",
        )

    private fun brandBatch(rows: List<String>, version: Int? = 1): String {
        val versionField = version?.let { "\"requiredBrandVersion\":$it," } ?: ""
        return """{$versionField"results":[${rows.joinToString(",") }]}"""
    }

    private fun brandRow(
        requiredBrand: String?, candidates: List<String>, suggested: String? = null, includeEcho: Boolean = true,
    ): String {
        val echo = if (!includeEcho) "" else "\"requiredBrand\":${requiredBrand?.let(::jsonString) ?: "null"},"
        val suggestion = suggested?.let { ",\"suggested\":$it" } ?: ""
        return """{$echo"candidates":[${candidates.joinToString(",")}]$suggestion}"""
    }

    private fun candidateJson(
        spin: String, sku: String, brand: String?, evidenceSpin: String = spin, evidenceSku: String = sku,
    ): String {
        val evidence = brand?.let {
            ",\"catalogueBrand\":{\"name\":${jsonString(it)},\"spinId\":${jsonString(evidenceSpin)}," +
                "\"skuId\":${jsonString(evidenceSku)},\"source\":\"search_variation\"}"
        } ?: ""
        return """{"spinId":${jsonString(spin)},"skuId":${jsonString(sku)},"label":"synthetic product"$evidence}"""
    }

    private fun evidenceJson(
        name: String = "Arbor Farm",
        spinId: String = "spin-1",
        skuId: String = "sku-1",
        source: String = "search_variation",
    ): String = """{"name":${jsonString(name)},"spinId":${jsonString(spinId)},"skuId":${jsonString(skuId)},"source":${jsonString(source)}}"""

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
