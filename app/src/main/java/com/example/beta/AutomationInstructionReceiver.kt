package com.example.beta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceSource
import com.example.beta.automation.PreferenceStore

class AutomationInstructionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == PreferenceStore.ACTION_SEED_PREFERENCE) {
            val token = intent.getStringExtra("token").orEmpty()
            val preferredPhrase = intent.getStringExtra("preferred_phrase").orEmpty()
            val confidence = intent.getFloatExtra("confidence", 1.0f)
            if (token.isNotBlank() && preferredPhrase.isNotBlank()) {
                PreferenceStore.upsert(
                    context,
                    Preference(
                        token = token,
                        preferredPhrase = preferredPhrase,
                        avoidPhrases = intent.getStringArrayExtra("avoid_phrases")?.toList().orEmpty(),
                        source = PreferenceSource.SEEDED,
                        confidence = confidence
                    )
                )
                Log.i("BetaAgent", "PREFERENCE_SEEDED token=\"$token\"")
            }
            return
        }
        if (intent?.action == PreferenceStore.ACTION_CLEAR_PREFERENCES) {
            PreferenceStore.forgetAll(context)
            Log.i("BetaAgent", "PREFERENCES_CLEARED")
            return
        }
        if (intent?.action != ScreenCaptureService.ACTION_SUBMIT_AUTOMATION_INSTRUCTION) {
            return
        }

        val instruction = intent.getStringExtra("instruction")?.trim().orEmpty()
        if (instruction.isBlank()) {
            Log.w("BetaAgent", "AUTOMATION_INSTRUCTION_EMPTY")
            return
        }

        val service = (context.applicationContext as? MyApplication)?.getScreenCaptureService()
        if (service == null) {
            Log.w("BetaAgent", "AUTOMATION_INSTRUCTION_NO_SCREEN_SERVICE: $instruction")
            return
        }

        Log.i("BetaAgent", "AUTOMATION_INSTRUCTION_RECEIVED: $instruction")
        if (intent.getBooleanExtra(CommerceAppLauncher.EXTRA_LAUNCH_PREFERRED_COMMERCE_APP, false)) {
            val launchResult = CommerceAppLauncher.launchPreferred(context, instruction)
            if (!launchResult.launched) {
                Toast.makeText(context, launchResult.message, Toast.LENGTH_LONG).show()
                Log.w("BetaAgent", "AUTOMATION_INSTRUCTION_NO_COMMERCE_APP: $instruction")
                return
            }
            Toast.makeText(context, launchResult.message, Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                service.submitAutomationInstruction(instruction)
            }, CommerceAppLauncher.LAUNCH_SETTLE_DELAY_MS)
            return
        }

        service.submitAutomationInstruction(instruction)
    }
}
