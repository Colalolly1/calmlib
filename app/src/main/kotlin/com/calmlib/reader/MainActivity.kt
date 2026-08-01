package com.calmlib.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.calmlib.reader.data.repository.SettingsRepository
import com.calmlib.reader.ui.library.LibraryScreen
import com.calmlib.reader.ui.library.LibraryViewModel
import com.calmlib.reader.ui.reader.ReaderActivity
import com.calmlib.reader.ui.theme.CalmLibTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: LibraryViewModel
    private lateinit var settings: SettingsRepository

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanForBooks()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStorageAccess()) viewModel.scanForBooks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[LibraryViewModel::class.java]
        settings = SettingsRepository(this)

        lifecycleScope.launch {
            val firstRun = settings.firstRunComplete.first().not()
            if (firstRun) {
                settings.setFirstRunComplete(true)
                requestStorageAndScan()
            }
        }
        viewModel.maybeRunWeeklyBackup()

        // Quiet rescan on every resume so books copied onto the phone show up
        // without a manual scan. This is safe now (unlike the old auto-rescan
        // that got removed): the scanner only reads dedicated book folders —
        // never Downloads/Documents — and dedups by path and content fingerprint,
        // so it can't re-pollute the library. It stays silent unless it finds
        // something new.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                if (hasStorageAccess()) viewModel.scanForBooksQuietly()
            }
        })

        setContent {
            CalmLibTheme {
                LibraryScreen(
                    viewModel = viewModel,
                    onBookClick = { book ->
                        val intent = Intent(this, ReaderActivity::class.java).apply {
                            putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                            putExtra(ReaderActivity.EXTRA_FILE_PATH, book.filePath)
                        }
                        startActivity(intent)
                    },
                    onRequestScan = { requestStorageAndScan() },
                )
            }
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAndScan() {
        if (hasStorageAccess()) {
            viewModel.scanForBooks()
            return
        }
        // On the legacy READ_EXTERNAL_STORAGE path we ALSO trigger a scan attempt
        // because some devices (e.g. AOSP without Google) silently grant scoped
        // access via the app-local /sdcard/Android/data path even when the system
        // permission check looks denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            val pm = packageManager
            val resolvable = intent.resolveActivity(pm) != null
            if (resolvable) {
                try {
                    manageStorageLauncher.launch(intent)
                    return
                } catch (_: Exception) { /* fall through */ }
            }
            val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (global.resolveActivity(pm) != null) {
                try {
                    manageStorageLauncher.launch(global)
                    return
                } catch (_: Exception) { /* fall through */ }
            }
            // Fallback: device has no "all files" settings screen — request the legacy
            // permission instead. Scan will work for accessible directories.
            legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            viewModel.scanForBooks()
        } else {
            legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
