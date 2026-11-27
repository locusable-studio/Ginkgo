package com.locusable.ginkgo.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

internal class PaperRow(
    context: Context,
    title: String, icon: View, trailing: InkGlyph.Kind?, badge: InkGlyph.Kind? = null, singleLine: Boolean = false,
) : LinearLayout(context) {
    val titleView = context.label(title, ROW_TEXT_SP).apply {
        if (singleLine) setSingleLine(true) else maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBaselineAligned(false)
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        minimumHeight = context.dp(ACTION_HEIGHT_DP)
        setPadding(context.dp(4), context.dp(5), context.dp(2), context.dp(5))
        contentDescription = title
        addView(icon, LayoutParams(context.dp(29), context.dp(30)))
        addView(titleView, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(11) })
        if (badge != null) addView(InkGlyph(context, badge), LayoutParams(context.dp(22), context.dp(24)))
        if (trailing != null) addView(InkGlyph(context, trailing, tone = SOFT_INK), LayoutParams(context.dp(29), context.dp(30)))
    }
}
