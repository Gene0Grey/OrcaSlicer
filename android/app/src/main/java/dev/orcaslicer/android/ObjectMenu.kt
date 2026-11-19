package dev.orcaslicer.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File

/** One tappable board row of the object menu. */
@Composable
private fun Action(label: String, detail: String = "", color: androidx.compose.ui.graphics.Color = Board.letter, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color)
        if (detail.isNotEmpty()) Text(detail.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
    }

/**
 * The desktop's object right-click menu for the picked object (or the selection): instances, mirror,
 * scale, units, filament, printable, settings copy/paste, split/assemble, repair, reload/replace,
 * export, align/distribute.
 */
@Composable
fun ObjectMenuDialog(state: AppState, host: PlateHost, onClose: () -> Unit) {
    val o = state.objects.getOrNull(state.selected) ?: return onClose()
    val scope = rememberCoroutineScope()
    val many = state.pickedCount
    var rename by remember { mutableStateOf(false) }
    fun run(cmd: String, vararg args: Pair<String, Any?>, close: Boolean = true) {
        scope.launch { state.edit(cmd, *args) }
        if (close) onClose()
    }
    fun export(ext: String, objects: List<Int>, name: String) = host.saveFile("$name.$ext") { f ->
        state.edit("export", "path" to f.path, "format" to ext, "objects" to objects)
    }
    val pickedIdx = state.objects.indices.filter { state.objects[it].selected }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogBar(if (many > 1) "$many objects" else o.name.ifBlank { "Object" }, Icons.Default.Close, onClose)
                if (many > 1) {
                    BoardHeading("Align")
                    listOf("left" to "Align left", "center_x" to "Align left-right centre", "right" to "Align right",
                        "front" to "Align front", "center_y" to "Align front-back centre", "back" to "Align back").forEach { (m, l) ->
                        Action(l) { run("align", "mode" to m) }
                    }
                    Action("Distribute left-right") { run("distribute", "axis" to 0) }
                    Action("Distribute front-back") { run("distribute", "axis" to 1) }
                    BoardHeading("Selection")
                    Action("Assemble into one object", "parts") { run("assemble") }
                    Action("Export as one STL") { export("stl", pickedIdx, "objects"); onClose() }
                    Action("Delete selected", color = Board.red) { scope.launch { state.objectOp(0) }; onClose() }
                }

                BoardHeading("Instances")
                BoardRow("Copies") {
                    IconButton({ run("instances", "count" to o.instances - 1, close = false) }, enabled = o.instances > 1) { Icon(Icons.Default.Remove, "Remove instance") }
                    FlapCell("${o.instances}", color = Board.tealText)
                    IconButton({ run("instances", "count" to o.instances + 1, close = false) }) { Icon(Icons.Default.Add, "Add instance") }
                }
                Action("Fill plate with copies") { run("fill_bed") }

                BoardHeading("Transform")
                Action("Mirror along X") { run("mirror", "axis" to 0, close = false) }
                Action("Mirror along Y") { run("mirror", "axis" to 1, close = false) }
                Action("Mirror along Z") { run("mirror", "axis" to 2, close = false) }
                Action("Scale to build volume") { run("scale_to_bed") }
                // ConversionType: 0 to inch, 1 from inch, 2 to meter, 3 from meter (Model.hpp).
                if (o.inch) Action("Restore to inch") { run("units", "type" to 0) } else Action("Convert from inches") { run("units", "type" to 1) }
                if (o.meter) Action("Restore to meter") { run("units", "type" to 2) } else Action("Convert from meters") { run("units", "type" to 3) }

                BoardHeading("Object")
                Action("Rename", o.name) { rename = true }
                BoardRow("Printable") {
                    Text(if (o.printable) "SLICED WITH THE PLATE" else "LEFT OUT OF THE SLICE", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = if (o.printable) Board.steelLight else Board.amber)
                    Switch(o.printable, { run("printable", "value" to it, close = false) }, colors = switchColors)
                }
                BoardRow("Filament") {
                    val choices = filamentChoices(state)
                    Dropdown(choices.getOrElse(o.filament) { choices[0] }, choices, Modifier.weight(1f)) { f -> run("filament", "value" to f, close = false) }
                }
                Action("Copy process settings") { run("copy_settings") }
                Action("Paste process settings") { run("paste_settings") }
                Action("Split to objects") { run("split_objects") }
                Action("Split to parts") { run("split_parts") }
                if (o.volumes.count { it.type == 0 } > 1) Action("Merge parts into one") { run("merge_parts") }
                Action("Repair mesh", "merge vertices · drop degenerate faces") { run("repair") }
                if (o.source.isNotBlank()) Action("Reload from disk", File(o.source).name) { run("reload") }
                Action("Replace with 3D file…") { onClose(); host.pickFile { f -> scope.launch { state.edit("replace", "path" to f.path) } } }

                BoardHeading("Export")
                val name = o.name.ifBlank { "object" }.substringBeforeLast('.')
                Action("Export as STL") { export("stl", listOf(state.selected), name); onClose() }
                Action("Export as DRC") { export("drc", listOf(state.selected), name); onClose() }
                Action("Export as OBJ") { export("obj", listOf(state.selected), name); onClose() }
            }
        }
    }
    if (rename) TextDialog("Rename object", o.name, { rename = false }) { n -> run("rename", "name" to n, close = false) }
}

private val PRIMITIVES = listOf("Cube", "Cylinder", "Sphere", "Cone", "Disc", "Torus")

/** Plate › Add primitive / handy model (the desktop's "Add Primitive" and "Add Handy models"). */
@Composable
fun AddShapeDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("ADD TO THE PLATE", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("PRIMITIVES", style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                PRIMITIVES.forEach { s ->
                    Text(s.uppercase(), Modifier.fillMaxWidth().clickable { onClose(); scope.launch { state.edit("add_object", "shape" to s) } }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
                Text("HANDY MODELS", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                state.handyModels.forEach { f ->
                    Text(f.nameWithoutExtension.replace('_', ' ').uppercase(), Modifier.fillMaxWidth()
                        .clickable { onClose(); scope.launch { state.edit("add_object", "path" to f.path) } }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge, color = Board.tealText)
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}
