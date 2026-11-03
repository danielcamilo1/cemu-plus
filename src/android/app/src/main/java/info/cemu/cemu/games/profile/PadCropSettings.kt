package info.cemu.cemu.games.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.SecondScreenInfo
import info.cemu.cemu.common.android.display.rememberSecondScreen
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.ui.components.GamepadAdjustHint
import info.cemu.cemu.common.ui.components.GamepadAdjustState
import info.cemu.cemu.common.ui.components.gamepadAdjustable
import info.cemu.cemu.common.ui.components.rememberGamepadAdjustState
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBodyText
import info.cemu.cemu.common.ui.wiiu.WiiUButton
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUSwitchRow
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.settings.screenlayout.FallbackSecondScreen
import info.cemu.cemu.settings.screenlayout.PreviewCanvas
import info.cemu.cemu.settings.screenlayout.PreviewPadColor
import info.cemu.cemu.settings.screenlayout.PreviewPaint
import info.cemu.cemu.settings.screenlayout.WII_U_ASPECT_RATIO
import info.cemu.cemu.settings.screenlayout.biggestBar
import info.cemu.cemu.settings.screenlayout.drawGameImage
import info.cemu.cemu.settings.screenlayout.drawToolbar
import info.cemu.cemu.settings.screenlayout.fitImage
import info.cemu.cemu.settings.screenlayout.isToolbarEnabled
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

private val GamePadScreen = SecondScreenInfo(name = "", width = 854, height = 480)
private val PreviewGridColor = Color.Black.copy(alpha = 0.12f)
private val PreviewCutColor = Color.Black.copy(alpha = 0.65f)

// fraction of the GamePad image left on one axis after cutting cropPercent off it
internal fun keepFraction(cropPercent: Int) = 1f - cropPercent / 100f

internal fun croppedAspectRatio(horizontal: Int, vertical: Int) =
    WII_U_ASPECT_RATIO * keepFraction(horizontal) / keepFraction(vertical)

private fun SecondScreenInfo.aspectRatio() = width.toFloat() / height

// how much of the screen an image with this shape fills
private fun screenPercentUsed(screen: SecondScreenInfo, imageRatio: Float): Int {
    val screenRatio = screen.aspectRatio()
    val fraction = if (imageRatio > screenRatio) screenRatio / imageRatio else imageRatio / screenRatio
    return (fraction * 100).roundToInt()
}

// cuts the sides of a screen narrower than the image, or the top and bottom of a wider one, until it fills the screen
internal fun fillScreenCrop(screen: SecondScreenInfo, horizontal: Int, vertical: Int): Pair<Int, Int> {
    val screenRatio = screen.aspectRatio()
    return if (screenRatio < croppedAspectRatio(horizontal, vertical)) {
        val keepX = screenRatio * keepFraction(vertical) / WII_U_ASPECT_RATIO
        ((1f - keepX) * 100).roundToInt().coerceIn(0, NativeGameTitles.PAD_CROP_MAX) to vertical
    } else {
        val keepY = WII_U_ASPECT_RATIO * keepFraction(horizontal) / screenRatio
        horizontal to ((1f - keepY) * 100).roundToInt().coerceIn(0, NativeGameTitles.PAD_CROP_MAX)
    }
}

// the part of the whole image that is kept, the cut is split evenly between both sides
private fun keptPart(image: Rect, horizontal: Int, vertical: Int): Rect {
    val cutX = image.width * (1f - keepFraction(horizontal)) / 2
    val cutY = image.height * (1f - keepFraction(vertical)) / 2
    return Rect(image.left + cutX, image.top + cutY, image.right - cutX, image.bottom - cutY)
}

// the GamePad image with a grid on it, so the previews show how much bigger it gets. Only the visible part is drawn
private fun DrawScope.drawGamePadImage(image: Rect, visible: Rect, paint: PreviewPaint) {
    clipRect(visible.left, visible.top, visible.right, visible.bottom) {
        drawGameImage(image, PreviewPadColor, paint.padLabel, paint)
        val cell = image.width / 16
        val strokeWidth = 1.dp.toPx()
        for (i in 1 until 16) {
            val x = image.left + i * cell
            drawLine(PreviewGridColor, Offset(x, image.top), Offset(x, image.bottom), strokeWidth)
        }
        for (i in 1 until 9) {
            val y = image.top + i * cell
            drawLine(PreviewGridColor, Offset(image.left, y), Offset(image.right, y), strokeWidth)
        }
    }
}

// the whole GamePad image with the borders that get cut off darkened
private fun DrawScope.drawCropFrame(horizontal: Int, vertical: Int, paint: PreviewPaint) {
    val image = Rect(Offset.Zero, size)
    val kept = keptPart(image, horizontal, vertical)
    drawGamePadImage(image, image, paint)
    for (cut in listOf(
        Rect(image.left, image.top, image.right, kept.top),
        Rect(image.left, kept.bottom, image.right, image.bottom),
        Rect(image.left, kept.top, kept.left, kept.bottom),
        Rect(kept.right, kept.top, image.right, kept.bottom),
    )) {
        if (cut.width > 0f && cut.height > 0f) {
            drawRect(PreviewCutColor, topLeft = cut.topLeft, size = cut.size)
        }
    }
    drawRect(paint.toolbarColor, topLeft = kept.topLeft, size = kept.size, style = Stroke(2.dp.toPx()))
}

// what the second screen shows in game: the kept part as big as it fits, with the toolbar in the bars that are left
private fun DrawScope.drawCroppedSecondScreen(horizontal: Int, vertical: Int, alignment: Int, showToolbar: Boolean, paint: PreviewPaint) {
    val area = Rect(Offset.Zero, size)
    val visible = fitImage(area, alignment, croppedAspectRatio(horizontal, vertical))
    val imageWidth = visible.width / keepFraction(horizontal)
    val imageHeight = visible.height / keepFraction(vertical)
    val image = Rect(
        visible.center.x - imageWidth / 2,
        visible.center.y - imageHeight / 2,
        visible.center.x + imageWidth / 2,
        visible.center.y + imageHeight / 2,
    )
    drawGamePadImage(image, visible, paint)
    if (showToolbar) {
        drawToolbar(biggestBar(area, visible), paint.toolbarColor, paint.toolbarTiles)
    }
}

// cuts the borders of the GamePad image for this game so the middle of it gets bigger on a second screen
// that is not 16:9, like the bottom screen of the AYN Thor
@Composable
internal fun PadCropSettings(titleId: Long) {
    val detectedScreen = rememberSecondScreen()
    val screen = detectedScreen ?: FallbackSecondScreen
    val isPadOnSecondScreen by remember {
        AppSettingsStore.dataStore.data.map { it.emulationSettings.isPadOnExternalDisplay }
    }.collectAsState(initial = true)
    val areScreensSwapped by remember {
        AppSettingsStore.dataStore.data.map { it.emulationSettings.areScreensSwapped }
    }.collectAsState(initial = false)
    val alignment = remember { NativeSettings.getPadScreenAlignment() }
    val isToolbarOn = remember { isToolbarEnabled() }

    var enabled by rememberSaveable { mutableStateOf(NativeGameTitles.isPadCropEnabledForTitle(titleId)) }
    var hideToolbar by rememberSaveable { mutableStateOf(NativeGameTitles.isPadCropToolbarHiddenForTitle(titleId)) }
    // follow the sliders while they are dragged, they are saved once let go
    var horizontal by rememberSaveable { mutableIntStateOf(NativeGameTitles.getPadCropHorizontalForTitle(titleId)) }
    var vertical by rememberSaveable { mutableIntStateOf(NativeGameTitles.getPadCropVerticalForTitle(titleId)) }

    val horizontalAdjustState = rememberGamepadAdjustState()
    val verticalAdjustState = rememberGamepadAdjustState()
    val horizontalInteractionSource = remember { MutableInteractionSource() }
    val verticalInteractionSource = remember { MutableInteractionSource() }
    val isHorizontalFocused by horizontalInteractionSource.collectIsFocusedAsState()
    val isVerticalFocused by verticalInteractionSource.collectIsFocusedAsState()

    fun save(newHorizontal: Int, newVertical: Int) {
        horizontal = newHorizontal
        vertical = newVertical
        NativeGameTitles.setPadCropForTitle(titleId, newHorizontal, newVertical)
    }

    val fill = fillScreenCrop(screen, horizontal, vertical)
    val shownHorizontal = if (enabled) horizontal else 0
    val shownVertical = if (enabled) vertical else 0
    // the same rule as in game, the toolbar is only hidden while something is cut off
    val showToolbar = isToolbarOn && !(hideToolbar && (shownHorizontal > 0 || shownVertical > 0))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WiiUBodyText(tr("Cuts the borders of the GamePad image so the middle of it gets bigger on your second screen. Useful for games that keep the important things near the center. What is cut off cannot be seen or touched."))
        if (!isPadOnSecondScreen) {
            WiiUBodyText(
                text = tr("Only used while the GamePad is on the second screen. Turn on Use second screen in Settings, Screen layout."),
                color = WiiUColors.AccentDark,
            )
        } else if (areScreensSwapped) {
            WiiUBodyText(
                text = tr("Swap screens is on, so the GamePad image is on the main screen and is not cropped. The crop is used while the GamePad is on the second screen."),
                color = WiiUColors.AccentDark,
            )
        }
        WiiUSwitchRow(
            label = tr("Crop the GamePad image"),
            description = tr("Off for every game until you turn it on here. Turn off to see the whole GamePad image again without losing the crop. The in-game menu has the same switch"),
            icon = R.drawable.ic_resize,
            checked = enabled,
            onCheckedChange = {
                enabled = it
                NativeGameTitles.setPadCropEnabledForTitle(titleId, it)
            },
        )
        if (isToolbarOn) {
            WiiUSwitchRow(
                label = tr("Hide the toolbar for this game"),
                description = tr("Hides the toolbar in the black bars of the second screen while the GamePad image is cropped. The quick menu still opens with its hotkey or the back button"),
                icon = R.drawable.ic_dashboard,
                enabled = enabled,
                checked = hideToolbar,
                onCheckedChange = {
                    hideToolbar = it
                    NativeGameTitles.setPadCropToolbarHiddenForTitle(titleId, it)
                },
            )
        }
        WiiUCard(modifier = Modifier.fillMaxWidth()) {
            // the left and right crop above the previews and the top and bottom crop beside them, so the
            // previews stay in sight while dragging either one
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val previewsWidth = (maxWidth - SideSliderWidth - PreviewSpacing).coerceAtMost(MaxPreviewsWidth)
                // both previews are the same height, weighted by their shapes
                val previewHeight = (previewsWidth - PreviewSpacing) / (WII_U_ASPECT_RATIO + screen.aspectRatio())
                Column(modifier = Modifier.alpha(if (enabled) 1f else 0.6f)) {
                    Column(modifier = Modifier.width(previewsWidth)) {
                        SliderValueLabel(tr("Crop left and right"), tr("{0}% of the width", horizontal.toString()))
                        CropSlider(
                            value = horizontal,
                            enabled = enabled,
                            vertical = false,
                            adjustState = horizontalAdjustState,
                            interactionSource = horizontalInteractionSource,
                            onValueChanging = { horizontal = it },
                            onValueChange = { save(horizontal, vertical) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PreviewSpacing)) {
                        Row(
                            modifier = Modifier.width(previewsWidth),
                            horizontalArrangement = Arrangement.spacedBy(PreviewSpacing),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            PreviewWithCaption(
                                modifier = Modifier.weight(WII_U_ASPECT_RATIO),
                                screen = GamePadScreen,
                                caption = tr("GamePad image"),
                            ) { paint ->
                                drawCropFrame(shownHorizontal, shownVertical, paint)
                            }
                            PreviewWithCaption(
                                modifier = Modifier.weight(screen.aspectRatio()),
                                screen = screen,
                                caption = if (detectedScreen != null) {
                                    tr("Second screen, {0}x{1}", screen.width.toString(), screen.height.toString())
                                } else {
                                    tr("Second screen (AYN Thor shape)")
                                },
                            ) { paint ->
                                drawCroppedSecondScreen(shownHorizontal, shownVertical, alignment, showToolbar, paint)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .width(SideSliderWidth)
                                .height(previewHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = tr("{0}%", vertical.toString()), color = WiiUColors.Text, fontSize = 14.sp)
                            VerticalSlider(
                                modifier = Modifier.weight(1f),
                                value = vertical,
                                enabled = enabled,
                                adjustState = verticalAdjustState,
                                interactionSource = verticalInteractionSource,
                                onValueChanging = { vertical = it },
                                onValueChange = { save(horizontal, vertical) },
                            )
                        }
                    }
                    WiiUBodyText(
                        text = tr("The slider beside the previews crops the top and bottom."),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (isHorizontalFocused) GamepadAdjustHint(horizontalAdjustState)
                    if (isVerticalFocused) GamepadAdjustHint(verticalAdjustState, vertical = true)
                }
            }
        }
        WiiUBodyText(
            tr(
                "The GamePad image fills {0}% of the second screen, {1}% without cropping.",
                screenPercentUsed(screen, croppedAspectRatio(shownHorizontal, shownVertical)).toString(),
                screenPercentUsed(screen, WII_U_ASPECT_RATIO).toString(),
            )
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WiiUButton(
                text = tr("Fill the screen"),
                icon = R.drawable.ic_resize,
                primary = true,
                enabled = enabled && fill != (horizontal to vertical),
                onClick = { save(fill.first, fill.second) },
            )
            WiiUButton(
                text = tr("Reset"),
                icon = R.drawable.ic_restart,
                enabled = enabled && (horizontal != 0 || vertical != 0),
                onClick = { save(0, 0) },
            )
        }
    }
}

private val SideSliderWidth = 56.dp
private val PreviewSpacing = 12.dp
private val MaxPreviewsWidth = 720.dp

@Composable
private fun SliderValueLabel(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = WiiUColors.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(text = value, color = WiiUColors.TextSecondary, fontSize = 14.sp)
    }
}

// a crop slider the gamepad goes past unless A is pressed on it, see gamepadAdjustable
@Composable
private fun CropSlider(
    value: Int,
    enabled: Boolean,
    vertical: Boolean,
    adjustState: GamepadAdjustState,
    interactionSource: MutableInteractionSource,
    onValueChanging: (Int) -> Unit,
    onValueChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    var valueBeforeAdjusting by remember { mutableIntStateOf(value) }
    val isFocused by interactionSource.collectIsFocusedAsState()

    fun setValue(newValue: Int) {
        onValueChanging(newValue.coerceIn(0, NativeGameTitles.PAD_CROP_MAX))
        onValueChange()
    }

    MaterialSlider(
        value = value.toFloat(),
        valueRange = 0f..NativeGameTitles.PAD_CROP_MAX.toFloat(),
        steps = NativeGameTitles.PAD_CROP_MAX - 1,
        enabled = enabled,
        interactionSource = interactionSource,
        onValueChange = { onValueChanging(it.roundToInt()) },
        onValueChangeFinished = onValueChange,
        modifier = Modifier
            .background(
                when {
                    adjustState.isAdjusting -> WiiUColors.AccentTint
                    isFocused -> WiiUColors.Bar
                    else -> Color.Transparent
                },
                RoundedCornerShape(12.dp),
            )
            .gamepadAdjustable(
                state = adjustState,
                enabled = enabled,
                vertical = vertical,
                onStart = { valueBeforeAdjusting = currentValue },
                onStep = { setValue(currentValue + it) },
                onCancel = { setValue(valueBeforeAdjusting) },
            )
            .then(modifier),
    )
}

// a slider turned upright, more crop towards the top
@Composable
private fun VerticalSlider(
    value: Int,
    enabled: Boolean,
    adjustState: GamepadAdjustState,
    interactionSource: MutableInteractionSource,
    onValueChanging: (Int) -> Unit,
    onValueChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CropSlider(
        value = value,
        enabled = enabled,
        vertical = true,
        adjustState = adjustState,
        interactionSource = interactionSource,
        onValueChanging = onValueChanging,
        onValueChange = onValueChange,
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                // measured lying down with the height as its length, then stood up
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            },
    )
}

@Composable
private fun PreviewWithCaption(
    modifier: Modifier,
    screen: SecondScreenInfo,
    caption: String,
    draw: DrawScope.(PreviewPaint) -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PreviewCanvas(screen = screen, draw = draw)
        Text(
            text = caption,
            modifier = Modifier.padding(top = 8.dp),
            color = WiiUColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
