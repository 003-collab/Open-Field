package com.openfield.core.gnss

import java.io.InputStream
import java.util.Random
import kotlin.math.cos
import kotlin.math.PI

/**
 * Generates realistic NMEA output (GGA, GST, GSA, RMC, GSV) with Gaussian noise around a position. Drives the app's
 * demo mode and the unit tests, so the whole workflow can be exercised without a receiver.
 */
class NmeaSimulator(
    var latDeg: Double, var lonDeg: Double, var altMsl: Double,
    var geoidSep: Double = 0.0,
    var quality: Int = 4,
    var satellites: Int = 18,
    var hdop: Double = 0.7,
    var sigmaHorizontal: Double = 0.012,
    var sigmaVertical: Double = 0.02,
    var utcSeconds: Double = 12 * 3600.0,
    seed: Long = 42
) {
    private val rnd = Random(seed)

    /** Velocity in m/s (north, east); position integrates on each [epoch]. */
    var velNorth = 0.0
    var velEast = 0.0

    private fun metresPerDegLat() = 111_320.0

    /** Produces one epoch of sentences (CRLF terminated) and advances the clock by [dt] seconds. */
    fun epoch(dt: Double = 1.0): String {
        latDeg += velNorth * dt / metresPerDegLat()
        lonDeg += velEast * dt / (metresPerDegLat() * cos(latDeg * PI / 180))
        utcSeconds += dt
        val nN = rnd.nextGaussian() * sigmaHorizontal
        val nE = rnd.nextGaussian() * sigmaHorizontal
        val nU = rnd.nextGaussian() * sigmaVertical
        val lat = latDeg + nN / metresPerDegLat()
        val lon = lonDeg + nE / (metresPerDegLat() * cos(latDeg * PI / 180))
        val alt = altMsl + nU
        val (la, ns) = NmeaParser.formatLat(lat)
        val (lo, ew) = NmeaParser.formatLon(lon)
        val t = NmeaParser.formatTime(utcSeconds)
        val sb = StringBuilder()
        val ageField = if (quality in 4..5) "1.0,0000" else ","
        sb.appendLine(NmeaParser.build("GNGGA,$t,$la,$ns,$lo,$ew,$quality,%02d,%.1f,%.4f,M,%.4f,M,$ageField".format(satellites, hdop, alt, geoidSep)))
        sb.appendLine(NmeaParser.build("GNGST,$t,1.0,%.3f,%.3f,0.0,%.3f,%.3f,%.3f".format(sigmaHorizontal, sigmaHorizontal, sigmaHorizontal, sigmaHorizontal, sigmaVertical)))
        sb.appendLine(NmeaParser.build("GNGSA,A,3,01,03,06,09,11,14,17,19,22,28,,,%.1f,%.1f,%.1f,1".format(hdop * 1.4, hdop, hdop * 1.2)))
        val speedKn = Math.hypot(velNorth, velEast) / 0.514444
        val course = if (speedKn > 0.01) (Math.toDegrees(Math.atan2(velEast, velNorth)) + 360) % 360 else 0.0
        sb.appendLine(NmeaParser.build("GNRMC,$t,A,$la,$ns,$lo,$ew,%.2f,%.1f,010125,,,A".format(speedKn, course)))
        sb.appendLine(NmeaParser.build("GPGSV,1,1,04,01,45,120,42,03,60,200,45,06,30,300,40,09,15,050,38"))
        return sb.toString().replace("\n", "\r\n").replace("\r\r\n", "\r\n")
    }

    /** Streams epochs at [rateHz] from a background thread through an InputStream (use with [StreamTransport]). */
    fun asStream(rateHz: Double = 1.0): InputStream = SimStream(this, rateHz)

    private class SimStream(val sim: NmeaSimulator, rateHz: Double) : InputStream() {
        private val periodMs = (1000.0 / rateHz).toLong()
        private var buf = ByteArray(0)
        private var pos = 0
        @Volatile private var closed = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (pos >= buf.size) {
                try { Thread.sleep(periodMs) } catch (_: InterruptedException) { return -1 }
                if (closed) return -1
                buf = sim.epoch(1.0 / (1000.0 / periodMs)).toByteArray(); pos = 0
            }
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n); pos += n
            return n
        }
        override fun close() { closed = true }
    }
}
