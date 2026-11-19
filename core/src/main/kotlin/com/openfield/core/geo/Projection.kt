package com.openfield.core.geo

import kotlin.math.*

/** Map projection: geodetic radians <-> grid metres (before false origin units conversion). */
sealed interface Projection {
    val ellipsoid: Ellipsoid
    /** Returns [easting, northing] in metres. */
    fun forward(latRad: Double, lonRad: Double): DoubleArray
    /** Returns [lat rad, lon rad]. */
    fun inverse(e: Double, n: Double): DoubleArray
    val typeId: String
}

/** Transverse Mercator using the 6th-order Krueger series (mm-accurate over thousands of km). Covers UTM, Gauss-Kruger, state plane TM zones. */
class TransverseMercator(
    override val ellipsoid: Ellipsoid,
    val lat0Deg: Double, val lon0Deg: Double,
    val k0: Double, val falseEasting: Double, val falseNorthing: Double
) : Projection {
    override val typeId = "tm"
    private val n = ellipsoid.f / (2 - ellipsoid.f)
    private val aa = ellipsoid.a / (1 + n) * (1 + n * n / 4 + n.pow(4) / 64 + n.pow(6) / 256)
    private val alpha = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 5 * n.pow(3) / 16 + 41 * n.pow(4) / 180 - 127 * n.pow(5) / 288 + 7891 * n.pow(6) / 37800,
        13 * n * n / 48 - 3 * n.pow(3) / 5 + 557 * n.pow(4) / 1440 + 281 * n.pow(5) / 630 - 1983433 * n.pow(6) / 1935360,
        61 * n.pow(3) / 240 - 103 * n.pow(4) / 140 + 15061 * n.pow(5) / 26880 + 167603 * n.pow(6) / 181440,
        49561 * n.pow(4) / 161280 - 179 * n.pow(5) / 168 + 6601661 * n.pow(6) / 7257600,
        34729 * n.pow(5) / 80640 - 3418889 * n.pow(6) / 1995840,
        212378941 * n.pow(6) / 319334400
    )
    private val beta = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 37 * n.pow(3) / 96 - n.pow(4) / 360 - 81 * n.pow(5) / 512 + 96199 * n.pow(6) / 604800,
        n * n / 48 + n.pow(3) / 15 - 437 * n.pow(4) / 1440 + 46 * n.pow(5) / 105 - 1118711 * n.pow(6) / 3870720,
        17 * n.pow(3) / 480 - 37 * n.pow(4) / 840 - 209 * n.pow(5) / 4480 + 5569 * n.pow(6) / 90720,
        4397 * n.pow(4) / 161280 - 11 * n.pow(5) / 504 - 830251 * n.pow(6) / 7257600,
        4583 * n.pow(5) / 161280 - 108847 * n.pow(6) / 3991680,
        20648693 * n.pow(6) / 638668800
    )
    private val delta = doubleArrayOf(
        2 * n - 2 * n * n / 3 - 2 * n.pow(3) + 116 * n.pow(4) / 45 + 26 * n.pow(5) / 45 - 2854 * n.pow(6) / 675,
        7 * n * n / 3 - 8 * n.pow(3) / 5 - 227 * n.pow(4) / 45 + 2704 * n.pow(5) / 315 + 2323 * n.pow(6) / 945,
        56 * n.pow(3) / 15 - 136 * n.pow(4) / 35 - 1262 * n.pow(5) / 105 + 73814 * n.pow(6) / 2835,
        4279 * n.pow(4) / 630 - 332 * n.pow(5) / 35 - 399572 * n.pow(6) / 14175,
        4174 * n.pow(5) / 315 - 144838 * n.pow(6) / 6237,
        601676 * n.pow(6) / 22275
    )
    private val lon0 = lon0Deg * DEG
    private val xi0: Double

    init {
        val (xp, _) = conformal(lat0Deg * DEG, 0.0)
        var x = xp
        for (j in 1..6) x += alpha[j - 1] * sin(2 * j * xp)
        xi0 = x
    }

    /** Returns (xi', eta') for the conformal sphere. */
    private fun conformal(lat: Double, dLon: Double): Pair<Double, Double> {
        val e = ellipsoid.e
        val sinLat = sin(lat)
        val t = sinh(atanh(sinLat) - e * atanh(e * sinLat))
        val xp = atan2(t, cos(dLon))
        val ep = asinh(sin(dLon) / hypot(t, cos(dLon)))
        return xp to ep
    }

    override fun forward(latRad: Double, lonRad: Double): DoubleArray {
        val dLon = wrap(lonRad - lon0)
        val (xp, ep) = conformal(latRad, dLon)
        var xi = xp; var eta = ep
        for (j in 1..6) {
            xi += alpha[j - 1] * sin(2 * j * xp) * cosh(2 * j * ep)
            eta += alpha[j - 1] * cos(2 * j * xp) * sinh(2 * j * ep)
        }
        return doubleArrayOf(falseEasting + k0 * aa * eta, falseNorthing + k0 * aa * (xi - xi0))
    }

    override fun inverse(e: Double, n: Double): DoubleArray {
        val xi = (n - falseNorthing) / (k0 * aa) + xi0
        val eta = (e - falseEasting) / (k0 * aa)
        var xp = xi; var ep = eta
        for (j in 1..6) {
            xp -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            ep -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xp) / cosh(ep))
        var lat = chi
        for (j in 1..6) lat += delta[j - 1] * sin(2 * j * chi)
        val lon = lon0 + atan2(sinh(ep), cos(xp))
        return doubleArrayOf(lat, wrap(lon))
    }

    private fun wrap(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }

    companion object {
        /** Standard UTM zone on [ellipsoid] (WGS84 by default). */
        fun utm(zone: Int, south: Boolean, ellipsoid: Ellipsoid = Ellipsoid.WGS84) =
            TransverseMercator(ellipsoid, 0.0, zone * 6.0 - 183.0, 0.9996, 500000.0, if (south) 10000000.0 else 0.0)

        fun utmZoneFor(latDeg: Double, lonDeg: Double): Int = (floor((lonDeg + 180.0) / 6.0).toInt() % 60) + 1
    }
}

/** Lambert Conformal Conic, two standard parallels (EPSG method 9802). Covers most US state plane zones. */
class LambertConic2SP(
    override val ellipsoid: Ellipsoid,
    val lat0Deg: Double, val lon0Deg: Double,
    val lat1Deg: Double, val lat2Deg: Double,
    val falseEasting: Double, val falseNorthing: Double
) : Projection {
    override val typeId = "lcc2sp"
    private val e = ellipsoid.e
    private val a = ellipsoid.a

    private fun m(phi: Double) = cos(phi) / sqrt(1 - ellipsoid.e2 * sin(phi).pow(2))
    private fun t(phi: Double): Double {
        val s = sin(phi)
        return tan(PI / 4 - phi / 2) / ((1 - e * s) / (1 + e * s)).pow(e / 2)
    }

    private val phi0 = lat0Deg * DEG
    private val phi1 = lat1Deg * DEG
    private val phi2 = lat2Deg * DEG
    private val lam0 = lon0Deg * DEG
    private val nCone: Double
    private val bigF: Double
    private val r0: Double

    init {
        val m1 = m(phi1); val m2 = m(phi2)
        val t1 = t(phi1); val t2 = t(phi2); val t0 = t(phi0)
        nCone = if (abs(phi1 - phi2) < 1e-12) sin(phi1) else (ln(m1) - ln(m2)) / (ln(t1) - ln(t2))
        bigF = m1 / (nCone * t1.pow(nCone))
        r0 = a * bigF * t0.pow(nCone)
    }

    override fun forward(latRad: Double, lonRad: Double): DoubleArray {
        val r = a * bigF * t(latRad).pow(nCone)
        val theta = nCone * (lonRad - lam0)
        return doubleArrayOf(falseEasting + r * sin(theta), falseNorthing + r0 - r * cos(theta))
    }

    override fun inverse(e: Double, n: Double): DoubleArray {
        val dx = e - falseEasting
        val dy = r0 - (n - falseNorthing)
        val rp = sign(nCone) * hypot(dx, dy)
        val tp = (rp / (a * bigF)).pow(1.0 / nCone)
        val theta = atan2(sign(nCone) * dx, sign(nCone) * dy)
        var phi = PI / 2 - 2 * atan(tp)
        repeat(10) {
            val s = sin(phi)
            phi = PI / 2 - 2 * atan(tp * ((1 - this.e * s) / (1 + this.e * s)).pow(this.e / 2))
        }
        return doubleArrayOf(phi, theta / nCone + lam0)
    }
}
