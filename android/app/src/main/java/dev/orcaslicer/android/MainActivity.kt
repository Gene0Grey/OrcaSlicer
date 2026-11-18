package dev.orcaslicer.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val OrcaGreen = Color(0xFF009688)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val slicer = Slicer(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = OrcaGreen, secondary = OrcaGreen,
                    secondaryContainer = Color(0xFF00504A), onSecondaryContainer = Color.White,
                ),
            ) {
                Surface(Modifier.fillMaxSize()) { SliceScreen(slicer) }
            }
        }
    }
}

private sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val lastLine: String) : SliceState
    data class Done(val result: SliceResult) : SliceState
    data class Failed(val message: String) : SliceState
}

private val PRINTERS = listOf(
    PrinterSettings("Generic 220x220", 220, 220, 250),
    PrinterSettings("Generic 235x235", 235, 235, 250),
    PrinterSettings("Generic 256x256", 256, 256, 256),
    PrinterSettings("Klipper 300x300", 300, 300, 300, flavor = "klipper"),
)
private val FILAMENTS = listOf(
    FilamentSettings("PLA", 210, 60), FilamentSettings("PETG", 240, 75),
    FilamentSettings("ABS", 250, 100), FilamentSettings("TPU", 225, 40),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SliceScreen(slicer: Slicer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf<File?>(null) }
    var printer by remember { mutableStateOf(PRINTERS[0]) }
    var print by remember { mutableStateOf(PrintSettings()) }
    var filament by remember { mutableStateOf(FILAMENTS[0]) }
    var state by remember { mutableStateOf<SliceState>(SliceState.Idle) }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { model = withContext(Dispatchers.IO) { copyToCache(context, uri) }; state = SliceState.Idle }
    }
    val saveGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        val done = state as? SliceState.Done ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out -> done.result.gcode.inputStream().use { it.copyTo(out) } }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OrcaSlicer", style = MaterialTheme.typography.headlineMedium)

            Section("Model") {
                Text(model?.name ?: "No model loaded", style = MaterialTheme.typography.bodyLarge)
                FilledTonalButton(onClick = { openModel.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (model == null) "Open STL / 3MF / OBJ / STEP" else "Choose another model")
                }
            }

            Section("Printer") {
                Chips(PRINTERS, printer, { it.name }) { printer = it.copy(nozzle = printer.nozzle) }
                Text("Nozzle", style = MaterialTheme.typography.labelLarge)
                Chips(listOf(0.2f, 0.4f, 0.6f, 0.8f), printer.nozzle, { "$it mm" }) { printer = printer.copy(nozzle = it) }
            }

            Section("Quality") {
                Text("Layer height", style = MaterialTheme.typography.labelLarge)
                Chips(listOf(0.08f, 0.12f, 0.16f, 0.20f, 0.24f, 0.28f), print.layerHeight, { "%.2f".format(it) }) {
                    print = print.copy(layerHeight = it)
                }
                Stepper("Walls", print.walls, 1..10) { print = print.copy(walls = it) }
                Text("Infill ${print.infill}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = print.infill.toFloat(), onValueChange = { print = print.copy(infill = (it / 5).roundToInt() * 5) },
                    valueRange = 0f..100f, steps = 19,
                )
                Toggle("Supports (tree)", print.supports) { print = print.copy(supports = it) }
                Toggle("Brim", print.brim) { print = print.copy(brim = it) }
            }

            Section("Filament") {
                Chips(FILAMENTS, filament, { it.type }) { filament = it }
                Stepper("Nozzle °C", filament.nozzleTemp, 150..320, step = 5) { filament = filament.copy(nozzleTemp = it) }
                Stepper("Bed °C", filament.bedTemp, 0..120, step = 5) { filament = filament.copy(bedTemp = it) }
            }

            when (val s = state) {
                is SliceState.Done -> Section("Result") {
                    Stat("Print time", s.result.time ?: "–")
                    Stat("Filament", s.result.filamentGrams?.let { "$it g" } ?: "–")
                    Stat("Layers", s.result.layers ?: "–")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { saveGcode.launch(s.result.gcode.name) }, Modifier.weight(1f).height(52.dp)) { Text("Save G-code") }
                        OutlinedButton(onClick = { share(context, s.result.gcode) }, Modifier.weight(1f).height(52.dp)) { Text("Share") }
                    }
                }
                is SliceState.Failed -> Section("Error") {
                    Text(s.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                else -> {}
            }
        }

        Column(Modifier.padding(16.dp)) {
            val running = state as? SliceState.Running
            if (running != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(running.lastLine, maxLines = 1, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
            }
            Button(
                onClick = {
                    val m = model ?: return@Button
                    state = SliceState.Running("Starting…")
                    scope.launch {
                        state = try {
                            SliceState.Done(slicer.slice(m, printer, print, filament) { line ->
                                if (line.isNotBlank()) scope.launch { if (state is SliceState.Running) state = SliceState.Running(line.trim()) }
                            })
                        } catch (e: Exception) {
                            SliceState.Failed(e.message ?: e.toString())
                        }
                    }
                },
                enabled = model != null && running == null,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) { Text("Slice", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, modifier = Modifier.height(44.dp))
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, Modifier.size(52.dp)) { Text("−") }
        Text("$value", Modifier.width(56.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, Modifier.size(52.dp)) { Text("+") }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(2.dp))
}

private fun copyToCache(context: android.content.Context, uri: Uri): File {
    var name = "model.stl"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0) ?: name
    }
    // Keep only a safe file name; the extension tells the slicer the format.
    name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = File(context.cacheDir, "models").apply { deleteRecursively(); mkdirs() }
    val dest = File(dir, name)
    context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
    return dest
}

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("text/x.gcode").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share G-code"))
}
