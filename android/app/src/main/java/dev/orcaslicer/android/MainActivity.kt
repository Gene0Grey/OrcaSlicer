package dev.orcaslicer.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val OrcaTeal = Color(0xFF009688)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = AppState(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = OrcaTeal, onPrimary = Color.White, secondary = OrcaTeal, secondaryContainer = Color(0xFF00504A), onSecondaryContainer = Color.White)
                         else lightColorScheme(primary = OrcaTeal, secondary = OrcaTeal, secondaryContainer = Color(0xFFB2DFDB))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App(state, dark) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(state: AppState, dark: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = remember { OrbitCamera() }
    var scene by remember { mutableStateOf<SceneView?>(null) }
    var preview by remember { mutableStateOf<PreviewInfo?>(null) }
    var picker by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Int?>(null) }
    var transform by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { state.start() }.onFailure { state.error = it.message; state.busy = null } }
    fun fit() {
        val b = state.presets.bed
        if (b.isNotEmpty()) camera.fit(b.minOf { it.first }.toFloat(), b.minOf { it.second }.toFloat(), b.maxOf { it.first }.toFloat(), b.maxOf { it.second }.toFloat())
        scene?.requestRender()
    }
    LaunchedEffect(state.presets.bed) { fit() }
    LaunchedEffect(state.objects, state.presets, state.screen) {
        scene?.mode = if (state.screen == Screen.Preview) 1 else 0
        scene?.requestRender()
        if (state.screen == Screen.Preview && state.stats != null) scene?.requestPreviewInfo()
    }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.addModel(uri); state.screen = Screen.Prepare }
    }
    val exportGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        val path = state.stats?.gcode ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
    }
    val slice: () -> Unit = {
        scope.launch {
            if (state.slice()) { preview = null; state.screen = Screen.Preview; scene?.mode = 1; scene?.requestPreviewInfo() }
        }
    }
    BackHandler(state.screen != Screen.Home) { state.screen = Screen.Home }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(state, onSlice = slice, onExport = { exportGcode.launch("${state.projectName}.gcode") }, onShare = { state.stats?.let { share(context, File(it.gcode)) } })
        state.sliceProgress?.let { (p, t) ->
            LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth())
            Text(t, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Box(Modifier.weight(1f)) {
            when (state.screen) {
                Screen.Home -> HomeScreen(state, onNew = { scope.launch { state.objectOp(4, -1); state.projectName = "Untitled"; state.screen = Screen.Prepare } },
                    onOpen = { openModel.launch(arrayOf("*/*")) },
                    onRecent = { path -> scope.launch { state.addRecent(path); state.screen = Screen.Prepare } })
                Screen.Device -> DeviceScreen(state)
                Screen.Project -> ProjectScreen(state)
                Screen.Prepare, Screen.Preview -> {
                    val sheet = rememberBottomSheetScaffoldState()
                    BottomSheetScaffold(
                        scaffoldState = sheet,
                        sheetPeekHeight = if (state.screen == Screen.Prepare) 132.dp else 0.dp,
                        sheetContent = {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Sidebar(state, onAddPrinter = { picker = true }, onEdit = { editor = it })
                            }
                        },
                    ) { inner ->
                        Box(Modifier.fillMaxSize().padding(inner)) {
                            AndroidView(factory = { ctx ->
                                SceneView(ctx, camera, dark).also { v ->
                                    v.onPick = { idx -> scope.launch { state.select(idx) } }
                                    v.onFit = ::fit
                                    v.onPreviewInfo = { preview = PreviewInfo.parse(it) }
                                    v.mode = if (state.screen == Screen.Preview) 1 else 0
                                    scene = v
                                }
                            }, modifier = Modifier.fillMaxSize())
                            if (state.screen == Screen.Prepare) PrepareToolbar(state, onAdd = { openModel.launch(arrayOf("*/*")) }, onFit = ::fit, onTransform = { transform = true })
                            else PreviewOverlay(state.stats, preview, scene)
                        }
                    }
                }
            }
            state.busy?.let { Surface(Modifier.align(Alignment.Center), tonalElevation = 6.dp, shape = MaterialTheme.shapes.large) { Text(it, Modifier.padding(24.dp)) } }
        }
    }

    if (state.presets.printer.isEmpty() && state.busy == null && state.error == null) picker = true
    if (picker) PrinterPicker(state) { picker = false }
    editor?.let { PresetEditorDialog(state, it) { editor = null } }
    if (transform && state.selected >= 0) TransformDialog(state) { transform = false }
    state.error?.let { msg ->
        AlertDialog(onDismissRequest = { state.error = null }, confirmButton = { TextButton({ state.error = null }) { Text("OK") } },
            title = { Text("OrcaSlicer") }, text = { Text(msg) })
    }
}

@Composable
private fun TopBar(state: AppState, onSlice: () -> Unit, onExport: () -> Unit, onShare: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TabButton(Icons.Default.Home, "Home", state.screen == Screen.Home) { state.screen = Screen.Home }
                TabButton(Icons.Default.ViewInAr, "Prepare", state.screen == Screen.Prepare) { state.screen = Screen.Prepare }
                TabButton(Icons.Default.Layers, "Preview", state.screen == Screen.Preview) { state.screen = Screen.Preview }
                TabButton(Icons.Default.Print, "Device", state.screen == Screen.Device) { state.screen = Screen.Device }
                TabButton(Icons.Default.Description, "Project", state.screen == Screen.Project) { state.screen = Screen.Project }
            }
            if (state.screen == Screen.Prepare || state.screen == Screen.Preview) Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onSlice, Modifier.weight(1f), enabled = state.objects.isNotEmpty() && state.sliceProgress == null) { Text("Slice plate") }
                OutlinedButton(onExport, Modifier.weight(1f), enabled = state.stats != null) { Text("Export G-code") }
                if (state.stats != null) TextButton(onShare) { Text("Share") }
            }
        }
    }
}

@Composable
private fun TabButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) OrcaTeal else Color.Transparent
    Surface(onClick = onClick, color = color, contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, Modifier.padding(start = 6.dp))
        }
    }
}

/** The desktop's plate toolbar, as large touch buttons. */
@Composable
private fun PrepareToolbar(state: AppState, onAdd: () -> Unit, onFit: () -> Unit, onTransform: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sel = state.selected >= 0
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tool(Icons.Default.AddBox, "Add model", true, onAdd)
        Tool(Icons.Default.AutoAwesomeMosaic, "Arrange", state.objects.isNotEmpty()) { scope.launch { state.objectOp(2) } }
        Tool(Icons.Default.ScreenRotation, "Auto orient", sel) { scope.launch { state.objectOp(3) } }
        Tool(Icons.Default.OpenWith, "Move / rotate / scale", sel, onTransform)
        Tool(Icons.Default.ContentCopy, "Duplicate", sel) { scope.launch { state.objectOp(1) } }
        Tool(Icons.Default.Delete, "Delete", sel) { scope.launch { state.objectOp(0) } }
        Tool(Icons.Default.DeleteSweep, "Clear plate", state.objects.isNotEmpty()) { scope.launch { state.objectOp(4, -1) } }
        Tool(Icons.Default.CenterFocusStrong, "Fit view", true, onFit)
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) =
    FilledTonalIconButton(onClick, enabled = enabled, modifier = Modifier.size(52.dp)) { Icon(icon, label) }

fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("text/x.gcode").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share G-code"))
}
