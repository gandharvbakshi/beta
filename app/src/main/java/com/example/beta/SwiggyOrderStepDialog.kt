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

internal data class SwiggyStepRow(
    val title: String,
    val detail: String? = null,
    val badge: String? = null,
    val tone: SwiggyStepTone = SwiggyStepTone.NEUTRAL,
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
)

/** Full-screen, single-surface UI for the direct Swiggy MCP cart journey. */
internal class SwiggyOrderStepDialog(private val activity: Activity) {
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
        eyebrow.text = screen.eyebrow
        title.text = screen.title
        message.text = screen.message
        caption.text = screen.caption
        caption.contentDescription = screen.caption
        renderContent(screen.rows, screen.choices)

        safetyNote.text = screen.safetyNote.orEmpty()
        safetyNote.visibility = if (screen.safetyNote.isNullOrBlank()) View.GONE else View.VISIBLE

        bindAction(primary, screen.primary, R.drawable.beta_btn_primary)
        bindAction(secondary, screen.secondary, R.drawable.beta_btn_secondary)
        bindAction(tertiary, screen.tertiary, android.R.color.transparent)

        close.visibility = if (screen.cancel == null) View.GONE else View.VISIBLE
        close.setOnClickListener { screen.cancel?.invoke() }
        dialog.setCancelable(screen.cancel != null)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { screen.cancel?.invoke() }

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
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun bindAction(button: Button, action: SwiggyStepAction?, backgroundRes: Int) {
        button.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) {
            button.setOnClickListener(null)
            return
        }
        button.text = action.label
        button.setBackgroundResource(backgroundRes)
        button.setOnClickListener { action.onClick() }
    }

    private fun renderContent(rows: List<SwiggyStepRow>, choices: List<SwiggyStepChoice>) {
        items.removeAllViews()
        rows.forEach { items.addView(createRow(it)) }
        choices.forEach { items.addView(createChoice(it)) }
        items.visibility = if (rows.isEmpty() && choices.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun createRow(row: SwiggyStepRow): View {
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
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(activity).apply {
            text = row.title
            setTextAppearance(R.style.Beta_Body)
            setTextColor(ContextCompat.getColor(activity, R.color.beta_text_primary))
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
        return container
    }

    private fun createChoice(choice: SwiggyStepChoice): View {
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
            setOnClickListener { choice.onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
