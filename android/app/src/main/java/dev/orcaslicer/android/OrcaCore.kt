package dev.orcaslicer.android

/** JNI entry points into liborca_core.so (android/native/jni). JSON strings cross the boundary. */
object OrcaCore {
    init {
        System.loadLibrary("orca_core")
    }

    fun interface ProgressListener {
        fun onProgress(percent: Int, text: String)
    }

    const val KIND_PROCESS = 0
    const val KIND_FILAMENT = 1
    const val KIND_PRINTER = 2

    external fun init(resources: String, cache: String): String?
    external fun optionDefs(): String
    external fun vendors(): String
    external fun loadVendor(vendor: String, printer: String): String
    external fun state(): String
    external fun selectPreset(kind: Int, slot: Int, name: String): String
    external fun setFilamentCount(count: Int): String
    external fun setBedType(type: Int): String
    /** index: the extruder whose per-extruder values to show (printer settings), else 0 */
    external fun values(kind: Int, index: Int): String
    external fun setValue(kind: Int, key: String, value: String, index: Int): String?

    // Presets.cpp
    external fun setFilamentColor(slot: Int, hex: String): String
    /** {matrix: n*n, multiplier, count} */
    external fun flushVolumes(): String
    /** Empty matrix = recalculate from the filament colours. */
    external fun setFlushVolumes(matrix: DoubleArray, multiplier: Double)
    external fun editFilamentSlot(slot: Int): String
    external fun renamePreset(kind: Int, name: String): String
    external fun deletePreset(kind: Int): String
    external fun exportPreset(kind: Int, path: String): String
    external fun importPresets(path: String): String
    /** [{key, a, b}] */
    external fun comparePresets(kind: Int, a: String, b: String): String
    external fun discardChanges(kind: Int)
    external fun savePreset(kind: Int, name: String): String

    external fun addModel(path: String): String
    external fun objects(): String
    external fun select(index: Int)

    /** op: 0 delete, 1 duplicate, 2 arrange, 3 orient, 4 clear, 5 drop to bed */
    external fun objectOp(op: Int, index: Int): String

    /** values: offset xyz, rotation xyz (radians), scale xyz */
    external fun setTransform(index: Int, values: DoubleArray): String
    /** Slices one plate (off the session lock). Returns its stats, {"cancelled"} or {"error"}. */
    external fun slice(plate: Int, listener: ProgressListener): String
    external fun cancelSlice()
    /** The current plate's G-code, or the viewer's file. */
    external fun gcodePath(): String
    /** Stats of what Preview shows ({} when nothing is sliced). */
    external fun previewStats(): String
    /** [{plate, gcode}] of every sliced plate. */
    external fun slicedPlates(): String
    /** G-code viewer: reads a G-code file for Preview. */
    external fun openGcode(path: String): String
    external fun closeViewer()

    /** {current, plates: [{name, locked, sliced, objects, origin}], viewer} */
    external fun plates(): String
    /** select / add / delete / rename / duplicate / lock / move_objects / arrange (Plates.cpp) */
    external fun plateCmd(cmd: String, args: String): String

    /** 3MF project in the desktop's format; withGcode adds the sliced plates. Returns "" or an error. */
    external fun saveProject(path: String, withGcode: Boolean): String
    external fun openProject(path: String): String

    /** mode: 0 off, 1 move, 2 rotate, 3 scale */
    external fun gizmoSetMode(mode: Int)
    external fun gizmoBegin(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): Int
    external fun gizmoDrag(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray)
    external fun gizmoEnd()

    // Tools.cpp
    /** cut_preview / cut / simplify / boolean / text / svg / measure_clear; returns objects or {"error"} */
    external fun tool(cmd: String, args: String): String
    /** [{name, path}] of the system fonts */
    external fun fonts(): String
    /** {features: [...], result: {distance, distanceInfinite, xyz, angle}} */
    external fun measurePick(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): String
    /** Rotates the tapped object so the tapped face lies on the bed; returns its index or -1. */
    external fun layOnFace(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): Int

    external fun undo(): String
    external fun redo(): String
    /** {undo steps, redo steps} */
    external fun history(): IntArray

    /** The object menu's commands (Edit.cpp); args is a JSON object. Returns the objects or {"error"}. */
    external fun edit(cmd: String, args: String): String

    external fun objectSettingKeys(): String
    /** part: >= 0 a part (volume), -1 the object, <= -2 height range (-2 - part) */
    external fun objectValues(index: Int, part: Int): String
    /** value null removes the override */
    external fun setObjectValue(index: Int, part: Int, key: String, value: String?): String?

    // GL thread
    external fun glInit()
    external fun glResize(width: Int, height: Int)
    external fun glRender(mode: Int, view: FloatArray, proj: FloatArray, background: FloatArray)
    external fun pick(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): Int
    external fun previewInfo(): String
    external fun previewSetLayers(low: Int, high: Int)
    external fun previewSetMoves(low: Int, high: Int)
    /** {enabled:[a,b], visible:[a,b], x, y, z, speed, line}: the moves slider and the tool at the current move. */
    external fun previewCurrent(): String
    external fun previewSetViewType(type: Int)
    external fun previewToggleRole(role: Int)
    external fun previewToggleTravel()
    /** {kind: "range", values, colors, log} or {kind: "list", colors} for a colour scheme. */
    external fun previewLegend(type: Int): String
    /** Visible toolpaths as OBJ (+ .mtl beside it). */
    external fun exportToolpaths(path: String): Boolean
}
