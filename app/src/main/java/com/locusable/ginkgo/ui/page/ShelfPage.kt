package com.locusable.ginkgo.ui.page

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.cover.ShelfCovers
import com.locusable.ginkgo.shelf.weread.WeReadCovers
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.CAPTION_TEXT_SP
import com.locusable.ginkgo.ui.DIALOG_TITLE_SP
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
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.paperDialog
import com.locusable.ginkgo.ui.paperDialogPanel
import com.locusable.ginkgo.ui.paperTileList
import com.locusable.ginkgo.ui.showTileRows

private const val SHELF_COLUMNS = 4
private const val FOLDER_COLUMNS = 3
private const val SHELF_COLUMN_GAP_DP = 14
private const val SHELF_ROW_GAP_DP = 16

private fun LinearLayout.shelfFolderWidth(shelfWidth: Int): Int {
    val gap = context.dp(TILE_COLUMN_GAP_DP)
    val shelfTilesWidth = shelfWidth - (SHELF_COLUMNS - 1) * gap
    return paddingLeft + paddingRight + shelfTilesWidth * FOLDER_COLUMNS / SHELF_COLUMNS + (FOLDER_COLUMNS - 1) * gap
}

internal data class ShelfPageModel(
    val permissionRequired: Boolean,
    val notice: String?,
    val entries: List<ShelfEntry>,
    val inFolder: Boolean = false,
)

internal class ShelfRowCovers {
    val local = mutableListOf<ShelfCovers.Target>()
    val online = mutableListOf<WeReadCovers.Target>()
}

internal fun LinearLayout.showShelf(
    model: ShelfPageModel,
    requestBooksPermission: () -> Unit,
    tile: (ShelfEntry, ShelfRowCovers) -> View,
): ListView? {
    if (model.entries.isEmpty()) {
        if (model.permissionRequired) {
            notice("允许读取后，这里会显示设备里的书。")
            showPermissionAction(requestBooksPermission)
        } else model.notice?.let { notice(it) }
        return null
    }
    val root = !model.inFolder
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
            if (root) row.setPadding(0, 0, 0, context.dp(SHELF_ROW_GAP_DP))
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

internal class ShelfFolderWindow(activity: Activity, shelfWidth: Int, goBack: () -> Unit) {
    val dialog = activity.paperDialog()
    val content = activity.column()
    private val panel = activity.paperDialogPanel(maxHeight = activity.dp(activity.resources.configuration.screenHeightDp * 3 / 4))
    private val width = panel.shelfFolderWidth(shelfWidth)
    private val title = activity.label("", DIALOG_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val path = activity.label("", CAPTION_TEXT_SP, tone = SOFT_INK).apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.START
    }
    private val back = InkGlyph(activity, InkGlyph.Kind.CLOSE, sizeDp = 20).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        buttonRole()
        action(stroke = LINE, click = goBack)
    }
    private val loadingHeight = ((width - panel.paddingLeft - panel.paddingRight -
        activity.dp(TILE_COLUMN_GAP_DP) * (FOLDER_COLUMNS - 1)) / FOLDER_COLUMNS) * 3 / 2 + activity.dp(48)

    init {
        panel.addView(activity.horizontal().apply {
            gravity = Gravity.TOP
            addView(back, LinearLayout.LayoutParams(activity.dp(ACTION_HEIGHT_DP), activity.dp(ACTION_HEIGHT_DP)).apply {
                marginEnd = activity.dp(8)
            })
            addView(activity.column().apply {
                minimumHeight = activity.dp(ACTION_HEIGHT_DP)
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(path, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.gap(10)
        panel.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.isClickable = true
        panel.isFocusable = false
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        dialog.setContentView(AnchoredShelfFolder(activity, panel).apply {
            setOnClickListener { dialog.cancel() }
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    fun prepare(name: String, parentPath: String?, loading: Boolean) {
        title.text = name
        path.text = parentPath.orEmpty()
        path.visibility = if (parentPath == null) View.GONE else View.VISIBLE
        back.kind = if (parentPath == null) InkGlyph.Kind.CLOSE else InkGlyph.Kind.LEFT
        back.contentDescription = if (parentPath == null) "关闭文件夹，返回书架" else "返回上一级"
        content.minimumHeight = if (loading) maxOf(content.height, content.minimumHeight)
            .takeIf { it > 0 } ?: loadingHeight else 0
        content.removeAllViews()
    }

    fun show() {
        dialog.window?.setLayout(width, MATCH_PARENT)
        dialog.show()
    }
}

private class AnchoredShelfFolder(context: Context, private val panel: View) : FrameLayout(context) {
    private val position = LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)
    private var viewportHeight = -1

    init { addView(panel, position) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getSize(heightMeasureSpec) != viewportHeight) position.topMargin = 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val height = bottom - top
        if (viewportHeight != height) {
            viewportHeight = height
            // Keep the initial top offset when contents resize; FrameLayout then measures the space below it.
            position.topMargin = (height - panel.measuredHeight).coerceAtLeast(0) / 2
        }
        super.onLayout(changed, left, top, right, bottom)
    }
}
