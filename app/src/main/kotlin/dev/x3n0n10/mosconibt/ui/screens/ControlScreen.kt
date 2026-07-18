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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.x3n0n10.mosconibt.ControlUiState
import dev.x3n0n10.mosconibt.MosconiViewModel
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol
import dev.x3n0n10.mosconibt.ui.ResponsiveContent
import kotlinx.coroutines.delay

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
    // DSP-affecting controls stay disabled until the app actually knows the device's real
    // values - editing before that would start from a guess (restored prefs, or hardcoded
    // defaults) and could silently overwrite whatever the device was really set to. The
    // haptic-feedback switch below is local-only and isn't gated by this.
    val controlsEnabled = state.controlsReady

    ResponsiveContent {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            SyncStatusRow(
                lastSyncedAtMillis = state.lastSyncedAtMillis,
                lastLocalEditAtMillis = state.lastLocalEditAtMillis,
            )
            Spacer(Modifier.height(16.dp))

            SectionLabel("Presets")
            PresetRow(
                selected = state.selectedPreset,
                names = state.presetNames,
                enabled = controlsEnabled,
                onPresetSelected = onPresetSelected,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Sub")
            LabeledSlider(
                label = "Sub level",
                value = state.subLevel,
                valueRange = 0..MosconiProtocol.SUB_STEPS,
                enabled = controlsEnabled,
                onValueChange = onSubChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Balance / Fader")
            LabeledSlider(
                label = "Balance (left ↔ right)",
                value = state.balance,
                valueRange = 0..MosconiProtocol.BALANCE_FADER_STEPS,
                enabled = controlsEnabled,
                onValueChange = onBalanceChange,
            )
            LabeledSlider(
                label = "Fader (front ↔ rear)",
                value = state.fader,
                valueRange = 0..MosconiProtocol.BALANCE_FADER_STEPS,
                enabled = controlsEnabled,
                onValueChange = onFaderChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Tone")
            LabeledSlider(
                label = "Treble",
                value = state.treble,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                enabled = controlsEnabled,
                onValueChange = onTrebleChange,
            )
            LabeledSlider(
                label = "Mid",
                value = state.mid,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                enabled = controlsEnabled,
                onValueChange = onMidChange,
            )
            LabeledSlider(
                label = "Bass",
                value = state.bass,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                enabled = controlsEnabled,
                onValueChange = onBassChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Volume")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MosconiProtocol.VolumeTarget.entries.forEachIndexed { index, target ->
                    SegmentedButton(
                        selected = state.volumeTarget == target,
                        onClick = { onVolumeTargetChange(target) },
                        enabled = controlsEnabled,
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
                enabled = controlsEnabled,
                onValueChange = onVolumeChange,
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
 *
 * A recent local edit is shown as a distinct, deliberate "Adjusting…" state rather than
 * reusing the stale/error styling: the ViewModel *intentionally* pauses applying poll
 * results for a few seconds after any slider/button touch, so a response can't revert the
 * value the user just picked - and that shouldn't look like something has gone wrong.
 */
@Composable
private fun SyncStatusRow(lastSyncedAtMillis: Long?, lastLocalEditAtMillis: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val editAgeMillis = lastLocalEditAtMillis?.let { now - it }
    val isPausedForEdit = editAgeMillis != null && editAgeMillis < MosconiViewModel.PAUSE_READS_AFTER_EDIT_MS

    val syncAgeMillis = lastSyncedAtMillis?.let { now - it }
    val isStale = syncAgeMillis == null || syncAgeMillis > STALE_AFTER_MILLIS

    val (dotColor, label) = when {
        isPausedForEdit -> MaterialTheme.colorScheme.primary to "Adjusting…"
        lastSyncedAtMillis == null -> MaterialTheme.colorScheme.outline to "Reading current settings…"
        isStale -> Color(0xFFFFA000) to "Sync lost – last update ${(syncAgeMillis!! / 1000)}s ago"
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

/**
 * [names] are the custom preset names read (read-only) from the DSP itself - see
 * [dev.x3n0n10.mosconibt.protocol.MosconiProtocol.parsePresetNames]. The four chips
 * themselves always stay identically shaped/sized (just "P<n>", nothing content-
 * dependent) - a non-blank custom name renders as its own label *below* the chip,
 * clipped to that chip's column width so neighbors can never overlap.
 */
@Composable
private fun PresetRow(selected: Int, names: List<String?>, enabled: Boolean, onPresetSelected: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (index in 0 until MosconiProtocol.PRESET_COUNT) {
            val customName = names.getOrNull(index)?.takeUnless { it.isBlank() }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilterChip(
                    modifier = Modifier.fillMaxWidth(),
                    selected = selected == index,
                    enabled = enabled,
                    onClick = { onPresetSelected(index) },
                    label = {
                        Text(
                            "P${index + 1}",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                if (customName != null) {
                    Text(
                        customName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
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
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    val labelColor = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
            Text(
                "$value",
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}
