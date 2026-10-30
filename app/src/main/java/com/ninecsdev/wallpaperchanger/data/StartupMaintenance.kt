package com.ninecsdev.wallpaperchanger.data

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** App-startup housekeeping. Runs on [ApplicationScope] so closing the app right after opening it does not cancel it. */
@Singleton
class StartupMaintenance @Inject constructor(
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    @param:ApplicationScope private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "StartupMaintenance"
    }

    private val hasRun = AtomicBoolean(false)

    /**
     * Until the backfill has moved the default wallpaper into rows, nothing references its file and
     * [WallpaperRepository.reconcileStorage] would reclaim it, so a failed backfill skips reconciliation.
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
