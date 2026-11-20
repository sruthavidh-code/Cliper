package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ClipRepository
import com.example.media.FaceReframeAnalyzer
import com.example.media.Media3Engine
import com.example.media.YouTubeSupport
import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.ExportResolution
import com.example.model.VideoProject
import com.example.model.ViralClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ClipEditorViewModel"

sealed interface ExportUiState {
    data object Hidden : ExportUiState
    data class Configure(
        val clip: ViralClip,
        val resolution: ExportResolution = ExportResolution.HD_1080P,
        val isDemoMode: Boolean = false
    ) : ExportUiState

    data class Exporting(
        val clip: ViralClip,
        val resolution: ExportResolution,
        val progressPercent: Int
    ) : ExportUiState

    data class Completed(
        val clip: ViralClip,
        val exportedUri: Uri
    ) : ExportUiState

    data class DemoPreview(
        val clip: ViralClip,
        val resolution: ExportResolution,
        val progressPercent: Int,
        val isComplete: Boolean
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
        val message: String
    ) : ExportUiState
}

class ClipEditorViewModel(
    private val projectId: Long,
    private val repository: ClipRepository,
    private val faceAnalyzer: FaceReframeAnalyzer,
    private val media3Engine: Media3Engine
) : ViewModel() {

    val project: StateFlow<VideoProject?> = repository.observeProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val clips: StateFlow<List<ViralClip>> = repository.observeClipsForProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Hidden)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _isComputingFaceTrack = MutableStateFlow(false)
    val isComputingFaceTrack: StateFlow<Boolean> = _isComputingFaceTrack.asStateFlow()

    private var exportJob: Job? = null

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
            // ML Kit requires local decoded video frames; keep safe center-crop until local file is attached
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
        val currentProject = project.value
        if (currentProject != null && currentProject.isYouTubeOnly && !currentProject.youtubeVideoId.isNullOrBlank()) {
            _exportState.value = ExportUiState.YouTubeOptions(
                clip = clip,
                youtubeVideoId = currentProject.youtubeVideoId,
                srtContent = YouTubeSupport.generateSrt(clip.captions, clip.captionUppercase)
            )
            return
        }
        val isDemo = currentProject?.isDemo == true
        _exportState.value = ExportUiState.Configure(
            clip = clip,
            resolution = ExportResolution.HD_1080P,
            isDemoMode = isDemo
        )
    }

    fun attachLocalVideo(localUri: Uri, continueToExportClip: ViralClip? = null) {
        viewModelScope.launch {
            val currentExport = _exportState.value
            if (currentExport is ExportUiState.YouTubeOptions) {
                _exportState.value = currentExport.copy(isAttachingLocalVideo = true)
            }
            try {
                repository.attachLocalVideoToProject(projectId, localUri)
                if (continueToExportClip != null) {
                    _exportState.value = ExportUiState.Configure(
                        clip = continueToExportClip,
                        resolution = ExportResolution.HD_1080P,
                        isDemoMode = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach local video to YouTube project", e)
                if (continueToExportClip != null) {
                    _exportState.value = ExportUiState.Error(
                        clip = continueToExportClip,
                        resolution = ExportResolution.HD_1080P,
                        message = e.localizedMessage ?: "Could not attach selected video file."
                    )
                }
            }
        }
    }

    fun selectExportResolution(resolution: ExportResolution) {
        val current = _exportState.value
        if (current is ExportUiState.Configure) {
            _exportState.value = current.copy(resolution = resolution)
        }
    }

    fun startExport() {
        val config = _exportState.value as? ExportUiState.Configure ?: return
        val currentProject = project.value ?: return

        exportJob?.cancel()
        if (currentProject.isDemo) {
            exportJob = viewModelScope.launch {
                for (pct in 0..100 step 10) {
                    _exportState.value = ExportUiState.DemoPreview(
                        clip = config.clip,
                        resolution = config.resolution,
                        progressPercent = pct,
                        isComplete = pct == 100
                    )
                    if (pct < 100) delay(120L)
                }
            }
            return
        }

        exportJob = viewModelScope.launch {
            _exportState.value = ExportUiState.Exporting(
                clip = config.clip,
                resolution = config.resolution,
                progressPercent = 0
            )
            try {
                val uri = media3Engine.exportClipToGallery(
                    sourceUri = Uri.parse(currentProject.sourceUri),
                    clip = config.clip,
                    sourceWidth = currentProject.sourceWidth,
                    sourceHeight = currentProject.sourceHeight,
                    resolution = config.resolution
                ) { percent ->
                    _exportState.value = ExportUiState.Exporting(
                        clip = config.clip,
                        resolution = config.resolution,
                        progressPercent = percent
                    )
                }
                val updatedClip = config.clip.copy(lastExportedUri = uri.toString())
                repository.updateClip(updatedClip)
                _exportState.value = ExportUiState.Completed(
                    clip = updatedClip,
                    exportedUri = uri
                )
            } catch (e: CancellationException) {
                _exportState.value = ExportUiState.Hidden
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "MP4 export failed", e)
                _exportState.value = ExportUiState.Error(
                    clip = config.clip,
                    resolution = config.resolution,
                    message = e.localizedMessage ?: "Export failed. Try 720p Fast or another video."
                )
            }
        }
    }

    fun cancelOrDismissExport() {
        exportJob?.cancel()
        media3Engine.cancelActiveOperation()
        _exportState.value = ExportUiState.Hidden
    }

    private fun saveClip(updated: ViralClip) {
        viewModelScope.launch {
            repository.updateClip(updated)
        }
    }

    override fun onCleared() {
        super.onCleared()
        exportJob?.cancel()
        media3Engine.cancelActiveOperation()
    }

    companion object {
        fun factory(
            projectId: Long,
            repository: ClipRepository,
            faceAnalyzer: FaceReframeAnalyzer,
            media3Engine: Media3Engine
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ClipEditorViewModel(
                    projectId = projectId,
                    repository = repository,
                    faceAnalyzer = faceAnalyzer,
                    media3Engine = media3Engine
                ) as T
            }
        }
    }
}
