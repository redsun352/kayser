package com.arkeosar.groundscan.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.bluetooth.BluetoothDataSource
import com.arkeosar.groundscan.data.ScanAxis
import com.arkeosar.groundscan.data.ScanDataSource
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.data.ScanReading
import com.arkeosar.groundscan.data.ScanSourceState
import com.arkeosar.groundscan.data.ScanSourceType
import com.arkeosar.groundscan.databinding.ActivityGridScanBinding
import com.arkeosar.groundscan.sensors.InternalSensorSource
import java.io.File

/**
 * 2D detector grid screen: the first step of a ground scan, modeled on
 * ArkeoMag / Thuban Lodestar's cell-by-cell scan view. The scanner walks
 * a column-major zigzag pattern (see [ScanAxis.COLUMN_MAJOR]); each cell
 * is colored as it's measured, with a live-interpolated preview for
 * unmeasured cells so the grid never looks blank mid-scan.
 *
 * When every cell has been measured, this hands off automatically to
 * [ScanActivity] for the 3D surface view, passing along the same
 * [ScanGrid] data (via a temporary save file) so the 3D view starts
 * with the completed scan already loaded - mirroring the reference
 * app's automatic 2D-grid-to-3D-surface transition.
 */
class GridScanActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_SCAN_METERS = 5
        private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 6000L
        private const val TRANSITION_DELAY_MS = 900L // lets the "tamamlandı" moment register before handoff
    }

    private lateinit var binding: ActivityGridScanBinding
    private lateinit var grid: ScanGrid

    private var activeSource: ScanDataSource? = null
    private var fallbackHandler: Handler? = null
    private var fallbackTriggered = false
    private var handedOff = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBluetoothWithFallback()
        } else {
            startInternalSensor()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val resolution = ScanGrid.resolutionForMeters(DEFAULT_SCAN_METERS, samplesPerMeter = 1).coerceAtMost(8)
        // Note: the detector grid is intentionally coarser than the 3D
        // scan's dense walking-survey grid (a handful of cells per side,
        // matching the reference app's 5x5-style detector view) - the
        // dense interpolation happens visually within GridScanView, not
        // by having hundreds of physical grid cells.
        grid = ScanGrid(columns = resolution, rows = resolution, zigzag = true, scanAxis = ScanAxis.COLUMN_MAJOR)
        binding.gridScanView.grid = grid

        ensurePermissionsThenConnect()
    }

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

    private fun startBluetoothWithFallback() {
        fallbackTriggered = false
        val bluetoothSource = BluetoothDataSource(this)
        activeSource = bluetoothSource

        val handler = Handler(mainLooper)
        fallbackHandler = handler
        val timeoutRunnable = Runnable {
            if (!fallbackTriggered) triggerFallbackToInternalSensor()
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
                        else -> {}
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
        if (handedOff || grid.isComplete) return

        val point = grid.addValue(
            value = reading.value,
            latitude = reading.latitude,
            longitude = reading.longitude,
            altitude = reading.altitude,
            rawX = reading.rawX,
            rawY = reading.rawY,
            rawZ = reading.rawZ
        )
        if (point != null) {
            binding.liveValueText.text = String.format("%.1f", reading.value)
            binding.progressText.text = "${grid.filledPoints} / ${grid.totalPoints}"
            binding.gridScanView.refresh()

            if (grid.isComplete) {
                handOffToSurfaceView()
            }
        }
    }

    /**
     * Saves the completed grid to a temporary file and launches
     * [ScanActivity] to open it directly, then finishes this activity -
     * the same automatic "grid done -> show me the 3D surface" handoff
     * seen in the reference app's "Lütfen Bekleyiniz..." transition.
     */
    private fun handOffToSurfaceView() {
        if (handedOff) return
        handedOff = true
        activeSource?.stop()

        binding.transitionOverlay.visibility = android.view.View.VISIBLE

        Handler(mainLooper).postDelayed({
            val dir = File(filesDir, "scans").also { it.mkdirs() }
            val file = File(dir, "handoff_${System.currentTimeMillis()}.asgs")
            com.arkeosar.groundscan.data.ArkeoSarFile.save(grid, file, metadata = mapOf("source" to "GridScanActivity"))

            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra("openFilePath", file.absolutePath)
            )
            finish()
        }, TRANSITION_DELAY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        fallbackHandler?.removeCallbacksAndMessages(null)
        if (!handedOff) {
            activeSource?.stop()
        }
    }
}
