package com.arkeosar.groundscan.processing

import com.arkeosar.groundscan.data.GridPoint
import com.arkeosar.groundscan.data.ScanGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Applies a GPR-style processing chain to magnetometer grid data.
 *
 * Important: this does not turn the phone magnetometer into a real GPR. A
 * magnetometer measures magnetic-field contrast at the surface, while GPR sends
 * electromagnetic pulses and records reflections through time. This module
 * brings the useful GPR workflow to magnetic data: line/profile acquisition,
 * background removal, gain/envelope, lateral continuity, simple migration-like
 * focusing and a labelled pseudo-depth estimate.
 */
object MagneticGprProcessor {
    data class GprSummary(
        val strongestRow: Int,
        val strongestColumn: Int,
        val strongestMigratedScore: Float,
        val averageEnvelope: Float,
        val pseudoDepthM: Float,
        val continuity: Float,
        val interpretation: String
    ) {
        val shortText: String
            get() = "GPR-benzeri: $interpretation • derinlik ~${"%.1f".format(pseudoDepthM)} m • süreklilik %${(continuity * 100f).toInt()}"
    }

    fun update(grid: ScanGrid): GprSummary {
        val filled = grid.allPoints().filter { it.hasValue }
        if (filled.isEmpty()) {
            return GprSummary(0, 0, 0f, 0f, 0f, 0f, "Veri yok")
        }

        removeProfileBackground(grid)
        computeEnvelopeAndContinuity(grid)
        focusMigrationLike(grid)
        assignPseudoDepth(grid)

        val strongest = filled.maxByOrNull { it.gprMigratedScore } ?: filled.first()
        val avgEnvelope = filled.map { it.gprEnvelope }.average().toFloat().coerceAtLeast(0f)
        val avgContinuity = filled.map { it.gprLineContinuity }.average().toFloat().coerceIn(0f, 1f)
        return GprSummary(
            strongestRow = strongest.row,
            strongestColumn = strongest.column,
            strongestMigratedScore = strongest.gprMigratedScore,
            averageEnvelope = avgEnvelope,
            pseudoDepthM = strongest.pseudoDepthM,
            continuity = strongest.gprLineContinuity,
            interpretation = interpret(strongest)
        )
    }

    private fun removeProfileBackground(grid: ScanGrid) {
        // GPR background removal: each survey line's slow component is removed.
        for (r in 0 until grid.rows) {
            val rowPoints = (0 until grid.columns).map { c -> grid.pointAt(r, c) }.filter { it.hasValue }
            if (rowPoints.isEmpty()) continue
            val mean = rowPoints.map { it.value }.average().toFloat()
            rowPoints.forEach { it.gprBackgroundRemoved = it.value - mean }
        }
        // Also remove column bias so cross-lines do not dominate.
        for (c in 0 until grid.columns) {
            val colPoints = (0 until grid.rows).map { r -> grid.pointAt(r, c) }.filter { it.hasValue }
            if (colPoints.isEmpty()) continue
            val mean = colPoints.map { it.gprBackgroundRemoved }.average().toFloat()
            colPoints.forEach { it.gprBackgroundRemoved -= mean * 0.45f }
        }
    }

    private fun computeEnvelopeAndContinuity(grid: ScanGrid) {
        val filled = grid.allPoints().filter { it.hasValue }
        val rms = sqrt(filled.map { it.gprBackgroundRemoved * it.gprBackgroundRemoved }.average().toFloat().coerceAtLeast(0.0001f))
        filled.forEach { p ->
            val local = localStats(p, grid)
            val vertical = abs(neighborValue(grid, p.row - 1, p.column) - neighborValue(grid, p.row + 1, p.column))
            val horizontal = abs(neighborValue(grid, p.row, p.column - 1) - neighborValue(grid, p.row, p.column + 1))
            val edge = sqrt(vertical * vertical + horizontal * horizontal)
            p.gprEnvelope = (abs(p.gprBackgroundRemoved) / (rms * 2.2f)).coerceIn(0f, 1f)
            p.gprLineContinuity = (1f - (local.std / (rms * 2.8f + 0.001f))).coerceIn(0f, 1f)
            // Edges are useful but should not overpower coherent linear responses.
            p.gprMigratedScore = (p.gprEnvelope * 0.55f + p.gprLineContinuity * 0.28f + (edge / (rms * 4f + 0.001f)).coerceIn(0f, 1f) * 0.17f).coerceIn(0f, 1f)
        }
    }

    private fun focusMigrationLike(grid: ScanGrid) {
        // Simple migration-like focusing: coherent energy in a small hyperbola/arc
        // neighborhood is concentrated back to the center cell.
        val original = grid.allPoints().associateWith { it.gprMigratedScore }
        for (p in grid.allPoints().filter { it.hasValue }) {
            var support = 0f
            var count = 0
            for (dr in -2..2) {
                val dcLimit = 2 - abs(dr) / 2
                for (dc in -dcLimit..dcLimit) {
                    val q = pointOrNull(grid, p.row + dr, p.column + dc) ?: continue
                    if (!q.hasValue) continue
                    support += original[q] ?: 0f
                    count++
                }
            }
            val coherent = if (count == 0) 0f else support / count
            p.gprMigratedScore = (p.gprMigratedScore * 0.68f + coherent * 0.32f).coerceIn(0f, 1f)
        }
    }

    private fun assignPseudoDepth(grid: ScanGrid) {
        // Pseudo depth from anomaly width: broad/continuous anomalies are rendered
        // deeper; sharp, compact anomalies are rendered shallower. This is a display
        // estimate, not a measured GPR travel-time depth.
        for (p in grid.allPoints().filter { it.hasValue }) {
            val width = anomalyWidth(p, grid)
            val compactness = (1f - p.gprLineContinuity).coerceIn(0f, 1f)
            val depth = 0.6f + width * 0.38f + (1f - compactness) * 1.1f
            p.pseudoDepthM = depth.coerceIn(0.4f, 6.5f)
        }
    }

    private fun interpret(p: GridPoint): String = when {
        p.gprMigratedScore >= 0.72f && p.gprBackgroundRemoved > 0f && p.gprLineContinuity < 0.45f -> "kompakt metal hedef"
        p.gprMigratedScore >= 0.65f && p.gprBackgroundRemoved < 0f && p.gprLineContinuity >= 0.45f -> "boşluk/oda kontrastı"
        p.gprMigratedScore >= 0.56f && p.gprLineContinuity >= 0.58f -> "tünel/hat sürekliliği"
        p.gprMigratedScore >= 0.42f -> "zayıf yansıma/anomali"
        else -> "normal zemin"
    }

    private data class LocalStats(val mean: Float, val std: Float)

    private fun localStats(p: GridPoint, grid: ScanGrid): LocalStats {
        val vals = mutableListOf<Float>()
        for (r in max(0, p.row - 1)..min(grid.rows - 1, p.row + 1)) {
            for (c in max(0, p.column - 1)..min(grid.columns - 1, p.column + 1)) {
                val q = grid.pointAt(r, c)
                if (q.hasValue) vals += q.gprBackgroundRemoved
            }
        }
        if (vals.isEmpty()) return LocalStats(0f, 0f)
        val mean = vals.average().toFloat()
        val std = sqrt(vals.map { (it - mean) * (it - mean) }.average().toFloat().coerceAtLeast(0f))
        return LocalStats(mean, std)
    }

    private fun anomalyWidth(p: GridPoint, grid: ScanGrid): Float {
        val threshold = max(0.12f, p.gprEnvelope * 0.45f)
        var cells = 0
        for (r in max(0, p.row - 3)..min(grid.rows - 1, p.row + 3)) {
            for (c in max(0, p.column - 3)..min(grid.columns - 1, p.column + 3)) {
                val q = grid.pointAt(r, c)
                if (q.hasValue && q.gprEnvelope >= threshold) cells++
            }
        }
        return sqrt(cells.toFloat())
    }

    private fun pointOrNull(grid: ScanGrid, row: Int, col: Int): GridPoint? {
        if (row !in 0 until grid.rows || col !in 0 until grid.columns) return null
        return grid.pointAt(row, col)
    }

    private fun neighborValue(grid: ScanGrid, row: Int, col: Int): Float {
        val p = pointOrNull(grid, row, col)
        return if (p != null && p.hasValue) p.gprBackgroundRemoved else 0f
    }
}
