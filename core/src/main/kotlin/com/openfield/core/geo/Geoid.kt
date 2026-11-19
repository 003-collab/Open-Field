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

    companion object {
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
