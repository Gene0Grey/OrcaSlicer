package dev.orcaslicer.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
class PlateHost(val camera: OrbitCamera) {
    var scene by mutableStateOf<SceneView?>(null)
    var preview by mutableStateOf<PreviewInfo?>(null)
    var openModel: () -> Unit = {}
    var exportGcode: () -> Unit = {}
    var shareGcode: () -> Unit = {}
    var slice: () -> Unit = {}
    var addPrinter: () -> Unit = {}
    var editPreset: (Int) -> Unit = {}
    var exactTransform: () -> Unit = {}

    fun fit(state: AppState) {
        val b = state.presets.bed
        if (b.isNotEmpty()) camera.fit(b.minOf { it.first }.toFloat(), b.minOf { it.second }.toFloat(), b.maxOf { it.first }.toFloat(), b.maxOf { it.second }.toFloat())
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
    val host = remember { PlateHost(OrbitCamera()) }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.addModel(uri); state.screen = Screen.Prepare }
    }
    val exportGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        val path = state.stats?.gcode ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
    }
    host.openModel = { openModel.launch(arrayOf("*/*")) }
    host.exportGcode = { exportGcode.launch("${state.projectName}.gcode") }
    host.shareGcode = { state.stats?.let { share(context, File(it.gcode)) } }
    host.addPrinter = { picker = true }
    host.editPreset = { editor = it }
    host.exactTransform = { transform = true }
    host.slice = {
        scope.launch {
            if (state.slice()) { host.preview = null; state.screen = Screen.Preview; host.scene?.mode = 1; host.scene?.requestPreviewInfo() }
        }
    }

    LaunchedEffect(Unit) { runCatching { state.start() }.onFailure { state.error = it.message; state.busy = null } }
    LaunchedEffect(state.presets.bed) { host.fit(state) }
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
                        onRecent = { path -> scope.launch { state.addRecent(path); state.screen = Screen.Prepare } })
                    Screen.Device -> DeviceScreen(state)
                    Screen.Project -> ProjectScreen(state)
                    Screen.Prepare, Screen.Preview -> PlateScreen(state, host, wide)
                }
                state.busy?.let { BusyBoard(it, Modifier.align(Alignment.Center)) }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                BoardNav(state, vertical = true)
                content(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                content(Modifier.weight(1f))
                BoardNav(state, vertical = false)
            }
        }
    }

    if (state.presets.printer.isEmpty() && state.busy == null && state.error == null) picker = true
    if (picker) PrinterPicker(state) { picker = false }
    editor?.let { PresetEditorDialog(state, it) { editor = null } }
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

fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("text/x.gcode").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share G-code"))
}

/**
 * Navigation as a row (phone) or column (tablet) of flap keys split by rules. The live destination is
 * lit: teal letters and a teal edge, no pill.
 */
@Composable
private fun BoardNav(state: AppState, vertical: Boolean) {
    val keys: @Composable (Modifier) -> Unit = { m ->
        DESTINATIONS.forEach { d ->
            val active = state.screen == d.screen
            Column(
                m.selectable(active, role = Role.Tab, onClick = { state.screen = d.screen })
                    .flap(if (active) Board.teal else null, 0.dp).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(d.icon, null, tint = if (active) Board.tealText else Board.steelLight)
                Text(d.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (active) Board.tealText else Board.steelLight)
            }
        }
    }
    if (vertical) Column(Modifier.width(88.dp).fillMaxHeight().steel().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys(Modifier.fillMaxWidth().heightIn(min = 72.dp))
    } else Row(Modifier.fillMaxWidth().steel().windowInsetsPadding(WindowInsets.navigationBars).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keys(Modifier.weight(1f).heightIn(min = 64.dp))
    }
}
