package dev.x3n0n10.mosconibt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.x3n0n10.mosconibt.bluetooth.BtConnectionState
import dev.x3n0n10.mosconibt.ui.screens.ConnectScreen
import dev.x3n0n10.mosconibt.ui.screens.ControlScreen
import dev.x3n0n10.mosconibt.ui.theme.MosconiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MosconiTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MosconiApp()
                }
            }
        }
    }
}

private fun hasBluetoothConnectPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosconiApp(viewModel: MosconiViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasBluetoothConnectPermission(context)) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refreshPairedDevices()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.refreshPairedDevices()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(topBarTitle(ui.connection)) },
                navigationIcon = {
                    if (ui.isConnected) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (ui.isConnected) {
                ControlScreen(
                    state = ui,
                    onVolumeChange = viewModel::onVolumeChange,
                    onVolumeTargetChange = viewModel::onVolumeTargetChange,
                    onSubChange = viewModel::onSubChange,
                    onGeoXChange = viewModel::onGeoXChange,
                    onGeoYChange = viewModel::onGeoYChange,
                    onTrebleChange = viewModel::onTrebleChange,
                    onMidChange = viewModel::onMidChange,
                    onBassChange = viewModel::onBassChange,
                    onPresetSelected = viewModel::onPresetSelected,
                    onHapticToggle = viewModel::onHapticFeedbackToggle,
                )
            } else {
                ConnectScreen(
                    devices = ui.pairedDevices,
                    connectionState = ui.connection,
                    hasBluetoothPermission = hasPermission,
                    bluetoothAvailable = viewModel.isBluetoothAvailable,
                    bluetoothEnabled = viewModel.isBluetoothEnabled,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    onRefresh = { viewModel.refreshPairedDevices() },
                    onConnect = { viewModel.connect(it) },
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
