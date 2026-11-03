package info.cemu.cemu.common.ui.wiiu

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.R

// the light look of the Wii U system setup: white and silver surfaces with a cyan highlight
internal object WiiUColors {
    val Accent = Color(0xFF00A9E0)
    val AccentLight = Color(0xFF4FD0F7)
    val AccentDark = Color(0xFF0086BA)
    val AccentGlow = Color(0x6600B4F0)
    val AccentTint = Color(0xFFE3F6FD)
    val BackgroundTop = Color(0xFFFFFFFF)
    val BackgroundBottom = Color(0xFFE2E5E9)
    val Stripe = Color(0x07000000)
    val Bar = Color(0xFFF6F7F8)
    val BarBorder = Color(0xFFD2D5D9)
    val Card = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFD9DCE0)
    val Text = Color(0xFF3E4044)
    val TextSecondary = Color(0xFF7A7D82)
    val ButtonTop = Color(0xFFFFFFFF)
    val ButtonBottom = Color(0xFFE6E8EA)
    val ButtonBorder = Color(0xFFC2C5C9)
    val Success = Color(0xFF34B24A)
    val Inactive = Color(0xFFC9CCD0)
}

private val WiiUColorScheme = lightColorScheme(
    primary = WiiUColors.Accent,
    onPrimary = Color.White,
    primaryContainer = WiiUColors.AccentTint,
    onPrimaryContainer = WiiUColors.AccentDark,
    secondary = WiiUColors.Accent,
    secondaryContainer = WiiUColors.AccentTint,
    tertiary = WiiUColors.Accent,
    background = WiiUColors.BackgroundTop,
    onBackground = WiiUColors.Text,
    surface = WiiUColors.Card,
    onSurface = WiiUColors.Text,
    onSurfaceVariant = WiiUColors.TextSecondary,
    surfaceContainerHigh = WiiUColors.Card,
    outline = WiiUColors.ButtonBorder,
)

@Composable
internal fun WiiUTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WiiUColorScheme, content = content)
}

// a soft silver gradient with the fine horizontal lines of the Wii U menus
@Composable
internal fun WiiUBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(WiiUColors.BackgroundTop, WiiUColors.BackgroundBottom)))
            .drawBehind {
                val step = 4.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(WiiUColors.Stripe, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            }
    )
}

private val PillShape = RoundedCornerShape(percent = 50)

// the rounded buttons of the Wii U menus. The focused one glows cyan, like the cursor on the GamePad
@Composable
internal fun WiiUButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "scale")
    val borderWidth by animateDpAsState(if (isFocused) 3.dp else 1.dp, label = "border")
    val brush = when {
        primary -> Brush.verticalGradient(listOf(WiiUColors.AccentLight, WiiUColors.Accent))
        else -> Brush.verticalGradient(listOf(WiiUColors.ButtonTop, WiiUColors.ButtonBottom))
    }
    val borderColor = when {
        isFocused -> WiiUColors.AccentLight
        primary -> WiiUColors.AccentDark
        else -> WiiUColors.ButtonBorder
    }
    val contentColor = if (primary) Color.White else WiiUColors.Text
    Box(
        modifier = modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 48.dp)
            .widthIn(min = 112.dp)
            .shadow(if (isFocused) 6.dp else 2.dp, PillShape, ambientColor = WiiUColors.AccentGlow, spotColor = if (isFocused) WiiUColors.Accent else Color.Black)
            .clip(PillShape)
            .background(brush)
            .border(borderWidth, borderColor, PillShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = if (primary) Color.White else WiiUColors.Accent),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            }
            Text(text = text, color = contentColor, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

// a white panel. Selected or focused ones get the cyan outline
@Composable
internal fun WiiUCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    // for cards made focusable through the modifier, so they light up too
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val isFocused by interactionSource.collectIsFocusedAsState()
    val highlighted = selected || isFocused
    val borderColor by animateColorAsState(if (highlighted) WiiUColors.Accent else WiiUColors.CardBorder, label = "border")
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(color = WiiUColors.Accent),
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .shadow(if (highlighted) 5.dp else 2.dp, shape, spotColor = if (highlighted) WiiUColors.Accent else Color.Black)
            .clip(shape)
            .background(if (selected) WiiUColors.AccentTint else WiiUColors.Card)
            .border(BorderStroke(if (highlighted) 2.dp else 1.dp, borderColor), shape)
            .then(clickModifier)
            .padding(16.dp),
        content = content,
    )
}

// the round cyan badge on top of every page
@Composable
internal fun WiiUHeroIcon(@DrawableRes icon: Int, size: Int = 88) {
    Box(
        modifier = Modifier
            .size((size + 16).dp)
            .background(WiiUColors.AccentGlow.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .shadow(6.dp, CircleShape, spotColor = WiiUColors.Accent)
                .background(Brush.verticalGradient(listOf(WiiUColors.AccentLight, WiiUColors.AccentDark)), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size((size / 2).dp))
        }
    }
}

// a small icon in a tinted circle, used at the start of list rows
@Composable
internal fun WiiURowIcon(@DrawableRes icon: Int, tint: Color = WiiUColors.Accent) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

// the round radio of the option cards, filled with a check mark once picked
@Composable
internal fun WiiURadio(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(if (selected) WiiUColors.Accent else Color.White, CircleShape)
            .border(2.dp, if (selected) WiiUColors.AccentDark else WiiUColors.ButtonBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

// a big choice with an icon, a title and what it means
@Composable
internal fun WiiUOptionCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    WiiUCard(modifier = modifier.fillMaxWidth(), selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, color = WiiUColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (badge != null) {
                        Text(
                            text = badge,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(WiiUColors.Accent, PillShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(text = description, color = WiiUColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
            WiiURadio(selected)
        }
    }
}

// a switch on a white panel, the whole panel toggles it
@Composable
internal fun WiiUSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    WiiUCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
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
                Text(text = label, color = WiiUColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (description != null) {
                    Text(text = description, color = WiiUColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = WiiUColors.Accent,
                    checkedBorderColor = WiiUColors.AccentDark,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = WiiUColors.Inactive,
                    uncheckedBorderColor = WiiUColors.ButtonBorder,
                ),
            )
        }
    }
}

// a pill that is lit cyan with a check mark when picked
@Composable
internal fun WiiUChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(if (selected) WiiUColors.Accent else Color.White, label = "chip")
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 40.dp)
            .clip(PillShape)
            .background(background)
            .border(
                if (isFocused) 3.dp else 1.dp,
                when {
                    isFocused -> WiiUColors.AccentLight
                    selected -> WiiUColors.AccentDark
                    else -> WiiUColors.ButtonBorder
                },
                PillShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = if (selected) Color.White else WiiUColors.Accent),
                role = Role.Checkbox,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp),
            )
        }
        Text(text = label, color = if (selected) Color.White else WiiUColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// the dots at the top that show how far along the setup is
@Composable
internal fun WiiUStepIndicator(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (step in 1..total) {
            val width by animateDpAsState(if (step == current) 28.dp else 10.dp, label = "dot")
            val color by animateColorAsState(if (step <= current) WiiUColors.Accent else WiiUColors.Inactive, label = "dotColor")
            Box(
                modifier = Modifier
                    .height(10.dp)
                    .width(width)
                    .background(color, PillShape)
            )
        }
    }
}

@Composable
internal fun WiiUSectionTitle(text: String) {
    Text(
        text = text,
        color = WiiUColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
internal fun WiiUBodyText(text: String, modifier: Modifier = Modifier, color: Color = WiiUColors.TextSecondary) {
    Text(text = text, color = color, fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)
}

// a thin bar at the top or bottom of the screen
@Composable
internal fun WiiUBar(top: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(if (top) listOf(Color.White, WiiUColors.Bar) else listOf(WiiUColors.Bar, Color.White)))
            .drawBehind {
                val y = if (top) size.height else 0f
                drawLine(WiiUColors.BarBorder, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            },
        content = content,
    )
}

// the white rounded window of a dialog, with its title at the top
@Composable
internal fun WiiUDialogPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    WiiUTheme {
        Column(
            modifier = modifier
                .sizeIn(maxWidth = 560.dp, maxHeight = 600.dp)
                .fillMaxWidth()
                .shadow(12.dp, shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(WiiUColors.BackgroundTop, WiiUColors.Bar)))
                .border(1.dp, WiiUColors.CardBorder, shape),
        ) {
            Text(
                text = title,
                color = WiiUColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            )
            content()
        }
    }
}
