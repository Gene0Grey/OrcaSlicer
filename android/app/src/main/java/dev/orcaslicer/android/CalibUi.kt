package dev.orcaslicer.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** One calibration: its native test id, the fields it asks for (label, key, default), and fixed args. */
private data class CalibTest(val title: String, val test: String, val detail: String, val fields: List<Triple<String, String, String>> = emptyList(),
                             val fixed: Map<String, Any> = emptyMap())

private val range = { s: String, e: String, st: String -> listOf(Triple("Start", "start", s), Triple("End", "end", e), Triple("Step", "step", st)) }

// The desktop's Calibration menu, with its dialogs' default values.
private val TESTS = listOf(
    CalibTest("Temperature", "temp", "Tower of blocks, one temperature each", range("230", "190", "5")),
    CalibTest("Flow rate · pass 1", "flow", "Coarse: nine blocks from -20 % to +20 %", fixed = mapOf("pass" to 1)),
    CalibTest("Flow rate · pass 2", "flow", "Fine: after setting pass 1's best", fixed = mapOf("pass" to 2)),
    CalibTest("Flow rate · YOLO", "flow_yolo", "One print, linear steps", fixed = mapOf("pass" to 1)),
    CalibTest("Flow rate · YOLO perfectionist", "flow_yolo", "Finer linear steps", fixed = mapOf("pass" to 2)),
    CalibTest("Pressure advance · line", "pa_line", "Lines at rising PA", range("0", "0.1", "0.002")),
    CalibTest("Pressure advance · pattern", "pa_pattern", "Corners at rising PA; speeds and accelerations optional",
        range("0", "0.1", "0.002") + listOf(Triple("Speeds (mm/s, comma separated)", "speeds", ""), Triple("Accelerations (comma separated)", "accelerations", ""))),
    CalibTest("Pressure advance · tower", "pa_tower", "Tower, PA rising with height", range("0", "0.1", "0.002")),
    CalibTest("Retraction", "retraction", "Tower, retraction length rising with height", range("0", "2", "0.1")),
    CalibTest("Max volumetric speed", "max_vol", "Spiral at rising mm³/s", range("5", "20", "0.5")),
    CalibTest("VFA", "vfa", "Vertical fine artifacts by speed", range("40", "200", "10")),
    CalibTest("Input shaping · frequency", "shaping_freq", "Ringing tower across frequencies",
        listOf(Triple("Start X (Hz)", "freq_start_x", "15"), Triple("End X (Hz)", "freq_end_x", "110"), Triple("Start Y (Hz)", "freq_start_y", "15"),
            Triple("End Y (Hz)", "freq_end_y", "110"))),
    CalibTest("Input shaping · damping", "shaping_damp", "Ringing tower across damping ratios",
        listOf(Triple("Frequency X (Hz)", "freq_start_x", "40"), Triple("Frequency Y (Hz)", "freq_start_y", "40")) + range("0", "0.5", "0.05")),
    CalibTest("Cornering", "cornering", "Jerk or junction deviation rising with height", range("1", "15", "1")),
)

/** Calibration menu: pick a test, set its range, and it replaces the plate (as on the desktop). */
@Composable
fun CalibrationDialog(state: AppState, host: PlateHost, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var test by remember { mutableStateOf<CalibTest?>(null) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogBar("Calibration", Icons.Default.Close, onClose)
                Text("EACH TEST STARTS A NEW PROJECT AND CHANGES THE PRESETS FOR THE TEST. SAVE YOUR WORK FIRST.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = Board.amber)
                TESTS.forEach { t ->
                    Column(Modifier.fillMaxWidth().clickable { test = t }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(t.title.uppercase(), style = MaterialTheme.typography.titleMedium)
                        Text(t.detail.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                    }
                }
                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/OrcaSlicer/OrcaSlicer/wiki/Calibration"))) },
                    Modifier.padding(8.dp)) { Text("CALIBRATION GUIDE", color = Board.tealText) }
            }
        }
    }
    test?.let { t -> CalibParamsDialog(state, host, t, { test = null }) { test = null; onClose() } }
}

@Composable
private fun CalibParamsDialog(state: AppState, host: PlateHost, t: CalibTest, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val values = remember(t) { t.fields.map { mutableStateOf(it.third) } }
    fun run() = scope.launch {
        val args = t.fixed.toList().toMutableList<Pair<String, Any?>>()
        t.fields.forEachIndexed { i, (_, key, _) ->
            val v = values[i].value.trim()
            if (key == "speeds" || key == "accelerations")
                args += key to v.split(',').mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
            else v.replace(',', '.').toDoubleOrNull()?.let { args += key to it }
        }
        // Damping uses one frequency per axis: the end equals the start.
        if (t.test == "shaping_damp") {
            values.getOrNull(0)?.value?.toDoubleOrNull()?.let { args += "freq_end_x" to it }
            values.getOrNull(1)?.value?.toDoubleOrNull()?.let { args += "freq_end_y" to it }
        }
        if (state.calibrate(t.test, *args.toTypedArray())) { state.screen = Screen.Prepare; host.fit(state); onDone() }
    }
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text(t.title.uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.detail.uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                t.fields.forEachIndexed { i, (label, key, _) ->
                    OutlinedTextField(values[i].value, { values[i].value = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors,
                        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = if (key == "speeds" || key == "accelerations") KeyboardType.Text else KeyboardType.Decimal))
                }
            }
        },
        confirmButton = { TextButton({ run() }) { Text("START TEST", color = Board.tealText) } },
        dismissButton = { Row { TextButton(onClose) { Text("CANCEL", color = Board.steelLight) } } })
}
