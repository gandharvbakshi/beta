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
)

/** Full-screen, single-surface UI for the direct Swiggy MCP cart journey. */
internal class SwiggyOrderStepDialog(private val activity: Activity) {
    private val interactionGate = SwiggyStepInteractionGate()
    private var activeEpoch = 0L
    private val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
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
    private var activeAcknowledgementEpoch = 0L
    private var acknowledgementChecked = false
    private var consumedAcknowledgementEpoch = 0L

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

    fun show(screen: SwiggyStepScreen) {
        val epoch = interactionGate.beginPresentation()
        activeEpoch = epoch
        activeAcknowledgementEpoch = if (screen.acknowledgement == null) 0L else epoch
        acknowledgementChecked = false
        consumedAcknowledgementEpoch = 0L
        screen.acknowledgement?.onCheckedChange?.invoke(false)
        val cancel = screen.cancel
        eyebrow.text = screen.eyebrow
        title.text = screen.title
        message.text = screen.message
        caption.text = screen.caption
        caption.contentDescription = screen.caption
        caption.visibility = if (screen.caption.isBlank()) View.GONE else View.VISIBLE
        renderContent(screen.rows, screen.choices, screen.acknowledgement, epoch)

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
                activeAcknowledgementEpoch = 0L
                acknowledgementChecked = false
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
        title.announceForAccessibility("${screen.eyebrow}. ${screen.title}")
    }

    fun dismiss() {
        consumeAcknowledgement(activeEpoch)
        interactionGate.invalidate(activeEpoch)
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun bindAction(button: Button, action: SwiggyStepAction?, backgroundRes: Int, epoch: Long) {
        button.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) {
            button.setOnClickListener(null)
            return
        }
        button.text = action.label
        button.setBackgroundResource(backgroundRes)
        button.isEnabled = action != null && (
            button !== primary || !requiresAcknowledgement(epoch) || isAcknowledged(epoch)
        )
        button.setOnClickListener {
            if (button === primary && requiresAcknowledgement(epoch) && !isAcknowledged(epoch)) return@setOnClickListener
            runStepAction(epoch, action.onClick)
        }
    }

    private fun renderContent(
        rows: List<SwiggyStepRow>,
        choices: List<SwiggyStepChoice>,
        acknowledgement: SwiggyStepAcknowledgement?,
        epoch: Long,
    ) {
        items.removeAllViews()
        rows.forEach { items.addView(createRow(it, epoch)) }
        choices.forEach { items.addView(createChoice(it, epoch)) }
        acknowledgement?.let { items.addView(createAcknowledgement(it, epoch)) }
        items.visibility = if (rows.isEmpty() && choices.isEmpty() && acknowledgement == null) View.GONE else View.VISIBLE
    }

    private fun createAcknowledgement(acknowledgement: SwiggyStepAcknowledgement, epoch: Long): CheckBox =
        CheckBox(activity).apply {
            id = R.id.swiggyStepAcknowledgement
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
                primary.isEnabled = primary.visibility == View.VISIBLE && (!requiresAcknowledgement(epoch) || checked)
                acknowledgement.onCheckedChange(checked)
            }
        }

    private fun requiresAcknowledgement(epoch: Long): Boolean = activeAcknowledgementEpoch == epoch && epoch != 0L

    private fun isAcknowledged(epoch: Long): Boolean =
        requiresAcknowledgement(epoch) && acknowledgementChecked && consumedAcknowledgementEpoch != epoch

    private fun consumeAcknowledgement(epoch: Long) {
        if (epoch == activeEpoch && activeAcknowledgementEpoch == epoch) {
            consumedAcknowledgementEpoch = epoch
            acknowledgementChecked = false
            primary.isEnabled = false
        }
    }

    private fun runStepAction(epoch: Long, callback: () -> Unit) {
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
        heading.addView(TextView(activity).apply {
            text = row.title
            setTextAppearance(R.style.Beta_Body)
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
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
}
