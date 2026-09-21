package com.camscanner.pro.ui.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscanner.pro.R
import com.camscanner.pro.core.compression.SizeTargetCompressor
import com.camscanner.pro.core.pdf.PdfMergerEngine
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityMergeCompressBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MergeCompressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMergeCompressBinding

    // PDF Merge State
    private val selectedPdfUris = mutableListOf<Uri>()
    private var lastMergedResult: PdfMergerEngine.MergeResult? = null

    // Image Compress State
    private var selectedImageUri: Uri? = null
    private var lastCompressResult: SizeTargetCompressor.CompressionResult? = null

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedPdfUris.clear()
            selectedPdfUris.addAll(uris)
            updatePdfSelectionUI()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            updateImageSelectionUI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMergeCompressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupPdfMergeListeners()
        setupImageCompressListeners()
    }

    private fun setupTabs() {
        binding.btnBack.setOnClickListener { finish() }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.scrollMergePdf.visibility = View.VISIBLE
                        binding.scrollCompressImage.visibility = View.GONE
                    }
                    1 -> {
                        binding.scrollMergePdf.visibility = View.GONE
                        binding.scrollCompressImage.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ==========================================
    // SECTION 1: MERGE PDFS TO TARGET SIZE
    // ==========================================
    private fun setupPdfMergeListeners() {
        binding.btnSelectPdfs.setOnClickListener {
            pdfPickerLauncher.launch("application/pdf")
        }

        binding.chipGroupPdfSize.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipPdfNoLimit -> binding.etPdfTargetKb.setText("")
                R.id.chipPdf500Kb -> binding.etPdfTargetKb.setText("500")
                R.id.chipPdf1Mb -> binding.etPdfTargetKb.setText("1024")
                R.id.chipPdf2Mb -> binding.etPdfTargetKb.setText("2048")
            }
        }

        binding.btnExecuteMerge.setOnClickListener {
            if (selectedPdfUris.size < 2) {
                Toast.makeText(this, "Please select at least 2 PDF files to merge", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetKbText = binding.etPdfTargetKb.text?.toString()?.trim()
            val targetKb = if (!targetKbText.isNullOrBlank()) targetKbText.toIntOrNull() else null
            val title = binding.etMergedTitle.text?.toString()?.trim()?.ifBlank { "Merged_Document" } ?: "Merged_Document"

            executePdfMerge(targetKb, title)
        }

        binding.btnOpenMergedPdf.setOnClickListener {
            val result = lastMergedResult ?: return@setOnClickListener
            val uri = FileManager.getUriForFile(this, result.outputFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No PDF viewer app found", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShareMergedPdf.setOnClickListener {
            val result = lastMergedResult ?: return@setOnClickListener
            val uri = FileManager.getUriForFile(this, result.outputFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, result.outputFile.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Merged PDF"))
        }
    }

    private fun updatePdfSelectionUI() {
        val count = selectedPdfUris.size
        binding.tvSelectedPdfsSummary.text = "$count PDF document(s) selected and ready to merge."
        binding.btnSelectPdfs.text = "Change Selected PDFs ($count)"
    }

    private fun executePdfMerge(targetKb: Int?, title: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Merging & Optimizing PDF")
            .setMessage("Extracting pages and applying target size optimization...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PdfMergerEngine.mergePdfs(
                    context = this@MergeCompressActivity,
                    pdfUris = selectedPdfUris,
                    targetSizeKb = targetKb,
                    outputTitle = title
                )
            }

            progress.dismiss()

            result.onSuccess { mergeRes ->
                lastMergedResult = mergeRes
                binding.cardMergeResult.visibility = View.VISIBLE

                val sizeStr = if (mergeRes.fileSizeKb > 1024) {
                    String.format("%.1f MB", mergeRes.fileSizeKb / 1024.0)
                } else {
                    "${mergeRes.fileSizeKb} KB"
                }

                val targetStr = if (targetKb != null && targetKb > 0) " (Target: $targetKb KB)" else ""
                binding.tvMergeResultStats.text = "Final Size: $sizeStr$targetStr • ${mergeRes.totalPages} Pages across ${mergeRes.sourcePdfCount} PDFs"
                Toast.makeText(this@MergeCompressActivity, "PDFs merged successfully!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@MergeCompressActivity, "Merge failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==========================================
    // SECTION 2: COMPRESS IMAGE TO EXACT TARGET SIZE
    // ==========================================
    private fun setupImageCompressListeners() {
        binding.btnSelectImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.chipGroupImgSize.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipImg50Kb -> binding.etImageTargetKb.setText("50")
                R.id.chipImg100Kb -> binding.etImageTargetKb.setText("100")
                R.id.chipImg200Kb -> binding.etImageTargetKb.setText("200")
                R.id.chipImg500Kb -> binding.etImageTargetKb.setText("500")
            }
        }

        binding.btnExecuteImageCompress.setOnClickListener {
            val uri = selectedImageUri
            if (uri == null) {
                Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetKbStr = binding.etImageTargetKb.text?.toString()?.trim()
            val targetKb = targetKbStr?.toIntOrNull() ?: 50
            if (targetKb <= 0) {
                Toast.makeText(this, "Please specify a positive target size in KB", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            executeImageCompress(uri, targetKb)
        }

        binding.btnViewCompressedImage.setOnClickListener {
            val result = lastCompressResult ?: return@setOnClickListener
            val uri = FileManager.getUriForFile(this, result.outputFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }

        binding.btnShareCompressedImage.setOnClickListener {
            val result = lastCompressResult ?: return@setOnClickListener
            val uri = FileManager.getUriForFile(this, result.outputFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Compressed Image")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Compressed Image"))
        }
    }

    private fun updateImageSelectionUI(uri: Uri) {
        binding.layoutImagePreviewContainer.visibility = View.VISIBLE
        binding.ivPickedImagePreview.setImageURI(uri)

        var sizeKb = 0L
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            sizeKb = pfd.statSize / 1024
        }

        val sizeStr = if (sizeKb > 1024) {
            String.format("%.2f MB", sizeKb / 1024.0)
        } else {
            "$sizeKb KB"
        }

        binding.tvPickedImageName.text = "Selected Image"
        binding.tvPickedImageSize.text = "Original Size: $sizeStr"
        binding.btnSelectImage.text = "Change Image"
    }

    private fun executeImageCompress(uri: Uri, targetKb: Int) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Compressing Image")
            .setMessage("Optimizing image to fit inside $targetKb KB with maximum clarity...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = SizeTargetCompressor.compressImageFileToTargetSize(
                context = this@MergeCompressActivity,
                imageUri = uri,
                targetKb = targetKb
            )

            progress.dismiss()

            result.onSuccess { compRes ->
                lastCompressResult = compRes
                binding.cardImageCompressResult.visibility = View.VISIBLE

                val origKb = compRes.originalBytes / 1024
                val compKb = compRes.compressedBytes / 1024

                binding.tvCompressBeforeAfter.text = "Original: $origKb KB  ➜  Compressed: $compKb KB (-${compRes.savingsPercent}%)"
                binding.tvCompressQualityDetail.text = "Optimal Quality: ${compRes.qualityUsed}% • Dimensions: ${compRes.width}×${compRes.height} px"

                Toast.makeText(this@MergeCompressActivity, "Compressed to $compKb KB!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@MergeCompressActivity, "Compression failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
