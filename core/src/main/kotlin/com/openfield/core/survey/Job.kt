package com.openfield.core.survey

import com.openfield.core.geo.CoordinateSystem
import com.openfield.core.gnss.SolutionType

enum class PointKind(val label: String) { MEASURED("Measured"), CONTROL("Control"), DESIGN("Design"), KEYED_IN("Keyed in") }
enum class MeasureMethod(val label: String) { TOPO("Topo"), OBSERVED_CONTROL("Observed control"), CONTINUOUS("Continuous"), KEYED_IN("Keyed in"), IMPORTED("Imported") }

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
    val deviceId: String = ""
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
    private val _points = LinkedHashMap<String, SurveyPoint>()
    val points: List<SurveyPoint> get() = _points.values.toList()
    val lines = ArrayList<DesignLine>()
    var nextPointName: String = "1"
    var styleId: String = ""
    var modifiedMillis: Long = createdMillis
        private set

    fun find(name: String): SurveyPoint? = _points[name]
    fun has(name: String) = _points.containsKey(name)

    /** Adds a point; throws [DuplicatePointException] unless [overwrite]. Advances the auto-name counter when the name matches it. */
    fun add(p: SurveyPoint, overwrite: Boolean = false) {
        if (_points.containsKey(p.name) && !overwrite) throw DuplicatePointException(p.name)
        _points[p.name] = p
        if (p.name == nextPointName) nextPointName = PointNamer.next(p.name)
        modifiedMillis = System.currentTimeMillis()
    }

    /** First unused name starting from [nextPointName]. */
    fun freeName(): String {
        var n = nextPointName
        while (_points.containsKey(n)) n = PointNamer.next(n)
        return n
    }

    fun remove(name: String): Boolean { val r = _points.remove(name) != null; if (r) modifiedMillis = System.currentTimeMillis(); return r }
    fun clearPoints() { _points.clear() }
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
