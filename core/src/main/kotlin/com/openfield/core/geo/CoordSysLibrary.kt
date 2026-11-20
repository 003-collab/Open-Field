package com.openfield.core.geo

import com.openfield.core.json.*

/** One zone of a library system, for example "Kentucky Single Zone". */
data class LibraryZone(val name: String, val projection: Projection, val defaultUnit: LinearUnit, val epsg: Int? = null) {
    /** Short description of the projection for the picker, for example "Lambert 2SP, origin 36.33°N 85.75°W". */
    val summary: String get() = when (val p = projection) {
        is TransverseMercator -> "Transverse Mercator, CM %.4f°, k0 %.6f".format(p.lon0Deg, p.k0)
        is LambertConic2SP -> "Lambert 2SP, parallels %.4f° and %.4f°".format(p.lat1Deg, p.lat2Deg)
    }
}

/** A library system (family of zones) such as "United States/NAD83" or "UTM". */
class LibrarySystem(
    val name: String,
    val datum: Datum,
    val datumLabel: String,
    val refFrame: String,
    val epoch: Double?,
    val note: String,
    val zones: List<LibraryZone>
)

/** A geoid model that can be selected. The grid itself is a file the user imports; [file] is the usual source name. */
data class GeoidEntry(val name: String, val region: String, val file: String)

/**
 * Built-in coordinate system library, the equivalent of "Select from library". US State Plane (NAD83(2011)) zones are read
 * from the bundled coordsys_library.json (generated from the EPSG registry); UTM zones are computed.
 *
 * WGS 84 and NAD83(2011) are treated as coincident. Real-time corrections from US CORS networks are delivered in the
 * NAD83(2011) frame, so no transformation is applied. No displacement (plate motion) model is applied either.
 */
object CoordSysLibrary {
    private val data: Map<String, Any?> by lazy {
        val stream = CoordSysLibrary::class.java.getResourceAsStream("/coordsys_library.json")
        if (stream == null) emptyMap() else Json.parseObject(stream.bufferedReader().use { it.readText() })
    }

    private fun utmSystem(): LibrarySystem {
        val zones = (1..60).flatMap { z ->
            val cm = -183.0 + 6 * z
            fun range() = "${fmtLon(cm - 3)} to ${fmtLon(cm + 3)}"
            listOf(false, true).map { south ->
                LibraryZone("Zone $z ${if (south) "South" else "North"} (${range()})", TransverseMercator.utm(z, south, Ellipsoid.WGS84), LinearUnit.METER)
            }
        }
        return LibrarySystem("UTM", Datum.WGS84, "WGS 84", "WGS 84", null, "Universal Transverse Mercator on WGS 84.", zones)
    }

    private fun fmtLon(v: Double): String = "%d°%s".format(kotlin.math.abs(v.toInt()), if (v < 0) "W" else "E")

    private fun usSystem(): LibrarySystem? {
        val s = data["systems"].asList().firstOrNull()?.asObj() ?: return null
        val ell = Ellipsoid.GRS80
        val zones = s["zones"].asList().map { it.asObj() }.map { z ->
            val proj: Projection = when (z["type"].asStr()) {
                "tm" -> TransverseMercator(ell, z["lat0"].asDouble(), z["lon0"].asDouble(), z["k0"].asDouble(1.0), z["fe"].asDouble(), z["fn"].asDouble())
                else -> LambertConic2SP(ell, z["lat0"].asDouble(), z["lon0"].asDouble(), z["lat1"].asDouble(), z["lat2"].asDouble(), z["fe"].asDouble(), z["fn"].asDouble())
            }
            LibraryZone(z["name"].asStr(), proj, runCatching { LinearUnit.valueOf(z["unit"].asStr()) }.getOrDefault(LinearUnit.METER), z["epsg"].asDouble().toInt())
        }
        return LibrarySystem(s["name"].asStr(), Datum.NAD83, s["datum"].asStr(), s["refFrame"].asStr(), s["epoch"].asDouble(), s["note"].asStr(), zones)
    }

    val systems: List<LibrarySystem> by lazy { listOfNotNull(usSystem(), utmSystem()) }

    val geoids: List<GeoidEntry> by lazy {
        data["geoids"].asList().map { it.asObj() }.map { GeoidEntry(it["name"].asStr(), it["region"].asStr(), it["file"].asStr()) }
    }

    fun system(name: String?) = systems.firstOrNull { it.name == name }

    /** Builds a ready-to-use coordinate system from a library selection. */
    fun build(
        system: LibrarySystem, zone: LibraryZone, unit: LinearUnit = zone.defaultUnit, geoid: GeoidModel = NoGeoid,
        name: String = "${system.name} / ${zone.name}", ground: GroundSpec? = null
    ) = CoordinateSystem(
        name, system.datum, zone.projection, geoid, unit, null, ground,
        librarySystem = system.name, libraryZone = zone.name, refFrame = system.refFrame, epoch = system.epoch
    )
}
