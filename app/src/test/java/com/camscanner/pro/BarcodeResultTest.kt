package com.camscanner.pro

import com.camscanner.pro.core.barcode.BarcodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeResultTest {

    @Test
    fun testBarcodeResultModel() {
        val result = BarcodeResult(
            displayValue = "https://example.com/document",
            formatName = "QR Code",
            valueType = 1,
            url = "https://example.com/document"
        )

        assertEquals("https://example.com/document", result.displayValue)
        assertEquals("QR Code", result.formatName)
        assertEquals(1, result.valueType)
        assertEquals("https://example.com/document", result.url)
    }

    @Test
    fun testBarcodeResultWithNullUrl() {
        val result = BarcodeResult(
            displayValue = "978-0132350884",
            formatName = "EAN-13",
            valueType = 7
        )

        assertEquals("978-0132350884", result.displayValue)
        assertEquals("EAN-13", result.formatName)
        assertNull(result.url)
    }
}
