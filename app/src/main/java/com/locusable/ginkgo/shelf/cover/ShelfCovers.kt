package com.locusable.ginkgo.shelf.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.locusable.ginkgo.shelf.ShelfSort
import com.locusable.ginkgo.shelf.cache.CoverDiskCache
import com.locusable.ginkgo.shelf.local.CoverFormat
import com.locusable.ginkgo.shelf.local.FolderAccess
import com.locusable.ginkgo.shelf.local.FolderContents
import com.locusable.ginkgo.shelf.local.FolderItem
import com.locusable.ginkgo.shelf.local.FolderLocation
import com.locusable.ginkgo.shelf.orderedBy
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParserException

/** One background queue, with bounded thumbnail and mosaic caches for the shelf. */
internal class ShelfCovers(private val context: Context) {
    sealed interface Target {
        data class File(val item: FolderItem, val display: (Bitmap) -> Unit) : Target
        data class Author(val item: FolderItem, val display: (String) -> Unit) : Target
        data class Directory(val location: FolderLocation, val display: (List<Face>) -> Unit) : Target
    }
    data class Face(val name: String, val bitmap: Bitmap?)
    private data class Key(
        val location: FolderLocation, val modified: Long, val size: Long, val format: CoverFormat, val edge: Int,
    ) {
        fun diskKey(): String? = if (modified > 0) "$edge:$format:$modified:$size:${location.id}" else null
    }
    private data class Preview(val bitmap: Bitmap?)
    private data class Author(val name: String?)
    private data class MosaicKey(val location: String, val sort: ShelfSort, val openRevision: Int)
    private data class Mosaic(val items: List<FolderItem>, val faces: List<Face>)

    private val cache = object : LruCache<Key, Preview>(6 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Preview) = value.bitmap?.allocationByteCount ?: 512
    }
    private val authors = LruCache<FolderItem, Author>(256)
    private val mosaics = object : LruCache<MosaicKey, Mosaic>(3 * 1024 * 1024) {
        override fun sizeOf(key: MosaicKey, value: Mosaic) =
            512 + value.faces.sumOf { it.bitmap?.allocationByteCount ?: 0 }
    }
    private val disk by lazy { CoverDiskCache(context) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var batch: Future<*>? = null
    private var generation = 0

    fun show(
        targets: List<Target>, sort: ShelfSort, opened: Map<String, Long>,
        openRevision: (FolderLocation) -> Int,
    ) {
        val request = ++generation
        batch?.cancel(true)
        batch = null
        if (targets.isEmpty()) return
        val files = targets.filterIsInstance<Target.File>().mapNotNull { target ->
            val key = target.item.coverKey(THUMBNAIL_EDGE) ?: return@mapNotNull null
            val hit = cache.get(key)
            if (hit == null) key to target.display else {
                hit.bitmap?.let(target.display)
                null
            }
        }
        val pendingAuthors = targets.filterIsInstance<Target.Author>().filter { target ->
            val hit = authors.get(target.item)
            hit?.name?.let(target.display)
            hit == null
        }
        val directories = targets.filterIsInstance<Target.Directory>().map { directory ->
            val revision = if (sort == ShelfSort.LAST_OPENED) openRevision(directory.location) else 0
            val key = MosaicKey(directory.location.id, sort, revision)
            val cached = mosaics.get(key)
            cached?.faces?.takeIf { it.isNotEmpty() }?.let(directory.display)
            Triple(directory, key, cached)
        }
        if (files.isEmpty() && pendingAuthors.isEmpty() && directories.isEmpty()) return
        batch = worker.submit {
            for ((key, display) in files) {
                if (Thread.currentThread().isInterrupted) return@submit
                val bitmap = load(key)
                if (bitmap != null) main.post { if (request == generation) display(bitmap) }
            }
            for ((item, display) in pendingAuthors) {
                if (Thread.currentThread().isInterrupted) return@submit
                val author = authors.get(item) ?: readAuthor(item)?.also {
                    if (Thread.currentThread().isInterrupted) return@submit
                    authors.put(item, it)
                }
                author?.name?.let { name -> main.post { if (request == generation) display(name) } }
            }
            for ((directory, key, cached) in directories) {
                if (Thread.currentThread().isInterrupted) return@submit
                val children = FolderAccess.contents(directory.location) as? FolderContents.Items
                val items = children?.entries.orEmpty().orderedBy(sort, opened).asSequence()
                    .filterNot { it.isDirectory }.take(4).toList()
                if (cached?.items == items) continue
                val faces = ArrayList<Face>(items.size)
                for (item in items) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    val image = item.coverKey(MOSAIC_EDGE)?.let { thumb ->
                        val large = cache.get(thumb.copy(edge = THUMBNAIL_EDGE))
                        if (large != null) large.bitmap else load(thumb)
                    }
                    faces.add(Face(item.location.name, image))
                }
                if (Thread.currentThread().isInterrupted) return@submit
                mosaics.put(key, Mosaic(items, faces))
                if (faces.isNotEmpty() || cached?.faces?.isNotEmpty() == true) {
                    main.post { if (request == generation) directory.display(faces) }
                }
            }
        }
    }

    fun wallpaperCover(item: FolderItem, edge: Int): Bitmap? {
        val key = item.coverKey(edge.coerceIn(THUMBNAIL_EDGE, 640)) ?: return null
        val regular = key.copy(edge = THUMBNAIL_EDGE)
        // Don't displace normal thumbnails with larger transient wallpaper artwork.
        return if (key == regular) load(regular) else thumbnail(key) ?: load(regular)
    }

    private fun FolderItem.coverKey(edge: Int): Key? = coverFormat?.let { Key(location, lastModified, size, it, edge) }

    private fun load(key: Key): Bitmap? {
        cache.get(key)?.let { return it.bitmap }
        val diskKey = key.diskKey()
        val stored = if (diskKey == null) null else disk.read(diskKey)
        val image = stored ?: thumbnail(key)
        if (Thread.currentThread().isInterrupted) return null
        cache.put(key, Preview(image))
        if (stored == null && image != null && diskKey != null) disk.write(diskKey, image)
        return image
    }

    fun close() {
        generation++
        batch?.cancel(true)
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun thumbnail(key: Key): Bitmap? = try {
        FolderAccess.book(key.location)?.let { file ->
            when (key.format) {
                CoverFormat.EPUB -> epubCover(file) { decodeThumbnail(it, key.edge) }
                CoverFormat.PDF -> pdfCover(file, key.edge)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: XmlPullParserException) {
        null
    } catch (_: RuntimeException) {
        // Unreadable, protected, or no layout-safe cover; leave the file glyph visible.
        null
    }

    private fun readAuthor(item: FolderItem): Author? = try {
        FolderAccess.book(item.location)?.let { file ->
            Author(when (item.coverFormat) {
                CoverFormat.EPUB -> epubAuthor(file)
                CoverFormat.PDF -> pdfAuthor(file)
                null -> null
            })
        }
    } catch (_: IOException) {
        null
    } catch (_: XmlPullParserException) {
        Author(null)
    } catch (_: RuntimeException) {
        null
    }

    private fun pdfAuthor(file: File): String? {
        if (file.length() !in 1..64L * 1024 * 1024) return null
        PDFBoxResourceLoader.init(context.applicationContext)
        val memory = MemoryUsageSetting.setupMixed(2L * 1024 * 1024, 32L * 1024 * 1024).setTempDir(context.cacheDir)
        return PDDocument.load(file, memory).use { document ->
            document.documentInformation.author?.trim()?.takeIf { it.length in 1..256 }
        }
    }

    private fun pdfCover(file: File, edge: Int): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try {
            PdfRenderer(descriptor) // Owns the descriptor only after a successful construction.
        } catch (failure: Exception) {
            descriptor.close()
            throw failure
        }
        return pdf.use { renderer ->
            if (renderer.pageCount == 0) return@use null
            renderer.openPage(0).use { page ->
                val (width, height) = previewSize(page.width, page.height, edge)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    private fun decodeThumbnail(bytes: ByteArray, edge: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth < 1 || options.outHeight < 1) return null
        val (width, height) = previewSize(options.outWidth, options.outHeight, edge)
        val longest = maxOf(options.outWidth, options.outHeight)
        var sample = 1
        while (longest / sample >= 2 * edge) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true).also { bitmap.recycle() }
    }

    private fun previewSize(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        val scale = minOf(1.0, edge.toDouble() / maxOf(width, height).coerceAtLeast(1))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private companion object {
        const val THUMBNAIL_EDGE = 320
        const val MOSAIC_EDGE = THUMBNAIL_EDGE / 2
    }
}
