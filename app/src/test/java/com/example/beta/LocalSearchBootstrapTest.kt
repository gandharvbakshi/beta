package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSearchBootstrapTest {
    @Test
    fun topValidSearch_isSelected() {
        val result = LocalSearchBootstrap.selectSearchBootstrapResult(
            listOf(
                candidate(
                    text = "Search",
                    left = 36,
                    top = 42,
                    right = 176,
                    bottom = 88
                )
            )
        )

        assertEquals(LocalSearchBootstrapResult(106, 65, "search", SearchConfidenceCategory.MEDIUM), result)
    }

    @Test
    fun bottomSearchOccurrence_isRejected() {
        val result = LocalSearchBootstrap.selectSearchBootstrapResult(
            listOf(
                candidate(
                    text = "Search",
                    left = 28,
                    top = 1260,
                    right = 180,
                    bottom = 1310
                )
            )
        )

        assertNull(result)
    }

    @Test
    fun unsafeText_isRejected() {
        val result = LocalSearchBootstrap.selectSearchBootstrapResult(
            listOf(
                candidate(
                    text = "Search cart coupon",
                    left = 28,
                    top = 44,
                    right = 230,
                    bottom = 92
                )
            )
        )

        assertNull(result)
    }

    @Test
    fun bestCandidate_prefersExactSearchPhrase() {
        val result = LocalSearchBootstrap.selectSearchBootstrapResult(
            listOf(
                candidate(
                    text = "Search",
                    left = 28,
                    top = 34,
                    right = 128,
                    bottom = 78
                ),
                candidate(
                    text = "Search for products",
                    left = 24,
                    top = 28,
                    right = 252,
                    bottom = 82
                )
            )
        )

        assertEquals(
            LocalSearchBootstrapResult(138, 55, "search for products", SearchConfidenceCategory.HIGH),
            result
        )
    }

    private fun candidate(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Int = 360,
        imageHeight: Int = 1600
    ): SearchOcrCandidate {
        return SearchOcrCandidate(
            text = text,
            bounds = SearchBounds(left, top, right, bottom),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }
}
