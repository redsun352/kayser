package com.arkeosar.groundscan.data

import java.io.File
import java.util.Locale

/**
 * Exports a [ScanGrid] to a plain CSV file, for opening in spreadsheet
 * or analysis tools (Excel, pandas, R, etc). Independent, minimal
 * implementation - CSV is a generic format, not tied to any particular
 * application's schema.
 *
 * Columns: row, col, value, lat, lon, alt, rawX, rawY, rawZ, rawMagnitude, baseline, delta, anomalyScore, confidence, targetClass
 * Missing values (no GPS fix, no raw axes, unmeasured point) are left
 * as empty fields rather than 0, so a spreadsheet doesn't mistake "no
 * data" for "zero reading".
 */
object CsvExporter {

    private const val HEADER = "row,col,value,lat,lon,alt,rawX,rawY,rawZ,rawMagnitude,baseline,delta,anomalyScore,confidence,targetClass,gprBackgroundRemoved,gprEnvelope,gprMigratedScore,gprLineContinuity,pseudoDepthM"

    fun export(grid: ScanGrid, file: File) {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')

        grid.allPoints().forEach { p ->
            sb.append(p.row).append(',')
            sb.append(p.column).append(',')
            sb.append(if (p.hasValue) formatNumber(p.value) else "").append(',')
            sb.append(p.latitude?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.longitude?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.altitude?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.rawX?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.rawY?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.rawZ?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.rawMagnitude?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.baseline?.let { formatNumber(it) } ?: "").append(',')
            sb.append(p.delta?.let { formatNumber(it) } ?: "").append(',')
            sb.append(formatNumber(p.anomalyScore)).append(',')
            sb.append(formatNumber(p.confidence)).append(',')
            sb.append(csvText(p.targetClass)).append(',')
            sb.append(formatNumber(p.gprBackgroundRemoved)).append(',')
            sb.append(formatNumber(p.gprEnvelope)).append(',')
            sb.append(formatNumber(p.gprMigratedScore)).append(',')
            sb.append(formatNumber(p.gprLineContinuity)).append(',')
            sb.append(formatNumber(p.pseudoDepthM))
            sb.append('\n')
        }

        file.writeText(sb.toString())
    }

    private fun formatNumber(value: Float): String = String.format(Locale.US, "%.4f", value)
    private fun formatNumber(value: Double): String = String.format(Locale.US, "%.6f", value)
    private fun csvText(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
