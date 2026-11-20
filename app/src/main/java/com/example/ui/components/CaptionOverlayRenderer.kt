package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CaptionYellow
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.MontserratFontFamily
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import kotlin.math.roundToInt

@Composable
fun LiveCaptionOverlay(
    captions: List<CaptionChunk>,
    relativePositionMs: Long,
    style: CaptionStylePreset,
    yPositionFraction: Float,
    uppercase: Boolean,
    modifier: Modifier = Modifier
) {
    if (style == CaptionStylePreset.NO_CAPTIONS || captions.isEmpty()) return

    val activeChunk = captions.firstOrNull { relativePositionMs in it.startMs..it.endMs }
        ?: captions.firstOrNull()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        val yOffsetPx = (heightPx * yPositionFraction.coerceIn(0.18f, 0.86f) - heightPx * 0.5f).roundToInt()

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(0, yOffsetPx) }
                .padding(horizontal = 16.dp)
        ) {
            AnimatedContent(
                targetState = activeChunk,
                transitionSpec = {
                    (scaleIn(
                        initialScale = 0.90f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn()) togetherWith fadeOut()
                },
                label = "caption_chunk_anim"
            ) { chunk ->
                if (chunk != null) {
                    RenderedCaptionLine(
                        text = if (uppercase) chunk.text.uppercase() else chunk.text,
                        highlightWord = chunk.highlightWord,
                        style = style,
                        compact = false
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderedCaptionLine(
    text: String,
    highlightWord: String,
    style: CaptionStylePreset,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val words = text.split(" ").filter { it.isNotBlank() }
    val normalizedHighlight = highlightWord
        .trim()
        .replace(Regex("[^A-Za-z0-9]"), "")
        .lowercase()

    when (style) {
        CaptionStylePreset.HORMOZI_BOLD -> {
            val fontSize = if (compact) 13.sp else 21.sp
            val strokeWidth = if (compact) 6f else 11f
            FlowRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                words.forEach { word ->
                    val isHit = isWordHighlighted(word, normalizedHighlight)
                    OutlinedCaptionWord(
                        word = word,
                        fillColor = if (isHit) NeonLime else Color.White,
                        strokeColor = Color.Black,
                        fontSizeSp = fontSize.value,
                        strokeWidthPx = strokeWidth,
                        scaleUp = isHit && !compact
                    )
                }
            }
        }

        CaptionStylePreset.NEON_POP -> {
            val fontSize = if (compact) 12.sp else 18.sp
            FlowRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                words.forEach { word ->
                    val isHit = isWordHighlighted(word, normalizedHighlight)
                    val boxModifier = if (isHit) {
                        Modifier
                            .clip(RoundedCornerShape(if (compact) 5.dp else 8.dp))
                            .background(
                                Brush.linearGradient(listOf(NeonCyan, NeonMagenta))
                            )
                            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 1.dp else 3.dp)
                    } else {
                        Modifier.padding(horizontal = 2.dp, vertical = if (compact) 1.dp else 3.dp)
                    }
                    Box(modifier = boxModifier) {
                        Text(
                            text = word,
                            style = TextStyle(
                                fontFamily = MontserratFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = fontSize,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }

        CaptionStylePreset.MINIMAL_CLEAN -> {
            val fontSize = if (compact) 11.sp else 15.sp
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(CharcoalBg.copy(alpha = 0.80f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                    .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 4.dp else 7.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    words.forEach { word ->
                        val isHit = isWordHighlighted(word, normalizedHighlight)
                        Text(
                            text = word,
                            style = TextStyle(
                                fontFamily = InterFontFamily,
                                fontWeight = if (isHit) FontWeight.Bold else FontWeight.Medium,
                                fontSize = fontSize,
                                color = if (isHit) CaptionYellow else Color.White
                            )
                        )
                    }
                }
            }
        }

        CaptionStylePreset.NO_CAPTIONS -> {
            if (compact) {
                Text(
                    text = "Clean Frame",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OutlinedCaptionWord(
    word: String,
    fillColor: Color,
    strokeColor: Color,
    fontSizeSp: Float,
    strokeWidthPx: Float,
    scaleUp: Boolean
) {
    val size = if (scaleUp) (fontSizeSp * 1.12f).sp else fontSizeSp.sp
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = word,
            style = TextStyle(
                fontFamily = MontserratFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = size,
                color = strokeColor,
                drawStyle = Stroke(width = strokeWidthPx, join = StrokeJoin.Round)
            )
        )
        Text(
            text = word,
            style = TextStyle(
                fontFamily = MontserratFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = size,
                color = fillColor
            )
        )
    }
}

@Composable
fun CaptionStylePresetCard(
    preset: CaptionStylePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) ElectricViolet else BorderHairline
    val bgColor = if (selected) ElectricViolet.copy(alpha = 0.16f) else SurfaceElevated

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("caption_preset_${preset.name.lowercase()}"),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1F2438), SurfaceCard)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                RenderedCaptionLine(
                    text = if (preset == CaptionStylePreset.HORMOZI_BOLD) "VIRAL HOOK" else "Viral Hook",
                    highlightWord = "Viral",
                    style = preset,
                    compact = true
                )
            }
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

private fun isWordHighlighted(word: String, normalizedHighlight: String): Boolean {
    if (normalizedHighlight.isEmpty()) return false
    val cleanWord = word.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
    return cleanWord == normalizedHighlight || cleanWord.contains(normalizedHighlight)
}
