package com.example.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "AudioExtractor"

class NoAudioTrackException(
    message: String = "This video has no audio track. ClipPulse analyzes spoken audio to detect viral hooks and captions—please import a video with sound."
) : IllegalStateException(message)

data class AudioChunkWindow(
    val index: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class AudioChunkPayload(
    val index: Int,
    val totalChunks: Int,
    val startMs: Long,
    val durationMs: Long,
    val audioBytes: ByteArray,
    val mimeType: String = "audio/mp4"
)

data class ExtractedAudioResult(
    val chunks: List<AudioChunkPayload>,
    val totalDurationMs: Long,
    val loudnessEnvelope: FloatArray,
    val envelopeBucketMs: Long = AudioExtractor.ENVELOPE_BUCKET_MS
)

/**
 * Pure Kotlin DSP helpers for mono downmix, linear resampling to 16 kHz, 50 ms RMS loudness
 * envelope calculation, and chunk window planning. Kept framework-free for fast JVM unit tests.
 */
object AudioPcmProcessor {

    const val TARGET_SAMPLE_RATE = 16_000
    const val ENVELOPE_BUCKET_MS = 50L
    const val SAMPLES_PER_ENVELOPE_BUCKET = (TARGET_SAMPLE_RATE * ENVELOPE_BUCKET_MS / 1_000L).toInt() // 800

    /**
     * Downmixes interleaved multi-channel 16-bit PCM to mono by averaging channels per frame.
     */
    fun downmixToMono(
        interleaved: ShortArray,
        sampleCount: Int = interleaved.size,
        channelCount: Int
    ): ShortArray {
        val safeCount = sampleCount.coerceIn(0, interleaved.size)
        if (safeCount == 0) return ShortArray(0)
        if (channelCount <= 1) {
            return interleaved.copyOf(safeCount)
        }
        val frameCount = safeCount / channelCount
        if (frameCount <= 0) return ShortArray(0)

        val mono = ShortArray(frameCount)
        var inIdx = 0
        for (frame in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until channelCount) {
                sum += interleaved[inIdx++].toInt()
            }
            mono[frame] = (sum / channelCount)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return mono
    }

    /**
     * Linearly resamples mono 16-bit PCM from [inputSampleRate] to [outputSampleRate] (default 16 kHz).
     */
    fun resampleLinear(
        monoInput: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int = TARGET_SAMPLE_RATE
    ): ShortArray {
        if (monoInput.isEmpty()) return ShortArray(0)
        if (inputSampleRate <= 0 || outputSampleRate <= 0 || inputSampleRate == outputSampleRate) {
            return monoInput.copyOf()
        }
        if (monoInput.size == 1) {
            return monoInput.copyOf()
        }

        val outLength = ((monoInput.size.toLong() * outputSampleRate.toLong()) / inputSampleRate.toLong())
            .toInt()
            .coerceAtLeast(1)
        val output = ShortArray(outLength)
        val step = inputSampleRate.toDouble() / outputSampleRate.toDouble()
        val lastIndex = monoInput.lastIndex

        for (i in 0 until outLength) {
            val srcPos = i * step
            val idx0 = floor(srcPos).toInt().coerceIn(0, lastIndex)
            val idx1 = min(idx0 + 1, lastIndex)
            val frac = srcPos - idx0
            val s0 = monoInput[idx0].toDouble()
            val s1 = monoInput[idx1].toDouble()
            val interpolated = (s0 + (s1 - s0) * frac).roundToInt()
            output[i] = interpolated
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return output
    }

    /**
     * Splits [totalDurationMs] into consecutive windows of at most [maxChunkDurationMs] (45 min)
     * with [overlapMs] (15 s) overlap. Always returns a single window whenever duration fits.
     */
    fun buildChunkWindows(
        totalDurationMs: Long,
        maxChunkDurationMs: Long = AudioExtractor.MAX_CHUNK_DURATION_MS,
        overlapMs: Long = AudioExtractor.CHUNK_OVERLAP_MS
    ): List<AudioChunkWindow> {
        if (totalDurationMs <= 0L) return emptyList()
        if (totalDurationMs <= maxChunkDurationMs) {
            return listOf(AudioChunkWindow(index = 0, startMs = 0L, endMs = totalDurationMs))
        }

        val safeOverlap = overlapMs.coerceIn(0L, maxChunkDurationMs / 2L)
        val step = (maxChunkDurationMs - safeOverlap).coerceAtLeast(1_000L)
        val windows = mutableListOf<AudioChunkWindow>()
        var start = 0L
        var index = 0
        while (start < totalDurationMs) {
            val end = (start + maxChunkDurationMs).coerceAtMost(totalDurationMs)
            windows += AudioChunkWindow(index = index++, startMs = start, endMs = end)
            if (end >= totalDurationMs) break
            start += step
        }
        return windows
    }

    /**
     * Computes a normalized [0f..1f] RMS loudness envelope with one value per [samplesPerBucket]
     * samples (800 samples = 50 ms at 16 kHz).
     */
    fun computeRmsEnvelope(
        mono16k: ShortArray,
        samplesPerBucket: Int = SAMPLES_PER_ENVELOPE_BUCKET
    ): FloatArray {
        val acc = LoudnessEnvelopeAccumulator(samplesPerBucket)
        acc.append(mono16k, 0, mono16k.size)
        return acc.finish()
    }
}

class LoudnessEnvelopeAccumulator(
    private val samplesPerBucket: Int = AudioPcmProcessor.SAMPLES_PER_ENVELOPE_BUCKET
) {
    private val buckets = ArrayList<Float>(1024)
    private var sumSquares = 0.0
    private var countInBucket = 0

    fun append(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        val end = (offset + length).coerceAtMost(samples.size)
        for (i in offset until end) {
            val v = samples[i].toDouble()
            sumSquares += v * v
            countInBucket++
            if (countInBucket >= samplesPerBucket) {
                val rms = (sqrt(sumSquares / countInBucket) / 32768.0).toFloat().coerceIn(0f, 1f)
                buckets.add(rms)
                sumSquares = 0.0
                countInBucket = 0
            }
        }
    }

    fun finish(): FloatArray {
        if (countInBucket > 0) {
            val rms = (sqrt(sumSquares / countInBucket) / 32768.0).toFloat().coerceIn(0f, 1f)
            buckets.add(rms)
            sumSquares = 0.0
            countInBucket = 0
        }
        val out = FloatArray(buckets.size)
        for (i in buckets.indices) {
            out[i] = buckets[i]
        }
        return out
    }
}

/**
 * Extracts only the audio track from a local content:// video URI on [Dispatchers.IO], decodes PCM
 * via [MediaCodec], downmixes to mono, resamples to 16 kHz, computes a 50 ms RMS loudness envelope
 * in the same pass, encodes to compact 28 kbps AAC (.m4a) via [MediaCodec] + [MediaMuxer] in
 * [Context.getCacheDir], and deletes temporary files in finally.
 */
class AudioExtractor(private val context: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE = AudioPcmProcessor.TARGET_SAMPLE_RATE // 16 kHz
        const val TARGET_CHANNELS = 1
        const val TARGET_AAC_BITRATE_BPS = 28_000 // 28 kbps (strictly within 24-32 kbps)
        const val ENVELOPE_BUCKET_MS = AudioPcmProcessor.ENVELOPE_BUCKET_MS // 50 ms

        // Firebase AI Logic caps requests at 20 MB including ~33% base64 overhead -> 14 MB binary guard
        const val MAX_SINGLE_AUDIO_BYTES = 14L * 1024L * 1024L
        const val MAX_CHUNK_DURATION_MS = 45L * 60L * 1000L // 45 minutes
        const val CHUNK_OVERLAP_MS = 15L * 1000L // 15 seconds

        private const val CODEC_TIMEOUT_US = 10_000L
        private const val ESTIMATED_CONTAINER_OVERHEAD_BYTES = 256L * 1024L
    }

    private data class ProbedAudioTrack(
        val trackIndex: Int,
        val format: MediaFormat,
        val mime: String,
        val durationMs: Long
    )

    private data class EncodedWindowResult(
        val bytes: ByteArray,
        val encodedDurationMs: Long
    )

    suspend fun extractAudioForGemini(
        sourceUri: Uri,
        fallbackDurationMs: Long = 0L,
        onProgress: (percent: Int) -> Unit = {}
    ): ExtractedAudioResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        onProgress(0)

        val probed = probeAudioTrack(sourceUri, fallbackDurationMs)
        val estimatedDurationMs = probed.durationMs.coerceAtLeast(fallbackDurationMs).coerceAtLeast(1_000L)
        val estimatedBytes =
            ((estimatedDurationMs / 1_000L) * (TARGET_AAC_BITRATE_BPS / 8L)) + ESTIMATED_CONTAINER_OVERHEAD_BYTES

        // Prefer a single call whenever the audio is expected to fit under the 14 MB ceiling
        if (estimatedBytes <= MAX_SINGLE_AUDIO_BYTES) {
            val envelopeAcc = LoudnessEnvelopeAccumulator()
            val single = extractWindowToM4a(
                sourceUri = sourceUri,
                windowStartMs = 0L,
                windowEndMs = Long.MAX_VALUE,
                expectedDurationMs = estimatedDurationMs,
                envelopeAccumulator = envelopeAcc,
                envelopeSkipBeforeMs = 0L,
                onWindowProgress = onProgress
            )
            val envelope = envelopeAcc.finish()
            val actualDurationMs = single.encodedDurationMs.coerceAtLeast(probed.durationMs).coerceAtLeast(1_000L)

            if (single.bytes.size.toLong() <= MAX_SINGLE_AUDIO_BYTES) {
                onProgress(100)
                return@withContext ExtractedAudioResult(
                    chunks = listOf(
                        AudioChunkPayload(
                            index = 0,
                            totalChunks = 1,
                            startMs = 0L,
                            durationMs = actualDurationMs,
                            audioBytes = single.bytes
                        )
                    ),
                    totalDurationMs = actualDurationMs,
                    loudnessEnvelope = envelope,
                    envelopeBucketMs = ENVELOPE_BUCKET_MS
                )
            }

            // Unexpectedly exceeded 14 MB (e.g. VBR encoder spike) -> split cleanly into <= 45 min chunks
            Log.i(TAG, "Single-pass audio (${single.bytes.size} bytes) exceeded 14 MB; splitting into chunks")
            val windows = AudioPcmProcessor.buildChunkWindows(
                totalDurationMs = actualDurationMs,
                maxChunkDurationMs = MAX_CHUNK_DURATION_MS,
                overlapMs = CHUNK_OVERLAP_MS
            )
            val chunks = extractChunkList(
                sourceUri = sourceUri,
                windows = windows,
                existingEnvelope = envelope,
                onProgress = onProgress
            )
            onProgress(100)
            return@withContext ExtractedAudioResult(
                chunks = chunks,
                totalDurationMs = actualDurationMs,
                loudnessEnvelope = envelope,
                envelopeBucketMs = ENVELOPE_BUCKET_MS
            )
        }

        // Long recording (> ~65 min): proactively extract consecutive <= 45 min chunks with 15 s overlap
        val windows = AudioPcmProcessor.buildChunkWindows(
            totalDurationMs = estimatedDurationMs,
            maxChunkDurationMs = MAX_CHUNK_DURATION_MS,
            overlapMs = CHUNK_OVERLAP_MS
        )
        val envelopeAcc = LoudnessEnvelopeAccumulator()
        val chunks = extractChunkList(
            sourceUri = sourceUri,
            windows = windows,
            existingEnvelope = null,
            envelopeAccumulator = envelopeAcc,
            onProgress = onProgress
        )
        val envelope = envelopeAcc.finish()
        val finalDurationMs = chunks.maxOfOrNull { it.startMs + it.durationMs }
            ?.coerceAtLeast(estimatedDurationMs)
            ?: estimatedDurationMs

        onProgress(100)
        ExtractedAudioResult(
            chunks = chunks,
            totalDurationMs = finalDurationMs,
            loudnessEnvelope = envelope,
            envelopeBucketMs = ENVELOPE_BUCKET_MS
        )
    }

    private suspend fun extractChunkList(
        sourceUri: Uri,
        windows: List<AudioChunkWindow>,
        existingEnvelope: FloatArray?,
        envelopeAccumulator: LoudnessEnvelopeAccumulator? = null,
        onProgress: (Int) -> Unit
    ): List<AudioChunkPayload> {
        val total = windows.size.coerceAtLeast(1)
        val chunks = ArrayList<AudioChunkPayload>(total)
        var recordedEnvelopeUntilMs = 0L

        windows.forEachIndexed { idx, window ->
            coroutineContext.ensureActive()
            val encoded = extractWindowToM4a(
                sourceUri = sourceUri,
                windowStartMs = window.startMs,
                windowEndMs = window.endMs,
                expectedDurationMs = window.durationMs.coerceAtLeast(1_000L),
                envelopeAccumulator = if (existingEnvelope == null) envelopeAccumulator else null,
                envelopeSkipBeforeMs = recordedEnvelopeUntilMs,
                onWindowProgress = { windowPct ->
                    val overall = (((idx * 100) + windowPct) / total).coerceIn(0, 99)
                    onProgress(overall)
                }
            )
            recordedEnvelopeUntilMs = maxOf(recordedEnvelopeUntilMs, window.endMs)
            chunks += AudioChunkPayload(
                index = idx,
                totalChunks = total,
                startMs = window.startMs,
                durationMs = encoded.encodedDurationMs.coerceAtLeast(window.durationMs),
                audioBytes = encoded.bytes
            )
        }
        return chunks
    }

    private fun probeAudioTrack(sourceUri: Uri, fallbackDurationMs: Long): ProbedAudioTrack {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    val durMs = if (durUs > 0L) durUs / 1_000L else fallbackDurationMs
                    return ProbedAudioTrack(
                        trackIndex = i,
                        format = format,
                        mime = mime,
                        durationMs = durMs
                    )
                }
            }
            throw NoAudioTrackException()
        } catch (e: NoAudioTrackException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not read the selected video's audio track: ${e.localizedMessage ?: "unsupported container"}.",
                e
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun extractWindowToM4a(
        sourceUri: Uri,
        windowStartMs: Long,
        windowEndMs: Long,
        expectedDurationMs: Long,
        envelopeAccumulator: LoudnessEnvelopeAccumulator?,
        envelopeSkipBeforeMs: Long,
        onWindowProgress: (Int) -> Unit
    ): EncodedWindowResult {
        val cacheDir = File(context.cacheDir, "audio_extract").apply { mkdirs() }
        val tempFile = File(cacheDir, "clippulse_audio_${System.currentTimeMillis()}_${windowStartMs}.m4a")

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var wroteAnySample = false

        try {
            extractor.setDataSource(context, sourceUri, null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            var inputMime: String? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    inputMime = mime
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null || inputMime == null) {
                throw NoAudioTrackException()
            }

            extractor.selectTrack(audioTrackIndex)
            val windowStartUs = windowStartMs * 1_000L
            val windowEndUs = if (windowEndMs == Long.MAX_VALUE) Long.MAX_VALUE else windowEndMs * 1_000L
            if (windowStartUs > 0L) {
                extractor.seekTo(windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            var currentSampleRate = inputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var currentChannelCount = inputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var currentPcmEncoding = inputFormat.getIntOrDefault(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
            )

            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_AAC_BITRATE_BPS)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerAudioTrack = -1

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderInputDone = false
            var encoderDone = false

            var totalEncodedSamples = 0L
            var lastMuxedPtsUs = -1L
            var lastReportedPct = -1

            // Small FIFO queue for resampled 16 kHz mono PCM samples awaiting AAC encoder input buffers
            var pcmQueue = ShortArray(16_384)
            var pcmQueueSize = 0

            fun appendToPcmQueue(samples: ShortArray) {
                if (samples.isEmpty()) return
                val needed = pcmQueueSize + samples.size
                if (needed > pcmQueue.size) {
                    pcmQueue = pcmQueue.copyOf(maxOf(needed, pcmQueue.size * 2))
                }
                System.arraycopy(samples, 0, pcmQueue, pcmQueueSize, samples.size)
                pcmQueueSize += samples.size
            }

            fun drainEncoderOutput(blockingTimeoutUs: Long) {
                val enc = encoder ?: return
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(encInfo, blockingTimeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerAudioTrack = muxer!!.addTrack(enc.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = enc.getOutputBuffer(outIdx)
                            val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (outBuf != null && encInfo.size > 0 && !isConfig && muxerStarted && muxerAudioTrack >= 0) {
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                var safePts = encInfo.presentationTimeUs.coerceAtLeast(0L)
                                if (safePts <= lastMuxedPtsUs) {
                                    safePts = lastMuxedPtsUs + 1L
                                }
                                lastMuxedPtsUs = safePts
                                encInfo.presentationTimeUs = safePts
                                muxer!!.writeSampleData(muxerAudioTrack, outBuf, encInfo)
                                wroteAnySample = true
                            }

                            enc.releaseOutputBuffer(outIdx, false)
                            if (isEos) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }
            }

            fun feedEncoderFromQueue(signalEosWhenEmpty: Boolean) {
                val enc = encoder ?: return
                while (pcmQueueSize > 0 || (signalEosWhenEmpty && !encoderInputDone)) {
                    val inIdx = enc.dequeueInputBuffer(0L)
                    if (inIdx < 0) {
                        drainEncoderOutput(0L)
                        break
                    }
                    if (pcmQueueSize > 0) {
                        val inBuf = enc.getInputBuffer(inIdx) ?: break
                        inBuf.clear()
                        val maxShorts = (inBuf.capacity() / 2).coerceAtLeast(1)
                        val shortsToWrite = min(pcmQueueSize, maxShorts)
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until shortsToWrite) {
                            inBuf.putShort(pcmQueue[i])
                        }
                        val ptsUs = (totalEncodedSamples * 1_000_000L) / TARGET_SAMPLE_RATE.toLong()
                        enc.queueInputBuffer(inIdx, 0, shortsToWrite * 2, ptsUs, 0)
                        totalEncodedSamples += shortsToWrite

                        val remaining = pcmQueueSize - shortsToWrite
                        if (remaining > 0) {
                            System.arraycopy(pcmQueue, shortsToWrite, pcmQueue, 0, remaining)
                        }
                        pcmQueueSize = remaining
                        drainEncoderOutput(0L)
                    } else if (signalEosWhenEmpty && !encoderInputDone) {
                        val eosPtsUs = (totalEncodedSamples * 1_000_000L) / TARGET_SAMPLE_RATE.toLong()
                        enc.queueInputBuffer(inIdx, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderInputDone = true
                        break
                    }
                }
            }

            while (!encoderDone) {
                coroutineContext.ensureActive()

                // 1) Feed compressed audio packets from MediaExtractor into decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleSize < 0 || (windowEndUs != Long.MAX_VALUE && sampleTimeUs > windowEndUs)) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) Drain decoded PCM frames -> mono downmix -> 16 kHz resample -> RMS envelope + AAC queue
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, if (extractorDone) CODEC_TIMEOUT_US else 0L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = decoder.outputFormat
                            currentSampleRate = newFmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, currentSampleRate)
                            currentChannelCount = newFmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, currentChannelCount)
                            currentPcmEncoding = newFmt.getIntOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                        }
                        outIdx >= 0 -> {
                            val outBuf = decoder.getOutputBuffer(outIdx)
                            val ptsUs = decInfo.presentationTimeUs
                            val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (outBuf != null && decInfo.size > 0 && ptsUs >= windowStartUs && ptsUs <= windowEndUs) {
                                val interleavedShorts = readDecodedPcmShorts(
                                    buffer = outBuf,
                                    offset = decInfo.offset,
                                    size = decInfo.size,
                                    pcmEncoding = currentPcmEncoding
                                )
                                if (interleavedShorts.isNotEmpty()) {
                                    val mono = AudioPcmProcessor.downmixToMono(
                                        interleaved = interleavedShorts,
                                        sampleCount = interleavedShorts.size,
                                        channelCount = currentChannelCount
                                    )
                                    val resampled16k = AudioPcmProcessor.resampleLinear(
                                        monoInput = mono,
                                        inputSampleRate = currentSampleRate,
                                        outputSampleRate = TARGET_SAMPLE_RATE
                                    )
                                    if (envelopeAccumulator != null) {
                                        val frameTimeMs = ptsUs / 1_000L
                                        if (frameTimeMs >= envelopeSkipBeforeMs) {
                                            envelopeAccumulator.append(resampled16k)
                                        }
                                    }
                                    appendToPcmQueue(resampled16k)
                                }

                                val elapsedMs = ((ptsUs - windowStartUs) / 1_000L).coerceAtLeast(0L)
                                val pct = ((elapsedMs * 98L) / expectedDurationMs.coerceAtLeast(1L))
                                    .toInt()
                                    .coerceIn(0, 98)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onWindowProgress(pct)
                                }
                            }

                            decoder.releaseOutputBuffer(outIdx, false)
                            if (isEos) {
                                decoderDone = true
                            }
                        }
                    }
                }

                // 3) Feed 16 kHz mono PCM queue into AAC encoder and drain muxed .m4a samples
                feedEncoderFromQueue(signalEosWhenEmpty = decoderDone)
                drainEncoderOutput(if (encoderInputDone) CODEC_TIMEOUT_US else 0L)
            }

            if (!wroteAnySample || !muxerStarted) {
                throw NoAudioTrackException(
                    "The audio track in this video appears empty or silent and produced no playable samples."
                )
            }

            // Finalize MediaMuxer cleanly before reading .m4a bytes
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            val encodedBytes = tempFile.readBytes()
            if (encodedBytes.isEmpty()) {
                throw IllegalStateException("Extracted audio file was empty.")
            }

            val encodedDurationMs = (totalEncodedSamples * 1_000L) / TARGET_SAMPLE_RATE.toLong()
            onWindowProgress(100)
            return EncodedWindowResult(
                bytes = encodedBytes,
                encodedDurationMs = encodedDurationMs
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxer != null) {
                if (muxerStarted && wroteAnySample) {
                    runCatching { muxer.stop() }
                }
                runCatching { muxer.release() }
            }
            runCatching { extractor.release() }
            runCatching { tempFile.delete() }
        }
    }

    private fun readDecodedPcmShorts(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        pcmEncoding: Int
    ): ShortArray {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(offset)
        dup.limit(offset + size)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
        ) {
            val floatCount = size / 4
            val out = ShortArray(floatCount)
            for (i in 0 until floatCount) {
                val sample = dup.float.coerceIn(-1f, 1f)
                out[i] = (sample * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return out
        }

        val shortCount = size / 2
        val out = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            out[i] = dup.short
        }
        return out
    }

    private fun MediaFormat.getIntOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) {
            runCatching { getInteger(key) }.getOrDefault(defaultValue)
        } else {
            defaultValue
        }
    }
}
