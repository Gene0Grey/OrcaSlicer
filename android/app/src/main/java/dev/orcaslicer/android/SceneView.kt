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

    fun fit(minX: Float, minY: Float, maxX: Float, maxY: Float, height: Float = 0f) {
        tx = (minX + maxX) / 2; ty = (minY + maxY) / 2; tz = height / 3
        val size = max(maxX - minX, maxY - minY)
        distance = size * 1.9f + 60f
        azimuth = -90f; elevation = 35f
    }

    fun update(width: Int, height: Int) {
        val az = Math.toRadians(azimuth.toDouble()); val el = Math.toRadians(elevation.toDouble())
        val ex = tx + distance * (cos(el) * cos(az)).toFloat()
        val ey = ty + distance * (cos(el) * sin(az)).toFloat()
        val ez = tz + distance * sin(el).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / max(1, height), max(1f, distance * 0.02f), distance * 8f + 2000f)
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
 * One finger orbits, two fingers pan, pinch zooms, tap selects, double tap fits the plate.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SceneView(context: Context, val camera: OrbitCamera, private val dark: Boolean) : GLSurfaceView(context) {
    @Volatile var mode = 0
    var onPick: (Int) -> Unit = {}
    var onPreviewInfo: (String) -> Unit = {}
    var onFit: () -> Unit = {}
    @Volatile private var wantPreviewInfo = false
    private val main = Handler(Looper.getMainLooper())

    private val renderer = object : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = OrcaCore.glInit()
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = OrcaCore.glResize(width, height)
        override fun onDrawFrame(gl: GL10?) {
            camera.update(width, height)
            val bg = if (dark) floatArrayOf(0.17f, 0.17f, 0.18f) else floatArrayOf(0.90f, 0.90f, 0.91f)
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

    private var lastX = 0f; private var lastY = 0f; private var pointers = 0
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { camera.zoom(d.scaleFactor); requestRender(); return true }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode == 0) {
                camera.update(width, height)
                val idx = OrcaCore.pick(e.x, e.y, width, height, camera.view.clone(), camera.proj.clone())
                onPick(idx)
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { onFit(); requestRender(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
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
