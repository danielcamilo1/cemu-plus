package info.cemu.cemu.settings.input.controller.emulatedcontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUColors

@Composable
fun InputItemsGroup(
    groupName: String,
    inputIds: List<Int>,
    inputIdToString: (Int) -> String,
    onInputClick: (String, Int) -> Unit,
    onInputLongClick: (Int) -> Unit,
    controlsMapping: Map<Int, String>,
) {
    Text(
        text = groupName,
        color = WiiUColors.AccentDark,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    // more columns on wide screens, so a whole controller fits on a few screens
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 840.dp -> 3
            maxWidth >= 520.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            inputIds.chunked(columns).forEach { rowIds ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowIds.forEach {
                        val buttonName = inputIdToString(it)
                        InputItem(
                            buttonName = buttonName,
                            mapping = controlsMapping[it],
                            onClick = { onInputClick(buttonName, it) },
                            onLongClick = { onInputLongClick(it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowIds.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// an input of the emulated controller and what it is bound to, tap to bind it and long press to clear it
@Composable
fun InputItem(
    buttonName: String,
    mapping: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (mapping != null) WiiUColors.Card else WiiUColors.Bar)
            .border(1.dp, WiiUColors.CardBorder, shape)
            .combinedClickable(
                indication = ripple(color = WiiUColors.Accent),
                interactionSource = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = buttonName,
            color = WiiUColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = mapping ?: tr("Not set"),
            color = if (mapping != null) WiiUColors.AccentDark else WiiUColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
