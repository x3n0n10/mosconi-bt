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
    /** True while [connection] is [BtConnectionState.Connecting] as a result of
     *  auto-connecting to the last-used device, as opposed to a device the user just
     *  tapped - lets the UI offer a way to cancel it, unlike a manual connect attempt. */
    val isAutoConnecting: Boolean = false,
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
    /** Custom preset names as set in the Windows tuning GUI (read-only here - this app
     *  never writes them). A null slot means no custom name is set on the device, or it
     *  hasn't been read yet; the UI should fall back to a generic "P<n>" label. */
    val presetNames: List<String?> = List(MosconiProtocol.PRESET_COUNT) { null },
    /** Wall-clock time of the last successfully parsed status read, or null if never. */
    val lastSyncedAtMillis: Long? = null,
    /** Wall-clock time of the last local slider/button edit, or null if none this session. */
    val lastLocalEditAtMillis: Long? = null,
) {
    val isConnected: Boolean get() = connection is BtConnectionState.Connected

    /** Controls stay disabled until we know the DSP's real values - otherwise editing
     *  would start from a guess (local prefs, or hardcoded defaults) and could silently
     *  overwrite whatever the device actually had. */
    val controlsReady: Boolean get() = lastSyncedAtMillis != null
}

class MosconiViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetooth = ClassicBluetoothManager(application)
    private val prefs = MosconiPrefs(application)
    private val packetState = MosconiProtocol.State()
    private val vibrator: Vibrator? = getVibrator(application)

    private var pollJob: Job? = null
    private var lastStatusByte: Int = 0
    private var presetNameFetchJob: Job? = null

    private var connectJob: Job? = null

    /** Auto-connect is attempted at most once per foreground session (see
     *  [onAppForegrounded]) - once the user cancels it, or connects/switches manually,
     *  it should stay out of the way until the app is reopened, not keep retrying. */
    private var autoConnectSuppressed = false

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
                _ui.update {
                    it.copy(
                        connection = connection,
                        // Only meaningful while still connecting; anything else (idle,
                        // connected, failed) means this attempt is resolved.
                        isAutoConnecting = connection is BtConnectionState.Connecting && it.isAutoConnecting,
                    )
                }
                if (connection is BtConnectionState.Connected) {
                    startPolling()
                } else {
                    stopPolling()
                }
            }
        }
    }

    /**
     * Mirrors the factory app: try to reconnect to whatever device was last used,
     * without making the user pick it again every time. Runs at most once per
     * foreground session and only when nothing else is already connecting/connected,
     * so it never fights a manual pick or a cancel. Safe to call before Bluetooth
     * permission is granted - [ClassicBluetoothManager.bondedDevices] just returns
     * nothing in that case, so this quietly no-ops rather than crashing.
     */
    fun maybeAutoConnect() {
        if (autoConnectSuppressed) return
        if (_ui.value.connection !is BtConnectionState.Idle) return
        val lastAddress = prefs.lastDeviceAddress ?: return
        val device = bluetooth.bondedDevices().firstOrNull { it.address == lastAddress } ?: return
        connect(device, automatic = true)
    }

    /** Cancels a pending auto-connect and returns to the device picker, untouched. */
    fun cancelAutoConnect() {
        connectJob?.cancel()
        bluetooth.disconnect()
        _ui.update { it.copy(isAutoConnecting = false) }
    }

    /** Call when the app becomes visible again: re-arms auto-connect for this session
     *  and retries it immediately (covers both "just launched" and "switched back from
     *  another app"). */
    fun onAppForegrounded() {
        autoConnectSuppressed = false
        refreshPairedDevices()
        maybeAutoConnect()
    }

    /** Call when the app is no longer visible: tear down the serial session (and the
     *  polling that rides on it) rather than holding the DSP's Bluetooth module busy
     *  and draining battery for a screen nobody's looking at. */
    fun onAppBackgrounded() {
        disconnect()
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
        presetNameFetchJob?.cancel()
        presetNameFetchJob = viewModelScope.launch { fetchPresetNames() }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        presetNameFetchJob?.cancel()
        presetNameFetchJob = null
    }

    /**
     * One-shot (not repeated per poll cycle) read of the 4 custom preset names via the
     * DSP's bulk "USERDATA" memory - see [MosconiProtocol.buildUserDataRequest]. Purely
     * cosmetic and best-effort: this app never writes these, and if the read times out
     * or fails to validate (see the ASSUMPTION notes on
     * [MosconiProtocol.userDataResponseSize]), the UI just keeps showing the generic
     * "P<n>" labels it already falls back to - never garbled/misaligned data, and never
     * something the user needs to react to.
     */
    private suspend fun fetchPresetNames() {
        val request = MosconiProtocol.buildUserDataRequest(
            address = MosconiProtocol.PRESET_NAME_ADDRESS,
            count = MosconiProtocol.PRESET_NAME_COUNT,
            lastStatusByte = lastStatusByte,
        )
        val response = bluetooth.sendAndReceive(
            request.toByteArray(),
            responseSize = MosconiProtocol.userDataResponseSize(MosconiProtocol.PRESET_NAME_COUNT),
        )
        if (response == null) {
            bluetooth.drainStrayInput()
            return
        }
        val payload = MosconiProtocol.parseUserDataResponse(
            response.map { it.toInt() and 0xFF }.toIntArray(),
            address = MosconiProtocol.PRESET_NAME_ADDRESS,
            count = MosconiProtocol.PRESET_NAME_COUNT,
        )
        if (payload == null) {
            bluetooth.drainStrayInput()
            return
        }
        _ui.update { it.copy(presetNames = MosconiProtocol.parsePresetNames(payload)) }
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
     * it doesn't touch, not stale ones). Skipped entirely for [PAUSE_READS_AFTER_EDIT_MS]
     * after any local edit - long enough to cover a whole drag gesture (every intermediate
     * position re-triggers this window, so it keeps extending for as long as a finger is
     * moving a slider) plus a settling margin, so an in-flight or newly-arriving poll
     * response can't yank a slider back to the pre-edit value while - or right after - the
     * user is choosing where they want it.
     */
    private fun applyStatusResponse(status: MosconiProtocol.StatusResponse) {
        val lastEdit = _ui.value.lastLocalEditAtMillis
        if (lastEdit != null && System.currentTimeMillis() - lastEdit < PAUSE_READS_AFTER_EDIT_MS) return

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

    fun connect(device: BtDevice, automatic: Boolean = false) {
        // Any explicit attempt - auto or manual - claims this foreground session; a
        // manual pick in particular must never be overridden by a stale auto-retry.
        autoConnectSuppressed = true
        connectJob?.cancel()
        prefs.lastDeviceAddress = device.address
        // None of these are valid for a new connection: nothing's been synced yet, any
        // earlier edit belonged to a previous session's (possibly different) device,
        // and a different device may have entirely different (or no) custom names.
        _ui.update {
            it.copy(
                lastSyncedAtMillis = null,
                lastLocalEditAtMillis = null,
                isAutoConnecting = automatic,
                presetNames = List(MosconiProtocol.PRESET_COUNT) { null },
            )
        }
        connectJob = viewModelScope.launch { bluetooth.connect(device) }
    }

    fun disconnect() {
        connectJob?.cancel()
        stopPolling()
        bluetooth.disconnect()
        _ui.update {
            it.copy(
                lastSyncedAtMillis = null,
                lastLocalEditAtMillis = null,
                isAutoConnecting = false,
                presetNames = List(MosconiProtocol.PRESET_COUNT) { null },
            )
        }
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
        _ui.update { it.copy(lastLocalEditAtMillis = System.currentTimeMillis()) }
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
        /** How long poll-derived updates are suppressed after a local edit. Deliberately a
         *  few seconds, not a few hundred milliseconds: this needs to comfortably outlast
         *  the DSP's own processing + our poll's round trip, or a read could land in the
         *  gap and revert the very value the user just set. */
        const val PAUSE_READS_AFTER_EDIT_MS = 3000L
    }
}
