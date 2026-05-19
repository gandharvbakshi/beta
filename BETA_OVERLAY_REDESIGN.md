# Beta — Floating Overlay Redesign (v2)

> **Audience:** Codex working in `gandharvbakshi/beta`, branch `master`
> **Goal:** Bring the two `WindowManager` overlays — the floating status pill ("Emulator Mode - Tap for input") and the input popup it expands into — into the same warm, friendly system the main activity already uses.
> **Why:** A 60-year-old user sees these overlays *on top of their grocery app*. Right now they look like debug chrome: a dark `#80000000` block with white tech-y text, then a popup of unstyled `android.R.drawable.btn_default` buttons. They should feel like Beta — calm, warm, plain-language, and unmistakably trustworthy.

**What this touches**
- `app/src/main/res/layout/overlay_layout.xml` — *rewrite* (the floating pill)
- `app/src/main/res/layout/input_overlay.xml` — **new** (the tap-to-input popup)
- `app/src/main/res/drawable/` — five new shape drawables
- `app/src/main/res/values/strings.xml` — new strings; **remove** "Emulator" from user-visible labels
- `app/src/main/res/values/colors.xml` — one tiny addition (scrim)
- `app/src/main/java/com/example/beta/ScreenCaptureService.kt` — replace the hand-coded `EditText`/Button construction in `showSimpleInputOverlay()` and `showEmulatorInputOverlay()` with `LayoutInflater.from(this).inflate(R.layout.input_overlay, …)`, then wire the inflated views by id. Replace the user-visible status strings (the four `updateOverlayText` / `updateEmulatorOverlayText` call sites identified below).
- **No business logic changes.** Don't touch capture lifecycle, broadcast receivers, `submitInstruction`, sequence/generation tracking, scaling, retry logic, or emulator-vs-device branching. Emulator and device take the *same* visual path now; the only thing that differs internally is window width/positioning (keep that as-is).

---

## 1. Design principles for the overlays

1. **The overlay never says "Emulator" to the user.** The dev distinction matters in logs, not on a customer's screen.
2. **Pill ≠ alarm.** Soft white card on a hairline border, not a dark rectangle. A small β mark on the left makes it unmistakably Beta. A coloured dot on the right signals state (sage = ready, terracotta = listening, amber = working).
3. **Popup is a conversation, not a form.** Newsreader heading "What can I help with?", a roomy sand-coloured input, three example-prompt chips that turn into one-tap shortcuts ("Refill last week", etc.), and a clear two-button row — outlined "Cancel" + filled terracotta "Send to Beta →".
4. **Touch targets ≥ 56dp.** Buttons fill the row. The pill itself is at least 44dp tall.
5. **No `android.R.drawable.*` defaults.** Everything uses the Beta drawable system from v1.

---

## 2. New / changed strings — `app/src/main/res/values/strings.xml`

**Add these** new strings:

```xml
<!-- Floating overlay (pill) — user-visible status -->
<string name="overlay_status_ready">Tap to tell Beta</string>
<string name="overlay_status_listening">Beta is listening…</string>
<string name="overlay_status_working">Beta is working…</string>
<string name="overlay_status_capturing">Reading your screen…</string>

<!-- Input popup -->
<string name="input_title">What can I help with?</string>
<string name="input_subtitle">I\'ll build the cart. You finish the order.</string>
<string name="input_hint">For example, "2 kg atta and a packet of milk"</string>
<string name="input_example_refill">Refill last week\'s order</string>
<string name="input_example_basics">Bread, eggs and milk</string>
<string name="input_example_cleaning">Cleaning supplies</string>
<string name="input_close">Close</string>
<string name="input_send">Send to Beta</string>
<string name="input_or_speak">Or hold the mic to speak instead</string>
```

**Do not delete** any existing string. The `overlay_text` view still exists; we're just replacing what the service writes into it.

---

## 3. Color additions — `app/src/main/res/values/colors.xml`

Add one token (everything else already exists from v1):

```xml
<color name="beta_scrim">#73151B2A</color>   <!-- 45% deep-navy scrim behind input popup -->
```

---

## 4. New drawables

### 4.1 `drawable/beta_pill_overlay.xml` — the floating-pill background

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_surface"/>
    <stroke android:width="1.5dp" android:color="@color/beta_line"/>
    <corners android:radius="999dp"/>
</shape>
```

> The drop shadow on the pill is achieved with `android:elevation="12dp"` on the root `LinearLayout` (see §5.1) — no need for a layered drawable.

### 4.2 `drawable/beta_dot_sage.xml` — state dot, ready

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/beta_success"/>
    <size android:width="10dp" android:height="10dp"/>
</shape>
```

### 4.3 `drawable/beta_dot_terracotta.xml` — state dot, listening

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/beta_primary"/>
    <size android:width="10dp" android:height="10dp"/>
</shape>
```

### 4.4 `drawable/beta_dot_amber.xml` — state dot, working

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/beta_amber"/>
    <size android:width="10dp" android:height="10dp"/>
</shape>
```

### 4.5 `drawable/beta_input_card.xml` — input popup background (cream card, 24dp radius)

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_background"/>
    <corners android:radius="24dp"/>
</shape>
```

### 4.6 `drawable/beta_input_field.xml` — the text-input box itself

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_surface"/>
    <stroke android:width="1.5dp" android:color="@color/beta_line"/>
    <corners android:radius="16dp"/>
</shape>
```

### 4.7 `drawable/beta_scrim.xml` — the dim behind the popup

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_scrim"/>
</shape>
```

### 4.8 `drawable/beta_close_circle.xml` — round close-button on the popup

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/beta_surface_alt"/>
    <size android:width="36dp" android:height="36dp"/>
</shape>
```

---

## 5. Layouts

### 5.1 **Replace** `app/src/main/res/layout/overlay_layout.xml`

This file is already inflated and added to the window manager (see `ScreenCaptureService.kt` around lines 1290–1296 / 1360–1370). **The id `overlay_text` MUST be preserved** — the service does `overlayView.findViewById<TextView>(R.id.overlay_text)` in `updateOverlayText()` / `updateEmulatorOverlayText()`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:minHeight="44dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="10dp"
    android:paddingEnd="16dp"
    android:paddingTop="8dp"
    android:paddingBottom="8dp"
    android:background="@drawable/beta_pill_overlay"
    android:elevation="12dp">

    <!-- β monogram -->
    <TextView
        android:layout_width="28dp"
        android:layout_height="28dp"
        android:background="@drawable/beta_step_current"
        android:gravity="center"
        android:fontFamily="@font/newsreader"
        android:text="β"
        android:textColor="@color/beta_on_primary"
        android:textSize="16sp"/>

    <!-- Status text — text is set programmatically -->
    <TextView
        android:id="@+id/overlay_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="10dp"
        android:fontFamily="@font/jakarta_semibold"
        android:text="@string/overlay_status_ready"
        android:textColor="@color/beta_text_primary"
        android:textSize="14sp"
        android:maxLines="1"
        android:ellipsize="end"
        android:maxWidth="180dp"/>

    <!-- State dot — drawable is swapped from Kotlin via setBackgroundResource -->
    <View
        android:id="@+id/overlay_dot"
        android:layout_width="10dp"
        android:layout_height="10dp"
        android:layout_marginStart="10dp"
        android:background="@drawable/beta_dot_sage"/>
</LinearLayout>
```

### 5.2 **New** `app/src/main/res/layout/input_overlay.xml`

This is what the service currently builds in Kotlin inside `showSimpleInputOverlay()` and `showEmulatorInputOverlay()`. Move all of that markup here.

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/beta_scrim">

    <LinearLayout
        android:id="@+id/inputCard"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:layout_marginHorizontal="16dp"
        android:orientation="vertical"
        android:padding="22dp"
        android:background="@drawable/beta_input_card"
        android:elevation="24dp">

        <!-- Header row: β + title block + close -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <TextView
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:background="@drawable/beta_step_current"
                android:gravity="center"
                android:fontFamily="@font/newsreader"
                android:text="β"
                android:textColor="@color/beta_on_primary"
                android:textSize="20sp"/>

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="12dp"
                android:orientation="vertical">

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:fontFamily="@font/newsreader"
                    android:text="@string/input_title"
                    android:textColor="@color/beta_text_primary"
                    android:textSize="22sp"/>

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:fontFamily="@font/jakarta"
                    android:text="@string/input_subtitle"
                    android:textColor="@color/beta_text_secondary"
                    android:textSize="13sp"/>
            </LinearLayout>

            <TextView
                android:id="@+id/inputClose"
                android:layout_width="36dp"
                android:layout_height="36dp"
                android:background="@drawable/beta_close_circle"
                android:gravity="center"
                android:fontFamily="@font/jakarta_semibold"
                android:text="×"
                android:textColor="@color/beta_text_primary"
                android:textSize="18sp"
                android:contentDescription="@string/input_close"/>
        </LinearLayout>

        <!-- The EditText itself -->
        <EditText
            android:id="@+id/inputField"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:minHeight="110dp"
            android:padding="16dp"
            android:background="@drawable/beta_input_field"
            android:fontFamily="@font/jakarta"
            android:hint="@string/input_hint"
            android:textColor="@color/beta_text_primary"
            android:textColorHint="@color/beta_text_secondary"
            android:textSize="17sp"
            android:inputType="textMultiLine|textCapSentences"
            android:gravity="top|start"
            android:lineSpacingExtra="2dp"/>

        <!-- Example-prompt chips -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/inputChipRefill"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginEnd="6dp"
                android:paddingHorizontal="12dp"
                android:paddingVertical="10dp"
                android:background="@drawable/beta_app_chip"
                android:gravity="center"
                android:fontFamily="@font/jakarta_semibold"
                android:text="@string/input_example_refill"
                android:textColor="@color/beta_text_primary"
                android:textSize="13sp"
                android:maxLines="1"
                android:ellipsize="end"/>

            <TextView
                android:id="@+id/inputChipBasics"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginHorizontal="3dp"
                android:paddingHorizontal="12dp"
                android:paddingVertical="10dp"
                android:background="@drawable/beta_app_chip"
                android:gravity="center"
                android:fontFamily="@font/jakarta_semibold"
                android:text="@string/input_example_basics"
                android:textColor="@color/beta_text_primary"
                android:textSize="13sp"
                android:maxLines="1"
                android:ellipsize="end"/>

            <TextView
                android:id="@+id/inputChipCleaning"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="6dp"
                android:paddingHorizontal="12dp"
                android:paddingVertical="10dp"
                android:background="@drawable/beta_app_chip"
                android:gravity="center"
                android:fontFamily="@font/jakarta_semibold"
                android:text="@string/input_example_cleaning"
                android:textColor="@color/beta_text_primary"
                android:textSize="13sp"
                android:maxLines="1"
                android:ellipsize="end"/>
        </LinearLayout>

        <!-- Buttons -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:orientation="horizontal">

            <Button
                android:id="@+id/inputCancel"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:minHeight="56dp"
                android:background="@drawable/beta_btn_secondary"
                android:fontFamily="@font/jakarta_semibold"
                android:text="@string/input_close"
                android:textAllCaps="false"
                android:textColor="@color/beta_text_primary"
                android:textSize="16sp"
                android:stateListAnimator="@null"/>

            <Button
                android:id="@+id/inputSubmit"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="2"
                android:layout_marginStart="10dp"
                android:minHeight="56dp"
                android:background="@drawable/beta_btn_primary"
                android:fontFamily="@font/jakarta_semibold"
                android:text="@string/input_send"
                android:textAllCaps="false"
                android:textColor="@color/beta_on_primary"
                android:textSize="17sp"
                android:stateListAnimator="@null"/>
        </LinearLayout>

        <!-- "Or hold mic to speak" affordance — leave non-interactive for now;
             wire up only if voice mode is already available in this service. -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center"
            android:fontFamily="@font/jakarta"
            android:text="@string/input_or_speak"
            android:textColor="@color/beta_text_secondary"
            android:textSize="13sp"/>
    </LinearLayout>
</FrameLayout>
```

---

## 6. Kotlin changes — `ScreenCaptureService.kt`

> Keep the file shape. Don't restructure the class or change permission/notification logic.

### 6.1 New helper near the other overlay helpers (right after `updateOverlayText` / `updateEmulatorOverlayText`)

```kotlin
/** Possible visual states for the floating pill. */
enum class OverlayState { READY, LISTENING, WORKING, CAPTURING }

/**
 * Single source of truth for what the pill looks/reads like.
 * Use this from every code path that currently calls updateOverlayText() /
 * updateEmulatorOverlayText() with a raw string.
 */
fun setOverlayState(state: OverlayState) {
    if (!::overlayView.isInitialized) return
    val textView = overlayView.findViewById<TextView>(R.id.overlay_text) ?: return
    val dot      = overlayView.findViewById<View>(R.id.overlay_dot)      ?: return

    val (labelRes, dotDrawable) = when (state) {
        OverlayState.READY      -> R.string.overlay_status_ready      to R.drawable.beta_dot_sage
        OverlayState.LISTENING  -> R.string.overlay_status_listening  to R.drawable.beta_dot_terracotta
        OverlayState.WORKING    -> R.string.overlay_status_working    to R.drawable.beta_dot_amber
        OverlayState.CAPTURING  -> R.string.overlay_status_capturing  to R.drawable.beta_dot_amber
    }
    textView.setText(labelRes)
    dot.setBackgroundResource(dotDrawable)
}
```

### 6.2 Replace the raw-string call sites

Find every place in `ScreenCaptureService.kt` that does:

```kotlin
overlayText.text = "Emulator Mode - Tap for input"
updateOverlayText("Ready - Tap to add instruction")
updateEmulatorOverlayText("Ready - Tap for input (Emulator)")
updateEmulatorOverlayText("Tap for input (Emulator)")
updateOverlayText("Capturing...")
// etc.
```

Replace each with the appropriate `setOverlayState(OverlayState.X)` call:

| Old string (contains)              | New call                              |
|------------------------------------|---------------------------------------|
| `Tap for input`, `Tap to add…`      | `setOverlayState(OverlayState.READY)`     |
| `Listening`, `Beta is listening`    | `setOverlayState(OverlayState.LISTENING)` |
| `Processing`, `Working`, `Adding…`  | `setOverlayState(OverlayState.WORKING)`   |
| `Capturing`, `Reading`              | `setOverlayState(OverlayState.CAPTURING)` |

The existing `updateOverlayText()` and `updateEmulatorOverlayText()` functions can stay as deprecated thin wrappers (they keep the dot in sync by inferring state from the string, OR they no-op — either is fine; new code shouldn't call them).

### 6.3 Rewrite `showSimpleInputOverlay()` to inflate the new layout

Replace the entire body of `showSimpleInputOverlay()` (and the emulator twin) with:

```kotlin
private fun showSimpleInputOverlay() {
    try {
        Log.d("ScreenCaptureService", "Inflating input_overlay.xml")
        hideInputOverlay()

        val inflated = LayoutInflater.from(this).inflate(R.layout.input_overlay, null)
        val card     = inflated.findViewById<View>(R.id.inputCard)
        val input    = inflated.findViewById<EditText>(R.id.inputField)
        val cancel   = inflated.findViewById<Button>(R.id.inputCancel)
        val submit   = inflated.findViewById<Button>(R.id.inputSubmit)
        val close    = inflated.findViewById<View>(R.id.inputClose)
        val chipRefill   = inflated.findViewById<TextView>(R.id.inputChipRefill)
        val chipBasics   = inflated.findViewById<TextView>(R.id.inputChipBasics)
        val chipCleaning = inflated.findViewById<TextView>(R.id.inputChipCleaning)

        input.setText(currentInputText ?: "")

        val dismiss = { hideInputOverlay() }
        cancel.setOnClickListener { dismiss() }
        close .setOnClickListener { dismiss() }
        submit.setOnClickListener {
            submitInstruction(input.text.toString().trim())
        }

        // Tapping a chip pre-fills the field (user can edit or hit Send)
        chipRefill  .setOnClickListener { input.setText(getString(R.string.input_example_refill));   input.setSelection(input.text.length) }
        chipBasics  .setOnClickListener { input.setText(getString(R.string.input_example_basics));   input.setSelection(input.text.length) }
        chipCleaning.setOnClickListener { input.setText(getString(R.string.input_example_cleaning)); input.setSelection(input.text.length) }

        // Tap-on-scrim dismisses
        inflated.setOnClickListener { dismiss() }
        card.setOnClickListener { /* swallow */ }

        // Layout params — full-screen FrameLayout (scrim + centered card).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // Focusable so the EditText receives input and the IME can attach.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0f      // we paint our own scrim drawable for the warm tone
        }

        inputOverlayView   = inflated
        inputOverlayParams = params
        windowManager.addView(inflated, params)

        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    } catch (e: Exception) {
        Log.e("ScreenCaptureService", "Error inflating input overlay: ${e.message}", e)
    }
}
```

The emulator-specific `showEmulatorInputOverlay()` should now just call `showSimpleInputOverlay()` — visually they're identical. (If sizing differences are needed for the emulator, keep them in the `WindowManager.LayoutParams` block; the layout itself stays the same.)

### 6.4 Optional: remove the emulator parenthetical from internal logs

Internal log strings (`Log.d`) can keep "(Emulator)" for debugging. Only user-visible strings need to drop it.

---

## 7. Acceptance checklist

- [ ] Build succeeds; no resource errors.
- [ ] When the service is running but idle, the floating pill shows a **β monogram + "Tap to tell Beta" + sage dot** on a white pill — no dark `#80000000` rectangle anywhere.
- [ ] No user-visible string in the overlays contains the word "Emulator". (The app still detects emulator mode and uses emulator-specific window sizing — that's internal.)
- [ ] Pill changes to **terracotta dot + "Beta is listening…"** when capture/listening begins, and **amber dot + "Beta is working…"** while processing.
- [ ] Tapping the pill darkens the screen with a warm navy scrim (`#73151B2A`) and centers a cream card with:
  - "What can I help with?" in Newsreader 22sp
  - "I'll build the cart. You finish the order." in Jakarta 13sp grey
  - White 16dp-radius input with hint "For example, 2 kg atta and a packet of milk", min 110dp tall, 17sp Jakarta
  - Three sand-coloured chips: "Refill last week's order", "Bread, eggs and milk", "Cleaning supplies"
  - Outlined "Close" + filled terracotta "Send to Beta" buttons (proportions 1:2, both ≥56dp)
- [ ] Tapping a chip fills the input with that text (caret at end).
- [ ] "Send to Beta" calls `submitInstruction(input.text.toString().trim())` exactly like before — same downstream behaviour.
- [ ] Both Close × and Cancel buttons dismiss the popup; tapping the scrim outside the card also dismisses.
- [ ] Test on a physical device **and** the Pixel emulator the project already uses (Pixel 8 API 35) — both render the same.
- [ ] Test at system text scale 130% — text doesn't clip in the pill (it ellipsises), and the popup card grows correctly.
- [ ] TalkBack: pill reads "Beta. Tap to tell Beta. Ready." (or similar); each button in the popup announces its label.

---

## 8. Out of scope

- Voice mode (the "Or hold the mic to speak" line is a static affordance for now; wire actual hold-to-speak when voice-in-overlay is a tracked feature).
- Animated pulsing dot — current dot is static. Add a `ValueAnimator` later if desired; not required for v2.
- Drag-to-move pill behaviour — keep current positioning logic untouched.
- Non-emulator pill positioning (top-offset via `getOverlayTopOffsetPx()`) — keep as-is.

---

## 9. Visual reference

See `Beta UI Redesign.html` in this project, sections **06 · Floating pill (3 states)** and **07 · Tap to tell Beta — popup**. The pill artboard shows the three states (Ready / Listening / Working) stacked over a mock Blinkit screen so the on-app feel is obvious. The popup artboard shows the centered card with scrim, header, input, chips, and button row.
