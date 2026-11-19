package dev.orcaslicer.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

enum class Screen { Home, Prepare, Preview, Device, Project }

data class OptionDef(
    val key: String, val type: Int, val label: String, val fullLabel: String, val tooltip: String,
    val sidetext: String, val mode: Int, val multiline: Boolean, val code: Boolean, val readonly: Boolean,
    val min: Double?, val max: Double?, val values: List<String>, val labels: List<String>,
) {
    val baseType get() = type and 0x3FFF
    val isVector get() = type and 0x4000 != 0
}

data class LayoutGroup(val title: String, val options: List<String>)
data class LayoutPage(val title: String, val groups: List<LayoutGroup>)
data class PresetItem(val name: String, val system: Boolean)
data class FilamentSlot(val name: String, val color: String)

data class PresetsState(
    val vendor: String = "",
    val printer: String = "", val printers: List<PresetItem> = emptyList(), val printerDirty: Boolean = false,
    val process: String = "", val processes: List<PresetItem> = emptyList(), val processDirty: Boolean = false,
    val slots: List<FilamentSlot> = emptyList(), val filaments: List<PresetItem> = emptyList(), val filamentDirty: Boolean = false,
    val bed: List<Pair<Double, Double>> = emptyList(), val bedHeight: Double = 250.0, val bedType: Int = 0,
)

/** type: 0 part, 1 negative part, 2 modifier, 3 support blocker, 4 support enforcer (ModelVolumeType). */
data class PlateVolume(val name: String, val type: Int, val filament: Int, val settings: Int,
                       val offset: List<Double>, val rotation: List<Double>, val scale: List<Double>, val size: List<Double>)
data class HeightRange(val lo: Double, val hi: Double, val settings: Int)

data class PlateObject(
    val name: String, val offset: List<Double>, val rotation: List<Double>, val scale: List<Double>, val size: List<Double>,
    val selected: Boolean, val outside: Boolean, val primary: Boolean = selected, val instances: Int = 1, val printable: Boolean = true,
    val filament: Int = 0, val settings: Int = 0, val inch: Boolean = false, val meter: Boolean = false, val source: String = "",
    val volumes: List<PlateVolume> = emptyList(), val ranges: List<HeightRange> = emptyList(),
)

val VOLUME_TYPES = listOf("Part", "Negative part", "Modifier", "Support blocker", "Support enforcer")

data class RoleStat(val role: Int, val name: String, val time: Double, val percent: Double, val meters: Double, val grams: Double)
data class SliceStats(val roles: List<RoleStat>, val travelTime: Double, val totalTime: Double, val prepareTime: Double, val meters: Double, val grams: Double, val gcode: String)

data class Vendor(val id: String, val name: String, val models: List<String>)

/** Everything the screens show; all native calls go through here on the IO dispatcher. */
class AppState(private val context: Context) {
    private val prefs = context.getSharedPreferences("orca", Context.MODE_PRIVATE)
    private val resources = File(context.filesDir, "orca/resources")

    var screen by mutableStateOf(Screen.Home)
    var busy by mutableStateOf<String?>("Preparing resources…")
    var error by mutableStateOf<String?>(null)
    var defs: Map<String, OptionDef> = emptyMap(); private set
    var layout: Map<Int, List<LayoutPage>> = emptyMap(); private set
    var vendors by mutableStateOf<List<Vendor>>(emptyList())
    var presets by mutableStateOf(PresetsState())
    val values = mutableStateMapOf<Int, Map<String, String>>()
    var objects by mutableStateOf<List<PlateObject>>(emptyList())
    var selected by mutableIntStateOf(-1)
    var stats by mutableStateOf<SliceStats?>(null)
    var sliceProgress by mutableStateOf<Pair<Int, String>?>(null)
    var advanced by mutableStateOf(prefs.getBoolean("advanced", false))
    var projectName by mutableStateOf("Untitled")
    var gizmoMode by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var objectKeys: List<String> = emptyList(); private set
    /** Overrides per (object, part); part -1 = the object, >= 0 a part, <= -2 a height range. */
    val objectValues = mutableStateMapOf<Pair<Int, Int>, Map<String, String>>()
    var recent by mutableStateOf(prefs.getString("recent", "")!!.split('\n').filter { it.isNotBlank() })

    suspend fun start() = withContext(Dispatchers.IO) {
        extractResources()
        OrcaCore.init(resources.path, context.cacheDir.path)
        defs = parseDefs(JSONObject(OrcaCore.optionDefs()))
        layout = parseLayout(JSONObject(context.assets.open("settings_layout.json").bufferedReader().readText()))
        vendors = JSONArray(OrcaCore.vendors()).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { v ->
                Vendor(v.getString("id"), v.getString("name"), v.getJSONArray("models").let { m -> (0 until m.length()).map(m::getString) })
            }.sortedBy { it.name.lowercase() }
        }
        objectKeys = JSONArray(OrcaCore.objectSettingKeys()).let { a -> (0 until a.length()).map(a::getString) }
        val vendor = prefs.getString("vendor", null)
        if (vendor != null) loadVendor(vendor, prefs.getString("printer", "")!!)
        busy = null
    }

    suspend fun loadVendor(vendor: String, printer: String) = withContext(Dispatchers.IO) {
        busy = "Loading $vendor profiles…"
        applyState(OrcaCore.loadVendor(vendor, printer))
        prefs.edit().putString("vendor", vendor).putString("printer", presets.printer).apply()
        refreshObjects()
        busy = null
    }

    suspend fun selectPreset(kind: Int, slot: Int, name: String) = withContext(Dispatchers.IO) {
        applyState(OrcaCore.selectPreset(kind, slot, name))
        if (kind == OrcaCore.KIND_PRINTER) prefs.edit().putString("printer", name).apply()
    }

    suspend fun setFilamentCount(n: Int) = withContext(Dispatchers.IO) { applyState(OrcaCore.setFilamentCount(n)) }
    suspend fun setBedType(t: Int) = withContext(Dispatchers.IO) { applyState(OrcaCore.setBedType(t)) }

    suspend fun setValue(kind: Int, key: String, value: String) = withContext(Dispatchers.IO) {
        OrcaCore.setValue(kind, key, value)?.let { error = it }
        applyState(OrcaCore.state())
    }

    suspend fun discard(kind: Int) = withContext(Dispatchers.IO) { OrcaCore.discardChanges(kind); applyState(OrcaCore.state()) }

    suspend fun savePreset(kind: Int, name: String) = withContext(Dispatchers.IO) { applyState(OrcaCore.savePreset(kind, name)) }

    suspend fun addModel(uri: Uri) = withContext(Dispatchers.IO) {
        busy = "Loading model…"
        val file = copyToCache(uri)
        val r = OrcaCore.addModel(file.path)
        if (r.startsWith("{")) error = JSONObject(r).optString("error") else applyObjects(r)
        if (projectName == "Untitled") projectName = file.nameWithoutExtension
        remember(file.path)
        stats = null
        busy = null
    }

    suspend fun addRecent(path: String) = withContext(Dispatchers.IO) {
        val r = OrcaCore.addModel(path)
        if (r.startsWith("{")) error = JSONObject(r).optString("error") else applyObjects(r)
    }

    suspend fun objectOp(op: Int, index: Int = selected) = withContext(Dispatchers.IO) {
        applyObjects(OrcaCore.objectOp(op, index)); stats = null
    }

    suspend fun setTransform(index: Int, values: DoubleArray) = withContext(Dispatchers.IO) {
        applyObjects(OrcaCore.setTransform(index, values)); stats = null
    }

    suspend fun select(index: Int) = withContext(Dispatchers.IO) { OrcaCore.select(index); refreshObjects() }

    /** Runs an object menu command (Edit.cpp); false when it was refused (the reason is shown). */
    suspend fun edit(cmd: String, vararg args: Pair<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        val a = JSONObject()
        args.forEach { (k, v) -> a.put(k, if (v is List<*>) JSONArray(v) else v) }
        val r = OrcaCore.edit(cmd, a.toString())
        if (r.startsWith("{")) { error = JSONObject(r).optString("error"); return@withContext false }
        applyObjects(r)
        objectValues.clear()
        if (cmd != "toggle_select" && cmd != "select_all" && cmd != "copy_settings" && cmd != "export") stats = null
        true
    }

    /** Slices the plate; returns true when the preview has a result to show. */
    suspend fun slice(): Boolean = withContext(Dispatchers.IO) {
        sliceProgress = 0 to "Slicing…"
        val r = JSONObject(OrcaCore.slice { p, t -> sliceProgress = p to t })
        sliceProgress = null
        if (r.has("error")) { error = r.getString("error"); return@withContext false }
        stats = parseStats(r)
        true
    }

    fun setGizmo(mode: Int) {
        gizmoMode = if (gizmoMode == mode) 0 else mode
        OrcaCore.gizmoSetMode(gizmoMode)
    }

    suspend fun afterGizmo() = withContext(Dispatchers.IO) { refreshObjects(); stats = null }

    val pickedCount get() = objects.count { it.selected }

    suspend fun undo() = withContext(Dispatchers.IO) { applyObjects(OrcaCore.undo()); objectValues.clear(); stats = null }
    suspend fun redo() = withContext(Dispatchers.IO) { applyObjects(OrcaCore.redo()); objectValues.clear(); stats = null }

    fun refreshObjectValues(index: Int, part: Int = -1) {
        objectValues[index to part] = JSONObject(OrcaCore.objectValues(index, part)).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }

    /** value null removes the override. */
    suspend fun setObjectValue(index: Int, part: Int, key: String, value: String?) = withContext(Dispatchers.IO) {
        OrcaCore.setObjectValue(index, part, key, value)?.let { error = it }
        refreshObjectValues(index, part)
        refreshObjects()
        stats = null
    }

    fun refreshValues(kind: Int) {
        values[kind] = JSONObject(OrcaCore.values(kind)).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }

    private fun refreshObjects() = applyObjects(OrcaCore.objects())

    private fun applyObjects(json: String) {
        val a = JSONArray(json)
        fun JSONObject.d(k: String) = getJSONArray(k).let { x -> (0 until x.length()).map(x::getDouble) }
        fun JSONObject.list(k: String) = optJSONArray(k)?.let { x -> (0 until x.length()).map(x::getJSONObject) }.orEmpty()
        objects = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            PlateObject(o.getString("name"), o.d("offset"), o.d("rotation"), o.d("scale"), o.d("size"), o.getBoolean("selected"), o.optBoolean("outside"),
                o.optBoolean("primary", o.getBoolean("selected")), o.optInt("instances", 1), o.optBoolean("printable", true), o.optInt("filament"),
                o.optInt("settings"), o.optBoolean("inch"), o.optBoolean("meter"), o.optString("source"),
                o.list("volumes").map { v -> PlateVolume(v.getString("name"), v.getInt("type"), v.getInt("filament"), v.getInt("settings"),
                    v.d("offset"), v.d("rotation"), v.d("scale"), v.d("size")) },
                o.list("ranges").map { r -> HeightRange(r.getDouble("lo"), r.getDouble("hi"), r.getInt("settings")) })
        }
        selected = objects.indexOfFirst { it.primary }
        OrcaCore.history().let { canUndo = it[0] > 0; canRedo = it[1] > 0 }
    }

    private fun applyState(json: String) {
        val s = JSONObject(json)
        if (s.has("error")) { error = s.getString("error"); return }
        if (!s.has("printer")) return
        fun items(o: JSONObject) = o.getJSONArray("list").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { PresetItem(it.getString("name"), it.getBoolean("system")) } }
        val pr = s.getJSONObject("printer"); val pc = s.getJSONObject("process"); val fl = s.getJSONObject("filament")
        val bed = s.getJSONObject("bed")
        presets = PresetsState(
            vendor = s.getString("vendor"),
            printer = pr.getString("selected"), printers = items(pr), printerDirty = pr.getBoolean("dirty"),
            process = pc.getString("selected"), processes = items(pc), processDirty = pc.getBoolean("dirty"),
            slots = fl.getJSONArray("slots").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { FilamentSlot(it.getString("name"), it.getString("color")) } },
            filaments = items(fl), filamentDirty = fl.getBoolean("dirty"),
            bed = bed.getJSONArray("area").let { a -> (0 until a.length()).map { a.getJSONArray(it) }.map { it.getDouble(0) to it.getDouble(1) } },
            bedHeight = bed.getDouble("height"), bedType = s.optInt("bedType", 0),
        )
        for (k in 0..2) refreshValues(k)
    }

    private fun parseStats(r: JSONObject) = SliceStats(
        roles = r.getJSONArray("roles").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            RoleStat(it.getInt("role"), it.getString("name"), it.getDouble("time"), it.getDouble("percent"), it.optDouble("meters", 0.0), it.optDouble("grams", 0.0))
        } },
        travelTime = r.getDouble("travelTime"), totalTime = r.getDouble("totalTime"), prepareTime = r.getDouble("prepareTime"),
        meters = r.getDouble("meters"), grams = r.getDouble("grams"), gcode = r.getString("gcode"),
    )

    private fun remember(path: String) {
        recent = (listOf(path) + recent.filter { it != path }).take(10)
        prefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }

    val handyModels: List<File> get() = File(resources, "handy_models").listFiles()?.sortedBy { it.name }.orEmpty()

    fun saveAdvanced(v: Boolean) { advanced = v; prefs.edit().putBoolean("advanced", v).apply() }

    suspend fun importFile(uri: Uri): File = withContext(Dispatchers.IO) { copyToCache(uri) }

    private fun copyToCache(uri: Uri): File {
        var name = "model.stl"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val dest = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    /** Unzips the bundled resources (profiles, info) once per app install/update. */
    private fun extractResources() {
        val stamp = File(resources, ".stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return
        resources.deleteRecursively()
        ZipInputStream(context.assets.open("orca-resources.zip").buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val f = File(resources, e.name)
                require(f.canonicalPath.startsWith(resources.canonicalPath)) { "Bad entry ${e.name}" }
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                e = zip.nextEntry
            }
        }
        stamp.writeText(version)
    }

    private fun parseDefs(o: JSONObject): Map<String, OptionDef> = o.keys().asSequence().associateWith { k ->
        val d = o.getJSONObject(k)
        fun list(n: String) = d.optJSONArray(n)?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()
        OptionDef(
            k, d.getInt("type"), d.getString("label"), d.getString("full"), d.getString("tooltip"), d.getString("side"),
            d.getInt("mode"), d.getBoolean("multiline"), d.getBoolean("code"), d.getBoolean("readonly"),
            if (d.has("min")) d.getDouble("min") else null, if (d.has("max")) d.getDouble("max") else null, list("values"), list("labels"),
        )
    }

    private fun parseLayout(o: JSONObject): Map<Int, List<LayoutPage>> {
        fun pages(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            LayoutPage(p.getString("title"), p.getJSONArray("groups").let { g -> (0 until g.length()).map { g.getJSONObject(it) }.map { gr ->
                LayoutGroup(gr.getString("title"), gr.getJSONArray("options").let { x -> (0 until x.length()).map(x::getString) })
            } })
        }
        return mapOf(
            OrcaCore.KIND_PROCESS to pages(o.getJSONArray("process")),
            OrcaCore.KIND_FILAMENT to pages(o.getJSONArray("filament")),
            OrcaCore.KIND_PRINTER to pages(o.getJSONArray("printer")),
        )
    }
}
