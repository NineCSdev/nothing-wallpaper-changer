package com.ninecsdev.wallpaperchanger.data

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import kotlinx.coroutines.CancellationException
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
     * Runs the startup tasks once per process, in the one order that matters: the backfill migrates
     * the default wallpaper into rows, and until it has, that file is referenced by nothing and
     * [WallpaperRepository.reconcileStorage] would reclaim it. So the backfill failing skips
     * reconciliation entirely rather than letting it run against state the migration still owns.
     */
    // TODO tests: check "tests/Storage Reconcile Tests" note (the backfill gate)
    fun runOnce() {
        if (!hasRun.compareAndSet(false, true)) return

        scope.launch {
            try {
                backfillDefaultWallpaper()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Retried next launch. Reconciliation is skipped
                Log.e(TAG, "Default-wallpaper backfill failed, skipping reconciliation", e)
                return@launch
            }
            repository.reconcileStorage()
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

        repository.setDefaultWallpaper(legacyUri.toString())
        appDataStore.clearLegacyDefaultWallpaperUri()
        Log.i(TAG, "Backfilled the default wallpaper into the collection rows")
    }
}
