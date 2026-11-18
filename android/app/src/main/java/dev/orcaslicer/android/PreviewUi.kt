package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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
    return when { h > 0 -> "${h}h${m}m${sec}s"; m > 0 -> "${m}m${sec}s"; else -> "${sec}s" }
}

@Composable
fun PreviewOverlay(stats: SliceStats?, info: PreviewInfo?, scene: SceneView?) {
    if (stats == null || info == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (stats == null) "Slice the plate to see the preview." else "Loading preview…")
        }
        return
    }
    val layers = info.zs.size
    var range by remember(info) { mutableStateOf(0f..(layers - 1).coerceAtLeast(0).toFloat()) }
    var moves by remember(info) { mutableStateOf(info.fullMoves.second.toFloat()) }
    var viewType by remember { mutableIntStateOf(1) }
    var legendOpen by remember { mutableStateOf(true) }
    val hidden = remember(info) { mutableStateMapOf<Int, Boolean>() }
    var travel by remember(info) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Legend (desktop's top-right table), collapsible.
        Surface(
            Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = 360.dp).heightIn(max = 440.dp),
            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Color scheme", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Dropdown(VIEW_TYPES[viewType], VIEW_TYPES, Modifier.padding(start = 8.dp).weight(1f)) { t ->
                        viewType = t; scene?.onGl { OrcaCore.previewSetViewType(t) }
                    }
                    IconButton({ legendOpen = !legendOpen }) { Icon(if (legendOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "Toggle legend") }
                }
                if (legendOpen) Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                    Row { Cell("Line Type", 2f, true); Cell("Time", 1.2f, true); Cell("%", 0.8f, true); Cell("g", 0.8f, true); Cell("", 0.6f, true) }
                    stats.roles.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(parseHex(info.roleColors[r.role] ?: "#888888")))
                                Text(r.name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Cell(formatTime(r.time), 1.2f); Cell("%.1f%%".format(r.percent), 0.8f); Cell("%.2f".format(r.grams), 0.8f)
                            Checkbox(hidden[r.role] != true, { v -> hidden[r.role] = !v; scene?.onGl { OrcaCore.previewToggleRole(r.role) } }, Modifier.weight(0.6f).size(36.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Travel", Modifier.weight(2f).padding(start = 16.dp), style = MaterialTheme.typography.bodySmall)
                        Cell(formatTime(stats.travelTime), 1.2f); Cell("", 0.8f); Cell("", 0.8f)
                        Checkbox(travel, { v -> travel = v; scene?.onGl { OrcaCore.previewToggleTravel() } }, Modifier.weight(0.6f).size(36.dp))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Total estimation", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Total("Total filament", "%.2f m   %.2f g".format(stats.meters, stats.grams))
                    Total("Prepare time", formatTime(stats.prepareTime.toDouble()))
                    Total("Total time", formatTime(stats.totalTime))
                }
            }
        }

        // Layer range (desktop's vertical slider on the right).
        if (layers > 1) Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.62f).padding(end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LayerLabel(range.endInclusive.roundToInt(), info.zs)
            RangeSlider(
                value = range, onValueChange = { r ->
                    range = r
                    scene?.onGl { OrcaCore.previewSetLayers(r.start.roundToInt(), r.endInclusive.roundToInt()) }
                },
                valueRange = 0f..(layers - 1).toFloat(), modifier = Modifier.weight(1f).vertical(),
            )
            LayerLabel(range.start.roundToInt(), info.zs)
        }

        // Moves within the top layer (desktop's horizontal slider at the bottom).
        Slider(
            value = moves, onValueChange = { v ->
                moves = v
                scene?.onGl { OrcaCore.previewSetMoves(info.fullMoves.first, v.roundToInt()) }
            },
            valueRange = info.fullMoves.first.toFloat()..info.fullMoves.second.toFloat().coerceAtLeast(info.fullMoves.first + 1f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 72.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, header: Boolean = false) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, fontWeight = if (header) FontWeight.Bold else null, maxLines = 1)

@Composable
private fun Total(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    Text(value, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LayerLabel(layer: Int, zs: List<Float>) = Surface(shape = RoundedCornerShape(6.dp), tonalElevation = 3.dp) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${layer + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text("%.2f".format(zs.getOrElse(layer) { 0f }), style = MaterialTheme.typography.labelSmall)
    }
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
