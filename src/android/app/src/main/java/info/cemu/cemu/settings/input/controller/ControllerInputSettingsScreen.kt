package info.cemu.cemu.settings.input.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.android.context.getDeviceVibrator
import info.cemu.cemu.common.android.inputdevice.tryUseVibrator
import info.cemu.cemu.common.input.GamepadInputSource
import info.cemu.cemu.common.ui.components.Header
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.components.Toggle
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.controllerTypeToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBodyText
import info.cemu.cemu.common.ui.wiiu.WiiUButton
import info.cemu.cemu.common.ui.wiiu.WiiUCard
import info.cemu.cemu.common.ui.wiiu.WiiUChip
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUDialogPanel
import info.cemu.cemu.common.ui.wiiu.WiiURowIcon
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeInput.EmulatedControllerType
import info.cemu.cemu.settings.input.controller.emulatedcontroller.ClassicControllerInputs
import info.cemu.cemu.settings.input.controller.emulatedcontroller.ProControllerInputs
import info.cemu.cemu.settings.input.controller.emulatedcontroller.VPADInputs
import info.cemu.cemu.settings.input.controller.emulatedcontroller.WiimoteControllerInputs

private val ControllerTypeChoices = listOf(
    EmulatedControllerType.VPAD,
    EmulatedControllerType.PRO,
    EmulatedControllerType.CLASSIC,
    EmulatedControllerType.WIIMOTE,
    EmulatedControllerType.DISABLED,
)

// everything about one emulated controller on a single page, the most used settings first:
// what it emulates, the device that drives it, its inputs, motion, rumble, profiles and the stick sensitivity last
@Composable
fun ControllerInputSettingsScreen(
    navigateBack: () -> Unit,
    controllerIndex: Int,
    viewModel: ControllersViewModel = viewModel(
        factory = ControllersViewModel.Factory, extras = MutableCreationExtras().apply {
            set(ControllersViewModel.CONTROLLER_INDEX_KEY, controllerIndex)
        }),
) {
    DisposableEffect(Unit) {
        onDispose {
            viewModel.save()
        }
    }

    val controllers by viewModel.controllers.collectAsState()
    val buttonToBind by viewModel.buttonToBind.collectAsState()
    val controllerType by viewModel.controllerType.collectAsState()
    val controls by viewModel.controls.collectAsState()
    val activeController by viewModel.activeController.collectAsState()
    val isDeviceMotionEnabled by viewModel.isDeviceMotionEnabled.collectAsState()
    val motionInversion by viewModel.motionInversion.collectAsState()
    val deviceControllerIndex by viewModel.deviceControllerIndex.collectAsState()
    val deviceRumble by viewModel.deviceRumble.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    var showSaveProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showLoadProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun onInputClick(buttonName: String, buttonId: Int) {
        viewModel.setButtonToBind(ButtonInfo(buttonName, buttonId))
    }

    fun onInputLongClick(buttonId: Int) {
        viewModel.clearButtonMapping(buttonId)
    }

    ScreenContent(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        appBarText = tr("Controller {0}", controllerIndex + 1),
        navigateBack = navigateBack,
    ) {
        viewModel.gameProfileName?.let {
            NoticeCard(
                icon = R.drawable.ic_videogame,
                text = tr("The running game uses the profile {0} for this controller, changes are saved to that profile", it),
            )
        }

        Header(tr("Emulated controller"))
        WiiUCard(modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ControllerTypeChoices.forEach { type ->
                    WiiUChip(
                        label = if (type == EmulatedControllerType.DISABLED) tr("Off") else controllerTypeToString(type),
                        selected = controllerType == type,
                        enabled = viewModel.isControllerTypeAllowed(type),
                        onClick = { viewModel.setControllerType(type) },
                    )
                }
            }
        }

        if (controllerType == EmulatedControllerType.DISABLED) {
            WiiUBodyText(
                text = tr("Pick what this controller emulates to set it up"),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            ProfilesSection(
                profiles = profiles,
                onSave = { showSaveProfileDialog = true },
                onLoad = { showLoadProfileDialog = true },
                canSave = false,
            )
            return@ScreenContent
        }

        Header(tr("Input device"))
        InputDeviceCard(
            controllers = controllers,
            activeController = activeController?.controller,
            onSelect = viewModel::setActiveController,
            onRefresh = {
                if (!viewModel.refreshAvailableControllers()) {
                    snackbarHostState.showMessage(coroutineScope, tr("No controllers available"))
                }
            },
            onSetupAllInputs = { controller ->
                viewModel.mapAllInputs(controller.id)
                snackbarHostState.showMessage(coroutineScope, tr("All inputs are bound to {0}", controller.name))
            },
        )
        val hasDeviceControls = deviceControllerIndex == controllerIndex
        Toggle(
            label = tr("Touch screen, sensors and vibration of this device"),
            icon = R.drawable.ic_tablet,
            checked = hasDeviceControls,
            enabled = !hasDeviceControls,
            description = if (hasDeviceControls) tr("Used by this controller")
            else tr("Used by Controller {0}, turn on to use them with this controller", deviceControllerIndex + 1),
            onCheckedChanged = { if (it) viewModel.useDeviceControls() },
        )

        Header(tr("Buttons and sticks"))
        WiiUBodyText(
            text = tr("Tap an input to bind it, long press it to clear it"),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        when (controllerType) {
            EmulatedControllerType.VPAD -> VPADInputs(
                controllerIndex = controllerIndex,
                onInputClick = ::onInputClick,
                onInputLongClick = ::onInputLongClick,
                controlsMapping = controls,
            )

            EmulatedControllerType.PRO -> ProControllerInputs(
                onInputClick = ::onInputClick,
                onInputLongClick = ::onInputLongClick,
                controlsMapping = controls,
            )

            EmulatedControllerType.CLASSIC -> ClassicControllerInputs(
                onInputClick = ::onInputClick,
                onInputLongClick = ::onInputLongClick,
                controlsMapping = controls,
            )

            EmulatedControllerType.WIIMOTE -> WiimoteControllerInputs(
                onInputClick = ::onInputClick,
                onInputLongClick = ::onInputLongClick,
                controlsMapping = controls,
            )
        }

        Header(tr("Motion"))
        MotionSettings(
            controllerType = controllerType,
            activeController = activeController,
            isDeviceMotionEnabled = isDeviceMotionEnabled,
            onDeviceMotionEnabledChanged = viewModel::setDeviceMotionEnabled,
            onSetControllerSettings = viewModel::setControllerSettings,
        )
        Toggle(
            label = tr("Invert motion X axis"),
            description = tr("Turning left and right goes the other way, for every controller"),
            checked = motionInversion.isXInverted,
            onCheckedChanged = viewModel::setMotionXInverted,
        )
        Toggle(
            label = tr("Invert motion Y axis"),
            description = tr("Tilting up and down goes the other way, for every controller"),
            checked = motionInversion.isYInverted,
            onCheckedChanged = viewModel::setMotionYInverted,
        )

        Header(tr("Rumble"))
        RumbleSettings(
            activeController = activeController,
            hasDeviceControls = hasDeviceControls,
            deviceRumble = deviceRumble,
            onDeviceRumbleChanged = viewModel::setDeviceRumble,
            onSetControllerSettings = viewModel::setControllerSettings,
        )

        ProfilesSection(
            profiles = profiles,
            onSave = { showSaveProfileDialog = true },
            onLoad = { showLoadProfileDialog = true },
            canSave = true,
        )

        Header(tr("Advanced"))
        ExpandableCard(
            icon = R.drawable.ic_tune,
            title = tr("Stick and trigger sensitivity"),
            description = activeController?.controller?.name?.let { tr("Deadzone and range of {0}", it) }
                ?: tr("Connect a controller to change them"),
            expanded = showAdvanced && activeController != null,
            enabled = activeController != null,
            onClick = { showAdvanced = !showAdvanced },
        )
        AnimatedVisibility(visible = showAdvanced && activeController != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                activeController?.let { (controller, settings) ->
                    fun update(newSettings: NativeInput.ControllerSettings) =
                        viewModel.setControllerSettings(controller, newSettings)
                    AxisGroup(
                        label = tr("Left stick"),
                        values = settings.axis,
                        onValuesChanged = { update(settings.copy(axis = it)) },
                    )
                    AxisGroup(
                        label = tr("Right stick"),
                        values = settings.rotation,
                        onValuesChanged = { update(settings.copy(rotation = it)) },
                    )
                    AxisGroup(
                        label = tr("Triggers"),
                        values = settings.trigger,
                        onValuesChanged = { update(settings.copy(trigger = it)) },
                    )
                }
            }
        }
    }

    buttonToBind?.let {
        InputBindingPopup(
            buttonName = it.name,
            mapKeyEvent = { event ->
                viewModel.mapKeyEvent(event, it.id)
                viewModel.clearButtonToBind()
            },
            mapMotionEvent = { event ->
                if (viewModel.tryMapMotionEvent(event, it.id)) {
                    viewModel.clearButtonToBind()
                }
            },
            onClear = {
                viewModel.clearButtonMapping(it.id)
                viewModel.clearButtonToBind()
            },
            onDismiss = {
                viewModel.clearButtonToBind()
            },
        )
    }

    if (showSaveProfileDialog) {
        SaveProfileDialog(
            profiles = profiles,
            isValidName = viewModel::isValidProfileName,
            onDismissRequest = { showSaveProfileDialog = false },
            onSave = { name ->
                val message = if (viewModel.saveProfile(name)) tr("Saved the profile {0}", name.trim())
                else tr("The profile could not be saved")
                snackbarHostState.showMessage(coroutineScope, message)
            },
        )
    }

    if (showLoadProfileDialog) {
        LoadProfileDialog(
            profiles = profiles,
            onDismissRequest = { showLoadProfileDialog = false },
            onLoad = { name ->
                val message = if (viewModel.loadProfile(name)) tr("Loaded the profile {0}", name)
                else tr("The profile could not be loaded")
                snackbarHostState.showMessage(coroutineScope, message)
            },
            onDelete = viewModel::deleteProfile,
        )
    }
}

@Composable
private fun NoticeCard(@DrawableRes icon: Int, text: String) {
    WiiUCard(modifier = Modifier.fillMaxWidth(), selected = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(icon)
            WiiUBodyText(
                text = text,
                color = WiiUColors.Text,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

@Composable
private fun InputDeviceCard(
    controllers: List<InputController>,
    activeController: InputController?,
    onSelect: (InputController) -> Unit,
    onRefresh: () -> Unit,
    onSetupAllInputs: (InputController) -> Unit,
) {
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(R.drawable.ic_controller)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = tr("Controller in use"),
                    color = WiiUColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (controllers.isEmpty()) tr("No controllers connected, connect one and refresh")
                    else tr("Its motion, rumble and sensitivity are set below"),
                    color = WiiUColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = tr("Refresh"),
                    tint = WiiUColors.Accent,
                )
            }
        }
        if (controllers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controllers.forEach {
                    WiiUChip(
                        label = it.name,
                        selected = it.id == activeController?.id,
                        onClick = { onSelect(it) },
                    )
                }
            }
        }
        WiiUButton(
            text = tr("Set up all inputs"),
            icon = R.drawable.ic_auto_awesome,
            primary = true,
            enabled = activeController != null,
            onClick = { activeController?.let(onSetupAllInputs) },
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth(),
        )
        WiiUBodyText(
            text = tr("Binds every input to the controller in use at once, single inputs can still be changed below"),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MotionSettings(
    controllerType: Int,
    activeController: ActiveController?,
    isDeviceMotionEnabled: Boolean,
    onDeviceMotionEnabledChanged: (Boolean) -> Unit,
    onSetControllerSettings: (InputController, NativeInput.ControllerSettings) -> Unit,
) {
    val context = LocalContext.current
    val deviceHasMotionSensors = remember(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }
    val isVPAD = controllerType == EmulatedControllerType.VPAD
    val controller = activeController?.controller

    if (isVPAD && controller != null && controller.hasMotion) {
        Toggle(
            label = tr("Use motion"),
            icon = R.drawable.ic_screen_rotation,
            checked = activeController.settings.motion,
            onCheckedChanged = { onSetControllerSettings(controller, activeController.settings.copy(motion = it)) },
            description = tr("Uses the motion sensors of {0}", controller.name),
        )
    } else {
        // built in controls like on handhelds have no sensors of their own, the device sensors are used instead
        val canUseDeviceMotion = isVPAD && deviceHasMotionSensors
        Toggle(
            label = tr("Use motion"),
            icon = R.drawable.ic_screen_rotation,
            checked = isDeviceMotionEnabled && canUseDeviceMotion,
            onCheckedChanged = onDeviceMotionEnabledChanged,
            enabled = canUseDeviceMotion,
            description = when {
                !isVPAD -> tr("Requires Wii U GamePad")
                !deviceHasMotionSensors -> tr("This device has no motion sensors")
                else -> tr("Uses the motion sensors of this device, same as Enable motion in the quick menu")
            },
        )
    }
}

@Composable
private fun RumbleSettings(
    activeController: ActiveController?,
    hasDeviceControls: Boolean,
    deviceRumble: Float,
    onDeviceRumbleChanged: (Float) -> Unit,
    onSetControllerSettings: (InputController, NativeInput.ControllerSettings) -> Unit,
) {
    if (activeController != null) {
        val (controller, settings) = activeController
        Slider(
            label = tr("Rumble of {0}", controller.name),
            description = if (controller.hasRumble) null else tr("This controller can not rumble"),
            enabled = controller.hasRumble,
            value = (settings.rumble * 100f).fastRoundToInt(),
            valueFrom = 0,
            steps = 19,
            valueTo = 100,
            labelFormatter = { "$it%" },
            onValueChange = {
                val rumble = it / 100f
                onSetControllerSettings(controller, settings.copy(rumble = rumble))

                val amplitude = (255 * rumble).toInt()
                InputDevice.getDevice(controller.id)?.tryUseVibrator {
                    cancel()
                    if (amplitude in 1..255) {
                        vibrate(VibrationEffect.createOneShot(500L, amplitude))
                    }
                }
            },
        )
    }
    if (hasDeviceControls) {
        val context = LocalContext.current
        val vibrator = remember(context) { context.getDeviceVibrator() }
        Slider(
            label = tr("Vibration of this device"),
            value = (deviceRumble * 100f).fastRoundToInt(),
            valueFrom = 0,
            steps = 19,
            valueTo = 100,
            labelFormatter = { "$it%" },
            onValueChange = {
                val rumble = it / 100f
                onDeviceRumbleChanged(rumble)

                val amplitude = (255 * rumble).toInt()
                vibrator.cancel()
                if (amplitude in 1..255) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500L, amplitude))
                }
            },
        )
    }
    if (activeController == null && !hasDeviceControls) {
        WiiUBodyText(
            text = tr("Connect a controller to set its rumble"),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ProfilesSection(
    profiles: List<String>,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    canSave: Boolean,
) {
    Header(tr("Profiles"))
    WiiUCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(R.drawable.ic_save)
            WiiUBodyText(
                text = tr("Save this controller under a name to load it again later, or to use it for a single game in its game settings"),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            )
        }
        FlowRow(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WiiUButton(text = tr("Save as profile"), icon = R.drawable.ic_save, enabled = canSave, onClick = onSave)
            WiiUButton(
                text = if (profiles.isEmpty()) tr("No profiles yet") else tr("Load a profile ({0})", profiles.size),
                icon = R.drawable.ic_folder,
                enabled = profiles.isNotEmpty(),
                onClick = onLoad,
            )
        }
    }
}

@Composable
private fun ExpandableCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    WiiUCard(modifier = Modifier.fillMaxWidth(), onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WiiURowIcon(icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(text = title, color = WiiUColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    color = WiiUColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = WiiUColors.TextSecondary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation),
            )
        }
    }
}

@Composable
private fun AxisGroup(
    label: String,
    values: NativeInput.AxisSetting,
    onValuesChanged: (NativeInput.AxisSetting) -> Unit
) {
    Text(
        text = label,
        color = WiiUColors.AccentDark,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    Slider(
        label = tr("Deadzone"),
        description = tr("How far it has to move before the game sees it"),
        value = (values.deadzone * 100f).fastRoundToInt(),
        valueFrom = 0,
        valueTo = 100,
        labelFormatter = { "$it%" },
        onValueChange = { onValuesChanged(values.copy(deadzone = it / 100f)) },
    )
    Slider(
        label = tr("Range"),
        description = tr("How far it has to move to reach the end"),
        value = (values.range * 100f).fastRoundToInt(),
        valueFrom = 50,
        valueTo = 200,
        labelFormatter = { "$it%" },
        onValueChange = { onValuesChanged(values.copy(range = it / 100f)) },
    )
}

@Composable
private fun SaveProfileDialog(
    profiles: List<String>,
    isValidName: (String) -> Boolean,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val isValid = isValidName(name)
    val replacesProfile = profiles.contains(name.trim())

    fun save() {
        if (!isValid) return
        onSave(name)
        onDismissRequest()
    }

    Dialog(onDismissRequest = onDismissRequest) {
        WiiUDialogPanel(title = tr("Save as profile")) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(tr("Profile name")) },
                    isError = name.isNotEmpty() && !isValid,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                WiiUBodyText(
                    text = when {
                        name.isNotEmpty() && !isValid -> tr("This name can not be used")
                        replacesProfile -> tr("Replaces the profile with this name")
                        else -> tr("Games can use it in their game settings")
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (profiles.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        profiles.forEach {
                            WiiUChip(label = it, selected = it == name.trim(), onClick = { name = it })
                        }
                    }
                }
            }
            DialogButtons {
                WiiUButton(text = tr("Cancel"), onClick = onDismissRequest)
                WiiUButton(text = tr("Save"), primary = true, enabled = isValid, onClick = ::save)
            }
        }
    }
}

@Composable
private fun LoadProfileDialog(
    profiles: List<String>,
    onDismissRequest: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var profileToDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismissRequest) {
        WiiUDialogPanel(title = tr("Load a profile")) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WiiUBodyText(
                    text = tr("Replaces the settings of this controller with the profile"),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                profiles.forEach { profile ->
                    WiiUCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onLoad(profile)
                            onDismissRequest()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile,
                                color = WiiUColors.Text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { profileToDelete = profile }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = tr("Delete"),
                                    tint = WiiUColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
            DialogButtons {
                WiiUButton(text = tr("Cancel"), onClick = onDismissRequest)
            }
        }
    }

    profileToDelete?.let { profile ->
        Dialog(onDismissRequest = { profileToDelete = null }) {
            WiiUDialogPanel(title = tr("Delete the profile {0}?", profile)) {
                WiiUBodyText(
                    text = tr("Games that use it go back to the global settings of the controller"),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                DialogButtons {
                    WiiUButton(text = tr("Cancel"), onClick = { profileToDelete = null })
                    WiiUButton(
                        text = tr("Delete"),
                        primary = true,
                        onClick = {
                            onDelete(profile)
                            profileToDelete = null
                            if (profiles.size <= 1) onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        content()
    }
}

@Composable
private fun InputBindingPopup(
    buttonName: String,
    mapKeyEvent: (KeyEvent) -> Unit,
    mapMotionEvent: (MotionEvent) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        GamepadInputSource.events.collect { event ->
            when (event) {
                is GamepadInputSource.InputEvent.Key -> mapKeyEvent(event.keyEvent)
                is GamepadInputSource.InputEvent.Motion -> mapMotionEvent(event.motionEvent)
            }
        }
    }

    Popup(alignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center,
        ) {
            WiiUDialogPanel(title = tr("Input binding"), modifier = Modifier.padding(16.dp)) {
                WiiUBodyText(
                    text = tr("Trigger an input to bind it to {0}", buttonName),
                    color = WiiUColors.Text,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                DialogButtons {
                    WiiUButton(text = tr("Clear"), onClick = onClear)
                    WiiUButton(text = tr("Cancel"), onClick = onDismiss)
                }
            }
        }
    }
}
