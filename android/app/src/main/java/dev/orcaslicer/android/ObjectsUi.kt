package dev.orcaslicer.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Modifier.ruled() = this.drawBehind {
    drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
}

/** "Default" then "Filament 1..n": the choices for an object's or part's filament. */
fun filamentChoices(state: AppState) = listOf("DEFAULT") + state.presets.slots.indices.map { "FILAMENT ${it + 1}" }

/** Process › Objects: the plate's objects; the picked one opens its settings, parts and height ranges (desktop object list). */
@Composable
fun ObjectList(state: AppState, host: PlateHost) {
    val scope = rememberCoroutineScope()
    if (state.objects.isEmpty()) Text("NO OBJECTS ON THE PLATE.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
    state.objects.forEachIndexed { i, o ->
        Row(Modifier.fillMaxWidth().clickable { scope.launch { state.select(i) } }.heightIn(min = 60.dp).ruled()
            .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(o.name.ifBlank { "Object ${i + 1}" }.uppercase() + if (o.instances > 1) "  ×${o.instances}" else "", style = MaterialTheme.typography.titleMedium,
                    color = when { o.outside -> Board.red; !o.printable -> Board.steelMid; o.selected -> Board.tealText; else -> Board.letter },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val extra = listOfNotNull(
                    o.volumes.size.takeIf { it > 1 }?.let { "$it PARTS" },
                    o.ranges.size.takeIf { it > 0 }?.let { "$it RANGES" },
                    o.settings.takeIf { it > 0 }?.let { "$it OWN SETTINGS" },
                    "NOT PRINTED".takeIf { !o.printable },
                )
                Text((listOf("%.1f × %.1f × %.1f MM".format(o.size[0], o.size[1], o.size[2])) + extra).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, color = if (extra.isNotEmpty()) Board.amber else Board.steelLight)
            }
            if (o.filament > 0) state.presets.slots.getOrNull(o.filament - 1)?.let { Swatch(it.color, o.filament) }
            IconButton({ scope.launch { state.objectOp(0, i) } }) { Icon(Icons.Default.Delete, "Delete object", tint = Board.steelLight) }
        }
        if (o.primary) ObjectDetail(state, host, i, o)
    }
}

@Composable
private fun ObjectDetail(state: AppState, host: PlateHost, i: Int, o: PlateObject) {
    val scope = rememberCoroutineScope()
    var openPart by remember(i) { mutableIntStateOf(-1) }
    var openRange by remember(i) { mutableIntStateOf(-1) }
    var addPart by remember { mutableStateOf(false) }
    var addRange by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().flap(Board.teal, 0.dp)) {
        BoardHeading("Object settings")
        Overrides(state, i, -1)

        BoardHeading("Parts") { IconButton({ addPart = true }) { Icon(Icons.Default.Add, "Add part or modifier", tint = Board.tealText) } }
        o.volumes.forEachIndexed { vi, v ->
            Row(Modifier.fillMaxWidth().clickable { openPart = if (openPart == vi) -1 else vi }.heightIn(min = 52.dp).ruled().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(v.name.ifBlank { "Part ${vi + 1}" }.uppercase(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (openPart == vi) Board.tealText else Board.letter)
                    Text(VOLUME_TYPES[v.type.coerceIn(0, 4)].uppercase() + if (v.settings > 0) "  ·  ${v.settings} OWN SETTINGS" else "",
                        style = MaterialTheme.typography.labelSmall, color = if (v.type == 0) Board.steelLight else Board.amber)
                }
                if (v.filament > 0) state.presets.slots.getOrNull(v.filament - 1)?.let { Swatch(it.color, v.filament) }
            }
            if (openPart == vi) PartPanel(state, host, i, vi, v)
        }

        BoardHeading("Height ranges") { IconButton({ addRange = true }) { Icon(Icons.Default.Add, "Add height range modifier", tint = Board.tealText) } }
        if (o.ranges.isEmpty()) Text("SETTINGS FOR A SLICE OF HEIGHT: ADD A RANGE.", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
        o.ranges.forEachIndexed { ri, r ->
            var edit by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { openRange = if (openRange == ri) -1 else ri }.heightIn(min = 52.dp).ruled().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("%.2f – %.2f MM".format(r.lo, r.hi), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = if (openRange == ri) Board.tealText else Board.letter)
                IconButton({ edit = true }) { Icon(Icons.Default.Edit, "Edit range", tint = Board.steelLight) }
                IconButton({ scope.launch { state.edit("delete_range", "index" to ri) } }) { Icon(Icons.Default.Delete, "Delete range", tint = Board.steelLight) }
            }
            if (openRange == ri) Overrides(state, i, -2 - ri)
            if (edit) RangeDialog(r.lo, r.hi, { edit = false }) { lo, hi -> scope.launch { state.edit("set_range", "index" to ri, "lo" to lo, "hi" to hi) } }
        }
    }
    if (addPart) AddPartDialog(state, host) { addPart = false }
    if (addRange) RangeDialog(0.0, (o.size[2] / 2).coerceAtLeast(1.0), { addRange = false }) { lo, hi -> scope.launch { state.edit("add_range", "lo" to lo, "hi" to hi) } }
}

/** One part's controls: type, filament, rename, exact transform, delete, and its own settings. */
@Composable
private fun PartPanel(state: AppState, host: PlateHost, i: Int, vi: Int, v: PlateVolume) {
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var transform by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        BoardRow("Type", labelWidth = 72.dp) {
            Dropdown(VOLUME_TYPES[v.type.coerceIn(0, 4)].uppercase(), VOLUME_TYPES.map { it.uppercase() }, Modifier.weight(1f)) { t ->
                scope.launch { state.edit("volume_type", "vol" to vi, "type" to t) }
            }
        }
        if (v.type == 0) BoardRow("Filament", labelWidth = 72.dp) {
            val choices = filamentChoices(state)
            Dropdown(choices.getOrElse(v.filament) { choices[0] }, choices, Modifier.weight(1f)) { f -> scope.launch { state.edit("filament", "vol" to vi, "value" to f) } }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ rename = true }) { Text("RENAME", color = Board.tealText) }
            TextButton({ transform = true }) { Text("POSITION", color = Board.tealText) }
            TextButton({ host.pickFile { f -> scope.launch { state.edit("replace", "vol" to vi, "path" to f.path) } } }) { Text("REPLACE", color = Board.tealText) }
            TextButton({ scope.launch { state.edit("delete_volume", "vol" to vi) } }) { Text("DELETE", color = Board.red) }
        }
        Overrides(state, i, vi)
    }
    if (rename) TextDialog("Rename part", v.name, { rename = false }) { n -> scope.launch { state.edit("rename", "vol" to vi, "name" to n) } }
    if (transform) TransformFieldsDialog(v.name.ifBlank { "Part" }, v.offset, v.rotation, v.scale, v.size, { transform = false }) { values ->
        scope.launch { state.edit("volume_transform", "vol" to vi, "values" to values.toList()) }
    }
}

/** The overrides of an object, part (>= 0) or height range (<= -2), with "add a setting". */
@Composable
private fun Overrides(state: AppState, i: Int, part: Int) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(i, part, state.objects) { withContext(Dispatchers.IO) { state.refreshObjectValues(i, part) } }
    val overrides = state.objectValues[i to part].orEmpty()
    overrides.forEach { (key, value) ->
        val def = state.defs[key] ?: return@forEach
        OptionRow(def, value, { v -> scope.launch { state.setObjectValue(i, part, key, v) } }) {
            IconButton({ scope.launch { state.setObjectValue(i, part, key, null) } }) { Icon(Icons.Default.Close, "Remove override", tint = Board.steelLight) }
        }
    }
    Surface(onClick = { adding = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = Board.tealText)
            Spacer(Modifier.width(10.dp))
            Text(if (part == -1) "ADD A SETTING FOR THIS OBJECT" else if (part >= 0) "ADD A SETTING FOR THIS PART" else "ADD A SETTING FOR THIS RANGE",
                style = MaterialTheme.typography.labelLarge, color = Board.tealText)
        }
    }
    if (adding) AddObjectSettingDialog(state, existing = overrides.keys, onClose = { adding = false }) { key ->
        adding = false
        scope.launch { state.setObjectValue(i, part, key, state.values[OrcaCore.KIND_PROCESS]?.get(key) ?: "") }
    }
}

/** Searchable list of the settings an object may override, in the desktop's page and group order. */
@Composable
private fun AddObjectSettingDialog(state: AppState, existing: Set<String>, onClose: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val allowed = remember { state.objectKeys.toSet() }
    val rows = remember(query) {
        val q = query.trim().lowercase()
        state.layout[OrcaCore.KIND_PROCESS].orEmpty().flatMap { p ->
            p.groups.flatMap { g -> g.options.filter { it in allowed && it !in existing }.mapNotNull { k -> state.defs[k]?.let { Triple(p.title, g.title, it) } } }
        }.filter { (_, _, d) -> q.isEmpty() || d.label.lowercase().contains(q) || d.key.contains(q) }.distinctBy { it.third.key }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column {
                DialogBar("Add setting", Icons.AutoMirrored.Filled.ArrowBack, onClose)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, colors = fieldColors,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search settings") })
                LazyColumn {
                    items(rows, key = { it.third.key }) { (page, group, def) ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(def.key) }.heightIn(min = 56.dp).ruled().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(def.label.ifBlank { def.key }.uppercase(), style = MaterialTheme.typography.titleMedium)
                            Text("$page › $group".uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                        }
                    }
                }
            }
        }
    }
}

private val SHAPES = listOf("Cube", "Cylinder", "Sphere", "Cone", "Disc", "Torus")

/** Add part / negative part / modifier / support blocker or enforcer, from a primitive or a file (desktop "Add Part" menus). */
@Composable
private fun AddPartDialog(state: AppState, host: PlateHost, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("ADD TO THE OBJECT", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Dropdown(VOLUME_TYPES[type].uppercase(), VOLUME_TYPES.map { it.uppercase() }, Modifier.fillMaxWidth()) { type = it }
                (SHAPES + (if (type == 2) listOf("Slab") else emptyList())).forEach { s ->
                    Text(s.uppercase(), Modifier.fillMaxWidth().clickable { onClose(); scope.launch { state.edit("add_volume", "type" to type, "shape" to s) } }
                        .padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
                }
                Text("FROM A FILE…", Modifier.fillMaxWidth().clickable {
                    val t = type; onClose()
                    host.pickFile { f -> scope.launch { state.edit("add_volume", "type" to t, "path" to f.path, "name" to f.nameWithoutExtension) } }
                }.padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = Board.tealText)
            }
        },
        confirmButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

@Composable
fun RangeDialog(lo: Double, hi: Double, onClose: () -> Unit, onApply: (Double, Double) -> Unit) {
    var a by remember { mutableStateOf("%.2f".format(lo)) }
    var b by remember { mutableStateOf("%.2f".format(hi)) }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("HEIGHT RANGE · MM", style = MaterialTheme.typography.titleMedium) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a, { a = it }, Modifier.weight(1f), label = { Text("From") }, singleLine = true, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(b, { b = it }, Modifier.weight(1f), label = { Text("To") }, singleLine = true, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton({ onApply(a.replace(',', '.').toDoubleOrNull() ?: lo, b.replace(',', '.').toDoubleOrNull() ?: hi); onClose() }) { Text("APPLY", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

@Composable
fun TextDialog(title: String, initial: String, onClose: () -> Unit, onApply: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, colors = fieldColors) },
        confirmButton = { TextButton({ if (text.isNotBlank()) onApply(text.trim()); onClose() }) { Text("APPLY", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

