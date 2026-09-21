package com.camscanner.pro.core.backup

import android.content.Context
import android.content.SharedPreferences
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.data.local.entity.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object CloudBackupManager {

    private const val PREFS_NAME = "camscanner_cloud_prefs"
    private const val KEY_LAST_BACKUP = "key_last_backup_timestamp"
    private const val KEY_LAST_BACKUP_PATH = "key_last_backup_path"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastBackupTime(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_BACKUP, 0L)

    fun getLastBackupFile(context: Context): File? {
        val path = getPrefs(context).getString(KEY_LAST_BACKUP_PATH, null) ?: return null
        val f = File(path)
        return if (f.exists()) f else null
    }

    /**
     * Builds a compressed cloud backup archive (.zip) containing manifest.json
     * and all document scan image bitmaps.
     */
    suspend fun createBackupArchive(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val db = com.camscanner.pro.data.local.AppDatabase.getInstance(context)
            val allDocs = db.documentDao().getAllDocuments()

            if (allDocs.isEmpty()) {
                return@withContext Result.failure(Exception("No documents to back up"))
            }

            val docItems = mutableListOf<BackupDocItem>()
            val filesToArchive = mutableListOf<File>()

            for (doc in allDocs) {
                val pages = db.pageDao().getPagesForDocument(doc.id)
                val pageItems = mutableListOf<BackupPageItem>()

                for (page in pages) {
                    val imgFile = File(page.imagePath)
                    if (imgFile.exists()) {
                        filesToArchive.add(imgFile)
                        pageItems.add(
                            BackupPageItem(
                                pageIndex = page.pageIndex,
                                filename = imgFile.name,
                                filterType = page.filterType,
                                rotationDegrees = page.rotationDegrees,
                                ocrText = page.ocrText
                            )
                        )
                    }
                }

                docItems.add(
                    BackupDocItem(
                        id = doc.id,
                        title = doc.title,
                        createdAt = doc.createdAt,
                        updatedAt = doc.updatedAt,
                        pageCount = pages.size,
                        category = doc.category,
                        ocrSnippet = doc.ocrSnippet,
                        pages = pageItems
                    )
                )
            }

            val manifest = BackupManifest(documents = docItems)
            val manifestJson = manifest.toJson()

            // Prepare backup zip destination
            val backupDir = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(backupDir, "camscanner_cloud_backup_$timeStamp.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                // Write manifest.json
                val manifestEntry = ZipEntry("manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Write each image file
                val buffer = ByteArray(8192)
                for (file in filesToArchive) {
                    val entry = ZipEntry("images/${file.name}")
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        var count: Int
                        while (fis.read(buffer).also { count = it } != -1) {
                            zos.write(buffer, 0, count)
                        }
                    }
                    zos.closeEntry()
                }
            }

            // Save last backup timestamp
            val now = System.currentTimeMillis()
            getPrefs(context).edit()
                .putLong(KEY_LAST_BACKUP, now)
                .putString(KEY_LAST_BACKUP_PATH, zipFile.absolutePath)
                .apply()

            Result.success(zipFile)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Restores all documents and pages from a backup archive into Room DB and internal storage.
     */
    suspend fun restoreBackupArchive(context: Context, zipFile: File): Result<Int> = withContext(Dispatchers.IO) {
        if (!zipFile.exists()) {
            return@withContext Result.failure(Exception("Backup file not found"))
        }

        val tempExtractDir = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        val targetDocsDir = FileManager.getDocumentsDir(context)
        val db = com.camscanner.pro.data.local.AppDatabase.getInstance(context)

        try {
            // 1. Unzip all entries
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry?
                val buffer = ByteArray(8192)
                while (zis.nextEntry.also { entry = it } != null) {
                    val outFile = File(tempExtractDir, entry!!.name)
                    if (entry!!.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var count: Int
                            while (zis.read(buffer).also { count = it } != -1) {
                                fos.write(buffer, 0, count)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }

            // 2. Read manifest.json
            val manifestFile = File(tempExtractDir, "manifest.json")
            if (!manifestFile.exists()) {
                return@withContext Result.failure(Exception("Invalid backup: manifest.json is missing"))
            }

            val manifestJson = manifestFile.readText(Charsets.UTF_8)
            val manifest = BackupManifest.fromJson(manifestJson)

            var restoredDocCount = 0

            // 3. Restore documents and pages into database
            for (docItem in manifest.documents) {
                var firstRestoredPath: String? = null

                val newDoc = DocumentEntity(
                    title = docItem.title,
                    createdAt = docItem.createdAt,
                    updatedAt = docItem.updatedAt,
                    pageCount = docItem.pages.size,
                    thumbnailPath = null,
                    ocrSnippet = docItem.ocrSnippet,
                    category = docItem.category
                )
                val newDocId = db.documentDao().insertDocument(newDoc)

                for (pageItem in docItem.pages) {
                    val extractedImg = File(tempExtractDir, "images/${pageItem.filename}")
                    val destImg = File(targetDocsDir, "RESTORED_${pageItem.filename}")

                    if (extractedImg.exists()) {
                        extractedImg.copyTo(destImg, overwrite = true)
                    }

                    if (firstRestoredPath == null) {
                        firstRestoredPath = destImg.absolutePath
                    }

                    val pageEntity = PageEntity(
                        documentId = newDocId,
                        pageIndex = pageItem.pageIndex,
                        imagePath = destImg.absolutePath,
                        originalImagePath = destImg.absolutePath,
                        filterType = pageItem.filterType,
                        rotationDegrees = pageItem.rotationDegrees,
                        ocrText = pageItem.ocrText
                    )
                    db.pageDao().insertPage(pageEntity)
                }

                // Update thumbnail path
                if (firstRestoredPath != null) {
                    db.documentDao().updateDocument(
                        newDoc.copy(id = newDocId, thumbnailPath = firstRestoredPath)
                    )
                }

                restoredDocCount++
            }

            Result.success(restoredDocCount)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }
}
