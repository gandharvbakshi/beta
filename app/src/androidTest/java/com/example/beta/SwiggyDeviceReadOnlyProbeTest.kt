package com.example.beta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwiggyDeviceReadOnlyProbeTest {
    @Test
    fun recordOnlyDeviceTestPrerequisites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val data = JSONObject()
            .put("capturedAtMillis", System.currentTimeMillis())
            .put("microphoneMuted", audio.isMicrophoneMute)
            .put("microphonePermissionGranted", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("fontScale", context.resources.configuration.fontScale.toDouble())
        context.openFileOutput("swiggy-device-test-prerequisites.json", Context.MODE_PRIVATE).use {
            it.write(data.toString().toByteArray())
        }
    }

    @Test
    fun lateTtsInitializationCannotRestartAnEngineAfterShutdown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val tts = IndianEnglishTextToSpeech(instrumentation.targetContext)
            tts.shutdown()
            val init = IndianEnglishTextToSpeech::class.java.getDeclaredMethod(
                "onInitialised", Int::class.javaPrimitiveType, String::class.java,
            ).apply { isAccessible = true }
            init.invoke(tts, TextToSpeech.ERROR, "com.google.android.tts")
            init.invoke(tts, TextToSpeech.SUCCESS, "com.google.android.tts")
            tts.speak("Synthetic test prompt after shutdown")
            val engine = IndianEnglishTextToSpeech::class.java.getDeclaredField("engine").apply { isAccessible = true }
            val pending = IndianEnglishTextToSpeech::class.java.getDeclaredField("pendingMessage").apply { isAccessible = true }
            assertNull("A released owner must not start a fallback engine", engine.get(tts))
            assertNull("A released owner must not queue speech", pending.get(tts))
        }
    }
}
