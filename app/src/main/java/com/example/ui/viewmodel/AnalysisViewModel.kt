package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiClipAnalyzer
import com.example.ai.NoSpeechDetectedException
import com.example.data.ClipRepository
import com.example.media.AudioExtractor
import com.example.media.FaceReframeAnalyzer
import com.example.media.NoAudioTrackException
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
    private val audioExtractor: AudioExtractor
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
                // YouTube path remains unchanged
                if (project.isYouTubeOnly && !project.youtubeUrl.isNullOrBlank()) {
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 0,
                        stageSubtitle = "Validating public YouTube link & metadata..."
                    )
                    delay(280L)

                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 1,
                        stageSubtitle = "Streaming YouTube link to Gemini Flash for viral hook detection..."
                    )
                    val ytResult = geminiAnalyzer.analyzeYouTubeVideo(
                        youtubeUrl = project.youtubeUrl,
                        projectId = project.id,
                        targetDuration = project.targetDuration,
                        aspectRatio = project.aspectRatio,
                        defaultCaptionStyle = project.defaultCaptionStyle,
                        focusKeywords = project.focusKeywords
                    ) { chars ->
                        _uiState.value = AnalysisUiState.Running(
                            projectTitle = project.title,
                            activeStageIndex = 1,
                            stageSubtitle = "Streaming viral clip timestamps from Gemini Flash ($chars chars)..."
                        )
                    }

                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 2,
                        stageSubtitle = "Ranking ${ytResult.clips.size} YouTube moments & syncing captions..."
                    )
                    ytResult.detectedDurationMs?.let { detected ->
                        repository.updateProjectDuration(project.id, detected)
                    }
                    delay(350L)

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

                // Local video path (never uploads video — keeps original strictly as content:// URI)
                val sourceUri = Uri.parse(project.sourceUri)

                // Stage 0: Extracting audio (16 kHz mono AAC + 50 ms loudness envelope)
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 0,
                    stageSubtitle = "Extracting audio track & loudness envelope...",
                    proxyProgressPercent = 0
                )
                val extracted = audioExtractor.extractAudioForGemini(
                    sourceUri = sourceUri,
                    fallbackDurationMs = project.durationMs
                ) { pct ->
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 0,
                        stageSubtitle = "Extracting audio ($pct%)...",
                        proxyProgressPercent = pct
                    )
                }

                val exactDurationMs = extracted.totalDurationMs.coerceAtLeast(project.durationMs)
                if (exactDurationMs > 0L && exactDurationMs != project.durationMs) {
                    repository.updateProjectDuration(project.id, exactDurationMs)
                }

                // Stage 1: Analyzing with Gemini (audio/mp4 inline upload, chunked only if > 14 MB)
                val chunkCount = extracted.chunks.size
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 1,
                    stageSubtitle = if (chunkCount > 1) {
                        "Analyzing audio chunk 1 of $chunkCount with Gemini Flash..."
                    } else {
                        "Analyzing audio with Gemini Flash for spoken viral hooks..."
                    },
                    proxyProgressPercent = null
                )

                val generatedClips = geminiAnalyzer.analyzeVideo(
                    audioChunks = extracted.chunks,
                    projectId = project.id,
                    totalDurationMs = exactDurationMs,
                    loudnessEnvelope = extracted.loudnessEnvelope,
                    envelopeBucketMs = extracted.envelopeBucketMs,
                    targetDuration = project.targetDuration,
                    aspectRatio = project.aspectRatio,
                    defaultCaptionStyle = project.defaultCaptionStyle,
                    focusKeywords = project.focusKeywords
                ) { chunkIndex, totalChunks, chars ->
                    val label = if (totalChunks > 1) {
                        "Analyzing with Gemini (chunk ${chunkIndex + 1}/$totalChunks • $chars chars)..."
                    } else {
                        "Analyzing with Gemini ($chars chars)..."
                    }
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 1,
                        stageSubtitle = label,
                        proxyProgressPercent = null
                    )
                }

                // Stage 2: Ranking & pause-gap snapping
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 2,
                    stageSubtitle = "Ranking ${generatedClips.size} clips, snapping cuts to pauses & syncing word captions..."
                )
                delay(350L)

                // Stage 3: Framing on the local video URI via on-device ML Kit Face Detection
                _uiState.value = AnalysisUiState.Running(
                    projectTitle = project.title,
                    activeStageIndex = 3,
                    stageSubtitle = "Framing clips with on-device ML Kit Face Detection..."
                )

                val reframedClips = generatedClips.mapIndexed { idx, clip ->
                    _uiState.value = AnalysisUiState.Running(
                        projectTitle = project.title,
                        activeStageIndex = 3,
                        stageSubtitle = "Framing clip ${idx + 1} of ${generatedClips.size}..."
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
            } catch (e: NoAudioTrackException) {
                Log.w(TAG, "Selected video has no audio track", e)
                repository.updateProjectStatus(projectId, ProjectStatus.ERROR)
                _uiState.value = AnalysisUiState.Error(
                    projectTitle = project.title,
                    message = e.message
                        ?: "This video has no audio track. Please import a video with sound."
                )
            } catch (e: NoSpeechDetectedException) {
                Log.w(TAG, "No spoken speech detected in audio", e)
                repository.updateProjectStatus(projectId, ProjectStatus.ERROR)
                _uiState.value = AnalysisUiState.Error(
                    projectTitle = project.title,
                    message = e.message
                        ?: "No spoken dialogue was detected in this video's audio. Import a video with speech or tap Retry."
                )
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
    }

    companion object {
        fun factory(
            projectId: Long,
            repository: ClipRepository,
            geminiAnalyzer: GeminiClipAnalyzer,
            faceAnalyzer: FaceReframeAnalyzer,
            audioExtractor: AudioExtractor
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AnalysisViewModel(
                    projectId = projectId,
                    repository = repository,
                    geminiAnalyzer = geminiAnalyzer,
                    faceAnalyzer = faceAnalyzer,
                    audioExtractor = audioExtractor
                ) as T
            }
        }
    }
}
