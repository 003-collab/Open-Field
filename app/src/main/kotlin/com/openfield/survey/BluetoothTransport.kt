package com.openfield.survey

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.openfield.core.gnss.Transport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth serial (RFCOMM / SPP) link to a paired receiver. The receiver must already be paired in Android's
 * Bluetooth settings. [deviceHint] matches a paired device by name (substring) or MAC address.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(private val context: Context, private val deviceHint: String) : Transport {
    private var socket: BluetoothSocket? = null

    override val description: String get() = "Bluetooth ${deviceHint.ifBlank { "(no device set)" }}"

    override fun open() {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) throw IOException("Bluetooth permission was not granted. Allow 'Nearby devices' for OpenField in Android settings.")

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw IOException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")

        val bonded = try { adapter.bondedDevices.toList() } catch (e: SecurityException) { throw IOException("Bluetooth permission was not granted") }
        val hint = deviceHint.trim()
        val dev = if (hint.isEmpty()) null else bonded.firstOrNull {
            it.address.equals(hint, ignoreCase = true) || (it.name ?: "").contains(hint, ignoreCase = true)
        }
        if (dev == null) {
            val paired = bonded.joinToString { it.name ?: it.address }.ifEmpty { "none" }
            throw IOException(
                if (hint.isEmpty()) "Set the receiver's Bluetooth name in its device profile. Paired devices: $paired"
                else "No paired device matches '$hint'. Paired devices: $paired"
            )
        }
        adapter.cancelDiscovery()   // discovery slows or breaks RFCOMM connects
        val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw IOException("Could not connect to ${dev.name ?: dev.address}: ${e.message}")
        }
        socket = s
    }

    override val input: InputStream get() = socket!!.inputStream
    override val output: OutputStream get() = socket!!.outputStream

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
