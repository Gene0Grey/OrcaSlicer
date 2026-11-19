package dev.orcaslicer.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** CustomGCode::Type order. */
val LAYER_ACTIONS = listOf("Colour change", "Pause", "Filament change", "Template", "Custom G-code")

data class LayerGcode(val z: Double, val type: Int, val extruder: Int, val color: String, val extra: String)

/** Ticks beside the layer slider where the plate pauses or changes colour (desktop slider ticks). */
@Composable
fun LayerTicks(gcodes: List<LayerGcode>, zs: List<Float>, modifier: Modifier) = Canvas(modifier.width(6.dp)) {
    if (zs.size < 2) return@Canvas
    // The slider's track runs inside a thumb radius of each end.
    val pad = 10.dp.toPx()
    gcodes.forEach { g ->
        val i = zs.indexOfFirst { it >= g.z - 1e-4 }.takeIf { it >= 0 } ?: return@forEach
        val y = size.height - pad - (size.height - 2 * pad) * i / (zs.size - 1)
        drawLine(if (g.type == 1) Board.amber else Board.tealText, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
    }
}

/** Adds a pause / colour change / filament change / custom G-code at a layer, or removes the one there. */
@Composable
fun LayerActionDialog(state: AppState, z: Double, layer: Int, onDone: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val existing = state.layerGcodes.firstOrNull { kotlin.math.abs(it.z - z) < 1e-4 }
    var type by remember { mutableIntStateOf(if (state.presets.slots.size > 1) 2 else 0) }
    var extruder by remember { mutableIntStateOf(1) }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("LAYER ${layer + 1} · %.2f MM".format(z), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) Text("HERE NOW: ${LAYER_ACTIONS.getOrElse(existing.type) { "?" }.uppercase()}", style = MaterialTheme.typography.labelMedium, color = Board.amber)
                Dropdown(LAYER_ACTIONS[type].uppercase(), LAYER_ACTIONS.map { it.uppercase() }, Modifier.fillMaxWidth()) { type = it }
                if (type == 0 || type == 2) {
                    val slots = state.presets.slots.indices.map { "FILAMENT ${it + 1}" }
                    if (slots.size > 1) Dropdown(slots.getOrElse(extruder - 1) { slots[0] }, slots, Modifier.fillMaxWidth()) { extruder = it + 1 }
                }
                if (type == 1 || type == 4) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), colors = fieldColors,
                    label = { Text(if (type == 1) "Message on the printer" else "G-code") }, singleLine = type == 1)
            }
        },
        confirmButton = {
            TextButton({
                val color = state.presets.slots.getOrNull(extruder - 1)?.color ?: ""
                scope.launch {
                    state.plateCmd("add_gcode", "z" to z, "type" to type, "extruder" to extruder, "color" to color,
                        "extra" to if (type == 4) text else if (type == 1) text else "")
                    onDone()
                }
                onClose()
            }) { Text("ADD", color = Board.tealText) }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton({ scope.launch { state.plateCmd("remove_gcode", "z" to z); onDone() }; onClose() }) { Text("REMOVE", color = Board.red) }
                TextButton(onClose) { Text("CANCEL", color = Board.steelLight) }
            }
        })
}

/** The plate's layer actions on the results board, each removable. */
@Composable
fun LayerActionsList(state: AppState, onChanged: () -> Unit) {
    if (state.layerGcodes.isEmpty()) return
    val scope = rememberCoroutineScope()
    BoardHeading("Layer actions")
    state.layerGcodes.forEach { g ->
        BoardRow("%.2f MM".format(g.z)) {
            if (g.type == 0 || g.type == 2) state.presets.slots.getOrNull(g.extruder - 1)?.let { Swatch(it.color, g.extruder) }
            Text((LAYER_ACTIONS.getOrElse(g.type) { "?" } + if (g.extra.isNotBlank()) " · ${g.extra}" else "").uppercase(), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, maxLines = 1)
            IconButton({ scope.launch { state.plateCmd("remove_gcode", "z" to g.z); onChanged() } }) { Icon(Icons.Default.Close, "Remove", tint = Board.steelLight) }
        }
    }
    Text("SLICE AGAIN TO APPLY CHANGES.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
}

/** The legend of a value colour scheme (speed, height, width, flow, fan, temperature...): a gradient with its values. */
@Composable
fun RangeLegend(host: PlateHost, viewType: Int, unit: String) {
    var legend by remember(viewType) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(viewType) { host.scene?.query({ OrcaCore.previewLegend(viewType) }) { legend = JSONObject(it) } }
    val l = legend ?: return
    val colors = l.optJSONArray("colors")?.let { a -> (0 until a.length()).map { parseHex(a.getString(it)) } }.orEmpty()
    if (colors.isEmpty()) return
    if (l.optString("kind") == "list") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            colors.forEachIndexed { i, c -> Box(Modifier.size(width = 32.dp, height = 24.dp).background(c), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Board.flap) } }
        }
        return
    }
    val values = l.getJSONArray("values").let { a -> (0 until a.length()).map { a.getDouble(it) } }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(18.dp).background(Brush.horizontalGradient(if (colors.size > 1) colors else colors + colors)))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (values.size <= 6) values else listOf(0, values.size / 4, values.size / 2, 3 * values.size / 4, values.size - 1).map { values[it] }
            shown.forEach { v -> Text("%.${if (kotlin.math.abs(v) < 10) 2 else 0}f".format(v), style = MaterialTheme.typography.labelSmall, color = Board.steelLight) }
        }
        if (unit.isNotEmpty()) Text(unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
    }
}

// Units of libvgcode's EViewType ranges, by index in VIEW_TYPES.
val VIEW_UNITS = mapOf(3 to "mm/s", 4 to "mm/s", 5 to "mm", 6 to "mm", 7 to "mm³/s", 8 to "mm³/s", 9 to "s", 10 to "s", 11 to "%", 12 to "°C",
    13 to "", 14 to "mm/s²", 15 to "mm/s")

