package com.camscanner.pro

import com.camscanner.pro.core.migration.CamScannerImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class CamScannerImporterTest {

    @Test
    fun testImportResultModel() {
        val result = CamScannerImporter.ImportResult(
            documentsImported = 5,
            pagesImported = 23
        )

        assertEquals(5, result.documentsImported)
        assertEquals(23, result.pagesImported)
    }

    @Test
    fun testFileNameSanitization() {
        val rawName = "CamScanner_09-21-2026_16.00.pdf"
        val sanitized = rawName.replace(".pdf", "", ignoreCase = true)
        assertEquals("CamScanner_09-21-2026_16.00", sanitized)

        val emptyName = ".pdf"
        val fallback = emptyName.replace(".pdf", "", ignoreCase = true).ifBlank { "Imported_CamScanner_Doc" }
        assertEquals("Imported_CamScanner_Doc", fallback)
    }
}
