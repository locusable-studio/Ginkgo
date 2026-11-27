package com.locusable.ginkgo.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** A single-stroke set that stays legible on monochrome displays. */
internal class InkGlyph(
    context: Context,
    private val kind: Kind,
    private val sizeDp: Int = 22,
    tone: Int = INK,
) : View(context) {
    enum class Kind { APP, HOME, FILE, DOCUMENT, BOOK, WALLPAPER, SETTINGS, SORT, REFRESH, PIN, EYE_OFF, ARROW, LEFT, RIGHT }

    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tone
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height, context.dp(sizeDp)).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24, size / 24)
        when (kind) {
            Kind.APP -> {
                canvas.drawRoundRect(3.5f, 3.5f, 20.5f, 20.5f, 3.5f, 3.5f, pen)
                for (column in 0..1) for (row in 0..1) {
                    val x = 7f + column * 6f
                    val y = 7f + row * 6f
                    canvas.drawRoundRect(x, y, x + 4f, y + 4f, 0.75f, 0.75f, pen)
                }
            }
            Kind.HOME -> stroke(canvas, 3, 11, 12, 4, 21, 11, 19, 11, 19, 20, 14, 20, 14, 15, 10, 15, 10, 20, 5, 20, 5, 11, 3, 11)
            Kind.FILE -> {
                stroke(canvas, 3, 18, 3, 6, 9, 6, 11, 8, 20, 8, 20, 11)
                stroke(canvas, 3, 18, 6, 11, 22, 11, 19, 19, 4, 19, 3, 18)
                canvas.drawLine(8f, 15f, 12f, 15f, pen)
            }
            Kind.DOCUMENT -> {
                stroke(canvas, 5, 3, 14, 3, 19, 8, 19, 21, 5, 21, 5, 3)
                stroke(canvas, 14, 3, 14, 8, 19, 8)
                canvas.drawLine(8f, 12f, 16f, 12f, pen)
                canvas.drawLine(8f, 16f, 14f, 16f, pen)
            }
            Kind.BOOK -> {
                path.rewind()
                path.moveTo(12f, 6.5f)
                path.cubicTo(9f, 4f, 6f, 4f, 3f, 5f)
                path.lineTo(3f, 17f)
                path.cubicTo(6f, 16f, 9f, 16f, 12f, 19f)
                path.cubicTo(15f, 16f, 18f, 16f, 21f, 17f)
                path.lineTo(21f, 5f)
                path.cubicTo(18f, 4f, 15f, 4f, 12f, 6.5f)
                path.close()
                canvas.drawPath(path, pen)
                canvas.drawLine(12f, 6.5f, 12f, 19f, pen)
                stroke(canvas, 6, 9, 9, 10)
                stroke(canvas, 15, 10, 18, 9)
            }
            Kind.WALLPAPER -> {
                canvas.drawRoundRect(3.5f, 4.5f, 20.5f, 19.5f, 2f, 2f, pen)
                canvas.drawCircle(15.5f, 9f, 1.4f, pen)
                stroke(canvas, 5, 17, 9, 12, 13, 16, 16, 13, 19, 16)
            }
            Kind.SETTINGS -> {
                canvas.drawLine(4f, 8f, 20f, 8f, pen)
                canvas.drawLine(4f, 16f, 20f, 16f, pen)
                canvas.drawLine(9f, 5f, 9f, 11f, pen)
                canvas.drawLine(15f, 13f, 15f, 19f, pen)
            }
            Kind.SORT -> {
                canvas.drawLine(4f, 7f, 20f, 7f, pen)
                canvas.drawLine(4f, 12f, 15f, 12f, pen)
                canvas.drawLine(4f, 17f, 10f, 17f, pen)
            }
            Kind.REFRESH -> {
                canvas.drawArc(4.5f, 4.5f, 19.5f, 19.5f, -38f, 306f, false, pen)
                stroke(canvas, 12, 2, 16, 5, 12, 8)
            }
            Kind.PIN -> {
                stroke(canvas, 8, 5, 16, 5, 15, 10, 18, 14, 6, 14, 9, 10, 8, 5)
                canvas.drawLine(12f, 14f, 12f, 20f, pen)
            }
            Kind.EYE_OFF -> {
                path.rewind()
                path.moveTo(7.4f, 7.4f)
                path.cubicTo(4.6f, 9.2f, 3f, 12f, 3f, 12f)
                path.cubicTo(3f, 12f, 6.5f, 18f, 12f, 18f)
                path.cubicTo(13.8f, 18f, 15.3f, 17.4f, 16.6f, 16.6f)
                path.moveTo(11f, 6.1f)
                path.cubicTo(16.9f, 5.4f, 21f, 12f, 21f, 12f)
                path.cubicTo(21f, 12f, 20.2f, 13.4f, 19.1f, 14.4f)
                canvas.drawPath(path, pen)
                canvas.drawArc(9.2f, 9.2f, 14.8f, 14.8f, 45f, 180f, false, pen)
                canvas.drawLine(4f, 4f, 20f, 20f, pen)
            }
            Kind.LEFT -> stroke(canvas, 14, 7, 9, 12, 14, 17)
            Kind.RIGHT -> stroke(canvas, 10, 7, 15, 12, 10, 17)
            Kind.ARROW -> {
                stroke(canvas, 8, 16, 16, 8)
                stroke(canvas, 10, 8, 16, 8, 16, 14)
            }
        }
        canvas.restore()
    }

    private fun stroke(canvas: Canvas, vararg points: Int) {
        path.rewind()
        path.moveTo(points[0].toFloat(), points[1].toFloat())
        for (i in 2 until points.size step 2) path.lineTo(points[i].toFloat(), points[i + 1].toFloat())
        canvas.drawPath(path, pen)
    }
}
