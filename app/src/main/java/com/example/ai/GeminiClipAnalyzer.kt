package com.example.ai

import android.content.Context
import android.util.Log
import com.example.media.AudioChunkPayload
import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.TargetDuration
import com.example.model.ViralClip
import com.example.model.distributeCaptionWordsProportionally
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

private const val TAG = "GeminiClipAnalyzer"
const val GEMINI_FLASH_MODEL = "gemini-3.8-flash"
private const val AUDIO_ANALYSIS_TIMEOUT_MS = 180_000L
private const val YOUTUBE_ANALYSIS_TIMEOUT_MS = 120_000L
private const val MAX_TRANSIENT_RETRIES = 2
private const val INITIAL_RETRY_DELAY_MS = 2_000L

private const val AUDIO_EDITOR_SYSTEM_INSTRUCTION =
    "You are an expert short-form viral audio editor specializing in TikTok, Instagram Reels, and YouTube Shorts. " +
        "The input is AUDIO ONLY—judge hooks, retention, pacing, and payoff strictly by what is spoken.\n\n" +
        "Rules:\n" +
        "1. Hook Priority (Spoken Only): Every clip MUST open with a scroll-stopping spoken hook in the first 3 seconds (a bold claim, curiosity gap, emotional spike, surprising statistic, or sharp question). Never start mid-sentence.\n" +
        "2. Self-Contained Arc: Every clip must feel complete on its own (Hook -> Value/Tension -> Payoff) and end cleanly on a finished spoken sentence, lasting at least 5 seconds.\n" +
        "3. Exact Audio Timestamps: Return `startTime` and `endTime` as accurate floating-point seconds strictly within the exact provided audio duration.\n" +
        "4. Honest Scoring: Score `viralityScore`, `hookScore`, `flowScore`, and `engagementScore` critically from 0 to 100.\n" +
        "5. Faithful Phrase Captions: Transcribe ONLY actual words spoken in the audio—NEVER invent, guess, or hallucinate dialogue. Break spoken speech inside each clip into punchy 2-to-4-word phrase chunks with absolute float timestamps `start` and `end` (in seconds), and pick the single most impactful spoken word in each phrase as `highlightWord`.\n" +
        "6. No Speech Handling: If the audio contains only music, silence, or ambient sound with no clear spoken words, return an empty `clips` array (`{\"clips\": []}`).\n" +
        "7. Strict Format: Output ONLY valid JSON matching the requested schema. No markdown fences or commentary."

private const val YOUTUBE_EDITOR_SYSTEM_INSTRUCTION =
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

class GeminiClipAnalyzer(
    private val appContext: Context
) {

    private fun requireFirebaseReady() {
        val app = ensureFirebaseAppInitialized(appContext)
        if (app == null) {
            throw IllegalStateException(
                "Firebase is not configured in this build (missing google-services.json resources for " +
                    "${appContext.packageName}). Place app/google-services.json in the project, rebuild the APK, and reinstall."
            )
        }
    }

    // Audio-only structured JSON schema (absolute float seconds for clips and captions)
    private val audioResponseSchema: Schema = Schema.obj(
        properties = mapOf(
            "clips" to Schema.array(
                description = "Ranked list of viral short-form clips detected from spoken audio (empty array if no speech)",
                items = Schema.obj(
                    properties = mapOf(
                        "title" to Schema.string(description = "Catchy short-form title under 65 characters"),
                        "startTime" to Schema.double(description = "Clip start timestamp in absolute seconds"),
                        "endTime" to Schema.double(description = "Clip end timestamp in absolute seconds"),
                        "viralityScore" to Schema.integer(description = "Overall virality score from 0 to 100"),
                        "hookScore" to Schema.integer(description = "Opening 3-second spoken hook strength from 0 to 100"),
                        "flowScore" to Schema.integer(description = "Spoken pacing and narrative flow score from 0 to 100"),
                        "engagementScore" to Schema.integer(description = "Share, save, and comment potential from 0 to 100"),
                        "reason" to Schema.string(description = "2-sentence editorial explanation of retention potential"),
                        "hashtags" to Schema.array(
                            items = Schema.string(),
                            description = "3 to 5 relevant social hashtags starting with #"
                        ),
                        "captions" to Schema.array(
                            description = "Faithful 2-to-4 word phrase caption chunks in absolute float seconds",
                            items = Schema.obj(
                                properties = mapOf(
                                    "text" to Schema.string(description = "2 to 4 actually spoken words"),
                                    "start" to Schema.double(description = "Phrase start time in absolute seconds"),
                                    "end" to Schema.double(description = "Phrase end time in absolute seconds"),
                                    "highlightWord" to Schema.string(description = "Single most impactful word in text")
                                )
                            )
                        )
                    )
                )
            )
        )
    )

    // Existing YouTube schema (unchanged)
    private val youtubeResponseSchema: Schema = Schema.obj(
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

    // Note: Firebase AI Logic (Gemini Developer API backend) does not expose a Vertex-only
    // audioTimestamp flag on GenerationConfig.Builder; exact duration prompting + float-second
    // schema + on-device +/-1 s loudness-envelope gap snapping ensure accurate clip alignment.
    private val audioModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_FLASH_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@GeminiClipAnalyzer.audioResponseSchema
                temperature = 0.35f
            },
            systemInstruction = content {
                text(AUDIO_EDITOR_SYSTEM_INSTRUCTION)
            }
        )
    }

    private val youtubeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_FLASH_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@GeminiClipAnalyzer.youtubeResponseSchema
                temperature = 0.4f
            },
            systemInstruction = content {
                text(YOUTUBE_EDITOR_SYSTEM_INSTRUCTION)
            }
        )
    }

    /**
     * Analyzes one or more compact audio-only `.m4a` chunks (`audio/mp4`) with Gemini Flash in a
     * single structured JSON call per chunk (returning both clips and phrase captions), applies
     * loudness-gap snapping & proportional word timings, and merges/deduplicates chunk overlaps.
     */
    suspend fun analyzeVideo(
        audioChunks: List<AudioChunkPayload>,
        projectId: Long,
        totalDurationMs: Long,
        loudnessEnvelope: FloatArray,
        envelopeBucketMs: Long,
        targetDuration: TargetDuration,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        focusKeywords: String,
        onChunkStatus: (chunkIndex: Int, totalChunks: Int, charsReceived: Int) -> Unit = { _, _, _ -> }
    ): List<ViralClip> {
        if (audioChunks.isEmpty()) {
            throw IllegalStateException("No extracted audio chunks available for AI analysis.")
        }
        requireFirebaseReady()

        val allClips = mutableListOf<ViralClip>()
        val totalChunks = audioChunks.size

        for (chunk in audioChunks) {
            val chunkClips = analyzeSingleAudioChunk(
                chunk = chunk,
                projectId = projectId,
                totalDurationMs = totalDurationMs,
                loudnessEnvelope = loudnessEnvelope,
                envelopeBucketMs = envelopeBucketMs,
                targetDuration = targetDuration,
                aspectRatio = aspectRatio,
                defaultCaptionStyle = defaultCaptionStyle,
                focusKeywords = focusKeywords,
                onStreamingProgress = { chars ->
                    onChunkStatus(chunk.index, totalChunks, chars)
                }
            )
            allClips += chunkClips
        }

        val merged = AudioClipPostProcessor.mergeAndDeduplicateClips(allClips)
        if (merged.isEmpty()) {
            throw NoSpeechDetectedException()
        }
        return merged
    }

    /**
     * Convenience overload for single-chunk audio analysis.
     */
    suspend fun analyzeVideo(
        audioBytes: ByteArray,
        projectId: Long,
        durationMs: Long,
        loudnessEnvelope: FloatArray = FloatArray(0),
        envelopeBucketMs: Long = AudioClipPostProcessor.DEFAULT_ENVELOPE_BUCKET_MS,
        targetDuration: TargetDuration,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        focusKeywords: String,
        onStreamingProgress: (charsReceived: Int) -> Unit = {}
    ): List<ViralClip> {
        val singleChunk = AudioChunkPayload(
            index = 0,
            totalChunks = 1,
            startMs = 0L,
            durationMs = durationMs,
            audioBytes = audioBytes,
            mimeType = "audio/mp4"
        )
        return analyzeVideo(
            audioChunks = listOf(singleChunk),
            projectId = projectId,
            totalDurationMs = durationMs,
            loudnessEnvelope = loudnessEnvelope,
            envelopeBucketMs = envelopeBucketMs,
            targetDuration = targetDuration,
            aspectRatio = aspectRatio,
            defaultCaptionStyle = defaultCaptionStyle,
            focusKeywords = focusKeywords,
            onChunkStatus = { _, _, chars -> onStreamingProgress(chars) }
        )
    }

    private suspend fun analyzeSingleAudioChunk(
        chunk: AudioChunkPayload,
        projectId: Long,
        totalDurationMs: Long,
        loudnessEnvelope: FloatArray,
        envelopeBucketMs: Long,
        targetDuration: TargetDuration,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        focusKeywords: String,
        onStreamingProgress: (charsReceived: Int) -> Unit
    ): List<ViralClip> {
        val chunkDurationSec = String.format(Locale.US, "%.2f", chunk.durationMs / 1000.0)
        val focusClause = if (focusKeywords.isNotBlank()) {
            "Prioritize spoken moments touching these user focus keywords/topics: \"$focusKeywords\"."
        } else {
            "Select the strongest general-appeal spoken viral moments across this audio."
        }

        val prompt = buildString {
            appendLine("The attached input is AUDIO ONLY (no video). Judge hooks and story arcs strictly by what is spoken.")
            appendLine("Exact audio duration: $chunkDurationSec seconds (0.00 to $chunkDurationSec).")
            appendLine("Return 3 to 4 ranked viral short-form clips sorted from highest to lowest viralityScore.")
            appendLine("Target clip length rule: ${targetDuration.promptGuidance}. Every clip must be at least 5.0 seconds long.")
            appendLine(focusClause)
            appendLine("CRITICAL TIMESTAMP RULE: Every clip startTime/endTime and every caption start/end MUST be absolute floating-point seconds strictly between 0.0 and $chunkDurationSec, with endTime > startTime.")
            appendLine("CRITICAL TRANSCRIPTION RULE: Do NOT invent or hallucinate words. Only transcribe actual speech heard in this audio into 2-to-4-word phrase captions with accurate absolute `start` and `end` timestamps in seconds.")
            appendLine("If the audio contains only music, silence, or ambient noise with no clear spoken words, return {\"clips\": []}.")
        }

        val rawJson = executeWithTransientRetry(
            timeoutMs = AUDIO_ANALYSIS_TIMEOUT_MS,
            operationLabel = "audio analysis"
        ) {
            val accumulated = StringBuilder()
            audioModel.generateContentStream(
                content {
                    inlineData(chunk.audioBytes, "audio/mp4")
                    text(prompt)
                }
            ).collect { piece ->
                val text = piece.text
                if (!text.isNullOrEmpty()) {
                    accumulated.append(text)
                    onStreamingProgress(accumulated.length)
                }
            }
            accumulated.toString().trim()
        }

        if (rawJson.isEmpty()) {
            throw IllegalStateException("Gemini returned an empty response. Please retry analysis.")
        }

        return AudioClipPostProcessor.parseAudioClipsJson(
            rawJson = rawJson,
            projectId = projectId,
            chunkStartMs = chunk.startMs,
            chunkDurationMs = chunk.durationMs,
            totalDurationMs = totalDurationMs,
            aspectRatio = aspectRatio,
            defaultCaptionStyle = defaultCaptionStyle,
            loudnessEnvelope = loudnessEnvelope,
            envelopeBucketMs = envelopeBucketMs
        )
    }

    private suspend fun executeWithTransientRetry(
        timeoutMs: Long,
        operationLabel: String,
        block: suspend () -> String
    ): String {
        var attempt = 0
        var backoffMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                return withTimeout(timeoutMs) {
                    block()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini $operationLabel timed out on attempt ${attempt + 1}", e)
                if (attempt >= MAX_TRANSIENT_RETRIES) {
                    throw IllegalStateException(
                        "AI analysis timed out after ${timeoutMs / 1000L} seconds. Check your connection or tap Retry.",
                        e
                    )
                }
                attempt++
                delay(backoffMs)
                backoffMs *= 2L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val transient = AudioClipPostProcessor.isTransientError(e)
                Log.e(TAG, "Gemini $operationLabel failed on attempt ${attempt + 1} (transient=$transient)", e)
                if (!transient || attempt >= MAX_TRANSIENT_RETRIES) {
                    throw IllegalStateException(
                        e.localizedMessage ?: "AI analysis failed. Check connection and retry.",
                        e
                    )
                }
                attempt++
                delay(backoffMs)
                backoffMs *= 2L
            }
        }
    }

    // YouTube analysis path — unchanged
    suspend fun analyzeYouTubeVideo(
        youtubeUrl: String,
        projectId: Long,
        targetDuration: TargetDuration,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        focusKeywords: String,
        onStreamingProgress: (charsReceived: Int) -> Unit = {}
    ): YouTubeAnalysisResult {
        requireFirebaseReady()
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
            withTimeout(YOUTUBE_ANALYSIS_TIMEOUT_MS) {
                youtubeModel.generateContentStream(
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

            val parsed = parseYouTubeClipsJson(
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

    private fun parseYouTubeClipsJson(
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
                        highlightWord = highlight,
                        words = distributeCaptionWordsProportionally(text, cStart, cEnd)
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
