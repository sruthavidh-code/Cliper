package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.media.YouTubeVideoMetadata
import com.example.model.AspectRatioOption
import com.example.model.CaptionStylePreset
import com.example.model.ClipConfig
import com.example.model.ExportedClipRecord
import com.example.model.ProjectStatus
import com.example.model.TargetDuration
import com.example.model.VideoProject
import com.example.model.formatTimeMs
import com.example.ui.components.CaptionStylePresetCard
import com.example.ui.components.DemoPreviewCanvas
import com.example.ui.components.ExportHistoryVaultSheet
import com.example.ui.components.RecentExportsCarouselSection
import com.example.ui.components.formatFileSizeCompact
import com.example.ui.components.openExportedVideo
import com.example.ui.components.shareClipMetadataText
import com.example.ui.components.shareExportedVideo
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.ScoreAmber
import com.example.ui.theme.ScoreCoral
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.PendingImportSource
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenAnalysis: (Long) -> Unit,
    onOpenClipsFeed: (Long) -> Unit,
    onOpenClipEditor: (Long, Long) -> Unit
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val totalClips by viewModel.totalClipCount.collectAsStateWithLifecycle()
    val recentExports by viewModel.recentExports.collectAsStateWithLifecycle()
    val totalExports by viewModel.totalExportCount.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showExportVaultSheet by remember { mutableStateOf(false) }

    val handlePlayExport: (ExportedClipRecord) -> Unit = { record ->
        if (record.isDemo || record.exportedUri.startsWith("demo://")) {
            val pid = record.projectId
            val cid = record.clipId
            if (pid != null && cid != null) {
                showExportVaultSheet = false
                onOpenClipEditor(pid, cid)
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Demo preview export — import a local video for gallery MP4 playback.")
                }
            }
        } else {
            openExportedVideo(context, Uri.parse(record.exportedUri))
        }
    }

    val handleShareExport: (ExportedClipRecord) -> Unit = { record ->
        if (record.isDemo || record.exportedUri.startsWith("demo://")) {
            shareClipMetadataText(
                context = context,
                title = "${record.title} (${record.resolution.label}, ${record.aspectRatio.shortLabel})",
                hashtags = listOf("#ClipPulse", "#Shorts")
            )
        } else {
            shareExportedVideo(context, Uri.parse(record.exportedUri), record.title)
        }
    }

    val handleEditExport: (ExportedClipRecord) -> Unit = { record ->
        val pid = record.projectId
        val cid = record.clipId
        if (pid != null && cid != null) {
            showExportVaultSheet = false
            onOpenClipEditor(pid, cid)
        }
    }

    val handleDeleteExport: (ExportedClipRecord) -> Unit = { record ->
        viewModel.deleteExport(record.id)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Removed from export history")
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.generatedSampleNotification) {
        val sample = uiState.generatedSampleNotification
        if (sample != null) {
            val sizeLabel = formatFileSizeCompact(sample.fileSizeBytes)
            val result = snackbarHostState.showSnackbar(
                message = "Saved 5:00 video ($sizeLabel) to Android Media",
                actionLabel = "Import",
                duration = SnackbarDuration.Long
            )
            viewModel.consumeGeneratedSampleNotification()
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onVideoSelected(sample.uri)
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.onVideoSelected(uri)
        }
    }

    Scaffold(
        containerColor = CharcoalBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    DashboardHeader(
                        totalClips = totalClips,
                        totalExports = totalExports,
                        onOpenExportsVault = { showExportVaultSheet = true }
                    )
                }

                item {
                    StudioHeroCard(
                        isExtracting = uiState.isExtractingMetadata,
                        isGeneratingSample = uiState.isGeneratingSampleVideo,
                        sampleProgress = uiState.sampleGenerationProgress,
                        onImportClick = {
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onYouTubeClick = viewModel::openYouTubeDialog,
                        onDemoClick = {
                            viewModel.openDemoMode(onDemoReady = onOpenClipsFeed)
                        },
                        onAddSampleMediaClick = {
                            viewModel.generateRandomSampleVideo(isAutoSeed = false)
                        }
                    )
                }

                if (recentExports.isNotEmpty()) {
                    item(key = "recent_exports_carousel_section") {
                        RecentExportsCarouselSection(
                            exports = recentExports,
                            onPlayExport = handlePlayExport,
                            onShareExport = handleShareExport,
                            onEditExport = handleEditExport,
                            onDeleteExport = handleDeleteExport,
                            onViewAllClick = { showExportVaultSheet = true }
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Projects",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "${projects.size} ${if (projects.size == 1) "project" else "projects"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (projects.isEmpty()) {
                    item {
                        EmptyStudioCard(
                            onTryDemo = { viewModel.openDemoMode(onDemoReady = onOpenClipsFeed) }
                        )
                    }
                } else {
                    items(projects, key = { it.id }) { project ->
                        RecentProjectCard(
                            project = project,
                            onClick = {
                                if (project.status == ProjectStatus.READY && project.clipCount > 0) {
                                    onOpenClipsFeed(project.id)
                                } else {
                                    onOpenAnalysis(project.id)
                                }
                            },
                            onDelete = { viewModel.deleteProject(project.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (uiState.showYouTubeDialog) {
        YouTubeLinkImportDialog(
            urlInput = uiState.youtubeUrlInput,
            isFetching = uiState.isFetchingYouTube,
            errorMessage = uiState.youtubeError,
            previewMetadata = uiState.previewYouTubeMetadata,
            onUrlChange = viewModel::updateYouTubeUrlInput,
            onPreviewFetch = viewModel::fetchYouTubeLinkMetadata,
            onContinue = viewModel::proceedYouTubeToConfig,
            onDismiss = viewModel::dismissYouTubeDialog
        )
    }

    val pendingSource = uiState.pendingSource
    if (pendingSource != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!uiState.isCreatingProject) viewModel.dismissConfigSheet() },
            sheetState = sheetState,
            containerColor = SurfaceCard,
            dragHandle = null
        ) {
            AiClipConfigSheetContent(
                source = pendingSource,
                config = uiState.config,
                isSubmitting = uiState.isCreatingProject,
                onDurationChange = viewModel::updateTargetDuration,
                onAspectChange = viewModel::updateAspectRatio,
                onCaptionStyleChange = viewModel::updateCaptionStyle,
                onKeywordsChange = viewModel::updateFocusKeywords,
                onGenerateClick = {
                    viewModel.startAiGeneration(onProjectCreated = onOpenAnalysis)
                }
            )
        }
    }

    if (showExportVaultSheet) {
        val vaultSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showExportVaultSheet = false },
            sheetState = vaultSheetState,
            containerColor = SurfaceCard,
            dragHandle = null
        ) {
            ExportHistoryVaultSheet(
                exports = recentExports,
                onPlayExport = handlePlayExport,
                onShareExport = handleShareExport,
                onEditExport = handleEditExport,
                onDeleteExport = handleDeleteExport,
                onClearAllExports = {
                    viewModel.clearAllExports()
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Export history cleared")
                    }
                },
                onDismiss = { showExportVaultSheet = false }
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    totalClips: Int,
    totalExports: Int,
    onOpenExportsVault: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.clippulse_icon_1795140874430),
                contentDescription = "ClipPulse logo",
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Column {
                Text(
                    text = "ClipPulse",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Text(
                    text = "AI Viral Clip Studio",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (totalExports > 0) {
                Surface(
                    onClick = onOpenExportsVault,
                    modifier = Modifier.testTag("header_exports_pill"),
                    shape = RoundedCornerShape(999.dp),
                    color = ElectricViolet.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = "Open Export History",
                            tint = ElectricVioletLight,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "$totalExports",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricVioletLight
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = NeonLime.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = NeonLime,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "$totalClips Clips",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonLime
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioHeroCard(
    isExtracting: Boolean,
    isGeneratingSample: Boolean,
    sampleProgress: Int,
    onImportClick: () -> Unit,
    onYouTubeClick: () -> Unit,
    onDemoClick: () -> Unit,
    onAddSampleMediaClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(ElectricViolet.copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.12f),
                            radius = size.width * 0.72f
                        ),
                        radius = size.width * 0.72f,
                        center = Offset(size.width * 0.85f, size.height * 0.12f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(NeonLime.copy(alpha = 0.12f), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.95f),
                            radius = size.width * 0.55f
                        ),
                        radius = size.width * 0.55f,
                        center = Offset(size.width * 0.1f, size.height * 0.95f)
                    )
                }
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = ElectricViolet.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "GEMINI FLASH + MEDIA3 + ML KIT",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricVioletLight,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Text(
                text = "Turn Long Videos Into Viral Shorts",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            Text(
                text = "Import a video from your device or paste a public YouTube link—Gemini Flash finds viral hooks, scores retention, and builds synced creator captions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Side-by-side Import Long Video + YouTube Link CTAs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onImportClick,
                    enabled = !isExtracting,
                    modifier = Modifier
                        .weight(1.15f)
                        .height(54.dp)
                        .testTag("import_long_video_button"),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.VideoLibrary,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Import Video",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }

                OutlinedButton(
                    onClick = onYouTubeClick,
                    enabled = !isExtracting,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("import_youtube_link_button"),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    border = BorderStroke(1.5.dp, ScoreCoral.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ScoreCoral.copy(alpha = 0.12f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = ScoreCoral,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "YouTube Link",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDemoClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("try_demo_mode_button"),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = NeonLime.copy(alpha = 0.08f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleOutline,
                        contentDescription = null,
                        tint = NeonLime,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Demo Mode",
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonLime,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onAddSampleMediaClick,
                    enabled = !isGeneratingSample,
                    modifier = Modifier
                        .weight(1.1f)
                        .height(50.dp)
                        .testTag("add_sample_5min_media_button"),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = NeonCyan.copy(alpha = 0.08f)
                    )
                ) {
                    if (isGeneratingSample) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NeonCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saving $sampleProgress%",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonCyan,
                            maxLines = 1
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MovieCreation,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "+ 5m Media",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonCyan,
                            maxLines = 1
                        )
                    }
                }
            }

            if (isGeneratingSample) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sample_video_progress_banner"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Creating random 5:00 MP4 in Android Media…",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan
                        )
                        Text(
                            text = "$sampleProgress%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (sampleProgress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = NeonCyan,
                        trackColor = SurfaceElevated
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeLinkImportDialog(
    urlInput: String,
    isFetching: Boolean,
    errorMessage: String?,
    previewMetadata: YouTubeVideoMetadata?,
    onUrlChange: (String) -> Unit,
    onPreviewFetch: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = { if (!isFetching) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("youtube_import_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = SurfaceCard,
            border = BorderStroke(1.dp, BorderHairline)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(ScoreCoral.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                tint = ScoreCoral
                            )
                        }
                        Column {
                            Text(
                                text = "Import YouTube Link",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Watch, youtu.be, Shorts, or Live URL",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("yt_dialog_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close YouTube link dialog",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlChange,
                    placeholder = {
                        Text(
                            text = "https://youtu.be/... or youtube.com/watch?v=...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                if (pasted.isNotBlank()) {
                                    onUrlChange(pasted.trim())
                                }
                            },
                            modifier = Modifier.testTag("yt_paste_clipboard_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = "Paste link from clipboard",
                                tint = ElectricVioletLight
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("yt_url_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricViolet,
                        unfocusedBorderColor = BorderHairline,
                        focusedContainerColor = SurfaceElevated,
                        unfocusedContainerColor = SurfaceElevated,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ScoreCoral
                    )
                }

                if (previewMetadata != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SurfaceElevated,
                        border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = previewMetadata.thumbnailUrl,
                                contentDescription = "YouTube thumbnail for ${previewMetadata.title}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 88.dp, height = 54.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = previewMetadata.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = previewMetadata.authorName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonLime,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (previewMetadata == null) {
                        OutlinedButton(
                            onClick = onPreviewFetch,
                            enabled = !isFetching && urlInput.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("yt_verify_link_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text("Preview", color = Color.White)
                        }
                    }

                    Button(
                        onClick = onContinue,
                        enabled = !isFetching && urlInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(50.dp)
                            .testTag("yt_continue_config_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking...", color = Color.White)
                        } else {
                            Text("Configure Clips", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStudioCard(onTryDemo: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = ElectricVioletLight,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = "Your Studio Is Ready",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Text(
                text = "Import any video or paste a YouTube link above—or explore a pre-built podcast project in Demo Mode right away.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onTryDemo,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderHairline),
                modifier = Modifier.testTag("empty_try_demo_button")
            ) {
                Text("Launch Demo Project", color = NeonLime)
            }
        }
    }
}

@Composable
private fun RecentProjectCard(
    project: VideoProject,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (project.status) {
        ProjectStatus.READY -> NeonLime
        ProjectStatus.ANALYZING -> ScoreAmber
        ProjectStatus.ERROR -> ScoreCoral
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("project_card_${project.id}"),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 76.dp, height = 92.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
            ) {
                val thumb = project.thumbnailPath
                if (project.isDemo || thumb.isNullOrBlank()) {
                    DemoPreviewCanvas(
                        relativePositionMs = 2_400L,
                        clipDurationMs = 28_000L,
                        isPlaying = false,
                        panX = 0f,
                        zoomScale = 1f,
                        autoFaceTrackEnabled = false,
                        clipSeed = project.id.toInt(),
                        compactThumbnail = true
                    )
                } else {
                    AsyncImage(
                        model = if (thumb.startsWith("http")) thumb else File(thumb),
                        contentDescription = "Video thumbnail for ${project.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CharcoalBg.copy(alpha = 0.82f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatTimeMs(project.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (project.isDemo) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ElectricViolet.copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "DEMO",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricVioletLight
                            )
                        }
                    } else if (project.isYouTubeSource) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScoreCoral.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (project.hasLocalVideo) "YT + HD" else "YOUTUBE",
                                style = MaterialTheme.typography.labelSmall,
                                color = ScoreCoral
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = project.status.displayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }

                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${project.clipCount} clips • ${project.aspectRatio.shortLabel} • ${project.defaultCaptionStyle.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_project_${project.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete project ${project.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiClipConfigSheetContent(
    source: PendingImportSource,
    config: ClipConfig,
    isSubmitting: Boolean,
    onDurationChange: (TargetDuration) -> Unit,
    onAspectChange: (AspectRatioOption) -> Unit,
    onCaptionStyleChange: (CaptionStylePreset) -> Unit,
    onKeywordsChange: (String) -> Unit,
    onGenerateClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(BorderHairline)
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "AI Clip Configuration",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )

            when (source) {
                is PendingImportSource.LocalVideo -> {
                    val metadata = source.metadata
                    val sizeMb = metadata.fileSizeBytes / (1024f * 1024f)
                    val needsProxy = metadata.fileSizeBytes > 18L * 1024L * 1024L
                    Text(
                        text = buildString {
                            append(metadata.displayName)
                            append(" • ")
                            append(formatTimeMs(metadata.durationMs))
                            append(" • ${metadata.width}×${metadata.height}")
                            if (sizeMb > 0.1f) {
                                append(" • %.1f MB".format(sizeMb))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (needsProxy) {
                        Text(
                            text = "Video exceeds 18 MB — ClipPulse will auto-compress a lightweight AI proxy for Gemini while keeping original HD for export.",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonLime
                        )
                    }
                }

                is PendingImportSource.YouTubeLink -> {
                    val yt = source.metadata
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = yt.thumbnailUrl,
                            contentDescription = "YouTube preview thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 72.dp, height = 44.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = yt.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "YouTube • ${yt.authorName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ScoreCoral
                            )
                        }
                    }
                }
            }
        }

        // Target Clip Duration Chips
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Target Clip Duration",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TargetDuration.entries.forEach { option ->
                    val selected = option == config.targetDuration
                    FilterChip(
                        selected = selected,
                        onClick = { onDurationChange(option) },
                        label = {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("duration_chip_${option.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricViolet,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceElevated,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = BorderHairline,
                            selectedBorderColor = ElectricViolet
                        )
                    )
                }
            }
        }

        // Output Aspect Ratio Selector
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Output Aspect Ratio",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatioOption.entries.forEach { ratio ->
                    val selected = ratio == config.aspectRatio
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onAspectChange(ratio) }
                            .testTag("aspect_option_${ratio.name.lowercase()}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) ElectricViolet.copy(alpha = 0.2f) else SurfaceElevated,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) ElectricViolet else BorderHairline
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = ratio.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = ratio.shortLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) NeonLime else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Caption Style Preset 2x2 Visual Preview Grid
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Caption Style Preset",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            val presets = CaptionStylePreset.entries
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CaptionStylePresetCard(
                        preset = presets[0],
                        selected = config.captionStyle == presets[0],
                        onClick = { onCaptionStyleChange(presets[0]) },
                        modifier = Modifier.weight(1f)
                    )
                    CaptionStylePresetCard(
                        preset = presets[1],
                        selected = config.captionStyle == presets[1],
                        onClick = { onCaptionStyleChange(presets[1]) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CaptionStylePresetCard(
                        preset = presets[2],
                        selected = config.captionStyle == presets[2],
                        onClick = { onCaptionStyleChange(presets[2]) },
                        modifier = Modifier.weight(1f)
                    )
                    CaptionStylePresetCard(
                        preset = presets[3],
                        selected = config.captionStyle == presets[3],
                        onClick = { onCaptionStyleChange(presets[3]) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Focus Keywords / Topic
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Focus Keywords / Topic (Optional)",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            OutlinedTextField(
                value = config.focusKeywords,
                onValueChange = onKeywordsChange,
                placeholder = {
                    Text(
                        text = "e.g., pricing tips, hook, storytelling",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("focus_keywords_input"),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricViolet,
                    unfocusedBorderColor = BorderHairline,
                    focusedContainerColor = SurfaceHighlight.copy(alpha = 0.45f),
                    unfocusedContainerColor = SurfaceElevated,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        Button(
            onClick = onGenerateClick,
            enabled = !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("generate_viral_clips_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonLime)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = CharcoalBg
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = CharcoalBg
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Generate Viral Clips",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = CharcoalBg
                )
            }
        }
    }
}
