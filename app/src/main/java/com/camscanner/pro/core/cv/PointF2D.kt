package com.camscanner.pro.core.cv

import android.graphics.PointF
import java.io.Serializable
import kotlin.math.hypot

/**
 * High-precision 2D Point representation for quadrilateral calculations.
 */
data class PointF2D(
    var x: Float = 0f,
    var y: Float = 0f
) : Serializable {

    fun distanceTo(other: PointF2D): Float {
        return hypot(x - other.x, y - other.y)
    }

    fun midPointTo(other: PointF2D): PointF2D {
        return PointF2D((x + other.x) / 2f, (y + other.y) / 2f)
    }

    fun toAndroidPointF(): PointF = PointF(x, y)

    companion object {
        fun fromAndroidPointF(p: PointF): PointF2D = PointF2D(p.x, p.y)
    }
}
