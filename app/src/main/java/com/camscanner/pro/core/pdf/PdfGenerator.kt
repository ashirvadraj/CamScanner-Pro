package com.camscanner.pro.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.camscanner.pro.core.storage.FileManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object PdfGenerator {

    // Standard A4 dimensions in PDF points (72 points per inch)
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842

    fun generatePdf(
        context: Context,
        imagePaths: List<String>,
        documentTitle: String = "Scanned_Document"
    ): File {
        val pdfDocument = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            imagePaths.forEachIndexed { index, path ->
                val bitmap = FileManager.loadSampledBitmap(path, maxDim = 1600) ?: return@forEachIndexed

                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Calculate aspect ratio fit inside A4 page with 20pt margin
                val margin = 20
                val availW = PAGE_WIDTH - (margin * 2)
                val availH = PAGE_HEIGHT - (margin * 2)

                val scale = min(
                    availW.toFloat() / bitmap.width,
                    availH.toFloat() / bitmap.height
                )

                val drawnW = (bitmap.width * scale).toInt()
                val drawnH = (bitmap.height * scale).toInt()

                // Center on page
                val left = margin + (availW - drawnW) / 2
                val top = margin + (availH - drawnH) / 2

                val destRect = Rect(left, top, left + drawnW, top + drawnH)
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                canvas.drawBitmap(bitmap, srcRect, destRect, paint)

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
