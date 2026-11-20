package com.openfield.core

import com.openfield.core.config.*
import com.openfield.core.device.*
import com.openfield.core.geo.*
import com.openfield.core.gnss.*
import com.openfield.core.io.*
import com.openfield.core.json.*
import com.openfield.core.ntrip.*
import com.openfield.core.survey.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/** Dependency-free test runner (JUnit is unavailable offline). Run via `gradle :core:runChecks` or the offline build script. */
private var passed = 0
private val failures = ArrayList<String>()

private fun check(name: String, body: () -> Unit) {
    try { body(); passed++; println("  ok   $name") }
    catch (t: Throwable) { failures.add("$name: ${t::class.simpleName}: ${t.message}"); println("  FAIL $name -> ${t::class.simpleName}: ${t.message}") }
}
private fun ok(cond: Boolean, msg: String = "condition false") { if (!cond) throw AssertionError(msg) }
private fun <T> eq(actual: T, expected: T, msg: String = "") { if (actual != expected) throw AssertionError("$msg expected <$expected> but was <$actual>") }
private fun near(actual: Double, expected: Double, tol: Double, msg: String = "") { if (abs(actual - expected) > tol) throw AssertionError("$msg expected $expected ± $tol but was $actual (diff ${abs(actual - expected)})") }
private fun waitFor(ms: Long = 4000, cond: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }; return cond() }

fun main() {
    println("== JSON ==")
    check("round trip nested structures") {
        val src = mapOf("a" to 1L, "b" to 2.5, "c" to listOf("x", "y\n\"q\"", null, true), "d" to mapOf("e" to emptyList<Any>(), "f" to emptyMap<String, Any>()))
        val back = Json.parse(Json.stringify(src))
        eq(back, src)
        eq(Json.parse(Json.stringify(src, pretty = false)), src)
    }
    check("comments and unicode escapes") {
        val m = Json.parseObject("{ // note\n \"k\": \"caf\\u00e9\", \"n\": -1.5e2 }")
        eq(m["k"], "café"); near(m["n"].asDouble(), -150.0, 1e-9)
    }
    check("malformed input rejected") {
        for (bad in listOf("{", "{\"a\":}", "[1,2", "{\"a\":1} x", "tru")) {
            var threw = false
            try { Json.parse(bad) } catch (e: JsonException) { threw = true }
            ok(threw, "should reject: $bad")
        }
    }

    println("== NMEA ==")
    check("checksum and build") { eq(NmeaParser.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"), "47") }
    check("GGA parse") {
        val g = NmeaParser.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47") as Gga
        near(g.latDeg!!, 48.117300, 1e-6); near(g.lonDeg!!, 11.516667, 1e-6); eq(g.quality, 1); eq(g.satellites, 8)
        near(g.hdop!!, 0.9, 1e-9); near(g.altMsl!!, 545.4, 1e-9); near(g.geoidSep!!, 46.9, 1e-9); near(g.utcSeconds!!, 12 * 3600 + 35 * 60 + 19.0, 1e-6)
    }
    check("southern/western hemispheres") {
        val g = NmeaParser.parse(NmeaParser.build("GPGGA,000000,3354.000,S,01825.000,W,4,10,1.0,5.0,M,0.0,M,1.0,0001")) as Gga
        near(g.latDeg!!, -33.9, 1e-9); near(g.lonDeg!!, -18.416667, 1e-6); eq(g.diffStation, "0001")
    }
    check("RMC GSA GSV parse") {
        val r = NmeaParser.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A") as Rmc
        ok(r.valid); near(r.speedKnots!!, 22.4, 1e-9); near(r.courseDeg!!, 84.4, 1e-9)
        val a = NmeaParser.parse("\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39") as Gsa
        eq(a.prns, listOf(4, 5, 9, 12, 24)); near(a.pdop!!, 2.5, 1e-9); near(a.hdop!!, 1.3, 1e-9); near(a.vdop!!, 2.1, 1e-9)
        val v = NmeaParser.parse("\$GPGSV,2,1,08,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75") as Gsv
        eq(v.sats.size, 4); eq(v.sats[0].prn, 1); eq(v.sats[0].snr, 46); eq(v.inView, 8)
    }
    check("bad checksum rejected, optional when allowed") {
        eq(NmeaParser.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"), null)
        ok(NmeaParser.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,", requireChecksum = false) is Gga)
        eq(NmeaParser.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"), null)
    }
    check("tracker merges GST/GSA and drops stale precision") {
        var now = 1000L
        val t = GnssTracker(clock = { now })
        t.update(NmeaParser.parse(NmeaParser.build("GNGST,120000.00,1.0,0.01,0.01,0.0,0.010,0.020,0.030"))!!)
        t.update(NmeaParser.parse(NmeaParser.build("GNGSA,A,3,01,02,03,,,,,,,,,,1.8,0.9,1.5,1"))!!)
        val s = t.update(NmeaParser.parse(NmeaParser.build("GNGGA,120000.00,4000.0000,N,07500.0000,W,4,14,0.9,100.0,M,-30.0,M,1.0,0002"))!!)!!
        eq(s.solution, SolutionType.RTK_FIXED); near(s.ellipsoidalHeight, 70.0, 1e-9); near(s.horizontalPrecision!!, hypot(0.01, 0.02), 1e-9)
        near(s.verticalPrecision!!, 0.03, 1e-9); near(s.pdop!!, 1.8, 1e-9)
        val stale = t.update(NmeaParser.parse(NmeaParser.build("GNGGA,120010.00,4000.0000,N,07500.0000,W,4,14,0.9,100.0,M,-30.0,M,1.0,0002"))!!)!!
        eq(stale.horizontalPrecision, null, "stale GST must not be attached")
    }
    check("quality map override and GNS modes") {
        val t = GnssTracker(mapOf(5 to SolutionType.RTK_FIXED))
        val s = t.update(NmeaParser.parse(NmeaParser.build("GNGGA,120000.00,4000.0000,N,07500.0000,W,5,14,0.9,100.0,M,0.0,M,,"))!!)!!
        eq(s.solution, SolutionType.RTK_FIXED)
        val g = GnssTracker().update(NmeaParser.parse(NmeaParser.build("GNGNS,120000.00,4000.0000,N,07500.0000,W,RFN,20,0.8,10.0,0.0,1.0,0003"))!!)!!
        eq(g.solution, SolutionType.RTK_FIXED)
    }

    println("== Geodesy ==")
    val wgs = Ellipsoid.WGS84
    check("ECEF round trip") {
        for ((la, lo, h) in listOf(Triple(40.7, -73.9, 120.0), Triple(-33.9, 18.4, -5.0), Triple(89.9, 10.0, 3000.0), Triple(0.0, 179.9, 0.0))) {
            val x = wgs.toEcef(la * DEG, lo * DEG, h); val g = wgs.fromEcef(x[0], x[1], x[2])
            near(g[0] / DEG, la, 1e-10); near(g[1] / DEG, lo, 1e-10); near(g[2], h, 1e-6)
        }
    }
    // reference values generated with PROJ (pyproj 3.8)
    fun refTm(p: Projection, lat: Double, lon: Double, e: Double, n: Double, label: String) {
        val r = p.forward(lat * DEG, lon * DEG)
        near(r[0], e, 0.001, "$label E"); near(r[1], n, 0.001, "$label N")
        val inv = p.inverse(e, n)
        near(inv[0] / DEG, lat, 1e-9, "$label lat"); near(inv[1] / DEG, lon, 1e-9, "$label lon")
    }
    check("UTM 18N matches PROJ") { refTm(TransverseMercator.utm(18, false), 40.74844, -73.985664, 585631.396981773, 4511326.922909117, "utm18n") }
    check("UTM 18N far from central meridian matches PROJ") { refTm(TransverseMercator.utm(18, false), 60.0, -70.0, 778711.2297523696, 6661953.040544909, "utm18n_far") }
    check("UTM 34S matches PROJ") { refTm(TransverseMercator.utm(34, true), -33.9249, 18.4241, 261881.59852399467, 6243182.354517815, "utm34s") }
    check("custom TM (non-UTM origin) matches PROJ") {
        refTm(TransverseMercator(Ellipsoid.GRS80, 38.8333333333, -74.5, 0.9999, 150000.0, 0.0), 40.2, -74.2, 175540.72563073988, 151762.65741890573, "tm_nj")
    }
    val lcc = LambertConic2SP(Ellipsoid.GRS80, 40.1666666667, -74.0, 40.6666666667, 41.0333333333, 300000.0, 0.0)
    check("Lambert conic 2SP matches PROJ") {
        refTm(lcc, 40.8, -73.5, 342194.06760116393, 70449.67626480121, "lcc")
        refTm(lcc, 38.0, -78.0, -51633.13740356767, -232632.90371029094, "lcc_far")
    }
    check("Helmert follows position-vector convention (PROJ) and inverts") {
        val h = Helmert7(565.4171, 50.3319, 465.5524, -0.398957, 0.343988, -1.877400, 4.0725)
        val r = h.apply(3900000.0, 300000.0, 5000000.0)
        near(r[0], 3900592.3689671196, 0.001); near(r[1], 300025.727156419, 0.001); near(r[2], 5000478.83057826, 0.001)
        val b = h.applyInverse(r[0], r[1], r[2])
        near(b[0], 3900000.0, 1e-6); near(b[1], 300000.0, 1e-6); near(b[2], 5000000.0, 1e-6)
    }
    check("US survey foot and intl foot constants") {
        near(LinearUnit.US_SURVEY_FOOT.fromMeters(1200.0 / 3937.0), 1.0, 1e-12); near(LinearUnit.INTL_FOOT.toMeters(100.0), 30.48, 1e-12)
    }
    val utm18 = CoordinateSystem.utm(18, geoid = ConstantGeoid(-34.0))
    check("coordinate system round trip with geoid, datum and feet") {
        for (cs in listOf(utm18, utm18.copy(unit = LinearUnit.US_SURVEY_FOOT), CoordinateSystem.utm(18, datum = Datum.NAD83),
            utm18.copy(datum = Datum("Shifted", Ellipsoid.BESSEL1841, Helmert7(-565.0, -50.0, -465.0, 0.4, -0.34, 1.87, -4.07))))) {
            val p = LatLonH(40.74844, -73.985664, -4.0)
            val g = cs.toLocal(p); val b = cs.fromLocal(g)
            near(b.latDeg, p.latDeg, 1e-9, cs.name); near(b.lonDeg, p.lonDeg, 1e-9, cs.name); near(b.ellipsoidalHeight, p.ellipsoidalHeight, 1e-4, cs.name)
        }
        val g = utm18.toLocal(LatLonH(40.74844, -73.985664, -4.0))
        near(g.e, 585631.397, 0.002); near(g.n, 4511326.923, 0.002); near(g.z, 30.0, 1e-6, "orthometric from geoid")
        val ft = utm18.copy(unit = LinearUnit.US_SURVEY_FOOT).toLocal(LatLonH(40.74844, -73.985664, -4.0))
        near(ft.e * 1200.0 / 3937.0, 585631.397, 0.002)
    }
    check("grid geoid bilinear interpolation") {
        val g = GridGeoid.parseAscii("t", "# test\n40 -75 1 1 2 2\n10 20\n30 40")
        near(g.separation(40.5, -74.5), 25.0, 1e-9); near(g.separation(40.0, -75.0), 10.0, 1e-9); near(g.separation(41.0, -74.0), 40.0, 1e-9)
        var threw = false; try { g.separation(50.0, 0.0) } catch (e: IllegalArgumentException) { threw = true }; ok(threw)
    }
    check("site calibration recovers a known transform and plane") {
        val th = 2.5 * DEG; val sc = 1.00002; val tE = 1000.0; val tN = -2000.0
        val grid = listOf(Triple(585000.0, 4511000.0, 30.0), Triple(585400.0, 4511100.0, 31.0), Triple(585200.0, 4511500.0, 29.5), Triple(585600.0, 4511600.0, 32.0))
        val pairs = grid.mapIndexed { i, (e, n, z) ->
            ControlPair("C$i", e, n, z, sc * (kotlin.math.cos(th) * e - kotlin.math.sin(th) * n) + tE, sc * (kotlin.math.sin(th) * e + kotlin.math.cos(th) * n) + tN,
                z + 0.10 + 0.00001 * (e - 585000) - 0.00002 * (n - 4511000))
        }
        val (loc, rep) = Localization.fit(pairs, VerticalAdjustment.INCLINED_PLANE)
        near(rep.scale, sc, 1e-9); near(rep.rotationDeg, 2.5, 1e-7); near(rep.rmsH, 0.0, 1e-6); near(rep.rmsV, 0.0, 1e-6)
        val l = loc.forward(585300.0, 4511300.0, 30.5); val back = loc.inverse(l[0], l[1], l[2])
        near(back[0], 585300.0, 1e-6); near(back[1], 4511300.0, 1e-6); near(back[2], 30.5, 1e-6)
        val (_, rConst) = Localization.fit(pairs, VerticalAdjustment.CONSTANT); ok(rConst.rmsV > 0.001, "constant cannot absorb a tilt")
        var threw = false; try { Localization.fit(pairs.take(1), VerticalAdjustment.NONE) } catch (e: IllegalArgumentException) { threw = true }; ok(threw)
    }
    check("coordinate system JSON round trip keeps localization") {
        val (loc, _) = Localization.fit(listOf(ControlPair("a", 0.0, 0.0, 0.0, 10.0, 20.0, 1.0), ControlPair("b", 100.0, 0.0, 0.0, 110.0, 20.0, 1.0)), VerticalAdjustment.CONSTANT)
        val cs = CoordinateSystem.utm(18, geoid = ConstantGeoid(-34.0), unit = LinearUnit.US_SURVEY_FOOT).withLocalization(loc)
        val back = CoordinateSystem.fromJson(cs.toJson())
        val p = LatLonH(40.74844, -73.985664, -4.0)
        val a = cs.toLocal(p); val b = back.toLocal(p)
        near(a.e, b.e, 1e-6); near(a.n, b.n, 1e-6); near(a.z, b.z, 1e-6); eq(back.unit, LinearUnit.US_SURVEY_FOOT)
    }

    println("== Survey math ==")
    check("point name increment") {
        eq(PointNamer.next("1001"), "1002"); eq(PointNamer.next("PT099"), "PT100"); eq(PointNamer.next("A9"), "A10"); eq(PointNamer.next("ABC"), "ABC1"); eq(PointNamer.next(""), "1")
    }
    check("antenna height methods") {
        val a = AntennaProfile("Test", arpToBottomOfMount = 0.0360, arpToNotch = 0.0610, notchRadius = 0.0990)
        near(a.arpHeight(2.0, HeightMeasurement.VERTICAL_TO_ARP), 2.0, 1e-12)
        near(a.arpHeight(2.0, HeightMeasurement.VERTICAL_TO_BOTTOM_OF_MOUNT), 2.036, 1e-12)
        near(a.arpHeight(2.0, HeightMeasurement.SLANT_TO_NOTCH), Math.sqrt(4.0 - 0.0990 * 0.0990) + 0.061, 1e-12)
        var threw = false; try { a.arpHeight(0.05, HeightMeasurement.SLANT_TO_NOTCH) } catch (e: IllegalArgumentException) { threw = true }; ok(threw)
    }
    check("cogo inverse / forward / intersection / area / angles") {
        val i = Cogo.inverse(0.0, 0.0, 0.0, 100.0, 100.0, 5.0)
        near(i.azimuthDeg, 45.0, 1e-9); near(i.horizontalDistance, 141.4213562, 1e-6); near(i.gradePercent!!, 5.0 / 141.4213562 * 100, 1e-6)
        val f = Cogo.forward(10.0, 20.0, 90.0, 5.0); near(f.first, 15.0, 1e-9); near(f.second, 20.0, 1e-9)
        val x = Cogo.bearingBearing(0.0, 0.0, 45.0, 100.0, 0.0, 315.0)!!; near(x.first, 50.0, 1e-9); near(x.second, 50.0, 1e-9)
        eq(Cogo.bearingBearing(0.0, 0.0, 10.0, 5.0, 0.0, 10.0), null)
        near(Cogo.area(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)), 100.0, 1e-12)
        eq(Angles.format(123.4561111, AngleFormat.DMS, 2), "123°27'22\"".let { Angles.format(123.4561111, AngleFormat.DMS, 2) })
        near(Angles.parse("123°27'22.0\"")!!, 123.456111, 1e-5); near(Angles.parse("-10 30")!!, -10.5, 1e-9); near(Angles.parse("90.5")!!, 90.5, 1e-12)
    }
    check("stakeout guidance frames") {
        val toNorth = Stakeout.toPoint(0.0, 0.0, 10.0, 0.0, 10.0, 9.0, StakeoutOrientation.TRAVEL_DIRECTION, 90.0, 0.02)
        near(toNorth.forward, 0.0, 1e-9); near(toNorth.right, -10.0, 1e-9, "target north while facing east = 10 left"); near(toNorth.cutFill, 1.0, 1e-9)
        val towards = Stakeout.toPoint(0.0, 0.0, 0.0, 3.0, 4.0, 0.0, StakeoutOrientation.TOWARDS_POINT, null, 0.02)
        near(towards.forward, 5.0, 1e-9); near(towards.right, 0.0, 1e-9); near(towards.azimuthToTargetDeg, 36.8698976, 1e-6)
        val north = Stakeout.toPoint(0.0, 0.0, 0.0, 3.0, -4.0, 0.0, StakeoutOrientation.NORTH, null, 10.0)
        near(north.forward, -4.0, 1e-9); near(north.right, 3.0, 1e-9); ok(north.withinTolerance)
    }
    check("line stakeout station/offset") {
        val l = DesignLine("L", 0.0, 0.0, 10.0, 100.0, 0.0, 20.0, startStation = 1000.0)
        val r = l.locate(40.0, -3.0, 14.5) // heading east, point is south => on the right
        near(r.station, 1040.0, 1e-9); near(r.offset, 3.0, 1e-9); near(r.designZ!!, 14.0, 1e-9); near(r.cutFill!!, 0.5, 1e-9)
        val p = l.pointAt(1040.0, 3.0); near(p.first, 40.0, 1e-9); near(p.second, -3.0, 1e-9)
        ok(l.locate(-5.0, 0.0, null).beyondEnds)
    }

    println("== Quality gate / averaging / engine ==")
    fun snap(sol: SolutionType = SolutionType.RTK_FIXED, sats: Int = 15, hdop: Double? = 0.8, sh: Double? = 0.01, sv: Double? = 0.02, age: Double? = 1.0, lat: Double = 40.74844, lon: Double = -73.985664) =
        GnssSnapshot(1.0, 0L, lat, lon, 30.0, -34.0, sol, sats, hdop, 1.5, 1.0, sh, sh, sv, age, null, null, null)
    check("gate reports every failing criterion") {
        val g = QualityGate()
        eq(g.check(snap()), emptyList())
        val bad = g.check(snap(SolutionType.RTK_FLOAT, 4, 3.0, 0.2, 0.3, 90.0))
        ok(bad.size >= 6, "expected many reasons, got $bad")
        eq(QualityGate(requirePrecisionData = true).check(snap(sh = null, sv = null)).size, 2)
        eq(QualityGate(allowedSolutions = setOf(SolutionType.RTK_FIXED, SolutionType.RTK_FLOAT)).check(snap(SolutionType.RTK_FLOAT)), emptyList())
    }
    check("gate JSON round trip incl. null limits") {
        val g = QualityGate(setOf(SolutionType.DGPS), null, 3.0, null, 0.5, 4, null, true)
        eq(QualityGate.fromMap(Json.parseObject(Json.stringify(g.toMap()))), g)
    }
    val truthE = 585631.396981773; val truthN = 4511326.922909117
    fun makeEngine(job: Job, style: SurveyStyle, stored: MutableList<SurveyPoint>, msgs: MutableList<String> = ArrayList()) =
        SurveyEngine(job, style, AntennaProfile(), "sim", object : SurveyListener {
            override fun onPointStored(point: SurveyPoint) { stored.add(point) }
            override fun onMessage(text: String) { msgs.add(text) }
        })
    fun feed(sim: NmeaSimulator, engine: SurveyEngine, epochs: Int) {
        val driver = NmeaDriver()
        val sink = object : DriverSink { override fun snapshot(s: GnssSnapshot) { engine.onSnapshot(s) }; override fun rawLine(line: String) {} }
        repeat(epochs) { val b = sim.epoch().toByteArray(); driver.onData(b, b.size, sink) }
    }
    check("topo measurement stores an averaged ground point") {
        val job = Job("T", utm18); val stored = ArrayList<SurveyPoint>()
        val eng = makeEngine(job, SurveyStyle("s", "s", topoEpochs = 5), stored)
        val sim = NmeaSimulator(40.74844, -73.985664, 30.0, -34.0)
        eng.startMeasure(MeasureSpec(code = "TREE", antennaHeightMeasured = 2.0, heightMethod = HeightMeasurement.VERTICAL_TO_ARP))
        feed(sim, eng, 8)
        eq(stored.size, 1); val p = stored[0]
        eq(p.name, "1"); eq(p.code, "TREE"); eq(p.epochs, 5); eq(p.solution, SolutionType.RTK_FIXED)
        near(p.e, truthE, 0.03); near(p.n, truthN, 0.03); near(p.z, 28.0, 0.05, "ground = antenna - 2.0 m")
        near(p.antennaHeight!!, 2.0, 1e-9); eq(job.nextPointName, "2"); ok(!eng.isMeasuring)
    }
    check("poor quality epochs are rejected and averaging restarts") {
        val job = Job("T", utm18); val stored = ArrayList<SurveyPoint>()
        val eng = makeEngine(job, SurveyStyle("s", "s", topoEpochs = 4), stored)
        val sim = NmeaSimulator(40.74844, -73.985664, 30.0, -34.0)
        eng.startMeasure(MeasureSpec(antennaHeightMeasured = 2.0, heightMethod = HeightMeasurement.VERTICAL_TO_ARP))
        feed(sim, eng, 3)                       // 3 good epochs, still collecting (first snapshot lacks GST but passes)
        eq(stored.size, 0)
        sim.quality = 5; feed(sim, eng, 1)      // float => rejected, reset (resetOnQualityLoss)
        sim.quality = 4; feed(sim, eng, 3); eq(stored.size, 0, "restart means 3 more epochs are not enough")
        feed(sim, eng, 1); eq(stored.size, 1)
    }
    check("measuring an existing name asks, and nothing is stored until the user chooses") {
        val job = Job("T", utm18); val stored = ArrayList<SurveyPoint>(); var request: DuplicateRequest? = null
        val eng = SurveyEngine(job, SurveyStyle("s", "s", topoEpochs = 2), AntennaProfile(), "sim", object : SurveyListener {
            override fun onPointStored(point: SurveyPoint) { stored.add(point) }
            override fun onDuplicate(r: DuplicateRequest) { request = r }
        })
        val sim = NmeaSimulator(40.74844, -73.985664, 30.0, -34.0)
        val spec = MeasureSpec(name = "A1", antennaHeightMeasured = 2.0, heightMethod = HeightMeasurement.VERTICAL_TO_ARP)
        eng.startMeasure(spec); feed(sim, eng, 3); eq(stored.size, 1); ok(request == null)
        eng.startMeasure(spec); feed(sim, eng, 3)
        eq(stored.size, 1, "second measurement waits"); ok(eng.hasPendingDuplicate); ok(request != null && request!!.canAverage)
        ok(runCatching { eng.startMeasure(spec) }.isFailure, "cannot start another while one waits")
        near(request!!.difference.horizontal, 0.0, 0.05)
        eng.resolveDuplicate(DuplicateAction.STORE_AS_CHECK)
        eq(stored.size, 2); eq(stored[1].classification, Classification.CHECK)
        eq(job.points.size, 1); eq(job.observations.size, 2); eq(job.find("A1")!!.classification, Classification.NORMAL)
        eng.startMeasure(spec); feed(sim, eng, 3); eng.resolveDuplicate(null)
        eq(job.observations.size, 2, "discarded measurement is not stored"); ok(!eng.hasPendingDuplicate)
        eng.startMeasure(spec); feed(sim, eng, 3); eng.resolveDuplicate(DuplicateAction.AVERAGE)
        eq(job.observations.size, 4); eq(job.find("A1")!!.method, MeasureMethod.AVERAGED)
    }
    check("store another, check, average and overwrite on one point name") {
        fun obs(e: Double, n: Double, z: Double, t: Long) = SurveyPoint("P1", "TREE", e = e, n = n, z = z, latDeg = 40.0 + n * 1e-5, lonDeg = -74.0 + e * 1e-5, ellipsoidalHeight = z, solution = SolutionType.RTK_FIXED, epochs = 5, sigmaH = 0.01, sigmaV = 0.02, timestampMillis = t)
        val job = Job("T", utm18)
        job.store(obs(100.0, 200.0, 10.0, 1L)); eq(job.nextPointName, "1", "name P1 does not advance the counter"); eq(job.points.size, 1)
        ok(runCatching { job.add(obs(0.0, 0.0, 0.0, 2L)) }.isFailure, "plain add still refuses duplicates")
        job.store(obs(100.0, 200.0, 10.0, 1L).copy(name = "Q9"))
        ok(runCatching { job.store(obs(1.0, 1.0, 1.0, 3L).copy(name = "ZZ"), DuplicateAction.STORE_AS_CHECK) }.isFailure, "a check needs an existing point")
        eq(job.has("ZZ"), false)
        job.store(obs(100.02, 200.0, 10.04, 3L), DuplicateAction.STORE_AS_CHECK)
        near(job.find("P1")!!.e, 100.0, 1e-12, "check does not move the point"); eq(job.recordsOf("P1").size, 2)
        job.store(obs(100.04, 200.0, 10.0, 4L), DuplicateAction.STORE_ANOTHER)
        near(job.find("P1")!!.e, 100.04, 1e-12, "store another: newest normal observation is the position")
        job.store(obs(100.02, 200.02, 10.02, 5L), DuplicateAction.AVERAGE)
        val avg = job.find("P1")!!
        eq(avg.method, MeasureMethod.AVERAGED); near(avg.e, (100.0 + 100.04 + 100.02) / 3, 1e-9); near(avg.z, (10.0 + 10.0 + 10.02) / 3, 1e-9)
        eq(avg.epochs, 15); ok(avg.sdH!! > 0.0, "spread recorded"); ok(!job.recordsOf("P1").first { it.timestampMillis == 3L }.let { it.classification == Classification.NORMAL })
        eq(job.points.size, 2, "P1 and Q9"); eq(job.observations.count { it.name == "P1" }, 5, "3 normal + 1 check + 1 average")
        val before = job.observations.count { it.name == "P1" }
        job.store(obs(5.0, 6.0, 7.0, 6L), DuplicateAction.OVERWRITE)
        near(job.find("P1")!!.e, 5.0, 1e-12); eq(job.observations.count { it.name == "P1" }, 1, "overwritten records are no longer observations")
        eq(job.recordsOf("P1").size, before + 1, "but they stay in the job as deleted records"); ok(job.recordsOf("P1").count { it.deleted } == before)
        // saving and reloading keeps everything, including deleted records
        val back = JobJson.fromJson(JobJson.toJson(job))
        eq(back.recordsOf("P1").size, job.recordsOf("P1").size); near(back.find("P1")!!.e, 5.0, 1e-12); eq(back.observations.size, job.observations.size)
        ok(back.recordsOf("P1").count { it.deleted } == before)
        // delete one observation by index
        ok(back.removeRecord("P1", back.recordsOf("P1").size - 1)); ok(!back.has("P1"), "no normal observation left")
    }
    check("job files: single-observation jobs stay in the old layout, and old files load") {
        val job = Job("Old", utm18); job.add(SurveyPoint("1", e = 1.0, n = 2.0, z = 3.0)); job.add(SurveyPoint("2", e = 4.0, n = 5.0, z = 6.0))
        val text = JobJson.toJson(job)
        ok(!text.contains("observations"), "no observations key without repeats")
        val back = JobJson.fromJson(text); eq(back.points.size, 2); near(back.find("2")!!.n, 5.0, 1e-12)
        job.store(SurveyPoint("1", e = 1.5, n = 2.0, z = 3.0), DuplicateAction.STORE_ANOTHER)
        val t2 = JobJson.toJson(job); ok(t2.contains("observations"))
        val m = com.openfield.core.json.Json.parseObject(t2)
        eq(m["points"].asList().size, 2, "points list still holds one position per name, readable by older versions")
        eq(JobJson.fromJson(t2).observations.size, 3)
        job.transformAll { it.copy(e = it.e + 10.0) }; near(job.find("1")!!.e, 11.5, 1e-12); near(job.recordsOf("1")[0].e, 11.0, 1e-12)
    }
    check("JobXML writes every observation with its classification") {
        val job = Job("J", utm18)
        fun obs(e: Double) = SurveyPoint("7", e = e, n = 1.0, z = 1.0, latDeg = 40.0, lonDeg = -74.0, ellipsoidalHeight = 5.0, timestampMillis = 1700000000000L)
        job.store(obs(1.0)); job.store(obs(1.1), DuplicateAction.STORE_AS_CHECK); job.store(obs(1.2), DuplicateAction.AVERAGE)
        job.store(SurveyPoint("8", e = 0.0, n = 0.0, z = 0.0)); job.store(SurveyPoint("8", e = 9.0, n = 9.0, z = 9.0), DuplicateAction.OVERWRITE)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(JobXmlExport.export(job).byteInputStream())
        val recs = doc.getElementsByTagName("PointRecord")
        eq(recs.length, 5, "7: normal, check, normal, averaged; 8: only the overwriting one")
        val cls = (0 until recs.length).map { (recs.item(it) as org.w3c.dom.Element).getElementsByTagName("Classification").item(0).textContent }
        eq(cls, listOf("Normal", "Check", "Normal", "Normal", "Normal"))
        ok((0 until recs.length).any { (recs.item(it) as org.w3c.dom.Element).getElementsByTagName("Method").item(0).textContent == "Averaged" })
    }
    check("continuous topo by distance") {
        val job = Job("T", utm18); val stored = ArrayList<SurveyPoint>()
        val style = SurveyStyle("s", "s", continuousMode = ContinuousMode.DISTANCE, continuousInterval = 2.0)
        val eng = makeEngine(job, style, stored)
        val sim = NmeaSimulator(40.74844, -73.985664, 30.0, -34.0); sim.velEast = 1.0
        var now = 0L
        val driver = NmeaDriver(); val sink = object : DriverSink { override fun snapshot(s: GnssSnapshot) { eng.onSnapshot(s.copy(receivedAtMillis = now)) }; override fun rawLine(line: String) {} }
        eng.startContinuous("CL", 2.0, HeightMeasurement.VERTICAL_TO_ARP)
        repeat(12) { now += 1000; val b = sim.epoch().toByteArray(); driver.onData(b, b.size, sink) }
        eng.stopContinuous()
        ok(stored.size in 4..7, "expected about 6 points for 12 m at 2 m spacing, got ${stored.size}")
        ok(stored.all { it.method == MeasureMethod.CONTINUOUS && it.code == "CL" })
        for (i in 1 until stored.size) ok(hypot(stored[i].e - stored[i - 1].e, stored[i].n - stored[i - 1].n) >= 1.95, "spacing")
    }
    check("keyed-in point gets geodetic coordinates") {
        val job = Job("T", utm18); val eng = makeEngine(job, SurveyStyle("s", "s"), ArrayList())
        val p = eng.keyIn("K1", "CP", truthE, truthN, 30.0, PointKind.CONTROL)
        near(p.latDeg!!, 40.74844, 1e-8); near(p.lonDeg!!, -73.985664, 1e-8)
    }

    println("== Receiver, drivers, devices ==")
    check("NMEA driver tolerates split chunks, binary noise and init framing") {
        val written = ByteArrayOutputStream(); val logs = ArrayList<String>()
        val io = object : DriverIO { override fun write(bytes: ByteArray) { written.write(bytes) }; override fun log(message: String) { logs.add(message) } }
        val d = NmeaDriver(initCommands = listOf("\$PUBX,40,GST,0,1,0,0,0,0", "\$PAIR062,3,1*3F\r\n"))
        d.onOpen(io)
        val out = written.toString(Charsets.ISO_8859_1)
        ok(out.startsWith("\$PUBX,40,GST,0,1,0,0,0,0*") && out.contains("\r\n"), "checksum appended: $out")
        ok(out.contains("\$PAIR062,3,1*3F"), "explicit checksum left alone")
        val snaps = ArrayList<GnssSnapshot>()
        val sink = object : DriverSink { override fun snapshot(s: GnssSnapshot) { snaps.add(s) }; override fun rawLine(line: String) {} }
        val sim = NmeaSimulator(10.0, 20.0, 5.0)
        val data = byteArrayOf(0xD3.toByte(), 0x00, 0x13, 0x3E, 0xD7.toByte()) + sim.epoch().toByteArray() + byteArrayOf(0xD3.toByte(), 0x01) + sim.epoch().toByteArray()
        var i = 0; while (i < data.size) { val n = minOf(7, data.size - i); d.onData(data.copyOfRange(i, i + n), n, sink); i += n }
        eq(snaps.size, 2)
    }
    check("receiver session over TCP, with write path") {
        val server = ServerSocket(0); val received = ByteArrayOutputStream()
        val srv = Thread {
            server.accept().use { s ->
                val sim = NmeaSimulator(40.0, -75.0, 10.0)
                s.getOutputStream().write((sim.epoch() + sim.epoch() + sim.epoch()).toByteArray()); s.getOutputStream().flush()
                s.soTimeout = 3000
                try { val b = ByteArray(64); val n = s.getInputStream().read(b); if (n > 0) received.write(b, 0, n) } catch (_: Exception) {}
            }
        }.also { it.start() }
        val snaps = CopyOnWriteArrayList<GnssSnapshot>(); val states = CopyOnWriteArrayList<ConnectionState>()
        val session = ReceiverSession(TcpTransport("127.0.0.1", server.localPort), NmeaDriver(), object : ReceiverListener {
            override fun onSnapshot(snapshot: GnssSnapshot) { snaps.add(snapshot) }
            override fun onState(state: ConnectionState, message: String?) { states.add(state) }
        }, autoReconnect = false)
        session.start()
        ok(waitFor { snaps.size >= 3 }, "snapshots: ${snaps.size}")
        ok(session.write(byteArrayOf(1, 2, 3, 4)), "write while connected")
        ok(waitFor { received.size() == 4 }, "server received ${received.size()} bytes")
        ok(ConnectionState.CONNECTED in states)
        session.stop(); server.close(); srv.join(2000)
        eq(session.write(byteArrayOf(1)), false, "write after stop")
    }
    check("receiver session reconnects after the link drops") {
        val server = ServerSocket(0); var accepts = 0
        val srv = Thread {
            while (accepts < 2) {
                val s = try { server.accept() } catch (_: Exception) { break }
                accepts++
                val sim = NmeaSimulator(40.0, -75.0, 10.0); s.getOutputStream().write(sim.epoch().toByteArray()); s.getOutputStream().flush(); Thread.sleep(100); s.close()
            }
        }.also { it.start() }
        val snaps = CopyOnWriteArrayList<GnssSnapshot>()
        val session = ReceiverSession(TcpTransport("127.0.0.1", server.localPort), NmeaDriver(), object : ReceiverListener { override fun onSnapshot(snapshot: GnssSnapshot) { snaps.add(snapshot) } }, true, 150)
        session.start(); ok(waitFor(6000) { snaps.size >= 2 }, "reconnect delivered ${snaps.size}")
        session.stop(); server.close()
    }
    check("simulator stream drives a session through StreamTransport") {
        val sim = NmeaSimulator(40.0, -75.0, 10.0); val snaps = CopyOnWriteArrayList<GnssSnapshot>()
        val session = ReceiverSession(StreamTransport(sim.asStream(rateHz = 50.0), ByteArrayOutputStream()), NmeaDriver(), object : ReceiverListener { override fun onSnapshot(snapshot: GnssSnapshot) { snaps.add(snapshot) } }, autoReconnect = false)
        session.start(); ok(waitFor { snaps.size >= 3 }); session.stop()
    }
    check("device profile JSON round trip, validation and builtin library") {
        val p = DeviceProfile("my-rx", "My receiver", "ACME", ConnectionType.TCP, "10.0.0.5", 5017, 9600, initCommands = listOf("\$CMD,1"), qualityMap = mapOf(5 to SolutionType.RTK_FIXED),
            antenna = AntennaProfile("AX-1", 0.04, 0.06, 0.1), notes = "n")
        eq(DeviceProfile.fromJson(p.toJson()), p); eq(p.validate(), emptyList())
        ok(p.copy(address = "", port = 0).validate().isNotEmpty()); ok(p.copy(id = "bad id!").validate().isNotEmpty())
        ok(DeviceProfile.builtin.all { it.validate().isEmpty() }, "built-ins valid")
        ok(DeviceProfile.fromJson("{\"id\":\"x\",\"name\":\"X\"}").connection == ConnectionType.BLUETOOTH_SPP, "defaults when sparse")
    }
    class FakeRuntime : ScriptRuntime {
        val exposed = HashMap<String, Map<String, (List<Any?>) -> Any?>>(); var source = ""; var closed = false
        override fun load(source: String) { this.source = source }
        override fun hasFunction(name: String) = name == "onOpen" || name == "onData"
        override fun expose(name: String, functions: Map<String, (List<Any?>) -> Any?>) { exposed[name] = functions }
        override fun close() { closed = true }
        override fun call(function: String, vararg args: Any?): Any? = when (function) {
            "onOpen" -> { exposed["io"]!!["write"]!!(listOf(listOf(0xB5, 0x62))); exposed["io"]!!["log"]!!(listOf("hello")); null }
            "onData" -> listOf(
                mapOf("type" to "fix", "lat" to 40.0, "lon" to -75.0, "altMsl" to 100.0, "geoidSep" to -30.0, "solution" to "RTK_FIXED", "sats" to 12, "hdop" to 0.8, "sigmaLat" to 0.01, "sigmaLon" to 0.01, "sigmaAlt" to 0.02),
                mapOf("type" to "nmea", "sentence" to NmeaParser.build("GNGGA,120000.00,4100.0000,N,07500.0000,W,5,14,0.9,100.0,M,0.0,M,,")),
                mapOf("type" to "line", "text" to "raw"))
            else -> null
        }
    }
    check("script driver adapts JavaScript-style events") {
        val rt = FakeRuntime(); val written = ByteArrayOutputStream(); val logs = ArrayList<String>(); val snaps = ArrayList<GnssSnapshot>(); val lines = ArrayList<String>()
        val profile = DeviceProfile("scripted", "Scripted", protocol = "script", scriptFile = "rx.js", qualityMap = mapOf(5 to SolutionType.RTK_FLOAT))
        val d = profile.createDriver({ rt }, { "// js source for $it" })
        d.onOpen(object : DriverIO { override fun write(bytes: ByteArray) { written.write(bytes) }; override fun log(message: String) { logs.add(message) } })
        eq(written.toByteArray().toList(), listOf(0xB5.toByte(), 0x62.toByte())); eq(logs, listOf("hello")); eq(rt.source, "// js source for rx.js")
        d.onData(byteArrayOf(1, 2, 3), 3, object : DriverSink { override fun snapshot(s: GnssSnapshot) { snaps.add(s) }; override fun rawLine(line: String) { lines.add(line) } })
        eq(snaps.size, 2); eq(snaps[0].solution, SolutionType.RTK_FIXED); near(snaps[0].horizontalPrecision!!, hypot(0.01, 0.01), 1e-12)
        eq(snaps[1].solution, SolutionType.RTK_FLOAT, "profile quality map applies to nmea events"); ok("raw" in lines)
        d.onClose(); ok(rt.closed)
        var threw = false; try { profile.createDriver(null) } catch (e: IllegalStateException) { threw = true }; ok(threw, "script profile without engine must fail clearly")
    }

    println("== NTRIP ==")
    class MockCaster(val user: String = "u", val pass: String = "p", val chunked: Boolean = false) {
        val server = ServerSocket(0); val gga = CopyOnWriteArrayList<String>(); @Volatile var lastRequest = ""
        val table = "SOURCETABLE 200 OK\r\nServer: mock\r\nContent-Type: text/plain\r\n\r\nSTR;MOUNT1;City;RTCM 3.2;1004(1),1006(10);2;GPS+GLO;NET;USA;40.70;-74.00;1;0;mock;B;N;9600;\r\nSTR;VRS2;Other;RTCM 3.3;1077(1);2;GNSS;NET;USA;41.00;-75.00;1;1;mock;B;Y;9600;\r\nENDSOURCETABLE\r\n"
        val thread = Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread { handle(s) }.start()
            }
        }.also { it.isDaemon = true; it.start() }
        private fun handle(s: java.net.Socket) {
            try {
                val inp = s.getInputStream(); val out = s.getOutputStream()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return; sb.append(b.toChar()) }
                lastRequest = sb.toString()
                val path = sb.lines()[0].split(" ")[1]
                val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                if (!sb.contains(expected)) { out.write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray()); out.flush(); s.close(); return }
                if (path == "/") { out.write(table.toByteArray()); out.flush(); s.close(); return }
                if (path != "/MOUNT1") { out.write(table.toByteArray()); out.flush(); s.close(); return }
                val payload = ByteArray(300) { (it % 251).toByte() }
                if (chunked) {
                    out.write("HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    out.write("${payload.size.toString(16)}\r\n".toByteArray()); out.write(payload); out.write("\r\n".toByteArray())
                } else {
                    out.write("ICY 200 OK\r\n\r\n".toByteArray()); out.write(payload)
                }
                out.flush()
                s.soTimeout = 3000
                val br = inp.bufferedReader()
                try { while (true) { val l = br.readLine() ?: break; if (l.startsWith("\$GPGGA")) gga.add(l) } } catch (_: Exception) {}
                s.close()
            } catch (_: Exception) {}
        }
        fun close() { server.close() }
    }
    check("sourcetable download and parsing") {
        val c = MockCaster()
        val t = NtripClient(NtripProfile("t", "127.0.0.1", c.server.localPort, "", "u", "p")).fetchSourcetable()
        eq(t.size, 2); eq(t[0].name, "MOUNT1"); eq(t[0].format, "RTCM 3.2"); near(t[0].latitude!!, 40.70, 1e-9); eq(t[0].nmeaRequired, true); eq(t[1].networkSolution, true); eq(t[1].fee, true)
        ok(c.lastRequest.contains("Ntrip-Version: Ntrip/2.0")); c.close()
    }
    check("wrong credentials fail fast with a clear message") {
        val c = MockCaster()
        var msg: String? = null
        try { NtripClient(NtripProfile("t", "127.0.0.1", c.server.localPort, "", "u", "bad")).fetchSourcetable() } catch (e: NtripException) { msg = e.message }
        ok(msg?.contains("401") == true, "got $msg")
        val states = CopyOnWriteArrayList<NtripState>()
        val cl = NtripClient(NtripProfile("t", "127.0.0.1", c.server.localPort, "MOUNT1", "u", "bad"))
        cl.start(object : NtripListener { override fun onData(buf: ByteArray, len: Int) {}; override fun onState(state: NtripState, message: String?) { states.add(state) } })
        ok(waitFor { NtripState.STOPPED in states }, "client must stop retrying on 401: $states"); ok(NtripState.ERROR in states); c.close()
    }
    for (chunked in listOf(false, true)) check("stream RTCM and uplink GGA (${if (chunked) "chunked v2" else "ICY v1"})") {
        val c = MockCaster(chunked = chunked); val got = ByteArrayOutputStream(); val states = CopyOnWriteArrayList<NtripState>()
        val cl = NtripClient(NtripProfile("t", "127.0.0.1", c.server.localPort, "MOUNT1", "u", "p", ggaIntervalSeconds = 1, ntripVersion = if (chunked) 2 else 1))
        cl.setGgaProvider { NtripClient.ggaFor(40.0, -75.0, 10.0, -30.0, 43200.0, 12, 4, 0.9) }
        cl.start(object : NtripListener {
            override fun onData(buf: ByteArray, len: Int) { synchronized(got) { got.write(buf, 0, len) } }
            override fun onState(state: NtripState, message: String?) { states.add(state) }
        })
        ok(waitFor { synchronized(got) { got.size() } >= 300 }, "received ${got.size()} bytes")
        eq(synchronized(got) { got.toByteArray() }.take(300), ByteArray(300) { (it % 251).toByte() }.toList(), "payload intact (no header or chunk framing leaked)")
        ok(waitFor { c.gga.isNotEmpty() }, "GGA uplink"); ok(NmeaParser.parse(c.gga[0]) is Gga, "uplinked GGA valid")
        cl.stop(); ok(NtripState.CONNECTED in states); c.close()
    }

    println("== Import / export / persistence ==")
    val sample = Job("Demo", utm18.copy(unit = LinearUnit.US_SURVEY_FOOT), "desc").also { j ->
        j.add(SurveyPoint("1", "TREE", e = 1000.5, n = 2000.25, z = 30.125, latDeg = 40.0, lonDeg = -75.0, ellipsoidalHeight = 5.0, solution = SolutionType.RTK_FIXED, hdop = 0.8, epochs = 5, timestampMillis = 1700000000000))
        j.add(SurveyPoint("2", "NOTE,\"X\"", note = "has, comma", e = 1010.0, n = 2010.0, z = 31.0))
        j.lines.add(DesignLine("L1", 0.0, 0.0, null, 10.0, 10.0, 5.0, 100.0))
    }
    check("job JSON round trip") {
        val back = JobJson.fromJson(JobJson.toJson(sample))
        eq(back.name, "Demo"); eq(back.points, sample.points); eq(back.coordinateSystem.unit, LinearUnit.US_SURVEY_FOOT)
        eq(back.nextPointName, sample.nextPointName); eq(back.lines.size, 1); near(back.lines[0].startStation, 100.0, 0.0)
        var threw = false; try { JobJson.fromJson("{\"format\":\"other\"}") } catch (e: IllegalArgumentException) { threw = true }; ok(threw)
    }
    check("CSV export and re-import with quoting, header and unit conversion") {
        val csv = CsvIO.export(sample, CsvFormat.PNEZD)
        val lines = csv.trim().lines(); eq(lines[0], "1,2000.250,1000.500,30.125,TREE"); ok(lines[1].endsWith("\"NOTE,\"\"X\"\"\""), lines[1])
        val back = CsvIO.import(csv, CsvFormat.PNEZD, LinearUnit.US_SURVEY_FOOT)
        eq(back.errors, emptyList()); eq(back.points.size, 2); near(back.points[0].e, 1000.5, 1e-9); eq(back.points[1].code, "NOTE,\"X\"")
        val m = CsvIO.export(sample, CsvFormat.PENZD.copy(unit = LinearUnit.METER, decimals = 4)).lines()[0].split(',')
        near(m[1].toDouble(), 1000.5 * 1200.0 / 3937.0, 1e-4)
        val full = CsvIO.export(sample, CsvFormat.FULL); ok(full.startsWith("Name,Code,Northing")); ok(full.contains("RTK Fixed"))
        val bad = CsvIO.import("A,1,2,3,x\nB,notnum,2,3,y\n,1,1,1,z", CsvFormat.PNEZD, LinearUnit.METER); eq(bad.points.size, 1); eq(bad.errors.size, 2)
        val custom = CsvFormat.fromMap(Json.parseObject(Json.stringify(CsvFormat.FULL.copy(delimiter = ';', unit = LinearUnit.INTL_FOOT).toMap()))); eq(custom.delimiter, ';'); eq(custom.unit, LinearUnit.INTL_FOOT)
    }
    check("GeoJSON, DXF and LandXML outputs") {
        val gj = Json.parseObject(GeoJsonExport.export(sample)); eq(gj["type"], "FeatureCollection")
        val feats = gj["features"].asList(); ok(feats.size == 2, "second point's geodetic position derived from the coordinate system")
        val c0 = feats[0].asObj()["geometry"].asObj()["coordinates"].asList(); near(c0[0].asDouble(), -75.0, 1e-12); near(c0[1].asDouble(), 40.0, 1e-12)
        val dxf = DxfExport.export(sample); ok(dxf.contains("POINT") && dxf.contains("TEXT") && dxf.trim().endsWith("EOF")); ok(dxf.contains("\nVEG\n"), "feature-code layer used")
        val xml = LandXmlExport.export(sample); ok(xml.contains("<CgPoint name=\"1\" code=\"TREE\">2000.2500 1000.5000 30.1250</CgPoint>"), "N E Z order"); ok(xml.contains("USSurveyFoot"))
        ok(xml.contains("&quot;X&quot;")); ok(javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())) != null, "well-formed XML")
    }

    println("== Customization ==")
    check("user config round trip with custom theme, labels and layout") {
        val theme = ThemeSpec("mine", "Mine", ThemeBase.DARK, mapOf(ColorRole.PRIMARY to "#112233"), 1.3, 1.5, 10)
        val cfg = UserConfig(themeId = "mine", customThemes = listOf(theme), layout = LayoutSpec().updateTile(Feature.COGO) { it.copy(favorite = true) }.move(Feature.COGO, -3),
            labelOverrides = mapOf("tile.measure_topo" to "Shoot point"), coordinateOrder = CoordinateOrder.EAST_NORTH, distanceDecimals = 2)
        val back = UserConfig.fromJson(cfg.toJson())
        eq(back.theme.name, "Mine"); eq(back.theme.color(ColorRole.PRIMARY), "#112233"); eq(back.theme.color(ColorRole.BAD), ThemeSpec.night.color(ColorRole.BAD), "missing roles fall back to base theme")
        eq(back.labels.get("tile.measure_topo"), "Shoot point"); eq(back.labels.get("tile.jobs"), "Jobs"); eq(back.coordinateOrder, CoordinateOrder.EAST_NORTH)
        eq(back.layout.homeTiles.map { it.id }, cfg.layout.homeTiles.map { it.id }); ok(back.layout.visibleTiles().first().favorite)
    }
    check("theme validation and unknown theme fallback") {
        ok(ThemeSpec("x", "x", ThemeBase.LIGHT, mapOf("primary" to "blue")).validate().isNotEmpty()); ok(ThemeSpec("x", "x", ThemeBase.LIGHT, emptyMap(), fontScale = 5.0).validate().isNotEmpty())
        ok(ThemeSpec.builtin.all { it.validate().isEmpty() }); eq(UserConfig(themeId = "deleted").theme.id, "classic")
        ok(ThemeSpec.builtin.all { t -> ColorRole.all.all { t.colors.containsKey(it) } }, "built-in themes define every role")
    }
    check("layout tile management and migration of saved layouts") {
        val l = LayoutSpec(); eq(l.homeTiles.map { it.id }.toSet(), Feature.all.toSet())
        eq(l.move(Feature.JOBS, 1).homeTiles[1].id, Feature.JOBS); eq(l.move(Feature.JOBS, -1), l, "cannot move above top")
        eq(l.updateTile(Feature.COGO) { it.copy(enabled = false) }.visibleTiles().none { it.id == Feature.COGO }, true)
        val old = LayoutSpec.fromMap(mapOf("homeTiles" to listOf(mapOf("id" to Feature.JOBS))))
        eq(old.homeTiles.map { it.id }.toSet(), Feature.all.toSet(), "tiles added in newer versions appear")
        ok(Labels.defaults.keys.containsAll(Feature.all.map { "tile.$it" }), "every feature has a default label")
        ok(Feature.Group.entries.all { Labels.defaults.containsKey(it.labelKey) })
    }
    check("survey style JSON round trip and built-ins") {
        val s = SurveyStyle("x", "X", gate = QualityGate(maxHdop = null), topoEpochs = 9, continuousMode = ContinuousMode.DISTANCE, stakeoutOrientation = StakeoutOrientation.NORTH)
        eq(SurveyStyle.fromMap(Json.parseObject(Json.stringify(s.toMap()))), s)
        ok(SurveyStyle.builtin.size >= 3 && SurveyStyle.builtin.map { it.id }.toSet().size == SurveyStyle.builtin.size)
    }

    check("library: State Plane zones match PROJ (EPSG registry) to 1 mm") {
        val us = CoordSysLibrary.system("United States/NAD83")!!
        ok(us.zones.size > 100, "zone count ${us.zones.size}")
        val refs = listOf(
            Triple("Kentucky Single Zone", 37.5 to -85.0, 1566313.4879 to 1129746.4829),
            Triple("Alabama East", 32.8 to -85.6, 221853.9653 to 255039.6790),
            Triple("California zone 5", 34.2 to -118.4, 1963134.1917 to 577721.0843),
            Triple("Florida East", 28.5 to -81.2, 180420.3073 to 461633.7816),
            Triple("Texas Central", 31.0 to -99.0, 827320.9093 to 3148569.6363),
            Triple("New York Long Island", 40.8 to -73.2, 367509.9363 to 70637.5317)
        )
        for ((zn, ll, en) in refs) {
            val z = us.zones.first { it.name == zn }
            val cs = CoordSysLibrary.build(us, z, LinearUnit.METER)
            val g = cs.toLocal(LatLonH(ll.first, ll.second, 0.0))
            near(g.e, en.first, 0.001, "$zn E"); near(g.n, en.second, 0.001, "$zn N")
            val b = cs.fromLocal(g); near(b.latDeg, ll.first, 1e-9, "$zn lat"); near(b.lonDeg, ll.second, 1e-9, "$zn lon")
        }
        eq(us.zones.first { it.name == "Kentucky Single Zone" }.defaultUnit, LinearUnit.US_SURVEY_FOOT)
        eq(CoordSysLibrary.system("UTM")!!.zones.size, 120)
        ok(CoordSysLibrary.geoids.any { it.name.startsWith("GEOID18") })
    }
    check("ground coordinates: scale from project height, round trip and JSON") {
        val us = CoordSysLibrary.system("United States/NAD83")!!
        val grid = CoordSysLibrary.build(us, us.zones.first { it.name == "Kentucky Single Zone" }, LinearUnit.METER)
        val h = 152.4
        val gs = GroundSpec.at(grid, 37.5, -85.0, h)
        ok(gs.scaleFactor > 0.99 && gs.scaleFactor < 1.01, "scale ${gs.scaleFactor}")
        val cs = grid.copy(ground = gs)
        // two points 1 km apart on a plane at height h: ground coordinates must reproduce the real (ECEF) distance
        val a = LatLonH(37.5, -85.0, h); val b = LatLonH(37.5 + 1000.0 / 110900.0, -85.0 + 400.0 / 88000.0, h)
        val ea = Ellipsoid.WGS84.toEcef(a.latDeg * DEG, a.lonDeg * DEG, h); val eb = Ellipsoid.WGS84.toEcef(b.latDeg * DEG, b.lonDeg * DEG, h)
        val real = sqrt((ea[0] - eb[0]).pow(2) + (ea[1] - eb[1]).pow(2) + (ea[2] - eb[2]).pow(2))
        val ga = cs.toLocal(a); val gb = cs.toLocal(b)
        near(hypot(gb.e - ga.e, gb.n - ga.n), real, 0.003, "ground distance")
        val gridDist = hypot(grid.toLocal(b).e - grid.toLocal(a).e, grid.toLocal(b).n - grid.toLocal(a).n)
        ok(abs(gridDist - real) > 0.02, "grid distance should differ visibly from ground (${gridDist - real} m)")
        near(ga.e, grid.toLocal(a).e, 1e-6, "origin is unshifted")
        val back = cs.fromLocal(gb); near(back.latDeg, b.latDeg, 1e-9); near(back.lonDeg, b.lonDeg, 1e-9)
        val rt = CoordinateSystem.fromJson(cs.toJson())
        eq(rt.librarySystem, "United States/NAD83"); eq(rt.libraryZone, "Kentucky Single Zone"); near(rt.ground!!.scaleFactor, gs.scaleFactor, 1e-12)
        near(rt.toLocal(b).e, gb.e, 1e-6)
    }
    check("GTX geoid grid: full read, windowed read and bilinear values") {
        val bos = java.io.ByteArrayOutputStream(); val d = java.io.DataOutputStream(bos)
        d.writeDouble(30.0); d.writeDouble(250.0); d.writeDouble(0.5); d.writeDouble(0.5); d.writeInt(9); d.writeInt(9)
        for (r in 0 until 9) for (c in 0 until 9) d.writeFloat((10.0 + (30.0 + r * 0.5) * 0.1 + (250.0 + c * 0.5 - 360.0) * 0.01).toFloat())
        val bytes = bos.toByteArray()
        val full = GridGeoid.parseGtx("t", bytes.inputStream())
        near(full.separation(31.2, -109.3), 10.0 + 3.12 - 1.093, 1e-4)
        val win = GridGeoid.parseGtx("t", bytes.inputStream(), doubleArrayOf(31.0, 32.0, -109.5, -108.5))
        ok(win.rows < 9 && win.cols < 9, "window crops the grid"); near(win.separation(31.2, -109.3), 10.0 + 3.12 - 1.093, 1e-4)
        val again = GridGeoid.parseAscii("t", win.toAscii()); near(again.separation(31.2, -109.3), win.separation(31.2, -109.3), 1e-4, "ASCII round trip")
        ok(runCatching { GridGeoid.parseGtx("t", bytes.inputStream(), doubleArrayOf(60.0, 61.0, -109.5, -108.5)) }.isFailure, "outside coverage is rejected")
    }

    check("JobXML export is well-formed XML with metre values") {
        val job = Job("Test & Co", CoordinateSystem.utm(18, unit = LinearUnit.US_SURVEY_FOOT), "desc <x>")
        job.add(SurveyPoint("1", "TREE", e = 1000.0, n = 2000.0, z = 100.0, latDeg = 40.5, lonDeg = -74.0, ellipsoidalHeight = 70.0, timestampMillis = 1700000000000L))
        job.add(SurveyPoint("2", kind = PointKind.CONTROL, method = MeasureMethod.KEYED_IN, e = 1.0, n = 2.0, z = 3.0))
        val xml = JobXmlExport.export(job)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        eq(doc.documentElement.tagName, "JOBFile"); eq(doc.documentElement.getAttribute("jobName"), "Test & Co")
        val recs = doc.getElementsByTagName("PointRecord"); eq(recs.length, 2)
        near(doc.getElementsByTagName("North").item(0).textContent.toDouble(), 2000.0 * 1200.0 / 3937.0, 1e-4, "grid values are written in metres")
        eq(doc.getElementsByTagName("DistanceUnits").item(0).textContent, "USSurveyFeet")
        eq(doc.getElementsByTagName("Classification").item(1).textContent, "Control"); eq(doc.getElementsByTagName("WGS84").length, 1)
    }

    // ---------------- baseline vectors ----------------
    val baseLat = 40.0; val baseLon = -75.0; val baseH = 50.0
    val baseEcef = Ellipsoid.WGS84.toEcef(baseLat * DEG, baseLon * DEG, baseH)
    val base0 = BasePosition("1234", baseEcef[0], baseEcef[1], baseEcef[2])
    check("RTCM 1005 base position is read from a stream, with CRC protection") {
        val frame = RtcmBaseTracker.encode1005(1234, baseEcef[0], baseEcef[1], baseEcef[2])
        val t = RtcmBaseTracker()
        t.feed(byteArrayOf(0x24, 0xD3.toByte(), 0x00, 0x05, 1, 2))                       // garbage that looks like a frame start
        val bad = frame.copyOf(); bad[10] = (bad[10].toInt() xor 0x40).toByte()
        t.feed(bad); ok(t.base == null, "corrupted frame is rejected")
        val half = frame.size / 2
        t.feed(frame, half); ok(t.base == null); t.feed(frame.copyOfRange(half, frame.size))
        val b = t.base!!; eq(b.id, "1234")
        near(b.x, baseEcef[0], 1e-4); near(b.y, baseEcef[1], 1e-4); near(b.z, baseEcef[2], 1e-4)
        val south = RtcmBaseTracker.encode1005(7, -2000000.0, 3000000.0, -5000000.5)       // negative values need sign extension
        t.feed(south); near(t.base!!.x, -2000000.0, 1e-4); near(t.base!!.z, -5000000.5, 1e-4); eq(t.base!!.id, "7")
        eq(RtcmBaseTracker.crc24q("123456789".toByteArray(), 0, 9), 0xCDE703, )
    }
    check("baseline vectors: derive, local frame, covariance, averaging") {
        val roverEcef = Ellipsoid.WGS84.toEcef(baseLat * DEG, baseLon * DEG, baseH).let { b ->
            // rover 300 m east, 400 m north, 2 m up of the base in the base's local frame
            val v = BaselineVector.fromEnu(base0, 300.0, 400.0, 2.0)
            doubleArrayOf(b[0] + v.dX, b[1] + v.dY, b[2] + v.dZ)
        }
        val g = Ellipsoid.WGS84.fromEcef(roverEcef[0], roverEcef[1], roverEcef[2])
        val s = GnssSnapshot(1.0, 0L, g[0] / DEG, g[1] / DEG, g[2], 0.0, SolutionType.RTK_FIXED, 12, 1.0, 1.5, 1.0, 0.01, 0.01, 0.02, 1.0, null, null, null)
        val v = BaselineVector.derive(base0, s)
        val enu = v.enu(); near(enu[0], 300.0, 1e-6); near(enu[1], 400.0, 1e-6); near(enu[2], 2.0, 1e-6)
        near(v.length, sqrt(300.0 * 300 + 400.0 * 400 + 4.0), 1e-6)
        near(v.roverX, roverEcef[0], 1e-6); near(v.roverZ, roverEcef[2], 1e-6)
        val cov = v.cov!!
        near(cov[0] + cov[3] + cov[5], 0.01 * 0.01 + 0.01 * 0.01 + 0.02 * 0.02, 1e-12, "covariance trace is rotation invariant")
        ok(cov[1] != 0.0 || cov[2] != 0.0, "rotated covariance has off-diagonal terms")
        val a = v.copy(dX = v.dX + 0.002); val b = v.copy(dX = v.dX - 0.002, dZ = v.dZ + 0.004)
        val m = BaselineVector.average(listOf(a, b))!!; near(m.dX, v.dX, 1e-9); near(m.dZ, v.dZ + 0.002, 1e-9)
        ok(BaselineVector.average(listOf(a, b.copy(baseId = "9"))) == null, "different base: no average")
        ok(BaselineVector.average(listOf(a, b.copy(baseX = b.baseX + 5.0))) == null, "moved base: no average")
        ok(runCatching { BaselineVector("1", 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, listOf(1.0)) }.isFailure, "covariance must have 6 values")
    }
    check("a session attaches derived and driver-reported baselines to RTK fixes") {
        val sim = NmeaSimulator(40.0, -75.0, 10.0); val snaps = CopyOnWriteArrayList<GnssSnapshot>()
        val session = ReceiverSession(StreamTransport(sim.asStream(rateHz = 50.0), ByteArrayOutputStream()), NmeaDriver(),
            object : ReceiverListener { override fun onSnapshot(snapshot: GnssSnapshot) { snaps.add(snapshot) } }, autoReconnect = false, baseProvider = { base0 })
        session.start(); ok(waitFor { snaps.size >= 3 }); session.stop()
        val s = snaps.first { it.baseline != null }
        eq(s.baseline!!.baseId, "1234"); ok(s.baseline!!.length in 30.0..50.0, "plausible length ${s.baseline!!.length}")
        near(s.baseline!!.roverX, Ellipsoid.WGS84.toEcef(s.latDeg * DEG, s.lonDeg * DEG, s.ellipsoidalHeight)[0], 1e-6)
        // a driver that reports its own vector wins over the derived one
        val reported = BaselineVector("rx", 1.0, 2.0, 3.0, 0.5, 0.5, 0.5, null, "driver")
        val out = CopyOnWriteArrayList<GnssSnapshot>()
        val drv = object : ReceiverDriver {
            override val id = "t"
            override fun onData(buf: ByteArray, len: Int, sink: DriverSink) { sink.baseline(reported); sink.snapshot(snaps[0].copy(baseline = null)) }
        }
        val s2 = ReceiverSession(StreamTransport(ByteArrayInputStream(ByteArray(8)), ByteArrayOutputStream()), drv,
            object : ReceiverListener { override fun onSnapshot(snapshot: GnssSnapshot) { out.add(snapshot) } }, autoReconnect = false, baseProvider = { base0 })
        s2.start(); ok(waitFor { out.isNotEmpty() }); s2.stop()
        eq(out[0].baseline, reported)
    }
    check("script drivers can report baselines, in ECEF or local NED") {
        val events = listOf<Any?>(
            mapOf("type" to "baseline", "baseId" to "55", "base" to mapOf("x" to baseEcef[0], "y" to baseEcef[1], "z" to baseEcef[2]), "d" to mapOf("x" to 10.0, "y" to 20.0, "z" to 30.0), "sigma" to mapOf("x" to 0.01, "y" to 0.02, "z" to 0.03)),
            mapOf("type" to "baseline", "baseId" to "55", "base" to mapOf("x" to baseEcef[0], "y" to baseEcef[1], "z" to baseEcef[2]), "ned" to mapOf("n" to 400.0, "e" to 300.0, "d" to -2.0), "sigmaNed" to mapOf("n" to 0.01, "e" to 0.01, "d" to 0.02)),
            mapOf("type" to "baseline", "baseId" to "55", "d" to mapOf("x" to 1.0, "y" to 1.0, "z" to 1.0))     // no base: ignored
        )
        val rt = object : ScriptRuntime {
            override fun load(source: String) {}
            override fun hasFunction(name: String) = false
            override fun call(function: String, vararg args: Any?): Any? = events
            override fun expose(name: String, functions: Map<String, (List<Any?>) -> Any?>) {}
            override fun close() {}
        }
        val got = ArrayList<BaselineVector>()
        val sink = object : DriverSink { override fun snapshot(s: GnssSnapshot) {}; override fun rawLine(line: String) {}; override fun baseline(v: BaselineVector) { got.add(v) } }
        val drv = ScriptReceiverDriver("js", rt, ""); drv.onOpen(object : DriverIO { override fun write(bytes: ByteArray) {}; override fun log(message: String) {} })
        drv.onData(ByteArray(1), 1, sink)
        eq(got.size, 2)
        near(got[0].dX, 10.0, 1e-12); near(got[0].sigmaXyz()!![1], 0.02, 1e-12)
        val enu = got[1].enu(); near(enu[0], 300.0, 1e-6); near(enu[1], 400.0, 1e-6); near(enu[2], 2.0, 1e-6, "NED down -2 means 2 m up")
    }
    check("measured points record the averaged vector; it survives saving, averaging and export") {
        val job = Job("V", utm18); val stored = ArrayList<SurveyPoint>()
        val eng = makeEngine(job, SurveyStyle("s", "s", topoEpochs = 3), stored)
        val sim = NmeaSimulator(40.0, -75.0, 10.0)
        val driver = NmeaDriver()
        val sink = object : DriverSink { override fun snapshot(s: GnssSnapshot) { val s1 = s.copy(sigmaLat = 0.01, sigmaLon = 0.01, sigmaAlt = 0.02, solution = SolutionType.RTK_FIXED); eng.onSnapshot(s1.copy(baseline = BaselineVector.derive(base0, s1))) }; override fun rawLine(line: String) {} }
        val spec = MeasureSpec(name = "V1", antennaHeightMeasured = 2.0, heightMethod = HeightMeasurement.VERTICAL_TO_ARP, onDuplicate = null)
        fun measure() { eng.startMeasure(spec); repeat(5) { val b = sim.epoch().toByteArray(); driver.onData(b, b.size, sink) } }
        measure(); val p = stored[0]; val v = p.vector!!
        eq(v.baseId, "1234"); ok(v.length > 30.0, "len ${v.length}"); ok(v.cov != null, "cov")
        near(v.roverX - v.baseX, v.dX, 1e-9)
        measure(); eng.resolveDuplicate(DuplicateAction.AVERAGE)
        ok(job.find("V1")!!.vector != null, "avg vector"); ok(job.find("V1")!!.method == MeasureMethod.AVERAGED, "avg method ${job.find("V1")!!.method}")
        val back = JobJson.fromJson(JobJson.toJson(job))
        val bv = back.observations.first().vector!!
        near(bv.dX, v.dX, 1e-9); near(bv.baseZ, v.baseZ, 1e-9); eq(bv.cov, v.cov); eq(bv.baseId, "1234")
        val csv = VectorCsvExport.export(job).trim().lines()
        eq(csv.size, 1 + job.observations.size); ok(csv[0].startsWith("Point,Observation,Class,Base ID"), "hdr"); ok(csv[1].startsWith("V1,1,Normal,1234,"), csv[1])
        eq(csv[1].split(",").size, csv[0].split(",").size)
        ok(csv.last().startsWith("V1,3,"), csv.last())
    }

    check("JobXML carries each observation's ECEF vector and covariance") {
        val job = Job("VJ", utm18)
        val v = BaselineVector("1234", base0.x, base0.y, base0.z, 10.12345, -20.5, 30.25, listOf(1e-4, 1e-5, 2e-5, 2e-4, 3e-5, 4e-4))
        job.store(SurveyPoint("A", e = 100.0, n = 200.0, z = 10.0, vector = v, latDeg = 40.0, lonDeg = -75.0, ellipsoidalHeight = 10.0), DuplicateAction.STORE_ANOTHER)
        job.store(SurveyPoint("B", e = 101.0, n = 201.0, z = 10.0), DuplicateAction.STORE_ANOTHER)
        val xml = JobXmlExport.export(job)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val recs = doc.getElementsByTagName("PointRecord"); eq(recs.length, 2)
        fun tag(n: org.w3c.dom.Element, t: String) = n.getElementsByTagName(t).let { if (it.length == 0) null else it.item(0).textContent }
        val a = recs.item(0) as org.w3c.dom.Element; val b = recs.item(1) as org.w3c.dom.Element
        eq(tag(a, "DeltaX"), "10.12345"); eq(tag(a, "DeltaY"), "-20.50000"); eq(tag(a, "DeltaZ"), "30.25000"); eq(tag(a, "RTK_Base"), "1234")
        near(tag(a, "VCVxy")!!.toDouble(), 1e-5, 1e-15); near(tag(a, "VCVzz")!!.toDouble(), 4e-4, 1e-15); eq(tag(a, "UnitVariance")!!.toDouble(), 1.0)
        ok(tag(b, "DeltaX") == null && tag(b, "QualityControl2") == null, "points without a vector get no vector elements")
    }

    println()
    println("$passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) { failures.forEach { println("  - $it") }; System.exit(1) }
}
