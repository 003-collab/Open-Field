package com.openfield.survey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.openfield.survey.fmtNum
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
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

/** Short unit text for the scale bar ("m", "sft"); provided once at the root from the open job. */
val LocalUnitLabel = staticCompositionLocalOf { "" }

private class MapState {
    var cx by mutableDoubleStateOf(0.0)
    var cy by mutableDoubleStateOf(0.0)
    var scale by mutableDoubleStateOf(1.0)          // pixels per job unit
    var follow by mutableStateOf(false)
    var labels by mutableStateOf(true)
    var selected by mutableStateOf<String?>(null)
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)

    fun sx(e: Double) = width / 2f + ((e - cx) * scale).toFloat()
    fun sy(n: Double) = height / 2f - ((n - cy) * scale).toFloat()

    fun fit(xs: List<Double>, ys: List<Double>) {
        if (xs.isEmpty() || width == 0 || height == 0) return
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        cx = (minX + maxX) / 2; cy = (minY + maxY) / 2
        val spanX = max(maxX - minX, 10.0) * 1.3; val spanY = max(maxY - minY, 10.0) * 1.3
        scale = min(width / spanX, height / spanY)
    }

    fun zoomBy(f: Double) { scale = (scale * f).coerceIn(1e-3, 1e5) }
}

/**
 * Interactive plan (top-down, north up) view of the job: drag to pan, pinch or use the toolbar to zoom, double-tap to zoom to extents,
 * tap a point to see its details. The toolbar (zoom in/out, extents, follow receiver, labels) sits on the left like a controller's map.
 * Coordinates are in the job's unit; [current] and [target] are (easting, northing).
 */
@Composable
fun PlanView(
    points: List<SurveyPoint>,
    codes: FeatureCodeLibrary,
    current: Pair<Double, Double>?,
    target: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
    lines: List<DesignLine> = emptyList(),
    toolbar: Boolean = true,
    onPointTap: ((SurveyPoint) -> Unit)? = null
) {
    val t = LocalAppTheme.current
    val st = remember { MapState() }
    val paint = remember { android.graphics.Paint() }
    val surface = t.c(ColorRole.SURFACE)
    val outline = t.c(ColorRole.OUTLINE)
    val onSurface = t.c(ColorRole.ON_SURFACE)
    val accent = t.c(ColorRole.ACCENT)
    val bad = t.c(ColorRole.BAD)
    val primary = t.c(ColorRole.PRIMARY)
    val unitLabel = LocalUnitLabel.current
    val currentState = rememberUpdatedState(current)
    val pointsState = rememberUpdatedState(points)
    val linesState = rememberUpdatedState(lines)
    val targetState = rememberUpdatedState(target)

    fun extents() {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        pointsState.value.forEach { xs.add(it.e); ys.add(it.n) }
        linesState.value.forEach { xs.add(it.e1); ys.add(it.n1); xs.add(it.e2); ys.add(it.n2) }
        currentState.value?.let { xs.add(it.first); ys.add(it.second) }
        targetState.value?.let { xs.add(it.first); ys.add(it.second) }
        st.fit(xs, ys)
    }

    // fit when the pane first gets a size, when it is resized, and when a different stake-out target is chosen
    LaunchedEffect(st.width, st.height, target) { st.follow = false; extents() }
    LaunchedEffect(current, st.follow) { if (st.follow && current != null) { st.cx = current.first; st.cy = current.second } }

    Box(
        modifier.clip(RoundedCornerShape(t.cornerRadiusDp.dp)).background(surface).border(1.dp, outline, RoundedCornerShape(t.cornerRadiusDp.dp))
            .onSizeChanged { st.width = it.width; st.height = it.height }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    st.follow = false
                    val w = st.width; val h = st.height
                    val old = st.scale
                    val ns = (old * zoom).coerceIn(1e-3, 1e5)
                    val wx = st.cx + (centroid.x - w / 2) / old
                    val wy = st.cy - (centroid.y - h / 2) / old
                    st.scale = ns
                    st.cx = wx - (centroid.x - w / 2) / ns - pan.x / ns
                    st.cy = wy + (centroid.y - h / 2) / ns + pan.y / ns
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { st.follow = false; extents() },
                    onTap = { o ->
                        var best: SurveyPoint? = null; var bd = 28.dp.toPx()
                        for (p in pointsState.value) {
                            val d = hypot(st.sx(p.e) - o.x, st.sy(p.n) - o.y)
                            if (d < bd) { bd = d; best = p }
                        }
                        st.selected = best?.name
                        best?.let { onPointTap?.invoke(it) }
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            if (points.isEmpty() && lines.isEmpty() && current == null) {
                drawIntoCanvas { it.nativeCanvas.drawText("No points yet", 72.dp.toPx(), h / 2, paint.textFor(onSurface.copy(alpha = 0.6f), 14.sp.toPx())) }
            }
            for (l in lines) drawLine(accent, Offset(st.sx(l.e1), st.sy(l.n1)), Offset(st.sx(l.e2), st.sy(l.n2)), strokeWidth = 2.dp.toPx())
            val labelPaint = paint.textFor(onSurface, 11.sp.toPx())
            for (p in points) {
                val x = st.sx(p.e); val y = st.sy(p.n)
                if (x < -20 || y < -20 || x > w + 20 || y > h + 20) continue
                val col = codes.find(p.code)?.let { parseColor(it.colorHex) } ?: when (p.kind) {
                    PointKind.DESIGN -> accent; PointKind.CONTROL -> bad; else -> primary
                }
                if (p.kind == PointKind.DESIGN) drawCircle(col, 6.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx())) else drawCircle(col, 4.dp.toPx(), Offset(x, y))
                if (p.name == st.selected) drawCircle(accent, 10.dp.toPx(), Offset(x, y), style = Stroke(2.5f.dp.toPx()))
                if (st.labels && (points.size <= 120 || st.scale > 0.5)) drawIntoCanvas { it.nativeCanvas.drawText(p.name, x + 7.dp.toPx(), y - 5.dp.toPx(), labelPaint) }
            }
            if (target != null) {
                val tx = st.sx(target.first); val ty = st.sy(target.second)
                if (current != null) drawLine(bad.copy(alpha = 0.6f), Offset(st.sx(current.first), st.sy(current.second)), Offset(tx, ty), strokeWidth = 2.dp.toPx())
                drawCircle(bad, 9.dp.toPx(), Offset(tx, ty), style = Stroke(3.dp.toPx()))
                drawCircle(bad, 2.dp.toPx(), Offset(tx, ty))
            }
            if (current != null) {
                val x = st.sx(current.first); val y = st.sy(current.second)
                val arm = 14.dp.toPx()
                drawCircle(accent, 7.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
                drawLine(accent, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = 2.dp.toPx())
                drawLine(accent, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = 2.dp.toPx())
            }
            // north arrow, top right
            val nx = w - 28.dp.toPx(); val ny = 14.dp.toPx()
            drawLine(onSurface, Offset(nx, ny + 26.dp.toPx()), Offset(nx, ny), strokeWidth = 2.dp.toPx())
            drawLine(onSurface, Offset(nx, ny), Offset(nx - 5.dp.toPx(), ny + 9.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawLine(onSurface, Offset(nx, ny), Offset(nx + 5.dp.toPx(), ny + 9.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawIntoCanvas { it.nativeCanvas.drawText("N", nx - 5.dp.toPx(), ny + 44.dp.toPx(), paint.textFor(onSurface, 14.sp.toPx(), bold = true)) }
            // scale bar, bottom right: the longest 1/2/5 x 10^n length that fits in about 90 dp
            if (st.scale > 0) {
                val raw = 90.dp.toPx() / st.scale
                val mag = 10.0.pow(floor(log10(raw))); val m = raw / mag
                val nice = (if (m >= 5) 5.0 else if (m >= 2) 2.0 else 1.0) * mag
                val len = (nice * st.scale).toFloat()
                val x2 = w - 16.dp.toPx(); val x1 = x2 - len; val y = h - 16.dp.toPx()
                drawLine(onSurface, Offset(x1, y), Offset(x2, y), strokeWidth = 2.dp.toPx())
                drawLine(onSurface, Offset(x1, y - 5.dp.toPx()), Offset(x1, y), strokeWidth = 2.dp.toPx())
                drawLine(onSurface, Offset(x2, y - 5.dp.toPx()), Offset(x2, y), strokeWidth = 2.dp.toPx())
                val text = (if (nice >= 1) nice.toLong().toString() else nice.toString()) + unitLabel
                val tp = paint.textFor(onSurface, 12.sp.toPx())
                drawIntoCanvas { it.nativeCanvas.drawText(text, x2 - tp.measureText(text), y - 9.dp.toPx(), tp) }
            }
        }
        if (toolbar) {
            Surface(
                Modifier.align(Alignment.TopStart).padding(6.dp), color = surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(t.cornerRadiusDp.dp), border = BorderStroke(1.dp, outline), shadowElevation = 2.dp
            ) {
                Column {
                    MapTool(Icons.Default.ZoomIn, "Zoom in") { st.zoomBy(1.6) }
                    MapTool(Icons.Default.ZoomOut, "Zoom out") { st.zoomBy(1 / 1.6) }
                    MapTool(Icons.Default.ZoomOutMap, "Zoom to extents") { st.follow = false; extents() }
                    MapTool(Icons.Default.MyLocation, "Follow position", active = st.follow, enabled = current != null) { st.follow = !st.follow }
                    MapTool(Icons.Default.Label, "Point names", active = st.labels) { st.labels = !st.labels }
                }
            }
        }
        val sel = points.firstOrNull { it.name == st.selected }
        if (sel != null) {
            Surface(
                Modifier.align(Alignment.BottomStart).padding(6.dp), color = surface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(t.cornerRadiusDp.dp), border = BorderStroke(1.dp, outline), shadowElevation = 2.dp
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(sel.name + if (sel.code.isNotBlank()) "  ${sel.code}" else "", fontWeight = FontWeight.Bold, color = onSurface)
                    Text("N ${fmtNum(sel.n, 3)}   E ${fmtNum(sel.e, 3)}   Z ${fmtNum(sel.z, 3)}", style = MaterialTheme.typography.bodySmall, color = onSurface)
                }
            }
        }
    }
}

@Composable
private fun MapTool(icon: ImageVector, description: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalAppTheme.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp * LocalTouch.current)) {
        Icon(icon, description, tint = if (active) t.c(ColorRole.ACCENT) else t.c(ColorRole.ON_SURFACE).copy(alpha = if (enabled) 1f else 0.35f))
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
