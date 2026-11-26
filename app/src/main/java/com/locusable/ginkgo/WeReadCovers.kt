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

/** Cache decoded thumbnails only, shared by official and uploaded books and scoped to the account. */
internal class WeReadCovers private constructor(context: Context) {
    class Target(val book: WeReadEntry.Book, private val draw: (Bitmap) -> Unit) {
        private var displayed: Bitmap? = null

        fun display(bitmap: Bitmap) {
            if (displayed === bitmap) return
            displayed = bitmap
            draw(bitmap)
        }
    }

    private data class Source(val key: String, val uri: URI?)
    private val cacheLock = Any()
    @Volatile private var cacheVersion = 0
    @Volatile private var diskVersion = 0

    private val disk = CoverDiskCache(context, "weread-covers-v1")
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    // A cancelled HTTPS call can still occupy its worker until the socket times out.
    private val cacheWorker = Executors.newSingleThreadExecutor()
    private val downloadWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cacheBatch: Future<*>? = null
    private var downloadBatch: Future<*>? = null
    private var generation = 0
    private var owner: Any? = null

    fun show(owner: Any, account: String?, session: WeReadWeb.Session?, targets: List<Target>) {
        stopBatch()
        this.owner = owner
        val request = generation
        val version = cacheVersion
        if (account == null || targets.isEmpty()) return
        val pending = targets.groupBy { it.book }.mapNotNull { (book, destinations) ->
            val source = source(account, book) ?: return@mapNotNull null
            val hit = memory.get(source.key)
            if (hit != null) {
                destinations.forEach { it.display(hit) }
                null
            } else Triple(source.key, source.uri, destinations)
        }
        if (pending.isEmpty()) return
        fun isCurrent() = generation == request && cacheVersion == version
        fun publish(bitmap: Bitmap, destinations: List<Target>) {
            main.post { if (isCurrent()) destinations.forEach { it.display(bitmap) } }
        }
        cacheBatch = cacheWorker.submit {
            val missing = pending.filter { (key, _, destinations) ->
                if (Thread.currentThread().isInterrupted || version != cacheVersion) return@submit
                val cached = memory.get(key) ?: readDisk(version, key)
                if (cached != null) publish(cached, destinations)
                cached == null
            }
            if (missing.isEmpty()) return@submit
            main.post {
                if (!isCurrent()) return@post
                val activeSession = session?.takeIf { it.account == account }
                downloadBatch = downloadWorker.submit {
                    for ((key, uri, destinations) in missing) {
                        if (Thread.currentThread().isInterrupted || version != cacheVersion) break
                        // A previous request may have succeeded while this one was queued.
                        val cached = memory.get(key) ?: readDisk(version, key)
                        if (cached != null) {
                            publish(cached, destinations)
                            continue
                        }
                        val bitmap = fetchBookCover(destinations.first().book, uri, activeSession) ?: continue
                        // Preserve successful images even if their original views have gone away.
                        if (remember(version, key, bitmap, save = true)) publish(bitmap, destinations)
                    }
                }
            }
        }
    }

    fun hide(owner: Any) {
        if (this.owner === owner) stopBatch()
    }

    fun clear() {
        stopBatch()
        synchronized(cacheLock) {
            val version = ++cacheVersion
            memory.evictAll()
            cacheWorker.execute {
                disk.clear()
                diskVersion = version
            }
        }
    }

    private fun readDisk(version: Int, key: String): Bitmap? {
        // Wallpaper reads can run concurrently with a queued account-cache clear.
        if (version != cacheVersion || version != diskVersion) return null
        val bitmap = disk.read(key) ?: return null
        return bitmap.takeIf { remember(version, key, it, save = false) }
    }

    private fun remember(version: Int, key: String, bitmap: Bitmap, save: Boolean): Boolean = synchronized(cacheLock) {
        if (version != cacheVersion) return@synchronized false
        memory.put(key, bitmap)
        if (save) cacheWorker.execute { if (version == cacheVersion) disk.write(key, bitmap) }
        true
    }

    private fun source(account: String, book: WeReadEntry.Book): Source? {
        val uri = book.cover?.let(WeReadCoverUrl::normalize)
        // Uploaded images are cached by book, not by transient image signatures.
        val key = if (book.isUploaded) "$account/${book.id}/uploaded"
            else uri?.let { "$account/${book.id}/$it" } ?: return null
        return Source(key, uri)
    }

    fun wallpaperSource(account: String?, session: WeReadWeb.Session?): (WeReadEntry.Book, Long) -> Bitmap? {
        val version = cacheVersion
        val active = session?.takeIf { it.account == account }
        return lookup@{ book, deadline ->
            if (account == null || version != cacheVersion || Thread.currentThread().isInterrupted) return@lookup null
            val source = source(account, book) ?: return@lookup null
            val cached = memory.get(source.key) ?: readDisk(version, source.key)
            if (cached != null) return@lookup cached.takeIf { version == cacheVersion }
            val bitmap = fetchBookCover(book, source.uri, active, deadline) ?: return@lookup null
            bitmap.takeIf { !Thread.currentThread().isInterrupted && remember(version, source.key, it, save = true) }
        }
    }

    private fun stopBatch() {
        owner = null
        generation++
        cacheBatch?.cancel(true)
        cacheBatch = null
        downloadBatch?.cancel(true)
        downloadBatch = null
    }

    private fun fetchBookCover(
        book: WeReadEntry.Book, primary: URI?, session: WeReadWeb.Session?, deadline: Long = Long.MAX_VALUE,
    ): Bitmap? {
        if (!book.isUploaded) return primary?.let { fetch(it, deadline) }
        if (session == null) return null
        val attempted = mutableSetOf<URI>()
        fun tryCover(uri: URI?): Bitmap? =
            if (uri != null && attempted.add(uri) && !Thread.currentThread().isInterrupted) fetch(uri, deadline) else null

        tryCover(primary)?.let { return it }
        tryCover(WeReadCoverUrl.uploadedCover(book.id))?.let { return it }
        val timeout = timeoutFor(deadline, 5_000) ?: return null
        val detail = try { WeReadWeb.uploadedBookCover(session, book.id, timeout) } catch (_: Exception) { null }
        return tryCover(detail?.let(WeReadCoverUrl::normalize))
    }

    private fun timeoutFor(deadline: Long, normal: Int): Int? {
        if (Thread.currentThread().isInterrupted) return null
        if (deadline == Long.MAX_VALUE) return normal
        val remaining = deadline - SystemClock.elapsedRealtime()
        return remaining.takeIf { it > 0 }?.coerceAtMost(2_500L)?.toInt()
    }

    private fun fetch(initial: URI, deadline: Long): Bitmap? = try {
        var uri = initial
        var bitmap: Bitmap? = null
        for (hop in 0..3) {
            val timeout = timeoutFor(deadline, 4_000) ?: break
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
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
