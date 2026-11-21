package com.example.media

import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbFilter
import com.example.model.VideoFilterPreset

/**
 * Shared color-grading effect factory mapping ClipPulse's [VideoFilterPreset] to:
 * 1) Hardware-accelerated Android [ColorMatrixColorFilter] for TextureView RenderEffect preview (API 31+)
 * 2) Media3 OpenGL [Effect]s for legacy preview fallback (API < 31) and final Media3 Transformer MP4 export
 * 3) Matching Compose [ColorFilter] & CSS filter rules for Demo Mode and YouTube preview parity.
 */
@OptIn(UnstableApi::class)
object VideoFilterEffects {

    /**
     * Media3 Effects injected into Transformer BEFORE caption OverlayEffect so burned captions
     * retain full electric colors on top of graded footage.
     */
    fun buildExportEffects(preset: VideoFilterPreset): List<Effect> = when (preset) {
        VideoFilterPreset.NONE -> emptyList()
        VideoFilterPreset.BLACK_AND_WHITE -> listOf(
            RgbFilter.createGrayscaleFilter()
        )
        VideoFilterPreset.HIGH_CONTRAST -> listOf(
            Contrast(0.42f),
            HslAdjustment.Builder()
                .adjustSaturation(18f)
                .build()
        )
        VideoFilterPreset.DRAMATIC_NOIR -> listOf(
            RgbFilter.createGrayscaleFilter(),
            Contrast(0.45f)
        )
    }

    /**
     * Media3 Effects for API < 31 ExoPlayer preview fallback.
     * Returns emptyList() for NONE so ExoPlayer never spins up an unnecessary OpenGL VideoFrameProcessor.
     */
    fun buildPreviewEffects(preset: VideoFilterPreset): List<Effect> = buildExportEffects(preset)

    /**
     * Native Android ColorMatrixColorFilter for API 31+ TextureView.setRenderEffect(...) previews,
     * delivering instant 0ms color grading without spinning up a Media3 OpenGL VideoFrameProcessor.
     */
    fun androidColorFilter(preset: VideoFilterPreset): ColorMatrixColorFilter? = when (preset) {
        VideoFilterPreset.NONE -> null
        VideoFilterPreset.BLACK_AND_WHITE -> {
            val matrix = AndroidColorMatrix().apply { setSaturation(0f) }
            ColorMatrixColorFilter(matrix)
        }
        VideoFilterPreset.HIGH_CONTRAST -> {
            val matrix = createAndroidContrastMatrix(scale = 1.42f)
            val sat = AndroidColorMatrix().apply { setSaturation(1.18f) }
            matrix.postConcat(sat)
            ColorMatrixColorFilter(matrix)
        }
        VideoFilterPreset.DRAMATIC_NOIR -> {
            val matrix = createAndroidContrastMatrix(scale = 1.45f)
            val gray = AndroidColorMatrix().apply { setSaturation(0f) }
            matrix.postConcat(gray)
            ColorMatrixColorFilter(matrix)
        }
    }

    /**
     * Matching Compose ColorMatrix filter so Demo Mode canvas previews mirror Media3 Effects accurately.
     */
    fun composeColorFilter(preset: VideoFilterPreset): ColorFilter? = when (preset) {
        VideoFilterPreset.NONE -> null
        VideoFilterPreset.BLACK_AND_WHITE -> {
            val matrix = ColorMatrix().apply { setToSaturation(0f) }
            ColorFilter.colorMatrix(matrix)
        }
        VideoFilterPreset.HIGH_CONTRAST -> {
            val contrastMatrix = createContrastMatrix(scale = 1.42f)
            val saturationMatrix = ColorMatrix().apply { setToSaturation(1.18f) }
            contrastMatrix.timesAssign(saturationMatrix)
            ColorFilter.colorMatrix(contrastMatrix)
        }
        VideoFilterPreset.DRAMATIC_NOIR -> {
            val contrastMatrix = createContrastMatrix(scale = 1.45f)
            val grayscaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
            contrastMatrix.timesAssign(grayscaleMatrix)
            ColorFilter.colorMatrix(contrastMatrix)
        }
    }

    /**
     * Matching CSS filter rule for embedded YouTube IFrame preview parity.
     */
    fun cssFilterRule(preset: VideoFilterPreset): String = when (preset) {
        VideoFilterPreset.NONE -> "none"
        VideoFilterPreset.BLACK_AND_WHITE -> "grayscale(100%)"
        VideoFilterPreset.HIGH_CONTRAST -> "contrast(142%) saturate(118%)"
        VideoFilterPreset.DRAMATIC_NOIR -> "grayscale(100%) contrast(145%)"
    }

    private fun createAndroidContrastMatrix(scale: Float): AndroidColorMatrix {
        val translate = (-0.5f * scale + 0.5f) * 255f
        return AndroidColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun createContrastMatrix(scale: Float): ColorMatrix {
        val translate = (-0.5f * scale + 0.5f) * 255f
        return ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
