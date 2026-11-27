package com.locusable.ginkgo.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun Activity.showPaperDialog(
    title: String,
    message: String,
    primaryLabel: String,
    extraContent: View? = null,
    onConfirm: (() -> Unit)? = null,
): Dialog {
    val dialog = paperDialog()
    val panel = paperDialogPanel()
    panel.addView(TextView(this).apply {
        text = title
        textSize = DIALOG_TITLE_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(INK)
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    panel.addView(TextView(this).apply {
        text = message
        textSize = ACTION_TEXT_SP
        setTextColor(SOFT_INK)
        setLineSpacing(dp(3).toFloat(), 1f)
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

    if (extraContent != null) {
        panel.addView(extraContent, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }

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
    dialog.setContentView(paperDialogContent(panel))
    presentPaperDialog(dialog)
    return dialog
}

internal fun Context.paperDialogPanel() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(PAGE_INSET_DP), dp(SECTION_GAP_DP), dp(PAGE_INSET_DP), dp(SECTION_GAP_DP))
    background = paperShape(INK)
}

internal fun Context.paperDialogContent(panel: LinearLayout) = ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(panel)
}

internal fun Activity.paperDialog(secure: Boolean = false): Dialog = Dialog(this).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setCanceledOnTouchOutside(true)
    window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setDimAmount(0.15f)
        setWindowAnimations(0)
        setGravity(Gravity.CENTER)
    }
}

internal fun Activity.presentPaperDialog(dialog: Dialog) {
    dialog.show()
    val width = (resources.displayMetrics.widthPixels - dp(PAGE_INSET_DP * 2)).coerceIn(dp(1), dp(340))
    dialog.window?.setLayout(width, WRAP_CONTENT)
}
