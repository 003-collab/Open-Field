package com.openfield.core.io

import com.openfield.core.geo.GridPoint
import com.openfield.core.geo.LinearUnit
import com.openfield.core.json.Json
import com.openfield.core.survey.*
import java.time.Instant

enum class CsvColumn(val header: String) {
    NAME("Name"), CODE("Code"), NORTHING("Northing"), EASTING("Easting"), ELEVATION("Elevation"),
    LATITUDE("Latitude"), LONGITUDE("Longitude"), ELLIPSOIDAL_HEIGHT("EllipsoidalHeight"),
    SOLUTION("Solution"), HDOP("HDOP"), PDOP("PDOP"), SIGMA_H("SigmaH"), SIGMA_V("SigmaV"),
    SATELLITES("Satellites"), EPOCHS("Epochs"), ANTENNA_HEIGHT("AntennaHeight"), TIME("Time"), METHOD("Method"), NOTE("Note"),
    /** Skipped on import, blank on export. */
    IGNORE("")
}

/**
 * User-definable text format: choose columns, order, delimiter, header row, decimals and units. Presets cover the common
 * P,N,E,Z,D layouts; users can save their own (see [toMap]/[fromMap]) to match any office software.
 */
data class CsvFormat(
    val id: String, val name: String,
    val columns: List<CsvColumn>,
    val delimiter: Char = ',',
    val header: Boolean = false,
    val decimals: Int = 3,
    val geodeticDecimals: Int = 9,
    /** null = keep the job's unit. */
    val unit: LinearUnit? = null
) {
    fun toMap() = mapOf("id" to id, "name" to name, "columns" to columns.map { it.name }, "delimiter" to delimiter.toString(),
        "header" to header, "decimals" to decimals, "geodeticDecimals" to geodeticDecimals, "unit" to unit?.name)

    companion object {
        fun fromMap(m: Map<String, Any?>) = CsvFormat(
            m["id"] as? String ?: "custom", m["name"] as? String ?: "Custom",
            (m["columns"] as? List<*>)?.mapNotNull { (it as? String)?.let { s -> runCatching { CsvColumn.valueOf(s) }.getOrNull() } } ?: emptyList(),
            (m["delimiter"] as? String)?.firstOrNull() ?: ',', m["header"] as? Boolean ?: false,
            (m["decimals"] as? Number)?.toInt() ?: 3, (m["geodeticDecimals"] as? Number)?.toInt() ?: 9,
            (m["unit"] as? String)?.let { runCatching { LinearUnit.valueOf(it) }.getOrNull() }
        )

        val PNEZD = CsvFormat("pnezd", "Name, Northing, Easting, Elevation, Code", listOf(CsvColumn.NAME, CsvColumn.NORTHING, CsvColumn.EASTING, CsvColumn.ELEVATION, CsvColumn.CODE))
        val PENZD = CsvFormat("penzd", "Name, Easting, Northing, Elevation, Code", listOf(CsvColumn.NAME, CsvColumn.EASTING, CsvColumn.NORTHING, CsvColumn.ELEVATION, CsvColumn.CODE))
        val FULL = CsvFormat("full", "Full GNSS report", listOf(CsvColumn.NAME, CsvColumn.CODE, CsvColumn.NORTHING, CsvColumn.EASTING, CsvColumn.ELEVATION,
            CsvColumn.LATITUDE, CsvColumn.LONGITUDE, CsvColumn.ELLIPSOIDAL_HEIGHT, CsvColumn.SOLUTION, CsvColumn.HDOP, CsvColumn.PDOP, CsvColumn.SIGMA_H, CsvColumn.SIGMA_V,
            CsvColumn.SATELLITES, CsvColumn.EPOCHS, CsvColumn.ANTENNA_HEIGHT, CsvColumn.TIME, CsvColumn.NOTE), header = true)
        val builtin = listOf(PNEZD, PENZD, FULL)
    }
}

class ImportResult(val points: List<SurveyPoint>, val errors: List<String>)

object CsvIO {
    fun export(job: Job, format: CsvFormat, points: List<SurveyPoint> = job.points): String {
        val sb = StringBuilder()
        if (format.header) sb.append(format.columns.joinToString(format.delimiter.toString()) { it.header }).append("\r\n")
        val factor = format.unit?.let { job.coordinateSystem.unit.metersPerUnit / it.metersPerUnit } ?: 1.0
        for (p in points) {
            sb.append(format.columns.joinToString(format.delimiter.toString()) { col -> cell(p, col, format, factor) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun d(v: Double?, dec: Int) = if (v == null) "" else "%.${dec}f".format(java.util.Locale.ROOT, v)

    private fun cell(p: SurveyPoint, c: CsvColumn, f: CsvFormat, k: Double): String = when (c) {
        CsvColumn.NAME -> esc(p.name, f.delimiter); CsvColumn.CODE -> esc(p.code, f.delimiter)
        CsvColumn.NORTHING -> d(p.n * k, f.decimals); CsvColumn.EASTING -> d(p.e * k, f.decimals); CsvColumn.ELEVATION -> d(p.z * k, f.decimals)
        CsvColumn.LATITUDE -> d(p.latDeg, f.geodeticDecimals); CsvColumn.LONGITUDE -> d(p.lonDeg, f.geodeticDecimals)
        CsvColumn.ELLIPSOIDAL_HEIGHT -> d(p.ellipsoidalHeight, f.decimals)
        CsvColumn.SOLUTION -> p.solution?.label ?: ""; CsvColumn.HDOP -> d(p.hdop, 1); CsvColumn.PDOP -> d(p.pdop, 1)
        CsvColumn.SIGMA_H -> d(p.sigmaH, 3); CsvColumn.SIGMA_V -> d(p.sigmaV, 3)
        CsvColumn.SATELLITES -> p.satellites?.toString() ?: ""; CsvColumn.EPOCHS -> p.epochs.toString()
        CsvColumn.ANTENNA_HEIGHT -> d(p.antennaHeight, 3)
        CsvColumn.TIME -> if (p.timestampMillis > 0) Instant.ofEpochMilli(p.timestampMillis).toString() else ""
        CsvColumn.METHOD -> p.method.label; CsvColumn.NOTE -> esc(p.note, f.delimiter); CsvColumn.IGNORE -> ""
    }

    private fun esc(s: String, delim: Char) = if (s.contains(delim) || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Splits one CSV line honouring double quotes. */
    fun splitLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> q = !q
                ch == delim && !q -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    /**
     * Import points (typically design/control points for stakeout). Values are taken as being in [sourceUnit] and
     * converted to the job's unit. Missing coordinates produce an error line, not a crash.
     */
    fun import(text: String, format: CsvFormat, jobUnit: LinearUnit, sourceUnit: LinearUnit = jobUnit, kind: PointKind = PointKind.DESIGN): ImportResult {
        val points = ArrayList<SurveyPoint>(); val errors = ArrayList<String>()
        val k = sourceUnit.metersPerUnit / jobUnit.metersPerUnit
        text.lineSequence().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (idx == 0 && format.header) return@forEachIndexed
            val cells = splitLine(line, format.delimiter)
            fun get(c: CsvColumn): String? = format.columns.indexOf(c).takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim() }
            val name = get(CsvColumn.NAME); val n = get(CsvColumn.NORTHING)?.toDoubleOrNull(); val e = get(CsvColumn.EASTING)?.toDoubleOrNull()
            if (name.isNullOrEmpty() || n == null || e == null) { errors.add("Line ${idx + 1}: needs a name, northing and easting"); return@forEachIndexed }
            val z = get(CsvColumn.ELEVATION)?.toDoubleOrNull() ?: Double.NaN
            points.add(SurveyPoint(name = name, code = get(CsvColumn.CODE) ?: "", kind = kind, method = MeasureMethod.IMPORTED, e = e * k, n = n * k, z = if (z.isNaN()) 0.0 else z * k, note = get(CsvColumn.NOTE) ?: ""))
        }
        return ImportResult(points, errors)
    }
}

object GeoJsonExport {
    /** Points with known lat/lon as a GeoJSON FeatureCollection (WGS 84, [lon, lat, ellipsoidal height]). */
    fun export(job: Job, points: List<SurveyPoint> = job.points): String {
        val cs = job.coordinateSystem
        val features = points.mapNotNull { p ->
            var lat = p.latDeg; var lon = p.lonDeg; var h = p.ellipsoidalHeight
            if ((lat == null || lon == null) && cs.projection != null) {
                val ll = cs.fromLocal(GridPoint(p.e, p.n, p.z)); lat = ll.latDeg; lon = ll.lonDeg; h = ll.ellipsoidalHeight
            }
            if (lat == null || lon == null) return@mapNotNull null
            mapOf("type" to "Feature",
                "geometry" to mapOf("type" to "Point", "coordinates" to listOfNotNull(lon, lat, h)),
                "properties" to linkedMapOf("name" to p.name, "code" to p.code, "northing" to p.n, "easting" to p.e, "elevation" to p.z,
                    "solution" to p.solution?.label, "hdop" to p.hdop, "time" to if (p.timestampMillis > 0) Instant.ofEpochMilli(p.timestampMillis).toString() else null))
        }
        return Json.stringify(mapOf("type" to "FeatureCollection", "name" to job.name, "features" to features))
    }
}

object DxfExport {
    /** Minimal ASCII DXF (R12 style): a POINT and a name TEXT per point, layered by feature-code layer. */
    fun export(job: Job, codes: FeatureCodeLibrary = FeatureCodeLibrary(), textHeight: Double = 0.25, points: List<SurveyPoint> = job.points): String {
        val sb = StringBuilder()
        fun g(code: Int, v: Any) { sb.append(code).append('\n').append(v).append('\n') }
        g(0, "SECTION"); g(2, "ENTITIES")
        for (p in points) {
            val layer = codes.find(p.code)?.layer?.takeIf { it.isNotEmpty() } ?: if (p.code.isNotEmpty()) p.code.uppercase().replace(Regex("[^A-Z0-9_]"), "_") else "POINTS"
            g(0, "POINT"); g(8, layer); g(10, fmt(p.e)); g(20, fmt(p.n)); g(30, fmt(p.z))
            g(0, "TEXT"); g(8, "$layer-NAMES"); g(10, fmt(p.e + textHeight)); g(20, fmt(p.n + textHeight)); g(30, fmt(p.z)); g(40, fmt(textHeight)); g(1, p.name)
        }
        g(0, "ENDSEC"); g(0, "EOF")
        return sb.toString()
    }
    private fun fmt(v: Double) = "%.4f".format(java.util.Locale.ROOT, v)
}

object LandXmlExport {
    fun export(job: Job, appVersion: String = "1.0", points: List<SurveyPoint> = job.points): String {
        val cs = job.coordinateSystem
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\" date=\"${java.time.LocalDate.now()}\" time=\"${java.time.LocalTime.now().withNano(0)}\">\n")
        val unitXml = when (cs.unit) {
            LinearUnit.METER -> "<Metric linearUnit=\"meter\" areaUnit=\"squareMeter\" volumeUnit=\"cubicMeter\" temperatureUnit=\"celsius\" pressureUnit=\"milliBars\"/>"
            LinearUnit.US_SURVEY_FOOT -> "<Imperial linearUnit=\"USSurveyFoot\" areaUnit=\"squareFoot\" volumeUnit=\"cubicFeet\" temperatureUnit=\"fahrenheit\" pressureUnit=\"inHG\"/>"
            LinearUnit.INTL_FOOT -> "<Imperial linearUnit=\"foot\" areaUnit=\"squareFoot\" volumeUnit=\"cubicFeet\" temperatureUnit=\"fahrenheit\" pressureUnit=\"inHG\"/>"
        }
        sb.append("  <Units>$unitXml</Units>\n")
        sb.append("  <CoordinateSystem name=\"${x(cs.name)}\" desc=\"${x(cs.name)}\"/>\n")
        sb.append("  <Project name=\"${x(job.name)}\" desc=\"${x(job.description)}\"/>\n")
        sb.append("  <Application name=\"OpenField\" version=\"${x(appVersion)}\"/>\n")
        sb.append("  <CgPoints>\n")
        for (p in points) {
            // LandXML order is Northing Easting Elevation
            sb.append("    <CgPoint name=\"${x(p.name)}\" code=\"${x(p.code)}\">${"%.4f %.4f %.4f".format(java.util.Locale.ROOT, p.n, p.e, p.z)}</CgPoint>\n")
        }
        sb.append("  </CgPoints>\n</LandXML>\n")
        return sb.toString()
    }
    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
