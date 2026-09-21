package com.camscanner.pro.core.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.camscanner.pro.core.storage.FileManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

enum class PageSize(val displayName: String, val width: Int, val height: Int) {
    A4("A4 Standard (595 × 842 pt)", 595, 842),
    US_LETTER("US Letter (612 × 792 pt)", 612, 792),
    FIT_IMAGE("Fit to Image (No Border)", 0, 0)
}

enum class PdfQuality(val displayName: String, val maxDim: Int) {
    HIGH("High Quality (Original)", 1920),
    MEDIUM("Medium (Standard)", 1280),
    COMPACT("Compact (Email Size)", 800)
}

data class PdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val quality: PdfQuality = PdfQuality.HIGH,
    val watermarkText: String? = null
)

object PdfGenerator {

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842

    fun generatePdf(
        context: Context,
        imagePaths: List<String>,
        documentTitle: String = "Scanned_Document",
        options: PdfOptions = PdfOptions()
    ): File {
        val pdfDocument = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            imagePaths.forEachIndexed { index, path ->
                val bitmap = FileManager.loadSampledBitmap(path, maxDim = options.quality.maxDim) ?: return@forEachIndexed

                val (pageW, pageH) = if (options.pageSize == PageSize.FIT_IMAGE) {
                    Pair(bitmap.width, bitmap.height)
                } else {
                    Pair(options.pageSize.width, options.pageSize.height)
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                if (options.pageSize == PageSize.FIT_IMAGE) {
                    val destRect = Rect(0, 0, pageW, pageH)
                    val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, srcRect, destRect, paint)
                } else {
                    // Margin calculation
                    val margin = 20
                    val availW = pageW - (margin * 2)
                    val availH = pageH - (margin * 2)

                    val scale = min(
                        availW.toFloat() / bitmap.width,
                        availH.toFloat() / bitmap.height
                    )

                    val drawnW = (bitmap.width * scale).toInt()
                    val drawnH = (bitmap.height * scale).toInt()

                    val left = margin + (availW - drawnW) / 2
                    val top = margin + (availH - drawnH) / 2

                    val destRect = Rect(left, top, left + drawnW, top + drawnH)
                    val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, srcRect, destRect, paint)
                }

                // Draw diagonal watermark text if specified
                if (!options.watermarkText.isNullOrBlank()) {
                    canvas.save()
                    canvas.translate(pageW / 2f, pageH / 2f)
                    canvas.rotate(-35f)
                    val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#33888888")
                        textSize = (pageW * 0.08f).coerceIn(24f, 56f)
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    canvas.drawText(options.watermarkText, 0f, 0f, watermarkPaint)
                    canvas.restore()
                }

                pdfDocument.finishPage(page)
                bitmap.recycle()
            }

            val sanitizedTitle = documentTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val pdfFile = File(FileManager.getDocumentsDir(context), "${sanitizedTitle}_${timeStamp}.pdf")

            FileOutputStream(pdfFile).use { out ->
                pdfDocument.writeTo(out)
            }

            return pdfFile
        } finally {
            pdfDocument.close()
        }
    }
}
