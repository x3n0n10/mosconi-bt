package dev.x3n0n10.mosconibt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.x3n0n10.mosconibt.ControlUiState
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol
import dev.x3n0n10.mosconibt.ui.ResponsiveContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    state: ControlUiState,
    onVolumeChange: (Int) -> Unit,
    onVolumeTargetChange: (MosconiProtocol.VolumeTarget) -> Unit,
    onSubChange: (Int) -> Unit,
    onBalanceChange: (Int) -> Unit,
    onFaderChange: (Int) -> Unit,
    onTrebleChange: (Int) -> Unit,
    onMidChange: (Int) -> Unit,
    onBassChange: (Int) -> Unit,
    onPresetSelected: (Int) -> Unit,
    onHapticToggle: (Boolean) -> Unit,
) {
    ResponsiveContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            SyncStatusRow(lastSyncedAtMillis = state.lastSyncedAtMillis)
            Spacer(Modifier.height(16.dp))

            SectionLabel("Presets")
            PresetRow(selected = state.selectedPreset, onPresetSelected = onPresetSelected)

            Spacer(Modifier.height(28.dp))
            SectionLabel("Volume")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MosconiProtocol.VolumeTarget.entries.forEachIndexed { index, target ->
                    SegmentedButton(
                        selected = state.volumeTarget == target,
                        onClick = { onVolumeTargetChange(target) },
                        shape = SegmentedButtonDefaults.itemShape(index, MosconiProtocol.VolumeTarget.entries.size),
                    ) {
                        Text(if (target == MosconiProtocol.VolumeTarget.OUTPUT) "Output" else "Input")
                    }
                }
            }
            LabeledSlider(
                label = if (state.volumeTarget == MosconiProtocol.VolumeTarget.OUTPUT) "Output volume" else "Input volume",
                value = state.volumeStep,
                valueRange = 0..MosconiProtocol.VOLUME_STEPS,
                onValueChange = onVolumeChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Sub")
            LabeledSlider(
                label = "Sub level",
                value = state.subLevel,
                valueRange = 0..MosconiProtocol.SUB_STEPS,
                onValueChange = onSubChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Balance / Fader")
            LabeledSlider(
                label = "Balance (left ↔ right)",
                value = state.balance,
                valueRange = 0..MosconiProtocol.BALANCE_FADER_STEPS,
                onValueChange = onBalanceChange,
            )
            LabeledSlider(
                label = "Fader (front ↔ rear)",
                value = state.fader,
                valueRange = 0..MosconiProtocol.BALANCE_FADER_STEPS,
                onValueChange = onFaderChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Tone")
            LabeledSlider(
                label = "Treble",
                value = state.treble,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                onValueChange = onTrebleChange,
            )
            LabeledSlider(
                label = "Mid",
                value = state.mid,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                onValueChange = onMidChange,
            )
            LabeledSlider(
                label = "Bass",
                value = state.bass,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                onValueChange = onBassChange,
            )

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Touch feedback", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Vibrate briefly when a control changes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.hapticFeedback, onCheckedChange = onHapticToggle)
            }
        }
    }
}

@Composable
private fun SyncStatusRow(lastSyncedAtMillis: Long?) {
    val synced = lastSyncedAtMillis != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (synced) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (synced) "Synced with device" else "Reading current settings…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetRow(selected: Int, onPresetSelected: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (index in 0 until MosconiProtocol.PRESET_COUNT) {
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = selected == index,
                onClick = { onPresetSelected(index) },
                label = { Text("P${index + 1}") },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text("$value", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
        )
    }
}
