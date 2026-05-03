package com.ninecsdev.wallpaperchanger.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.shouldRotateAt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class ScreenOffReceiver : BroadcastReceiver() {

    private val tag = "ScreenOffReceiver"

    @Inject lateinit var repository: WallpaperRepository
    @Inject lateinit var rotationEngine: RotationEngine
    @Inject lateinit var bufferManager: BufferManager

    companion object {
        // Prevents multiple concurrent swaps if the power button is clicked many times
        private val isWorkInProgress = AtomicBoolean(false)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_SCREEN_OFF) return

        if (!isWorkInProgress.compareAndSet(false, true)) {
            Log.d(tag, "Work already in progress. Skipping.")
            return
        }

        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Configurable delay (default 250ms for Nothing Phone animation)
                val delayMs = repository.getScreenOffDelay()
                delay(delayMs)

                // Safety check: if the user woke the screen during the delay abort
                if (powerManager.isInteractive) {
                    Log.w(tag, "Screen woke up. Aborting.")
                    return@launch
                }

                val activeCollection = repository.getActiveCollectionOnce()
                if (activeCollection == null) {
                    Log.w(tag, "No active collection found. Skipping wallpaper change.")
                    return@launch
                }

                if (!activeCollection.shouldRotateAt()) {
                    val frequencyLabel = when (activeCollection.rotationFrequency) {
                        RotationFrequency.PER_LOCK -> "per lock"
                        RotationFrequency.HOURLY -> "hourly"
                        RotationFrequency.PER_DAY -> "daily"
                    }
                    Log.d(tag, "Rotation skipped. Timer for $frequencyLabel not met yet.")
                    return@launch
                }

                // Apply the pre-processed buffer image and prepare next image
                val applied = applyBufferToLockScreen(context)
                if (applied) {
                    repository.markWallpaperChanged(activeCollection.id)
                    rotationEngine.refillDiskBuffer()
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during wallpaper change", e)
            } finally {
                isWorkInProgress.set(false)
                pendingResult.finish()
            }
        }
    }

    /**
     * Reads the .webp buffer from disk and streams it to the WallpaperManager.
     * This bypasses the Bitmap heap, preventing OutOfMemory errors
     * and instantly changes the wallpaper.
     */
    private fun applyBufferToLockScreen(context: Context): Boolean {
        try {
            val bufferFile = bufferManager.getBufferFile()

            if (!bufferFile.exists()) {
                Log.w(tag, "Buffer file missing. Is the service initialized?")
                return false
            }

            bufferFile.inputStream().use { stream ->
                val wallpaperManager = WallpaperManager.getInstance(context)
                wallpaperManager.setStream(
                    stream,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )
            }
            Log.i(tag, "Wallpaper applied successfully from disk buffer.")
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to stream buffer to lock screen", e)
            return false
        }
    }
}
