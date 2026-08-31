package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
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

/**
 * What entering atmosphere produced: whether a source is ready for the system picker, and whether
 * getting there cost the user their separate lock-screen wallpaper.
 */
data class AtmosphereEntry(
    val sourceReady: Boolean,
    val lockWallpaperRemoved: Boolean
)

/**
 * Owns crossing between wallpaper modes, in both directions, and keeping the live engine supplied.
 *
 * Entering has to stage a source *before* handing off to the picker, and leaving is a compensating
 * transaction that must roll the preference back if the live wallpaper cannot actually
 * be replaced.
 *
 * Three entry points, asymmetric because the transitions are:
 *
 * - [enterAtmosphere] stages the source and takes the lock screen. Cannot finish the job (only
 *   the user can) so it reports readiness and the caller fires the intent.
 * - [exitAtmosphere] is the transaction around [AtmosphereExit], and the only path that writes the
 *   preference itself, because the rollback is the whole point.
 * - [reconcile] is the repeatable one: re-snapshot liveness, and bring the delivered source up to
 *   date if the inputs it was rendered from have moved. Safe to call as often as a caller likes.
 */
// TODO tests: see vault note tests/Atmosphere Delivery Tests.md (mode-switch transaction + reconcile)
@Singleton
class AtmosphereTransition @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val repository: WallpaperRepository,
    private val modeResolver: WallpaperModeResolver,
    private val atmosphereExit: AtmosphereExit,
    private val sourceProvisioner: AtmosphereSourceProvisioner,
    private val atmosphereDelivery: AtmosphereDelivery,
    private val rotationEngine: RotationEngine
) {
    private companion object {
        const val TAG = "AtmosphereTransition"

        /** Divides a render key's image half from its framing half. */
        const val KEY_SEPARATOR = '|'
    }

    private val _liveness = MutableStateFlow(modeResolver.isAtmosphereEngineActive())

    /** Snapshot of whether NWC's engine is the system wallpaper right now, as of the last [reconcile]. */
    val liveness: StateFlow<Boolean> = _liveness.asStateFlow()

    /**
     * Whether anything exists to feed the renderer: an available image in the active collection, or
     * the user's default wallpaper as the fallback. Gates the set-atmosphere button.
     */
    val canEnter: Flow<Boolean> = combine(
        repository.activeCollectionImagesFlow(),
        appDataStore.defaultWallpaperUriFlow()
    ) { activeSnapshot, defaultUri ->
        (activeSnapshot?.second?.isNotEmpty() == true) || defaultUri != null
    }

    /**
     * Stages the source image and takes both screens (as atmosphere NEEDS both), ready for the
     * caller to hand off to the system live-wallpaper picker. Writes no preference.
     */
    suspend fun enterAtmosphere(): AtmosphereEntry {
        // Order matters: the lock screen is taken only once there is something to show. Clearing
        // first would destroy the user's lock wallpaper even on the path that then fails to
        // provision and never reaches the picker, leaving them with neither.
        if (!provisionAndRecord()) return AtmosphereEntry(sourceReady = false, lockWallpaperRemoved = false)
        return AtmosphereEntry(sourceReady = true, lockWallpaperRemoved = releaseLockScreen())
    }

    /**
     * Leaves the live wallpaper and reports what it took, as a compensating transaction over the
     * preference. A failed switch is reported as not having happened.
     *
     * Returns the raw outcome.
     */
    suspend fun exitAtmosphere(): AtmosphereExitOutcome {
        // Preference is written first and rolled back only on failure.
        appDataStore.setWallpaperMode(WallpaperMode.STATIC)

        val outcome = atmosphereExit.leave()
        if (outcome == AtmosphereExitOutcome.FAILED) {
            Log.w(TAG, "Could not replace the live wallpaper; reverting mode to ATMOSPHERE.")
            appDataStore.setWallpaperMode(WallpaperMode.ATMOSPHERE)
            return outcome
        }

        // Effective mode is STATIC from here on. No-op with the service stopped and magazine empty.
        // Non-cancellable because it is the second half of the mode write above abandoning this
        // leaves the next rotation applying a wallpaper framed for atmo in static
        withContext(NonCancellable) { rotationEngine.refillDiskBuffer() }

        _liveness.value = modeResolver.isAtmosphereEngineActive()
        // Reclaim the source file once the engine is confirmed gone
        if (!_liveness.value) {
            atmosphereDelivery.clearSource()
            appDataStore.setAtmosphereRenderKey(null)
        }
        return outcome
    }

    /**
     * Re-snapshots engine liveness and brings the world into line with it. Idempotent: callers may
     * run this on a resume, after a settings change, or on a schedule, without having to work out
     * whether anything changed first.
     *
     * Two things can be stale: The **buffer** is framed against effective mode, so it has to be
     * re-rendered when the engine goes live. The **delivered source** has to be re-rendered when the inputs it was
     * produced from move: a different image, edited differently, or a changed zoom fix.
     * Comparing [renderKey] against the stored one answers that.
     */
    suspend fun reconcile() {
        val wasLive = _liveness.value
        val isLive = modeResolver.isAtmosphereEngineActive()
        _liveness.value = isLive
        if (!isLive) return

        val current = renderKey()
        val stored = appDataStore.getAtmosphereRenderKey()
        if (current != stored) {
            if (stored != null && current.namesSameImageAs(stored)) {
                // Same image, different settings: draw it again
                if (sourceProvisioner.reprovisionLive()) {
                    appDataStore.setAtmosphereRenderKey(renderKey())
                }
            } else {
                // A different image is live and already rendered under the current settings. Nothing to redraw.
                appDataStore.setAtmosphereRenderKey(current)
            }
        }

        // The buffer is framed against effective mode, so it is only wrong across this edge.
        if (!wasLive && appDataStore.getWallpaperMode() == WallpaperMode.ATMOSPHERE) {
            rotationEngine.refillDiskBuffer()
        }
    }

    /**
     * Renders and delivers a freshly-picked source, then records what it was rendered from.
     *
     * For the paths with nothing on screen worth preserving: entry, and recovery from having no source.
     */
    private suspend fun provisionAndRecord(): Boolean {
        if (!sourceProvisioner.provision()) return false
        appDataStore.setAtmosphereRenderKey(renderKey())
        return true
    }

    /**
     * The inputs the currently-delivered source was rendered from, as a comparable string: which
     * image, how it is edited and framed, and the zoom fix used. Two renders that would produce
     * the same pixels share a key.
     *
     * Split by [KEY_SEPARATOR] into *which image* and *how it was framed*.
     */
    private suspend fun renderKey(): String {
        val zoomFix = appDataStore.getWallpaperZoomFix().name
        val wallpaperId = appDataStore.getAtmosphereLiveWallpaperId()
            ?: return "default$KEY_SEPARATOR${WallpaperImage.DEFAULT_WALLPAPER_CROP_RULE}|$zoomFix" + "|${appDataStore.getDefaultWallpaperUri().hashCode()}"

        val wallpaper = repository.getWallpaperById(wallpaperId) ?: return "missing:$wallpaperId$KEY_SEPARATOR$zoomFix"
        val cropRule = repository.getCollectionById(wallpaper.collectionId)?.defaultCropRule ?: WallpaperCollection.DEFAULT_CROP_RULE
        val edit = wallpaper.editParams
        val editKey = if (edit == null) "none" else "${edit.zoom},${edit.offsetX},${edit.offsetY}"

        return "$wallpaperId$KEY_SEPARATOR$editKey|$cropRule|$zoomFix"
    }

    /** Whether two render keys name the same image, ignoring how it was framed. */
    private fun String.namesSameImageAs(other: String): Boolean =
        substringBefore(KEY_SEPARATOR) == other.substringBefore(KEY_SEPARATOR)

    /**
     * Removes any separate lock-screen wallpaper so the engine receives both screens, reporting
     * whether there was one to remove.
     *
     * Runs on [Dispatchers.IO]: clearing makes system_server delete the lock wallpaper, reset its
     * bitmap and notify listeners.
     */
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
