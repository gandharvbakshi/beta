package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSubmissionPolicyTest {
    @Test
    fun keepSearchInputOpen_returnsFalseForBlinkitSearchInputActions() {
        assertFalse(
            SearchSubmissionPolicy.keepSearchInputOpen(
                isSearchInputAction = true,
                isBlinkitForeground = true
            )
        )
    }

    @Test
    fun keepSearchInputOpen_returnsTrueForNonBlinkitSearchInputActions() {
        assertTrue(
            SearchSubmissionPolicy.keepSearchInputOpen(
                isSearchInputAction = true,
                isBlinkitForeground = false
            )
        )
    }

    @Test
    fun keepSearchInputOpen_returnsFalseForNonSearchActions() {
        assertFalse(
            SearchSubmissionPolicy.keepSearchInputOpen(
                isSearchInputAction = false,
                isBlinkitForeground = false
            )
        )
    }
}
