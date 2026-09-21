package com.camscanner.pro

import com.camscanner.pro.core.cv.PointF2D
import com.camscanner.pro.core.cv.QuadBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadBoundsTest {

    @Test
    fun testOrderPoints_sortsArbitraryPointsToStandardOrder() {
        // Scrambled points of a rectangle (0,0) -> (100, 100)
        val pTL = PointF2D(0f, 0f)
        val pTR = PointF2D(100f, 0f)
        val pBR = PointF2D(100f, 100f)
        val pBL = PointF2D(0f, 100f)

        val scrambled = listOf(pBR, pTL, pBL, pTR)
        val ordered = QuadBounds.orderPoints(scrambled)

        assertEquals("TopLeft X", 0f, ordered.topLeft.x, 0.001f)
        assertEquals("TopLeft Y", 0f, ordered.topLeft.y, 0.001f)

        assertEquals("TopRight X", 100f, ordered.topRight.x, 0.001f)
        assertEquals("TopRight Y", 0f, ordered.topRight.y, 0.001f)

        assertEquals("BottomRight X", 100f, ordered.bottomRight.x, 0.001f)
        assertEquals("BottomRight Y", 100f, ordered.bottomRight.y, 0.001f)

        assertEquals("BottomLeft X", 0f, ordered.bottomLeft.x, 0.001f)
        assertEquals("BottomLeft Y", 100f, ordered.bottomLeft.y, 0.001f)
    }

    @Test
    fun testComputeOutputDimensions_returnsAccurateEuclideanDimensions() {
        val quad = QuadBounds(
            topLeft = PointF2D(0f, 0f),
            topRight = PointF2D(300f, 0f),
            bottomRight = PointF2D(300f, 400f),
            bottomLeft = PointF2D(0f, 400f)
        )

        val (w, h) = quad.computeOutputDimensions()
        assertEquals(300, w)
        assertEquals(400, h)
    }

    @Test
    fun testGetMidPoints_calculatesCenterOfEachEdge() {
        val quad = QuadBounds(
            topLeft = PointF2D(0f, 0f),
            topRight = PointF2D(200f, 0f),
            bottomRight = PointF2D(200f, 100f),
            bottomLeft = PointF2D(0f, 100f)
        )

        val mids = quad.getMidPoints()
        assertEquals(4, mids.size)

        // Top Mid (0,0) -> (200,0) => (100,0)
        assertEquals(100f, mids[0].x, 0.001f)
        assertEquals(0f, mids[0].y, 0.001f)

        // Right Mid (200,0) -> (200,100) => (200,50)
        assertEquals(200f, mids[1].x, 0.001f)
        assertEquals(50f, mids[1].y, 0.001f)

        // Bottom Mid (200,100) -> (0,100) => (100,100)
        assertEquals(100f, mids[2].x, 0.001f)
        assertEquals(100f, mids[2].y, 0.001f)

        // Left Mid (0,100) -> (0,0) => (0,50)
        assertEquals(0f, mids[3].x, 0.001f)
        assertEquals(50f, mids[3].y, 0.001f)
    }

    @Test
    fun testClamp_restrictsPointsInsideBounds() {
        val quad = QuadBounds(
            topLeft = PointF2D(-20f, -10f),
            topRight = PointF2D(550f, -5f),
            bottomRight = PointF2D(600f, 900f),
            bottomLeft = PointF2D(-10f, 850f)
        )

        quad.clamp(500f, 800f)

        assertEquals(0f, quad.topLeft.x, 0.001f)
        assertEquals(0f, quad.topLeft.y, 0.001f)
        assertEquals(500f, quad.topRight.x, 0.001f)
        assertEquals(0f, quad.topRight.y, 0.001f)
        assertEquals(500f, quad.bottomRight.x, 0.001f)
        assertEquals(800f, quad.bottomRight.y, 0.001f)
        assertEquals(0f, quad.bottomLeft.x, 0.001f)
        assertEquals(800f, quad.bottomLeft.y, 0.001f)
    }

    @Test
    fun testCreateDefaultInset_appliesMarginCorrectly() {
        val quad = QuadBounds.createDefaultInset(1000f, 2000f, 0.1f)
        assertEquals(100f, quad.topLeft.x, 0.001f)
        assertEquals(200f, quad.topLeft.y, 0.001f)
        assertEquals(900f, quad.topRight.x, 0.001f)
        assertEquals(200f, quad.topRight.y, 0.001f)
        assertEquals(900f, quad.bottomRight.x, 0.001f)
        assertEquals(1800f, quad.bottomRight.y, 0.001f)
        assertEquals(100f, quad.bottomLeft.x, 0.001f)
        assertEquals(1800f, quad.bottomLeft.y, 0.001f)
    }
}
