package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.example.beta.automation.Quantity
import org.junit.Test

class GroceryIntentSameCountRefinementsTest {
    @Test fun reconstructsOnlyCompleteInitialEditorialPrefixesForTheFirstAnchoredSource() {
        val old = "two chocolate"
        val final = "two organic chocolate"
        listOf("Jot these down for me: ", "Please note: ", " \tJot these down for me: ").forEach { prefix ->
            listOf(false, true).forEach { fullPrimary ->
                val instruction = "$prefix$old, sorry, $final"
                val primary = if (fullPrimary) "$old, sorry, $final" else final
                val receipt = if (fullPrimary) proveSameCountRefinement(
                    instruction = instruction,
                    rowIndex = 0,
                    sourceText = primary,
                    previousText = old,
                    finalText = final,
                    previousAmountText = "two",
                    finalAmountText = "two",
                    quantity = BigDecimal("2"),
                    quantityUnit = "count",
                    finalName = "organic chocolate",
                    originalPrimaries = listOf(quote(instruction, primary)),
                ) else reconstructSameCountRefinement(
                    instruction, 0, "two", BigDecimal("2"), "count", "organic chocolate",
                    listOf(quote(instruction, primary)),
                )
                assertEquals(prefix.trimEnd(), receipt.prefix?.text?.trimEnd())
                assertEquals(old + ", sorry, $final", receipt.source.text)
                assertEquals(listOfNotNull(receipt.prefix, receipt.superseded), receipt.coverageSources)
            }
        }

        val oldPack = "two packets of 250 g moonroot"
        val finalPack = "two packets of 250 g organic moonroot"
        val packInstruction = "Please write the following items down for us: $oldPack, sorry, $finalPack"
        val packReceipt = reconstructSameCountRefinement(
            packInstruction, 0, "two", BigDecimal("2"), "pack", "organic moonroot",
            listOf(quote(packInstruction, finalPack)), packaging = "packet",
            packValue = BigDecimal("250"), packUnit = "g", finalPackText = "250",
        )
        assertTrue(packReceipt.prefix?.text?.startsWith("Please write") == true)
    }

    @Test fun reconstructionDoesNotCropUnsafeOrNoninitialEditorialHeaders() {
        val final = "two organic chocolate"
        val unsafePrefixes = listOf(
            "Please note bread: ", "Please note no sugar: ", "Please note BrandA: ",
            "Please note 2 items: ", "Please note if available: ", "Please note for tomorrow: ",
            "Please note like last time: ", "Aaj ki list mein ",
            "Please note\u000b", "unknown\t",
        )
        unsafePrefixes.forEach { prefix ->
            val instruction = "${prefix}two chocolate, sorry, $final"
            assertThrows("unsafePrefix=$prefix", SameCountRefinementError::class.java) {
                reconstructSameCountRefinement(instruction, 0, "two", BigDecimal("2"), "count",
                    "organic chocolate", listOf(quote(instruction, final)))
            }
        }

        val overlongPrefix = " ".repeat(181) + "Jot these down for me: "
        val overlong = "$overlongPrefix two chocolate, sorry, $final"
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(overlong, 0, "two", BigDecimal("2"), "count",
                "organic chocolate", listOf(quote(overlong, final)))
        }

        val instruction = "Jot these down for me: two chocolate, sorry, $final"
        val finalQuote = quote(instruction, final)
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(instruction, 0, "two", BigDecimal("2"), "count",
                "organic chocolate", listOf(finalQuote), protected = listOf(quote(instruction, "these")))
        }

        val commaJoined = "one milk, Jot these down for me: two chocolate, sorry, $final"
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(commaJoined, 1, "two", BigDecimal("2"), "count",
                "organic chocolate", listOf(quote(commaJoined, "one milk"), quote(commaJoined, final)))
        }

        val old = "two chocolate"
        val indented = " \t $old, sorry, $final"
        val indentedReceipt = reconstructSameCountRefinement(indented, 0, "two", BigDecimal("2"), "count",
            "organic chocolate", listOf(quote(indented, final)))
        assertEquals(null, indentedReceipt.prefix)

        listOf("; ", "\n").forEach { boundary ->
            val hardClause = "Jot these down for me$boundary$old, sorry, $final"
            val receipt = reconstructSameCountRefinement(hardClause, 0, "two", BigDecimal("2"), "count",
                "organic chocolate", listOf(quote(hardClause, final)))
            assertEquals(null, receipt.prefix)
            assertEquals(old + ", sorry, $final", receipt.source.text)
        }
    }

    @Test fun preparationUsesPrefixCoverageForCountAndRetailFinalPrimaries() {
        val oldCount = "two chocolate"
        val finalCount = "two organic chocolate"
        listOf(
            "Jot these down for me: " to finalCount,
            "Please add the following items: " to finalCount,
        ).forEach { (prefix, primary) ->
            val instruction = "$prefix$oldCount, sorry, $finalCount"
            val prepared = prepareCountSource(instruction, finalCount, "organic chocolate", primaryText = primary)
            assertEquals(1, prepared.items.size)
            assertEquals(Quantity.Count(2), prepared.items.single().quantity)
            assertEquals(finalCount, prepared.items.single().rawText)
        }

        val oldPack = "two packets of 250 g moonroot"
        val finalPack = "two packets of 250 g organic moonroot"
        listOf(
            "Jot these down for me: " to finalPack,
            "Please write these down: " to finalPack,
        ).forEach { (prefix, primary) ->
            val instruction = "$prefix$oldPack, sorry, $finalPack"
            val prepared = prepareRetailSource(instruction, finalPack, "organic moonroot", primaryText = primary)
            assertEquals(1, prepared.items.size)
            assertEquals(Quantity.Count(2), prepared.items.single().quantity)
            assertTrue(prepared.items.single().retailPackCount)
            assertEquals(finalPack, prepared.items.single().rawText)
        }
    }

    @Test fun preparationKeepsUnsafeEditorialPrefixesVisibleAndNonactionable() {
        val final = "two organic chocolate"
        listOf(
            "Please note bread: ", "Please note no sugar: ", "Please note 2 items: ",
            "Please note if available: ", "Please note for tomorrow: ", "Please note like last time: ",
        ).forEach { prefix ->
            val old = "two chocolate"
            val instruction = "$prefix$old, sorry, $final"
            val held = prepareCountSource(instruction, final, "organic chocolate")
            assertOriginalSourceRetained(held, instruction, old, final)
            val retained = (held.unresolvedText + held.excludedText).joinToString(" ")
            // Neutral courtesy/prepositions can be separate covered spans;
            // every material header token must remain visible even when split.
            prefix.removePrefix("Please ").split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotEmpty() && it !in setOf("for", "like") }.forEach { token ->
                    assertTrue("missing=$token prefix=$prefix unresolved=${held.unresolvedText} excluded=${held.excludedText}",
                        Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(retained))
                }
        }
    }

    @Test fun failedPrefixOwnerCannotLeaveItsSiblingActionable() {
        val old = "two chocolate"
        val final = "two organic chocolate"
        val full = "$old, sorry, $final"
        val sibling = "one tea"
        val instruction = "Jot these down for me: $full; $sibling"
        fun countRow(index: Int, text: String, name: String, amount: String, required: List<String> = emptyList()) =
            GroceryIntentReviewV2ReadyRow(
                rowIndex = index,
                item = GroceryIntentReviewV2Item(
                    name = name, quantity = BigDecimal(amount), quantityUnit = "count", packValue = null,
                    packUnit = "unspecified", packaging = null, required = required, excluded = emptyList(),
                    sourceText = text, supportingText = emptyList(), needsClarification = false,
                    quantityText = if (index == 0) "two" else "one", packText = null,
                ),
                evidence = GroceryIntentReviewV2Evidence(quote(instruction, text), emptyList()),
                quantityOrigin = "stated", amountRoleStatus = "matched",
            )
        val prepared = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
            sourceItemCount = 2,
            readyRows = listOf(
                countRow(0, final, "organic chocolate", "2", listOf("lactose_free")),
                countRow(1, sibling, "tea", "1"),
            ),
            blockedRows = emptyList(),
        ).prepareForReview()
        assertTrue(prepared.items.isEmpty())
        val retained = (prepared.unresolvedText + prepared.excludedText).joinToString(" ")
        assertTrue(retained.contains(old))
        assertTrue(retained.contains(final))
        assertTrue(retained.contains(sibling))

        val packSibling = "tea ka one packet"
        val packInstruction = "Please add $full; $packSibling"
        val packOwner = countRow(0, final, "organic chocolate", "2", listOf("lactose_free")).copy(
            evidence = GroceryIntentReviewV2Evidence(quote(packInstruction, final), emptyList()),
            item = countRow(0, final, "organic chocolate", "2", listOf("lactose_free")).item.copy(sourceText = final),
        )
        val packSiblingRow = GroceryIntentReviewV2ReadyRow(
            rowIndex = 1,
            item = GroceryIntentReviewV2Item(
                name = "tea", quantity = BigDecimal.ONE, quantityUnit = "pack", packValue = null,
                packUnit = "unspecified", packaging = "packet", required = emptyList(), excluded = emptyList(),
                sourceText = packSibling, supportingText = emptyList(), needsClarification = false,
                quantityText = "one", packText = null,
            ),
            evidence = GroceryIntentReviewV2Evidence(quote(packInstruction, packSibling), emptyList()),
            quantityOrigin = "stated", amountRoleStatus = "matched",
        )
        val packPrepared = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = packInstruction,
            sourceItemCount = 2, readyRows = listOf(packOwner, packSiblingRow), blockedRows = emptyList(),
        ).prepareForReview()
        assertTrue(packPrepared.items.isEmpty())
        val packRetained = (packPrepared.unresolvedText + packPrepared.excludedText).joinToString(" ")
        assertTrue(packRetained.contains(old))
        assertTrue(packRetained.contains(final))
        assertTrue(packRetained.contains(packSibling))
    }

    @Test fun provesSameCountRetailRefinementForExplicitAndReconstructedWholeFinalQuotes() {
        val old = "two packets of 250 g moonroot"
        val final = "two packets of 250 g organic moonroot"
        val cue = ", sorry, "
        val source = old + cue + final
        val facts = retailFacts(source, old, final, "organic moonroot")

        val explicit = proveRetail(facts)
        assertEquals(old, explicit.previous.text)
        assertEquals(final, explicit.final.text)
        assertEquals("two", explicit.previousAmount.text)
        assertEquals("two", explicit.finalAmount.text)
        assertEquals(old + cue, explicit.superseded.text)

        val reconstructed = reconstructSameCountRefinement(source, 0, "two", BigDecimal("2"), "pack",
            "organic moonroot", listOf(quote(source, final)), packaging = "packet", packValue = BigDecimal("250"),
            packUnit = "g", finalPackText = "250")
        assertEquals(source, reconstructed.source.text)
        assertEquals(explicit.previous, reconstructed.previous)
        assertEquals(final, reconstructed.final.text)
    }

    @Test fun provesCountedRetailFormWithoutAnInventedPhysicalSize() {
        val old = "two jars of moonroot"
        val final = "two jars of organic moonroot"
        val source = "$old, actually $final"
        val receipt = proveRetail(retailFacts(source, old, final, "organic moonroot", packaging = "jar",
            packValue = null, packUnit = "unspecified", packText = null))
        assertEquals(final, receipt.final.text)
    }

    @Test fun provesAttachedAndSpacedCanonicalPackSizesWithoutConvertingThem() {
        val gramOld = "two packets of 250g moonroot"
        val gramFinal = "two packets of 250g organic moonroot"
        val gramSource = "$gramOld, sorry, $gramFinal"
        val grams = proveRetail(retailFacts(gramSource, gramOld, gramFinal, "organic moonroot",
            packValue = BigDecimal("250"), packUnit = "g", packText = "250"))
        assertEquals(gramFinal, grams.final.text)

        val fractionalOld = "two packets of 0.25kg moonroot"
        val fractionalFinal = "two packets of 0.25kg organic moonroot"
        val fractionalSource = "$fractionalOld, sorry, $fractionalFinal"
        val fractional = proveRetail(retailFacts(fractionalSource, fractionalOld, fractionalFinal, "organic moonroot",
            packValue = BigDecimal("0.25"), packUnit = "kg", packText = "0.25"))
        assertEquals(fractionalFinal, fractional.final.text)
    }

    @Test fun rejectsChangedRetailCountFormSizeUnitAndForgedMetadata() {
        val old = "two packets of 250 g moonroot"
        val validFinal = "two packets of 250 g organic moonroot"
        val source = "$old, sorry, $validFinal"
        fun rejected(final: String, finalAmount: String = "two", quantity: BigDecimal = BigDecimal("2"),
            quantityUnit: String = "pack", packaging: String? = "packet", packValue: BigDecimal? = BigDecimal("250"),
            packUnit: String = "g", packText: String? = "250"): String {
            return assertThrows(SameCountRefinementError::class.java) {
                proveSameCountRefinement(source.replace(validFinal, final), 0, old + ", sorry, " + final,
                    old, final, "two", finalAmount, quantity, quantityUnit, "organic moonroot",
                    listOf(quote(source.replace(validFinal, final), old + ", sorry, " + final)),
                    packaging = packaging, packValue = packValue, packUnit = packUnit, finalPackText = packText)
            }.code
        }
        assertEquals("refinement_count_unproven", rejected("three packets of 250 g organic moonroot", "three"))
        assertEquals("refinement_retail_form_unproven", rejected("two bags of 250 g organic moonroot"))
        assertEquals("refinement_retail_size_unproven", rejected("two packets of 500 g organic moonroot"))
        assertEquals("refinement_retail_size_unproven", rejected("two packets of 250 ml organic moonroot"))
        assertEquals("refinement_retail_size_unproven", rejected("two packets of 250gmoonroot"))
        assertEquals("refinement_retail_size_unproven", rejected("two packets of 250grams organic moonroot"))
        assertEquals("refinement_retail_size_unproven", rejected("two packets of 250.0g organic moonroot"))
        assertEquals("refinement_contract_invalid", rejected(validFinal, quantityUnit = "count"))
        assertEquals("refinement_contract_invalid", rejected(validFinal, packaging = null))
        assertEquals("refinement_contract_invalid", rejected(validFinal, packValue = null))
        assertEquals("refinement_contract_invalid", rejected(validFinal, packValue = BigDecimal("500")))
        assertEquals("refinement_nominal_unproven", rejected("two packets of 250 g organic moonroot 70"))
    }

    @Test fun retailProofStillRequiresIdentityPolarityAndExcludesContentsRelations() {
        val old = "two packets of 250 g organic moonroot"
        listOf(
            "two packets of 250 g moonroot" to "moonroot",
            "two packets of 250 g not organic moonroot" to "not organic moonroot",
            "two packets of 250 g organic moonroot containing almonds" to "organic moonroot containing almonds",
        ).forEach { (final, name) ->
            val source = "$old, sorry, $final"
            assertThrows(SameCountRefinementError::class.java) {
                proveRetail(retailFacts(source, old, final, name))
            }
        }
    }

    @Test fun copiedFinalNamesCannotLaunderNominalConnectorsForCountsOrRetailPacks() {
        val oldCount = "two moonroot"
        listOf("alongside", "as well as", "besides", "plus", "followed by").forEach { connector ->
            val final = "two eggs $connector moonroot"
            rejects("$oldCount, sorry, $final", oldCount, final, "eggs $connector moonroot",
                "refinement_role_or_polarity_unproven")
        }

        val oldPack = "two packets of 250 g moonroot"
        listOf("alongside", "as well as", "besides", "plus", "followed by").forEach { connector ->
            val final = "two packets of 250 g eggs $connector moonroot"
            val source = "$oldPack, sorry, $final"
            val name = "eggs $connector moonroot"
            assertEquals("connector=$connector", "refinement_role_or_polarity_unproven",
                assertThrows(SameCountRefinementError::class.java) {
                    proveRetail(retailFacts(source, oldPack, final, name))
                }.code)
        }
    }

    @Test fun preparationRetainsWholeSourceForCopiedFinalNamesWithConnectors() {
        listOf("alongside", "as well as", "besides", "plus", "followed by").forEach { connector ->
            val countOld = "two moonroot"
            val countFinal = "two eggs $connector moonroot"
            val countInstruction = "$countOld, sorry, $countFinal"
            val countHeld = prepareCountSource(countInstruction, countFinal, "eggs $connector moonroot")
            assertOriginalSourceRetained(countHeld, countInstruction, countOld, countFinal)

            val packOld = "two packets of 250 g moonroot"
            val packFinal = "two packets of 250 g eggs $connector moonroot"
            val packInstruction = "$packOld, sorry, $packFinal"
            val packHeld = prepareRetailSource(packInstruction, packFinal, "eggs $connector moonroot")
            assertOriginalSourceRetained(packHeld, packInstruction, packOld, packFinal)
        }
    }

    @Test fun pureRetailProofRejectsNutritionTotalAndUnknownSizeRolesOnEitherMention() {
        val cases = listOf(
            Triple("two bars of 20 g protein", "two bars of 20 g organic protein", "organic protein"),
            Triple("two packets of 250 g total moonroot", "two packets of 250 g organic total moonroot", "organic total moonroot"),
            Triple("two packets 250 g moonroot", "two packets 250 g organic moonroot", "organic moonroot"),
        )
        cases.forEachIndexed { index, (old, final, name) ->
            val source = "$old, sorry, $final"
            val error = assertThrows(SameCountRefinementError::class.java) {
                proveRetail(retailFacts(source, old, final, name,
                    packaging = if ("bars" in old) "bar" else "packet",
                    packValue = if ("20 g" in old) BigDecimal("20") else BigDecimal("250")))
            }
            assertEquals("case=$index", if (index == 1) "refinement_role_or_polarity_unproven" else "refinement_retail_role_unproven", error.code)
        }
    }

    @Test fun pureRetailProofRejectsTrailingTotalRoleMarkersOnBothMentions() {
        listOf("total", "altogether", "kul", "collectively", "combined", "overall", "cumulative", "aggregate")
            .forEach { marker ->
                val old = "two packets of 250 g moonroot $marker"
                val final = "two packets of 250 g organic moonroot $marker"
                val source = "$old, sorry, $final"
                val error = assertThrows(SameCountRefinementError::class.java) {
                    proveRetail(retailFacts(source, old, final, "organic moonroot $marker"))
                }
                assertEquals("marker=$marker", "refinement_role_or_polarity_unproven", error.code)
            }
    }

    @Test fun bareCountProofRejectsTrailingTotalRoleMarkersOnBothMentions() {
        listOf("total", "altogether", "kul", "collectively", "combined", "overall", "cumulative", "aggregate")
            .forEach { marker ->
                val old = "two moonroot $marker"
                val final = "two organic moonroot $marker"
                rejects("$old, sorry, $final", old, final, "organic moonroot $marker",
                    "refinement_role_or_polarity_unproven")
            }
    }

    @Test fun cupAndSprayServingFormsAreNotRetailProofForms() {
        listOf("cup", "cups", "spray", "sprays").forEach { form ->
            val canonical = when (form) {
                "cup", "cups" -> "cup"
                else -> "spray"
            }
            listOf(false, true).forEach { withOf ->
                listOf(false, true).forEach { withSize ->
                    val formText = "two $form" + if (withOf) " of" else ""
                    val old = formText + (if (withSize) " 250 ml" else "") + " coffee"
                    val final = formText + (if (withSize) " 250 ml" else "") + " organic coffee"
                    val source = "$old, sorry, $final"
                    val error = assertThrows(SameCountRefinementError::class.java) {
                        proveRetail(retailFacts(source, old, final, "organic coffee", packaging = canonical,
                            packValue = if (withSize) BigDecimal("250") else null,
                            packUnit = if (withSize) "ml" else "unspecified",
                            packText = if (withSize) "250" else null))
                    }
                    assertEquals("form=$form withOf=$withOf withSize=$withSize",
                        "refinement_contract_invalid", error.code)
                }
            }
        }
    }

    @Test fun preparationValidatesRetailRoleOnBothSourceMentionsAndRetainsUnprovenRows() {
        val old = "two packets of 250 g moonroot"
        val final = "two packets of 250 g organic moonroot"
        val source = "$old, sorry, $final"
        val safe = prepareRetailSource(source, final, "organic moonroot")
        assertEquals(1, safe.items.size)
        assertEquals(Quantity.Count(2), safe.items.single().quantity)
        assertTrue(safe.items.single().retailPackCount)
        assertEquals(final, safe.items.single().rawText)

        listOf(
            "two bars of 20 g protein, sorry, two bars of 20 g organic protein" to "organic protein",
            "two packets of 250 g total moonroot, sorry, two packets of 250 g organic moonroot" to "organic moonroot",
        ).forEach { (instruction, name) ->
            val finalSource = instruction.substringAfter(", sorry, ")
            val held = prepareRetailSource(instruction, finalSource, name, packUnit = if ("20 g" in instruction) "g" else "g",
                packValue = if ("20 g" in instruction) BigDecimal("20") else BigDecimal("250"),
                packaging = if ("bars" in instruction) "bar" else "packet")
            assertTrue("must keep original source for $name", held.items.isEmpty())
            assertOriginalSourceRetained(held, instruction,
                instruction.substringBefore(", sorry, "), finalSource)
        }
    }

    @Test fun preparationKeepsTrailingTotalsAndAmbiguousServingFormsUnresolved() {
        listOf("total", "altogether", "kul", "collectively", "combined", "overall", "cumulative", "aggregate")
            .forEach { marker ->
                val old = "two packets of 250 g moonroot $marker"
                val final = "two packets of 250 g organic moonroot $marker"
                val instruction = "$old, sorry, $final"
                val held = prepareRetailSource(instruction, final, "organic moonroot $marker")
                assertOriginalSourceRetained(held, instruction, old, final)
            }

        listOf("cup", "cups", "spray", "sprays").forEach { form ->
            val canonical = if (form.startsWith("cup")) "cup" else "spray"
            listOf(false, true).forEach { withOf ->
                listOf(false, true).forEach { withSize ->
                    val formText = "two $form" + if (withOf) " of" else ""
                    val old = formText + (if (withSize) " 250 ml" else "") + " coffee"
                    val final = formText + (if (withSize) " 250 ml" else "") + " organic coffee"
                    val instruction = "$old, sorry, $final"
                    val held = prepareRetailSource(instruction, final, "organic coffee",
                        packValue = if (withSize) BigDecimal("250") else null,
                        packUnit = if (withSize) "ml" else "unspecified",
                        packaging = canonical)
                    assertOriginalSourceRetained(held, instruction, old, final)
                }
            }
        }
    }

    @Test fun acceptsEnglishHinglishAndUnrelatedPeerWithoutChangingFacts() {
        val englishText = "two chocolate, sorry I mean two Amul dark chocolate"
        val english = prove(englishText, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate", sourceText = englishText)
        assertEquals("two chocolate, sorry I mean ", english.superseded.text)
        assertEquals("two", english.finalAmount.text)
        assertEquals(BigDecimal("2"), english.quantity)
        assertEquals(englishText, english.source.text)
        assertEquals(emptyList<Int>(), english.scopeMembers)

        val hindiText = "do chocolate, actually do Amul dark chocolate"
        val hindi = prove(hindiText, "do chocolate", "do Amul dark chocolate", "Amul dark chocolate", amount = "do")
        assertEquals("do chocolate, actually ", hindi.superseded.text)

        val withPeer = "two chocolate, actually two Amul dark chocolate; one apple"
        val source = "two chocolate, actually two Amul dark chocolate"
        val receipt = prove(withPeer, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate",
            sourceText = source, primaries = listOf(quote(withPeer, source), quote(withPeer, "one apple")))
        assertEquals("two", receipt.finalAmount.text)
        assertFalse(receipt.toString().contains("chocolate"))
        assertFalse(receipt.toString().contains("Amul"))
    }

    @Test fun rejectsUnsupportedQualifierIdentityAndAmountChanges() {
        rejects("two chocolate, actually two Amul dark chocolate with almonds", "two chocolate", "two Amul dark chocolate with almonds", "Amul dark chocolate with almonds", "refinement_role_or_polarity_unproven")
        rejects("two chocolate, actually two Amul dark chocolate 70", "two chocolate", "two Amul dark chocolate 70", "Amul dark chocolate 70", "refinement_nominal_unproven")
        rejects("two chocolate, actually three Amul dark chocolate", "two chocolate", "three Amul dark chocolate", "Amul dark chocolate", "refinement_count_unproven", finalAmount = "three")
        rejects("two chocolate, actually two Amul dark chocolates", "two chocolate", "two Amul dark chocolates", "Amul dark chocolates", "refinement_identity_removed_or_replaced")
        rejects("two Amul dark chocolate, actually two Other dark chocolate", "two Amul dark chocolate", "two Other dark chocolate", "Other dark chocolate", "refinement_identity_removed_or_replaced")
        rejects("two chocolate, actually two Amul dark chocolate", "two chocolate", "two Amul dark chocolate", "Amul chocolate", "refinement_final_identity_not_preserved")
        listOf("few", "several", "gyarah").forEach { word ->
            rejects("two root, actually two organic $word lumaroot", "two root", "two organic $word lumaroot", "organic $word lumaroot", "refinement_nominal_unproven")
        }
    }

    @Test fun rejectsHyphenatedAndPlainDenialControls() {
        listOf(
            "organic pack-of lumaroot",
            "organic if-certified lumaroot",
            "organic without-sugar lumaroot",
            "organic no-added-sugar lumaroot",
            "organic sugar-free lumaroot",
            "organic when-available lumaroot",
            "organic when available lumaroot",
            "organic jab mil lumaroot",
            "organic roughly lumaroot",
            "organic ml lumaroot",
            "organic refill lumaroot",
        ).forEach { name ->
            val final = "two $name"
            rejects("two root, actually $final", "two root", final, name, "refinement_role_or_polarity_unproven")
        }
    }

    @Test fun rejectsAmbiguousPeersOverlapsForgedOffsetsAndDuplicateSource() {
        val instruction = "two chocolate, actually two Amul dark chocolate; four chocolate biscuits"
        val src = "two chocolate, actually two Amul dark chocolate"
        val sameHeadPeer = quote(instruction, "four chocolate biscuits")
        assertEquals("refinement_target_ambiguous", assertThrows(SameCountRefinementError::class.java) {
            prove(instruction, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate",
                primaries = listOf(quote(instruction, src), sameHeadPeer))
        }.code)

        val protectedText = "chocolate, actually"
        assertEquals("refinement_scope_conflict", assertThrows(SameCountRefinementError::class.java) {
            prove(instruction, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate",
                protected = listOf(quote(instruction, protectedText)))
        }.code)
        assertEquals("refinement_source_unproven", assertThrows(SameCountRefinementError::class.java) {
            prove(instruction, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate",
                primaries = listOf(GroceryIntentReviewV2Quote(src, 1, 1 + src.length)))
        }.code)

        val duplicate = "$src; $src"
        assertEquals("refinement_source_unproven", assertThrows(SameCountRefinementError::class.java) {
            prove(duplicate, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate", sourceText = src)
        }.code)
        val hyphenated = "two luma-root, actually two blue luma-root; four blue luma-root"
        val hyphenSource = "two luma-root, actually two blue luma-root"
        assertEquals("refinement_target_ambiguous", assertThrows(SameCountRefinementError::class.java) {
            prove(hyphenated, "two luma-root", "two blue luma-root", "blue luma-root",
                sourceText = hyphenSource,
                primaries = listOf(quote(hyphenated, hyphenSource), quote(hyphenated, "four blue luma-root")))
        }.code)
    }

    @Test fun preservesUtf16OffsetsAfterNonBmpPrefix() {
        val instruction = "😀 please:\ntwo chocolate, actually two Amul dark chocolate"
        val source = "two chocolate, actually two Amul dark chocolate"
        val receipt = prove(instruction, "two chocolate", "two Amul dark chocolate", "Amul dark chocolate", sourceText = source)
        assertEquals(instruction.indexOf(source), receipt.source.startUtf16)
        assertEquals(instruction.indexOf(source) + "two chocolate".length, receipt.previous.endUtf16)
    }

    @Test fun reconstructionFindsOnlyWholeFinalPrimaryAcrossSupportedBoundaries() {
        val cases = listOf(
            Triple("", ", sorry, ", ""),
            Triple("🧺:\n\t", ", actually,", " . "),
            Triple("four blue moongrain; ", ", I mean ", "; two otherroot"),
            Triple("", ", sorry I mean ", "\n"),
        )
        cases.forEach { (prefix, cue, suffix) ->
            val old = "teen   organic lumaroot"
            val final = "three organic certified lumaroot"
            val instruction = prefix + old + cue + final + suffix
            val primaries = mutableListOf(quote(instruction, final))
            if (prefix.contains("moongrain")) primaries += quote(instruction, "four blue moongrain")
            if (suffix.contains("otherroot")) primaries += quote(instruction, "two otherroot")
            val before = primaries.toList()
            val receipt = reconstructSameCountRefinement(instruction, 0, "three", BigDecimal("3"), "count",
                "organic certified lumaroot", primaries)
            assertEquals(old, receipt.previous.text)
            assertEquals(final, receipt.final.text)
            assertEquals(old + cue + final, receipt.source.text)
            assertEquals("teen", receipt.previousAmount.text)
            assertEquals("three", receipt.finalAmount.text)
            assertEquals(old + cue, receipt.superseded.text)
            assertEquals(before, primaries)
        }
    }

    @Test fun commaPartitionProofAndReconstructionRequireEveryOriginalPeerAndRecordScope() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val own = "$old, sorry, $final"
        val instruction = "one rice, $own, one tea"
        val primaries = listOf(quote(instruction, "one rice"), quote(instruction, own), quote(instruction, "one tea"))
        val expectedScope = listOf(0, 1, 2)

        val direct = proveSameCountRefinement(instruction, 1, own, old, final, "teen", "three",
            BigDecimal("3"), "count", "organic certified lumaroot", primaries,
            independentPeerIndices = setOf(0, 2))
        assertEquals(expectedScope, direct.scopeMembers)

        val finalOnlyPrimaries = listOf(primaries[0], quote(instruction, final), primaries[2])
        val reconstructed = reconstructSameCountRefinement(instruction, 1, "three", BigDecimal("3"), "count",
            "organic certified lumaroot", finalOnlyPrimaries, independentPeerIndices = setOf(0, 2))
        assertEquals(own, reconstructed.source.text)
        assertEquals(expectedScope, reconstructed.scopeMembers)
    }

    @Test fun commaPartitionRejectsMissingPeersUnownedTextAndUnsupportedRows() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val own = "$old, sorry, $final"
        val base = "one rice, $own, one tea"
        val basePrimaries = listOf(quote(base, "one rice"), quote(base, own), quote(base, "one tea"))
        listOf(emptySet(), setOf(0)).forEach { independent ->
            assertThrows(SameCountRefinementError::class.java) {
                proveSameCountRefinement(base, 1, own, old, final, "teen", "three", BigDecimal("3"),
                    "count", "organic certified lumaroot", basePrimaries, independentPeerIndices = independent)
            }
        }

        listOf(
            "please " to "one rice, $own, one tea",
            "two boxes of one rice, $own, one tea" to "",
            "one rice, $own, one tea" to " please add one more",
            "one rice, $own, one tea" to " and have it arrive by tonight",
            "one rice, two boxes of, $own, one tea" to "",
        ).forEach { (prefix, suffix) ->
            val instruction = prefix + suffix
            val primaries = listOf(quote(instruction, "one rice"), quote(instruction, own), quote(instruction, "one tea"))
            assertThrows(SameCountRefinementError::class.java) {
                proveSameCountRefinement(instruction, 1, own, old, final, "teen", "three", BigDecimal("3"),
                    "count", "organic certified lumaroot", primaries, independentPeerIndices = setOf(0, 2))
            }
        }

        val correctedPeer = "one rice, sorry, two rice"
        val cueInstruction = "$correctedPeer, $own, one tea"
        assertThrows(SameCountRefinementError::class.java) {
            proveSameCountRefinement(cueInstruction, 1, own, old, final, "teen", "three", BigDecimal("3"),
                "count", "organic certified lumaroot",
                listOf(quote(cueInstruction, correctedPeer), quote(cueInstruction, own), quote(cueInstruction, "one tea")),
                independentPeerIndices = setOf(0, 2))
        }
    }

    @Test fun commaPartitionRequiresLocatedUniquePeersAndDoesNotMergeSplitRowsOrCycles() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val own = "$old, sorry, $final"
        val instruction = "one rice, $own, one tea"
        val target = quote(instruction, own)
        val rice = quote(instruction, "one rice")

        val unlocated = GroceryIntentReviewV2Quote("one missing tea", -1, 15)
        assertEquals("refinement_source_unproven", assertThrows(SameCountRefinementError::class.java) {
            proveSameCountRefinement(instruction, 1, own, old, final, "teen", "three", BigDecimal("3"),
                "count", "organic certified lumaroot", listOf(rice, target, unlocated),
                independentPeerIndices = setOf(0, 2))
        }.code)

        val duplicateHeadInstruction = "$instruction, one lumaroot"
        assertEquals("refinement_target_ambiguous", assertThrows(SameCountRefinementError::class.java) {
            proveSameCountRefinement(duplicateHeadInstruction, 1, own, old, final, "teen", "three", BigDecimal("3"),
                "count", "organic certified lumaroot", listOf(
                    quote(duplicateHeadInstruction, "one rice"), quote(duplicateHeadInstruction, own),
                    quote(duplicateHeadInstruction, "one tea"), quote(duplicateHeadInstruction, "one lumaroot")),
                independentPeerIndices = setOf(0, 2, 3))
        }.code)

        val splitInstruction = "one rice, $old, $final, one tea"
        val split = listOf(quote(splitInstruction, "one rice"), quote(splitInstruction, old),
            quote(splitInstruction, final), quote(splitInstruction, "one tea"))
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(splitInstruction, 2, "three", BigDecimal("3"), "count",
                "organic certified lumaroot", split, independentPeerIndices = setOf(0, 1, 3))
        }

        val secondOld = "two amber moongrain"
        val secondFinal = "two amber certified moongrain"
        val second = "$secondOld, sorry, $secondFinal"
        val twoComponents = "$own, $second"
        val twoPrimaries = listOf(quote(twoComponents, own), quote(twoComponents, second))
        assertThrows(SameCountRefinementError::class.java) {
            proveSameCountRefinement(twoComponents, 0, own, old, final, "teen", "three", BigDecimal("3"),
                "count", "organic certified lumaroot", twoPrimaries, independentPeerIndices = setOf(1))
        }
        assertThrows(SameCountRefinementError::class.java) {
            proveSameCountRefinement(twoComponents, 1, second, secondOld, secondFinal, "two", "two", BigDecimal("2"),
                "count", "amber certified moongrain", twoPrimaries, independentPeerIndices = setOf(0))
        }
    }

    @Test fun commaPartitionDoesNotCropFractionFragments() {
        val own = "3 organic lumaroot, sorry, 3 organic certified lumaroot"
        val instruction = "1/$own, one tea"
        val primaries = listOf(quote(instruction, own), quote(instruction, "one tea"))
        assertEquals("refinement_source_unproven", assertThrows(SameCountRefinementError::class.java) {
            proveSameCountRefinement(instruction, 0, own, "3 organic lumaroot", "3 organic certified lumaroot",
                "3", "3", BigDecimal("3"), "count", "organic certified lumaroot", primaries,
                independentPeerIndices = setOf(1))
        }.code)
    }

    @Test fun reconstructionRejectsPeerDelimiterOutsideFinalPrimaryWithoutOffsetOverrun() {
        val instruction = "one rice, sorry, three organic certified lumaroot"
        val rice = quote(instruction, "one rice")
        val malformedFinal = quote(instruction, ", sorry, three organic certified lumaroot")
        assertEquals("refinement_partition_gap_unproven", assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(instruction, 1, "three", BigDecimal("3"), "count",
                "organic certified lumaroot", listOf(rice, malformedFinal), independentPeerIndices = setOf(0))
        }.code)
    }

    @Test fun reconstructionRejectsOutOfRangeIndependentPeerIndexWithTypedError() {
        val old = "teen organic lumaroot"
        val final = "three organic certified lumaroot"
        val own = "$old, sorry, $final"
        val instruction = "one rice, $own, one tea"
        val primaries = listOf(quote(instruction, "one rice"), quote(instruction, final), quote(instruction, "one tea"))
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(instruction, 1, "three", BigDecimal("3"), "count",
                "organic certified lumaroot", primaries, independentPeerIndices = setOf(0, 3))
        }
    }

    @Test fun reconstructionCannotCropContainingClauseOrUseBlockedPeerAsPositiveBoundary() {
        val old = "three organic lumaroot"
        val final = "three organic certified lumaroot"
        listOf(
            "two boxes of " to "",
            "if available " to "",
            "one apple, " to "",
            "" to " if available",
            "" to " and two oranges",
            "" to ", one apple",
        ).forEach { (prefix, suffix) ->
            val instruction = prefix + old + ", sorry, " + final + suffix
            val primaries = mutableListOf(quote(instruction, final))
            if (prefix.isNotEmpty()) primaries += quote(instruction, prefix.trim(' ', ','))
            assertThrows(SameCountRefinementError::class.java) {
                reconstructSameCountRefinement(instruction, 0, "three", BigDecimal("3"), "count",
                    "organic certified lumaroot", primaries)
            }

            val sourceText = "$old, sorry, $final"
            val source = quote(instruction, sourceText)
            assertEquals("refinement_clause_boundary_unproven", assertThrows(SameCountRefinementError::class.java) {
                proveSameCountRefinement(instruction, 0, sourceText, old, final, "three", "three",
                    BigDecimal("3"), "count", "organic certified lumaroot", listOf(source))
            }.code)
        }

        val blockedPrefix = "two boxes of "
        val instruction = blockedPrefix + old + ", sorry, " + final
        val primaries = listOf(quote(instruction, final), quote(instruction, "two boxes of"))
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(instruction, 0, "three", BigDecimal("3"), "count",
                "organic certified lumaroot", primaries)
        }
    }

    @Test fun reconstructionDoesNotMergeAnOldDuplicatePrimaryRow() {
        val old = "three organic lumaroot"
        val final = "three organic certified lumaroot"
        val instruction = "$old, sorry, $final"
        val primaries = listOf(quote(instruction, final), quote(instruction, old))
        assertThrows(SameCountRefinementError::class.java) {
            reconstructSameCountRefinement(instruction, 0, "three", BigDecimal("3"), "count",
                "organic certified lumaroot", primaries)
        }
    }

    @Test fun neverTreatsAFractionDenominatorAsTheCount() {
        for (separator in listOf("/", "⁄", "∕", ",", "٫", "٬", "．", "，", ".")) {
            val source = "3 organic lumaroot, sorry, 3 organic certified lumaroot"
            val instruction = "1$separator$source"
            assertEquals("refinement_source_unproven", assertThrows(SameCountRefinementError::class.java) {
                prove(instruction, "3 organic lumaroot", "3 organic certified lumaroot",
                    "organic certified lumaroot", amount = "3", sourceText = source)
            }.code)
        }
    }

    @Test fun commaDirectlyBeforeFinalCountIsNotANumberSeparator() {
        val source = "teen organic lumaroot, sorry,3 organic certified lumaroot"
        val receipt = prove(source, "teen organic lumaroot", "3 organic certified lumaroot",
            "organic certified lumaroot", amount = "teen", finalAmount = "3", sourceText = source)
        assertEquals("3", receipt.finalAmount.text)
    }

    private fun rejects(instruction: String, previous: String, final: String, name: String, code: String, finalAmount: String = "two") {
        assertEquals(code, assertThrows(SameCountRefinementError::class.java) {
            prove(instruction, previous, final, name, finalAmount = finalAmount, sourceText = instruction)
        }.code)
    }

    private fun assertOriginalSourceRetained(
        prepared: GroceryIntentPreparation,
        instruction: String,
        vararg originalClauses: String,
    ) {
        assertTrue("must not prepare items for source length ${instruction.length}", prepared.items.isEmpty())
        val reviewText = (prepared.unresolvedText + prepared.excludedText).joinToString(" ")
        originalClauses.forEach { clause ->
            assertTrue("missing original clause of length ${clause.length}", reviewText.contains(clause))
        }
    }

    private data class RetailFacts(
        val instruction: String,
        val source: String,
        val previous: String,
        val final: String,
        val name: String,
        val packaging: String?,
        val packValue: BigDecimal?,
        val packUnit: String,
        val packText: String?,
    )

    private fun retailFacts(
        instruction: String,
        previous: String,
        final: String,
        name: String,
        packaging: String? = "packet",
        packValue: BigDecimal? = BigDecimal("250"),
        packUnit: String = "g",
        packText: String? = packValue?.stripTrailingZeros()?.toPlainString(),
    ): RetailFacts {
        val previousStart = instruction.indexOf(previous)
        val finalStart = instruction.indexOf(final, previousStart + previous.length)
        require(previousStart >= 0 && finalStart > previousStart)
        return RetailFacts(instruction, instruction.substring(previousStart, finalStart + final.length),
            previous, final, name, packaging, packValue, packUnit, packText)
    }

    private fun proveRetail(facts: RetailFacts) = proveSameCountRefinement(
        instruction = facts.instruction,
        rowIndex = 0,
        sourceText = facts.source,
        previousText = facts.previous,
        finalText = facts.final,
        previousAmountText = "two",
        finalAmountText = "two",
        quantity = BigDecimal("2"),
        quantityUnit = "pack",
        finalName = facts.name,
        originalPrimaries = listOf(quote(facts.instruction, facts.source)),
        packaging = facts.packaging,
        packValue = facts.packValue,
        packUnit = facts.packUnit,
        finalPackText = facts.packText,
    )

    private fun prepareRetailSource(
        instruction: String,
        finalSource: String,
        name: String,
        packValue: BigDecimal? = BigDecimal("250"),
        packUnit: String = "g",
        packaging: String? = "packet",
        primaryText: String = finalSource,
    ): GroceryIntentPreparation {
        val primary = quote(instruction, primaryText)
        val item = GroceryIntentReviewV2Item(
            name = name,
            quantity = BigDecimal("2"),
            quantityUnit = "pack",
            packValue = packValue,
            packUnit = if (packValue == null) "unspecified" else packUnit,
            packaging = packaging,
            required = listOf("organic").takeIf { "organic" in name.lowercase() }.orEmpty(),
            excluded = emptyList(),
            sourceText = primaryText,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = "two",
            packText = if (packValue == null) null else "${packValue.stripTrailingZeros().toPlainString()}",
        )
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(primary, emptyList()),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = 1,
            readyRows = listOf(row),
            blockedRows = emptyList(),
        ).prepareForReview()
    }

    private fun prepareCountSource(
        instruction: String,
        finalSource: String,
        name: String,
        primaryText: String = finalSource,
    ): GroceryIntentPreparation {
        val item = GroceryIntentReviewV2Item(
            name = name,
            quantity = BigDecimal("2"),
            quantityUnit = "count",
            packValue = null,
            packUnit = "unspecified",
            packaging = null,
            required = emptyList(),
            excluded = emptyList(),
            sourceText = primaryText,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = "two",
            packText = null,
        )
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(quote(instruction, primaryText), emptyList()),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
        return GroceryIntentReviewV2("grocery-intent-v2", true, instruction, 1, listOf(row), emptyList())
            .prepareForReview()
    }

    private fun prove(
        instruction: String,
        previous: String,
        final: String,
        finalName: String,
        amount: String = "two",
        finalAmount: String = amount,
        sourceText: String = "$previous, actually $final",
        primaries: List<GroceryIntentReviewV2Quote> = listOf(quote(instruction, sourceText)),
        protected: List<GroceryIntentReviewV2Quote> = emptyList(),
        independentPeerIndices: Set<Int> = emptySet(),
    ) = proveSameCountRefinement(instruction, 0, sourceText, previous, final, amount, finalAmount,
        requireNotNull(parseIntentCorrectionAmount(amount)), "count", finalName, primaries, protected,
        independentPeerIndices)

    private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
        val start = instruction.indexOf(text)
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }
}
