package com.example.beta

import org.junit.Assert.*
import org.junit.Test

class SwiggyCheckoutSpeechTest {
    @Test fun longBasketKeepsEveryWordWithinSpeechLimit() {
        val basket = (1..100).joinToString(". ") { "$it packets of carefully selected groceries with the exact pack size" }
        val chunks = splitSpeechForTts(basket)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 3500 })
        assertEquals(basket, chunks.joinToString(" "))
    }
    @Test fun emptyAndShortSpeechRemainSimple() {
        assertTrue(splitSpeechForTts("  ").isEmpty())
        assertEquals(listOf("Pay 100 rupees"), splitSpeechForTts(" Pay 100 rupees "))
    }
    @Test fun longUnbrokenTextNeverSplitsSurrogatePair() {
        val chunks = splitSpeechForTts("ab😀cd😀ef", 3)
        assertEquals("ab😀cd😀ef", chunks.joinToString(""))
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) })
    }
}
