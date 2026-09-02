package com.ninecsdev.wallpaperchanger.logic

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.policyOr
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The time-driven rotation trigger: fires when a time-based policy comes due **while the screen is
 * on**, complementing the screen-off trigger. The anchor is wall-clock, so time spent with the screen
 * off still counts and no wakeup is ever scheduled.
 *
 * It never decides *whether* a rotation is allowed, only when to ask. [RotationCoordinator]
 * re-evaluates the same policy on arrival, so a stale wake is harmless.
 */
// TODO tests: see vault note tests/Global Rotation Policy Tests.md
@Singleton
class RotationScheduler @Inject constructor(
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    private val rotationCoordinator: RotationCoordinator,
    private val wallpaperModeResolver: WallpaperModeResolver
) {
    private companion object {
        const val TAG = "RotationScheduler"

        /** Longest single sleep before re-reading the clock. */
        const val MAX_SLEEP_MS = 15 * 60 * 1000L

        /** Backoff after an attempt that did not rotate, so a persistent failure cannot spin. */
        const val RETRY_DELAY_MS = 60 * 1000L

        /** Backoff after being refused by the guard. Short, because the rotation holding it is expected to finish within seconds.*/
        const val ALREADY_RUNNING_RETRY_DELAY_MS = 5 * 1000L
    }

    /** Fed by the owning service's screen-state receiver. */
    private val screenOn = MutableStateFlow(true)

    private var job: Job? = null

    /** Everything an arm depends on. Any change cancels the pending wait and recomputes */
    private data class Arm(
        val collection: WallpaperCollection?,
        val globalPolicy: RotationPolicy,
        val destination: WallpaperDestination,
        val desiredMode: WallpaperMode,
        val screenOn: Boolean
    )

    fun setScreenOn(on: Boolean) {
        screenOn.value = on
    }

    /** Idempotent: cancels any previous watch first, so pause/resume can call it freely. */
    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            combine(
                // Only these three columns change what to wait for
                repository.activeCollectionFlow().distinctUntilChangedBy { Triple(it?.id, it?.rotationPolicy, it?.lastWallpaperChangeAt) },
                appDataStore.rotationPolicyFlow(),
                appDataStore.wallpaperDestinationFlow(),
                appDataStore.wallpaperModeFlow(),
                screenOn,
                ::Arm
            ).collectLatest { arm -> awaitAndRotate(arm, scope) }
        }
    }

    /** Stops arming. Safe to call when not started, and deliberately does **not** abort a running rotation */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun awaitAndRotate(arm: Arm, scope: CoroutineScope) {
        val collection = arm.collection ?: return
        if (!arm.screenOn) return

        // With the lock-only the image matters at the next unlock, which the screen-off trigger already owns
        if (arm.destination == WallpaperDestination.LOCK) return

        // Atmosphere holds its source until the panel is dark and morphs on unlock
        if (wallpaperModeResolver.effectiveMode() != WallpaperMode.STATIC) return

        val policy = collection.rotationPolicy.policyOr(arm.globalPolicy)

        while (true) {
            // Recomputed every pass (clock or timezone might have change)
            val dueAt = policy.nextDueAt(collection.lastWallpaperChangeAt) ?: return
            val remaining = dueAt - System.currentTimeMillis()

            if (remaining > 0) {
                delay(remaining.coerceAtMost(MAX_SLEEP_MS))
                if (remaining > MAX_SLEEP_MS) continue
            }

            // Launched into the owning scope as the rotation writes the very timestamp this collector watches.
            // Awaiting keeps the retry below; cancelling the await abandons the result, never the rotation.
            // Only WallpaperService.onDestroy can cancel that.
            when (val outcome = scope.async { rotationCoordinator.rotateOnce(TAG) }.await()) {
                // The timestamp write re-emits the active collection, cancelling this and re-arming with the new anchor
                RotationOutcome.ROTATED, RotationOutcome.HANDED_OFF -> return

                RotationOutcome.NOT_DONE -> {
                    Log.d(TAG, "Timed rotation did not happen ($outcome); retrying in ${RETRY_DELAY_MS}ms.")
                    delay(RETRY_DELAY_MS)
                }

                // Looping re-reads the anchor: once the rotation holding the guard stamps it, the
                // next pass simply sleeps until the new due time.
                RotationOutcome.ALREADY_RUNNING -> delay(ALREADY_RUNNING_RETRY_DELAY_MS)
            }
        }
    }
}
