package com.arkeosar.groundscan.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.data.ScanDataSource
import com.arkeosar.groundscan.data.ScanReading
import com.arkeosar.groundscan.data.ScanSourceState
import com.arkeosar.groundscan.data.ScanSourceType
import com.arkeosar.groundscan.processing.SensorFusionProcessor

/**
 * Reads the phone's own built-in magnetometer (Sensor.TYPE_MAGNETIC_FIELD)
 * as a [ScanDataSource]. This lets the app keep working as a ground-scan
 * tool even with no OKM Rover / external probe connected - useful for
 * quick scans, testing the workflow, or as a fallback when the Bluetooth
 * link is unavailable.
 *
 * The reading reported is the magnitude of the 3-axis magnetic field
 * vector (in microtesla), which is the standard way to turn a phone's
 * raw 3-axis magnetometer into a single anomaly-style intensity value -
 * the same general approach used by any magnetometer-based survey tool,
 * independent of any particular vendor's implementation.
 *
 * Caveats worth knowing (surfaced in the UI via [ScanSourceType.INTERNAL_SENSOR]
 * so the user knows the data quality is different from a real probe):
 *   - Phone magnetometers are far less sensitive than a dedicated probe
 *     and pick up the phone's own internals (speaker magnets, case, etc).
 *   - There is no physical trigger button, so "manual mode" doesn't apply;
 *     this source always behaves like automatic mode.
 */
class InternalSensorSource(private val context: Context) : ScanDataSource {

    override val sourceType: ScanSourceType = ScanSourceType.INTERNAL_SENSOR

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var listener: SensorEventListener? = null
    private var locationListener: LocationListener? = null

    private var lastLocation: Location? = null
    private val processor = SensorFusionProcessor()

    fun isAvailable(): Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

    override fun start(
        onStateChanged: (ScanSourceState) -> Unit,
        onReading: (ScanReading) -> Unit
    ) {
        val manager = sensorManager
        val magnetometer = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (manager == null || magnetometer == null) {
            onStateChanged(ScanSourceState.UNAVAILABLE)
            return
        }

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val processed = processor.process(x, y, z)

                onReading(
                    ScanReading(
                        value = processed.processedValue,
                        buttonPressed = processed.anomalyScore >= 0.78f,
                        latitude = lastLocation?.latitude,
                        longitude = lastLocation?.longitude,
                        altitude = lastLocation?.altitude,
                        rawX = x,
                        rawY = y,
                        rawZ = z,
                        rawMagnitude = processed.rawMagnitude,
                        baseline = processed.baseline,
                        delta = processed.delta,
                        anomalyScore = processed.anomalyScore,
                        confidence = processed.confidence,
                        targetHint = processed.targetHint
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = sensorListener

        processor.reset()
        val registered = manager.registerListener(sensorListener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            onStateChanged(ScanSourceState.ERROR)
            return
        }

        tryStartLocationUpdates()
        onStateChanged(ScanSourceState.ACTIVE)
    }

    private fun tryStartLocationUpdates() {
        val manager = locationManager ?: return
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
            }
            @Deprecated("Deprecated in API 29, kept for older devices")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener

        try {
            val provider = when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                manager.requestLocationUpdates(provider, 1000L, 1f, listener)
                lastLocation = manager.getLastKnownLocation(provider)
            }
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call; scan
            // simply continues without GPS tagging.
        }
    }

    override fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        locationListener?.let {
            try { locationManager?.removeUpdates(it) } catch (_: SecurityException) {}
        }
        locationListener = null
    }
}
