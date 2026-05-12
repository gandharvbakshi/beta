package com.example.beta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutomationInstructionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
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
        service.submitAutomationInstruction(instruction)
    }
}
