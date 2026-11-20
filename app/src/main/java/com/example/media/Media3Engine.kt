package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.model.AspectRatioOption
import com.example.model.CaptionStylePreset
import com.example.model.ExportResolution
import com.example.model.ViralClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Media3Engine"
private const val MAX_INLINE_GEMINI_BYTES = 18L * 1024L * 1024L // 18 MB safety ceiling under 20 MB limit

data class PreparedVideoPayload(
    val bytes: ByteArray,
    val mimeType: String,
    val wasCompressedProxy: Boolean
)

@OptIn(UnstableApi::class)
class Media3Engine(private val context: Context) {

    private val activeTransformer = AtomicReference<Transformer?>(null)

    fun cancelActiveOperation() {
        val current = activeTransformer.getAndSet(null) ?: return
        try {
            current.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling active Media3 Transformer", e)
        }
    }

    /**
     * Reads imported video bytes directly when <= 18 MB, or automatically transcodes a compact
     * lightweight proxy MP4 via Media3 Transformer so Gemini stays safely below the 20 MB payload limit.
     */
    suspend fun prepareVideoPayloadForGemini(
        sourceUri: Uri,
        onProxyCompressing: (percent: Int) -> Unit
    ): PreparedVideoPayload {
        val rawSize = queryFileSize(sourceUri)
        if (rawSize in 1L..MAX_INLINE_GEMINI_BYTES) {
            val bytes = readUriBytes(sourceUri)
            val mime = context.contentResolver.getType(sourceUri) ?: MimeTypes.VIDEO_MP4
            return PreparedVideoPayload(
                bytes = bytes,
                mimeType = mime,
                wasCompressedProxy = false
            )
        }

        // Auto-compress a lightweight 360p proxy MP4 in app cache
        val proxyDir = File(context.cacheDir, "ai_proxy").apply { mkdirs() }
        val proxyFile = File(proxyDir, "proxy_${System.currentTimeMillis()}.mp4")
        try {
            onProxyCompressing(0)
            transcodeProxy(sourceUri, proxyFile, targetHeight = 360, onProgress = onProxyCompressing)

            if (proxyFile.length() > MAX_INLINE_GEMINI_BYTES) {
                // Second ultra-compact pass at 240p for very long videos
                val ultraProxy = File(proxyDir, "proxy_240_${System.currentTimeMillis()}.mp4")
                try {
                    transcodeProxy(sourceUri, ultraProxy, targetHeight = 240, onProgress = onProxyCompressing)
                    if (ultraProxy.length() > MAX_INLINE_GEMINI_BYTES) {
                        throw IllegalStateException(
                            "This video is too long for inline AI analysis (>20 MB even after 240p proxy compression). Please import a shorter recording."
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
     * and saves the exported MP4 cleanly into Movies/ClipPulse via Scoped Storage MediaStore.
     */
    suspend fun exportClipToGallery(
        sourceUri: Uri,
        clip: ViralClip,
        sourceWidth: Int,
        sourceHeight: Int,
        resolution: ExportResolution,
        onProgress: (Int) -> Unit
    ): Uri {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = clip.title
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(36)
            .ifBlank { "ClipPulse_Short" }
        val fileName = "${safeTitle}_${System.currentTimeMillis()}.mp4"
        val tempOutputFile = File(exportDir, fileName)

        try {
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

            runTransformer(editedMediaItem, tempOutputFile.absolutePath) { pct ->
                // Reserve final 5% for atomic MediaStore gallery commit
                onProgress((pct * 0.95f).toInt().coerceIn(0, 95))
            }

            val savedUri = saveMp4ToMediaStore(tempOutputFile, fileName)
            onProgress(100)
            return savedUri
        } catch (e: CancellationException) {
            tempOutputFile.delete()
            throw e
        } catch (e: Exception) {
            tempOutputFile.delete()
            throw e
        }
    }

    private suspend fun transcodeProxy(
        sourceUri: Uri,
        outputFile: File,
        targetHeight: Int,
        onProgress: (Int) -> Unit
    ) {
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForHeight(targetHeight))
                )
            )
            .build()
        runTransformer(editedItem, outputFile.absolutePath, onProgress)
    }

    private suspend fun runTransformer(
        editedMediaItem: EditedMediaItem,
        outputPath: String,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.Main) {
        var progressJob: Job? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                    try {
                        transformer.cancel()
                    } catch (_: Exception) {
                    }
                    activeTransformer.compareAndSet(transformer, null)
                }

                transformer.start(editedMediaItem, outputPath)

                val holder = ProgressHolder()
                progressJob = launch {
                    while (isActive) {
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress.coerceIn(0, 100))
                        }
                        delay(120L)
                    }
                }
            }
        } finally {
            progressJob?.cancel()
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
