package com.locusable.ginkgo.update

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.locusable.ginkgo.R
import com.locusable.ginkgo.ui.INK
import com.locusable.ginkgo.ui.LABEL_TEXT_SP
import com.locusable.ginkgo.ui.LINE
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label

/** Determinate, non-animated progress for e-ink. */
internal class UpdateDownloadProgress(context: Context) : LinearLayout(context) {
    private val status = context.label("正在下载", LABEL_TEXT_SP, tone = SOFT_INK)
    private val percentLabel = context.label(context.getString(R.string.update_download_percent, 0), LABEL_TEXT_SP)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        progressTintList = ColorStateList.valueOf(INK)
        progressBackgroundTintList = ColorStateList.valueOf(LINE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        orientation = VERTICAL
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(context.horizontal().apply {
            addView(status, LayoutParams(0, WRAP_CONTENT, 1f))
            addView(percentLabel, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(8) })
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(bar, LayoutParams(MATCH_PARENT, context.dp(4)).apply { topMargin = context.dp(6) })
    }

    fun show(percent: Int) {
        bar.setProgress(percent, false)
        percentLabel.text = context.getString(R.string.update_download_percent, percent)
        if (percent == 100) status.text = "正在校验安装包"
    }
}
