package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentRestatementsTest {
    @Test fun freshnessFormRestatementRequiresBothOriginalFormAndRequiredProperty() {
        val forms = listOf("fresh", "frozen", "dried")
        forms.forEach { form ->
            val clause = "spinach $form hi"
            val primary = "1 $form spinach"
            val instruction = "$primary; $clause."
            val primaryQuote = quote(instruction, primary)
            val receipt = groceryIntentRestatementReceipts(instruction,
                listOf(row(0, primaryQuote, "1", "count", required = listOf(form))), listOf(primaryQuote)).single()
            assertEquals(0, receipt.rowIndex)
            assertEquals(clause, receipt.source.text)
            assertEquals(clause, instruction.substring(receipt.source.startUtf16, receipt.source.endUtf16))

            val otherForm = forms.first { it != form }
            listOf(
                "1 spinach" to listOf(form),
                "1 $otherForm spinach" to listOf(form),
                primary to emptyList<String>(),
                primary to listOf(otherForm),
            ).forEach { (unprovenPrimary, required) ->
                val unprovenInstruction = "$unprovenPrimary; $clause."
                val source = quote(unprovenInstruction, unprovenPrimary)
                assertTrue("form=$form, primary=$unprovenPrimary, required=$required",
                    groceryIntentRestatementReceipts(unprovenInstruction,
                        listOf(row(0, source, "1", "count", required = required)), listOf(source)).isEmpty())
            }
        }
    }

    @Test fun exactAdjacentPurchaseQuantityExclusionsHaveTheirOwnReceiptKind() {
        val primary = "two boxes of cereal"
        listOf(", not one" to "not one", " not 1 box" to "not 1 box").forEach { (tail, expected) ->
            val instruction = "$primary$tail; one apple"
            val source = quote(instruction, primary)
            val rows = listOf(row(0, source, "2", "pack", packaging = "box"))

            assertTrue(groceryIntentRestatementReceipts(instruction, rows, listOf(source)).isEmpty())
            val receipt = groceryIntentRestatementReceipts(
                instruction, rows, listOf(source), allowQuantityExclusions = true,
            ).single()

            assertEquals(0, receipt.rowIndex)
            assertEquals("quantity_exclusion", receipt.kind)
            assertEquals(expected, receipt.source.text)
            assertEquals(expected, instruction.substring(receipt.source.startUtf16, receipt.source.endUtf16))
            assertTrue(receipt.source.startUtf16 >= source.endUtf16)
        }
    }

    @Test fun quantityExclusionRequiresExactDiscreteRoleAndCompleteHardBoundedTail() {
        val primary = "two boxes of cereal"
        val rejected = listOf(
            ", no one", ", nahi one", ", not two boxes", ", not zero boxes", ", not 0.5 boxes",
            ", not 1 bottle", ", not one, buy rice", ", not one if available", ", not 1 box or 2 boxes",
        )
        rejected.forEach { tail ->
            val instruction = "$primary$tail; one apple"
            val source = quote(instruction, primary)
            assertTrue("unexpected receipt for $tail", groceryIntentRestatementReceipts(
                instruction, listOf(row(0, source, "2", "pack", packaging = "box")), listOf(source),
                allowQuantityExclusions = true,
            ).isEmpty())
        }

        listOf(
            Triple("2 apples", "2", "count") to ", not 1",
            Triple("two packs of apples", "2", "pack") to " not one",
            Triple("2 pieces gum", "2", "piece") to ", not one",
        ).forEach { (primarySpec, tail) ->
            val (text, quantity, unit) = primarySpec
            val instruction = text + tail
            val source = quote(instruction, text)
            assertEquals(1, groceryIntentRestatementReceipts(
                instruction, listOf(row(0, source, quantity, unit,
                    packaging = if (unit == "pack") "pack" else null)), listOf(source),
                allowQuantityExclusions = true,
            ).size)
        }
    }

    @Test fun packSizeAndPhysicalExclusionsNeedUnambiguousMatchingRoles() {
        val packPrimary = "two boxes of 500 g cereal"
        fun packReceipt(tail: String) = groceryIntentRestatementReceipts(
            packPrimary + tail,
            listOf(row(0, quote(packPrimary + tail, packPrimary), "2", "pack",
                packValue = "500", packUnit = "g", packaging = "box")),
            listOf(quote(packPrimary + tail, packPrimary)),
            allowQuantityExclusions = true,
        )
        assertTrue(packReceipt(", not one").isEmpty())
        assertEquals("quantity_exclusion", packReceipt(", not one box").single().kind)

        val physicalPrimary = "2 kg rice"
        fun physicalReceipt(tail: String, packValue: String? = null) = run {
            val instruction = physicalPrimary + tail
            val source = quote(instruction, physicalPrimary)
            groceryIntentRestatementReceipts(
                instruction,
                listOf(row(0, source, "2", "kg", packValue = packValue,
                    packUnit = if (packValue == null) "unspecified" else "kg", packaging = "pack".takeIf { packValue != null })),
                listOf(source),
                allowQuantityExclusions = true,
            )
        }
        assertEquals("quantity_exclusion", physicalReceipt(", not 1 kg").single().kind)
        assertTrue(physicalReceipt(", not 1000 g").isEmpty())
        assertTrue(physicalReceipt(", not 1 kg", packValue = "1").isEmpty())
    }

    @Test fun quantityExclusionCannotBorrowOwnerOrCrossProtectedAndNonAdjacentSpans() {
        val primary = "two boxes of cereal"
        val instruction = "$primary, not one; one cereal"
        val first = quote(instruction, primary)
        val second = quote(instruction, "one cereal")
        val originalRow = row(0, first, "2", "pack", packaging = "box")
        assertTrue(groceryIntentRestatementReceipts(
            instruction, listOf(originalRow), listOf(first, second), allowQuantityExclusions = true,
        ).isEmpty())

        val uniqueInstruction = "$primary, not one"
        val unique = quote(uniqueInstruction, primary)
        val protected = quote(uniqueInstruction, "not one")
        assertTrue(groceryIntentRestatementReceipts(
            uniqueInstruction, listOf(row(0, unique, "2", "pack", packaging = "box")), listOf(unique),
            protected = listOf(protected), allowQuantityExclusions = true,
        ).isEmpty())

        val before = ", not one; $primary"
        val laterPrimary = quote(before, primary)
        assertTrue(groceryIntentRestatementReceipts(
            before, listOf(row(0, laterPrimary, "2", "pack", packaging = "box")), listOf(laterPrimary),
            allowQuantityExclusions = true,
        ).isEmpty())

        val nonAdjacent = "$primary; one apple; not one"
        val nonAdjacentPrimary = quote(nonAdjacent, primary)
        assertTrue(groceryIntentRestatementReceipts(
            nonAdjacent, listOf(row(0, nonAdjacentPrimary, "2", "pack", packaging = "box")),
            listOf(nonAdjacentPrimary), allowQuantityExclusions = true,
        ).isEmpty())

        val forged = unique.copy(text = "two boxes of rice")
        assertTrue(groceryIntentRestatementReceipts(
            uniqueInstruction,
            listOf(row(0, forged, "2", "pack", packaging = "box")),
            listOf(forged), allowQuantityExclusions = true,
        ).isEmpty())

        val aliasInstruction = "two boxes of milk, not one; one doodh"
        val milk = quote(aliasInstruction, "two boxes of milk")
        val doodh = quote(aliasInstruction, "one doodh")
        assertTrue(groceryIntentRestatementReceipts(
            aliasInstruction,
            listOf(row(0, milk, "2", "pack", packaging = "box")),
            listOf(milk, doodh), allowQuantityExclusions = true,
        ).isEmpty())
    }

    @Test fun equalSourceFactsProduceExactLaterClauseReceipts() {
        val instruction = "2 packets tea; tea two packets hi! 1 kg rice; rice 1 kilogram same"
        val teaPrimary = quote(instruction, "2 packets tea")
        val ricePrimary = quote(instruction, "1 kg rice")
        val rows = listOf(
            row(0, teaPrimary, "2", "pack", packaging = "packet"),
            row(1, ricePrimary, "1", "kg"),
        )

        val receipts = groceryIntentRestatementReceipts(
            instruction,
            rows,
            listOf(teaPrimary, ricePrimary),
        )

        assertEquals(2, receipts.size)
        assertEquals(listOf(0, 1), receipts.map { it.rowIndex })
        assertEquals(listOf("tea two packets hi", "rice 1 kilogram same"), receipts.map { it.source.text })
        receipts.forEach { receipt ->
            assertEquals(receipt.source.text, instruction.substring(receipt.source.startUtf16, receipt.source.endUtf16))
        }
    }

    @Test fun equalPhysicalPackPropertyAndCountFactsAreTypedByTheirSyntax() {
        val instruction = "2 packets tea; tea 2 packets hi. 2 packets of 500 g rice; rice 500 grams each hi. " +
            "1 organic milk; milk organic hi. 4 apples; apples four hi"
        val teaPrimary = quote(instruction, "2 packets tea")
        val ricePrimary = quote(instruction, "2 packets of 500 g rice")
        val milkPrimary = quote(instruction, "1 organic milk")
        val applePrimary = quote(instruction, "4 apples")
        val rows = listOf(
            row(0, teaPrimary, "2", "pack", packaging = "packet"),
            row(1, ricePrimary, "2", "pack", packValue = "500", packUnit = "g", packaging = "packet"),
            row(2, milkPrimary, "1", "count", required = listOf("organic")),
            row(3, applePrimary, "4", "count"),
        )

        val receipts = groceryIntentRestatementReceipts(
            instruction,
            rows,
            listOf(teaPrimary, ricePrimary, milkPrimary, applePrimary),
        )

        assertEquals(listOf(0, 1, 2, 3), receipts.map { it.rowIndex })
        assertEquals(
            listOf("tea 2 packets hi", "rice 500 grams each hi", "milk organic hi", "apples four hi"),
            receipts.map { it.source.text },
        )
    }

    @Test fun changedNegatedIncompleteOrCountVsPackClausesCannotBeReceipts() {
        val primary = "2 packets tea"
        val badClauses = listOf(
            "tea 3 packets hi",
            "tea 2 packets nahi 3 hi",
            "tea 2 packets",
            "tea 2 hi",
            "tea 2 packets hi tomorrow",
            "tea 2 packets, 2 packets hi",
        )
        badClauses.forEach { clause ->
            val instruction = "$primary; $clause"
            val source = quote(instruction, primary)
            val receipts = groceryIntentRestatementReceipts(
                instruction,
                listOf(row(0, source, "2", "pack", packaging = "packet")),
                listOf(source),
            )
            assertTrue("unexpected receipt for: $clause", receipts.isEmpty())
        }

        val countPrimary = "2 tea"
        val countClause = "tea 2 packets hi"
        val countInstruction = "$countPrimary; $countClause"
        val countQuote = quote(countInstruction, countPrimary)
        assertTrue(groceryIntentRestatementReceipts(
            countInstruction,
            listOf(row(0, countQuote, "2", "count")),
            listOf(countQuote),
        ).isEmpty())
    }

    @Test fun onlyUniqueLiteralOriginalTargetInFullLaterUnprotectedClauseCanBeOwned() {
        val first = "2 packets tea"
        val second = "3 packets tea"
        val clause = "tea 2 packets hi"
        val instruction = "$first; $second; $clause"
        val firstQuote = quote(instruction, first)
        val secondQuote = quote(instruction, second)
        val rows = listOf(
            row(0, firstQuote, "2", "pack", packaging = "packet"),
            row(1, secondQuote, "3", "pack", packaging = "packet"),
        )
        val primaries = listOf(firstQuote, secondQuote)
        assertTrue(groceryIntentRestatementReceipts(instruction, rows, primaries).isEmpty())

        val uniqueInstruction = "$first; $clause"
        val uniquePrimary = quote(uniqueInstruction, first)
        val protectedClause = quote(uniqueInstruction, clause)
        assertTrue(groceryIntentRestatementReceipts(
            uniqueInstruction,
            listOf(row(0, uniquePrimary, "2", "pack", packaging = "packet")),
            listOf(uniquePrimary),
            protected = listOf(protectedClause),
        ).isEmpty())

        val beforePrimary = "$clause; $first"
        val laterPrimary = quote(beforePrimary, first)
        assertTrue(groceryIntentRestatementReceipts(
            beforePrimary,
            listOf(row(0, laterPrimary, "2", "pack", packaging = "packet")),
            listOf(laterPrimary),
        ).isEmpty())
    }

    @Test fun unicodeAndSourceQuoteMismatchesFailClosedWithoutChangingOffsets() {
        val instruction = "😀 2 packets tea; tea two packets hi"
        val source = quote(instruction, "2 packets tea")
        val receipt = groceryIntentRestatementReceipts(
            instruction,
            listOf(row(0, source, "2", "pack", packaging = "packet")),
            listOf(source),
        ).single()
        assertEquals(instruction.indexOf("tea two packets hi"), receipt.source.startUtf16)
        assertEquals(instruction.substring(receipt.source.startUtf16, receipt.source.endUtf16), receipt.source.text)

        val unicodeClause = "tea two packеts hi"
        val unsafe = "$instruction; $unicodeClause"
        val unsafeSource = quote(unsafe, "2 packets tea")
        val safeReceipts = groceryIntentRestatementReceipts(
            unsafe,
            listOf(row(0, unsafeSource, "2", "pack", packaging = "packet")),
            listOf(unsafeSource),
        )
        // The first, valid clause remains owned; the confusable clause does not.
        assertEquals(listOf("tea two packets hi"), safeReceipts.map { it.source.text })

        val forged = source.copy(text = "2 packets rice")
        assertTrue(groceryIntentRestatementReceipts(
            instruction,
            listOf(row(0, forged, "2", "pack", packaging = "packet")),
            listOf(forged),
        ).isEmpty())
    }

    private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
        val start = instruction.indexOf(text).also { require(it >= 0) }
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }

    private fun row(
        index: Int,
        primary: GroceryIntentReviewV2Quote,
        quantity: String?,
        quantityUnit: String,
        packValue: String? = null,
        packUnit: String = "unspecified",
        packaging: String? = null,
        required: List<String> = emptyList(),
    ) = GroceryIntentRestatementRow(
        rowIndex = index,
        primary = primary,
        quantity = quantity?.let { BigDecimal(it) },
        quantityUnit = quantityUnit,
        packValue = packValue?.let { BigDecimal(it) },
        packUnit = packUnit,
        packaging = packaging,
        required = required,
    )
}
