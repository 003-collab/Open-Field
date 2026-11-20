package com.openfield.core.gnss

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** A byte pipe to a receiver. Core ships TCP; the Android app adds Bluetooth SPP and USB serial. */
interface Transport {
    val description: String
    @Throws(IOException::class) fun open()
    val input: InputStream
    val output: OutputStream
    fun close()
}

class TcpTransport(private val host: String, private val port: Int, private val timeoutMs: Int = 8000) : Transport {
    private var socket: Socket? = null
    override val description get() = "TCP $host:$port"
    override fun open() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 0
        socket = s
    }
    override val input: InputStream get() = socket!!.getInputStream()
    override val output: OutputStream get() = socket!!.getOutputStream()
    override fun close() { try { socket?.close() } catch (_: IOException) {} }
}

/** In-memory transport for tests and simulation. */
class StreamTransport(override val input: InputStream, override val output: OutputStream, override val description: String = "Stream") : Transport {
    override fun open() {}
    override fun close() { try { input.close() } catch (_: IOException) {}; try { output.close() } catch (_: IOException) {} }
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

interface ReceiverListener {
    fun onState(state: ConnectionState, message: String?) {}
    fun onSnapshot(snapshot: GnssSnapshot) {}
    /** Every decoded text line, for the NMEA console / logging. */
    fun onRawLine(line: String) {}
}

// ---------------- Driver API: this is the extension point for custom equipment ----------------

interface DriverIO {
    fun write(bytes: ByteArray)
    fun log(message: String)
}

interface DriverSink {
    fun snapshot(s: GnssSnapshot)
    fun rawLine(line: String)
    /** A baseline vector reported by the receiver. It is attached to the following position epochs for up to 2.5 s. */
    fun baseline(v: BaselineVector) {}
}

/**
 * A receiver driver turns incoming bytes into [GnssSnapshot]s and may send initialisation commands.
 * Implement this in Kotlin/Java for compiled drivers, or use a JSON device profile ([NmeaDriver]) / JavaScript
 * ([com.openfield.core.device.ScriptReceiverDriver]) for no-compile support.
 */
interface ReceiverDriver {
    val id: String
    fun onOpen(io: DriverIO) {}
    fun onData(buf: ByteArray, len: Int, sink: DriverSink)
    fun onClose() {}
}

/**
 * Standard NMEA 0183 driver: frames lines, validates checksums, feeds [GnssTracker]. Binary data mixed into the
 * stream (e.g. RTCM echoed by the receiver) is skipped by resynchronising on '$'.
 */
class NmeaDriver(
    override val id: String = "nmea0183",
    private val initCommands: List<String> = emptyList(),
    qualityMap: Map<Int, SolutionType> = emptyMap(),
    private val requireChecksum: Boolean = true,
    private val maxAuxAgeSeconds: Double = 2.5
) : ReceiverDriver {
    private val tracker = GnssTracker(qualityMap, maxAuxAgeSeconds)
    private val line = ByteArrayOutputStream()

    override fun onOpen(io: DriverIO) {
        tracker.reset(); line.reset()
        for (cmd in initCommands) {
            io.write(frameCommand(cmd).toByteArray(Charsets.ISO_8859_1))
            io.log("Sent: ${cmd.trim()}")
            try { Thread.sleep(40) } catch (_: InterruptedException) {}
        }
    }

    override fun onData(buf: ByteArray, len: Int, sink: DriverSink) {
        for (i in 0 until len) {
            val b = buf[i]
            if (b == '\n'.code.toByte()) {
                if (line.size() > 0) handleLine(line.toString(Charsets.ISO_8859_1), sink)
                line.reset()
            } else if (line.size() < 512) {
                line.write(b.toInt())
            } else {
                line.reset() // runaway binary data
            }
        }
    }

    private fun handleLine(text: String, sink: DriverSink) {
        val s = text.trim()
        val start = s.lastIndexOf('$')
        if (start < 0) return
        // resynchronise on '$': binary bytes (e.g. RTCM echoed by the receiver) may precede the sentence on the same "line"
        val clean = s.substring(start)
        if (clean.any { it.code < 32 || it.code > 126 }) return
        sink.rawLine(clean)
        val sentence = NmeaParser.parse(clean, requireChecksum) ?: return
        tracker.update(sentence)?.let { sink.snapshot(it) }
    }

    companion object {
        /** "$PUBX,40,GST,0,1,0,0,0,0" -> adds checksum and CRLF. Commands already containing '*' or not starting with '$' are only terminated. */
        fun frameCommand(cmd: String): String {
            val c = cmd.trimEnd('\r', '\n')
            return if (c.startsWith("$") && !c.contains('*')) NmeaParser.build(c.substring(1)) + "\r\n" else c + "\r\n"
        }
    }
}

/**
 * Owns the read thread, reconnection and write path for one receiver. Thread-safe: [write] may be called from the
 * NTRIP thread while the reader thread is running. Listener callbacks arrive on the reader thread.
 */
class ReceiverSession(
    private val transport: Transport,
    private val driver: ReceiverDriver,
    private val listener: ReceiverListener,
    private val autoReconnect: Boolean = true,
    private val reconnectDelayMs: Long = 3000,
    /** Supplies the base station position (from the RTCM stream) so RTK positions get a derived baseline vector. */
    private val baseProvider: (() -> BasePosition?)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile var state = ConnectionState.DISCONNECTED
        private set
    private val writeLock = Any()
    @Volatile private var out: OutputStream? = null

    val isRunning get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "receiver-reader").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        transport.close()
        thread?.interrupt()
        driver.onClose()
        setState(ConnectionState.DISCONNECTED, null)
    }

    /** Send bytes (e.g. RTCM corrections) to the receiver. Returns false if not connected. */
    fun write(bytes: ByteArray, len: Int = bytes.size): Boolean {
        val o = out ?: return false
        return try { synchronized(writeLock) { o.write(bytes, 0, len); o.flush() }; true } catch (e: IOException) { false }
    }

    private fun setState(s: ConnectionState, msg: String?) { state = s; listener.onState(s, msg) }

    private val io = object : DriverIO {
        override fun write(bytes: ByteArray) { this@ReceiverSession.write(bytes) }
        override fun log(message: String) { listener.onRawLine("# $message") }
    }
    @Volatile private var lastBaseline: BaselineVector? = null
    @Volatile private var lastBaselineAt = 0L

    private fun withBaseline(s: GnssSnapshot): GnssSnapshot {
        if (s.baseline != null) return s
        val reported = lastBaseline
        if (reported != null && clock() - lastBaselineAt <= 2500) return s.copy(baseline = reported)
        val base = baseProvider?.invoke()
        if (base != null && s.solution.isRtk) return s.copy(baseline = BaselineVector.derive(base, s))
        return s
    }

    private val sink = object : DriverSink {
        override fun snapshot(s: GnssSnapshot) = listener.onSnapshot(withBaseline(s))
        override fun rawLine(line: String) = listener.onRawLine(line)
        override fun baseline(v: BaselineVector) { lastBaseline = v; lastBaselineAt = clock() }
    }

    private fun loop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                setState(ConnectionState.CONNECTING, transport.description)
                transport.open()
                out = transport.output
                setState(ConnectionState.CONNECTED, transport.description)
                driver.onOpen(io)
                val input = transport.input
                while (running.get()) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("Receiver closed the connection")
                    if (n > 0) driver.onData(buf, n, sink)
                }
            } catch (e: Exception) {
                if (!running.get()) break
                setState(ConnectionState.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                out = null
                transport.close()
            }
            if (!running.get() || !autoReconnect) break
            try { Thread.sleep(reconnectDelayMs) } catch (_: InterruptedException) { break }
        }
        running.set(false)
        if (state != ConnectionState.ERROR && state != ConnectionState.DISCONNECTED) setState(ConnectionState.DISCONNECTED, null)
    }
}
