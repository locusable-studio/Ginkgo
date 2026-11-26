package com.locusable.ginkgo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

internal enum class CoverFormat { EPUB, PDF }

/** One background queue, with bounded thumbnail and mosaic caches for the shelf. */
internal class ShelfCovers(private val context: Context) {
    sealed interface Target {
        data class File(val item: FolderItem, val display: (Bitmap) -> Unit) : Target
        data class Directory(val location: FolderLocation, val display: (List<Face>) -> Unit) : Target
    }
    data class Face(val name: String, val bitmap: Bitmap?)
    private data class Key(
        val location: FolderLocation, val modified: Long, val size: Long, val format: CoverFormat, val edge: Int,
    ) {
        fun diskKey(): String? = if (modified > 0) "$edge:$format:$modified:$size:${location.id}" else null
    }
    private data class Preview(val bitmap: Bitmap?)
    private data class MosaicKey(val location: String, val sort: FolderSort, val openRevision: Int)
    private data class Mosaic(val items: List<FolderItem>, val faces: List<Face>)

    private val cache = object : LruCache<Key, Preview>(6 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Preview) = value.bitmap?.allocationByteCount ?: 512
    }
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
        targets: List<Target>, sort: FolderSort, opened: Map<String, Long>,
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
        val directories = targets.filterIsInstance<Target.Directory>().map { directory ->
            val revision = if (sort == FolderSort.LAST_OPENED) openRevision(directory.location) else 0
            val key = MosaicKey(directory.location.id, sort, revision)
            val cached = mosaics.get(key)
            cached?.faces?.takeIf { it.isNotEmpty() }?.let(directory.display)
            Triple(directory, key, cached)
        }
        if (files.isEmpty() && directories.isEmpty()) return
        batch = worker.submit {
            for ((key, display) in files) {
                if (Thread.currentThread().isInterrupted) return@submit
                val bitmap = load(key)
                if (bitmap != null) main.post { if (request == generation) display(bitmap) }
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
