package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.NeonLime
import com.example.ui.theme.ScoreCoral
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.viewmodel.AnalysisUiState
import com.example.ui.viewmodel.AnalysisViewModel

private val ANALYSIS_STAGES = listOf(
    "Extracting audio",
    "Analyzing with Gemini",
    "Ranking",
    "Framing"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisProgressScreen(
    viewModel: AnalysisViewModel,
    onNavigateBack: () -> Unit,
    onAnalysisCompleted: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        val done = uiState as? AnalysisUiState.Completed
        if (done != null) {
            onAnalysisCompleted(done.projectId)
        }
    }

    Scaffold(
        containerColor = CharcoalBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI Clip Pipeline",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("analysis_back_button")
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
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                when (val state = uiState) {
                    is AnalysisUiState.Idle,
                    is AnalysisUiState.Running -> {
                        val running = state as? AnalysisUiState.Running
                        val activeIdx = running?.activeStageIndex ?: 0

                        PulsingStudioOrb()

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = running?.projectTitle ?: "Preparing Studio...",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = running?.stageSubtitle ?: "Initializing analysis...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ElectricVioletLight,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (running?.proxyProgressPercent != null) {
                            LinearProgressIndicator(
                                progress = { (running.proxyProgressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = NeonLime,
                                trackColor = SurfaceElevated
                            )
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            color = SurfaceCard,
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                ANALYSIS_STAGES.forEachIndexed { index, stageLabel ->
                                    StageProgressRow(
                                        index = index,
                                        label = stageLabel,
                                        isCompleted = index < activeIdx,
                                        isActive = index == activeIdx
                                    )
                                }
                            }
                        }
                    }

                    is AnalysisUiState.Error -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("analysis_error_card"),
                            shape = RoundedCornerShape(24.dp),
                            color = SurfaceCard,
                            border = BorderStroke(1.dp, ScoreCoral.copy(alpha = 0.55f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(ScoreCoral.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = "AI Analysis failed",
                                        tint = ScoreCoral,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }

                                Text(
                                    text = "AI Analysis Interrupted",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = viewModel::retryAnalysis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("retry_ai_analysis_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Retry AI Analysis",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White
                                    )
                                }

                                OutlinedButton(
                                    onClick = onNavigateBack,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, BorderHairline)
                                ) {
                                    Text("Back to Studio", color = Color.White)
                                }
                            }
                        }
                    }

                    is AnalysisUiState.Completed -> {
                        CircularProgressIndicator(color = NeonLime)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingStudioOrb() {
    val transition = rememberInfiniteTransition(label = "ai_orb_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    Box(
        modifier = Modifier
            .size(92.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(ElectricViolet.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(ElectricViolet),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = "Gemini AI analyzing video",
                tint = NeonLime,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun StageProgressRow(
    index: Int,
    label: String,
    isCompleted: Boolean,
    isActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCompleted -> {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Stage completed",
                        tint = NeonLime,
                        modifier = Modifier.size(26.dp)
                    )
                }

                isActive -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.6.dp,
                        color = ElectricVioletLight
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                isCompleted -> NeonLime
                isActive -> Color.White
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
