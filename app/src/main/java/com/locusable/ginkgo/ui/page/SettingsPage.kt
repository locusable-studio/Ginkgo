package com.locusable.ginkgo.ui.page

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import com.locusable.ginkgo.launcher.LauncherPage
import com.locusable.ginkgo.settings.InkPreferences
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.DISABLED_INK
import com.locusable.ginkgo.ui.INK
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.PAGE_INSET_DP
import com.locusable.ginkgo.ui.PaperRow
import com.locusable.ginkgo.ui.ROW_TEXT_SP
import com.locusable.ginkgo.ui.SECTION_GAP_DP
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.pressBackground
import com.locusable.ginkgo.ui.section
import com.locusable.ginkgo.ui.tileRows

internal class SettingsPage(
    private val context: Context,
    private val prefs: InkPreferences,
    private val onTabsChanged: () -> Unit,
    private val openWeReadAccount: () -> Unit,
    private val exportWallpaper: () -> Unit,
    private val checkUpdate: () -> Unit,
) {
    fun render(content: LinearLayout) = with(content) {
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
        folderRow("检查更新", InkGlyph.Kind.REFRESH, InkGlyph.Kind.RIGHT, checkUpdate)
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
