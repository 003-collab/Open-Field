package com.openfield.core.geo

import com.openfield.core.json.*
import kotlin.math.*

/** Output of the coordinate system pipeline, expressed in the system's linear unit. */
data class GridPoint(val e: Double, val n: Double, val z: Double)

/** Geodetic WGS-84 position as delivered by the receiver. */
data class LatLonH(val latDeg: Double, val lonDeg: Double, val ellipsoidalHeight: Double)

/**
 * "Ground" coordinates: grid coordinates scaled about a chosen point so that distances match ground distances at the
 * project height. e' = groundE + (e - originE) * scaleFactor (metres), same for northing. Height is not affected.
 */
data class GroundSpec(
    val scaleFactor: Double,
    val originE: Double, val originN: Double,
    val groundE: Double = originE, val groundN: Double = originN,
    val projectHeightM: Double = 0.0
) {
    fun forward(e: Double, n: Double) = doubleArrayOf(groundE + (e - originE) * scaleFactor, groundN + (n - originN) * scaleFactor)
    fun inverse(ge: Double, gn: Double) = doubleArrayOf(originE + (ge - groundE) / scaleFactor, originN + (gn - groundN) / scaleFactor)
    fun toMap(): Map<String, Any?> = mapOf("scale" to scaleFactor, "originE" to originE, "originN" to originN, "groundE" to groundE, "groundN" to groundN, "projectHeight" to projectHeightM)

    companion object {
        fun fromMap(m: Map<String, Any?>): GroundSpec {
            fun d(k: String, def: Double = 0.0) = (m[k] as? Number)?.toDouble() ?: def
            return GroundSpec(d("scale", 1.0), d("originE"), d("originN"), d("groundE"), d("groundN"), d("projectHeight"))
        }

        /**
         * Scale that turns grid distances into ground distances at [latDeg],[lonDeg] and ellipsoidal height [ellipsoidalHeightM]:
         * 1 / (gridScale * R / (R + h)). The grid origin is that location's grid coordinate, left unshifted.
         */
        fun at(cs: CoordinateSystem, latDeg: Double, lonDeg: Double, ellipsoidalHeightM: Double): GroundSpec {
            val proj = requireNotNull(cs.projection) { "Ground coordinates need a projection" }
            val flat = cs.copy(localization = null, ground = null, unit = LinearUnit.METER, geoid = NoGeoid, datum = cs.datum)
            val g0 = flat.toLocal(LatLonH(latDeg, lonDeg, 0.0))
            val k = gridScaleAt(proj, latDeg, lonDeg)
            val ell = proj.ellipsoid
            val sl = sin(latDeg * DEG)
            val w = 1 - ell.e2 * sl * sl
            val rm = ell.a * (1 - ell.e2) / w.pow(1.5); val rn = ell.a / sqrt(w)
            val r = sqrt(rm * rn)
            val elev = r / (r + ellipsoidalHeightM)
            return GroundSpec(1.0 / (k * elev), g0.e, g0.n, g0.e, g0.n, ellipsoidalHeightM)
        }

        /** Local grid scale factor of a conformal projection, measured numerically over 100 m. */
        fun gridScaleAt(proj: Projection, latDeg: Double, lonDeg: Double): Double {
            val ell = proj.ellipsoid
            val sl = sin(latDeg * DEG); val w = 1 - ell.e2 * sl * sl
            val rm = ell.a * (1 - ell.e2) / w.pow(1.5)
            val a = proj.forward(latDeg * DEG, lonDeg * DEG)
            val b = proj.forward(latDeg * DEG + 100.0 / rm, lonDeg * DEG)
            return hypot(b[0] - a[0], b[1] - a[1]) / 100.0
        }
    }
}

/**
 * Complete coordinate system definition, analogous to a "coordinate system" in a data collector:
 * datum transformation -> projection -> geoid model -> optional site calibration -> linear unit.
 *
 * Grid (E,N) and Z are held in metres until the final unit conversion in [toLocal]/[fromLocal].
 */
data class CoordinateSystem(
    val name: String,
    val datum: Datum = Datum.WGS84,
    val projection: Projection? = null,
    val geoid: GeoidModel = NoGeoid,
    val unit: LinearUnit = LinearUnit.METER,
    val localization: Localization? = null,
    val ground: GroundSpec? = null,
    /** Library selection this system was built from (informational, lets the picker reopen on the same entries). */
    val librarySystem: String? = null,
    val libraryZone: String? = null,
    val refFrame: String? = null,
    val epoch: Double? = null
) {
    /** WGS-84 geodetic -> local coordinates in [unit]. Without a projection, E=lon°, N=lat° and Z in [unit]. */
    fun toLocal(p: LatLonH): GridPoint {
        val latR = p.latDeg * DEG; val lonR = p.lonDeg * DEG
        var lat = latR; var lon = lonR; var h = p.ellipsoidalHeight
        if (!datum.fromWgs84.isIdentity || datum.ellipsoid != Ellipsoid.WGS84) {
            val x = Ellipsoid.WGS84.toEcef(latR, lonR, p.ellipsoidalHeight)
            val t = datum.fromWgs84.apply(x[0], x[1], x[2])
            val g = datum.ellipsoid.fromEcef(t[0], t[1], t[2])
            lat = g[0]; lon = g[1]; h = g[2]
        }
        val ortho = h - geoid.separation(p.latDeg, p.lonDeg)
        val proj = projection
        if (proj == null) return GridPoint(lon / DEG, lat / DEG, unit.fromMeters(ortho))
        val en = proj.forward(lat, lon)
        var e = en[0]; var n = en[1]; var z = ortho
        ground?.let { val q = it.forward(e, n); e = q[0]; n = q[1] }
        localization?.let { val l = it.forward(e, n, z); e = l[0]; n = l[1]; z = l[2] }
        return GridPoint(unit.fromMeters(e), unit.fromMeters(n), unit.fromMeters(z))
    }

    /** Local coordinates in [unit] -> WGS-84 geodetic. */
    fun fromLocal(g: GridPoint): LatLonH {
        val proj = projection
        val lat: Double; val lon: Double; val ortho: Double
        if (proj == null) {
            lat = g.n * DEG; lon = g.e * DEG; ortho = unit.toMeters(g.z)
        } else {
            var e = unit.toMeters(g.e); var n = unit.toMeters(g.n); var z = unit.toMeters(g.z)
            localization?.let { val l = it.inverse(e, n, z); e = l[0]; n = l[1]; z = l[2] }
            ground?.let { val q = it.inverse(e, n); e = q[0]; n = q[1] }
            val ll = proj.inverse(e, n)
            lat = ll[0]; lon = ll[1]; ortho = z
        }
        // geoid separation is defined on WGS-84 lat/lon; datum shifts are metre-level so the effect is negligible
        val h = ortho + geoid.separation(lat / DEG, lon / DEG)
        if (datum.fromWgs84.isIdentity && datum.ellipsoid == Ellipsoid.WGS84) return LatLonH(lat / DEG, lon / DEG, h)
        val x = datum.ellipsoid.toEcef(lat, lon, h)
        val w = datum.fromWgs84.applyInverse(x[0], x[1], x[2])
        val q = Ellipsoid.WGS84.fromEcef(w[0], w[1], w[2])
        return LatLonH(q[0] / DEG, q[1] / DEG, q[2])
    }

    /** Projection-grid coordinates in metres with no localization, used as input to a calibration. */
    fun toProjectedMetres(p: LatLonH): GridPoint = copy(localization = null, unit = LinearUnit.METER).toLocal(p)

    fun withLocalization(l: Localization?) = copy(localization = l)

    // ---------------- JSON ----------------

    fun toMap(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("name" to name, "unit" to unit.name)
        m["datum"] = mapOf(
            "name" to datum.name, "ellipsoid" to datum.ellipsoid.name, "a" to datum.ellipsoid.a, "invF" to datum.ellipsoid.invF,
            "helmert" to datum.fromWgs84.let { mapOf("tx" to it.tx, "ty" to it.ty, "tz" to it.tz, "rx" to it.rxArcsec, "ry" to it.ryArcsec, "rz" to it.rzArcsec, "scalePpm" to it.scalePpm) }
        )
        when (val p = projection) {
            is TransverseMercator -> m["projection"] = mapOf("type" to "tm", "lat0" to p.lat0Deg, "lon0" to p.lon0Deg, "k0" to p.k0, "falseEasting" to p.falseEasting, "falseNorthing" to p.falseNorthing)
            is LambertConic2SP -> m["projection"] = mapOf("type" to "lcc2sp", "lat0" to p.lat0Deg, "lon0" to p.lon0Deg, "lat1" to p.lat1Deg, "lat2" to p.lat2Deg, "falseEasting" to p.falseEasting, "falseNorthing" to p.falseNorthing)
            null -> {}
        }
        when (val g = geoid) {
            is ConstantGeoid -> m["geoid"] = mapOf("type" to "constant", "value" to g.value)
            is GridGeoid -> m["geoid"] = mapOf("type" to "grid", "name" to g.name, "file" to "geoid/${g.name}.grd")
            else -> {}
        }
        localization?.let { m["localization"] = it.toMap() }
        ground?.let { m["ground"] = it.toMap() }
        librarySystem?.let { m["librarySystem"] = it }
        libraryZone?.let { m["libraryZone"] = it }
        refFrame?.let { m["refFrame"] = it }
        epoch?.let { m["epoch"] = it }
        return m
    }

    fun toJson(pretty: Boolean = true) = Json.stringify(toMap(), pretty)

    companion object {
        val LATLON = CoordinateSystem("WGS 84 Latitude/Longitude")

        fun utm(zone: Int, south: Boolean = false, datum: Datum = Datum.WGS84, unit: LinearUnit = LinearUnit.METER, geoid: GeoidModel = NoGeoid) =
            CoordinateSystem("${datum.name} / UTM zone $zone${if (south) "S" else "N"}", datum, TransverseMercator.utm(zone, south, datum.ellipsoid), geoid, unit)

        val presets: List<CoordinateSystem> = listOf(LATLON) + (1..60).map { utm(it, false) }

        fun fromMap(m: Map<String, Any?>, geoidResolver: (String) -> GeoidModel? = { null }): CoordinateSystem {
            val dm = m["datum"].asObj()
            val ell = Ellipsoid.byName(dm["ellipsoid"].asStr()) ?: Ellipsoid(dm["ellipsoid"].asStr("Custom"), dm["a"].asDouble(6378137.0), dm["invF"].asDouble(298.257223563))
            val hm = dm["helmert"].asObj()
            val datum = Datum(dm["name"].asStr("WGS 84"), ell, Helmert7(hm["tx"].asDouble(), hm["ty"].asDouble(), hm["tz"].asDouble(), hm["rx"].asDouble(), hm["ry"].asDouble(), hm["rz"].asDouble(), hm["scalePpm"].asDouble()))
            val pm = m["projection"].asObj()
            val proj: Projection? = when (pm["type"].asStr()) {
                "tm" -> TransverseMercator(ell, pm["lat0"].asDouble(), pm["lon0"].asDouble(), pm["k0"].asDouble(0.9996), pm["falseEasting"].asDouble(), pm["falseNorthing"].asDouble())
                "lcc2sp" -> LambertConic2SP(ell, pm["lat0"].asDouble(), pm["lon0"].asDouble(), pm["lat1"].asDouble(), pm["lat2"].asDouble(), pm["falseEasting"].asDouble(), pm["falseNorthing"].asDouble())
                else -> null
            }
            val gm = m["geoid"].asObj()
            val geoid: GeoidModel = when (gm["type"].asStr()) {
                "constant" -> ConstantGeoid(gm["value"].asDouble())
                "grid" -> geoidResolver(gm["name"].asStr()) ?: NoGeoid
                else -> NoGeoid
            }
            val loc = if (m.containsKey("localization")) Localization.fromMap(m["localization"].asObj()) else null
            val gr = if (m.containsKey("ground")) GroundSpec.fromMap(m["ground"].asObj()) else null
            return CoordinateSystem(m["name"].asStr("Custom"), datum, proj, geoid,
                runCatching { LinearUnit.valueOf(m["unit"].asStr()) }.getOrDefault(LinearUnit.METER), loc, gr,
                m["librarySystem"]?.asStr(), m["libraryZone"]?.asStr(), m["refFrame"]?.asStr(), (m["epoch"] as? Number)?.toDouble())
        }

        fun fromJson(text: String, geoidResolver: (String) -> GeoidModel? = { null }) = fromMap(Json.parseObject(text), geoidResolver)
    }
}
