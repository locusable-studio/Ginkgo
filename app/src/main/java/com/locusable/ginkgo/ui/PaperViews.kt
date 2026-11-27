package com.locusable.ginkgo.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

internal const val TILE_ROW_GAP = 12
internal const val TILE_COLUMN_GAP_DP = 9

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

internal fun Context.bookAuthorLabel() = label("未知", CAPTION_TEXT_SP, tone = SOFT_INK).apply {
    setSingleLine(true)
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

private fun Context.caption(value: String) = label(value, CAPTION_TEXT_SP, face = Typeface.MONOSPACE, tone = SOFT_INK).apply {
    letterSpacing = 0.045f
    maxLines = 1
}

internal fun LinearLayout.rule(color: Int = LINE) = addView(context.paperRule(color), LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)))

internal fun LinearLayout.gap(height: Int) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))

internal fun View.action(stroke: Int = Color.TRANSPARENT, click: () -> Unit) {
    isFocusable = true
    defaultFocusHighlightEnabled = false
    background = context.pressBackground(stroke)
    setOnClickListener { click() }
}

internal fun LinearLayout.title(text: String, accessory: View? = null, leading: View? = null, singleLine: Boolean = false) {
    val heading = context.label(text, PAGE_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply {
        minimumHeight = context.dp(ACTION_HEIGHT_DP)
        if (singleLine) setSingleLine(true) else maxLines = 2
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

internal fun LinearLayout.section(left: String, right: String) {
    addView(context.horizontal().apply {
        addView(context.label(left, LABEL_TEXT_SP, face = Typeface.DEFAULT_BOLD), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        if (right.isNotEmpty()) addView(context.caption(right), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(33)))
}

internal fun <T : Any> LinearLayout.tileRows(
    items: List<T>, columns: Int, rowHeight: Int? = null, rowGap: Int = TILE_ROW_GAP, tile: (T) -> View,
) {
    val rowSize = rowHeight?.let(context::dp) ?: WRAP_CONTENT
    items.chunked(columns).forEach { group ->
        if (rowGap > 0) gap(rowGap)
        addView(context.horizontal().apply { addTileCells(group, columns, rowHeight != null, tile) },
            LinearLayout.LayoutParams(MATCH_PARENT, rowSize))
    }
}

internal fun <T : Any> LinearLayout.addTileCells(items: List<T>, columns: Int, fixedHeight: Boolean, tile: (T) -> View) {
    gravity = Gravity.TOP
    repeat(columns) { index ->
        val child = items.getOrNull(index)?.let(tile)
        val height = if (child == null) 0 else if (fixedHeight) MATCH_PARENT else WRAP_CONTENT
        addView(child ?: View(context), LinearLayout.LayoutParams(0, height, 1f).apply {
            if (index > 0) marginStart = context.dp(TILE_COLUMN_GAP_DP)
        })
    }
}

internal fun Context.paperTileList() = ListView(this).apply {
    divider = null
    setSelector(ColorDrawable(Color.TRANSPARENT))
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    clipToPadding = false
    setItemsCanFocus(true)
}

internal fun <T : Any> ListView.showTileRows(
    items: List<T>, columns: Int, rowHeight: Int? = null, bind: (LinearLayout, List<T>) -> Unit,
) {
    val rowGap = context.dp(TILE_ROW_GAP)
    val height = rowHeight?.let { context.dp(it) + rowGap } ?: WRAP_CONTENT
    setRecyclerListener { (it as? TileListRow)?.reset() }
    adapter = object : BaseAdapter() {
        override fun getCount() = (items.size + columns - 1) / columns
        override fun getItem(position: Int) = items.subList(position * columns, minOf((position + 1) * columns, items.size))
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? TileListRow ?: TileListRow(context).apply {
                layoutParams = AbsListView.LayoutParams(MATCH_PARENT, height)
                setPadding(0, 0, 0, rowGap)
            }
            if (row.position != position) {
                row.reset()
                bind(row, getItem(position))
                row.position = position
            }
            return row
        }
    }
}

private class TileListRow(context: Context) : LinearLayout(context) {
    var position = -1

    init { setBaselineAligned(false) }

    fun reset() {
        position = -1
        tag = null
        removeAllViews()
    }
}

internal data class TileScroll(val row: Int = 0, val offset: Int = 0)

internal fun ListView.tileScroll(): TileScroll? = getChildAt(0)?.let {
    TileScroll(firstVisiblePosition, it.top - listPaddingTop)
}

internal fun ListView.restoreTileScroll(position: TileScroll) {
    if (count > 0 && (position.row != 0 || position.offset != 0)) {
        setSelectionFromTop(position.row.coerceIn(0, count - 1), position.offset)
    }
}

internal fun LinearLayout.notice(message: String) {
    addView(context.label(message, ACTION_TEXT_SP, tone = SOFT_INK).apply {
        setPadding(0, context.dp(HEADING_GAP_DP), 0, context.dp(HEADING_GAP_DP))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

internal fun View.buttonRole(longAction: String? = null) {
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Button"
            if (longAction != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_LONG_CLICK, longAction,
            ))
        }
    }
}
