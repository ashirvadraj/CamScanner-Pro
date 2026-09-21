package com.camscanner.pro

import com.camscanner.pro.core.pdf.PdfMergerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfMergerEngineTest {

    @Test
    fun testMergeResultModel() {
        val result = PdfMergerEngine.MergeResult(
            outputFile = File("merged_output.pdf"),
            sourcePdfCount = 3,
            totalPages = 12,
            fileSizeBytes = 512_000L,
            targetSizeKb = 500
        )

        assertEquals(3, result.sourcePdfCount)
        assertEquals(12, result.totalPages)
        assertEquals(512_000L, result.fileSizeBytes)
        assertEquals(500L, result.fileSizeKb)
        assertEquals(500, result.targetSizeKb)
    }

    @Test
    fun testSanitizedFileName() {
        val rawTitle = "My Receipt #1 & Invoice @ 2026!"
        val sanitized = rawTitle.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        assertEquals("My_Receipt__1___Invoice___2026_", sanitized)
        assertTrue(!sanitized.contains("#"))
        assertTrue(!sanitized.contains("&"))
    }
}
