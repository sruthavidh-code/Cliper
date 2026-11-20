package com.example.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.model.CaptionChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "YouTubeSupport"

data class YouTubeVideoMetadata(
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val authorName: String,
    val thumbnailUrl: String
)

object YouTubeSupport {

    private val videoIdRegexList = listOf(
        Regex("""(?:youtube\.com/watch\?.*v=)([A-Za-z0-9_-]{11})"""),
        Regex("""(?:youtu\.be/)([A-Za-z0-9_-]{11})"""),
        Regex("""(?:youtube\.com/shorts/)([A-Za-z0-9_-]{11})"""),
        Regex("""(?:youtube\.com/live/)([A-Za-z0-9_-]{11})"""),
        Regex("""(?:youtube\.com/embed/)([A-Za-z0-9_-]{11})""")
    )

    fun extractVideoId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        for (regex in videoIdRegexList) {
            val match = regex.find(trimmed)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun buildCanonicalUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId"

    fun buildTimestampedShareUrl(videoId: String, startMs: Long): String {
        val seconds = (startMs.coerceAtLeast(0L) / 1_000L)
        return "https://youtu.be/$videoId?t=$seconds"
    }

    fun defaultThumbnailUrl(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    suspend fun fetchMetadata(rawUrl: String): YouTubeVideoMetadata = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(rawUrl)
            ?: throw IllegalArgumentException(
                "Please enter a valid YouTube link (watch, youtu.be, Shorts, or Live URL)."
            )

        val canonical = buildCanonicalUrl(videoId)
        val fallbackThumb = defaultThumbnailUrl(videoId)

        var title = "YouTube Video ($videoId)"
        var author = "YouTube Creator"
        var thumbUrl = fallbackThumb

        var conn: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(canonical, "UTF-8")
            val endpoint = URL("https://www.youtube.com/oembed?url=$encoded&format=json")
            conn = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                title = json.optString("title").trim().ifBlank { title }
                author = json.optString("author_name").trim().ifBlank { author }
                thumbUrl = json.optString("thumbnail_url").trim().ifBlank { fallbackThumb }
            } else if (conn.responseCode == 401 || conn.responseCode == 403 || conn.responseCode == 404) {
                throw IllegalStateException(
                    "This YouTube video appears to be private, unavailable, or invalid."
                )
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "oEmbed lookup unavailable; proceeding with canonical YouTube metadata", e)
        } finally {
            conn?.disconnect()
        }

        YouTubeVideoMetadata(
            videoId = videoId,
            canonicalUrl = canonical,
            title = title,
            authorName = author,
            thumbnailUrl = thumbUrl
        )
    }

    fun generateSrt(captions: List<CaptionChunk>, uppercase: Boolean): String {
        if (captions.isEmpty()) return ""
        return buildString {
            captions.forEachIndexed { idx, chunk ->
                val text = if (uppercase) chunk.text.uppercase() else chunk.text
                appendLine(idx + 1)
                appendLine("${formatSrtTime(chunk.startMs)} --> ${formatSrtTime(chunk.endMs)}")
                appendLine(text.trim())
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun createShareableSrtUri(
        context: Context,
        clipTitle: String,
        captions: List<CaptionChunk>,
        uppercase: Boolean
    ): Uri {
        val srtBody = generateSrt(captions, uppercase)
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = clipTitle
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(36)
            .ifBlank { "ClipPulse_Captions" }
        val file = File(exportDir, "${safeTitle}_${System.currentTimeMillis()}.srt")
        file.writeText(srtBody, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
