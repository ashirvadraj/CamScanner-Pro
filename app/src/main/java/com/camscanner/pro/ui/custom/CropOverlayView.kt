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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.camscanner.pro.core.cv.PointF2D
import com.camscanner.pro.core.cv.QuadBounds
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

    // Loupe Paint
    private val loupeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val loupeCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cornerRadius = 32f
    private val innerRadius = 14f
    private val midRadius = 16f
    private val touchThreshold = 80f

    // Active drag index: 0..3 = Corners (TL, TR, BR, BL), 4..7 = Mids (T, R, B, L), -1 = None
    private var activeDragIndex = -1
    private val lastTouch = PointF()

    // Loupe configuration
    private val loupeRadius = 120f
    private val loupeZoom = 2.0f
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

        // 6. Draw Magnifying Loupe when dragging a corner
        if (showLoupe && activeDragIndex in 0..3 && sourceBitmap != null) {
            drawLoupe(canvas, corners[activeDragIndex])
        }
    }

    private fun drawLoupe(canvas: Canvas, targetCorner: PointF2D) {
        val bitmap = sourceBitmap ?: return

        // Position loupe offset above the active touch
        loupeCenter.x = targetCorner.x
        loupeCenter.y = max(loupeRadius + 20f, targetCorner.y - loupeRadius - 60f)

        // Ensure loupe stays inside view horizontally
        if (loupeCenter.x - loupeRadius < 20f) loupeCenter.x = loupeRadius + 20f
        if (loupeCenter.x + loupeRadius > width - 20f) loupeCenter.x = width - loupeRadius - 20f

        // Map targetCorner from View coordinates to sourceBitmap coordinates
        val scaleX = bitmap.width.toFloat() / imageBounds.width()
        val scaleY = bitmap.height.toFloat() / imageBounds.height()

        // Build shader with 2x magnification centered at target point
        val matrix = Matrix()
        matrix.postScale(loupeZoom / scaleX, loupeZoom / scaleY)
        matrix.postTranslate(
            loupeCenter.x - targetCorner.x * loupeZoom,
            loupeCenter.y - targetCorner.y * loupeZoom
        )

        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(matrix)
        loupePaint.shader = shader

        // Draw zoomed circle
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius, loupePaint)
        canvas.drawCircle(loupeCenter.x, loupeCenter.y, loupeRadius, loupeBorderPaint)

        // Draw crosshair
        canvas.drawLine(
            loupeCenter.x - 20f, loupeCenter.y,
            loupeCenter.x + 20f, loupeCenter.y,
            loupeCrosshairPaint
        )
        canvas.drawLine(
            loupeCenter.x, loupeCenter.y - 20f,
            loupeCenter.x, loupeCenter.y + 20f,
            loupeCrosshairPaint
        )
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
                        showLoupe = false
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
