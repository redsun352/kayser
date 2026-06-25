package com.arkeosar.groundscan.render

import com.arkeosar.groundscan.data.ScanGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Builds a smooth, interpolated triangle mesh from a [ScanGrid].
 *
 * Unlike a naive "one quad per grid cell" mesh (which looks faceted and
 * blocky), this fits a [ThinPlateSpline] through the grid's filled
 * points and evaluates it on a denser output lattice
 * ([outputResolutionMultiplier] times the source grid's resolution per
 * axis), producing the smooth, continuously-draped surface look seen in
 * "İnce Plaka" (thin-plate) style surface plots. Per-vertex normals are
 * computed from the interpolated heightfield so the surface shades
 * properly under lighting, instead of every triangle facet being
 * uniformly flat-colored.
 *
 * Height scaling: rather than a fixed multiplier (which looks flat for
 * sensors with a narrow reading range, like a phone magnetometer's
 * ~35-39 µT, and absurdly tall for sensors with a wide one), the
 * surface's vertical extent is normalized so the *tallest* point always
 * reaches [maxHeightFraction] of the grid's horizontal span. This keeps
 * the surface visually readable regardless of the raw value units or
 * range - exactly the kind of auto-scaling a "thin plate" style surface
 * plot needs to look right across very different data sources (an
 * external probe's wide-range readings vs. a phone's narrow-range ones).
 *
 * If there are fewer than 3 filled points (not enough to fit a TPS
 * surface yet), this falls back to a flat, unlit panel at height 0 so
 * the view never breaks while a scan is just starting.
 */
class HeightmapMesh(
    grid: ScanGrid,
    private val maxHeightFraction: Float = 0.45f,
    private val outputResolutionMultiplier: Int = 3
) {

    var vertexBuffer: FloatBuffer
    var normalBuffer: FloatBuffer
    var colorBuffer: FloatBuffer
    var indexBuffer: ShortBuffer
    var indexCount: Int = 0

    /** Line-list indices for RenderMode.WIREFRAME (each output-lattice cell's 4 edges). */
    var wireframeIndexBuffer: ShortBuffer
    var wireframeIndexCount: Int = 0

    /** Point-list indices for RenderMode.POINT_CLOUD (one point per output-lattice vertex). */
    var pointIndexBuffer: ShortBuffer
    var pointIndexCount: Int = 0

    /** Value range actually used for color scaling - exposed for the colorbar UI. */
    var valueRangeMin: Float = 0f
    var valueRangeMax: Float = 1f

    init {
        val filled = grid.pointsPassingThreshold()

        val outCols = ((grid.columns - 1) * outputResolutionMultiplier + 1).coerceAtLeast(2)
        val outRows = ((grid.rows - 1) * outputResolutionMultiplier + 1).coerceAtLeast(2)

        // Sample (or fall back to) a height value per output lattice cell.
        val heights = FloatArray(outCols * outRows)

        if (filled.size >= 3) {
            val xs = DoubleArray(filled.size)
            val ys = DoubleArray(filled.size)
            val zs = DoubleArray(filled.size)
            filled.forEachIndexed { i, p ->
                xs[i] = p.column.toDouble()
                ys[i] = p.row.toDouble()
                zs[i] = p.value.toDouble()
            }
            // Small regularization: real sensor data is noisy, so a tiny
            // lambda avoids the surface ringing tightly around every
            // single sample.
            val tps = ThinPlateSpline.fit(xs, ys, zs, lambda = 0.05)

            for (outRow in 0 until outRows) {
                val gridY = outRow.toDouble() / outputResolutionMultiplier
                for (outCol in 0 until outCols) {
                    val gridX = outCol.toDouble() / outputResolutionMultiplier
                    heights[outRow * outCols + outCol] = tps.evaluate(gridX, gridY).toFloat()
                }
            }
        }
        // else: heights stays all-zero (flat panel) - handled uniformly below.

        valueRangeMin = if (filled.isEmpty()) 0f else filled.minOf { it.value }
        valueRangeMax = if (filled.isEmpty()) 1f else filled.maxOf { it.value }
        if (valueRangeMin == valueRangeMax) valueRangeMax = valueRangeMin + 1f

        // Auto-scale: map the full value range onto maxHeightFraction of
        // the grid's horizontal span, so the surface always shows a
        // clearly visible relief regardless of the source's raw units.
        val gridSpan = maxOf(grid.columns - 1, grid.rows - 1, 1).toFloat()
        val valueSpan = valueRangeMax - valueRangeMin
        val heightScale = if (valueSpan > 1e-6f) (gridSpan * maxHeightFraction) / valueSpan else 0f
        val heightOffset = (valueRangeMin + valueRangeMax) / 2f // center the relief vertically around 0

        val vertexCount = outCols * outRows
        val vertices = FloatArray(vertexCount * 3)
        val colors = FloatArray(vertexCount * 4)

        for (row in 0 until outRows) {
            for (col in 0 until outCols) {
                val idx = row * outCols + col
                val h = heights[idx]

                val x = (col.toFloat() / outputResolutionMultiplier) - (grid.columns - 1) / 2f
                val z = (row.toFloat() / outputResolutionMultiplier) - (grid.rows - 1) / 2f
                val y = (h - heightOffset) * heightScale

                vertices[idx * 3 + 0] = x
                vertices[idx * 3 + 1] = y
                vertices[idx * 3 + 2] = z

                val rgb = AnomalyColorScale.colorForRaw(h, valueRangeMin, valueRangeMax)
                colors[idx * 4 + 0] = rgb.r
                colors[idx * 4 + 1] = rgb.g
                colors[idx * 4 + 2] = rgb.b
                colors[idx * 4 + 3] = 1f
            }
        }

        val normals = computeSmoothNormals(vertices, outCols, outRows)

        val quadCount = (outCols - 1) * (outRows - 1)
        val indices = ShortArray(quadCount * 6)
        var i = 0
        for (row in 0 until outRows - 1) {
            for (col in 0 until outCols - 1) {
                val topLeft = (row * outCols + col).toShort()
                val topRight = (row * outCols + col + 1).toShort()
                val bottomLeft = ((row + 1) * outCols + col).toShort()
                val bottomRight = ((row + 1) * outCols + col + 1).toShort()

                indices[i++] = topLeft
                indices[i++] = bottomLeft
                indices[i++] = topRight

                indices[i++] = topRight
                indices[i++] = bottomLeft
                indices[i++] = bottomRight
            }
        }
        indexCount = indices.size

        // Wireframe: one line per horizontal and vertical edge of the lattice.
        val hEdges = (outCols - 1) * outRows
        val vEdges = outCols * (outRows - 1)
        val wireIndices = ShortArray((hEdges + vEdges) * 2)
        var w = 0
        for (row in 0 until outRows) {
            for (col in 0 until outCols - 1) {
                wireIndices[w++] = (row * outCols + col).toShort()
                wireIndices[w++] = (row * outCols + col + 1).toShort()
            }
        }
        for (row in 0 until outRows - 1) {
            for (col in 0 until outCols) {
                wireIndices[w++] = (row * outCols + col).toShort()
                wireIndices[w++] = ((row + 1) * outCols + col).toShort()
            }
        }
        wireframeIndexCount = wireIndices.size

        // Point cloud: every output-lattice vertex as its own point.
        val pointIndices = ShortArray(vertexCount) { it.toShort() }
        pointIndexCount = pointIndices.size

        vertexBuffer = allocateFloatBuffer(vertices)
        normalBuffer = allocateFloatBuffer(normals)
        colorBuffer = allocateFloatBuffer(colors)
        indexBuffer = allocateShortBuffer(indices)
        wireframeIndexBuffer = allocateShortBuffer(wireIndices)
        pointIndexBuffer = allocateShortBuffer(pointIndices)
    }

    /**
     * Per-vertex normals via central differences on the heightfield grid,
     * giving each vertex a smoothly varying normal (rather than a flat
     * per-triangle normal), which is what makes a lit surface look
     * "draped" instead of faceted.
     */
    private fun computeSmoothNormals(vertices: FloatArray, cols: Int, rows: Int): FloatArray {
        val normals = FloatArray(cols * rows * 3)

        fun heightAt(c: Int, r: Int): Float {
            val cc = c.coerceIn(0, cols - 1)
            val rr = r.coerceIn(0, rows - 1)
            return vertices[(rr * cols + cc) * 3 + 1]
        }

        // Note: normals only need *direction*, not exact world-space
        // scale, so a uniform dx=dz=2 central-difference step (in
        // output-lattice units) is used consistently for both axes
        // regardless of outputResolutionMultiplier.

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val hl = heightAt(col - 1, row)
                val hr = heightAt(col + 1, row)
                val hd = heightAt(col, row - 1)
                val hu = heightAt(col, row + 1)

                // Tangent vectors along X (col) and Z (row) directions.
                val tangentX = floatArrayOf(2f, hr - hl, 0f)
                val tangentZ = floatArrayOf(0f, hu - hd, 2f)

                // Normal = cross(tangentZ, tangentX), normalized.
                var nx = tangentZ[1] * tangentX[2] - tangentZ[2] * tangentX[1]
                var ny = tangentZ[2] * tangentX[0] - tangentZ[0] * tangentX[2]
                var nz = tangentZ[0] * tangentX[1] - tangentZ[1] * tangentX[0]

                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
                nx /= len; ny /= len; nz /= len
                if (ny < 0f) { nx = -nx; ny = -ny; nz = -nz } // keep normals pointing "up"

                val idx = row * cols + col
                normals[idx * 3 + 0] = nx
                normals[idx * 3 + 1] = ny
                normals[idx * 3 + 2] = nz
            }
        }
        return normals
    }

    private fun allocateFloatBuffer(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        return buf
    }

    private fun allocateShortBuffer(data: ShortArray): ShortBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        buf.put(data).position(0)
        return buf
    }
}
