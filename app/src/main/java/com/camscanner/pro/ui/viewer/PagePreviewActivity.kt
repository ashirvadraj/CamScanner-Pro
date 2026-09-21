package com.camscanner.pro.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.R
import com.camscanner.pro.core.barcode.BarcodeScannerManager
import com.camscanner.pro.core.cv.ImageFilterEngine
import com.camscanner.pro.core.cv.SignatureStamper
import com.camscanner.pro.core.cv.WatermarkStamper
import com.camscanner.pro.core.ocr.OcrManager
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.data.local.entity.PageEntity
import com.camscanner.pro.databinding.ActivityPagePreviewBinding
import com.camscanner.pro.databinding.DialogBarcodeResultBinding
import com.camscanner.pro.databinding.DialogOcrResultBinding
import com.camscanner.pro.databinding.DialogSignatureBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPagePreviewBinding
    private val repository by lazy { CamScannerApp.instance.repository }

    private var pageId: Long = -1L
    private var imagePath: String = ""
    private var currentPage: PageEntity? = null
    private var currentBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pageId = intent.getLongExtra("PAGE_ID", -1L)
        imagePath = intent.getStringExtra("IMAGE_PATH") ?: ""

        setupListeners()
        loadPage()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSharePage.setOnClickListener {
            val file = File(imagePath)
            if (file.exists()) {
                val uri = FileManager.getUriForFile(this, file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Page Image"))
            }
        }

        binding.btnDeletePage.setOnClickListener {
            confirmDeletePage()
        }

        binding.toolSign.setOnClickListener {
            showSignatureDialog()
        }

        binding.toolWatermark.setOnClickListener {
            showWatermarkDialog()
        }

        binding.toolBarcode.setOnClickListener {
            scanBarcodeOnPage()
        }

        binding.toolOcr.setOnClickListener {
            runOcrOnPage()
        }

        binding.toolRotate.setOnClickListener {
            rotatePage()
        }
    }

    private fun loadPage() {
        lifecycleScope.launch {
            if (pageId != -1L) {
                val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@PagePreviewActivity)
                currentPage = db.pageDao().getPageById(pageId)
                currentPage?.let {
                    imagePath = it.imagePath
                    binding.tvPreviewTitle.text = "Page ${it.pageIndex + 1} Preview"
                }
            }

            val bm = withContext(Dispatchers.IO) {
                FileManager.loadSampledBitmap(imagePath, maxDim = 1920)
            }
            if (bm == null) {
                Toast.makeText(this@PagePreviewActivity, "Unable to load image", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            currentBitmap = bm
            binding.ivFullPage.setImageBitmap(bm)
        }
    }

    private fun showSignatureDialog() {
        val dialogBinding = DialogSignatureBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        var selectedColor = Color.parseColor("#1A1C1E")

        dialogBinding.colorBlack.setOnClickListener {
            selectedColor = Color.parseColor("#1A1C1E")
            dialogBinding.signaturePad.setInkColor(selectedColor)
        }

        dialogBinding.colorBlue.setOnClickListener {
            selectedColor = Color.parseColor("#0D47A1")
            dialogBinding.signaturePad.setInkColor(selectedColor)
        }

        dialogBinding.colorRed.setOnClickListener {
            selectedColor = Color.parseColor("#C62828")
            dialogBinding.signaturePad.setInkColor(selectedColor)
        }

        dialogBinding.btnClearSig.setOnClickListener {
            dialogBinding.signaturePad.clear()
        }

        dialogBinding.btnCancelSig.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnApplySig.setOnClickListener {
            val sigBitmap = dialogBinding.signaturePad.getSignatureBitmap()
            if (sigBitmap == null) {
                Toast.makeText(this, "Please draw a signature first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val baseBm = currentBitmap ?: return@setOnClickListener
            lifecycleScope.launch {
                val stamped = withContext(Dispatchers.Default) {
                    SignatureStamper.stampSignature(baseBm, sigBitmap)
                }
                currentBitmap = stamped
                binding.ivFullPage.setImageBitmap(stamped)

                // Save updated image
                val newFile = withContext(Dispatchers.IO) {
                    FileManager.saveBitmap(this@PagePreviewActivity, stamped, "SIGNED")
                }
                imagePath = newFile.absolutePath
                if (pageId != -1L) {
                    repository.updatePageImage(pageId, newFile.absolutePath)
                }
                Toast.makeText(this@PagePreviewActivity, "Signature stamped successfully!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showWatermarkDialog() {
        val input = EditText(this).apply {
            hint = "e.g. CONFIDENTIAL / DO NOT COPY"
            setText("CONFIDENTIAL")
        }
        AlertDialog.Builder(this)
            .setTitle("Add Watermark")
            .setMessage("Enter text to stamp diagonally across this page:")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val baseBm = currentBitmap ?: return@setPositiveButton
                    lifecycleScope.launch {
                        val watermarked = withContext(Dispatchers.Default) {
                            WatermarkStamper.stampWatermark(baseBm, text)
                        }
                        currentBitmap = watermarked
                        binding.ivFullPage.setImageBitmap(watermarked)

                        val newFile = withContext(Dispatchers.IO) {
                            FileManager.saveBitmap(this@PagePreviewActivity, watermarked, "WATERMARKED")
                        }
                        imagePath = newFile.absolutePath
                        if (pageId != -1L) {
                            repository.updatePageImage(pageId, newFile.absolutePath)
                        }
                        Toast.makeText(this@PagePreviewActivity, "Watermark stamped!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanBarcodeOnPage() {
        val bm = currentBitmap ?: return
        lifecycleScope.launch {
            Toast.makeText(this@PagePreviewActivity, "Scanning for Barcode / QR Code...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.Default) {
                BarcodeScannerManager.scanBarcode(bm)
            }

            result.onSuccess { barcode ->
                if (barcode != null) {
                    showBarcodeDialog(barcode)
                } else {
                    Toast.makeText(this@PagePreviewActivity, "No barcode or QR code detected on this page.", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                Toast.makeText(this@PagePreviewActivity, "Scan error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBarcodeDialog(barcode: com.camscanner.pro.core.barcode.BarcodeResult) {
        val dialogBinding = DialogBarcodeResultBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvBarcodeFormat.text = barcode.formatName
        dialogBinding.tvBarcodeValue.text = barcode.displayValue

        if (!barcode.url.isNullOrBlank()) {
            dialogBinding.btnOpenUrl.visibility = View.VISIBLE
            dialogBinding.btnOpenUrl.setOnClickListener {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(barcode.url))
                startActivity(browserIntent)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCopyBarcode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Barcode Value", barcode.displayValue)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnCloseBarcode.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun runOcrOnPage() {
        val bm = currentBitmap ?: return
        lifecycleScope.launch {
            Toast.makeText(this@PagePreviewActivity, "Recognizing text...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.Default) {
                OcrManager.recognizeText(bm)
            }
            result.onSuccess { text ->
                showOcrDialog(text)
            }.onFailure { error ->
                Toast.makeText(this@PagePreviewActivity, "OCR Error: ${error.message}", Toast.LENGTH_LONG).show()
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

    private fun rotatePage() {
        val bm = currentBitmap ?: return
        lifecycleScope.launch {
            val rotated = withContext(Dispatchers.Default) {
                ImageFilterEngine.rotateBitmap(bm, 90f)
            }
            currentBitmap = rotated
            binding.ivFullPage.setImageBitmap(rotated)

            val newFile = withContext(Dispatchers.IO) {
                FileManager.saveBitmap(this@PagePreviewActivity, rotated, "ROTATED")
            }
            imagePath = newFile.absolutePath
            if (pageId != -1L) {
                repository.updatePageImage(pageId, newFile.absolutePath)
            }
            Toast.makeText(this@PagePreviewActivity, "Rotated 90°", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeletePage() {
        val page = currentPage ?: run {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete Page")
            .setMessage("Are you sure you want to delete this page?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deletePage(page)
                    Toast.makeText(this@PagePreviewActivity, "Page deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
