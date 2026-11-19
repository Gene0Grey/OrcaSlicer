package dev.orcaslicer.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

private val SETUP_PEEK = 236.dp
private val RESULTS_PEEK = 150.dp

/** Prepare and Preview: the plate view with its board overlays, and the setup/results sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateScreen(state: AppState, host: PlateHost, wide: Boolean) {
    val prepare = state.screen == Screen.Prepare
    val panel: @Composable () -> Unit = {
        if (prepare) Sidebar(state, host) else ResultsBoard(state, host.preview, host)
    }
    val plate: @Composable (Dp) -> Unit = { bottomInset ->
        Box(Modifier.fillMaxSize()) {
            host.camera.bottomInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { bottomInset.toPx() }
            SceneHost(state, host)
            if (prepare) PrepareOverlay(state, host, bottomInset) else PreviewOverlay(state, host, bottomInset)
            state.sliceProgress?.let { (p, t) -> SliceProgressBoard(p, t, Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) { state.cancelSlice() } }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(420.dp).fillMaxHeight().steel().verticalScroll(rememberScrollState())) { panel() }
            Box(Modifier.weight(1f)) { plate(0.dp) }
        }
    } else {
        val sheet = rememberBottomSheetScaffoldState()
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = if (prepare) SETUP_PEEK else RESULTS_PEEK,
            sheetContainerColor = Board.frame,
            sheetContentColor = Board.letter,
            sheetShape = MaterialTheme.shapes.large,
            sheetDragHandle = { BottomSheetDefaults.DragHandle(color = Board.steelMid) },
            containerColor = Board.flap,
            sheetContent = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { panel() } },
        ) { plate(if (prepare) SETUP_PEEK else RESULTS_PEEK) }
    }
}

@Composable
private fun SceneHost(state: AppState, host: PlateHost) {
    val scope = rememberCoroutineScope()
    AndroidView(factory = { ctx ->
        SceneView(ctx, host.camera, dark = !Board.light).also { v ->
            v.onPick = { idx -> scope.launch { state.select(idx) } }
            v.onTogglePick = { idx -> scope.launch { state.edit("toggle_select", "idx" to idx) } }
            v.onFit = { host.fit(state) }
            v.onPreviewInfo = { host.preview = PreviewInfo.parse(it) }
            v.onGizmoEnd = { scope.launch { state.afterGizmo() } }
            v.onMeasure = { host.measure = org.json.JSONObject(it) }
            v.brush = host.brush
            v.mode = if (state.screen == Screen.Preview) 1 else 0
            v.gizmoMode = state.gizmoMode
            host.scene = v
        }
    }, update = { v -> if (v.dark == Board.light) { v.dark = !Board.light; v.requestRender() } }, modifier = Modifier.fillMaxSize())
}

// ---- Prepare -------------------------------------------------------------------------------

@Composable
private fun PrepareOverlay(state: AppState, host: PlateHost, bottomInset: Dp) {
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(ToolPanel.None) }
    var svg by remember { mutableStateOf<String?>(null) }
    var texture by remember { mutableStateOf<java.io.File?>(null) }
    val sel = state.objects.getOrNull(state.selected)
    val outside = state.objects.any { it.outside }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            PlateBar(state, host)
            val hint = when {
                state.gizmoMode == 4 -> "Tap a face to lay it on the plate"
                state.gizmoMode == 5 -> "Tap a point, edge, circle or face; then a second one"
                state.gizmoMode == 6 -> "One finger paints the picked object; two fingers move the view"
                state.gizmoMode == 7 -> "Tap the picked object where a brim ear goes"
                state.gizmoMode != 0 && sel == null -> "Tap an object to pick it"
                state.gizmoMode == 1 -> "Drag the object to slide it, or an arrow for one axis"
                state.gizmoMode == 2 -> "Drag a ring to turn it, 5 degree steps"
                state.gizmoMode == 3 -> "Drag a corner to scale"
                state.objects.isEmpty() -> "Plate empty. Add a model to begin"
                else -> null
            }
            if (outside) StatusLine("Off the plate: move or scale the red object to slice", Board.red)
            else hint?.let { StatusLine(it, Board.steelLight) }
            if (sel != null) ObjectBar(state, sel, host)
        }
        // Centred in the visible strip between the plate bar and the sheet peek.
        ToolRail(state, Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 56.dp, bottom = bottomInset)) { tools = true }
        if (tools) ToolSheet(state, host, onPick = { p ->
            panel = p
            if (p == ToolPanel.Measure) { state.gizmoMode = 0; state.setGizmo(5); host.measure = null }
        }, onSvg = { svg = it }, onPaint = { m ->
            host.brush.mode = m; host.brush.state = 1
            OrcaCore.paintMode(m)
            state.gizmoMode = 0; state.setGizmo(if (m == 5) 7 else 6)
            host.scene?.requestRender()
        }, onVariableLayers = { host.layersOpen = true }, onTexture = { texture = it }) { tools = false }
        texture?.let { TextureDialog(state, it) { texture = null } }
        if (host.layersOpen) VariableLayerDialog(state) { host.layersOpen = false }
        if (host.brush.mode > 0) PaintPanel(state, host.brush, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)) {
            OrcaCore.paintMode(0); host.brush.mode = 0
            if (state.gizmoMode >= 6) state.setGizmo(state.gizmoMode)
            host.scene?.requestRender()
        }
        svg?.let { SvgToolDialog(state, it) { svg = null } }
        when (panel) {
            ToolPanel.Cut -> CutPanel(state, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)) { panel = ToolPanel.None }
            ToolPanel.Measure -> MeasurePanel(host.measure, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
                onClear = { scope.launch { state.tool("measure_clear") }; host.measure = null; host.scene?.requestRender() }) {
                scope.launch { state.tool("measure_clear") }; host.measure = null; state.setGizmo(5); panel = ToolPanel.None
            }
            ToolPanel.Simplify -> SimplifyDialog(state) { panel = ToolPanel.None }
            ToolPanel.Boolean -> BooleanDialog(state) { panel = ToolPanel.None }
            ToolPanel.Text -> TextToolDialog(state) { panel = ToolPanel.None }
            else -> {}
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomInset + 16.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtendedFloatingActionButton(
                onClick = { if (state.objects.isNotEmpty() && !outside && state.sliceProgress == null) host.slice() },
                containerColor = if (state.objects.isNotEmpty() && !outside) Board.teal else Board.frame,
                contentColor = if (state.objects.isNotEmpty() && !outside) Board.flap else Board.steelMid,
                shape = MaterialTheme.shapes.medium,
                text = { Text("SLICE PLATE", style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(Icons.Default.NearMe, null) },
            )
        }
    }
}

@Composable
private fun PlateBar(state: AppState, host: PlateHost) {
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    var addShape by remember { mutableStateOf(false) }
    if (addShape) AddShapeDialog(state) { addShape = false }
    Row(Modifier.fillMaxWidth().background(Board.flap.copy(alpha = 0.86f)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        var platesOpen by remember { mutableStateOf(false) }
        PlateSwitcher(state, host) { platesOpen = true }
        if (platesOpen) PlatesDialog(state, host) { platesOpen = false }
        val n = state.plates.getOrNull(state.currentPlate)?.objects ?: 0
        FlapCell(when (n) { 0 -> "EMPTY"; 1 -> "1 OBJ"; else -> "$n OBJ" }, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        BarIcon(Icons.AutoMirrored.Filled.Undo, "Undo", state.canUndo) { scope.launch { state.undo() } }
        BarIcon(Icons.AutoMirrored.Filled.Redo, "Redo", state.canRedo) { scope.launch { state.redo() } }
        BarIcon(Icons.Default.Add, "Add model", true, host.openModel)
        Box {
            BarIcon(Icons.Default.MoreVert, "Plate actions", true) { more = true }
            DropdownMenu(more, { more = false }, containerColor = Board.frame) {
                MenuItem("Add shape or handy model", Icons.Default.Category, true) { more = false; addShape = true }
                MenuItem("Import models from a ZIP", Icons.Default.Add, true) {
                    more = false
                    host.pickFile { f -> scope.launch { modelFiles(f).forEach { state.addPath(it) } } }
                }
                MenuItem("Select all", Icons.Default.SelectAll, state.objects.isNotEmpty()) { more = false; scope.launch { state.edit("select_all") } }
                MenuItem("Arrange plate", Icons.Default.AutoAwesomeMosaic, state.objects.isNotEmpty()) { more = false; scope.launch { state.plateCmd("arrange") } }
                MenuItem("Slice all plates", Icons.Default.NearMe, state.objects.isNotEmpty()) { more = false; host.sliceAll() }
                MenuItem(if (state.autoSlice) "Background slicing: on" else "Background slicing: off", Icons.Default.Autorenew, true) {
                    more = false; state.saveAutoSlice(!state.autoSlice)
                }
                MenuItem("Save project (3MF)", Icons.Default.SaveAlt, true) { more = false; host.saveProject(false) }
                MenuItem("Calibration", Icons.Default.Tune, true) { more = false; host.calibrationOpen = true }
                MenuItem("Export all sliced plates", Icons.Default.SaveAlt, state.plates.any { it.sliced }) { more = false; host.saveProject(true) }
                MenuItem("Share sliced plates' G-code", Icons.Default.Share, state.plates.any { it.sliced }) {
                    more = false; host.shareFiles(state.slicedFiles().map { it.second }.filter { it.exists() })
                }
                MenuItem("Export plate as STL", Icons.Default.SaveAlt, state.objects.isNotEmpty()) {
                    more = false
                    host.saveFile("${state.projectName}.stl") { f -> state.edit("export", "path" to f.path, "format" to "stl", "objects" to state.objects.indices.toList()) }
                }
                MenuItem("Share each object as STL", Icons.Default.Share, state.objects.isNotEmpty()) {
                    more = false
                    scope.launch { host.shareFiles(exportEach(state, host)) }
                }
                MenuItem("Fit view", Icons.Default.Tune, true) { more = false; host.fit(state) }
                MenuItem("Clear plate", Icons.Default.Delete, state.objects.isNotEmpty()) { more = false; scope.launch { state.objectOp(4, -1) } }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(tr(label).uppercase(), style = MaterialTheme.typography.labelLarge) }, onClick = onClick,
        enabled = enabled, leadingIcon = { Icon(icon, null) })

@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) =
    IconButton(onClick, enabled = enabled, colors = IconButtonDefaults.iconButtonColors(contentColor = Board.letter, disabledContentColor = Board.steelMid)) {
        Icon(icon, label)
    }

@Composable
private fun StatusLine(text: String, color: Color) =
    Text(text.uppercase(), Modifier.fillMaxWidth().background(Board.flap.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = color)

private data class Tool(val mode: Int, val label: String, val icon: ImageVector)

private val TOOLS = listOf(
    Tool(0, "Pick", Icons.Default.NearMe), Tool(1, "Move", Icons.Default.OpenWith), Tool(2, "Rotate", Icons.AutoMirrored.Filled.RotateRight),
    Tool(3, "Scale", Icons.Default.PhotoSizeSelectSmall), Tool(4, "Lay flat", Icons.Default.VerticalAlignBottom),
)

/** The desktop's left gizmo toolbar as a column of flap keys; the live tool is lit teal. */
@Composable
private fun ToolRail(state: AppState, modifier: Modifier, onTools: () -> Unit) {
    Column(modifier.background(Board.flap.copy(alpha = 0.86f), MaterialTheme.shapes.medium).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TOOLS.forEach { t ->
            val active = state.gizmoMode == t.mode
            val enabled = t.mode == 0 || state.objects.isNotEmpty()
            Surface(
                onClick = { if (t.mode == 0) { if (state.gizmoMode != 0) state.setGizmo(state.gizmoMode) } else state.setGizmo(t.mode) },
                enabled = enabled, color = Color.Transparent, shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(width = 60.dp, height = 58.dp).flap(if (active) Board.teal else null)
                    .semantics { role = Role.Tab; selected = active; contentDescription = t.label },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val c = when { active -> Board.tealText; enabled -> Board.letter; else -> Board.steelMid }
                    Icon(t.icon, null, tint = c, modifier = Modifier.size(22.dp))
                    Text(t.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
        // Cut, simplify, boolean, measure, text, SVG, painting, variable layer height.
        Surface(onClick = onTools, color = Color.Transparent, shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(width = 60.dp, height = 58.dp).flap(if (state.gizmoMode >= 5) Board.teal else null).semantics { contentDescription = "Tools" }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Construction, null, tint = if (state.gizmoMode >= 5) Board.tealText else Board.letter, modifier = Modifier.size(22.dp))
                Text("TOOLS", style = MaterialTheme.typography.labelSmall, color = if (state.gizmoMode >= 5) Board.tealText else Board.letter)
            }
        }
    }
}

/** Actions for the picked object, shown only while one is picked. */
@Composable
private fun ObjectBar(state: AppState, o: PlateObject, host: PlateHost) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    if (menu) ObjectMenuDialog(state, host) { menu = false }
    val many = state.pickedCount
    Row(Modifier.fillMaxWidth().steel().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (many > 1) "$many OBJECTS PICKED" else o.name.ifBlank { "Object" }.uppercase(), style = MaterialTheme.typography.labelLarge,
            color = if (o.outside) Board.red else Board.tealText, maxLines = 1)
        Text(if (many > 1) "LONG PRESS TO ADD OR DROP" else "%.1f × %.1f × %.1f MM".format(o.size[0], o.size[1], o.size[2]) + if (o.instances > 1) "  ×${o.instances}" else "",
            style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
      }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (many <= 1) {
                ObjectAction(Icons.Default.ScreenRotation, "Orient") { scope.launch { state.objectOp(3) } }
                ObjectAction(Icons.Default.Tune, "Exact", onClick = host.exactTransform)
                ObjectAction(Icons.Default.ContentCopy, "Copy") { scope.launch { state.objectOp(1) } }
            }
            ObjectAction(Icons.Default.Delete, "Delete", Board.red) { scope.launch { state.objectOp(0) } }
            ObjectAction(Icons.Default.MoreHoriz, "More") { menu = true }
        }
    }
}

@Composable
private fun ObjectAction(icon: ImageVector, label: String, tint: Color = Board.letter, onClick: () -> Unit) =
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(width = 56.dp, height = 56.dp).flap().semantics { contentDescription = label }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }

@Composable
private fun SliceProgressBoard(percent: Int, text: String, modifier: Modifier, onCancel: () -> Unit) =
    Row(modifier.padding(horizontal = 16.dp).steel().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FlapCell("SLICING", color = Board.tealText)
        FlapCell("${percent.coerceIn(0, 100)}%".padStart(4), style = MaterialTheme.typography.titleMedium)
        FlapCell(text.uppercase().take(26), Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
        IconButton(onCancel, Modifier.flap()) { Icon(Icons.Default.Close, "Cancel slicing", tint = Board.red) }
    }

// ---- Preview -------------------------------------------------------------------------------

@Composable
private fun PreviewOverlay(state: AppState, host: PlateHost, bottomInset: Dp) {
    Box(Modifier.fillMaxSize()) {
        var layerAction by remember { mutableStateOf(-1) }
        PreviewSliders(state.stats, host.preview, host.scene, bottomInset, state.layerGcodes) { layerAction = it }
        host.preview?.zs?.getOrNull(layerAction)?.let { z ->
            LayerActionDialog(state, z.toDouble(), layerAction, onDone = {}) { layerAction = -1 }
        }
        var platesOpen by remember { mutableStateOf(false) }
        Row(Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.viewerFile.isNotBlank()) {
                FlapCell(java.io.File(state.viewerFile).name.uppercase().take(22), color = Board.amber, style = MaterialTheme.typography.labelMedium)
                IconButton({ state.closeViewer(); host.preview = null; host.scene?.requestPreviewInfo() }, Modifier.flap()) { Icon(Icons.Default.Close, "Close G-code file", tint = Board.letter) }
            } else {
                PlateSwitcher(state, host) { platesOpen = true }
            }
        }
        if (platesOpen) PlatesDialog(state, host) { platesOpen = false; host.preview = null; host.scene?.requestPreviewInfo() }
        if (state.stats != null) Row(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(host.sendGcode, Modifier.flap()) { Icon(Icons.AutoMirrored.Filled.Send, "Send to printer", tint = Board.tealText) }
            IconButton({ shareToolpaths(host) }, Modifier.flap()) { Icon(Icons.Default.ViewInAr, "Share toolpaths as OBJ", tint = Board.letter) }
            IconButton(host.shareGcode, Modifier.flap()) { Icon(Icons.Default.Share, "Share G-code", tint = Board.letter) }
            ExtendedFloatingActionButton(onClick = host.exportGcode, containerColor = Board.teal, contentColor = Board.flap,
                shape = MaterialTheme.shapes.medium, text = { Text("EXPORT G-CODE", style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(Icons.Default.VerticalAlignBottom, null) }, modifier = Modifier.height(48.dp))
        }
    }
}

/** Writes each object to its own STL in the cache, for sharing. */
private suspend fun exportEach(state: AppState, host: PlateHost): List<java.io.File> {
    val dir = java.io.File(host.cacheDir, "export").apply { mkdirs() }
    return state.objects.mapIndexed { i, o ->
        val name = (o.name.ifBlank { "object" }.substringBeforeLast('.') + "_${i + 1}").replace(Regex("[^A-Za-z0-9._-]"), "_")
        java.io.File(dir, "$name.stl").also { f -> state.edit("export", "path" to f.path, "format" to "stl", "objects" to listOf(i)) }
    }.filter { it.exists() }
}

/** Writes the visible toolpaths to an OBJ + MTL (on the GL thread, where the preview lives) and shares both. */
private fun shareToolpaths(host: PlateHost) {
    val dir = java.io.File(host.cacheDir, "export").apply { mkdirs() }
    val obj = java.io.File(dir, "toolpaths.obj")
    host.scene?.query({ if (OrcaCore.exportToolpaths(obj.path)) "ok" else "" }) { r ->
        if (r == "ok") host.shareFiles(listOf(obj, java.io.File(dir, "toolpaths.mtl")))
    }
}
