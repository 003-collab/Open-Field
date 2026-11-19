package com.openfield.core.device

import com.openfield.core.gnss.*
import com.openfield.core.json.*
import kotlin.math.sqrt

enum class ConnectionType(val label: String) {
    BLUETOOTH_SPP("Bluetooth (serial)"), TCP("TCP / Wi-Fi"), USB_SERIAL("USB serial"), SIMULATOR("Simulator")
}

enum class HeightMeasurement(val label: String) {
    VERTICAL_TO_ARP("Vertical to antenna reference point (ARP)"),
    VERTICAL_TO_BOTTOM_OF_MOUNT("Vertical to bottom of antenna mount"),
    SLANT_TO_NOTCH("Slant to measurement notch"),
    VERTICAL_TO_NOTCH("Vertical to measurement notch")
}

/**
 * Antenna geometry. Distances in metres.
 *  - [arpToBottomOfMount]: vertical distance from the ARP down to the bottom of the antenna mount.
 *  - [arpToNotch]: vertical distance from the ARP down to the measurement notch (zero if the antenna has none).
 *  - [notchRadius]: horizontal distance from the antenna axis to the notch, used to reduce slant to vertical.
 */
data class AntennaProfile(
    val model: String = "Generic",
    val arpToBottomOfMount: Double = 0.0,
    val arpToNotch: Double = 0.0,
    val notchRadius: Double = 0.0
) {
    /** Vertical distance from the ground mark up to the ARP for the measured value and method. */
    fun arpHeight(measured: Double, method: HeightMeasurement): Double = when (method) {
        HeightMeasurement.VERTICAL_TO_ARP -> measured
        HeightMeasurement.VERTICAL_TO_BOTTOM_OF_MOUNT -> measured + arpToBottomOfMount
        HeightMeasurement.VERTICAL_TO_NOTCH -> measured + arpToNotch
        HeightMeasurement.SLANT_TO_NOTCH -> {
            require(measured >= notchRadius) { "Slant height ($measured) is shorter than the notch radius ($notchRadius)" }
            sqrt(measured * measured - notchRadius * notchRadius) + arpToNotch
        }
    }

    fun toMap() = mapOf("model" to model, "arpToBottomOfMount" to arpToBottomOfMount, "arpToNotch" to arpToNotch, "notchRadius" to notchRadius)
    companion object {
        fun fromMap(m: Map<String, Any?>) = AntennaProfile(m["model"].asStr("Generic"), m["arpToBottomOfMount"].asDouble(), m["arpToNotch"].asDouble(), m["notchRadius"].asDouble())
    }
}

/**
 * Everything the app needs to know to talk to one kind of receiver. Stored as a plain JSON file so users can add
 * their own equipment without code. See docs/DEVICE_PROFILES.md.
 */
data class DeviceProfile(
    val id: String,
    val name: String,
    val manufacturer: String = "",
    val connection: ConnectionType = ConnectionType.BLUETOOTH_SPP,
    /** Bluetooth: substring of the device name to pre-select. TCP: host. Serial: ignored. */
    val address: String = "",
    val port: Int = 0,
    val baudRate: Int = 115200,
    /** "nmea0183" (built in) or "script" (JavaScript driver, see [scriptFile]). */
    val protocol: String = "nmea0183",
    val scriptFile: String = "",
    /** Sent to the receiver on connect. NMEA commands starting with '$' get a checksum added automatically. */
    val initCommands: List<String> = emptyList(),
    val requireChecksum: Boolean = true,
    /** Override NMEA GGA quality numbers, e.g. {"5": "RTK_FIXED"} for receivers with non-standard codes. */
    val qualityMap: Map<Int, SolutionType> = emptyMap(),
    /** Whether RTCM from NTRIP should be written to this device's link. */
    val acceptsRtcm: Boolean = true,
    val antenna: AntennaProfile = AntennaProfile(),
    val notes: String = ""
) {
    fun createDriver(scriptRuntimeFactory: (() -> ScriptRuntime)? = null, scriptLoader: (String) -> String = { "" }): ReceiverDriver = when (protocol) {
        "script" -> {
            val rt = scriptRuntimeFactory?.invoke() ?: throw IllegalStateException("This build has no script engine; use protocol 'nmea0183' or enable scripting")
            ScriptReceiverDriver(id, rt, scriptLoader(scriptFile), requireChecksum, qualityMap)
        }
        else -> NmeaDriver(id, initCommands, qualityMap, requireChecksum)
    }

    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9_.-]+"))) problems.add("id must be letters, digits, '.', '_' or '-'")
        if (name.isBlank()) problems.add("name is required")
        if (connection == ConnectionType.TCP && (address.isBlank() || port !in 1..65535)) problems.add("TCP profiles need an address and a port between 1 and 65535")
        if (protocol == "script" && scriptFile.isBlank()) problems.add("script protocol needs a scriptFile")
        if (protocol != "script" && protocol != "nmea0183") problems.add("unknown protocol '$protocol'")
        return problems
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "manufacturer" to manufacturer,
        "connection" to mapOf("type" to connection.name, "address" to address, "port" to port, "baudRate" to baudRate),
        "protocol" to mapOf("type" to protocol, "scriptFile" to scriptFile, "initCommands" to initCommands, "requireChecksum" to requireChecksum,
            "qualityMap" to qualityMap.entries.associate { it.key.toString() to it.value.name }, "acceptsRtcm" to acceptsRtcm),
        "antenna" to antenna.toMap(), "notes" to notes
    )

    fun toJson() = Json.stringify(toMap())

    companion object {
        fun fromMap(m: Map<String, Any?>): DeviceProfile {
            val c = m["connection"].asObj(); val p = m["protocol"].asObj()
            return DeviceProfile(
                id = m["id"].asStr(), name = m["name"].asStr(), manufacturer = m["manufacturer"].asStr(),
                connection = runCatching { ConnectionType.valueOf(c["type"].asStr()) }.getOrDefault(ConnectionType.BLUETOOTH_SPP),
                address = c["address"].asStr(), port = c["port"].asInt(), baudRate = c["baudRate"].asInt(115200),
                protocol = p["type"].asStr("nmea0183"), scriptFile = p["scriptFile"].asStr(),
                initCommands = p["initCommands"].asStrList(), requireChecksum = p["requireChecksum"].asBool(true),
                qualityMap = p["qualityMap"].asObj().mapNotNull { (k, v) ->
                    val key = k.toIntOrNull(); val sol = runCatching { SolutionType.valueOf(v.asStr()) }.getOrNull()
                    if (key != null && sol != null) key to sol else null
                }.toMap(),
                acceptsRtcm = p["acceptsRtcm"].asBool(true),
                antenna = AntennaProfile.fromMap(m["antenna"].asObj()), notes = m["notes"].asStr()
            )
        }

        fun fromJson(text: String) = fromMap(Json.parseObject(text))

        /** Starter profiles shipped with the app; users copy and edit them. */
        val builtin: List<DeviceProfile> = listOf(
            DeviceProfile("generic-nmea-bt", "Generic NMEA receiver (Bluetooth)", "Generic",
                notes = "Any receiver that streams NMEA 0183 over a Bluetooth serial port. Enable GGA and GST output on the receiver."),
            DeviceProfile("generic-nmea-tcp", "Generic NMEA receiver (TCP)", "Generic", ConnectionType.TCP, "192.168.1.1", 9001,
                notes = "Receivers or bridges that publish NMEA on a TCP port. Set the address and port for your device."),
            DeviceProfile("ublox-nmea-gst", "u-blox NMEA (enable GST)", "u-blox",
                initCommands = listOf("\$PUBX,40,GST,0,1,0,0,0,0"),
                notes = "Example init command asking a u-blox receiver to output GST on the UART port so precision is available. Check your receiver's interface description for the correct port columns."),
            DeviceProfile("simulator", "Simulator (demo)", "OpenField", ConnectionType.SIMULATOR,
                notes = "Generates NMEA with realistic noise. Use it to learn the app or test a survey style without a receiver.")
        )
    }
}
