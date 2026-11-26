package com.locusable.ginkgo

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class InkActivity : Activity() {
    private enum class Page(val title: String) {
        HOME("首页"), APPS("应用"), SETTINGS("设置"), SHELF("书架"), ABOUT("关于"),
    }

    private companion object {
        const val READ_LOCAL_BOOKS = 111
        const val HOME_COLUMNS = 4
        const val SHELF_COLUMNS = 4
    }

    private val prefs by lazy { InkPreferences(this) }
    private val catalog by lazy { AppCatalog(this) }
    private val folderWorker = Executors.newSingleThreadExecutor()
    private val covers by lazy { ShelfCovers(applicationContext) }
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private val developer get() = getString(R.string.app_developer)
    @Suppress("DEPRECATION")
    private val installedVersion by lazy {
        val info = packageManager.getPackageInfo(packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName.orEmpty()}($build)"
    }
    private var apps = emptyList<AppEntry>()
    private val visiblePages get() = buildList {
        add(Page.HOME)
        if (prefs.showAppsTab) add(Page.APPS)
        if (prefs.showShelfTab) add(Page.SHELF)
        if (prefs.showAboutTab) add(Page.ABOUT)
        add(Page.SETTINGS)
    }
    private var page = Page.HOME
    private var renderedPages = emptyList<Page>()
    private var appPage = 0
    private var shelfPage = 0
    private var folderTrail = emptyList<FolderLocation>()
    private var folderContents: FolderContents = FolderContents.Loading
    private var folderRequest = 0
    private val weRead by lazy { WeReadController.get(this) }
    private val weReadCovers = lazy { WeReadCovers.get(applicationContext) }
    private val weReadCoverOwner = Any()
    private var weReadGroup: String? = null
    private var observedSnapshot: WeReadSnapshot? = null
    private var observedWeReadLoading = true
    private var loginDialog: Dialog? = null
    private var resumed = false
    private var observingWeRead = false
    private var recentLocalBooks = emptyList<FolderItem>()
    private var recentLocalRequest = 0
    private var renderedRecentBooks = emptyList<ShelfEntry>()
    private val weReadChanged: () -> Unit = {
        val current = weRead.snapshot
        val previous = observedSnapshot
        observedSnapshot = current
        val loading = weRead.state == WeReadController.State.LOADING
        val loaded = observedWeReadLoading && !loading
        observedWeReadLoading = loading
        if (previous?.account != current.account && weReadGroup != null) {
            weReadGroup = null
            shelfPage = 0
        }
        if (previous?.shelf != current.shelf || previous?.session != current.session || (page == Page.SHELF && loaded)) {
            render(scrollY = body?.scrollY ?: 0)
        }
        loginDialog?.let(::updateWeReadLoginDialog)
    }
    private var pendingBook: FolderItem? = null
    private var shelfOrderDirty = false
    private val mosaicRevisions = mutableMapOf<String, Int>()
    private var pinDialog: Dialog? = null
    private var body: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val tab = savedInstanceState?.getInt("page", 0) ?: 0
        page = Page.entries.getOrElse(tab) { Page.HOME }.takeIf { it in visiblePages } ?: Page.HOME
        appPage = savedInstanceState?.getInt("app_page", 0) ?: 0
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refresh()
        observeWeRead()
        if (page == Page.HOME) loadRecentLocalBooks()
        refreshShelf()
    }

    override fun onDestroy() {
        pinDialog?.dismiss()
        loginDialog?.dismiss()
        folderRequest++
        recentLocalRequest++
        folderWorker.shutdownNow()
        covers.close()
        if (weReadCovers.isInitialized()) weReadCovers.value.hide(weReadCoverOwner)
        super.onDestroy()
    }

    override fun onPause() {
        pendingBook = null
        resumed = false
        loginDialog?.dismiss()
        observeWeRead()
        if (weReadCovers.isInitialized()) weReadCovers.value.hide(weReadCoverOwner)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            pinDialog?.dismiss()
            loginDialog?.dismiss()
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
            page == Page.SHELF && folderTrail.isNotEmpty() -> navigateFolder(folderTrail.dropLast(1))
            page == Page.SHELF && weReadGroup != null -> navigateWeRead(null)
            page != Page.HOME -> show(Page.HOME)
            !isDefaultHome() -> super.onBackPressed()
        }
    }

    private fun refresh() {
        val current = catalog.apps()
        prefs.seedPins(current)
        val storedPins = prefs.pinned()
        if (storedPins.size > MAX_PINS) {
            val available = current.mapTo(HashSet()) { it.key }
            prefs.savePins(storedPins.filter { it in available })
        }
        // Xiaomi may retain a HOME task and a separate launcher-entry task in the same process.
        if (body == null || renderedPages != visiblePages || apps != current || page == Page.SETTINGS ||
            (page == Page.SHELF && shelfOrderDirty) || (page == Page.HOME && renderedRecentBooks != homeBooks())) {
            apps = current
            render(scrollY = body?.scrollY ?: 0)
        }
    }

    private fun show(next: Page) {
        if (next !in visiblePages) return
        val changed = page != next
        if (changed && next == Page.APPS) appPage = 0
        if (changed) pendingBook = null
        page = next
        render()
        if (next == Page.HOME) loadRecentLocalBooks()
        observeWeRead()
        refreshShelf()
    }

    private fun observeWeRead() {
        val observe = resumed && page in setOf(Page.HOME, Page.SHELF, Page.SETTINGS)
        if (observe == observingWeRead) return
        observingWeRead = observe
        if (observe) weRead.observe(weReadChanged) else weRead.unobserve(weReadChanged)
    }

    private fun refreshShelf() {
        if (page != Page.SHELF || !resumed || isDestroyed) return
        if (weReadGroup == null) reloadFolder()
        weRead.whenLoaded {
            if (page == Page.SHELF && resumed && !isDestroyed) weRead.sync()
        }
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
        val withBooks = page == Page.SHELF || page == Page.HOME
        val sort = if (page == Page.SHELF) prefs.shelfSort else FolderSort.NAME
        val opened = if (withBooks) prefs.folderOpenTimes() else emptyMap()
        val onlineOpened = if (withBooks) prefs.weReadOpenTimes(weRead.snapshot.account) else emptyMap()
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val content = column().apply { setPadding(0, dp(18), 0, dp(12)) }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val coverTargets = mutableListOf<ShelfCovers.Target>()
        val onlineCoverTargets = mutableListOf<WeReadCovers.Target>()
        when (page) {
            Page.HOME -> fillHome(content, coverTargets, onlineCoverTargets, opened, onlineOpened)
            Page.APPS -> fillApps(content, scroll)
            Page.SHELF -> {
                shelfOrderDirty = false
                panel.rule()
                val pager = FrameLayout(this)
                panel.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, dp(ACTION_HEIGHT_DP)))
                fillShelf(content, scroll, pager, coverTargets, onlineCoverTargets, sort, opened, onlineOpened)
            }
            Page.ABOUT -> fillAbout(content)
            Page.SETTINGS -> fillSettings(content)
        }
        root.addView(footer(pages), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        renderedPages = pages
        covers.show(coverTargets, sort, opened) { location ->
            mosaicRevisions[location.id] ?: 0
        }
        if (onlineCoverTargets.isNotEmpty() && resumed) {
            weReadCovers.value.show(weReadCoverOwner, weRead.snapshot.account, weRead.snapshot.session, onlineCoverTargets)
        } else if (weReadCovers.isInitialized()) {
            weReadCovers.value.hide(weReadCoverOwner)
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

    private fun fillHome(
        content: LinearLayout, localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
        opened: Map<String, Long>, onlineOpened: Map<String, Long>,
    ) = with(content) {
        addView(wordmark(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(25)
        val pins = prefs.pinned().mapNotNull { key -> apps.find { it.key == key } }.take(MAX_PINS)
        val grid = prefs.homeGrid
        section("常用应用", "${pins.size} / $MAX_PINS")
        if (pins.isEmpty()) {
            addView(label("还没有常用应用", 15f, tone = SOFT_INK), LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        } else if (grid) {
            tileRows(pins, columns = HOME_COLUMNS, rowHeight = 112, tile = ::appTile)
        } else pins.forEach { item -> addView(appRow(item, pinned = true)) }

        val books = homeBooks(opened, onlineOpened)
        renderedRecentBooks = books
        if (books.isNotEmpty()) {
            gap(20)
            section("最近打开", "")
            if (grid) tileRows(books, columns = HOME_COLUMNS) {
                shelfTile(it, localCovers, onlineCovers, FolderSort.NAME, onlineOpened)
            } else books.forEach { addView(shelfListRow(it)) }
        }
    }

    private fun homeBooks(
        local: Map<String, Long> = prefs.folderOpenTimes(),
        online: Map<String, Long> = prefs.weReadOpenTimes(weRead.snapshot.account),
    ) = recentShelfBooks(recentLocalBooks, weRead.snapshot.shelf?.books.orEmpty(), local, online, MAX_RECENT_BOOKS)

    private fun loadRecentLocalBooks() {
        val request = ++recentLocalRequest
        val opened = if (Build.VERSION.SDK_INT <= 28 && hasBooksPermission()) prefs.folderOpenTimes() else emptyMap()
        folderWorker.execute {
            val recent = FolderAccess.recentBooks(opened, MAX_RECENT_BOOKS)
            runOnUiThread {
                if (request != recentLocalRequest || isDestroyed) return@runOnUiThread
                recentLocalBooks = recent
                if (page == Page.HOME && renderedRecentBooks != homeBooks(opened)) render()
            }
        }
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

    private fun fillShelf(
        content: LinearLayout, scroll: ScrollView, pager: FrameLayout,
        localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
        sort: FolderSort, opened: Map<String, Long>, onlineOpened: Map<String, Long>,
    ) = with(content) {
        val trail = folderTrail
        val cloudShelf = weRead.snapshot.shelf
        val group = cloudShelf?.groups?.firstOrNull { it.id == weReadGroup }
        if (weReadGroup != null && group == null) {
            weReadGroup = null
            shelfPage = 0
        }
        val folder = trail.lastOrNull()
        val back = when {
            group != null -> shelfBackButton("返回书架") { navigateWeRead(null) }
            folder != null -> shelfBackButton(if (trail.size == 1) "返回书架" else "返回上一级") {
                navigateFolder(trail.dropLast(1))
            }
            else -> null
        }
        val sorting = sortButton(sort) {
            prefs.shelfSort = it
            shelfPage = 0
            render()
        }
        title(group?.name ?: folder?.name ?: Page.SHELF.title, accessory = sorting, leading = back)

        val local = folderContents
        val entries = buildList {
            if (group == null) {
                (local as? FolderContents.Items)?.entries?.forEach { add(ShelfEntry.Local(it)) }
            }
            if (folder == null) {
                (group?.books ?: cloudShelf?.root).orEmpty().forEach { add(ShelfEntry.Online(it)) }
            }
        }.orderedOnShelf(sort, opened, onlineOpened)
        if (group == null && local == FolderContents.PermissionRequired) {
            addView(this@InkActivity.paperButton("允许读取本地图书", primary = false) { requestBooksPermission() },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(ACTION_HEIGHT_DP)))
        }
        if (entries.isEmpty()) {
            val message = when {
                group != null -> "这个文件夹没有书籍"
                local == FolderContents.Loading || (folder == null && weRead.state == WeReadController.State.LOADING) -> "正在读取书架…"
                local == FolderContents.PermissionRequired -> null
                local == FolderContents.Unavailable && folder != null -> "无法读取此目录"
                folder != null -> "这个目录没有书籍"
                else -> "书架上还没有书籍"
            }
            message?.let { folderNotice(it) }
            if (local != FolderContents.Loading && weRead.state != WeReadController.State.LOADING) shelfPage = 0
            updatePager(pager, 0, 1) {}
            return@with
        }
        section(if (folder == null && group == null) "全部" else "目录内容", "${entries.size} 项")
        if (prefs.shelfGrid) {
            val pageSize = SHELF_COLUMNS * 2
            val total = (entries.size - 1) / pageSize + 1
            shelfPage = shelfPage.coerceIn(0, total - 1)
            val start = shelfPage * pageSize
            tileRows(entries.subList(start, minOf(start + pageSize, entries.size)), SHELF_COLUMNS) {
                shelfTile(it, localCovers, onlineCovers, sort, onlineOpened)
            }
            updatePager(pager, shelfPage, total) { shelfPage += it; render() }
        } else {
            showPagedList(content, scroll, addRows(), pager, entries, shelfPage, alwaysShowPager = true,
                title = ShelfEntry::name, singleLine = true, onPage = { shelfPage = it }, makeRow = ::shelfListRow)
        }
    }

    private fun navigateWeRead(group: String?) {
        pendingBook = null
        weReadGroup = group
        shelfPage = 0
        render()
        if (group == null) refreshShelf()
    }

    private fun openWeReadBook(book: WeReadEntry.Book) {
        val link = Uri.Builder().scheme("weread").authority("reading").appendQueryParameter("bId", book.id).build()
        val app = "com.tencent.weread.eink"
        if (!tryStart(Intent(Intent.ACTION_VIEW, link).setPackage(app))) {
            val web = book.readerLink?.let(Uri::parse)?.takeIf {
                it.scheme == "https" && it.encodedAuthority == "weread.qq.com" && it.path.orEmpty().startsWith("/web/reader/")
            }
            if (web == null || !tryStart(Intent(Intent.ACTION_VIEW, web).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app))) {
                toast("未找到支持续读链接的微信读书墨水屏版")
                return
            }
        }
        weRead.snapshot.account?.let { prefs.recordWeReadOpen(it, book.id) }
        shelfOrderDirty = prefs.shelfSort == FolderSort.LAST_OPENED
    }

    private fun updatePager(pager: FrameLayout, index: Int, total: Int, move: (Int) -> Unit) {
        pager.removeAllViews()
        pager.addView(paginationRow(index, total, move), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun LinearLayout.addRows() = column().also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    private fun <T> showPagedList(
        content: LinearLayout, scroll: ScrollView, rows: LinearLayout, pager: FrameLayout,
        entries: List<T>, initialPage: Int, alwaysShowPager: Boolean, trailingHeight: () -> Int = { 0 },
        title: (T) -> String, hasPin: (T) -> Boolean = { false }, singleLine: Boolean = false,
        onPage: (Int) -> Unit, makeRow: (T) -> View,
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
            val heights = measureRows(entries, width, title, hasPin, singleLine)
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

    private fun <T> measureRows(
        entries: List<T>, width: Int, title: (T) -> String, hasPin: (T) -> Boolean, singleLine: Boolean,
    ): IntArray {
        fun sample(pin: Boolean) = PaperRow("", InkGlyph(this, InkGlyph.Kind.DOCUMENT), InkGlyph.Kind.ARROW,
            showPin = pin, singleLine = singleLine)
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

    private fun shelfListRow(entry: ShelfEntry): View = shelfListRow(entry.name, entry.directory) { openShelfEntry(entry) }.apply {
        if (entry is ShelfEntry.Online) contentDescription = "${entry.name}，微信读书"
    }

    private fun openShelfEntry(entry: ShelfEntry) {
        when (entry) {
            is ShelfEntry.Local -> openFolderItem(entry.item)
            is ShelfEntry.Online -> when (val item = entry.entry) {
                is WeReadEntry.Book -> openWeReadBook(item)
                is WeReadEntry.Group -> navigateWeRead(item.id)
            }
        }
    }

    private fun shelfListRow(name: String, directory: Boolean, open: () -> Unit): View {
        val icon = if (directory) InkGlyph.Kind.FILE else InkGlyph.Kind.DOCUMENT
        val arrow = if (directory) InkGlyph.Kind.RIGHT else InkGlyph.Kind.ARROW
        return PaperRow(name, InkGlyph(this, icon), arrow, singleLine = true).apply { action(click = open) }
    }

    private fun openFolderItem(item: FolderItem) {
        if (item.isDirectory) {
            prefs.recordFolderOpen(item.location)
            navigateFolder(folderTrail + item.location)
            return
        }
        if (pendingBook != null) return
        pendingBook = item
        folderWorker.execute {
            val bookId = FolderAccess.book(item.location)?.let(DuokanBookLink::idFor)
            runOnUiThread {
                if (isDestroyed || pendingBook !== item) return@runOnUiThread
                pendingBook = null
                if (bookId == null) {
                    toast("无法读取这本书")
                    return@runOnUiThread
                }
                if (!tryStart(DuokanBookLink.intent(bookId))) {
                    toast("未找到支持续读链接的多看阅读器")
                    return@runOnUiThread
                }
                prefs.recordFolderOpen(item.location)
                File(item.location.id).parent?.let { id -> mosaicRevisions[id] = (mosaicRevisions[id] ?: 0) + 1 }
                shelfOrderDirty = prefs.shelfSort == FolderSort.LAST_OPENED
            }
        }
    }

    private fun shelfBackButton(description: String, goBack: () -> Unit) = InkGlyph(this, InkGlyph.Kind.LEFT, sizeDp = 20).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ACTION_HEIGHT_DP), dp(ACTION_HEIGHT_DP))
        contentDescription = description
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        buttonRole()
        action(click = goBack)
    }

    private fun sortButton(sort: FolderSort, change: (FolderSort) -> Unit) = horizontal().apply {
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
        action { change(next) }
    }

    private fun shelfTile(
        entry: ShelfEntry, localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
        sort: FolderSort, onlineOpened: Map<String, Long>,
    ): View = when (entry) {
        is ShelfEntry.Local -> localShelfTile(entry.item, localCovers)
        is ShelfEntry.Online -> coverTile(entry.name, entry.directory, online = true, bind = { cover ->
            when (val item = entry.entry) {
                is WeReadEntry.Book -> onlineCovers.add(WeReadCovers.Target(item) { cover.showCoverBitmap(it) })
                is WeReadEntry.Group -> {
                    val books = item.books.map(ShelfEntry::Online).orderedOnShelf(sort, emptyMap(), onlineOpened).take(4)
                        .map { it.entry as WeReadEntry.Book }
                    val faces = books.map { ShelfCovers.Face(it.name, null) }.toMutableList()
                    if (faces.isNotEmpty()) cover.showCoverMosaic(faces, fileNames = false)
                    for ((index, book) in books.withIndex()) {
                        onlineCovers.add(WeReadCovers.Target(book) { bitmap ->
                            faces[index] = ShelfCovers.Face(book.name, bitmap)
                            cover.showCoverMosaic(faces, fileNames = false)
                        })
                    }
                }
            }
        }) { openShelfEntry(entry) }
    }

    private fun localShelfTile(item: FolderItem, coverTargets: MutableList<ShelfCovers.Target>): View {
        val name = item.location.name
        val stem = name.substringBeforeLast('.')
        val extension = item.extension
        val hasSuffix = extension.length in 1..8 && extension.all { it in 'a'..'z' || it in '0'..'9' }
        val displayName = if (!item.isDirectory && stem.isNotBlank() && hasSuffix) stem else name
        return coverTile(displayName, item.isDirectory, bind = { cover ->
            if (item.isDirectory) {
                coverTargets.add(ShelfCovers.Target.Directory(item.location) { faces ->
                    if (faces.isEmpty()) cover.showCoverTitle(displayName) else cover.showCoverMosaic(faces)
                })
            } else if (item.coverFormat != null) {
                coverTargets.add(ShelfCovers.Target.File(item) { cover.showCoverBitmap(it) })
            }
        }) { openFolderItem(item) }
    }

    private fun coverTile(
        name: String, directory: Boolean, online: Boolean = false, bind: (FrameLayout) -> Unit, open: () -> Unit,
    ) = column().apply {
        contentDescription = buildString {
            if (directory) append("目录，")
            append(name)
            if (online) append("，微信读书")
        }
        val artwork = FrameLayout(this@InkActivity).apply { showCoverTitle(name) }
        val cover = CoverFrame().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(artwork, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (online) addView(label("微信读书", 10f, face = Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                background = paperShape(SOFT_INK)
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(4)
                bottomMargin = dp(4)
            })
        }
        bind(artwork)
        addView(cover, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(5)
        addView(horizontal().apply {
            gravity = Gravity.TOP
            if (directory) {
                addView(InkGlyph(this@InkActivity, InkGlyph.Kind.FILE, sizeDp = 16, tone = SOFT_INK),
                    LinearLayout.LayoutParams(dp(17), dp(20)).apply { marginEnd = dp(4) })
            }
            addView(label(name, 14f, face = if (directory) Typeface.DEFAULT_BOLD else Typeface.DEFAULT).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        action(click = open)
    }

    private inner class CoverFrame : FrameLayout(this@InkActivity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = (width * 3 + 1) / 2
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private fun FrameLayout.showCoverTitle(name: String, compact: Boolean = false) {
        removeAllViews()
        background = paperShape(0xFFC9C9C9.toInt(), 0xFFF7F7F7.toInt())
        val inset = dp(if (compact) 4 else 9)
        addView(column().apply {
            setPaddingRelative(inset, dp(if (compact) 7 else 14), inset, 0)
            if (!compact) {
                addView(paperRule(0xFF8A8A8A.toInt()), LinearLayout.LayoutParams(dp(14), dp(1)))
                gap(6)
            }
            addView(label(bookTitlePreview(name), if (compact) 9f else 12f,
                face = Typeface.create("serif", Typeface.NORMAL), tone = SOFT_INK).apply {
                gravity = Gravity.TOP or Gravity.START
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(if (compact) 1 else 2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
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

    private fun FrameLayout.showCoverMosaic(faces: List<ShelfCovers.Face>, fileNames: Boolean = true) {
        removeAllViews()
        setBackgroundColor(0xFFE6E6E6.toInt())
        addView(column().apply {
            repeat(2) { line ->
                addView(horizontal().apply {
                    repeat(2) { column ->
                        val panel = FrameLayout(this@InkActivity)
                        faces.getOrNull(line * 2 + column)?.let { face ->
                            if (face.bitmap != null) panel.showCoverBitmap(face.bitmap)
                            else panel.showCoverTitle(if (fileNames) face.name.substringBeforeLast('.') else face.name, compact = true)
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
        pendingBook = null
        weReadGroup = null
        folderTrail = trail
        shelfPage = 0
        folderContents = FolderContents.Loading
        render()
        if (trail.isEmpty()) refreshShelf() else reloadFolder()
    }

    private fun reloadFolder() {
        val location = folderTrail.lastOrNull()
        val request = ++folderRequest
        val gate = when {
            Build.VERSION.SDK_INT > 28 -> FolderContents.Unsupported
            !hasBooksPermission() -> FolderContents.PermissionRequired
            else -> null
        }
        if (gate != null) {
            updateFolder(gate)
            return
        }
        folderWorker.execute {
            val result = FolderAccess.contents(location)
            runOnUiThread {
                if (request == folderRequest && !isDestroyed) updateFolder(result)
            }
        }
    }

    private fun updateFolder(result: FolderContents) {
        if (result == folderContents) return
        folderContents = result
        if (page == Page.SHELF && weReadGroup == null) render(scrollY = body?.scrollY ?: 0)
    }

    @Suppress("DEPRECATION")
    private fun hasBooksPermission() = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun requestBooksPermission() {
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_LOCAL_BOOKS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != READ_LOCAL_BOOKS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) reloadFolder()
        else toast("可在应用权限中允许 Ginkgo 读取存储")
    }

    private fun fillAbout(content: LinearLayout) = with(content) {
        gravity = Gravity.CENTER_HORIZONTAL
        val serif = Typeface.create("serif", Typeface.NORMAL)
        fun whitespace(minimumDp: Int, share: Float) {
            addView(View(this@InkActivity), LinearLayout.LayoutParams(1, dp(minimumDp), share))
        }

        fun imprintLabel(resource: Int) = label(getString(resource), 11f, tone = SOFT_INK).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
        }

        whitespace(54, 1.5f)
        addView(ImageView(this@InkActivity).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        gap(8)
        addView(label(getString(R.string.app_name), 68f, face = serif).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(14)
        addView(label(getString(R.string.about_description), 15f, tone = SOFT_INK).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        whitespace(34, 1.25f)
        addView(paperRule(LINE), LinearLayout.LayoutParams(dp(36), dp(1)))
        gap(14)
        addView(imprintLabel(R.string.about_credit), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        gap(2)
        addView(label(developer, 22f, face = serif).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(12)
        addView(horizontal().apply {
            setBaselineAligned(true)
            addView(imprintLabel(R.string.about_version))
            addView(label(installedVersion, 12f, face = Typeface.MONOSPACE, tone = SOFT_INK),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        whitespace(26, 1f)
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
        addView(visibilityOption(Page.SHELF.title, prefs.showShelfTab) { checked ->
            prefs.showShelfTab = checked
            render(scrollY = body?.scrollY ?: 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(visibilityOption("关于", prefs.showAboutTab) { checked ->
            prefs.showAboutTab = checked
            render(scrollY = body?.scrollY ?: 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(visibilityOption("设置", checked = true, enabled = false), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(20)
        section("展示方式", "")
        gap(4)
        layoutChoice("首页", "图标", prefs.homeGrid) { prefs.homeGrid = it }
        layoutChoice(Page.SHELF.title, "封面", prefs.shelfGrid) {
            prefs.shelfGrid = it
            shelfPage = 0
        }
        gap(20)
        section("账号", "")
        folderRow("微信读书", InkGlyph.Kind.BOOK, InkGlyph.Kind.RIGHT, ::manageWeReadAccount)
    }

    private fun manageWeReadAccount() {
        weRead.whenLoaded {
            if (!resumed || page != Page.SETTINGS || isDestroyed || loginDialog != null) return@whenLoaded
            if (weRead.snapshot.session != null) confirmWeReadForget(signedIn = true)
            else if (weRead.state == WeReadController.State.IDLE) showWeReadLoginDialog()
        }
    }

    private fun confirmWeReadForget(signedIn: Boolean) {
        if (weRead.state != WeReadController.State.IDLE) return
        showPaperDialog(if (signedIn) "退出微信读书？" else "清除微信读书缓存？",
            "仅清除 Ginkgo 中的登录、书架缓存和打开记录，不影响微信读书。",
            if (signedIn) "退出登录" else "清除缓存") { weRead.forget() }
    }

    @Suppress("DEPRECATION") // Resize for OTP input on the target Android 8.1 reader.
    private fun showWeReadLoginDialog() {
        val dialog = paperDialog(secure = true)
        loginDialog = dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.setOnDismissListener {
            if (loginDialog === dialog) {
                loginDialog = null
                weRead.stopLogin()
            }
        }
        // beginLogin notifies the dialog immediately; later callbacks replace only its contents.
        weRead.beginLogin()
        presentPaperDialog(dialog)
    }

    private fun updateWeReadLoginDialog(dialog: Dialog) {
        if (weRead.snapshot.session != null) {
            dialog.dismiss()
            return
        }
        val panel = column().apply {
            setPadding(dp(PAGE_INSET_DP), dp(20), dp(PAGE_INSET_DP), dp(20))
            background = paperShape(INK)
            gravity = Gravity.CENTER_HORIZONTAL
            val state = weRead.state
            addView(label(if (state == WeReadController.State.VERIFY) "验证微信读书登录" else "微信读书扫码登录",
                20f, face = Typeface.DEFAULT_BOLD).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            gap(12)
            fun message(text: String) {
                addView(label(text, 14f, tone = SOFT_INK).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            var submit: (() -> Unit)? = null
            var button = "确认"
            when (state) {
                WeReadController.State.SCAN -> {
                    message("使用手机微信扫一扫，并确认登录")
                    weRead.qr?.let { addView(wechatQr(it), LinearLayout.LayoutParams(MATCH_PARENT, dp(222))) }
                }
                WeReadController.State.VERIFY -> {
                    message(when (weRead.notice) {
                        WeReadController.Notice.BAD_CODE -> "验证码不正确，请重试。"
                        WeReadController.Notice.LOGIN_FAILED -> "暂时无法验证，请重试。"
                        else -> "输入微信读书登录页面显示的 4 位验证码。"
                    })
                    gap(12)
                    val code = EditText(this@InkActivity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        filters = arrayOf(InputFilter.LengthFilter(4))
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        isSaveEnabled = false
                        hint = "4 位验证码"
                        setSingleLine()
                    }
                    addView(code, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    submit = {
                        val value = code.text.toString()
                        if (value.length == 4) weRead.submitCode(value) else toast("请输入 4 位验证码")
                    }
                }
                WeReadController.State.IDLE -> {
                    message(if (weRead.notice == WeReadController.Notice.LOGIN_TIMEOUT) "二维码已过期。"
                        else "未能获取登录二维码，请重试。")
                    button = "重新扫码"
                    submit = { weRead.beginLogin() }
                }
                WeReadController.State.SAVING -> message("正在完成登录…")
                else -> message("正在获取二维码…")
            }
            gap(12)
            addView(horizontal().apply {
                addView(this@InkActivity.paperButton("取消", primary = false) { dialog.dismiss() })
                submit?.let { confirm ->
                    addView(this@InkActivity.paperButton(button, primary = false, click = confirm),
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
                }
            })
            // An expired session can be renewed by scanning or cleared explicitly without adding a settings row.
            if (weRead.snapshot.account != null && state != WeReadController.State.SAVING) {
                gap(6)
                addView(this@InkActivity.paperButton("清除本机缓存", primary = false) {
                    if (loginDialog === dialog) {
                        loginDialog = null
                        dialog.dismiss()
                        weRead.stopLogin()
                        confirmWeReadForget(signedIn = false)
                    }
                })
            }
        }
        dialog.setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel)
        })
    }

    private fun wechatQr(modules: Array<BooleanArray>): View = object : View(this) {
        private val paint = Paint().apply { color = Color.BLACK }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val step = minOf(width, height) / (modules.size + 8)
            val edge = modules.size * step
            val x = (width - edge) / 2
            val y = (height - edge) / 2
            for (row in modules.indices) for (column in modules[row].indices) {
                if (modules[row][column]) canvas.drawRect((x + column * step).toFloat(),
                    (y + row * step).toFloat(), (x + (column + 1) * step).toFloat(),
                    (y + (row + 1) * step).toFloat(), paint)
            }
        }
    }.apply { contentDescription = "微信读书登录二维码" }

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

    private fun LinearLayout.title(text: String, accessory: View? = null, leading: View? = null) {
        val heading = label(text, PAGE_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply {
            if (leading == null) maxLines = 2 else setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        if (accessory == null && leading == null) {
            addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            addView(horizontal().apply {
                leading?.let { addView(it) }
                addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                accessory?.let { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }) }
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

    private inner class PaperRow(
        title: String, icon: View, trailing: InkGlyph.Kind?, showPin: Boolean = false, singleLine: Boolean = false,
    ) : LinearLayout(this@InkActivity) {
        val titleView = label(title, 18f).apply {
            if (singleLine) setSingleLine(true) else maxLines = 2
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
            val destination = item.title
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
