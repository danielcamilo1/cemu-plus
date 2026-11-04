package info.cemu.cemu.settings.input

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.localization.controllerTypeToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBodyText
import info.cemu.cemu.nativeinterface.NativeInput

private data class ControllerSlot(
    val index: Int,
    val type: Int,
    val gameProfile: String?,
)

// the controllers in use with everything about each of them behind a single tap, then the settings for all of them
@Composable
fun InputSettingsScreen(
    navigateBack: () -> Unit,
    goToInputOverlaySettings: () -> Unit,
    goToControllerSettings: (controllerIndex: Int) -> Unit,
    goToHotkeySettings: () -> Unit,
) {
    val slots = remember {
        (0..<NativeInput.MAX_CONTROLLERS).map {
            ControllerSlot(it, NativeInput.getControllerType(it), NativeInput.getGameControllerProfile(it))
        }
    }
    val deviceControllerIndex = remember { NativeInput.getDeviceControllerIndex() }
    val usedSlots = slots.filter { it.type != NativeInput.EmulatedControllerType.DISABLED }
    val freeSlot = slots.firstOrNull { it.type == NativeInput.EmulatedControllerType.DISABLED }

    ScreenContent(
        appBarText = tr("Input settings"),
        navigateBack = navigateBack,
    ) {
        Header(tr("Controllers"))
        WiiUBodyText(
            text = tr("Pick a controller to set what it emulates, its buttons, motion, rumble and profiles"),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        usedSlots.forEach { slot ->
            val details = buildList {
                add(controllerTypeToString(slot.type))
                if (slot.index == deviceControllerIndex) add(tr("touch screen and sensors of this device"))
                slot.gameProfile?.let { add(tr("profile {0} for this game", it)) }
            }
            Button(
                label = tr("Controller {0}", slot.index + 1),
                description = details.joinToString(" · "),
                icon = R.drawable.ic_controller,
                onClick = dropUnlessResumed { goToControllerSettings(slot.index) },
            )
        }
        if (freeSlot != null) {
            Button(
                label = tr("Add a controller"),
                description = tr("Set up Controller {0}", freeSlot.index + 1),
                icon = R.drawable.ic_add,
                onClick = dropUnlessResumed { goToControllerSettings(freeSlot.index) },
            )
        }

        Header(tr("More"))
        Button(
            label = tr("Hotkeys"),
            description = tr("Button combinations to open the quick menu, exit the game and more"),
            icon = R.drawable.ic_keyboard,
            onClick = dropUnlessResumed { goToHotkeySettings() },
        )
        Button(
            label = tr("On-screen controls"),
            description = tr("The touch buttons drawn over the game"),
            icon = R.drawable.ic_touch_app,
            onClick = dropUnlessResumed { goToInputOverlaySettings() },
        )
    }
}
