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
    external fun values(kind: Int): String
    external fun setValue(kind: Int, key: String, value: String): String?
    external fun discardChanges(kind: Int)
    external fun savePreset(kind: Int, name: String): String

    external fun addModel(path: String): String
    external fun objects(): String
    external fun select(index: Int)

    /** op: 0 delete, 1 duplicate, 2 arrange, 3 orient, 4 clear, 5 drop to bed */
    external fun objectOp(op: Int, index: Int): String

    /** values: offset xyz, rotation xyz (radians), scale xyz */
    external fun setTransform(index: Int, values: DoubleArray): String
    external fun slice(listener: ProgressListener): String
    external fun gcodePath(): String

    /** mode: 0 off, 1 move, 2 rotate, 3 scale */
    external fun gizmoSetMode(mode: Int)
    external fun gizmoBegin(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): Int
    external fun gizmoDrag(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray)
    external fun gizmoEnd()

    external fun objectSettingKeys(): String
    external fun objectValues(index: Int): String
    /** value null removes the override */
    external fun setObjectValue(index: Int, key: String, value: String?): String?

    // GL thread
    external fun glInit()
    external fun glResize(width: Int, height: Int)
    external fun glRender(mode: Int, view: FloatArray, proj: FloatArray, background: FloatArray)
    external fun pick(x: Float, y: Float, width: Int, height: Int, view: FloatArray, proj: FloatArray): Int
    external fun previewInfo(): String
    external fun previewSetLayers(low: Int, high: Int)
    external fun previewSetMoves(low: Int, high: Int)
    external fun previewSetViewType(type: Int)
    external fun previewToggleRole(role: Int)
    external fun previewToggleTravel()
}
