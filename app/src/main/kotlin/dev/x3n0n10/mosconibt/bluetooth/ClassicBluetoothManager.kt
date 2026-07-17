package dev.x3n0n10.mosconibt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A paired device, trimmed down to what the UI actually needs. */
data class BtDevice(val name: String, val address: String)

sealed interface BtConnectionState {
    data object Idle : BtConnectionState
    data class Connecting(val device: BtDevice) : BtConnectionState
    data class Connected(val device: BtDevice) : BtConnectionState
    data class Failed(val device: BtDevice?, val message: String) : BtConnectionState
}

/**
 * Talks classic Bluetooth RFCOMM/SPP to a MOSCONI DSP - the same transport the factory
 * app uses (this hardware is not BLE). All blocking socket I/O runs on [Dispatchers.IO].
 *
 * Every call that touches the adapter or a [BluetoothDevice]'s identifying info requires
 * `BLUETOOTH_CONNECT` (Android 12+/API 31+) to already be granted; callers are expected to
 * have checked that via the UI layer first, matching how the rest of this codebase treats
 * runtime permissions as a UI concern, not a transport concern.
 *
 * There's exactly one RFCOMM socket, so writes (from slider drags) and status reads (from
 * polling) share one input/output stream pair. [ioMutex] serializes every transaction so a
 * status response's bytes can never be misread as a write's ack byte or vice versa.
 */
class ClassicBluetoothManager(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: android.bluetooth.BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private val ioMutex = Mutex()

    private val _state = MutableStateFlow<BtConnectionState>(BtConnectionState.Idle)
    val state: StateFlow<BtConnectionState> = _state.asStateFlow()

    val isBluetoothAvailable: Boolean get() = adapter != null
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BtDevice> {
        val devices = adapter?.bondedDevices.orEmpty()
        return devices
            .map { BtDevice(name = it.name ?: it.address, address = it.address) }
            .sortedByDescending { it.name.contains("MOSCONI", ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(target: BtDevice) = withContext(Dispatchers.IO) {
        _state.value = BtConnectionState.Connecting(target)
        val currentAdapter = adapter
        val device: BluetoothDevice? = currentAdapter?.bondedDevices?.firstOrNull { it.address == target.address }
        if (currentAdapter == null || device == null) {
            _state.value = BtConnectionState.Failed(target, "Device is no longer paired")
            return@withContext
        }
        try {
            currentAdapter.cancelDiscovery()
            val sock = device.createRfcommSocketToServiceRecord(UUID.fromString(MosconiProtocol.SPP_UUID))
            sock.connect()
            socket = sock
            output = sock.outputStream
            input = sock.inputStream
            _state.value = BtConnectionState.Connected(target)
        } catch (e: IOException) {
            closeQuietly()
            _state.value = BtConnectionState.Failed(target, e.message ?: "Connection failed")
        }
    }

    /**
     * Sends one 8-byte write-command frame and, matching the factory app's behaviour, makes
     * a best-effort attempt to drain the single ack byte the DSP replies with. The ack's
     * content is never validated - the original app didn't either - it's just cleared so
     * it doesn't pile up in the receive buffer.
     */
    suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val out = output ?: return@withLock
            try {
                out.write(frame)
                out.flush()
                readAvailable(maxBytes = 1, timeoutMs = 50)
            } catch (e: IOException) {
                failConnection(e.message ?: "Write failed")
            }
        }
    }

    /**
     * Sends a frame and blocks (with [timeoutMs]) for exactly [responseSize] bytes back -
     * used for status-query frames, which get a structured, checksummed reply rather than
     * a single ack byte. Returns null on timeout, a closed connection, or any I/O error.
     */
    suspend fun sendAndReceive(frame: ByteArray, responseSize: Int, timeoutMs: Long = 500): ByteArray? =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val out = output ?: return@withLock null
                try {
                    out.write(frame)
                    out.flush()
                    readExact(responseSize, timeoutMs)
                } catch (e: IOException) {
                    failConnection(e.message ?: "Read failed")
                    null
                }
            }
        }

    /** Polls (non-blocking, coroutine-friendly) until [maxBytes] bytes arrive or [timeoutMs] elapses. */
    private suspend fun readAvailable(maxBytes: Int, timeoutMs: Long): ByteArray? =
        readUpTo(maxBytes, timeoutMs, exact = false)

    /** Polls until exactly [length] bytes arrive, or returns null if [timeoutMs] elapses first. */
    private suspend fun readExact(length: Int, timeoutMs: Long): ByteArray? =
        readUpTo(length, timeoutMs, exact = true)

    private suspend fun readUpTo(length: Int, timeoutMs: Long, exact: Boolean): ByteArray? {
        val inp = input ?: return null
        val buffer = ByteArray(length)
        var filled = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (filled < length) {
            if (System.currentTimeMillis() > deadline) {
                return if (exact) null else buffer.copyOf(filled).takeIf { filled > 0 }
            }
            val avail = inp.available()
            if (avail > 0) {
                val n = inp.read(buffer, filled, minOf(avail, length - filled))
                if (n < 0) return null // stream closed
                filled += n
            } else {
                delay(10)
            }
        }
        return buffer
    }

    private fun failConnection(message: String) {
        val failedDevice = (_state.value as? BtConnectionState.Connected)?.device
        closeQuietly()
        _state.value = BtConnectionState.Failed(failedDevice, message)
    }

    fun disconnect() {
        closeQuietly()
        _state.value = BtConnectionState.Idle
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
