package com.openfield.core.survey

import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

import com.openfield.core.geo.CoordinateSystem
import com.openfield.core.gnss.BaselineVector
import com.openfield.core.gnss.SolutionType

enum class PointKind(val label: String) { MEASURED("Measured"), CONTROL("Control"), DESIGN("Design"), KEYED_IN("Keyed in") }
enum class MeasureMethod(val label: String) { TOPO("Topo"), OBSERVED_CONTROL("Observed control"), CONTINUOUS("Continuous"), KEYED_IN("Keyed in"), IMPORTED("Imported"), AVERAGED("Averaged") }

/** NORMAL observations can be the position of a point; CHECK observations are kept for comparison and never move the point. */
enum class Classification(val label: String) { NORMAL("Normal"), CHECK("Check") }

/** What to do when a point is measured with a name that already exists. */
enum class DuplicateAction(val label: String) {
    STORE_ANOTHER("Store another"), AVERAGE("Average"), STORE_AS_CHECK("Store as check"), OVERWRITE("Overwrite")
}

/** Position of [to] relative to [from], in the job's linear unit (north, east, height; horizontal distance). */
data class PositionDifference(val dn: Double, val de: Double, val dz: Double) {
    val horizontal: Double get() = hypot(dn, de)
    companion object { fun between(from: SurveyPoint, to: SurveyPoint) = PositionDifference(to.n - from.n, to.e - from.e, to.z - from.z) }
}

/**
 * A stored point. E/N/Z are in the job coordinate system's linear unit and refer to the GROUND mark
 * (antenna height already removed). Precision/uncertainty fields are in metres.
 */
data class SurveyPoint(
    val name: String,
    val code: String = "",
    val kind: PointKind = PointKind.MEASURED,
    val method: MeasureMethod = MeasureMethod.TOPO,
    val e: Double, val n: Double, val z: Double,
    val latDeg: Double? = null, val lonDeg: Double? = null, val ellipsoidalHeight: Double? = null,
    val solution: SolutionType? = null,
    val hdop: Double? = null, val pdop: Double? = null,
    val sigmaH: Double? = null, val sigmaV: Double? = null,
    val satellites: Int? = null,
    val epochs: Int = 0,
    val antennaHeight: Double? = null,
    val sdH: Double? = null, val sdV: Double? = null,
    val timestampMillis: Long = 0L,
    val note: String = "",
    val deviceId: String = "",
    /** RTK baseline vector (base to rover antenna) with its covariance, for network adjustment. Null for keyed-in, imported and non-RTK points. */
    val vector: BaselineVector? = null,
    val classification: Classification = Classification.NORMAL,
    /** True for observations replaced by an Overwrite. Kept in the job as an audit trail, never used as a position or exported. */
    val deleted: Boolean = false
)

object PointNamer {
    /** Increments the trailing number, preserving zero padding: "PT099"->"PT100", "A9"->"A10", "1001"->"1002", "ABC"->"ABC1". */
    fun next(name: String): String {
        if (name.isEmpty()) return "1"
        var i = name.length
        while (i > 0 && name[i - 1].isDigit()) i--
        val prefix = name.substring(0, i); val digits = name.substring(i)
        if (digits.isEmpty()) return name + "1"
        val inc = (digits.toLong() + 1).toString().padStart(digits.length, '0')
        return prefix + inc
    }
}

class DuplicatePointException(name: String) : Exception("A point named '$name' already exists")

class Job(
    var name: String,
    var coordinateSystem: CoordinateSystem,
    var description: String = "",
    val createdMillis: Long = System.currentTimeMillis(),
    var operator: String = ""
) {
    /** Every stored record per point name, oldest first: normal, check, averaged and overwritten (deleted) observations. */
    private val records = LinkedHashMap<String, MutableList<SurveyPoint>>()
    /** The record that gives each name its position: the latest normal observation that has not been overwritten. */
    private val positions = LinkedHashMap<String, SurveyPoint>()

    /** One point per name: its current position. Maps, stake out, calibration and the text exports use this. */
    val points: List<SurveyPoint> get() = positions.values.toList()
    /** Every observation that is still part of the job (overwritten ones excluded), grouped by name, oldest first. */
    val observations: List<SurveyPoint> get() = records.values.flatten().filter { !it.deleted }
    /** All records of one name including overwritten ones, oldest first. */
    fun recordsOf(name: String): List<SurveyPoint> = records[name]?.toList() ?: emptyList()
    /** Every record including overwritten ones, for saving the job file. */
    fun allRecords(): List<SurveyPoint> = records.values.flatten()
    val hasMultipleObservations: Boolean get() = records.values.any { it.size > 1 }

    val lines = ArrayList<DesignLine>()
    var nextPointName: String = "1"
    var styleId: String = ""
    var modifiedMillis: Long = createdMillis
        private set

    fun find(name: String): SurveyPoint? = positions[name]
    fun has(name: String) = positions.containsKey(name)
    private fun touch() { modifiedMillis = System.currentTimeMillis() }

    private fun refresh(name: String) {
        val list = records[name]
        if (list == null || list.isEmpty()) { records.remove(name); positions.remove(name); return }
        val pos = list.lastOrNull { !it.deleted && it.classification == Classification.NORMAL }
        if (pos == null) positions.remove(name) else positions[name] = pos
    }

    private fun advanceName(name: String) { if (name == nextPointName) nextPointName = PointNamer.next(name) }

    /** Adds a new point; throws [DuplicatePointException] if the name is taken unless [overwrite], which behaves like [DuplicateAction.OVERWRITE]. */
    fun add(p: SurveyPoint, overwrite: Boolean = false) {
        if (has(p.name) && !overwrite) throw DuplicatePointException(p.name)
        store(p, if (overwrite) DuplicateAction.OVERWRITE else DuplicateAction.STORE_ANOTHER)
    }

    /**
     * Stores an observation. For a new name it simply becomes the point. If the name exists:
     * - STORE_ANOTHER: kept as another normal observation, and becomes the position;
     * - STORE_AS_CHECK: kept as a check observation, the position does not change;
     * - AVERAGE: kept, and an averaged record of all GNSS normal observations of the name becomes the position;
     * - OVERWRITE: earlier observations are marked deleted and this one becomes the point.
     * Returns the record that is now the position (or the check observation).
     */
    fun store(p: SurveyPoint, action: DuplicateAction = DuplicateAction.STORE_ANOTHER): SurveyPoint {
        val list = records.getOrPut(p.name) { ArrayList() }
        val exists = list.any { !it.deleted && it.classification == Classification.NORMAL }
        var result = p.copy(classification = Classification.NORMAL, deleted = false)
        try {
            if (!exists) {
                require(action != DuplicateAction.STORE_AS_CHECK) { "There is no point named '${p.name}' to check" }
                list.add(result)
            } else when (action) {
                DuplicateAction.STORE_ANOTHER -> list.add(result)
                DuplicateAction.STORE_AS_CHECK -> { result = result.copy(classification = Classification.CHECK); list.add(result) }
                DuplicateAction.OVERWRITE -> { list.replaceAll { it.copy(deleted = true) }; list.add(result) }
                DuplicateAction.AVERAGE -> {
                    list.add(result)
                    val sources = list.filter { !it.deleted && it.classification == Classification.NORMAL && it.method != MeasureMethod.AVERAGED && it.latDeg != null }
                    if (sources.size >= 2) { result = averageOf(sources); list.add(result) }
                }
            }
        } finally { refresh(p.name) }
        advanceName(p.name); touch()
        return result
    }

    /** The mean of [sources] (GNSS observations of one point) as a new averaged record. Spread between the observations goes in sdH/sdV (metres). */
    private fun averageOf(sources: List<SurveyPoint>): SurveyPoint {
        val k = sources.size
        val unit = coordinateSystem.unit
        val e = sources.sumOf { it.e } / k; val n = sources.sumOf { it.n } / k; val z = sources.sumOf { it.z } / k
        val sdH = unit.toMeters(sqrt(sources.sumOf { (it.e - e).pow(2) + (it.n - n).pow(2) } / (k - 1)))
        val sdV = unit.toMeters(sqrt(sources.sumOf { (it.z - z).pow(2) } / (k - 1)))
        fun mean(f: (SurveyPoint) -> Double?): Double? = sources.mapNotNull(f).let { if (it.isEmpty()) null else it.average() }
        fun rms(f: (SurveyPoint) -> Double?): Double? = sources.mapNotNull(f).let { if (it.isEmpty()) null else sqrt(it.sumOf { v -> v * v } / it.size) }
        val last = sources.last()
        return last.copy(
            e = e, n = n, z = z, method = MeasureMethod.AVERAGED, classification = Classification.NORMAL, deleted = false,
            latDeg = mean { it.latDeg }, lonDeg = mean { it.lonDeg }, ellipsoidalHeight = mean { it.ellipsoidalHeight },
            solution = if (sources.all { it.solution == last.solution }) last.solution else sources.mapNotNull { it.solution }.minByOrNull { it.rank },
            hdop = mean { it.hdop }, pdop = mean { it.pdop }, sigmaH = rms { it.sigmaH }, sigmaV = rms { it.sigmaV },
            vector = if (sources.all { it.vector != null }) BaselineVector.average(sources.map { it.vector!! }) else null,
            satellites = sources.mapNotNull { it.satellites }.minOrNull(), epochs = sources.sumOf { it.epochs },
            sdH = sdH, sdV = sdV, timestampMillis = System.currentTimeMillis(), note = "Average of $k observations"
        )
    }

    /** Replaces the point's current position record in place (for edits such as the code or note). Stores it as a new point if the name is unknown. */
    fun replacePosition(p: SurveyPoint) {
        val list = records[p.name]
        val cur = positions[p.name]
        if (list == null || cur == null) { store(p); return }
        val i = list.indexOfLast { it === cur }
        list[i] = p.copy(classification = Classification.NORMAL, deleted = false)
        refresh(p.name); touch()
    }

    /** Restores a record exactly as saved (job file loading). Call [rebuildPositions] afterwards. */
    fun restore(p: SurveyPoint) { records.getOrPut(p.name) { ArrayList() }.add(p) }
    fun rebuildPositions() { positions.clear(); for (name in records.keys.toList()) refresh(name) }

    /** Applies [f] to every record (for example after a coordinate system change). */
    fun transformAll(f: (SurveyPoint) -> SurveyPoint) {
        for (list in records.values) list.replaceAll(f)
        rebuildPositions(); touch()
    }

    /** Removes one record of [name] by its index in [recordsOf]. */
    fun removeRecord(name: String, index: Int): Boolean {
        val list = records[name] ?: return false
        if (index !in list.indices) return false
        list.removeAt(index); refresh(name); touch(); return true
    }

    /** First unused name starting from [nextPointName]. */
    fun freeName(): String {
        var n = nextPointName
        while (positions.containsKey(n)) n = PointNamer.next(n)
        return n
    }

    fun remove(name: String): Boolean { val r = records.remove(name) != null; positions.remove(name); if (r) touch(); return r }
    fun clearPoints() { records.clear(); positions.clear() }
}

/** Feature code for quick coding while measuring. */
data class FeatureCode(
    val code: String, val description: String = "", val type: FeatureType = FeatureType.POINT,
    val colorHex: String = "#1565C0", val layer: String = ""
)
enum class FeatureType { POINT, LINE, AREA }

class FeatureCodeLibrary(codes: List<FeatureCode> = defaults) {
    val codes: MutableList<FeatureCode> = codes.toMutableList()
    fun find(code: String) = codes.firstOrNull { it.code.equals(code, true) }
    fun search(prefix: String) = codes.filter { it.code.startsWith(prefix, true) || it.description.contains(prefix, true) }

    companion object {
        val defaults = listOf(
            FeatureCode("CP", "Control point", FeatureType.POINT, "#C62828"),
            FeatureCode("TREE", "Tree", FeatureType.POINT, "#2E7D32", "VEG"),
            FeatureCode("POLE", "Utility pole", FeatureType.POINT, "#6D4C41", "UTIL"),
            FeatureCode("MH", "Manhole", FeatureType.POINT, "#455A64", "UTIL"),
            FeatureCode("EP", "Edge of pavement", FeatureType.LINE, "#37474F", "ROAD"),
            FeatureCode("CL", "Centreline", FeatureType.LINE, "#F9A825", "ROAD"),
            FeatureCode("FENCE", "Fence", FeatureType.LINE, "#8D6E63", "BOUNDARY"),
            FeatureCode("BLDG", "Building corner", FeatureType.AREA, "#5E35B1", "STRUCT"),
            FeatureCode("PIN", "Boundary pin", FeatureType.POINT, "#D81B60", "BOUNDARY")
        )
    }
}
