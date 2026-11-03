package info.cemu.cemu.common.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUColors

@Stable
class GamepadAdjustState {
    var isAdjusting by mutableStateOf(false)
        internal set
}

@Composable
fun rememberGamepadAdjustState() = remember { GamepadAdjustState() }

// with a gamepad or keyboard, moving across a slider goes to the next setting instead of changing it.
// A starts adjusting, the arrows along the slider change it, A keeps the value and B puts the old one back.
// Left and right on a horizontal slider also start adjusting right away, since they move nowhere else
fun Modifier.gamepadAdjustable(
    state: GamepadAdjustState,
    enabled: Boolean,
    vertical: Boolean = false,
    // saves what the value was, to put it back on cancel
    onStart: () -> Unit,
    // +1 or -1, towards the end or the start of the slider
    onStep: (Int) -> Unit,
    onCancel: () -> Unit,
) = composed {
    val focusManager = LocalFocusManager.current
    onFocusChanged { if (!it.isFocused) state.isAdjusting = false }
        .onPreviewKeyEvent { event ->
            if (!enabled) return@onPreviewKeyEvent false
            val isDown = event.type == KeyEventType.KeyDown
            val isUp = event.type == KeyEventType.KeyUp
            val step = when (event.key) {
                Key.DirectionRight -> if (vertical) null else 1
                Key.DirectionLeft -> if (vertical) null else -1
                Key.DirectionUp -> if (vertical) 1 else null
                Key.DirectionDown -> if (vertical) -1 else null
                else -> null
            }
            val focusDirection = when (event.key) {
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> null
            }
            when {
                step != null && (state.isAdjusting || !vertical) -> {
                    if (isDown) {
                        if (!state.isAdjusting) {
                            onStart()
                            state.isAdjusting = true
                        }
                        onStep(step)
                    }
                    true
                }

                focusDirection != null -> {
                    if (isDown) {
                        state.isAdjusting = false
                        focusManager.moveFocus(focusDirection)
                    }
                    true
                }

                event.key in ConfirmKeys -> {
                    if (isUp) {
                        if (!state.isAdjusting) onStart()
                        state.isAdjusting = !state.isAdjusting
                    }
                    true
                }

                event.key in CancelKeys && state.isAdjusting -> {
                    if (isUp) {
                        state.isAdjusting = false
                        onCancel()
                    }
                    true
                }

                else -> false
            }
        }
}

private val ConfirmKeys = setOf(Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private val CancelKeys = setOf(Key.ButtonB, Key.Back, Key.Escape)

// what the buttons do, shown while the slider has the gamepad focus
@Composable
fun GamepadAdjustHint(state: GamepadAdjustState, vertical: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier,
        text = when {
            !state.isAdjusting -> tr("Ⓐ change")
            vertical -> tr("▲ ▼ change   Ⓐ done   Ⓑ cancel")
            else -> tr("◀ ▶ change   Ⓐ done   Ⓑ cancel")
        },
        color = WiiUColors.AccentDark,
        fontSize = 13.sp,
    )
}
