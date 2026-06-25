package com.arkeosar.groundscan.render

/**
 * Maps a magnetic anomaly reading to an RGB color for the 3D heightmap
 * visualization, using a four-stop gradient (low -> mid -> high -> critical)
 * defined in colors.xml. This is a standard "thermal" style anomaly palette
 * used widely across geophysical visualization tools (similar in spirit to
 * a seismic or radar colormap); the specific stop colors here are
 * ArkeoSAR's own choice (anomaly_low/_mid/_high/_critical), not copied
 * from any third party's renderer.
 */
object AnomalyColorScale {

    data class Rgb(val r: Float, val g: Float, val b: Float)

    // Stops correspond to colors.xml: anomaly_low, anomaly_mid, anomaly_high, anomaly_critical
    private val stops = listOf(
        0.00f to Rgb(0x1C / 255f, 0x5F / 255f, 0xA8 / 255f), // anomaly_low (blue)
        0.45f to Rgb(0x2D / 255f, 0xBE / 255f, 0x6C / 255f), // anomaly_mid (green)
        0.80f to Rgb(0xE8 / 255f, 0xA3 / 255f, 0x3D / 255f), // anomaly_high (amber)
        1.00f to Rgb(0xE0 / 255f, 0x48 / 255f, 0x3B / 255f)  // anomaly_critical (red)
    )

    /**
     * @param normalized a value in [0, 1] representing where the reading
     *   falls between the scan's observed minimum and maximum.
     */
    fun colorFor(normalized: Float): Rgb {
        val t = normalized.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (t in t0..t1) {
                val localT = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
                return Rgb(
                    lerp(c0.r, c1.r, localT),
                    lerp(c0.g, c1.g, localT),
                    lerp(c0.b, c1.b, localT)
                )
            }
        }
        return stops.last().second
    }

    fun colorForRaw(value: Float, rangeMin: Float, rangeMax: Float): Rgb {
        val span = rangeMax - rangeMin
        val normalized = if (span <= 0f) 0.5f else (value - rangeMin) / span
        return colorFor(normalized)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
