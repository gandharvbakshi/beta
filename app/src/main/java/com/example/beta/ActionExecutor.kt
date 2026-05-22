package com.example.beta

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ActionExecutor(private val accessibilityService: AccessibilityService) {
    
    companion object {
        private const val TAG = "ActionExecutor"
        private const val BLINKIT_PACKAGE = "com.grofers.customerapp"
        private const val SWIGGY_INSTAMART_PACKAGE = "in.swiggy.android.instamart"
        private const val SWIGGY_SEARCH_FOCUS_MARK_MAX_AGE_MS = 120000L
        private val SUPPORTED_COMMERCE_PACKAGES = setOf(
            BLINKIT_PACKAGE,
            SWIGGY_INSTAMART_PACKAGE,
        )
        private val COMMERCE_SEARCH_FIELD_VIEW_IDS = listOf(
            "in.swiggy.android.instamart:id/et_search_query_v2",
        )
    }

    private data class TapAdjustment(
        val x: Int,
        val y: Int,
        val overlayDeflection: Int
    )

    private enum class QuantityStepDirection {
        INCREMENT,
        DECREMENT
    }

    private var lastSwiggyTopSearchFocusTapAtMs: Long = 0L

    private class NodeScanBudget(
        private val maxNodes: Int = 180,
        maxDurationMs: Long = 500
    ) {
        private val deadlineMs = System.currentTimeMillis() + maxDurationMs
        var visited: Int = 0

        fun shouldStop(): Boolean {
            return visited >= maxNodes || System.currentTimeMillis() >= deadlineMs
        }

        fun markVisited(): Boolean {
            if (shouldStop()) return false
            visited += 1
            return true
        }
    }

    private fun newQuickNodeScanBudget() = NodeScanBudget(maxNodes = 90, maxDurationMs = 250)
    private fun newDefaultNodeScanBudget() = NodeScanBudget(maxNodes = 450, maxDurationMs = 800)
    
    private fun hasRequiredPermissions(): Boolean {
        val serviceInfo = accessibilityService.serviceInfo
        val hasGestureCapability = serviceInfo?.capabilities?.and(
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
        ) != 0
        
        val hasRetrieveCapability = serviceInfo?.capabilities?.and(
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
        ) != 0
        
        Log.d(TAG, "Permission check - Gestures: $hasGestureCapability, Retrieve: $hasRetrieveCapability")
        return hasGestureCapability && hasRetrieveCapability
    }

    private fun containsAnrText(
        node: AccessibilityNodeInfo?,
        budget: NodeScanBudget = NodeScanBudget(maxNodes = 160, maxDurationMs = 250)
    ): Boolean {
        if (node == null || !budget.markVisited()) return false

        val text = node.text?.toString().orEmpty().lowercase()
        val description = node.contentDescription?.toString().orEmpty().lowercase()

        if (text.contains("isn't responding", ignoreCase = true) ||
                text.contains("not responding", ignoreCase = true) ||
                description.contains("isn't responding", ignoreCase = true) ||
                description.contains("not responding", ignoreCase = true)
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            if (containsAnrText(child, budget)) {
                return true
            }
        }
        return false
    }

    private fun handleAnrByClickingWait(): Boolean {
        val rootNode = allAvailableWindowRoots().firstOrNull { root ->
            containsAnrText(root, NodeScanBudget(maxNodes = 160, maxDurationMs = 250))
        } ?: return false
        val waitNode = findNodeByExactText(
            rootNode,
            "Wait",
            NodeScanBudget(maxNodes = 80, maxDurationMs = 250)
        )
            ?: return false
        val clickableWaitNode = findClickableSelfOrAncestor(waitNode) ?: waitNode
        val clicked = clickableWaitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.d(TAG, "Tapped ANR 'Wait' button")
            Thread.sleep(350)
        } else {
            Log.w(TAG, "Failed to tap ANR 'Wait' button")
        }
        return clicked
    }

    private fun handleBlockingSystemDialogs(): Boolean {
        val permissionHandled = handleRuntimePermissionDialogByDenying()
        val anrHandled = handleAnrByClickingWait()
        return permissionHandled || anrHandled
    }

    private fun handleRuntimePermissionDialogByDenying(): Boolean {
        val rootNode = allAvailableWindowRoots().firstOrNull { root ->
            root.packageName?.toString() == "com.google.android.permissioncontroller" &&
                containsRuntimePermissionDialogText(root, NodeScanBudget(maxNodes = 180, maxDurationMs = 250))
        } ?: return false

        val denyNode = findNodeByResourceId(
            rootNode,
            "com.android.permissioncontroller:id/permission_deny_button",
            NodeScanBudget(maxNodes = 120, maxDurationMs = 250)
        ) ?: findPermissionDenyNode(rootNode, NodeScanBudget(maxNodes = 140, maxDurationMs = 250))
            ?: return false

        val clickableDenyNode = findClickableSelfOrAncestor(denyNode) ?: denyNode
        val clicked = clickableDenyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.d(TAG, "Dismissed Android runtime permission dialog with deny action")
            Thread.sleep(700)
        } else {
            Log.w(TAG, "Failed to deny Android runtime permission dialog")
        }
        return clicked
    }

    private fun containsRuntimePermissionDialogText(
        node: AccessibilityNodeInfo?,
        budget: NodeScanBudget
    ): Boolean {
        if (node == null || !budget.markVisited()) return false

        val text = node.text?.toString().orEmpty().lowercase()
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        val viewId = node.viewIdResourceName.orEmpty()
        val combined = "$text $description"
        if (
            viewId == "com.android.permissioncontroller:id/permission_message" ||
            (combined.contains("allow") && combined.contains("to ") && combined.contains("?")) ||
            combined.contains("permission")
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            if (containsRuntimePermissionDialogText(child, budget)) {
                return true
            }
        }
        return false
    }

    private fun findPermissionDenyNode(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        val text = node.text?.toString().orEmpty().lowercase()
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        val combined = "$text $description"
        if (
            combined.contains("deny") ||
            combined.contains("don't allow") ||
            combined.contains("dont allow") ||
            (combined.contains("don") && combined.contains("allow"))
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findPermissionDenyNode(child, budget)
            if (result != null) return result
        }
        return null
    }

    fun executeAction(recommendedAction: JSONObject, minConfidence: Double = 0.7): Boolean {
        return try {
            val actionType = recommendedAction.getString("action_type")
            val actionTarget = recommendedAction.getString("action_target")
            val confidenceScore = recommendedAction.getDouble("confidence")
            
            Log.d(TAG, "Executing action: $actionType on $actionTarget (confidence: $confidenceScore, min required: $minConfidence)")
            
            // Check if we have required permissions
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing required accessibility permissions")
                return false
            }
            
            // Use backend-defined confidence threshold
            if (confidenceScore < minConfidence) {
                Log.w(TAG, "Action confidence too low ($confidenceScore < $minConfidence), skipping execution")
                return false
            }

            // Keep system-owned blocker dialogs from trapping the commerce app.
            handleBlockingSystemDialogs()

            // Add a small delay to ensure UI is stable
            Thread.sleep(500)
            
            val firstAttempt = when (actionType.lowercase()) {
                "click" -> performClick(recommendedAction)
                "scroll" -> performScroll(recommendedAction)
                "type" -> performType(recommendedAction)
                "wait" -> {
                    Thread.sleep(1500)
                    true
                }
                "back", "press_back" -> accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "swipe" -> performSwipe(recommendedAction)
                "long_press" -> performLongPress(recommendedAction)
                "double_tap" -> performDoubleTap(recommendedAction)
                else -> {
                    Log.w(TAG, "Unknown action type: $actionType")
                    false
                }
            }
            if (firstAttempt) {
                return firstAttempt
            }

            if (!handleBlockingSystemDialogs()) {
                return false
            }
            Thread.sleep(300)
            when (actionType.lowercase()) {
                "click" -> performClick(recommendedAction)
                "scroll" -> performScroll(recommendedAction)
                "type" -> performType(recommendedAction)
                "wait" -> {
                    Thread.sleep(1500)
                    true
                }
                "back", "press_back" -> accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "swipe" -> performSwipe(recommendedAction)
                "long_press" -> performLongPress(recommendedAction)
                "double_tap" -> performDoubleTap(recommendedAction)
                else -> {
                    Log.w(TAG, "Unknown action type: $actionType")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action: ${e.message}")
            false
        }
    }
    
    private fun performClick(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform click action")
        
        // Check if this is a search bar or ADD button - use appropriate method
        val actionTarget = recommendedAction.optString("action_target", "")
        val isSearchBar = actionTarget.contains("search", ignoreCase = true)
        val isOpenCartAction = actionTarget.contains("cart", ignoreCase = true)
            && (actionTarget.contains("view cart", ignoreCase = true) || actionTarget.contains("open cart", ignoreCase = true))
        val isAddButton = isProductAddButtonAction(actionTarget)

        if (isAddButton && isSwiggyCheckoutOrPaymentBoundaryActive()) {
            Log.w(TAG, "Refusing Swiggy ADD action because a checkout/payment boundary is foreground")
            return false
        }
        
        // For search bars, try coordinate-based clicking FIRST (more reliable)
        if (isSearchBar) {
            if (!ensureCommerceForegroundForSearchAction(recommendedAction)) {
                Log.w(TAG, "Search action refused because no supported commerce app is foreground")
                return false
            }
            if (isSwiggyForeground() && tapSwiggyTopSearchFieldForFocus()) {
                Log.d(TAG, "Focused Swiggy product search using fresh foreground state")
                return true
            }
            val coordinates = recommendedAction.optJSONObject("coordinates")
            if (coordinates != null) {
                Log.d(TAG, "Search bar detected - using coordinate-based click as primary method")
                val success = performClickByCoordinates(recommendedAction)
                if (success) {
                    if (!confirmCommerceForegroundAfterSearchClick()) {
                        return false
                    }
                    Log.d(TAG, "Coordinate click successful for search bar")
                    return typeAfterSearchClickIfRequested(recommendedAction, true)
                } else {
                    Log.d(TAG, "Coordinate click failed, falling back to accessibility")
                }
            }
        }

        if (isOpenCartAction) {
            val coordinates = recommendedAction.optJSONObject("coordinates")
            val openCartAttempts = 3
            for (attempt in 1..openCartAttempts) {
                Log.d(TAG, "Cart-opening action attempt $attempt/$openCartAttempts")
                val openCartNode = findOpenCartNodeByText()
                if (openCartNode) {
                    Log.d(TAG, "Direct accessibility click successful for cart-opening action")
                    return true
                }

                if (coordinates != null) {
                    Log.d(TAG, "Cart-opening action detected - trying raw coordinate click")
                    val rawSuccess = performRawCoordinateClick(recommendedAction)
                    if (rawSuccess) {
                        Log.d(TAG, "Raw coordinate click successful for cart-opening action")
                        return true
                    }
                    Log.d(TAG, "Raw coordinate click failed for cart-opening action, trying scaled coordinate path")
                    val success = performClickByCoordinates(recommendedAction)
                    if (success) {
                        Log.d(TAG, "Coordinate click successful for cart-opening action")
                        return true
                    }
                    Log.d(TAG, "Coordinate click failed for cart-opening action")
                }

                if (attempt < openCartAttempts) {
                    Thread.sleep(250)
                }
            }
        }
        
        // For ADD buttons, use coordinate validation with retry logic
        if (isAddButton) {
            Log.d(TAG, "ADD button detected - using coordinate validation approach")
            val rootNode = accessibilityService.rootInActiveWindow
            val hasCoordinates = recommendedAction.optJSONObject("coordinates") != null ||
                recommendedAction.optJSONObject("fallback_coordinates") != null
            if (hasCoordinates) {
                val coordinateSuccess = performAddButtonClickWithValidation(recommendedAction)
                if (coordinateSuccess) {
                    return true
                }
                Log.w(TAG, "ADD coordinate validation failed; trying accessibility fallback")
                if (rootNode != null && isVariantAddAction(recommendedAction)) {
                    val modalAdd = findModalAddButtonElement(rootNode, recommendedAction)
                    if (modalAdd != null) {
                        Log.d(TAG, "Found modal ADD button via accessibility tree; clicking nearest match")
                        val clicked = modalAdd.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            return true
                        }
                        Log.w(TAG, "Modal ADD ACTION_CLICK failed; giving up after coordinate validation")
                    }
                }
                return false
            }

            if (rootNode != null && isVariantAddAction(recommendedAction)) {
                val modalAdd = findModalAddButtonElement(rootNode, recommendedAction)
                if (modalAdd != null) {
                    Log.d(TAG, "Found modal ADD button via accessibility tree; clicking nearest match")
                    val clicked = modalAdd.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        return true
                    }
                    Log.w(TAG, "Modal ADD ACTION_CLICK failed; falling back to coordinate validation")
                }
            }
            return performAddButtonClickWithValidation(recommendedAction)
        }
        
        // Try to find element using multiple methods
        val targetNode = findTargetElement(recommendedAction)
        
        return if (targetNode != null) {
            Log.d(TAG, "Found target element, performing click")
            Log.d(TAG, "Element details - Clickable: ${targetNode.isClickable}, Enabled: ${targetNode.isEnabled}, Visible: ${targetNode.isVisibleToUser}")
            
            // Try multiple click strategies
            var success = false
            
            // Strategy 1: Direct click on the element
            if (targetNode.isClickable) {
                success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Direct click result: $success")
            }
            
            // Strategy 2: If direct click failed, try clicking on parent
            if (!success) {
                val clickableParent = findClickableParent(targetNode)
                if (clickableParent != null) {
                    Log.d(TAG, "Trying click on parent element")
                    success = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Parent click result: $success")
                }
            }
            
            // Strategy 3: If still failed, try focusing then clicking
            if (!success) {
                Log.d(TAG, "Trying focus then click")
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100) // Small delay after focus
                success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Focus + click result: $success")
            }
            
            // Strategy 4: If still failed, try coordinate-based clicking
            if (!success) {
                Log.d(TAG, "All accessibility clicks failed, trying coordinate-based click")
                success = performClickByCoordinates(recommendedAction)
                Log.d(TAG, "Coordinate click result: $success")
            }
            
            Log.d(TAG, "Final click action result: $success")
            typeAfterSearchClickIfRequested(recommendedAction, success)
        } else {
            Log.w(TAG, "Target element not found, trying fallback coordinates")
            val coordinateSuccess = performClickByCoordinates(recommendedAction)
            Log.d(TAG, "Fallback coordinate click result: $coordinateSuccess")
            return typeAfterSearchClickIfRequested(recommendedAction, coordinateSuccess)
        }
    }

    private fun typeAfterSearchClickIfRequested(recommendedAction: JSONObject, clickSuccess: Boolean): Boolean {
        if (!clickSuccess) return false

        val actionTarget = recommendedAction.optString("action_target", "")
        val textToType = recommendedAction.optString("text_to_type", "")
        if (!actionTarget.contains("search", ignoreCase = true)) return true

        if (isSwiggyForeground()) {
            val trustedFocus = waitForSearchFieldFocus(400) || waitForSwiggySafeKeyboardAfterSearchClick(4500)
            if (trustedFocus) {
                markRecentSwiggySearchFocus("search click action produced search focus or keyboard")
            } else {
                preserveOrClearRecentSwiggySearchFocus("search click did not produce new trusted focus")
                Log.d(TAG, "Swiggy search click did not produce trusted search focus; not marking safe keyboard entry")
            }
        } else {
            markRecentSwiggySearchFocus("search click action succeeded")
        }
        if (textToType.isBlank()) return true

        if (!waitForSearchFieldFocus(500)) {
            Log.d(TAG, "Search field not focused after click; attempting accessibility fallback")
            if (!focusSearchFieldForTyping(recommendedAction)) {
                Log.w(TAG, "Search field focus fallback failed after click")
                return false
            }
        }

        val typed = typeTextIntoFocusedField(
            textToType,
            waitForFocusMs = 1500,
            submitIme = false,
            dismissKeyboard = false,
            requireSearchField = true
        )
        Log.d(TAG, "Search click follow-up type '$textToType' result: $typed")
        return typed
    }

    private fun isProductAddButtonAction(actionTarget: String): Boolean {
        return CommerceActionClassifier.isProductAddButtonAction(actionTarget)
    }

    private fun performRawCoordinateClick(
        recommendedAction: JSONObject,
        callback: AccessibilityService.GestureResultCallback? = null
    ): Boolean {
        Log.d(TAG, "Attempting raw coordinate click")

        val coordinates = recommendedAction.optJSONObject("coordinates") ?: return false
        val x = coordinates.optInt("x", -1)
        val y = coordinates.optInt("y", -1)
        if (x < 0 || y < 0) {
            return false
        }

        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path,
                    0,
                    100
                )
            )
            .build()

        val gestureCallback = callback ?: object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "Raw coordinate click gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "Raw coordinate click gesture cancelled at ($x, $y)")
            }
        }

        val success = accessibilityService.dispatchGesture(gesture, gestureCallback, null)

        if (success) {
            Log.d(TAG, "Raw coordinate click dispatched successfully at ($x, $y)")
            return true
        }

        Log.w(TAG, "Raw coordinate click dispatch failed at ($x, $y)")
        return false
    }

    private fun findOpenCartNodeByText(): Boolean {
        val labels = listOf("View cart", "View Cart", "Go to cart", "View items in cart")
        var bestNode: AccessibilityNodeInfo? = null
        var bestY = Int.MIN_VALUE

        val roots = mutableListOf<AccessibilityNodeInfo>()
        accessibilityService.rootInActiveWindow?.let { roots.add(it) }
        try {
            accessibilityService.windows?.forEach { window ->
                try {
                    val windowRoot = window?.root
                    if (windowRoot != null) {
                        roots.add(windowRoot)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read accessibility window root: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate accessibility windows: ${e.message}")
        }

        if (roots.isEmpty()) {
            return false
        }

        fun traverse(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty().lowercase()
            val description = node.contentDescription?.toString().orEmpty().lowercase()
            if (node.isVisibleToUser && node.isEnabled && labels.any { label ->
                    text.contains(label.lowercase()) || description.contains(label.lowercase())
                }) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val score = if (!bounds.isEmpty) bounds.centerY() else bounds.top
                if (score > bestY) {
                    bestY = score
                    bestNode = node
                }
            }

            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i) ?: continue
                    traverse(child)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed traversing accessibility child node: ${e.message}")
                }
            }
        }

        roots.forEach { root ->
            try {
                traverse(root)
            } catch (e: Exception) {
                Log.w(TAG, "Failed traversing accessibility root: ${e.message}")
            }
        }

        val targetNode = bestNode ?: return false
        val clickableNode = findClickableSelfOrAncestor(targetNode) ?: targetNode
        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.d(TAG, "Tapped direct View cart node at centerY=$bestY")
        } else {
            Log.w(TAG, "Failed to tap direct View cart node at centerY=$bestY")
        }
        return clicked
    }
    
    private fun performScroll(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform scroll action")
        
        val targetNode = findTargetElement(recommendedAction)
        if (targetNode == null) {
            Log.w(TAG, "Target element not found for scroll, trying coordinate gesture fallback")
            return performCoordinateScroll(recommendedAction)
        }
        
        // Check if the node is scrollable
        Log.d(TAG, "Target element scrollable check: ${targetNode.isScrollable}")
        Log.d(TAG, "Target element scroll actions available: ${targetNode.actionList}")
        
        if (!targetNode.isScrollable) {
            Log.w(TAG, "Target element is not scrollable, but trying scroll actions anyway")
        }
        
        // Try different scroll actions
        val scrollActions = listOf(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
        
        for (action in scrollActions) {
            val actionName = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
                else -> "UNKNOWN"
            }
            
            Log.d(TAG, "Trying scroll action: $actionName")
            val success = targetNode.performAction(action)
            Log.d(TAG, "Scroll action $actionName result: $success")
            
            if (success) {
                Log.d(TAG, "Scroll action succeeded with $actionName")
                return true
            }
        }
        
        Log.w(TAG, "All accessibility scroll actions failed, trying gesture scroll")
        
        // Fallback: Try gesture-based scrolling
        val gestureSuccess = performGestureScroll(recommendedAction)
        if (gestureSuccess) {
            return true
        }

        Log.w(TAG, "Target gesture scroll failed, trying full-screen coordinate scroll")
        return performCoordinateScroll(recommendedAction)
    }
    
    private fun performGestureScroll(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting gesture-based scroll")
        
        val targetNode = findTargetElement(recommendedAction)
        if (targetNode == null) {
            Log.w(TAG, "Target element not found for gesture scroll")
            return false
        }
        
        // Get the bounds of the scrollable element
        val bounds = android.graphics.Rect()
        targetNode.getBoundsInScreen(bounds)
        
        if (bounds.isEmpty) {
            Log.w(TAG, "Target element has empty bounds")
            return false
        }
        
        // Create a vertical scroll gesture (swipe up to scroll down)
        val path = android.graphics.Path()
        val startY = bounds.centerY() + bounds.height() / 4
        val endY = bounds.centerY() - bounds.height() / 4
        val centerX = bounds.centerX()
        
        path.moveTo(centerX.toFloat(), startY.toFloat())
        path.lineTo(centerX.toFloat(), endY.toFloat())
        
        val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
        gestureBuilder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 500))
        
        val gesture = gestureBuilder.build()
        
        return accessibilityService.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "Gesture scroll completed successfully")
            }
            
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "Gesture scroll was cancelled")
            }
        }, null)
    }

    private fun performCoordinateScroll(recommendedAction: JSONObject): Boolean {
        return try {
            val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
            if (screenWidth <= 0 || screenHeight <= 0) {
                Log.w(TAG, "Cannot coordinate-scroll with invalid screen dimensions: ${screenWidth}x${screenHeight}")
                return false
            }

            val direction = recommendedAction.optString("direction", "down").lowercase()
            val centerX = screenWidth / 2
            val startY: Int
            val endY: Int

            if (direction == "up" || direction == "backward") {
                startY = (screenHeight * 0.35f).toInt()
                endY = (screenHeight * 0.75f).toInt()
            } else {
                startY = (screenHeight * 0.75f).toInt()
                endY = (screenHeight * 0.35f).toInt()
            }

            val path = android.graphics.Path().apply {
                moveTo(centerX.toFloat(), startY.toFloat())
                lineTo(centerX.toFloat(), endY.toFloat())
            }

            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 650))
                .build()

            val success = accessibilityService.dispatchGesture(
                gesture,
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        Log.d(TAG, "Coordinate scroll completed successfully")
                    }

                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        Log.w(TAG, "Coordinate scroll was cancelled")
                    }
                },
                null
            )
            Log.d(TAG, "Coordinate scroll dispatch result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Coordinate scroll failed: ${e.message}")
            false
        }
    }
    
    private fun performType(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform type action")
        
        val textToType = recommendedAction.optString("text_to_type", "")
        if (textToType.isBlank()) {
            Log.w(TAG, "Type action missing text_to_type")
            return false
        }

        if (isUnsafeCouponOrPromoInputSurfaceActive()) {
            Log.w(TAG, "Refusing to type order text into coupon/promo input surface")
            return false
        }

        val keepSearchOpen = shouldKeepSearchInputOpen(recommendedAction)
        val searchNodeTyped = typeTextIntoSearchFieldNode(
            textToType,
            submitIme = !keepSearchOpen,
            dismissKeyboard = !keepSearchOpen
        )
        if (searchNodeTyped) {
            Log.d(TAG, "Type action used direct search field node")
            return true
        }

        if (isSwiggyForeground() && keepSearchOpen) {
            val reacquiredTyped = typeTextIntoSwiggySearchAfterFreshReacquire(textToType)
            if (reacquiredTyped) {
                Log.d(TAG, "Type action used fresh Swiggy search reacquire path")
                return true
            }
            if (isKeyboardLikelyActive()) {
                val typed = typeTextByKeyboardGesture(
                    textToType,
                    submitIme = false,
                    requireSearchField = true,
                    allowSearchSurfaceInference = true
                )
                Log.d(TAG, "Swiggy visible keyboard text entry result: $typed")
                if (typed) return true
            }
            Log.w(TAG, "Swiggy search typing failed via dedicated search-field path; skipping repeated focus retries")
            return false
        }

        val focusedTyped = typeTextIntoFocusedField(
            textToType,
            waitForFocusMs = 700,
            submitIme = !keepSearchOpen,
            dismissKeyboard = !keepSearchOpen,
            requireSearchField = keepSearchOpen
        )
        if (focusedTyped) {
            Log.d(TAG, "Type action used already focused editable field")
            return true
        }

        if (focusSearchFieldForTyping(recommendedAction)) {
            val typed = typeTextIntoFocusedField(
                textToType,
                waitForFocusMs = 2500,
                submitIme = !keepSearchOpen,
                dismissKeyboard = !keepSearchOpen,
                requireSearchField = keepSearchOpen
            )
            Log.d(TAG, "Type action text entry after search field focus result: $typed")
            if (typed) return true
            if (isSwiggyForeground() && keepSearchOpen && isKeyboardLikelyActive()) {
                val keyboardTyped = typeTextByKeyboardGesture(
                    textToType,
                    submitIme = false,
                    requireSearchField = true,
                    allowSearchSurfaceInference = true
                )
                Log.d(TAG, "Type action keyboard text entry after Swiggy search focus result: $keyboardTyped")
                if (keyboardTyped) return true
            }
        }

        if (isSwiggyForeground() && keepSearchOpen) {
            Log.w(TAG, "Swiggy search typing failed via safe search-field paths; skipping generic editable fallbacks")
            return false
        }

        val rootNode = bestCommerceRootNode()
        val editableNode = if (rootNode != null) findFirstEditable(rootNode) else null
        if (editableNode != null) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val typed = typeTextIntoFocusedField(
                textToType,
                waitForFocusMs = 1200,
                submitIme = !keepSearchOpen,
                dismissKeyboard = !keepSearchOpen,
                requireSearchField = keepSearchOpen
            )
            Log.d(TAG, "Type action text entry via editable node result: $typed")
            if (typed) return true
        }

        val targetNode = findTargetElement(recommendedAction)
        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val typed = typeTextIntoFocusedField(
                textToType,
                waitForFocusMs = 1800,
                submitIme = !keepSearchOpen,
                dismissKeyboard = !keepSearchOpen,
                requireSearchField = keepSearchOpen
            )
            Log.d(TAG, "Type action text entry after target focus result: $typed")
            return typed
        }

        if (keepSearchOpen || isKeyboardLikelyActive()) {
            val typed = typeTextByKeyboardGesture(
                textToType,
                submitIme = !keepSearchOpen,
                requireSearchField = keepSearchOpen
            )
            if (typed) {
                Log.d(TAG, "Type action used keyboard gesture fallback")
                return true
            }
        }

        Log.w(TAG, "Target element not found for type and no editable field available")
        return false
    }

    private fun focusSearchFieldForTyping(recommendedAction: JSONObject): Boolean {
        return try {
            val rootNode = bestCommerceRootNode() ?: return false
            val searchNode = findCommerceSearchField(rootNode)
            if (searchNode != null) {
                val clickableNode = findClickableSelfOrAncestor(searchNode) ?: searchNode
                val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Focused commerce search field via accessibility node result: $clicked")
                if (clicked) {
                    return waitForSearchFieldFocus(700)
                }
            }

            if (tapSwiggyTopSearchFieldForFocus()) {
                return true
            }

            if (isSwiggyForeground()) {
                Log.w(TAG, "Swiggy search focus failed via known search paths; skipping broad tree target scan")
                return false
            }

            val targetNode = findTargetElement(recommendedAction)
            if (targetNode != null) {
                val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Focused search field via target node result: $clicked")
                if (clicked) {
                    return waitForSearchFieldFocus(700)
                }
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "Search field focus for typing failed: ${e.message}")
            false
        }
    }
    
    private fun performSwipe(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform swipe action")
        
        val targetNode = findTargetElement(recommendedAction)
        return if (targetNode != null) {
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Log.d(TAG, "Swipe action result: $success")
            success || performCoordinateScroll(recommendedAction)
        } else {
            Log.w(TAG, "Target element not found for swipe, trying coordinate gesture fallback")
            performCoordinateScroll(recommendedAction)
        }
    }
    
    private fun performLongPress(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform long press action")
        
        val targetNode = findTargetElement(recommendedAction)
        return if (targetNode != null) {
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            Log.d(TAG, "Long press action result: $success")
            success
        } else {
            Log.w(TAG, "Target element not found for long press")
            false
        }
    }
    
    private fun performDoubleTap(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform double tap action")
        
        val targetNode = findTargetElement(recommendedAction)
        return if (targetNode != null) {
            // Perform two clicks in quick succession
            val firstClick = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(100) // Small delay between clicks
            val secondClick = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val success = firstClick && secondClick
            Log.d(TAG, "Double tap action result: $success")
            success
        } else {
            Log.w(TAG, "Target element not found for double tap")
            false
        }
    }

    private fun shouldKeepSearchInputOpen(recommendedAction: JSONObject): Boolean {
        val actionTarget = recommendedAction.optString("action_target", "")
        if (actionTarget.contains("search", ignoreCase = true)) return true

        val selector = recommendedAction.optJSONObject("element_selector")
        val className = selector?.optString("class_name", "").orEmpty()
        val resourceId = selector?.optString("resource_id", "").orEmpty()
        val text = selector?.optString("text", "").orEmpty()
        val contentDescription = selector?.optString("content_description", "").orEmpty()
        return className.contains("EditText", ignoreCase = true) ||
            resourceId.contains("search", ignoreCase = true) ||
            text.contains("search", ignoreCase = true) ||
            contentDescription.contains("search", ignoreCase = true)
    }

    private fun isKeyboardLikelyActive(): Boolean {
        if (hasVisibleInputMethodWindow()) {
            return true
        }
        return visibleWindowPackages().any { packageName ->
            packageName.contains("inputmethod", ignoreCase = true) ||
                packageName.contains("keyboard", ignoreCase = true) ||
                packageName.contains("latin", ignoreCase = true)
        }
    }

    private fun hasVisibleInputMethodWindow(): Boolean {
        return try {
            accessibilityService.windows?.any { window ->
                window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect input method windows: ${e.message}")
            false
        }
    }

    private fun isSwiggyForeground(): Boolean {
        return visibleWindowPackages().any { it == SWIGGY_INSTAMART_PACKAGE }
    }

    private fun activeRootPackage(): String? {
        return try {
            accessibilityService.rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read active root package: ${e.message}")
            null
        }
    }

    private fun isCommerceForeground(): Boolean {
        if (isSupportedCommercePackage(activeRootPackage())) {
            return true
        }
        return visibleWindowPackages().any { isSupportedCommercePackage(it) }
    }

    private fun ensureCommerceForegroundForSearchAction(recommendedAction: JSONObject): Boolean {
        if (isCommerceForeground()) {
            return true
        }

        val activePackage = activeRootPackage().orEmpty()
        val visiblePackages = visibleWindowPackages().distinct().joinToString(",").ifBlank { "<none>" }
        Log.w(TAG, "Search action requested while foreground package is '$activePackage'; visible packages: $visiblePackages")
        if (waitForSupportedCommerceForeground(2500)) {
            return true
        }
        try {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(700)
        } catch (e: Exception) {
            Log.w(TAG, "Back navigation before search action failed: ${e.message}")
        }
        if (waitForSupportedCommerceForeground(1500)) {
            return true
        }

        val launchPackage = intendedCommercePackageForAction(recommendedAction) ?: return false
        return relaunchCommercePackage(launchPackage)
    }

    private fun intendedCommercePackageForAction(recommendedAction: JSONObject): String? {
        val actionText = listOf(
            recommendedAction.optString("action_target", ""),
            recommendedAction.optString("reasoning", ""),
            recommendedAction.optString("app_name", "")
        ).joinToString(" ").lowercase()
        return when {
            "swiggy" in actionText || "instamart" in actionText -> SWIGGY_INSTAMART_PACKAGE
            "blinkit" in actionText || "grofers" in actionText -> BLINKIT_PACKAGE
            else -> null
        }
    }

    private fun relaunchCommercePackage(packageName: String): Boolean {
        return try {
            val launchIntent = accessibilityService.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.w(TAG, "No launch intent for commerce package '$packageName'")
                return false
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            accessibilityService.startActivity(launchIntent)
            waitForCommercePackageForeground(packageName, 10000)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to relaunch commerce package '$packageName': ${e.message}")
            false
        }
    }

    private fun waitForSupportedCommerceForeground(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (isCommerceForeground()) {
                return true
            }
            handleBlockingSystemDialogs()
            Thread.sleep(150)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun waitForCommercePackageForeground(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (visibleWindowPackages().any { it == packageName }) {
                return true
            }
            handleBlockingSystemDialogs()
            Thread.sleep(150)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun confirmCommerceForegroundAfterSearchClick(): Boolean {
        Thread.sleep(450)
        if (isCommerceForeground()) {
            return true
        }
        Log.w(TAG, "Search click moved focus outside supported commerce app; backing out")
        try {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(500)
        } catch (e: Exception) {
            Log.w(TAG, "Back navigation after unsafe search click failed: ${e.message}")
        }
        return false
    }

    private fun visibleWindowPackages(): List<String> {
        val packages = mutableListOf<String>()
        try {
            accessibilityService.rootInActiveWindow?.packageName?.toString()?.let { packages.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read active root package: ${e.message}")
        }

        try {
            accessibilityService.windows?.forEach { window ->
                try {
                    window?.root?.packageName?.toString()?.let { packages.add(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read window package: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate window packages: ${e.message}")
        }

        return packages
    }

    private fun typeTextByKeyboardGesture(
        text: String,
        submitIme: Boolean,
        requireSearchField: Boolean = false,
        allowSearchSurfaceInference: Boolean = false
    ): Boolean {
        if (!isSafeKeyboardTextEntryActive(requireSearchField, allowSearchSurfaceInference)) {
            return false
        }
        if (isSwiggyForeground() && requireSearchField && allowSearchSurfaceInference) {
            clearSwiggyTopSearchTextForKeyboardEntry()
            if (!waitForSwiggySearchKeyboardReadyAfterClear()) {
                Log.w(TAG, "Refusing Swiggy keyboard text entry because search input was not stable after clearing")
                return false
            }
        }
        val typed = tapKeyboardText(text, submitIme)
        val verification = if (typed && isSwiggyForeground() && requireSearchField && !submitIme) {
            verifySwiggySearchText(text, 4500)
        } else {
            true
        }
        if (verification == false) {
            Log.w(TAG, "Swiggy keyboard text entry produced a different query than '$text'")
            return false
        } else if (verification == null) {
            Log.w(TAG, "Swiggy keyboard text entry query was not observable after guarded typing; continuing")
        }
        return typed
    }

    private fun typeTextByTrustedSwiggyKeyboardGesture(
        text: String,
        submitIme: Boolean
    ): Boolean {
        if (!isRecentSwiggyTopSearchFocusTap(maxAgeMs = SWIGGY_SEARCH_FOCUS_MARK_MAX_AGE_MS)) {
            Log.w(TAG, "Refusing trusted Swiggy keyboard text entry because top-search tap is stale")
            return false
        }
        if (!isSwiggyForeground()) {
            Log.w(TAG, "Refusing trusted Swiggy keyboard text entry because Swiggy is not foreground")
            return false
        }
        if (!waitForKeyboardLikelyActive(4500)) {
            Log.w(TAG, "Refusing trusted Swiggy keyboard text entry because keyboard did not become visible")
            return false
        }
        clearSwiggyTopSearchTextForKeyboardEntry()
        if (!waitForSwiggySearchKeyboardReadyAfterClear()) {
            Log.w(TAG, "Refusing trusted Swiggy keyboard text entry because search input was not stable after clearing")
            return false
        }
        val typed = tapKeyboardText(text, submitIme)
        val verification = if (typed && !submitIme) verifySwiggySearchText(text, 4500) else true
        if (verification == false) {
            Log.w(TAG, "Trusted Swiggy keyboard text entry produced a different query than '$text'")
            return false
        } else if (verification == null) {
            Log.w(TAG, "Trusted Swiggy keyboard text entry query was not observable after guarded typing; continuing")
        }
        return typed
    }

    private fun clearSwiggyTopSearchTextForKeyboardEntry() {
        if (!isSwiggyForeground() || !isKeyboardLikelyActive()) {
            return
        }
        if (isUnsafeCouponOrPromoInputSurfaceActive() || isSwiggyLocationPickerSurfaceActive()) {
            return
        }
        val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
        if (screenWidth <= 0 || screenHeight <= 0) {
            return
        }
        val clearX = (screenWidth * 0.80f).toInt()
        val clearY = (screenHeight * 0.081f).toInt()
        val clicked = performRawClick(clearX, clearY, waitForCompletion = true)
        Log.d(TAG, "Swiggy top search clear-before-keyboard-entry click result: $clicked")
        Thread.sleep(250)
    }

    private fun waitForSwiggySearchKeyboardReadyAfterClear(timeoutMs: Long = 2200): Boolean {
        Thread.sleep(850)
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (
                isSwiggyForeground() &&
                isKeyboardLikelyActive() &&
                !isUnsafeCouponOrPromoInputSurfaceActive() &&
                !isSwiggyLocationPickerSurfaceActive()
            ) {
                val focusedNode = waitForFocusedEditable(250)
                if (focusedNode == null || isUsableSearchEditableNode(focusedNode) || isSwiggyProductSearchKeyboardSurfaceActive()) {
                    Thread.sleep(250)
                    return true
                }
            }
            Thread.sleep(120)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun verifySwiggySearchText(expectedText: String, timeoutMs: Long): Boolean? {
        val expected = expectedText.trim().lowercase()
        val deadline = System.currentTimeMillis() + timeoutMs
        var observedText = false
        do {
            val current = currentSwiggySearchText().trim()
            if (current.equals(expectedText.trim(), ignoreCase = true)) {
                return true
            }
            if (current.isNotBlank()) {
                observedText = true
                Log.d(TAG, "Waiting for Swiggy search text '$expected'; observed '${current.lowercase()}'")
            }
            Thread.sleep(160)
        } while (System.currentTimeMillis() < deadline)
        return if (observedText) false else null
    }

    private fun currentSwiggySearchText(): String {
        if (!isSwiggyForeground()) return ""
        if (isUnsafeCouponOrPromoInputSurfaceActive() || isSwiggyLocationPickerSurfaceActive()) return ""
        val roots = commerceWindowRoots()
        for (root in roots) {
            refreshNode(root)
            findSearchEditableNodeByKnownId(root)?.text?.toString()?.let { return it }
            findFocusedSearchEditableNode(root)?.text?.toString()?.let { return it }
            findFocusedEditable(root, NodeScanBudget(maxNodes = 260, maxDurationMs = 550))?.text?.toString()?.let { return it }
            findSearchEditableNode(root, NodeScanBudget(maxNodes = 240, maxDurationMs = 550))?.text?.toString()?.let { return it }
            if (root.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE && hasVisibleProductSearchSurface(root)) {
                findFirstEditable(root, NodeScanBudget(maxNodes = 260, maxDurationMs = 550))?.text?.toString()?.let { return it }
            }
        }
        return ""
    }

    private fun markRecentSwiggySearchFocus(reason: String) {
        if (!isSwiggyForeground()) return
        lastSwiggyTopSearchFocusTapAtMs = System.currentTimeMillis()
        Log.d(TAG, "Marked recent Swiggy search focus: $reason")
    }

    private fun preserveOrClearRecentSwiggySearchFocus(reason: String) {
        if (
            isRecentSwiggyTopSearchFocusTap(maxAgeMs = SWIGGY_SEARCH_FOCUS_MARK_MAX_AGE_MS) &&
            isSwiggyForeground() &&
            !isUnsafeCouponOrPromoInputSurfaceActive() &&
            !isSwiggyLocationPickerSurfaceActive()
        ) {
            Log.d(TAG, "Preserving recent Swiggy search focus after redundant focus action: $reason")
            return
        }
        lastSwiggyTopSearchFocusTapAtMs = 0L
    }

    private fun tapKeyboardText(text: String, submitIme: Boolean): Boolean {
        val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
        val keyCenters = keyboardKeyCenters(screenWidth, screenHeight)
        var typedAny = false

        for (char in text.lowercase()) {
            val point = keyCenters[char] ?: continue
            if (!performRawClick(point.first, point.second, waitForCompletion = true)) {
                return false
            }
            typedAny = true
            Thread.sleep(90)
        }

        if (typedAny && submitIme) {
            performRawClick((screenWidth * 0.92f).toInt(), (screenHeight * 0.91f).toInt(), waitForCompletion = true)
            Thread.sleep(250)
        }
        return typedAny
    }

    private fun waitForKeyboardLikelyActive(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (isKeyboardLikelyActive()) {
                return true
            }
            Thread.sleep(120)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun isSafeKeyboardTextEntryActive(
        requireSearchField: Boolean,
        allowSearchSurfaceInference: Boolean = false
    ): Boolean {
        if (!isKeyboardLikelyActive()) {
            Log.w(TAG, "Refusing keyboard gesture text entry because the keyboard is not visible")
            return false
        }

        val focusedNode = waitForFocusedEditable(0)
        if (focusedNode == null) {
            if (allowSearchSurfaceInference && requireSearchField && isSwiggyKeyboardEntrySafeAfterTopSearchTap()) {
                Log.d(TAG, "Allowing Swiggy keyboard text entry based on recent safe top-search tap")
                return true
            }
            if (allowSearchSurfaceInference && requireSearchField && isSwiggyProductSearchKeyboardSurfaceActive()) {
                Log.d(TAG, "Allowing Swiggy keyboard text entry based on visible product search surface")
                return true
            }
            Log.w(TAG, "Refusing keyboard gesture text entry because no editable field is focused")
            return false
        }

        if (isUnsafeCouponOrPromoInputSurfaceActive()) {
            Log.w(TAG, "Refusing keyboard gesture text entry inside coupon/promo surface")
            return false
        }

        if (requireSearchField && !isUsableSearchEditableNode(focusedNode)) {
            if (allowSearchSurfaceInference && isSwiggyProductSearchKeyboardSurfaceActive()) {
                Log.d(TAG, "Allowing Swiggy keyboard text entry based on visible product search surface despite generic focused editable")
                return true
            }
            Log.w(TAG, "Refusing keyboard gesture text entry because focused editable is not a search field")
            return false
        }

        return true
    }

    private fun performRawClick(x: Int, y: Int, waitForCompletion: Boolean = false): Boolean {
        val coordinates = JSONObject().apply {
            put("x", x)
            put("y", y)
        }
        val action = JSONObject().apply {
            put("coordinates", coordinates)
        }
        if (!waitForCompletion) {
            return performRawCoordinateClick(action)
        }

        val completionLatch = CountDownLatch(1)
        val resultHolder = BooleanArray(1)
        val success = performRawCoordinateClick(action, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                resultHolder[0] = true
                completionLatch.countDown()
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                resultHolder[0] = false
                completionLatch.countDown()
            }
        })

        if (!success) {
            return false
        }

        if (!completionLatch.await(900, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Timed out waiting for raw keyboard tap to finish at ($x, $y)")
            return true
        }

        return resultHolder[0]
    }

    private fun keyboardKeyCenters(screenWidth: Int, screenHeight: Int): Map<Char, Pair<Int, Int>> {
        val centers = mutableMapOf<Char, Pair<Int, Int>>()
        val topRow = "qwertyuiop"
        val middleRow = "asdfghjkl"
        val bottomRow = "zxcvbnm"
        val topY = (screenHeight * 0.715f).toInt()
        val middleY = (screenHeight * 0.782f).toInt()
        val bottomY = (screenHeight * 0.852f).toInt()

        topRow.forEachIndexed { index, char ->
            centers[char] = Pair(((index + 0.5f) * screenWidth / 10f).toInt(), topY)
        }
        middleRow.forEachIndexed { index, char ->
            centers[char] = Pair(((index + 1f) * screenWidth / 10f).toInt(), middleY)
        }
        bottomRow.forEachIndexed { index, char ->
            centers[char] = Pair(((index + 2f) * screenWidth / 10f).toInt(), bottomY)
        }
        centers[' '] = Pair((screenWidth * 0.55f).toInt(), (screenHeight * 0.915f).toInt())
        return centers
    }

    private fun typeTextIntoSearchFieldNode(
        text: String,
        submitIme: Boolean,
        dismissKeyboard: Boolean
    ): Boolean {
        val swiggyForeground = isSwiggyForeground()
        if (swiggyForeground && isSwiggyLocationPickerSurfaceActive()) {
            recoverFromSwiggyLocationPicker("location picker was active before typing into product search")
            return false
        }

        waitForSearchEditableNodeInFreshRoots(
            timeoutMs = if (swiggyForeground) 2200 else 500,
            allowSwiggyTopScan = !swiggyForeground
        )?.let { searchNode ->
            if (swiggyForeground) {
                markRecentSwiggySearchFocus("fresh editable search node before direct typing")
            }
            if (typeIntoSearchNode(searchNode, text, submitIme, dismissKeyboard)) {
                return true
            }
        }

        if (swiggyForeground && !submitIme && !dismissKeyboard && isKeyboardLikelyActive()) {
            val typed = typeTextByKeyboardGesture(
                text,
                submitIme = false,
                requireSearchField = true,
                allowSearchSurfaceInference = true
            )
            Log.d(TAG, "Swiggy visible keyboard text entry before coordinate focus result: $typed")
            if (typed) return true
        }

        if (swiggyForeground && tapSwiggyTopSearchFieldForFocus()) {
            if (!submitIme && !dismissKeyboard) {
                val typed = typeTextByTrustedSwiggyKeyboardGesture(
                    text,
                    submitIme = false
                )
                Log.d(TAG, "Swiggy trusted keyboard text entry after top-search tap result: $typed")
                return typed
            }
        }
        return false
    }

    private fun typeTextIntoSwiggySearchAfterFreshReacquire(text: String): Boolean {
        if (!isSwiggyForeground()) return false
        if (isUnsafeCouponOrPromoInputSurfaceActive()) {
            Log.w(TAG, "Refusing Swiggy search reacquire because coupon/promo input is active")
            return false
        }
        if (isSwiggyLocationPickerSurfaceActive()) {
            recoverFromSwiggyLocationPicker("location picker was active during Swiggy search reacquire")
            return false
        }

        waitForSearchEditableNodeInFreshRoots(timeoutMs = 2200, allowSwiggyTopScan = true)?.let { searchNode ->
            markRecentSwiggySearchFocus("fresh editable search node during type reacquire")
            if (typeIntoSearchNode(searchNode, text, submitIme = false, dismissKeyboard = false)) {
                return true
            }
        }

        if (isKeyboardLikelyActive()) {
            val typed = typeTextByKeyboardGesture(
                text,
                submitIme = false,
                requireSearchField = true,
                allowSearchSurfaceInference = true
            )
            if (typed) return true
        }

        if (tapSwiggyTopSearchFieldForFocus()) {
            waitForSearchEditableNodeInFreshRoots(timeoutMs = 1800, allowSwiggyTopScan = true)?.let { searchNode ->
                markRecentSwiggySearchFocus("fresh editable search node after Swiggy coordinate refocus")
                if (typeIntoSearchNode(searchNode, text, submitIme = false, dismissKeyboard = false)) {
                    return true
                }
            }

            val typed = typeTextByTrustedSwiggyKeyboardGesture(text, submitIme = false)
            if (typed) return true
        }

        waitForSearchEditableNodeInFreshRoots(timeoutMs = 2600, allowSwiggyTopScan = true)?.let { searchNode ->
            markRecentSwiggySearchFocus("late fresh editable search node during type reacquire")
            if (typeIntoSearchNode(searchNode, text, submitIme = false, dismissKeyboard = false)) {
                return true
            }
        }

        return false
    }

    private fun typeIntoSearchNode(
        searchNode: AccessibilityNodeInfo,
        text: String,
        submitIme: Boolean,
        dismissKeyboard: Boolean
    ): Boolean {
        refreshNode(searchNode)
        searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        searchNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val success = searchNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Direct search field text entry result: $success")
        val textEntrySuccess = success || pasteTextIntoNode(searchNode, text)
        if (!textEntrySuccess) {
            return false
        }
        if (submitIme) {
            submitImeEnter(searchNode)
        }
        if (dismissKeyboard) {
            dismissKeyboardIfStillFocused(text)
        } else {
            Thread.sleep(500)
        }
        return true
    }

    private fun findSearchEditableNodeInFreshRoots(allowSwiggyTopScan: Boolean): AccessibilityNodeInfo? {
        val roots = commerceWindowRoots()
        for (root in roots) {
            refreshNode(root)
            val rootPackage = root.packageName?.toString()
            findSearchEditableNodeByKnownId(root)?.let { return it }
            findFocusedSearchEditableNode(root)?.let { return it }
            if (rootPackage != SWIGGY_INSTAMART_PACKAGE || allowSwiggyTopScan) {
                findSearchEditableNode(root, newQuickNodeScanBudget())?.let { return it }
            }
        }
        return null
    }

    private fun waitForSearchEditableNodeInFreshRoots(
        timeoutMs: Long,
        allowSwiggyTopScan: Boolean
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            findSearchEditableNodeInFreshRoots(allowSwiggyTopScan)?.let { return it }
            Thread.sleep(140)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun refreshNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.refresh()
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility node refresh failed: ${e.message}")
            false
        }
    }

    private fun pasteTextIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val clipboard = accessibilityService.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("beta_search_query", text))
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "Search field paste fallback result: $pasted")
            pasted
        } catch (e: Exception) {
            Log.w(TAG, "Search field paste fallback failed: ${e.message}")
            false
        }
    }

    private fun allAvailableWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        accessibilityService.rootInActiveWindow?.let { roots.add(it) }

        try {
            accessibilityService.windows?.forEach { window ->
                try {
                    val root = window?.root ?: return@forEach
                    if (!roots.any { it === root }) {
                        roots.add(root)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read accessibility window root: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate accessibility windows: ${e.message}")
        }

        return roots
    }

    private fun findSearchEditableNodeByKnownId(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in COMMERCE_SEARCH_FIELD_VIEW_IDS) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (e: Exception) {
                Log.w(TAG, "Search field lookup failed for $viewId: ${e.message}")
                emptyList()
            }

            Log.d(TAG, "Search field lookup for $viewId returned ${nodes.size} nodes")
            val match = nodes.firstOrNull { isUsableSearchEditableNode(it) }
            if (match != null) {
                Log.d(TAG, "Found commerce search field by view id: $viewId")
                return match
            }
        }
        return null
    }

    private fun findFocusedSearchEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focusedNode = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
        return focusedNode?.takeIf { isUsableSearchEditableNode(it) }
    }

    private fun findSearchEditableNode(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget = newQuickNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (isUsableSearchEditableNode(node)) {
            return node
        }

        if (bounds.top > 700 && bounds.height() > 0) {
            return null
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findSearchEditableNode(child, budget)
            if (result != null) return result
        }
        return null
    }

    private fun isUsableSearchEditableNode(node: AccessibilityNodeInfo): Boolean {
        val resourceId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        return node.isVisibleToUser && node.isEnabled &&
            (node.isEditable || className.contains("EditText", ignoreCase = true)) &&
            (
                resourceId.contains("search", ignoreCase = true) ||
                    description.contains("search", ignoreCase = true) ||
                    text.contains("search", ignoreCase = true) ||
                    hint.contains("search", ignoreCase = true)
            )
    }

    private fun isUsableEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val className = node.className?.toString().orEmpty()
        return node.isVisibleToUser && node.isEnabled &&
            (node.isEditable || className.contains("EditText", ignoreCase = true))
    }

    fun typeTextIntoFocusedField(
        text: String,
        waitForFocusMs: Long = 0,
        submitIme: Boolean = true,
        dismissKeyboard: Boolean = true,
        requireSearchField: Boolean = false
    ): Boolean {
        return try {
            val focusedNode = waitForFocusedEditable(waitForFocusMs) ?: run {
                Log.w(TAG, "No focused editable field found within ${waitForFocusMs}ms")
                return false
            }

            if (isUnsafeCouponOrPromoInputSurfaceActive()) {
                Log.w(TAG, "Focused editable is inside coupon/promo surface; aborting text entry")
                return false
            }

            if (requireSearchField && !isUsableSearchEditableNode(focusedNode)) {
                Log.w(TAG, "Focused editable is not a product search field; aborting search text entry")
                return false
            }

            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Type text into focused field result: $success")
            if (success) {
                if (submitIme) {
                    submitImeEnter(focusedNode)
                }
                if (dismissKeyboard) {
                    dismissKeyboardIfStillFocused(text)
                } else {
                    Log.d(TAG, "Keeping focused input open after text entry")
                    Thread.sleep(500)
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error typing into focused field: ${e.message}")
            false
        }
    }

    private fun waitForFocusedEditable(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            val roots = commerceWindowRoots()
            val focusedNode = roots.firstNotNullOfOrNull { root ->
                refreshNode(root)
                val directFocus = try {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (e: Exception) {
                    null
                }
                if (isUsableEditableNode(directFocus)) directFocus else null
            } ?: roots.firstNotNullOfOrNull { root -> findFocusedEditable(root, newQuickNodeScanBudget()) }
            if (focusedNode != null) return focusedNode
            if (timeoutMs <= 0) break
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun submitImeEnter(focusedNode: AccessibilityNodeInfo) {
        try {
            Thread.sleep(300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeEnterAction = AccessibilityNodeInfo::class.java
                    .getField("ACTION_IME_ENTER")
                    .getInt(null)
                val imeSuccess = focusedNode.performAction(imeEnterAction)
                Log.d(TAG, "IME enter action result: $imeSuccess")
            }
        } catch (e: Exception) {
            Log.w(TAG, "IME enter action failed: ${e.message}")
        }
    }

    private fun dismissKeyboardIfStillFocused(expectedText: String) {
        try {
            Thread.sleep(700)
            val focusedNode = waitForFocusedEditable(300)
            val focusedText = focusedNode?.text?.toString().orEmpty()
            if (focusedNode != null && focusedText.contains(expectedText, ignoreCase = true)) {
                val backSuccess = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Log.d(TAG, "Keyboard dismiss fallback after typing result: $backSuccess")
                Thread.sleep(300)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keyboard dismiss fallback failed: ${e.message}")
        }
    }

    private fun findFocusedEditable(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget = newQuickNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (node.isFocused || node.isAccessibilityFocused) {
            if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
                node.isEditable
            ) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child, budget)
            if (result != null) return result
        }
        return null
    }

    private fun findFirstEditable(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget = newQuickNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true || node.isEditable) {
            if (node.isVisibleToUser && node.isEnabled) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child, budget)
            if (result != null) return result
        }
        return null
    }

    private fun findCommerceSearchField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findSearchEditableNodeByKnownId(node)?.let { return it }
        findFocusedSearchEditableNode(node)?.let { return it }

        if (node.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE) {
            return null
        }

        return findCommerceSearchFieldCandidate(node, newQuickNodeScanBudget())
    }

    private fun findCommerceSearchFieldCandidate(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        val resourceId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val combined = "$resourceId $text $description"
        val isSearchField = node.isVisibleToUser && node.isEnabled && (
            resourceId.contains("search", ignoreCase = true) ||
                combined.contains("Search for", ignoreCase = true) ||
                combined.contains("Search across", ignoreCase = true)
            )
        if (isSearchField) return node

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findCommerceSearchFieldCandidate(child, budget)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun waitForSearchFieldFocus(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (commerceWindowRoots().any { rootNode -> hasFocusedSearchField(rootNode) }) {
                return true
            }
            if (timeoutMs <= 0) {
                break
            }
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun hasFocusedSearchField(node: AccessibilityNodeInfo): Boolean {
        if (findFocusedSearchEditableNode(node) != null) {
            return true
        }

        val focusedEditable = findFocusedEditable(node, newQuickNodeScanBudget())
        if (focusedEditable != null && isUsableSearchEditableNode(focusedEditable)) {
            return true
        }
        return findFocusedSearchNode(node, newQuickNodeScanBudget()) != null
    }

    private fun isUnsafeCouponOrPromoInputSurfaceActive(): Boolean {
        return try {
            val keyboardActive = isKeyboardLikelyActive()
            commerceWindowRoots().any { root ->
                val surfaceText = collectNodeText(root).lowercase()
                val hasCouponHeading = listOf(
                    "apply coupon",
                    "apply promo",
                    "promo code",
                    "coupon code",
                    "offer code"
                ).any { surfaceText.contains(it) } ||
                    (surfaceText.contains("apply") && listOf("coupon", "promo", "voucher").any { surfaceText.contains(it) })
                val hasCouponResult = listOf(
                    "no coupons found",
                    "no coupon found",
                    "invalid coupon",
                    "invalid promo"
                ).any { surfaceText.contains(it) }
                val hasEditable = findFocusedEditable(root) != null || findFirstEditable(root) != null
                hasCouponHeading && (hasCouponResult || hasEditable || keyboardActive)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Coupon/promo surface check failed: ${e.message}")
            false
        }
    }

    private fun isSwiggyLocationPickerSurfaceActive(): Boolean {
        if (!isSwiggyForeground()) {
            return false
        }

        return try {
            commerceWindowRoots().any { root ->
                root.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE &&
                    isSwiggyLocationPickerText(collectNodeText(root).lowercase())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Swiggy location-picker surface check failed: ${e.message}")
            false
        }
    }

    private fun isSwiggyCheckoutOrPaymentBoundaryActive(): Boolean {
        if (!isSwiggyForeground()) {
            return false
        }

        return try {
            commerceWindowRoots().any { root ->
                root.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE &&
                    isSwiggyCheckoutOrPaymentText(collectNodeText(root).lowercase())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Swiggy checkout/payment boundary check failed: ${e.message}")
            false
        }
    }

    private fun isSwiggyFullscreenProductPreviewActive(): Boolean {
        if (!isSwiggyForeground()) {
            return false
        }

        return try {
            commerceWindowRoots().any { root ->
                root.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE &&
                    collectNodeText(root).lowercase().let { surfaceText ->
                        surfaceText.contains("full_screen_preview_recycler_view") ||
                            surfaceText.contains("full_screen_product_thumbnail_card") ||
                            (surfaceText.contains("close") && surfaceText.contains("full_screen_thumbnail_recycler_view"))
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Swiggy product preview check failed: ${e.message}")
            false
        }
    }

    private fun closeSwiggyFullscreenProductPreviewIfActive(reason: String): Boolean {
        if (!isSwiggyFullscreenProductPreviewActive()) {
            return false
        }
        Log.w(TAG, "Closing Swiggy product image preview $reason")
        lastSwiggyTopSearchFocusTapAtMs = 0L
        return try {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(450)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close Swiggy product image preview: ${e.message}")
            false
        }
    }

    private fun isSwiggyCheckoutOrPaymentText(surfaceText: String): Boolean {
        if (surfaceText.isBlank()) {
            return false
        }
        return listOf(
            "pay to instamart",
            "otp verification",
            "submit & pay",
            "submit pay",
            "complete this payment",
            "go to bank page",
            "payment failed",
            "cancel transaction",
            "auto-reading otp",
            "auto reading otp"
        ).any { surfaceText.contains(it) }
    }

    private fun isSwiggyLocationPickerText(surfaceText: String): Boolean {
        if (surfaceText.isBlank()) {
            return false
        }
        return listOf(
            "select your location",
            "search an area or address",
            "saved addresses",
            "add new address",
            "use current location"
        ).any { surfaceText.contains(it) }
    }

    private fun waitForSwiggyLocationPickerSurface(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (isSwiggyLocationPickerSurfaceActive()) {
                return true
            }
            Thread.sleep(120)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun recoverFromSwiggyLocationPicker(reason: String) {
        Log.w(TAG, "Recovering from Swiggy location picker during product search focus: $reason")
        lastSwiggyTopSearchFocusTapAtMs = 0L
        try {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(900)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back out of Swiggy location picker: ${e.message}")
        }
    }

    private fun isSwiggyProductSearchKeyboardSurfaceActive(): Boolean {
        if (!isSwiggyForeground() || !isKeyboardLikelyActive()) {
            return false
        }
        if (isUnsafeCouponOrPromoInputSurfaceActive()) {
            return false
        }

        return commerceWindowRoots().any { root ->
            root.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE && hasVisibleProductSearchSurface(root)
        }
    }

    private fun isSwiggyProductSearchEntrySurfaceActive(): Boolean {
        if (!isSwiggyForeground()) {
            return false
        }
        if (isUnsafeCouponOrPromoInputSurfaceActive() || isSwiggyLocationPickerSurfaceActive()) {
            return false
        }

        return commerceWindowRoots().any { root ->
            root.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE && hasVisibleProductSearchSurface(root)
        }
    }

    private fun hasVisibleProductSearchSurface(root: AccessibilityNodeInfo): Boolean {
        refreshNode(root)
        if (findSearchEditableNodeByKnownId(root) != null) return true
        if (findFocusedSearchEditableNode(root) != null) return true
        if (findSearchEditableNode(root, NodeScanBudget(maxNodes = 160, maxDurationMs = 350)) != null) return true

        val topText = collectTopNodeText(root).lowercase()
        return listOf(
            "search for products",
            "search for",
            "your past searches",
            "search instamart",
            "showing results for",
            "search instead for"
        ).any { topText.contains(it) }
    }

    private fun isRecentSwiggyTopSearchFocusTap(maxAgeMs: Long = SWIGGY_SEARCH_FOCUS_MARK_MAX_AGE_MS): Boolean {
        return lastSwiggyTopSearchFocusTapAtMs > 0L &&
            System.currentTimeMillis() - lastSwiggyTopSearchFocusTapAtMs <= maxAgeMs
    }

    private fun isSwiggyKeyboardEntrySafeAfterTopSearchTap(): Boolean {
        if (!isRecentSwiggyTopSearchFocusTap()) {
            Log.d(TAG, "Recent Swiggy top-search tap ignored because it is stale or missing")
            return false
        }
        if (!isSwiggyForeground()) {
            Log.d(TAG, "Recent Swiggy top-search tap ignored because Swiggy is not foreground")
            return false
        }
        if (!isKeyboardLikelyActive()) {
            Log.d(TAG, "Recent Swiggy top-search tap ignored because keyboard is not visible")
            return false
        }
        if (isUnsafeCouponOrPromoInputSurfaceActive()) {
            Log.w(TAG, "Recent Swiggy top-search tap ignored inside coupon/promo surface")
            return false
        }
        if (isSwiggyLocationPickerSurfaceActive()) {
            Log.w(TAG, "Recent Swiggy top-search tap ignored inside location picker")
            return false
        }
        return true
    }

    private fun waitForSwiggyKeyboardEntrySafeAfterTopSearchTap(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (isSwiggyKeyboardEntrySafeAfterTopSearchTap()) {
                return true
            }
            Thread.sleep(120)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun waitForSwiggyKeyboardAfterTopSearchTap(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (isSwiggyForeground() && isKeyboardLikelyActive()) {
                return true
            }
            Thread.sleep(120)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun waitForSwiggySafeKeyboardAfterSearchClick(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (
                isSwiggyForeground() &&
                isKeyboardLikelyActive() &&
                !isUnsafeCouponOrPromoInputSurfaceActive() &&
                !isSwiggyLocationPickerSurfaceActive()
            ) {
                return true
            }
            Thread.sleep(120)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun tapSwiggyTopSearchFieldForFocus(): Boolean {
        if (!isSwiggyForeground()) return false

        return try {
            val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
            if (screenWidth <= 0 || screenHeight <= 0) {
                return false
            }

            if (isSwiggyLocationPickerSurfaceActive()) {
                recoverFromSwiggyLocationPicker("location picker was already active before search focus")
            }

            if (!isSwiggyProductSearchEntrySurfaceActive()) {
                Log.w(TAG, "Swiggy product search entry surface is not visible; refusing coordinate search focus taps")
                return false
            }

            val tapCandidates = listOf(
                Pair(0.20f, 0.075f),
                Pair(0.50f, 0.075f),
                Pair(0.24f, 0.145f),
                Pair(0.50f, 0.145f),
                Pair(0.50f, 0.175f)
            )

            for ((xRatio, yRatio) in tapCandidates) {
                val x = (screenWidth * xRatio).toInt()
                val y = (screenHeight * yRatio).toInt()
                val clicked = performRawClick(x, y, waitForCompletion = true)
                Log.d(TAG, "Swiggy product search coordinate focus result at ($x,$y): $clicked")
                if (!clicked) {
                    continue
                }
                if (waitForSwiggyLocationPickerSurface(900)) {
                    recoverFromSwiggyLocationPicker("tap at ($x,$y) opened address search")
                    continue
                }
                if (waitForSwiggyKeyboardAfterTopSearchTap(2400)) {
                    markRecentSwiggySearchFocus("coordinate product-search focus at ($x,$y)")
                    return true
                }
                if (isSwiggyLocationPickerSurfaceActive()) {
                    recoverFromSwiggyLocationPicker("location picker appeared after search focus wait")
                    continue
                }
                Log.d(TAG, "Swiggy product search tap at ($x,$y) did not show keyboard; trying next candidate")
            }
            lastSwiggyTopSearchFocusTapAtMs = 0L
            false
        } catch (e: Exception) {
            Log.w(TAG, "Swiggy top search field focus tap failed: ${e.message}")
            false
        }
    }

    private fun collectNodeText(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        appendNodeText(root, builder, NodeScanBudget(maxNodes = 260, maxDurationMs = 400))
        return builder.toString()
    }

    private fun collectTopNodeText(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        appendNodeText(
            root,
            builder,
            NodeScanBudget(maxNodes = 180, maxDurationMs = 350),
            maxTop = 900
        )
        return builder.toString()
    }

    private fun appendNodeText(
        node: AccessibilityNodeInfo,
        builder: StringBuilder,
        budget: NodeScanBudget,
        maxTop: Int? = null
    ) {
        if (!budget.markVisited()) return
        if (builder.length > 12000) return
        if (maxTop != null) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top > maxTop && bounds.height() > 0) {
                return
            }
        }
        listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            node.hintText?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty()
        ).forEach { value ->
            if (value.isNotBlank()) {
                builder.append(' ').append(value)
            }
        }
        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            appendNodeText(child, builder, budget, maxTop)
        }
    }

    private fun findFocusedSearchNode(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget = newQuickNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if ((node.isFocused || node.isAccessibilityFocused) && isSearchFieldCandidate(node)) {
            return node
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findFocusedSearchNode(child, budget)
            if (result != null) return result
        }
        return null
    }

    private fun bestCommerceRootNode(): AccessibilityNodeInfo? {
        return commerceWindowRoots().firstOrNull()
    }

    private fun commerceWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val activeRoot = accessibilityService.rootInActiveWindow
        if (isSupportedCommercePackage(activeRoot?.packageName?.toString())) {
            roots.add(activeRoot!!)
        }

        try {
            accessibilityService.windows?.forEach { window ->
                try {
                    val root = window?.root ?: return@forEach
                    if (isSupportedCommercePackage(root.packageName?.toString()) && !roots.any { it === root }) {
                        roots.add(root)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read commerce window root: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate accessibility windows: ${e.message}")
        }

        return roots
    }

    private fun isSupportedCommercePackage(packageName: String?): Boolean {
        return packageName in SUPPORTED_COMMERCE_PACKAGES
    }

    private fun isSearchFieldCandidate(node: AccessibilityNodeInfo): Boolean {
        val resourceId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()
        return node.isEditable ||
            className.contains("EditText", ignoreCase = true) ||
            resourceId.contains("search", ignoreCase = true) ||
            text.contains("search", ignoreCase = true) ||
            description.contains("search", ignoreCase = true) ||
            hint.contains("search", ignoreCase = true) ||
            description.contains("what", ignoreCase = true)
    }

    private fun getOverlayDeflectionAdjustment(x: Int, y: Int): TapAdjustment {
        var adjustedX = x
        var adjustedY = y
        var overlayDeflection = 0
        try {
            val app = accessibilityService.application as? MyApplication
            val screenService = app?.getScreenCaptureService()
            val overlayRect = screenService?.getOverlayRect()
            if (overlayRect != null && !overlayRect.isEmpty) {
                val density = accessibilityService.resources.displayMetrics.density
                val pad = (24 * density).toInt()
                val inflated = Rect(
                    overlayRect.left - pad,
                    overlayRect.top - pad,
                    overlayRect.right + pad,
                    overlayRect.bottom + pad
                )
                if (inflated.contains(adjustedX, adjustedY)) {
                    overlayDeflection = overlayRect.height() + 24
                    adjustedY += overlayDeflection
                    Log.d(TAG, "Deflected tap from ($adjustedX, ${adjustedY - overlayDeflection}) to ($adjustedX, $adjustedY) to avoid overlay")
                    DebugLogger.logDebug(TAG, "Overlay deflection applied: +$overlayDeflection px")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overlay deflection check failed: ${e.message}")
        }
        return TapAdjustment(adjustedX, adjustedY, overlayDeflection)
    }
    
    private fun findTargetElement(recommendedAction: JSONObject): AccessibilityNodeInfo? {
        Log.d(TAG, "Searching for target element")
        
        val actionTarget = recommendedAction.optString("action_target", "")
        val swiggyQuantityStepDirection = quantityStepDirection(recommendedAction)
        val requiresCommerceRoot = actionTarget.contains("search", ignoreCase = true) ||
            actionTarget.contains("action_bar_root") ||
            (isSwiggyForeground() && swiggyQuantityStepDirection != null)
        val rootNode = if (requiresCommerceRoot) {
            bestCommerceRootNode()
        } else {
            accessibilityService.rootInActiveWindow
        }
        if (rootNode == null) {
            Log.w(TAG, "Root node is null for target lookup")
            return null
        }

        if (isSwiggyForeground() && swiggyQuantityStepDirection != null) {
            val stepperNode = findSwiggyQuantityStepperNode(
                rootNode,
                recommendedAction,
                swiggyQuantityStepDirection
            )
            if (stepperNode != null) {
                return stepperNode
            }
            Log.w(TAG, "Swiggy quantity stepper target not found; skipping generic class-name lookup")
            return null
        }
        
        // First try to parse action_target text for element information
        if (actionTarget.isNotEmpty()) {
            Log.d(TAG, "Parsing action target: '$actionTarget'")
            
            // Special handling for search bar - prioritize clickable search elements
            if (actionTarget.contains("search", ignoreCase = true) || actionTarget.contains("action_bar_root")) {
                Log.d(TAG, "Searching for search bar element")
                val searchElement = findClickableSearchElement(rootNode)
                if (searchElement != null) {
                    Log.d(TAG, "Found clickable search element")
                    return searchElement
                }
                if (rootNode.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE) {
                    Log.w(TAG, "Swiggy search target not found via known fields; skipping broad target lookup")
                    return null
                }
            }
            
            // Special handling for ADD buttons - prioritize green ADD buttons over heart icons
            if (isProductAddButtonAction(actionTarget)) {
                Log.d(TAG, "Searching for ADD button element")
                val addElement = findAddButtonElement(rootNode)
                if (addElement != null) {
                    Log.d(TAG, "Found ADD button element")
                    return addElement
                }
            }
            
            // Try to extract an exact text token inside single quotes, e.g., 'ADD'
            // Prefer exact text match if present
            val quotedTextRegex = "'([^']+)'".toRegex()
            val quotedText = quotedTextRegex.find(actionTarget)?.groupValues?.getOrNull(1)
            if (!quotedText.isNullOrBlank()) {
                val nodeByExactText = findNodeByExactText(rootNode, quotedText)
                if (nodeByExactText != null && !isToolbarOrBackNode(nodeByExactText)) {
                    Log.d(TAG, "Found element by exact quoted text: '$quotedText'")
                    return nodeByExactText
                }
            }

            // Try to extract content description from the action target
            val contentDescPattern = "contentDescription='([^']+)'".toRegex()
            val contentDescMatch = contentDescPattern.find(actionTarget)
            if (contentDescMatch != null) {
                val contentDesc = contentDescMatch.groupValues[1]
                Log.d(TAG, "Extracted content description: '$contentDesc'")
                val nodeByDesc = findNodeByContentDescription(rootNode, contentDesc)
                if (nodeByDesc != null) {
                    if (!isToolbarOrBackNode(nodeByDesc)) {
                        Log.d(TAG, "Found element by extracted content description: '$contentDesc'")
                        return nodeByDesc
                    }
                }
            }
            
            // Try to extract resource ID from the action target
            val resourceIdPattern = "id/([^;\\s]+)".toRegex()
            val resourceIdMatch = resourceIdPattern.find(actionTarget)
            if (resourceIdMatch != null) {
                val resourceId = "com.grofers.customerapp:id/${resourceIdMatch.groupValues[1]}"
                Log.d(TAG, "Extracted resource ID: '$resourceId'")
                val nodeById = findNodeByResourceId(rootNode, resourceId)
                if (nodeById != null) {
                    if (!isToolbarOrBackNode(nodeById)) {
                        Log.d(TAG, "Found element by extracted resource ID: '$resourceId'")
                        // If the found element is not clickable, try to find a clickable child or parent
                        if (!nodeById.isClickable) {
                            val clickableChild = findClickableChild(nodeById)
                            if (clickableChild != null) {
                                Log.d(TAG, "Found clickable child for resource ID: '$resourceId'")
                                return clickableChild
                            }
                            val clickableParent = findClickableParent(nodeById)
                            if (clickableParent != null) {
                                Log.d(TAG, "Found clickable parent for resource ID: '$resourceId'")
                                return clickableParent
                            }
                        }
                        return nodeById
                    }
                }
            }
            
            // Try to find by simple text matching
            if (actionTarget.contains("Categories", ignoreCase = true)) {
                Log.d(TAG, "Searching for 'Categories' element")
                val nodeByText = findNodeByContentDescription(rootNode, "Categories")
                if (nodeByText != null) {
                    Log.d(TAG, "Found element by text 'Categories'")
                    // Try to find clickable parent if the found element is not clickable
                    if (!nodeByText.isClickable) {
                        val clickableParent = findClickableParent(nodeByText)
                        if (clickableParent != null) {
                            Log.d(TAG, "Found clickable parent for Categories element")
                            return clickableParent
                        }
                    }
                    return nodeByText
                }
            }
        }
        
        // Try multiple methods to find the element using element_selector (legacy format)
        val elementSelector = recommendedAction.optJSONObject("element_selector")
        if (elementSelector != null) {
            // Method 1: Try by text content
            val text = elementSelector.optString("text", "")
            if (text.isNotEmpty()) {
                val nodeByText = findNodeByExactText(rootNode, text) ?: findNodeByText(rootNode, text)
                if (nodeByText != null) {
                    Log.d(TAG, "Found element by text: '$text'")
                    return nodeByText
                }
            }
            
            // Method 2: Try by content description (prioritize this over class name)
            val contentDescription = elementSelector.optString("content_description", "")
            if (contentDescription.isNotEmpty()) {
                Log.d(TAG, "Searching by content description: '$contentDescription'")
                val nodeByDesc = findNodeByContentDescription(rootNode, contentDescription)
                if (nodeByDesc != null) {
                    Log.d(TAG, "Found element by content description: '$contentDescription'")
                    return nodeByDesc
                } else {
                    Log.d(TAG, "No element found with content description: '$contentDescription'")
                }
            }
            
            // Method 3: Try by resource ID
            val resourceId = elementSelector.optString("resource_id", "")
            if (resourceId.isNotEmpty()) {
                Log.d(TAG, "Searching by resource ID: '$resourceId'")
                val nodeById = findNodeByResourceId(rootNode, resourceId)
                if (nodeById != null) {
                    Log.d(TAG, "Found element by resource ID: '$resourceId'")
                    return nodeById
                } else {
                    Log.d(TAG, "No element found with resource ID: '$resourceId'")
                }
            }
            
            // Method 4: Try by class name (last resort)
            val className = elementSelector.optString("class_name", "")
            if (className.isNotEmpty()) {
                Log.d(TAG, "Searching by class name: '$className'")
                val nodeByClass = findNodeByClassName(rootNode, className)
                if (nodeByClass != null) {
                    if (!isToolbarOrBackNode(nodeByClass)) {
                        Log.d(TAG, "Found element by class name: '$className'")
                        return nodeByClass
                    } else {
                        Log.d(TAG, "Class-name match was a toolbar/back element, ignoring")
                    }
                } else {
                    Log.d(TAG, "No element found with class name: '$className'")
                }
            }
        }
        
        Log.w(TAG, "Could not find target element using any method")
        return null
    }

    private fun quantityStepDirection(recommendedAction: JSONObject): QuantityStepDirection? {
        val selector = recommendedAction.optJSONObject("element_selector")
        val selectorText = selector?.optString("text", "").orEmpty().trim()
        if (selectorText == "+") return QuantityStepDirection.INCREMENT
        if (selectorText == "-") return QuantityStepDirection.DECREMENT

        val combined = listOf(
            recommendedAction.optString("action_target", ""),
            recommendedAction.optString("reasoning", ""),
            selector?.optString("content_description", "").orEmpty(),
            selector?.optString("resource_id", "").orEmpty()
        ).joinToString(" ").lowercase()

        if (
            combined.contains("increase quantity") ||
            combined.contains("increment") ||
            combined.contains("tap plus") ||
            combined.contains(" plus ") ||
            combined.contains("reach requested quantity")
        ) {
            return QuantityStepDirection.INCREMENT
        }

        if (
            combined.contains("decrease quantity") ||
            combined.contains("decrement") ||
            combined.contains("tap minus") ||
            combined.contains(" minus ") ||
            combined.contains("remove one")
        ) {
            return QuantityStepDirection.DECREMENT
        }

        return null
    }

    private fun findSwiggyQuantityStepperNode(
        rootNode: AccessibilityNodeInfo,
        recommendedAction: JSONObject,
        direction: QuantityStepDirection
    ): AccessibilityNodeInfo? {
        val resourceIds = when (direction) {
            QuantityStepDirection.INCREMENT -> listOf(
                "in.swiggy.android.instamart:id/increment_button_touch_target",
                "in.swiggy.android.instamart:id/increment_button"
            )
            QuantityStepDirection.DECREMENT -> listOf(
                "in.swiggy.android.instamart:id/decrement_button_touch_target",
                "in.swiggy.android.instamart:id/decrement_button"
            )
        }

        val candidates = findAllNodesByResourceIds(rootNode, resourceIds)
            .mapNotNull { node -> findClickableSelfOrAncestor(node) ?: node.takeIf { it.isClickable } }
            .filter { it.isVisibleToUser && it.isEnabled && it.isClickable }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No visible Swiggy $direction quantity stepper controls found by resource id")
            return null
        }

        val targetPoint = recommendedActionScreenPoint(recommendedAction)
        val selected = if (targetPoint != null) {
            candidates.minByOrNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val dx = (centerX - targetPoint.first).toLong()
                val dy = (centerY - targetPoint.second).toLong()
                dx * dx + dy * dy
            }
        } else if (candidates.size == 1) {
            candidates.first()
        } else {
            Log.w(TAG, "Multiple Swiggy $direction steppers found without coordinates; refusing ambiguous class-name fallback")
            null
        }

        selected?.let { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            Log.d(TAG, "Found Swiggy $direction quantity stepper at bounds: $bounds")
        }
        return selected
    }

    private fun recommendedActionScreenPoint(recommendedAction: JSONObject): Pair<Int, Int>? {
        val coordinates = recommendedAction.optJSONObject("coordinates")
            ?: recommendedAction.optJSONObject("fallback_coordinates")
            ?: return null
        val rawX = coordinates.optInt("x", -1)
        val rawY = coordinates.optInt("y", -1)
        if (rawX < 0 || rawY < 0) return null

        val app = accessibilityService.application as? MyApplication
        val (storedWidth, storedHeight) = app?.getLastScreenshotDimensions() ?: Pair(0, 0)
        val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
        if (storedWidth > 0 && storedHeight > 0 && screenWidth > 0 && screenHeight > 0) {
            return Pair(
                (rawX * screenWidth.toFloat() / storedWidth.toFloat()).toInt(),
                (rawY * screenHeight.toFloat() / storedHeight.toFloat()).toInt()
            )
        }
        return Pair(rawX, rawY)
    }

    private fun isToolbarOrBackNode(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (desc.contains("navigate up") || viewId.contains("action_bar") || viewId.contains("toolbar")) {
            return true
        }
        return false
    }
    
    private fun findNodeByText(
        rootNode: AccessibilityNodeInfo,
        text: String,
        budget: NodeScanBudget = newDefaultNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (rootNode.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            if (budget.shouldStop()) break
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByText(child, text, budget)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun findNodeByExactText(
        rootNode: AccessibilityNodeInfo,
        exact: String,
        budget: NodeScanBudget = newDefaultNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (rootNode.text?.toString()?.equals(exact, ignoreCase = true) == true) {
            return rootNode
        }
        for (i in 0 until rootNode.childCount) {
            if (budget.shouldStop()) break
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByExactText(child, exact, budget)
                if (result != null) return result
            }
        }
        return null
    }
    
    private fun findNodeByContentDescription(
        rootNode: AccessibilityNodeInfo,
        contentDescription: String,
        budget: NodeScanBudget = newDefaultNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (rootNode.contentDescription?.toString()?.contains(contentDescription, ignoreCase = true) == true) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            if (budget.shouldStop()) break
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByContentDescription(child, contentDescription, budget)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
    
    private fun findNodeByResourceId(
        rootNode: AccessibilityNodeInfo,
        resourceId: String,
        budget: NodeScanBudget = newDefaultNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (rootNode.viewIdResourceName == resourceId) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            if (budget.shouldStop()) break
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByResourceId(child, resourceId, budget)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun findAllNodesByResourceIds(
        rootNode: AccessibilityNodeInfo,
        resourceIds: List<String>,
        budget: NodeScanBudget = newDefaultNodeScanBudget(),
        limit: Int = 80
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()

        fun search(node: AccessibilityNodeInfo) {
            if (!budget.markVisited() || result.size >= limit) {
                return
            }
            if (node.viewIdResourceName in resourceIds) {
                result.add(node)
                if (result.size >= limit) {
                    return
                }
            }
            for (i in 0 until node.childCount) {
                if (budget.shouldStop() || result.size >= limit) break
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }

        search(rootNode)
        return result
    }
    
    private fun findNodeByClassName(
        rootNode: AccessibilityNodeInfo,
        className: String,
        budget: NodeScanBudget = newDefaultNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (rootNode.className?.toString() == className) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            if (budget.shouldStop()) break
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByClassName(child, className, budget)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
    
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.isClickable) {
                Log.d(TAG, "Found clickable parent: ${current.className}")
                return current
            }
            current = current.parent
        }
        return null
    }
    
    private fun findClickableChild(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget = newDefaultNodeScanBudget()
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            if (child.isClickable) {
                return child
            }
            // Recursively search in children
            val clickableDescendant = findClickableChild(child, budget)
            if (clickableDescendant != null) {
                return clickableDescendant
            }
        }
        return null
    }
    
    private fun findClickableSearchElement(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findSearchEditableNodeByKnownId(rootNode)?.let { return it }
        findFocusedSearchEditableNode(rootNode)?.let { return it }

        if (rootNode.packageName?.toString() == SWIGGY_INSTAMART_PACKAGE) {
            Log.d(TAG, "No known Swiggy search field found; skipping broad clickable search scan")
            return null
        }

        // Look for common search bar patterns
        val searchPatterns = listOf(
            "search", "Search", "SEARCH",
            "Search for products", "Search products",
            "What are you looking for?", "What do you want to buy?",
            "Enter search term", "Type to search"
        )

        // First, try to find by content description
        for (pattern in searchPatterns) {
            val nodeByDesc = findNodeByContentDescription(rootNode, pattern)
            if (nodeByDesc != null && nodeByDesc.isClickable) {
                Log.d(TAG, "Found clickable search element by content description: '$pattern'")
                return nodeByDesc
            }
        }
        
        // Look for EditText elements that might be search bars
        val editTextNodes = findAllNodesByClassName(rootNode, "android.widget.EditText")
        for (editText in editTextNodes) {
            if (editText.isClickable || editText.isEditable) {
                val hint = editText.hintText?.toString()?.lowercase() ?: ""
                val contentDesc = editText.contentDescription?.toString()?.lowercase() ?: ""
                if (hint.contains("search") || contentDesc.contains("search") || 
                    hint.contains("what") || contentDesc.contains("what")) {
                    Log.d(TAG, "Found clickable search EditText with hint: '${editText.hintText}'")
                    return editText
                }
            }
        }
        
        // Look for clickable elements with search-related resource IDs
        val searchResourceIds = listOf(
            "search", "search_bar", "search_input", "search_edit_text",
            "action_search", "search_view", "search_text"
        )
        
        for (resourceId in searchResourceIds) {
            val fullResourceId = "com.grofers.customerapp:id/$resourceId"
            val nodeById = findNodeByResourceId(rootNode, fullResourceId)
            if (nodeById != null && nodeById.isClickable) {
                Log.d(TAG, "Found clickable search element by resource ID: '$fullResourceId'")
                return nodeById
            }
        }
        
        // Look for any clickable element in the action bar area that might be a search bar
        val actionBarNodes = findAllNodesByClassName(rootNode, "android.widget.LinearLayout")
        for (actionBar in actionBarNodes) {
            val resourceId = actionBar.viewIdResourceName?.lowercase() ?: ""
            if (resourceId.contains("action_bar") || resourceId.contains("toolbar")) {
                val clickableChild = findClickableChild(actionBar)
                if (clickableChild != null) {
                    val childClass = clickableChild.className?.toString() ?: ""
                    val childDesc = clickableChild.contentDescription?.toString()?.lowercase() ?: ""
                    val childHint = clickableChild.hintText?.toString()?.lowercase() ?: ""
                    
                    if (childClass.contains("EditText") || childDesc.contains("search") || 
                        childHint.contains("search") || childDesc.contains("what")) {
                        Log.d(TAG, "Found clickable search element in action bar")
                        return clickableChild
                    }
                }
            }
        }
        
        Log.d(TAG, "No clickable search element found")
        return null
    }
    
    private fun findAddButtonElement(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        Log.d(TAG, "Searching for ADD button element")
        
        // Look for elements with exact text "ADD" (case insensitive)
        val addByText = findNodeByExactText(rootNode, "ADD")
        if (addByText != null && addByText.isClickable) {
            Log.d(TAG, "Found ADD button by exact text: 'ADD'")
            return addByText
        }
        
        val addByTextLower = findNodeByExactText(rootNode, "add")
        if (addByTextLower != null && addByTextLower.isClickable) {
            Log.d(TAG, "Found ADD button by exact text: 'add'")
            return addByTextLower
        }
        
        // Look for Button elements that might be ADD buttons
        val buttons = findAllNodesByClassName(rootNode, "android.widget.Button")
        for (button in buttons) {
            val buttonText = button.text?.toString()
            val buttonDescription = button.contentDescription?.toString()
            if (
                button.isClickable &&
                CommerceActionClassifier.isProductAddButtonAction("", buttonText, buttonDescription)
            ) {
                Log.d(TAG, "Found ADD button by Button class and text")
                return button
            }
        }
        
        val productAddNode = findProductAddNode(rootNode, newDefaultNodeScanBudget())
        if (productAddNode != null) {
            Log.d(TAG, "Found ADD button by product-add node classifier")
            return productAddNode
        }
        
        Log.d(TAG, "No ADD button element found")
        return null
    }

    private fun findProductAddNode(
        node: AccessibilityNodeInfo,
        budget: NodeScanBudget
    ): AccessibilityNodeInfo? {
        if (!budget.markVisited()) return null

        if (
            node.isClickable &&
            CommerceActionClassifier.isProductAddButtonAction(
                "",
                node.text?.toString(),
                node.contentDescription?.toString()
            )
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            if (budget.shouldStop()) break
            val child = node.getChild(i) ?: continue
            val result = findProductAddNode(child, budget)
            if (result != null) return result
        }
        return null
    }

    private fun isVariantAddAction(recommendedAction: JSONObject): Boolean {
        val target = recommendedAction.optString("action_target", "").lowercase()
        val reasoning = recommendedAction.optString("reasoning", "").lowercase()
        return target.contains("variant") || target.contains("smallest") ||
            reasoning.contains("variant") || reasoning.contains("bottom sheet")
    }

    private fun getScaledActionPoint(recommendedAction: JSONObject): Pair<Int, Int>? {
        val coordinates = recommendedAction.optJSONObject("coordinates")
            ?: recommendedAction.optJSONObject("fallback_coordinates")
            ?: return null

        val originalX = coordinates.optInt("x", -1)
        val originalY = coordinates.optInt("y", -1)
        if (originalX < 0 || originalY < 0) {
            return null
        }

        val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
        val app = accessibilityService.application as? MyApplication
        val (storedWidth, storedHeight) = app?.getLastScreenshotDimensions() ?: Pair(0, 0)
        val screenshotWidth = if (storedWidth > 0) storedWidth else screenWidth
        val screenshotHeight = if (storedHeight > 0) storedHeight else screenHeight

        if (screenshotWidth <= 0 || screenshotHeight <= 0) {
            return null
        }

        val scaleX = screenWidth.toFloat() / screenshotWidth.toFloat()
        val scaleY = screenHeight.toFloat() / screenshotHeight.toFloat()
        return Pair((originalX * scaleX).toInt(), (originalY * scaleY).toInt())
    }

    private fun findModalAddButtonElement(
        rootNode: AccessibilityNodeInfo,
        recommendedAction: JSONObject? = null
    ): AccessibilityNodeInfo? {
        val modalRoot = findModalRoot(rootNode)
        if (modalRoot == null) {
            Log.d(TAG, "No modal root found for ADD lookup")
            return null
        }

        val addNodes = mutableListOf<AccessibilityNodeInfo>()
        collectExactAddNodes(modalRoot, addNodes)
        if (addNodes.isEmpty()) {
            Log.d(TAG, "No exact ADD nodes found inside modal")
            return null
        }

        val scaledPoint = recommendedAction?.let { getScaledActionPoint(it) }
        val selected = if (scaledPoint != null) {
            val (targetX, targetY) = scaledPoint
            addNodes.minWithOrNull(
                compareBy<AccessibilityNodeInfo> {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    val centerX = (bounds.left + bounds.right) / 2
                    val centerY = (bounds.top + bounds.bottom) / 2
                    val dx = (centerX - targetX).toLong()
                    val dy = (centerY - targetY).toLong()
                    dx * dx + dy * dy
                }.thenBy {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    bounds.top
                }.thenBy {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    bounds.left
                }
            )
        } else {
            addNodes.sortedWith(compareBy<AccessibilityNodeInfo> {
                Rect().also { rect -> it.getBoundsInScreen(rect) }.top
            }.thenBy {
                Rect().also { rect -> it.getBoundsInScreen(rect) }.left
            }).lastOrNull()
        }

        selected?.let {
            val bounds = Rect()
            it.getBoundsInScreen(bounds)
            Log.d(TAG, "Selected modal ADD node at bounds: $bounds")
        }
        return selected
    }

    private fun findModalRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (
            cls.contains("bottomsheet") ||
            cls.contains("dialog") ||
            cls.contains("coordinatorlayout") ||
            viewId.contains("design_bottom_sheet") ||
            viewId.contains("bottom_sheet")
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findModalRoot(child)
            if (result != null) return result
        }
        return null
    }

    private fun collectExactAddNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
            (text.equals("ADD", ignoreCase = true) || desc.equals("ADD", ignoreCase = true))
        ) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectExactAddNodes(child, out)
        }
    }
    
    private fun performAddButtonClickWithValidation(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Performing ADD button click with validation")
        val isSwiggyAdd = isSwiggyForeground()
        if (isSwiggyAdd && closeSwiggyFullscreenProductPreviewIfActive("before ADD coordinate click")) {
            return false
        }
        
        val coordinates = recommendedAction.optJSONObject("coordinates")
        if (coordinates == null) {
            Log.w(TAG, "No coordinates provided for ADD button")
            return false
        }
        
        val originalX = coordinates.optInt("x", 0)
        val originalY = coordinates.optInt("y", 0)
        
        Log.d(TAG, "Original ADD button coordinates: ($originalX, $originalY)")
        
        // Strategy 1: Try original coordinates with validation
        val success1 = performClickByCoordinatesWithValidation(recommendedAction, "Original coordinates")
        if (success1) {
            Log.d(TAG, "ADD button click attempt 1: SUCCESS")
            return true
        }
        if (isSwiggyAdd && closeSwiggyFullscreenProductPreviewIfActive("after ADD coordinate click")) {
            return false
        }
        
        // Strategy 2: Try multiple coordinate adjustments for typical product card layouts
        val coordinateStrategies = listOf(
            "Move right (avoid heart icon)" to Pair(originalX + 50, originalY),
            "Move down (avoid heart icon)" to Pair(originalX, originalY + 30),
            "Move right+down" to Pair(originalX + 30, originalY + 20),
            "Move left (if heart was on right)" to Pair(originalX - 30, originalY),
            "Move up (if ADD button is above)" to Pair(originalX, originalY - 20),
            "Move right+up" to Pair(originalX + 40, originalY - 10),
            "Move left+down" to Pair(originalX - 20, originalY + 25)
        )
        
        for ((strategyName, coords) in coordinateStrategies) {
            Log.d(TAG, "ADD button click attempt: $strategyName at (${coords.first}, ${coords.second})")
            
            val adjustedAction = JSONObject(recommendedAction.toString()).apply {
                put("coordinates", JSONObject().apply {
                    put("x", coords.first)
                    put("y", coords.second)
                })
            }
            
            val success = performClickByCoordinatesWithValidation(adjustedAction, strategyName)
            if (success) {
                Log.d(TAG, "ADD button click SUCCESS with strategy: $strategyName")
                return true
            }
        }
        
        Log.d(TAG, "All ADD button click strategies failed")
        return false
    }
    
    private fun performClickByCoordinatesWithValidation(recommendedAction: JSONObject, strategyName: String): Boolean {
        Log.d(TAG, "Attempting click by coordinates with validation: $strategyName")
        
        // Try both 'coordinates' (backend format) and 'fallback_coordinates' (legacy format)
        val coordinates = recommendedAction.optJSONObject("coordinates") 
            ?: recommendedAction.optJSONObject("fallback_coordinates")
        
        if (coordinates != null) {
            val originalX = coordinates.optInt("x", 0)
            val originalY = coordinates.optInt("y", 0)
            
            // Get all dimension information for coordinate system analysis
            val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
            val statusBarHeight = ScreenMetrics.getStatusBarHeight(accessibilityService)
            val navigationBarHeight = ScreenMetrics.getNavigationBarHeight(accessibilityService)
            
            // Get screenshot dimensions from MyApplication storage
            val app = accessibilityService.application as? MyApplication
            val (storedWidth, storedHeight) = app?.getLastScreenshotDimensions() ?: Pair(0, 0)
            
            // Use stored screenshot dimensions if available, otherwise fall back to screen dimensions
            val screenshotWidth = if (storedWidth > 0) storedWidth else screenWidth
            val screenshotHeight = if (storedHeight > 0) storedHeight else screenHeight
            
            // Log comprehensive coordinate system analysis BEFORE any processing
            DebugLogger.logCoordinateSystem(
                backendX = originalX,
                backendY = originalY,
                screenshotWidth = screenshotWidth,
                screenshotHeight = screenshotHeight,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                statusBarHeight = statusBarHeight,
                navigationBarHeight = navigationBarHeight,
                finalX = originalX, // Will update after processing
                finalY = originalY, // Will update after processing
                strategy = strategyName
            )
            
            // Calculate and log scaling ratios for hypothesis verification
            val scaleX = screenWidth.toFloat() / screenshotWidth.toFloat()
            val scaleY = screenHeight.toFloat() / screenshotHeight.toFloat()
            val scaledBackendX = (originalX * scaleX).toInt()
            val scaledBackendY = (originalY * scaleY).toInt()
            
            Log.i("ActionExecutor", "SCALING ANALYSIS - Strategy: $strategyName")
            Log.i("ActionExecutor", "Screenshot dimensions: ${screenshotWidth}x${screenshotHeight}")
            Log.i("ActionExecutor", "Screen dimensions: ${screenWidth}x${screenHeight}")
            Log.i("ActionExecutor", "Scaling factors: X=${String.format("%.3f", scaleX)}, Y=${String.format("%.3f", scaleY)}")
            Log.i("ActionExecutor", "Backend coords: ($originalX, $originalY)")
            Log.i("ActionExecutor", "Scaled coords should be: ($scaledBackendX, $scaledBackendY)")
            
            if (Math.abs(scaleY - 1.0) > 0.01) {
                Log.w("ActionExecutor", "SCALING MISMATCH DETECTED! Y scaling factor: ${String.format("%.3f", scaleY)}")
                Log.w("ActionExecutor", "Applying scaling correction to fix coordinate mismatch!")
            } else {
                Log.i("ActionExecutor", "No scaling mismatch detected - using coordinates as-is")
            }
            
            // APPLY SCALING CORRECTION: Backend coordinates are in screenshot space, need to scale to screen space
            // This fixes the coordinate mismatch caused by MediaProjection scaling down to fit smaller buffer
            var x = (originalX * scaleX).toInt()
            var y = (originalY * scaleY).toInt()
            
            val statusBarAdjustment = 0  // No additional status bar adjustment needed
            val buttonCenterAdjustment = 0  // No additional button adjustment needed
            
            Log.i(TAG, "SCALING CORRECTION APPLIED:")
            Log.i(TAG, "  Original coords: ($originalX, $originalY)")
            Log.i(TAG, "  Scaling factors: X=${String.format("%.3f", scaleX)}, Y=${String.format("%.3f", scaleY)}")
            Log.i(TAG, "  Scaled coords: ($x, $y)")
            Log.i(TAG, "  Y offset applied: +${y - originalY}px")
            
            // Check if scaled coordinates are within bounds using screen dimensions
            // Scaled coordinates are now in screen space, so check against screen dimensions
            if (x < 0 || x >= screenWidth || y < 0 || y >= screenHeight) {
                Log.e(TAG, "Scaled coordinates out of bounds: ($x, $y), screen: ${screenWidth}x${screenHeight}")
                DebugLogger.logError(TAG, "Scaled click coordinates out of bounds: ($x, $y), screen: ${screenWidth}x${screenHeight}")
                return false
            }

            val overlayAdjustment = getOverlayDeflectionAdjustment(x, y)
            x = overlayAdjustment.x
            y = overlayAdjustment.y
            val overlayDeflection = overlayAdjustment.overlayDeflection
            
            Log.d(TAG, "Clicking at scaled coordinates: ($x, $y) [Original: ($originalX, $originalY), Scaling: ${String.format("%.3f", scaleY)}x]")
            
            // Log the actual click coordinates
            val actionTarget = recommendedAction.optString("action_target", "unknown")
            DebugLogger.logActualClickCoordinates(
                x = x,
                y = y,
                screenWidth = screenshotWidth,
                screenHeight = screenshotHeight,
                targetDescription = "$strategyName - $actionTarget"
            )
            
            // Create a gesture for clicking at specific coordinates
            val gestureBuilder = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.d(TAG, "Coordinate click gesture completed: $strategyName")
                    DebugLogger.logGestureResult(strategyName, completed = true, cancelled = false)
                }
                
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.w(TAG, "Coordinate click gesture cancelled: $strategyName")
                    DebugLogger.logGestureResult(strategyName, completed = false, cancelled = true)
                }
            }
            
            // Create a tap gesture (press and release)
            val path = android.graphics.Path().apply { 
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 100 // Increased duration to 100ms for more reliable clicks
                ))
                .build()
            
            val dispatchResult = accessibilityService.dispatchGesture(gesture, gestureBuilder, null)
            Log.d(TAG, "Coordinate click dispatch result: $dispatchResult")
            
            if (dispatchResult) {
                // Add a small delay to allow UI to respond
                Thread.sleep(200)
                
                // Additional validation: Check if we can find any UI changes that might indicate success
                val validationResult = validateClickSuccess(recommendedAction, strategyName)
                
                // Log the actual result AFTER validation
                DebugLogger.logClickExecution(
                    strategy = strategyName,
                    originalX = originalX,
                    originalY = originalY,
                    finalX = x,
                    finalY = y,
                    statusBarAdjustment = statusBarAdjustment,
                    overlayDeflection = overlayDeflection,
                    buttonCenterAdjustment = buttonCenterAdjustment,
                    success = validationResult
                )
                
                // Log comprehensive scaling analysis for hypothesis verification
                DebugLogger.logScalingAnalysis(
                    backendX = originalX,
                    backendY = originalY,
                    screenshotWidth = screenshotWidth,
                    screenshotHeight = screenshotHeight,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    clickX = x,
                    clickY = y
                )
                
                if (validationResult) {
                    Log.d(TAG, "Click dispatched successfully with validation: $strategyName")
                    return true
                } else {
                    Log.w(TAG, "Click dispatched but validation failed: $strategyName")
                    return false
                }
            } else {
                // Log failed dispatch
                DebugLogger.logClickExecution(
                    strategy = strategyName,
                    originalX = originalX,
                    originalY = originalY,
                    finalX = x,
                    finalY = y,
                    statusBarAdjustment = statusBarAdjustment,
                    overlayDeflection = overlayDeflection,
                    buttonCenterAdjustment = buttonCenterAdjustment,
                    success = false
                )
                
                Log.w(TAG, "Click dispatch failed: $strategyName")
                return false
            }
        }
        
        Log.w(TAG, "No fallback coordinates available")
        return false
    }
    
    private fun validateClickSuccess(recommendedAction: JSONObject, strategyName: String): Boolean {
        Log.d(TAG, "Validating click success for: $strategyName")
        
        // For ADD button clicks, try to detect success indicators
        val actionTarget = recommendedAction.optString("action_target", "")
        val isAddButton = isProductAddButtonAction(actionTarget)
        
        if (isAddButton) {
            // Wait a bit more for UI to update
            Thread.sleep(300)
            if (isSwiggyForeground()) {
                if (isSwiggyFullscreenProductPreviewActive()) {
                    Log.w(TAG, "Swiggy ADD click opened the product image preview; treating as failed")
                    return false
                }
                if (isSwiggyCheckoutOrPaymentBoundaryActive()) {
                    Log.w(TAG, "Swiggy ADD click reached checkout/payment boundary; treating as failed")
                    return false
                }
            }
            
            // Check if we can find any success indicators in the accessibility tree
            val rootNode = accessibilityService.rootInActiveWindow
            if (rootNode != null) {
                // Look for common success indicators
                val successIndicators = listOf(
                    "Added to cart",
                    "Item added",
                    "Added to wishlist", // This would indicate a misclick
                    "Cart updated",
                    "Product added"
                )
                
                for (indicator in successIndicators) {
                    val foundNode = findNodeByText(rootNode, indicator)
                    if (foundNode != null) {
                        Log.d(TAG, "Found success indicator: '$indicator'")
                        
                        // Check if it's a wishlist misclick
                        if (indicator.contains("wishlist", ignoreCase = true)) {
                            Log.w(TAG, "WISHLIST MISCLICK DETECTED: Click hit wishlist button instead of ADD button")
                            return false
                        }
                        
                        return true
                    }
                }
                
                // Alternative validation: Check if the ADD button text changed or became disabled
                // This is a more sophisticated approach that would require tracking button state
                Log.d(TAG, "No explicit success indicators found, assuming click was successful")
                return true
            }
        }
        
        // For non-ADD buttons, just return true if dispatch was successful
        return true
    }
    
    private fun findAllNodesByClassName(
        rootNode: AccessibilityNodeInfo,
        className: String,
        budget: NodeScanBudget = newDefaultNodeScanBudget(),
        limit: Int = 80
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        
        fun search(node: AccessibilityNodeInfo) {
            if (!budget.markVisited() || result.size >= limit) {
                return
            }
            if (node.className?.toString() == className) {
                result.add(node)
                if (result.size >= limit) {
                    return
                }
            }
            for (i in 0 until node.childCount) {
                if (budget.shouldStop() || result.size >= limit) break
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }
        
        search(rootNode)
        return result
    }
    
    private fun performClickByCoordinates(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting click by coordinates")
        
        // Try both 'coordinates' (backend format) and 'fallback_coordinates' (legacy format)
        val coordinates = recommendedAction.optJSONObject("coordinates") 
            ?: recommendedAction.optJSONObject("fallback_coordinates")
        
        if (coordinates != null) {
            val originalX = coordinates.optInt("x", 0)
            val originalY = coordinates.optInt("y", 0)
            
            // Get all dimension information for coordinate system analysis
            val (screenWidth, screenHeight) = ScreenMetrics.getScreenDimensions(accessibilityService)
            val statusBarHeight = ScreenMetrics.getStatusBarHeight(accessibilityService)
            val navigationBarHeight = ScreenMetrics.getNavigationBarHeight(accessibilityService)
            
            // Get screenshot dimensions from MyApplication storage
            val app = accessibilityService.application as? MyApplication
            val (storedWidth, storedHeight) = app?.getLastScreenshotDimensions() ?: Pair(0, 0)
            
            // Use stored screenshot dimensions if available, otherwise fall back to screen dimensions
            val screenshotWidth = if (storedWidth > 0) storedWidth else screenWidth
            val screenshotHeight = if (storedHeight > 0) storedHeight else screenHeight
            
            // Log comprehensive coordinate system analysis BEFORE any processing
            DebugLogger.logCoordinateSystem(
                backendX = originalX,
                backendY = originalY,
                screenshotWidth = screenshotWidth,
                screenshotHeight = screenshotHeight,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                statusBarHeight = statusBarHeight,
                navigationBarHeight = navigationBarHeight,
                finalX = originalX, // Will update after processing
                finalY = originalY, // Will update after processing
                strategy = "coordinate_click"
            )
            
            // Calculate and log scaling ratios for hypothesis verification
            val scaleX = screenWidth.toFloat() / screenshotWidth.toFloat()
            val scaleY = screenHeight.toFloat() / screenshotHeight.toFloat()
            val scaledBackendX = (originalX * scaleX).toInt()
            val scaledBackendY = (originalY * scaleY).toInt()
            
            Log.i("ActionExecutor", "SCALING ANALYSIS - Strategy: coordinate_click")
            Log.i("ActionExecutor", "Screenshot dimensions: ${screenshotWidth}x${screenshotHeight}")
            Log.i("ActionExecutor", "Screen dimensions: ${screenWidth}x${screenHeight}")
            Log.i("ActionExecutor", "Scaling factors: X=${String.format("%.3f", scaleX)}, Y=${String.format("%.3f", scaleY)}")
            Log.i("ActionExecutor", "Backend coords: ($originalX, $originalY)")
            Log.i("ActionExecutor", "Scaled coords should be: ($scaledBackendX, $scaledBackendY)")
            
            if (Math.abs(scaleY - 1.0) > 0.01) {
                Log.w("ActionExecutor", "SCALING MISMATCH DETECTED! Y scaling factor: ${String.format("%.3f", scaleY)}")
                Log.w("ActionExecutor", "Applying scaling correction to fix coordinate mismatch!")
            } else {
                Log.i("ActionExecutor", "No scaling mismatch detected - using coordinates as-is")
            }
            
            // APPLY SCALING CORRECTION: Backend coordinates are in screenshot space, need to scale to screen space
            // This fixes the coordinate mismatch caused by MediaProjection scaling down to fit smaller buffer
            var x = (originalX * scaleX).toInt()
            var y = (originalY * scaleY).toInt()
            
            val statusBarAdjustment = 0  // No additional status bar adjustment needed
            val buttonCenterAdjustment = 0  // No additional button adjustment needed
            
            Log.i(TAG, "SCALING CORRECTION APPLIED:")
            Log.i(TAG, "  Original coords: ($originalX, $originalY)")
            Log.i(TAG, "  Scaling factors: X=${String.format("%.3f", scaleX)}, Y=${String.format("%.3f", scaleY)}")
            Log.i(TAG, "  Scaled coords: ($x, $y)")
            Log.i(TAG, "  Y offset applied: +${y - originalY}px")
            
            // Check if scaled coordinates are within bounds using screen dimensions
            // Scaled coordinates are now in screen space, so check against screen dimensions
            if (x < 0 || x >= screenWidth || y < 0 || y >= screenHeight) {
                Log.e(TAG, "Scaled coordinates out of bounds: ($x, $y), screen: ${screenWidth}x${screenHeight}")
                DebugLogger.logError(TAG, "Scaled click coordinates out of bounds: ($x, $y), screen: ${screenWidth}x${screenHeight}")
                return false
            }

            val overlayAdjustment = getOverlayDeflectionAdjustment(x, y)
            x = overlayAdjustment.x
            y = overlayAdjustment.y
            val overlayDeflection = overlayAdjustment.overlayDeflection
            
            Log.d(TAG, "Clicking at scaled coordinates: ($x, $y) [Original: ($originalX, $originalY), Scaling: ${String.format("%.3f", scaleY)}x]")
            
            // Log the actual click coordinates
            val actionTarget = recommendedAction.optString("action_target", "unknown")
            DebugLogger.logActualClickCoordinates(
                x = x,
                y = y,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                targetDescription = "coordinate_click - $actionTarget"
            )
            
            // Create a gesture for clicking at specific coordinates
            val gestureBuilder = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.d(TAG, "Coordinate click gesture completed: coordinate_click")
                    DebugLogger.logGestureResult("coordinate_click", completed = true, cancelled = false)
                }
                
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.w(TAG, "Coordinate click gesture cancelled: coordinate_click")
                    DebugLogger.logGestureResult("coordinate_click", completed = false, cancelled = true)
                }
            }
            
            // Create a tap gesture (press and release)
            val path = android.graphics.Path().apply { 
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 100 // Increased duration to 100ms for more reliable clicks
                ))
                .build()
            
            val success = accessibilityService.dispatchGesture(gesture, gestureBuilder, null)
            Log.d(TAG, "Coordinate click dispatch result: $success")
            
            // Log the actual result AFTER dispatch
            DebugLogger.logClickExecution(
                strategy = "coordinate_click",
                originalX = originalX,
                originalY = originalY,
                finalX = x,
                finalY = y,
                statusBarAdjustment = statusBarAdjustment,
                overlayDeflection = overlayDeflection,
                buttonCenterAdjustment = buttonCenterAdjustment,
                success = success
            )
            
            // Log comprehensive scaling analysis for hypothesis verification
            DebugLogger.logScalingAnalysis(
                backendX = originalX,
                backendY = originalY,
                screenshotWidth = screenshotWidth,
                screenshotHeight = screenshotHeight,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                clickX = x,
                clickY = y
            )
            
            // TODO: Add verification for "ADD" button clicks to detect wishlist misclicks
            // Could monitor for "Added to wishlist" toasts or check cart badge changes
            
            return success
        }
        
        Log.w(TAG, "No fallback coordinates available")
        return false
    }
}
