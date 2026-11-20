package com.openfield.core.ntrip

import com.openfield.core.gnss.BasePosition

/**
 * Listens to the RTCM 3 stream coming from the NTRIP caster and remembers the base station's reference position from
 * message 1005 / 1006 (stationary antenna reference point, ECEF in units of 0.1 mm). Feed it every block of bytes that is
 * also written to the receiver. Frames are checked with the RTCM CRC-24Q, so garbage and other message types are skipped safely.
 *
 * With a network (VRS) mount point the "base" is a virtual station near the rover, so vectors derived from it are relative to that virtual point.
 */
class RtcmBaseTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private var buf = ByteArray(4096)
    private var len = 0
    @Volatile var base: BasePosition? = null
        private set

    @Synchronized fun reset() { len = 0; base = null }

    @Synchronized fun feed(bytes: ByteArray, n: Int = bytes.size) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(bytes, 0, buf, len, n); len += n
        var pos = 0
        while (true) {
            while (pos < len && (buf[pos].toInt() and 0xFF) != 0xD3) pos++
            if (len - pos < 3) break
            val payload = ((buf[pos + 1].toInt() and 0x03) shl 8) or (buf[pos + 2].toInt() and 0xFF)
            val total = 3 + payload + 3
            if (len - pos < total) break
            val crc = crc24q(buf, pos, 3 + payload)
            val got = ((buf[pos + 3 + payload].toInt() and 0xFF) shl 16) or ((buf[pos + 4 + payload].toInt() and 0xFF) shl 8) or (buf[pos + 5 + payload].toInt() and 0xFF)
            if (crc == got) { handle(buf, pos + 3, payload); pos += total } else pos++
        }
        if (pos > 0) { System.arraycopy(buf, pos, buf, 0, len - pos); len -= pos }
        if (len > 16384) len = 0   // no valid frame in 16 kB: not RTCM, start over
    }

    private fun handle(b: ByteArray, off: Int, payloadLen: Int) {
        if (payloadLen < 2) return
        val type = bits(b, off, 0, 12).toInt()
        if ((type == 1005 && payloadLen >= 19) || (type == 1006 && payloadLen >= 21)) {
            val id = bits(b, off, 12, 12).toInt()
            val x = signed(bits(b, off, 34, 38), 38) * 0.0001
            val y = signed(bits(b, off, 74, 38), 38) * 0.0001
            val z = signed(bits(b, off, 114, 38), 38) * 0.0001
            val p = BasePosition(id.toString(), x, y, z, clock(), "RTCM $type")
            if (p.isValid) base = p
        }
    }

    companion object {
        /** RTCM 3 CRC-24Q over [len] bytes from [off]. */
        fun crc24q(d: ByteArray, off: Int, len: Int): Int {
            var crc = 0
            for (i in off until off + len) {
                crc = crc xor ((d[i].toInt() and 0xFF) shl 16)
                repeat(8) { crc = crc shl 1; if (crc and 0x1000000 != 0) crc = crc xor 0x1864CFB }
            }
            return crc and 0xFFFFFF
        }

        /** Unsigned [count]-bit field starting [bitOffset] bits into the payload at [off]. */
        fun bits(d: ByteArray, off: Int, bitOffset: Int, count: Int): Long {
            var v = 0L
            for (i in 0 until count) {
                val bit = bitOffset + i
                val byte = d[off + bit / 8].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - bit % 8)) and 1).toLong()
            }
            return v
        }

        private fun signed(v: Long, width: Int): Long = if (v and (1L shl (width - 1)) != 0L) v - (1L shl width) else v

        /** Builds a framed 1005 message (for tests and simulators). */
        fun encode1005(stationId: Int, x: Double, y: Double, z: Double): ByteArray {
            val payload = ByteArray(19)
            fun put(bitOffset: Int, count: Int, value: Long) {
                for (i in 0 until count) {
                    val bit = bitOffset + i
                    val on = (value shr (count - 1 - i)) and 1L
                    if (on == 1L) payload[bit / 8] = (payload[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
                }
            }
            put(0, 12, 1005); put(12, 12, stationId.toLong()); put(30, 1, 1)
            put(34, 38, Math.round(x / 0.0001) and ((1L shl 38) - 1)); put(74, 38, Math.round(y / 0.0001) and ((1L shl 38) - 1)); put(114, 38, Math.round(z / 0.0001) and ((1L shl 38) - 1))
            val frame = ByteArray(3 + payload.size + 3)
            frame[0] = 0xD3.toByte(); frame[1] = ((payload.size shr 8) and 0x03).toByte(); frame[2] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 3, payload.size)
            val crc = crc24q(frame, 0, 3 + payload.size)
            frame[3 + payload.size] = (crc shr 16).toByte(); frame[4 + payload.size] = (crc shr 8).toByte(); frame[5 + payload.size] = crc.toByte()
            return frame
        }
    }
}
