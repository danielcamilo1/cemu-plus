package info.cemu.cemu.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import info.cemu.cemu.common.ui.wiiu.WiiUSwitchRow

@Composable
fun Toggle(
    label: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    WiiUSwitchRow(
        label = label,
        checked = checked,
        onCheckedChange = onCheckedChanged,
        description = description,
        icon = icon,
        enabled = enabled,
    )
}

@Composable
fun Toggle(
    label: String,
    initialCheckedState: () -> Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    var checked by rememberSaveable { mutableStateOf(initialCheckedState()) }

    Toggle(
        label = label,
        checked = checked,
        onCheckedChanged = {
            checked = it
            onCheckedChanged(it)
        },
        description = description,
        enabled = enabled,
        icon = icon,
    )
}
