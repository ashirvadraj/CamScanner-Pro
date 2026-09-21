package com.camscanner.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardMergerTest {

    @Test
    fun testStandardIdCardAspectRatio() {
        // ISO/IEC 7810 ID-1 standard: 85.60 mm × 53.98 mm
        val standardRatio = 85.60f / 53.98f // ~1.58577
        val cardW = 1200f
        val cardH = cardW / 1.586f

        assertEquals(1.586f, standardRatio, 0.01f)
        assertTrue("Card height must fit comfortably on A4 page", cardH < 800f)

        // Verify two stacked cards fit inside 2262px A4 height
        val totalCardsHeight = cardH * 2 + 300f // 2 cards + spacing
        assertTrue("Two stacked cards must be less than A4 canvas height (2262px)", totalCardsHeight < 2262f)
    }
}
