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

/** Standard screen: coloured header with back button, receiver status strip, then scrollable or custom content. */
@Composable
fun ScreenFrame(
    vm: AppViewModel,
    title: String,
    onBack: (() -> Unit)? = { vm.back() },
    actions: @Composable RowScope.() -> Unit = {},
    showStatus: Boolean = true,
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
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), content = content)
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

/** One column on phones; two side-by-side panes on tablets. [right] receives a modifier sized for the current layout. */
@Composable
fun TwoPane(left: @Composable ColumnScope.() -> Unit, right: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = left
                )
                Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) { right(Modifier.fillMaxSize()) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                left()
                right(Modifier.fillMaxWidth().height(280.dp))
            }
        }
    }
}

/** Receiver status chips, configured by the user in Appearance & layout. */
@Composable
fun StatusStrip(vm: AppViewModel) {
    val t = LocalAppTheme.current
    val fields = vm.config.layout.statusBarFields
    val snap = vm.snapshot
    val unit = vm.unit
    Row(
        Modifier.fillMaxWidth().background(t.c(ColorRole.SURFACE)).horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        for (f in fields) {
            val labels = LocalLabels.current
            val title = labels.get("status.$f")
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
            Column {
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
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
