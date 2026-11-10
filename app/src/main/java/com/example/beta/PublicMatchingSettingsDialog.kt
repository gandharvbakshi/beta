package com.example.beta

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatRadioButton
import kotlin.math.roundToInt

/** Local settings only; showing or cancelling the dialog cannot start a matching request. */
internal class PublicMatchingSettingsDialog(
    private val activity: Activity,
    private val read: () -> GroceryIntentSelection,
    private val allow: () -> Boolean,
    private val useStandard: () -> Boolean,
    private val ui: Context = activity,
    private val internalAllow: ((GroceryIntentPreferences.Mode) -> Boolean)? = null,
) {
    private val body = LinearLayout(ui).apply {
        id = android.R.id.list
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(20))
        setBackgroundColor(activity.getColor(R.color.beta_background))
    }
    private val scroll = ScrollView(ui).apply { addView(body) }
    private val footer = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(12))
    }
    private var expandedConsent = false
    private var normalWidth = WRAP_CONTENT
    private var normalHeight = WRAP_CONTENT
    private var normalBackground: Drawable? = null
    private val panel = object : LinearLayout(ui) {
        init {
            orientation = VERTICAL
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!expandedConsent) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
            footer.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            // Keep one scroller. If the actions leave too little readable space, scroll the whole page.
            val pin = bounded && height >= footer.measuredHeight + dp(112)
            val parent = if (pin) this else body
            if (footer.parent !== parent) {
                (footer.parent as? ViewGroup)?.removeView(footer)
                parent.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                scroll.layoutParams = if (pin) LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                    else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            super.onMeasure(widthMeasureSpec, if (bounded) MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                else heightMeasureSpec)
        }
    }
    private val dialog = AlertDialog.Builder(activity).setView(panel).create()

    fun show(): AlertDialog {
        dialog.show()
        choices(read())
        return dialog
    }

    private fun choices(choice: GroceryIntentSelection, choosingAgain: Boolean = false, notice: Int? = null,
                        noticeText: CharSequence? = null) {
        page(R.string.matching_settings_title)
        if (internalAllow != null) {
            internalChoices(choice, choosingAgain, notice, noticeText)
            return
        }
        val openAi = isOpenAi(choice)
        val standard = choice == GroceryIntentSelection.Disabled
        val gemini = choice is GroceryIntentSelection.Enabled && choice.mode == GroceryIntentPreferences.Mode.GEMINI &&
            choice.contract == GroceryIntentContract.V1
        if (!openAi && !standard && !gemini && !choosingAgain) {
            message(R.string.matching_earlier_unavailable)
            action(R.string.matching_choose_again, true) { choices(read(), choosingAgain = true) }
            action(R.string.matching_keep_standard, false) { save(openAi = false) }
            return
        }
        notice?.let(::message)
        noticeText?.let(::messageText)
        if (openAi) message(R.string.matching_openai_on)
        if (gemini) message(R.string.matching_legacy_gemini)
        radio(android.R.id.text1, R.string.matching_standard, R.string.matching_standard_detail, standard) {
            if (!standard) confirmStandard(openAi)
        }
        radio(android.R.id.text2, R.string.matching_openai, R.string.matching_openai_detail, openAi) {
            if (!openAi) confirmOpenAi()
        }
        if (openAi) action(R.string.matching_openai_turn_off, true) { confirmStandard(true) }
        action(android.R.string.cancel, false) { dialog.dismiss() }
    }

    private fun confirmOpenAi() {
        confirmProviderConsent(GroceryIntentPreferences.Mode.OPENAI, publicOpenAi = true)
    }

    private fun internalChoices(choice: GroceryIntentSelection, choosingAgain: Boolean, notice: Int?,
                                noticeText: CharSequence?) {
        val selection = choice as? GroceryIntentSelection.Enabled
        val currentMode = selection?.mode?.takeIf { mode ->
            mode in setOf(GroceryIntentPreferences.Mode.GEMINI, GroceryIntentPreferences.Mode.DEEPSEEK,
                GroceryIntentPreferences.Mode.OPENAI) && selection.contract == GroceryIntentContract.INTERPRETED
        }
        val standard = choice == GroceryIntentSelection.Disabled
        if (currentMode == null && choice != GroceryIntentSelection.Disabled) {
            message(R.string.matching_earlier_unavailable)
        }
        notice?.let(::message)
        noticeText?.let(::messageText)
        radio(android.R.id.text1, R.string.matching_standard, R.string.matching_standard_detail, standard) {
            if (!standard) confirmStandard(currentMode != null, currentMode)
        }
        radio(R.id.internalMatchingGemini, R.string.matching_provider_gemini, R.string.matching_provider_detail,
            currentMode == GroceryIntentPreferences.Mode.GEMINI) {
            if (!isInternalSelection(choice, GroceryIntentPreferences.Mode.GEMINI))
                chooseInternalMode(GroceryIntentPreferences.Mode.GEMINI, currentMode)
        }
        radio(R.id.internalMatchingDeepSeek, R.string.matching_provider_deepseek, R.string.matching_provider_detail,
            currentMode == GroceryIntentPreferences.Mode.DEEPSEEK) {
            if (!isInternalSelection(choice, GroceryIntentPreferences.Mode.DEEPSEEK))
                chooseInternalMode(GroceryIntentPreferences.Mode.DEEPSEEK, currentMode)
        }
        radio(android.R.id.text2, R.string.matching_provider_openai, R.string.matching_provider_detail,
            currentMode == GroceryIntentPreferences.Mode.OPENAI) {
            if (!isInternalSelection(choice, GroceryIntentPreferences.Mode.OPENAI))
                chooseInternalMode(GroceryIntentPreferences.Mode.OPENAI, currentMode)
        }
        if (currentMode != null) actionText(activity.getString(R.string.matching_provider_turn_off, providerName(currentMode)), true) {
            confirmStandard(true, currentMode)
        }
        action(android.R.string.cancel, false) { dialog.dismiss() }
    }

    private fun chooseInternalMode(mode: GroceryIntentPreferences.Mode, oldMode: GroceryIntentPreferences.Mode?) {
        if (oldMode != null && oldMode != mode) {
            page(R.string.matching_provider_switch_title)
            messageText(activity.getString(R.string.matching_provider_switch,
                providerName(mode), providerName(oldMode)))
            action(R.string.matching_provider_switch_continue, true) { confirmInternalMode(mode) }
            action(R.string.common_not_now, false) { dialog.dismiss() }
        } else {
            confirmInternalMode(mode)
        }
    }

    private fun confirmInternalMode(mode: GroceryIntentPreferences.Mode) {
        confirmProviderConsent(mode, publicOpenAi = false)
    }

    private fun confirmProviderConsent(mode: GroceryIntentPreferences.Mode, publicOpenAi: Boolean) {
        val name = providerName(mode)
        val title = if (publicOpenAi) R.string.matching_openai_consent else R.string.matching_provider_consent
        page(title, if (publicOpenAi) activity.getString(title) else activity.getString(title, name))
        messageText(activity.getString(R.string.matching_openai_disclosure, name))
        if (publicOpenAi) action(R.string.matching_allow_openai, true) { saveProvider(mode, publicOpenAi = true) }
        else actionText(activity.getString(R.string.matching_provider_allow, name), true) {
            saveProvider(mode, publicOpenAi = false)
        }
        action(R.string.common_not_now, false) { dialog.dismiss() }
        if (expandedConsent) body.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun saveProvider(mode: GroceryIntentPreferences.Mode, publicOpenAi: Boolean) {
        val written = if (publicOpenAi) allow() else internalAllow?.invoke(mode) == true
        val current = if (written) read() else null
        if (isExactProviderSelection(current, mode)) {
            choices(requireNotNull(current))
            return
        }
        page(R.string.matching_could_not_save)
        action(R.string.matching_retry, true) { saveProvider(mode, publicOpenAi) }
        action(android.R.string.cancel, false) { dialog.dismiss() }
    }

    private fun isExactProviderSelection(choice: GroceryIntentSelection?, mode: GroceryIntentPreferences.Mode) =
        choice is GroceryIntentSelection.Enabled && choice.mode == mode &&
            choice.contract == GroceryIntentContract.INTERPRETED

    private fun providerName(mode: GroceryIntentPreferences.Mode): String = activity.getString(when (mode) {
        GroceryIntentPreferences.Mode.GEMINI -> R.string.matching_provider_gemini
        GroceryIntentPreferences.Mode.DEEPSEEK -> R.string.matching_provider_deepseek
        GroceryIntentPreferences.Mode.OPENAI -> R.string.matching_provider_openai
        else -> error("Automatic matching is not an internal provider choice")
    })

    private fun isInternalSelection(choice: GroceryIntentSelection, mode: GroceryIntentPreferences.Mode) =
        isExactProviderSelection(choice, mode)

    private fun confirmStandard(wasOn: Boolean, previousMode: GroceryIntentPreferences.Mode? = null) {
        page(R.string.matching_confirm_standard)
        message(R.string.matching_standard_detail)
        if (internalAllow != null && wasOn && previousMode != null) {
            actionText(activity.getString(R.string.matching_provider_turn_off, providerName(previousMode)), true) {
                save(openAi = false, wasOn = true, previousMode = previousMode)
            }
        } else action(if (wasOn) R.string.matching_openai_turn_off else R.string.matching_use_standard, true) {
            save(openAi = false, wasOn = wasOn)
        }
        action(R.string.common_not_now, false) { dialog.dismiss() }
    }

    private fun save(openAi: Boolean, wasOn: Boolean = false,
                     previousMode: GroceryIntentPreferences.Mode? = null) {
        val written = if (openAi) allow() else useStandard()
        val current = if (written) read() else null
        if (current != null && (if (openAi) isExactProviderSelection(current, GroceryIntentPreferences.Mode.OPENAI)
            else current == GroceryIntentSelection.Disabled)) {
            val internalOffNotice = if (internalAllow != null && wasOn && !openAi && previousMode != null)
                activity.getString(R.string.matching_provider_off, providerName(previousMode)) else null
            choices(current,
                notice = R.string.matching_openai_off.takeIf { wasOn && !openAi && internalAllow == null },
                noticeText = internalOffNotice)
            return
        }
        page(R.string.matching_could_not_save)
        action(R.string.matching_retry, true) { save(openAi, wasOn, previousMode) }
        action(android.R.string.cancel, false) { dialog.dismiss() }
    }

    private fun isOpenAi(selection: GroceryIntentSelection) = selection is GroceryIntentSelection.Enabled &&
        selection.mode == GroceryIntentPreferences.Mode.OPENAI && selection.contract == GroceryIntentContract.INTERPRETED

    private fun page(title: Int, headingText: CharSequence = activity.getString(title)) {
        val expand = title in setOf(R.string.matching_openai_consent, R.string.matching_provider_consent) &&
            ui.resources.configuration.fontScale >= 1.5f
        if (expand != expandedConsent) {
            expandedConsent = expand
            val window = requireNotNull(dialog.window)
            if (expand) {
                normalWidth = window.attributes.width
                normalHeight = window.attributes.height
                normalBackground = window.decorView.background
                window.setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.beta_background)))
                window.setLayout(MATCH_PARENT, MATCH_PARENT)
            } else {
                footer.removeAllViews()
                (footer.parent as? ViewGroup)?.removeView(footer)
                scroll.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                window.setBackgroundDrawable(normalBackground)
                window.setLayout(normalWidth, normalHeight)
            }
        }
        body.removeAllViews()
        val heading = TextView(ui).apply {
            id = android.R.id.title
            text = headingText
            setTextAppearance(R.style.Beta_Title)
            isAccessibilityHeading = true
            isFocusableInTouchMode = true
        }
        body.addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (internalAllow != null) {
            body.addView(TextView(ui).apply {
                setTextAppearance(R.style.Beta_Body)
                setText(R.string.matching_internal_test_banner)
                isAccessibilityHeading = false
            }, rowParams())
        }
        heading.post {
            if (heading.isAttachedToWindow) {
                scroll.scrollTo(0, 0)
                heading.requestFocus()
                heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
    }

    private fun message(label: Int) = body.addView(TextView(ui).apply {
        setTextAppearance(R.style.Beta_Body)
        setText(label)
    }, rowParams())

    private fun messageText(text: CharSequence) = body.addView(TextView(ui).apply {
        setTextAppearance(R.style.Beta_Body)
        this.text = text
    }, rowParams())

    private fun radio(viewId: Int, label: Int, detail: Int, checked: Boolean, onClick: () -> Unit) {
        // RadioButton ordinarily toggles before the click listener. Only saved state can check this row.
        body.addView(object : AppCompatRadioButton(ui) { override fun toggle() = Unit }.apply {
            id = viewId
            text = activity.getString(label) + "\n" + activity.getString(detail)
            setTextAppearance(R.style.Beta_Body)
            setSingleLine(false)
            buttonTintList = ColorStateList.valueOf(activity.getColor(R.color.beta_text_primary))
            minHeight = dp(56)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isChecked = checked
            setOnClickListener { if (dialog.isShowing && parent === body) onClick() }
        }, rowParams())
    }

    private fun action(label: Int, primary: Boolean, onClick: () -> Unit) {
        actionText(activity.getString(label), primary, onClick)
    }

    private fun actionText(label: CharSequence, primary: Boolean, onClick: () -> Unit) {
        val container = if (expandedConsent) footer else body
        container.addView(Button(ui).apply {
            id = if (primary) android.R.id.button1 else android.R.id.button2
            text = label
            setTextAppearance(R.style.Beta_Body)
            isAllCaps = false
            setSingleLine(false)
            ellipsize = null
            minHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            backgroundTintList = null
            setBackgroundResource(if (primary) R.drawable.beta_btn_primary else R.drawable.beta_btn_secondary)
            setTextColor(activity.getColor(if (primary) R.color.beta_on_primary else R.color.beta_text_primary))
            setOnClickListener { if (dialog.isShowing && parent === container) onClick() }
        }, rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    private fun dp(value: Int) = (value * ui.resources.displayMetrics.density).roundToInt()
}
