package com.ninecsdev.wallpaperchanger.logic

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies prepared images to the Android (lock for now) screen wallpaper.
 */
@Singleton
class WallpaperApplier @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val bufferManager: BufferManager
) {
    private companion object {
        const val TAG = "WallpaperApplier"
    }

    suspend fun applyDefaultWallpaper(): Boolean = withContext(Dispatchers.IO) {
        val uri = appDataStore.getDefaultWallpaperUri() ?: return@withContext false

        try {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return@withContext false
                var processed = bitmap

                try {
                    processed = bufferManager.applyZoomFixIfNeeded(bitmap)
                    WallpaperManager.getInstance(appContext).setBitmap(
                        processed,
                        null,
                        true,
                        WallpaperManager.FLAG_LOCK
                    )

                    Log.i(TAG, "Successfully applied default wallpaper.")
                    true
                } finally {
                    if (processed !== bitmap) processed.recycle()
                    bitmap.recycle()
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply default wallpaper", e)
            false
        }
    }

    suspend fun applyBufferWallpaper(): Boolean = withContext(Dispatchers.IO) {
        try {
            val bufferFile = bufferManager.getBufferFile()

            if (!bufferFile.exists()) {
                Log.w(TAG, "Buffer file missing. Is the service initialized?")
                return@withContext false
            }

            bufferFile.inputStream().use { stream ->
                WallpaperManager.getInstance(appContext).setStream(
                    stream,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )
            }

            Log.i(TAG, "Wallpaper applied successfully from disk buffer.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream buffer to lock screen", e)
            false
        }
    }
}
