package com.locusable.ginkgo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Best-effort private thumbnail cache, accessed only by the shelf worker. */
internal class CoverDiskCache(context: Context, name: String = "shelf-covers-v1") {
    private val directory = File(context.cacheDir, name)
    private var bytes: Long? = null

    fun read(key: String): Bitmap? = try {
        val file = coverFile(key)
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path).also { image ->
                if (image != null) file.setLastModified(System.currentTimeMillis())
                else if (file.delete()) bytes = null
            }
        } else null
    } catch (_: SecurityException) {
        null
    }

    fun write(key: String, bitmap: Bitmap) {
        var pending: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val file = coverFile(key)
            val previousSize = file.length()
            val temporary = File.createTempFile("cover-", ".tmp", directory)
            pending = temporary
            val encoded = temporary.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!encoded || !temporary.renameTo(file)) return
            val total = bytes?.let { it - previousSize + file.length() }
            bytes = if (total != null && total <= MAX_BYTES) total else trim()
        } catch (_: IOException) {
            // A full or cleared cache must not stop covers from being shown.
        } catch (_: SecurityException) {
            // Treat unavailable cache storage as a cache miss.
        } finally {
            try {
                pending?.delete()
            } catch (_: SecurityException) {
                // Cleanup is best effort as well.
            }
        }
    }

    fun clear() {
        try {
            directory.deleteRecursively()
        } catch (_: SecurityException) {
            // This is a best-effort cleanup of regenerable thumbnails.
        }
        bytes = null
    }

    private fun coverFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return File(directory, "$name.png")
    }

    private fun trim(): Long {
        val files = directory.listFiles().orEmpty().filter { file ->
            if (file.name.endsWith(".tmp")) file.delete() // An earlier process may have been interrupted mid-write.
            file.name.endsWith(".png") && file.isFile
        }
        var total = files.sumOf { it.length() }
        if (total > MAX_BYTES) {
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
        return total
    }

    private companion object {
        const val MAX_BYTES = 24L * 1024 * 1024
    }
}
