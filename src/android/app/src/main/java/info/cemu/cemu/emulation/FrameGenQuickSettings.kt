package info.cemu.cemu.emulation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun FrameGenQuickSettings(isOpen: Boolean) {
    val isDllInstalled = remember { isLosslessDllInstalled() }
    if (!isDllInstalled) {
        return
    }

    var enabled by remember { mutableStateOf(NativeFrameGen.isEnabled()) }
    var multiplier by remember { mutableIntStateOf(NativeFrameGen.getMultiplier()) }
    var targetRate by remember { mutableIntStateOf(NativeFrameGen.getTargetRate()) }
    var flowScale by remember { mutableIntStateOf(NativeFrameGen.getFlowScale()) }
    var runtimeStatus by remember { mutableIntStateOf(NativeFrameGen.getRuntimeStatus()) }

    LaunchedEffect(isOpen) {
        while (isOpen) {
            runtimeStatus = NativeFrameGen.getRuntimeStatus()
            // the panel buttons can change these while the menu is closed
            enabled = NativeFrameGen.isEnabled()
            multiplier = NativeFrameGen.getMultiplier()
            targetRate = NativeFrameGen.getTargetRate()
            flowScale = NativeFrameGen.getFlowScale()
            delay(1000)
        }
    }

    QuickMenuSection(tr("Frame generation")) {
        QuickMenuToggle(
            label = tr("Enable frame generation"),
            description = if (enabled) tr("Status: {0}", frameGenRuntimeStatusToString(runtimeStatus)) else null,
            checked = enabled,
            onCheckedChange = {
                enabled = it
                NativeFrameGen.setEnabled(it)
                NativeSettings.saveSettings()
            },
        )
        QuickMenuChoices(
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
        QuickMenuChoices(
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
        QuickMenuChoices(
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
    }
}
