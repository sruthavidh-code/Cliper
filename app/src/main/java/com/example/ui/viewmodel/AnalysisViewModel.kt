package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiClipAnalyzer
import com.example.data.ClipRepository
import com.example.media.FaceReframeAnalyzer
import com.example.media.Media3Engine
import com.example.model.ProjectStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AnalysisViewModel"

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data class Running(
        val projectTitle: String,
        val activeStageIndex: Int, // 0..3
        val stageSubtitle: String,
        val proxyProgressPercent: Int? = null
    ) : AnalysisUiState

    data class Completed(val projectId: Long, val clipCount: Int) : AnalysisUiState
    data class Error(val projectTitle: String, val message: String) : AnalysisUiState
}

class AnalysisViewModel(
    private val projectId: Long,
    private val repository: ClipRepository,
    private val geminiAnalyzer: GeminiClipAnalyzer,
    private val faceAnalyzer: FaceReframeAnalyzer,
    private val media3Engine: Media3Engine
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    init {
        startAnalysis()
    }

    fun retryAnalysis() {
        startAnalysis()
    }

    private fun startAnalysis() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val project = repository.getProject(projectId)
            if (project == null) {
                _uiState.value = AnalysisUiState.Error(
                    projectTitle = "ClipPulse Project",
                    message = "Project could not be found in local storage."
                )
                return@launch
            }

            if (project.isDemo) {
                _uiState.value = AnalysisUiState.Completed(project.id, project.clipCount)
                return@launch
            }

            repository.updateProjectStatus(projectId, ProjectStatus.ANALYZING)

            try {
                if (project.isYouTubeOnly && !project.youtubeUrl.isNullOrBlank()) {
                    // Stage 1: Validate YouTube link & metadata
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 0,
                        stageSubtitle = "Validating public YouTube link & metadata..."
                    )
                    delay(280L)

                    // Stage 2: Detect high-retention hooks directly via Gemini fileData
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 1,
                        stageSubtitle = "Streaming YouTube link to Gemini AI for viral hook detection..."
                    )
                    val ytResult = geminiAnalyzer.analyzeYouTubeVideo(
                        youtubeUrl = project.youtubeUrl,
                        projectId = project.id,
                        targetDuration = project.targetDuration,
                        aspectRatio = project.aspectRatio,
                        defaultCaptionStyle = project.defaultCaptionStyle,
                        focusKeywords = project.focusKeywords
                    )

                    // Stage 3: Score viral potential & verify synced caption chunks
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 2,
                        stageSubtitle = "Ranking ${ytResult.clips.size} YouTube moments & syncing captions..."
                    )
                    ytResult.detectedDurationMs?.let { detected ->
                        repository.updateProjectDuration(project.id, detected)
                    }
                    delay(350L)

                    // Stage 4: Set smart framing defaults (ML Kit activates if local video is attached)
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 3,
                        stageSubtitle = "Preparing 9:16 framing presets & embedded player sync..."
                    )
                    val readyClips = ytResult.clips.map { clip ->
                        clip.copy(
                            autoFaceTrackEnabled = false,
                            faceCenterX = 0f,
                            manualPanX = 0f
                        )
                    }
                    delay(300L)

                    repository.saveGeneratedClips(project.id, readyClips)
                    _uiState.value = AnalysisUiState.Completed(project.id, readyClips.size)
                    return@launch
                }

                // Stage 1: Prepare video metadata & compact AI proxy if > 18 MB
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 0,
                    stageSubtitle = "Inspecting source tracks and checking payload size..."
                )
                val sourceUri = Uri.parse(project.sourceUri)
                val payload = media3Engine.prepareVideoPayloadForGemini(sourceUri) { proxyPct ->
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 0,
                        stageSubtitle = "Auto-compressing lightweight AI proxy ($proxyPct%)...",
                        proxyProgressPercent = proxyPct
                    )
                }

                // Stage 2: Detect high-retention hooks with Firebase AI Logic (Gemini)
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 1,
                    stageSubtitle = if (payload.wasCompressedProxy) {
                        "Compact AI proxy ready. Scanning video for 3-second viral hooks..."
                    } else {
                        "Scanning video audio & visuals for high-retention hooks..."
                    }
                )

                val generatedClips = geminiAnalyzer.analyzeVideo(
                    videoBytes = payload.bytes,
                    mimeType = payload.mimeType,
                    projectId = project.id,
                    durationMs = project.durationMs,
                    targetDuration = project.targetDuration,
                    aspectRatio = project.aspectRatio,
                    defaultCaptionStyle = project.defaultCaptionStyle,
                    focusKeywords = project.focusKeywords
                )

                // Stage 3: Score viral potential & verify synced caption chunks
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 2,
                    stageSubtitle = "Ranking ${generatedClips.size} moments by hook, flow & engagement..."
                )
                delay(450L)

                // Stage 4: Calculate 9:16 smart framing with on-device ML Kit Face Detection
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 3,
                    stageSubtitle = "Sampling frames with ML Kit Face Detection for 9:16 framing..."
                )

                val reframedClips = generatedClips.mapIndexed { idx, clip ->
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 3,
                        stageSubtitle = "Auto-framing clip ${idx + 1} of ${generatedClips.size}..."
                    )
                    val faceX = faceAnalyzer.computeSmartHorizontalFocus(
                        videoUri = sourceUri,
                        startMs = clip.startMs,
                        endMs = clip.endMs
                    )
                    clip.copy(
                        faceCenterX = faceX,
                        manualPanX = faceX,
                        autoFaceTrackEnabled = true
                    )
                }

                repository.saveGeneratedClips(project.id, reframedClips)
                _uiState.value = AnalysisUiState.Completed(project.id, reframedClips.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI analysis pipeline failed", e)
                repository.updateProjectStatus(projectId, ProjectStatus.ERROR)
                _uiState.value = AnalysisUiState.Error(
                    projectTitle = project.title,
                    message = e.localizedMessage
                        ?: "AI analysis encountered an unexpected error. Tap Retry AI Analysis."
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
        media3Engine.cancelActiveOperation()
    }

    companion object {
        fun factory(
            projectId: Long,
            repository: ClipRepository,
            geminiAnalyzer: GeminiClipAnalyzer,
            faceAnalyzer: FaceReframeAnalyzer,
            media3Engine: Media3Engine
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AnalysisViewModel(
                    projectId = projectId,
                    repository = repository,
                    geminiAnalyzer = geminiAnalyzer,
                    faceAnalyzer = faceAnalyzer,
                    media3Engine = media3Engine
                ) as T
            }
        }
    }
}
