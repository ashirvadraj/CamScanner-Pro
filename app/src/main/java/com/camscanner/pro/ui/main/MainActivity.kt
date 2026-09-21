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
import com.camscanner.pro.core.auth.GoogleAuthManager
import com.camscanner.pro.core.backup.CloudBackupManager
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
import com.camscanner.pro.ui.tools.MergeCompressActivity
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
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handlePickedImages(uris)
        }
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
        updateAccountBanner()
    }

    override fun onResume() {
        super.onResume()
        updateAccountBanner()
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

        binding.btnMergeCompress.setOnClickListener {
            startActivity(Intent(this, MergeCompressActivity::class.java))
        }

        binding.cardQuickCompressImage.setOnClickListener {
            val intent = Intent(this, MergeCompressActivity::class.java).apply {
                putExtra(MergeCompressActivity.EXTRA_INITIAL_TAB, MergeCompressActivity.TAB_COMPRESS_IMAGE)
            }
            startActivity(intent)
        }

        binding.cardQuickCompressPdf.setOnClickListener {
            val intent = Intent(this, MergeCompressActivity::class.java).apply {
                putExtra(MergeCompressActivity.EXTRA_INITIAL_TAB, MergeCompressActivity.TAB_COMPRESS_PDF)
            }
            startActivity(intent)
        }

        binding.cardQuickMergePdf.setOnClickListener {
            val intent = Intent(this, MergeCompressActivity::class.java).apply {
                putExtra(MergeCompressActivity.EXTRA_INITIAL_TAB, MergeCompressActivity.TAB_MERGE_PDF)
            }
            startActivity(intent)
        }

        binding.cardQuickMultiImageScan.setOnClickListener {
            galleryLauncher.launch("image/*")
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

    private fun handlePickedImages(uris: List<Uri>) {
        if (uris.size == 1) {
            handleGalleryImage(uris.first())
        } else {
            val options = arrayOf(
                "⚡ Fetch as Multi-Page Document (${uris.size} pages)",
                "✂️ Review & Crop Pages in Batch"
            )
            AlertDialog.Builder(this)
                .setTitle("Selected ${uris.size} Images")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> importBatchImages(uris)
                        1 -> startBatchCrop(uris)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startBatchCrop(uris: List<Uri>) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Preparing Batch Crop")
            .setMessage("Loading ${uris.size} images...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val tempFiles = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val tempFile = FileManager.createTempImageFile(this@MainActivity)
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile.absolutePath
                    }
                }
                progress.dismiss()

                if (tempFiles.isNotEmpty()) {
                    val first = tempFiles.first()
                    val remaining = ArrayList(tempFiles.drop(1))
                    val intent = Intent(this@MainActivity, CropActivity::class.java).apply {
                        putExtra("IMAGE_PATH", first)
                        putExtra("IS_BATCH", true)
                        putStringArrayListExtra("BATCH_REMAINING", remaining)
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, "Failed to load images: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
        popup.menu.add(0, 2, 1, "📉 Compress to Target Size")
        popup.menu.add(0, 3, 2, "Assign Category")
        popup.menu.add(0, 4, 3, "Delete Document")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> exportPdf(doc)
                2 -> openCompressForDocument(doc)
                3 -> showCategoryDialog(doc)
                4 -> confirmDelete(doc)
            }
            true
        }
        popup.show()
    }

    private fun openCompressForDocument(doc: DocumentEntity) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Preparing PDF")
            .setMessage("Exporting document pages to PDF...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@MainActivity)
                val pages = db.pageDao().getPagesForDocument(doc.id)
                if (pages.isEmpty()) {
                    progress.dismiss()
                    Toast.makeText(this@MainActivity, "No pages to compress", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val imagePaths = pages.map { it.imagePath }
                val options = com.camscanner.pro.core.pdf.PdfOptions(
                    pageSize = com.camscanner.pro.core.pdf.PageSize.A4,
                    quality = com.camscanner.pro.core.pdf.PdfQuality.HIGH
                )
                withContext(Dispatchers.IO) {
                    PdfGenerator.generatePdf(this@MainActivity, imagePaths, doc.title, options)
                }
                progress.dismiss()

                val intent = Intent(this@MainActivity, MergeCompressActivity::class.java).apply {
                    putExtra(MergeCompressActivity.EXTRA_INITIAL_TAB, MergeCompressActivity.TAB_COMPRESS_PDF)
                }
                startActivity(intent)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, "Failed to prepare PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
        popup.menu.add(0, 1, 0, "🖼️ Select Multiple Images & Fetch")
        popup.menu.add(0, 2, 1, "📄 Import CamScanner PDF")
        popup.menu.add(0, 3, 2, "📁 Import CamScanner Folder")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> galleryLauncher.launch("image/*")
                2 -> pdfPickerLauncher.launch("application/pdf")
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

    private fun updateAccountBanner() {
        val user = GoogleAuthManager.getUserProfile(this)
        val lastBackupTime = CloudBackupManager.getLastBackupTime(this)

        if (user != null) {
            val displayName = user.displayName ?: user.email ?: "Google Account"
            binding.tvAccountName.text = "👤 $displayName"
            if (lastBackupTime > 0) {
                val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(lastBackupTime))
                binding.tvAccountBackupStatus.text = "✅ Backup Saved to Google ($timeStr)"
                binding.tvAccountBackupStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                binding.ivAccountStatusIcon.setImageResource(R.drawable.ic_check)
                binding.ivAccountStatusIcon.setColorFilter(android.graphics.Color.parseColor("#2E7D32"))
                binding.cardAccountBackupStatus.setCardBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
                binding.cardAccountBackupStatus.strokeColor = android.graphics.Color.parseColor("#81C784")
                binding.btnAccountAction.text = "Manage"
            } else {
                binding.tvAccountBackupStatus.text = "⚠️ Signed In • No Cloud Backup Yet (Tap to Backup)"
                binding.tvAccountBackupStatus.setTextColor(android.graphics.Color.parseColor("#E65100"))
                binding.ivAccountStatusIcon.setImageResource(R.drawable.ic_cloud)
                binding.ivAccountStatusIcon.setColorFilter(android.graphics.Color.parseColor("#E65100"))
                binding.cardAccountBackupStatus.setCardBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
                binding.cardAccountBackupStatus.strokeColor = android.graphics.Color.parseColor("#FFB74D")
                binding.btnAccountAction.text = "Backup Now"
            }
        } else {
            binding.tvAccountName.text = "☁️ Google Cloud Backup"
            binding.tvAccountBackupStatus.text = "Sign in with Gmail to secure & restore your scans"
            binding.tvAccountBackupStatus.setTextColor(android.graphics.Color.parseColor("#757575"))
            binding.ivAccountStatusIcon.setImageResource(R.drawable.ic_cloud)
            binding.ivAccountStatusIcon.setColorFilter(android.graphics.Color.parseColor("#00A86B"))
            binding.cardAccountBackupStatus.setCardBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
            binding.cardAccountBackupStatus.strokeColor = android.graphics.Color.parseColor("#E2E8F0")
            binding.btnAccountAction.text = "Sign In"
        }

        binding.cardAccountBackupStatus.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        binding.btnAccountAction.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
    }
}
