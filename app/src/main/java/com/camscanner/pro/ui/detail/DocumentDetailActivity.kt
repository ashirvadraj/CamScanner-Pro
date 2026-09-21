package com.camscanner.pro.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.R
import com.camscanner.pro.core.pdf.PdfGenerator
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.data.local.entity.PageEntity
import com.camscanner.pro.databinding.ActivityDocumentDetailBinding
import com.camscanner.pro.databinding.DialogOcrResultBinding
import com.camscanner.pro.ui.camera.CameraActivity
import com.camscanner.pro.ui.crop.CropActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class DocumentDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentDetailBinding
    private val repository by lazy { CamScannerApp.instance.repository }
    private lateinit var adapter: PageGridAdapter

    private var documentId: Long = -1L
    private var currentDoc: DocumentEntity? = null
    private var currentPages: List<PageEntity> = emptyList()

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
                val intent = Intent(this, CropActivity::class.java).apply {
                    putExtra("IMAGE_PATH", tempFile.absolutePath)
                    putExtra("IS_BATCH", false)
                    putExtra("ADD_TO_DOC_ID", documentId)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to import: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        documentId = intent.getLongExtra("DOCUMENT_ID", -1L)
        if (documentId == -1L) {
            Toast.makeText(this, "Invalid document", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupRecyclerView()
        setupListeners()
        loadDocument()
    }

    private fun setupRecyclerView() {
        adapter = PageGridAdapter(
            onPageClick = { page ->
                val intent = Intent(this, com.camscanner.pro.ui.viewer.PagePreviewActivity::class.java).apply {
                    putExtra("PAGE_ID", page.id)
                    putExtra("IMAGE_PATH", page.imagePath)
                }
                startActivity(intent)
            },
            onOcrClick = { page ->
                showOcrDialog(page.ocrText ?: "No text recognized for this page.")
            },
            onDeleteClick = { page ->
                confirmDeletePage(page)
            }
        )

        binding.rvPages.apply {
            layoutManager = GridLayoutManager(this@DocumentDetailActivity, 2)
            adapter = this@DocumentDetailActivity.adapter
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.tvDocTitle.setOnClickListener {
            showRenameDialog()
        }

        binding.btnDeleteDoc.setOnClickListener {
            confirmDeleteDocument()
        }

        binding.btnAddPage.setOnClickListener {
            showAddPageOptions()
        }

        binding.btnExportPdf.setOnClickListener {
            exportPdf()
        }
    }

    private fun loadDocument() {
        lifecycleScope.launch {
            currentDoc = repository.getDocumentById(documentId)
            currentDoc?.let {
                binding.tvDocTitle.text = it.title
            } ?: run {
                finish()
                return@launch
            }

            repository.getPagesForDocument(documentId).collectLatest { pages ->
                currentPages = pages
                adapter.submitList(pages)
                if (pages.isEmpty()) {
                    finish()
                }
            }
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(currentDoc?.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_document)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    lifecycleScope.launch {
                        repository.updateDocumentTitle(documentId, newTitle)
                        binding.tvDocTitle.text = newTitle
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddPageOptions() {
        val options = arrayOf("Take Photo with Camera", "Import from Gallery")
        AlertDialog.Builder(this)
            .setTitle(R.string.add_page)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, CameraActivity::class.java).apply {
                            putExtra("ADD_TO_DOC_ID", documentId)
                        }
                        startActivity(intent)
                    }
                    1 -> {
                        galleryLauncher.launch("image/*")
                    }
                }
            }
            .show()
    }

    private fun exportPdf() {
        if (currentPages.isEmpty()) {
            Toast.makeText(this, "Document has no pages", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = com.camscanner.pro.databinding.DialogPdfOptionsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancelPdf.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnExportSharePdf.setOnClickListener {
            val pageSize = when {
                dialogBinding.rbLetter.isChecked -> com.camscanner.pro.core.pdf.PageSize.US_LETTER
                dialogBinding.rbFitImage.isChecked -> com.camscanner.pro.core.pdf.PageSize.FIT_IMAGE
                else -> com.camscanner.pro.core.pdf.PageSize.A4
            }

            val quality = when {
                dialogBinding.rbQualityCompact.isChecked -> com.camscanner.pro.core.pdf.PdfQuality.COMPACT
                dialogBinding.rbQualityMedium.isChecked -> com.camscanner.pro.core.pdf.PdfQuality.MEDIUM
                else -> com.camscanner.pro.core.pdf.PdfQuality.HIGH
            }

            val watermark = dialogBinding.etWatermark.text?.toString()?.trim()

            val options = com.camscanner.pro.core.pdf.PdfOptions(
                pageSize = pageSize,
                quality = quality,
                watermarkText = if (!watermark.isNullOrBlank()) watermark else null
            )

            dialog.dismiss()

            lifecycleScope.launch {
                binding.btnExportPdf.isEnabled = false
                Toast.makeText(this@DocumentDetailActivity, "Generating PDF...", Toast.LENGTH_SHORT).show()

                val pdfFile = withContext(Dispatchers.IO) {
                    val imagePaths = currentPages.map { it.imagePath }
                    val title = currentDoc?.title ?: "Scanned_Document"
                    PdfGenerator.generatePdf(this@DocumentDetailActivity, imagePaths, title, options)
                }
                binding.btnExportPdf.isEnabled = true

                val pdfUri = FileManager.getUriForFile(this@DocumentDetailActivity, pdfFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, pdfUri)
                    putExtra(Intent.EXTRA_SUBJECT, currentDoc?.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Document PDF"))
            }
        }

        dialog.show()
    }

    private fun confirmDeletePage(page: PageEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Page")
            .setMessage("Are you sure you want to delete Page ${page.pageIndex + 1}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deletePage(page)
                    Toast.makeText(this@DocumentDetailActivity, "Page deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteDocument() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_document)
            .setMessage("Delete this document and all its pages?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteDocument(documentId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
}
