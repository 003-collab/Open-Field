package com.openfield.core.survey

import com.openfield.core.device.AntennaProfile
import com.openfield.core.device.HeightMeasurement
import com.openfield.core.geo.GridPoint
import com.openfield.core.geo.LatLonH
import com.openfield.core.gnss.GnssSnapshot
import com.openfield.core.gnss.SolutionType
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Current position in job coordinates, updated every epoch. */
data class LivePosition(
    val snapshot: GnssSnapshot,
    /** Antenna phase-centre/ARP position in job coordinates. */
    val antenna: GridPoint,
    /** Ground position below the antenna (antenna height removed). */
    val ground: GridPoint,
    /** Reasons the current epoch fails the survey style's quality gate (empty = good). */
    val gateFailures: List<String>
)

data class AveragedPosition(
    val ground: GridPoint, val latDeg: Double, val lonDeg: Double, val groundEllipsoidalHeight: Double,
    val epochs: Int, val rejected: Int,
    val sdE: Double, val sdN: Double, val sdZ: Double,
    val meanHdop: Double?, val meanPdop: Double?, val meanSigmaH: Double?, val meanSigmaV: Double?,
    val minSatellites: Int, val solution: SolutionType, val maxDiffAge: Double?
) {
    /** Horizontal repeatability (1 sigma) in the job unit. */
    val sdH get() = hypot(sdE, sdN)
}

/** Accumulates accepted epochs and computes mean/standard deviation (Welford) in job coordinates. */
class PositionAverager(
    private val gate: QualityGate,
    private val system: com.openfield.core.geo.CoordinateSystem,
    private val arpHeightMetres: Double,
    private val resetOnQualityLoss: Boolean
) {
    var count = 0; private set
    var rejected = 0; private set
    var lastFailures: List<String> = emptyList(); private set
    private val unit = system.unit
    private var mE = 0.0; private var mN = 0.0; private var mZ = 0.0
    private var m2E = 0.0; private var m2N = 0.0; private var m2Z = 0.0
    private var sLat = 0.0; private var sLon = 0.0; private var sH = 0.0
    private var sHdop = 0.0; private var nHdop = 0; private var sPdop = 0.0; private var nPdop = 0
    private var sSigH = 0.0; private var nSigH = 0; private var sSigV = 0.0; private var nSigV = 0
    private var minSats = Int.MAX_VALUE
    private var worst: SolutionType? = null
    private var maxAge: Double? = null

    fun reset() {
        count = 0; mE = 0.0; mN = 0.0; mZ = 0.0; m2E = 0.0; m2N = 0.0; m2Z = 0.0; sLat = 0.0; sLon = 0.0; sH = 0.0
        sHdop = 0.0; nHdop = 0; sPdop = 0.0; nPdop = 0; sSigH = 0.0; nSigH = 0; sSigV = 0.0; nSigV = 0; minSats = Int.MAX_VALUE; worst = null; maxAge = null
    }

    /** Returns true if the epoch was accepted into the average. */
    fun add(s: GnssSnapshot): Boolean {
        val failures = gate.check(s)
        lastFailures = failures
        if (failures.isNotEmpty()) {
            rejected++
            if (resetOnQualityLoss && count > 0) reset()
            return false
        }
        val g = system.toLocal(LatLonH(s.latDeg, s.lonDeg, s.ellipsoidalHeight))
        count++
        val k = count.toDouble()
        var d = g.e - mE; mE += d / k; m2E += d * (g.e - mE)
        d = g.n - mN; mN += d / k; m2N += d * (g.n - mN)
        d = g.z - mZ; mZ += d / k; m2Z += d * (g.z - mZ)
        sLat += s.latDeg; sLon += s.lonDeg; sH += s.ellipsoidalHeight
        s.hdop?.let { sHdop += it; nHdop++ }; s.pdop?.let { sPdop += it; nPdop++ }
        s.horizontalPrecision?.let { sSigH += it; nSigH++ }; s.verticalPrecision?.let { sSigV += it; nSigV++ }
        minSats = minOf(minSats, s.satellitesUsed)
        if (worst == null || s.solution.rank < worst!!.rank) worst = s.solution
        s.diffAgeSeconds?.let { maxAge = max(maxAge ?: 0.0, it) }
        return true
    }

    fun result(): AveragedPosition {
        check(count > 0) { "No accepted epochs" }
        val n = count.toDouble()
        fun sd(m2: Double) = if (count > 1) sqrt(m2 / (n - 1)) else 0.0
        val arp = unit.fromMeters(arpHeightMetres)
        return AveragedPosition(
            GridPoint(mE, mN, mZ - arp), sLat / n, sLon / n, sH / n - arpHeightMetres, count, rejected,
            sd(m2E), sd(m2N), sd(m2Z),
            if (nHdop > 0) sHdop / nHdop else null, if (nPdop > 0) sPdop / nPdop else null,
            if (nSigH > 0) sSigH / nSigH else null, if (nSigV > 0) sSigV / nSigV else null,
            minSats, worst ?: SolutionType.NONE, maxAge
        )
    }
}

data class MeasureSpec(
    val name: String? = null,
    val code: String = "",
    val method: MeasureMethod = MeasureMethod.TOPO,
    val epochs: Int? = null,
    val antennaHeightMeasured: Double,
    val heightMethod: HeightMeasurement,
    val note: String = ""
)

data class MeasureProgress(val spec: MeasureSpec, val accepted: Int, val required: Int, val rejected: Int, val lastFailures: List<String>)

interface SurveyListener {
    fun onLive(p: LivePosition) {}
    fun onProgress(p: MeasureProgress) {}
    fun onPointStored(point: SurveyPoint) {}
    fun onMessage(text: String) {}
}

/**
 * Orchestrates measurement: feed it receiver snapshots, start/cancel measurements, and it stores points in the job.
 * Single-threaded by design; call from one thread (the app marshals onto its main/viewmodel thread).
 */
class SurveyEngine(
    var job: Job,
    var style: SurveyStyle,
    var antenna: AntennaProfile,
    var deviceId: String = "",
    private val listener: SurveyListener
) {
    var live: LivePosition? = null; private set
    /** Antenna height used for the live ground position display (vertical to ARP, metres). */
    var liveArpHeight: Double = style.defaultAntennaHeight

    private var averager: PositionAverager? = null
    private var active: MeasureSpec? = null
    private var activeArp = 0.0
    private var continuous: MeasureSpec? = null
    private var lastContinuous: Pair<GridPoint, Long>? = null

    val isMeasuring get() = active != null
    val isRecordingContinuous get() = continuous != null

    fun onSnapshot(s: GnssSnapshot) {
        val cs = job.coordinateSystem
        if (s.hasPosition) {
            val ant = cs.toLocal(LatLonH(s.latDeg, s.lonDeg, s.ellipsoidalHeight))
            val arp = cs.unit.fromMeters(liveArpHeight)
            val lp = LivePosition(s, ant, GridPoint(ant.e, ant.n, ant.z - arp), style.gate.check(s))
            live = lp
            listener.onLive(lp)
        } else {
            live = null
        }
        averager?.let { handleMeasuring(it, s) }
        continuous?.let { handleContinuous(it, s) }
    }

    fun startMeasure(spec: MeasureSpec) {
        check(active == null) { "A measurement is already running" }
        val arp = antenna.arpHeight(spec.antennaHeightMeasured, spec.heightMethod)
        val epochs = spec.epochs ?: if (spec.method == MeasureMethod.OBSERVED_CONTROL) style.controlEpochs else style.topoEpochs
        val resolved = spec.copy(epochs = max(1, epochs))
        activeArp = arp
        active = resolved
        averager = PositionAverager(style.gate, job.coordinateSystem, arp, style.resetOnQualityLoss)
        listener.onProgress(MeasureProgress(resolved, 0, resolved.epochs!!, 0, emptyList()))
    }

    fun cancelMeasure() { active = null; averager = null }

    /** Store now with whatever epochs were accepted (e.g. user taps "Store" during an observed control point). */
    fun finishMeasureEarly(): SurveyPoint? {
        val a = averager ?: return null
        if (a.count == 0) { listener.onMessage("No acceptable epochs yet"); return null }
        return completeMeasure(a)
    }

    private fun handleMeasuring(a: PositionAverager, s: GnssSnapshot) {
        val spec = active ?: return
        a.add(s)
        val required = spec.epochs ?: 1
        listener.onProgress(MeasureProgress(spec, a.count, required, a.rejected, a.lastFailures))
        if (a.count >= required) completeMeasure(a)
    }

    private fun completeMeasure(a: PositionAverager): SurveyPoint? {
        val spec = active ?: return null
        val r = a.result()
        val name = spec.name ?: job.freeName()
        val unit = job.coordinateSystem.unit
        val point = SurveyPoint(
            name = name, code = spec.code,
            kind = if (spec.method == MeasureMethod.OBSERVED_CONTROL) PointKind.CONTROL else PointKind.MEASURED,
            method = spec.method, e = r.ground.e, n = r.ground.n, z = r.ground.z,
            latDeg = r.latDeg, lonDeg = r.lonDeg, ellipsoidalHeight = r.groundEllipsoidalHeight,
            solution = r.solution, hdop = r.meanHdop, pdop = r.meanPdop, sigmaH = r.meanSigmaH, sigmaV = r.meanSigmaV,
            satellites = r.minSatellites, epochs = r.epochs, antennaHeight = activeArp,
            sdH = unit.toMeters(r.sdH), sdV = unit.toMeters(r.sdZ), timestampMillis = System.currentTimeMillis(),
            note = spec.note, deviceId = deviceId
        )
        active = null; averager = null
        try {
            job.add(point)
            listener.onPointStored(point)
            return point
        } catch (e: DuplicatePointException) {
            listener.onMessage(e.message ?: "Duplicate point name")
            return null
        }
    }

    // ---------------- continuous topo ----------------

    fun startContinuous(code: String, antennaHeightMeasured: Double, method: HeightMeasurement, note: String = "") {
        val spec = MeasureSpec(null, code, MeasureMethod.CONTINUOUS, 1, antennaHeightMeasured, method, note)
        antenna.arpHeight(antennaHeightMeasured, method) // validate
        continuous = spec
        lastContinuous = null
    }

    fun stopContinuous() { continuous = null; lastContinuous = null }

    private fun handleContinuous(spec: MeasureSpec, s: GnssSnapshot) {
        if (style.gate.check(s).isNotEmpty()) return
        val cs = job.coordinateSystem
        val arp = antenna.arpHeight(spec.antennaHeightMeasured, spec.heightMethod)
        val g = cs.toLocal(LatLonH(s.latDeg, s.lonDeg, s.ellipsoidalHeight))
        val ground = GridPoint(g.e, g.n, g.z - cs.unit.fromMeters(arp))
        val last = lastContinuous
        val due = when {
            last == null -> true
            style.continuousMode == ContinuousMode.TIME -> (s.receivedAtMillis - last.second) >= (style.continuousInterval * 1000).toLong()
            else -> hypot(ground.e - last.first.e, ground.n - last.first.n) >= style.continuousInterval
        }
        if (!due) return
        val p = SurveyPoint(
            name = job.freeName(), code = spec.code, kind = PointKind.MEASURED, method = MeasureMethod.CONTINUOUS,
            e = ground.e, n = ground.n, z = ground.z, latDeg = s.latDeg, lonDeg = s.lonDeg, ellipsoidalHeight = s.ellipsoidalHeight - arp,
            solution = s.solution, hdop = s.hdop, pdop = s.pdop, sigmaH = s.horizontalPrecision, sigmaV = s.verticalPrecision,
            satellites = s.satellitesUsed, epochs = 1, antennaHeight = arp, timestampMillis = System.currentTimeMillis(), note = spec.note, deviceId = deviceId
        )
        job.add(p)
        lastContinuous = ground to s.receivedAtMillis
        listener.onPointStored(p)
    }

    // ---------------- keyed-in points ----------------

    fun keyIn(name: String?, code: String, e: Double, n: Double, z: Double, kind: PointKind = PointKind.KEYED_IN, note: String = ""): SurveyPoint {
        val cs = job.coordinateSystem
        val ll = if (cs.projection != null) cs.fromLocal(GridPoint(e, n, z)) else null
        val p = SurveyPoint(
            name = name ?: job.freeName(), code = code, kind = kind, method = MeasureMethod.KEYED_IN, e = e, n = n, z = z,
            latDeg = ll?.latDeg, lonDeg = ll?.lonDeg, ellipsoidalHeight = ll?.ellipsoidalHeight, timestampMillis = System.currentTimeMillis(), note = note
        )
        job.add(p)
        listener.onPointStored(p)
        return p
    }
}
