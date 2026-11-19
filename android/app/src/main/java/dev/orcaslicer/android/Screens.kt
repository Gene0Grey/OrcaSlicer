package dev.orcaslicer.android

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: the name on a flap header, two departures (new / open), and the recent-files board. */
@Composable
fun HomeScreen(state: AppState, onNew: () -> Unit, onOpen: () -> Unit, onOpenProject: () -> Unit, onOpenGcode: () -> Unit, onRecent: (String) -> Unit) {
    val stamp = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().steel().padding(horizontal = 12.dp, vertical = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                "ORCASLICER".forEach { c ->
                    Box(Modifier.weight(1f).aspectRatio(0.72f).flap(), contentAlignment = Alignment.Center) {
                        FlapText(c.toString(), style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlapCell("ANDROID", color = Board.tealText, style = MaterialTheme.typography.labelMedium)
                FlapCell("2.5.1", style = MaterialTheme.typography.labelMedium)
                state.presets.printer.takeIf { it.isNotBlank() }?.let {
                    FlapCell(it.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Departure(Icons.Default.Add, "New project", "Empty plate, current printer", Board.teal, onNew)
            Departure(Icons.Default.FolderOpen, "Open model", "STL · 3MF · OBJ · STEP", Board.steelLight, onOpen)
            Departure(Icons.Default.Inventory2, "Open project", "3MF with plates and settings", Board.steelLight, onOpenProject)
            Departure(Icons.Default.Visibility, "Open G-code", "View a sliced file", Board.steelLight, onOpenGcode)
        }
        BoardHeading("Recently opened")
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
            Text("OPENED", Modifier.width(104.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
            Text("FILE", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
            Text("TYPE", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelMid, textAlign = TextAlign.End)
        }
        val files = state.recent.map(::File).filter { it.exists() }
        if (files.isEmpty()) Text("NO FILES YET. OPEN A MODEL TO START.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
        files.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { onRecent(f.path) }.heightIn(min = 56.dp).drawBehind {
                drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
            }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stamp.format(Date(f.lastModified())).uppercase(), Modifier.width(104.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
                Text(f.nameWithoutExtension.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.extension.uppercase(), Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, color = Board.tealText, textAlign = TextAlign.End)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Departure(icon: ImageVector, title: String, detail: String, accent: Color, onClick: () -> Unit) =
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).flap(accent)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Board.letter)
                Text(detail.uppercase(), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
            }
        }
    }

/** Project: what this plate will print with, and what is on it. */
@Composable
fun ProjectScreen(state: AppState, host: PlateHost) {
    var name by remember { mutableStateOf(state.projectName) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BoardHeading("Project")
        OutlinedTextField(name, { name = it; state.projectName = it }, label = { Text("Project name") }, singleLine = true, colors = fieldColors,
            modifier = Modifier.fillMaxWidth().padding(16.dp))
        BoardRow("Printer") { FlapCell(state.presets.printer.uppercase(), Modifier.weight(1f)) }
        BoardRow("Process") { FlapCell(state.presets.process.uppercase(), Modifier.weight(1f)) }
        state.presets.slots.forEachIndexed { i, s ->
            BoardRow("Filament ${i + 1}") { Swatch(s.color, i + 1); FlapCell(s.name.uppercase(), Modifier.weight(1f)) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Departure(Icons.Default.Save, "Save project", "3MF · plates · settings", Board.teal) { host.saveProject(false) }
        }
        state.plates.forEachIndexed { pi, p ->
            BoardHeading(plateLabel(state, pi), Modifier.padding(top = 12.dp))
            val on = state.objects.filter { it.plate == pi }
            if (on.isEmpty()) Text("EMPTY PLATE.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
            on.forEach { o ->
                BoardRow(o.name.ifBlank { "Object" } + if (o.instances > 1) " ×${o.instances}" else "") {
                    FlapCell("%.1f × %.1f × %.1f MM".format(o.size[0], o.size[1], o.size[2]), Modifier.weight(1f), color = if (o.outside) Board.red else Board.letter)
                }
            }
        }
        state.stats?.let { s ->
            BoardHeading("Last slice", Modifier.padding(top = 12.dp))
            BoardRow("Print time") { FlapCell(formatTime(s.totalTime), Modifier.weight(1f)) }
            BoardRow("Filament") { FlapCell("%.2f M  ·  %.2f G".format(s.meters, s.grams), Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Exact move / rotate / scale for the picked object (the desktop gizmos' number panels). */
@Composable
fun TransformDialog(state: AppState, onClose: () -> Unit) {
    val o = state.objects.getOrNull(state.selected) ?: return onClose()
    val scope = rememberCoroutineScope()
    val index = state.selected
    TransformFieldsDialog(o.name.ifBlank { "Object" }, o.offset, o.rotation, o.scale, o.size, onClose) { values ->
        scope.launch { state.setTransform(index, values) }
    }
}

/** Position (mm), rotation (degrees) and scale (%) fields; [onApply] gets offset, rotation (radians), scale factors. */
@Composable
fun TransformFieldsDialog(title: String, offset: List<Double>, rotation: List<Double>, scale: List<Double>, size: List<Double>,
                          onClose: () -> Unit, onApply: (DoubleArray) -> Unit) {
    val fields = remember(offset, rotation, scale) {
        (offset + rotation.map { Math.toDegrees(it) } + scale.map { it * 100 }).map { mutableStateOf("%.2f".format(it)) }
    }
    var uniform by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Position · mm" to 0, "Rotation · degrees" to 3, "Scale · %" to 6).forEach { (heading, start) ->
                    Text(heading.uppercase(), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("X", "Y", "Z").forEachIndexed { i, axis ->
                            OutlinedTextField(fields[start + i].value, { v ->
                                fields[start + i].value = v
                                if (start == 6 && uniform) (6..8).forEach { fields[it].value = v }
                            }, label = { Text(axis) }, singleLine = true, colors = fieldColors, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(uniform, { uniform = it }, colors = CheckboxDefaults.colors(checkedColor = Board.teal, checkmarkColor = Board.flap))
                    Text("UNIFORM SCALE", style = MaterialTheme.typography.labelMedium)
                }
                Text("SIZE  %.1f × %.1f × %.1f MM".format(size[0], size[1], size[2]), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
            }
        },
        confirmButton = {
            TextButton({
                val v = fields.map { it.value.replace(',', '.').toDoubleOrNull() ?: 0.0 }
                onApply(DoubleArray(9) { i -> when { i < 3 -> v[i]; i < 6 -> Math.toRadians(v[i]); else -> (v[i] / 100).coerceAtLeast(0.001) } })
                onClose()
            }) { Text("APPLY", color = Board.tealText) }
        },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } },
    )
}
