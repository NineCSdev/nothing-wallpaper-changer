package com.ninecsdev.wallpaperchanger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.WallpaperApplier
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.shouldRotateAt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class ScreenOffReceiver(
    /**
     * Structured scope provided by the owning [WallpaperService].
     * Using the service's scope ensures coroutines are canceled when the
     * service is destroyed, preventing leaked work.
     */
    private val serviceScope: CoroutineScope
) : BroadcastReceiver() {

    private val tag = "ScreenOffReceiver"

    @Inject lateinit var repository: WallpaperRepository
    @Inject lateinit var appDataStore: AppDataStore
    @Inject lateinit var rotationEngine: RotationEngine
    @Inject lateinit var wallpaperApplier: WallpaperApplier

    companion object {
        // Prevents multiple concurrent swaps if the power button is clicked many times
        private val isWorkInProgress = AtomicBoolean(false)

        /**
         * Upper bound on the whole swap pipeline. [withTimeout] cancels the coroutine body if it
         * exceeds this, so a wedged read (e.g. a stuck ContentResolver) can't leave [isWorkInProgress]
         * latched forever. Replaces the old force-reset watchdog, which reset the flag without
         * cancelling the stuck work (risking two concurrent pipelines).
         */
        private const val WORK_TIMEOUT_MS = 30_000L
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_SCREEN_OFF) return

        if (!isWorkInProgress.compareAndSet(false, true)) {
            Log.d(tag, "Work already in progress. Skipping.")
            return
        }

        val pendingResult = goAsync()
        val broadcastFinished = AtomicBoolean(false)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        serviceScope.launch(Dispatchers.IO) {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    // Configurable delay (default 250ms for Nothing Phone animation)
                    val delayMs = appDataStore.getScreenOffDelay()
                    delay(delayMs)

                    // Safety check: if the user woke the screen during the delay abort
                    if (powerManager.isInteractive) {
                        Log.w(tag, "Screen woke up. Aborting.")
                        return@withTimeout
                    }

                    val activeCollection = repository.getActiveCollectionOnce()
                    if (activeCollection == null) {
                        Log.w(tag, "No active collection found. Skipping wallpaper change.")
                        return@withTimeout
                    }

                    if (!activeCollection.shouldRotateAt()) {
                        val frequencyLabel = when (activeCollection.rotationFrequency) {
                            RotationFrequency.PER_LOCK -> "per lock"
                            RotationFrequency.HOURLY -> "hourly"
                            RotationFrequency.PER_DAY -> "daily"
                        }
                        Log.d(tag, "Rotation skipped. Timer for $frequencyLabel not met yet.")
                        return@withTimeout
                    }

                    // Apply the pre-processed buffer image and prepare next image
                    val applied = wallpaperApplier.applyBufferWallpaper()
                    if (applied) {
                        repository.markWallpaperChanged(activeCollection.id)
                        if (broadcastFinished.compareAndSet(false, true)) pendingResult.finish()
                        rotationEngine.refillDiskBuffer()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(tag, "Wallpaper change timed out after ${WORK_TIMEOUT_MS}ms. Cancelled.")
            } catch (e: Exception) {
                Log.e(tag, "Error during wallpaper change", e)
            } finally {
                isWorkInProgress.set(false)
                if (broadcastFinished.compareAndSet(false, true)) pendingResult.finish()
            }
        }
    }

}
