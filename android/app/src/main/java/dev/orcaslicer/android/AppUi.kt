package dev.orcaslicer.android

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

val THEMES = listOf("system", "dark", "light")

/** Preferences (desktop Preferences dialog): language, theme, background slicing, advanced settings, tips. */
@Composable
fun PreferencesDialog(state: AppState, onWizard: () -> Unit, onClose: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val langs = remember { state.languages() }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogBar("Preferences", Icons.Default.Close, onClose)
                BoardRow("Language") {
                    val names = langs.map { Locale.forLanguageTag(it.replace('_', '-')).let { l -> l.getDisplayName(l) }.ifBlank { it } }
                    Dropdown(names.getOrElse(langs.indexOf(state.language)) { "English" }.uppercase(), names.map { it.uppercase() }, Modifier.weight(1f)) { i ->
                        state.saveLanguage(langs[i]); activity?.recreate()
                    }
                }
                BoardRow("Theme") {
                    Dropdown(THEMES[THEMES.indexOf(state.theme).coerceAtLeast(0)].uppercase(), THEMES.map { it.uppercase() }, Modifier.weight(1f)) { i -> state.saveTheme(THEMES[i]) }
                }
                PrefSwitch("Background slicing", "Re-slice a moment after each change", state.autoSlice, state::saveAutoSlice)
                PrefSwitch("Advanced settings", "Show every setting, not only the simple ones", state.advanced, state::saveAdvanced)
                PrefSwitch("Tip of the day", "A tip when the app starts", state.tips, state::saveTips)
                TextButton({ onClose(); onWizard() }, Modifier.padding(8.dp)) { Text("RUN THE SETUP WIZARD AGAIN", color = Board.tealText) }
            }
        }
    }
}

@Composable
private fun PrefSwitch(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) =
    BoardRow(title) {
        Text(detail.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
        Switch(value, onChange, colors = switchColors)
    }

/** First start (desktop Setup Wizard): language, printer, filament and process, preferences. */
@Composable
fun SetupWizard(state: AppState, onPickPrinter: () -> Unit, onClose: () -> Unit) {
    val activity = LocalContext.current as? Activity
    var page by remember { mutableIntStateOf(0) }
    val langs = remember { state.languages() }
    val p = state.presets
    AlertDialog(onDismissRequest = {}, containerColor = Board.frame,
        title = { Text(listOf("Welcome to OrcaSlicer", "Your printer", "Filament and process", "Ready").map(::tr)[page].uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (page) {
                    0 -> {
                        Text("The full OrcaSlicer engine and printer profiles, on this device. A few choices and you can slice.", style = MaterialTheme.typography.bodyMedium)
                        val names = langs.map { Locale.forLanguageTag(it.replace('_', '-')).let { l -> l.getDisplayName(l) }.ifBlank { it } }
                        Dropdown(names.getOrElse(langs.indexOf(state.language)) { "English" }.uppercase(), names.map { it.uppercase() }, Modifier.fillMaxWidth()) { i ->
                            if (langs[i] != state.language) { state.saveLanguage(langs[i]); activity?.recreate() }
                        }
                    }
                    1 -> {
                        FlapCell(p.printer.ifBlank { "NONE CHOSEN" }.uppercase(), Modifier.fillMaxWidth(), color = if (p.printer.isBlank()) Board.amber else Board.tealText)
                        TextButton(onPickPrinter) { Text(if (p.printer.isBlank()) "CHOOSE PRINTER" else "CHOOSE ANOTHER", color = Board.tealText) }
                    }
                    2 -> {
                        Text(tr("Filament").uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                        PresetSelector(state, OrcaCore.KIND_FILAMENT, p.slots.firstOrNull()?.name ?: "", p.filaments, false, modifier = Modifier.fillMaxWidth())
                        Text(tr("Process").uppercase(), style = MaterialTheme.typography.labelSmall, color = Board.steelLight)
                        PresetSelector(state, OrcaCore.KIND_PROCESS, p.process, p.processes, false, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BACKGROUND SLICING", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Switch(state.autoSlice, state::saveAutoSlice, colors = switchColors)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ADVANCED SETTINGS", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Switch(state.advanced, state::saveAdvanced, colors = switchColors)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ if (page < 3) page++ else { state.saveWizardDone(); onClose() } }, enabled = page != 1 || p.printer.isNotBlank()) {
                Text(if (page < 3) "NEXT" else "START", color = Board.tealText)
            }
        },
        dismissButton = { if (page > 0) TextButton({ page-- }) { Text("BACK", color = Board.steelLight) } })
}

/** Tip of the day from the desktop's hints (resources/data/hints.ini). */
@Composable
fun TipDialog(state: AppState, onClose: () -> Unit) {
    val tips = remember { state.hints() }
    if (tips.isEmpty()) return onClose()
    var i by remember { mutableIntStateOf(tips.indices.random()) }
    val (head, body) = tips[i]
    AlertDialog(onDismissRequest = onClose, containerColor = Board.frame,
        title = { Text(head.uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { Row {
            TextButton({ i = (i + 1) % tips.size }) { Text("NEXT TIP", color = Board.tealText) }
            TextButton(onClose) { Text("CLOSE", color = Board.steelLight) }
        } },
        dismissButton = { TextButton({ state.saveTips(false); onClose() }) { Text("DON'T SHOW", color = Board.steelLight) } })
}

private val SITES = listOf("Printables" to "https://www.printables.com", "MakerWorld" to "https://makerworld.com", "Thingiverse" to "https://www.thingiverse.com")

/**
 * Download models (desktop "Download Models"): the model sites in a browser; a downloaded STL,
 * 3MF, OBJ, STEP or ZIP of them goes straight onto the plate.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModelBrowser(state: AppState, onLoaded: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var site by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<String?>(null) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Board.ground) {
            Column {
                DialogBar("Download models", Icons.Default.Close, onClose)
                FlapTabs(SITES.map { it.first }, site) { site = it }
                progress?.let { Text(it.uppercase(), Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium, color = Board.tealText)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Board.teal, trackColor = Board.frameHi) }
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        setDownloadListener { url, ua, disposition, _, _ ->
                            scope.launch {
                                progress = "Downloading…"
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val name = android.webkit.URLUtil.guessFileName(url, disposition, null).replace(Regex("[^A-Za-z0-9._-]"), "_")
                                        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
                                        val f = File(dir, name)
                                        (URL(url).openConnection() as HttpURLConnection).run {
                                            setRequestProperty("User-Agent", ua)
                                            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                                            inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                                        }
                                        modelFiles(f)
                                    }.getOrElse { state.error = it.message; emptyList() }
                                }
                                progress = null
                                if (ok.isNotEmpty()) { ok.forEach { state.addPath(it) }; onLoaded() }
                            }
                        }
                        loadUrl(SITES[0].second)
                    }
                }, update = { w -> if (w.url?.startsWith(SITES[site].second) != true && w.tag != site) { w.tag = site; w.loadUrl(SITES[site].second) } },
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private val MODEL_EXT = setOf("stl", "3mf", "obj", "step", "stp", "amf", "drc")

/** The model files in a download: the file itself, or a ZIP's models unpacked beside it (desktop "Load models contained within a ZIP archive"). */
fun modelFiles(f: File): List<File> {
    if (f.extension.lowercase() != "zip") return if (f.extension.lowercase() in MODEL_EXT) listOf(f) else emptyList()
    val dir = File(f.parentFile, f.nameWithoutExtension).apply { mkdirs() }
    val out = mutableListOf<File>()
    ZipInputStream(f.inputStream().buffered()).use { zip ->
        var e = zip.nextEntry
        while (e != null) {
            val name = File(e.name).name
            if (!e.isDirectory && File(name).extension.lowercase() in MODEL_EXT) {
                val t = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                require(t.canonicalPath.startsWith(dir.canonicalPath)) { "Bad entry ${e.name}" }
                t.outputStream().use { zip.copyTo(it) }
                out += t
            }
            e = zip.nextEntry
        }
    }
    return out
}
