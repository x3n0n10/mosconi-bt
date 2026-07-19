package dev.x3n0n10.mosconibt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.x3n0n10.mosconibt.R
import dev.x3n0n10.mosconibt.bluetooth.BtConnectionState
import dev.x3n0n10.mosconibt.bluetooth.BtDevice
import dev.x3n0n10.mosconibt.ui.ResponsiveContent

@Composable
fun ConnectScreen(
    devices: List<BtDevice>,
    connectionState: BtConnectionState,
    isAutoConnecting: Boolean,
    hasBluetoothPermission: Boolean,
    bluetoothAvailable: Boolean,
    bluetoothEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (BtDevice) -> Unit,
    onCancelAutoConnect: () -> Unit,
) {
    val bluetoothIcon = ImageVector.vectorResource(R.drawable.ic_bluetooth)
    val bluetoothDisabledIcon = ImageVector.vectorResource(R.drawable.ic_bluetooth_disabled)

    ResponsiveContent {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Mosconi DSP", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Connect to your DSP's Bluetooth module",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            when {
                !bluetoothAvailable -> StatusMessage(
                    icon = bluetoothDisabledIcon,
                    title = "No Bluetooth adapter",
                    message = "This device doesn't support Bluetooth.",
                )

                !hasBluetoothPermission -> StatusMessage(
                    icon = bluetoothIcon,
                    title = "Bluetooth permission needed",
                    message = "To see and connect to your DSP, allow Bluetooth access.",
                    action = { Button(onClick = onRequestPermission) { Text("Grant permission") } },
                )

                !bluetoothEnabled -> StatusMessage(
                    icon = bluetoothDisabledIcon,
                    title = "Bluetooth is off",
                    message = "Turn on Bluetooth, then refresh this list.",
                    action = { OutlinedButton(onClick = onRefresh) { Text("Refresh") } },
                )

                else -> {
                    val autoConnectingDevice = (connectionState as? BtConnectionState.Connecting)
                        ?.device
                        ?.takeIf { isAutoConnecting }
                    if (autoConnectingDevice != null) {
                        AutoConnectBanner(deviceName = autoConnectingDevice.name, onCancel = onCancelAutoConnect)
                        Spacer(Modifier.height(16.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Paired devices",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (devices.isEmpty()) {
                        StatusMessage(
                            icon = bluetoothIcon,
                            title = "No paired devices",
                            message = "Pair with your DSP's Bluetooth module in Android's " +
                                "Bluetooth settings first (it usually shows up as something " +
                                "containing \"MOSCONI\"), then come back and refresh.",
                        )
                    } else {
                        val connectingAddress = (connectionState as? BtConnectionState.Connecting)?.device?.address
                        LazyColumn {
                            items(devices, key = { it.address }) { device ->
                                ListItem(
                                    headlineContent = {
                                        Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(device.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = { Icon(bluetoothIcon, contentDescription = null) },
                                    trailingContent = {
                                        Button(
                                            onClick = { onConnect(device) },
                                            enabled = connectingAddress != device.address,
                                        ) {
                                            Text(
                                                if (connectingAddress == device.address) "Connecting…" else "Connect",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (connectionState is BtConnectionState.Failed) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Couldn't connect: ${connectionState.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Shown in place of (well, on top of) the plain device list while auto-connecting to
 *  the last-used device, so the user isn't just staring at a "Connecting…" button with
 *  no way out - they can either wait, cancel, or tap a different paired device below. */
@Composable
private fun AutoConnectBanner(deviceName: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Connecting automatically…", style = MaterialTheme.typography.bodyLarge)
                Text(deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun StatusMessage(
    icon: ImageVector,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Icon(icon, contentDescription = null, modifier = Modifier.height(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}
