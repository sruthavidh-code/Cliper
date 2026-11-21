package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ClipRepository
import com.example.media.ExportStage
import com.example.media.FaceReframeAnalyzer
import com.example.media.VideoExportCoordinator
import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.ExportResolution
import com.example.model.VideoFilterPreset
import com.example.model.VideoProject
import com.example.model.ViralClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ClipEditorViewModel"

sealed interface ExportUiState {
    val isActiveRender: Boolean
        get() = when (this) {
            is Exporting -> true
            is DemoPreview -> !isComplete
            else -> false
        }

    val isPausedRender: Boolean
        get() = when (this) {
            is Exporting -> isPaused
            is DemoPreview -> !isComplete && isPaused
            else -> false
        }

    val isMinimizedBannerVisible: Boolean
        get() = when (this) {
            is Exporting -> isMinimized
            is DemoPreview -> isMinimized
            is Completed -> isMinimized
            is Error -> isMinimized
            else -> false
        }

    data object Hidden : ExportUiState

    data class Configure(
        val clip: ViralClip,
        val resolution: ExportResolution = ExportResolution.HD_1080P,
        val isDemoMode: Boolean = false
    ) : ExportUiState

    data class Exporting(
        val clip: ViralClip,
        val resolution: ExportResolution,
        val stage: ExportStage = ExportStage.PREPARING,
        val progressPercent: Int = 0,
        val isIndeterminate: Boolean = true,
        val elapsedMs: Long = 0L,
        val estimatedRemainingMs: Long? = null,
        val hasCaptions: Boolean = true,
        val isPaused: Boolean = false,
        val isMinimized: Boolean = false,
        val showCancelConfirmation: Boolean = false
    ) : ExportUiState

    data class Completed(
        val clip: ViralClip,
        val exportedUri: Uri,
        val totalRenderMs: Long = 0L,
        val isMinimized: Boolean = false
    ) : ExportUiState

    data class DemoPreview(
        val clip: ViralClip,
        val resolution: ExportResolution,
        val stage: ExportStage = ExportStage.PREPARING,
        val progressPercent: Int = 0,
        val isIndeterminate: Boolean = true,
        val elapsedMs: Long = 0L,
        val estimatedRemainingMs: Long? = null,
        val hasCaptions: Boolean = true,
        val isPaused: Boolean = false,
        val isComplete: Boolean = false,
        val isMinimized: Boolean = false,
        val showCancelConfirmation: Boolean = false
    ) : ExportUiState

    data class YouTubeOptions(
        val clip: ViralClip,
        val youtubeVideoId: String,
        val srtContent: String,
        val isAttachingLocalVideo: Boolean = false
    ) : ExportUiState

    data class Error(
        val clip: ViralClip,
        val resolution: ExportResolution,
        val message: String,
        val isMinimized: Boolean = false
    ) : ExportUiState
}

class ClipEditorViewModel(
    private val projectId: Long,
    private val repository: ClipRepository,
    private val faceAnalyzer: FaceReframeAnalyzer,
    private val exportCoordinator: VideoExportCoordinator
) : ViewModel() {

    val project: StateFlow<VideoProject?> = repository.observeProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val clips: StateFlow<List<ViralClip>> = repository.observeClipsForProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val exportState: StateFlow<ExportUiState> = exportCoordinator.exportState

    private val _isComputingFaceTrack = MutableStateFlow(false)
    val isComputingFaceTrack: StateFlow<Boolean> = _isComputingFaceTrack.asStateFlow()

    fun observeClip(clipId: Long): StateFlow<ViralClip?> = repository.observeClip(clipId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun updateTrimRange(clip: ViralClip, newStartMs: Long, newEndMs: Long) {
        val maxDuration = project.value?.durationMs?.coerceAtLeast(3_000L) ?: (clip.endMs + 10_000L)
        val clampedStart = newStartMs.coerceIn(0L, (maxDuration - 1_500L).coerceAtLeast(0L))
        val clampedEnd = newEndMs.coerceIn(clampedStart + 1_500L, maxDuration)
        saveClip(clip.copy(startMs = clampedStart, endMs = clampedEnd))
    }

    fun nudgeStart(clip: ViralClip, deltaMs: Long) {
        updateTrimRange(clip, clip.startMs + deltaMs, clip.endMs)
    }

    fun nudgeEnd(clip: ViralClip, deltaMs: Long) {
        updateTrimRange(clip, clip.startMs, clip.endMs + deltaMs)
    }

    fun toggleAutoFaceTrack(clip: ViralClip, enabled: Boolean) {
        val currentProject = project.value
        if (!enabled) {
            saveClip(clip.copy(autoFaceTrackEnabled = false))
            return
        }

        if (currentProject == null || currentProject.isDemo) {
            val demoFocus = if (clip.faceCenterX != 0f) clip.faceCenterX else -0.12f
            saveClip(clip.copy(autoFaceTrackEnabled = true, faceCenterX = demoFocus))
            return
        }

        if (currentProject.isYouTubeOnly) {
            saveClip(clip.copy(autoFaceTrackEnabled = false, faceCenterX = 0f))
            return
        }

        viewModelScope.launch {
            _isComputingFaceTrack.value = true
            try {
                val computedX = faceAnalyzer.computeSmartHorizontalFocus(
                    videoUri = Uri.parse(currentProject.sourceUri),
                    startMs = clip.startMs,
                    endMs = clip.endMs
                )
                saveClip(
                    clip.copy(
                        autoFaceTrackEnabled = true,
                        faceCenterX = computedX,
                        manualPanX = computedX
                    )
                )
            } finally {
                _isComputingFaceTrack.value = false
            }
        }
    }

    fun updateManualPan(clip: ViralClip, panX: Float) {
        saveClip(
            clip.copy(
                autoFaceTrackEnabled = false,
                manualPanX = panX.coerceIn(-1f, 1f)
            )
        )
    }

    fun updateZoomScale(clip: ViralClip, zoom: Float) {
        saveClip(clip.copy(zoomScale = zoom.coerceIn(1.0f, 1.5f)))
    }

    fun updateAspectRatio(clip: ViralClip, aspectRatio: AspectRatioOption) {
        saveClip(clip.copy(aspectRatio = aspectRatio))
    }

    fun updateVideoFilter(clip: ViralClip, filter: VideoFilterPreset) {
        saveClip(clip.copy(videoFilter = filter))
    }

    fun updateCaptionStyle(clip: ViralClip, style: CaptionStylePreset) {
        saveClip(clip.copy(captionStyle = style))
    }

    fun updateCaptionYPosition(clip: ViralClip, yFraction: Float) {
        saveClip(clip.copy(captionYPosition = yFraction.coerceIn(0.18f, 0.86f)))
    }

    fun updateCaptionUppercase(clip: ViralClip, uppercase: Boolean) {
        saveClip(clip.copy(captionUppercase = uppercase))
    }

    fun updateCaptionChunk(clip: ViralClip, index: Int, updatedText: String, updatedHighlight: String) {
        if (index !in clip.captions.indices) return
        val mutable = clip.captions.toMutableList()
        val old = mutable[index]
        val trimmed = updatedText.trim().ifBlank { old.text }
        val chosenHighlight = updatedHighlight.trim()
            .ifBlank { trimmed.split(" ").maxByOrNull { it.length }.orEmpty() }
        mutable[index] = CaptionChunk(
            startMs = old.startMs,
            endMs = old.endMs,
            text = trimmed,
            highlightWord = chosenHighlight
        )
        saveClip(clip.copy(captions = mutable))
    }

    fun updateClipTitle(clip: ViralClip, newTitle: String) {
        saveClip(clip.copy(title = newTitle))
    }

    fun openExportDialog(clip: ViralClip) {
        exportCoordinator.openExportDialog(clip = clip, project = project.value)
    }

    fun attachLocalVideo(localUri: Uri, continueToExportClip: ViralClip? = null) {
        viewModelScope.launch {
            exportCoordinator.setYouTubeAttaching(true)
            try {
                repository.attachLocalVideoToProject(projectId, localUri)
                if (continueToExportClip != null) {
                    exportCoordinator.openConfigureAfterAttach(continueToExportClip)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach local video to YouTube project", e)
                if (continueToExportClip != null) {
                    exportCoordinator.showImmediateError(
                        clip = continueToExportClip,
                        resolution = ExportResolution.HD_1080P,
                        message = e.localizedMessage ?: "Could not attach selected video file."
                    )
                }
            }
        }
    }

    fun selectExportResolution(resolution: ExportResolution) {
        exportCoordinator.selectResolution(resolution)
    }

    fun setExportMinimized(minimized: Boolean) {
        exportCoordinator.setMinimized(minimized)
    }

    fun togglePauseExport() {
        exportCoordinator.togglePauseExport()
    }

    fun requestCancelExport() {
        exportCoordinator.requestCancelConfirmation()
    }

    fun dismissCancelConfirmation() {
        exportCoordinator.dismissCancelConfirmation()
    }

    fun confirmCancelExport() {
        exportCoordinator.confirmCancelExport()
    }

    fun startExport() {
        val currentProject = project.value ?: return
        exportCoordinator.startForegroundExport(projectId = projectId, project = currentProject)
    }

    fun cancelOrDismissExport() {
        exportCoordinator.dismissIfInactive()
    }

    private fun saveClip(updated: ViralClip) {
        viewModelScope.launch {
            repository.updateClip(updated)
        }
    }

    companion object {
        fun factory(
            projectId: Long,
            repository: ClipRepository,
            faceAnalyzer: FaceReframeAnalyzer,
            exportCoordinator: VideoExportCoordinator
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ClipEditorViewModel(
                    projectId = projectId,
                    repository = repository,
                    faceAnalyzer = faceAnalyzer,
                    exportCoordinator = exportCoordinator
                ) as T
            }
        }
    }
}
