package com.locusable.ginkgo

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class InkActivity : Activity() {
    private enum class Page(val title: String) {
        HOME("首页"), APPS("应用"), SETTINGS("设置"), FOLDER("自定义目录"), ABOUT("关于"),
    }

    private companion object {
        const val PICK_FOLDER = 110
        const val READ_LOCAL_FOLDERS = 111
        const val HOME_COLUMNS = 4
        const val SHELF_COLUMNS = 4
    }

    private val prefs by lazy { InkPreferences(this) }
    private val catalog by lazy { AppCatalog(this) }
    private val folderAccess by lazy { FolderAccess(applicationContext) }
    private val folderWorker = Executors.newSingleThreadExecutor()
    private val covers by lazy { ShelfCovers(applicationContext, folderAccess) }
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private val developer get() = getString(R.string.app_developer)
    @Suppress("DEPRECATION")
    private val installedVersion by lazy {
        val info = packageManager.getPackageInfo(packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName.orEmpty()}($build)"
    }
    private var apps = emptyList<AppEntry>()
    private var boundFolder: BoundFolder? = null
    private val visiblePages get() = buildList {
        add(Page.HOME)
        if (prefs.showAppsTab) add(Page.APPS)
        if (boundFolder != null && prefs.showFolderTab) add(Page.FOLDER)
        if (prefs.showAboutTab) add(Page.ABOUT)
        add(Page.SETTINGS)
    }
    private var page = Page.HOME
    private var renderedPages = emptyList<Page>()
    private var appPage = 0
    private var folderPage = 0
    private var folderTrail = emptyList<FolderLocation>()
    private var folderContents: FolderContents = FolderContents.Loading
    private var folderRequest = 0
    private var folderOrderDirty = false
    private val mosaicRevisions = mutableMapOf<String, Int>()
    private var pinDialog: Dialog? = null
    private var body: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        boundFolder = prefs.boundFolder()
        val tab = savedInstanceState?.getInt("page", 0) ?: 0
        page = Page.entries.getOrElse(tab) { Page.HOME }.takeIf { it in visiblePages } ?: Page.HOME
        appPage = savedInstanceState?.getInt("app_page", 0) ?: 0
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (page == Page.FOLDER) reloadFolder()
    }

    override fun onDestroy() {
        pinDialog?.dismiss()
        folderRequest++
        folderWorker.shutdownNow()
        covers.close()
        super.onDestroy()
    }

    @Deprecated("Folder selection uses the Android 8.1 activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null) return
        val previous = boundFolder
        val selected = try {
            folderAccess.retain(data, previous)
        } catch (_: RuntimeException) {
            toast("无法访问所选目录，请重新选择")
            return
        }
        prefs.saveFolder(selected)
        boundFolder = selected
        if (previous != null && previous.uri != selected.uri) {
            folderAccess.release(previous.uri)
            if (previous.isLocal) LegacyFolders.clearReaderLinks(this)
        }
        folderRequest++
        folderTrail = listOf(selected.root)
        folderPage = 0
        folderContents = FolderContents.Loading
        render(scrollY = body?.scrollY ?: 0)
        if (page == Page.FOLDER) reloadFolder()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            pinDialog?.dismiss()
            show(Page.HOME)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page.ordinal)
        outState.putInt("app_page", appPage)
    }

    @Deprecated("Android 8.1 uses key-based navigation")
    override fun onBackPressed() {
        when {
            page == Page.FOLDER && folderTrail.size > 1 -> navigateFolder(folderTrail.dropLast(1))
            page != Page.HOME -> show(Page.HOME)
            !isDefaultHome() -> super.onBackPressed()
        }
    }

    private fun refresh() {
        val current = catalog.apps()
        prefs.seedPins(current)
        // Xiaomi may retain a HOME task and a separate launcher-entry task in the same process.
        if (body == null || renderedPages != visiblePages || apps != current || page == Page.SETTINGS ||
            (page == Page.FOLDER && folderOrderDirty)) {
            apps = current
            render(scrollY = body?.scrollY ?: 0)
        }
    }

    private fun show(next: Page) {
        if (next !in visiblePages) return
        if (page != next && next == Page.APPS) appPage = 0
        page = next
        render()
        if (next == Page.FOLDER) reloadFolder()
    }

    private fun render(scrollY: Int = 0) {
        val pages = visiblePages
        if (page !in pages) page = Page.HOME
        val root = column().apply { setBackgroundColor(Color.WHITE) }
        val panel = column().apply { setPadding(dp(PAGE_INSET_DP), 0, dp(PAGE_INSET_DP), 0) }
        root.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        body = scroll
        val inFolder = page == Page.FOLDER
        val sort = if (inFolder) prefs.folderSort else FolderSort.NAME
        val opened = if (sort == FolderSort.LAST_OPENED) prefs.folderOpenTimes() else emptyMap()
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val content = column().apply { setPadding(0, dp(18), 0, dp(12)) }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val coverTargets = mutableListOf<ShelfCovers.Target>()
        when (page) {
            Page.HOME -> fillHome(content)
            Page.APPS -> fillApps(content, scroll)
            Page.FOLDER -> {
                folderOrderDirty = false
                panel.rule()
                val pager = FrameLayout(this)
                panel.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, dp(ACTION_HEIGHT_DP)))
                fillFolder(content, scroll, pager, coverTargets, sort, opened)
            }
            Page.ABOUT -> fillAbout(content)
            Page.SETTINGS -> fillSettings(content)
        }
        root.addView(footer(pages), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        renderedPages = pages
        covers.show(if (inFolder) boundFolder else null, coverTargets, sort, opened) { location ->
            mosaicRevisions[location.id] ?: 0
        }
        if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun wordmark(): LinearLayout = horizontal().apply {
        gravity = Gravity.BOTTOM or Gravity.START
        val available = (resources.displayMetrics.widthPixels - dp(62)).toFloat()
        val byline = label("by $developer", 13f, tone = SOFT_INK).apply {
            includeFontPadding = false
            maxLines = 1
            fitLineTo(available * 0.4f)
        }
        val name = label(getString(R.string.app_name), 72f, face = Typeface.create("serif", Typeface.NORMAL)).apply {
            includeFontPadding = false
            maxLines = 1
            fitLineTo(available - byline.paint.measureText(byline.text.toString()))
        }
        addView(name, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(byline, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(11)
            bottomMargin = dp(12)
        })
    }

    private fun TextView.fitLineTo(width: Float) {
        val measured = paint.measureText(text.toString())
        if (measured > width) setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * width.coerceAtLeast(1f) / measured)
    }

    private fun fillHome(content: LinearLayout) = with(content) {
        addView(wordmark(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(25)
        val pins = prefs.pinned().mapNotNull { key -> apps.find { it.key == key } }
        section("常用应用", "${pins.size} / $MAX_PINS")
        if (pins.isEmpty()) {
            addView(label("还没有常用应用", 15f, tone = SOFT_INK), LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
            rule()
        } else if (prefs.homeGrid) {
            tileRows(pins, columns = HOME_COLUMNS, rowHeight = 112, tile = ::appTile)
        } else pins.forEach { item -> addView(appRow(item, pinned = true)) }
    }

    private fun appTile(item: AppEntry) = column().apply {
        val name = displayLabel(item)
        contentDescription = name
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(10), dp(2), dp(4))
        val iconSize = minOf(60, ((resources.configuration.screenWidthDp - 88) / HOME_COLUMNS).coerceAtLeast(34))
        val icon = catalog.icon(item)?.let { original ->
            ImageView(this@InkActivity).apply {
                setImageDrawable(original)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } ?: InkGlyph(this@InkActivity, InkGlyph.Kind.APP, sizeDp = 35)
        addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
        gap(6)
        addView(label(name, 14f).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        appAction(item, pinned = true)
    }

    private fun <T : Any> LinearLayout.tileRows(items: List<T>, columns: Int, rowHeight: Int? = null, tile: (T) -> View) {
        val rowSize = rowHeight?.let { dp(it) } ?: WRAP_CONTENT
        val tileHeight = if (rowHeight == null) WRAP_CONTENT else MATCH_PARENT
        items.chunked(columns).forEach { group ->
            gap(12)
            addView(horizontal().apply {
                gravity = Gravity.TOP
                repeat(columns) { index ->
                    val child = group.getOrNull(index)?.let(tile) ?: View(this@InkActivity)
                    addView(child, LinearLayout.LayoutParams(0, tileHeight, 1f).apply {
                        if (index > 0) marginStart = dp(9)
                    })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, rowSize))
        }
    }

    private fun fillApps(content: LinearLayout, scroll: ScrollView) = with(content) {
        title("应用")
        section("应用列表", "${apps.size} 项")
        if (apps.isEmpty()) {
            addView(label("没有可启动的应用", 15f, tone = SOFT_INK), LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
            rule()
            return@with
        }
        val rows = addRows()
        val pager = FrameLayout(this@InkActivity).apply { visibility = View.GONE }
        addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, dp(ACTION_HEIGHT_DP)))
        val hint = label("长按应用可固定到首页或取消固定。", 13f, tone = SOFT_INK).apply {
            setPadding(0, dp(14), 0, 0)
        }
        addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val pinned = prefs.pinned().toSet()
        showPagedList(content, scroll, rows, pager, apps, appPage, alwaysShowPager = false,
            trailingHeight = { hint.height }, title = ::displayLabel, hasPin = { it.key in pinned },
            onPage = { appPage = it }, makeRow = { appRow(it, pinned = it.key in pinned) })
    }

    private fun paginationRow(index: Int, total: Int, move: (Int) -> Unit) = horizontal().apply {
        addView(pageButton("上一页", InkGlyph.Kind.LEFT, index > 0) { move(-1) })
        addView(label("${index + 1}/$total", 14f, face = Typeface.MONOSPACE, tone = SOFT_INK).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            contentDescription = "第 ${index + 1} 页，共 $total 页"
        }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        addView(pageButton("下一页", InkGlyph.Kind.RIGHT, index + 1 < total) { move(1) })
    }

    private fun fillFolder(
        content: LinearLayout, scroll: ScrollView, pager: FrameLayout,
        coverTargets: MutableList<ShelfCovers.Target>, sort: FolderSort, opened: Map<String, Long>,
    ) = with(content) {
        val trail = folderTrail
        val name = trail.lastOrNull()?.name ?: boundFolder?.name.orEmpty()
        title(name, folderSortButton(sort))
        if (trail.size > 1) {
            folderRow("返回上一级", InkGlyph.Kind.LEFT, null) { navigateFolder(trail.dropLast(1)) }
        }
        var total = 1
        when (val result = folderContents) {
            FolderContents.Loading -> folderNotice("正在读取目录…")
            FolderContents.Unavailable -> {
                folderNotice("无法读取此目录，可能已移动或取消授权。")
                folderRow("重新选择目录", InkGlyph.Kind.FILE, InkGlyph.Kind.RIGHT) { chooseFolder() }
            }
            is FolderContents.Items -> {
                val entries = result.entries.orderedBy(sort, opened)
                section("目录内容", "${entries.size} 项")
                when {
                    entries.isEmpty() -> folderNotice("这个目录是空的")
                    prefs.folderShelf -> {
                        val pageSize = SHELF_COLUMNS * 2
                        total = (entries.size - 1) / pageSize + 1
                        folderPage = folderPage.coerceIn(0, total - 1)
                        val start = folderPage * pageSize
                        val visible = entries.subList(start, start + minOf(pageSize, entries.size - start))
                        tileRows(visible, SHELF_COLUMNS) { item -> shelfTile(item, coverTargets) }
                    }
                    else -> {
                        showPagedList(content, scroll, addRows(), pager, entries, folderPage, alwaysShowPager = true,
                            title = { it.location.name }, onPage = { folderPage = it }, makeRow = ::folderItemRow)
                        return@with
                    }
                }
            }
        }
        folderPage = folderPage.coerceIn(0, total - 1)
        updatePager(pager, folderPage, total) { step ->
            folderPage += step
            render()
        }
    }

    private fun updatePager(pager: FrameLayout, index: Int, total: Int, move: (Int) -> Unit) {
        pager.removeAllViews()
        pager.addView(paginationRow(index, total, move), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun LinearLayout.addRows() = column().also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    private fun <T> showPagedList(
        content: LinearLayout, scroll: ScrollView, rows: LinearLayout, pager: FrameLayout,
        entries: List<T>, initialPage: Int, alwaysShowPager: Boolean, trailingHeight: () -> Int = { 0 },
        title: (T) -> String, hasPin: (T) -> Boolean = { false }, onPage: (Int) -> Unit, makeRow: (T) -> View,
    ) {
        var pages = emptyList<IntRange>()
        var currentPage = initialPage

        fun showPage(index: Int) {
            currentPage = index.coerceIn(pages.indices)
            onPage(currentPage)
            rows.removeAllViews()
            for (i in pages[currentPage]) rows.addView(makeRow(entries[i]))
            if (alwaysShowPager || pages.size > 1) {
                pager.visibility = View.VISIBLE
                updatePager(pager, currentPage, pages.size) { step ->
                    scroll.scrollTo(0, 0)
                    showPage(currentPage + step)
                }
            } else {
                pager.visibility = View.GONE
                pager.removeAllViews()
            }
        }

        // Measure after the header and any sticky pager have taken their space.
        var lastWidth = 0
        var lastHeight = -1
        val layoutPages = ViewTreeObserver.OnPreDrawListener {
            val width = rows.width
            val height = (scroll.height - rows.top - content.paddingBottom - trailingHeight()).coerceAtLeast(0)
            if (width == 0 || (width == lastWidth && height == lastHeight)) return@OnPreDrawListener true
            lastWidth = width
            lastHeight = height
            val first = pages.getOrNull(currentPage)?.first
            val heights = measureRows(entries, width, title, hasPin)
            // The Apps pager is inline and only needs space if the list spills over one page.
            val available = if (!alwaysShowPager && heights.sumOf { it.toLong() } > height) {
                (height - dp(ACTION_HEIGHT_DP)).coerceAtLeast(0)
            } else height
            pages = pageRanges(heights, available)
            showPage(if (first == null) currentPage else pages.indexOfFirst { first in it })
            false // Wait for the rows to lay out before drawing them on the e-ink screen.
        }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.addOnPreDrawListener(layoutPages)
            override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnPreDrawListener(layoutPages)
        })
    }

    private fun <T> measureRows(entries: List<T>, width: Int, title: (T) -> String, hasPin: (T) -> Boolean): IntArray {
        fun sample(pin: Boolean) = PaperRow("", InkGlyph(this, InkGlyph.Kind.DOCUMENT), InkGlyph.Kind.ARROW, showPin = pin)
        val plain = sample(false)
        val pinned by lazy { sample(true) }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        return IntArray(entries.size) { index ->
            val item = entries[index]
            val row = if (hasPin(item)) pinned else plain
            row.titleView.text = title(item)
            row.titleView.forceLayout()
            row.forceLayout()
            row.measure(widthSpec, heightSpec)
            row.measuredHeight
        }
    }

    private fun pageRanges(heights: IntArray, pageHeight: Int): List<IntRange> = buildList {
        var start = 0
        var remaining = pageHeight
        for ((index, rowHeight) in heights.withIndex()) {
            if (index > start && rowHeight > remaining) {
                add(start until index)
                start = index
                remaining = pageHeight
            }
            remaining -= rowHeight
        }
        add(start until heights.size)
    }

    private fun folderItemRow(item: FolderItem): View {
        val icon = if (item.isDirectory) InkGlyph.Kind.FILE else InkGlyph.Kind.DOCUMENT
        val arrow = if (item.isDirectory) InkGlyph.Kind.RIGHT else InkGlyph.Kind.ARROW
        return PaperRow(item.location.name, InkGlyph(this, icon), arrow).apply { action { openFolderItem(item) } }
    }

    private fun openFolderItem(item: FolderItem) {
        if (item.isDirectory) {
            prefs.recordFolderOpen(item.location)
            navigateFolder(folderTrail + item.location)
            return
        }
        val readerUri = if (boundFolder?.isLocal == true) LegacyFolders.readerUri(this, item) else item.uri
        if (!tryStart(item.viewIntent(readerUri))) {
            toast("没有应用能打开此文件")
            return
        }
        prefs.recordFolderOpen(item.location)
        folderTrail.lastOrNull()?.id?.let { id -> mosaicRevisions[id] = (mosaicRevisions[id] ?: 0) + 1 }
        folderOrderDirty = prefs.folderSort == FolderSort.LAST_OPENED
    }

    private fun folderSortButton(sort: FolderSort) = horizontal().apply {
        val current = sort.label
        val next = FolderSort.entries[(sort.ordinal + 1) % FolderSort.entries.size]
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        minimumHeight = dp(ACTION_HEIGHT_DP)
        minimumWidth = dp(132)
        setPadding(dp(6), 0, dp(4), 0)
        contentDescription = "排序方式：$current，点按切换为${next.label}"
        addView(InkGlyph(this@InkActivity, InkGlyph.Kind.SORT, sizeDp = 21), LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(label(current, 14f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(5) })
        buttonRole()
        action {
            prefs.folderSort = next
            folderPage = 0
            render()
        }
    }

    private fun shelfTile(item: FolderItem, coverTargets: MutableList<ShelfCovers.Target>) = column().apply {
        val name = item.location.name
        val stem = name.substringBeforeLast('.')
        val extension = item.extension
        val hasSuffix = extension.length in 1..8 && extension.all { it in 'a'..'z' || it in '0'..'9' }
        val displayName = if (!item.isDirectory && stem.isNotBlank() && hasSuffix) stem else name
        contentDescription = if (item.isDirectory) "目录，$name" else name
        val cover = CoverFrame().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            showCoverInitial(displayName, 38f)
        }
        if (item.isDirectory) {
            coverTargets.add(ShelfCovers.Target.Directory(item.location) { faces ->
                if (faces.isEmpty()) cover.showCoverInitial(displayName, 38f) else cover.showCoverMosaic(faces)
            })
        } else if (item.coverFormat != null) {
            coverTargets.add(ShelfCovers.Target.File(item) { cover.showCoverBitmap(it) })
        }
        addView(cover, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(5)
        addView(horizontal().apply {
            gravity = Gravity.TOP
            if (item.isDirectory) {
                addView(InkGlyph(this@InkActivity, InkGlyph.Kind.FILE, sizeDp = 16, tone = SOFT_INK),
                    LinearLayout.LayoutParams(dp(17), dp(20)).apply { marginEnd = dp(4) })
            }
            addView(label(displayName, 14f, face = if (item.isDirectory) Typeface.DEFAULT_BOLD else Typeface.DEFAULT).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        action { openFolderItem(item) }
    }

    private inner class CoverFrame : FrameLayout(this@InkActivity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = (width * 3 + 1) / 2
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private fun FrameLayout.showCoverInitial(name: String, size: Float) {
        removeAllViews()
        background = paperShape(LINE, 0xFFF1F1F1.toInt())
        addView(label(coverInitial(name), size, face = Typeface.create("serif", Typeface.NORMAL)).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun coverInitial(name: String): String {
        var offset = 0
        while (offset < name.length) {
            val codePoint = name.codePointAt(offset)
            val length = Character.charCount(codePoint)
            if (Character.isLetterOrDigit(codePoint)) return name.substring(offset, offset + length).uppercase(Locale.ROOT)
            offset += length
        }
        return "·"
    }

    private fun FrameLayout.showCoverBitmap(bitmap: Bitmap) {
        removeAllViews()
        setBackgroundColor(Color.WHITE)
        addView(ImageView(this@InkActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            cropToPadding = true
            colorFilter = grayscale
            setImageBitmap(bitmap)
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun FrameLayout.showCoverMosaic(faces: List<ShelfCovers.Face>) {
        removeAllViews()
        setBackgroundColor(0xFFE6E6E6.toInt())
        addView(column().apply {
            repeat(2) { line ->
                addView(horizontal().apply {
                    repeat(2) { column ->
                        val panel = FrameLayout(this@InkActivity)
                        faces.getOrNull(line * 2 + column)?.let { face ->
                            if (face.bitmap != null) panel.showCoverBitmap(face.bitmap)
                            else panel.showCoverInitial(face.name.substringBeforeLast('.'), 21f)
                        }
                        addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                    }
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun LinearLayout.folderRow(name: String, icon: InkGlyph.Kind, arrow: InkGlyph.Kind?, open: () -> Unit) {
        addView(PaperRow(name, InkGlyph(this@InkActivity, icon), arrow).apply { action(click = open) })
    }

    private fun LinearLayout.folderNotice(message: String) {
        addView(label(message, 15f, tone = SOFT_INK).apply {
            setPadding(0, dp(14), 0, dp(14))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun navigateFolder(trail: List<FolderLocation>) {
        folderTrail = trail
        folderPage = 0
        folderContents = FolderContents.Loading
        render()
        reloadFolder()
    }

    private fun reloadFolder() {
        val binding = boundFolder ?: return
        if (folderTrail.isEmpty()) folderTrail = listOf(binding.root)
        val location = folderTrail.last()
        val request = ++folderRequest
        folderWorker.execute {
            val result = folderAccess.contents(binding, location)
            runOnUiThread {
                if (request != folderRequest || isDestroyed || result == folderContents) return@runOnUiThread
                folderContents = result
                if (page == Page.FOLDER) render(scrollY = body?.scrollY ?: 0)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder() {
        val local = Build.VERSION.SDK_INT <= 28 &&
            (boundFolder?.isLocal == true || packageManager.resolveContentProvider("com.android.externalstorage.documents", 0) == null)
        if (local && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_LOCAL_FOLDERS)
            return
        }
        val picker = if (local) {
            Intent(this, LocalFolderPickerActivity::class.java).apply {
                boundFolder?.takeIf { it.isLocal }?.uri?.path?.let { putExtra(LegacyFolders.EXTRA_LOCAL_DIRECTORY, it) }
            }
        } else folderAccess.picker(boundFolder)
        try {
            startActivityForResult(picker, PICK_FOLDER)
        } catch (_: ActivityNotFoundException) {
            toast("此系统未提供目录选择器")
        } catch (_: SecurityException) {
            toast("无法打开目录选择器")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != READ_LOCAL_FOLDERS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) chooseFolder()
        else toast("需要存储读取权限才能选择内部目录")
    }

    private fun fillAbout(content: LinearLayout) = with(content) {
        title("关于")
        addView(horizontal().apply {
            addView(ImageView(this@InkActivity).apply {
                setImageDrawable(applicationInfo.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(80), dp(80)))
            addView(column().apply {
                addView(label(getString(R.string.app_name), 42f, face = Typeface.create("serif", Typeface.NORMAL)).apply {
                    includeFontPadding = false
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                gap(4)
                addView(label("一个简单的墨水屏桌面启动器。", 14f, tone = SOFT_INK), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(18) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(24)
        aboutDetail("版本", installedVersion)
        aboutDetail("开发者", developer)
    }

    private fun LinearLayout.aboutDetail(name: String, value: String) {
        addView(horizontal().apply {
            minimumHeight = dp(44)
            setPadding(0, dp(5), 0, dp(5))
            addView(label(name, 15f, tone = SOFT_INK), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label(value, 16f, face = Typeface.DEFAULT_BOLD).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun fillSettings(content: LinearLayout) = with(content) {
        title("设置")
        section("显示页面", "")
        gap(4)
        addView(visibilityOption("首页", checked = true, enabled = false), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(visibilityOption("应用", prefs.showAppsTab) { checked ->
            prefs.showAppsTab = checked
            render(scrollY = body?.scrollY ?: 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(folderOption(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(visibilityOption("关于", prefs.showAboutTab) { checked ->
            prefs.showAboutTab = checked
            render(scrollY = body?.scrollY ?: 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(visibilityOption("设置", checked = true, enabled = false), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(20)
        section("展示方式", "")
        gap(4)
        layoutChoice("首页", "图标网格", prefs.homeGrid) { prefs.homeGrid = it }
        layoutChoice("自定义目录", "书架", prefs.folderShelf) {
            prefs.folderShelf = it
            folderPage = 0
        }
    }

    private fun LinearLayout.layoutChoice(name: String, alternative: String, useAlternative: Boolean, change: (Boolean) -> Unit) {
        addView(horizontal().apply {
            setPadding(0, dp(6), 0, dp(6))
            addView(label(name, 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            val edge = dp(1)
            val group = RadioGroup(this@InkActivity).apply {
                orientation = RadioGroup.HORIZONTAL
                setBaselineAligned(false)
                setBackgroundColor(LINE)
                setPadding(edge, edge, edge, edge)
            }
            val list = layoutButton(name, "列表")
            val other = layoutButton(name, alternative)
            group.addView(list, RadioGroup.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = edge })
            group.addView(other, RadioGroup.LayoutParams(0, MATCH_PARENT, 1f))
            group.check(if (useAlternative) other.id else list.id)
            group.setOnCheckedChangeListener { _, id -> if (id == list.id || id == other.id) change(id == other.id) }
            addView(group, LinearLayout.LayoutParams(0, dp(ACTION_HEIGHT_DP) + 2 * edge, 1.4f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun layoutButton(pageName: String, value: String) = RadioButton(this).apply {
        val checked = intArrayOf(android.R.attr.state_checked)
        val focused = intArrayOf(android.R.attr.state_focused)
        val pressed = intArrayOf(android.R.attr.state_pressed)
        id = View.generateViewId()
        text = value
        contentDescription = "$pageName，$value"
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        buttonDrawable = null
        minWidth = 0
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Color.WHITE, INK)))
        background = StateListDrawable().apply {
            addState(checked + pressed, paperShape(Color.TRANSPARENT, SOFT_INK))
            addState(checked + focused, paperShape(Color.TRANSPARENT, SOFT_INK))
            addState(checked, paperShape(Color.TRANSPARENT, INK))
            addState(pressed, paperShape(Color.TRANSPARENT, PRESS_FILL))
            addState(focused, paperShape(INK))
            addState(intArrayOf(), paperShape(Color.TRANSPARENT))
        }
    }

    private fun visibilityOption(name: String, checked: Boolean, enabled: Boolean = true, onToggle: ((Boolean) -> Unit)? = null) = horizontal().apply {
        val check = visibilityCheck(name, checked, enabled, onToggle)
        addVisibilityCheck(check)
        addView(visibilityTitle(name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (enabled) {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = pressBackground()
            setOnClickListener { check.performClick() }
        }
    }

    private fun visibilityCheck(name: String, checked: Boolean, enabled: Boolean, onToggle: ((Boolean) -> Unit)?) = CheckBox(this).apply {
        contentDescription = name
        buttonTintList = ColorStateList.valueOf(if (enabled) INK else DISABLED_INK)
        setPadding(dp(5), 0, 0, 0)
        minimumHeight = dp(ACTION_HEIGHT_DP)
        isChecked = checked
        isEnabled = enabled
        background = if (enabled) pressBackground() else null
        if (onToggle != null) setOnCheckedChangeListener { _, value -> onToggle(value) }
    }

    private fun LinearLayout.addVisibilityCheck(check: CheckBox) = addView(check, LinearLayout.LayoutParams(dp(ACTION_HEIGHT_DP), WRAP_CONTENT))

    private fun visibilityTitle(name: String) = label(name, 17f).apply {
        maxLines = 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun folderOption() = horizontal().apply {
        val folder = boundFolder
        addVisibilityCheck(visibilityCheck("显示自定义目录", checked = folder != null && prefs.showFolderTab, enabled = folder != null) { checked ->
            prefs.showFolderTab = checked
            render(scrollY = body?.scrollY ?: 0)
        })
        val name = folder?.name ?: "未选择"
        val choose = horizontal().apply {
            minimumHeight = dp(ACTION_HEIGHT_DP)
            contentDescription = "自定义目录，$name"
            addView(visibilityTitle("自定义目录"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f))
            addView(label(name, 14f, tone = SOFT_INK).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(InkGlyph(this@InkActivity, InkGlyph.Kind.RIGHT), LinearLayout.LayoutParams(dp(29), dp(30)))
            action { chooseFolder() }
        }
        addView(choose, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun LinearLayout.title(text: String, accessory: View? = null) {
        val heading = label(text, PAGE_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        if (accessory == null) {
            addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            addView(horizontal().apply {
                addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(accessory, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        gap(14)
    }

    private fun LinearLayout.section(left: String, right: String) {
        addView(horizontal().apply {
            addView(label(left, 14f, face = Typeface.DEFAULT_BOLD), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            if (right.isNotEmpty()) addView(caption(right), LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(33)))
        rule(INK)
    }

    private fun appIcon(item: AppEntry, large: Boolean = false): View {
        val glyph = when {
            item.stockHome -> InkGlyph.Kind.HOME
            item.isFileManager -> InkGlyph.Kind.FILE
            item.isWeRead -> InkGlyph.Kind.BOOK
            item.isAndroidSettings || item.isDeviceSettings -> InkGlyph.Kind.SETTINGS
            else -> null
        }
        val glyphSize = if (large) 33 else 22
        if (glyph != null) return InkGlyph(this, glyph, sizeDp = glyphSize)
        val icon = catalog.icon(item) ?: return InkGlyph(this, InkGlyph.Kind.APP, sizeDp = glyphSize)
        return ImageView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setImageDrawable(icon)
            colorFilter = grayscale
        }
    }

    private fun appRow(item: AppEntry, pinned: Boolean) =
        PaperRow(displayLabel(item), appIcon(item), InkGlyph.Kind.ARROW, showPin = page == Page.APPS && pinned).apply {
            appAction(item, pinned)
        }

    private fun View.appAction(item: AppEntry, pinned: Boolean, stroke: Int = Color.TRANSPARENT) {
        action(stroke) { openApp(item) }
        setOnLongClickListener { confirmPin(item); true }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK, if (pinned) "取消固定" else "固定到首页",
                ))
            }
        }
    }

    private fun displayLabel(item: AppEntry) = if (item.isDeviceSettings) "设备设置" else item.label

    private fun openApp(item: AppEntry) {
        if (!tryStart(item.launchIntent())) toast("无法打开 ${displayLabel(item)}")
    }

    private fun confirmPin(item: AppEntry) {
        if (pinDialog?.isShowing == true) return
        val pinned = prefs.pinned().filter { key -> apps.any { it.key == key } }
        val name = displayLabel(item)
        val isPinned = item.key in pinned
        val dialog = if (!isPinned && pinned.size >= MAX_PINS) {
            showPaperDialog("首页已满", "首页最多固定 $MAX_PINS 个应用。取消一个后，再固定「$name」。", "知道了")
        } else {
            val title = if (isPinned) "取消固定？" else "固定到首页？"
            val message = if (isPinned) "将「$name」从首页移除，应用仍然保留。" else "将「$name」添加到首页的常用应用。"
            showPaperDialog(title, message, if (isPinned) "取消固定" else "固定") {
                prefs.savePins(if (isPinned) pinned - item.key else pinned + item.key)
                render(scrollY = body?.scrollY ?: 0)
            }
        }
        pinDialog = dialog
        dialog.setOnDismissListener { if (pinDialog === dialog) pinDialog = null }
    }

    private inner class PaperRow(title: String, icon: View, trailing: InkGlyph.Kind?, showPin: Boolean = false) : LinearLayout(this@InkActivity) {
        val titleView = label(title, 18f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBaselineAligned(false)
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            minimumHeight = dp(ACTION_HEIGHT_DP)
            setPadding(dp(4), dp(5), dp(2), dp(5))
            contentDescription = if (showPin) "$title，已固定" else title
            addView(icon, LayoutParams(dp(29), dp(30)))
            addView(titleView, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(11) })
            if (showPin) addView(InkGlyph(this@InkActivity, InkGlyph.Kind.PIN), LayoutParams(dp(22), dp(24)))
            if (trailing != null) addView(InkGlyph(this@InkActivity, trailing), LayoutParams(dp(29), dp(30)))
        }
    }

    private fun pageButton(text: String, glyph: InkGlyph.Kind, enabled: Boolean, click: () -> Unit) = horizontal().apply {
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)
        val leading = glyph == InkGlyph.Kind.LEFT
        val tone = if (enabled) INK else DISABLED_INK
        gravity = (if (leading) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        minimumWidth = dp(88)
        setPadding(dp(3), 0, dp(3), 0)
        contentDescription = text
        val title = label(text, 15f, tone = tone).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val icon = InkGlyph(this@InkActivity, glyph, sizeDp = 20, tone = tone)
        if (leading) addView(icon, LinearLayout.LayoutParams(dp(23), dp(24)))
        addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        if (!leading) addView(icon, LinearLayout.LayoutParams(dp(23), dp(24)))
        isEnabled = enabled
        if (enabled) action(click = click)
        buttonRole()
    }

    private fun View.buttonRole() {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }

    private fun footer(pages: List<Page>) = column().apply {
        setPadding(dp(PAGE_INSET_DP), 0, dp(PAGE_INSET_DP), dp(4))
        rule(INK)
        val tabs = horizontal()
        pages.forEach { item ->
            val selected = page == item
            val destination = if (item == Page.FOLDER) boundFolder?.name ?: item.title else item.title
            val cell = column().apply {
                contentDescription = destination
                isSelected = selected
                action { show(item) }
                addView(View(this@InkActivity).apply {
                    setBackgroundColor(if (selected) INK else Color.WHITE)
                }, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
                addView(label(destination, 14f, face = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(3), 0, dp(3), 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            tabs.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, dp(49)))
    }

    private fun isDefaultHome(): Boolean {
        @Suppress("DEPRECATION")
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )
        return home?.activityInfo?.packageName == packageName
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBaselineAligned(false)
    }

    private fun label(value: String, size: Float, face: Typeface = Typeface.DEFAULT, tone: Int = INK) = TextView(this).apply {
        text = value
        textSize = size
        typeface = face
        setTextColor(tone)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private fun caption(value: String) = label(value, 11f, face = Typeface.MONOSPACE, tone = SOFT_INK).apply {
        letterSpacing = 0.045f
        maxLines = 1
    }

    private fun LinearLayout.rule(color: Int = LINE) = addView(paperRule(color), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))

    private fun LinearLayout.gap(height: Int) = addView(View(this@InkActivity), LinearLayout.LayoutParams(1, dp(height)))
    private fun View.action(stroke: Int = Color.TRANSPARENT, click: () -> Unit) {
        isFocusable = true
        background = pressBackground(stroke)
        setOnClickListener { click() }
    }
}
