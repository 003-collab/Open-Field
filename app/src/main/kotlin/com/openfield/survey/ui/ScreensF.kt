package com.openfield.survey.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openfield.core.config.ColorRole
import com.openfield.core.config.Feature
import com.openfield.core.geo.*
import com.openfield.survey.AppViewModel
import com.openfield.survey.abbr
import com.openfield.survey.parseNum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ------------------------------------------------------------------ map

/** Full-screen map of the job: points, design lines and the receiver position. Tap a point for its details. */
@Composable
fun MapScreen(vm: AppViewModel) {
    val job = vm.job
    @Suppress("UNUSED_VARIABLE") val rev = vm.pointsRev
    ScreenFrame(
        vm, label("tile.map"),
        softKeys = listOf(
            SoftKey("Esc", { vm.back() }),
            SoftKey("Review", { vm.go(Feature.REVIEW_JOB) }, enabled = job != null),
            SoftKey(label("tile.measure_topo"), { vm.go(Feature.MEASURE_TOPO) }, enabled = job != null, primary = true)
        )
    ) {
        if (job == null) { NoJob(vm); return@ScreenFrame }
        PlanView(job.points, vm.featureCodes, vm.live?.ground?.let { it.e to it.n }, null, Modifier.fillMaxSize().padding(6.dp), job.lines)
    }
}

// ------------------------------------------------------------------ new job

@Composable
fun NewJobScreen(vm: AppViewModel) {
    var error by remember { mutableStateOf<String?>(null) }
    val cs = vm.newJobSystem
    ScreenFrame(
        vm, "New job", showStatus = false,
        softKeys = listOf(
            SoftKey("Esc", { vm.back() }),
            SoftKey("Create", {
                val err = vm.createJob(vm.newJobName, vm.newJobSystem, vm.newJobDescription)
                if (err == null) vm.back() else error = err
            }, primary = true)
        )
    ) {
        ScrollBody {
            TextInput("Job name", vm.newJobName, { vm.newJobName = it; error = null }, error = error != null)
            error?.let { InfoText(it, color = LocalAppTheme.current.c(ColorRole.BAD)) }
            TextInput("Description (optional)", vm.newJobDescription, { vm.newJobDescription = it })
            Panel(onClick = { vm.go("coord_library:newjob") }) {
                SectionTitle("Coordinate system")
                Text(cs.name, style = MaterialTheme.typography.titleMedium)
                InfoText("Datum ${cs.datum.name}, ${cs.unit.label}, geoid: ${cs.geoid.name}${if (cs.ground != null) ", ground coordinates" else ""}")
                InfoText("Tap to select from the library or change the geoid and ground settings.")
            }
            Dropdown("Distance units", LinearUnit.entries.toList(), cs.unit, { it.label }, { vm.newJobSystem = cs.copy(unit = it) })
        }
    }
}

// ------------------------------------------------------------------ coordinate system library

private const val CONSTANT_GEOID = "Constant separation"
private enum class CoordMode(val label: String) { GRID("Grid"), GROUND("Ground") }

/** Route wrapper: "coord_library:newjob" picks the system for the New job screen, "coord_library:job" changes the open job's system. */
@Composable
fun CoordLibraryScreen(vm: AppViewModel, target: String) {
    var pending by remember { mutableStateOf<CoordinateSystem?>(null) }
    val forNewJob = target == "newjob"
    CoordSystemPicker(
        vm, if (forNewJob) vm.newJobSystem else vm.job?.coordinateSystem, "Select coordinate system", "Accept",
        onAccept = { cs ->
            if (forNewJob) { vm.newJobSystem = cs; vm.back() }
            else if (vm.job == null) vm.message = "Open a job first"
            else pending = cs
        },
        onCancel = { vm.back() }
    )
    pending?.let { cs ->
        val calibrated = vm.job?.coordinateSystem?.localization != null
        ConfirmDialog(
            "Change coordinate system?",
            "Points measured with GNSS are recomputed in '${cs.name}'. Typed-in and imported points keep their numbers, so check them against the new system." +
                if (calibrated) " The site calibration of this job is removed." else "",
            "Apply", { val n = vm.applyCoordinateSystem(cs); vm.message = "Recomputed $n point(s)"; vm.back() }, { pending = null }
        )
    }
}

private fun zoneCentre(zone: LibraryZone): Pair<Double, Double>? = when (val p = zone.projection) {
    is TransverseMercator -> if (kotlin.math.abs(p.lat0Deg) < 0.001) null else p.lat0Deg to p.lon0Deg   // UTM zones start at the equator: not a useful site position
    is LambertConic2SP -> (p.lat1Deg + p.lat2Deg) / 2 to p.lon0Deg
}

private fun initialSelection(vm: AppViewModel, initial: CoordinateSystem?, systems: List<LibrarySystem>): Pair<LibrarySystem, LibraryZone> {
    val fromInitial = systems.firstOrNull { it.name == initial?.librarySystem }?.let { s -> s.zones.firstOrNull { it.name == initial?.libraryZone }?.let { s to it } }
    if (fromInitial != null) return fromInitial
    val utm = systems.firstOrNull { it.name == "UTM" }
    val pos = vm.currentLatLon()
    if (utm != null && pos != null) {
        val z = TransverseMercator.utmZoneFor(pos.first, pos.second)
        val prefix = "Zone $z ${if (pos.first < 0) "South" else "North"} "
        utm.zones.firstOrNull { it.name.startsWith(prefix) }?.let { return utm to it }
    }
    val first = systems.first()
    return first to first.zones.first()
}

/**
 * "Select from library": choose a system and zone from the built-in library, then the geoid model, grid or ground coordinates
 * and distance units. Calls [onAccept] with a complete [CoordinateSystem].
 */
@Composable
fun CoordSystemPicker(
    vm: AppViewModel, initial: CoordinateSystem?, title: String, acceptLabel: String,
    onAccept: (CoordinateSystem) -> Unit, onCancel: () -> Unit
) {
    val t = LocalAppTheme.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val systems = remember { CoordSysLibrary.systems }
    val start = remember { initialSelection(vm, initial, systems) }
    var system by remember { mutableStateOf(start.first) }
    var zone by remember { mutableStateOf(start.second) }
    var unit by remember { mutableStateOf(if (initial != null && initial.libraryZone == start.second.name) initial.unit else start.second.defaultUnit) }
    var useGeoid by remember { mutableStateOf(initial != null && initial.geoid !== NoGeoid) }
    var geoidChoice by remember {
        mutableStateOf(
            when (val g = initial?.geoid) {
                is GridGeoid -> g.name
                is ConstantGeoid -> CONSTANT_GEOID
                else -> CoordSysLibrary.geoids.firstOrNull()?.name ?: CONSTANT_GEOID
            }
        )
    }
    var constText by remember { mutableStateOf((initial?.geoid as? ConstantGeoid)?.value?.toString() ?: "0.0") }
    var mode by remember { mutableStateOf(if (initial?.ground != null) CoordMode.GROUND else CoordMode.GRID) }
    var heightText by remember {
        mutableStateOf(initial?.let { i -> i.ground?.let { g -> String.format(Locale.ROOT, "%.3f", g.projectHeightM / i.unit.metersPerUnit) } } ?: "0.000")
    }
    var scaleText by remember { mutableStateOf("") }
    val pos = remember { vm.currentLatLon() ?: zoneCentre(start.second) }
    var latText by remember { mutableStateOf(pos?.first?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "") }
    var lonText by remember { mutableStateOf(pos?.second?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "") }
    var geoidRev by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val installedCustom = remember(geoidRev) { vm.store.listGeoids() }
    val geoidNames = remember(geoidRev) {
        CoordSysLibrary.geoids.map { it.name } + installedCustom.filter { g -> CoordSysLibrary.geoids.none { vm.store.safeName(it.name) == g } } + CONSTANT_GEOID
    }
    val installed = remember(geoidChoice, geoidRev) { geoidChoice != CONSTANT_GEOID && vm.store.hasGeoid(geoidChoice) }

    fun siteLatLon(): Pair<Double, Double>? {
        val la = parseNum(latText); val lo = parseNum(lonText)
        return if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
    }

    fun geoidModel(): GeoidModel? {
        if (!useGeoid) return NoGeoid
        if (geoidChoice == CONSTANT_GEOID) {
            val v = parseNum(constText)
            if (v == null) { error = "Enter the geoid separation in metres"; return null }
            return ConstantGeoid(v)
        }
        val g = vm.store.geoidResolver(geoidChoice)
        if (g == null) error = "Geoid '$geoidChoice' is not installed. Import its grid file first."
        return g
    }

    fun build(): CoordinateSystem? {
        error = null
        val geoid = geoidModel() ?: return null
        var cs = CoordSysLibrary.build(system, zone, unit, geoid)
        if (mode == CoordMode.GROUND) {
            val ll = siteLatLon()
            if (ll == null) { error = "Enter the project latitude and longitude (degrees) to compute ground coordinates"; return null }
            val h = unit.toMeters(parseNum(heightText) ?: 0.0)
            val sep = try { geoid.separation(ll.first, ll.second) } catch (e: IllegalArgumentException) { error = "The project location is outside the geoid grid"; return null }
            var gs = GroundSpec.at(cs, ll.first, ll.second, h + sep).copy(projectHeightM = h)
            if (scaleText.isNotBlank()) {
                val s = parseNum(scaleText)
                if (s == null || s < 0.9 || s > 1.1) { error = "Scale factor must be a number close to 1"; return null }
                gs = gs.copy(scaleFactor = s)
            }
            cs = cs.copy(ground = gs, name = cs.name + " (ground)")
        }
        return cs
    }

    /** Computed scale for display; null while the inputs are incomplete. */
    val autoScale: Double? = if (mode != CoordMode.GROUND) null else runCatching {
        val ll = siteLatLon() ?: return@runCatching null
        val geoid: GeoidModel = if (!useGeoid) NoGeoid else if (geoidChoice == CONSTANT_GEOID) ConstantGeoid(parseNum(constText) ?: 0.0) else (vm.store.geoidResolver(geoidChoice) ?: NoGeoid)
        val cs = CoordSysLibrary.build(system, zone, unit, geoid)
        GroundSpec.at(cs, ll.first, ll.second, unit.toMeters(parseNum(heightText) ?: 0.0) + geoid.separation(ll.first, ll.second)).scaleFactor
    }.getOrNull()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val entry = geoidChoice
            val centre = siteLatLon() ?: pos
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { importGeoidFile(ctx, vm, uri, entry, centre) } }
                busy = false
                result.onSuccess { vm.message = "Installed $entry ($it)"; geoidRev++ }.onFailure { vm.message = "Geoid grid rejected: ${it.message}" }
            }
        }
    }

    ScreenFrame(
        vm, title, onBack = onCancel, showStatus = false,
        softKeys = listOf(SoftKey("Esc", onCancel), SoftKey(acceptLabel, { build()?.let(onAccept) }, enabled = !busy, primary = true))
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TwoColumn(
                left = {
                    Dropdown("System", systems, system, { it.name }, { s -> system = s; zone = s.zones.first(); unit = zone.defaultUnit })
                    FormValue("Local datum", system.datumLabel + if (system.datum.fromWgs84.isIdentity) " (no shift from WGS 84)" else "")
                    FormValue("Global reference datum", system.refFrame)
                    FormValue("Displacement model", "None")
                    SwitchRow("Use geoid model", useGeoid) { useGeoid = it }
                    FormValue("Use datum grid", "No")
                    if (mode == CoordMode.GROUND) NumField("Project height (${if (useGeoid) "orthometric" else "ellipsoidal"})", heightText, { heightText = it }, suffix = unit.abbr())
                },
                right = {
                    Dropdown("Zone", system.zones, zone, { it.name }, { zone = it; unit = it.defaultUnit })
                    FormValue("Global reference epoch", system.epoch?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-")
                    InfoText(zone.summary)
                    if (useGeoid) {
                        Dropdown("Geoid model", geoidNames, geoidChoice, { it }, { geoidChoice = it })
                        if (geoidChoice == CONSTANT_GEOID) {
                            NumField("Geoid separation N (m)  (H = h − N)", constText, { constText = it })
                        } else if (installed) {
                            InfoText("Installed on this device.", color = t.c(ColorRole.GOOD))
                        } else {
                            InfoText("Not installed. Import the grid file${CoordSysLibrary.geoids.firstOrNull { it.name == geoidChoice }?.let { " (usually ${it.file})" } ?: ""}: NGS/PROJ .gtx or the app's ASCII grid.", color = t.c(ColorRole.WARNING))
                        }
                        if (geoidChoice != CONSTANT_GEOID) {
                            AppButton(if (busy) "Reading grid..." else "Import grid file", { importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy, primary = false)
                            InfoText("Only the area around the project location below is kept, so a continental grid stays small.")
                        }
                    }
                    Dropdown("Coordinates", CoordMode.entries.toList(), mode, { it.label }, { mode = it })
                    Dropdown("Distance units", LinearUnit.entries.toList(), unit, { it.label }, { unit = it })
                }
            )
            if (mode == CoordMode.GROUND || (useGeoid && geoidChoice != CONSTANT_GEOID)) {
                Panel {
                    SectionTitle("Project location")
                    InfoText("Used to choose the part of the geoid grid to keep and to compute the ground scale factor.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Latitude (°)", latText, { latText = it }, Modifier.weight(1f))
                        NumField("Longitude (°)", lonText, { lonText = it }, Modifier.weight(1f))
                    }
                    if (vm.currentLatLon() != null) AppButton("Use current position", {
                        vm.currentLatLon()?.let { latText = String.format(Locale.ROOT, "%.6f", it.first); lonText = String.format(Locale.ROOT, "%.6f", it.second) }
                    }, primary = false)
                    if (mode == CoordMode.GROUND) {
                        autoScale?.let { InfoText("Ground scale factor ${String.format(Locale.ROOT, "%.8f", it)} (grid scale and height combined), applied about the project location. Grid distance × factor = ground distance.") }
                        NumField("Scale factor override (blank = automatic)", scaleText, { scaleText = it })
                    }
                }
            }
            error?.let { InfoText(it, color = t.c(ColorRole.BAD)) }
            InfoText("WGS 84 and NAD83(2011) are treated as the same frame, and no plate-motion model is applied. Heights are orthometric only when a geoid model is used.")
        }
    }
}

/** Reads a geoid grid file (NGS/PROJ .gtx, cropped around [centre], or the app's ASCII grid) and stores it under [name]. Returns a coverage description. */
private fun importGeoidFile(ctx: Context, vm: AppViewModel, uri: Uri, name: String, centre: Pair<Double, Double>?): String {
    val display = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: ""
    val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not read the file")
    val grid = input.use {
        if (display.endsWith(".gtx", ignoreCase = true)) {
            val c = centre ?: throw IllegalStateException("Enter the project latitude and longitude first, so only the area you need is read")
            GridGeoid.parseGtx(name, it, doubleArrayOf(c.first - 3, c.first + 3, c.second - 4, c.second + 4))
        } else GridGeoid.parseAscii(name, it.bufferedReader().readText())
    }
    vm.store.saveGeoid(name, grid.toAscii())
    return "covers ${grid.coverage}"
}
