package com.openfield.core.io

import com.openfield.core.geo.GridPoint
import com.openfield.core.geo.LambertConic2SP
import com.openfield.core.geo.LinearUnit
import com.openfield.core.geo.NoGeoid
import com.openfield.core.geo.TransverseMercator
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

/**
 * Trimble JobXML (.jxl) export, for opening a job's points in Trimble Business Center, Trimble Access or other tools that read JobXML.
 * Like JobXML itself, every distance is written in metres; the UnitsRecord tells the reader which unit to display.
 * Written: header, job properties, units, coordinate system names, and one PointRecord per point (name, code, method, classification,
 * grid North/East/Elevation, and WGS 84 latitude/longitude/height when the point was measured with GNSS).
 */
object JobXmlExport {
    fun export(job: Job, appVersion: String = "1.0", points: List<SurveyPoint> = job.observations): String {
        val cs = job.coordinateSystem
        val u = cs.unit
        val stamp = fun(ms: Long): String = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(if (ms > 0) ms else job.createdMillis))
        fun m(v: Double) = String.format(java.util.Locale.ROOT, "%.5f", u.toMeters(v))
        fun d(v: Double) = String.format(java.util.Locale.ROOT, "%.10f", v)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<JOBFile jobName=\"${x(job.name)}\" TimeStamp=\"${stamp(job.modifiedMillis)}\" version=\"5.50\" product=\"OpenField\" productVersion=\"${x(appVersion)}\">\n")
        sb.append("  <FieldBook>\n")
        sb.append("    <JobPropertiesRecord TimeStamp=\"${stamp(job.createdMillis)}\">\n")
        sb.append("      <Reference>${x(job.name)}</Reference>\n      <Description>${x(job.description)}</Description>\n      <Operator>${x(job.operator)}</Operator>\n")
        sb.append("    </JobPropertiesRecord>\n")
        val distanceUnits = when (u) { LinearUnit.METER -> "Metres"; LinearUnit.US_SURVEY_FOOT -> "USSurveyFeet"; LinearUnit.INTL_FOOT -> "InternationalFeet" }
        sb.append("    <UnitsRecord TimeStamp=\"${stamp(job.createdMillis)}\">\n")
        sb.append("      <DistanceUnits>$distanceUnits</DistanceUnits>\n      <AngleUnits>Degrees</AngleUnits>\n      <LatLongUnits>Degrees</LatLongUnits>\n      <CoordinateOrder>North-East-Elevation</CoordinateOrder>\n")
        sb.append("    </UnitsRecord>\n")
        val projType = when (cs.projection) { is TransverseMercator -> "TransverseMercator"; is LambertConic2SP -> "LambertConformalConic2SP"; null -> "None" }
        sb.append("    <CoordinateSystemRecord TimeStamp=\"${stamp(job.createdMillis)}\">\n")
        sb.append("      <SystemName>${x(cs.librarySystem ?: cs.name)}</SystemName>\n      <ZoneName>${x(cs.libraryZone ?: cs.name)}</ZoneName>\n      <DatumName>${x(cs.datum.name)}</DatumName>\n")
        sb.append("      <ProjectionType>$projType</ProjectionType>\n")
        cs.geoid.let { if (it !== NoGeoid) sb.append("      <GeoidModel>${x(it.name)}</GeoidModel>\n") }
        sb.append("    </CoordinateSystemRecord>\n")
        for (p in points) {
            sb.append("    <PointRecord TimeStamp=\"${stamp(p.timestampMillis)}\">\n")
            sb.append("      <Name>${x(p.name)}</Name>\n      <Code>${x(p.code)}</Code>\n")
            val v = p.vector
            if (v != null && p.method != MeasureMethod.AVERAGED) sb.append("      <Method>GpsStaticObservation</Method>\n      <SurveyMethod>RTK</SurveyMethod>\n")
            else sb.append("      <Method>${x(if (p.method == MeasureMethod.AVERAGED) "Averaged" else if (p.latDeg != null) "RTK" else p.method.label)}</Method>\n")
            sb.append("      <Classification>${if (p.classification == Classification.CHECK) "Check" else if (p.kind == PointKind.CONTROL) "Control" else "Normal"}</Classification>\n")
            if (v != null) {
                // Base-to-rover vector, ECEF metres, as in Trimble GNSS observation records.
                sb.append("      <ECEFDeltas>\n        <DeltaX>${String.format(java.util.Locale.ROOT, "%.5f", v.dX)}</DeltaX>\n        <DeltaY>${String.format(java.util.Locale.ROOT, "%.5f", v.dY)}</DeltaY>\n        <DeltaZ>${String.format(java.util.Locale.ROOT, "%.5f", v.dZ)}</DeltaZ>\n      </ECEFDeltas>\n")
                sb.append("      <RTK_Base>${x(v.baseId)}</RTK_Base>\n")
            }
            sb.append("      <Grid>\n        <North>${m(p.n)}</North>\n        <East>${m(p.e)}</East>\n        <Elevation>${m(p.z)}</Elevation>\n      </Grid>\n")
            if (p.latDeg != null && p.lonDeg != null) {
                sb.append("      <WGS84>\n        <Latitude>${d(p.latDeg)}</Latitude>\n        <Longitude>${d(p.lonDeg)}</Longitude>\n")
                p.ellipsoidalHeight?.let { sb.append("        <Height>${String.format(java.util.Locale.ROOT, "%.5f", it)}</Height>\n") }
                sb.append("      </WGS84>\n")
            }
            p.vector?.cov?.let { c ->
                fun e(n: String, i: Int) = "        <$n>${String.format(java.util.Locale.ROOT, "%.10e", c[i])}</$n>\n"
                sb.append("      <QualityControl2>\n").append(e("VCVxx", 0)).append(e("VCVxy", 1)).append(e("VCVxz", 2)).append(e("VCVyy", 3)).append(e("VCVyz", 4)).append(e("VCVzz", 5))
                    .append("        <UnitVariance>1.0000000000</UnitVariance>\n      </QualityControl2>\n")
            }
            sb.append("    </PointRecord>\n")
        }
        sb.append("  </FieldBook>\n</JOBFile>\n")
        return sb.toString()
    }
    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * Baseline vectors as CSV, for network adjustment software: one row per observation that has a vector (overwritten ones excluded).
 * Vectors run from the base reference point to the rover antenna reference point (before antenna height reduction), in ECEF metres.
 * Sx, Sy, Sz are the standard deviations from the covariance diagonal; Cxy, Cxz, Cyz are the covariances in m^2 (blank when unknown).
 */
object VectorCsvExport {
    fun export(job: Job): String {
        val sb = StringBuilder("Point,Observation,Class,Base ID,Base X,Base Y,Base Z,dX,dY,dZ,Length,dE,dN,dU,Sx,Sy,Sz,Cxy,Cxz,Cyz,Antenna height,Time UTC\n")
        fun f(v: Double, dec: Int = 4) = String.format(java.util.Locale.ROOT, "%.${dec}f", v)
        fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val index = HashMap<String, Int>()
        for (p in job.observations) {
            val k = (index[p.name] ?: 0) + 1; index[p.name] = k
            val v = p.vector ?: continue
            val enu = v.enu(); val sg = v.sigmaXyz(); val c = v.cov
            sb.append(q(p.name)).append(',').append(k).append(',')
                .append(if (p.classification == Classification.CHECK) "Check" else if (p.method == MeasureMethod.AVERAGED) "Average" else "Normal").append(',')
                .append(q(v.baseId)).append(',').append(f(v.baseX)).append(',').append(f(v.baseY)).append(',').append(f(v.baseZ)).append(',')
                .append(f(v.dX)).append(',').append(f(v.dY)).append(',').append(f(v.dZ)).append(',').append(f(v.length)).append(',')
                .append(f(enu[0])).append(',').append(f(enu[1])).append(',').append(f(enu[2])).append(',')
                .append(sg?.let { f(it[0], 5) } ?: "").append(',').append(sg?.let { f(it[1], 5) } ?: "").append(',').append(sg?.let { f(it[2], 5) } ?: "").append(',')
                .append(c?.let { f(it[1], 8) } ?: "").append(',').append(c?.let { f(it[2], 8) } ?: "").append(',').append(c?.let { f(it[4], 8) } ?: "").append(',')
                .append(p.antennaHeight?.let { f(it, 3) } ?: "").append(',')
                .append(if (p.timestampMillis > 0) java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(p.timestampMillis)) else "")
                .append('\n')
        }
        return sb.toString()
    }
}
