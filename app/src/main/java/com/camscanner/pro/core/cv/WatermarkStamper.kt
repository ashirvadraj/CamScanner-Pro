package com.camscanner.pro.core.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

object WatermarkStamper {

    fun stampWatermark(
        source: Bitmap,
        text: String,
        colorString: String = "#33888888"
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val w = output.width.toFloat()
        val h = output.height.toFloat()

        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(-35f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorString)
            textSize = (max(w, h) * 0.06f).coerceIn(32f, 96f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isDither = true
        }

        canvas.drawText(text, 0f, 0f, paint)
        canvas.restore()

        return output
    }
}
