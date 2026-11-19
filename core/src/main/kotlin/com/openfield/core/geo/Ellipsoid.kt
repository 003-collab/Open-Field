package com.openfield.core.geo

import kotlin.math.*

const val DEG = PI / 180.0

enum class LinearUnit(val label: String, val metersPerUnit: Double) {
    METER("Meters", 1.0),
    US_SURVEY_FOOT("US survey feet", 1200.0 / 3937.0),
    INTL_FOOT("International feet", 0.3048);

    fun fromMeters(m: Double) = m / metersPerUnit
    fun toMeters(v: Double) = v * metersPerUnit
}

data class Ellipsoid(val name: String, val a: Double, val invF: Double) {
    val f: Double = if (invF == 0.0) 0.0 else 1.0 / invF
    val b: Double = a * (1 - f)
    val e2: Double = f * (2 - f)
    val ep2: Double = e2 / (1 - e2)
    val e: Double = sqrt(e2)

    /** Geodetic (radians, metres) to Earth-centred Earth-fixed XYZ. */
    fun toEcef(latRad: Double, lonRad: Double, h: Double): DoubleArray {
        val sl = sin(latRad); val cl = cos(latRad)
        val n = a / sqrt(1 - e2 * sl * sl)
        return doubleArrayOf((n + h) * cl * cos(lonRad), (n + h) * cl * sin(lonRad), (n * (1 - e2) + h) * sl)
    }

    /** ECEF to geodetic [lat rad, lon rad, h] using Bowring iteration (converges to sub-mm in 3-4 passes). */
    fun fromEcef(x: Double, y: Double, z: Double): DoubleArray {
        val lon = atan2(y, x)
        val p = hypot(x, y)
        var lat = atan2(z, p * (1 - e2))
        var h = 0.0
        repeat(8) {
            val sl = sin(lat)
            val n = a / sqrt(1 - e2 * sl * sl)
            h = p / cos(lat) - n
            lat = atan2(z, p * (1 - e2 * n / (n + h)))
        }
        return doubleArrayOf(lat, lon, h)
    }

    companion object {
        val WGS84 = Ellipsoid("WGS 84", 6378137.0, 298.257223563)
        val GRS80 = Ellipsoid("GRS 80", 6378137.0, 298.257222101)
        val CLARKE1866 = Ellipsoid("Clarke 1866", 6378206.4, 294.9786982)
        val INTERNATIONAL1924 = Ellipsoid("International 1924", 6378388.0, 297.0)
        val BESSEL1841 = Ellipsoid("Bessel 1841", 6377397.155, 299.1528128)
        val AIRY1830 = Ellipsoid("Airy 1830", 6377563.396, 299.3249646)
        val KRASSOVSKY = Ellipsoid("Krassovsky 1940", 6378245.0, 298.3)

        val all = listOf(WGS84, GRS80, CLARKE1866, INTERNATIONAL1924, BESSEL1841, AIRY1830, KRASSOVSKY)
        fun byName(name: String): Ellipsoid? = all.firstOrNull { it.name.equals(name, true) }
    }
}

/** 7-parameter Helmert (position-vector convention). Translations in m, rotations in arc-seconds, scale in ppm. */
data class Helmert7(
    val tx: Double = 0.0, val ty: Double = 0.0, val tz: Double = 0.0,
    val rxArcsec: Double = 0.0, val ryArcsec: Double = 0.0, val rzArcsec: Double = 0.0,
    val scalePpm: Double = 0.0
) {
    val isIdentity get() = this == Helmert7()

    private fun matrix(): Array<DoubleArray> {
        val k = PI / (180.0 * 3600.0)
        val rx = rxArcsec * k; val ry = ryArcsec * k; val rz = rzArcsec * k
        return arrayOf(doubleArrayOf(1.0, -rz, ry), doubleArrayOf(rz, 1.0, -rx), doubleArrayOf(-ry, rx, 1.0))
    }

    fun apply(x: Double, y: Double, z: Double): DoubleArray {
        val m = matrix(); val s = 1 + scalePpm * 1e-6
        return doubleArrayOf(
            tx + s * (m[0][0] * x + m[0][1] * y + m[0][2] * z),
            ty + s * (m[1][0] * x + m[1][1] * y + m[1][2] * z),
            tz + s * (m[2][0] * x + m[2][1] * y + m[2][2] * z)
        )
    }

    fun applyInverse(x: Double, y: Double, z: Double): DoubleArray {
        val m = matrix(); val s = 1 + scalePpm * 1e-6
        val v = doubleArrayOf((x - tx) / s, (y - ty) / s, (z - tz) / s)
        val inv = invert3(m)
        return doubleArrayOf(
            inv[0][0] * v[0] + inv[0][1] * v[1] + inv[0][2] * v[2],
            inv[1][0] * v[0] + inv[1][1] * v[1] + inv[1][2] * v[2],
            inv[2][0] * v[0] + inv[2][1] * v[1] + inv[2][2] * v[2]
        )
    }
}

internal fun invert3(m: Array<DoubleArray>): Array<DoubleArray> {
    val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
    val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
    val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    require(abs(det) > 1e-18) { "Singular matrix" }
    return arrayOf(
        doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
        doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
        doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    )
}

/** A horizontal datum: ellipsoid plus the transformation FROM WGS 84 (the receiver's frame) TO this datum. */
data class Datum(val name: String, val ellipsoid: Ellipsoid, val fromWgs84: Helmert7 = Helmert7()) {
    companion object {
        val WGS84 = Datum("WGS 84", Ellipsoid.WGS84)
        /** NAD83 treated as coincident with WGS 84 (typical for real-time RTK against CORS/network corrections). */
        val NAD83 = Datum("NAD 83", Ellipsoid.GRS80)
        val ETRS89 = Datum("ETRS89", Ellipsoid.GRS80)
        val builtin = listOf(WGS84, NAD83, ETRS89)
    }
}
