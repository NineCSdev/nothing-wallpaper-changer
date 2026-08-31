package com.ninecsdev.wallpaperchanger.logic

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What one [RotationCoordinator.rotateOnce] attempt did. */
enum class RotationOutcome {
    /** Applied and on screen, the rotation advanced. */
    ROTATED,

    /**
     * Handed to the live engine, the advance runs in [RotationCoordinator.confirmDeferredDisplay] if and
     * when the engine reports the image actually shown.
     */
    HANDED_OFF,

    /** Nothing rotated. */
    NOT_DONE
}

/**
 * The single entry point for "rotate the wallpaper once", owning the whole sequence: the
 * concurrency guard, the policy gate, the apply, the timestamp write and the buffer refill.
 *
 * Trigger-agnostic. Callers contribute their own preconditions. `logTag` is only a label for the log line.
 */
@Singleton
class RotationCoordinator @Inject constructor(
    private val repository: WallpaperRepository,
    private val rotationEngine: RotationEngine,
    private val wallpaperApplier: WallpaperApplier,
    private val rotationPolicyResolver: RotationPolicyResolver,
    private val atmosphereDelivery: AtmosphereDelivery
) {
    companion object {
        private const val TAG = "RotationCoordinator"

        /** Upper bound on the swap pipeline, so a wedged read cannot leave the guard latched forever. */
        const val WORK_TIMEOUT_MS = 30_000L
    }

    /**
     * Held across the apply and the refill of a single rotation.
     *
     * Static rotations are covered end to end. The atmosphere path releases it at *delivery*.
     */
    private val isWorkInProgress = AtomicBoolean(false)

    /**
     * Runs at most one rotation. [onApplied] fires as soon as the image has been applied or
     * delivered, before the buffer refill ([ScreenStateReceiver][com.ninecsdev.wallpaperchanger.service.ScreenStateReceiver] uses it to release its
     * broadcast early).
     */
    // TODO tests: see vault note tests/Global Rotation Policy Tests.md
    suspend fun rotateOnce(
        logTag: String,
        onApplied: () -> Unit = {}
    ): RotationOutcome {
        if (!isWorkInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "$logTag: a rotation is already in progress. Skipping.")
            return RotationOutcome.NOT_DONE
        }

        return try {
            withTimeout(WORK_TIMEOUT_MS) { rotateGuarded(logTag, onApplied) }
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

        val policy = rotationPolicyResolver.effectivePolicyFor(activeCollection)
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
                // On screen as of now, so advance the rotation now.
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

    /**
     * The deferred second half of an atmosphere rotation: called when the engine reports that it has
     * actually *shown* the image it was handed. A delivery that is never displayed must never burn a
     * rotation.
     *
     * Nothing enforces the pairing with [RotationOutcome.HANDED_OFF] as "the engine never showed it"
     * is a legitimate ending, not a leak.
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
