package ca.justinmo.starfield

import android.annotation.SuppressLint
import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: StarfieldRenderer

    private val INITIAL_SPEED = 3000f
    private val ORIGINAL_STAR_COUNT = 150000 / 4
    private val maxSpeed = 1f / Interpolation.exp5In(0.5f) * INITIAL_SPEED
    private val maxStarCount = (1f / Interpolation.exp5In(0.5f) * ORIGINAL_STAR_COUNT).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_layout)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        glSurfaceView = findViewById(R.id.gl_surface_view)
        glSurfaceView.setEGLContextClientVersion(2)
        
        // Enable antialiasing (MSAA)
        glSurfaceView.setEGLConfigChooser(object : GLSurfaceView.EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
                val sampleCounts = intArrayOf(16, 8, 4, 2)
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)

                for (samples in sampleCounts) {
                    val attributes = intArrayOf(
                        EGL10.EGL_RED_SIZE, 8,
                        EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 16,
                        EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                        EGL10.EGL_SAMPLE_BUFFERS, 1,
                        EGL10.EGL_SAMPLES, samples,
                        EGL10.EGL_NONE
                    )
                    if (egl.eglChooseConfig(display, attributes, configs, 1, numConfigs) && numConfigs[0] > 0) {
                        return configs[0]
                    }
                }

                // Fallback without multisampling
                val fallbackAttributes = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE
                )
                egl.eglChooseConfig(display, fallbackAttributes, configs, 1, numConfigs)
                return configs[0]
            }
        })

        renderer = StarfieldRenderer(maxStarCount, ORIGINAL_STAR_COUNT, INITIAL_SPEED)

        // Debug builds wrap the renderer so we can count real frames and show an
        // FPS overlay; release hands the surface view the renderer unwrapped, so
        // the counter costs nothing and never appears.
        val activeRenderer: GLSurfaceView.Renderer = if (BuildConfig.DEBUG) {
            val fpsView = findViewById<TextView>(R.id.fps_counter)
            fpsView.visibility = View.VISIBLE
            FpsRenderer(
                delegate = renderer,
                // Same expression StarfieldRenderer.drawStars uses, sampled on the
                // GL thread so the number matches the frame that was just drawn.
                starCount = { (maxStarCount * renderer.starPercentage).roundToInt() },
                onSample = { fps, stars ->
                    runOnUiThread { fpsView.text = "$fps FPS\n$stars stars" }
                }
            )
        } else {
            renderer
        }
        glSurfaceView.setRenderer(activeRenderer)

        glSurfaceView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                    val px = (event.x / glSurfaceView.width).coerceIn(0f, 1f)
                    val py = (event.y / glSurfaceView.height).coerceIn(0.01f, 1f)

                    // Match original logic: speed from X, density from Y
                    renderer.speed = Interpolation.exp5In(px) * maxSpeed
                    renderer.starPercentage = Interpolation.exp5In(py)
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    /**
     * Debug-only renderer wrapper that forwards every call to the real renderer
     * and reports an averaged frame rate plus the star count once per second.
     *
     * GLSurfaceView exposes no frame callback, so onDrawFrame is the only place
     * that sees genuinely presented frames. It runs on the GL thread, which is
     * why the host hops the result back to the UI thread.
     */
    private class FpsRenderer(
        private val delegate: GLSurfaceView.Renderer,
        private val starCount: () -> Int,
        private val onSample: (fps: Int, stars: Int) -> Unit
    ) : GLSurfaceView.Renderer {

        private var frames = 0
        private var windowStart = SystemClock.elapsedRealtime()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            delegate.onSurfaceCreated(gl, config)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            delegate.onSurfaceChanged(gl, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            delegate.onDrawFrame(gl)

            val now = SystemClock.elapsedRealtime()
            // Start each window at its own first frame, so time spent paused or
            // backgrounded cannot drag the average down.
            if (frames == 0) windowStart = now
            frames++

            val elapsed = now - windowStart
            if (elapsed >= FPS_WINDOW_MS) {
                onSample((frames * 1000f / elapsed).roundToInt(), starCount())
                frames = 0
            }
        }

        private companion object {
            const val FPS_WINDOW_MS = 1000L
        }
    }
}
