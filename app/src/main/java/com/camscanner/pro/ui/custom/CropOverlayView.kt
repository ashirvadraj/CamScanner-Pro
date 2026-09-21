package com.camscanner.pro.ui.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.camscanner.pro.core.cv.PointF2D
import com.camscanner.pro.core.cv.QuadBounds
import kotlin.math.hypot

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var quad: QuadBounds? = null
    private var sourceBitmap: Bitmap? = null
    private val imageBounds = RectF()

    private val maskPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val cornerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val innerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val midHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    // --- Enhanced Loupe / Magnifier Glass Paints ---
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val loupeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val loupeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#77000000")
        style = Paint.Style.FILL
    }

    private val loupeOuterRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44000000")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val loupeWhiteRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 7f
        style = Paint.Style.STROKE
    }

    private val loupeCyanRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val loupeCrosshairShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val loupeCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val loupeCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1A1C1E")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val cornerRadius = 32f
    private val innerRadius = 14f
    private val midRadius = 16f
    private val touchThreshold = 80f

    // Active drag index: 0..3 = Corners (TL, TR, BR, BL), 4..7 = Mids (T, R, B, L), -1 = None
    private var activeDragIndex = -1
    private val lastTouch = PointF()

    // Loupe configuration
    private val loupeRadius = 115f
    private val loupeZoom = 2.4f
    private var showLoupe = false
    private val loupeCenter = PointF()

    fun setQuad(newQuad: QuadBounds, bitmap: Bitmap?, bounds: RectF) {
        this.quad = newQuad
        this.sourceBitmap = bitmap
        this.imageBounds.set(bounds)
        invalidate()
    }

    fun getQuad(): QuadBounds? = quad

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return

        // 1. Draw polygon boundary path
        val path = Path().apply {
            moveTo(q.topLeft.x, q.topLeft.y)
            lineTo(q.topRight.x, q.topRight.y)
            lineTo(q.bottomRight.x, q.bottomRight.y)
            lineTo(q.bottomLeft.x, q.bottomLeft.y)
            close()
        }

        // 2. Draw dim mask outside crop quadrilateral
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawRect(imageBounds, maskPaint)
        canvas.restore()

        // 3. Draw cyan boundary line
        canvas.drawPath(path, linePaint)

        // 4. Draw 4 midpoints
        val mids = q.getMidPoints()
        mids.forEach { mid ->
            canvas.drawCircle(mid.x, mid.y, midRadius, midHandlePaint)
            canvas.drawCircle(mid.x, mid.y, midRadius / 2f, innerHandlePaint)
        }

        // 5. Draw 4 corner handles
        val corners = q.getCorners()
        corners.forEach { corner ->
            canvas.drawCircle(corner.x, corner.y, cornerRadius, cornerHandlePaint)
            canvas.drawCircle(corner.x, corner.y, innerRadius, innerHandlePaint)
        }

        // 6. Draw Magnifying Loupe when dragging a corner or edge handle
        if (showLoupe && activeDragIndex != -1 && sourceBitmap != null) {
            val targetPoint = if (activeDragIndex in 0..3) {
                corners[activeDragIndex]
            } else if (activeDragIndex in 4..7) {
                mids[activeDragIndex - 4]
            } else null

            targetPoint?.let { pt ->
                drawLoupe(canvas, pt, activeDragIndex)
            }
        }
    }

    private fun drawLoupe(canvas: Canvas, targetPoint: PointF2D, dragIndex: Int) {
        val bitmap = sourceBitmap ?: return
        if (imageBounds.width() <= 0f || imageBounds.height() <= 0f) return

        // 1. Smart vertical & horizontal loupe positioning
        // Avoid finger occlusion: If target is in upper 40% of view, place loupe below finger; else above.
        val fingerClearance = 85f
        var cy = if (targetPoint.y < height * 0.40f) {
            targetPoint.y + loupeRadius + fingerClearance
        } else {
            targetPoint.y - loupeRadius - fingerClearance
        }

        // Keep horizontal center inside view bounds with 20px padding
        val edgePadding = 20f
        var cx = targetPoint.x
        if (cx - loupeRadius < edgePadding) {
            cx = loupeRadius + edgePadding
        } else if (cx + loupeRadius > width - edgePadding) {
            cx = width - loupeRadius - edgePadding
        }

        // Clamp cy within view bounds
        cy = cy.coerceIn(loupeRadius + edgePadding, height - loupeRadius - edgePadding)
        loupeCenter.set(cx, cy)

        // 2. Precise Bitmap Coordinate Mapping
        // Bitmap coordinates of the target point:
        val scaleX = bitmap.width.toFloat() / imageBounds.width()
        val scaleY = bitmap.height.toFloat() / imageBounds.height()

        val bmX = (targetPoint.x - imageBounds.left) * scaleX
        val bmY = (targetPoint.y - imageBounds.top) * scaleY

        // Display scale factor (screen pixels per bitmap pixel)
        val screenPerBmX = imageBounds.width() / bitmap.width.toFloat()
        val screenPerBmY = imageBounds.height() / bitmap.height.toFloat()

        // Magnified scale in the shader
        val shaderScaleX = screenPerBmX * loupeZoom
        val shaderScaleY = screenPerBmY * loupeZoom

        // Matrix maps bitmap (bmX, bmY) to canvas (loupeCenter.x, loupeCenter.y)
        val matrix = Matrix()
        matrix.postScale(shaderScaleX, shaderScaleY)
        val transX = loupeCenter.x - bmX * shaderScaleX
        val transY = loupeCenter.y - bmY * shaderScaleY
        matrix.postTranslate(transX, transY)

        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(matrix)
        loupePaint.shader = shader

        // 3. Drop Shadow for depth & contrast
        canvas.drawCircle(loupeCenter.x, loupeCenter.y + 5f, loupeRadius + 3f, loupeShadowPaint)

        // 4. Background Fill (clean neutral underlay)
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius, loupeBackgroundPaint)

        // 5. Magnified Image Content
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius, loupePaint)

        // 6. Multi-layer High-Contrast Ring Border
        // Solid white inner ring
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius, loupeWhiteRingPaint)
        // Cyan accent ring
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius - 4f, loupeCyanRingPaint)
        // Outer subtle dark contour ring
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius + 1f, loupeOuterRingPaint)

        // 7. Reticle / Crosshair with center clearance
        drawCrosshair(canvas, loupeCenter.x, loupeCenter.y, loupeCrosshairShadowPaint)
        drawCrosshair(canvas, loupeCenter.x, loupeCenter.y, loupeCrosshairPaint)
        // Precision center point dot
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, 4f, loupeCenterDotPaint)
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, 2f, innerHandlePaint)

        // 8. Badge label for the active corner / edge
        val label = when (dragIndex) {
            0 -> "TOP-LEFT"
            1 -> "TOP-RIGHT"
            2 -> "BOTTOM-RIGHT"
            3 -> "BOTTOM-LEFT"
            4 -> "TOP EDGE"
            5 -> "RIGHT EDGE"
            6 -> "BOTTOM EDGE"
            7 -> "LEFT EDGE"
            else -> "CORNER"
        }
        drawBadge(canvas, loupeCenter.x, loupeCenter.y, label)
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        val innerR = 12f
        val outerR = 38f
        // Left ray
        canvas.drawLine(cx - outerR, cy, cx - innerR, cy, paint)
        // Right ray
        canvas.drawLine(cx + innerR, cy, cx + outerR, cy, paint)
        // Top ray
        canvas.drawLine(cx, cy - outerR, cx, cy - innerR, paint)
        // Bottom ray
        canvas.drawLine(cx, cy + innerR, cx, cy + outerR, paint)
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float, label: String) {
        val badgeW = 200f
        val badgeH = 46f
        val badgeY = if (cy < height / 2f) {
            cy + loupeRadius + 28f
        } else {
            cy - loupeRadius - 28f
        }
        val rect = RectF(cx - badgeW / 2f, badgeY - badgeH / 2f, cx + badgeW / 2f, badgeY + badgeH / 2f)
        canvas.drawRoundRect(rect, 23f, 23f, badgePaint)
        val textY = badgeY - ((badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f)
        canvas.drawText(label, cx, textY, badgeTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val q = quad ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tx = event.x
                val ty = event.y
                lastTouch.set(tx, ty)

                // 1. Check corner handles
                val corners = q.getCorners()
                for (i in corners.indices) {
                    if (hypot(tx - corners[i].x, ty - corners[i].y) < touchThreshold) {
                        activeDragIndex = i
                        showLoupe = true
                        invalidate()
                        return true
                    }
                }

                // 2. Check midpoint handles
                val mids = q.getMidPoints()
                for (i in mids.indices) {
                    if (hypot(tx - mids[i].x, ty - mids[i].y) < touchThreshold) {
                        activeDragIndex = 4 + i
                        showLoupe = true
                        invalidate()
                        return true
                    }
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeDragIndex == -1) return false
                val dx = event.x - lastTouch.x
                val dy = event.y - lastTouch.y
                lastTouch.set(event.x, event.y)

                when (activeDragIndex) {
                    0 -> movePoint(q.topLeft, dx, dy)
                    1 -> movePoint(q.topRight, dx, dy)
                    2 -> movePoint(q.bottomRight, dx, dy)
                    3 -> movePoint(q.bottomLeft, dx, dy)
                    4 -> { // Top Edge
                        movePoint(q.topLeft, 0f, dy)
                        movePoint(q.topRight, 0f, dy)
                    }
                    5 -> { // Right Edge
                        movePoint(q.topRight, dx, 0f)
                        movePoint(q.bottomRight, dx, 0f)
                    }
                    6 -> { // Bottom Edge
                        movePoint(q.bottomRight, 0f, dy)
                        movePoint(q.bottomLeft, 0f, dy)
                    }
                    7 -> { // Left Edge
                        movePoint(q.topLeft, dx, 0f)
                        movePoint(q.bottomLeft, dx, 0f)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDragIndex = -1
                showLoupe = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun movePoint(p: PointF2D, dx: Float, dy: Float) {
        val newX = (p.x + dx).coerceIn(imageBounds.left, imageBounds.right)
        val newY = (p.y + dy).coerceIn(imageBounds.top, imageBounds.bottom)
        p.x = newX
        p.y = newY
    }
}
