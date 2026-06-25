package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.arkeosar.groundscan.render.AnomalyColorScale
import kotlin.math.roundToInt

/**
 * Vertical color-scale legend, matching the colorbar shown alongside
 * ArkeoMag / Thuban Lodestar's 3D surface view: a gradient strip with
 * tick labels for the value at each gradient stop, plus the percentage
 * of scanned points falling in each band. Drawn entirely with Canvas -
 * no third-party charting library needed for something this simple.
 */
class ColorbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var rangeMin: Float = 0f
    private var rangeMax: Float = 1f
    private var bandPercentages: FloatArray = FloatArray(5)

    private val barPaint = Paint()
    private val textPaint = Paint().apply {
        color = 0xFFEDEFEE.toInt() // text_primary
        textSize = context.resources.displayMetrics.density * 12f
        isAntiAlias = true
    }
    private val percentPaint = Paint().apply {
        color = 0xFF8FA39D.toInt() // text_secondary
        textSize = context.resources.displayMetrics.density * 11f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }

    /**
     * @param min/max the value range currently mapped onto the gradient
     *   (should match what [com.arkeosar.groundscan.render.HeightmapMesh]
     *   used for its color scale, so the legend and surface agree).
     * @param values all currently-filled grid values, used to compute the
     *   percentage of points falling in each of 5 equal value bands.
     */
    fun update(min: Float, max: Float, values: List<Float>) {
        rangeMin = min
        rangeMax = if (max > min) max else min + 1f

        val bandCount = bandPercentages.size
        val counts = IntArray(bandCount)
        if (values.isNotEmpty()) {
            val span = rangeMax - rangeMin
            for (v in values) {
                val t = ((v - rangeMin) / span).coerceIn(0f, 0.999999f)
                val band = (t * bandCount).toInt().coerceIn(0, bandCount - 1)
                counts[band]++
            }
            for (i in 0 until bandCount) {
                bandPercentages[i] = counts[i] * 100f / values.size
            }
        } else {
            bandPercentages.fill(0f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val barLeft = width * 0.18f
        val barRight = width * 0.42f
        val barTop = height * 0.04f
        val barBottom = height * 0.96f

        // Gradient samples the currently active palette at evenly spaced
        // points, drawn top(high) to bottom(low) since that's how the
        // reference colorbar reads. Sampling (rather than reusing the
        // palette's raw stop list) keeps this correct for palettes with
        // different stop counts/spacing.
        val sampleCount = 12
        val colors = IntArray(sampleCount) { i ->
            val t = 1f - i / (sampleCount - 1).toFloat() // 1 (top) -> 0 (bottom)
            colorToArgb(AnomalyColorScale.colorFor(t))
        }
        val positions = FloatArray(sampleCount) { it / (sampleCount - 1).toFloat() }
        barPaint.shader = LinearGradient(0f, barTop, 0f, barBottom, colors, positions, Shader.TileMode.CLAMP)
        canvas.drawRect(barLeft, barTop, barRight, barBottom, barPaint)

        // Value tick labels (5 evenly spaced stops, high at top).
        val tickCount = 5
        for (i in 0 until tickCount) {
            val t = i / (tickCount - 1).toFloat()
            val value = rangeMax - t * (rangeMax - rangeMin)
            val y = barTop + t * (barBottom - barTop)
            canvas.drawText(formatValue(value), barRight + 8f, y + textPaint.textSize * 0.35f, textPaint)
        }

        // Band percentage labels to the left of the bar (top = highest band).
        val bandCount = bandPercentages.size
        for (i in 0 until bandCount) {
            val bandTopT = i / bandCount.toFloat()
            val bandBottomT = (i + 1) / bandCount.toFloat()
            val yCenter = barTop + (bandTopT + bandBottomT) / 2f * (barBottom - barTop)
            val percent = bandPercentages[bandCount - 1 - i] // reverse: band 0 in data is the lowest value
            canvas.drawText("%${percent.roundToInt().toString().padStart(2, '0')}", barLeft - 8f, yCenter + percentPaint.textSize * 0.35f, percentPaint)
        }
    }

    private fun formatValue(v: Float): String {
        return if (kotlin.math.abs(v) >= 100f) v.roundToInt().toString() else String.format("%.1f", v)
    }

    private fun colorToArgb(c: AnomalyColorScale.Rgb): Int {
        val r = (c.r * 255).roundToInt().coerceIn(0, 255)
        val g = (c.g * 255).roundToInt().coerceIn(0, 255)
        val b = (c.b * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
