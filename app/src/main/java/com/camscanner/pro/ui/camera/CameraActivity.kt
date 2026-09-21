package com.camscanner.pro.ui.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.camscanner.pro.R
import com.camscanner.pro.core.cv.IdCardMerger
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityCameraBinding
import com.camscanner.pro.ui.crop.CropActivity
import com.camscanner.pro.ui.filter.FilterActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

enum class ScanMode {
    SINGLE,
    BATCH,
    ID_CARD
}

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentMode = ScanMode.SINGLE
    private val batchImagePaths = ArrayList<String>()

    // ID Card state
    private var idCardFrontPath: String? = null

    private var flashMode = ImageCapture.FLASH_MODE_AUTO

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val tempFile = FileManager.createTempImageFile(this)
                contentResolver.openInputStream(it)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                openCrop(tempFile.absolutePath)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to import: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        startCamera()
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener { finish() }

        binding.tabSingle.setOnClickListener {
            selectMode(ScanMode.SINGLE)
        }

        binding.tabBatch.setOnClickListener {
            selectMode(ScanMode.BATCH)
        }

        binding.tabIdCard.setOnClickListener {
            selectMode(ScanMode.ID_CARD)
        }

        binding.btnFlash.setOnClickListener {
            cycleFlash()
        }

        binding.btnGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        binding.btnShutter.setOnClickListener {
            takePhoto()
        }

        binding.btnDoneBatch.setOnClickListener {
            if (batchImagePaths.isNotEmpty()) {
                val firstPath = batchImagePaths.removeAt(0)
                val intent = Intent(this, CropActivity::class.java).apply {
                    putExtra("IMAGE_PATH", firstPath)
                    putExtra("IS_BATCH", true)
                    putStringArrayListExtra("BATCH_REMAINING", batchImagePaths)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun selectMode(mode: ScanMode) {
        currentMode = mode
        val primary = ContextCompat.getColor(this, R.color.primary)

        binding.tabSingle.setBackgroundColor(if (mode == ScanMode.SINGLE) primary else 0)
        binding.tabBatch.setBackgroundColor(if (mode == ScanMode.BATCH) primary else 0)
        binding.tabIdCard.setBackgroundColor(if (mode == ScanMode.ID_CARD) primary else 0)

        when (mode) {
            ScanMode.SINGLE -> {
                binding.tvCameraHint.text = "Align document inside frame"
                binding.btnDoneBatch.visibility = View.GONE
                idCardFrontPath = null
            }
            ScanMode.BATCH -> {
                binding.tvCameraHint.text = "Batch Mode: Scan multiple pages rapidly"
                binding.btnDoneBatch.visibility = if (batchImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
                idCardFrontPath = null
            }
            ScanMode.ID_CARD -> {
                idCardFrontPath = null
                binding.tvCameraHint.text = "Step 1/2: Scan FRONT side of ID Card"
                binding.btnDoneBatch.visibility = View.GONE
            }
        }
    }

    private fun cycleFlash() {
        val capture = imageCapture ?: return
        when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                flashMode = ImageCapture.FLASH_MODE_ON
                binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
            }
            ImageCapture.FLASH_MODE_ON -> {
                flashMode = ImageCapture.FLASH_MODE_OFF
                binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
            }
            ImageCapture.FLASH_MODE_OFF -> {
                flashMode = ImageCapture.FLASH_MODE_AUTO
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
            }
        }
        capture.flashMode = flashMode
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val photoFile = FileManager.createTempImageFile(this)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        binding.btnShutter.isEnabled = false
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    binding.btnShutter.isEnabled = true

                    when (currentMode) {
                        ScanMode.SINGLE -> {
                            openCrop(photoFile.absolutePath)
                        }
                        ScanMode.BATCH -> {
                            batchImagePaths.add(photoFile.absolutePath)
                            binding.tvBatchCount.text = batchImagePaths.size.toString()
                            binding.btnDoneBatch.visibility = View.VISIBLE
                            Toast.makeText(this@CameraActivity, "Page ${batchImagePaths.size} captured", Toast.LENGTH_SHORT).show()
                        }
                        ScanMode.ID_CARD -> {
                            handleIdCardCapture(photoFile.absolutePath)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun handleIdCardCapture(capturedPath: String) {
        if (idCardFrontPath == null) {
            idCardFrontPath = capturedPath
            binding.tvCameraHint.text = "Step 2/2: Flip card & scan BACK side"
            Toast.makeText(this, "Front side captured! Now scan the back side.", Toast.LENGTH_LONG).show()
        } else {
            val frontPath = idCardFrontPath!!
            val backPath = capturedPath
            binding.tvCameraHint.text = "Merging ID Card sides..."

            lifecycleScope.launch {
                val frontBm = withContext(Dispatchers.IO) {
                    FileManager.loadSampledBitmap(frontPath, maxDim = 1600)
                }
                val backBm = withContext(Dispatchers.IO) {
                    FileManager.loadSampledBitmap(backPath, maxDim = 1600)
                }

                if (frontBm != null && backBm != null) {
                    val mergedBm = withContext(Dispatchers.Default) {
                        IdCardMerger.mergeIdCards(frontBm, backBm)
                    }
                    val mergedFile = withContext(Dispatchers.IO) {
                        FileManager.saveBitmap(this@CameraActivity, mergedBm, "ID_CARD")
                    }

                    val intent = Intent(this@CameraActivity, FilterActivity::class.java).apply {
                        putExtra("IMAGE_PATH", mergedFile.absolutePath)
                        putExtra("ORIGINAL_IMAGE_PATH", mergedFile.absolutePath)
                        putExtra("CATEGORY", "ID_CARD")
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@CameraActivity, "Failed to load card captures", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openCrop(imagePath: String) {
        val docId = intent.getLongExtra("ADD_TO_DOC_ID", -1L)
        val intent = Intent(this, CropActivity::class.java).apply {
            putExtra("IMAGE_PATH", imagePath)
            putExtra("IS_BATCH", false)
            if (docId != -1L) putExtra("ADD_TO_DOC_ID", docId)
        }
        startActivity(intent)
        finish()
    }
}
