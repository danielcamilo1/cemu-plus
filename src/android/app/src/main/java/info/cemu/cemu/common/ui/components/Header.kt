package info.cemu.cemu.common.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.wiiu.WiiUColors

@Composable
fun Header(text: String?, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
        text = text ?: "",
        color = WiiUColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
