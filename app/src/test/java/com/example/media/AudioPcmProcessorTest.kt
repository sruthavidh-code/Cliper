package com.example.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioPcmProcessorTest {

    @Test
    fun `downmixToMono averages stereo channels per frame`() {
        val stereo = shortArrayOf(
            1000, 3000,   // avg 2000
            -4000, 4000,  // avg 0
            -1000, -3000  // avg -2000
        )

        val mono = AudioPcmProcessor.downmixToMono(stereo, channelCount = 2)

        assertArrayEquals(shortArrayOf(2000, 0, -2000), mono)
    }

    @Test
    fun `downmixToMono handles 4 channels and mono passthrough`() {
        val quad = shortArrayOf(100, 200, 300, 400, -400, -400, -400, -400)
        val monoFromQuad = AudioPcmProcessor.downmixToMono(quad, channelCount = 4)
        assertArrayEquals(shortArrayOf(250, -400), monoFromQuad)

        val single = shortArrayOf(123, -456, 789)
        val monoPass = AudioPcmProcessor.downmixToMono(single, channelCount = 1)
        assertArrayEquals(single, monoPass)
    }

    @Test
    fun `resampleLinear downsamples 48kHz to 16kHz at 1-to-3 length ratio`() {
        // 480 samples at 48 kHz = 10 ms -> 160 samples at 16 kHz
        val input = ShortArray(480) { i -> ((i * 20) % 10_000).toShort() }

        val resampled = AudioPcmProcessor.resampleLinear(
            monoInput = input,
            inputSampleRate = 48_000,
            outputSampleRate = 16_000
        )

        assertEquals(160, resampled.size)
        // Sample 0 corresponds to src index 0; sample 1 corresponds to src index 3
        assertEquals(input[0], resampled[0])
        assertEquals(input[3], resampled[1])
    }

    @Test
    fun `resampleLinear upsamples 8kHz to 16kHz with linear midpoint interpolation`() {
        val input = shortArrayOf(0, 1000, 2000, 3000)

        val resampled = AudioPcmProcessor.resampleLinear(
            monoInput = input,
            inputSampleRate = 8_000,
            outputSampleRate = 16_000
        )

        assertEquals(8, resampled.size)
        assertEquals(0.toShort(), resampled[0])
        assertEquals(500.toShort(), resampled[1])
        assertEquals(1000.toShort(), resampled[2])
        assertEquals(1500.toShort(), resampled[3])
        assertEquals(2000.toShort(), resampled[4])
    }

    @Test
    fun `resampleLinear at matching 16kHz returns identical copy`() {
        val input = shortArrayOf(-1200, 0, 5400, 16000)
        val output = AudioPcmProcessor.resampleLinear(input, 16_000, 16_000)
        assertArrayEquals(input, output)
    }

    @Test
    fun `computeRmsEnvelope emits one RMS bucket per 50ms at 16kHz`() {
        val bucketSamples = AudioPcmProcessor.SAMPLES_PER_ENVELOPE_BUCKET // 800
        val samples = ShortArray(bucketSamples * 2) { idx ->
            if (idx < bucketSamples) 0 else 16_384 // first 50 ms silent, second 50 ms ~0.5 RMS
        }

        val envelope = AudioPcmProcessor.computeRmsEnvelope(samples)

        assertEquals(2, envelope.size)
        assertEquals(0f, envelope[0], 0.0001f)
        assertTrue(abs(envelope[1] - 0.5f) < 0.01f)
    }

    @Test
    fun `buildChunkWindows prefers single window up to 45 minutes and overlaps by 15 seconds above`() {
        val fiveMin = AudioPcmProcessor.buildChunkWindows(5L * 60_000L)
        assertEquals(1, fiveMin.size)
        assertEquals(0L, fiveMin[0].startMs)
        assertEquals(5L * 60_000L, fiveMin[0].endMs)

        val exactFortyFiveMin = AudioPcmProcessor.buildChunkWindows(45L * 60_000L)
        assertEquals(1, exactFortyFiveMin.size)

        // 95 minutes -> chunk 0: [0, 45m], chunk 1: [44m45s, 89m45s], chunk 2: [89m30s, 95m]
        val ninetyFiveMin = AudioPcmProcessor.buildChunkWindows(95L * 60_000L)
        assertEquals(3, ninetyFiveMin.size)
        assertEquals(0L, ninetyFiveMin[0].startMs)
        assertEquals(45L * 60_000L, ninetyFiveMin[0].endMs)
        assertEquals(45L * 60_000L - 15_000L, ninetyFiveMin[1].startMs)
        assertEquals(45L * 60_000L, ninetyFiveMin[1].durationMs)
        assertEquals(ninetyFiveMin[1].endMs - 15_000L, ninetyFiveMin[2].startMs)
        assertEquals(95L * 60_000L, ninetyFiveMin[2].endMs)
    }
}
