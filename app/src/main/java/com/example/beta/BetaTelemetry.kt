package com.example.beta

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.time.Instant
import java.time.ZoneOffset

class BetaTelemetry(
    private val context: Context,
    private val consentManager: AnalyticsConsentManager,
) {
    private val analytics get() = FirebaseAnalytics.getInstance(context)
    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun init() {
        if (!preferences.contains(KEY_FIRST_OPEN_AT_MS)) {
            preferences.edit().putLong(KEY_FIRST_OPEN_AT_MS, System.currentTimeMillis()).apply()
        }
        if (!consentManager.isAnalyticsAllowed()) {
            maybeMarkConsentDeferred()
        }
        if (consentManager.isAnalyticsAllowed()) configureProperties()
    }

    fun onAppResume(nowMs: Long = System.currentTimeMillis()) {
        maybeMarkConsentDeferred()
        if (!consentManager.isAnalyticsAllowed()) return
        configureProperties()
        maybeLogFirstOpen(nowMs)
        maybeLogPreviouslyCompletedActivation(nowMs)

        val lastOpenAt = preferences.getLong(KEY_LAST_APP_OPEN_AT_MS, 0L)
        if (nowMs - lastOpenAt >= APP_OPEN_DEBOUNCE_MS) {
            if (logEvent("app_open")) preferences.edit().putLong(KEY_LAST_APP_OPEN_AT_MS, nowMs).apply()
        }

        val utcDay = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
        if (preferences.getString(KEY_LAST_DAILY_ACTIVE_DAY, null) != utcDay) {
            if (logEvent("daily_active")) preferences.edit().putString(KEY_LAST_DAILY_ACTIVE_DAY, utcDay).apply()
        }
        maybeLogRetention(nowMs)
    }

    fun logEvent(name: String, params: Map<String, Any?> = emptyMap()): Boolean {
        if (!consentManager.isAnalyticsAllowed()) return false
        val validated = TelemetryPolicy.validated(name, params, BuildConfig.DEBUG)
        if (!TelemetryPolicy.isEventAllowed(name)) return false
        val bundle = Bundle()
        validated.forEach { (key, value) ->
            when (value) {
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Boolean -> bundle.putString(key, value.toString())
                is String -> bundle.putString(key, value)
            }
        }
        analytics.logEvent(name, bundle)
        return true
    }

    fun logOrderRequestSubmitted(source: String, instruction: String) {
        logEvent(
            "order_request_submitted",
            mapOf(
                "source" to source,
                "list_length_bucket" to when (instruction.length) {
                    in 0..40 -> "short"
                    in 41..120 -> "medium"
                    in 121..300 -> "long"
                    else -> "very_long"
                },
            ),
        )
    }

    fun logPermissionResult(permission: String, granted: Boolean) {
        logEvent(
            "permission_result",
            mapOf("permission" to permission, "outcome" to if (granted) "granted" else "denied"),
        )
    }

    fun maybeLogOnboardingCompleted() {
        if (preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)) return
        if (logEvent("onboarding_completed")) {
            preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        }
    }

    fun logCartUpdateVerified(itemCount: Int) {
        val nowMs = System.currentTimeMillis()
        rememberFirstVerifiedCart(nowMs, itemCount)
        logEvent(
            "cart_update_verified",
            buildCartEventParams(nowMs, itemCount),
        )
        val activationEventLogged = preferences.getBoolean(KEY_ACTIVATION_EVENT_LOGGED, false)
        val emitted = if (!activationEventLogged) {
            logEvent(
                "activation_completed",
                buildActivationEventParams(preferences.getLong(KEY_FIRST_VERIFIED_CART_AT_MS, nowMs), nowMs),
            )
        } else false
        preferences.edit()
            .putBoolean(KEY_ACTIVATION_COMPLETED, true)
            .putBoolean(
                KEY_ACTIVATION_EVENT_LOGGED,
                activationEventLogged || emitted,
            )
            .apply()
    }

    fun dueFeedbackMilestone(nowMs: Long = System.currentTimeMillis()): Int? {
        val firstOpenAt = preferences.getLong(KEY_FIRST_OPEN_AT_MS, nowMs)
        return dueFeedbackMilestoneDay(
            activationCompleted = preferences.getBoolean(KEY_ACTIVATION_COMPLETED, false),
            firstOpenAtMs = firstOpenAt,
            nowMs = nowMs,
            shownDays = setOf(1, 5).filterTo(mutableSetOf()) { day ->
                preferences.getBoolean("feedback_d${day}_shown", false)
            },
        )
    }

    fun markFeedbackMilestoneShown(day: Int) {
        if (day !in setOf(1, 5)) return
        preferences.edit().putBoolean("feedback_d${day}_shown", true).apply()
    }

    fun recordNonFatal(stage: String, throwable: Throwable) {
        if (!consentManager.isAnalyticsAllowed()) return
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("beta_stage", TelemetryPolicy.safeLabel(stage))
            recordException(throwable)
        }
    }

    private fun maybeLogFirstOpen(nowMs: Long) {
        if (preferences.getBoolean(KEY_FIRST_OPEN_LOGGED, false)) return
        val firstOpenAt = preferences.getLong(KEY_FIRST_OPEN_AT_MS, nowMs)
        val emitted = logEvent(
            "app_first_open",
            buildEventContext(firstOpenAt, nowMs) + mapOf(
                "consent_delayed" to (consentDelayed() || boundedDaysSince(firstOpenAt, nowMs) > 0),
            ),
        )
        if (!preferences.getBoolean(KEY_ONBOARDING_STARTED, false)) {
            if (logEvent("onboarding_started")) preferences.edit().putBoolean(KEY_ONBOARDING_STARTED, true).apply()
        }
        if (emitted) preferences.edit().putBoolean(KEY_FIRST_OPEN_LOGGED, true).apply()
    }

    private fun maybeLogRetention(nowMs: Long) {
        val firstOpenAt = preferences.getLong(KEY_FIRST_OPEN_AT_MS, nowMs)
        val logged = versionedRetentionFlags()
        val due = RetentionMilestonePolicy.dueEventNames(firstOpenAt, nowMs, logged)
        if (due.isEmpty()) return
        val params = buildEventContext(firstOpenAt, nowMs)
        val editor = preferences.edit()
        due.forEach { eventName ->
            if (logEvent(eventName, params)) {
                editor.putBoolean(RetentionMilestonePolicy.retentionFlagKey(eventName), true)
            }
        }
        editor.apply()
    }

    private fun maybeLogPreviouslyCompletedActivation(nowMs: Long) {
        if (!preferences.getBoolean(KEY_ACTIVATION_COMPLETED, false)) return
        if (preferences.getBoolean(KEY_ACTIVATION_EVENT_LOGGED, false)) return
        val activationAtMs = preferences.getLong(
            KEY_FIRST_VERIFIED_CART_AT_MS,
            nowMs,
        )
        if (logEvent("activation_completed", buildActivationEventParams(activationAtMs, nowMs))) {
            preferences.edit().putBoolean(KEY_ACTIVATION_EVENT_LOGGED, true).apply()
        }
    }

    private fun buildEventContext(firstOpenAtMs: Long, referenceMs: Long): Map<String, Any?> {
        return mapOf(
            "days_since_first_open" to boundedDaysSince(firstOpenAtMs, referenceMs),
            "consent_delayed" to consentDelayed(),
        )
    }

    private fun buildCartEventParams(nowMs: Long, itemCount: Int): Map<String, Any?> {
        val firstOpenAt = preferences.getLong(KEY_FIRST_OPEN_AT_MS, nowMs)
        return buildEventContext(firstOpenAt, nowMs) +
            mapOf("item_count" to itemCount.coerceAtLeast(0))
    }

    private fun buildActivationEventParams(activationAtMs: Long, referenceMs: Long): Map<String, Any?> {
        val activationTimestampKnown = preferences.getBoolean(
            KEY_FIRST_VERIFIED_CART_TIMESTAMP_KNOWN,
            preferences.contains(KEY_FIRST_VERIFIED_CART_AT_MS),
        )
        val itemCount = preferences.getInt(KEY_FIRST_VERIFIED_CART_ITEM_COUNT, 0)
        val activationAgeDays = if (activationTimestampKnown) {
            boundedDaysSince(activationAtMs, referenceMs)
        } else {
            0L
        }
        return mapOf(
            "activation_age_days" to activationAgeDays,
            "activation_timestamp_known" to activationTimestampKnown,
            "consent_delayed" to consentDelayed(),
        ) +
            mapOf("item_count" to itemCount.coerceAtLeast(0))
    }

    private fun rememberFirstVerifiedCart(nowMs: Long, itemCount: Int) {
        // An upgrade from the older release cannot reconstruct an earlier activation date.
        if (preferences.getBoolean(KEY_ACTIVATION_COMPLETED, false)) return
        if (!preferences.contains(KEY_FIRST_VERIFIED_CART_AT_MS)) {
            preferences.edit()
                .putLong(KEY_FIRST_VERIFIED_CART_AT_MS, nowMs)
                .putInt(KEY_FIRST_VERIFIED_CART_ITEM_COUNT, itemCount.coerceAtLeast(0))
                .putBoolean(KEY_FIRST_VERIFIED_CART_TIMESTAMP_KNOWN, true)
                .apply()
        }
    }

    private fun maybeMarkConsentDeferred() {
        if (!consentManager.hasSeenChoice()) return
        if (consentManager.isAnalyticsAllowed()) return
        if (preferences.getBoolean(KEY_CONSENT_DEFERRED_BY_DENIAL, false)) return
        if (!preferences.contains(KEY_FIRST_OPEN_AT_MS)) return
        preferences.edit().putBoolean(KEY_CONSENT_DEFERRED_BY_DENIAL, true).apply()
    }

    private fun consentDelayed(): Boolean {
        return preferences.getBoolean(KEY_CONSENT_DEFERRED_BY_DENIAL, false)
    }

    private fun versionedRetentionFlags(): Set<String> {
        return setOf("retention_d1", "retention_d5", "retention_d7", "retention_d28", "retention_w1")
            .filterTo(mutableSetOf()) { eventName ->
                preferences.getBoolean(RetentionMilestonePolicy.retentionFlagKey(eventName), false)
            }
            .mapTo(mutableSetOf()) { eventName ->
                RetentionMilestonePolicy.retentionFlagKey(eventName)
            }
    }

    private fun boundedDaysSince(firstOpenAtMs: Long, referenceMs: Long): Long {
        return RetentionMilestonePolicy.ageDays(firstOpenAtMs, referenceMs).coerceAtMost(36_500L)
    }

    private fun configureProperties() {
        analytics.setUserProperty("app_version_name", BuildConfig.VERSION_NAME)
        analytics.setUserProperty("app_version_code", BuildConfig.VERSION_CODE.toString())
        analytics.setUserProperty("commerce_provider", "swiggy_instamart")
        FirebaseCrashlytics.getInstance().setCustomKey("product_scope", "swiggy_mcp_only")
    }

    companion object {
        @JvmField
        var instance: BetaTelemetry? = null

        private const val PREFERENCES = "beta_telemetry"
        private const val KEY_FIRST_OPEN_AT_MS = "first_open_at_ms"
        private const val KEY_FIRST_OPEN_LOGGED = "first_open_logged"
        private const val KEY_LAST_APP_OPEN_AT_MS = "last_app_open_at_ms"
        private const val KEY_LAST_DAILY_ACTIVE_DAY = "last_daily_active_day"
        private const val KEY_CONSENT_DEFERRED_BY_DENIAL = "consent_deferred_by_denial"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_ONBOARDING_STARTED = "onboarding_started"
        private const val KEY_ACTIVATION_COMPLETED = "activation_completed"
        private const val KEY_ACTIVATION_EVENT_LOGGED = "activation_event_logged"
        private const val KEY_FIRST_VERIFIED_CART_AT_MS = "first_verified_cart_at_ms"
        private const val KEY_FIRST_VERIFIED_CART_ITEM_COUNT = "first_verified_cart_item_count"
        private const val KEY_FIRST_VERIFIED_CART_TIMESTAMP_KNOWN = "first_verified_cart_timestamp_known"
        private const val APP_OPEN_DEBOUNCE_MS = 60_000L
    }
}

internal fun dueFeedbackMilestoneDay(
    activationCompleted: Boolean,
    firstOpenAtMs: Long,
    nowMs: Long,
    shownDays: Set<Int> = emptySet(),
): Int? {
    if (!activationCompleted) return null
    val day = RetentionMilestonePolicy.ageDays(firstOpenAtMs, nowMs).toInt()
    if (day !in setOf(1, 5)) return null
    return day.takeUnless(shownDays::contains)
}

internal object TelemetryPolicy {
    private val allowedEvents = setOf(
        "app_first_open",
        "app_open",
        "daily_active",
        "onboarding_completed",
        "onboarding_started",
        "swiggy_connect_started",
        "swiggy_connect_completed",
        "swiggy_connect_failed",
        "order_request_submitted",
        "address_selected",
        "product_discovery_completed",
        "cart_review_viewed",
        "cart_apply_started",
        "cart_update_verified",
        "cart_update_failed",
        "activation_completed",
        "permission_result",
        "feedback_prompt_shown",
        "feedback_submitted",
        "consent_changed",
        "retention_w1",
        "retention_d1",
        "retention_d5",
        "retention_d7",
        "retention_d28",
    )
    private val allowedKeys = setOf(
        "source",
        "list_length_bucket",
        "reason",
        "category",
        "outcome",
        "permission",
        "consent_kind",
        "value",
        "item_count",
        "change_count",
        "starts_empty",
        "days_since_first_open",
        "activation_age_days",
        "activation_timestamp_known",
        "consent_delayed",
        "selection_reason",
    )
    private val forbiddenText = listOf(
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("\\+?\\d{10,}"),
        Regex("\\b\\d{6}\\b"),
    )
    private val allowedStringValues = mapOf(
        "source" to setOf("voice", "text"),
        "list_length_bucket" to setOf("short", "medium", "long", "very_long"),
        "reason" to setOf(
            "invalid_authorization_link",
            "reconnect_required",
            "backend_failure",
            "oauth_callback",
            "provider_unverified",
        ),
        "category" to setOf("verified_cart", "retention_1", "retention_5"),
        "outcome" to setOf("granted", "denied", "success", "failed"),
        "permission" to setOf("microphone", "location"),
        "consent_kind" to setOf("analytics"),
        "selection_reason" to setOf(
            "current_cart_address",
            "near_you_recently_used",
            "near_your_current_location",
            "same_area_recently_used",
            "same_area_as_your_location",
            "recently_used",
            "saved_address",
        ),
    )
    private val allowedBooleanKeys = setOf("starts_empty", "value", "consent_delayed", "activation_timestamp_known")
    private val allowedCountKeys = setOf("item_count", "change_count")
    private val allowedDayKeys = setOf("days_since_first_open", "activation_age_days")

    fun validated(name: String, params: Map<String, Any?>, strict: Boolean): Map<String, Any> {
        val problems = mutableListOf<String>()
        if (!isEventAllowed(name)) {
            if (strict) throw IllegalArgumentException("Unsafe telemetry: event")
            return emptyMap()
        }
        val output = linkedMapOf<String, Any>()
        params.forEach { (key, value) ->
            if (key !in allowedKeys || value == null) {
                if (key !in allowedKeys) problems += key
                return@forEach
            }
            val normalized = when {
                key in allowedStringValues -> {
                    val candidate = (value as? String)?.let(::safeLabel)
                    candidate?.takeIf { it in allowedStringValues[key].orEmpty() }
                }
                key in allowedBooleanKeys -> value.takeIf { it is Boolean }
                key in allowedCountKeys -> normalizedCount(value, 0, 200)
                key in allowedDayKeys -> normalizedCount(value, 0, 36_500)
                else -> null
            }
            if (normalized == null || forbiddenText.any { it.containsMatchIn(normalized.toString()) }) {
                problems += key
            } else {
                output[key] = normalized
            }
        }
        if (strict && problems.isNotEmpty()) {
            throw IllegalArgumentException("Unsafe telemetry: ${problems.distinct().joinToString()}")
        }
        return output
    }

    fun isEventAllowed(name: String): Boolean = name in allowedEvents

    fun safeLabel(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(40)
        .ifBlank { "unknown" }

    private fun normalizedCount(value: Any, min: Long, max: Long): Any? {
        val longValue = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return if (longValue in min..max) value else null
    }
}
