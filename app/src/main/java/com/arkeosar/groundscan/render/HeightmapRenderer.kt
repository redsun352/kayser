package com.arkeosar.groundscan.render

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arkeosar.groundscan.data.ScanGrid
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders a [ScanGrid] under one of three [ViewMode]s using OpenGL ES 2.0:
 *
 * - TOP_DOWN_2D / SURFACE_3D: the lit, thin-plate-spline-interpolated
 *   surface ([HeightmapMesh]) - TOP_DOWN_2D simply locks the orbit
 *   camera to look straight down, giving a flat map-style view of the
 *   same data without needing separate geometry.
 * - VOLUMETRIC_3D: [VolumetricMesh]'s stacked translucent depth slices,
 *   drawn with a second, unlit alpha-blended shader program (lighting
 *   doesn't make sense for stacked transparent slices the way it does
 *   for a single opaque surface).
 *
 * Camera controls: orbit (rotate around the grid) driven by touch drag,
 * and pinch-to-zoom, both wired up from [com.arkeosar.groundscan.ui.ScanActivity].
 */
class HeightmapRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile var grid: ScanGrid? = null
    @Volatile var meshDirty: Boolean = true
    @Volatile var renderMode: RenderMode = RenderMode.SURFACE
    @Volatile var viewMode: ViewMode = ViewMode.SURFACE_3D

    var rotationX: Float = 35f   // tilt
    var rotationY: Float = 0f    // orbit
    var zoom: Float = 12f

    /** Updated every frame the mesh is rebuilt; read by the colorbar UI. */
    @Volatile var lastValueRangeMin: Float = 0f
    @Volatile var lastValueRangeMax: Float = 1f

    private var mesh: HeightmapMesh? = null
    private var volumetricMesh: VolumetricMesh? = null

    // Surface program (lit, opaque)
    private var surfaceProgram: Int = 0
    private var positionHandle: Int = 0
    private var normalHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var normalMatrixHandle: Int = 0
    private var lightDirectionHandle: Int = 0
    private var pointSizeHandle: Int = 0

    // Volumetric program (unlit, alpha-blended)
    private var volumetricProgram: Int = 0
    private var volPositionHandle: Int = 0
    private var volColorHandle: Int = 0
    private var volMvpMatrixHandle: Int = 0

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

        surfaceProgram = buildProgram("shaders/heightmap.vert", "shaders/heightmap.frag")
        positionHandle = GLES20.glGetAttribLocation(surfaceProgram, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(surfaceProgram, "aNormal")
        colorHandle = GLES20.glGetAttribLocation(surfaceProgram, "aColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(surfaceProgram, "uMVPMatrix")
        normalMatrixHandle = GLES20.glGetUniformLocation(surfaceProgram, "uNormalMatrix")
        lightDirectionHandle = GLES20.glGetUniformLocation(surfaceProgram, "uLightDirection")
        pointSizeHandle = GLES20.glGetUniformLocation(surfaceProgram, "uPointSize")

        volumetricProgram = buildProgram("shaders/volumetric.vert", "shaders/volumetric.frag")
        volPositionHandle = GLES20.glGetAttribLocation(volumetricProgram, "aPosition")
        volColorHandle = GLES20.glGetAttribLocation(volumetricProgram, "aColor")
        volMvpMatrixHandle = GLES20.glGetUniformLocation(volumetricProgram, "uMVPMatrix")
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
            volumetricMesh = VolumetricMesh(currentGrid)
            lastValueRangeMin = mesh!!.valueRangeMin
            lastValueRangeMax = mesh!!.valueRangeMax
            meshDirty = false
        }

        updateViewMatrix()
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        when (viewMode) {
            ViewMode.TOP_DOWN_2D, ViewMode.SURFACE_3D -> drawSurface()
            ViewMode.VOLUMETRIC_3D -> drawVolumetric()
        }
    }

    /**
     * True orbit camera: the eye point sits on a sphere of radius
     * [zoom] around the origin, parameterized by pitch ([rotationX])
     * and yaw ([rotationY]). [ViewMode.TOP_DOWN_2D] overrides pitch to
     * look straight down, giving a flat map view without needing
     * separate 2D geometry - same mesh, different camera.
     */
    private fun updateViewMatrix() {
        val effectivePitch = if (viewMode == ViewMode.TOP_DOWN_2D) 89.9f else rotationX
        val pitchRad = Math.toRadians(effectivePitch.toDouble())
        val yawRad = Math.toRadians(rotationY.toDouble())

        val horizontalRadius = zoom * kotlin.math.cos(pitchRad).toFloat()
        val eyeX = horizontalRadius * kotlin.math.sin(yawRad).toFloat()
        val eyeZ = horizontalRadius * kotlin.math.cos(yawRad).toFloat()
        val eyeY = zoom * kotlin.math.sin(pitchRad).toFloat()

        // Looking nearly straight down makes the "up" vector ambiguous
        // right at the pole; nudge it slightly off pure (0,1,0) to keep
        // LookAt well-defined for TOP_DOWN_2D.
        val upZ = if (viewMode == ViewMode.TOP_DOWN_2D) -1f else 0f
        val upY = if (viewMode == ViewMode.TOP_DOWN_2D) 0.001f else 1f

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            0f, 0f, 0f,
            0f, upY, upZ
        )
    }

    private fun drawSurface() {
        val currentMesh = mesh ?: return

        extractUpper3x3(modelMatrix, normalMatrix4x4)
        copy4x4UpperTo3x3(normalMatrix4x4, normalMatrix3x3)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(surfaceProgram)

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
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, currentMesh.indexCount, GLES20.GL_UNSIGNED_SHORT, currentMesh.indexBuffer)
            }
            RenderMode.WIREFRAME -> {
                GLES20.glDrawElements(GLES20.GL_LINES, currentMesh.wireframeIndexCount, GLES20.GL_UNSIGNED_SHORT, currentMesh.wireframeIndexBuffer)
            }
            RenderMode.POINT_CLOUD -> {
                GLES20.glDrawElements(GLES20.GL_POINTS, currentMesh.pointIndexCount, GLES20.GL_UNSIGNED_SHORT, currentMesh.pointIndexBuffer)
            }
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun drawVolumetric() {
        val currentVolumetric = volumetricMesh ?: return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Depth writes off (but depth test on) so overlapping translucent
        // slices blend with each other instead of occluding by depth.
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(volumetricProgram)

        GLES20.glEnableVertexAttribArray(volPositionHandle)
        GLES20.glVertexAttribPointer(volPositionHandle, 3, GLES20.GL_FLOAT, false, 0, currentVolumetric.vertexBuffer)

        GLES20.glEnableVertexAttribArray(volColorHandle)
        GLES20.glVertexAttribPointer(volColorHandle, 4, GLES20.GL_FLOAT, false, 0, currentVolumetric.colorBuffer)

        GLES20.glUniformMatrix4fv(volMvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, currentVolumetric.indexCount, GLES20.GL_UNSIGNED_SHORT, currentVolumetric.indexBuffer)

        GLES20.glDisableVertexAttribArray(volPositionHandle)
        GLES20.glDisableVertexAttribArray(volColorHandle)

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Call after the grid's data changes (e.g. a new measurement arrives) or the display function changes. */
    fun invalidateMesh() {
        meshDirty = true
    }

    private fun buildProgram(vertPath: String, fragPath: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, loadShaderAsset(vertPath))
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, loadShaderAsset(fragPath))
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
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
