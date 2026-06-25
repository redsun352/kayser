package com.arkeosar.groundscan.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.arkeosar.groundscan.data.ScanDataSource
import com.arkeosar.groundscan.data.ScanReading
import com.arkeosar.groundscan.data.ScanSourceState
import com.arkeosar.groundscan.data.ScanSourceType

/**
 * Adapts [BluetoothScanService] to the common [ScanDataSource] interface
 * so [com.arkeosar.groundscan.ui.ScanActivity] can treat the Bluetooth
 * probe and the phone's internal sensor interchangeably.
 *
 * If no bonded probe is found, or the connection fails/drops, this
 * reports [ScanSourceState.UNAVAILABLE] / [ScanSourceState.ERROR] so the
 * caller can fall back to another source - it does not retry forever on
 * its own.
 */
class BluetoothDataSource(context: Context) : ScanDataSource {

    override val sourceType: ScanSourceType = ScanSourceType.BLUETOOTH_PROBE

    private val service = BluetoothScanService(context)

    override fun start(
        onStateChanged: (ScanSourceState) -> Unit,
        onReading: (ScanReading) -> Unit
    ) {
        val candidates = service.findCandidateDevices()
        val device: BluetoothDevice? = candidates.firstOrNull()
        if (device == null) {
            onStateChanged(ScanSourceState.UNAVAILABLE)
            return
        }

        service.onStateChanged = { state ->
            onStateChanged(
                when (state) {
                    ScanLinkState.CONNECTING -> ScanSourceState.CONNECTING
                    ScanLinkState.CONNECTED -> ScanSourceState.ACTIVE
                    ScanLinkState.DISCONNECTED -> ScanSourceState.IDLE
                    ScanLinkState.ERROR -> ScanSourceState.ERROR
                }
            )
        }
        service.onReading = { reading ->
            onReading(ScanReading(value = reading.value, buttonPressed = reading.buttonPressed))
        }

        service.connect(device)
    }

    override fun stop() {
        service.disconnect()
    }
}
