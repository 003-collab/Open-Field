package com.openfield.core.geo

import kotlin.math.*

enum class VerticalAdjustment(val label: String) {
    NONE("None"), CONSTANT("Constant"), INCLINED_PLANE("Inclined plane")
}

/** One control point: coordinates on the projection grid (from GNSS) and its known local coordinates. All metres. */
data class ControlPair(
    val name: String,
    val gridE: Double, val gridN: Double, val gridZ: Double,
    val localE: Double, val localN: Double, val localZ: Double,
    val useHorizontal: Boolean = true, val useVertical: Boolean = true
)

data class CalibrationResidual(val name: String, val dE: Double, val dN: Double, val dZ: Double) {
    val horizontal get() = hypot(dE, dN)
}

data class CalibrationReport(
    val scale: Double, val rotationDeg: Double, val translationE: Double, val translationN: Double,
    val residuals: List<CalibrationResidual>, val rmsH: Double, val rmsV: Double,
    val verticalMode: VerticalAdjustment
)

/**
 * Site calibration ("localization"): 2-D similarity transform (scale, rotation, translation) plus an optional
 * vertical constant or inclined plane. Maps projection-grid coordinates to the user's local site coordinates.
 */
class Localization(
    val a: Double, val b: Double, val tE: Double, val tN: Double,
    val verticalMode: VerticalAdjustment,
    val z0: Double, val dzConst: Double, val dzSlopeE: Double, val dzSlopeN: Double,
    val originE: Double, val originN: Double
) {
    val scale get() = hypot(a, b)
    val rotationDeg get() = atan2(b, a) / DEG

    /** grid -> local */
    fun forward(e: Double, n: Double, z: Double): DoubleArray {
        val le = a * e - b * n + tE
        val ln = b * e + a * n + tN
        val dz = when (verticalMode) {
            VerticalAdjustment.NONE -> 0.0
            VerticalAdjustment.CONSTANT -> dzConst
            VerticalAdjustment.INCLINED_PLANE -> dzConst + dzSlopeE * (e - originE) + dzSlopeN * (n - originN)
        }
        return doubleArrayOf(le, ln, z + dz)
    }

    /** local -> grid */
    fun inverse(le: Double, ln: Double, lz: Double): DoubleArray {
        val d = a * a + b * b
        val x = le - tE; val y = ln - tN
        val e = (a * x + b * y) / d
        val n = (-b * x + a * y) / d
        val dz = when (verticalMode) {
            VerticalAdjustment.NONE -> 0.0
            VerticalAdjustment.CONSTANT -> dzConst
            VerticalAdjustment.INCLINED_PLANE -> dzConst + dzSlopeE * (e - originE) + dzSlopeN * (n - originN)
        }
        return doubleArrayOf(e, n, lz - dz)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "a" to a, "b" to b, "tE" to tE, "tN" to tN, "verticalMode" to verticalMode.name,
        "dzConst" to dzConst, "dzSlopeE" to dzSlopeE, "dzSlopeN" to dzSlopeN, "originE" to originE, "originN" to originN
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): Localization {
            fun d(k: String) = (m[k] as? Number)?.toDouble() ?: 0.0
            return Localization(d("a"), d("b"), d("tE"), d("tN"),
                runCatching { VerticalAdjustment.valueOf(m["verticalMode"] as String) }.getOrDefault(VerticalAdjustment.NONE),
                0.0, d("dzConst"), d("dzSlopeE"), d("dzSlopeN"), d("originE"), d("originN"))
        }

        /** Least-squares fit. Needs >= 2 horizontal pairs; vertical needs >=1 (constant) or >=3 non-collinear (plane). */
        fun fit(pairs: List<ControlPair>, vertical: VerticalAdjustment): Pair<Localization, CalibrationReport> {
            val hp = pairs.filter { it.useHorizontal }
            require(hp.size >= 2) { "At least two horizontal control points are required" }
            val cx = hp.map { it.gridE }.average(); val cy = hp.map { it.gridN }.average()
            val cu = hp.map { it.localE }.average(); val cv = hp.map { it.localN }.average()
            var sxx = 0.0; var num1 = 0.0; var num2 = 0.0
            for (p in hp) {
                val x = p.gridE - cx; val y = p.gridN - cy; val u = p.localE - cu; val v = p.localN - cv
                sxx += x * x + y * y
                num1 += x * u + y * v
                num2 += x * v - y * u
            }
            require(sxx > 1e-9) { "Control points are coincident" }
            val a = num1 / sxx; val b = num2 / sxx
            val tE = cu - (a * cx - b * cy); val tN = cv - (b * cx + a * cy)

            val vp = pairs.filter { it.useVertical }
            var dzC = 0.0; var sE = 0.0; var sN = 0.0
            var mode = vertical
            val oE = cx; val oN = cy
            if (mode != VerticalAdjustment.NONE) {
                require(vp.isNotEmpty()) { "Vertical adjustment needs at least one vertical control point" }
                val dz = vp.map { it.localZ - it.gridZ }
                if (mode == VerticalAdjustment.INCLINED_PLANE) {
                    require(vp.size >= 3) { "Inclined plane needs at least three vertical control points" }
                    val sol = solvePlane(vp.map { it.gridE - oE }, vp.map { it.gridN - oN }, dz)
                    dzC = sol[0]; sE = sol[1]; sN = sol[2]
                } else {
                    dzC = dz.average()
                }
            }
            val loc = Localization(a, b, tE, tN, mode, 0.0, dzC, sE, sN, oE, oN)
            val res = pairs.map {
                val r = loc.forward(it.gridE, it.gridN, it.gridZ)
                CalibrationResidual(it.name, if (it.useHorizontal) it.localE - r[0] else 0.0,
                    if (it.useHorizontal) it.localN - r[1] else 0.0, if (it.useVertical && mode != VerticalAdjustment.NONE) it.localZ - r[2] else 0.0)
            }
            val hRes = res.filterIndexed { i, _ -> pairs[i].useHorizontal }
            val vRes = res.filterIndexed { i, _ -> pairs[i].useVertical && mode != VerticalAdjustment.NONE }
            val rmsH = if (hRes.isEmpty()) 0.0 else sqrt(hRes.sumOf { it.horizontal.pow(2) } / hRes.size)
            val rmsV = if (vRes.isEmpty()) 0.0 else sqrt(vRes.sumOf { it.dZ.pow(2) } / vRes.size)
            return loc to CalibrationReport(loc.scale, loc.rotationDeg, tE, tN, res, rmsH, rmsV, mode)
        }

        /** Solve dz = c0 + c1*x + c2*y by normal equations. */
        private fun solvePlane(x: List<Double>, y: List<Double>, z: List<Double>): DoubleArray {
            val n = x.size.toDouble()
            val sx = x.sum(); val sy = y.sum(); val sz = z.sum()
            val sxx = x.sumOf { it * it }; val syy = y.sumOf { it * it }; val sxy = x.indices.sumOf { x[it] * y[it] }
            val sxz = x.indices.sumOf { x[it] * z[it] }; val syz = y.indices.sumOf { y[it] * z[it] }
            val m = arrayOf(doubleArrayOf(n, sx, sy), doubleArrayOf(sx, sxx, sxy), doubleArrayOf(sy, sxy, syy))
            val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
            require(abs(det) > 1e-9) { "Vertical control points are collinear" }
            val inv = invert3(m)
            val rhs = doubleArrayOf(sz, sxz, syz)
            return DoubleArray(3) { r -> inv[r][0] * rhs[0] + inv[r][1] * rhs[1] + inv[r][2] * rhs[2] }
        }
    }
}
