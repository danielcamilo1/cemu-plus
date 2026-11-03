package info.cemu.cemu.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.localization.tr

@Composable
fun SettingsHomeScreen(
    goToGeneralSettings: () -> Unit,
    goToInputSettings: () -> Unit,
    goToGraphicsSettings: () -> Unit,
    goToEmulatedUSBDevicesSettings: () -> Unit,
    goToAudioSettings: () -> Unit,
    goToAccountSettings: () -> Unit,
    goToOverlaySettings: () -> Unit,
    goToScreenLayoutSettings: () -> Unit,
    isSecondScreenEnabled: Boolean,
    navigateBack: () -> Unit,
    goToSetupWizard: (() -> Unit)? = null,
) {
    ScreenContent(
        appBarText = tr("Settings"),
        navigateBack = navigateBack,
    ) {
        if (goToSetupWizard != null) {
            Button(
                label = tr("Setup wizard"),
                icon = R.drawable.ic_celebration,
                description = tr("Games folder, controls, frame generation and toolbar, step by step"),
                onClick = dropUnlessResumed(block = goToSetupWizard)
            )
        }
        Button(
            label = tr("General settings"),
            icon = R.drawable.ic_settings,
            onClick = dropUnlessResumed(block = goToGeneralSettings)
        )
        Button(
            label = tr("Input settings"),
            description = tr("Controllers, motion, rumble, profiles and hotkeys"),
            icon = R.drawable.ic_controller,
            onClick = dropUnlessResumed(block = goToInputSettings)
        )
        Button(
            label = tr("Screen layout"),
            icon = R.drawable.ic_dashboard,
            description = if (isSecondScreenEnabled) tr("The Wii U GamePad on your second screen and the toolbar in its black bars")
            else tr("Where the TV and GamePad images go and the toolbar in the black bars"),
            onClick = dropUnlessResumed(block = goToScreenLayoutSettings)
        )
        Button(
            label = tr("Graphics settings"),
            icon = R.drawable.ic_auto_awesome,
            onClick = dropUnlessResumed(block = goToGraphicsSettings)
        )
        Button(
            label = tr("Audio settings"),
            icon = R.drawable.ic_volume_up,
            onClick = dropUnlessResumed(block = goToAudioSettings)
        )
        Button(
            label = tr("Overlay settings"),
            icon = R.drawable.ic_layers,
            onClick = dropUnlessResumed(block = goToOverlaySettings)
        )
        Button(
            label = tr("Emulated USB Devices"),
            icon = R.drawable.ic_usb,
            onClick = dropUnlessResumed(block = goToEmulatedUSBDevicesSettings)
        )
        Button(
            label = tr("Account settings"),
            icon = R.drawable.ic_person,
            onClick = dropUnlessResumed(block = goToAccountSettings)
        )
    }
}