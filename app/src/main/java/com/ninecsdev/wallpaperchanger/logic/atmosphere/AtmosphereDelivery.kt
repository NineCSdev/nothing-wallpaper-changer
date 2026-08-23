package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninecsdev.wallpaperchanger.logic.replaceAtomically
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereSource
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atmosphere *delivery*, the final hop of the rotation pipeline when atmosphere mode is effective.
 *
 * Delivery is a cheap file copy plus a reload broadcast; **no rendering happens here**. The buffer
 * was already rendered (zoom-fix forced off) at buffer-fill time by
 * [BufferManager.prepareNextWallpaper][com.ninecsdev.wallpaperchanger.logic.BufferManager]. Delivering it means publishing that buffer to the live
 * engine's on-disk input ([AtmosphereSource.FILE_NAME] in `filesDir`) and telling the
 * engine to re-decode it.
 *
 * The buffer is **copied, never moved**: the running engine may cold-start and re-read the source
 * at any time, while the buffer file itself keeps getting overwritten by subsequent refills. The
 * copy lands in a temp file first and is swapped in with an atomic move so the engine can never
 * decode a half-written source.
 */
@Singleton
class AtmosphereDelivery @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {
    private companion object {
        const val TAG = "AtmosphereDelivery"
        const val DELIVERY_TEMP_FILENAME = "atmosphere_delivery_temp.webp"
    }

    private fun sourceFile(): File = AtmosphereSource.file(appContext)

    /**
     * Copies [bufferFile] into the atmosphere source path atomically, then broadcasts a reload.
     * Returns false (and logs) if the buffer is missing or the copy/move fails.
     *
     * TODO tests: see vault note tests/Atmosphere Delivery Tests.md
     */
    fun deliverBuffer(bufferFile: File): Boolean {
        if (!bufferFile.exists()) {
            Log.w(TAG, "Buffer file missing; nothing to deliver to atmosphere engine.")
            return false
        }

        val tempFile = File(appContext.filesDir, DELIVERY_TEMP_FILENAME)
        return try {
            // Copy (not move) the buffer so subsequent refills can keep overwriting it freely.
            Files.copy(bufferFile.toPath(), tempFile.toPath(), REPLACE_EXISTING)
            replaceAtomically(tempFile, sourceFile())
            // Tag as a rotation delivery: its display (and only its display) advances the magazine.
            sendReload(fromRotation = true)
            Log.i(TAG, "Delivered buffer to atmosphere source.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver buffer to atmosphere source", e)
            // Cleanup belongs here rather than in a `finally`: the successful path's swap already
            // consumed the temp file, so this is reachable only after a failed copy or failed swap.
            tempFile.delete()
            false
        }
    }

    /**
     * Deletes the on-disk source once the engine is confirmed gone (the user left atmosphere mode
     * and a static wallpaper actually replaced the live one). It is a screen-sized WebP that would
     * otherwise sit in `filesDir` indefinitely; re-entering atmosphere re-renders it.
     *
     * Only call after confirming the engine is no longer the system wallpaper — a live engine
     * cold-starts by re-reading this file, so deleting it out from under one shows black.
     */
    fun clearSource() {
        val source = sourceFile()
        if (source.exists() && !source.delete()) {
            Log.w(TAG, "Could not delete the atmosphere source file.")
        }
    }

    /**
     * Signals the live engine to re-decode its on-disk source. Same-app only
     * ([AtmosphereWallpaperService.ACTION_RELOAD] is RECEIVER_NOT_EXPORTED, so the explicit package
     * keeps delivery in-app).
     *
     * [fromRotation] marks whether the reload is a rotation delivery: only then does showing it
     * advance the magazine. The revert-to-default path calls this directly with the default
     * (`false`) — it renders straight into the source file and must not count as a rotation.
     */
    fun sendReload(fromRotation: Boolean = false) {
        appContext.sendBroadcast(
            Intent(AtmosphereWallpaperService.ACTION_RELOAD)
                .setPackage(appContext.packageName)
                .putExtra(AtmosphereWallpaperService.EXTRA_FROM_ROTATION, fromRotation)
        )
    }
}
