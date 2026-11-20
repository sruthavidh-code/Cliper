package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.AspectRatioOption
import com.example.model.CaptionStylePreset
import com.example.model.ClipJsonCodec
import com.example.model.ProjectStatus
import com.example.model.TargetDuration
import com.example.model.VideoProject
import com.example.model.ViralClip

@Entity(tableName = "video_projects")
data class VideoProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val sourceUri: String,
    val youtubeUrl: String? = null,
    val youtubeVideoId: String? = null,
    val thumbnailPath: String?,
    val durationMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetDuration: String,
    val aspectRatio: String,
    val defaultCaptionStyle: String,
    val focusKeywords: String,
    val status: String,
    val clipCount: Int,
    val isDemo: Boolean,
    val createdAtMs: Long
)

@Entity(
    tableName = "viral_clips",
    foreignKeys = [
        ForeignKey(
            entity = VideoProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class ViralClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val viralityScore: Int,
    val hookScore: Int,
    val flowScore: Int,
    val engagementScore: Int,
    val reason: String,
    val hashtagsJson: String,
    val captionsJson: String,
    val aspectRatio: String,
    val autoFaceTrackEnabled: Boolean,
    val faceCenterX: Float,
    val manualPanX: Float,
    val zoomScale: Float,
    val captionStyle: String,
    val captionYPosition: Float,
    val captionUppercase: Boolean,
    val lastExportedUri: String?
)

fun VideoProjectEntity.toDomain(): VideoProject = VideoProject(
    id = id,
    title = title,
    sourceUri = sourceUri,
    youtubeUrl = youtubeUrl,
    youtubeVideoId = youtubeVideoId,
    thumbnailPath = thumbnailPath,
    durationMs = durationMs,
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
    targetDuration = TargetDuration.fromName(targetDuration),
    aspectRatio = AspectRatioOption.fromName(aspectRatio),
    defaultCaptionStyle = CaptionStylePreset.fromName(defaultCaptionStyle),
    focusKeywords = focusKeywords,
    status = ProjectStatus.fromName(status),
    clipCount = clipCount,
    isDemo = isDemo,
    createdAtMs = createdAtMs
)

fun VideoProject.toEntity(): VideoProjectEntity = VideoProjectEntity(
    id = id,
    title = title,
    sourceUri = sourceUri,
    youtubeUrl = youtubeUrl,
    youtubeVideoId = youtubeVideoId,
    thumbnailPath = thumbnailPath,
    durationMs = durationMs,
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
    targetDuration = targetDuration.name,
    aspectRatio = aspectRatio.name,
    defaultCaptionStyle = defaultCaptionStyle.name,
    focusKeywords = focusKeywords,
    status = status.name,
    clipCount = clipCount,
    isDemo = isDemo,
    createdAtMs = createdAtMs
)

fun ViralClipEntity.toDomain(): ViralClip = ViralClip(
    id = id,
    projectId = projectId,
    title = title,
    startMs = startMs,
    endMs = endMs,
    viralityScore = viralityScore,
    hookScore = hookScore,
    flowScore = flowScore,
    engagementScore = engagementScore,
    reason = reason,
    hashtags = ClipJsonCodec.decodeHashtags(hashtagsJson),
    captions = ClipJsonCodec.decodeCaptions(captionsJson),
    aspectRatio = AspectRatioOption.fromName(aspectRatio),
    autoFaceTrackEnabled = autoFaceTrackEnabled,
    faceCenterX = faceCenterX,
    manualPanX = manualPanX,
    zoomScale = zoomScale,
    captionStyle = CaptionStylePreset.fromName(captionStyle),
    captionYPosition = captionYPosition,
    captionUppercase = captionUppercase,
    lastExportedUri = lastExportedUri
)

fun ViralClip.toEntity(): ViralClipEntity = ViralClipEntity(
    id = id,
    projectId = projectId,
    title = title,
    startMs = startMs,
    endMs = endMs,
    viralityScore = viralityScore,
    hookScore = hookScore,
    flowScore = flowScore,
    engagementScore = engagementScore,
    reason = reason,
    hashtagsJson = ClipJsonCodec.encodeHashtags(hashtags),
    captionsJson = ClipJsonCodec.encodeCaptions(captions),
    aspectRatio = aspectRatio.name,
    autoFaceTrackEnabled = autoFaceTrackEnabled,
    faceCenterX = faceCenterX,
    manualPanX = manualPanX,
    zoomScale = zoomScale,
    captionStyle = captionStyle.name,
    captionYPosition = captionYPosition,
    captionUppercase = captionUppercase,
    lastExportedUri = lastExportedUri
)
