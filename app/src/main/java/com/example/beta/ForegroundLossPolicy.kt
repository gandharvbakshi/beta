package com.example.beta

internal enum class ForegroundLossAction {
    PAUSE,
    FAIL
}

internal object ForegroundLossPolicy {
    private const val BLINKIT_PACKAGE = "com.grofers.customerapp"
    private const val SWIGGY_MAIN_PACKAGE = "in.swiggy.android"
    private const val SWIGGY_INSTAMART_PACKAGE = "in.swiggy.android.instamart"
    private const val ZEPTO_PACKAGE = "com.zeptoconsumerapp"

    private val supportedCommercePackages = setOf(
        BLINKIT_PACKAGE,
        SWIGGY_MAIN_PACKAGE,
        SWIGGY_INSTAMART_PACKAGE,
        ZEPTO_PACKAGE
    )

    internal fun decide(
        backendAppName: String?,
        activePackage: String?,
        lastCapturedPackage: String?,
        hasPreviouslyObservedSupportedCommercePackage: Boolean
    ): ForegroundLossAction {
        if (!backendAppName.isNullOrBlank()) {
            return ForegroundLossAction.FAIL
        }

        val observedPackage = firstConcretePackage(activePackage, lastCapturedPackage)
            ?: return ForegroundLossAction.FAIL

        if (isSupportedCommercePackage(observedPackage)) {
            return ForegroundLossAction.FAIL
        }

        if (!hasPreviouslyObservedSupportedCommercePackage) {
            return ForegroundLossAction.FAIL
        }

        return ForegroundLossAction.PAUSE
    }

    private fun firstConcretePackage(vararg packageNames: String?): String? {
        return packageNames.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun isSupportedCommercePackage(packageName: String): Boolean {
        return packageName in supportedCommercePackages
    }
}
