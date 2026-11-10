package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline source-to-review checks for the negotiated typed-ingredient path. */
class TypedIngredientEndToEndTest {
    private data class Prepared(val item: com.example.beta.automation.ParsedItem, val predicates: List<IngredientPredicate>)

    @Test
    fun sourceDtoEchoRawSkuProofAssessmentAndQuantityReviewStayBound() {
        val prepared = preparedSoap()
        val (item, predicates) = prepared
        assertEquals(listOf(IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")), predicates)
        assertTrue(item.query.contains("soap", ignoreCase = true))
        assertTrue(item.strictMatchPhrase.orEmpty().contains("soap", ignoreCase = true))
        assertEquals(predicates, item.ingredientProvenance?.predicates())

        val response = SwiggyMcpClient.parseRecommendationBatch(
            recommendationJson(item, predicates, title = "Fragrance-free soap 100 g", rawName = "Fragrance-free soap 100 g",
                extraRawField = "No added palm oil"),
            listOf(predicates),
        ).single()
        val candidate = response.candidates.single()
        assertNotNull(candidate.catalogueIngredients)
        val checked = assessSwiggyIntentQuantity(item, response)
        assertTrue(isSwiggyCandidateAllowed(checked, candidate))
        assertUnqualifiedCountNeedsReview(checked, candidate, 1)
        val requested = swiggyPreviewItemFromParsedItem(candidate, checked)
        assertEquals("spin-soap", requested.spinId)
        assertEquals("sku-soap", requested.skuId)
        assertEquals(1, requested.quantity)
    }

    @Test
    fun changedMissingExtraOrWrongOperatorEchoIsRejected() {
        val (item, predicates) = preparedSoap()
        val correct = recommendationJson(item, predicates, "Fragrance-free soap", "Fragrance-free soap")
        val echo = "\"ingredientPredicates\":[{\"operator\":\"absent\",\"operand\":\"fragrance\"}]"
        val missingVersion = correct.replace("\"ingredientPredicateVersion\":1,", "")
        val missingRowEcho = correct.replace("$echo,", "")
        val changed = correct.replace("\"operand\":\"fragrance\"", "\"operand\":\"salt\"")
        val extra = correct.replace(echo,
            "\"ingredientPredicates\":[{\"operator\":\"absent\",\"operand\":\"fragrance\"},{\"operator\":\"absent\",\"operand\":\"salt\"}]")
        val wrongOperator = correct.replace("\"operator\":\"absent\"", "\"operator\":\"no_addition\"")
        listOf(missingVersion, missingRowEcho, changed, extra, wrongOperator).forEach { body ->
            assertThrows(RuntimeException::class.java) {
                SwiggyMcpClient.parseRecommendationBatch(body, listOf(predicates))
            }
        }
    }

    @Test
    fun malformedDuplicateOrWrongSkuRawEvidenceCannotPassFinalCartGate() {
        val (item, predicates) = preparedSoap()
        val badEvidence = listOf(
            evidenceJson("other-sku", "Fragrance-free soap"),
            evidenceJson("sku-soap", "Fragrance-free soap", extraKey = true),
            """{"version":1,"source":"search_variation","spinId":"spin-soap","skuId":"sku-soap","fields":{"name":"Fragrance-free soap","name":"Fragrance-free soap"}}""",
        )
        badEvidence.forEach { evidence ->
            val recs = parseRecommendations(item, predicates,
                recommendationJson(item, predicates, "Fragrance-free soap", "Fragrance-free soap", evidenceOverride = evidence))
            val candidate = recs.candidates.single()
            assertTrue(candidate.catalogueIngredientsInvalid)
            val checked = assessSwiggyIntentQuantity(item, recs)
            assertFalse(isSwiggyCandidateAllowed(checked, candidate))
        }
    }

    @Test
    fun displayContradictionsAndCrossFieldFragmentsOnlyDeny() {
        val (item, predicates) = preparedSoap()
        listOf("*", "\u2020", "\u2021").forEach { marker ->
            val annotated = parseRecommendations(item, predicates, recommendationJson(
                item, predicates, "Fragrance-free soap$marker", "Fragrance-free soap",
            ))
            val checked = assessSwiggyIntentQuantity(item, annotated)
            assertFalse(isSwiggyCandidateAllowed(checked, annotated.candidates.single()))
            assertFalse(swiggyIntentQuantityCandidateChecked(checked, annotated.candidates.single()))
        }
        val conflicting = parseRecommendations(item, predicates,
            recommendationJson(item, predicates, "Soap with fragrance", "Fragrance-free soap"))
        val conflictingCandidate = conflicting.candidates.single()
        assertFalse(isSwiggyCandidateAllowed(assessSwiggyIntentQuantity(item, conflicting), conflictingCandidate))

        val split = parseRecommendations(item, predicates, recommendationJson(
            item, predicates, "Fragrance-free soap", "No", extraRawField = "fragrance",
        ))
        val splitCandidate = split.candidates.single()
        assertFalse(isSwiggyCandidateAllowed(assessSwiggyIntentQuantity(item, split), splitCandidate))
    }

    @Test
    fun editsToSourceProofOrSkuEvidenceAfterAssessmentInvalidateFinalSelection() {
        val (item, predicates) = preparedSoap()
        val recs = parseRecommendations(item, predicates,
            recommendationJson(item, predicates, "Fragrance-free soap", "Fragrance-free soap"))
        val candidate = recs.candidates.single()
        val assessed = assessSwiggyIntentQuantity(item, recs)
        assertTrue(isSwiggyCandidateAllowed(assessed, candidate))

        assertFalse(isSwiggyCandidateAllowed(assessed.copy(avoidPhrases = emptyList()), candidate))
        assertFalse(isSwiggyCandidateAllowed(assessed.copy(ingredientProvenance = null), candidate))
        val changedEvidence = parseRecommendations(item, predicates, recommendationJson(
            item, predicates, "Fragrance-free soap", "No fragrance soap",
        )).candidates.single()
        assertFalse(swiggyIntentQuantityCandidateChecked(assessed, changedEvidence))
    }

    @Test fun typedSugarCannotBypassTheExistingSweetenedContradictionVeto() {
        val (item, predicates) = preparedItem("iced tea", "sugar")
        val good = parseRecommendations(item, predicates,
            recommendationJson(item, predicates, "Sugar Free Iced Tea", "Sugar Free Iced Tea"))
        assertTrue(isSwiggyCandidateAllowed(assessSwiggyIntentQuantity(item, good), good.candidates.single()))
        for (contradiction in listOf("Sweetened", "Sweetened with honey", "With sugar")) {
            val candidate = good.candidates.single().copy(subtitle = contradiction)
            val checked = assessSwiggyIntentQuantity(item, good.copy(candidates = listOf(candidate)))
            assertFalse(isSwiggyCandidateAllowed(checked, candidate))
            assertFalse(swiggyIntentQuantityCandidateChecked(checked, candidate))
            val rawConflict = parseRecommendations(item, predicates,
                recommendationJson(item, predicates, "Sugar Free Iced Tea", "Sugar Free Iced Tea",
                    extraRawField = contradiction))
            assertFalse(isSwiggyCandidateAllowed(assessSwiggyIntentQuantity(item, rawConflict), rawConflict.candidates.single()))
        }
    }

    private fun preparedSoap(): Prepared = preparedItem("soap", "fragrance")

    private fun preparedItem(product: String, operand: String): Prepared {
        val instruction = "one $product; No $operand in the $product"
        val primary = "one $product"
        val relation = "No $operand in the $product"
        val relationStart = instruction.indexOf(relation)
        val row = """{"row_index":0,"item":{"name":"$product","quantity":1,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":["$operand"],"source_text":"$primary","supporting_text":[],"needs_clarification":false,"quantity_text":"one","pack_text":null},"evidence":{"primary":${quote(primary, 0)},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
        val ingredientReceipt = """{"source":${quote(relation, relationStart)},"member_indices":[0],"predicates":[${predicateJson(IngredientPredicateOperator.ABSENT, operand) }]}"""
        val body = """{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"$instruction","source_item_count":1,"ready_rows":[$row],"blocked_rows":[],"ingredient_obligations":[$ingredientReceipt]}"""
        val dto = GroceryIntentReviewV2.parse(
            (JsonParser(body).parseStrict() as JsonValue.Obj).members, instruction,
        )
        val prepared = dto.prepareForReview(
            allowPropertyObligations = true, allowIngredientPredicates = true,
            allowTypedIngredientPredicates = true,
        )
        assertEquals(emptyList<String>(), prepared.unresolvedText)
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        return Prepared(item, requireNotNull(item.ingredientProvenance).predicates())
    }

    private fun parseRecommendations(
        item: com.example.beta.automation.ParsedItem,
        predicates: List<IngredientPredicate>,
        body: String,
    ) = SwiggyMcpClient.parseRecommendationBatch(body, listOf(predicates)).single()

    private fun recommendationJson(
        item: com.example.beta.automation.ParsedItem,
        predicates: List<IngredientPredicate>,
        title: String,
        rawName: String,
        extraRawField: String? = null,
        evidenceOverride: String? = null,
    ): String {
        val fields = "\"name\":${quoted(rawName)}" + (extraRawField?.let { ",\"title\":${quoted(it)}" } ?: "")
        val evidence = evidenceOverride ?: """{"version":1,"source":"search_variation","spinId":"spin-soap","skuId":"sku-soap","fields":{$fields}}"""
        val candidate = """{"spinId":"spin-soap","skuId":"sku-soap","label":${quoted(title)},"catalogueIngredients":$evidence}"""
        val echo = predicates.joinToString(",") { predicateJson(it.operator, it.operand) }
        return """{"ingredientPredicateVersion":1,"results":[{"query":${quoted(item.query)},"ingredientPredicates":[$echo],"candidates":[$candidate]}]}"""
    }

    private fun evidenceJson(sku: String, value: String, extraKey: Boolean = false): String =
        """{"version":1,"source":"search_variation","spinId":"spin-soap","skuId":"$sku","fields":{"name":${quoted(value)}${if (extraKey) ",\"unexpected\":\"No fragrance\"" else ""}}}"""

    private fun predicateJson(operator: IngredientPredicateOperator, operand: String): String {
        IngredientPredicate(operator, operand)
        val name = if (operator == IngredientPredicateOperator.ABSENT) "absent" else "no_addition"
        return """{"operator":"$name","operand":"$operand"}"""
    }

    private fun quote(text: String, start: Int): String =
        """{"text":${quoted(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""

    private fun quoted(text: String): String = buildString {
        append('"')
        text.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
