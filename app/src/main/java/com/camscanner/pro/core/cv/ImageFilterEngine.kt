package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

enum class FilterType(val displayName: String) {
    ORIGINAL("Original"),
    MAGIC_COLOR("Magic Color"),
    GRAYSCALE("Grayscale"),
    BW_DOCUMENT("B&W Document"),
    LIGHTEN("Lighten")
}

object ImageFilterEngine {

    fun applyFilter(source: Bitmap, filterType: FilterType): Bitmap {
        return when (filterType) {
            FilterType.ORIGINAL -> source.copy(Bitmap.Config.ARGB_8888, true)
            FilterType.MAGIC_COLOR -> applyMagicColor(source)
            FilterType.GRAYSCALE -> applyGrayscale(source)
            FilterType.BW_DOCUMENT -> applyBwThreshold(source)
            FilterType.LIGHTEN -> applyLighten(source)
        }
    }

    /**
     * Magic Color: Boosts text contrast, expands dynamic range,
     * and enhances color saturation for sharp document rendering.
     */
    private fun applyMagicColor(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // 1. Contrast boost (1.3x)
        val contrast = 1.3f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 15f // slight lift for paper white

        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        // 2. Saturation boost (1.2x)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.2f)
        contrastMatrix.postConcat(satMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Grayscale: Standard luminance desaturation.
     */
    private fun applyGrayscale(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val matrix = ColorMatrix()
        matrix.setSaturation(0f)

        // Slight contrast enhancement on grayscale
        val contrastMatrix = ColorMatrix(floatArrayOf(
            1.2f, 0f, 0f, 0f, -20f,
            0f, 1.2f, 0f, 0f, -20f,
            0f, 0f, 1.2f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(contrastMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * B&W Document: Adaptive thresholding turning background into crisp white
     * and ink into dark black.
     */
    private fun applyBwThreshold(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Calculate Otsu-like average luminance threshold
        var totalLum = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            totalLum += (r * 299 + g * 587 + b * 114) / 1000
        }
        val threshold = (totalLum / pixels.size).toInt().coerceIn(100, 175)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val lum = (r * 299 + g * 587 + b * 114) / 1000

            pixels[i] = if (lum > threshold) {
                Color.WHITE
            } else {
                Color.BLACK
            }
        }

        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Lighten: Gamma boost to eliminate ambient shadows.
     */
    private fun applyLighten(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val matrix = ColorMatrix(floatArrayOf(
            1.15f, 0f, 0f, 0f, 35f,
            0f, 1.15f, 0f, 0f, 35f,
            0f, 0f, 1.15f, 0f, 35f,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Rotates bitmap by given degrees (90, 180, 270).
     */
    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
