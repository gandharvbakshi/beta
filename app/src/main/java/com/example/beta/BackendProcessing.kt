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
import com.example.beta.automation.ParsedItem

object BackendProcessing {
    private const val TAG = "BetaAgent"
    private val client = provideOkHttpClient()
    var currentBitmap: Bitmap? = null
    var currentFilename: String? = null
    private var currentInputText: String? = null
    private var currentAppName: String? = null
    private var currentTreeData: String? = null
    private var actionExecutor: ActionExecutor? = null

    private fun appNameForBackend(appName: String?): String {
        return when (appName?.trim()) {
            null, "", "com.grofers.customerapp" -> "Blinkit"
            else -> appName.trim()
        }
    }
    
    // Sequential action tracking
    private var currentActionNumber: Int = 0
    private var maxActions: Int = 20 // Safety limit for search + scroll + product + cart verification
    private var isActionSequenceActive: Boolean = false
    private var requestInFlight: Boolean = false
    private var originalInputText: String? = null
    private var sequenceContext: Context? = null
    private var emptyBlinkitTreeRetries: Int = 0
    private var hasEmittedItemOutcome = false
    private var multiItemSequenceActive = false
    private var multiItemSequenceStartedAtMs: Long = 0L
    private var multiItemSequenceAccessibilityService: MyAccessibilityService? = null
    private var multiItemSequenceItems: List<ParsedItem> = emptyList()
    private var multiItemSequenceIndex: Int = 0
    private var multiItemSequenceAwaitingNext = false
    private val multiItemSequenceOutcomes = mutableListOf<ItemOutcome>()
    
    // Historical context tracking
    private val actionHistory = mutableListOf<JSONObject>()
    
    // Services removed - not available in current version

    private fun ensureActionExecutor(context: Context, accessibilityService: MyAccessibilityService? = null): Boolean {
        if (actionExecutor != null) return true

        val service = accessibilityService
            ?: (context.applicationContext as? MyApplication)?.getAccessibilityService()

        return if (service != null) {
            actionExecutor = ActionExecutor(service)
            Log.d("BackendProcessing", "ActionExecutor initialized from active AccessibilityService")
            true
        } else {
            Log.w("BackendProcessing", "ActionExecutor unavailable: AccessibilityService is not active")
            false
        }
    }

    private fun updateFlowStatus(context: Context?, status: String) {
        Log.i(TAG, status)
        context ?: return
        (context.applicationContext as? MyApplication)
            ?.getScreenCaptureService()
            ?.updateSessionStatus(status)
    }

    private fun endScreenCaptureSession(context: Context, reason: String) {
        if (multiItemSequenceActive) {
            Log.d(TAG, "Keeping screen capture session active during multi-item sequence: $reason")
            return
        }
        (context.applicationContext as? MyApplication)
            ?.getScreenCaptureService()
            ?.endSession(reason)
    }

    private fun statusForAction(actionType: String, actionTarget: String, step: Int, max: Int): String {
        val target = actionTarget.lowercase()
        val phase = when {
            actionType.equals("type", ignoreCase = true) -> "Typing search"
            target.contains("search") -> "Opening search"
            actionType.equals("scroll", ignoreCase = true) ||
                actionType.equals("swipe", ignoreCase = true) -> "Scrolling results"
            target.contains("open product") ||
                target.contains("product page") -> "Opening product"
            target.contains("add") -> "Adding item"
            target.contains("cart") -> "Checking cart"
            actionType.equals("error", ignoreCase = true) -> "Needs attention"
            else -> "Working"
        }
        return "$phase ($step/$max)"
    }

    private fun isCheckoutBoundaryDetected(responseText: String?, treeData: String?): Boolean {
        val checkoutBoundaryPhrases = listOf(
            "place order",
            "pay now",
            "continue to payment",
            "proceed to payment",
            "to payment",
            "place the order",
            "payment",
            "finalize",
            "finalize order"
        )
        val normalizedResponseText = responseText?.lowercase() ?: ""
        val normalizedTreeData = treeData?.lowercase() ?: ""
        return checkoutBoundaryPhrases.any { normalizedResponseText.contains(it) } ||
            checkoutBoundaryPhrases.any { normalizedTreeData.contains(it) }
    }

    private fun statusForTerminalReason(reason: String): ItemOutcomeStatus {
        val normalized = reason.lowercase()
        return when {
            normalized.contains("out of stock") ||
                normalized.contains("sold out") ||
                normalized.contains("notify me") ||
                normalized.contains("currently unavailable") ||
                normalized.contains("unavailable") -> ItemOutcomeStatus.OOS
            normalized.contains("low") && normalized.contains("confidence") -> ItemOutcomeStatus.LOW_CONFIDENCE
            normalized.contains("timeout") ||
                normalized.contains("timed out") ||
                normalized.contains("max steps") ||
                normalized.contains("maximum actions") -> ItemOutcomeStatus.TIMEOUT
            normalized.contains("misclick") ||
                normalized.contains("wrong") ||
                normalized.contains("wishlist") ||
                normalized.contains("summary_and_edit") -> ItemOutcomeStatus.MISCLICK
            else -> ItemOutcomeStatus.NOT_FOUND
        }
    }

    private fun noteForTerminalReason(status: ItemOutcomeStatus, fallback: String): String {
        return when (status) {
            ItemOutcomeStatus.OOS -> "out_of_stock"
            ItemOutcomeStatus.LOW_CONFIDENCE -> "low_confidence"
            ItemOutcomeStatus.TIMEOUT -> "timeout"
            ItemOutcomeStatus.MISCLICK -> "misclick"
            else -> fallback
        }
    }

    private fun emitPhase0Outcome(
        context: Context,
        item: String?,
        status: ItemOutcomeStatus,
        matchedSku: String = "",
        qtyRequested: Int = 1,
        qtyAdded: Int = 0,
        notes: String = ""
    ) {
        if (hasEmittedItemOutcome) return
        hasEmittedItemOutcome = true

        val normalizedItem = normalizeOrderOutcomeItem(item)
        val normalizedSku = matchedSku.trim()
        val normalizedQtyAdded = if (status == ItemOutcomeStatus.SUCCESS && qtyAdded <= 0) 1 else qtyAdded
        val itemOutcome = ItemOutcome(
            item = normalizedItem,
            status = status,
            matchedSku = normalizedSku,
            qtyRequested = qtyRequested,
            qtyAdded = normalizedQtyAdded,
            notes = notes
        )
        Log.i(TAG, formatItemResultLine(itemOutcome))
        updateFlowStatus(context, formatItemResultStateLine(itemOutcome.item, status))

        if (handleMultiItemOutcome(context, itemOutcome)) {
            return
        }

        val failures = if (status == ItemOutcomeStatus.SUCCESS) {
            emptyList()
        } else {
            listOf(OrderFailure(item = normalizedItem, reason = status))
        }
        val orderOutcome = OrderOutcomeSummary(
            itemsTotal = 1,
            itemsSucceeded = if (status == ItemOutcomeStatus.SUCCESS) 1 else 0,
            itemsFailed = if (status == ItemOutcomeStatus.SUCCESS) 0 else 1,
            failures = failures
        )
        Log.i(TAG, orderOutcome.orderDoneLine())
        updateFlowStatus(context, formatOrderDoneStateLine())
    }

    private fun handleMultiItemOutcome(context: Context, itemOutcome: ItemOutcome): Boolean {
        if (!multiItemSequenceActive) return false

        multiItemSequenceOutcomes.add(itemOutcome)
        val totalItems = multiItemSequenceItems.size
        val nextIndex = multiItemSequenceIndex + 1
        val elapsedMs = System.currentTimeMillis() - multiItemSequenceStartedAtMs

        if (elapsedMs >= TimeUnit.MINUTES.toMillis(10)) {
            Log.w(TAG, "Multi-item sequence timed out after 10 minutes")
            finishMultiItemSequence(context, timedOut = true)
            return true
        }

        if (nextIndex >= totalItems) {
            finishMultiItemSequence(context, timedOut = false)
            return true
        }

        multiItemSequenceIndex = nextIndex
        val nextItem = multiItemSequenceItems[nextIndex]
        updateFlowStatus(context, "Preparing next item (${nextIndex + 1}/$totalItems)")
        multiItemSequenceAwaitingNext = true

        Thread {
            try {
                performMultiItemCleanup()
                Thread.sleep(1000)
                if (!multiItemSequenceActive) return@Thread
                (context.applicationContext as? MyApplication)
                    ?.getScreenCaptureService()
                    ?.startNewAutomationSessionForSequenceItem()
                startActionSequence(context, nextItem.query, multiItemSequenceAccessibilityService)
                multiItemSequenceAwaitingNext = false
                val nextIntent = Intent("com.example.beta.TRIGGER_NEXT_ACTION").apply {
                    putExtra("original_input", nextItem.query)
                    putExtra("action_number", 1)
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(nextIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to advance multi-item sequence: ${e.message}", e)
                finishMultiItemSequence(context, timedOut = true)
            }
        }.start()

        return true
    }

    private fun performMultiItemCleanup() {
        val service = multiItemSequenceAccessibilityService ?: return
        try {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.w(TAG, "Multi-item cleanup back navigation failed: ${e.message}")
        }
    }

    private fun resetMultiItemSequenceState() {
        multiItemSequenceActive = false
        multiItemSequenceStartedAtMs = 0L
        multiItemSequenceAccessibilityService = null
        multiItemSequenceItems = emptyList()
        multiItemSequenceIndex = 0
        multiItemSequenceAwaitingNext = false
        multiItemSequenceOutcomes.clear()
    }

    private fun finishMultiItemSequence(context: Context, timedOut: Boolean) {
        if (!multiItemSequenceActive) return

        val totalItems = multiItemSequenceItems.size
        val succeeded = multiItemSequenceOutcomes.count { it.status == ItemOutcomeStatus.SUCCESS }
        val failures = mutableListOf<OrderFailure>()
        multiItemSequenceOutcomes.forEach { outcome ->
            if (outcome.status != ItemOutcomeStatus.SUCCESS) {
                failures.add(OrderFailure(item = outcome.item, reason = outcome.status))
            }
        }
        if (timedOut && multiItemSequenceOutcomes.size < totalItems) {
            multiItemSequenceItems.drop(multiItemSequenceOutcomes.size).forEach { pendingItem ->
                failures.add(
                    OrderFailure(
                        item = normalizeOrderOutcomeItem(pendingItem.rawText),
                        reason = ItemOutcomeStatus.TIMEOUT
                    )
                )
            }
        }

        val orderOutcome = OrderOutcomeSummary(
            itemsTotal = totalItems,
            itemsSucceeded = succeeded,
            itemsFailed = totalItems - succeeded,
            failures = failures
        )
        Log.i(TAG, orderOutcome.orderDoneLine())
        updateFlowStatus(context, formatOrderDoneStateLine())

        resetMultiItemSequenceState()
    }

    fun startMultiItemSequence(
        context: Context,
        parsedItems: List<ParsedItem>,
        accessibilityService: MyAccessibilityService? = null
    ) {
        val validItems = parsedItems.filter { it.query.isNotBlank() }
        if (validItems.isEmpty()) {
            Log.w(TAG, "startMultiItemSequence called with no parsed items")
            return
        }

        if (validItems.size == 1) {
            startActionSequence(context, validItems.first().query, accessibilityService)
            return
        }

        multiItemSequenceActive = true
        multiItemSequenceStartedAtMs = System.currentTimeMillis()
        multiItemSequenceAccessibilityService = accessibilityService
        multiItemSequenceItems = validItems
        multiItemSequenceIndex = 0
        multiItemSequenceOutcomes.clear()
        multiItemSequenceAwaitingNext = false

        Log.i(TAG, "MULTI_ORDER_STARTED items_total=${validItems.size} items=\"${validItems.joinToString(";") { it.query }}\"")
        updateFlowStatus(context, "STATE: MULTI_ORDER_STARTED\nITEM 1/${validItems.size}: ${validItems.first().query}")
        startActionSequence(context, validItems.first().query, accessibilityService)

        val sequenceStartedAt = multiItemSequenceStartedAtMs
        Thread {
            Thread.sleep(TimeUnit.MINUTES.toMillis(10))
            if (multiItemSequenceActive && multiItemSequenceStartedAtMs == sequenceStartedAt) {
                Log.w(TAG, "Multi-item sequence reached 10-minute cap")
                finishMultiItemSequence(context, timedOut = true)
                stopActionSequence()
            }
        }.start()
    }

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

    fun uploadScreenshotAndProcess(context: Context, bitmap: Bitmap, filename: String, appName: String? = null, treeData: String? = null, sessionContext: SessionContext? = null) {
        Log.d("BackendProcessing", "uploadScreenshotAndProcess called with filename: $filename")
        currentBitmap = bitmap
        currentFilename = filename
        currentAppName = appName
        currentTreeData = treeData
    }

    fun startActionSequence(context: Context, inputText: String, accessibilityService: MyAccessibilityService? = null) {
        // Log.d("BackendProcessing", "Starting action sequence for: '$inputText'")
        
        // Log test run start for easy identification in logs
        DebugLogger.logTestRunStart("Action sequence: '$inputText'")
        
        // Reset sequence tracking
        currentActionNumber = 0
        isActionSequenceActive = true
        requestInFlight = false
        originalInputText = inputText
        sequenceContext = context
        emptyBlinkitTreeRetries = 0
        hasEmittedItemOutcome = false
        actionHistory.clear() // Reset history for new sequence
        Log.i(TAG, "INSTRUCTION_RECEIVED: $inputText")
        updateFlowStatus(context, "STATE: INSTRUCTION_RECEIVED\nTARGET: $inputText")
        
        // Initialize action executor if accessibility service is available
        ensureActionExecutor(context, accessibilityService)
        
        // Log.d("BackendProcessing", "Action sequence initialized - Action #${currentActionNumber + 1}/$maxActions")
    }
    
    fun stopActionSequence() {
        // Log.d("BackendProcessing", "Stopping action sequence")
        val preserveMultiSequence = multiItemSequenceActive && multiItemSequenceAwaitingNext
        isActionSequenceActive = false
        requestInFlight = false
        currentActionNumber = 0
        originalInputText = null
        sequenceContext = null
        emptyBlinkitTreeRetries = 0
        hasEmittedItemOutcome = false
        if (!preserveMultiSequence) {
            resetMultiItemSequenceState()
        }
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
            updateFlowStatus(sequenceContext, "Stopped - max steps")
            sequenceContext?.let { context ->
                emitPhase0Outcome(
                    context = context,
                    item = originalInputText,
                    status = ItemOutcomeStatus.TIMEOUT,
                    notes = "max_steps"
                )
            }
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
                updateFlowStatus(sequenceContext, "Reading screen (${currentActionNumber + 1}/$maxActions)")
                
                Log.d("BackendProcessing", "🔍 DEBUG: About to send broadcast with:")
                Log.d("BackendProcessing", "🔍 DEBUG: - Action: com.example.beta.TRIGGER_NEXT_ACTION")
                Log.d("BackendProcessing", "🔍 DEBUG: - Original input: '$originalInputText'")
                Log.d("BackendProcessing", "🔍 DEBUG: - Action number: ${currentActionNumber + 1}")
                Log.d("BackendProcessing", "🔍 DEBUG: - Context: ${sequenceContext?.javaClass?.simpleName}")
                
                // Use one delivery path. Sending both global and local creates duplicate
                // screenshots and overlapping backend actions on the emulator.
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
    
    fun processScreenshotWithInput(context: Context, bitmap: Bitmap, filename: String, inputText: String, appName: String? = null, treeData: String? = null, accessibilityService: MyAccessibilityService? = null, sessionContext: SessionContext? = null) {
        // Log.d("BackendProcessing", "processScreenshotWithInput called with filename: $filename")
        
        // Store the additional data
        currentAppName = appName
        currentTreeData = treeData

        if (isActionSequenceActive && requestInFlight) {
            Log.w("BackendProcessing", "Ignoring screenshot because backend request/action is already in flight")
            updateFlowStatus(context, "Working ($currentActionNumber/$maxActions)")
            return
        }
        requestInFlight = true
        
        // Initialize action executor if accessibility service is available
        ensureActionExecutor(context, accessibilityService)
        
        // If this is part of an action sequence, increment action number
        if (isActionSequenceActive) {
            currentActionNumber++
            updateFlowStatus(context, "Analyzing screen ($currentActionNumber/$maxActions)")
            // Log.d("BackendProcessing", "Processing action #$currentActionNumber in sequence")
        }

        val backendAppName = appNameForBackend(currentAppName)
        if (
            isActionSequenceActive &&
            backendAppName == "Blinkit" &&
            currentTreeData.isNullOrBlank() &&
            emptyBlinkitTreeRetries < 3
        ) {
            emptyBlinkitTreeRetries++
            Log.w("BackendProcessing", "Blinkit tree was empty; retrying capture before asking backend ($emptyBlinkitTreeRetries/3)")
            updateFlowStatus(context, "Reading screen ($currentActionNumber/$maxActions)")
            requestInFlight = false
            triggerNextAction()
            return
        }
        emptyBlinkitTreeRetries = 0
        
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
            
            // Get client version from MyApplication
            val clientVersion = (context.applicationContext as? MyApplication)?.getClientVersion() ?: "1.0"
            val apiVersion = (context.applicationContext as? MyApplication)?.getApiVersion() ?: "1.0"
            
            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody)
                .addFormDataPart("input_text", inputText ?: "")
                .addFormDataPart("api_version", apiVersion)
                .addFormDataPart("client_version", clientVersion)
            
            // Add session_id if available
            if (sessionContext != null) {
                requestBodyBuilder.addFormDataPart("session_id", sessionContext.sessionId)
                Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Session ID: ${sessionContext.sessionId}")
            } else {
                Log.w("BackendProcessing", "⚠️ No session context - session_id not sent")
            }
            
            // Add last action result if available (avoid smart-cast on mutable property)
            sessionContext?.lastActionResult?.let { lastResult ->
                val actionResultJson = lastResult.toJson().toString()
                requestBodyBuilder.addFormDataPart("last_action_result_json", actionResultJson)
                Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Last action result: ${lastResult.status}")
            }
            
            // Add action history if available
            if (sessionContext?.actionHistory?.isNotEmpty() == true) {
                val historyJson = JSONArray()
                sessionContext.actionHistory.forEach { action ->
                    historyJson.put(action)
                }
                requestBodyBuilder.addFormDataPart("action_history_json", historyJson.toString())
                Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Action history: ${sessionContext.actionHistory.size} actions")
            }
            
            requestBodyBuilder.addFormDataPart("app_name", backendAppName)
            Log.d("BackendProcessing", "📤 SENDING TO BACKEND - App name: $backendAppName")
            
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
                
            }
            
            // Log input text being sent
            Log.d("BackendProcessing", "📤 SENDING TO BACKEND - Input text: '$inputText'")
            
            // Log complete backend request details
            DebugLogger.logBackendRequest(
                inputText = inputText ?: "",
                appName = backendAppName,
                treeDataLength = currentTreeData?.length ?: 0,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
            
            val requestBody = requestBodyBuilder.build()

            val request = Request.Builder()
                .url("https://10.0.2.2:8000/analyze-screenshot")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    Log.e("UploadFailure", "Failed to upload image: ${e.message}")
                    requestInFlight = false
                    updateFlowStatus(context, "Stopped - backend offline")
                    // buttonHighlightService?.clearHighlight() - service removed
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.e("UploadResponse", "Unexpected response: $response")
                        requestInFlight = false
                        updateFlowStatus(context, "Stopped - backend error")
                        // buttonHighlightService?.clearHighlight() - service removed
                        return
                    }

                    response.body?.let { responseBody ->
                        try {
                            val jsonString = responseBody.string()
                            val jsonObject = JSONObject(jsonString)
                            
                            // Parse new response structure
                            val apiVersion = jsonObject.optString("api_version", "unknown")
                            val sessionId = jsonObject.optString("session_id", "")
                            val stateStr = jsonObject.optString("state", "")
                            val policyObj = jsonObject.optJSONObject("policy")
                            val progressObj = jsonObject.optJSONObject("progress")
                            val debugObj = jsonObject.optJSONObject("debug")
                            val errorObj = jsonObject.optJSONObject("error")
                            
                            // Parse session state
                            val sessionState = SessionState.fromString(stateStr)
                            
                            // Parse policy
                            val minConfidence = policyObj?.optDouble("min_confidence", 0.7) ?: 0.7
                            
                            // Parse progress
                            val itemsTotal = progressObj?.optInt("items_total", 0) ?: 0
                            val itemsDone = progressObj?.optInt("items_done", 0) ?: 0
                            
                            // Parse debug info
                            val requestId = debugObj?.optString("request_id", "") ?: ""
                            val debugNotes = debugObj?.optString("notes", "") ?: ""
                            
                            // Parse error
                            val errorReason = errorObj?.optString("reason", "") ?: ""
                            val errorDetails = errorObj?.optString("details", "") ?: ""
                            val workflowObj = jsonObject.optJSONObject("workflow")
                            val workflowState = workflowObj?.optString("state", "") ?: ""
                            val awaitingConfirmation = jsonObject.optBoolean("awaiting_confirmation", false) ||
                                workflowObj?.optBoolean("awaiting_confirmation", false) == true
                            val failureReason = jsonObject.optString("failure_reason", "")
                            val textToType = jsonObject.optString("text_to_type", "")
                            
                            // Parse verification fields (API v1.1)
                            val verificationRequired = jsonObject.optBoolean("verification_required", false)
                            val verificationStatusObj = jsonObject.optJSONObject("verification_status")
                            val verificationStatus = if (verificationStatusObj != null) {
                                VerificationStatus(
                                    isVerificationStep = verificationStatusObj.optBoolean("is_verification_step", false),
                                    targetItem = verificationStatusObj.optString("target_item", null),
                                    itemFoundInCart = if (verificationStatusObj.isNull("item_found_in_cart")) null 
                                                      else verificationStatusObj.optBoolean("item_found_in_cart", false),
                                    verificationDetails = verificationStatusObj.optString("verification_details", null),
                                    verificationFailed = verificationStatusObj.optBoolean("verification_failed", false),
                                    retryAction = verificationStatusObj.optString("retry_action", null)
                                )
                            } else null
                            
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - Session ID: $sessionId")
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - State: $stateStr")
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - Min Confidence: $minConfidence")
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - Progress: $itemsDone/$itemsTotal")
                            Log.d("BackendProcessing", "📥 RECEIVED FROM BACKEND - Request ID: $requestId")
                            
                            // Log verification status if present
                            if (verificationRequired) {
                                Log.d("BackendProcessing", "🔍 VERIFICATION REQUIRED - Target: ${verificationStatus?.targetItem}")
                                updateFlowStatus(context, "Checking cart ($currentActionNumber/$maxActions)")
                            }
                            if (verificationStatus?.isVerificationStep == true) {
                                Log.d("BackendProcessing", "🔍 VERIFICATION STEP - Checking for: ${verificationStatus.targetItem}")
                                Log.d("BackendProcessing", "🔍 Item found in cart: ${verificationStatus.itemFoundInCart}")
                                Log.d("BackendProcessing", "🔍 Details: ${verificationStatus.verificationDetails}")
                                updateFlowStatus(context, "Verifying item")
                            }
                            
                            if (errorReason.isNotEmpty()) {
                                Log.e("BackendProcessing", "📥 RECEIVED FROM BACKEND - Error: $errorReason - $errorDetails")
                                requestInFlight = false
                                handleBackendError(context, errorReason, errorDetails, sessionContext)
                                return@let
                            }

                            if (failureReason.isNotBlank() || workflowState == "FAILED_NEEDS_USER") {
                                val userMessage = failureReason.ifBlank { "Manual help needed to continue this order." }
                                val terminalStatus = statusForTerminalReason(userMessage)
                                Log.w("BackendProcessing", "🛑 Workflow stopped: $userMessage")
                                emitPhase0Outcome(
                                    context = context,
                                    item = verificationStatus?.targetItem ?: originalInputText,
                                    status = terminalStatus,
                                    notes = noteForTerminalReason(terminalStatus, "workflow_failed")
                                )
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(context, userMessage, android.widget.Toast.LENGTH_LONG).show()
                                }
                                endScreenCaptureSession(context, userMessage)
                                requestInFlight = false
                                stopActionSequence()
                                return@let
                            }

                            if (awaitingConfirmation || workflowState == "AWAITING_CONFIRMATION") {
                                Log.d("BackendProcessing", "🛒 Cart is ready; awaiting user confirmation")
                                updateFlowStatus(context, "Paused - check cart")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Cart is ready. Please confirm manually.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                sessionContext?.let { ctx ->
                                    val confirmationResult = ActionResult(
                                        actionId = requestId.ifBlank { "awaiting_confirmation" },
                                        status = "success",
                                        notes = "awaiting_user_confirmation"
                                    )
                                    ctx.lastActionResult = confirmationResult
                                    ctx.actionHistory.add(confirmationResult.toJson())
                                }
                                endScreenCaptureSession(context, "Awaiting confirmation")
                                requestInFlight = false
                                stopActionSequence()
                                return@let
                            }
                            
                            // Handle session end conditions
                            when (sessionState) {
                            SessionState.COMPLETED -> {
                                Log.d("BackendProcessing", "🏁 Session completed - ending session")
                                val completedItem = verificationStatus?.targetItem ?: originalInputText
                                val completedNotes = if (verificationStatus?.itemFoundInCart == true) {
                                    "verified_in_cart"
                                } else {
                                    "session_completed"
                                }
                                emitPhase0Outcome(
                                    context = context,
                                    item = completedItem,
                                    status = ItemOutcomeStatus.SUCCESS,
                                    matchedSku = verificationStatus?.targetItem ?: "",
                                    qtyAdded = 1,
                                    notes = completedNotes
                                )
                                endScreenCaptureSession(context, "Completed")
                                requestInFlight = false
                                return@let
                            }
                            SessionState.ERROR -> {
                                Log.e("BackendProcessing", "🚨 Session error state - ending session")
                                val terminalStatus = statusForTerminalReason(failureReason.ifBlank { stateStr })
                                emitPhase0Outcome(
                                    context = context,
                                    item = verificationStatus?.targetItem ?: originalInputText,
                                    status = terminalStatus,
                                    notes = noteForTerminalReason(terminalStatus, "session_error")
                                )
                                endScreenCaptureSession(context, "Error state")
                                requestInFlight = false
                                return@let
                            }
                            SessionState.SUMMARY_AND_EDIT -> {
                                Log.d("BackendProcessing", "📝 Summary and edit state - ending session")
                                emitPhase0Outcome(
                                    context = context,
                                    item = originalInputText,
                                    status = ItemOutcomeStatus.MISCLICK,
                                    notes = "summary_and_edit"
                                )
                                endScreenCaptureSession(context, "Summary and edit")
                                requestInFlight = false
                                    return@let
                                }
                                null -> Log.w("BackendProcessing", "⚠️ Unknown session state: $stateStr")
                                else -> Log.d("BackendProcessing", "🔄 Continuing session - state: $stateStr")
                            }
                            
                            // Handle verification result if this is a verification step
                            if (verificationStatus?.isVerificationStep == true) {
                                handleVerificationResult(
                                    context, 
                                    verificationStatus, 
                                    jsonObject.optBoolean("task_completed", false),
                                    sessionState,
                                    sessionContext
                                )
                                // If verification completed or failed, don't process action
                                if (jsonObject.optBoolean("task_completed", false) || 
                                    verificationStatus.verificationFailed) {
                                    requestInFlight = false
                                    return@let
                                }
                            }
                            
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
                                    updateFlowStatus(context, "Waiting for next step")
                                    requestInFlight = false
                                    // buttonHighlightService?.clearHighlight() - service removed
                                }
                                else -> {
                                    val actionId = recommendedAction.getString("action_id")
                                    val actionType = recommendedAction.getString("action_type")
                                    val actionTarget = recommendedAction.getString("action_target")
                                    val confidenceScore = recommendedAction.getDouble("confidence")
                                    val reasoning = recommendedAction.optString("reasoning", "No reasoning provided")
                                    val actionTextToType = recommendedAction.optString("text_to_type", textToType)
                                    
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
                                    Log.d("BackendProcessing", "  Action ID: $actionId")
                                    Log.d("BackendProcessing", "  Action Type: $actionType")
                                    Log.d("BackendProcessing", "  Action Target: $actionTarget")
                                    Log.d("BackendProcessing", "  Confidence Score: $confidenceScore (min required: $minConfidence)")
                                    Log.d("BackendProcessing", "  Reasoning: $reasoning")
                                    Log.d("BackendProcessing", "  Element Text: '$text'")
                                    Log.d("BackendProcessing", "  Resource ID: '$resourceId'")
                                    Log.d("BackendProcessing", "  Class Name: '$className'")
                                    Log.d("BackendProcessing", "  Content Description: '$contentDescription'")
                                    updateFlowStatus(
                                        context,
                                        statusForAction(actionType, actionTarget, currentActionNumber, maxActions)
                                    )
                                    val responseBoundaryText = listOf(
                                        actionType,
                                        actionTarget,
                                        reasoning,
                                        text,
                                        resourceId,
                                        className,
                                        contentDescription,
                                        hierarchyPath,
                                        actionTextToType,
                                        stateStr,
                                        debugNotes,
                                        jsonObject.optString("failure_reason", "")
                                    ).joinToString(" ")
                                    val isCheckoutBoundary = isCheckoutBoundaryDetected(
                                        responseBoundaryText,
                                        currentTreeData
                                    )
                                    if (backendAppName == "Blinkit" && isCheckoutBoundary) {
                                        Log.w("BackendProcessing", "🚫 Checkout/payment boundary detected in response - ending flow for safety.")
                                        emitPhase0Outcome(
                                            context = context,
                                            item = verificationStatus?.targetItem ?: originalInputText,
                                            status = if (verificationStatus?.itemFoundInCart == true) {
                                                ItemOutcomeStatus.SKIPPED
                                            } else {
                                                ItemOutcomeStatus.MISCLICK
                                            },
                                            qtyAdded = if (verificationStatus?.itemFoundInCart == true) 1 else 0,
                                            notes = "checkout_boundary"
                                        )
                                        requestInFlight = false
                                        endScreenCaptureSession(context, "Checkout boundary")
                                        stopActionSequence()
                                        hasEmittedItemOutcome = true
                                        return@onResponse
                                    }
                                    
                                    // Log backend response with coordinate details
                                    val statusBarHeight = ScreenMetrics.getStatusBarHeight(context)
                                    
                                    // Use fallbackCoordinates if available, otherwise create from boundingBox center
                                    val coordsForLogging = fallbackCoordinates ?: boundingBox?.let {
                                        JSONObject().apply {
                                            put("x", it.optInt("x", 0) + it.optInt("width", 0) / 2)
                                            put("y", it.optInt("y", 0) + it.optInt("height", 0) / 2)
                                        }
                                    }
                                    
                                    val adjustedCoords = if (coordsForLogging != null) {
                                        ScreenMetrics.adjustCoordinatesForScreen(
                                            coordsForLogging.optInt("x", 0), 
                                            coordsForLogging.optInt("y", 0), 
                                            context
                                        )
                                    } else null
                                    
                                    DebugLogger.logBackendResponse(
                                        actionId = actionId,
                                        actionType = actionType,
                                        actionTarget = actionTarget,
                                        confidence = confidenceScore,
                                        boundingBox = boundingBox,
                                        coordinates = coordsForLogging,
                                        statusBarHeight = statusBarHeight,
                                        adjustedCoordinates = adjustedCoords
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
                                    
                                    // Log.d("BackendProcessing", """
                                    //     Screen Information:
                                    //     - Screenshot Width: ${bitmap.width}
                                    //     - Screenshot Height: ${bitmap.height}
                                    //     - Screen Width: ${displayMetrics.widthPixels}
                                    //     - Screen Height: ${displayMetrics.heightPixels}
                                    //     - Screen Density: ${displayMetrics.density}
                                    // """.trimIndent())

                                    // Execute the recommended action
                                    ensureActionExecutor(context)
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
                                        
                                        // Update session context with current action
                                        sessionContext?.let { ctx ->
                                            val actionResult = ActionResult(
                                                actionId = actionId,
                                                status = "executing",
                                                notes = "Action being executed"
                                            )
                                            ctx.lastActionResult = actionResult
                                        }
                                                        
                                        val actionSuccess = actionExecutor!!.executeAction(recommendedAction, minConfidence)
                                        Log.d("BackendProcessing", "🎯 ACTION EXECUTION RESULT: $actionSuccess")
                                        updateFlowStatus(
                                            context,
                                            if (actionSuccess) {
                                                "Done: ${statusForAction(actionType, actionTarget, currentActionNumber, maxActions).substringBefore(" (")}"
                                            } else {
                                                "Failed: ${statusForAction(actionType, actionTarget, currentActionNumber, maxActions).substringBefore(" (")}"
                                            }
                                        )
                                        
                                        // Update session context with result
                                        sessionContext?.let { ctx ->
                                            // Determine if this was a search-related action
                                            val isSearchAction = actionTarget.contains("search", ignoreCase = true) || 
                                                               actionTarget.contains("action_bar_root", ignoreCase = true)
                                            val isTypeAction = actionType.equals("type", ignoreCase = true)
                                            val isScrollAction = actionType.equals("scroll", ignoreCase = true) ||
                                                actionType.equals("swipe", ignoreCase = true)
                                            val isAddAction = actionTarget.contains("add", ignoreCase = true) ||
                                                text.equals("ADD", ignoreCase = true) ||
                                                contentDescription.contains("add", ignoreCase = true)
                                            val isViewCartAction = actionTarget.contains("view cart", ignoreCase = true) ||
                                                actionTarget.contains("cart", ignoreCase = true) ||
                                                text.contains("view cart", ignoreCase = true) ||
                                                contentDescription.contains("view cart", ignoreCase = true)
                                            val noteContext = listOf(
                                                actionId,
                                                actionTarget,
                                                text,
                                                resourceId,
                                                contentDescription,
                                                hierarchyPath
                                            ).joinToString(" ")
                                            val isProductOpenAction = listOf(
                                                "open product",
                                                "open_product",
                                                "product_open",
                                                "product page",
                                                "product details",
                                                "product card",
                                                "product tile",
                                                "product item",
                                                "product result",
                                                "click product",
                                                "select product"
                                            ).any { noteContext.contains(it, ignoreCase = true) }
                                            
                                            val notes = when {
                                                actionSuccess && isTypeAction -> "search_query_typed"
                                                actionSuccess && isAddAction -> "add_clicked"
                                                actionSuccess && isViewCartAction -> "view_cart_clicked"
                                                actionSuccess && isProductOpenAction -> "product_open_clicked"
                                                actionSuccess && isSearchAction -> "search_field_clicked"
                                                actionSuccess && isScrollAction -> "results_scrolled"
                                                actionSuccess -> actionType
                                                else -> "Action failed"
                                            }
                                            
                                            val finalResult = ActionResult(
                                                actionId = actionId,
                                                status = if (actionSuccess) "success" else "fail",
                                                notes = notes
                                            )
                                            ctx.lastActionResult = finalResult
                                            ctx.actionHistory.add(finalResult.toJson())
                                        }

                                        when {
                                            actionSuccess && actionType.equals("type", ignoreCase = true) ->
                                                Log.i(TAG, "BLINKIT_SEARCH_TEXT_ENTERED: $text")
                                            actionSuccess && actionTarget.contains("search", ignoreCase = true) ->
                                                Log.i(TAG, "BLINKIT_SEARCH_BOX_FOUND")
                                            actionSuccess && actionTarget.contains("add", ignoreCase = true) ->
                                                Log.i(TAG, "BLINKIT_ADD_TO_CART_CLICKED")
                                        }
                                        
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
                                                Log.d("BackendProcessing", "🔍 DEBUG: verification_required=$verificationRequired")
                                                
                                                if (isCompleted || taskCompleted) {
                                                    Log.d("BackendProcessing", "🏁 TASK COMPLETED! Stopping action sequence.")
                                                    Log.i(TAG, "FLOW_SUCCESS: target=$originalInputText")
                                                    emitPhase0Outcome(
                                                        context = context,
                                                        item = verificationStatus?.targetItem ?: originalInputText,
                                                        status = ItemOutcomeStatus.SUCCESS,
                                                        matchedSku = verificationStatus?.targetItem ?: "",
                                                        qtyAdded = 1,
                                                        notes = if (taskCompleted) "task_completed" else "is_completed"
                                                    )
                                                    requestInFlight = false
                                                    stopActionSequence()
                                                } else if (verificationRequired) {
                                                    // Verification flow: automatically capture next screenshot for verification
                                                    Log.d("BackendProcessing", "🔍 VERIFICATION REQUIRED - Triggering automatic verification flow")
                                                    Log.d("BackendProcessing", "🔍 Target item: ${verificationStatus?.targetItem}")
                                                    updateFlowStatus(context, "Checking cart")
                                                    
                                                    // Update session context to indicate verification pending
                                                    sessionContext?.let { ctx ->
                                                        val verificationResult = ActionResult(
                                                            actionId = actionId,
                                                            status = "success",
                                                            notes = "item_added_awaiting_verification"
                                                        )
                                                        ctx.lastActionResult = verificationResult
                                                    }
                                                    
                                                    // Wait for cart UI to update, then trigger next screenshot
                                                    Log.d("BackendProcessing", "🔍 Waiting 1.5s for cart UI to update...")
                                                    requestInFlight = false
                                                    triggerNextAction()
                                                } else {
                                                    Log.d("BackendProcessing", "🔍 DEBUG: Task not completed, triggering next action...")
                                                    // Trigger the next action in the sequence
                                                    requestInFlight = false
                                                    triggerNextAction()
                                                }
                                            } else {
                                                // Single action mode - just add delay
                                                requestInFlight = false
                                                Thread.sleep(1000)
                                            }
                                         } else {
                                             Log.w("BackendProcessing", "❌ Action execution failed")
                                             Log.e(TAG, "FLOW_FAILED: reason=action_execution_failed")
                                             emitPhase0Outcome(
                                                 context = context,
                                                 item = originalInputText,
                                                 status = ItemOutcomeStatus.MISCLICK,
                                                 notes = "action_execution_failed"
                                             )
                                             requestInFlight = false
                                             if (isActionSequenceActive) {
                                                // Log.w("BackendProcessing", "Action failed in sequence - stopping sequence")
                                                stopActionSequence()
                                            }
                                        }
                                    } else {
                                        Log.w("BackendProcessing", "ActionExecutor not available - cannot execute action")
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Enable Beta accessibility service before ordering.",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        endScreenCaptureSession(context, "Accessibility service disabled")
                                        emitPhase0Outcome(
                                            context = context,
                                            item = originalInputText,
                                            status = ItemOutcomeStatus.SKIPPED,
                                            notes = "accessibility_service_disabled"
                                        )
                                        requestInFlight = false
                                        stopActionSequence()
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
                            requestInFlight = false
                            handleBackendError(context, "JSON parsing error", e.message ?: "Unknown error", sessionContext)
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
                    requestInFlight = false
                    emitPhase0Outcome(
                        context = context,
                        item = originalInputText,
                        status = ItemOutcomeStatus.TIMEOUT,
                        notes = "upload_timeout"
                    )
                } else {
                    Log.w("UploadRetry", "Attempt $attempt failed, retrying...")
                }
            }
        }
    }
    
    // Handle backend errors with retry logic for transient errors
    private fun handleBackendError(context: Context, reason: String, details: String, sessionContext: SessionContext?) {
        Log.e("BackendProcessing", "💥 Backend Error: $reason - $details")
        Log.e(TAG, "FLOW_FAILED: reason=$reason")
        
        // Check if this is a transient error that can be retried
        val isTransientError = reason.contains("missing_screenshot") || reason.contains("timeout") || reason.contains("network")
        
        if (isTransientError && sessionContext != null) {
            val screenService = (context.applicationContext as? MyApplication)?.getScreenCaptureService()
            if ((screenService?.getRetryAttempts() ?: 0) < 1) {
                Log.d("BackendProcessing", "🔄 Attempting retry for transient error")
                screenService?.incrementRetryAttempts()
                
                // Trigger a fresh screenshot after a delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    screenService?.triggerScreenshot()
                }, 1000) // 1 second delay
                
                return
            } else {
                Log.w("BackendProcessing", "❌ Max retries exceeded, giving up")
            }
        }

        val terminalStatus = if (isTransientError) {
            ItemOutcomeStatus.TIMEOUT
        } else {
            statusForTerminalReason("$reason $details")
        }
        emitPhase0Outcome(
            context = context,
            item = originalInputText,
            status = terminalStatus,
            notes = noteForTerminalReason(terminalStatus, "backend_error")
        )
        
        // End session on error
        endScreenCaptureSession(context, "Error: $reason")
        
        // Show user message
        android.widget.Toast.makeText(context, "Something went wrong. Please try again.", android.widget.Toast.LENGTH_LONG).show()
    }
    
    // Handle verification result (API v1.1)
    private fun handleVerificationResult(
        context: Context, 
        verificationStatus: VerificationStatus,
        taskCompleted: Boolean,
        sessionState: SessionState?,
        sessionContext: SessionContext?
    ) {
        Log.d("BackendProcessing", "🔍 Handling verification result...")
        
        // SUCCESS: Item found in cart and task completed
        if (taskCompleted && verificationStatus.itemFoundInCart == true) {
            Log.d("BackendProcessing", "✅ VERIFICATION SUCCESSFUL!")
            Log.d("BackendProcessing", "✅ Item '${verificationStatus.targetItem}' verified in cart")
            Log.d("BackendProcessing", "✅ Details: ${verificationStatus.verificationDetails}")
            Log.i(TAG, "BLINKIT_CART_INCREMENT_CONFIRMED")
            Log.i(TAG, "FLOW_SUCCESS: target=${verificationStatus.targetItem}")
            emitPhase0Outcome(
                context = context,
                item = verificationStatus.targetItem ?: originalInputText,
                status = ItemOutcomeStatus.SUCCESS,
                matchedSku = verificationStatus.targetItem ?: "",
                qtyAdded = 1,
                notes = "verified_in_cart"
            )
            
            // Show success message to user
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, 
                    "✅ ${verificationStatus.targetItem} verified in cart!", 
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            
            // End session successfully
            endScreenCaptureSession(context, "Verification successful")
            stopActionSequence()
            return
        }
        
        // FAILURE: Item not found in cart
        if (verificationStatus.verificationFailed) {
            Log.e("BackendProcessing", "❌ VERIFICATION FAILED!")
            Log.e("BackendProcessing", "❌ Item '${verificationStatus.targetItem}' NOT found in cart")
            Log.e("BackendProcessing", "❌ Details: ${verificationStatus.verificationDetails}")
            Log.e(TAG, "FLOW_FAILED: reason=cart_verification_failed")
            val terminalStatus = statusForTerminalReason(verificationStatus.verificationDetails ?: "cart_verification_failed")
            emitPhase0Outcome(
                context = context,
                item = verificationStatus.targetItem ?: originalInputText,
                status = terminalStatus,
                matchedSku = verificationStatus.targetItem ?: "",
                notes = noteForTerminalReason(
                    terminalStatus,
                    verificationStatus.verificationDetails ?: "cart_verification_failed"
                )
            )
            
            // Show failure message to user
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, 
                    "⚠️ Item not found in cart. Returning to home to retry...", 
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            
            // Handle retry action if specified
            if (verificationStatus.retryAction == "return_to_home") {
                Log.d("BackendProcessing", "🔄 Retry action: Returning to home")
                // End current session - user can restart manually
                endScreenCaptureSession(context, "Verification failed - retry needed")
                stopActionSequence()
                
                // Note: Automatic return to home and retry would require additional
                // navigation logic that depends on your app structure
                // For now, we just end the session and notify the user
            }
            return
        }
        
        // STILL VERIFYING: AI is navigating to cart or checking cart contents
        if (sessionState == SessionState.VERIFYING_CHECKOUT) {
            Log.d("BackendProcessing", "🔍 Still verifying - AI may be navigating to cart")
            
            // Show status to user
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, 
                    "🔍 Verifying ${verificationStatus.targetItem} in cart...", 
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            
            // Continue with action sequence - the backend may have a recommended action
            // to navigate to cart view
            return
        }
        
        Log.d("BackendProcessing", "🔍 Verification step processed - waiting for next action")
    }
}
