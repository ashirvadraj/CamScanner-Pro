package com.camscanner.pro

import com.camscanner.pro.core.pdf.PdfGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class PdfGeneratorTest {

    @Test
    fun testPdfA4Dimensions() {
        assertEquals(595, PdfGenerator.PAGE_WIDTH)
        assertEquals(842, PdfGenerator.PAGE_HEIGHT)
    }

    @Test
    fun testAspectFitCalculation_portraitImage() {
        val margin = 20
        val availW = PdfGenerator.PAGE_WIDTH - (margin * 2) // 555
        val availH = PdfGenerator.PAGE_HEIGHT - (margin * 2) // 802

        // Simulate 3000 x 4000 image
        val imgW = 3000
        val imgH = 4000

        val scale = min(availW.toFloat() / imgW, availH.toFloat() / imgH)
        val drawnW = (imgW * scale).toInt()
        val drawnH = (imgH * scale).toInt()

        assertTrue("Drawn width must fit inside available width", drawnW <= availW)
        assertTrue("Drawn height must fit inside available height", drawnH <= availH)
        assertEquals("Aspect ratio preserved", imgW.toFloat() / imgH, drawnW.toFloat() / drawnH, 0.05f)
    }

    @Test
    fun testAspectFitCalculation_landscapeImage() {
        val margin = 20
        val availW = PdfGenerator.PAGE_WIDTH - (margin * 2) // 555
        val availH = PdfGenerator.PAGE_HEIGHT - (margin * 2) // 802

        // Simulate 4000 x 2000 image
        val imgW = 4000
        val imgH = 2000

        val scale = min(availW.toFloat() / imgW, availH.toFloat() / imgH)
        val drawnW = (imgW * scale).toInt()
        val drawnH = (imgH * scale).toInt()

        assertTrue("Drawn width must fit inside available width", drawnW <= availW)
        assertTrue("Drawn height must fit inside available height", drawnH <= availH)
        assertEquals("Aspect ratio preserved", imgW.toFloat() / imgH, drawnW.toFloat() / drawnH, 0.05f)
    }
}
