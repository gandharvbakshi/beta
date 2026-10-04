package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryIntentPreferencesTest {
    @Test fun releaseOffersOffAndTheEvaluatedProviderOnly() {
        assertEquals(listOf(GroceryIntentPreferences.Mode.OFF, GroceryIntentPreferences.Mode.GEMINI), GroceryIntentPreferences.availableModes(debug = false))
    }

    @Test fun developmentRetainsAllProviderSwitches() {
        assertEquals(GroceryIntentPreferences.Mode.entries, GroceryIntentPreferences.availableModes(debug = true))
    }
}
