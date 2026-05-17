package com.example.beta.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightPolicyTest {
    @Test
    fun productionRequiresLocationDistanceChecks() {
        val policy = PreflightPolicy.forMode(PreflightMode.PRODUCTION)

        assertTrue(policy.locationDistanceChecksEnabled)
        assertTrue(policy.requires(PreflightCheck.LOCATION_SERVICES))
        assertTrue(policy.requires(PreflightCheck.LOCATION_PERMISSION))
        assertTrue(policy.requires(PreflightCheck.ADDRESS_DISTANCE))
    }

    @Test
    fun emulatorTestSkipsLocationDistanceChecks() {
        val policy = PreflightPolicy.forMode(PreflightMode.EMULATOR_TEST)

        assertFalse(policy.locationDistanceChecksEnabled)
        assertTrue(policy.skips(PreflightCheck.LOCATION_SERVICES))
        assertTrue(policy.skips(PreflightCheck.LOCATION_PERMISSION))
        assertTrue(policy.skips(PreflightCheck.ADDRESS_DISTANCE))
    }

    @Test
    fun manualTestKeepsCoreOrderSafetyChecks() {
        val policy = PreflightPolicy.forMode(PreflightMode.MANUAL_TEST)

        assertTrue(policy.requires(PreflightCheck.ACCESSIBILITY_SERVICE))
        assertTrue(policy.requires(PreflightCheck.SCREEN_CAPTURE_PERMISSION))
        assertTrue(policy.requires(PreflightCheck.TARGET_APP_INSTALLED))
        assertTrue(policy.requires(PreflightCheck.STOP_BEFORE_PAYMENT))
        assertFalse(policy.locationDistanceChecksEnabled)
    }
}
