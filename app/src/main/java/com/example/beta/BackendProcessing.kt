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

    fun uploadScreenshotAndProcess(context: Context, bitmap: Bitmap, filename: String) {
        Log.d("BackendProcessing", "uploadScreenshotAndProcess called with filename: $filename")
        currentBitmap = bitmap
        currentFilename = filename
    }

    fun processScreenshotWithInput(context: Context, bitmap: Bitmap, filename: String, inputText: String) {
        Log.d("BackendProcessing", "processScreenshotWithInput called with filename: $filename")
        
        // Log screenshot dimensions
        Log.d("BackendProcessing", "Screenshot dimensions - Width: ${bitmap.width}, Height: ${bitmap.height}")
        
        // Log screen metrics
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        Log.d("BackendProcessing", """
            Screen metrics:
            - Width: ${displayMetrics.widthPixels}
            - Height: ${displayMetrics.heightPixels}
            - Density: ${displayMetrics.density}
            - Scaled Density: ${displayMetrics.scaledDensity}
            - Density DPI: ${displayMetrics.densityDpi}
            - X DPI: ${displayMetrics.xdpi}
            - Y DPI: ${displayMetrics.ydpi}
        """.trimIndent())

        // ButtonHighlightService removed - not available in current version

        // AutomatedActionService removed - not available in current version

        val attemptUpload = {
            // Create a temporary file for the bitmap
            val tempFile = File(context.cacheDir, filename)
            Log.d("BackendProcessing", "Creating temporary file at: ${tempFile.absolutePath}")
            FileOutputStream(tempFile).use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            }
            Log.d("BackendProcessing", "Bitmap compressed and saved to temporary file")

            val fileBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody)
                .addFormDataPart("input_text", inputText ?: "")
                .build()

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
                            
                            // Log the entire JSON response
                            Log.d("JSONResponse", "Received JSON: $jsonString")
                            
                            // Extract and log app name
                            val appName = jsonObject.optString("app_name", "Unknown App")
                            Log.d("AppInfo", "Analyzing app: $appName")
                            
                            // Extract and log input text
                            val inputText = jsonObject.optString("input_text", "")
                            if (inputText.isNotEmpty()) {
                                Log.d("InputInfo", "User input: $inputText")
                            }
                            
                            // Extract and log image dimensions
                            val imageDimensions = jsonObject.optJSONObject("image_dimensions")
                            when (imageDimensions) {
                                null -> Log.d("ImageDimensions", "No image dimensions found in the response")
                                else -> {
                                    val width = imageDimensions.getInt("image_width")
                                    val height = imageDimensions.getInt("image_height")
                                    Log.d("ImageDimensions", "Width: $width, Height: $height")
                                    
                                    // Set screenshot dimensions in the highlight service
                                    // buttonHighlightService?.setScreenshotDimensions(width, height) - service removed
                                }
                            }
                            
                            // Extract and handle recommended button
                            val recommendedButton = jsonObject.optJSONObject("recommended_button")
                            when (recommendedButton) {
                                null -> {
                                    Log.d("Recommendation", "No button recommendation found")
                                    // buttonHighlightService?.clearHighlight() - service removed
                                }
                                else -> {
                                    val buttonName = recommendedButton.getString("button_name")
                                    val confidenceScore = recommendedButton.getDouble("confidence_score")
                                    val reason = recommendedButton.getString("reason")
                                    val boundingBox = recommendedButton.getJSONArray("bounding_box")
                                    
                                    // Create a Rect from the bounding box coordinates
                                    val rect = Rect(
                                        boundingBox.getInt(0),
                                        boundingBox.getInt(1),
                                        boundingBox.getInt(2),
                                        boundingBox.getInt(3)
                                    )
                                    
                                    // Log detailed bounding box information
                                    Log.d("BackendProcessing", """
                                        Button Recommendation Details:
                                        - Name: $buttonName
                                        - Confidence: $confidenceScore
                                        - Reason: $reason
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

                                    // Update the highlight
                                    // ButtonHighlightService functionality removed - not available in current version
                                    
                                    // Execute automated action based on the recommendation
                                    // AutomatedActionService functionality removed - not available in current version
                                }
                            }
                            
                            // Extract and log buttons information
                            val buttonsArray = jsonObject.optJSONArray("buttons")
                            when (buttonsArray) {
                                null -> Log.d("ButtonInfo", "No buttons found in the response")
                                else -> {
                                    for (i in 0 until buttonsArray.length()) {
                                        val button = buttonsArray.getJSONObject(i)
                                        val buttonName = button.getString("button_name")
                                        val boundingBox = button.getJSONArray("bounding_box")
                                        val actions = button.getJSONArray("actions")
                                        
                                        Log.d("ButtonInfo", """
                                            Button: $buttonName
                                            Bounding Box: [${boundingBox.getInt(0)}, ${boundingBox.getInt(1)}, ${boundingBox.getInt(2)}, ${boundingBox.getInt(3)}]
                                            Actions: ${actions.join(",")}
                                        """.trimIndent())
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