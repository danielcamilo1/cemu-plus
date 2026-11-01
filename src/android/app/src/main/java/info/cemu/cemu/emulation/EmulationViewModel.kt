package info.cemu.cemu.emulation

import android.view.SurfaceHolder
import androidx.compose.ui.geometry.Rect
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.cemu.cemu.common.either.Either
import info.cemu.cemu.common.either.Error
import info.cemu.cemu.common.either.Success
import info.cemu.cemu.common.either.attemptWithContext
import info.cemu.cemu.common.either.bind
import info.cemu.cemu.common.either.mapError
import info.cemu.cemu.common.framegen.FrameGenMultiplierChoices
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.InputOverlayRect
import info.cemu.cemu.common.settings.InputOverlaySettings
import info.cemu.cemu.common.settings.OverlayInputConfig
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeEmulation.OverlayAction
import info.cemu.cemu.nativeinterface.NativeEmulation.PrepareTitleResult
import info.cemu.cemu.nativeinterface.NativeException
import info.cemu.cemu.nativeinterface.NativeFrameGen
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SideMenuState(
    val isMotionEnabled: Boolean = false,
    val isTVReplacedWithPad: Boolean = false,
    val isPadVisible: Boolean = false,
    val isPadOnExternalDisplay: Boolean = false,
    val areScreensSwapped: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
    val isInputOverlayVisible: Boolean = false,
)

class ConditionFlags(
    var isMainConditionMet: Boolean = false, var isPadConditionMet: Boolean = false
) {
    fun get(isMain: Boolean): Boolean {
        return if (isMain) isMainConditionMet else isPadConditionMet
    }

    fun set(isMain: Boolean, value: Boolean) {
        if (isMain) {
            isMainConditionMet = value
        } else {
            isPadConditionMet = value
        }
    }
}

sealed interface NativeError {
    data class SurfaceCreationError(val message: String) : NativeError
    data class RendererInitializationError(val message: String) : NativeError

    object GameFilesNotFoundError : NativeError
    object NoDiscKeysError : NativeError
    object NoTitleTikError : NativeError
    data class UnknownTilePrepareError(val launchPath: String) : NativeError
    data class SystemInitializationError(val message: String) : NativeError

    object LaunchingTitleError : NativeError
}

data class SurfaceDimensions(val width: Int = 1, val height: Int = 1)

class EmulationViewModel(
    private val launchPath: String,
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore
) : ViewModel() {
    private val _emulationError = MutableStateFlow<NativeError?>(null)
    val emulationError = _emulationError.asStateFlow()

    private val _sideMenuState = MutableStateFlow(SideMenuState())
    val sideMenuState = _sideMenuState.asStateFlow()

    private val _mainSurfaceDimensions = MutableStateFlow(SurfaceDimensions())
    val mainSurfaceDimensions = _mainSurfaceDimensions.asStateFlow()

    private val _padSurfaceDimensions = MutableStateFlow(SurfaceDimensions())
    val padSurfaceDimensions = _padSurfaceDimensions.asStateFlow()

    val isInputOverlayVisible =
        sideMenuState.map { it.isInputOverlayVisible }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                false,
            )

    init {
        viewModelScope.launch {
            val settings = dataStore.data.first()
            _sideMenuState.update {
                it.copy(
                    isMotionEnabled = settings.emulationSettings.isMotionEnabled,
                    isInputOverlayVisible = settings.inputOverlaySettings.isOverlayEnabled,
                )
            }
            // the screen and motion settings can also be changed from the settings opened in game
            dataStore.data.map { it.emulationSettings }
                .distinctUntilChanged()
                .collect { emulationSettings ->
                    _sideMenuState.update {
                        it.copy(
                            isPadVisible = emulationSettings.isPadVisible,
                            isPadOnExternalDisplay = emulationSettings.isPadOnExternalDisplay,
                            isExternalScreenRotatedLeft = emulationSettings.isExternalScreenRotatedLeft,
                            isMotionEnabled = emulationSettings.isMotionEnabled,
                        )
                    }
                }
        }
    }

    val inputOverlaySettings = dataStore.data.map { it.inputOverlaySettings }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        InputOverlaySettings(),
    )

    fun saveInputOverlayRectangles(inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect>) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings =
                    it.inputOverlaySettings.copy(inputOverlayRectMap = inputOverlayRectMap)

                it.copy(inputOverlaySettings = overlaySettings)
            }
        }
    }

    fun resetInputOverlayLayout() {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings =
                    it.inputOverlaySettings.copy(inputOverlayRectMap = emptyMap())

                it.copy(inputOverlaySettings = overlaySettings)
            }
        }
    }

    fun updateSideMenuState(sideMenuState: SideMenuState) {
        val oldState = _sideMenuState.value
        _sideMenuState.value = sideMenuState

        if (oldState.isPadVisible != sideMenuState.isPadVisible ||
            oldState.isPadOnExternalDisplay != sideMenuState.isPadOnExternalDisplay ||
            oldState.isExternalScreenRotatedLeft != sideMenuState.isExternalScreenRotatedLeft ||
            oldState.isMotionEnabled != sideMenuState.isMotionEnabled
        ) {
            viewModelScope.launch {
                dataStore.updateData {
                    it.copy(
                        emulationSettings = it.emulationSettings.copy(
                            isPadVisible = sideMenuState.isPadVisible,
                            isPadOnExternalDisplay = sideMenuState.isPadOnExternalDisplay,
                            isExternalScreenRotatedLeft = sideMenuState.isExternalScreenRotatedLeft,
                            isMotionEnabled = sideMenuState.isMotionEnabled,
                        )
                    )
                }
            }
        }
    }

    val gamePadPosition = dataStore.data.map { it.emulationSettings.gamePadPosition }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null,
        )

    // the panel button that opens the quick menu
    private val _menuRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val menuRequests = _menuRequests.asSharedFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen = _isSettingsOpen.asStateFlow()

    private var pauseJob: Job? = null
    private var pausedForSettings = false

    fun setPaused(paused: Boolean) {
        if (_isPaused.value == paused) {
            return
        }
        _isPaused.value = paused
        NativeEmulation.setOverlayPaused(paused)
        pauseJob?.cancel()
        if (paused) {
            // the overlay is only drawn while the game runs, give it a few frames to show the resume button first
            pauseJob = viewModelScope.launch {
                delay(PAUSE_DELAY_MS)
                NativeEmulation.pauseTitle()
            }
        } else {
            NativeEmulation.resumeTitle()
        }
    }

    fun openSettings() {
        if (_isSettingsOpen.value) {
            return
        }
        pausedForSettings = !_isPaused.value
        // set before pausing so the panel gets drawn with the close button
        NativeEmulation.setOverlaySettingsOpen(true)
        setPaused(true)
        _isSettingsOpen.value = true
    }

    fun closeSettings() {
        if (!_isSettingsOpen.value) {
            return
        }
        _isSettingsOpen.value = false
        NativeEmulation.setOverlaySettingsOpen(false)
        NativeSettings.saveSettings()
        if (pausedForSettings) {
            setPaused(false)
        }
        pausedForSettings = false
    }

    private data class SurfaceLayout(val isTV: Boolean, val bounds: Rect, val dimensions: SurfaceDimensions)

    // where the surfaces inside the activity are drawn and the size of what they show, keyed by isMainCanvas
    private val surfaceLayouts = HashMap<Boolean, SurfaceLayout>()

    fun setSurfaceLayout(isMainCanvas: Boolean, isTV: Boolean, bounds: Rect, dimensions: SurfaceDimensions) {
        surfaceLayouts[isMainCanvas] = SurfaceLayout(isTV, bounds, dimensions)
    }

    fun removeSurfaceLayout(isMainCanvas: Boolean) {
        surfaceLayouts.remove(isMainCanvas)
    }

    // bounds of the panel overlay in root coordinates, null if no surface inside the activity draws it
    fun getPanelBounds(): Rect? {
        for ((isTV, bounds, dimensions) in surfaceLayouts.values) {
            val rect = NativeEmulation.getOverlayPanelRect(isTV) ?: continue
            val scaleX = bounds.width / dimensions.width
            val scaleY = bounds.height / dimensions.height
            return Rect(
                left = bounds.left + rect[0] * scaleX,
                top = bounds.top + rect[1] * scaleY,
                right = bounds.left + rect[2] * scaleX,
                bottom = bounds.top + rect[3] * scaleY,
            )
        }
        return null
    }

    fun onOverlayAction(action: Int) {
        when (action) {
            OverlayAction.FRAME_GEN -> NativeFrameGen.setEnabled(!NativeFrameGen.isEnabled())
            OverlayAction.FRAME_GEN_MODE -> cycleFrameGenMultiplier()
            OverlayAction.SETTINGS -> if (_isSettingsOpen.value) closeSettings() else openSettings()
            OverlayAction.PAUSE -> setPaused(!_isPaused.value)
            OverlayAction.MENU -> _menuRequests.tryEmit(Unit)
            OverlayAction.VOLUME -> viewModelScope.launch(Dispatchers.IO) { NativeSettings.saveSettings() }
        }
    }

    // x2 -> x3 -> x4 -> x2. A target frame rate switches back to a fixed multiplier
    private fun cycleFrameGenMultiplier() {
        if (NativeFrameGen.getTargetRate() > 0) {
            NativeFrameGen.setTargetRate(0)
            NativeFrameGen.setMultiplier(FrameGenMultiplierChoices.first())
            return
        }
        val index = FrameGenMultiplierChoices.indexOf(NativeFrameGen.getMultiplier())
        NativeFrameGen.setMultiplier(FrameGenMultiplierChoices[(index + 1) % FrameGenMultiplierChoices.size])
    }

    val destroyedSurfaces = ConditionFlags()
    var setSurfaces = ConditionFlags()

    private fun updateSurfaceDimensions(isMainCanvas: Boolean, width: Int, height: Int) {
        val newDimensions = SurfaceDimensions(
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
        )
        if (isMainCanvas) {
            _mainSurfaceDimensions.value = newDimensions
        } else {
            _padSurfaceDimensions.value = newDimensions
        }
    }

    private inner class CanvasSurfaceHolderCallback(val isMainCanvas: Boolean) :
        SurfaceHolder.Callback {

        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {}

        override fun surfaceChanged(
            surfaceHolder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            try {
                NativeEmulation.setSurfaceSize(width, height, isMainCanvas)
                updateSurfaceDimensions(isMainCanvas, width, height)

                if (setSurfaces.get(isMainCanvas)) {
                    return
                }

                NativeEmulation.setSurface(surfaceHolder.surface, isMainCanvas)
                val mainSurfaceWasDestroyed = destroyedSurfaces.get(isMain = true)

                // a game paused by the user stays paused when the app comes back
                if (mainSurfaceWasDestroyed && isMainCanvas && !_isPaused.value) {
                    NativeEmulation.resumeTitle()
                }

                setSurfaces.set(isMainCanvas, true)

                val padSurfaceWasSet = setSurfaces.get(isMain = false)
                if ((!isMainCanvas && !mainSurfaceWasDestroyed) || (isMainCanvas && padSurfaceWasSet)) {
                    NativeEmulation.initializeSurface(isMainCanvas = false)
                }

                destroyedSurfaces.set(isMainCanvas, false)
            } catch (exception: NativeException) {
                _emulationError.value = NativeError.SurfaceCreationError(exception.message!!)
            }
        }

        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            if (setSurfaces.get(isMain = false)) {
                NativeEmulation.clearPadSurface()
                setSurfaces.set(isMain = false, false)
                destroyedSurfaces.set(isMain = false, true)
            }

            if (isMainCanvas) {
                NativeEmulation.pauseTitle()

                setSurfaces.set(isMain = true, false)
                destroyedSurfaces.set(isMain = true, true)
            }
        }
    }

    val mainHolderCallback: SurfaceHolder.Callback = CanvasSurfaceHolderCallback(true)
    val padHolderCallback: SurfaceHolder.Callback = CanvasSurfaceHolderCallback(false)

    private suspend fun initializeSystems() = attemptWithContext(Dispatchers.IO) {
        NativeEmulation.initializeSystems()
    }.mapError { NativeError.SystemInitializationError(it) }

    private suspend fun initializeRenderer() = attemptWithContext(Dispatchers.IO) {
        NativeEmulation.initializeRenderer()
        NativeEmulation.initializeSurface(isMainCanvas = true)
    }.mapError { NativeError.RendererInitializationError(it) }

    private suspend fun prepareTitle(): Either<Unit, NativeError> =
        attemptWithContext(Dispatchers.IO) { NativeEmulation.prepareTitle(launchPath) }
            .fold(
                onSuccess = { result ->
                    when (result) {
                        PrepareTitleResult.SUCCESSFUL -> Success(Unit)
                        PrepareTitleResult.ERROR_GAME_BASE_FILES_NOT_FOUND -> Error(NativeError.GameFilesNotFoundError)
                        PrepareTitleResult.ERROR_NO_DISC_KEY -> Error(NativeError.NoDiscKeysError)
                        PrepareTitleResult.ERROR_NO_TITLE_TIK -> Error(NativeError.NoTitleTikError)
                        else -> Error(NativeError.UnknownTilePrepareError(launchPath))
                    }
                },
                onError = { Error(NativeError.UnknownTilePrepareError(launchPath)) }
            )

    private suspend fun launchTitle() =
        attemptWithContext(Dispatchers.IO) { NativeEmulation.launchTitle() }
            .mapError { NativeError.LaunchingTitleError }

    private val _isEmulationInitialized = MutableStateFlow(false)
    val isEmulationInitialized = _isEmulationInitialized.asStateFlow()
    private var emulationInitializationJob: Job? = null
    fun initializeEmulation() {
        if (_isEmulationInitialized.value || emulationInitializationJob != null) {
            return
        }

        emulationInitializationJob = viewModelScope.launch {
            prepareTitle()
                .bind { initializeSystems() }
                .bind { initializeRenderer() }
                .bind { launchTitle() }
                .onError { _emulationError.value = it }

            _isEmulationInitialized.value = true
        }
    }

    companion object {
        private const val PAUSE_DELAY_MS = 150L
        val LAUNCH_PATH_KEY = object : CreationExtras.Key<String> {}
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EmulationViewModel(
                    this[LAUNCH_PATH_KEY] as String
                )
            }
        }
    }
}
