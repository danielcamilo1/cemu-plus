package info.cemu.cemu.common.android.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import info.cemu.cemu.common.settings.AppSettingsStore

data class SecondScreenInfo(val name: String, val width: Int, val height: Int)

private fun Display.toSecondScreenInfo(): SecondScreenInfo {
    // the GamePad image is always drawn landscape, see PadPresentation
    val width = maxOf(mode.physicalWidth, mode.physicalHeight)
    val height = minOf(mode.physicalWidth, mode.physicalHeight)
    return SecondScreenInfo(name, width, height)
}

fun detectSecondScreen(context: Context): SecondScreenInfo? =
    DisplayUtils.getExternalDisplay(context)?.toSecondScreenInfo()

// the second screen the GamePad can be shown on, updated when screens are connected or turned off
@Composable
fun rememberSecondScreen(): SecondScreenInfo? {
    val context = LocalContext.current
    var secondScreen by remember { mutableStateOf(detectSecondScreen(context)) }
    DisposableEffect(context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = update()
            override fun onDisplayRemoved(displayId: Int) = update()
            override fun onDisplayChanged(displayId: Int) = update()
            fun update() {
                secondScreen = detectSecondScreen(context)
            }
        }
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }
    return secondScreen
}

// turns the second screen on the first time the app runs on a device that has one, like the AYN Thor
suspend fun applySecondScreenDefault(context: Context) {
    val hasSecondScreen = detectSecondScreen(context) != null
    AppSettingsStore.dataStore.updateData {
        if (it.emulationSettings.isSecondScreenDefaultApplied) {
            return@updateData it
        }
        it.copy(
            emulationSettings = it.emulationSettings.copy(
                isSecondScreenDefaultApplied = true,
                isPadOnExternalDisplay = it.emulationSettings.isPadOnExternalDisplay || hasSecondScreen,
            )
        )
    }
}
