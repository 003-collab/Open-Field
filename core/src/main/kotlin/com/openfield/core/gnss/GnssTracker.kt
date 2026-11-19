package com.openfield.core.gnss

import kotlin.math.hypot

/** One merged position epoch, independent of which NMEA sentences produced it. Heights in metres. */
data class GnssSnapshot(
    val utcSeconds: Double?,
    val receivedAtMillis: Long,
    val latDeg: Double, val lonDeg: Double,
    val altMsl: Double, val geoidSep: Double,
    val solution: SolutionType,
    val satellitesUsed: Int,
    val hdop: Double?, val pdop: Double?, val vdop: Double?,
    val sigmaLat: Double?, val sigmaLon: Double?, val sigmaAlt: Double?,
    val diffAgeSeconds: Double?, val referenceStation: String?,
    val speedMps: Double?, val courseDeg: Double?,
    val satellites: List<SatelliteInfo> = emptyList()
) {
    /** Height above the WGS-84 ellipsoid = orthometric altitude + geoid separation as reported by the receiver. */
    val ellipsoidalHeight get() = altMsl + geoidSep
    /** Horizontal 1-sigma precision (m) from GST when available. */
    val horizontalPrecision: Double? get() = if (sigmaLat != null && sigmaLon != null) hypot(sigmaLat, sigmaLon) else null
    val verticalPrecision: Double? get() = sigmaAlt
    val hasPosition get() = solution != SolutionType.NONE
}

/**
 * Merges NMEA sentences into [GnssSnapshot]s. A snapshot is produced whenever a position sentence (GGA or GNS)
 * arrives, carrying the most recent GST/GSA/RMC/VTG/GSV data. Precision (GST) is only attached if its timestamp is
 * within [maxAuxAgeSeconds] of the position, so stale values never masquerade as current precision.
 *
 * [qualityMap] lets a device profile override GGA quality codes for non-standard receivers.
 */
class GnssTracker(
    private val qualityMap: Map<Int, SolutionType> = emptyMap(),
    private val maxAuxAgeSeconds: Double = 2.5,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var gsa: Gsa? = null
    private var gst: Gst? = null
    private var rmc: Rmc? = null
    private var vtg: Vtg? = null
    private val satsByConstellation = LinkedHashMap<Constellation, MutableList<SatelliteInfo>>()
    private var gsvBuilding = HashMap<Constellation, MutableList<SatelliteInfo>>()

    var lastSnapshot: GnssSnapshot? = null
        private set

    fun reset() { gsa = null; gst = null; rmc = null; vtg = null; satsByConstellation.clear(); gsvBuilding.clear(); lastSnapshot = null }

    fun update(s: NmeaSentence): GnssSnapshot? {
        when (s) {
            is Gsa -> gsa = s
            is Gst -> gst = s
            is Rmc -> rmc = s
            is Vtg -> vtg = s
            is Gsv -> handleGsv(s)
            is Gga -> return emit(s.utcSeconds, s.latDeg, s.lonDeg, solutionFromGga(s.quality), s.satellites, s.hdop, s.altMsl, s.geoidSep, s.diffAge, s.diffStation)
            is Gns -> return emit(s.utcSeconds, s.latDeg, s.lonDeg, solutionFromGns(s.modes), s.satellites, s.hdop, s.altMsl, s.geoidSep, s.diffAge, s.diffStation)
        }
        return null
    }

    private fun handleGsv(g: Gsv) {
        val c = Constellation.fromTalker(g.talker)
        if (g.messageNumber == 1) gsvBuilding[c] = ArrayList()
        gsvBuilding.getOrPut(c) { ArrayList() }.addAll(g.sats)
        if (g.messageNumber == g.totalMessages) {
            satsByConstellation[c] = gsvBuilding.remove(c) ?: ArrayList()
        }
    }

    fun solutionFromGga(q: Int): SolutionType = qualityMap[q] ?: when (q) {
        0 -> SolutionType.NONE; 1 -> SolutionType.AUTONOMOUS; 2 -> SolutionType.DGPS; 3 -> SolutionType.PPS
        4 -> SolutionType.RTK_FIXED; 5 -> SolutionType.RTK_FLOAT; 6 -> SolutionType.ESTIMATED
        7 -> SolutionType.MANUAL; 8 -> SolutionType.SIMULATION; 9 -> SolutionType.DGPS
        else -> SolutionType.NONE
    }

    private fun solutionFromGns(modes: String): SolutionType = when {
        modes.contains('R') -> SolutionType.RTK_FIXED
        modes.contains('F') -> SolutionType.RTK_FLOAT
        modes.contains('P') -> SolutionType.PPS
        modes.contains('D') -> SolutionType.DGPS
        modes.contains('A') -> SolutionType.AUTONOMOUS
        modes.contains('E') -> SolutionType.ESTIMATED
        modes.contains('M') -> SolutionType.MANUAL
        modes.contains('S') -> SolutionType.SIMULATION
        else -> SolutionType.NONE
    }

    private fun fresh(t: Double?, ref: Double?): Boolean =
        t != null && ref != null && kotlin.math.abs(t - ref) <= maxAuxAgeSeconds

    private fun emit(
        utc: Double?, lat: Double?, lon: Double?, sol: SolutionType, sats: Int, hdop: Double?, alt: Double?,
        sep: Double?, diffAge: Double?, station: String?
    ): GnssSnapshot? {
        if (lat == null || lon == null) {
            // receiver without a fix still reports status; emit a NONE snapshot at 0,0 so the UI can show it
            val s = GnssSnapshot(utc, clock(), 0.0, 0.0, 0.0, 0.0, SolutionType.NONE, sats, hdop, gsa?.pdop, gsa?.vdop,
                null, null, null, null, null, null, null, allSatellites())
            lastSnapshot = s
            return s
        }
        val g = gst?.takeIf { fresh(it.utcSeconds, utc) }
        val m = rmc
        val speed = m?.speedKnots?.times(0.514444) ?: vtg?.speedKmh?.div(3.6)
        val course = m?.courseDeg ?: vtg?.courseDeg
        val s = GnssSnapshot(
            utc, clock(), lat, lon, alt ?: 0.0, sep ?: 0.0, sol, sats,
            hdop ?: gsa?.hdop, gsa?.pdop, gsa?.vdop,
            g?.sigmaLat, g?.sigmaLon, g?.sigmaAlt,
            diffAge, station, speed, course, allSatellites()
        )
        lastSnapshot = s
        return s
    }

    private fun allSatellites(): List<SatelliteInfo> = satsByConstellation.values.flatten()
}
