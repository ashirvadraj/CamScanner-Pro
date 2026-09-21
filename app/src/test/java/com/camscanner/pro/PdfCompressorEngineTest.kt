package com.camscanner.pro

import com.camscanner.pro.core.pdf.PdfCompressorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfCompressorEngineTest {

    @Test
    fun testCompressResultSavingsCalculation() {
        val originalBytes = 2_048_000L // 2 MB
        val compressedBytes = 512_000L // 500 KB

        val result = PdfCompressorEngine.CompressResult(
            outputFile = File("compressed_test.pdf"),
            originalBytes = originalBytes,
            compressedBytes = compressedBytes,
            targetBytes = 500 * 1024L,
            totalPages = 4
        )

        assertEquals(2000L, result.originalKb)
        assertEquals(500L, result.compressedKb)
        assertEquals(75, result.savingsPercent) // (2048 - 512) / 2048 = 75%
        assertEquals(4, result.totalPages)
    }

    @Test
    fun testCompressResultZeroSavings() {
        val bytes = 100_000L
        val result = PdfCompressorEngine.CompressResult(
            outputFile = File("same.pdf"),
            originalBytes = bytes,
            compressedBytes = bytes,
            targetBytes = null,
            totalPages = 1
        )
        assertEquals(0, result.savingsPercent)
    }

    @Test
    fun testPerPageBudgetCalculation() {
        val targetSizeKb = 600
        val pageCount = 6
        val availableForImages = (targetSizeKb * 0.92f).toInt() // 552 KB
        val perPageBudgetKb = kotlin.math.max(12, availableForImages / pageCount)

        assertEquals(92, perPageBudgetKb)
        assertTrue("Total image budget should fit inside target size", perPageBudgetKb * pageCount <= targetSizeKb)
    }

    @Test
    fun testSanitizedPdfTitle() {
        val title = "CamScanner File / Draft : 2026*"
        val sanitized = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        assertEquals("CamScanner_File___Draft___2026_", sanitized)
    }
}
