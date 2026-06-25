package com.arkeosar.groundscan.render

import kotlin.math.sqrt

/**
 * The set of axis-combination functions selectable in the "Fonksiyon"
 * picker, matching the d(X), d(Y), d(Z), d(XY), d(YZ), d(XZ), d(XYZ)
 * options shown in ArkeoMag / Thuban Lodestar. Each one turns a raw
 * 3-axis magnetometer sample into the single scalar that gets plotted
 * as the surface height / color.
 *
 * Since [com.arkeosar.groundscan.data.ScanGrid] already stores a single
 * scalar reading per point (the value the active source computed), this
 * enum's job in this app is mainly to label what that scalar represents
 * (and, when raw X/Y/Z components are available from a source, to let
 * the user pick which combination to display). The math itself is
 * elementary vector-magnitude arithmetic, not anyone's proprietary
 * algorithm.
 */
enum class DisplayFunction(val label: String) {
    X("d₍X₎ = X"),
    Y("d₍Y₎ = Y"),
    Z("d₍Z₎ = Z"),
    XY("d₍XY₎ = √(X² + Y²)"),
    YZ("d₍YZ₎ = √(Y² + Z²)"),
    XZ("d₍XZ₎ = √(X² + Z²)"),
    XYZ("d₍XYZ₎ = √(X² + Y² + Z²)");

    fun apply(x: Float, y: Float, z: Float): Float = when (this) {
        X -> x
        Y -> y
        Z -> z
        XY -> sqrt(x * x + y * y)
        YZ -> sqrt(y * y + z * z)
        XZ -> sqrt(x * x + z * z)
        XYZ -> sqrt(x * x + y * y + z * z)
    }
}
