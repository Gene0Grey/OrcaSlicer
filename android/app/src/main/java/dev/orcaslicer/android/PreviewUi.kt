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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.Role
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
    Box(Modifier.heightIn(min = 24.dp))
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
    FlapCell(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = Board.letter, flipIn = true)
}

@Composable
private fun RowScope.Head(text: String, w: Float) =
    Text(text.uppercase(), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)

@Composable
private fun LegendRow(name: String, swatch: androidx.compose.ui.graphics.Color, time: String, percent: String, grams: String, shown: Boolean, onToggle: (Boolean) -> Unit) {
    val dim = if (shown) Board.letter else Board.steelMid
    val cell = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).drawBehind {
        drawLine(Board.rule, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
    }.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).flap().padding(2.dp).background(if (shown) swatch else Board.steelMid))
            Text(name.uppercase(), Modifier.padding(start = 8.dp), style = cell, color = dim, maxLines = 1)
        }
        FlapCell(time, Modifier.weight(1.1f), style = cell, color = dim)
        FlapCell(percent, Modifier.weight(0.7f), style = cell, color = dim)
        FlapCell(grams, Modifier.weight(0.7f), style = cell, color = dim)
        Box(Modifier.weight(0.8f), contentAlignment = Alignment.Center) { FlapToggle(shown, onToggle, role = Role.Checkbox, label = "Show $name") }
    }
}

/** Layer range (right edge) and moves within the top layer (bottom), with flap readouts. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PreviewSliders(stats: SliceStats?, info: PreviewInfo?, scene: SceneView?, bottomInset: Dp) {
    if (stats == null || info == null) {
        if (stats != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlapCell("LOADING PREVIEW", color = Board.tealText) }
        return
    }
    val layers = info.zs.size
    var range by remember(info) { mutableStateOf(0f..(layers - 1).coerceAtLeast(0).toFloat()) }
    var moves by remember(info) { mutableStateOf(info.fullMoves.second.toFloat()) }
    Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
        if (layers > 1) Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.78f).padding(end = 6.dp, top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            LayerFlap(range.endInclusive.roundToInt(), info.zs)
            RangeSlider(
                value = range, onValueChange = { r ->
                    range = r
                    scene?.onGl { OrcaCore.previewSetLayers(r.start.roundToInt(), r.endInclusive.roundToInt()) }
                },
                valueRange = 0f..(layers - 1).toFloat(), modifier = Modifier.weight(1f).vertical(),
                startThumb = { FlapThumb() }, endThumb = { FlapThumb() }, track = { RuleTrack() },
            )
            LayerFlap(range.start.roundToInt(), info.zs)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Board.flap.copy(alpha = 0.8f)).padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("MOVES", style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
            Slider(
                value = moves, onValueChange = { v ->
                    moves = v
                    scene?.onGl { OrcaCore.previewSetMoves(info.fullMoves.first, v.roundToInt()) }
                },
                valueRange = info.fullMoves.first.toFloat()..info.fullMoves.second.toFloat().coerceAtLeast(info.fullMoves.first + 1f),
                thumb = { FlapThumb() }, track = { RuleTrack() }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
    }
}

/** Slider thumb: a small flap key with a teal edge; the track stays a thin steel rule. */
@Composable
private fun FlapThumb() = Box(Modifier.size(width = 22.dp, height = 34.dp).flap(Board.teal, 2.dp))

@Composable
private fun RuleTrack() = Box(Modifier.fillMaxWidth().height(3.dp).background(Board.steelMid))

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
