package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommerceSearchFocusPolicyTest {
    @Test
    fun acceptsCurrentBlinkitHomeSearchSemantics() {
        assertTrue(candidate(description = "Search"))
        assertTrue(candidate(description = "Search \"sunscreen\""))
        assertTrue(candidate(resourceId = "com.grofers.customerapp:id/search_container"))
    }

    @Test
    fun rejectsVoiceSearchAndUnrelatedContent() {
        assertFalse(candidate(description = "Double tap to voice search"))
        assertFalse(candidate(resourceId = "com.grofers.customerapp:id/voice_search"))
        assertFalse(candidate(description = "Drinks & Juices"))
    }

    @Test
    fun rejectsUnavailableNodes() {
        assertFalse(candidate(description = "Search", visible = false))
        assertFalse(candidate(description = "Search", enabled = false))
    }

    private fun candidate(
        resourceId: String = "",
        text: String = "",
        description: String = "",
        visible: Boolean = true,
        enabled: Boolean = true
    ): Boolean = CommerceSearchFocusPolicy.isAccessibilityCandidate(
        resourceId = resourceId,
        text = text,
        description = description,
        visible = visible,
        enabled = enabled
    )
}
