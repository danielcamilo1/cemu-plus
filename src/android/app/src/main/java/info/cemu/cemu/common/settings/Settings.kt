package info.cemu.cemu.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import info.cemu.cemu.common.ui.localization.DEFAULT_LANGUAGE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class EmulationSettings(
    val gamePadPosition: GamePadPosition = GamePadPosition.RIGHT,
    val isPadVisible: Boolean = false,
    val isPadOnExternalDisplay: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
    // the TV image on the second screen and the GamePad on this one, for second screens that are detected the other
    // way around, like the Dual Screen add-on of the Retroid Pocket Mini
    val areScreensSwapped: Boolean = false,
    // the second screen gets turned on once the first time one is detected, after that it is up to the user
    val isSecondScreenDefaultApplied: Boolean = false,
    val isMotionEnabled: Boolean = false,
    // for every motion source, X turns the GamePad left and right and Y tilts it up and down
    val isMotionXInverted: Boolean = false,
    val isMotionYInverted: Boolean = false,
    // how much the turning speed is scaled, in percent, for every motion source like the inversion above
    val motionSensitivityX: Int = DEFAULT_MOTION_SENSITIVITY,
    val motionSensitivityY: Int = DEFAULT_MOTION_SENSITIVITY,
)

const val DEFAULT_MOTION_SENSITIVITY = 100
const val MIN_MOTION_SENSITIVITY = 25
const val MAX_MOTION_SENSITIVITY = 300
const val MOTION_SENSITIVITY_STEP = 5

enum class GameListLayout {
    GRID,
    LIST,
}

@Serializable
data class GuiSettings(
    val language: String = DEFAULT_LANGUAGE,
    val gameListLayout: GameListLayout = GameListLayout.GRID,
    // how many icons fit across the short side of the screen in the grid, the long side is filled to match
    val gameGridSize: Int = DEFAULT_GAME_GRID_SIZE,
    // set once the setup wizard is finished or closed, it opens on launch until then
    val isSetupWizardCompleted: Boolean = false,
)

const val DEFAULT_GAME_GRID_SIZE = 3
const val MIN_GAME_GRID_SIZE = 2
const val MAX_GAME_GRID_SIZE = 6

@Serializable
data class StorageSettings(
    val dataRootPath: String? = null,
    val customRootUri: String? = null,
    val mirrorRootPath: String? = null,
    val pendingDeleteDataRootPath: String? = null,
    val isSaveMirrorDirty: Boolean = false,
    val lastSaveSyncAtMillis: Long? = null,
    val lastManualSyncAtMillis: Long? = null,
    @Deprecated("Kept only to decode settings written by older data-storage prototypes.")
    val isMirrorDirty: Boolean = false,
    val lastStorageError: String? = null,
)

@Serializable
data class InputOverlayRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class InputOverlaySettings(
    val isVibrateOnTouchEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val controllerIndex: Int = 0,
    val alpha: Int = 64,
    val inputVisibilityMap: Map<OverlayInputConfig, Boolean> = emptyMap(),
    val inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect> = emptyMap(),
)

@Serializable
data class AppSettings(
    val guiSettings: GuiSettings = GuiSettings(),
    val emulationSettings: EmulationSettings = EmulationSettings(),
    val storageSettings: StorageSettings = StorageSettings(),
    val inputOverlaySettings: InputOverlaySettings = InputOverlaySettings(),
    val hotkeySettings: Map<HotkeyAction, HotkeyCombo> = emptyMap(),
)

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        Json.decodeFromString<AppSettings>(input.readBytes().decodeToString())
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(Json.encodeToString(t).encodeToByteArray())
    }
}

object AppSettingsStore {
    private lateinit var _dataStore: DataStore<AppSettings>
    val dataStore: DataStore<AppSettings>
        get() = _dataStore

    fun init(context: Context) {
        _dataStore = MultiProcessDataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = null,
            produceFile = { context.dataStoreFile("appSettings.json") },
        )
    }
}
