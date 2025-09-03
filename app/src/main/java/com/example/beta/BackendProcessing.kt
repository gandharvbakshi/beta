package com.example.beta

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.json.JSONArray
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object BackendProcessing {
    private val client = provideOkHttpClient()
    var currentBitmap: Bitmap? = null
    var currentFilename: String? = null
    private var currentInputText: String? = null
    private var currentAppName: String? = null
    private var currentTreeData: String? = null
    
    // Services removed - not available in current version

    private fun provideOkHttpClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(object : HostnameVerifier {
                override fun verify(hostname: String, session: SSLSession): Boolean {
                    return true // Disable hostname verification for development
                }
            })
            .connectTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun uploadScreenshotAndProcess(context: Context, bitmap: Bitmap, filename: String, appName: String? = null, treeData: String? = null) {
        Log.d("BackendProcessing", "uploadScreenshotAndProcess called with filename: $filename")
        currentBitmap = bitmap
        currentFilename = filename
        currentAppName = appName
        currentTreeData = treeData
    }

    fun processScreenshotWithInput(context: Context, bitmap: Bitmap, filename: String, inputText: String, appName: String? = null, treeData: String? = null) {
        Log.d("BackendProcessing", "processScreenshotWithInput called with filename: $filename")
        
        // Store the additional data
        currentAppName = appName
        currentTreeData = treeData
        
        // Log screenshot dimensions
        Log.d("BackendProcessing", "Screenshot dimensions - Width: ${bitmap.width}, Height: ${bitmap.height}")

        // ButtonHighlightService removed - not available in current version

        // AutomatedActionService removed - not available in current version

        val attemptUpload = {
            // Create a temporary file for the bitmap
            val tempFile = File(context.cacheDir, filename)
            FileOutputStream(tempFile).use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            }

            val fileBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody)
                .addFormDataPart("input_text", inputText ?: "")
            
            // Add app name if available
            if (!currentAppName.isNullOrEmpty()) {
                requestBodyBuilder.addFormDataPart("app_name", currentAppName!!)
                Log.d("BackendProcessing", "Including app name: $currentAppName")
            }
            
            // Add tree data if available
            if (!currentTreeData.isNullOrEmpty()) {
                requestBodyBuilder.addFormDataPart("detailed_tree_data", currentTreeData!!)
                Log.d("BackendProcessing", "Including detailed tree data (length: ${currentTreeData!!.length})")
            }
            
            val requestBody = requestBodyBuilder.build()

            val request = Request.Builder()
                .url("https://10.0.2.2:8000/analyze-screenshot")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    Log.e("UploadFailure", "Failed to upload image: ${e.message}")
                    // buttonHighlightService?.clearHighlight() - service removed
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.e("UploadResponse", "Unexpected response: $response")
                        // buttonHighlightService?.clearHighlight() - service removed
                        return
                    }

                    response.body?.let { responseBody ->
                        try {
                            val jsonString = responseBody.string()
                            val jsonObject = JSONObject(jsonString)
                            
                            // Extract and log app name
                            val appName = jsonObject.optString("app_name", "Unknown App")
                            Log.d("BackendProcessing", "Processing response for app: $appName")
                            
                            // Extract and log input text
                            val inputText = jsonObject.optString("input_text", "")
                            
                            // Extract and log image dimensions
                            val imageDimensions = jsonObject.optJSONObject("image_dimensions")
                            when (imageDimensions) {
                                null -> { /* No image dimensions in response */ }
                                else -> {
                                    val width = imageDimensions.getInt("image_width")
                                    val height = imageDimensions.getInt("image_height")
                                    Log.d("BackendProcessing", "Image dimensions: ${width}x${height}")
                                }
                            }
                            
                            // Extract and handle recommended action (new format)
                            val recommendedAction = jsonObject.optJSONObject("recommended_action")
                            when (recommendedAction) {
                                null -> {
                                    Log.d("Recommendation", "No action recommendation found")
                                    // buttonHighlightService?.clearHighlight() - service removed
                                }
                                else -> {
                                    val actionType = recommendedAction.getString("action_type")
                                    val actionTarget = recommendedAction.getString("action_target")
                                    val confidenceScore = recommendedAction.getDouble("confidence_score")
                                    val reasoning = recommendedAction.getString("reasoning")
                                    val boundingBox = recommendedAction.optJSONArray("bounding_box")
                                    
                                    Log.d("BackendProcessing", """
                                        Action Recommendation Details:
                                        - Action Type: $actionType
                                        - Action Target: $actionTarget
                                        - Confidence: $confidenceScore
                                        - Reasoning: $reasoning
                                    """.trimIndent())
                                    
                                    // Log bounding box if available
                                    if (boundingBox != null) {
                                        val rect = Rect(
                                            boundingBox.getInt(0),
                                            boundingBox.getInt(1),
                                            boundingBox.getInt(2),
                                            boundingBox.getInt(3)
                                        )
                                        
                                                                                 // Get display metrics for screen information
                                         val displayMetrics = DisplayMetrics()
                                         val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                             val windowMetrics = windowManager.currentWindowMetrics
                                             val bounds = windowMetrics.bounds
                                             displayMetrics.widthPixels = bounds.width()
                                             displayMetrics.heightPixels = bounds.height()
                                             displayMetrics.density = context.resources.displayMetrics.density
                                         } else {
                                             @Suppress("DEPRECATION")
                                             windowManager.defaultDisplay.getMetrics(displayMetrics)
                                         }
                                        
                                        Log.d("BackendProcessing", """
                                            - Raw Bounding Box: [${boundingBox.getInt(0)}, ${boundingBox.getInt(1)}, ${boundingBox.getInt(2)}, ${boundingBox.getInt(3)}]
                                            - Box Width: ${rect.width()}
                                            - Box Height: ${rect.height()}
                                            - Box Center: (${rect.centerX()}, ${rect.centerY()})
                                            - Screenshot Width: ${bitmap.width}
                                            - Screenshot Height: ${bitmap.height}
                                            - Screen Width: ${displayMetrics.widthPixels}
                                            - Screen Height: ${displayMetrics.heightPixels}
                                            - Screen Density: ${displayMetrics.density}
                                        """.trimIndent())
                                    }

                                    // Update the highlight
                                    // ButtonHighlightService functionality removed - not available in current version
                                    
                                    // Execute automated action based on the recommendation
                                    // AutomatedActionService functionality removed - not available in current version
                                }
                            }
                            
                            // Extract and log buttons information
                            val buttonsArray = jsonObject.optJSONArray("buttons")
                            when (buttonsArray) {
                                null -> { /* No buttons in response */ }
                                else -> {
                                    Log.d("BackendProcessing", "Found ${buttonsArray.length()} buttons in response")
                                    for (i in 0 until buttonsArray.length()) {
                                        val button = buttonsArray.getJSONObject(i)
                                        val buttonName = button.getString("button_name")
                                        Log.d("BackendProcessing", "Button: $buttonName")
                                    }
                                }
                            }
                            
                        } catch (e: Exception) {
                            Log.e("JSONParsing", "Error parsing JSON response: ${e.message}")
                            // buttonHighlightService?.clearHighlight() - service removed
                        }
                    }
                }
            })
        }

        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                attemptUpload()
                break
            } catch (e: SocketTimeoutException) {
                if (attempt == maxAttempts) {
                    Log.e("UploadFailure", "Failed after multiple attempts: ${e.message}")
                } else {
                    Log.w("UploadRetry", "Attempt $attempt failed, retrying...")
                }
            }
        }
    }
}