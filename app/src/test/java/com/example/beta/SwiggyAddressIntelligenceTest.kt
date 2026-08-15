package com.example.beta

import com.example.beta.SwiggyMcpClient.SwiggyAddress
import org.junit.Assert.assertEquals
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
        assertEquals("Near your current location", ranked.first().reason)
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
    }

    @Test
    fun genericCityMatchDoesNotOverrideStrongRecency() {
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
    }

    private fun address(id: String, label: String) = SwiggyAddress(
        id = id,
        label = label,
        normalizedLabel = label,
        shortLabel = label,
    )
}
