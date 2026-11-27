package com.locusable.ginkgo.ui.page

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.locusable.ginkgo.launcher.LauncherPage
import com.locusable.ginkgo.settings.InkPreferences
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.ACTION_TEXT_SP
import com.locusable.ginkgo.ui.DISABLED_INK
import com.locusable.ginkgo.ui.INK
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.PAGE_INSET_DP
import com.locusable.ginkgo.ui.PaperRow
import com.locusable.ginkgo.ui.ROW_TEXT_SP
import com.locusable.ginkgo.ui.SECTION_GAP_DP
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.pressBackground
import com.locusable.ginkgo.ui.section
import com.locusable.ginkgo.ui.tileRows
import com.locusable.ginkgo.ui.title

internal class SettingsPage(
    private val context: Context,
    private val prefs: InkPreferences,
    private val onTabsChanged: () -> Unit,
    private val onLayoutChanged: (LauncherPage) -> Unit,
    private val openWeReadAccount: () -> Unit,
    private val exportWallpaper: () -> Unit,
) {
    fun render(content: LinearLayout) = with(content) {
        title("设置")
        section("显示页面", "")
        gap(4)
        val config = resources.configuration
        val contentWidth = config.screenWidthDp - 2 * PAGE_INSET_DP
        val columns = if (contentWidth >= 3 * 96 * config.fontScale.coerceAtLeast(1f)) 3 else 2
        val options = listOf(
            visibilityOption(LauncherPage.HOME.title, checked = true, enabled = false),
            visibilityOption(LauncherPage.APPS.title, prefs.showAppsTab) { checked ->
                prefs.showAppsTab = checked
                onTabsChanged()
            },
            visibilityOption(LauncherPage.SHELF.title, prefs.showShelfTab) { checked ->
                prefs.showShelfTab = checked
                onTabsChanged()
            },
            visibilityOption(LauncherPage.ABOUT.title, prefs.showAboutTab) { checked ->
                prefs.showAboutTab = checked
                onTabsChanged()
            },
            visibilityOption(LauncherPage.SETTINGS.title, checked = true, enabled = false),
        )
        tileRows(options, columns, rowGap = 0) { it }
        gap(SECTION_GAP_DP)
        section("展示方式", "")
        gap(4)
        layoutChoice(LauncherPage.HOME.title, "图标", prefs.homeGrid) { prefs.homeGrid = it }
        layoutChoice(LauncherPage.APPS.title, "图标", prefs.appsGrid) {
            prefs.appsGrid = it
            onLayoutChanged(LauncherPage.APPS)
        }
        layoutChoice(LauncherPage.SHELF.title, "封面", prefs.shelfGrid) {
            prefs.shelfGrid = it
            onLayoutChanged(LauncherPage.SHELF)
        }
        gap(SECTION_GAP_DP)
        section("书架来源", "")
        gap(4)
        val sources = prefs.shelfSources
        tileRows(listOf(
            visibilityOption("本地书架", sources.local) { checked ->
                prefs.shelfSources = prefs.shelfSources.copy(local = checked)
            },
            visibilityOption("微信读书", sources.weRead) { checked ->
                prefs.shelfSources = prefs.shelfSources.copy(weRead = checked)
            },
        ), 2, rowGap = 0) { it }
        gap(SECTION_GAP_DP)
        section("账号", "")
        folderRow("微信读书", InkGlyph.Kind.BOOK, InkGlyph.Kind.RIGHT, openWeReadAccount)
        gap(SECTION_GAP_DP)
        section("工具", "")
        folderRow("导出书架壁纸", InkGlyph.Kind.WALLPAPER, InkGlyph.Kind.ARROW, exportWallpaper)
    }

    private fun LinearLayout.layoutChoice(name: String, alternative: String, useAlternative: Boolean, change: (Boolean) -> Unit) {
        addView(context.horizontal().apply {
            setPadding(0, context.dp(3), 0, context.dp(3))
            addView(context.label(name, ROW_TEXT_SP).apply { setPadding(0, 0, 0, context.dp(6)) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = context.dp(8) })
            val group = RadioGroup(context).apply {
                orientation = RadioGroup.HORIZONTAL
                setBaselineAligned(false)
            }
            val list = layoutButton(name, "列表")
            val other = layoutButton(name, alternative)
            group.addView(list, RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.dp(6) })
            group.addView(other, RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            group.check(if (useAlternative) other.id else list.id)
            group.setOnCheckedChangeListener { _, id -> if (id == list.id || id == other.id) change(id == other.id) }
            addView(group, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun layoutButton(pageName: String, value: String) = object : RadioButton(context) {
        private val mark = Paint().apply { color = INK }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isChecked) {
                val left = (width - context.dp(20)) / 2f
                val bottom = (height - context.dp(7)).toFloat()
                canvas.drawRect(left, bottom - context.dp(2), left + context.dp(20), bottom, mark)
            }
        }
    }.apply {
        id = View.generateViewId()
        text = value
        contentDescription = "$pageName，$value"
        textSize = ACTION_TEXT_SP
        isAllCaps = false
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        buttonDrawable = null
        minWidth = context.dp(56)
        minHeight = context.dp(ACTION_HEIGHT_DP)
        setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(9))
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(INK, SOFT_INK)))
        background = context.pressBackground()
    }

    private fun visibilityOption(name: String, checked: Boolean, enabled: Boolean = true, onToggle: ((Boolean) -> Unit)? = null) = context.horizontal().apply {
        val check = visibilityCheck(name, checked, enabled, onToggle)
        addVisibilityCheck(check)
        addView(visibilityTitle(name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (enabled) {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = context.pressBackground()
            setOnClickListener { check.performClick() }
        }
    }

    private fun visibilityCheck(name: String, checked: Boolean, enabled: Boolean, onToggle: ((Boolean) -> Unit)?) = CheckBox(context).apply {
        contentDescription = name
        buttonTintList = ColorStateList.valueOf(if (enabled) INK else DISABLED_INK)
        setPadding(context.dp(5), 0, 0, 0)
        minimumHeight = context.dp(ACTION_HEIGHT_DP)
        isChecked = checked
        isEnabled = enabled
        background = if (enabled) context.pressBackground() else null
        if (onToggle != null) setOnCheckedChangeListener { _, value -> onToggle(value) }
    }

    private fun LinearLayout.addVisibilityCheck(check: CheckBox) = addView(check, LinearLayout.LayoutParams(context.dp(ACTION_HEIGHT_DP), WRAP_CONTENT))

    private fun visibilityTitle(name: String) = context.label(name, ROW_TEXT_SP).apply {
        maxLines = 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun LinearLayout.folderRow(name: String, icon: InkGlyph.Kind, arrow: InkGlyph.Kind?, open: () -> Unit) {
        addView(PaperRow(context, name, InkGlyph(context, icon), arrow).apply { action(click = open) })
    }
}
