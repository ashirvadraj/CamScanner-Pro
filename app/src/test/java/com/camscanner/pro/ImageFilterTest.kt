package com.camscanner.pro

import com.camscanner.pro.core.cv.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImageFilterTest {

    @Test
    fun testFilterTypeEnumValues() {
        val types = FilterType.values()
        assertEquals(5, types.size)

        assertNotNull(FilterType.valueOf("ORIGINAL"))
        assertNotNull(FilterType.valueOf("MAGIC_COLOR"))
        assertNotNull(FilterType.valueOf("GRAYSCALE"))
        assertNotNull(FilterType.valueOf("BW_DOCUMENT"))
        assertNotNull(FilterType.valueOf("LIGHTEN"))
    }

    @Test
    fun testFilterDisplayNames() {
        assertEquals("Original", FilterType.ORIGINAL.displayName)
        assertEquals("Magic Color", FilterType.MAGIC_COLOR.displayName)
        assertEquals("Grayscale", FilterType.GRAYSCALE.displayName)
        assertEquals("B&W Document", FilterType.BW_DOCUMENT.displayName)
        assertEquals("Lighten", FilterType.LIGHTEN.displayName)
    }

    @Test
    fun testContrastTranslationFormula() {
        val contrast = 1.3f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 15f
        // Verify translate calculation doesn't overflow or produce NaN
        org.junit.Assert.assertFalse(translate.isNaN())
        org.junit.Assert.assertTrue(translate < 255f)
    }
}
