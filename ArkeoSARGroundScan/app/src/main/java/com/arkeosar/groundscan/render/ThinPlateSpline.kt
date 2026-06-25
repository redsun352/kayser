package com.arkeosar.groundscan.render

import kotlin.math.ln

/**
 * Thin Plate Spline (TPS) interpolation, used to turn a sparse grid of
 * scan measurements into a smooth, continuous surface - the same general
 * technique behind "İnce Plaka" style interpolation in scientific
 * surface-plotting tools (e.g. Surfer-style gridding). This is a
 * standard, well-published numerical method (Duchon, 1977); the
 * implementation below is written from scratch for ArkeoSAR Ground Scan.
 *
 * TPS fits a function f(x, y) through a set of control points that
 * minimizes bending energy, which is what gives the resulting surface
 * its characteristic smooth, "draped cloth" look instead of sharp,
 * faceted grid cells.
 *
 * The math: for control points P_i = (x_i, y_i, z_i), TPS solves for
 * weights w_i and an affine part (a0, a1, a2) such that
 *   f(x, y) = a0 + a1*x + a2*y + sum_i( w_i * U(|P - P_i|) )
 * where U(r) = r^2 * ln(r) is the TPS radial basis kernel, subject to
 * f(x_i, y_i) = z_i and the side conditions that make the system solvable
 * (sum(w_i) = 0, sum(w_i * x_i) = 0, sum(w_i * y_i) = 0).
 *
 * A small regularization term (lambda) is supported: lambda = 0 gives an
 * exact interpolant through every control point; lambda > 0 trades exact
 * fit for extra smoothness, which is useful for noisy sensor data.
 * Performance note: fitting is O(n^3) in the number of *filled* control
 * points (Gaussian elimination on an (n+3)x(n+3) system), and evaluating
 * the fitted surface at each output grid cell is O(n) per cell. For very
 * large scans (many hundreds of filled points) this can get slow; if
 * that happens in practice, the usual fix is to fit on a downsampled
 * subset of control points (e.g. every Nth point) while still
 * evaluating on the full dense output grid - the visual smoothness is
 * barely affected, but fit time drops a lot. Not implemented here to
 * keep this class simple; add a `maxControlPoints` parameter to `fit()`
 * if a real scan turns out to need it.
 */
class ThinPlateSpline private constructor(
    private val controlX: DoubleArray,
    private val controlY: DoubleArray,
    private val weights: DoubleArray,   // size n
    private val affine: DoubleArray     // size 3: [a0, a1, a2]
) {
    private val n = controlX.size

    fun evaluate(x: Double, y: Double): Double {
        var sum = affine[0] + affine[1] * x + affine[2] * y
        for (i in 0 until n) {
            val dx = x - controlX[i]
            val dy = y - controlY[i]
            val r2 = dx * dx + dy * dy
            if (r2 > 1e-12) {
                sum += weights[i] * 0.5 * r2 * ln(r2)
            }
        }
        return sum
    }

    companion object {
        /**
         * Builds a TPS interpolant from control points.
         *
         * @param lambda regularization strength (0 = exact interpolation).
         *   A small positive value (e.g. 0.001 - 0.1 relative to the data
         *   scale) is recommended for real sensor data, since exact TPS
         *   through every noisy sample tends to ring/overshoot between
         *   points.
         */
        fun fit(xs: DoubleArray, ys: DoubleArray, zs: DoubleArray, lambda: Double = 0.0): ThinPlateSpline {
            val n = xs.size
            require(n == ys.size && n == zs.size) { "x, y, z arrays must have equal length" }
            require(n >= 3) { "TPS needs at least 3 control points" }

            // Build the (n+3) x (n+3) linear system:
            // [ K + lambda*I   P ] [ w ]   [ z ]
            // [ P^T            0 ] [ a ] = [ 0 ]
            // where K_ij = U(|P_i - P_j|), P_i row = [1, x_i, y_i]
            val size = n + 3
            val a = Array(size) { DoubleArray(size) }
            val b = DoubleArray(size)

            for (i in 0 until n) {
                for (j in 0 until n) {
                    if (i == j) {
                        a[i][j] = lambda
                    } else {
                        val dx = xs[i] - xs[j]
                        val dy = ys[i] - ys[j]
                        val r2 = dx * dx + dy * dy
                        a[i][j] = if (r2 > 1e-12) 0.5 * r2 * ln(r2) else 0.0
                    }
                }
                a[i][n] = 1.0
                a[i][n + 1] = xs[i]
                a[i][n + 2] = ys[i]
                a[n][i] = 1.0
                a[n + 1][i] = xs[i]
                a[n + 2][i] = ys[i]
                b[i] = zs[i]
            }
            // bottom-right 3x3 block stays zero; b[n..n+2] stay zero.

            val solution = solveLinearSystem(a, b, size)

            val weights = DoubleArray(n) { solution[it] }
            val affine = doubleArrayOf(solution[n], solution[n + 1], solution[n + 2])

            return ThinPlateSpline(xs.copyOf(), ys.copyOf(), weights, affine)
        }

        /** Simple Gaussian elimination with partial pivoting. Adequate for the grid sizes used here (a few hundred control points). */
        private fun solveLinearSystem(aIn: Array<DoubleArray>, bIn: DoubleArray, size: Int): DoubleArray {
            val a = Array(size) { i -> aIn[i].copyOf() }
            val b = bIn.copyOf()

            for (col in 0 until size) {
                var pivotRow = col
                var maxAbs = kotlin.math.abs(a[col][col])
                for (row in col + 1 until size) {
                    val v = kotlin.math.abs(a[row][col])
                    if (v > maxAbs) {
                        maxAbs = v
                        pivotRow = row
                    }
                }
                if (pivotRow != col) {
                    val tmpRow = a[col]; a[col] = a[pivotRow]; a[pivotRow] = tmpRow
                    val tmpB = b[col]; b[col] = b[pivotRow]; b[pivotRow] = tmpB
                }

                val pivot = a[col][col]
                if (kotlin.math.abs(pivot) < 1e-12) continue // singular-ish; skip to avoid NaN explosion

                for (row in col + 1 until size) {
                    val factor = a[row][col] / pivot
                    if (factor == 0.0) continue
                    for (k in col until size) {
                        a[row][k] -= factor * a[col][k]
                    }
                    b[row] -= factor * b[col]
                }
            }

            val x = DoubleArray(size)
            for (row in size - 1 downTo 0) {
                var sum = b[row]
                for (col in row + 1 until size) {
                    sum -= a[row][col] * x[col]
                }
                val diag = a[row][row]
                x[row] = if (kotlin.math.abs(diag) > 1e-12) sum / diag else 0.0
            }
            return x
        }
    }
}
