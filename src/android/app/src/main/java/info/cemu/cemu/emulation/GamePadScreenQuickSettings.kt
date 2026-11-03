package info.cemu.cemu.emulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import info.cemu.cemu.common.android.display.SecondScreenInfo
import info.cemu.cemu.common.android.display.rememberSecondScreen
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.games.profile.fillScreenCrop
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.settings.screenlayout.AlignmentChoices
import info.cemu.cemu.settings.screenlayout.FallbackSecondScreen
import info.cemu.cemu.settings.screenlayout.WII_U_ASPECT_RATIO
import info.cemu.cemu.settings.screenlayout.alignmentToString
import info.cemu.cemu.settings.screenlayout.applyToolbarSettings
import info.cemu.cemu.settings.screenlayout.areScreensSwappedEffective
import info.cemu.cemu.settings.screenlayout.isToolbarEnabled
import info.cemu.cemu.settings.screenlayout.toolbarScreenFor

/**
 * The main options of how the GamePad image looks for the in-game menu: the toolbar, where the image goes
 * and the crop of the running game. Changes apply immediately, the crop is saved to the game profile the
 * same as Edit game profile.
 */
@Composable
fun GamePadScreenQuickSettings(
    isOpen: Boolean,
    isEmulationInitialized: Boolean,
    isPadOnSecondScreen: Boolean,
    isPadVisible: Boolean,
    areScreensSwapped: Boolean,
) {
    // the menu is composed before the game starts, so look the game up again once it runs
    val titleId = remember(isEmulationInitialized) { NativeGameTitles.getRunningTitleId() }
    val screen = rememberSecondScreen() ?: FallbackSecondScreen
    // a screen wider than the GamePad gets the bars on the sides, there is nothing to align then
    val isLetterboxed = screen.width.toFloat() / screen.height < WII_U_ASPECT_RATIO

    // read again each time the menu opens, the settings can change these while it is closed
    var toolbarEnabled by remember(isOpen) { mutableStateOf(isToolbarEnabled()) }
    var alignment by remember(isOpen) { mutableIntStateOf(NativeSettings.getPadScreenAlignment()) }

    fun applyToolbar(enabled: Boolean, align: Int) {
        val toolbarScreen = toolbarScreenFor(isPadOnSecondScreen, isPadVisible, NativeSettings.getOverlayScreen())
        val swapped = areScreensSwappedEffective(isPadOnSecondScreen, isPadVisible, areScreensSwapped)
        applyToolbarSettings(enabled = enabled, screen = toolbarScreen, alignment = align, swapped = swapped)
    }

    QuickMenuSection(tr("GamePad screen and toolbar")) {
        QuickMenuToggle(
            label = tr("Toolbar in the black bars"),
            description = tr("Performance info and touch buttons around the game"),
            checked = toolbarEnabled,
            onCheckedChange = {
                toolbarEnabled = it
                applyToolbar(enabled = it, align = alignment)
                NativeSettings.saveSettings()
            },
        )
        if (!isPadOnSecondScreen) {
            QuickMenuNote(tr("The position and crop below are used while the GamePad is on the second screen"))
        }
        if (isLetterboxed) {
            QuickMenuChoices(
                label = tr("GamePad image position"),
                choices = AlignmentChoices,
                selected = alignment,
                choiceToString = ::alignmentToString,
                onSelect = {
                    alignment = it
                    NativeSettings.setPadScreenAlignment(it)
                    // the toolbar goes into the bar opposite to the image
                    if (toolbarEnabled) {
                        applyToolbar(enabled = true, align = it)
                    }
                    NativeSettings.saveSettings()
                },
            )
        }
        if (titleId != 0L) {
            PadCropControls(
                titleId = titleId,
                isOpen = isOpen,
                screen = screen,
                isToolbarEnabled = toolbarEnabled,
            )
        }
    }
}

@Composable
private fun PadCropControls(
    titleId: Long,
    isOpen: Boolean,
    screen: SecondScreenInfo,
    isToolbarEnabled: Boolean,
) {
    var enabled by remember(titleId, isOpen) { mutableStateOf(NativeGameTitles.isPadCropEnabledForTitle(titleId)) }
    var horizontal by remember(titleId, isOpen) { mutableIntStateOf(NativeGameTitles.getPadCropHorizontalForTitle(titleId)) }
    var vertical by remember(titleId, isOpen) { mutableIntStateOf(NativeGameTitles.getPadCropVerticalForTitle(titleId)) }
    var toolbarHidden by remember(titleId, isOpen) {
        mutableStateOf(NativeGameTitles.isPadCropToolbarHiddenForTitle(titleId))
    }

    fun save(newHorizontal: Int, newVertical: Int) {
        horizontal = newHorizontal.coerceIn(0, NativeGameTitles.PAD_CROP_MAX)
        vertical = newVertical.coerceIn(0, NativeGameTitles.PAD_CROP_MAX)
        NativeGameTitles.setPadCropForTitle(titleId, horizontal, vertical)
    }

    val fill = fillScreenCrop(screen, horizontal, vertical)

    QuickMenuToggle(
        label = tr("Crop the GamePad image"),
        description = tr("Saved for this game"),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            NativeGameTitles.setPadCropEnabledForTitle(titleId, it)
        },
    )
    QuickMenuStepper(
        label = tr("Left and right"),
        value = tr("{0}%", horizontal.toString()),
        enabled = enabled,
        canDecrease = horizontal > 0,
        canIncrease = horizontal < NativeGameTitles.PAD_CROP_MAX,
        onDecrease = { save(horizontal - 1, vertical) },
        onIncrease = { save(horizontal + 1, vertical) },
    )
    QuickMenuStepper(
        label = tr("Top and bottom"),
        value = tr("{0}%", vertical.toString()),
        enabled = enabled,
        canDecrease = vertical > 0,
        canIncrease = vertical < NativeGameTitles.PAD_CROP_MAX,
        onDecrease = { save(horizontal, vertical - 1) },
        onIncrease = { save(horizontal, vertical + 1) },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickMenuButton(
            label = tr("Fill the screen"),
            enabled = enabled && fill != (horizontal to vertical),
            onClick = { save(fill.first, fill.second) },
            modifier = Modifier.weight(1f),
        )
        QuickMenuButton(
            label = tr("Reset"),
            enabled = enabled && (horizontal != 0 || vertical != 0),
            onClick = { save(0, 0) },
            modifier = Modifier.weight(1f),
        )
    }
    if (isToolbarEnabled) {
        QuickMenuToggle(
            label = tr("Hide the toolbar for this game"),
            description = tr("While the crop is on"),
            checked = toolbarHidden,
            enabled = enabled,
            onCheckedChange = {
                toolbarHidden = it
                NativeGameTitles.setPadCropToolbarHiddenForTitle(titleId, it)
            },
        )
    }
}
