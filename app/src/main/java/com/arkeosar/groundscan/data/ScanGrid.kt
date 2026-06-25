package com.arkeosar.groundscan.data

import kotlin.math.max

/**
 * Which direction the zigzag sweep advances along first.
 *
 * ROW_MAJOR: walk along a row left-to-right, then the next row
 * right-to-left (used by [com.arkeosar.groundscan.ui.ScanActivity]'s
 * walking-survey style scan).
 *
 * COLUMN_MAJOR: walk down a column top-to-bottom, then the next column
 * bottom-to-top (used by [com.arkeosar.groundscan.ui.GridScanActivity]'s
 * cell-by-cell detector grid, matching the column-by-column fill order
 * observed in ArkeoMag / Thuban Lodestar's detector screen).
 *
 * Both are the same boustrophedon ("ox-plow") sweep pattern; only the
 * primary axis differs.
 */
enum class ScanAxis {
    ROW_MAJOR,
    COLUMN_MAJOR
}

/**
 * Represents a single measurement taken at one point in the scan grid.
 *
 * @property value the magnetic anomaly reading (raw sensor units) as
 *   currently displayed - this is what [DisplayFunction] computed from
 *   [rawX]/[rawY]/[rawZ] when those are available, or the source's own
 *   single-channel reading otherwise.
 * @property latitude WGS84 latitude, or null if no GPS fix was available
 * @property longitude WGS84 longitude, or null if no GPS fix was available
 * @property altitude meters above sea level, or null if unavailable
 * @property rawX/rawY/rawZ the raw 3-axis components behind [value], if
 *   the source exposed them (see [com.arkeosar.groundscan.data.ScanReading]).
 *   Kept so the function picker can recompute [value] with a different
 *   [DisplayFunction] without re-scanning.
 */
data class GridPoint(
    val row: Int,
    val column: Int,
    var value: Float = Float.NaN,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var altitude: Double? = null,
    var rawX: Float? = null,
    var rawY: Float? = null,
    var rawZ: Float? = null,
    var rawMagnitude: Float? = null,
    var baseline: Float? = null,
    var delta: Float? = null,
    var anomalyScore: Float = 0f,
    var confidence: Float = 0f,
    var targetClass: String = "Normal zemin"
) {
    val hasValue: Boolean get() = !value.isNaN()
    val hasRawAxes: Boolean get() = rawX != null && rawY != null && rawZ != null
}

/**
 * Holds the full set of measurements for one ground-scan session, arranged
 * on a 2D grid that is walked in a boustrophedon ("zigzag") pattern: the
 * scanner walks one row/column, then reverses direction for the next
 * one, and so on. This is the standard sweep pattern used in
 * magnetometer and GPR surveys to keep walking distance to a minimum
 * while covering a rectangular area. See [ScanAxis] for the two
 * supported sweep orientations.
 *
 * This implementation is written independently for ArkeoSAR Ground Scan;
 * it does not reuse or reference any third-party scanning application's
 * source code. The zigzag sweep itself is a standard, widely used survey
 * technique, not anyone's proprietary algorithm.
 */
class ScanGrid(
    val columns: Int,
    val rows: Int,
    val zigzag: Boolean = true,
    val scanAxis: ScanAxis = ScanAxis.ROW_MAJOR
) {
    private val points: Array<GridPoint> = Array(columns * rows) { index ->
        val row = index / columns
        val col = index % columns
        GridPoint(row = row, column = col)
    }

    /** Linear cursor over the grid in scan order (zigzag if enabled). */
    private var cursor: Int = 0

    val totalPoints: Int get() = points.size
    val filledPoints: Int get() = points.count { it.hasValue }
    val isComplete: Boolean get() = cursor >= points.size

    /**
     * Converts a logical (line, scanOrderIndex) pair into the physical
     * index along the secondary axis, accounting for the zigzag
     * direction reversal on odd lines. "Line" here means a row for
     * ROW_MAJOR, or a column for COLUMN_MAJOR.
     */
    private fun physicalSecondary(line: Int, scanOrderIndex: Int, secondaryLength: Int): Int {
        if (!zigzag) return scanOrderIndex
        val lineIsReversed = line % 2 == 1
        return if (lineIsReversed) (secondaryLength - 1 - scanOrderIndex) else scanOrderIndex
    }

    /** Returns the grid point the scanner is currently positioned over. */
    fun currentPoint(): GridPoint? {
        if (isComplete) return null
        return when (scanAxis) {
            ScanAxis.ROW_MAJOR -> {
                val row = cursor / columns
                val scanOrderCol = cursor % columns
                val col = physicalSecondary(row, scanOrderCol, columns)
                points[row * columns + col]
            }
            ScanAxis.COLUMN_MAJOR -> {
                val col = cursor / rows
                val scanOrderRow = cursor % rows
                val row = physicalSecondary(col, scanOrderRow, rows)
                points[row * columns + col]
            }
        }
    }

    /**
     * Records a measurement at the current scan position and advances
     * the cursor to the next point in zigzag order.
     *
     * @return the grid point that was just written, or null if the scan
     *         is already complete.
     */
    fun addValue(
        value: Float,
        latitude: Double? = null,
        longitude: Double? = null,
        altitude: Double? = null,
        rawX: Float? = null,
        rawY: Float? = null,
        rawZ: Float? = null,
        rawMagnitude: Float? = null,
        baseline: Float? = null,
        delta: Float? = null,
        anomalyScore: Float = 0f,
        confidence: Float = 0f,
        targetClass: String = "Normal zemin"
    ): GridPoint? {
        val point = currentPoint() ?: return null
        point.value = value
        point.latitude = latitude
        point.longitude = longitude
        point.altitude = altitude
        point.rawX = rawX
        point.rawY = rawY
        point.rawZ = rawZ
        point.rawMagnitude = rawMagnitude
        point.baseline = baseline
        point.delta = delta
        point.anomalyScore = anomalyScore
        point.confidence = confidence
        point.targetClass = targetClass
        cursor++
        return point
    }

    /**
     * Recomputes every filled point's [GridPoint.value] using a different
     * [com.arkeosar.groundscan.render.DisplayFunction], for points that
     * have raw X/Y/Z components stored. Points without raw axes (e.g.
     * readings that came from a single-channel Bluetooth probe) are left
     * untouched. Used by the "Fonksiyon" picker so switching functions
     * doesn't require re-scanning.
     */
    fun recomputeWithFunction(function: com.arkeosar.groundscan.render.DisplayFunction) {
        for (point in points) {
            val x = point.rawX
            val y = point.rawY
            val z = point.rawZ
            if (x != null && y != null && z != null) {
                point.value = function.apply(x, y, z)
            }
        }
    }

    /**
     * Fraction in [0, 1] used by the "Eşik" (threshold) slider in the
     * interpolation settings panel: 0 means every filled point
     * contributes to the surface; values above 0 progressively hide the
     * lowest-value points, so only the upper band of anomaly readings
     * remains visible. [HeightmapMesh] reads this via
     * [pointsPassingThreshold] when building the surface.
     */
    var thresholdFraction: Float = 0f

    /** Filled points whose value is at or above the threshold cutoff (see [thresholdFraction]). */
    fun pointsPassingThreshold(): List<GridPoint> {
        val filled = points.filter { it.hasValue }
        if (thresholdFraction <= 0f || filled.isEmpty()) return filled
        val lo = filled.minOf { it.value }
        val hi = filled.maxOf { it.value }
        if (lo == hi) return filled
        val cutoff = lo + thresholdFraction * (hi - lo)
        return filled.filter { it.value >= cutoff }
    }

    fun pointAt(row: Int, col: Int): GridPoint = points[row * columns + col]

    /**
     * The index of the row/column the scanner is currently on (whichever
     * is the primary [scanAxis]), or null if the scan is already
     * complete. Used by [com.arkeosar.groundscan.ui.GridScanActivity] to
     * show "N satır/sütun tamamlandı" progress.
     */
    fun currentLineIndex(): Int? {
        if (isComplete) return null
        return when (scanAxis) {
            ScanAxis.ROW_MAJOR -> cursor / columns
            ScanAxis.COLUMN_MAJOR -> cursor / rows
        }
    }

    /** How many full rows/columns (per [scanAxis]) have been completed so far. */
    fun completedLineCount(): Int {
        val lineLength = if (scanAxis == ScanAxis.ROW_MAJOR) columns else rows
        return cursor / lineLength
    }

    /** Direction of the current line's sweep: true if advancing in the "reversed" direction. */
    fun isCurrentLineReversed(): Boolean {
        val line = currentLineIndex() ?: return false
        return zigzag && line % 2 == 1
    }

    fun allPoints(): List<GridPoint> = points.toList()

    /** Min/max of all recorded values, ignoring unfilled points. Useful for color scaling. */
    fun valueRange(): ClosedFloatingPointRange<Float> {
        val filled = points.filter { it.hasValue }
        if (filled.isEmpty()) return 0f..1f
        val lo = filled.minOf { it.value }
        val hi = filled.maxOf { it.value }
        return if (lo == hi) lo..(lo + 1f) else lo..hi
    }

    fun reset() {
        cursor = 0
        points.forEach {
            it.value = Float.NaN
            it.latitude = null
            it.longitude = null
            it.altitude = null
            it.rawX = null
            it.rawY = null
            it.rawZ = null
            it.rawMagnitude = null
            it.baseline = null
            it.delta = null
            it.anomalyScore = 0f
            it.confidence = 0f
            it.targetClass = "Normal zemin"
        }
    }

    companion object {
        /**
         * Converts a survey side length in meters into a grid resolution,
         * using a fixed sampling density of one reading roughly every
         * ~17 cm (a common magnetometer walking-speed sampling rate).
         * This mirrors the kind of meters-to-samples conversion any
         * walking-survey tool needs; the specific density is tunable
         * in Settings.
         */
        fun resolutionForMeters(meters: Int, samplesPerMeter: Int = 6): Int {
            if (meters <= 0) return 10
            return max(2, meters * samplesPerMeter + 1)
        }
    }
}
