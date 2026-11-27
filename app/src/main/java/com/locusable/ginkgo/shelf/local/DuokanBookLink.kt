package com.locusable.ginkgo.shelf.local

import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object DuokanBookLink {
    private const val PACKAGE = "com.duokan.einkreader"
    private const val CHUNK = 20 * 1024
    private const val MAX_NATIVE_SIZE = 0xffffffffL
    private const val NATIVE_DIGITS = "0123456789bcdefg"

    fun intent(id: String): Intent = Intent(Intent.ACTION_VIEW, Uri.Builder()
        .scheme("duokan-reader").authority("read").appendPath("book").appendPath(id).build())
        .setPackage(PACKAGE)

    /** Matches the local-book ID used by Duokan 1.2.7 on EPD106, not a security hash. */
    fun idFor(file: File): String? = try {
        val modified = file.lastModified()
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            if (size !in 1..MAX_NATIVE_SIZE) return@use null

            val sha1 = MessageDigest.getInstance("SHA-1")
            val bytes = ByteArray(minOf(size, (3 * CHUNK).toLong()).toInt())
            if (size <= bytes.size) {
                input.readFully(bytes)
                sha1.update(bytes)
            } else {
                for (offset in longArrayOf(0, size / 3, size - CHUNK)) {
                    input.seek(offset)
                    input.readFully(bytes, 0, CHUNK)
                    sha1.update(bytes, 0, CHUNK)
                }
            }
            if (file.length() != size || file.lastModified() != modified) return@use null

            val md5 = MessageDigest.getInstance("MD5")
            md5.update(sha1.digest())
            val encoded = md5.digest(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
            buildString(encoded.size * 2) {
                for (byte in encoded) {
                    append(NATIVE_DIGITS[(byte.toInt() and 0xff) shr 4])
                    append(NATIVE_DIGITS[byte.toInt() and 0xf])
                }
                val first = this[0]
                setCharAt(0, first + if (first in '0'..'9') 55 else 16)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
