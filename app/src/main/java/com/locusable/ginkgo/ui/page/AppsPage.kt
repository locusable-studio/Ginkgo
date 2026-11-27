package com.locusable.ginkgo.ui.page

import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.locusable.ginkgo.launcher.AppEntry
import com.locusable.ginkgo.launcher.LauncherPage
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.TILE_ROW_GAP
import com.locusable.ginkgo.ui.column
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.tileRows
import com.locusable.ginkgo.ui.title
import com.locusable.ginkgo.ui.updatePager

private const val APP_COLUMNS = 4

internal fun LinearLayout.showApps(
    scroll: ScrollView,
    apps: List<AppEntry>,
    pinnedKeys: Set<String>,
    initialPage: Int,
    rowHeight: Int,
    onPageChanged: (Int) -> Unit,
    tile: (AppEntry, Boolean) -> View,
) {
    title(LauncherPage.APPS.title)
    if (apps.isEmpty()) {
        notice("没有可启动的应用")
        return
    }
    val rows = context.column().also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    val pager = FrameLayout(context).apply { visibility = View.GONE }
    addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(ACTION_HEIGHT_DP)))
    val rowStep = context.dp(rowHeight) + context.dp(TILE_ROW_GAP)
    val rowCount = (apps.size - 1) / APP_COLUMNS + 1
    var rowsPerPage = 0
    var page = initialPage

    fun showPage(index: Int) {
        val pageSize = rowsPerPage * APP_COLUMNS
        val total = (apps.size - 1) / pageSize + 1
        page = index.coerceIn(0, total - 1)
        onPageChanged(page)
        val start = page * pageSize
        rows.removeAllViews()
        rows.tileRows(apps.subList(start, minOf(start + pageSize, apps.size)), APP_COLUMNS, rowHeight = rowHeight) { app ->
            tile(app, app.key in pinnedKeys)
        }
        if (total > 1) {
            pager.visibility = View.VISIBLE
            pager.updatePager(page, total) { step ->
                scroll.scrollTo(0, 0)
                showPage(page + step)
            }
        } else {
            pager.visibility = View.GONE
            pager.removeAllViews()
        }
    }

    val layoutPages = ViewTreeObserver.OnPreDrawListener {
        if (rows.width == 0) return@OnPreDrawListener true
        val height = (scroll.height - rows.top - paddingBottom).coerceAtLeast(0)
        val available = if (rowCount > height / rowStep) (height - context.dp(ACTION_HEIGHT_DP)).coerceAtLeast(0) else height
        val count = (available / rowStep).coerceIn(1, rowCount)
        if (count == rowsPerPage) return@OnPreDrawListener true
        val index = if (rowsPerPage == 0) page else page * rowsPerPage / count
        rowsPerPage = count
        showPage(index)
        false
    }
    scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.addOnPreDrawListener(layoutPages)
        override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnPreDrawListener(layoutPages)
    })
}
