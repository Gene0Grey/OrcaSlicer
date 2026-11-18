package dev.orcaslicer.android

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private const val T_FLOAT = 1; private const val T_INT = 2; private const val T_STRING = 3; private const val T_PERCENT = 4
private const val T_FLOAT_OR_PERCENT = 5; private const val T_BOOL = 8; private const val T_ENUM = 9

val fieldColors @Composable get() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Board.teal, unfocusedBorderColor = Board.steelMid,
    focusedContainerColor = Board.flap, unfocusedContainerColor = Board.flap, cursorColor = Board.teal,
    focusedLeadingIconColor = Board.tealText, unfocusedLeadingIconColor = Board.steelLight)

/** The desktop's settings tabs as a board: page tabs, ruled groups, one row per setting. */
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
            value = query, onValueChange = { query = it }, singleLine = true, colors = fieldColors,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search ${if (state.advanced) "all" else "simple"} settings") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (query.isBlank()) {
            val current = page.coerceAtMost(pages.lastIndex)
            PrimaryScrollableTabRow(selectedTabIndex = current, edgePadding = 8.dp, containerColor = Board.flap, contentColor = Board.tealText,
                indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(current, matchContentSize = false), color = Board.teal, width = 48.dp) }) {
                pages.forEachIndexed { i, p ->
                    Tab(selected = i == current, onClick = { page = i }, selectedContentColor = Board.tealText, unselectedContentColor = Board.steelLight,
                        text = { Text(p.title.uppercase(), style = MaterialTheme.typography.labelLarge) })
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {
                for (g in pages[current].groups) {
                    val defs = g.options.mapNotNull(::visible)
                    if (defs.isEmpty()) continue
                    BoardHeading(g.title, Modifier.padding(top = 12.dp))
                    defs.forEach { OptionRow(state, kind, it, values[it.key] ?: "") }
                }
            }
        } else {
            val q = query.trim().lowercase()
            val hits = pages.flatMap { p -> p.groups.flatMap { g -> g.options.map { Triple(p.title, g.title, it) } } }
                .mapNotNull { (p, g, k) -> visible(k)?.takeIf { d -> d.label.lowercase().contains(q) || d.key.contains(q) }?.let { Triple(p, g, it) } }
                .distinctBy { it.third.key }
            Column(Modifier.padding(bottom = 24.dp)) {
                hits.forEach { (p, g, d) ->
                    Text("$p › $g".uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelMid, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                    OptionRow(state, kind, d, values[d.key] ?: "")
                }
                if (hits.isEmpty()) Text("NO SETTING MATCHES \"${query.uppercase()}\"." + if (!state.advanced) " TURN ON ADVANCED TO SEARCH EVERYTHING." else "",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
            }
        }
    }
}

@Composable
fun OptionRow(state: AppState, kind: Int, def: OptionDef, value: String) {
    val scope = rememberCoroutineScope()
    OptionRow(def, value, { v -> scope.launch { state.setValue(kind, def.key, v) } })
}

/** One setting on the board: caps label (tap for its explanation), its value on a flap, optional trailing action. */
@Composable
fun OptionRow(def: OptionDef, value: String, onSet: (String) -> Unit, trailing: @Composable () -> Unit = {}) {
    var info by remember { mutableStateOf(false) }
    val set: (String) -> Unit = { v -> if (v != value) onSet(v) }
    val label = def.label.ifBlank { def.fullLabel.ifBlank { def.key } }

    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).drawBehind {
        drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
    }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f).clickable(onClickLabel = "Explain", role = Role.Button) { info = true }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = Board.steelLight, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(168.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                def.baseType == T_BOOL -> Switch(checked = value == "1" || value == "true", enabled = !def.readonly, colors = switchColors,
                    onCheckedChange = { set(if (it) "1" else "0") })
                def.baseType == T_ENUM || def.values.isNotEmpty() && def.baseType == T_STRING && !def.multiline -> {
                    val idx = def.values.indexOf(value)
                    val shown = if (idx >= 0 && idx < def.labels.size) def.labels[idx] else value
                    if (def.readonly) FlapCell(shown.uppercase(), Modifier.fillMaxWidth())
                    else Dropdown(shown.uppercase(), def.labels.ifEmpty { def.values }.map { it.uppercase() }, Modifier.fillMaxWidth()) { i -> set(def.values[i]) }
                }
                def.baseType == T_STRING && (def.multiline || def.code) -> CodeField(label, value, set)
                else -> FlapField(def, value, set)
            }
        }
        trailing()
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false }, containerColor = Board.frame,
        confirmButton = { TextButton({ info = false }) { Text("GOT IT", color = Board.tealText) } },
        title = { Text((def.fullLabel.ifBlank { label }).uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = { Text(def.tooltip.ifBlank { "No description." } + "\n\nKey: ${def.key}", style = MaterialTheme.typography.bodyMedium) },
    )
}

/** Number / text entry on a flap: letters on black, unit in steel, committed on Done or when focus leaves. */
@Composable
private fun FlapField(def: OptionDef, value: String, set: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val numeric = def.baseType in listOf(T_FLOAT, T_INT, T_PERCENT)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).flap(if (focused) Board.teal else null).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text, onValueChange = { text = it }, singleLine = true, enabled = !def.readonly,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = if (def.readonly) Board.steelMid else Board.letter),
            cursorBrush = SolidColor(Board.teal),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { set(text.trim()) }),
            modifier = Modifier.weight(1f).onFocusChanged { f -> focused = f.isFocused; if (!f.isFocused && text != value) set(text.trim()) },
        )
        if (def.sidetext.isNotBlank()) Text(def.sidetext.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelMid, maxLines = 1)
    }
}

@Composable
private fun CodeField(label: String, value: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).flap()) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (value.isBlank()) "EMPTY" else "${value.lines().size} LINES", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.Edit, "Edit $label", tint = Board.steelLight)
        }
    }
    if (open) {
        var text by remember { mutableStateOf(value) }
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Board.ground) {
                Column {
                    DialogBar(label, Icons.AutoMirrored.Filled.Undo) { open = false }
                    // G-code is code: the one place a monospace face is the right tool.
                    BasicTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth().flap().padding(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = Board.letter),
                        cursorBrush = SolidColor(Board.teal))
                    Row(Modifier.align(Alignment.End).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ open = false }) { Text("CANCEL", color = Board.steelLight) }
                        TextButton({ set(text); open = false }) { Text("APPLY", color = Board.tealText) }
                    }
                }
            }
        }
    }
}

/** Preset on a flap; amber when it has unsaved edits, with save and revert beside it. */
@Composable
fun PresetSelector(state: AppState, kind: Int, selected: String, items: List<PresetItem>, dirty: Boolean, slot: Int = 0, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = { open = true }, color = Color.Transparent, shape = MaterialTheme.shapes.small,
            modifier = Modifier.weight(1f).heightIn(min = 44.dp).flap(if (dirty) Board.amber else null)) {
            Row(Modifier.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected.uppercase(), Modifier.weight(1f).padding(vertical = 11.dp), style = MaterialTheme.typography.labelLarge,
                    color = if (dirty) Board.amber else Board.letter, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null, tint = Board.steelLight)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Board.frame, modifier = Modifier.heightIn(max = 480.dp)) {
                val (user, system) = items.partition { !it.system }
                if (user.isNotEmpty()) MenuHeader("Your presets")
                user.forEach { p -> PresetItemRow(p.name, p.name == selected) { open = false; scope.launch { state.selectPreset(kind, slot, p.name) } } }
                if (system.isNotEmpty()) MenuHeader("System presets")
                system.forEach { p -> PresetItemRow(p.name, p.name == selected) { open = false; scope.launch { state.selectPreset(kind, slot, p.name) } } }
            }
        }
        if (dirty && slot == 0) {
            IconButton({ saving = true }) { Icon(Icons.Default.Save, "Save preset", tint = Board.amber) }
            IconButton({ scope.launch { state.discard(kind) } }) { Icon(Icons.AutoMirrored.Filled.Undo, "Discard changes", tint = Board.steelLight) }
        }
    }
    if (saving) {
        var name by remember { mutableStateOf(selected.substringBefore(" @").let { if (it.endsWith("- Copy")) it else "$it - Copy" }) }
        AlertDialog(
            onDismissRequest = { saving = false }, containerColor = Board.frame,
            title = { Text("SAVE AS NEW PRESET", style = MaterialTheme.typography.titleMedium) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Preset name") }, colors = fieldColors) },
            confirmButton = { TextButton({ saving = false; scope.launch { state.savePreset(kind, name.trim()) } }, enabled = name.isNotBlank()) { Text("SAVE", color = Board.tealText) } },
            dismissButton = { TextButton({ saving = false }) { Text("CANCEL", color = Board.steelLight) } },
        )
    }
}

@Composable
private fun PresetItemRow(name: String, active: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(name.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (active) Board.tealText else Board.letter) }, onClick = onClick)

@Composable
private fun MenuHeader(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelMid, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
