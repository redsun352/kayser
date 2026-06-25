package com.arkeosar.groundscan.data

/**
 * Where a measurement reading came from. Shown in the status bar so the
 * user always knows which source is currently feeding the scan.
 */
enum class ScanSourceType {
    BLUETOOTH_PROBE,
    INTERNAL_SENSOR
}

/**
 * Connection / availability state shared by every [ScanDataSource]
 * implementation (Bluetooth probe, internal sensor, anything added later).
 */
enum class ScanSourceState {
    IDLE, CONNECTING, ACTIVE, UNAVAILABLE, ERROR
}

/**
 * A single measurement from a [ScanDataSource].
 *
 * [value] is the scalar already chosen by the source as "the" reading
 * (e.g. an external probe's single intensity channel, or the internal
 * sensor's XYZ magnitude). When a source can also expose the raw 3-axis
 * components ([rawX], [rawY], [rawZ] - currently only [InternalSensorSource]
 * can, since a magnetometer chip naturally reports three axes), the
 * function picker in Settings can recompute [value] live using a
 * different [DisplayFunction] without needing a new reading from the
 * source. Bluetooth probes that only ever report a single combined
 * channel leave the raw fields null, and the function picker is simply
 * not offered for that source.
 */
data class ScanReading(
    val value: Float,
    val buttonPressed: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val rawX: Float? = null,
    val rawY: Float? = null,
    val rawZ: Float? = null,
    val rawMagnitude: Float? = null,
    val baseline: Float? = null,
    val delta: Float? = null,
    val anomalyScore: Float = 0f,
    val confidence: Float = 0f,
    val targetHint: String? = null
)

/**
 * Common contract for anything that can feed live readings into a scan:
 * an external Bluetooth probe (OKM Rover-class hardware) or the phone's
 * own internal magnetometer. [ScanActivity] talks only to this interface,
 * so it doesn't need to know or care which concrete source is active -
 * that's what makes the automatic Bluetooth -> internal-sensor fallback
 * possible without special-casing the UI layer.
 */
interface ScanDataSource {
    val sourceType: ScanSourceType

    fun start(
        onStateChanged: (ScanSourceState) -> Unit,
        onReading: (ScanReading) -> Unit
    )

    fun stop()
}
