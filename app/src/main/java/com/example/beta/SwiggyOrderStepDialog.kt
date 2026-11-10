package com.example.beta

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

internal data class SwiggyStepAction(
    val label: String,
    val onClick: () -> Unit,
)

internal data class SwiggyStepAcknowledgement(
    val label: String,
    val onCheckedChange: (Boolean) -> Unit,
)

internal data class SwiggyStepRow(
    val title: String,
    val detail: String? = null,
    val badge: String? = null,
    val tone: SwiggyStepTone = SwiggyStepTone.NEUTRAL,
    val action: SwiggyStepAction? = null,
    val isHeading: Boolean = false,
)

internal data class SwiggyStepChoice(
    val title: String,
    val detail: String? = null,
    val badge: String? = null,
    val onClick: () -> Unit,
)

internal enum class SwiggyStepTone {
    NEUTRAL,
    SUCCESS,
    AMBER,
}

internal data class SwiggyStepScreen(
    val eyebrow: String,
    val title: String,
    val message: String,
    val caption: String,
    val rows: List<SwiggyStepRow> = emptyList(),
    val choices: List<SwiggyStepChoice> = emptyList(),
    val safetyNote: String? = null,
    val primary: SwiggyStepAction? = null,
    val secondary: SwiggyStepAction? = null,
    val tertiary: SwiggyStepAction? = null,
    val cancel: (() -> Unit)? = null,
    val acknowledgement: SwiggyStepAcknowledgement? = null,
    val quantityAcknowledgement: SwiggyStepAcknowledgement? = null,
    val jumpToQuantityAcknowledgement: Boolean = false,
)

/** Full-screen, single-surface UI for the direct Swiggy MCP cart journey. */
internal class SwiggyOrderStepDialog(private val activity: Activity) {
    private val interactionGate = SwiggyStepInteractionGate()
    private var activeEpoch = 0L
    private var retryableEpoch = 0L
    private var beforeCancel: (() -> Unit)? = null
    private var recoveryView: GroceryIntentRecoveryView? = null
    private val dialog = object : Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar) {
        // Both legacy Back and the platform predictive-Back callback call cancel().
        // A save failure must be handled BEFORE the platform dismisses the window.
        override fun cancel() {
            val handler = beforeCancel
            if (handler != null) handler() else super.cancel()
        }
    }.apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_swiggy_order_step)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            statusBarColor = ContextCompat.getColor(activity, R.color.beta_background)
            navigationBarColor = ContextCompat.getColor(activity, R.color.beta_background)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private val root: View = dialog.findViewById(R.id.swiggyStepRoot)
    private val close: Button = dialog.findViewById(R.id.swiggyStepClose)
    private val eyebrow: TextView = dialog.findViewById(R.id.swiggyStepEyebrow)
    private val title: TextView = dialog.findViewById(R.id.swiggyStepTitle)
    private val message: TextView = dialog.findViewById(R.id.swiggyStepMessage)
    private val items: LinearLayout = dialog.findViewById(R.id.swiggyStepItems)
    private val safetyNote: TextView = dialog.findViewById(R.id.swiggyStepSafetyNote)
    private val primary: Button = dialog.findViewById(R.id.swiggyStepPrimary)
    private val secondary: Button = dialog.findViewById(R.id.swiggyStepSecondary)
    private val tertiary: Button = dialog.findViewById(R.id.swiggyStepTertiary)
    private val caption: TextView = dialog.findViewById(R.id.swiggyStepCaption)
    private val scroll: ScrollView = dialog.findViewById(R.id.swiggyStepScroll)
    private val scrollContent: LinearLayout = scroll.getChildAt(0) as LinearLayout
    private var activeAcknowledgementEpoch = 0L
    private var acknowledgementChecked = false
    private var consumedAcknowledgementEpoch = 0L
    private var activeAcknowledgement: SwiggyStepAcknowledgement? = null
    private var acknowledgementCheckBox: CheckBox? = null
    private var activeQuantityAcknowledgementEpoch = 0L
    private var quantityAcknowledgementChecked = false
    private var consumedQuantityAcknowledgementEpoch = 0L
    private var activeQuantityAcknowledgement: SwiggyStepAcknowledgement? = null
    private var quantityAcknowledgementCheckBox: CheckBox? = null
    private var quantityAcknowledgementError: TextView? = null
    private var quantityAcknowledgementCard: View? = null

    init {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                max(baseLeft, bars.left),
                max(baseTop, bars.top),
                max(baseRight, bars.right),
                max(baseBottom, bars.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun show(screen: SwiggyStepScreen, retryableActions: Boolean = false) {
        resetAcknowledgements(activeEpoch)
        removeQuantityAcknowledgementCard()
        val epoch = interactionGate.beginPresentation()
        activeEpoch = epoch
        retryableEpoch = if (retryableActions) epoch else 0L
        recoveryView = null
        beforeCancel = if (retryableActions) ({ if (activeEpoch == epoch) screen.cancel?.invoke() }) else null
        activeAcknowledgementEpoch = if (screen.acknowledgement == null) 0L else epoch
        acknowledgementChecked = false
        consumedAcknowledgementEpoch = 0L
        activeAcknowledgement = screen.acknowledgement
        activeQuantityAcknowledgementEpoch = if (screen.quantityAcknowledgement == null) 0L else epoch
        quantityAcknowledgementChecked = false
        consumedQuantityAcknowledgementEpoch = 0L
        activeQuantityAcknowledgement = screen.quantityAcknowledgement
        screen.acknowledgement?.onCheckedChange?.invoke(false)
        screen.quantityAcknowledgement?.onCheckedChange?.invoke(false)
        val cancel = screen.cancel
        eyebrow.text = screen.eyebrow
        eyebrow.visibility = if (screen.eyebrow.isBlank()) View.GONE else View.VISIBLE
        title.text = screen.title
        message.text = screen.message
        caption.text = screen.caption
        caption.contentDescription = screen.caption
        caption.visibility = if (screen.caption.isBlank()) View.GONE else View.VISIBLE
        renderContent(screen.rows, screen.choices, screen.acknowledgement, screen.quantityAcknowledgement,
            screen.jumpToQuantityAcknowledgement, epoch)
        renderQuantityAcknowledgement(screen.quantityAcknowledgement, epoch)

        safetyNote.text = screen.safetyNote.orEmpty()
        safetyNote.visibility = if (screen.safetyNote.isNullOrBlank()) View.GONE else View.VISIBLE

        bindAction(primary, screen.primary, R.drawable.beta_btn_primary, epoch)
        bindAction(secondary, screen.secondary, R.drawable.beta_btn_secondary, epoch)
        bindAction(tertiary, screen.tertiary, android.R.color.transparent, epoch)

        close.visibility = if (cancel == null) View.GONE else View.VISIBLE
        if (cancel != null) {
            close.setOnClickListener {
                runStepAction(epoch, cancel)
            }
        } else {
            close.setOnClickListener(null)
        }
        dialog.setCancelable(screen.cancel != null)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            runStepAction(epoch) {
                interactionGate.invalidate(epoch)
                cancel?.invoke()
            }
        }
        dialog.setOnDismissListener {
            interactionGate.invalidate(epoch)
            if (activeEpoch == epoch) {
                beforeCancel = null
                retryableEpoch = 0L
                recoveryView = null
                resetAcknowledgements(epoch)
                removeQuantityAcknowledgementCard()
            }
        }

        if (!dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        scroll.post { scroll.scrollTo(0, 0) }
        if (!retryableActions) title.announceForAccessibility("${screen.eyebrow}. ${screen.title}")
    }

    fun dismiss() {
        beforeCancel = null
        retryableEpoch = 0L
        recoveryView = null
        consumeAcknowledgement(activeEpoch)
        interactionGate.invalidate(activeEpoch)
        if (dialog.isShowing) dialog.dismiss() else resetAcknowledgements(activeEpoch)
    }

    fun showRecovery(
        display: GroceryIntentRecoveryDisplay,
        onTextChanged: (String) -> Unit,
        onRecheck: () -> Unit,
        onVoice: () -> Unit,
        onCancel: () -> Unit,
        onLeaveWithoutSaving: () -> Unit,
        onEditWholeMessage: (() -> Unit)? = null,
        allowDirtyWholeChoice: Boolean = false,
    ): GroceryIntentRecoveryView {
        show(SwiggyStepScreen("", "Please check your list", display.body, "", cancel = onCancel),
            retryableActions = true)
        val epoch = activeEpoch
        fun current(action: () -> Unit) { if (activeEpoch == epoch && retryableEpoch == epoch) action() }
        val content = GroceryIntentRecoveryView(activity, display,
            onTextChanged = { text -> current { onTextChanged(text) } },
            onRecheck = { current(onRecheck) }, onVoice = { current(onVoice) },
            onCancel = { current(onCancel) }, onLeaveWithoutSaving = { current(onLeaveWithoutSaving) },
            onEditWholeMessage = onEditWholeMessage?.let { callback -> { current(callback) } },
            allowDirtyWholeChoice = allowDirtyWholeChoice)
        recoveryView = content
        items.visibility = View.VISIBLE
        items.addView(content)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Input focus stays on a non-editable heading until the owner taps the editor.
        title.isFocusableInTouchMode = true
        title.requestFocus()
        title.post {
            if (activeEpoch == epoch) {
                content.updateViewportHeight(scroll.height)
                title.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
        return content
    }

    fun showScopedRecovery(
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
        onBack: () -> Unit,
        pendingQuote: GroceryIntentReviewV2Quote? = null,
        editKind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE,
        pendingEditKind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE,
        onChooseWholeMessage: () -> Unit = {},
        focusEditorOnOpen: Boolean = false,
    ): GroceryIntentScopedRecoveryView {
        show(SwiggyStepScreen("", "", "", "", cancel = onBack), retryableActions = true)
        val epoch = activeEpoch
        fun current(action: () -> Unit) { if (activeEpoch == epoch && retryableEpoch == epoch) action() }
        val content = GroceryIntentScopedRecoveryView(activity, originalInstruction, currentInstruction,
            changedRanges, quotes, selectedQuote, editorText, restored,
            onChoose = { quote -> current { onChoose(quote) } },
            onTextChanged = { text -> current { onTextChanged(text) } },
            onSave = { current(onSave) }, onCancelEdit = { current(onCancelEdit) }, onVoice = { current(onVoice) },
            pendingQuote = pendingQuote, editKind = editKind, pendingEditKind = pendingEditKind,
            onChooseWholeMessage = { current(onChooseWholeMessage) }, focusEditorOnOpen = focusEditorOnOpen)
        recoveryView = null
        items.visibility = View.VISIBLE
        items.addView(content)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        title.isFocusableInTouchMode = true
        title.requestFocus()
        title.post {
            if (activeEpoch == epoch && retryableEpoch == epoch) {
                if (selectedQuote != null && (focusEditorOnOpen || editKind == GroceryIntentEditKind.WHOLE_MESSAGE)) {
                    // Keyboard focus prepares cursor insertion, without opening the IME or taking
                    // accessibility focus away from the editor heading below.
                    content.editor?.requestFocus()
                }
                content.findViewById<View>(if (selectedQuote != null) R.id.groceryIntentScopedEditorHeading else R.id.groceryIntentScopedTitle)?.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
        return content
    }

    /** Quick quantity repair shares retryable saves without consuming the screen on failure. */
    fun showInputRepair(screen: SwiggyStepScreen, onLeaveWithoutSaving: () -> Unit): (String?, Boolean) -> Unit {
        show(screen, retryableActions = true)
        val epoch = activeEpoch
        val warning = TextView(activity).apply {
            id = R.id.groceryIntentRecoveryWarning
            textSize = 17f
            setTextColor(ContextCompat.getColor(activity, R.color.beta_amber))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.beta_card_amber)
            visibility = View.GONE
        }
        val leave = Button(activity).apply {
            id = R.id.groceryIntentRecoveryLeave
            text = "Leave without saving"
            isAllCaps = false
            textSize = 17f
            minHeight = dp(56)
            setBackgroundResource(R.drawable.beta_btn_secondary)
            visibility = View.GONE
            setOnClickListener { if (activeEpoch == epoch && retryableEpoch == epoch) onLeaveWithoutSaving() }
        }
        items.addView(warning)
        items.addView(leave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var lastMessage: String? = null
        return update@ { text, canLeave ->
            if (activeEpoch != epoch || retryableEpoch != epoch) return@update
            warning.text = text.orEmpty()
            warning.visibility = if (text == null) View.GONE else View.VISIBLE
            leave.visibility = if (canLeave) View.VISIBLE else View.GONE
            if (text != null && text != lastMessage) warning.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            lastMessage = text
        }
    }

    private fun bindAction(button: Button, action: SwiggyStepAction?, backgroundRes: Int, epoch: Long) {
        button.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) {
            button.setOnClickListener(null)
            return
        }
        button.text = action.label
        button.setBackgroundResource(backgroundRes)
        if (button === primary) updatePrimaryEnabled(epoch) else button.isEnabled = true
        button.setOnClickListener {
            if (button === primary && requiresQuantityAcknowledgement(epoch) && !quantityAcknowledgementChecked) {
                revealQuantityAcknowledgementError(epoch)
                return@setOnClickListener
            }
            if (button === primary && requiresAcknowledgement(epoch) && !isAcknowledged(epoch)) return@setOnClickListener
            runStepAction(epoch, action.onClick)
        }
    }

    private fun renderContent(
        rows: List<SwiggyStepRow>,
        choices: List<SwiggyStepChoice>,
        acknowledgement: SwiggyStepAcknowledgement?,
        quantityAcknowledgement: SwiggyStepAcknowledgement?,
        jumpToQuantityAcknowledgement: Boolean,
        epoch: Long,
    ) {
        items.removeAllViews()
        if (quantityAcknowledgement != null && jumpToQuantityAcknowledgement && rows.size > 5) {
            items.addView(createQuantityAcknowledgementJump(epoch))
        }
        rows.forEach { items.addView(createRow(it, epoch)) }
        choices.forEach { items.addView(createChoice(it, epoch)) }
        acknowledgement?.let {
            items.addView(createAcknowledgement(it, epoch))
        }
        items.visibility = if (rows.isEmpty() && choices.isEmpty() && acknowledgement == null &&
            !(quantityAcknowledgement != null && jumpToQuantityAcknowledgement && rows.size > 5)) View.GONE else View.VISIBLE
    }

    private fun createAcknowledgement(acknowledgement: SwiggyStepAcknowledgement, epoch: Long): CheckBox =
        CheckBox(activity).apply {
            id = R.id.swiggyStepAcknowledgement
            acknowledgementCheckBox = this
            text = acknowledgement.label
            contentDescription = acknowledgement.label
            minHeight = dp(56)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            textSize = 16f
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (epoch != activeEpoch || activeAcknowledgementEpoch != epoch ||
                    consumedAcknowledgementEpoch == epoch
                ) return@setOnCheckedChangeListener
                acknowledgementChecked = checked
                updatePrimaryEnabled(epoch)
                acknowledgement.onCheckedChange(checked)
            }
        }

    private fun renderQuantityAcknowledgement(acknowledgement: SwiggyStepAcknowledgement?, epoch: Long) {
        if (acknowledgement == null) return
        val card = LinearLayout(activity).apply {
            id = R.id.swiggyStepQuantityAcknowledgementCard
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundResource(R.drawable.beta_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val checkBox = CheckBox(activity).apply {
            id = R.id.swiggyStepQuantityAcknowledgement
            text = acknowledgement.label
            contentDescription = acknowledgement.label
            minHeight = dp(64)
            minimumHeight = dp(64)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            textSize = 17f
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
            isChecked = false
            setOnCheckedChangeListener { buttonView, checked ->
                if (epoch != activeEpoch || activeQuantityAcknowledgementEpoch != epoch ||
                    consumedQuantityAcknowledgementEpoch == epoch
                ) {
                    if (consumedQuantityAcknowledgementEpoch == epoch) buttonView.isChecked = false
                    return@setOnCheckedChangeListener
                }
                quantityAcknowledgementChecked = checked
                if (checked) quantityAcknowledgementError?.visibility = View.GONE
                updatePrimaryEnabled(epoch)
                acknowledgement.onCheckedChange(checked)
            }
        }
        val helper = TextView(activity).apply {
            id = R.id.swiggyStepQuantityAcknowledgementHelper
            text = QUANTITY_ACK_HELPER
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_secondary))
            textSize = 17f
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            setPadding(dp(12), dp(2), dp(12), dp(4))
            setOnClickListener { toggleQuantityAcknowledgement(epoch) }
        }
        val error = TextView(activity).apply {
            id = R.id.swiggyStepQuantityAcknowledgementError
            text = QUANTITY_ACK_ERROR
            setTextColor(ContextCompat.getColor(activity, R.color.beta_amber))
            textSize = 17f
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(12), dp(6), dp(12), 0)
            visibility = View.GONE
            setOnClickListener { toggleQuantityAcknowledgement(epoch) }
        }
        card.setOnClickListener { toggleQuantityAcknowledgement(epoch) }
        card.addView(checkBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.addView(helper, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.addView(error, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val addIndex = scrollContent.indexOfChild(primary).takeIf { it >= 0 } ?: scrollContent.childCount
        scrollContent.addView(card, addIndex)
        quantityAcknowledgementCheckBox = checkBox
        quantityAcknowledgementError = error
        quantityAcknowledgementCard = card
    }

    private fun createQuantityAcknowledgementJump(epoch: Long): Button = Button(activity).apply {
        id = R.id.swiggyStepQuantityAcknowledgementJump
        text = QUANTITY_ACK_JUMP
        contentDescription = QUANTITY_ACK_JUMP
        minHeight = dp(64)
        isAllCaps = false
        isSingleLine = false
        maxLines = Int.MAX_VALUE
        ellipsize = null
        textSize = 17f
        setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
        setBackgroundResource(R.drawable.beta_btn_secondary)
        setOnClickListener { focusQuantityAcknowledgement(epoch) }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun toggleQuantityAcknowledgement(epoch: Long) {
        if (epoch != activeEpoch || activeQuantityAcknowledgementEpoch != epoch ||
            consumedQuantityAcknowledgementEpoch == epoch
        ) return
        quantityAcknowledgementCheckBox?.let { it.isChecked = !it.isChecked }
    }

    private fun requiresQuantityAcknowledgement(epoch: Long): Boolean =
        activeQuantityAcknowledgementEpoch == epoch && epoch != 0L

    private fun updatePrimaryEnabled(epoch: Long) {
        if (epoch != activeEpoch || primary.visibility != View.VISIBLE) return
        if (consumedQuantityAcknowledgementEpoch == epoch) {
            primary.isEnabled = false
            return
        }
        val quantityMissing = requiresQuantityAcknowledgement(epoch) && !quantityAcknowledgementChecked
        primary.isEnabled = quantityMissing || !requiresAcknowledgement(epoch) || isAcknowledged(epoch)
    }

    private fun revealQuantityAcknowledgementError(epoch: Long) {
        if (!requiresQuantityAcknowledgement(epoch)) return
        quantityAcknowledgementError?.visibility = View.VISIBLE
        quantityAcknowledgementError?.announceForAccessibility(QUANTITY_ACK_ERROR)
        focusQuantityAcknowledgement(epoch)
    }

    private fun focusQuantityAcknowledgement(epoch: Long) {
        if (epoch != activeEpoch || !requiresQuantityAcknowledgement(epoch)) return
        val checkBox = quantityAcknowledgementCheckBox ?: return
        val card = quantityAcknowledgementCard ?: return
        scroll.post {
            if (epoch != activeEpoch || activeQuantityAcknowledgementEpoch != epoch) return@post
            // Enable touch-mode focus only for this explicit focus request.
            // Enabling it at construction swallows an initial ordinary tap.
            checkBox.isFocusableInTouchMode = true
            checkBox.requestFocus()
            scroll.smoothScrollTo(0, max(0, card.top - dp(16)))
            checkBox.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null,
            )
        }
    }

    private fun resetAcknowledgements(epoch: Long) {
        if (epoch == 0L) return
        if (activeAcknowledgementEpoch == epoch) {
            acknowledgementCheckBox?.setOnCheckedChangeListener(null)
            acknowledgementCheckBox?.isChecked = false
            activeAcknowledgement?.onCheckedChange?.invoke(false)
        }
        if (activeQuantityAcknowledgementEpoch == epoch) {
            quantityAcknowledgementCheckBox?.setOnCheckedChangeListener(null)
            quantityAcknowledgementCheckBox?.isChecked = false
            activeQuantityAcknowledgement?.onCheckedChange?.invoke(false)
        }
        activeAcknowledgementEpoch = 0L
        acknowledgementChecked = false
        consumedAcknowledgementEpoch = 0L
        activeAcknowledgement = null
        acknowledgementCheckBox = null
        activeQuantityAcknowledgementEpoch = 0L
        quantityAcknowledgementChecked = false
        consumedQuantityAcknowledgementEpoch = 0L
        activeQuantityAcknowledgement = null
        quantityAcknowledgementCheckBox = null
        quantityAcknowledgementError = null
    }

    private fun removeQuantityAcknowledgementCard() {
        quantityAcknowledgementCard?.let { card ->
            (card.parent as? ViewGroup)?.removeView(card)
        }
        quantityAcknowledgementCard = null
        quantityAcknowledgementCheckBox = null
        quantityAcknowledgementError = null
    }

    private fun requiresAcknowledgement(epoch: Long): Boolean = activeAcknowledgementEpoch == epoch && epoch != 0L

    private fun isAcknowledged(epoch: Long): Boolean =
        requiresAcknowledgement(epoch) && acknowledgementChecked && consumedAcknowledgementEpoch != epoch

    private fun consumeAcknowledgement(epoch: Long) {
        if (epoch == activeEpoch && activeQuantityAcknowledgementEpoch == epoch) {
            consumedQuantityAcknowledgementEpoch = epoch
            quantityAcknowledgementChecked = false
            quantityAcknowledgementCheckBox?.isEnabled = false
            quantityAcknowledgementCheckBox?.isChecked = false
            updatePrimaryEnabled(epoch)
        }
        if (epoch == activeEpoch && activeAcknowledgementEpoch == epoch) {
            consumedAcknowledgementEpoch = epoch
            acknowledgementChecked = false
            updatePrimaryEnabled(epoch)
        }
    }

    private fun runStepAction(epoch: Long, callback: () -> Unit) {
        if (epoch == activeEpoch && retryableEpoch == epoch) {
            callback()
            return
        }
        interactionGate.wrap(epoch) {
            consumeAcknowledgement(epoch)
            callback()
        }.invoke()
    }

    private fun createRow(row: SwiggyStepRow, epoch: Long): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(
                when (row.tone) {
                    SwiggyStepTone.AMBER -> R.drawable.beta_card_amber
                    SwiggyStepTone.SUCCESS -> R.drawable.beta_card_soft
                    SwiggyStepTone.NEUTRAL -> R.drawable.beta_card
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.START
        }
        val titleView = TextView(activity).apply {
            text = row.title
            setTextAppearance(R.style.Beta_Body)
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        ViewCompat.setAccessibilityHeading(titleView, row.isHeading)
        heading.addView(titleView)
        row.badge?.takeIf { it.isNotBlank() }?.let { badge ->
            heading.addView(TextView(activity).apply {
                text = badge
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(
                    if (row.tone == SwiggyStepTone.AMBER) R.drawable.beta_pill_amber
                    else R.drawable.beta_pill_sage
                )
                setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (row.tone == SwiggyStepTone.AMBER) R.color.beta_amber else R.color.beta_success,
                    )
                )
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            })
        }
        container.addView(heading)

        row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            container.addView(TextView(activity).apply {
                text = detail
                setTextAppearance(R.style.Beta_BodySoft)
                textSize = 15f
                setPadding(0, dp(5), 0, 0)
            })
        }
        row.action?.let { action ->
            container.addView(Button(activity).apply {
                id = R.id.swiggyStepRowAction
                text = action.label
                contentDescription = action.label
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                minHeight = dp(48)
                isAllCaps = false
                stateListAnimator = null
                setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setBackgroundResource(R.drawable.beta_btn_secondary)
                setOnClickListener {
                    runStepAction(epoch, action.onClick)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            })
        }
        return container
    }

    private fun createChoice(choice: SwiggyStepChoice, epoch: Long): View {
        return Button(activity).apply {
            text = buildString {
                append(choice.title)
                choice.detail?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                choice.badge?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
            }
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minHeight = dp(64)
            isAllCaps = false
            stateListAnimator = null
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setBackgroundResource(R.drawable.beta_btn_secondary)
            setOnClickListener {
                runStepAction(epoch, choice.onClick)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val QUANTITY_ACK_HELPER = "Beta only adds the suggested amounts if you tick this box."
        const val QUANTITY_ACK_ERROR = "Please check the suggested pack amounts, then tick the box above. Or tap Change amounts to edit your list."
        const val QUANTITY_ACK_JUMP = "Go to the tick box"
    }
}
