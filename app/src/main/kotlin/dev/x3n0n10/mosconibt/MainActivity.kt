package dev.x3n0n10.mosconibt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.x3n0n10.mosconibt.bluetooth.BtConnectionState
import dev.x3n0n10.mosconibt.ui.screens.ConnectScreen
import dev.x3n0n10.mosconibt.ui.screens.ControlScreen
import dev.x3n0n10.mosconibt.ui.screens.SettingsScreen
import dev.x3n0n10.mosconibt.ui.theme.MosconiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MosconiViewModel = viewModel()
            val ui by viewModel.ui.collectAsStateWithLifecycle()
            val darkTheme = when (ui.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MosconiTheme(darkTheme = darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MosconiApp(viewModel = viewModel, ui = ui)
                }
            }
        }
    }
}

private fun hasBluetoothConnectPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

private enum class AppScreen { Main, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosconiApp(viewModel: MosconiViewModel, ui: ControlUiState) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasBluetoothConnectPermission(context)) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.Main) }
    BackHandler(enabled = screen == AppScreen.Settings) { screen = AppScreen.Main }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            viewModel.refreshPairedDevices()
            viewModel.maybeAutoConnect()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.refreshPairedDevices()
            viewModel.maybeAutoConnect()
        }
    }

    // Tear down the serial session (and its polling) the moment the app isn't visible,
    // and re-attempt auto-connect to the last device each time it comes back - matching
    // the factory app rather than holding the DSP's Bluetooth module open in the
    // background for a screen nobody's looking at.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (screen == AppScreen.Settings) "Settings" else topBarTitle(ui.connection)) },
                navigationIcon = {
                    if (screen == AppScreen.Settings) {
                        IconButton(onClick = { screen = AppScreen.Main }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (screen == AppScreen.Main) {
                        IconButton(onClick = { screen = AppScreen.Settings }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_settings), contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                screen == AppScreen.Settings -> {
                    val rememberedDeviceLabel = ui.rememberedDeviceAddress?.let { address ->
                        ui.pairedDevices.firstOrNull { it.address == address }?.name ?: address
                    }
                    SettingsScreen(
                        hapticFeedback = ui.hapticFeedback,
                        themeMode = ui.themeMode,
                        rememberedDeviceLabel = rememberedDeviceLabel,
                        presetNames = ui.presetNames,
                        presetsEnabled = ui.presetsEnabled,
                        activePreset = ui.selectedPreset,
                        onHapticToggle = viewModel::onHapticFeedbackToggle,
                        onThemeModeChange = viewModel::onThemeModeChange,
                        onForgetDevice = { viewModel.forgetDevice() },
                        onPresetEnabledChange = viewModel::onPresetEnabledChange,
                    )
                }
                ui.isConnected -> ControlScreen(
                    state = ui,
                    onVolumeChange = viewModel::onVolumeChange,
                    onVolumeTargetChange = viewModel::onVolumeTargetChange,
                    onSubChange = viewModel::onSubChange,
                    onBalanceChange = viewModel::onBalanceChange,
                    onFaderChange = viewModel::onFaderChange,
                    onTrebleChange = viewModel::onTrebleChange,
                    onMidChange = viewModel::onMidChange,
                    onBassChange = viewModel::onBassChange,
                    onPresetSelected = viewModel::onPresetSelected,
                )
                else -> ConnectScreen(
                    devices = ui.pairedDevices,
                    connectionState = ui.connection,
                    isAutoConnecting = ui.isAutoConnecting,
                    hasBluetoothPermission = hasPermission,
                    bluetoothAvailable = viewModel.isBluetoothAvailable,
                    bluetoothEnabled = viewModel.isBluetoothEnabled,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    onRefresh = { viewModel.refreshPairedDevices() },
                    onConnect = { viewModel.connect(it) },
                    onCancelAutoConnect = { viewModel.cancelAutoConnect() },
                )
            }
        }
    }
}

private fun topBarTitle(connection: BtConnectionState): String = when (connection) {
    is BtConnectionState.Connected -> connection.device.name
    is BtConnectionState.Connecting -> "Connecting…"
    else -> "Mosconi DSP"
}
