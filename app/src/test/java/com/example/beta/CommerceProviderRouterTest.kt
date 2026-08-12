package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class CommerceProviderRouterTest {
    @Before
    fun setUp() {
        SwiggyCartMutationGuard.resetForTests()
        CommerceProviderRouter.resetSession()
    }

    @After
    fun tearDown() {
        SwiggyCartMutationGuard.resetForTests()
    }

    @Test
    fun providerSelectionIsLockedDuringConfirmedSwiggyCartMutation() {
        SwiggyCartMutationGuard.begin()

        CommerceProviderRouter.selectProviderFromUi(CommerceProviderRouter.CommerceProvider.BLINKIT)
        CommerceProviderRouter.selectProviderFromInstruction("add milk on zepto")
        val launchDecision = CommerceProviderRouter.routeLaunch(
            "open blinkit",
            setOf(
                installedApp(
                    CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
                    "in.swiggy.android",
                ),
                installedApp(
                    CommerceProviderRouter.CommerceProvider.BLINKIT,
                    "com.grofers.customerapp",
                ),
            ),
        )

        assertEquals(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
            CommerceProviderRouter.currentSessionProvider(),
        )
        assertEquals(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
            launchDecision.selectedProvider,
        )

        SwiggyCartMutationGuard.end()
        CommerceProviderRouter.selectProviderFromUi(CommerceProviderRouter.CommerceProvider.BLINKIT)
        assertEquals(
            CommerceProviderRouter.CommerceProvider.BLINKIT,
            CommerceProviderRouter.currentSessionProvider(),
        )
    }

    private fun installedApp(
        provider: CommerceProviderRouter.CommerceProvider,
        packageName: String,
    ) = CommerceProviderRouter.InstalledCommerceApp(provider, packageName)

    @Test
    fun defaultSessionStartsOnSwiggyInstamart() {
        assertEquals(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
            CommerceProviderRouter.currentSessionProvider()
        )
        assertEquals(
            CommerceProviderRouter.PreferenceSource.DEFAULT,
            CommerceProviderRouter.currentSessionSelectionSource()
        )
    }

    @Test
    fun uiSelectionUpdatesSessionMemory() {
        CommerceProviderRouter.selectProviderFromUi(CommerceProviderRouter.CommerceProvider.BLINKIT)

        assertEquals(CommerceProviderRouter.CommerceProvider.BLINKIT, CommerceProviderRouter.currentSessionProvider())
        assertEquals(CommerceProviderRouter.PreferenceSource.UI, CommerceProviderRouter.currentSessionSelectionSource())
    }

    @Test
    fun explicitInstructionOverridesDefaultForTheSession() {
        val decision = CommerceProviderRouter.routeLaunch(
            instruction = "please open zepto from the app",
            installedApps = setOf(installedApp(CommerceProviderRouter.CommerceProvider.ZEPTO, "com.zeptoconsumerapp"))
        )

        assertEquals(CommerceProviderRouter.CommerceProvider.ZEPTO, decision.selectedProvider)
        assertEquals(CommerceProviderRouter.CommerceProvider.ZEPTO, CommerceProviderRouter.currentSessionProvider())
        assertEquals(CommerceProviderRouter.PreferenceSource.VOICE_OR_TEXT, CommerceProviderRouter.currentSessionSelectionSource())
        assertTrue(decision.launchable)
    }

    @Test
    fun explicitSelectionDoesNotSilentlyFallbackWhenUnavailable() {
        CommerceProviderRouter.selectProviderFromUi(CommerceProviderRouter.CommerceProvider.BLINKIT)

        val decision = CommerceProviderRouter.routeLaunch(
            instruction = null,
            installedApps = setOf(installedApp(CommerceProviderRouter.CommerceProvider.ZEPTO, "com.zeptoconsumerapp"))
        )

        assertEquals(CommerceProviderRouter.CommerceProvider.BLINKIT, decision.selectedProvider)
        assertFalse(decision.launchable)
        assertFalse(decision.fallbackUsed)
        assertEquals("Could not open Blinkit. Please open it manually and try again.", decision.message)
    }

    @Test
    fun untouchedDefaultFallsBackBlinkitThenZepto() {
        val blinkitDecision = CommerceProviderRouter.routeLaunch(
            instruction = null,
            installedApps = setOf(
                installedApp(CommerceProviderRouter.CommerceProvider.BLINKIT, "com.grofers.customerapp"),
                installedApp(CommerceProviderRouter.CommerceProvider.ZEPTO, "com.zeptoconsumerapp"),
            )
        )

        assertEquals(CommerceProviderRouter.CommerceProvider.BLINKIT, blinkitDecision.selectedProvider)
        assertTrue(blinkitDecision.launchable)
        assertTrue(blinkitDecision.fallbackUsed)
        assertEquals("Swiggy Instamart was unavailable. Opening Blinkit.", blinkitDecision.message)
        assertEquals(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART, CommerceProviderRouter.currentSessionProvider())
        assertEquals(CommerceProviderRouter.PreferenceSource.DEFAULT, CommerceProviderRouter.currentSessionSelectionSource())

        CommerceProviderRouter.resetSession()

        val zeptoDecision = CommerceProviderRouter.routeLaunch(
            instruction = null,
            installedApps = setOf(installedApp(CommerceProviderRouter.CommerceProvider.ZEPTO, "com.zeptoconsumerapp"))
        )

        assertEquals(CommerceProviderRouter.CommerceProvider.ZEPTO, zeptoDecision.selectedProvider)
        assertTrue(zeptoDecision.launchable)
        assertTrue(zeptoDecision.fallbackUsed)
        assertEquals("Swiggy Instamart was unavailable. Opening Zepto.", zeptoDecision.message)

        CommerceProviderRouter.resetSession()

        val noInstalledDecision = CommerceProviderRouter.routeLaunch(
            instruction = null,
            installedApps = emptySet()
        )

        assertEquals(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART, noInstalledDecision.selectedProvider)
        assertFalse(noInstalledDecision.launchable)
        assertEquals(
            "Swiggy Instamart is unavailable. Install Swiggy Instamart, Blinkit, or Zepto to use Beta grocery automation.",
            noInstalledDecision.message
        )
    }

    @Test
    fun aliasesAndOpenDetectionWork() {
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("open swiggy"))
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("use instamart"))
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("launch blinkit app"))
        assertFalse(CommerceProviderRouter.isOpenCommerceAppInstruction("from grofers"))
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("open grocery app"))
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("switch to zepto"))
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("please open the swiggy app now"))
        assertFalse(CommerceProviderRouter.isOpenCommerceAppInstruction("order milk from swiggy"))
        assertFalse(CommerceProviderRouter.isOpenCommerceAppInstruction("use swiggy for milk"))
        assertFalse(CommerceProviderRouter.isOpenCommerceAppInstruction("search for milk"))

        val decision = CommerceProviderRouter.routeLaunch(
            instruction = "open grofers",
            installedApps = setOf(installedApp(CommerceProviderRouter.CommerceProvider.BLINKIT, "com.grofers.customerapp"))
        )
        assertEquals(CommerceProviderRouter.CommerceProvider.BLINKIT, decision.selectedProvider)
        assertTrue(decision.launchable)
    }

    @Test
    fun sanitizesTrailingProviderQualifiersFromOrderPhrases() {
        assertEquals("order milk", CommerceProviderRouter.sanitizeOrderInstruction("order milk from swiggy"))
        assertEquals("buy onions", CommerceProviderRouter.sanitizeOrderInstruction("buy onions on blinkit"))
        assertEquals("need bread", CommerceProviderRouter.sanitizeOrderInstruction("need bread via zepto"))
        assertEquals("milk", CommerceProviderRouter.sanitizeOrderInstruction("use swiggy for milk"))
        assertEquals("milk", CommerceProviderRouter.sanitizeOrderInstruction("please use Swiggy for milk"))
        assertEquals("order milk", CommerceProviderRouter.sanitizeOrderInstruction("order milk from Swiggy please"))
        assertEquals("Order Amul A2 Milk", CommerceProviderRouter.sanitizeOrderInstruction("Order Amul A2 Milk from Swiggy"))
        assertEquals("open swiggy", CommerceProviderRouter.sanitizeOrderInstruction("open swiggy"))
    }

    @Test
    fun swiggyMainPackageWinsOverLegacyWhenBothAreInstalled() {
        val decision = CommerceProviderRouter.routeLaunch(
            instruction = null,
            installedApps = setOf(
                installedApp(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART, "in.swiggy.android.instamart"),
                installedApp(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART, "in.swiggy.android"),
            )
        )

        assertEquals(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART, decision.selectedProvider)
        assertEquals("in.swiggy.android", decision.packageName)
        assertTrue(decision.launchable)
    }

    @Test
    fun resetReturnsSessionToDefaultSwiggy() {
        CommerceProviderRouter.selectProviderFromUi(CommerceProviderRouter.CommerceProvider.ZEPTO)
        CommerceProviderRouter.resetSession()

        assertEquals(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART, CommerceProviderRouter.currentSessionProvider())
        assertEquals(CommerceProviderRouter.PreferenceSource.DEFAULT, CommerceProviderRouter.currentSessionSelectionSource())
    }
}
