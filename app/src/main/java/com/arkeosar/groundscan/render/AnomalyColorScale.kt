package com.arkeosar.groundscan.render

/**
 * Color palette selectable for anomaly visualization, matching the
 * "Grafik Renkleri" picker in ArkeoMag / Thuban Lodestar's 3D view
 * (which offers several named palettes, e.g. "Chlorophyll"). These are
 * standard, widely published scientific colormaps (several originate
 * from matplotlib/ParaView-style visualization conventions, which are
 * generic color-stop recipes, not anyone's proprietary asset) plus a
 * few of ArkeoSAR's own simple gradients. Implemented from scratch here
 * as plain RGB stop lists.
 */
enum class ColorPalette(val label: String) {
    THERMAL("Termal"),               // blue -> green -> amber -> red (ArkeoSAR's original default)
    JET("Jet"),                      // classic blue -> cyan -> yellow -> red rainbow
    CHLOROPHYLL("Chlorophyll"),      // deep blue -> teal -> green -> yellow -> red
    GRAYSCALE("Gri Tonlama"),
    VIRIDIS("Viridis"),              // perceptually-uniform purple -> green -> yellow
    PLASMA("Plasma"),                // perceptually-uniform purple -> magenta -> orange -> yellow
    EARTH("Toprak"),                 // browns and tans, useful for soil-context overlays
    OCEAN("Okyanus"),                // deep navy -> teal -> white
    COPPER("Bakır"),                 // black -> copper/orange, classic metal-detection feel
    MONOCHROME_AMBER("Amber");       // single-hue amber ramp, low -> high brightness

    companion object {
        fun next(current: ColorPalette): ColorPalette {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }
    }
}

/**
 * Maps a magnetic anomaly reading to an RGB color using the currently
 * selected [ColorPalette]. Stop lists are defined per-palette below;
 * interpolation between stops is linear, same as the original
 * single-palette implementation this replaces.
 */
object AnomalyColorScale {

    data class Rgb(val r: Float, val g: Float, val b: Float)

    /** The palette used by [colorFor] / [colorForRaw]. Change this to switch palettes app-wide. */
    var activePalette: ColorPalette = ColorPalette.THERMAL

    private fun rgb(hex: Int): Rgb = Rgb(
        ((hex shr 16) and 0xFF) / 255f,
        ((hex shr 8) and 0xFF) / 255f,
        (hex and 0xFF) / 255f
    )

    private fun stopsFor(palette: ColorPalette): List<Pair<Float, Rgb>> = when (palette) {
        ColorPalette.THERMAL -> listOf(
            0.00f to rgb(0x1C5FA8),
            0.45f to rgb(0x2DBE6C),
            0.80f to rgb(0xE8A33D),
            1.00f to rgb(0xE0483B)
        )
        ColorPalette.JET -> listOf(
            0.00f to rgb(0x00007F),
            0.25f to rgb(0x0000FF),
            0.50f to rgb(0x00FFFF),
            0.75f to rgb(0xFFFF00),
            1.00f to rgb(0xFF0000)
        )
        ColorPalette.CHLOROPHYLL -> listOf(
            0.00f to rgb(0x05214F),
            0.30f to rgb(0x0E6E6E),
            0.55f to rgb(0x2DBE6C),
            0.80f to rgb(0xD9D14A),
            1.00f to rgb(0xE0483B)
        )
        ColorPalette.GRAYSCALE -> listOf(
            0.00f to rgb(0x101010),
            1.00f to rgb(0xF0F0F0)
        )
        ColorPalette.VIRIDIS -> listOf(
            0.00f to rgb(0x440154),
            0.33f to rgb(0x3B528B),
            0.66f to rgb(0x21908C),
            0.85f to rgb(0x5DC863),
            1.00f to rgb(0xFDE725)
        )
        ColorPalette.PLASMA -> listOf(
            0.00f to rgb(0x0D0887),
            0.33f to rgb(0x9C179E),
            0.66f to rgb(0xED7953),
            1.00f to rgb(0xF0F921)
        )
        ColorPalette.EARTH -> listOf(
            0.00f to rgb(0x2B1B0E),
            0.40f to rgb(0x6B4423),
            0.70f to rgb(0xA9784E),
            1.00f to rgb(0xE3C68A)
        )
        ColorPalette.OCEAN -> listOf(
            0.00f to rgb(0x021B33),
            0.50f to rgb(0x0E6E6E),
            1.00f to rgb(0xE8F4F2)
        )
        ColorPalette.COPPER -> listOf(
            0.00f to rgb(0x140A05),
            0.50f to rgb(0x8A4A1F),
            1.00f to rgb(0xF2A35C)
        )
        ColorPalette.MONOCHROME_AMBER -> listOf(
            0.00f to rgb(0x1A1206),
            1.00f to rgb(0xE8A33D)
        )
    }

    /**
     * @param normalized a value in [0, 1] representing where the reading
     *   falls between the scan's observed minimum and maximum.
     */
    fun colorFor(normalized: Float, palette: ColorPalette = activePalette): Rgb {
        val stops = stopsFor(palette)
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

    fun colorForRaw(value: Float, rangeMin: Float, rangeMax: Float, palette: ColorPalette = activePalette): Rgb {
        val span = rangeMax - rangeMin
        val normalized = if (span <= 0f) 0.5f else (value - rangeMin) / span
        return colorFor(normalized, palette)
    }

    /** All stop colors for [palette], for drawing a colorbar preview swatch. */
    fun previewStops(palette: ColorPalette): List<Rgb> = stopsFor(palette).map { it.second }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
