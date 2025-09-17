package com.example.beta

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
    
    // Sequential action tracking
    private var currentActionNumber: Int = 0
    private var maxActions: Int = 5 // Safety limit
    private var isActionSequenceActive: Boolean = false
    private var originalInputText: String? = null
    private var sequenceContext: Context? = null
    
    // Historical context tracking
    private val actionHistory = mutableListOf<JSONObject>()
    
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
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
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

    fun startActionSequence(context: Context, inputText: String, accessibilityService: MyAccessibilityService? = null) {
        // Log.d("BackendProcessing", "Starting action sequence for: '$inputText'")
        
        // Reset sequence tracking
        currentActionNumber = 0
        isActionSequenceActive = true
        originalInputText = inputText
        sequenceContext = context
        actionHistory.clear() // Reset history for new sequence
        
        // Initialize action executor if accessibility service is available
        if (accessibilityService != null && actionExecutor == null) {
            actionExecutor = ActionExecutor(accessibilityService)
            // Log.d("BackendProcessing", "ActionExecutor initialized for sequence")
        }
        
        // Log.d("BackendProcessing", "Action sequence initialized - Action #${currentActionNumber + 1}/$maxActions")
    }
    
    fun stopActionSequence() {
        // Log.d("BackendProcessing", "Stopping action sequence")
        isActionSequenceActive = false
        currentActionNumber = 0
        originalInputText = null
        sequenceContext = null
    }
    
    fun isSequenceActive(): Boolean {
        return isActionSequenceActive
    }
    
    fun getCurrentActionNumber(): Int {
        return currentActionNumber
    }
    
    fun getMaxActions(): Int {
        return maxActions
    }
    
    private fun triggerNextAction() {
        Log.d("BackendProcessing", "🔍 DEBUG: triggerNextAction called")
        Log.d("BackendProcessing", "🔍 DEBUG: isActionSequenceActive=$isActionSequenceActive, sequenceContext=${sequenceContext != null}, originalInputText=$originalInputText")
        
        if (!isActionSequenceActive || sequenceContext == null || originalInputText == null) {
            Log.w("BackendProcessing", "🔍 DEBUG: Cannot trigger next action - sequence not active or missing data")
            return
        }
        
        if (currentActionNumber >= maxActions) {
            Log.w("BackendProcessing", "🔍 DEBUG: Maximum actions reached ($maxActions), stopping sequence")
            stopActionSequence()
            return
        }
        
        Log.d("BackendProcessing", "🔍 DEBUG: Triggering next action #${currentActionNumber + 1} in sequence")
        
        // Wait a bit for UI to stabilize after the previous action
        Thread {
            try {
                Thread.sleep(1500) // Wait 1.5 seconds for UI to respond
                
                // Double-check that sequence is still active after delay
                if (!isActionSequenceActive) {
                    Log.w("BackendProcessing", "🔍 DEBUG: Sequence was stopped during delay, aborting next action")
                    return@Thread
                }
                
                // Trigger the next screenshot and tree capture sequence
                val intent = android.content.Intent("com.example.beta.TRIGGER_NEXT_ACTION")
                intent.putExtra("original_input", originalInputText)
                intent.putExtra("action_number", currentActionNumber + 1)
                
                Log.d("BackendProcessing", "🔍 DEBUG: About to send broadcast with:")
                Log.d("BackendProcessing", "🔍 DEBUG: - Action: com.example.beta.TRIGGER_NEXT_ACTION")
                Log.d("BackendProcessing", "🔍 DEBUG: - Original input: '$originalInputText'")
                Log.d("BackendProcessing", "🔍 DEBUG: - Action number: ${currentActionNumber + 1}")
                Log.d("BackendProcessing", "🔍 DEBUG: - Context: ${sequenceContext?.javaClass?.simpleName}")
                
                // Try both local and global broadcast
                try {
                    sequenceContext?.sendBroadcast(intent)
                    Log.d("BackendProcessing", "🔍 DEBUG: Global broadcast sent successfully")
                } catch (e: Exception) {
                    Log.e("BackendProcessing", "🔍 DEBUG: Global broadcast failed: ${e.message}")
                }
                
                // Also try local broadcast
                try {
                    LocalBroadcastManager.getInstance(sequenceContext!!).sendBroadcast(intent)
                    Log.d("BackendProcessing", "🔍 DEBUG: Local broadcast sent successfully")
                } catch (e: Exception) {
                    Log.e("BackendProcessing", "🔍 DEBUG: Local broadcast failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("BackendProcessing", "Error triggering next action: ${e.message}", e)
                stopActionSequence() // Stop sequence on error
            }
        }.start()
    }
    
    fun processScreenshotWithInput(context: Context, bitmap: Bitmap, filename: String, inputText: String, appName: String? = null, treeData: String? = null, accessibilityService: MyAccessibilityService? = null) {
        // Log.d("BackendProcessing", "processScreenshotWithInput called with filename: $filename")
        
        // Store the additional data
        currentAppName = appName
        currentTreeData = treeData
        
        // Initialize action executor if accessibility service is available
        if (accessibilityService != null && actionExecutor == null) {
            actionExecutor = ActionExecutor(accessibilityService)
            // Log.d("BackendProcessing", "ActionExecutor initialized")
        }
        
        // If this is part of an action sequence, increment action number
        if (isActionSequenceActive) {
            currentActionNumber++
            // Log.d("BackendProcessing", "Processing action #$currentActionNumber in sequence")
        }
        
        // Log screenshot dimensions
        // Log.d("BackendProcessing", "Screenshot dimensions - Width: ${bitmap.width}, Height: ${bitmap.height}")

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
                Log.d("BackendProcessing", "📤 SENDING TO BACKEND - App name: $currentAppName")
            } else {
                Log.w("BackendProcessing", "⚠️ App name is null or empty - not sending to backend")
            }
            
            // Add tree data if available
            if (!currentTreeData.isNullOrEmpty()) {
                requestBodyBuilder.addFormDataPart("detailed_tree_data", currentTreeData!!)
                Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Tree data length: ${currentTreeData!!.length}")
            } else {
                Log.w("BackendProcessing", "⚠️ Tree data is null or empty - not sending to backend")
            }
            
            // Add sequence tracking data
            if (isActionSequenceActive) {
                requestBodyBuilder.addFormDataPart("sequence_step", currentActionNumber.toString())
                requestBodyBuilder.addFormDataPart("max_sequence_steps", maxActions.toString())
                Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Sequence step: $currentActionNumber/$maxActions")
                
                // Add historical context if available
                if (actionHistory.isNotEmpty()) {
                    val historyJson = JSONArray()
                    actionHistory.forEach { action ->
                        historyJson.put(action)
                    }
                    requestBodyBuilder.addFormDataPart("action_history", historyJson.toString())
                    Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Action history: ${actionHistory.size} actions")
                }
            }
            
            // Log input text being sent
            Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Input text: '$inputText'")
            
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
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - App: $appName")
                            
                            // Extract and log input text
                            val inputText = jsonObject.optString("input_text", "")
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - Input text: '$inputText'")
                            
                            // Extract and log image dimensions
                            val imageDimensions = jsonObject.optJSONObject("image_dimensions")
                            when (imageDimensions) {
                                null -> { /* No image dimensions in response */ }
                                else -> {
                                    val width = imageDimensions.getInt("image_width")
                                    val height = imageDimensions.getInt("image_height")
                                    // Log.d("BackendProcessing", "Image dimensions: ${width}x${height}")
                                }
                            }
                            
                            // Extract and handle recommended action (enhanced format)
                            val recommendedAction = jsonObject.optJSONObject("recommended_action")
                            when (recommendedAction) {
                                null -> {
                                    Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - No action recommendation found")
                                    // buttonHighlightService?.clearHighlight() - service removed
                                }
                                else -> {
                                    val actionType = recommendedAction.getString("action_type")
                                    val actionTarget = recommendedAction.getString("action_target")
                                    val confidenceScore = recommendedAction.getDouble("confidence")
                                    val reasoning = recommendedAction.optString("reasoning", "No reasoning provided")
                                    
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
                                    
                                    Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - RECOMMENDED ACTION:")
                                    Log.d("BackendProcessing", "  Action Type: $actionType")
                                    Log.d("BackendProcessing", "  Action Target: $actionTarget")
                                    Log.d("BackendProcessing", "  Confidence Score: $confidenceScore")
                                    Log.d("BackendProcessing", "  Reasoning: $reasoning")
                                    Log.d("BackendProcessing", "  Element Text: '$text'")
                                    Log.d("BackendProcessing", "  Resource ID: '$resourceId'")
                                    Log.d("BackendProcessing", "  Class Name: '$className'")
                                    Log.d("BackendProcessing", "  Content Description: '$contentDescription'")
                                    
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
                                    
                                    // Log.d("BackendProcessing", """
                                    //     Screen Information:
                                    //     - Screenshot Width: ${bitmap.width}
                                    //     - Screenshot Height: ${bitmap.height}
                                    //     - Screen Width: ${displayMetrics.widthPixels}
                                    //     - Screen Height: ${displayMetrics.heightPixels}
                                    //     - Screen Density: ${displayMetrics.density}
                                    // """.trimIndent())

                                    // Execute the recommended action
                                    if (actionExecutor != null) {
                                        // Check if this was part of a sequence that's no longer active
                                        // (This prevents late backend responses from executing actions after sequence ended)
                                        val wasSequenceAction = currentActionNumber > 0
                                        if (wasSequenceAction && !isActionSequenceActive) {
                                            Log.w("BackendProcessing", "⚠️ Backend response received but action sequence is no longer active - ignoring action")
                                            return@onResponse
                                        }
                                        
                                        // Log.d("BackendProcessing", "Executing recommended action...")
                                        
                                        // Store action in history before executing
                                        val actionToStore = JSONObject()
                                        actionToStore.put("step", currentActionNumber)
                                        actionToStore.put("action_type", recommendedAction.optString("action_type", "unknown"))
                                        actionToStore.put("action_target", recommendedAction.optString("action_target", ""))
                                        actionToStore.put("confidence", recommendedAction.optDouble("confidence", 0.0))
                                        actionHistory.add(actionToStore)
                                        
                                        val actionSuccess = actionExecutor!!.executeAction(recommendedAction)
                                        Log.d("BackendProcessing", "🎯 ACTION EXECUTION RESULT: $actionSuccess")
                                        
                                        // Update stored action with execution result
                                        actionToStore.put("execution_success", actionSuccess)
                                        
                                        if (actionSuccess) {
                                            Log.d("BackendProcessing", "✅ Action executed successfully!")
                                            
                                            // Check if this is part of an action sequence
                                            if (isActionSequenceActive) {
                                                Log.d("BackendProcessing", "🔍 DEBUG: Action #$currentActionNumber completed, checking for next action...")
                                                
                                                // Check if the action indicates completion
                                                val isCompleted = recommendedAction.optBoolean("is_completed", false)
                                                val taskCompleted = jsonObject.optBoolean("task_completed", false)
                                                
                                                Log.d("BackendProcessing", "🔍 DEBUG: is_completed=$isCompleted, task_completed=$taskCompleted")
                                                
                                                if (isCompleted || taskCompleted) {
                                                    Log.d("BackendProcessing", "🏁 TASK COMPLETED! Stopping action sequence.")
                                                    stopActionSequence()
                                                } else {
                                                    Log.d("BackendProcessing", "🔍 DEBUG: Task not completed, triggering next action...")
                                                    // Trigger the next action in the sequence
                                                    triggerNextAction()
                                                }
                                            } else {
                                                // Single action mode - just add delay
                                                Thread.sleep(1000)
                                            }
                                        } else {
                                            Log.w("BackendProcessing", "❌ Action execution failed")
                                            if (isActionSequenceActive) {
                                                // Log.w("BackendProcessing", "Action failed in sequence - stopping sequence")
                                                stopActionSequence()
                                            }
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
                                    // Log.d("BackendProcessing", "Found ${buttonsArray.length()} buttons in response")
                                    // for (i in 0 until buttonsArray.length()) {
                                    //     val button = buttonsArray.getJSONObject(i)
                                    //     val buttonName = button.getString("button_name")
                                    //     Log.d("BackendProcessing", "Button: $buttonName")
                                    // }
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