package info.cemu.cemu.emulation

import android.content.Context
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.storage.CemuDataStorage
import info.cemu.cemu.common.storage.CemuSaveSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

object EmulationSessionState {
    private val activeSessions = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var savesReadyJob: Job? = null

    val isEmulationRunning: Boolean
        get() = activeSessions.get() > 0

    fun onSessionStarted(context: Context) {
        val applicationContext = context.applicationContext
        activeSessions.incrementAndGet()
        // the saves sync with the custom root in the background after the app opens, the game waits for it
        savesReadyJob = scope.launch {
            CemuDataStorage.awaitStartupSync()
            val wasDirty = AppSettingsStore.dataStore.data.first().storageSettings.isSaveMirrorDirty
            CemuDataStorage.markSavesDirty(applicationContext)
            CemuSaveSyncManager.start(applicationContext, isCustomRootInSync = !wasDirty)
        }
    }

    suspend fun awaitSavesReady() {
        savesReadyJob?.join()
    }

    fun onSessionStopped(context: Context) {
        val remainingSessions = activeSessions.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        if (remainingSessions == 0) {
            syncSavesToCustomRoot(context)
        }
    }

    fun syncSavesToCustomRoot(context: Context) {
        runBlocking { awaitSavesReady() }
        CemuSaveSyncManager.stopAndFlush(context.applicationContext)
        runBlocking {
            withContext(Dispatchers.IO) {
                CemuDataStorage.syncSavesToCustomRoot(context.applicationContext)
            }
        }
    }
}
