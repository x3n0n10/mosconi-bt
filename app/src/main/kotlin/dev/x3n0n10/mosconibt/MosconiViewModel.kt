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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ControlUiState(
    val connection: BtConnectionState = BtConnectionState.Idle,
    val pairedDevices: List<BtDevice> = emptyList(),
    val volumeTarget: MosconiProtocol.VolumeTarget = MosconiProtocol.VolumeTarget.OUTPUT,
    val volumeStep: Int = MosconiProtocol.VOLUME_STEPS / 2,
    val subLevel: Int = MosconiProtocol.SUB_STEPS,
    val balance: Int = MosconiProtocol.BALANCE_FADER_STEPS / 2,
    val fader: Int = MosconiProtocol.BALANCE_FADER_STEPS / 2,
    val treble: Int = 8,
    val mid: Int = 8,
    val bass: Int = 8,
    val selectedPreset: Int = 0,
    val hapticFeedback: Boolean = true,
    /** Wall-clock time of the last successfully parsed status read, or null if never. */
    val lastSyncedAtMillis: Long? = null,
) {
    val isConnected: Boolean get() = connection is BtConnectionState.Connected
}

class MosconiViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetooth = ClassicBluetoothManager(application)
    private val prefs = MosconiPrefs(application)
    private val packetState = MosconiProtocol.State()
    private val vibrator: Vibrator? = getVibrator(application)

    private var pollJob: Job? = null
    private var lastStatusByte: Int = 0

    /** Wall-clock time of the last local slider/button edit; polls briefly defer to it. */
    private var lastLocalWriteAtMillis: Long = 0L

    private val _ui = MutableStateFlow(
        ControlUiState(
            volumeTarget = prefs.volumeTarget,
            volumeStep = prefs.volumeStep,
            subLevel = prefs.subLevel,
            balance = prefs.balance,
            fader = prefs.fader,
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
                if (connection is BtConnectionState.Connected) {
                    startPolling()
                } else {
                    stopPolling()
                }
            }
        }
    }

    /**
     * Repeatedly issues [MosconiProtocol.buildStatusRequest] so the app reflects changes
     * made by the physical knob (or another controller) instead of only ever showing
     * whatever was last written from this app. The DSP's status reply only ever carries
     * *one* of the two "pages" of secondary controls per response (see
     * [MosconiProtocol.InformationPage]), so a full picture requires a couple of polls -
     * this loop just keeps going every second for as long as we're connected.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive && _ui.value.isConnected) {
                pollStatusOnce()
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollStatusOnce() {
        val request = MosconiProtocol.buildStatusRequest(lastStatusByte)
        val response = bluetooth.sendAndReceive(
            request.toByteArray(),
            responseSize = MosconiProtocol.STATUS_RESPONSE_SIZE,
        ) ?: return
        val status = MosconiProtocol.parseStatusResponse(response.map { it.toInt() and 0xFF }.toIntArray()) ?: return
        lastStatusByte = status.statusByte
        applyStatusResponse(status)
    }

    /**
     * Merges a live [MosconiProtocol.StatusResponse] into UI state and the write-side
     * [packetState] buffers (so the *next* local edit resends real values for the fields
     * it doesn't touch, not stale ones). Skipped entirely for a short window after any
     * local edit so an in-flight poll response can't yank a slider back mid-drag.
     */
    private fun applyStatusResponse(status: MosconiProtocol.StatusResponse) {
        if (System.currentTimeMillis() - lastLocalWriteAtMillis < RECENT_LOCAL_WRITE_WINDOW_MS) return

        val volumeControls = status.page as? MosconiProtocol.InformationPage.VolumeControls
        val tone = status.page as? MosconiProtocol.InformationPage.Tone

        packetState.setVolume(_ui.value.volumeTarget, status.volumeStep)
        packetState.selectPreset(status.preset)
        volumeControls?.let {
            packetState.setBalance(it.balance)
            packetState.setFader(it.fader)
            packetState.setSub(it.sub)
        }
        tone?.let {
            packetState.setBass(it.bass)
            packetState.setMid(it.mid)
            packetState.setTreble(it.treble)
        }

        _ui.update { current ->
            current.copy(
                volumeStep = status.volumeStep,
                selectedPreset = status.preset,
                balance = volumeControls?.balance ?: current.balance,
                fader = volumeControls?.fader ?: current.fader,
                subLevel = volumeControls?.sub ?: current.subLevel,
                bass = tone?.bass ?: current.bass,
                mid = tone?.mid ?: current.mid,
                treble = tone?.treble ?: current.treble,
                lastSyncedAtMillis = System.currentTimeMillis(),
            )
        }
        // Persist so a relaunch starts from the DSP's real state, not a stale local guess.
        with(prefs) {
            volumeStep = status.volumeStep
            selectedPreset = status.preset
            volumeControls?.let {
                balance = it.balance
                fader = it.fader
                subLevel = it.sub
            }
            tone?.let {
                bass = it.bass
                mid = it.mid
                treble = it.treble
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

    fun disconnect() {
        stopPolling()
        bluetooth.disconnect()
    }

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

    fun onBalanceChange(position: Int) {
        prefs.balance = position
        _ui.update { it.copy(balance = position) }
        send(packetState.setBalance(position))
        vibrateTick()
    }

    fun onFaderChange(position: Int) {
        prefs.fader = position
        _ui.update { it.copy(fader = position) }
        send(packetState.setFader(position))
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
        packetState.setBalance(s.balance)
        packetState.setFader(s.fader)
        packetState.selectPreset(s.selectedPreset)
        packetState.setTreble(s.treble)
        packetState.setMid(s.mid)
        packetState.setBass(s.bass)
    }

    private fun send(frame: IntArray) {
        if (!_ui.value.isConnected) return
        lastLocalWriteAtMillis = System.currentTimeMillis()
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

    private companion object {
        /** How long a poll-derived update is suppressed after a local edit, to avoid
         *  a slow-to-arrive status response yanking a slider mid-drag. */
        const val RECENT_LOCAL_WRITE_WINDOW_MS = 800L
    }
}
