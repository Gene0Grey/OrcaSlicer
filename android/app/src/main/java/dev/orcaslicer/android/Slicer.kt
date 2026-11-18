package dev.orcaslicer.android

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PrinterSettings(
    val name: String = "Generic 220x220",
    val bedX: Int = 220,
    val bedY: Int = 220,
    val height: Int = 250,
    val nozzle: Float = 0.4f,
    val flavor: String = "marlin2",
)

data class PrintSettings(
    val layerHeight: Float = 0.20f,
    val walls: Int = 2,
    val infill: Int = 15,
    val supports: Boolean = false,
    val brim: Boolean = false,
)

data class FilamentSettings(
    val type: String = "PLA",
    val nozzleTemp: Int = 210,
    val bedTemp: Int = 60,
)

data class SliceResult(val gcode: File, val time: String?, val filamentGrams: String?, val layers: String?)

class SliceException(message: String) : Exception(message)

/**
 * Runs the headless orca-slicer CLI (packaged as liborcaslicer_cli.so so Android extracts it to the
 * executable native lib dir). ponytail: process boundary instead of JNI; swap for a JNI facade when
 * the app needs live model state (3D view, per-object settings).
 */
class Slicer(private val context: Context) {
    private val root = File(context.filesDir, "orca")
    private val resourcesDir = File(root, "resources")
    private val binary = File(context.applicationInfo.nativeLibraryDir, "liborcaslicer_cli.so")

    /** Copies bundled resources out of the APK once per app install/update. */
    fun prepareResources() {
        val stamp = File(root, "resources.stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return
        resourcesDir.deleteRecursively()
        resourcesDir.mkdirs()
        if (context.assets.list("orca")?.contains("resources") == true)
            copyAssets(context.assets, "orca/resources", resourcesDir)
        stamp.writeText(version)
    }

    suspend fun slice(
        model: File,
        printer: PrinterSettings,
        print: PrintSettings,
        filament: FilamentSettings,
        onLog: (String) -> Unit,
    ): SliceResult = withContext(Dispatchers.IO) {
        prepareResources()
        val work = File(context.cacheDir, "slice").apply { deleteRecursively(); mkdirs() }
        val out = File(work, "out").apply { mkdirs() }
        val machine = File(work, "machine.json").apply { writeText(machineJson(printer).toString(2)) }
        val process = File(work, "process.json").apply { writeText(processJson(print, printer).toString(2)) }
        val fil = File(work, "filament.json").apply { writeText(filamentJson(filament).toString(2)) }

        val cmd = listOf(
            binary.path,
            "--datadir", File(root, "datadir").apply { mkdirs() }.path,
            "--load-settings", "${machine.path};${process.path}",
            "--load-filaments", fil.path,
            "--arrange", "1",
            "--slice", "0",
            "--outputdir", out.path,
            model.path,
        )
        val proc = ProcessBuilder(cmd)
            .directory(work)
            .redirectErrorStream(true)
            .apply {
                environment()["ORCA_RESOURCES_DIR"] = resourcesDir.path
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                environment()["HOME"] = root.path
            }
            .start()
        val tail = ArrayDeque<String>()
        proc.inputStream.bufferedReader().forEachLine { line ->
            onLog(line)
            tail.addLast(line)
            if (tail.size > 20) tail.removeFirst()
        }
        val code = proc.waitFor()
        val gcode = out.listFiles { f -> f.extension == "gcode" }?.firstOrNull()
        if (code != 0 || gcode == null)
            throw SliceException("Slicing failed (exit $code)\n" + tail.joinToString("\n"))
        parseStats(gcode)
    }

    private fun parseStats(gcode: File): SliceResult {
        var time: String? = null
        var grams: String? = null
        var layers: String? = null
        gcode.useLines { lines ->
            for (l in lines) {
                if (!l.startsWith(";")) continue
                when {
                    time == null && l.contains("total estimated time:") -> time = l.substringAfter("total estimated time:").trim()
                    time == null && l.startsWith("; estimated printing time") -> time = l.substringAfter("=").trim()
                    l.startsWith("; total filament used [g]") -> grams = l.substringAfter("=").trim()
                    layers == null && l.startsWith("; total layer number:") -> layers = l.substringAfter(":").trim()
                }
            }
        }
        return SliceResult(gcode, time, grams, layers)
    }

    companion object {
        private fun arr(vararg v: Any) = JSONArray(v.map { it.toString() })

        fun machineJson(p: PrinterSettings) = JSONObject().apply {
            put("type", "machine"); put("from", "User"); put("name", p.name)
            put("printer_technology", "FFF")
            put("gcode_flavor", p.flavor)
            put("printable_area", arr("0x0", "${p.bedX}x0", "${p.bedX}x${p.bedY}", "0x${p.bedY}"))
            put("printable_height", p.height.toString())
            put("nozzle_diameter", arr(p.nozzle))
            put("min_layer_height", arr(0.05))
            put("max_layer_height", arr(p.nozzle * 0.75f))
            put("retraction_length", arr(if (p.flavor == "klipper") 0.8 else 2))
            put("machine_start_gcode", START_GCODE)
            put("machine_end_gcode", END_GCODE)
            put("layer_change_gcode", "")
        }

        fun processJson(s: PrintSettings, p: PrinterSettings) = JSONObject().apply {
            put("type", "process"); put("from", "User"); put("name", "Android process")
            put("layer_height", s.layerHeight.toString())
            put("initial_layer_print_height", maxOf(s.layerHeight, p.nozzle * 0.5f).toString())
            put("wall_loops", s.walls.toString())
            put("sparse_infill_density", "${s.infill}%")
            put("enable_support", if (s.supports) "1" else "0")
            put("support_type", "tree(auto)")
            put("brim_type", if (s.brim) "outer_only" else "no_brim")
            put("brim_width", "5")
            put("line_width", p.nozzle.toString())
        }

        fun filamentJson(f: FilamentSettings) = JSONObject().apply {
            put("type", "filament"); put("from", "User"); put("name", "Android ${f.type}")
            put("filament_type", arr(f.type))
            put("filament_diameter", arr(1.75))
            put("nozzle_temperature", arr(f.nozzleTemp))
            put("nozzle_temperature_initial_layer", arr(f.nozzleTemp + 5))
            // Every plate type gets the same bed temperature; the generic printer has no plate choice.
            for (k in listOf("cool_plate_temp", "eng_plate_temp", "hot_plate_temp", "textured_plate_temp", "supertack_plate_temp")) {
                put(k, arr(f.bedTemp)); put("${k}_initial_layer", arr(f.bedTemp))
            }
        }

        private const val START_GCODE =
            "G28 ; home\n" +
            "M140 S[bed_temperature_initial_layer_single]\n" +
            "M104 S[nozzle_temperature_initial_layer]\n" +
            "M190 S[bed_temperature_initial_layer_single]\n" +
            "M109 S[nozzle_temperature_initial_layer]\n" +
            "G92 E0\nG1 Z2 F3000\nG1 X5 Y20 F5000\nG1 Z0.3 F3000\n" +
            "G1 X5 Y150 E15 F1500 ; purge line\nG92 E0\nG1 Z2 F3000"
        private const val END_GCODE =
            "G91\nG1 E-2 F2700\nG1 Z10 F3000\nG90\nM104 S0\nM140 S0\nM107\nG28 X\nM84"

        private fun copyAssets(am: AssetManager, path: String, dest: File) {
            val children = am.list(path) ?: emptyArray()
            if (children.isEmpty()) {
                dest.parentFile?.mkdirs()
                am.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
                return
            }
            dest.mkdirs()
            for (c in children) copyAssets(am, "$path/$c", File(dest, c))
        }
    }
}
