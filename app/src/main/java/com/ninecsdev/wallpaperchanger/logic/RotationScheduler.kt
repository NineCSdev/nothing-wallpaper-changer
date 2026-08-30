package com.ninecsdev.wallpaperchanger.logic

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The time-driven rotation trigger: fires when a time-based policy comes due **while the screen is
 * on**, complementing the screen-off trigger.
 *
 * A gate at screen-off already covers everything seen at unlock. What it cannot cover is the home
 * screen, which is what an "every X minutes" setting is actually asking for, and the only case this
 * serves. The anchor is wall-clock, so time spent with the screen off still counts and no wakeup is
 * ever scheduled.
 *
 * It never decides *whether* a rotation is allowed — only when to ask. [RotationCoordinator]
 * re-evaluates the same policy on arrival, so a stale wake is harmless.
 */
// TODO tests: see vault note tests/Global Rotation Policy Tests.md
@Singleton
class RotationScheduler @Inject constructor(
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    private val rotationPolicyResolver: RotationPolicyResolver,
    private val rotationCoordinator: RotationCoordinator,
    private val wallpaperModeResolver: WallpaperModeResolver
) {
    private companion object {
        const val TAG = "RotationScheduler"

        /**
         * Longest single sleep before re-reading the clock. Sleeps are relative, so a long one would
         * not notice the user changing the system time or timezone under it.
         */
        const val MAX_SLEEP_MS = 15 * 60 * 1000L

        /** Backoff after an attempt that did not rotate, so a persistent failure cannot spin. */
        const val RETRY_DELAY_MS = 60 * 1000L
    }

    /** Fed by the owning service's screen-state receiver. */
    private val screenOn = MutableStateFlow(true)

    private var job: Job? = null

    /**
     * Everything an arm depends on. Any change cancels the pending wait and recomputes — including
     * `lastWallpaperChangeAt`, so the stamp written by a completed rotation is what schedules the
     * next one.
     */
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
                repository.activeCollectionFlow(),
                appDataStore.rotationPolicyFlow(),
                appDataStore.wallpaperDestinationFlow(),
                appDataStore.wallpaperModeFlow(),
                screenOn,
                ::Arm
            ).collectLatest { arm -> awaitAndRotate(arm) }
        }
    }

    /** Safe to call when not started. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun awaitAndRotate(arm: Arm) {
        val collection = arm.collection ?: return
        if (!arm.screenOn) return

        // With the lock-only destination the image matters at the next unlock, which the screen-off
        // trigger already owns: a tick would burn an image and re-anchor, so the rotation the user
        // would have seen gets skipped instead.
        if (arm.destination == WallpaperDestination.LOCK) return

        // Atmosphere holds its source until the panel is dark and morphs on unlock, so a mid-session
        // tick would be invisible. This is also load-bearing for concurrency, not just for UX:
        // RotationCoordinator releases its guard at atmosphere delivery, and the deferred half runs
        // unguarded until the engine confirms display. Nothing can interleave there only because
        // this gate keeps the scheduler out of atmosphere mode entirely. Relaxing it reopens that
        // window.
        if (wallpaperModeResolver.effectiveMode() != WallpaperMode.STATIC) return

        val policy = rotationPolicyResolver.resolve(collection.rotationPolicy, arm.globalPolicy)

        while (true) {
            // Recomputed every pass: a chunked sleep may have crossed a clock or timezone change.
            val dueAt = policy.nextDueAt(collection.lastWallpaperChangeAt) ?: return
            val remaining = dueAt - System.currentTimeMillis()

            if (remaining > 0) {
                delay(remaining.coerceAtMost(MAX_SLEEP_MS))
                if (remaining > MAX_SLEEP_MS) continue
            }

            when (val outcome = rotationCoordinator.rotateOnce(TAG)) {
                // The timestamp write re-emits the active collection, cancelling this body and
                // re-arming with the new anchor.
                RotationOutcome.ROTATED, RotationOutcome.HANDED_OFF -> return

                RotationOutcome.NOT_DONE -> {
                    Log.d(TAG, "Timed rotation did not happen ($outcome); retrying in ${RETRY_DELAY_MS}ms.")
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }
}
