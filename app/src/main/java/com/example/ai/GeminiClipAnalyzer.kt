package com.example.ai

import android.util.Log
import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.TargetDuration
import com.example.model.ViralClip
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiClipAnalyzer"
const val GEMINI_FLASH_MODEL = "gemini-3.8-flash"
private const val VIDEO_ANALYSIS_TIMEOUT_MS = 120_000L

private const val VIRAL_EDITOR_SYSTEM_INSTRUCTION =
    "You are an expert short-form viral video editor specializing in TikTok, Instagram Reels, and YouTube Shorts. Analyze the provided video and identify the highest-retention standalone moments.\n\n" +
        "Rules:\n" +
        "1. Hook Priority: Every clip MUST open with an immediate scroll-stopping hook within the first 3 seconds (a bold claim, curiosity gap, emotional spike, surprising statistic, or clear problem statement). Never start mid-sentence or during filler pauses.\n" +
        "2. Self-Contained Arc: Every clip must feel complete on its own (Hook -> Value/Tension -> Payoff/Punchline) and end cleanly on a finished thought.\n" +
        "3. Exact Timestamps: Return `startMs` and `endMs` accurately in milliseconds, strictly within the source video's duration and aligned to the user's requested target clip length.\n" +
        "4. Honest Scoring: Score `viralityScore`, `hookScore`, `flowScore`, and `engagementScore` critically from 0 to 100. Do not inflate weak moments above 85.\n" +
        "5. Fast-Paced Captions: Break spoken audio inside each clip into punchy 2-to-4-word caption chunks with exact `startMs` and `endMs` relative to the clip start, and pick the single most impactful word in each chunk as `highlightWord`.\n" +
        "6. Strict Format: Output ONLY valid JSON matching the requested schema. No markdown fences, no commentary."

data class YouTubeAnalysisResult(
    val clips: List<ViralClip>,
    val detectedDurationMs: Long?
)

class GeminiClipAnalyzer {

    private val responseSchema: Schema = Schema.obj(
        properties = mapOf(
            "videoDurationMs" to Schema.integer(description = "Total source video duration in milliseconds"),
            "clips" to Schema.array(
                description = "Ranked list of 3 to 5 viral short-form clip moments",
                items = Schema.obj(
                    properties = mapOf(
                        "title" to Schema.string(description = "Catchy short-form title under 65 characters"),
                        "startMs" to Schema.integer(description = "Clip start timestamp in milliseconds"),
                        "endMs" to Schema.integer(description = "Clip end timestamp in milliseconds"),
                        "viralityScore" to Schema.integer(description = "Overall virality score from 0 to 100"),
                        "hookScore" to Schema.integer(description = "Opening 3-second hook strength from 0 to 100"),
                        "flowScore" to Schema.integer(description = "Story pacing and structure score from 0 to 100"),
                        "engagementScore" to Schema.integer(description = "Share, save, and comment potential from 0 to 100"),
                        "reason" to Schema.string(description = "2-sentence editorial explanation of retention potential"),
                        "hashtags" to Schema.array(
                            items = Schema.string(),
                            description = "3 to 5 relevant social hashtags starting with #"
                        ),
                        "captions" to Schema.array(
                            description = "Fast-paced 2-to-4 word caption chunks with timestamps relative to clip start",
                            items = Schema.obj(
                                properties = mapOf(
                                    "startMs" to Schema.integer(description = "Chunk start offset in ms relative to clip start"),
                                    "endMs" to Schema.integer(description = "Chunk end offset in ms relative to clip start"),
                                    "text" to Schema.string(description = "2 to 4 spoken words"),
                                    "highlightWord" to Schema.string(description = "Single most impactful word in text")
                                )
                            )
                        )
                    )
                )
            )
        ),
        optionalProperties = listOf("videoDurationMs")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_FLASH_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@GeminiClipAnalyzer.responseSchema
                temperature = 0.4f
            },
            systemInstruction = content {
                text(VIRAL_EDITOR_SYSTEM_INSTRUCTION)
            }
        )
    }

    suspend fun analyzeVideo(
        videoBytes: ByteArray,
        mimeType: String,
        projectId: Long,
        durationMs: Long,
        targetDuration: TargetDuration,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        focusKeywords: String,
        onStreamingProgress: (charsReceived: Int) -> Unit = {}
    ): List<ViralClip> {
        val focusClause = if (focusKeywords.isNotBlank()) {
            "Prioritize moments touching these user focus keywords/topics: \"$focusKeywords\"."
        } else {
            "Select the strongest general-appeal viral moments across the entire video."
        }

        val prompt = buildString {
            appendLine("Analyze this video (exact total duration: ${durationMs} ms).")
            appendLine("Return 3 to 4 ranked short clips sorted from highest to lowest viralityScore.")
            appendLine("Target clip length rule: ${targetDuration.promptGuidance}.")
            appendLine(focusClause)
            appendLine("CRITICAL: Every startMs and endMs MUST lie strictly between 0 and $durationMs ms, with endMs > startMs.")
            appendLine("For each clip, generate 6 to 10 punchy timestamped caption chunks (startMs and endMs relative to 0..clipDurationMs).")
        }

        try {
            val accumulated = StringBuilder()
            withTimeout(VIDEO_ANALYSIS_TIMEOUT_MS) {
                model.generateContentStream(
                    content {
                        inlineData(videoBytes, mimeType)
                        text(prompt)
                    }
                ).collect { chunk ->
                    val piece = chunk.text
                    if (!piece.isNullOrEmpty()) {
                        accumulated.append(piece)
                        onStreamingProgress(accumulated.length)
                    }
                }
            }

            val rawJson = accumulated.toString().trim()
            if (rawJson.isEmpty()) {
                throw IllegalStateException("Gemini returned an empty response. Please retry analysis.")
            }

            val parsed = parseClipsJson(
                rawJson = rawJson,
                projectId = projectId,
                durationMs = durationMs,
                aspectRatio = aspectRatio,
                defaultCaptionStyle = defaultCaptionStyle
            )

            if (parsed.isEmpty()) {
                throw IllegalStateException("Gemini did not return any valid clip timestamps. Please retry analysis.")
            }

            return parsed.sortedByDescending { it.viralityScore }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini video analysis timed out", e)
            throw IllegalStateException(
                "AI analysis timed out after 2 minutes. Check your connection or tap Retry.",
                e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini video analysis failed", e)
            throw IllegalStateException(
                e.localizedMessage ?: "AI video analysis failed. Check connection and retry.",
                e
            )
        }
    }

    suspend fun analyzeYouTubeVideo(
        youtubeUrl: String,
        projectId: Long,
        targetDuration: TargetDuration,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        focusKeywords: String,
        onStreamingProgress: (charsReceived: Int) -> Unit = {}
    ): YouTubeAnalysisResult {
        val focusClause = if (focusKeywords.isNotBlank()) {
            "Prioritize moments touching these user focus keywords/topics: \"$focusKeywords\"."
        } else {
            "Select the strongest general-appeal viral moments across the entire video."
        }

        val prompt = buildString {
            appendLine("Analyze this public YouTube video carefully.")
            appendLine("Include its exact total duration in milliseconds as videoDurationMs.")
            appendLine("Return 3 to 4 ranked short clips sorted from highest to lowest viralityScore.")
            appendLine("Target clip length rule: ${targetDuration.promptGuidance}.")
            appendLine(focusClause)
            appendLine("CRITICAL: Every startMs and endMs MUST accurately match the spoken moments in the YouTube video, with endMs > startMs.")
            appendLine("For each clip, generate 6 to 10 punchy timestamped caption chunks (startMs and endMs relative to 0..clipDurationMs).")
        }

        try {
            val accumulated = StringBuilder()
            withTimeout(VIDEO_ANALYSIS_TIMEOUT_MS) {
                model.generateContentStream(
                    content {
                        fileData(youtubeUrl, "video/mp4")
                        text(prompt)
                    }
                ).collect { chunk ->
                    val piece = chunk.text
                    if (!piece.isNullOrEmpty()) {
                        accumulated.append(piece)
                        onStreamingProgress(accumulated.length)
                    }
                }
            }

            val rawJson = accumulated.toString().trim()
            if (rawJson.isEmpty()) {
                throw IllegalStateException("Gemini returned an empty response for this YouTube video. Please retry.")
            }

            val root = try {
                JSONObject(rawJson)
            } catch (e: Exception) {
                throw IllegalStateException("Gemini returned malformed JSON data. Tap Retry AI Analysis.", e)
            }

            val detectedDuration = root.optLong("videoDurationMs", 0L).takeIf { it > 3_000L }
            val fallbackDuration = detectedDuration ?: run {
                val arr = root.optJSONArray("clips")
                var maxEnd = 180_000L
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val endVal = arr.optJSONObject(i)?.optLong("endMs", 0L) ?: 0L
                        if (endVal > maxEnd) maxEnd = endVal + 5_000L
                    }
                }
                maxEnd
            }

            val parsed = parseClipsJson(
                rawJson = rawJson,
                projectId = projectId,
                durationMs = fallbackDuration,
                aspectRatio = aspectRatio,
                defaultCaptionStyle = defaultCaptionStyle
            )

            if (parsed.isEmpty()) {
                throw IllegalStateException("Gemini could not extract valid viral clips from this YouTube link. Please retry.")
            }

            val finalDuration = detectedDuration
                ?: parsed.maxOfOrNull { it.endMs }?.plus(5_000L)
                ?: fallbackDuration

            return YouTubeAnalysisResult(
                clips = parsed.sortedByDescending { it.viralityScore },
                detectedDurationMs = finalDuration
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini YouTube analysis timed out", e)
            throw IllegalStateException(
                "AI analysis timed out after 2 minutes. Longer YouTube videos may need a retry.",
                e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini YouTube analysis failed", e)
            throw IllegalStateException(
                e.localizedMessage ?: "Could not analyze this YouTube link. Ensure the video is public and tap Retry.",
                e
            )
        }
    }

    private fun parseClipsJson(
        rawJson: String,
        projectId: Long,
        durationMs: Long,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset
    ): List<ViralClip> {
        val root = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            throw IllegalStateException("Gemini returned malformed JSON data. Tap Retry AI Analysis.", e)
        }

        val clipsArray = root.optJSONArray("clips")
            ?: throw IllegalStateException("AI response was missing the clips list. Tap Retry AI Analysis.")

        val safeDuration = durationMs.coerceAtLeast(3_000L)
        val result = mutableListOf<ViralClip>()

        for (i in 0 until clipsArray.length()) {
            val item = clipsArray.optJSONObject(i) ?: continue
            val title = item.optString("title").trim().ifBlank { "Viral Highlight #${i + 1}" }

            var startMs = item.optLong("startMs", 0L).coerceIn(0L, (safeDuration - 1_500L).coerceAtLeast(0L))
            var endMs = item.optLong("endMs", (startMs + 15_000L).coerceAtMost(safeDuration))
                .coerceIn(startMs + 1_000L, safeDuration)

            if (endMs <= startMs) {
                startMs = 0L
                endMs = safeDuration.coerceAtMost(30_000L)
            }

            val clipLen = (endMs - startMs).coerceAtLeast(1_000L)
            val virality = item.optInt("viralityScore", 72).coerceIn(0, 100)
            val hook = item.optInt("hookScore", virality).coerceIn(0, 100)
            val flow = item.optInt("flowScore", virality).coerceIn(0, 100)
            val engagement = item.optInt("engagementScore", virality).coerceIn(0, 100)
            val reason = item.optString("reason").trim()
                .ifBlank { "Strong opening hook with clear pacing and high short-form retention potential." }

            val tagsArr = item.optJSONArray("hashtags")
            val hashtags = mutableListOf<String>()
            if (tagsArr != null) {
                for (t in 0 until tagsArr.length()) {
                    val tag = tagsArr.optString(t).trim()
                    if (tag.isNotBlank()) {
                        hashtags += if (tag.startsWith("#")) tag else "#$tag"
                    }
                }
            }
            if (hashtags.isEmpty()) {
                hashtags += listOf("#Shorts", "#Reels", "#ViralClip")
            }

            val captionsArr = item.optJSONArray("captions")
            val captions = mutableListOf<CaptionChunk>()
            if (captionsArr != null) {
                for (c in 0 until captionsArr.length()) {
                    val cObj = captionsArr.optJSONObject(c) ?: continue
                    val text = cObj.optString("text").trim()
                    if (text.isBlank()) continue
                    var cStart = cObj.optLong("startMs", 0L)
                    var cEnd = cObj.optLong("endMs", cStart + 2_000L)
                    // Normalize if model returned absolute video timestamps instead of clip-relative offsets
                    if (cStart >= startMs && cEnd <= endMs + 1_000L && startMs > 0L) {
                        cStart -= startMs
                        cEnd -= startMs
                    }
                    cStart = cStart.coerceIn(0L, (clipLen - 200L).coerceAtLeast(0L))
                    cEnd = cEnd.coerceIn(cStart + 200L, clipLen)
                    val highlight = cObj.optString("highlightWord").trim()
                        .ifBlank { text.split(" ").maxByOrNull { it.length }.orEmpty() }
                    captions += CaptionChunk(
                        startMs = cStart,
                        endMs = cEnd,
                        text = text,
                        highlightWord = highlight
                    )
                }
            }

            result += ViralClip(
                projectId = projectId,
                title = title,
                startMs = startMs,
                endMs = endMs,
                viralityScore = virality,
                hookScore = hook,
                flowScore = flow,
                engagementScore = engagement,
                reason = reason,
                hashtags = hashtags.take(5),
                captions = captions.sortedBy { it.startMs },
                aspectRatio = aspectRatio,
                autoFaceTrackEnabled = true,
                faceCenterX = 0f,
                manualPanX = 0f,
                zoomScale = 1.0f,
                captionStyle = defaultCaptionStyle,
                captionYPosition = 0.74f,
                captionUppercase = defaultCaptionStyle == CaptionStylePreset.HORMOZI_BOLD
            )
        }

        return result
    }
}
