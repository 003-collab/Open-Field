package com.openfield.core.survey

import kotlin.math.*

/** Which way is "up" on the stakeout screen / how deltas are expressed. */
enum class StakeoutOrientation(val label: String) {
    TOWARDS_POINT("Towards point (up = direction to target)"),
    NORTH("North up"),
    TRAVEL_DIRECTION("Direction of travel")
}

data class StakeoutGuidance(
    val azimuthToTargetDeg: Double,
    val distance: Double,
    val deltaE: Double, val deltaN: Double,
    /** Positive = move forward (in the chosen orientation). */
    val forward: Double,
    /** Positive = move right. */
    val right: Double,
    /** Current minus design height: positive = fill needed... see [cutFill]. */
    val deltaZ: Double,
    val withinTolerance: Boolean
) {
    /** "Cut" when the current surface is above design (dig down), "fill" when below (build up). */
    val cutFill: Double get() = deltaZ
}

object Stakeout {
    /**
     * Guidance from the current position to a design point. Distances in the job's unit (all inputs share one unit).
     * [headingDeg] is the direction of travel, used only for [StakeoutOrientation.TRAVEL_DIRECTION].
     */
    fun toPoint(
        curE: Double, curN: Double, curZ: Double,
        tgtE: Double, tgtN: Double, tgtZ: Double,
        orientation: StakeoutOrientation, headingDeg: Double?, tolerance: Double
    ): StakeoutGuidance {
        val dE = tgtE - curE; val dN = tgtN - curN
        val dist = hypot(dE, dN)
        val az = Angles.normalize360(Math.toDegrees(atan2(dE, dN)))
        val ref = when (orientation) {
            StakeoutOrientation.NORTH -> 0.0
            StakeoutOrientation.TOWARDS_POINT -> az
            StakeoutOrientation.TRAVEL_DIRECTION -> headingDeg ?: az
        }
        val r = Math.toRadians(ref)
        // rotate the delta vector into the frame whose "forward" axis points along ref
        val forward = dN * cos(r) + dE * sin(r)
        val right = dE * cos(r) - dN * sin(r)
        return StakeoutGuidance(az, dist, dE, dN, forward, right, curZ - tgtZ, dist <= tolerance)
    }
}

/** Position relative to a design line: station along it, offset left/right, and the design elevation if given. */
data class LineStakeoutResult(val station: Double, val offset: Double, val designZ: Double?, val cutFill: Double?, val distanceFromLine: Double, val beyondEnds: Boolean)

class DesignLine(
    val name: String,
    val e1: Double, val n1: Double, val z1: Double?,
    val e2: Double, val n2: Double, val z2: Double?,
    val startStation: Double = 0.0
) {
    val length get() = hypot(e2 - e1, n2 - n1)
    val azimuthDeg get() = Angles.normalize360(Math.toDegrees(atan2(e2 - e1, n2 - n1)))

    /** Offset is positive to the right of the line when looking from start to end. */
    fun locate(e: Double, n: Double, z: Double?): LineStakeoutResult {
        val len = length
        require(len > 1e-9) { "Line has zero length" }
        val ux = (e2 - e1) / len; val uy = (n2 - n1) / len
        val dx = e - e1; val dy = n - n1
        val along = dx * ux + dy * uy
        val off = dx * uy - dy * ux // right-hand perpendicular of (ux,uy) is (uy,-ux)
        val dz = if (z1 != null && z2 != null) z1 + (z2 - z1) * (along / len).coerceIn(0.0, 1.0) else null
        return LineStakeoutResult(startStation + along, off, dz, if (dz != null && z != null) z - dz else null, abs(off), along < 0 || along > len)
    }

    /** Coordinates of a point at [station] and [offset] (right positive). */
    fun pointAt(station: Double, offset: Double): Pair<Double, Double> {
        val len = length
        val ux = (e2 - e1) / len; val uy = (n2 - n1) / len
        val s = station - startStation
        return (e1 + s * ux + offset * uy) to (n1 + s * uy - offset * ux)
    }
}
