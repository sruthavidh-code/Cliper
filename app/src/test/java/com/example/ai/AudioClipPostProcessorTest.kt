package com.example.ai

import com.example.model.AspectRatioOption
import com.example.model.CaptionStylePreset
import com.example.model.ViralClip
import com.example.model.distributeCaptionWordsProportionally
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioClipPostProcessorTest {

    @Test
    fun `parseAudioClipsJson converts float seconds, makes captions clip-relative, distributes words proportionally, and drops clips under 5s`() {
        val rawJson = """
            {
              "clips": [
                {
                  "title": "Why 90% of Podcasts Lose Listeners",
                  "startTime": 10.0,
                  "endTime": 25.0,
                  "viralityScore": 91,
                  "hookScore": 95,
                  "flowScore": 88,
                  "engagementScore": 90,
                  "reason": "Opens with a concrete statistic and delivers a tight takeaway.",
                  "hashtags": ["PodcastTips", "#Shorts"],
                  "captions": [
                    {
                      "text": "Go viral now",
                      "start": 10.5,
                      "end": 12.5,
                      "highlightWord": "viral"
                    },
                    {
                      "text": "Stop wasting intros",
                      "start": 12.6,
                      "end": 15.0,
                      "highlightWord": "Stop"
                    }
                  ]
                },
                {
                  "title": "Too Short Clip Should Drop",
                  "startTime": 30.0,
                  "endTime": 34.2,
                  "viralityScore": 80,
                  "hookScore": 80,
                  "flowScore": 80,
                  "engagementScore": 80,
                  "reason": "Under 5 seconds.",
                  "hashtags": ["#Short"],
                  "captions": []
                }
              ]
            }
        """.trimIndent()

        val clips = AudioClipPostProcessor.parseAudioClipsJson(
            rawJson = rawJson,
            projectId = 7L,
            chunkStartMs = 0L,
            chunkDurationMs = 60_000L,
            totalDurationMs = 60_000L,
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            defaultCaptionStyle = CaptionStylePreset.HORMOZI_BOLD
        )

        // Clip under 5s is dropped
        assertEquals(1, clips.size)
        val clip = clips[0]
        assertEquals(10_000L, clip.startMs)
        assertEquals(25_000L, clip.endMs)
        assertEquals(91, clip.viralityScore)
        assertEquals(95, clip.hookScore)
        assertEquals(listOf("#PodcastTips", "#Shorts"), clip.hashtags)

        // Captions are strictly clip-relative (10.5s - 10.0s = 500ms, 12.5s - 10.0s = 2500ms)
        assertEquals(2, clip.captions.size)
        val firstCaption = clip.captions[0]
        assertEquals(500L, firstCaption.startMs)
        assertEquals(2_500L, firstCaption.endMs)

        // Words distributed proportionally to character count: "Go"(2), "viral"(5), "now"(3) -> total 10 chars across 2000ms
        assertEquals(3, firstCaption.words.size)
        assertEquals("Go", firstCaption.words[0].text)
        assertEquals(500L, firstCaption.words[0].startMs)
        assertEquals(900L, firstCaption.words[0].endMs) // 2/10 * 2000 = 400ms
        assertEquals("viral", firstCaption.words[1].text)
        assertEquals(900L, firstCaption.words[1].startMs)
        assertEquals(1_900L, firstCaption.words[1].endMs) // 5/10 * 2000 = 1000ms
        assertEquals("now", firstCaption.words[2].text)
        assertEquals(1_900L, firstCaption.words[2].startMs)
        assertEquals(2_500L, firstCaption.words[2].endMs) // 3/10 * 2000 = 600ms
    }

    @Test
    fun `parseAudioClipsJson clamps timestamps to duration and handles empty no-speech clips array`() {
        val emptyJson = """{"clips": []}"""
        val empty = AudioClipPostProcessor.parseAudioClipsJson(
            rawJson = emptyJson,
            projectId = 1L,
            chunkStartMs = 0L,
            chunkDurationMs = 30_000L,
            totalDurationMs = 30_000L,
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            defaultCaptionStyle = CaptionStylePreset.HORMOZI_BOLD
        )
        assertTrue(empty.isEmpty())

        val overflowJson = """
            {
              "clips": [
                {
                  "title": "Clamped End Clip",
                  "startTime": -2.0,
                  "endTime": 99.0,
                  "viralityScore": 84,
                  "hookScore": 82,
                  "flowScore": 85,
                  "engagementScore": 80,
                  "reason": "Clamped cleanly.",
                  "hashtags": ["#Clip"],
                  "captions": []
                }
              ]
            }
        """.trimIndent()
        val clamped = AudioClipPostProcessor.parseAudioClipsJson(
            rawJson = overflowJson,
            projectId = 1L,
            chunkStartMs = 0L,
            chunkDurationMs = 20_000L,
            totalDurationMs = 20_000L,
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            defaultCaptionStyle = CaptionStylePreset.MINIMAL_CLEAN
        )
        assertEquals(1, clamped.size)
        assertEquals(0L, clamped[0].startMs)
        assertEquals(20_000L, clamped[0].endMs)
    }

    @Test(expected = IllegalStateException::class)
    fun `parseAudioClipsJson throws clear error on malformed JSON`() {
        AudioClipPostProcessor.parseAudioClipsJson(
            rawJson = "not valid json",
            projectId = 1L,
            chunkStartMs = 0L,
            chunkDurationMs = 30_000L,
            totalDurationMs = 30_000L,
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            defaultCaptionStyle = CaptionStylePreset.HORMOZI_BOLD
        )
    }

    @Test
    fun `snapToQuietGapMs snaps boundary to lowest RMS bucket within plus-minus 1s radius`() {
        // 200 buckets = 10 seconds at 50 ms per bucket; default loud speech = 0.6f
        val envelope = FloatArray(200) { 0.6f }
        // Place a clear pause at bucket 90 (4500..4550 ms, center = 4525 ms) — 475 ms before 5000 ms
        envelope[90] = 0.02f
        // Place an even quieter pause at bucket 20 (1000 ms) — OUTSIDE +/- 1s of 5000 ms
        envelope[20] = 0.001f

        val snapped = AudioClipPostProcessor.snapToQuietGapMs(
            targetMs = 5_000L,
            loudnessEnvelope = envelope,
            bucketMs = 50L,
            radiusMs = 1_000L,
            minMs = 0L,
            maxMs = 10_000L
        )

        // Should snap to bucket 90 center (4525 ms), NOT bucket 20 (outside +/- 1 s)
        assertEquals(4_525L, snapped)
    }

    @Test
    fun `snapToQuietGapMs breaks flat silence ties toward closest bucket`() {
        val envelope = FloatArray(100) { 0.01f } // uniform silence
        val snapped = AudioClipPostProcessor.snapToQuietGapMs(
            targetMs = 2_000L,
            loudnessEnvelope = envelope,
            bucketMs = 50L,
            radiusMs = 1_000L,
            minMs = 0L,
            maxMs = 5_000L
        )
        // Closest bucket center to 2000ms is bucket 39 (1975ms) or 40 (2025ms) -> within 25ms
        assertTrue(kotlin.math.abs(snapped - 2_000L) <= 25L)
    }

    @Test
    fun `mergeAndDeduplicateClips keeps higher viralityScore across 15s chunk overlap and retains distinct clips`() {
        val chunk1Distinct = fakeClip(title = "Intro Hook", startMs = 12_000L, endMs = 38_000L, score = 88)
        val chunk1Seam = fakeClip(title = "Seam Moment A", startMs = 2_688_000L, endMs = 2_700_000L, score = 82)
        val chunk2SeamDuplicate = fakeClip(title = "Seam Moment B (Better)", startMs = 2_689_000L, endMs = 2_708_000L, score = 94)
        val chunk2Distinct = fakeClip(title = "Late Payoff", startMs = 3_100_000L, endMs = 3_135_000L, score = 86)

        val merged = AudioClipPostProcessor.mergeAndDeduplicateClips(
            listOf(chunk1Distinct, chunk1Seam, chunk2SeamDuplicate, chunk2Distinct)
        )

        assertEquals(3, merged.size)
        assertEquals("Seam Moment B (Better)", merged[0].title)
        assertEquals("Intro Hook", merged[1].title)
        assertEquals("Late Payoff", merged[2].title)
    }

    @Test
    fun `chunk offset shifts chunk 2 timestamps onto original timeline`() {
        val chunk2StartMs = 45L * 60_000L - 15_000L // 2_685_000 ms
        val chunk2Json = """
            {
              "clips": [
                {
                  "title": "Second Chunk Highlight",
                  "startTime": 20.0,
                  "endTime": 40.0,
                  "viralityScore": 90,
                  "hookScore": 90,
                  "flowScore": 89,
                  "engagementScore": 91,
                  "reason": "Strong second-half moment.",
                  "hashtags": ["#Viral"],
                  "captions": [
                    { "text": "Hello world", "start": 21.0, "end": 23.0, "highlightWord": "world" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = AudioClipPostProcessor.parseAudioClipsJson(
            rawJson = chunk2Json,
            projectId = 3L,
            chunkStartMs = chunk2StartMs,
            chunkDurationMs = 30L * 60_000L,
            totalDurationMs = 75L * 60_000L,
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            defaultCaptionStyle = CaptionStylePreset.HORMOZI_BOLD
        )

        assertEquals(1, parsed.size)
        assertEquals(chunk2StartMs + 20_000L, parsed[0].startMs)
        assertEquals(chunk2StartMs + 40_000L, parsed[0].endMs)
        // Caption still clip-relative: 21s - 20s = 1000ms
        assertEquals(1_000L, parsed[0].captions[0].startMs)
        assertEquals(3_000L, parsed[0].captions[0].endMs)
    }

    @Test
    fun `isTransientError detects 429, 503, and IOException while rejecting permanent errors`() {
        assertTrue(AudioClipPostProcessor.isTransientError(RuntimeException("HTTP 429 RESOURCE_EXHAUSTED")))
        assertTrue(AudioClipPostProcessor.isTransientError(RuntimeException("503 Service Unavailable")))
        assertTrue(AudioClipPostProcessor.isTransientError(IOException("Connection reset")))
        assertFalse(AudioClipPostProcessor.isTransientError(NoSpeechDetectedException()))
        assertFalse(AudioClipPostProcessor.isTransientError(CancellationException("Cancelled")))
        assertFalse(AudioClipPostProcessor.isTransientError(IllegalArgumentException("Invalid schema")))
    }

    @Test
    fun `distributeCaptionWordsProportionally covers full chunk range monotonically`() {
        val words = distributeCaptionWordsProportionally("A supercalifragilistic test", 1_000L, 4_000L)
        assertEquals(3, words.size)
        assertEquals(1_000L, words.first().startMs)
        assertEquals(4_000L, words.last().endMs)
        assertTrue((words[1].endMs - words[1].startMs) > (words[0].endMs - words[0].startMs))
        assertTrue((words[1].endMs - words[1].startMs) > (words[2].endMs - words[2].startMs))
    }

    private fun fakeClip(title: String, startMs: Long, endMs: Long, score: Int): ViralClip {
        return ViralClip(
            projectId = 1L,
            title = title,
            startMs = startMs,
            endMs = endMs,
            viralityScore = score,
            hookScore = score,
            flowScore = score,
            engagementScore = score,
            reason = "Test clip",
            hashtags = listOf("#Test"),
            captions = emptyList()
        )
    }
}
