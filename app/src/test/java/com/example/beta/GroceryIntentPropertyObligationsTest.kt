package com.example.beta

import com.example.beta.automation.ProductLexicon
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentPropertyObligationsTest {
    @Test fun sharedIdentityNounAliasFixturePinsResolvedValuesAndExactClosedKeys() {
        val relativePath = "scripts/fixtures/intent_identity_noun_aliases_oct27.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile) ?: error("Cannot find noun-alias fixture at $relativePath")
        val source = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("1e2b9639db921c5060231ee11567891bbc39a3a661c5bd40630eb714cb304026", sha256)
        val document = objectAt(JsonParser(source).parseStrict())
        assertEquals("identity-noun-aliases-v1", stringAt(document["version"]))
        val pairs = arrayAt(document["pairs"]).map { value ->
            val pair = arrayAt(value)
            assertEquals(2, pair.size)
            stringAt(pair[0]) to stringAt(pair[1])
        }
        assertEquals(87, pairs.size)
        assertEquals(87, pairs.map { it.first }.toSet().size)
        val keysField = ProductLexicon::class.java.getDeclaredField("identityNounAliasKeys")
        keysField.isAccessible = true
        assertEquals(pairs.map { it.first }.toSet(), keysField.get(ProductLexicon) as Set<*>)
        pairs.forEach { (alias, canonical) ->
            assertEquals(alias, canonical, ProductLexicon.canonicalizeIdentityNounsPreservingText(alias))
            assertEquals(alias, canonical,
                ProductLexicon.canonicalizeIdentityNounsPreservingText(alias.uppercase(java.util.Locale.ROOT)))
            val original = "PREFIX:\t($alias),! SUFFIX"
            assertEquals(alias, "PREFIX:\t($canonical),! SUFFIX",
                ProductLexicon.canonicalizeIdentityNounsPreservingText(original))
        }
    }

    @Test fun commaConjunctionCannotCoverAnOmittedMiddlePurchase() {
        val instruction = "1 rice, and 1 milk, and 1 eggs; eggs should be organic"
        val result = compileGroceryIntentPropertyObligations(instruction, listOf(
            row(instruction, "1 rice", "rice", "rice"),
            row(instruction, "1 eggs", "eggs", "eggs", required = listOf("organic"), rowIndex = 2),
        ))
        assertTrue(result.issueCodes.isNotEmpty())
        assertTrue(result.obligations.isEmpty())
    }

    @Test
    fun shared_fixture_matches_validate_only_scope_compiler() {
        val relativePath = "scripts/fixtures/intent_property_obligations_oct13.json"
        val fixture = listOf(File("../$relativePath"), File(relativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot find shared property-obligations fixture at $relativePath")
        val source = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("64fd67e472aac645baa101da7bdc69acc99f03f7f5353e3da72ae4cedb601ee4", sha256)
        val cases = arrayAt(JsonParser(source).parseStrict())
        assertEquals(27, cases.size)

        cases.forEach { value ->
            val case = objectAt(value)
            val id = stringAt(case["id"])
            val instruction = stringAt(case["instruction"])
            val rows = arrayAt(case["rows"]).mapIndexed { rowIndex, rowValue ->
                val row = objectAt(rowValue)
                val source = stringAt(row["source"])
                val start = instruction.indexOf(source)
                require(start >= 0) { "$id primary source missing" }
                GroceryIntentPropertyRow(
                    rowIndex = rowIndex,
                    primary = quote(instruction, start, source.length),
                    sourceIdentity = stringAt(row["identity"]),
                    name = stringAt(row["name"]),
                    required = row["required"]?.let(::stringListAt).orEmpty(),
                    excluded = row["excluded"]?.let(::stringListAt).orEmpty(),
                )
            }
            val result = compileGroceryIntentPropertyObligations(instruction, rows)
            val expectedIssue = case["issue"]?.let(::stringAt)
            assertEquals("$id issue", if (expectedIssue == null) emptyList() else listOf(expectedIssue), result.issueCodes)
            if (expectedIssue == null) {
                val expectedMembers = arrayAt(case["members"]).map { arrayAt(it).map(::intAt) }
                assertEquals("$id member scopes", expectedMembers, result.obligations.map { it.memberIndices })
            }
            if (id == "dairy_scope") {
                assertEquals("dairy", result.obligations.single().selector)
                assertEquals(listOf("lowfat"), result.obligations.single().required)
                assertEquals(listOf(0, 1), result.obligations.single().memberIndices)
            }
            if (id == "no_added_sugar_not_sugar_free") {
                assertEquals(listOf("noaddedsugar"), result.obligations.single().required)
                assertEquals(listOf("sugarfree"), result.obligations.single().excluded)
            }
            if ("source_start_utf16" in case) {
                assertEquals(intAt(case["source_start_utf16"]), result.obligations.single().source.startUtf16)
            }
        }
    }

    @Test
    fun a_lost_contrast_property_and_ambiguous_same_name_target_fail_closed() {
        val contrastInstruction = "1 milk. Milk needs not low-fat, not full cream."
        val contrast = compileGroceryIntentPropertyObligations(
            contrastInstruction,
            listOf(row(contrastInstruction, "1 milk", "milk", "milk", excluded = listOf("low_fat"))),
        )
        assertEquals(listOf("property_scope_mismatch"), contrast.issueCodes)

        val doubleNegativeInstruction = "1 milk. Milk should not be not low-fat."
        val doubleNegative = compileGroceryIntentPropertyObligations(
            doubleNegativeInstruction,
            listOf(row(doubleNegativeInstruction, "1 milk", "milk", "milk", excluded = listOf("low_fat"))),
        )
        assertEquals(listOf("property_conflicting_polarity"), doubleNegative.issueCodes)

        val ambiguousInstruction = "1 milk, 1 milk. Milk should be low-fat."
        val first = ambiguousInstruction.indexOf("1 milk")
        val second = ambiguousInstruction.indexOf("1 milk", first + 1)
        val ambiguous = compileGroceryIntentPropertyObligations(
            ambiguousInstruction,
            listOf(
                row(ambiguousInstruction, "1 milk", "milk", "low-fat milk", start = first),
                row(ambiguousInstruction, "1 milk", "milk", "low-fat milk", start = second, rowIndex = 1),
            ),
        )
        assertEquals(listOf("property_target_unproven"), ambiguous.issueCodes)
    }

    @Test
    fun ascii_case_folding_and_universal_missing_descriptor_are_conservative() {
        val hindiInstruction = "1 milk. Milk low-fat hona chahiye."
        val hindi = compileGroceryIntentPropertyObligations(
            hindiInstruction,
            listOf(row(hindiInstruction, "1 milk", "milk", "low-fat milk", required = listOf("low_fat"))),
        )
        assertEquals(emptyList<String>(), hindi.issueCodes)
        assertEquals(listOf("lowfat"), hindi.obligations.single().required)

        val universalInstruction = "1 yogurt. Every flavoured yogurt should be low-fat."
        val unknown = compileGroceryIntentPropertyObligations(
            universalInstruction,
            listOf(row(universalInstruction, "1 yogurt", "yogurt", "low-fat yogurt", required = listOf("low_fat"))),
        )
        assertEquals(listOf("property_scope_unknown"), unknown.issueCodes)
    }

    @Test
    fun tampered_source_identity_cannot_drop_a_product_descriptor() {
        val instruction = "1 almond milk. Milk should be low-fat."
        val result = compileGroceryIntentPropertyObligations(
            instruction,
            listOf(row(instruction, "1 almond milk", "milk", "low-fat milk", required = listOf("low_fat"))),
        )
        assertEquals(listOf("property_source_identity_unproven"), result.issueCodes)
        val lexical = "1 nonorganic curd. Curd should be organic."
        val negative = compileGroceryIntentPropertyObligations(lexical,
            listOf(row(lexical, "1 nonorganic curd", "nonorganic curd", "nonorganic curd", required = listOf("organic"))))
        assertEquals(listOf("property_source_identity_unproven"), negative.issueCodes)
    }

    @Test
    fun supplementary_letters_and_uncovered_unicode_numbers_cannot_disappear() {
        val instruction = "1 𝒌 milk. Milk should be low-fat."
        val result = compileGroceryIntentPropertyObligations(instruction,
            listOf(row(instruction, "1 𝒌 milk", "milk", "milk", required = listOf("low_fat"))))
        assertEquals(listOf("property_source_identity_unproven"), result.issueCodes)
        val uncovered = "²; 1 milk. Milk should be low-fat."
        val other = compileGroceryIntentPropertyObligations(uncovered,
            listOf(row(uncovered, "1 milk", "milk", "milk", required = listOf("low_fat"))))
        assertEquals(listOf("property_incomplete_source_coverage"), other.issueCodes)
    }

    @Test fun hindiFreshContrastOwnsOnlyTheOriginalNamedTarget() {
        for (ending in listOf("chahiye", "ho", "hona", "hona chahiye")) {
            for (negative in listOf("nahi", "nahin")) {
                val clause = "spinach fresh $ending, frozen $negative"
                val instruction = "1 rice, 1 spinach; $clause."
                val result = compileGroceryIntentPropertyObligations(instruction, listOf(
                    row(instruction, "1 rice", "rice", "rice"),
                    row(instruction, "1 spinach", "spinach", "fresh spinach",
                        required = listOf("fresh"), excluded = listOf("frozen"), rowIndex = 1),
                ))
                assertEquals("$ending/$negative", emptyList<String>(), result.issueCodes)
                val proof = result.obligations.single()
                assertEquals(listOf(1), proof.memberIndices)
                assertEquals(listOf("fresh"), proof.required)
                assertEquals(listOf("frozen"), proof.excluded)
                assertEquals(clause, proof.source.text)
            }
        }
    }

    @Test fun hindiContrastUsesExistingLowFatAndFullCreamPropertyKeys() {
        val instruction = "1 milk; milk low fat hona chahiye, full cream nahin."
        val result = compileGroceryIntentPropertyObligations(instruction, listOf(
            row(instruction, "1 milk", "milk", "low fat milk", required = listOf("low_fat"), excluded = listOf("full_cream")),
        ))
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(listOf("lowfat"), result.obligations.single().required)
        assertEquals(listOf("fullcream"), result.obligations.single().excluded)
    }

    @Test fun freshnessOppositesCannotOverrideLiteralOriginalForm() {
        for ((original, requested) in listOf("frozen" to "fresh", "dried" to "fresh", "fresh" to "frozen", "fresh" to "dried")) {
            val primary = "1 $original spinach"
            val instruction = "$primary; spinach should be $requested."
            val result = compileGroceryIntentPropertyObligations(instruction, listOf(
                row(instruction, primary, "$original spinach", "$original spinach", required = listOf(requested)),
            ))
            assertEquals("$original/$requested", listOf("property_source_conflict"), result.issueCodes)
        }
    }

    @Test fun hindiSamePropertyInBothPolaritiesAndDuplicateTargetFailClosed() {
        val conflict = "1 spinach; spinach fresh chahiye, fresh nahi."
        val conflictResult = compileGroceryIntentPropertyObligations(conflict, listOf(
            row(conflict, "1 spinach", "spinach", "fresh spinach", required = listOf("fresh"), excluded = listOf("fresh")),
        ))
        assertEquals(listOf("property_conflicting_polarity"), conflictResult.issueCodes)
        val duplicate = "1 spinach, 2 spinach; spinach fresh chahiye, frozen nahi."
        val duplicateResult = compileGroceryIntentPropertyObligations(duplicate, listOf(
            row(duplicate, "1 spinach", "spinach", "fresh spinach", required = listOf("fresh"), excluded = listOf("frozen")),
            row(duplicate, "2 spinach", "spinach", "fresh spinach", required = listOf("fresh"), excluded = listOf("frozen"), rowIndex = 1),
        ))
        assertEquals(listOf("property_target_unproven"), duplicateResult.issueCodes)
    }

    @Test fun scopedFreshnessCannotInventAdditionalPositiveOrNegativeForms() {
        val instruction = "1 spinach; spinach should be fresh, not frozen"
        listOf(
            row(instruction, "1 spinach", "spinach", "spinach",
                required = listOf("fresh"), excluded = listOf("frozen", "dried")),
            row(instruction, "1 spinach", "spinach", "spinach",
                required = listOf("fresh", "dried"), excluded = listOf("frozen")),
        ).forEach { candidate ->
            val result = compileGroceryIntentPropertyObligations(instruction, listOf(candidate))
            assertTrue(result.issueCodes.isNotEmpty())
            assertTrue(result.obligations.isEmpty())
        }
    }

    @Test fun scopedFreshnessPreservesIndependentlyStatedFormOnAnotherRow() {
        val instruction = "1 frozen peas, 1 spinach; spinach should be fresh, not frozen"
        val result = compileGroceryIntentPropertyObligations(instruction, listOf(
            row(instruction, "1 frozen peas", "frozen peas", "frozen peas", required = listOf("frozen")),
            row(instruction, "1 spinach", "spinach", "spinach", required = listOf("fresh"),
                excluded = listOf("frozen"), rowIndex = 1),
        ))
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(listOf(1), result.obligations.single().memberIndices)
        assertEquals(listOf("fresh"), result.obligations.single().required)
        assertEquals(listOf("frozen"), result.obligations.single().excluded)
    }

    @Test fun intactPrimaryMayFollowIndependentlyProvenNeutralRequestPrefix() {
        val instruction = "Please get 1 milk; milk should be low fat"
        val primary = row(instruction, "1 milk", "milk", "milk", required = listOf("low_fat"))
        val result = compileGroceryIntentPropertyObligations(
            instruction, listOf(primary), coveredContext = listOf(quote(instruction, 0, "Please get".length)),
        )
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(listOf(0), result.obligations.single().memberIndices)
        assertEquals(listOf("lowfat"), result.obligations.single().required)
        assertEquals("milk should be low fat", result.obligations.single().source.text)
        assertEquals("1 milk", primary.primary.text)
        assertEquals(instruction.indexOf("1 milk"), primary.primary.startUtf16)
    }

    @Test fun arbitraryCoveredContextCannotAuthorizeCroppedPropertyPrimary() {
        listOf("Amul ", "organic ", "2 ", "if available ", "make milk two ", "milk low fat hi ",
            "Please get Amul ").forEach { prefix ->
            val instruction = prefix + "1 milk; milk should be low fat"
            val result = compileGroceryIntentPropertyObligations(
                instruction,
                listOf(row(instruction, "1 milk", "milk", "milk", required = listOf("low_fat"))),
                coveredContext = listOf(quote(instruction, 0, prefix.trimEnd().length)),
            )
            assertEquals(prefix, listOf("property_cropped_primary"), result.issueCodes)
            assertTrue(prefix, result.obligations.isEmpty())
        }
        val instruction = "Please get Amul 1 milk; milk should be low fat"
        val neutralOnly = compileGroceryIntentPropertyObligations(
            instruction,
            listOf(row(instruction, "1 milk", "milk", "milk", required = listOf("low_fat"))),
            coveredContext = listOf(quote(instruction, 0, "Please get".length)),
        )
        assertEquals(listOf("property_cropped_primary"), neutralOnly.issueCodes)
        assertTrue(neutralOnly.obligations.isEmpty())
    }

    @Test fun neutralPrefixAndCoveredSuffixCannotHideTrailingProductDescriptor() {
        val instruction = "Please get 1 milk organic; milk should be low fat"
        val result = compileGroceryIntentPropertyObligations(
            instruction,
            listOf(row(instruction, "1 milk", "milk", "milk", required = listOf("low_fat"))),
            coveredContext = listOf(
                quote(instruction, 0, "Please get".length),
                quote(instruction, instruction.indexOf("organic"), "organic".length),
            ),
        )
        assertEquals(listOf("property_cropped_primary"), result.issueCodes)
        assertTrue(result.obligations.isEmpty())
    }

    @Test fun namedSelectorsUseOriginalCrossLanguageIdentityInBothDirections() {
        val aliases = listOf("palak" to "spinach", "doodh" to "milk", "dahi" to "curd",
            "chawal" to "rice", "shunti" to "ginger", "nariyal pani" to "coconut water")
        for ((alias, canonical) in aliases) {
            for ((identity, selector) in listOf(alias to canonical, canonical to alias)) {
                val primary = "1 $identity"
                val clause = "$selector should be organic"
                val instruction = "$primary; $clause"
                val original = row(instruction, primary, identity, identity, required = listOf("organic"))
                val result = compileGroceryIntentPropertyObligations(instruction, listOf(original))
                assertEquals(instruction, emptyList<String>(), result.issueCodes)
                assertEquals(instruction, listOf(0), result.obligations.single().memberIndices)
                assertEquals(listOf("organic"), result.obligations.single().required)
                assertEquals(clause, result.obligations.single().source.text)
                assertEquals(primary, original.primary.text)
                assertEquals(identity, original.sourceIdentity)
            }
        }
    }

    @Test fun aliasSelectorsPreserveDescriptorsAndDoNotLendPropertiesToNonmembers() {
        val instruction = "1 baby spinach, 2 rice; baby palak should be organic"
        val rows = listOf(
            row(instruction, "1 baby spinach", "baby spinach", "baby spinach", required = listOf("organic")),
            row(instruction, "2 rice", "rice", "rice", rowIndex = 1),
        )
        val result = compileGroceryIntentPropertyObligations(instruction, rows)
        assertEquals(emptyList<String>(), result.issueCodes)
        assertEquals(listOf(0), result.obligations.single().memberIndices)
        val borrowed = compileGroceryIntentPropertyObligations(instruction,
            listOf(rows.first(), rows.last().copy(required = listOf("organic"))))
        assertTrue(borrowed.issueCodes.isNotEmpty())
        assertTrue(borrowed.obligations.isEmpty())
        for ((identity, selector) in listOf("spinach" to "baby palak", "red spinach" to "baby palak")) {
            val source = "1 $identity; $selector should be organic"
            val missingDescriptor = compileGroceryIntentPropertyObligations(source,
                listOf(row(source, "1 $identity", identity, identity, required = listOf("organic"))))
            assertTrue(source, missingDescriptor.issueCodes.isNotEmpty())
            assertTrue(source, missingDescriptor.obligations.isEmpty())
        }
    }

    @Test fun aliasEquivalentDuplicateTargetsNeedExplicitUniversalSelector() {
        for (selector in listOf("palak", "all palak", "every spinach")) {
            val instruction = "1 spinach, 2 palak; $selector should be organic"
            val result = compileGroceryIntentPropertyObligations(instruction, listOf(
                row(instruction, "1 spinach", "spinach", "spinach", required = listOf("organic")),
                row(instruction, "2 palak", "palak", "palak", required = listOf("organic"), rowIndex = 1),
            ))
            if (selector == "palak") {
                assertEquals(listOf("property_target_unproven"), result.issueCodes)
                assertTrue(result.obligations.isEmpty())
            } else {
                assertEquals(selector, emptyList<String>(), result.issueCodes)
                assertEquals(selector, listOf(0, 1), result.obligations.single().memberIndices)
            }
        }
    }

    @Test fun aliasesCannotReinterpretCompoundsRenameOriginalsOrJoinPunctuation() {
        for ((identity, selector) in listOf(
            "peanut butter" to "makkhan", "elaichi banana" to "cardamom",
            "banana elaichi" to "cardamom", "coconut water" to "nariyal-pani",
            "nariyal-pani" to "coconut water", "coconut water" to "nariyal/pani",
        )) {
            val instruction = "1 $identity; $selector should be organic"
            val result = compileGroceryIntentPropertyObligations(instruction,
                listOf(row(instruction, "1 $identity", identity, identity, required = listOf("organic"))))
            // An unsupported clause is not a proof either: the caller retains
            // its uncovered text instead of interpreting punctuation as words.
            assertTrue(instruction, result.obligations.isEmpty())
        }
        val instruction = "1 rice; palak should be organic"
        val modelOnlyRename = compileGroceryIntentPropertyObligations(instruction,
            listOf(row(instruction, "1 rice", "rice", "spinach", required = listOf("organic"))))
        assertTrue(modelOnlyRename.issueCodes.isNotEmpty())
        assertTrue(modelOnlyRename.obligations.isEmpty())
        val forgedSourceIdentity = compileGroceryIntentPropertyObligations(instruction,
            listOf(row(instruction, "1 rice", "spinach", "spinach", required = listOf("organic"))))
        assertEquals(listOf("property_source_identity_unproven"), forgedSourceIdentity.issueCodes)
        assertTrue(forgedSourceIdentity.obligations.isEmpty())
    }

    @Test fun identityNounAliasesDoNotRewriteAdjectivesPropertiesOrSearchPhrases() {
        for (original in listOf("laal", "kachcha", "aam", "chai", "lean milk", "ready made tea", "without added sugar")) {
            assertEquals(original, original, ProductLexicon.canonicalizeIdentityNounsPreservingText(original))
        }
        assertEquals("spinach", ProductLexicon.canonicalizeIdentityNounsPreservingText("palak"))
        assertEquals("coconut water", ProductLexicon.canonicalizeIdentityNounsPreservingText("nariyal pani"))
        assertEquals("baby spinach", ProductLexicon.canonicalizeIdentityNounsPreservingText("baby palak"))
        assertEquals("elaichi banana", ProductLexicon.canonicalizeIdentityNounsPreservingText("elaichi banana"))
        assertEquals("elaichi banana", ProductLexicon.canonicalizeIdentityNounsPreservingText("banana elaichi"))
    }

    @Test fun identityNounAliasesKeepOriginalSeparatorsAndLexicalBoundaries() {
        for (separator in listOf("\n", "\r\n", "-", ",", "/", ".")) {
            val original = "nariyal${separator}pani"
            assertEquals(original, "coconut${separator}pani",
                ProductLexicon.canonicalizeIdentityNounsPreservingText(original))
        }
        for (original in listOf("palakX", "Xpalak", "palak's", "'palak", "palak'", "palak_")) {
            assertEquals(original, original, ProductLexicon.canonicalizeIdentityNounsPreservingText(original))
        }
    }

    @Test fun namedAliasCompilerRejectsNonAsciiOriginalIdentity() {
        for (identity in listOf("पालक", "palaké", "𝒑alak")) {
            val instruction = "1 $identity; spinach should be fresh"
            val result = compileGroceryIntentPropertyObligations(instruction,
                listOf(row(instruction, "1 $identity", identity, "spinach", required = listOf("fresh"))))
            assertTrue(instruction, result.issueCodes.isNotEmpty())
            assertTrue(instruction, result.obligations.isEmpty())
        }
    }

    @Test fun exactAliasTargetDoesNotResolveEmbeddedCompoundOrOriginalVarietyAmbiguity() {
        for ((first, second, selector) in listOf(
            Triple("spinach", "palak paneer", "palak"),
            Triple("cardamom", "elaichi banana", "elaichi"),
        )) {
            val instruction = "1 $first, 1 $second; $selector should be fresh"
            val result = compileGroceryIntentPropertyObligations(instruction, listOf(
                row(instruction, "1 $first", first, first, required = listOf("fresh")),
                row(instruction, "1 $second", second, second, rowIndex = 1),
            ))
            assertEquals(instruction, listOf("property_scope_unknown"), result.issueCodes)
            assertTrue(instruction, result.obligations.isEmpty())
        }
    }

    @Test fun eggsAndAndeRemainDuplicateNamedTargetsUnlessExplicitlyUniversal() {
        for (selector in listOf("ande", "all ande", "every eggs")) {
            val instruction = "1 eggs, 2 ande; $selector should be organic"
            val result = compileGroceryIntentPropertyObligations(instruction, listOf(
                row(instruction, "1 eggs", "eggs", "eggs", required = listOf("organic")),
                row(instruction, "2 ande", "ande", "ande", required = listOf("organic"), rowIndex = 1),
            ))
            if (selector == "ande") {
                assertEquals(listOf("property_target_unproven"), result.issueCodes)
                assertTrue(result.obligations.isEmpty())
            } else {
                assertEquals(selector, emptyList<String>(), result.issueCodes)
                assertEquals(listOf(0, 1), result.obligations.single().memberIndices)
            }
        }
    }

    @Test fun wholeVersusLowFatConflictIsPreservedAcrossMilkNounAliases() {
        for (identity in listOf("milk", "doodh")) {
            for ((originalForm, requestedForm) in listOf("whole" to "low fat", "low fat" to "whole")) {
                val primary = "1 $originalForm $identity"
                val instruction = "$primary; $identity should be $requestedForm"
                val result = compileGroceryIntentPropertyObligations(instruction,
                    listOf(row(instruction, primary, "$originalForm $identity", "$originalForm $identity",
                        required = listOf(requestedForm.replace(' ', '_')))))
                assertEquals(instruction, listOf("property_source_conflict"), result.issueCodes)
                assertTrue(result.obligations.isEmpty())
            }
        }
    }

    @Test fun namedNounAliasesDoNotExpandExistingVegetableCategoryMembership() {
        for (identity in listOf("spinach", "palak")) {
            val instruction = "1 $identity; all vegetables should be organic"
            val result = compileGroceryIntentPropertyObligations(instruction,
                listOf(row(instruction, "1 $identity", identity, identity, required = listOf("organic"))))
            if (identity == "spinach") {
                assertEquals(emptyList<String>(), result.issueCodes)
                assertEquals(listOf(0), result.obligations.single().memberIndices)
                assertEquals("vegetables", result.obligations.single().selector)
            } else {
                assertEquals(listOf("property_scope_unknown"), result.issueCodes)
                assertTrue(result.obligations.isEmpty())
            }
        }
    }

    @Test fun exactCommaConjunctionBetweenOriginalPrimariesIsOrderIndependent() {
        for (gap in listOf(", and ", ", aur ", " \t,\tAND\t", ",\tAur ")) {
            val instruction = "1 rice${gap}2 spinach; palak should be fresh"
            val rows = listOf(
                row(instruction, "1 rice", "rice", "rice"),
                row(instruction, "2 spinach", "spinach", "spinach", required = listOf("fresh"), rowIndex = 1),
            )
            val result = compileGroceryIntentPropertyObligations(instruction, rows)
            assertEquals(instruction, emptyList<String>(), result.issueCodes)
            assertEquals(listOf(1), result.obligations.single().memberIndices)
            assertEquals(listOf("fresh"), result.obligations.single().required)
            assertEquals("palak should be fresh", result.obligations.single().source.text)
            assertEquals(instruction, result, compileGroceryIntentPropertyObligations(instruction, rows.reversed()))
            rows.forEach { original ->
                assertEquals(original.primary.text,
                    instruction.substring(original.primary.startUtf16, original.primary.endUtf16))
            }
        }
    }

    @Test fun conjunctionReceiptCannotBridgeOmittedProductsAlternativesOrDescriptors() {
        for (gap in listOf(", 3 tea, and ", " and ", " aur ", ", or ", ", ya ",
            ", and organic ", ", fresh and ", ", and\n", ", and and ")) {
            val instruction = "1 rice${gap}2 spinach; palak should be fresh"
            val result = compileGroceryIntentPropertyObligations(instruction, listOf(
                row(instruction, "1 rice", "rice", "rice"),
                row(instruction, "2 spinach", "spinach", "spinach", required = listOf("fresh"), rowIndex = 1),
            ))
            assertTrue(instruction, result.issueCodes.isNotEmpty())
            assertTrue(instruction, result.obligations.isEmpty())
        }
    }

    private fun row(
        instruction: String,
        primary: String,
        identity: String,
        name: String,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        start: Int = instruction.indexOf(primary),
        rowIndex: Int = 0,
    ) = GroceryIntentPropertyRow(
        rowIndex = rowIndex,
        primary = quote(instruction, start, primary.length),
        sourceIdentity = identity,
        name = name,
        required = required,
        excluded = excluded,
    )

    private fun quote(instruction: String, start: Int, length: Int) =
        GroceryIntentReviewV2Quote(instruction.substring(start, start + length), start, start + length)

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private fun stringListAt(value: JsonValue?): List<String> = arrayAt(value).map(::stringAt)

    private fun intAt(value: JsonValue?): Int =
        (value as? JsonValue.Num)?.raw?.toIntOrNull() ?: error("Expected integer JSON number")
}
