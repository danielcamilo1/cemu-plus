package info.cemu.cemu.emulation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.framegen.FrameGenFlowScaleChoices
import info.cemu.cemu.common.framegen.FrameGenMultiplierChoices
import info.cemu.cemu.common.framegen.FrameGenTargetRateChoices
import info.cemu.cemu.common.framegen.frameGenFlowScaleToString
import info.cemu.cemu.common.framegen.frameGenMultiplierToString
import info.cemu.cemu.common.framegen.frameGenRuntimeStatusToString
import info.cemu.cemu.common.framegen.isLosslessDllInstalled
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeFrameGen
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.delay

/**
 * Frame generation controls for the in-game menu. Changes apply immediately to the running game.
 */
@Composable
fun FrameGenQuickSettings() {
    val isDllInstalled = remember { isLosslessDllInstalled() }
    if (!isDllInstalled) {
        return
    }

    var enabled by remember { mutableStateOf(NativeFrameGen.isEnabled()) }
    var multiplier by remember { mutableIntStateOf(NativeFrameGen.getMultiplier()) }
    var targetRate by remember { mutableIntStateOf(NativeFrameGen.getTargetRate()) }
    var flowScale by remember { mutableIntStateOf(NativeFrameGen.getFlowScale()) }
    var runtimeStatus by remember { mutableIntStateOf(NativeFrameGen.getRuntimeStatus()) }

    LaunchedEffect(Unit) {
        while (true) {
            runtimeStatus = NativeFrameGen.getRuntimeStatus()
            delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = tr("Frame generation"),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        if (enabled) {
            Text(
                text = tr("Status: {0}", frameGenRuntimeStatusToString(runtimeStatus)),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    enabled = !enabled
                    NativeFrameGen.setEnabled(enabled)
                    NativeSettings.saveSettings()
                }
                .padding(8.dp)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("Enable frame generation"),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .weight(1f),
                fontSize = 16.sp,
            )
            Checkbox(checked = enabled, onCheckedChange = null)
        }

        ChipRow(
            label = tr("Target frame rate"),
            enabled = enabled,
            choices = FrameGenTargetRateChoices,
            selected = targetRate,
            choiceToString = { if (it == 0) tr("Fixed") else it.toString() },
            onSelect = {
                targetRate = it
                NativeFrameGen.setTargetRate(it)
                NativeSettings.saveSettings()
            },
        )
        ChipRow(
            label = tr("Frame multiplier"),
            enabled = enabled && targetRate == 0,
            choices = FrameGenMultiplierChoices,
            selected = multiplier,
            choiceToString = ::frameGenMultiplierToString,
            onSelect = {
                multiplier = it
                NativeFrameGen.setMultiplier(it)
                NativeSettings.saveSettings()
            },
        )
        ChipRow(
            label = tr("Motion estimation resolution"),
            enabled = enabled,
            choices = FrameGenFlowScaleChoices,
            selected = flowScale,
            choiceToString = ::frameGenFlowScaleToString,
            onSelect = {
                flowScale = it
                NativeFrameGen.setFlowScale(it)
                NativeSettings.saveSettings()
            },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun ChipRow(
    label: String,
    enabled: Boolean,
    choices: List<Int>,
    selected: Int,
    choiceToString: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 8.dp)
    ) {
        Text(text = label, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (choice in choices) {
                FilterChip(
                    selected = choice == selected,
                    enabled = enabled,
                    onClick = { onSelect(choice) },
                    label = { Text(choiceToString(choice)) },
                )
            }
        }
    }
}
