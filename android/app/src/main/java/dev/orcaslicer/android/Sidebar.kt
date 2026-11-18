package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

fun parseHex(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex.take(7))) }.getOrDefault(Board.teal)

fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

val switchColors @Composable get() = SwitchDefaults.colors(checkedThumbColor = Board.flap, checkedTrackColor = Board.teal,
    uncheckedThumbColor = Board.steelLight, uncheckedTrackColor = Board.flap, uncheckedBorderColor = Board.steelMid)

/** The setup board: Printer, Filament and Process rows first (the sheet's peek), then the full depth. */
@Composable
fun Sidebar(state: AppState, host: PlateHost) {
    val p = state.presets
    val scope = rememberCoroutineScope()
    var objectsTab by remember { mutableIntStateOf(0) }

    BoardRow("Printer", labelWidth = 76.dp) {
        PresetSelector(state, OrcaCore.KIND_PRINTER, p.printer, p.printers, p.printerDirty, modifier = Modifier.weight(1f))
        IconButton(host.addPrinter) { Icon(Icons.Default.Add, "Choose another printer brand", tint = Board.steelLight) }
        IconButton({ host.editPreset(OrcaCore.KIND_PRINTER) }) { Icon(Icons.Default.Edit, "Printer settings", tint = Board.steelLight) }
    }
    p.slots.firstOrNull()?.let { slot ->
        BoardRow("Filament", labelWidth = 76.dp) {
            Swatch(slot.color, 1)
            PresetSelector(state, OrcaCore.KIND_FILAMENT, slot.name, p.filaments, p.filamentDirty, slot = 0, modifier = Modifier.weight(1f))
            IconButton({ host.editPreset(OrcaCore.KIND_FILAMENT) }) { Icon(Icons.Default.Edit, "Filament settings", tint = Board.steelLight) }
        }
    }
    BoardRow("Process", labelWidth = 76.dp) {
        PresetSelector(state, OrcaCore.KIND_PROCESS, p.process, p.processes, p.processDirty, modifier = Modifier.weight(1f))
    }

    state.defs["curr_bed_type"]?.let { def ->
        BoardRow("Plate", labelWidth = 76.dp) {
            Dropdown(def.labels.getOrElse(p.bedType) { "" }.uppercase(), def.labels.map { it.uppercase() }, Modifier.weight(1f)) { i ->
                scope.launch { state.setBedType(i) }
            }
        }
    }

    BoardHeading("Filaments") {
        IconButton({ scope.launch { state.setFilamentCount(p.slots.size - 1) } }, enabled = p.slots.size > 1) { Icon(Icons.Default.Remove, "Remove filament slot") }
        IconButton({ scope.launch { state.setFilamentCount(p.slots.size + 1) } }) { Icon(Icons.Default.Add, "Add filament slot") }
    }
    p.slots.drop(1).forEachIndexed { j, slot ->
        val i = j + 1
        BoardRow("Slot ${i + 1}", labelWidth = 76.dp) {
            Swatch(slot.color, i + 1)
            PresetSelector(state, OrcaCore.KIND_FILAMENT, slot.name, p.filaments, false, slot = i, modifier = Modifier.weight(1f))
        }
    }
    if (p.slots.size == 1) Text("ONE FILAMENT. ADD SLOTS FOR MULTI-COLOUR.", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = Board.steelMid)

    BoardHeading("Process") {
        Text("ADVANCED", style = MaterialTheme.typography.labelMedium, color = if (state.advanced) Board.tealText else Board.steelLight)
        Switch(state.advanced, state::saveAdvanced, Modifier.padding(horizontal = 8.dp), colors = switchColors)
    }
    FlapTabs(listOf("Global", "Objects"), objectsTab) { objectsTab = it }
    if (objectsTab == 0) SettingsPages(state, OrcaCore.KIND_PROCESS) else ObjectList(state)
}

@Composable
fun Swatch(hex: String, number: Int) =
    Box(Modifier.size(width = 28.dp, height = 40.dp).background(parseHex(hex), MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
        Text("$number", style = MaterialTheme.typography.labelLarge, color = Board.flap)
    }

/** Tabs as flap keys: the live tab is lit teal on black, the rest steel. */
@Composable
fun FlapTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected, containerColor = Board.flap, contentColor = Board.tealText,
        indicator = { tabs -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabs[selected]), color = Board.teal) }) {
        labels.forEachIndexed { i, l ->
            Tab(selected = i == selected, onClick = { onSelect(i) }, selectedContentColor = Board.tealText, unselectedContentColor = Board.steelLight,
                text = { Text(l.uppercase(), style = MaterialTheme.typography.labelLarge) })
        }
    }
}

/** A value on a flap that opens a menu. */
@Composable
fun Dropdown(selected: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = true }, color = Color.Transparent, shape = MaterialTheme.shapes.small, modifier = modifier.heightIn(min = 44.dp).flap()) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FlapText(selected, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.ArrowDropDown, null, tint = Board.steelLight)
        }
        DropdownMenu(open, { open = false }, containerColor = Board.frame, modifier = Modifier.heightIn(max = 480.dp)) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, style = MaterialTheme.typography.labelLarge) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}

/** Full-screen settings for a preset kind (printer / filament), opened by the pencil buttons. */
@Composable
fun PresetEditorDialog(state: AppState, kind: Int, onClose: () -> Unit) {
    val p = state.presets
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column {
                DialogBar(if (kind == OrcaCore.KIND_PRINTER) "Printer settings" else "Filament settings", Icons.AutoMirrored.Filled.ArrowBack, onClose)
                BoardRow(if (kind == OrcaCore.KIND_PRINTER) "Printer" else "Filament", labelWidth = 76.dp) {
                    if (kind == OrcaCore.KIND_PRINTER) PresetSelector(state, kind, p.printer, p.printers, p.printerDirty, modifier = Modifier.weight(1f))
                    else PresetSelector(state, kind, p.slots.firstOrNull()?.name ?: "", p.filaments, p.filamentDirty, modifier = Modifier.weight(1f))
                }
                LazyColumn { item { SettingsPages(state, kind) } }
            }
        }
    }
}

@Composable
fun DialogBar(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClose: () -> Unit) =
    Row(Modifier.fillMaxWidth().steel().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(icon, "Close", tint = Board.letter) }
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Board.letter)
    }

/** Vendor -> printer chooser: a departures board of brands, then of printers. */
@Composable
fun PrinterPicker(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var vendor by remember { mutableStateOf<Vendor?>(null) }
    var query by remember { mutableStateOf("") }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column {
                DialogBar(vendor?.name ?: "Choose your printer", if (vendor != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close) {
                    if (vendor != null) { vendor = null; query = "" } else onClose()
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(if (vendor == null) "Search brands and printers" else "Search printers") })
                val v = vendor
                if (v == null) {
                    LazyColumn {
                        items(state.vendors.filter { query.isBlank() || it.name.contains(query, true) || it.models.any { m -> m.contains(query, true) } }) { item ->
                            PickerRow(item.name, "${item.models.size}") { vendor = item; query = ""; scope.launch { state.loadVendor(item.id, "") } }
                        }
                    }
                } else if (state.presets.vendor == v.id) {
                    LazyColumn {
                        items(state.presets.printers.filter { query.isBlank() || it.name.contains(query, true) }) { item ->
                            PickerRow(item.name.removePrefix(v.name).trim(), if (item.name == state.presets.printer) "ACTIVE" else "") {
                                scope.launch { state.selectPreset(OrcaCore.KIND_PRINTER, 0, item.name) }
                                onClose()
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlapCell("LOADING ${v.name.uppercase()}", color = Board.tealText) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(name: String, cell: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Board.letter, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (cell.isNotEmpty()) FlapCell(cell, color = if (cell == "ACTIVE") Board.tealText else Board.steelLight, style = MaterialTheme.typography.labelMedium)
    }
