package com.openfield.core.geo

import kotlin.math.floor

/** Geoid separation N (metres): orthometric height H = ellipsoidal height h - N. */
interface GeoidModel {
    val name: String
    fun separation(latDeg: Double, lonDeg: Double): Double
}

object NoGeoid : GeoidModel {
    override val name = "None (ellipsoidal heights)"
    override fun separation(latDeg: Double, lonDeg: Double) = 0.0
}

class ConstantGeoid(val value: Double) : GeoidModel {
    override val name = "Constant %.3f m".format(value)
    override fun separation(latDeg: Double, lonDeg: Double) = value
}

/**
 * Regular-grid geoid with bilinear interpolation. Users can supply their own grid in a simple ASCII format:
 *
 *     # comment lines allowed
 *     <latMin> <lonMin> <dLat> <dLon> <rows> <cols>
 *     <rows x cols separations in metres, row-major, first row = southernmost latitude, west to east>
 *
 * Any official geoid (GEOID18, EGM2008, EGG08...) can be resampled to this format with a short script.
 */
class GridGeoid(
    override val name: String,
    val latMin: Double, val lonMin: Double, val dLat: Double, val dLon: Double,
    val rows: Int, val cols: Int, private val values: DoubleArray
) : GeoidModel {
    init { require(values.size == rows * cols) { "Geoid grid size mismatch" } }

    override fun separation(latDeg: Double, lonDeg: Double): Double {
        val fy = (latDeg - latMin) / dLat
        val fx = (lonDeg - lonMin) / dLon
        require(fy >= 0 && fx >= 0 && fy <= rows - 1 && fx <= cols - 1) { "Position outside geoid grid" }
        val y0 = minOf(floor(fy).toInt(), rows - 2); val x0 = minOf(floor(fx).toInt(), cols - 2)
        val ty = fy - y0; val tx = fx - x0
        val v00 = values[y0 * cols + x0]; val v01 = values[y0 * cols + x0 + 1]
        val v10 = values[(y0 + 1) * cols + x0]; val v11 = values[(y0 + 1) * cols + x0 + 1]
        return v00 * (1 - tx) * (1 - ty) + v01 * tx * (1 - ty) + v10 * (1 - tx) * ty + v11 * tx * ty
    }

    /** Area covered, for display: "lat 33.00..40.00, lon -90.00..-80.00". */
    val coverage: String get() = "lat %.2f to %.2f, lon %.2f to %.2f".format(latMin, latMin + (rows - 1) * dLat, lonMin, lonMin + (cols - 1) * dLon)

    /** Serialises to the simple ASCII format read by [parseAscii]. */
    fun toAscii(): String = buildString(rows * cols * 8 + 100) {
        append("# ").append(name).append('\n')
        append(latMin).append(' ').append(lonMin).append(' ').append(dLat).append(' ').append(dLon).append(' ').append(rows).append(' ').append(cols).append('\n')
        for (r in 0 until rows) {
            for (c in 0 until cols) { if (c > 0) append(' '); append(String.format(java.util.Locale.ROOT, "%.4f", values[r * cols + c])) }
            append('\n')
        }
    }

    companion object {
        /**
         * Reads an NGS / PROJ ".gtx" grid (big-endian: lat0, lon0, dLat, dLon as doubles, rows and cols as ints, then rows*cols floats,
         * south row first). With a [window] (latMin, latMax, lonMin, lonMax in degrees, lon -180..180 or 0..360 as in the file) only
         * the covered cells plus a one-cell margin are kept, so a continental grid fits in memory.
         */
        fun parseGtx(name: String, input: java.io.InputStream, window: DoubleArray? = null): GridGeoid {
            val din = java.io.DataInputStream(java.io.BufferedInputStream(input, 1 shl 16))
            val lat0 = din.readDouble(); var lon0 = din.readDouble(); val dLat = din.readDouble(); val dLon = din.readDouble()
            val rows = din.readInt(); val cols = din.readInt()
            require(rows > 1 && cols > 1 && dLat > 0 && dLon > 0) { "Not a valid GTX geoid grid" }
            // Express the file's longitudes in -180..180 so positions from a receiver compare directly.
            val shift = if (lon0 > 180.0) -360.0 else 0.0
            lon0 += shift
            val r0: Int; val r1: Int; val c0: Int; val c1: Int
            if (window == null) { r0 = 0; r1 = rows - 1; c0 = 0; c1 = cols - 1 } else {
                r0 = maxOf(0, floor((window[0] - lat0) / dLat).toInt() - 1); r1 = minOf(rows - 1, kotlin.math.ceil((window[1] - lat0) / dLat).toInt() + 1)
                c0 = maxOf(0, floor((window[2] - lon0) / dLon).toInt() - 1); c1 = minOf(cols - 1, kotlin.math.ceil((window[3] - lon0) / dLon).toInt() + 1)
                require(r1 - r0 >= 1 && c1 - c0 >= 1) { "The grid does not cover the requested area" }
            }
            val outCols = c1 - c0 + 1
            val out = DoubleArray((r1 - r0 + 1) * outCols)
            val rowBytes = ByteArray(cols * 4)
            val bb = java.nio.ByteBuffer.wrap(rowBytes)
            for (r in 0 until rows) {
                if (r > r1) break
                din.readFully(rowBytes)
                if (r < r0) continue
                for (c in c0..c1) {
                    val v = bb.getFloat(c * 4).toDouble()
                    out[(r - r0) * outCols + (c - c0)] = if (kotlin.math.abs(v + 88.8888) < 1e-3) Double.NaN else v   // -88.8888 is the GTX "no data" value
                }
            }
            require(out.none { it.isNaN() }) { "The grid has no data in the requested area" }
            return GridGeoid(name, lat0 + r0 * dLat, lon0 + c0 * dLon, dLat, dLon, r1 - r0 + 1, outCols, out)
        }

        fun parseAscii(name: String, text: String): GridGeoid {
            val tokens = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                .flatMap { it.split(Regex("[\\s,]+")).asSequence() }.map { it.toDouble() }.toList()
            require(tokens.size >= 6) { "Geoid grid header missing" }
            val rows = tokens[4].toInt(); val cols = tokens[5].toInt()
            require(tokens.size == 6 + rows * cols) { "Geoid grid expects ${rows * cols} values, found ${tokens.size - 6}" }
            return GridGeoid(name, tokens[0], tokens[1], tokens[2], tokens[3], rows, cols, tokens.drop(6).toDoubleArray())
        }
    }
}
