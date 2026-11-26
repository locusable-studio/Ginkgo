package com.locusable.ginkgo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button

internal const val INK = 0xFF171717.toInt()
internal const val SOFT_INK = 0xFF555555.toInt()
internal const val DISABLED_INK = 0xFF888888.toInt()
internal const val LINE = 0xFFB0B0B0.toInt()
internal const val PRESS_FILL = 0xFFF2F2F2.toInt()
internal const val PAGE_INSET_DP = 24
internal const val PAGE_TOP_DP = 18
internal const val PAGE_BOTTOM_DP = 12
internal const val HEADING_GAP_DP = 12
internal const val SECTION_GAP_DP = 20
internal const val ACTION_HEIGHT_DP = 48
internal const val PAGE_TITLE_SP = 30f
internal const val DIALOG_TITLE_SP = 21f
internal const val ROW_TEXT_SP = 17f
internal const val ACTION_TEXT_SP = 15f
internal const val LABEL_TEXT_SP = 14f
internal const val CAPTION_TEXT_SP = 12f

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.paperShape(stroke: Int, fill: Int = Color.WHITE) = GradientDrawable().apply {
    setColor(fill)
    if (stroke != Color.TRANSPARENT) setStroke(dp(1).coerceAtLeast(1), stroke)
}

internal fun Context.pressBackground(stroke: Int = Color.TRANSPARENT) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), paperShape(stroke, PRESS_FILL))
    addState(intArrayOf(android.R.attr.state_focused), paperShape(INK))
    addState(intArrayOf(), paperShape(stroke))
}

internal fun Context.paperRule(color: Int = LINE) = View(this).apply {
    setBackgroundColor(color)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** The darker outline and weight give confirmation priority without a solid ink fill. */
internal fun Context.paperButton(text: String, primary: Boolean, enabled: Boolean = true, click: () -> Unit) = Button(this).apply {
    this.text = text
    setAllCaps(false)
    letterSpacing = 0f
    gravity = Gravity.CENTER
    textSize = ACTION_TEXT_SP
    typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    setTextColor(if (enabled) INK else DISABLED_INK)
    minWidth = dp(80)
    minHeight = dp(ACTION_HEIGHT_DP)
    setPadding(dp(10), dp(7), dp(10), dp(7))
    stateListAnimator = null
    backgroundTintList = null
    foreground = null
    background = if (enabled) pressBackground(if (primary) INK else LINE) else paperShape(LINE, PRESS_FILL)
    isEnabled = enabled
    isFocusable = enabled
    if (enabled) setOnClickListener { click() }
}
