package com.openfield.core.io

import com.openfield.core.geo.CoordinateSystem
import com.openfield.core.geo.GeoidModel
import com.openfield.core.gnss.BaselineVector
import com.openfield.core.gnss.SolutionType
import com.openfield.core.json.*
import com.openfield.core.survey.*

/** Native job file format (.ofjob, plain JSON). Human-readable and diff-able; versioned for future migrations. */
object JobJson {
    const val VERSION = 1

    fun toJson(job: Job): String = Json.stringify(toMap(job))

    fun toMap(job: Job): Map<String, Any?> = linkedMapOf<String, Any?>(
        "format" to "openfield-job", "version" to VERSION,
        "name" to job.name, "description" to job.description, "operator" to job.operator,
        "created" to job.createdMillis, "modified" to job.modifiedMillis,
        "styleId" to job.styleId, "nextPointName" to job.nextPointName,
        "coordinateSystem" to job.coordinateSystem.toMap(),
        // "points" holds one position per name, as in older files, so earlier versions can still open a job.
        "points" to job.points.map { pointMap(it) },
        "lines" to job.lines.map { l ->
            mapOf("name" to l.name, "e1" to l.e1, "n1" to l.n1, "z1" to l.z1, "e2" to l.e2, "n2" to l.n2, "z2" to l.z2, "startStation" to l.startStation)
        }
    ).also { m ->
        // Only jobs with repeated observations carry the full history, so simple jobs stay identical to earlier files.
        if (job.hasMultipleObservations) m["observations"] = job.allRecords().map { pointMap(it) }
    }

    private fun pointMap(p: SurveyPoint): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("name" to p.name, "code" to p.code, "kind" to p.kind.name, "method" to p.method.name, "e" to p.e, "n" to p.n, "z" to p.z)
        fun put(k: String, v: Any?) { if (v != null) m[k] = v }
        put("lat", p.latDeg); put("lon", p.lonDeg); put("ellH", p.ellipsoidalHeight); put("solution", p.solution?.name)
        put("hdop", p.hdop); put("pdop", p.pdop); put("sigmaH", p.sigmaH); put("sigmaV", p.sigmaV); put("sats", p.satellites)
        m["epochs"] = p.epochs; put("antennaHeight", p.antennaHeight); put("sdH", p.sdH); put("sdV", p.sdV)
        m["time"] = p.timestampMillis
        if (p.note.isNotEmpty()) m["note"] = p.note
        if (p.deviceId.isNotEmpty()) m["device"] = p.deviceId
        p.vector?.let { v ->
            m["vector"] = linkedMapOf<String, Any?>(
                "base" to v.baseId, "baseXyz" to listOf(v.baseX, v.baseY, v.baseZ), "dXyz" to listOf(v.dX, v.dY, v.dZ), "source" to v.source
            ).also { vm -> v.cov?.let { vm["cov"] = it } }
        }
        if (p.classification != Classification.NORMAL) m["class"] = p.classification.name
        if (p.deleted) m["deleted"] = true
        return m
    }

    fun fromJson(text: String, geoidResolver: (String) -> GeoidModel? = { null }): Job {
        val m = Json.parseObject(text)
        require(m["format"].asStr() == "openfield-job") { "Not an OpenField job file" }
        val job = Job(m["name"].asStr("Untitled"), CoordinateSystem.fromMap(m["coordinateSystem"].asObj(), geoidResolver),
            m["description"].asStr(), m["created"].asLong(System.currentTimeMillis()), m["operator"].asStr())
        job.styleId = m["styleId"].asStr()
        val saved = if (m.containsKey("observations")) m["observations"].asList() else m["points"].asList()
        for (pm in saved) {
            val o = pm.asObj()
            job.restore(SurveyPoint(
                name = o["name"].asStr(), code = o["code"].asStr(),
                kind = runCatching { PointKind.valueOf(o["kind"].asStr()) }.getOrDefault(PointKind.MEASURED),
                method = runCatching { MeasureMethod.valueOf(o["method"].asStr()) }.getOrDefault(MeasureMethod.TOPO),
                e = o["e"].asDouble(), n = o["n"].asDouble(), z = o["z"].asDouble(),
                latDeg = o["lat"].asDoubleOrNull(), lonDeg = o["lon"].asDoubleOrNull(), ellipsoidalHeight = o["ellH"].asDoubleOrNull(),
                solution = o["solution"].asStrOrNull()?.let { runCatching { SolutionType.valueOf(it) }.getOrNull() },
                hdop = o["hdop"].asDoubleOrNull(), pdop = o["pdop"].asDoubleOrNull(), sigmaH = o["sigmaH"].asDoubleOrNull(), sigmaV = o["sigmaV"].asDoubleOrNull(),
                satellites = o["sats"]?.asInt(), epochs = o["epochs"].asInt(), antennaHeight = o["antennaHeight"].asDoubleOrNull(),
                sdH = o["sdH"].asDoubleOrNull(), sdV = o["sdV"].asDoubleOrNull(), timestampMillis = o["time"].asLong(),
                note = o["note"].asStr(), deviceId = o["device"].asStr(),
                vector = o["vector"]?.asObj()?.let { vo ->
                    val b = vo["baseXyz"].asList().map { it.asDouble() }; val d = vo["dXyz"].asList().map { it.asDouble() }
                    val c = if (vo.containsKey("cov")) vo["cov"].asList().map { it.asDouble() } else null
                    if (b.size == 3 && d.size == 3 && (c == null || c.size == 6)) BaselineVector(vo["base"].asStr(), b[0], b[1], b[2], d[0], d[1], d[2], c, vo["source"].asStr("saved")) else null
                },
                classification = runCatching { Classification.valueOf(o["class"].asStr()) }.getOrDefault(Classification.NORMAL),
                deleted = o["deleted"].asBool(false)
            ))
        }
        job.rebuildPositions()
        for (lm in m["lines"].asList()) {
            val o = lm.asObj()
            job.lines.add(DesignLine(o["name"].asStr(), o["e1"].asDouble(), o["n1"].asDouble(), o["z1"].asDoubleOrNull(),
                o["e2"].asDouble(), o["n2"].asDouble(), o["z2"].asDoubleOrNull(), o["startStation"].asDouble()))
        }
        job.nextPointName = m["nextPointName"].asStr("1")
        return job
    }
}
