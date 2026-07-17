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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class ClassicBluetoothManager(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: android.bluetooth.BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

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
     * Sends one 8-byte command frame and, matching the factory app's behaviour, makes a
     * best-effort attempt to drain the single ack byte the DSP replies with. The ack's
     * content is never validated - the original app didn't either - it's just cleared so
     * it doesn't pile up in the receive buffer.
     */
    suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: return@withContext
        try {
            out.write(frame)
            out.flush()
            drainAck()
        } catch (e: IOException) {
            val failedDevice = (_state.value as? BtConnectionState.Connected)?.device
            closeQuietly()
            _state.value = BtConnectionState.Failed(failedDevice, e.message ?: "Write failed")
        }
    }

    private fun drainAck() {
        try {
            val inp = input ?: return
            if (inp.available() > 0) inp.read()
        } catch (_: IOException) {
            // Best-effort only; a failed read here doesn't invalidate the write that just succeeded.
        }
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
