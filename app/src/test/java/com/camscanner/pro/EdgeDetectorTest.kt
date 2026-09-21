package com.camscanner.pro

import com.camscanner.pro.core.cv.EdgeDetector
import com.camscanner.pro.core.cv.PointF2D
import com.camscanner.pro.core.cv.QuadBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeDetectorTest {

    @Test
    fun testDefaultInsetMargins() {
        val w = 1920f
        val h = 1080f
        val quad = QuadBounds.createDefaultInset(w, h, 0.08f)

        // 8% of 1920 is 153.6, 8% of 1080 is 86.4
        assertEquals(153.6f, quad.topLeft.x, 0.01f)
        assertEquals(86.4f, quad.topLeft.y, 0.01f)

        assertEquals(1920f - 153.6f, quad.topRight.x, 0.01f)
        assertEquals(86.4f, quad.topRight.y, 0.01f)

        assertEquals(1920f - 153.6f, quad.bottomRight.x, 0.01f)
        assertEquals(1080f - 86.4f, quad.bottomRight.y, 0.01f)

        assertEquals(153.6f, quad.bottomLeft.x, 0.01f)
        assertEquals(1080f - 86.4f, quad.bottomLeft.y, 0.01f)
    }

    @Test
    fun testPointF2DMath() {
        val p1 = PointF2D(10f, 20f)
        val p2 = PointF2D(40f, 60f)

        // Distance: sqrt((40-10)^2 + (60-20)^2) = sqrt(30^2 + 40^2) = 50
        assertEquals(50f, p1.distanceTo(p2), 0.001f)

        // MidPoint: ((10+40)/2, (20+60)/2) = (25, 40)
        val mid = p1.midPointTo(p2)
        assertEquals(25f, mid.x, 0.001f)
        assertEquals(40f, mid.y, 0.001f)
    }

    @Test
    fun testComputeQuadArea() {
        val tl = PointF2D(0f, 0f)
        val tr = PointF2D(200f, 0f)
        val br = PointF2D(200f, 100f)
        val bl = PointF2D(0f, 100f)

        val area = EdgeDetector.computeQuadArea(tl, tr, br, bl)
        assertEquals(20000f, area, 0.01f)
    }

    @Test
    fun testIsConvexQuad() {
        val tl = PointF2D(0f, 0f)
        val tr = PointF2D(200f, 0f)
        val br = PointF2D(200f, 100f)
        val bl = PointF2D(0f, 100f)
        assertTrue(EdgeDetector.isConvexQuad(tl, tr, br, bl))

        // Non-convex / self-intersecting polygon (hourglass / bowtie)
        val crossedBr = PointF2D(0f, 100f)
        val crossedBl = PointF2D(200f, 100f)
        assertFalse(EdgeDetector.isConvexQuad(tl, tr, crossedBr, crossedBl))
    }

    @Test
    fun testDetectFullPage() {
        val w = 1000f
        val h = 2000f
        val quad = QuadBounds.createDefaultInset(w, h, 0.015f)

        assertEquals(15f, quad.topLeft.x, 0.01f)
        assertEquals(30f, quad.topLeft.y, 0.01f)
        assertEquals(985f, quad.topRight.x, 0.01f)
        assertEquals(30f, quad.topRight.y, 0.01f)
        assertEquals(985f, quad.bottomRight.x, 0.01f)
        assertEquals(1970f, quad.bottomRight.y, 0.01f)
        assertEquals(15f, quad.bottomLeft.x, 0.01f)
        assertEquals(1970f, quad.bottomLeft.y, 0.01f)
    }
}
