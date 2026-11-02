package info.cemu.cemu.emulation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.android.display.rememberSecondScreen
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.games.profile.fillScreenCrop
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.settings.screenlayout.FallbackSecondScreen

/**
 * GamePad screen crop of the running game for the in-game menu. Changes apply immediately and are saved
 * to the game profile, the same as Edit game profile.
 */
@Composable
fun PadCropQuickSettings(isEmulationInitialized: Boolean, isPadOnSecondScreen: Boolean) {
    // the menu is composed before the game starts, so look the game up again once it runs
    val titleId = remember(isEmulationInitialized) { NativeGameTitles.getRunningTitleId() }
    if (titleId == 0L) {
        return
    }
    val screen = rememberSecondScreen() ?: FallbackSecondScreen

    var enabled by remember(titleId) { mutableStateOf(NativeGameTitles.isPadCropEnabledForTitle(titleId)) }
    var horizontal by remember(titleId) { mutableIntStateOf(NativeGameTitles.getPadCropHorizontalForTitle(titleId)) }
    var vertical by remember(titleId) { mutableIntStateOf(NativeGameTitles.getPadCropVerticalForTitle(titleId)) }

    fun save(newHorizontal: Int, newVertical: Int) {
        horizontal = newHorizontal.coerceIn(0, NativeGameTitles.PAD_CROP_MAX)
        vertical = newVertical.coerceIn(0, NativeGameTitles.PAD_CROP_MAX)
        NativeGameTitles.setPadCropForTitle(titleId, horizontal, vertical)
    }

    val fill = fillScreenCrop(screen, horizontal, vertical)

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = tr("GamePad screen crop"),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!isPadOnSecondScreen) {
            Text(
                text = tr("Used while the GamePad is on the second screen"),
                modifier = Modifier.padding(horizontal = 8.dp),
                fontSize = 14.sp,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    enabled = !enabled
                    NativeGameTitles.setPadCropEnabledForTitle(titleId, enabled)
                }
                .padding(8.dp)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("Crop the GamePad image"),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .weight(1f),
                fontSize = 16.sp,
            )
            Checkbox(checked = enabled, onCheckedChange = null)
        }
        CropStepper(
            label = tr("Left and right"),
            value = horizontal,
            enabled = enabled,
            onValueChange = { save(it, vertical) },
        )
        CropStepper(
            label = tr("Top and bottom"),
            value = vertical,
            enabled = enabled,
            onValueChange = { save(horizontal, it) },
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = enabled && fill != (horizontal to vertical),
                onClick = { save(fill.first, fill.second) },
            ) {
                Text(tr("Fill the screen"))
            }
            OutlinedButton(
                enabled = enabled && (horizontal != 0 || vertical != 0),
                onClick = { save(0, 0) },
            ) {
                Text(tr("Reset"))
            }
        }
    }
}

// buttons instead of a slider, they are easier to use with a controller in the menu
@Composable
private fun CropStepper(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
        )
        TextButton(enabled = enabled && value > 0, onClick = { onValueChange(value - 1) }) {
            Text("−", fontSize = 20.sp)
        }
        Text(
            text = tr("{0}%", value.toString()),
            modifier = Modifier.widthIn(min = 48.dp),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
        )
        TextButton(enabled = enabled && value < NativeGameTitles.PAD_CROP_MAX, onClick = { onValueChange(value + 1) }) {
            Text("+", fontSize = 20.sp)
        }
    }
}
