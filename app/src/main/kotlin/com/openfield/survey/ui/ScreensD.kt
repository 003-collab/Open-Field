@file:OptIn(ExperimentalLayoutApi::class)

package com.openfield.survey.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfield.core.config.ColorRole
import com.openfield.core.device.ConnectionType
import com.openfield.core.device.DeviceProfile
import com.openfield.core.geo.ConstantGeoid
import com.openfield.core.geo.CoordinateSystem
import com.openfield.core.geo.Datum
import com.openfield.core.geo.GeoidModel
import com.openfield.core.geo.LinearUnit
import com.openfield.core.geo.NoGeoid
import com.openfield.core.gnss.ConnectionState
import com.openfield.core.json.Json
import com.openfield.core.ntrip.Mountpoint
import com.openfield.core.ntrip.NtripProfile
import com.openfield.core.ntrip.NtripState
import com.openfield.core.survey.FeatureCode
import com.openfield.core.survey.FeatureCodeLibrary
import com.openfield.core.survey.FeatureType
import com.openfield.core.survey.SurveyStyle
import com.openfield.survey.AppViewModel
import com.openfield.survey.fmtNum
import com.openfield.survey.parseNum

// ------------------------------------------------------------------ generic text editor (JSON profiles, scripts)

/** Full-screen monospace editor used for device profiles, survey styles, coordinate systems and driver scripts. */
@Composable
fun TextEditorScreen(
    vm: AppViewModel,
    title: String,
    initial: String,
    help: String,
    templates: List<Pair<String, String>> = emptyList(),
    extraActions: @Composable (currentText: String) -> Unit = {},
    onSave: (String) -> String?
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    ScreenFrame(vm, title, showStatus = false) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoText(help)
            OutlinedTextField(
                value = text, onValueChange = { text = it; error = null },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )
            error?.let { InfoText(it, color = LocalAppTheme.current.c(ColorRole.BAD)) }
            if (templates.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { (name, body) -> AppButton("Load: $name", { text = body; error = null }, primary = false) }
                }
            }
            extraActions(text)
            ActionBar {
                AppButton("Save", {
                    val e = try { onSave(text) } catch (ex: Exception) { ex.message ?: ex.javaClass.simpleName }
                    if (e == null) { vm.message = "Saved"; vm.back() } else error = e
                })
            }
        }
    }
}

// ------------------------------------------------------------------ equipment

private const val DEVICE_HELP =
    "A receiver profile is plain JSON. connection.type: BLUETOOTH_SPP (address = part of the paired device's name or its MAC), TCP (address + port), " +
        "or SIMULATOR. protocol.type: nmea0183, or script (set scriptFile and write a JavaScript driver). protocol.initCommands are sent on connect; " +
        "NMEA commands starting with \$ get their checksum added. protocol.qualityMap remaps GGA quality numbers, e.g. {\"5\": \"RTK_FIXED\"}. " +
        "antenna holds the offsets used to reduce the height you measure to the antenna reference point (metres)."

@Composable
fun EquipmentScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val ctx = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("Could not read file")
                val p = DeviceProfile.fromJson(text)
                val problems = vm.saveDevice(p)
                vm.message = if (problems.isEmpty()) "Imported ${p.name}" else problems.joinToString("; ")
            } catch (e: Exception) { vm.message = "Not a valid receiver profile: ${e.message}" }
        }
    }
    ScreenFrame(vm, label("tile.equipment")) {
        ScrollBody {
            val dev = vm.activeDevice
            Panel {
                SectionTitle("Receiver connection")
                Text(dev.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val stateText = when (vm.connection) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.ERROR -> "Error: ${vm.connectionMessage ?: ""}"
                    ConnectionState.DISCONNECTED -> "Not connected"
                }
                Text(stateText, color = t.c(if (vm.connection == ConnectionState.CONNECTED) ColorRole.GOOD else if (vm.connection == ConnectionState.ERROR) ColorRole.BAD else ColorRole.ON_SURFACE))
                val connected = vm.connection == ConnectionState.CONNECTED || vm.connection == ConnectionState.CONNECTING || vm.connection == ConnectionState.ERROR
                ActionBar {
                    if (connected) AppButton(label("action.disconnect"), { vm.disconnect() }, color = t.c(ColorRole.BAD))
                    else AppButton(label("action.connect"), { vm.connect() })
                }
            }

            SectionTitle("Receiver profiles   (survey style: ${vm.activeStyle.name})")
            vm.devices.toList().forEach { d ->
                val active = d.id == dev.id
                Panel(onClick = { vm.setDeviceForActiveStyle(d.id) }, borderColor = if (active) t.c(ColorRole.PRIMARY) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = active, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(d.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            InfoText(buildString {
                                if (d.manufacturer.isNotEmpty()) append(d.manufacturer).append("  ")
                                append(d.connection.label)
                                if (d.connection == ConnectionType.TCP) append("  ${d.address}:${d.port}")
                                if (d.connection == ConnectionType.BLUETOOTH_SPP && d.address.isNotEmpty()) append("  \"${d.address}\"")
                                if (d.protocol == "script") append("  JS driver")
                            })
                        }
                        IconButton(onClick = { vm.go("device_edit:${d.id}") }) { Icon(Icons.Default.Edit, "Edit profile") }
                    }
                }
            }
            ActionBar {
                AppButton("Import profile file", { importer.launch(arrayOf("*/*")) }, primary = false)
                AppButton("New profile", { vm.go("device_edit:new") })
            }
            Panel {
                SectionTitle("Adding your own equipment")
                InfoText(
                    "Most receivers only need a profile: pick Bluetooth or TCP, set the name or address, add any start-up commands, and enter the antenna offsets. " +
                        "For receivers with a binary or proprietary protocol, set protocol.type to script and write a short JavaScript driver in the script editor."
                )
            }
        }
    }
}

@Composable
fun DeviceEditScreen(vm: AppViewModel, id: String) {
    val isNew = id == "new"
    val existing = vm.devices.firstOrNull { it.id == id }
    val initial = (existing ?: DeviceProfile("my-receiver", "My receiver", "", ConnectionType.BLUETOOTH_SPP, "", 0, 115200,
        notes = "Describe this receiver here.")).toJson()
    TextEditorScreen(
        vm, if (isNew) "New receiver profile" else "Edit ${existing?.name ?: id}", initial, DEVICE_HELP,
        extraActions = { current ->
            val parsed = try { DeviceProfile.fromJson(current) } catch (e: Exception) { null }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (parsed != null && parsed.protocol == "script" && parsed.scriptFile.isNotBlank()) {
                    AppButton("Edit driver script", { vm.go("script_edit:${parsed.scriptFile}") }, primary = false)
                }
                if (!isNew && vm.isDeviceCustomised(id)) {
                    AppButton(if (DeviceProfile.builtin.any { it.id == id }) "Reset to default" else "Delete profile", { vm.deleteDevice(id); vm.back() }, primary = false)
                }
            }
        }
    ) { text ->
        val p = DeviceProfile.fromJson(text)
        val problems = vm.saveDevice(p)
        if (problems.isEmpty()) null else problems.joinToString("\n")
    }
}

@Composable
fun ScriptEditScreen(vm: AppViewModel, file: String) {
    val ctx = LocalContext.current
    fun asset(name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }
    val initial = if (vm.store.driverExists(file)) vm.store.readDriver(file) else asset("driver_template.js")
    TextEditorScreen(
        vm, "Driver script: $file", initial,
        "JavaScript run by the Rhino interpreter. Define onOpen() and onData(bytes) as described in the comments of the examples below. " +
            "The profile must have protocol.type \"script\" and this file name as scriptFile. Reconnect after saving.",
        templates = listOf("NMEA line framing" to asset("driver_template.js"), "Binary position record" to asset("driver_example_binary_fix.js"))
    ) { text ->
        vm.store.writeDriver(file, text); null
    }
}

// ------------------------------------------------------------------ real-time corrections (NTRIP)

@Composable
fun RealtimeScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    var editing by remember { mutableStateOf<NtripProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    ScreenFrame(vm, label("tile.realtime")) {
        ScrollBody {
            Panel {
                SectionTitle("Corrections stream")
                val state = vm.ntripState
                val color = when (state) { NtripState.CONNECTED -> ColorRole.GOOD; NtripState.ERROR -> ColorRole.BAD; NtripState.CONNECTING -> ColorRole.WARNING; else -> ColorRole.ON_SURFACE }
                Text(
                    when (state) { NtripState.CONNECTED -> "Receiving corrections"; NtripState.CONNECTING -> "Connecting…"; NtripState.ERROR -> "Error"; NtripState.STOPPED -> "Stopped"; NtripState.IDLE -> "Off" },
                    color = t.c(color), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                )
                vm.ntripMessage?.let { InfoText(it) }
                if (state == NtripState.CONNECTED) KeyValue("Received", fmtNum(vm.ntripBytes / 1024.0, 1) + " KB")
                if (vm.connection != ConnectionState.CONNECTED) InfoText("Connect the receiver first (Equipment) so corrections have somewhere to go.", color = t.c(ColorRole.WARNING))
                val running = state == NtripState.CONNECTED || state == NtripState.CONNECTING || state == NtripState.ERROR
                ActionBar {
                    if (running) AppButton("Stop", { vm.stopNtrip() }, color = t.c(ColorRole.BAD))
                    else AppButton("Start", { vm.startNtrip() }, enabled = vm.activeNtrip != null)
                }
            }
            SectionTitle("Connections   (survey style: ${vm.activeStyle.name})")
            if (vm.ntripProfiles.isEmpty()) InfoText("No connections yet. Add your NTRIP caster, mountpoint and login.")
            vm.ntripProfiles.toList().forEach { p ->
                val active = p.name == vm.activeStyle.ntripProfileName
                Panel(onClick = { vm.setNtripForActiveStyle(p.name) }, borderColor = if (active) t.c(ColorRole.PRIMARY) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = active, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            InfoText("${p.host}:${p.port}/${p.mountpoint}${if (p.useTls) "  TLS" else ""}")
                        }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Edit connection") }
                    }
                }
            }
            ActionBar { AppButton("New connection", { creating = true }) }
        }
    }
    if (creating || editing != null) NtripEditDialog(vm, editing) { editing = null; creating = false }
}

@Composable
private fun NtripEditDialog(vm: AppViewModel, existing: NtripProfile?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 2101).toString()) }
    var mount by remember { mutableStateOf(existing?.mountpoint ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var tls by remember { mutableStateOf(existing?.useTls ?: false) }
    var gga by remember { mutableStateOf((existing?.ggaIntervalSeconds ?: 10).toString()) }
    var version by remember { mutableIntStateOf(existing?.ntripVersion ?: 2) }
    var error by remember { mutableStateOf<String?>(null) }
    var table by remember { mutableStateOf<List<Mountpoint>?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun build(): NtripProfile? {
        val p = port.toIntOrNull()
        if (name.isBlank()) { error = "Enter a name"; return null }
        if (host.isBlank()) { error = "Enter the caster address"; return null }
        if (p == null || p !in 1..65535) { error = "Port must be 1 to 65535"; return null }
        if (existing == null && vm.ntripProfiles.any { it.name == name.trim() }) { error = "A connection with this name already exists"; return null }
        return NtripProfile(name.trim(), host.trim(), p, mount.trim(), user.trim(), pass, tls, gga.toIntOrNull()?.coerceAtLeast(0) ?: 10, version)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New connection" else "Edit connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Name", name, { name = it; error = null })
                TextInput("Caster address", host, { host = it; error = null }, keyboard = KeyboardType.Uri)
                NumField("Port", port, { port = it }, keyboard = KeyboardType.Number)
                TextInput("Mountpoint", mount, { mount = it })
                TextInput("User name", user, { user = it })
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Use TLS (secure)", Modifier.weight(1f)); Switch(checked = tls, onCheckedChange = { tls = it }) }
                NumField("Send position to caster every (s, 0 = never)", gga, { gga = it }, keyboard = KeyboardType.Number)
                Dropdown("NTRIP version", listOf(1, 2), version, { "NTRIP $it.0" }, { version = it })
                InfoText("Network (VRS) casters need the position sent back. The password is stored unencrypted in this app's private storage.")
                AppButton(if (loading) "Loading…" else "Get mountpoints from caster", {
                    val p = build()
                    if (p != null) {
                        loading = true; error = null
                        vm.fetchSourcetable(p.copy(mountpoint = "")) { r ->
                            loading = false
                            r.onSuccess { table = it; if (it.isEmpty()) error = "The caster returned no mountpoints" }
                            r.onFailure { error = it.message ?: "Could not reach the caster" }
                        }
                    }
                }, Modifier.fillMaxWidth(), primary = false, enabled = !loading)
                error?.let { InfoText(it, color = LocalAppTheme.current.c(ColorRole.BAD)) }
                table?.take(150)?.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { mount = m.name; table = null }.padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.SemiBold)
                            InfoText("${m.format}  ${m.navSystem}  ${m.identifier}${if (m.nmeaRequired) "  needs position" else ""}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { build()?.let { vm.saveNtripProfile(it, existing?.name); onDismiss() } }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.deleteNtripProfile(existing.name); onDismiss() }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ------------------------------------------------------------------ survey styles

private const val STYLE_HELP =
    "A survey style bundles the receiver, corrections and measurement rules. gate.allowedSolutions: RTK_FIXED, RTK_FLOAT, DGPS, AUTONOMOUS. " +
        "Limits (maxHdop, maxPdop, maxHorizontalPrecision, maxVerticalPrecision, maxDiffAgeSeconds) are in metres or seconds; use null to switch a limit off. " +
        "topoEpochs / controlEpochs are the epochs averaged per point. continuousMode TIME or DISTANCE with continuousInterval. " +
        "stakeoutOrientation: TOWARDS_POINT, NORTH or TRAVEL_DIRECTION. defaultHeightMethod: VERTICAL_TO_ARP, VERTICAL_TO_BOTTOM_OF_MOUNT, SLANT_TO_NOTCH, VERTICAL_TO_NOTCH."

@Composable
fun StylesScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    ScreenFrame(vm, label("tile.survey_styles")) {
        ScrollBody {
            vm.styles.toList().forEach { s ->
                val active = s.id == vm.activeStyle.id
                Panel(onClick = { vm.setActiveStyle(s.id) }, borderColor = if (active) t.c(ColorRole.PRIMARY) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = active, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            val g = s.gate
                            InfoText(
                                g.allowedSolutions.joinToString { it.label } + "  |  " + s.topoEpochs + " epochs" +
                                    (g.maxHorizontalPrecision?.let { "  |  H ≤ ${fmtNum(it, 3)} m" } ?: "") +
                                    "  |  receiver: ${vm.devices.firstOrNull { it.id == s.deviceProfileId }?.name ?: s.deviceProfileId}"
                            )
                        }
                        IconButton(onClick = { vm.go("style_edit:${s.id}") }) { Icon(Icons.Default.Edit, "Edit style") }
                    }
                }
            }
            ActionBar { AppButton("New style from current", { vm.go("style_edit:new") }) }
        }
    }
}

@Composable
fun StyleEditScreen(vm: AppViewModel, id: String) {
    val isNew = id == "new"
    val base = vm.styles.firstOrNull { it.id == id } ?: vm.activeStyle.copy(id = "my-style", name = "My style")
    TextEditorScreen(
        vm, if (isNew) "New survey style" else "Edit ${base.name}", Json.stringify(base.toMap()), STYLE_HELP,
        extraActions = {
            if (!isNew && vm.isStyleCustomised(id)) {
                AppButton(if (SurveyStyle.builtin.any { it.id == id }) "Reset to default" else "Delete style", { vm.deleteStyle(id); vm.back() }, primary = false)
            }
        }
    ) { text ->
        vm.saveStyle(SurveyStyle.fromMap(Json.parseObject(text)))
    }
}

// ------------------------------------------------------------------ coordinate systems

private const val COORD_HELP =
    "projection.type \"tm\" (Transverse Mercator: lat0, lon0, k0, falseEasting, falseNorthing) or \"lcc2sp\" (Lambert conic: lat0, lon0, lat1, lat2, falseEasting, falseNorthing); " +
        "angles in degrees, false origins in metres. datum.helmert holds the 7-parameter shift from WGS 84 (tx, ty, tz in m; rx, ry, rz in arc-seconds; scalePpm), position-vector convention. " +
        "unit: METER, US_SURVEY_FOOT or INTL_FOOT. geoid: {\"type\":\"constant\",\"value\":-34.0} or {\"type\":\"grid\",\"name\":\"<installed grid>\"}."

@Composable
fun CoordinateSystemsScreen(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val ctx = LocalContext.current
    var geoidRev by remember { mutableIntStateOf(0) }
    var geoidName by remember { mutableStateOf("") }
    var pendingApply by remember { mutableStateOf<CoordinateSystem?>(null) }
    val grids = remember(geoidRev) { vm.store.listGeoids() }

    val geoidImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("Could not read file")
                val n = geoidName.trim().ifEmpty { "geoid" }
                vm.store.saveGeoid(n, text)
                geoidRev++
                vm.message = "Installed geoid grid '$n'"
            } catch (e: Exception) { vm.message = "Geoid grid rejected: ${e.message}" }
        }
    }

    ScreenFrame(vm, label("tile.coordinate_systems")) {
        ScrollBody {
            vm.job?.let { j ->
                Panel(borderColor = t.c(ColorRole.PRIMARY)) {
                    SectionTitle("Current job: ${j.name}")
                    KeyValue("System", j.coordinateSystem.name)
                    KeyValue("Datum", j.coordinateSystem.datum.name)
                    KeyValue("Geoid", j.coordinateSystem.geoid.name)
                    KeyValue("Units", j.coordinateSystem.unit.label)
                    if (j.coordinateSystem.localization != null) KeyValue("Site calibration", "Applied")
                }
            }
            Panel {
                SectionTitle("Select from library")
                InfoText("Pick a system and zone from the built-in library (US State Plane on NAD83(2011), UTM), choose a geoid model, grid or ground coordinates and the distance units.")
                ActionBar { AppButton("Select from library", { if (vm.job == null) vm.message = "Open a job first" else vm.go("coord_library:job") }) }
            }
            Panel {
                SectionTitle("Geoid grids")
                InfoText("Library geoids (GEOID18 and others) are imported from the library screen as NGS/PROJ .gtx files. Here you can import any other geoid as a simple ASCII grid: a header line 'latMin lonMin dLat dLon rows cols', then rows x cols separations in metres (south to north, west to east). Resample any official geoid to this format for your project area.")
                TextInput("Name for the grid", geoidName, { geoidName = it })
                AppButton("Import geoid grid file", { geoidImporter.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), primary = false)
                if (grids.isNotEmpty()) InfoText("Installed: ${grids.joinToString()}")
            }
            SectionTitle("Custom systems")
            if (vm.userCoordSystems.isEmpty()) InfoText("None yet. Save one from the builder above, or write a custom projection as JSON.")
            vm.userCoordSystems.toList().forEach { cs ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cs.name, fontWeight = FontWeight.SemiBold)
                            InfoText("${cs.unit.label}, geoid: ${cs.geoid.name}")
                        }
                        IconButton(onClick = { vm.go("coord_edit:${cs.name}") }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { vm.deleteCoordSystem(cs.name) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    ActionBar { AppButton("Apply to job", { if (vm.job == null) vm.message = "Open a job first" else pendingApply = cs }, primary = false) }
                }
            }
            ActionBar { AppButton("New from JSON", { vm.go("coord_edit:new") }) }
        }
    }
    pendingApply?.let { cs ->
        ConfirmDialog(
            "Change coordinate system?",
            "Points measured with GNSS are recomputed in '${cs.name}'. Typed-in and imported points keep their numbers, so check them against the new system.",
            "Apply", { val n = vm.applyCoordinateSystem(cs); vm.message = "Recomputed $n point(s)" }, { pendingApply = null }
        )
    }
}

@Composable
fun CoordEditScreen(vm: AppViewModel, name: String) {
    val isNew = name == "new"
    val existing = vm.userCoordSystems.firstOrNull { it.name == name }
    val template = existing ?: vm.job?.coordinateSystem?.copy(name = "My coordinate system", localization = null) ?: CoordinateSystem.utm(18).copy(name = "My coordinate system")
    TextEditorScreen(vm, if (isNew) "New coordinate system" else "Edit $name", template.toJson(), COORD_HELP) { text ->
        val cs = CoordinateSystem.fromJson(text, vm.store.geoidResolver)
        if (cs.name.isBlank()) "name is required" else {
            if (!isNew && cs.name != name) vm.deleteCoordSystem(name)
            vm.saveCoordSystem(cs); null
        }
    }
}

// ------------------------------------------------------------------ feature codes

@Composable
fun FeatureCodesScreen(vm: AppViewModel) {
    var editing by remember { mutableStateOf<FeatureCode?>(null) }
    var creating by remember { mutableStateOf(false) }
    ScreenFrame(vm, label("tile.feature_codes")) {
        ScrollBody {
            InfoText("Codes appear as quick-pick chips in Measure codes and set colours in the plan view. DXF export puts each code's layer on its own CAD layer.")
            vm.featureCodes.codes.toList().forEach { c ->
                Panel(onClick = { editing = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Canvas(Modifier.padding(end = 10.dp).size(16.dp)) { drawCircle(parseColor(c.colorHex)) }
                        Column(Modifier.weight(1f)) {
                            Text(c.code, fontWeight = FontWeight.Bold)
                            InfoText("${c.description}  ${c.type.name.lowercase()}${if (c.layer.isNotEmpty()) "  layer ${c.layer}" else ""}")
                        }
                    }
                }
            }
            ActionBar { AppButton("Add code", { creating = true }) }
        }
    }
    if (creating || editing != null) {
        val ex = editing
        var code by remember { mutableStateOf(ex?.code ?: "") }
        var desc by remember { mutableStateOf(ex?.description ?: "") }
        var type by remember { mutableStateOf(ex?.type ?: FeatureType.POINT) }
        var color by remember { mutableStateOf(ex?.colorHex ?: "#1565C0") }
        var layer by remember { mutableStateOf(ex?.layer ?: "") }
        val close = { creating = false; editing = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (ex == null) "New code" else "Edit code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("Code", code, { code = it.uppercase() })
                    TextInput("Description", desc, { desc = it })
                    Dropdown("Type", FeatureType.entries.toList(), type, { it.name.lowercase() }, { type = it })
                    TextInput("Colour (#RRGGBB)", color, { color = it }, error = parseColor(color) == androidx.compose.ui.graphics.Color.Magenta && color.uppercase() != "#FF00FF")
                    TextInput("CAD layer", layer, { layer = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (code.isNotBlank()) {
                        val updated = vm.featureCodes.codes.filter { it.code != ex?.code && !it.code.equals(code, true) } + FeatureCode(code.trim(), desc, type, color, layer.trim())
                        vm.saveFeatureCodes(FeatureCodeLibrary(updated.sortedBy { it.code }))
                        close()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (ex != null) TextButton(onClick = { vm.saveFeatureCodes(FeatureCodeLibrary(vm.featureCodes.codes.filter { it.code != ex.code })); close() }) { Text("Delete") }
                    TextButton(onClick = close) { Text("Cancel") }
                }
            }
        )
    }
}
