package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ClipRepository
import com.example.data.ImportedVideoMetadata
import com.example.media.YouTubeSupport
import com.example.media.YouTubeVideoMetadata
import com.example.model.AspectRatioOption
import com.example.model.CaptionStylePreset
import com.example.model.ClipConfig
import com.example.model.TargetDuration
import com.example.model.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PendingImportSource {
    data class LocalVideo(val metadata: ImportedVideoMetadata) : PendingImportSource
    data class YouTubeLink(val metadata: YouTubeVideoMetadata) : PendingImportSource
}

data class DashboardUiState(
    val pendingSource: PendingImportSource? = null,
    val config: ClipConfig = ClipConfig(),
    val isExtractingMetadata: Boolean = false,
    val isCreatingProject: Boolean = false,
    val showYouTubeDialog: Boolean = false,
    val youtubeUrlInput: String = "",
    val isFetchingYouTube: Boolean = false,
    val youtubeError: String? = null,
    val previewYouTubeMetadata: YouTubeVideoMetadata? = null,
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val repository: ClipRepository
) : ViewModel() {

    val projects: StateFlow<List<VideoProject>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val totalClipCount: StateFlow<Int> = repository.totalClipCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExtractingMetadata = true, errorMessage = null) }
            try {
                val metadata = repository.extractVideoMetadata(uri)
                _uiState.update {
                    it.copy(
                        pendingSource = PendingImportSource.LocalVideo(metadata),
                        config = ClipConfig(),
                        isExtractingMetadata = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExtractingMetadata = false,
                        errorMessage = e.localizedMessage ?: "Unable to read the selected video."
                    )
                }
            }
        }
    }

    fun openYouTubeDialog() {
        _uiState.update {
            it.copy(
                showYouTubeDialog = true,
                youtubeUrlInput = "",
                youtubeError = null,
                previewYouTubeMetadata = null,
                isFetchingYouTube = false
            )
        }
    }

    fun dismissYouTubeDialog() {
        _uiState.update {
            it.copy(
                showYouTubeDialog = false,
                isFetchingYouTube = false,
                youtubeError = null
            )
        }
    }

    fun updateYouTubeUrlInput(url: String) {
        _uiState.update {
            it.copy(
                youtubeUrlInput = url,
                youtubeError = null,
                previewYouTubeMetadata = if (url.trim() != it.previewYouTubeMetadata?.canonicalUrl) null else it.previewYouTubeMetadata
            )
        }
    }

    fun fetchYouTubeLinkMetadata() {
        val rawUrl = _uiState.value.youtubeUrlInput.trim()
        if (rawUrl.isBlank()) {
            _uiState.update { it.copy(youtubeError = "Paste a YouTube link to continue.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingYouTube = true, youtubeError = null) }
            try {
                val metadata = YouTubeSupport.fetchMetadata(rawUrl)
                _uiState.update {
                    it.copy(
                        isFetchingYouTube = false,
                        previewYouTubeMetadata = metadata,
                        youtubeError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isFetchingYouTube = false,
                        previewYouTubeMetadata = null,
                        youtubeError = e.localizedMessage ?: "Invalid YouTube link."
                    )
                }
            }
        }
    }

    fun proceedYouTubeToConfig() {
        val existingPreview = _uiState.value.previewYouTubeMetadata
        if (existingPreview != null) {
            _uiState.update {
                it.copy(
                    showYouTubeDialog = false,
                    pendingSource = PendingImportSource.YouTubeLink(existingPreview),
                    config = ClipConfig()
                )
            }
            return
        }

        // Fetch and advance in a single tap if user didn't preview first
        val rawUrl = _uiState.value.youtubeUrlInput.trim()
        if (rawUrl.isBlank()) {
            _uiState.update { it.copy(youtubeError = "Paste a YouTube link to continue.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingYouTube = true, youtubeError = null) }
            try {
                val metadata = YouTubeSupport.fetchMetadata(rawUrl)
                _uiState.update {
                    it.copy(
                        isFetchingYouTube = false,
                        showYouTubeDialog = false,
                        previewYouTubeMetadata = metadata,
                        pendingSource = PendingImportSource.YouTubeLink(metadata),
                        config = ClipConfig()
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isFetchingYouTube = false,
                        youtubeError = e.localizedMessage ?: "Invalid YouTube link."
                    )
                }
            }
        }
    }

    fun dismissConfigSheet() {
        _uiState.update { it.copy(pendingSource = null, isCreatingProject = false) }
    }

    fun updateTargetDuration(duration: TargetDuration) {
        _uiState.update { it.copy(config = it.config.copy(targetDuration = duration)) }
    }

    fun updateAspectRatio(aspectRatio: AspectRatioOption) {
        _uiState.update { it.copy(config = it.config.copy(aspectRatio = aspectRatio)) }
    }

    fun updateCaptionStyle(style: CaptionStylePreset) {
        _uiState.update { it.copy(config = it.config.copy(captionStyle = style)) }
    }

    fun updateFocusKeywords(keywords: String) {
        _uiState.update { it.copy(config = it.config.copy(focusKeywords = keywords)) }
    }

    fun startAiGeneration(onProjectCreated: (Long) -> Unit) {
        val source = _uiState.value.pendingSource ?: return
        val config = _uiState.value.config
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingProject = true, errorMessage = null) }
            try {
                val projectId = when (source) {
                    is PendingImportSource.LocalVideo ->
                        repository.createProjectFromImport(source.metadata, config)
                    is PendingImportSource.YouTubeLink ->
                        repository.createProjectFromYouTube(source.metadata, config)
                }
                _uiState.update { it.copy(pendingSource = null, isCreatingProject = false) }
                onProjectCreated(projectId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCreatingProject = false,
                        errorMessage = e.localizedMessage ?: "Failed to create project."
                    )
                }
            }
        }
    }

    fun openDemoMode(onDemoReady: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val demoId = repository.ensureDemoProject()
                onDemoReady(demoId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Could not load Demo Mode.")
                }
            }
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun factory(repository: ClipRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(repository) as T
                }
            }
    }
}
