package dev.orcaslicer.android

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HomeScreen(state: AppState, onNew: () -> Unit, onOpen: () -> Unit, onRecent: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeCard(Icons.Default.Add, OrcaTeal, "New Project", "Create new project", onNew)
        HomeCard(Icons.Default.FolderOpen, Color(0xFF1E88E5), "Open Project", "3mf, stl, obj, step", onOpen)
        Text("Recently opened", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        if (state.recent.isEmpty()) Text("Nothing yet.", color = MaterialTheme.colorScheme.outline)
        state.recent.filter { File(it).exists() }.forEach { path ->
            ListItem(headlineContent = { Text(File(path).name) }, supportingContent = { Text(path, maxLines = 1) },
                modifier = Modifier.clickableRow { onRecent(path) })
        }
    }
}

@Composable
private fun HomeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = tint, modifier = Modifier.size(56.dp)) {
                Icon(icon, null, Modifier.padding(14.dp), tint = Color.White)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Device tab: the printer's own web interface (Mainsail, Fluidd, OctoPrint, …), as the desktop shows it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceScreen(state: AppState) {
    val prefs = LocalContext.current.getSharedPreferences("orca", 0)
    val presetHost = state.values[OrcaCore.KIND_PRINTER]?.get("print_host").orEmpty()
    var url by remember { mutableStateOf(prefs.getString("device_url_${state.presets.printer}", null) ?: presetHost) }
    var edit by remember { mutableStateOf(url.isBlank()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.presets.printer.ifBlank { "No printer" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton({ edit = true }) { Text("Connection") }
        }
        HorizontalDivider()
        val full = if (url.isBlank() || url.startsWith("http")) url else "http://$url"
        if (full.isBlank()) Text("Set the printer's address (Moonraker / OctoPrint / PrusaLink web UI).", Modifier.padding(24.dp))
        else AndroidView(factory = { ctx ->
            WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient(); loadUrl(full) }
        }, update = { if (it.url != full) it.loadUrl(full) }, modifier = Modifier.fillMaxSize())
    }
    if (edit) {
        var text by remember { mutableStateOf(url) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Printer address") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("192.168.1.50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) },
            confirmButton = { TextButton({ url = text.trim(); prefs.edit().putString("device_url_${state.presets.printer}", url).apply(); edit = false }) { Text("Connect") } },
            dismissButton = { TextButton({ edit = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ProjectScreen(state: AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.projectName, { state.projectName = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SectionHeader("Profiles")
        Info("Printer", state.presets.printer)
        Info("Process", state.presets.process)
        state.presets.slots.forEachIndexed { i, s -> Info("Filament ${i + 1}", s.name) }
        SectionHeader("Plate 1")
        if (state.objects.isEmpty()) Text("Empty plate.")
        state.objects.forEach { o -> Info(o.name, "%.1f × %.1f × %.1f mm".format(o.size[0], o.size[1], o.size[2])) }
        state.stats?.let { s ->
            SectionHeader("Sliced")
            Info("Print time", formatTime(s.totalTime))
            Info("Filament", "%.2f m / %.2f g".format(s.meters, s.grams))
        }
    }
}

@Composable
private fun Info(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.outline)
    Text(value, Modifier.weight(1f))
}

/** Numeric move / rotate / scale for the selected object (the desktop gizmos' input panels). */
@Composable
fun TransformDialog(state: AppState, onClose: () -> Unit) {
    val o = state.objects.getOrNull(state.selected) ?: return onClose()
    val scope = rememberCoroutineScope()
    val fields = remember(o) {
        (o.offset + o.rotation.map { Math.toDegrees(it) } + o.scale.map { it * 100 }).map { mutableStateOf("%.2f".format(it)) }
    }
    var uniform by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(o.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Position (mm)" to 0, "Rotation (°)" to 3, "Scale (%)" to 6).forEach { (title, start) ->
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("X", "Y", "Z").forEachIndexed { i, axis ->
                            OutlinedTextField(fields[start + i].value, { v ->
                                fields[start + i].value = v
                                if (start == 6 && uniform) (6..8).forEach { fields[it].value = v }
                            }, label = { Text(axis) }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(uniform, { uniform = it }); Text("Uniform scale")
                }
                Text("Size: %.1f × %.1f × %.1f mm".format(o.size[0], o.size[1], o.size[2]), color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton({
                val v = fields.map { it.value.replace(',', '.').toDoubleOrNull() ?: 0.0 }
                val values = DoubleArray(9) { i -> when { i < 3 -> v[i]; i < 6 -> Math.toRadians(v[i]); else -> (v[i] / 100).coerceAtLeast(0.001) } }
                scope.launch { state.setTransform(state.selected, values) }
                onClose()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
