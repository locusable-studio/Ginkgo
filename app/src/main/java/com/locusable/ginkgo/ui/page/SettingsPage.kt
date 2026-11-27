package com.locusable.ginkgo.ui.page

import android.content.Context
import android.graphics.ColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.locusable.ginkgo.launcher.LauncherPage
import com.locusable.ginkgo.settings.InkPreferences
import com.locusable.ginkgo.shelf.weread.WeReadWeb
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.DISABLED_INK
import com.locusable.ginkgo.ui.INK
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.PaperRow
import com.locusable.ginkgo.ui.ROW_TEXT_SP
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.buttonRole
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.pressBackground
import com.locusable.ginkgo.ui.section

private const val SETTINGS_SECTION_GAP_DP = 16

internal class SettingsPage(
    private val context: Context,
    private val prefs: InkPreferences,
    private val onTabsChanged: () -> Unit,
    private val openWeReadAccount: () -> Unit,
    private val grayscale: ColorFilter,
    private val exportWallpaper: () -> Unit,
    private val checkUpdate: () -> Unit,
) {
    private val weReadAccountTag = Any()

    fun render(content: LinearLayout, profile: WeReadWeb.Profile?) = with(content) {
        section("显示页面", "")
        toggleRow(LauncherPage.HOME.title, checked = true)
        toggleRow(LauncherPage.APPS.title, prefs.showAppsTab) {
            prefs.showAppsTab = it
            onTabsChanged()
        }
        toggleRow(LauncherPage.SHELF.title, prefs.showShelfTab) {
            prefs.showShelfTab = it
            onTabsChanged()
        }
        toggleRow(LauncherPage.ABOUT.title, prefs.showAboutTab) {
            prefs.showAboutTab = it
            onTabsChanged()
        }
        toggleRow(LauncherPage.SETTINGS.title, checked = true)

        gap(SETTINGS_SECTION_GAP_DP)
        section("书架来源", "")
        val sources = prefs.shelfSources
        toggleRow("本地书架", sources.local) { prefs.shelfSources = prefs.shelfSources.copy(local = it) }
        toggleRow("微信读书", sources.weRead) { prefs.shelfSources = prefs.shelfSources.copy(weRead = it) }

        gap(SETTINGS_SECTION_GAP_DP)
        section("账号", "")
        addView(FrameLayout(context).apply { tag = weReadAccountTag })
        updateWeReadProfile(this, profile)

        gap(SETTINGS_SECTION_GAP_DP)
        section("工具", "")
        actionRow("导出书架壁纸", InkGlyph.Kind.WALLPAPER, exportWallpaper)
        actionRow("检查更新", InkGlyph.Kind.REFRESH, checkUpdate)
    }

    fun updateWeReadProfile(root: View, profile: WeReadWeb.Profile?) {
        val slot = root.findViewWithTag<FrameLayout>(weReadAccountTag) ?: return
        if (slot.getChildAt(0)?.let { it.tag === profile } == true) return
        val focused = slot.hasFocus()
        val icon = profile?.avatar?.let { avatar ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                colorFilter = grayscale
                setImageBitmap(avatar)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } ?: InkGlyph(context, InkGlyph.Kind.BOOK)
        val row = PaperRow(context, profile?.name ?: "微信读书", icon, InkGlyph.Kind.RIGHT).apply {
            tag = profile
            profile?.name?.let { contentDescription = "微信读书，$it" }
            buttonRole()
            action(click = openWeReadAccount)
        }
        slot.removeAllViews()
        slot.addView(row)
        if (focused) row.requestFocus()
    }

    private fun LinearLayout.toggleRow(name: String, checked: Boolean, onToggle: ((Boolean) -> Unit)? = null) {
        val enabled = onToggle != null
        addView(CheckBox(context).apply {
            val tone = if (enabled) INK else DISABLED_INK
            val mark = buttonDrawable?.mutate()?.apply { setTint(tone) }
            setButtonDrawable(null)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, mark, null)
            compoundDrawablePadding = context.dp(12)
            text = name
            textSize = ROW_TEXT_SP
            setTextColor(tone)
            setAllCaps(false)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minimumHeight = context.dp(ACTION_HEIGHT_DP)
            background = if (enabled) context.pressBackground() else null
            backgroundTintList = null
            defaultFocusHighlightEnabled = false
            setPaddingRelative(context.dp(4), context.dp(6), context.dp(4), context.dp(6))
            isEnabled = enabled
            isChecked = checked
            jumpDrawablesToCurrentState()
            if (onToggle != null) setOnCheckedChangeListener { _, value ->
                jumpDrawablesToCurrentState()
                onToggle(value)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun LinearLayout.actionRow(name: String, icon: InkGlyph.Kind, open: () -> Unit) {
        addView(PaperRow(context, name, InkGlyph(context, icon), InkGlyph.Kind.RIGHT).apply {
            buttonRole()
            action(click = open)
        })
    }
}
