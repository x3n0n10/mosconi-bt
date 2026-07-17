package dev.x3n0n10.mosconibt

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.x3n0n10.mosconibt.bluetooth.BtConnectionState
import dev.x3n0n10.mosconibt.bluetooth.BtDevice
import dev.x3n0n10.mosconibt.bluetooth.ClassicBluetoothManager
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol.toByteArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ControlUiState(
    val connection: BtConnectionState = BtConnectionState.Idle,
    val pairedDevices: List<BtDevice> = emptyList(),
    val volumeTarget: MosconiProtocol.VolumeTarget = MosconiProtocol.VolumeTarget.OUTPUT,
    val volumeStep: Int = MosconiProtocol.VOLUME_STEPS / 2,
    val subLevel: Int = MosconiProtocol.SUB_STEPS,
    val geoX: Int = MosconiProtocol.GEO_STEPS / 2,
    val geoY: Int = MosconiProtocol.GEO_STEPS / 2,
    val treble: Int = 8,
    val mid: Int = 8,
    val bass: Int = 8,
    val selectedPreset: Int = 0,
    val hapticFeedback: Boolean = true,
) {
    val isConnected: Boolean get() = connection is BtConnectionState.Connected
}

class MosconiViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetooth = ClassicBluetoothManager(application)
    private val prefs = MosconiPrefs(application)
    private val packetState = MosconiProtocol.State()
    private val vibrator: Vibrator? = getVibrator(application)

    private val _ui = MutableStateFlow(
        ControlUiState(
            volumeTarget = prefs.volumeTarget,
            volumeStep = prefs.volumeStep,
            subLevel = prefs.subLevel,
            geoX = prefs.geoX,
            geoY = prefs.geoY,
            treble = prefs.treble,
            mid = prefs.mid,
            bass = prefs.bass,
            selectedPreset = prefs.selectedPreset,
            hapticFeedback = prefs.hapticFeedback,
        ),
    )
    val ui: StateFlow<ControlUiState> = _ui.asStateFlow()

    val isBluetoothAvailable: Boolean get() = bluetooth.isBluetoothAvailable
    val isBluetoothEnabled: Boolean get() = bluetooth.isBluetoothEnabled

    init {
        // Prime the packet-builder with the restored state so the *first* control the user
        // touches sends a frame with correct sibling fields, not just-constructed defaults.
        replayRestoredStateIntoPacketBuilder()

        viewModelScope.launch {
            bluetooth.state.collect { connection ->
                _ui.update { it.copy(connection = connection) }
            }
        }
    }

    fun refreshPairedDevices() {
        _ui.update { it.copy(pairedDevices = bluetooth.bondedDevices()) }
    }

    fun connect(device: BtDevice) {
        prefs.lastDeviceAddress = device.address
        viewModelScope.launch { bluetooth.connect(device) }
    }

    fun disconnect() = bluetooth.disconnect()

    fun onVolumeTargetChange(target: MosconiProtocol.VolumeTarget) {
        prefs.volumeTarget = target
        _ui.update { it.copy(volumeTarget = target) }
        // Re-send the current volume step under the new target so the DSP's other
        // channel doesn't keep stale gain until the slider is next touched.
        onVolumeChange(_ui.value.volumeStep)
    }

    fun onVolumeChange(step: Int) {
        prefs.volumeStep = step
        _ui.update { it.copy(volumeStep = step) }
        send(packetState.setVolume(_ui.value.volumeTarget, step))
        vibrateTick()
    }

    fun onSubChange(level: Int) {
        prefs.subLevel = level
        _ui.update { it.copy(subLevel = level) }
        send(packetState.setSub(level))
        vibrateTick()
    }

    fun onGeoXChange(position: Int) {
        prefs.geoX = position
        _ui.update { it.copy(geoX = position) }
        send(packetState.setGeoX(position))
        vibrateTick()
    }

    fun onGeoYChange(position: Int) {
        prefs.geoY = position
        _ui.update { it.copy(geoY = position) }
        send(packetState.setGeoY(position))
        vibrateTick()
    }

    fun onTrebleChange(level: Int) {
        prefs.treble = level
        _ui.update { it.copy(treble = level) }
        send(packetState.setTreble(level))
        vibrateTick()
    }

    fun onMidChange(level: Int) {
        prefs.mid = level
        _ui.update { it.copy(mid = level) }
        send(packetState.setMid(level))
        vibrateTick()
    }

    fun onBassChange(level: Int) {
        prefs.bass = level
        _ui.update { it.copy(bass = level) }
        send(packetState.setBass(level))
        vibrateTick()
    }

    fun onPresetSelected(index: Int) {
        prefs.selectedPreset = index
        _ui.update { it.copy(selectedPreset = index) }
        send(packetState.selectPreset(index))
        vibrateTick(strong = true)
    }

    fun onHapticFeedbackToggle(enabled: Boolean) {
        prefs.hapticFeedback = enabled
        _ui.update { it.copy(hapticFeedback = enabled) }
    }

    private fun replayRestoredStateIntoPacketBuilder() {
        val s = _ui.value
        packetState.setVolume(MosconiProtocol.VolumeTarget.OUTPUT, if (s.volumeTarget == MosconiProtocol.VolumeTarget.OUTPUT) s.volumeStep else MosconiProtocol.VOLUME_STEPS / 2)
        packetState.setVolume(MosconiProtocol.VolumeTarget.INPUT, if (s.volumeTarget == MosconiProtocol.VolumeTarget.INPUT) s.volumeStep else MosconiProtocol.VOLUME_STEPS / 2)
        packetState.setSub(s.subLevel)
        packetState.setGeoX(s.geoX)
        packetState.setGeoY(s.geoY)
        packetState.selectPreset(s.selectedPreset)
        packetState.setTreble(s.treble)
        packetState.setMid(s.mid)
        packetState.setBass(s.bass)
    }

    private fun send(frame: IntArray) {
        if (!_ui.value.isConnected) return
        viewModelScope.launch { bluetooth.send(frame.toByteArray()) }
    }

    private fun vibrateTick(strong: Boolean = false) {
        if (!_ui.value.hapticFeedback) return
        val v = vibrator ?: return
        val durationMs = if (strong) 25L else 8L
        v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun getVibrator(context: Context): Vibrator? {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        return manager?.defaultVibrator
    }
}
