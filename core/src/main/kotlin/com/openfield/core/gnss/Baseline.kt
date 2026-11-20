package com.openfield.core.gnss

import com.openfield.core.geo.Ellipsoid
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Reference position of a base station in Earth-centred Earth-fixed metres (for example from RTCM message 1005/1006). */
data class BasePosition(val id: String, val x: Double, val y: Double, val z: Double, val receivedAtMillis: Long = 0, val source: String = "RTCM") {
    val isValid: Boolean get() = abs(x) + abs(y) + abs(z) > 1000.0
}

/**
 * An RTK baseline vector from a base station to the rover antenna, in the Earth-centred Earth-fixed frame, with its variance-covariance matrix.
 * This is the observation a network least squares adjustment uses, instead of the rover's absolute position.
 *
 * - The vector ends at the rover antenna reference point as reported by the receiver, before the antenna height is reduced to the ground mark.
 * - [cov] holds the upper triangle of the 3x3 covariance in m^2: xx, xy, xz, yy, yz, zz. Null when the receiver reported no precision.
 * - The base position is the reference point of the base station, in the frame of the correction source (WGS 84 / ITRF / NAD83(2011) are treated as identical here).
 */
data class BaselineVector(
    val baseId: String,
    val baseX: Double, val baseY: Double, val baseZ: Double,
    val dX: Double, val dY: Double, val dZ: Double,
    val cov: List<Double>? = null,
    val source: String = "derived"
) {
    init { require(cov == null || cov.size == 6) { "Covariance needs 6 values: xx, xy, xz, yy, yz, zz" } }

    val length: Double get() = sqrt(dX * dX + dY * dY + dZ * dZ)
    val roverX: Double get() = baseX + dX
    val roverY: Double get() = baseY + dY
    val roverZ: Double get() = baseZ + dZ

    /** Standard deviations of the x, y and z components (m), or null without a covariance. */
    fun sigmaXyz(): DoubleArray? = cov?.let { doubleArrayOf(sqrt(it[0]), sqrt(it[3]), sqrt(it[5])) }

    /** Vector components in the local east, north, up frame at the base (m). */
    fun enu(ellipsoid: Ellipsoid = Ellipsoid.WGS84): DoubleArray {
        val g = ellipsoid.fromEcef(baseX, baseY, baseZ)
        val r = enuToEcef(g[0], g[1])
        // transpose: local = R^T * d
        return doubleArrayOf(
            r[0][0] * dX + r[1][0] * dY + r[2][0] * dZ,
            r[0][1] * dX + r[1][1] * dY + r[2][1] * dZ,
            r[0][2] * dX + r[1][2] * dY + r[2][2] * dZ
        )
    }

    companion object {
        /** Rotation matrix whose columns are the east, north and up unit vectors in ECEF at ([latRad], [lonRad]). */
        fun enuToEcef(latRad: Double, lonRad: Double): Array<DoubleArray> {
            val sl = sin(latRad); val cl = cos(latRad); val so = sin(lonRad); val co = cos(lonRad)
            return arrayOf(
                doubleArrayOf(-so, -sl * co, cl * co),
                doubleArrayOf(co, -sl * so, cl * so),
                doubleArrayOf(0.0, cl, sl)
            )
        }

        /** Covariance in ECEF from independent east/north/up standard deviations at ([latRad], [lonRad]). */
        fun covFromEnu(sigmaE: Double, sigmaN: Double, sigmaU: Double, latRad: Double, lonRad: Double): List<Double> {
            val r = enuToEcef(latRad, lonRad)
            val s = doubleArrayOf(sigmaE * sigmaE, sigmaN * sigmaN, sigmaU * sigmaU)
            fun c(i: Int, j: Int) = s[0] * r[i][0] * r[j][0] + s[1] * r[i][1] * r[j][1] + s[2] * r[i][2] * r[j][2]
            return listOf(c(0, 0), c(0, 1), c(0, 2), c(1, 1), c(1, 2), c(2, 2))
        }

        /** A vector given in ECEF components. */
        fun fromEcef(base: BasePosition, dx: Double, dy: Double, dz: Double, cov: List<Double>? = null, source: String = "receiver") =
            BaselineVector(base.id, base.x, base.y, base.z, dx, dy, dz, cov, source)

        /** A vector given as east, north, up components in the local frame at the base. */
        fun fromEnu(
            base: BasePosition, de: Double, dn: Double, du: Double,
            sigmaE: Double? = null, sigmaN: Double? = null, sigmaU: Double? = null, source: String = "receiver",
            ellipsoid: Ellipsoid = Ellipsoid.WGS84
        ): BaselineVector {
            val g = ellipsoid.fromEcef(base.x, base.y, base.z)
            val r = enuToEcef(g[0], g[1])
            val cov = if (sigmaE != null && sigmaN != null && sigmaU != null) covFromEnu(sigmaE, sigmaN, sigmaU, g[0], g[1]) else null
            return BaselineVector(
                base.id, base.x, base.y, base.z,
                r[0][0] * de + r[0][1] * dn + r[0][2] * du, r[1][0] * de + r[1][1] * dn + r[1][2] * du, r[2][0] * de + r[2][1] * dn + r[2][2] * du,
                cov, source
            )
        }

        /**
         * The vector implied by an RTK position and the base's reference position: rover ECEF minus base ECEF. Works with any receiver that
         * outputs a position with enough digits; the covariance comes from the receiver's reported latitude/longitude/height deviations.
         */
        fun derive(base: BasePosition, s: GnssSnapshot, ellipsoid: Ellipsoid = Ellipsoid.WGS84): BaselineVector {
            val lat = Math.toRadians(s.latDeg); val lon = Math.toRadians(s.lonDeg)
            val p = ellipsoid.toEcef(lat, lon, s.ellipsoidalHeight)
            val cov = if (s.sigmaLat != null && s.sigmaLon != null && s.sigmaAlt != null) covFromEnu(s.sigmaLon, s.sigmaLat, s.sigmaAlt, lat, lon) else null
            return BaselineVector(base.id, base.x, base.y, base.z, p[0] - base.x, p[1] - base.y, p[2] - base.z, cov, "derived (${base.source})")
        }

        /**
         * The mean of several epochs of one baseline, or null if they do not share the same base. The covariance is the mean of the epoch
         * covariances (epochs of one RTK solution are strongly correlated, so it is not divided by the number of epochs).
         */
        fun average(list: List<BaselineVector>): BaselineVector? {
            if (list.isEmpty()) return null
            val first = list[0]
            if (list.any { it.baseId != first.baseId || abs(it.baseX - first.baseX) > 0.01 || abs(it.baseY - first.baseY) > 0.01 || abs(it.baseZ - first.baseZ) > 0.01 }) return null
            val n = list.size.toDouble()
            val cov = if (list.all { it.cov != null }) List(6) { i -> list.sumOf { it.cov!![i] } / n } else null
            return first.copy(dX = list.sumOf { it.dX } / n, dY = list.sumOf { it.dY } / n, dZ = list.sumOf { it.dZ } / n, cov = cov)
        }
    }
}
