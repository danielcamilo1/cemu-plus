package info.cemu.cemu.games.profile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.localization.regionToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBackground
import info.cemu.cemu.common.ui.wiiu.WiiUBar
import info.cemu.cemu.common.ui.wiiu.WiiUBodyText
import info.cemu.cemu.common.ui.wiiu.WiiUButton
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUChip
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUHeroIcon
import info.cemu.cemu.common.ui.wiiu.WiiURowIcon
import info.cemu.cemu.common.ui.wiiu.WiiUSectionTitle
import info.cemu.cemu.common.ui.wiiu.WiiUSwitchRow
import info.cemu.cemu.common.ui.wiiu.WiiUTheme
import info.cemu.cemu.games.getLastPlayedDate
import info.cemu.cemu.games.getTimePlayed
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeGameTitles.DriverSettingMode

// wide enough to show the game beside its settings instead of above them
private val TwoPaneWidth = 760.dp
private val SettingsMaxWidth = 760.dp

@Composable
fun GameProfileEditScreen(
    game: NativeGameTitles.Game,
    navigateBack: () -> Unit,
) {
    WiiUTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            WiiUBackground()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                GameSettingsBar(
                    title = tr("Game settings"),
                    navigateBack = navigateBack,
                )
                GameSettingsContent(
                    game = game,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// the settings of the game picked in the library, shown on the second screen like the Wii U Menu shows the picked
// title on the TV. They can be changed there with touch
@Composable
fun GameSettingsSecondScreen(game: NativeGameTitles.Game?, startGame: (NativeGameTitles.Game) -> Unit) {
    WiiUTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            WiiUBackground()
            Column(modifier = Modifier.fillMaxSize()) {
                GameSettingsBar(title = tr("Game settings"))
                if (game == null) {
                    NoGameSelected(modifier = Modifier.weight(1f))
                } else {
                    // every game starts with its own saved settings
                    key(game.path) {
                        GameSettingsContent(
                            game = game,
                            startGame = { startGame(game) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameSettingsBar(title: String, navigateBack: (() -> Unit)? = null) {
    WiiUBar(top = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = if (navigateBack != null) 4.dp else 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigateBack != null) {
                IconButton(onClick = navigateBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = tr("Back"),
                        tint = WiiUColors.TextSecondary,
                    )
                }
            }
            Text(
                text = title,
                color = WiiUColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NoGameSelected(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WiiUHeroIcon(icon = R.drawable.ic_videogame)
        Text(
            text = tr("Pick a game"),
            color = WiiUColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp),
        )
        WiiUBodyText(
            text = tr("Tap a game in your library once to see its settings here. Tap it again to start it."),
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun GameSettingsContent(
    game: NativeGameTitles.Game,
    modifier: Modifier = Modifier,
    startGame: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneWidth) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 20.dp)
                ) {
                    GameCard(game = game, isWide = true, startGame = startGame)
                }
                GameSettingsList(
                    game = game,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            GameSettingsList(
                game = game,
                header = { GameCard(game = game, isWide = false, startGame = startGame) },
            )
        }
    }
}

// the game like its icon on the Wii U Menu, with what the library knows about it
@Composable
private fun GameCard(game: NativeGameTitles.Game, isWide: Boolean, startGame: (() -> Unit)?) {
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        if (isWide) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                GameIconTile(game = game, size = 160.dp)
                Text(
                    text = game.name ?: "",
                    color = WiiUColors.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GameFacts(game)
                }
                if (startGame != null) {
                    StartButton(startGame, modifier = Modifier.padding(top = 16.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIconTile(game = game, size = 88.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = game.name ?: "",
                        color = WiiUColors.Text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GameFacts(game)
                }
            }
            if (startGame != null) {
                StartButton(
                    startGame,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StartButton(startGame: () -> Unit, modifier: Modifier) {
    WiiUButton(
        text = tr("Start"),
        icon = R.drawable.ic_play_arrow,
        primary = true,
        onClick = startGame,
        modifier = modifier,
    )
}

@Composable
private fun GameFacts(game: NativeGameTitles.Game) {
    GameFact(icon = R.drawable.ic_videogame, text = getTimePlayed(game))
    GameFact(icon = R.drawable.ic_date_range, text = tr("Last played: {0}", getLastPlayedDate(game)))
    GameFact(
        icon = R.drawable.ic_package_2,
        text = tr("{0}, version {1}", regionToString(game.region), game.version.toString()),
    )
}

@Composable
private fun GameFact(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = WiiUColors.Accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            color = WiiUColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// the white rounded frame the library draws around game icons
@Composable
private fun GameIconTile(game: NativeGameTitles.Game, size: Dp) {
    val frameShape = RoundedCornerShape(size * 0.16f)
    val iconShape = RoundedCornerShape(size * 0.12f)
    Box(
        modifier = Modifier
            .size(size)
            .shadow(elevation = 4.dp, shape = frameShape)
            .background(Color.White, frameShape)
            .padding(size * 0.05f),
    ) {
        val icon = game.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = game.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(iconShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(iconShape)
                    .background(Color(0xFFEDEDED)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_question_mark),
                    contentDescription = game.name,
                    tint = WiiUColors.TextSecondary,
                    modifier = Modifier.size(size * 0.4f),
                )
            }
        }
    }
}

@Composable
private fun GameSettingsList(
    game: NativeGameTitles.Game,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val viewModel: GameProfileEditViewModel = viewModel(
        key = "GameProfileEditViewModel:${game.path}",
        factory = GameProfileEditViewModel.Factory,
        extras = MutableCreationExtras().apply {
            set(GameProfileEditViewModel.GAME_KEY, game)
        },
    )
    val supportsLoadingCustomDrivers = remember { NativeEmulation.supportsLoadingCustomDriver() }
    val driverSettingChoices by viewModel.driverSettingChoices.collectAsState()
    val selectedDriverSetting by viewModel.selectedDriverSetting.collectAsState()
    val titleId = game.titleId

    var loadSharedLibraries by rememberSaveable {
        mutableStateOf(NativeGameTitles.isLoadingSharedLibrariesForTitleEnabled(titleId))
    }
    var shaderMultiplicationAccuracy by rememberSaveable {
        mutableStateOf(NativeGameTitles.isShaderMultiplicationAccuracyForTitleEnabled(titleId))
    }
    var cpuMode by rememberSaveable { mutableIntStateOf(NativeGameTitles.getCpuModeForTitle(titleId)) }
    var threadQuantum by rememberSaveable { mutableIntStateOf(NativeGameTitles.getThreadQuantumForTitle(titleId)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SettingsMaxWidth)
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header?.invoke()

            WiiUSectionTitle(tr("Emulation"))
            WiiUSwitchRow(
                label = tr("Load shared libraries"),
                description = tr("Load libraries from the cafeLibs directory"),
                icon = R.drawable.ic_package_2,
                checked = loadSharedLibraries,
                onCheckedChange = {
                    loadSharedLibraries = it
                    NativeGameTitles.setLoadingSharedLibrariesForTitleEnabled(titleId, it)
                },
            )
            WiiUSwitchRow(
                label = tr("Shader multiplication accuracy"),
                description = tr("Controls the accuracy of floating point multiplication in shaders"),
                icon = R.drawable.ic_auto_awesome,
                checked = shaderMultiplicationAccuracy,
                onCheckedChange = {
                    shaderMultiplicationAccuracy = it
                    NativeGameTitles.setShaderMultiplicationAccuracyForTitleEnabled(titleId, it)
                },
            )
            ChoiceCard(
                icon = R.drawable.ic_build,
                title = tr("CPU mode"),
                description = tr("How the Wii U processor is emulated for this game"),
                choices = listOf(
                    NativeGameTitles.CPUMode.AUTO,
                    NativeGameTitles.CPUMode.MULTICORERECOMPILER,
                    NativeGameTitles.CPUMode.SINGLECORERECOMPILER,
                    NativeGameTitles.CPUMode.SINGLECOREINTERPRETER,
                ),
                selected = cpuMode,
                choiceToString = ::cpuModeToString,
                onChoiceSelected = {
                    cpuMode = it
                    NativeGameTitles.setCpuModeForTitle(titleId, it)
                },
            )
            ChoiceCard(
                icon = R.drawable.ic_restart,
                title = tr("Thread quantum"),
                description = tr("How long each emulated CPU thread runs before the next one gets its turn"),
                choices = NativeGameTitles.THREAD_QUANTUM_VALUES,
                selected = threadQuantum,
                choiceToString = { it.toString() },
                onChoiceSelected = {
                    threadQuantum = it
                    NativeGameTitles.setThreadQuantumForTitle(titleId, it)
                },
            )
            if (supportsLoadingCustomDrivers && driverSettingChoices.isNotEmpty()) {
                ChoiceCard(
                    icon = R.drawable.ic_upgrade,
                    title = tr("Custom driver"),
                    description = tr("The graphics driver this game runs with"),
                    choices = driverSettingChoices,
                    selected = selectedDriverSetting,
                    choiceToString = ::customDriverSettingChoiceToString,
                    onChoiceSelected = viewModel::setSelectedDriverSetting,
                )
            }

            WiiUSectionTitle(tr("GamePad screen crop"))
            PadCropSettings(titleId)
        }
    }
}

// a setting with a few values, all of them shown as pills so one tap picks one
@Composable
private fun <T> ChoiceCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    choices: List<T>,
    selected: T,
    choiceToString: (T) -> String,
    onChoiceSelected: (T) -> Unit,
) {
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(text = title, color = WiiUColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    color = WiiUColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (choice in choices) {
                WiiUChip(
                    label = choiceToString(choice),
                    selected = choice == selected,
                    onClick = { onChoiceSelected(choice) },
                )
            }
        }
    }
}

private fun customDriverSettingChoiceToString(driverSettingChoice: DriverSettingChoice): String =
    when (driverSettingChoice.mode) {
        DriverSettingMode.GLOBAL -> tr("Global")
        DriverSettingMode.SYSTEM -> tr("System driver")
        else -> driverSettingChoice.customDriverData!!.name
    }

private fun cpuModeToString(cpuMode: Int): String = when (cpuMode) {
    NativeGameTitles.CPUMode.SINGLECOREINTERPRETER -> tr("Single-core interpreter")
    NativeGameTitles.CPUMode.SINGLECORERECOMPILER -> tr("Single-core recompiler")
    NativeGameTitles.CPUMode.MULTICORERECOMPILER -> tr("Multi-core recompiler")
    else -> tr("Auto (recommended)")
}
