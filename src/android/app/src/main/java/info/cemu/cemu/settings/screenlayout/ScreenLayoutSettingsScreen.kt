package info.cemu.cemu.settings.screenlayout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.android.display.SecondScreenInfo
import info.cemu.cemu.common.android.display.rememberSecondScreen
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.SingleSelection
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.components.Toggle
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlin.math.roundToInt

internal const val WII_U_ASPECT_RATIO = 16f / 9f
private const val OVERLAY_TEXT_SCALE_STEPS = 9

// shape used for the previews while no second screen is connected, the bottom screen of the AYN Thor
internal val FallbackSecondScreen = SecondScreenInfo(name = "", width = 1240, height = 1080)
private val FallbackMainScreen = SecondScreenInfo(name = "", width = 1920, height = 1080)

internal val AlignmentChoices = listOf(
    NativeSettings.PadScreenAlignment.TOP,
    NativeSettings.PadScreenAlignment.CENTER,
    NativeSettings.PadScreenAlignment.BOTTOM,
)

// the toolbar is the GamePad panel overlay drawn inside the black bars around the game image
internal fun isToolbarEnabled() =
    NativeSettings.getOverlayPosition() != NativeSettings.OverlayScreenPosition.DISABLED &&
        NativeSettings.getOverlayStyle() == NativeSettings.OverlayStyle.GAMEPAD &&
        NativeSettings.getOverlayPlacement() == NativeSettings.OverlayPlacement.BLACK_BARS

// the overlay prefers the bar on the side of its position, so put it opposite to the image
private fun toolbarPositionFor(alignment: Int) =
    if (alignment == NativeSettings.PadScreenAlignment.TOP) NativeSettings.OverlayScreenPosition.BOTTOM_CENTER
    else NativeSettings.OverlayScreenPosition.TOP_CENTER

private fun enableToolbar(overlayScreen: Int, alignment: Int) {
    NativeSettings.setOverlayStyle(NativeSettings.OverlayStyle.GAMEPAD)
    NativeSettings.setOverlayPlacement(NativeSettings.OverlayPlacement.BLACK_BARS)
    NativeSettings.setOverlayScreen(overlayScreen)
    NativeSettings.setOverlayPosition(toolbarPositionFor(alignment))
}

// the toolbar follows the screen the GamePad is on with a second screen, otherwise the TV image unless chosen
internal fun toolbarScreenFor(useSecondScreen: Boolean, isPadVisible: Boolean, chosenScreen: Int) = when {
    useSecondScreen -> NativeSettings.OverlayScreen.PAD
    !isPadVisible -> NativeSettings.OverlayScreen.TV
    chosenScreen == NativeSettings.OverlayScreen.PAD -> NativeSettings.OverlayScreen.PAD
    else -> NativeSettings.OverlayScreen.TV
}

internal fun applyToolbarSettings(enabled: Boolean, screen: Int, alignment: Int) {
    if (!enabled) {
        NativeSettings.setOverlayPosition(NativeSettings.OverlayScreenPosition.DISABLED)
        return
    }
    enableToolbar(screen, if (screen == NativeSettings.OverlayScreen.PAD) alignment else NativeSettings.PadScreenAlignment.CENTER)
}

// what the toolbar can show. The settings list them as switches and the setup wizard as chips with the short label
internal class ToolbarItem(
    val label: String,
    val shortLabel: String,
    val description: String,
    val isEnabled: () -> Boolean,
    val setEnabled: (Boolean) -> Unit,
)

internal fun toolbarInformationItems() = listOf(
    ToolbarItem(
        label = tr("Shader compilation progress"),
        shortLabel = tr("Shaders"),
        description = tr("Shows the shader compilation progress in the toolbar instead of as a notification"),
        isEnabled = NativeSettings::isOverlayShaderCompilingEnabled,
        setEnabled = NativeSettings::setOverlayShaderCompilingEnabled,
    ),
    ToolbarItem(
        label = tr("FPS"),
        shortLabel = tr("FPS"),
        description = tr("The number of frames per second. Average over last 5 seconds"),
        isEnabled = NativeSettings::isOverlayFPSEnabled,
        setEnabled = NativeSettings::setOverlayFPSEnabled,
    ),
    ToolbarItem(
        label = tr("Draw calls per frame"),
        shortLabel = tr("Draw calls"),
        description = tr("The number of draw calls per frame. Average over last 5 seconds"),
        isEnabled = NativeSettings::isOverlayDrawCallsPerFrameEnabled,
        setEnabled = NativeSettings::setOverlayDrawCallsPerFrameEnabled,
    ),
    ToolbarItem(
        label = tr("CPU usage"),
        shortLabel = tr("CPU"),
        description = tr("CPU usage of Cemu in percent"),
        isEnabled = NativeSettings::isOverlayCPUUsageEnabled,
        setEnabled = NativeSettings::setOverlayCPUUsageEnabled,
    ),
    ToolbarItem(
        label = tr("RAM usage"),
        shortLabel = tr("RAM"),
        description = tr("Cemu RAM usage in MB"),
        isEnabled = NativeSettings::isOverlayRAMUsageEnabled,
        setEnabled = NativeSettings::setOverlayRAMUsageEnabled,
    ),
    ToolbarItem(
        label = tr("GPU usage"),
        shortLabel = tr("GPU"),
        description = tr("GPU load and clock speed, if the device exposes them"),
        isEnabled = NativeSettings::isOverlayGPUUsageEnabled,
        setEnabled = NativeSettings::setOverlayGPUUsageEnabled,
    ),
    ToolbarItem(
        label = tr("Device temperature"),
        shortLabel = tr("Temperature"),
        description = tr("CPU and GPU temperature, falls back to the battery temperature"),
        isEnabled = NativeSettings::isOverlayTemperatureEnabled,
        setEnabled = NativeSettings::setOverlayTemperatureEnabled,
    ),
    ToolbarItem(
        label = tr("Battery"),
        shortLabel = tr("Battery"),
        description = tr("Battery level and charging state"),
        isEnabled = NativeSettings::isOverlayBatteryEnabled,
        setEnabled = NativeSettings::setOverlayBatteryEnabled,
    ),
)

internal fun toolbarButtonItems() = listOf(
    ToolbarItem(
        label = tr("Frame generation button"),
        shortLabel = tr("Frame generation"),
        description = tr("Adds a button that turns frame generation on and off during the game. This does not enable frame generation by itself"),
        isEnabled = NativeSettings::isOverlayFrameGenActionEnabled,
        setEnabled = NativeSettings::setOverlayFrameGenActionEnabled,
    ),
    ToolbarItem(
        label = tr("Frame generation mode button"),
        shortLabel = tr("Frame generation mode"),
        description = tr("Adds a button that cycles the frame generation multiplier between x2, x3 and x4"),
        isEnabled = NativeSettings::isOverlayFrameGenModeActionEnabled,
        setEnabled = NativeSettings::setOverlayFrameGenModeActionEnabled,
    ),
    ToolbarItem(
        label = tr("Settings button"),
        shortLabel = tr("Settings"),
        description = tr("Adds a button that pauses the game and opens the emulator settings"),
        isEnabled = NativeSettings::isOverlaySettingsActionEnabled,
        setEnabled = NativeSettings::setOverlaySettingsActionEnabled,
    ),
    ToolbarItem(
        label = tr("Pause button"),
        shortLabel = tr("Pause"),
        description = tr("Adds a button that pauses and resumes the game"),
        isEnabled = NativeSettings::isOverlayPauseActionEnabled,
        setEnabled = NativeSettings::setOverlayPauseActionEnabled,
    ),
    ToolbarItem(
        label = tr("Quick menu button"),
        shortLabel = tr("Quick menu"),
        description = tr("Adds a button that opens the in-game quick menu with motion, screens, input overlay and exit"),
        isEnabled = NativeSettings::isOverlayMenuActionEnabled,
        setEnabled = NativeSettings::setOverlayMenuActionEnabled,
    ),
)

private fun landscapeScreen(width: Int, height: Int) =
    SecondScreenInfo(name = "", width = maxOf(width, height), height = minOf(width, height))

@Composable
internal fun rememberMainScreen(): SecondScreenInfo {
    val context = LocalContext.current
    return remember(context) {
        DisplayUtils.getInternalDisplay(context)?.mode
            ?.let { landscapeScreen(it.physicalWidth, it.physicalHeight) }
            ?: FallbackMainScreen
    }
}

// where the GamePad goes and the toolbar that uses the black bars around the game, for one or two screens
@Composable
fun ScreenLayoutSettingsScreen(
    navigateBack: () -> Unit,
    viewModel: ScreenLayoutSettingsViewModel = viewModel(),
) {
    val emulationSettings by viewModel.emulationSettings.collectAsState()
    val detectedScreen = rememberSecondScreen()
    val mainScreen = rememberMainScreen()
    val useSecondScreen = emulationSettings.isPadOnExternalDisplay

    var alignment by rememberSaveable { mutableIntStateOf(NativeSettings.getPadScreenAlignment()) }
    var toolbarEnabled by rememberSaveable { mutableStateOf(isToolbarEnabled()) }
    var toolbarScreen by rememberSaveable { mutableIntStateOf(NativeSettings.getOverlayScreen()) }

    val effectiveToolbarScreen = toolbarScreenFor(useSecondScreen, emulationSettings.isPadVisible, toolbarScreen)

    fun applyToolbar(enabled: Boolean = toolbarEnabled, screen: Int = effectiveToolbarScreen, align: Int = alignment) =
        applyToolbarSettings(enabled = enabled, screen = screen, alignment = align)

    // the first option of the layout section, so the previews below show what the toolbar takes
    val toolbarToggle: @Composable () -> Unit = {
        Toggle(
            label = tr("Toolbar in the black bars"),
            description = tr("Uses the empty space around the game for a toolbar with performance info and touch buttons. It never covers the game"),
            checked = toolbarEnabled,
            onCheckedChanged = {
                toolbarEnabled = it
                applyToolbar(enabled = it)
            },
        )
    }

    ScreenContent(
        appBarText = tr("Screen layout"),
        navigateBack = navigateBack,
    ) {
        ScreenStatusCard(detectedScreen)
        Toggle(
            label = tr("Use second screen"),
            description = tr("Shows the Wii U GamePad on the second screen of devices like the AYN Thor"),
            checked = useSecondScreen,
            onCheckedChanged = {
                viewModel.setSecondScreenEnabled(it)
                applyToolbar(screen = if (it) NativeSettings.OverlayScreen.PAD else NativeSettings.OverlayScreen.TV)
            },
        )

        if (useSecondScreen) {
            SecondScreenLayout(
                screen = detectedScreen ?: FallbackSecondScreen,
                isDetected = detectedScreen != null,
                isRotatedLeft = emulationSettings.isExternalScreenRotatedLeft,
                onRotatedLeftChanged = viewModel::setExternalScreenRotatedLeft,
                alignment = alignment,
                showToolbar = toolbarEnabled,
                toolbarToggle = toolbarToggle,
                onAlignmentChanged = {
                    alignment = it
                    NativeSettings.setPadScreenAlignment(it)
                    applyToolbar(align = it)
                },
            )
        } else {
            SingleScreenLayout(
                screen = mainScreen,
                isPadVisible = emulationSettings.isPadVisible,
                onPadVisibleChanged = {
                    viewModel.setPadVisible(it)
                    applyToolbar(screen = if (it) effectiveToolbarScreen else NativeSettings.OverlayScreen.TV)
                },
                gamePadPosition = emulationSettings.gamePadPosition,
                onGamePadPositionChanged = viewModel::setGamePadPosition,
                showToolbar = toolbarEnabled,
                toolbarOnPad = effectiveToolbarScreen == NativeSettings.OverlayScreen.PAD,
                toolbarToggle = toolbarToggle,
            )
        }

        if (toolbarEnabled) {
            Header(tr("Toolbar style"))
            SingleSelection(
                label = tr("Theme"),
                initialChoice = NativeSettings::getOverlayTheme,
                onChoiceChanged = NativeSettings::setOverlayTheme,
                choiceToString = { toolbarThemeToString(it) },
                choices = listOf(NativeSettings.OverlayTheme.BLACK, NativeSettings.OverlayTheme.WHITE),
            )
        }
        if (toolbarEnabled && !useSecondScreen && emulationSettings.isPadVisible) {
            SingleSelection(
                label = tr("Toolbar goes around"),
                choice = effectiveToolbarScreen,
                choices = listOf(NativeSettings.OverlayScreen.TV, NativeSettings.OverlayScreen.PAD),
                choiceToString = {
                    if (it == NativeSettings.OverlayScreen.PAD) tr("The GamePad image") else tr("The TV image")
                },
                onChoiceChanged = {
                    toolbarScreen = it
                    applyToolbar(screen = it)
                },
            )
        }
        if (toolbarEnabled) {
            ToolbarSettings()
        }
    }
}

@Composable
private fun SecondScreenLayout(
    screen: SecondScreenInfo,
    isDetected: Boolean,
    isRotatedLeft: Boolean,
    onRotatedLeftChanged: (Boolean) -> Unit,
    alignment: Int,
    showToolbar: Boolean,
    toolbarToggle: @Composable () -> Unit,
    onAlignmentChanged: (Int) -> Unit,
) {
    // a screen wider than the GamePad gets the bars on the sides instead, there is nothing to align then
    val isLetterboxed = screen.width.toFloat() / screen.height < WII_U_ASPECT_RATIO

    Toggle(
        label = tr("Rotate second screen left"),
        description = tr("Turn this on if the GamePad image shows up sideways on the second screen"),
        checked = isRotatedLeft,
        onCheckedChanged = onRotatedLeftChanged,
    )

    Header(tr("Layout"))
    toolbarToggle()
    InfoText(secondScreenExplanation(screen, isDetected, isLetterboxed))
    if (!isLetterboxed) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (choice in AlignmentChoices) {
            PreviewChoice(
                modifier = Modifier.weight(1f),
                screen = screen,
                label = alignmentToString(choice),
                selected = alignment == choice,
                onClick = { onAlignmentChanged(choice) },
            ) { paint ->
                drawSecondScreenPreview(choice, showToolbar, paint)
            }
        }
    }
    if (alignment == NativeSettings.PadScreenAlignment.CENTER) {
        InfoText(tr("Centered splits the free space into two thin bars, so the toolbar has less room. Top or Bottom keep it in one big bar."))
    }
}

@Composable
private fun SingleScreenLayout(
    screen: SecondScreenInfo,
    isPadVisible: Boolean,
    onPadVisibleChanged: (Boolean) -> Unit,
    gamePadPosition: GamePadPosition,
    onGamePadPositionChanged: (GamePadPosition) -> Unit,
    showToolbar: Boolean,
    toolbarOnPad: Boolean,
    toolbarToggle: @Composable () -> Unit,
) {
    Header(tr("Layout"))
    toolbarToggle()
    InfoText(singleScreenExplanation(screen, isPadVisible))
    Toggle(
        label = tr("Show the GamePad on this screen too"),
        description = tr("Splits the screen between the TV image and the GamePad image, for games that use both"),
        checked = isPadVisible,
        onCheckedChanged = onPadVisibleChanged,
    )
    if (!isPadVisible) {
        PreviewCard(screen) { paint ->
            drawSingleScreenPreview(null, showToolbar, false, paint)
        }
        return
    }
    SubHeader(tr("GamePad position"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (position in GamePadPosition.entries) {
            PreviewChoice(
                modifier = Modifier.weight(1f),
                screen = screen,
                label = gamePadPositionToString(position),
                selected = gamePadPosition == position,
                onClick = { onGamePadPositionChanged(position) },
            ) { paint ->
                drawSingleScreenPreview(position, showToolbar, toolbarOnPad, paint)
            }
        }
    }
}

@Composable
private fun ToolbarSettings() {
    Slider(
        label = tr("Size"),
        initialValue = NativeSettings::getOverlayTextScalePercentage,
        steps = OVERLAY_TEXT_SCALE_STEPS,
        valueFrom = NativeSettings.OVERLAY_TEXT_SCALE_MIN,
        valueTo = NativeSettings.OVERLAY_TEXT_SCALE_MAX,
        onValueChange = NativeSettings::setOverlayTextScalePercentage,
        labelFormatter = { "$it%" }
    )

    // these only pick what the toolbar shows, the features themselves are set up in their own settings
    Header(tr("Toolbar contents"))
    InfoText(tr("Choose what appears in the toolbar. These switches only add or remove items from the toolbar, they do not turn the features themselves on or off."))

    SubHeader(tr("Information"))
    InfoText(tr("Readouts shown in the toolbar while you play."))
    for (item in toolbarInformationItems()) {
        ToolbarItemToggle(item)
    }

    SubHeader(tr("Buttons"))
    InfoText(tr("Touch buttons added to the toolbar. Turning one on here only places the button in the toolbar, you tap it during the game to use it."))
    for (item in toolbarButtonItems()) {
        ToolbarItemToggle(item)
    }
}

@Composable
private fun ToolbarItemToggle(item: ToolbarItem) {
    Toggle(
        label = item.label,
        description = item.description,
        initialCheckedState = item.isEnabled,
        onCheckedChanged = item.setEnabled,
    )
}

@Composable
private fun ScreenStatusCard(detectedScreen: SecondScreenInfo?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (detectedScreen != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (detectedScreen != null) tr("Second screen detected") else tr("One screen"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                color = LocalContentColor.current.copy(alpha = 0.7f),
                fontSize = 14.sp,
                text = if (detectedScreen != null) {
                    tr("{0} · {1}x{2}", detectedScreen.name, detectedScreen.width.toString(), detectedScreen.height.toString())
                } else {
                    tr("No second screen is connected right now. The TV and GamePad images share this screen")
                },
            )
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = LocalContentColor.current.copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun freePercent(screenRatio: Float, imageRatio: Float): String {
    val imageFraction = if (screenRatio < imageRatio) screenRatio / imageRatio else imageRatio / screenRatio
    return ((1f - imageFraction) * 100).roundToInt().toString()
}

internal fun secondScreenExplanation(screen: SecondScreenInfo, isDetected: Boolean, isLetterboxed: Boolean): String {
    val screenRatio = screen.width.toFloat() / screen.height
    val shape = if (isDetected) {
        tr("Your second screen is {0}x{1}, a {2}:1 shape.", screen.width.toString(), screen.height.toString(), "%.2f".format(screenRatio))
    } else {
        tr("Second screens are usually not the same shape as the GamePad.")
    }
    val free = freePercent(screenRatio, WII_U_ASPECT_RATIO)
    val bars = if (isLetterboxed) {
        tr("The Wii U GamePad is 16:9 (1.78:1), so the game image cannot fill it and about {0}% of the screen is left as black bars above and below. Choose where the image goes:", free)
    } else {
        tr("The Wii U GamePad is 16:9 (1.78:1), so about {0}% of the screen is left as black bars on the sides. The toolbar goes into one of them.", free)
    }
    return "$shape $bars"
}

internal fun singleScreenExplanation(screen: SecondScreenInfo, isPadVisible: Boolean): String {
    val screenRatio = screen.width.toFloat() / screen.height
    return if (isPadVisible) {
        tr("With the TV and GamePad images sharing this {0}x{1} screen, both are 16:9 and get smaller, which leaves wide black bars around them. The toolbar can use that space.", screen.width.toString(), screen.height.toString())
    } else {
        tr("This screen is {0}x{1}, a {2}:1 shape. The Wii U image is 16:9 (1.78:1), so about {3}% of it is left as black bars. The toolbar can use that space.", screen.width.toString(), screen.height.toString(), "%.2f".format(screenRatio), freePercent(screenRatio, WII_U_ASPECT_RATIO))
    }
}

internal fun alignmentToString(alignment: Int) = when (alignment) {
    NativeSettings.PadScreenAlignment.CENTER -> tr("Center")
    NativeSettings.PadScreenAlignment.TOP -> tr("Top")
    NativeSettings.PadScreenAlignment.BOTTOM -> tr("Bottom")
    else -> throw IllegalArgumentException("Invalid GamePad screen alignment: $alignment")
}

private fun toolbarThemeToString(theme: Int) = when (theme) {
    NativeSettings.OverlayTheme.BLACK -> tr("Black, like the Wii U Deluxe")
    NativeSettings.OverlayTheme.WHITE -> tr("White, like the Wii U Basic")
    else -> throw IllegalArgumentException("Invalid toolbar theme: $theme")
}

private fun gamePadPositionToString(position: GamePadPosition) = when (position) {
    GamePadPosition.ABOVE -> tr("Above")
    GamePadPosition.BELOW -> tr("Below")
    GamePadPosition.LEFT -> tr("Left")
    GamePadPosition.RIGHT -> tr("Right")
}

// what the previews are drawn with, the game image is white with a label so it reads as the gameplay
internal class PreviewPaint(
    val toolbarColor: Color,
    val textMeasurer: TextMeasurer,
    val gameplayLabel: String,
    val padLabel: String,
    val toolbarTiles: Int,
)

private val PreviewGameplayColor = Color.White
internal val PreviewPadColor = Color(0xFFDCDCE2)
private val PreviewLabelColor = Color(0xFF202024)

// toolbarTiles is how many tiles the toolbar is drawn with
@Composable
internal fun PreviewCanvas(
    screen: SecondScreenInfo,
    toolbarColor: Color = MaterialTheme.colorScheme.tertiary,
    toolbarTiles: Int = 4,
    draw: DrawScope.(PreviewPaint) -> Unit,
) {
    val paint = PreviewPaint(
        toolbarColor = toolbarColor,
        textMeasurer = rememberTextMeasurer(),
        gameplayLabel = tr("Gameplay"),
        padLabel = tr("GamePad"),
        toolbarTiles = toolbarTiles,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(screen.width.toFloat() / screen.height)
    ) {
        drawRoundRect(color = Color.Black, cornerRadius = CornerRadius(4.dp.toPx()))
        draw(paint)
    }
}

@Composable
private fun PreviewCard(screen: SecondScreenInfo, draw: DrawScope.(PreviewPaint) -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            PreviewCanvas(screen = screen, draw = draw)
        }
    }
}

@Composable
private fun PreviewChoice(
    modifier: Modifier,
    screen: SecondScreenInfo,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    draw: DrawScope.(PreviewPaint) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        onClick = onClick,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PreviewCanvas(screen = screen, draw = draw)
            Text(
                text = label,
                modifier = Modifier.padding(top = 8.dp),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

// a 16:9 image (or another shape) inside the area, centered unless aligned to the top or bottom
internal fun fitImage(
    area: Rect,
    alignment: Int = NativeSettings.PadScreenAlignment.CENTER,
    aspectRatio: Float = WII_U_ASPECT_RATIO,
): Rect {
    var width = area.width
    var height = width / aspectRatio
    if (height > area.height) {
        height = area.height
        width = height * aspectRatio
    }
    val left = area.left + (area.width - width) / 2
    val top = when (alignment) {
        NativeSettings.PadScreenAlignment.TOP -> area.top
        NativeSettings.PadScreenAlignment.BOTTOM -> area.bottom - height
        else -> area.top + (area.height - height) / 2
    }
    return Rect(left, top, left + width, top + height)
}

// the biggest black bar next to the image, the overlay picks the same one
internal fun biggestBar(area: Rect, image: Rect): Rect = listOf(
    Rect(area.left, area.top, area.right, image.top),
    Rect(area.left, image.bottom, area.right, area.bottom),
    Rect(area.left, area.top, image.left, area.bottom),
    Rect(image.right, area.top, area.right, area.bottom),
).maxBy { it.width * it.height }

internal fun DrawScope.drawToolbar(bar: Rect, toolbarColor: Color, tiles: Int) {
    val inset = minOf(minOf(bar.width, bar.height) * 0.18f, 4.dp.toPx())
    val toolbar = Rect(bar.left + inset, bar.top + inset, bar.right - inset, bar.bottom - inset)
    if (toolbar.width <= 0f || toolbar.height <= 0f) {
        return
    }
    drawRoundRect(
        color = toolbarColor.copy(alpha = 0.35f),
        topLeft = toolbar.topLeft,
        size = toolbar.size,
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    // a few tiles like the real toolbar, in a row or a column depending on the bar
    val horizontal = toolbar.width >= toolbar.height
    val length = if (horizontal) toolbar.width else toolbar.height
    val thickness = (if (horizontal) toolbar.height else toolbar.width) - inset * 2
    val tileLength = (length - inset * (tiles + 1)) / tiles
    if (tiles <= 0 || thickness <= 0f || tileLength <= 0f) {
        return
    }
    for (i in 0 until tiles) {
        val along = inset + i * (tileLength + inset)
        drawRoundRect(
            color = toolbarColor,
            topLeft = if (horizontal) Offset(toolbar.left + along, toolbar.top + inset) else Offset(toolbar.left + inset, toolbar.top + along),
            size = if (horizontal) Size(tileLength, thickness) else Size(thickness, tileLength),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

// the game image with its label centered, as big as the image allows
internal fun DrawScope.drawGameImage(image: Rect, color: Color, label: String, paint: PreviewPaint) {
    drawRect(color = color, topLeft = image.topLeft, size = image.size)
    val style = TextStyle(color = PreviewLabelColor, fontWeight = FontWeight.Medium, fontSize = (image.height * 0.2f).toSp())
    var text = paint.textMeasurer.measure(label, style)
    val maxWidth = image.width * 0.85f
    if (text.size.width > maxWidth) {
        text = paint.textMeasurer.measure(label, style.copy(fontSize = style.fontSize * (maxWidth / text.size.width)))
    }
    drawText(text, topLeft = Offset(image.center.x - text.size.width / 2f, image.center.y - text.size.height / 2f))
}

internal fun DrawScope.drawSecondScreenPreview(alignment: Int, showToolbar: Boolean, paint: PreviewPaint) {
    val area = Rect(Offset.Zero, size)
    val image = fitImage(area, alignment)
    drawGameImage(image, PreviewGameplayColor, paint.gameplayLabel, paint)
    if (showToolbar) {
        drawToolbar(biggestBar(area, image), paint.toolbarColor, paint.toolbarTiles)
    }
}

// position is null when only the TV image is shown
internal fun DrawScope.drawSingleScreenPreview(
    position: GamePadPosition?,
    showToolbar: Boolean,
    toolbarOnPad: Boolean,
    paint: PreviewPaint,
) {
    val full = Rect(Offset.Zero, size)
    if (position == null) {
        val image = fitImage(full)
        drawGameImage(image, PreviewGameplayColor, paint.gameplayLabel, paint)
        if (showToolbar) {
            drawToolbar(biggestBar(full, image), paint.toolbarColor, paint.toolbarTiles)
        }
        return
    }
    val first = if (position.isVertical()) Rect(0f, 0f, size.width, size.height / 2) else Rect(0f, 0f, size.width / 2, size.height)
    val second = if (position.isVertical()) Rect(0f, size.height / 2, size.width, size.height) else Rect(size.width / 2, 0f, size.width, size.height)
    val (tvArea, padArea) = if (position.appearsAfterTV()) first to second else second to first
    val tvImage = fitImage(tvArea)
    val padImage = fitImage(padArea)
    drawGameImage(tvImage, PreviewGameplayColor, paint.gameplayLabel, paint)
    // the GamePad image a light gray to tell them apart
    drawGameImage(padImage, PreviewPadColor, paint.padLabel, paint)
    if (showToolbar) {
        drawToolbar(if (toolbarOnPad) biggestBar(padArea, padImage) else biggestBar(tvArea, tvImage), paint.toolbarColor, paint.toolbarTiles)
    }
}
