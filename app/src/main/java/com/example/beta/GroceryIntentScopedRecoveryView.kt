package com.example.beta

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/** Text-only, inline phrase recovery surface. Persistence and validation belong to the host. */
internal class GroceryIntentScopedRecoveryView(
    activity: Activity,
    originalInstruction: String,
    currentInstruction: String,
    changedRanges: List<GroceryIntentRecoveryRecord.ChangedRange>,
    quotes: List<GroceryIntentReviewV2Quote>,
    selectedQuote: GroceryIntentReviewV2Quote?,
    editorText: String?,
    restored: Boolean,
    onChoose: (GroceryIntentReviewV2Quote) -> Unit,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onVoice: () -> Unit,
    pendingQuote: GroceryIntentReviewV2Quote? = null,
    editKind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE,
    pendingEditKind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE,
    onChooseWholeMessage: () -> Unit = {},
    focusEditorOnOpen: Boolean = false,
) : LinearLayout(activity) {
    private val wholeMessage = editKind == GroceryIntentEditKind.WHOLE_MESSAGE
    private val phraseActions = mutableMapOf<GroceryIntentReviewV2Quote, Button>()
    internal val editor: EditText? = if (selectedQuote == null) null else EditText(activity)
    private var saveButton: Button? = null
    private val status = TextView(activity)
    private val saveAction = onSave
    private val initialText = selectedQuote?.text.orEmpty()
    private var lastText = editorText ?: initialText
    private var bindingEditor = false

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val title = label(if (quotes.isEmpty()) "Help Beta with your message" else "Help Beta with your message (${quotes.size})", true)
        title.id = R.id.groceryIntentScopedTitle
        ViewCompat.setAccessibilityHeading(title, true)
        addView(title, params(bottom = 10))
        addView(label("Beta couldn't use some parts of your message. After you change them, Beta checks your whole request again.", false), params(bottom = 14))

        quotes.forEach { quote ->
            val card = LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.beta_card_amber)
            }
            card.addView(label("Beta can't follow this yet: '${quote.text}'", true), params(bottom = 8))
            if (!wholeMessage && selectedQuote == quote) {
                addEditor(card, quote.text, editorText, restored, onTextChanged, onCancelEdit, onVoice)
            } else {
                val label = if (quote == pendingQuote && pendingEditKind == GroceryIntentEditKind.PHRASE) "Continue editing" else "Change wording"
                val action = button(label, R.id.groceryIntentScopedChoose) { onChoose(quote) }
                phraseActions[quote] = action
                card.addView(action)
            }
            addView(card, params(bottom = 12))
        }
        val wholeAction = button(if (pendingQuote != null && pendingEditKind == GroceryIntentEditKind.WHOLE_MESSAGE) "Continue editing your message" else "Edit my whole message", R.id.groceryIntentScopedWholeMessage) { onChooseWholeMessage() }
        addView(wholeAction, params(bottom = 4))
        addView(label("Change several parts at once, then save once.", false), params(bottom = 12))
        if (wholeMessage && selectedQuote != null) {
            val wholeCard = LinearLayout(activity).apply { orientation = VERTICAL }
            addEditor(wholeCard, currentInstruction, editorText, restored, onTextChanged, onCancelEdit, onVoice)
            addView(wholeCard, params(bottom = 12))
        }
        val original = readOnly(originalInstruction)
        original.id = R.id.groceryIntentScopedOriginal
        original.visibility = if (wholeMessage) GONE else VISIBLE
        val originalToggle = button("Your original message", R.id.groceryIntentScopedOriginalToggle) {
            original.visibility = if (original.visibility == VISIBLE) GONE else VISIBLE
            ViewCompat.setStateDescription(findViewById(R.id.groceryIntentScopedOriginalToggle), if (original.visibility == VISIBLE) "Expanded" else "Collapsed")
        }
        ViewCompat.setStateDescription(originalToggle, if (original.visibility == VISIBLE) "Expanded" else "Collapsed")
        originalToggle.contentDescription = "Your original message. Tap to expand or collapse."
        addView(originalToggle, params(top = 6, bottom = 6))
        addView(original, params(bottom = 12))
        addView(label("Your current wording", true), params(bottom = 6))
        addView(readOnly(currentInstruction), params(bottom = 12))
        changedRanges.filter { it.startUtf16 >= 0 && it.endUtf16 <= currentInstruction.length && it.endUtf16 > it.startUtf16 }
            .forEach { range -> addView(label("Changed by you: '${currentInstruction.substring(range.startUtf16, range.endUtf16)}'", false), params(bottom = 8)) }
        refreshSave()
        if (restored) showStatus(if (wholeMessage) "Your unsaved changes were restored. Save or cancel." else "Your unsaved change was restored. Save or cancel.")
        if (focusEditorOnOpen) post { editor?.requestFocus() }
    }

    private fun addEditor(card: LinearLayout, source: String, editorText: String?, restored: Boolean,
        onTextChanged: (String) -> Unit, onCancelEdit: () -> Unit, onVoice: () -> Unit) {
                val heading = label(if (wholeMessage) "Change your message" else "Change your words", true)
                heading.id = R.id.groceryIntentScopedEditorHeading
                ViewCompat.setAccessibilityHeading(heading, true)
                heading.accessibilityTraversalBefore = R.id.groceryIntentScopedEditor
                heading.nextFocusForwardId = R.id.groceryIntentScopedEditor
                card.addView(heading, params(bottom = 6))
                if (!wholeMessage) card.addView(label("Your words: '$source'", false), params(bottom = 8))
                editor?.apply {
                    id = R.id.groceryIntentScopedEditor
                    accessibilityTraversalAfter = R.id.groceryIntentScopedEditorHeading
                    setText(editorText ?: source)
                    contentDescription = if (wholeMessage) "Your whole message" else "Your changed words"
                    setTextSize(17f)
                    setTextColor(ContextCompat.getColor(context, R.color.beta_text_primary))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    isSingleLine = false
                    setSelection(text.length)
                    minLines = 2
                    if (wholeMessage) {
                        maxLines = 6
                        isVerticalScrollBarEnabled = true
                        setOnTouchListener { view, event ->
                            view.parent?.requestDisallowInterceptTouchEvent(event.action != android.view.MotionEvent.ACTION_UP && event.action != android.view.MotionEvent.ACTION_CANCEL)
                            false
                        }
                    }
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundResource(R.drawable.beta_card)
                    isSaveEnabled = false
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            lastText = s?.toString().orEmpty()
                            if (!bindingEditor) onTextChanged(lastText)
                            refreshSave()
                        }
                    })
                }
                editor?.let { card.addView(it, params(bottom = 8)) }
                val voice = button("Use microphone", R.id.groceryIntentScopedVoice) { onVoice() }
                card.addView(voice, params(bottom = 6))
                val actions = LinearLayout(context).apply { orientation = VERTICAL }
                val save = button("Save change", R.id.groceryIntentScopedSave) { saveAction() }
                saveButton = save
                actions.addView(save, params())
                actions.addView(button("Cancel", R.id.groceryIntentScopedCancel) { onCancelEdit() }, params())
                card.addView(actions)
                status.apply { id = R.id.groceryIntentScopedStatus; setTextSize(17f); setTextColor(ContextCompat.getColor(context, R.color.beta_amber)); visibility = GONE; isFocusable = true; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
                card.addView(status, params(top = 8))
                if (restored) showStatus("Your unsaved change was restored. Save or cancel.")
    }

    internal fun updateStatus(message: String?, saveFailed: Boolean = false) {
        showStatus(message.orEmpty())
        if (saveFailed) {
            saveButton?.text = "Try again"
            saveButton?.isEnabled = lastText.isNotBlank() && lastText != initialText
        } else refreshSave()
    }

    /** Voice transcription updates only the in-memory editor; it never saves implicitly. */
    internal fun updateText(text: String, selectionStart: Int = text.length, selectionEnd: Int = selectionStart) {
        val input = editor ?: return
        if (input.text.toString() == text) {
            input.setSelection(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
            return
        }
        bindingEditor = true
        input.setText(text)
        input.setSelection(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
        bindingEditor = false
        lastText = text
        refreshSave()
    }

    internal fun editorSelection(): Pair<Int, Int>? = editor?.let { it.selectionStart to it.selectionEnd }

    internal fun focusStatus() {
        if (status.visibility != VISIBLE) return
        status.requestFocus()
        status.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    internal fun focusOrigin(kind: GroceryIntentEditKind, quote: GroceryIntentReviewV2Quote?) {
        val action = if (kind == GroceryIntentEditKind.WHOLE_MESSAGE) findViewById<Button>(R.id.groceryIntentScopedWholeMessage) else phraseActions[quote]
        action?.requestFocus()
        action?.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    private fun refreshSave() {
        saveButton?.apply {
            text = "Save change"
            isEnabled = lastText.isNotBlank() && lastText != initialText
        }
        if (lastText.isBlank()) showStatus("Type or say something.")
        else if (lastText == initialText) showStatus("Change the words first.")
        else if (status.text.toString() == "Type or say something." || status.text.toString() == "Change the words first.") showStatus("")
    }

    private fun showStatus(message: String) { status.text = message; status.visibility = if (message.isBlank()) GONE else VISIBLE }
    private fun label(text: String, heading: Boolean) = TextView(context).apply { this.text = text; setTextSize(17f); setTextColor(ContextCompat.getColor(context, R.color.beta_text_primary)); if (heading) setTypeface(typeface, android.graphics.Typeface.BOLD); setLineSpacing(0f, 1.12f) }
    private fun readOnly(text: String) = TextView(context).apply { this.text = text; setTextSize(17f); setTextColor(ContextCompat.getColor(context, R.color.beta_text_primary)); setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundResource(R.drawable.beta_card); setTextIsSelectable(true) }
    private fun button(text: String, idValue: Int, action: () -> Unit) = Button(context).apply { id = idValue; this.text = text; setTextSize(17f); minHeight = dp(48); isAllCaps = false; setOnClickListener { action() } }
    private fun params(top: Int = 0, bottom: Int = 0) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
