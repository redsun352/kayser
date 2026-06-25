package com.arkeosar.groundscan.render

import kotlin.math.pow

/**
 * Derives a **schematic** pseudo-depth volume from surface-only magnetic
 * anomaly readings, for the volumetric ("4D") view mode.
 *
 * ## What this actually is, and is not
 *
 * A single surface magnetometer survey cannot uniquely determine the
 * depth of a buried source - the same surface anomaly pattern can be
 * produced by many different (depth, size, magnetic-strength)
 * combinations. Properly inverting for true depth needs additional
 * information this app doesn't have (known target type, multiple
 * sensor heights, borehole calibration, etc).
 *
 * What this class *does* do is grounded in real physics: a magnetic
 * dipole's field falls off with the inverse cube of distance from the
 * source (B ∝ 1/r³), which is the standard near-field approximation
 * used across magnetometer survey literature for point-like buried
 * sources. This class assumes every surface reading corresponds to a
 * dipole sitting at an *assumed* representative depth, and projects
 * that same falloff law downward to build a 3D volume - i.e. it shows
 * "what the anomaly would look like in depth if it behaves like a
 * typical dipole source," not a measured or inverted depth.
 *
 * Every call site that renders this volume must visibly label it as a
 * schematic/estimated model, not a measurement - see
 * [com.arkeosar.groundscan.ui.ScanActivity]'s volumetric mode banner.
 */
object SchematicDepthModel {

    /**
     * A representative assumed source depth (in grid-column units, same
     * scale as [com.arkeosar.groundscan.data.ScanGrid] coordinates) used
     * to anchor the falloff projection. This is a modeling assumption,
     * not a measured value - exposed as a parameter so the UI can let
     * the person adjust it and see how the schematic volume changes,
     * which also helps make clear that it's a tunable assumption rather
     * than a fixed fact.
     */
    const val DEFAULT_ASSUMED_DEPTH = 1.5

    /**
     * Computes a 3D grid of "schematic anomaly strength" values: for
     * each (col, row) surface cell and each depth slice, project the
     * surface reading downward using inverse-cube dipole falloff
     * relative to [assumedDepth].
     *
     * @param surfaceValues surface readings, indexed [row][col] (NaN for unmeasured cells)
     * @param depthSlices number of horizontal slices to generate through the volume
     * @param maxDepth total depth (in grid units) the volume should span
     * @return a [depthSlices] x rows x cols array of schematic strength values
     */
    fun buildVolume(
        surfaceValues: Array<FloatArray>,
        depthSlices: Int,
        maxDepth: Double,
        assumedDepth: Double = DEFAULT_ASSUMED_DEPTH
    ): Array<Array<FloatArray>> {
        val rows = surfaceValues.size
        val cols = if (rows > 0) surfaceValues[0].size else 0

        return Array(depthSlices) { sliceIndex ->
            // Depth of this slice, from 0 (surface) to maxDepth (bottom of the displayed volume).
            val sliceDepth = (sliceIndex.toDouble() / (depthSlices - 1).coerceAtLeast(1)) * maxDepth

            Array(rows) { row ->
                FloatArray(cols) { col ->
                    val surface = surfaceValues[row][col]
                    if (surface.isNaN()) {
                        Float.NaN
                    } else {
                        falloff(surface, sliceDepth, assumedDepth)
                    }
                }
            }
        }
    }

    /**
     * Inverse-cube falloff: strength at [sliceDepth] relative to the
     * surface reading, assuming the source sits at [assumedDepth].
     * At sliceDepth == 0 (the surface) this returns the original
     * reading unchanged; it decays smoothly below that.
     *
     * Derivation: for a dipole at depth d, B(z) ∝ 1 / (d - z)³ for
     * z < d (above the source) and the field is undefined/singular at
     * z = d itself - clamped here to avoid a literal singularity, since
     * this is an illustrative falloff, not a precise field solver.
     */
    private fun falloff(surfaceValue: Float, sliceDepth: Double, assumedDepth: Double): Float {
        val distanceFromAssumedSource = (assumedDepth - sliceDepth).let {
            if (it <= 0.05) 0.05 else it // clamp to avoid dividing by ~0 near/below the assumed source depth
        }
        val referenceDistance = assumedDepth.let { if (it <= 0.05) 0.05 else it }
        val ratio = (referenceDistance / distanceFromAssumedSource).pow(3)
        return (surfaceValue * ratio).toFloat()
    }

    /**
     * Convenience: estimates a single "most likely schematic depth" per
     * surface cell, purely for display as a number (e.g. in an info
     * panel) - this is the depth at which the falloff model's strength
     * would equal a target fraction of the surface reading, which is
     * just another way of presenting the same dipole assumption, not an
     * independent estimate.
     */
    fun schematicDepthEstimate(assumedDepth: Double = DEFAULT_ASSUMED_DEPTH): Double = assumedDepth

    /** Inverse-square-root distance falloff curve, useful for rendering a faint reference profile. */
    fun referenceFalloffCurve(maxDepth: Double, steps: Int, assumedDepth: Double = DEFAULT_ASSUMED_DEPTH): List<Pair<Double, Double>> {
        return (0 until steps).map { i ->
            val depth = (i.toDouble() / (steps - 1).coerceAtLeast(1)) * maxDepth
            val strength = falloff(1f, depth, assumedDepth).toDouble()
            depth to strength
        }
    }
}
