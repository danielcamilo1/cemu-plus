package info.cemu.cemu.setupwizard

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBackground
import info.cemu.cemu.common.ui.wiiu.WiiUBar
import info.cemu.cemu.common.ui.wiiu.WiiUButton
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUHeroIcon
import info.cemu.cemu.common.ui.wiiu.WiiUStepIndicator
import info.cemu.cemu.common.ui.wiiu.WiiUTheme
import info.cemu.cemu.nativeinterface.NativeFrameGen
import info.cemu.cemu.nativeinterface.NativeFrameGen.LosslessDllStatus
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.settings.gamespath.GamesPathsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class WizardPage(val step: Int) {
    WELCOME(0),
    GAMES(1),
    CONTROLS(2),
    FRAME_GEN(3),
    TOOLBAR(4),
    DONE(0),
}

internal const val WIZARD_STEPS = 4

// the first time setup: games folder, controls, frame generation and the toolbar. Every step applies right away
// and can be skipped, leaving keeps what was set up so far and the wizard can be opened again from the settings
@Composable
fun SetupWizardScreen(
    onExit: () -> Unit,
    gamesPathsViewModel: GamesPathsViewModel = viewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = WizardPage.entries[pageIndex]
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    val gamesPaths by gamesPathsViewModel.gamesPaths.collectAsState()
    var dllStatus by remember { mutableIntStateOf(NativeFrameGen.getInstalledLosslessDllStatus()) }
    val nextFocusRequester = remember { FocusRequester() }

    fun goTo(target: WizardPage) {
        pageIndex = target.ordinal
    }

    fun complete() {
        coroutineScope.launch {
            NativeInput.saveInputs()
            NativeSettings.saveSettings()
            AppSettingsStore.dataStore.updateData {
                it.copy(guiSettings = it.guiSettings.copy(isSetupWizardCompleted = true))
            }
            onExit()
        }
    }

    BackHandler {
        if (page == WizardPage.WELCOME) showLeaveDialog = true else pageIndex--
    }

    // the next button starts focused so a controller can go through the setup with A
    LaunchedEffect(page) {
        delay(300)
        runCatching { nextFocusRequester.requestFocus() }
    }

    val canContinue = when (page) {
        WizardPage.GAMES -> gamesPaths.isNotEmpty()
        WizardPage.FRAME_GEN -> dllStatus == LosslessDllStatus.OK
        else -> true
    }

    WiiUTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            WiiUBackground()
            Column(modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()) {
                WizardHeader(page = page, onClose = { showLeaveDialog = true })
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = "page",
                ) { target ->
                    when (target) {
                        WizardPage.WELCOME -> WizardPageLayout(
                            icon = R.drawable.ic_videogame,
                            title = tr("Welcome to Cemu Plus"),
                            description = tr("Let's get a few things ready so you can jump straight into your games. It takes about a minute and every step can be skipped."),
                        ) { WelcomeContent() }

                        WizardPage.GAMES -> WizardPageLayout(
                            icon = R.drawable.ic_folder,
                            title = tr("Your games"),
                            description = tr("Show Cemu Plus where your Wii U games are and they will appear in your library."),
                        ) {
                            GamesStep(
                                gamesPaths = gamesPaths,
                                onAdd = gamesPathsViewModel::addGamesPath,
                                onRemove = gamesPathsViewModel::removeGamesPath,
                            )
                        }

                        WizardPage.CONTROLS -> WizardPageLayout(
                            icon = R.drawable.ic_controller,
                            title = tr("Controls"),
                            description = tr("Choose how you play and set up all the buttons of your controller in one go."),
                        ) { ControlsStep() }

                        WizardPage.FRAME_GEN -> WizardPageLayout(
                            icon = R.drawable.ic_auto_awesome,
                            title = tr("Frame generation"),
                            description = tr("Smoother motion with the frame generation of Lossless Scaling, using a copy you own."),
                        ) {
                            FrameGenStep(dllStatus = dllStatus, onDllStatusChanged = { dllStatus = it })
                        }

                        WizardPage.TOOLBAR -> WizardPageLayout(
                            icon = R.drawable.ic_dashboard,
                            title = tr("Toolbar"),
                            description = tr("Turn the black bars around the game into a toolbar with live info and quick buttons."),
                        ) { ToolbarStep() }

                        WizardPage.DONE -> WizardPageLayout(
                            icon = R.drawable.ic_celebration,
                            title = tr("You're all set!"),
                            description = tr("Here is how Cemu Plus is set up. Tap a step to change it, or change anything later in Settings."),
                        ) {
                            DoneContent(gamesPathCount = gamesPaths.size, onGoTo = ::goTo)
                        }
                    }
                }
                WizardFooter(
                    page = page,
                    canContinue = canContinue,
                    nextFocusRequester = nextFocusRequester,
                    onBack = { if (page == WizardPage.WELCOME) showLeaveDialog = true else pageIndex-- },
                    onSkip = { pageIndex++ },
                    onNext = { if (page == WizardPage.DONE) complete() else pageIndex++ },
                )
            }
        }

        if (showLeaveDialog) {
            AlertDialog(
                icon = { Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = null) },
                title = { Text(tr("Leave the setup?")) },
                text = { Text(tr("Everything you set up so far is kept. You can run the setup again any time from Settings > Setup wizard.")) },
                confirmButton = {
                    TextButton(onClick = {
                        showLeaveDialog = false
                        complete()
                    }) { Text(tr("Leave setup")) }
                },
                dismissButton = { TextButton(onClick = { showLeaveDialog = false }) { Text(tr("Keep going")) } },
                onDismissRequest = { showLeaveDialog = false },
            )
        }
    }
}

@Composable
private fun WizardHeader(page: WizardPage, onClose: () -> Unit) {
    WiiUBar(top = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("Cemu Plus Setup"),
                color = WiiUColors.TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (page.step > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WiiUStepIndicator(current = page.step, total = WIZARD_STEPS)
                    Text(
                        text = tr("Step {0} of {1}", page.step, WIZARD_STEPS),
                        color = WiiUColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (page != WizardPage.DONE) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = tr("Close"),
                            tint = WiiUColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardFooter(
    page: WizardPage,
    canContinue: Boolean,
    nextFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    WiiUBar(top = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WiiUButton(
                text = if (page == WizardPage.WELCOME) tr("Set up later") else tr("Back"),
                onClick = onBack,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (page.step > 0) {
                WiiUButton(text = tr("Skip"), onClick = onSkip)
            }
            WiiUButton(
                text = when (page) {
                    WizardPage.WELCOME -> tr("Let's start")
                    WizardPage.DONE -> tr("Start playing")
                    else -> tr("Next")
                },
                onClick = onNext,
                primary = true,
                enabled = canContinue,
                modifier = Modifier.focusRequester(nextFocusRequester),
            )
        }
    }
}

// the page title next to its options on wide screens, above them otherwise
@Composable
private fun WizardPageLayout(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 640.dp && maxWidth > maxHeight
        if (isWide) {
            Row(modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)) {
                Box(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        WizardHero(icon = icon, title = title, description = description, iconSize = 96)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        WizardHero(icon = icon, title = title, description = description, iconSize = 72)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun WizardHero(@DrawableRes icon: Int, title: String, description: String, iconSize: Int) {
    WiiUHeroIcon(icon = icon, size = iconSize)
    Text(
        text = title,
        color = WiiUColors.Text,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        text = description,
        color = WiiUColors.TextSecondary,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
}
