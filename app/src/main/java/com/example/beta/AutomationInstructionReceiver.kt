package com.example.beta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
        if (intent?.action != ACTION_SUBMIT_AUTOMATION_INSTRUCTION) {
            return
        }

        val instruction = intent.getStringExtra("instruction")?.trim().orEmpty()
        if (instruction.isBlank()) {
            Log.w("BetaAgent", "AUTOMATION_INSTRUCTION_EMPTY")
            return
        }
        Log.i("BetaAgent", "AUTOMATION_INSTRUCTION_RECEIVED characters=${instruction.length}")
        if (CommerceProviderRouter.isOpenCommerceAppInstruction(instruction)) {
            CommerceAppLauncher.launchPreferred(context, instruction)
            return
        }
        val handoffToken = SwiggyOrderHandoff.issue(instruction)
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(SwiggyOrderHandoff.EXTRA_TOKEN, handoffToken)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }.onFailure { error ->
            SwiggyOrderHandoff.consume(handoffToken)
            Log.e("BetaAgent", "SWIGGY_MCP_RECEIVER_ROUTE_FAILED: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val ACTION_SUBMIT_AUTOMATION_INSTRUCTION =
            "live.betaapp.android.SUBMIT_AUTOMATION_INSTRUCTION"
    }
}
