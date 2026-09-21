package com.camscanner.pro.ui.crop

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscanner.pro.core.cv.EdgeDetector
import com.camscanner.pro.core.cv.ImageFilterEngine
import com.camscanner.pro.core.cv.PerspectiveTransformer
import com.camscanner.pro.core.cv.PointF2D
import com.camscanner.pro.core.cv.QuadBounds
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityCropBinding
import com.camscanner.pro.ui.filter.FilterActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class CropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropBinding
    private var imagePath: String = ""
    private var sourceBitmap: Bitmap? = null
    private val displayBounds = RectF()
    private var isBatch: Boolean = false
    private var batchRemaining = java.util.ArrayList<String>()
    private var addToDocId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imagePath = intent.getStringExtra("IMAGE_PATH") ?: ""
        isBatch = intent.getBooleanExtra("IS_BATCH", false)
        batchRemaining = intent.getStringArrayListExtra("BATCH_REMAINING") ?: java.util.ArrayList()
        addToDocId = intent.getLongExtra("ADD_TO_DOC_ID", -1L)

        if (imagePath.isEmpty() || !File(imagePath).exists()) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupButtons()
        loadImageAndDetectEdges()
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRotate.setOnClickListener {
            val bm = sourceBitmap ?: return@setOnClickListener
            val rotated = ImageFilterEngine.rotateBitmap(bm, 90f)
            sourceBitmap = rotated
            binding.ivCropSource.setImageBitmap(rotated)
            autoDetectEdges()
        }

        binding.btnAutoDetect.setOnClickListener {
            autoDetectEdges()
        }

        binding.btnFullDoc.setOnClickListener {
            applyFullDocumentBounds()
        }

        binding.btnFullImage.setOnClickListener {
            val q = QuadBounds(
                PointF2D(displayBounds.left, displayBounds.top),
                PointF2D(displayBounds.right, displayBounds.top),
                PointF2D(displayBounds.right, displayBounds.bottom),
                PointF2D(displayBounds.left, displayBounds.bottom)
            )
            binding.cropOverlay.setQuad(q, sourceBitmap, displayBounds)
        }

        binding.btnNext.setOnClickListener {
            processCropAndProceed()
        }
    }

    private fun loadImageAndDetectEdges() {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                FileManager.loadSampledBitmap(imagePath, maxDim = 1920)
            }

            if (bitmap == null) {
                Toast.makeText(this@CropActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            sourceBitmap = bitmap
            binding.ivCropSource.setImageBitmap(bitmap)

            // Wait for view layout to compute fitCenter display bounds
            binding.ivCropSource.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        binding.ivCropSource.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        calculateDisplayBounds()
                        autoDetectEdges()
                    }
                }
            )
        }
    }

    private fun calculateDisplayBounds() {
        val bm = sourceBitmap ?: return
        val viewW = binding.cropContainer.width.toFloat()
        val viewH = binding.cropContainer.height.toFloat()

        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / bm.width, viewH / bm.height)
        val drawnW = bm.width * scale
        val drawnH = bm.height * scale

        val left = (viewW - drawnW) / 2f
        val top = (viewH - drawnH) / 2f

        displayBounds.set(left, top, left + drawnW, top + drawnH)
    }

    private fun autoDetectEdges() {
        val bm = sourceBitmap ?: return
        lifecycleScope.launch {
            val detected = withContext(Dispatchers.Default) {
                EdgeDetector.detectDocumentEdges(bm)
            }

            // Map detected quad from bitmap coordinates to view coordinates
            val scaleX = displayBounds.width() / bm.width.toFloat()
            val scaleY = displayBounds.height() / bm.height.toFloat()

            val viewQuad = QuadBounds(
                PointF2D(displayBounds.left + detected.topLeft.x * scaleX, displayBounds.top + detected.topLeft.y * scaleY),
                PointF2D(displayBounds.left + detected.topRight.x * scaleX, displayBounds.top + detected.topRight.y * scaleY),
                PointF2D(displayBounds.left + detected.bottomRight.x * scaleX, displayBounds.top + detected.bottomRight.y * scaleY),
                PointF2D(displayBounds.left + detected.bottomLeft.x * scaleX, displayBounds.top + detected.bottomLeft.y * scaleY)
            )

            binding.cropOverlay.setQuad(viewQuad, bm, displayBounds)
        }
    }

    private fun applyFullDocumentBounds() {
        val bm = sourceBitmap ?: return
        val fullDocQuad = EdgeDetector.detectFullPage(bm)
        val scaleX = displayBounds.width() / bm.width.toFloat()
        val scaleY = displayBounds.height() / bm.height.toFloat()

        val viewQuad = QuadBounds(
            PointF2D(displayBounds.left + fullDocQuad.topLeft.x * scaleX, displayBounds.top + fullDocQuad.topLeft.y * scaleY),
            PointF2D(displayBounds.left + fullDocQuad.topRight.x * scaleX, displayBounds.top + fullDocQuad.topRight.y * scaleY),
            PointF2D(displayBounds.left + fullDocQuad.bottomRight.x * scaleX, displayBounds.top + fullDocQuad.bottomRight.y * scaleY),
            PointF2D(displayBounds.left + fullDocQuad.bottomLeft.x * scaleX, displayBounds.top + fullDocQuad.bottomLeft.y * scaleY)
        )
        binding.cropOverlay.setQuad(viewQuad, bm, displayBounds)
    }

    private fun processCropAndProceed() {
        val bm = sourceBitmap ?: return
        val viewQuad = binding.cropOverlay.getQuad() ?: return

        lifecycleScope.launch {
            binding.btnNext.isEnabled = false

            // Convert view quad points back to bitmap coordinates
            val scaleX = bm.width.toFloat() / displayBounds.width()
            val scaleY = bm.height.toFloat() / displayBounds.height()

            val bitmapQuad = QuadBounds(
                PointF2D((viewQuad.topLeft.x - displayBounds.left) * scaleX, (viewQuad.topLeft.y - displayBounds.top) * scaleY),
                PointF2D((viewQuad.topRight.x - displayBounds.left) * scaleX, (viewQuad.topRight.y - displayBounds.top) * scaleY),
                PointF2D((viewQuad.bottomRight.x - displayBounds.left) * scaleX, (viewQuad.bottomRight.y - displayBounds.top) * scaleY),
                PointF2D((viewQuad.bottomLeft.x - displayBounds.left) * scaleX, (viewQuad.bottomLeft.y - displayBounds.top) * scaleY)
            )
            bitmapQuad.clamp(bm.width.toFloat(), bm.height.toFloat())

            val cropped = withContext(Dispatchers.Default) {
                PerspectiveTransformer.warpPerspective(bm, bitmapQuad)
            }

            val savedCroppedFile = withContext(Dispatchers.IO) {
                FileManager.saveBitmap(this@CropActivity, cropped, "CROP")
            }

            val intent = Intent(this@CropActivity, FilterActivity::class.java).apply {
                putExtra("IMAGE_PATH", savedCroppedFile.absolutePath)
                putExtra("ORIGINAL_IMAGE_PATH", imagePath)
                putExtra("IS_BATCH", isBatch)
                putStringArrayListExtra("BATCH_REMAINING", batchRemaining)
                if (addToDocId != -1L) putExtra("ADD_TO_DOC_ID", addToDocId)
            }
            startActivity(intent)
            finish()
        }
    }
}
