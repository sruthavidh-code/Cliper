package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.InAppMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "SampleVideoGenerator"

data class GeneratedSampleVideo(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val fileSizeBytes: Long
)

/**
 * Generates a real, hardware-playable randomized 5-minute (300s) 16:9 H.264 + AAC MP4 video
 * using native MediaCodec encoding and Media3's pure-Java InAppMuxer (completely bypassing
 * Android's legacy native MPEG4Writer), then publishes it into Android's MediaStore
 * (Movies/ClipPulse) via Scoped Storage.
 *
 * Strategy:
 * 1. Encode a fresh randomized 16-second multi-scene master segment in memory
 *    (768x432 @ 12fps = 192 H.264 frames, 48kHz mono AAC = 750 exact frames; < 1 MB total).
 * 2. Write the full 5:00 (300s) MP4 in one clean pass using Media3 InAppMuxer across 18.75 loops
 *    (18 * 16s + 12s = exact 300.0s / 05:00) with monotonic timestamps and zero A/V drift.
 */
@OptIn(UnstableApi::class)
class SampleVideoGenerator(private val context: Context) {

    private companion object {
        const val WIDTH = 768
        const val HEIGHT = 432
        const val FPS = 12
        const val VIDEO_BITRATE = 120_000
        const val AUDIO_SAMPLE_RATE = 48_000
        const val AUDIO_CHANNELS = 1
        const val AUDIO_BITRATE = 32_000
        const val AAC_SAMPLES_PER_FRAME = 1024

        // 16.000000 seconds is an EXACT integer multiple of both 12fps (192 frames)
        // and 48kHz AAC-LC (750 frames * 1024 samples = 768,000 samples = 16.0s).
        const val SEGMENT_DURATION_SEC = 16
        const val SEGMENT_DURATION_US = SEGMENT_DURATION_SEC * 1_000_000L
        const val SEGMENT_VIDEO_FRAMES = SEGMENT_DURATION_SEC * FPS // 192
        const val SEGMENT_AAC_FRAMES = (SEGMENT_DURATION_SEC * AUDIO_SAMPLE_RATE) / AAC_SAMPLES_PER_FRAME // 750

        const val TARGET_DURATION_SEC = 300 // 5 minutes
        const val TARGET_DURATION_US = TARGET_DURATION_SEC * 1_000_000L
        const val TARGET_DURATION_MS = TARGET_DURATION_SEC * 1_000L

        const val CODEC_TIMEOUT_US = 10_000L
        const val PREFS_NAME = "clippulse_sandbox_media"
        const val KEY_SEEDED_5MIN = "seeded_random_5min_video_v3"
    }

    private data class EncodedSample(
        val data: ByteArray,
        val ptsUs: Long,
        val isKeyFrame: Boolean
    )

    private data class EncodedTrack(
        val format: Format,
        val samples: List<EncodedSample>
    )

    fun isInitialSampleSeeded(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEDED_5MIN, false)
    }

    fun markInitialSampleSeeded() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEDED_5MIN, true)
            .apply()
    }

    private data class ScenePalette(
        val topColor: Int,
        val bottomColor: Int,
        val accentA: Int,
        val accentB: Int,
        val label: String,
        val speakerShiftX: Float
    )

    suspend fun generateRandomFiveMinuteVideo(
        onProgress: (Int) -> Unit = {}
    ): GeneratedSampleVideo = withContext(Dispatchers.Default) {
        val seed = System.currentTimeMillis() xor System.nanoTime()
        val rng = Random(seed)
        val sessionTag = (1000..9999).random(rng)
        val topics = listOf(
            "Creator Studio Session",
            "Tech Deep Dive",
            "Podcast Highlights",
            "Product Launch Live",
            "Founder Fireside"
        )
        val topic = topics[rng.nextInt(topics.size)]
        val palettes = buildRandomPalettes(rng, topic)

        val cacheDir = File(context.cacheDir, "sample_gen").apply { mkdirs() }
        val finalMp4File = File(cacheDir, "final_5min_$sessionTag.mp4")

        try {
            onProgress(3)
            // Step 1: Encode randomized 16-second H.264 video master track in memory (3% -> 58%)
            val videoTrack = encodeMasterVideoTrack(
                palettes = palettes,
                sessionTag = sessionTag,
                topic = topic,
                rng = rng
            ) { fraction ->
                onProgress((3 + (fraction * 55f).toInt()).coerceIn(3, 58))
            }

            if (videoTrack.samples.isEmpty()) {
                throw IllegalStateException("Video encoder produced no frames.")
            }

            coroutineContext.ensureActive()

            // Step 2: Encode matching 16-second randomized ambient AAC audio track in memory (58% -> 72%)
            val audioTrack = runCatching {
                encodeMasterAudioTrack(rng = rng) { fraction ->
                    onProgress((58 + (fraction * 14f).toInt()).coerceIn(58, 72))
                }.takeIf { it.samples.isNotEmpty() }
            }.onFailure { err ->
                Log.w(TAG, "Audio synthesis fallback to video-only MP4", err)
            }.getOrNull()

            coroutineContext.ensureActive()

            // Step 3: Write the full 5:00 (300s) MP4 directly with Media3 InAppMuxer (72% -> 92%)
            muxToFiveMinutesInApp(
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                finalOutputFile = finalMp4File
            ) { fraction ->
                onProgress((72 + (fraction * 20f).toInt()).coerceIn(72, 92))
            }

            if (!finalMp4File.exists() || finalMp4File.length() <= 0L) {
                throw IllegalStateException("Final 5-minute MP4 file is empty.")
            }

            coroutineContext.ensureActive()

            // Step 4: Publish into Android MediaStore (Movies/ClipPulse) (92% -> 100%)
            val displayName = "ClipPulse_Random_5min_$sessionTag.mp4"
            val fileSizeBytes = finalMp4File.length()
            val savedUri = saveToAndroidMediaStore(finalMp4File, displayName)
            onProgress(100)

            GeneratedSampleVideo(
                uri = savedUri,
                displayName = displayName,
                durationMs = TARGET_DURATION_MS,
                fileSizeBytes = fileSizeBytes
            )
        } finally {
            runCatching { finalMp4File.delete() }
        }
    }

    private fun buildRandomPalettes(rng: Random, topic: String): List<ScenePalette> {
        val colorSets = listOf(
            intArrayOf(0xFF1A103D.toInt(), 0xFF0A0D18.toInt(), 0xFF7C3AED.toInt(), 0xFFA3E635.toInt()),
            intArrayOf(0xFF0B253A.toInt(), 0xFF08101A.toInt(), 0xFF22D3EE.toInt(), 0xFFF472B6.toInt()),
            intArrayOf(0xFF2E1032.toInt(), 0xFF100A16.toInt(), 0xFFFB7185.toInt(), 0xFFFBBF24.toInt()),
            intArrayOf(0xFF102A24.toInt(), 0xFF081412.toInt(), 0xFF34D399.toInt(), 0xFFA78BFA.toInt()),
            intArrayOf(0xFF2B1B0E.toInt(), 0xFF110C08.toInt(), 0xFFF59E0B.toInt(), 0xFF38BDF8.toInt())
        ).shuffled(rng)

        val chapterNames = listOf(
            "HOOK • $topic",
            "KEY INSIGHT • STORY ARC",
            "BREAKDOWN • LIVE STUDIO",
            "TAKEAWAY • VIRAL MOMENT"
        )
        val shifts = listOf(-0.18f, 0.16f, -0.06f, 0.14f)

        return (0 until 4).map { idx ->
            val set = colorSets[idx % colorSets.size]
            ScenePalette(
                topColor = set[0],
                bottomColor = set[1],
                accentA = set[2],
                accentB = set[3],
                label = chapterNames[idx],
                speakerShiftX = shifts[idx]
            )
        }
    }

    private suspend fun encodeMasterVideoTrack(
        palettes: List<ScenePalette>,
        sessionTag: Int,
        topic: String,
        rng: Random,
        onFraction: (Float) -> Unit
    ): EncodedTrack {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val argbPixels = IntArray(WIDTH * HEIGHT)
        val bufferInfo = MediaCodec.BufferInfo()
        val motionPhaseOffset = rng.nextFloat() * 6.28f

        var outputFormat: MediaFormat? = null
        val samples = ArrayList<EncodedSample>(SEGMENT_VIDEO_FRAMES)
        var lastPtsUs = -1L

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val inputFormat = runCatching { codec.inputFormat }.getOrNull()
            val stride = inputFormat?.getIntegerOrDefault(MediaFormat.KEY_STRIDE, WIDTH)
                ?.coerceAtLeast(WIDTH) ?: WIDTH
            val sliceHeight = inputFormat?.getIntegerOrDefault(MediaFormat.KEY_SLICE_HEIGHT, HEIGHT)
                ?.coerceAtLeast(HEIGHT) ?: HEIGHT
            val nominalFrameBytes = (stride * sliceHeight * 3) / 2

            var frameIndex = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        if (frameIndex >= SEGMENT_VIDEO_FRAMES) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = (frameIndex.toLong() * 1_000_000L) / FPS.toLong()
                            renderStudioFrame(
                                canvas = canvas,
                                frameIndex = frameIndex,
                                palettes = palettes,
                                sessionTag = sessionTag,
                                topic = topic,
                                motionPhaseOffset = motionPhaseOffset
                            )
                            bitmap.getPixels(argbPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

                            val rawCapacity = runCatching {
                                codec.getInputBuffer(inputIndex)?.capacity()
                            }.getOrNull() ?: nominalFrameBytes

                            val image = runCatching { codec.getInputImage(inputIndex) }.getOrNull()
                            val queueSize = if (image != null) {
                                writeArgbToYuvImage(argbPixels, WIDTH, HEIGHT, image)
                                nominalFrameBytes.coerceAtMost(rawCapacity)
                            } else {
                                val inputBuf = codec.getInputBuffer(inputIndex)
                                if (inputBuf != null) {
                                    writeArgbToI420Buffer(argbPixels, WIDTH, HEIGHT, inputBuf)
                                }
                                (WIDTH * HEIGHT * 3 / 2).coerceAtMost(rawCapacity)
                            }
                            codec.queueInputBuffer(inputIndex, 0, queueSize, ptsUs, 0)
                            frameIndex++
                            if (frameIndex % 8 == 0 || frameIndex == SEGMENT_VIDEO_FRAMES) {
                                onFraction(frameIndex.toFloat() / SEGMENT_VIDEO_FRAMES.toFloat())
                            }
                        }
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (inputDone) CODEC_TIMEOUT_US else 0L)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = codec.outputFormat
                        }
                        outputIndex >= 0 -> {
                            val encodedBuf = codec.getOutputBuffer(outputIndex)
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (encodedBuf != null && bufferInfo.size > 0 && !isConfig) {
                                val bytes = ByteArray(bufferInfo.size)
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.get(bytes)

                                var safePts = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                if (safePts <= lastPtsUs) {
                                    safePts = lastPtsUs + 1L
                                }
                                lastPtsUs = safePts

                                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                samples.add(EncodedSample(bytes, safePts, isKey))
                            }

                            codec.releaseOutputBuffer(outputIndex, false)
                            if (isEos) {
                                outputDone = true
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            bitmap.recycle()
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val resolvedMediaFormat = outputFormat
            ?: throw IllegalStateException("Video encoder did not emit output format.")
        val media3Format = MediaFormatUtil.createFormatFromMediaFormat(resolvedMediaFormat)
        return EncodedTrack(format = media3Format, samples = samples)
    }

    private suspend fun encodeMasterAudioTrack(
        rng: Random,
        onFraction: (Float) -> Unit
    ): EncodedTrack {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_SAMPLES_PER_FRAME * 4)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val bufferInfo = MediaCodec.BufferInfo()

        val chordPool = listOf(
            doubleArrayOf(220.0, 277.18, 329.63),
            doubleArrayOf(196.0, 246.94, 293.66),
            doubleArrayOf(261.63, 329.63, 392.00),
            doubleArrayOf(174.61, 220.0, 261.63),
            doubleArrayOf(293.66, 349.23, 440.0)
        ).shuffled(rng)

        var outputFormat: MediaFormat? = null
        val samples = ArrayList<EncodedSample>(SEGMENT_AAC_FRAMES)
        var lastPtsUs = -1L

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var aacFrameIdx = 0
            var totalSamples = 0L
            var inputDone = false
            var outputDone = false
            val pcmBytes = ByteArray(AAC_SAMPLES_PER_FRAME * 2)

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        if (aacFrameIdx >= SEGMENT_AAC_FRAMES) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = (totalSamples * 1_000_000L) / AUDIO_SAMPLE_RATE.toLong()
                            fillSynthPcmFrame(
                                pcmBytes = pcmBytes,
                                startSample = totalSamples,
                                chords = chordPool
                            )
                            val inputBuf = codec.getInputBuffer(inputIndex)
                            if (inputBuf != null) {
                                inputBuf.clear()
                                inputBuf.put(pcmBytes)
                            }
                            codec.queueInputBuffer(inputIndex, 0, pcmBytes.size, ptsUs, 0)
                            totalSamples += AAC_SAMPLES_PER_FRAME
                            aacFrameIdx++
                            if (aacFrameIdx % 30 == 0 || aacFrameIdx == SEGMENT_AAC_FRAMES) {
                                onFraction(aacFrameIdx.toFloat() / SEGMENT_AAC_FRAMES.toFloat())
                            }
                        }
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (inputDone) CODEC_TIMEOUT_US else 0L)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = codec.outputFormat
                        }
                        outputIndex >= 0 -> {
                            val encodedBuf = codec.getOutputBuffer(outputIndex)
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (encodedBuf != null && bufferInfo.size > 0 && !isConfig) {
                                val bytes = ByteArray(bufferInfo.size)
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.get(bytes)

                                var safePts = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                if (safePts <= lastPtsUs) {
                                    safePts = lastPtsUs + 1L
                                }
                                lastPtsUs = safePts

                                samples.add(EncodedSample(bytes, safePts, isKeyFrame = true))
                            }

                            codec.releaseOutputBuffer(outputIndex, false)
                            if (isEos) {
                                outputDone = true
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val resolvedMediaFormat = outputFormat
            ?: throw IllegalStateException("Audio encoder did not emit output format.")
        val media3Format = MediaFormatUtil.createFormatFromMediaFormat(resolvedMediaFormat)
        return EncodedTrack(format = media3Format, samples = samples)
    }

    private fun fillSynthPcmFrame(
        pcmBytes: ByteArray,
        startSample: Long,
        chords: List<DoubleArray>
    ) {
        val twoPi = 2.0 * PI
        var outPos = 0
        for (i in 0 until AAC_SAMPLES_PER_FRAME) {
            val sampleIdx = startSample + i
            val t = sampleIdx.toDouble() / AUDIO_SAMPLE_RATE.toDouble()
            val sceneIdx = ((t / 4.0).toInt()).coerceIn(0, 3)
            val chord = chords[sceneIdx % chords.size]
            val localT = t % 4.0
            val env = when {
                localT < 0.12 -> localT / 0.12
                localT > 3.88 -> (4.0 - localT) / 0.12
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val pulse = 0.65 + 0.35 * abs(sin(PI * t * 1.5))
            val signal = (
                sin(twoPi * chord[0] * t) * 0.45 +
                    sin(twoPi * chord[1] * t) * 0.32 +
                    sin(twoPi * chord[2] * t) * 0.23
                ) * env * pulse * 0.22

            val pcm = (signal * 32767.0).toInt().coerceIn(-32768, 32767)
            pcmBytes[outPos++] = (pcm and 0xFF).toByte()
            pcmBytes[outPos++] = ((pcm shr 8) and 0xFF).toByte()
        }
    }

    private suspend fun muxToFiveMinutesInApp(
        videoTrack: EncodedTrack,
        audioTrack: EncodedTrack?,
        finalOutputFile: File,
        onFraction: (Float) -> Unit
    ) {
        val muxer = InAppMuxer.Factory.Builder().build().create(finalOutputFile.absolutePath)
        val bufferInfo = MediaCodec.BufferInfo()
        var isClosed = false
        try {
            val videoToken = muxer.addTrack(videoTrack.format)
            val audioToken = audioTrack?.let { muxer.addTrack(it.format) }

            val totalPasses = (TARGET_DURATION_SEC + SEGMENT_DURATION_SEC - 1) / SEGMENT_DURATION_SEC // 19
            var lastVideoPtsUs = -1L
            var lastAudioPtsUs = -1L

            for (pass in 0 until totalPasses) {
                coroutineContext.ensureActive()
                val baseOffsetUs = pass.toLong() * SEGMENT_DURATION_US
                val remainingUs = TARGET_DURATION_US - baseOffsetUs
                if (remainingUs <= 0L) break

                for (sample in videoTrack.samples) {
                    if (sample.ptsUs >= remainingUs) break
                    var targetPts = baseOffsetUs + sample.ptsUs
                    if (targetPts <= lastVideoPtsUs) {
                        targetPts = lastVideoPtsUs + 1L
                    }
                    lastVideoPtsUs = targetPts

                    val flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    bufferInfo.set(0, sample.data.size, targetPts, flags)
                    muxer.writeSampleData(
                        videoToken,
                        ByteBuffer.wrap(sample.data),
                        bufferInfo
                    )
                }

                if (audioTrack != null && audioToken != null) {
                    for (sample in audioTrack.samples) {
                        if (sample.ptsUs >= remainingUs) break
                        var targetPts = baseOffsetUs + sample.ptsUs
                        if (targetPts <= lastAudioPtsUs) {
                            targetPts = lastAudioPtsUs + 1L
                        }
                        lastAudioPtsUs = targetPts

                        bufferInfo.set(0, sample.data.size, targetPts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(
                            audioToken,
                            ByteBuffer.wrap(sample.data),
                            bufferInfo
                        )
                    }
                }

                onFraction((pass + 1).toFloat() / totalPasses.toFloat())
            }

            isClosed = true
            muxer.close()
        } catch (e: Exception) {
            if (!isClosed) {
                isClosed = true
                runCatching { muxer.close() }
            }
            throw e
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) {
            runCatching { getInteger(key) }.getOrDefault(defaultValue)
        } else {
            defaultValue
        }
    }

    private fun renderStudioFrame(
        canvas: Canvas,
        frameIndex: Int,
        palettes: List<ScenePalette>,
        sessionTag: Int,
        topic: String,
        motionPhaseOffset: Float
    ) {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val tSec = frameIndex.toFloat() / FPS.toFloat()
        val sceneIndex = ((tSec / 4f).toInt()).coerceIn(0, palettes.lastIndex)
        val palette = palettes[sceneIndex]
        val phase = tSec * 2.2f + motionPhaseOffset

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w, h,
                palette.topColor, palette.bottomColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Floating ambient light orbs
        val orb1X = w * (0.28f + 0.14f * cos(phase * 0.7f))
        val orb1Y = h * (0.30f + 0.12f * sin(phase * 0.9f))
        val orb1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                orb1X, orb1Y, w * 0.42f,
                intArrayOf(applyAlpha(palette.accentA, 120), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(orb1X, orb1Y, w * 0.42f, orb1Paint)

        val orb2X = w * (0.74f + 0.12f * sin(phase * 0.65f))
        val orb2Y = h * (0.68f + 0.10f * cos(phase * 0.8f))
        val orb2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                orb2X, orb2Y, w * 0.36f,
                intArrayOf(applyAlpha(palette.accentB, 100), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(orb2X, orb2Y, w * 0.36f, orb2Paint)

        // Subtle broadcast rule-of-thirds grid
        val gridPaint = Paint().apply {
            color = Color.argb(24, 255, 255, 255)
            strokeWidth = 1.5f
        }
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)

        // Stylized presenter silhouette shifting per scene (great for 9:16 reframe testing)
        val subjectX = w * (0.5f + palette.speakerShiftX + 0.025f * sin(phase))
        val headY = h * 0.40f
        val headR = h * 0.125f

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 40, 62)
        }
        val shouldersRect = RectF(
            subjectX - headR * 2.1f,
            headY + headR * 1.05f,
            subjectX + headR * 2.1f,
            h + 24f
        )
        canvas.drawRoundRect(shouldersRect, 54f, 54f, bodyPaint)

        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 60, 90)
        }
        canvas.drawCircle(subjectX, headY, headR, headPaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = palette.accentA
        }
        canvas.drawCircle(subjectX, headY, headR + 2f, rimPaint)

        // Kinetic audio equalizer bars along the lower third
        val barCount = 28
        val totalBarW = w * 0.72f
        val startX = (w - totalBarW) / 2f
        val slotW = totalBarW / barCount
        val baseY = h * 0.80f
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (i in 0 until barCount) {
            val wave = abs(sin(phase * 1.9f + i * 0.45f))
            val barH = 14f + 58f * wave
            barPaint.color = if (i % 2 == 0) palette.accentB else palette.accentA
            barPaint.alpha = 210
            canvas.drawRoundRect(
                RectF(
                    startX + i * slotW,
                    baseY - barH / 2f,
                    startX + i * slotW + slotW * 0.58f,
                    baseY + barH / 2f
                ),
                5f,
                5f,
                barPaint
            )
        }

        // Top studio pill badge
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 11, 13, 18)
        }
        canvas.drawRoundRect(RectF(24f, 20f, 360f, 64f), 22f, 22f, badgePaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentB
        }
        canvas.drawCircle(46f, 42f, 6.5f, dotPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("$topic  #$sessionTag", 64f, 48f, titlePaint)

        // Chapter banner text
        val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentB
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(palette.label, w / 2f, h * 0.93f, chapterPaint)
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun writeArgbToYuvImage(
        argb: IntArray,
        width: Int,
        height: Int,
        image: android.media.Image
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var argbIdx = 0
        for (j in 0 until height) {
            val yRowOffset = j * yRowStride
            val uvRow = j shr 1
            val uRowOffset = uvRow * uRowStride
            val vRowOffset = uvRow * vRowStride
            val sampleUvRow = (j and 1) == 0

            for (i in 0 until width) {
                val px = argb[argbIdx++]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                val yPos = yRowOffset + i * yPixelStride
                if (yPos < yLimit) {
                    val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                    yBuf.put(yPos, yVal.toByte())
                }

                if (sampleUvRow && (i and 1) == 0) {
                    val uvCol = i shr 1
                    val uPos = uRowOffset + uvCol * uPixelStride
                    val vPos = vRowOffset + uvCol * vPixelStride
                    if (uPos < uLimit && vPos < vLimit) {
                        val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                        val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                        uBuf.put(uPos, uVal.toByte())
                        vBuf.put(vPos, vVal.toByte())
                    }
                }
            }
        }
    }

    private fun writeArgbToI420Buffer(
        argb: IntArray,
        width: Int,
        height: Int,
        buffer: ByteBuffer
    ) {
        buffer.clear()
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val cap = buffer.capacity()
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + chromaSize
        var argbIdx = 0

        for (j in 0 until height) {
            val sampleUvRow = (j and 1) == 0
            for (i in 0 until width) {
                val px = argb[argbIdx++]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                if (yIndex < cap) {
                    val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                    buffer.put(yIndex++, yVal.toByte())
                }

                if (sampleUvRow && (i and 1) == 0) {
                    val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    if (uIndex < cap) buffer.put(uIndex++, uVal.toByte())
                    if (vIndex < cap) buffer.put(vIndex++, vVal.toByte())
                }
            }
        }
        buffer.position(0)
        buffer.limit((frameSize + chromaSize * 2).coerceAtMost(cap))
    }

    private suspend fun saveToAndroidMediaStore(sourceFile: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/ClipPulse"
                    )
                    put(MediaStore.Video.Media.DURATION, TARGET_DURATION_MS)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create MediaStore video entry.")

                try {
                    resolver.openOutputStream(itemUri)?.use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Could not open MediaStore output stream.")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, completeValues, null, null)
                    itemUri
                } catch (e: Exception) {
                    resolver.delete(itemUri, null, null)
                    throw e
                }
            } else {
                val savedDir = File(context.filesDir, "sample_media").apply { mkdirs() }
                val dest = File(savedDir, displayName)
                sourceFile.copyTo(dest, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            }
        }
}
