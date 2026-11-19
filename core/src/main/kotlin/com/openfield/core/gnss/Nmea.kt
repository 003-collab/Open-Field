package com.openfield.core.gnss

import kotlin.math.*

enum class SolutionType(val label: String, val rank: Int) {
    NONE("No fix", 0),
    AUTONOMOUS("Autonomous", 1),
    DGPS("Code differential", 2),
    PPS("PPS", 2),
    RTK_FLOAT("RTK Float", 3),
    RTK_FIXED("RTK Fixed", 4),
    ESTIMATED("Dead reckoning", 0),
    MANUAL("Manual input", 0),
    SIMULATION("Simulated", 0);

    val isRtk get() = this == RTK_FIXED || this == RTK_FLOAT
}

enum class Constellation(val label: String, val talker: String) {
    GPS("GPS", "GP"), GLONASS("GLONASS", "GL"), GALILEO("Galileo", "GA"), BEIDOU("BeiDou", "GB"),
    QZSS("QZSS", "GQ"), NAVIC("NavIC", "GI"), MIXED("Mixed", "GN"), UNKNOWN("Unknown", "");

    companion object {
        fun fromTalker(t: String) = when (t) {
            "GP" -> GPS; "GL" -> GLONASS; "GA" -> GALILEO; "GB", "BD" -> BEIDOU; "GQ" -> QZSS; "GI" -> NAVIC; "GN" -> MIXED
            else -> UNKNOWN
        }
    }
}

data class SatelliteInfo(val constellation: Constellation, val prn: Int, val elevationDeg: Int?, val azimuthDeg: Int?, val snr: Int?)

sealed interface NmeaSentence {
    val talker: String
}

data class Gga(
    override val talker: String, val utcSeconds: Double?, val latDeg: Double?, val lonDeg: Double?, val quality: Int,
    val satellites: Int, val hdop: Double?, val altMsl: Double?, val geoidSep: Double?, val diffAge: Double?, val diffStation: String?
) : NmeaSentence

data class Gns(
    override val talker: String, val utcSeconds: Double?, val latDeg: Double?, val lonDeg: Double?, val modes: String,
    val satellites: Int, val hdop: Double?, val altMsl: Double?, val geoidSep: Double?, val diffAge: Double?, val diffStation: String?
) : NmeaSentence

data class Rmc(
    override val talker: String, val utcSeconds: Double?, val valid: Boolean, val latDeg: Double?, val lonDeg: Double?,
    val speedKnots: Double?, val courseDeg: Double?, val date: String?
) : NmeaSentence

data class Vtg(override val talker: String, val courseDeg: Double?, val speedKmh: Double?) : NmeaSentence

data class Gsa(override val talker: String, val fixType: Int, val prns: List<Int>, val pdop: Double?, val hdop: Double?, val vdop: Double?) : NmeaSentence

data class Gst(
    override val talker: String, val utcSeconds: Double?, val rmsRange: Double?, val sigmaMajor: Double?, val sigmaMinor: Double?,
    val orientationDeg: Double?, val sigmaLat: Double?, val sigmaLon: Double?, val sigmaAlt: Double?
) : NmeaSentence

data class Gsv(override val talker: String, val totalMessages: Int, val messageNumber: Int, val inView: Int, val sats: List<SatelliteInfo>) : NmeaSentence

object NmeaParser {

    fun checksum(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return "%02X".format(x)
    }

    /** Builds a complete sentence "$body*CS" from a body without '$' or checksum. */
    fun build(body: String) = "$$body*${checksum(body)}"

    /**
     * Parses one NMEA 0183 line. Returns null for unknown sentence types, framing errors or bad checksums.
     * Set [requireChecksum] to false for receivers or simulators that omit it.
     */
    fun parse(rawLine: String, requireChecksum: Boolean = true): NmeaSentence? {
        val line = rawLine.trim()
        val start = line.indexOf('$')
        if (start < 0) return null
        val star = line.lastIndexOf('*')
        val body: String
        if (star > start) {
            body = line.substring(start + 1, star)
            val given = line.substring(star + 1).trim().take(2).uppercase()
            if (given != checksum(body)) return null
        } else {
            if (requireChecksum) return null
            body = line.substring(start + 1)
        }
        val f = body.split(',')
        val id = f[0]
        if (id.length < 5) return null
        val talker = id.substring(0, 2)
        return try {
            when (id.substring(2)) {
                "GGA" -> Gga(talker, time(f.at(1)), lat(f.at(2), f.at(3)), lon(f.at(4), f.at(5)), f.at(6).toIntOrNull() ?: 0,
                    f.at(7).toIntOrNull() ?: 0, f.at(8).toDoubleOrNull(), f.at(9).toDoubleOrNull(), f.at(11).toDoubleOrNull(),
                    f.at(13).toDoubleOrNull(), f.at(14).ifEmpty { null })
                "GNS" -> Gns(talker, time(f.at(1)), lat(f.at(2), f.at(3)), lon(f.at(4), f.at(5)), f.at(6), f.at(7).toIntOrNull() ?: 0,
                    f.at(8).toDoubleOrNull(), f.at(9).toDoubleOrNull(), f.at(10).toDoubleOrNull(), f.at(11).toDoubleOrNull(), f.at(12).ifEmpty { null })
                "RMC" -> Rmc(talker, time(f.at(1)), f.at(2) == "A", lat(f.at(3), f.at(4)), lon(f.at(5), f.at(6)),
                    f.at(7).toDoubleOrNull(), f.at(8).toDoubleOrNull(), f.at(9).ifEmpty { null })
                "VTG" -> Vtg(talker, f.at(1).toDoubleOrNull(), f.at(7).toDoubleOrNull())
                "GSA" -> Gsa(talker, f.at(2).toIntOrNull() ?: 1, (3..14).mapNotNull { f.at(it).toIntOrNull() },
                    f.at(15).toDoubleOrNull(), f.at(16).toDoubleOrNull(), f.at(17).substringBefore('*').toDoubleOrNull())
                "GST" -> Gst(talker, time(f.at(1)), f.at(2).toDoubleOrNull(), f.at(3).toDoubleOrNull(), f.at(4).toDoubleOrNull(),
                    f.at(5).toDoubleOrNull(), f.at(6).toDoubleOrNull(), f.at(7).toDoubleOrNull(), f.at(8).toDoubleOrNull())
                "GSV" -> {
                    val c = Constellation.fromTalker(talker)
                    val sats = ArrayList<SatelliteInfo>()
                    var i = 4
                    while (i + 3 < f.size + 0 && f.at(i).isNotEmpty()) {
                        sats.add(SatelliteInfo(c, f.at(i).toIntOrNull() ?: 0, f.at(i + 1).toIntOrNull(), f.at(i + 2).toIntOrNull(), f.at(i + 3).toIntOrNull()))
                        i += 4
                    }
                    Gsv(talker, f.at(1).toIntOrNull() ?: 1, f.at(2).toIntOrNull() ?: 1, f.at(3).toIntOrNull() ?: 0, sats)
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun List<String>.at(i: Int) = if (i < size) this[i] else ""

    /** hhmmss.ss -> seconds since midnight UTC */
    private fun time(s: String): Double? {
        if (s.length < 6) return null
        val h = s.substring(0, 2).toIntOrNull() ?: return null
        val m = s.substring(2, 4).toIntOrNull() ?: return null
        val sec = s.substring(4).toDoubleOrNull() ?: return null
        return h * 3600.0 + m * 60.0 + sec
    }

    /** ddmm.mmmm + N/S -> signed degrees */
    private fun lat(v: String, hemi: String): Double? {
        if (v.length < 4) return null
        val d = v.substring(0, 2).toDoubleOrNull() ?: return null
        val m = v.substring(2).toDoubleOrNull() ?: return null
        val r = d + m / 60.0
        return if (hemi == "S") -r else r
    }

    /** dddmm.mmmm + E/W -> signed degrees */
    private fun lon(v: String, hemi: String): Double? {
        if (v.length < 5) return null
        val d = v.substring(0, 3).toDoubleOrNull() ?: return null
        val m = v.substring(3).toDoubleOrNull() ?: return null
        val r = d + m / 60.0
        return if (hemi == "W") -r else r
    }

    // ---- formatting helpers used by the simulator and tests ----
    fun formatLat(deg: Double): Pair<String, String> {
        val a = abs(deg); val d = floor(a).toInt(); val m = (a - d) * 60
        return "%02d%010.7f".format(d, m) to if (deg >= 0) "N" else "S"
    }

    fun formatLon(deg: Double): Pair<String, String> {
        val a = abs(deg); val d = floor(a).toInt(); val m = (a - d) * 60
        return "%03d%010.7f".format(d, m) to if (deg >= 0) "E" else "W"
    }

    fun formatTime(utcSeconds: Double): String {
        val s = ((utcSeconds % 86400) + 86400) % 86400
        val h = (s / 3600).toInt(); val m = ((s % 3600) / 60).toInt(); val sec = s % 60
        return "%02d%02d%05.2f".format(h, m, sec)
    }
}
