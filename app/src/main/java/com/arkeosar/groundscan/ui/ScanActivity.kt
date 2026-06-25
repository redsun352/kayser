package com.arkeosar.groundscan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Vibrator
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.bluetooth.BluetoothDataSource
import com.arkeosar.groundscan.data.ArkeoSarFile
import com.arkeosar.groundscan.data.ScanDataSource
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.data.ScanReading
import com.arkeosar.groundscan.data.ScanSourceState
import com.arkeosar.groundscan.data.ScanSourceType
import com.arkeosar.groundscan.databinding.ActivityScanBinding
import com.arkeosar.groundscan.render.DisplayFunction
import com.arkeosar.groundscan.render.HeightmapRenderer
import com.arkeosar.groundscan.render.RenderMode
import com.arkeosar.groundscan.processing.ScanAnalysisEngine
import com.arkeosar.groundscan.sensors.InternalSensorSource
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

/**
 * Live 3D ground-scan screen.
 *
 * Data source priority: this activity first tries the Bluetooth probe
 * (e.g. an OKM Rover-class device). If no bonded probe is found, or the
 * connection fails, it automatically falls back to the phone's own
 * internal magnetometer ([InternalSensorSource]) so a scan can still be
 * carried out without any external hardware. The status bar always
 * shows which source is currently active.
 *
 * Visualization controls (toolbar, function picker, threshold, colorbar)
 * are modeled on ArkeoMag / Thuban Lodestar's 3D surface view.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_MAGNETOMETER = 0
        const val MODE_GROUND_SCAN = 1

        private const val DEFAULT_SCAN_METERS = 5
        private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 6000L
        private const val COLORBAR_REFRESH_INTERVAL_MS = 500L
    }

    private lateinit var binding: ActivityScanBinding
    private lateinit var renderer: HeightmapRenderer
    private lateinit var grid: ScanGrid
    private var vibrator: Vibrator? = null

    private var activeSource: ScanDataSource? = null
    private var fallbackHandler: android.os.Handler? = null
    private var fallbackTriggered = false
    private var colorbarHandler: android.os.Handler? = null

    private var currentFunction: DisplayFunction = DisplayFunction.XYZ
    private var thresholdFraction: Float = 0f // 0 = show all points, up to 1 = only the very highest band

    // touch-drag orbit state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastPinchDistance = 0f
    private var isPinching = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBluetoothWithFallback()
        } else {
            // No Bluetooth/location permission - go straight to the
            // internal sensor, which only needs the sensor itself.
            startInternalSensor()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vibrator = ContextCompat.getSystemService(this, Vibrator::class.java)

        val openFilePath = intent.getStringExtra("openFilePath")
        grid = if (openFilePath != null) {
            ArkeoSarFile.load(File(openFilePath))
        } else {
            val resolution = ScanGrid.resolutionForMeters(DEFAULT_SCAN_METERS)
            ScanGrid(columns = resolution, rows = resolution, zigzag = true)
        }

        renderer = HeightmapRenderer(this).also {
            it.grid = grid
            it.invalidateMesh()
        }

        binding.glSurfaceView.setEGLContextClientVersion(2)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        binding.glSurfaceView.setOnTouchListener { _, event -> handleTouch(event); true }

        binding.btnSave.setOnClickListener { saveScan() }

        setupFunctionPicker()
        setupPalettePicker()
        setupThresholdSlider()
        setupToolbars()
        setupViewModeTabs()
        startColorbarRefreshLoop()
        binding.btnExportCsv.setOnClickListener { exportCsv() }

        if (openFilePath != null) {
            binding.statusText.text = File(openFilePath).name
        } else {
            ensurePermissionsThenConnect()
        }
    }

    // ---------------------------------------------------------------
    // Interpolation settings panel: function picker + threshold slider
    // ---------------------------------------------------------------

    private fun setupViewModeTabs() {
        binding.tab2d.setOnClickListener { setViewMode(com.arkeosar.groundscan.render.ViewMode.TOP_DOWN_2D) }
        binding.tabSurface3d.setOnClickListener { setViewMode(com.arkeosar.groundscan.render.ViewMode.SURFACE_3D) }
        binding.tabVolumetric.setOnClickListener { setViewMode(com.arkeosar.groundscan.render.ViewMode.VOLUMETRIC_3D) }
        updateViewModeTabStyles()
    }

    private fun setViewMode(mode: com.arkeosar.groundscan.render.ViewMode) {
        renderer.viewMode = mode
        binding.schematicDisclaimer.visibility =
            if (mode == com.arkeosar.groundscan.render.ViewMode.VOLUMETRIC_3D) android.view.View.VISIBLE
            else android.view.View.GONE
        updateViewModeTabStyles()
    }

    private fun updateViewModeTabStyles() {
        val active = renderer.viewMode
        val amber = resources.getColor(com.arkeosar.groundscan.R.color.signal_amber, theme)
        val deepBg = resources.getColor(com.arkeosar.groundscan.R.color.background_deep, theme)
        val primaryText = resources.getColor(com.arkeosar.groundscan.R.color.text_primary, theme)

        fun style(view: android.widget.TextView, isActive: Boolean) {
            if (isActive) {
                view.setBackgroundColor(amber)
                view.setTextColor(deepBg)
            } else {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                view.setTextColor(primaryText)
            }
        }

        style(binding.tab2d, active == com.arkeosar.groundscan.render.ViewMode.TOP_DOWN_2D)
        style(binding.tabSurface3d, active == com.arkeosar.groundscan.render.ViewMode.SURFACE_3D)
        style(binding.tabVolumetric, active == com.arkeosar.groundscan.render.ViewMode.VOLUMETRIC_3D)
    }

    private fun setupFunctionPicker() {
        val labels = DisplayFunction.entries.map { it.label }
        binding.spinnerFunction.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerFunction.setSelection(DisplayFunction.entries.indexOf(currentFunction))
        binding.spinnerFunction.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = DisplayFunction.entries[position]
                if (selected != currentFunction) {
                    currentFunction = selected
                    grid.recomputeWithFunction(currentFunction)
                    renderer.invalidateMesh()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupPalettePicker() {
        val labels = com.arkeosar.groundscan.render.ColorPalette.entries.map { it.label }
        binding.spinnerPalette.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerPalette.setSelection(
            com.arkeosar.groundscan.render.ColorPalette.entries.indexOf(
                com.arkeosar.groundscan.render.AnomalyColorScale.activePalette
            )
        )
        binding.spinnerPalette.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = com.arkeosar.groundscan.render.ColorPalette.entries[position]
                if (selected != com.arkeosar.groundscan.render.AnomalyColorScale.activePalette) {
                    com.arkeosar.groundscan.render.AnomalyColorScale.activePalette = selected
                    renderer.invalidateMesh()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun exportCsv() {
        try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ArkeoSARGroundScan").also { it.mkdirs() }
            val file = File(dir, "scan_${System.currentTimeMillis()}.csv")
            com.arkeosar.groundscan.data.CsvExporter.export(grid, file)
            Toast.makeText(this, "${getString(com.arkeosar.groundscan.R.string.csv_exported)} ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "CSV export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupThresholdSlider() {
        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                thresholdFraction = value / 100f
                grid.thresholdFraction = thresholdFraction
                renderer.invalidateMesh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------------------------------------------------------------
    // Toolbars: top-right (ruler/view-mode/zoom/camera) and left (render mode)
    // ---------------------------------------------------------------

    private fun setupToolbars() {
        binding.btnToolRuler.setOnClickListener {
            Toast.makeText(this, getString(com.arkeosar.groundscan.R.string.tool_ruler), Toast.LENGTH_SHORT).show()
            // Measurement-mode interaction (tap two points on the surface
            // to read the distance) is a natural next step once the
            // surface has real-world unit calibration; left as a clear
            // extension point rather than guessed at here.
        }
        binding.btnToolViewMode.setOnClickListener { cycleRenderMode() }
        binding.btnToolZoom.setOnClickListener {
            renderer.zoom = 12f
            renderer.rotationX = 35f
            renderer.rotationY = 0f
        }
        binding.btnToolCamera.setOnClickListener { captureScreenshot() }

        binding.btnModeGrid.setOnClickListener { setRenderMode(RenderMode.GRID) }
        binding.btnModeWireframe.setOnClickListener { setRenderMode(RenderMode.WIREFRAME) }
        binding.btnModeSurface.setOnClickListener { setRenderMode(RenderMode.SURFACE) }
        binding.btnModePointCloud.setOnClickListener { setRenderMode(RenderMode.POINT_CLOUD) }
        binding.btnModeSave.setOnClickListener {
            binding.interpolationPanel.visibility =
                if (binding.interpolationPanel.visibility == android.view.View.VISIBLE) android.view.View.GONE
                else android.view.View.VISIBLE
        }
    }

    private fun setRenderMode(mode: RenderMode) {
        renderer.renderMode = mode
    }

    private fun cycleRenderMode() {
        val all = RenderMode.entries
        val nextIndex = (all.indexOf(renderer.renderMode) + 1) % all.size
        setRenderMode(all[nextIndex])
    }

    /**
     * Reads the GLSurfaceView's current frame back from the GPU and
     * writes it to Pictures/ArkeoSARGroundScan as a PNG - the "Ekran
     * Görüntüsü" (screenshot) tool.
     */
    private fun captureScreenshot() {
        binding.glSurfaceView.queueEvent {
            val width = binding.glSurfaceView.width
            val height = binding.glSurfaceView.height
            if (width <= 0 || height <= 0) return@queueEvent

            val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            // GL's row 0 is the bottom of the image; flip vertically.
            val flipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(flipped)
            canvas.scale(1f, -1f, width / 2f, height / 2f)
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            runOnUiThread { saveScreenshotBitmap(flipped) }
        }
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap) {
        try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ArkeoSARGroundScan").also { it.mkdirs() }
            val file = File(dir, "scan_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            Toast.makeText(this, file.name, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "screenshot failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------
    // Colorbar legend refresh loop
    // ---------------------------------------------------------------

    private fun startColorbarRefreshLoop() {
        val handler = android.os.Handler(mainLooper)
        colorbarHandler = handler
        val refresh = object : Runnable {
            override fun run() {
                val values = grid.allPoints().mapNotNull { if (it.hasValue) it.value else null }
                binding.colorbarView.update(renderer.lastValueRangeMin, renderer.lastValueRangeMax, values)
                handler.postDelayed(this, COLORBAR_REFRESH_INTERVAL_MS)
            }
        }
        handler.post(refresh)
    }

    // ---------------------------------------------------------------
    // Data source lifecycle (Bluetooth probe -> internal sensor fallback)
    // ---------------------------------------------------------------

    private fun ensurePermissionsThenConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        }
        needed += Manifest.permission.ACCESS_FINE_LOCATION

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBluetoothWithFallback()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    /**
     * Starts the Bluetooth source and arms a timeout: if it hasn't
     * reached [ScanSourceState.ACTIVE] within [BLUETOOTH_CONNECT_TIMEOUT_MS],
     * or if it reports UNAVAILABLE/ERROR at any point, we switch to the
     * internal sensor automatically.
     */
    private fun startBluetoothWithFallback() {
        fallbackTriggered = false
        val bluetoothSource = BluetoothDataSource(this)
        activeSource = bluetoothSource

        val handler = android.os.Handler(mainLooper)
        fallbackHandler = handler
        val timeoutRunnable = Runnable {
            if (!fallbackTriggered) {
                triggerFallbackToInternalSensor()
            }
        }
        handler.postDelayed(timeoutRunnable, BLUETOOTH_CONNECT_TIMEOUT_MS)

        bluetoothSource.start(
            onStateChanged = { state ->
                runOnUiThread {
                    updateStatusText(ScanSourceType.BLUETOOTH_PROBE, state)
                    when (state) {
                        ScanSourceState.ACTIVE -> handler.removeCallbacks(timeoutRunnable)
                        ScanSourceState.UNAVAILABLE, ScanSourceState.ERROR -> {
                            handler.removeCallbacks(timeoutRunnable)
                            triggerFallbackToInternalSensor()
                        }
                        else -> { /* CONNECTING / IDLE: keep waiting for the timeout */ }
                    }
                }
            },
            onReading = { reading -> runOnUiThread { onSourceReading(reading) } }
        )
    }

    private fun triggerFallbackToInternalSensor() {
        if (fallbackTriggered) return
        fallbackTriggered = true
        activeSource?.stop()
        startInternalSensor()
    }

    private fun startInternalSensor() {
        val sensorSource = InternalSensorSource(this)
        activeSource = sensorSource
        sensorSource.start(
            onStateChanged = { state -> runOnUiThread { updateStatusText(ScanSourceType.INTERNAL_SENSOR, state) } },
            onReading = { reading -> runOnUiThread { onSourceReading(reading) } }
        )
    }

    private fun updateStatusText(source: ScanSourceType, state: ScanSourceState) {
        val sourceLabel = when (source) {
            ScanSourceType.BLUETOOTH_PROBE -> getString(com.arkeosar.groundscan.R.string.source_bluetooth_probe)
            ScanSourceType.INTERNAL_SENSOR -> getString(com.arkeosar.groundscan.R.string.source_internal_sensor)
        }
        val stateLabel = when (state) {
            ScanSourceState.IDLE -> getString(com.arkeosar.groundscan.R.string.scan_disconnected)
            ScanSourceState.CONNECTING -> getString(com.arkeosar.groundscan.R.string.scan_connect)
            ScanSourceState.ACTIVE -> getString(com.arkeosar.groundscan.R.string.scan_connected)
            ScanSourceState.UNAVAILABLE -> getString(com.arkeosar.groundscan.R.string.scan_no_device)
            ScanSourceState.ERROR -> getString(com.arkeosar.groundscan.R.string.scan_disconnected)
        }
        binding.statusText.text = "$sourceLabel — $stateLabel"
    }

    private fun onSourceReading(reading: ScanReading) {
        val point = grid.addValue(
            value = reading.value,
            latitude = reading.latitude,
            longitude = reading.longitude,
            altitude = reading.altitude,
            rawX = reading.rawX,
            rawY = reading.rawY,
            rawZ = reading.rawZ,
            rawMagnitude = reading.rawMagnitude,
            baseline = reading.baseline,
            delta = reading.delta,
            anomalyScore = reading.anomalyScore,
            confidence = reading.confidence,
            targetClass = reading.targetHint ?: "Normal zemin"
        )
        if (point != null) {
            ScanAnalysisEngine.updatePoint(point, grid)
            renderer.invalidateMesh()
            updateAnalysisPanel(reading)
            if (point.anomalyScore >= 0.78f || reading.buttonPressed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(28, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vibrator?.vibrate(28)
                }
            }
        }
    }

    private fun updateAnalysisPanel(reading: ScanReading? = null) {
        val summary = ScanAnalysisEngine.summarize(grid)
        val raw = reading?.rawMagnitude?.let { String.format(Locale.US, "ham %.1f µT", it) } ?: "ham —"
        val delta = reading?.delta?.let { String.format(Locale.US, "Δ %.2f", it) } ?: "Δ —"
        binding.liveReadingText.text = String.format(
            Locale.US,
            "Değer: %.2f  |  %s  |  %s",
            reading?.value ?: summary.strongestValue,
            raw,
            delta
        )
        binding.analysisText.text = "İlerleme %${summary.progressPercent} • ${summary.shortText}"
        binding.targetText.text = String.format(
            Locale.US,
            "En güçlü nokta: R%d C%d • skor %%%d • ortalama skor %%%d",
            summary.strongestRow + 1,
            summary.strongestColumn + 1,
            (summary.strongestScore * 100).toInt(),
            (summary.averageScore * 100).toInt()
        )
    }

    // ---------------------------------------------------------------
    // Touch: one-finger orbit, two-finger pinch-zoom
    // ---------------------------------------------------------------

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    renderer.rotationY += dx * 0.5f
                    renderer.rotationX = (renderer.rotationX + dy * 0.5f).coerceIn(-80f, 80f)
                    lastTouchX = event.x
                    lastTouchY = event.y
                } else if (event.pointerCount == 2) {
                    val distance = pinchDistance(event)
                    if (!isPinching) {
                        isPinching = true
                        lastPinchDistance = distance
                    } else {
                        val delta = distance - lastPinchDistance
                        renderer.zoom = (renderer.zoom - delta * 0.05f).coerceIn(4f, 40f)
                        lastPinchDistance = distance
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                isPinching = false
            }
        }
    }

    private fun pinchDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun saveScan() {
        val dir = File(filesDir, "scans").also { it.mkdirs() }
        val filename = "scan_${System.currentTimeMillis()}.asgs"
        val file = File(dir, filename)
        ArkeoSarFile.save(grid, file, metadata = mapOf("app" to "ArkeoSAR Ground Scan", "analysis" to ScanAnalysisEngine.summarize(grid).shortText))
        try {
            com.arkeosar.groundscan.data.CsvExporter.export(grid, File(dir, filename.replace(".asgs", ".csv")))
        } catch (_: Exception) {}
        binding.statusText.text = filename
    }

    override fun onDestroy() {
        super.onDestroy()
        fallbackHandler?.removeCallbacksAndMessages(null)
        colorbarHandler?.removeCallbacksAndMessages(null)
        activeSource?.stop()
    }
}
