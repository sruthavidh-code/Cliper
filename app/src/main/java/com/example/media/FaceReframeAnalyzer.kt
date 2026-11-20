package com.example.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "FaceReframeAnalyzer"

class FaceReframeAnalyzer(private val context: Context) {

    /**
     * Samples up to 6 frames across [startMs..endMs], detects faces on-device via Google ML Kit,
     * and returns a normalized horizontal focal point in [-1.0f, +1.0f] where 0f is center.
     * Falls back cleanly to 0f (center crop) when no face is detected or on any read error.
     */
    suspend fun computeSmartHorizontalFocus(
        videoUri: Uri,
        startMs: Long,
        endMs: Long
    ): Float = withContext(Dispatchers.Default) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()

        val detector = FaceDetection.getClient(options)
        val retriever = MediaMetadataRetriever()
        val detectedCenters = mutableListOf<Float>()

        try {
            retriever.setDataSource(context, videoUri)
            val safeStart = startMs.coerceAtLeast(0L)
            val safeEnd = endMs.coerceAtLeast(safeStart + 1_000L)
            val sampleCount = 6
            val span = (safeEnd - safeStart).coerceAtLeast(1_000L)

            for (i in 0 until sampleCount) {
                val fraction = (i + 0.5f) / sampleCount.toFloat()
                val timeUs = (safeStart + (span * fraction).toLong()) * 1_000L
                val bitmap = try {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    Log.w(TAG, "Frame sample failed at ${timeUs}us", e)
                    null
                } ?: continue

                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val faces = detector.process(image).await()
                    val primaryFace = faces.maxByOrNull { face ->
                        face.boundingBox.width() * face.boundingBox.height()
                    }
                    if (primaryFace != null && bitmap.width > 0) {
                        val centerRatio = primaryFace.boundingBox.exactCenterX() / bitmap.width.toFloat()
                        // Map [0..1] horizontal position to [-1..+1] offset from center
                        val normalizedOffset = ((centerRatio - 0.5f) * 2f).coerceIn(-0.85f, 0.85f)
                        detectedCenters += normalizedOffset
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit face detection failed on frame $i", e)
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open video for ML Kit face tracking; falling back to center crop", e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            try {
                detector.close()
            } catch (_: Exception) {
            }
        }

        if (detectedCenters.isEmpty()) {
            0f
        } else {
            val sorted = detectedCenters.sorted()
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2f
            }
            median.coerceIn(-0.85f, 0.85f)
        }
    }
}
