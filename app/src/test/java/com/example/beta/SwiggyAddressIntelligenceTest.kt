package com.example.beta

import com.example.beta.SwiggyMcpClient.SwiggyAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyAddressIntelligenceTest {
    private val now = 1_800_000_000_000L

    @Test
    fun strongLocationMatchMovesNearbyAddressFirstWithoutAutoSelectingIt() {
        val oldHome = address("home", "8/18, Lynwood Avenue, Bengaluru, 560047")
        val nearbyWork = address("work", "602, Jains Prakrithi, 4th Block, Bengaluru, 560041")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(oldHome, nearbyWork),
            usageByAddressId = mapOf(
                oldHome.id to SwiggyAddressUsage(now - 60_000L, 5),
            ),
            locationHint = SwiggyLocationHint(postalCode = "560041"),
            nowMillis = now,
        )

        assertEquals("work", ranked.first().address.id)
        assertEquals("Same area as your location", ranked.first().reason)
        assertEquals(SwiggyLocationAssessment.AREA_MATCH, ranked.first().locationAssessment)
    }

    @Test
    fun recentlySelectedAddressIsFirstWhenGpsIsUnavailable() {
        val providerFirst = address("one", "One Road, Bengaluru")
        val recent = address("two", "Two Road, Bengaluru")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(providerFirst, recent),
            usageByAddressId = mapOf(
                recent.id to SwiggyAddressUsage(now - 60_000L, 2),
            ),
            locationHint = null,
            nowMillis = now,
        )

        assertEquals("two", ranked.first().address.id)
        assertEquals("Recently used", ranked.first().reason)
        assertEquals(SwiggyLocationAssessment.UNKNOWN, ranked.first().locationAssessment)
    }

    @Test
    fun cityOnlyHintDoesNotClaimAreaMatch() {
        val recent = address("recent", "Recent Road, Bengaluru")
        val cityOnly = address("city", "Other Road, Bengaluru")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(cityOnly, recent),
            usageByAddressId = mapOf(
                recent.id to SwiggyAddressUsage(now - 60_000L, 1),
            ),
            locationHint = SwiggyLocationHint(locality = "Bengaluru"),
            nowMillis = now,
        )

        assertEquals("recent", ranked.first().address.id)
        assertEquals("Recently used", ranked.first().reason)
        assertEquals(SwiggyLocationAssessment.UNKNOWN, ranked.first().locationAssessment)
    }

    @Test
    fun recentSwiggyHistoryCanRankAnAddressBeforeBetaHasLocalUsage() {
        val first = address("first", "First Road")
        val recentOrderAddress = address("recent-order", "Recent Order Road")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(first, recentOrderAddress),
            usageByAddressId = emptyMap(),
            locationHint = null,
            recentlyUsedAddressIds = listOf(recentOrderAddress.id),
            nowMillis = now,
        )

        assertEquals("recent-order", ranked.first().address.id)
        assertTrue(ranked.first().reason.orEmpty().contains("Recently used"))
        assertEquals(SwiggyLocationAssessment.UNKNOWN, ranked.first().locationAssessment)
    }

    @Test
    fun postalCodeMismatchMarksNoAreaMatch() {
        val home = address("home", "8/18, Lynwood Avenue, Bengaluru, 560047")

        val ranked = rankSwiggyAddresses(
            addresses = listOf(home),
            usageByAddressId = emptyMap(),
            locationHint = SwiggyLocationHint(postalCode = "560041"),
            nowMillis = now,
        )

        assertEquals("home", ranked.first().address.id)
        assertEquals(SwiggyLocationAssessment.NOT_MATCHED, ranked.first().locationAssessment)
        assertEquals(null, ranked.first().reason)
    }

    @Test
    fun currentCartAddressRanksAheadOfGpsAndRecencyWithAnExplicitReason() {
        val strongGpsRecent = address("home", "602, Jains Prakrithi, 4th Block, Bengaluru, 560041")
        val cartAddress = address(
            id = "cart",
            label = "8/18, Lynwood Avenue, Bengaluru, 560047",
            hasCurrentCart = true,
        )

        val ranked = rankSwiggyAddresses(
            addresses = listOf(strongGpsRecent, cartAddress),
            usageByAddressId = mapOf(
                strongGpsRecent.id to SwiggyAddressUsage(now - 60_000L, 5),
            ),
            locationHint = SwiggyLocationHint(postalCode = "560041"),
            nowMillis = now,
        )

        assertEquals("cart", ranked.first().address.id)
        assertEquals("Current cart address", ranked.first().reason)
        assertEquals(SwiggyLocationAssessment.NOT_MATCHED, ranked.first().locationAssessment)
    }

    @Test
    fun locationUsabilityRejectsStaleFutureAndInaccurateFixes() {
        assertTrue(isSwiggyLocationUsable(0L, 1f))
        assertTrue(isSwiggyLocationUsable(300_000L, 2_000f))
        assertFalse(isSwiggyLocationUsable(300_001L, 1f))
        assertFalse(isSwiggyLocationUsable(-1L, 1f))
        assertFalse(isSwiggyLocationUsable(1_000L, 0f))
        assertFalse(isSwiggyLocationUsable(1_000L, Float.NaN))
    }

    private fun address(id: String, label: String, hasCurrentCart: Boolean = false) = SwiggyAddress(
        id = id,
        label = label,
        normalizedLabel = label,
        shortLabel = label,
        hasCurrentCart = hasCurrentCart,
    )
}
