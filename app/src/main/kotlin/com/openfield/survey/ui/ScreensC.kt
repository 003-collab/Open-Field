package com.openfield.survey.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openfield.core.config.ColorRole
import com.openfield.core.geo.LinearUnit
import com.openfield.core.io.CsvColumn
import com.openfield.core.io.CsvFormat
import com.openfield.core.io.CsvIO
import com.openfield.core.io.DxfExport
import com.openfield.core.io.GeoJsonExport
import com.openfield.core.io.JobJson
import com.openfield.core.io.JobXmlExport
import com.openfield.core.io.LandXmlExport
import com.openfield.core.io.VectorCsvExport
import com.openfield.core.survey.PointKind
import com.openfield.survey.AppViewModel
import com.openfield.survey.abbr
import com.openfield.survey.fmtNum
import com.openfield.survey.fmtNumOrDash
import com.openfield.survey.fmtUtc

private val CUSTOM_FORMAT_ID = "custom"

private enum class ImportMode { POINTS, JOB }

@Composable
fun ImportExportScreen(vm: AppViewModel) {
    val job = vm.job
    val ctx = LocalContext.current
    val t = LocalAppTheme.current
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.POINTS) }

    // format controls (shared by export and import)
    val presets = remember { CsvFormat.builtin + CsvFormat(CUSTOM_FORMAT_ID, "Custom columns…", listOf(CsvColumn.NAME, CsvColumn.EASTING, CsvColumn.NORTHING, CsvColumn.ELEVATION, CsvColumn.CODE)) }
    var preset by remember { mutableStateOf(presets[0]) }
    var customCols by remember { mutableStateOf("NAME, EASTING, NORTHING, ELEVATION, CODE") }
    var delimiter by remember { mutableStateOf(",") }
    var header by remember { mutableStateOf(false) }
    var exportUnit by remember { mutableStateOf<LinearUnit?>(null) }
    var importUnit by remember { mutableStateOf(vm.unit) }
    var importKind by remember { mutableStateOf(PointKind.DESIGN) }
    var formatError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun currentFormat(): CsvFormat? {
        if (preset.id != CUSTOM_FORMAT_ID) return preset.copy(unit = exportUnit)
        val cols = customCols.split(',', ';').map { it.trim().uppercase().replace(' ', '_') }.filter { it.isNotEmpty() }.map {
            try { CsvColumn.valueOf(it) } catch (e: IllegalArgumentException) {
                formatError = "Unknown column '$it'. Valid: ${CsvColumn.entries.filter { c -> c != CsvColumn.IGNORE }.joinToString { c -> c.name }}, or IGNORE to skip one"
                return null
            }
        }
        if (cols.isEmpty()) { formatError = "List at least one column"; return null }
        formatError = null
        return CsvFormat(CUSTOM_FORMAT_ID, "Custom", cols, delimiter.firstOrNull() ?: ',', header, 3, 9, exportUnit)
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = pending
        if (uri != null && data != null) {
            try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }; vm.message = "File saved" }
            catch (e: Exception) { vm.message = "Could not save: ${e.message}" }
        }
        pending = null
    }
    fun save(fileName: String, text: String) { pending = text.toByteArray(Charsets.UTF_8); saver.launch(fileName) }

    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
        if (text == null) { result = "Could not read that file"; return@rememberLauncherForActivityResult }
        when (importMode) {
            ImportMode.JOB -> result = vm.importJobText(text) ?: "Job imported and opened"
            ImportMode.POINTS -> {
                val f = currentFormat()
                result = if (f == null) formatError else vm.importCsv(text, f.copy(unit = null), importUnit, importKind)
            }
        }
    }

    ScreenFrame(vm, label("tile.import_export")) {
        ScrollBody {
            Panel {
                SectionTitle("Text file format")
                Dropdown("Columns", presets, preset, { it.name }, { preset = it })
                if (preset.id == CUSTOM_FORMAT_ID) {
                    TextInput("Column order", customCols, { customCols = it; formatError = null }, error = formatError != null)
                    InfoText("Choose from: ${CsvColumn.entries.filter { it != CsvColumn.IGNORE }.joinToString { it.name }}. Use IGNORE for a column to skip on import.")
                    TextInput("Delimiter", delimiter, { delimiter = it.take(1) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("First row is a header", Modifier.weight(1f)); Switch(checked = header, onCheckedChange = { header = it })
                    }
                }
                formatError?.let { InfoText(it, color = t.c(ColorRole.BAD)) }
            }

            Panel {
                SectionTitle("Export")
                if (job == null) InfoText("Open a job to export its points.") else {
                    InfoText("${job.points.size} point(s) in ${job.name}")
                    Dropdown("Export distance units", listOf<LinearUnit?>(null) + LinearUnit.entries, exportUnit, { it?.label ?: "Job units (${job.coordinateSystem.unit.label})" }, { exportUnit = it })
                    AppButton("Points as text file (CSV)", {
                        currentFormat()?.let { f -> save("${job.name}.csv", CsvIO.export(job, f)) }
                    }, Modifier.fillMaxWidth())
                    AppButton("GeoJSON (WGS 84)", { save("${job.name}.geojson", GeoJsonExport.export(job)) }, Modifier.fillMaxWidth(), primary = false)
                    AppButton("DXF (points and names)", { save("${job.name}.dxf", DxfExport.export(job, vm.featureCodes)) }, Modifier.fillMaxWidth(), primary = false)
                    AppButton("Trimble JobXML (.jxl)", { save("${job.name}.jxl", JobXmlExport.export(job)) }, Modifier.fillMaxWidth(), primary = false)
                    AppButton("Vectors (CSV, ECEF + local)", { save("${job.name}_vectors.csv", VectorCsvExport.export(job)) }, Modifier.fillMaxWidth(), primary = false)
                    AppButton("LandXML", { save("${job.name}.xml", LandXmlExport.export(job)) }, Modifier.fillMaxWidth(), primary = false)
                    AppButton("OpenField job file (backup)", { save("${job.name}.ofjob", JobJson.toJson(job)) }, Modifier.fillMaxWidth(), primary = false)
                }
            }

            Panel {
                SectionTitle("Import")
                Dropdown("Values in file are in", LinearUnit.entries.toList(), importUnit, { it.label }, { importUnit = it })
                Dropdown("Import as", listOf(PointKind.DESIGN, PointKind.CONTROL, PointKind.KEYED_IN), importKind, { it.label }, { importKind = it })
                InfoText("Points whose names already exist in the job are skipped, never overwritten.")
                AppButton("Import points from text file", {
                    importMode = ImportMode.POINTS
                    if (job == null) vm.message = "Open a job first" else { result = null; opener.launch(arrayOf("*/*")) }
                }, Modifier.fillMaxWidth())
                AppButton("Open an OpenField job file", { importMode = ImportMode.JOB; result = null; opener.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), primary = false)
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ------------------------------------------------------------------ GNSS status

@Composable
fun GnssStatusScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    var showConsole by remember { mutableStateOf(vm.config.layout.showNmeaConsole) }
    DisposableEffect(showConsole) {
        vm.consoleOn = showConsole
        onDispose { vm.consoleOn = false }
    }
    val s = vm.snapshot
    val unit = vm.unit
    ScreenFrame(vm, label("tile.gnss_status")) {
        ScrollBody {
            Panel {
                Text(
                    s?.solution?.label ?: "No data", color = t.solutionColor(s?.solution),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                if (s == null) InfoText(
                    when (vm.connection) {
                        com.openfield.core.gnss.ConnectionState.ERROR -> "Connection error: ${vm.connectionMessage ?: "unknown"}"
                        com.openfield.core.gnss.ConnectionState.CONNECTING -> "Connecting…"
                        com.openfield.core.gnss.ConnectionState.CONNECTED -> "Connected. Waiting for the first position sentence (GGA or GNS)."
                        else -> "Not connected. Use Connect on the home screen."
                    }
                )
                else {
                    KeyValue(label("field.lat"), fmtNum(s.latDeg, 9) + "°", mono = true)
                    KeyValue(label("field.lon"), fmtNum(s.lonDeg, 9) + "°", mono = true)
                    KeyValue(label("field.ellh"), fmtNum(unit.fromMeters(s.ellipsoidalHeight), 3) + " " + unit.abbr(), mono = true)
                    KeyValue("Altitude (receiver, MSL)", fmtNum(unit.fromMeters(s.altMsl), 3) + " " + unit.abbr(), mono = true)
                    KeyValue("Horizontal precision", s.horizontalPrecision?.let { fmtNum(unit.fromMeters(it), 3) + " " + unit.abbr() } ?: "not reported (needs GST)")
                    KeyValue("Vertical precision", s.verticalPrecision?.let { fmtNum(unit.fromMeters(it), 3) + " " + unit.abbr() } ?: "not reported (needs GST)")
                    KeyValue("Satellites used", s.satellitesUsed.toString())
                    KeyValue("HDOP / PDOP / VDOP", "${fmtNumOrDash(s.hdop, 1)} / ${fmtNumOrDash(s.pdop, 1)} / ${fmtNumOrDash(s.vdop, 1)}")
                    KeyValue("Correction age", s.diffAgeSeconds?.let { fmtNum(it, 0) + " s" } ?: "-")
                    KeyValue("Reference station", s.referenceStation ?: "-")
                    KeyValue("Speed / course", "${fmtNumOrDash(s.speedMps, 2)} m/s / ${s.courseDeg?.let { vm.fmtAngle(it) } ?: "-"}")
                    KeyValue("UTC time", fmtUtc(s.utcSeconds))
                }
            }

            if (vm.simulator != null) {
                Panel {
                    SectionTitle("Simulator controls")
                    InfoText("Force a solution type to see how survey-style quality limits accept or reject epochs.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Fixed", { vm.setSimulatorQuality(4) }, primary = false)
                        AppButton("Float", { vm.setSimulatorQuality(5) }, primary = false)
                        AppButton("DGPS", { vm.setSimulatorQuality(2) }, primary = false)
                        AppButton("Single", { vm.setSimulatorQuality(1) }, primary = false)
                    }
                }
            }

            val sats = s?.satellites ?: emptyList()
            Panel {
                SectionTitle("Sky plot (${sats.size} in view)")
                if (sats.isEmpty()) InfoText("No GSV sentences received. Enable GSV output on the receiver to see satellites.")
                else {
                    SkyPlot(sats, Modifier.fillMaxWidth().height(280.dp))
                    SectionTitle("Signal strength (C/N0)")
                    SnrBars(sats, Modifier.fillMaxWidth().height(120.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        sats.map { it.constellation }.distinct().forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(constellationColor(c)) }
                                Text(" ${c.label}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NMEA console", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = showConsole, onCheckedChange = { showConsole = it })
                }
                if (showConsole) {
                    if (vm.console.isEmpty()) InfoText("Waiting for data…")
                    Column {
                        vm.console.takeLast(40).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}
