package com.locusable.ginkgo.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

internal const val TILE_ROW_GAP = 12

internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

internal fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setBaselineAligned(false)
}

internal fun Context.label(value: String, size: Float, face: Typeface = Typeface.DEFAULT, tone: Int = INK) = TextView(this).apply {
    text = value
    textSize = size
    typeface = face
    setTextColor(tone)
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
}

private fun Context.caption(value: String) = label(value, CAPTION_TEXT_SP, face = Typeface.MONOSPACE, tone = SOFT_INK).apply {
    letterSpacing = 0.045f
    maxLines = 1
}

internal fun LinearLayout.rule(color: Int = LINE) = addView(context.paperRule(color), LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)))

internal fun LinearLayout.gap(height: Int) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))

internal fun View.action(stroke: Int = Color.TRANSPARENT, click: () -> Unit) {
    isFocusable = true
    background = context.pressBackground(stroke)
    setOnClickListener { click() }
}

internal fun LinearLayout.title(text: String, accessory: View? = null, leading: View? = null) {
    val heading = context.label(text, PAGE_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply {
        minimumHeight = context.dp(ACTION_HEIGHT_DP)
        if (leading == null) maxLines = 2 else setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    if (accessory == null && leading == null) {
        addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    } else {
        addView(context.horizontal().apply {
            leading?.let { addView(it) }
            addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            accessory?.let { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(8) }) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    gap(HEADING_GAP_DP)
}

internal fun LinearLayout.collectionHeader(name: String, accessory: View? = null, leading: View? = null) {
    title(name, accessory, leading)
    rule()
}

internal fun LinearLayout.section(left: String, right: String) {
    addView(context.horizontal().apply {
        addView(context.label(left, LABEL_TEXT_SP, face = Typeface.DEFAULT_BOLD), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        if (right.isNotEmpty()) addView(context.caption(right), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(33)))
    rule()
}

internal fun <T : Any> LinearLayout.tileRows(
    items: List<T>, columns: Int, rowHeight: Int? = null, rowGap: Int = TILE_ROW_GAP, tile: (T) -> View,
) {
    val rowSize = rowHeight?.let(context::dp) ?: WRAP_CONTENT
    val tileHeight = if (rowHeight == null) WRAP_CONTENT else MATCH_PARENT
    items.chunked(columns).forEach { group ->
        if (rowGap > 0) gap(rowGap)
        addView(context.horizontal().apply {
            gravity = Gravity.TOP
            repeat(columns) { index ->
                val child = group.getOrNull(index)?.let(tile)
                // Blank cells reserve width without filling the remaining ScrollView height.
                addView(child ?: View(context), LinearLayout.LayoutParams(0, if (child == null) 0 else tileHeight, 1f).apply {
                    if (index > 0) marginStart = context.dp(9)
                })
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, rowSize))
    }
}
