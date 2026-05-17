package com.example.beta.automation

enum class PreflightMode {
    PRODUCTION,
    MANUAL_TEST,
    EMULATOR_TEST
}

enum class PreflightCheck {
    ACCESSIBILITY_SERVICE,
    SCREEN_CAPTURE_PERMISSION,
    TARGET_APP_INSTALLED,
    TARGET_APP_LAUNCHABLE,
    LOGGED_IN_SESSION,
    CART_STATE,
    STOP_BEFORE_PAYMENT,
    LOCATION_SERVICES,
    LOCATION_PERMISSION,
    ADDRESS_DISTANCE
}

data class PreflightPolicy(
    val mode: PreflightMode,
    val requiredChecks: Set<PreflightCheck>,
    val skippedChecks: Set<PreflightCheck> = emptySet()
) {
    fun requires(check: PreflightCheck): Boolean = check in requiredChecks

    fun skips(check: PreflightCheck): Boolean = check in skippedChecks

    val locationDistanceChecksEnabled: Boolean
        get() = requires(PreflightCheck.LOCATION_SERVICES) &&
            requires(PreflightCheck.LOCATION_PERMISSION) &&
            requires(PreflightCheck.ADDRESS_DISTANCE)

    companion object {
        private val baseChecks = setOf(
            PreflightCheck.ACCESSIBILITY_SERVICE,
            PreflightCheck.SCREEN_CAPTURE_PERMISSION,
            PreflightCheck.TARGET_APP_INSTALLED,
            PreflightCheck.TARGET_APP_LAUNCHABLE,
            PreflightCheck.LOGGED_IN_SESSION,
            PreflightCheck.CART_STATE,
            PreflightCheck.STOP_BEFORE_PAYMENT
        )

        private val locationChecks = setOf(
            PreflightCheck.LOCATION_SERVICES,
            PreflightCheck.LOCATION_PERMISSION,
            PreflightCheck.ADDRESS_DISTANCE
        )

        fun forMode(mode: PreflightMode): PreflightPolicy {
            return when (mode) {
                PreflightMode.PRODUCTION -> PreflightPolicy(
                    mode = mode,
                    requiredChecks = baseChecks + locationChecks
                )

                PreflightMode.MANUAL_TEST,
                PreflightMode.EMULATOR_TEST -> PreflightPolicy(
                    mode = mode,
                    requiredChecks = baseChecks,
                    skippedChecks = locationChecks
                )
            }
        }
    }
}
