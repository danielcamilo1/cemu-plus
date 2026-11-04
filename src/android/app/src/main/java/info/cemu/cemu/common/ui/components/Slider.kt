package info.cemu.cemu.common.ui.components

import androidx.annotation.IntRange
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import kotlin.math.roundToInt
import androidx.compose.material3.Slider as MaterialSlider

@Composable
fun Slider(
    label: String,
    value: Int,
    valueFrom: Int,
    valueTo: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @IntRange(from = 0) steps: Int = (valueTo - valueFrom - 1).coerceAtLeast(0),
    labelFormatter: (Int) -> String,
    // called while dragging, onValueChange only once the thumb is let go
    onValueChanging: (Int) -> Unit = {},
    description: String? = null,
    onValueChange: (Int) -> Unit,
) {
    var sliderValue by rememberSaveable(value) { mutableFloatStateOf(value.toFloat()) }
    // with a gamepad or keyboard the whole card takes the focus, not the slider in it
    val adjustState = rememberGamepadAdjustState()
    var valueBeforeAdjusting by remember { mutableFloatStateOf(sliderValue) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val keyStep = (valueTo - valueFrom).toFloat() / (steps + 1)

    fun setValue(newValue: Float) {
        sliderValue = newValue.coerceIn(valueFrom.toFloat(), valueTo.toFloat())
        onValueChanging(sliderValue.roundToInt())
        onValueChange(sliderValue.roundToInt())
    }

    WiiUCard(
        selected = adjustState.isAdjusting,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .gamepadAdjustable(
                state = adjustState,
                enabled = enabled,
                onStart = { valueBeforeAdjusting = sliderValue },
                onStep = { setValue(sliderValue + it * keyStep) },
                onCancel = { if (sliderValue != valueBeforeAdjusting) setValue(valueBeforeAdjusting) },
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                color = WiiUColors.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = labelFormatter(sliderValue.roundToInt()),
                color = WiiUColors.AccentDark,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
        if (description != null) {
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = description,
                color = WiiUColors.TextSecondary,
                fontSize = 14.sp,
            )
        }
        if (isFocused) GamepadAdjustHint(adjustState, modifier = Modifier.padding(top = 2.dp))
        MaterialSlider(
            modifier = Modifier.focusProperties { canFocus = false },
            valueRange = valueFrom.toFloat()..valueTo.toFloat(),
            steps = steps,
            value = sliderValue,
            enabled = enabled,
            onValueChangeFinished = { onValueChange(sliderValue.roundToInt()) },
            onValueChange = {
                sliderValue = it
                onValueChanging(it.roundToInt())
            },
            colors = SliderDefaults.colors(
                thumbColor = WiiUColors.Accent,
                activeTrackColor = WiiUColors.Accent,
                activeTickColor = WiiUColors.AccentTint,
                inactiveTrackColor = WiiUColors.Inactive,
                inactiveTickColor = WiiUColors.TextSecondary,
            ),
        )
    }
}


@Composable
fun Slider(
    label: String,
    initialValue: () -> Int,
    valueFrom: Int,
    valueTo: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @IntRange(from = 0) steps: Int = (valueTo - valueFrom - 1).coerceAtLeast(0),
    labelFormatter: (Int) -> String,
    description: String? = null,
    onValueChange: (Int) -> Unit,
) {
    var value by rememberSaveable { mutableIntStateOf(initialValue()) }

    Slider(
        label = label,
        value = value,
        valueFrom = valueFrom,
        valueTo = valueTo,
        modifier = modifier,
        enabled = enabled,
        steps = steps,
        labelFormatter = labelFormatter,
        description = description,
        onValueChange = onValueChange,
    )
}
