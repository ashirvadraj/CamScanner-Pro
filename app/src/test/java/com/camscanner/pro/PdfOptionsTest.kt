package com.camscanner.pro

import com.camscanner.pro.core.pdf.PageSize
import com.camscanner.pro.core.pdf.PdfOptions
import com.camscanner.pro.core.pdf.PdfQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfOptionsTest {

    @Test
    fun testPageSizeDimensions() {
        assertEquals(595, PageSize.A4.width)
        assertEquals(842, PageSize.A4.height)

        assertEquals(612, PageSize.US_LETTER.width)
        assertEquals(792, PageSize.US_LETTER.height)

        assertEquals(0, PageSize.FIT_IMAGE.width)
        assertEquals(0, PageSize.FIT_IMAGE.height)
    }

    @Test
    fun testPdfQualityDimensions() {
        assertEquals(1920, PdfQuality.HIGH.maxDim)
        assertEquals(1280, PdfQuality.MEDIUM.maxDim)
        assertEquals(800, PdfQuality.COMPACT.maxDim)
    }

    @Test
    fun testPdfOptionsDefaults() {
        val options = PdfOptions()
        assertEquals(PageSize.A4, options.pageSize)
        assertEquals(PdfQuality.HIGH, options.quality)
        assertNull(options.watermarkText)
    }
}
