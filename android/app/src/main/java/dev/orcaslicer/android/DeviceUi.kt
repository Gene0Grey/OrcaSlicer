package dev.orcaslicer.android

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Device: the linked printer's live status, job controls and camera, sending files, and its own web UI. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceScreen(state: AppState, host: PlateHost) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember(state.presets.printer) { mutableStateOf(HostConfig.load(ctx, state.presets.printer)) }
    var edit by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<HostStatus?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    val c = config

    // Poll the printer while this screen shows.
    LaunchedEffect(c, tab) {
        if (c == null || tab != 0) return@LaunchedEffect
        while (true) {
            runCatching { withContext(Dispatchers.IO) { PrintHost.status(c) } }
                .onSuccess { status = it; problem = null }.onFailure { problem = it.message }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoardRow("Printer") { FlapCell(state.presets.printer.ifBlank { "NONE CHOSEN" }.uppercase(), Modifier.weight(1f)) }
        BoardRow("Link") {
            FlapCell(c?.let { "${it.type.label} · ${it.url}" }?.uppercase() ?: "NOT LINKED", Modifier.weight(1f), color = if (c == null) Board.amber else Board.tealText)
            IconButton({ edit = true }) { Icon(Icons.Default.Edit, "Link printer", tint = Board.steelLight) }
        }
        if (c == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LINK YOUR PRINTER", style = MaterialTheme.typography.titleLarge)
                Text("OctoPrint, Klipper (Moonraker: Mainsail, Fluidd), PrusaLink or Duet on your network: send G-code, watch the print, pause or stop it, see the camera.",
                    style = MaterialTheme.typography.bodyMedium, color = Board.steelLight)
                TextButton({ edit = true }) { Text("LINK OR FIND A PRINTER", color = Board.tealText) }
            }
        } else {
            FlapTabs(listOf("Status", "Web UI"), tab) { tab = it }
            if (tab == 0) Column(Modifier.verticalScroll(rememberScrollState())) {
                problem?.let { Text("CAN'T REACH THE PRINTER: ${it.uppercase()}", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium, color = Board.red) }
                status?.let { s ->
                    BoardRow("State") { FlapCell(s.state.ifBlank { "IDLE" }.uppercase(), Modifier.weight(1f), color = Board.tealText) }
                    if (s.file.isNotBlank()) BoardRow("File") { FlapCell(s.file.uppercase(), Modifier.weight(1f)) }
                    BoardRow("Progress") {
                        Column(Modifier.weight(1f)) {
                            LinearProgressIndicator({ (s.progress / 100).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = Board.teal, trackColor = Board.frameHi)
                            Text("%.1f %%".format(s.progress) + if (s.printTime > 0) "  ·  ${formatTime(s.printTime)}" else "", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    BoardRow("Nozzle") { FlapCell("%.1f / %.0f °C".format(s.nozzle, s.nozzleTarget), Modifier.weight(1f)) }
                    BoardRow("Bed") { FlapCell("%.1f / %.0f °C".format(s.bed, s.bedTarget), Modifier.weight(1f)) }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    fun control(cmd: String) = scope.launch {
                        runCatching { withContext(Dispatchers.IO) { PrintHost.control(c, cmd) } }.onFailure { state.error = it.message }
                    }
                    TextButton({ control("pause") }) { Text("PAUSE", color = Board.amber) }
                    TextButton({ control("resume") }) { Text("RESUME", color = Board.tealText) }
                    TextButton({ confirmCancel = true }) { Text("STOP", color = Board.red) }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton({ host.sendGcode() }, enabled = state.plates.getOrNull(state.currentPlate)?.sliced == true) { Text("SEND PLATE", color = Board.tealText) }
                    TextButton({ host.pickFile { f -> host.sendFile(f) } }) { Text("SEND A FILE…", color = Board.tealText) }
                }
                val cam = c.camera.ifBlank { PrintHost.defaultCamera(c) }
                if (cam.isNotBlank()) {
                    BoardHeading("Camera")
                    AndroidView(factory = { ctx2 -> WebView(ctx2).apply { settings.javaScriptEnabled = false; webViewClient = WebViewClient() } },
                        update = { w ->
                            val html = "<html><body style='margin:0;background:#000'><img src='$cam' style='width:100%'></body></html>"
                            if (w.tag != cam) { w.tag = cam; w.loadDataWithBaseURL(c.base, html, "text/html", "utf-8", null) }
                        }, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                }
            } else AndroidView(factory = { ctx2 ->
                WebView(ctx2).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient(); loadUrl(c.base) }
            }, update = { if (it.url?.startsWith(c.base) != true) it.loadUrl(c.base) }, modifier = Modifier.fillMaxSize())
        }
    }
    if (edit) LinkPrinterDialog(c, { edit = false }) { nc ->
        HostConfig.save(ctx, state.presets.printer, nc); config = nc; edit = false
    }
    if (confirmCancel && c != null) AlertDialog(onDismissRequest = { confirmCancel = false }, containerColor = Board.frame,
        title = { Text("STOP THE PRINT?", style = MaterialTheme.typography.titleMedium) },
        text = { Text("The printer cancels the job. It can't be resumed.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton({ confirmCancel = false; scope.launch { runCatching { withContext(Dispatchers.IO) { PrintHost.control(c, "cancel") } }
            .onFailure { state.error = it.message } } }) { Text("STOP", color = Board.red) } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text("KEEP PRINTING", color = Board.steelLight) } })
}

/** Link a printer by hand or pick one found on the network (desktop physical printer dialog). */
@Composable
private fun LinkPrinterDialog(current: HostConfig?, onClose: () -> Unit, onSave: (HostConfig) -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(current?.type ?: HostType.Moonraker) }
    var url by remember { mutableStateOf(current?.url ?: "") }
    var key by remember { mutableStateOf(current?.apiKey ?: "") }
    var cam by remember { mutableStateOf(current?.camera ?: "") }
    val found = remember { mutableStateListOf<Triple<String, String, HostType>>() }
    DisposableEffect(Unit) {
        val d = PrinterDiscovery(ctx) { n, u, t -> android.os.Handler(android.os.Looper.getMainLooper()).post { if (found.none { it.second == u }) found += Triple(n, u, t) } }
        d.start()
        onDispose { d.stop() }
    }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text("LINK PRINTER", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (found.isEmpty()) "LOOKING ON YOUR NETWORK…" else "FOUND ON YOUR NETWORK", style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                found.forEach { (n, u, t) ->
                    Text("${n.uppercase()} · ${t.label.uppercase()}\n$u", Modifier.fillMaxWidth().clickable { url = u; type = t }.heightIn(min = 48.dp).padding(vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = Board.tealText)
                }
                Dropdown(type.label.uppercase(), HostType.entries.map { it.label.uppercase() }, Modifier.fillMaxWidth()) { type = HostType.entries[it] }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors, label = { Text("Address (host or IP[:port])") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (type == HostType.OctoPrint || type == HostType.PrusaLink)
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors, label = { Text("API key") })
                OutlinedTextField(cam, { cam = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors, label = { Text("Camera stream URL (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        },
        confirmButton = { TextButton({ if (url.isNotBlank()) onSave(HostConfig(type, url.trim(), key.trim(), cam.trim())) }) { Text("SAVE", color = Board.tealText) } },
        dismissButton = { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } })
}

/** Upload with progress, then optionally start printing (desktop "Upload" / "Upload and print"). */
@Composable
fun SendDialog(state: AppState, file: File, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember(state.presets.printer) { HostConfig.load(ctx, state.presets.printer) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    fun send(print: Boolean) = scope.launch {
        val c = config ?: return@launch
        progress = 0f
        done = runCatching { withContext(Dispatchers.IO) { PrintHost.upload(c, file, name, print) { p -> progress = p } } }
            .fold({ if (print) "SENT. THE PRINTER IS STARTING." else "SENT." }, { "FAILED: ${it.message}" })
        progress = null
    }
    AlertDialog(onDismissRequest = { if (progress == null) onClose() }, containerColor = Board.frame,
        title = { Text("SEND ${name.uppercase()}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (config == null) Text("LINK A PRINTER ON THE DEVICE TAB FIRST.", style = MaterialTheme.typography.labelMedium, color = Board.amber)
                else Text("TO ${config.type.label.uppercase()} · ${config.url.uppercase()}", style = MaterialTheme.typography.labelMedium, color = Board.steelLight)
                progress?.let { p -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth(), color = Board.teal, trackColor = Board.frameHi) }
                done?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (it.startsWith("FAILED")) Board.red else Board.tealText) }
            }
        },
        confirmButton = {
            Row {
                TextButton({ send(false) }, enabled = config != null && progress == null) { Text("UPLOAD", color = Board.tealText) }
                TextButton({ send(true) }, enabled = config != null && progress == null) { Text("UPLOAD AND PRINT", color = Board.tealText) }
            }
        },
        dismissButton = { TextButton(onClose, enabled = progress == null) { Text("CLOSE", color = Board.steelLight) } })
}
