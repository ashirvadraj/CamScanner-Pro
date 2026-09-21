package com.camscanner.pro.ui.filter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.R
import com.camscanner.pro.core.cv.FilterType
import com.camscanner.pro.core.cv.ImageFilterEngine
import com.camscanner.pro.core.ocr.OcrManager
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityFilterBinding
import com.camscanner.pro.databinding.DialogOcrResultBinding
import com.camscanner.pro.ui.crop.CropActivity
import com.camscanner.pro.ui.detail.DocumentDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilterBinding
    private val repository by lazy { CamScannerApp.instance.repository }

    private var imagePath: String = ""
    private var originalImagePath: String = ""
    private var baseBitmap: Bitmap? = null
    private var currentFilteredBitmap: Bitmap? = null
    private var currentFilterType = FilterType.MAGIC_COLOR
    private var extractedOcrText: String? = null

    private var isBatch: Boolean = false
    private var batchRemaining = java.util.ArrayList<String>()
    private var addToDocId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imagePath = intent.getStringExtra("IMAGE_PATH") ?: ""
        originalImagePath = intent.getStringExtra("ORIGINAL_IMAGE_PATH") ?: imagePath
        isBatch = intent.getBooleanExtra("IS_BATCH", false)
        batchRemaining = intent.getStringArrayListExtra("BATCH_REMAINING") ?: java.util.ArrayList()
        addToDocId = intent.getLongExtra("ADD_TO_DOC_ID", -1L)

        if (imagePath.isEmpty() || !File(imagePath).exists()) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupButtons()
        loadBaseImageAndSetupFilters()
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRotate.setOnClickListener {
            val bm = baseBitmap ?: return@setOnClickListener
            val rotated = ImageFilterEngine.rotateBitmap(bm, 90f)
            baseBitmap = rotated
            applyCurrentFilter()
        }

        binding.btnOcr.setOnClickListener {
            runOcr()
        }

        binding.btnSave.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun loadBaseImageAndSetupFilters() {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                FileManager.loadSampledBitmap(imagePath, maxDim = 1920)
            }

            if (bitmap == null) {
                Toast.makeText(this@FilterActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            baseBitmap = bitmap

            // Generate thumbnails for filter carousel
            val thumbSize = 120
            val thumb = Bitmap.createScaledBitmap(bitmap, thumbSize, thumbSize, true)

            val filterItems = withContext(Dispatchers.Default) {
                FilterType.values().map { type ->
                    val filterThumb = ImageFilterEngine.applyFilter(thumb, type)
                    FilterItem(type = type, thumbnail = filterThumb)
                }
            }

            binding.rvFilters.apply {
                layoutManager = LinearLayoutManager(this@FilterActivity, LinearLayoutManager.HORIZONTAL, false)
                adapter = FilterAdapter(filterItems) { selectedType ->
                    currentFilterType = selectedType
                    applyCurrentFilter()
                }
            }

            // Apply default Magic Color
            applyCurrentFilter()
        }
    }

    private fun applyCurrentFilter() {
        val base = baseBitmap ?: return
        lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                ImageFilterEngine.applyFilter(base, currentFilterType)
            }
            currentFilteredBitmap = filtered
            binding.ivPreview.setImageBitmap(filtered)
        }
    }

    private fun runOcr() {
        val bm = currentFilteredBitmap ?: return
        lifecycleScope.launch {
            binding.btnOcr.isEnabled = false
            Toast.makeText(this@FilterActivity, "Analyzing document text...", Toast.LENGTH_SHORT).show()

            val result = withContext(Dispatchers.Default) {
                OcrManager.recognizeText(bm)
            }
            binding.btnOcr.isEnabled = true

            result.onSuccess { text ->
                extractedOcrText = text
                showOcrDialog(text)
            }.onFailure { error ->
                Toast.makeText(this@FilterActivity, "OCR Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOcrDialog(text: String) {
        val dialogBinding = DialogOcrResultBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvOcrContent.text = text

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCopyText.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Scanned Text", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialogBinding.btnShareText.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Scanned Text"))
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveAndFinish() {
        val filtered = currentFilteredBitmap ?: return
        lifecycleScope.launch {
            binding.btnSave.isEnabled = false

            val finalFile = withContext(Dispatchers.IO) {
                FileManager.saveBitmap(this@FilterActivity, filtered, "PROCESSED")
            }

            var targetDocId = addToDocId
            if (targetDocId == -1L) {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val title = "Scan_$timeStamp"
                targetDocId = repository.createDocumentWithPage(
                    title = title,
                    processedImagePath = finalFile.absolutePath,
                    originalImagePath = originalImagePath,
                    filterType = currentFilterType.name,
                    ocrText = extractedOcrText
                )
            } else {
                repository.addPageToDocument(
                    documentId = targetDocId,
                    processedImagePath = finalFile.absolutePath,
                    originalImagePath = originalImagePath,
                    filterType = currentFilterType.name,
                    ocrText = extractedOcrText
                )
            }

            // Check if there are remaining batch images
            if (batchRemaining.isNotEmpty()) {
                val nextPath = batchRemaining.removeAt(0)
                val intent = Intent(this@FilterActivity, CropActivity::class.java).apply {
                    putExtra("IMAGE_PATH", nextPath)
                    putExtra("IS_BATCH", true)
                    putStringArrayListExtra("BATCH_REMAINING", batchRemaining)
                    putExtra("ADD_TO_DOC_ID", targetDocId)
                }
                startActivity(intent)
                finish()
            } else {
                val intent = Intent(this@FilterActivity, DocumentDetailActivity::class.java).apply {
                    putExtra("DOCUMENT_ID", targetDocId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
