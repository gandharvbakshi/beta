package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class GroceryIntentPreferencesTest {
    // Mimic SharedPreferences: the in-process value can change before disk commit reports failure.
    private class Store {
        val authority = GroceryIntentPreferenceAuthority()
        var memory: Any? = null
        fun read(): String? = memory as String?
        fun selection() = authority.selection(::read) {
            GroceryIntentPreferences.resolveSelection(it, false, false, false, false)
        }
        fun write(value: String, finish: () -> Boolean = { true }) =
            authority.write(value, { memory = it; finish() }, ::read)
    }

    @Test fun failedEnableAndThrownRevokeHoldUntilExplicitVerifiedWrite() {
        val store = Store()
        assertEquals(GroceryIntentSelection.Disabled, store.selection())
        assertFalse(store.write("gemini") { false })
        assertEquals("gemini", store.memory)
        assertEquals(GroceryIntentSelection.Unavailable, store.selection())

        assertTrue(store.write("gemini"))
        val grant = store.selection() as GroceryIntentSelection.Enabled
        assertEquals(GroceryIntentContract.V1, grant.contract)
        assertFalse(store.write("off") { throw IllegalStateException("commit failed") })
        assertEquals("off", store.memory)
        assertEquals(GroceryIntentSelection.Unavailable, store.selection())
        assertTrue(store.write("off"))
        assertEquals(GroceryIntentSelection.Disabled, store.selection())
    }

    @Test fun regrantOfSameModeCannotReviveCapturedSelection() {
        val store = Store()
        assertTrue(store.write("gemini"))
        val captured = store.selection() as GroceryIntentSelection.Enabled
        assertEquals(captured, store.selection())
        assertTrue(store.write("off"))
        assertTrue(store.write("gemini"))
        val replacement = store.selection() as GroceryIntentSelection.Enabled
        assertEquals(captured.mode, replacement.mode)
        assertEquals(captured.contract, replacement.contract)
        assertFalse(captured == replacement)
        assertTrue(store.write("gemini"))
        assertFalse(replacement == store.selection())
    }

    @Test fun unreadableOrMalformedChoiceAndMismatchedReadbackRequireFreshWrite() {
        for (bad in listOf(42, "unrecognized")) {
            val store = Store()
            store.memory = bad
            assertEquals(GroceryIntentSelection.Unavailable, store.selection())
            store.memory = "gemini"
            assertEquals(GroceryIntentSelection.Unavailable, store.selection())
            assertFalse(store.write("off") { store.memory = "gemini"; true })
            assertEquals(GroceryIntentSelection.Unavailable, store.selection())
            assertTrue(store.write("gemini"))
            assertTrue(store.selection() is GroceryIntentSelection.Enabled)
        }
    }

    @Test fun contractSelectionIsExplicitAndContextIsOptIn() {
        assertEquals(GroceryIntentContract.V1, GroceryIntentPreferences.selectContract(false, false))
        assertEquals(GroceryIntentContract.V2, GroceryIntentPreferences.selectContract(true, false))
        assertEquals(GroceryIntentContract.CONTEXT, GroceryIntentPreferences.selectContract(false, true))
        assertEquals(GroceryIntentContract.INTERPRETED, GroceryIntentPreferences.selectContract(false, false, true))
        assertTrue(runCatching { GroceryIntentPreferences.selectContract(true, true) }.isFailure)
        assertTrue(runCatching { GroceryIntentPreferences.selectContract(true, false, true) }.isFailure)
        assertTrue(runCatching { GroceryIntentPreferences.selectContract(false, true, true) }.isFailure)
    }

    @Test fun unavailableInterpretedContractNeverSilentlySwitchesNextAttemptToLocalParsing() {
        assertTrue(!GroceryIntentContract.INTERPRETED.disablesProviderOnUnavailable())
        assertTrue(!GroceryIntentContract.CONTEXT.disablesProviderOnUnavailable())
        assertTrue(GroceryIntentContract.V1.disablesProviderOnUnavailable())
        assertTrue(GroceryIntentContract.V2.disablesProviderOnUnavailable())
    }

    @Test fun releaseOffersOffAndTheEvaluatedProviderOnly() {
        assertEquals(listOf(GroceryIntentPreferences.Mode.OFF, GroceryIntentPreferences.Mode.GEMINI), GroceryIntentPreferences.availableModes(debug = false))
    }

    @Test fun developmentRetainsAllProviderSwitches() {
        assertEquals(GroceryIntentPreferences.Mode.entries, GroceryIntentPreferences.availableModes(debug = true))
    }

    @Test fun oldPlainModesNeverAutoUpgradeAndReleaseGeminiIsAlwaysV1() {
        for (mode in GroceryIntentPreferences.Mode.entries.filter { it != GroceryIntentPreferences.Mode.OFF }) {
            assertEquals(GroceryIntentSelection.Enabled(mode, GroceryIntentContract.V1),
                GroceryIntentPreferences.resolveSelection(mode.storedValue, true, false, false, false))
            assertEquals(GroceryIntentSelection.Unavailable,
                GroceryIntentPreferences.resolveSelection(mode.storedValue, true, false, false, true))
        }
        assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentContract.V1),
            GroceryIntentPreferences.resolveSelection("gemini", false, true, false, false))
        assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentContract.V1),
            GroceryIntentPreferences.resolveSelection("gemini", false, false, false, true))
    }

    @Test fun debugLegacyModesHonorV2AndContextButIgnoreInterpretedCapability() {
        assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentContract.V2),
            GroceryIntentPreferences.resolveSelection("gemini", true, true, false, false))
        assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.OPENAI, GroceryIntentContract.CONTEXT),
            GroceryIntentPreferences.resolveSelection("openai", true, false, true, false))
    }

    @Test fun exactInterpretedReceiptRequiresDebugCapabilityAndConcreteProvider() {
        val receipt = GroceryIntentPreferences.encodeInterpretedDevelopmentChoice(GroceryIntentPreferences.Mode.OPENAI)
        assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.OPENAI, GroceryIntentContract.INTERPRETED),
            GroceryIntentPreferences.resolveSelection(receipt, true, false, false, true))
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection(receipt, false, false, false, true))
        val developmentReceipt = GroceryIntentPreferences.encodeInterpretedDevelopmentChoice(
            GroceryIntentPreferences.Mode.OPENAI,
        )
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection(developmentReceipt, true, false, false, true, true))
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection(receipt, true, false, false, false))
        for (invalid in listOf(
            receipt.replace("grocery-intent-interpreted-v2", "grocery-intent-v2"),
            receipt.replace("|openai|", "|auto|"), receipt.replace("|debug", "|release"),
            "$receipt|extra", receipt.replace("v1|", "V1|"), receipt.replace("|openai|", "|openai |"),
            receipt.replace("v1|", "v0|"), receipt.replace("|openai|", "|unknown|"),
            "v1|openai|grocery-intent-interpreted-v2|disclosure1|debug",
        )) {
            assertEquals(invalid, GroceryIntentSelection.Unavailable,
                GroceryIntentPreferences.resolveSelection(invalid, true, false, false, true))
        }
        assertTrue(runCatching { GroceryIntentPreferences.encodeInterpretedDevelopmentChoice(GroceryIntentPreferences.Mode.AUTO) }.isFailure)
        assertTrue(runCatching { GroceryIntentPreferences.encodeInterpretedDevelopmentChoice(GroceryIntentPreferences.Mode.OFF) }.isFailure)
    }

    @Test fun offAndMalformedValuesAreConservativeAndExclusivityStillApplies() {
        assertEquals(GroceryIntentSelection.Disabled, GroceryIntentPreferences.resolveSelection("off", true, false, false, false))
        assertEquals(GroceryIntentSelection.Disabled, GroceryIntentPreferences.resolveSelection(null, true, false, false, false))
        assertEquals(GroceryIntentSelection.Unavailable, GroceryIntentPreferences.resolveSelection(" GeminI ", true, false, false, false))
        assertTrue(runCatching { GroceryIntentPreferences.resolveSelection("unknown", true, true, true, false) }.isFailure)
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection("openai", false, false, false, true))
        assertTrue(runCatching { GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.OFF, GroceryIntentContract.V1) }.isFailure)
        assertTrue(runCatching { GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.AUTO, GroceryIntentContract.INTERPRETED) }.isFailure)
    }

    @Test fun replacingReceiptWithLegacyOrOffCannotReviveIt() {
        val receipt = GroceryIntentPreferences.encodeInterpretedDevelopmentChoice(GroceryIntentPreferences.Mode.GEMINI)
        assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentContract.INTERPRETED),
            GroceryIntentPreferences.resolveSelection(receipt, true, false, false, true))
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection("gemini", true, false, false, true))
        assertEquals(GroceryIntentSelection.Disabled,
            GroceryIntentPreferences.resolveSelection("off", true, false, false, true))
        assertFalse(GroceryIntentPreferences.Mode.OFF.providerOrNull() != null)
    }

    @Test fun everyNamedTesterGrantBindsToOneProviderAndNeverCrossesToPublic() {
        var diskValue: String? = null
        val authority = GroceryIntentPreferenceAuthority()
        fun current() = authority.selection({ diskValue }) {
            GroceryIntentPreferences.resolveSelection(it, true, false, false, true, false)
        }
        var previous: GroceryIntentSelection = current()
        assertEquals(GroceryIntentSelection.Disabled, previous)
        for (mode in listOf(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentPreferences.Mode.DEEPSEEK,
            GroceryIntentPreferences.Mode.OPENAI, GroceryIntentPreferences.Mode.GEMINI)) {
            val receipt = GroceryIntentPreferences.encodeInterpretedDevelopmentChoice(mode)
            assertTrue(authority.write(receipt, { diskValue = it; true }, { diskValue }))
            val selected = current() as GroceryIntentSelection.Enabled
            assertEquals(mode.providerOrNull(), selected.provider)
            assertEquals(GroceryIntentContract.INTERPRETED, selected.contract)
            assertFalse(selected == previous)
            for (debug in listOf(true, false)) {
                assertEquals(GroceryIntentSelection.Unavailable,
                    GroceryIntentPreferences.resolveSelection(receipt, debug, false, false, true, true))
            }
            previous = selected
        }
        assertTrue(authority.write("off", { diskValue = it; true }, { diskValue }))
        assertEquals(GroceryIntentSelection.Disabled, current())
    }

    @Test fun publicOpenAiReceiptRequiresExactNov7PublicConsentAndBothIndependentFlags() {
        val receipt = GroceryIntentPreferences.encodePublicOpenAiReceipt()
        val expected = GroceryIntentSelection.Enabled(
            GroceryIntentPreferences.Mode.OPENAI, GroceryIntentContract.INTERPRETED,
        )
        assertEquals("public", GroceryIntentPreferences.eligiblePublicOptionAudience(true, true))
        assertEquals(null, GroceryIntentPreferences.eligiblePublicOptionAudience(false, true))
        assertEquals(null, GroceryIntentPreferences.eligiblePublicOptionAudience(true, false))
        assertEquals(null, GroceryIntentPreferences.eligiblePublicOptionAudience(false, false))

        // A QA/debug build may exercise the actual public path only with this exact receipt and flag.
        assertEquals(expected, GroceryIntentPreferences.resolveSelection(receipt, true, false, false, true, true))
        assertEquals(expected, GroceryIntentPreferences.resolveSelection(receipt, false, false, false, true, true))
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection(receipt, true, false, false, true, false))
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection(receipt, false, false, false, false, true))
        assertEquals(GroceryIntentSelection.Unavailable,
            GroceryIntentPreferences.resolveSelection(receipt, false, false, false, true))

        for (invalid in listOf(
            "v1|openai|grocery-intent-interpreted-v2|disclosure1|public",
            "v1|openai|grocery-intent-interpreted-v2|disclosure-nov06-v1|public",
            "v1|gemini|grocery-intent-interpreted-v2|disclosure-nov07-v1|public",
            "v1|openai|grocery-intent-v2|disclosure-nov07-v1|public",
            "v1|openai|grocery-intent-interpreted-v2|disclosure-nov07-v1|debug",
        )) {
            assertEquals(invalid, GroceryIntentSelection.Unavailable,
                GroceryIntentPreferences.resolveSelection(invalid, false, false, false, true, true))
        }
    }

    @Test fun publicCapabilityNeverUpgradesRawOpenAiAndKeepsReleaseGeminiV1() {
        for (debug in listOf(false, true)) {
            for (mode in listOf("openai", "deepseek", "auto")) {
                assertEquals(GroceryIntentSelection.Unavailable,
                    GroceryIntentPreferences.resolveSelection(mode, debug, false, false, true, true))
            }
            assertEquals(GroceryIntentSelection.Enabled(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentContract.V1),
                GroceryIntentPreferences.resolveSelection("gemini", debug, false, false, true, true))
            assertEquals(GroceryIntentSelection.Disabled,
                GroceryIntentPreferences.resolveSelection("off", debug, false, false, true, true))
        }
    }
}
