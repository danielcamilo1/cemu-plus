package info.cemu.cemu.settings.input.controller

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.cemu.cemu.common.android.inputdevice.hasMotion
import info.cemu.cemu.common.android.inputdevice.hasRumble
import info.cemu.cemu.common.android.inputdevice.listGameControllers
import info.cemu.cemu.common.android.inputdevice.toControllerInfo
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.DEFAULT_MOTION_SENSITIVITY
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.settings.HotkeyCombo
import info.cemu.cemu.nativeinterface.NativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ButtonInfo(
    val name: String,
    val id: Int,
)

data class InputController(
    val id: Int,
    val name: String,
    val descriptor: String,
    val hasMotion: Boolean,
    val hasRumble: Boolean,
)

data class MotionInversion(
    val isXInverted: Boolean = false,
    val isYInverted: Boolean = false,
)

// in percent, 100 keeps the turning speed of the sensors
data class MotionSensitivity(
    val x: Int = DEFAULT_MOTION_SENSITIVITY,
    val y: Int = DEFAULT_MOTION_SENSITIVITY,
)

data class ActiveController(
    val controller: InputController,
    val settings: NativeInput.ControllerSettings
)

class ControllersViewModel(
    val controllerIndex: Int,
    // the profile of a game being edited instead of the global settings of the controller
    val editedProfile: String? = null,
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore,
) : ViewModel() {
    // first, so everything below reads the controller from the profile
    val isEditingProfile = editedProfile != null && NativeInput.beginControllerProfileEdit(controllerIndex, editedProfile)

    // the device sensors drive the GamePad motion, same setting as Enable motion in the quick menu
    val isDeviceMotionEnabled = dataStore.data.map { it.emulationSettings.isMotionEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setDeviceMotionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy(emulationSettings = it.emulationSettings.copy(isMotionEnabled = enabled))
            }
        }
    }

    // turning left and right or tilting up and down goes the other way, for every controller
    val motionInversion = dataStore.data.map {
        MotionInversion(it.emulationSettings.isMotionXInverted, it.emulationSettings.isMotionYInverted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionInversion())

    fun setMotionXInverted(inverted: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy(emulationSettings = it.emulationSettings.copy(isMotionXInverted = inverted))
            }
        }
    }

    fun setMotionYInverted(inverted: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy(emulationSettings = it.emulationSettings.copy(isMotionYInverted = inverted))
            }
        }
    }

    // how fast turning or tilting goes, for every controller like the inversion
    val motionSensitivity = dataStore.data.map {
        MotionSensitivity(it.emulationSettings.motionSensitivityX, it.emulationSettings.motionSensitivityY)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionSensitivity())

    fun setMotionSensitivityX(sensitivity: Int) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy(emulationSettings = it.emulationSettings.copy(motionSensitivityX = sensitivity))
            }
        }
    }

    fun setMotionSensitivityY(sensitivity: Int) {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy(emulationSettings = it.emulationSettings.copy(motionSensitivityY = sensitivity))
            }
        }
    }

    // the controller that has the touch screen, sensors and vibration of this device
    private val _deviceControllerIndex = MutableStateFlow(NativeInput.getDeviceControllerIndex())
    val deviceControllerIndex = _deviceControllerIndex.asStateFlow()

    private val _deviceRumble = MutableStateFlow(NativeInput.getDeviceRumble())
    val deviceRumble = _deviceRumble.asStateFlow()

    fun useDeviceControls() {
        NativeInput.setDeviceControllerIndex(controllerIndex)
        refreshDeviceControls()
    }

    fun setDeviceRumble(rumble: Float) {
        NativeInput.setDeviceRumble(rumble)
        _deviceRumble.value = rumble
    }

    private fun refreshDeviceControls() {
        _deviceControllerIndex.value = NativeInput.getDeviceControllerIndex()
        _deviceRumble.value = NativeInput.getDeviceRumble()
    }

    // set while a game runs that starts with a profile for this controller, changes then go to that profile
    val gameProfileName: String? = NativeInput.getGameControllerProfile(controllerIndex)

    private val _profiles = MutableStateFlow(NativeInput.getControllerProfiles().sorted())
    val profiles = _profiles.asStateFlow()

    fun isValidProfileName(name: String) =
        name.isNotBlank() && NativeInput.isValidControllerProfileName(name.trim())

    fun saveProfile(name: String): Boolean {
        val saved = NativeInput.saveControllerProfile(controllerIndex, name.trim())
        _profiles.value = NativeInput.getControllerProfiles().sorted()
        return saved
    }

    fun loadProfile(name: String): Boolean {
        if (!NativeInput.loadControllerProfile(controllerIndex, name)) {
            return false
        }
        _controllerType.value = NativeInput.getControllerType(controllerIndex)
        refreshControllerData()
        refreshDeviceControls()
        setActiveController(_activeController.value?.controller)
        return true
    }

    fun deleteProfile(name: String) {
        NativeInput.deleteControllerProfile(name)
        _profiles.value = NativeInput.getControllerProfiles().sorted()
    }

    private var _controllerType = MutableStateFlow(NativeInput.getControllerType(controllerIndex))
    val controllerType = _controllerType.asStateFlow()

    private val _controls = MutableStateFlow<Map<Int, String>>(emptyMap())
    val controls = _controls.asStateFlow()

    private val _controllers = MutableStateFlow<List<InputController>>(emptyList())
    val controllers = _controllers.asStateFlow()

    private val _buttonToBind = MutableStateFlow<ButtonInfo?>(null)
    val buttonToBind = _buttonToBind.asStateFlow()

    fun setButtonToBind(buttonInfo: ButtonInfo) {
        _buttonToBind.value = buttonInfo
    }

    fun clearButtonToBind() {
        _buttonToBind.value = null
    }

    private var controllersCount = NativeInput.ControllerCount(0, 0)

    private fun getControllerMapping(buttonId: Int) =
        buttonId to NativeInput.getControllerMapping(controllerIndex, buttonId)

    fun setControllerType(controllerType: Int) {
        if (!isControllerTypeAllowed(controllerType) || _controllerType.value == controllerType)
            return
        _controllerType.value = controllerType
        NativeInput.setControllerType(controllerIndex, controllerType)
        refreshControllerData()
        refreshDeviceControls()
        setActiveController(_activeController.value?.controller)
    }

    fun mapKeyEvent(keyEvent: KeyEvent, buttonId: Int) {
        InputMapper.mapKeyEventToMappingId(controllerIndex, buttonId, keyEvent)
        _controls.value += getControllerMapping(buttonId)
    }

    // keeps the picked controller if it is still connected
    fun refreshAvailableControllers(): Boolean {
        val gameControllers = listGameControllers()

        val inputControllers = gameControllers.map {
            InputController(
                id = it.id,
                name = it.name,
                descriptor = it.descriptor,
                hasMotion = it.hasMotion(),
                hasRumble = it.hasRumble(),
            )
        }
        val activeDescriptor = _activeController.value?.controller?.descriptor
        setActiveController(inputControllers.firstOrNull { it.descriptor == activeDescriptor } ?: inputControllers.firstOrNull())
        _controllers.value = inputControllers

        NativeInput.setControllers(gameControllers.map { it.toControllerInfo() }.toTypedArray())

        return gameControllers.isNotEmpty()
    }

    fun mapAllInputs(deviceId: Int) {
        val oldControls = _controls.value
        _controls.value = emptyMap()
        oldControls.keys.forEach { NativeInput.clearControllerMapping(controllerIndex, it) }

        InputMapper.mapAllInputs(deviceId, controllerIndex)

        val buttons = getNativeButtonsForControllerType(controllerType.value)
        buttons.forEach { _controls.value += getControllerMapping(it.nativeKeyCode) }

        // its motion, rumble and sticks are the ones shown next
        _controllers.value.firstOrNull { it.id == deviceId }?.let(::setActiveController)

        applyDefaultHotkeys()
    }

    // Select + Start exits the game and Select + X opens the quick menu, they can be changed in Hotkeys
    private fun applyDefaultHotkeys() {
        viewModelScope.launch {
            dataStore.updateData {
                it.copy(
                    hotkeySettings = it.hotkeySettings + mapOf(
                        HotkeyAction.QUIT to HotkeyCombo(setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)),
                        HotkeyAction.TOGGLE_MENU to HotkeyCombo(setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_X)),
                    )
                )
            }
        }
    }

    fun tryMapMotionEvent(motionEvent: MotionEvent, buttonId: Int): Boolean {
        if (InputMapper.tryMapMotionEventToMappingId(controllerIndex, buttonId, motionEvent)) {
            _controls.value += getControllerMapping(buttonId)
            return true
        }
        return false
    }

    fun clearButtonMapping(buttonId: Int) {
        _controls.value -= buttonId
        NativeInput.clearControllerMapping(controllerIndex, buttonId)
    }

    private fun refreshControllerData() {
        controllersCount = NativeInput.getControllersCount()
        _controls.value = NativeInput.getControllerMappings(controllerIndex)
    }

    fun isControllerTypeAllowed(controllerType: Int): Boolean {
        if (controllerType == NativeInput.EmulatedControllerType.DISABLED) {
            return true
        }

        val currentControllerType = this.controllerType.value

        if (controllerType == NativeInput.EmulatedControllerType.VPAD) {
            return currentControllerType == NativeInput.EmulatedControllerType.VPAD || controllersCount.vpadCount < NativeInput.MAX_VPAD_CONTROLLERS
        }

        val isWPAD = currentControllerType != NativeInput.EmulatedControllerType.VPAD
                && currentControllerType != NativeInput.EmulatedControllerType.DISABLED

        return isWPAD || controllersCount.wpadCount < NativeInput.MAX_WPAD_CONTROLLERS
    }

    private val _activeController = MutableStateFlow<ActiveController?>(null)
    val activeController = _activeController.asStateFlow()

    fun setActiveController(controller: InputController?) {
        if (controller == null) {
            _activeController.value = null
            return
        }

        val settings = NativeInput.getControllerSettings(
            controllerIndex,
            controller.descriptor,
            controller.name
        ) ?: NativeInput.ControllerSettings()
        _activeController.value = ActiveController(controller, settings)
    }

    fun setControllerSettings(
        controller: InputController,
        settings: NativeInput.ControllerSettings
    ) {
        NativeInput.setControllerSettings(
            controllerIndex,
            controller.descriptor,
            controller.name,
            settings
        )
        _activeController.value = _activeController.value?.copy(settings = settings)
    }

    fun save() {
        NativeInput.saveInputs()
    }

    init {
        refreshControllerData()
        refreshAvailableControllers()
    }

    override fun onCleared() {
        if (isEditingProfile) {
            NativeInput.endControllerProfileEdit(controllerIndex)
        }
    }

    companion object {
        val CONTROLLER_INDEX_KEY = object : CreationExtras.Key<Int> {}
        val EDITED_PROFILE_KEY = object : CreationExtras.Key<String?> {}
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ControllersViewModel(
                    this[CONTROLLER_INDEX_KEY] as Int,
                    this[EDITED_PROFILE_KEY],
                )
            }
        }
    }
}