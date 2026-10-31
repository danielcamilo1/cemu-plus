package info.cemu.cemu.settings.framegen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import info.cemu.cemu.common.framegen.FrameGenFlowScaleChoices
import info.cemu.cemu.common.framegen.FrameGenMultiplierChoices
import info.cemu.cemu.common.framegen.FrameGenTargetRateChoices
import info.cemu.cemu.common.framegen.frameGenFlowScaleToString
import info.cemu.cemu.common.framegen.frameGenMultiplierToString
import info.cemu.cemu.common.framegen.frameGenTargetRateToString
import info.cemu.cemu.common.framegen.installLosslessDll
import info.cemu.cemu.common.framegen.losslessDllStatusToString
import info.cemu.cemu.common.framegen.removeLosslessDll
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.SingleSelection
import info.cemu.cemu.common.ui.components.Toggle
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeFrameGen
import info.cemu.cemu.nativeinterface.NativeFrameGen.LosslessDllStatus
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.launch

@Composable
fun FrameGenSettingsScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var dllStatus by remember { mutableIntStateOf(NativeFrameGen.getInstalledLosslessDllStatus()) }
    var isInstalling by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(NativeFrameGen.isEnabled()) }
    var multiplier by remember { mutableIntStateOf(NativeFrameGen.getMultiplier()) }
    var targetRate by remember { mutableIntStateOf(NativeFrameGen.getTargetRate()) }
    var flowScale by remember { mutableIntStateOf(NativeFrameGen.getFlowScale()) }
    val isDllInstalled = dllStatus == LosslessDllStatus.OK

    fun showMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val dllPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            isInstalling = true
            coroutineScope.launch {
                val status = installLosslessDll(context, uri)
                isInstalling = false
                dllStatus = NativeFrameGen.getInstalledLosslessDllStatus()
                showMessage(
                    if (status == LosslessDllStatus.OK) tr("Lossless.dll installed successfully")
                    else tr("Failed to install Lossless.dll: {0}", losslessDllStatusToString(status))
                )
            }
        }

    ScreenContent(
        appBarText = tr("Frame generation"),
        navigateBack = navigateBack,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        Header(tr("Lossless Scaling"))
        Text(
            modifier = Modifier.padding(horizontal = 8.dp),
            text = tr("Frame generation uses the shaders of Lossless Scaling. Select Lossless.dll from your own copy of Lossless Scaling (found in its installation folder on Windows). The DLL is only read, never executed."),
        )
        Button(
            label = if (isDllInstalled) tr("Replace Lossless.dll") else tr("Select Lossless.dll"),
            description = tr("Status: {0}", losslessDllStatusToString(dllStatus)),
            onClick = { dllPickerLauncher.launch(arrayOf("*/*")) },
        )
        if (isDllInstalled) {
            Button(
                label = tr("Remove Lossless.dll"),
                onClick = {
                    coroutineScope.launch {
                        removeLosslessDll()
                        enabled = false
                        dllStatus = NativeFrameGen.getInstalledLosslessDllStatus()
                        showMessage(tr("Lossless.dll removed"))
                    }
                },
            )
        }

        Header(tr("Settings"))
        Toggle(
            label = tr("Enable frame generation"),
            description = tr("Insert interpolated frames between the frames rendered by the game. Forces VSync (FIFO) while enabled. Vulkan only, applies to the TV screen. Can also be changed from the in-game menu."),
            checked = enabled,
            enabled = isDllInstalled,
            onCheckedChanged = {
                enabled = it
                NativeFrameGen.setEnabled(it)
                NativeSettings.saveSettings()
            },
        )
        SingleSelection(
            label = tr("Target frame rate"),
            choice = targetRate,
            choices = FrameGenTargetRateChoices,
            choiceToString = ::frameGenTargetRateToString,
            onChoiceChanged = {
                targetRate = it
                NativeFrameGen.setTargetRate(it)
                NativeSettings.saveSettings()
            },
        )
        SingleSelection(
            label = tr("Frame multiplier"),
            choice = multiplier,
            choices = FrameGenMultiplierChoices,
            choiceToString = ::frameGenMultiplierToString,
            enabled = targetRate == 0,
            onChoiceChanged = {
                multiplier = it
                NativeFrameGen.setMultiplier(it)
                NativeSettings.saveSettings()
            },
        )
        SingleSelection(
            label = tr("Motion estimation resolution"),
            choice = flowScale,
            choices = FrameGenFlowScaleChoices,
            choiceToString = ::frameGenFlowScaleToString,
            onChoiceChanged = {
                flowScale = it
                NativeFrameGen.setFlowScale(it)
                NativeSettings.saveSettings()
            },
        )
        Text(
            modifier = Modifier.padding(8.dp),
            text = tr("The target frame rate adjusts the multiplier automatically and backs off when frame generation slows the game down. Pick the refresh rate of your display, or use a fixed multiplier. A lower motion estimation resolution is the cheapest way to save GPU time."),
        )
    }

    if (isInstalling) {
        AlertDialog(
            title = { Text(tr("Installing")) },
            text = { LinearProgressIndicator() },
            confirmButton = {},
            onDismissRequest = {},
        )
    }
}
