package com.example.beta

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
            
            // Add a small delay to ensure UI is stable
            Thread.sleep(500)
            
            when (actionType.lowercase()) {
                "click" -> performClick(recommendedAction)
                "scroll" -> performScroll(recommendedAction)
                "type" -> performType(recommendedAction)
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
        val isAddButton = actionTarget.contains("ADD", ignoreCase = true) || actionTarget.contains("add", ignoreCase = true)
        
        // For search bars, try coordinate-based clicking FIRST (more reliable)
        if (isSearchBar) {
            val coordinates = recommendedAction.optJSONObject("coordinates")
            if (coordinates != null) {
                Log.d(TAG, "Search bar detected - using coordinate-based click as primary method")
                val success = performClickByCoordinates(recommendedAction)
                if (success) {
                    Log.d(TAG, "Coordinate click successful for search bar")
                    return true
                } else {
                    Log.d(TAG, "Coordinate click failed, falling back to accessibility")
                }
            }
        }
        
        // For ADD buttons, use coordinate validation with retry logic
        if (isAddButton) {
            Log.d(TAG, "ADD button detected - using coordinate validation approach")
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
            success
        } else {
            Log.w(TAG, "Target element not found, trying fallback coordinates")
            val coordinateSuccess = performClickByCoordinates(recommendedAction)
            Log.d(TAG, "Fallback coordinate click result: $coordinateSuccess")
            return coordinateSuccess
        }
    }
    
    private fun performScroll(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform scroll action")
        
        val targetNode = findTargetElement(recommendedAction)
        if (targetNode == null) {
            Log.w(TAG, "Target element not found for scroll")
            return false
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
        return performGestureScroll(recommendedAction)
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
    
    private fun performType(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform type action")
        
        val targetNode = findTargetElement(recommendedAction)
        return if (targetNode != null) {
            // For typing, we need to focus the element first
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // Note: Actual text input would require additional implementation
            // This is a placeholder for the typing functionality
            Log.d(TAG, "Type action - element focused")
            true
        } else {
            Log.w(TAG, "Target element not found for type")
            false
        }
    }
    
    private fun performSwipe(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform swipe action")
        
        val targetNode = findTargetElement(recommendedAction)
        return if (targetNode != null) {
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Log.d(TAG, "Swipe action result: $success")
            success
        } else {
            Log.w(TAG, "Target element not found for swipe")
            false
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

    fun typeTextIntoFocusedField(text: String): Boolean {
        return try {
            val root = accessibilityService.rootInActiveWindow ?: return false
            val focusedNode = findFocusedEditable(root) ?: return false

            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Type text into focused field result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error typing into focused field: ${e.message}")
            false
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
        
        // Strategy 1: Try original coordinates
        val success1 = performClickByCoordinates(recommendedAction)
        if (success1) {
            Log.d(TAG, "ADD button click attempt 1: SUCCESS")
            // TODO: Add validation here - monitor for "Added to wishlist" vs "Added to cart"
            return true
        }
        
        // Strategy 2: Try multiple coordinate adjustments for typical product card layouts
        val coordinateStrategies = listOf(
            "Move right (avoid heart icon)" to Pair(originalX + 50, originalY),
            "Move down (avoid heart icon)" to Pair(originalX, originalY + 30),
            "Move right+down" to Pair(originalX + 30, originalY + 20),
            "Move left (if heart was on right)" to Pair(originalX - 30, originalY),
            "Move up (if ADD button is above)" to Pair(originalX, originalY - 20)
        )
        
        for ((strategyName, coords) in coordinateStrategies) {
            Log.d(TAG, "ADD button click attempt: $strategyName at (${coords.first}, ${coords.second})")
            
            val adjustedAction = JSONObject(recommendedAction.toString()).apply {
                put("coordinates", JSONObject().apply {
                    put("x", coords.first)
                    put("y", coords.second)
                })
            }
            
            val success = performClickByCoordinates(adjustedAction)
            if (success) {
                Log.d(TAG, "ADD button click SUCCESS with strategy: $strategyName")
                return true
            }
        }
        
        Log.d(TAG, "All ADD button click strategies failed")
        return false
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
            var x = coordinates.optInt("x", 0)
            var y = coordinates.optInt("y", 0)

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
                        val deflect = overlayRect.height() + 24
                        y += deflect
                        Log.d(TAG, "Deflected tap from ($x, ${y - deflect}) to ($x, $y) to avoid overlay")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Overlay deflection check failed: ${e.message}")
            }
            
            Log.d(TAG, "Clicking at coordinates: ($x, $y)")
            
            // Create a gesture for clicking at specific coordinates
            val gestureBuilder = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.d(TAG, "Coordinate click gesture completed")
                }
                
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.d(TAG, "Coordinate click gesture cancelled")
                }
            }
            
            // Create a tap gesture (press and release)
            val path = android.graphics.Path().apply { 
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 50 // 50ms duration for a quick tap
                ))
                .build()
            
            val success = accessibilityService.dispatchGesture(gesture, gestureBuilder, null)
            Log.d(TAG, "Coordinate click dispatch result: $success")
            
            // TODO: Add verification for "ADD" button clicks to detect wishlist misclicks
            // Could monitor for "Added to wishlist" toasts or check cart badge changes
            
            return success
        }
        
        Log.w(TAG, "No fallback coordinates available")
        return false
    }
}
