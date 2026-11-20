@file:OptIn(ExperimentalLayoutApi::class)

package com.openfield.survey.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openfield.core.config.ColorRole
import com.openfield.core.geo.LatLonH
import com.openfield.core.geo.Localization
import com.openfield.core.geo.ControlPair
import com.openfield.core.geo.CalibrationReport
import com.openfield.core.geo.VerticalAdjustment
import com.openfield.core.survey.Angles
import com.openfield.core.survey.Cogo
import com.openfield.core.survey.DesignLine
import com.openfield.core.survey.MeasureMethod
import com.openfield.core.survey.MeasureSpec
import com.openfield.core.survey.PointKind
import com.openfield.core.survey.SurveyPoint
import com.openfield.survey.AppViewModel
import com.openfield.survey.abbr
import com.openfield.survey.fmtDateTime
import com.openfield.survey.fmtNum
import com.openfield.survey.fmtNumOrDash
import com.openfield.survey.parseNum
import kotlin.math.abs

// ------------------------------------------------------------------ stake out: points

@Composable
fun StakePointsScreen(vm: AppViewModel) {
    val job = vm.job
    var targetName by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = targetName != null) { targetName = null }
    ScreenFrame(vm, label("tile.stake_points"), onBack = { if (targetName != null) targetName = null else vm.back() }, actions = { if (targetName != null) MapModeButtons(vm) }) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        @Suppress("UNUSED_VARIABLE") val rev = vm.pointsRev
        val target = targetName?.let { job.find(it) }
        if (target == null) {
            var q by remember { mutableStateOf("") }
            val list = remember(q, vm.pointsRev) { job.points.filter { q.isBlank() || it.name.contains(q, true) || it.code.contains(q, true) } }
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Search points to stake", q, { q = it })
                if (job.points.isEmpty()) InfoText("This job has no points. Import design points in Import / Export, or key them in with Cogo.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.name }) { p ->
                        Panel(onClick = { targetName = p.name }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    InfoText("${p.code}  ${p.kind.label}")
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("N ${vm.fmtDist(p.n)}", fontFamily = FontFamily.Monospace)
                                    Text("E ${vm.fmtDist(p.e)}", fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            StakeRun(vm, target) { targetName = it.name }
        }
    }
}

@Composable
private fun StakeRun(vm: AppViewModel, target: SurveyPoint, onTarget: (SurveyPoint) -> Unit) {
    val job = vm.job ?: return
    val t = LocalAppTheme.current
    var useZ by remember(target.name) { mutableStateOf(target.z != 0.0) }
    val measuring = vm.progress != null
    val pts = job.points
    val idx = pts.indexOfFirst { it.name == target.name }
    TwoPane(vm,
        left = {
            Panel {
                Text(target.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (target.code.isNotEmpty()) InfoText(target.code)
                KeyValue(label("field.northing"), vm.fmtDist(target.n), mono = true)
                KeyValue(label("field.easting"), vm.fmtDist(target.e), mono = true)
                KeyValue(label("field.elevation"), vm.fmtDist(target.z), mono = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include elevation (cut/fill)", Modifier.weight(1f))
                    Switch(checked = useZ, onCheckedChange = { useZ = it })
                }
            }
            GuidancePanel(vm, target.e, target.n, if (useZ) target.z else null)
            AntennaEditor(vm)
            MeasureProgressPanel(vm)
            ActionBar {
                AppButton("Previous", { if (idx > 0) onTarget(pts[idx - 1]) }, primary = false, enabled = idx > 0 && !measuring)
                AppButton("Next", { if (idx in 0 until pts.size - 1) onTarget(pts[idx + 1]) }, primary = false, enabled = idx in 0 until pts.size - 1 && !measuring)
            }
            ActionBar {
                if (measuring) {
                    AppButton("Cancel", { vm.cancelMeasure() }, primary = false)
                    AppButton("Store now", { vm.finishMeasureEarly() }, color = t.c(ColorRole.ACCENT))
                } else {
                    AppButton("Store as-staked", {
                        val h = vm.antennaMeasuredMetres()
                        if (h == null) vm.message = "Enter the antenna height" else {
                            val err = vm.startMeasure(MeasureSpec(null, "STAKED", MeasureMethod.TOPO, null, h, vm.antennaMethod, "Staked ${target.name}"))
                            if (err != null) vm.message = err
                        }
                    }, enabled = vm.live != null && vm.antennaError == null)
                }
            }
        },
        right = { m -> PlanView(pts, vm.featureCodes, vm.live?.ground?.let { it.e to it.n }, target.e to target.n, m, job.lines) }
    )
}

// ------------------------------------------------------------------ stake out: lines

@Composable
fun StakeLinesScreen(vm: AppViewModel) {
    val job = vm.job
    val t = LocalAppTheme.current
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    BackHandler(enabled = selected != null) { selected = null }
    ScreenFrame(
        vm, label("tile.stake_lines"),
        onBack = { if (selected != null) selected = null else vm.back() },
        actions = {
            if (selected == null && job != null) IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add line", tint = t.c(ColorRole.ON_HEADER)) }
            if (selected != null) MapModeButtons(vm)
        }
    ) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        @Suppress("UNUSED_VARIABLE") val rev = vm.pointsRev
        val line = selected?.let { n -> job.lines.firstOrNull { it.name == n } }
        if (line == null) {
            ScrollBody {
                if (job.lines.isEmpty()) InfoText("No lines yet. Tap + to define a line between two points.")
                job.lines.toList().forEach { l ->
                    Panel(onClick = { selected = l.name }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                InfoText("Length ${vm.fmtDist(l.length)} ${vm.unit.abbr()}   Azimuth ${vm.fmtAngle(l.azimuthDeg)}   Start station ${vm.fmtDist(l.startStation)}")
                            }
                            IconButton(onClick = { vm.deleteLine(l) }) { Icon(Icons.Default.Delete, "Delete line") }
                        }
                    }
                }
            }
        } else {
            LineRun(vm, line)
        }
    }
    if (adding) AddLineDialog(vm) { adding = false }
}

@Composable
private fun LineRun(vm: AppViewModel, line: DesignLine) {
    val job = vm.job ?: return
    val t = LocalAppTheme.current
    val live = vm.live
    val measuring = vm.progress != null
    var stationText by remember(line.name) { mutableStateOf("") }
    var offsetText by remember(line.name) { mutableStateOf("0") }
    val st = parseNum(stationText); val off = parseNum(offsetText)
    val tgt = if (st != null && off != null) line.pointAt(st, off) else null
    val tgtZ = tgt?.let { line.locate(it.first, it.second, null).designZ }
    val u = " " + vm.unit.abbr()
    TwoPane(vm,
        left = {
            Panel {
                Text(line.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (live == null) Text("Waiting for a position…") else {
                    val r = line.locate(live.ground.e, live.ground.n, live.ground.z)
                    BigValue(label("stake.station"), vm.fmtDist(r.station) + u)
                    BigValue(if (r.offset >= 0) "${label("stake.offset")} right" else "${label("stake.offset")} left", vm.fmtDist(abs(r.offset)) + u)
                    r.cutFill?.let { BigValue(if (it >= 0) label("stake.cut") else label("stake.fill"), vm.fmtDist(abs(it)) + u) }
                    if (r.beyondEnds) InfoText("Beyond the ends of the line", color = t.c(ColorRole.WARNING))
                }
            }
            Panel {
                SectionTitle("Go to station / offset")
                NumField(label("stake.station"), stationText, { stationText = it }, suffix = vm.unit.abbr())
                NumField("${label("stake.offset")} (right +, left -)", offsetText, { offsetText = it }, suffix = vm.unit.abbr())
            }
            if (tgt != null) GuidancePanel(vm, tgt.first, tgt.second, tgtZ)
            AntennaEditor(vm)
            MeasureProgressPanel(vm)
            ActionBar {
                if (measuring) {
                    AppButton("Cancel", { vm.cancelMeasure() }, primary = false)
                    AppButton("Store now", { vm.finishMeasureEarly() }, color = t.c(ColorRole.ACCENT))
                } else {
                    AppButton("Store as-staked", {
                        val h = vm.antennaMeasuredMetres()
                        if (h == null) vm.message = "Enter the antenna height" else {
                            val err = vm.startMeasure(MeasureSpec(null, "STAKED", MeasureMethod.TOPO, null, h, vm.antennaMethod, "Line ${line.name}"))
                            if (err != null) vm.message = err
                        }
                    }, enabled = live != null && vm.antennaError == null)
                }
            }
        },
        right = { m -> PlanView(job.points, vm.featureCodes, live?.ground?.let { it.e to it.n }, tgt, m, job.lines) }
    )
}

@Composable
private fun AddLineDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val job = vm.job ?: return
    var name by remember { mutableStateOf("Line ${job.lines.size + 1}") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("0") }
    var useZ by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New line") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Line name", name, { name = it })
                PointField(vm, "Start point", from) { from = it }
                PointField(vm, "End point", to) { to = it }
                NumField("Start station", start, { start = it }, suffix = vm.unit.abbr())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use point elevations for cut/fill", Modifier.weight(1f))
                    Switch(checked = useZ, onCheckedChange = { useZ = it })
                }
                error?.let { InfoText(it, color = LocalAppTheme.current.c(ColorRole.BAD)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = job.find(from.trim()); val b = job.find(to.trim()); val s = parseNum(start)
                when {
                    name.isBlank() -> error = "Enter a line name"
                    job.lines.any { it.name == name.trim() } -> error = "A line with this name already exists"
                    a == null || b == null -> error = "Both points must exist in the job"
                    a.name == b.name || (a.e == b.e && a.n == b.n) -> error = "The two points must be different"
                    s == null -> error = "Enter a start station"
                    else -> { vm.addLine(DesignLine(name.trim(), a.e, a.n, if (useZ) a.z else null, b.e, b.n, if (useZ) b.z else null, s)); onDismiss() }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ review

@Composable
fun ReviewScreen(vm: AppViewModel) {
    val job = vm.job
    var q by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<String?>(null) }
    ScreenFrame(vm, label("tile.review_job")) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        val rev = vm.pointsRev
        val list = remember(q, rev) { job.points.asReversed().filter { q.isBlank() || it.name.contains(q, true) || it.code.contains(q, true) } }
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("Search name or code", q, { q = it })
            InfoText("${job.points.size} point(s), ${job.lines.size} line(s). Newest first.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.name }) { p ->
                    Panel(onClick = { detail = p.name }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                InfoText("${p.code}  ${p.kind.label} / ${p.method.label}")
                                p.solution?.let { s -> Text(s.label, color = LocalAppTheme.current.solutionColor(s), style = MaterialTheme.typography.labelMedium) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("N ${vm.fmtDist(p.n)}", fontFamily = FontFamily.Monospace)
                                Text("E ${vm.fmtDist(p.e)}", fontFamily = FontFamily.Monospace)
                                Text("Z ${vm.fmtDist(p.z)}", fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { n -> vm.job?.find(n)?.let { PointDetailDialog(vm, it) { detail = null } } }
}

@Composable
private fun PointDetailDialog(vm: AppViewModel, p: SurveyPoint, onDismiss: () -> Unit) {
    var code by remember(p.name) { mutableStateOf(p.code) }
    var note by remember(p.name) { mutableStateOf(p.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val u = " " + vm.unit.abbr()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyValue("Type", "${p.kind.label} / ${p.method.label}")
                KeyValue(label("field.northing"), vm.fmtDist(p.n) + u, mono = true)
                KeyValue(label("field.easting"), vm.fmtDist(p.e) + u, mono = true)
                KeyValue(label("field.elevation"), vm.fmtDist(p.z) + u, mono = true)
                p.latDeg?.let { KeyValue(label("field.lat"), fmtNum(it, 9) + "°", mono = true) }
                p.lonDeg?.let { KeyValue(label("field.lon"), fmtNum(it, 9) + "°", mono = true) }
                p.ellipsoidalHeight?.let { KeyValue(label("field.ellh"), fmtNum(vm.unit.fromMeters(it), 3) + u, mono = true) }
                p.solution?.let { KeyValue("Solution", it.label, LocalAppTheme.current.solutionColor(it)) }
                if (p.epochs > 0) KeyValue("Epochs", p.epochs.toString())
                p.satellites?.let { KeyValue("Satellites (min)", it.toString()) }
                p.hdop?.let { KeyValue("HDOP", fmtNum(it, 1)) }
                p.pdop?.let { KeyValue("PDOP", fmtNum(it, 1)) }
                p.sigmaH?.let { KeyValue("Reported precision H", fmtNum(vm.unit.fromMeters(it), 3) + u) }
                p.sigmaV?.let { KeyValue("Reported precision V", fmtNum(vm.unit.fromMeters(it), 3) + u) }
                p.sdH?.let { KeyValue("Repeatability H (1σ)", fmtNum(vm.unit.fromMeters(it), 3) + u) }
                p.sdV?.let { KeyValue("Repeatability V (1σ)", fmtNum(vm.unit.fromMeters(it), 3) + u) }
                p.antennaHeight?.let { KeyValue("ARP height", fmtNum(vm.unit.fromMeters(it), 3) + u) }
                if (p.timestampMillis > 0) KeyValue("Time", fmtDateTime(p.timestampMillis))
                if (p.deviceId.isNotEmpty()) KeyValue("Receiver", p.deviceId)
                TextInput(label("point.code"), code, { code = it })
                TextInput("Note", note, { note = it })
            }
        },
        confirmButton = { TextButton(onClick = { vm.updatePoint(p.copy(code = code.trim(), note = note)); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
    if (confirmDelete) ConfirmDialog("Delete point?", "Delete ${p.name} from this job?", "Delete", { vm.deletePoint(p.name); onDismiss() }, { confirmDelete = false })
}

// ------------------------------------------------------------------ cogo

@Composable
fun CogoScreen(vm: AppViewModel) {
    val job = vm.job
    var tab by remember { mutableIntStateOf(0) }
    ScreenFrame(vm, label("tile.cogo")) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        @Suppress("UNUSED_VARIABLE") val rev = vm.pointsRev
        ScrollBody {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Inverse", "Point by azimuth", "Intersection").forEachIndexed { i, title ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(title) })
                }
            }
            when (tab) {
                0 -> CogoInverse(vm)
                1 -> CogoPointByAz(vm)
                else -> CogoIntersection(vm)
            }
        }
    }
}

@Composable
private fun CogoInverse(vm: AppViewModel) {
    val job = vm.job ?: return
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    PointField(vm, "From point", a) { a = it }
    PointField(vm, "To point", b) { b = it }
    val pa = job.find(a.trim()); val pb = job.find(b.trim())
    if (pa != null && pb != null) {
        val i = Cogo.inverse(pa.e, pa.n, pa.z, pb.e, pb.n, pb.z)
        val u = " " + vm.unit.abbr()
        Panel {
            KeyValue("Azimuth", vm.fmtAngle(i.azimuthDeg))
            KeyValue("Back azimuth", vm.fmtAngle(i.azimuthDeg + 180.0))
            KeyValue("Horizontal distance", vm.fmtDist(i.horizontalDistance) + u, mono = true)
            KeyValue("Δ Easting", vm.fmtDist(i.deltaE) + u, mono = true)
            KeyValue("Δ Northing", vm.fmtDist(i.deltaN) + u, mono = true)
            KeyValue("Δ Elevation", vm.fmtDist(i.deltaZ) + u, mono = true)
            KeyValue("Slope distance", vm.fmtDist(i.slopeDistance) + u, mono = true)
            KeyValue("Grade", i.gradePercent?.let { fmtNum(it, 2) + " %" } ?: "-")
        }
    } else if (a.isNotBlank() || b.isNotBlank()) InfoText("Enter two point names that exist in the job.")
}

@Composable
private fun CogoPointByAz(vm: AppViewModel) {
    val job = vm.job ?: return
    var from by remember { mutableStateOf("") }
    var az by remember { mutableStateOf("") }
    var dist by remember { mutableStateOf("") }
    var name by remember(vm.pointsRev) { mutableStateOf(job.freeName()) }
    val p = job.find(from.trim())
    val azv = Angles.parse(az); val d = parseNum(dist)
    PointField(vm, "From point", from) { from = it }
    TextInput("Azimuth (decimal degrees, or deg min sec)", az, { az = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Text)
    NumField("Horizontal distance", dist, { dist = it }, suffix = vm.unit.abbr())
    TextInput("New point name", name, { name = it })
    if (p != null && azv != null && d != null) {
        val r = Cogo.forward(p.e, p.n, azv, d)
        Panel {
            KeyValue(label("field.northing"), vm.fmtDist(r.second), mono = true)
            KeyValue(label("field.easting"), vm.fmtDist(r.first), mono = true)
            KeyValue(label("field.elevation"), vm.fmtDist(p.z), mono = true)
            ActionBar {
                AppButton("Store point", {
                    val err = vm.keyIn(name.trim().ifEmpty { null }, "COGO", r.first, r.second, p.z, PointKind.KEYED_IN, "From ${p.name}")
                    vm.message = err ?: "Stored"
                })
            }
        }
    }
}

@Composable
private fun CogoIntersection(vm: AppViewModel) {
    val job = vm.job ?: return
    var p1 by remember { mutableStateOf("") }
    var a1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var a2 by remember { mutableStateOf("") }
    var name by remember(vm.pointsRev) { mutableStateOf(job.freeName()) }
    PointField(vm, "Point 1", p1) { p1 = it }
    TextInput("Azimuth from point 1", a1, { a1 = it })
    PointField(vm, "Point 2", p2) { p2 = it }
    TextInput("Azimuth from point 2", a2, { a2 = it })
    TextInput("New point name", name, { name = it })
    val q1 = job.find(p1.trim()); val q2 = job.find(p2.trim()); val z1 = Angles.parse(a1); val z2 = Angles.parse(a2)
    if (q1 != null && q2 != null && z1 != null && z2 != null) {
        val r = Cogo.bearingBearing(q1.e, q1.n, z1, q2.e, q2.n, z2)
        if (r == null) InfoText("The two lines are parallel, so there is no intersection.", color = LocalAppTheme.current.c(ColorRole.BAD))
        else Panel {
            KeyValue(label("field.northing"), vm.fmtDist(r.second), mono = true)
            KeyValue(label("field.easting"), vm.fmtDist(r.first), mono = true)
            ActionBar {
                AppButton("Store point", {
                    val err = vm.keyIn(name.trim().ifEmpty { null }, "COGO", r.first, r.second, (q1.z + q2.z) / 2, PointKind.KEYED_IN, "Intersection ${q1.name}/${q2.name}")
                    vm.message = err ?: "Stored"
                })
            }
        }
    }
}

// ------------------------------------------------------------------ calibration

private data class CalPair(val gnss: String, val control: String, val h: Boolean = true, val v: Boolean = true)

@Composable
fun CalibrationScreen(vm: AppViewModel) {
    val job = vm.job
    ScreenFrame(vm, label("tile.calibration")) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        @Suppress("UNUSED_VARIABLE") val rev = vm.pointsRev
        val t = LocalAppTheme.current
        val pairs = remember { mutableStateListOf<CalPair>() }
        var gnss by remember { mutableStateOf("") }
        var control by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(VerticalAdjustment.CONSTANT) }
        var report by remember { mutableStateOf<Pair<Localization, CalibrationReport>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var confirmApply by remember { mutableStateOf(false) }
        val cs = job.coordinateSystem
        val u = " " + vm.unit.abbr()

        ScrollBody {
            Panel {
                SectionTitle("Site calibration")
                InfoText(
                    "Fit the GNSS grid to known local control. First measure each control point with GNSS (Observed control), and key in or import " +
                        "its known local coordinates as a second point. Then pair them here. Two points fit rotation, scale and shift; three or more also give " +
                        "residuals so you can see how well the control agrees."
                )
                if (cs.projection == null) InfoText("This job uses latitude/longitude, which has no grid to calibrate. Choose a projected coordinate system first.", color = t.c(ColorRole.BAD))
                if (cs.localization != null) {
                    val l = cs.localization!!
                    KeyValue("Current calibration", "Scale ${fmtNum(l.scale, 8)}, rotation ${vm.fmtAngle(l.rotationDeg)}")
                    ActionBar { AppButton("Remove calibration", { vm.applyCoordinateSystem(cs.withLocalization(null)); vm.message = "Calibration removed; measured points recomputed" }, primary = false) }
                }
            }
            Panel {
                SectionTitle("Control pairs")
                PointField(vm, "GNSS-measured point", gnss) { gnss = it }
                PointField(vm, "Known local control point", control) { control = it }
                ActionBar {
                    AppButton("Add pair", {
                        val g = job.find(gnss.trim()); val c = job.find(control.trim())
                        when {
                            g == null || c == null -> error = "Both points must exist in the job"
                            g.latDeg == null || g.lonDeg == null || g.ellipsoidalHeight == null -> error = "${g.name} has no GNSS position. Choose a point that was measured with the receiver."
                            g.name == c.name -> error = "Choose two different points"
                            else -> { pairs.add(CalPair(g.name, c.name)); gnss = ""; control = ""; error = null; report = null }
                        }
                    })
                }
                pairs.forEachIndexed { i, pr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${pr.gnss} → ${pr.control}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("H"); Switch(checked = pr.h, onCheckedChange = { pairs[i] = pr.copy(h = it); report = null })
                        Text("V"); Switch(checked = pr.v, onCheckedChange = { pairs[i] = pr.copy(v = it); report = null })
                        IconButton(onClick = { pairs.removeAt(i); report = null }) { Icon(Icons.Default.Delete, "Remove pair") }
                    }
                }
                Dropdown("Vertical adjustment", VerticalAdjustment.entries.toList(), mode, { it.label }, { mode = it; report = null })
                error?.let { InfoText(it, color = t.c(ColorRole.BAD)) }
                ActionBar {
                    AppButton("Compute", {
                        error = null
                        try {
                            val cp = pairs.map { pr ->
                                val g = job.find(pr.gnss) ?: throw IllegalArgumentException("${pr.gnss} is no longer in the job")
                                val c = job.find(pr.control) ?: throw IllegalArgumentException("${pr.control} is no longer in the job")
                                val ll = LatLonH(
                                    g.latDeg ?: throw IllegalArgumentException("${g.name} has no GNSS position"),
                                    g.lonDeg ?: throw IllegalArgumentException("${g.name} has no GNSS position"),
                                    g.ellipsoidalHeight ?: throw IllegalArgumentException("${g.name} has no GNSS height")
                                )
                                val grid = cs.toProjectedMetres(ll)    // grid coordinates with any previous calibration removed
                                ControlPair("${pr.gnss}→${pr.control}", grid.e, grid.n, grid.z, cs.unit.toMeters(c.e), cs.unit.toMeters(c.n), cs.unit.toMeters(c.z), pr.h, pr.v)
                            }
                            report = Localization.fit(cp, mode)
                        } catch (e: IllegalArgumentException) { report = null; error = e.message }
                    }, enabled = pairs.size >= 2 && cs.projection != null)
                }
            }
            report?.let { (loc, r) ->
                Panel(borderColor = t.c(ColorRole.PRIMARY)) {
                    SectionTitle("Result")
                    KeyValue("Scale", fmtNum(r.scale, 8) + "  (" + fmtNum((r.scale - 1) * 1e6, 2) + " ppm)", mono = true)
                    KeyValue("Rotation", vm.fmtAngle(r.rotationDeg))
                    KeyValue("RMS horizontal", fmtNum(vm.unit.fromMeters(r.rmsH), 4) + u, mono = true)
                    KeyValue("RMS vertical", fmtNum(vm.unit.fromMeters(r.rmsV), 4) + u, mono = true)
                    SectionTitle("Residuals (control minus fitted)")
                    r.residuals.forEach { res ->
                        KeyValue(res.name, "E ${fmtNum(vm.unit.fromMeters(res.dE), 3)}  N ${fmtNum(vm.unit.fromMeters(res.dN), 3)}  Z ${fmtNum(vm.unit.fromMeters(res.dZ), 3)}", mono = true)
                    }
                    if (pairs.size == 2) InfoText("With only two points there is no redundancy, so residuals are zero by construction. Add a third point to check the fit.")
                    ActionBar { AppButton("Apply to job", { confirmApply = true }) }
                    if (confirmApply) ConfirmDialog(
                        "Apply calibration?",
                        "Every GNSS-measured point in this job is recomputed with the new calibration. Typed-in and imported points keep their numbers.",
                        "Apply", { vm.applyCoordinateSystem(cs.withLocalization(loc)); vm.message = "Calibration applied"; pairs.clear(); report = null }, { confirmApply = false }
                    )
                }
            }
        }
    }
}
