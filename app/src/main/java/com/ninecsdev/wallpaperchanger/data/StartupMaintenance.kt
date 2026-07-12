package com.ninecsdev.wallpaperchanger.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot app-startup housekeeping, triggered from the UI's entry point but owned here so
 * the work is decoupled from any UI lifecycle. Runs on a singleton-lived scope (mirroring
 * [ServiceStateManager]) rather than a `viewModelScope`, so it isn't cancelled if the user
 * opens then immediately closes the app.
 */
@Singleton
class StartupMaintenance @Inject constructor(
    private val repository: WallpaperRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ensures the tasks run at most once per process, even if triggered from several places. */
    private val hasRun = AtomicBoolean(false)

    /**
     * Runs the startup tasks once per process. All are reconciliation sweeps kept permanently
     * (not one-time migrations), reclaiming whatever a process death left half-cleaned. Ordered
     * deliberately: the registry GC first (releases leaked picker grants and deletes orphan file
     * rows), then the grant and disk sweeps, which diff the system's persisted grants and
     * `internal_wallpapers/` against the rows that survived.
     */
    fun runOnce() {
        if (!hasRun.compareAndSet(false, true)) return
        scope.launch {
            repository.cleanupOrphanFileRegistry()
            repository.cleanupOrphanPersistedGrants()
            repository.cleanupOrphanInternalFiles()
        }
    }
}
