package com.camscanner.pro.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.camscanner.pro.core.compression.SizeTargetCompressor
import com.camscanner.pro.core.storage.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Intelligent PDF Compressor that reduces the size of any PDF document
 * to an exact target file size (e.g., 5MB -> 500KB, or 1MB -> 200KB)
 * while maintaining maximum visual clarity of text and images.
 */
object PdfCompressorEngine {

    data class CompressResult(
        val outputFile: File,
        val originalBytes: Long,
        val compressedBytes: Long,
        val targetBytes: Long?,
        val totalPages: Int
    ) {
        val savingsPercent: Int
            get() = if (originalBytes > 0) {
                (((originalBytes - compressedBytes).toDouble() / originalBytes) * 100).roundToInt().coerceAtLeast(0)
            } else 0

        val originalKb: Long get() = originalBytes / 1024
        val compressedKb: Long get() = compressedBytes / 1024
    }

    /**
     * Compresses a PDF file to fit within [targetSizeKb] kilobytes.
     */
    suspend fun compressPdf(
        context: Context,
        pdfUri: Uri,
        targetSizeKb: Int?,
        outputTitle: String = "Compressed_Document"
    ): Result<CompressResult> = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val tempImages = mutableListOf<File>()

        try {
            pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext Result.failure(Exception("Cannot open PDF file"))

            val originalSize = pfd.statSize
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            if (pageCount == 0) {
                return@withContext Result.failure(Exception("PDF contains 0 pages"))
            }

            // Extract all pages as bitmaps
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val scale = 2f
                val w = (page.width * scale).toInt()
                val h = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val temp = FileManager.createTempImageFile(context)
                FileOutputStream(temp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                tempImages.add(temp)
            }

            // Calculate per-page byte budget
            val perPageBudgetKb: Int = if (targetSizeKb != null && targetSizeKb > 0) {
                val availableForImages = (targetSizeKb * 0.92f).toInt()
                max(12, availableForImages / pageCount)
            } else {
                // Default moderate compression if no target specified (approx 120KB per page)
                120
            }

            // Assemble into new compressed PdfDocument
            val pdfDoc = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            tempImages.forEachIndexed { index, imgFile ->
                val rawBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                if (rawBitmap != null) {
                    val compRes = SizeTargetCompressor.compressBitmapToTargetSize(
                        context = context,
                        bitmap = rawBitmap,
                        targetKb = perPageBudgetKb
                    )

                    val compressedBitmap = BitmapFactory.decodeFile(compRes.outputFile.absolutePath) ?: rawBitmap

                    val pageWidth = 595
                    val pageHeight = 842
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    val imgW = compressedBitmap.width.toFloat()
                    val imgH = compressedBitmap.height.toFloat()
                    val scale = min(pageWidth / imgW, pageHeight / imgH)

                    val dw = imgW * scale
                    val dh = imgH * scale
                    val left = (pageWidth - dw) / 2f
                    val top = (pageHeight - dh) / 2f

                    val srcRect = Rect(0, 0, compressedBitmap.width, compressedBitmap.height)
                    val dstRect = Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
                    canvas.drawBitmap(compressedBitmap, srcRect, dstRect, paint)

                    pdfDoc.finishPage(page)

                    if (compressedBitmap != rawBitmap) {
                        compressedBitmap.recycle()
                    }
                    rawBitmap.recycle()
                    try { compRes.outputFile.delete() } catch (_: Throwable) {}
                }
            }

            val sanitizedTitle = outputTitle.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val outputFile = FileManager.getPdfFile(context, "${sanitizedTitle}_COMPRESSED")
            FileOutputStream(outputFile).use { fos ->
                pdfDoc.writeTo(fos)
            }
            pdfDoc.close()

            Result.success(
                CompressResult(
                    outputFile = outputFile,
                    originalBytes = originalSize,
                    compressedBytes = outputFile.length(),
                    targetBytes = if (targetSizeKb != null) targetSizeKb * 1024L else null,
                    totalPages = pageCount
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Throwable) {}
            for (f in tempImages) {
                try { f.delete() } catch (_: Throwable) {}
            }
        }
    }
}
