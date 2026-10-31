package com.ninecsdev.wallpaperchanger.logic

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereDelivery
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.policyOr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/** What one [RotationCoordinator.rotateOnce] attempt did. */
enum class RotationOutcome {
    /** Applied and on screen, the rotation advanced. */
    ROTATED,

    /**
     * Handed to the live engine, the advance runs in [RotationCoordinator.confirmDeferredDisplay] if and
     * when the engine reports the image actually shown.
     */
    HANDED_OFF,

    NOT_DONE,

    /** Refused: another rotation held the guard.*/
    ALREADY_RUNNING
}

/** The single entry point for rotating once; callers contribute their own preconditions. */
@Singleton
class RotationCoordinator @Inject constructor(
    private val repository: WallpaperRepository,
    private val rotationEngine: RotationEngine,
    private val wallpaperApplier: WallpaperApplier,
    private val appDataStore: AppDataStore,
    private val atmosphereDelivery: AtmosphereDelivery,
    private val wallpaperModeResolver: WallpaperModeResolver
) {
    companion object {
        private const val TAG = "RotationCoordinator"
        private const val SWITCH_TAG = "CollectionSwitch"

        /** Upper bound on the swap pipeline, so a wedged read cannot leave the guard latched forever. */
        const val WORK_TIMEOUT_MS = 30_000L

        /** Short, because the rotation holding the guard is expected to finish within seconds. */
        const val ALREADY_RUNNING_RETRY_DELAY_MS = 5_000L
    }

    /** Held across the apply and refill. Static rotations are covered end to end; the atmosphere path releases it at delivery. */
    private val isWorkInProgress = AtomicBoolean(false)

    /**
     * Runs at most one rotation. [onApplied] fires as soon as the image is applied or delivered, before
     * the buffer refill, so the screen-state receiver can release its broadcast early.
     */
    // TODO tests: see vault note tests/Global Rotation Policy Tests.md
    suspend fun rotateOnce(
        logTag: String,
        onApplied: () -> Unit = {}
    ): RotationOutcome = guarded(logTag) { rotateGuarded(logTag, onApplied) }

    /**
     * Shows the newly active collection's prepared image regardless of its rotation policy. Shares the
     * rotation guard, so the caller retries [RotationOutcome.ALREADY_RUNNING].
     */
    // TODO tests: see vault note tests/Collection Switch Rotation Gate Tests.md
    suspend fun applySwitch(): RotationOutcome = guarded(SWITCH_TAG) { switchGuarded() }

    private suspend fun guarded(logTag: String, work: suspend () -> RotationOutcome): RotationOutcome {
        if (!isWorkInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "$logTag: a rotation is already in progress. Skipping.")
            return RotationOutcome.ALREADY_RUNNING
        }

        return try {
            withTimeout(WORK_TIMEOUT_MS.milliseconds) { work() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "$logTag: rotation timed out after ${WORK_TIMEOUT_MS}ms. Cancelled.")
            RotationOutcome.NOT_DONE
        } catch (e: CancellationException) {
            // Not a failed rotation: the scope owning this call is in teardown
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$logTag: error during rotation", e)
            RotationOutcome.NOT_DONE
        } finally {
            isWorkInProgress.set(false)
        }
    }

    // TODO tests: see vault note tests/Collection Switch Rotation Gate Tests.md
    private suspend fun rotateGuarded(
        logTag: String,
        onApplied: () -> Unit
    ): RotationOutcome {
        val activeCollection = repository.getActiveCollectionOnce()
        if (activeCollection == null) {
            Log.w(TAG, "$logTag: no active collection. Skipping.")
            return RotationOutcome.NOT_DONE
        }

        // Read fresh at rotation time: the global policy can have changed since the trigger was armed
        val policy = activeCollection.rotationPolicy.policyOr(appDataStore.getRotationPolicy())
        if (!policy.shouldRotateAt(activeCollection.lastWallpaperChangeAt)) {
            Log.d(TAG, "$logTag: not due yet under $policy.")
            return RotationOutcome.NOT_DONE
        }

        // Pinned to the active collection as a switch re-anchors the gate to "due now"
        val applied = rotationEngine.withBufferFor(activeCollection.id) {
            wallpaperApplier.applyBufferWallpaper(activeCollection.id)
        }
        if (applied == null) {
            Log.w(TAG, "$logTag: nothing prepared for collection ${activeCollection.id} yet. Skipping.")
            return RotationOutcome.NOT_DONE
        }

        return when (applied) {
            WallpaperApplyOutcome.SHOWN -> {
                repository.markWallpaperChanged(activeCollection.id)
                onApplied()
                rotationEngine.refillDiskBuffer()
                RotationOutcome.ROTATED
            }

            WallpaperApplyOutcome.DEFERRED -> {
                onApplied()
                RotationOutcome.HANDED_OFF
            }

            // Nothing was delivered, so nothing to advance and nothing to refill.
            WallpaperApplyOutcome.ALREADY_LIVE, WallpaperApplyOutcome.FAILED-> RotationOutcome.NOT_DONE
        }
    }

    private suspend fun switchGuarded(): RotationOutcome {
        val activeCollection = repository.getActiveCollectionOnce() ?: return RotationOutcome.NOT_DONE

        // The switch zeroed it, so a stamp means a rotation has already shown this collection since
        if (activeCollection.lastWallpaperChangeAt != 0L) {
            Log.d(TAG, "$SWITCH_TAG: collection ${activeCollection.id} was already shown since the switch.")
            return RotationOutcome.NOT_DONE
        }

        val atmosphere = wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE
        // Lock-only shows nothing until the next lock, which the zeroed anchor already makes due
        if (!atmosphere && appDataStore.getWallpaperDestination() == WallpaperDestination.LOCK) {
            return RotationOutcome.NOT_DONE
        }

        val applied = rotationEngine.withBufferFor(activeCollection.id) {
            if (!atmosphere) wallpaperApplier.applyBufferWallpaper(activeCollection.id)
            else if (atmosphereDelivery.deliverPreparedNow()) WallpaperApplyOutcome.SHOWN
            else WallpaperApplyOutcome.FAILED
        }
        if (applied != WallpaperApplyOutcome.SHOWN) {
            Log.w(TAG, "$SWITCH_TAG: nothing shown for collection ${activeCollection.id} ($applied); the next trigger will.")
            return RotationOutcome.NOT_DONE
        }

        repository.markWallpaperChanged(activeCollection.id)
        rotationEngine.refillDiskBuffer()
        return RotationOutcome.ROTATED
    }

    /**
     * The deferred second half of an atmosphere rotation: the engine reports it has shown the image.
     * A [RotationOutcome.HANDED_OFF] without a call here is legitimate; a never-shown delivery must not burn a rotation.
     */
    // TODO tests: see vault note tests/Collection Switch Rotation Gate Tests.md
    suspend fun confirmDeferredDisplay() {
        // Consuming the latch is what records the delivered image as the live one
        val deliveredId = atmosphereDelivery.confirmDisplayed()

        if (deliveredId == null) {
            Log.w(TAG, "Atmosphere display with no delivery on record; not advancing.")
            return
        }

        repository.markWallpaperChanged(deliveredId)

        val active = repository.getActiveCollectionOnce()
        if (active?.id == deliveredId) rotationEngine.refillDiskBuffer()
    }
}
