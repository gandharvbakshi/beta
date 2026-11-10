package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentPrimaryCorrectionTest {
    @Test fun unprovenOperationInsideEitherPrimaryBlocksTheWholeBasket() {
        val operations = listOf(
            "no sorry keep three",
            "no wait make it three",
            "not sorry, make it three",
            "actually keep three",
            "nahi sorry teen rakho",
            "if needed, make it three",
            "make milk three",
            "set milk to three",
            "keep it three",
            "teen rakho",
            "teen rakh do",
            "teen kar do",
            "teen bana do",
            "badal do",
        )
        operations.forEach { operation ->
            for (foldedFirst in listOf(true, false)) {
                val folded = "two rice, $operation"
                val milk = "one milk"
                val instruction = if (foldedFirst) "$folded, $milk" else "$milk, $folded"
                val result = primaryReview(instruction, listOf(
                    primaryReady(instruction, if (foldedFirst) 0 else 1,
                        primaryItem("rice", folded, 2)),
                    primaryReady(instruction, if (foldedFirst) 1 else 0,
                        primaryItem("milk", milk, 1)),
                )).prepareForReview()

                assertTrue("operation should block all parsed items: $instruction", result.items.isEmpty())
                assertTrue("original folded wording should be visible: $instruction",
                    result.excludedText.any { it.contains(folded) })
            }
        }
    }

    @Test fun foldedAndOmittedOperationsAreBothRejectedWithoutClarificationFlag() {
        val folded = "two rice, no sorry keep three"
        val omittedInstruction = "one milk, two rice, no sorry keep three"
        val omitted = primaryReview(omittedInstruction, listOf(
            primaryReady(omittedInstruction, 0, primaryItem("milk", "one milk", 1)),
            primaryReady(omittedInstruction, 1, primaryItem("rice", "two rice", 2)),
        )).prepareForReview()
        assertTrue(omitted.items.isEmpty())
        assertTrue((omitted.excludedText + omitted.unresolvedText).any { it.contains("no sorry keep three") })

        val foldedInstruction = "one milk, $folded"
        val foldedResult = primaryReview(foldedInstruction, listOf(
            primaryReady(foldedInstruction, 0, primaryItem("milk", "one milk", 1)),
            primaryReady(foldedInstruction, 1, primaryItem("rice", folded, 2)),
        )).prepareForReview()
        assertTrue(foldedResult.items.isEmpty())
        assertTrue(foldedResult.excludedText.any { it.contains(folded) })
    }

    @Test fun standaloneOrUnpunctuatedOperationCannotHideReferenceToAnotherPrimary() {
        listOf("make milk three", "set milk to three", "set it three", "keep milk three").forEach { operation ->
            listOf(operation, "two rice $operation").forEach { source ->
                val instruction = "one milk, $source"
                val result = primaryReview(instruction, listOf(
                    primaryReady(instruction, 0, primaryItem("milk", "one milk", 1)),
                    primaryReady(instruction, 1, primaryItem("rice", source, 2)),
                )).prepareForReview()
                assertTrue(operation, result.items.isEmpty())
                assertTrue(result.excludedText.any { it.contains(source) })
            }
        }
    }

    @Test fun blockedFoldedPrimaryAlsoBlocksItsReadyPeer() {
        val folded = "two rice, no sorry keep three"
        val instruction = "one milk, $folded"
        val result = primaryReview(instruction,
            listOf(primaryReady(instruction, 0, primaryItem("milk", "one milk", 1))),
            listOf(primaryBlocked(instruction, 1, folded)),
        ).prepareForReview()
        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.any { it.contains(folded) })
    }

    @Test fun decodedLegacyPartialWireCannotKeepReadyPeerWhenBlockedPrimaryHasCorrection() {
        val instruction = "one milk, two rice, no sorry keep three"
        val ready = "one milk"
        val blocked = "two rice, no sorry keep three"
        val readyStart = instruction.indexOf(ready)
        val blockedStart = instruction.indexOf(blocked)
        val wire = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":"$instruction","source_item_count":2,"ready_rows":[{"row_index":0,"item":{"name":"milk","quantity":1,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":"$ready","supporting_text":[],"needs_clarification":false,"quantity_text":"one","pack_text":null},"evidence":{"primary":{"text":"$ready","start_utf16":$readyStart,"end_utf16":${readyStart + ready.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}],"blocked_rows":[{"row_index":1,"source":{"text":"$blocked","start_utf16":$blockedStart,"end_utf16":${blockedStart + blocked.length}},"issue_codes":["model_needs_review"]}]},"model":"synthetic","promptVersion":"primary-correction-test","latencyMs":1,"usage":{}}"""

        val result = GroceryIntentReviewV2Response.parse(wire, instruction).draft.prepareForReview()
        assertTrue(result.items.isEmpty())
        assertTrue(result.excludedText.any { it.contains(blocked) })
    }

    @Test fun typedNamedCorrectionStillWorksAndOrdinaryRowsRemainIndependent() {
        val correction = "make rice 3"
        val instruction = "2 rice; $correction, one milk"
        val rice = primaryReady(instruction, 0, primaryItem("rice", "2 rice", 2)).copy(
            corrections = listOf(GroceryIntentReviewV2Correction(
                kind = "quantity",
                source = primaryQuote(instruction, correction),
                newAmount = primaryQuote(instruction, "3"),
                oldValue = BigDecimal("2"), oldUnit = "count",
                newValue = BigDecimal("3"), newUnit = "count",
            )),
        )
        val result = primaryReview(instruction, listOf(
            rice,
            primaryReady(instruction, 1, primaryItem("milk", "one milk", 1)),
        )).prepareForReview()
        assertEquals(listOf(Quantity.Count(3), Quantity.Count(1)), result.items.map { it.quantity })
        assertTrue(result.unresolvedText.isEmpty())

        val exclusionInstruction = "one zero sugar drink, two rice"
        val exclusionsPreserved = primaryReview(exclusionInstruction, listOf(
            primaryReady(exclusionInstruction, 0, primaryItem("drink", "one zero sugar drink", 1)
                .copy(required = listOf("sugar_free"), excluded = listOf("sugar"))),
            primaryReady(exclusionInstruction, 1, primaryItem("rice", "two rice", 2)),
        )).prepareForReview()
        assertEquals(2, exclusionsPreserved.items.size)

        val curdInstruction = "one set curd, two rice"
        val ordinary = primaryReview(curdInstruction, listOf(
            primaryReady(curdInstruction, 0, primaryItem("set curd", "one set curd", 1)),
            primaryReady(curdInstruction, 1, primaryItem("rice", "two rice", 2)),
        )).prepareForReview()
        assertEquals(2, ordinary.items.size)

        val trailingQuantity = "set curd one"
        val trailing = primaryReview(trailingQuantity, listOf(
            primaryReady(trailingQuantity, 0, primaryItem("set curd", trailingQuantity, 1)),
        )).prepareForReview()
        assertEquals(listOf("set curd"), trailing.items.map { it.query })

        val nameFirst = "set curd, one milk"
        val nameFirstRows = primaryReview(nameFirst, listOf(
            primaryReady(nameFirst, 0, primaryItem("set curd", "set curd", 0).copy(
                quantity = null, quantityUnit = "unspecified", quantityText = null,
            )),
            primaryReady(nameFirst, 1, primaryItem("milk", "one milk", 1)),
        )).prepareForReview()
        assertTrue(nameFirstRows.items.any { it.query == "set curd" })
    }

    @Test fun countedSetDairyCompoundsRemainLiteralProductsInFullPreparation() {
        val cases = listOf(
            Triple("one Valley curd, one Meadow set curd", "Valley curd", "Meadow set curd"),
            Triple("one Valley yogurt, one Meadow set yogurt", "Valley yogurt", "Meadow set yogurt"),
            Triple("one Valley yoghurt, one Meadow set yoghurt", "Valley yoghurt", "Meadow set yoghurt"),
        )
        cases.forEach { (instruction, firstName, secondName) ->
            val first = "one $firstName"
            val second = "one $secondName"
            val prepared = primaryReview(instruction, listOf(
                primaryReady(instruction, 0, primaryItem(firstName, first, 1)),
                primaryReady(instruction, 1, primaryItem(secondName, second, 1)),
            )).prepareForReview()
            assertEquals(instruction, listOf(firstName, secondName), prepared.items.map { it.query })
            assertEquals(instruction, listOf(Quantity.Count(1), Quantity.Count(1)), prepared.items.map { it.quantity })
            assertTrue(instruction, prepared.excludedText.isEmpty())
            assertTrue(instruction, prepared.unresolvedText.isEmpty())
        }

        val brandlessInstruction = "one set curd, two rice"
        val brandless = primaryReview(brandlessInstruction, listOf(
            primaryReady(brandlessInstruction, 0, primaryItem("set curd", "one set curd", 1)),
            primaryReady(brandlessInstruction, 1, primaryItem("rice", "two rice", 2)),
        )).prepareForReview()
        assertEquals(listOf("set curd", "rice"), brandless.items.map { it.query })
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), brandless.items.map { it.quantity })
        assertTrue(brandless.excludedText.isEmpty())
    }

    @Test fun countedSetDairyOperationLikePhrasesBlockTheirOtherCurdPeer() {
        val cases = listOf(
            "one Valley curd, set curd to two" to listOf(
                Triple("one Valley curd", "Valley curd", 1),
                Triple("set curd to two", "curd", 2),
            ),
            "one Valley curd, two rice set curd three" to listOf(
                Triple("one Valley curd", "Valley curd", 1),
                Triple("two rice set curd three", "rice", 2),
            ),
            "one curd; set curd" to listOf(
                Triple("one curd", "curd", 1),
                Triple("set curd", "curd", 1),
            ),
            "one Plain curd, one please set curd" to listOf(
                Triple("one Plain curd", "Plain curd", 1),
                Triple("one please set curd", "curd", 1),
            ),
            "one Meadow set curd and keep curd three, two rice" to listOf(
                Triple("one Meadow set curd and keep curd three", "Meadow set curd", 1),
                Triple("two rice", "rice", 2),
            ),
        )
        cases.forEach { (instruction, specs) ->
            val rows = specs.mapIndexed { index, (source, name, quantity) ->
                primaryReady(instruction, index, primaryItem(name, source, quantity))
            }
            val prepared = primaryReview(instruction, rows).prepareForReview()
            assertTrue("operation must block every peer: $instruction", prepared.items.isEmpty())
            assertTrue("operation wording remains visible: $instruction",
                (prepared.excludedText + prepared.unresolvedText).isNotEmpty())
        }
    }

    @Test fun countedDairyExceptionCannotHideOtherCountOwners() {
        listOf("customers preferred", "families shared", "families sharing", "people used", "children loved",
            "people choosing", "sets Meadow", "trays Meadow", "cartons Meadow", "portions Meadow", "tetra packs").forEach { prefix ->
            val name = "$prefix set curd"
            val source = "two $name"
            val instruction = "one Plain curd, $source"
            val prepared = primaryReview(instruction, listOf(
                primaryReady(instruction, 0, primaryItem("Plain curd", "one Plain curd", 1)),
                primaryReady(instruction, 1, primaryItem(name, source, 2)),
            )).prepareForReview()
            assertTrue(instruction, prepared.items.isEmpty())
            assertTrue(instruction, prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun requestAndListingScaffoldingAreNotAmountCorrections() {
        listOf("order kar do", "add kar do", "cart mein rakh do", "section bana do").forEach {
            assertTrue(it, !intentV2HasCorrectionCue(it))
        }
        listOf("teen", "3", "half", "0", "zero", "shunya").forEach { atom ->
            listOf("kar do", "rakh do", "bana do", "rakho").forEach { verb ->
                assertTrue("$atom $verb", intentV2HasCorrectionCue("$atom $verb"))
            }
        }
        val instruction = "one milk kar do, two rice"
        val result = primaryReview(instruction, listOf(
            primaryReady(instruction, 0, primaryItem("milk", "one milk kar do", 1)),
            primaryReady(instruction, 1, primaryItem("rice", "two rice", 2)),
        )).prepareForReview()
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), result.items.map { it.quantity })
    }
}

private fun primaryReview(
    instruction: String,
    ready: List<GroceryIntentReviewV2ReadyRow>,
    blocked: List<GroceryIntentReviewV2BlockedRow> = emptyList(),
) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
    sourceItemCount = ready.size + blocked.size, readyRows = ready, blockedRows = blocked,
)

private fun primaryItem(name: String, source: String, count: Int) = GroceryIntentReviewV2Item(
    name = name, quantity = BigDecimal.valueOf(count.toLong()), quantityUnit = "count",
    packValue = null, packUnit = "unspecified", packaging = null,
    required = emptyList(), excluded = emptyList(), sourceText = source,
    supportingText = emptyList(), needsClarification = false,
    quantityText = when {
        Regex("\\b$count\\b").containsMatchIn(source) -> count.toString()
        count == 1 -> "one"
        count == 2 -> "two"
        count == 3 -> "three"
        else -> count.toString()
    }, packText = null,
)

private fun primaryReady(instruction: String, index: Int, item: GroceryIntentReviewV2Item) =
    GroceryIntentReviewV2ReadyRow(
        rowIndex = index, item = item,
        evidence = GroceryIntentReviewV2Evidence(primaryQuote(instruction, item.sourceText), emptyList()),
        quantityOrigin = "stated", amountRoleStatus = "matched",
    )

private fun primaryBlocked(instruction: String, index: Int, source: String) =
    GroceryIntentReviewV2BlockedRow(index, primaryQuote(instruction, source), listOf("model_needs_review"))

private fun primaryQuote(instruction: String, text: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(text)
    require(start >= 0)
    return GroceryIntentReviewV2Quote(text, start, start + text.length)
}
