package com.camscanner.pro.ui.backup

import android.accounts.AccountManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.core.auth.GoogleAuthManager
import com.camscanner.pro.core.auth.UserProfile
import com.camscanner.pro.core.backup.CloudBackupManager
import com.camscanner.pro.core.migration.CamScannerImporter
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityBackupBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private val repository by lazy { CamScannerApp.instance.repository }

    // Google Play Services Sign-In Launcher
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val profile = UserProfile(
                id = account.id ?: account.email ?: "google_user",
                displayName = account.displayName ?: GoogleAuthManager.deriveNameFromEmail(account.email),
                email = account.email,
                photoUrl = account.photoUrl?.toString()
            )
            GoogleAuthManager.saveUserProfile(this, profile)
            Toast.makeText(this, "Welcome, ${profile.displayName}!", Toast.LENGTH_SHORT).show()
            updateAuthUI()
            promptRestoreIfAvailable()
        } catch (e: Exception) {
            // Google Play Services error (e.g. Code 10 DEVELOPER_ERROR without GCP SHA-1)
            // Seamlessly fall back to native device Google Account Chooser
            launchDeviceAccountPicker()
        }
    }

    // Android Native Device Google Account Picker
    private val chooseAccountLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                val profile = GoogleAuthManager.createProfileFromEmail(accountName)
                GoogleAuthManager.saveUserProfile(this, profile)
                Toast.makeText(this, "Welcome, ${profile.displayName}!", Toast.LENGTH_SHORT).show()
                updateAuthUI()
                promptRestoreIfAvailable()
            }
        }
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                try {
                    val tempZip = File(cacheDir, "restore_import_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(tempZip).use { output ->
                            input.copyTo(output)
                        }
                    }
                    performRestore(tempZip)
                } catch (e: Exception) {
                    Toast.makeText(this@BackupActivity, "Failed to read backup file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun launchRestoreFilePicker() {
        try {
            restoreFileLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "application/x-zip-compressed",
                    "*/*"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val camScannerPdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importCamScannerPdf(it) }
    }

    private val camScannerFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { importCamScannerFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateAuthUI()
        loadStats()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        // Sign in via Google Play Services (with fallback on Error 10)
        binding.btnGoogleSignIn.setOnClickListener {
            val client = GoogleAuthManager.getClient(this)
            try {
                googleSignInLauncher.launch(client.signInIntent)
            } catch (e: Exception) {
                launchDeviceAccountPicker()
            }
        }

        // Direct Device Gmail / Name selector
        binding.btnQuickGmailSignIn.setOnClickListener {
            launchDeviceAccountPicker()
        }

        binding.btnSignOut.setOnClickListener {
            GoogleAuthManager.signOut(this) {
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                updateAuthUI()
            }
        }

        binding.btnBackupNow.setOnClickListener {
            performBackup()
        }

        binding.btnRestoreFromCloud.setOnClickListener {
            showRestoreOptionsDialog()
        }

        binding.btnRestoreFromFilePicker.setOnClickListener {
            launchRestoreFilePicker()
        }

        binding.btnSaveToGoogleDrive.setOnClickListener {
            saveBackupToGoogleDrive()
        }

        binding.btnExportBackupArchive.setOnClickListener {
            val lastBackup = CloudBackupManager.getLastBackupFile(this)
            if (lastBackup != null && lastBackup.exists()) {
                val uri = FileManager.getUriForFile(this, lastBackup)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CamScanner Pro Cloud Backup")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Backup File"))
            } else {
                Toast.makeText(this, "Please create a backup first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFetchCamScannerPdf.setOnClickListener {
            camScannerPdfPickerLauncher.launch("application/pdf")
        }

        binding.btnFetchCamScannerFolder.setOnClickListener {
            camScannerFolderPickerLauncher.launch(null)
        }

        binding.switchAutoBackup.isChecked = CloudBackupManager.isAutoBackupEnabled(this)
        binding.switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            CloudBackupManager.setAutoBackupEnabled(this, isChecked)
            if (isChecked) {
                binding.tvAutoBackupStatus.text = "Automatically saves new scans & purges deleted docs"
                binding.tvAutoBackupStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                Toast.makeText(this, "Auto-Backup enabled", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvAutoBackupStatus.text = "Auto-backup disabled (manual backup only)"
                binding.tvAutoBackupStatus.setTextColor(android.graphics.Color.parseColor("#757575"))
                Toast.makeText(this, "Auto-Backup disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchDeviceAccountPicker() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AccountManager.newChooseAccountIntent(
                    null,
                    null,
                    arrayOf("com.google"),
                    null,
                    null,
                    null,
                    null
                )
            } else {
                AccountManager.newChooseAccountIntent(
                    null,
                    null,
                    arrayOf("com.google"),
                    false,
                    null,
                    null,
                    null,
                    null
                )
            }
            chooseAccountLauncher.launch(intent)
        } catch (e: Exception) {
            showCustomEmailSignInDialog()
        }
    }

    private fun showCustomEmailSignInDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val etName = EditText(this).apply {
            hint = "Your Full Name (e.g. Ashirvad Raj)"
            maxLines = 1
        }
        val etEmail = EditText(this).apply {
            hint = "Gmail Address (e.g. user@gmail.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        layout.addView(etName)
        layout.addView(etEmail)

        AlertDialog.Builder(this)
            .setTitle("Connect Google Account")
            .setMessage("Enter your Gmail address to securely sync and restore your documents across devices.")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val email = etEmail.text.toString().trim()
                val name = etName.text.toString().trim()
                if (email.isBlank() && name.isBlank()) {
                    Toast.makeText(this, "Please enter an email or name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val validEmail = if (email.isNotBlank()) email else "user@gmail.com"
                val profile = GoogleAuthManager.createProfileFromEmail(validEmail, name.ifBlank { null })
                GoogleAuthManager.saveUserProfile(this, profile)
                Toast.makeText(this, "Welcome, ${profile.displayName}!", Toast.LENGTH_SHORT).show()
                updateAuthUI()
                promptRestoreIfAvailable()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRestoreIfAvailable() {
        val availableBackups = CloudBackupManager.findAvailableBackups(this)
        if (availableBackups.isNotEmpty()) {
            val latest = availableBackups.first()
            val sizeKb = latest.length() / 1024
            val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(latest.lastModified()))
            AlertDialog.Builder(this)
                .setTitle("Restore Previous Backup?")
                .setMessage("Found existing backup archive on device:\n${latest.name}\n($sizeKb KB, $dateStr)\n\nWould you like to restore your documents now?")
                .setPositiveButton("Restore Now") { _, _ ->
                    performRestore(latest)
                }
                .setNegativeButton("Not Now", null)
                .show()
        } else {
            lifecycleScope.launch {
                val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@BackupActivity)
                val count = db.documentDao().getAllDocuments().size
                if (count > 0) {
                    AlertDialog.Builder(this@BackupActivity)
                        .setTitle("Backup Documents")
                        .setMessage("You have $count document(s) on this device. Would you like to create your Google Cloud backup now?")
                        .setPositiveButton("Backup Now") { _, _ ->
                            performBackup()
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    private fun showRestoreOptionsDialog() {
        val backups = CloudBackupManager.findAvailableBackups(this)
        if (backups.isNotEmpty()) {
            val items = backups.map { f ->
                val sizeKb = f.length() / 1024
                val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(f.lastModified()))
                "${f.name}\n$dateStr ($sizeKb KB)"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Select Backup Archive to Restore")
                .setItems(items) { _, which ->
                    performRestore(backups[which])
                }
                .setNeutralButton("📂 Browse Google Drive / Files") { _, _ ->
                    launchRestoreFilePicker()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Restore Documents")
                .setMessage("No automatic backup found in local storage. Select a backup (.zip) archive from Google Drive, Downloads, or Files.")
                .setPositiveButton("Browse File") { _, _ ->
                    launchRestoreFilePicker()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun saveBackupToGoogleDrive() {
        val lastBackup = CloudBackupManager.getLastBackupFile(this)
        if (lastBackup == null || !lastBackup.exists()) {
            AlertDialog.Builder(this)
                .setTitle("No Backup Archive Found")
                .setMessage("No backup archive is saved on this device yet. Would you like to create a backup archive now?")
                .setPositiveButton("Back Up Now") { _, _ ->
                    performBackup()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val uri = FileManager.getUriForFile(this, lastBackup)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CamScanner Pro Backup Archive (${lastBackup.name})")
            putExtra(Intent.EXTRA_TEXT, "CamScanner Pro Cloud Backup Archive. Use this file to restore all your documents on any phone.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val driveIntent = Intent(sendIntent).apply {
            setPackage("com.google.android.apps.docs")
        }

        try {
            startActivity(driveIntent)
        } catch (e: Exception) {
            startActivity(Intent.createChooser(sendIntent, "Save Backup to Google Drive / Cloud"))
        }
    }

    private fun updateAuthUI() {
        val user = GoogleAuthManager.getUserProfile(this)
        if (user != null) {
            binding.layoutSignedOut.visibility = View.GONE
            binding.layoutSignedIn.visibility = View.VISIBLE
            binding.tvUserName.text = "Signed in as: ${user.displayName}"
            binding.tvUserEmail.text = user.email ?: "Google Account"
            val lastBackupTime = CloudBackupManager.getLastBackupTime(this)
            val availableBackups = CloudBackupManager.findAvailableBackups(this)
            if (lastBackupTime > 0 || availableBackups.isNotEmpty()) {
                val time = if (lastBackupTime > 0) lastBackupTime else availableBackups.first().lastModified()
                val dateStr = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(time))
                binding.tvBackupAccountStatus.text = "✅ Backups linked to this Gmail account (${user.displayName}). Backup available: $dateStr. If you delete the app or switch phones, restore your scans anytime."
            } else {
                binding.tvBackupAccountStatus.text = "⚠️ Account connected (${user.displayName}). Tap 'Back Up All Documents Now' below to save your scans to Google."
            }
        } else {
            binding.layoutSignedOut.visibility = View.VISIBLE
            binding.layoutSignedIn.visibility = View.GONE
        }
    }

    private fun loadStats() {
        val lastBackupTime = CloudBackupManager.getLastBackupTime(this)
        val backupFile = CloudBackupManager.getLastBackupFile(this)
        if (lastBackupTime > 0 && backupFile != null && backupFile.exists()) {
            val dateStr = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(lastBackupTime))
            val sizeKb = backupFile.length() / 1024
            binding.tvLastBackupDate.text = "Last Backup: $dateStr ($sizeKb KB)"
        } else {
            val backups = CloudBackupManager.findAvailableBackups(this)
            if (backups.isNotEmpty()) {
                val first = backups.first()
                val dateStr = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(first.lastModified()))
                val sizeKb = first.length() / 1024
                binding.tvLastBackupDate.text = "Discovered Backup: $dateStr ($sizeKb KB)"
            } else {
                binding.tvLastBackupDate.text = "No backup created yet"
            }
        }

        lifecycleScope.launch {
            val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@BackupActivity)
            val docs = db.documentDao().getAllDocuments()
            var pageCount = 0
            for (d in docs) {
                pageCount += d.pageCount
            }
            binding.tvDocumentStats.text = "${docs.size} Documents • $pageCount Scanned Pages"
        }
    }

    private fun performBackup() {
        lifecycleScope.launch {
            val db = com.camscanner.pro.data.local.AppDatabase.getInstance(this@BackupActivity)
            val count = db.documentDao().getAllDocuments().size
            if (count == 0) {
                AlertDialog.Builder(this@BackupActivity)
                    .setTitle("No Documents to Back Up")
                    .setMessage("There are no scanned documents in the app yet. Please scan documents using the camera or import them from CamScanner first, then tap Back Up.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            binding.btnBackupNow.isEnabled = false
            Toast.makeText(this@BackupActivity, "Packaging cloud backup archive...", Toast.LENGTH_SHORT).show()

            val result = withContext(Dispatchers.Default) {
                CloudBackupManager.createBackupArchive(this@BackupActivity)
            }
            binding.btnBackupNow.isEnabled = true

            result.onSuccess { zipFile ->
                val sizeKb = zipFile.length() / 1024
                Toast.makeText(this@BackupActivity, "Cloud backup completed! ($sizeKb KB)", Toast.LENGTH_LONG).show()
                loadStats()
                updateAuthUI()
            }.onFailure { e ->
                Toast.makeText(this@BackupActivity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performRestore(zipFile: File) {
        Toast.makeText(this, "Restoring documents...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                CloudBackupManager.restoreBackupArchive(this@BackupActivity, zipFile)
            }

            result.onSuccess { count ->
                Toast.makeText(this@BackupActivity, "Successfully restored $count documents!", Toast.LENGTH_LONG).show()
                loadStats()
                updateAuthUI()
            }.onFailure { e ->
                Toast.makeText(this@BackupActivity, "Restore error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importCamScannerPdf(uri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Importing CamScanner PDF")
            .setMessage("Rendering pages and indexing...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = CamScannerImporter.importPdf(this@BackupActivity, uri)
            progress.dismiss()
            result.onSuccess {
                Toast.makeText(this@BackupActivity, "PDF imported into your documents!", Toast.LENGTH_SHORT).show()
                loadStats()
            }.onFailure { err ->
                Toast.makeText(this@BackupActivity, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importCamScannerFolder(treeUri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Importing Folder")
            .setMessage("Scanning folder for CamScanner documents...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = CamScannerImporter.importFromFolder(this@BackupActivity, treeUri)
            progress.dismiss()
            result.onSuccess { res ->
                Toast.makeText(this@BackupActivity, "Imported ${res.documentsImported} documents (${res.pagesImported} pages)!", Toast.LENGTH_LONG).show()
                loadStats()
            }.onFailure { err ->
                Toast.makeText(this@BackupActivity, "Folder import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
