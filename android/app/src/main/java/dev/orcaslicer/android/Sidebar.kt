package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

fun parseHex(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex.take(7))) }.getOrDefault(Color(0xFF26A69A))

/** The desktop left panel: Printer, Filament and Process sections. */
@Composable
fun Sidebar(state: AppState, onAddPrinter: () -> Unit, onEdit: (Int) -> Unit) {
    val p = state.presets
    val scope = rememberCoroutineScope()
    var objectsTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader("Printer") { IconButton(onAddPrinter) { Icon(Icons.Default.Add, "Add printer") } }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PresetSelector(state, OrcaCore.KIND_PRINTER, p.printer, p.printers, p.printerDirty, modifier = Modifier.weight(1f))
            IconButton({ onEdit(OrcaCore.KIND_PRINTER) }) { Icon(Icons.Default.Edit, "Printer settings") }
        }
        state.defs["curr_bed_type"]?.let { def ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bed type", Modifier.width(80.dp))
                Dropdown(def.labels.getOrElse(p.bedType) { "" }, def.labels) { i -> scope.launch { state.setBedType(i) } }
            }
        }

        SectionHeader("Filament") {
            IconButton({ scope.launch { state.setFilamentCount(p.slots.size - 1) } }, enabled = p.slots.size > 1) { Icon(Icons.Default.Remove, "Remove filament") }
            IconButton({ scope.launch { state.setFilamentCount(p.slots.size + 1) } }) { Icon(Icons.Default.Add, "Add filament") }
        }
        p.slots.forEachIndexed { i, slot ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(parseHex(slot.color), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                PresetSelector(state, OrcaCore.KIND_FILAMENT, slot.name, p.filaments, p.filamentDirty && i == 0, slot = i, modifier = Modifier.weight(1f))
                if (i == 0) IconButton({ onEdit(OrcaCore.KIND_FILAMENT) }) { Icon(Icons.Default.Edit, "Filament settings") }
            }
        }

        SectionHeader("Process") {
            Text("Advanced", style = MaterialTheme.typography.labelMedium)
            Switch(state.advanced, state::saveAdvanced, Modifier.padding(horizontal = 6.dp))
        }
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
            listOf("Global", "Objects").forEachIndexed { i, label ->
                SegmentedButton(selected = objectsTab == i, onClick = { objectsTab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        if (objectsTab == 0) {
            PresetSelector(state, OrcaCore.KIND_PROCESS, p.process, p.processes, p.processDirty, modifier = Modifier.padding(12.dp))
            SettingsPages(state, OrcaCore.KIND_PROCESS)
        } else {
            ObjectList(state)
        }
    }
}

@Composable
fun SectionHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        actions()
    }
}

@Composable
fun Dropdown(selected: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = true }, shape = MaterialTheme.shapes.small, tonalElevation = 2.dp, modifier = modifier.heightIn(min = 44.dp)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(selected, Modifier.padding(vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}

/** Full-screen settings for a preset kind (printer / filament), opened by the pencil buttons. */
@Composable
fun PresetEditorDialog(state: AppState, kind: Int, onClose: () -> Unit) {
    val p = state.presets
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    Text(if (kind == OrcaCore.KIND_PRINTER) "Printer settings" else "Filament settings", style = MaterialTheme.typography.titleLarge)
                }
                if (kind == OrcaCore.KIND_PRINTER)
                    PresetSelector(state, kind, p.printer, p.printers, p.printerDirty, modifier = Modifier.padding(horizontal = 12.dp))
                else
                    PresetSelector(state, kind, p.slots.firstOrNull()?.name ?: "", p.filaments, p.filamentDirty, modifier = Modifier.padding(horizontal = 12.dp))
                LazyColumn { item { SettingsPages(state, kind) } }
            }
        }
    }
}

/** Vendor -> printer chooser, the mobile take on the desktop's printer selection wizard. */
@Composable
fun PrinterPicker(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var vendor by remember { mutableStateOf<Vendor?>(null) }
    var query by remember { mutableStateOf("") }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (vendor != null) { vendor = null; query = "" } else onClose() }) {
                        Icon(if (vendor != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close, "Back")
                    }
                    Text(vendor?.name ?: "Select printer brand", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search") })
                HorizontalDivider(Modifier.padding(top = 8.dp))
                val v = vendor
                if (v == null) {
                    LazyColumn {
                        items(state.vendors.filter { query.isBlank() || it.name.contains(query, true) || it.models.any { m -> m.contains(query, true) } }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text("${item.models.size} printers") },
                                modifier = Modifier.padding(horizontal = 4.dp).clickableRow {
                                    vendor = item; query = ""
                                    scope.launch { state.loadVendor(item.id, "") }
                                },
                            )
                        }
                    }
                } else if (state.presets.vendor == v.id) {
                    LazyColumn {
                        items(state.presets.printers.filter { query.isBlank() || it.name.contains(query, true) }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                leadingContent = { Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) },
                                modifier = Modifier.clickableRow {
                                    scope.launch { state.selectPreset(OrcaCore.KIND_PRINTER, 0, item.name) }
                                    onClose()
                                },
                            )
                        }
                    }
                } else {
                    Text("Loading ${v.name} profiles…", Modifier.padding(24.dp))
                }
            }
        }
    }
}

fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)
