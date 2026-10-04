package com.example.beta

import com.example.beta.SwiggyMcpClient.SwiggyAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyAddressPresentationTest {
    @Test fun compactPromptRetainsUnknownAndRemoteLocationWarnings() {
        assertTrue(swiggyAddressSuggestionPrompt(null).contains("Location isn't available"))
        assertTrue(swiggyAddressSuggestionPrompt(SwiggyLocationAssessment.NOT_MATCHED).contains("away from your location"))
        assertTrue(swiggyAddressSuggestionPrompt(SwiggyLocationAssessment.AREA_MATCH).contains("check the flat"))
    }
    @Test
    fun duplicateHomeAddressesKeepUsefulDetailsInsteadOfOnlyNumberedLabels() {
        val first = address(
            id = "home-one",
            shortLabel = "Home — Bengaluru",
            confirmationDetail = "Unit 12 in Maple Court, Orchard Lane 560047",
        )
        val second = address(
            id = "home-two",
            shortLabel = "Home — Bengaluru",
            confirmationDetail = "Flat 204, Block B in Maple Court, Orchard Lane 560041",
            label = "Flat 204, Block B, Maple Court, Orchard Lane, 7th Block, Bengaluru, 560041, India",
        )

        val firstCard = swiggyAddressPresentation(first)
        val secondCard = swiggyAddressPresentation(second)

        assertEquals("Home — Bengaluru", firstCard.title)
        assertEquals("Unit 12 in Maple Court, Orchard Lane", firstCard.detail)
        assertNotEquals(firstCard.detail, secondCard.detail)
        assertTrue(secondCard.detail.contains("Block B"))
        assertTrue(secondCard.detail.contains("Maple Court"))
        assertTrue(secondCard.detail.contains("7th Block"))
        assertTrue(swiggySpokenAddressSuggestion(second).startsWith("Suggested address Home"))
        assertTrue(!swiggySpokenAddressSuggestion(second).contains("You selected"))
    }

    @Test
    fun fallbackLabelIsCompactAndRemovesCountryPin() {
        val presentation = swiggyAddressPresentation(
            address(
                id = "fallback",
                shortLabel = "Saved address 3 — Bengaluru",
                confirmationDetail = null,
                label = "Unit 34, Orchard Lane, Bengaluru, 560041, India",
            )
        )

        assertEquals("Saved address 3 — Bengaluru", presentation.title)
        assertEquals("Unit 34, Orchard Lane, Bengaluru", presentation.detail)
        assertTrue(!presentation.detail.contains("560041"))
        assertTrue(!presentation.detail.contains("India", ignoreCase = true))
    }

    @Test
    fun blankConfirmationDetailFallsBackToFullLabel() {
        val presentation = swiggyAddressPresentation(
            address(
                id = "blank-detail",
                shortLabel = "Home — Bengaluru",
                confirmationDetail = "   ",
                label = "Unit 12, Maple Court, Orchard Lane, Bengaluru, 560047, India",
            )
        )

        assertEquals("Unit 12, Maple Court, Orchard Lane · Bengaluru", presentation.detail)
    }

    private fun address(
        id: String,
        shortLabel: String,
        confirmationDetail: String?,
        label: String = confirmationDetail ?: shortLabel,
    ) = SwiggyAddress(
        id = id,
        label = label,
        normalizedLabel = label,
        shortLabel = shortLabel,
        categoryLabel = shortLabel.substringBefore(" —"),
        confirmationDetail = confirmationDetail,
    )
}
