package com.locusable.ginkgo.ui.page

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import com.locusable.ginkgo.R
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.displayTitle
import com.locusable.ginkgo.ui.CAPTION_TEXT_SP
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.PAGE_INSET_DP
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.bookAuthorLabel
import com.locusable.ginkgo.ui.buttonRole
import com.locusable.ginkgo.ui.column
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.notice
import com.locusable.ginkgo.ui.section
import com.locusable.ginkgo.ui.tileRows

private const val HOME_COLUMNS = 4
internal const val HOME_RECENT_BOOK_LIMIT = 4
private const val HOME_SECTION_GAP_DP = 14
private const val HOME_TILE_GAP_DP = 8

internal fun LinearLayout.showHome(
    recentBooks: List<ShelfEntry>,
    booksReady: Boolean,
    featuredCover: (ShelfEntry) -> View,
    bindAuthor: (ShelfEntry, (String) -> Unit) -> Unit,
    recentBookTile: (ShelfEntry) -> View,
    openBook: (ShelfEntry) -> Unit,
) {
    val credit = context.label("by ${context.getString(R.string.app_developer)}", CAPTION_TEXT_SP, tone = SOFT_INK).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = context.dp((resources.configuration.screenWidthDp - 2 * PAGE_INSET_DP) / 2)
    }
    addView(context.horizontal().apply {
        addView(context.label(context.getString(R.string.app_name), 18f, face = Typeface.DEFAULT_BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(credit, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(8) })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val featured = recentBooks.firstOrNull()
    if (featured != null) {
        gap(12)
        section("继续阅读", "")
        addView(context.resumeRow(featured, featuredCover(featured), { display -> bindAuthor(featured, display) }) { openBook(featured) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val otherBooks = recentBooks.drop(1).take(HOME_RECENT_BOOK_LIMIT)
        if (otherBooks.isNotEmpty()) {
            gap(HOME_SECTION_GAP_DP)
            section("最近打开", "")
            tileRows(otherBooks, HOME_COLUMNS, rowGap = HOME_TILE_GAP_DP, tile = recentBookTile)
        }
    } else if (booksReady) {
        gap(12)
        notice("在书架打开一本书，下次就能在这里继续阅读。")
    }
}

private fun Context.resumeRow(book: ShelfEntry, cover: View, bindAuthor: ((String) -> Unit) -> Unit, open: () -> Unit) = horizontal().apply {
    val title = book.displayTitle
    val source = if (book is ShelfEntry.Online) "微信读书" else "多看"
    val author = bookAuthorLabel()
    fun showAuthor(name: String) {
        author.text = name
        contentDescription = "继续阅读，$title，作者：$name，$source"
    }
    showAuthor(author.text.toString())
    bindAuthor(::showAuthor)
    setPaddingRelative(0, dp(4), dp(2), dp(4))
    addView(cover, LinearLayout.LayoutParams(dp(54), WRAP_CONTENT))
    addView(column().apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(label(title, 21f, face = Typeface.DEFAULT_BOLD).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(author, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(13); marginEnd = dp(8) })
    addView(InkGlyph(context, InkGlyph.Kind.ARROW, tone = SOFT_INK).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(29), dp(30)))
    buttonRole()
    action(click = open)
}
