package com.openfield.survey

import com.openfield.core.config.UserConfig
import com.openfield.core.device.DeviceProfile
import com.openfield.core.geo.CoordinateSystem
import com.openfield.core.geo.GeoidModel
import com.openfield.core.geo.GridGeoid
import com.openfield.core.io.JobJson
import com.openfield.core.json.*
import com.openfield.core.ntrip.NtripProfile
import com.openfield.core.survey.*
import java.io.File

class Loaded<T>(val items: List<T>, val errors: List<String>)

/**
 * Everything the user can edit lives as plain files under the app's private folder, so it can be backed up, copied to
 * another tablet, or edited by hand:
 *
 *   jobs folder (.ofjob)       survey jobs            devices folder (.json)   receiver profiles (override built-ins by id)
 *   styles folder (.json)      survey styles          coordsys folder (.json)  custom coordinate systems
 *   geoid folder (.grd)        geoid grids            drivers folder (.js)     JavaScript receiver drivers
 *   ntrip.json  real-time connections   codes.json  feature codes   config.json  appearance and layout
 */
class Storage(root: File) {
    val jobsDir = File(root, "jobs").apply { mkdirs() }
    val devicesDir = File(root, "devices").apply { mkdirs() }
    val stylesDir = File(root, "styles").apply { mkdirs() }
    val coordDir = File(root, "coordsys").apply { mkdirs() }
    val geoidDir = File(root, "geoid").apply { mkdirs() }
    val driversDir = File(root, "drivers").apply { mkdirs() }
    private val ntripFile = File(root, "ntrip.json")
    private val codesFile = File(root, "codes.json")
    private val configFile = File(root, "config.json")

    fun atomicWrite(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun safeName(s: String): String = s.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { "untitled" }.take(80)

    // ---------------- config ----------------
    fun loadConfig(): UserConfig = try { if (configFile.exists()) UserConfig.fromJson(configFile.readText()) else UserConfig() } catch (e: Exception) { UserConfig() }
    fun saveConfigText(text: String) = atomicWrite(configFile, text)

    // ---------------- jobs ----------------
    fun jobFile(name: String) = File(jobsDir, safeName(name) + ".ofjob")
    fun listJobs(): List<File> = (jobsDir.listFiles() ?: emptyArray()).filter { it.extension == "ofjob" }.sortedByDescending { it.lastModified() }

    private val geoidCache = HashMap<String, GeoidModel?>()
    val geoidResolver: (String) -> GeoidModel? = { name -> geoidCache.getOrPut(name) { loadGeoid(name) } }

    /** Returns the job plus warnings (for example a missing geoid grid, which would silently change heights). */
    fun loadJob(file: File): Pair<Job, List<String>> = loadJobText(file.readText())

    fun loadJobText(text: String): Pair<Job, List<String>> {
        val warnings = ArrayList<String>()
        val g = Json.parseObject(text)["coordinateSystem"].asObj()["geoid"].asObj()
        if (g["type"].asStr() == "grid" && loadGeoid(g["name"].asStr()) == null)
            warnings.add("Geoid grid '${g["name"].asStr()}' is not installed. Heights are shown as ellipsoidal until you import it (Coordinate systems > Import geoid grid).")
        return JobJson.fromJson(text, geoidResolver) to warnings
    }

    // ---------------- geoid ----------------
    fun hasGeoid(name: String): Boolean = File(geoidDir, safeName(name) + ".grd").exists()
    fun listGeoids(): List<String> = (geoidDir.listFiles() ?: emptyArray()).filter { it.extension == "grd" }.map { it.nameWithoutExtension }.sorted()
    private fun loadGeoid(name: String): GridGeoid? {
        val f = File(geoidDir, safeName(name) + ".grd")
        return if (f.exists()) try { GridGeoid.parseAscii(name, f.readText()) } catch (e: Exception) { null } else null
    }
    /** Validates the grid before saving; throws with a readable message when the file is malformed. */
    fun saveGeoid(name: String, text: String) {
        GridGeoid.parseAscii(name, text)
        atomicWrite(File(geoidDir, safeName(name) + ".grd"), text)
        geoidCache.remove(name)
    }

    // ---------------- devices ----------------
    fun deviceFile(id: String) = File(devicesDir, safeName(id) + ".json")
    fun loadDevices(): Loaded<DeviceProfile> {
        val errors = ArrayList<String>()
        val map = LinkedHashMap<String, DeviceProfile>()
        DeviceProfile.builtin.forEach { map[it.id] = it }
        for (f in (devicesDir.listFiles() ?: emptyArray()).filter { it.extension == "json" }.sortedBy { it.name }) {
            try {
                val p = DeviceProfile.fromJson(f.readText())
                val problems = p.validate()
                if (problems.isEmpty()) map[p.id] = p else errors.add("${f.name}: ${problems.joinToString("; ")}")
            } catch (e: Exception) { errors.add("${f.name}: ${e.message}") }
        }
        return Loaded(map.values.toList(), errors)
    }
    fun saveDevice(p: DeviceProfile) = atomicWrite(deviceFile(p.id), p.toJson())
    fun deleteDevice(id: String) { deviceFile(id).delete() }

    // ---------------- survey styles ----------------
    fun styleFile(id: String) = File(stylesDir, safeName(id) + ".json")
    fun loadStyles(): Loaded<SurveyStyle> {
        val errors = ArrayList<String>()
        val map = LinkedHashMap<String, SurveyStyle>()
        SurveyStyle.builtin.forEach { map[it.id] = it }
        for (f in (stylesDir.listFiles() ?: emptyArray()).filter { it.extension == "json" }.sortedBy { it.name }) {
            try {
                val s = SurveyStyle.fromMap(Json.parseObject(f.readText()))
                if (s.id.isBlank() || s.name.isBlank()) errors.add("${f.name}: id and name are required") else map[s.id] = s
            } catch (e: Exception) { errors.add("${f.name}: ${e.message}") }
        }
        return Loaded(map.values.toList(), errors)
    }
    fun saveStyle(s: SurveyStyle) = atomicWrite(styleFile(s.id), Json.stringify(s.toMap()))
    fun deleteStyle(id: String) { styleFile(id).delete() }

    // ---------------- coordinate systems ----------------
    fun coordFile(name: String) = File(coordDir, safeName(name) + ".json")
    fun loadCoordSystems(): Loaded<CoordinateSystem> {
        val errors = ArrayList<String>(); val list = ArrayList<CoordinateSystem>()
        for (f in (coordDir.listFiles() ?: emptyArray()).filter { it.extension == "json" }.sortedBy { it.name }) {
            try { list.add(CoordinateSystem.fromJson(f.readText(), geoidResolver)) } catch (e: Exception) { errors.add("${f.name}: ${e.message}") }
        }
        return Loaded(list, errors)
    }
    fun saveCoordSystem(cs: CoordinateSystem) = atomicWrite(coordFile(cs.name), cs.toJson())
    fun deleteCoordSystem(name: String) { coordFile(name).delete() }

    // ---------------- NTRIP ----------------
    fun loadNtrip(): List<NtripProfile> = try {
        if (!ntripFile.exists()) emptyList() else Json.parseObject(ntripFile.readText())["profiles"].asList().map { it.asObj() }.map {
            NtripProfile(it["name"].asStr(), it["host"].asStr(), it["port"].asInt(2101), it["mountpoint"].asStr(), it["username"].asStr(),
                it["password"].asStr(), it["useTls"].asBool(false), it["ggaIntervalSeconds"].asInt(10), it["ntripVersion"].asInt(2))
        }
    } catch (e: Exception) { emptyList() }

    fun saveNtrip(list: List<NtripProfile>) = atomicWrite(ntripFile, Json.stringify(mapOf("profiles" to list.map {
        mapOf("name" to it.name, "host" to it.host, "port" to it.port, "mountpoint" to it.mountpoint, "username" to it.username,
            "password" to it.password, "useTls" to it.useTls, "ggaIntervalSeconds" to it.ggaIntervalSeconds, "ntripVersion" to it.ntripVersion)
    })))

    // ---------------- feature codes ----------------
    fun loadCodes(): FeatureCodeLibrary = try {
        if (!codesFile.exists()) FeatureCodeLibrary() else FeatureCodeLibrary(Json.parse(codesFile.readText()).asList().map { it.asObj() }.map {
            FeatureCode(it["code"].asStr(), it["description"].asStr(), runCatching { FeatureType.valueOf(it["type"].asStr()) }.getOrDefault(FeatureType.POINT),
                it["colorHex"].asStr("#1565C0"), it["layer"].asStr())
        })
    } catch (e: Exception) { FeatureCodeLibrary() }

    fun saveCodes(lib: FeatureCodeLibrary) = atomicWrite(codesFile, Json.stringify(lib.codes.map {
        mapOf("code" to it.code, "description" to it.description, "type" to it.type.name, "colorHex" to it.colorHex, "layer" to it.layer)
    }))

    // ---------------- driver scripts ----------------
    private fun driverFile(name: String) = File(driversDir, File(name).name)   // strip any path
    fun readDriver(name: String): String {
        val f = driverFile(name)
        if (!f.exists()) throw java.io.FileNotFoundException("Driver script '$name' not found. Open Equipment > edit profile > Edit script to create it.")
        return f.readText()
    }
    fun writeDriver(name: String, text: String) = atomicWrite(driverFile(name), text)
    fun driverExists(name: String) = driverFile(name).exists()
}
