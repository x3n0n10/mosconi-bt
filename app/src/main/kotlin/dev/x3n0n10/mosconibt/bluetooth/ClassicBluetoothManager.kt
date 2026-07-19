package dev.x3n0n10.mosconibt.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 * runtime permissions as a UI concern, not a transport concern. This class deliberately
 * never calls [BluetoothAdapter.cancelDiscovery] or otherwise touches discovery - only
 * `bondedDevices()` and connecting to an already-paired device - since that's a *different*
 * dangerous permission (`BLUETOOTH_SCAN`) this app doesn't declare/request; calling it
 * crashes with a SecurityException instead of merely no-op'ing.
 *
 * There's exactly one RFCOMM socket, so writes (from slider drags) and status reads (from
 * polling) share one input/output stream pair. [ioMutex] serializes every transaction so a
 * status response's bytes can never be misread as a write's ack byte or vice versa.
 */
class ClassicBluetoothManager(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val appContext = context.applicationContext

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private val ioMutex = Mutex()

    /** Bumped on every [connect]/[disconnect] call so a superseded attempt (cancelled,
     *  or overtaken by a newer connect to a different device) can tell it's stale after
     *  its blocking socket call returns/throws, and skip clobbering newer state. */
    private var connectAttemptId = 0

    private val _state = MutableStateFlow<BtConnectionState>(BtConnectionState.Idle)
    val state: StateFlow<BtConnectionState> = _state.asStateFlow()

    val isBluetoothAvailable: Boolean get() = adapter != null
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private val hasConnectPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BtDevice> {
        if (!hasConnectPermission) return emptyList()
        val devices = adapter?.bondedDevices.orEmpty()
        return devices
            .map { BtDevice(name = it.name ?: it.address, address = it.address) }
            .sortedByDescending { it.name.contains("MOSCONI", ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(target: BtDevice) = withContext(Dispatchers.IO) {
        val attemptId = ++connectAttemptId
        closeQuietly() // abandon any previous in-flight/stale socket before starting fresh
        _state.value = BtConnectionState.Connecting(target)
        val currentAdapter = adapter
        val device: BluetoothDevice? = currentAdapter?.bondedDevices?.firstOrNull { it.address == target.address }
        if (currentAdapter == null || device == null) {
            if (attemptId == connectAttemptId) _state.value = BtConnectionState.Failed(target, "Device is no longer paired")
            return@withContext
        }
        try {
            val sock = device.createRfcommSocketToServiceRecord(UUID.fromString(MosconiProtocol.SPP_UUID))
            // Assigned before connect() returns so a concurrent cancel/disconnect can close
            // (and thereby interrupt) this specific socket while it's still blocking.
            socket = sock
            sock.connect()
            if (attemptId != connectAttemptId) {
                // A newer attempt (cancel, or a switch to a different device) already took
                // over while we were blocked in connect() - discard this one quietly.
                runCatching { sock.close() }
                return@withContext
            }
            output = sock.outputStream
            input = sock.inputStream
            _state.value = BtConnectionState.Connected(target)
        } catch (e: IOException) {
            if (attemptId == connectAttemptId) {
                closeQuietly()
                _state.value = BtConnectionState.Failed(target, e.message ?: "Connection failed")
            }
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

    /**
     * Best-effort flush of whatever's currently sitting in the input buffer. Used after
     * a response whose length/framing didn't validate (e.g. a variable-length read
     * whose exact byte count is a best guess - see [dev.x3n0n10.mosconibt.protocol.MosconiProtocol.userDataResponseSize])
     * so leftover bytes from an under-read can't bleed into and desync the next,
     * unrelated request/response.
     */
    suspend fun drainStrayInput(timeoutMs: Long = 200) = withContext(Dispatchers.IO) {
        ioMutex.withLock { readAvailable(maxBytes = 4096, timeoutMs = timeoutMs) }
        Unit
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
        connectAttemptId++ // invalidate any in-flight connect attempt
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
