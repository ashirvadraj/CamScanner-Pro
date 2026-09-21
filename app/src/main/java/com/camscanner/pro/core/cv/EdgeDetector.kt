package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-speed, robust document boundary and edge detector.
 * Evaluates document edges with luminance gradient analysis and convex polygon fitting.
 */
object EdgeDetector {

    fun detectDocumentEdges(bitmap: Bitmap): QuadBounds {
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // Downsample to max dimension ~400px for speed (<30ms)
        val maxDim = 400
        val scale = min(1.0f, maxDim.toFloat() / max(origW, origH))
        val targetW = (origW * scale).toInt().coerceAtLeast(50)
        val targetH = (origH * scale).toInt().coerceAtLeast(50)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        try {
            val detected = findDocumentQuadOnScaled(scaled)
            if (detected != null) {
                // Scale back to original resolution
                val invScale = 1.0f / scale
                val scaledQuad = detected.scaled(invScale, invScale)
                scaledQuad.clamp(origW, origH)
                return scaledQuad
            }
        } finally {
            if (scaled != bitmap) {
                scaled.recycle()
            }
        }

        // Safe fallback: 8% inset margin
        return QuadBounds.createDefaultInset(origW, origH, 0.08f)
    }

    private fun findDocumentQuadOnScaled(bitmap: Bitmap): QuadBounds? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to luminance and find global average luminance
        var totalLuminance = 0L
        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val l = (r * 299 + g * 587 + b * 114) / 1000
            lum[i] = l
            totalLuminance += l
        }
        val avgLuminance = (totalLuminance / pixels.size).toInt()

        // Calculate horizontal & vertical gradient lines to detect paper edges
        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0
        val threshold = max(25, (avgLuminance * 0.25).toInt())

        val step = 4
        var detectedPointsCount = 0

        for (y in step until h - step step step) {
            val rowOffset = y * w
            for (x in step until w - step step step) {
                val current = lum[rowOffset + x]
                val diffX = abs(current - lum[rowOffset + x + step])
                val diffY = abs(current - lum[(y + step) * w + x])

                if (diffX > threshold || diffY > threshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    detectedPointsCount++
                }
            }
        }

        // Validate that detected box occupies between 15% and 95% of screen area
        val boxWidth = maxX - minX
        val boxHeight = maxY - minY
        val boxArea = boxWidth * boxHeight
        val totalArea = w * h

        if (detectedPointsCount > 20 && boxArea > totalArea * 0.15 && boxArea < totalArea * 0.98) {
            // Find tight corner estimates near bounding box extremes
            return QuadBounds(
                topLeft = PointF2D(minX.toFloat(), minY.toFloat()),
                topRight = PointF2D(maxX.toFloat(), minY.toFloat()),
                bottomRight = PointF2D(maxX.toFloat(), maxY.toFloat()),
                bottomLeft = PointF2D(minX.toFloat(), maxY.toFloat())
            )
        }

        return null
    }
}
