package info.cemu.cemu.games.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.android.display.SecondScreenInfo
import info.cemu.cemu.common.android.display.rememberSecondScreen
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.localization.tr
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
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val GamePadScreen = SecondScreenInfo(name = "", width = 854, height = 480)
private val PreviewGridColor = Color.Black.copy(alpha = 0.12f)
private val PreviewCutColor = Color.Black.copy(alpha = 0.65f)

// fraction of the GamePad image left on one axis after cutting cropPercent off it
private fun keepFraction(cropPercent: Int) = 1f - cropPercent / 100f

private fun croppedAspectRatio(horizontal: Int, vertical: Int) =
    WII_U_ASPECT_RATIO * keepFraction(horizontal) / keepFraction(vertical)

private fun SecondScreenInfo.aspectRatio() = width.toFloat() / height

// how much of the screen an image with this shape fills
private fun screenPercentUsed(screen: SecondScreenInfo, imageRatio: Float): Int {
    val screenRatio = screen.aspectRatio()
    val fraction = if (imageRatio > screenRatio) screenRatio / imageRatio else imageRatio / screenRatio
    return (fraction * 100).roundToInt()
}

// cuts the sides of a screen narrower than the image, or the top and bottom of a wider one, until it fills the screen
private fun fillScreenCrop(screen: SecondScreenInfo, horizontal: Int, vertical: Int): Pair<Int, Int> {
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
    val alignment = remember { NativeSettings.getPadScreenAlignment() }
    val showToolbar = remember { isToolbarEnabled() }

    var savedHorizontal by rememberSaveable { mutableIntStateOf(NativeGameTitles.getPadCropHorizontalForTitle(titleId)) }
    var savedVertical by rememberSaveable { mutableIntStateOf(NativeGameTitles.getPadCropVerticalForTitle(titleId)) }
    // follows the sliders while they are dragged, the saved values only change when they are let go
    var horizontal by rememberSaveable { mutableIntStateOf(savedHorizontal) }
    var vertical by rememberSaveable { mutableIntStateOf(savedVertical) }

    fun save(newHorizontal: Int, newVertical: Int) {
        horizontal = newHorizontal
        vertical = newVertical
        savedHorizontal = newHorizontal
        savedVertical = newVertical
        NativeGameTitles.setPadCropForTitle(titleId, newHorizontal, newVertical)
    }

    val fill = fillScreenCrop(screen, horizontal, vertical)

    Column {
        Header(tr("GamePad screen crop"))
        InfoText(tr("Cuts the borders of the GamePad image so the middle of it gets bigger on your second screen. Useful for games that keep the important things near the center. What is cut off cannot be seen or touched."))
        if (!isPadOnSecondScreen) {
            InfoText(tr("Only used while the GamePad is on the second screen. Turn on Use second screen in Settings, Screen layout."))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // weighted by their shapes so both previews are the same height
            PreviewWithCaption(
                modifier = Modifier.weight(WII_U_ASPECT_RATIO),
                screen = GamePadScreen,
                caption = tr("GamePad image"),
            ) { paint ->
                drawCropFrame(horizontal, vertical, paint)
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
                drawCroppedSecondScreen(horizontal, vertical, alignment, showToolbar, paint)
            }
        }
        InfoText(
            tr(
                "The GamePad image fills {0}% of the second screen, {1}% without cropping.",
                screenPercentUsed(screen, croppedAspectRatio(horizontal, vertical)).toString(),
                screenPercentUsed(screen, WII_U_ASPECT_RATIO).toString(),
            )
        )
        Slider(
            label = tr("Crop left and right"),
            value = savedHorizontal,
            valueFrom = 0,
            valueTo = NativeGameTitles.PAD_CROP_MAX,
            labelFormatter = { tr("{0}% of the width", it.toString()) },
            onValueChanging = { horizontal = it },
            onValueChange = { save(it, vertical) },
        )
        Slider(
            label = tr("Crop top and bottom"),
            value = savedVertical,
            valueFrom = 0,
            valueTo = NativeGameTitles.PAD_CROP_MAX,
            labelFormatter = { tr("{0}% of the height", it.toString()) },
            onValueChanging = { vertical = it },
            onValueChange = { save(horizontal, it) },
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = fill != (horizontal to vertical),
                onClick = { save(fill.first, fill.second) },
            ) {
                Text(tr("Fill the screen"))
            }
            OutlinedButton(
                enabled = horizontal != 0 || vertical != 0,
                onClick = { save(0, 0) },
            ) {
                Text(tr("Reset"))
            }
        }
    }
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
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
