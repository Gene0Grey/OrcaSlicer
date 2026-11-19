package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Preview data reported by the GL thread after libvgcode loads a slice. */
data class PreviewInfo(val zs: List<Float>, val fullMoves: Pair<Int, Int>, val roleColors: Map<Int, String>) {
    companion object {
        fun parse(json: String): PreviewInfo? {
            val o = JSONObject(json)
            if (!o.optBoolean("loaded")) return null
            val zs = o.getJSONArray("zs").let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
            val full = o.getJSONArray("full")
            val roles = o.getJSONObject("roles")
            return PreviewInfo(zs, full.getInt(0) to full.getInt(1), roles.keys().asSequence().associate { it.toInt() to roles.getJSONObject(it).getString("color") })
        }
    }
}

// Same order as libvgcode::EViewType; the desktop's "Color scheme" list.
val VIEW_TYPES = listOf("Summary", "Line Type", "Color Print", "Speed", "Actual Speed", "Height", "Width", "Flow",
    "Actual Flow", "Layer Time (linear)", "Layer Time (log)", "Fan Speed", "Temperature", "Pressure Advance", "Acceleration", "Jerk", "Tool")

fun formatTime(seconds: Double): String {
    val s = seconds.roundToInt(); val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return when { h > 0 -> "${h}H ${m}M"; m > 0 -> "${m}M ${sec}S"; else -> "${sec}S" }
}

private val sliderColors @Composable get() = SliderDefaults.colors(
    thumbColor = Board.teal, activeTrackColor = Board.teal, inactiveTrackColor = Board.frameHi,
    activeTickColor = Board.teal, inactiveTickColor = Board.frameHi)

/** The results board: totals that flip into place, colour scheme, and the line-type legend. */
@Composable
fun ResultsBoard(state: AppState, info: PreviewInfo?, host: PlateHost) {
    val stats = state.stats
    if (stats == null) {
        BoardHeading("Results")
        Text("NOTHING SLICED YET. SLICE THE PLATE ON PREPARE.", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
        return
    }
    var viewType by remember { mutableIntStateOf(1) }
    val hidden = remember(info) { mutableStateMapOf<Int, Boolean>() }
    var travel by remember(info) { mutableStateOf(false) }

    // Peek: the three numbers a hobbyist came for.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Total("Print time", formatTime(stats.totalTime), Modifier.weight(1.3f))
        Total("Filament", "%.1f G".format(stats.grams), Modifier.weight(1f))
        Total("Layers", "${info?.zs?.size ?: "-"}", Modifier.weight(0.8f))
    }
    BoardRow("Color scheme") {
        Dropdown(VIEW_TYPES[viewType].uppercase(), VIEW_TYPES.map { it.uppercase() }, Modifier.weight(1f)) { t ->
            viewType = t; host.scene?.onGl { OrcaCore.previewSetViewType(t) }
        }
    }
    if (viewType != 1) Text("THE LEGEND BELOW LISTS LINE TYPES; THE PLATE SHOWS ${VIEW_TYPES[viewType].uppercase()}.",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
    // Legend: the departure table.
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Head("Line type", 2.2f); Head("Time", 1.1f); Head("%", 0.7f); Head("G", 0.7f); Head("Show", 0.8f)
    }
    stats.roles.forEach { r ->
        LegendRow(r.name, parseHex(info?.roleColors?.get(r.role) ?: "#888888"), formatTime(r.time), "%.1f".format(r.percent), "%.2f".format(r.grams),
            hidden[r.role] != true) { v -> hidden[r.role] = !v; host.scene?.onGl { OrcaCore.previewToggleRole(r.role) } }
    }
    LegendRow("Travel", Board.steelMid, formatTime(stats.travelTime), "", "", travel) { v -> travel = v; host.scene?.onGl { OrcaCore.previewToggleTravel() } }
    BoardRow("Total filament") { FlapCell("%.2f M   %.2f G".format(stats.meters, stats.grams), Modifier.weight(1f)) }
    BoardRow("Prepare time") { FlapCell(formatTime(stats.prepareTime.toDouble()), Modifier.weight(1f)) }
    // Per filament, as the desktop's filament table: length, weight (with flush), cost.
    if (stats.filaments.isNotEmpty()) {
        BoardHeading("Filament use")
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Head("Filament", 1.6f); Head("M", 1f); Head("G", 1f); Head("Flush G", 1f); Head("Cost", 1f)
        }
        stats.filaments.forEach { f ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically) {
                    state.presets.slots.getOrNull(f.filament - 1)?.let { Swatch(it.color, f.filament) } ?: Text("${f.filament}")
                }
                Text("%.2f".format(f.meters), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("%.2f".format(f.grams), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("%.2f".format(f.flushGrams), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("%.2f".format(f.cost), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
        }
        BoardRow("Total cost") { FlapCell("%.2f".format(stats.cost), Modifier.weight(1f)) }
        if (stats.changes > 0) BoardRow("Filament changes") { FlapCell("${stats.changes}", Modifier.weight(1f)) }
    }
    Box(Modifier.heightIn(min = 24.dp))
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
    FlapCell(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = Board.letter)
}

@Composable
private fun RowScope.Head(text: String, w: Float) =
    Text(text.uppercase(), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)

@Composable
private fun LegendRow(name: String, swatch: androidx.compose.ui.graphics.Color, time: String, percent: String, grams: String, shown: Boolean, onToggle: (Boolean) -> Unit) {
    val dim = if (shown) Board.letter else Board.steelMid
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
        drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
    }.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 6.dp, height = 22.dp).background(if (shown) swatch else Board.steelMid))
            Text(name.uppercase(), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = dim, maxLines = 1)
        }
        Text(time, Modifier.weight(1.1f), style = MaterialTheme.typography.labelMedium, color = dim)
        Text(percent, Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = dim)
        Text(grams, Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = dim)
        Box(Modifier.weight(0.8f), contentAlignment = Alignment.CenterStart) {
            Checkbox(shown, onToggle, colors = CheckboxDefaults.colors(checkedColor = Board.teal, checkmarkColor = Board.flap, uncheckedColor = Board.steelMid))
        }
    }
}

/** Layer range (right edge) and moves within the top layer (bottom), with flap readouts. */
@Composable
fun PreviewSliders(stats: SliceStats?, info: PreviewInfo?, scene: SceneView?, bottomInset: Dp) {
    if (stats == null || info == null) {
        if (stats != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlapCell("LOADING PREVIEW", color = Board.tealText) }
        return
    }
    val layers = info.zs.size
    var range by remember(info.zs) { mutableStateOf(0f..(layers - 1).coerceAtLeast(0).toFloat()) }
    // The moves slider walks the top visible layer (as on the desktop); it re-ranges when the layer range moves.
    var move by remember(info) { mutableStateOf<MoveState?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val refresh: () -> Unit = { scene?.query({ OrcaCore.previewCurrent() }) { move = MoveState.parse(it) } }
    LaunchedEffect(info) { refresh() }
    val lines = remember(stats.gcode) { GcodeLines(File(stats.gcode)) }
    var window by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    LaunchedEffect(move?.line) {
        val l = move?.line ?: 0
        window = withContext(Dispatchers.IO) { runCatching { lines.window(l, 2) }.getOrDefault(emptyList()) }
    }

    Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
        // Above the moves readout (position, G-code lines, slider), which owns the bottom strip.
        if (layers > 1) Column(Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(end = 6.dp, top = 80.dp, bottom = 236.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            LayerFlap(range.endInclusive.roundToInt(), info.zs)
            RangeSlider(
                value = range, onValueChange = { r ->
                    range = r
                    scene?.onGl { OrcaCore.previewSetLayers(r.start.roundToInt(), r.endInclusive.roundToInt()) }
                    refresh()
                },
                valueRange = 0f..(layers - 1).toFloat(), colors = sliderColors, modifier = Modifier.weight(1f).vertical(),
            )
            LayerFlap(range.start.roundToInt(), info.zs)
        }
        val m = move
        if (m != null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Board.flap.copy(alpha = 0.88f))) {
            // Tool position at the current move, then the G-code lines around it (current line lit teal).
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FlapCell("X %.2f".format(m.x), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                FlapCell("Y %.2f".format(m.y), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                FlapCell("Z %.2f".format(m.z), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                FlapCell("F %.0f".format(m.speed), Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, color = Board.tealText)
            }
            window.forEach { (n, text) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$n", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = if (n == m.line) Board.amber else Board.steelLight)
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (n == m.line) Board.tealText else Board.steelLight, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val lo = m.enabled.first.toFloat()
                val hi = m.enabled.second.toFloat().coerceAtLeast(lo + 1f)
                Text("MOVES", style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                Slider(
                    value = (dragging ?: m.visibleEnd.toFloat()).coerceIn(lo, hi), onValueChange = { v ->
                        dragging = v
                        scene?.onGl { OrcaCore.previewSetMoves(m.enabled.first, v.roundToInt()) }
                        refresh()
                    },
                    onValueChangeFinished = { dragging = null },
                    valueRange = lo..hi,
                    colors = sliderColors, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("${m.visibleEnd - m.enabled.first}", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
            }
        }
    }
}

/** Moves slider state from libvgcode: the top layer's move range and the tool at the current move. */
data class MoveState(val enabled: Pair<Int, Int>, val visibleEnd: Int, val x: Double, val y: Double, val z: Double, val speed: Double, val line: Int) {
    companion object {
        fun parse(json: String): MoveState? {
            val o = JSONObject(json)
            if (!o.has("enabled")) return null
            val e = o.getJSONArray("enabled"); val v = o.getJSONArray("visible")
            return MoveState(e.getInt(0) to e.getInt(1), v.getInt(1), o.getDouble("x"), o.getDouble("y"), o.getDouble("z"),
                o.getDouble("speed"), o.getInt("line"))
        }
    }
}

@Composable
private fun LayerFlap(layer: Int, zs: List<Float>) = Column(Modifier.flap(Board.frameHi).padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    FlapText("${layer + 1}".padStart(3), style = MaterialTheme.typography.titleMedium)
    FlapText("%.2f".format(zs.getOrElse(layer) { 0f }), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
}

/** Rotates a horizontal control to vertical (bottom = start), swapping its measured size. */
fun Modifier.vertical() = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints(minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth, maxHeight = constraints.maxWidth))
        layout(placeable.height, placeable.width) {
            placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2)
        }
    }
    .graphicsLayer { rotationZ = 270f }
