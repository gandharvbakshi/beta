package com.example.beta

import android.app.Application
import android.util.Log

class MyApplication : Application() {
    lateinit var analyticsConsentManager: AnalyticsConsentManager
        private set

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        DebugLogger.logInfo("MyApplication", "Application started")
        analyticsConsentManager = AnalyticsConsentManager(this)
        BetaTelemetry(this, analyticsConsentManager).also { telemetry ->
            telemetry.init()
            BetaTelemetry.instance = telemetry
        }
        Log.i("BetaAgent", "SWIGGY_ONLY_APPLICATION_READY")
    }

    fun getRecentDebugLogs(lines: Int = 100): String = DebugLogger.readLastLogs(lines)
}
