package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Common filament colours (the desktop colour picker's swatches) in a 6-wide grid.
private val SWATCHES = listOf(
    "#FFFFFF", "#F2F2F2", "#A6A9AA", "#5B5E63", "#2A2D31", "#000000",
    "#E5484D", "#FF6F00", "#FFB000", "#F4EE2A", "#8BC34A", "#00AE42",
    "#009688", "#00BCD4", "#0086D6", "#3F51B5", "#7B1FA2", "#E91E63",
    "#8D6E63", "#C9A17A", "#F5DEB3", "#C0C0C0", "#D4AF37", "#B87333",
)

/** Filament colour: swatches plus a hex field (desktop colour picker). */
@Composable
fun ColorPickerDialog(initial: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    var hex by remember { mutableStateOf(initial.take(7).uppercase()) }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("FILAMENT COLOUR", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SWATCHES.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Box(Modifier.size(40.dp).background(parseHex(c), MaterialTheme.shapes.small)
                                .border(if (c.equals(hex, true)) 3.dp else 1.dp, if (c.equals(hex, true)) Board.tealText else Board.steelMid, MaterialTheme.shapes.small)
                                .clickable { hex = c })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(40.dp).background(parseHex(hex), MaterialTheme.shapes.small))
                    OutlinedTextField(hex, { hex = it.trim().uppercase().let { v -> if (v.startsWith("#")) v else "#$v" }.take(7) },
                        singleLine = true, colors = fieldColors, label = { Text("Hex") })
                }
            }
        },
        confirmButton = { TextButton({ if (Regex("#[0-9A-F]{6}").matches(hex)) onPick(hex); onClose() }) { Text("APPLY", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

/** The desktop's "Flushing volumes" table: mm³ purged going from one filament (row) to another (column). */
@Composable
fun FlushDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { data = withContext(Dispatchers.IO) { JSONObject(OrcaCore.flushVolumes()) } }
    val d = data ?: return
    val n = d.optInt("count", 1)
    val cells = remember(d) {
        val m = d.optJSONArray("matrix") ?: JSONArray()
        List(n * n) { i -> mutableStateOf(if (i < m.length()) "%.0f".format(m.getDouble(i)) else "0") }
    }
    var mult by remember(d) { mutableStateOf("%.2f".format(d.optDouble("multiplier", 1.0))) }
    fun apply(auto: Boolean) = scope.launch {
        withContext(Dispatchers.IO) {
            OrcaCore.setFlushVolumes(if (auto) DoubleArray(0) else DoubleArray(n * n) { cells[it].value.toDoubleOrNull() ?: 0.0 },
                mult.replace(',', '.').toDoubleOrNull() ?: 1.0)
            data = JSONObject(OrcaCore.flushVolumes())
        }
        state.invalidate()
    }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("FLUSHING VOLUMES · MM³", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (n < 2) Text("ONE FILAMENT: NOTHING TO FLUSH.", style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
                else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("FROM \\ TO", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
                        (0 until n).forEach { j -> Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) { state.presets.slots.getOrNull(j)?.let { Swatch(it.color, j + 1) } } }
                    }
                    (0 until n).forEach { i ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(56.dp)) { state.presets.slots.getOrNull(i)?.let { Swatch(it.color, i + 1) } }
                            (0 until n).forEach { j ->
                                if (i == j) Box(Modifier.width(72.dp)) { Text("—", color = Board.steelMid) }
                                else OutlinedTextField(cells[i * n + j].value, { cells[i * n + j].value = it }, Modifier.width(72.dp), singleLine = true,
                                    colors = fieldColors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                        }
                    }
                }
                OutlinedTextField(mult, { mult = it }, singleLine = true, colors = fieldColors, label = { Text("Multiplier") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton({ apply(false); onClose() }) { Text("APPLY", color = Board.tealText) } },
        dismissButton = {
            Row {
                TextButton({ apply(true) }) { Text("AUTO-CALC", color = Board.tealText) }
                TextButton(onClose) { Text("CANCEL", color = Board.steelLight) }
            }
        })
}

/** Rename / delete / export / import / compare for a preset kind (desktop preset combo and File menu). */
@Composable
fun PresetManageMenu(state: AppState, host: PlateHost, kind: Int) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    val current = when (kind) { OrcaCore.KIND_PRINTER -> state.presets.printer; OrcaCore.KIND_PROCESS -> state.presets.process
        else -> state.presets.slots.getOrNull(state.filamentSlot)?.name.orEmpty() }
    val list = when (kind) { OrcaCore.KIND_PRINTER -> state.presets.printers; OrcaCore.KIND_PROCESS -> state.presets.processes; else -> state.presets.filaments }
    val isUser = list.firstOrNull { it.name == current }?.system == false
    Box {
        IconButton({ open = true }) { Icon(Icons.Default.MoreVert, "Preset actions", tint = Board.letter) }
        DropdownMenu(open, { open = false }, containerColor = Board.frame) {
            DropdownMenuItem(text = { Text("RENAME") }, enabled = isUser, onClick = { open = false; rename = true })
            DropdownMenuItem(text = { Text("DELETE") }, enabled = isUser, onClick = { open = false; delete = true })
            DropdownMenuItem(text = { Text("EXPORT PRESET (JSON)") }, onClick = {
                open = false
                host.saveFile("${current.replace(Regex("[^A-Za-z0-9._ -]"), "_")}.json") { f -> state.exportPreset(kind, f) }
            })
            DropdownMenuItem(text = { Text("IMPORT PRESETS…") }, onClick = { open = false; host.pickFile { f -> scope.launch { state.importPresets(f) } } })
            DropdownMenuItem(text = { Text("COMPARE WITH…") }, onClick = { open = false; compare = true })
        }
    }
    if (rename) TextDialog("Rename preset", current, { rename = false }) { n -> scope.launch { state.renamePreset(kind, n) } }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, containerColor = Board.frame,
        title = { Text("DELETE ${current.uppercase()}?", style = MaterialTheme.typography.titleMedium) },
        text = { Text("The preset file is removed from this device.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton({ delete = false; scope.launch { state.deletePreset(kind) } }) { Text("DELETE", color = Board.red) } },
        dismissButton = { TextButton({ delete = false }) { Text("CANCEL", color = Board.steelLight) } })
    if (compare) CompareDialog(state, kind, current, list.map { it.name }) { compare = false }
}

/** Settings that differ between two presets (desktop "Compare presets"). */
@Composable
private fun CompareDialog(state: AppState, kind: Int, a: String, names: List<String>, onClose: () -> Unit) {
    var b by remember { mutableStateOf(names.firstOrNull { it != a } ?: a) }
    var rows by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    LaunchedEffect(b) {
        rows = withContext(Dispatchers.IO) {
            JSONArray(OrcaCore.comparePresets(kind, a, b)).let { x -> (0 until x.length()).map { x.getJSONObject(it) }.map { Triple(it.getString("key"), it.getString("a"), it.getString("b")) } }
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column {
                DialogBar("Compare presets", Icons.AutoMirrored.Filled.ArrowBack, onClose)
                BoardRow("This") { FlapCell(a.uppercase(), Modifier.weight(1f)) }
                BoardRow("With") { Dropdown(b.uppercase(), names.map { it.uppercase() }, Modifier.weight(1f)) { b = names[it] } }
                Text("${rows.size} DIFFERENT SETTINGS", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
                LazyColumn {
                    items(rows) { (key, va, vb) ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text((state.defs[key]?.label?.ifBlank { key } ?: key).uppercase(), style = MaterialTheme.typography.labelLarge)
                            Text(va.take(120), style = MaterialTheme.typography.bodySmall, color = Board.tealText)
                            Text(vb.take(120), style = MaterialTheme.typography.bodySmall, color = Board.amber)
                        }
                    }
                }
            }
        }
    }
}

/** Search across printer, filament and process settings at once (desktop search box); rows edit in place. */
@Composable
fun SearchAllDialog(state: AppState, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val kinds = listOf(OrcaCore.KIND_PROCESS to "Process", OrcaCore.KIND_FILAMENT to "Filament", OrcaCore.KIND_PRINTER to "Printer")
    val hits = remember(query, state.values.toMap()) {
        val q = query.trim().lowercase()
        if (q.length < 2) emptyList() else kinds.flatMap { (kind, name) ->
            state.layout[kind].orEmpty().flatMap { p -> p.groups.flatMap { g -> g.options.map { Triple(p.title, g.title, it) } } }
                .mapNotNull { (p, g, k) -> state.defs[k]?.takeIf { d -> state.values[kind]?.containsKey(k) == true && (d.label.lowercase().contains(q) || d.key.contains(q) || d.tooltip.lowercase().contains(q)) }
                    ?.let { Hit(kind, "$name › $p › $g", it) } }
                .distinctBy { it.def.key }
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column {
                DialogBar("Search settings", Icons.AutoMirrored.Filled.ArrowBack, onClose)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, colors = fieldColors,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Printer, filament and process settings") })
                LazyColumn {
                    items(hits, key = { "${it.kind}:${it.def.key}" }) { h ->
                        Text(h.where.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelMid, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                        OptionRow(state, h.kind, h.def, state.values[h.kind]?.get(h.def.key) ?: "")
                    }
                }
            }
        }
    }
}

private data class Hit(val kind: Int, val where: String, val def: OptionDef)

