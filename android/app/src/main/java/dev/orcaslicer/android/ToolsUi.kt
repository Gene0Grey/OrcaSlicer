package dev.orcaslicer.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The tool sheets a tool key can open (beyond the transform gizmos). */
enum class ToolPanel { None, Cut, Simplify, Boolean, Measure, Text, Svg }

private val toolSlider @Composable get() = SliderDefaults.colors(thumbColor = Board.teal, activeTrackColor = Board.teal, inactiveTrackColor = Board.frameHi)

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.clickable { onChange(!value) }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange, colors = CheckboxDefaults.colors(checkedColor = Board.teal, checkmarkColor = Board.flap))
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium)
    }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, colors = toolSlider)
        Text(format.format(value), Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
    }

/** Cut (desktop Cut gizmo by numbers): plane height and tilt with a live plane, keep/flip/parts options. */
@Composable
fun CutPanel(state: AppState, modifier: Modifier, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val o = state.objects.getOrNull(state.selected) ?: return onClose()
    // The object stands on the bed: heights run from 0 to its size.
    var z by remember(state.selected) { mutableFloatStateOf((o.size[2] / 2).toFloat()) }
    var rx by remember { mutableFloatStateOf(0f) }
    var ry by remember { mutableFloatStateOf(0f) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var flip by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf(false) }
    val zMin = 0f; val zMax = o.size[2].toFloat()
    LaunchedEffect(z, rx, ry, state.selected) { state.tool("cut_preview", "z" to z.toDouble(), "rx" to rx.toDouble(), "ry" to ry.toDouble()) }
    Column(modifier.fillMaxWidth().steel().padding(12.dp)) {
        Text("CUT · ${o.name.uppercase()}", style = MaterialTheme.typography.labelLarge, color = Board.tealText)
        LabeledSlider("Height", z.coerceIn(zMin, zMax), zMin..zMax, "%.2f") { z = it }
        LabeledSlider("Tilt X", rx, -90f..90f, "%.0f°") { rx = it }
        LabeledSlider("Tilt Y", ry, -90f..90f, "%.0f°") { ry = it }
        Row { Check("Keep upper", upper) { upper = it }; Check("Keep lower", lower) { lower = it } }
        Row { Check("Flip upper", flip) { flip = it }; Check("As parts", parts) { parts = it } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ scope.launch { if (state.tool("cut", "z" to z.toDouble(), "rx" to rx.toDouble(), "ry" to ry.toDouble(), "upper" to upper,
                    "lower" to lower, "flip_upper" to flip, "parts" to parts)) onClose() } }) { Text("CUT", color = Board.tealText) }
            TextButton({ scope.launch { state.tool("cut_preview", "on" to false) }; onClose() }) { Text("CLOSE", color = Board.steelLight) }
        }
    }
}

/** Simplify (desktop Simplify gizmo): keep a share of the triangles. */
@Composable
fun SimplifyDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ratio by remember { mutableFloatStateOf(0.5f) }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("SIMPLIFY", style = MaterialTheme.typography.titleMedium) },
        text = { Column { Text("KEEP ${(ratio * 100).toInt()}% OF THE TRIANGLES", style = MaterialTheme.typography.labelMedium)
            Slider(ratio, { ratio = it }, valueRange = 0.05f..0.95f, colors = toolSlider) } },
        confirmButton = { TextButton({ scope.launch { state.tool("simplify", "ratio" to ratio.toDouble()) }; onClose() }) { Text("APPLY", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

/** Mesh boolean between two parts of the picked object (desktop Mesh Boolean gizmo). */
@Composable
fun BooleanDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val o = state.objects.getOrNull(state.selected) ?: return onClose()
    val names = o.volumes.mapIndexed { i, v -> "${i + 1} · ${v.name.ifBlank { "Part" }}".uppercase() }
    var a by remember { mutableIntStateOf(0) }
    var b by remember { mutableIntStateOf(1.coerceAtMost(names.lastIndex)) }
    var op by remember { mutableIntStateOf(0) }
    var keep by remember { mutableStateOf(false) }
    val ops = listOf("union", "difference", "intersection")
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("MESH BOOLEAN", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (names.size < 2) Text("THE OBJECT HAS ONE PART. ADD A PART, OR ASSEMBLE OBJECTS INTO ONE FIRST.", style = MaterialTheme.typography.labelMedium, color = Board.amber)
                else {
                    BoardRow("Part A", labelWidth = 60.dp) { Dropdown(names[a], names, Modifier.weight(1f)) { a = it } }
                    BoardRow("Part B", labelWidth = 60.dp) { Dropdown(names[b], names, Modifier.weight(1f)) { b = it } }
                    BoardRow("Do", labelWidth = 60.dp) { Dropdown(ops[op].uppercase(), ops.map { it.uppercase() }, Modifier.weight(1f)) { op = it } }
                    Check("Keep part B", keep) { keep = it }
                }
            }
        },
        confirmButton = { TextButton({ scope.launch { state.tool("boolean", "a" to a, "b" to b, "op" to ops[op], "keep" to keep) }; onClose() }, enabled = names.size > 1) {
            Text("APPLY", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

private val EMBOSS_TARGETS = listOf("New object" to -1, "Part of the object" to 0, "Engrave (negative part)" to 1, "Modifier" to 2)

/** Text emboss (desktop Text tool): system fonts, size, depth; as an object or on the picked object. */
@Composable
fun TextToolDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Orca") }
    var fonts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var font by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf("10") }
    var depth by remember { mutableStateOf("2") }
    var target by remember { mutableIntStateOf(if (state.selected >= 0) 1 else 0) }
    LaunchedEffect(Unit) {
        fonts = withContext(Dispatchers.IO) { JSONArray(OrcaCore.fonts()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("name") to it.getString("path") } } }
            .sortedBy { it.first }
        font = fonts.indexOfFirst { it.first == "Roboto-Regular" }.coerceAtLeast(0)
    }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("TEXT", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), colors = fieldColors, label = { Text("Text") })
                if (fonts.isNotEmpty()) Dropdown(fonts[font].first.uppercase(), fonts.map { it.first.uppercase() }, Modifier.fillMaxWidth()) { font = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(size, { size = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Height mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(depth, { depth = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Depth mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                Dropdown(EMBOSS_TARGETS[target].first.uppercase(), EMBOSS_TARGETS.map { it.first.uppercase() }, Modifier.fillMaxWidth()) { target = it }
            }
        },
        confirmButton = { TextButton({
            scope.launch { state.tool("text", "text" to text, "font" to fonts.getOrNull(font)?.second, "size" to (size.toDoubleOrNull() ?: 10.0),
                "depth" to (depth.toDoubleOrNull() ?: 2.0), "type" to EMBOSS_TARGETS[target].second) }
            onClose()
        }, enabled = text.isNotBlank()) { Text("ADD", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

/** SVG emboss (desktop SVG tool) for a picked .svg file. */
@Composable
fun SvgToolDialog(state: AppState, path: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf("40") }
    var depth by remember { mutableStateOf("2") }
    var target by remember { mutableIntStateOf(if (state.selected >= 0) 1 else 0) }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("SVG · ${java.io.File(path).name.uppercase()}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(size, { size = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Size mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(depth, { depth = it }, Modifier.weight(1f), colors = fieldColors, label = { Text("Depth mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                Dropdown(EMBOSS_TARGETS[target].first.uppercase(), EMBOSS_TARGETS.map { it.first.uppercase() }, Modifier.fillMaxWidth()) { target = it }
            }
        },
        confirmButton = { TextButton({
            scope.launch { state.tool("svg", "path" to path, "size" to (size.toDoubleOrNull() ?: 40.0), "depth" to (depth.toDoubleOrNull() ?: 2.0),
                "type" to EMBOSS_TARGETS[target].second) }
            onClose()
        }) { Text("ADD", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

/** Measure readout: the picked features and, with two, distance and angle (desktop Measure gizmo). */
@Composable
fun MeasurePanel(result: JSONObject?, modifier: Modifier, onClear: () -> Unit, onClose: () -> Unit) {
    Column(modifier.fillMaxWidth().steel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("MEASURE · TAP TWO FEATURES", style = MaterialTheme.typography.labelLarge, color = Board.tealText)
        val feats = result?.optJSONArray("features")
        if (feats != null) for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val extra = when (f.optString("type")) { "edge" -> " · %.2f MM".format(f.optDouble("length")); "circle" -> " · R %.2f MM".format(f.optDouble("radius")); else -> "" }
            Text("${i + 1}: ${f.optString("type").uppercase()}$extra", style = MaterialTheme.typography.labelMedium)
        }
        result?.optJSONObject("result")?.let { r ->
            if (r.has("distance")) Text("DISTANCE %.3f MM".format(r.getDouble("distance")), style = MaterialTheme.typography.titleMedium, color = Board.amber)
            if (r.has("distanceInfinite")) Text("PERPENDICULAR %.3f MM".format(r.getDouble("distanceInfinite")), style = MaterialTheme.typography.labelMedium)
            r.optJSONArray("xyz")?.let { x -> Text("X %.2f  Y %.2f  Z %.2f".format(x.getDouble(0), x.getDouble(1), x.getDouble(2)), style = MaterialTheme.typography.labelMedium) }
            if (r.has("angle")) Text("ANGLE %.2f°".format(r.getDouble("angle")), style = MaterialTheme.typography.labelMedium)
        }
        Row { TextButton(onClear) { Text("CLEAR", color = Board.tealText) }; TextButton(onClose) { Text("CLOSE", color = Board.steelLight) } }
    }
}

/** The tools the left rail's TOOLS key opens. */
@Composable
fun ToolSheet(state: AppState, host: PlateHost, onPick: (ToolPanel) -> Unit, onSvg: (String) -> Unit, onPaint: (Int) -> Unit, onVariableLayers: () -> Unit,
              onTexture: (java.io.File) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hasObject = state.selected >= 0
    val items = listOf(
        Triple("Cut", hasObject) { onPick(ToolPanel.Cut) },
        Triple("Simplify", hasObject) { onPick(ToolPanel.Simplify) },
        Triple("Mesh boolean", hasObject) { onPick(ToolPanel.Boolean) },
        Triple("Measure", state.objects.isNotEmpty()) { onPick(ToolPanel.Measure) },
        Triple("Text", true) { onPick(ToolPanel.Text) },
        Triple("SVG", true) { host.pickFile { f -> onSvg(f.path) } },
        Triple("Assemble selection", state.pickedCount > 1) { scope.launch { state.edit("assemble") } },
        Triple("Support painting", hasObject) { onPaint(1) },
        Triple("Seam painting", hasObject) { onPaint(2) },
        Triple("Colour painting", hasObject) { onPaint(3) },
        Triple("Fuzzy skin painting", hasObject) { onPaint(4) },
        Triple("Brim ears", hasObject) { onPaint(5) },
        Triple("Variable layer height", hasObject) { onVariableLayers() },
        Triple("Texture displacement", hasObject) { host.pickFile(onTexture) },
    )
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("TOOLS", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach { (label, enabled, go) ->
                    Text(label.uppercase(), Modifier.fillMaxWidth().clickable(enabled) { onClose(); go() }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = if (enabled) Board.letter else Board.steelMid)
                }
                if (!hasObject) Text("PICK AN OBJECT FOR THE OBJECT TOOLS.", style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
            }
        },
        confirmButton = { TextButton(onClose) { Text("CLOSE", color = Board.steelLight) } })
}
