package com.example.beta

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

class AnalyticsConsentManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        applyConsent(isAnalyticsAllowed())
    }

    fun isAnalyticsAllowed(): Boolean = preferences.getBoolean(KEY_ALLOWED, false)

    fun hasSeenChoice(): Boolean = preferences.getBoolean(KEY_SEEN, false)

    fun setAnalyticsAllowed(allowed: Boolean) {
        preferences.edit()
            .putBoolean(KEY_ALLOWED, allowed)
            .putBoolean(KEY_SEEN, true)
            .apply()
        applyConsent(allowed)
    }

    private fun applyConsent(allowed: Boolean) {
        val analytics = FirebaseAnalytics.getInstance(appContext)
        analytics.setConsent(
            mapOf(
                FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to if (allowed) {
                    FirebaseAnalytics.ConsentStatus.GRANTED
                } else {
                    FirebaseAnalytics.ConsentStatus.DENIED
                },
                FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
                FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
                FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED,
            )
        )
        analytics.setAnalyticsCollectionEnabled(allowed)
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(allowed)
            if (!allowed) deleteUnsentReports()
        }
    }

    private companion object {
        const val PREFERENCES = "analytics_consent"
        const val KEY_ALLOWED = "analytics_allowed"
        const val KEY_SEEN = "analytics_choice_seen"
    }
}
