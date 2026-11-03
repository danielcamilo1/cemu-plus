package info.cemu.cemu.emulation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.settings.screenlayout.applyToolbarSettings
import info.cemu.cemu.settings.screenlayout.areScreensSwappedEffective
import info.cemu.cemu.settings.screenlayout.isToolbarEnabled
import info.cemu.cemu.settings.screenlayout.toolbarScreenFor

// the quick menu follows the toolbar theme, white like the Wii U Basic or black like the Deluxe
internal class QuickMenuColors(
    val background: Color,
    val stripe: Color,
    val card: Color,
    val outline: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val onAccent: Color,
    val control: Color,
    val focus: Color,
)

private val WhiteQuickMenuColors = QuickMenuColors(
    background = Color(0xFFEDEFF2),
    stripe = Color(0x0C000000),
    card = Color.White,
    outline = Color(0xFFD4D8DE),
    text = Color(0xFF2E3035),
    secondaryText = Color(0xFF666B74),
    accent = Color(0xFF0088CC),
    onAccent = Color.White,
    control = Color(0xFFE5E8ED),
    focus = Color(0xFF00B4F0),
)

private val BlackQuickMenuColors = QuickMenuColors(
    background = Color(0xFF15161A),
    stripe = Color(0x0AFFFFFF),
    card = Color(0xFF23252A),
    outline = Color(0xFF383B42),
    text = Color(0xFFF0F1F3),
    secondaryText = Color(0xFFA2A7AF),
    accent = Color(0xFF2CB5EC),
    onAccent = Color(0xFF04202C),
    control = Color(0xFF30333A),
    focus = Color(0xFF3CC8FF),
)

internal val LocalQuickMenuColors = staticCompositionLocalOf { WhiteQuickMenuColors }

private const val DISABLED_ALPHA = 0.45f
private val QuickMenuWidth = 360.dp
private val QuickMenuCardShape = RoundedCornerShape(18.dp)
private val QuickMenuItemShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)

// the drawer with the thin stripes of the Wii U menus behind the content
@Composable
fun QuickMenuSheet(isOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val theme = remember(isOpen) { NativeSettings.getOverlayTheme() }
    val colors = if (theme == NativeSettings.OverlayTheme.BLACK) BlackQuickMenuColors else WhiteQuickMenuColors
    CompositionLocalProvider(
        LocalQuickMenuColors provides colors,
        LocalContentColor provides colors.text,
    ) {
        ModalDrawerSheet(
            modifier = Modifier.width(QuickMenuWidth),
            drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            drawerContainerColor = colors.background,
            drawerContentColor = colors.text,
            drawerTonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val gap = 6.dp.toPx()
                        val thickness = 1.dp.toPx()
                        var y = 0f
                        while (y < size.height) {
                            drawLine(colors.stripe, Offset(0f, y), Offset(size.width, y), thickness)
                            y += gap
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/**
 * The in-game quick menu. The big buttons on top are what is used the most, then the screens, how the GamePad
 * image looks, frame generation and the controls. Toggles apply right away and keep the menu open so several
 * can be tried in a row.
 */
@Composable
fun QuickMenuContent(
    isOpen: Boolean,
    isEmulationInitialized: Boolean,
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
    onBackToGame: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
    onShowEmulatedUSBDevices: () -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
) {
    val firstButton = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // buttons only take focus outside of touch mode, which the first controller key switches to
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(isOpen, inputMode) {
        if (isOpen) {
            // so a controller can move through the menu right away
            if (inputMode == InputMode.Keyboard) {
                runCatching { firstButton.requestFocus() }
            }
        } else {
            focusManager.clearFocus()
        }
    }

    // the toolbar moves along when the screens change, the same as in the screen layout settings
    fun updateScreens(newState: SideMenuState) {
        updateState(newState)
        if (isToolbarEnabled()) {
            // turning the second screen on starts the toolbar out with the GamePad
            val turnedSecondScreenOn = newState.isPadOnExternalDisplay && !sideMenuState.isPadOnExternalDisplay
            val screen = toolbarScreenFor(
                useSecondScreen = newState.isPadOnExternalDisplay,
                isPadVisible = newState.isPadVisible,
                chosenScreen = if (turnedSecondScreenOn) NativeSettings.OverlayScreen.PAD else NativeSettings.getOverlayScreen(),
            )
            val swapped = areScreensSwappedEffective(newState.isPadOnExternalDisplay, newState.isPadVisible, newState.areScreensSwapped)
            applyToolbarSettings(enabled = true, screen = screen, alignment = NativeSettings.getPadScreenAlignment(), swapped = swapped)
            NativeSettings.saveSettings()
        }
    }

    QuickMenuHeader(
        firstButton = firstButton,
        onBackToGame = onBackToGame,
        onOpenSettings = onOpenSettings,
        onQuit = onQuit,
    )

    QuickMenuSection(tr("Screens")) {
        QuickMenuToggle(
            label = tr("Use second screen"),
            checked = sideMenuState.isPadOnExternalDisplay,
            onCheckedChange = { updateScreens(sideMenuState.copy(isPadOnExternalDisplay = it)) },
        )
        QuickMenuToggle(
            label = tr("Swap screens"),
            checked = sideMenuState.areScreensSwapped,
            enabled = sideMenuState.isPadOnExternalDisplay || sideMenuState.isPadVisible,
            onCheckedChange = { updateScreens(sideMenuState.copy(areScreensSwapped = it)) },
        )
        // with a second screen the GamePad always goes there, this splits a single screen
        QuickMenuToggle(
            label = tr("Show the GamePad on this screen"),
            checked = sideMenuState.isPadVisible,
            enabled = !sideMenuState.isPadOnExternalDisplay,
            onCheckedChange = { updateScreens(sideMenuState.copy(isPadVisible = it)) },
        )
        QuickMenuToggle(
            label = tr("Replace the TV image with the GamePad"),
            checked = sideMenuState.isTVReplacedWithPad,
            onCheckedChange = { updateState(sideMenuState.copy(isTVReplacedWithPad = it)) },
        )
        QuickMenuToggle(
            label = tr("Rotate second screen left"),
            checked = sideMenuState.isExternalScreenRotatedLeft,
            enabled = sideMenuState.isPadOnExternalDisplay,
            onCheckedChange = { updateState(sideMenuState.copy(isExternalScreenRotatedLeft = it)) },
        )
    }

    GamePadScreenQuickSettings(
        isOpen = isOpen,
        isEmulationInitialized = isEmulationInitialized,
        isPadOnSecondScreen = sideMenuState.isPadOnExternalDisplay,
        isPadVisible = sideMenuState.isPadVisible,
        areScreensSwapped = sideMenuState.areScreensSwapped,
    )

    FrameGenQuickSettings(isOpen = isOpen)

    QuickMenuSection(tr("Controls")) {
        QuickMenuToggle(
            label = tr("Enable motion"),
            checked = sideMenuState.isMotionEnabled,
            onCheckedChange = { updateState(sideMenuState.copy(isMotionEnabled = it)) },
        )
        QuickMenuToggle(
            label = tr("Show input overlay"),
            checked = sideMenuState.isInputOverlayVisible,
            onCheckedChange = { updateState(sideMenuState.copy(isInputOverlayVisible = it)) },
        )
        QuickMenuAction(
            label = tr("Edit inputs"),
            enabled = sideMenuState.isInputOverlayVisible,
            onClick = onEditInputOverlay,
        )
        QuickMenuAction(
            label = tr("Reset input overlay"),
            enabled = sideMenuState.isInputOverlayVisible,
            onClick = onResetInputOverlay,
        )
        QuickMenuAction(
            label = tr("Emulated USB Devices"),
            onClick = onShowEmulatedUSBDevices,
        )
    }
}

// like the HOME Menu of the Wii U, the way back to the game, the settings and exit as big buttons
@Composable
private fun QuickMenuHeader(
    firstButton: FocusRequester,
    onBackToGame: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
) {
    Text(
        text = tr("Quick menu"),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickMenuTile(
            icon = R.drawable.ic_play_arrow,
            label = tr("Back to game"),
            highlighted = true,
            onClick = onBackToGame,
            modifier = Modifier
                .weight(1f)
                .focusRequester(firstButton),
        )
        QuickMenuTile(
            icon = R.drawable.ic_settings,
            label = tr("Settings"),
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
        QuickMenuTile(
            icon = R.drawable.ic_logout,
            label = tr("Exit game"),
            onClick = onQuit,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickMenuTile(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = LocalQuickMenuColors.current
    val contentColor = if (highlighted) colors.onAccent else colors.text
    QuickMenuSurface(
        onClick = onClick,
        modifier = modifier.height(88.dp),
        shape = QuickMenuCardShape,
        color = if (highlighted) colors.accent else colors.card,
        outlined = !highlighted,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (highlighted) contentColor else colors.accent,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = label,
                modifier = Modifier.padding(top = 6.dp),
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                lineHeight = 15.sp,
            )
        }
    }
}

// every button of the menu, with the cyan outline of the Wii U around the one a controller is on
@Composable
internal fun QuickMenuSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = QuickMenuItemShape,
    color: Color = Color.Transparent,
    outlined: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalQuickMenuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val border = when {
        isFocused -> BorderStroke(3.dp, colors.focus)
        outlined -> BorderStroke(1.dp, colors.outline)
        else -> null
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(color)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = colors.accent),
                enabled = enabled,
                onClick = onClick,
            ),
        content = content,
    )
}

@Composable
internal fun QuickMenuSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalQuickMenuColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            color = colors.secondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(QuickMenuCardShape)
                .background(colors.card)
                .border(1.dp, colors.outline, QuickMenuCardShape)
                .padding(4.dp),
            content = content,
        )
    }
}

@Composable
internal fun QuickMenuToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    description: String? = null,
) {
    val colors = LocalQuickMenuColors.current
    QuickMenuSurface(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(text = label, fontSize = 16.sp)
                if (description != null) {
                    Text(text = description, color = colors.secondaryText, fontSize = 13.sp)
                }
            }
            // the row is the button, the switch only shows the state
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.accent,
                    checkedBorderColor = colors.accent,
                    uncheckedThumbColor = colors.secondaryText,
                    uncheckedTrackColor = colors.control,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }
    }
}

@Composable
internal fun QuickMenuAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = LocalQuickMenuColors.current
    QuickMenuSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, modifier = Modifier.weight(1f), fontSize = 16.sp)
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// a row of choices, the picked one filled like the selected tab of the Wii U settings
@Composable
internal fun <T> QuickMenuChoices(
    label: String,
    choices: List<T>,
    selected: T,
    choiceToString: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalQuickMenuColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
            color = colors.secondaryText,
            fontSize = 14.sp,
        )
        Row(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clip(PillShape)
                .background(colors.control)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (choice in choices) {
                val isSelected = choice == selected
                QuickMenuSurface(
                    onClick = { onSelect(choice) },
                    enabled = enabled,
                    shape = PillShape,
                    color = if (isSelected) colors.accent else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .semantics { this.selected = isSelected },
                ) {
                    Text(
                        text = choiceToString(choice),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 4.dp),
                        color = if (isSelected) colors.onAccent else colors.text,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// buttons instead of a slider, they are easier to use with a controller
@Composable
internal fun QuickMenuStepper(
    label: String,
    value: String,
    enabled: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            fontSize = 16.sp,
        )
        StepButton(symbol = "−", enabled = enabled && canDecrease, onClick = onDecrease)
        Text(
            text = value,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        StepButton(symbol = "+", enabled = enabled && canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    QuickMenuSurface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = LocalQuickMenuColors.current.control,
        modifier = Modifier.size(44.dp),
    ) {
        Text(text = symbol, modifier = Modifier.align(Alignment.Center), fontSize = 22.sp)
    }
}

@Composable
internal fun QuickMenuButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    QuickMenuSurface(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        color = LocalQuickMenuColors.current.control,
        modifier = modifier.height(44.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun QuickMenuNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        color = LocalQuickMenuColors.current.secondaryText,
        fontSize = 13.sp,
    )
}
