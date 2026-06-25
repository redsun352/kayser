package com.arkeosar.groundscan.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ArkeoSAR Ground Scan's own scan file format (.asgs - "ArkeoSAR Ground
 * Scan"). This is a plain JSON document, designed independently for this
 * app: human-readable, easy to debug, and easy to extend later (e.g. with
 * multiple layers for the 4D/time-series work already explored in
 * ArkeoSAR Pro). It intentionally does not replicate the binary layout of
 * any third-party scanner's save format - it only carries the same kind
 * of information (grid size, per-point value + optional GPS) because
 * that information is what any ground-scan viewer needs, independent of
 * how a particular vendor happens to lay out their bytes.
 *
 * If interoperability with OKM's .v3d format is wanted later (e.g. to
 * import scans recorded by stock OKM software), that should be a
 * separate, clearly-labeled *importer* that reads the third-party format
 * into a ScanGrid - not a reuse of this save format.
 */
object ArkeoSarFile {

    private const val FORMAT_NAME = "ArkeoSAR.GroundScan"
    private const val FORMAT_VERSION = 4 // v4 adds magnetic GPR-style processing metadata

    fun save(grid: ScanGrid, file: File, metadata: Map<String, String> = emptyMap()) {
        val root = JSONObject()
        root.put("format", FORMAT_NAME)
        root.put("formatVersion", FORMAT_VERSION)
        root.put("columns", grid.columns)
        root.put("rows", grid.rows)
        root.put("zigzag", grid.zigzag)
        root.put("thresholdFraction", grid.thresholdFraction)

        val metaObj = JSONObject()
        metadata.forEach { (k, v) -> metaObj.put(k, v) }
        root.put("metadata", metaObj)

        val pointsArray = JSONArray()
        grid.allPoints().forEach { point ->
            val p = JSONObject()
            p.put("row", point.row)
            p.put("col", point.column)
            if (point.hasValue) {
                p.put("value", point.value.toDouble())
            }
            point.latitude?.let { p.put("lat", it) }
            point.longitude?.let { p.put("lon", it) }
            point.altitude?.let { p.put("alt", it) }
            point.rawX?.let { p.put("rawX", it.toDouble()) }
            point.rawY?.let { p.put("rawY", it.toDouble()) }
            point.rawZ?.let { p.put("rawZ", it.toDouble()) }
            point.rawMagnitude?.let { p.put("rawMagnitude", it.toDouble()) }
            point.baseline?.let { p.put("baseline", it.toDouble()) }
            point.delta?.let { p.put("delta", it.toDouble()) }
            p.put("anomalyScore", point.anomalyScore.toDouble())
            p.put("confidence", point.confidence.toDouble())
            p.put("targetClass", point.targetClass)
            p.put("gprBackgroundRemoved", point.gprBackgroundRemoved.toDouble())
            p.put("gprEnvelope", point.gprEnvelope.toDouble())
            p.put("gprMigratedScore", point.gprMigratedScore.toDouble())
            p.put("gprLineContinuity", point.gprLineContinuity.toDouble())
            p.put("pseudoDepthM", point.pseudoDepthM.toDouble())
            pointsArray.put(p)
        }
        root.put("points", pointsArray)

        file.writeText(root.toString())
    }

    fun load(file: File): ScanGrid {
        val root = JSONObject(file.readText())
        val columns = root.getInt("columns")
        val rows = root.getInt("rows")
        val zigzag = root.optBoolean("zigzag", true)

        val grid = ScanGrid(columns = columns, rows = rows, zigzag = zigzag)
        grid.thresholdFraction = root.optDouble("thresholdFraction", 0.0).toFloat()

        val pointsArray = root.getJSONArray("points")
        for (i in 0 until pointsArray.length()) {
            val p = pointsArray.getJSONObject(i)
            val row = p.getInt("row")
            val col = p.getInt("col")
            val target = grid.pointAt(row, col)
            if (p.has("value")) target.value = p.getDouble("value").toFloat()
            if (p.has("lat")) target.latitude = p.getDouble("lat")
            if (p.has("lon")) target.longitude = p.getDouble("lon")
            if (p.has("alt")) target.altitude = p.getDouble("alt")
            if (p.has("rawX")) target.rawX = p.getDouble("rawX").toFloat()
            if (p.has("rawY")) target.rawY = p.getDouble("rawY").toFloat()
            if (p.has("rawZ")) target.rawZ = p.getDouble("rawZ").toFloat()
            if (p.has("rawMagnitude")) target.rawMagnitude = p.getDouble("rawMagnitude").toFloat()
            if (p.has("baseline")) target.baseline = p.getDouble("baseline").toFloat()
            if (p.has("delta")) target.delta = p.getDouble("delta").toFloat()
            target.anomalyScore = p.optDouble("anomalyScore", 0.0).toFloat()
            target.confidence = p.optDouble("confidence", 0.0).toFloat()
            target.targetClass = p.optString("targetClass", "Normal zemin")
            target.gprBackgroundRemoved = p.optDouble("gprBackgroundRemoved", 0.0).toFloat()
            target.gprEnvelope = p.optDouble("gprEnvelope", 0.0).toFloat()
            target.gprMigratedScore = p.optDouble("gprMigratedScore", 0.0).toFloat()
            target.gprLineContinuity = p.optDouble("gprLineContinuity", 0.0).toFloat()
            target.pseudoDepthM = p.optDouble("pseudoDepthM", 0.0).toFloat()
        }
        return grid
    }
}
