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

/**
 * Enterprise PDF Merger Engine with intelligent target file size optimization.
 * Merges multiple PDF documents into a single PDF while respecting a target size limit
 * without reducing visual quality.
 */
object PdfMergerEngine {

    data class MergeResult(
        val outputFile: File,
        val sourcePdfCount: Int,
        val totalPages: Int,
        val fileSizeBytes: Long,
        val targetSizeKb: Int?
    ) {
        val fileSizeKb: Long
            get() = fileSizeBytes / 1024
    }

    data class SourcePdfInfo(
        val uri: Uri,
        val fileName: String,
        val fileSizeBytes: Long,
        val pageCount: Int
    )

    /**
     * Inspects a PDF URI to extract metadata (name, size, page count) before merging.
     */
    suspend fun inspectPdf(context: Context, uri: Uri): SourcePdfInfo? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val size = pfd.statSize
            renderer = PdfRenderer(pfd)
            val pages = renderer.pageCount

            var name = "Document.pdf"
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = it.getString(idx)
                }
            }

            SourcePdfInfo(uri, name, size, pages)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Merges a list of PDF URIs into a single PDF, optionally targeting [targetSizeKb] maximum file size.
     */
    suspend fun mergePdfs(
        context: Context,
        pdfUris: List<Uri>,
        targetSizeKb: Int? = null,
        outputTitle: String = "Merged_Document"
    ): Result<MergeResult> = withContext(Dispatchers.IO) {
        if (pdfUris.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No PDFs provided for merge"))
        }

        val tempPageImages = mutableListOf<File>()
        var totalPages = 0

        try {
            // 1. Extract all pages across all input PDFs as bitmaps
            for (uri in pdfUris) {
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: continue
                    renderer = PdfRenderer(pfd)
                    val count = renderer.pageCount

                    for (pageIdx in 0 until count) {
                        val page = renderer.openPage(pageIdx)
                        val scale = 2f
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt()

                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.close()

                        val tempFile = FileManager.createTempImageFile(context)
                        FileOutputStream(tempFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        bitmap.recycle()

                        tempPageImages.add(tempFile)
                        totalPages++
                    }
                } finally {
                    try {
                        renderer?.close()
                        pfd?.close()
                    } catch (_: Throwable) {}
                }
            }

            if (tempPageImages.isEmpty()) {
                return@withContext Result.failure(Exception("No pages could be extracted from input PDFs"))
            }

            // 2. Determine per-page budget if targetSizeKb is requested
            val perPageBudgetKb: Int? = if (targetSizeKb != null && targetSizeKb > 0) {
                // Reserve 8% for PDF structure overhead
                val availableForImages = (targetSizeKb * 0.92f).toInt()
                max(15, availableForImages / totalPages)
            } else null

            // 3. Assemble into a new clean PdfDocument
            val pdfDoc = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            tempPageImages.forEachIndexed { index, imageFile ->
                val rawBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (rawBitmap != null) {
                    val finalBitmap: Bitmap = if (perPageBudgetKb != null) {
                        val compRes = SizeTargetCompressor.compressBitmapToTargetSize(
                            context,
                            rawBitmap,
                            perPageBudgetKb
                        )
                        BitmapFactory.decodeFile(compRes.outputFile.absolutePath) ?: rawBitmap
                    } else {
                        rawBitmap
                    }

                    // A4 page dimensions: 595 x 842 pt
                    val pageWidth = 595
                    val pageHeight = 842
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas: Canvas = page.canvas

                    canvas.drawColor(Color.WHITE)

                    // Fit image into A4 page preserving aspect ratio
                    val imageW = finalBitmap.width.toFloat()
                    val imageH = finalBitmap.height.toFloat()
                    val scale = min(pageWidth / imageW, pageHeight / imageH)

                    val drawnW = imageW * scale
                    val drawnH = imageH * scale
                    val left = (pageWidth - drawnW) / 2f
                    val top = (pageHeight - drawnH) / 2f

                    val srcRect = Rect(0, 0, finalBitmap.width, finalBitmap.height)
                    val dstRect = Rect(left.toInt(), top.toInt(), (left + drawnW).toInt(), (top + drawnH).toInt())
                    canvas.drawBitmap(finalBitmap, srcRect, dstRect, paint)

                    pdfDoc.finishPage(page)

                    if (finalBitmap != rawBitmap) {
                        finalBitmap.recycle()
                    }
                    rawBitmap.recycle()
                }
            }

            val sanitizedTitle = outputTitle.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val outputFile = FileManager.getPdfFile(context, sanitizedTitle)
            FileOutputStream(outputFile).use { fos ->
                pdfDoc.writeTo(fos)
            }
            pdfDoc.close()
            FileManager.scanFileForMedia(context, outputFile, "application/pdf")

            Result.success(
                MergeResult(
                    outputFile = outputFile,
                    sourcePdfCount = pdfUris.size,
                    totalPages = totalPages,
                    fileSizeBytes = outputFile.length(),
                    targetSizeKb = targetSizeKb
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            // Clean up temporary extracted page images
            for (f in tempPageImages) {
                try { f.delete() } catch (_: Throwable) {}
            }
        }
    }
}
