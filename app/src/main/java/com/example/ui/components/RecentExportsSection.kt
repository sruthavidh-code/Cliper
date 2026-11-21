package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.ExportResolution
import com.example.model.ExportedClipRecord
import com.example.model.VideoFilterPreset
import com.example.model.formatTimeMs
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ExportVaultFilter(val label: String) {
    ALL("All"),
    HD_1080P("1080p HD"),
    FAST_720P("720p Fast"),
    FILTERED("Color Graded")
}

@Composable
fun RecentExportsCarouselSection(
    exports: List<ExportedClipRecord>,
    onPlayExport: (ExportedClipRecord) -> Unit,
    onShareExport: (ExportedClipRecord) -> Unit,
    onEditExport: (ExportedClipRecord) -> Unit,
    onDeleteExport: (ExportedClipRecord) -> Unit,
    onViewAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (exports.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("recent_exports_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Recent Exports",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = ElectricViolet.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "${exports.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricVioletLight,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            TextButton(
                onClick = onViewAllClick,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("view_all_exports_button")
            ) {
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.labelLarge,
                    color = NeonLime
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recent_exports_carousel")
        ) {
            items(exports.take(10), key = { it.id }) { record ->
                RecentExportCard(
                    record = record,
                    onPlay = { onPlayExport(record) },
                    onShare = { onShareExport(record) },
                    onEdit = { onEditExport(record) },
                    onDelete = { onDeleteExport(record) }
                )
            }
        }
    }
}

@Composable
private fun RecentExportCard(
    record: ExportedClipRecord,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val canEdit = record.projectId != null && record.clipId != null

    Surface(
        modifier = Modifier
            .width(172.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPlay)
            .testTag("recent_export_card_${record.id}"),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 9:16 portrait preview thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .background(SurfaceElevated)
            ) {
                ExportThumbnailVisual(
                    record = record,
                    modifier = Modifier.fillMaxSize()
                )

                // Soft bottom scrim for legible badges
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, CharcoalBg.copy(alpha = 0.88f))
                            )
                        )
                )

                // Top-left resolution & Demo pill
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val resShort = if (record.resolution == ExportResolution.HD_1080P) "1080p" else "720p"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CharcoalBg.copy(alpha = 0.8f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = resShort,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = NeonLime
                        )
                    }

                    if (record.isDemo) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScoreAmber.copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "DEMO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ScoreAmber
                            )
                        }
                    }
                }

                // Top-right filter pill if color-graded
                if (record.videoFilter != VideoFilterPreset.NONE) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ElectricViolet.copy(alpha = 0.85f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = record.videoFilter.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Centered translucent Play button indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(CharcoalBg.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play exported clip ${record.title}",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Bottom-left aspect ratio & bottom-right duration
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CharcoalBg.copy(alpha = 0.78f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = record.aspectRatio.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CharcoalBg.copy(alpha = 0.82f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatTimeMs(record.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            // Card details & quick action row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val sizeText = when {
                    record.fileSizeBytes > 0L -> formatFileSizeCompact(record.fileSizeBytes)
                    record.isDemo -> "Demo"
                    else -> "MP4"
                }
                Text(
                    text = "${formatRelativeExportTime(record.exportedAtMs)} • $sizeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("share_export_${record.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share ${record.title}",
                        tint = NeonLime,
                        modifier = Modifier.size(19.dp)
                    )
                }

                if (canEdit) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("edit_export_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Re-edit ${record.title}",
                            tint = ElectricVioletLight,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_export_${record.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove ${record.title} from history",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportHistoryVaultSheet(
    exports: List<ExportedClipRecord>,
    onPlayExport: (ExportedClipRecord) -> Unit,
    onShareExport: (ExportedClipRecord) -> Unit,
    onEditExport: (ExportedClipRecord) -> Unit,
    onDeleteExport: (ExportedClipRecord) -> Unit,
    onClearAllExports: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(ExportVaultFilter.ALL) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val filteredExports = remember(exports, selectedFilter) {
        when (selectedFilter) {
            ExportVaultFilter.ALL -> exports
            ExportVaultFilter.HD_1080P -> exports.filter { it.resolution == ExportResolution.HD_1080P }
            ExportVaultFilter.FAST_720P -> exports.filter { it.resolution == ExportResolution.FAST_720P }
            ExportVaultFilter.FILTERED -> exports.filter { it.videoFilter != VideoFilterPreset.NONE }
        }
    }

    val totalBytes = remember(exports) { exports.sumOf { it.fileSizeBytes.coerceAtLeast(0L) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("export_history_vault_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(BorderHairline)
        )

        // Sheet header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ElectricViolet.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = ElectricVioletLight
                    )
                }
                Column {
                    Text(
                        text = "Export History",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    val storageLabel = if (totalBytes > 0L) {
                        " • ${formatFileSizeCompact(totalBytes)} total"
                    } else {
                        ""
                    }
                    Text(
                        text = "${exports.size} saved ${if (exports.size == 1) "export" else "exports"}$storageLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (exports.isNotEmpty()) {
                TextButton(
                    onClick = { showClearConfirm = !showClearConfirm },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clear_all_exports_button")
                ) {
                    Text(
                        text = "Clear All",
                        style = MaterialTheme.typography.labelMedium,
                        color = ScoreCoral
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("close_export_vault_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close Export History",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Inline Clear-All confirmation card
        AnimatedVisibility(
            visible = showClearConfirm && exports.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ScoreCoral.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ScoreCoral.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Clear all ${exports.size} entries from Export History?",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "This removes local history records and cached thumbnails only. Your exported MP4 files in Movies/ClipPulse stay untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showClearConfirm = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("cancel_clear_exports_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text("Keep History", color = Color.White)
                        }
                        Button(
                            onClick = {
                                showClearConfirm = false
                                onClearAllExports()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("confirm_clear_exports_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ScoreCoral)
                        ) {
                            Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExportVaultFilter.entries.forEach { filter ->
                val selected = filter == selectedFilter
                FilterChip(
                    selected = selected,
                    onClick = { selectedFilter = filter },
                    label = {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.testTag("export_filter_${filter.name.lowercase()}"),
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

        if (filteredExports.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SurfaceElevated,
                border = BorderStroke(1.dp, BorderHairline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = if (exports.isEmpty()) "No exports yet" else "No exports match this filter",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = if (exports.isEmpty()) {
                            "Render a clip in the Clip Editor and it will appear here with its thumbnail and render stats."
                        } else {
                            "Try selecting All to view every saved export."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .testTag("export_vault_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(filteredExports, key = { it.id }) { record ->
                    ExportVaultListRow(
                        record = record,
                        onPlay = { onPlayExport(record) },
                        onShare = { onShareExport(record) },
                        onEdit = { onEditExport(record) },
                        onDelete = { onDeleteExport(record) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportVaultListRow(
    record: ExportedClipRecord,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val canEdit = record.projectId != null && record.clipId != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .testTag("vault_export_row_${record.id}"),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, BorderHairline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Portrait thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CharcoalBg)
                ) {
                    ExportThumbnailVisual(
                        record = record,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CharcoalBg.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play ${record.title}",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(CharcoalBg.copy(alpha = 0.85f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = formatTimeMs(record.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Metadata stack
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetadataPill(
                            text = record.resolution.label,
                            containerColor = NeonLime.copy(alpha = 0.16f),
                            contentColor = NeonLime
                        )
                        MetadataPill(
                            text = record.aspectRatio.shortLabel,
                            containerColor = NeonCyan.copy(alpha = 0.16f),
                            contentColor = NeonCyan
                        )
                        if (record.videoFilter != VideoFilterPreset.NONE) {
                            MetadataPill(
                                text = record.videoFilter.shortLabel,
                                containerColor = ElectricViolet.copy(alpha = 0.28f),
                                contentColor = ElectricVioletLight
                            )
                        }
                        if (record.isDemo) {
                            MetadataPill(
                                text = "DEMO",
                                containerColor = ScoreAmber.copy(alpha = 0.2f),
                                contentColor = ScoreAmber
                            )
                        }
                    }

                    val detailsLine = buildString {
                        append(record.captionStyle.displayName)
                        if (record.fileSizeBytes > 0L) {
                            append(" • ")
                            append(formatFileSizeCompact(record.fileSizeBytes))
                        }
                        if (record.renderDurationMs > 0L) {
                            append(" • Render ${formatDurationClock(record.renderDurationMs)}")
                        }
                    }
                    Text(
                        text = detailsLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "Exported ${formatRelativeExportTime(record.exportedAtMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action bar with full 48dp touch targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("vault_play_${record.id}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonLime)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = CharcoalBg,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (record.isDemo) "Preview" else "Play",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CharcoalBg,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("vault_share_${record.id}"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Share",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                if (canEdit) {
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onEdit)
                            .testTag("vault_edit_${record.id}"),
                        shape = RoundedCornerShape(12.dp),
                        color = ElectricViolet.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.45f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.AutoFixHigh,
                                contentDescription = "Open ${record.title} in Clip Editor",
                                tint = ElectricVioletLight,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDelete)
                        .testTag("vault_delete_${record.id}"),
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceCard,
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Remove ${record.title} from history",
                            tint = ScoreCoral,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportThumbnailVisual(
    record: ExportedClipRecord,
    modifier: Modifier = Modifier
) {
    val thumbFile = remember(record.thumbnailPath) {
        record.thumbnailPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }
    }

    if (thumbFile != null) {
        AsyncImage(
            model = thumbFile,
            contentDescription = "Export thumbnail for ${record.title}",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        DemoPreviewCanvas(
            relativePositionMs = (record.durationMs / 4L).coerceAtLeast(1_500L),
            clipDurationMs = record.durationMs.coerceAtLeast(1_000L),
            isPlaying = false,
            panX = 0f,
            zoomScale = 1f,
            autoFaceTrackEnabled = false,
            videoFilter = record.videoFilter,
            clipSeed = record.id.toInt(),
            compactThumbnail = true,
            modifier = modifier
        )
    }
}

@Composable
private fun MetadataPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

fun formatFileSizeCompact(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return "${kb.toInt().coerceAtLeast(1)} KB"
    }
    val mb = kb / 1024.0
    return if (mb < 100.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

fun formatRelativeExportTime(exportedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMs = (nowMs - exportedAtMs).coerceAtLeast(0L)
    val minutes = diffMs / 60_000L
    val hours = diffMs / 3_600_000L
    val days = diffMs / 86_400_000L
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7L -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(exportedAtMs))
    }
}
