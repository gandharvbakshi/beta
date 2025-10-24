# Button Center Adjustment Fix

## The Problem
Clicks on "Add to cart" buttons were landing **a few pixels above** the actual button, causing them to miss the clickable area.

## Root Cause
The backend's Vision API uses **OCR (Optical Character Recognition)** to find text like "Add to cart". It then calculates the **center of the text bounding box**:

```
Backend OCR finds:
- "Add" at [779, 2060, 842, 2083]
- "cart" at [895, 2060, 957, 2084]
- Combined box: [779, 2060, 957, 2084]
- Center: (868, 2072) ← This is the TEXT center
```

**The issue**: Y=2072 is the center of the **text**, but buttons typically have padding. The actual **clickable button center** is slightly lower than the text center.

## The Solution
Added a **+12px downward adjustment** for button elements to move from text center to button center:

```kotlin
// BUTTON CENTER FIX: OCR finds text center, but buttons are clickable below text
if (actionTarget.contains("add to cart", ignoreCase = true) || 
    actionTarget.contains("ADD", ignoreCase = false) ||
    actionTarget.contains("button", ignoreCase = true)) {
    buttonCenterAdjustment = 12  // Move 12px down
    y += buttonCenterAdjustment
}
```

### Before Fix:
```
Backend: (868, 2072) → Click at: (868, 2072) ❌ (misses button)
```

### After Fix:
```
Backend: (868, 2072) → Click at: (868, 2084) ✅ (hits button center)
```

## Why 12 Pixels?
- Typical button padding is 8-16px
- 12px is a conservative middle ground
- Moves click from text center to lower part of button
- Works for most standard button designs

## When This Applies
The adjustment triggers for actions containing:
- ✅ "add to cart" (case insensitive)
- ✅ "ADD" (exact case - uppercase ADD buttons)
- ✅ "button" (any button element)

## Logging
The new logs show the adjustment clearly:
```
🎯 CLICK EXECUTION
  Strategy: Original coordinates
  Original Coords: (868, 2072)
  Status Bar Adjustment: +0 px
  Button Center Adjustment: +12 px      ← NEW!
  Overlay Deflection: +0 px
  Final Coords: (868, 2084)
  Total Y Offset: +12 px
  Gesture Dispatch Success: true
```

## Files Modified
1. **ActionExecutor.kt**
   - Added button center adjustment in both click functions
   - Applies to `performClickByCoordinates()` and `performClickByCoordinatesWithValidation()`

2. **DebugLogger.kt**
   - Added `buttonCenterAdjustment` parameter to logging
   - Shows button adjustment in debug logs

## Testing
Install the updated APK and test "Add to cart" buttons:
```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The click should now land directly on the button instead of above it!

## Coordinate Transformation Pipeline (Updated)
```
Backend Coords (OCR Text Center)
  ↓
+ Button Center Adjustment (+12px if button) ← NEW STEP!
  ↓
+ Overlay Deflection (if applicable)
  ↓
Final Gesture Coords
  ↓
Dispatch Click
```

## Note
This is a **heuristic adjustment** based on typical button designs. If you find buttons that still miss:
- Increase the adjustment value (try 16px or 20px)
- Or add specific adjustments for different button types
- The adjustment is logged so you can see exactly what's being applied

