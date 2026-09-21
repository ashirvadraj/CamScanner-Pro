package com.camscanner.pro.core.backup

import android.content.Context
import android.content.SharedPreferences
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.core.auth.GoogleAuthManager
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.data.local.entity.PageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private const val KEY_AUTO_BACKUP_ENABLED = "key_auto_backup_enabled"
    private const val KEY_HAD_DOCS_IN_SESSION = "key_had_documents_in_session"

    private val backupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoBackupJob: Job? = null

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoBackupEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_AUTO_BACKUP_ENABLED, true)

    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()
        if (enabled) {
            triggerAutoBackup(context, delayMs = 300L)
        }
    }

    fun getLastBackupTime(context: Context): Long {
        val saved = getPrefs(context).getLong(KEY_LAST_BACKUP, 0L)
        if (saved > 0) return saved
        val latest = findAvailableBackups(context).firstOrNull()
        val time = latest?.lastModified() ?: 0L
        if (time > 0L) {
            getPrefs(context).edit()
                .putLong(KEY_LAST_BACKUP, time)
                .putString(KEY_LAST_BACKUP_PATH, latest?.absolutePath)
                .apply()
        }
        return time
    }

    fun getLastBackupFile(context: Context): File? {
        val path = getPrefs(context).getString(KEY_LAST_BACKUP_PATH, null)
        if (path != null) {
            val f = File(path)
            if (f.exists()) return f
        }
        val latest = findAvailableBackups(context).firstOrNull()
        if (latest != null) {
            getPrefs(context).edit()
                .putString(KEY_LAST_BACKUP_PATH, latest.absolutePath)
                .putLong(KEY_LAST_BACKUP, latest.lastModified())
                .apply()
        }
        return latest
    }

    /**
     * Finds any backup archives across internal storage, app external storage,
     * public Documents folders, and Downloads folders. Scans recursively so backups
     * survive app uninstallation and are immediately detected upon reinstallation.
     */
    fun findAvailableBackups(context: Context): List<File> {
        val backupFiles = mutableListOf<File>()

        fun scanDir(dir: File?) {
            if (dir == null || !dir.exists() || !dir.isDirectory) return
            try {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && file.extension.equals("zip", ignoreCase = true)) {
                        val lowerName = file.name.lowercase(Locale.ROOT)
                        if (lowerName.contains("camscanner") ||
                            lowerName.contains("backup") ||
                            lowerName.contains("scan") ||
                            lowerName.contains("manifest")) {
                            backupFiles.add(file)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 1. Internal backup directory
        scanDir(File(context.filesDir, "backups"))

        // 2. External app storage directory
        scanDir(File(context.getExternalFilesDir(null), "backups"))

        // 3. Public Documents directory & CamScanner Pro subfolders (survives app uninstallation)
        try {
            val pubDocDir = FileManager.getPublicDocumentsDir(context)
            scanDir(pubDocDir)
            scanDir(File(pubDocDir, "Backups"))
            val sysDocDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            scanDir(File(sysDocDir, "CamScanner Pro"))
            scanDir(File(sysDocDir, "CamScanner Pro/Backups"))
            scanDir(sysDocDir)
        } catch (_: Exception) {}

        // 4. Public Downloads directory & CamScanner Pro subfolders (survives app uninstallation)
        try {
            val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            scanDir(File(dlDir, "CamScanner Pro Backups"))
            scanDir(File(dlDir, "CamScanner Pro"))
            scanDir(dlDir)
        } catch (_: Exception) {}

        // 5. Root storage CamScanner directories
        try {
            val root = android.os.Environment.getExternalStorageDirectory()
            scanDir(File(root, "CamScanner"))
            scanDir(File(root, "CamScanner Pro"))
        } catch (_: Exception) {}

        return backupFiles.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
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
                .putBoolean(KEY_HAD_DOCS_IN_SESSION, true)
                .apply()

            // 1. Mirror backup to persistent public Documents ("Documents/CamScanner Pro/Backups")
            // This directory SURVIVES app uninstallation!
            try {
                val pubBackupsDir = File(FileManager.getPublicDocumentsDir(context), "Backups").apply { if (!exists()) mkdirs() }
                val pubCopy = File(pubBackupsDir, zipFile.name)
                zipFile.copyTo(pubCopy, overwrite = true)
                FileManager.scanFileForMedia(context, pubCopy, "application/zip")

                // Maintain a 'latest' pointer for instant 1-tap restore after reinstall
                val pubLatest = File(pubBackupsDir, "camscanner_cloud_backup_latest.zip")
                zipFile.copyTo(pubLatest, overwrite = true)
                FileManager.scanFileForMedia(context, pubLatest, "application/zip")

                // Account-bound backup if user is logged in with Google/Gmail
                val user = GoogleAuthManager.getUserProfile(context)
                if (user != null) {
                    val emailSlug = (user.email?.substringBefore("@") ?: user.displayName ?: "user")
                        .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                    val userBackup = File(pubBackupsDir, "camscanner_backup_${emailSlug}_latest.zip")
                    zipFile.copyTo(userBackup, overwrite = true)
                    FileManager.scanFileForMedia(context, userBackup, "application/zip")
                }
            } catch (ignored: Exception) {}

            // 2. Mirror backup to persistent public Downloads folder ("Download/CamScanner Pro Backups")
            // This directory SURVIVES app uninstallation!
            try {
                val dlDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CamScanner Pro Backups").apply { if (!exists()) mkdirs() }
                val dlCopy = File(dlDir, zipFile.name)
                zipFile.copyTo(dlCopy, overwrite = true)
                FileManager.scanFileForMedia(context, dlCopy, "application/zip")
            } catch (ignored: Exception) {}

            // 3. Mirror backup to external files directory
            try {
                val extBackupDir = File(context.getExternalFilesDir(null), "backups").apply { if (!exists()) mkdirs() }
                val extCopy = File(extBackupDir, zipFile.name)
                zipFile.copyTo(extCopy, overwrite = true)
            } catch (ignored: Exception) {}

            Result.success(zipFile)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Debounced auto-backup trigger: Automatically packages new documents/pages
     * or purges deleted documents in a background coroutine without blocking the UI.
     */
    fun triggerAutoBackup(context: Context, delayMs: Long = 1000L) {
        val appContext = context.applicationContext
        if (!isAutoBackupEnabled(appContext)) return

        autoBackupJob?.cancel()
        autoBackupJob = backupScope.launch {
            delay(delayMs)
            performAutoSync(appContext)
        }
    }

    /**
     * Synchronizes backup state with current Room DB documents.
     * If documents exist, builds a fresh backup archive and removes stale archives.
     * If 0 documents exist (all deleted), removes old backup archives so deleted documents
     * are completely purged from backup.
     */
    suspend fun performAutoSync(context: Context): Result<File?> = withContext(Dispatchers.IO) {
        try {
            val db = com.camscanner.pro.data.local.AppDatabase.getInstance(context)
            val allDocs = db.documentDao().getAllDocuments()

            if (allDocs.isEmpty()) {
                // If there are 0 documents, only purge backups if user had documents in this install
                // and explicitly deleted them (protects fresh reinstalls!)
                val hadDocs = getPrefs(context).getBoolean(KEY_HAD_DOCS_IN_SESSION, false)
                if (!hadDocs) {
                    return@withContext Result.success(null)
                }

                // Purge internal, external, and public backup archives so deleted scans are removed
                val internalDir = File(context.filesDir, "backups")
                if (internalDir.exists()) {
                    internalDir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
                }
                val extDir = File(context.getExternalFilesDir(null), "backups")
                if (extDir.exists()) {
                    extDir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
                }
                try {
                    val pubDir = File(FileManager.getPublicDocumentsDir(context), "Backups")
                    if (pubDir.exists()) {
                        pubDir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
                    }
                } catch (_: Exception) {}

                getPrefs(context).edit()
                    .putLong(KEY_LAST_BACKUP, System.currentTimeMillis())
                    .remove(KEY_LAST_BACKUP_PATH)
                    .apply()
                return@withContext Result.success(null)
            } else {
                getPrefs(context).edit().putBoolean(KEY_HAD_DOCS_IN_SESSION, true).apply()
            }

            val backupRes = createBackupArchive(context)
            backupRes.onSuccess {
                pruneOldBackups(context, keepLatest = 3)
            }
            backupRes
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Prunes old backup archives across internal, external, and public folders,
     * keeping the latest [keepLatest] copies.
     */
    fun pruneOldBackups(context: Context, keepLatest: Int = 3) {
        fun pruneFolder(dir: File?) {
            if (dir == null || !dir.exists()) return
            try {
                val zips = dir.listFiles { f -> f.extension.equals("zip", ignoreCase = true) }
                    ?.filter { !it.name.contains("latest", ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                if (zips != null && zips.size > keepLatest) {
                    zips.drop(keepLatest).forEach { f ->
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        pruneFolder(File(context.filesDir, "backups"))
        pruneFolder(File(context.getExternalFilesDir(null), "backups"))
        try {
            pruneFolder(File(FileManager.getPublicDocumentsDir(context), "Backups"))
            pruneFolder(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CamScanner Pro Backups"))
        } catch (_: Exception) {}
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

            // Update last backup timestamp and path
            getPrefs(context).edit()
                .putLong(KEY_LAST_BACKUP, zipFile.lastModified())
                .putString(KEY_LAST_BACKUP_PATH, zipFile.absolutePath)
                .putBoolean(KEY_HAD_DOCS_IN_SESSION, true)
                .apply()

            Result.success(restoredDocCount)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }
}
