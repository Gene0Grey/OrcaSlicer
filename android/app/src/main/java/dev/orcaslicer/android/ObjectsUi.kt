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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process › Objects: the plate's objects as board rows; the picked one opens its own setting overrides. */
@Composable
fun ObjectList(state: AppState) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    if (state.objects.isEmpty()) Text("NO OBJECTS ON THE PLATE.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
    state.objects.forEachIndexed { i, o ->
        val overrides = state.objectValues[i].orEmpty()
        Row(Modifier.fillMaxWidth().clickable { scope.launch { state.select(i) } }.heightIn(min = 60.dp).drawBehind {
            drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
        }.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(o.name.ifBlank { "Object ${i + 1}" }.uppercase(), style = MaterialTheme.typography.titleMedium,
                    color = when { o.outside -> Board.red; o.selected -> Board.tealText; else -> Board.letter }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%.1f × %.1f × %.1f MM".format(o.size[0], o.size[1], o.size[2]) + if (overrides.isNotEmpty()) "  ·  ${overrides.size} OWN SETTINGS" else "",
                    style = MaterialTheme.typography.labelSmall, color = if (overrides.isNotEmpty()) Board.amber else Board.steelLight)
            }
            IconButton({ scope.launch { state.objectOp(0, i) } }) { Icon(Icons.Default.Delete, "Delete object", tint = Board.steelLight) }
        }
        if (o.selected) {
            LaunchedEffect(i) { withContext(Dispatchers.IO) { state.refreshObjectValues(i) } }
            Column(Modifier.fillMaxWidth().flap(Board.teal, 0.dp)) {
                overrides.forEach { (key, value) ->
                    val def = state.defs[key] ?: return@forEach
                    OptionRow(def, value, { v -> scope.launch { state.setObjectValue(i, key, v) } }) {
                        IconButton({ scope.launch { state.setObjectValue(i, key, null) } }) { Icon(Icons.Default.Close, "Remove override", tint = Board.steelLight) }
                    }
                }
                Surface(onClick = { adding = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Board.tealText)
                        Spacer(Modifier.width(10.dp))
                        Text("ADD A SETTING FOR THIS OBJECT", style = MaterialTheme.typography.labelLarge, color = Board.tealText)
                    }
                }
            }
            if (adding) AddObjectSettingDialog(state, existing = overrides.keys, onClose = { adding = false }) { key ->
                adding = false
                val value = state.values[OrcaCore.KIND_PROCESS]?.get(key) ?: ""
                scope.launch { state.setObjectValue(i, key, value) }
            }
        }
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
                DialogBar("Add object setting", Icons.AutoMirrored.Filled.ArrowBack, onClose)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, colors = fieldColors,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search settings") })
                LazyColumn {
                    items(rows, key = { it.third.key }) { (page, group, def) ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(def.key) }.heightIn(min = 56.dp).drawBehind {
                            drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
                        }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(def.label.ifBlank { def.key }.uppercase(), style = MaterialTheme.typography.titleMedium)
                            Text("$page › $group".uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                        }
                    }
                }
            }
        }
    }
}
