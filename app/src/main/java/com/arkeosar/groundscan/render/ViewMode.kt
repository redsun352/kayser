package com.arkeosar.groundscan.render

/**
 * The three display modes selectable from the mode switcher in
 * [com.arkeosar.groundscan.ui.ScanActivity]:
 *
 * - TOP_DOWN_2D: flat top-down heatmap, like a map view.
 * - SURFACE_3D: the existing TPS-interpolated lit surface (height + color).
 * - VOLUMETRIC_3D: a schematic 3D block showing [SchematicDepthModel]'s
 *   projected pseudo-depth volume. Always shown with a visible
 *   "şematik/tahmini" label since it is a modeled projection, not a
 *   measurement - see [SchematicDepthModel]'s documentation for why.
 */
enum class ViewMode {
    TOP_DOWN_2D,
    SURFACE_3D,
    VOLUMETRIC_3D
}
