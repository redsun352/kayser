package com.arkeosar.groundscan.render

import com.arkeosar.groundscan.data.ScanGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Builds the geometry for [ViewMode.VOLUMETRIC_3D]: a stack of
 * semi-transparent horizontal slices through [SchematicDepthModel]'s
 * projected pseudo-depth volume, giving the "block with colored
 * pockets at depth" look. Each slice is a flat quad grid (same X/Z
 * footprint as the surface scan) positioned at its depth and colored
 * per-cell from the schematic falloff model.
 *
 * This is a deliberately simple slice-stack renderer rather than true
 * volumetric ray-marching, which is enough to convey "the anomaly
 * persists at depth and fades out" without the complexity of a full
 * volume renderer - appropriate for a mobile GLES 2.0 pipeline.
 */
class VolumetricMesh(
    grid: ScanGrid,
    private val depthSliceCount: Int = 6,
    private val maxDepthInGridUnits: Double = 4.0,
    private val assumedDepth: Double = SchematicDepthModel.DEFAULT_ASSUMED_DEPTH
) {

    var vertexBuffer: FloatBuffer
    var colorBuffer: FloatBuffer
    var indexBuffer: ShortBuffer
    var indexCount: Int = 0

    var valueRangeMin: Float = 0f
    var valueRangeMax: Float = 1f

    init {
        val cols = grid.columns
        val rows = grid.rows

        val surfaceValues = Array(rows) { row ->
            FloatArray(cols) { col -> grid.pointAt(row, col).value }
        }

        val filledValues = surfaceValues.flatMap { it.toList() }.filter { !it.isNaN() }
        valueRangeMin = if (filledValues.isEmpty()) 0f else filledValues.min()
        valueRangeMax = if (filledValues.isEmpty()) 1f else filledValues.max()
        if (valueRangeMin == valueRangeMax) valueRangeMax = valueRangeMin + 1f

        val volume = SchematicDepthModel.buildVolume(
            surfaceValues = surfaceValues,
            depthSlices = depthSliceCount,
            maxDepth = maxDepthInGridUnits,
            assumedDepth = assumedDepth
        )

        val verticesPerSlice = cols * rows
        val vertices = FloatArray(depthSliceCount * verticesPerSlice * 3)
        val colors = FloatArray(depthSliceCount * verticesPerSlice * 4)

        for (slice in 0 until depthSliceCount) {
            val sliceDepth = (slice.toDouble() / (depthSliceCount - 1).coerceAtLeast(1)) * maxDepthInGridUnits
            // Fade slices out with depth so deeper layers read as "behind/below"
            // rather than fully opaque, reinforcing that this is a translucent
            // schematic volume, not a solid measured block.
            val sliceAlpha = (1f - (slice.toFloat() / depthSliceCount.toFloat()) * 0.75f).coerceIn(0.15f, 1f)

            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val vertexIndex = slice * verticesPerSlice + row * cols + col
                    val x = col - (cols - 1) / 2f
                    val z = row - (rows - 1) / 2f
                    val y = -sliceDepth.toFloat() // slices go downward (negative Y) from the surface

                    vertices[vertexIndex * 3 + 0] = x
                    vertices[vertexIndex * 3 + 1] = y
                    vertices[vertexIndex * 3 + 2] = z

                    val rawValue = volume[slice][row][col]
                    val rgb = if (rawValue.isNaN()) {
                        AnomalyColorScale.Rgb(0.1f, 0.12f, 0.11f)
                    } else {
                        AnomalyColorScale.colorForRaw(rawValue, valueRangeMin, valueRangeMax)
                    }
                    val alpha = if (rawValue.isNaN()) sliceAlpha * 0.3f else sliceAlpha

                    colors[vertexIndex * 4 + 0] = rgb.r
                    colors[vertexIndex * 4 + 1] = rgb.g
                    colors[vertexIndex * 4 + 2] = rgb.b
                    colors[vertexIndex * 4 + 3] = alpha
                }
            }
        }

        // Two triangles per cell, per slice (no inter-slice walls - just stacked flat grids).
        val quadsPerSlice = (cols - 1) * (rows - 1)
        val indices = ShortArray(depthSliceCount * quadsPerSlice * 6)
        var i = 0
        for (slice in 0 until depthSliceCount) {
            val base = slice * verticesPerSlice
            for (row in 0 until rows - 1) {
                for (col in 0 until cols - 1) {
                    val topLeft = (base + row * cols + col).toShort()
                    val topRight = (base + row * cols + col + 1).toShort()
                    val bottomLeft = (base + (row + 1) * cols + col).toShort()
                    val bottomRight = (base + (row + 1) * cols + col + 1).toShort()

                    indices[i++] = topLeft
                    indices[i++] = bottomLeft
                    indices[i++] = topRight

                    indices[i++] = topRight
                    indices[i++] = bottomLeft
                    indices[i++] = bottomRight
                }
            }
        }
        indexCount = indices.size

        vertexBuffer = allocateFloatBuffer(vertices)
        colorBuffer = allocateFloatBuffer(colors)
        indexBuffer = allocateShortBuffer(indices)
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
