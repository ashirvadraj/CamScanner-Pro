package com.camscanner.pro.data.repository

import android.content.Context
import com.camscanner.pro.core.backup.CloudBackupManager
import com.camscanner.pro.data.local.AppDatabase
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.data.local.entity.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class DocumentRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(context)
    private val docDao = db.documentDao()
    private val pageDao = db.pageDao()

    fun getAllDocuments(): Flow<List<DocumentEntity>> = docDao.getAllDocumentsFlow()

    fun searchDocuments(query: String): Flow<List<DocumentEntity>> = docDao.searchDocumentsFlow(query)

    fun filterDocuments(category: String, query: String): Flow<List<DocumentEntity>> =
        docDao.filterDocumentsFlow(category, query)

    fun getPagesForDocument(docId: Long): Flow<List<PageEntity>> = pageDao.getPagesForDocumentFlow(docId)

    suspend fun getDocumentById(id: Long): DocumentEntity? = withContext(Dispatchers.IO) {
        docDao.getDocumentById(id)
    }

    suspend fun createDocumentWithPage(
        title: String,
        processedImagePath: String,
        originalImagePath: String,
        filterType: String,
        ocrText: String? = null,
        category: String = "ALL"
    ): Long = withContext(Dispatchers.IO) {
        val snippet = ocrText?.take(120)
        val doc = DocumentEntity(
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            pageCount = 1,
            thumbnailPath = processedImagePath,
            ocrSnippet = snippet,
            category = category
        )
        val docId = docDao.insertDocument(doc)

        val page = PageEntity(
            documentId = docId,
            pageIndex = 0,
            imagePath = processedImagePath,
            originalImagePath = originalImagePath,
            filterType = filterType,
            ocrText = ocrText
        )
        pageDao.insertPage(page)
        CloudBackupManager.triggerAutoBackup(appContext)
        docId
    }

    suspend fun addPageToDocument(
        documentId: Long,
        processedImagePath: String,
        originalImagePath: String,
        filterType: String,
        ocrText: String? = null
    ) = withContext(Dispatchers.IO) {
        val existingPages = pageDao.getPagesForDocument(documentId)
        val nextIndex = existingPages.size

        val page = PageEntity(
            documentId = documentId,
            pageIndex = nextIndex,
            imagePath = processedImagePath,
            originalImagePath = originalImagePath,
            filterType = filterType,
            ocrText = ocrText
        )
        pageDao.insertPage(page)

        val doc = docDao.getDocumentById(documentId)
        if (doc != null) {
            val updatedSnippet = if (doc.ocrSnippet.isNullOrBlank() && !ocrText.isNullOrBlank()) {
                ocrText.take(120)
            } else {
                doc.ocrSnippet
            }
            docDao.updateDocument(
                doc.copy(
                    updatedAt = System.currentTimeMillis(),
                    pageCount = nextIndex + 1,
                    ocrSnippet = updatedSnippet
                )
            )
        }
        CloudBackupManager.triggerAutoBackup(appContext)
    }

    suspend fun updateDocumentTitle(docId: Long, newTitle: String) = withContext(Dispatchers.IO) {
        val doc = docDao.getDocumentById(docId)
        if (doc != null) {
            docDao.updateDocument(doc.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
            CloudBackupManager.triggerAutoBackup(appContext)
        }
    }

    suspend fun updateDocumentCategory(docId: Long, category: String) = withContext(Dispatchers.IO) {
        val doc = docDao.getDocumentById(docId)
        if (doc != null) {
            docDao.updateDocument(doc.copy(category = category, updatedAt = System.currentTimeMillis()))
            CloudBackupManager.triggerAutoBackup(appContext)
        }
    }

    suspend fun updatePageImage(pageId: Long, newImagePath: String) = withContext(Dispatchers.IO) {
        val page = pageDao.getPageById(pageId)
        if (page != null) {
            pageDao.updatePage(page.copy(imagePath = newImagePath))
            val doc = docDao.getDocumentById(page.documentId)
            if (doc != null && page.pageIndex == 0) {
                docDao.updateDocument(doc.copy(thumbnailPath = newImagePath, updatedAt = System.currentTimeMillis()))
            }
            CloudBackupManager.triggerAutoBackup(appContext)
        }
    }

    suspend fun deletePage(page: PageEntity) = withContext(Dispatchers.IO) {
        pageDao.deletePage(page)
        try {
            File(page.imagePath).delete()
            File(page.originalImagePath).delete()
        } catch (_: Exception) {}

        val remainingPages = pageDao.getPagesForDocument(page.documentId)
        if (remainingPages.isEmpty()) {
            docDao.deleteDocumentById(page.documentId)
        } else {
            val doc = docDao.getDocumentById(page.documentId)
            if (doc != null) {
                docDao.updateDocument(
                    doc.copy(
                        pageCount = remainingPages.size,
                        thumbnailPath = remainingPages.first().imagePath,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        CloudBackupManager.triggerAutoBackup(appContext)
    }

    suspend fun deleteDocument(docId: Long) = withContext(Dispatchers.IO) {
        val pages = pageDao.getPagesForDocument(docId)
        pages.forEach { p ->
            try {
                File(p.imagePath).delete()
                File(p.originalImagePath).delete()
            } catch (_: Exception) {}
        }
        docDao.deleteDocumentById(docId)
        CloudBackupManager.triggerAutoBackup(appContext)
    }
}
