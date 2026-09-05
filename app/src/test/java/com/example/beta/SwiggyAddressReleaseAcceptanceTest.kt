package com.example.beta

import com.example.beta.SwiggyMcpClient.SwiggyAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyAddressReleaseAcceptanceTest {
    private val now = 1_800_000_000_000L

    @Test
    fun duplicateHomeLabelsStayDistinctAndSpokenConfirmationStaysShort() {
        val homeOne = address(
            id = "home-1",
            label = "12/24, Example Avenue, Bengaluru, 560047",
            shortLabel = "Home — Bengaluru",
            confirmationDetail = "12/24 in Example Avenue, Bengaluru 560047",
        )
        val homeTwo = address(
            id = "home-2",
            label = "304, 2nd Block, Example apartments, Bengaluru, 560041",
            shortLabel = "Home — Bengaluru",
            confirmationDetail = "304, 2nd block in Example apartments, Bengaluru 560041",
        )

        assertEquals(
            listOf("Home — Bengaluru — saved address 1", "Home — Bengaluru — saved address 2"),
            swiggyAddressChoiceLabels(listOf(homeOne, homeTwo)),
        )
        assertTrue(swiggySpokenAddressConfirmation(homeOne).startsWith("You selected Home"))
        assertFalse(swiggySpokenAddressConfirmation(homeOne).contains("560047"))
        val spoken = swiggySpokenAddressConfirmation(homeTwo)
        assertTrue(spoken.startsWith("You selected Home"))
        assertTrue(spoken.contains("304"))
        assertTrue(spoken.contains("2nd block"))
        assertTrue(spoken.contains("Example apartments"))
        assertFalse(spoken.contains("560041"))
    }

    @Test
    fun currentCartAddressOutranksNearbyAndRecentlyUsedAlternatives() {
        val currentCart = address(
            id = "cart",
            label = "12/24, Example Avenue, Bengaluru, 560047",
            shortLabel = "Home — Bengaluru",
            hasCurrentCart = true,
        )
        val nearbyRecent = address(
            id = "recent",
            label = "304, Example apartments, 2nd Block, Bengaluru, 560041",
            shortLabel = "Work — Bengaluru",
        )

        val ranked = rankSwiggyAddresses(
            addresses = listOf(nearbyRecent, currentCart),
            usageByAddressId = mapOf(
                nearbyRecent.id to SwiggyAddressUsage(now - 60_000L, 4),
            ),
            locationHint = SwiggyLocationHint(postalCode = "560041"),
            nowMillis = now,
        )

        assertEquals("cart", ranked.first().address.id)
        assertEquals("Current cart address", ranked.first().reason)
        assertEquals(SwiggyLocationAssessment.NOT_MATCHED, ranked.first().locationAssessment)
        assertEquals("recent", ranked.last().address.id)
        assertEquals(SwiggyLocationAssessment.AREA_MATCH, ranked.last().locationAssessment)
        assertTrue(swiggyAddressLocationNotice(ranked.last().locationAssessment).contains("not an exact GPS distance"))
    }

    @Test
    fun missingGpsKeepsEverySavedAddressVisibleAndUnknown() {
        val first = address("one", "One Road, Bengaluru")
        val second = address("two", "Two Road, Bengaluru")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(first, second),
            usageByAddressId = mapOf(
                second.id to SwiggyAddressUsage(now - 60_000L, 3),
            ),
            locationHint = null,
            nowMillis = now,
        )

        assertEquals(listOf("two", "one"), ranked.map { it.address.id })
        assertTrue(ranked.all { it.locationAssessment == SwiggyLocationAssessment.UNKNOWN })
        assertTrue(swiggyAddressLocationNotice(SwiggyLocationAssessment.UNKNOWN).contains("still use a saved address"))
    }

    @Test
    fun futureUsageStillRanksRecentWithoutPretendingToKnowExactGpsIdentity() {
        val futureUsage = address("future", "304, 2nd Block, Example apartments, Bengaluru, 560041")
        val plain = address("plain", "Plain Road, Bengaluru")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(plain, futureUsage),
            usageByAddressId = mapOf(
                futureUsage.id to SwiggyAddressUsage(now + 90_000L, 1),
            ),
            locationHint = SwiggyLocationHint(postalCode = "560041"),
            nowMillis = now,
        )

        assertEquals("future", ranked.first().address.id)
        assertEquals("Same area · recently used", ranked.first().reason)
        assertEquals(SwiggyLocationAssessment.AREA_MATCH, ranked.first().locationAssessment)
        assertFalse(swiggyAddressChoiceLabel(futureUsage).contains("GPS", ignoreCase = true))
    }

    private fun address(
        id: String,
        label: String,
        shortLabel: String = label,
        hasCurrentCart: Boolean = false,
        confirmationDetail: String? = null,
    ) = SwiggyAddress(
        id = id,
        label = label,
        normalizedLabel = label,
        shortLabel = shortLabel,
        hasCurrentCart = hasCurrentCart,
        confirmationDetail = confirmationDetail,
    )
}
