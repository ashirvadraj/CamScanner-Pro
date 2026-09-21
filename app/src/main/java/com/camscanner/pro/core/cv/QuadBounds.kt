package com.camscanner.pro.core.cv

import java.io.Serializable
import kotlin.math.max

/**
 * Represents the 4 corners of a quadrilateral document crop.
 * Guaranteed order: Top-Left, Top-Right, Bottom-Right, Bottom-Left.
 */
data class QuadBounds(
    var topLeft: PointF2D,
    var topRight: PointF2D,
    var bottomRight: PointF2D,
    var bottomLeft: PointF2D
) : Serializable {

    fun toFloatArray(): FloatArray {
        return floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )
    }

    fun getCorners(): List<PointF2D> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun getMidPoints(): List<PointF2D> = listOf(
        topLeft.midPointTo(topRight),       // Top Mid
        topRight.midPointTo(bottomRight),  // Right Mid
        bottomRight.midPointTo(bottomLeft), // Bottom Mid
        bottomLeft.midPointTo(topLeft)      // Left Mid
    )

    /**
     * Computes the realistic rectangular output dimensions of the dewarped document
     * based on maximum opposite edge lengths.
     */
    fun computeOutputDimensions(): Pair<Int, Int> {
        val topWidth = topLeft.distanceTo(topRight)
        val bottomWidth = bottomLeft.distanceTo(bottomRight)
        val maxWidth = max(topWidth, bottomWidth).toInt().coerceAtLeast(100)

        val leftHeight = topLeft.distanceTo(bottomLeft)
        val rightHeight = topRight.distanceTo(bottomRight)
        val maxHeight = max(leftHeight, rightHeight).toInt().coerceAtLeast(100)

        return Pair(maxWidth, maxHeight)
    }

    /**
     * Scales all coordinates by a scale factor.
     */
    fun scaled(scaleX: Float, scaleY: Float): QuadBounds {
        return QuadBounds(
            PointF2D(topLeft.x * scaleX, topLeft.y * scaleY),
            PointF2D(topRight.x * scaleX, topRight.y * scaleY),
            PointF2D(bottomRight.x * scaleX, bottomRight.y * scaleY),
            PointF2D(bottomLeft.x * scaleX, bottomLeft.y * scaleY)
        )
    }

    /**
     * Clamps all points within [0, maxWidth] and [0, maxHeight].
     */
    fun clamp(maxWidth: Float, maxHeight: Float) {
        listOf(topLeft, topRight, bottomRight, bottomLeft).forEach { p ->
            p.x = p.x.coerceIn(0f, maxWidth)
            p.y = p.y.coerceIn(0f, maxHeight)
        }
    }

    companion object {
        /**
         * Orders 4 arbitrary points into:
         * 0: Top-Left (smallest x + y sum)
         * 1: Top-Right (smallest y - x diff)
         * 2: Bottom-Right (largest x + y sum)
         * 3: Bottom-Left (largest y - x diff)
         */
        fun orderPoints(points: List<PointF2D>): QuadBounds {
            require(points.size == 4) { "Exactly 4 points required" }

            val sortedBySum = points.sortedBy { it.x + it.y }
            val tl = sortedBySum.first()
            val br = sortedBySum.last()

            val sortedByDiff = points.sortedBy { it.y - it.x }
            val tr = sortedByDiff.first()
            val bl = sortedByDiff.last()

            return QuadBounds(
                topLeft = PointF2D(tl.x, tl.y),
                topRight = PointF2D(tr.x, tr.y),
                bottomRight = PointF2D(br.x, br.y),
                bottomLeft = PointF2D(bl.x, bl.y)
            )
        }

        /**
         * Creates a default rectangular inset quad with given margin percentage (e.g. 10%).
         */
        fun createDefaultInset(width: Float, height: Float, marginFraction: Float = 0.08f): QuadBounds {
            val marginX = width * marginFraction
            val marginY = height * marginFraction

            return QuadBounds(
                topLeft = PointF2D(marginX, marginY),
                topRight = PointF2D(width - marginX, marginY),
                bottomRight = PointF2D(width - marginX, height - marginY),
                bottomLeft = PointF2D(marginX, height - marginY)
            )
        }
    }
}
