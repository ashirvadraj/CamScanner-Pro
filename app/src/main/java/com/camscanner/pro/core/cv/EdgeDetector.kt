package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Intelligent Document Boundary & Salient Region Detector.
 *
 * Implements a multi-stage Computer Vision pipeline:
 * 1. Background vs Foreground perimeter luminance and edge gradient differential.
 * 2. Center-outward radial boundary ray casting (immune to external desk noise/highlights).
 * 3. 4-quadrant corner localization with least-squares edge line fitting & intersection.
 * 4. Convexity, aspect ratio, and area validation:
 *    - Snaps accurately to document edges whether document covers 15% (receipt/card) or 90% (A4 on desk).
 *    - Clean full page inset (1.5%) for close-up full frame documents.
 * 5. High-frequency content salience fallback for low-contrast document bounds.
 */
object EdgeDetector {

    /**
     * Primary smart auto-detection: detects the document quad (paper, receipt, card, note)
     * accurately across any background.
     */
    fun detectImportantPart(bitmap: Bitmap): QuadBounds {
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // Working resolution ~380px for sub-30ms analysis
        val maxDim = 380
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

        // 1. Grayscale luminance
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

        // 2. Sobel edge gradient magnitude
        val grad = IntArray(w * h)
        var totalGrad = 0L
        var y = 1
        while (y < h - 1) {
            val row = y * w
            val prevRow = (y - 1) * w
            val nextRow = (y + 1) * w
            var x = 1
            while (x < w - 1) {
                val dx = abs(lum[row + x + 1] - lum[row + x - 1])
                val dy = abs(lum[nextRow + x] - lum[prevRow + x])
                val gVal = dx + dy
                grad[row + x] = gVal
                totalGrad += gVal
                x++
            }
            y++
        }
        val avgGrad = (totalGrad / max(1, (w - 2) * (h - 2))).toInt()

        // 3. Robust Background Sampling (outer 6% perimeter bands)
        val insetX = max(2, (w * 0.06f).toInt())
        val insetY = max(2, (h * 0.06f).toInt())
        var borderLumSum = 0L
        var borderPixelCount = 0

        for (by in 0 until h) {
            val row = by * w
            val inBandY = by < insetY || by >= h - insetY
            for (bx in 0 until w) {
                if (inBandY || bx < insetX || bx >= w - insetX) {
                    borderLumSum += lum[row + bx]
                    borderPixelCount++
                }
            }
        }
        val borderAvgLum = if (borderPixelCount > 0) (borderLumSum / borderPixelCount).toInt() else globalAvgLum

        // Central region luminance (inner 60%)
        val innerStartX = (w * 0.20f).toInt()
        val innerEndX = (w * 0.80f).toInt()
        val innerStartY = (h * 0.20f).toInt()
        val innerEndY = (h * 0.80f).toInt()
        var centerLumSum = 0L
        var centerPixelCount = 0
        for (cy in innerStartY until innerEndY) {
            val row = cy * w
            for (cx in innerStartX until innerEndX) {
                centerLumSum += lum[row + cx]
                centerPixelCount++
            }
        }
        val centerAvgLum = if (centerPixelCount > 0) (centerLumSum / centerPixelCount).toInt() else globalAvgLum

        // Determine if paper is lighter or darker than the background
        val paperIsLighter = (centerAvgLum >= borderAvgLum - 10)

        // Threshold calculation
        val paperThreshold = if (paperIsLighter) {
            max(borderAvgLum + 12, (borderAvgLum * 0.55f + centerAvgLum * 0.45f).toInt())
        } else {
            min(borderAvgLum - 12, (borderAvgLum * 0.55f + centerAvgLum * 0.45f).toInt())
        }

        // 4. Centroid of paper / salient region
        var sumPaperX = 0L
        var sumPaperY = 0L
        var paperCount = 0
        val paperMask = BooleanArray(w * h)

        for (py in insetY until h - insetY) {
            val row = py * w
            for (px in insetX until w - insetX) {
                val l = lum[row + px]
                val isPaper = if (paperIsLighter) l >= paperThreshold else l <= paperThreshold
                if (isPaper) {
                    paperMask[row + px] = true
                    sumPaperX += px
                    sumPaperY += py
                    paperCount++
                }
            }
        }

        val centerX = if (paperCount > 200) (sumPaperX.toFloat() / paperCount) else (w / 2f)
        val centerY = if (paperCount > 200) (sumPaperY.toFloat() / paperCount) else (h / 2f)

        // 5. Center-Outward Radial Boundary Ray Casting (36 rays, 10 degrees apart)
        val numRays = 36
        val rayDistances = FloatArray(numRays)
        val rayPoints = ArrayList<PointF2D>(numRays)
        val gradThreshold = max(18, (avgGrad * 1.3f).toInt())

        for (k in 0 until numRays) {
            val angle = (k * (2.0 * PI / numRays)).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)

            // Max distance along ray until canvas boundary
            val maxRx = if (cosA > 0.001f) (w - 2f - centerX) / cosA else if (cosA < -0.001f) (1f - centerX) / cosA else Float.MAX_VALUE
            val maxRy = if (sinA > 0.001f) (h - 2f - centerY) / sinA else if (sinA < -0.001f) (1f - centerY) / sinA else Float.MAX_VALUE
            val maxR = min(maxRx, maxRy).coerceAtLeast(10f)

            var bestR = maxR * 0.95f
            var maxRGrad = 0
            var bestGradR = -1f
            var maskDropR = -1f

            var r = 8f
            while (r < maxR) {
                val rx = (centerX + r * cosA).toInt().coerceIn(1, w - 2)
                val ry = (centerY + r * sinA).toInt().coerceIn(1, h - 2)
                val idx = ry * w + rx

                val gVal = grad[idx]
                if (gVal > maxRGrad) {
                    maxRGrad = gVal
                    bestGradR = r
                }

                if (maskDropR < 0f && !paperMask[idx]) {
                    // Check next sample ahead to confirm boundary drop
                    val nextR = min(maxR, r + 4f)
                    val nrx = (centerX + nextR * cosA).toInt().coerceIn(1, w - 2)
                    val nry = (centerY + nextR * sinA).toInt().coerceIn(1, h - 2)
                    if (!paperMask[nry * w + nrx]) {
                        maskDropR = r
                    }
                }
                r += 2f
            }

            if (maskDropR > 0f) {
                bestR = if (bestGradR > 0f && abs(bestGradR - maskDropR) < 25f) {
                    bestGradR
                } else {
                    maskDropR
                }
            } else if (maxRGrad >= gradThreshold && bestGradR > 15f) {
                bestR = bestGradR
            }

            rayDistances[k] = bestR
            val px = (centerX + bestR * cosA).coerceIn(0f, w.toFloat())
            val py = (centerY + bestR * sinA).coerceIn(0f, h.toFloat())
            rayPoints.add(PointF2D(px, py))
        }

        // 6. Partition perimeter points into 4 edges
        val topPoints = ArrayList<PointF2D>()
        val rightPoints = ArrayList<PointF2D>()
        val bottomPoints = ArrayList<PointF2D>()
        val leftPoints = ArrayList<PointF2D>()

        var bestTLProj = Float.MAX_VALUE
        var bestTRProj = Float.MIN_VALUE
        var bestBRProj = Float.MIN_VALUE
        var bestBLProj = Float.MAX_VALUE

        var rawTL = PointF2D(0f, 0f)
        var rawTR = PointF2D(w.toFloat(), 0f)
        var rawBR = PointF2D(w.toFloat(), h.toFloat())
        var rawBL = PointF2D(0f, h.toFloat())

        for (pt in rayPoints) {
            val sum = pt.x + pt.y
            val diff = pt.x - pt.y

            if (sum < bestTLProj) {
                bestTLProj = sum
                rawTL = pt
            }
            if (sum > bestBRProj) {
                bestBRProj = sum
                rawBR = pt
            }
            if (diff > bestTRProj) {
                bestTRProj = diff
                rawTR = pt
            }
            if (diff < bestBLProj) {
                bestBLProj = diff
                rawBL = pt
            }

            // Quadrant allocation for edge line fitting
            if (pt.y <= centerY && pt.x >= rawTL.x && pt.x <= rawTR.x) topPoints.add(pt)
            if (pt.x >= centerX && pt.y >= rawTR.y && pt.y <= rawBR.y) rightPoints.add(pt)
            if (pt.y >= centerY && pt.x >= rawBL.x && pt.x <= rawBR.x) bottomPoints.add(pt)
            if (pt.x <= centerX && pt.y >= rawTL.y && pt.y <= rawBL.y) leftPoints.add(pt)
        }

        // 7. Line Fitting & Intersection for razor-sharp corners
        val topL = fitLine(topPoints)
        val rightL = fitLine(rightPoints)
        val bottomL = fitLine(bottomPoints)
        val leftL = fitLine(leftPoints)

        val fitTL = (if (topL != null && leftL != null) topL.intersect(leftL) else null) ?: rawTL
        val fitTR = (if (topL != null && rightL != null) topL.intersect(rightL) else null) ?: rawTR
        val fitBR = (if (bottomL != null && rightL != null) bottomL.intersect(rightL) else null) ?: rawBR
        val fitBL = (if (bottomL != null && leftL != null) bottomL.intersect(leftL) else null) ?: rawBL

        fitTL.x = fitTL.x.coerceIn(0f, w.toFloat())
        fitTL.y = fitTL.y.coerceIn(0f, h.toFloat())
        fitTR.x = fitTR.x.coerceIn(0f, w.toFloat())
        fitTR.y = fitTR.y.coerceIn(0f, h.toFloat())
        fitBR.x = fitBR.x.coerceIn(0f, w.toFloat())
        fitBR.y = fitBR.y.coerceIn(0f, h.toFloat())
        fitBL.x = fitBL.x.coerceIn(0f, w.toFloat())
        fitBL.y = fitBL.y.coerceIn(0f, h.toFloat())

        val candidateQuad = QuadBounds(fitTL, fitTR, fitBR, fitBL)
        val quadArea = computeQuadArea(fitTL, fitTR, fitBR, fitBL)
        val areaRatio = quadArea / totalArea
        val isConvex = isConvexQuad(fitTL, fitTR, fitBR, fitBL)

        val topW = fitTL.distanceTo(fitTR)
        val botW = fitBL.distanceTo(fitBR)
        val leftH = fitTL.distanceTo(fitBL)
        val rightH = fitTR.distanceTo(fitBR)
        val maxW = max(topW, botW)
        val maxH = max(leftH, rightH)
        val aspectRatio = if (maxH > 0f) maxW / maxH else 1.0f

        // 8. Decision Arbiter:
        // Valid document quad check: covers between 10% and 96% of the frame, convex, and normal aspect ratio
        if (isConvex && areaRatio in 0.10f..0.96f && aspectRatio in 0.22f..4.5f) {
            return candidateQuad
        }

        // 9. High-Frequency Content Salience Fallback (for low-contrast backgrounds)
        val salient = detectSalientRegion(lum, w, h)
        if (salient != null) {
            val sArea = computeQuadArea(salient.topLeft, salient.topRight, salient.bottomRight, salient.bottomLeft)
            val sRatio = sArea / totalArea
            if (sRatio in 0.08f..0.95f) {
                return salient
            }
        }

        // 10. Clean full page fallback with 1.5% margin
        return QuadBounds.createDefaultInset(w.toFloat(), h.toFloat(), 0.015f)
    }

    private data class Line2D(val a: Float, val b: Float, val c: Float) {
        // Line equation: a * x + b * y + c = 0
        fun intersect(other: Line2D): PointF2D? {
            val d = a * other.b - b * other.a
            if (abs(d) < 1e-5f) return null
            val x = (b * other.c - other.b * c) / d
            val y = (other.a * c - a * other.c) / d
            return PointF2D(x, y)
        }
    }

    private fun fitLine(points: List<PointF2D>): Line2D? {
        if (points.size < 3) return null
        val n = points.size.toFloat()
        var sumX = 0f
        var sumY = 0f
        for (p in points) {
            sumX += p.x
            sumY += p.y
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (p in points) {
            val dx = p.x - meanX
            val dy = p.y - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }

        return if (sxx >= syy) {
            if (sxx < 1e-4f) return null
            val m = sxy / sxx
            val c = meanY - m * meanX
            Line2D(m, -1f, c)
        } else {
            if (syy < 1e-4f) return null
            val m = sxy / syy
            val c = meanX - m * meanY
            Line2D(-1f, m, c)
        }
    }

    private fun detectSalientRegion(lum: IntArray, w: Int, h: Int): QuadBounds? {
        val gridCols = 24
        val gridRows = 24
        val cellW = w.toFloat() / gridCols
        val cellH = h.toFloat() / gridRows
        val cellEnergy = Array(gridRows) { FloatArray(gridCols) }
        var totalEnergy = 0f

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
            }
        }

        val meanEnergy = totalEnergy / (gridRows * gridCols)
        val threshold = max(12f, meanEnergy * 0.70f)

        var minCellX = gridCols
        var maxCellX = 0
        var minCellY = gridRows
        var maxCellY = 0
        var salientCellCount = 0

        for (gy in 0 until gridRows) {
            for (gx in 0 until gridCols) {
                if (cellEnergy[gy][gx] >= threshold) {
                    if (gx < minCellX) minCellX = gx
                    if (gx > maxCellX) maxCellX = gx
                    if (gy < minCellY) minCellY = gy
                    if (gy > maxCellY) maxCellY = gy
                    salientCellCount++
                }
            }
        }

        if (salientCellCount >= 6 && minCellX <= maxCellX && minCellY <= maxCellY) {
            val padX = cellW * 1.2f
            val padY = cellH * 1.2f
            val sLeft = max(0f, minCellX * cellW - padX)
            val sTop = max(0f, minCellY * cellH - padY)
            val sRight = min(w.toFloat(), (maxCellX + 1) * cellW + padX)
            val sBottom = min(h.toFloat(), (maxCellY + 1) * cellH + padY)

            return QuadBounds(
                PointF2D(sLeft, sTop),
                PointF2D(sRight, sTop),
                PointF2D(sRight, sBottom),
                PointF2D(sLeft, sBottom)
            )
        }
        return null
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
