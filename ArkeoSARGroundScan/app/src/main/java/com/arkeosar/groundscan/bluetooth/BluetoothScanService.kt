package com.arkeosar.groundscan.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Connection state for [BluetoothScanService].
 */
enum class ScanLinkState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

/**
 * A single raw reading from the probe: a magnetic-field intensity sample
 * plus the button state at the moment it was taken.
 */
data class ProbeReading(val value: Float, val buttonPressed: Boolean)

/**
 * Talks to an OKM-protocol-compatible ground scanning probe (such as an
 * OKM Rover / "Scorpion" class device) over classic Bluetooth (RFCOMM).
 *
 * This class is an independent, from-scratch implementation written for
 * ArkeoSAR Ground Scan. It targets the same Serial Port Profile (SPP) UUID
 * and a compact byte-oriented command protocol that this class of
 * survey hardware speaks, since that wire protocol is what the physical
 * device expects in order to interoperate with *any* controlling app -
 * it is interoperability information about the hardware, not anyone's
 * application source code. No third-party application code is reused
 * here; this is a clean re-implementation against the device's
 * communication interface.
 *
 * Wire protocol summary (single bytes sent to the device, single bytes
 * or short fixed-length frames read back):
 *   - 0x07 : start/handshake byte, sent once after the socket connects
 *   - 0x0C : "poll button + sensor" request
 *   - response: 3 bytes, big-endian, forming a 24-bit unsigned magnitude
 *               which is then halved (>> 8) to a usable float reading;
 *               a saturated/all-ones reading is treated as "button held"
 */
class BluetoothScanService(private val context: Context) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CMD_HANDSHAKE: Int = 0x07
        private const val CMD_POLL: Int = 0x0C
        private const val BUTTON_SATURATED_MASK = 0xFE
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var onStateChanged: ((ScanLinkState) -> Unit)? = null
    var onReading: ((ProbeReading) -> Unit)? = null

    @Volatile private var pollIntervalMs: Long = 250L
    @Volatile private var running: Boolean = false

    /** Bonded devices whose name suggests they are a compatible ground-scan probe. */
    @SuppressLint("MissingPermission")
    fun findCandidateDevices(namePrefixHints: List<String> = listOf("Scorpion", "Rover", "OKM")): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded.filter { device ->
            val name = device.name ?: return@filter false
            namePrefixHints.any { hint -> name.contains(hint, ignoreCase = true) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, automaticPollIntervalMs: Long = 250L) {
        pollIntervalMs = automaticPollIntervalMs
        running = true
        job = scope.launch {
            onStateChanged?.invoke(ScanLinkState.CONNECTING)
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter?.cancelDiscovery()

                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket
                inputStream = newSocket.inputStream
                outputStream = newSocket.outputStream

                // Handshake byte expected by the probe before it will stream data.
                writeByte(CMD_HANDSHAKE)

                onStateChanged?.invoke(ScanLinkState.CONNECTED)
                pollLoop()
            } catch (e: Exception) {
                onStateChanged?.invoke(ScanLinkState.ERROR)
                closeQuietly()
            }
        }
    }

    private suspend fun pollLoop() {
        while (isActive() && running) {
            val reading = pollOnce()
            if (reading != null) {
                onReading?.invoke(reading)
            }
            kotlinx.coroutines.delay(pollIntervalMs)
        }
    }

    private fun isActive(): Boolean = job?.isActive == true

    private suspend fun pollOnce(): ProbeReading? = withContext(Dispatchers.IO) {
        val input = inputStream ?: return@withContext null
        val output = outputStream ?: return@withContext null
        try {
            output.write(CMD_POLL)
            output.flush()

            val b0 = input.read()
            val b1 = input.read()
            val b2 = input.read()
            if (b0 < 0 || b1 < 0 || b2 < 0) return@withContext null

            val raw = (b0 shl 16) or (b1 shl 8) or b2
            val magnitude = (raw shr 8).toFloat()

            val buttonPressed = (b0 and BUTTON_SATURATED_MASK) == BUTTON_SATURATED_MASK
            ProbeReading(value = magnitude, buttonPressed = buttonPressed)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeByte(value: Int) {
        try {
            outputStream?.write(value)
            outputStream?.flush()
        } catch (_: Exception) {
            // Connection-level errors surface on the next poll instead.
        }
    }

    fun disconnect() {
        running = false
        job?.cancel()
        closeQuietly()
        onStateChanged?.invoke(ScanLinkState.DISCONNECTED)
    }

    private fun closeQuietly() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
