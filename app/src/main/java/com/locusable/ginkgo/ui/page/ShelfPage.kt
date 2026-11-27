package com.locusable.ginkgo.ui.page

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ListView
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.cover.ShelfCovers
import com.locusable.ginkgo.shelf.weread.WeReadCovers
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.CAPTION_TEXT_SP
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.LINE
import com.locusable.ginkgo.ui.PaperRow
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.TILE_COLUMN_GAP_DP
import com.locusable.ginkgo.ui.TILE_ROW_GAP
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.addTileCells
import com.locusable.ginkgo.ui.buttonRole
import com.locusable.ginkgo.ui.column
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.paperTileList
import com.locusable.ginkgo.ui.showTileRows
import com.locusable.ginkgo.ui.title

private const val SHELF_COLUMNS = 4
private const val FOLDER_COLUMNS = 3
private const val SHELF_COLUMN_GAP_DP = 14

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
    val parentPath: String? = null,
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
        model.parentPath?.let { path ->
            addView(context.label(path, CAPTION_TEXT_SP, tone = SOFT_INK).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.START
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            gap(4)
        }
        title(name, leading = model.backLabel?.let { context.shelfBackButton(it, goBack) }, singleLine = true)
    }
    if (model.entries.isEmpty()) {
        if (model.permissionRequired) {
            notice("允许读取后，这里会显示设备里的书。")
            showPermissionAction(requestBooksPermission)
        } else model.notice?.let { notice(it) }
        return null
    }
    val root = model.folderName == null
    val columns = if (root) SHELF_COLUMNS else FOLDER_COLUMNS
    val columnGap = if (root) SHELF_COLUMN_GAP_DP else TILE_COLUMN_GAP_DP
    return context.paperTileList().apply {
        if (model.permissionRequired) {
            addHeaderView(context.column().apply {
                showPermissionAction(requestBooksPermission)
                gap(TILE_ROW_GAP)
            }, null, true)
        }
        showTileRows(model.entries, columns, anchorKey = ::shelfAnchorKey) { row, entries ->
            val covers = ShelfRowCovers()
            row.addTileCells(entries, columns, fixedHeight = false, columnGapDp = columnGap) { tile(it, covers) }
            row.tag = covers
        }
    }
}

private fun shelfAnchorKey(entry: ShelfEntry): String = when {
    entry is ShelfEntry.Local -> "local:${entry.id}"
    entry.directory -> "group:${entry.id}"
    else -> "weread:${entry.id}"
}

private fun LinearLayout.showPermissionAction(requestPermission: () -> Unit) {
    addView(PaperRow(context, "允许读取本地图书", InkGlyph(context, InkGlyph.Kind.FILE), InkGlyph.Kind.RIGHT).apply {
        buttonRole()
        action(stroke = LINE, click = requestPermission)
    })
}

private fun Context.shelfBackButton(description: String, goBack: () -> Unit) = InkGlyph(this, InkGlyph.Kind.LEFT, sizeDp = 20).apply {
    layoutParams = LinearLayout.LayoutParams(dp(ACTION_HEIGHT_DP), dp(ACTION_HEIGHT_DP)).apply { marginEnd = dp(8) }
    contentDescription = description
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    buttonRole()
    action(stroke = LINE, click = goBack)
}
