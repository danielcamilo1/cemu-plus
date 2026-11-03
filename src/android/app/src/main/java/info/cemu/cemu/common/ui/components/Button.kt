package info.cemu.cemu.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiURowIcon

// a white panel that opens something, with a chevron at its end like the Wii U settings lists
@Composable
fun Button(
    label: String,
    description: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit = {},
) {
    WiiUCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        onClick = if (enabled) onClick else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                WiiURowIcon(icon)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (icon != null) 14.dp else 0.dp, end = 12.dp)
            ) {
                Text(
                    text = label,
                    color = WiiUColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (description != null) {
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        color = WiiUColors.TextSecondary,
                        text = description,
                        fontSize = 14.sp,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = WiiUColors.TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
