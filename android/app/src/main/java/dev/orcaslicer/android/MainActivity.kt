package dev.orcaslicer.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = AppState(applicationContext)
        setContent {
            BoardTheme { Surface(Modifier.fillMaxSize(), color = Board.ground) { App(state) } }
        }
    }
}

private data class Destination(val screen: Screen, val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination(Screen.Home, "Home", Icons.Default.Home),
    Destination(Screen.Prepare, "Prepare", Icons.Default.ViewInAr),
    Destination(Screen.Preview, "Preview", Icons.Default.Layers),
    Destination(Screen.Device, "Device", Icons.Default.Print),
    Destination(Screen.Project, "Project", Icons.AutoMirrored.Filled.Article),
)

/** What the plate screens (Prepare / Preview) need from the app shell. */
class PlateHost(val camera: OrbitCamera, val cacheDir: File) {
    val brush = PaintBrush()
    var layersOpen by mutableStateOf(false)
    var scene by mutableStateOf<SceneView?>(null)
    var preview by mutableStateOf<PreviewInfo?>(null)
    var openModel: () -> Unit = {}
    var exportGcode: () -> Unit = {}
    var shareGcode: () -> Unit = {}
    var slice: () -> Unit = {}
    var sliceAll: () -> Unit = {}
    /** Saves the project as 3MF; withGcode adds every sliced plate (desktop "Export all plate sliced file"). */
    var saveProject: (withGcode: Boolean) -> Unit = {}
    var addPrinter: () -> Unit = {}
    var editPreset: (Int) -> Unit = {}
    var exactTransform: () -> Unit = {}
    /** Measure tool's last result. */
    var measure by mutableStateOf<org.json.JSONObject?>(null)
    /** Lets the user pick any file; it arrives copied into app storage. */
    var pickFile: (onPicked: (File) -> Unit) -> Unit = {}
    /** Asks where to save [name], has [produce] write it to a temporary file, then copies it there. */
    var saveFile: (name: String, produce: suspend (File) -> Boolean) -> Unit = { _, _ -> }
    var shareFiles: (List<File>) -> Unit = {}
    /** Send the current plate's G-code / a file to the linked printer. */
    var sendGcode: () -> Unit = {}
    var sendFile: (File) -> Unit = {}

    /** Frames the current plate (Prepare) or the sliced plate, which G-code puts at plate 1 (Preview). */
    fun fit(state: AppState) {
        val b = state.presets.bed
        val p = state.plates.getOrNull(state.currentPlate)
        val (ox, oy) = if (state.screen == Screen.Prepare && p != null) p.originX to p.originY else 0.0 to 0.0
        if (b.isNotEmpty()) camera.fit((b.minOf { it.first } + ox).toFloat(), (b.minOf { it.second } + oy).toFloat(),
            (b.maxOf { it.first } + ox).toFloat(), (b.maxOf { it.second } + oy).toFloat())
        scene?.requestRender()
    }
}

@Composable
fun App(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Int?>(null) }
    var transform by remember { mutableStateOf(false) }
    val host = remember { PlateHost(OrbitCamera(), context.cacheDir) }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.addModel(uri); state.screen = Screen.Prepare }
    }
    val exportGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        val path = state.stats?.gcode ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
    }
    var picked by remember { mutableStateOf<((File) -> Unit)?>(null) }
    val pickAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = picked; picked = null
        if (uri != null && cb != null) scope.launch { cb(state.importFile(uri)) }
    }
    var saving by remember { mutableStateOf<Pair<String, suspend (File) -> Boolean>?>(null) }
    val saveAny = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = saving; saving = null
        if (uri != null && job != null) scope.launch(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "export/${job.first}").apply { parentFile?.mkdirs(); delete() }
            if (job.second(tmp) && tmp.exists())
                context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        }
    }
    host.pickFile = { cb -> picked = cb; pickAny.launch(arrayOf("*/*")) }
    host.saveFile = { name, produce -> saving = name to produce; saveAny.launch(name) }
    host.shareFiles = { files -> shareAll(context, files) }
    var sending by remember { mutableStateOf<Pair<File, String>?>(null) }
    host.sendGcode = { state.stats?.let { sending = File(it.gcode) to "${state.projectName}_plate${state.currentPlate + 1}.gcode" } }
    host.sendFile = { f -> sending = f to f.name }
    sending?.let { (f, n) -> SendDialog(state, f, n) { sending = null } }
    host.openModel = { openModel.launch(arrayOf("*/*")) }
    host.exportGcode = { exportGcode.launch("${state.projectName}.gcode") }
    host.shareGcode = { state.stats?.let { share(context, File(it.gcode)) } }
    host.addPrinter = { picker = true }
    host.editPreset = { editor = it }
    host.exactTransform = { transform = true }
    fun showPreview() { host.preview = null; state.screen = Screen.Preview; host.scene?.mode = 1; host.scene?.requestPreviewInfo(); host.fit(state) }
    host.slice = { scope.launch { if (state.slice()) showPreview() } }
    host.sliceAll = { scope.launch { if (state.sliceAll()) showPreview() } }
    host.saveProject = { withGcode ->
        host.saveFile(if (withGcode) "${state.projectName}.gcode.3mf" else "${state.projectName}.3mf") { f -> state.saveProject(f, withGcode) }
    }
    // Background slicing (desktop preference): re-slice the current plate a moment after each edit.
    LaunchedEffect(state.edits, state.autoSlice, state.currentPlate) {
        if (!state.autoSlice || state.objects.isEmpty() || state.objects.any { it.outside }) return@LaunchedEffect
        state.cancelSlice()
        kotlinx.coroutines.delay(800)
        state.slice(quiet = true)
        if (state.screen == Screen.Preview) host.scene?.requestPreviewInfo()
    }

    LaunchedEffect(Unit) { runCatching { state.start() }.onFailure { state.error = it.message; state.busy = null } }
    LaunchedEffect(state.presets.bed, state.screen) { host.fit(state) }
    LaunchedEffect(state.objects, state.presets, state.screen, state.gizmoMode) {
        host.scene?.mode = if (state.screen == Screen.Preview) 1 else 0
        host.scene?.gizmoMode = state.gizmoMode
        host.scene?.requestRender()
        if (state.screen == Screen.Preview && state.stats != null) host.scene?.requestPreviewInfo()
    }
    BackHandler(state.screen != Screen.Home) { state.screen = Screen.Home }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val content: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                when (state.screen) {
                    Screen.Home -> HomeScreen(state,
                        onNew = { scope.launch { state.objectOp(4, -1); state.projectName = "Untitled"; state.screen = Screen.Prepare } },
                        onOpen = host.openModel,
                        onOpenProject = { host.pickFile { f -> scope.launch { if (state.openProject(f)) { state.screen = Screen.Prepare; host.fit(state) } } } },
                        onOpenGcode = { host.pickFile { f -> scope.launch { if (state.openGcode(f)) showPreview() } } },
                        onRecent = { path -> scope.launch { state.addRecent(path); state.screen = Screen.Prepare } })
                    Screen.Device -> DeviceScreen(state, host)
                    Screen.Project -> ProjectScreen(state, host)
                    Screen.Prepare, Screen.Preview -> PlateScreen(state, host, wide)
                }
                state.busy?.let { BusyBoard(it, Modifier.align(Alignment.Center)) }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail(containerColor = Board.flap) {
                    DESTINATIONS.forEach { d ->
                        NavigationRailItem(
                            selected = state.screen == d.screen, onClick = { state.screen = d.screen },
                            icon = { Icon(d.icon, null) }, label = { Text(d.label.uppercase(), style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = Board.flap, indicatorColor = Board.teal,
                                selectedTextColor = Board.tealText, unselectedIconColor = Board.steelLight, unselectedTextColor = Board.steelLight),
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                content(Modifier.weight(1f))
                NavigationBar(containerColor = Board.flap, tonalElevation = 0.dp) {
                    DESTINATIONS.forEach { d ->
                        NavigationBarItem(
                            selected = state.screen == d.screen, onClick = { state.screen = d.screen },
                            icon = { Icon(d.icon, null) }, label = { Text(d.label.uppercase(), style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Board.flap, indicatorColor = Board.teal,
                                selectedTextColor = Board.tealText, unselectedIconColor = Board.steelLight, unselectedTextColor = Board.steelLight),
                        )
                    }
                }
            }
        }
    }

    if (state.presets.printer.isEmpty() && state.busy == null && state.error == null) picker = true
    if (picker) PrinterPicker(state) { picker = false }
    editor?.let { PresetEditorDialog(state, host, it) { editor = null } }
    if (transform && state.selected >= 0) TransformDialog(state) { transform = false }
    state.error?.let { msg ->
        AlertDialog(onDismissRequest = { state.error = null }, confirmButton = { TextButton({ state.error = null }) { Text("OK") } },
            title = { Text("CAN'T DO THAT", style = MaterialTheme.typography.titleMedium) },
            text = { Text(msg, style = MaterialTheme.typography.bodyMedium) }, containerColor = Board.frame)
    }
}

/** Status plate for long operations (loading profiles, models). */
@Composable
fun BusyBoard(text: String, modifier: Modifier = Modifier) =
    Box(modifier.padding(24.dp).steel().padding(16.dp)) {
        FlapCell(text.uppercase().trimEnd('…', '.'), color = Board.tealText)
    }

fun shareAll(context: android.content.Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/octet-stream").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share files"))
}

fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("text/x.gcode").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share G-code"))
}
