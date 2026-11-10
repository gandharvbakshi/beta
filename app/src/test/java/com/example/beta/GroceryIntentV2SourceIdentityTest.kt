package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.ProductLexicon
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentV2SourceIdentityTest {
    @Test fun uncompiledNativeSourceScopeCannotBeCopiedDroppedOrReorderedIntoAffirmativeV2Rows() {
        val scopes = listOf("दूध नहीं", "दूध नही", "दूध नहीँ", "मत दूध", "बिना दूध", "अगर दूध", "यदि दूध",
            "दूध वरना", "दूध बजाय", "दूध अलावा", "दूध छोड़कर", "मिल जाए तो दूध",
            "दूध नहीं\u200d", "दूध नहीं\u0301", "मिल जाए तो\u200c दूध")
        scopes.forEach { scope ->
            val cases = listOf(
                SourceIdentityCase(scope, scope, null, "unspecified", null),
                SourceIdentityCase("500 g $scope", scope, 500, "g", "500"),
                SourceIdentityCase("500 ग्राम $scope", scope, 500, "g", "500"),
                SourceIdentityCase("2 $scope", scope, 2, "count", "2"),
            )
            cases.forEach { original ->
                listOf(scope, "दूध", scope.split(' ').reversed().joinToString(" ")).forEach { name ->
                    val case = original.copy(name = name, instruction = "please get ${original.source}")
                    val prepared = prepareSourceIdentity(case) { row ->
                        assertEquals("item_contains_correction_or_alternative", row.groundingIssue())
                        assertEquals(case.source, row.evidence.primary.text)
                        assertEquals(case.instruction.indexOf(case.source), row.evidence.primary.startUtf16)
                    }
                    assertReviewBlocked(case, prepared, requireNoItems = true)
                    assertReviewBlocked(case.copy(excluded = listOf("दूध")),
                        prepareSourceIdentity(case.copy(excluded = listOf("दूध"))), requireNoItems = true)
                }
            }
        }
        for (noun in listOf("अगरबत्ती", "मतलब", "तोरई", "milk")) {
            val case = SourceIdentityCase(noun, noun, null, "unspecified", null)
            assertEquals(noun, 1, prepareSourceIdentity(case).items.size)
        }
        assertEquals(1, prepareSourceIdentity(SourceIdentityCase("500 g दूध", "दूध", 500, "g", "500")).items.size)
        // The local predicate cannot see cropped text. Existing original-input
        // coverage must still keep the omitted operator unresolved, not actionable.
        val cropped = SourceIdentityCase("दूध", "दूध", null, "unspecified", null,
            instruction = "दूध नहीं")
        assertFalse(groceryIntentHasUncompiledNativeSourceScope(cropped.source))
        val croppedPrepared = prepareSourceIdentity(cropped)
        assertReviewBlocked(cropped, croppedPrepared)
        assertTrue(croppedPrepared.unresolvedText.any { "नहीं" in it })
    }

    @Test fun nativeMeasuredIdentityKeepsOriginalSourceForPrefixAndSuffixAmounts() {
        listOf(
            SourceIdentityCase("500 ग्राम मक्खन", "मक्खन", 500, "g", "500"),
            SourceIdentityCase("मक्खन 500 ग्राम", "मक्खन", 500, "g", "500"),
            SourceIdentityCase("2 किलो भिंडी", "भिंडी", 2, "kg", "2"),
            SourceIdentityCase("भिंडी 2 किलो", "भिंडी", 2, "kg", "2"),
            SourceIdentityCase("500 ग्राम laal mirch", "laal mirch", 500, "g", "500"),
        ).forEach { original ->
            val case = original.copy(instruction = "please get ${original.source}")
            val sourceStart = case.instruction.indexOf(case.source)
            val amountEnd = case.source.indexOf(case.quantityText!!) + case.quantityText.length
            val unit = requireNotNull(GroceryIntentPhysicalUnitMatcher.after(case.source, amountEnd, case.quantityUnit))
            assertEquals(case.source, case.source.substring(unit.start, unit.end), unit.token)
            assertEquals(case.source, case.source,
                case.instruction.substring(sourceStart, sourceStart + case.source.length))
            val prepared = prepareSourceIdentity(case) { row ->
                assertEquals(sourceStart, row.evidence.primary.startUtf16)
                assertEquals(sourceStart + case.source.length, row.evidence.primary.endUtf16)
                assertEquals(case.source, row.evidence.primary.text)
                val masked = row.identitySourceWithoutAmounts(boundAtomsOnly = true)
                assertEquals(case.source.length, masked.length)
                assertEquals(case.name, masked.trim())
                assertEquals(case.quantityText, row.item.quantityText)
            }
            assertEquals("${case.source}: ${prepared.unresolvedText}", 1, prepared.items.size)
            assertEquals(case.source, case.source, prepared.items.single().rawText)
            assertTrue(case.source, prepared.items.single().query.isNotBlank())
            assertTrue(case.source, prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun nativeUnitRequiresCompleteAffirmativeAttestedNounNotCopiedFreeText() {
        listOf("तिल नहीं", "बिना चीनी की चाय", "मिल जाए तो चाय", "दूध नहीं",
            "दूध एक्स्ट्रा", "दूध extra", "दूध milk", "मखाना", "ग्राम").forEach { noun ->
            val source = "500 ग्राम $noun"
            val case = SourceIdentityCase(source, noun, 500, "g", "500")
            assertReviewBlocked(case, prepareSourceIdentity(case), requireNoItems = true)
        }
        val control = SourceIdentityCase("500 ग्राम दूध", "दूध", 500, "g", "500")
        assertEquals(1, prepareSourceIdentity(control).items.size)
        listOf(
            control.copy(name = "दूध extra"),
            control.copy(required = listOf("organic")),
            control.copy(excluded = listOf("extra")),
            control.copy(packValue = "500", packUnit = "g", packText = "500"),
            control.copy(packaging = "pouch"),
            control.copy(supporting = listOf(SourceIdentitySupport("modifier", "extra")),
                instruction = "500 ग्राम दूध; extra"),
        ).forEach { case -> assertReviewBlocked(case, prepareSourceIdentity(case), requireNoItems = true) }
        assertEquals(null, ProductLexicon.completeIdentityNoun("दूध\n"))
        assertEquals(null, ProductLexicon.completeIdentityNoun("\u00a0दूध"))
        assertEquals(null, ProductLexicon.completeIdentityNoun(""))
        assertEquals("milk", ProductLexicon.completeIdentityNoun("\t दूध \t"))
        val split = SourceIdentityCase("coconut 2 किलो water", "coconut water", 2, "kg", "2")
        assertReviewBlocked(split, prepareSourceIdentity(split), requireNoItems = true)
        val conditional = SourceIdentityCase("दो लीटर दूध", "दूध", 2, "l", "दो",
            instruction = "अगर दुकान खुली हो, दो लीटर दूध")
        val preparedConditional = prepareSourceIdentity(conditional)
        assertReviewBlocked(conditional, preparedConditional)
        assertTrue(preparedConditional.unresolvedText.isNotEmpty())
    }

    @Test fun nativeMeasuredIdentityDoesNotAuthorizeDifferentNativeNounOrVowel() {
        listOf(
            SourceIdentityCase("500 ग्राम मखाना", "मखानी", 500, "g", "500"),
            SourceIdentityCase("मखाना 500 ग्राम", "मखानी", 500, "g", "500"),
            SourceIdentityCase("500 ग्राम भिंडी", "भिंडा", 500, "g", "500"),
            SourceIdentityCase("500 ग्राम ग्राम", "ग्राम्य", 500, "g", "500"),
            SourceIdentityCase("500 ग्राम मखाना", "मखाना", 500, "ml", "500"),
            SourceIdentityCase("500 ग्राम्य मखाना", "मखाना", 500, "g", "500"),
            SourceIdentityCase("500 ग्राम\u0301 मखाना", "मखाना", 500, "g", "500"),
            SourceIdentityCase("2 L'Oreal shampoo", "shampoo", 2, "l", "2"),
        ).forEach { case ->
            val prepared = prepareSourceIdentity(case) { row ->
                if (case.name in setOf("मखानी", "भिंडा", "ग्राम्य")) {
                    assertTrue("wrong native identity must fail grounding: ${case.name}", row.groundingIssue() != null)
                }
            }
            assertReviewBlocked(case, prepared, requireNoItems = true)
        }
    }

    @Test fun danglingSourceRelationsCannotBeCopiedIntoIdentityToBypassFullPreparation() {
        val invalid = listOf(
            SourceIdentityCase("two boxes of", "boxes", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "box"),
            SourceIdentityCase("one milk with", "milk with"),
            SourceIdentityCase("one cereal containing", "cereal containing"),
            SourceIdentityCase("one cereal contains", "cereal contains"),
            SourceIdentityCase("two boxes of please", "boxes", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "box"),
            SourceIdentityCase("one milk with, please", "milk with"),
            SourceIdentityCase("one cereal containing please", "cereal containing"),
            SourceIdentityCase("one cereal contains de do", "cereal contains"),
            SourceIdentityCase("two boxes of chahiye", "boxes", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "box"),
        )
        invalid.forEach { case ->
            assertReviewBlocked(case, prepareSourceIdentity(case), requireNoItems = true)
        }

        val positive = listOf(
            SourceIdentityCase("two boxes of cereal", "cereal", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "box"),
            SourceIdentityCase("one cream of mushroom", "cream of mushroom"),
            SourceIdentityCase("two boxes of cereal please", "cereal", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "box"),
            SourceIdentityCase("one cream of mushroom chahiye", "cream of mushroom"),
        )
        positive.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            assertEquals(case.source, case.source, prepared.items.single().rawText)
            val gate = GroceryIntentReviewGate()
            gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes,
                prepared.fulfillmentConstraints)
            assertTrue("${case.source} -> review gate", gate.canApply())
        }
    }

    @Test fun productRelativePackGenitiveAndParticleWordOrdersRetainExactIdentityAndAmounts() {
        val cases = listOf(
            SourceIdentityCase(
                source = "BrandA milk ka ek 1 litre wala pouch",
                name = "BrandA milk",
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "ek",
                packValue = "1",
                packUnit = "l",
                packText = "1",
                packaging = "pouch",
            ),
            SourceIdentityCase(
                source = "BrandA tea leaves 250 g ke do packets",
                name = "BrandA tea leaves",
                quantity = 2,
                quantityUnit = "pack",
                quantityText = "do",
                packValue = "250",
                packUnit = "g",
                packText = "250",
                packaging = "packet",
            ),
            SourceIdentityCase(
                source = "BrandA rice ki do 500 g bags",
                name = "BrandA rice",
                quantity = 2,
                quantityUnit = "pack",
                quantityText = "do",
                packValue = "500",
                packUnit = "g",
                packText = "500",
                packaging = "bag",
            ),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, Quantity.Count(case.quantity!!), item.quantity)
            assertTrue(case.source, item.retailPackCount)
            assertTrue(case.source, item.query.contains(case.name))
            val expectedSize = if (case.packUnit == "l") "1000 ml" else "${case.packValue} ${case.packUnit}"
            assertTrue(case.source, item.query.contains(expectedSize))
            assertTrue(case.source, item.query.contains(requireNotNull(case.packaging)))
            assertEquals(case.source, item.query, item.strictMatchPhrase)
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun productNameGenitiveContainerKeepsLiteralIdentityAndOptionalCountWithoutInventingSize() {
        val cases = listOf(
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("laal mirch powder ka packet", "laal mirch powder",
                quantity = null, quantityUnit = "unspecified", quantityText = null, packaging = "packet"),
            SourceIdentityCase("BrandA rice ki do bags", "BrandA rice",
                quantity = 2, quantityUnit = "pack", quantityText = "do", packaging = "bag"),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            assertTrue(case.source, prepared.fulfillmentConstraints.isEmpty())
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, if (case.quantity == null) Quantity.Default else Quantity.Count(case.quantity), item.quantity)
            assertEquals(case.source, case.quantity == null, item.intentQuantityProvenance?.origin ==
                com.example.beta.automation.IntentQuantityOrigin.UNSTATED)
            assertEquals(case.source, case.quantity != null, item.retailPackCount)
            val expectedNameTokens = ProductLexicon.tokenize(ProductLexicon.canonicalizeProductText(case.name))
            val actualQueryTokens = ProductLexicon.tokenize(item.query)
            assertTrue(case.source, actualQueryTokens.containsAll(expectedNameTokens))
            assertTrue(case.source, item.query.contains(requireNotNull(case.packaging)))
            assertEquals(case.source, item.query, item.strictMatchPhrase)
            assertEquals(case.source, case.quantityText, item.intentQuantityProvenance?.local?.atom)
        }
    }

    @Test fun productNameGenitiveContainerRejectsCropsUnboundAmountsAndUnsafeScope() {
        val invalid = listOf(
            SourceIdentityCase("chai patti ka ek packet", "patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "bag"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = null, quantityUnit = "unspecified", quantityText = null, packaging = "packet"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "count", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti ka only ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("BrandA organic rice ki do bags", "BrandA rice",
                quantity = 2, quantityUnit = "pack", quantityText = "do", packaging = "bag"),
            SourceIdentityCase("chai patti ka ek packet per child", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("each child needs chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti ka ek packet under 100", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("no chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti ka-ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti kа ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti ka\u200bek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("chai patti ka ek dabba", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "box"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet",
                instruction = "chai patti ka ek packet, organic"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet",
                instruction = "chai patti ka ek packet, sugar-free"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet",
                instruction = "chai patti ka ek packet, BrandA"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet",
                instruction = "organic, chai patti ka ek packet"),
            SourceIdentityCase("chai patti ka ek packet", "chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet",
                instruction = "BrandA, chai patti ka ek packet"),
        )
        invalid.forEach { case -> assertReviewBlocked(case, prepareSourceIdentity(case), requireNoItems = true) }

        val duplicateSource = "chai patti ka ek packet"
        val duplicate = SourceIdentityCase(duplicateSource, "chai patti", quantity = 1,
            quantityUnit = "pack", quantityText = "ek", packaging = "packet",
            instruction = "$duplicateSource; $duplicateSource")
        assertReviewBlocked(duplicate, prepareSourceIdentity(duplicate), requireNoItems = true)

        val supportedSource = "chai patti ka ek packet"
        val supported = SourceIdentityCase(supportedSource, "BrandA chai patti", quantity = 1,
            quantityUnit = "pack", quantityText = "ek", packaging = "packet",
            supporting = listOf(SourceIdentitySupport("modifier", "BrandA")),
            instruction = "BrandA; $supportedSource")
        assertReviewBlocked(supported, prepareSourceIdentity(supported), requireNoItems = true)

        val grouped = prepareGroupedSourceIdentity(
            SourceIdentityCase("BrandA chai patti ka ek packet", "BrandA chai patti",
                quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet"),
            SourceIdentityCase("BrandA rice ki do bags", "BrandA rice",
                quantity = 2, quantityUnit = "pack", quantityText = "do", packaging = "bag"),
            header = "BrandA: ",
        )
        assertTrue("group cannot authorize product-genitive proof", grouped.items.isEmpty())
    }

    @Test fun genitiveContainerRejectsQuantityTextThatIsNotInTheSource() {
        val forged = SourceIdentityCase("chai patti ka ek packet", "chai patti",
            quantity = 2, quantityUnit = "pack", quantityText = "two", packaging = "packet")
        val result = runCatching { prepareSourceIdentity(forged) }
        if (result.isSuccess) {
            assertReviewBlocked(forged, result.getOrThrow(), requireNoItems = true)
        } else {
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun productNameGenitiveContainerKeepsCompleteListsInBothOrders() {
        val tea = SourceIdentityCase("chai patti ka ek packet", "chai patti",
            quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet")
        val eggs = SourceIdentityCase("two eggs", "eggs",
            quantity = 2, quantityUnit = "count", quantityText = "two")
        listOf(", ", "; ", " and ").forEach { separator ->
            listOf(listOf(tea, eggs), listOf(eggs, tea)).forEach { cases ->
                val prepared = prepareSourceIdentityBasket(cases, separator)
                assertEquals(cases.map { it.source }, prepared.items.map { it.rawText })
                assertEquals(2, prepared.items.size)
                assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            }
        }
    }

    @Test fun productNameGenitiveContainerCannotBorrowBrandFromSplitRows() {
        val teaSource = "chai patti ka ek packet"
        listOf(false, true).forEach { clarifyBrand ->
            val brand = SourceIdentityCase("BrandA", "BrandA", quantity = null,
                quantityUnit = "unspecified", quantityText = null,
                needsClarification = clarifyBrand)
            val tea = SourceIdentityCase(teaSource, "chai patti", quantity = 1,
                quantityUnit = "pack", quantityText = "ek", packaging = "packet")
            val prefix = prepareSourceIdentityBasket(listOf(brand, tea), separator = "",
                instructionText = "BrandA $teaSource")
            assertFalse(prefix.items.any { it.rawText == teaSource })

            val suffix = prepareSourceIdentityBasket(listOf(tea, brand), separator = "",
                instructionText = "$teaSource, BrandA")
            assertFalse(suffix.items.any { it.rawText == teaSource })
        }
    }

    @Test fun productNameGenitiveContainerKeepsCompleteSupportedRequestPrefix() {
        val tea = SourceIdentityCase("chai patti ka ek packet", "chai patti",
            quantity = 1, quantityUnit = "pack", quantityText = "ek", packaging = "packet")
        val eggs = SourceIdentityCase("two eggs", "eggs",
            quantity = 2, quantityUnit = "count", quantityText = "two")
        val intro = "I need these groceries today:"
        val prepared = prepareSourceIdentityBasket(listOf(tea, eggs), "; ",
            instructionText = intro + " " + tea.source + "; " + eggs.source, fulfillment = listOf(intro))
        assertEquals(listOf(tea.source, eggs.source), prepared.items.map { it.rawText })
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        assertTrue(prepared.fulfillmentConstraints.contains(intro))
    }

    @Test fun productGenitiveTargetIsNotAdmittedNextToInvalidIdentityOrPropertyPeer() {
        val teaSource = "chai patti ka ek packet"
        val tea = SourceIdentityCase(teaSource, "chai patti", quantity = 1,
            quantityUnit = "pack", quantityText = "ek", packaging = "packet")
        val invalidIdentityPeer = SourceIdentityCase("BrandA 500 g", "rice",
            quantity = 500, quantityUnit = "g", quantityText = "500")
        val identityReview = prepareSourceIdentityBasket(listOf(invalidIdentityPeer, tea))
        assertFalse(identityReview.items.any { it.rawText == teaSource })

        val invalidPropertyPeer = SourceIdentityCase("one BrandA", "BrandA",
            quantity = 1, quantityUnit = "count", quantityText = "one",
            required = listOf("organic"))
        val propertyReview = prepareSourceIdentityBasket(listOf(invalidPropertyPeer, tea))
        assertFalse(propertyReview.items.any { it.rawText == teaSource })
    }

    @Test fun invalidIdentityNeighborCannotBridgeAThreeItemChain() {
        val teaSource = "chai patti ka ek packet"
        val peer = SourceIdentityCase("BrandA 500 g", "rice", quantity = 500,
            quantityUnit = "g", quantityText = "500")
        val tea = SourceIdentityCase(teaSource, "chai patti", quantity = 1,
            quantityUnit = "pack", quantityText = "ek", packaging = "packet")
        val eggs = SourceIdentityCase("two eggs", "eggs", quantity = 2,
            quantityUnit = "count", quantityText = "two")
        val prepared = prepareSourceIdentityBasket(listOf(peer, tea, eggs))
        assertFalse(prepared.items.any { it.rawText == teaSource })
        assertTrue(prepared.items.any { it.rawText == eggs.source })
    }

    @Test fun v2TypedPackContentsRequireTheExplicitNounAndPerPackCountEndToEnd() {
        val source = "one BrandA cough lozenges pack of 24 lozenges"
        val base = SourceIdentityCase(
            source = source,
            name = "BrandA cough lozenges",
            quantity = 1,
            quantityUnit = "count",
            quantityText = "one",
            packValue = "24",
            packUnit = "piece",
            packText = "24",
            required = listOf("lozenges"),
            packaging = "pack",
        )
        listOf(base, base.copy(required = listOf("lozenge"))).forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals("${case.required} -> one row", 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(source, item.rawText)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.strictMatchPhrase.orEmpty().contains("24 pieces"))
            assertTrue(item.strictMatchPhrase.orEmpty().contains(case.required.single()))
            assertEquals(item.query, item.strictMatchPhrase)
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            assertTrue(swiggyMatchesProductIdentity(item, "BrandA Cough Lozenges pack 24 lozenges"))
            assertTrue(swiggyMatchesProductIdentity(item, "BrandA Cough Lozenges pack lozenge 24 pieces"))
            assertFalse(swiggyMatchesProductIdentity(item, "BrandA Cough Lozenges pack 24 tablets"))
            assertFalse(swiggyMatchesProductIdentity(item, "BrandA Cough Lozenges pack 25 lozenges"))
        }

        val packSource = source
        val explicitPack = base.copy(source = packSource, instruction = packSource,
            packaging = "pack", quantityUnit = "pack")
        val explicitItem = prepareSourceIdentity(explicitPack).items.single()
        assertEquals(Quantity.Count(1), explicitItem.quantity)
        assertTrue(explicitItem.retailPackCount)
        assertTrue(swiggyMatchesProductIdentity(explicitItem, "BrandA Cough Lozenges pack 24 lozenges"))
        assertFalse(swiggyMatchesProductIdentity(explicitItem, "BrandA Cough Lozenges pack 24 tablets"))
        assertFalse(swiggyMatchesProductIdentity(explicitItem, "BrandA Cough Lozenges pack 25 lozenges"))

        // A backend-ready row is not whole-basket proof: a cropped primary
        // must not hide its uncited contents noun from the final review gate.
        val cropped = explicitPack.copy(source = packSource.removeSuffix(" lozenges"),
            required = emptyList())
        val croppedReview = prepareSourceIdentity(cropped)
        assertTrue(croppedReview.unresolvedText.any { it.contains("lozenges") })
        assertReviewBlocked(cropped, croppedReview)

        listOf(
            // A comma-separated quantity without a source-local pack role stays held.
            base.copy(source = "one BrandA cough drops, 24 lozenges",
                instruction = "one BrandA cough drops, 24 lozenges"),
            base.copy(name = "BrandA cough", required = emptyList()),
            base.copy(required = listOf("tablets")),
            base.copy(packValue = "25"),
        ).forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("required=${case.required}, pack=${case.packValue}", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun v2DiscretePackContentsDoNotInventPurchaseQuantityOrServingOwnership() {
        val unspecifiedCase = SourceIdentityCase(
            source = "pack of 25 apples",
            name = "apples",
            packaging = "pack",
            quantity = null,
            quantityUnit = "unspecified",
            quantityText = null,
            packValue = "25",
            packUnit = "piece",
            packText = "25",
        )
        val unspecified = prepareSourceIdentity(unspecifiedCase)
        assertEquals(1, unspecified.items.size)
        assertEquals(Quantity.Default, unspecified.items.single().quantity)
        assertFalse(unspecified.items.single().retailPackCount)
        assertEquals("apples pack 25 pieces", unspecified.items.single().strictMatchPhrase)

        listOf(
            SourceIdentityCase(
                source = "one pack of 2 cups of tea",
                name = "tea",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = "2",
                packUnit = "piece",
                packText = "2",
            ),
            SourceIdentityCase(
                source = "one cup of tea pack of 2 tea bags",
                name = "tea bags",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = "2",
                packUnit = "piece",
                packText = "2",
                packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one 24-pack BrandA drops",
                name = "BrandA drops",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = "24",
                packUnit = "piece",
                packText = "24",
                packaging = "pack",
            ),
        ).forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("must not infer discrete contents role: ${case.source}", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared, requireNoItems = true)
        }
    }

    @Test fun discretePackPieceRoleCannotMergeASecondProductOrIgnoreExplicitPackEvidence() {
        val separate = "one BrandA remote, 2 batteries"
        val remote = SourceIdentityCase(
            source = separate,
            name = "BrandA remote batteries",
            quantity = 1,
            quantityUnit = "count",
            quantityText = "one",
            packValue = "2",
            packUnit = "piece",
            packText = "2",
            required = listOf("batteries"),
        )
        val held = listOf(
            separate,
            "one BrandA remote; 2 batteries",
            "one BrandA remote\n2 batteries",
            "one BrandA remote and 2 batteries",
            "one BrandA remote aur 2 batteries",
            "one BrandA remote with 2 batteries",
            "one BrandA remote plus 2 batteries",
            "one BrandA remote extra 2 batteries",
            "one BrandA remote including 2 batteries",
            "one BrandA remote, a pack of 2 batteries",
            "one BrandA remote, one pack of 2 batteries",
            "one BrandA remote a pack of 2 batteries",
            "one BrandA remote 2 batteries pack",
        )
        fun fixtureRow(case: SourceIdentityCase): GroceryIntentReviewV2ReadyRow {
            val item = GroceryIntentReviewV2Item(
                name = case.name,
                quantity = case.quantity?.let(::BigDecimal),
                quantityUnit = case.quantityUnit,
                packValue = case.packValue?.let(::BigDecimal),
                packUnit = case.packUnit,
                packaging = case.packaging,
                required = case.required,
                excluded = case.excluded,
                sourceText = case.source,
                supportingText = emptyList(),
                needsClarification = false,
                quantityText = case.quantityText,
                packText = case.packText,
            )
            return GroceryIntentReviewV2ReadyRow(
                rowIndex = 0,
                item = item,
                evidence = GroceryIntentReviewV2Evidence(
                    GroceryIntentReviewV2Quote(case.source, 0, case.source.length), emptyList(),
                ),
                quantityOrigin = "stated",
                amountRoleStatus = "matched",
            )
        }
        held.forEach { source ->
            val case = remote.copy(source = source, instruction = source,
                packaging = "pack".takeIf { "pack of" in source })
            val prepared = prepareSourceIdentity(case)
            assertTrue("must not merge a separate pack/product: $source", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared, requireNoItems = true)
            val row = fixtureRow(case)
            assertEquals("wire amount role remains untrusted: $source", "matched", row.amountRoleStatus)
            assertEquals("$source -> explicit discrete pack role", "discrete_pack_role_unproven",
                row.discretePackRoleIssue())
        }

        // The outer box follows the contents atom; the product itself also
        // includes an inner bag noun, so a blanket container cap is unsafe.
        val teaBagsInBox = SourceIdentityCase(
            source = "one BrandA tea bags 20 pieces box",
            name = "BrandA tea bags",
            quantity = 1,
            quantityUnit = "count",
            quantityText = "one",
            packValue = "20",
            packUnit = "piece",
            packText = "20",
            packaging = "box",
        )
        val prepared = prepareSourceIdentity(teaBagsInBox)
        assertEquals(1, prepared.items.size)
        assertEquals(teaBagsInBox.source, prepared.items.single().rawText)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertTrue(prepared.items.single().query.contains("BrandA tea bags"))
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun unspokenPurchaseCountCannotMaskAProductBeforePiecePackEvidence() {
        listOf(
            SourceIdentityCase(
                source = "BrandA remote pack of 2 batteries",
                name = "BrandA remote batteries",
                quantity = null,
                quantityUnit = "unspecified",
                quantityText = null,
                packValue = "2",
                packUnit = "piece",
                packText = "2",
                packaging = "pack",
                required = listOf("batteries"),
            ),
            SourceIdentityCase(
                source = "BrandA cough drops pack of 24 lozenges",
                name = "BrandA cough drops lozenges",
                quantity = null,
                quantityUnit = "unspecified",
                quantityText = null,
                packValue = "24",
                packUnit = "piece",
                packText = "24",
                packaging = "pack",
                required = listOf("lozenges"),
            ),
        ).forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("pack contents must be scoped to the product: ${case.source}", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared, requireNoItems = true)
        }
    }

    @Test fun productPrefixBeforePackQuantityCannotScopeTypedContents() {
        listOf(
            SourceIdentityCase(
                source = "one BrandA remote pack of 2 batteries",
                name = "BrandA remote batteries",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = "2",
                packUnit = "piece",
                packText = "2",
                packaging = "pack",
                required = listOf("batteries"),
            ),
            SourceIdentityCase(
                source = "BrandA remote one pack of 2 batteries",
                name = "BrandA remote batteries",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = "2",
                packUnit = "piece",
                packText = "2",
                packaging = "pack",
                required = listOf("batteries"),
            ),
            SourceIdentityCase(
                source = "one BrandA remote 2 pcs pack batteries",
                name = "BrandA remote batteries",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
                packValue = "2",
                packUnit = "piece",
                packText = "2",
                packaging = "pack",
                required = listOf("batteries"),
            ),
        )
        .forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue(case.source, prepared.items.isEmpty())
            assertReviewBlocked(case, prepared, requireNoItems = true)
        }
    }

    @Test fun repeatedTypedContentsAndGenericPieceCountsRemainValid() {
        val accepted = listOf(
            SourceIdentityCase(
                source = "one BrandA eggs pack of 6 eggs", name = "BrandA eggs",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one BrandA batteries pack of 2 batteries", name = "BrandA batteries",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "2", packUnit = "piece", packText = "2", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one BrandA cough lozenges pack of 24 lozenges", name = "BrandA cough lozenges",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "24", packUnit = "piece", packText = "24", packaging = "pack",
                required = listOf("lozenges"),
            ),
            SourceIdentityCase(
                source = "one BrandA eggs pack of 6 pieces", name = "BrandA eggs",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one BrandA eggs pack of 6", name = "BrandA eggs",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one BrandA eggs 6 pieces pack", name = "BrandA eggs",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one BrandA eggs 6 pieces pack of eggs", name = "BrandA eggs",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "two packs of 6 eggs", name = "eggs",
                quantity = 2, quantityUnit = "pack", quantityText = "two",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
        )
        accepted.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, Quantity.Count(case.quantity!!), item.quantity)
            assertEquals(case.source, case.quantityUnit == "pack", item.retailPackCount)
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun wrongTypedContentsCannotBeBorrowedFromNeighboringProductWords() {
        val rejected = listOf(
            SourceIdentityCase(
                source = "one BrandA cough drops pack of 24 lozenges", name = "BrandA cough drops lozenges",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "24", packUnit = "piece", packText = "24", packaging = "pack",
                required = listOf("lozenges"),
            ),
            SourceIdentityCase(
                source = "one BrandA remote pack of 2 batteries", name = "BrandA remote batteries",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "2", packUnit = "piece", packText = "2", packaging = "pack",
                required = listOf("batteries"),
            ),
            SourceIdentityCase(
                source = "one BrandA remote pack of 24 tablets", name = "BrandA remote tablets",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "24", packUnit = "piece", packText = "24", packaging = "pack",
                required = listOf("tablets"),
            ),
            SourceIdentityCase(
                source = "one BrandA apple juice pack of 6 orange juice", name = "BrandA apple juice orange juice",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "6", packUnit = "piece", packText = "6", packaging = "pack",
            ),
            SourceIdentityCase(
                source = "one BrandA remote 2 pcs pack batteries", name = "BrandA remote batteries",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                packValue = "2", packUnit = "piece", packText = "2", packaging = "pack",
                required = listOf("batteries"),
            ),
        )
        rejected.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("contents must not be borrowed from product identity: ${case.source}", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared, requireNoItems = true)
        }

        fun assertForgedMatchedRowIsScoped(case: SourceIdentityCase) {
            val wireItem = GroceryIntentReviewV2Item(
                name = case.name,
                quantity = case.quantity?.let(::BigDecimal),
                quantityUnit = case.quantityUnit,
                packValue = case.packValue?.let(::BigDecimal),
                packUnit = case.packUnit,
                packaging = case.packaging,
                required = case.required,
                excluded = case.excluded,
                sourceText = case.source,
                supportingText = emptyList(),
                needsClarification = false,
                quantityText = case.quantityText,
                packText = case.packText,
            )
            val forgedMatchedRow = GroceryIntentReviewV2ReadyRow(
                rowIndex = 0,
                item = wireItem,
                evidence = GroceryIntentReviewV2Evidence(
                    GroceryIntentReviewV2Quote(case.source, 0, case.source.length), emptyList(),
                ),
                quantityOrigin = "stated",
                amountRoleStatus = "matched",
            )
            assertEquals("matched amount metadata cannot bypass product scope: ${case.source}",
                "pack_contents_scope_unresolved", forgedMatchedRow.discretePackRoleIssue())
        }
        assertForgedMatchedRowIsScoped(rejected[1]) // remote + batteries
        assertForgedMatchedRowIsScoped(rejected[2]) // product prefix + tablet dosage noun
        assertForgedMatchedRowIsScoped(rejected[4]) // suffix generic pieces before container
    }

    @Test fun productRelativePackGrammarRejectsUnboundAmountsIdentityDriftAndNearForms() {
        val valid = SourceIdentityCase(
            source = "BrandA milk ka ek 1 litre wala pouch",
            name = "BrandA milk",
            quantity = 1,
            quantityUnit = "pack",
            quantityText = "ek",
            packValue = "1",
            packUnit = "l",
            packText = "1",
            packaging = "pouch",
        )
        val invalid = listOf(
            valid.copy(name = "milk"),
            valid.copy(quantity = 2),
            valid.copy(packValue = "500"),
            valid.copy(packUnit = "ml"),
            valid.copy(packaging = null),
            valid.copy(packaging = "packet"),
            valid.copy(packValue = null, packUnit = "unspecified", packText = null),
            valid.copy(source = "BrandA milk ka only ek 1 litre wala pouch",
                instruction = "BrandA milk ka only ek 1 litre wala pouch"),
            valid.copy(source = "BrandA milk ka ek 1 litre roughly wala pouch",
                instruction = "BrandA milk ka ek 1 litre roughly wala pouch"),
            valid.copy(instruction = "${valid.source}; ${valid.source}"),
            valid.copy(source = "BrandA milk ka ek 1 litre wala pouch and atta",
                instruction = "BrandA milk ka ek 1 litre wala pouch and atta"),
            valid.copy(source = "BrandA milk ka ek 1 litre Wala pouch",
                instruction = "BrandA milk ka ek 1 litre Wala pouch"),
            valid.copy(source = "BrandA milk 1 litre Ke ek pouch",
                instruction = "BrandA milk 1 litre Ke ek pouch"),
            SourceIdentityCase(
                source = "BrandA tea leaves 250 g ke roughly do packets",
                name = "BrandA tea leaves",
                quantity = 2,
                quantityUnit = "pack",
                quantityText = "do",
                packValue = "250",
                packUnit = "g",
                packText = "250",
                packaging = "packet",
            ),
        )
        invalid.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertReviewBlocked(case, prepared, requireNoItems = true)
        }
    }

    @Test fun hindiPackSizeConnectorUsesExistingPackRoleAndKeepsAllOriginalIdentity() {
        listOf("ka", "ki", "ke").forEach { connector ->
            val case = SourceIdentityCase("2 BrandA milk $connector 1 litre pouch", "BrandA milk",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1",
                packUnit = "l", packText = "1", packaging = "pouch")
            val result = prepareSourceIdentity(case)
            assertEquals(case.source, 1, result.items.size)
            assertEquals(Quantity.Count(2), result.items.single().quantity)
            assertTrue(result.items.single().retailPackCount)
            assertTrue(result.items.single().query.contains("BrandA milk"))
            assertTrue(result.items.single().query.contains("1000 ml"))
            assertTrue(result.items.single().query.contains("pouch"))
            assertTrue(result.unresolvedText.isEmpty() && result.excludedText.isEmpty())
            listOf(
                case.copy(name = "milk"), case.copy(packaging = null),
                case.copy(quantityUnit = "count"), case.copy(packValue = null, packUnit = "unspecified", packText = null),
                case.copy(source = "2 BrandA milk Ke 1 litre pouch", instruction = "2 BrandA milk Ke 1 litre pouch"),
                case.copy(source = "2 BrandA organic milk ke 1 litre pouch", instruction = "2 BrandA organic milk ke 1 litre pouch"),
                case.copy(source = "2 BrandA milk ke only 1 litre pouch", instruction = "2 BrandA milk ke only 1 litre pouch"),
                case.copy(source = "2 ke 1 litre BrandA milk pouch", instruction = "2 ke 1 litre BrandA milk pouch"),
            ).forEach { bad -> assertTrue(bad.source, prepareSourceIdentity(bad).items.isEmpty()) }
        }
        val source = "2 milk ke 1 litre pouch"
        val supported = SourceIdentityCase(source, "BrandA milk",
            quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1",
            packUnit = "l", packText = "1", packaging = "pouch",
            supporting = listOf(SourceIdentitySupport("modifier", "BrandA milk")),
            instruction = "$source; BrandA milk")
        assertTrue(prepareSourceIdentity(supported).items.isEmpty())
    }

    @Test fun primaryAppositionUsesOnlyLiteralRetainedIdentityAndKeepsOriginalQuantity() {
        val cases = listOf(
            SourceIdentityCase("one BrandA Fenugreek, methi wala", "BrandA Fenugreek"),
            SourceIdentityCase("ek BrandA Foxtail Millet, kangni", "BrandA Foxtail Millet", quantityText = "ek"),
            SourceIdentityCase("ek BrandA Cinnamon Whole, dalchini wali", "BrandA Cinnamon Whole", quantityText = "ek"),
            SourceIdentityCase("one BrandA oat milk, oat wala", "BrandA oat milk"),
            SourceIdentityCase("one BrandA Green Cardamom Whole, elaichi green wale", "BrandA Green Cardamom Whole"),
        )
        cases.forEach { case ->
            val result = prepareSourceIdentity(case)
            assertEquals(case.source, 1, result.items.size)
            assertEquals(case.source, Quantity.Count(1), result.items.single().quantity)
            assertTrue(case.source, result.excludedText.isEmpty() && result.unresolvedText.isEmpty())
        }
        listOf(
            SourceIdentityCase("one BrandA Fenugreek, methi Wala", "BrandA Fenugreek"),
            SourceIdentityCase("one BrandA Fenugreek, methi wala extra", "BrandA Fenugreek extra"),
            cases[0].copy(name = "Fenugreek"),
            cases[0].copy(name = "BrandA Fenugreek seeds"),
            cases[4].copy(name = "BrandA Cardamom Whole"),
            SourceIdentityCase("one tea, Chai Wala", "tea"),
            SourceIdentityCase("one kangni", "foxtail millet"),
            SourceIdentityCase("one BrandA tea without cardamom, elaichi wali", "BrandA tea without cardamom"),
            SourceIdentityCase("one BrandA Fenugreek, methi-wala", "BrandA Fenugreek"),
            SourceIdentityCase("one BrandA Fenugreek, methi's", "BrandA Fenugreek"),
            SourceIdentityCase("one BrandA Fenugreek\n, methi wala", "BrandA Fenugreek"),
            SourceIdentityCase("one BrandA \u00e9fenugreek, methi", "BrandA fenugreek"),
            SourceIdentityCase("one BrandA Fenugreek\u0301, methi", "BrandA Fenugreek"),
            SourceIdentityCase("one BrandA Cardamo\u212a, elai\u212ahi", "BrandA Cardamo\u212a cardamom"),
        ).forEach { case -> assertTrue(case.source, prepareSourceIdentity(case).items.isEmpty()) }
    }

    @Test fun verifiedLanguageAliasesSurviveBothGroundingDirectionsWithoutInventingVariants() {
        listOf("sebu" to "apple", "hasiru menasinakaayi" to "green chilli",
            "hasiru menasinakayi" to "green chilli", "dalimbe" to "pomegranate",
            "daalimbe" to "pomegranate").forEach { (source, canonical) ->
            val prepared = prepareSourceIdentity(SourceIdentityCase("one $source", canonical))
            assertEquals(source, 1, prepared.items.size)
            assertEquals(Quantity.Count(1), prepared.items.single().quantity)
            assertTrue(prepared.excludedText.isEmpty())
        }
        listOf(
            SourceIdentityCase("one hasiru menasinakayi", "chilli"),
            SourceIdentityCase("one hasiru menasinakayi", "red chilli"),
            SourceIdentityCase("one hasiru menasinakayi", "capsicum"),
            SourceIdentityCase("one menasinakayi", "green chilli"),
            SourceIdentityCase("one dalimbe", "pomegranate juice"),
            SourceIdentityCase("one sebu", "gala apple"),
        ).forEach { case -> assertTrue(case.source, prepareSourceIdentity(case).items.isEmpty()) }
    }

    @Test fun verifiedGroceryAliasesKeepV2NamesCountsAndPackMeasurementsExact() {
        val cases = listOf(
            SourceIdentityCase(
                source = "BrandA shunti 250 g ke do packets",
                name = "BrandA ginger",
                quantity = 2,
                quantityUnit = "pack",
                quantityText = "do",
                packValue = "250",
                packUnit = "g",
                packText = "250",
                packaging = "packet",
            ),
            SourceIdentityCase("one BrandA elaichi", "BrandA cardamom"),
            SourceIdentityCase("one BrandA dalchini", "BrandA cinnamon"),
            SourceIdentityCase("one no added sugar kittale hannu, zero maida", "orange",
                required = listOf("no_added_sugar"), excluded = listOf("maida")),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, Quantity.Count(case.quantity!!), item.quantity)
            assertTrue(case.source, item.query.contains(case.name))
            if (case.required.contains("no_added_sugar")) {
                assertTrue(case.source, item.query.contains("no added sugar", ignoreCase = true))
                assertEquals(case.source, listOf("maida"), item.avoidPhrases)
            }
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
        val measured = prepareSourceIdentity(cases.first()).items.single()
        assertTrue(measured.retailPackCount)
        assertTrue(measured.query.contains("250 g"))
        assertTrue(measured.query.contains("packet"))

        listOf(
            SourceIdentityCase("one green elaichi", "green cardamom"),
            SourceIdentityCase("one whole dalchini", "whole cinnamon"),
        ).forEach { case ->
            assertEquals(case.source, 1, prepareSourceIdentity(case).items.size)
        }
        listOf(
            SourceIdentityCase("one green elaichi", "cardamom"),
            SourceIdentityCase("one whole dalchini", "cinnamon"),
            SourceIdentityCase("one shunti", "ginger powder"),
            SourceIdentityCase("one kittale hannu", "fresh orange"),
        ).forEach { case ->
            assertTrue(case.source, prepareSourceIdentity(case).items.isEmpty())
        }
    }

    @Test fun sharedHindiIdentityAliasesPrepareExactCanonicalQueriesAndKeepOriginalEvidence() {
        val aliases = listOf(
            "chai patti" to "tea leaves", "santra" to "orange", "santre" to "orange",
            "laal mirch" to "red chilli", "kali mirch" to "black pepper",
            "hari mirch" to "green chilli", "methi" to "fenugreek",
        )
        aliases.forEach { (sourceName, canonicalName) ->
            val source = "one $sourceName"
            listOf(sourceName, canonicalName).forEach { proposedName ->
                val prepared = prepareSourceIdentity(SourceIdentityCase(source, proposedName))
                assertEquals(source, 1, prepared.items.size)
                val item = prepared.items.single()
                assertEquals(source, Quantity.Count(1), item.quantity)
                assertEquals(source, source, item.rawText)
                assertEquals("$sourceName -> query", canonicalName, item.query)
                assertEquals("$sourceName -> strict phrase", canonicalName, item.strictMatchPhrase)
                assertTrue(source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            }
        }
        listOf(
            "methi leaves" to "fenugreek leaves", "methi seeds" to "fenugreek seeds",
            "dried methi" to "dried fenugreek", "methi juice" to "fenugreek juice",
        ).forEach { (sourceName, canonicalName) ->
            val source = "one $sourceName"
            val prepared = prepareSourceIdentity(SourceIdentityCase(source, canonicalName))
            assertEquals(source, 1, prepared.items.size)
            assertEquals(canonicalName, prepared.items.single().query)
            assertEquals(source, source, prepared.items.single().rawText)
        }
        listOf(
            SourceIdentityCase("one methi", "fenugreek leaves"),
            SourceIdentityCase("one methi", "fenugreek seeds"),
            SourceIdentityCase("one methi", "dried fenugreek"),
            SourceIdentityCase("one methi_suffix", "fenugreek"),
            SourceIdentityCase("one methi's", "fenugreek"),
            SourceIdentityCase("one methi\u0301", "fenugreek"),
            SourceIdentityCase("one kali; mirch", "black pepper"),
            SourceIdentityCase("one hari; mirch", "green chilli"),
        ).forEach { case -> assertTrue(case.source, prepareSourceIdentity(case).items.isEmpty()) }

        val measured = SourceIdentityCase(
            source = "2 packets of BrandA laal mirch 500 g",
            name = "BrandA laal mirch",
            quantity = 2,
            quantityUnit = "pack",
            quantityText = "2",
            packValue = "500",
            packUnit = "g",
            packText = "500",
            packaging = "packet",
        )
        val prepared = prepareSourceIdentity(measured)
        assertEquals(measured.source, 1, prepared.items.size)
        assertEquals(Quantity.Count(2), prepared.items.single().quantity)
        assertTrue(prepared.items.single().query.contains("BrandA red chilli"))
        assertTrue(prepared.items.single().query.contains("500 g"))
        assertEquals(measured.source, measured.source, prepared.items.single().rawText)
    }

    @Test fun sourcePositiveProductIdentityCannotBeDroppedOrMovedToExclusions() {
        val cases = listOf(
            SourceIdentityCase("one ExampleBrand chocolate", "chocolate"),
            SourceIdentityCase("one strawberry yogurt", "yogurt"),
            SourceIdentityCase("one whole masoor lentil", "masoor lentil"),
            SourceIdentityCase("one Add gel pen", "gel pen"),
            SourceIdentityCase("one Get chocolate", "chocolate"),
            SourceIdentityCase(
                source = "one chocolate",
                name = "chocolate",
                supporting = listOf(SourceIdentitySupport("modifier", "ExampleBrand dark chocolate")),
                instruction = "one chocolate; ExampleBrand dark chocolate",
            ),
            SourceIdentityCase("one strawberry yogurt", "yogurt", excluded = listOf("strawberry")),
            SourceIdentityCase("one strawberry yogurt", "strawberry yogurt", excluded = listOf("strawberry")),
        )

        cases.forEach { case ->
            val result = prepareSourceIdentity(case)

            assertTrue("${case.source} -> ${case.name}", result.items.isEmpty())
            assertFalse("${case.source} -> ${case.name}", result.excludedText.isEmpty())
        }
    }

    @Test fun completeSourceIdentityAndOrdinaryAmountsRemainUsable() {
        listOf(
            SourceIdentityCase("one ExampleBrand chocolate", "ExampleBrand chocolate"),
            SourceIdentityCase("one strawberry yogurt", "strawberry yogurt"),
            SourceIdentityCase("one whole masoor lentil", "whole masoor lentil"),
            SourceIdentityCase("one Add gel pen", "Add gel pen"),
            SourceIdentityCase("Can you get one ExampleBrand chocolate", "ExampleBrand chocolate"),
        ).forEach { case ->
            val result = prepareSourceIdentity(case)

            assertEquals(case.source, 1, result.items.size)
            assertEquals(case.source, Quantity.Count(1), result.items.single().quantity)
            assertTrue(result.excludedText.isEmpty())
        }

        val source = "two 500 ml ExampleBrand milk bottles"
        val result = prepareSourceIdentity(SourceIdentityCase(
            source = source,
            name = "ExampleBrand 500 ml milk",
            quantity = 2,
            quantityUnit = "pack",
            quantityText = "two",
            packValue = "500",
            packUnit = "ml",
            packText = "500",
            packaging = "bottle",
        ))

        assertEquals(1, result.items.size)
        assertEquals(Quantity.Count(2), result.items.single().quantity)
        assertTrue(result.items.single().retailPackCount)
        assertTrue(result.items.single().query.contains("500 ml", ignoreCase = true))
        assertTrue(result.excludedText.isEmpty())

        val wattage = "one 9 watt bulb"
        val retainedDescriptor = prepareSourceIdentity(SourceIdentityCase(
            source = wattage,
            name = "9 watt bulb",
        ))
        assertEquals(1, retainedDescriptor.items.size)
        assertEquals(Quantity.Count(1), retainedDescriptor.items.single().quantity)
        assertTrue(retainedDescriptor.items.single().query.contains("9 watt", ignoreCase = true))
        assertTrue(retainedDescriptor.excludedText.isEmpty())
    }

    @Test fun adjacentTypedPieceUnitIsQuantityButOtherPieceWordsRemainIdentity() {
        val good = prepareSourceIdentity(SourceIdentityCase(
            "two pieces chocolate", "chocolate", quantity = 2, quantityUnit = "piece", quantityText = "two",
        ))
        assertEquals(Quantity.Count(2), good.items.single().quantity)
        assertTrue(good.items.single().logicalPieceCount)
        val missingIdentity = prepareSourceIdentity(SourceIdentityCase(
            "two pieces chocolate walnut pieces", "chocolate walnut", quantity = 2,
            quantityUnit = "piece", quantityText = "two",
        ))
        assertTrue(missingIdentity.items.isEmpty())
    }

    @Test fun closedColloquialGlueKeepsBrandFlavourNegationAndExactAmount() {
        val cases = listOf(
            SourceIdentityCase("ExampleBrand no added sugar yogurt mixed berries wala ek",
                "ExampleBrand no added sugar yogurt mixed berries", quantityText = "ek", required = listOf("no_added_sugar")),
            SourceIdentityCase("ExampleBrand nuts no palm oil wala ek", "ExampleBrand nuts",
                quantityText = "ek", excluded = listOf("palm_oil")),
            SourceIdentityCase("Cinnamon whole from ExampleBrand one", "ExampleBrand cinnamon whole"),
            SourceIdentityCase("ExampleBrand ka organic curd, ek", "ExampleBrand organic curd", quantityText = "ek", required = listOf("organic")),
            SourceIdentityCase("ExampleBrand yogurt in mixed berries, one", "ExampleBrand yogurt mixed berries"),
            SourceIdentityCase("one sweet gala apple also", "sweet gala apple"),
            SourceIdentityCase("Cinnamon whole from Example Brand one", "Example Brand cinnamon whole"),
        )
        cases.forEach { case ->
            val result = prepareSourceIdentity(case)
            assertEquals(case.source, 1, result.items.size)
            assertEquals(case.source, Quantity.Count(1), result.items.single().quantity)
            assertTrue(result.excludedText.isEmpty())
        }
        assertEquals(listOf("palm oil"), prepareSourceIdentity(cases[1]).items.single().avoidPhrases)
        listOf(
            cases[0].copy(name = "ExampleBrand no added sugar yogurt"),
            cases[1].copy(excluded = emptyList()),
            cases[2].copy(name = "cinnamon whole"),
            cases[3].copy(name = "organic curd"),
            cases[4].copy(name = "ExampleBrand yogurt"),
            cases[5].copy(name = "gala apple"),
            cases[6].copy(name = "Example cinnamon whole"),
            SourceIdentityCase("one From chocolate", "chocolate"),
            SourceIdentityCase("one Ka chocolate", "chocolate"),
            SourceIdentityCase("one In chocolate", "chocolate"),
            SourceIdentityCase("one Wala chocolate", "chocolate"),
            SourceIdentityCase("one Also chocolate", "chocolate"),
        ).forEach { case -> assertTrue(case.source, prepareSourceIdentity(case).items.isEmpty()) }
    }

    @Test fun terminalLowercaseParticleKeepsCompleteProtectedPropertyAndNegativeConstraint() {
        val cases = listOf(
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, low-fat wali", "BrandA snack",
                required = listOf("low_fat"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, organic wale", "BrandA snack",
                required = listOf("organic"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, high-protein wala", "BrandA snack",
                required = listOf("high_protein"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, sugar-free wali", "BrandA snack",
                required = listOf("sugar_free"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, no-added-sugar wale", "BrandA snack",
                required = listOf("no_added_sugar"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, unsweetened wala", "BrandA snack",
                required = listOf("unsweetened"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, ready-to-eat wali", "BrandA snack",
                required = listOf("ready_to_eat"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, zero maida, HIGH-FIBRE wala", "BrandA snack",
                required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA high fibre biscuit, zero maida, high fibre wala",
                "BrandA high fibre biscuit", excluded = listOf("maida")),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            assertEquals(case.source, Quantity.Count(1), prepared.items.single().quantity)
            assertFalse(case.source, prepared.items.single().query.contains("maida", ignoreCase = true))
            assertEquals(case.source, listOf("maida"), prepared.items.single().avoidPhrases)
            assertTrue(case.source, prepared.items.single().query.contains("BrandA", ignoreCase = true))
            assertTrue(case.source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun terminalParticleKeepsCompleteGeneralRetainedDescriptor() {
        val cases = listOf(
            SourceIdentityCase("one BrandA biscuit, atta wala", "BrandA biscuit atta"),
            SourceIdentityCase("one BrandA snack, masala wali", "BrandA snack masala"),
            SourceIdentityCase("one BrandA rice, whole wale", "BrandA whole rice"),
            SourceIdentityCase("one BrandA apple, red wala", "BrandA red apple"),
            SourceIdentityCase("one BrandA tea, BrandB wali", "BrandA tea BrandB"),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            assertTrue(case.source, prepared.items.single().query.contains(case.name.substringAfter("BrandA "), ignoreCase = true))
            assertTrue(case.source, prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun terminalParticleCannotDropProtectedPropertyOrClaimUnprovenTail() {
        val primary = "one BrandA digestive biscuit, zero maida, high fibre wala"
        val rejected = listOf(
            SourceIdentityCase(primary, "BrandA digestive biscuit", excluded = listOf("maida")),
            SourceIdentityCase(primary, "digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre Wala",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, atta wala",
                "BrandA digestive biscuit", excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, last wala",
                "BrandA digestive biscuit last", excluded = listOf("maida")),
            SourceIdentityCase("one BrandA tea, zero maida, Chai Wala", "BrandA tea", excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala extra",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala if available",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala, not low fat",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala, make it two",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala nahi chahiye",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA digestive biscuit, zero maida, high fibre wala mat lena",
                "BrandA digestive biscuit", required = listOf("high_fibre"), excluded = listOf("maida")),
            SourceIdentityCase("one BrandA snack, almost sugar-free wala",
                "BrandA almost sugar-free snack", required = listOf("sugar_free")),
            SourceIdentityCase("one BrandA oil, litre wala", "BrandA oil litre"),
            SourceIdentityCase("one BrandA rice, kilo wali", "BrandA rice kilo"),
            SourceIdentityCase("one BrandA snack, gram wale", "BrandA snack gram"),
            SourceIdentityCase("one BrandA drink, ML wala", "BrandA drink ML"),
            SourceIdentityCase("one BrandA oil, half-litre wala", "BrandA oil half litre"),
            SourceIdentityCase("one BrandA rice, bada wala", "BrandA rice bada"),
            SourceIdentityCase("one BrandA snack, small wali", "BrandA snack small"),
        )
        rejected.forEach { case -> assertTrue(case.source, prepareSourceIdentity(case).items.isEmpty()) }

        listOf(" extra", "\n", "\u00a0").forEach { tail ->
            val case = SourceIdentityCase(primary, "BrandA digestive biscuit",
                required = listOf("high_fibre"), excluded = listOf("maida"), instruction = primary + tail)
            assertTrue(tail, prepareSourceIdentity(case).items.isEmpty())
        }
        val duplicate = SourceIdentityCase(primary, "BrandA digestive biscuit",
            required = listOf("high_fibre"), excluded = listOf("maida"), instruction = "$primary; $primary")
        assertTrue(prepareSourceIdentity(duplicate).items.isEmpty())
    }

    @Test fun terminalParticleCanPrecedeOnlyExactCommaSeparatedProtectedSuffixes() {
        val cases = listOf(
            SourceIdentityCase(
                source = "one BrandA snack, vanilla yogurt wala, no added sugar",
                name = "BrandA vanilla yogurt snack",
                required = listOf("no_added_sugar"),
            ),
            SourceIdentityCase(
                source = "three BrandA biscuit, high fibre wale, no palm oil",
                name = "BrandA high fibre biscuit",
                quantity = 3,
                quantityUnit = "count",
                quantityText = "three",
                excluded = listOf("palm_oil"),
            ),
            SourceIdentityCase(
                source = "one BrandA snack, strawberry wala, low fat, organic, no added sugar",
                name = "BrandA strawberry snack",
                required = listOf("low_fat", "organic", "no_added_sugar"),
            ),
            SourceIdentityCase(
                source = "one BrandA cereal, honey wala, low sugar",
                name = "BrandA honey cereal",
                required = listOf("low_sugar"),
            ),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals("${case.source} -> one item", 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals("${case.source} -> unchanged source", case.source, item.rawText)
            assertEquals("${case.source} -> exact quantity", Quantity.Count(case.quantity!!), item.quantity)
            assertFalse("${case.source} -> count is not inferred as a retail pack", item.retailPackCount)
            assertTrue("${case.source} -> identity remains in query", item.query.contains(case.name))
            assertEquals("${case.source} -> strict query", item.query, item.strictMatchPhrase)
            if (case.required.isNotEmpty()) {
                case.required.forEach { property ->
                    val phrase = property.replace('_', ' ')
                    assertTrue("${case.source} -> $property retained", item.query.contains(phrase, ignoreCase = true))
                }
            }
            if ("palm_oil" in case.excluded) assertEquals(listOf("palm oil"), item.avoidPhrases)
            assertTrue("${case.source} -> complete coverage",
                prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val yogurt = cases.first()
        fun changedSource(case: SourceIdentityCase, source: String) =
            case.copy(source = source, instruction = source)
        val invalid = listOf(
            // This near-synonym is deliberately not normalized into the supported exclusion.
            changedSource(cases[1], "three BrandA biscuit, high fibre wale, without palm oil"),
            yogurt.copy(name = "BrandA snack"),
            yogurt.copy(required = emptyList()),
            yogurt.copy(required = listOf("sugar_free")),
            cases.last().copy(required = listOf("sugar_free")),
            yogurt.copy(quantity = 2),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt Wala, no added sugar"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala no added sugar"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, if available, no added sugar"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, almost sugar free"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, maybe no added sugar"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, imported, no added sugar"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, no added sugar, chips"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, no added sugar and two more"),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, no added sugar, 250 g"),
            yogurt.copy(instruction = "${yogurt.source} wali version"),
            yogurt.copy(
                source = "one BrandA snack, vanilla yogurt",
                instruction = "one BrandA snack, vanilla yogurt wali, no added sugar",
            ),
            changedSource(yogurt, "one BrandA snack, vanilla yogurt wala, vanilla yogurt, no added sugar"),
            yogurt.copy(instruction = "${yogurt.source}; ${yogurt.source}"),
            yogurt.copy(quantity = null, quantityUnit = "unspecified", quantityText = null),
        )
        invalid.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("${case.source} -> must not be accepted", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun closedDietaryAbsenceLabelsCanProveTerminalRetailPackWithoutChangingMeaning() {
        val cases = listOf(
            SourceIdentityCase("ek no-added-sugar peanut chikki pack", "peanut chikki",
                quantityUnit = "pack", quantityText = "ek", packaging = "pack",
                required = listOf("no_added_sugar")),
            SourceIdentityCase("one no added sugar peanut chikki pack", "peanut chikki",
                quantityUnit = "pack", quantityText = "one", packaging = "pack",
                required = listOf("no_added_sugar")),
            SourceIdentityCase("two no salt roasted peanuts packs", "roasted peanuts", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "pack",
                excluded = listOf("salt")),
            SourceIdentityCase("one unsalted roasted peanuts pack", "roasted peanuts",
                quantityUnit = "pack", quantityText = "one", packaging = "pack",
                required = listOf("unsalted")),
            SourceIdentityCase("one peanut chikki no palm oil pack", "peanut chikki",
                quantityUnit = "pack", quantityText = "one", packaging = "pack",
                excluded = listOf("palm_oil")),
            SourceIdentityCase("one BrandA no-added-sugar peanut chikki pack", "BrandA no-added-sugar peanut chikki",
                quantityUnit = "pack", quantityText = "one", packaging = "pack",
                required = listOf("no_added_sugar")),
            SourceIdentityCase("one BrandA Valley no-added-sugar peanut chikki pack",
                "BrandA Valley no-added-sugar peanut chikki", quantityUnit = "pack",
                quantityText = "one", packaging = "pack", required = listOf("no_added_sugar")),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, Quantity.Count(case.quantity!!), item.quantity)
            assertTrue(case.source, item.retailPackCount)
            assertEquals(case.source, item.query, item.strictMatchPhrase)
            if (case.required.isNotEmpty()) {
                case.required.forEach { property ->
                    val phrase = property.replace('_', ' ')
                    assertTrue("${case.source} retains $property", item.query.contains(phrase, ignoreCase = true))
                }
            }
            if ("palm_oil" in case.excluded) assertEquals(listOf("palm oil"), item.avoidPhrases)
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun closedDietaryAbsenceLabelCannotBeDroppedFlippedOrManufactured() {
        val weaker = SourceIdentityCase("two no salt roasted peanuts packs", "roasted peanuts", quantity = 2,
            quantityUnit = "pack", quantityText = "two", packaging = "pack", required = listOf("unsalted"))
        assertReviewBlocked(weaker, prepareSourceIdentity(weaker))
        val base = SourceIdentityCase("one no-added-sugar peanut chikki pack", "peanut chikki",
            quantityUnit = "pack", quantityText = "one", packaging = "pack",
            required = listOf("no_added_sugar"))
        val rejected = listOf(
            base.copy(required = emptyList()),
            base.copy(required = listOf("sugar_free")),
            base.copy(name = "sugar-free peanut chikki"),
            base.copy(source = "one not no-added-sugar peanut chikki pack",
                instruction = "one not no-added-sugar peanut chikki pack"),
            base.copy(source = "one no-added-sugar or peanut chikki pack",
                instruction = "one no-added-sugar or peanut chikki pack"),
            base.copy(source = "one no-added-sugar if peanut chikki pack",
                instruction = "one no-added-sugar if peanut chikki pack"),
            base.copy(source = "one without no-added-sugar peanut chikki pack",
                instruction = "one without no-added-sugar peanut chikki pack"),
            base.copy(source = "one almost no-added-sugar peanut chikki pack",
                instruction = "one almost no-added-sugar peanut chikki pack"),
            base.copy(source = "one maybe no-added-sugar peanut chikki pack",
                instruction = "one maybe no-added-sugar peanut chikki pack"),
            base.copy(source = "one no-added-sugar-free peanut chikki pack",
                instruction = "one no-added-sugar-free peanut chikki pack"),
            base.copy(source = "one no-added-sugar versions peanut chikki pack",
                instruction = "one no-added-sugar versions peanut chikki pack"),
            base.copy(source = "one no 500 g added sugar peanut chikki pack", packValue = "500",
                packUnit = "g", packText = "500", instruction = "one no 500 g added sugar peanut chikki pack"),
            base.copy(source = "one who wants no-added-sugar chikki pack",
                name = "who wants no-added-sugar chikki", instruction = "one who wants no-added-sugar chikki pack"),
            base.copy(source = "one prefers no-added-sugar chikki pack",
                name = "prefers no-added-sugar chikki", instruction = "one prefers no-added-sugar chikki pack"),
            base.copy(source = "one no-added-sugar chikki pack per child",
                name = "no-added-sugar chikki per child", instruction = "one no-added-sugar chikki pack per child"),
            base.copy(source = "per one no-added-sugar chikki pack",
                name = "per no-added-sugar chikki", instruction = "per one no-added-sugar chikki pack"),
            base.copy(source = "someone prefers one no-added-sugar chikki pack",
                name = "someone prefers no-added-sugar chikki",
                instruction = "someone prefers one no-added-sugar chikki pack"),
        )
        rejected.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("${case.source} -> must not be accepted", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun commaSeparatedBasketRetainsDietQualifiedMiddleRetailPackAfterReviewPreparation() {
        val cases = listOf(
            SourceIdentityCase("one Gala apple pack", "Gala apple", quantityUnit = "pack",
                quantityText = "one", packaging = "pack"),
            SourceIdentityCase("one no-added-sugar peanut chikki pack", "peanut chikki",
                quantityUnit = "pack", quantityText = "one", packaging = "pack",
                required = listOf("no_added_sugar")),
            SourceIdentityCase("two Amul dark chocolate packs", "Amul dark chocolate", quantity = 2,
                quantityUnit = "pack", quantityText = "two", packaging = "pack"),
        )
        val prepared = prepareSourceIdentityBasket(cases)
        assertEquals(3, prepared.items.size)
        cases.forEachIndexed { index, case ->
            val item = prepared.items[index]
            assertEquals("index=$index", case.source, item.rawText)
            assertEquals("index=$index", Quantity.Count(case.quantity!!), item.quantity)
            assertTrue("index=$index", item.retailPackCount)
        }
        assertTrue(prepared.items[1].query.contains("no added sugar", ignoreCase = true))
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun pairedParticlePropertySuffixUsesTheClosedPropertyCatalogueWithoutInventingEvidence() {
        val properties = listOf(
            "sugar free", "no added sugar", "low fat", "tetra pack", "ready to eat",
            "ready to drink", "ready to cook", "organic", "certified", "unsalted", "plain",
            "high fibre", "high fiber", "high protein", "low calorie", "low sugar",
            "gluten free", "lactose free", "vegan", "vegetarian", "unsweetened",
            "un sweetened", "non sweetened", "sweetened",
        )
        properties.forEach { property ->
            val source = "one BrandA snack, vanilla wala, $property"
            val name = "BrandA vanilla snack $property"
            val prepared = prepareSourceIdentity(SourceIdentityCase(source, name))
            assertEquals("$property -> one item", 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals("$property -> source unchanged", source, item.rawText)
            assertEquals("$property -> exact count", Quantity.Count(1), item.quantity)
            assertEquals("$property -> name/property query", ProductLexicon.canonicalizeProductText(name),
                ProductLexicon.canonicalizeProductText(item.query))
            assertTrue("$property -> canonical query phrase retained",
                ProductLexicon.canonicalizeProductText(item.query)
                    .contains(ProductLexicon.canonicalizeProductText(property)))
            assertTrue("$property -> fully covered", prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        listOf(
            "sugar free", "no added sugar", "low fat", "organic", "low sugar",
        ).forEach { assertTrue("closed property: $it", groceryIntentIsClosedNominalProperty(it)) }
        listOf(
            "almost sugar free", "no added sugar maybe", "no sugar-free substitutions",
            "without palm oil",
        ).forEach { assertFalse("not a closed nominal property: $it", groceryIntentIsClosedNominalProperty(it)) }
    }

    @Test fun terminalParticleDoesNotBorrowFromUnstatedAmountOrSupportingText() {
        val source = "BrandA digestive biscuit, zero maida, high fibre wala"
        val unstated = SourceIdentityCase(source, "BrandA digestive biscuit",
            quantity = null, quantityUnit = "unspecified", quantityText = null,
            required = listOf("high_fibre"), excluded = listOf("maida"))
        assertTrue(prepareSourceIdentity(unstated).items.isEmpty())

        val primary = "one BrandA digestive biscuit, zero maida"
        val support = "high fibre wala"
        val supported = SourceIdentityCase(primary, "BrandA digestive biscuit",
            required = listOf("high_fibre"), excluded = listOf("maida"),
            supporting = listOf(SourceIdentitySupport("modifier", support)),
            instruction = "$primary; $support")
        assertTrue(prepareSourceIdentity(supported).items.isEmpty())

        val sibling = "one BrandB snack, high fibre wala"
        val borrowed = SourceIdentityCase(primary, "BrandA digestive biscuit",
            required = listOf("high_fibre"), excluded = listOf("maida"),
            instruction = "$primary; $sibling")
        assertTrue(prepareSourceIdentity(borrowed).items.isEmpty())
    }

    @Test fun terminalLowercaseHindiParticleKeepsGeneralDescriptorAndExactRetailAmounts() {
        val cases = listOf(
            SourceIdentityCase("4 packet ExampleBrand noodles masala wale", "ExampleBrand noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt ke 1 kg wale", "ExampleBrand salt",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("3 packet ExampleBrand chips peri peri wali!", "ExampleBrand chips peri peri",
                quantity = 3, quantityUnit = "pack", quantityText = "3", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand atta ke 1.5 kg wali.", "ExampleBrand atta",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1.5",
                packUnit = "kg", packText = "1.5", packaging = "packet"),
        )
        val expectedQueries = listOf(
            "ExampleBrand noodles masala packet",
            "ExampleBrand salt packet 1000 g",
            "ExampleBrand chips peri peri packet",
            "ExampleBrand atta packet 1500 g",
        )
        cases.zip(expectedQueries).forEach { (case, expectedQuery) ->
            val prepared = prepareSourceIdentity(case)
            assertEquals("${case.source} -> ${case.name}", 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals("${case.source} -> query", expectedQuery, item.query)
            assertEquals("${case.source} -> quantity", Quantity.Count(case.quantity!!), item.quantity)
            assertTrue("${case.source} -> retail pack", item.retailPackCount)
            assertTrue("${case.source} -> brand", item.query.contains("ExampleBrand"))
            assertTrue("${case.source} -> review coverage", prepared.excludedText.isEmpty() && prepared.unresolvedText.isEmpty())
        }
    }

    @Test fun terminalParticleRejectsDroppedIdentityFalseAmountsAndUnsafeBoundaries() {
        val cases = listOf(
            SourceIdentityCase("4 packet ExampleBrand noodles masala wale", "ExampleBrand noodles",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles masala wale", "ExampleBrand noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4"),
            SourceIdentityCase("2 packet ExampleBrand salt ke 1 kg wale", "ExampleBrand salt",
                quantity = 3, quantityUnit = "pack", quantityText = "2", packValue = "1",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt ke 1 kg wale", "ExampleBrand salt",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "2",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt ke 1 kg wale", "ExampleBrand salt",
                quantity = 2, quantityUnit = "count", quantityText = "2", packValue = "1",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt ke 1 kg wale", "ExampleBrand salt",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles masala Wala", "ExampleBrand noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles masala wale if available",
                "ExampleBrand noodles masala", quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles masala wale, not spicy",
                "ExampleBrand noodles masala", quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("earlier 4 packet ExampleBrand noodles masala wale",
                "ExampleBrand noodles masala", quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("packet ExampleBrand noodles masala wale", "ExampleBrand noodles masala",
                quantity = null, quantityUnit = "unspecified", quantityText = null, packaging = "packet"),
        )
        cases.forEachIndexed { index, case ->
            val prepared = prepareSourceIdentity(case)
            assertReviewBlocked(case, prepared, requireNoItems = index == 0 || index == 6)
        }

        val upperWalaBrand = SourceIdentityCase("4 packet ExampleBrand noodles masala Wala",
            "ExampleBrand noodles masala Wala", quantity = 4, quantityUnit = "pack",
            quantityText = "4", packaging = "packet")
        val preserved = prepareSourceIdentity(upperWalaBrand)
        assertEquals("${upperWalaBrand.source} -> ${upperWalaBrand.name}", 1, preserved.items.size)
        assertEquals("${upperWalaBrand.source} -> query", "ExampleBrand noodles masala Wala packet",
            preserved.items.single().query)
        assertTrue(upperWalaBrand.source, preserved.unresolvedText.isEmpty() && preserved.excludedText.isEmpty())

        val complete = "4 packet ExampleBrand noodles masala wale"
        val cropped = SourceIdentityCase("4 packet ExampleBrand noodles masala", "ExampleBrand noodles masala",
            quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet",
            instruction = complete)
        val cropResult = prepareSourceIdentity(cropped)
        assertReviewBlocked(cropped, cropResult)
        assertEquals("${cropped.source} -> retained item", 1, cropResult.items.size)
        assertEquals("${cropped.source} -> uncovered suffix", listOf("wale"), cropResult.unresolvedText)

        val nonterminal = SourceIdentityCase(complete, "ExampleBrand noodles masala",
            quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet",
            instruction = "$complete extra")
        val nonterminalResult = prepareSourceIdentity(nonterminal)
        assertReviewBlocked(nonterminal, nonterminalResult, requireNoItems = true)

        val duplicate = SourceIdentityCase(complete, "ExampleBrand noodles masala",
            quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet",
            instruction = "$complete; $complete")
        val duplicateResult = prepareSourceIdentity(duplicate)
        assertReviewBlocked(duplicate, duplicateResult)

        val primary = "4 packet ExampleBrand noodles"
        val supported = SourceIdentityCase(primary, "ExampleBrand noodles masala",
            quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet",
            supporting = listOf(SourceIdentitySupport("modifier", "masala wale")),
            instruction = "$primary; masala wale")
        val supportResult = prepareSourceIdentity(supported)
        assertReviewBlocked(supported, supportResult, requireNoItems = true)

        val grouped = SourceIdentityCase(complete, "ExampleBrand noodles masala",
            quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet")
        val sibling = SourceIdentityCase("one packet ExampleBrand tea", "ExampleBrand tea",
            quantity = 1, quantityUnit = "pack", quantityText = "one", packaging = "packet")
        val groupResult = prepareGroupedSourceIdentity(grouped, sibling)
        assertReviewBlocked(grouped, groupResult)
    }

    @Test fun terminalParticleWholePrimarySelectorsCoordinatorsAndRangesStayBlocked() {
        val cases = listOf(
            SourceIdentityCase("4 packet ExampleBrand papa noodles masala wale", "ExampleBrand papa noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand mere noodles masala wale", "ExampleBrand mere noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand sasta noodles masala wale", "ExampleBrand sasta noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand best noodles masala wale", "ExampleBrand best noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand roz noodles masala wale", "ExampleBrand roz noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand jaisa noodles masala wale", "ExampleBrand jaisa noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand type noodles masala wale", "ExampleBrand type noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand koi bhi noodles masala wale", "ExampleBrand koi bhi noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand naya noodles masala wale", "ExampleBrand naya noodles masala",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            // "woh" is beyond the last four lexical words before the terminal particle.
            SourceIdentityCase("4 packet ExampleBrand woh whole grain roasted salted spicy masala noodles wale",
                "ExampleBrand woh whole grain roasted salted spicy masala noodles",
                quantity = 4, quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles aur ExampleBrand salt wale",
                "ExampleBrand noodles aur ExampleBrand salt", quantity = 4,
                quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles and ExampleBrand salt wale",
                "ExampleBrand noodles and ExampleBrand salt", quantity = 4,
                quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles plus ExampleBrand salt wale",
                "ExampleBrand noodles plus ExampleBrand salt", quantity = 4,
                quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("4 packet ExampleBrand noodles & ExampleBrand salt wale",
                "ExampleBrand noodles & ExampleBrand salt", quantity = 4,
                quantityUnit = "pack", quantityText = "4", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt 1 kg se kam wale", "ExampleBrand salt 1 kg se kam",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt 1 kg tak wale", "ExampleBrand salt 1 kg tak",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt 1 kg ya 500 g wale", "ExampleBrand salt 1 kg ya 500 g",
                quantity = 2, quantityUnit = "pack", quantityText = "2", packValue = "1",
                packUnit = "kg", packText = "1", packaging = "packet"),
            SourceIdentityCase("2 packet ExampleBrand salt 1 kg ke aas paas wale",
                "ExampleBrand salt 1 kg ke aas paas", quantity = 2, quantityUnit = "pack",
                quantityText = "2", packValue = "1", packUnit = "kg", packText = "1", packaging = "packet"),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun terminalParticleHistoryLowerBoundAndPriceSelectorsOutsidePrimaryAreBlocked() {
        val cases = listOf(
            SourceIdentityCase("2 masala wala", "masala", quantity = 2, quantityUnit = "count",
                quantityText = "2", instruction = "pichli baar 2 masala wala"),
            SourceIdentityCase("2 masala wala", "masala", quantity = 2, quantityUnit = "count",
                quantityText = "2", instruction = "Amul se sasta 2 masala wala"),
            SourceIdentityCase("2 masala wala", "masala", quantity = 2, quantityUnit = "count",
                quantityText = "2", instruction = "at least 2 masala wala"),
            SourceIdentityCase("2 bees rupay wala", "bees rupay", quantity = 2,
                quantityUnit = "count", quantityText = "2"),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun terminalParticleExplicitRequestPrefixRemainsFullyCovered() {
        val case = SourceIdentityCase("2 masala wala", "masala", quantity = 2,
            quantityUnit = "count", quantityText = "2", instruction = "please get 2 masala wala")
        val prepared = prepareSourceIdentity(case)

        assertEquals("${case.instruction} -> single item", 1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals("${case.instruction} -> query", "masala", item.query)
        assertEquals("${case.instruction} -> quantity", Quantity.Count(2), item.quantity)
        assertFalse("${case.instruction} -> count is not retail pack", item.retailPackCount)
        assertTrue("${case.instruction} -> fully covered", prepared.unresolvedText.isEmpty() &&
            prepared.excludedText.isEmpty())
    }

    @Test fun terminalRequestScaffoldPreservesExactIdentityAmountAndDiet() {
        val cases = listOf(
            SourceIdentityCase("long grain rice bhi", "long grain rice", quantity = null,
                quantityUnit = "unspecified", quantityText = null),
            SourceIdentityCase("two eggs bhi chahiye", "eggs", quantity = 2,
                quantityUnit = "count", quantityText = "two"),
            SourceIdentityCase("one egg BHI CHAHIE ji", "egg", quantity = 1,
                quantityUnit = "count", quantityText = "one"),
            SourceIdentityCase("Three eggs chahiye PLEASE", "eggs", quantity = 3,
                quantityUnit = "count", quantityText = "Three"),
            SourceIdentityCase("one no added sugar tea bhi chaiye please", "tea",
                quantity = 1, quantityUnit = "count", quantityText = "one",
                required = listOf("no_added_sugar")),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, case.name, item.query.substringBefore(" no added sugar"))
            assertEquals(case.source, case.quantity?.let { Quantity.Count(it) } ?: Quantity.Default, item.quantity)
            assertFalse(case.source, item.retailPackCount)
            if ("no_added_sugar" in case.required) {
                assertTrue(case.source, item.query.contains("no added sugar", ignoreCase = true))
            }
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun terminalRequestScaffoldRejectsClauseCroppingAndIdentityDrift() {
        val cases = listOf(
            SourceIdentityCase("not one long grain rice bhi chahiye", "long grain rice", quantity = 1,
                quantityText = "one"),
            SourceIdentityCase("if available, one long grain rice bhi chahiye", "long grain rice",
                quantity = 1, quantityText = "one"),
            SourceIdentityCase("one long grain rice bhi chahiye if available", "long grain rice",
                quantity = 1, quantityText = "one"),
            SourceIdentityCase("one long grain rice bhi chahiye", "long grain rice",
                quantity = 1, quantityText = "one", instruction = "one long grain rice bhi chahiye nahi"),
            SourceIdentityCase("one long grain rice bhi chahiye 2", "long grain rice",
                quantity = 1, quantityText = "one"),
            SourceIdentityCase("one chahiye tea bhi", "tea", quantity = 1, quantityText = "one"),
            SourceIdentityCase("one long grain rice bhi chahiye", "rice", quantity = 1, quantityText = "one"),
            SourceIdentityCase("two eggs bhi chahiye", "eggs", quantity = 3, quantityText = "two"),
            SourceIdentityCase("one rice bhi chahiye", "wheat flour", quantity = 1, quantityText = "one"),
            SourceIdentityCase("one no added sugar tea bhi chahiye", "tea", quantity = 1,
                quantityText = "one", required = listOf("sugar_free")),
            SourceIdentityCase("one long grain rice bhi chahiye", "long grain rice wheat",
                quantity = 1, quantityText = "one"),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("source=${case.source} instruction=${case.instruction}", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun closedPositiveCommandPrefixesPreserveInternalOfJarIdentityAndExactCount() {
        val source = "one Brand of Valley curd jar"
        val prefixes = listOf("Add ", "Please add ", "Hello, could you please add ", "Beta, add ", "Also ")
        prefixes.forEach { prefix ->
            val case = SourceIdentityCase(
                source = source,
                name = "Brand of Valley curd",
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "one",
                packaging = "jar",
                instruction = prefix + source,
            )
            val prepared = prepareSourceIdentity(case)
            assertEquals(prefix, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(prefix, source, item.rawText)
            assertEquals(prefix, "Brand of Valley curd jar", item.query)
            assertEquals(prefix, Quantity.Count(1), item.quantity)
            assertTrue(prefix, item.retailPackCount)
            assertEquals(prefix, item.query, item.strictMatchPhrase)
            assertTrue(prefix, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun closedPositiveCommandPrefixesPreserveLongContainerIdentityWithOptionalForm() {
        val source = "a Green Meadow pure unscented bath soap bar"
        val names = listOf(
            "Green Meadow pure unscented bath soap",
            "Green Meadow pure unscented bath soap bar",
        )
        val prefixes = listOf("Add ", "Please add ", "Hello, could you please add ", "Beta, add ", "Also ")
        for (name in names) for (prefix in prefixes) {
            val case = SourceIdentityCase(
                source = source,
                name = name,
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "a",
                packaging = "bar",
                instruction = prefix + source,
            )
            val prepared = prepareSourceIdentity(case)
            assertEquals("name=$name prefix=$prefix", 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals("name=$name prefix=$prefix", source, item.rawText)
            assertEquals("name=$name prefix=$prefix", "Green Meadow pure unscented bath soap bar", item.query)
            assertEquals("name=$name prefix=$prefix", Quantity.Count(1), item.quantity)
            assertTrue("name=$name prefix=$prefix", item.retailPackCount)
            assertEquals("name=$name prefix=$prefix", item.query, item.strictMatchPhrase)
            assertTrue("name=$name prefix=$prefix", prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun positiveCommandPrefixCannotAuthorizeNegatedConditionalExtraQuantityOrServingIdentity() {
        val jar = "one Brand of Valley curd jar"
        val rejected = listOf(
            SourceIdentityCase(jar, "Brand of Valley curd", quantity = 1, quantityUnit = "pack",
                quantityText = "one", packaging = "jar", instruction = "not $jar"),
            SourceIdentityCase(jar, "Brand of Valley curd", quantity = 1, quantityUnit = "pack",
                quantityText = "one", packaging = "jar", instruction = "if available, $jar"),
            SourceIdentityCase(jar, "Brand of Valley curd", quantity = 1, quantityUnit = "pack",
                quantityText = "one", packaging = "jar", instruction = "two $jar"),
            SourceIdentityCase(jar, "Brand of Valley curd", quantity = 2, quantityUnit = "pack",
                quantityText = "one", packaging = "jar", instruction = "Add $jar"),
            SourceIdentityCase(jar, "Other Brand curd", quantity = 1, quantityUnit = "pack",
                quantityText = "one", packaging = "jar", instruction = "Hello, could you please add $jar"),
            SourceIdentityCase("a serving of Green Meadow pure unscented bath soap bar",
                "Green Meadow pure unscented bath soap", quantity = 1,
                quantityUnit = "pack", quantityText = "a", packaging = "bar",
                instruction = "Also a serving of Green Meadow pure unscented bath soap bar"),
        )
        rejected.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("instruction=${case.instruction}", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }

        val adversarialPrefixes = listOf(
            "Please add half of ", "Please add another ", "Please don't add ",
            "Please add if available ", "Please remove ", "Please replace ",
            "Please send ", "Please get home ", "and also add ", "Please also add ",
            "Please\u00a0add ", "plea\u017Fe add ", "Please\u200B add ",
            "If you can, please add ",
        )
        adversarialPrefixes.forEach { prefix ->
            val case = SourceIdentityCase(
                source = jar,
                name = "Brand of Valley curd",
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "one",
                packaging = "jar",
                instruction = prefix + jar,
            )
            val prepared = prepareSourceIdentity(case)
            assertTrue("prefix=$prefix", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }

        listOf("Don't, add ", "If available, add ").forEach { prefix ->
            val case = SourceIdentityCase(
                source = jar,
                name = "Brand of Valley curd",
                quantity = 1,
                quantityUnit = "pack",
                quantityText = "one",
                packaging = "jar",
                instruction = prefix + jar,
            )
            val prepared = prepareSourceIdentity(case)
            assertTrue("prefix=$prefix", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun groupPackagingJarIsDeduplicatedWhenOneMemberNameAlreadyContainsJar() {
        val first = SourceIdentityCase(
            source = "one honey jar",
            name = "honey jar",
            quantity = 1,
            quantityUnit = "pack",
            quantityText = "one",
            packaging = "jar",
        )
        val second = SourceIdentityCase(
            source = "one jam jar",
            name = "jam",
            quantity = 1,
            quantityUnit = "pack",
            quantityText = "one",
            packaging = "jar",
        )
        val prepared = prepareGroupedSourceIdentity(
            first, second, header = "jars: ", groupPackaging = "jar",
        )
        assertEquals(2, prepared.items.size)
        val expected = listOf(
            first.source to "honey jar",
            second.source to "jam jar",
        )
        expected.forEachIndexed { index, (source, query) ->
            val item = prepared.items[index]
            assertEquals("index=$index", source, item.rawText)
            assertEquals("index=$index", query, item.query)
            assertEquals("index=$index", item.query, item.strictMatchPhrase)
            assertEquals("index=$index", Quantity.Count(1), item.quantity)
            assertTrue("index=$index", item.retailPackCount)
            assertEquals("index=$index", 0.7f, item.parserConfidence, 0f)
            assertEquals("index=$index", 1, Regex("\\bjar\\b", RegexOption.IGNORE_CASE)
                .findAll(item.query).count())
        }
        assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
    }

    @Test fun v2PackagingFormDedupRequiresExactWholeFormInOriginalName() {
        val cases = listOf(
            SourceIdentityCase("one barley bar", "barley", quantity = 1,
                quantityUnit = "pack", quantityText = "one", packaging = "bar") to
                "barley bar",
            SourceIdentityCase("one barley bar", "barley bar", quantity = 1,
                quantityUnit = "pack", quantityText = "one", packaging = "bar") to
                "barley bar",
            SourceIdentityCase("one protein bar, no added sugar", "protein bar", quantity = 1,
                quantityUnit = "pack", quantityText = "one", packaging = "bar",
                required = listOf("no_added_sugar")) to "protein bar no added sugar",
            SourceIdentityCase("one soap BAR", "soap BAR", quantity = 1,
                quantityUnit = "pack", quantityText = "one", packaging = "bar") to
                "soap BAR",
        )
        cases.forEach { (case, expectedQuery) ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            assertEquals(case.source, expectedQuery, item.query)
            assertEquals(case.source, Quantity.Count(1), item.quantity)
            assertTrue(case.source, item.retailPackCount)
            assertEquals(case.source, 0.7f, item.parserConfidence, 0f)
            assertEquals(case.source, item.query, item.strictMatchPhrase)
            if ("no_added_sugar" in case.required) {
                assertEquals(case.source, 1, Regex("no added sugar", RegexOption.IGNORE_CASE)
                    .findAll(item.query).count())
            }
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun queryOnlyFormDedupPreservesOriginalConfidenceAndOtherParsedFields() {
        val item = GroceryIntentItem(
            name = "soap bar",
            quantity = BigDecimal.ONE,
            quantityUnit = "pack",
            packValue = null,
            packUnit = "unspecified",
            required = listOf("bar"),
            excluded = listOf("maida"),
            sourceText = "one soap bar",
            needsClarification = false,
        )
        val legacy = item.toParsedItem()
        val deduplicated = item.toParsedItem(setOf("bar"))

        assertEquals("soap bar bar", legacy.query)
        assertEquals("soap bar", deduplicated.query)
        assertEquals(0.7f, legacy.parserConfidence, 0f)
        assertEquals(0.7f, deduplicated.parserConfidence, 0f)
        assertEquals(legacy.rawText, deduplicated.rawText)
        assertEquals(legacy.quantity, deduplicated.quantity)
        assertEquals(legacy.quantitySignal, deduplicated.quantitySignal)
        assertEquals(legacy.avoidPhrases, deduplicated.avoidPhrases)
        assertEquals(legacy.retailPackCount, deduplicated.retailPackCount)
        assertEquals(legacy.logicalPieceCount, deduplicated.logicalPieceCount)
        assertEquals(legacy.requiresIntentQuantityProof, deduplicated.requiresIntentQuantityProof)
        assertEquals(legacy.strictMatchPhrase, legacy.query)
        assertEquals(deduplicated.strictMatchPhrase, deduplicated.query)
        assertEquals(legacy.parserConfidence, deduplicated.parserConfidence, 0f)
    }

    @Test fun explicitSourceProvenContainerCountsPassWithoutAllowingAmountOrIdentityDrift() {
        val explicitSized = SourceIdentityCase(
            source = "a jar of honey 500 g each",
            name = "honey",
            quantity = 1,
            quantityUnit = "pack",
            quantityText = "a",
            packValue = "500",
            packUnit = "g",
            packText = "500",
            packaging = "jar",
        )
        val explicitCount = SourceIdentityCase(
            source = "two jars of honey",
            name = "honey",
            quantity = 2,
            quantityUnit = "pack",
            quantityText = "two",
            packaging = "jar",
        )

        listOf(explicitSized, explicitCount).forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals("${case.source} -> one item", 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals("${case.source} -> original source", case.source, item.rawText)
            assertTrue("${case.source} -> literal product identity", item.query.contains(case.name))
            assertEquals("${case.source} -> exact count", Quantity.Count(case.quantity!!), item.quantity)
            assertTrue("${case.source} -> retail pack count", item.retailPackCount)
            assertTrue("${case.source} -> fully covered",
                prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val rejected = listOf(
            // The source says one; the forged amount says two.
            explicitSized.copy(quantity = 2),
            // A source count cannot be omitted and silently become a default quantity.
            explicitCount.copy(quantity = null, quantityUnit = "unspecified", quantityText = null),
            // A source-proved brand or property cannot be dropped from the proposal.
            explicitSized.copy(source = "a jar of BrandA honey 500 g each", instruction =
                "a jar of BrandA honey 500 g each"),
            explicitSized.copy(source = "a jar of unsweetened honey 500 g each", instruction =
                "a jar of unsweetened honey 500 g each"),
            // An extra amount in the same primary is not owned by the proposed count or size.
            explicitSized.copy(source = "a jar of honey 500 g each and 250 g honey", instruction =
                "a jar of honey 500 g each and 250 g honey"),
        )
        rejected.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("${case.source} -> must not become a usable item", prepared.items.isEmpty())
        }
    }
    @Test fun localAvailabilityTailKeepsCountedProductAndExactIdentity() {
        val cases = listOf(
            SourceIdentityCase(
                source = "one hand cream if the unscented kind is available",
                name = "unscented hand cream",
                quantity = 1,
                quantityUnit = "count",
                quantityText = "one",
            ),
            SourceIdentityCase("two soaps if available", "soaps", quantity = 2,
                quantityUnit = "count", quantityText = "two"),
            SourceIdentityCase("500 g rice if available", "rice", quantity = 500,
                quantityUnit = "g", quantityText = "500"),
        )
        cases.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertEquals(case.source, 1, prepared.items.size)
            val item = prepared.items.single()
            assertEquals(case.source, case.source, item.rawText)
            val expected = if (case.quantityUnit == "g") Quantity.Weight(case.quantity!!)
                else Quantity.Count(case.quantity!!)
            assertEquals(case.source, expected, item.quantity)
            assertTrue(case.source, item.query.contains(case.name, ignoreCase = true))
            assertEquals(case.source, item.query, item.strictMatchPhrase)
            assertFalse(case.source, item.query.contains("available", ignoreCase = true))
            if (case.source.contains("unscented")) {
                assertTrue(case.source, item.query.contains("unscented", ignoreCase = true))
                assertTrue(case.source,
                    swiggyMatchesProductIdentity(item, "SoftCare unscented hand cream 50 ml"))
                assertFalse(case.source,
                    swiggyMatchesProductIdentity(item, "SoftCare scented hand cream 50 ml"))
                assertFalse(case.source,
                    swiggyMatchesProductIdentity(item, "SoftCare hand cream 50 ml"))
            }
            assertTrue(case.source, prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }

        val basket = prepareSourceIdentityBasket(cases.take(2))
        assertEquals(listOf("unscented hand cream", "soaps"), basket.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), basket.items.map { it.quantity })
        assertTrue(basket.items.all { it.rawText.contains("if") })
        assertTrue(basket.unresolvedText.isEmpty() && basket.excludedText.isEmpty())
    }

    @Test fun availabilityConditionsCannotFloatOrChangeProductMeaning() {
        val base = SourceIdentityCase(
            source = "one hand cream if the unscented kind is available",
            name = "unscented hand cream",
            quantity = 1,
            quantityUnit = "count",
            quantityText = "one",
        )
        val rejected = listOf(
            base.copy(name = "hand cream"),
            base.copy(source = "one hand cream if milk is available",
                instruction = "one hand cream if milk is available"),
            base.copy(source = "one hand cream if the unscented kind is available otherwise scented",
                instruction = "one hand cream if the unscented kind is available otherwise scented"),
            base.copy(source = "one hand cream if the unscented kind is available for each child",
                instruction = "one hand cream if the unscented kind is available for each child"),
            base.copy(source = "if unscented cream is available buy one hand cream",
                instruction = "if unscented cream is available buy one hand cream"),
            base.copy(source = "one hand cream if unscented or scented is available",
                instruction = "one hand cream if unscented or scented is available"),
            base.copy(source = "one hand cream unless the unscented kind is available",
                instruction = "one hand cream unless the unscented kind is available"),
            base.copy(source = "one protein bar, no added sugar if the sugar-free kind is available",
                name = "sugar-free protein bar", required = listOf("sugar_free"),
                instruction = "one protein bar, no added sugar if the sugar-free kind is available"),
        )
        rejected.forEach { case ->
            val prepared = prepareSourceIdentity(case)
            assertTrue("${case.source} -> no usable item", prepared.items.isEmpty())
            assertReviewBlocked(case, prepared)
        }
    }

    @Test fun availabilityCannotBeEnabledByAcknowledgingUnknownSurroundingConditions() {
        listOf(
            "when milk is available buy one hand cream if available",
            "skip one hand cream if available",
            "one hand cream if available, provided milk is available",
            "one hand cream if available, for each child",
            "one hand cream if available for each child",
        ).forEach { instruction ->
            val case = SourceIdentityCase("one hand cream if available", "hand cream", instruction = instruction,
                quantity = 1, quantityUnit = "count", quantityText = "one")
            val result = prepareSourceIdentity(case)
            assertTrue(instruction, result.items.isEmpty())
            assertReviewBlocked(case, result)
        }
    }

    @Test fun localAvailabilityMustCloseOverAllPrimariesIncludingBlockedOrLateFailedConditionRows() {
        val instruction = "when milk is available buy one hand cream if available"
        val milkSource = "when milk is available"
        val creamSource = "one hand cream if available"

        fun quoted(text: String): String {
            val start = instruction.indexOf(text)
            require(start >= 0)
            return """{"text":${sourceIdentityJsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""
        }
        fun readyRow(index: Int, source: String, name: String, quantityJson: String,
            quantityUnit: String, quantityText: String?, quantityOrigin: String, amountStatus: String): String {
            val quantityTextJson = quantityText?.let(::sourceIdentityJsonString) ?: "null"
            return """{"row_index":$index,"item":{"name":${sourceIdentityJsonString(name)},"quantity":$quantityJson,"quantity_unit":${sourceIdentityJsonString(quantityUnit)},"pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${sourceIdentityJsonString(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$quantityTextJson,"pack_text":null},"evidence":{"primary":${quoted(source)},"supporting":[]},"quantity_origin":${sourceIdentityJsonString(quantityOrigin)},"amount_role_status":${sourceIdentityJsonString(amountStatus)}}"""
        }
        fun reviewWithBlockedMilk(): GroceryIntentPreparation {
            val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${sourceIdentityJsonString(instruction)},"source_item_count":2,"ready_rows":[${readyRow(1, creamSource, "hand cream", "1", "count", "one", "stated", "matched")}],"blocked_rows":[{"row_index":0,"source":${quoted(milkSource)},"issue_codes":["model_needs_clarification"]}],"groups":[]},"model":"test","promptVersion":"source-identity-test","latencyMs":1,"usage":{}}"""
            return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
        }
        fun reviewWithLateMilkGroundingFailure(): GroceryIntentPreparation {
            val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${sourceIdentityJsonString(instruction)},"source_item_count":2,"ready_rows":[${readyRow(0, milkSource, "milk", "null", "unspecified", null, "not_stated", "not_stated")},${readyRow(1, creamSource, "hand cream", "1", "count", "one", "stated", "matched")}],"blocked_rows":[],"groups":[]},"model":"test","promptVersion":"source-identity-test","latencyMs":1,"usage":{}}"""
            return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
        }

        listOf(reviewWithBlockedMilk(), reviewWithLateMilkGroundingFailure()).forEach { result ->
            assertTrue(result.items.isEmpty())
            assertTrue(result.unresolvedText.joinToString(" ").contains("milk", ignoreCase = true))
            assertReviewBlocked(
                SourceIdentityCase(creamSource, "hand cream", instruction = instruction,
                    quantity = 1, quantityUnit = "count", quantityText = "one"),
                result,
                requireNoItems = true,
            )
        }

        val trailingCondition = SourceIdentityCase(
            source = creamSource,
            name = "hand cream",
            quantity = 1,
            quantityUnit = "count",
            quantityText = "one",
            instruction = "one hand cream if available, provided milk is available",
        )
        val trailingResult = prepareSourceIdentity(trailingCondition)
        assertTrue(trailingCondition.instruction, trailingResult.items.isEmpty())
        assertTrue(trailingCondition.instruction,
            trailingResult.unresolvedText.joinToString(" ").contains("milk", ignoreCase = true))
        assertReviewBlocked(trailingCondition, trailingResult, requireNoItems = true)
    }
}

private data class SourceIdentityCase(
    val source: String,
    val name: String,
    val quantity: Int? = 1,
    val quantityUnit: String = "count",
    val quantityText: String? = "one",
    val packValue: String? = null,
    val packUnit: String = "unspecified",
    val packText: String? = null,
    val packaging: String? = null,
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val supporting: List<SourceIdentitySupport> = emptyList(),
    val instruction: String = source,
    val needsClarification: Boolean = false,
)

private data class SourceIdentitySupport(val kind: String, val text: String)

private fun prepareSourceIdentity(
    case: SourceIdentityCase,
    inspectRow: ((GroceryIntentReviewV2ReadyRow) -> Unit)? = null,
): GroceryIntentPreparation {
    val instruction = case.instruction
    val quantity = case.quantity?.toString() ?: "null"
    val quantityOrigin = if (case.quantity == null) "not_stated" else "stated"
    val amountRoleStatus = if (case.quantity == null && case.packValue == null) "not_stated" else "matched"
    val start = instruction.indexOf(case.source)
    require(start >= 0)
    val supportJson = case.supporting.joinToString(",") { support ->
        val supportStart = instruction.indexOf(support.text)
        require(supportStart >= 0)
        """{"kind":${sourceIdentityJsonString(support.kind)},"quote":{"text":${sourceIdentityJsonString(support.text)},"start_utf16":$supportStart,"end_utf16":${supportStart + support.text.length}}}"""
    }
    val supportingTextJson = case.supporting.joinToString(",") { support ->
        """{"kind":${sourceIdentityJsonString(support.kind)},"text":${sourceIdentityJsonString(support.text)}}"""
    }
    val quantityText = case.quantityText?.let(::sourceIdentityJsonString) ?: "null"
    val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${sourceIdentityJsonString(instruction)},"source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":${sourceIdentityJsonString(case.name)},"quantity":$quantity,"quantity_unit":${sourceIdentityJsonString(case.quantityUnit)},"pack_value":${case.packValue ?: "null"},"pack_unit":${sourceIdentityJsonString(case.packUnit)},"packaging":${case.packaging?.let(::sourceIdentityJsonString) ?: "null"},"required":${sourceIdentityJsonArray(case.required)},"excluded":${sourceIdentityJsonArray(case.excluded)},"source_text":${sourceIdentityJsonString(case.source)},"supporting_text":[$supportingTextJson],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":${case.packText?.let(::sourceIdentityJsonString) ?: "null"}},"evidence":{"primary":{"text":${sourceIdentityJsonString(case.source)},"start_utf16":$start,"end_utf16":${start + case.source.length}},"supporting":[$supportJson]},"quantity_origin":"$quantityOrigin","amount_role_status":"$amountRoleStatus"}],"blocked_rows":[],"groups":[]},"model":"test","promptVersion":"source-identity-test","latencyMs":1,"usage":{}}"""

    val draft = GroceryIntentReviewV2Response.parse(body, instruction).draft
    inspectRow?.invoke(draft.readyRows.single())
    return draft.prepareForReview()
}

private fun prepareSourceIdentityBasket(
    cases: List<SourceIdentityCase>,
    separator: String = ", ",
    instructionText: String? = null,
    fulfillment: List<String> = emptyList(),
): GroceryIntentPreparation {
    require(cases.isNotEmpty())
    val instruction = instructionText ?: cases.joinToString(separator) { it.source }
    val rows = cases.mapIndexed { index, case ->
        val start = instruction.indexOf(case.source)
        require(start >= 0)
        val quantity = case.quantity?.toString() ?: "null"
        val quantityOrigin = if (case.quantity == null) "not_stated" else "stated"
        val amountRoleStatus = if (case.quantity == null && case.packValue == null) "not_stated" else "matched"
        val quantityText = case.quantityText?.let(::sourceIdentityJsonString) ?: "null"
        """{"row_index":$index,"item":{"name":${sourceIdentityJsonString(case.name)},"quantity":$quantity,"quantity_unit":${sourceIdentityJsonString(case.quantityUnit)},"pack_value":${case.packValue ?: "null"},"pack_unit":${sourceIdentityJsonString(case.packUnit)},"packaging":${case.packaging?.let(::sourceIdentityJsonString) ?: "null"},"required":${sourceIdentityJsonArray(case.required)},"excluded":${sourceIdentityJsonArray(case.excluded)},"source_text":${sourceIdentityJsonString(case.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":${case.packText?.let(::sourceIdentityJsonString) ?: "null"}},"evidence":{"primary":{"text":${sourceIdentityJsonString(case.source)},"start_utf16":$start,"end_utf16":${start + case.source.length}},"supporting":[]},"quantity_origin":"$quantityOrigin","amount_role_status":"$amountRoleStatus"}"""
    }
    val readyRows = rows.filterIndexed { index, _ -> !cases[index].needsClarification }
    val blockedRows = cases.mapIndexedNotNull { index, case ->
        if (!case.needsClarification) null else {
            val start = instruction.indexOf(case.source)
            """{"row_index":$index,"source":{"text":${sourceIdentityJsonString(case.source)},"start_utf16":$start,"end_utf16":${start + case.source.length}},"issue_codes":["model_needs_clarification"]}"""
        }
    }
    val constraints = fulfillment.joinToString(",") { text ->
        val start = instruction.indexOf(text)
        require(start >= 0)
        """{"kind":"fulfillment","source":{"text":${sourceIdentityJsonString(text)},"start_utf16":$start,"end_utf16":${start + text.length}}}"""
    }
    val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${sourceIdentityJsonString(instruction)},"source_item_count":${cases.size},"ready_rows":[${readyRows.joinToString(",")}],"blocked_rows":[${blockedRows.joinToString(",")}],"groups":[],"constraints":[$constraints]},"model":"test","promptVersion":"source-identity-test","latencyMs":1,"usage":{}}"""
    return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
}

private fun assertReviewBlocked(
    case: SourceIdentityCase,
    prepared: GroceryIntentPreparation,
    requireNoItems: Boolean = false,
) {
    if (requireNoItems) assertTrue("${case.source} -> ${case.name}", prepared.items.isEmpty())
    val gate = GroceryIntentReviewGate()
    gate.prepare(prepared.unresolvedText, prepared.excludedText, prepared.sharedGroupNotes,
        prepared.fulfillmentConstraints)
    assertFalse("${case.source} -> review gate", gate.canApply())
}

private fun sourceIdentityJsonArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { sourceIdentityJsonString(it) }

private fun sourceIdentityJsonString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""

private fun prepareGroupedSourceIdentity(
    first: SourceIdentityCase,
    second: SourceIdentityCase,
    header: String = "ExampleBrand: ",
    groupPackaging: String? = null,
): GroceryIntentPreparation {
    val instruction = "$header${first.source} and ${second.source}"
    fun row(case: SourceIdentityCase, index: Int): String {
        val start = instruction.indexOf(case.source)
        val quantity = case.quantity?.toString() ?: "null"
        val quantityOrigin = if (case.quantity == null) "not_stated" else "stated"
        val amountRoleStatus = if (case.quantity == null && case.packValue == null) "not_stated" else "matched"
        val quantityText = case.quantityText?.let(::sourceIdentityJsonString) ?: "null"
        return """{"row_index":$index,"item":{"name":${sourceIdentityJsonString(case.name)},"quantity":$quantity,"quantity_unit":${sourceIdentityJsonString(case.quantityUnit)},"pack_value":${case.packValue ?: "null"},"pack_unit":${sourceIdentityJsonString(case.packUnit)},"packaging":${case.packaging?.let(::sourceIdentityJsonString) ?: "null"},"required":${sourceIdentityJsonArray(case.required)},"excluded":${sourceIdentityJsonArray(case.excluded)},"source_text":${sourceIdentityJsonString(case.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":$quantityText,"pack_text":${case.packText?.let(::sourceIdentityJsonString) ?: "null"}},"evidence":{"primary":{"text":${sourceIdentityJsonString(case.source)},"start_utf16":$start,"end_utf16":${start + case.source.length}},"supporting":[]},"quantity_origin":"$quantityOrigin","amount_role_status":"$amountRoleStatus"}"""
    }
    val members = listOf(first, second).mapIndexed { index, case ->
        """{"row_index":$index,"value":${case.quantity ?: "null"},"unit":${sourceIdentityJsonString(case.quantityUnit)},"origin":"local"}"""
    }
    val group = """{"status":"structurally_verified","amount_status":"local_amounts_verified","source":{"text":${sourceIdentityJsonString(instruction)},"start_utf16":0,"end_utf16":${instruction.length}},"header":{"text":${sourceIdentityJsonString(header)},"start_utf16":0,"end_utf16":${header.length}},"member_indices":[0,1],"members":[${members.joinToString(",")}],"packaging":${groupPackaging?.let(::sourceIdentityJsonString) ?: "null"},"total":null,"each":null}"""
    val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${sourceIdentityJsonString(instruction)},"source_item_count":2,"ready_rows":[${row(first, 0)},${row(second, 1)}],"blocked_rows":[],"groups":[$group]},"model":"test","promptVersion":"source-identity-test","latencyMs":1,"usage":{}}"""
    return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
}
