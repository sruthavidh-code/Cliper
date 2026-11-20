package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.ViralClip
import com.example.model.formatTimePreciseMs
import com.example.ui.components.CaptionStylePresetCard
import com.example.ui.components.ViralityBreakdownSection
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.NeonLime
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHighlight
import kotlin.math.roundToLong

@Composable
fun TrimTabContent(
    clip: ViralClip,
    sourceDurationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onTrimRangeChanged: (Long, Long) -> Unit,
    onNudgeStart: (Long) -> Unit,
    onNudgeEnd: (Long) -> Unit
) {
    val safeTotal = sourceDurationMs.coerceAtLeast(clip.endMs.coerceAtLeast(3_000L)).toFloat()
    var sliderStart by remember(clip.id, clip.startMs) { mutableStateOf(clip.startMs.toFloat()) }
    var sliderEnd by remember(clip.id, clip.endMs) { mutableStateOf(clip.endMs.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Trim Range",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                Text(
                    text = "${formatTimePreciseMs(sliderStart.roundToLong())}  →  ${formatTimePreciseMs(sliderEnd.roundToLong())}  (${formatTimePreciseMs((sliderEnd - sliderStart).roundToLong())})",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonLime
                )
            }

            Button(
                onClick = onTogglePlay,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("trim_play_pause_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isPlaying) "Pause" else "Play", color = Color.White)
            }
        }

        RangeSlider(
            value = sliderStart.coerceIn(0f, safeTotal)..sliderEnd.coerceIn(0f, safeTotal),
            onValueChange = { range ->
                val minGap = 1_500f.coerceAtMost(safeTotal * 0.5f)
                if (range.endInclusive - range.start >= minGap) {
                    sliderStart = range.start
                    sliderEnd = range.endInclusive
                }
            },
            onValueChangeFinished = {
                onTrimRangeChanged(sliderStart.roundToLong(), sliderEnd.roundToLong())
            },
            valueRange = 0f..safeTotal,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("trim_range_slider"),
            colors = SliderDefaults.colors(
                thumbColor = NeonLime,
                activeTrackColor = ElectricViolet,
                inactiveTrackColor = SurfaceHighlight
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NudgeGroup(
                label = "Start (${formatTimePreciseMs(clip.startMs)})",
                onMinus = { onNudgeStart(-500L) },
                onPlus = { onNudgeStart(500L) },
                tagPrefix = "start",
                modifier = Modifier.weight(1f)
            )
            NudgeGroup(
                label = "End (${formatTimePreciseMs(clip.endMs)})",
                onMinus = { onNudgeEnd(-500L) },
                onPlus = { onNudgeEnd(500L) },
                tagPrefix = "end",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NudgeGroup(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onMinus,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("nudge_${tagPrefix}_minus"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Text("-0.5s", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                OutlinedButton(
                    onClick = onPlus,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("nudge_${tagPrefix}_plus"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Text("+0.5s", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ReframeTabContent(
    clip: ViralClip,
    isComputingFaceTrack: Boolean,
    isYouTubeOnly: Boolean = false,
    onAttachLocalVideoClick: () -> Unit = {},
    onAspectRatioChange: (AspectRatioOption) -> Unit,
    onToggleAutoFaceTrack: (Boolean) -> Unit,
    onManualPanChange: (Float) -> Unit,
    onZoomChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Aspect Ratio Quick Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectRatioOption.entries.forEach { option ->
                val selected = clip.aspectRatio == option
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onAspectRatioChange(option) }
                        .testTag("reframe_aspect_${option.name.lowercase()}"),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) ElectricViolet else SurfaceElevated,
                    border = BorderStroke(1.dp, if (selected) ElectricViolet else BorderHairline)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = option.shortLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Auto Face-Track Toggle (ML Kit Face Detection)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = SurfaceElevated,
            border = BorderStroke(
                1.dp,
                if (clip.autoFaceTrackEnabled && !isYouTubeOnly) NeonLime.copy(alpha = 0.5f) else BorderHairline
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isComputingFaceTrack) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = NeonLime
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.FaceRetouchingNatural,
                            contentDescription = null,
                            tint = if (clip.autoFaceTrackEnabled && !isYouTubeOnly) NeonLime else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column {
                        Text(
                            text = "Auto Face-Track Framing",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = when {
                                isYouTubeOnly -> "Attach local video to enable ML Kit face tracking"
                                isComputingFaceTrack -> "Running ML Kit face scan on frames..."
                                clip.autoFaceTrackEnabled -> "ML Kit active • Focus X: %+.2f".format(clip.faceCenterX)
                                else -> "Manual framing override active"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isYouTubeOnly) {
                    OutlinedButton(
                        onClick = onAttachLocalVideoClick,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("reframe_attach_local_button")
                    ) {
                        Text("Attach", style = MaterialTheme.typography.labelMedium, color = NeonLime)
                    }
                } else {
                    Switch(
                        checked = clip.autoFaceTrackEnabled,
                        onCheckedChange = onToggleAutoFaceTrack,
                        modifier = Modifier.testTag("auto_face_track_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CharcoalBg,
                            checkedTrackColor = NeonLime
                        )
                    )
                }
            }
        }

        // Manual Horizontal Pan Slider (-1.0 to +1.0)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Horizontal Pan",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Text(
                    text = "%+.2f".format(clip.effectivePanX),
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricVioletLight
                )
            }
            Slider(
                value = clip.effectivePanX,
                onValueChange = onManualPanChange,
                valueRange = -1.0f..1.0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_pan_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = ElectricViolet,
                    activeTrackColor = ElectricViolet,
                    inactiveTrackColor = SurfaceHighlight
                )
            )
        }

        // Zoom Scale Slider (1.0x to 1.5x)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Zoom Scale",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Text(
                    text = "%.2fx".format(clip.zoomScale),
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonLime
                )
            }
            Slider(
                value = clip.zoomScale,
                onValueChange = onZoomChange,
                valueRange = 1.0f..1.5f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("zoom_scale_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = NeonLime,
                    activeTrackColor = NeonLime,
                    inactiveTrackColor = SurfaceHighlight
                )
            )
        }
    }
}

@Composable
fun CaptionsTabContent(
    clip: ViralClip,
    onStyleChange: (CaptionStylePreset) -> Unit,
    onYPositionChange: (Float) -> Unit,
    onUppercaseChange: (Boolean) -> Unit,
    onEditCaptionLine: (index: Int, newText: String, newHighlight: String) -> Unit
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val presets = CaptionStylePreset.entries
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptionStylePresetCard(
                preset = presets[0],
                selected = clip.captionStyle == presets[0],
                onClick = { onStyleChange(presets[0]) },
                modifier = Modifier.weight(1f)
            )
            CaptionStylePresetCard(
                preset = presets[1],
                selected = clip.captionStyle == presets[1],
                onClick = { onStyleChange(presets[1]) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptionStylePresetCard(
                preset = presets[2],
                selected = clip.captionStyle == presets[2],
                onClick = { onStyleChange(presets[2]) },
                modifier = Modifier.weight(1f)
            )
            CaptionStylePresetCard(
                preset = presets[3],
                selected = clip.captionStyle == presets[3],
                onClick = { onStyleChange(presets[3]) },
                modifier = Modifier.weight(1f)
            )
        }

        // Vertical Y-Position Slider + Uppercase Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Vertical Position",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Text(
                        text = "${(clip.captionYPosition * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricVioletLight
                    )
                }
                Slider(
                    value = clip.captionYPosition,
                    onValueChange = onYPositionChange,
                    valueRange = 0.18f..0.86f,
                    modifier = Modifier.testTag("caption_y_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricViolet,
                        activeTrackColor = ElectricViolet,
                        inactiveTrackColor = SurfaceHighlight
                    )
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ALL CAPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = clip.captionUppercase,
                    onCheckedChange = onUppercaseChange,
                    modifier = Modifier.testTag("caption_uppercase_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CharcoalBg,
                        checkedTrackColor = NeonLime
                    )
                )
            }
        }

        Text(
            text = "Tap any caption line to edit inline",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            clip.captions.forEachIndexed { idx, chunk ->
                CaptionChunkEditRow(
                    index = idx,
                    chunk = chunk,
                    onClick = { editingIndex = idx }
                )
            }
        }
    }

    val idx = editingIndex
    if (idx != null && idx in clip.captions.indices) {
        InlineCaptionEditDialog(
            chunk = clip.captions[idx],
            onDismiss = { editingIndex = null },
            onSave = { newText, newHighlight ->
                onEditCaptionLine(idx, newText, newHighlight)
                editingIndex = null
            }
        )
    }
}

@Composable
private fun CaptionChunkEditRow(
    index: Int,
    chunk: CaptionChunk,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("caption_line_$index"),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${formatTimePreciseMs(chunk.startMs)} – ${formatTimePreciseMs(chunk.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricVioletLight
                )
                Text(
                    text = chunk.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chunk.highlightWord.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonLime.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = chunk.highlightWord,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonLime
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit caption line",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun InlineCaptionEditDialog(
    chunk: CaptionChunk,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var textValue by remember(chunk) { mutableStateOf(chunk.text) }
    var highlightValue by remember(chunk) { mutableStateOf(chunk.highlightWord) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Text(
                text = "Edit Caption (${formatTimePreciseMs(chunk.startMs)})",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Caption Text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_caption_text_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricViolet,
                        unfocusedBorderColor = BorderHairline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = highlightValue,
                    onValueChange = { highlightValue = it },
                    label = { Text("Highlight Word") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_caption_highlight_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonLime,
                        unfocusedBorderColor = BorderHairline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(textValue, highlightValue) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                modifier = Modifier.testTag("save_caption_edit_button")
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun DetailsTabContent(
    clip: ViralClip,
    onTitleChange: (String) -> Unit
) {
    val context = LocalContext.current
    var titleDraft by remember(clip.id) { mutableStateOf(clip.title) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedTextField(
            value = titleDraft,
            onValueChange = {
                titleDraft = it
                onTitleChange(it)
            },
            label = { Text("Clip Title") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("details_title_input"),
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

        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clip.hashtags.joinToString(" ")
                clipboard.setPrimaryClip(ClipData.newPlainText("ClipPulse Hashtags", text))
                Toast.makeText(context, "Hashtags copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("copy_hashtags_button"),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.6f))
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = ElectricVioletLight,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copy Generated Hashtags", color = Color.White)
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceElevated,
            border = BorderStroke(1.dp, BorderHairline)
        ) {
            ViralityBreakdownSection(
                hookScore = clip.hookScore,
                flowScore = clip.flowScore,
                engagementScore = clip.engagementScore,
                reason = clip.reason,
                hashtags = clip.hashtags,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}
