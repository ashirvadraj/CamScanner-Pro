package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Merges front and back sides of an ID card onto a single standard A4 document.
 */
object IdCardMerger {

    // Standard A4 canvas dimensions
    private const val CANVAS_W = 1600
    private const val CANVAS_H = 2262

    fun mergeIdCards(front: Bitmap, back: Bitmap): Bitmap {
        val merged = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)

        // Clean white document background
        canvas.drawColor(Color.WHITE)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C1E")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00A86B")
            style = Paint.Style.FILL
        }

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCCCCC")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
        }

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw Document Header
        canvas.drawText("IDENTITY CARD / DOCUMENT SCAN", CANVAS_W / 2f, 100f, textPaint)
        canvas.drawLine(120f, 130f, CANVAS_W - 120f, 130f, borderPaint)

        // Standard ID card aspect ratio is 85.6mm : 53.98mm ~= 1.586
        val cardW = 1200f
        val cardH = cardW / 1.586f // ~756px
        val marginX = (CANVAS_W - cardW) / 2f

        // 1. FRONT CARD SECTION
        val frontTop = 220f
        val frontBox = RectF(marginX, frontTop, marginX + cardW, frontTop + cardH)
        canvas.drawRoundRect(frontBox, 24f, 24f, borderPaint)

        // Badge: FRONT SIDE
        val badgeW = 200f
        val badgeH = 44f
        val frontBadgeRect = RectF(marginX + 20f, frontTop - 22f, marginX + 20f + badgeW, frontTop + 22f)
        canvas.drawRoundRect(frontBadgeRect, 12f, 12f, badgePaint)
        canvas.drawText("FRONT SIDE", frontBadgeRect.centerX(), frontBadgeRect.centerY() + 10f, badgeTextPaint)

        // Draw Front Image inside box
        drawCardImageInsideBox(canvas, front, frontBox, bitmapPaint)

        // 2. BACK CARD SECTION
        val backTop = frontTop + cardH + 160f
        val backBox = RectF(marginX, backTop, marginX + cardW, backTop + cardH)
        canvas.drawRoundRect(backBox, 24f, 24f, borderPaint)

        // Badge: BACK SIDE
        val backBadgeRect = RectF(marginX + 20f, backTop - 22f, marginX + 20f + badgeW, backTop + 22f)
        canvas.drawRoundRect(backBadgeRect, 12f, 12f, badgePaint)
        canvas.drawText("BACK SIDE", backBadgeRect.centerX(), backBadgeRect.centerY() + 10f, badgeTextPaint)

        // Draw Back Image inside box
        drawCardImageInsideBox(canvas, back, backBox, bitmapPaint)

        // Draw Document Footer
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#888888")
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText("Scanned with CamScanner Pro • $dateStr", CANVAS_W / 2f, CANVAS_H - 80f, footerPaint)

        return merged
    }

    private fun drawCardImageInsideBox(
        canvas: Canvas,
        cardBm: Bitmap,
        box: RectF,
        paint: Paint
    ) {
        val innerPadding = 12f
        val availW = box.width() - innerPadding * 2
        val availH = box.height() - innerPadding * 2

        val scale = min(availW / cardBm.width, availH / cardBm.height)
        val drawnW = cardBm.width * scale
        val drawnH = cardBm.height * scale

        val left = box.left + innerPadding + (availW - drawnW) / 2f
        val top = box.top + innerPadding + (availH - drawnH) / 2f

        val destRect = RectF(left, top, left + drawnW, top + drawnH)
        val srcRect = Rect(0, 0, cardBm.width, cardBm.height)
        canvas.drawBitmap(cardBm, srcRect, destRect, paint)
    }
}
