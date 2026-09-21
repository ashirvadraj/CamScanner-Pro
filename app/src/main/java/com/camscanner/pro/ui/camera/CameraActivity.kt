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
import com.camscanner.pro.R
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityCameraBinding
import com.camscanner.pro.ui.crop.CropActivity
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isBatchMode = false
    private val batchImagePaths = ArrayList<String>()

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
            isBatchMode = false
            binding.tabSingle.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.tabBatch.setBackgroundColor(0)
            binding.btnDoneBatch.visibility = if (batchImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.tabBatch.setOnClickListener {
            isBatchMode = true
            binding.tabBatch.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.tabSingle.setBackgroundColor(0)
            binding.btnDoneBatch.visibility = if (batchImagePaths.isNotEmpty()) View.VISIBLE else View.GONE
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
                    if (isBatchMode) {
                        batchImagePaths.add(photoFile.absolutePath)
                        binding.tvBatchCount.text = batchImagePaths.size.toString()
                        binding.btnDoneBatch.visibility = View.VISIBLE
                        Toast.makeText(this@CameraActivity, "Page ${batchImagePaths.size} captured", Toast.LENGTH_SHORT).show()
                    } else {
                        openCrop(photoFile.absolutePath)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
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
