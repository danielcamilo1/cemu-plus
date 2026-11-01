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
import androidx.compose.ui.text.font.FontWeight
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

private const val WII_U_ASPECT_RATIO = 16f / 9f
private const val OVERLAY_TEXT_SCALE_STEPS = 9

// shape used for the previews while no second screen is connected, the bottom screen of the AYN Thor
private val FallbackSecondScreen = SecondScreenInfo(name = "", width = 1240, height = 1080)
private val FallbackMainScreen = SecondScreenInfo(name = "", width = 1920, height = 1080)

private val AlignmentChoices = listOf(
    NativeSettings.PadScreenAlignment.TOP,
    NativeSettings.PadScreenAlignment.CENTER,
    NativeSettings.PadScreenAlignment.BOTTOM,
)

// the toolbar is the GamePad panel overlay drawn inside the black bars around the game image
private fun isToolbarEnabled() =
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

private fun landscapeScreen(width: Int, height: Int) =
    SecondScreenInfo(name = "", width = maxOf(width, height), height = minOf(width, height))

@Composable
private fun rememberMainScreen(): SecondScreenInfo {
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

    // the toolbar follows the screen the GamePad is on with a second screen, otherwise the TV image unless chosen
    val effectiveToolbarScreen = when {
        useSecondScreen -> NativeSettings.OverlayScreen.PAD
        !emulationSettings.isPadVisible -> NativeSettings.OverlayScreen.TV
        toolbarScreen == NativeSettings.OverlayScreen.PAD -> NativeSettings.OverlayScreen.PAD
        else -> NativeSettings.OverlayScreen.TV
    }

    fun applyToolbar(enabled: Boolean = toolbarEnabled, screen: Int = effectiveToolbarScreen, align: Int = alignment) {
        if (!enabled) {
            NativeSettings.setOverlayPosition(NativeSettings.OverlayScreenPosition.DISABLED)
            return
        }
        enableToolbar(screen, if (screen == NativeSettings.OverlayScreen.PAD) align else NativeSettings.PadScreenAlignment.CENTER)
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
            )
        }

        Header(tr("Toolbar"))
        Toggle(
            label = tr("Toolbar in the black bars"),
            description = tr("Uses the empty space around the game for a toolbar with performance info and touch buttons. It never covers the game"),
            checked = toolbarEnabled,
            onCheckedChanged = {
                toolbarEnabled = it
                applyToolbar(enabled = it)
            },
        )
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
            ) { imageColor, toolbarColor ->
                drawSecondScreenPreview(choice, showToolbar, imageColor, toolbarColor)
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
) {
    Header(tr("Layout"))
    InfoText(singleScreenExplanation(screen, isPadVisible))
    Toggle(
        label = tr("Show the GamePad on this screen too"),
        description = tr("Splits the screen between the TV image and the GamePad image, for games that use both"),
        checked = isPadVisible,
        onCheckedChanged = onPadVisibleChanged,
    )
    if (!isPadVisible) {
        PreviewCard(screen) { imageColor, toolbarColor ->
            drawSingleScreenPreview(null, showToolbar, false, imageColor, toolbarColor)
        }
        return
    }
    Text(
        text = tr("GamePad position"),
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(8.dp),
    )
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
            ) { imageColor, toolbarColor ->
                drawSingleScreenPreview(position, showToolbar, toolbarOnPad, imageColor, toolbarColor)
            }
        }
    }
}

@Composable
private fun ToolbarSettings() {
    Header(tr("Toolbar style"))
    Slider(
        label = tr("Size"),
        initialValue = NativeSettings::getOverlayTextScalePercentage,
        steps = OVERLAY_TEXT_SCALE_STEPS,
        valueFrom = NativeSettings.OVERLAY_TEXT_SCALE_MIN,
        valueTo = NativeSettings.OVERLAY_TEXT_SCALE_MAX,
        onValueChange = NativeSettings::setOverlayTextScalePercentage,
        labelFormatter = { "$it%" }
    )
    Toggle(
        label = tr("Shader compilation progress"),
        description = tr("Shows the shader compilation progress in the toolbar instead of as a notification"),
        initialCheckedState = NativeSettings::isOverlayShaderCompilingEnabled,
        onCheckedChanged = NativeSettings::setOverlayShaderCompilingEnabled,
    )

    Header(tr("Information"))
    Toggle(
        label = tr("FPS"),
        description = tr("The number of frames per second. Average over last 5 seconds"),
        initialCheckedState = NativeSettings::isOverlayFPSEnabled,
        onCheckedChanged = NativeSettings::setOverlayFPSEnabled,
    )
    Toggle(
        label = tr("Draw calls per frame"),
        description = tr("The number of draw calls per frame. Average over last 5 seconds"),
        initialCheckedState = NativeSettings::isOverlayDrawCallsPerFrameEnabled,
        onCheckedChanged = NativeSettings::setOverlayDrawCallsPerFrameEnabled,
    )
    Toggle(
        label = tr("CPU usage"),
        description = tr("CPU usage of Cemu in percent"),
        initialCheckedState = NativeSettings::isOverlayCPUUsageEnabled,
        onCheckedChanged = NativeSettings::setOverlayCPUUsageEnabled,
    )
    Toggle(
        label = tr("RAM usage"),
        description = tr("Cemu RAM usage in MB"),
        initialCheckedState = NativeSettings::isOverlayRAMUsageEnabled,
        onCheckedChanged = NativeSettings::setOverlayRAMUsageEnabled,
    )
    Toggle(
        label = tr("GPU usage"),
        description = tr("GPU load and clock speed, if the device exposes them"),
        initialCheckedState = NativeSettings::isOverlayGPUUsageEnabled,
        onCheckedChanged = NativeSettings::setOverlayGPUUsageEnabled,
    )
    Toggle(
        label = tr("Device temperature"),
        description = tr("CPU and GPU temperature, falls back to the battery temperature"),
        initialCheckedState = NativeSettings::isOverlayTemperatureEnabled,
        onCheckedChanged = NativeSettings::setOverlayTemperatureEnabled,
    )
    Toggle(
        label = tr("Battery"),
        description = tr("Battery level and charging state"),
        initialCheckedState = NativeSettings::isOverlayBatteryEnabled,
        onCheckedChanged = NativeSettings::setOverlayBatteryEnabled,
    )

    Header(tr("Buttons"))
    Toggle(
        label = tr("Frame generation"),
        description = tr("Turns frame generation on and off"),
        initialCheckedState = NativeSettings::isOverlayFrameGenActionEnabled,
        onCheckedChanged = NativeSettings::setOverlayFrameGenActionEnabled,
    )
    Toggle(
        label = tr("Frame generation mode"),
        description = tr("Cycles the frame generation multiplier between x2, x3 and x4"),
        initialCheckedState = NativeSettings::isOverlayFrameGenModeActionEnabled,
        onCheckedChanged = NativeSettings::setOverlayFrameGenModeActionEnabled,
    )
    Toggle(
        label = tr("Settings"),
        description = tr("Pauses the game and opens the emulator settings"),
        initialCheckedState = NativeSettings::isOverlaySettingsActionEnabled,
        onCheckedChanged = NativeSettings::setOverlaySettingsActionEnabled,
    )
    Toggle(
        label = tr("Pause"),
        description = tr("Pauses and resumes the game"),
        initialCheckedState = NativeSettings::isOverlayPauseActionEnabled,
        onCheckedChanged = NativeSettings::setOverlayPauseActionEnabled,
    )
    Toggle(
        label = tr("Quick menu"),
        description = tr("Opens the in-game quick menu with motion, screens, input overlay and exit"),
        initialCheckedState = NativeSettings::isOverlayMenuActionEnabled,
        onCheckedChanged = NativeSettings::setOverlayMenuActionEnabled,
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

private fun secondScreenExplanation(screen: SecondScreenInfo, isDetected: Boolean, isLetterboxed: Boolean): String {
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

private fun singleScreenExplanation(screen: SecondScreenInfo, isPadVisible: Boolean): String {
    val screenRatio = screen.width.toFloat() / screen.height
    return if (isPadVisible) {
        tr("With the TV and GamePad images sharing this {0}x{1} screen, both are 16:9 and get smaller, which leaves wide black bars around them. The toolbar can use that space.", screen.width.toString(), screen.height.toString())
    } else {
        tr("This screen is {0}x{1}, a {2}:1 shape. The Wii U image is 16:9 (1.78:1), so about {3}% of it is left as black bars. The toolbar can use that space.", screen.width.toString(), screen.height.toString(), "%.2f".format(screenRatio), freePercent(screenRatio, WII_U_ASPECT_RATIO))
    }
}

private fun alignmentToString(alignment: Int) = when (alignment) {
    NativeSettings.PadScreenAlignment.CENTER -> tr("Center")
    NativeSettings.PadScreenAlignment.TOP -> tr("Top")
    NativeSettings.PadScreenAlignment.BOTTOM -> tr("Bottom")
    else -> throw IllegalArgumentException("Invalid GamePad screen alignment: $alignment")
}

private fun gamePadPositionToString(position: GamePadPosition) = when (position) {
    GamePadPosition.ABOVE -> tr("Above")
    GamePadPosition.BELOW -> tr("Below")
    GamePadPosition.LEFT -> tr("Left")
    GamePadPosition.RIGHT -> tr("Right")
}

@Composable
private fun PreviewCanvas(screen: SecondScreenInfo, draw: DrawScope.(imageColor: Color, toolbarColor: Color) -> Unit) {
    val imageColor = MaterialTheme.colorScheme.primary
    val toolbarColor = MaterialTheme.colorScheme.tertiary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(screen.width.toFloat() / screen.height)
    ) {
        drawRoundRect(color = Color.Black, cornerRadius = CornerRadius(4.dp.toPx()))
        draw(imageColor, toolbarColor)
    }
}

@Composable
private fun PreviewCard(screen: SecondScreenInfo, draw: DrawScope.(imageColor: Color, toolbarColor: Color) -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            PreviewCanvas(screen, draw)
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
    draw: DrawScope.(imageColor: Color, toolbarColor: Color) -> Unit,
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
            PreviewCanvas(screen, draw)
            Text(
                text = label,
                modifier = Modifier.padding(top = 8.dp),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

// a 16:9 image inside the area, centered unless aligned to the top or bottom
private fun fitImage(area: Rect, alignment: Int = NativeSettings.PadScreenAlignment.CENTER): Rect {
    var width = area.width
    var height = width / WII_U_ASPECT_RATIO
    if (height > area.height) {
        height = area.height
        width = height * WII_U_ASPECT_RATIO
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
private fun biggestBar(area: Rect, image: Rect): Rect = listOf(
    Rect(area.left, area.top, area.right, image.top),
    Rect(area.left, image.bottom, area.right, area.bottom),
    Rect(area.left, area.top, image.left, area.bottom),
    Rect(image.right, area.top, area.right, area.bottom),
).maxBy { it.width * it.height }

private fun DrawScope.drawToolbar(bar: Rect, toolbarColor: Color) {
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
    val tiles = 4
    val horizontal = toolbar.width >= toolbar.height
    val length = if (horizontal) toolbar.width else toolbar.height
    val thickness = (if (horizontal) toolbar.height else toolbar.width) - inset * 2
    val tileLength = (length - inset * (tiles + 1)) / tiles
    if (thickness <= 0f || tileLength <= 0f) {
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

private fun DrawScope.drawSecondScreenPreview(alignment: Int, showToolbar: Boolean, imageColor: Color, toolbarColor: Color) {
    val area = Rect(Offset.Zero, size)
    val image = fitImage(area, alignment)
    drawRect(color = imageColor, topLeft = image.topLeft, size = image.size)
    if (showToolbar) {
        drawToolbar(biggestBar(area, image), toolbarColor)
    }
}

// position is null when only the TV image is shown
private fun DrawScope.drawSingleScreenPreview(
    position: GamePadPosition?,
    showToolbar: Boolean,
    toolbarOnPad: Boolean,
    imageColor: Color,
    toolbarColor: Color,
) {
    val full = Rect(Offset.Zero, size)
    if (position == null) {
        val image = fitImage(full)
        drawRect(color = imageColor, topLeft = image.topLeft, size = image.size)
        if (showToolbar) {
            drawToolbar(biggestBar(full, image), toolbarColor)
        }
        return
    }
    val first = if (position.isVertical()) Rect(0f, 0f, size.width, size.height / 2) else Rect(0f, 0f, size.width / 2, size.height)
    val second = if (position.isVertical()) Rect(0f, size.height / 2, size.width, size.height) else Rect(size.width / 2, 0f, size.width, size.height)
    val (tvArea, padArea) = if (position.appearsAfterTV()) first to second else second to first
    val tvImage = fitImage(tvArea)
    val padImage = fitImage(padArea)
    drawRect(color = imageColor, topLeft = tvImage.topLeft, size = tvImage.size)
    // the GamePad image a bit lighter to tell them apart
    drawRect(color = imageColor.copy(alpha = 0.55f), topLeft = padImage.topLeft, size = padImage.size)
    if (showToolbar) {
        drawToolbar(if (toolbarOnPad) biggestBar(padArea, padImage) else biggestBar(tvArea, tvImage), toolbarColor)
    }
}
