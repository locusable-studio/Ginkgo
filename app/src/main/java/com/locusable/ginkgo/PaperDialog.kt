package com.locusable.ginkgo

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

internal fun Activity.showPaperDialog(
    title: String,
    message: String,
    primaryLabel: String,
    onConfirm: (() -> Unit)? = null,
): Dialog {
    val dialog = Dialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(PAGE_INSET_DP), dp(20), dp(PAGE_INSET_DP), dp(20))
        background = paperShape(INK)
    }
    panel.addView(TextView(this).apply {
        text = title
        textSize = 23f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(INK)
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    panel.addView(TextView(this).apply {
        text = message
        textSize = 15f
        setTextColor(SOFT_INK)
        setLineSpacing(dp(3).toFloat(), 1f)
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setBaselineAligned(false)
    }
    if (onConfirm != null) {
        buttons.addView(paperButton("取消", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
    }
    buttons.addView(paperButton(primaryLabel, primary = true) {
        dialog.dismiss()
        onConfirm?.invoke()
    }, if (onConfirm == null) LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
       else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    panel.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
    dialog.setContentView(panel)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.15f)
        setWindowAnimations(0)
        setGravity(Gravity.CENTER)
    }
    dialog.show()
    val width = (resources.displayMetrics.widthPixels - dp(PAGE_INSET_DP * 2)).coerceIn(dp(1), dp(340))
    dialog.window?.setLayout(width, WRAP_CONTENT)
    return dialog
}
