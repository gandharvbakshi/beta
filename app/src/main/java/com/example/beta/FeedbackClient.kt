package com.example.beta

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object FeedbackClient {
    private const val TAG = "BetaFeedback"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val client = provideClient()

    private fun provideClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        return builder.build()
    }

    fun submit(
        context: Context,
        rating: String,
        category: String,
        message: String,
        includeLogs: Boolean,
        callback: (Boolean, String) -> Unit
    ) {
        val logs = if (includeLogs) {
            (context.applicationContext as? MyApplication)?.getRecentDebugLogs(80).orEmpty()
        } else {
            ""
        }
        val payload = FeedbackPayload.build(
            context = context,
            rating = rating,
            category = category,
            message = message,
            includeLogs = includeLogs,
            logs = logs
        )
        submitPayload(payload, callback)
    }

    internal fun submitPayload(payload: JSONObject, callback: (Boolean, String) -> Unit) {
        val feedbackUrl = try {
            AppConfig.feedbackUrl
        } catch (_: BackendConfigurationUnavailableException) {
            callback(false, "backend_configuration_unavailable")
            return
        }
        val request = Request.Builder()
            .url(feedbackUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .apply {
                val feedbackKey = BuildConfig.BETA_FEEDBACK_API_KEY.trim()
                if (feedbackKey.isNotEmpty()) {
                    header("x-beta-feedback-key", feedbackKey)
                }
            }
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Feedback submit failed: ${e.message}")
                callback(false, e.message ?: "network_error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        val feedbackId = runCatching {
                            JSONObject(body).optString("feedback_id", "accepted")
                        }.getOrDefault("accepted")
                        Log.i(TAG, "Feedback accepted: $feedbackId")
                        callback(true, feedbackId)
                    } else {
                        Log.w(TAG, "Feedback rejected: ${it.code} $body")
                        callback(false, "http_${it.code}")
                    }
                }
            }
        })
    }
}
