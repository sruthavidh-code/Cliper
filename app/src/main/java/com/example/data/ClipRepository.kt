package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.local.ClipPulseDao
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.media.YouTubeVideoMetadata
import com.example.model.ClipConfig
import com.example.model.ExportResolution
import com.example.model.ExportedClipRecord
import com.example.model.ProjectStatus
import com.example.model.VideoProject
import com.example.model.ViralClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ClipRepository"

data class ImportedVideoMetadata(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val thumbnailPath: String?
)

class ClipRepository(
    private val context: Context,
    private val dao: ClipPulseDao
) {
    val allProjects: Flow<List<VideoProject>> = dao.observeAllProjects()
        .map { list -> list.map { it.toDomain() } }

    val totalClipCount: Flow<Int> = dao.observeTotalClipCount()

    val allExports: Flow<List<ExportedClipRecord>> = dao.observeAllExports()
        .map { list -> list.map { it.toDomain() } }

    val totalExportCount: Flow<Int> = dao.observeTotalExportCount()

    fun observeProject(projectId: Long): Flow<VideoProject?> =
        dao.observeProject(projectId).map { it?.toDomain() }

    fun observeClipsForProject(projectId: Long): Flow<List<ViralClip>> =
        dao.observeClipsForProject(projectId).map { list -> list.map { it.toDomain() } }

    fun observeClip(clipId: Long): Flow<ViralClip?> =
        dao.observeClipById(clipId).map { it?.toDomain() }

    suspend fun getProject(projectId: Long): VideoProject? =
        dao.getProjectById(projectId)?.toDomain()

    suspend fun getClip(clipId: Long): ViralClip? =
        dao.getClipById(clipId)?.toDomain()

    suspend fun createProjectFromImport(
        metadata: ImportedVideoMetadata,
        config: ClipConfig
    ): Long {
        val project = VideoProject(
            title = metadata.displayName,
            sourceUri = metadata.uri.toString(),
            thumbnailPath = metadata.thumbnailPath,
            durationMs = metadata.durationMs,
            sourceWidth = metadata.width,
            sourceHeight = metadata.height,
            targetDuration = config.targetDuration,
            aspectRatio = config.aspectRatio,
            defaultCaptionStyle = config.captionStyle,
            focusKeywords = config.focusKeywords.trim(),
            status = ProjectStatus.ANALYZING,
            clipCount = 0,
            isDemo = false
        )
        return dao.insertProject(project.toEntity())
    }

    suspend fun createProjectFromYouTube(
        metadata: YouTubeVideoMetadata,
        config: ClipConfig
    ): Long {
        val project = VideoProject(
            title = metadata.title,
            sourceUri = "",
            youtubeUrl = metadata.canonicalUrl,
            youtubeVideoId = metadata.videoId,
            thumbnailPath = metadata.thumbnailUrl,
            durationMs = 600_000L, // Refined automatically from Gemini analysis
            sourceWidth = 1920,
            sourceHeight = 1080,
            targetDuration = config.targetDuration,
            aspectRatio = config.aspectRatio,
            defaultCaptionStyle = config.captionStyle,
            focusKeywords = config.focusKeywords.trim(),
            status = ProjectStatus.ANALYZING,
            clipCount = 0,
            isDemo = false
        )
        return dao.insertProject(project.toEntity())
    }

    suspend fun updateProjectDuration(projectId: Long, durationMs: Long) {
        val existing = dao.getProjectById(projectId) ?: return
        if (durationMs > 1_000L) {
            dao.updateProject(existing.copy(durationMs = durationMs))
        }
    }

    suspend fun attachLocalVideoToProject(projectId: Long, localUri: Uri): VideoProject? {
        val existing = dao.getProjectById(projectId) ?: return null
        val metadata = extractVideoMetadata(localUri)
        val updated = existing.copy(
            sourceUri = localUri.toString(),
            durationMs = metadata.durationMs,
            sourceWidth = metadata.width,
            sourceHeight = metadata.height,
            thumbnailPath = metadata.thumbnailPath ?: existing.thumbnailPath
        )
        dao.updateProject(updated)
        return updated.toDomain()
    }

    suspend fun saveGeneratedClips(projectId: Long, clips: List<ViralClip>) {
        dao.deleteClipsForProject(projectId)
        dao.insertClips(clips.map { it.copy(projectId = projectId).toEntity() })
        val existing = dao.getProjectById(projectId) ?: return
        dao.updateProject(
            existing.copy(
                status = ProjectStatus.READY.name,
                clipCount = clips.size
            )
        )
    }

    suspend fun updateProjectStatus(projectId: Long, status: ProjectStatus) {
        val existing = dao.getProjectById(projectId) ?: return
        dao.updateProject(existing.copy(status = status.name))
    }

    suspend fun updateClip(clip: ViralClip) {
        dao.updateClip(clip.toEntity())
    }

    suspend fun deleteProject(projectId: Long) {
        dao.deleteProject(projectId)
    }

    /**
     * Persists export metadata & extracts a crisp JPEG thumbnail from the rendered MP4
     * into app-private storage (zero broad storage permissions required).
     */
    suspend fun recordExportedClip(
        projectId: Long,
        clip: ViralClip,
        exportedUri: String,
        resolution: ExportResolution,
        renderDurationMs: Long,
        isDemo: Boolean
    ): ExportedClipRecord = withContext(Dispatchers.IO) {
        var fileSizeBytes = 0L
        var thumbnailPath: String? = null

        if (!isDemo && exportedUri.isNotBlank()) {
            val uri = Uri.parse(exportedUri)
            fileSizeBytes = queryExportFileSize(uri)
            thumbnailPath = extractExportThumbnail(uri, clip.clipDurationMs)
        }

        val record = ExportedClipRecord(
            projectId = projectId,
            clipId = clip.id,
            title = clip.title,
            exportedUri = exportedUri,
            thumbnailPath = thumbnailPath,
            durationMs = clip.clipDurationMs,
            fileSizeBytes = fileSizeBytes,
            resolution = resolution,
            aspectRatio = clip.aspectRatio,
            captionStyle = clip.captionStyle,
            videoFilter = clip.videoFilter,
            renderDurationMs = renderDurationMs.coerceAtLeast(0L),
            isDemo = isDemo,
            exportedAtMs = System.currentTimeMillis()
        )
        val insertedId = dao.insertExport(record.toEntity())
        record.copy(id = insertedId)
    }

    suspend fun deleteExport(exportId: Long) = withContext(Dispatchers.IO) {
        val existing = dao.getExportById(exportId)
        existing?.thumbnailPath?.let { path ->
            runCatching { File(path).delete() }
        }
        dao.deleteExportById(exportId)
    }

    suspend fun clearAllExports() = withContext(Dispatchers.IO) {
        runCatching {
            File(context.filesDir, "export_thumbs").listFiles()?.forEach { it.delete() }
        }
        dao.clearAllExports()
    }

    private fun queryExportFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) afd.length else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun extractExportThumbnail(uri: Uri, durationMs: Long): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // Sample ~25% into the exported clip so burned captions & color grading are visible
            val sampleMs = (durationMs * 0.25).toLong().coerceIn(400L, 4_000L)
            val frame = retriever.getFrameAtTime(
                sampleMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (frame != null) {
                val scaled = scaleThumbnailBitmap(frame, maxEdgePx = 420)
                val thumbDir = File(context.filesDir, "export_thumbs").apply { mkdirs() }
                val outFile = File(thumbDir, "export_${System.currentTimeMillis()}.jpg")
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 84, fos)
                }
                if (scaled !== frame) {
                    scaled.recycle()
                }
                frame.recycle()
                outFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to extract thumbnail from exported MP4", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaleThumbnailBitmap(source: Bitmap, maxEdgePx: Int): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= maxEdgePx) return source
        val scale = maxEdgePx.toFloat() / maxSide.toFloat()
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    suspend fun ensureDemoProject(): Long {
        val existing = dao.getDemoProject()
        if (existing != null) {
            return existing.id
        }
        val demoProjectId = dao.insertProject(DemoProjectSeeder.createDemoProject().toEntity())
        val demoClips = DemoProjectSeeder.createDemoClips(demoProjectId)
        dao.insertClips(demoClips.map { it.toEntity() })
        return demoProjectId
    }

    suspend fun extractVideoMetadata(uri: Uri): ImportedVideoMetadata = withContext(Dispatchers.IO) {
        var displayName = "Imported Video"
        var fileSizeBytes = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val rawName = cursor.getString(nameIdx)
                        if (!rawName.isNullOrBlank()) {
                            displayName = rawName.substringBeforeLast('.')
                        }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        fileSizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query display name/size from ContentResolver", e)
        }

        var durationMs = 60_000L
        var width = 1920
        var height = 1080
        var thumbnailPath: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1_000L) ?: 60_000L

            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1920
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            if (rotation == 90 || rotation == 270) {
                width = rawHeight
                height = rawWidth
            } else {
                width = rawWidth
                height = rawHeight
            }

            val thumbTimeUs = (durationMs.coerceAtMost(3_000L) * 1000L) / 2L
            val frame = retriever.getFrameAtTime(thumbTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                val thumbDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
                val outFile = File(thumbDir, "thumb_${System.currentTimeMillis()}.jpg")
                FileOutputStream(outFile).use { fos ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                }
                frame.recycle()
                thumbnailPath = outFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video metadata", e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        ImportedVideoMetadata(
            uri = uri,
            displayName = displayName,
            durationMs = durationMs,
            width = width,
            height = height,
            fileSizeBytes = fileSizeBytes,
            thumbnailPath = thumbnailPath
        )
    }
}
