package com.camscanner.pro.core.migration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.core.ocr.OcrManager
import com.camscanner.pro.core.storage.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Migration engine to import existing scanned documents, PDFs, and image folders
 * exported from the original CamScanner app into CamScanner Pro.
 */
object CamScannerImporter {

    data class ImportResult(
        val documentsImported: Int,
        val pagesImported: Int
    )

    /**
     * Imports a PDF file (e.g. exported from CamScanner) by rendering each page
     * and running on-device OCR for search indexing.
     */
    suspend fun importPdf(
        context: Context,
        pdfUri: Uri,
        customTitle: String? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        val repository = CamScannerApp.instance.repository
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext Result.failure(Exception("Cannot open PDF file"))

            renderer = PdfRenderer(fileDescriptor)
            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                return@withContext Result.failure(Exception("PDF has no pages"))
            }

            val title = customTitle ?: getFileNameFromUri(context, pdfUri)
                .replace(".pdf", "", ignoreCase = true)
                .ifBlank { "Imported_CamScanner_Doc" }

            var createdDocId = -1L

            for (pageIndex in 0 until pageCount) {
                val page = renderer.openPage(pageIndex)
                // Standard 2x render scale for crisp text resolution
                val scale = 2f
                val renderW = (page.width * scale).toInt()
                val renderH = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                // White background before rendering
                bitmap.eraseColor(android.graphics.Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val savedFile = FileManager.saveBitmap(context, bitmap, "IMPORTED")

                // Run OCR in background
                val ocrResult = OcrManager.recognizeText(bitmap).getOrNull()
                bitmap.recycle()

                if (pageIndex == 0) {
                    createdDocId = repository.createDocumentWithPage(
                        title = title,
                        processedImagePath = savedFile.absolutePath,
                        originalImagePath = savedFile.absolutePath,
                        filterType = "ORIGINAL",
                        ocrText = ocrResult,
                        category = "ALL"
                    )
                } else {
                    repository.addPageToDocument(
                        documentId = createdDocId,
                        processedImagePath = savedFile.absolutePath,
                        originalImagePath = savedFile.absolutePath,
                        filterType = "ORIGINAL",
                        ocrText = ocrResult
                    )
                }
            }

            Result.success(createdDocId)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Imports multiple image files into a single scanned document.
     */
    suspend fun importImagesAsDocument(
        context: Context,
        imageUris: List<Uri>,
        documentTitle: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val repository = CamScannerApp.instance.repository
        try {
            var docId = -1L

            imageUris.forEachIndexed { index, uri ->
                val tempFile = FileManager.createTempImageFile(context)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val bitmap = FileManager.loadSampledBitmap(tempFile.absolutePath, 1920)
                    ?: return@forEachIndexed

                val finalFile = FileManager.saveBitmap(context, bitmap, "IMPORTED")
                val ocrText = OcrManager.recognizeText(bitmap).getOrNull()
                bitmap.recycle()

                if (index == 0) {
                    docId = repository.createDocumentWithPage(
                        title = documentTitle,
                        processedImagePath = finalFile.absolutePath,
                        originalImagePath = finalFile.absolutePath,
                        filterType = "ORIGINAL",
                        ocrText = ocrText
                    )
                } else {
                    repository.addPageToDocument(
                        documentId = docId,
                        processedImagePath = finalFile.absolutePath,
                        originalImagePath = finalFile.absolutePath,
                        filterType = "ORIGINAL",
                        ocrText = ocrText
                    )
                }
            }

            if (docId != -1L) {
                Result.success(docId)
            } else {
                Result.failure(Exception("No valid images imported"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Scans a directory picked via Storage Access Framework for CamScanner files.
     */
    suspend fun importFromFolder(
        context: Context,
        treeUri: Uri
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(Exception("Cannot access folder"))

            var docCount = 0
            var pageCount = 0

            val files = rootDoc.listFiles()
            for (file in files) {
                if (file.isFile && file.name?.endsWith(".pdf", ignoreCase = true) == true) {
                    val res = importPdf(context, file.uri, file.name)
                    if (res.isSuccess) {
                        docCount++
                    }
                }
            }

            // Group images by parent or import individually
            val imageFiles = files.filter {
                it.isFile && (it.name?.endsWith(".jpg", ignoreCase = true) == true ||
                        it.name?.endsWith(".png", ignoreCase = true) == true)
            }

            if (imageFiles.isNotEmpty()) {
                val uris = imageFiles.map { it.uri }
                val folderTitle = rootDoc.name ?: "Imported_CamScanner_Batch"
                val res = importImagesAsDocument(context, uris, folderTitle)
                if (res.isSuccess) {
                    docCount++
                    pageCount += imageFiles.size
                }
            }

            Result.success(ImportResult(docCount, pageCount))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName = "Document"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    fileName = it.getString(index)
                }
            }
        }
        return fileName
    }
}
