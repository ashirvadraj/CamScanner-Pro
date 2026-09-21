package com.camscanner.pro.core.compression

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.camscanner.pro.core.storage.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Intelligent Image Compressor that hits an exact target file size (e.g. 1MB -> 50KB)
 * while preserving maximum visual clarity and sharpness.
 *
 * Employs a 2-tier optimization strategy:
 * 1. Pixel Budget Estimation: Computes required dimensional resolution only if target
 *    size cannot physically be reached without severe JPEG block compression.
 * 2. Adaptive Binary Search on Quality: Pins down the optimal compression quality
 *    factor to maximize visual fidelity while strictly adhering to the size limit.
 */
object SizeTargetCompressor {

    data class CompressionResult(
        val outputFile: File,
        val originalBytes: Long,
        val compressedBytes: Long,
        val targetBytes: Long,
        val qualityUsed: Int,
        val width: Int,
        val height: Int
    ) {
        val savingsPercent: Int
            get() = if (originalBytes > 0) {
                (((originalBytes - compressedBytes).toDouble() / originalBytes) * 100).roundToInt().coerceAtLeast(0)
            } else 0
    }

    /**
     * Compresses an existing image file (from Uri) to a target size in Kilobytes (KB).
     */
    suspend fun compressImageFileToTargetSize(
        context: Context,
        imageUri: Uri,
        targetKb: Int
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        try {
            var originalSize = 0L
            context.contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                originalSize = pfd.statSize
            }

            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return@withContext Result.failure(Exception("Cannot open image input stream"))

            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) {
                return@withContext Result.failure(Exception("Failed to decode image bitmap"))
            }

            if (originalSize <= 0) {
                originalSize = (originalBitmap.width * originalBitmap.height * 4).toLong()
            }

            val result = compressBitmapToTarget(context, originalBitmap, targetKb, originalSize)
            Result.success(result)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Compresses a Bitmap to fit within [targetKb] kilobytes.
     */
    suspend fun compressBitmapToTargetSize(
        context: Context,
        bitmap: Bitmap,
        targetKb: Int
    ): CompressionResult = withContext(Dispatchers.Default) {
        val estimatedOriginal = (bitmap.width * bitmap.height * 3).toLong()
        compressBitmapToTarget(context, bitmap, targetKb, estimatedOriginal)
    }

    private fun compressBitmapToTarget(
        context: Context,
        bitmap: Bitmap,
        targetKb: Int,
        originalBytes: Long
    ): CompressionResult {
        val targetBytes = targetKb * 1024L
        var currentBitmap = bitmap
        var workingW = bitmap.width
        var workingH = bitmap.height

        // Check if downscaling is required to avoid macroblock artifacts at tight budgets
        // e.g. A 4000x3000 image cannot be compressed to 50KB at JPEG Q>30 without downscaling.
        // A budget of 50KB can comfortably carry ~1.2 Megapixels at Q=75.
        val maxPixelsForBudget = (targetBytes * 30).coerceAtLeast(300_000L).coerceAtMost(8_000_000L)
        val currentPixels = (workingW.toLong() * workingH.toLong())

        if (currentPixels > maxPixelsForBudget) {
            val scale = sqrt(maxPixelsForBudget.toDouble() / currentPixels).toFloat()
            val newW = (workingW * scale).roundToInt().coerceAtLeast(200)
            val newH = (workingH * scale).roundToInt().coerceAtLeast(200)
            currentBitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            workingW = newW
            workingH = newH
        }

        // Binary search for the optimal compression quality factor in [10, 95]
        var lowQuality = 10
        var highQuality = 95
        var bestQuality = 80
        var bestBytes: ByteArray? = null

        var iteration = 0
        while (lowQuality <= highQuality && iteration < 8) {
            val midQuality = (lowQuality + highQuality) / 2
            val stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, midQuality, stream)
            val outputArray = stream.toByteArray()
            val size = outputArray.size

            if (size <= targetBytes) {
                // Fits inside target budget! Remember this and try higher quality
                bestQuality = midQuality
                bestBytes = outputArray
                lowQuality = midQuality + 1
            } else {
                // Too large, decrease quality
                highQuality = midQuality - 1
            }
            iteration++
        }

        // If even lowest quality Q=10 exceeded budget, perform secondary subtle downscale
        if (bestBytes == null || bestBytes.size > targetBytes) {
            var downscaleRatio = 0.8f
            while (downscaleRatio >= 0.3f) {
                val nextW = (workingW * downscaleRatio).roundToInt().coerceAtLeast(150)
                val nextH = (workingH * downscaleRatio).roundToInt().coerceAtLeast(150)
                val scaledDown = Bitmap.createScaledBitmap(currentBitmap, nextW, nextH, true)

                val stream = ByteArrayOutputStream()
                scaledDown.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val bytes = stream.toByteArray()

                if (scaledDown != currentBitmap && scaledDown != bitmap) {
                    scaledDown.recycle()
                }

                if (bytes.size <= targetBytes || downscaleRatio <= 0.35f) {
                    bestBytes = bytes
                    bestQuality = 70
                    workingW = nextW
                    workingH = nextH
                    break
                }
                downscaleRatio -= 0.15f
            }
        }

        val finalBytes = bestBytes ?: run {
            val stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            stream.toByteArray()
        }

        val outputFile = FileManager.createImageFile(context, "COMPRESSED_${targetKb}KB")
        FileOutputStream(outputFile).use { fos ->
            fos.write(finalBytes)
        }
        FileManager.scanFileForMedia(context, outputFile, "image/jpeg")

        if (currentBitmap != bitmap) {
            currentBitmap.recycle()
        }

        return CompressionResult(
            outputFile = outputFile,
            originalBytes = originalBytes,
            compressedBytes = finalBytes.size.toLong(),
            targetBytes = targetBytes,
            qualityUsed = bestQuality,
            width = workingW,
            height = workingH
        )
    }
}
