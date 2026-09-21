package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

object SignatureStamper {

    fun stampSignature(
        pageBitmap: Bitmap,
        signatureBitmap: Bitmap,
        marginRight: Float = 80f,
        marginBottom: Float = 100f
    ): Bitmap {
        val output = pageBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        // Scale signature to ~30% of page width
        val targetW = pageBitmap.width * 0.32f
        val scale = targetW / signatureBitmap.width
        val targetH = signatureBitmap.height * scale

        val left = pageBitmap.width - marginRight - targetW
        val top = pageBitmap.height - marginBottom - targetH

        val destRect = RectF(left, top, left + targetW, top + targetH)
        val srcRect = Rect(0, 0, signatureBitmap.width, signatureBitmap.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(signatureBitmap, srcRect, destRect, paint)
        return output
    }
}
