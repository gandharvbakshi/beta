package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryPreparationJoinTest {
    @Test fun waitsForBothAndDeliversExactlyOnceInEitherOrder() {
        for (reverse in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val join = GroceryPreparationJoin<String, Int> { a, b -> calls += "$a:$b" }
            if (reverse) join.second(2) else join.first("basket")
            assertEquals(emptyList<String>(), calls)
            if (reverse) join.first("basket") else join.second(2)
            join.first("late")
            join.second(3)
            assertEquals(listOf("basket:2"), calls)
        }
    }
}
