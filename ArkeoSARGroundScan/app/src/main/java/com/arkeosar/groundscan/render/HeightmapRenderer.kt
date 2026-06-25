package com.arkeosar.groundscan.render

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arkeosar.groundscan.data.ScanGrid
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders a [ScanGrid] as a smooth, lit 3D surface using OpenGL ES 2.0
 * with a programmable vertex/fragment shader pair (see assets/shaders).
 * The mesh itself ([HeightmapMesh]) is built from a thin-plate-spline
 * interpolation of the grid's filled points, which is what gives the
 * surface its continuous, draped look (matching the reference look from
 * ArkeoMag / Thuban Lodestar's "İnce Plaka" surface mode) instead of a
 * blocky per-cell mesh.
 *
 * Camera controls: orbit (rotate around the grid) driven by touch drag,
 * and pinch-to-zoom, both wired up from [com.arkeosar.groundscan.ui.ScanActivity].
 */
class HeightmapRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile var grid: ScanGrid? = null
    @Volatile var meshDirty: Boolean = true
    @Volatile var renderMode: RenderMode = RenderMode.SURFACE

    var rotationX: Float = 35f   // tilt
    var rotationY: Float = 0f    // orbit
    var zoom: Float = 12f

    /** Updated every frame the mesh is rebuilt; read by the colorbar UI. */
    @Volatile var lastValueRangeMin: Float = 0f
    @Volatile var lastValueRangeMax: Float = 1f

    private var mesh: HeightmapMesh? = null
    private var program: Int = 0
    private var positionHandle: Int = 0
    private var normalHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var normalMatrixHandle: Int = 0
    private var lightDirectionHandle: Int = 0
    private var pointSizeHandle: Int = 0

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix3x3 = FloatArray(9)
    private val normalMatrix4x4 = FloatArray(16)

    // Fixed world-space light direction (pointing down and slightly
    // toward the viewer), giving a stable, pleasant shading regardless
    // of camera orbit.
    private val lightDirection = floatArrayOf(-0.4f, -0.8f, -0.4f).also { normalizeInPlace(it) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.043f, 0.059f, 0.055f, 1f) // background_deep
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        val vertexShaderSrc = loadShaderAsset("shaders/heightmap.vert")
        val fragmentShaderSrc = loadShaderAsset("shaders/heightmap.frag")

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        normalMatrixHandle = GLES20.glGetUniformLocation(program, "uNormalMatrix")
        lightDirectionHandle = GLES20.glGetUniformLocation(program, "uLightDirection")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentGrid = grid ?: return
        if (meshDirty) {
            mesh = HeightmapMesh(currentGrid)
            lastValueRangeMin = mesh!!.valueRangeMin
            lastValueRangeMax = mesh!!.valueRangeMax
            meshDirty = false
        }
        val currentMesh = mesh ?: return

        // True orbit camera: the eye point is placed on a sphere of
        // radius `zoom` around the origin, parameterized by pitch
        // (rotationX, clamped so it can't flip past looking straight
        // down/up) and yaw (rotationY). This replaces the earlier
        // approach of rotating the *model* instead of the camera, which
        // made the surface appear to skew into a flat, trapezoid-looking
        // slab at most angles instead of a readable 3D relief.
        val pitchRad = Math.toRadians(rotationX.toDouble())
        val yawRad = Math.toRadians(rotationY.toDouble())

        val horizontalRadius = zoom * kotlin.math.cos(pitchRad).toFloat()
        val eyeX = horizontalRadius * kotlin.math.sin(yawRad).toFloat()
        val eyeZ = horizontalRadius * kotlin.math.cos(yawRad).toFloat()
        val eyeY = zoom * kotlin.math.sin(pitchRad).toFloat()

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ, // eye: orbits around the origin
            0f, 0f, 0f,       // center: always looking at the grid's center
            0f, 1f, 0f        // up
        )

        Matrix.setIdentityM(modelMatrix, 0) // the model itself never rotates anymore

        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // The model never rotates now (the camera orbits instead), so
        // vertex normals are already in world space as computed by
        // HeightmapMesh - the normal matrix is simply identity here.
        // Keeping the uniform wired up (rather than removing it) leaves
        // room to reintroduce model transforms later without touching
        // the shader again.
        extractUpper3x3(modelMatrix, normalMatrix4x4)
        copy4x4UpperTo3x3(normalMatrix4x4, normalMatrix3x3)

        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, currentMesh.vertexBuffer)

        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, currentMesh.normalBuffer)

        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, currentMesh.colorBuffer)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix3fv(normalMatrixHandle, 1, false, normalMatrix3x3, 0)
        GLES20.glUniform3fv(lightDirectionHandle, 1, lightDirection, 0)
        GLES20.glUniform1f(pointSizeHandle, if (renderMode == RenderMode.POINT_CLOUD) 8f else 1f)

        when (renderMode) {
            RenderMode.SURFACE, RenderMode.GRID -> {
                // GRID currently reuses the lit surface draw; the wireframe
                // overlay on top of it (drawn by the left toolbar's grid
                // icon conceptually) is provided by switching to WIREFRAME
                // mode outright, keeping this renderer simple.
                GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES,
                    currentMesh.indexCount,
                    GLES20.GL_UNSIGNED_SHORT,
                    currentMesh.indexBuffer
                )
            }
            RenderMode.WIREFRAME -> {
                GLES20.glDrawElements(
                    GLES20.GL_LINES,
                    currentMesh.wireframeIndexCount,
                    GLES20.GL_UNSIGNED_SHORT,
                    currentMesh.wireframeIndexBuffer
                )
            }
            RenderMode.POINT_CLOUD -> {
                GLES20.glDrawElements(
                    GLES20.GL_POINTS,
                    currentMesh.pointIndexCount,
                    GLES20.GL_UNSIGNED_SHORT,
                    currentMesh.pointIndexBuffer
                )
            }
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    /** Call after the grid's data changes (e.g. a new measurement arrives) or the display function changes. */
    fun invalidateMesh() {
        meshDirty = true
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun loadShaderAsset(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private fun extractUpper3x3(src4x4: FloatArray, dst4x4: FloatArray) {
        System.arraycopy(src4x4, 0, dst4x4, 0, 16)
    }

    private fun copy4x4UpperTo3x3(src4x4: FloatArray, dst3x3: FloatArray) {
        // Matrix.* arrays are column-major.
        dst3x3[0] = src4x4[0]; dst3x3[1] = src4x4[1]; dst3x3[2] = src4x4[2]
        dst3x3[3] = src4x4[4]; dst3x3[4] = src4x4[5]; dst3x3[5] = src4x4[6]
        dst3x3[6] = src4x4[8]; dst3x3[7] = src4x4[9]; dst3x3[8] = src4x4[10]
    }

    private fun normalizeInPlace(v: FloatArray) {
        val len = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len > 1e-6f) {
            v[0] /= len; v[1] /= len; v[2] /= len
        }
    }
}
