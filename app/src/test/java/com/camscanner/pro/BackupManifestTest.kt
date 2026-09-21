package com.camscanner.pro

import com.camscanner.pro.core.backup.BackupDocItem
import com.camscanner.pro.core.backup.BackupManifest
import com.camscanner.pro.core.backup.BackupPageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    @Test
    fun testBackupManifestSerializationAndDeserialization() {
        val pages = listOf(
            BackupPageItem(
                pageIndex = 0,
                filename = "doc_1_page_0.jpg",
                filterType = "MAGIC_COLOR",
                rotationDegrees = 0,
                ocrText = "Invoice #1024 Paid in Full"
            ),
            BackupPageItem(
                pageIndex = 1,
                filename = "doc_1_page_1.jpg",
                filterType = "B_AND_W",
                rotationDegrees = 90,
                ocrText = "Terms & Conditions"
            )
        )

        val doc = BackupDocItem(
            id = 42L,
            title = "Business Contract",
            createdAt = 1710000000000L,
            updatedAt = 1710000500000L,
            pageCount = 2,
            category = "CONTRACT",
            ocrSnippet = "Invoice #1024",
            pages = pages
        )

        val manifest = BackupManifest(
            version = 1,
            createdAt = 1710001000000L,
            appVersion = "1.2.0",
            documents = listOf(doc)
        )

        val jsonStr = manifest.toJson()
        assertNotNull(jsonStr)
        assertTrue(jsonStr.contains("Business Contract"))
        assertTrue(jsonStr.contains("CONTRACT"))
        assertTrue(jsonStr.contains("Invoice #1024 Paid in Full"))

        val restored = BackupManifest.fromJson(jsonStr)
        assertEquals(1, restored.version)
        assertEquals("1.2.0", restored.appVersion)
        assertEquals(1, restored.documents.size)

        val restoredDoc = restored.documents[0]
        assertEquals(42L, restoredDoc.id)
        assertEquals("Business Contract", restoredDoc.title)
        assertEquals("CONTRACT", restoredDoc.category)
        assertEquals(2, restoredDoc.pageCount)
        assertEquals(2, restoredDoc.pages.size)

        val page0 = restoredDoc.pages[0]
        assertEquals(0, page0.pageIndex)
        assertEquals("doc_1_page_0.jpg", page0.filename)
        assertEquals("MAGIC_COLOR", page0.filterType)
        assertEquals(0, page0.rotationDegrees)
        assertEquals("Invoice #1024 Paid in Full", page0.ocrText)

        val page1 = restoredDoc.pages[1]
        assertEquals(1, page1.pageIndex)
        assertEquals("doc_1_page_1.jpg", page1.filename)
        assertEquals("B_AND_W", page1.filterType)
        assertEquals(90, page1.rotationDegrees)
        assertEquals("Terms & Conditions", page1.ocrText)
    }

    @Test
    fun testEmptyManifest() {
        val emptyManifest = BackupManifest(
            version = 1,
            createdAt = 1700000000000L,
            appVersion = "1.2.0",
            documents = emptyList()
        )

        val json = emptyManifest.toJson()
        val restored = BackupManifest.fromJson(json)

        assertEquals(1, restored.version)
        assertEquals("1.2.0", restored.appVersion)
        assertTrue(restored.documents.isEmpty())
    }

    @Test
    fun testBackupDocDefaults() {
        val doc = BackupDocItem(
            id = 1L,
            title = "Test",
            createdAt = 1000L,
            updatedAt = 2000L,
            pageCount = 1,
            pages = emptyList()
        )

        assertEquals("ALL", doc.category)
        assertNull(doc.ocrSnippet)
    }

    @Test
    fun testAutoBackupAddAndPurgeOnDelete() {
        val doc1 = BackupDocItem(id = 1L, title = "Doc 1", createdAt = 1000L, updatedAt = 1000L, pageCount = 1, pages = emptyList())
        val doc2 = BackupDocItem(id = 2L, title = "Doc 2", createdAt = 2000L, updatedAt = 2000L, pageCount = 1, pages = emptyList())

        // Initial backup with 2 documents
        val manifestBefore = BackupManifest(version = 1, createdAt = 3000L, appVersion = "1.4.2", documents = listOf(doc1, doc2))
        assertEquals(2, manifestBefore.documents.size)

        // When Doc 1 is deleted from the app, the updated backup manifest purges Doc 1
        val updatedDocs = manifestBefore.documents.filter { it.id != 1L }
        val manifestAfter = manifestBefore.copy(createdAt = 4000L, documents = updatedDocs)

        assertEquals(1, manifestAfter.documents.size)
        assertEquals(2L, manifestAfter.documents[0].id)
        assertEquals("Doc 2", manifestAfter.documents[0].title)

        // Verify JSON roundtrip has completely purged Doc 1
        val json = manifestAfter.toJson()
        org.junit.Assert.assertFalse(json.contains("Doc 1"))
        org.junit.Assert.assertTrue(json.contains("Doc 2"))
    }
}
