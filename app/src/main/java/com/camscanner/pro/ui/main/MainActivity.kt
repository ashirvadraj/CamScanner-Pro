package com.camscanner.pro.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import com.camscanner.pro.core.pdf.PdfGenerator
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.databinding.ActivityMainBinding
import com.camscanner.pro.ui.camera.CameraActivity
import com.camscanner.pro.ui.crop.CropActivity
import com.camscanner.pro.ui.detail.DocumentDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { CamScannerApp.instance.repository }
    private lateinit var adapter: DocumentAdapter

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeDocuments()
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

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }

        binding.etSearch.addTextChangedListener { text ->
            val query = text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
                if (query.isEmpty()) {
                    repository.getAllDocuments().collectLatest { updateList(it) }
                } else {
                    repository.searchDocuments(query).collectLatest { updateList(it) }
                }
            }
        }
    }

    private fun observeDocuments() {
        lifecycleScope.launch {
            repository.getAllDocuments().collectLatest { docs ->
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
        popup.menu.add(0, 2, 1, "Delete Document")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> exportPdf(doc)
                2 -> confirmDelete(doc)
            }
            true
        }
        popup.show()
    }

    private fun exportPdf(doc: DocumentEntity) {
        lifecycleScope.launch {
            try {
                val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@MainActivity)
                val pages = db.pageDao().getPagesForDocument(doc.id)
                if (pages.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No pages to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val imagePaths = pages.map { it.imagePath }
                val pdfFile = PdfGenerator.generatePdf(this@MainActivity, imagePaths, doc.title)
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
