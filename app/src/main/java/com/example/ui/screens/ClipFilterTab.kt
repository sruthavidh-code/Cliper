package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.media.VideoFilterEffects
import com.example.model.VideoFilterPreset
import com.example.model.ViralClip
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.ScoreAmber
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextSecondary

@Composable
fun FilterTabContent(
    clip: ViralClip,
    isComparingOriginal: Boolean,
    onSelectFilter: (VideoFilterPreset) -> Unit,
    onToggleCompareOriginal: () -> Unit
) {
    val presets = VideoFilterPreset.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + A/B Original Comparison Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint = NeonLime,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "Media3 Color Grading",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "Live OpenGL preview • Applied before caption burn-in",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (clip.videoFilter != VideoFilterPreset.NONE) {
                Surface(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onToggleCompareOriginal)
                        .testTag("filter_compare_original_button"),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isComparingOriginal) NeonLime.copy(alpha = 0.2f) else SurfaceElevated,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isComparingOriginal) NeonLime else BorderHairline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Compare,
                            contentDescription = "Compare original footage",
                            tint = if (isComparingOriginal) NeonLime else ElectricVioletLight,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isComparingOriginal) "Original" else "Compare",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isComparingOriginal) NeonLime else Color.White
                        )
                    }
                }
            }
        }

        // 2x2 Visual Color Grading Preset Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VideoFilterPresetCard(
                preset = presets[0],
                selected = clip.videoFilter == presets[0],
                onClick = { onSelectFilter(presets[0]) },
                modifier = Modifier.weight(1f)
            )
            VideoFilterPresetCard(
                preset = presets[1],
                selected = clip.videoFilter == presets[1],
                onClick = { onSelectFilter(presets[1]) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VideoFilterPresetCard(
                preset = presets[2],
                selected = clip.videoFilter == presets[2],
                onClick = { onSelectFilter(presets[2]) },
                modifier = Modifier.weight(1f)
            )
            VideoFilterPresetCard(
                preset = presets[3],
                selected = clip.videoFilter == presets[3],
                onClick = { onSelectFilter(presets[3]) },
                modifier = Modifier.weight(1f)
            )
        }

        // Render Pipeline Order Strip
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = SurfaceElevated,
            border = BorderStroke(1.dp, BorderHairline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Export Render Order",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElectricVioletLight
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PipelineStepPill(text = "1. Crop", highlighted = false)
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    PipelineStepPill(
                        text = "2. ${clip.videoFilter.shortLabel}",
                        highlighted = clip.videoFilter != VideoFilterPreset.NONE
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    PipelineStepPill(text = "3. Captions", highlighted = false)
                }
                Text(
                    text = "Captions are burned after color grading so highlight colors stay vivid over monochrome or high-contrast video.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun VideoFilterPresetCard(
    preset: VideoFilterPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val swatchFilter = VideoFilterEffects.composeColorFilter(preset)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("filter_preset_${preset.name.lowercase()}"),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) ElectricViolet.copy(alpha = 0.2f) else SurfaceElevated,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) NeonLime else BorderHairline
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Live color swatch demonstrating exact filter look + un-filtered "Aa" caption chip on top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CharcoalBg)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            if (swatchFilter == null) {
                                drawContent()
                            } else {
                                drawIntoCanvas { canvas ->
                                    val paint = Paint().apply { colorFilter = swatchFilter }
                                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                                    drawContent()
                                    canvas.restore()
                                }
                            }
                        }
                ) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(ElectricViolet, NeonCyan, NeonMagenta, ScoreAmber),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        )
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.45f),
                        radius = size.height * 0.42f,
                        center = Offset(size.width * 0.28f, size.height * 0.5f)
                    )
                    drawCircle(
                        color = Color(0xFF0F172A).copy(alpha = 0.65f),
                        radius = size.height * 0.34f,
                        center = Offset(size.width * 0.72f, size.height * 0.55f)
                    )
                }

                // Unfiltered neon caption chip illustrating pre-caption burn-in order
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(CharcoalBg.copy(alpha = 0.78f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "Aa",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonLime
                    )
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(CharcoalBg.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Selected filter",
                            tint = NeonLime,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PipelineStepPill(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) NeonLime.copy(alpha = 0.16f) else SurfaceHighlight,
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) NeonLime.copy(alpha = 0.65f) else BorderHairline
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = if (highlighted) NeonLime else Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
