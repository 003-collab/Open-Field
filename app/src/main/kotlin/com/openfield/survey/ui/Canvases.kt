package com.openfield.survey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfield.core.config.ColorRole
import com.openfield.core.gnss.Constellation
import com.openfield.core.gnss.SatelliteInfo
import com.openfield.core.survey.DesignLine
import com.openfield.core.survey.FeatureCodeLibrary
import com.openfield.core.survey.PointKind
import com.openfield.core.survey.SurveyPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun android.graphics.Paint.textFor(color: Color, sizePx: Float, bold: Boolean = false): android.graphics.Paint {
    this.isAntiAlias = true
    this.color = color.toArgb()
    this.textSize = sizePx
    this.isFakeBoldText = bold
    return this
}

/**
 * Plan (top-down) view of the job. Tap to cycle zoom: fit everything, follow the receiver at ~25 units, follow at ~100 units.
 * Coordinates are in the job's unit; [current] and [target] are (easting, northing).
 */
@Composable
fun PlanView(
    points: List<SurveyPoint>,
    codes: FeatureCodeLibrary,
    current: Pair<Double, Double>?,
    target: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
    lines: List<DesignLine> = emptyList()
) {
    val t = LocalAppTheme.current
    var zoom by remember { mutableIntStateOf(0) }
    val paint = remember { android.graphics.Paint() }
    val surface = t.c(ColorRole.SURFACE)
    val outline = t.c(ColorRole.OUTLINE)
    val onSurface = t.c(ColorRole.ON_SURFACE)
    val accent = t.c(ColorRole.ACCENT)
    val bad = t.c(ColorRole.BAD)
    val primary = t.c(ColorRole.PRIMARY)
    val shape = RoundedCornerShape(t.cornerRadiusDp.dp)

    Box(modifier.clip(shape).background(surface).border(1.dp, outline, shape).clickable { zoom = (zoom + 1) % 3 }) {
        Canvas(Modifier.fillMaxSize()) {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            if (zoom == 0 || current == null) {
                points.forEach { xs.add(it.e); ys.add(it.n) }
                lines.forEach { xs.add(it.e1); ys.add(it.n1); xs.add(it.e2); ys.add(it.n2) }
                current?.let { xs.add(it.first); ys.add(it.second) }
                target?.let { xs.add(it.first); ys.add(it.second) }
            } else {
                val r = if (zoom == 1) 25.0 else 100.0
                xs.add(current.first - r); xs.add(current.first + r); ys.add(current.second - r); ys.add(current.second + r)
            }
            if (xs.isEmpty()) {
                drawIntoCanvas { it.nativeCanvas.drawText("No points yet", 16.dp.toPx(), size.height / 2, paint.textFor(onSurface.copy(alpha = 0.6f), 14.sp.toPx())) }
                return@Canvas
            }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val span = max(max(maxX - minX, maxY - minY), 1.0) * 1.25
            val scale = (min(size.width, size.height) / span).toFloat()
            val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
            fun sx(e: Double) = size.width / 2 + ((e - cx) * scale).toFloat()
            fun sy(n: Double) = size.height / 2 - ((n - cy) * scale).toFloat()

            for (l in lines) drawLine(accent, Offset(sx(l.e1), sy(l.n1)), Offset(sx(l.e2), sy(l.n2)), strokeWidth = 2.dp.toPx())
            val labelPaint = paint.textFor(onSurface, 11.sp.toPx())
            val showLabels = points.size <= 80 || zoom != 0
            for (p in points) {
                val x = sx(p.e); val y = sy(p.n)
                if (x < -20 || y < -20 || x > size.width + 20 || y > size.height + 20) continue
                val col = codes.find(p.code)?.let { parseColor(it.colorHex) } ?: when (p.kind) {
                    PointKind.DESIGN -> accent; PointKind.CONTROL -> bad; else -> primary
                }
                if (p.kind == PointKind.DESIGN) drawCircle(col, 6.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx())) else drawCircle(col, 4.dp.toPx(), Offset(x, y))
                if (showLabels) drawIntoCanvas { it.nativeCanvas.drawText(p.name, x + 7.dp.toPx(), y - 5.dp.toPx(), labelPaint) }
            }
            if (target != null) {
                val tx = sx(target.first); val ty = sy(target.second)
                if (current != null) drawLine(bad.copy(alpha = 0.6f), Offset(sx(current.first), sy(current.second)), Offset(tx, ty), strokeWidth = 2.dp.toPx())
                drawCircle(bad, 9.dp.toPx(), Offset(tx, ty), style = Stroke(3.dp.toPx()))
                drawCircle(bad, 2.dp.toPx(), Offset(tx, ty))
            }
            if (current != null) {
                val x = sx(current.first); val y = sy(current.second)
                val arm = 14.dp.toPx()
                drawCircle(accent, 7.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
                drawLine(accent, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = 2.dp.toPx())
                drawLine(accent, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = 2.dp.toPx())
            }
            // north arrow
            drawIntoCanvas { it.nativeCanvas.drawText("N ↑", size.width - 34.dp.toPx(), 20.dp.toPx(), paint.textFor(onSurface, 14.sp.toPx(), bold = true)) }
        }
        val zoomText = when (zoom) { 0 -> "Fit"; 1 -> "Follow ± 25"; else -> "Follow ± 100" }
        Text(
            "$zoomText  (tap)", modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.6f)
        )
    }
}

fun constellationColor(c: Constellation): Color = when (c) {
    Constellation.GPS -> Color(0xFF1565C0)
    Constellation.GLONASS -> Color(0xFFC62828)
    Constellation.GALILEO -> Color(0xFF2E7D32)
    Constellation.BEIDOU -> Color(0xFFEF6C00)
    Constellation.QZSS -> Color(0xFF6A1B9A)
    Constellation.NAVIC -> Color(0xFF00838F)
    else -> Color(0xFF616161)
}

/** Sky plot: centre is zenith, rim is the horizon, north is up. */
@Composable
fun SkyPlot(sats: List<SatelliteInfo>, modifier: Modifier = Modifier) {
    val t = LocalAppTheme.current
    val onSurface = t.c(ColorRole.ON_SURFACE)
    val outline = t.c(ColorRole.OUTLINE)
    val paint = remember { android.graphics.Paint() }
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2 - 18.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        for (frac in listOf(1f, 2f / 3f, 1f / 3f)) drawCircle(outline, r * frac, c, style = Stroke(1.dp.toPx()))
        drawLine(outline, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(outline, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        drawIntoCanvas {
            val p = paint.textFor(onSurface, 12.sp.toPx(), bold = true)
            it.nativeCanvas.drawText("N", c.x - 4.dp.toPx(), c.y - r - 4.dp.toPx(), p)
            it.nativeCanvas.drawText("S", c.x - 4.dp.toPx(), c.y + r + 14.dp.toPx(), p)
            it.nativeCanvas.drawText("E", c.x + r + 4.dp.toPx(), c.y + 4.dp.toPx(), p)
            it.nativeCanvas.drawText("W", c.x - r - 14.dp.toPx(), c.y + 4.dp.toPx(), p)
        }
        for (s in sats) {
            val el = s.elevationDeg ?: continue; val az = s.azimuthDeg ?: continue
            val rr = r * (90 - el).coerceIn(0, 90) / 90f
            val a = az * PI / 180.0
            val x = c.x + (rr * sin(a)).toFloat(); val y = c.y - (rr * cos(a)).toFloat()
            val strong = (s.snr ?: 0) >= 30
            val col = constellationColor(s.constellation)
            drawCircle(col.copy(alpha = if (s.snr == null || s.snr == 0) 0.35f else 1f), if (strong) 9.dp.toPx() else 6.dp.toPx(), Offset(x, y))
            drawIntoCanvas { it.nativeCanvas.drawText(s.prn.toString(), x + 8.dp.toPx(), y - 6.dp.toPx(), paint.textFor(onSurface, 10.sp.toPx())) }
        }
    }
}

/** Signal strength bars (C/N0, 0-60 dB-Hz), one per satellite, coloured by constellation. */
@Composable
fun SnrBars(sats: List<SatelliteInfo>, modifier: Modifier = Modifier) {
    val t = LocalAppTheme.current
    val onSurface = t.c(ColorRole.ON_SURFACE)
    val outline = t.c(ColorRole.OUTLINE)
    val paint = remember { android.graphics.Paint() }
    val list = sats.sortedWith(compareBy({ it.constellation.ordinal }, { it.prn }))
    Canvas(modifier) {
        if (list.isEmpty()) return@Canvas
        val bottom = size.height - 16.dp.toPx()
        val slot = size.width / list.size
        val barW = min(slot * 0.7f, 22.dp.toPx())
        drawLine(outline, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        list.forEachIndexed { i, s ->
            val h = (bottom - 4.dp.toPx()) * ((s.snr ?: 0).coerceIn(0, 60) / 60f)
            val x = slot * i + (slot - barW) / 2
            drawRect(constellationColor(s.constellation), Offset(x, bottom - h), androidx.compose.ui.geometry.Size(barW, h))
            drawIntoCanvas { it.nativeCanvas.drawText(s.prn.toString(), x, size.height - 2.dp.toPx(), paint.textFor(onSurface, 9.sp.toPx())) }
        }
    }
}

/**
 * Stakeout guidance: an arrow pointing the way to the target in the chosen orientation, or a filled circle once within
 * tolerance. [angleDeg] is 0 when the target is straight ahead, positive clockwise.
 */
@Composable
fun GuidanceArrow(angleDeg: Float, within: Boolean, modifier: Modifier = Modifier) {
    val t = LocalAppTheme.current
    val good = t.c(ColorRole.GOOD)
    val primary = t.c(ColorRole.PRIMARY)
    val outline = t.c(ColorRole.OUTLINE)
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2 - 4.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(outline, r, c, style = Stroke(2.dp.toPx()))
        if (within) {
            drawCircle(good, r * 0.8f, c)
            val check = Path().apply {
                moveTo(c.x - r * 0.35f, c.y); lineTo(c.x - r * 0.1f, c.y + r * 0.28f); lineTo(c.x + r * 0.4f, c.y - r * 0.3f)
            }
            drawPath(check, Color.White, style = Stroke(r * 0.12f))
        } else {
            rotate(angleDeg, pivot = c) {
                val arrow = Path().apply {
                    moveTo(c.x, c.y - r * 0.85f)
                    lineTo(c.x + r * 0.5f, c.y + r * 0.5f)
                    lineTo(c.x, c.y + r * 0.22f)
                    lineTo(c.x - r * 0.5f, c.y + r * 0.5f)
                    close()
                }
                drawPath(arrow, primary)
            }
        }
    }
}
