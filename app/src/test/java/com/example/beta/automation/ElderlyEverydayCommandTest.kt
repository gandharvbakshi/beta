package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser fixtures for short everyday commands. These are offline assertions:
 * they stop at ParsedItem and never call a provider or mutate a basket.
 */
class ElderlyEverydayCommandTest {
    private data class CoreCase(
        val input: String,
        val expectedQuery: String,
        val expectedQuantity: Quantity = Quantity.Count(1),
    )

    @Test
    fun everyday_commands_keep_core_inventory_and_quantity() {
        val cases = listOf(
            CoreCase("Order one samosa", "samosa"),
            CoreCase("I want one samosa", "samosa"),
            CoreCase("I need one samosa", "samosa"),
            CoreCase("Can you please order one samosa", "samosa"),
            CoreCase("Can you please add one samosa", "samosa"),
            CoreCase("I would like one samosa", "samosa"),
            CoreCase("Could you please order one samosa", "samosa"),
            CoreCase("Order one tetra pack milk", "tetra pack milk"),
            CoreCase("I want one tetra pack milk", "tetra pack milk"),
            CoreCase("I need one tetra pack milk", "tetra pack milk"),
            CoreCase("Can you please order one tetra pack milk", "tetra pack milk"),
            CoreCase("ek samosa", "samosa"),
            CoreCase("ek tetra pack doodh", "tetra pack milk"),
            CoreCase("I want one lean milk", "low fat milk"),
            CoreCase("I need one lean milk", "low fat milk"),
            CoreCase("Can you please order one lean milk", "low fat milk"),
            CoreCase("one lean milk", "low fat milk"),
        )

        val failures = cases.mapNotNull { case ->
            val items = InstructionParser.parse(case.input)
            if (items.size != 1) return@mapNotNull "${case.input}: expected 1 item, actual ${items.size}"
            val item = items.single()
            val errors = buildList {
                if (item.query != case.expectedQuery) add("query expected '${case.expectedQuery}' actual '${item.query}'")
                if (item.quantity != case.expectedQuantity) add("quantity expected ${case.expectedQuantity} actual ${item.quantity}")
            }
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "${case.input}: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun polite_and_hinglish_quantity_variants_preserve_inventory_words() {
        val cases = listOf(
            "I want one milk with less cream" to ("low fat milk" to Quantity.Count(1)),
            "Can you please order one milk with less cream" to ("low fat milk" to Quantity.Count(1)),
            "One milk with less cream" to ("low fat milk" to Quantity.Count(1)),
            "ek milk kam malai wala" to ("milk kam malai wala" to Quantity.Count(1)),
            "kam malai wala doodh" to ("low fat milk" to Quantity.Default),
            "doodh kam malai wala" to ("low fat milk" to Quantity.Default),
            "ek lean doodh" to ("lean milk" to Quantity.Count(1)),
            "do samosa" to ("samosa" to Quantity.Count(2)),
            "I want two samosa" to ("samosa" to Quantity.Count(2)),
            "I need teen samosa" to ("samosa" to Quantity.Count(3)),
        )

        val failures = cases.mapNotNull { (input, expected) ->
            val (expectedQuery, expectedQuantity) = expected
            val items = InstructionParser.parse(input)
            if (items.size != 1) return@mapNotNull "$input: expected 1 item, actual ${items.size}"
            val item = items.single()
            val errors = buildList {
                if (item.query != expectedQuery) add("query expected '$expectedQuery' actual '${item.query}'")
                if (item.quantity != expectedQuantity) add("quantity expected $expectedQuantity actual ${item.quantity}")
            }
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "$input: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun sugar_free_and_preparation_language_is_not_invented_or_collapsed() {
        val readyMadeCases = listOf(
            "I want sugar-free ready-made tea",
            "I need sugar free ready made tea",
            "Can you please order sugar-free ready-made tea",
            "sugar-free ready-made tea",
        )
        val readyMadeFailures = readyMadeCases.mapNotNull { input ->
            val items = InstructionParser.parse(input)
            if (items.size != 1) return@mapNotNull "$input: expected 1 item, actual ${items.size}"
            val query = items.single().query
            val errors = buildList {
                if (query != "sugar free ready to drink tea") add("query expected 'sugar free ready to drink tea' actual '$query'")
                if (items.single().avoidPhrases.isNotEmpty()) add("avoidPhrases expected [] actual ${items.single().avoidPhrases}")
                if (items.single().quantity != Quantity.Default) add("quantity expected Default actual ${items.single().quantity}")
            }
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "$input: $it" }
        }
        assertTrue(readyMadeFailures.joinToString("\n"), readyMadeFailures.isEmpty())

        val readyMadeCoffee = listOf("ready-made coffee", "ready made coffee", "coffee ready-made", "coffee ready made")
        val coffeeFailures = readyMadeCoffee.mapNotNull { input ->
            val items = InstructionParser.parse(input)
            if (items.size != 1) return@mapNotNull "$input: expected 1 item, actual ${items.size}"
            val item = items.single()
            if (item.query != "ready to drink coffee") "$input: query expected 'ready to drink coffee' actual '${item.query}'" else null
        }
        assertTrue(coffeeFailures.joinToString("\n"), coffeeFailures.isEmpty())

        val standaloneSugarFree = InstructionParser.parse("sugar-free")
        assertEquals(1, standaloneSugarFree.size)
        assertEquals("sugar free", standaloneSugarFree.single().query)
        assertTrue(standaloneSugarFree.single().avoidPhrases.isEmpty())
        assertFalse("sugar-free alone must not invent tea", standaloneSugarFree.single().query.contains("tea"))

        val noAddedSugarItem = InstructionParser.parse("no added sugar").single()
        val withoutAddedSugarItem = InstructionParser.parse("without added sugar").single()
        assertEquals("no added sugar", noAddedSugarItem.query)
        assertEquals("no added sugar", withoutAddedSugarItem.query)
        assertTrue(noAddedSugarItem.avoidPhrases.isEmpty())
        assertTrue(withoutAddedSugarItem.avoidPhrases.isEmpty())
        val noAddedSugar = noAddedSugarItem.query
        val sugarFree = standaloneSugarFree.single().query
        assertNotEquals("no added sugar and sugar-free remain distinct", noAddedSugar, sugarFree)
        assertTrue(noAddedSugar.contains("added"))

        val plainReadyMade = InstructionParser.parse("ready-made tea").single().query
        val instantTea = InstructionParser.parse("instant tea").single().query
        assertEquals("ready to drink tea", plainReadyMade)
        assertEquals("instant tea", instantTea)
        assertNotEquals("plain ready-made and instant tea are not synonyms", plainReadyMade, instantTea)

        assertEquals("ready made popcorn", InstructionParser.parse("ready-made popcorn").single().query)
    }

    @Test
    fun hindi_and_hinglish_preference_phrases_remain_visible() {
        data class PreferenceCase(
            val input: String,
            val expectedQuery: String,
            val expectedAvoid: List<String> = emptyList(),
        )
        val cases = listOf(
            PreferenceCase("bina cheeni chai", "tea", listOf("sugar")),
            PreferenceCase("chai bina cheeni", "tea", listOf("sugar")),
            PreferenceCase("kam malai wala doodh", "low fat milk"),
            PreferenceCase("doodh kam malai wala", "low fat milk"),
            PreferenceCase("no added sugar", "no added sugar"),
            PreferenceCase("without added sugar", "no added sugar"),
            PreferenceCase("tea without added sugar", "tea no added sugar"),
            PreferenceCase("tea no added sugar", "tea no added sugar"),
            PreferenceCase("tea with no added sugar", "tea no added sugar"),
            PreferenceCase("tea no added sugar ready made", "tea no added sugar ready made"),
            PreferenceCase("no added sugar ready made tea", "no added sugar ready to drink tea"),
            PreferenceCase("tea without sugar", "tea", listOf("sugar")),
        )

        val failures = cases.mapNotNull { case ->
            val items = InstructionParser.parse(case.input)
            if (items.size != 1) return@mapNotNull "${case.input}: expected 1 item, actual ${items.size}"
            val item = items.single()
            val errors = buildList {
                if (item.query != case.expectedQuery) add("query expected '${case.expectedQuery}' actual '${item.query}'")
                if (item.avoidPhrases != case.expectedAvoid) add("avoidPhrases expected ${case.expectedAvoid} actual ${item.avoidPhrases}")
            }
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "${case.input}: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun retail_pack_count_provenance_is_not_inferred_from_plain_counts() {
        data class RetailCase(
            val input: String,
            val expectedQuery: String,
            val expectedQuantity: Quantity,
            val expectedRetailPackCount: Boolean,
        )
        val cases = listOf(
            RetailCase("one packet frozen samosa", "frozen samosa", Quantity.Count(1), true),
            RetailCase("1 samosa", "samosa", Quantity.Count(1), false),
            RetailCase("5 milk tetrapacks", "milk tetrapacks", Quantity.Count(5), true),
            RetailCase("milk packs paanch", "milk", Quantity.Count(5), true),
            RetailCase("2x500ml milk packs paanch", "2x500ml milk", Quantity.Count(5), true),
            RetailCase("24 pack paper towels", "24 pack paper towels", Quantity.Default, false),
        )

        val failures = cases.mapNotNull { case ->
            val items = InstructionParser.parse(case.input)
            if (items.size != 1) return@mapNotNull "${case.input}: expected 1 item, actual ${items.size}"
            val item = items.single()
            val errors = buildList {
                if (item.query != case.expectedQuery) add("query expected '${case.expectedQuery}' actual '${item.query}'")
                if (item.quantity != case.expectedQuantity) add("quantity expected ${case.expectedQuantity} actual ${item.quantity}")
                if (item.retailPackCount != case.expectedRetailPackCount) {
                    add("retailPackCount expected ${case.expectedRetailPackCount} actual ${item.retailPackCount}")
                }
            }
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "${case.input}: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun pack_and_piece_requests_with_same_query_do_not_merge() {
        val items = InstructionParser.parse("1 packet frozen samosa and 1 frozen samosa")
        assertEquals(2, items.size)
        assertEquals(Quantity.Count(1), items[0].quantity)
        assertEquals(Quantity.Count(1), items[1].quantity)
        assertTrue(items[0].retailPackCount)
        assertFalse(items[1].retailPackCount)
    }

    @Test
    fun implicit_mixed_quantity_boundary_does_not_leak_pack_provenance() {
        val items = InstructionParser.parse("1 milk pack 2 samosa")
        assertTrue(items.none { it.query == "samosa" && it.retailPackCount })
    }
}
