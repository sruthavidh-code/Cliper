package com.example.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.media.YouTubeSupport
import com.example.model.VideoProject
import com.example.model.ViralClip
import com.example.model.formatTimeMs
import com.example.ui.components.DemoPreviewCanvas
import com.example.ui.components.ExportPipelineDialog
import com.example.ui.components.MinimizedExportBanner
import com.example.ui.components.ViralityBreakdownSection
import com.example.ui.components.ViralityScoreBadge
import com.example.ui.components.shareClipMetadataText
import com.example.ui.components.shareExportedVideo
import com.example.ui.components.shareTimestampedYouTubeLink
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.NeonLime
import com.example.ui.theme.ScoreCoral
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.viewmodel.ClipEditorViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsFeedScreen(
    viewModel: ClipEditorViewModel,
    onNavigateBack: () -> Unit,
    onOpenEditor: (Long) -> Unit
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val expandedState = remember { mutableStateMapOf<Long, Boolean>() }

    Scaffold(
        containerColor = CharcoalBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Viral Clips",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            if (project?.isDemo == true) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(NeonLime.copy(alpha = 0.18f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "DEMO MODE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonLime
                                    )
                                }
                            } else if (project?.isYouTubeSource == true) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ScoreCoral.copy(alpha = 0.2f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (project!!.hasLocalVideo) "YT + LOCAL HD" else "YOUTUBE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ScoreCoral
                                    )
                                }
                            }
                        }
                        if (project != null) {
                            Text(
                                text = project!!.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("clips_feed_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Studio Dashboard",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CharcoalBg)
            )
        }
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SurfaceCard,
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = NeonLime,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "${clips.size} ranked clips sorted by Virality Score",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                itemsIndexed(clips, key = { _, clip -> clip.id }) { index, clip ->
                    val isExpanded = expandedState[clip.id] ?: (index == 0)
                    ViralClipFeedCard(
                        rank = index + 1,
                        clip = clip,
                        project = project,
                        expanded = isExpanded,
                        onToggleExpand = { expandedState[clip.id] = !isExpanded },
                        onEditPreview = { onOpenEditor(clip.id) },
                        onQuickExport = { viewModel.openExportDialog(clip) },
                        onShare = {
                            val exported = clip.lastExportedUri
                            val ytId = project?.youtubeVideoId
                            when {
                                !exported.isNullOrBlank() ->
                                    shareExportedVideo(context, Uri.parse(exported), clip.title)
                                !ytId.isNullOrBlank() ->
                                    shareTimestampedYouTubeLink(
                                        context = context,
                                        title = clip.title,
                                        timestampedUrl = YouTubeSupport.buildTimestampedShareUrl(ytId, clip.startMs),
                                        hashtags = clip.hashtags
                                    )
                                else ->
                                    shareClipMetadataText(context, clip.title, clip.hashtags)
                            }
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(if (exportState.isMinimizedBannerVisible) 96.dp else 24.dp)) }
            }

            MinimizedExportBanner(
                state = exportState,
                onExpand = { viewModel.setExportMinimized(false) },
                onTogglePause = viewModel::togglePauseExport,
                onRequestCancel = viewModel::requestCancelExport,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    ExportPipelineDialog(
        state = exportState,
        onSelectResolution = viewModel::selectExportResolution,
        onStartExport = viewModel::startExport,
        onAttachLocalVideo = { uri, targetClip ->
            viewModel.attachLocalVideo(uri, continueToExportClip = targetClip)
        },
        onMinimize = { viewModel.setExportMinimized(true) },
        onTogglePause = viewModel::togglePauseExport,
        onRequestCancel = viewModel::requestCancelExport,
        onDismissCancelConfirm = viewModel::dismissCancelConfirmation,
        onConfirmCancel = viewModel::confirmCancelExport,
        onCancelOrDismiss = viewModel::cancelOrDismissExport,
        onBackToClips = viewModel::cancelOrDismissExport
    )
}

@Composable
private fun ViralClipFeedCard(
    rank: Int,
    clip: ViralClip,
    project: VideoProject?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditPreview: () -> Unit,
    onQuickExport: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("viral_clip_card_${clip.id}"),
        shape = RoundedCornerShape(22.dp),
        color = SurfaceCard,
        border = BorderStroke(
            width = if (rank == 1) 1.5.dp else 1.dp,
            color = if (rank == 1) ElectricViolet.copy(alpha = 0.65f) else BorderHairline
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Vertical 9:16 Video Preview Thumbnail
                Box(
                    modifier = Modifier
                        .width(92.dp)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceElevated)
                        .clickable(onClick = onEditPreview)
                        .testTag("clip_thumb_${clip.id}")
                ) {
                    val thumb = project?.thumbnailPath
                    if (project == null || project.isDemo || thumb.isNullOrBlank()) {
                        DemoPreviewCanvas(
                            relativePositionMs = rank * 2_200L,
                            clipDurationMs = clip.clipDurationMs,
                            isPlaying = false,
                            panX = clip.effectivePanX,
                            zoomScale = clip.zoomScale,
                            autoFaceTrackEnabled = false,
                            videoFilter = clip.videoFilter,
                            clipSeed = rank,
                            compactThumbnail = true
                        )
                    } else {
                        AsyncImage(
                            model = if (thumb.startsWith("http")) thumb else File(thumb),
                            contentDescription = "Preview thumbnail for ${clip.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Play overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CharcoalBg.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Preview clip",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Duration badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(CharcoalBg.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatTimeMs(clip.clipDurationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Right column: Rank, Score Badge, Title & Timestamps
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (rank == 1) NeonLime.copy(alpha = 0.16f)
                                        else ElectricViolet.copy(alpha = 0.2f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (rank == 1) "#1 TOP PICK" else "#$rank VIRAL CLIP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (rank == 1) NeonLime else ElectricVioletLight
                                )
                            }
                            Text(
                                text = "${formatTimeMs(clip.startMs)} – ${formatTimeMs(clip.endMs)} • ${clip.aspectRatio.shortLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        ViralityScoreBadge(score = clip.viralityScore, size = 56.dp)
                    }

                    Text(
                        text = clip.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Expandable AI Virality Breakdown trigger
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onToggleExpand)
                            .testTag("toggle_breakdown_${clip.id}"),
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceElevated
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AI Virality Breakdown",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricVioletLight
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (expanded) "Collapse breakdown" else "Expand breakdown",
                                tint = ElectricVioletLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = BorderHairline)
                    ViralityBreakdownSection(
                        hookScore = clip.hookScore,
                        flowScore = clip.flowScore,
                        engagementScore = clip.engagementScore,
                        reason = clip.reason,
                        hashtags = clip.hashtags
                    )
                }
            }

            // Action Bar: Edit & Preview, Quick Export, Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onEditPreview,
                    modifier = Modifier
                        .weight(1.35f)
                        .height(48.dp)
                        .testTag("edit_preview_button_${clip.id}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Edit & Preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onQuickExport,
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp)
                        .testTag("quick_export_button_${clip.id}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = NeonLime,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Export",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonLime,
                        maxLines = 1
                    )
                }

                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onShare)
                        .testTag("share_clip_button_${clip.id}"),
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceElevated,
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "Share clip",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
