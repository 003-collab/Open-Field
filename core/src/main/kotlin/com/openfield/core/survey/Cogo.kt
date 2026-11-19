package com.openfield.core.survey

import kotlin.math.*

enum class AngleFormat(val label: String) { DEGREES_DECIMAL("Decimal degrees"), DMS("Degrees-minutes-seconds"), GONS("Gons") }

object Angles {
    fun normalize360(d: Double): Double { var x = d % 360.0; if (x < 0) x += 360.0; return x }

    fun format(deg: Double, fmt: AngleFormat, decimals: Int = 4): String = when (fmt) {
        AngleFormat.DEGREES_DECIMAL -> "%.${decimals}f°".format(normalize360(deg))
        AngleFormat.GONS -> "%.${decimals}f g".format(normalize360(deg) * 400.0 / 360.0)
        AngleFormat.DMS -> {
            val total = normalize360(deg) * 3600.0
            val scale = 10.0.pow(max(0, decimals - 2))
            val rounded = Math.round(total * scale) / scale
            val d = (rounded / 3600).toInt(); val m = ((rounded % 3600) / 60).toInt(); val s = rounded % 60
            "%d°%02d'%0${3 + max(0, decimals - 2)}.${max(0, decimals - 2)}f\"".format(d, m, s)
        }
    }

    /** Parses "123.4567", "123 45 67.8" or "123°45'06.7\"" into decimal degrees. */
    fun parse(text: String): Double? {
        val t = text.trim()
        t.toDoubleOrNull()?.let { return it }
        val parts = t.replace("°", " ").replace("'", " ").replace("\"", " ").trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        if (parts.isEmpty() || parts.size > 3) return null
        val neg = t.startsWith("-")
        val v = abs(parts[0]) + (parts.getOrNull(1) ?: 0.0) / 60.0 + (parts.getOrNull(2) ?: 0.0) / 3600.0
        return if (neg) -v else v
    }
}

data class Inverse(val azimuthDeg: Double, val horizontalDistance: Double, val deltaE: Double, val deltaN: Double, val deltaZ: Double, val slopeDistance: Double) {
    /** Grade in percent, null when the points are coincident in plan. */
    val gradePercent get() = if (horizontalDistance > 1e-9) deltaZ / horizontalDistance * 100.0 else null
}

object Cogo {
    /** Azimuth is measured clockwise from grid north. */
    fun inverse(e1: Double, n1: Double, z1: Double, e2: Double, n2: Double, z2: Double): Inverse {
        val de = e2 - e1; val dn = n2 - n1; val dz = z2 - z1
        val hd = hypot(de, dn)
        return Inverse(Angles.normalize360(Math.toDegrees(atan2(de, dn))), hd, de, dn, dz, sqrt(hd * hd + dz * dz))
    }

    /** Point at [distance] along [azimuthDeg] from the start. */
    fun forward(e: Double, n: Double, azimuthDeg: Double, distance: Double): Pair<Double, Double> {
        val a = Math.toRadians(azimuthDeg)
        return (e + distance * sin(a)) to (n + distance * cos(a))
    }

    /** Intersection of two bearings from two points; returns null when parallel. */
    fun bearingBearing(e1: Double, n1: Double, az1: Double, e2: Double, n2: Double, az2: Double): Pair<Double, Double>? {
        val a1 = Math.toRadians(az1); val a2 = Math.toRadians(az2)
        val d1e = sin(a1); val d1n = cos(a1); val d2e = sin(a2); val d2n = cos(a2)
        val det = d1e * (-d2n) - d1n * (-d2e)
        if (abs(det) < 1e-12) return null
        val t = ((e2 - e1) * (-d2n) - (n2 - n1) * (-d2e)) / det
        return (e1 + t * d1e) to (n1 + t * d1n)
    }

    /** Polygon area (shoelace), always positive. */
    fun area(points: List<Pair<Double, Double>>): Double {
        if (points.size < 3) return 0.0
        var s = 0.0
        for (i in points.indices) { val (x1, y1) = points[i]; val (x2, y2) = points[(i + 1) % points.size]; s += x1 * y2 - x2 * y1 }
        return abs(s) / 2.0
    }
}
