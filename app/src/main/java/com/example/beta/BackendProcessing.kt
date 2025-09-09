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
    private var actionExecutor: ActionExecutor? = null
    
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

    fun processScreenshotWithInput(context: Context, bitmap: Bitmap, filename: String, inputText: String, appName: String? = null, treeData: String? = null, accessibilityService: MyAccessibilityService? = null) {
        Log.d("BackendProcessing", "processScreenshotWithInput called with filename: $filename")
        
        // Store the additional data
        currentAppName = appName
        currentTreeData = treeData
        
        // Initialize action executor if accessibility service is available
        if (accessibilityService != null && actionExecutor == null) {
            actionExecutor = ActionExecutor(accessibilityService)
            Log.d("BackendProcessing", "ActionExecutor initialized")
        }
        
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
                            
                            // Extract and handle recommended action (enhanced format)
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
                                    
                                    // Get bounding box (object format)
                                    val boundingBox = recommendedAction.optJSONObject("bounding_box")
                                    val x = boundingBox?.optInt("x", 0) ?: 0
                                    val y = boundingBox?.optInt("y", 0) ?: 0
                                    val width = boundingBox?.optInt("width", 0) ?: 0
                                    val height = boundingBox?.optInt("height", 0) ?: 0
                                    
                                    // Get element selector
                                    val elementSelector = recommendedAction.optJSONObject("element_selector")
                                    val text = elementSelector?.optString("text", "") ?: ""
                                    val resourceId = elementSelector?.optString("resource_id", "") ?: ""
                                    val className = elementSelector?.optString("class_name", "") ?: ""
                                    val contentDescription = elementSelector?.optString("content_description", "") ?: ""
                                    val hierarchyPath = elementSelector?.optString("hierarchy_path", "") ?: ""
                                    
                                    // Get fallback coordinates
                                    val fallbackCoordinates = recommendedAction.optJSONObject("fallback_coordinates")
                                    val fallbackX = fallbackCoordinates?.optInt("x", 0) ?: 0
                                    val fallbackY = fallbackCoordinates?.optInt("y", 0) ?: 0
                                    
                                    Log.d("BackendProcessing", "=== RECOMMENDED ACTION DETAILS ===")
                                    Log.d("BackendProcessing", "Action Type: $actionType")
                                    Log.d("BackendProcessing", "Action Target: $actionTarget")
                                    Log.d("BackendProcessing", "Confidence Score: $confidenceScore")
                                    Log.d("BackendProcessing", "Reasoning: $reasoning")
                                    Log.d("BackendProcessing", "Bounding Box: x=$x, y=$y, width=$width, height=$height")
                                    Log.d("BackendProcessing", "Element Selector:")
                                    Log.d("BackendProcessing", "  - Text: '$text'")
                                    Log.d("BackendProcessing", "  - Resource ID: '$resourceId'")
                                    Log.d("BackendProcessing", "  - Class Name: '$className'")
                                    Log.d("BackendProcessing", "  - Content Description: '$contentDescription'")
                                    Log.d("BackendProcessing", "  - Hierarchy Path: '$hierarchyPath'")
                                    Log.d("BackendProcessing", "Fallback Coordinates: x=$fallbackX, y=$fallbackY")
                                    Log.d("BackendProcessing", "=== END RECOMMENDED ACTION ===")
                                    
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
                                        Screen Information:
                                        - Screenshot Width: ${bitmap.width}
                                        - Screenshot Height: ${bitmap.height}
                                        - Screen Width: ${displayMetrics.widthPixels}
                                        - Screen Height: ${displayMetrics.heightPixels}
                                        - Screen Density: ${displayMetrics.density}
                                    """.trimIndent())

                                    // Execute the recommended action
                                    if (actionExecutor != null) {
                                        Log.d("BackendProcessing", "Executing recommended action...")
                                        val actionSuccess = actionExecutor!!.executeAction(recommendedAction)
                                        Log.d("BackendProcessing", "Action execution result: $actionSuccess")
                                        
                                        if (actionSuccess) {
                                            Log.d("BackendProcessing", "✅ Action executed successfully!")
                                            // Add a delay to allow UI to respond to the action
                                            Thread.sleep(1000)
                                        } else {
                                            Log.w("BackendProcessing", "❌ Action execution failed")
                                        }
                                    } else {
                                        Log.w("BackendProcessing", "ActionExecutor not available - cannot execute action")
                                    }
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