package com.openfield.core.device

import com.openfield.core.gnss.*
import com.openfield.core.json.*
import kotlin.math.pow

/**
 * Abstraction over a JavaScript engine so the core stays dependency-free. The Android app supplies a Rhino
 * implementation. Values crossing the boundary are plain: numbers, strings, booleans, List and Map.
 */
interface ScriptRuntime {
    fun load(source: String)
    fun hasFunction(name: String): Boolean
    /** Calls a global function. Return values must be converted to Kotlin List/Map/String/Number/Boolean/null. */
    fun call(function: String, vararg args: Any?): Any?
    /** Exposes a host object (a map of function names to Kotlin lambdas is converted by the implementation). */
    fun expose(name: String, functions: Map<String, (List<Any?>) -> Any?>)
    fun close()
}

/**
 * Script driver contract (JavaScript):
 *
 *     function onOpen()            { io.write([0xB5, 0x62, ...]); io.log("init sent"); }   // optional
 *     function onData(bytes)       { ... return [ events ]; }                                // bytes: array of 0-255 ints
 *
 * Each returned event is one of:
 *     { type: "nmea", sentence: "$GNGGA,...*hh" }           // fed to the standard NMEA tracker
 *     { type: "fix", lat, lon, altMsl, geoidSep, solution: "RTK_FIXED"|"RTK_FLOAT"|..., sats, hdop, pdop, vdop,
 *       sigmaLat, sigmaLon, sigmaAlt, diffAge, station }   // direct position, any field optional except lat/lon
 *     { type: "baseline", baseId: "1234", base: {x,y,z}, d: {x,y,z}, sigma: {x,y,z} }   // RTK vector, ECEF metres; or ned: {n,e,d},
 *       sigmaNed: {n,e,d} for a vector in the local frame at the base; or cov: [xx,xy,xz,yy,yz,zz]. Attached to the next fixes for 2.5 s.
 *     { type: "line", text: "..." }                          // shown in the receiver console
 *
 * This lets users support binary protocols (UBX, Trimble GSOF, Septentrio SBF, ...) by decoding in JavaScript.
 */
class ScriptReceiverDriver(
    override val id: String,
    private val runtime: ScriptRuntime,
    private val source: String,
    private val requireChecksum: Boolean = true,
    qualityMap: Map<Int, SolutionType> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis
) : ReceiverDriver {
    private val tracker = GnssTracker(qualityMap, clock = clock)
    private var loaded = false

    override fun onOpen(io: DriverIO) {
        if (!loaded) {
            runtime.load(source)
            loaded = true
        }
        tracker.reset()
        runtime.expose("io", mapOf(
            "write" to { a: List<Any?> -> io.write(bytesFrom(a.firstOrNull())); null },
            "log" to { a: List<Any?> -> io.log(a.firstOrNull()?.toString() ?: ""); null }
        ))
        if (runtime.hasFunction("onOpen")) runtime.call("onOpen")
    }

    override fun onData(buf: ByteArray, len: Int, sink: DriverSink) {
        val arr = ArrayList<Int>(len)
        for (i in 0 until len) arr.add(buf[i].toInt() and 0xFF)
        val result = runtime.call("onData", arr) ?: return
        for (ev in result.asList()) {
            val m = ev.asObj()
            when (m["type"].asStr()) {
                "nmea" -> {
                    val text = m["sentence"].asStr()
                    sink.rawLine(text)
                    NmeaParser.parse(text, requireChecksum)?.let { s -> tracker.update(s)?.let(sink::snapshot) }
                }
                "line" -> sink.rawLine(m["text"].asStr())
                "fix" -> snapshotFrom(m)?.let(sink::snapshot)
                "baseline" -> baselineFrom(m)?.let(sink::baseline)
            }
        }
    }

    override fun onClose() { try { runtime.close() } catch (_: Exception) {}; loaded = false }

    private fun snapshotFrom(m: Map<String, Any?>): GnssSnapshot? {
        val lat = m["lat"].asDoubleOrNull() ?: return null
        val lon = m["lon"].asDoubleOrNull() ?: return null
        val sol = runCatching { SolutionType.valueOf(m["solution"].asStr("AUTONOMOUS")) }.getOrDefault(SolutionType.AUTONOMOUS)
        return GnssSnapshot(
            m["utc"].asDoubleOrNull(), clock(), lat, lon, m["altMsl"].asDouble(), m["geoidSep"].asDouble(), sol,
            m["sats"].asInt(), m["hdop"].asDoubleOrNull(), m["pdop"].asDoubleOrNull(), m["vdop"].asDoubleOrNull(),
            m["sigmaLat"].asDoubleOrNull(), m["sigmaLon"].asDoubleOrNull(), m["sigmaAlt"].asDoubleOrNull(),
            m["diffAge"].asDoubleOrNull(), m["station"].asStrOrNull(), m["speed"].asDoubleOrNull(), m["course"].asDoubleOrNull()
        )
    }

    /**
     * `{type:"baseline", baseId:"1234", base:{x,y,z}, d:{x,y,z}}` (ECEF metres) or `..., ned:{n,e,d}` for a vector in the local frame at the base.
     * Optional `sigma:{x,y,z}` (ECEF) or `sigmaNed:{n,e,d}` standard deviations, or `cov:[xx,xy,xz,yy,yz,zz]`. The base position is required.
     */
    private fun baselineFrom(m: Map<String, Any?>): BaselineVector? {
        val b = m["base"]?.asObj() ?: return null
        val base = BasePosition(m["baseId"].asStr("0"), b["x"].asDouble(), b["y"].asDouble(), b["z"].asDouble(), clock(), "driver")
        if (!base.isValid) return null
        val cov: List<Double>? = m["cov"]?.asList()?.map { it.asDouble() }?.takeIf { it.size == 6 }
        m["d"]?.asObj()?.let { d ->
            val sg = m["sigma"]?.asObj()
            val c = cov ?: sg?.let { listOf(it["x"].asDouble().pow(2), 0.0, 0.0, it["y"].asDouble().pow(2), 0.0, it["z"].asDouble().pow(2)) }
            return BaselineVector.fromEcef(base, d["x"].asDouble(), d["y"].asDouble(), d["z"].asDouble(), c, "driver")
        }
        m["ned"]?.asObj()?.let { v ->
            val sg = m["sigmaNed"]?.asObj()
            return BaselineVector.fromEnu(base, v["e"].asDouble(), v["n"].asDouble(), -v["d"].asDouble(), sg?.get("e").asDoubleOrNull(), sg?.get("n").asDoubleOrNull(), sg?.get("d").asDoubleOrNull(), "driver")
        }
        return null
    }

    private fun bytesFrom(v: Any?): ByteArray = when (v) {
        is List<*> -> ByteArray(v.size) { ((v[it] as? Number)?.toInt() ?: 0).toByte() }
        is String -> v.toByteArray(Charsets.ISO_8859_1)
        else -> ByteArray(0)
    }
}
