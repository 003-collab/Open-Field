package com.openfield.core.config

import com.openfield.core.json.*

enum class ThemeBase { LIGHT, DARK }

/** Colour roles every theme must define. Values are "#RRGGBB" strings. */
object ColorRole {
    const val PRIMARY = "primary"; const val ON_PRIMARY = "onPrimary"
    const val HEADER = "header"; const val ON_HEADER = "onHeader"
    const val BACKGROUND = "background"; const val SURFACE = "surface"; const val ON_SURFACE = "onSurface"
    const val ACCENT = "accent"; const val OUTLINE = "outline"
    const val GOOD = "good"; const val WARNING = "warning"; const val BAD = "bad"
    const val FIXED = "solutionFixed"; const val FLOAT = "solutionFloat"; const val DIFF = "solutionDiff"; const val AUTONOMOUS = "solutionAutonomous"; const val NO_FIX = "solutionNone"
    val all = listOf(PRIMARY, ON_PRIMARY, HEADER, ON_HEADER, BACKGROUND, SURFACE, ON_SURFACE, ACCENT, OUTLINE, GOOD, WARNING, BAD, FIXED, FLOAT, DIFF, AUTONOMOUS, NO_FIX)
}

data class ThemeSpec(
    val id: String,
    val name: String,
    val base: ThemeBase,
    val colors: Map<String, String>,
    /** Multiplies all text sizes (0.8 - 1.6). */
    val fontScale: Double = 1.0,
    /** Multiplies minimum touch-target height; raise for gloves. */
    val touchScale: Double = 1.0,
    val cornerRadiusDp: Int = 6,
    val builtin: Boolean = false
) {
    fun color(role: String): String = colors[role] ?: defaults(base)[role] ?: "#FF00FF"

    fun validate(): List<String> {
        val p = ArrayList<String>()
        if (id.isBlank()) p.add("Theme id is required")
        for ((k, v) in colors) if (!Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?").matches(v)) p.add("Colour '$k' is not a valid #RRGGBB value: $v")
        if (fontScale !in 0.8..1.6) p.add("Font scale must be between 0.8 and 1.6")
        if (touchScale !in 0.8..2.0) p.add("Touch scale must be between 0.8 and 2.0")
        return p
    }

    fun toMap() = mapOf("id" to id, "name" to name, "base" to base.name, "colors" to colors, "fontScale" to fontScale, "touchScale" to touchScale, "cornerRadiusDp" to cornerRadiusDp)

    companion object {
        fun fromMap(m: Map<String, Any?>): ThemeSpec {
            val base = runCatching { ThemeBase.valueOf(m["base"].asStr()) }.getOrDefault(ThemeBase.LIGHT)
            return ThemeSpec(m["id"].asStr(), m["name"].asStr("Custom theme"), base,
                m["colors"].asObj().mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(),
                m["fontScale"].asDouble(1.0), m["touchScale"].asDouble(1.0), m["cornerRadiusDp"].asInt(6))
        }

        private fun defaults(base: ThemeBase) = if (base == ThemeBase.LIGHT) classic.colors else night.colors

        val classic = ThemeSpec("classic", "Classic field", ThemeBase.LIGHT, mapOf(
            ColorRole.PRIMARY to "#0B5CAD", ColorRole.ON_PRIMARY to "#FFFFFF", ColorRole.HEADER to "#0B3C6E", ColorRole.ON_HEADER to "#FFFFFF",
            ColorRole.BACKGROUND to "#F2F4F7", ColorRole.SURFACE to "#FFFFFF", ColorRole.ON_SURFACE to "#1B1F24",
            ColorRole.ACCENT to "#F29900", ColorRole.OUTLINE to "#C5CCD6",
            ColorRole.GOOD to "#2E7D32", ColorRole.WARNING to "#EF6C00", ColorRole.BAD to "#C62828",
            ColorRole.FIXED to "#2E7D32", ColorRole.FLOAT to "#EF6C00", ColorRole.DIFF to "#1565C0", ColorRole.AUTONOMOUS to "#8E24AA", ColorRole.NO_FIX to "#C62828"
        ), builtin = true)

        val night = ThemeSpec("night", "Night", ThemeBase.DARK, mapOf(
            ColorRole.PRIMARY to "#4D9DE0", ColorRole.ON_PRIMARY to "#06121F", ColorRole.HEADER to "#10161D", ColorRole.ON_HEADER to "#E6EDF3",
            ColorRole.BACKGROUND to "#0B0F14", ColorRole.SURFACE to "#161C23", ColorRole.ON_SURFACE to "#E6EDF3",
            ColorRole.ACCENT to "#FFB74D", ColorRole.OUTLINE to "#2F3A46",
            ColorRole.GOOD to "#66BB6A", ColorRole.WARNING to "#FFA726", ColorRole.BAD to "#EF5350",
            ColorRole.FIXED to "#66BB6A", ColorRole.FLOAT to "#FFA726", ColorRole.DIFF to "#64B5F6", ColorRole.AUTONOMOUS to "#CE93D8", ColorRole.NO_FIX to "#EF5350"
        ), builtin = true)

        /** Maximum contrast for direct sunlight on a tablet; larger touch targets and text. */
        val sunlight = ThemeSpec("sunlight", "Sunlight (high contrast)", ThemeBase.LIGHT, mapOf(
            ColorRole.PRIMARY to "#003A8C", ColorRole.ON_PRIMARY to "#FFFFFF", ColorRole.HEADER to "#000000", ColorRole.ON_HEADER to "#FFFFFF",
            ColorRole.BACKGROUND to "#FFFFFF", ColorRole.SURFACE to "#FFFFFF", ColorRole.ON_SURFACE to "#000000",
            ColorRole.ACCENT to "#B34700", ColorRole.OUTLINE to "#000000",
            ColorRole.GOOD to "#005F1F", ColorRole.WARNING to "#8A3B00", ColorRole.BAD to "#A00000",
            ColorRole.FIXED to "#005F1F", ColorRole.FLOAT to "#8A3B00", ColorRole.DIFF to "#003A8C", ColorRole.AUTONOMOUS to "#5B0A91", ColorRole.NO_FIX to "#A00000"
        ), fontScale = 1.15, touchScale = 1.25, builtin = true)

        val builtin = listOf(classic, night, sunlight)
    }
}

/** Every screen reachable from the home menu. Users reorder, hide or favourite these. */
object Feature {
    const val JOBS = "jobs"
    const val MAP = "map"
    const val MEASURE_TOPO = "measure_topo"
    const val MEASURE_CODES = "measure_codes"
    const val OBSERVED_CONTROL = "observed_control"
    const val CONTINUOUS_TOPO = "continuous_topo"
    const val STAKE_POINTS = "stake_points"
    const val STAKE_LINES = "stake_lines"
    const val COGO = "cogo"
    const val CALIBRATION = "calibration"
    const val REVIEW_JOB = "review_job"
    const val IMPORT_EXPORT = "import_export"
    const val GNSS_STATUS = "gnss_status"
    const val EQUIPMENT = "equipment"
    const val REALTIME = "realtime"
    const val SURVEY_STYLES = "survey_styles"
    const val COORDINATE_SYSTEMS = "coordinate_systems"
    const val FEATURE_CODES = "feature_codes"
    const val APPEARANCE = "appearance"

    val all = listOf(JOBS, MAP, MEASURE_TOPO, MEASURE_CODES, OBSERVED_CONTROL, CONTINUOUS_TOPO, STAKE_POINTS, STAKE_LINES, COGO, CALIBRATION,
        REVIEW_JOB, IMPORT_EXPORT, GNSS_STATUS, EQUIPMENT, REALTIME, SURVEY_STYLES, COORDINATE_SYSTEMS, FEATURE_CODES, APPEARANCE)

    enum class Group(val labelKey: String) { JOB("group.job"), SURVEY("group.survey"), STAKEOUT("group.stakeout"), TOOLS("group.tools"), SETTINGS("group.settings") }

    fun group(id: String) = when (id) {
        JOBS, REVIEW_JOB, IMPORT_EXPORT -> Group.JOB
        MAP, MEASURE_TOPO, MEASURE_CODES, OBSERVED_CONTROL, CONTINUOUS_TOPO -> Group.SURVEY
        STAKE_POINTS, STAKE_LINES -> Group.STAKEOUT
        COGO, CALIBRATION, GNSS_STATUS -> Group.TOOLS
        else -> Group.SETTINGS
    }
}

/** Fields selectable for the status bar and the live position panel. */
object Field {
    // status bar
    const val CONNECTION = "connection"; const val SOLUTION = "solution"; const val H_PRECISION = "hprec"; const val V_PRECISION = "vprec"
    const val SATELLITES = "sats"; const val HDOP = "hdop"; const val PDOP = "pdop"; const val CORRECTION_AGE = "age"; const val JOB_NAME = "job"; const val RTK_STATE = "rtk"
    // position panel
    const val NORTHING = "northing"; const val EASTING = "easting"; const val ELEVATION = "elevation"
    const val LATITUDE = "lat"; const val LONGITUDE = "lon"; const val ELLIPSOIDAL_HEIGHT = "ellh"
    const val GROUND_ELEVATION = "ground_elevation"; const val SPEED = "speed"; const val COURSE = "course"; const val TIME = "time"; const val ANTENNA_HEIGHT = "antenna_height"

    val statusBarOptions = listOf(CONNECTION, SOLUTION, H_PRECISION, V_PRECISION, SATELLITES, HDOP, PDOP, CORRECTION_AGE, RTK_STATE, JOB_NAME)
    val positionOptions = listOf(NORTHING, EASTING, ELEVATION, GROUND_ELEVATION, LATITUDE, LONGITUDE, ELLIPSOIDAL_HEIGHT, SPEED, COURSE, TIME, ANTENNA_HEIGHT)
}

data class HomeTile(val id: String, val enabled: Boolean = true, val favorite: Boolean = false)

data class LayoutSpec(
    val homeTiles: List<HomeTile> = defaultTiles(),
    val homeColumns: Int = 3,
    val statusBarFields: List<String> = listOf(Field.CONNECTION, Field.SOLUTION, Field.H_PRECISION, Field.V_PRECISION, Field.SATELLITES, Field.JOB_NAME),
    val positionFields: List<String> = listOf(Field.NORTHING, Field.EASTING, Field.ELEVATION),
    val showPlanView: Boolean = true,
    /** Place primary action buttons on the left for left-handed use. */
    val leftHanded: Boolean = false,
    val showNmeaConsole: Boolean = false
) {
    /** Tiles shown on the home screen, favourites first, in the user's order. */
    fun visibleTiles(): List<HomeTile> = homeTiles.filter { it.enabled }.sortedByDescending { it.favorite }

    fun move(id: String, delta: Int): LayoutSpec {
        val list = homeTiles.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return this
        val t = list.removeAt(i); list.add(j, t)
        return copy(homeTiles = list)
    }

    fun updateTile(id: String, f: (HomeTile) -> HomeTile) = copy(homeTiles = homeTiles.map { if (it.id == id) f(it) else it })

    /** Adds tiles for any feature missing from a saved layout (after an app update). */
    fun withMissingFeatures(): LayoutSpec {
        val have = homeTiles.map { it.id }.toSet()
        return copy(homeTiles = homeTiles + Feature.all.filter { it !in have }.map { HomeTile(it) })
    }

    fun toMap() = mapOf(
        "homeTiles" to homeTiles.map { mapOf("id" to it.id, "enabled" to it.enabled, "favorite" to it.favorite) },
        "homeColumns" to homeColumns, "statusBarFields" to statusBarFields, "positionFields" to positionFields,
        "showPlanView" to showPlanView, "leftHanded" to leftHanded, "showNmeaConsole" to showNmeaConsole
    )

    companion object {
        fun defaultTiles(): List<HomeTile> {
            val favs = setOf(Feature.JOBS, Feature.MEASURE_TOPO, Feature.STAKE_POINTS, Feature.GNSS_STATUS)
            return Feature.all.map { HomeTile(it, true, it in favs) }
        }

        fun fromMap(m: Map<String, Any?>): LayoutSpec {
            val d = LayoutSpec()
            val tiles = m["homeTiles"].asList().mapNotNull { t -> val o = t.asObj(); o["id"].asStrOrNull()?.let { HomeTile(it, o["enabled"].asBool(true), o["favorite"].asBool(false)) } }
            return LayoutSpec(
                if (tiles.isEmpty()) d.homeTiles else tiles, m["homeColumns"].asInt(3).coerceIn(1, 6),
                m["statusBarFields"].asStrList().ifEmpty { d.statusBarFields }, m["positionFields"].asStrList().ifEmpty { d.positionFields },
                m["showPlanView"].asBool(true), m["leftHanded"].asBool(false), m["showNmeaConsole"].asBool(false)
            ).withMissingFeatures()
        }
    }
}

/**
 * Terminology. Defaults follow familiar data-collector wording ("Measure topo", "Observed control point", "Survey styles"...),
 * and every label can be overridden by the user to match their company's vocabulary or language.
 */
class Labels(private val overrides: Map<String, String> = emptyMap()) {
    fun get(key: String): String = overrides[key] ?: defaults[key] ?: key
    fun withOverride(key: String, value: String?): Labels = Labels(if (value.isNullOrBlank()) overrides - key else overrides + (key to value))
    val custom get() = overrides

    companion object {
        val defaults: Map<String, String> = linkedMapOf(
            "group.job" to "Job", "group.survey" to "Measure", "group.stakeout" to "Stake out", "group.tools" to "Tools", "group.settings" to "Settings",
            "tile.jobs" to "Jobs", "tile.map" to "Map", "tile.measure_topo" to "Measure topo", "tile.measure_codes" to "Measure codes",
            "tile.observed_control" to "Observed control point", "tile.continuous_topo" to "Continuous topo",
            "tile.stake_points" to "Points", "tile.stake_lines" to "Lines", "tile.cogo" to "Cogo", "tile.calibration" to "Calibration",
            "tile.review_job" to "Review job", "tile.import_export" to "Import / Export", "tile.gnss_status" to "GNSS status",
            "tile.equipment" to "Equipment", "tile.realtime" to "Real-time connection", "tile.survey_styles" to "Survey styles",
            "tile.coordinate_systems" to "Coordinate systems", "tile.feature_codes" to "Feature codes", "tile.appearance" to "Appearance & layout",
            "action.measure" to "Measure", "action.store" to "Store", "action.cancel" to "Cancel", "action.connect" to "Connect", "action.disconnect" to "Disconnect",
            "field.northing" to "Northing", "field.easting" to "Easting", "field.elevation" to "Elevation", "field.ground_elevation" to "Ground elevation",
            "field.lat" to "Latitude", "field.lon" to "Longitude", "field.ellh" to "Height (ellipsoidal)", "field.speed" to "Speed", "field.course" to "Course",
            "field.time" to "UTC time", "field.antenna_height" to "Antenna height",
            "status.hprec" to "H prec", "status.vprec" to "V prec", "status.sats" to "SVs", "status.hdop" to "HDOP", "status.pdop" to "PDOP",
            "status.age" to "Age", "status.job" to "Job", "status.rtk" to "Corrections", "status.solution" to "Solution", "status.connection" to "Receiver",
            "stake.goForward" to "Go forward", "stake.goBack" to "Go back", "stake.goLeft" to "Go left", "stake.goRight" to "Go right",
            "stake.cut" to "Cut", "stake.fill" to "Fill", "stake.station" to "Station", "stake.offset" to "Offset",
            "point.name" to "Point name", "point.code" to "Code", "point.antennaHeight" to "Antenna height", "point.measuredTo" to "Measured to"
        )
    }
}

enum class CoordinateOrder(val label: String) { NORTH_EAST("Northing, Easting"), EAST_NORTH("Easting, Northing") }

data class UserConfig(
    val themeId: String = ThemeSpec.classic.id,
    val customThemes: List<ThemeSpec> = emptyList(),
    val layout: LayoutSpec = LayoutSpec(),
    val labelOverrides: Map<String, String> = emptyMap(),
    val coordinateOrder: CoordinateOrder = CoordinateOrder.NORTH_EAST,
    val angleFormat: String = "DMS",
    val distanceDecimals: Int = 3,
    val keepScreenOn: Boolean = true,
    val operatorName: String = "",
    val activeStyleId: String = "rtk-fixed",
    val lastJobFile: String = ""
) {
    val themes get() = ThemeSpec.builtin + customThemes
    val theme: ThemeSpec get() = themes.firstOrNull { it.id == themeId } ?: ThemeSpec.classic
    val labels get() = Labels(labelOverrides)

    fun toJson(): String = Json.stringify(mapOf(
        "themeId" to themeId, "customThemes" to customThemes.map { it.toMap() }, "layout" to layout.toMap(),
        "labelOverrides" to labelOverrides, "coordinateOrder" to coordinateOrder.name, "angleFormat" to angleFormat,
        "distanceDecimals" to distanceDecimals, "keepScreenOn" to keepScreenOn, "operatorName" to operatorName,
        "activeStyleId" to activeStyleId, "lastJobFile" to lastJobFile
    ))

    companion object {
        fun fromJson(text: String): UserConfig {
            val m = Json.parseObject(text)
            val d = UserConfig()
            return UserConfig(
                m["themeId"].asStr(d.themeId), m["customThemes"].asList().map { ThemeSpec.fromMap(it.asObj()) },
                if (m.containsKey("layout")) LayoutSpec.fromMap(m["layout"].asObj()) else d.layout,
                m["labelOverrides"].asObj().mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(),
                runCatching { CoordinateOrder.valueOf(m["coordinateOrder"].asStr()) }.getOrDefault(d.coordinateOrder),
                m["angleFormat"].asStr(d.angleFormat), m["distanceDecimals"].asInt(d.distanceDecimals), m["keepScreenOn"].asBool(true),
                m["operatorName"].asStr(), m["activeStyleId"].asStr(d.activeStyleId), m["lastJobFile"].asStr()
            )
        }
    }
}
