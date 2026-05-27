package com.nxdeveloper.unapk

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nxdeveloper.unapk.core.DecompileResult
import com.nxdeveloper.unapk.core.ProgressUpdate
import com.nxdeveloper.unapk.core.Stage
import com.nxdeveloper.unapk.databinding.ActivityMainBinding
import com.nxdeveloper.unapk.prefs.AppPreferences
import com.nxdeveloper.unapk.service.DecompileService
import com.nxdeveloper.unapk.util.FileUtils
import com.nxdeveloper.unapk.BuildConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences

    private var boundService: DecompileService? = null
    private var serviceBound = false

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            handlePickedApk(uri)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? DecompileService.LocalBinder ?: return
            boundService = localBinder.getService()
            serviceBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.subtitle = getString(
            R.string.toolbar_version_subtitle,
            BuildConfig.VERSION_NAME,
            BuildConfig.ENGINE_TAG
        )
        preferences = AppPreferences(this)

        binding.selectApkButton.setOnClickListener {
            requestNotificationPermissionIfNeeded()
            pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }

        binding.openOutputButton.setOnClickListener {
            shareLastZipOutput()
        }

        binding.openOutputButton.isEnabled = false
        binding.progressIndicator.progress = 0
        binding.statusText.text = getString(R.string.status_idle)
        binding.warningsBlock.isVisible = false
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DecompileService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handlePickedApk(uri: Uri) {
        val displayName = FileUtils.queryDisplayName(contentResolver, uri) ?: "selected.apk"
        binding.selectedApkText.text = getString(R.string.selected_apk, displayName)
        binding.statusText.text = getString(R.string.status_starting)
        binding.progressIndicator.progress = 0
        binding.warningsBlock.isVisible = false
        binding.openOutputButton.isEnabled = false

        if (!preferences.backgroundExecutionEnabled) {
            binding.statusText.text = getString(R.string.warning_background_disabled)
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: SecurityException) {
        }
        DecompileService.startService(this, uri, displayName)
    }

    private fun observeService() {
        val service = boundService ?: return
        lifecycleScope.launch {
            service.progress.collectLatest { update ->
                renderProgress(update)
            }
        }
        lifecycleScope.launch {
            service.lastResult.collectLatest { result ->
                renderResult(result)
            }
        }
    }

    private fun renderProgress(update: ProgressUpdate) {
        binding.progressIndicator.progress = update.percent
        binding.statusText.text = stageLabel(update.stage, update.message, update.percent)
        if (update.stage == Stage.DONE || update.stage == Stage.FAILED) {
            binding.progressIndicator.progress = 100
        }
    }

    private fun renderResult(result: DecompileResult?) {
        if (result == null) {
            return
        }
        when (result) {
            is DecompileResult.Success -> {
                binding.statusText.text = getString(R.string.status_done)
                binding.openOutputButton.isEnabled = result.zipArchive != null
                if (result.warnings.isNotEmpty()) {
                    binding.warningsBlock.isVisible = true
                    binding.warningsText.text = result.warnings.joinToString("\n• ", "• ")
                }
            }
            is DecompileResult.Failure -> {
                binding.statusText.text = getString(R.string.status_failed, result.reason)
                binding.openOutputButton.isEnabled = false
            }
        }
    }

    private fun shareLastZipOutput() {
        val result = boundService?.lastResult?.value as? DecompileResult.Success ?: return
        val zip = result.zipArchive ?: return
        val uri = exportableUri(zip)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_result)))
    }

    private fun exportableUri(file: File): Uri {
        val target = if (file.parentFile?.absolutePath?.startsWith(filesDir.absolutePath) == true) {
            file
        } else {
            val staged = File(File(filesDir, "outputs"), file.name)
            file.copyTo(staged, overwrite = true)
            staged
        }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
    }

    private fun stageLabel(stage: Stage, fallback: String, percent: Int): String {
        val resId = when (stage) {
            Stage.PREPARING -> R.string.stage_preparing
            Stage.EXTRACTING -> R.string.stage_extracting
            Stage.DECODING_RESOURCES -> R.string.stage_decoding_resources
            Stage.DECODING_MANIFEST -> R.string.stage_decoding_manifest
            Stage.DECOMPILING_JAVA -> R.string.stage_decompiling_java
            Stage.DISASSEMBLING_SMALI -> R.string.stage_disassembling_smali
            Stage.ANALYZING_NATIVE -> R.string.stage_analyzing_native
            Stage.PACKAGING -> R.string.stage_packaging
            Stage.DONE -> R.string.stage_done
            Stage.FAILED -> R.string.stage_failed
        }
        return getString(resId, percent).ifBlank { fallback }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
