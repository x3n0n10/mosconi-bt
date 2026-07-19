package dev.x3n0n10.mosconibt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
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
) {
    // DSP-affecting controls stay disabled until the app actually knows the device's real
    // values - editing before that would start from a guess (restored prefs, or hardcoded
    // defaults) and could silently overwrite whatever the device was really set to.
    val controlsEnabled = state.controlsReady

    ResponsiveContent(scrollable = true) {
        Column(modifier = Modifier.padding(24.dp)) {
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
                presetsEnabled = state.presetsEnabled,
                onPresetSelected = onPresetSelected,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Subwoofer")
            LabeledSlider(
                label = "Level",
                value = state.subLevel,
                valueRange = 0..MosconiProtocol.SUB_STEPS,
                enabled = controlsEnabled,
                onValueChange = onSubChange,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Listening position")
            LabeledSlider(
                label = "Balance (left ↔ right)",
                value = state.balance,
                valueRange = 0..MosconiProtocol.BALANCE_FADER_STEPS,
                enabled = controlsEnabled,
                onValueChange = onBalanceChange,
                centerValue = MosconiProtocol.BALANCE_FADER_STEPS / 2,
            )
            LabeledSlider(
                label = "Fader (rear ↔ front)",
                // Displayed mirrored from the underlying domain value: the DSP/protocol
                // side keeps position 0 = front (see MosconiProtocol.State.setFader's
                // doc comment - real-hardware-confirmed), but this slider shows rear on
                // the left and front on the right, so flip for display only and flip
                // back on edit. The center mark stays put either way since the range is
                // symmetric around its midpoint.
                value = MosconiProtocol.BALANCE_FADER_STEPS - state.fader,
                valueRange = 0..MosconiProtocol.BALANCE_FADER_STEPS,
                enabled = controlsEnabled,
                onValueChange = { onFaderChange(MosconiProtocol.BALANCE_FADER_STEPS - it) },
                centerValue = MosconiProtocol.BALANCE_FADER_STEPS / 2,
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Tone")
            LabeledSlider(
                label = "Treble",
                value = state.treble,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                enabled = controlsEnabled,
                onValueChange = onTrebleChange,
                centerValue = FLAT_TONE_VALUE,
            )
            LabeledSlider(
                label = "Mid",
                value = state.mid,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                enabled = controlsEnabled,
                onValueChange = onMidChange,
                centerValue = FLAT_TONE_VALUE,
            )
            LabeledSlider(
                label = "Bass",
                value = state.bass,
                valueRange = 0..MosconiProtocol.TONE_STEPS,
                enabled = controlsEnabled,
                onValueChange = onBassChange,
                centerValue = FLAT_TONE_VALUE,
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

/** The DSP's "flat"/no-adjustment tone value - see the Tone frame docs in PROTOCOL.md.
 *  Not the exact geometric center of 0..[MosconiProtocol.TONE_STEPS] (that's 7.5),
 *  which is why [LabeledSlider]'s mark is positioned by value, not by fixed alignment. */
private const val FLAT_TONE_VALUE = 8

/**
 * [names] are the custom preset names read (read-only) from the DSP itself - see
 * [dev.x3n0n10.mosconibt.protocol.MosconiProtocol.parsePresetNames]. The four chips
 * themselves always stay identically shaped/sized (just "P<n>", nothing content-
 * dependent) - a non-blank custom name renders as its own label *below* the chip,
 * clipped to that chip's column width so neighbors can never overlap.
 *
 * [presetsEnabled] is the local-only "enable preset" safety gate (see
 * [dev.x3n0n10.mosconibt.MosconiPrefs.isPresetEnabled]) - a disabled slot's chip is
 * greyed out and unselectable, same as the device-not-synced [enabled] gate, except
 * the *currently selected* preset is always shown enabled regardless of its stored
 * flag: settings can't disable the active preset, but a physical-knob change on the
 * device could still make a locally-disabled slot become the active one.
 */
@Composable
private fun PresetRow(
    selected: Int,
    names: List<String?>,
    enabled: Boolean,
    presetsEnabled: List<Boolean>,
    onPresetSelected: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (index in 0 until MosconiProtocol.PRESET_COUNT) {
            val customName = names.getOrNull(index)?.takeUnless { it.isBlank() }
            val isSelected = selected == index
            val presetEnabled = isSelected || presetsEnabled.getOrElse(index) { true }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilterChip(
                    modifier = Modifier.fillMaxWidth(),
                    selected = isSelected,
                    enabled = enabled && presetEnabled,
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
    centerValue: Int? = null,
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
        Box(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
                enabled = enabled,
            )
            if (centerValue != null) {
                // A landmark tick at this control's rest value, not a snap point -
                // worth being able to see (and land back on) without staring at the
                // numeric readout above. Drawn on top of the slider so it only shows
                // in the track's transparent margin - it visually "hides" under the
                // thumb exactly when the value matches. Positioned by value (not a
                // fixed Alignment.Center) since it isn't always the exact midpoint -
                // the tone controls' "flat" value of 8 sits slightly off-center in
                // their 0..15 range.
                val fraction = (centerValue - valueRange.first).toFloat() / (valueRange.last - valueRange.first)
                Box(
                    modifier = Modifier
                        .align(BiasAlignment(horizontalBias = fraction * 2f - 1f, verticalBias = 0f))
                        .width(2.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}
