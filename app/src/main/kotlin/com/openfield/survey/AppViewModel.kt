package com.openfield.survey

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.openfield.core.config.*
import com.openfield.core.device.*
import com.openfield.core.geo.*
import com.openfield.core.gnss.*
import com.openfield.core.io.CsvFormat
import com.openfield.core.io.CsvIO
import com.openfield.core.io.JobJson
import com.openfield.core.ntrip.*
import com.openfield.core.survey.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Single source of truth for the UI. Receiver and NTRIP callbacks arrive on background threads and are marshalled to the
 * main thread before touching state, so [SurveyEngine] (single-threaded by design) is only ever used from the main thread.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app
    val store = Storage(app.filesDir)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()       // file writes, in order
    private val net = Executors.newCachedThreadPool()          // blocking network calls

    // ---------------- observable state ----------------
    var config by mutableStateOf(UserConfig())
        private set
    val devices = mutableStateListOf<DeviceProfile>()
    val styles = mutableStateListOf<SurveyStyle>()
    val ntripProfiles = mutableStateListOf<NtripProfile>()
    val userCoordSystems = mutableStateListOf<CoordinateSystem>()
    var featureCodes by mutableStateOf(FeatureCodeLibrary())
        private set

    var job by mutableStateOf<Job?>(null)
        private set
    private var jobFile: File? = null
    /** Bumped whenever points change so screens that read the job's (non-observable) point list recompose. */
    var pointsRev by mutableIntStateOf(0)
        private set

    var snapshot by mutableStateOf<GnssSnapshot?>(null)
        private set
    var live by mutableStateOf<LivePosition?>(null)
        private set
    var headingDeg by mutableStateOf<Double?>(null)
        private set
    var connection by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var connectionMessage by mutableStateOf<String?>(null)
        private set
    var ntripState by mutableStateOf(NtripState.IDLE)
        private set
    var ntripMessage by mutableStateOf<String?>(null)
        private set
    var ntripBytes by mutableLongStateOf(0L)
        private set
    var progress by mutableStateOf<MeasureProgress?>(null)
        private set
    var continuousOn by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
    val loadErrors = mutableStateListOf<String>()
    val console = mutableStateListOf<String>()
    @Volatile var consoleOn = false

    // antenna height entry is shared by every measuring screen
    var antennaHeightText by mutableStateOf("2.000")
    var antennaMethod by mutableStateOf(HeightMeasurement.VERTICAL_TO_ARP)
    var antennaError by mutableStateOf<String?>(null)
        private set

    var screenStack by mutableStateOf(listOf("home"))
        private set

    // ---------------- connections ----------------
    private var engine: SurveyEngine? = null
    @Volatile private var session: ReceiverSession? = null
    private var ntrip: NtripClient? = null
    @Volatile private var lastSnap: GnssSnapshot? = null
    var simulator: NmeaSimulator? = null
        private set

    val activeStyle: SurveyStyle
        get() = styles.firstOrNull { it.id == config.activeStyleId } ?: styles.firstOrNull() ?: SurveyStyle.builtin[0]
    val activeDevice: DeviceProfile
        get() = devices.firstOrNull { it.id == activeStyle.deviceProfileId } ?: devices.firstOrNull() ?: DeviceProfile.builtin[0]
    val activeNtrip: NtripProfile?
        get() = ntripProfiles.firstOrNull { it.name == activeStyle.ntripProfileName }
    val unit: LinearUnit get() = job?.coordinateSystem?.unit ?: LinearUnit.METER
    val isConnected get() = connection == ConnectionState.CONNECTED

    init {
        config = store.loadConfig()
        reloadLibraries()
        resetAntennaDefaults()
        config.lastJobFile.takeIf { it.isNotBlank() }?.let { name ->
            val f = File(store.jobsDir, name)
            if (f.exists()) try { openJob(f) } catch (e: Exception) { loadErrors.add("Could not reopen last job: ${e.message}") }
        }
    }

    override fun onCleared() {
        disconnect(); stopNtrip(); io.shutdown(); net.shutdown()
    }

    // ---------------- navigation ----------------
    val route: String get() = screenStack.last()
    fun go(r: String) { screenStack = screenStack + r }
    fun back() { if (screenStack.size > 1) screenStack = screenStack.dropLast(1) }
    fun home() { screenStack = listOf("home") }

    // ---------------- libraries ----------------
    fun reloadLibraries() {
        loadErrors.clear()
        store.loadDevices().let { devices.clear(); devices.addAll(it.items); loadErrors.addAll(it.errors) }
        store.loadStyles().let { styles.clear(); styles.addAll(it.items); loadErrors.addAll(it.errors) }
        store.loadCoordSystems().let { userCoordSystems.clear(); userCoordSystems.addAll(it.items); loadErrors.addAll(it.errors) }
        ntripProfiles.clear(); ntripProfiles.addAll(store.loadNtrip())
        featureCodes = store.loadCodes()
    }

    /** The user's own systems first, then WGS 84 lat/lon and every UTM zone in both hemispheres. */
    fun allCoordSystems(): List<CoordinateSystem> =
        userCoordSystems.toList() + CoordinateSystem.presets + (1..60).map { CoordinateSystem.utm(it, south = true) }

    fun updateConfig(f: (UserConfig) -> UserConfig) {
        config = f(config)
        val text = config.toJson()
        io.execute { store.saveConfigText(text) }
    }

    // ---------------- devices, styles, real-time profiles ----------------
    /** Returns validation problems, or an empty list when saved. */
    fun saveDevice(p: DeviceProfile): List<String> {
        val problems = p.validate(); if (problems.isNotEmpty()) return problems
        store.saveDevice(p)
        val i = devices.indexOfFirst { it.id == p.id }
        if (i >= 0) devices[i] = p else devices.add(p)
        refreshEngineSettings()
        return emptyList()
    }

    /** Removes a user profile, or reverts a built-in profile to its shipped defaults. */
    fun deleteDevice(id: String) {
        store.deleteDevice(id)
        val builtin = DeviceProfile.builtin.firstOrNull { it.id == id }
        val i = devices.indexOfFirst { it.id == id }
        if (builtin != null) { if (i >= 0) devices[i] = builtin } else if (i >= 0) devices.removeAt(i)
        refreshEngineSettings()
    }

    fun isDeviceCustomised(id: String) = store.deviceFile(id).exists()

    fun saveStyle(s: SurveyStyle): String? {
        if (s.id.isBlank() || !s.id.matches(Regex("[A-Za-z0-9_.-]+"))) return "Style id must be letters, digits, '.', '_' or '-'"
        if (s.name.isBlank()) return "Style name is required"
        store.saveStyle(s)
        val i = styles.indexOfFirst { it.id == s.id }
        if (i >= 0) styles[i] = s else styles.add(s)
        refreshEngineSettings()
        return null
    }

    fun deleteStyle(id: String) {
        store.deleteStyle(id)
        val builtin = SurveyStyle.builtin.firstOrNull { it.id == id }
        val i = styles.indexOfFirst { it.id == id }
        if (builtin != null) { if (i >= 0) styles[i] = builtin } else if (i >= 0) styles.removeAt(i)
        if (config.activeStyleId == id && builtin == null) updateConfig { it.copy(activeStyleId = SurveyStyle.builtin[0].id) }
        refreshEngineSettings()
    }

    fun isStyleCustomised(id: String) = store.styleFile(id).exists()

    fun setActiveStyle(id: String) {
        updateConfig { it.copy(activeStyleId = id) }
        resetAntennaDefaults()
        refreshEngineSettings()
        if (isConnected) message = "Disconnect and reconnect to use this style's receiver"
    }

    /** The receiver and real-time connection belong to the survey style, as in a typical data collector. */
    fun setDeviceForActiveStyle(deviceId: String) { saveStyle(activeStyle.copy(deviceProfileId = deviceId)); refreshEngineSettings() }
    fun setNtripForActiveStyle(name: String) { saveStyle(activeStyle.copy(ntripProfileName = name)) }

    fun saveNtripProfile(p: NtripProfile, replaceName: String? = null) {
        val key = replaceName ?: p.name
        val i = ntripProfiles.indexOfFirst { it.name == key }
        if (i >= 0) ntripProfiles[i] = p else ntripProfiles.add(p)
        store.saveNtrip(ntripProfiles.toList())
        if (replaceName != null && replaceName != p.name && activeStyle.ntripProfileName == replaceName) setNtripForActiveStyle(p.name)
    }

    fun deleteNtripProfile(name: String) {
        ntripProfiles.removeAll { it.name == name }
        store.saveNtrip(ntripProfiles.toList())
    }

    fun saveFeatureCodes(lib: FeatureCodeLibrary) { featureCodes = lib; io.execute { store.saveCodes(lib) } }

    fun saveCoordSystem(cs: CoordinateSystem) {
        store.saveCoordSystem(cs)
        val i = userCoordSystems.indexOfFirst { it.name == cs.name }
        if (i >= 0) userCoordSystems[i] = cs else userCoordSystems.add(cs)
    }

    fun deleteCoordSystem(name: String) { store.deleteCoordSystem(name); userCoordSystems.removeAll { it.name == name } }

    // ---------------- antenna ----------------
    fun resetAntennaDefaults() {
        antennaHeightText = fmtNum(unit.fromMeters(activeStyle.defaultAntennaHeight), 3)
        antennaMethod = activeStyle.defaultHeightMethod
    }

    /** Validates the entered antenna height and pushes it to the live ground-position calculation. */
    fun applyAntenna() {
        val h = antennaHeightText.toDoubleOrNull()
        if (h == null) { antennaError = "Enter the antenna height"; return }
        try {
            val arp = activeDevice.antenna.arpHeight(unit.toMeters(h), antennaMethod)
            engine?.liveArpHeight = arp
            antennaError = null
        } catch (e: IllegalArgumentException) {
            antennaError = e.message
        }
    }

    /** Antenna height as entered, in metres (what [SurveyEngine] expects). */
    fun antennaMeasuredMetres(): Double? = antennaHeightText.toDoubleOrNull()?.let { unit.toMeters(it) }

    // ---------------- jobs ----------------
    fun createJob(name: String, cs: CoordinateSystem, description: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Enter a job name"
        val f = store.jobFile(n)
        if (f.exists()) return "A job named '$n' already exists"
        val j = Job(n, cs, description, operator = config.operatorName)
        j.styleId = activeStyle.id
        job = j; jobFile = f
        pointsRev++
        store.atomicWrite(f, JobJson.toJson(j))
        updateConfig { it.copy(lastJobFile = f.name) }
        resetAntennaDefaults()
        rebuildEngine()
        return null
    }

    /** Opens a job file. Returns warnings (for example a missing geoid grid). */
    fun openJob(f: File): List<String> {
        val (j, warnings) = store.loadJob(f)
        stopContinuous(); engine?.cancelMeasure(); progress = null
        job = j; jobFile = f
        pointsRev++
        updateConfig { it.copy(lastJobFile = f.name) }
        resetAntennaDefaults()
        rebuildEngine()
        warnings.firstOrNull()?.let { message = it }
        return warnings
    }

    fun importJobText(text: String): String? = try {
        val (j, _) = store.loadJobText(text)
        var f = store.jobFile(j.name); var n = 2
        while (f.exists()) { f = store.jobFile("${j.name} ($n)"); n++ }
        store.atomicWrite(f, text)
        openJob(f); null
    } catch (e: Exception) { "Not a valid OpenField job: ${e.message}" }

    fun deleteJob(f: File) {
        if (f == jobFile) { job = null; jobFile = null; engine = null; live = null; updateConfig { it.copy(lastJobFile = "") } }
        f.delete()
        pointsRev++
    }

    fun closeJob() { stopContinuous(); engine?.cancelMeasure(); progress = null; job = null; jobFile = null; engine = null; live = null; updateConfig { it.copy(lastJobFile = "") } }

    private fun persistJob() {
        val j = job ?: return; val f = jobFile ?: return
        val text = JobJson.toJson(j)       // serialised on the main thread so it cannot race with edits
        io.execute { store.atomicWrite(f, text) }
    }

    fun deletePoint(name: String) { if (job?.remove(name) == true) { pointsRev++; persistJob() } }

    fun updatePoint(p: SurveyPoint) { job?.add(p, overwrite = true); pointsRev++; persistJob() }

    fun addLine(l: DesignLine) { job?.lines?.add(l); pointsRev++; persistJob() }
    fun deleteLine(l: DesignLine) { job?.lines?.remove(l); pointsRev++; persistJob() }

    /**
     * Switches the job to a new coordinate system and re-derives every GNSS-measured point from its stored geodetic position,
     * so numbers stay consistent. Typed-in and imported points keep their numeric coordinates. Returns the count recomputed.
     */
    fun applyCoordinateSystem(cs: CoordinateSystem): Int {
        val j = job ?: return 0
        j.coordinateSystem = cs
        var n = 0
        for (p in j.points) {
            val derived = p.method == MeasureMethod.TOPO || p.method == MeasureMethod.OBSERVED_CONTROL || p.method == MeasureMethod.CONTINUOUS
            val lat = p.latDeg; val lon = p.lonDeg; val h = p.ellipsoidalHeight
            if (derived && lat != null && lon != null && h != null) {
                val g = cs.toLocal(LatLonH(lat, lon, h))
                j.add(p.copy(e = g.e, n = g.n, z = g.z), overwrite = true); n++
            }
        }
        pointsRev++
        persistJob()
        resetAntennaDefaults()
        rebuildEngine()
        return n
    }

    /** Bulk-adds imported points, skipping names that already exist. Returns (added, skipped). */
    fun addImportedPoints(points: List<SurveyPoint>): Pair<Int, Int> {
        val j = job ?: return 0 to points.size
        var added = 0; var skipped = 0
        for (p in points) { if (j.has(p.name)) skipped++ else { j.add(p); added++ } }
        pointsRev++; persistJob()
        return added to skipped
    }

    fun importCsv(text: String, format: CsvFormat, source: LinearUnit, kind: PointKind): String {
        val j = job ?: return "Open a job first"
        val r = CsvIO.import(text, format, j.coordinateSystem.unit, source, kind)
        val (added, skipped) = addImportedPoints(r.points)
        return buildString {
            append("Imported $added point(s)")
            if (skipped > 0) append(", skipped $skipped with names already in the job")
            if (r.errors.isNotEmpty()) append(". ${r.errors.size} line(s) could not be read: ${r.errors.take(3).joinToString("; ")}")
        }
    }

    // ---------------- engine ----------------
    private val surveyListener = object : SurveyListener {
        override fun onLive(p: LivePosition) { live = p }
        override fun onProgress(p: MeasureProgress) { progress = p }
        override fun onPointStored(point: SurveyPoint) { pointsRev++; progress = null; message = "Stored ${point.name}"; persistJob() }
        override fun onMessage(text: String) { message = text; if (engine?.isMeasuring != true) progress = null }
    }

    private fun rebuildEngine() {
        val j = job
        if (j == null) { engine = null; live = null; return }
        val e = SurveyEngine(j, activeStyle, activeDevice.antenna, activeDevice.id, surveyListener)
        engine = e
        applyAntenna()
        snapshot?.let { e.onSnapshot(it); live = e.live }
    }

    private fun refreshEngineSettings() {
        val e = engine ?: return
        e.style = activeStyle; e.antenna = activeDevice.antenna; e.deviceId = activeDevice.id
        applyAntenna()
    }

    fun startMeasure(spec: MeasureSpec): String? {
        val e = engine ?: return "Open a job first"
        if (e.isMeasuring) return "A measurement is already running"
        val wanted = spec.name
        if (wanted != null && job?.has(wanted) == true) return "Point '$wanted' already exists"
        return try { e.startMeasure(spec); null } catch (ex: IllegalArgumentException) { ex.message } catch (ex: IllegalStateException) { ex.message }
    }

    fun finishMeasureEarly() { engine?.finishMeasureEarly() }
    fun cancelMeasure() { engine?.cancelMeasure(); progress = null }

    fun startContinuous(code: String): String? {
        val e = engine ?: return "Open a job first"
        val h = antennaMeasuredMetres() ?: return "Enter the antenna height"
        return try { e.startContinuous(code, h, antennaMethod); continuousOn = true; null } catch (ex: IllegalArgumentException) { ex.message }
    }

    fun stopContinuous() { engine?.stopContinuous(); continuousOn = false }

    fun keyIn(name: String?, code: String, e: Double, n: Double, z: Double, kind: PointKind = PointKind.KEYED_IN, note: String = ""): String? {
        val eng = engine ?: return "Open a job first"
        return try { eng.keyIn(name, code, e, n, z, kind, note); null } catch (ex: DuplicatePointException) { ex.message }
    }

    // ---------------- receiver ----------------
    private val rxListener = object : ReceiverListener {
        override fun onState(state: ConnectionState, message: String?) { main.post { connection = state; connectionMessage = message } }
        override fun onSnapshot(snapshot: GnssSnapshot) { lastSnap = snapshot; main.post { handleSnapshot(snapshot) } }
        override fun onRawLine(line: String) {
            if (consoleOn) main.post { console.add(line); while (console.size > 150) console.removeAt(0) }
        }
    }

    private fun handleSnapshot(s: GnssSnapshot) {
        snapshot = s
        val speed = s.speedMps
        if (speed != null && speed >= 0.3 && s.courseDeg != null) headingDeg = s.courseDeg
        val e = engine
        if (e != null) { e.onSnapshot(s); live = e.live } else live = null
    }

    fun connect() {
        if (session != null) return
        val dev = activeDevice
        console.clear()
        val transport: Transport = when (dev.connection) {
            ConnectionType.BLUETOOTH_SPP -> BluetoothTransport(appContext, dev.address)
            ConnectionType.TCP -> TcpTransport(dev.address, dev.port)
            ConnectionType.SIMULATOR -> {
                val o = simOrigin(job?.coordinateSystem)
                val sim = NmeaSimulator(o.first, o.second, 30.0, geoidSep = -34.0)
                simulator = sim
                StreamTransport(sim.asStream(1.0), ByteArrayOutputStream(), "Simulator")
            }
            ConnectionType.USB_SERIAL -> {
                message = "USB serial is not included in this build. Use Bluetooth or TCP, or add a USB Transport (see docs/DEVICE_PROFILES.md)."
                return
            }
        }
        val driver = try {
            dev.createDriver({ RhinoRuntime() }, { store.readDriver(it) })
        } catch (e: Exception) { message = e.message ?: "Could not start the receiver driver"; simulator = null; return }
        val s = ReceiverSession(transport, driver, rxListener, autoReconnect = dev.connection != ConnectionType.SIMULATOR)
        session = s
        s.start()
    }

    fun disconnect() {
        session?.stop(); session = null; simulator = null
        connection = ConnectionState.DISCONNECTED; connectionMessage = null
        snapshot = null; lastSnap = null; live = null
    }

    fun setSimulatorQuality(q: Int) { simulator?.quality = q }

    /** Puts the simulated receiver inside the job's coordinate system so demo points land where the plan view expects. */
    private fun simOrigin(cs: CoordinateSystem?): Pair<Double, Double> = when (val p = cs?.projection) {
        is TransverseMercator -> (if (p.lat0Deg != 0.0) p.lat0Deg else 40.0) to (p.lon0Deg + 0.05)
        is LambertConic2SP -> p.lat0Deg to p.lon0Deg
        else -> 40.74844 to -73.985664
    }

    // ---------------- real-time corrections ----------------
    fun startNtrip() {
        val p = activeNtrip ?: run { message = "Choose a real-time connection first (Real-time connection screen)"; return }
        stopNtrip()
        val accepts = activeDevice.acceptsRtcm
        val c = NtripClient(p)
        c.setGgaProvider { ggaNow() }
        ntrip = c
        ntripBytes = 0
        c.start(object : NtripListener {
            override fun onData(buf: ByteArray, len: Int) { if (accepts) session?.write(buf, len) }
            override fun onState(state: NtripState, message: String?) { main.post { ntripState = state; ntripMessage = message } }
            override fun onStats(bytesReceived: Long) { main.post { ntripBytes = bytesReceived } }
        })
    }

    fun stopNtrip() { ntrip?.stop(); ntrip = null; ntripState = NtripState.IDLE; ntripMessage = null }

    private fun ggaNow(): String? {
        val s = lastSnap ?: return null
        if (!s.hasPosition) return null
        val q = when (s.solution) {
            SolutionType.RTK_FIXED -> 4; SolutionType.RTK_FLOAT -> 5; SolutionType.DGPS -> 2; SolutionType.PPS -> 3
            SolutionType.AUTONOMOUS -> 1; else -> 0
        }
        return NtripClient.ggaFor(s.latDeg, s.lonDeg, s.altMsl, s.geoidSep, s.utcSeconds ?: 0.0, s.satellitesUsed, q, s.hdop ?: 1.0)
    }

    fun fetchSourcetable(p: NtripProfile, onResult: (Result<List<Mountpoint>>) -> Unit) {
        net.execute {
            val r = runCatching { NtripClient(p).fetchSourcetable() }
            main.post { onResult(r) }
        }
    }

    // ---------------- formatting helpers shared by screens ----------------
    fun angleFormat(): AngleFormat = AngleFormat.entries.firstOrNull { it.name == config.angleFormat } ?: AngleFormat.DMS
    fun fmtAngle(deg: Double): String = Angles.format(deg, angleFormat(), 4)
    fun fmtDist(v: Double): String = fmtNum(v, config.distanceDecimals)
}
