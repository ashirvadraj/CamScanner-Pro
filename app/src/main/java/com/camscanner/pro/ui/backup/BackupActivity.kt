package com.camscanner.pro.ui.backup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscanner.pro.CamScannerApp
import com.camscanner.pro.core.auth.GoogleAuthManager
import com.camscanner.pro.core.backup.CloudBackupManager
import com.camscanner.pro.core.storage.FileManager
import com.camscanner.pro.databinding.ActivityBackupBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private val repository by lazy { CamScannerApp.instance.repository }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            Toast.makeText(this, "Welcome, ${account.displayName}!", Toast.LENGTH_SHORT).show()
            updateAuthUI()
        } catch (e: Exception) {
            // Simulated login for local/offline testing or error fallback
            Toast.makeText(this, "Google Sign-In: ${e.message}", Toast.LENGTH_LONG).show()
            updateAuthUI()
        }
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                try {
                    val tempZip = File(cacheDir, "restore_import_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(it)?.use { input ->
                        java.io.FileOutputStream(tempZip).use { output ->
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

        binding.btnGoogleSignIn.setOnClickListener {
            val client = GoogleAuthManager.getClient(this)
            googleSignInLauncher.launch(client.signInIntent)
        }

        binding.btnSignOut.setOnClickListener {
            GoogleAuthManager.getClient(this).signOut().addOnCompleteListener {
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                updateAuthUI()
            }
        }

        binding.btnBackupNow.setOnClickListener {
            performBackup()
        }

        binding.btnRestoreFromCloud.setOnClickListener {
            val lastBackup = CloudBackupManager.getLastBackupFile(this)
            if (lastBackup != null && lastBackup.exists()) {
                AlertDialog.Builder(this)
                    .setTitle("Restore Documents")
                    .setMessage("Restore from latest cloud backup (${lastBackup.name})?")
                    .setPositiveButton("Restore") { _, _ ->
                        performRestore(lastBackup)
                    }
                    .setNeutralButton("Choose File") { _, _ ->
                        restoreFileLauncher.launch("application/zip")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                restoreFileLauncher.launch("application/zip")
            }
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
    }

    private fun updateAuthUI() {
        val user = GoogleAuthManager.getUserProfile(this)
        if (user != null) {
            binding.layoutSignedOut.visibility = View.GONE
            binding.layoutSignedIn.visibility = View.VISIBLE
            binding.tvUserName.text = "Signed in as: ${user.displayName}"
            binding.tvUserEmail.text = user.email ?: "Google Account"
            val lastBackupTime = CloudBackupManager.getLastBackupTime(this)
            if (lastBackupTime > 0) {
                val dateStr = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(lastBackupTime))
                binding.tvBackupAccountStatus.text = "✅ Backups linked to this Gmail account (${user.displayName}). Last synced: $dateStr. If you delete the app or switch phones, sign in to restore your scans."
            } else {
                binding.tvBackupAccountStatus.text = "⚠️ Account connected (${user.displayName}). Tap 'Create Cloud Backup Now' below to save your scans to Google."
            }
        } else {
            binding.layoutSignedOut.visibility = View.VISIBLE
            binding.layoutSignedIn.visibility = View.GONE
        }
    }

    private fun loadStats() {
        val lastBackupTime = CloudBackupManager.getLastBackupTime(this)
        if (lastBackupTime > 0) {
            val dateStr = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(lastBackupTime))
            val backupFile = CloudBackupManager.getLastBackupFile(this)
            val sizeKb = if (backupFile != null) backupFile.length() / 1024 else 0
            binding.tvLastBackupDate.text = "Last Backup: $dateStr ($sizeKb KB)"
        } else {
            binding.tvLastBackupDate.text = "No backup created yet"
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
        binding.btnBackupNow.isEnabled = false
        Toast.makeText(this, "Packaging cloud backup archive...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                CloudBackupManager.createBackupArchive(this@BackupActivity)
            }
            binding.btnBackupNow.isEnabled = true

            result.onSuccess { zipFile ->
                val sizeKb = zipFile.length() / 1024
                Toast.makeText(this@BackupActivity, "Cloud backup completed! ($sizeKb KB)", Toast.LENGTH_LONG).show()
                loadStats()
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
            }.onFailure { e ->
                Toast.makeText(this@BackupActivity, "Restore error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
