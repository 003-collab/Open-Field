package com.openfield.core.ntrip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/** Saved caster connection ("Real-time connection" in the app's settings). */
data class NtripProfile(
    val name: String,
    val host: String,
    val port: Int = 2101,
    val mountpoint: String = "",
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    /** Send position back to the caster as NMEA GGA every [ggaIntervalSeconds] (required by VRS/network RTK). 0 = never. */
    val ggaIntervalSeconds: Int = 10,
    val ntripVersion: Int = 2
)

data class Mountpoint(
    val name: String, val identifier: String, val format: String, val formatDetails: String, val carrier: Int,
    val navSystem: String, val network: String, val country: String, val latitude: Double?, val longitude: Double?,
    val nmeaRequired: Boolean, val networkSolution: Boolean, val generator: String, val authentication: String,
    val fee: Boolean, val bitrate: Int?
)

enum class NtripState { IDLE, CONNECTING, CONNECTED, ERROR, STOPPED }

interface NtripListener {
    fun onState(state: NtripState, message: String?) {}
    /** Called on the NTRIP thread with each block of RTCM bytes - route it to the receiver. */
    fun onData(buf: ByteArray, len: Int)
    fun onStats(bytesReceived: Long) {}
}

class NtripException(message: String, val httpStatus: Int? = null) : IOException(message)

object NtripSourcetable {
    fun parse(text: String): List<Mountpoint> = text.lineSequence().map { it.trim() }.filter { it.startsWith("STR;") }.mapNotNull { line ->
        val f = line.split(';')
        if (f.size < 12) return@mapNotNull null
        fun s(i: Int) = f.getOrElse(i) { "" }
        Mountpoint(s(1), s(2), s(3), s(4), s(5).toIntOrNull() ?: 0, s(6), s(7), s(8), s(9).toDoubleOrNull(), s(10).toDoubleOrNull(),
            s(11) == "1", s(12) == "1", s(13), s(14), s(15) == "Y", s(16).toIntOrNull())
    }.toList()
}

/**
 * NTRIP 1.0 / 2.0 client. Streams RTCM from the caster to [NtripListener.onData] and optionally uplinks GGA positions.
 * Blocking calls ([fetchSourcetable]) must not run on the Android main thread.
 */
class NtripClient(private val profile: NtripProfile) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var ggaProvider: (() -> String?)? = null
    @Volatile var bytesReceived = 0L
        private set

    private fun openSocket(timeoutMs: Int): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(profile.host, profile.port), timeoutMs)
        raw.soTimeout = timeoutMs
        return if (profile.useTls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, profile.host, profile.port, true)
        } else raw
    }

    private fun authHeader(): String =
        if (profile.username.isEmpty()) "" else
            "Authorization: Basic " + Base64.getEncoder().encodeToString("${profile.username}:${profile.password}".toByteArray()) + "\r\n"

    private fun request(path: String): String = buildString {
        append("GET /$path HTTP/1.1\r\n")
        append("Host: ${profile.host}${if (profile.port != 80 && profile.port != 443) ":${profile.port}" else ""}\r\n")
        if (profile.ntripVersion >= 2) append("Ntrip-Version: Ntrip/2.0\r\n")
        append("User-Agent: NTRIP OpenField/1.0\r\n")
        append(authHeader())
        append("Connection: close\r\n\r\n")
    }

    /** Download the sourcetable (list of mountpoints). */
    fun fetchSourcetable(timeoutMs: Int = 10000): List<Mountpoint> {
        val s = openSocket(timeoutMs)
        try {
            s.getOutputStream().apply { write(request("").toByteArray()); flush() }
            val input = s.getInputStream()
            val head = readHeaders(input)
            val status = head.first
            if (status.contains("401")) throw NtripException("Caster rejected the credentials (401)", 401)
            if (!status.contains("200")) throw NtripException("Caster replied: $status")
            val body = ByteArrayOutputStream()
            val chunked = head.second.entries.any { it.key.equals("transfer-encoding", true) && it.value.contains("chunked", true) }
            val src: InputStream = if (chunked) ChunkedInputStream(input) else input
            val buf = ByteArray(4096)
            while (true) {
                val n = try { src.read(buf) } catch (e: java.net.SocketTimeoutException) { -1 }
                if (n < 0) break
                body.write(buf, 0, n)
                if (body.toString(Charsets.ISO_8859_1).contains("ENDSOURCETABLE")) break
            }
            return NtripSourcetable.parse(body.toString(Charsets.ISO_8859_1))
        } finally { try { s.close() } catch (_: IOException) {} }
    }

    /** Provide a function returning the current GGA sentence (with checksum, no CRLF) to uplink to the caster. */
    fun setGgaProvider(provider: (() -> String?)?) { ggaProvider = provider }

    fun start(listener: NtripListener) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ run(listener) }, "ntrip-client").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: IOException) {}
        thread?.interrupt()
    }

    private fun run(l: NtripListener) {
        var backoff = 2000L
        while (running.get()) {
            try {
                l.onState(NtripState.CONNECTING, "${profile.host}:${profile.port}/${profile.mountpoint}")
                val s = openSocket(10000)
                socket = s
                val out = s.getOutputStream()
                out.write(request(profile.mountpoint).toByteArray()); out.flush()
                val input = s.getInputStream()
                val head = readHeaders(input)
                val status = head.first
                when {
                    status.contains("401") -> throw NtripException("Invalid NTRIP username or password", 401)
                    status.contains("403") -> throw NtripException("Access denied to mountpoint", 403)
                    status.startsWith("SOURCETABLE") || status.contains("404") ->
                        throw NtripException("Mountpoint '${profile.mountpoint}' not found on caster", 404)
                    !(status.contains("200")) -> throw NtripException("Caster replied: $status")
                }
                val chunked = head.second.entries.any { it.key.equals("transfer-encoding", true) && it.value.contains("chunked", true) }
                val src: InputStream = if (chunked) ChunkedInputStream(input) else input
                s.soTimeout = 20000
                l.onState(NtripState.CONNECTED, profile.mountpoint)
                backoff = 2000L
                val gga = Thread({ ggaLoop(out) }, "ntrip-gga").also { it.isDaemon = true; if (profile.ggaIntervalSeconds > 0) it.start() }
                val buf = ByteArray(4096)
                try {
                    while (running.get()) {
                        val n = src.read(buf)
                        if (n < 0) throw IOException("Caster closed the stream")
                        if (n > 0) { bytesReceived += n; l.onData(buf, n); l.onStats(bytesReceived) }
                    }
                } finally { gga.interrupt() }
            } catch (e: NtripException) {
                if (!running.get()) break
                l.onState(NtripState.ERROR, e.message)
                if (e.httpStatus == 401 || e.httpStatus == 403 || e.httpStatus == 404) { running.set(false); break } // retrying will not help
            } catch (e: Exception) {
                if (!running.get()) break
                l.onState(NtripState.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                try { socket?.close() } catch (_: IOException) {}
            }
            if (!running.get()) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = minOf(backoff * 2, 30000L)
        }
        l.onState(NtripState.STOPPED, null)
    }

    private fun ggaLoop(out: OutputStream) {
        try {
            while (running.get()) {
                val gga = ggaProvider?.invoke()
                if (gga != null) { out.write((gga + "\r\n").toByteArray()); out.flush() }
                Thread.sleep(profile.ggaIntervalSeconds * 1000L)
            }
        } catch (_: Exception) {}
    }

    /** Reads up to and including the blank line; returns (status line, headers). Reads byte-wise so no RTCM is consumed. */
    private fun readHeaders(input: InputStream): Pair<String, Map<String, String>> {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed during handshake")
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) break
            if (sb.length == 12 && sb.toString().startsWith("ICY 200 OK")) { /* NTRIP 1 has CRLF CRLF too */ }
            if (sb.length > 16384) throw IOException("Oversized NTRIP response header")
        }
        val lines = sb.toString().split("\r\n").filter { it.isNotEmpty() }
        val headers = LinkedHashMap<String, String>()
        for (ln in lines.drop(1)) { val i = ln.indexOf(':'); if (i > 0) headers[ln.substring(0, i).trim()] = ln.substring(i + 1).trim() }
        return (lines.firstOrNull() ?: "") to headers
    }

    /** Minimal HTTP/1.1 chunked decoder (used by some NTRIP v2 casters). */
    private class ChunkedInputStream(private val inp: InputStream) : InputStream() {
        private var remaining = 0
        private var eof = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (remaining == 0) {
                val line = StringBuilder()
                while (true) {
                    val c = inp.read()
                    if (c < 0) { eof = true; return -1 }
                    if (c == '\n'.code) break
                    if (c != '\r'.code) line.append(c.toChar())
                }
                if (line.isEmpty()) return read(b, off, len) // blank line between chunks
                remaining = line.toString().substringBefore(';').trim().toInt(16)
                if (remaining == 0) { eof = true; return -1 }
            }
            val n = inp.read(b, off, minOf(len, remaining))
            if (n < 0) { eof = true; return -1 }
            remaining -= n
            return n
        }
    }

    companion object {
        /** Build a GGA sentence for uplink from a snapshot. */
        fun ggaFor(latDeg: Double, lonDeg: Double, altMsl: Double, geoidSep: Double, utcSeconds: Double, satellites: Int, quality: Int, hdop: Double): String {
            val (la, ns) = com.openfield.core.gnss.NmeaParser.formatLat(latDeg)
            val (lo, ew) = com.openfield.core.gnss.NmeaParser.formatLon(lonDeg)
            val t = com.openfield.core.gnss.NmeaParser.formatTime(utcSeconds)
            return com.openfield.core.gnss.NmeaParser.build("GPGGA,$t,$la,$ns,$lo,$ew,$quality,%02d,%.1f,%.3f,M,%.3f,M,,".format(satellites, hdop, altMsl, geoidSep))
        }
    }
}
