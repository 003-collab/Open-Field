@file:OptIn(ExperimentalLayoutApi::class)

package com.openfield.survey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openfield.core.config.ColorRole
import com.openfield.core.config.CoordinateOrder
import com.openfield.core.config.Feature
import com.openfield.core.config.Field
import com.openfield.core.config.UserConfig
import com.openfield.core.device.HeightMeasurement
import com.openfield.core.geo.CoordinateSystem
import com.openfield.core.geo.LinearUnit
import com.openfield.core.geo.TransverseMercator
import com.openfield.core.gnss.ConnectionState
import com.openfield.core.survey.MeasureMethod
import com.openfield.core.survey.MeasureSpec
import com.openfield.core.survey.Stakeout
import com.openfield.core.survey.SurveyPoint
import com.openfield.survey.AppViewModel
import com.openfield.survey.abbr
import com.openfield.survey.fmtDateTime
import com.openfield.survey.fmtNum
import com.openfield.survey.fmtNumOrDash
import com.openfield.survey.fmtUtc
import com.openfield.survey.parseNum
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2

// ------------------------------------------------------------------ shared survey widgets

/** One place that maps features to icons, so a missing icon in a future Compose version is a one-line fix. */
fun featureIcon(id: String): ImageVector = when (id) {
    Feature.JOBS -> Icons.Default.Folder
    Feature.MAP -> Icons.Default.Map
    Feature.MEASURE_TOPO -> Icons.Default.MyLocation
    Feature.MEASURE_CODES -> Icons.Default.Edit
    Feature.OBSERVED_CONTROL -> Icons.Default.Flag
    Feature.CONTINUOUS_TOPO -> Icons.Default.Timeline
    Feature.STAKE_POINTS -> Icons.Default.Place
    Feature.STAKE_LINES -> Icons.Default.Straighten
    Feature.COGO -> Icons.Default.Calculate
    Feature.CALIBRATION -> Icons.Default.Tune
    Feature.REVIEW_JOB -> Icons.AutoMirrored.Filled.List
    Feature.IMPORT_EXPORT -> Icons.Default.Share
    Feature.GNSS_STATUS -> Icons.Default.GpsFixed
    Feature.EQUIPMENT -> Icons.Default.Bluetooth
    Feature.REALTIME -> Icons.Default.Cloud
    Feature.SURVEY_STYLES -> Icons.Default.Build
    Feature.COORDINATE_SYSTEMS -> Icons.Default.Terrain
    Feature.FEATURE_CODES -> Icons.Default.Code
    Feature.APPEARANCE -> Icons.Default.Palette
    else -> Icons.Default.Settings
}

/** Puts Northing/Easting in the order the user prefers, wherever they sit in the field list. */
fun orderedPositionFields(cfg: UserConfig): List<String> {
    val f = cfg.layout.positionFields.toMutableList()
    val n = f.indexOf(Field.NORTHING); val e = f.indexOf(Field.EASTING)
    if (n >= 0 && e >= 0) {
        val wantNorthFirst = cfg.coordinateOrder == CoordinateOrder.NORTH_EAST
        if ((wantNorthFirst && e < n) || (!wantNorthFirst && n < e)) { f[n] = Field.EASTING; f[e] = Field.NORTHING }
    }
    return f
}

@Composable
fun AntennaEditor(vm: AppViewModel) {
    val t = LocalAppTheme.current
    LaunchedEffect(vm.antennaHeightText, vm.antennaMethod, vm.activeDevice, vm.job?.name) { vm.applyAntenna() }
    Panel {
        SectionTitle(label("point.antennaHeight"))
        NumField(label("point.antennaHeight"), vm.antennaHeightText, { vm.antennaHeightText = it }, suffix = vm.unit.abbr(), error = vm.antennaError != null)
        Dropdown(label("point.measuredTo"), HeightMeasurement.entries.toList(), vm.antennaMethod, { it.label }, { vm.antennaMethod = it })
        vm.antennaError?.let { InfoText(it, color = t.c(ColorRole.BAD)) }
        val a = vm.activeDevice.antenna
        InfoText("Antenna: ${a.model}. Offsets come from the receiver profile (Equipment).")
    }
}

/** Live position in the job's coordinates, using the fields the user chose in Appearance & layout. */
@Composable
fun PositionPanel(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val live = vm.live
    val cs = vm.job?.coordinateSystem
    Panel {
        if (live == null) {
            Text(
                if (vm.connection == ConnectionState.CONNECTED) "Waiting for a position…" else "Receiver not connected. Use Connect on the home screen.",
                style = MaterialTheme.typography.bodyLarge
            )
            return@Panel
        }
        val s = live.snapshot
        val projected = cs?.projection != null
        val u = if (projected) " " + vm.unit.abbr() else ""
        val dec = if (projected) vm.config.distanceDecimals else 8
        for (f in orderedPositionFields(vm.config)) {
            val v: String = when (f) {
                Field.NORTHING -> fmtNum(live.ground.n, dec) + u
                Field.EASTING -> fmtNum(live.ground.e, dec) + u
                Field.ELEVATION -> fmtNum(live.antenna.z, vm.config.distanceDecimals) + " " + vm.unit.abbr()
                Field.GROUND_ELEVATION -> fmtNum(live.ground.z, vm.config.distanceDecimals) + " " + vm.unit.abbr()
                Field.LATITUDE -> fmtNum(s.latDeg, 9) + "°"
                Field.LONGITUDE -> fmtNum(s.lonDeg, 9) + "°"
                Field.ELLIPSOIDAL_HEIGHT -> fmtNum(vm.unit.fromMeters(s.ellipsoidalHeight), vm.config.distanceDecimals) + " " + vm.unit.abbr()
                Field.SPEED -> fmtNumOrDash(s.speedMps, 2) + " m/s"
                Field.COURSE -> s.courseDeg?.let { vm.fmtAngle(it) } ?: "-"
                Field.TIME -> fmtUtc(s.utcSeconds)
                Field.ANTENNA_HEIGHT -> vm.antennaHeightText + " " + vm.unit.abbr()
                else -> ""
            }
            BigValue(label("field.$f"), v)
        }
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.solution.label, color = t.solutionColor(s.solution), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("${s.satellitesUsed} SVs   HDOP ${fmtNumOrDash(s.hdop, 1)}", style = MaterialTheme.typography.bodyMedium)
        }
        if (live.gateFailures.isEmpty()) {
            InfoText("Meets the quality limits of this survey style", color = t.c(ColorRole.GOOD))
        } else {
            live.gateFailures.forEach { InfoText("• $it", color = t.c(ColorRole.BAD)) }
        }
    }
}

@Composable
fun MeasureProgressPanel(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val p = vm.progress ?: return
    Panel(borderColor = t.c(ColorRole.PRIMARY)) {
        Text("Measuring… ${p.accepted} of ${p.required} epochs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { if (p.required == 0) 0f else p.accepted.toFloat() / p.required }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (p.rejected > 0) InfoText("${p.rejected} epoch(s) rejected so far")
        p.lastFailures.forEach { InfoText("• $it (waiting for better quality)", color = t.c(ColorRole.WARNING)) }
    }
}

@Composable
fun PointPickerDialog(vm: AppViewModel, onPick: (SurveyPoint) -> Unit, onDismiss: () -> Unit) {
    val job = vm.job ?: return
    var q by remember { mutableStateOf("") }
    val rev = vm.pointsRev
    val list = remember(q, rev) { job.points.filter { q.isBlank() || it.name.contains(q, true) || it.code.contains(q, true) }.take(200) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select point") },
        text = {
            Column {
                TextInput("Search name or code", q, { q = it })
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(list, key = { it.name }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p); onDismiss() }.padding(vertical = 12.dp)) {
                            Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(p.code, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun PointField(vm: AppViewModel, label: String, value: String, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        IconButton(onClick = { picking = true }) { Icon(Icons.Default.Search, contentDescription = "Pick point") }
    }
    if (picking) PointPickerDialog(vm, onPick = { onChange(it.name) }, onDismiss = { picking = false })
}

/** Arrow plus forward/right/cut-fill read-outs toward a target position (job coordinates, job units). */
@Composable
fun GuidancePanel(vm: AppViewModel, tgtE: Double, tgtN: Double, tgtZ: Double?) {
    val t = LocalAppTheme.current
    val live = vm.live
    if (live == null) { Panel { Text("Waiting for a position…") }; return }
    val unit = vm.unit
    val style = vm.activeStyle
    val tol = unit.fromMeters(style.stakeoutTolerance)
    val g = Stakeout.toPoint(
        live.ground.e, live.ground.n, live.ground.z, tgtE, tgtN, tgtZ ?: live.ground.z,
        style.stakeoutOrientation, vm.headingDeg, tol
    )
    val angle = Math.toDegrees(atan2(g.right, g.forward)).toFloat()
    val u = " " + unit.abbr()
    val good = t.c(ColorRole.GOOD)
    val col = if (g.withinTolerance) good else null
    Panel(borderColor = if (g.withinTolerance) good else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GuidanceArrow(angle, g.withinTolerance, Modifier.size(140.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BigValue("Distance", vm.fmtDist(g.distance) + u, color = col)
                BigValue(if (g.forward >= 0) label("stake.goForward") else label("stake.goBack"), vm.fmtDist(abs(g.forward)) + u, color = col)
                BigValue(if (g.right >= 0) label("stake.goRight") else label("stake.goLeft"), vm.fmtDist(abs(g.right)) + u, color = col)
                if (tgtZ != null) BigValue(if (g.deltaZ >= 0) label("stake.cut") else label("stake.fill"), vm.fmtDist(abs(g.deltaZ)) + u, color = col)
            }
        }
        KeyValue("Azimuth to point", vm.fmtAngle(g.azimuthToTargetDeg))
        InfoText("Orientation: ${style.stakeoutOrientation.label}. Tolerance ${vm.fmtDist(tol)}$u.")
        if (style.stakeoutOrientation == com.openfield.core.survey.StakeoutOrientation.TRAVEL_DIRECTION && vm.headingDeg == null) {
            InfoText("Walk a few steps so the receiver can work out your direction of travel.", color = t.c(ColorRole.WARNING))
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val layout = vm.config.layout
    val tiles = layout.visibleTiles()
    Column(Modifier.fillMaxSize().background(t.c(ColorRole.BACKGROUND))) {
        Row(
            Modifier.fillMaxWidth().background(t.c(ColorRole.HEADER)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("OpenField", color = t.c(ColorRole.ON_HEADER), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(vm.job?.name ?: "No job open", color = t.c(ColorRole.ON_HEADER).copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            }
            val connected = vm.connection == ConnectionState.CONNECTED || vm.connection == ConnectionState.CONNECTING
            AppButton(
                if (connected) label("action.disconnect") else label("action.connect"),
                onClick = { if (connected) vm.disconnect() else vm.connect() },
                color = if (connected) t.c(ColorRole.BAD) else t.c(ColorRole.ACCENT)
            )
        }
        StatusStrip(vm)
        if (vm.loadErrors.isNotEmpty()) {
            Panel(Modifier.padding(12.dp), borderColor = t.c(ColorRole.WARNING)) {
                Text("Some configuration files could not be loaded:", fontWeight = FontWeight.Bold)
                vm.loadErrors.take(4).forEach { InfoText(it) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.homeColumns),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).navigationBarsPadding()
        ) {
            items(tiles, key = { it.id }) { tile ->
                val shape = RoundedCornerShape(t.cornerRadiusDp.dp)
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp * LocalTouch.current).clickable { vm.go(tile.id) },
                    color = t.c(ColorRole.SURFACE), contentColor = t.c(ColorRole.ON_SURFACE), shape = shape,
                    border = androidx.compose.foundation.BorderStroke(if (tile.favorite) 2.dp else 1.dp, if (tile.favorite) t.c(ColorRole.ACCENT) else t.c(ColorRole.OUTLINE))
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                        ) {
                            Icon(featureIcon(tile.id), contentDescription = null, tint = t.c(ColorRole.PRIMARY), modifier = Modifier.size(36.dp * LocalTouch.current))
                            Text(label("tile.${tile.id}"), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        }
                        if (tile.favorite) Icon(Icons.Default.Star, null, tint = t.c(ColorRole.ACCENT), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ jobs

@Composable
fun JobsScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    var deleting by remember { mutableStateOf<File?>(null) }
    val rev = vm.pointsRev
    ScreenFrame(vm, label("tile.jobs"), actions = {
        IconButton(onClick = { vm.beginNewJob(); vm.go("job_new") }) { Icon(Icons.Default.Add, "New job", tint = t.c(ColorRole.ON_HEADER)) }
    }) {
        ScrollBody {
            val current = vm.job
            if (current != null) {
                Panel(borderColor = t.c(ColorRole.PRIMARY)) {
                    SectionTitle("Current job")
                    Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    KeyValue("Points", current.points.size.toString())
                    KeyValue("Coordinate system", current.coordinateSystem.name)
                    KeyValue("Units", current.coordinateSystem.unit.label)
                    KeyValue("Geoid", current.coordinateSystem.geoid.name)
                    if (current.coordinateSystem.localization != null) KeyValue("Site calibration", "Applied")
                    ActionBar { AppButton("Close job", { vm.closeJob() }, primary = false) }
                }
            }
            AppButton("New job", { vm.beginNewJob(); vm.go("job_new") }, Modifier.fillMaxWidth())
            SectionTitle("Saved jobs")
            val files = remember(rev) { vm.store.listJobs() }
            if (files.isEmpty()) InfoText("No jobs yet. Create one to start collecting points.")
            files.forEach { f ->
                Panel(onClick = {
                    try { vm.openJob(f) } catch (e: Exception) { vm.message = "Could not open ${f.name}: ${e.message}" }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            InfoText("${fmtDateTime(f.lastModified())}   ${(f.length() + 512) / 1024} KB")
                        }
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Default.Delete, "Delete job") }
                    }
                }
            }
        }
    }
    deleting?.let { f ->
        ConfirmDialog("Delete job?", "'${f.nameWithoutExtension}' and all of its points will be deleted from this device.", "Delete", { vm.deleteJob(f) }, { deleting = null })
    }
}

// ------------------------------------------------------------------ measure

/** Measure topo, Measure codes and Observed control share one screen; [mode] picks the defaults. A map sits beside the form (split screen). */
@Composable
fun MeasureScreen(vm: AppViewModel, mode: String) {
    val job = vm.job
    val t = LocalAppTheme.current
    val rev = vm.pointsRev
    val style = vm.activeStyle
    var control by remember(mode) { mutableStateOf(mode == Feature.OBSERVED_CONTROL) }
    var name by remember(rev, job?.name) { mutableStateOf(job?.freeName() ?: "") }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var epochsText by remember(control, style.id) { mutableStateOf((if (control) style.controlEpochs else style.topoEpochs).toString()) }
    var showOptions by remember { mutableStateOf(false) }
    val measuring = vm.progress != null
    val taken = job != null && name.isNotBlank() && job.has(name)

    fun measureNow() {
        val h = vm.antennaMeasuredMetres()
        if (h == null) { vm.message = "Enter the antenna height"; return }
        val err = vm.startMeasure(
            MeasureSpec(
                name = name.trim().ifEmpty { null }, code = code.trim(),
                method = if (control) MeasureMethod.OBSERVED_CONTROL else MeasureMethod.TOPO,
                epochs = epochsText.toIntOrNull()?.coerceAtLeast(1),
                antennaHeightMeasured = h, heightMethod = vm.antennaMethod, note = note
            )
        )
        if (err != null) vm.message = err
    }

    ScreenFrame(
        vm, label("tile.$mode"), actions = { MapModeButtons(vm) },
        softKeys = listOf(
            SoftKey("Esc", { if (measuring) vm.cancelMeasure() else vm.back() }),
            SoftKey("GNSS", { vm.go(Feature.GNSS_STATUS) }),
            if (measuring) SoftKey("Store now", { vm.finishMeasureEarly() }) else SoftKey("Options", { showOptions = true }, enabled = job != null),
            SoftKey(if (measuring) "Measuring" else label("action.measure"), { measureNow() }, enabled = job != null && !measuring && vm.live != null && !taken && vm.antennaError == null, primary = true)
        )
    ) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        TwoPane(
            vm,
            left = {
                FormHeader(label("tile.$mode"), vm.isFavorite(mode)) { vm.toggleFavorite(mode) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(label("point.name"), name, { name = it }, Modifier.weight(1f), error = taken, enabled = !measuring)
                    TextInput(label("point.code"), code, { code = it }, Modifier.weight(1f), enabled = !measuring)
                }
                if (taken) InfoText("A point with this name already exists", color = t.c(ColorRole.BAD))
                if (mode == Feature.MEASURE_CODES || code.isNotEmpty()) {
                    val matches = vm.featureCodes.let { lib -> if (code.isBlank()) lib.codes else lib.search(code) }.take(24)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        matches.forEach { fc ->
                            FilterChip(selected = fc.code.equals(code, true), onClick = { code = fc.code }, label = { Text(fc.code) })
                        }
                    }
                }
                Dropdown("Method", listOf(false, true), control, { if (it) "Observed control point" else "Topo point" }, { control = it })
                AntennaEditor(vm)
                MeasureProgressPanel(vm)
                PositionPanel(vm)
            },
            right = { m ->
                PlanView(job.points, vm.featureCodes, vm.live?.ground?.let { it.e to it.n }, null, m, job.lines)
            }
        )
    }
    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false }, title = { Text("Options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Epochs to average", epochsText, { epochsText = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                    TextInput("Note (optional)", note, { note = it })
                    InfoText("Quality limits (solution, HDOP, precision) come from the survey style: ${style.name}.")
                }
            },
            confirmButton = { TextButton(onClick = { showOptions = false }) { Text("OK") } }
        )
    }
}

@Composable
fun ContinuousScreen(vm: AppViewModel) {
    val job = vm.job
    @Suppress("UNUSED_VARIABLE") val rev = vm.pointsRev   // reading it subscribes this screen to point changes
    val style = vm.activeStyle
    var code by remember { mutableStateOf("") }
    val t = LocalAppTheme.current
    fun start() { val err = vm.startContinuous(code.trim()); if (err != null) vm.message = err }
    ScreenFrame(
        vm, label("tile.continuous_topo"), actions = { MapModeButtons(vm) },
        softKeys = listOf(
            SoftKey("Esc", { if (vm.continuousOn) vm.stopContinuous() else vm.back() }),
            SoftKey("GNSS", { vm.go(Feature.GNSS_STATUS) }),
            if (vm.continuousOn) SoftKey("Stop", { vm.stopContinuous() }, primary = true)
            else SoftKey("Start", { start() }, enabled = job != null && vm.live != null && vm.antennaError == null, primary = true)
        )
    ) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        TwoPane(
            vm,
            left = {
                FormHeader(label("tile.continuous_topo"), vm.isFavorite(Feature.CONTINUOUS_TOPO)) { vm.toggleFavorite(Feature.CONTINUOUS_TOPO) }
                TextInput(label("point.code"), code, { code = it }, enabled = !vm.continuousOn)
                val unitText = if (style.continuousMode == com.openfield.core.survey.ContinuousMode.TIME) "every ${style.continuousInterval} s" else "every ${style.continuousInterval} ${vm.unit.abbr()}"
                InfoText("Stores a point $unitText while the receiver meets the survey style's quality limits. Change this in Survey styles.")
                if (vm.continuousOn) KeyValue("Points in job", job.points.size.toString())
                AntennaEditor(vm)
                PositionPanel(vm)
            },
            right = { m -> PlanView(job.points, vm.featureCodes, vm.live?.ground?.let { it.e to it.n }, null, m, job.lines) }
        )
    }
}
