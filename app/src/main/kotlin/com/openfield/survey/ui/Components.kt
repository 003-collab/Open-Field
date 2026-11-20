@file:OptIn(ExperimentalLayoutApi::class)

package com.openfield.survey.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.*
import com.openfield.survey.MapMode
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfield.core.config.ColorRole
import com.openfield.core.config.Field
import com.openfield.core.gnss.ConnectionState
import com.openfield.survey.AppViewModel
import com.openfield.survey.abbr
import com.openfield.survey.fmtNum
import com.openfield.survey.fmtNumOrDash

// ---------------------------------------------------------------- basic building blocks

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalAppTheme.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = t.c(ColorRole.SURFACE),
        contentColor = t.c(ColorRole.ON_SURFACE),
        shape = RoundedCornerShape(t.cornerRadiusDp.dp),
        border = BorderStroke(1.dp, borderColor ?: t.c(ColorRole.OUTLINE))
    ) {
        val clickable = if (onClick != null) Modifier.clickable { onClick() } else Modifier
        Column(clickable.padding(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall,
        color = LocalAppTheme.current.c(ColorRole.PRIMARY), fontWeight = FontWeight.Bold
    )
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else null, color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AppButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = true, color: Color? = null
) {
    val h = 48.dp * LocalTouch.current
    if (primary) {
        Button(
            onClick = onClick, modifier = modifier.heightIn(min = h), enabled = enabled,
            colors = if (color != null) ButtonDefaults.buttonColors(containerColor = color) else ButtonDefaults.buttonColors()
        ) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = h), enabled = enabled) { Text(text) }
    }
}

/** Row of action buttons. Left-handed mode groups them on the left edge instead of the right. */
@Composable
fun ActionBar(content: @Composable RowScope.() -> Unit) {
    val left = LocalLeftHanded.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (left) Alignment.Start else Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun NumField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    suffix: String? = null, keyboard: KeyboardType = KeyboardType.Decimal, enabled: Boolean = true, error: Boolean = false
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun TextInput(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    singleLine: Boolean = true, enabled: Boolean = true, error: Boolean = false, keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, enabled = enabled, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun <T> Dropdown(label: String, items: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp * LocalTouch.current)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(text(selected), style = MaterialTheme.typography.bodyLarge)
            }
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(text(item)) }, onClick = { open = false; onSelect(item) })
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "OK", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun InfoText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
}

@Composable
fun NoJob(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No job is open", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        InfoText("Create or open a job first. Points, coordinate system and units belong to a job.")
        Spacer(Modifier.height(16.dp))
        AppButton("Go to Jobs", onClick = { vm.go(com.openfield.core.config.Feature.JOBS) })
    }
}

// ---------------------------------------------------------------- screen frame and status strip

/** One key of the bottom soft-key bar (Esc, Options, Enter ...). Keys are given in logical order, the primary action last. */
data class SoftKey(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val primary: Boolean = false)

/** Bottom bar of large keys, like a controller's soft keys. The primary key is highlighted; left-handed mode mirrors the order. */
@Composable
fun SoftKeyBar(keys: List<SoftKey>) {
    val t = LocalAppTheme.current
    val ordered = if (LocalLeftHanded.current) keys.reversed() else keys
    Row(Modifier.fillMaxWidth().background(t.c(ColorRole.HEADER)).navigationBarsPadding()) {
        ordered.forEachIndexed { i, k ->
            val bg = if (k.primary) t.c(ColorRole.PRIMARY) else Color.Transparent
            val fg = if (k.primary) t.c(ColorRole.ON_PRIMARY) else t.c(ColorRole.ON_HEADER)
            Box(
                Modifier.weight(1f).height(56.dp * LocalTouch.current).background(bg)
                    .then(if (k.enabled) Modifier.clickable { k.onClick() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(k.label, color = fg.copy(alpha = if (k.enabled) 1f else 0.4f), fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
            }
            if (i < ordered.size - 1) Box(Modifier.width(1.dp).height(56.dp * LocalTouch.current).background(t.c(ColorRole.ON_HEADER).copy(alpha = 0.18f)))
        }
    }
}

/** Standard screen: coloured header with back button, receiver status strip, content, and an optional soft-key bar. */
@Composable
fun ScreenFrame(
    vm: AppViewModel,
    title: String,
    onBack: (() -> Unit)? = { vm.back() },
    actions: @Composable RowScope.() -> Unit = {},
    showStatus: Boolean = true,
    softKeys: List<SoftKey>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalAppTheme.current
    Column(Modifier.fillMaxSize().background(t.c(ColorRole.BACKGROUND))) {
        Row(
            Modifier.fillMaxWidth().background(t.c(ColorRole.HEADER)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = t.c(ColorRole.ON_HEADER)) }
            } else Spacer(Modifier.width(12.dp))
            Text(
                title, modifier = Modifier.weight(1f), color = t.c(ColorRole.ON_HEADER),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            actions()
        }
        if (showStatus) StatusStrip(vm)
        Column(Modifier.weight(1f).fillMaxWidth().then(if (softKeys == null) Modifier.navigationBarsPadding() else Modifier), content = content)
        if (softKeys != null) SoftKeyBar(softKeys)
    }
}

/** Header buttons that switch a map + form screen between Map, Split and Form. */
@Composable
fun RowScope.MapModeButtons(vm: AppViewModel) {
    val t = LocalAppTheme.current
    for ((mode, icon, desc) in listOf(Triple(MapMode.MAP, Icons.Default.Map, "Map only"), Triple(MapMode.SPLIT, Icons.Default.VerticalSplit, "Split screen"), Triple(MapMode.FORM, Icons.Default.Edit, "Form only"))) {
        val on = vm.mapMode == mode
        IconButton(onClick = { vm.mapMode = mode }) {
            Icon(icon, desc, tint = if (on) t.c(ColorRole.ACCENT) else t.c(ColorRole.ON_HEADER).copy(alpha = 0.75f))
        }
    }
}

/**
 * A map beside its form (map left, form right; mirrored in left-handed mode), stacked top/bottom in portrait. The divider can be dragged
 * and the user can show either pane alone from the header buttons ([MapModeButtons]). Only the visible pane is composed, so hiding the map resets its view.
 */
@Composable
fun MapFormSplit(vm: AppViewModel, map: @Composable (Modifier) -> Unit, form: @Composable (Modifier) -> Unit) {
    val t = LocalAppTheme.current
    val mirrored = LocalLeftHanded.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth >= maxHeight
        val totalPx = (if (landscape) constraints.maxWidth else constraints.maxHeight).toFloat()
        val ratio = vm.splitRatio.coerceIn(0.2f, 0.8f)
        val mode = vm.mapMode
        val handle: @Composable (Modifier) -> Unit = { m ->
            Box(
                m.background(t.c(ColorRole.OUTLINE).copy(alpha = 0.35f)).pointerInput(landscape, mirrored) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val d = (if (landscape) drag.x else drag.y) / totalPx
                        vm.splitRatio = (vm.splitRatio + if (landscape && mirrored) -d else d).coerceIn(0.2f, 0.8f)
                    }
                },
                contentAlignment = Alignment.Center
            ) { Text(if (landscape) "⋮" else "⋯", color = t.c(ColorRole.ON_SURFACE).copy(alpha = 0.7f), fontWeight = FontWeight.Bold) }
        }
        when (mode) {
            MapMode.MAP -> map(Modifier.fillMaxSize())
            MapMode.FORM -> form(Modifier.fillMaxSize())
            MapMode.SPLIT -> if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    val mapW = Modifier.weight(ratio).fillMaxHeight(); val formW = Modifier.weight(1f - ratio).fillMaxHeight()
                    if (mirrored) { form(formW); handle(Modifier.width(22.dp).fillMaxHeight()); map(mapW) }
                    else { map(mapW); handle(Modifier.width(22.dp).fillMaxHeight()); form(formW) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    map(Modifier.weight(ratio).fillMaxWidth())
                    handle(Modifier.height(22.dp).fillMaxWidth())
                    form(Modifier.weight(1f - ratio).fillMaxWidth())
                }
            }
        }
    }
}

/** Scrolling body with standard padding, for screens that are simple forms or lists of panels. */
@Composable
fun ScrollBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content
    )
}

/**
 * A form beside a map: [left] is the scrolling form, [right] receives a modifier for the map. The map sits on the left in landscape (top in portrait),
 * the divider drags, and the header buttons from [MapModeButtons] show either pane alone.
 */
@Composable
fun TwoPane(vm: AppViewModel, left: @Composable ColumnScope.() -> Unit, right: @Composable (Modifier) -> Unit) {
    MapFormSplit(
        vm,
        map = { m -> Box(m.padding(6.dp)) { right(Modifier.fillMaxSize()) } },
        form = { m -> Column(m.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = left) }
    )
}

/** Coloured title band at the top of a form pane, with a star that adds the screen to the favourites at the top of the home menu. */
@Composable
fun FormHeader(title: String, favorite: Boolean? = null, onFavorite: () -> Unit = {}) {
    val t = LocalAppTheme.current
    Row(Modifier.fillMaxWidth().background(t.c(ColorRole.PRIMARY)).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(vertical = 12.dp), color = t.c(ColorRole.ON_PRIMARY), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (favorite != null) IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favourite", tint = t.c(ColorRole.ON_PRIMARY))
        }
    }
}

/** Caption over a read-only value, for facts that are shown but not edited. */
@Composable
fun FormValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Two columns on a wide screen (tablet, landscape), one column on a phone. */
@Composable
fun TwoColumn(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 640.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = right)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { left(); right() }
        }
    }
}

private fun statusIcon(f: String): ImageVector? = when (f) {
    Field.CONNECTION -> Icons.Default.Bluetooth
    Field.SOLUTION -> Icons.Default.GpsFixed
    Field.SATELLITES -> Icons.Default.SatelliteAlt
    Field.RTK_STATE -> Icons.Default.Cloud
    Field.JOB_NAME -> Icons.Default.Folder
    else -> null
}

/**
 * Receiver status tiles in the controller style: an icon over a value, with the fields the user chose in Appearance & layout.
 * Fields without a natural icon show a small caption instead. A summary of the position solution sits at the right.
 */
@Composable
fun StatusStrip(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val fields = vm.config.layout.statusBarFields
    val snap = vm.snapshot
    val unit = vm.unit
    val labels = LocalLabels.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().background(t.c(ColorRole.SURFACE)), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (f in fields) {
                var color: Color? = null
                val value: String = when (f) {
                    Field.CONNECTION -> {
                        color = t.c(when (vm.connection) { ConnectionState.CONNECTED -> ColorRole.GOOD; ConnectionState.ERROR -> ColorRole.BAD; ConnectionState.CONNECTING -> ColorRole.WARNING; else -> ColorRole.OUTLINE })
                        when (vm.connection) { ConnectionState.CONNECTED -> "Connected"; ConnectionState.CONNECTING -> "Connecting"; ConnectionState.ERROR -> "Error"; ConnectionState.DISCONNECTED -> "Off" }
                    }
                    Field.SOLUTION -> { color = t.solutionColor(snap?.solution); snap?.solution?.label ?: "-" }
                    Field.H_PRECISION -> snap?.horizontalPrecision?.let { fmtNum(unit.fromMeters(it), 3) + " " + unit.abbr() } ?: "-"
                    Field.V_PRECISION -> snap?.verticalPrecision?.let { fmtNum(unit.fromMeters(it), 3) + " " + unit.abbr() } ?: "-"
                    Field.SATELLITES -> snap?.satellitesUsed?.toString() ?: "-"
                    Field.HDOP -> fmtNumOrDash(snap?.hdop, 1)
                    Field.PDOP -> fmtNumOrDash(snap?.pdop, 1)
                    Field.CORRECTION_AGE -> snap?.diffAgeSeconds?.let { fmtNum(it, 0) + " s" } ?: "-"
                    Field.RTK_STATE -> {
                        color = t.c(when (vm.ntripState) { com.openfield.core.ntrip.NtripState.CONNECTED -> ColorRole.GOOD; com.openfield.core.ntrip.NtripState.ERROR -> ColorRole.BAD; com.openfield.core.ntrip.NtripState.CONNECTING -> ColorRole.WARNING; else -> ColorRole.OUTLINE })
                        when (vm.ntripState) {
                            com.openfield.core.ntrip.NtripState.CONNECTED -> "Receiving"
                            com.openfield.core.ntrip.NtripState.CONNECTING -> "Connecting"
                            com.openfield.core.ntrip.NtripState.ERROR -> "Error"
                            else -> "Off"
                        }
                    }
                    Field.JOB_NAME -> vm.job?.name ?: "-"
                    else -> ""
                }
                val icon = statusIcon(f)
                Column(
                    Modifier.widthIn(min = 64.dp).height(56.dp).padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    if (icon != null) Icon(icon, labels.get("status.$f"), Modifier.size(22.dp), tint = color ?: onSurface)
                    else Text(labels.get("status.$f"), style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f), maxLines = 1)
                    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color ?: onSurface, maxLines = 1)
                }
                Box(Modifier.width(1.dp).height(40.dp).background(t.c(ColorRole.OUTLINE)))
            }
        }
        // position solution summary, for example "Fixed  H 0.015 m  V 0.030 m"
        if (snap != null && snap.hasPosition) {
            val sol = snap.solution
            val h = snap.horizontalPrecision?.let { "H:" + fmtNum(unit.fromMeters(it), 3) + unit.abbr() }
            val v = snap.verticalPrecision?.let { "V:" + fmtNum(unit.fromMeters(it), 3) + unit.abbr() }
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(sol.label, h, v).joinToString(" "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = t.solutionColor(sol), maxLines = 1)
                if (sol == com.openfield.core.gnss.SolutionType.RTK_FIXED) Icon(Icons.Default.Check, "Fixed", Modifier.padding(start = 4.dp).size(18.dp), tint = t.c(ColorRole.GOOD))
            }
        }
    }
    androidx.compose.material3.HorizontalDivider(color = t.c(ColorRole.OUTLINE))
}

@Composable
fun BigValue(label: String, value: String, modifier: Modifier = Modifier, color: Color? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(
            value, fontSize = 26.sp * LocalAppTheme.current.fontScale.toFloat(), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1
        )
    }
}
