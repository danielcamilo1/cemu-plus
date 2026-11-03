package info.cemu.cemu.common.android.display

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// lets the controller be used on a touchable second screen. Its window can't take the focus, so while this is
// active the controller keys the main screen gets are sent to the second screen instead, where they move the focus
// like they do on the main screen. B, or Y again, gives the controller back once nothing there uses it
@Stable
class SecondScreenControl {
    var isActive by mutableStateOf(false)
        private set

    // the content of the second screen, set while it is shown
    internal var view: View? = null
        set(value) {
            field = value
            if (value == null) exit()
        }

    private var isExitKeyDown = false

    // false when there is no second screen to go to
    fun enter(): Boolean {
        val view = view ?: return false
        // leaves touch mode, otherwise nothing there can take the focus
        view.requestFocusFromTouch()
        isExitKeyDown = false
        isActive = true
        active = this
        return true
    }

    fun exit() {
        isActive = false
        if (active == this) active = null
    }

    private fun dispatch(event: KeyEvent): Boolean {
        val view = view ?: return false
        val keyCode = event.keyCode
        if (!isControllerKey(keyCode)) return false
        val isExitKey = keyCode in ExitKeys
        // the clickables there react to the center of the D-pad, the main screen gets it from A the same way
        val forwarded = if (keyCode == KeyEvent.KEYCODE_BUTTON_A) KeyEvent(
            event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        ) else event
        // Y always goes back, B only when nothing there used it, like a slider being changed
        val isUsed = keyCode != KeyEvent.KEYCODE_BUTTON_Y && view.dispatchKeyEvent(forwarded)
        if (isExitKey && !isUsed) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> isExitKeyDown = true
                KeyEvent.ACTION_UP -> if (isExitKeyDown) exit()
            }
        }
        return true
    }

    private fun isControllerKey(keyCode: Int) =
        KeyEvent.isGamepadButton(keyCode) || keyCode in NavigationKeys || keyCode in ExitKeys

    companion object {
        private var active: SecondScreenControl? = null

        private val NavigationKeys = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        private val ExitKeys = setOf(
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )

        // called by the activity with every key, true when the second screen took it
        fun dispatchKeyEvent(event: KeyEvent) = active?.dispatch(event) ?: false
    }
}
