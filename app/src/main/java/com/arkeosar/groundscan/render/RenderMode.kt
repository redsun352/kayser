package com.arkeosar.groundscan.render

/**
 * Visualization mode selectable from the left-side toolbar, matching the
 * grid / wireframe / surface / point-cloud switcher seen in ArkeoMag /
 * Thuban Lodestar's 3D view. [HeightmapRenderer] reads this to decide
 * how to draw the current mesh: as filled, lit triangles (SURFACE), as
 * an unlit wireframe outline (WIREFRAME), as a flat reference grid
 * (GRID), or as discrete points at each sample location (POINT_CLOUD).
 */
enum class RenderMode {
    GRID,
    WIREFRAME,
    SURFACE,
    POINT_CLOUD
}
