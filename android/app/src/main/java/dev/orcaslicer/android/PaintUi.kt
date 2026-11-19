package dev.orcaslicer.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Brush settings the 3D view reads while painting (gizmo mode 6) or placing brim ears (7). */
class PaintBrush {
    var mode by mutableIntStateOf(0)      // 1 support, 2 seam, 3 colour, 4 fuzzy skin, 5 brim ears
    var state by mutableIntStateOf(1)     // EnforcerBlockerType: 0 erase, 1 enforce / filament 1, 2 block / filament 2, ...
    var radius by mutableFloatStateOf(2f) // mm
    var tool by mutableIntStateOf(0)      // 0 brush, 1 smart fill
}

val PAINT_TITLES = mapOf(1 to "Support painting", 2 to "Seam painting", 3 to "Colour painting", 4 to "Fuzzy skin painting", 5 to "Brim ears")

private val paintSlider @Composable get() = SliderDefaults.colors(thumbColor = Board.teal, activeTrackColor = Board.teal, inactiveTrackColor = Board.frameHi)

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected, onClick, { Text(label.uppercase(), style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Board.teal, selectedLabelColor = Board.flap, labelColor = Board.letter))

/** The painting gizmo's panel: what to paint, brush or smart fill, brush size, clear. One finger paints, two move the view. */
@Composable
fun PaintPanel(state: AppState, brush: PaintBrush, modifier: Modifier, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().steel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(PAINT_TITLES[brush.mode].orEmpty().uppercase() + if (brush.mode == 5) " · TAP THE OBJECT TO ADD" else " · ONE FINGER PAINTS",
            style = MaterialTheme.typography.labelLarge, color = Board.tealText)
        if (brush.mode != 5) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (brush.mode) {
                1, 2 -> { Chip("Enforce", brush.state == 1) { brush.state = 1 }; Chip("Block", brush.state == 2) { brush.state = 2 } }
                3 -> state.presets.slots.forEachIndexed { i, _ -> Chip("${i + 1}", brush.state == i + 1) { brush.state = i + 1 } }
                4 -> Chip("Fuzzy", brush.state == 1) { brush.state = 1 }
            }
            Chip("Erase", brush.state == 0) { brush.state = 0 }
        }
        if (brush.mode != 5) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Brush", brush.tool == 0) { brush.tool = 0 }
            Chip("Smart fill", brush.tool == 1) { brush.tool = 1 }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (brush.mode == 5) "EAR" else "SIZE", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
            Slider(brush.radius, { brush.radius = it }, Modifier.weight(1f), valueRange = 0.5f..15f, colors = paintSlider)
            Text("%.1f MM".format(brush.radius), style = MaterialTheme.typography.labelMedium)
        }
        Row {
            if (brush.mode == 5) TextButton({ scope.launch { withContext(Dispatchers.IO) {
                OrcaCore.brimEar(1, 0f, 0f, 1, 1, FloatArray(16), FloatArray(16), brush.radius) }; state.afterGizmo() } }) { Text("AUTO", color = Board.tealText) }
            TextButton({ scope.launch { withContext(Dispatchers.IO) { OrcaCore.paintClear(brush.mode) }; state.afterGizmo() } }) { Text("CLEAR ALL", color = Board.red) }
            TextButton(onClose) { Text("DONE", color = Board.tealText) }
        }
    }
}

/**
 * Variable layer height (desktop layer editing): the profile as layer height (across) over Z (up).
 * Drag right on a height to thicken layers there, left to thin them; adaptive, smooth and reset below.
 */
@Composable
fun VariableLayerDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var quality by remember { mutableFloatStateOf(0.5f) }
    var band by remember { mutableFloatStateOf(2f) }
    suspend fun run(cmd: String, vararg args: Pair<String, Any?>) {
        val a = JSONObject(); args.forEach { (k, v) -> a.put(k, v) }
        val r = JSONObject(withContext(Dispatchers.IO) { OrcaCore.layerProfile(cmd, a.toString()) })
        if (r.has("error")) state.error = r.getString("error") else data = r
        if (cmd != "get") state.invalidate()
    }
    LaunchedEffect(Unit) { run("get") }
    val d = data
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("VARIABLE LAYER HEIGHT", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d == null) { Text("LOADING…"); return@Column }
                val prof = d.getJSONArray("profile").let { a -> (0 until a.length()).map { a.getDouble(it) } }.chunked(2).filter { it.size == 2 }
                val minH = d.getDouble("min").coerceAtLeast(0.01); val maxH = d.getDouble("max").coerceAtLeast(minH + 0.01)
                val height = d.getDouble("height").coerceAtLeast(1.0)
                Text("MIN %.2f · MAX %.2f MM".format(minH, maxH), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                var start by remember { mutableStateOf(false) }
                Canvas(Modifier.fillMaxWidth().height(260.dp)
                    .pointerInput(d) {
                        detectDragGestures(onDragStart = { start = true }) { change, drag ->
                            val z = (1 - change.position.y / size.height) * height
                            val action = if (drag.x >= 0) 0 else 1
                            val first = start; start = false
                            scope.launch { run("adjust", "z" to z, "delta" to 0.004 * kotlin.math.abs(drag.x), "band" to band.toDouble(), "action" to action, "start" to first) }
                        }
                    }
                    .pointerInput(d) {
                        detectTapGestures(onLongPress = { p ->
                            scope.launch { run("adjust", "z" to (1 - p.y / size.height) * height, "delta" to 0.0, "band" to band.toDouble(), "action" to 3) }
                        })
                    }) {
                    fun x(h: Double) = ((h - minH) / (maxH - minH)).toFloat().coerceIn(0f, 1f) * size.width
                    fun y(z: Double) = (1 - (z / height).toFloat()) * size.height
                    drawLine(Board.steelMid, Offset(x(minH), 0f), Offset(x(minH), size.height))
                    drawLine(Board.steelMid, Offset(x(maxH), 0f), Offset(x(maxH), size.height))
                    if (prof.isNotEmpty()) {
                        val path = Path()
                        prof.forEachIndexed { i, (z, h) -> if (i == 0) path.moveTo(x(h), y(z)) else path.lineTo(x(h), y(z)) }
                        drawPath(path, Board.tealText, style = Stroke(3.dp.toPx()))
                    }
                }
                Text("DRAG RIGHT: THICKER · LEFT: THINNER · LONG PRESS: SMOOTH THERE", style = MaterialTheme.typography.labelSmall, color = Board.steelMid)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BAND", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall)
                    Slider(band, { band = it }, Modifier.weight(1f), valueRange = 0.5f..10f, colors = paintSlider)
                    Text("%.1f MM".format(band), style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("QUALITY", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall)
                    Slider(quality, { quality = it }, Modifier.weight(1f), valueRange = 0f..1f, colors = paintSlider)
                    TextButton({ scope.launch { run("adaptive", "quality" to quality.toDouble()) } }) { Text("ADAPTIVE", color = Board.tealText) }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("DONE", color = Board.tealText) } },
        dismissButton = {
            Row {
                TextButton({ scope.launch { run("smooth", "radius" to 5, "keep_min" to false) } }) { Text("SMOOTH", color = Board.tealText) }
                TextButton({ scope.launch { run("reset") } }) { Text("RESET", color = Board.red) }
            }
        })
}
