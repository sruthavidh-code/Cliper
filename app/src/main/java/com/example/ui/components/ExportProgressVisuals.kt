package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.media.ExportStage
import com.example.model.ExportResolution
import com.example.model.VideoFilterPreset
import com.example.model.ViralClip
import com.example.model.formatTimeMs
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.MontserratFontFamily
import com.example.ui.theme.NeonLime
import com.example.ui.theme.ScoreAmber
import com.example.ui.theme.ScoreCoral
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.ExportUiState

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Keeps the device screen awake while Media3 is actively rendering so long exports
 * are not interrupted by screen sleep, and reliably clears the flag once done.
 */
@Composable
fun KeepScreenAwakeEffect(keepAwake: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, keepAwake) {
        val window = context.findActivity()?.window
        if (keepAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
fun ExportProgressRing(
    progressPercent: Int,
    isIndeterminate: Boolean,
    stage: ExportStage,
    isDemo: Boolean,
    isPaused: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 164.dp
) {
    val targetFraction = (progressPercent.coerceIn(0, 100) / 100f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "export_ring_progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "export_ring_indeterminate")
    val spinDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "indeterminate_rotation"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_pulse"
    )

    Box(
        modifier = modifier
            .size(size)
            .testTag("export_progress_ring"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 12.dp.toPx()
            val inset = strokePx / 2f + 4.dp.toPx()
            val arcSize = Size(
                width = this.size.width - inset * 2f,
                height = this.size.height - inset * 2f
            )
            val topLeft = Offset(inset, inset)
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            // Subtle radial creator glow behind the ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ElectricViolet.copy(alpha = glowAlpha),
                        Color.Transparent
                    ),
                    center = center,
                    radius = this.size.minDimension * 0.56f
                ),
                radius = this.size.minDimension * 0.56f,
                center = center
            )

            // Base circular track
            drawArc(
                color = SurfaceElevated,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Crisp hairline inner guide ring
            drawCircle(
                color = BorderHairline.copy(alpha = 0.55f),
                radius = (arcSize.minDimension / 2f) - strokePx * 0.85f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            val sweepBrush = if (isPaused) {
                Brush.sweepGradient(
                    0.0f to ScoreAmber.copy(alpha = 0.7f),
                    0.5f to ScoreAmber,
                    1.0f to ScoreAmber.copy(alpha = 0.7f),
                    center = center
                )
            } else {
                Brush.sweepGradient(
                    0.0f to ElectricViolet,
                    0.45f to ElectricVioletLight,
                    0.85f to NeonLime,
                    1.0f to ElectricViolet,
                    center = center
                )
            }

            if (isIndeterminate) {
                rotate(degrees = if (isPaused) 0f else spinDegrees, pivot = center) {
                    drawArc(
                        brush = sweepBrush,
                        startAngle = -90f,
                        sweepAngle = 98f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            } else {
                val sweep = (animatedFraction * 360f).coerceAtLeast(6f)
                rotate(degrees = -90f, pivot = center) {
                    drawArc(
                        brush = sweepBrush,
                        startAngle = 0f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isIndeterminate) {
                Text(
                    text = if (isPaused) "HOLD" else "INIT",
                    fontFamily = MontserratFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    color = if (isPaused) ScoreAmber else ElectricVioletLight
                )
            } else {
                Text(
                    text = "${progressPercent.coerceIn(0, 100)}%",
                    fontFamily = MontserratFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    color = if (isPaused) ScoreAmber else NeonLime
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            isPaused -> ScoreAmber.copy(alpha = 0.2f)
                            isDemo -> ScoreAmber.copy(alpha = 0.16f)
                            else -> ElectricViolet.copy(alpha = 0.2f)
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isPaused) "PAUSED" else stage.shortLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isPaused || isDemo) ScoreAmber else ElectricVioletLight
                )
            }
        }
    }
}

private enum class ChecklistStepState {
    COMPLETED, ACTIVE, PENDING, SKIPPED
}

private data class ExportChecklistRow(
    val title: String,
    val detail: String,
    val state: ChecklistStepState
)

@Composable
fun ExportStageChecklist(
    stage: ExportStage,
    hasCaptions: Boolean,
    videoFilter: VideoFilterPreset = VideoFilterPreset.NONE,
    progressPercent: Int,
    isIndeterminate: Boolean,
    isPaused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        ExportChecklistRow(
            title = "Preparing media & crop",
            detail = when {
                stage == ExportStage.PREPARING && isPaused -> "Paused — tap Resume here or in notification"
                stage == ExportStage.PREPARING && isIndeterminate -> "Initializing hardware codec & framing"
                else -> "Clip range & framing locked"
            },
            state = when (stage) {
                ExportStage.PREPARING -> ChecklistStepState.ACTIVE
                ExportStage.ENCODING, ExportStage.SAVING -> ChecklistStepState.COMPLETED
            }
        ),
        ExportChecklistRow(
            title = if (videoFilter == VideoFilterPreset.NONE) {
                "Encoding H.264 video & AAC audio"
            } else {
                "Encoding + ${videoFilter.displayName} grade"
            },
            detail = when (stage) {
                ExportStage.PREPARING -> "Waiting for hardware encoder"
                ExportStage.ENCODING -> when {
                    isPaused -> "Paused at ${progressPercent.coerceIn(0, 99)}% — checkpoint ready"
                    videoFilter == VideoFilterPreset.NONE ->
                        "Media3 Transformer • ${progressPercent.coerceIn(0, 99)}%"
                    else ->
                        "Media3 Effects (${videoFilter.shortLabel}) • ${progressPercent.coerceIn(0, 99)}%"
                }
                ExportStage.SAVING -> if (videoFilter == VideoFilterPreset.NONE) {
                    "Video & audio tracks encoded"
                } else {
                    "${videoFilter.displayName} graded & encoded"
                }
            },
            state = when (stage) {
                ExportStage.PREPARING -> ChecklistStepState.PENDING
                ExportStage.ENCODING -> ChecklistStepState.ACTIVE
                ExportStage.SAVING -> ChecklistStepState.COMPLETED
            }
        ),
        ExportChecklistRow(
            title = "Burning styled captions",
            detail = if (!hasCaptions) {
                "Skipped (No Captions preset)"
            } else {
                when (stage) {
                    ExportStage.PREPARING -> "Queued for overlay pass"
                    ExportStage.ENCODING -> "Compositing active-word overlay"
                    ExportStage.SAVING -> "Captions burned into frames"
                }
            },
            state = if (!hasCaptions) {
                ChecklistStepState.SKIPPED
            } else {
                when (stage) {
                    ExportStage.PREPARING -> ChecklistStepState.PENDING
                    ExportStage.ENCODING -> ChecklistStepState.ACTIVE
                    ExportStage.SAVING -> ChecklistStepState.COMPLETED
                }
            }
        ),
        ExportChecklistRow(
            title = "Saving to Movies/ClipPulse",
            detail = when (stage) {
                ExportStage.SAVING -> if (progressPercent >= 100) "Published to gallery" else "Writing via Scoped MediaStore"
                else -> "Pending render completion"
            },
            state = when (stage) {
                ExportStage.SAVING -> if (progressPercent >= 100) ChecklistStepState.COMPLETED else ChecklistStepState.ACTIVE
                else -> ChecklistStepState.PENDING
            }
        )
    )

    val pulseTransition = rememberInfiniteTransition(label = "stage_dot_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "active_stage_alpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("export_stage_checklist"),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (row.state) {
                        ChecklistStepState.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Completed stage",
                                tint = NeonLime,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        ChecklistStepState.ACTIVE -> {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isPaused) ScoreAmber.copy(alpha = 0.25f)
                                        else ElectricViolet.copy(alpha = 0.25f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isPaused) ScoreAmber
                                            else NeonLime.copy(alpha = pulseAlpha)
                                        )
                                )
                            }
                        }
                        ChecklistStepState.PENDING,
                        ChecklistStepState.SKIPPED -> {
                            Box(
                                modifier = Modifier
                                    .size(18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(BorderHairline)
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (row.state == ChecklistStepState.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                            color = when (row.state) {
                                ChecklistStepState.COMPLETED,
                                ChecklistStepState.ACTIVE -> Color.White
                                ChecklistStepState.PENDING -> TextSecondary
                                ChecklistStepState.SKIPPED -> TextMuted
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = row.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (row.state) {
                                ChecklistStepState.ACTIVE -> ElectricVioletLight
                                ChecklistStepState.COMPLETED -> NeonLime.copy(alpha = 0.85f)
                                else -> TextMuted
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportTelemetryStrip(
    elapsedMs: Long,
    estimatedRemainingMs: Long?,
    clip: ViralClip,
    resolution: ExportResolution,
    isPaused: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryStatCard(
                icon = Icons.Rounded.Timer,
                label = "Elapsed",
                value = formatDurationClock(elapsedMs),
                valueColor = Color.White,
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                icon = Icons.Rounded.Schedule,
                label = if (isPaused) "Status" else "Est. Remaining",
                value = if (isPaused) "Paused" else formatEtaShort(estimatedRemainingMs),
                valueColor = when {
                    isPaused -> ScoreAmber
                    estimatedRemainingMs != null -> NeonLime
                    else -> ElectricVioletLight
                },
                modifier = Modifier.weight(1f)
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SpecChip(text = resolution.label)
            SpecChip(text = clip.aspectRatio.shortLabel)
            SpecChip(text = formatTimeMs(clip.clipDurationMs))
            if (clip.videoFilter != VideoFilterPreset.NONE) {
                SpecChip(text = clip.videoFilter.shortLabel)
            }
            SpecChip(text = clip.captionStyle.displayName)
        }
    }
}

@Composable
private fun TelemetryStatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ElectricVioletLight,
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SpecChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = SurfaceHighlight,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CancelExportConfirmationCard(
    progressPercent: Int,
    isIndeterminate: Boolean,
    onKeepRendering: () -> Unit,
    onConfirmStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cancel_export_confirmation_card"),
        shape = RoundedCornerShape(16.dp),
        color = ScoreCoral.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, ScoreCoral.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = ScoreCoral,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Cancel active render?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = if (isIndeterminate) {
                    "Media3 is initializing your video render. Stopping now will discard this export."
                } else {
                    "Your render is ${progressPercent.coerceIn(0, 100)}% complete. Stopping now will discard all progress."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onKeepRendering,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("keep_rendering_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Text("Keep Rendering", color = Color.White, maxLines = 1)
                }
                Button(
                    onClick = onConfirmStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("confirm_stop_export_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScoreCoral)
                ) {
                    Text(
                        text = "Stop Export",
                        color = CharcoalBg,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun MinimizedExportBanner(
    state: ExportUiState,
    onExpand: () -> Unit,
    onTogglePause: () -> Unit,
    onRequestCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = state.isMinimizedBannerVisible

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val borderColor = when (state) {
            is ExportUiState.Completed -> NeonLime.copy(alpha = 0.7f)
            is ExportUiState.Exporting -> if (state.isPaused) ScoreAmber.copy(alpha = 0.75f) else ElectricViolet.copy(alpha = 0.75f)
            is ExportUiState.DemoPreview -> when {
                state.isComplete -> NeonLime.copy(alpha = 0.7f)
                state.isPaused -> ScoreAmber.copy(alpha = 0.75f)
                else -> ElectricViolet.copy(alpha = 0.75f)
            }
            is ExportUiState.Error -> ScoreCoral.copy(alpha = 0.75f)
            else -> ElectricViolet.copy(alpha = 0.75f)
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onExpand)
                    .testTag("minimized_export_banner"),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceCard,
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.5.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (state) {
                        is ExportUiState.Exporting -> {
                            MiniExportRing(
                                progressPercent = state.progressPercent,
                                isIndeterminate = state.isIndeterminate,
                                isPaused = state.isPaused
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.isPaused) {
                                        "Paused • ${state.clip.title}"
                                    } else {
                                        "Exporting • ${state.clip.title}"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = if (state.isPaused) {
                                    "Paused at ${state.progressPercent}% • Tap Resume"
                                } else {
                                    buildString {
                                        append(state.stage.shortLabel)
                                        if (!state.isIndeterminate) {
                                            append(" • ${state.progressPercent}%")
                                        }
                                        append(" • ${formatEtaShort(state.estimatedRemainingMs)}")
                                    }
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.isPaused) ScoreAmber else NeonLime,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        is ExportUiState.DemoPreview -> {
                            if (state.isComplete) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Demo export complete",
                                    tint = NeonLime,
                                    modifier = Modifier.size(34.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Demo Preview Ready",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Tap to view export summary",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NeonLime,
                                        maxLines = 1
                                    )
                                }
                            } else {
                                MiniExportRing(
                                    progressPercent = state.progressPercent,
                                    isIndeterminate = state.isIndeterminate,
                                    isPaused = state.isPaused
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (state.isPaused) {
                                            "Paused • ${state.clip.title}"
                                        } else {
                                            "Demo Export • ${state.clip.title}"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subtitle = if (state.isPaused) {
                                        "Paused at ${state.progressPercent}% • Tap Resume"
                                    } else {
                                        buildString {
                                            append(state.stage.shortLabel)
                                            if (!state.isIndeterminate) {
                                                append(" • ${state.progressPercent}%")
                                            }
                                            append(" • ${formatEtaShort(state.estimatedRemainingMs)}")
                                        }
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ScoreAmber,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        is ExportUiState.Completed -> {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Export saved",
                                tint = NeonLime,
                                modifier = Modifier.size(34.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Saved to Movies/ClipPulse",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Tap to open or share ${state.clip.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonLime,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        is ExportUiState.Error -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Export failed",
                                tint = ScoreCoral,
                                modifier = Modifier.size(34.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Export Failed",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Tap to view details or retry",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ScoreCoral,
                                    maxLines = 1
                                )
                            }
                        }

                        else -> Unit
                    }

                    if (state.isActiveRender) {
                        IconButton(
                            onClick = onTogglePause,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("banner_pause_resume_export_button")
                        ) {
                            Icon(
                                imageVector = if (state.isPausedRender) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (state.isPausedRender) "Resume export" else "Pause export",
                                tint = if (state.isPausedRender) NeonLime else ScoreAmber,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onExpand,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("expand_export_banner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInFull,
                            contentDescription = "Expand export progress",
                            tint = ElectricVioletLight,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onRequestCancel,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("banner_cancel_export_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = if (state.isActiveRender) "Cancel active export" else "Dismiss export banner",
                            tint = if (state.isActiveRender) ScoreCoral else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniExportRing(
    progressPercent: Int,
    isIndeterminate: Boolean,
    isPaused: Boolean = false
) {
    val animatedFraction by animateFloatAsState(
        targetValue = (progressPercent.coerceIn(0, 100) / 100f),
        animationSpec = tween(durationMillis = 200),
        label = "mini_ring_progress"
    )
    val spinTransition = rememberInfiniteTransition(label = "mini_ring_spin")
    val spinDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mini_ring_angle"
    )

    Box(
        modifier = Modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 4.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val center = Offset(size.width / 2f, size.height / 2f)

            drawArc(
                color = SurfaceElevated,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            val brush = if (isPaused) {
                Brush.sweepGradient(
                    0f to ScoreAmber,
                    1f to ScoreAmber,
                    center = center
                )
            } else {
                Brush.sweepGradient(
                    0f to ElectricViolet,
                    0.7f to NeonLime,
                    1f to ElectricViolet,
                    center = center
                )
            }

            if (isIndeterminate) {
                rotate(degrees = if (isPaused) 0f else spinDegrees, pivot = center) {
                    drawArc(
                        brush = brush,
                        startAngle = -90f,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            } else {
                rotate(degrees = -90f, pivot = center) {
                    drawArc(
                        brush = brush,
                        startAngle = 0f,
                        sweepAngle = (animatedFraction * 360f).coerceAtLeast(8f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
        }
        if (!isIndeterminate) {
            Text(
                text = "${progressPercent.coerceIn(0, 99)}",
                fontFamily = MontserratFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = if (isPaused) ScoreAmber else Color.White
            )
        }
    }
}

fun formatDurationClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1_000L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%02d:%02d".format(mins, secs)
}

fun formatEtaShort(ms: Long?): String {
    if (ms == null) return "Calculating…"
    if (ms <= 0L) return "Done"
    val totalSec = ((ms + 500L) / 1_000L).coerceAtLeast(1L)
    return if (totalSec < 60L) {
        "~${totalSec}s left"
    } else {
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        "~${mins}m ${secs}s left"
    }
}
