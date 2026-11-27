package com.locusable.ginkgo.ui.page

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.ShelfSort
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.ACTION_TEXT_SP
import com.locusable.ginkgo.ui.HEADING_GAP_DP
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.LABEL_TEXT_SP
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.buttonRole
import com.locusable.ginkgo.ui.column
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.paperButton
import com.locusable.ginkgo.ui.tileRows
import com.locusable.ginkgo.ui.title
import com.locusable.ginkgo.ui.updatePager

private const val SHELF_COLUMNS = 4
internal const val SHELF_PAGE_SIZE = SHELF_COLUMNS * 2

internal data class ShelfPageModel(
    val title: String,
    val sort: ShelfSort?,
    val backLabel: String?,
    val permissionRequired: Boolean,
    val notice: String?,
    val entries: List<ShelfEntry>,
    val index: Int,
    val total: Int,
)

internal fun LinearLayout.showShelf(
    model: ShelfPageModel,
    pager: FrameLayout,
    goBack: () -> Unit,
    changeSort: (ShelfSort) -> Unit,
    requestBooksPermission: () -> Unit,
    movePage: (Int) -> Unit,
    tile: (ShelfEntry) -> View,
) {
    val permissionEmpty = model.permissionRequired && model.entries.isEmpty()
    title(
        model.title,
        accessory = model.sort?.takeUnless { permissionEmpty }?.let { context.sortButton(it, changeSort) },
        leading = model.backLabel?.let { context.shelfBackButton(it, goBack) },
    )
    val paginated = !permissionEmpty && model.total > 1
    pager.visibility = if (paginated) View.VISIBLE else View.GONE
    if (permissionEmpty) {
        showPermissionEmpty(requestBooksPermission)
        return
    }
    if (model.permissionRequired) showPermissionHint(requestBooksPermission)
    model.notice?.let { notice(it) }
    tileRows(model.entries, SHELF_COLUMNS, tile = tile)
    if (paginated) pager.updatePager(model.index, model.total, movePage)
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
    layoutParams = LinearLayout.LayoutParams(dp(ACTION_HEIGHT_DP), dp(ACTION_HEIGHT_DP))
    contentDescription = description
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    buttonRole()
    action(click = goBack)
}

private fun Context.sortButton(sort: ShelfSort, change: (ShelfSort) -> Unit) = horizontal().apply {
    val current = sort.label
    val next = ShelfSort.entries[(sort.ordinal + 1) % ShelfSort.entries.size]
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    minimumHeight = dp(ACTION_HEIGHT_DP)
    minimumWidth = dp(112)
    setPaddingRelative(dp(6), 0, dp(4), 0)
    contentDescription = "排序方式：$current，点按切换为${next.label}"
    addView(InkGlyph(this@sortButton, InkGlyph.Kind.SORT, sizeDp = 21), LinearLayout.LayoutParams(dp(24), dp(24)))
    addView(label(current, LABEL_TEXT_SP).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(5) })
    buttonRole()
    action { change(next) }
}
