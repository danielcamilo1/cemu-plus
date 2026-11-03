package info.cemu.cemu.settings.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.dropUnlessResumed
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.EmulationSettings
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.Toggle
import info.cemu.cemu.common.ui.localization.controllerTypeToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeInput
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun InputSettingsScreen(
    navigateBack: () -> Unit,
    goToInputOverlaySettings: () -> Unit,
    goToControllerSettings: (controllerIndex: Int) -> Unit,
    goToHostInputSettings: () -> Unit,
    goToHotkeySettings: () -> Unit,
) {
    val controllers = remember {
        (0..<NativeInput.MAX_CONTROLLERS).map { it to NativeInput.getControllerType(it) }
    }
    // also changed from the quick menu of a running game, which picks the change up from the store
    val emulationSettings by remember {
        AppSettingsStore.dataStore.data.map { it.emulationSettings }.distinctUntilChanged()
    }.collectAsState(initial = EmulationSettings())
    val scope = rememberCoroutineScope()

    fun updateEmulationSettings(update: (EmulationSettings) -> EmulationSettings) {
        scope.launch {
            AppSettingsStore.dataStore.updateData { it.copy(emulationSettings = update(it.emulationSettings)) }
        }
    }

    ScreenContent(
        appBarText = tr("Input settings"),
        navigateBack = navigateBack,
    ) {
        Button(
            label = tr("Input overlay settings"),
            onClick = dropUnlessResumed { goToInputOverlaySettings() },
        )
        Button(
            label = tr("Device settings"),
            onClick = dropUnlessResumed { goToHostInputSettings() },
        )
        Button(
            label = tr("Hotkey settings"),
            onClick = dropUnlessResumed { goToHotkeySettings() },
        )
        controllers.forEach { (controllerIndex, controllerEmulatedType) ->
            Button(
                label = tr("Controller {0}", controllerIndex + 1),
                description = tr(
                    "Emulated controller: {0}",
                    controllerTypeToString(controllerEmulatedType),
                ),
                onClick = dropUnlessResumed { goToControllerSettings(controllerIndex) },
            )
        }
        Header(tr("Motion"))
        Toggle(
            label = tr("Invert motion X axis"),
            description = tr("Turning left and right goes the other way, for the motion of the device and of controllers"),
            checked = emulationSettings.isMotionXInverted,
            onCheckedChanged = { inverted -> updateEmulationSettings { it.copy(isMotionXInverted = inverted) } },
        )
        Toggle(
            label = tr("Invert motion Y axis"),
            description = tr("Tilting up and down goes the other way, for the motion of the device and of controllers"),
            checked = emulationSettings.isMotionYInverted,
            onCheckedChanged = { inverted -> updateEmulationSettings { it.copy(isMotionYInverted = inverted) } },
        )
    }
}
