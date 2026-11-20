package com.example.model

import org.json.JSONArray
import org.json.JSONObject

const val DEMO_VIDEO_URI = "demo://clippulse_creator_podcast"

enum class TargetDuration(val label: String, val promptGuidance: String) {
    AUTO("Auto", "Choose the most natural viral duration between 20s and 60s per clip"),
    UNDER_30S("< 30s", "Each clip must be strictly between 15 and 30 seconds"),
    BETWEEN_30_60S("30–60s", "Each clip must be strictly between 30 and 60 seconds"),
    BETWEEN_60_90S("60–90s", "Each clip must be strictly between 60 and 90 seconds");

    companion object {
        fun fromName(name: String): TargetDuration =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

enum class AspectRatioOption(val label: String, val shortLabel: String, val ratio: Float) {
    VERTICAL_9_16("9:16 Vertical (Reels/Shorts/TikTok)", "9:16", 9f / 16f),
    SQUARE_1_1("1:1 Square", "1:1", 1f),
    ORIGINAL_16_9("16:9 Original", "16:9", 16f / 9f);

    companion object {
        fun fromName(name: String): AspectRatioOption =
            entries.firstOrNull { it.name == name } ?: VERTICAL_9_16
    }
}

enum class CaptionStylePreset(val displayName: String, val description: String) {
    HORMOZI_BOLD("Hormozi Bold", "Heavy uppercase, lime pop & thick outline"),
    NEON_POP("Neon Pop", "Cyan-magenta highlight box behind active phrase"),
    MINIMAL_CLEAN("Minimal Clean", "Sleek frosted pill with crisp white sans"),
    NO_CAPTIONS("No Captions", "Clean frame with burned captions disabled");

    companion object {
        fun fromName(name: String): CaptionStylePreset =
            entries.firstOrNull { it.name == name } ?: HORMOZI_BOLD
    }
}

enum class ExportResolution(val label: String, val subtitle: String, val shortEdgePx: Int) {
    FAST_720P("720p Fast", "Quicker render, crisp mobile upload", 720),
    HD_1080P("1080p HD", "Maximum sharpness for Reels & Shorts", 1080)
}

enum class ProjectStatus(val displayLabel: String) {
    ANALYZING("Analyzing"),
    READY("Ready"),
    ERROR("Needs Retry");

    companion object {
        fun fromName(name: String): ProjectStatus =
            entries.firstOrNull { it.name == name } ?: READY
    }
}

data class ClipConfig(
    val targetDuration: TargetDuration = TargetDuration.AUTO,
    val aspectRatio: AspectRatioOption = AspectRatioOption.VERTICAL_9_16,
    val captionStyle: CaptionStylePreset = CaptionStylePreset.HORMOZI_BOLD,
    val focusKeywords: String = ""
)

data class CaptionChunk(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val highlightWord: String
)

data class VideoProject(
    val id: Long = 0L,
    val title: String,
    val sourceUri: String,
    val youtubeUrl: String? = null,
    val youtubeVideoId: String? = null,
    val thumbnailPath: String? = null,
    val durationMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetDuration: TargetDuration = TargetDuration.AUTO,
    val aspectRatio: AspectRatioOption = AspectRatioOption.VERTICAL_9_16,
    val defaultCaptionStyle: CaptionStylePreset = CaptionStylePreset.HORMOZI_BOLD,
    val focusKeywords: String = "",
    val status: ProjectStatus = ProjectStatus.READY,
    val clipCount: Int = 0,
    val isDemo: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val isYouTubeSource: Boolean get() = !youtubeVideoId.isNullOrBlank()
    val hasLocalVideo: Boolean get() = !isDemo && sourceUri.isNotBlank()
    val isYouTubeOnly: Boolean get() = isYouTubeSource && !hasLocalVideo
}

data class ViralClip(
    val id: Long = 0L,
    val projectId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val viralityScore: Int,
    val hookScore: Int,
    val flowScore: Int,
    val engagementScore: Int,
    val reason: String,
    val hashtags: List<String>,
    val captions: List<CaptionChunk>,
    val aspectRatio: AspectRatioOption = AspectRatioOption.VERTICAL_9_16,
    val autoFaceTrackEnabled: Boolean = true,
    val faceCenterX: Float = 0f,
    val manualPanX: Float = 0f,
    val zoomScale: Float = 1.0f,
    val captionStyle: CaptionStylePreset = CaptionStylePreset.HORMOZI_BOLD,
    val captionYPosition: Float = 0.74f,
    val captionUppercase: Boolean = true,
    val lastExportedUri: String? = null
) {
    val clipDurationMs: Long get() = (endMs - startMs).coerceAtLeast(1_000L)
    val effectivePanX: Float
        get() = (if (autoFaceTrackEnabled) faceCenterX else manualPanX).coerceIn(-1f, 1f)
}

object ClipJsonCodec {
    fun encodeHashtags(tags: List<String>): String {
        val arr = JSONArray()
        tags.forEach { arr.put(it) }
        return arr.toString()
    }

    fun decodeHashtags(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeCaptions(captions: List<CaptionChunk>): String {
        val arr = JSONArray()
        captions.forEach { chunk ->
            arr.put(
                JSONObject().apply {
                    put("startMs", chunk.startMs)
                    put("endMs", chunk.endMs)
                    put("text", chunk.text)
                    put("highlightWord", chunk.highlightWord)
                }
            )
        }
        return arr.toString()
    }

    fun decodeCaptions(json: String): List<CaptionChunk> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CaptionChunk(
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 1_000L),
                    text = obj.optString("text", ""),
                    highlightWord = obj.optString("highlightWord", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

fun formatTimeMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

fun formatTimePreciseMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = (safe / 1000L).toInt()
    val tenths = ((safe % 1000L) / 100L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}
