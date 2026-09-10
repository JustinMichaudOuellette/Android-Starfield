package ca.justinmo.starfield

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

class StarfieldRenderer(
    private val maxStarCount: Int,
    initialStarCount: Int,
    initialSpeed: Float)
: GLSurfaceView.Renderer {

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var vPMatrixHandle: Int = 0

    private val STAR_SIZE = 7.5f
    private val MAX_Z = 30000f

    private val VIEWPORT_RANGE = 3.5f
    private val DEAD_CENTER_RANGE = 0.075f

    private lateinit var vertexBuffer: FloatBuffer
    private val coordsPerVertex = 3
    private val vertexStride = coordsPerVertex * 4 // 4 bytes per float

    private var lastTime: Long = 0
    var speed = initialSpeed
    var starPercentage = initialStarCount / maxStarCount.toFloat()

    private var width: Int = 0
    private var height: Int = 0

    private var zOffset1 = 0f
    private var zOffset2 = -MAX_Z

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, StarShaders.VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, StarShaders.FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        vPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        lastTime = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)

        val ratio: Float = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 1f, MAX_Z)

        generateStarBuffer()
    }

    private fun generateStarBuffer() {
        val rng = Random()
        // 6 vertices per star, 3 coords each = 18 floats per star
        val starData = FloatArray(maxStarCount * 18)
        val deadRangeX = width * DEAD_CENTER_RANGE
        val deadRangeY = height * DEAD_CENTER_RANGE
        val rangeX = width * VIEWPORT_RANGE
        val rangeY = height * VIEWPORT_RANGE

        for (i in 0 until maxStarCount) {
            val xSide = if (rng.nextBoolean()) -1 else 1
            val ySide = if (rng.nextBoolean()) -1 else 1
            
            var rx: Float
            var ry: Float
            while (true) {
                rx = rangeX * rng.nextFloat()
                ry = rangeY * rng.nextFloat()
                if (rx > deadRangeX || ry > deadRangeY) break
            }
            
            val x = rx * xSide
            val y = ry * ySide
            val z = -MAX_Z * rng.nextFloat()
            
            val base = i * 18
            // Triangle 1
            starData[base] = x;          starData[base + 1] = y;              starData[base + 2] = z
            starData[base + 3] = x;      starData[base + 4] = y - STAR_SIZE;  starData[base + 5] = z
            starData[base + 6] = x - STAR_SIZE; starData[base + 7] = y - STAR_SIZE; starData[base + 8] = z
            // Triangle 2
            starData[base + 9] = x - STAR_SIZE; starData[base + 10] = y - STAR_SIZE; starData[base + 11] = z
            starData[base + 12] = x - STAR_SIZE; starData[base + 13] = y;         starData[base + 14] = z
            starData[base + 15] = x;     starData[base + 16] = y;             starData[base + 17] = z
        }

        val bb = ByteBuffer.allocateDirect(starData.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(starData)
        vertexBuffer.position(0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val currentTime = SystemClock.elapsedRealtime()
        val elapsedSeconds = (currentTime - lastTime) / 1000f
        lastTime = currentTime

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        zOffset1 += speed * elapsedSeconds
        if (zOffset1 >= MAX_Z) zOffset1 -= 2 * MAX_Z

        zOffset2 += speed * elapsedSeconds
        if (zOffset2 >= MAX_Z) zOffset2 -= 2 * MAX_Z

        drawStars(zOffset1)
        drawStars(zOffset2)
    }

    private fun drawStars(zOffset: Float) {
        GLES20.glUseProgram(program)

        // Matrix logic: emulate Processing's camera and transform
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 500f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, zOffset)
        
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        val finalMVP = FloatArray(16)
        Matrix.multiplyMM(finalMVP, 0, vPMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(vPMatrixHandle, 1, false, finalMVP, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, coordsPerVertex, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer)

        val starsToDraw = (maxStarCount * starPercentage).roundToInt()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, starsToDraw * 6)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
