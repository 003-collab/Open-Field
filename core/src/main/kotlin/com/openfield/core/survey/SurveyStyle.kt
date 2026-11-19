package com.openfield.core.survey

import com.openfield.core.device.HeightMeasurement
import com.openfield.core.gnss.GnssSnapshot
import com.openfield.core.gnss.SolutionType
import com.openfield.core.json.*

/**
 * Acceptance criteria for an observation. Every limit is optional (null = not checked). Distances in metres.
 */
data class QualityGate(
    val allowedSolutions: Set<SolutionType> = setOf(SolutionType.RTK_FIXED),
    val maxHdop: Double? = 2.0,
    val maxPdop: Double? = 4.0,
    val maxHorizontalPrecision: Double? = 0.030,
    val maxVerticalPrecision: Double? = 0.050,
    val minSatellites: Int = 5,
    val maxDiffAgeSeconds: Double? = 30.0,
    /** When true, a receiver that does not report precision (no GST) fails the gate instead of skipping the precision checks. */
    val requirePrecisionData: Boolean = false
) {
    /** Returns human-readable reasons the epoch was rejected; empty = accepted. */
    fun check(s: GnssSnapshot): List<String> {
        val r = ArrayList<String>()
        if (s.solution !in allowedSolutions) r.add("Solution is ${s.solution.label}")
        if (s.satellitesUsed < minSatellites) r.add("Only ${s.satellitesUsed} satellites (need $minSatellites)")
        if (maxHdop != null && (s.hdop ?: Double.MAX_VALUE) > maxHdop) r.add("HDOP ${s.hdop?.let { "%.1f".format(it) } ?: "n/a"} > ${maxHdop}")
        if (maxPdop != null && s.pdop != null && s.pdop > maxPdop) r.add("PDOP %.1f > %.1f".format(s.pdop, maxPdop))
        val hp = s.horizontalPrecision; val vp = s.verticalPrecision
        if (maxHorizontalPrecision != null) {
            if (hp == null) { if (requirePrecisionData) r.add("No horizontal precision from receiver") }
            else if (hp > maxHorizontalPrecision) r.add("Horizontal precision %.3f m > %.3f m".format(hp, maxHorizontalPrecision))
        }
        if (maxVerticalPrecision != null) {
            if (vp == null) { if (requirePrecisionData) r.add("No vertical precision from receiver") }
            else if (vp > maxVerticalPrecision) r.add("Vertical precision %.3f m > %.3f m".format(vp, maxVerticalPrecision))
        }
        if (maxDiffAgeSeconds != null && s.diffAgeSeconds != null && s.diffAgeSeconds > maxDiffAgeSeconds) r.add("Corrections are ${s.diffAgeSeconds.toInt()} s old")
        return r
    }

    fun toMap() = mapOf(
        "allowedSolutions" to allowedSolutions.map { it.name }, "maxHdop" to maxHdop, "maxPdop" to maxPdop,
        "maxHorizontalPrecision" to maxHorizontalPrecision, "maxVerticalPrecision" to maxVerticalPrecision,
        "minSatellites" to minSatellites, "maxDiffAgeSeconds" to maxDiffAgeSeconds, "requirePrecisionData" to requirePrecisionData
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): QualityGate {
            val d = QualityGate()
            return QualityGate(
                allowedSolutions = if (m.containsKey("allowedSolutions")) m["allowedSolutions"].asStrList().mapNotNull { runCatching { SolutionType.valueOf(it) }.getOrNull() }.toSet() else d.allowedSolutions,
                maxHdop = if (m.containsKey("maxHdop")) m["maxHdop"].asDoubleOrNull() else d.maxHdop,
                maxPdop = if (m.containsKey("maxPdop")) m["maxPdop"].asDoubleOrNull() else d.maxPdop,
                maxHorizontalPrecision = if (m.containsKey("maxHorizontalPrecision")) m["maxHorizontalPrecision"].asDoubleOrNull() else d.maxHorizontalPrecision,
                maxVerticalPrecision = if (m.containsKey("maxVerticalPrecision")) m["maxVerticalPrecision"].asDoubleOrNull() else d.maxVerticalPrecision,
                minSatellites = m["minSatellites"].asInt(d.minSatellites),
                maxDiffAgeSeconds = if (m.containsKey("maxDiffAgeSeconds")) m["maxDiffAgeSeconds"].asDoubleOrNull() else d.maxDiffAgeSeconds,
                requirePrecisionData = m["requirePrecisionData"].asBool(false)
            )
        }
    }
}

enum class ContinuousMode(val label: String) { TIME("Time interval (s)"), DISTANCE("Distance interval") }

/** Reusable measurement settings (a "survey style"). */
data class SurveyStyle(
    val id: String,
    val name: String,
    val deviceProfileId: String = "generic-nmea-bt",
    val ntripProfileName: String = "",
    val gate: QualityGate = QualityGate(),
    val topoEpochs: Int = 5,
    val controlEpochs: Int = 180,
    /** Restart an averaging observation if the solution drops out of the allowed set. */
    val resetOnQualityLoss: Boolean = true,
    val autoStore: Boolean = false,
    val continuousMode: ContinuousMode = ContinuousMode.TIME,
    val continuousInterval: Double = 1.0,
    val defaultAntennaHeight: Double = 2.0,
    val defaultHeightMethod: HeightMeasurement = HeightMeasurement.VERTICAL_TO_ARP,
    val stakeoutTolerance: Double = 0.02,
    val stakeoutOrientation: StakeoutOrientation = StakeoutOrientation.TOWARDS_POINT,
    val elevationMaskDeg: Int = 10
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "deviceProfileId" to deviceProfileId, "ntripProfileName" to ntripProfileName,
        "gate" to gate.toMap(), "topoEpochs" to topoEpochs, "controlEpochs" to controlEpochs,
        "resetOnQualityLoss" to resetOnQualityLoss, "autoStore" to autoStore,
        "continuousMode" to continuousMode.name, "continuousInterval" to continuousInterval,
        "defaultAntennaHeight" to defaultAntennaHeight, "defaultHeightMethod" to defaultHeightMethod.name,
        "stakeoutTolerance" to stakeoutTolerance, "stakeoutOrientation" to stakeoutOrientation.name, "elevationMaskDeg" to elevationMaskDeg
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): SurveyStyle {
            val d = SurveyStyle("", "")
            return SurveyStyle(
                id = m["id"].asStr(), name = m["name"].asStr(), deviceProfileId = m["deviceProfileId"].asStr(d.deviceProfileId),
                ntripProfileName = m["ntripProfileName"].asStr(), gate = QualityGate.fromMap(m["gate"].asObj()),
                topoEpochs = m["topoEpochs"].asInt(d.topoEpochs), controlEpochs = m["controlEpochs"].asInt(d.controlEpochs),
                resetOnQualityLoss = m["resetOnQualityLoss"].asBool(true), autoStore = m["autoStore"].asBool(false),
                continuousMode = runCatching { ContinuousMode.valueOf(m["continuousMode"].asStr()) }.getOrDefault(d.continuousMode),
                continuousInterval = m["continuousInterval"].asDouble(d.continuousInterval),
                defaultAntennaHeight = m["defaultAntennaHeight"].asDouble(d.defaultAntennaHeight),
                defaultHeightMethod = runCatching { HeightMeasurement.valueOf(m["defaultHeightMethod"].asStr()) }.getOrDefault(d.defaultHeightMethod),
                stakeoutTolerance = m["stakeoutTolerance"].asDouble(d.stakeoutTolerance),
                stakeoutOrientation = runCatching { StakeoutOrientation.valueOf(m["stakeoutOrientation"].asStr()) }.getOrDefault(d.stakeoutOrientation),
                elevationMaskDeg = m["elevationMaskDeg"].asInt(d.elevationMaskDeg)
            )
        }

        val builtin = listOf(
            SurveyStyle("rtk-fixed", "RTK (fixed only)"),
            SurveyStyle("rtk-float", "RTK (fixed or float)", gate = QualityGate(allowedSolutions = setOf(SolutionType.RTK_FIXED, SolutionType.RTK_FLOAT), maxHorizontalPrecision = 0.10, maxVerticalPrecision = 0.15), topoEpochs = 3),
            SurveyStyle("dgps", "Code differential (sub-metre)", gate = QualityGate(allowedSolutions = setOf(SolutionType.RTK_FIXED, SolutionType.RTK_FLOAT, SolutionType.DGPS), maxHdop = 3.0, maxHorizontalPrecision = 1.0, maxVerticalPrecision = 2.0), topoEpochs = 5, stakeoutTolerance = 0.5),
            SurveyStyle("demo", "Demo / simulator", deviceProfileId = "simulator")
        )
    }
}
