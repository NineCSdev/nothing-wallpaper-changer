package com.ninecsdev.wallpaperchanger.data

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot app-startup housekeeping, triggered from the UI's entry point but owned here so
 * the work is decoupled from any UI lifecycle. Runs on a singleton-lived scope (mirroring
 * [ServiceLifecycle][com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle]) rather than a
 * `viewModelScope`, so it isn't canceled if the user
 * opens then immediately closes the app.
 */
@Singleton
class StartupMaintenance @Inject constructor(
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore
) {
    private companion object {
        const val TAG = "StartupMaintenance"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ensures the tasks run at most once per process, even if triggered from several places. */
    private val hasRun = AtomicBoolean(false)

    /**
     * Runs the startup tasks once per process. Apart from the first, all are reconciliation sweeps
     * kept permanently (not one-time migrations), reclaiming whatever a process death left
     * half-cleaned. Ordered deliberately: the default-wallpaper backfill first, because until it
     * runs that file is referenced by nothing and the sweeps below would reclaim it; then the
     * registry GC (reclaims backing resources and deletes orphan file rows), then the grant,
     * MediaStore-availability, and disk sweeps, which diff the system's persisted grants, the
     * device's MediaStore, and `internal_wallpapers/` against the rows that survived.
     */
    fun runOnce() {
        if (!hasRun.compareAndSet(false, true)) return

        scope.launch {
            backfillDefaultWallpaper()
            repository.cleanupOrphanFileRegistry()
            repository.cleanupOrphanPersistedGrants()
            repository.reconcileMediaStoreAvailability()
            repository.cleanupOrphanInternalFiles()
        }
    }

    /** Moves a default wallpaper stored as a bare settings uri into the rows that now hold it. */
    // TODO: delete this and its two AppDataStore accessors in v0.4.1
    // TODO tests: see vault note tests/Default Wallpaper Membership Tests.md (backfill)
    private suspend fun backfillDefaultWallpaper() {
        val legacyUri = appDataStore.getLegacyDefaultWallpaperUri() ?: return
        if (repository.getDefaultWallpaper() != null) {
            appDataStore.clearLegacyDefaultWallpaperUri()
            return
        }

        val path = legacyUri.path
        if (path == null || !File(path).exists()) {
            Log.w(TAG, "Stored default wallpaper is missing from disk; dropping the setting")
            appDataStore.clearLegacyDefaultWallpaperUri()
            return
        }

        repository.setDefaultWallpaper(legacyUri)
        appDataStore.clearLegacyDefaultWallpaperUri()
        Log.i(TAG, "Backfilled the default wallpaper into the collection rows")
    }
}
