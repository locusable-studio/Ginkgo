package com.locusable.ginkgo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection

/** Public thumbnails use the local shelf cache limits; uploaded covers live only in the displayed view. */
internal class WeReadCovers private constructor(context: Context) {
    data class Target(val book: WeReadEntry.Book, val display: (Bitmap) -> Unit)

    private val disk = CoverDiskCache(context, "weread-covers-v1")
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failed = LruCache<String, Long>(128)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var batch: Future<*>? = null
    private var generation = 0
    private var owner: Any? = null

    fun show(owner: Any, account: String?, session: WeReadWeb.Session?, targets: List<Target>) {
        stopBatch()
        this.owner = owner
        val request = generation
        if (account == null || targets.isEmpty()) return
        val byBook = targets.groupBy { it.book }
        val pending = byBook.mapNotNull { (book, destinations) ->
            val uri = book.cover?.let(WeReadCoverUrl::normalize)
            if (uri == null && (!book.isUploaded || session?.account != account)) return@mapNotNull null
            val key = uri?.takeUnless { book.isUploaded }?.let { "$account/${book.id}/$it" }
            val hit = key?.let(memory::get)
            if (hit != null) {
                destinations.forEach { it.display(hit) }
                null
            } else Triple(key, uri, destinations)
        }
        if (pending.isEmpty()) return
        batch = worker.submit {
            fun publish(key: String?, bitmap: Bitmap, destinations: List<Target>) {
                if (key != null) memory.put(key, bitmap)
                main.post { if (generation == request) destinations.forEach { it.display(bitmap) } }
            }
            val missing = pending.filter { (key, _, destinations) ->
                if (Thread.currentThread().isInterrupted) return@submit
                val cached = key?.let(disk::read)
                if (cached != null) publish(key, cached, destinations)
                cached == null
            }
            for ((key, uri, destinations) in missing) {
                if (Thread.currentThread().isInterrupted) break
                val now = SystemClock.elapsedRealtime()
                if (key != null && (failed.get(key) ?: -1L) > now) continue
                val book = destinations.first().book
                val bitmap = uri?.let(::fetch) ?: if (session?.account == account && book.isUploaded) {
                    val detail = try { WeReadWeb.uploadedBookCover(session, book.id) } catch (_: Exception) { null }
                    detail?.let(WeReadCoverUrl::normalize)?.takeUnless { it == uri }?.let(::fetch)
                } else null
                if (Thread.currentThread().isInterrupted) break
                if (bitmap != null) {
                    if (key != null) disk.write(key, bitmap)
                    publish(key, bitmap, destinations)
                } else if (key != null) {
                    failed.put(key, now + 12_000)
                }
            }
        }
    }

    fun hide(owner: Any) {
        if (this.owner === owner) stopBatch()
    }

    fun clear() {
        stopBatch()
        memory.evictAll()
        failed.evictAll()
        worker.execute(disk::clear)
    }

    private fun stopBatch() {
        owner = null
        generation++
        batch?.cancel(true)
        batch = null
    }

    private fun fetch(initial: URI): Bitmap? = try {
        var uri = initial
        var bitmap: Bitmap? = null
        for (hop in 0..3) {
            if (Thread.currentThread().isInterrupted) break
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.setRequestProperty("Accept", "image/*, application/octet-stream;q=0.8")
                connection.setRequestProperty("Referer", "$WEREAD_ORIGIN/")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    uri = connection.getHeaderField("Location")?.let { WeReadCoverUrl.redirect(uri, it) } ?: break
                    continue
                }
                if (status == 200 && isImageType(connection.contentType)) {
                    val bytes = connection.inputStream.use { it.readLimited(4 * 1024 * 1024) }
                    bitmap = thumbnail(bytes)
                }
                break
            } finally {
                connection.disconnect()
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }

    private fun isImageType(value: String?): Boolean {
        val mime = value?.substringBefore(';')?.trim()?.lowercase()
        return mime == null || mime.startsWith("image/") || mime == "application/octet-stream" || mime == "binary/octet-stream"
    }

    private fun thumbnail(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192) return null
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 384) options.inSampleSize *= 2
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        private var instance: WeReadCovers? = null
        fun get(context: Context): WeReadCovers = instance ?: WeReadCovers(context.applicationContext).also { instance = it }

    }
}
