package com.nxdeveloper.unapk.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nxdeveloper.unapk.MainActivity
import com.nxdeveloper.unapk.R
import com.nxdeveloper.unapk.UnApkApplication
import com.nxdeveloper.unapk.core.DecompileOptions
import com.nxdeveloper.unapk.core.DecompileResult
import com.nxdeveloper.unapk.core.ProgressUpdate
import com.nxdeveloper.unapk.core.Stage
import com.nxdeveloper.unapk.core.UnApkEngine
import com.nxdeveloper.unapk.prefs.AppPreferences
import com.nxdeveloper.unapk.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DecompileService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    private val progressState = MutableStateFlow(
        ProgressUpdate(Stage.PREPARING, 0, "Idle")
    )
    val progress: StateFlow<ProgressUpdate> = progressState.asStateFlow()

    private val resultState = MutableStateFlow<DecompileResult?>(null)
    val lastResult: StateFlow<DecompileResult?> = resultState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): DecompileService = this@DecompileService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uriString = intent?.getStringExtra(EXTRA_URI)
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: "selected.apk"
        if (uriString.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startInForegroundIfNeeded(displayName, ProgressUpdate(Stage.PREPARING, 0, "Starting"))
        runJob(Uri.parse(uriString), displayName)
        return START_NOT_STICKY
    }

    private fun runJob(apkUri: Uri, displayName: String) {
        val previous = runningJob
        if (previous != null && previous.isActive) {
            return
        }
        runningJob = scope.launch {
            try {
                val workspace = File(cacheDir, "workspace")
                val outputBase = File(filesDir, "outputs")
                outputBase.mkdirs()
                val baseName = sanitizeFileName(displayName.removeSuffix(".apk"))
                val outputDir = File(outputBase, baseName)

                val cachedApk = File(cacheDir, "input.apk")
                if (cachedApk.exists()) {
                    cachedApk.delete()
                }
                FileUtils.copyUriToFile(contentResolver, apkUri, cachedApk)

                val preferences = AppPreferences(this@DecompileService)
                val options = DecompileOptions(
                    sourceApk = cachedApk,
                    outputDirectory = outputDir,
                    workspaceDirectory = workspace,
                    produceJavaSources = preferences.produceJavaSources,
                    produceSmaliSources = preferences.produceSmaliSources,
                    decodeResources = preferences.decodeResources,
                    packAsZip = preferences.packAsZip,
                    keepIntermediateFiles = preferences.keepIntermediateFiles
                )

                val engine = UnApkEngine()
                val result = engine.run(options) { update ->
                    progressState.value = update
                    updateForegroundNotification(displayName, update)
                }
                resultState.value = result
                postFinalNotification(displayName, result)
            } catch (cancellation: Throwable) {
                resultState.value = DecompileResult.Failure(
                    stage = Stage.FAILED,
                    reason = cancellation.message ?: "Service cancelled",
                    cause = cancellation
                )
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun startInForegroundIfNeeded(displayName: String, update: ProgressUpdate) {
        val notification = buildProgressNotification(displayName, update)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeOrZero())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypeOrZero(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }

    private fun updateForegroundNotification(displayName: String, update: ProgressUpdate) {
        val notification = buildProgressNotification(displayName, update)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun postFinalNotification(displayName: String, result: DecompileResult) {
        val title: String
        val text: String
        when (result) {
            is DecompileResult.Success -> {
                title = getString(R.string.notification_done_title, displayName)
                text = result.zipArchive?.let { archive ->
                    getString(
                        R.string.notification_done_text,
                        FileUtils.humanReadableSize(archive.length())
                    )
                } ?: getString(R.string.notification_done_text_no_zip)
            }
            is DecompileResult.Failure -> {
                title = getString(R.string.notification_failed_title)
                text = result.reason
            }
        }
        val notification = NotificationCompat.Builder(this, UnApkApplication.CHANNEL_DECOMPILE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openMainPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun buildProgressNotification(displayName: String, update: ProgressUpdate): Notification {
        val title = getString(R.string.notification_progress_title, displayName)
        return NotificationCompat.Builder(this, UnApkApplication.CHANNEL_DECOMPILE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(update.message)
            .setProgress(100, update.percent, update.stage == Stage.PREPARING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openMainPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun openMainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "output" }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_URI = "extra_apk_uri"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"

        fun startService(context: Context, uri: Uri, displayName: String) {
            val intent = Intent(context, DecompileService::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
