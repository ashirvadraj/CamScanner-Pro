package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent Document Boundary & Salient Region Detector.
 *
 * Implements a multi-stage Computer Vision pipeline:
 * 1. Background vs Foreground border luminance differential.
 * 2. High-frequency content salience (text, print, barcodes, lines, graphics).
 * 3. Otsu-based document blob segmentation & 4-corner convex extrema fitting.
 * 4. Multi-candidate decision arbiter:
 *    - Isolated documents (receipts, cards, checks, notes): Tightly selects the document quad.
 *    - Full page documents (A4 paper, contracts, book pages): Cleanly selects the full document page.
 */
object EdgeDetector {

    /**
     * Primary smart auto-detection: selects the most important part of the image
     * (the receipt/card/document), or cleanly selects the full document page if it fills the view.
     */
    fun detectImportantPart(bitmap: Bitmap): QuadBounds {
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // Working resolution ~360px for sub-25ms analysis
        val maxDim = 360
        val scale = min(1.0f, maxDim.toFloat() / max(origW, origH))
        val targetW = (origW * scale).toInt().coerceAtLeast(60)
        val targetH = (origH * scale).toInt().coerceAtLeast(60)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        try {
            val detected = findBestDocumentQuad(scaled)
            if (detected != null) {
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

        // Clean full page document fallback (1.5% margin)
        return QuadBounds.createDefaultInset(origW, origH, 0.015f)
    }

    /**
     * Backward-compatible alias for detectImportantPart.
     */
    fun detectDocumentEdges(bitmap: Bitmap): QuadBounds {
        return detectImportantPart(bitmap)
    }

    /**
     * Returns full-page document bounds (1.5% margin so edge text is not clipped).
     */
    fun detectFullPage(bitmap: Bitmap): QuadBounds {
        return QuadBounds.createDefaultInset(bitmap.width.toFloat(), bitmap.height.toFloat(), 0.015f)
    }

    private fun findBestDocumentQuad(bitmap: Bitmap): QuadBounds? {
        val w = bitmap.width
        val h = bitmap.height
        val totalArea = (w * h).toFloat()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Convert to Luminance
        val lum = IntArray(w * h)
        var totalLum = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val l = (r * 299 + g * 587 + b * 114) / 1000
            lum[i] = l
            totalLum += l
        }
        val globalAvgLum = (totalLum / pixels.size).toInt()

        // 2. Sample Border Luminance (background reference)
        var borderLumSum = 0L
        var borderCount = 0
        for (x in 0 until w) {
            borderLumSum += lum[x]                  // Top border
            borderLumSum += lum[(h - 1) * w + x]    // Bottom border
            borderCount += 2
        }
        for (y in 1 until h - 1) {
            borderLumSum += lum[y * w]              // Left border
            borderLumSum += lum[y * w + (w - 1)]    // Right border
            borderCount += 2
        }
        val borderAvgLum = if (borderCount > 0) (borderLumSum / borderCount).toInt() else globalAvgLum

        // 3. Compute High-Frequency Salience (Gradient Energy Grid)
        val gridCols = 24
        val gridRows = 24
        val cellW = w.toFloat() / gridCols
        val cellH = h.toFloat() / gridRows
        val cellEnergy = Array(gridRows) { FloatArray(gridCols) }
        var totalEnergy = 0f
        var maxCellEnergy = 0f

        for (gy in 0 until gridRows) {
            val startY = (gy * cellH).toInt().coerceIn(1, h - 2)
            val endY = ((gy + 1) * cellH).toInt().coerceIn(1, h - 2)
            for (gx in 0 until gridCols) {
                val startX = (gx * cellW).toInt().coerceIn(1, w - 2)
                val endX = ((gx + 1) * cellW).toInt().coerceIn(1, w - 2)
                var energySum = 0f
                var count = 0

                var y = startY
                while (y < endY) {
                    val row = y * w
                    var x = startX
                    while (x < endX) {
                        val dx = abs(lum[row + x + 1] - lum[row + x - 1])
                        val dy = abs(lum[(y + 1) * w + x] - lum[(y - 1) * w + x])
                        energySum += (dx + dy)
                        count++
                        x += 2
                    }
                    y += 2
                }
                val avgCellEnergy = if (count > 0) energySum / count else 0f
                cellEnergy[gy][gx] = avgCellEnergy
                totalEnergy += avgCellEnergy
                if (avgCellEnergy > maxCellEnergy) maxCellEnergy = avgCellEnergy
            }
        }

        val meanEnergy = totalEnergy / (gridRows * gridCols)
        val energyThreshold = max(12f, meanEnergy * 0.75f)

        // Find salient cell bounding box
        var minCellX = gridCols
        var maxCellX = 0
        var minCellY = gridRows
        var maxCellY = 0
        var salientCellCount = 0

        for (gy in 0 until gridRows) {
            for (gx in 0 until gridCols) {
                if (cellEnergy[gy][gx] >= energyThreshold) {
                    if (gx < minCellX) minCellX = gx
                    if (gx > maxCellX) maxCellX = gx
                    if (gy < minCellY) minCellY = gy
                    if (gy > maxCellY) maxCellY = gy
                    salientCellCount++
                }
            }
        }

        val hasSalientContent = salientCellCount >= 6 && minCellX <= maxCellX && minCellY <= maxCellY
        val salientBounds: QuadBounds? = if (hasSalientContent) {
            // Expand by 1 cell margin for comfortable text padding
            val padX = cellW * 1.2f
            val padY = cellH * 1.2f
            val sLeft = max(0f, minCellX * cellW - padX)
            val sTop = max(0f, minCellY * cellH - padY)
            val sRight = min(w.toFloat(), (maxCellX + 1) * cellW + padX)
            val sBottom = min(h.toFloat(), (maxCellY + 1) * cellH + padY)

            QuadBounds(
                PointF2D(sLeft, sTop),
                PointF2D(sRight, sTop),
                PointF2D(sRight, sBottom),
                PointF2D(sLeft, sBottom)
            )
        } else null

        // 4. Document Paper Segmentation via Contrast
        // Determine whether paper is lighter or darker than background
        val paperIsLighter = (globalAvgLum >= borderAvgLum - 10)
        val contrastDiff = abs(globalAvgLum - borderAvgLum)

        // Calculate Otsu or adaptive threshold
        val threshold = if (paperIsLighter) {
            max(borderAvgLum + 15, (borderAvgLum * 0.6f + globalAvgLum * 0.4f).toInt())
        } else {
            min(borderAvgLum - 15, (borderAvgLum * 0.6f + globalAvgLum * 0.4f).toInt())
        }

        // Collect paper candidate points
        var minSum = Float.MAX_VALUE
        var maxSum = Float.MIN_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = Float.MIN_VALUE

        var pTL = PointF2D(0f, 0f)
        var pBR = PointF2D(w.toFloat(), h.toFloat())
        var pTR = PointF2D(w.toFloat(), 0f)
        var pBL = PointF2D(0f, h.toFloat())

        var paperPixelCount = 0
        val insetMarginX = (w * 0.02f).toInt()
        val insetMarginY = (h * 0.02f).toInt()

        val step = 3
        for (y in insetMarginY until h - insetMarginY step step) {
            val row = y * w
            for (x in insetMarginX until w - insetMarginX step step) {
                val l = lum[row + x]
                val isPaperPixel = if (paperIsLighter) l >= threshold else l <= threshold

                if (isPaperPixel) {
                    paperPixelCount++
                    val fx = x.toFloat()
                    val fy = y.toFloat()

                    val sum = fx + fy
                    if (sum < minSum) {
                        minSum = sum
                        pTL = PointF2D(fx, fy)
                    }
                    if (sum > maxSum) {
                        maxSum = sum
                        pBR = PointF2D(fx, fy)
                    }

                    val diff = fy - fx
                    if (diff < minDiff) {
                        minDiff = diff
                        pTR = PointF2D(fx, fy)
                    }
                    if (diff > maxDiff) {
                        maxDiff = diff
                        pBL = PointF2D(fx, fy)
                    }
                }
            }
        }

        val totalSampledPixels = ((w - 2 * insetMarginX) / step) * ((h - 2 * insetMarginY) / step)
        val paperRatio = if (totalSampledPixels > 0) paperPixelCount.toFloat() / totalSampledPixels else 0f

        // Evaluate candidate paper quad
        val candidateQuad = QuadBounds(pTL, pTR, pBR, pBL)
        val quadArea = computeQuadArea(pTL, pTR, pBR, pBL)
        val isConvex = isConvexQuad(pTL, pTR, pBR, pBL)
        val areaRatio = quadArea / totalArea

        // Decision Arbiter:
        // Case 1: Full-page document filling the entire view (areaRatio > 0.75 or paper covers almost whole view)
        if (paperRatio > 0.75f || areaRatio > 0.75f || (salientBounds != null && computeQuadArea(salientBounds.topLeft, salientBounds.topRight, salientBounds.bottomRight, salientBounds.bottomLeft) / totalArea > 0.72f)) {
            // Document is full-page: select full page document with clean 1.5% margin
            return QuadBounds.createDefaultInset(w.toFloat(), h.toFloat(), 0.015f)
        }

        // Case 2: Sub-frame document (receipt, card, check, photo on a table)
        if (contrastDiff >= 12 && isConvex && areaRatio in 0.07f..0.75f) {
            return candidateQuad
        }

        // Case 3: Content-based salient selection (when paper boundary contrast is subtle)
        if (salientBounds != null) {
            val sAreaRatio = computeQuadArea(salientBounds.topLeft, salientBounds.topRight, salientBounds.bottomRight, salientBounds.bottomLeft) / totalArea
            if (sAreaRatio in 0.05f..0.75f) {
                return salientBounds
            }
        }

        // Case 4: Default clean full page document
        return QuadBounds.createDefaultInset(w.toFloat(), h.toFloat(), 0.015f)
    }

    /**
     * Calculates area of quadrilateral using Shoelace formula.
     */
    fun computeQuadArea(tl: PointF2D, tr: PointF2D, br: PointF2D, bl: PointF2D): Float {
        val term1 = tl.x * tr.y + tr.x * br.y + br.x * bl.y + bl.x * tl.y
        val term2 = tl.y * tr.x + tr.y * br.x + br.y * bl.x + bl.y * tl.x
        return 0.5f * abs(term1 - term2)
    }

    /**
     * Checks if 4 points form a strictly convex quadrilateral.
     */
    fun isConvexQuad(tl: PointF2D, tr: PointF2D, br: PointF2D, bl: PointF2D): Boolean {
        val pts = listOf(tl, tr, br, bl)
        var prevSign = 0
        for (i in 0 until 4) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            val p3 = pts[(i + 2) % 4]
            val dx1 = p2.x - p1.x
            val dy1 = p2.y - p1.y
            val dx2 = p3.x - p2.x
            val dy2 = p3.y - p2.y
            val cross = dx1 * dy2 - dy1 * dx2
            val sign = if (cross > 1e-4) 1 else if (cross < -1e-4) -1 else 0
            if (sign != 0) {
                if (prevSign == 0) {
                    prevSign = sign
                } else if (prevSign != sign) {
                    return false
                }
            }
        }
        return prevSign != 0
    }
}
