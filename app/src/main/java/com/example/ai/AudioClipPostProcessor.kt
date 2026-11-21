package com.example.ai

import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.ViralClip
import com.example.model.distributeCaptionWordsProportionally
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

class NoSpeechDetectedException(
    message: String = "No spoken dialogue was detected in this video's audio (music, silence, or ambient noise only). Import a video with speech or tap Retry."
) : IllegalStateException(message)

/**
 * Pure Kotlin post-processor for audio-only Gemini clip responses:
 * - Parses structured float-second JSON into millisecond domain models
 * - Clamps clip boundaries to exact audio duration
 * - Snaps clip start/end to the nearest low-loudness gap within +/- 1 s via the 50 ms RMS envelope
 * - Drops clips shorter than 5 seconds
 * - Normalizes captions strictly clip-relative (subtracting snapped clip start) and distributes
 *   word timestamps proportionally to character count
 * - Merges and deduplicates overlapping clips across multi-chunk long recordings
 */
object AudioClipPostProcessor {

    const val MIN_CLIP_DURATION_MS = 5_000L
    const val DEFAULT_ENVELOPE_BUCKET_MS = 50L
    const val GAP_SNAP_RADIUS_MS = 1_000L
    private const val QUIET_TIE_TOLERANCE = 0.005f

    /**
     * Snaps [targetMs] to the quietest 50 ms RMS loudness bucket within +/- [radiusMs],
     * clamped to [minMs, maxMs]. Breaks near-ties in favor of the bucket closest to [targetMs].
     */
    fun snapToQuietGapMs(
        targetMs: Long,
        loudnessEnvelope: FloatArray,
        bucketMs: Long = DEFAULT_ENVELOPE_BUCKET_MS,
        radiusMs: Long = GAP_SNAP_RADIUS_MS,
        minMs: Long = 0L,
        maxMs: Long
    ): Long {
        val safeMax = maxMs.coerceAtLeast(minMs)
        val clampedTarget = targetMs.coerceIn(minMs, safeMax)
        if (loudnessEnvelope.isEmpty() || bucketMs <= 0L || radiusMs <= 0L) {
            return clampedTarget
        }

        val searchStartMs = (clampedTarget - radiusMs).coerceAtLeast(minMs)
        val searchEndMs = (clampedTarget + radiusMs).coerceAtMost(safeMax)
        val startIdx = (searchStartMs / bucketMs).toInt().coerceIn(0, loudnessEnvelope.lastIndex)
        val endIdx = (searchEndMs / bucketMs).toInt().coerceIn(startIdx, loudnessEnvelope.lastIndex)

        var bestIdx = startIdx
        var bestRms = loudnessEnvelope[startIdx]
        var bestDist = abs((startIdx * bucketMs + bucketMs / 2L) - clampedTarget)

        for (idx in (startIdx + 1)..endIdx) {
            val rms = loudnessEnvelope[idx]
            val centerMs = idx * bucketMs + bucketMs / 2L
            val dist = abs(centerMs - clampedTarget)
            val clearlyQuieter = rms + QUIET_TIE_TOLERANCE < bestRms
            val tiedAndCloser = abs(rms - bestRms) <= QUIET_TIE_TOLERANCE && dist < bestDist
            if (clearlyQuieter || tiedAndCloser) {
                bestIdx = idx
                bestRms = rms
                bestDist = dist
            }
        }

        val snapped = bestIdx * bucketMs + bucketMs / 2L
        return snapped.coerceIn(minMs, safeMax)
    }

    /**
     * Parses a single audio-chunk Gemini JSON payload (timestamps in float seconds), applies
     * chunk offset, duration clamping, +/- 1 s loudness-gap boundary snapping, 5-second minimum
     * filtering, strict clip-relative caption conversion, and character-proportional word timings.
     */
    fun parseAudioClipsJson(
        rawJson: String,
        projectId: Long,
        chunkStartMs: Long,
        chunkDurationMs: Long,
        totalDurationMs: Long,
        aspectRatio: AspectRatioOption,
        defaultCaptionStyle: CaptionStylePreset,
        loudnessEnvelope: FloatArray = FloatArray(0),
        envelopeBucketMs: Long = DEFAULT_ENVELOPE_BUCKET_MS
    ): List<ViralClip> {
        val root = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            throw IllegalStateException("Gemini returned malformed JSON data. Tap Retry AI Analysis.", e)
        }

        val clipsArray = root.optJSONArray("clips")
            ?: throw IllegalStateException("AI response was missing the clips list. Tap Retry AI Analysis.")

        val safeTotalDurationMs = totalDurationMs.coerceAtLeast(1_000L)
        val chunkMaxSec = (chunkDurationMs.coerceAtLeast(1_000L) / 1000.0)
        val result = mutableListOf<ViralClip>()

        for (i in 0 until clipsArray.length()) {
            val item = clipsArray.optJSONObject(i) ?: continue
            val title = item.optString("title").trim().ifBlank { "Viral Highlight #${i + 1}" }

            val rawStartSec = item.optDouble("startTime", Double.NaN)
            val rawEndSec = item.optDouble("endTime", Double.NaN)
            if (rawStartSec.isNaN() || rawEndSec.isNaN()) continue

            // Offsetting rule: if chunkStartMs > 0 and model returned chunk-local seconds (<= chunkMaxSec + 5s),
            // offset by chunkStartMs so all clips live on the original media timeline.
            val applyChunkOffset = chunkStartMs > 0L && rawEndSec <= (chunkMaxSec + 5.0)
            val baseOffsetMs = if (applyChunkOffset) chunkStartMs else 0L

            val clampedStartMs = ((rawStartSec * 1000.0).roundToLong() + baseOffsetMs)
                .coerceIn(0L, safeTotalDurationMs)
            val clampedEndMs = ((rawEndSec * 1000.0).roundToLong() + baseOffsetMs)
                .coerceIn(0L, safeTotalDurationMs)

            if (clampedEndMs <= clampedStartMs) continue

            // Snap clip boundaries to nearest low-loudness gap within +/- 1 s
            val snappedStartMs = snapToQuietGapMs(
                targetMs = clampedStartMs,
                loudnessEnvelope = loudnessEnvelope,
                bucketMs = envelopeBucketMs,
                radiusMs = GAP_SNAP_RADIUS_MS,
                minMs = 0L,
                maxMs = (clampedEndMs - 1_000L).coerceAtLeast(0L)
            )
            val snappedEndMs = snapToQuietGapMs(
                targetMs = clampedEndMs,
                loudnessEnvelope = loudnessEnvelope,
                bucketMs = envelopeBucketMs,
                radiusMs = GAP_SNAP_RADIUS_MS,
                minMs = (snappedStartMs + 1_000L).coerceAtMost(safeTotalDurationMs),
                maxMs = safeTotalDurationMs
            )

            val clipDurationMs = snappedEndMs - snappedStartMs
            // B8: drop clips shorter than 5 seconds
            if (clipDurationMs < MIN_CLIP_DURATION_MS) continue

            val virality = item.optInt("viralityScore", 72).coerceIn(0, 100)
            val hook = item.optInt("hookScore", virality).coerceIn(0, 100)
            val flow = item.optInt("flowScore", virality).coerceIn(0, 100)
            val engagement = item.optInt("engagementScore", virality).coerceIn(0, 100)
            val reason = item.optString("reason").trim()
                .ifBlank { "Strong spoken opening hook with clear pacing and standalone payoff." }

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

            // B6 & B8: parse absolute float-second captions -> subtract clip start (always clip-relative)
            // and distribute word timings proportionally to character count
            val captionsArr = item.optJSONArray("captions")
            val captions = mutableListOf<CaptionChunk>()
            if (captionsArr != null) {
                for (c in 0 until captionsArr.length()) {
                    val cObj = captionsArr.optJSONObject(c) ?: continue
                    val text = cObj.optString("text").trim()
                    if (text.isBlank()) continue

                    val cStartSec = cObj.optDouble("start", Double.NaN)
                    val cEndSec = cObj.optDouble("end", Double.NaN)
                    if (cStartSec.isNaN() || cEndSec.isNaN()) continue

                    val absCaptionStartMs = (cStartSec * 1000.0).roundToLong() + baseOffsetMs
                    val absCaptionEndMs = (cEndSec * 1000.0).roundToLong() + baseOffsetMs

                    // Always convert to clip-relative offsets by subtracting snapped clip start
                    val relStartRaw = absCaptionStartMs - snappedStartMs
                    val relEndRaw = absCaptionEndMs - snappedStartMs

                    // Skip captions that lie completely outside the snapped clip window
                    if (relEndRaw <= 0L || relStartRaw >= clipDurationMs) continue

                    val relStart = relStartRaw.coerceIn(0L, (clipDurationMs - 150L).coerceAtLeast(0L))
                    val relEnd = relEndRaw.coerceIn(relStart + 150L, clipDurationMs)

                    val highlight = cObj.optString("highlightWord").trim()
                        .ifBlank { text.split(Regex("\\s+")).maxByOrNull { it.length }.orEmpty() }

                    val wordTimings = distributeCaptionWordsProportionally(
                        text = text,
                        startMs = relStart,
                        endMs = relEnd
                    )

                    captions += CaptionChunk(
                        startMs = relStart,
                        endMs = relEnd,
                        text = text,
                        highlightWord = highlight,
                        words = wordTimings
                    )
                }
            }

            result += ViralClip(
                projectId = projectId,
                title = title,
                startMs = snappedStartMs,
                endMs = snappedEndMs,
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

    /**
     * Merges clips from consecutive audio chunks and deduplicates overlaps (keeping the clip with
     * the higher viralityScore, breaking ties by longer duration).
     */
    fun mergeAndDeduplicateClips(
        clips: List<ViralClip>,
        maxOverlapRatio: Float = 0.40f,
        maxBoundaryDeltaMs: Long = 6_000L
    ): List<ViralClip> {
        if (clips.size <= 1) return clips.sortedByDescending { it.viralityScore }

        val ranked = clips.sortedWith(
            compareByDescending<ViralClip> { it.viralityScore }
                .thenByDescending { it.endMs - it.startMs }
                .thenBy { it.startMs }
        )

        val accepted = mutableListOf<ViralClip>()
        for (candidate in ranked) {
            val isDuplicate = accepted.any { existing ->
                clipsOverlapSignificantly(
                    a = existing,
                    b = candidate,
                    maxOverlapRatio = maxOverlapRatio,
                    maxBoundaryDeltaMs = maxBoundaryDeltaMs
                )
            }
            if (!isDuplicate) {
                accepted += candidate
            }
        }

        return accepted.sortedByDescending { it.viralityScore }
    }

    private fun clipsOverlapSignificantly(
        a: ViralClip,
        b: ViralClip,
        maxOverlapRatio: Float,
        maxBoundaryDeltaMs: Long
    ): Boolean {
        if (abs(a.startMs - b.startMs) <= maxBoundaryDeltaMs &&
            abs(a.endMs - b.endMs) <= maxBoundaryDeltaMs
        ) {
            return true
        }
        val overlapStart = maxOf(a.startMs, b.startMs)
        val overlapEnd = minOf(a.endMs, b.endMs)
        val overlapMs = overlapEnd - overlapStart
        if (overlapMs <= 0L) return false

        val minDuration = min(a.endMs - a.startMs, b.endMs - b.startMs).coerceAtLeast(1L)
        val overlapFraction = overlapMs.toFloat() / minDuration.toFloat()
        return overlapFraction >= maxOverlapRatio
    }

    /**
     * Returns true when [throwable] represents a transient rate-limit (429), backend unavailable
     * (503/500/502/504), or temporary network/socket error eligible for exponential-backoff retry.
     */
    fun isTransientError(throwable: Throwable): Boolean {
        if (throwable is CancellationException ||
            throwable is NoSpeechDetectedException
        ) {
            return false
        }

        var cursor: Throwable? = throwable
        var depth = 0
        while (cursor != null && depth < 6) {
            if (cursor is IOException ||
                cursor is UnknownHostException ||
                cursor is SocketTimeoutException
            ) {
                return true
            }
            val msg = cursor.message.orEmpty().lowercase()
            if (msg.contains("429") ||
                msg.contains("503") ||
                msg.contains("502") ||
                msg.contains("504") ||
                msg.contains("500") ||
                msg.contains("unavailable") ||
                msg.contains("resource_exhausted") ||
                msg.contains("rate limit") ||
                msg.contains("too many requests") ||
                msg.contains("overloaded") ||
                msg.contains("network") ||
                msg.contains("connection reset") ||
                msg.contains("unable to resolve host")
            ) {
                return true
            }
            cursor = cursor.cause
            depth++
        }
        return false
    }
}
