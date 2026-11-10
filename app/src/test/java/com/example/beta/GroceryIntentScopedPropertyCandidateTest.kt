package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentScopedPropertyCandidateTest {
    @Test
    fun noAddedSugarAndSugarFreeContrastSurvivesIntoCandidateFiltering() {
        val item = preparedFixtureItem("no_added_sugar_not_sugar_free")

        assertTrue(item.query.contains("no added sugar"))
        assertEquals(listOf("sugar free"), item.avoidPhrases)
        assertEquals(item.query, item.strictMatchPhrase)
        assertTrue(allowed(item, "Peanut Butter No Added Sugar"))
        assertFalse(allowed(item, "Peanut Butter No Added Sugar Sugar Free"))
        assertFalse(allowed(item, "Peanut Butter Sugar Free"))

        val sugarFreeExcluded = preparedFixtureItem("excluded_sugar_free")
        assertEquals(listOf("sugar free"), sugarFreeExcluded.avoidPhrases)
        assertTrue(allowed(sugarFreeExcluded, "Milk"))
        assertFalse(allowed(sugarFreeExcluded, "Milk Sugar Free"))
    }

    @Test
    fun lowFatRequirementRejectsFullCreamCandidate() {
        val item = preparedFixtureItem("required_low_fat")

        assertTrue(item.query.contains("low fat"))
        assertEquals(emptyList<String>(), item.avoidPhrases)
        assertEquals(item.query, item.strictMatchPhrase)
        assertTrue(allowed(item, "Low Fat Milk"))
        assertFalse(allowed(item, "Full Cream Milk"))
        assertFalse(allowed(item, "Milk"))
    }

    @Test
    fun plainRequirementRejectsFlavouredCandidate() {
        val item = preparedFixtureItem("required_plain")

        assertTrue(item.query.contains("plain"))
        assertEquals(item.query, item.strictMatchPhrase)
        assertTrue(allowed(item, "Plain Milk"))
        assertFalse(allowed(item, "Strawberry Milk"))
    }

    private fun preparedFixtureItem(caseId: String) = fixtureCase(caseId).let { case ->
        val instruction = fixtureString(case["instruction"])
        val response = GroceryIntentReviewV2Response.parse(propertyFixtureJson(case["response"]), instruction)
        val prepared = response.draft.prepareForReview(allowPropertyObligations = true)
        assertTrue("$caseId property context should be fully covered: ${prepared.unresolvedText}",
            prepared.unresolvedText.isEmpty())
        assertEquals(1, prepared.items.size)
        prepared.items.single()
    }

    private fun allowed(item: com.example.beta.automation.ParsedItem, label: String): Boolean =
        isSwiggyCandidateAllowed(item, RecommendationCandidate(
            spinId = "synthetic-${label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}",
            label = label,
        ))

    private fun fixtureCase(caseId: String): Map<String, JsonValue> {
        val relativePath = "scripts/fixtures/intent_property_wire_oct13.json"
        val fixtureFile = listOf(java.io.File("../$relativePath"), java.io.File(relativePath))
            .firstOrNull(java.io.File::isFile) ?: error("Cannot find property wire fixture")
        val root = fixtureObject(JsonParser(fixtureFile.readText(Charsets.UTF_8)).parseStrict())
        return fixtureArray(root["cases"]).map(::fixtureObject)
            .single { fixtureString(it["id"]) == caseId }
    }

    private fun fixtureObject(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected fixture object")

    private fun fixtureArray(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected fixture array")

    private fun fixtureString(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected fixture string")

}
