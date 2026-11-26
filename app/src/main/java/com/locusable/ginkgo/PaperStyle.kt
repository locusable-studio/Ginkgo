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
internal const val ACTION_HEIGHT_DP = 48
internal const val PAGE_TITLE_SP = 32f

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

/** Filled for confirmation, outlined for cancellation, gray when unavailable. */
internal fun Context.paperButton(text: String, primary: Boolean, enabled: Boolean = true, click: () -> Unit) = Button(this).apply {
    this.text = text
    setAllCaps(false)
    letterSpacing = 0f
    gravity = Gravity.CENTER
    textSize = 15f
    typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    setTextColor(when {
        !enabled -> DISABLED_INK
        primary -> Color.WHITE
        else -> INK
    })
    minWidth = dp(80)
    minHeight = dp(ACTION_HEIGHT_DP)
    setPadding(dp(10), dp(7), dp(10), dp(7))
    stateListAnimator = null
    backgroundTintList = null
    foreground = null
    background = when {
        !enabled -> paperShape(LINE, PRESS_FILL)
        primary -> StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), paperShape(INK, SOFT_INK))
            addState(intArrayOf(android.R.attr.state_focused), paperShape(INK, SOFT_INK))
            addState(intArrayOf(), paperShape(INK, INK))
        }
        else -> pressBackground(LINE)
    }
    isEnabled = enabled
    isFocusable = enabled
    if (enabled) setOnClickListener { click() }
}
