package info.cemu.cemu.common.ui.wiiu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// a controller button and what it does here
data class ButtonGuideItem(val button: String, val label: String)

private val GuideShape = RoundedCornerShape(percent = 50)
private val BadgeColor = Color(0xFF4A4A4A)

// the buttons of the controller that do something on this screen, drawn like the hints at the bottom of the Wii U
// Menu. Unless alwaysShown, it is only shown once a controller or keyboard is used and touch hides it again
@Composable
fun WiiUButtonGuide(
    items: List<ButtonGuideItem>,
    modifier: Modifier = Modifier,
    message: String? = null,
    visible: Boolean = true,
    alwaysShown: Boolean = false,
) {
    val usesKeys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    AnimatedVisibility(
        visible = visible && (alwaysShown || usesKeys),
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Row(
            modifier = Modifier
                .shadow(4.dp, GuideShape)
                .background(Color.White.copy(alpha = 0.96f), GuideShape)
                .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (message != null) {
                Text(
                    text = message,
                    color = WiiUColors.AccentDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ButtonBadge(item.button)
                    Text(text = item.label, color = WiiUColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// a round badge for a letter, a wider one for a named button like Start
@Composable
private fun ButtonBadge(button: String) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .background(BadgeColor, GuideShape)
            .padding(horizontal = if (button.length > 1) 8.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = button,
            color = Color.White,
            fontSize = if (button.length > 1) 11.sp else 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
