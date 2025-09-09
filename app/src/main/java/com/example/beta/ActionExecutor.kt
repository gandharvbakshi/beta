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
    
    fun executeAction(recommendedAction: JSONObject): Boolean {
        return try {
            val actionType = recommendedAction.getString("action_type")
            val actionTarget = recommendedAction.getString("action_target")
            val confidenceScore = recommendedAction.getDouble("confidence_score")
            
            Log.d(TAG, "Executing action: $actionType on $actionTarget (confidence: $confidenceScore)")
            
            // Check if we have required permissions
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing required accessibility permissions")
                return false
            }
            
            // Safety check: Only execute actions with high confidence
            if (confidenceScore < 0.7) {
                Log.w(TAG, "Action confidence too low ($confidenceScore), skipping execution")
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
            
            Log.d(TAG, "Final click action result: $success")
            success
        } else {
            Log.w(TAG, "Target element not found, trying fallback coordinates")
            performClickByCoordinates(recommendedAction)
        }
    }
    
    private fun performScroll(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting to perform scroll action")
        
        val targetNode = findTargetElement(recommendedAction)
        return if (targetNode != null) {
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Log.d(TAG, "Scroll action result: $success")
            success
        } else {
            Log.w(TAG, "Target element not found for scroll")
            false
        }
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
    
    private fun findTargetElement(recommendedAction: JSONObject): AccessibilityNodeInfo? {
        Log.d(TAG, "Searching for target element")
        
        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "Root node is null")
            return null
        }
        
        // Try multiple methods to find the element
        val elementSelector = recommendedAction.optJSONObject("element_selector")
        if (elementSelector != null) {
            // Method 1: Try by text content
            val text = elementSelector.optString("text", "")
            if (text.isNotEmpty()) {
                val nodeByText = findNodeByText(rootNode, text)
                if (nodeByText != null) {
                    Log.d(TAG, "Found element by text: '$text'")
                    return nodeByText
                }
            }
            
            // Method 2: Try by content description
            val contentDescription = elementSelector.optString("content_description", "")
            if (contentDescription.isNotEmpty()) {
                val nodeByDesc = findNodeByContentDescription(rootNode, contentDescription)
                if (nodeByDesc != null) {
                    Log.d(TAG, "Found element by content description: '$contentDescription'")
                    return nodeByDesc
                }
            }
            
            // Method 3: Try by resource ID
            val resourceId = elementSelector.optString("resource_id", "")
            if (resourceId.isNotEmpty()) {
                val nodeById = findNodeByResourceId(rootNode, resourceId)
                if (nodeById != null) {
                    Log.d(TAG, "Found element by resource ID: '$resourceId'")
                    return nodeById
                }
            }
            
            // Method 4: Try by class name
            val className = elementSelector.optString("class_name", "")
            if (className.isNotEmpty()) {
                val nodeByClass = findNodeByClassName(rootNode, className)
                if (nodeByClass != null) {
                    Log.d(TAG, "Found element by class name: '$className'")
                    return nodeByClass
                }
            }
        }
        
        Log.w(TAG, "Could not find target element using any method")
        return null
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
    
    private fun performClickByCoordinates(recommendedAction: JSONObject): Boolean {
        Log.d(TAG, "Attempting click by coordinates")
        
        val fallbackCoordinates = recommendedAction.optJSONObject("fallback_coordinates")
        if (fallbackCoordinates != null) {
            val x = fallbackCoordinates.optInt("x", 0)
            val y = fallbackCoordinates.optInt("y", 0)
            
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
            return success
        }
        
        Log.w(TAG, "No fallback coordinates available")
        return false
    }
}
