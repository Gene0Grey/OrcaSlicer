package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Process panel's "Objects" view: plate objects, and setting overrides for the selected one. */
@Composable
fun ObjectList(state: AppState) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    if (state.objects.isEmpty()) Text("No objects on the plate.", Modifier.padding(16.dp))
    state.objects.forEachIndexed { i, o ->
        val overrides = state.objectValues[i].orEmpty()
        ListItem(
            headlineContent = { Text(o.name.ifBlank { "Object ${i + 1}" }, fontWeight = if (o.selected) FontWeight.Bold else null) },
            supportingContent = {
                Text("%.1f × %.1f × %.1f mm".format(o.size[0], o.size[1], o.size[2]) +
                    if (overrides.isNotEmpty()) " · ${overrides.size} custom settings" else "")
            },
            leadingContent = { if (overrides.isNotEmpty()) Icon(Icons.Default.Tune, "Has custom settings", tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { IconButton({ scope.launch { state.objectOp(0, i) } }) { Icon(Icons.Default.Delete, "Delete object") } },
            colors = ListItemDefaults.colors(containerColor = if (o.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickableRow { scope.launch { state.select(i) } },
        )
        if (o.selected) {
            LaunchedEffect(i) { withContext(Dispatchers.IO) { state.refreshObjectValues(i) } }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                overrides.forEach { (key, value) ->
                    val def = state.defs[key] ?: return@forEach
                    OptionRow(def, value, { v -> scope.launch { state.setObjectValue(i, key, v) } }) {
                        IconButton({ scope.launch { state.setObjectValue(i, key, null) } }) { Icon(Icons.Default.Close, "Remove override") }
                    }
                }
                OutlinedButton({ adding = true }, Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add setting for this object")
                }
            }
            if (adding) AddObjectSettingDialog(state, existing = overrides.keys, onClose = { adding = false }) { key ->
                adding = false
                val value = state.values[OrcaCore.KIND_PROCESS]?.get(key) ?: ""
                scope.launch { state.setObjectValue(i, key, value) }
            }
        }
        HorizontalDivider()
    }
}

/** Searchable list of the settings an object may override, in the desktop's page/group order. */
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
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Add object setting", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search") })
                LazyColumn {
                    items(rows, key = { it.third.key }) { (page, group, def) ->
                        ListItem(
                            headlineContent = { Text(def.label.ifBlank { def.key }) },
                            supportingContent = { Text("$page › $group") },
                            modifier = Modifier.clickableRow { onPick(def.key) },
                        )
                    }
                }
            }
        }
    }
}
