package com.locusable.ginkgo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal object ShelfWallpaper {
    private const val ANGLE = 15f
    private const val DIRECTORY = "Wallpaper"
    private const val MAX_PIXELS = 8_000_000L

    data class Layout(val width: Int, val height: Int, val slots: List<RectF>, val coverEdge: Int)

    /** Only include tiles that intersect the visible canvas, not all tiles in the rotated bounding box. */
    fun layout(width: Int, height: Int): Layout {
        require(width in 120..8192 && height in 120..8192 && width.toLong() * height <= MAX_PIXELS)
        val shortEdge = minOf(width, height).toFloat()
        val faceWidth = shortEdge / 2.85f
        val faceHeight = faceWidth * 1.5f
        val stepX = faceWidth + shortEdge * 0.012f
        val stepY = faceHeight + shortEdge * 0.012f
        val angle = Math.toRadians(ANGLE.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val canvasX = width / 2f
        val canvasY = height / 2f
        val rotatedCanvasX = c * canvasX + s * canvasY
        val rotatedCanvasY = s * canvasX + c * canvasY
        val halfW = faceWidth / 2f
        val halfH = faceHeight / 2f
        val rotatedFaceX = c * halfW + s * halfH
        val rotatedFaceY = s * halfW + c * halfH
        val columns = ceil((rotatedCanvasX + halfW) / stepX + 0.5f).toInt()
        val rows = ceil((rotatedCanvasY + halfH) / stepY).toInt()
        val slots = buildList {
            for (row in -rows..rows) for (column in -columns..columns) {
                val x = (column + 0.5f) * stepX
                val y = row * stepY
                if (abs(x) >= rotatedCanvasX + halfW || abs(y) >= rotatedCanvasY + halfH) continue
                if (abs(c * x - s * y) >= canvasX + rotatedFaceX || abs(s * x + c * y) >= canvasY + rotatedFaceY) continue
                add(RectF(canvasX + x - halfW, canvasY + y - halfH, canvasX + x + halfW, canvasY + y + halfH))
            }
        }
        return Layout(width, height, slots, ceil(faceHeight).toInt())
    }

    fun composeRecent(layout: Layout, recent: List<ShelfEntry>, coverFor: (ShelfEntry) -> Bitmap?): Bitmap? {
        val covers = ArrayList<Bitmap>(layout.slots.size)
        for (entry in recent) {
            requireNotInterrupted()
            coverFor(entry)?.let(covers::add)
            if (covers.size == layout.slots.size) break
        }
        return if (covers.isEmpty()) null else compose(layout, covers)
    }

    fun compose(layout: Layout, covers: List<Bitmap>): Bitmap {
        require(covers.isNotEmpty())
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFFF7F7F7.toInt())
            canvas.rotate(ANGLE, layout.width / 2f, layout.height / 2f)
            val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            val faceWidth = layout.slots.first().width()
            val radius = faceWidth * 0.036f
            val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                setShadowLayer(faceWidth * 0.017f, 0f, faceWidth * 0.008f, 0x33000000)
            }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFBFBFBF.toInt()
                strokeWidth = 0.8f
                style = Paint.Style.STROKE
            }
            layout.slots.forEachIndexed { index, rect ->
                requireNotInterrupted()
                val cover = covers[index % covers.size]
                val scale = maxOf(rect.width() / cover.width, rect.height() / cover.height)
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(rect.centerX() - cover.width * scale / 2, rect.centerY() - cover.height * scale / 2)
                }
                art.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
                canvas.drawRoundRect(rect, radius, radius, paper)
                canvas.drawRoundRect(rect, radius, radius, art)
                canvas.drawRoundRect(rect, radius, radius, edge)
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    @Suppress("DEPRECATION") // The target reader supports a top-level Wallpaper directory.
    fun save(context: Context, bitmap: Bitmap): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT))
        val name = "Ginkgo-$stamp-${UUID.randomUUID().toString().take(8)}.png"
        if (Build.VERSION.SDK_INT < 29) {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val directory = File(root, DIRECTORY)
            if (directory.canonicalFile != directory) throw IOException("Invalid wallpaper directory")
            val saved = writeFile(directory, name, bitmap)
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(saved.path), arrayOf("image/png"), null) }
            return "$DIRECTORY/$name"
        }
        // Scoped storage only allows image collections under approved media directories.
        val directory = "${Environment.DIRECTORY_PICTURES}/$DIRECTORY"
        val resolver = context.contentResolver
        requireNotInterrupted()
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "$directory/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: throw IOException("Cannot create wallpaper")
        var saved = false
        try {
            resolver.openOutputStream(uri)?.use { writePng(it, bitmap) } ?: throw IOException("Cannot open wallpaper")
            requireNotInterrupted()
            saved = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) == 1
            if (!saved) throw IOException("Cannot publish wallpaper")
        } finally {
            if (!saved) resolver.delete(uri, null, null)
        }
        return "$directory/$name"
    }

    internal fun writeFile(directory: File, name: String, bitmap: Bitmap): File {
        requireNotInterrupted()
        if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) throw IOException("Cannot create wallpaper directory")
        val target = File(directory, name)
        val temporary = File.createTempFile(".ginkgo-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use {
                writePng(it, bitmap)
                it.fd.sync()
            }
            requireNotInterrupted()
            Files.move(temporary.toPath(), target.toPath()) // Never replace an existing export.
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun writePng(stream: OutputStream, bitmap: Bitmap) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Cannot encode wallpaper")
    }

    fun requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
