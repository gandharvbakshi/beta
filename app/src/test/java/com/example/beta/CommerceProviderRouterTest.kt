package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommerceProviderRouterTest {
    @Test
    fun swiggyIsTheOnlySupportedProvider() {
        assertEquals(
            listOf(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART),
            CommerceProviderRouter.supportedProviders(),
        )
    }

    @Test
    fun sanitizesSwiggyWordingWithoutChangingTheGroceryList() {
        assertEquals("order milk", CommerceProviderRouter.sanitizeOrderInstruction("order milk from Swiggy"))
        assertEquals("milk", CommerceProviderRouter.sanitizeOrderInstruction("use Instamart for milk"))
    }

    @Test
    fun onlyPureOpenCommandsLaunchTheInstalledSwiggyApp() {
        assertTrue(CommerceProviderRouter.isOpenCommerceAppInstruction("open swiggy"))
        assertFalse(CommerceProviderRouter.isOpenCommerceAppInstruction("order milk from swiggy"))
    }
}
