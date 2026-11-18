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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private const val T_FLOAT = 1; private const val T_INT = 2; private const val T_STRING = 3; private const val T_PERCENT = 4
private const val T_FLOAT_OR_PERCENT = 5; private const val T_BOOL = 8; private const val T_ENUM = 9

/** Desktop-style settings: page tabs, then titled groups of option rows. */
@Composable
fun SettingsPages(state: AppState, kind: Int, modifier: Modifier = Modifier) {
    val pages = state.layout[kind] ?: return
    val values = state.values[kind] ?: return
    var page by remember(kind) { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val maxMode = if (state.advanced) 2 else 0
    fun visible(key: String): OptionDef? = state.defs[key]?.takeIf { it.mode <= maxMode && values.containsKey(key) }

    Column(modifier) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search settings") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (query.isBlank()) {
            PrimaryScrollableTabRow(selectedTabIndex = page.coerceAtMost(pages.lastIndex), edgePadding = 8.dp) {
                pages.forEachIndexed { i, p -> Tab(selected = i == page, onClick = { page = i }, text = { Text(p.title) }) }
            }
            val p = pages[page.coerceAtMost(pages.lastIndex)]
            Column(Modifier.padding(bottom = 24.dp)) {
                for (g in p.groups) {
                    val defs = g.options.mapNotNull(::visible)
                    if (defs.isEmpty()) continue
                    GroupHeader(g.title)
                    defs.forEach { OptionRow(state, kind, it, values[it.key] ?: "") }
                }
            }
        } else {
            val q = query.trim().lowercase()
            val hits = pages.flatMap { p -> p.groups.flatMap { g -> g.options.map { Triple(p.title, g.title, it) } } }
                .mapNotNull { (p, g, k) -> visible(k)?.takeIf { d -> d.label.lowercase().contains(q) || d.key.contains(q) }?.let { Triple(p, g, it) } }
            Column(Modifier.padding(bottom = 24.dp)) {
                hits.forEach { (p, g, d) ->
                    Text("$p › $g", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    OptionRow(state, kind, d, values[d.key] ?: "")
                }
                if (hits.isEmpty()) Text("No settings match.", Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Column(Modifier.padding(top = 14.dp, start = 12.dp, end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
fun OptionRow(state: AppState, kind: Int, def: OptionDef, value: String) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(false) }
    val set: (String) -> Unit = { v -> if (v != value) scope.launch { state.setValue(kind, def.key, v) } }
    val label = def.label.ifBlank { def.fullLabel.ifBlank { def.key } }

    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).clickable { info = true }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(170.dp)) {
            when {
                def.baseType == T_BOOL -> Switch(checked = value == "1" || value == "true", enabled = !def.readonly,
                    onCheckedChange = { set(if (it) "1" else "0") }, modifier = Modifier.align(Alignment.End))
                def.baseType == T_ENUM || def.values.isNotEmpty() && def.baseType == T_STRING && !def.multiline ->
                    EnumField(def, value, set)
                def.baseType == T_STRING && (def.multiline || def.code) -> CodeField(label, value, set)
                else -> TextValueField(def, value, set)
            }
        }
        IconButton(onClick = { info = true }, Modifier.size(40.dp)) { Icon(Icons.Default.Info, "About $label", tint = MaterialTheme.colorScheme.outline) }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false }, confirmButton = { TextButton({ info = false }) { Text("OK") } },
        title = { Text(def.fullLabel.ifBlank { label }) },
        text = { Text(def.tooltip.ifBlank { "No description." } + "\n\nKey: ${def.key}") },
    )
}

@Composable
private fun TextValueField(def: OptionDef, value: String, set: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val numeric = def.baseType in listOf(T_FLOAT, T_INT, T_PERCENT, T_FLOAT_OR_PERCENT)
    OutlinedTextField(
        value = text, onValueChange = { text = it }, singleLine = true, enabled = !def.readonly,
        suffix = { if (def.sidetext.isNotBlank()) Text(def.sidetext, style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric && def.baseType != T_FLOAT_OR_PERCENT) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { set(text.trim()) }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != value) set(text.trim()) },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun EnumField(def: OptionDef, value: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val idx = def.values.indexOf(value)
    val shown = if (idx >= 0 && idx < def.labels.size) def.labels[idx] else value
    Surface(onClick = { if (!def.readonly) open = true }, shape = MaterialTheme.shapes.small, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shown, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            def.values.forEachIndexed { i, v ->
                DropdownMenuItem(text = { Text(def.labels.getOrNull(i) ?: v) }, onClick = { open = false; set(v) })
            }
        }
    }
}

@Composable
private fun CodeField(label: String, value: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp))
        Text(if (value.isBlank()) "Empty" else "${value.lines().size} lines")
    }
    if (open) {
        var text by remember { mutableStateOf(value) }
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ open = false }) { Text("Cancel") }
                        TextButton({ set(text); open = false }) { Text("Apply") }
                    }
                }
            }
        }
    }
}

/** Preset dropdown with dirty marker, save-as and discard, like the desktop combo + icons. */
@Composable
fun PresetSelector(state: AppState, kind: Int, selected: String, items: List<PresetItem>, dirty: Boolean, slot: Int = 0, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.small, tonalElevation = 2.dp, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected + if (dirty) " (modified)" else "", Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
                val (user, system) = items.partition { !it.system }
                if (user.isNotEmpty()) MenuHeader("User presets")
                user.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; scope.launch { state.selectPreset(kind, slot, p.name) } }) }
                if (system.isNotEmpty()) MenuHeader("System presets")
                system.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; scope.launch { state.selectPreset(kind, slot, p.name) } }) }
            }
        }
        if (dirty && slot == 0) {
            IconButton({ saving = true }) { Icon(Icons.Default.Save, "Save preset") }
            IconButton({ scope.launch { state.discard(kind) } }) { Icon(Icons.AutoMirrored.Filled.Undo, "Discard changes") }
        }
    }
    if (saving) {
        var name by remember { mutableStateOf(selected.substringBefore(" @").let { if (it.endsWith("- Copy")) it else "$it - Copy" }) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ saving = false; scope.launch { state.savePreset(kind, name.trim()) } }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ saving = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MenuHeader(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
