package dev.orcaslicer.android

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Orbit camera around a target on the plate, Z up like the desktop. */
class OrbitCamera {
    var tx = 110f; var ty = 110f; var tz = 0f
    var distance = 450f
    var azimuth = -90f   // degrees, -90 = looking from the front (-Y)
    var elevation = 35f  // degrees above the plate
    val view = FloatArray(16)
    val proj = FloatArray(16)
    var aspect = 1f
    /** Pixels at the bottom covered by UI (the sheet peek); the view centre is lifted above them. */
    var bottomInsetPx = 0f

    fun fit(minX: Float, minY: Float, maxX: Float, maxY: Float, height: Float = 0f) {
        tx = (minX + maxX) / 2; ty = (minY + maxY) / 2; tz = height / 3
        // Keep the whole plate in view: on a portrait screen the width is the limit (fov 35°: 2*tan = 0.63).
        val size = max(maxX - minX, maxY - minY)
        distance = size * 1.15f / (0.63f * aspect.coerceAtMost(1f)) + 20f
        azimuth = -90f; elevation = 35f
    }

    fun update(width: Int, height: Int) {
        aspect = width.toFloat() / max(1, height)
        val az = Math.toRadians(azimuth.toDouble()); val el = Math.toRadians(elevation.toDouble())
        val ex = tx + distance * (cos(el) * cos(az)).toFloat()
        val ey = ty + distance * (cos(el) * sin(az)).toFloat()
        val ez = tz + distance * sin(el).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / max(1, height), max(1f, distance * 0.02f), distance * 8f + 2000f)
        if (bottomInsetPx > 0f) {
            // Lens shift: move the image up by half the covered strip so the plate centres in the visible part.
            val shift = FloatArray(16); Matrix.setIdentityM(shift, 0); Matrix.translateM(shift, 0, 0f, bottomInsetPx / max(1, height), 0f)
            val out = FloatArray(16); Matrix.multiplyMM(out, 0, shift, 0, proj, 0); out.copyInto(proj)
        }
    }

    fun orbit(dxPx: Float, dyPx: Float) {
        azimuth -= dxPx * 0.3f
        elevation = (elevation + dyPx * 0.3f).coerceIn(-89f, 89f)
    }

    fun pan(dxPx: Float, dyPx: Float, viewHeight: Int) {
        val scale = distance * 0.73f / max(1, viewHeight) // ~2*tan(fov/2) per pixel at target distance
        val az = Math.toRadians(azimuth.toDouble()); val el = Math.toRadians(elevation.toDouble())
        // camera right (horizontal) and up vectors
        val rx = -sin(az).toFloat(); val ry = cos(az).toFloat()
        val ux = (-sin(el) * cos(az)).toFloat(); val uy = (-sin(el) * sin(az)).toFloat(); val uz = cos(el).toFloat()
        tx += (-dxPx * rx + dyPx * ux) * scale
        ty += (-dxPx * ry + dyPx * uy) * scale
        tz += (dyPx * uz) * scale
    }

    fun zoom(factor: Float) { distance = (distance / factor).coerceIn(10f, 5000f) }
}

/**
 * The native 3D view. mode 0 = Prepare (plate + objects), 1 = Preview (toolpaths).
 * One finger orbits, two fingers pan, pinch zooms, tap selects, long press adds to the selection,
 * double tap fits the plate.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SceneView(context: Context, val camera: OrbitCamera, @Volatile var dark: Boolean) : GLSurfaceView(context) {
    @Volatile var mode = 0
    var onPick: (Int) -> Unit = {}
    /** Long press: add the object to (or take it out of) the selection. */
    var onTogglePick: (Int) -> Unit = {}
    var onPreviewInfo: (String) -> Unit = {}
    var onFit: () -> Unit = {}
    var onGizmoEnd: () -> Unit = {}
    /** Measure tool (gizmoMode 5): result of a tap, as measurePick JSON. */
    var onMeasure: (String) -> Unit = {}
    /** Painting (gizmoMode 6) and brim ears (7) read the brush. */
    var brush: PaintBrush? = null
    private var painting = false
    @Volatile var gizmoMode = 0
    private var dragging = false
    @Volatile private var wantPreviewInfo = false
    private val main = Handler(Looper.getMainLooper())

    private val renderer = object : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = OrcaCore.glInit()
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            OrcaCore.glResize(width, height)
            camera.aspect = width.toFloat() / max(1, height)
            main.post { onFit() }
        }
        override fun onDrawFrame(gl: GL10?) {
            camera.update(width, height)
            // Board flap black behind the plate.
            val bg = if (dark) floatArrayOf(0.043f, 0.051f, 0.051f) else floatArrayOf(0.90f, 0.90f, 0.91f)
            OrcaCore.glRender(mode, camera.view, camera.proj, bg)
            if (wantPreviewInfo && mode == 1) {
                wantPreviewInfo = false
                val info = OrcaCore.previewInfo()
                main.post { onPreviewInfo(info) }
            }
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Runs on the GL thread (viewer calls), then redraws. */
    fun onGl(block: () -> Unit) { queueEvent(block); requestRender() }

    fun requestPreviewInfo() { wantPreviewInfo = true; requestRender() }

    /** Runs [block] on the GL thread (where the viewer lives) and hands its result to [done] on the main thread. */
    fun query(block: () -> String, done: (String) -> Unit) {
        queueEvent { val r = block(); main.post { done(r) } }
        requestRender()
    }

    private var lastX = 0f; private var lastY = 0f; private var pointers = 0
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { camera.zoom(d.scaleFactor); requestRender(); return true }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode == 0) {
                camera.update(width, height)
                if (gizmoMode == 7) {
                    val b = brush
                    if (b != null && OrcaCore.brimEar(0, e.x, e.y, width, height, camera.view.clone(), camera.proj.clone(), b.radius) >= 0) { onGizmoEnd(); requestRender() }
                } else if (gizmoMode == 5) {
                    val r = OrcaCore.measurePick(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone())
                    onMeasure(r); requestRender()
                } else if (gizmoMode == 4) {
                    // Lay on face: the tapped face goes onto the bed.
                    if (OrcaCore.layOnFace(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone()) >= 0) { onGizmoEnd(); requestRender() }
                } else {
                    onPick(OrcaCore.pick(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone()))
                }
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { onFit(); requestRender(); return true }
        override fun onLongPress(e: MotionEvent) {
            if (mode != 0 || gizmoMode == 4 || pointers > 1) return
            camera.update(width, height)
            val idx = OrcaCore.pick(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone())
            if (idx >= 0) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onTogglePick(idx) }
        }
    })

    /** One finger paints; a second finger ends the stroke and moves the view. */
    private fun paint(e: MotionEvent): Boolean {
        val b = brush ?: return false
        camera.update(width, height)
        fun stroke(phase: Int) = OrcaCore.paintStroke(phase, e.x, e.y, width, height, camera.view.clone(), camera.proj.clone(), b.mode, b.state, b.radius, b.tool)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { painting = true; stroke(0) }
            MotionEvent.ACTION_MOVE -> if (painting && e.pointerCount == 1 && b.tool == 0) stroke(1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (painting) {
                painting = false; stroke(2); onGizmoEnd()
                requestRender()
                return e.actionMasked != MotionEvent.ACTION_POINTER_DOWN
            }
        }
        if (painting) requestRender()
        return painting
    }

    private fun gizmo(e: MotionEvent): Boolean {
        if (mode == 0 && gizmoMode == 6) return paint(e)
        if (mode != 0 || gizmoMode == 0 || gizmoMode == 5 || gizmoMode == 7) return false
        camera.update(width, height)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                dragging = OrcaCore.gizmoBegin(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone()) != 0
            MotionEvent.ACTION_MOVE -> if (dragging) OrcaCore.gizmoDrag(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (dragging) {
                OrcaCore.gizmoEnd(); dragging = false; onGizmoEnd()
                requestRender()
                return e.actionMasked != MotionEvent.ACTION_POINTER_DOWN
            }
        }
        if (dragging) requestRender()
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // A touch that starts on a gizmo handle (or the selected object, when moving) drags it;
        // anything else drives the camera.
        if (gizmo(e)) return true
        scale.onTouchEvent(e)
        taps.onTouchEvent(e)
        val cx = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
        val cy = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor whenever the finger count changes so the view does not jump.
                lastX = cx; lastY = cy
                pointers = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.pointerCount - 1 else e.pointerCount
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = cx - lastX; val dy = cy - lastY
                lastX = cx; lastY = cy
                if (scale.isInProgress && pointers >= 2) camera.pan(dx, dy, height)
                else if (pointers >= 2) camera.pan(dx, dy, height)
                else if (sqrt(dx * dx + dy * dy) > 0f) camera.orbit(dx, dy)
                requestRender()
            }
        }
        return true
    }
}
