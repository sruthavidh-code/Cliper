package com.example.media

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.AppContainer
import com.example.model.CaptionStylePreset
import com.example.ui.viewmodel.ExportUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "VideoExportFgService"

/**
 * Dedicated Android Foreground Service executing Media3 video exports (and Demo Mode export
 * previews) with a persistent progress notification supporting direct Pause, Resume, and Cancel
 * controls from the system notification shade.
 */
class VideoExportForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var appContainer: AppContainer
    private lateinit var coordinator: VideoExportCoordinator
    private lateinit var notificationManager: ExportNotificationManager

    private var exportJob: Job? = null
    private var elapsedTickerJob: Job? = null
    private var activeRequest: ExportJobRequest? = null

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer.getInstance(applicationContext)
        coordinator = appContainer.exportCoordinator
        notificationManager = appContainer.exportNotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStartExport()
            ACTION_PAUSE -> handlePauseExport()
            ACTION_RESUME -> handleResumeExport()
            ACTION_CANCEL -> handleCancelExport()
            else -> {
                if (exportJob?.isActive != true) {
                    stopForegroundAndSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached (fgsType=$fgsType); stopping active export cleanly.")
        val req = activeRequest
        elapsedTickerJob?.cancel()
        exportJob?.cancel()
        appContainer.media3Engine.cancelActiveOperation()
        if (req != null) {
            val timeoutMsg = "Export paused by Android background processing limit. Tap retry to continue."
            coordinator.markError(req.clip, req.resolution, timeoutMsg)
            notificationManager.showError(req.clip.title, timeoutMsg)
        } else {
            coordinator.markCancelled()
        }
        activeRequest = null
        stopForegroundAndSelf(startId)
    }

    override fun onDestroy() {
        elapsedTickerJob?.cancel()
        exportJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStartExport() {
        val request = coordinator.consumePendingRequest()
        if (request == null) {
            // Must still satisfy startForeground if started via startForegroundService
            if (exportJob?.isActive != true) {
                val fallbackNotification = notificationManager.buildProgressNotification(
                    clipTitle = "Viral Clip",
                    stage = ExportStage.PREPARING,
                    progressPercent = 0,
                    isIndeterminate = true,
                    etaText = null,
                    isDemo = false,
                    isPaused = false
                )
                startInForeground(fallbackNotification)
                stopForegroundAndSelf()
            }
            return
        }

        elapsedTickerJob?.cancel()
        exportJob?.cancel()
        appContainer.media3Engine.cancelActiveOperation()

        activeRequest = request
        val initialNotification = notificationManager.buildProgressNotification(
            clipTitle = request.clip.title,
            stage = ExportStage.PREPARING,
            progressPercent = 0,
            isIndeterminate = true,
            etaText = "Calculating…",
            isDemo = request.project.isDemo,
            isPaused = false
        )
        startInForeground(initialNotification)

        if (request.project.isDemo) {
            launchDemoExport(request)
        } else {
            launchMedia3Export(request)
        }
    }

    private fun handlePauseExport() {
        if (coordinator.markPaused()) {
            pushNotificationFromCurrentState(forceUpdate = true)
        }
    }

    private fun handleResumeExport() {
        if (coordinator.markResumed()) {
            pushNotificationFromCurrentState(forceUpdate = true)
        }
    }

    private fun handleCancelExport() {
        elapsedTickerJob?.cancel()
        exportJob?.cancel()
        appContainer.media3Engine.cancelActiveOperation()
        activeRequest = null
        coordinator.markCancelled()
        stopForegroundAndSelf()
    }

    private fun launchDemoExport(request: ExportJobRequest) {
        val clip = request.clip
        val resolution = request.resolution
        val hasCaptions = clip.captionStyle != CaptionStylePreset.NO_CAPTIONS &&
            clip.captions.isNotEmpty()

        startElapsedTicker()

        exportJob = serviceScope.launch {
            try {
                // Phase 1: Indeterminate codec warm-up (supports instant pause at every 100ms slice)
                var warmUpRemainingMs = 1_100L
                while (warmUpRemainingMs > 0L) {
                    coordinator.awaitIfPaused()
                    val step = minOf(100L, warmUpRemainingMs)
                    delay(step)
                    warmUpRemainingMs -= step
                }

                // Phase 2: Determinate encoding & caption burn-in
                for (pct in 4..94 step 3) {
                    coordinator.awaitIfPaused()
                    val state = coordinator.updateActiveProgress(
                        clip = clip,
                        resolution = resolution,
                        stage = ExportStage.ENCODING,
                        progressPercent = pct,
                        isIndeterminate = false,
                        hasCaptions = hasCaptions,
                        isDemo = true
                    )
                    pushNotificationForState(state, forceUpdate = false)
                    delay(170L)
                }

                // Phase 3: Finalizing & saving to Movies/ClipPulse
                for (pct in listOf(96, 98, 100)) {
                    coordinator.awaitIfPaused()
                    val done = pct == 100
                    val state = coordinator.updateActiveProgress(
                        clip = clip,
                        resolution = resolution,
                        stage = ExportStage.SAVING,
                        progressPercent = pct,
                        isIndeterminate = false,
                        hasCaptions = hasCaptions,
                        isDemo = true,
                        isComplete = done
                    )
                    if (!done) {
                        pushNotificationForState(state, forceUpdate = true)
                        delay(220L)
                    }
                }

                elapsedTickerJob?.cancel()
                val totalDemoMs = coordinator.currentActiveElapsedMs()
                runCatching {
                    appContainer.repository.recordExportedClip(
                        projectId = request.projectId,
                        clip = clip,
                        exportedUri = "demo://export/${clip.id}/${System.currentTimeMillis()}",
                        resolution = resolution,
                        renderDurationMs = totalDemoMs,
                        isDemo = true
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Could not record demo export metadata", e)
                }

                activeRequest = null
                stopForegroundAndSelf()
                notificationManager.showCompleted(clip.title, isDemo = true)
            } catch (e: CancellationException) {
                elapsedTickerJob?.cancel()
                throw e
            } catch (e: Exception) {
                elapsedTickerJob?.cancel()
                Log.e(TAG, "Demo export preview failed", e)
                val friendlyMsg = e.localizedMessage ?: "Demo export preview interrupted."
                coordinator.markError(clip, resolution, friendlyMsg)
                activeRequest = null
                stopForegroundAndSelf()
                notificationManager.showError(clip.title, friendlyMsg)
            }
        }
    }

    private fun launchMedia3Export(request: ExportJobRequest) {
        val clip = request.clip
        val project = request.project
        val resolution = request.resolution
        val hasCaptions = clip.captionStyle != CaptionStylePreset.NO_CAPTIONS &&
            clip.captions.isNotEmpty()

        startElapsedTicker()

        exportJob = serviceScope.launch {
            try {
                val uri = appContainer.media3Engine.exportClipToGallery(
                    sourceUri = Uri.parse(project.sourceUri),
                    clip = clip,
                    sourceWidth = project.sourceWidth,
                    sourceHeight = project.sourceHeight,
                    resolution = resolution,
                    isPausedFlow = coordinator.isPausedFlow
                ) { progress ->
                    val state = coordinator.updateActiveProgress(
                        clip = clip,
                        resolution = resolution,
                        stage = progress.stage,
                        progressPercent = progress.progressPercent,
                        isIndeterminate = progress.isIndeterminate,
                        hasCaptions = hasCaptions,
                        isDemo = false
                    )
                    pushNotificationForState(state, forceUpdate = false)
                }

                elapsedTickerJob?.cancel()
                val totalRenderMs = coordinator.currentActiveElapsedMs()
                val updatedClip = clip.copy(lastExportedUri = uri.toString())
                appContainer.repository.updateClip(updatedClip)
                runCatching {
                    appContainer.repository.recordExportedClip(
                        projectId = request.projectId,
                        clip = updatedClip,
                        exportedUri = uri.toString(),
                        resolution = resolution,
                        renderDurationMs = totalRenderMs,
                        isDemo = false
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Could not persist export history metadata/thumbnail", e)
                }

                coordinator.markCompleted(
                    updatedClip = updatedClip,
                    exportedUri = uri,
                    totalRenderMs = totalRenderMs
                )
                activeRequest = null
                stopForegroundAndSelf()
                notificationManager.showCompleted(updatedClip.title, isDemo = false)
            } catch (e: CancellationException) {
                elapsedTickerJob?.cancel()
                throw e
            } catch (e: Exception) {
                elapsedTickerJob?.cancel()
                Log.e(TAG, "MP4 export failed in foreground service", e)
                val friendlyMsg = e.localizedMessage
                    ?: "Export failed. Try 720p Fast or another video."
                coordinator.markError(clip, resolution, friendlyMsg)
                activeRequest = null
                stopForegroundAndSelf()
                notificationManager.showError(clip.title, friendlyMsg)
            }
        }
    }

    private fun startElapsedTicker() {
        elapsedTickerJob?.cancel()
        elapsedTickerJob = serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                if (!coordinator.isPausedFlow.value) {
                    val updated = coordinator.tickElapsedClock()
                    pushNotificationForState(updated, forceUpdate = false)
                }
            }
        }
    }

    private fun pushNotificationFromCurrentState(forceUpdate: Boolean) {
        pushNotificationForState(coordinator.exportState.value, forceUpdate = forceUpdate)
    }

    private fun pushNotificationForState(state: ExportUiState, forceUpdate: Boolean) {
        when (state) {
            is ExportUiState.Exporting -> {
                notificationManager.updateProgressNotification(
                    clipTitle = state.clip.title,
                    stage = state.stage,
                    progressPercent = state.progressPercent,
                    isIndeterminate = state.isIndeterminate,
                    etaText = formatEtaForNotification(state.estimatedRemainingMs),
                    isDemo = false,
                    isPaused = state.isPaused,
                    forceUpdate = forceUpdate
                )
            }

            is ExportUiState.DemoPreview -> {
                if (!state.isComplete) {
                    notificationManager.updateProgressNotification(
                        clipTitle = state.clip.title,
                        stage = state.stage,
                        progressPercent = state.progressPercent,
                        isIndeterminate = state.isIndeterminate,
                        etaText = formatEtaForNotification(state.estimatedRemainingMs),
                        isDemo = true,
                        isPaused = state.isPaused,
                        forceUpdate = forceUpdate
                    )
                }
            }

            else -> Unit
        }
    }

    private fun startInForeground(notification: Notification) {
        val foregroundType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(
            this,
            ExportNotificationManager.NOTIFICATION_ID_EXPORT,
            notification,
            foregroundType
        )
    }

    private fun stopForegroundAndSelf(startId: Int? = null) {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground state", e)
        }
        notificationManager.cancelProgress()
        if (startId != null) {
            stopSelf(startId)
        } else {
            stopSelf()
        }
    }

    private fun formatEtaForNotification(etaMs: Long?): String? {
        if (etaMs == null) return null
        val totalSec = ((etaMs + 500L) / 1_000L).coerceAtLeast(1L)
        return if (totalSec < 60L) {
            "~${totalSec}s left"
        } else {
            val mins = totalSec / 60L
            val secs = totalSec % 60L
            "~${mins}m ${secs}s left"
        }
    }

    companion object {
        const val ACTION_START = "com.example.action.EXPORT_START"
        const val ACTION_PAUSE = "com.example.action.EXPORT_PAUSE"
        const val ACTION_RESUME = "com.example.action.EXPORT_RESUME"
        const val ACTION_CANCEL = "com.example.action.EXPORT_CANCEL"

        fun startExport(context: Context) {
            val intent = Intent(context, VideoExportForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun createPauseIntent(context: Context): Intent =
            Intent(context, VideoExportForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }

        fun createResumeIntent(context: Context): Intent =
            Intent(context, VideoExportForegroundService::class.java).apply {
                action = ACTION_RESUME
            }

        fun createCancelIntent(context: Context): Intent =
            Intent(context, VideoExportForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }

        fun pauseExport(context: Context) {
            try {
                context.startService(createPauseIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to dispatch pause action to export service", e)
            }
        }

        fun resumeExport(context: Context) {
            try {
                context.startService(createResumeIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to dispatch resume action to export service", e)
            }
        }

        fun cancelExport(context: Context) {
            try {
                context.startService(createCancelIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to dispatch cancel action to export service", e)
            }
        }
    }
}
