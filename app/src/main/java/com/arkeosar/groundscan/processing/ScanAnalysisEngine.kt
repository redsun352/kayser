package com.arkeosar.groundscan.processing

import com.arkeosar.groundscan.data.GridPoint
import com.arkeosar.groundscan.data.ScanGrid
import kotlin.math.abs
import kotlin.math.sqrt

object ScanAnalysisEngine {
    data class Summary(
        val filled: Int,
        val total: Int,
        val strongestValue: Float,
        val strongestRow: Int,
        val strongestColumn: Int,
        val averageScore: Float,
        val strongestScore: Float,
        val targetCount: Int,
        val dominantClass: String,
        val confidence: Float
    ) {
        val progressPercent: Int get() = if (total == 0) 0 else ((filled * 100f) / total).toInt()
        val shortText: String get() = "$dominantClass • güven %${(confidence * 100).toInt()} • hedef $targetCount"
    }

    fun updatePoint(point: GridPoint, grid: ScanGrid) {
        val filled = grid.allPoints().filter { it.hasValue }
        if (filled.size < 4) {
            point.anomalyScore = 0f
            point.confidence = 0.35f
            point.targetClass = "Kalibrasyon"
            return
        }
        val values = filled.map { it.value }
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        val std = sqrt(maxOf(variance, 0.0001f))
        val z = abs(point.value - mean) / std
        val local = localContrast(point, grid)
        val score = ((z / 3.2f) * 0.62f + (local / (std * 2.4f + 0.001f)) * 0.38f).coerceIn(0f, 1f)
        point.anomalyScore = score
        point.confidence = (0.52f + score * 0.43f).coerceIn(0f, 0.97f)
        point.targetClass = classify(point.value - mean, score, local)
    }

    fun summarize(grid: ScanGrid): Summary {
        val filled = grid.allPoints().filter { it.hasValue }
        if (filled.isEmpty()) return Summary(0, grid.totalPoints, 0f, 0, 0, 0f, 0f, 0, "Veri yok", 0f)
        val strongest = filled.maxBy { it.anomalyScore }
        val avgScore = filled.map { it.anomalyScore }.average().toFloat()
        val targets = filled.count { it.anomalyScore >= 0.48f }
        val dominant = filled.groupingBy { it.targetClass }.eachCount().maxByOrNull { it.value }?.key ?: "Normal zemin"
        val conf = filled.map { it.confidence }.average().toFloat().coerceIn(0f, 0.98f)
        return Summary(
            filled = filled.size,
            total = grid.totalPoints,
            strongestValue = strongest.value,
            strongestRow = strongest.row,
            strongestColumn = strongest.column,
            averageScore = avgScore,
            strongestScore = strongest.anomalyScore,
            targetCount = targets,
            dominantClass = dominant,
            confidence = conf
        )
    }

    private fun localContrast(point: GridPoint, grid: ScanGrid): Float {
        var sum = 0f
        var count = 0
        for (r in point.row - 1..point.row + 1) {
            for (c in point.column - 1..point.column + 1) {
                if (r == point.row && c == point.column) continue
                if (r !in 0 until grid.rows || c !in 0 until grid.columns) continue
                val p = grid.pointAt(r, c)
                if (p.hasValue) {
                    sum += p.value
                    count++
                }
            }
        }
        if (count == 0) return 0f
        return abs(point.value - sum / count)
    }

    private fun classify(centeredValue: Float, score: Float, local: Float): String = when {
        score >= 0.74f && centeredValue > 0f -> "Güçlü metal"
        score >= 0.74f && centeredValue < 0f -> "Boşluk/oda"
        score >= 0.52f && local > 1.2f && centeredValue < 0f -> "Tünel izi"
        score >= 0.48f && centeredValue > 0f -> "Metal olasılığı"
        score >= 0.35f -> "Zemin farkı"
        else -> "Normal zemin"
    }
}
