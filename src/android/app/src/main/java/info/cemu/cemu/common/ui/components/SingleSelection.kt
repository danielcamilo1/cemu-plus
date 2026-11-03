package info.cemu.cemu.common.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.wiiu.WiiUButton
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUDialogPanel
import info.cemu.cemu.common.ui.wiiu.WiiURadio
import info.cemu.cemu.common.ui.localization.tr

@Composable
fun SingleSelection(
    label: String,
    choice: String,
    choices: Collection<String>,
    modifier: Modifier = Modifier,
    isChoiceEnabled: (String) -> Boolean = { true },
    enabled: Boolean = true,
    onChoiceChanged: (String) -> Unit,
) {
    SingleSelection(
        label = label,
        choice = choice,
        choices = choices,
        modifier = modifier,
        choiceToString = { it },
        enabled = enabled,
        isChoiceEnabled = isChoiceEnabled,
        onChoiceChanged = onChoiceChanged,
    )
}


@Composable
fun <T> SingleSelection(
    label: String,
    initialChoice: () -> T,
    choices: Collection<T>,
    modifier: Modifier = Modifier,
    choiceToString: (T) -> String,
    isChoiceEnabled: (T) -> Boolean = { true },
    enabled: Boolean = true,
    onChoiceChanged: (T) -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf(initialChoice()) }
    SingleSelection(
        label = label,
        choice = choice,
        choices = choices,
        modifier = modifier,
        isChoiceEnabled = isChoiceEnabled,
        enabled = enabled,
        choiceToString = choiceToString,
        onChoiceChanged = { newChoice ->
            choice = newChoice
            onChoiceChanged(newChoice)
        },
    )
}

@Composable
fun <T> SingleSelection(
    label: String,
    choice: T,
    choices: Collection<T>,
    choiceToString: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isChoiceEnabled: (T) -> Boolean = { true },
    onChoiceChanged: (T) -> Unit,
) {
    var showSelectDialog by rememberSaveable { mutableStateOf(false) }

    WiiUCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        onClick = if (enabled) ({ showSelectDialog = true }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = label,
                    color = WiiUColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = choiceToString(choice),
                    modifier = Modifier.padding(top = 2.dp),
                    color = WiiUColors.AccentDark,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = WiiUColors.TextSecondary,
            )
        }
    }

    if (showSelectDialog) {
        SelectDialog(
            label = label,
            currentChoice = choice,
            choices = choices,
            choiceToString = choiceToString,
            onDismissRequest = { showSelectDialog = false },
            isChoiceEnabled = isChoiceEnabled,
            onChoiceChanged = onChoiceChanged
        )
    }
}

@Composable
private fun <T> SelectDialog(
    label: String,
    currentChoice: T,
    choices: Collection<T>,
    isChoiceEnabled: (T) -> Boolean,
    choiceToString: (T) -> String,
    onDismissRequest: () -> Unit,
    onChoiceChanged: (T) -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        WiiUDialogPanel(title = label) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight = 1.0f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { choice ->
                    Choice(
                        label = choiceToString(choice),
                        selected = currentChoice == choice,
                        isEnabled = isChoiceEnabled(choice),
                        onClick = {
                            onChoiceChanged(choice)
                            onDismissRequest()
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                WiiUButton(text = tr("Cancel"), onClick = onDismissRequest)
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, isEnabled: Boolean, onClick: () -> Unit) {
    WiiUCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.5f),
        selected = selected,
        onClick = if (isEnabled) onClick else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                color = WiiUColors.Text,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            WiiURadio(selected)
        }
    }
}
