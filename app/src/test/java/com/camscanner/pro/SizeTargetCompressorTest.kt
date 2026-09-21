package com.camscanner.pro

import com.camscanner.pro.core.compression.SizeTargetCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SizeTargetCompressorTest {

    @Test
    fun testCompressionResultSavingsCalculation() {
        val original = 1_000_000L // 1 MB
        val compressed = 50_000L   // 50 KB
        val target = 51_200L       // 50 KB limit

        val result = SizeTargetCompressor.CompressionResult(
            outputFile = File("dummy.jpg"),
            originalBytes = original,
            compressedBytes = compressed,
            targetBytes = target,
            qualityUsed = 78,
            width = 1200,
            height = 800
        )

        assertEquals(95, result.savingsPercent)
        assertTrue(result.compressedBytes <= result.targetBytes)
        assertEquals(78, result.qualityUsed)
        assertEquals(1200, result.width)
        assertEquals(800, result.height)
    }

    @Test
    fun testZeroSavingsWhenAlreadySmall() {
        val result = SizeTargetCompressor.CompressionResult(
            outputFile = File("dummy.jpg"),
            originalBytes = 40_000L,
            compressedBytes = 40_000L,
            targetBytes = 50_000L,
            qualityUsed = 95,
            width = 600,
            height = 400
        )

        assertEquals(0, result.savingsPercent)
    }
}
