package com.example.beta

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class ActionExecutor(private val accessibilityService: AccessibilityService) {
    
    companion object {
        private const val TAG = "ActionExecutor"
    }
    
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

    private fun containsAnrText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

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
            val child = node.getChild(i) ?: continue
            if (containsAnrText(child)) {
                return true
            }
        }
        return false
    }

    private fun handleAnrByClickingWait(): Boolean {
        val rootNode = accessibilityService.rootInActiveWindow ?: return false
        if (!containsAnrText(rootNode)) {
            return false
        }
        val waitNode = findNodeByExactText(rootNode, "Wait")
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

            // Keep ANR dialog from blocking action execution
            handleAnrByClickingWait()

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

            if (!handleAnrByClickingWait()) {
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
        val isAddButton = actionTarget.contains("ADD", ignoreCase = true) || actionTarget.contains("add", ignoreCase = true)
        
        // For search bars, try coordinate-based clicking FIRST (more reliable)
        if (isSearchBar) {
            val coordinates = recommendedAction.optJSONObject("coordinates")
            if (coordinates != null) {
                Log.d(TAG, "Search bar detected - using coordinate-based click as primary method")
                val success = performClickByCoordinates(recommendedAction)
                if (success) {
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
        if (!actionTarget.contains("search", ignoreCase = true)) return true

        val textToType = recommendedAction.optString("text_to_type", "")
        if (textToType.isBlank()) return true

        val typed = typeTextIntoFocusedField(textToType, waitForFocusMs = 1500)
        Log.d(TAG, "Search click follow-up type '$textToType' result: $typed")
        return true
    }

    private fun performRawCoordinateClick(recommendedAction: JSONObject): Boolean {
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

        val success = accessibilityService.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "Raw coordinate click gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "Raw coordinate click gesture cancelled at ($x, $y)")
            }
        }, null)

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

        val focusedTyped = typeTextIntoFocusedField(textToType, waitForFocusMs = 700)
        if (focusedTyped) {
            Log.d(TAG, "Type action used already focused editable field")
            return true
        }

        if (focusSearchFieldForTyping(recommendedAction)) {
            val typed = typeTextIntoFocusedField(textToType, waitForFocusMs = 2500)
            Log.d(TAG, "Type action text entry after search field focus result: $typed")
            if (typed) return true
        }

        val rootNode = accessibilityService.rootInActiveWindow
        val editableNode = if (rootNode != null) findFirstEditable(rootNode) else null
        if (editableNode != null) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val typed = typeTextIntoFocusedField(textToType, waitForFocusMs = 1200)
            Log.d(TAG, "Type action text entry via editable node result: $typed")
            if (typed) return true
        }

        val targetNode = findTargetElement(recommendedAction)
        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val typed = typeTextIntoFocusedField(textToType, waitForFocusMs = 1800)
            Log.d(TAG, "Type action text entry after target focus result: $typed")
            return typed
        }

        Log.w(TAG, "Target element not found for type and no editable field available")
        return false
    }

    private fun focusSearchFieldForTyping(recommendedAction: JSONObject): Boolean {
        return try {
            val rootNode = accessibilityService.rootInActiveWindow ?: return false
            val searchNode = findBlinkitSearchField(rootNode)
            if (searchNode != null) {
                val clickableNode = findClickableSelfOrAncestor(searchNode) ?: searchNode
                val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Focused Blinkit search field via accessibility node result: $clicked")
                if (clicked) {
                    Thread.sleep(700)
                    return true
                }
            }

            val targetNode = findTargetElement(recommendedAction)
            if (targetNode != null) {
                val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Focused search field via target node result: $clicked")
                if (clicked) {
                    Thread.sleep(700)
                    return true
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

    fun typeTextIntoFocusedField(text: String, waitForFocusMs: Long = 0): Boolean {
        return try {
            val focusedNode = waitForFocusedEditable(waitForFocusMs) ?: run {
                Log.w(TAG, "No focused editable field found within ${waitForFocusMs}ms")
                return false
            }

            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Type text into focused field result: $success")
            if (success) {
                submitImeEnter(focusedNode)
                dismissKeyboardIfStillFocused(text)
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
            val root = accessibilityService.rootInActiveWindow
            val focusedNode = if (root != null) findFocusedEditable(root) else null
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

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused || node.isAccessibilityFocused) {
            if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
                node.isEditable
            ) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true || node.isEditable) {
            if (node.isVisibleToUser && node.isEnabled) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            if (result != null) return result
        }
        return null
    }

    private fun findBlinkitSearchField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
            val child = node.getChild(i) ?: continue
            val result = findBlinkitSearchField(child)
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
    
    private fun findTargetElement(recommendedAction: JSONObject): AccessibilityNodeInfo? {
        Log.d(TAG, "Searching for target element")
        
        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "Root node is null")
            return null
        }
        
        // First try to parse action_target text for element information
        val actionTarget = recommendedAction.optString("action_target", "")
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
            }
            
            // Special handling for ADD buttons - prioritize green ADD buttons over heart icons
            if (actionTarget.contains("ADD", ignoreCase = true) || actionTarget.contains("add", ignoreCase = true)) {
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

    private fun isToolbarOrBackNode(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (desc.contains("navigate up") || viewId.contains("action_bar") || viewId.contains("toolbar")) {
            return true
        }
        return false
    }
    
    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (rootNode.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByText(child, text)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun findNodeByExactText(rootNode: AccessibilityNodeInfo, exact: String): AccessibilityNodeInfo? {
        if (rootNode.text?.toString()?.equals(exact, ignoreCase = true) == true) {
            return rootNode
        }
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByExactText(child, exact)
                if (result != null) return result
            }
        }
        return null
    }
    
    private fun findNodeByContentDescription(rootNode: AccessibilityNodeInfo, contentDescription: String): AccessibilityNodeInfo? {
        if (rootNode.contentDescription?.toString()?.contains(contentDescription, ignoreCase = true) == true) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByContentDescription(child, contentDescription)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
    
    private fun findNodeByResourceId(rootNode: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (rootNode.viewIdResourceName == resourceId) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByResourceId(child, resourceId)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
    
    private fun findNodeByClassName(rootNode: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (rootNode.className?.toString() == className) {
            return rootNode
        }
        
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            if (child != null) {
                val result = findNodeByClassName(child, className)
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
    
    private fun findClickableChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable) {
                return child
            }
            // Recursively search in children
            val clickableDescendant = findClickableChild(child)
            if (clickableDescendant != null) {
                return clickableDescendant
            }
        }
        return null
    }
    
    private fun findClickableSearchElement(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
        val addByText = findNodeByText(rootNode, "ADD")
        if (addByText != null && addByText.isClickable) {
            Log.d(TAG, "Found ADD button by exact text: 'ADD'")
            return addByText
        }
        
        // Look for elements with text containing "add" (case insensitive)
        val addByTextLower = findNodeByText(rootNode, "add")
        if (addByTextLower != null && addByTextLower.isClickable) {
            Log.d(TAG, "Found ADD button by text: 'add'")
            return addByTextLower
        }
        
        // Look for Button elements that might be ADD buttons
        val buttons = findAllNodesByClassName(rootNode, "android.widget.Button")
        for (button in buttons) {
            val buttonText = button.text?.toString()?.lowercase()
            if (button.isClickable && (buttonText == "add" || buttonText?.contains("add") == true)) {
                Log.d(TAG, "Found ADD button by Button class and text")
                return button
            }
        }
        
        // Look for elements with content description containing "add"
        val addByDesc = findNodeByContentDescription(rootNode, "add")
        if (addByDesc != null && addByDesc.isClickable) {
            Log.d(TAG, "Found ADD button by content description: 'add'")
            return addByDesc
        }
        
        Log.d(TAG, "No ADD button element found")
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
            var overlayDeflection = 0
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

            // Deflect taps if inside overlay rectangle (with padding)
            try {
                val app = accessibilityService.application as? MyApplication
                val screenService = app?.getScreenCaptureService()
                val overlayRect = screenService?.getOverlayRect()
                if (overlayRect != null && !overlayRect.isEmpty) {
                    // Inflate by padding (e.g., 24dp on all sides)
                    val density = accessibilityService.resources.displayMetrics.density
                    val pad = (24 * density).toInt()
                    val inflated = Rect(
                        overlayRect.left - pad,
                        overlayRect.top - pad,
                        overlayRect.right + pad,
                        overlayRect.bottom + pad
                    )
                    if (inflated.contains(x, y)) {
                        // Deflect downward by overlay height + 24px
                        overlayDeflection = overlayRect.height() + 24
                        y += overlayDeflection
                        Log.d(TAG, "Deflected tap from ($x, ${y - overlayDeflection}) to ($x, $y) to avoid overlay")
                        DebugLogger.logDebug(TAG, "Overlay deflection applied: +$overlayDeflection px")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overlay deflection check failed: ${e.message}")
            }
            
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
        val isAddButton = actionTarget.contains("ADD", ignoreCase = true) || actionTarget.contains("add", ignoreCase = true)
        
        if (isAddButton) {
            // Wait a bit more for UI to update
            Thread.sleep(300)
            
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
    
    private fun findAllNodesByClassName(rootNode: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        
        fun search(node: AccessibilityNodeInfo) {
            if (node.className?.toString() == className) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
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
            var overlayDeflection = 0
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

            // Deflect taps if inside overlay rectangle (with padding)
            try {
                val app = accessibilityService.application as? MyApplication
                val screenService = app?.getScreenCaptureService()
                val overlayRect = screenService?.getOverlayRect()
                if (overlayRect != null && !overlayRect.isEmpty) {
                    // Inflate by padding (e.g., 24dp on all sides)
                    val density = accessibilityService.resources.displayMetrics.density
                    val pad = (24 * density).toInt()
                    val inflated = Rect(
                        overlayRect.left - pad,
                        overlayRect.top - pad,
                        overlayRect.right + pad,
                        overlayRect.bottom + pad
                    )
                    if (inflated.contains(x, y)) {
                        // Deflect downward by overlay height + 24px
                        overlayDeflection = overlayRect.height() + 24
                        y += overlayDeflection
                        Log.d(TAG, "Deflected tap from ($x, ${y - overlayDeflection}) to ($x, $y) to avoid overlay")
                        DebugLogger.logDebug(TAG, "Overlay deflection applied: +$overlayDeflection px")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overlay deflection check failed: ${e.message}")
            }
            
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
