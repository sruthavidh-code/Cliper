package com.example.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.example.model.CaptionStylePreset
import com.example.model.ExportResolution
import com.example.model.VideoProject
import com.example.model.ViralClip
import com.example.ui.viewmodel.ExportUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference

data class ExportJobRequest(
    val projectId: Long,
    val project: VideoProject,
    val clip: ViralClip,
    val resolution: ExportResolution
)

/**
 * Process-scoped coordinator bridging ClipEditorViewModel and VideoExportForegroundService.
 * Owns the single source of truth for ExportUiState, pause/resume gate, and active elapsed clock
 * (excluding paused time so elapsed duration and ETA remain accurate).
 */
class VideoExportCoordinator(
    private val appContext: Context,
    private val notificationManager: ExportNotificationManager
) {
    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Hidden)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPausedFlow: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val pendingRequest = AtomicReference<ExportJobRequest?>(null)
    private val clockLock = Any()

    private var accumulatedActiveMs: Long = 0L
    private var activeSegmentStartRealtimeMs: Long? = null
    private var smoothedPctPerMs: Double? = null

    fun consumePendingRequest(): ExportJobRequest? = pendingRequest.getAndSet(null)

    fun openExportDialog(clip: ViralClip, project: VideoProject?) {
        // If an export is already running (or paused), expand its modal instead of overwriting it
        if (_exportState.value.isActiveRender) {
            setMinimized(false)
            return
        }

        if (project != null && project.isYouTubeOnly && !project.youtubeVideoId.isNullOrBlank()) {
            _exportState.value = ExportUiState.YouTubeOptions(
                clip = clip,
                youtubeVideoId = project.youtubeVideoId,
                srtContent = YouTubeSupport.generateSrt(clip.captions, clip.captionUppercase)
            )
            return
        }

        val isDemo = project?.isDemo == true
        _exportState.value = ExportUiState.Configure(
            clip = clip,
            resolution = ExportResolution.HD_1080P,
            isDemoMode = isDemo
        )
    }

    fun setYouTubeAttaching(attaching: Boolean) {
        val current = _exportState.value
        if (current is ExportUiState.YouTubeOptions) {
            _exportState.value = current.copy(isAttachingLocalVideo = attaching)
        }
    }

    fun openConfigureAfterAttach(clip: ViralClip) {
        _exportState.value = ExportUiState.Configure(
            clip = clip,
            resolution = ExportResolution.HD_1080P,
            isDemoMode = false
        )
    }

    fun showImmediateError(clip: ViralClip, resolution: ExportResolution, message: String) {
        _exportState.value = ExportUiState.Error(
            clip = clip,
            resolution = resolution,
            message = message
        )
    }

    fun selectResolution(resolution: ExportResolution) {
        val current = _exportState.value
        if (current is ExportUiState.Configure) {
            _exportState.value = current.copy(resolution = resolution)
        } else if (current is ExportUiState.Error) {
            _exportState.value = current.copy(resolution = resolution)
        }
    }

    fun setMinimized(minimized: Boolean) {
        _exportState.value = when (val current = _exportState.value) {
            is ExportUiState.Exporting -> current.copy(
                isMinimized = minimized,
                showCancelConfirmation = if (minimized) false else current.showCancelConfirmation
            )
            is ExportUiState.DemoPreview -> current.copy(
                isMinimized = minimized,
                showCancelConfirmation = if (minimized) false else current.showCancelConfirmation
            )
            is ExportUiState.Completed -> current.copy(isMinimized = minimized)
            is ExportUiState.Error -> current.copy(isMinimized = minimized)
            else -> current
        }
    }

    fun requestCancelConfirmation() {
        _exportState.value = when (val current = _exportState.value) {
            is ExportUiState.Exporting -> current.copy(showCancelConfirmation = true)
            is ExportUiState.DemoPreview -> {
                if (current.isComplete) {
                    notificationManager.cancelAll()
                    ExportUiState.Hidden
                } else {
                    current.copy(showCancelConfirmation = true)
                }
            }
            else -> {
                notificationManager.cancelAll()
                ExportUiState.Hidden
            }
        }
    }

    fun dismissCancelConfirmation() {
        _exportState.value = when (val current = _exportState.value) {
            is ExportUiState.Exporting -> current.copy(showCancelConfirmation = false)
            is ExportUiState.DemoPreview -> current.copy(showCancelConfirmation = false)
            else -> current
        }
    }

    fun dismissIfInactive() {
        val current = _exportState.value
        if (current.isActiveRender) {
            requestCancelConfirmation()
        } else {
            notificationManager.cancelAll()
            _exportState.value = ExportUiState.Hidden
        }
    }

    /**
     * Enqueues a Media3 or Demo export and starts VideoExportForegroundService.
     */
    fun startForegroundExport(projectId: Long, project: VideoProject) {
        val (targetClip, targetResolution) = when (val current = _exportState.value) {
            is ExportUiState.Configure -> current.clip to current.resolution
            is ExportUiState.Error -> current.clip to current.resolution
            else -> return
        }

        val request = ExportJobRequest(
            projectId = projectId,
            project = project,
            clip = targetClip,
            resolution = targetResolution
        )
        val hasCaptions = targetClip.captionStyle != CaptionStylePreset.NO_CAPTIONS &&
            targetClip.captions.isNotEmpty()

        resetClockAndStart()
        _isPaused.value = false
        pendingRequest.set(request)

        _exportState.value = if (project.isDemo) {
            ExportUiState.DemoPreview(
                clip = targetClip,
                resolution = targetResolution,
                stage = ExportStage.PREPARING,
                progressPercent = 0,
                isIndeterminate = true,
                elapsedMs = 0L,
                estimatedRemainingMs = null,
                hasCaptions = hasCaptions,
                isPaused = false,
                isComplete = false,
                isMinimized = false,
                showCancelConfirmation = false
            )
        } else {
            ExportUiState.Exporting(
                clip = targetClip,
                resolution = targetResolution,
                stage = ExportStage.PREPARING,
                progressPercent = 0,
                isIndeterminate = true,
                elapsedMs = 0L,
                estimatedRemainingMs = null,
                hasCaptions = hasCaptions,
                isPaused = false,
                isMinimized = false,
                showCancelConfirmation = false
            )
        }

        VideoExportForegroundService.startExport(appContext)
    }

    fun togglePauseExport() {
        val current = _exportState.value
        if (!current.isActiveRender) return
        if (current.isPausedRender) {
            VideoExportForegroundService.resumeExport(appContext)
        } else {
            VideoExportForegroundService.pauseExport(appContext)
        }
    }

    fun confirmCancelExport() {
        VideoExportForegroundService.cancelExport(appContext)
        // Also clear immediately in case service has already stopped
        if (!_exportState.value.isActiveRender) {
            _exportState.value = ExportUiState.Hidden
        }
    }

    // --- Service-driven state mutations ---

    fun resetClockAndStart() {
        synchronized(clockLock) {
            accumulatedActiveMs = 0L
            activeSegmentStartRealtimeMs = SystemClock.elapsedRealtime()
            smoothedPctPerMs = null
        }
    }

    fun currentActiveElapsedMs(): Long = synchronized(clockLock) {
        val start = activeSegmentStartRealtimeMs
        if (start != null) {
            (accumulatedActiveMs + (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
        } else {
            accumulatedActiveMs.coerceAtLeast(0L)
        }
    }

    fun computeSmoothedEta(elapsedMs: Long, percent: Int, isIndeterminate: Boolean): Long? {
        if (_isPaused.value || isIndeterminate || percent < 5 || elapsedMs < 1_500L) return null
        if (percent >= 99) return 1_000L
        synchronized(clockLock) {
            val instantRate = percent.toDouble() / elapsedMs.toDouble()
            if (instantRate <= 0.0) return null
            val updatedRate = if (smoothedPctPerMs == null) {
                instantRate
            } else {
                0.35 * instantRate + 0.65 * smoothedPctPerMs!!
            }
            smoothedPctPerMs = updatedRate
            val remainingPct = (100 - percent).coerceAtLeast(1)
            return (remainingPct / updatedRate).toLong().coerceIn(1_000L, 60L * 60L * 1_000L)
        }
    }

    fun markPaused(): Boolean {
        val current = _exportState.value
        if (!current.isActiveRender || _isPaused.value) return false
        val frozenElapsed = synchronized(clockLock) {
            val start = activeSegmentStartRealtimeMs
            if (start != null) {
                accumulatedActiveMs += (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                activeSegmentStartRealtimeMs = null
            }
            accumulatedActiveMs.coerceAtLeast(0L)
        }
        _isPaused.value = true
        _exportState.value = when (current) {
            is ExportUiState.Exporting -> current.copy(
                isPaused = true,
                elapsedMs = frozenElapsed
            )
            is ExportUiState.DemoPreview -> current.copy(
                isPaused = true,
                elapsedMs = frozenElapsed
            )
            else -> current
        }
        return true
    }

    fun markResumed(): Boolean {
        val current = _exportState.value
        if (!current.isActiveRender || !_isPaused.value) return false
        synchronized(clockLock) {
            activeSegmentStartRealtimeMs = SystemClock.elapsedRealtime()
        }
        _isPaused.value = false
        _exportState.value = when (current) {
            is ExportUiState.Exporting -> current.copy(isPaused = false)
            is ExportUiState.DemoPreview -> current.copy(isPaused = false)
            else -> current
        }
        return true
    }

    suspend fun awaitIfPaused() {
        if (_isPaused.value) {
            _isPaused.first { !it }
        }
    }

    fun updateActiveProgress(
        clip: ViralClip,
        resolution: ExportResolution,
        stage: ExportStage,
        progressPercent: Int,
        isIndeterminate: Boolean,
        hasCaptions: Boolean,
        isDemo: Boolean,
        isComplete: Boolean = false
    ): ExportUiState {
        val elapsed = currentActiveElapsedMs()
        val paused = _isPaused.value
        val eta = if (isComplete) {
            0L
        } else {
            computeSmoothedEta(elapsed, progressPercent, isIndeterminate)
        }
        val updated = if (isDemo) {
            val latest = _exportState.value as? ExportUiState.DemoPreview
            ExportUiState.DemoPreview(
                clip = clip,
                resolution = resolution,
                stage = stage,
                progressPercent = progressPercent,
                isIndeterminate = isIndeterminate,
                elapsedMs = elapsed,
                estimatedRemainingMs = eta,
                hasCaptions = hasCaptions,
                isPaused = paused && !isComplete,
                isComplete = isComplete,
                isMinimized = latest?.isMinimized ?: false,
                showCancelConfirmation = if (isComplete) false else (latest?.showCancelConfirmation ?: false)
            )
        } else {
            val latest = _exportState.value as? ExportUiState.Exporting
            ExportUiState.Exporting(
                clip = clip,
                resolution = resolution,
                stage = stage,
                progressPercent = progressPercent,
                isIndeterminate = isIndeterminate,
                elapsedMs = elapsed,
                estimatedRemainingMs = eta,
                hasCaptions = hasCaptions,
                isPaused = paused,
                isMinimized = latest?.isMinimized ?: false,
                showCancelConfirmation = latest?.showCancelConfirmation ?: false
            )
        }
        _exportState.value = updated
        return updated
    }

    fun tickElapsedClock(): ExportUiState {
        if (_isPaused.value) return _exportState.value
        val elapsed = currentActiveElapsedMs()
        val current = _exportState.value
        val updated = when (current) {
            is ExportUiState.Exporting -> {
                val eta = computeSmoothedEta(elapsed, current.progressPercent, current.isIndeterminate)
                current.copy(elapsedMs = elapsed, estimatedRemainingMs = eta)
            }
            is ExportUiState.DemoPreview -> {
                if (!current.isComplete) {
                    val eta = computeSmoothedEta(elapsed, current.progressPercent, current.isIndeterminate)
                    current.copy(elapsedMs = elapsed, estimatedRemainingMs = eta)
                } else {
                    current
                }
            }
            else -> current
        }
        _exportState.value = updated
        return updated
    }

    fun markCompleted(updatedClip: ViralClip, exportedUri: Uri, totalRenderMs: Long) {
        val wasMinimized = (_exportState.value as? ExportUiState.Exporting)?.isMinimized ?: false
        _isPaused.value = false
        _exportState.value = ExportUiState.Completed(
            clip = updatedClip,
            exportedUri = exportedUri,
            totalRenderMs = totalRenderMs,
            isMinimized = wasMinimized
        )
    }

    fun markError(clip: ViralClip, resolution: ExportResolution, message: String) {
        val wasMinimized = when (val cur = _exportState.value) {
            is ExportUiState.Exporting -> cur.isMinimized
            is ExportUiState.DemoPreview -> cur.isMinimized
            else -> false
        }
        _isPaused.value = false
        _exportState.value = ExportUiState.Error(
            clip = clip,
            resolution = resolution,
            message = message,
            isMinimized = wasMinimized
        )
    }

    fun markCancelled() {
        _isPaused.value = false
        pendingRequest.set(null)
        _exportState.value = ExportUiState.Hidden
    }
}
