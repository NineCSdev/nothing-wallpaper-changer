package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AtmosphereEntry(
    val sourceReady: Boolean,
    val lockWallpaperRemoved: Boolean
)

/**
 * Crosses between wallpaper modes and keeps the live engine supplied. Entering must stage a source
 * *before* handing off to the picker; [exitAtmosphere] is the only path that writes the preference,
 * rolling it back if the live wallpaper cannot be replaced; [reconcile] is idempotent.
 */
// TODO tests: see vault note tests/Atmosphere Delivery Tests.md (mode-switch transaction + reconcile)
@Singleton
class AtmosphereTransition @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val repository: WallpaperRepository,
    private val modeResolver: WallpaperModeResolver,
    private val atmosphereExit: AtmosphereExit,
    private val sourceProvisioner: AtmosphereSourceProvisioner,
    private val atmosphereDelivery: AtmosphereDelivery,
    private val rotationEngine: RotationEngine
) {
    private companion object {
        const val TAG = "AtmosphereTransition"

        const val KEY_SEPARATOR = '|'
    }

    private val _liveness = MutableStateFlow<Boolean?>(null)

    /** As of the last [reconcile]; null until the first one runs. */
    val liveness: StateFlow<Boolean?> = _liveness.asStateFlow()

    /** Whether anything exists to feed the renderer. Gates the set-atmosphere button. */
    val canEnter: Flow<Boolean> = combine(
        repository.activeCollectionImagesFlow(),
        repository.defaultWallpaperFlow()
    ) { activeSnapshot, default ->
        (activeSnapshot?.second?.isNotEmpty() == true) || default?.isAvailable == true
    }

    /** Stages the source and clears the lock screen for the caller's picker hand-off; writes no preference. */
    suspend fun enterAtmosphere(): AtmosphereEntry {
        // Only clear the lockscreen if a source was produced
        if (!provisionAndRecord()) return AtmosphereEntry(sourceReady = false, lockWallpaperRemoved = false)
        return AtmosphereEntry(sourceReady = true, lockWallpaperRemoved = releaseLockScreen())
    }

    /** A failed switch rolls the preference back and is reported as not having happened. */
    suspend fun exitAtmosphere(): AtmosphereExitOutcome {
        // Written first, rolled back only on failure
        appDataStore.setWallpaperMode(WallpaperMode.STATIC)

        val outcome = atmosphereExit.leave()
        if (outcome == AtmosphereExitOutcome.FAILED) {
            Log.w(TAG, "Could not replace the live wallpaper; reverting mode to ATMOSPHERE.")
            appDataStore.setWallpaperMode(WallpaperMode.ATMOSPHERE)
            return outcome
        }

        // Non-cancellable: it is the second half of the mode write above, and abandoning it leaves
        // the next rotation applying an atmosphere-framed wallpaper in static
        withContext(NonCancellable) { rotationEngine.refillDiskBuffer() }

        val stillLive = modeResolver.isAtmosphereEngineActive()
        _liveness.value = stillLive
        if (!stillLive) {
            atmosphereDelivery.clearSource()
            wallpaperRecordStore.setAtmosphereRenderKey(null)
        }
        return outcome
    }

    /**
     * The buffer is framed against effective mode, so it is re-rendered when the engine goes live.
     * The delivered source is re-rendered when [renderKey] no longer matches the stored one.
     */
    suspend fun reconcile() {
        val wasLive = _liveness.value
        val isLive = modeResolver.isAtmosphereEngineActive()
        _liveness.value = isLive
        if (!isLive) return

        val current = renderKey()
        val stored = wallpaperRecordStore.snapshot().atmosphereRenderKey
        if (current != stored) {
            if (stored != null && current.namesSameImageAs(stored)) {
                if (sourceProvisioner.reprovisionLive()) {
                    wallpaperRecordStore.setAtmosphereRenderKey(renderKey())
                }
            } else {
                // A different image is live, already rendered under the current settings
                wallpaperRecordStore.setAtmosphereRenderKey(current)
            }
        }

        if (wasLive == false && appDataStore.getWallpaperMode() == WallpaperMode.ATMOSPHERE) {
            rotationEngine.refillDiskBuffer()
        }
    }

    /** For paths with nothing on screen worth preserving: entry, and recovery from having no source. */
    private suspend fun provisionAndRecord(): Boolean {
        if (!sourceProvisioner.provision()) return false
        wallpaperRecordStore.setAtmosphereRenderKey(renderKey())
        return true
    }

    /** Image, edit, framing and zoom fix the delivered source was rendered from; the part before [KEY_SEPARATOR] names the image. */
    private suspend fun renderKey(): String {
        val zoomFix = appDataStore.getWallpaperZoomFix().name
        val wallpaperId = wallpaperRecordStore.snapshot().liveAtmosphereWallpaperId ?: return "none$KEY_SEPARATOR$zoomFix"

        val wallpaper = repository.getWallpaperById(wallpaperId) ?: return "missing:$wallpaperId$KEY_SEPARATOR$zoomFix"
        val cropRule = repository.cropRuleFor(wallpaper)
        val edit = wallpaper.editParams
        val editKey = if (edit == null) "none" else "${edit.zoom},${edit.offsetX},${edit.offsetY}"

        return "$wallpaperId$KEY_SEPARATOR$editKey|$cropRule|$zoomFix"
    }

    private fun String.namesSameImageAs(other: String): Boolean = substringBefore(KEY_SEPARATOR) == other.substringBefore(KEY_SEPARATOR)

    /** Returns whether there was a separate lock wallpaper to remove. Blocking, so on [Dispatchers.IO]. */
    private suspend fun releaseLockScreen(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val manager = WallpaperManager.getInstance(appContext)
            if (manager.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0) return@runCatching false
            manager.clear(WallpaperManager.FLAG_LOCK)
            Log.i(TAG, "Cleared the separate lock wallpaper; the engine now owns both screens")
            true
        }.onFailure { Log.w(TAG, "Could not clear the lock wallpaper", it) }.getOrDefault(false)
    }
}
