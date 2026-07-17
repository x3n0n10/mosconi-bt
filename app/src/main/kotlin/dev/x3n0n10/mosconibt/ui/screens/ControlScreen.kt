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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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

/**
 * Reflects live poll freshness, not just "has a poll ever succeeded": status polls run
 * every second while connected, so anything older than [STALE_AFTER_MILLIS] means recent
 * polls are failing (a timeout, a checksum mismatch, a flaky link) - not stale for good,
 * but worth flagging rather than silently continuing to show what might be old data.
 */
@Composable
private fun SyncStatusRow(lastSyncedAtMillis: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val ageMillis = lastSyncedAtMillis?.let { now - it }
    val isStale = ageMillis == null || ageMillis > STALE_AFTER_MILLIS

    val (dotColor, label) = when {
        lastSyncedAtMillis == null -> MaterialTheme.colorScheme.outline to "Reading current settings…"
        isStale -> Color(0xFFFFA000) to "Sync lost – last update ${(ageMillis!! / 1000)}s ago"
        else -> Color(0xFF4CAF50) to "Synced with device"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color = dotColor, shape = CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Poll interval is 1s; two missed polls in a row is a meaningfully stale signal. */
private const val STALE_AFTER_MILLIS = 3000L

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
