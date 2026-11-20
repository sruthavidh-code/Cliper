package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.media.YouTubeSupport
import com.example.model.ExportResolution
import com.example.model.ViralClip
import com.example.model.formatTimeMs
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletLight
import com.example.ui.theme.NeonLime
import com.example.ui.theme.ScoreAmber
import com.example.ui.theme.ScoreCoral
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.viewmodel.ExportUiState

@Composable
fun ExportPipelineDialog(
    state: ExportUiState,
    onSelectResolution: (ExportResolution) -> Unit,
    onStartExport: () -> Unit,
    onAttachLocalVideo: (Uri, ViralClip) -> Unit,
    onCancelOrDismiss: () -> Unit,
    onBackToClips: () -> Unit
) {
    if (state is ExportUiState.Hidden) return

    val context = LocalContext.current
    val activeYtClip = (state as? ExportUiState.YouTubeOptions)?.clip

    val attachVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && activeYtClip != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            onAttachLocalVideo(uri, activeYtClip)
        }
    }

    Dialog(
        onDismissRequest = {
            if (state !is ExportUiState.Exporting) onCancelOrDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = state !is ExportUiState.Exporting,
            dismissOnClickOutside = state !is ExportUiState.Exporting
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = SurfaceCard,
            border = BorderStroke(1.dp, BorderHairline)
        ) {
            when (state) {
                is ExportUiState.YouTubeOptions -> {
                    val timestampedUrl = YouTubeSupport.buildTimestampedShareUrl(
                        videoId = state.youtubeVideoId,
                        startMs = state.clip.startMs
                    )
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
                                        .background(ScoreCoral.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = "YouTube Clip Export",
                                        tint = ScoreCoral
                                    )
                                }
                                Column {
                                    Text(
                                        text = "YouTube Clip Export",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${formatTimeMs(state.clip.startMs)}–${formatTimeMs(state.clip.endMs)} • ${state.clip.captions.size} caption lines",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = onCancelOrDismiss,
                                modifier = Modifier.testTag("export_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close export dialog",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = SurfaceElevated,
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text(
                                text = "Attach your local video copy to unlock hardware Media3 MP4 rendering (720p/1080p) with burned captions—or export .SRT subtitles and a timestamped YouTube link immediately.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        // 1. Attach Local Video for MP4 Export
                        Button(
                            onClick = {
                                attachVideoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            enabled = !state.isAttachingLocalVideo,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("yt_attach_local_video_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonLime)
                        ) {
                            if (state.isAttachingLocalVideo) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = CharcoalBg
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Linking Local Video...", color = CharcoalBg, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.VideoLibrary,
                                    contentDescription = null,
                                    tint = CharcoalBg
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Attach Local Video for MP4",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = CharcoalBg
                                )
                            }
                        }

                        // 2. Export .SRT Captions + Copy SRT
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    shareSrtSubtitles(
                                        context = context,
                                        clip = state.clip
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("yt_export_srt_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Subtitles,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Export .SRT Captions", color = Color.White)
                            }

                            Surface(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("ClipPulse SRT", state.srtContent)
                                        )
                                        Toast.makeText(context, "SRT captions copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                    .testTag("yt_copy_srt_button"),
                                shape = RoundedCornerShape(14.dp),
                                color = SurfaceElevated,
                                border = BorderStroke(1.dp, BorderHairline)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy SRT captions",
                                        tint = ElectricVioletLight
                                    )
                                }
                            }
                        }

                        // 3. Share Timestamped YouTube Link + Open on YouTube
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    shareTimestampedYouTubeLink(
                                        context = context,
                                        title = state.clip.title,
                                        timestampedUrl = timestampedUrl,
                                        hashtags = state.clip.hashtags
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("yt_share_timestamp_link_button"),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, BorderHairline)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share Timestamp Link", color = Color.White, maxLines = 1)
                            }

                            Surface(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(timestampedUrl))
                                            )
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Unable to open YouTube link.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .testTag("yt_open_timestamp_button"),
                                shape = RoundedCornerShape(14.dp),
                                color = SurfaceElevated,
                                border = BorderStroke(1.dp, BorderHairline)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = "Open at timestamp on YouTube",
                                        tint = ScoreCoral
                                    )
                                }
                            }
                        }
                    }
                }

                is ExportUiState.Configure -> {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                        .background(ElectricViolet.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MovieCreation,
                                        contentDescription = "Export MP4",
                                        tint = ElectricViolet
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Export MP4",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${state.clip.aspectRatio.shortLabel} • ${formatTimeMs(state.clip.clipDurationMs)} • ${state.clip.captionStyle.displayName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = onCancelOrDismiss,
                                modifier = Modifier.testTag("export_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close export dialog",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (state.isDemoMode) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ScoreAmber.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, ScoreAmber.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "Demo Mode runs an animated preview canvas without saving a file to gallery. Import any real video on the Dashboard for real Media3 MP4 export to Movies/ClipPulse.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ScoreAmber,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Text(
                            text = "Output Resolution",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ExportResolution.entries.forEach { res ->
                                val selected = res == state.resolution
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onSelectResolution(res) }
                                        .testTag("resolution_${res.name.lowercase()}"),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) ElectricViolet.copy(alpha = 0.18f) else SurfaceElevated,
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) ElectricViolet else BorderHairline
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = res.label,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color.White
                                            )
                                            Text(
                                                text = res.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = "Selected resolution",
                                                tint = NeonLime
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = onStartExport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("start_export_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                        ) {
                            Text(
                                text = if (state.isDemoMode) "Preview Export Pipeline" else "Start MP4 Export",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }

                is ExportUiState.Exporting -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Rendering MP4 (${state.resolution.label})",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Trimming clip, applying ${state.clip.aspectRatio.shortLabel} crop & burning ${state.clip.captionStyle.displayName} captions...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${state.progressPercent}%",
                            style = MaterialTheme.typography.displaySmall,
                            color = NeonLime
                        )
                        LinearProgressIndicator(
                            progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = NeonLime,
                            trackColor = SurfaceElevated
                        )
                        TextButton(
                            onClick = onCancelOrDismiss,
                            modifier = Modifier.testTag("cancel_export_button")
                        ) {
                            Text("Cancel Export", color = ScoreCoral)
                        }
                    }
                }

                is ExportUiState.DemoPreview -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!state.isComplete) {
                            Text(
                                text = "Demo Export Preview",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "${state.progressPercent}%",
                                style = MaterialTheme.typography.displaySmall,
                                color = NeonLime
                            )
                            LinearProgressIndicator(
                                progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = NeonLime,
                                trackColor = SurfaceElevated
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Demo export preview complete",
                                tint = NeonLime,
                                modifier = Modifier.size(52.dp)
                            )
                            Text(
                                text = "Demo Preview Complete",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Your ${state.resolution.label} (${state.clip.aspectRatio.shortLabel}) settings are ready. Import a real video from the Studio Dashboard to render and save a gallery MP4 to Movies/ClipPulse.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = onBackToClips,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("demo_export_back_clips"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                            ) {
                                Text("Back to Clips", color = Color.White)
                            }
                            TextButton(onClick = onCancelOrDismiss) {
                                Text("Keep Editing", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                is ExportUiState.Completed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Export succeeded",
                            tint = NeonLime,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Saved to Movies/ClipPulse",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = state.clip.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { openExportedVideo(context, state.exportedUri) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("open_exported_video_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonLime)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = CharcoalBg
                            )
                            Text(
                                text = "Open Video",
                                color = CharcoalBg,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        Button(
                            onClick = { shareExportedVideo(context, state.exportedUri, state.clip.title) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("share_exported_video_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Text(
                                text = "Share to Reels / Shorts / TikTok",
                                color = Color.White,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = onBackToClips,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("export_back_to_clips_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text("Back to Clips", color = Color.White)
                        }
                    }
                }

                is ExportUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = "Export error",
                            tint = ScoreCoral,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Export Failed",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                onSelectResolution(ExportResolution.FAST_720P)
                                onStartExport()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("retry_export_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                        ) {
                            Text("Try Again", color = Color.White)
                        }
                        TextButton(onClick = onCancelOrDismiss) {
                            Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                ExportUiState.Hidden -> Unit
            }
        }
    }
}

fun openExportedVideo(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open ClipPulse Video"))
    } catch (_: Exception) {
        Toast.makeText(context, "No video player found to open MP4.", Toast.LENGTH_SHORT).show()
    }
}

fun shareExportedVideo(context: Context, uri: Uri, title: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title — Made with ClipPulse")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share to Reels, Shorts or TikTok"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to open share sheet.", Toast.LENGTH_SHORT).show()
    }
}

fun shareSrtSubtitles(context: Context, clip: ViralClip) {
    try {
        val srtUri = YouTubeSupport.createShareableSrtUri(
            context = context,
            clipTitle = clip.title,
            captions = clip.captions,
            uppercase = clip.captionUppercase
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, srtUri)
            putExtra(Intent.EXTRA_SUBJECT, "${clip.title}.srt")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export .SRT Subtitles"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to export SRT captions.", Toast.LENGTH_SHORT).show()
    }
}

fun shareTimestampedYouTubeLink(
    context: Context,
    title: String,
    timestampedUrl: String,
    hashtags: List<String>
) {
    try {
        val body = buildString {
            appendLine(title)
            appendLine(timestampedUrl)
            if (hashtags.isNotEmpty()) {
                appendLine()
                append(hashtags.joinToString(" "))
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Share Timestamped YouTube Clip"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share link.", Toast.LENGTH_SHORT).show()
    }
}

fun shareClipMetadataText(context: Context, title: String, hashtags: List<String>) {
    try {
        val body = buildString {
            appendLine(title)
            if (hashtags.isNotEmpty()) {
                appendLine()
                append(hashtags.joinToString(" "))
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Share Clip Details"))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share clip.", Toast.LENGTH_SHORT).show()
    }
}
