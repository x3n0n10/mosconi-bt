package dev.x3n0n10.mosconibt

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.x3n0n10.mosconibt.bluetooth.BtConnectionState
import dev.x3n0n10.mosconibt.bluetooth.BtDevice
import dev.x3n0n10.mosconibt.ui.screens.ConnectScreen
import dev.x3n0n10.mosconibt.ui.screens.ControlScreen
import dev.x3n0n10.mosconibt.ui.screens.SettingsScreen
import dev.x3n0n10.mosconibt.ui.theme.MosconiTheme
import org.junit.Rule
import org.junit.Test

/**
 * One-off screenshot generation, not wired into CI. Run with:
 *   ./gradlew :app:recordPaparazziDebug
 * Images land in app/src/test/snapshots/images.
 */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private val sampleDevices = listOf(
        BtDevice(name = "MOSCONI PICO 6|8", address = "AA:BB:CC:11:22:33"),
        BtDevice(name = "Pixel Buds Pro", address = "AA:BB:CC:44:55:66"),
    )

    private val sampleControlState = ControlUiState(
        connection = BtConnectionState.Connected(sampleDevices[0]),
        outputVolumeStep = 24,
        subLevel = 11,
        balance = 18,
        fader = 20,
        treble = 9,
        mid = 7,
        bass = 10,
        selectedPreset = 1,
        hapticFeedback = true,
        presetNames = listOf("Daily", "Highway", null, "Bass Boost"),
        // Paparazzi's own rendering pipeline takes several real seconds, which would
        // otherwise make this sample look "stale" by the time the pixels are captured -
        // bias into the future so screenshots show the steady-state "synced" look.
        lastSyncedAtMillis = System.currentTimeMillis() + 60_000L,
    )

    @Test
    fun connectScreen_withPairedDevices() {
        paparazzi.snapshot {
            AppRoot {
                ConnectScreen(
                    devices = sampleDevices,
                    connectionState = BtConnectionState.Idle,
                    isAutoConnecting = false,
                    hasBluetoothPermission = true,
                    bluetoothAvailable = true,
                    bluetoothEnabled = true,
                    onRequestPermission = {},
                    onRefresh = {},
                    onConnect = {},
                    onCancelAutoConnect = {},
                )
            }
        }
    }

    @Test
    fun connectScreen_permissionNeeded() {
        paparazzi.snapshot {
            AppRoot {
                ConnectScreen(
                    devices = emptyList(),
                    connectionState = BtConnectionState.Idle,
                    isAutoConnecting = false,
                    hasBluetoothPermission = false,
                    bluetoothAvailable = true,
                    bluetoothEnabled = true,
                    onRequestPermission = {},
                    onRefresh = {},
                    onConnect = {},
                    onCancelAutoConnect = {},
                )
            }
        }
    }

    @Test
    fun connectScreen_autoConnecting() {
        paparazzi.snapshot {
            AppRoot {
                ConnectScreen(
                    devices = sampleDevices,
                    connectionState = BtConnectionState.Connecting(sampleDevices[0]),
                    isAutoConnecting = true,
                    hasBluetoothPermission = true,
                    bluetoothAvailable = true,
                    bluetoothEnabled = true,
                    onRequestPermission = {},
                    onRefresh = {},
                    onConnect = {},
                    onCancelAutoConnect = {},
                )
            }
        }
    }

    @Test
    fun controlScreen_lightTheme() {
        paparazzi.snapshot {
            AppRoot(darkTheme = false) {
                ControlScreen(
                    state = sampleControlState,
                    onOutputVolumeChange = {},
                    onSubChange = {},
                    onBalanceChange = {},
                    onFaderChange = {},
                    onTrebleChange = {},
                    onMidChange = {},
                    onBassChange = {},
                    onPresetSelected = {},
                )
            }
        }
    }

    @Test
    fun controlScreen_darkTheme() {
        paparazzi.snapshot {
            AppRoot(darkTheme = true) {
                ControlScreen(
                    state = sampleControlState,
                    onOutputVolumeChange = {},
                    onSubChange = {},
                    onBalanceChange = {},
                    onFaderChange = {},
                    onTrebleChange = {},
                    onMidChange = {},
                    onBassChange = {},
                    onPresetSelected = {},
                )
            }
        }
    }

    @Test
    fun controlScreen_notYetSynced_controlsAreDisabled() {
        paparazzi.snapshot {
            AppRoot {
                ControlScreen(
                    // lastSyncedAtMillis defaults to null: connected, but nothing read yet.
                    state = sampleControlState.copy(lastSyncedAtMillis = null),
                    onOutputVolumeChange = {},
                    onSubChange = {},
                    onBalanceChange = {},
                    onFaderChange = {},
                    onTrebleChange = {},
                    onMidChange = {},
                    onBassChange = {},
                    onPresetSelected = {},
                )
            }
        }
    }

    @Test
    fun controlScreen_withDisabledPreset_isGreyedOut() {
        paparazzi.snapshot {
            AppRoot {
                ControlScreen(
                    state = sampleControlState.copy(presetsEnabled = listOf(true, true, false, true)),
                    onOutputVolumeChange = {},
                    onSubChange = {},
                    onBalanceChange = {},
                    onFaderChange = {},
                    onTrebleChange = {},
                    onMidChange = {},
                    onBassChange = {},
                    onPresetSelected = {},
                )
            }
        }
    }

    @Test
    fun settingsScreen_withRememberedDevice() {
        paparazzi.snapshot {
            AppRoot {
                SettingsScreen(
                    hapticFeedback = true,
                    themeMode = ThemeMode.SYSTEM,
                    rememberedDeviceLabel = "MOSCONI PICO 6|8",
                    presetNames = listOf("Daily", "Highway", null, "Bass Boost"),
                    presetsEnabled = listOf(true, true, false, true),
                    activePreset = 1,
                    onHapticToggle = {},
                    onThemeModeChange = {},
                    onForgetDevice = {},
                    onPresetEnabledChange = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun settingsScreen_noRememberedDevice() {
        paparazzi.snapshot {
            AppRoot {
                SettingsScreen(
                    hapticFeedback = false,
                    themeMode = ThemeMode.DARK,
                    rememberedDeviceLabel = null,
                    presetNames = listOf(null, null, null, null),
                    presetsEnabled = listOf(true, true, true, true),
                    activePreset = 0,
                    onHapticToggle = {},
                    onThemeModeChange = {},
                    onForgetDevice = {},
                    onPresetEnabledChange = { _, _ -> },
                )
            }
        }
    }
}

/** A separate rule instance with a wide/short "car head unit"-style screen. */
class ScreenshotTestWideScreen {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1920, screenHeight = 720),
    )

    @Test
    fun controlScreen_ultraWideScreen_widthIsCapped() {
        paparazzi.snapshot {
            AppRoot {
                ControlScreen(
                    state = ControlUiState(
                        connection = BtConnectionState.Connected(BtDevice("MOSCONI PICO 6|8", "AA:BB:CC:11:22:33")),
                        outputVolumeStep = 24,
                        subLevel = 11,
                        balance = 18,
                        fader = 20,
                        treble = 9,
                        mid = 7,
                        bass = 10,
                        selectedPreset = 1,
                        lastSyncedAtMillis = System.currentTimeMillis() + 60_000L, // see comment above
                    ),
                    onOutputVolumeChange = {},
                    onSubChange = {},
                    onBalanceChange = {},
                    onFaderChange = {},
                    onTrebleChange = {},
                    onMidChange = {},
                    onBassChange = {},
                    onPresetSelected = {},
                )
            }
        }
    }

    @Test
    fun connectScreen_ultraWideScreen_widthIsCapped() {
        paparazzi.snapshot {
            AppRoot {
                ConnectScreen(
                    devices = listOf(
                        BtDevice(name = "MOSCONI PICO 6|8", address = "AA:BB:CC:11:22:33"),
                        BtDevice(name = "Pixel Buds Pro", address = "AA:BB:CC:44:55:66"),
                    ),
                    connectionState = BtConnectionState.Idle,
                    isAutoConnecting = false,
                    hasBluetoothPermission = true,
                    bluetoothAvailable = true,
                    bluetoothEnabled = true,
                    onRequestPermission = {},
                    onRefresh = {},
                    onConnect = {},
                    onCancelAutoConnect = {},
                )
            }
        }
    }
}

/** Mirrors MainActivity's root composition (theme + background-painting Surface). */
@Composable
private fun AppRoot(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MosconiTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
