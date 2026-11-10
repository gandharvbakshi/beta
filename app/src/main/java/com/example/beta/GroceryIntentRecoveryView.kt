package com.example.beta

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max

internal data class GroceryIntentRecoveryDisplay(
    val originalInstruction: String,
    val body: String,
    val heldRows: List<GroceryIntentRecoveryTextRow>,
    val understoodRows: List<GroceryIntentRecoveryTextRow> = emptyList(),
    val showJump: Boolean = false,
    val spokenPrompt: String,
    val editorText: String = originalInstruction,
)

internal data class GroceryIntentRecoveryTextRow(
    val title: String,
    val detail: String,
    val spoken: String,
)

/** Editable recovery content mounted inside the existing Swiggy step scroll area. */
internal class GroceryIntentRecoveryView(
    private val activity: Activity,
    private val display: GroceryIntentRecoveryDisplay,
    onTextChanged: (String) -> Unit,
    onRecheck: () -> Unit,
    onVoice: () -> Unit,
    onCancel: () -> Unit,
    onLeaveWithoutSaving: () -> Unit,
    onEditWholeMessage: (() -> Unit)? = null,
    allowDirtyWholeChoice: Boolean = false,
) : LinearLayout(activity) {
    internal val editor = EditText(activity)
    internal val spokenPrompt: String = display.spokenPrompt

    private val warning = TextView(activity)
    private val leaveButton = makeButton(
        label = "Leave without saving",
        id = R.id.groceryIntentRecoveryLeave,
        heightDp = 56,
        background = R.drawable.beta_btn_secondary,
        textColor = R.color.beta_text_primary,
        onClick = onLeaveWithoutSaving,
    )
    private val checkButton = makeButton(
        label = "Check my list again",
        id = R.id.groceryIntentRecoveryCheck,
        heightDp = 64,
        background = R.drawable.beta_btn_primary,
        textColor = R.color.white,
        textSizeSp = 18f,
        onClick = onRecheck,
    )
    private var lastAnnouncedStatus: String? = null
    private var viewportHeightPx: Int = 0
    private var wholeMessageButton: Button? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        if (display.showJump) {
            addView(makeButton(
                label = "Go to my list",
                id = R.id.groceryIntentRecoveryGoToList,
                heightDp = 56,
                background = R.drawable.beta_btn_secondary,
                textColor = R.color.beta_text_primary,
                onClick = ::scrollToEditor,
            ), spacedParams(bottomDp = 12))
        }

        addView(sectionLabel("Not confirmed yet"), spacedParams(bottomDp = 12))
        display.heldRows.forEach { addView(makeCard(it, amber = true), spacedParams(bottomDp = 12)) }

        if (onEditWholeMessage != null) {
            wholeMessageButton = makeButton(
                label = "Edit my whole message",
                id = R.id.groceryIntentRecoveryWholeMessage,
                heightDp = 48,
                background = R.drawable.beta_btn_secondary,
                textColor = R.color.beta_text_primary,
                onClick = {
                    // Never abandon an unsaved OR already-persisted legacy edit on a mode switch.
                    // The host also checks its authoritative source and last-saved state.
                    if (display.editorText == display.originalInstruction &&
                        (allowDirtyWholeChoice || editor.text.toString() == display.originalInstruction)) onEditWholeMessage()
                },
            ).apply { isEnabled = this@GroceryIntentRecoveryView.display.editorText == this@GroceryIntentRecoveryView.display.originalInstruction }
            addView(wholeMessageButton, spacedParams(bottomDp = 6))
            addView(TextView(activity).apply {
                text = "Change several parts at once, then save once."
                textSize = 17f
                setTextColor(ContextCompat.getColor(activity, R.color.beta_text_secondary))
            }, spacedParams(bottomDp = 12))
        }

        val editorLabel = sectionLabel("Your list")
        editorLabel.labelFor = R.id.groceryIntentRecoveryEditor
        addView(editorLabel, spacedParams(topDp = 8, bottomDp = 12))

        editor.apply {
            id = R.id.groceryIntentRecoveryEditor
            contentDescription = "Your list, editable"
            setText(this@GroceryIntentRecoveryView.display.editorText)
            isSaveEnabled = false
            setTextSize(17f)
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.beta_text_secondary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.beta_card)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setHorizontallyScrolling(false)
            clearFocus()
        }
        addView(editor, spacedParams(bottomDp = 12))

        addView(checkButton, spacedParams(topDp = 4, bottomDp = 12))

        warning.apply {
            id = R.id.groceryIntentRecoveryWarning
            setTextSize(17f)
            setTextColor(ContextCompat.getColor(activity, R.color.beta_amber))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.beta_card_amber)
            visibility = View.GONE
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(warning, spacedParams(bottomDp = 12))
        addView(leaveButton, spacedParams(bottomDp = 12))
        leaveButton.visibility = View.GONE

        addView(makeButton(
            label = "Say my list instead",
            id = R.id.groceryIntentRecoverySay,
            heightDp = 56,
            background = R.drawable.beta_btn_secondary,
            textColor = R.color.beta_text_primary,
            onClick = onVoice,
        ), spacedParams(bottomDp = 16))

        if (display.understoodRows.isNotEmpty()) {
            addView(sectionLabel("Beta read these parts as"), spacedParams(bottomDp = 12))
            display.understoodRows.forEach { addView(makeCard(it, amber = false), spacedParams(bottomDp = 12)) }
        }

        addView(sectionLabel("Your original words"), spacedParams(topDp = 4, bottomDp = 12))
        addView(makeReadOnlyCard(display.originalInstruction), spacedParams(bottomDp = 16))

        addView(makeButton(
            label = "Cancel — add nothing",
            id = R.id.groceryIntentRecoveryCancel,
            heightDp = 48,
            background = android.R.color.transparent,
            textColor = R.color.beta_text_primary,
            onClick = onCancel,
        ))

        // Install the observer only after the exact submitted text is in place.
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                wholeMessageButton?.isEnabled = display.editorText == display.originalInstruction &&
                    (allowDirtyWholeChoice || s?.toString().orEmpty() == display.originalInstruction)
                onTextChanged(s?.toString().orEmpty())
            }
        })

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val scrollHeight = nearestScrollView()?.height ?: 0
            if (scrollHeight > 0 && scrollHeight != viewportHeightPx) {
                updateViewportHeight(scrollHeight)
            }
        }
    }

    internal fun focusWholeMessageAction() {
        wholeMessageButton?.let { action ->
            action.requestFocus()
            action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    /** Updates inline save status without replacing the editor or changing its text. */
    fun updateStatus(message: String?, canLeaveWithoutSaving: Boolean) {
        val visibleMessage = message?.takeIf { it.isNotBlank() }
        val scroll = nearestScrollView()
        val oldScrollX = scroll?.scrollX ?: 0
        val oldScrollY = scroll?.scrollY ?: 0

        warning.text = visibleMessage.orEmpty()
        warning.visibility = if (visibleMessage == null) View.GONE else View.VISIBLE
        leaveButton.visibility = if (visibleMessage != null && canLeaveWithoutSaving) View.VISIBLE else View.GONE

        val shouldAnnounce = visibleMessage != null && visibleMessage != lastAnnouncedStatus && isTalkBackActive()
        if (shouldAnnounce) {
            lastAnnouncedStatus = visibleMessage
            warning.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        } else {
            if (visibleMessage == null) lastAnnouncedStatus = null
            scroll?.post { scroll.scrollTo(oldScrollX, oldScrollY) }
        }
    }

    /** Lets the host provide the available scroll viewport; four lines always win over the cap. */
    fun updateViewportHeight(heightPx: Int) {
        if (heightPx <= 0) return
        viewportHeightPx = heightPx
        val fourLineMinimum = editor.lineHeight * 4 + editor.paddingTop + editor.paddingBottom
        editor.maxHeight = max(fourLineMinimum, (heightPx * 0.4f).toInt())
        editor.requestLayout()
    }

    /** Scrolls the editor and its primary action into view without focusing the input. */
    fun scrollToEditor() {
        val scroll = nearestScrollView() ?: return
        val content = scroll.getChildAt(0) as? ViewGroup ?: return
        scroll.post {
            val target = Rect()
            fun include(view: View) {
                val rect = Rect(0, 0, view.width, view.height)
                content.offsetDescendantRectToMyCoords(view, rect)
                if (target.isEmpty) target.set(rect) else target.union(rect)
            }
            include(editor)
            include(checkButton)
            scroll.requestChildRectangleOnScreen(content, target, true)
        }
    }

    private fun makeCard(row: GroceryIntentRecoveryTextRow, amber: Boolean): View =
        LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(if (amber) R.drawable.beta_card_amber else R.drawable.beta_card_soft)
            contentDescription = row.spoken
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

            addView(TextView(activity).apply {
                text = row.title
                setTextAppearance(R.style.Beta_Body)
                textSize = 17f
                setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                includeFontPadding = true
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (row.detail.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = row.detail
                    setTextAppearance(R.style.Beta_BodySoft)
                    textSize = 17f
                    includeFontPadding = true
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6) }
                })
            }
        }.apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun makeReadOnlyCard(text: String): View = TextView(activity).apply {
        id = R.id.groceryIntentRecoveryOriginalWords
        this.text = text
        contentDescription = text
        setTextAppearance(R.style.Beta_Body)
        textSize = 17f
        setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackgroundResource(R.drawable.beta_card)
        includeFontPadding = true
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }

    private fun sectionLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextAppearance(R.style.Beta_Body)
        textSize = 17f
        setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        isAccessibilityHeading = true
    }

    private fun makeButton(
        label: String,
        id: Int,
        heightDp: Int,
        background: Int,
        textColor: Int,
        textSizeSp: Float = 17f,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        this.id = id
        text = label
        contentDescription = label
        minHeight = dp(heightDp)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isAllCaps = false
        stateListAnimator = null
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setTextColor(ContextCompat.getColor(activity, textColor))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setBackgroundResource(background)
        setOnClickListener { onClick() }
    }

    private fun spacedParams(topDp: Int = 0, bottomDp: Int = 0) =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (topDp > 0) topMargin = dp(topDp)
            if (bottomDp > 0) bottomMargin = dp(bottomDp)
        }

    private fun nearestScrollView(): ScrollView? {
        var ancestor: ViewParent? = parent
        while (ancestor is View) {
            if (ancestor is ScrollView) return ancestor
            ancestor = (ancestor as View).parent
        }
        return null
    }

    private fun isTalkBackActive(): Boolean {
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
