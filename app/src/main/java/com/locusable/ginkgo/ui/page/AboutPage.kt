package com.locusable.ginkgo.ui.page

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import com.locusable.ginkgo.R
import com.locusable.ginkgo.ui.ACTION_TEXT_SP
import com.locusable.ginkgo.ui.CAPTION_TEXT_SP
import com.locusable.ginkgo.ui.LINE
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.paperRule

internal fun LinearLayout.showAbout(installedVersion: String) = with(context) {
    this@showAbout.gravity = Gravity.CENTER_HORIZONTAL
    val serif = Typeface.create("serif", Typeface.NORMAL)
    fun whitespace(minimumDp: Int, share: Float) {
        addView(View(context), LinearLayout.LayoutParams(1, dp(minimumDp), share))
    }

    fun imprintLabel(resource: Int) = label(getString(resource), 11f, tone = SOFT_INK).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.18f
    }

    whitespace(54, 1.5f)
    addView(ImageView(context).apply {
        setImageDrawable(applicationInfo.loadIcon(packageManager))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(72), dp(72)))
    gap(8)
    addView(label(getString(R.string.app_name), 68f, face = serif).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    gap(14)
    addView(label(getString(R.string.about_description), ACTION_TEXT_SP, tone = SOFT_INK).apply {
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    whitespace(34, 1.25f)
    addView(paperRule(LINE), LinearLayout.LayoutParams(dp(36), dp(1)))
    gap(14)
    addView(imprintLabel(R.string.about_credit), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    gap(2)
    addView(label(getString(R.string.app_developer), 22f, face = serif).apply {
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    gap(12)
    addView(horizontal().apply {
        setBaselineAligned(true)
        addView(imprintLabel(R.string.about_version))
        addView(label(installedVersion, CAPTION_TEXT_SP, face = Typeface.MONOSPACE, tone = SOFT_INK),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    whitespace(26, 1f)
}
