package com.locusable.ginkgo.ui.page

import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import com.locusable.ginkgo.launcher.AppEntry
import com.locusable.ginkgo.ui.addTileCells
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.paperTileList
import com.locusable.ginkgo.ui.showTileRows

internal const val APP_COLUMNS = 4

internal fun LinearLayout.showApps(
    apps: List<AppEntry>, pinnedKeys: Set<String>, tile: (AppEntry, Boolean) -> View,
): ListView? {
    if (apps.isEmpty()) {
        notice("没有可启动的应用")
        return null
    }
    return context.paperTileList().apply {
        showTileRows(apps, APP_COLUMNS) { row, group ->
            row.addTileCells(group, APP_COLUMNS, fixedHeight = false) { tile(it, it.key in pinnedKeys) }
        }
    }
}
