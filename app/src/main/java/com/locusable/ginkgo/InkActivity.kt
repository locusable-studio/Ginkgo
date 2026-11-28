package com.locusable.ginkgo

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.locusable.ginkgo.launcher.AppCatalog
import com.locusable.ginkgo.launcher.AppEntry
import com.locusable.ginkgo.launcher.LauncherPage
import com.locusable.ginkgo.settings.InkPreferences
import com.locusable.ginkgo.settings.ShelfSources
import com.locusable.ginkgo.shelf.HiddenBooks
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.ShelfSort
import com.locusable.ginkgo.shelf.cover.ShelfCovers
import com.locusable.ginkgo.shelf.displayTitle
import com.locusable.ginkgo.shelf.local.CoverFormat
import com.locusable.ginkgo.shelf.local.DuokanBookLink
import com.locusable.ginkgo.shelf.local.FolderAccess
import com.locusable.ginkgo.shelf.local.FolderContents
import com.locusable.ginkgo.shelf.local.FolderItem
import com.locusable.ginkgo.shelf.local.FolderLocation
import com.locusable.ginkgo.shelf.orderedOnShelf
import com.locusable.ginkgo.shelf.recentShelfBooks
import com.locusable.ginkgo.shelf.weread.WeReadBookLink
import com.locusable.ginkgo.shelf.weread.WeReadController
import com.locusable.ginkgo.shelf.weread.WeReadCovers
import com.locusable.ginkgo.shelf.weread.WeReadEntry
import com.locusable.ginkgo.shelf.weread.WeReadOpenHistory
import com.locusable.ginkgo.shelf.weread.WeReadSnapshot
import com.locusable.ginkgo.ui.ACTION_HEIGHT_DP
import com.locusable.ginkgo.ui.ACTION_TEXT_SP
import com.locusable.ginkgo.ui.DIALOG_TITLE_SP
import com.locusable.ginkgo.ui.HEADING_GAP_DP
import com.locusable.ginkgo.ui.INK
import com.locusable.ginkgo.ui.InkGlyph
import com.locusable.ginkgo.ui.LABEL_TEXT_SP
import com.locusable.ginkgo.ui.LINE
import com.locusable.ginkgo.ui.PAGE_BOTTOM_DP
import com.locusable.ginkgo.ui.PAGE_INSET_DP
import com.locusable.ginkgo.ui.PAGE_TOP_DP
import com.locusable.ginkgo.ui.PRESS_FILL
import com.locusable.ginkgo.ui.ROW_TEXT_SP
import com.locusable.ginkgo.ui.SECTION_GAP_DP
import com.locusable.ginkgo.ui.SOFT_INK
import com.locusable.ginkgo.ui.TILE_COLUMN_GAP_DP
import com.locusable.ginkgo.ui.TileScroll
import com.locusable.ginkgo.ui.action
import com.locusable.ginkgo.ui.bookAuthorLabel
import com.locusable.ginkgo.ui.bookTitlePreview
import com.locusable.ginkgo.ui.buttonRole
import com.locusable.ginkgo.ui.column
import com.locusable.ginkgo.ui.dp
import com.locusable.ginkgo.ui.gap
import com.locusable.ginkgo.ui.horizontal
import com.locusable.ginkgo.ui.label
import com.locusable.ginkgo.ui.page.APP_COLUMNS
import com.locusable.ginkgo.ui.page.HOME_RECENT_BOOK_LIMIT
import com.locusable.ginkgo.ui.page.SettingsPage
import com.locusable.ginkgo.ui.page.ShelfFolderWindow
import com.locusable.ginkgo.ui.page.ShelfPageModel
import com.locusable.ginkgo.ui.page.ShelfRowCovers
import com.locusable.ginkgo.ui.page.showAbout
import com.locusable.ginkgo.ui.page.showApps
import com.locusable.ginkgo.ui.page.showHome
import com.locusable.ginkgo.ui.page.showShelf
import com.locusable.ginkgo.ui.paperButton
import com.locusable.ginkgo.ui.paperDialog
import com.locusable.ginkgo.ui.paperDialogPanel
import com.locusable.ginkgo.ui.paperRule
import com.locusable.ginkgo.ui.paperShape
import com.locusable.ginkgo.ui.presentPaperDialog
import com.locusable.ginkgo.ui.pressBackground
import com.locusable.ginkgo.ui.restoreTileScroll
import com.locusable.ginkgo.ui.rule
import com.locusable.ginkgo.ui.showPaperDialog
import com.locusable.ginkgo.ui.tileScroll
import com.locusable.ginkgo.update.UpdateFlow
import com.locusable.ginkgo.wallpaper.ShelfWallpaper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class InkActivity : Activity() {
    private companion object {
        const val READ_LOCAL_BOOKS = 111
        const val WRITE_WALLPAPER = 112
        const val MAX_HOME_BOOKS = 1 + HOME_RECENT_BOOK_LIMIT
        const val APP_ICON_DP = 52
        const val APP_PIN_DP = 14
        const val ABOUT_TOP_DP = 18
        const val COVER_OUTLINE = 0xFFC9C9C9.toInt()
    }

    private val prefs by lazy { InkPreferences(this) }
    private val updateFlow = lazy { UpdateFlow(this) }
    private lateinit var navigation: LinearLayout
    private val settingsPage by lazy {
        SettingsPage(this, prefs,
            onTabsChanged = ::refreshNavigation,
            openWeReadAccount = ::manageWeReadAccount,
            grayscale = grayscale,
            exportWallpaper = ::requestWallpaperExport,
            checkUpdate = { updateFlow.value.check() },
        )
    }
    private val catalog by lazy { AppCatalog(this) }
    private val folderWorker = Executors.newSingleThreadExecutor()
    private val wallpaperWorker = Executors.newSingleThreadExecutor()
    private val covers by lazy { ShelfCovers(applicationContext) }
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    @Suppress("DEPRECATION")
    private val installedVersion by lazy {
        val info = packageManager.getPackageInfo(packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName.orEmpty()}($build)"
    }
    private var apps = emptyList<AppEntry>()
    private val visiblePages get() = buildList {
        add(LauncherPage.HOME)
        if (prefs.showAppsTab) add(LauncherPage.APPS)
        if (prefs.showShelfTab) add(LauncherPage.SHELF)
        if (prefs.showAboutTab) add(LauncherPage.ABOUT)
        add(LauncherPage.SETTINGS)
    }
    private var page = LauncherPage.HOME
    private var renderedPages = emptyList<LauncherPage>()
    private var renderedPins = emptyList<String>()
    private var renderedPage = LauncherPage.HOME
    private var appsScroll = TileScroll()
    private var shelfScroll = TileScroll()
    private var folderTrail = emptyList<FolderLocation>()
    private var folderContents: FolderContents = FolderContents.Loading
    private var directoryOpenTimes = emptyMap<String, Long>()
    private var folderRequest = 0
    private var openFolderContents: FolderContents = FolderContents.Loading
    private var openFolderRequest = 0
    private var folderDialog: ShelfFolderWindow? = null
    private var folderViewport: ShelfViewport? = null
    private var renderedFolderId: String? = null
    private val folderScroll = mutableMapOf<String, TileScroll>()
    private val weRead by lazy { WeReadController.get(this) }
    private val weReadHistory by lazy { WeReadOpenHistory(applicationContext) }
    private val weReadCovers = lazy { WeReadCovers.get(applicationContext) }
    private val weReadCoverOwner = Any()
    private var weReadGroup: String? = null
    private var observedSnapshot: WeReadSnapshot? = null
    private var observedWeReadLoading = true
    private var loginDialog: WeReadLoginWindow? = null
    private var wallpaperDialog: Dialog? = null
    private var wallpaperJob: Future<*>? = null
    private var wallpaperSaving = false
    private var wallpaperAwaitingCache = false
    private var resumeWallpaperExport = false
    private var resumed = false
    private var observingWeRead = false
    private var recentLocalBooks: List<FolderItem>? = null
    private var recentLocalRequest = 0
    private var renderedRecentBooks = emptyList<ShelfEntry>()
    private var renderedHiddenBooks: HiddenBooks? = null
    private var renderedShelfSources: ShelfSources? = null
    private var renderedOnlineCovers = emptyList<WeReadCovers.Target>()
    private val weReadChanged: () -> Unit = {
        val current = weRead.snapshot
        val previous = observedSnapshot
        observedSnapshot = current
        val loading = weRead.state == WeReadController.State.LOADING
        val loaded = observedWeReadLoading && !loading
        observedWeReadLoading = loading
        if (previous?.account != current.account && weReadGroup != null) dismissShelfFolder(refreshRoot = false)
        if (page == LauncherPage.HOME) {
            if (previous?.account != current.account || renderedRecentBooks != homeBooks() ||
                (loaded && renderedRecentBooks.isEmpty() && recentLocalBooks != null)) {
                render(scrollY = body?.scrollY ?: 0)
            } else if (previous?.session?.cookies != current.session?.cookies) {
                bindOnlineCovers()
            }
        } else if (page == LauncherPage.SHELF) {
            if (prefs.shelfSources.weRead) {
                if (previous?.account != current.account || previous?.shelf != current.shelf) {
                    render()
                } else if (previous?.session?.cookies != current.session?.cookies) {
                    bindOnlineCovers()
                }
            }
        } else if (previous?.shelf != current.shelf || previous?.session != current.session) {
            render(scrollY = body?.scrollY ?: 0)
        }
        if (page == LauncherPage.SETTINGS) {
            body?.let { settingsPage.updateWeReadProfile(it, weRead.profile) }
            if (current.session != null && previous?.session?.account != current.session.account) weRead.loadProfile()
        }
        loginDialog?.render()
    }
    private var pendingBook: FolderItem? = null
    private var shelfOrderDirty = false
    private val mosaicRevisions = mutableMapOf<String, Int>()
    private var editDialog: Dialog? = null
    private var body: View? = null
    private data class ShelfViewport(val list: ListView, val opened: Map<String, Long>)
    private var shelfViewport: ShelfViewport? = null
    private var visibleShelfCovers: List<ShelfRowCovers>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val tab = savedInstanceState?.getInt("page", 0) ?: 0
        page = LauncherPage.entries.getOrElse(tab) { LauncherPage.HOME }.takeIf { it in visiblePages } ?: LauncherPage.HOME
        appsScroll = TileScroll(savedInstanceState?.getInt("apps_row") ?: 0, savedInstanceState?.getInt("apps_offset") ?: 0)
        shelfScroll = TileScroll(savedInstanceState?.getInt("shelf_row") ?: 0, savedInstanceState?.getInt("shelf_offset") ?: 0,
            savedInstanceState?.getString("shelf_key"))
        if (page == LauncherPage.SETTINGS) savedInstanceState?.getString("update_apk")?.let {
            updateFlow.value.awaitingPermission = it
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val previousBody = body
        refresh()
        observeWeRead()
        // onPause cancels outstanding loads; resume them even if refresh kept the same views.
        if (page == LauncherPage.SHELF) refreshVisibleShelfCovers()
        else if (body === previousBody) bindOnlineCovers()
        refreshReadingData()
        if (resumeWallpaperExport) {
            resumeWallpaperExport = false
            requestWallpaperExport()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (page == LauncherPage.SETTINGS && updateFlow.isInitialized()) updateFlow.value.resume()
    }

    override fun onDestroy() {
        if (updateFlow.isInitialized()) updateFlow.value.close()
        editDialog?.dismiss()
        loginDialog?.dismiss()
        dismissShelfFolder(refreshRoot = false)
        cancelWallpaperExport()
        wallpaperWorker.shutdownNow()
        folderRequest++
        recentLocalRequest++
        folderWorker.shutdownNow()
        covers.close()
        if (weReadCovers.isInitialized()) weReadCovers.value.hide(weReadCoverOwner)
        super.onDestroy()
    }

    override fun onPause() {
        if (updateFlow.isInitialized()) updateFlow.value.pause()
        pendingBook = null
        resumed = false
        if (page == LauncherPage.SHELF) refreshVisibleShelfCovers()
        loginDialog?.dismiss()
        cancelWallpaperExport()
        observeWeRead()
        if (weReadCovers.isInitialized()) weReadCovers.value.hide(weReadCoverOwner)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (updateFlow.isInitialized()) {
                updateFlow.value.pause()
                updateFlow.value.awaitingPermission = null
            }
            editDialog?.dismiss()
            loginDialog?.dismiss()
            cancelWallpaperExport()
            show(LauncherPage.HOME)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        rememberTileScroll()
        outState.putInt("page", page.ordinal)
        outState.putInt("apps_row", appsScroll.row)
        outState.putInt("apps_offset", appsScroll.offset)
        outState.putInt("shelf_row", shelfScroll.row)
        outState.putInt("shelf_offset", shelfScroll.offset)
        outState.putString("shelf_key", shelfScroll.key)
        if (updateFlow.isInitialized()) outState.putString("update_apk", updateFlow.value.awaitingPermission)
    }

    @Deprecated("Android 8.1 uses key-based navigation")
    override fun onBackPressed() {
        when {
            page == LauncherPage.SHELF && folderDialog != null -> backFromShelfFolder()
            page != LauncherPage.HOME -> show(LauncherPage.HOME)
            !isDefaultHome() -> super.onBackPressed()
        }
    }

    private fun refresh() {
        val current = catalog.apps()
        // Xiaomi may retain a HOME task and a separate launcher-entry task in the same process.
        if (body == null || renderedPages != visiblePages || apps != current || page == LauncherPage.SETTINGS ||
            (page == LauncherPage.APPS && renderedPins != prefs.pinned()) ||
            (page == LauncherPage.SHELF && (shelfOrderDirty || renderedShelfSources != prefs.shelfSources ||
                renderedHiddenBooks != prefs.hiddenBooks(weRead.snapshot.account))) ||
            (page == LauncherPage.HOME && renderedRecentBooks != homeBooks())) {
            apps = current
            render(scrollY = body?.scrollY ?: 0)
        }
    }

    private fun show(next: LauncherPage) {
        if (next !in visiblePages) return
        if (page != next) {
            pendingBook = null
            when (next) {
                LauncherPage.APPS -> appsScroll = TileScroll()
                LauncherPage.SHELF -> shelfScroll = TileScroll()
                else -> Unit
            }
        }
        if (next != LauncherPage.SHELF) dismissShelfFolder(refreshRoot = false)
        page = next
        render()
        observeWeRead()
        refreshReadingData()
    }

    private fun observeWeRead() {
        if (resumed && page == LauncherPage.SETTINGS) weRead.loadProfile()
        val observe = resumed && page in setOf(LauncherPage.HOME, LauncherPage.SHELF, LauncherPage.SETTINGS)
        if (observe == observingWeRead) return
        observingWeRead = observe
        if (observe) weRead.observe(weReadChanged) else weRead.unobserve(weReadChanged)
    }

    private fun refreshReadingData() {
        if (!resumed || isDestroyed) return
        when (page) {
            LauncherPage.HOME -> loadRecentLocalBooks()
            LauncherPage.SHELF -> {
                reloadFolder()
                if (folderDialog != null) reloadOpenFolder()
            }
            else -> return
        }
        if (prefs.shelfSources.weRead) weRead.whenLoaded {
            if (resumed && !isDestroyed && (page == LauncherPage.HOME || page == LauncherPage.SHELF) &&
                prefs.shelfSources.weRead) weRead.sync()
        }
    }

    private fun rememberTileScroll() {
        val position = (body as? ListView)?.tileScroll() ?: return
        when (renderedPage) {
            LauncherPage.APPS -> appsScroll = position
            LauncherPage.SHELF -> shelfScroll = position
            else -> Unit
        }
    }

    private fun render(scrollY: Int = 0, resetAppsScroll: Boolean = false) {
        rememberTileScroll()
        if (resetAppsScroll) appsScroll = TileScroll()
        val pages = visiblePages
        if (page !in pages) {
            dismissShelfFolder(refreshRoot = false)
            page = LauncherPage.HOME
        }
        if (page == LauncherPage.SHELF && folderDialog != null) {
            shelfOrderDirty = true
            renderShelfFolder()
            return
        }
        val root = column().apply { setBackgroundColor(Color.WHITE) }
        val panel = column().apply {
            val top = if (page == LauncherPage.ABOUT) ABOUT_TOP_DP else PAGE_TOP_DP
            setPadding(dp(PAGE_INSET_DP), dp(top), dp(PAGE_INSET_DP), 0)
        }
        root.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val withBooks = page == LauncherPage.SHELF || page == LauncherPage.HOME
        val sort = if (page == LauncherPage.SHELF) ShelfSort.LAST_OPENED else ShelfSort.NAME
        val opened = if (withBooks) prefs.folderOpenTimes() else emptyMap()
        val onlineOpened = if (withBooks) weReadHistory.times(weRead.snapshot.account) else emptyMap()
        val content = column().apply { setPadding(0, 0, 0, dp(PAGE_BOTTOM_DP)) }
        val coverTargets = mutableListOf<ShelfCovers.Target>()
        val onlineCoverTargets = mutableListOf<WeReadCovers.Target>()
        var grid: ListView? = null
        when (page) {
            LauncherPage.HOME -> fillHome(content, coverTargets, onlineCoverTargets, opened, onlineOpened)
            LauncherPage.APPS -> {
                val pins = prefs.pinned()
                renderedPins = pins
                val pinnedKeys = pins.toSet()
                val byKey = apps.associateBy { it.key }
                val ordered = pins.mapNotNull(byKey::get) + apps.filterNot { it.key in pinnedKeys }
                grid = content.showApps(ordered, pinnedKeys, tile = ::appTile)
            }
            LauncherPage.SHELF -> {
                shelfOrderDirty = false
                grid = fillShelf(content, sort, opened, onlineOpened)
            }
            LauncherPage.ABOUT -> content.showAbout(installedVersion)
            LauncherPage.SETTINGS -> settingsPage.render(content, weRead.profile)
        }
        shelfViewport = if (page == LauncherPage.SHELF) grid?.let { watchShelfRows(it, opened) } else null
        val view = if (grid == null) ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else grid.apply {
            setPadding(0, 0, 0, dp(PAGE_BOTTOM_DP))
            restoreTileScroll(if (page == LauncherPage.APPS) appsScroll else shelfScroll)
        }
        body = view
        renderedPage = page
        panel.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        attachNavigation(root, pages)
        setContentView(root)
        if (page == LauncherPage.SHELF) {
            refreshVisibleShelfCovers()
        } else {
            visibleShelfCovers = null
            covers.show(coverTargets, sort, opened) { location -> mosaicRevisions[location.id] ?: 0 }
            renderedOnlineCovers = onlineCoverTargets
            bindOnlineCovers()
        }
        if (grid == null && scrollY > 0) {
            view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    view.scrollTo(0, scrollY)
                    return true
                }
            })
        }
    }

    private fun watchShelfRows(list: ListView, opened: Map<String, Long>): ShelfViewport {
        var scheduled = false
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (scheduled) return
                scheduled = true
                view.postOnAnimation {
                    scheduled = false
                    if (view.isAttachedToWindow) refreshVisibleShelfCovers()
                }
            }
        })
        return ShelfViewport(list, opened)
    }

    private fun refreshVisibleShelfCovers() {
        if (page != LauncherPage.SHELF) return
        val viewport = if (!resumed) null else if (folderDialog != null) folderViewport else shelfViewport
        val rows = viewport?.list?.let { list ->
            (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? ShelfRowCovers }
        }.orEmpty()
        if (rows == visibleShelfCovers) return
        visibleShelfCovers = rows
        covers.show(rows.flatMap { it.local }, ShelfSort.LAST_OPENED, viewport?.opened.orEmpty()) { location ->
            mosaicRevisions[location.id] ?: 0
        }
        renderedOnlineCovers = rows.flatMap { it.online }
        bindOnlineCovers()
    }

    private fun bindOnlineCovers() {
        if (renderedOnlineCovers.isNotEmpty() && resumed) {
            val snapshot = weRead.snapshot
            weReadCovers.value.show(weReadCoverOwner, snapshot.account, snapshot.session, renderedOnlineCovers)
        } else if (weReadCovers.isInitialized()) {
            weReadCovers.value.hide(weReadCoverOwner)
        }
    }

    private fun fillHome(
        content: LinearLayout, localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
        opened: Map<String, Long>, onlineOpened: Map<String, Long>,
    ) {
        val books = homeBooks(opened, onlineOpened)
        renderedRecentBooks = books
        content.showHome(
            books,
            booksReady = recentLocalBooks != null && weRead.state != WeReadController.State.LOADING,
            featuredCover = { book -> resumeCover(book, localCovers, onlineCovers) },
            bindAuthor = { book, display -> bindBookAuthor(book, localCovers, display) },
            recentBookTile = { book -> shelfTile(book, localCovers, onlineCovers, ShelfSort.NAME, onlineOpened) },
            openBook = ::openShelfEntry,
        )
    }

    private fun homeBooks(
        local: Map<String, Long> = prefs.folderOpenTimes(),
        online: Map<String, Long> = weReadHistory.times(weRead.snapshot.account),
    ) = recentShelfBooks(recentLocalBooks.orEmpty(), weRead.snapshot.shelf?.books.orEmpty(), local, online,
        prefs.hiddenBooks(weRead.snapshot.account), MAX_HOME_BOOKS)

    private fun loadRecentLocalBooks() {
        val request = ++recentLocalRequest
        val opened = if (Build.VERSION.SDK_INT <= 28 && hasBooksPermission()) {
            prefs.folderOpenTimes() - prefs.hiddenBooks(null).local
        } else emptyMap()
        folderWorker.execute {
            val recent = FolderAccess.recentBooks(opened, MAX_HOME_BOOKS)
            runOnUiThread {
                if (request != recentLocalRequest || isDestroyed) return@runOnUiThread
                val firstLoad = recentLocalBooks == null
                recentLocalBooks = recent
                if (page == LauncherPage.HOME && (firstLoad || renderedRecentBooks != homeBooks(opened))) {
                    render(scrollY = body?.scrollY ?: 0)
                }
            }
        }
    }

    private fun appTile(item: AppEntry, pinned: Boolean) = column().apply {
        val name = displayLabel(item)
        contentDescription = if (pinned) "$name，已置顶" else name
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), 0, dp(2), 0)
        val tileWidth = (resources.configuration.screenWidthDp - 2 * PAGE_INSET_DP -
            (APP_COLUMNS - 1) * TILE_COLUMN_GAP_DP) / APP_COLUMNS
        val iconSize = minOf(APP_ICON_DP, (tileWidth - 2 * (APP_PIN_DP + 4)).coerceAtLeast(1))
        val icon = catalog.icon(item)?.let { original ->
            ImageView(this@InkActivity).apply {
                setImageDrawable(original)
                colorFilter = grayscale
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } ?: InkGlyph(this@InkActivity, InkGlyph.Kind.APP, sizeDp = minOf(36, iconSize))
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(FrameLayout(this@InkActivity).apply {
            addView(icon, FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
            if (pinned) {
                addView(InkGlyph(this@InkActivity, InkGlyph.Kind.PIN, sizeDp = APP_PIN_DP).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(APP_PIN_DP), dp(APP_PIN_DP), Gravity.TOP or Gravity.END).apply {
                    marginEnd = dp(((tileWidth - iconSize) / 2 - APP_PIN_DP - 4).coerceAtLeast(0))
                })
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(iconSize)))
        gap(6)
        addView(label(name, ACTION_TEXT_SP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setLines(2)
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        appAction(item, pinned)
    }

    private fun fillShelf(
        content: LinearLayout, sort: ShelfSort, opened: Map<String, Long>, onlineOpened: Map<String, Long>,
    ): ListView? {
        val sources = prefs.shelfSources
        if (sources != renderedShelfSources) {
            renderedShelfSources = sources
            if (!sources.local) {
                folderContents = FolderContents.Loading
                directoryOpenTimes = emptyMap()
                folderRequest++
            }
        }
        val snapshot = weRead.snapshot
        val cloudShelf = snapshot.shelf.takeIf { sources.weRead }
        val hidden = prefs.hiddenBooks(snapshot.account)
        renderedHiddenBooks = hidden
        val local = folderContents.takeIf { sources.local }
        val entries = buildList {
            (local as? FolderContents.Items)?.entries?.forEach { add(ShelfEntry.Local(it)) }
            cloudShelf?.root.orEmpty().forEach { add(ShelfEntry.Online(it)) }
        }.orderedOnShelf(sort, opened, onlineOpened, hidden, directories = directoryOpenTimes)
        val message = if (entries.isEmpty()) when {
            !sources.local && !sources.weRead -> "未开启书架来源"
            local == FolderContents.Loading -> "正在读取书架…"
            local == FolderContents.PermissionRequired -> null
            else -> "暂无可显示的书籍"
        } else null
        return content.showShelfGrid(ShelfPageModel(local == FolderContents.PermissionRequired, message, entries),
            sort, onlineOpened, hidden)
    }

    private fun LinearLayout.showShelfGrid(
        model: ShelfPageModel, sort: ShelfSort, onlineOpened: Map<String, Long>, hidden: HiddenBooks,
    ): ListView? = showShelf(model, ::requestBooksPermission) { entry, covers ->
        shelfTile(entry, covers.local, covers.online, sort, onlineOpened,
            hiddenOnHome = entry in hidden, rootShelf = !model.inFolder)
    }

    private fun openShelfGroup(group: String) {
        pendingBook = null
        weReadGroup = group
        renderShelfFolder()
    }

    private fun openWeReadBook(book: WeReadEntry.Book) {
        if (!tryStart(WeReadBookLink.intent(book.id))) {
            val fallback = WeReadBookLink.fallback(book.readerLink)
            if (fallback == null || !tryStart(fallback)) {
                toast("未找到支持续读链接的微信读书墨水屏版")
                return
            }
        }
        weRead.snapshot.account?.let { weReadHistory.record(it, book.id) }
        shelfOrderDirty = true
    }

    private fun openShelfEntry(entry: ShelfEntry) {
        when (entry) {
            is ShelfEntry.Local -> openFolderItem(entry.item)
            is ShelfEntry.Online -> when (val item = entry.entry) {
                is WeReadEntry.Book -> openWeReadBook(item)
                is WeReadEntry.Group -> openShelfGroup(item.id)
            }
        }
    }

    private fun openFolderItem(item: FolderItem) {
        if (item.isDirectory) {
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
                prefs.recordBookOpen(item.location)
                File(item.location.id).parent?.let { id -> mosaicRevisions[id] = (mosaicRevisions[id] ?: 0) + 1 }
                shelfOrderDirty = true
            }
        }
    }

    private fun shelfTile(
        entry: ShelfEntry, localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
        sort: ShelfSort, onlineOpened: Map<String, Long>, hiddenOnHome: Boolean = false, rootShelf: Boolean = false,
    ): View = coverTile(entry.displayTitle, entry.directory, online = entry is ShelfEntry.Online, hiddenOnHome = hiddenOnHome,
        rootShelf = rootShelf, bind = { cover ->
        when {
            !entry.directory -> bindBookCover(entry, cover, localCovers, onlineCovers)
            entry is ShelfEntry.Local -> localCovers.add(ShelfCovers.Target.Directory(entry.item.location) { faces ->
                if (faces.isNotEmpty() || rootShelf) cover.showCoverMosaic(faces, markFolder = rootShelf)
                else cover.showCoverTitle(entry.displayTitle)
            })
            entry is ShelfEntry.Online -> {
                val group = entry.entry as WeReadEntry.Group
                val books = group.books.map(ShelfEntry::Online).orderedOnShelf(sort, emptyMap(), onlineOpened).take(4)
                    .map { it.entry as WeReadEntry.Book }
                val faces = books.map { ShelfCovers.Face(it.name, null) }.toMutableList()
                if (faces.isNotEmpty()) cover.showCoverMosaic(faces, fileNames = false, markFolder = rootShelf)
                for ((index, book) in books.withIndex()) {
                    onlineCovers.add(WeReadCovers.Target(book) { bitmap ->
                        faces[index] = ShelfCovers.Face(book.name, bitmap)
                        cover.showCoverMosaic(faces, fileNames = false, markFolder = rootShelf)
                    })
                }
            }
        }
    }) { openShelfEntry(entry) }.apply {
        if (!entry.directory) {
            val author = context.bookAuthorLabel()
            val description = contentDescription
            fun showAuthor(name: String) {
                author.text = name
                contentDescription = "$description，作者：$name"
            }
            showAuthor(author.text.toString())
            addView(author, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            bindBookAuthor(entry, localCovers, ::showAuthor)
        }
        bookVisibilityAction(entry, hiddenOnHome)
    }

    private fun bindBookAuthor(book: ShelfEntry, localCovers: MutableList<ShelfCovers.Target>, display: (String) -> Unit) {
        when (book) {
            is ShelfEntry.Local -> if (book.item.coverFormat == CoverFormat.EPUB) {
                localCovers.add(ShelfCovers.Target.Author(book.item, display))
            }
            is ShelfEntry.Online -> (book.entry as? WeReadEntry.Book)?.author?.let(display)
        }
    }

    private fun resumeCover(
        book: ShelfEntry, localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
    ): View = CoverFrame().apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val artwork = FrameLayout(context).apply { showCoverTitle(book.displayTitle, compact = true) }
        addView(artwork, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bindBookCover(book, artwork, localCovers, onlineCovers)
    }

    private fun bindBookCover(
        book: ShelfEntry, artwork: FrameLayout,
        localCovers: MutableList<ShelfCovers.Target>, onlineCovers: MutableList<WeReadCovers.Target>,
    ) {
        when (book) {
            is ShelfEntry.Local -> if (book.item.coverFormat != null) {
                localCovers.add(ShelfCovers.Target.File(book.item) { artwork.showCoverBitmap(it) })
            }
            is ShelfEntry.Online -> (book.entry as? WeReadEntry.Book)?.let { entry ->
                onlineCovers.add(WeReadCovers.Target(entry) { artwork.showCoverBitmap(it) })
            }
        }
    }

    private fun coverTile(
        name: String, directory: Boolean, online: Boolean, hiddenOnHome: Boolean, rootShelf: Boolean,
        bind: (FrameLayout) -> Unit, open: () -> Unit,
    ) = column().apply {
        contentDescription = buildString {
            if (directory) append("目录，")
            append(name)
            if (online) append("，微信读书")
            if (hiddenOnHome) append("，已在首页隐藏")
        }
        val artwork = FrameLayout(this@InkActivity).apply {
            if (rootShelf && directory) showCoverMosaic(emptyList(), markFolder = true)
            else showCoverTitle(name, rootShelf = rootShelf)
        }
        val cover = CoverFrame(outlined = !rootShelf || !directory).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(artwork, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (hiddenOnHome) addView(InkGlyph(this@InkActivity, InkGlyph.Kind.EYE_OFF, sizeDp = 17).apply {
                background = paperShape(LINE)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4)
                marginEnd = dp(4)
            })
        }
        bind(artwork)
        addView(cover, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(if (rootShelf) 7 else 5)
        addView(horizontal().apply {
            gravity = Gravity.TOP
            if (directory && !rootShelf) {
                addView(InkGlyph(this@InkActivity, InkGlyph.Kind.FILE, sizeDp = 16, tone = SOFT_INK),
                    LinearLayout.LayoutParams(dp(17), dp(20)).apply { marginEnd = dp(4) })
            }
            addView(label(name, LABEL_TEXT_SP, face = if (directory) Typeface.DEFAULT_BOLD else Typeface.DEFAULT).apply {
                if (rootShelf) includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        action(click = open)
    }

    private inner class CoverFrame(outlined: Boolean = true) : FrameLayout(this@InkActivity) {
        init { if (outlined) foreground = paperShape(COVER_OUTLINE, Color.TRANSPARENT) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = (width * 3 + 1) / 2
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private fun FrameLayout.showCoverTitle(name: String, compact: Boolean = false, rootShelf: Boolean = false) {
        removeAllViews()
        background = paperShape(if (compact) COVER_OUTLINE else Color.TRANSPARENT, 0xFFF7F7F7.toInt())
        val inset = dp(if (compact) 4 else 9)
        addView(column().apply {
            setPaddingRelative(inset, dp(if (compact) 7 else 14), inset, 0)
            if (!compact && !rootShelf) {
                addView(paperRule(0xFF8A8A8A.toInt()), LinearLayout.LayoutParams(dp(14), dp(1)))
                gap(6)
            }
            addView(label(bookTitlePreview(name), if (compact) 9f else if (rootShelf) LABEL_TEXT_SP else 12f,
                face = Typeface.create("serif", Typeface.NORMAL), tone = if (rootShelf) INK else SOFT_INK).apply {
                gravity = Gravity.TOP or Gravity.START
                maxLines = if (rootShelf) 3 else 2
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

    private fun FrameLayout.showCoverMosaic(faces: List<ShelfCovers.Face>, fileNames: Boolean = true, markFolder: Boolean = false) {
        removeAllViews()
        setBackgroundColor(if (markFolder) Color.TRANSPARENT else 0xFFE6E6E6.toInt())
        if (markFolder) {
            addView(View(context).apply { background = paperShape(LINE, PRESS_FILL) },
                FrameLayout.LayoutParams(dp(26), dp(8), Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(8)
                    marginStart = dp(12)
                })
        }
        addView(column().apply {
            if (markFolder) {
                background = paperShape(LINE, PRESS_FILL)
                val inset = dp(4)
                setPadding(inset, inset, inset, inset)
            }
            if (faces.isNotEmpty()) repeat(2) { line ->
                addView(horizontal().apply {
                    repeat(2) { column ->
                        val panel = FrameLayout(this@InkActivity)
                        faces.getOrNull(line * 2 + column)?.let { face ->
                            if (face.bitmap != null) panel.showCoverBitmap(face.bitmap)
                            else panel.showCoverTitle(if (fileNames) face.name.substringBeforeLast('.') else face.name, compact = true)
                        }
                        addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                            if (markFolder && column > 0) marginStart = dp(3)
                        })
                    }
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                    if (markFolder && line > 0) topMargin = dp(3)
                })
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            if (markFolder) setMargins(dp(7), dp(15), dp(7), dp(8))
        })
    }

    private fun navigateFolder(trail: List<FolderLocation>) {
        pendingBook = null
        weReadGroup = null
        folderTrail = trail
        openFolderContents = FolderContents.Loading
        renderShelfFolder()
        reloadOpenFolder()
    }

    private fun backFromShelfFolder() {
        if (folderTrail.size > 1) navigateFolder(folderTrail.dropLast(1)) else dismissShelfFolder()
    }

    private fun renderShelfFolder() {
        val sources = prefs.shelfSources
        val snapshot = weRead.snapshot
        val folder = folderTrail.lastOrNull()?.takeIf { sources.local }
        val group = snapshot.shelf?.groups?.firstOrNull { sources.weRead && it.id == weReadGroup }
        val name = group?.name ?: folder?.name
        if (name == null) {
            dismissShelfFolder()
            return
        }
        renderedFolderId?.let { id -> folderViewport?.list?.tileScroll()?.let { folderScroll[id] = it } }
        val id = group?.id ?: requireNotNull(folder).id
        val sort = ShelfSort.LAST_OPENED
        val opened = prefs.folderOpenTimes()
        val onlineOpened = weReadHistory.times(snapshot.account)
        val hidden = prefs.hiddenBooks(snapshot.account)
        val local = openFolderContents.takeIf { folder != null }
        val entries = buildList {
            (local as? FolderContents.Items)?.entries?.forEach { add(ShelfEntry.Local(it)) }
            group?.books.orEmpty().forEach { add(ShelfEntry.Online(it)) }
        }.orderedOnShelf(sort, opened, onlineOpened, hidden, directories = directoryOpenTimes)
        val message = if (entries.isEmpty()) when (local) {
            FolderContents.Loading -> "正在读取书架…"
            FolderContents.PermissionRequired -> null
            FolderContents.Unavailable -> "无法读取此目录"
            else -> if (group != null) "这个文件夹没有书籍" else "这个目录没有书籍"
        } else null
        val parentPath = if (folderTrail.size > 1) folderTrail.dropLast(1).joinToString(" / ") { it.name } else null
        val window = folderDialog ?: ShelfFolderWindow(this, checkNotNull(body).width, ::backFromShelfFolder).also { created ->
            folderDialog = created
            created.dialog.setOnDismissListener { if (folderDialog === created) dismissShelfFolder() }
            created.dialog.setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_BACK || folderTrail.size <= 1) false else {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) backFromShelfFolder()
                    true
                }
            }
        }
        window.prepare(name, parentPath, loading = local == FolderContents.Loading)
        val list = window.content.showShelfGrid(
            ShelfPageModel(local == FolderContents.PermissionRequired, message, entries, inFolder = true),
            sort, onlineOpened, hidden)
        folderViewport = list?.let {
            window.content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            folderScroll[id]?.let(it::restoreTileScroll)
            watchShelfRows(it, opened)
        }
        renderedFolderId = id
        if (!window.dialog.isShowing) window.show()
        refreshVisibleShelfCovers()
    }

    private fun dismissShelfFolder(refreshRoot: Boolean = true) {
        val dialog = folderDialog?.dialog
        folderDialog = null
        weReadGroup = null
        folderTrail = emptyList()
        folderViewport = null
        renderedFolderId = null
        folderScroll.clear()
        openFolderContents = FolderContents.Loading
        openFolderRequest++
        if (dialog == null) return
        pendingBook = null
        if (dialog.isShowing) dialog.dismiss()
        if (refreshRoot && page == LauncherPage.SHELF && resumed) {
            if (shelfOrderDirty) render() else refreshVisibleShelfCovers()
        }
    }

    private fun reloadFolder() {
        if (!prefs.shelfSources.local) return
        val request = ++folderRequest
        readFolder(null) { contents, directoryTimes ->
            if (request != folderRequest || !prefs.shelfSources.local) return@readFolder
            val orderChanged = directoryTimes != directoryOpenTimes
            if (contents == folderContents && !orderChanged) return@readFolder
            folderContents = contents
            directoryOpenTimes = directoryTimes
            if (page == LauncherPage.SHELF) {
                if (folderDialog == null) render() else {
                    shelfOrderDirty = true
                    if (orderChanged && folderTrail.isNotEmpty() && openFolderContents is FolderContents.Items &&
                        folderDialog?.dialog?.isShowing == true) renderShelfFolder()
                }
            }
        }
    }

    private fun reloadOpenFolder() {
        val location = folderTrail.lastOrNull() ?: return
        val window = folderDialog ?: return
        val request = ++openFolderRequest
        readFolder(location) { contents, _ ->
            if (request == openFolderRequest && folderDialog === window && window.dialog.isShowing && contents != openFolderContents) {
                openFolderContents = contents
                renderShelfFolder()
            }
        }
    }

    private fun readFolder(location: FolderLocation?, deliver: (FolderContents, Map<String, Long>) -> Unit) {
        val gate = when {
            Build.VERSION.SDK_INT > 28 -> FolderContents.Unsupported
            !hasBooksPermission() -> FolderContents.PermissionRequired
            else -> null
        }
        if (gate != null) {
            deliver(gate, emptyMap())
            return
        }
        folderWorker.execute {
            val result = FolderAccess.contents(location)
            val directoryTimes = if (location == null && result is FolderContents.Items) {
                FolderAccess.directoryOpenTimes(prefs.folderOpenTimes())
            } else emptyMap()
            runOnUiThread { if (!isDestroyed) deliver(result, directoryTimes) }
        }
    }

    @Suppress("DEPRECATION")
    private fun hasBooksPermission() = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun requestBooksPermission() {
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_LOCAL_BOOKS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            READ_LOCAL_BOOKS -> if (granted) {
                reloadFolder()
                reloadOpenFolder()
            } else toast("可在应用权限中允许 Ginkgo 读取存储")
            WRITE_WALLPAPER -> if (granted) {
                if (resumed) requestWallpaperExport() else resumeWallpaperExport = true
            } else toast("允许存储权限后才能导出壁纸")
        }
    }

    @Suppress("DEPRECATION")
    private fun requestWallpaperExport() {
        if (!resumed || isDestroyed || page != LauncherPage.SETTINGS || wallpaperDialog != null ||
            wallpaperAwaitingCache || wallpaperSaving) return
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_WALLPAPER)
            return
        }
        wallpaperAwaitingCache = true
        weRead.whenLoaded {
            wallpaperAwaitingCache = false
            if (resumed && !isDestroyed && page == LauncherPage.SETTINGS && wallpaperDialog == null) exportShelfWallpaper()
        }
    }

    @Suppress("DEPRECATION") // Use the full display, not the current app viewport.
    private fun exportShelfWallpaper() {
        val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        val snapshot = weRead.snapshot
        val hidden = prefs.hiddenBooks(snapshot.account)
        val localOpened = if (Build.VERSION.SDK_INT < 29 && hasBooksPermission()) {
            prefs.folderOpenTimes() - hidden.local
        } else emptyMap()
        val onlineOpened = weReadHistory.times(snapshot.account)
        val localCover = covers
        val onlineCover = weReadCovers.value.wallpaperSource(snapshot.account, snapshot.session)
        val dialog = paperDialog()
        dialog.setContentView(paperDialogPanel().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("正在生成壁纸…", DIALOG_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply { gravity = Gravity.CENTER })
            gap(SECTION_GAP_DP)
            addView(this@InkActivity.paperButton("取消", primary = false, click = ::cancelWallpaperExport),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        })
        wallpaperDialog = dialog
        dialog.setOnDismissListener { if (wallpaperDialog === dialog) cancelWallpaperExport() }
        presentPaperDialog(dialog)
        wallpaperJob = wallpaperWorker.submit {
            val result = runCatching {
                val layout = ShelfWallpaper.layout(size.x, size.y)
                val recent = recentShelfBooks(FolderAccess.recentBooks(localOpened, Int.MAX_VALUE).filter { it.coverFormat != null },
                    snapshot.shelf?.books.orEmpty(), localOpened, onlineOpened, hidden, Int.MAX_VALUE)
                val deadline = SystemClock.elapsedRealtime() + 15_000
                ShelfWallpaper.composeRecent(layout, recent) { entry ->
                    when (entry) {
                        is ShelfEntry.Local -> localCover.wallpaperCover(entry.item, layout.coverEdge)
                        is ShelfEntry.Online -> (entry.entry as? WeReadEntry.Book)?.let { onlineCover(it, deadline) }
                    }
                }
            }
            runOnUiThread {
                if (wallpaperDialog !== dialog || isDestroyed) {
                    result.getOrNull()?.recycle()
                    return@runOnUiThread
                }
                wallpaperDialog = null
                wallpaperJob = null
                dialog.dismiss()
                result.fold(
                    onSuccess = { image ->
                        if (image == null) showPaperDialog("没有可用封面", "最近打开的书里没有可用封面。", "知道了")
                        else showWallpaperPreview(image)
                    },
                    onFailure = { showPaperDialog("未能生成壁纸", "请稍后重试。", "知道了") },
                )
            }
        }
    }

    private fun showWallpaperPreview(bitmap: Bitmap) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(resources.configuration.screenHeightDp / 2)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "书架壁纸预览"
            setImageBitmap(bitmap)
        }
        val dialog = showPaperDialog("壁纸预览", "保存位置：${ShelfWallpaper.saveDirectory}/", "保存", extraContent = image) {
            wallpaperDialog = null
            saveWallpaper(bitmap)
        }
        wallpaperDialog = dialog
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            if (wallpaperDialog === dialog) wallpaperDialog = null
        }
    }

    private fun saveWallpaper(bitmap: Bitmap) {
        if (wallpaperSaving) return
        wallpaperSaving = true
        val context = applicationContext
        wallpaperWorker.execute {
            val failed = runCatching { ShelfWallpaper.save(context, bitmap) }.isFailure
            runOnUiThread {
                wallpaperSaving = false
                if (failed && !isDestroyed && resumed && page == LauncherPage.SETTINGS) {
                    showPaperDialog("未能保存壁纸", "请检查存储空间后再试。", "知道了")
                }
            }
        }
    }

    private fun cancelWallpaperExport() {
        val dialog = wallpaperDialog
        wallpaperDialog = null
        wallpaperJob?.cancel(true)
        wallpaperJob = null
        dialog?.dismiss()
    }

    private fun manageWeReadAccount() {
        weRead.whenLoaded {
            if (!resumed || page != LauncherPage.SETTINGS || isDestroyed || loginDialog != null) return@whenLoaded
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

    private fun showWeReadLoginDialog() {
        val login = WeReadLoginWindow()
        loginDialog = login
        weRead.beginLogin()
        presentPaperDialog(login.dialog)
    }

    @Suppress("DEPRECATION") // Keep the footer reachable when the reader's OTP keyboard opens.
    private inner class WeReadLoginWindow {
        val dialog = paperDialog(secure = true)
        private val content = column().apply { gravity = Gravity.CENTER }
        private val scroll = ScrollView(this@InkActivity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        private var previous: Pair<WeReadController.State, WeReadController.Notice?>? = null
        private var submit: (() -> Unit)? = null
        private val confirm = paperButton("确认", primary = true) { submit?.invoke() }
        private val clear = if (weRead.snapshot.account == null) null else paperButton("清除本机缓存", primary = false) {
            dismiss()
            confirmWeReadForget(signedIn = false)
        }

        init {
            dialog.setOnDismissListener { stopLogin() }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dialog.setContentView(paperDialogPanel().apply {
                addView(label("微信读书登录", DIALOG_TITLE_SP, face = Typeface.DEFAULT_BOLD).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                gap(12)
                addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(264), 1f))
                gap(12)
                addView(horizontal().apply {
                    gravity = Gravity.CENTER
                    addView(paperButton("取消", primary = false) { dismiss() },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(confirm, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                clear?.let {
                    gap(6)
                    addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    })
                }
            })
        }

        fun dismiss() {
            stopLogin()
            dialog.dismiss()
        }

        private fun stopLogin() {
            if (loginDialog !== this) return
            loginDialog = null
            weRead.stopLogin()
        }

        fun render() {
            if (weRead.snapshot.session != null) {
                dismiss()
                return
            }
            val state = weRead.state
            val next = state to weRead.notice
            if (next == previous) return
            if (previous?.first == WeReadController.State.VERIFY && state != WeReadController.State.VERIFY) {
                dialog.currentFocus?.let { focused ->
                    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
                    focused.clearFocus()
                }
            }
            previous = next
            submit = null
            content.removeAllViews()
            when (state) {
                WeReadController.State.SCAN -> {
                    message("使用手机微信扫一扫，并确认登录")
                    content.addView(wechatQr(checkNotNull(weRead.qr)), LinearLayout.LayoutParams(MATCH_PARENT, dp(222)))
                }
                WeReadController.State.VERIFY -> {
                    message(when (weRead.notice) {
                        WeReadController.Notice.BAD_CODE -> "验证码不正确，请重试。"
                        WeReadController.Notice.LOGIN_FAILED -> "暂时无法验证，请重试。"
                        else -> "输入微信读书登录页面显示的 4 位验证码。"
                    })
                    content.gap(12)
                    val code = EditText(this@InkActivity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        filters = arrayOf(InputFilter.LengthFilter(4))
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        isSaveEnabled = false
                        hint = "4 位验证码"
                        setSingleLine()
                    }
                    content.addView(code, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    confirm.text = "确认"
                    submit = {
                        val value = code.text.toString()
                        if (value.length == 4) weRead.submitCode(value) else toast("请输入 4 位验证码")
                    }
                }
                WeReadController.State.IDLE -> {
                    message(when (weRead.notice) {
                        WeReadController.Notice.LOGIN_TIMEOUT -> "二维码已过期。"
                        WeReadController.Notice.CODE_EXPIRED -> "验证码已过期，请重新扫码。"
                        WeReadController.Notice.LOGIN_STATUS_FAILED -> "暂时无法确认登录结果，请重新扫码。"
                        else -> "未能获取登录二维码，请重试。"
                    })
                    confirm.text = "重新扫码"
                    submit = weRead::beginLogin
                }
                WeReadController.State.VERIFYING -> message("正在验证登录…")
                WeReadController.State.SAVING -> message("正在完成登录…")
                else -> message("正在获取二维码…")
            }
            confirm.visibility = if (submit != null) View.VISIBLE else View.INVISIBLE
            clear?.visibility = if (state == WeReadController.State.SAVING) View.INVISIBLE else View.VISIBLE
            scroll.scrollTo(0, 0)
        }

        private fun message(text: String) {
            content.addView(label(text, ACTION_TEXT_SP, tone = SOFT_INK).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
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

    private fun View.appAction(item: AppEntry, pinned: Boolean, stroke: Int = Color.TRANSPARENT) {
        action(stroke) { openApp(item) }
        confirmOnLongPress(if (pinned) "取消置顶" else "置顶") { confirmPin(item) }
    }

    private fun View.bookVisibilityAction(entry: ShelfEntry, hiddenOnHome: Boolean) {
        if (page != LauncherPage.SHELF || entry.directory) return
        val account = weRead.snapshot.account
        confirmOnLongPress(if (hiddenOnHome) "在首页显示" else "在首页隐藏") {
            confirmBookVisibility(entry, account)
        }
    }

    private fun View.confirmOnLongPress(label: String, confirm: () -> Unit) {
        setOnLongClickListener { confirm(); true }
        buttonRole(longAction = label)
    }

    private fun confirmBookVisibility(entry: ShelfEntry, account: String?) {
        fun sameAccount() = entry is ShelfEntry.Local || (account != null && account == weRead.snapshot.account)
        if (editDialog?.isShowing == true || !sameAccount()) return
        val hidden = entry in prefs.hiddenBooks(account)
        val title = if (hidden) "在首页显示？" else "在首页隐藏？"
        val message = if (hidden) "「${entry.name}」可以再次出现在首页的最近打开中。"
            else "将「${entry.name}」从首页隐藏，书籍仍保留在书架。"
        trackEditDialog(showPaperDialog(title, message, if (hidden) "显示" else "隐藏") {
            if (sameAccount()) {
                prefs.setBookHidden(entry, account, !hidden)
                render(scrollY = body?.scrollY ?: 0)
            }
        })
    }

    private fun displayLabel(item: AppEntry) = if (item.isDeviceSettings) "设备设置" else item.label

    private fun openApp(item: AppEntry) {
        if (!tryStart(item.launchIntent())) toast("无法打开 ${displayLabel(item)}")
    }

    private fun confirmPin(item: AppEntry) {
        if (editDialog?.isShowing == true) return
        val name = displayLabel(item)
        val isPinned = item.key in prefs.pinned()
        val title = if (isPinned) "取消置顶？" else "置顶应用？"
        val message = if (isPinned) "「$name」将回到应用页的默认排序。" else "将「$name」移到应用页最前面。"
        trackEditDialog(showPaperDialog(title, message, if (isPinned) "取消置顶" else "置顶") {
            val pins = prefs.pinned() - item.key
            prefs.savePins(if (isPinned) pins else listOf(item.key) + pins)
            render(resetAppsScroll = !isPinned)
        })
    }

    private fun trackEditDialog(dialog: Dialog) {
        editDialog = dialog
        dialog.setOnDismissListener { if (editDialog === dialog) editDialog = null }
    }

    private fun refreshNavigation() {
        val root = navigation.parent as LinearLayout
        root.removeView(navigation)
        attachNavigation(root, visiblePages)
    }

    private fun attachNavigation(root: LinearLayout, pages: List<LauncherPage>) {
        navigation = footer(pages)
        root.addView(navigation, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        renderedPages = pages
    }

    private fun footer(pages: List<LauncherPage>) = column().apply {
        setPadding(dp(PAGE_INSET_DP), 0, dp(PAGE_INSET_DP), dp(4))
        rule(INK)
        val tabs = horizontal()
        pages.forEach { item ->
            val selected = page == item
            val destination = item.title
            val cell = column().apply {
                contentDescription = destination
                isSelected = selected
                buttonRole()
                action { show(item) }
                addView(View(this@InkActivity).apply {
                    setBackgroundColor(if (selected) INK else Color.TRANSPARENT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(20), dp(2)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(label(destination, LABEL_TEXT_SP, face = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT).apply {
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
}
