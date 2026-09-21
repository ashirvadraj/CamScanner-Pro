package com.camscanner.pro.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.R
import com.camscanner.pro.core.migration.CamScannerImporter
import com.camscanner.pro.core.pdf.PdfGenerator
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.databinding.ActivityMainBinding
import com.camscanner.pro.databinding.DialogPdfOptionsBinding
import com.camscanner.pro.ui.backup.BackupActivity
import com.camscanner.pro.ui.camera.CameraActivity
import com.camscanner.pro.ui.crop.CropActivity
import com.camscanner.pro.ui.detail.DocumentDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { CamScannerApp.instance.repository }
    private lateinit var adapter: DocumentAdapter

    private var currentCategory: String = "ALL"
    private var currentQuery: String = ""

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan documents", Toast.LENGTH_SHORT).show()
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleGalleryImage(it) }
    }

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importCamScannerPdf(it) }
    }

    private val imageBatchPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importBatchImages(uris)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { importFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        applyFilters()
    }

    private fun setupRecyclerView() {
        adapter = DocumentAdapter(
            onItemClick = { doc ->
                val intent = Intent(this, DocumentDetailActivity::class.java).apply {
                    putExtra("DOCUMENT_ID", doc.id)
                }
                startActivity(intent)
            },
            onMoreClick = { view, doc ->
                showDocumentMenu(view, doc)
            }
        )

        binding.rvDocuments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupListeners() {
        binding.fabCamera.setOnClickListener {
            checkCameraPermissionAndStart()
        }

        binding.fabGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        binding.btnCloudBackup.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        binding.btnImport.setOnClickListener { v ->
            showImportMenu(v)
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
            applyFilters()
        }

        binding.etSearch.addTextChangedListener { text ->
            currentQuery = text?.toString()?.trim().orEmpty()
            applyFilters()
        }

        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipAll
            currentCategory = when (checkedId) {
                R.id.chipIdCard -> "ID_CARD"
                R.id.chipReceipt -> "RECEIPT"
                R.id.chipContract -> "CONTRACT"
                R.id.chipPersonal -> "PERSONAL"
                R.id.chipWork -> "WORK"
                else -> "ALL"
            }
            applyFilters()
        }
    }

    private fun applyFilters() {
        lifecycleScope.launch {
            repository.filterDocuments(currentCategory, currentQuery).collectLatest { docs ->
                updateList(docs)
            }
        }
    }

    private fun updateList(docs: List<DocumentEntity>) {
        adapter.submitList(docs)
        if (docs.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvDocuments.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvDocuments.visibility = View.VISIBLE
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val intent = Intent(this, CameraActivity::class.java)
        startActivity(intent)
    }

    private fun handleGalleryImage(uri: Uri) {
        try {
            val tempFile = FileManager.createTempImageFile(this)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            val intent = Intent(this, CropActivity::class.java).apply {
                putExtra("IMAGE_PATH", tempFile.absolutePath)
                putExtra("IS_BATCH", false)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDocumentMenu(view: View, doc: DocumentEntity) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "Export as PDF")
        popup.menu.add(0, 2, 1, "Assign Category")
        popup.menu.add(0, 3, 2, "Delete Document")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> exportPdf(doc)
                2 -> showCategoryDialog(doc)
                3 -> confirmDelete(doc)
            }
            true
        }
        popup.show()
    }

    private fun showCategoryDialog(doc: DocumentEntity) {
        val categories = arrayOf("ID_CARD", "RECEIPT", "CONTRACT", "PERSONAL", "WORK", "ALL")
        val displayLabels = arrayOf("🆔 ID Card", "🧾 Receipt", "📝 Contract", "👤 Personal", "💼 Work", "None (All)")

        AlertDialog.Builder(this)
            .setTitle("Assign Category")
            .setItems(displayLabels) { _, which ->
                val selected = categories[which]
                lifecycleScope.launch {
                    repository.updateDocumentCategory(doc.id, selected)
                    Toast.makeText(this@MainActivity, "Category updated to ${displayLabels[which]}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun exportPdf(doc: DocumentEntity) {
        val dialogBinding = DialogPdfOptionsBinding.inflate(layoutInflater)
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
                try {
                    val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@MainActivity)
                    val pages = db.pageDao().getPagesForDocument(doc.id)
                    if (pages.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No pages to export", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val imagePaths = pages.map { it.imagePath }
                    val pdfFile = withContext(Dispatchers.IO) {
                        PdfGenerator.generatePdf(this@MainActivity, imagePaths, doc.title, options)
                    }
                    val pdfUri = FileManager.getUriForFile(this@MainActivity, pdfFile)

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, pdfUri)
                        putExtra(Intent.EXTRA_SUBJECT, doc.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Document PDF"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "PDF export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun showImportMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "📄 Import CamScanner PDF")
        popup.menu.add(0, 2, 1, "🖼️ Import Multiple Images")
        popup.menu.add(0, 3, 2, "📁 Import CamScanner Folder")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> pdfPickerLauncher.launch("application/pdf")
                2 -> imageBatchPickerLauncher.launch("image/*")
                3 -> folderPickerLauncher.launch(null)
            }
            true
        }
        popup.show()
    }

    private fun importCamScannerPdf(uri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Importing CamScanner PDF")
            .setMessage("Rendering pages and analyzing text...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = CamScannerImporter.importPdf(this@MainActivity, uri)
            progress.dismiss()
            result.onSuccess {
                Toast.makeText(this@MainActivity, "CamScanner PDF imported successfully!", Toast.LENGTH_SHORT).show()
                applyFilters()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importBatchImages(uris: List<Uri>) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Importing Scans")
            .setMessage("Importing ${uris.size} pages...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val title = "CamScanner_Import_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val result = CamScannerImporter.importImagesAsDocument(this@MainActivity, uris, title)
            progress.dismiss()
            result.onSuccess {
                Toast.makeText(this@MainActivity, "Imported ${uris.size} pages into new document!", Toast.LENGTH_SHORT).show()
                applyFilters()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Batch import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importFolder(treeUri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Importing Folder")
            .setMessage("Scanning folder for CamScanner documents...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = CamScannerImporter.importFromFolder(this@MainActivity, treeUri)
            progress.dismiss()
            result.onSuccess { res ->
                Toast.makeText(this@MainActivity, "Imported ${res.documentsImported} document(s) (${res.pagesImported} pages)!", Toast.LENGTH_LONG).show()
                applyFilters()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Folder import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(doc: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Document")
            .setMessage("Are you sure you want to delete \"${doc.title}\"? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteDocument(doc.id)
                    Toast.makeText(this@MainActivity, "Document deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
