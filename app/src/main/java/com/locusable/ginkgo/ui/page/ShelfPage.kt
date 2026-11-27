package com.locusable.ginkgo.ui.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ListView
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.cover.ShelfCovers
import com.locusable.ginkgo.shelf.weread.WeReadCovers
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.ACTION_TEXT_SP
import com.locusable.ginkgo.ui.HEADING_GAP_DP
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.LABEL_TEXT_SP
import com.locusable.ginkgo.ui.LINE
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.TILE_COLUMN_GAP_DP
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.addTileCells
import com.locusable.ginkgo.ui.buttonRole
import com.locusable.ginkgo.ui.column
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.paperButton
import com.locusable.ginkgo.ui.paperTileList
import com.locusable.ginkgo.ui.showTileRows
import com.locusable.ginkgo.ui.title

private const val SHELF_COLUMNS = 4
private const val FOLDER_COLUMNS = 3

internal fun LinearLayout.shelfFolderWidth(shelfWidth: Int): Int {
    val gap = context.dp(TILE_COLUMN_GAP_DP)
    val shelfTilesWidth = shelfWidth - (SHELF_COLUMNS - 1) * gap
    return paddingLeft + paddingRight + shelfTilesWidth * FOLDER_COLUMNS / SHELF_COLUMNS + (FOLDER_COLUMNS - 1) * gap
}

internal data class ShelfPageModel(
    val folderName: String?,
    val backLabel: String?,
    val permissionRequired: Boolean,
    val notice: String?,
    val entries: List<ShelfEntry>,
)

internal class ShelfRowCovers {
    val local = mutableListOf<ShelfCovers.Target>()
    val online = mutableListOf<WeReadCovers.Target>()
}

internal fun LinearLayout.showShelf(
    model: ShelfPageModel,
    goBack: () -> Unit,
    requestBooksPermission: () -> Unit,
    tile: (ShelfEntry, ShelfRowCovers) -> View,
): ListView? {
    model.folderName?.let { name ->
        title(name, leading = model.backLabel?.let { context.shelfBackButton(it, goBack) })
    }
    if (model.entries.isEmpty()) {
        if (model.permissionRequired) showPermissionEmpty(requestBooksPermission)
        else model.notice?.let { notice(it) }
        return null
    }
    val columns = if (model.folderName == null) SHELF_COLUMNS else FOLDER_COLUMNS
    return context.paperTileList().apply {
        if (model.permissionRequired) {
            addHeaderView(context.column().apply { showPermissionHint(requestBooksPermission) }, null, true)
        }
        showTileRows(model.entries, columns) { row, entries ->
            val covers = ShelfRowCovers()
            row.addTileCells(entries, columns, fixedHeight = false) { tile(it, covers) }
            row.tag = covers
        }
    }
}

private fun LinearLayout.showPermissionEmpty(requestPermission: () -> Unit) {
    addView(context.column().apply {
        gravity = Gravity.CENTER
        addView(context.label("允许读取后，这里会显示设备里的书。", ACTION_TEXT_SP, tone = SOFT_INK).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(HEADING_GAP_DP)
        addView(context.paperButton("允许读取", primary = true, click = requestPermission),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
}

private fun LinearLayout.showPermissionHint(requestPermission: () -> Unit) {
    addView(context.horizontal().apply {
        addView(context.label("本地图书未显示", LABEL_TEXT_SP, tone = SOFT_INK),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(context.label("允许读取", LABEL_TEXT_SP).apply {
            gravity = Gravity.CENTER
            minimumHeight = context.dp(ACTION_HEIGHT_DP)
            setPaddingRelative(context.dp(12), 0, context.dp(4), 0)
            buttonRole()
            action(click = requestPermission)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

private fun Context.shelfBackButton(description: String, goBack: () -> Unit) = InkGlyph(this, InkGlyph.Kind.LEFT, sizeDp = 20).apply {
    layoutParams = LinearLayout.LayoutParams(dp(ACTION_HEIGHT_DP), dp(ACTION_HEIGHT_DP)).apply { marginEnd = dp(8) }
    contentDescription = description
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    buttonRole()
    action(stroke = LINE, click = goBack)
}
