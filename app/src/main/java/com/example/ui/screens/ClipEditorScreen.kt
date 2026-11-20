package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.model.VideoProject
import com.example.model.ViralClip
import com.example.ui.components.DemoPreviewCanvas
import com.example.ui.components.ExportPipelineDialog
import com.example.ui.components.LiveCaptionOverlay
import com.example.ui.components.YouTubeEmbedPlayerSurface
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonLime
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.viewmodel.ClipEditorViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class EditorTab(val label: String, val icon: ImageVector) {
    TRIM("Trim", Icons.Rounded.ContentCut),
    REFRAME("Reframe", Icons.Rounded.AspectRatio),
    CAPTIONS("Captions", Icons.Rounded.ClosedCaption),
    DETAILS("Details", Icons.Rounded.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipEditorScreen(
    clipId: Long,
    viewModel: ClipEditorViewModel,
    onNavigateBack: () -> Unit
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val clipFlow = remember(clipId) { viewModel.observeClip(clipId) }
    val clip by clipFlow.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val isComputingFaceTrack by viewModel.isComputingFaceTrack.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var relativePositionMs by remember { mutableLongStateOf(0L) }

    val attachLocalVideoLauncher = rememberLauncherForActivityResult(
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
            viewModel.attachLocalVideo(uri, continueToExportClip = null)
        }
    }

    Scaffold(
        containerColor = CharcoalBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = clip?.title ?: "Clip Editor",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("editor_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Viral Clips",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (clip != null) {
                        Button(
                            onClick = { viewModel.openExportDialog(clip!!) },
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .height(42.dp)
                                .testTag("editor_export_mp4_button"),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonLime)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = null,
                                tint = CharcoalBg,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Export MP4",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CharcoalBg
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CharcoalBg)
            )
        }
    ) { innerPadding ->
        val activeProject = project
        val activeClip = clip

        if (activeProject == null || activeClip == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ElectricViolet)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // TOP 60%: Live Media3 ExoPlayer (or Demo Canvas) + Real-time Synced Captions
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.58f)
                        .background(Color(0xFF07090D))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ClipPreviewViewport(
                        project = activeProject,
                        clip = activeClip,
                        isPlaying = isPlaying,
                        relativePositionMs = relativePositionMs,
                        onRelativePositionUpdate = { relativePositionMs = it },
                        onTogglePlay = { isPlaying = !isPlaying },
                        onAttachLocalVideoClick = {
                            attachLocalVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                    )
                }

                // BOTTOM 40%: Tabbed Control Deck
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.42f),
                    color = SurfaceCard,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    ) {
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                    color = ElectricViolet,
                                    height = 3.dp
                                )
                            },
                            divider = { HorizontalDivider(color = BorderHairline) }
                        ) {
                            EditorTab.entries.forEachIndexed { index, tab ->
                                val selected = index == selectedTabIndex
                                Tab(
                                    selected = selected,
                                    onClick = { selectedTabIndex = index },
                                    modifier = Modifier.testTag("editor_tab_${tab.name.lowercase()}"),
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (selected) NeonLime else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = tab.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (EditorTab.entries[selectedTabIndex]) {
                                EditorTab.TRIM -> TrimTabContent(
                                    clip = activeClip,
                                    sourceDurationMs = activeProject.durationMs,
                                    isPlaying = isPlaying,
                                    onTogglePlay = { isPlaying = !isPlaying },
                                    onTrimRangeChanged = { s, e ->
                                        viewModel.updateTrimRange(activeClip, s, e)
                                    },
                                    onNudgeStart = { delta -> viewModel.nudgeStart(activeClip, delta) },
                                    onNudgeEnd = { delta -> viewModel.nudgeEnd(activeClip, delta) }
                                )

                                EditorTab.REFRAME -> ReframeTabContent(
                                    clip = activeClip,
                                    isComputingFaceTrack = isComputingFaceTrack,
                                    isYouTubeOnly = activeProject.isYouTubeOnly,
                                    onAttachLocalVideoClick = {
                                        attachLocalVideoLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                    onAspectRatioChange = { ratio ->
                                        viewModel.updateAspectRatio(activeClip, ratio)
                                    },
                                    onToggleAutoFaceTrack = { enabled ->
                                        viewModel.toggleAutoFaceTrack(activeClip, enabled)
                                    },
                                    onManualPanChange = { pan ->
                                        viewModel.updateManualPan(activeClip, pan)
                                    },
                                    onZoomChange = { zoom ->
                                        viewModel.updateZoomScale(activeClip, zoom)
                                    }
                                )

                                EditorTab.CAPTIONS -> CaptionsTabContent(
                                    clip = activeClip,
                                    onStyleChange = { style ->
                                        viewModel.updateCaptionStyle(activeClip, style)
                                    },
                                    onYPositionChange = { y ->
                                        viewModel.updateCaptionYPosition(activeClip, y)
                                    },
                                    onUppercaseChange = { caps ->
                                        viewModel.updateCaptionUppercase(activeClip, caps)
                                    },
                                    onEditCaptionLine = { idx, text, highlight ->
                                        viewModel.updateCaptionChunk(activeClip, idx, text, highlight)
                                    }
                                )

                                EditorTab.DETAILS -> DetailsTabContent(
                                    clip = activeClip,
                                    onTitleChange = { title ->
                                        viewModel.updateClipTitle(activeClip, title)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ExportPipelineDialog(
        state = exportState,
        onSelectResolution = viewModel::selectExportResolution,
        onStartExport = viewModel::startExport,
        onAttachLocalVideo = { uri, targetClip ->
            viewModel.attachLocalVideo(uri, continueToExportClip = targetClip)
        },
        onCancelOrDismiss = viewModel::cancelOrDismissExport,
        onBackToClips = {
            viewModel.cancelOrDismissExport()
            onNavigateBack()
        }
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ClipPreviewViewport(
    project: VideoProject,
    clip: ViralClip,
    isPlaying: Boolean,
    relativePositionMs: Long,
    onRelativePositionUpdate: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onAttachLocalVideoClick: () -> Unit
) {
    val clipDuration = clip.clipDurationMs
    val progress = (relativePositionMs.toFloat() / clipDuration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(clip.aspectRatio.ratio, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onTogglePlay)
            .testTag("clip_preview_viewport")
    ) {
        when {
            project.isDemo -> {
                // Smooth 30fps loop timer driving DemoPreviewCanvas & live synced captions
                LaunchedEffect(clip.id, clip.startMs, clip.endMs, isPlaying) {
                    var pos = relativePositionMs.coerceIn(0L, clipDuration)
                    while (isActive && isPlaying) {
                        delay(33L)
                        pos = (pos + 33L) % clipDuration
                        onRelativePositionUpdate(pos)
                    }
                }

                DemoPreviewCanvas(
                    relativePositionMs = relativePositionMs,
                    clipDurationMs = clipDuration,
                    isPlaying = isPlaying,
                    panX = clip.effectivePanX,
                    zoomScale = clip.zoomScale,
                    autoFaceTrackEnabled = clip.autoFaceTrackEnabled,
                    clipSeed = clip.id.toInt(),
                    modifier = Modifier.fillMaxSize()
                )
            }

            project.isYouTubeOnly && !project.youtubeVideoId.isNullOrBlank() -> {
                YouTubeEmbedPlayerSurface(
                    videoId = project.youtubeVideoId,
                    thumbnailUrl = project.thumbnailPath,
                    clip = clip,
                    isPlaying = isPlaying,
                    relativePositionMs = relativePositionMs,
                    onRelativePositionUpdate = onRelativePositionUpdate,
                    onAttachLocalVideoClick = onAttachLocalVideoClick,
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                LiveExoPlayerSurface(
                    sourceUri = Uri.parse(project.sourceUri),
                    clip = clip,
                    isPlaying = isPlaying,
                    onRelativePositionUpdate = onRelativePositionUpdate
                )
            }
        }

        // Synced Animated Compose Captions directly over the video frame
        LiveCaptionOverlay(
            captions = clip.captions,
            relativePositionMs = relativePositionMs,
            style = clip.captionStyle,
            yPositionFraction = clip.captionYPosition,
            uppercase = clip.captionUppercase,
            modifier = Modifier.fillMaxSize()
        )

        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(CharcoalBg.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Resume playback",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp),
            color = NeonLime,
            trackColor = Color.White.copy(alpha = 0.22f)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LiveExoPlayerSurface(
    sourceUri: Uri,
    clip: ViralClip,
    isPlaying: Boolean,
    onRelativePositionUpdate: (Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(sourceUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }

    // Seek whenever trim start changes or play state toggles
    LaunchedEffect(exoPlayer, clip.startMs, clip.endMs, isPlaying) {
        if (exoPlayer.currentPosition < clip.startMs || exoPlayer.currentPosition >= clip.endMs) {
            exoPlayer.seekTo(clip.startMs)
        }
        exoPlayer.playWhenReady = isPlaying

        while (isActive) {
            val currentPos = exoPlayer.currentPosition
            if (currentPos >= clip.endMs) {
                exoPlayer.seekTo(clip.startMs)
                onRelativePositionUpdate(0L)
            } else if (currentPos < clip.startMs) {
                exoPlayer.seekTo(clip.startMs)
                onRelativePositionUpdate(0L)
            } else {
                onRelativePositionUpdate((currentPos - clip.startMs).coerceAtLeast(0L))
            }
            delay(40L)
        }
    }

    // Lifecycle-safe pause & mandatory ExoPlayer release
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { playerView ->
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = clip.zoomScale.coerceIn(1.0f, 1.5f)
                scaleX = scale
                scaleY = scale
                // Apply horizontal pan offset smoothly in live preview
                translationX = -clip.effectivePanX * size.width * 0.25f * scale
            }
    )
}
