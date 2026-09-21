package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * High-performance 4-point perspective dewarping engine.
 * Flattens skewed quadrilateral document captures into clean rectangular bitmaps.
 */
object PerspectiveTransformer {

    fun warpPerspective(source: Bitmap, quad: QuadBounds): Bitmap {
        val (targetW, targetH) = quad.computeOutputDimensions()

        val outputBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        val srcPoints = quad.toFloatArray()
        val dstPoints = floatArrayOf(
            0f, 0f,                           // Top-Left
            targetW.toFloat(), 0f,            // Top-Right
            targetW.toFloat(), targetH.toFloat(), // Bottom-Right
            0f, targetH.toFloat()             // Bottom-Left
        )

        val matrix = Matrix()
        val success = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }

        if (success) {
            canvas.drawBitmap(source, matrix, paint)
        } else {
            // Fallback: draw centered crop if poly-to-poly fails
            val srcRect = android.graphics.Rect(0, 0, source.width, source.height)
            val dstRect = android.graphics.Rect(0, 0, targetW, targetH)
            canvas.drawBitmap(source, srcRect, dstRect, paint)
        }

        return outputBitmap
    }
}
