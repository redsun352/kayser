package com.arkeosar.groundscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.arkeosar.groundscan.data.ScanAxis
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.render.AnomalyColorScale
import com.arkeosar.groundscan.render.ThinPlateSpline

/**
 * 2D detector grid view, modeled on ArkeoMag / Thuban Lodestar's
 * cell-by-cell scan screen: a grid of square cells, each colored by
 * interpolating the scan's filled readings (so the coloring updates
 * smoothly as more cells fill in, not just cell-by-cell flat fills),
 * with a chevron arrow marking the cell the scan is currently
 * positioned over and which direction the sweep is currently heading.
 *
 * This is a from-scratch Canvas-based implementation; it borrows the
 * *concept* (interpolated grid-fill detector view with a directional
 * cursor) from the reference app's behavior, not any of its code.
 */
class GridScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var grid: ScanGrid? = null
        set(value) {
            field = value
            invalidate()
        }

    private val cellBorderPaint = Paint().apply {
        color = 0xFF23332E.toInt() // grid_line
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1f
    }

    private val emptyCellPaint = Paint().apply {
        color = 0xFF141B19.toInt() // surface_panel
        style = Paint.Style.FILL
    }

    private val activeCellBgPaint = Paint().apply {
        color = 0xFF8FA39D.toInt() // text_secondary, used as the gray highlight behind the arrow
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint().apply {
        color = 0xFFE8E13D.toInt() // bright yellow, matching the reference app's cursor arrow
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cellPaint = Paint().apply { isAntiAlias = true }

    /** Triggers a fresh interpolation pass and redraw. Call after every new reading. */
    fun refresh() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentGrid = grid ?: return
        if (width == 0 || height == 0) return

        val cols = currentGrid.columns
        val rows = currentGrid.rows
        val cellWidth = width.toFloat() / cols
        val cellHeight = height.toFloat() / rows

        val filled = currentGrid.allPoints().filter { it.hasValue }
        val tps: ThinPlateSpline? = if (filled.size >= 3) {
            val xs = DoubleArray(filled.size) { filled[it].column.toDouble() }
            val ys = DoubleArray(filled.size) { filled[it].row.toDouble() }
            val zs = DoubleArray(filled.size) { filled[it].value.toDouble() }
            ThinPlateSpline.fit(xs, ys, zs, lambda = 0.05)
        } else null

        val rangeMin = if (filled.isEmpty()) 0f else filled.minOf { it.value }
        val rangeMaxRaw = if (filled.isEmpty()) 1f else filled.maxOf { it.value }
        val rangeMax = if (rangeMaxRaw == rangeMin) rangeMin + 1f else rangeMaxRaw

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = col * cellWidth
                val top = row * cellHeight
                val right = left + cellWidth
                val bottom = top + cellHeight

                val point = currentGrid.pointAt(row, col)
                if (point.hasValue) {
                    val rgb = AnomalyColorScale.colorForRaw(point.value, rangeMin, rangeMax)
                    cellPaint.color = rgbToColorInt(rgb)
                    canvas.drawRect(left, top, right, bottom, cellPaint)
                } else if (tps != null) {
                    // Not yet measured here, but enough data exists elsewhere
                    // to show a live-interpolated preview color, matching the
                    // reference app's smooth (not blocky) fill-as-you-go look.
                    val interpolated = tps.evaluate(col.toDouble(), row.toDouble()).toFloat()
                    val rgb = AnomalyColorScale.colorForRaw(interpolated, rangeMin, rangeMax)
                    cellPaint.color = rgbToColorInt(rgb)
                    cellPaint.alpha = 140 // dimmed: this is a preview, not a real reading yet
                    canvas.drawRect(left, top, right, bottom, cellPaint)
                    cellPaint.alpha = 255
                } else {
                    canvas.drawRect(left, top, right, bottom, emptyCellPaint)
                }

                canvas.drawRect(left, top, right, bottom, cellBorderPaint)
            }
        }

        drawActiveCellIndicator(canvas, currentGrid, cellWidth, cellHeight)
    }

    private fun drawActiveCellIndicator(canvas: Canvas, grid: ScanGrid, cellWidth: Float, cellHeight: Float) {
        val active = grid.currentPoint() ?: return
        val left = active.column * cellWidth
        val top = active.row * cellHeight

        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, activeCellBgPaint)

        // Arrow direction depends on scan axis and current sweep direction,
        // matching the reference app's up/down chevron for column-major
        // sweeps (up when heading toward row 0, down when heading away).
        val pointingUp = when (grid.scanAxis) {
            ScanAxis.COLUMN_MAJOR -> !grid.isCurrentLineReversed()
            ScanAxis.ROW_MAJOR -> true // a left/right chevron would read oddly rotated here; keep it simple
        }

        val cx = left + cellWidth / 2f
        val cy = top + cellHeight / 2f
        val arrowHalfWidth = cellWidth * 0.22f
        val arrowHeight = cellHeight * 0.26f

        val path = Path()
        if (pointingUp) {
            path.moveTo(cx, cy - arrowHeight)
            path.lineTo(cx + arrowHalfWidth, cy + arrowHeight * 0.4f)
            path.lineTo(cx + arrowHalfWidth * 0.4f, cy + arrowHeight * 0.4f)
            path.lineTo(cx, cy - arrowHeight * 0.2f)
            path.lineTo(cx - arrowHalfWidth * 0.4f, cy + arrowHeight * 0.4f)
            path.lineTo(cx - arrowHalfWidth, cy + arrowHeight * 0.4f)
        } else {
            path.moveTo(cx, cy + arrowHeight)
            path.lineTo(cx + arrowHalfWidth, cy - arrowHeight * 0.4f)
            path.lineTo(cx + arrowHalfWidth * 0.4f, cy - arrowHeight * 0.4f)
            path.lineTo(cx, cy + arrowHeight * 0.2f)
            path.lineTo(cx - arrowHalfWidth * 0.4f, cy - arrowHeight * 0.4f)
            path.lineTo(cx - arrowHalfWidth, cy - arrowHeight * 0.4f)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    private fun rgbToColorInt(rgb: AnomalyColorScale.Rgb): Int {
        val r = (rgb.r * 255).toInt().coerceIn(0, 255)
        val g = (rgb.g * 255).toInt().coerceIn(0, 255)
        val b = (rgb.b * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
