package dev.x3n0n10.mosconibt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.x3n0n10.mosconibt.ThemeMode
import dev.x3n0n10.mosconibt.ui.ResponsiveContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    hapticFeedback: Boolean,
    themeMode: ThemeMode,
    rememberedDeviceLabel: String?,
    onHapticToggle: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onForgetDevice: () -> Unit,
) {
    ResponsiveContent(scrollable = true) {
        Column(modifier = Modifier.padding(24.dp)) {
            SettingsSectionLabel("Appearance")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    ) {
                        Text(mode.label())
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            SettingsRow(
                title = "Touch feedback",
                description = "Vibrate briefly when a control changes",
            ) {
                Switch(checked = hapticFeedback, onCheckedChange = onHapticToggle)
            }

            HorizontalDivider()
            SettingsRow(
                title = "Forget device",
                description = if (rememberedDeviceLabel != null) {
                    "Stop automatically reconnecting to $rememberedDeviceLabel, and " +
                        "disconnect now if connected."
                } else {
                    "No device is currently remembered for auto-connect."
                },
            ) {
                OutlinedButton(onClick = onForgetDevice, enabled = rememberedDeviceLabel != null) {
                    Text("Forget")
                }
            }
            HorizontalDivider()
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

/** One labeled row with a trailing control - the shared layout for every setting on
 *  this screen (a switch, a button, whatever [trailing] needs to be). */
@Composable
private fun SettingsRow(title: String, description: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
