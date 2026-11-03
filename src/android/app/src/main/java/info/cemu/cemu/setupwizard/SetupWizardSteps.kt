package info.cemu.cemu.setupwizard

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.SecondScreenPresentation
import info.cemu.cemu.common.android.display.rememberSecondScreen
import info.cemu.cemu.common.framegen.FrameGenMultiplierChoices
import info.cemu.cemu.common.framegen.frameGenMultiplierToString
import info.cemu.cemu.common.framegen.hasLosslessDll
import info.cemu.cemu.common.framegen.installLosslessDll
import info.cemu.cemu.common.framegen.losslessDllStatusToString
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.ui.localization.controllerTypeToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBodyText
import info.cemu.cemu.common.ui.wiiu.WiiUButton
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUChip
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUOptionCard
import info.cemu.cemu.common.ui.wiiu.WiiURowIcon
import info.cemu.cemu.common.ui.wiiu.WiiUSectionTitle
import info.cemu.cemu.common.ui.wiiu.WiiUSwitchRow
import info.cemu.cemu.nativeinterface.NativeFrameGen
import info.cemu.cemu.nativeinterface.NativeFrameGen.LosslessDllStatus
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeInput.EmulatedControllerType
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.settings.input.controller.ControllersViewModel
import info.cemu.cemu.settings.screenlayout.AlignmentChoices
import info.cemu.cemu.settings.screenlayout.FallbackSecondScreen
import info.cemu.cemu.settings.screenlayout.HiddenToolbarPositionChoices
import info.cemu.cemu.settings.screenlayout.PreviewPadColor
import info.cemu.cemu.settings.screenlayout.ToolbarContents
import info.cemu.cemu.settings.screenlayout.areScreensSwappedEffective
import info.cemu.cemu.settings.screenlayout.floatingToolbarFraction
import info.cemu.cemu.settings.screenlayout.isToolbarOnPadWindow
import info.cemu.cemu.settings.screenlayout.screenPositionToString
import info.cemu.cemu.settings.screenlayout.PreviewCanvas
import info.cemu.cemu.settings.screenlayout.ScreenLayoutSettingsViewModel
import info.cemu.cemu.settings.screenlayout.ToolbarItem
import info.cemu.cemu.settings.screenlayout.WII_U_ASPECT_RATIO
import info.cemu.cemu.settings.screenlayout.alignmentToString
import info.cemu.cemu.settings.screenlayout.applyToolbarSettings
import info.cemu.cemu.settings.screenlayout.drawSecondScreenPreview
import info.cemu.cemu.settings.screenlayout.drawSingleScreenPreview
import info.cemu.cemu.settings.screenlayout.toolbarScreenFor
import info.cemu.cemu.settings.screenlayout.isToolbarEnabled
import info.cemu.cemu.settings.screenlayout.rememberMainScreen
import info.cemu.cemu.settings.screenlayout.secondScreenExplanation
import info.cemu.cemu.settings.screenlayout.singleScreenExplanation
import info.cemu.cemu.settings.screenlayout.toolbarButtonItems
import info.cemu.cemu.settings.screenlayout.toolbarInformationItems
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val MAX_PREVIEW_TOOLBAR_TILES = 8

@Composable
internal fun WelcomeContent() {
    WizardOverviewRow(R.drawable.ic_folder, tr("Your games"), tr("Pick the folder with your Wii U games"))
    WizardOverviewRow(R.drawable.ic_controller, tr("Controls"), tr("Wii U GamePad or Pro Controller, buttons mapped for you"))
    WizardOverviewRow(R.drawable.ic_auto_awesome, tr("Frame generation"), tr("Smoother motion with Lossless Scaling"))
    WizardOverviewRow(R.drawable.ic_dashboard, tr("Toolbar"), tr("Live info and quick buttons in the black bars"))
}

@Composable
private fun WizardOverviewRow(@DrawableRes icon: Int, title: String, description: String) {
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(icon)
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(text = title, color = WiiUColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                WiiUBodyText(description)
            }
        }
    }
}

// "primary:Games/Wii U" reads as "Internal storage/Games/Wii U"
private fun gamesPathToDisplayName(gamesPath: String): String = runCatching {
    val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(gamesPath))
    val volume = documentId.substringBefore(':')
    val path = documentId.substringAfter(':')
    val volumeName = if (volume == "primary") tr("Internal storage") else volume
    if (path.isEmpty()) volumeName else "$volumeName/$path"
}.getOrDefault(gamesPath)

@Composable
internal fun GamesStep(
    gamesPaths: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val gamesPath = DocumentFile.fromTreeUri(context, uri)?.uri?.toString()
            ?: return@rememberLauncherForActivityResult
        message = if (gamesPaths.contains(gamesPath)) tr("That folder is already added") else null
        onAdd(gamesPath)
    }

    WiiUCard(modifier = Modifier.fillMaxWidth(), onClick = { launcher.launch(null) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(R.drawable.ic_add)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = if (gamesPaths.isEmpty()) tr("Choose your games folder") else tr("Add another folder"),
                    color = WiiUColors.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                WiiUBodyText(tr("Games in its subfolders are found too"))
            }
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = WiiUColors.TextSecondary)
        }
    }
    message?.let { WiiUBodyText(it, color = WiiUColors.AccentDark) }

    if (gamesPaths.isNotEmpty()) {
        WiiUSectionTitle(tr("Added folders"))
    }
    for (gamesPath in gamesPaths) {
        WiiUCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WiiURowIcon(R.drawable.ic_folder, tint = WiiUColors.Success)
                Text(
                    text = gamesPathToDisplayName(gamesPath),
                    color = WiiUColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                )
                IconButton(onClick = { onRemove(gamesPath) }) {
                    Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = tr("Remove"), tint = WiiUColors.TextSecondary)
                }
            }
        }
    }
    WiiUBodyText(tr("Cemu Plus reads WUA, WUD, WUX and RPX files and extracted game folders. You can add more folders later in Settings > General settings > Game paths."))
}

// the toolbar goes with the GamePad or the TV image as chosen, so it is moved along when the screens change
private fun reapplyToolbar(
    useSecondScreen: Boolean,
    isPadVisible: Boolean,
    swapped: Boolean,
    chosenScreen: Int = NativeSettings.getOverlayScreen(),
) {
    if (!isToolbarEnabled()) {
        return
    }
    val screen = toolbarScreenFor(useSecondScreen, isPadVisible, chosenScreen)
    applyToolbarSettings(
        enabled = true,
        screen = screen,
        alignment = NativeSettings.getPadScreenAlignment(),
        swapped = areScreensSwappedEffective(useSecondScreen, isPadVisible, swapped),
    )
}

// a screen that only says which image it gets in game, the TV image white and the GamePad image light gray like the
// layout previews
@Composable
private fun ScreenRoleImage(isGamePad: Boolean, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(WII_U_ASPECT_RATIO)
            .background(if (isGamePad) PreviewPadColor else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Icon(
                painter = painterResource(if (isGamePad) R.drawable.ic_tablet else R.drawable.ic_videogame),
                contentDescription = null,
                tint = WiiUColors.Accent,
                modifier = Modifier.size(if (subtitle != null) 72.dp else 28.dp),
            )
            Text(
                text = title,
                color = Color(0xFF202024),
                fontSize = if (subtitle != null) 44.sp else 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            subtitle?.let {
                Text(text = it, color = Color(0xFF505058), fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun screenRoleName(isGamePad: Boolean) = if (isGamePad) tr("Wii U GamePad") else tr("TV")

// shown on the second screen itself while the screens are set up, so it is easy to tell which one gets what
@Composable
private fun SecondScreenRolePreview(isGamePad: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ScreenRoleImage(
            isGamePad = isGamePad,
            title = screenRoleName(isGamePad),
            subtitle = if (isGamePad) tr("This screen shows the GamePad image while you play")
            else tr("This screen shows the TV image while you play"),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ScreenRolesCard(swapped: Boolean, secondScreenName: String) {
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((isGamePad, caption) in listOf(swapped to tr("This screen"), !swapped to secondScreenName)) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScreenRoleImage(
                        isGamePad = isGamePad,
                        title = screenRoleName(isGamePad),
                        subtitle = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    WiiUBodyText(caption, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        WiiUBodyText(
            tr("Look at your second screen: it shows what it gets in game. Swap the screens if it should be the other way around."),
            modifier = Modifier.padding(top = 10.dp),
            color = WiiUColors.Text,
        )
    }
}

@Composable
internal fun ControlsStep(
    controllersViewModel: ControllersViewModel = viewModel(
        factory = ControllersViewModel.Factory, extras = MutableCreationExtras().apply {
            set(ControllersViewModel.CONTROLLER_INDEX_KEY, 0)
        }),
    screenLayoutViewModel: ScreenLayoutSettingsViewModel = viewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    val controllerType by controllersViewModel.controllerType.collectAsState()
    val controllers by controllersViewModel.controllers.collectAsState()
    val controls by controllersViewModel.controls.collectAsState()
    val emulationSettings by screenLayoutViewModel.emulationSettings.collectAsState()
    val secondScreen = rememberSecondScreen()
    val isTouchOverlayEnabled by remember {
        AppSettingsStore.dataStore.data.map { it.inputOverlaySettings.isOverlayEnabled }
    }.collectAsState(initial = false)
    var selectedDeviceId by remember { mutableStateOf<Int?>(null) }
    var mappedDeviceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        controllersViewModel.refreshAvailableControllers()
    }
    DisposableEffect(Unit) {
        onDispose { controllersViewModel.save() }
    }
    val selectedController = controllers.firstOrNull { it.id == selectedDeviceId } ?: controllers.firstOrNull()

    WiiUSectionTitle(tr("Controller 1 plays as"))
    WiiUOptionCard(
        icon = R.drawable.ic_tablet,
        title = tr("Wii U GamePad"),
        badge = tr("Recommended"),
        description = if (secondScreen != null) tr("Works with every game. Its touch screen goes on your second screen")
        else tr("Works with every game, including the ones that use the touch screen"),
        selected = controllerType == EmulatedControllerType.VPAD,
        onClick = { controllersViewModel.setControllerType(EmulatedControllerType.VPAD) },
    )
    WiiUOptionCard(
        icon = R.drawable.ic_videogame,
        title = tr("Pro Controller"),
        description = tr("A regular controller without the touch screen. Some games need the GamePad"),
        selected = controllerType == EmulatedControllerType.PRO,
        onClick = { controllersViewModel.setControllerType(EmulatedControllerType.PRO) },
    )

    if (secondScreen != null) {
        WiiUSwitchRow(
            icon = R.drawable.ic_tablet,
            label = tr("GamePad on the second screen"),
            description = tr("Shows the GamePad screen on {0}, like holding a real GamePad", secondScreen.name),
            checked = emulationSettings.isPadOnExternalDisplay,
            onCheckedChange = {
                screenLayoutViewModel.setSecondScreenEnabled(it)
                reapplyToolbar(
                    useSecondScreen = it,
                    isPadVisible = emulationSettings.isPadVisible,
                    swapped = emulationSettings.areScreensSwapped,
                    chosenScreen = NativeSettings.OverlayScreen.PAD,
                )
            },
        )
        if (emulationSettings.isPadOnExternalDisplay) {
            WiiUSwitchRow(
                icon = R.drawable.ic_swap_vert,
                label = tr("Swap screens"),
                description = tr("The GamePad on this screen and the TV image on {0}. For devices where the screens are found the other way around", secondScreen.name),
                checked = emulationSettings.areScreensSwapped,
                onCheckedChange = {
                    screenLayoutViewModel.setScreensSwapped(it)
                    reapplyToolbar(useSecondScreen = true, isPadVisible = emulationSettings.isPadVisible, swapped = it)
                },
            )
            ScreenRolesCard(swapped = emulationSettings.areScreensSwapped, secondScreenName = secondScreen.name)
            SecondScreenPresentation {
                SecondScreenRolePreview(isGamePad = !emulationSettings.areScreensSwapped)
            }
        }
    } else if (controllerType == EmulatedControllerType.VPAD) {
        WiiUSwitchRow(
            icon = R.drawable.ic_tablet,
            label = tr("Show the GamePad screen too"),
            description = tr("Splits this screen between the TV and GamePad images, for games that use both"),
            checked = emulationSettings.isPadVisible,
            onCheckedChange = {
                screenLayoutViewModel.setPadVisible(it)
                reapplyToolbar(useSecondScreen = false, isPadVisible = it, swapped = emulationSettings.areScreensSwapped)
            },
        )
    }

    WiiUSectionTitle(tr("Map your buttons"))
    if (controllers.isEmpty()) {
        WiiUCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = tr("No controller found"), color = WiiUColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            WiiUBodyText(
                tr("Connect a controller, or turn on the touch controls below. Built-in controls of handhelds show up here as a controller."),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            WiiUButton(
                text = tr("Look again"),
                icon = R.drawable.ic_refresh,
                onClick = { controllersViewModel.refreshAvailableControllers() },
            )
        }
    } else {
        for (controller in controllers) {
            WiiUOptionCard(
                icon = R.drawable.ic_controller,
                title = controller.name,
                description = if (controller.id == selectedController?.id) tr("The buttons will be mapped from this controller")
                else tr("Tap to use this controller"),
                selected = controller.id == selectedController?.id,
                onClick = { selectedDeviceId = controller.id },
            )
        }
        val canMap = controllerType != EmulatedControllerType.DISABLED && selectedController != null
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WiiUButton(
                text = tr("Map all buttons automatically"),
                icon = R.drawable.ic_auto_awesome,
                primary = true,
                enabled = canMap,
                onClick = {
                    val controller = selectedController ?: return@WiiUButton
                    controllersViewModel.mapAllInputs(controller.id)
                    controllersViewModel.save()
                    mappedDeviceName = controller.name
                },
            )
        }
        if (controllerType == EmulatedControllerType.DISABLED) {
            WiiUBodyText(tr("Choose how controller 1 plays first"))
        }
        mappedDeviceName?.let {
            WizardSuccessText(tr("{0} buttons mapped from {1}", controls.size, it))
        }
    }

    WiiUSwitchRow(
        label = tr("Touch controls"),
        description = tr("Buttons and sticks drawn on top of the game, for when no controller is around"),
        checked = isTouchOverlayEnabled,
        onCheckedChange = { enabled ->
            coroutineScope.launch {
                AppSettingsStore.dataStore.updateData {
                    it.copy(inputOverlaySettings = it.inputOverlaySettings.copy(isOverlayEnabled = enabled))
                }
            }
        },
    )
    WiiUBodyText(tr("You can change single buttons later in Settings > Input settings > Controller 1."))
}

@Composable
private fun WizardSuccessText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(WiiUColors.Success, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(text = text, color = WiiUColors.Success, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun WizardNumberedStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(WiiUColors.Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        WiiUBodyText(text, modifier = Modifier.padding(start = 12.dp, top = 3.dp), color = WiiUColors.Text)
    }
}

@Composable
internal fun FrameGenStep(dllStatus: Int, onDllStatusChanged: (Int) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isInstalling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(NativeFrameGen.isEnabled()) }
    var multiplier by remember { mutableIntStateOf(NativeFrameGen.getMultiplier()) }
    val isInstalled = dllStatus == LosslessDllStatus.OK

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isInstalling = true
        error = null
        coroutineScope.launch {
            val status = installLosslessDll(context, uri)
            isInstalling = false
            if (status != LosslessDllStatus.OK) {
                error = tr("That file did not work: {0}", losslessDllStatusToString(status))
            }
            onDllStatusChanged(NativeFrameGen.getInstalledLosslessDllStatus())
        }
    }

    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(R.drawable.ic_auto_awesome, tint = if (isInstalled) WiiUColors.Success else WiiUColors.Accent)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = if (isInstalled) tr("Lossless.dll is added") else tr("Lossless.dll is not added yet"),
                    color = WiiUColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                WiiUBodyText(
                    if (isInstalled) tr("Frame generation is ready to use")
                    else tr("Frame generation needs it, the toolbar buttons stay grayed out until then")
                )
            }
        }
    }

    if (!isInstalled) {
        WiiUSectionTitle(tr("How to get it"))
        WizardNumberedStep(1, tr("Lossless Scaling is a Windows app sold on Steam. On the PC where you own it, right click it in Steam and choose Manage > Browse local files."))
        WizardNumberedStep(2, tr("Copy Lossless.dll from that folder to this device."))
        WizardNumberedStep(3, tr("Tap Select Lossless.dll below and pick the file. It is only read for its shaders, never run."))
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (isInstalling) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = WiiUColors.Accent, strokeWidth = 3.dp)
                WiiUBodyText(tr("Checking the file..."), modifier = Modifier.padding(start = 12.dp), color = WiiUColors.Text)
            }
        } else {
            WiiUButton(
                text = if (isInstalled) tr("Replace Lossless.dll") else tr("Select Lossless.dll"),
                icon = R.drawable.ic_folder,
                primary = !isInstalled,
                onClick = { launcher.launch(arrayOf("*/*")) },
            )
        }
    }
    error?.let { WiiUBodyText(it, color = Color(0xFFD03A2F)) }

    if (isInstalled) {
        WiiUSwitchRow(
            label = tr("Turn on frame generation"),
            description = tr("You can also switch it on and off with the toolbar button while playing"),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                NativeFrameGen.setEnabled(it)
                NativeSettings.saveSettings()
            },
        )
        WiiUSectionTitle(tr("Frame multiplier"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in FrameGenMultiplierChoices) {
                WiiUChip(
                    label = frameGenMultiplierToString(choice),
                    selected = multiplier == choice,
                    onClick = {
                        multiplier = choice
                        NativeFrameGen.setMultiplier(choice)
                        NativeSettings.saveSettings()
                    },
                )
            }
        }
        WiiUBodyText(tr("2x is the smoothest to run. Higher multipliers need a faster GPU."))
    }
    WiiUBodyText(tr("Don't own Lossless Scaling? Skip this step, everything else works without it."))
}

@Composable
internal fun ToolbarStep(screenLayoutViewModel: ScreenLayoutSettingsViewModel = viewModel()) {
    val emulationSettings by screenLayoutViewModel.emulationSettings.collectAsState()
    val detectedScreen = rememberSecondScreen()
    val mainScreen = rememberMainScreen()
    val density = LocalDensity.current.density
    val useSecondScreen = emulationSettings.isPadOnExternalDisplay
    val isPadVisible = emulationSettings.isPadVisible
    val swapped = areScreensSwappedEffective(useSecondScreen, isPadVisible, emulationSettings.areScreensSwapped)
    var toolbarEnabled by remember { mutableStateOf(isToolbarEnabled()) }
    var alignment by remember { mutableIntStateOf(NativeSettings.getPadScreenAlignment()) }
    var chosenScreen by remember { mutableIntStateOf(NativeSettings.getOverlayScreen()) }
    var hiddenPosition by remember { mutableIntStateOf(NativeSettings.getOverlayHiddenPosition()) }
    val informationItems = toolbarInformationItems()
    val buttonItems = toolbarButtonItems()
    val itemStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            (informationItems + buttonItems).forEach { put(it.label, it.isEnabled()) }
        }
    }
    val toolbarScreen = toolbarScreenFor(useSecondScreen, isPadVisible, chosenScreen)
    val toolbarOnPadWindow = isToolbarOnPadWindow(toolbarScreen, swapped)
    val secondScreen = detectedScreen ?: FallbackSecondScreen
    val isLetterboxed = secondScreen.width.toFloat() / secondScreen.height < WII_U_ASPECT_RATIO
    // the toolbar contents decide whether it fits in the bars, the shader status takes a row instead of a tile
    val shadersLabel = informationItems.first().label
    val hasStatus = itemStates[shadersLabel] == true
    val contents = ToolbarContents(tiles = itemStates.values.count { it } - (if (hasStatus) 1 else 0), hasStatus = hasStatus)
    val tileCount = itemStates.values.count { it }.coerceIn(1, MAX_PREVIEW_TOOLBAR_TILES)
    val secondScreenFloating = floatingToolbarFraction(secondScreen, alignment, contents, density)
    val mainScreenFloating = floatingToolbarFraction(mainScreen, NativeSettings.PadScreenAlignment.CENTER, contents, density)
    // the preview here shows the screen the toolbar is on
    val previewSecondScreen = useSecondScreen && toolbarOnPadWindow
    val previewScreen = if (previewSecondScreen) secondScreen else mainScreen
    val previewFloating = when {
        previewSecondScreen -> secondScreenFloating
        isPadVisible && !useSecondScreen -> null
        else -> mainScreenFloating
    }
    val floatsOverGame = toolbarEnabled && previewFloating != null

    fun applyChanges(enabled: Boolean = toolbarEnabled, align: Int = alignment, screen: Int = toolbarScreen) =
        applyToolbarSettings(enabled = enabled, screen = screen, alignment = align, swapped = swapped)

    // the second screen shows itself as it will look in game, to judge the layout on the real screen
    if (useSecondScreen) {
        SecondScreenPresentation {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                PreviewCanvas(
                    screen = secondScreen,
                    toolbarColor = WiiUColors.Accent,
                    toolbarTiles = tileCount,
                    floatingToolbar = secondScreenFloating,
                    floatingAtTop = alignment != NativeSettings.PadScreenAlignment.TOP,
                    modifier = Modifier.fillMaxSize(),
                ) { paint ->
                    drawSecondScreenPreview(alignment, toolbarEnabled && toolbarOnPadWindow, paint)
                }
            }
        }
    }

    WiiUBodyText(
        if (useSecondScreen) secondScreenExplanation(secondScreen, detectedScreen != null, isLetterboxed)
        else singleScreenExplanation(mainScreen, isPadVisible),
        color = WiiUColors.Text,
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        WiiUCard(modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()) {
            PreviewCanvas(
                screen = previewScreen,
                toolbarColor = WiiUColors.Accent,
                toolbarTiles = tileCount,
                floatingToolbar = previewFloating,
                floatingAtTop = !previewSecondScreen || alignment != NativeSettings.PadScreenAlignment.TOP,
            ) { paint ->
                if (previewSecondScreen) {
                    drawSecondScreenPreview(alignment, toolbarEnabled, paint)
                } else {
                    drawSingleScreenPreview(
                        if (isPadVisible && !useSecondScreen) emulationSettings.gamePadPosition else null,
                        toolbarEnabled,
                        toolbarScreen == NativeSettings.OverlayScreen.PAD,
                        paint,
                    )
                }
            }
            WiiUBodyText(
                text = when {
                    previewSecondScreen && detectedScreen != null -> tr("Preview of your second screen, it is also shown on {0}", detectedScreen.name)
                    previewSecondScreen -> tr("Preview of your second screen")
                    else -> tr("Preview of this screen")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }

    WiiUSwitchRow(
        icon = R.drawable.ic_dashboard,
        label = tr("Fill the black bars with the toolbar"),
        description = tr("When the bars are too thin for it, it floats over the game and can be minimized"),
        checked = toolbarEnabled,
        onCheckedChange = {
            toolbarEnabled = it
            applyChanges(enabled = it)
        },
    )
    if (!toolbarEnabled) {
        return
    }

    if (useSecondScreen || isPadVisible) {
        WiiUSectionTitle(tr("Toolbar goes on"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in listOf(NativeSettings.OverlayScreen.PAD, NativeSettings.OverlayScreen.TV)) {
                WiiUChip(
                    label = when {
                        choice == NativeSettings.OverlayScreen.PAD && useSecondScreen -> tr("The GamePad screen")
                        choice == NativeSettings.OverlayScreen.PAD -> tr("The GamePad image")
                        useSecondScreen -> tr("The TV screen")
                        else -> tr("The TV image")
                    },
                    selected = toolbarScreen == choice,
                    onClick = {
                        chosenScreen = choice
                        applyChanges(screen = choice)
                    },
                )
            }
        }
        if (useSecondScreen) {
            WiiUBodyText(tr("The GamePad screen is the usual place, next to the touch screen. Pick the TV screen to keep the GamePad image clear."))
        }
    }

    if (useSecondScreen && isLetterboxed) {
        WiiUSectionTitle(tr("Game image position on the second screen"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in AlignmentChoices) {
                WiiUChip(
                    label = alignmentToString(choice),
                    selected = alignment == choice,
                    onClick = {
                        alignment = choice
                        NativeSettings.setPadScreenAlignment(choice)
                        applyChanges(align = choice)
                    },
                )
            }
        }
        WiiUBodyText(tr("Top or Bottom keep the free space in one big bar, which gives the toolbar the most room."))
    }

    WiiUSectionTitle(tr("When it floats over the game"))
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(R.drawable.ic_dashboard, tint = if (floatsOverGame) WiiUColors.AccentDark else WiiUColors.Accent)
            WiiUBodyText(
                if (floatsOverGame) tr("The black bars of this layout are too thin for the toolbar, so it floats over the game. Tap the – key in its top right corner to minimize it into a small frame rate bubble, and tap the bubble to bring it back.")
                else tr("The toolbar fits in the black bars of this layout. If it doesn't fit, like with a game image that fills the screen, it floats over the game and its – key minimizes it into a small frame rate bubble."),
                color = WiiUColors.Text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            )
        }
    }
    WiiUBodyText(tr("Where the minimized toolbar goes"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in HiddenToolbarPositionChoices) {
            WiiUChip(
                label = screenPositionToString(choice),
                selected = hiddenPosition == choice,
                onClick = {
                    hiddenPosition = choice
                    NativeSettings.setOverlayHiddenPosition(choice)
                },
            )
        }
    }

    WiiUBodyText(tr("Pick what the toolbar shows. These only add items to the toolbar, they do not turn the features themselves on or off."))
    WiiUSectionTitle(tr("Information"))
    ToolbarChips(informationItems, itemStates)
    WiiUSectionTitle(tr("Buttons"))
    ToolbarChips(buttonItems, itemStates)
    if (!hasLosslessDll()) {
        WiiUBodyText(tr("The frame generation buttons ask you to add Lossless.dll first."))
    }
}

@Composable
private fun ToolbarChips(items: List<ToolbarItem>, states: MutableMap<String, Boolean>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) {
            WiiUChip(
                label = item.shortLabel,
                selected = states[item.label] == true,
                onClick = {
                    val enabled = states[item.label] != true
                    states[item.label] = enabled
                    item.setEnabled(enabled)
                },
            )
        }
    }
}

@Composable
internal fun DoneContent(gamesPathCount: Int, onGoTo: (WizardPage) -> Unit) {
    val secondScreenEnabled by remember {
        AppSettingsStore.dataStore.data.map { it.emulationSettings.isPadOnExternalDisplay }
    }.collectAsState(initial = false)
    val controllerType = remember { NativeInput.getControllerType(0) }
    val hasDll = remember { hasLosslessDll() }
    val frameGenEnabled = remember { NativeFrameGen.isEnabled() }
    val toolbarEnabled = remember { isToolbarEnabled() }

    WizardSummaryRow(
        icon = R.drawable.ic_folder,
        title = tr("Your games"),
        status = if (gamesPathCount > 0) tr("{0} folder(s) added", gamesPathCount) else tr("No games folder yet"),
        isDone = gamesPathCount > 0,
        onClick = { onGoTo(WizardPage.GAMES) },
    )
    WizardSummaryRow(
        icon = R.drawable.ic_controller,
        title = tr("Controls"),
        status = if (controllerType == EmulatedControllerType.VPAD && secondScreenEnabled) tr("Wii U GamePad on the second screen")
        else controllerTypeToString(controllerType),
        isDone = controllerType != EmulatedControllerType.DISABLED,
        onClick = { onGoTo(WizardPage.CONTROLS) },
    )
    WizardSummaryRow(
        icon = R.drawable.ic_auto_awesome,
        title = tr("Frame generation"),
        status = when {
            !hasDll -> tr("Not set up")
            frameGenEnabled -> tr("Ready and turned on")
            else -> tr("Ready, turn it on from the toolbar")
        },
        isDone = hasDll,
        onClick = { onGoTo(WizardPage.FRAME_GEN) },
    )
    WizardSummaryRow(
        icon = R.drawable.ic_dashboard,
        title = tr("Toolbar"),
        status = if (toolbarEnabled) tr("In the black bars") else tr("Off"),
        isDone = toolbarEnabled,
        onClick = { onGoTo(WizardPage.TOOLBAR) },
    )
}

@Composable
private fun WizardSummaryRow(@DrawableRes icon: Int, title: String, status: String, isDone: Boolean, onClick: () -> Unit) {
    WiiUCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(icon, tint = if (isDone) WiiUColors.Success else WiiUColors.TextSecondary)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(text = title, color = WiiUColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                WiiUBodyText(status)
            }
            if (isDone) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(WiiUColors.Success, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = WiiUColors.TextSecondary)
            }
        }
    }
}
