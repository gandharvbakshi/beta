package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundLossPolicyTest {
    @Test
    fun pausesWhenBlankBackendAndActivePackageShowsNonCommerceAfterSupportedCommerceWasSeen() {
        val decision = ForegroundLossPolicy.decide(
            backendAppName = "",
            activePackage = "com.spotify.music",
            lastCapturedPackage = null,
            hasPreviouslyObservedSupportedCommercePackage = true
        )

        assertEquals(ForegroundLossAction.PAUSE, decision)
    }

    @Test
    fun pausesWhenBlankBackendAndLastCapturedPackageShowsNonCommerceAfterSupportedCommerceWasSeen() {
        val decision = ForegroundLossPolicy.decide(
            backendAppName = null,
            activePackage = "",
            lastCapturedPackage = "com.instagram.android",
            hasPreviouslyObservedSupportedCommercePackage = true
        )

        assertEquals(ForegroundLossAction.PAUSE, decision)
    }

    @Test
    fun failsWhenThereIsNoConcreteForegroundEvidence() {
        val decision = ForegroundLossPolicy.decide(
            backendAppName = "",
            activePackage = null,
            lastCapturedPackage = "",
            hasPreviouslyObservedSupportedCommercePackage = true
        )

        assertEquals(ForegroundLossAction.FAIL, decision)
    }

    @Test
    fun failsWhenObservedPackageIsStillSupportedCommerce() {
        val decision = ForegroundLossPolicy.decide(
            backendAppName = "",
            activePackage = "com.grofers.customerapp",
            lastCapturedPackage = "com.instagram.android",
            hasPreviouslyObservedSupportedCommercePackage = true
        )

        assertEquals(ForegroundLossAction.FAIL, decision)
    }

    @Test
    fun failsWhenSupportedCommerceWasNeverObservedBeforeTheNonCommercePackage() {
        val decision = ForegroundLossPolicy.decide(
            backendAppName = "",
            activePackage = "com.spotify.music",
            lastCapturedPackage = null,
            hasPreviouslyObservedSupportedCommercePackage = false
        )

        assertEquals(ForegroundLossAction.FAIL, decision)
    }
}
