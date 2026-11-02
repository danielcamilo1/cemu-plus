package info.cemu.cemu.settings.screenlayout

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.EmulationSettings
import info.cemu.cemu.common.settings.GamePadPosition
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScreenLayoutSettingsViewModel(
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore,
) : ViewModel() {
    val emulationSettings = dataStore.data.map { it.emulationSettings }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            EmulationSettings(),
        )

    private fun updateEmulationSettings(transform: (EmulationSettings) -> EmulationSettings) {
        viewModelScope.launch {
            dataStore.updateData { it.copy(emulationSettings = transform(it.emulationSettings)) }
        }
    }

    fun setSecondScreenEnabled(enabled: Boolean) = updateEmulationSettings {
        it.copy(isPadOnExternalDisplay = enabled, isSecondScreenDefaultApplied = true)
    }

    fun setExternalScreenRotatedLeft(rotatedLeft: Boolean) = updateEmulationSettings {
        it.copy(isExternalScreenRotatedLeft = rotatedLeft)
    }

    fun setPadVisible(visible: Boolean) = updateEmulationSettings {
        it.copy(isPadVisible = visible)
    }

    fun setGamePadPosition(position: GamePadPosition) = updateEmulationSettings {
        it.copy(gamePadPosition = position)
    }
}
