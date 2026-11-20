package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.example.R
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset

@androidx.annotation.OptIn(UnstableApi::class)
class CaptionCanvasOverlay(
    context: Context,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val captions: List<CaptionChunk>,
    private val style: CaptionStylePreset,
    private val yPositionFraction: Float,
    private val uppercase: Boolean
) : BitmapOverlay() {

    private val montserratTypeface: Typeface = try {
        Typeface.create(
            ResourcesCompat.getFont(context, R.font.montserrat) ?: Typeface.DEFAULT_BOLD,
            Typeface.BOLD
        )
    } catch (_: Exception) {
        Typeface.DEFAULT_BOLD
    }

    private val interTypeface: Typeface = try {
        Typeface.create(
            ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.SANS_SERIF,
            Typeface.BOLD
        )
    } catch (_: Exception) {
        Typeface.SANS_SERIF
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val overlayBitmap: Bitmap = Bitmap.createBitmap(
        frameWidth.coerceAtLeast(360),
        frameHeight.coerceAtLeast(360),
        Bitmap.Config.ARGB_8888
    )
    private val overlayCanvas = Canvas(overlayBitmap)
    private var lastDrawnChunkIndex: Int = Int.MIN_VALUE

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1_000L
        val chunkIndex = if (style == CaptionStylePreset.NO_CAPTIONS || captions.isEmpty()) {
            -1
        } else {
            captions.indexOfFirst { timeMs in it.startMs..it.endMs }
        }

        if (chunkIndex == lastDrawnChunkIndex) {
            return overlayBitmap
        }
        lastDrawnChunkIndex = chunkIndex

        // Clear previous frame's caption pixels cleanly
        overlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (chunkIndex < 0) return overlayBitmap

        drawCaptionChunk(overlayCanvas, captions[chunkIndex])
        return overlayBitmap
    }

    private fun drawCaptionChunk(canvas: Canvas, activeChunk: CaptionChunk) {
        val rawText = if (uppercase) activeChunk.text.uppercase() else activeChunk.text
        val words = rawText.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val highlightTarget = activeChunk.highlightWord
            .trim()
            .replace(Regex("[^A-Za-z0-9]"), "")
            .lowercase()

        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val baseTextSize = (width * when (style) {
            CaptionStylePreset.HORMOZI_BOLD -> 0.082f
            CaptionStylePreset.NEON_POP -> 0.072f
            CaptionStylePreset.MINIMAL_CLEAN -> 0.056f
            CaptionStylePreset.NO_CAPTIONS -> 0.06f
        }).coerceIn(28f, 108f)

        val typeface = when (style) {
            CaptionStylePreset.MINIMAL_CLEAN -> interTypeface
            else -> montserratTypeface
        }

        fillPaint.typeface = typeface
        fillPaint.textSize = baseTextSize
        strokePaint.typeface = typeface
        strokePaint.textSize = baseTextSize
        strokePaint.strokeWidth = baseTextSize * 0.20f

        val maxLineWidth = width * 0.86f
        val spaceWidth = fillPaint.measureText(" ")
        val lines = wrapWords(words, maxLineWidth, spaceWidth)

        val fm = fillPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.22f
        val totalHeight = lineHeight * lines.size
        val centerY = height * yPositionFraction.coerceIn(0.18f, 0.88f)
        val startTop = centerY - totalHeight / 2f

        if (style == CaptionStylePreset.MINIMAL_CLEAN) {
            val widestLine = lines.maxOf { measureLineWidth(it, spaceWidth) }
            val padH = baseTextSize * 0.65f
            val padV = baseTextSize * 0.38f
            val pillRect = RectF(
                (width - widestLine) / 2f - padH,
                startTop - padV,
                (width + widestLine) / 2f + padH,
                startTop + totalHeight + padV * 0.6f
            )
            bgPaint.shader = null
            bgPaint.color = Color.argb(185, 11, 13, 18)
            canvas.drawRoundRect(pillRect, baseTextSize * 0.45f, baseTextSize * 0.45f, bgPaint)
        }

        lines.forEachIndexed { lineIndex, lineWords ->
            val lineW = measureLineWidth(lineWords, spaceWidth)
            var cursorX = (width - lineW) / 2f
            val baselineY = startTop + lineIndex * lineHeight - fm.ascent

            lineWords.forEach { word ->
                val cleanWord = word.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
                val isHighlight = highlightTarget.isNotEmpty() &&
                    (cleanWord == highlightTarget || cleanWord.contains(highlightTarget))
                val wordWidth = fillPaint.measureText(word)

                when (style) {
                    CaptionStylePreset.HORMOZI_BOLD -> {
                        strokePaint.color = Color.BLACK
                        canvas.drawText(word, cursorX, baselineY, strokePaint)
                        fillPaint.color = if (isHighlight) Color.parseColor("#A3E635") else Color.WHITE
                        canvas.drawText(word, cursorX, baselineY, fillPaint)
                    }

                    CaptionStylePreset.NEON_POP -> {
                        if (isHighlight) {
                            val padX = baseTextSize * 0.22f
                            val padY = baseTextSize * 0.14f
                            val rect = RectF(
                                cursorX - padX,
                                baselineY + fm.ascent - padY,
                                cursorX + wordWidth + padX,
                                baselineY + fm.descent + padY
                            )
                            bgPaint.shader = LinearGradient(
                                rect.left,
                                rect.top,
                                rect.right,
                                rect.bottom,
                                Color.parseColor("#22D3EE"),
                                Color.parseColor("#EC4899"),
                                Shader.TileMode.CLAMP
                            )
                            canvas.drawRoundRect(rect, baseTextSize * 0.22f, baseTextSize * 0.22f, bgPaint)
                        }
                        strokePaint.color = Color.argb(220, 11, 13, 18)
                        strokePaint.strokeWidth = baseTextSize * 0.12f
                        canvas.drawText(word, cursorX, baselineY, strokePaint)
                        fillPaint.color = Color.WHITE
                        canvas.drawText(word, cursorX, baselineY, fillPaint)
                    }

                    CaptionStylePreset.MINIMAL_CLEAN -> {
                        fillPaint.color = if (isHighlight) Color.parseColor("#A3E635") else Color.WHITE
                        canvas.drawText(word, cursorX, baselineY, fillPaint)
                    }

                    CaptionStylePreset.NO_CAPTIONS -> Unit
                }

                cursorX += wordWidth + spaceWidth
            }
        }
    }

    private fun wrapWords(words: List<String>, maxWidth: Float, spaceWidth: Float): List<List<String>> {
        val lines = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentWidth = 0f

        words.forEach { word ->
            val w = fillPaint.measureText(word)
            val needed = if (current.isEmpty()) w else currentWidth + spaceWidth + w
            if (current.isNotEmpty() && needed > maxWidth) {
                lines += current
                current = mutableListOf(word)
                currentWidth = w
            } else {
                current += word
                currentWidth = needed
            }
        }
        if (current.isNotEmpty()) {
            lines += current
        }
        return lines
    }

    private fun measureLineWidth(words: List<String>, spaceWidth: Float): Float {
        if (words.isEmpty()) return 0f
        var sum = 0f
        words.forEachIndexed { index, word ->
            sum += fillPaint.measureText(word)
            if (index < words.lastIndex) sum += spaceWidth
        }
        return sum
    }
}
