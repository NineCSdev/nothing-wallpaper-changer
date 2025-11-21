package com.ninecsdev.wallpaperchanger

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean


/**
 * A BroadcastReceiver that listens for the `Intent.ACTION_SCREEN_OFF` system event.
 * Its primary role is to trigger the lock screen wallpaper change. It performs this work
 * on a background thread to avoid blocking the main thread and uses concurrency controls
 * to prevent race conditions from multiple rapid screen-off events.
 */
class ScreenOffReceiver : BroadcastReceiver() {

    private val tag = "ScreenOffReceiver"

    /**
     * The companion object holds static properties to manage concurrency across all instances
     * of the receiver. This is crucial because the system may create new receiver instances
     * for each broadcast.
     */
    companion object {
        // An atomic flag to ensure that only one wallpaper-changing operation can run at a time.
        // This prevents issues if the screen is turned off and on very quickly.
        private val isWorkInProgress = AtomicBoolean(false)
        // A lock object to synchronize access to the wallpaper setting logic,
        // ensuring that getting the next image and preloading are an atomic operation from the perspective of this receiver.
        private val wallpaperLock = Any()
    }

    /**
     * This method is called when the BroadcastReceiver receives an Intent broadcast.
     * It specifically handles the `ACTION_SCREEN_OFF` intent.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received, which should contain the screen-off action.
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        // 1. Basic validation: ensure we have a context and the correct intent action.
        if (context == null || intent?.action != Intent.ACTION_SCREEN_OFF) {
            return
        }

        // 2. Concurrency check: Atomically check if work is already running.
        // If 'isWorkInProgress' is false, set it to true and proceed. If it's already true, exit.
        if (!isWorkInProgress.compareAndSet(false, true)) {
            Log.d(tag, "Work is already in progress. Skipping this trigger.")
            return
        }

        Log.d(tag, "Acquired lock. Starting background work.")
        // Inform the system that we are performing asynchronous work, preventing the process from being killed.
        val pendingResult = goAsync()
        // Get the PowerManager, as it's more reliable for checking interactivity.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 3. Perform all heavy work on a background thread.
        Thread {
            try {
                // Small delay to make sure the fade to black animation on lock has finished.
                // 150ms is the "Sweet Spot" for Nothing Phone (1).
                // It is fast enough to feel responsive but covers the visual animation
                // to prevent the user from seeing the wallpaper swap.
                Thread.sleep(150)

                Log.d(tag, "Screen is no longer interactive. Proceeding to change wallpaper.")

                // 4. Synchronize the core logic to prevent race conditions.
                synchronized(wallpaperLock) {
                    // Get the URI for the next wallpaper from the static methods in WallpaperService.
                    val nextImageUri = WallpaperService.getNextImage()

                    if (nextImageUri != null) {
                        // If we got a valid URI, set the wallpaper.
                        setLockScreenWallpaper(context, nextImageUri, powerManager)
                        // After setting, immediately start preloading the *next* image for the *next* screen-off event.
                        WallpaperService.preloadNextImage(context)
                    } else {
                        // This may happen if the cache is empty (e.g., no folder selected or accessible).
                        Log.w(tag, "Service returned no image. Is a folder selected?")
                    }
                }

            } catch (e: Exception) {
                Log.e(tag, "Error during wallpaper change work", e)
            } finally {
                // 5. Cleanup: Always release the lock and finish the async task.
                isWorkInProgress.set(false)
                pendingResult.finish()
                Log.d(tag, "Background work finished. Lock released.")
            }
        }.start()
    }

    /**
     * Handles the logic of decoding and setting the wallpaper bitmap.
     * It prioritizes using a preloaded bitmap for speed but falls back to decoding from the URI if needed.
     *
     * @param context The application context.
     * @param imageUri The URI of the image to set as the wallpaper.
     * @param powerManager The PowerManager instance to perform a final interactivity check.
     */
    private fun setLockScreenWallpaper(context: Context, imageUri: Uri, powerManager: PowerManager) {
        try {
            var bitmap = WallpaperService.getPreloadedBitmap()

            if (bitmap != null) {
                Log.d(tag, "Using preloaded bitmap for faster wallpaper change")
            } else {
                // Fallback: If no preloaded bitmap is available (e.g., first run or cache was cleared),
                // decode it from the content URI now. This is a slower operation.
                Log.d(tag, "No preloaded bitmap, loading from URI")
                context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    bitmap = BitmapFactory.decodeStream(inputStream)
                }
            }

            // FINAL SAFETY CHECK: Abort the operation if the user turned the screen back on
            // while the bitmap was being decoded. This prevents the wallpaper from changing
            // unexpectedly after the screen is already on.
            if (powerManager.isInteractive) {
                Log.w(tag, "FINAL CHECK FAILED: Screen became interactive while bitmap was decoding. Aborting.")
                return
            }

            if (bitmap != null) {
                val wallpaperManager = WallpaperManager.getInstance(context)
                // Set the bitmap only on the lock screen.
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                Log.i(tag, "Wallpaper set successfully!")
            } else {
                Log.w(tag, "Failed to decode bitmap from Uri: $imageUri")
            }
        } catch (e: IOException) {
            Log.e(tag, "Could not set wallpaper", e)
        }
    }
}
