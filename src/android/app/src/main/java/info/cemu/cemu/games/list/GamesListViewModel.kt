package info.cemu.cemu.games.list

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.GameListLayout
import info.cemu.cemu.common.settings.MAX_GAME_GRID_SIZE
import info.cemu.cemu.common.settings.MIN_GAME_GRID_SIZE
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeGameTitles.Game
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GamesListViewModel(
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore,
) : ViewModel() {
    // null until the setting is read, so the list doesn't flash before the grid
    val layout: StateFlow<GameListLayout?> = dataStore.data.map { it.guiSettings.gameListLayout }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null,
        )

    fun setLayout(layout: GameListLayout) {
        viewModelScope.launch {
            dataStore.updateData { it.copy(guiSettings = it.guiSettings.copy(gameListLayout = layout)) }
        }
    }

    val gridSize: StateFlow<Int?> = dataStore.data.map { it.guiSettings.gameGridSize }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null,
        )

    // zooming in shows fewer, bigger icons and zooming out more, smaller ones
    fun zoomGrid(zoomIn: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                val size = (it.guiSettings.gameGridSize + if (zoomIn) -1 else 1)
                    .coerceIn(MIN_GAME_GRID_SIZE, MAX_GAME_GRID_SIZE)
                it.copy(guiSettings = it.guiSettings.copy(gameGridSize = size))
            }
        }
    }

    private var gamePaths = NativeSettings.getGamesPaths().toSet()

    private val _filterText = MutableStateFlow("")
    val filterText = _filterText.asStateFlow()
    fun setFilterText(filterText: String) {
        _filterText.value = filterText
    }

    private val _games = MutableStateFlow<Set<Game>>(emptySet())
    val games: StateFlow<List<Game>> = combine(_filterText, _games) { filter, games ->
        if (filter.isBlank()) {
            games
        } else {
            games.filter { it.name?.contains(filter, true) ?: false }
        }
    }.map {
        it.sorted()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    init {
        NativeGameTitles.setGameTitleLoadedCallback(NativeGameTitles.GameTitleLoadedCallback { game: Game ->
            if (_games.value.any { it.titleId == game.titleId || it.path == game.path })
                return@GameTitleLoadedCallback

            _games.value += game
        })
        refreshGames()
    }

    fun removeShadersForGame(game: Game) {
        NativeGameTitles.removeShaderCacheFilesForTitle(game.titleId)
    }

    fun setGameTitleFavorite(game: Game, isFavorite: Boolean) {
        if (!_games.value.contains(game)) {
            return
        }
        NativeGameTitles.setGameTitleFavorite(game.titleId, isFavorite)
        _games.value = _games.value.toMutableSet().apply {
            remove(game)
            add(game.copy(isFavorite = isFavorite))
        }
    }

    override fun onCleared() {
        NativeGameTitles.setGameTitleLoadedCallback(null)
    }

    fun gamePathsHaveChanged(): Boolean {
        val newGamePaths = NativeSettings.getGamesPaths().toSet()
        if (newGamePaths != gamePaths) {
            gamePaths = newGamePaths
            return true
        }
        return false
    }

    fun refreshGames() {
        _games.value = emptySet()
        NativeGameTitles.reloadGameTitles()
    }
}
