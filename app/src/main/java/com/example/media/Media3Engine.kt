package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLDisplay
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.model.AspectRatioOption
import com.example.model.CaptionStylePreset
import com.example.model.ExportResolution
import com.example.model.ViralClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Media3Engine"
private const val MAX_DIRECT_INLINE_BYTES = 12L * 1024L * 1024L // 12 MB direct upload (avoids unnecessary proxy transcode on compact 5-min videos)
private const val MAX_INLINE_GEMINI_BYTES = 14L * 1024L * 1024L // 14 MB hard ceiling (base64 stays well under 20 MB)

data class PreparedVideoPayload(
    val bytes: ByteArray,
    val mimeType: String,
    val wasCompressedProxy: Boolean
)

enum class ExportStage(val shortLabel: String, val notificationLabel: String) {
    PREPARING("Preparing", "Preparing media & framing"),
    ENCODING("Encoding", "Encoding video & burning captions"),
    SAVING("Saving", "Saving MP4 to Movies/ClipPulse")
}

data class Media3ExportProgress(
    val stage: ExportStage,
    val progressPercent: Int,
    val isIndeterminate: Boolean
)

private enum class TransformerPassOutcome {
    COMPLETED,
    PAUSED
}

/**
 * Wraps Media3's [DefaultGlObjectsProvider] so the active EGL draw/read surfaces are always
 * unbound before EGL context destruction, preventing dequeued BufferQueue warnings on emulators.
 */
@OptIn(UnstableApi::class)
private class SafeGlObjectsProvider(
    private val delegate: GlObjectsProvider = DefaultGlObjectsProvider()
) : GlObjectsProvider by delegate {
    override fun release(eglDisplay: EGLDisplay) {
        runCatching {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        }
        delegate.release(eglDisplay)
    }
}

@OptIn(UnstableApi::class)
class Media3Engine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val transformerMutex = Mutex()
    private val activeTransformer = AtomicReference<Transformer?>(null)

    private fun cancelTransformerOnMain(transformer: Transformer) {
        val action: () -> Unit = {
            try {
                transformer.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling active Media3 Transformer", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun cancelActiveOperation() {
        val current = activeTransformer.getAndSet(null) ?: return
        cancelTransformerOnMain(current)
    }

    /**
     * Reads compact videos (<= 12 MB) directly for instant Gemini upload with zero transcoding,
     * or compresses larger/longer videos in a single 1 FPS 240p low-bitrate pass (matching
     * Gemini's 1 FPS video ingestion) so large recordings stay well within inline limits.
     */
    suspend fun prepareVideoPayloadForGemini(
        sourceUri: Uri,
        onProxyCompressing: (percent: Int) -> Unit
    ): PreparedVideoPayload {
        val rawSize = queryFileSize(sourceUri)
        if (rawSize in 1L..MAX_DIRECT_INLINE_BYTES) {
            val bytes = readUriBytes(sourceUri)
            val mime = context.contentResolver.getType(sourceUri) ?: MimeTypes.VIDEO_MP4
            return PreparedVideoPayload(
                bytes = bytes,
                mimeType = mime,
                wasCompressedProxy = false
            )
        }

        // Single-pass ultra-compact 240p @ 1 FPS (120 kbps) AI proxy in app cache
        val proxyDir = File(context.cacheDir, "ai_proxy").apply { mkdirs() }
        val proxyFile = File(proxyDir, "proxy_${System.currentTimeMillis()}.mp4")
        try {
            onProxyCompressing(0)
            transcodeProxy(
                sourceUri = sourceUri,
                outputFile = proxyFile,
                targetHeight = 240,
                targetFps = 1.0f,
                targetVideoBitrate = 120_000,
                onProgress = onProxyCompressing
            )

            if (proxyFile.length() > MAX_INLINE_GEMINI_BYTES) {
                // Ultra-long video fallback: 180p @ 0.5 FPS (80 kbps)
                val ultraProxy = File(proxyDir, "proxy_180_${System.currentTimeMillis()}.mp4")
                try {
                    transcodeProxy(
                        sourceUri = sourceUri,
                        outputFile = ultraProxy,
                        targetHeight = 180,
                        targetFps = 0.5f,
                        targetVideoBitrate = 80_000,
                        onProgress = onProxyCompressing
                    )
                    if (ultraProxy.length() > MAX_INLINE_GEMINI_BYTES) {
                        throw IllegalStateException(
                            "This video is too long for inline AI analysis (>14 MB even after ultra-compact proxy compression). Please import a shorter recording."
                        )
                    }
                    val bytes = withContext(Dispatchers.IO) { ultraProxy.readBytes() }
                    return PreparedVideoPayload(
                        bytes = bytes,
                        mimeType = MimeTypes.VIDEO_MP4,
                        wasCompressedProxy = true
                    )
                } finally {
                    ultraProxy.delete()
                }
            }

            val bytes = withContext(Dispatchers.IO) { proxyFile.readBytes() }
            return PreparedVideoPayload(
                bytes = bytes,
                mimeType = MimeTypes.VIDEO_MP4,
                wasCompressedProxy = true
            )
        } finally {
            proxyFile.delete()
        }
    }

    /**
     * Trims, crops/scales (with focal pan & zoom), burns styled captions via CanvasOverlay,
     * supports true pause/resume via Media3 Transformer.resume() (with automatic fallback),
     * and saves the exported MP4 cleanly into Movies/ClipPulse via Scoped Storage MediaStore.
     */
    suspend fun exportClipToGallery(
        sourceUri: Uri,
        clip: ViralClip,
        sourceWidth: Int,
        sourceHeight: Int,
        resolution: ExportResolution,
        isPausedFlow: StateFlow<Boolean> = MutableStateFlow(false),
        onProgress: (Media3ExportProgress) -> Unit
    ): Uri {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = clip.title
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(36)
            .ifBlank { "ClipPulse_Short" }
        val baseTimestamp = System.currentTimeMillis()
        val finalDisplayName = "${safeTitle}_$baseTimestamp.mp4"

        var currentOutputFile: File? = null
        var checkpointFile: File? = null

        try {
            onProgress(
                Media3ExportProgress(
                    stage = ExportStage.PREPARING,
                    progressPercent = 0,
                    isIndeterminate = true
                )
            )

            val (outWidth, outHeight) = computeOutputDimensions(clip.aspectRatio, resolution)
            val cropEffect = buildCropEffect(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetAspect = clip.aspectRatio.ratio,
                panX = clip.effectivePanX,
                zoomScale = clip.zoomScale
            )

            val videoEffects = mutableListOf<Effect>()
            if (cropEffect != null) {
                videoEffects += cropEffect
            }
            videoEffects += Presentation.createForWidthAndHeight(
                outWidth,
                outHeight,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
            // Apply color grading effects (B&W / High Contrast / Noir) BEFORE burning captions
            videoEffects += VideoFilterEffects.buildExportEffects(clip.videoFilter)

            if (clip.captionStyle != CaptionStylePreset.NO_CAPTIONS && clip.captions.isNotEmpty()) {
                val captionOverlay = CaptionCanvasOverlay(
                    context = context,
                    frameWidth = outWidth,
                    frameHeight = outHeight,
                    captions = clip.captions,
                    style = clip.captionStyle,
                    yPositionFraction = clip.captionYPosition,
                    uppercase = clip.captionUppercase
                )
                videoEffects += OverlayEffect(listOf(captionOverlay))
            }

            val mediaItem = MediaItem.Builder()
                .setUri(sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs.coerceAtLeast(0L))
                        .setEndPositionMs(clip.endMs.coerceAtLeast(clip.startMs + 500L))
                        .build()
                )
                .build()

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val sequence = EditedMediaItemSequence.Builder(listOf(editedMediaItem)).build()
            val composition = Composition.Builder(sequence).build()

            var hasReceivedDeterminate = false
            var lastEncodedPercent = 0
            var passIndex = 0
            var exportCompleted = false

            while (!exportCompleted) {
                if (isPausedFlow.value) {
                    isPausedFlow.first { !it }
                }

                passIndex++
                val passOutputFile = File(exportDir, "${safeTitle}_${baseTimestamp}_p$passIndex.mp4")
                currentOutputFile = passOutputFile

                val progressCallback: (Int, Int) -> Unit = { progressState, rawPercent ->
                    if (!isPausedFlow.value) {
                        when (progressState) {
                            Transformer.PROGRESS_STATE_AVAILABLE -> {
                                hasReceivedDeterminate = true
                                // Reserve final 6% for atomic MediaStore gallery write
                                val scaled = (rawPercent * 0.94f).toInt().coerceIn(1, 94)
                                lastEncodedPercent = maxOf(lastEncodedPercent, scaled)
                                onProgress(
                                    Media3ExportProgress(
                                        stage = ExportStage.ENCODING,
                                        progressPercent = lastEncodedPercent,
                                        isIndeterminate = false
                                    )
                                )
                            }

                            Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY,
                            Transformer.PROGRESS_STATE_UNAVAILABLE -> {
                                if (!hasReceivedDeterminate) {
                                    onProgress(
                                        Media3ExportProgress(
                                            stage = ExportStage.PREPARING,
                                            progressPercent = 0,
                                            isIndeterminate = true
                                        )
                                    )
                                } else {
                                    onProgress(
                                        Media3ExportProgress(
                                            stage = ExportStage.ENCODING,
                                            progressPercent = lastEncodedPercent,
                                            isIndeterminate = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                val resumeCandidate = checkpointFile?.takeIf { it.exists() && it.length() > 1024L }
                val outcome = if (resumeCandidate != null) {
                    try {
                        runTransformerResumable(
                            composition = composition,
                            outputPath = passOutputFile.absolutePath,
                            resumeFromPath = resumeCandidate.absolutePath,
                            isPausedFlow = isPausedFlow,
                            onProgressState = progressCallback
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Early pause before a keyframe was flushed can prevent partial MP4 resume;
                        // allow hardware codec Surface buffers to drain cleanly before restarting.
                        Log.w(TAG, "Partial checkpoint resume unavailable; retrying cleanly from start", e)
                        resumeCandidate.delete()
                        checkpointFile = null
                        passOutputFile.delete()
                        delay(150L)
                        runTransformerResumable(
                            composition = composition,
                            outputPath = passOutputFile.absolutePath,
                            resumeFromPath = null,
                            isPausedFlow = isPausedFlow,
                            onProgressState = progressCallback
                        )
                    }
                } else {
                    runTransformerResumable(
                        composition = composition,
                        outputPath = passOutputFile.absolutePath,
                        resumeFromPath = null,
                        isPausedFlow = isPausedFlow,
                        onProgressState = progressCallback
                    )
                }

                when (outcome) {
                    TransformerPassOutcome.COMPLETED -> {
                        checkpointFile?.delete()
                        checkpointFile = null
                        exportCompleted = true
                    }

                    TransformerPassOutcome.PAUSED -> {
                        if (passOutputFile.exists() && passOutputFile.length() > 1024L) {
                            checkpointFile?.delete()
                            checkpointFile = passOutputFile
                        } else {
                            passOutputFile.delete()
                        }
                        currentOutputFile = null
                        // Wait cleanly with zero CPU usage until user resumes or cancels,
                        // then give the codec input Surface a brief settle window.
                        isPausedFlow.first { !it }
                        delay(150L)
                    }
                }
            }

            if (isPausedFlow.value) {
                isPausedFlow.first { !it }
            }

            val completedFile = currentOutputFile
                ?: throw IllegalStateException("Rendered MP4 output file missing after completion.")

            onProgress(
                Media3ExportProgress(
                    stage = ExportStage.SAVING,
                    progressPercent = 96,
                    isIndeterminate = false
                )
            )
            val savedUri = saveMp4ToMediaStore(completedFile, finalDisplayName)
            onProgress(
                Media3ExportProgress(
                    stage = ExportStage.SAVING,
                    progressPercent = 100,
                    isIndeterminate = false
                )
            )
            return savedUri
        } catch (e: CancellationException) {
            currentOutputFile?.delete()
            checkpointFile?.delete()
            throw e
        } catch (e: Exception) {
            currentOutputFile?.delete()
            checkpointFile?.delete()
            throw e
        }
    }

    private suspend fun transcodeProxy(
        sourceUri: Uri,
        outputFile: File,
        targetHeight: Int,
        targetFps: Float,
        targetVideoBitrate: Int,
        onProgress: (Int) -> Unit
    ) {
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        FrameDropEffect.createDefaultFrameDropEffect(targetFps),
                        Presentation.createForHeight(targetHeight)
                    )
                )
            )
            .build()
        runTransformer(
            editedMediaItem = editedItem,
            outputPath = outputFile.absolutePath,
            targetVideoBitrate = targetVideoBitrate,
            onProgressState = { progressState, rawPercent ->
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(rawPercent)
                }
            }
        )
    }

    private suspend fun runTransformer(
        editedMediaItem: EditedMediaItem,
        outputPath: String,
        targetVideoBitrate: Int? = null,
        onProgressState: (progressState: Int, rawPercent: Int) -> Unit
    ) = transformerMutex.withLock {
        withContext(Dispatchers.Main) {
            var progressJob: Job? = null
            try {
                suspendCancellableCoroutine { continuation ->
                val frameProcessorFactory = DefaultVideoFrameProcessor.Factory.Builder()
                    .setGlObjectsProvider(SafeGlObjectsProvider())
                    .build()

                val builder = Transformer.Builder(context)
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
                    .setVideoFrameProcessorFactory(frameProcessorFactory)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)

                if (targetVideoBitrate != null) {
                    val encoderSettings = VideoEncoderSettings.Builder()
                        .setBitrate(targetVideoBitrate)
                        .build()
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(encoderSettings)
                        .setEnableFallback(true)
                        .build()
                    builder.setEncoderFactory(encoderFactory)
                } else {
                    builder.setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setEnableFallback(true)
                            .build()
                    )
                }

                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            progressJob?.cancel()
                            activeTransformer.set(null)
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            progressJob?.cancel()
                            activeTransformer.set(null)
                            Log.e(TAG, "Media3 Transformer export failed", exportException)
                            if (continuation.isActive) {
                                val friendly = when (exportException.errorCode) {
                                    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                                    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                                        "This video codec is unsupported by the device hardware decoder. Try another video."
                                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                                        "Could not initialize MP4 encoder at this resolution. Try switching to 720p Fast."
                                    else ->
                                        exportException.localizedMessage
                                            ?: "Video export failed (${exportException.errorCodeName})."
                                }
                                continuation.resumeWithException(IllegalStateException(friendly, exportException))
                            }
                        }
                    })
                    .build()

                activeTransformer.set(transformer)

                continuation.invokeOnCancellation {
                    progressJob?.cancel()
                    activeTransformer.compareAndSet(transformer, null)
                    cancelTransformerOnMain(transformer)
                }

                transformer.start(editedMediaItem, outputPath)

                val holder = ProgressHolder()
                progressJob = launch {
                    while (isActive) {
                        val state = transformer.getProgress(holder)
                        val pct = if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            holder.progress.coerceIn(0, 100)
                        } else {
                            0
                        }
                        onProgressState(state, pct)
                        delay(120L)
                    }
                }
            }
            } finally {
                progressJob?.cancel()
            }
        }
    }

    private suspend fun runTransformerResumable(
        composition: Composition,
        outputPath: String,
        resumeFromPath: String?,
        isPausedFlow: StateFlow<Boolean>,
        onProgressState: (progressState: Int, rawPercent: Int) -> Unit
    ): TransformerPassOutcome = transformerMutex.withLock {
        withContext(Dispatchers.Main) {
            var progressJob: Job? = null
            var pauseWatcherJob: Job? = null
            try {
                suspendCancellableCoroutine { continuation ->
                val pausedByUser = AtomicBoolean(false)

                val frameProcessorFactory = DefaultVideoFrameProcessor.Factory.Builder()
                    .setGlObjectsProvider(SafeGlObjectsProvider())
                    .build()

                val transformer = Transformer.Builder(context)
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
                    .setVideoFrameProcessorFactory(frameProcessorFactory)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setEnableFallback(true)
                            .build()
                    )
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                            progressJob?.cancel()
                            pauseWatcherJob?.cancel()
                            activeTransformer.set(null)
                            if (continuation.isActive) {
                                continuation.resume(TransformerPassOutcome.COMPLETED)
                            }
                        }

                        override fun onError(
                            comp: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            progressJob?.cancel()
                            pauseWatcherJob?.cancel()
                            activeTransformer.set(null)
                            if (pausedByUser.get()) {
                                if (continuation.isActive) {
                                    continuation.resume(TransformerPassOutcome.PAUSED)
                                }
                                return
                            }
                            Log.e(TAG, "Media3 Transformer export failed", exportException)
                            if (continuation.isActive) {
                                val friendly = when (exportException.errorCode) {
                                    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                                    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                                        "This video codec is unsupported by the device hardware decoder. Try another video."
                                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                                        "Could not initialize MP4 encoder at this resolution. Try switching to 720p Fast."
                                    else ->
                                        exportException.localizedMessage
                                            ?: "Video export failed (${exportException.errorCodeName})."
                                }
                                continuation.resumeWithException(IllegalStateException(friendly, exportException))
                            }
                        }
                    })
                    .build()

                activeTransformer.set(transformer)

                continuation.invokeOnCancellation {
                    progressJob?.cancel()
                    pauseWatcherJob?.cancel()
                    activeTransformer.compareAndSet(transformer, null)
                    cancelTransformerOnMain(transformer)
                }

                if (resumeFromPath != null) {
                    transformer.resume(composition, outputPath, resumeFromPath)
                } else {
                    transformer.start(composition, outputPath)
                }

                pauseWatcherJob = launch {
                    isPausedFlow.first { it }
                    pausedByUser.set(true)
                    progressJob?.cancel()
                    activeTransformer.compareAndSet(transformer, null)
                    cancelTransformerOnMain(transformer)
                    if (continuation.isActive) {
                        continuation.resume(TransformerPassOutcome.PAUSED)
                    }
                }

                val holder = ProgressHolder()
                progressJob = launch {
                    while (isActive && !pausedByUser.get()) {
                        val state = transformer.getProgress(holder)
                        val pct = if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            holder.progress.coerceIn(0, 100)
                        } else {
                            0
                        }
                        onProgressState(state, pct)
                        delay(120L)
                    }
                }
            }
            } finally {
                progressJob?.cancel()
                pauseWatcherJob?.cancel()
            }
        }
    }

    private suspend fun saveMp4ToMediaStore(sourceFile: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, MimeTypes.VIDEO_MP4)
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/ClipPulse"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create MediaStore entry in Movies/ClipPulse.")

                try {
                    resolver.openOutputStream(itemUri)?.use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Could not open gallery output stream.")

                    val completeValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, completeValues, null, null)
                    sourceFile.delete()
                    itemUri
                } catch (e: Exception) {
                    resolver.delete(itemUri, null, null)
                    throw e
                }
            } else {
                // Scoped app-private fallback on API 24-28 (zero legacy WRITE_EXTERNAL_STORAGE permission)
                val savedDir = File(context.filesDir, "exports").apply { mkdirs() }
                val dest = File(savedDir, displayName)
                sourceFile.copyTo(dest, overwrite = true)
                sourceFile.delete()
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            }
        }

    private fun computeOutputDimensions(
        aspectRatio: AspectRatioOption,
        resolution: ExportResolution
    ): Pair<Int, Int> {
        val base = resolution.shortEdgePx
        return when (aspectRatio) {
            AspectRatioOption.VERTICAL_9_16 -> base to ((base * 16) / 9)
            AspectRatioOption.SQUARE_1_1 -> base to base
            AspectRatioOption.ORIGINAL_16_9 -> ((base * 16) / 9) to base
        }
    }

    /**
     * Computes Media3 Crop(left, right, bottom, top) in normalized [-1, +1] device coordinates
     * applying aspect ratio framing, horizontal panX (-1..+1), and zoomScale (1.0..1.5).
     */
    private fun buildCropEffect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: Float,
        panX: Float,
        zoomScale: Float
    ): Crop? {
        val safeW = sourceWidth.coerceAtLeast(1).toFloat()
        val safeH = sourceHeight.coerceAtLeast(1).toFloat()
        val srcAspect = safeW / safeH
        val safeZoom = zoomScale.coerceIn(1.0f, 1.5f)

        var widthFraction = if (srcAspect > targetAspect) {
            (targetAspect / srcAspect)
        } else {
            1.0f
        }
        var heightFraction = if (srcAspect < targetAspect) {
            (srcAspect / targetAspect)
        } else {
            1.0f
        }

        widthFraction = (widthFraction / safeZoom).coerceIn(0.2f, 1.0f)
        heightFraction = (heightFraction / safeZoom).coerceIn(0.2f, 1.0f)

        // NDC span is 2.0 (-1..+1)
        val halfSpanX = widthFraction
        val halfSpanY = heightFraction
        val maxShiftX = (1.0f - halfSpanX).coerceAtLeast(0f)
        val centerX = (panX.coerceIn(-1f, 1f) * maxShiftX)

        val left = (centerX - halfSpanX).coerceIn(-1f, 1f)
        val right = (centerX + halfSpanX).coerceIn(-1f, 1f)
        val bottom = (-halfSpanY).coerceIn(-1f, 1f)
        val top = halfSpanY.coerceIn(-1f, 1f)

        if (left <= -0.999f && right >= 0.999f && bottom <= -0.999f && top >= 0.999f) {
            return null
        }
        return Crop(left, right, bottom, top)
    }

    private suspend fun queryFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private suspend fun readUriBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read selected video file.")
    }
}
