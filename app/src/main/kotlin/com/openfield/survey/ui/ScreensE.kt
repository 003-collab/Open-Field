@file:OptIn(ExperimentalLayoutApi::class)

package com.openfield.survey.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openfield.core.config.ColorRole
import com.openfield.core.config.CoordinateOrder
import com.openfield.core.config.Field
import com.openfield.core.config.Labels
import com.openfield.core.config.ThemeSpec
import com.openfield.core.config.UserConfig
import com.openfield.core.json.Json
import com.openfield.core.survey.AngleFormat
import com.openfield.survey.AppViewModel
import com.openfield.survey.fmtNum

private val HEX = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")

/** Edits the selected theme. Built-in themes are copied first so they can always be restored by selecting them again. */
private fun editTheme(vm: AppViewModel, f: (ThemeSpec) -> ThemeSpec) {
    vm.updateConfig { c ->
        val cur = c.theme
        if (cur.builtin) {
            val copy = f(cur.copy(id = "custom-${cur.id}", name = "${cur.name} (custom)", builtin = false))
            c.copy(themeId = copy.id, customThemes = c.customThemes.filter { it.id != copy.id } + copy)
        } else {
            c.copy(customThemes = c.customThemes.map { if (it.id == cur.id) f(it) else it })
        }
    }
}

@Composable
fun AppearanceScreen(vm: AppViewModel) {
    val cfg = vm.config
    val theme = cfg.theme
    val t = LocalAppTheme.current
    val ctx = LocalContext.current
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = pendingExport
        if (uri != null && data != null) {
            try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }; vm.message = "Settings saved" }
            catch (e: Exception) { vm.message = "Could not save: ${e.message}" }
        }
        pendingExport = null
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("Could not read file")
                val imported = UserConfig.fromJson(text)
                vm.updateConfig { imported }
                vm.message = "Settings imported"
            } catch (e: Exception) { vm.message = "Not a valid settings file: ${e.message}" }
        }
    }

    ScreenFrame(vm, label("tile.appearance")) {
        ScrollBody {
            // ---------------- theme ----------------
            Panel {
                SectionTitle("Theme")
                cfg.themes.forEach { th ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = th.id == theme.id, onClick = { vm.updateConfig { it.copy(themeId = th.id) } })
                        Text(th.name + if (th.builtin) "" else "  (custom)", Modifier.weight(1f))
                    }
                }
                var fs by remember(theme.id) { mutableFloatStateOf(theme.fontScale.toFloat()) }
                var ts by remember(theme.id) { mutableFloatStateOf(theme.touchScale.toFloat()) }
                var cr by remember(theme.id) { mutableFloatStateOf(theme.cornerRadiusDp.toFloat()) }
                Text("Text size  ${fmtNum(fs.toDouble(), 2)}×")
                Slider(value = fs, onValueChange = { fs = it }, valueRange = 0.8f..1.6f, onValueChangeFinished = { editTheme(vm) { it.copy(fontScale = fs.toDouble()) } })
                Text("Button and row size  ${fmtNum(ts.toDouble(), 2)}×   (raise for gloves)")
                Slider(value = ts, onValueChange = { ts = it }, valueRange = 0.8f..2.0f, onValueChangeFinished = { editTheme(vm) { it.copy(touchScale = ts.toDouble()) } })
                Text("Corner radius  ${cr.toInt()} dp")
                Slider(value = cr, onValueChange = { cr = it }, valueRange = 0f..24f, onValueChangeFinished = { editTheme(vm) { it.copy(cornerRadiusDp = cr.toInt()) } })
                InfoText("Editing a built-in theme saves your changes as a custom copy. Select the original again to go back.")
                ActionBar {
                    AppButton("Edit as JSON", { vm.go("theme_edit:${theme.id}") }, primary = false)
                    if (!theme.builtin) AppButton("Delete theme", {
                        vm.updateConfig { c -> c.copy(themeId = ThemeSpec.classic.id, customThemes = c.customThemes.filter { it.id != theme.id }) }
                    }, primary = false)
                }
            }

            Panel {
                SectionTitle("Colours")
                ColorRole.all.forEach { role ->
                    ColorRow(role, theme.color(role)) { hex -> editTheme(vm) { it.copy(colors = it.colors + (role to hex)) } }
                }
            }

            // ---------------- layout ----------------
            Panel {
                SectionTitle("Home screen")
                var cols by remember { mutableFloatStateOf(cfg.layout.homeColumns.toFloat()) }
                Text("Tiles per row  ${cols.toInt()}")
                Slider(
                    value = cols, onValueChange = { cols = it }, valueRange = 1f..6f, steps = 4,
                    onValueChangeFinished = { vm.updateConfig { it.copy(layout = it.layout.copy(homeColumns = cols.toInt())) } }
                )
                InfoText("Favourites (star) are shown first. Switch off anything you never use; use the arrows to reorder.")
                cfg.layout.homeTiles.forEach { tile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.updateConfig { it.copy(layout = it.layout.updateTile(tile.id) { x -> x.copy(favorite = !x.favorite) }) } }) {
                            Icon(Icons.Default.Star, "Favourite", tint = if (tile.favorite) t.c(ColorRole.ACCENT) else t.c(ColorRole.OUTLINE))
                        }
                        Icon(featureIcon(tile.id), null, tint = t.c(ColorRole.PRIMARY), modifier = Modifier.size(22.dp))
                        Text(label("tile.${tile.id}"), Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = { vm.updateConfig { it.copy(layout = it.layout.move(tile.id, -1)) } }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { vm.updateConfig { it.copy(layout = it.layout.move(tile.id, 1)) } }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        Switch(checked = tile.enabled, onCheckedChange = { on -> vm.updateConfig { it.copy(layout = it.layout.updateTile(tile.id) { x -> x.copy(enabled = on) }) } })
                    }
                }
            }

            Panel {
                SectionTitle("Status bar")
                InfoText("Choose what the strip under the title shows, in the order you tick them.")
                val labels = LocalLabels.current
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field.statusBarOptions.forEach { f ->
                        val on = f in cfg.layout.statusBarFields
                        FilterChip(selected = on, onClick = {
                            vm.updateConfig { c -> c.copy(layout = c.layout.copy(statusBarFields = if (on) c.layout.statusBarFields - f else c.layout.statusBarFields + f)) }
                        }, label = { Text(labels.get("status.$f")) })
                    }
                }
            }

            Panel {
                SectionTitle("Position panel")
                val labels = LocalLabels.current
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field.positionOptions.forEach { f ->
                        val on = f in cfg.layout.positionFields
                        FilterChip(selected = on, onClick = {
                            vm.updateConfig { c -> c.copy(layout = c.layout.copy(positionFields = if (on) c.layout.positionFields - f else c.layout.positionFields + f)) }
                        }, label = { Text(labels.get("field.$f")) })
                    }
                }
                Dropdown("Coordinate order", CoordinateOrder.entries.toList(), cfg.coordinateOrder, { it.label }, { o -> vm.updateConfig { it.copy(coordinateOrder = o) } })
            }

            Panel {
                SectionTitle("Behaviour")
                ToggleRow("Show plan view", cfg.layout.showPlanView) { on -> vm.updateConfig { it.copy(layout = it.layout.copy(showPlanView = on)) } }
                ToggleRow("Left-handed (action buttons on the left)", cfg.layout.leftHanded) { on -> vm.updateConfig { it.copy(layout = it.layout.copy(leftHanded = on)) } }
                ToggleRow("NMEA console on by default", cfg.layout.showNmeaConsole) { on -> vm.updateConfig { it.copy(layout = it.layout.copy(showNmeaConsole = on)) } }
                ToggleRow("Keep screen on", cfg.keepScreenOn) { on -> vm.updateConfig { it.copy(keepScreenOn = on) } }
                Dropdown("Angle format", AngleFormat.entries.toList(), vm.angleFormat(), { it.label }, { a -> vm.updateConfig { it.copy(angleFormat = a.name) } })
                Dropdown("Distance decimals", listOf(0, 1, 2, 3, 4, 5), cfg.distanceDecimals, { it.toString() }, { d -> vm.updateConfig { it.copy(distanceDecimals = d) } })
                TextInput("Operator name (stored on new jobs)", cfg.operatorName, { n -> vm.updateConfig { it.copy(operatorName = n) } })
            }

            Panel {
                SectionTitle("Terminology")
                InfoText("Rename any menu item, field or button to match your company's wording or language.")
                ActionBar { AppButton("Edit terminology", { vm.go("labels") }) }
            }

            Panel {
                SectionTitle("Back up or share your setup")
                InfoText("Saves themes, layout and terminology as one file you can load on another device.")
                ActionBar {
                    AppButton("Import", { opener.launch(arrayOf("*/*")) }, primary = false)
                    AppButton("Export", { pendingExport = cfg.toJson().toByteArray(); saver.launch("openfield-settings.json") })
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorRow(role: String, hex: String, onValid: (String) -> Unit) {
    var text by remember(role, hex) { mutableStateOf(hex) }
    val valid = HEX.matches(text)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(if (valid) parseColor(text) else parseColor(hex)).border(1.dp, LocalAppTheme.current.c(ColorRole.OUTLINE)))
        Text(role, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text, onValueChange = { text = it; if (HEX.matches(it)) onValid(it) },
            singleLine = true, isError = !valid, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun LabelsScreen(vm: AppViewModel) {
    var q by remember { mutableStateOf("") }
    val custom = vm.config.labelOverrides
    val entries = remember(q) { Labels.defaults.entries.filter { q.isBlank() || it.key.contains(q, true) || it.value.contains(q, true) } }
    ScreenFrame(vm, "Terminology", showStatus = false) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("Search", q, { q = it })
            InfoText("Leave a field empty to use the default wording. ${custom.size} customised.")
            ActionBar { AppButton("Reset all", { vm.updateConfig { it.copy(labelOverrides = emptyMap()) } }, primary = false, enabled = custom.isNotEmpty()) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it.key }) { e ->
                    Column {
                        Text(e.key, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = custom[e.key] ?: "", singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(e.value) },
                            onValueChange = { v -> vm.updateConfig { c -> c.copy(labelOverrides = if (v.isBlank()) c.labelOverrides - e.key else c.labelOverrides + (e.key to v)) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeEditScreen(vm: AppViewModel, id: String) {
    val theme = vm.config.themes.firstOrNull { it.id == id } ?: vm.config.theme
    val start = if (theme.builtin) theme.copy(id = "custom-${theme.id}", name = "${theme.name} (custom)", builtin = false) else theme
    TextEditorScreen(
        vm, "Theme: ${theme.name}", Json.stringify(start.toMap()),
        "Edit colours (#RRGGBB, or #AARRGGBB), fontScale (0.8 to 1.6), touchScale (0.8 to 2.0) and cornerRadiusDp. base is LIGHT or DARK and supplies any colour you leave out. Change id to save a separate theme."
    ) { text ->
        val spec = ThemeSpec.fromMap(Json.parseObject(text))
        val problems = spec.validate()
        if (problems.isNotEmpty()) problems.joinToString("\n") else {
            vm.updateConfig { c -> c.copy(themeId = spec.id, customThemes = c.customThemes.filter { it.id != spec.id } + spec) }
            null
        }
    }
}
