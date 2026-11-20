package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.model.formatTimePreciseMs
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.NeonMagenta
import kotlin.math.abs
import kotlin.math.sin

/**
 * Rich animated creator-studio storyboard canvas for Demo Mode playback preview.
 * Responds live to panX, zoomScale, autoFaceTrackEnabled, play/pause state, and relative timecode.
 */
@Composable
fun DemoPreviewCanvas(
    relativePositionMs: Long,
    clipDurationMs: Long,
    isPlaying: Boolean,
    panX: Float,
    zoomScale: Float,
    autoFaceTrackEnabled: Boolean,
    clipSeed: Int = 0,
    compactThumbnail: Boolean = false,
    modifier: Modifier = Modifier
) {
    val phase = (relativePositionMs / 240f) + clipSeed * 1.7f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalBg)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Studio atmospheric gradient backdrop
            val topColor = when (clipSeed % 3) {
                0 -> Color(0xFF1D123A)
                1 -> Color(0xFF0E2238)
                else -> Color(0xFF28122E)
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(topColor, Color(0xFF111522), Color(0xFF090B10))
                )
            )

            // Studio rim-light glow orbs shifted by horizontal pan & zoom
            val shiftX = -panX * w * 0.24f * zoomScale
            val scale = zoomScale.coerceIn(1.0f, 1.5f)
            val pulse = if (isPlaying) 0.85f + 0.15f * sin(phase) else 0.92f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ElectricViolet.copy(alpha = 0.42f * pulse), Color.Transparent),
                    center = Offset(w * 0.28f + shiftX, h * 0.26f),
                    radius = w * 0.65f * scale
                ),
                center = Offset(w * 0.28f + shiftX, h * 0.26f),
                radius = w * 0.65f * scale
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonCyan.copy(alpha = 0.26f * pulse), Color.Transparent),
                    center = Offset(w * 0.78f + shiftX, h * 0.34f),
                    radius = w * 0.52f * scale
                ),
                center = Offset(w * 0.78f + shiftX, h * 0.34f),
                radius = w * 0.52f * scale
            )

            // Subtle broadcast framing grid lines
            val gridColor = Color.White.copy(alpha = 0.05f)
            drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1f)
            drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1f)

            // Stylized studio presenter silhouette (head + shoulders + broadcast mic)
            val subjectCenterX = w * 0.5f + shiftX
            val headCenterY = h * 0.36f
            val headRadius = w * 0.145f * scale

            // Shoulders
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF2B324C), Color(0xFF171B2B))
                ),
                topLeft = Offset(subjectCenterX - w * 0.30f * scale, headCenterY + headRadius * 1.15f),
                size = Size(w * 0.60f * scale, h * 0.36f * scale),
                cornerRadius = CornerRadius(w * 0.18f * scale, w * 0.18f * scale)
            )

            // Head silhouette with subtle rim light
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF3E476A), Color(0xFF23283D))
                ),
                radius = headRadius,
                center = Offset(subjectCenterX, headCenterY)
            )
            drawCircle(
                color = ElectricViolet.copy(alpha = 0.55f),
                radius = headRadius,
                center = Offset(subjectCenterX, headCenterY),
                style = Stroke(width = 2.5f)
            )

            // Studio podcast boom mic silhouette on lower-left
            drawLine(
                color = Color(0xFF475569),
                start = Offset(subjectCenterX - w * 0.36f * scale, h * 0.72f),
                end = Offset(subjectCenterX - w * 0.12f * scale, headCenterY + headRadius * 1.35f),
                strokeWidth = 6f * scale
            )
            drawRoundRect(
                color = Color(0xFF1E293B),
                topLeft = Offset(
                    subjectCenterX - w * 0.15f * scale,
                    headCenterY + headRadius * 1.1f
                ),
                size = Size(w * 0.09f * scale, h * 0.055f * scale),
                cornerRadius = CornerRadius(12f, 12f)
            )

            // ML Kit Face-Tracking Reticle when Auto Face-Track is enabled
            if (autoFaceTrackEnabled && !compactThumbnail) {
                val boxSize = headRadius * 2.5f
                val topLeft = Offset(subjectCenterX - boxSize / 2f, headCenterY - boxSize / 2f)
                drawRoundRect(
                    color = NeonLime.copy(alpha = 0.85f),
                    topLeft = topLeft,
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
                    )
                )
            }

            // Live kinetic audio equalizer bars
            val barCount = if (compactThumbnail) 12 else 22
            val barSpacing = w * 0.62f / barCount
            val startX = w * 0.19f
            val baseBarY = if (compactThumbnail) h * 0.84f else h * 0.60f
            for (i in 0 until barCount) {
                val wave = if (isPlaying) {
                    abs(sin(phase * 1.6f + i * 0.55f))
                } else {
                    0.22f + 0.15f * abs(sin(i * 0.7f))
                }
                val barH = (if (compactThumbnail) 16f else 28f) * (0.25f + wave * 0.85f)
                val barColor = if (i % 3 == 0) NeonLime else if (i % 2 == 0) NeonCyan else NeonMagenta
                drawRoundRect(
                    color = barColor.copy(alpha = 0.72f),
                    topLeft = Offset(startX + i * barSpacing, baseBarY - barH / 2f),
                    size = Size(barSpacing * 0.55f, barH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }

        if (!compactThumbnail) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(CharcoalBg.copy(alpha = 0.75f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) NeonLime else Color(0xFF94A3B8))
                )
                Text(
                    text = "DEMO CANVAS • ${formatTimePreciseMs(relativePositionMs)} / ${formatTimePreciseMs(clipDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            if (autoFaceTrackEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(NeonLime.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "FACE LOCK",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonLime
                    )
                }
            }
        }
    }
}
