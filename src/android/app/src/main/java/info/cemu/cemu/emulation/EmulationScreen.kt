package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.EmulatedUSBDevicesDialog
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurface
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.DEFAULT
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_POSITION
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_SIZE
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.settings.SettingsRoute
import info.cemu.cemu.settings.SettingsStartScreen
import info.cemu.cemu.settings.navigateToStartScreen
import info.cemu.cemu.settings.settingsNavigation
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EmulationScreen(
    gamePath: String,
    setMotionSensorEnabled: (Boolean) -> Unit,
    setInputListeningEnabled: (Boolean) -> Unit,
    onQuit: () -> Unit,
    viewModel: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory, extras = MutableCreationExtras().apply {
            set(EmulationViewModel.LAUNCH_PATH_KEY, gamePath)
        }),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuitConfirmationDialog by remember { mutableStateOf(false) }
    var inputOverlayInputMode by rememberSaveable { mutableStateOf(DEFAULT) }
    var showEmulatedUSBDevices by remember { mutableStateOf(false) }

    val emulationError by viewModel.emulationError.collectAsState()
    val isEmulationInitialized by viewModel.isEmulationInitialized.collectAsState()
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val isInputOverlayVisible by viewModel.isInputOverlayVisible.collectAsState()
    val inputOverlaySettings by viewModel.inputOverlaySettings.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val isFrameGenSetupDialogVisible by viewModel.isFrameGenSetupDialogVisible.collectAsState()
    // true as soon as the menu starts opening, so it shows the current settings while it slides in
    val isMenuOpen = drawerState.targetValue == DrawerValue.Open


    fun closeDrawer() {
        scope.launch {
            drawerState.close()
        }
    }

    suspend fun toggleMenu() {
        drawerState.apply {
            if (isClosed) {
                open()
            } else {
                close()
            }
        }
    }

    BackHandler {
        if (drawerState.isAnimationRunning) {
            return@BackHandler
        }

        scope.launch {
            toggleMenu()
        }
    }

    LaunchedEffect(drawerState.isClosed, isSettingsOpen) {
        setInputListeningEnabled(drawerState.isClosed && !isSettingsOpen)
    }

    LaunchedEffect(sideMenuState.isMotionEnabled) {
        setMotionSensorEnabled(sideMenuState.isMotionEnabled)
    }

    LaunchedEffect(sideMenuState.isMotionXInverted, sideMenuState.isMotionYInverted) {
        NativeInput.setMotionInverted(sideMenuState.isMotionXInverted, sideMenuState.isMotionYInverted)
    }

    LaunchedEffect(sideMenuState.motionSensitivityX, sideMenuState.motionSensitivityY) {
        NativeInput.setMotionSensitivity(sideMenuState.motionSensitivityX / 100f, sideMenuState.motionSensitivityY / 100f)
    }

    LaunchedEffect(Unit) {
        viewModel.menuRequests.collect {
            if (!drawerState.isAnimationRunning) {
                toggleMenu()
            }
        }
    }

    LaunchedEffect(sideMenuState.isExternalScreenRotatedLeft) {
        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)
    }

    LaunchedEffect(Unit) {
        HotkeyManager.actions.collect { action ->
            when (action) {
                HotkeyAction.QUIT -> showQuitConfirmationDialog = true
                HotkeyAction.TOGGLE_MENU -> toggleMenu()
                HotkeyAction.SHOW_EMULATED_USB_DEVICES_DIALOG -> showEmulatedUSBDevices = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            QuickMenuSheet(isOpen = isMenuOpen) {
                QuickMenuContent(
                    isOpen = isMenuOpen,
                    isEmulationInitialized = isEmulationInitialized,
                    sideMenuState = sideMenuState,
                    updateState = {
                        viewModel.updateSideMenuState(it)
                        NativeEmulation.setReplaceTVWithPadView(it.isTVReplacedWithPad)
                    },
                    onBackToGame = ::closeDrawer,
                    onOpenSettings = {
                        closeDrawer()
                        viewModel.openSettings()
                    },
                    onQuit = {
                        showQuitConfirmationDialog = true
                        closeDrawer()
                    },
                    onOpenInputSettings = {
                        closeDrawer()
                        viewModel.openSettings(SettingsStartScreen.Input)
                    },
                    onOpenControllerSettings = { controllerIndex ->
                        closeDrawer()
                        viewModel.openSettings(SettingsStartScreen.Controller(controllerIndex))
                    },
                    onShowEmulatedUSBDevices = {
                        showEmulatedUSBDevices = true
                        closeDrawer()
                    },
                    onEditInputOverlay = {
                        snackbarHostState.showMessage(scope, tr("Edit input positions"))
                        inputOverlayInputMode = EDIT_POSITION
                        closeDrawer()
                    },
                    onResetInputOverlay = {
                        viewModel.resetInputOverlayLayout()
                        closeDrawer()
                    },
                )
            }
        },
    ) {
        EmulationSurfaces(
            viewModel = viewModel,
            isEmulationInitialized = isEmulationInitialized,
        )

        InputOverlaySurface(
            isVisible = isInputOverlayVisible,
            inputOverlaySettings = inputOverlaySettings,
            inputMode = inputOverlayInputMode,
            onEditFinished = { viewModel.saveInputOverlayRectangles(it) },
        )

        if (inputOverlayInputMode != DEFAULT) {
            EditInputsLayout(
                inputMode = inputOverlayInputMode,
                onFinishClick = {
                    snackbarHostState.showMessage(scope, tr("Exited input edit mode"))
                    inputOverlayInputMode = DEFAULT
                },
                onMoveClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input positions"))
                    inputOverlayInputMode = EDIT_POSITION
                },
                onResizeClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input size"))
                    inputOverlayInputMode = EDIT_SIZE
                },
            )
        }
    }

    if (isPaused && !isSettingsOpen) {
        PausedIndicator(onResume = { viewModel.setPaused(false) })
    }

    if (isSettingsOpen) {
        val panelBounds = remember { viewModel.getPanelBounds() }
        val startScreen = remember { viewModel.settingsStartScreen.value }
        EmulationSettings(
            panelBounds = panelBounds,
            startScreen = startScreen,
            onClose = viewModel::closeSettings,
        )
    }

    if (isFrameGenSetupDialogVisible) {
        FrameGenSetupDialog(
            onSetUp = viewModel::openFrameGenSettings,
            onDismiss = viewModel::dismissFrameGenSetupDialog,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    emulationError?.let {
        EmulationErrorDialog(it, onQuit)
    }

    if (!isEmulationInitialized) {
        EmulationLoadingDialog()
    }

    if (showQuitConfirmationDialog) {
        EmulationQuitConfirmationDialog(
            onQuit = onQuit,
            onDismiss = { showQuitConfirmationDialog = false },
        )
    }

    if (showEmulatedUSBDevices) {
        EmulatedUSBDevicesDialog(
            onDismiss = { showEmulatedUSBDevices = false },
        )
    }

    EmulationTextInputDialog()
}

@Composable
private fun PausedIndicator(onResume: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("Paused"), fontSize = 16.sp)
                TextButton(onClick = onResume) { Text(tr("Resume")) }
            }
        }
    }
}

// the part of the screen beside the panel, null if that is too small for the settings
private fun settingsAreaBesidePanel(screen: Rect, panel: Rect?): Rect? {
    if (panel == null) {
        return null
    }
    val area = listOf(
        Rect(screen.left, screen.top, screen.right, panel.top),
        Rect(screen.left, panel.bottom, screen.right, screen.bottom),
        Rect(screen.left, screen.top, panel.left, screen.bottom),
        Rect(panel.right, screen.top, screen.right, screen.bottom),
    ).filter { it.width > 0f && it.height > 0f }
        .maxByOrNull { it.width * it.height } ?: return null
    return if (area.width * area.height >= screen.width * screen.height * MIN_SETTINGS_AREA) area else null
}

private const val MIN_SETTINGS_AREA = 0.35f

// the emulator settings on top of the paused game, opened from the panel overlay. The panel stays visible
// next to them so its settings button can close them again
@Composable
private fun EmulationSettings(panelBounds: Rect?, startScreen: SettingsStartScreen, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screen = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val area = settingsAreaBesidePanel(screen, panelBounds) ?: screen
        val density = LocalDensity.current
        Surface(
            modifier = Modifier
                .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
        ) {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = SettingsRoute,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                settingsNavigation(navController, onExit = onClose)
            }
            LaunchedEffect(Unit) { navController.navigateToStartScreen(startScreen) }
        }
    }
}

@Composable
private fun EditInputsLayout(
    inputMode: InputOverlaySurfaceView.InputMode,
    onFinishClick: () -> Unit,
    onMoveClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onFinishClick) { Text(tr("Done")) }

            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(enabled = inputMode != EDIT_POSITION, onClick = onMoveClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_move),
                        contentDescription = tr("Move")
                    )
                }

                FilledIconButton(enabled = inputMode != EDIT_SIZE, onClick = onResizeClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_resize),
                        contentDescription = tr("Resize"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmulationSurfaces(
    viewModel: EmulationViewModel,
    isEmulationInitialized: Boolean,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val gamePadPosition by viewModel.gamePadPosition.collectAsState()
    val mainSurfaceDimensions by viewModel.mainSurfaceDimensions.collectAsState()
    val padSurfaceDimensions by viewModel.padSurfaceDimensions.collectAsState()

    val currentGamePadPosition = gamePadPosition ?: return

    val padDisplay = if (activity != null) rememberPadDisplay(activity) else null
    val isPadVisibleEffective = sideMenuState.isPadVisible && isEmulationInitialized
    // with a second screen the GamePad always goes there, Show PAD is for splitting a single screen
    val usePadPresentation =
        isEmulationInitialized && sideMenuState.isPadOnExternalDisplay && padDisplay != null

    val mainTouchListener = remember(viewModel) { CanvasOnTouchListener(viewModel::onOverlayAction) }
    val padTouchListener = remember(viewModel) { CanvasOnTouchListener(viewModel::onOverlayAction) }
    val padPresentationTouchListener = remember(viewModel) { CanvasOnTouchListener(viewModel::onOverlayAction) }

    // the swap is remembered, but only used while there is a GamePad screen to swap with
    val areScreensSwapped = sideMenuState.areScreensSwapped && (usePadPresentation || isPadVisibleEffective)
    LaunchedEffect(areScreensSwapped) {
        NativeEmulation.setSwapScreens(areScreensSwapped)
    }

    val isMainTargetingTV = !areScreensSwapped
    val mainTargetDimensions =
        if (isMainTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val isPadTargetingTV = areScreensSwapped
    val padTargetDimensions =
        if (isPadTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val rotatePresentationTouch = usePadPresentation && sideMenuState.isExternalScreenRotatedLeft

    LaunchedEffect(isPadTargetingTV, padTargetDimensions, padSurfaceDimensions, rotatePresentationTouch) {
        padPresentationTouchListener.updateConfiguration(
            isTv = isPadTargetingTV,
            surfaceWidth = padTargetDimensions.width,
            surfaceHeight = padTargetDimensions.height,
            rotateLeft = rotatePresentationTouch,
            isPadWindow = true,
            windowWidth = padSurfaceDimensions.width,
            windowHeight = padSurfaceDimensions.height,
        )
    }

    DisposableEffect(activity, padDisplay, usePadPresentation, sideMenuState.isExternalScreenRotatedLeft) {
        val activityNonNull = activity ?: return@DisposableEffect onDispose {}
        if (!usePadPresentation) {
            return@DisposableEffect onDispose {}
        }
        val padDisplayNonNull = padDisplay

        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)

        val padPresentation = PadPresentation(
            context = activityNonNull,
            display = padDisplayNonNull,
            rotateLeft = sideMenuState.isExternalScreenRotatedLeft,
            holderCallback = viewModel.padHolderCallback,
            touchListener = padPresentationTouchListener,
        )

        padPresentation.show()

        onDispose { padPresentation.dismiss() }
    }

    LinearLayout(currentGamePadPosition) { itemModifier ->
        EmulationSurface(
            modifier = itemModifier.onGloballyPositioned {
                viewModel.setSurfaceLayout(true, it.boundsInRoot(), mainSurfaceDimensions)
            },
            holderCallback = viewModel.mainHolderCallback,
            touchListener = mainTouchListener,
            touchIsTv = isMainTargetingTV,
            touchSurfaceWidth = mainTargetDimensions.width,
            touchSurfaceHeight = mainTargetDimensions.height,
            isPadWindow = false,
            windowDimensions = mainSurfaceDimensions,
            afterInit = { viewModel.initializeEmulation() },
        )

        if (isPadVisibleEffective && !usePadPresentation) {
            DisposableEffect(Unit) {
                onDispose { viewModel.removeSurfaceLayout(false) }
            }
            EmulationSurface(
                modifier = itemModifier.onGloballyPositioned {
                    viewModel.setSurfaceLayout(false, it.boundsInRoot(), padSurfaceDimensions)
                },
                holderCallback = viewModel.padHolderCallback,
                touchListener = padTouchListener,
                touchIsTv = isPadTargetingTV,
                touchSurfaceWidth = padTargetDimensions.width,
                touchSurfaceHeight = padTargetDimensions.height,
                isPadWindow = true,
                windowDimensions = padSurfaceDimensions,
            )
        }
    }
}

// the TV and GamePad surfaces side by side or stacked, each getting the same share of the screen. The children are
// only placed in a different order, so moving the GamePad doesn't recreate the surfaces
@Composable
private fun LinearLayout(
    gamePadPosition: GamePadPosition,
    content: @Composable (itemModifier: Modifier) -> Unit,
) {
    val vertical = gamePadPosition.isVertical()
    val padFirst = !gamePadPosition.appearsAfterTV()
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = { content(Modifier) },
    ) { measurables, constraints ->
        val count = measurables.size.coerceAtLeast(1)
        val itemWidth = if (vertical) constraints.maxWidth else constraints.maxWidth / count
        val itemHeight = if (vertical) constraints.maxHeight / count else constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints.fixed(itemWidth, itemHeight)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val slot = if (padFirst && placeables.size > 1) placeables.size - 1 - index else index
                if (vertical) placeable.place(0, slot * itemHeight) else placeable.place(slot * itemWidth, 0)
            }
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
private fun EmulationSurface(
    modifier: Modifier,
    holderCallback: SurfaceHolder.Callback,
    touchListener: CanvasOnTouchListener,
    touchIsTv: Boolean,
    touchSurfaceWidth: Int,
    touchSurfaceHeight: Int,
    isPadWindow: Boolean,
    windowDimensions: SurfaceDimensions,
    rotateLeft: Boolean = false,
    afterInit: () -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                var firstChange = true

                setOnTouchListener(touchListener)

                holder.addCallback(holderCallback)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        if (firstChange) {
                            afterInit()
                            firstChange = false
                        }
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        },
        update = {
            touchListener.updateConfiguration(
                isTv = touchIsTv,
                surfaceWidth = touchSurfaceWidth,
                surfaceHeight = touchSurfaceHeight,
                rotateLeft = rotateLeft,
                isPadWindow = isPadWindow,
                windowWidth = windowDimensions.width,
                windowHeight = windowDimensions.height,
            )
        },
    )
}

@Composable
private fun rememberPadDisplay(activity: Activity): Display? {
    val displayManager =
        remember(activity) { activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    var padDisplay by remember { mutableStateOf<Display?>(null) }

    fun updatePadDisplay() {
        padDisplay =
            if (activity.display.displayId == Display.DEFAULT_DISPLAY) {
                DisplayUtils.getExternalDisplay(activity)
            } else {
                DisplayUtils.getInternalDisplay(activity)
            }
    }

    DisposableEffect(displayManager, activity) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updatePadDisplay()
            override fun onDisplayRemoved(displayId: Int) = updatePadDisplay()
            override fun onDisplayChanged(displayId: Int) = updatePadDisplay()
        }

        updatePadDisplay()
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return padDisplay
}

// the frame generation buttons of the panel need Lossless.dll, this explains it and leads to where it is added
@Composable
private fun FrameGenSetupDialog(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        icon = { Icon(painter = painterResource(R.drawable.ic_build), contentDescription = null) },
        title = { Text(tr("Set up frame generation first")) },
        text = {
            Text(tr("Frame generation uses the shaders of Lossless Scaling. Add Lossless.dll from your own copy of Lossless Scaling and this button will start working. It only takes a moment and the game stays paused while you do it."))
        },
        confirmButton = { TextButton(onClick = onSetUp) { Text(tr("Set up now")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationQuitConfirmationDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(tr("Exit confirmation")) },
        text = { Text(tr("Are you sure you want to exit?")) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Yes")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("No")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationLoadingDialog() {
    AlertDialog(
        title = { Text(tr("Initializing emulation")) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        onDismissRequest = {},
    )
}

@Composable
private fun EmulationErrorDialog(error: NativeError, onQuit: () -> Unit) {
    val errorMessage = remember(error) {
        when (error) {
            is NativeError.SystemInitializationError -> tr("Failed to initialize")

            is NativeError.RendererInitializationError ->
                tr("Failed creating renderer: {0}", error.message)

            is NativeError.SurfaceCreationError -> tr("Failed creating surface: {0}", error.message)

            NativeError.GameFilesNotFoundError -> tr("Unable to launch game because the base files were not found.")
            NativeError.NoDiscKeysError -> tr("Could not decrypt title. Make sure that keys.txt contains the correct disc key for this title.")
            NativeError.NoTitleTikError -> tr("Could not decrypt title because title.tik is missing.")
            is NativeError.UnknownTilePrepareError ->
                tr("Unable to launch game\nPath: {0}", error.launchPath)

            NativeError.LaunchingTitleError -> tr("Failed to launch title")
        }
    }
    AlertDialog(
        title = { Text(tr("Error")) },
        text = { Text(errorMessage) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Quit")) } },
        onDismissRequest = {},
    )
}
