package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyIngredientPredicateTest {
    @Test fun sharedPredicateFixtureMatchesTypedLiteralEvidence() {
        val relativePath = "scripts/fixtures/ingredient_predicates_v1.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile) ?: error("Cannot find predicate fixture at $relativePath")
        val source = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("bed273c67a0cbbf22a3cd4c3a6bd89a880dd9fde01762675eea8857bbffcf367", sha256)
        val document = (JsonParser(source).parseStrict() as JsonValue.Obj).members
        assertEquals("ingredient-predicates-v1", (document.getValue("version") as JsonValue.Str).value)
        val cases = (document.getValue("cases") as JsonValue.Arr).items
        assertEquals(133, cases.size)
        val ids = mutableSetOf<String>()
        cases.forEach { value ->
            val case = (value as JsonValue.Obj).members
            fun string(key: String) = (case.getValue(key) as JsonValue.Str).value
            val id = string("id")
            assertTrue("Duplicate fixture ID: $id", ids.add(id))
            val predicate = IngredientPredicate(IngredientPredicateOperator.valueOf(string("operator")), string("operand"))
            assertEquals(id, (case.getValue("allowed") as JsonValue.Bool).value,
                swiggyHasIngredientPredicate(string("label"), predicate))
        }
    }

    @Test fun legacyProjectionExistsOnlyForTheExactProtectedContract() {
        val phrases = listOf("maida", "palm oil", "salt", "added salt", "added sugar", "chemicals", "preservatives",
            "artificial colours", "artificial flavours")
        phrases.forEach { phrase ->
            assertEquals(phrase, requireNotNull(IngredientPredicate.fromProtectedExclusion(phrase)).legacyExclusion())
        }
        val absent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")
        val noAddition = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "fragrance")
        assertNull(absent.legacyExclusion())
        assertNull(noAddition.legacyExclusion())
        assertNull(IngredientPredicate(IngredientPredicateOperator.ABSENT, "sugar").legacyExclusion())
        assertNull(IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "maida").legacyExclusion())
        assertTrue(swiggyHasIngredientPredicate("Soap fragrance-free", absent))
        assertFalse(swiggyHasIngredientPredicate("Soap", absent))
        assertTrue(swiggyHasIngredientPredicate("Soap no added fragrance", noAddition))
        assertFalse(swiggyHasIngredientPredicate("Soap", noAddition))
    }

    @Test fun interpretedClaimAlarmOnlyUsesExistingOperandsAndExplicitOperators() {
        assertEquals(setOf(IngredientPredicate(IngredientPredicateOperator.ABSENT, "salt")),
            swiggyIngredientClaimAlarms("Unsalted cashews"))
        assertEquals(setOf(IngredientPredicate(IngredientPredicateOperator.ABSENT, "sugar")),
            swiggyIngredientClaimAlarms("Sugar-free cereal"))
        assertEquals(setOf(IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")),
            swiggyIngredientClaimAlarms("Chips with no added salt"))

        // Negated/qualified forms and unsupported language are deliberately not
        // recognized as claims. This deny-only alarm's silence is not proof that
        // a source has no requirement or that a model interpretation is faithful.
        assertTrue(swiggyIngredientClaimAlarms("Maybe gluten-free oats").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("Not gluten-free oats").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("बिना नमक के चने").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("Unsweetened cocoa").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("around 500 g unsalted butter").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("sugar free or plain biscuits").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("unsalted* butter").isEmpty())
        assertTrue(swiggyIngredientClaimAlarms("500 g unsalted butter").isNotEmpty())
    }

    @Test fun sharedDenyOnlyClaimFloorFixtureMatchesAlarmImplementation() {
        val relativePath = "scripts/fixtures/ingredient_claim_floor_v1.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile) ?: error("Cannot find claim-floor fixture at $relativePath")
        val source = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("c07396ce55b0066fe25c128c545c9432163ff98e06a8eaa414ba88ebd113c55d", sha256)
        val document = (JsonParser(source).parseStrict() as JsonValue.Obj).members
        assertEquals("deny-only-ingredient-claim-alarms-v1",
            (document.getValue("schema") as JsonValue.Str).value)
        assertEquals("Alarm parity only; not semantic intent or dietary proof",
            (document.getValue("claim") as JsonValue.Str).value)
        val cases = (document.getValue("cases") as JsonValue.Arr).items
        assertEquals(17, cases.size)
        for (value in cases) {
            val case = (value as JsonValue.Obj).members
            val text = (case.getValue("text") as JsonValue.Str).value
            val expected = (case.getValue("alarms") as JsonValue.Arr).items.map { entry ->
                val alarm = (entry as JsonValue.Obj).members
                val operator = when ((alarm.getValue("operator") as JsonValue.Str).value) {
                    "absent" -> IngredientPredicateOperator.ABSENT
                    "no_addition" -> IngredientPredicateOperator.NO_ADDITION
                    else -> error("Unknown fixture operator")
                }
                IngredientPredicate(operator, (alarm.getValue("operand") as JsonValue.Str).value)
            }.toSet()
            assertEquals(text, expected, swiggyIngredientClaimAlarms(text))
        }
    }

    @Test fun protectedExclusionProjectionIsExactAndClosed() {
        val absent = listOf("maida", "palm oil", "salt", "chemicals", "preservatives",
            "artificial colours", "artificial flavours")
        absent.forEach { phrase ->
            assertEquals(IngredientPredicate(IngredientPredicateOperator.ABSENT, phrase),
                IngredientPredicate.fromProtectedExclusion(phrase))
        }
        for (operand in listOf("salt", "sugar")) {
            assertEquals(IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, operand),
                IngredientPredicate.fromProtectedExclusion("added $operand"))
        }
        for (unknown in listOf("sugar", "fragrance", "added fragrance", "preservative", "artificial colors",
            "palm-oil", "Palm oil", " palm oil", "palm oil ", "palm  oil", "no maida")) {
            assertNull(unknown, IngredientPredicate.fromProtectedExclusion(unknown))
        }
    }

    @Test fun novelOperandKeepsAbsenceAndNoAdditionOperatorsDistinct() {
        val absent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "fragrance")
        val noAddition = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "fragrance")
        for (label in listOf("Soap no fragrance", "Soap fragrance-free", "Soap 0% fragrance")) {
            assertTrue(label, swiggyHasIngredientPredicate(label, absent))
            assertFalse(label, swiggyHasIngredientPredicate(label, noAddition))
        }
        for (label in listOf("Soap no added fragrance", "Soap without added fragrance", "Soap 0% added fragrance")) {
            assertFalse(label, swiggyHasIngredientPredicate(label, absent))
            assertTrue(label, swiggyHasIngredientPredicate(label, noAddition))
        }
        for (label in listOf("Soap", "Soap fragrance", "Soap no fragrance added",
            "Soap with no fragrance added", "Soap maybe no added fragrance", "Soap fragrance alternative")) {
            assertFalse(label, swiggyHasIngredientPredicate(label, absent))
            assertFalse(label, swiggyHasIngredientPredicate(label, noAddition))
        }
    }

    @Test fun unsaltedAlarmIsNotIngredientAbsenceProofAndNoAdditionStaysDistinct() {
        val saltAbsent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "salt")
        val saltNotAdded = IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, "salt")
        assertTrue(swiggyHasIngredientPredicate("Biscuits with no salt", saltAbsent))
        assertFalse(swiggyHasIngredientPredicate("Biscuits with no salt", saltNotAdded))
        assertTrue(swiggyHasIngredientPredicate("Biscuits with no added salt", saltNotAdded))
        assertFalse(swiggyHasIngredientPredicate("Biscuits with no added salt", saltAbsent))
        assertFalse(swiggyHasIngredientPredicate("Biscuits unsalted", saltAbsent))
        assertFalse(swiggyHasExplicitIngredientAbsence("Biscuits unsalted", "salt"))
        assertEquals(setOf(saltAbsent), swiggyIngredientClaimAlarms("Unsalted cashews"))

        val sugarAbsent = IngredientPredicate(IngredientPredicateOperator.ABSENT, "sugar")
        assertFalse(swiggyHasIngredientPredicate("Unsweetened cocoa", sugarAbsent))
    }

    @Test fun operandsAreValidatedWithoutNormalizationOrGrammarInjection() {
        for (operand in listOf("Fragrance", " fragrance", "fragrance ", "palm  oil", "palm\toil", "palm\noil",
            "palm-oil", "fragrance's", "fragrance.*", "fragrance|salt", "fragrance2", "fragrancé", "",
            "one two three four five", "a".repeat(61))) {
            assertThrows(operand, IllegalArgumentException::class.java) {
                IngredientPredicate(IngredientPredicateOperator.ABSENT, operand)
            }
        }
        val unsafeWords = listOf("not", "no", "without", "added", "with", "and", "or", "free", "if", "unless",
            "except", "may", "might", "low", "less", "reduced", "for", "in", "on", "to", "from")
        for (word in unsafeWords) for (operator in IngredientPredicateOperator.values()) {
            assertThrows(word, IllegalArgumentException::class.java) { IngredientPredicate(operator, "$word fragrance") }
            assertThrows(word, IllegalArgumentException::class.java) { IngredientPredicate(operator, "fragrance $word") }
        }
        assertEquals("alpha beta gamma delta",
            IngredientPredicate(IngredientPredicateOperator.ABSENT, "alpha beta gamma delta").operand)
        assertEquals(60, IngredientPredicate(IngredientPredicateOperator.ABSENT, "a".repeat(60)).operand.length)
    }

    @Test fun predicateStringDoesNotExposeOperand() {
        for (operator in IngredientPredicateOperator.values()) {
            val display = IngredientPredicate(operator, "synthetic confidential ingredient").toString()
            assertEquals("IngredientPredicate(operator=$operator, operand=<redacted>)", display)
            assertFalse(display.contains("synthetic"))
            assertFalse(display.contains("confidential"))
        }
    }

    @Test fun protectedTypedEvaluationUsesExplicitLiteralEvidenceOnly() {
        val phrases = listOf("maida", "palm oil", "salt", "added salt", "added sugar", "chemicals", "preservatives",
            "artificial colours", "artificial flavours")
        for (phrase in phrases) {
            val predicate = requireNotNull(IngredientPredicate.fromProtectedExclusion(phrase))
            for (label in listOf("Biscuits", "Biscuits no $phrase", "Biscuits without $phrase", "Biscuits $phrase-free",
                "Biscuits 0% $phrase", "Biscuits zero percent $phrase", "Biscuits no $phrase added",
                "Biscuits not $phrase-free", "Biscuits no $phrase, $phrase", "Biscuits low $phrase")) {
                assertEquals("$phrase / $label", swiggyHasExplicitIngredientAbsence(label, phrase),
                    swiggyHasIngredientPredicate(label, predicate))
            }
        }
        assertFalse(swiggyHasExplicitIngredientAbsence("Biscuits unsalted", "salt"))
        assertFalse(swiggyHasExplicitIngredientAbsence("Biscuits unsalted, salted", "salt"))
        assertEquals("Biscuits zeromaida", swiggyNormalizeZeroPercentIngredients("Biscuits 0% maida"))
        assertEquals("Biscuits 0% maida, maida", swiggyNormalizeZeroPercentIngredients("Biscuits 0% maida, maida"))
    }

    @Test fun protectedCandidateBranchUsesTypedLiteralEvidenceWithoutChangingUnknownExclusions() {
        fun allowed(exclusion: String, label: String) = isSwiggyCandidateAllowed(
            ParsedItem(rawText = "biscuits", query = "biscuits", avoidPhrases = listOf(exclusion)),
            RecommendationCandidate(spinId = "synthetic", label = label),
        )
        assertTrue(allowed("palm_oil", "Biscuits no palm oil"))
        assertFalse(allowed("palm oil", "Biscuits"))
        assertFalse(allowed("palm oil", "Biscuits no added palm oil"))
        assertFalse(allowed("salt", "Unsalted biscuits"))
        assertTrue(allowed("salt", "Biscuits no salt"))
        assertFalse(allowed("salt", "Biscuits no added salt"))
        assertTrue(allowed("added salt", "Biscuits no added salt"))
        assertFalse(allowed("added salt", "Biscuits no salt"))
        assertFalse(allowed("added salt", "Biscuits salt-free"))
        // Unknown exclusions keep their existing lexical gate; they are not
        // silently promoted into the protected ingredient proof mechanism.
        assertNull(IngredientPredicate.fromProtectedExclusion("fragrance"))
        assertTrue(allowed("fragrance", "Biscuits"))
        assertFalse(allowed("fragrance", "Biscuits fragrance"))
    }

    @Test fun legacySugarExclusionChecksContradictionsAcrossSameSkuFields() {
        val item = ParsedItem(rawText = "apple juice", query = "apple juice", avoidPhrases = listOf("sugar"))
        fun offer(variant: String? = null, catalogueSubtitle: String? = null): RecommendationCandidate {
            val spin = "spin-apple-juice"
            val sku = "sku-apple-juice"
            val subtitleField = catalogueSubtitle?.let { ",\"subTitle\":${jsonString(it)}" } ?: ""
            val evidenceJson = """{"version":1,"source":"search_variation","spinId":${jsonString(spin)},"skuId":${jsonString(sku)},"fields":{"title":"Sugar Free Apple Juice"$subtitleField}}"""
            val evidence = requireNotNull(CatalogueIngredientEvidence.parse(
                JsonParser(evidenceJson).parseStrict(), spin, sku, emptySet(),
            ))
            return RecommendationCandidate(
                spinId = spin, skuId = sku, label = "Sugar Free Apple Juice", variant = variant,
                catalogueIngredients = evidence,
            )
        }

        val clean = offer()
        assertTrue(isSwiggyCandidateAllowed(item, clean))
        assertFalse(isSwiggyCandidateAllowed(item, offer(variant = "with added sugar")))
        assertFalse(isSwiggyCandidateAllowed(item, offer(catalogueSubtitle = "with added sugar")))
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
